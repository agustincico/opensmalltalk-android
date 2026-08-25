package au.com.darkside.x11server;

import android.app.Activity;
import android.content.res.AssetManager;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Boots the chosen image against the native display driver — no X server, no X11 libraries.
 *
 * <p>This is deliberately a second, minimal entry point rather than a switch inside
 * {@link XServerActivity}: it keeps the shipping X11 path untouched while the native one is
 * measured against it, and it can be launched straight from a terminal:
 *
 * <pre>adb shell am start -n ar.com.opensmalltalk/au.com.darkside.x11server.NativeDisplayActivity</pre>
 *
 * <p>It expects the image to have been chosen already (the normal launcher activity writes
 * the {@code .custom_image} marker), and reuses the plugins that activity extracted.
 */
public class NativeDisplayActivity extends Activity {

    private static final String TAG = "Cuis";
    private static final String DISPLAY_MODULE = "vm-display-android.so";

    private native int startVMNative(String libPath, String imagePath, String pluginsPath);

    private SqueakSurfaceView _view;
    private boolean _started;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        _view = new SqueakSurfaceView(this);
        _view.setSurfaceReadyListener((w, h) -> startVM());
        setContentView(_view);
        _view.requestFocus();
        goFullScreen();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) goFullScreen();
    }

    private void goFullScreen() {
        _view.setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
          | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
          | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
          | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
          | View.SYSTEM_UI_FLAG_FULLSCREEN
          | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    /** Started from the surface callback: the driver must have a window before the VM boots. */
    private synchronized void startVM() {
        if (_started) return;
        _started = true;

        final File filesDir = getFilesDir();
        final File plugins = new File(filesDir, "plugins");
        final File image = chosenImage(filesDir);

        if (image == null || !image.exists()) {
            Log.e(TAG, "native display: no image chosen yet — run the launcher first");
            finish();
            return;
        }
        if (!ensureDisplayModule(plugins)) {
            Log.e(TAG, "native display: " + DISPLAY_MODULE + " missing and could not be extracted");
            finish();
            return;
        }

        final String libPath = getApplicationInfo().nativeLibraryDir + "/libsqueak.so";
        Log.i(TAG, "native display: booting " + image.getName());

        NativeDisplay.enable(true);
        new Thread(() -> {
            int res = startVMNative(libPath, image.getAbsolutePath(), plugins.getAbsolutePath());
            Log.i(TAG, "native display: startVMNative returned " + res);
        }, "squeak-boot").start();
    }

    /** The image the launcher last chose; the marker holds its file name (empty = legacy). */
    private File chosenImage(File filesDir) {
        File marker = new File(filesDir, ".custom_image");
        String name = "Cuis.image";
        if (marker.exists()) {
            try {
                byte[] buf = new byte[256];
                try (InputStream in = new java.io.FileInputStream(marker)) {
                    int n = in.read(buf);
                    if (n > 0) {
                        String s = new String(buf, 0, n).trim();
                        if (!s.isEmpty()) name = s;
                    }
                }
            } catch (IOException e) {
                Log.w(TAG, "native display: cannot read .custom_image", e);
            }
        }
        File f = new File(filesDir, name);
        return f.exists() ? f : null;
    }

    /** The driver is a new asset, so an install that predates it will not have it yet. */
    private boolean ensureDisplayModule(File plugins) {
        File dest = new File(plugins, DISPLAY_MODULE);
        if (dest.exists() && dest.length() > 0) return true;
        if (!plugins.isDirectory() && !plugins.mkdirs()) return false;

        AssetManager assets = getAssets();
        try (InputStream in = assets.open("plugins/" + DISPLAY_MODULE);
             OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            Log.i(TAG, "native display: extracted " + DISPLAY_MODULE);
            return true;
        } catch (IOException e) {
            Log.e(TAG, "native display: extracting " + DISPLAY_MODULE + " failed", e);
            return false;
        }
    }
}
