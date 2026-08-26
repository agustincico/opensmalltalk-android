package au.com.darkside.x11server;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.text.InputType;
import android.util.AttributeSet;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

/**
 * The whole client side of the X-server-free display: a {@link SurfaceView} whose buffer the
 * VM draws into directly, plus the touch and key translation the X server used to do.
 *
 * <p>Coordinates are mapped physical → logical, so a logical size smaller than the surface
 * (see {@link NativeDisplay#setLogicalSize}) zooms in hardware while taps still land where
 * the user aimed. Note that this zoom is the opposite of the X server's: there the image
 * keeps its size and the result is upscaled, here the image is told it has a smaller screen
 * and re-lays-out into it, so text is drawn large rather than magnified.
 */
public class SqueakSurfaceView extends SurfaceView
        implements SurfaceHolder.Callback, SmalltalkDisplay {

    private static final String TAG = "Cuis";

    /**
     * Squeak's button bits (sq.h). Which bit produces which behaviour is NOT what the
     * colour names suggest, so this is written down rather than guessed: the Unix VM maps
     * X buttons 1/2/3 to the red/yellow/blue bits in that order (rybMap, sqUnixX11.c), and
     * the X path sends **button 3 for the context menu** and **button 2 for halos**
     * (ScreenView.armRightClick / armMiddleClick, verified on Cuis 7.7). Therefore
     * menu = BIT_BLUE and halos = BIT_YELLOW. Reproducing those exact bits is what makes
     * this backend behave identically to the X one.
     */
    private static final int BIT_RED = 4, BIT_YELLOW = 2, BIT_BLUE = 1;

    /** Squeak's key event kinds (sq.h). */
    private static final int KEY_CHAR = 0, KEY_DOWN = 1, KEY_UP = 2;

    private int _surfaceW, _surfaceH;      // physical pixels
    private int _logicalW, _logicalH;      // what the image believes the screen is
    private float _pendingScale = 1f;      // requested zoom, applied once a surface exists
    private int _buttons;                  // currently held Squeak buttons
    private int _armedButton;              // set by the pill's ⊙ / ✦, consumed by one tap
    private int _downButton = BIT_RED;     // the button THIS gesture pressed
    private int _touchOffsetY;             // precise pointer: aim above the fingertip
    private boolean _sharedClipboard;
    private SurfaceReadyListener _listener;

    /** Told once, when the surface first exists and the VM may be started against it. */
    public interface SurfaceReadyListener {
        void onSurfaceReady(int width, int height);
    }

    public SqueakSurfaceView(Context context) { this(context, null); }

    public SqueakSurfaceView(Context context, AttributeSet attrs) {
        super(context, attrs);
        getHolder().addCallback(this);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setKeepScreenOn(true);
        // Never setZOrderOnTop(true): it would put this surface above the window and hide
        // the floating pill and every dialog behind the Smalltalk world.
    }

    public void setSurfaceReadyListener(SurfaceReadyListener l) { _listener = l; }

    // --- SmalltalkDisplay --------------------------------------------------

    @Override public android.view.View asView() { return this; }

    @Override
    public boolean supports(Feature f) {
        switch (f) {
        case ZOOM:
        case PRECISE_POINTER:
        case SHARED_CLIPBOARD:
        case FILE_IN:
            return true;
        case SMOOTH_ZOOM:
            // Nothing to toggle: the X path filters a Canvas blit
            // (_paint.setFilterBitmap), while here the scaling is SurfaceFlinger's and
            // ANativeWindow offers no filter knob. The image re-lays-out instead, so
            // there is no interpolation to soften in the first place.
            return false;
        case TRACKPAD:
        case POINTER_ARROW:
        case LONG_PRESS_MENU:
            return false;   // ported separately; see docs/NATIVE-DISPLAY.md
        default:
            return false;
        }
    }

    @Override
    public boolean isEnabled(Feature f) {
        switch (f) {
        case PRECISE_POINTER:  return _touchOffsetY != 0;
        case SHARED_CLIPBOARD: return _sharedClipboard;
        default:               return false;
        }
    }

    @Override
    public boolean toggle(Feature f) {
        switch (f) {
        case PRECISE_POINTER:
            _touchOffsetY = (_touchOffsetY != 0) ? 0
                    : Math.round(48 * getResources().getDisplayMetrics().density);
            return _touchOffsetY != 0;
        case SHARED_CLIPBOARD:
            _sharedClipboard = !_sharedClipboard;
            if (_sharedClipboard) {
                NativeDisplay.setClipboardSink(this::copyToAndroidClipboard);
                pushAndroidClipboardIn();
            } else {
                NativeDisplay.setClipboardSink(null);
            }
            return _sharedClipboard;
        default:
            return false;
        }
    }

    @Override
    public float getDisplayScale() {
        return (_surfaceW == 0 || _logicalW == 0) ? 1f : _surfaceW / (float) _logicalW;
    }

    @Override
    public void setDisplayScale(float scale) {
        if (scale <= 0f) return;
        _pendingScale = scale;
        if (_surfaceW == 0) return;      // remembered; surfaceChanged will apply it
        applyScale();
    }

    private void applyScale() {
        _logicalW = Math.max(1, Math.round(_surfaceW / _pendingScale));
        _logicalH = Math.max(1, Math.round(_surfaceH / _pendingScale));
        NativeDisplay.setLogicalSize(_logicalW, _logicalH);
    }

    @Override
    public void armNextTap(int button) {
        if (button == BUTTON_MENU)       _armedButton = BIT_BLUE;
        else if (button == BUTTON_HALOS) _armedButton = BIT_YELLOW;
        else                             _armedButton = 0;
    }

    /**
     * Hand a file to the running image as a drag-and-drop.
     *
     * <p>The pointer is warped first for the same reason the X path does it: the image
     * dispatches the drop at the VM's last mouse position, and DropFilesEvent rejects a
     * position outside the world. Two moves, not one, because the first may only produce
     * an enter/leave when the window changes.
     */
    @Override
    public boolean dropFile(String absolutePath) {
        if (absolutePath == null || !NativeDisplay.isReady()) return false;
        if (_logicalW == 0 || _logicalH == 0) return false;
        int cx = _logicalW / 2, cy = _logicalH / 3;
        NativeDisplay.postMouse(cx, Math.max(0, cy - 8), 0, 0);
        NativeDisplay.postMouse(cx, cy, 0, 0);
        NativeDisplay.postDropFile(absolutePath);
        return true;
    }

    /** No pointer concept yet on this backend, so the IME panner stays out of the way. */
    @Override public int caretY() { return -1; }

    @Override public void applyImePan(float translationY) { /* see caretY() */ }

    // --- clipboard ---------------------------------------------------------

    private void copyToAndroidClipboard(String text) {
        if (!_sharedClipboard || text == null) return;
        post(() -> {
            ClipboardManager cm = (ClipboardManager) getContext()
                    .getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Smalltalk", text));
        });
    }

    /** Make what Android has on the clipboard visible to the image's paste. */
    public void pushAndroidClipboardIn() {
        if (!_sharedClipboard) return;
        ClipboardManager cm = (ClipboardManager) getContext()
                .getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip() || cm.getPrimaryClip() == null) return;
        if (cm.getPrimaryClip().getItemCount() < 1) return;
        CharSequence cs = cm.getPrimaryClip().getItemAt(0).coerceToText(getContext());
        if (cs != null) NativeDisplay.setClipboard(cs.toString());
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (hasWindowFocus) pushAndroidClipboardIn();
    }

    // --- surface lifecycle -------------------------------------------------

    @Override
    public void surfaceCreated(SurfaceHolder holder) { }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        boolean first = (_surfaceW == 0);
        _surfaceW = width;
        _surfaceH = height;
        Log.i(TAG, "native display: surface " + width + "x" + height + " scale " + _pendingScale);
        NativeDisplay.setSurface(holder.getSurface(), width, height);
        // Recompute on EVERY call, not only the first: after a rotation the logical size
        // must follow the new aspect ratio, or the compositor stretches the old one.
        applyScale();
        if (first && _listener != null) _listener.onSurfaceReady(width, height);
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        Log.i(TAG, "native display: surface destroyed");
        NativeDisplay.setSurface(null, 0, 0);   // blocks until any blit in flight finishes
    }

    // --- touch -------------------------------------------------------------

    private int mapX(float x) {
        if (_surfaceW == 0) return (int) x;
        int v = Math.round(x * _logicalW / (float) _surfaceW);
        return v < 0 ? 0 : (v >= _logicalW ? _logicalW - 1 : v);
    }

    private int mapY(float y) {
        if (_surfaceH == 0) return (int) y;
        int v = Math.round((y - _touchOffsetY) * _logicalH / (float) _surfaceH);
        return v < 0 ? 0 : (v >= _logicalH ? _logicalH - 1 : v);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int x = mapX(event.getX());
        int y = mapY(event.getY());

        switch (event.getActionMasked()) {

        case MotionEvent.ACTION_DOWN:
            // Consume the arm here and remember the button for the release: clearing the
            // flag between down and up would release a different button and leave the
            // armed one stuck down in the image (the X path learned this the hard way).
            _downButton = (_armedButton != 0) ? _armedButton : BIT_RED;
            _armedButton = 0;
            _buttons = _downButton;
            // Move with no button first: the image tracks the pointer, and a press that
            // arrives at a stale position clicks wherever the previous one was.
            NativeDisplay.postMouse(x, y, 0, 0);
            NativeDisplay.postMouse(x, y, _buttons, 0);
            return true;

        case MotionEvent.ACTION_POINTER_DOWN:
            // Two fingers = context menu, as a complete click, matching the X path
            // (ScreenView sends button 3 down+up on the second finger). Release the first
            // finger's button first, or the image sees two buttons held at once.
            if (event.getActionIndex() == 1) {
                NativeDisplay.postMouse(x, y, 0, 0);
                NativeDisplay.postMouse(x, y, BIT_BLUE, 0);
                NativeDisplay.postMouse(x, y, 0, 0);
                _buttons = 0;
                _downButton = BIT_RED;
            }
            return true;

        case MotionEvent.ACTION_MOVE:
            NativeDisplay.postMouse(x, y, _buttons, 0);
            return true;

        case MotionEvent.ACTION_UP:
        case MotionEvent.ACTION_CANCEL:
            NativeDisplay.postMouse(x, y, 0, 0);
            _buttons = 0;
            _downButton = BIT_RED;
            return true;

        case MotionEvent.ACTION_POINTER_UP:
            return true;    // the gesture continues with the remaining finger
        }
        return super.onTouchEvent(event);
    }

    // --- keyboard ----------------------------------------------------------

    /** Returning null makes the IME send key events, which is what the image understands. */
    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        outAttrs.inputType = InputType.TYPE_TEXT_VARIATION_NORMAL;
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE | EditorInfo.IME_FLAG_NO_FULLSCREEN;
        return null;
    }

    private static int squeakModifiers(KeyEvent event) {
        int m = 0;
        if (event.isShiftPressed()) m |= 1;      // ShiftKeyBit
        if (event.isCtrlPressed())  m |= 2;      // CtrlKeyBit
        if (event.isAltPressed())   m |= 4;      // OptionKeyBit
        if (event.isMetaPressed())  m |= 8;      // CommandKeyBit
        return m;
    }

    /** Android key code → the character code the image expects, or -1 if it is not one. */
    private static int squeakKeyCode(int keyCode) {
        switch (keyCode) {
        case KeyEvent.KEYCODE_DEL:          return 8;
        case KeyEvent.KEYCODE_TAB:          return 9;
        case KeyEvent.KEYCODE_ENTER:        return 13;
        case KeyEvent.KEYCODE_ESCAPE:       return 27;
        case KeyEvent.KEYCODE_MOVE_HOME:    return 1;
        case KeyEvent.KEYCODE_MOVE_END:     return 4;
        case KeyEvent.KEYCODE_INSERT:       return 5;
        case KeyEvent.KEYCODE_PAGE_UP:      return 11;
        case KeyEvent.KEYCODE_PAGE_DOWN:    return 12;
        case KeyEvent.KEYCODE_DPAD_LEFT:    return 28;
        case KeyEvent.KEYCODE_DPAD_RIGHT:   return 29;
        case KeyEvent.KEYCODE_DPAD_UP:      return 30;
        case KeyEvent.KEYCODE_DPAD_DOWN:    return 31;
        case KeyEvent.KEYCODE_FORWARD_DEL:  return 127;
        default:                            return -1;
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) return super.onKeyDown(keyCode, event);

        int mods = squeakModifiers(event);
        int code = squeakKeyCode(keyCode);
        int ucs4 = (code >= 0) ? code : event.getUnicodeChar(event.getMetaState());
        if (ucs4 == 0 && code < 0) return super.onKeyDown(keyCode, event);
        if (code < 0) code = ucs4;

        // Down then char, exactly as the X11 driver does it (sqUnixX11.c).
        NativeDisplay.postKey(code, KEY_DOWN, mods, ucs4);
        NativeDisplay.postKey(code, KEY_CHAR, mods, ucs4);
        return true;
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) return super.onKeyUp(keyCode, event);

        int mods = squeakModifiers(event);
        int code = squeakKeyCode(keyCode);
        int ucs4 = (code >= 0) ? code : event.getUnicodeChar(event.getMetaState());
        if (ucs4 == 0 && code < 0) return super.onKeyUp(keyCode, event);
        if (code < 0) code = ucs4;

        NativeDisplay.postKey(code, KEY_UP, mods, ucs4);
        return true;
    }

    /**
     * Soft keyboards that commit whole strings (autocorrect, prediction) send them as one
     * multi-character event rather than key by key.
     */
    @Override
    public boolean onKeyMultiple(int keyCode, int repeatCount, KeyEvent event) {
        String chars = event.getCharacters();
        if (chars == null) return super.onKeyMultiple(keyCode, repeatCount, event);
        for (int i = 0; i < chars.length(); i++) {
            int c = chars.charAt(i);
            NativeDisplay.postKey(c, KEY_DOWN, 0, c);
            NativeDisplay.postKey(c, KEY_CHAR, 0, c);
            NativeDisplay.postKey(c, KEY_UP, 0, c);
        }
        return true;
    }
}
