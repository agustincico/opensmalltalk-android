package au.com.darkside.x11server;

import android.view.View;

import au.com.darkside.xserver.ScreenView;

/**
 * {@link SmalltalkDisplay} over the embedded X server's {@link ScreenView}.
 *
 * <p>An adapter rather than an {@code implements} clause on {@code ScreenView} itself: that
 * class is 2500 lines of the display that actually ships, and it lives in the {@code library}
 * module, which does not depend on this package. Wrapping it keeps the library's diff empty.
 *
 * <p>It is also the right home for the two conversions that used to leak into the activity:
 * the {@code try/catch} around {@link ScreenView#getDisplayScale()} (the placeholder
 * constructor leaves the server null) and the logical→physical caret arithmetic.
 */
final class X11Display implements SmalltalkDisplay {

    private final ScreenView _sv;

    X11Display(ScreenView sv) { _sv = sv; }

    @Override public View asView() { return _sv; }

    /** The X path grew all of these, so it has them all. */
    @Override public boolean supports(Feature f) { return true; }

    @Override
    public boolean isEnabled(Feature f) {
        switch (f) {
        case SMOOTH_ZOOM:      return _sv.isSmoothZoom();
        case TRACKPAD:         return _sv.isTrackpadMode();
        case PRECISE_POINTER:  return _sv.isPreciseTouch();
        case POINTER_ARROW:    return _sv.isShowPointer();
        case SHARED_CLIPBOARD: return _sv.isSharedClipboard();
        case LONG_PRESS_MENU:  return _sv.isLongPressMenuEnabled();
        default:               return false;
        }
    }

    @Override
    public boolean toggle(Feature f) {
        switch (f) {
        case SMOOTH_ZOOM:      return _sv.toggleSmoothZoom();
        case TRACKPAD:         return _sv.toggleTrackpadMode();
        case PRECISE_POINTER:  return _sv.togglePreciseTouch();
        case POINTER_ARROW:    return _sv.toggleShowPointer();
        case SHARED_CLIPBOARD: return _sv.toggleSharedClipboard();
        case LONG_PRESS_MENU:  return _sv.toggleLongPressMenu();
        default:               return false;
        }
    }

    @Override
    public float getDisplayScale() {
        try { return _sv.getDisplayScale(); } catch (Exception e) { return 1.0f; }
    }

    @Override public void setDisplayScale(float scale) { _sv.setDisplayScale(scale); }

    @Override
    public void armNextTap(int button) {
        if (button == BUTTON_MENU) _sv.armRightClick();
        else if (button == BUTTON_HALOS) _sv.armMiddleClick();
    }

    @Override
    public boolean dropFile(String absolutePath) {
        try { return _sv.dropFile(absolutePath); } catch (Exception e) { return false; }
    }

    @Override
    public int caretY() {
        try { return Math.round(_sv.getPointerY() * _sv.getDisplayScale()); }
        catch (Exception e) { return -1; }
    }

    @Override
    public void applyImePan(float translationY) {
        if (_sv.getTranslationY() != translationY) _sv.setTranslationY(translationY);
    }
}
