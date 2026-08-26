package au.com.darkside.x11server;

import android.view.View;

/**
 * What the floating pill and the options dialog need from a display backend.
 *
 * <p>There are two backends: the embedded X server (the shipping one, adapted by
 * {@link X11Display}) and {@link SqueakSurfaceView}, which lets the VM draw straight into a
 * surface with no X server at all. The activity above them should not care which it has.
 *
 * <p>Not every backend can do everything — the X server has no hardware scaler, the surface
 * has no {@code Canvas} to filter — so capabilities are declared rather than assumed:
 * the options dialog is built from whatever {@link #supports} accepts, which keeps
 * "not available here" a fact about the backend instead of an {@code if} in the menu.
 */
public interface SmalltalkDisplay {

    /** Optional capabilities. The options dialog shows an item only if it is supported. */
    enum Feature {
        ZOOM, SMOOTH_ZOOM, TRACKPAD, PRECISE_POINTER, POINTER_ARROW,
        SHARED_CLIPBOARD, LONG_PRESS_MENU, FILE_IN
    }

    /**
     * What the pill's two arming buttons mean. Named for the effect, not the button number,
     * because the two backends number them differently: the X server speaks X buttons
     * (2 = halos, 3 = context menu) and the surface speaks Squeak's own bits.
     */
    int BUTTON_NORMAL = 1, BUTTON_HALOS = 2, BUTTON_MENU = 3;

    /** The view to put in the layout; also the focus, IME and postDelayed target. */
    View asView();

    boolean supports(Feature f);

    boolean isEnabled(Feature f);

    /** Flip it and answer the new state. A no-op answering false when unsupported. */
    boolean toggle(Feature f);

    float getDisplayScale();

    void setDisplayScale(float scale);

    /** Arm the NEXT tap as the given button. One-shot: the tap after it is ordinary. */
    void armNextTap(int button);

    /** Hand a file to the running image as a drop; false means "I could not". */
    boolean dropFile(String absolutePath);

    /** Caret row in PHYSICAL pixels, or -1 when this backend cannot say where it is. */
    int caretY();

    /** Lift the world by this many physical pixels so the keyboard cannot cover the caret. */
    void applyImePan(float translationY);
}
