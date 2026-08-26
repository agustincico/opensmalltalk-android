package au.com.darkside.x11server;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.AttributeSet;
import android.util.Log;
import android.view.ActionMode;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
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

    /** Where the image believes the pointer is, in LOGICAL coordinates. -1 = nowhere yet. */
    private int _pointerX = -1, _pointerY = -1;
    private boolean _showPointer = true;
    private PointerOverlayView _overlay;

    /** Trackpad mode: the finger drives a RELATIVE cursor, laptop style. */
    private boolean _trackpad;
    private float _tpLastX, _tpLastY, _tpDownX, _tpDownY;
    private boolean _tpMoved, _tpDragging;
    private int _touchSlop;
    private final Handler _handler = new Handler(Looper.getMainLooper());
    private Runnable _tpLongPress;

    /** Long-press menu (CTRL chords, clicks, keyboard). Off by default, as on the X path. */
    private boolean _longPressMenu;
    private Runnable _lpRun;
    private float _lpDownX, _lpDownY;
    private ActionMode _actionMode;
    private Runnable _keyboardToggler;

    private static final int ACT_CTRL_C = 1, ACT_CTRL_V = 2, ACT_CTRL_X = 3, ACT_CTRL_A = 4,
                             ACT_ESC = 5, ACT_M_CLICK = 6, ACT_R_CLICK = 7,
                             ACT_KEYBOARD = 8, ACT_CANCEL = 9;

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

    @Override public View asView() { return this; }

    @Override
    public boolean supports(Feature f) {
        switch (f) {
        case ZOOM:
        case PRECISE_POINTER:
        case SHARED_CLIPBOARD:
        case FILE_IN:
        case TRACKPAD:
        case POINTER_ARROW:
        case LONG_PRESS_MENU:
            return true;
        case SMOOTH_ZOOM:
            // Nothing to toggle: the X path filters a Canvas blit
            // (_paint.setFilterBitmap), while here the scaling is SurfaceFlinger's and
            // ANativeWindow offers no filter knob. The image re-lays-out instead, so
            // there is no interpolation to soften in the first place.
            return false;
        default:
            return false;
        }
    }

    @Override
    public boolean isEnabled(Feature f) {
        switch (f) {
        case PRECISE_POINTER:  return _touchOffsetY != 0;
        case SHARED_CLIPBOARD: return _sharedClipboard;
        case TRACKPAD:         return _trackpad;
        case POINTER_ARROW:    return _showPointer;
        case LONG_PRESS_MENU:  return _longPressMenu;
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
        case TRACKPAD:
            // Leaving a button held here would trap the image in a drag forever: there is
            // no server in between to notice the press was never matched.
            releaseHeldButtons();
            cancelTrackpadTimer();
            _tpDragging = _tpMoved = false;
            _trackpad = !_trackpad;
            return _trackpad;
        case POINTER_ARROW:
            _showPointer = !_showPointer;
            if (_overlay != null) _overlay.setVisible(_showPointer);
            return _showPointer;
        case LONG_PRESS_MENU:
            _longPressMenu = !_longPressMenu;
            if (!_longPressMenu) cancelLongPressTimer();
            return _longPressMenu;
        default:
            return false;
        }
    }

    /** Created by the activity and added to the frame just above this surface. */
    View attachPointerOverlay(Context context) {
        _overlay = new PointerOverlayView(context);
        _overlay.setVisible(_showPointer);
        return _overlay;
    }

    /** The long-press menu's Keyboard item; the view should not reach into the activity. */
    public void setKeyboardToggler(Runnable r) { _keyboardToggler = r; }

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
        // A fixed logical point lands somewhere else physically once the logical size
        // changes, so re-clamp and re-place the arrow instead of leaving it behind.
        if (_pointerX >= 0) {
            _pointerX = Math.min(_pointerX, _logicalW - 1);
            _pointerY = Math.min(_pointerY, _logicalH - 1);
            pushPointerToOverlay();
        }
    }

    /**
     * The one place an effect becomes a button bit. Both the pill's arming buttons and the
     * long-press menu's clicks go through it, so a wrong constant is a compile error rather
     * than two menu items quietly swapping behaviour — which is exactly the bug the X path
     * shipped for a while ("M-Click" registered under the right-click action id).
     */
    private static int squeakBitFor(int effect) {
        if (effect == BUTTON_MENU)  return BIT_BLUE;
        if (effect == BUTTON_HALOS) return BIT_YELLOW;
        return BIT_RED;
    }

    @Override
    public void armNextTap(int button) {
        _armedButton = (button == BUTTON_MENU || button == BUTTON_HALOS)
                ? squeakBitFor(button) : 0;
    }

    /**
     * Every pointer movement and button change goes through here: it posts to the image,
     * remembers where the pointer is and keeps the on-screen arrow with it. Coordinates are
     * LOGICAL — the same ones the image works in.
     */
    private void postPointer(int lx, int ly, int buttons) {
        NativeDisplay.postMouse(lx, ly, buttons, 0);
        if (lx == _pointerX && ly == _pointerY) return;
        _pointerX = lx;
        _pointerY = ly;
        pushPointerToOverlay();
    }

    private void pushPointerToOverlay() {
        if (_overlay == null || _pointerX < 0 || _logicalW == 0 || _logicalH == 0) return;
        _overlay.moveTo(_pointerX * _surfaceW / (float) _logicalW,
                        _pointerY * _surfaceH / (float) _logicalH);
    }

    /** Release anything still held, so a button can never be left down in the image. */
    private void releaseHeldButtons() {
        if (_buttons == 0) return;
        _buttons = 0;
        if (_pointerX >= 0) NativeDisplay.postMouse(_pointerX, _pointerY, 0, 0);
    }

    private void cancelTrackpadTimer() {
        if (_tpLongPress != null) { _handler.removeCallbacks(_tpLongPress); _tpLongPress = null; }
    }

    private void cancelLongPressTimer() {
        if (_lpRun != null) { _handler.removeCallbacks(_lpRun); _lpRun = null; }
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
        postPointer(cx, Math.max(0, cy - 8), 0);
        postPointer(cx, cy, 0);
        NativeDisplay.postDropFile(absolutePath);
        return true;
    }

    /**
     * The pointer's row in PHYSICAL pixels of the untranslated view, which is what the IME
     * panner measures against.
     *
     * <p>The Y ratio, not {@link #getDisplayScale()} — that is the WIDTH ratio, and the two
     * axes are rounded independently, so they differ for a fractional zoom and differ more
     * after a rotation. And deliberately NOT adjusted for the pan already applied: the
     * layout listener re-runs after every pan, so a caret that moved with it would make the
     * pan chase itself.
     */
    @Override
    public int caretY() {
        if (_pointerY < 0 || _surfaceH == 0 || _logicalH == 0) return -1;
        return Math.round(_pointerY * _surfaceH / (float) _logicalH);
    }

    @Override
    public void applyImePan(float translationY) {
        if (getTranslationY() != translationY) setTranslationY(translationY);
        // The arrow must ride with the world it points at, or it detaches during the pan.
        if (_overlay != null && _overlay.getTranslationY() != translationY)
            _overlay.setTranslationY(translationY);
    }

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
        // A timer that fires after the surface is gone would push a press into a queue the
        // image drains on its next run.
        cancelTrackpadTimer();
        cancelLongPressTimer();
        releaseHeldButtons();
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
        if (_trackpad) { handleTrackpadTouch(event); return true; }

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
            scheduleLongPressMenu(event.getX(), event.getY());
            // Move with no button first: the image tracks the pointer, and a press that
            // arrives at a stale position clicks wherever the previous one was.
            postPointer(x, y, 0);
            postPointer(x, y, _buttons);
            return true;

        case MotionEvent.ACTION_POINTER_DOWN:
            // Two fingers = context menu, as a complete click, matching the X path
            // (ScreenView sends button 3 down+up on the second finger). Release the first
            // finger's button first, or the image sees two buttons held at once.
            if (event.getActionIndex() == 1) {
                cancelLongPressTimer();
                postPointer(x, y, 0);
                postPointer(x, y, BIT_BLUE);
                postPointer(x, y, 0);
                _buttons = 0;
                _downButton = BIT_RED;
            }
            return true;

        case MotionEvent.ACTION_MOVE:
            vetoLongPressOnMovement(event.getX(), event.getY());
            postPointer(x, y, _buttons);
            return true;

        case MotionEvent.ACTION_UP:
        case MotionEvent.ACTION_CANCEL:
            cancelLongPressTimer();
            postPointer(x, y, 0);
            _buttons = 0;
            _downButton = BIT_RED;
            return true;

        case MotionEvent.ACTION_POINTER_UP:
            return true;    // the gesture continues with the remaining finger
        }
        return super.onTouchEvent(event);
    }

    // --- trackpad mode -----------------------------------------------------

    /**
     * The finger drives a relative cursor instead of touching the world directly: a slide
     * moves the pointer with NO button held (so hovering opens Cuis submenus, and your
     * finger never covers what you are aiming at), a quick tap clicks at the cursor,
     * press-pause-drag drags, and a second finger opens the context menu.
     *
     * <p>Ported from ScreenView.handleTrackpadTouch, which is pure MotionEvent arithmetic;
     * only the two sinks change. The one rule that makes it work is that a hover must carry
     * no buttons — that is why the press is not emitted on ACTION_DOWN.
     */
    private void handleTrackpadTouch(MotionEvent event) {
        if (_touchSlop == 0)
            _touchSlop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
        final float scale = getDisplayScale();

        switch (event.getActionMasked()) {

        case MotionEvent.ACTION_DOWN:
            _tpDownX = _tpLastX = event.getX();
            _tpDownY = _tpLastY = event.getY();
            _tpMoved = false;
            _tpDragging = false;
            if (_pointerX < 0) {   // first ever gesture: start in the middle of the world
                _pointerX = _logicalW / 2;
                _pointerY = _logicalH / 2;
                pushPointerToOverlay();
            }
            cancelTrackpadTimer();
            scheduleLongPressMenu(event.getX(), event.getY());
            _tpLongPress = () -> {
                // removeCallbacks loses to an already-dispatched runnable, so re-check.
                if (!_tpMoved && !_tpDragging) {
                    _tpDragging = true;
                    cancelLongPressTimer();     // this hold is a drag, not a menu
                    _buttons = BIT_RED;
                    postPointer(_pointerX, _pointerY, _buttons);
                }
            };
            _handler.postDelayed(_tpLongPress, 350);
            break;

        case MotionEvent.ACTION_MOVE: {
            float dx = event.getX() - _tpLastX;
            float dy = event.getY() - _tpLastY;
            _tpLastX = event.getX();
            _tpLastY = event.getY();
            int nx = Math.max(0, Math.min(_logicalW - 1, _pointerX + Math.round(dx / scale)));
            int ny = Math.max(0, Math.min(_logicalH - 1, _pointerY + Math.round(dy / scale)));
            postPointer(nx, ny, _buttons);
            double dist = Math.hypot(event.getX() - _tpDownX, event.getY() - _tpDownY);
            // Any real movement means a slide, not a still hold: cancel the pending
            // hold-to-drag on a SMALL threshold so a slow, precise slide never becomes a
            // drag. Dragging is press AND pause.
            if (!_tpDragging && dist > 10 * getResources().getDisplayMetrics().density)
                cancelTrackpadTimer();
            if (dist > _touchSlop) {
                _tpMoved = true;
                vetoLongPressOnMovement(event.getX(), event.getY());
            }
            break;
        }

        case MotionEvent.ACTION_POINTER_DOWN:
            cancelTrackpadTimer();
            cancelLongPressTimer();
            if (_tpDragging) { _buttons = 0; postPointer(_pointerX, _pointerY, 0); _tpDragging = false; }
            postPointer(_pointerX, _pointerY, BIT_BLUE);    // second finger → context menu
            postPointer(_pointerX, _pointerY, 0);
            _tpMoved = true;                                // and no click when it lifts
            break;

        case MotionEvent.ACTION_UP:
        case MotionEvent.ACTION_CANCEL:
            cancelTrackpadTimer();
            cancelLongPressTimer();
            if (_tpDragging) {
                _buttons = 0;
                postPointer(_pointerX, _pointerY, 0);       // end the drag
                _tpDragging = false;
            } else if (!_tpMoved && event.getActionMasked() == MotionEvent.ACTION_UP) {
                // Quick tap → a click AT THE CURSOR, not at the finger. The arm is consumed
                // here and not at DOWN: a slide emits no click, and consuming it there
                // would silently swallow the armed button.
                int bit = (_armedButton != 0) ? _armedButton : BIT_RED;
                _armedButton = 0;
                postPointer(_pointerX, _pointerY, bit);
                postPointer(_pointerX, _pointerY, 0);
            }
            _buttons = 0;
            break;
        }
    }

    // --- long-press menu ---------------------------------------------------

    private void scheduleLongPressMenu(float downX, float downY) {
        cancelLongPressTimer();
        if (!_longPressMenu) return;
        _lpDownX = downX;
        _lpDownY = downY;
        _lpRun = () -> {
            _lpRun = null;
            showLongPressMenu();
        };
        _handler.postDelayed(_lpRun, ViewConfiguration.getLongPressTimeout());
    }

    /** A finger that travels is a gesture, not a long press. */
    private void vetoLongPressOnMovement(float x, float y) {
        if (_lpRun == null) return;
        if (Math.hypot(x - _lpDownX, y - _lpDownY) > 20 * getResources().getDisplayMetrics().density)
            cancelLongPressTimer();
    }

    private void showLongPressMenu() {
        if (_actionMode != null) return;
        _actionMode = startActionMode(new ActionMode.Callback() {
            @Override
            public boolean onCreateActionMode(ActionMode mode, Menu menu) {
                menu.add(0, ACT_CTRL_C, 0, "CTRL+C");
                menu.add(0, ACT_CTRL_V, 0, "CTRL+V");
                menu.add(0, ACT_CTRL_X, 0, "CTRL+X");
                menu.add(0, ACT_CTRL_A, 0, "CTRL+A");
                menu.add(0, ACT_ESC, 0, "ESC");
                menu.add(0, ACT_M_CLICK, 0, "M-Click (halos)");
                menu.add(0, ACT_R_CLICK, 0, "R-Click");
                menu.add(0, ACT_KEYBOARD, 0, "Keyboard");
                menu.add(0, ACT_CANCEL, 0, "Cancel");
                return true;
            }

            @Override public boolean onPrepareActionMode(ActionMode mode, Menu menu) { return false; }

            @Override
            public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
                switch (item.getItemId()) {
                case ACT_CTRL_C: ctrlChord(3, 'c'); break;
                case ACT_CTRL_V: pushAndroidClipboardIn(); ctrlChord(22, 'v'); break;
                case ACT_CTRL_X: ctrlChord(24, 'x'); break;
                case ACT_CTRL_A: ctrlChord(1, 'a'); break;
                case ACT_ESC:
                    NativeDisplay.postKey(27, KEY_DOWN, 0, 27);
                    NativeDisplay.postKey(27, KEY_CHAR, 0, 27);
                    NativeDisplay.postKey(27, KEY_UP, 0, 27);
                    break;
                case ACT_M_CLICK: clickHere(squeakBitFor(BUTTON_HALOS)); break;
                case ACT_R_CLICK: clickHere(squeakBitFor(BUTTON_MENU)); break;
                case ACT_KEYBOARD: if (_keyboardToggler != null) _keyboardToggler.run(); break;
                default: break;
                }
                mode.finish();
                return true;
            }

            @Override public void onDestroyActionMode(ActionMode mode) { _actionMode = null; }
        }, ActionMode.TYPE_FLOATING);
    }

    /**
     * Squeak sees a Ctrl chord as the CONTROL CHARACTER carrying CtrlKeyBit, with the plain
     * letter as the Unicode value — which is exactly what XLookupString handed the VM on the
     * X path, control character and all.
     */
    private void ctrlChord(int controlChar, int letter) {
        NativeDisplay.postKey(controlChar, KEY_DOWN, 2, letter);
        NativeDisplay.postKey(controlChar, KEY_CHAR, 2, letter);
        NativeDisplay.postKey(controlChar, KEY_UP, 2, letter);
        // The driver keeps the modifier word globally, so without this the NEXT tap would
        // still be a ctrl-click. On the X path the Control_L release did it implicitly.
        if (_pointerX >= 0) NativeDisplay.postMouse(_pointerX, _pointerY, 0, 0);
    }

    /** A complete click at the pointer, used by the menu's R-Click / M-Click items. */
    private void clickHere(int squeakBit) {
        if (_pointerX < 0) return;
        pushAndroidClipboardIn();          // the image's context menu offers Paste
        postPointer(_pointerX, _pointerY, 0);
        postPointer(_pointerX, _pointerY, squeakBit);
        postPointer(_pointerX, _pointerY, 0);
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
