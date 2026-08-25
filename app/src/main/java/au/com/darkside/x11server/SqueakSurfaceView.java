package au.com.darkside.x11server;

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
 * VM draws into directly, plus the touch/key translation the X server used to do.
 *
 * <p>Coordinates are mapped physical → logical, so a logical size smaller than the surface
 * (see {@link NativeDisplay#setLogicalSize}) zooms in hardware while taps still land where
 * the user aimed.
 */
public class SqueakSurfaceView extends SurfaceView implements SurfaceHolder.Callback {

    private static final String TAG = "Cuis";

    /** Squeak's button bits (sq.h): red is the primary button. */
    private static final int RED_BUTTON = 4, YELLOW_BUTTON = 2, BLUE_BUTTON = 1;

    /** Squeak's key event kinds (sq.h). */
    private static final int KEY_CHAR = 0, KEY_DOWN = 1, KEY_UP = 2;

    private int _surfaceW, _surfaceH;      // physical pixels
    private int _logicalW, _logicalH;      // what the image believes the screen is
    private int _buttons;                  // currently held Squeak buttons
    private boolean _multiTouch;           // a second finger joined this gesture
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
    }

    public void setSurfaceReadyListener(SurfaceReadyListener l) { _listener = l; }

    public int getLogicalWidth()  { return _logicalW; }
    public int getLogicalHeight() { return _logicalH; }

    /**
     * Render at {@code 1/scale} of the physical resolution and let the compositor scale the
     * result up. Unlike the X server's zoom this makes the VM draw FEWER pixels.
     */
    public void setDisplayScale(float scale) {
        if (scale <= 0f || _surfaceW == 0) return;
        _logicalW = Math.max(1, Math.round(_surfaceW / scale));
        _logicalH = Math.max(1, Math.round(_surfaceH / scale));
        NativeDisplay.setLogicalSize(_logicalW, _logicalH);
    }

    // --- surface lifecycle -------------------------------------------------

    @Override
    public void surfaceCreated(SurfaceHolder holder) { }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        boolean first = (_surfaceW == 0);
        _surfaceW = width;
        _surfaceH = height;
        if (_logicalW == 0 || first) { _logicalW = width; _logicalH = height; }
        Log.i(TAG, "native display: surface " + width + "x" + height
                   + " logical " + _logicalW + "x" + _logicalH);
        NativeDisplay.setSurface(holder.getSurface(), width, height);
        if (_logicalW != width || _logicalH != height)
            NativeDisplay.setLogicalSize(_logicalW, _logicalH);
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
        int v = Math.round(y * _logicalH / (float) _surfaceH);
        return v < 0 ? 0 : (v >= _logicalH ? _logicalH - 1 : v);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int x = mapX(event.getX());
        int y = mapY(event.getY());

        switch (event.getActionMasked()) {

        case MotionEvent.ACTION_DOWN:
            _multiTouch = false;
            _buttons = RED_BUTTON;
            // Move first with no button: the image tracks the pointer, and a press
            // arriving at a stale position clicks wherever the last one was.
            NativeDisplay.postMouse(x, y, 0, 0);
            NativeDisplay.postMouse(x, y, _buttons, 0);
            return true;

        case MotionEvent.ACTION_POINTER_DOWN:
            // Two fingers mean "right click" — release the first finger's button
            // before pressing the other, or the image sees both at once.
            _multiTouch = true;
            NativeDisplay.postMouse(x, y, 0, 0);
            _buttons = BLUE_BUTTON;
            NativeDisplay.postMouse(x, y, _buttons, 0);
            return true;

        case MotionEvent.ACTION_MOVE:
            NativeDisplay.postMouse(x, y, _buttons, 0);
            return true;

        case MotionEvent.ACTION_UP:
        case MotionEvent.ACTION_CANCEL:
            _buttons = 0;
            NativeDisplay.postMouse(x, y, 0, 0);
            _multiTouch = false;
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
