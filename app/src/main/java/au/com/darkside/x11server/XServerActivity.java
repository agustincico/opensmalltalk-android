package au.com.darkside.x11server;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.Service;
import android.app.NotificationManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.view.Menu;
import android.view.MenuItem;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import au.com.darkside.xserver.ScreenView;
import au.com.darkside.xserver.XServer;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ProcessBuilder;
import java.lang.Class;
import java.lang.reflect.Constructor;

import android.util.Log;

import java.io.FileOutputStream;
import android.content.res.AssetManager;

import java.io.IOException;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;

/**
 * This activity launches an X server and provides a screen for it.
 *
 * @author Matthew Kwan
 */


public class XServerActivity extends Activity {

    static {
        try {
            System.loadLibrary("squeak_jni");
            Log.i("Cuis", "✅ libsqueak_jni cargada OK");
        } catch (UnsatisfiedLinkError e) {
            Log.e("Cuis", "❌ Error cargando libsqueak_jni", e);
        }
    }

    public native int startVMNative(
            String libPath,
            String imagePath,
            String pluginsPath
    );

    private static final String TAG = "Cuis";

    private XServer _xServer;
    private ScreenView _screenView;
    private WakeLock _wakeLock;

    private static final String NOTIFICATION_CHANNEL_DEFAULT = "default";

    private static final int MENU_KEYBOARD = 1;
    private static final int MENU_IP_ADDRESS = 2;
    private static final int MENU_ACCESS_CONTROL = 3;
    private static final int MENU_REMOTE_LOGIN = 4;
    private static final int MENU_TOGGLE_ARROWS = 5;
    private static final int MENU_TOGGLE_BACKBUTTON = 6;
    private static final int MENU_TOGGLE_TOUCHCLICKS = 7;
    private static final int MENU_TOGGLE_WINDOWMANAGER = 8;
    private static final int MENU_TOGGLE_ORIENTATION = 9;
    private static final int MENU_TOGGLE_SHARED_CLIPBOARD = 10;
    private static final int ACTIVITY_ACCESS_CONTROL = 1;

    private static final int DEFAULT_PORT = 6000;
    private static final String PORT_DESC_PRE = "Listening on port ";

    private int _port = DEFAULT_PORT;
    private String _portDescription = PORT_DESC_PRE + DEFAULT_PORT;
    private Process _windowManager;

    /**
     * Called when the activity is first created.
     *
     * @param savedInstanceState Saved state.
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

           extractAssets();
        Log.i(TAG, "Extract Assets OK");
        extractPlugins();
        Log.i(TAG, "Extract Plugins OK");


        int port = DEFAULT_PORT;
        Intent intent = getIntent();

        // If it was launched from an intent, get the port number.
        if (intent != null) {
            Uri uri = intent.getData();

            if (uri != null) {
                int p = uri.getPort();

                if (p >= 0) {
                    if (p < 10) // Using ports 0-9 is bad juju.
                        port = p + DEFAULT_PORT;
                    else port = p;
                }
            }
        }

        _port = port;
        if (_port != DEFAULT_PORT) _portDescription = PORT_DESC_PRE + _port;

        _xServer = new XServer(this, port, null);

        // execute binary on start (if there was any packed into the assets folder)
_xServer.setOnStartListener(new XServer.OnXSeverStartListener() {
    @Override
    public void onStart() {
        Log.i(TAG, "XServer iniciado, preparando VM Smalltalk");

        // Delay corto para asegurar que DISPLAY esté listo
        _screenView.postDelayed(() -> {
            try {
                String libPath = getApplicationInfo().nativeLibraryDir + "/libsqueak.so";
                String imagePath = getFilesDir().getAbsolutePath() + "/Cuis.image";
                String pluginsPath = getFilesDir().getAbsolutePath() + "/plugins";

                Log.i(TAG, "Lanzando VM");
                Log.i(TAG, "libPath=" + libPath);
                Log.i(TAG, "imagePath=" + imagePath);
                Log.i(TAG, "pluginsPath=" + pluginsPath);

                int res = startVMNative(libPath, imagePath, pluginsPath);
                Log.i(TAG, "startVMNative() retornó: " + res);

            } catch (Throwable t) {
                Log.e(TAG, "Error lanzando VM", t);
            }
        }, 500); // ← importante
    }
});


        setAccessControl();
        FrameLayout fl = (FrameLayout) findViewById(R.id.frame);

        _screenView = _xServer.getScreen();
        fl.addView(_screenView);

        PowerManager pm;

        pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        _wakeLock = pm.newWakeLock(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON, getPackageName() + ":XServer");

        // make window fullscreen
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);

        startService(new Intent(this, XServerService.class));

        /*
         * Create notification channel as it required for notifications on Android >= 8
         * Use reflection to stay backward compatible with sdk provided by debian
         */
        if (Build.VERSION.SDK_INT >= 26) {
            CharSequence name = "Default channel";
            String description = "Default notification channel of XServer demo";
            int importance = 3; // default importance

            try{
                Class nc = Class.forName("android.app.NotificationChannel");
                Object ncObj = nc.getConstructor(new Class[] {String.class, CharSequence.class, int.class}).newInstance(NOTIFICATION_CHANNEL_DEFAULT, name, importance);
                nc.getMethod("setDescription", String.class).invoke(ncObj, description);
                nc.getMethod("setVibrationPattern", long[].class).invoke(ncObj, new long[]{ 0 }); // enableVibration is bugged, use this as workaround
                nc.getMethod("enableVibration", boolean.class).invoke(ncObj, true);
                nc.getMethod("enableLights", boolean.class).invoke(ncObj, false);
                NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                manager.getClass().getMethod("createNotificationChannel", nc).invoke(manager, ncObj);
            }
            catch(Exception e){
                Log.e("FATAL", "Could not reflect Android SDK >= 26", e);
            }
        }
    }

    /**
     * Called when the activity resumes.
     */
    @Override
    public void onResume() {
        super.onResume();
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        manager.cancel(1);
        _wakeLock.acquire();
    }

    /**
     * Called when the activity pauses.
     */
    @Override
    public void onPause() {
        super.onPause();
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, getIntent(), PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder nb = new Notification.Builder(this)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Running!")
            .setContentText("XServer running in background.")
            .setContentIntent(pendingIntent)
            .setOngoing(true);

        /*
         * Set notification channel as it required for notifications on Android >= 8
         * Use reflection to stay backward compatible with sdk provided by debian
         */
        if (Build.VERSION.SDK_INT >= 26) {
            try{
                nb.getClass().getMethod("setChannelId", String.class).invoke(nb, NOTIFICATION_CHANNEL_DEFAULT);
            }
            catch(Exception e){
                Log.e("FATAL", "Could not reflect Android SDK >= 26", e);
            }
        }


        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        manager.notify(1, nb.build());

        _wakeLock.release();
    }

    /**
     * Called when the activity is destroyed.
     */
    @Override
    public void onDestroy() {
        _xServer.stop();
        super.onDestroy();

        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        manager.cancel(1);
    }

    /**
     * Called the first time a menu is needed.
     *
     * @param menu The options menu in which you place your items.
     * @return True for the menu to be displayed.
     */
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuItem item;

        item = menu.add(0, MENU_KEYBOARD, 0, "Keyboard");
        item.setIcon(android.R.drawable.ic_menu_add);

        item = menu.add(0, MENU_IP_ADDRESS, 0, "IP address");
        item.setIcon(android.R.drawable.ic_menu_info_details);

        item = menu.add(0, MENU_ACCESS_CONTROL, 0, "Access control");
        item.setIcon(android.R.drawable.ic_menu_edit);

        item = menu.add(0, MENU_REMOTE_LOGIN, 0, "Remote login");
        item.setIcon(android.R.drawable.ic_menu_upload);

        item = menu.add(0, MENU_TOGGLE_ARROWS, 0, "Arrows as Mouseclicks (off)");
        item.setIcon(android.R.drawable.star_off);

        item = menu.add(0, MENU_TOGGLE_BACKBUTTON, 0, "Inhibit back button (off)");
        item.setIcon(android.R.drawable.star_off);

        item = menu.add(0, MENU_TOGGLE_TOUCHCLICKS, 0, "Touch Mouseclicks (on)");
        item.setIcon(android.R.drawable.star_on);

        item = menu.add(0, MENU_TOGGLE_WINDOWMANAGER, 0, "Window Manager (off)");
        item.setIcon(android.R.drawable.star_on);

        item = menu.add(0, MENU_TOGGLE_SHARED_CLIPBOARD, 0, "Shared Clipboard (on)");
        item.setIcon(android.R.drawable.star_on);

        item = menu.add(0, MENU_TOGGLE_ORIENTATION, 0, "Screen Orientation (H)");

        return true;
    }

    /**
     * Called when a menu selection has been made.
     *
     * @param item The menu item that was selected.
     * @return True if the menu selection has been handled.
     */
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        super.onOptionsItemSelected(item);

        switch (item.getItemId()) {
            case MENU_KEYBOARD:
                InputMethodManager imm = (InputMethodManager) getSystemService(Service.INPUT_METHOD_SERVICE);

                // If anyone knows a better way to bring up the soft
                // keyboard, I'd love to hear about it.
                _screenView.requestFocus();
                imm.hideSoftInputFromWindow(_screenView.getWindowToken(), 0);
                imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0);
                return true;
            case MENU_IP_ADDRESS:
                getMenuIpAdressDialog().show();
                return true;
            case MENU_ACCESS_CONTROL:
                launchAccessControlEditor();
                return true;
            case MENU_REMOTE_LOGIN:
                launchSshApp();
                return true;
            case MENU_TOGGLE_ARROWS:
                if (_xServer.getScreen().toggleArrowsAsButtons()) {
                    item.setIcon(android.R.drawable.star_on);
                    item.setTitle("Arrows as Mouseclicks (on)");
                } else {
                    item.setIcon(android.R.drawable.star_off);
                    item.setTitle("Arrows as Mouseclicks (off)");
                }
                return true;
            case MENU_TOGGLE_BACKBUTTON:
                if (_xServer.getScreen().toggleInhibitBackButton()) {
                    item.setIcon(android.R.drawable.star_on);
                    item.setTitle("Inhibit back button (on)");
                } else {
                    item.setIcon(android.R.drawable.star_off);
                    item.setTitle("Inhibit back button (off)");
                }
                return true;
            case MENU_TOGGLE_TOUCHCLICKS:
                if (_xServer.getScreen().toggleEnableTouchClicks()) {
                    item.setIcon(android.R.drawable.star_on);
                    item.setTitle("Touch Mouseclicks (on)");
                } else {
                    item.setIcon(android.R.drawable.star_off);
                    item.setTitle("Touch Mouseclicks (off)");
                }
                return true;
            case MENU_TOGGLE_SHARED_CLIPBOARD:
                if (_xServer.getScreen().toggleSharedClipboard()) {
                    item.setIcon(android.R.drawable.star_on);
                    item.setTitle("Shared Clipboard (on)");
                } else {
                    item.setIcon(android.R.drawable.star_off);
                    item.setTitle("Shared Clipboard (off)");
                }
                return true;
            case MENU_TOGGLE_WINDOWMANAGER:
                if (_windowManager == null) {
                    try {
                        File file = new File(getApplicationInfo().nativeLibraryDir + "/libwm.so");
                        file.setExecutable(true); // make program executable
                        ProcessBuilder pb = new ProcessBuilder(file.getPath());
                        Map<String, String> env = pb.environment();
                        env.put("DISPLAY", "127.0.0.1:0");
                        pb.directory(new File(getApplicationInfo().dataDir)); // execute within dataDir
                        _windowManager = pb.start();
                        item.setIcon(android.R.drawable.star_on);
                        item.setTitle("Window Manager (on)");
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                } else {
                    _windowManager.destroy();
                    _windowManager = null;
                    item.setIcon(android.R.drawable.star_off);
                    item.setTitle("Window Manager (off)");
                }
                return true;
            case MENU_TOGGLE_ORIENTATION:
                if (getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) {
                    setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
                    item.setTitle("Screen Orientation (V)");
                } else {
                    setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
                    item.setTitle("Screen Orientation (H)");
                }
                return true;
        }

        return false;
    }

    /**
     * Return a string describing the IP address(es) of this device.
     *
     * @return A string describing the IP address(es) of this device.
     */
    private String getAddressInfo() {
        String s = _portDescription;

        try {
            for (Enumeration<NetworkInterface> nie = NetworkInterface.getNetworkInterfaces(); nie.hasMoreElements(); ) {
                NetworkInterface ni = nie.nextElement();

                for (Enumeration<InetAddress> iae = ni.getInetAddresses(); iae.hasMoreElements(); ) {
                    InetAddress ia = iae.nextElement();

                    if (ia.isLoopbackAddress()) continue;

                    s += "\n" + ni.getDisplayName() + ": " + ia.getHostAddress();
                }
            }
        } catch (Exception e) {
            s += "\nError: " + e.getMessage();
        }

        return s;
    }

    /**
     * @return The Dialog to enter the server IP Adress.
     */
    private Dialog getMenuIpAdressDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("IP address").setMessage(getAddressInfo()).setPositiveButton("OK", new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int id) {
                dialog.cancel();
            }
        });
        return builder.create();
    }


    /**
     * Load the access control hosts from persistent storage.
     */
    private void setAccessControl() {
        SharedPreferences prefs = getSharedPreferences("AccessControlHosts", MODE_PRIVATE);
        Map<String, ?> map = prefs.getAll();
        HashSet<Integer> hosts = _xServer.getAccessControlHosts();

        hosts.clear();
        if (!map.isEmpty()) {
            Set<String> set = map.keySet();

            for (String s : set) {
                try {
                    int host = (int) Long.parseLong(s, 16);

                    hosts.add(host);
                } catch (Exception e) {
                }
            }
        }

        _xServer.setAccessControl(!hosts.isEmpty());
    }

    /**
     * Called when an activity returns a result.
     */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == ACTIVITY_ACCESS_CONTROL && resultCode == RESULT_OK) setAccessControl();
    }

    /**
     * Launch the access control list editor.
     */
    private void launchAccessControlEditor() {
        Intent intent = new Intent(this, AccessControlEditor.class);

        startActivityForResult(intent, ACTIVITY_ACCESS_CONTROL);
    }

    /**
     * Launch an application.
     */
    private boolean launchApp(String pkg, String cls) {
        Intent intent = new Intent(Intent.ACTION_MAIN);

        intent.setComponent(new ComponentName(pkg, cls));
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            return false;
        }

        return true;
    }

    /**
     * Launch an application that will allow an SSH login.
     */
    private void launchSshApp() {
        if (launchApp("org.connectbot", "org.connectbot.HostListActivity")) return;
        if (launchApp("com.madgag.ssh.agent", "com.madgag.ssh.agent.HostListActivity")) return;
        if (launchApp("sk.vx.connectbot", "sk.vx.connectbot.HostListActivity")) return;

        Toast.makeText(this, "The ConnectBot application needs to be installed", Toast.LENGTH_LONG).show();
    }

    
    private void extractPlugins() {
        new Thread(() -> {
        final String assetSubDir = "plugins";
        File pluginsDir = new File(getFilesDir(), assetSubDir);

        // 1. Asegurarse de que el directorio de destino exista
        try {
            if (!pluginsDir.exists()) {
                if (pluginsDir.mkdirs()) {
                    runOnUiThread(() -> appendLog("📁 Directorio plugins creado: " + pluginsDir.getAbsolutePath()));
                } else {
                    runOnUiThread(() -> appendLog("❌ ERROR: No se pudo crear el directorio plugins: " + pluginsDir.getAbsolutePath()));
                    return; // Error fatal, no se puede continuar
                }
            } else {
                 runOnUiThread(() -> appendLog("ℹ️ Directorio plugins ya existe: " + pluginsDir.getAbsolutePath()));
            }
        } catch (Exception e) {
             runOnUiThread(() -> appendLog("💥 ERROR al crear directorio plugins: " + e.getMessage()));
             return;
        }

        // 2. Listar todos los archivos dentro de assets/plugins
        String[] assetFiles;
        try {
            assetFiles = getAssets().list(assetSubDir);
            if (assetFiles == null || assetFiles.length == 0) {
                runOnUiThread(() -> appendLog("⚠️ No se encontraron archivos en assets/" + assetSubDir));
                return;
            }
            runOnUiThread(() -> appendLog("ℹ️ Encontrados " + assetFiles.length + " archivos en assets/" + assetSubDir));
        } catch (IOException e) {
            runOnUiThread(() -> appendLog("❌ ERROR: No se pudo listar assets/" + assetSubDir + ": " + e.getMessage()));
            return;
        }

        // 3. Iterar y extraer cada archivo
        int successCount = 0;
        int failCount = 0;

        for (String filename : assetFiles) {
            if (filename.isEmpty()) continue;
            
            File destFile = new File(pluginsDir, filename);
            
            if (destFile.exists()) {
                runOnUiThread(() -> appendLog("✅ Plugin ya existe: " + filename));
                successCount++;
                continue;
            }

            String assetFilePath = assetSubDir + "/" + filename;
            try (InputStream in = getAssets().open(assetFilePath);
                 FileOutputStream out = new FileOutputStream(destFile)) {

                byte[] buffer = new byte[8192];
                int read;
                long totalBytes = 0;
                
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    totalBytes += read;
                }
                
                destFile.setExecutable(true, false);
                
                final long totalBytesFinal = totalBytes;
                // NOTA: Esta lambda interna usa totalBytesFinal, que es final.
                runOnUiThread(() -> appendLog("✅ Plugin extraído: " + filename + " (" + totalBytesFinal + " bytes)"));
                successCount++;

            } catch (IOException e) {
                runOnUiThread(() -> appendLog("❌ ERROR extrayendo " + filename + ": " + e.getMessage()));
                failCount++;
            }
        }
        
        // 4. Reporte final
        // --- ¡CORRECCIÓN AQUÍ! ---
        // Se crean copias finales de los contadores para pasarlas a la lambda de runOnUiThread.
        final int finalSuccessCount = successCount; 
        final int finalFailCount = failCount;
        final File finalPluginsDir = pluginsDir; // pluginsDir es efectivamente final, pero es buena práctica en estos casos.
        // -------------------------

        runOnUiThread(() -> {
            appendLog("--- Resumen de Extracción de Plugins ---");
            appendLog("Éxitos (nuevos + existentes): " + finalSuccessCount);
            appendLog("Fallos: " + finalFailCount);
            
            String[] finalFiles = finalPluginsDir.list();
             if (finalFiles != null && finalFiles.length > 0) {
                appendLog("📁 Contenido final: " + String.join(", ", finalFiles));
            } else {
                appendLog("❌ Directorio de plugins está vacío.");
            }
        });

    }).start();
}

private void extractAssets() {
    new Thread(() -> {
        try {
            String[] files = getAssets().list(""); // lista la raíz de assets
            if (files == null) return;

            for (String filename : files) {
                // saltear directorios (plugins ya lo maneja extractPlugins)
                String[] sub = getAssets().list(filename);
                if (sub != null && sub.length > 0) continue; // es directorio

                File destFile = new File(getFilesDir(), filename);
                if (destFile.exists()) {
                    runOnUiThread(() -> appendLog("✅ Ya existe: " + filename));
                    continue;
                }

                runOnUiThread(() -> appendLog("Extrayendo: " + filename));
                try (InputStream in = getAssets().open(filename);
                     FileOutputStream out = new FileOutputStream(destFile)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
                    runOnUiThread(() -> appendLog("✅ OK: " + filename));
                } catch (IOException e) {
                    runOnUiThread(() -> appendLog("❌ Error: " + filename + " - " + e.getMessage()));
                }
            }
        } catch (Exception e) {
            final String error = e.getMessage();
            runOnUiThread(() -> appendLog("ERROR extractAssets: " + error));
        }
    }).start();
}

    private void appendLog(final String msg) {
        Log.i("CuisApp", "APP-LOG: " + msg); // ✅ FORZAR LOG
    }
}