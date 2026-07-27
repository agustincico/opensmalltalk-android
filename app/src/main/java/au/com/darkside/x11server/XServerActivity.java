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
import android.net.Uri;
import android.provider.DocumentsContract;
import android.os.Build;
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
            File filesDir = getFilesDir();
            File marker = new File(filesDir, ".custom_image");
            // Nothing chosen yet? Don't auto-boot the bundled image — show the
            // "Load image" chooser (download Squeak/Cuis, or browse the device).
            if (!marker.exists()) {
                showLoadImageDialog();
                return;
            }

            File image = new File(filesDir, "Cuis.image");
            File bootPending = new File(filesDir, ".boot_pending");

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

                // Mark the boot in-progress; a healthy run clears it below. If the VM
                // aborts on a bad image, this file survives → next launch recovers (a).
                try { bootPending.createNewFile(); } catch (IOException ignore) {}

                int res = startVMNative(libPath, imagePath, pluginsPath);
                Log.i(TAG, "startVMNative() retornó: " + res);

                // Still alive a few seconds later ⇒ the image booted fine.
                _screenView.postDelayed(() -> {
                    if (bootPending.delete()) Log.i(TAG, "boot healthy; cleared .boot_pending");
                }, 7000);

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
        if (requestCode == ACTIVITY_LOAD_IMAGE) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                Uri imgUri = data.getData();
                File image = new File(getFilesDir(), "Cuis.image");
                if (copyUriToFile(imgUri, image)) {
                    // Reject a 32-bit image up front (the 64-bit VM would abort on it)
                    // so we never set the marker / restart into a boot that bricks.
                    if (is32BitSpurImage(image)) {
                        image.delete();
                        showLoadImageDialog("That image is 32-bit — the VM needs a 64-bit "
                                + "Spur image. Pick another.");
                        return;
                    }
                    // Assume the .changes lives next to the .image (same folder) and
                    // grab it automatically — no second picker.
                    copySiblingChanges(imgUri);
                    // Mark so extractAssets() never overwrites this custom image / changes.
                    try { new File(getFilesDir(), ".custom_image").createNewFile(); }
                    catch (IOException e) { Log.e(TAG, "could not write .custom_image marker", e); }
                    Toast.makeText(this, "Image loaded — restarting.", Toast.LENGTH_SHORT).show();
                    restartApp();
                } else {
                    Toast.makeText(this, "Could not read the selected image.", Toast.LENGTH_LONG).show();
                }
            }
            return;
        }
    }

    /**
     * Copy the .changes file that sits beside the picked .image (same directory) into
     * filesDir as Cuis.changes. Derives the sibling document URI by swapping the
     * extension in the document id (works for on-device providers: Downloads, storage).
     * If there's no sibling, boot without a changes file (stable) rather than keeping
     * a mismatched one.
     */
    private void copySiblingChanges(Uri imgUri) {
        File changes = new File(getFilesDir(), "Cuis.changes");
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
        // Collapsed by default so the menu doesn't sit over the image — only the
        // small handle shows; tapping it slides the buttons out.
        menuBtn.setVisibility(View.GONE);
        kbdBtn.setVisibility(View.GONE);

        final Button handle = makeIconButton("‹");  // the minimal always-visible tab
        handle.setOnClickListener(v -> {
            _controlsExpanded = !_controlsExpanded;
            menuBtn.setVisibility(_controlsExpanded ? View.VISIBLE : View.GONE);
            kbdBtn.setVisibility(_controlsExpanded ? View.VISIBLE : View.GONE);
            handle.setText(_controlsExpanded ? "›" : "‹");
        });

        bar.addView(menuBtn);
        bar.addView(kbdBtn);
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

    /** Copy the APK's bundled Cuis image/changes into filesDir and boot it (works offline). */
    private void loadBundledImage() {
        final ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage("Loading bundled Cuis…");
        pd.setCancelable(false);
        pd.show();
        new Thread(() -> {
            try {
                copyAsset("Cuis.image", new File(getFilesDir(), "Cuis.image"));
                try { copyAsset("Cuis.changes", new File(getFilesDir(), "Cuis.changes")); }
                catch (Exception ignore) { /* image may ship without a .changes */ }
                new File(getFilesDir(), ".custom_image").createNewFile();
                runOnUiThread(() -> { pd.dismiss(); restartApp(); });
            } catch (Exception e) {
                Log.e(TAG, "loadBundledImage failed", e);
                runOnUiThread(() -> { pd.dismiss();
                    Toast.makeText(this, "No bundled image: " + e.getMessage(), Toast.LENGTH_LONG).show(); });
            }
        }).start();
    }

    private void copyAsset(String assetName, File dst) throws IOException {
        try (InputStream in = getAssets().open(assetName); FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[65536]; int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
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
                "Zoom (" + zoom + "×)",
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
                        case 1: sv.cycleDisplayScale(); showOptionsDialog(); break;  // reopen to keep cycling
                        case 2: sv.toggleShowPointer(); break;
                        case 3: sv.toggleSharedClipboard(); break;
                        case 4: sv.toggleLongPressMenu(); break;
                        case 5: toggleOrientation(); break;
                    }
                })
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

    /** "Load image…" → choose: download the latest Squeak or Cuis, or browse the device. */
    private void showLoadImageDialog() { showLoadImageDialog(null); }

    /** As above, optionally preceded by a short explanatory toast (e.g. after a bad image). */
    private void showLoadImageDialog(String message) {
        if (message != null)
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        final String[] options = { "Latest Squeak (download)", "Cuis 7.5 (download)",
                "From device…", "Bundled Cuis (offline)" };
        new AlertDialog.Builder(this)
                .setTitle("Load image")
                .setItems(options, (dialog, which) -> {
                    if (which == 0) downloadAndLoad("Squeak");
                    else if (which == 1) downloadAndLoad("Cuis");
                    else if (which == 2) launchImagePicker();
                    else loadBundledImage();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * True if the file's image-format magic is a known 32-bit (or V3) Squeak format,
     * which the bundled 64-bit Spur VM can't boot. Reads the first 4 bytes as a
     * little-endian format number. 64-bit Spur images (68021 / 68531 / 68533) → false.
     */
    private boolean is32BitSpurImage(File image) {
        if (image == null || !image.isFile()) return false;
        try (InputStream in = new java.io.FileInputStream(image)) {
            byte[] b = new byte[4];
            int n = 0; while (n < 4) { int r = in.read(b, n, 4 - n); if (r < 0) break; n += r; }
            if (n < 4) return false;
            int fmt = (b[0] & 0xFF) | ((b[1] & 0xFF) << 8) | ((b[2] & 0xFF) << 16) | ((b[3] & 0xFF) << 24);
            return fmt == 6521 || fmt == 6505 || fmt == 6504;  // 32-bit Spur / V3
        } catch (Exception e) {
            return false;  // unreadable → let the boot path deal with it (guard a still covers it)
        }
    }

    /** Download the latest Squeak/Cuis image into filesDir on a background thread, then restart. */
    private void downloadAndLoad(final String flavor) {
        final ProgressDialog pd = new ProgressDialog(this);
        pd.setTitle("Downloading latest " + flavor);
        pd.setMessage("Contacting server…");
        pd.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        pd.setIndeterminate(true);
        pd.setCancelable(false);
        pd.setMax(100);
        pd.show();
        new Thread(() -> {
            try {
                if (flavor.equals("Squeak")) downloadSqueak(pd); else downloadCuis(pd);
                // keep the download from being clobbered by the bundled default next boot
                new File(getFilesDir(), ".custom_image").createNewFile();
                runOnUiThread(() -> {
                    pd.dismiss();
                    Toast.makeText(this, flavor + " loaded — restarting.", Toast.LENGTH_SHORT).show();
                    restartApp();
                });
            } catch (Exception e) {
                Log.e(TAG, "download failed", e);
                final String msg = e.getMessage();
                runOnUiThread(() -> {
                    pd.dismiss();
                    Toast.makeText(this, "Download failed: " + msg, Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void downloadSqueak(ProgressDialog pd) throws Exception {
        setProgressMsg(pd, "Finding latest build…");
        String listing = httpGetString("https://files.squeak.org/6.0/");
        Matcher m = Pattern.compile("Squeak6\\.0-(\\d+)-64bit").matcher(listing);
        int best = -1;
        while (m.find()) { int b = Integer.parseInt(m.group(1)); if (b > best) best = b; }
        if (best < 0) throw new Exception("no Squeak 6.0 build listed");
        String baseName = "Squeak6.0-" + best + "-64bit";
        String url = "https://files.squeak.org/6.0/" + baseName + "/" + baseName + ".zip";
        File zip = new File(getCacheDir(), "download.zip");
        downloadToFile(url, zip, pd, "Downloading " + baseName);
        setProgressMsg(pd, "Unzipping…");
        unzipBundle(zip);
        zip.delete();
    }

    private void downloadCuis(ProgressDialog pd) throws Exception {
        setProgressMsg(pd, "Finding image…");
        // Pin to the stable base tag (currently Cuis 7.5-7775) rather than master
        // HEAD: the bleeding-edge dev image (Cuis 7.9-8090) boots but renders a blank
        // white world on our embedded X server. 7.5 renders + runs fine. The tag name
        // starts with '#', URL-encoded as %23. The download_url values the API returns
        // are already correctly encoded for this ref.
        String ref = "%23BaseForCuis7.6";
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
            if (name.endsWith(".sources")) { srcName = name; srcUrl = e.getValue(); }
        }
        if (imgUrl == null) throw new Exception("no Cuis .image found");
        String chgUrl = byName.get(imgName.replace(".image", ".changes"));
        downloadToFile(imgUrl, new File(getFilesDir(), "Cuis.image"), pd, "Downloading " + imgName);
        if (chgUrl != null) downloadToFile(chgUrl, new File(getFilesDir(), "Cuis.changes"), pd, "Downloading changes");
        if (srcUrl != null) downloadToFile(srcUrl, new File(getFilesDir(), srcName), pd, "Downloading sources");
    }

    private void setProgressMsg(final ProgressDialog pd, final String msg) {
        runOnUiThread(() -> { pd.setIndeterminate(true); pd.setMessage(msg); });
    }

    /** GET a URL as a String (directory listing / JSON API). */
    private String httpGetString(String urlStr) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(20000); c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", "opensmalltalk-android");
        try (InputStream in = c.getInputStream()) {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) != -1) bo.write(buf, 0, n);
            return bo.toString("UTF-8");
        } finally { c.disconnect(); }
    }

    /** Stream a URL to a file, updating the ProgressDialog. */
    private void downloadToFile(String urlStr, File dst, final ProgressDialog pd, final String label) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(20000); c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", "opensmalltalk-android");
        final int total = c.getContentLength();
        runOnUiThread(() -> { pd.setMessage(label); pd.setIndeterminate(total <= 0); pd.setProgress(0); });
        try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[65536]; int n; long got = 0, lastPct = -1;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n); got += n;
                if (total > 0) {
                    long pct = got * 100 / total;
                    if (pct != lastPct) { lastPct = pct; final int p = (int) pct; runOnUiThread(() -> pd.setProgress(p)); }
                }
            }
        } finally { c.disconnect(); }
        Log.i(TAG, "downloaded " + urlStr + " -> " + dst.getName() + " (" + dst.length() + " bytes)");
    }

    /** Unzip a Squeak bundle: *.image -> Cuis.image, *.changes -> Cuis.changes, *.sources kept by name. */
    private void unzipBundle(File zip) throws Exception {
        boolean gotImage = false;
        try (ZipInputStream zis = new ZipInputStream(
                new java.io.BufferedInputStream(new java.io.FileInputStream(zip)))) {
            ZipEntry ze;
            while ((ze = zis.getNextEntry()) != null) {
                if (ze.isDirectory()) continue;
                String base = new File(ze.getName()).getName();
                String low = base.toLowerCase();
                File dst;
                if (low.endsWith(".image")) { dst = new File(getFilesDir(), "Cuis.image"); gotImage = true; }
                else if (low.endsWith(".changes")) dst = new File(getFilesDir(), "Cuis.changes");
                else if (low.endsWith(".sources")) dst = new File(getFilesDir(), base);
                else continue;
                try (FileOutputStream out = new FileOutputStream(dst)) {
                    byte[] buf = new byte[65536]; int n;
                    while ((n = zis.read(buf)) != -1) out.write(buf, 0, n);
                }
                Log.i(TAG, "unzipped " + base + " -> " + dst.getName());
            }
        }
        if (!gotImage) throw new Exception("no .image found in the downloaded zip");
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

    /**
     * Truncate Cuis.changes to just after the last ----SNAPSHOT----/----QUIT----/
     * ----QUIT/NOSAVE---- record, dropping any trailing content. On Android the app
     * is killed (not cleanly quit), so Cuis leaves a dangling ----STARTUP---- at the
     * end of the changes file; on the next boot that reads as "lost changes" and pops
     * a modal dialog. Removing the tail makes Smalltalk>>hasToRestoreChanges false
     * (equivalent to choosing "Nothing"), while keeping all history up to the last
     * snapshot/quit. No-op when there's no changes file. Runs before every VM launch.
     */
    private void pruneChangesFile() {
        File changes = new File(getFilesDir(), "Cuis.changes");
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