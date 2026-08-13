package au.com.darkside.x11server;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.Service;
import android.app.NotificationManager;
import android.app.Notification;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.ContentValues;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.database.Cursor;
import android.os.Build;
import android.os.FileObserver;
import android.os.Environment;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.view.Menu;
import android.view.MenuItem;
import android.view.WindowManager;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.graphics.Color;
import android.widget.Toast;

import android.app.ProgressDialog;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

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
import java.io.RandomAccessFile;
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

    /**
     * The native side's startup log (which libs loaded, which failed and why).
     * squeak_jni.c has always exported this, but Java never declared it — so the
     * diagnostics it collects were unreachable. Used when startVMNative() fails.
     */
    public native String getLastError();

    private static final String TAG = "Cuis";

    private XServer _xServer;
    private ScreenView _screenView;
    private boolean _controlsExpanded = false;  // floating menu drawer state
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
    private static final int MENU_ZOOM = 11;
    private static final int MENU_LOAD_IMAGE = 12;
    private static final int MENU_TOGGLE_LONGPRESS = 13;
    private static final int MENU_TOGGLE_POINTER = 14;
    private static final int ACTIVITY_ACCESS_CONTROL = 1;
    private static final int ACTIVITY_LOAD_IMAGE = 2;
    private static final int ACTIVITY_LOAD_CHANGES = 3;
    private static final int ACTIVITY_FILE_IN = 4;

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

        // Android 6.0–9 need a runtime grant of WRITE_EXTERNAL_STORAGE for the
        // fileout export to Downloads (10+ uses MediaStore, no permission). Ask
        // once; if declined, exports are skipped quietly and everything else works.
        if (Build.VERSION.SDK_INT >= 23 && Build.VERSION.SDK_INT < 29
                && checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                   != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE }, 42);

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
            File filesDir = getFilesDir();
            File marker = new File(filesDir, ".custom_image");
            // Nothing chosen yet? Don't auto-boot the bundled image — show the
            // "Load image" chooser (download Squeak/Cuis, or browse the device).
            if (!marker.exists()) {
                showLoadImageDialog();
                return;
            }

            File image = new File(filesDir, currentImageName());
            File bootPending = new File(filesDir, ".boot_pending");

            if (!image.isFile()) {
                Log.w(TAG, "chosen image " + image.getName() + " is gone — back to the chooser");
                marker.delete();
                bootPending.delete();  // a stale one must not condemn the NEXT choice
                showLoadImageDialog("That image is no longer on the device. Pick another.");
                return;
            }

            // Don't brick the app on an image that can't boot (a 32-bit image, say,
            // makes the 64-bit VM abort the whole process → the app "dies" and, since
            // the marker + bad image persist, keeps dying every launch). Two guards:
            //  (a) crash-loop: the previous boot wrote .boot_pending and never cleared
            //      it (a healthy boot clears it a few seconds in), so it died early;
            //  (b) the image isn't a 64-bit Spur image (wrong word size).
            if (bootPending.exists() || is32BitSpurImage(image)) {
                Log.w(TAG, "previous image failed to boot (pending=" + bootPending.exists()
                        + ", 32bit=" + is32BitSpurImage(image) + ") — back to the chooser");
                bootPending.delete();
                marker.delete();  // clears the loop; the chooser opens instead
                showLoadImageDialog("The previous image couldn't start — it needs to be a "
                        + "64-bit Spur image. Pick another.");
                return;
            }

            try {
                String libPath = getApplicationInfo().nativeLibraryDir + "/libsqueak.so";
                String imagePath = image.getAbsolutePath();
                String pluginsPath = filesDir.getAbsolutePath() + "/plugins";

                Log.i(TAG, "Lanzando VM");
                Log.i(TAG, "libPath=" + libPath);
                Log.i(TAG, "imagePath=" + imagePath);
                Log.i(TAG, "pluginsPath=" + pluginsPath);

                // Drop any trailing "lost changes" (a dangling ----STARTUP---- that
                // Cuis wrote last boot and Android killed before a clean quit) so the
                // image doesn't pop the "Last changes may have been lost" dialog.
                pruneChangesFile();

                // Fresh per-boot image adaptation script (fileout-to-Downloads patch,
                // author-initials preseed, script chaining) — see writeAndroidSetupScript.
                writeAndroidSetupScript();

                // Mark the boot in-progress; a healthy run clears it below. If the VM
                // aborts on a bad image, this file survives → next launch recovers (a).
                try { bootPending.createNewFile(); } catch (IOException ignore) {}

                int res = startVMNative(libPath, imagePath, pluginsPath);
                Log.i(TAG, "startVMNative() retornó: " + res);

                if (res != 0) {
                    // The native side could not load the VM / find its entry point.
                    // Without this the screen just stayed black with no explanation.
                    Log.e(TAG, "VM no pudo iniciar (" + res + "): " + getLastError());
                    bootPending.delete();
                    showLoadImageDialog("The VM could not start with that image. Pick another.");
                    return;
                }
                _vmRunning = true;

                // Still alive a few seconds later ⇒ the image booted fine.
                _screenView.postDelayed(() -> {
                    if (bootPending.delete()) Log.i(TAG, "boot healthy; cleared .boot_pending");
                    // A queued File in… script was read at startup — remove it so
                    // it runs exactly once (deleting the file doesn't affect the
                    // already-scheduled evaluation).
                    File pf = new File(filesDir, "pending-filein.st");
                    if (pf.delete()) Log.i(TAG, "consumed pending-filein.st");
                }, 7000);

                // Copy fileouts (.st/.cs) the image writes into the user-visible
                // Downloads/OpenSmalltalk/ folder as they appear.
                startFileoutWatcher();

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

        // On-screen access to the options menu + soft keyboard. Phones have no
        // hardware MENU key and the ActionBar is hidden in fullscreen, so without
        // this there's no way to reach "Load image…" or bring up the keyboard.
        addFloatingControls(fl);

        // Keep what you're typing visible: the soft keyboard overlays the lower half
        // and would hide the text you're editing. When it's up, pan the X view up
        // just enough to lift the pointer/caret row above the keyboard (and reset
        // when it's dismissed). We don't use adjustResize — resizing the view would
        // resize the whole X display and reflow the Smalltalk world.
        fl.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            if (_screenView == null) return;
            int viewH = _screenView.getHeight();
            if (viewH <= 0) return;
            // Classic keyboard-height detection (compileSdk 29 has no WindowInsets.Type):
            // the visible display frame shrinks by the IME height when it's up.
            android.graphics.Rect r = new android.graphics.Rect();
            _screenView.getWindowVisibleDisplayFrame(r);
            int imeH = Math.max(0, _screenView.getRootView().getHeight() - r.bottom);
            float ty = 0f;
            if (imeH > viewH * 0.15f) {  // keyboard is up
                float scale = _screenView.getDisplayScale();
                int caretY = Math.round(_screenView.getPointerY() * scale);  // physical caret y
                int keyboardTop = viewH - imeH;
                int over = caretY - (keyboardTop - dp(28));
                if (over > 0) ty = -over;
            }
            if (_screenView.getTranslationY() != ty) _screenView.setTranslationY(ty);
        });

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
        stopFileoutWatcher();
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

        item = menu.add(0, MENU_TOGGLE_LONGPRESS, 0, "Long-press menu (off)");
        item.setIcon(android.R.drawable.star_off);

        item = menu.add(0, MENU_TOGGLE_POINTER, 0, "Mouse pointer (on)");
        item.setIcon(android.R.drawable.star_on);

        item = menu.add(0, MENU_LOAD_IMAGE, 0, "Load image…");

        float zoom = 1.0f;
        try { zoom = _xServer.getScreen().getDisplayScale(); } catch (Exception e) { }
        item = menu.add(0, MENU_ZOOM, 0, "Zoom (" + zoom + "x)");

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
            case MENU_LOAD_IMAGE:
                showLoadImageDialog();
                return true;
            case MENU_ZOOM: {
                float s = _xServer.getScreen().cycleDisplayScale();
                item.setTitle("Zoom (" + s + "x)");
                item.setIcon(s > 1.0f ? android.R.drawable.star_on : android.R.drawable.star_off);
                return true;
            }
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
            case MENU_TOGGLE_LONGPRESS:
                if (_xServer.getScreen().toggleLongPressMenu()) {
                    item.setIcon(android.R.drawable.star_on);
                    item.setTitle("Long-press menu (on)");
                } else {
                    item.setIcon(android.R.drawable.star_off);
                    item.setTitle("Long-press menu (off)");
                }
                return true;
            case MENU_TOGGLE_POINTER:
                if (_xServer.getScreen().toggleShowPointer()) {
                    item.setIcon(android.R.drawable.star_on);
                    item.setTitle("Mouse pointer (on)");
                } else {
                    item.setIcon(android.R.drawable.star_off);
                    item.setTitle("Mouse pointer (off)");
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
        if (requestCode == ACTIVITY_ACCESS_CONTROL && resultCode == RESULT_OK) {
            setAccessControl();
            return;
        }
        if (requestCode == ACTIVITY_FILE_IN) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null)
                handleFileInPick(data.getData());
            return;
        }
        if (requestCode == ACTIVITY_LOAD_IMAGE) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                Uri imgUri = data.getData();
                // Keep the picked file's real name so it stays its own entry in the
                // Load-image library instead of overwriting the previous image.
                String name = displayNameForUri(imgUri);
                if (name != null) name = name.trim();
                if (name == null || !name.endsWith(".image") || name.length() <= ".image".length())
                    name = "Imported.image";
                File image = new File(getFilesDir(), name);
                // Copy to a temp file and validate BEFORE moving into place, so a
                // rejected pick can't destroy a same-named image already in the library.
                File part = new File(image.getPath() + ".part");
                if (copyUriToFile(imgUri, part)) {
                    // Reject a 32-bit image up front (the 64-bit VM would abort on it)
                    // so we never set the marker / restart into a boot that bricks.
                    if (is32BitSpurImage(part)) {
                        part.delete();
                        showLoadImageDialog("That image is 32-bit — the VM needs a 64-bit "
                                + "Spur image. Pick another.");
                        return;
                    }
                    if (!part.renameTo(image)) {
                        part.delete();
                        Toast.makeText(this, "Could not store the selected image.", Toast.LENGTH_LONG).show();
                        return;
                    }
                    // Assume the .changes lives next to the .image (same folder) and
                    // grab it automatically — no second picker.
                    copySiblingChanges(imgUri, imageBase(name) + ".changes");
                    copySiblingSources(imgUri, name);
                    setCurrentImageName(name);
                    Toast.makeText(this, "Image loaded — restarting.", Toast.LENGTH_SHORT).show();
                    restartApp();
                } else {
                    part.delete();
                    Toast.makeText(this, "Could not read the selected image.", Toast.LENGTH_LONG).show();
                }
            }
            return;
        }
    }

    /** The user-visible file name of a SAF document, or null. */
    private String displayNameForUri(Uri uri) {
        try (Cursor c = getContentResolver().query(uri,
                new String[]{ OpenableColumns.DISPLAY_NAME }, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                // keep it a safe flat filename
                if (n != null) return new File(n).getName();
            }
        } catch (Exception e) {
            Log.i(TAG, "no display name for " + uri + ": " + e.getMessage());
        }
        return null;
    }

    /**
     * Copy the .changes file that sits beside the picked .image (same directory) into
     * filesDir under the given name (the image's base + ".changes", so the VM finds
     * it). Derives the sibling document URI by swapping the extension in the document
     * id (works for on-device providers: Downloads, storage). If there's no sibling,
     * boot without a changes file (stable) rather than keeping a mismatched one.
     */
    private void copySiblingChanges(Uri imgUri, String changesName) {
        File changes = new File(getFilesDir(), changesName);
        try {
            String docId = DocumentsContract.getDocumentId(imgUri);
            if (docId != null && docId.toLowerCase().endsWith(".image")) {
                String chgId = docId.substring(0, docId.length() - ".image".length()) + ".changes";
                Uri chgUri = DocumentsContract.buildDocumentUri(imgUri.getAuthority(), chgId);
                if (copyUriToFile(chgUri, changes)) {
                    Log.i(TAG, "copied sibling .changes");
                    return;
                }
            }
        } catch (Exception e) {
            Log.i(TAG, "no sibling .changes: " + e.getMessage());
        }
        if (changes.exists()) changes.delete();  // boot without changes rather than mismatched
    }

    /**
     * Try to bring in the .sources file sitting next to a picked .image.
     *
     * Two things make this a guess rather than a lookup. A .sources is NOT named
     * after its image — Cuis7.7-7976.image wants Cuis7.6.sources, Squeak 6.0 wants
     * SqueakV60.sources — because the name comes from the image's internal
     * SourceFileVersionString, which we cannot read from out here. And
     * ACTION_OPEN_DOCUMENT grants access to one document, not its folder, so the
     * directory cannot be listed. So: try the handful of names upstream actually
     * ships, and give up quietly.
     *
     * Getting this right matters more than it looks — without it Cuis opens a modal
     * "cannot locate the sources file" warning on every single launch, and the
     * browser shows decompiled code with no comments.
     */
    private void copySiblingSources(Uri imgUri, String imageName) {
        String[] candidates = imageName.toLowerCase().startsWith("squeak")
                ? new String[]{ "SqueakV60.sources", "SqueakV50.sources", "SqueakV41.sources" }
                : new String[]{ "Cuis7.8.sources", "Cuis7.6.sources", "Cuis7.4.sources",
                                "Cuis7.2.sources", "Cuis7.0.sources", "Cuis6.0.sources",
                                "Cuis5.0.sources" };
        // Anything already here (e.g. downloaded earlier) is good enough — the VM
        // looks the file up by name, so a second copy buys nothing.
        for (String c : candidates) if (new File(getFilesDir(), c).isFile()) return;
        try {
            String docId = DocumentsContract.getDocumentId(imgUri);
            if (docId == null) return;
            int slash = Math.max(docId.lastIndexOf('/'), docId.lastIndexOf(':'));
            String dir = slash >= 0 ? docId.substring(0, slash + 1) : "";
            for (String cand : candidates) {
                File dst = new File(getFilesDir(), cand);
                try {
                    Uri u = DocumentsContract.buildDocumentUri(imgUri.getAuthority(), dir + cand);
                    if (copyUriToFile(u, dst)) { Log.i(TAG, "copied sibling " + cand); return; }
                } catch (Exception ignored) { /* next candidate */ }
                if (dst.exists()) dst.delete();
            }
        } catch (Exception e) {
            Log.i(TAG, "no sibling .sources: " + e.getMessage());
        }
    }

    /**
     * Launch the access control list editor.
     */
    private void launchAccessControlEditor() {
        Intent intent = new Intent(this, AccessControlEditor.class);

        startActivityForResult(intent, ACTIVITY_ACCESS_CONTROL);
    }

    /**
     * Small semi-transparent overlay (top-left) with a menu button and a keyboard
     * button, so the app is usable on phones with no hardware MENU key.
     */
    /**
     * The custom title for the Load-image dialog: the "Load image" heading plus a
     * dark rounded panel crediting FAST ("Funded by" + the transparent-background
     * logo), so the funding shows every time the user loads an image — right in the
     * dialog, framed dark for contrast on the light dialog background.
     */
    private View buildLoadImageTitle(String note) {
        android.widget.LinearLayout root = new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(18), dp(22), dp(4));

        // Credit panel first (dark, framing the transparent logo): the funding line
        // above the logo, then the FAST logo.
        android.widget.LinearLayout panel = new android.widget.LinearLayout(this);
        panel.setOrientation(android.widget.LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER_HORIZONTAL);
        panel.setPadding(dp(16), dp(12), dp(16), dp(12));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(0xff1a1d3a);        // dark navy — matches the logo, frames it on white
        bg.setCornerRadius(dp(10));
        panel.setBackground(bg);

        android.widget.TextView funded = new android.widget.TextView(this);
        funded.setText("OpenSmalltalk for Android is funded by");
        funded.setTextColor(0xffdddddd);
        funded.setTextSize(13);
        funded.setGravity(Gravity.CENTER_HORIZONTAL);
        funded.setPadding(0, 0, 0, dp(8));
        panel.addView(funded);

        android.widget.ImageView logo = new android.widget.ImageView(this);
        logo.setImageResource(R.drawable.fast_logo);
        logo.setAdjustViewBounds(true);
        logo.setContentDescription("Fundación Argentina de Smalltalk (FAST)");
        panel.addView(logo, new android.widget.LinearLayout.LayoutParams(
                dp(210), android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));

        root.addView(panel, new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));

        // "Load image" heading BELOW the logo.
        android.widget.TextView heading = new android.widget.TextView(this);
        heading.setText("Load image");
        heading.setTextColor(0xff222222);
        heading.setTextSize(20);
        heading.setTypeface(heading.getTypeface(), android.graphics.Typeface.BOLD);
        android.widget.LinearLayout.LayoutParams hlp = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = dp(16);
        root.addView(heading, hlp);

        // Why we got sent back here, if we were. AlertDialog's setMessage is
        // unavailable once setItems is used (the list replaces the message view), and
        // a Toast is gone before the user has finished reading it — so the
        // explanation goes here, directly above the options they will retry with.
        if (note != null && !note.isEmpty()) {
            android.widget.TextView warn = new android.widget.TextView(this);
            warn.setText(note);
            warn.setTextColor(0xffb00020);
            warn.setTextSize(13);
            android.widget.LinearLayout.LayoutParams wlp = new android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
            wlp.topMargin = dp(8);
            root.addView(warn, wlp);
        }
        return root;
    }

    private void addFloatingControls(FrameLayout fl) {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);

        // a solid, rounded dark pill (no washed-out alpha)
        android.graphics.drawable.GradientDrawable pill = new android.graphics.drawable.GradientDrawable();
        pill.setColor(0xE62A2A2E);
        pill.setCornerRadius(dp(22));
        pill.setStroke(dp(1), 0x33FFFFFF);
        bar.setBackground(pill);
        bar.setPadding(dp(4), dp(2), dp(4), dp(2));

        Button menuBtn = makeIconButton("☰");   // options menu (Load image…, Zoom, …)
        menuBtn.setOnClickListener(v -> showOptionsDialog());
        Button kbdBtn = makeIconButton("⌨");    // toggle the soft keyboard
        kbdBtn.setOnClickListener(v -> toggleKeyboard());
        Button rclickBtn = makeIconButton("⊙"); // arm the next tap as a right-click
        rclickBtn.setOnClickListener(v -> {
            _xServer.getScreen().armRightClick();
            Toast.makeText(this, "Next tap = right-click (context menu)", Toast.LENGTH_SHORT).show();
        });
        // Collapsed by default so the menu doesn't sit over the image — only the
        // small handle shows; tapping it slides the buttons out.
        menuBtn.setVisibility(View.GONE);
        kbdBtn.setVisibility(View.GONE);
        rclickBtn.setVisibility(View.GONE);

        final Button handle = makeIconButton("‹");  // the minimal always-visible tab
        handle.setOnClickListener(v -> {
            _controlsExpanded = !_controlsExpanded;
            menuBtn.setVisibility(_controlsExpanded ? View.VISIBLE : View.GONE);
            kbdBtn.setVisibility(_controlsExpanded ? View.VISIBLE : View.GONE);
            rclickBtn.setVisibility(_controlsExpanded ? View.VISIBLE : View.GONE);
            handle.setText(_controlsExpanded ? "›" : "‹");
        });

        bar.addView(menuBtn);
        bar.addView(kbdBtn);
        bar.addView(rclickBtn);
        bar.addView(handle);

        // Bottom-right corner, out of the way of the Smalltalk world/menus.
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM | Gravity.END;
        lp.setMargins(0, 0, dp(10), dp(16));
        fl.addView(bar, lp);
    }

    private Button makeIconButton(String glyph) {
        Button b = new Button(this);
        b.setText(glyph);
        b.setTextColor(Color.WHITE);
        b.setTextSize(20);
        b.setBackgroundColor(Color.TRANSPARENT);
        b.setMinWidth(0);
        b.setMinHeight(0);
        b.setPadding(dp(16), dp(6), dp(16), dp(6));
        b.setIncludeFontPadding(false);
        return b;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** Bring up / dismiss the Android soft keyboard, aimed at the Smalltalk view. */
    private void toggleKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Service.INPUT_METHOD_SERVICE);
        if (imm == null || _screenView == null) return;
        _screenView.requestFocus();
        imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0);
    }

    /**
     * The ☰ options menu — a curated, opaque dialog (the old Android options panel
     * was translucent and hard to read over the Smalltalk world, and full of X-server
     * legacy items: IP address, Access control, Remote login, Window Manager, etc.).
     * Only what a Smalltalk-on-phone user needs is kept here.
     */
    private void showOptionsDialog() {
        final ScreenView sv = _xServer.getScreen();
        float zoom = 1.0f;
        try { zoom = sv.getDisplayScale(); } catch (Exception e) { }
        final String[] labels = {
                "Load image…",
                "File in code (.st)…",
                "Zoom (" + zoom + "×)",
                "Smooth zoom: " + (sv.isSmoothZoom() ? "on" : "off"),
                "Trackpad mode: " + (sv.isTrackpadMode() ? "on" : "off"),
                "Precise pointer: " + (sv.isPreciseTouch() ? "on" : "off"),
                "Mouse pointer: " + (sv.isShowPointer() ? "on" : "off"),
                "Shared clipboard: " + (sv.isSharedClipboard() ? "on" : "off"),
                "Long-press menu: " + (sv.isLongPressMenuEnabled() ? "on" : "off"),
                "Screen orientation",
        };
        new AlertDialog.Builder(this)
                .setTitle("Options")
                .setItems(labels, (dialog, which) -> {
                    switch (which) {
                        case 0: showLoadImageDialog(); break;
                        case 1: launchFileInPicker(); break;
                        case 2: showZoomDialog(); break;
                        case 3: {
                            boolean on = sv.toggleSmoothZoom();
                            Toast.makeText(this, on
                                    ? "Smooth zoom (better for images; softer text)."
                                    : "Crisp zoom (nearest-neighbour; best for text).", Toast.LENGTH_SHORT).show();
                            break;
                        }
                        case 4: {
                            boolean on = sv.toggleTrackpadMode();
                            Toast.makeText(this, on
                                    ? "Trackpad: slide to move the pointer, tap to click, hold+drag to drag."
                                    : "Trackpad off (direct touch).", Toast.LENGTH_LONG).show();
                            break;
                        }
                        case 5: sv.togglePreciseTouch(); break;
                        case 6: sv.toggleShowPointer(); break;
                        case 7: sv.toggleSharedClipboard(); break;
                        case 8: sv.toggleLongPressMenu(); break;
                        case 9: toggleOrientation(); break;
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }

    /**
     * Pick a zoom level directly (better than cycling, and goes higher). Whole-number
     * zooms are pixel-crisp because the upscale is nearest-neighbour; fractional ones
     * (e.g. 1.75×) scale unevenly and look softer — so they're labelled.
     */
    private void showZoomDialog() {
        final ScreenView sv = _xServer.getScreen();
        final float[] levels = { 1.0f, 1.5f, 2.0f, 2.5f, 3.0f, 4.0f };
        float cur = 1.0f;
        try { cur = sv.getDisplayScale(); } catch (Exception e) { }
        final String[] labels = new String[levels.length];
        for (int i = 0; i < levels.length; i++) {
            boolean whole = levels[i] == Math.rint(levels[i]);
            boolean current = Math.abs(levels[i] - cur) < 0.01f;
            labels[i] = (current ? "●  " : "○  ") + levels[i] + "×" + (whole ? "   (sharp)" : "");
        }
        new AlertDialog.Builder(this)
                .setTitle("Zoom — whole numbers are sharpest")
                .setItems(labels, (dialog, which) -> sv.setDisplayScale(levels[which]))
                .setNegativeButton("Close", null)
                .show();
    }

    /** Flip between portrait and landscape (locks to the chosen one). */
    private void toggleOrientation() {
        if (getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE)
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        else
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
    }

    /** "Load image…" → open a local image, download Squeak/Cuis/Cuis University, or browse. */
    private void showLoadImageDialog() { showLoadImageDialog(null); }

    /**
     * The image library. Every image ever downloaded / picked / saved-as stays in
     * filesDir under its own name, so it is listed here and reopens offline with one
     * tap. The download entries always follow, then the device picker, then (when
     * there is something to delete) a delete submenu to reclaim space.
     */
    private void showLoadImageDialog(String message) {
        final File[] local = localImages();
        final java.util.ArrayList<String> items = new java.util.ArrayList<>();
        for (File f : local)
            items.add(imageBase(f.getName()) + "  (on device)");
        items.add("Squeak (download)");
        items.add("Cuis (download)");
        items.add("Cuis University (download)");
        items.add("From device…");
        if (local.length > 0) items.add("Delete an image…");
        new AlertDialog.Builder(this)
                .setCustomTitle(buildLoadImageTitle(message))
                .setItems(items.toArray(new String[0]), (dialog, which) -> {
                    if (which < local.length) { openLocalImage(local[which]); return; }
                    int k = which - local.length;
                    if (k == 0) downloadAndLoad("Squeak");
                    else if (k == 1) downloadAndLoad("Cuis");
                    else if (k == 2) downloadAndLoad("Cuis University");
                    else if (k == 3) launchImagePicker();
                    else showDeleteImageDialog();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Local .image files in filesDir, most recently used (mtime) first. */
    private File[] localImages() {
        File[] all = getFilesDir().listFiles(
                f -> f.isFile() && f.getName().endsWith(".image"));
        if (all == null) return new File[0];
        java.util.Arrays.sort(all, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return all;
    }

    private String imageBase(String fileName) {
        return fileName.endsWith(".image")
                ? fileName.substring(0, fileName.length() - ".image".length()) : fileName;
    }

    /** Boot an image that is already in filesDir — no download, works offline. */
    private void openLocalImage(File image) {
        if (is32BitSpurImage(image)) {
            showLoadImageDialog("That image is 32-bit — the VM needs a 64-bit Spur image.");
            return;
        }
        setCurrentImageName(image.getName());
        Toast.makeText(this, "Opening " + imageBase(image.getName()) + "…", Toast.LENGTH_SHORT).show();
        restartApp();
    }

    private void showDeleteImageDialog() {
        final File[] local = localImages();
        if (local.length == 0) { showLoadImageDialog(); return; }
        final String[] names = new String[local.length];
        for (int i = 0; i < local.length; i++)
            names[i] = imageBase(local[i].getName())
                     + "  (" + (local[i].length() >> 20) + " MB)";
        new AlertDialog.Builder(this)
                .setTitle("Delete which image?")
                .setItems(names, (dialog, which) -> {
                    File img = local[which];
                    boolean wasCurrent = img.getName().equals(currentImageName());
                    if (wasCurrent && _vmRunning) {
                        // The VM holds this file open: deleting it would silently
                        // unlink the session's history, and an in-image Save would
                        // resurrect the file behind the library's back.
                        Toast.makeText(this, "That image is open right now — "
                                + "switch to another image first.", Toast.LENGTH_LONG).show();
                        showLoadImageDialog();
                        return;
                    }
                    File chg = new File(getFilesDir(), imageBase(img.getName()) + ".changes");
                    img.delete(); chg.delete();
                    if (wasCurrent) new File(getFilesDir(), ".custom_image").delete();
                    Toast.makeText(this, "Deleted.", Toast.LENGTH_SHORT).show();
                    showLoadImageDialog();
                })
                .setNegativeButton("Cancel", (d, w) -> showLoadImageDialog())
                .show();
    }

    /**
     * Which image in filesDir boots. Stored as the contents of the .custom_image
     * marker; an EMPTY marker (legacy installs, push-image.sh) means "Cuis.image".
     */
    private String currentImageName() {
        File marker = new File(getFilesDir(), ".custom_image");
        try (InputStream in = new java.io.FileInputStream(marker)) {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[256]; int n;
            while ((n = in.read(buf)) != -1) bo.write(buf, 0, n);
            String name = bo.toString("UTF-8").trim();
            if (!name.isEmpty() && !name.contains("/") && name.endsWith(".image"))
                return name;
        } catch (Exception ignore) { }
        return "Cuis.image";
    }

    private void setCurrentImageName(String name) {
        try (FileOutputStream out = new FileOutputStream(
                new File(getFilesDir(), ".custom_image"))) {
            // trim: currentImageName() trims on read, so keep the two symmetric
            out.write(name.trim().getBytes("UTF-8"));
        } catch (IOException e) {
            Log.e(TAG, "could not write .custom_image marker", e);
        }
    }

    /**
     * True if the file's image-format magic is a known 32-bit (or V3) Squeak format,
     * which the bundled 64-bit Spur VM can't boot. Reads the first 4 bytes as a
     * little-endian format number. 64-bit Spur images (68021 / 68531 / 68533) → false.
     */
    private boolean is32BitSpurImage(File image) {
        int fmt = imageFormatMagic(image);   // -1 (unreadable) → let the boot path's
        return fmt == 6521 || fmt == 6505 || fmt == 6504;  // crash-loop guard cover it
    }

    /**
     * Read an image's 4-byte format magic. -1 if it can't be read.
     * 68021 = Cuis/Squeak 64-bit Spur, 68531/68533 = Squeak 6.0.
     */
    private int imageFormatMagic(File image) {
        if (image == null || !image.isFile()) return -1;
        try (InputStream in = new java.io.FileInputStream(image)) {
            byte[] b = new byte[4];
            int n = 0; while (n < 4) { int r = in.read(b, n, 4 - n); if (r < 0) break; n += r; }
            if (n < 4) return -1;
            return (b[0] & 0xFF) | ((b[1] & 0xFF) << 8) | ((b[2] & 0xFF) << 16) | ((b[3] & 0xFF) << 24);
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Throw unless this file really is a 64-bit Spur image we can boot. Called on
     * anything freshly downloaded, before it becomes the image the app restarts
     * into — the point being to fail while the chooser is still on screen and can
     * say why, instead of after a restart into a VM abort.
     */
    private void assertUsableImage(File image) throws IOException {
        if (image == null || !image.isFile())
            throw new IOException("the download produced no image file");
        // Real 64-bit images are tens of MB; anything tiny is an error page or a stub.
        if (image.length() < 1024 * 1024)
            throw new IOException("the downloaded file is only " + image.length()
                    + " bytes — the server sent something that is not an image "
                    + "(a captive portal or a login page can do this)");
        int fmt = imageFormatMagic(image);
        if (fmt == 68021 || fmt == 68531 || fmt == 68533) return;
        image.delete();
        if (fmt == 6521 || fmt == 6505 || fmt == 6504)
            throw new IOException("that image is 32-bit — this VM needs a 64-bit Spur image");
        throw new IOException("the downloaded file is not a Smalltalk image "
                + "(format magic " + fmt + ") — the network may have intercepted the download");
    }

    /** Download a Squeak/Cuis/Cuis University image into filesDir on a background thread, then restart. */
    private void downloadAndLoad(final String flavor) {
        final ProgressDialog pd = new ProgressDialog(this);
        pd.setTitle("Downloading " + flavor);
        pd.setMessage("Contacting server…");
        pd.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        pd.setIndeterminate(true);
        pd.setCancelable(false);
        pd.setMax(100);
        pd.show();
        new Thread(() -> {
            try {
                String imageName;
                if (flavor.equals("Squeak")) imageName = downloadSqueak(pd);
                else if (flavor.equals("Cuis University")) imageName = downloadCuisUniversity(pd);
                else imageName = downloadCuis(pd);
                // Never hand the boot path something we have not looked at. Whatever
                // arrived is about to be recorded as THE image and the app restarts
                // into it, so a captive portal's HTML login page — served with a
                // cheerful 200 — would be booted as a Spur image, the VM would abort,
                // and the user would land on "the previous image couldn't start",
                // which reads exactly like "downloading is broken".
                File got = new File(getFilesDir(), imageName);
                assertUsableImage(got);
                // Remember which image boots; the file keeps its real name, so it also
                // stays in the Load-image list for offline reopening later.
                setCurrentImageName(imageName);
                runOnUiThread(() -> {
                    pd.dismiss();
                    Toast.makeText(this, flavor + " loaded — restarting.", Toast.LENGTH_SHORT).show();
                    restartApp();
                });
            } catch (Exception e) {
                Log.e(TAG, "download failed", e);
                // getMessage() is null for plenty of IO exceptions ("Download failed:
                // null" helps nobody), so fall back to the exception itself.
                final String msg = (e.getMessage() != null && !e.getMessage().isEmpty())
                        ? e.getMessage() : e.toString();
                runOnUiThread(() -> {
                    pd.dismiss();
                    // Reopen the chooser rather than only toasting. On a first run there
                    // is nothing behind this dialog — a bare Toast leaves the user
                    // staring at an empty app with no way back in.
                    showLoadImageDialog("Download failed: " + msg);
                });
            }
        }).start();
    }

    private String downloadSqueak(ProgressDialog pd) throws Exception {
        setProgressMsg(pd, "Finding latest build…");
        String listing = httpGetString("https://files.squeak.org/6.0/");
        Matcher m = Pattern.compile("Squeak6\\.0-(\\d+)-64bit").matcher(listing);
        int best = -1;
        while (m.find()) { int b = Integer.parseInt(m.group(1)); if (b > best) best = b; }
        if (best < 0) throw new Exception("no Squeak 6.0 build listed");
        String baseName = "Squeak6.0-" + best + "-64bit";
        String url = "https://files.squeak.org/6.0/" + baseName + "/" + baseName + ".zip";
        // ~25 MB zip, and the image alone unpacks to ~50 MB with ~55 MB of sources.
        requireFreeSpace(160L * 1024 * 1024, "Squeak");
        File zip = new File(getCacheDir(), "download.zip");
        try {
            downloadToFile(url, zip, pd, "Downloading " + baseName);
            setProgressMsg(pd, "Unzipping…");
            return unzipBundle(zip);
        } finally { zip.delete(); }
    }

    private String downloadCuis(ProgressDialog pd) throws Exception {
        setProgressMsg(pd, "Finding image…");
        // Pin to the newest base tag that WORKS (currently Cuis 7.7-7976 at
        // #BaseForCuis7.8) rather than master HEAD: every rolling 7.9 snapshot from
        // 7983 through 8090 never starts its UI on this VM (idle process only — the
        // 2026 startup-sequence rework; upstream fixed it in updates 8093/8094 AFTER
        // the 8090 snapshot). Retest master when a newer rolling image lands. The
        // tag name starts with '#', URL-encoded as %23.
        String ref = "%23BaseForCuis7.8";
        String json = httpGetString(
                "https://api.github.com/repos/Cuis-Smalltalk/Cuis-Smalltalk-Dev/contents/CuisImage?ref=" + ref);
        Matcher entry = Pattern.compile(
                "\"name\"\\s*:\\s*\"([^\"]+)\"[^}]*?\"download_url\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        java.util.HashMap<String, String> byName = new java.util.HashMap<>();
        while (entry.find()) byName.put(entry.group(1), entry.group(2));
        String imgName = null, imgUrl = null, srcName = null, srcUrl = null;
        int bestMj = -1, bestMn = -1, bestBd = -1;
        for (Map.Entry<String, String> e : byName.entrySet()) {
            String name = e.getKey();
            Matcher im = Pattern.compile("^Cuis(\\d+)\\.(\\d+)-(\\d+)\\.image$").matcher(name);
            if (im.matches()) {
                int mj = Integer.parseInt(im.group(1)), mn = Integer.parseInt(im.group(2)), bd = Integer.parseInt(im.group(3));
                if (mj > bestMj || (mj == bestMj && (mn > bestMn || (mn == bestMn && bd > bestBd)))) {
                    bestMj = mj; bestMn = mn; bestBd = bd; imgName = name; imgUrl = e.getValue();
                }
            }
            // Take the highest-versioned .sources deterministically. Picking
            // "whichever the HashMap happens to yield last" is a coin flip when the
            // folder carries more than one, and a mismatched sources file is worse
            // than none: Cuis shows the same "cannot locate" warning either way, but
            // a wrong one silently maps method source text to the wrong code.
            if (name.endsWith(".sources") && (srcName == null || name.compareTo(srcName) > 0)) {
                srcName = name; srcUrl = e.getValue();
            }
        }
        if (imgUrl == null) throw new Exception("no Cuis .image found");
        String chgName = imgName.replace(".image", ".changes");
        String chgUrl = byName.get(chgName);
        downloadToFile(imgUrl, new File(getFilesDir(), imgName), pd, "Downloading " + imgName);
        if (chgUrl != null) downloadToFile(chgUrl, new File(getFilesDir(), chgName), pd, "Downloading changes");
        if (srcUrl != null) downloadToFile(srcUrl, new File(getFilesDir(), srcName), pd, "Downloading sources");
        return imgName;
    }

    private String downloadCuisUniversity(ProgressDialog pd) throws Exception {
        setProgressMsg(pd, "Finding latest release…");
        // Cuis University (sites.google.com/view/cuis-university) publishes per-platform
        // bundles as GitHub releases on Cuis-University/Cuis-University. Every platform's
        // bundle contains the same image/changes/sources plus a platform VM we ignore;
        // we take the Windows zip (a plain zip — the macOS one carries __MACOSX
        // resource-fork entries) and unzipBundle() keeps just the three files we need.
        String json = httpGetString(
                "https://api.github.com/repos/Cuis-University/Cuis-University/releases/latest");
        Matcher m = Pattern.compile(
                "\"browser_download_url\"\\s*:\\s*\"([^\"]+windows64\\.zip)\"").matcher(json);
        if (!m.find()) throw new Exception("no windows64.zip in the latest Cuis University release");
        String url = m.group(1);
        // ~205 MB of zip staged in the cache, then ~60 MB of image/changes/sources
        // extracted next to it. Check up front instead of dying half-way through.
        requireFreeSpace(280L * 1024 * 1024, "Cuis University");
        File zip = new File(getCacheDir(), "download.zip");
        try {
            downloadToFile(url, zip, pd, "Downloading Cuis University (~200 MB)");
            // Unzipping this one takes ~50 s on a phone (a 45 MB image plus changes
            // and sources come out of it), and it is indeterminate, so say so rather
            // than leaving the user looking at a spinner that seems stuck.
            setProgressMsg(pd, "Unzipping — this takes a minute…");
            return unzipBundle(zip);
        } finally {
            // Always: a failure used to leave 205 MB parked in the cache directory.
            zip.delete();
        }
    }

    /** Fail before starting a large download rather than half-way through it. */
    private void requireFreeSpace(long needBytes, String what) throws IOException {
        long free = getFilesDir().getUsableSpace();
        if (free >= needBytes) return;
        throw new IOException(what + " needs about " + (needBytes / (1024 * 1024))
                + " MB free, but this device has " + (free / (1024 * 1024))
                + " MB. Free some space and try again.");
    }

    private void setProgressMsg(final ProgressDialog pd, final String msg) {
        runOnUiThread(() -> { pd.setIndeterminate(true); pd.setMessage(msg); });
    }

    /**
     * Check the response before reading a body, and turn the failures users
     * actually hit into sentences they can act on.
     *
     * getInputStream() already throws for >= 400, but with a message that is just
     * the URL — useless in a Toast. The one worth naming specially is GitHub's
     * rate limit: both Cuis entries call api.github.com unauthenticated, which is
     * 60 requests/hour *per IP*, and mobile carriers put thousands of phones
     * behind one address. On such a network the download simply fails, and
     * "Download failed: https://api.github.com/..." tells the user nothing.
     */
    private void checkHttpOk(HttpURLConnection c, String urlStr) throws IOException {
        int rc = c.getResponseCode();
        if (rc >= 200 && rc <= 299) return;
        if (rc == 403 || rc == 429) {
            String left = c.getHeaderField("X-RateLimit-Remaining");
            if ("0".equals(left) || rc == 429)
                throw new IOException("GitHub is rate-limiting this network (shared mobile IPs hit "
                        + "its 60-requests-per-hour cap). Try again in a few minutes, or on Wi-Fi.");
        }
        throw new IOException("server answered HTTP " + rc
                + (c.getResponseMessage() != null ? " " + c.getResponseMessage() : "")
                + " for " + urlStr);
    }

    /** GET a URL as a String (directory listing / JSON API). */
    private String httpGetString(String urlStr) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(20000); c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", "opensmalltalk-android");
        try {
            checkHttpOk(c, urlStr);
            try (InputStream in = c.getInputStream()) {
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192]; int n;
                while ((n = in.read(buf)) != -1) bo.write(buf, 0, n);
                return bo.toString("UTF-8");
            }
        } finally { c.disconnect(); }
    }

    /**
     * Stream a URL to a file, updating the ProgressDialog. Writes to "<dst>.part"
     * and renames on success, so an interrupted download can neither truncate an
     * existing library image nor leave a half-image that LOOKS loadable.
     */
    private void downloadToFile(String urlStr, File dst, final ProgressDialog pd, final String label) throws Exception {
        File part = new File(dst.getPath() + ".part");
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(20000); c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", "opensmalltalk-android");
        final int total;
        try {
            checkHttpOk(c, urlStr);
            total = c.getContentLength();
        } catch (Exception e) { c.disconnect(); throw e; }
        runOnUiThread(() -> { pd.setMessage(label); pd.setIndeterminate(total <= 0); pd.setProgress(0); });
        long got = 0;
        try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(part)) {
            byte[] buf = new byte[65536]; int n; long lastPct = -1;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n); got += n;
                if (total > 0) {
                    long pct = got * 100 / total;
                    if (pct != lastPct) { lastPct = pct; final int p = (int) pct; runOnUiThread(() -> pd.setProgress(p)); }
                }
            }
        } catch (Exception e) {
            part.delete();
            throw e;
        } finally { c.disconnect(); }
        // A connection dropped mid-transfer ends the read loop without an exception,
        // so without this a half-downloaded image is renamed into place and looks
        // perfectly loadable — until the VM aborts on it.
        if (total > 0 && got != total) {
            part.delete();
            throw new IOException("connection dropped after " + got + " of " + total
                    + " bytes — check the network and try again");
        }
        if (!part.renameTo(dst)) { part.delete(); throw new IOException("could not move " + part + " into place"); }
        Log.i(TAG, "downloaded " + urlStr + " -> " + dst.getName() + " (" + dst.length() + " bytes)");
    }

    /**
     * Unzip a Squeak/Cuis bundle keeping the files' real names (image + changes +
     * sources; anything else — platform VMs etc. — is skipped). Returns the image
     * file name, which the caller records as the current image.
     */
    private String unzipBundle(File zip) throws Exception {
        String imageName = null;
        try (ZipInputStream zis = new ZipInputStream(
                new java.io.BufferedInputStream(new java.io.FileInputStream(zip)))) {
            ZipEntry ze;
            while ((ze = zis.getNextEntry()) != null) {
                if (ze.isDirectory()) continue;
                String base = new File(ze.getName()).getName();
                // Skip macOS resource-fork entries (__MACOSX/._Foo.image) — they end in
                // .image too and would clobber the real one with AppleDouble junk.
                if (base.startsWith("._") || ze.getName().contains("__MACOSX")) continue;
                String low = base.toLowerCase();
                if (!(low.endsWith(".image") || low.endsWith(".changes") || low.endsWith(".sources")))
                    continue;
                File dst = new File(getFilesDir(), base);
                File part = new File(dst.getPath() + ".part");
                if (low.endsWith(".image")) imageName = base;
                try (FileOutputStream out = new FileOutputStream(part)) {
                    byte[] buf = new byte[65536]; int n;
                    while ((n = zis.read(buf)) != -1) out.write(buf, 0, n);
                } catch (Exception e) {
                    part.delete();
                    throw e;
                }
                if (!part.renameTo(dst)) { part.delete(); throw new IOException("could not move " + base + " into place"); }
                Log.i(TAG, "unzipped " + base + " -> " + dst.getName());
            }
        }
        if (imageName == null) throw new Exception("no .image found in the downloaded zip");
        return imageName;
    }

    /**
     * "File in code (.st)…": pick a .st/.cs from the device, copy it into the image
     * folder, and queue a startup script (pending-filein.st, see squeak_jni.c) that
     * files it in. The image evaluates the script on its NEXT start, so the user
     * chooses between restarting right away or picking the change up later. This is
     * the practical route on Android: the in-image FileList can't browse outside the
     * app sandbox, and Cuis can't enumerate "/", so it never reaches the file.
     */
    private void launchFileInPicker() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*"); // .st has no registered MIME type
        try {
            startActivityForResult(i, ACTIVITY_FILE_IN);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No file picker available on this device.", Toast.LENGTH_LONG).show();
        }
    }

    private void handleFileInPick(Uri uri) {
        String name = displayNameForUri(uri);
        if (name != null) name = name.trim();
        String low = name == null ? "" : name.toLowerCase();
        if (!(low.endsWith(".st") || low.endsWith(".cs"))) {
            Toast.makeText(this, "Pick a Smalltalk fileout (.st or .cs).", Toast.LENGTH_LONG).show();
            return;
        }
        final String fname = name;
        File dst = new File(getFilesDir(), fname);
        File part = new File(dst.getPath() + ".part");
        if (!copyUriToFile(uri, part)) {
            part.delete();
            Toast.makeText(this, "Could not read the selected file.", Toast.LENGTH_LONG).show();
            return;
        }
        _skipExportOnce.add(fname);   // don't bounce it straight back to Downloads
        if (!part.renameTo(dst)) {
            part.delete();
            Toast.makeText(this, "Could not store the selected file.", Toast.LENGTH_LONG).show();
            return;
        }
        // Preferred: synthesize a desktop-style drag-and-drop of the file onto the
        // RUNNING image (XDND) — no restart, and the image itself decides what to
        // do with the drop, exactly like on a desktop.
        boolean dropped = false;
        if (_vmRunning) {
            try { dropped = _xServer.getScreen().dropFile(dst.getAbsolutePath()); }
            catch (Exception e) { Log.e(TAG, "XDND drop failed", e); }
        }
        if (dropped) {
            Toast.makeText(this, "Dropped " + fname + " into the image.", Toast.LENGTH_LONG).show();
            return;
        }
        // Fallback (VM not running / image without XDND): queue a startup file-in.
        writePendingFileIn(fname);
        new AlertDialog.Builder(this)
                .setTitle("File in " + fname)
                .setMessage("The code files in when the image starts. Restart now?\n\n"
                        + "Anything not saved in the image (Save Image) will be lost.")
                .setPositiveButton("Restart & file in", (d, w) -> restartApp())
                .setNegativeButton("Later", (d, w) -> Toast.makeText(this,
                        fname + " queued — it files in on the next image start.",
                        Toast.LENGTH_LONG).show())
                .show();
    }

    /**
     * Copy assets/android-setup.st into filesDir fresh on every launch. squeak_jni
     * passes it to the image via -s (it chains pending-filein.st / dev-tests.st
     * itself, so it owns the single -s slot). The script adapts whichever image is
     * booting to the phone — Cuis: silent fileOut into the image folder (mirrored to
     * Downloads) + author-initials preseed; Squeak: file in a dropped .st/.cs in the
     * running image instead of the singleton-relaunch note. It is plain Smalltalk in
     * the asset (guarded per-image, errors caught), not a string built here.
     */
    private void writeAndroidSetupScript() {
        copyAssetToFiles("android-setup.st");   // Cuis (-s)
    }

    private void copyAssetToFiles(String name) {
        try (InputStream in = getAssets().open(name);
             FileOutputStream outS = new FileOutputStream(new File(getFilesDir(), name))) {
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) != -1) outS.write(buf, 0, n);
        } catch (IOException e) {
            Log.e(TAG, "could not write " + name, e);
        }
    }

    /**
     * Queue <filesDir>/pending-filein.st: android-setup.st evaluates it on the next
     * image start (Cuis 6+; other images ignore it), and the boot-healthy timer
     * deletes it so it runs once. The script defers the fileIn until the UI is up
     * (class definitions too early in startup are unsafe) and reports to
     * stdout → logcat either way.
     */
    private void writePendingFileIn(String fileName) {
        String quoted = fileName.replace("'", "''");
        String script =
                "| doIt |\n"
              + "doIt := [ [ ('" + quoted + "' asFileEntry) readStreamDo: [ :s | s fileIn ].\n"
              + "  StdIOWriteStream stdout nextPutAll: 'FILEIN OK " + quoted + "'; newLine; flush ]\n"
              + "  on: Error do: [ :e |\n"
              + "    StdIOWriteStream stdout nextPutAll: 'FILEIN ERROR ', (e messageText ifNil: [ '?' ]); newLine; flush ] ].\n"
              + "(Smalltalk includesKey: #UISupervisor)\n"
              + "  ifTrue: [ UISupervisor whenUIinSafeState: doIt ]\n"
              + "  ifFalse: [ doIt value ].\n";
        try (FileOutputStream out = new FileOutputStream(
                new File(getFilesDir(), "pending-filein.st"))) {
            out.write(script.getBytes("UTF-8"));
        } catch (IOException e) {
            Log.e(TAG, "could not write pending-filein.st", e);
            Toast.makeText(this, "Could not queue the file-in.", Toast.LENGTH_LONG).show();
        }
    }

    /** Let the user pick a Smalltalk .image from device storage (SAF — no permission needed). */
    private void launchImagePicker() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*"); // .image has no registered MIME type
        try {
            startActivityForResult(i, ACTIVITY_LOAD_IMAGE);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No file picker available.", Toast.LENGTH_LONG).show();
        }
    }

    private void launchChangesPicker() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try {
            startActivityForResult(i, ACTIVITY_LOAD_CHANGES);
        } catch (ActivityNotFoundException e) {
            restartApp();
        }
    }

    /** Copy a content Uri's bytes into a file in filesDir. */
    private boolean copyUriToFile(Uri uri, File dst) {
        try (InputStream in = getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(dst)) {
            if (in == null) return false;
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            Log.i(TAG, "Copied " + uri + " -> " + dst.getAbsolutePath() + " (" + dst.length() + " bytes)");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "copyUriToFile failed", e);
            return false;
        }
    }

    /**
     * Restart the process so the native VM re-initialises with the new image.
     *
     * The VM can't re-init in a live process, so we must kill this process and
     * boot a fresh one. We hand that off to {@link RestartActivity}, a trampoline
     * in a separate process: while we're STILL in the foreground (so the start is
     * allowed on Android 10+, unlike a backgrounded AlarmManager relaunch, which
     * Android blocks) we launch it, and it kills us and relaunches us cleanly.
     */
    private void restartApp() {
        // A deliberate restart is not a crashed boot: clear .boot_pending so the
        // next launch isn't mistaken for a crash-loop (it stays set if the user
        // switches images within ~7 s of a boot, before the healthy-boot timer).
        new File(getFilesDir(), ".boot_pending").delete();
        // Close the X server socket (127.0.0.1:6000) cleanly first — otherwise the
        // dying process still holds the port and the freshly restarted VM's X
        // server can't serve, and the new process dies a few seconds in.
        try { if (_xServer != null) _xServer.stop(); } catch (Exception e) { Log.e(TAG, "xserver stop", e); }

        Intent next = new Intent(this, XServerActivity.class);
        next.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);

        Intent trampoline = new Intent(this, RestartActivity.class);
        trampoline.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK
                | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        trampoline.putExtra(RestartActivity.EXTRA_PID, android.os.Process.myPid());
        trampoline.putExtra(RestartActivity.EXTRA_NEXT, next);

        try {
            startActivity(trampoline);
        } catch (Exception e) {
            // Last-resort fallback: relaunch directly and exit. Racier, but better
            // than staying dead if the trampoline can't be started for some reason.
            Log.e(TAG, "trampoline start failed, falling back", e);
            startActivity(next);
            new android.os.Handler(android.os.Looper.getMainLooper())
                    .postDelayed(() -> Runtime.getRuntime().exit(0), 400);
        }
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

                // The image/changes are never auto-extracted anymore: startup shows
                // the "Load image" chooser, and "Bundled Cuis" copies them on demand.
                // (Auto-extracting here would also race that copy.)
                if (filename.equals("Cuis.image") || filename.equals("Cuis.changes"))
                    continue;
                // Written fresh each boot by writeAndroidSetupScript(); don't
                // leave a stale first-boot copy here.
                if (filename.equals("android-setup.st"))
                    continue;

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

    // ------------------------------------------------------------------
    // Fileout export: Smalltalk fileouts (.st / .pck.st / .cs) are written into the
    // app-private filesDir, where the user can't see them. Watch for them and copy
    // each one into the shared Downloads/OpenSmalltalk/ folder as it appears.
    // ------------------------------------------------------------------

    /** True once startVMNative() succeeded — the booted image file is then in use. */
    private volatile boolean _vmRunning = false;

    // CopyOnWrite: appended from the UI thread at startup AND from FileObserver's
    // dispatch thread when the image creates a new subdirectory at runtime.
    private final java.util.List<FileObserver> _fileoutObservers =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    private final Set<String> _watchedDirs =
            java.util.Collections.synchronizedSet(new HashSet<>());
    // Names the fileout watcher should skip once: files WE just imported via
    // "File in…" (re-exporting them to Downloads immediately would be noise).
    private final Set<String> _skipExportOnce =
            java.util.Collections.synchronizedSet(new HashSet<>());

    private void startFileoutWatcher() {
        if (!_fileoutObservers.isEmpty()) return;   // already watching
        watchDirForFileouts(getFilesDir(), false);
        // Fileouts may land in a subdirectory of the user dir (image-dependent), and
        // FileObserver is not recursive — watch existing subdirs too (skip plugins).
        File[] subs = getFilesDir().listFiles(File::isDirectory);
        if (subs != null)
            for (File d : subs)
                if (!d.getName().equals("plugins")) watchDirForFileouts(d, false);
    }

    private void stopFileoutWatcher() {
        for (FileObserver o : _fileoutObservers) o.stopWatching();
        _fileoutObservers.clear();
        _watchedDirs.clear();
    }

    /**
     * Watch one directory. exportExisting is set for directories discovered while
     * the app runs (the image just created them): a fileout written in the gap
     * between mkdir and our inotify watch would otherwise be missed, so sweep the
     * directory once after the watch starts. It is NOT set for the startup set —
     * re-exporting every old fileout on every boot would just spam toasts.
     */
    private void watchDirForFileouts(final File dir, boolean exportExisting) {
        if (!_watchedDirs.add(dir.getAbsolutePath())) return;   // already watched
        FileObserver o = new FileObserver(dir.getAbsolutePath(),
                FileObserver.CLOSE_WRITE | FileObserver.MOVED_TO | FileObserver.CREATE) {
            @Override public void onEvent(int event, String path) {
                if (path == null) return;
                File f = new File(dir, path);
                // A new subdirectory (e.g. the image creating a FileOuts/ folder):
                // start watching it as well, so fileouts inside it are caught.
                if ((event & FileObserver.CREATE) != 0 && f.isDirectory()
                        && !path.equals("plugins") && dir.equals(getFilesDir())) {
                    watchDirForFileouts(f, true);
                    return;
                }
                if ((event & (FileObserver.CLOSE_WRITE | FileObserver.MOVED_TO)) == 0) return;
                maybeExportFileout(f, path);
            }
        };
        o.startWatching();
        _fileoutObservers.add(o);
        Log.i(TAG, "watching for fileouts in " + dir);
        if (exportExisting) {
            File[] present = dir.listFiles(File::isFile);
            if (present != null)
                for (File f : present) maybeExportFileout(f, f.getName());
        }
    }

    private void maybeExportFileout(File f, String name) {
        String low = name.toLowerCase();
        boolean isFileout = low.endsWith(".st") || low.endsWith(".cs");
        if (!isFileout || name.equals("dev-tests.st") || name.equals("pending-filein.st")
                || name.equals("android-setup.st")) return;
        if (_skipExportOnce.remove(name)) return;   // a file WE just imported
        if (!f.isFile() || f.length() == 0) return;
        exportToDownloads(f);
    }

    /**
     * Copy a fileout into the shared Downloads/OpenSmalltalk/ folder. On Android 10+
     * this uses MediaStore (no permission needed); re-exporting the same name
     * overwrites the previous copy instead of piling up "name (1).st" duplicates.
     * On older devices it writes to the public Downloads directory directly and
     * quietly gives up if storage permission is missing.
     */
    private void exportToDownloads(File src) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                Uri collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
                String relPath = Environment.DIRECTORY_DOWNLOADS + "/OpenSmalltalk/";
                Uri existing = null;
                try (Cursor c = getContentResolver().query(collection,
                        new String[]{ MediaStore.MediaColumns._ID },
                        MediaStore.MediaColumns.DISPLAY_NAME + "=? AND "
                                + MediaStore.MediaColumns.RELATIVE_PATH + "=?",
                        new String[]{ src.getName(), relPath }, null)) {
                    if (c != null && c.moveToFirst())
                        existing = Uri.withAppendedPath(collection, c.getString(0));
                }
                Uri dst = existing;
                if (dst == null) {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.MediaColumns.DISPLAY_NAME, src.getName());
                    cv.put(MediaStore.MediaColumns.RELATIVE_PATH, relPath);
                    // No MIME_TYPE: declaring text/plain makes MediaStore append
                    // ".txt" to the unknown ".st" extension, renaming the fileout.
                    dst = getContentResolver().insert(collection, cv);
                }
                if (dst == null) throw new IOException("MediaStore insert failed");
                try (InputStream in = new java.io.FileInputStream(src);
                     OutputStream out = getContentResolver().openOutputStream(dst, "wt")) {
                    byte[] buf = new byte[65536]; int n;
                    while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                }
            } else {
                File dir = new File(Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS), "OpenSmalltalk");
                dir.mkdirs();
                File dst = new File(dir, src.getName());
                try (InputStream in = new java.io.FileInputStream(src);
                     OutputStream out = new FileOutputStream(dst)) {
                    byte[] buf = new byte[65536]; int n;
                    while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                }
            }
            Log.i(TAG, "fileout exported: " + src.getName());
            runOnUiThread(() -> Toast.makeText(this,
                    "Saved to Downloads/OpenSmalltalk/" + src.getName(),
                    Toast.LENGTH_SHORT).show());
        } catch (Exception e) {
            // e.g. pre-Android-10 device without storage permission — don't nag on
            // every fileout, the copy in filesDir is still intact.
            Log.e(TAG, "could not export fileout " + src.getName(), e);
        }
    }

    /**
     * Truncate the current image's .changes to just after the last ----SNAPSHOT----/----QUIT----/
     * ----QUIT/NOSAVE---- record, dropping any trailing content. On Android the app
     * is killed (not cleanly quit), so Cuis leaves a dangling ----STARTUP---- at the
     * end of the changes file; on the next boot that reads as "lost changes" and pops
     * a modal dialog. Removing the tail makes Smalltalk>>hasToRestoreChanges false
     * (equivalent to choosing "Nothing"), while keeping all history up to the last
     * snapshot/quit. No-op when there's no changes file. Runs before every VM launch.
     */
    private void pruneChangesFile() {
        File changes = new File(getFilesDir(),
                imageBase(currentImageName()) + ".changes");
        if (!changes.exists() || changes.length() < 64) return;
        RandomAccessFile raf = null;
        try {
            long len = changes.length();
            int scan = (int) Math.min(len, 2L * 1024 * 1024); // markers live at the tail
            byte[] buf = new byte[scan];
            raf = new RandomAccessFile(changes, "rw");
            raf.seek(len - scan);
            raf.readFully(buf);
            String tail = new String(buf, "ISO-8859-1"); // 1 byte == 1 char, offsets match
            int marker = Math.max(tail.lastIndexOf("----SNAPSHOT"), tail.lastIndexOf("----QUIT"));
            if (marker < 0) return; // no marker in the tail — leave the file untouched
            int bang = tail.indexOf('!', marker); // chunk terminator of that record
            if (bang < 0) return;
            long cut = (len - scan) + bang + 1;
            if (cut < len) {
                raf.setLength(cut);
                Log.i(TAG, "pruneChangesFile: " + len + " -> " + cut + " (dropped trailing lost changes)");
            }
        } catch (Exception e) {
            Log.e(TAG, "pruneChangesFile failed", e);
        } finally {
            if (raf != null) try { raf.close(); } catch (IOException e) { }
        }
    }
}