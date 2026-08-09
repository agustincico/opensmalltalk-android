#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <stdlib.h>
#include <string.h>
#include <pthread.h>
#include <unistd.h> // Para chdir(), pipe() y dup2()
#include <stdio.h>  // Para FILE*
#include <sys/socket.h>
#include <sys/un.h>

#define LOG(...) __android_log_print(ANDROID_LOG_ERROR, "SQUEAK", __VA_ARGS__)
#define LOG_VM(...) __android_log_print(ANDROID_LOG_INFO, "SQUEAK_VM", __VA_ARGS__) // Log para la VM

typedef int (*squeak_main_t)(int argc, char **argv);

static char last_error[4096] = "";
static squeak_main_t g_squeak_main = NULL;
static char g_image_path[512] = "";
static char g_lib_dir[512] = "";    // Directorio de la librería nativa del APK
static char g_files_dir[512] = ""; // Directorio de archivos de la app (/data/data/pkg/files)

/*
 * Append to last_error, TRUNCATING instead of overflowing.
 *
 * This used to be a plain strcat() into a fixed 2048-byte buffer, called ~16
 * times plus 3x per preloaded dependency (there are 40+). Once the accumulated
 * text passed the end of the buffer — which a few failing dlopen()s (their
 * dlerror() text is long) reliably caused — Android's FORTIFY killed the whole
 * process with SIGABRT *while the VM was starting*, i.e. the app crashed on
 * launch. Truncating keeps the diagnostics without ever aborting.
 */
static void err_append(const char *s) {
    if (!s) return;
    size_t len = strlen(last_error);
    if (len + 1 >= sizeof(last_error)) return;   // full — drop silently
    snprintf(last_error + len, sizeof(last_error) - len, "%s", s);
}


// --- 1. FUNCIÓN PARA REDIRECCIONAR STDOUT/STDERR AL LOGCAT ---



void* log_redirect_thread(void* arg) {
    int read_fd = (int)(intptr_t)arg;
    char buffer[1024];

    FILE* pipe_stream = fdopen(read_fd, "r");
    if (pipe_stream == NULL) {
        LOG("log_redirect_thread: fdopen falló");
        close(read_fd);
        return NULL;
    }

    // Leer línea por línea
    while (fgets(buffer, sizeof(buffer), pipe_stream) != NULL) {
        // Enviar al log de Android
        // Nota: fgets incluye el salto de línea, LOG_VM lo maneja.
        LOG_VM("%s", buffer);
    }

    LOG("log_redirect_thread: Saliendo...");
    fclose(pipe_stream); // Esto también cierra read_fd
    return NULL;
}


// --- 2. HILO DE EJECUCIÓN DE LA VM ---

void* run_squeak_thread(void* arg) {
    LOG("Thread Squeak iniciado");
    setenv("DISPLAY", "127.0.0.1:0", 1);
    LOG("DISPLAY establecido a: %s", getenv("DISPLAY"));
    // Directorio de plugins es /data/data/pkg/files/plugins
    char plugins_path[512];
    snprintf(plugins_path, sizeof(plugins_path), "%s/plugins", g_files_dir);

    // CRÍTICO: Cambiar CWD al directorio de archivos para manejar I/O de la VM
    if (chdir(g_files_dir) == 0) {
        LOG("CWD cambiado a: %s", g_files_dir);
    } else {
        LOG("Error al cambiar CWD a: %s", g_files_dir);
    }
    
    // Establecer HOME/TMPDIR
    setenv("HOME", g_files_dir, 1);
    setenv("TMPDIR", g_files_dir, 1);

    // --- Redirección de Logs (Restaurada) ---
    int pipe_fds[2];
    if (pipe(pipe_fds) == -1) {
        LOG("pipe() falló");
    } else {
        // Redirigir stdout y stderr al pipe
        dup2(pipe_fds[1], STDOUT_FILENO);
        dup2(pipe_fds[1], STDERR_FILENO);

        // Cerrar el descriptor de escritura original
        close(pipe_fds[1]); 

        // Iniciar el hilo de lectura de logs
        pthread_t log_thread;
        // Se usa intptr_t para pasar el descriptor de archivo (fd) al thread
        pthread_create(&log_thread, NULL, log_redirect_thread, (void*)(intptr_t)pipe_fds[0]);
        pthread_detach(log_thread);
        LOG("Redirección de logs iniciada.");
    }
    // --- Fin de Redirección ---

    // Argumentos de la VM: squeak -plugins <ruta_plugins> -display <disp> <ruta_imagen>
    // Hook de tests de dev (opcional): si existe <filesDir>/dev-tests.st se agrega
    //   -s <filesDir>/dev-tests.st  para que la imagen (Cuis 6.x) lo evalúe al arrancar
    //   e imprima resultados por stdout (-> logcat). En producción (sin ese
    //   archivo) el argv queda idéntico al original de 6 elementos.
    static char dev_st_path[600];
    snprintf(dev_st_path, sizeof(dev_st_path), "%s/dev-tests.st", g_files_dir);
    int have_dev_st = (access(dev_st_path, R_OK) == 0);

    // Hook de "File in…" (opcional): la app escribe <filesDir>/pending-filein.st
    // cuando el usuario elige un .st para file-in; tiene prioridad sobre
    // dev-tests.st y la app lo borra después de un boot sano.
    static char filein_st_path[600];
    snprintf(filein_st_path, sizeof(filein_st_path), "%s/pending-filein.st", g_files_dir);
    if (access(filein_st_path, R_OK) == 0) {
        snprintf(dev_st_path, sizeof(dev_st_path), "%s", filein_st_path);
        have_dev_st = 1;
        LOG("pending-filein.st encontrado; se usara como script -s");
    }

    char *argv[12];
    int argc = 0;
    argv[argc++] = (char*)"squeak";
    argv[argc++] = (char*)"-plugins";
    argv[argc++] = plugins_path;
    argv[argc++] = (char*)"-display";
    argv[argc++] = (char*)"127.0.0.1:0";
    argv[argc++] = g_image_path;
    // Point Cuis's "user base directory" at the (writable, already-existing)
    // filesDir. Cuis 6.x/7.x does `UserBaseDirectory assureExistence` on startup;
    // its default path isn't creatable on Android, which pops a createDirectory
    // error debugger. Since filesDir exists, assureExistence becomes a no-op.
    // -ud is a Cuis option; other images (Squeak, Cuis 5.0) ignore it.
    argv[argc++] = (char*)"-ud";
    argv[argc++] = g_files_dir;
    if (have_dev_st) {
        argv[argc++] = (char*)"-s";
        argv[argc++] = dev_st_path;
        LOG("dev-tests.st encontrado; agregando: -s %s", dev_st_path);
    }
    argv[argc] = NULL;

    // === INICIO DEL LOGUEO DEL ARREGLO argv ===
    LOG("Argumentos de la VM (argc: %d):", argc);
    for (int i = 0; i < argc; i++) {
        LOG("  argv[%d]: %s", i, argv[i]);
    }
    // === FIN DEL LOGUEO DEL ARREGLO argv ===

    LOG("Llamando a g_squeak_main() con plugins: %s", plugins_path);


    
    int result = g_squeak_main(argc, argv);
    
    // NOTA: Si llega aquí, la VM terminó "limpiamente" (o después de un fallo capturado)
    LOG("Squeak main() terminó con: %d", result);
    
    return NULL;
}

JNIEXPORT jstring JNICALL
Java_au_com_darkside_x11server_XServerActivity_getLastError(JNIEnv *env, jobject thiz) {
        LOG("startVMNative() llamado");
    return (*env)->NewStringUTF(env, last_error);
}


// --- 3. FUNCIÓN PRINCIPAL DE INICIO (Carga de libs) ---

JNIEXPORT jint JNICALL
Java_au_com_darkside_x11server_XServerActivity_startVMNative(
    JNIEnv *env,
    jobject thiz,
    jstring libPath,
    jstring imagePath,
    jstring pluginsPath
) {
    LOG("=== ENTRANDO A startVMNative ===");
    
    const char *lib = (*env)->GetStringUTFChars(env, libPath, 0);
    const char *image = (*env)->GetStringUTFChars(env, imagePath, 0);
    const char *plugins_path = (*env)->GetStringUTFChars(env, pluginsPath, 0);
    
    LOG("Parametros recibidos");
    LOG("lib=%s", lib);           // ← AGREGAR
    LOG("image=%s", image);       // ← AGREGAR
    LOG("plugins=%s", plugins_path); // ← AGREGAR
    
    snprintf(last_error, sizeof(last_error), "Iniciando...\n");
    LOG("last_error inicializado");  // ← AGREGAR
    
    char lib_dir_temp[512];
    char temp[512];
    
    LOG("Variables locales creadas");  // ← AGREGAR
    
    // 1. Obtener Native Lib Dir
    snprintf(lib_dir_temp, sizeof(lib_dir_temp), "%s", lib);
    LOG("lib_dir_temp copiado");  // ← AGREGAR
    
    char *last_slash_lib = strrchr(lib_dir_temp, '/');
    if (last_slash_lib) *last_slash_lib = '\0';
    snprintf(g_lib_dir, sizeof(g_lib_dir), "%s", lib_dir_temp);
    
    LOG("g_lib_dir=%s", g_lib_dir);  // ← AGREGAR
    
    // 2. Obtener Files Dir
    snprintf(g_files_dir, sizeof(g_files_dir), "%s", image);
    char *image_name = strrchr(g_files_dir, '/');
    if (image_name) *image_name = '\0';
    
    LOG("g_files_dir=%s", g_files_dir);  // ← AGREGAR
    
    // 3. Guardar image path
    snprintf(g_image_path, sizeof(g_image_path), "%s", image);
    
    LOG("g_image_path=%s", g_image_path);  // ← AGREGAR
    LOG("Iniciando carga de dependencias");  // ← AGREGAR
    
    // ... resto del código con las dependencias
    
    // Cargar dependencias (usando la macro para brevedad)
    char path[1024];
    void *h;
    
    #define LOAD_DEP(name) \
        snprintf(path, sizeof(path), "%s/" #name, g_lib_dir); \
        err_append("Cargando " #name "...\n"); \
        h = dlopen(path, RTLD_NOW | RTLD_GLOBAL); \
        if (!h) { \
            snprintf(temp, sizeof(temp), "ERROR: %s\n", dlerror()); \
            err_append(temp); \
        } else { \
            err_append("OK\n"); \
        }
            
    LOAD_DEP(libz.so)
    LOAD_DEP(libandroid-posix-semaphore.so)
    LOAD_DEP(libuuid.so)
    LOAD_DEP(libiconv.so)
    LOAD_DEP(libandroid-execinfo.so)
    
    err_append("Cargando libsqueak.so...\n");
    void *vm_handle = dlopen(lib, RTLD_NOW | RTLD_GLOBAL);
    if (!vm_handle) {
        // Previously this branch was EMPTY: we carried on with a NULL handle,
        // dlsym(NULL,...) returned NULL, and calling it crashed with SIGSEGV —
        // a hard crash instead of a diagnosable "the VM could not be loaded".
        snprintf(temp, sizeof(temp), "ERROR: no se pudo cargar la VM (%s): %s\n", lib, dlerror());
        err_append(temp);
        LOG("%s", temp);
        (*env)->ReleaseStringUTFChars(env, libPath, lib);
        (*env)->ReleaseStringUTFChars(env, imagePath, image);
        (*env)->ReleaseStringUTFChars(env, pluginsPath, plugins_path);
        return -1;   // Java side reports this instead of rendering nothing
    }
    err_append("OK\n");

    // ----------------------------------------------------
    // INICIO: CARGA EXPLÍCITA DE DEPENDENCIAS (El Fix)
    // ----------------------------------------------------
    
    // Lista de todas las librerías dependientes encontradas en el readelf

    LOG("Iniciando precarga de dependencias (plugins)");  // ← AGREGAR

    const char *deps_to_load[] = {
  
        "libglib-2.0.so.0",
        "libgobject-2.0.so.0",
        "libgmodule-2.0.so.0",
        "libgio-2.0.so.0",
        "libbz2.so.1.0",
        "libpango-1.0.so.0",
        "libexpat.so.1",
        "libpangoft2-1.0.so.0",
        "libcairo.so.2",
        "libpangocairo-1.0.so.0",
    
        "libXau.so", 
        "libGLdispatch.so.0",
        "libsqueak_jni.so", 
        "libandroid-shmem.so", 
        "libXdmcp.so",
        "libxcb.so",
        "libandroid-support.so",
        "libX11.so", 
        "libGLX.so.0",
        "libGL.so", 
        "libGL.so.1", 
        "libXext.so", 
        "libXrender.so",
        "libXrandr.so",

        "XDisplayControlPlugin.so",
        "vm-sound-pulse.so",
        "vm-sound-null.so",
        "vm-display-X11.so",
        "vm-display-null.so",
        "VectorEnginePlugin.so",
        "UUIDPlugin.so",
        "UnixOSProcessPlugin.so",
        "UnicodePlugin.so",
        "SHA2Plugin.so",
        "MD5Plugin.so",
        "LocalePlugin.so",
        "ImmX11Plugin.so",
        "FileAttributesPlugin.so",
        "DESPlugin.so",
        "ClipboardExtendedPlugin.so",
        "B3DAcceleratorPlugin.so",


        // Aquí puedes agregar cualquier otra dependencia que te pida más tarde
        NULL // Marcador de fin de array
    };

    err_append("Iniciando precarga de dependencias...\n");
    
    LOG("Array de dependencias creado");  // ← AGREGAR

    for (int i = 0; deps_to_load[i] != NULL; i++) {
        const char *dep_name = deps_to_load[i];
        char dep_full_path[512];
        
        LOG("Iteración %d: %s", i, dep_name);  // ← AGREGAR
        
        // Construir la ruta completa: /ruta/a/plugins/libX.so
        snprintf(dep_full_path, sizeof(dep_full_path), "%s/%s", plugins_path, dep_name);
        
        LOG("Ruta completa: %s", dep_full_path);  // ← AGREGAR
        
        err_append("Cargando: ");
        err_append(dep_name);
        err_append("...");

        // Usar dlopen con RTLD_GLOBAL para cargar en el espacio de nombres principal
        void *dep_handle = dlopen(dep_full_path, RTLD_NOW | RTLD_GLOBAL);
        
        if (!dep_handle) {
            // Si falla, lo registramos. Squeak fallará después, pero el log lo explica.
            snprintf(temp, sizeof(temp), "FALLO: %s\n", dlerror());
            err_append(temp);
            LOG("FALLO cargando %s: %s", dep_name, dlerror());  // ← AGREGAR
        } else {
            err_append("OK\n");
            LOG("OK: %s", dep_name);  // ← AGREGAR
        }
    }

    err_append("Precarga terminada.\n");
    LOG("Loop de precarga terminado");  // ← AGREGAR
    // ----------------------------------------------------
    // FIN: CARGA EXPLÍCITA DE DEPENDENCIAS
    // ----------------------------------------------------
    
    err_append("Buscando main()...\n");
    g_squeak_main = (squeak_main_t)dlsym(vm_handle, "main");
    if (!g_squeak_main) {
        // Same as above: this branch used to be empty, so a missing entry point
        // meant calling a NULL function pointer on the VM thread.
        snprintf(temp, sizeof(temp), "ERROR: la VM no expone main(): %s\n", dlerror());
        err_append(temp);
        LOG("%s", temp);
        (*env)->ReleaseStringUTFChars(env, libPath, lib);
        (*env)->ReleaseStringUTFChars(env, imagePath, image);
        (*env)->ReleaseStringUTFChars(env, pluginsPath, plugins_path);
        return -2;
    }
    err_append("main() encontrado!\n");

    LOG("Preparando para lanzar thread de VM");  // ← AGREGAR
    err_append("Lanzando thread...\n");

    // Crear thread nativo para la VM
    pthread_t thread;
    LOG("Antes de pthread_create");  // ← AGREGAR

    int ret = pthread_create(&thread, NULL, run_squeak_thread, NULL);

    LOG("pthread_create retornó: %d", ret);  // ← AGREGAR

    if (ret != 0) {
        LOG("ERROR creando thread: %d", ret);  // ← AGREGAR
        // Manejo de error de pthread_create
    } else {
        LOG("Thread creado exitosamente");  // ← AGREGAR
    }

    pthread_detach(thread);
    LOG("Thread detached");  // ← AGREGAR

    err_append("Thread lanzado! VM ejecutándose en background.\n");

    LOG("Liberando strings JNI");  // ← AGREGAR
    
    // Liberar recursos JNI
    (*env)->ReleaseStringUTFChars(env, libPath, lib);
    (*env)->ReleaseStringUTFChars(env, imagePath, image);
    (*env)->ReleaseStringUTFChars(env, pluginsPath, plugins_path);
    
    return 0;
}