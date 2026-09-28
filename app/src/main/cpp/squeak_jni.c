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
#include <android/native_window_jni.h>

#define LOG(...) __android_log_print(ANDROID_LOG_ERROR, "SQUEAK", __VA_ARGS__)
#define LOG_VM(...) __android_log_print(ANDROID_LOG_INFO, "SQUEAK_VM", __VA_ARGS__) // Log para la VM

typedef int (*squeak_main_t)(int argc, char **argv);

static char last_error[4096] = "";
static squeak_main_t g_squeak_main = NULL;
static char g_image_path[512] = "";
static char g_lib_dir[512] = "";    // Directorio de la librería nativa del APK
static char g_files_dir[512] = ""; // Directorio de archivos de la app (/data/data/pkg/files)
static int g_native_display = 0;   // 1 = render with vm-display-android (no X server)
static JavaVM *g_jvm = NULL;       // for calling back into Java from the VM thread
static void nd_vm_exiting(void);    // atexit hook; defined with the JNI bridge below
/*
 * NativeDisplay, held as a global ref taken in JNI_OnLoad.
 *
 * It cannot be looked up later with FindClass: a thread attached with
 * AttachCurrentThread gets the SYSTEM class loader, which knows nothing about the
 * app's classes, so FindClass simply fails there. JNI_OnLoad runs on the thread that
 * called System.loadLibrary, which has the right loader.
 */
static jclass g_nativeDisplayClass = NULL;

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

    // Script de adaptación por-boot (lo escribe la app en cada launch): parchea la
    // imagen para el teléfono (fileout silencioso → Downloads, initials) y ENCADENA
    // pending-filein.st / dev-tests.st él mismo, así que se lleva el único slot -s.
    static char setup_st_path[600];
    snprintf(setup_st_path, sizeof(setup_st_path), "%s/android-setup.st", g_files_dir);
    if (access(setup_st_path, R_OK) == 0) {
        snprintf(dev_st_path, sizeof(dev_st_path), "%s", setup_st_path);
        have_dev_st = 1;
        LOG("android-setup.st encontrado; se usara como script -s (encadena los demas)");
    }

    char *argv[16];
    int argc = 0;
    argv[argc++] = (char*)"squeak";
    argv[argc++] = (char*)"-plugins";
    argv[argc++] = plugins_path;
    if (g_native_display) {
        // Ask for our own display module. The VM resolves "-vm-display-<name>"
        // to vm-display-<name>.so in the plugins directory, so nothing in the
        // VM itself has to know this driver exists.
        argv[argc++] = (char*)"-vm-display-android";
    } else {
        argv[argc++] = (char*)"-display";
        argv[argc++] = (char*)"127.0.0.1:0";
    }
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
        LOG("script -s: %s", dev_st_path);
    }
    argv[argc] = NULL;

    // === INICIO DEL LOGUEO DEL ARREGLO argv ===
    LOG("Argumentos de la VM (argc: %d):", argc);
    for (int i = 0; i < argc; i++) {
        LOG("  argv[%d]: %s", i, argv[i]);
    }
    // === FIN DEL LOGUEO DEL ARREGLO argv ===

    LOG("Llamando a g_squeak_main() con plugins: %s", plugins_path);

    atexit(nd_vm_exiting);

    int result = g_squeak_main(argc, argv);
    
    // NOTA: Si llega aquí, la VM terminó "limpiamente" (o después de un fallo capturado)
    LOG("Squeak main() terminó con: %d", result);
    
    return NULL;
}


// ---------------------------------------------------------------------------
// Native display (vm-display-android): the X11-free path.
//
// The display driver is an ordinary Unix display module, so the VM loads it
// itself when argv says -vm-display-android. This glue only has to hand it the
// SurfaceView's window and forward input; it talks to the module through the
// handful of plain C entry points the module exports, resolved by name, so
// neither side needs to know about the other at link time.
//
// The surface can appear before or after the VM starts, so the window is cached
// here and flushed to the module as soon as the module is resolvable.
// ---------------------------------------------------------------------------

static void (*nd_setWindow)(void *window, int w, int h) = NULL;
static void (*nd_setLogicalSize)(int w, int h)          = NULL;
static void (*nd_postMouse)(int, int, int, int)         = NULL;
static void (*nd_postKey)(int, int, int, int)           = NULL;
static void (*nd_postWheel)(int, int)                   = NULL;
static void (*nd_postDrop)(const char *)                = NULL;
static void (*nd_setClipboard)(const char *)            = NULL;

static ANativeWindow *g_pending_window = NULL;   // held until the module is up
static int g_pending_w = 0, g_pending_h = 0;

/* The driver calls this from the VM THREAD when the image writes the clipboard,
   so the thread has to be attached to the JVM before touching JNI. */
static void nd_clipboard_written(const char *utf8) {
    JNIEnv *env = NULL;
    int attached = 0;
    if (!g_jvm || !utf8) return;
    if ((*g_jvm)->GetEnv(g_jvm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        if ((*g_jvm)->AttachCurrentThread(g_jvm, &env, NULL) != JNI_OK) return;
        attached = 1;
    }
    if (g_nativeDisplayClass) {
        jmethodID mid = (*env)->GetStaticMethodID(env, g_nativeDisplayClass,
                                                  "onImageWroteClipboard",
                                                  "(Ljava/lang/String;)V");
        if (mid) {
            jstring js = (*env)->NewStringUTF(env, utf8);
            if (js) {
                (*env)->CallStaticVoidMethod(env, g_nativeDisplayClass, mid, js);
                (*env)->DeleteLocalRef(env, js);
            }
        }
    }
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    if (attached) (*g_jvm)->DetachCurrentThread(g_jvm);
}

/*
 * The image quitting ("Save image and quit") ends in exit(), which takes the whole
 * Android process with it -- the app simply vanished to the launcher. An atexit
 * handler runs first and lets Java put the chooser back up.
 *
 * It must do its work SYNCHRONOUSLY: posting to the UI thread would lose the race
 * against exit(). And it is registered here rather than in the display driver so it
 * covers both display backends, since the exit path is the VM's, not the driver's.
 */
static void nd_vm_exiting(void) {
    JNIEnv *env = NULL;
    int attached = 0;
    if (!g_jvm) return;
    LOG("la VM esta saliendo (exit); avisando a la app");
    if ((*g_jvm)->GetEnv(g_jvm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        if ((*g_jvm)->AttachCurrentThread(g_jvm, &env, NULL) != JNI_OK) return;
        attached = 1;
    }
    if (g_nativeDisplayClass) {
        jmethodID mid = (*env)->GetStaticMethodID(env, g_nativeDisplayClass, "onVmQuit", "()V");
        if (mid) (*env)->CallStaticVoidMethod(env, g_nativeDisplayClass, mid);
    } else {
        LOG("no se pudo avisar: NativeDisplay no quedo cacheada en JNI_OnLoad");
    }
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    if (attached) (*g_jvm)->DetachCurrentThread(g_jvm);
}

/* Resolve the module's API. Returns 1 once it is available. The module is
   loaded RTLD_GLOBAL (by us at startup, or by the VM when it parses argv), so
   RTLD_DEFAULT finds it -- dlopen(NULL) would not: inside an APK that handle is
   app_process, which knows nothing about libraries we dlopen()ed. */
static int nd_resolve(void) {
    if (nd_setWindow) return 1;
    nd_setWindow      = dlsym(RTLD_DEFAULT, "sqAndroidSetNativeWindow");
    nd_setLogicalSize = dlsym(RTLD_DEFAULT, "sqAndroidSetLogicalSize");
    nd_postMouse      = dlsym(RTLD_DEFAULT, "sqAndroidPostMouseEvent");
    nd_postKey        = dlsym(RTLD_DEFAULT, "sqAndroidPostKeyEvent");
    nd_postWheel      = dlsym(RTLD_DEFAULT, "sqAndroidPostWheelEvent");
    nd_postDrop       = dlsym(RTLD_DEFAULT, "sqAndroidPostDropFile");
    nd_setClipboard   = dlsym(RTLD_DEFAULT, "sqAndroidSetClipboardText");
    if (!nd_setWindow) return 0;
    LOG("display nativo: API del modulo resuelta");
    {
        void (*setWriter)(void (*)(const char *)) =
            dlsym(RTLD_DEFAULT, "sqAndroidSetClipboardWriter");
        if (setWriter) setWriter(nd_clipboard_written);
    }
    if (g_pending_window) {
        nd_setWindow(g_pending_window, g_pending_w, g_pending_h);
        ANativeWindow_release(g_pending_window);   // the module took its own ref
        g_pending_window = NULL;
    }
    return 1;
}

JNIEXPORT void JNICALL
Java_au_com_darkside_x11server_NativeDisplay_enable(JNIEnv *env, jclass cls, jboolean on) {
    g_native_display = on ? 1 : 0;
    LOG("display nativo: %s", g_native_display ? "ACTIVADO" : "desactivado");
}

JNIEXPORT jboolean JNICALL
Java_au_com_darkside_x11server_NativeDisplay_isReady(JNIEnv *env, jclass cls) {
    return nd_resolve() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_au_com_darkside_x11server_NativeDisplay_setSurface(
        JNIEnv *env, jclass cls, jobject surface, jint w, jint h) {
    ANativeWindow *win = surface ? ANativeWindow_fromSurface(env, surface) : NULL;
    if (nd_resolve()) {
        nd_setWindow(win, w, h);
        if (win) ANativeWindow_release(win);       // the module holds its own ref
    } else {
        if (g_pending_window) ANativeWindow_release(g_pending_window);
        g_pending_window = win;                    // keep the ref for the flush
        g_pending_w = w;
        g_pending_h = h;
    }
    LOG("display nativo: surface %s %dx%d", surface ? "set" : "cleared", (int)w, (int)h);
}

JNIEXPORT void JNICALL
Java_au_com_darkside_x11server_NativeDisplay_setLogicalSize(JNIEnv *env, jclass cls, jint w, jint h) {
    if (nd_resolve() && nd_setLogicalSize) nd_setLogicalSize(w, h);
}

JNIEXPORT void JNICALL
Java_au_com_darkside_x11server_NativeDisplay_postMouse(
        JNIEnv *env, jclass cls, jint x, jint y, jint buttons, jint modifiers) {
    if (nd_resolve() && nd_postMouse) nd_postMouse(x, y, buttons, modifiers);
}

JNIEXPORT void JNICALL
Java_au_com_darkside_x11server_NativeDisplay_postKey(
        JNIEnv *env, jclass cls, jint keyCode, jint pressCode, jint modifiers, jint ucs4) {
    if (nd_resolve() && nd_postKey) nd_postKey(keyCode, pressCode, modifiers, ucs4);
}

JNIEXPORT void JNICALL
Java_au_com_darkside_x11server_NativeDisplay_postWheel(JNIEnv *env, jclass cls, jint dx, jint dy) {
    if (nd_resolve() && nd_postWheel) nd_postWheel(dx, dy);
}

JNIEXPORT void JNICALL
Java_au_com_darkside_x11server_NativeDisplay_postDropFile(JNIEnv *env, jclass cls, jstring path) {
    if (!nd_resolve() || !nd_postDrop || !path) return;
    const char *p = (*env)->GetStringUTFChars(env, path, 0);
    nd_postDrop(p);
    (*env)->ReleaseStringUTFChars(env, path, p);
}

JNIEXPORT void JNICALL
Java_au_com_darkside_x11server_NativeDisplay_setClipboard(JNIEnv *env, jclass cls, jstring text) {
    if (!nd_resolve() || !nd_setClipboard) return;
    if (!text) { nd_setClipboard(""); return; }
    const char *t = (*env)->GetStringUTFChars(env, text, 0);
    nd_setClipboard(t);
    (*env)->ReleaseStringUTFChars(env, text, t);
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
    LOG("lib=%s", lib);
    LOG("image=%s", image);
    LOG("plugins=%s", plugins_path);
    
    snprintf(last_error, sizeof(last_error), "Iniciando...\n");
    LOG("last_error inicializado");
    
    char lib_dir_temp[512];
    char temp[512];
    
    LOG("Variables locales creadas");
    
    // 1. Obtener Native Lib Dir
    snprintf(lib_dir_temp, sizeof(lib_dir_temp), "%s", lib);
    LOG("lib_dir_temp copiado");
    
    char *last_slash_lib = strrchr(lib_dir_temp, '/');
    if (last_slash_lib) *last_slash_lib = '\0';
    snprintf(g_lib_dir, sizeof(g_lib_dir), "%s", lib_dir_temp);
    
    LOG("g_lib_dir=%s", g_lib_dir);
    
    // 2. Obtener Files Dir
    snprintf(g_files_dir, sizeof(g_files_dir), "%s", image);
    char *image_name = strrchr(g_files_dir, '/');
    if (image_name) *image_name = '\0';
    
    LOG("g_files_dir=%s", g_files_dir);
    
    // 3. Guardar image path
    snprintf(g_image_path, sizeof(g_image_path), "%s", image);
    
    LOG("g_image_path=%s", g_image_path);
    LOG("Iniciando carga de dependencias");
    
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

    LOG("Iniciando precarga de dependencias (plugins)");

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


        "vm-sound-pulse.so",
        "vm-sound-null.so",
        "vm-display-X11.so",
        // These two resolve symbols FROM the X display module (displayName,
        // setCompositionFocus), so they have to be loaded after it. Listed before it,
        // both failed with "cannot locate symbol" on every launch -- which the
        // double dlerror() call above reported as "(null)" for years.
        "XDisplayControlPlugin.so",
        "ImmX11Plugin.so",
        "vm-display-null.so",
        "VectorEnginePlugin.so",
        "UUIDPlugin.so",
        "UnixOSProcessPlugin.so",
        "UnicodePlugin.so",
        "SHA2Plugin.so",
        "MD5Plugin.so",
        "LocalePlugin.so",

        "FileAttributesPlugin.so",
        "DESPlugin.so",
        "ClipboardExtendedPlugin.so",
        "B3DAcceleratorPlugin.so",


        // Aquí puedes agregar cualquier otra dependencia que te pida más tarde
        NULL // Marcador de fin de array
    };

    err_append("Iniciando precarga de dependencias...\n");
    

    for (int i = 0; deps_to_load[i] != NULL; i++) {
        const char *dep_name = deps_to_load[i];
        char dep_full_path[512];
        
        // Construir la ruta completa: /ruta/a/plugins/libX.so
        snprintf(dep_full_path, sizeof(dep_full_path), "%s/%s", plugins_path, dep_name);
        
        // A build made with -PnativeOnly ships no X display driver and no X-only
        // plugins, so several names on this list simply are not there. That is not
        // a failure and should not read like one in the log -- nor leave a dangling
        // "Cargando: X..." with no outcome in the diagnostics buffer.
        if (access(dep_full_path, R_OK) != 0) {
            LOG("omitido (no incluido en esta variante): %s", dep_name);
            continue;
        }

        err_append("Cargando: ");
        err_append(dep_name);
        err_append("...");

        // RTLD_GLOBAL: the plugins resolve each other's symbols through the process
        // namespace, so a module must be visible to whatever is loaded after it.

        void *dep_handle = dlopen(dep_full_path, RTLD_NOW | RTLD_GLOBAL);
        
        if (!dep_handle) {
            // dlerror() CLEARS the error, so it must be read once. Calling it twice --
            // as this did -- made every one of these lines report "(null)" and hid the
            // actual reason a plugin would not load.
            const char *why = dlerror();
            if (!why) why = "(sin detalle)";
            snprintf(temp, sizeof(temp), "FALLO: %s\n", why);
            err_append(temp);
            LOG("FALLO cargando %s: %s", dep_name, why);
        } else {
            err_append("OK\n");
        }
    }

    err_append("Precarga terminada.\n");
    LOG("Loop de precarga terminado");
    // ----------------------------------------------------
    // FIN: CARGA EXPLÍCITA DE DEPENDENCIAS
    // ----------------------------------------------------
    
    // In native-display mode load the driver now, so the surface that the UI
    // thread may already have created can be handed over before the VM boots.
    if (g_native_display) {
        char nd_path[600];
        snprintf(nd_path, sizeof(nd_path), "%s/vm-display-android.so", plugins_path);
        void *nd_handle = dlopen(nd_path, RTLD_NOW | RTLD_GLOBAL);
        if (!nd_handle) {
            snprintf(temp, sizeof(temp), "ERROR: no se pudo cargar %s: %s\n", nd_path, dlerror());
            err_append(temp);
            LOG("%s", temp);
        } else {
            LOG("display nativo: %s cargado", nd_path);
            nd_resolve();
        }
    }

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

    LOG("Preparando para lanzar thread de VM");
    err_append("Lanzando thread...\n");

    // Crear thread nativo para la VM
    pthread_t thread;
    LOG("Antes de pthread_create");

    int ret = pthread_create(&thread, NULL, run_squeak_thread, NULL);

    LOG("pthread_create retornó: %d", ret);

    if (ret != 0) {
        LOG("ERROR creando thread: %d", ret);
        // Manejo de error de pthread_create
    } else {
        LOG("Thread creado exitosamente");
    }

    pthread_detach(thread);
    LOG("Thread detached");

    err_append("Thread lanzado! VM ejecutándose en background.\n");

    LOG("Liberando strings JNI");
    
    // Liberar recursos JNI
    (*env)->ReleaseStringUTFChars(env, libPath, lib);
    (*env)->ReleaseStringUTFChars(env, imagePath, image);
    (*env)->ReleaseStringUTFChars(env, pluginsPath, plugins_path);
    
    return 0;
}
/* Kept for the case where NativeDisplayActivity declares its own startVMNative:
   a native method binds by its DECLARING class name, and today that activity simply
   inherits XServerActivity's, so nothing calls this. It costs a few bytes and it is
   the thing that would silently break if the inheritance ever changed. */
JNIEXPORT jint JNICALL
Java_au_com_darkside_x11server_NativeDisplayActivity_startVMNative(
    JNIEnv *env, jobject thiz, jstring libPath, jstring imagePath, jstring pluginsPath) {
    return Java_au_com_darkside_x11server_XServerActivity_startVMNative(
        env, thiz, libPath, imagePath, pluginsPath);
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    JNIEnv *env = NULL;
    g_jvm = vm;
    if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) == JNI_OK) {
        jclass local = (*env)->FindClass(env, "au/com/darkside/x11server/NativeDisplay");
        if (local) {
            g_nativeDisplayClass = (*env)->NewGlobalRef(env, local);
            (*env)->DeleteLocalRef(env, local);
        } else {
            (*env)->ExceptionClear(env);
            LOG("JNI_OnLoad: no se encontro NativeDisplay");
        }
    }
    return JNI_VERSION_1_6;
}
