package au.com.darkside.x11server;

import android.view.Surface;

/**
 * Bridge to <code>vm-display-android.so</code> — the display driver that renders the
 * Smalltalk world straight into a {@link Surface}, with no X server in between.
 *
 * <p>The driver is an ordinary OpenSmalltalk Unix display module (the same kind as
 * <code>vm-display-X11</code>), selected by putting <code>-vm-display-android</code> on the VM's
 * command line, so it needs no change to the VM. This class only hands it the surface and
 * forwards input; everything here is a thin JNI call implemented in <code>squeak_jni.c</code>.
 *
 * <p>All methods are safe to call before the driver is loaded — they are no-ops until the
 * VM has it, and the surface is remembered and handed over as soon as it does.
 */
public final class NativeDisplay {

    static {
        // This class is reachable from an activity that does not go through
        // XServerActivity's static block, so load the glue here too. A second
        // loadLibrary of an already-loaded library is a no-op.
        try {
            System.loadLibrary("squeak_jni");
        } catch (UnsatisfiedLinkError e) {
            android.util.Log.e("Cuis", "NativeDisplay: cannot load libsqueak_jni", e);
        }
    }

    private NativeDisplay() {}

    /** Must be called before startVMNative() to boot against this driver instead of X11. */
    public static native void enable(boolean on);

    /** True once the driver is loaded and its entry points are resolved. */
    public static native boolean isReady();

    /** Give the driver the SurfaceView's surface, or null when it is destroyed. */
    public static native void setSurface(Surface surface, int width, int height);

    /**
     * The size the image believes the screen is. Smaller than the surface means
     * SurfaceFlinger scales the buffer up in hardware — a zoom that costs the VM
     * fewer pixels to draw rather than more.
     */
    public static native void setLogicalSize(int width, int height);

    /** Squeak button bits: red (left) = 4, yellow (middle) = 2, blue (right) = 1. */
    public static native void postMouse(int x, int y, int buttons, int modifiers);

    /** pressCode: 0 = char, 1 = down, 2 = up. */
    public static native void postKey(int keyCode, int pressCode, int modifiers, int ucs4);

    public static native void postWheel(int dx, int dy);

    /** Deliver a file to the running image as a drag-and-drop, as the X11 path does. */
    public static native void postDropFile(String path);

    /** Push the system clipboard in, so the image's paste sees it. */
    public static native void setClipboard(String text);
}
