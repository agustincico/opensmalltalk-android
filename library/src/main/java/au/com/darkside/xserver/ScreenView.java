package au.com.darkside.xserver;


import android.app.Service;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.ActionMode;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedList;
import java.util.Queue;
import java.util.Vector;

/**
 * This class implements an X Windows screen.
 * <p>
 * It also implements the screen's root window.
 * </p>
 *
 * @author Matthew Kwan
 */
public class ScreenView extends View {
    private static final String LOG_TAG = "ScreenView";

    static public abstract class ScreenViewOnTouchCallback {
        public abstract boolean onTouch(View v, MotionEvent event);
    }

    private ScreenViewOnTouchCallback onTouchCallback;

    private interface PendingEvent {
        public void run();
    }

    private interface PendingPointerEvent extends PendingEvent {
    }

    private class PendingGrabButtonNotify implements PendingPointerEvent {

        private Window mWindow;
        private boolean mPressed;
        private int mMotionX;
        private int mMotionY;
        private int mButton;
        private int mGrabEventMask;
        private Client mGrabPointerClient;
        private boolean mGrabPointerOwnerEvents;

        public PendingGrabButtonNotify(Window w, boolean pressed, int motionX, int motionY, int button,
                                       int grabEventMask, Client grabPointerClient, boolean grabPointerOwnerEvents) {
            mWindow = w;
            mPressed = pressed;
            mMotionX = motionX;
            mMotionY = motionY;
            mButton = button;
            mGrabEventMask = grabEventMask;
            mGrabPointerClient = grabPointerClient;
            mGrabPointerOwnerEvents = grabPointerOwnerEvents;
        }

        public void run() {
            callGrabButtonNotify(mWindow, mPressed, mMotionX, mMotionY, mButton, mGrabEventMask, mGrabPointerClient,
                    mGrabPointerOwnerEvents);
        }
    }

    private class PendingGrabMotionNotify implements PendingPointerEvent {

        private Window mWindow;
        private int mX;
        private int mY;
        private int mButtons;
        private int mGrabEventMask;
        private Client mGrabPointerClient;
        private boolean mGrabPointerOwnerEvents;

        public PendingGrabMotionNotify(Window w, int x, int y, int buttons, int grabEventMask, Client grabPointerClient,
                                       boolean grabPointerOwnerEvents) {
            mWindow = w;
            mX = x;
            mY = y;
            mButtons = buttons;
            mGrabEventMask = grabEventMask;
            mGrabPointerClient = grabPointerClient;
            mGrabPointerOwnerEvents = grabPointerOwnerEvents;
        }

        public void run() {
            callGrabMotionNotify(mWindow, mX, mY, mButtons, mGrabEventMask, mGrabPointerClient,
                    mGrabPointerOwnerEvents);
        }
    }

    private interface PendingKeyboardEvent extends PendingEvent {
    }

    private class PendingGrabKeyNotify implements PendingKeyboardEvent {

        private Window mWindow;
        private boolean mPressed;
        private int mMotionX;
        private int mMotionY;
        private int mKeycode;
        private Client mGrabKeyboardClient;
        private boolean mGrabKeyboardOwnerEvents;

        public PendingGrabKeyNotify(Window w, boolean pressed, int motionX, int motionY, int keycode,
                                    Client grabKeyboardClient, boolean grabKeyboardOwnerEvents) {
            mWindow = w;
            mPressed = pressed;
            mMotionX = motionX;
            mMotionY = motionY;
            mKeycode = keycode;
            mGrabKeyboardClient = grabKeyboardClient;
            mGrabKeyboardOwnerEvents = grabKeyboardOwnerEvents;
        }

        public void run() {
            callGrabKeyNotify(mWindow, mPressed, mMotionX, mMotionY, mKeycode, mGrabKeyboardClient,
                    mGrabKeyboardOwnerEvents);
        }
    }

    private static class PendingEventQueue<T extends PendingEvent> {

        private Queue<T> mQueue = new LinkedList<T>();

        public void add(T event) {
            if (mQueue.offer(event)) {
                return;
            }
        }

        public T next() {
            return mQueue.poll();
        }
    }

    private final XServer _xServer;
    private final int _rootId;
    private Window _rootWindow = null;
    private boolean _initialFullscreenApplied = false;  // force the world to fill the screen once, at startup

    // Backlog #3: display zoom. The X screen (root window) is rendered at
    // physicalSize / _displayScale, then the bitmap is scaled up to fill the
    // View, so everything Cuis draws is _displayScale× bigger and small targets
    // (menus, window buttons) are far easier to tap. 1.0 = off (native pixels).
    private float _displayScale = 1.0f;  // 1.0 = native; user raises it via the Zoom menu item

    /** X-screen (logical) width  = physical view width  / display scale. */
    public int logicalWidth()  { return Math.max(1, Math.round(getWidth()  / _displayScale)); }
    /** X-screen (logical) height = physical view height / display scale. */
    public int logicalHeight() { return Math.max(1, Math.round(getHeight() / _displayScale)); }

    public float getDisplayScale() { return _displayScale; }

    /** Set the zoom factor and re-fit the world to the new logical screen size. */
    public void setDisplayScale(float scale) {
        if (scale < 1.0f) scale = 1.0f;
        if (scale > 4.0f) scale = 4.0f;
        if (scale == _displayScale) return;
        _displayScale = scale;
        try {
            synchronized (_xServer) {
                if (_rootWindow != null) {
                    _rootWindow.resize(logicalWidth(), logicalHeight());
                    notifyClientsScreenResize(logicalWidth(), logicalHeight());
                }
            }
        } catch (Exception e) {
            Log.e("ScreenView", "setDisplayScale error: " + e.getMessage(), e);
        }
        postInvalidate();
        Log.i("ScreenView", "displayScale=" + _displayScale + " logical=" + logicalWidth() + "x" + logicalHeight());
    }

    /** Cycle the zoom in 0.25 steps, 1.0 .. 2.5, wrapping. Returns the new scale. */
    public float cycleDisplayScale() {
        float next = Math.round(_displayScale * 4f) / 4f + 0.25f;
        if (next > 2.5f) next = 1.0f;
        setDisplayScale(next);
        return _displayScale;
    }
    private Window _sharedClipboardWindow = null;
    private Property _sharedClipboardProperty = null;
    private Property _sharedClipboardPrimaryProperty = null;
    private Colormap _defaultColormap = null;
    private final Vector<Colormap> _installedColormaps;
    private final float _pixelsPerMillimeter;

    private Cursor _currentCursor;
    private int _currentCursorX;
    private int _currentCursorY;
    private Cursor _drawnCursor = null;
    private int _drawnCursorX;
    private int _drawnCursorY;
    private Window _motionWindow = null;
    private int _motionX;
    private int _motionY;
    private int _buttons = 0;
    private boolean _isBlanked = false;
    private boolean _arrowsAsButtons = false;
    private boolean _inhibitBackButton = false;
    private boolean _enableTouchClicks = true;
    private boolean _sharedClipboard = true;
    private Paint _paint;

    private Client _grabPointerClient = null;
    private Window _grabPointerWindow = null;
    private int _grabPointerTime = 0;
    private boolean _grabPointerOwnerEvents = false;
    private boolean _grabPointerSynchronous = false;
    private boolean _grabPointerPassive = false;
    private boolean _grabPointerAutomatic = false;
    private boolean _grabPointerFreezeNextEvent = false;
    private Client _grabKeyboardClient = null;
    private Window _grabKeyboardWindow = null;
    private int _grabKeyboardTime = 0;
    private boolean _grabKeyboardOwnerEvents = false;
    private boolean _grabKeyboardSynchronous = false;
    private boolean _grabKeyboardFreezeNextEvent = false;
    private Cursor _grabCursor = null;
    private Window _grabConfineWindow = null;
    private int _grabEventMask = 0;
    private PassiveKeyGrab _grabKeyboardPassiveGrab = null;

    private Window _focusWindow = null;
    private byte _focusRevertTo = 0; // 0=None, 1=Root, 2=Parent.
    private int _focusLastChangeTime = 0;

    private PendingEventQueue<PendingPointerEvent> mPendingPointerEvents;
    private PendingEventQueue<PendingKeyboardEvent> mPendingKeyboardEvents;

    private boolean _ignoreLongPress = false;
    // Long-press (hold a finger down) used to pop an ActionMode menu (CTRL+C/V/…,
    // R-Click, Keyboard). It gets in the way of Smalltalk's own press-and-hold
    // gestures, so it's OFF by default; re-enable from the options menu.
    private boolean _enableLongPressMenu = false;

    // Precise pointer: when > 0, the X pointer sits this many physical px ABOVE the
    // finger so the finger doesn't occlude small targets (window close box, menus).
    private int _touchOffsetY = 0;
    // Trackpad mode: the finger drives a *relative* cursor (laptop-trackpad style):
    // slide = move the cursor (hover → submenus open, precise aim), quick tap = click
    // at the cursor, press-and-hold-then-drag = drag with the button held.
    private boolean _trackpadMode = false;
    private float _tpLastX, _tpLastY, _tpDownX, _tpDownY;
    private boolean _tpMoved, _tpDragging;
    private int _touchSlop = 0;
    private final Handler _tpHandler = new Handler(Looper.getMainLooper());
    private Runnable _tpLongPress;
    // Right-click: the ⊙ button arms this so the NEXT tap is a right-click (button 3),
    // an easy way to get context menus (two-finger tap also right-clicks).
    private boolean _armRightClick = false;
    // Smooth zoom: bilinear upscale (better for image-heavy images like Dialogo, which
    // look blocky with the default nearest-neighbour upscale). Text is crisper with
    // nearest, so it's off by default.
    private boolean _smoothZoom = false;

    private static final int ACTION_CANCEL = 0;
    private static final int ACTION_CTRL_C = 1;
    private static final int ACTION_CTRL_V = 2;
    private static final int ACTION_CTRL_X = 3;
    private static final int ACTION_CTRL_A = 4;
    private static final int ACTION_R_CLICK = 5;
    private static final int ACTION_M_CLICK = 6;
    private static final int ACTION_ESC = 7;
    private static final int ACTION_KEYBOARD = 8;

    // -- helpers for movement thresholding, works around phones with cheap touch
    // screens
    private double _totalMove = 0;
    private double _xPrec = 0;
    private double _yPrec = 0;

    private int _xPrev = 0;
    private int _yPrev = 0;

    /**
     * Constructor.
     *
     * @param c                   The application context.
     * @param xServer             The X server.
     * @param rootId              The ID of the root window, to be created later.
     * @param pixelsPerMillimeter Screen resolution.
     */
    public ScreenView(Context c, XServer xServer, int rootId, float pixelsPerMillimeter) {
        super(c);

        setFocusable(true);
        setFocusableInTouchMode(true);

        _xServer = xServer;
        _rootId = rootId;
        _installedColormaps = new Vector<Colormap>();
        _pixelsPerMillimeter = pixelsPerMillimeter;
        _paint = new Paint();

        // Backlog: pick a legible default zoom for THIS device's physical size
        // (denser screen -> smaller physical pixels -> more zoom), independent of
        // raw resolution, so the world is readable from the first launch. The user
        // can still change it via the Zoom menu.
        try {
            int dpi = c.getResources().getDisplayMetrics().densityDpi;
            // gentle curve above mdpi(160). Round to 0.5 (favouring whole numbers)
            // and clamp 1.0–3.0: the upscale is nearest-neighbour, so an INTEGER
            // zoom (e.g. 2×) is pixel-crisp while a fractional one (1.75×) scales
            // unevenly and looks soft. e.g. 440dpi(emu/Pixel5)->2.0, 320dpi->1.5.
            float s = 1.0f + (dpi - 160) / 360.0f;
            s = Math.round(s * 2f) / 2f;
            _displayScale = Math.max(1.0f, Math.min(3.0f, s));
        } catch (Exception e) {
            _displayScale = 1.0f;
        }

        mPendingPointerEvents = new PendingEventQueue<PendingPointerEvent>();
        mPendingKeyboardEvents = new PendingEventQueue<PendingKeyboardEvent>();
        // ---- Listeners for touch input ----
        setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
               Log.d("setOnTouchListener", "Touch event: " + v);
            }
        });

        setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (onTouchCallback != null) {
                    Log.d(LOG_TAG, "onTouchCallback is not null. Calling onTouchCallback");
                    if (!onTouchCallback.onTouch(v, event)) {
                        return false;
                    }
                }

                Log.d(LOG_TAG, "onTouch: event: " + event.toString());

                /*
                 * int x = (int) event.getX();
                 * int y = (int) event.getY();
                 *
                 * // TODO: collibrate screen's average click event process time
                 * sleepThread(95);
                 *
                 * if (x ==_xPrev && y == _yPrev) {
                 * Log.d(LOG_TAG, "x and y is the same as old position");
                 * if(event.getPointerCount() == 3){
                 * Log.d(LOG_TAG, "onTouch: Three finger touch detected");
                 * toggleNavigationBar();
                 *
                 * }
                 * return false;
                 * }
                 *
                 * _xPrev = x;
                 * _yPrev = y;
                 */

                Log.d(LOG_TAG, "Process _xServer touches");
                synchronized (_xServer) {
                    if (_rootWindow == null)
                        return false;

                    blank(false); // Reset the screen saver.

                    // Trackpad mode drives a relative cursor — handled entirely below.
                    if (_trackpadMode) {
                        handleTrackpadTouch(event);
                        return false;
                    }

                    // Direct touch: map physical touch -> logical X coords (accounts for
                    // display zoom). With "precise pointer" on, lift the pointer a bit
                    // above the finger so it doesn't occlude the target.
                    updatePointerPosition((int) (event.getX() / _displayScale),
                            (int) ((event.getY() - _touchOffsetY) / _displayScale), 0);

                    if (_enableTouchClicks) {
                        final int action = event.getActionMasked();
                        // Primary finger = left button, or right button for ONE tap
                        // after the ⊙ (right-click) button is armed.
                        if (action == MotionEvent.ACTION_DOWN && event.getActionIndex() == 0)
                            updatePointerButtons(_armRightClick ? 3 : 1, true);
                        if (action == MotionEvent.ACTION_UP && event.getActionIndex() == 0) {
                            updatePointerButtons(_armRightClick ? 3 : 1, false);
                            _armRightClick = false;
                        }
                        // Two-finger tap = right-click. Drop the first finger's left
                        // press first so Smalltalk gets a clean button-3 click (this is
                        // why the old two-finger gesture was unreliable).
                        if (action == MotionEvent.ACTION_POINTER_DOWN && event.getActionIndex() == 1) {
                            updatePointerButtons(1, false);
                            updatePointerButtons(3, true);
                            updatePointerButtons(3, false);
                        }
                    }
                }

                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    _totalMove = 0;
                    _xPrec = event.getX();
                    _yPrec = event.getY();
                } else if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                    final double dx = event.getX() - _xPrec;
                    final double dy = event.getY() - _yPrec;
                    final double dl = Math.sqrt(dx * dx + dy * dy);
                    _totalMove += dl;
                    _xPrec = event.getX();
                    _yPrec = event.getY();
                }

                if (_totalMove < 20) { // -- workaround for phones with cheap touchscreens (which will constantly
                    // trigger ACTION_MOVE events)
                    _ignoreLongPress = false;
                    return false; // make longClick Listeners work!
                }

                _ignoreLongPress = true;
                return false;
            }

        });

        setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                if (!_enableLongPressMenu)
                    return false;   // let Smalltalk see the press-and-hold instead
                if (_ignoreLongPress)
                    return true;

                ActionMode.Callback cb = new ActionMode.Callback() {
                    @Override
                    public boolean onCreateActionMode(ActionMode mode, Menu menu) {
                        menu.add(0, ACTION_CTRL_C, 0, "CTRL+C");
                        menu.add(0, ACTION_CTRL_V, 0, "CTRL+V");
                        menu.add(0, ACTION_CTRL_X, 0, "CTRL+X");
                        menu.add(0, ACTION_CTRL_A, 0, "CTRL+A");
                        menu.add(0, ACTION_ESC, 0, "ESC");
                        menu.add(0, ACTION_R_CLICK, 0, "M-Click");
                        menu.add(0, ACTION_R_CLICK, 0, "R-Click");
                        menu.add(0, ACTION_KEYBOARD, 0, "Keyboard");
                        menu.add(0, ACTION_CANCEL, 0, "Cancel");
                        return true;
                    }

                    @Override
                    public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
                        return false; // Return false if nothing is done
                    }

                    @Override
                    public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
                        switch (item.getItemId()) {
                            case ACTION_CTRL_C:
                                onKeyDown(KeyEvent.KEYCODE_CTRL_LEFT,
                                        new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CTRL_LEFT));
                                onKeyDown(KeyEvent.KEYCODE_C, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_C));
                                onKeyUp(KeyEvent.KEYCODE_C, new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_C));
                                onKeyUp(KeyEvent.KEYCODE_CTRL_LEFT,
                                        new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_CTRL_LEFT));
                                mode.finish();
                                return true;
                            case ACTION_CTRL_V:
                                if (_sharedClipboard) {
                                    Selection.setSelectionOwner(_xServer, _xServer.findAtom("CLIPBOARD"),
                                            _sharedClipboardWindow); // override owner to point to our clipboardwindow
                                    Selection.setSelectionOwner(_xServer, _xServer.findAtom("PRIMARY"),
                                            _sharedClipboardWindow);
                                    ClipboardManager clipboard = (ClipboardManager) _xServer.getContext()
                                            .getSystemService(Context.CLIPBOARD_SERVICE);
                                    ClipData.Item clipitem = clipboard.getPrimaryClip().getItemAt(0);

                                    _sharedClipboardProperty.setData(clipitem.getText().toString());
                                    _sharedClipboardPrimaryProperty.setData(clipitem.getText().toString());
                                }

                                onKeyDown(KeyEvent.KEYCODE_CTRL_LEFT,
                                        new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CTRL_LEFT));
                                onKeyDown(KeyEvent.KEYCODE_V, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_V));
                                onKeyUp(KeyEvent.KEYCODE_V, new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_V));
                                onKeyUp(KeyEvent.KEYCODE_CTRL_LEFT,
                                        new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_CTRL_LEFT));
                                mode.finish();
                                return true;
                            case ACTION_CTRL_X:
                                onKeyDown(KeyEvent.KEYCODE_CTRL_LEFT,
                                        new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CTRL_LEFT));
                                onKeyDown(KeyEvent.KEYCODE_X, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_X));
                                onKeyUp(KeyEvent.KEYCODE_X, new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_X));
                                onKeyUp(KeyEvent.KEYCODE_CTRL_LEFT,
                                        new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_CTRL_LEFT));
                                mode.finish();
                                return true;
                            case ACTION_CTRL_A:
                                onKeyDown(KeyEvent.KEYCODE_CTRL_LEFT,
                                        new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CTRL_LEFT));
                                onKeyDown(KeyEvent.KEYCODE_A, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A));
                                onKeyUp(KeyEvent.KEYCODE_A, new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_A));
                                onKeyUp(KeyEvent.KEYCODE_CTRL_LEFT,
                                        new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_CTRL_LEFT));
                                mode.finish();
                                return true;
                            case ACTION_R_CLICK:
                                if (_sharedClipboard) {
                                    Selection.setSelectionOwner(_xServer, _xServer.findAtom("CLIPBOARD"),
                                            _sharedClipboardWindow); // override owner to point to our clipboardwindow
                                    Selection.setSelectionOwner(_xServer, _xServer.findAtom("PRIMARY"),
                                            _sharedClipboardWindow);
                                    ClipboardManager clipboard = (ClipboardManager) _xServer.getContext()
                                            .getSystemService(Context.CLIPBOARD_SERVICE);
                                    ClipData.Item clipitem = clipboard.getPrimaryClip().getItemAt(0);

                                    _sharedClipboardProperty.setData(clipitem.getText().toString());
                                    _sharedClipboardPrimaryProperty.setData(clipitem.getText().toString());
                                }
                                updatePointerButtons(3, true);
                                updatePointerButtons(3, false);
                                mode.finish();
                                return true;
                            case ACTION_M_CLICK:
                                if (_sharedClipboard) {
                                    Selection.setSelectionOwner(_xServer, _xServer.findAtom("CLIPBOARD"),
                                            _sharedClipboardWindow); // override owner to point to our clipboardwindow
                                    Selection.setSelectionOwner(_xServer, _xServer.findAtom("PRIMARY"),
                                            _sharedClipboardWindow);
                                    ClipboardManager clipboard = (ClipboardManager) _xServer.getContext()
                                            .getSystemService(Context.CLIPBOARD_SERVICE);
                                    ClipData.Item clipitem = clipboard.getPrimaryClip().getItemAt(0);

                                    _sharedClipboardProperty.setData(clipitem.getText().toString());
                                    _sharedClipboardPrimaryProperty.setData(clipitem.getText().toString());
                                }
                                updatePointerButtons(2, true);
                                updatePointerButtons(2, false);
                                mode.finish();
                                return true;
                            case ACTION_ESC:
                                onKeyDown(KeyEvent.KEYCODE_ESCAPE,
                                        new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE));
                                onKeyUp(KeyEvent.KEYCODE_ESCAPE,
                                        new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ESCAPE));
                                mode.finish();
                                return true;
                            case ACTION_KEYBOARD:
                                InputMethodManager imm = (InputMethodManager) _xServer.getContext()
                                        .getSystemService(Service.INPUT_METHOD_SERVICE);
                                requestFocus();
                                imm.hideSoftInputFromWindow(getWindowToken(), 0);
                                imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0);
                                mode.finish();
                                return true;
                            case ACTION_CANCEL:
                                mode.finish();
                                return true;
                            default:
                                mode.finish();
                                return false;
                        }
                    }

                    // Called when the user exits the action mode
                    @Override
                    public void onDestroyActionMode(ActionMode mode) {
                        mode = null;
                    }
                };

                // use floating mode on newer android versions
                if (Build.VERSION.SDK_INT >= 23) {
                    startActionMode(cb, ActionMode.TYPE_FLOATING);
                } else {
                    startActionMode(cb);
                }

                return false;
            }
        });
        requestFocus();
    }

    /**
     * needed make softkeyboard work in landscape mode and to capture backspace.
     */
    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        outAttrs.inputType = InputType.TYPE_TEXT_VARIATION_NORMAL;
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE | EditorInfo.IME_FLAG_NO_FULLSCREEN;
        return null;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        DisplayMetrics displayMetrics = new DisplayMetrics();
        WindowManager wm = (WindowManager) getContext().getSystemService(Context.WINDOW_SERVICE);
        wm.getDefaultDisplay().getMetrics(displayMetrics);
        int deviceWidth = displayMetrics.widthPixels;
        int deviceHeight = displayMetrics.heightPixels;
        initializeXserver(deviceWidth, deviceHeight);

    }

    /**
     * Placeholder constructor to prevent a compiler warning.
     *
     * @param c
     */
    public ScreenView(Context c) {
        super(c);

        _xServer = null;
        _rootId = 0;
        _installedColormaps = null;
        _pixelsPerMillimeter = 0;
    }
    /*
     * @Override
     * public WindowInsets onApplyWindowInsets(WindowInsets insets) {
     * int bottomInset = insets.getSystemWindowInsetBottom(); // This is the
     * navigation bar height
     *
     * ViewGroup.LayoutParams layoutParams = getLayoutParams();
     * layoutParams.height = getHeight() - bottomInset;
     * setLayoutParams(layoutParams);
     *
     * return insets;
     * }
     */

    /**
     * Return the screen's root window.
     *
     * @return The screen's root window.
     */
    public Window getRootWindow() {
        return _rootWindow;
    }

    /**
     * Return the screen's default colormap.
     *
     * @return The screen's default colormap.
     */
    public Colormap getDefaultColormap() {
        return _defaultColormap;
    }

    /**
     * Return the current cursor.
     *
     * @return The current cursor.
     */
    public Cursor getCurrentCursor() {
        return _currentCursor;
    }

    /**
     * Return the current pointer X coordinate.
     *
     * @return The current pointer X coordinate.
     */
    public int getPointerX() {
        return _currentCursorX;
    }

    /**
     * Return the current pointer Y coordinate.
     *
     * @return The current pointer Y coordinate.
     */
    public int getPointerY() {
        return _currentCursorY;
    }

    /**
     * Return a mask indicating the current state of the pointer and
     * modifier buttons.
     *
     * @return A mask indicating the current state of the buttons.
     */
    public int getButtons() {
        return _buttons;
    }

    /**
     * Return the window that has input focus. Can be null.
     *
     * @return The window that has input focus.
     */
    public Window getFocusWindow() {
        return _focusWindow;
    }

    /**
     * Blank/unblank the screen.
     *
     * @param flag If true, blank the screen. Otherwise unblank it.
     */
    public void blank(boolean flag) {
        if (_isBlanked == flag)
            return;

        _isBlanked = flag;
        postInvalidate();

        if (!_isBlanked)
            _xServer.resetScreenSaver();
    }

    /**
     * Add an installed colormap.
     *
     * @param cmap The colormap to add.
     */
    public void addInstalledColormap(Colormap cmap) {
        _installedColormaps.add(cmap);
        if (_defaultColormap == null)
            _defaultColormap = cmap;
    }

    /**
     * Remove an installed colormap.
     *
     * @param cmap The colormap to remove.
     */
    public void removeInstalledColormap(Colormap cmap) {
        _installedColormaps.remove(cmap);
        if (_defaultColormap == cmap) {
            if (_installedColormaps.size() == 0)
                _defaultColormap = null;
            else
                _defaultColormap = _installedColormaps.firstElement();
        }
    }

    /**
     * Remove all colormaps except the default one.
     */
    public void removeNonDefaultColormaps() {
        if (_installedColormaps.size() < 2)
            return;

        _installedColormaps.clear();
        if (_defaultColormap != null)
            _installedColormaps.add(_defaultColormap);
    }

    /**
     * Called when a window is deleted, usually due to a client disconnecting.
     * Removes all references to the window.
     *
     * @param w The window being deleted.
     */
    public void deleteWindow(Window w) {
        if (_grabPointerWindow == w || _grabConfineWindow == w) {
            _grabPointerClient = null;
            _grabPointerWindow = null;
            _grabCursor = null;
            _grabConfineWindow = null;
            updatePointer(2);
        } else {
            updatePointer(0);
        }

        revertFocus(w);
    }

    private void sleepThread(int milliseconds) {
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    /**
     * Called when the window is unmapped.
     * If the window had keyboard focus, update the focus window.
     *
     * @param w
     */
    public void revertFocus(Window w) {
        if (w == _grabKeyboardWindow) {
            Window pw = _rootWindow.windowAtPoint(_motionX, _motionY);

            Window.focusInOutNotify(_grabKeyboardWindow, _focusWindow, pw, _rootWindow, 2);
            _grabKeyboardClient = null;
            _grabKeyboardWindow = null;
        }

        if (w == _focusWindow) {
            Window pw = _rootWindow.windowAtPoint(_motionX, _motionY);

            if (_focusRevertTo == 0) {
                _focusWindow = null;
            } else if (_focusRevertTo == 1) {
                _focusWindow = _rootWindow;
            } else {
                _focusWindow = w.getParent();
                while (!_focusWindow.isViewable())
                    _focusWindow = _focusWindow.getParent();
            }

            _focusRevertTo = 0;
            Window.focusInOutNotify(w, _focusWindow, pw, _rootWindow, _grabKeyboardWindow == null ? 0 : 3);
        }
    }

    /**
     * Called when the view needs drawing.
     *
     * @param canvas The canvas on which the view will be drawn.
     */
    @Override
    protected void onDraw(Canvas canvas) {
        if (_rootWindow == null) {
            super.onDraw(canvas);
            return;
        }

        synchronized (_xServer) {
            if (_isBlanked) {
                canvas.drawColor(0xff000000);
                return;
            }

            _paint.reset();
            final boolean zoom = _displayScale != 1.0f;
            if (zoom) {
                canvas.save();
                canvas.scale(_displayScale, _displayScale);
                // Smooth (bilinear) upscale for image-heavy content; nearest-neighbour
                // (crisp, default) for text. Only matters when zoomed.
                _paint.setFilterBitmap(_smoothZoom);
            }
            _rootWindow.draw(canvas, _paint);
            // cursor is in logical (X) coords; drawn inside the scaled canvas it
            // lands under the finger at the right physical spot.
            canvas.drawBitmap(_currentCursor.getBitmap(), _currentCursorX - _currentCursor.getHotspotX(),
                    _currentCursorY - _currentCursor.getHotspotY(), null);
            // Always-visible pointer: touch has no persistent hover, and Smalltalk
            // often hides the X cursor (drawing its own only while moving), so the
            // pointer would vanish. Draw a clear arrow at the last pointer position
            // so you always see where the "mouse" is — and it stays put on lift.
            if (_showPointer)
                drawPointerMarker(canvas, _currentCursorX, _currentCursorY);
            if (zoom) canvas.restore();

            _drawnCursor = _currentCursor;
            _drawnCursorX = _currentCursorX;
            _drawnCursorY = _currentCursorY;
        }
    }

    /**
     * Called when the size changes.
     * Create the root window.
     *
     * @param width     The new width.
     * @param height    The new height.
     * @param oldWidth  The old width.
     * @param oldHeight The old height.
     */
 @Override
protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
    super.onSizeChanged(width, height, oldWidth, oldHeight);
    
    Log.i("ScreenView", "onSizeChanged: " + oldWidth + "x" + oldHeight + " -> " + width + "x" + height);
    
    // X-screen size is the physical view size divided by the display zoom.
    int lw = Math.max(1, Math.round(width / _displayScale));
    int lh = Math.max(1, Math.round(height / _displayScale));
    if (!_xServer.isStarted()) {
        initializeXserver(lw, lh);
    } else if (_rootWindow != null) {
        // El servidor ya está iniciado, necesitamos redimensionar
        Log.i("ScreenView", "Redimensionando root window y notificando clientes");

        synchronized (_xServer) {
            // Redimensionar la ventana root
            _rootWindow.resize(lw, lh);

            // Notificar a todos los clientes del cambio de tamaño
            notifyClientsScreenResize(lw, lh);
        }

        // Forzar redibujado
        postInvalidate();
    }

    // Backlog #1: the world otherwise renders at its saved (smaller) size until
    // the first device rotation. Once a client has mapped its top-level window,
    // apply the same resize a rotation would — once — so it's fullscreen from start.
    // (Tested NOT to be what breaks Cuis >=7983 — those images stall with the
    // resize disabled too; their reworked startup never launches the UI process
    // on this VM. See CLAUDE.md "Cuis master blank world".)
    ensureInitialFullscreen();
}

    protected void initializeXserver(int width, int height) {

        /*
         * DisplayMetrics displayMetrics = new DisplayMetrics();
         * getWindowManager().getDefaultDisplay().getMetrics(displayMetrics);
         * int width = displayMetrics.heightPixels;
         * int height = displayMetrics.widthPixels;
         */

        _rootWindow = new Window(_rootId, _xServer, null, this, null, 0, 0, width, height, 0, false, true);
        _sharedClipboardWindow = new Window(_xServer.nextFreeResourceId() + 1, _xServer, null, this, _rootWindow, -1,
                -1, 1, 1, 0, true, false); // hidden window managing android <-> xServer clipboard
        _sharedClipboardWindow.setIsServerWindow(true); // flag as functional server only window (there is a urgent need
        // to introduce interfaces..)
        _sharedClipboardProperty = new Property(_xServer.findAtom("CLIPBOARD").getId(),
                _xServer.findAtom("CLIPBOARD").getId(), (byte) 32); // property which will hold the clipboard data
        _sharedClipboardPrimaryProperty = new Property(_xServer.findAtom("PRIMARY").getId(),
                _xServer.findAtom("PRIMARY").getId(), (byte) 32); // property which will hold the clipboard data

        Property.OnPropertyChangedListener cb = new Property.OnPropertyChangedListener() { // -- executed on a per
            // client thread basis
            @Override
            public void onPropertyChanged(byte[] data, Atom type) {
                switch (type.getName()) {
                    case "UTF8_STRING":
                        String s = new String(data, StandardCharsets.UTF_8); // convert to UTF8 string

                        // create task for UI thread
                        class OneShotTask implements Runnable {
                            private String d;

                            OneShotTask(String s) {
                                d = s;
                            }

                            public void run() {
                                ClipboardManager clipboard = (ClipboardManager) _xServer.getContext()
                                        .getSystemService(Context.CLIPBOARD_SERVICE);
                                ClipData clip = ClipData.newPlainText("cb", d);
                                clipboard.setPrimaryClip(clip); // store to clipboard
                            }
                        }
                        new Handler(Looper.getMainLooper()).post(new OneShotTask(s));
                        break;
                    default: // different types can be implemented here (binary etc.)
                        break;
                }
            }
        };

        // capture data changes on this property
        _sharedClipboardProperty.setOnPropertyChangedListener(cb);
        _sharedClipboardPrimaryProperty.setOnPropertyChangedListener(cb);

        _sharedClipboardWindow.addProperty(_sharedClipboardPrimaryProperty);
        _sharedClipboardWindow.addProperty(_sharedClipboardProperty);

        _xServer.addResource(_rootWindow);
        _xServer.addResource(_sharedClipboardWindow);

        _currentCursor = _rootWindow.getCursor();
        _currentCursorX = width / 2;
        _currentCursorY = height / 2;
        _drawnCursorX = _currentCursorX;
        _drawnCursorY = _currentCursorY;
        _motionX = _currentCursorX;
        _motionY = _currentCursorY;
        _motionWindow = _rootWindow;
        _focusWindow = _rootWindow;

        // Everything set up, so start listening for clients.
        _xServer.start();
    }

    /**
     * Move the pointer on the screen.
     *
     * @param x      New X coordinate.
     * @param y      New Y coordinate.
     * @param cursor The cursor to draw.
     */
    private boolean _showPointer = true;
    private Path _ptrPath = null;
    private Paint _ptrFill = null, _ptrStroke = null;

    /** Draw a classic arrow pointer (tip at x,y), always visible, over the world. */
    private void drawPointerMarker(Canvas canvas, int x, int y) {
        if (_ptrPath == null) {
            // ~11x18 arrow with the tip at (0,0).
            _ptrPath = new Path();
            _ptrPath.moveTo(0, 0);
            _ptrPath.lineTo(0, 17);
            _ptrPath.lineTo(4, 13);
            _ptrPath.lineTo(7, 20);
            _ptrPath.lineTo(9, 19);
            _ptrPath.lineTo(6, 12);
            _ptrPath.lineTo(11, 12);
            _ptrPath.close();
            _ptrFill = new Paint(Paint.ANTI_ALIAS_FLAG);
            _ptrFill.setStyle(Paint.Style.FILL);
            _ptrFill.setColor(0xFF000000);
            _ptrStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
            _ptrStroke.setStyle(Paint.Style.STROKE);
            _ptrStroke.setStrokeWidth(1.5f);
            _ptrStroke.setColor(0xFFFFFFFF);
        }
        canvas.save();
        canvas.translate(x, y);
        canvas.drawPath(_ptrPath, _ptrFill);
        canvas.drawPath(_ptrPath, _ptrStroke);
        canvas.restore();
    }

    /**
     * Toggle the always-visible mouse pointer overlay.
     *
     * @return new state of switch
     */
    public boolean toggleShowPointer() {
        _showPointer = !_showPointer;
        postInvalidate();
        return _showPointer;
    }

    // State getters (used to label the curated options menu).
    public boolean isShowPointer() { return _showPointer; }
    public boolean isLongPressMenuEnabled() { return _enableLongPressMenu; }
    public boolean isSharedClipboard() { return _sharedClipboard; }
    public boolean isTrackpadMode() { return _trackpadMode; }
    public boolean isPreciseTouch() { return _touchOffsetY != 0; }
    public boolean isSmoothZoom() { return _smoothZoom; }

    /** Arm the next tap to be a right-click (button 3). */
    public void armRightClick() { _armRightClick = true; }

    /** Toggle bilinear (smooth) vs nearest-neighbour (crisp) zoom upscaling. */
    public boolean toggleSmoothZoom() {
        _smoothZoom = !_smoothZoom;
        postInvalidate();
        return _smoothZoom;
    }

    /** Toggle trackpad mode (relative cursor). */
    public boolean toggleTrackpadMode() {
        _trackpadMode = !_trackpadMode;
        _tpDragging = false; _tpMoved = false;
        if (_tpLongPress != null) _tpHandler.removeCallbacks(_tpLongPress);
        return _trackpadMode;
    }

    /** Toggle the precise-pointer offset (direct-touch mode). */
    public boolean togglePreciseTouch() {
        _touchOffsetY = (_touchOffsetY == 0)
                ? Math.round(48 * getResources().getDisplayMetrics().density) : 0;
        return _touchOffsetY != 0;
    }

    /**
     * Trackpad-style touch: the finger drives a RELATIVE cursor (like a laptop
     * trackpad), so you can position precisely and hover (which opens Cuis
     * submenus) without your finger occluding the target.
     *   • slide            → move the cursor (hover, no button)
     *   • quick tap        → left-click at the cursor
     *   • hold ~300ms then drag → button-1 drag (move windows, select text)
     *   • second finger    → right-click at the cursor
     */
    private void handleTrackpadTouch(MotionEvent event) {
        if (_touchSlop == 0)
            _touchSlop = android.view.ViewConfiguration.get(getContext()).getScaledTouchSlop();
        final float scale = _displayScale;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                _tpDownX = _tpLastX = event.getX();
                _tpDownY = _tpLastY = event.getY();
                _tpMoved = false;
                _tpDragging = false;
                if (_tpLongPress != null) _tpHandler.removeCallbacks(_tpLongPress);
                _tpLongPress = () -> {
                    synchronized (_xServer) {
                        if (!_tpMoved && !_tpDragging && _rootWindow != null) {
                            _tpDragging = true;           // held still → start a button-1 drag
                            updatePointerButtons(1, true);
                        }
                    }
                };
                _tpHandler.postDelayed(_tpLongPress, 350);
                break;
            case MotionEvent.ACTION_MOVE: {
                float dx = event.getX() - _tpLastX;
                float dy = event.getY() - _tpLastY;
                _tpLastX = event.getX();
                _tpLastY = event.getY();
                int lw = Math.max(1, Math.round(getWidth() / scale));
                int lh = Math.max(1, Math.round(getHeight() / scale));
                int nx = Math.max(0, Math.min(lw - 1, _currentCursorX + Math.round(dx / scale)));
                int ny = Math.max(0, Math.min(lh - 1, _currentCursorY + Math.round(dy / scale)));
                updatePointerPosition(nx, ny, 0);
                double dist = Math.hypot(event.getX() - _tpDownX, event.getY() - _tpDownY);
                // Any real movement ⇒ it's a slide, not a still hold: cancel the
                // pending hold-to-drag with a SMALL threshold so even a slow, precise
                // slide never accidentally starts a drag (dragging = press + pause).
                if (!_tpDragging && _tpLongPress != null
                        && dist > 10 * getResources().getDisplayMetrics().density) {
                    _tpHandler.removeCallbacks(_tpLongPress);
                    _tpLongPress = null;
                }
                if (dist > _touchSlop) _tpMoved = true;    // for the tap-vs-slide click decision
                break;
            }
            case MotionEvent.ACTION_POINTER_DOWN:
                if (_tpLongPress != null) _tpHandler.removeCallbacks(_tpLongPress);
                if (_tpDragging) { updatePointerButtons(1, false); _tpDragging = false; }
                updatePointerButtons(3, true);            // second finger → right-click
                updatePointerButtons(3, false);
                _tpMoved = true;                          // suppress the click on lift
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (_tpLongPress != null) _tpHandler.removeCallbacks(_tpLongPress);
                if (_tpDragging) {
                    updatePointerButtons(1, false);       // end the drag
                    _tpDragging = false;
                } else if (!_tpMoved) {
                    updatePointerButtons(1, true);        // quick tap → click at the cursor
                    updatePointerButtons(1, false);
                }
                break;
        }
    }

    private void movePointer(int x, int y, Cursor cursor) {
        _drawnCursor = null;
        _currentCursor = cursor;
        _currentCursorX = x;
        _currentCursorY = y;
        // Full invalidate: the old partial (cursor-bitmap-sized) region was in
        // logical coords and ignored the display zoom, and it can't cover the
        // always-visible pointer marker — a partial redraw would leave arrow
        // trails. The world blit is cheap, so just repaint the view.
        postInvalidate();
    }

    /**
     * Update the location of the pointer.
     *
     * @param x    New X coordinate.
     * @param y    New Y coordinate.
     * @param mode 0=Normal, 1=Grab, 2=Ungrab
     */
    public void updatePointerPosition(int x, int y, int mode) {
        Window w;
        Cursor c;

        if (_grabConfineWindow != null) {
            Rect rect = _grabConfineWindow.getIRect();

            if (x < rect.left)
                x = rect.left;
            else if (x >= rect.right)
                x = rect.right - 1;

            if (y < rect.top)
                y = rect.top;
            else if (y >= rect.bottom)
                y = rect.bottom - 1;
        }

        if (_grabPointerWindow != null)
            w = _grabPointerWindow;
        else
            w = _rootWindow.windowAtPoint(x, y);

        if (_grabCursor != null)
            c = _grabCursor;
        else
            c = w.getCursor();

        if (c != _currentCursor || x != _currentCursorX || y != _currentCursorY)
            movePointer(x, y, c);

        if (w != _motionWindow) {
            _motionWindow.leaveEnterNotify(x, y, w, mode);
            _motionWindow = w;
            _motionX = x;
            _motionY = y;
        } else if (x != _motionX || y != _motionY) {
            if (_grabPointerWindow == null) {
                w.motionNotify(x, y, _buttons & 0xff00, null);
            } else if (!_grabPointerSynchronous) {
                callGrabMotionNotify(w, x, y, _buttons, _grabEventMask, _grabPointerClient, _grabPointerOwnerEvents);
            } else {
                PendingPointerEvent e;
                e = new PendingGrabMotionNotify(w, x, y, _buttons, _grabEventMask, _grabPointerClient,
                        _grabPointerOwnerEvents);
                mPendingPointerEvents.add(e);
            }

            _motionX = x;
            _motionY = y;
        }
    }

    /**
     * Update the pointer in case its glyph has changed.
     *
     * @param mode 0=Normal, 1=Grab, 2=Ungrab
     */
    public void updatePointer(int mode) {
        updatePointerPosition(_currentCursorX, _currentCursorY, mode);
    }

    /**
     * Called when a pointer button is pressed/released.
     *
     * @param button  The button that was pressed/released.
     * @param pressed True if the button was pressed.
     */
    public void updatePointerButtons(int button, boolean pressed) {
        Pointer p = _xServer.getPointer();

        button = p.mapButton(button);
        if (button == 0)
            return;

        int mask = 0x80 << button;

        if (pressed) {
            if ((_buttons & mask) != 0)
                return;

            _buttons |= mask;
        } else {
            if ((_buttons & mask) == 0)
                return;

            _buttons &= ~mask;
        }

        if (_grabPointerWindow == null) {
            Window w = _rootWindow.windowAtPoint(_motionX, _motionY);
            PassiveButtonGrab pbg = null;

            if (pressed)
                pbg = w.findPassiveButtonGrab(_buttons, null);

            if (pbg != null) {
                _grabPointerClient = pbg.getGrabClient();
                _grabPointerWindow = pbg.getGrabWindow();
                _grabPointerPassive = true;
                _grabPointerAutomatic = false;
                _grabPointerTime = _xServer.getTimestamp();
                _grabConfineWindow = pbg.getConfineWindow();
                _grabEventMask = pbg.getEventMask();
                _grabPointerOwnerEvents = pbg.getOwnerEvents();
                _grabPointerSynchronous = pbg.getPointerSynchronous();
                _grabKeyboardSynchronous = pbg.getKeyboardSynchronous();

                _grabCursor = pbg.getCursor();
                if (_grabCursor == null)
                    _grabCursor = _grabPointerWindow.getCursor();

                updatePointer(1);
            } else {
                int timestamp = _xServer.getTimestamp();
                Window ew = w.buttonNotify(pressed, _motionX, _motionY, button, timestamp, null);
                reflectPointerFreezeNextEvent();
                Client c = null;

                if (pressed && ew != null) {
                    Vector<Client> sc;

                    sc = ew.getSelectingClients(EventCode.MaskButtonPress);
                    if (sc != null)
                        c = sc.firstElement();
                }

                // Start an automatic key grab.
                if (c != null) {
                    int em = ew.getClientEventMask(c);

                    _grabPointerClient = c;
                    _grabPointerWindow = ew;
                    _grabPointerPassive = false;
                    _grabPointerAutomatic = true;
                    _grabPointerTime = timestamp;
                    _grabCursor = ew.getCursor();
                    _grabConfineWindow = null;
                    _grabEventMask = em & EventCode.MaskAllPointer;
                    _grabPointerOwnerEvents = (em & EventCode.MaskOwnerGrabButton) != 0;
                    _grabPointerSynchronous = false;
                    _grabKeyboardSynchronous = false;
                    updatePointer(1);
                }
            }
        } else {
            if (!_grabPointerSynchronous) {
                callGrabButtonNotify(_grabPointerWindow, pressed, _motionX, _motionY, button, _grabEventMask,
                        _grabPointerClient, _grabPointerOwnerEvents);
            } else {
                PendingPointerEvent e;
                e = new PendingGrabButtonNotify(_grabPointerWindow, pressed, _motionX, _motionY, button, _grabEventMask,
                        _grabPointerClient, _grabPointerOwnerEvents);
                mPendingPointerEvents.add(e);
            }

            if (_grabPointerAutomatic && !pressed && (_buttons & 0xff00) == 0) {
                _grabPointerClient = null;
                _grabPointerWindow = null;
                _grabCursor = null;
                _grabConfineWindow = null;
                updatePointer(2);
            }
        }
    }

    /**
     * Updates keycodes for modifier keys (i.e. shift/alt).
     */
    private void updateModifiers() {
        int mask = 0;

        Keyboard kb = _xServer.getKeyboard();
        mask = kb.getState();

        _buttons = (_buttons & 0xff00) | mask;
    }

    /**
     * Called when a key is pressed or released.
     *
     * @param keycode Keycode of the key.
     * @param pressed True if pressed, false if released.
     */
    public void notifyKeyPressedReleased(int keycode, boolean pressed) {
        if (_grabKeyboardWindow == null && _focusWindow == null)
            return;

        Keyboard kb = _xServer.getKeyboard();

        keycode = kb.translateToXKeycode(keycode);

        if (pressed && _grabKeyboardWindow == null) {
            PassiveKeyGrab pkg = _focusWindow.findPassiveKeyGrab(keycode, _buttons & 0xff, null);

            if (pkg == null) {
                Window w = _rootWindow.windowAtPoint(_motionX, _motionY);

                if (w.isAncestor(_focusWindow))
                    pkg = w.findPassiveKeyGrab(keycode, _buttons & 0xff, null);
            }

            if (pkg != null) {
                _grabKeyboardPassiveGrab = pkg;
                _grabKeyboardClient = pkg.getGrabClient();
                _grabKeyboardWindow = pkg.getGrabWindow();
                _grabKeyboardTime = _xServer.getTimestamp();
                _grabKeyboardOwnerEvents = pkg.getOwnerEvents();
                _grabPointerSynchronous = pkg.getPointerSynchronous();
                _grabKeyboardSynchronous = pkg.getKeyboardSynchronous();
            }
        }

        if (_grabKeyboardWindow == null) {
            Window w = _rootWindow.windowAtPoint(_motionX, _motionY);

            if (w.isAncestor(_focusWindow))
                w.keyNotify(pressed, _motionX, _motionY, keycode, null);
            else
                _focusWindow.keyNotify(pressed, _motionX, _motionY, keycode, null);
            reflectKeyboardFreezeNextEvent();
        } else if (!_grabKeyboardSynchronous) {
            callGrabKeyNotify(_grabKeyboardWindow, pressed, _motionX, _motionY, keycode, _grabKeyboardClient,
                    _grabKeyboardOwnerEvents);
        } else {
            PendingKeyboardEvent e;
            e = new PendingGrabKeyNotify(_grabKeyboardWindow, pressed, _motionX, _motionY, keycode, _grabKeyboardClient,
                    _grabKeyboardOwnerEvents);
            mPendingKeyboardEvents.add(e);
        }

        kb.updateKeymap(keycode, pressed);

        if (!pressed && _grabKeyboardPassiveGrab != null) {
            int rk = _grabKeyboardPassiveGrab.getKey();

            if (rk == 0 || rk == keycode) {
                _grabKeyboardPassiveGrab = null;
                _grabKeyboardClient = null;
                _grabKeyboardWindow = null;
            }
        }
    }

    /**
     * Called when there is a key down event.
     *
     * @param keycode The value in event.getKeyCode().
     * @param event   The key event.
     * @return True if the event was handled.
     */
    @Override
    public boolean onKeyDown(int keycode, KeyEvent event) {
        synchronized (_xServer) {
            if (_rootWindow == null)
                return false;

            blank(false); // Reset the screen saver.

            boolean sendEvent = false;

            if (_arrowsAsButtons) {
                switch (keycode) {
                    case KeyEvent.KEYCODE_DPAD_LEFT:
                    case KeyEvent.KEYCODE_DPAD_CENTER:
                        updatePointerButtons(1, true);
                        return true;
                    case KeyEvent.KEYCODE_DPAD_UP:
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                        updatePointerButtons(2, true);
                        return true;
                    case KeyEvent.KEYCODE_DPAD_RIGHT:
                        updatePointerButtons(3, true);
                        return true;
                }
            }

            switch (keycode) {
                case KeyEvent.KEYCODE_BACK:
                    if (!_inhibitBackButton)
                        return false;
                    keycode = 128 - _xServer.getKeyboard().getMinimumKeycodeDiff(); // Special keycode since keycode
                    // value 5 is out of range
                    sendEvent = true;
                    break;
                case KeyEvent.KEYCODE_MENU:
                    return false;
                case KeyEvent.KEYCODE_VOLUME_UP:
                    updatePointerButtons(1, true);
                    break;
                case KeyEvent.KEYCODE_VOLUME_DOWN:
                    updatePointerButtons(3, true);
                    break;
                default:
                    sendEvent = true;
                    break;
            }

            updateModifiers();
            if (sendEvent)
                notifyKeyPressedReleased(keycode, true);
        }

        return true;
    }

    /**
     * Called when there is a key up event.
     *
     * @param keycode The value in event.getKeyCode().
     * @param event   The key event.
     * @return True if the event was handled.
     */
    @Override
    public boolean onKeyUp(int keycode, KeyEvent event) {
        synchronized (_xServer) {
            if (_rootWindow == null)
                return false;

            blank(false); // Reset the screen saver.

            boolean sendEvent = false;

            if (_arrowsAsButtons) {
                switch (keycode) {
                    case KeyEvent.KEYCODE_DPAD_LEFT:
                    case KeyEvent.KEYCODE_DPAD_CENTER:
                        updatePointerButtons(1, false);
                        return true;
                    case KeyEvent.KEYCODE_DPAD_UP:
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                        updatePointerButtons(2, false);
                        return true;
                    case KeyEvent.KEYCODE_DPAD_RIGHT:
                        updatePointerButtons(3, false);
                        return true;
                }
            }

            switch (keycode) {
                case KeyEvent.KEYCODE_BACK:
                    if (!_inhibitBackButton)
                        return false;
                    keycode = 128 - _xServer.getKeyboard().getMinimumKeycodeDiff(); // Special keycode since keycode
                    // value 5 is out of range
                    sendEvent = true;
                    break;
                case KeyEvent.KEYCODE_MENU:
                    return false;
                case KeyEvent.KEYCODE_VOLUME_UP:
                    updatePointerButtons(1, false);
                    break;
                case KeyEvent.KEYCODE_VOLUME_DOWN:
                    updatePointerButtons(3, false);
                    break;
                default:
                    sendEvent = true;
                    break;
            }

            updateModifiers();
            if (sendEvent)
                notifyKeyPressedReleased(keycode, false);
        }

        return true;
    }

    /**
     * Write details of the screen.
     *
     * @param io The input/output stream.
     * @throws IOException
     */
    public void write(InputOutput io) throws IOException {
        Visual vis = _xServer.getRootVisual();

        io.writeInt(_rootWindow.getId()); // Root window ID.
        io.writeInt(_defaultColormap.getId()); // Default colormap ID.
        io.writeInt(_defaultColormap.getWhitePixel()); // White pixel.
        io.writeInt(_defaultColormap.getBlackPixel()); // Black pixel.
        io.writeInt(0); // Current input masks.
        io.writeShort((short) logicalWidth()); // Width in pixels (logical / zoomed).
        io.writeShort((short) logicalHeight()); // Height in pixels (logical / zoomed).
        io.writeShort((short) (logicalWidth() / _pixelsPerMillimeter)); // Width in millimeters.
        io.writeShort((short) (logicalHeight() / _pixelsPerMillimeter)); // Height in millimeters.
        io.writeShort((short) 1); // Minimum installed maps.
        io.writeShort((short) 1); // Maximum installed maps.
        io.writeInt(vis.getId()); // Root visual ID.
        io.writeByte(vis.getBackingStoreInfo());
        io.writeByte((byte) (vis.getSaveUnder() ? 1 : 0));
        io.writeByte((byte) vis.getDepth()); // Root depth.
        io.writeByte((byte) 1); // Number of allowed depths.

        // Write the only allowed depth.
        io.writeByte((byte) vis.getDepth()); // Depth.
        io.writeByte((byte) 0); // Unused.
        io.writeShort((short) 1); // Number of visuals with this depth.
        io.writePadBytes(4); // Unused.
        vis.write(io); // The visual at this depth.
    }

    /**
     * Write the screen's installed colormaps.
     *
     * @param client The remote client.
     * @throws IOException
     */
    public void writeInstalledColormaps(Client client) throws IOException {
        InputOutput io = client.getInputOutput();
        int n = _installedColormaps.size();

        synchronized (io) {
            Util.writeReplyHeader(client, (byte) 0);
            io.writeInt(n); // Reply length.
            io.writeShort((short) n); // Number of colormaps.
            io.writePadBytes(22); // Unused.

            for (Colormap cmap : _installedColormaps)
                io.writeInt(cmap.getId());
        }
        io.flush();
    }

    /**
     * Process a screen-related request.
     *
     * @param xServer        The X server.
     * @param client         The remote client.
     * @param opcode         The request's opcode.
     * @param arg            Optional first argument.
     * @param bytesRemaining Bytes yet to be read in the request.
     * @throws IOException
     */
    public void processRequest(XServer xServer, Client client, byte opcode, byte arg, int bytesRemaining)
            throws IOException {
        InputOutput io = client.getInputOutput();

        switch (opcode) {
            case RequestCode.SendEvent:
                if (bytesRemaining != 40) {
                    io.readSkip(bytesRemaining);
                    ErrorCode.write(client, ErrorCode.Length, opcode, 0);
                } else {
                    processSendEventRequest(_xServer, client, arg == 1);
                }
                break;
            case RequestCode.GrabPointer:
                if (bytesRemaining != 20) {
                    io.readSkip(bytesRemaining);
                    ErrorCode.write(client, ErrorCode.Length, opcode, 0);
                } else {
                    processGrabPointerRequest(_xServer, client, arg == 1);
                }
                break;
            case RequestCode.UngrabPointer:
                if (bytesRemaining != 4) {
                    io.readSkip(bytesRemaining);
                    ErrorCode.write(client, ErrorCode.Length, opcode, 0);
                } else {
                    int time = io.readInt(); // Time.
                    int now = _xServer.getTimestamp();

                    if (time == 0)
                        time = now;

                    if (time >= _grabPointerTime && time <= now && _grabPointerClient == client) {
                        _grabPointerClient = null;
                        _grabPointerWindow = null;
                        _grabCursor = null;
                        _grabConfineWindow = null;
                        updatePointer(2);
                    }
                }
                break;
            case RequestCode.GrabButton:
                if (bytesRemaining != 20) {
                    io.readSkip(bytesRemaining);
                    ErrorCode.write(client, ErrorCode.Length, opcode, 0);
                } else {
                    processGrabButtonRequest(_xServer, client, arg == 1);
                }
                break;
            case RequestCode.UngrabButton:
                if (bytesRemaining != 8) {
                    io.readSkip(bytesRemaining);
                    ErrorCode.write(client, ErrorCode.Length, opcode, 0);
                } else {
                    int wid = io.readInt(); // Grab window.
                    int modifiers = io.readShort(); // Modifiers.
                    Resource r = _xServer.getResource(wid);

                    io.readSkip(2); // Unused.

                    if (r == null || r.getType() != Resource.WINDOW) {
                        ErrorCode.write(client, ErrorCode.Window, opcode, wid);
                    } else {
                        Window w = (Window) r;

                        w.removePassiveButtonGrab(arg, modifiers);
                    }
                }
                break;
            case RequestCode.ChangeActivePointerGrab:
                if (bytesRemaining != 12) {
                    io.readSkip(bytesRemaining);
                    ErrorCode.write(client, ErrorCode.Length, opcode, 0);
                } else {
                    int cid = io.readInt(); // Cursor.
                    int time = io.readInt(); // Time.
                    int mask = io.readShort(); // Event mask.
                    Cursor c = null;

                    io.readSkip(2); // Unused.

                    if (cid != 0) {
                        Resource r = _xServer.getResource(cid);

                        if (r == null || r.getType() != Resource.CURSOR)
                            ErrorCode.write(client, ErrorCode.Cursor, opcode, 0);
                        else
                            c = (Cursor) r;
                    }

                    int now = _xServer.getTimestamp();

                    if (time == 0)
                        time = now;

                    if (_grabPointerWindow != null && !_grabPointerPassive && _grabPointerClient == client
                            && time >= _grabPointerTime && time <= now && (cid == 0 || c != null)) {
                        _grabEventMask = mask;
                        if (c != null)
                            _grabCursor = c;
                        else
                            _grabCursor = _grabPointerWindow.getCursor();
                    }
                }
                break;
            case RequestCode.GrabKeyboard:
                if (bytesRemaining != 12) {
                    io.readSkip(bytesRemaining);
                    ErrorCode.write(client, ErrorCode.Length, opcode, 0);
                } else {
                    processGrabKeyboardRequest(_xServer, client, arg == 1);
                }
                break;
            case RequestCode.UngrabKeyboard:
                if (bytesRemaining != 4) {
                    io.readSkip(bytesRemaining);
                    ErrorCode.write(client, ErrorCode.Length, opcode, 0);
                } else {
                    int time = io.readInt(); // Time.
                    int now = _xServer.getTimestamp();

                    if (time == 0)
                        time = now;

                    if (time >= _grabKeyboardTime && time <= now) {
                        Window pw = _rootWindow.windowAtPoint(_motionX, _motionY);

                        Window.focusInOutNotify(_grabKeyboardWindow, _focusWindow, pw, _rootWindow, 2);
                        _grabKeyboardClient = null;
                        _grabKeyboardWindow = null;
                    }
                }
                break;
            case RequestCode.GrabKey:
                if (bytesRemaining != 12) {
                    io.readSkip(bytesRemaining);
                    ErrorCode.write(client, ErrorCode.Length, opcode, 0);
                } else {
                    processGrabKeyRequest(_xServer, client, arg == 1);
                }
                break;
            case RequestCode.UngrabKey:
                if (bytesRemaining != 8) {
                    io.readSkip(bytesRemaining);
                    ErrorCode.write(client, ErrorCode.Length, opcode, 0);
                } else {
                    int wid = io.readInt(); // Grab window.
                    int modifiers = io.readShort(); // Modifiers.
                    Resource r = _xServer.getResource(wid);

                    io.readSkip(2); // Unused.

                    if (r == null || r.getType() != Resource.WINDOW) {
                        ErrorCode.write(client, ErrorCode.Window, opcode, wid);
                    } else {
                        Window w = (Window) r;

                        w.removePassiveKeyGrab(arg, modifiers);
                    }
                }
                break;
            case RequestCode.AllowEvents:
                processAllowEvents(client, opcode, io, bytesRemaining, arg);
                break;
            case RequestCode.SetInputFocus:
                if (bytesRemaining != 8) {
                    io.readSkip(bytesRemaining);
                    ErrorCode.write(client, ErrorCode.Length, opcode, 0);
                } else {
                    processSetInputFocusRequest(_xServer, client, arg);
                }
                break;
            case RequestCode.GetInputFocus:
                if (bytesRemaining != 0) {
                    io.readSkip(bytesRemaining);
                    ErrorCode.write(client, ErrorCode.Length, opcode, 0);
                } else {
                    int wid;

                    if (_focusWindow == null)
                        wid = 0;
                    else if (_focusWindow == _rootWindow)
                        wid = 1;
                    else
                        wid = _focusWindow.getId();

                    synchronized (io) {
                        Util.writeReplyHeader(client, _focusRevertTo);
                        io.writeInt(0); // Reply length.
                        io.writeInt(wid); // Focus window.
                        io.writePadBytes(20); // Unused.
                    }
                    io.flush();
                }
                break;
        }
    }

    private void processAllowEvents(Client client, byte opcode, InputOutput io, int bytesRemaining, byte mode)
            throws IOException {
        if (bytesRemaining != 4) {
            io.readSkip(bytesRemaining);
            ErrorCode.write(client, ErrorCode.Length, opcode, 0);
            return;
        }

        int t = io.readInt();
        int now = _xServer.getTimestamp();
        int time = t == 0 ? now : t;
        if ((now < time) || (time < _grabPointerTime) || (time < _grabKeyboardTime)) {
            return;
        }

        String message;
        switch (mode) {
            case RequestCode.AllowEventsMode.AsyncPointer:
                flushPendingPointerEvents();
                _grabPointerSynchronous = false;
                _grabPointerFreezeNextEvent = false;
                break;
            case RequestCode.AllowEventsMode.SyncPointer:
                flushPendingPointerEvents();
                _grabPointerSynchronous = false;
                _grabPointerFreezeNextEvent = true;
                break;
            case RequestCode.AllowEventsMode.AsyncKeyboard:
                flushPendingKeyboardEvents();
                _grabKeyboardSynchronous = false;
                _grabKeyboardFreezeNextEvent = false;
                break;
            case RequestCode.AllowEventsMode.SyncKeyboard:
                flushPendingKeyboardEvents();
                _grabKeyboardSynchronous = false;
                _grabKeyboardFreezeNextEvent = true;
                break;
            case RequestCode.AllowEventsMode.AsyncBoth:
            case RequestCode.AllowEventsMode.SyncBoth:
            case RequestCode.AllowEventsMode.ReplayPointer:
            case RequestCode.AllowEventsMode.ReplayKeyboard:
                String fmt = "unsupported AllowEvents mode: %d (%s)";
                String name = RequestCode.AllowEventsMode.toString(mode);
                message = String.format(fmt, mode, name);
                reportError(client, ErrorCode.Implementation, opcode, message);
                break;
            default:
                message = String.format("unknown AllowEvents mode: %d", mode);
                reportError(client, ErrorCode.Value, opcode, message);
                break;
        }
    }

    private void reportError(Client client, byte error, byte opcode, String message) throws IOException {
        ErrorCode.write(client, error, opcode, 0);
    }

    /**
     * Toggle Arrows As Buttons.
     * <p>
     * Switch between key and button events for arrow keys
     *
     * @return new state of switch
     */
    public boolean toggleArrowsAsButtons() {
        _arrowsAsButtons = !_arrowsAsButtons;
        return _arrowsAsButtons;
    }

    /**
     * Toggle shared clipboard. Shared clipboard works when using the long press
     * action shortcuts.
     *
     * @return new state of switch
     */
    public boolean toggleSharedClipboard() {
        _sharedClipboard = !_sharedClipboard;
        return _sharedClipboard;
    }

    /**
     * Toggle Inhibit Back Button.
     *
     * @return new state of switch
     */
    public boolean toggleInhibitBackButton() {
        _inhibitBackButton = !_inhibitBackButton;
        return _inhibitBackButton;
    }

    /**
     * Toggle touchscreen mouse click emulation.
     *
     * @return new state of switch
     */
    public boolean toggleEnableTouchClicks() {
        _enableTouchClicks = !_enableTouchClicks;
        return _enableTouchClicks;
    }

    /**
     * Toggle the long-press ActionMode menu (CTRL+C/V/…, R-Click, Keyboard).
     *
     * @return new state of switch
     */
    public boolean toggleLongPressMenu() {
        _enableLongPressMenu = !_enableLongPressMenu;
        return _enableLongPressMenu;
    }

    /**
     * Process a SendEvent request.
     *
     * @param xServer   The X server.
     * @param client    The remote client.
     * @param propagate Propagate flag.
     * @throws IOException
     */
    private void processSendEventRequest(XServer xServer, Client client, boolean propagate) throws IOException {
        InputOutput io = client.getInputOutput();
        int wid = io.readInt(); // Destination window.
        int mask = io.readInt(); // Event mask.
        byte[] event = new byte[32];
        Window w;

        io.readBytes(event, 0, 32); // Event.

        if (wid == 0) { // Pointer window.
            w = _rootWindow.windowAtPoint(_motionX, _motionY);
        } else if (wid == 1) { // Input focus.
            if (_focusWindow == null) {
                ErrorCode.write(client, ErrorCode.Window, RequestCode.SendEvent, wid);
                return;
            }

            Window pw = _rootWindow.windowAtPoint(_motionX, _motionY);

            if (pw.isAncestor(_focusWindow))
                w = pw;
            else
                w = _focusWindow;
        } else {
            Resource r = _xServer.getResource(wid);

            if (r == null || r.getType() != Resource.WINDOW) {
                ErrorCode.write(client, ErrorCode.Window, RequestCode.SendEvent, wid);
                return;
            } else
                w = (Window) r;
        }

        // Diagnostic: the VM answers our synthetic XDND messages by SendEvent-ing
        // ClientMessages (XdndStatus/XdndFinished/XdndSqueakLaunchAck) back to the
        // clientless source window — log them so drop handshakes are observable.
        if (w != null && w.isServerWindow() && event[0] == EventCode.ClientMessage) {
            int typeAtom = ((event[8] & 0xff)) | ((event[9] & 0xff) << 8)
                    | ((event[10] & 0xff) << 16) | ((event[11] & 0xff) << 24);
            Atom ta = _xServer.getAtom(typeAtom);
            Log.i("ScreenView", "SendEvent to server window: ClientMessage "
                    + (ta != null ? ta.getName() : ("atom#" + typeAtom)));
        }

        Vector<Client> dc = null;

        if (mask == 0) {
            dc = new Vector<Client>();
            dc.add(w.getClient());
        } else if (!propagate) {
            dc = w.getSelectingClients(mask);
        } else {
            for (; ; ) {
                if ((dc = w.getSelectingClients(mask)) != null)
                    break;

                mask &= ~w.getDoNotPropagateMask();
                if (mask == 0)
                    break;

                w = w.getParent();
                if (w == null)
                    break;
                if (wid == 1 && w == _focusWindow)
                    break;
            }
        }

        if (dc == null)
            return;

        for (Client c : dc) {
            if (c == null)
                continue;
            InputOutput dio = c.getInputOutput();

            synchronized (dio) {
                dio.writeByte((byte) (event[0] | 128));

                if (event[0] == EventCode.KeymapNotify) {
                    dio.writeBytes(event, 1, 31);
                } else {
                    dio.writeByte(event[1]);
                    dio.writeShort((short) (c.getSequenceNumber() & 0xffff));
                    dio.writeBytes(event, 4, 28);
                }
            }
            dio.flush();
        }
    }

    /**
     * Process a GrabPointer request.
     *
     * @param xServer     The X server.
     * @param client      The remote client.
     * @param ownerEvents Owner-events flag.
     * @throws IOException
     */
    private void processGrabPointerRequest(XServer xServer, Client client, boolean ownerEvents) throws IOException {
        InputOutput io = client.getInputOutput();
        int wid = io.readInt(); // Grab window.
        int mask = io.readShort(); // Event mask.
        boolean psync = (io.readByte() == 0); // Pointer mode.
        boolean ksync = (io.readByte() == 0); // Keyboard mode.
        int cwid = io.readInt(); // Confine-to.
        int cid = io.readInt(); // Cursor.
        int time = io.readInt(); // Time.
        Resource r = _xServer.getResource(wid);

        if (r == null || r.getType() != Resource.WINDOW) {
            ErrorCode.write(client, ErrorCode.Window, RequestCode.GrabPointer, wid);
            return;
        }

        Window w = (Window) r;
        Cursor c = null;
        Window cw = null;

        if (cwid != 0) {
            r = _xServer.getResource(cwid);

            if (r == null || r.getType() != Resource.WINDOW) {
                ErrorCode.write(client, ErrorCode.Window, RequestCode.GrabPointer, cwid);
                return;
            }
            cw = (Window) r;
        }

        if (cid != 0) {
            r = _xServer.getResource(cid);
            if (r != null && r.getType() != Resource.CURSOR) {
                ErrorCode.write(client, ErrorCode.Cursor, RequestCode.GrabPointer, cid);
                return;
            }

            c = (Cursor) r;
        }

        if (c == null)
            c = w.getCursor();

        byte status = 0; // Success.
        int now = _xServer.getTimestamp();

        if (time == 0)
            time = now;

        if (time < _grabPointerTime || time > now) {
            status = 2; // Invalid time.
        } else if (_grabPointerWindow != null && _grabPointerClient != client) {
            status = 1; // Already grabbed.
        } else {
            _grabPointerClient = client;
            _grabPointerWindow = w;
            _grabPointerPassive = false;
            _grabPointerAutomatic = false;
            _grabPointerTime = time;
            _grabCursor = c;
            _grabConfineWindow = cw;
            _grabEventMask = mask;
            _grabPointerOwnerEvents = ownerEvents;
            _grabPointerSynchronous = psync;
            _grabKeyboardSynchronous = ksync;
        }

        synchronized (io) {
            Util.writeReplyHeader(client, status);
            io.writeInt(0); // Reply length.
            io.writePadBytes(24); // Unused.
        }
        io.flush();

        if (status == 0)
            updatePointer(1);
    }

    /**
     * Process a GrabButton request.
     *
     * @param xServer     The X server.
     * @param client      The remote client.
     * @param ownerEvents Owner-events flag.
     * @throws IOException
     */
    private void processGrabButtonRequest(XServer xServer, Client client, boolean ownerEvents) throws IOException {
        InputOutput io = client.getInputOutput();
        int wid = io.readInt(); // Grab window.
        int mask = io.readShort(); // Event mask.
        boolean psync = (io.readByte() == 0); // Pointer mode.
        boolean ksync = (io.readByte() == 0); // Keyboard mode.
        int cwid = io.readInt(); // Confine-to.
        int cid = io.readInt(); // Cursor.
        byte button = (byte) io.readByte(); // Button.
        int modifiers;
        Resource r = _xServer.getResource(wid);

        io.readSkip(1); // Unused.
        modifiers = io.readShort(); // Modifiers.

        if (r == null || r.getType() != Resource.WINDOW) {
            ErrorCode.write(client, ErrorCode.Window, RequestCode.GrabPointer, wid);
            return;
        }

        Window w = (Window) r;
        Cursor c = null;
        Window cw = null;

        if (cwid != 0) {
            r = _xServer.getResource(cwid);

            if (r == null || r.getType() != Resource.WINDOW) {
                ErrorCode.write(client, ErrorCode.Window, RequestCode.GrabPointer, cwid);
                return;
            }
            cw = (Window) r;
        }

        if (cid != 0) {
            r = _xServer.getResource(cid);

            if (r != null && r.getType() != Resource.CURSOR) {
                ErrorCode.write(client, ErrorCode.Cursor, RequestCode.GrabPointer, cid);
                return;
            }
            c = (Cursor) r;
        }

        w.addPassiveButtonGrab(
                new PassiveButtonGrab(client, w, button, modifiers, ownerEvents, mask, psync, ksync, cw, c));
    }

    /**
     * Process a GrabKeyboard request.
     *
     * @param xServer     The X server.
     * @param client      The remote client.
     * @param ownerEvents Owner-events flag.
     * @throws IOException
     */
    private void processGrabKeyboardRequest(XServer xServer, Client client, boolean ownerEvents) throws IOException {
        InputOutput io = client.getInputOutput();
        int wid = io.readInt(); // Grab window.
        int time = io.readInt(); // Time.
        boolean psync = (io.readByte() == 0); // Pointer mode.
        boolean ksync = (io.readByte() == 0); // Keyboard mode.
        Resource r = _xServer.getResource(wid);

        io.readSkip(2); // Unused.

        if (r == null || r.getType() != Resource.WINDOW) {
            ErrorCode.write(client, ErrorCode.Window, RequestCode.GrabKeyboard, wid);
            return;
        }

        Window w = (Window) r;
        byte status = 0; // Success.
        int now = _xServer.getTimestamp();

        if (time == 0)
            time = now;

        if (time < _grabKeyboardTime || time > now) {
            status = 2; // Invalid time.
        } else if ((_grabKeyboardWindow != null) && (_grabKeyboardClient != client)) {
            status = 1; // Already grabbed.
        } else {
            _grabKeyboardClient = client;
            _grabKeyboardWindow = w;
            _grabKeyboardTime = time;
            _grabKeyboardOwnerEvents = ownerEvents;
            _grabPointerSynchronous = psync;
            _grabKeyboardSynchronous = ksync;
        }

        synchronized (io) {
            Util.writeReplyHeader(client, status);
            io.writeInt(0); // Reply length.
            io.writePadBytes(24); // Unused.
        }
        io.flush();

        if (status == 0)
            Window.focusInOutNotify(_focusWindow, w, _rootWindow.windowAtPoint(_motionX, _motionY), _rootWindow, 1);
    }

    /**
     * Process a GrabKey request.
     *
     * @param xServer     The X server.
     * @param client      The remote client.
     * @param ownerEvents Owner-events flag.
     * @throws IOException
     */
    private void processGrabKeyRequest(XServer xServer, Client client, boolean ownerEvents) throws IOException {
        InputOutput io = client.getInputOutput();
        int wid = io.readInt(); // Grab window.
        int modifiers = io.readShort(); // Modifiers.
        byte keycode = (byte) io.readByte(); // Key.
        boolean psync = (io.readByte() == 0); // Pointer mode.
        boolean ksync = (io.readByte() == 0); // Keyboard mode.
        Resource r = _xServer.getResource(wid);

        io.readSkip(3); // Unused.

        if (r == null || r.getType() != Resource.WINDOW) {
            ErrorCode.write(client, ErrorCode.Window, RequestCode.GrabPointer, wid);
            return;
        }

        Window w = (Window) r;

        w.addPassiveKeyGrab(new PassiveKeyGrab(client, w, keycode, modifiers, ownerEvents, psync, ksync));
    }

    /**
     * Process a SetInputFocus request.
     *
     * @param xServer  The X server.
     * @param client   The remote client.
     * @param revertTo 0=None, 1=Root, 2=Parent.
     * @throws IOException
     */
    private void processSetInputFocusRequest(XServer xServer, Client client, byte revertTo) throws IOException {
        InputOutput io = client.getInputOutput();
        int wid = io.readInt(); // Focus window.
        int time = io.readInt(); // Time.
        Window w;

        if (wid == 0) {
            w = null;
            revertTo = 0;
        } else if (wid == 1) {
            w = _rootWindow;
            revertTo = 0;
        } else {
            Resource r = xServer.getResource(wid);

            if (r == null || r.getType() != Resource.WINDOW) {
                ErrorCode.write(client, ErrorCode.Window, RequestCode.GrabPointer, wid);
                return;
            }

            w = (Window) r;
        }

        int now = xServer.getTimestamp();

        if (time == 0)
            time = now;

        if (time < _focusLastChangeTime || time > now)
            return;

        Window.focusInOutNotify(_focusWindow, w, _rootWindow.windowAtPoint(_motionX, _motionY), _rootWindow,
                _grabKeyboardWindow == null ? 0 : 3);

        _focusWindow = w;
        _focusRevertTo = revertTo;
        _focusLastChangeTime = time;
    }

    private void reflectPointerFreezeNextEvent() {
        _grabPointerSynchronous = _grabPointerFreezeNextEvent;
    }

    private void reflectKeyboardFreezeNextEvent() {
        _grabKeyboardSynchronous = _grabKeyboardFreezeNextEvent;
    }

    private void callGrabButtonNotify(Window w, boolean pressed, int motionX, int motionY, int button,
                                      int grabEventMask, Client grabPointerClient, boolean grabPointerOwnerEvents) {
        w.grabButtonNotify(pressed, motionX, motionY, button, grabEventMask, grabPointerClient, grabPointerOwnerEvents);
        reflectPointerFreezeNextEvent();
    }

    private void callGrabMotionNotify(Window w, int x, int y, int buttons, int grabEventMask, Client grabPointerClient,
                                      boolean grabPointerOwnerEvents) {
        w.grabMotionNotify(x, y, buttons & 0xff00, grabEventMask, grabPointerClient, grabPointerOwnerEvents);
    }

    private void callGrabKeyNotify(Window w, boolean pressed, int motionX, int motionY, int keycode,
                                   Client grabKeyboardClient, boolean grabKeyboardOwnerEvents) {
        w.grabKeyNotify(pressed, motionX, motionY, keycode, grabKeyboardClient, grabKeyboardOwnerEvents);
        reflectKeyboardFreezeNextEvent();
    }

    private void flushPendingPointerEvents() {
        flushPendingEvents(mPendingPointerEvents);
    }

    private void flushPendingKeyboardEvents() {
        flushPendingEvents(mPendingKeyboardEvents);
    }

    private void flushPendingEvents(PendingEventQueue q) {
        PendingEvent e;
        while ((e = q.next()) != null) {
            e.run();
        }
    }

    /**
     * @return true if shared clipboard is enabled, false otherwise.
     */
    public boolean hasSharedClipboard() {
        return _sharedClipboard;
    }

    /**
     * @return Window used for shared clipboard.
     */
    public Window getSharedClipboardWindow() {
        return _sharedClipboardWindow;
    }

    /**
     * Sets the callback that will be invoked when a touch event is dispatched to this view.
     *
     * @param callback The callback to be invoked.
     */
    public void setOnTouchCallback(ScreenViewOnTouchCallback callback) {
        this.onTouchCallback = callback;
    }
    /**
 * Notifica a todos los clientes que la pantalla cambió de tamaño
 */
/**
 * Drop a file onto the client's top-level window via a synthesized XDND
 * handshake, exactly as a desktop drag-and-drop would: the client (the
 * Smalltalk VM's X window, which sets XdndAware) receives XdndEnter →
 * XdndPosition → XdndDrop from us, requests the XdndSelection contents
 * (answered by the existing server-window selection path in Selection.java
 * with a text/uri-list pointing at the file), and hands the image a
 * DropFiles event — the image then decides what to do with the file.
 *
 * Returns false when there is no XdndAware client window or the XDND atoms
 * aren't interned (VM built with -noxdnd) — callers should fall back to
 * another delivery mechanism.
 */
public boolean dropFile(final String path) {
    try {
        if (_xServer == null || !_xServer.isStarted() || _rootWindow == null) return false;

        final Atom aSelection = _xServer.findAtom("XdndSelection");
        final Atom aEnter = _xServer.findAtom("XdndEnter");
        final Atom aPosition = _xServer.findAtom("XdndPosition");
        final Atom aDrop = _xServer.findAtom("XdndDrop");
        final Atom aActionCopy = _xServer.findAtom("XdndActionCopy");
        final Atom aUriList = _xServer.findAtom("text/uri-list");
        final Atom aAware = _xServer.findAtom("XdndAware");
        if (aSelection == null || aEnter == null || aPosition == null || aDrop == null
                || aActionCopy == null || aUriList == null || aAware == null) {
            Log.w("ScreenView", "dropFile: XDND atoms not interned — client has no XDND support");
            return false;
        }

        // The drop target: a viewable client top-level that declared XdndAware.
        Window target = null;
        Vector<Window> children = _rootWindow.getChildren();
        if (children != null) {
            for (Window c : children) {
                if (c != null && c.isViewable() && c.getClient() != null
                        && c.getProperty(aAware.getId()) != null) {
                    target = c;
                    break;
                }
            }
        }
        if (target == null) {
            Log.w("ScreenView", "dropFile: no XdndAware client window");
            return false;
        }

        // The VM implements a Squeak-specific simplified drop for exactly this
        // case (sqUnixXdnd.c dndInLaunchDrop, "leaves out the 8 step dance"):
        // the absolute path goes in the XdndSqueakLaunchDrop property ON THE
        // SOURCE window (type XA_ATOM(!), format 8, trailing NUL included) and
        // one ClientMessage with data.l[0] = source window announces it. The VM
        // reads the property, records the image DropFiles event itself, and
        // acks with XdndSqueakLaunchAck (visible in our SendEvent log).
        final Atom aLaunchDrop = _xServer.findAtom("XdndSqueakLaunchDrop");
        if (aLaunchDrop == null) {
            Log.w("ScreenView", "dropFile: XdndSqueakLaunchDrop not interned");
            return false;
        }
        byte[] pathZ = (path + "\0").getBytes(StandardCharsets.UTF_8);
        Property prop = _sharedClipboardWindow.getProperty(aLaunchDrop.getId());
        if (prop == null) {
            prop = new Property(aLaunchDrop.getId(), 4 /* XA_ATOM */, (byte) 8);
            _sharedClipboardWindow.addProperty(prop);
        }
        prop.setData(pathZ);
        prop.setType(4 /* XA_ATOM — what the VM's XGetWindowProperty insists on */);

        // The image dispatches the drop AT THE POINTER POSITION (and rejects it
        // outright when that position is outside the world). Park the pointer
        // around the upper-middle of the screen first so the "Select action"
        // menu is always visible. Two moves: the first may only produce
        // Enter/Leave (window change), only the second — same window, different
        // coords — is guaranteed to emit the MotionNotify the VM tracks.
        try {
            int cx = logicalWidth() / 2, cy = logicalHeight() / 3;
            updatePointerPosition(cx, cy - 8, 0);
            updatePointerPosition(cx, cy, 0);
        } catch (Exception e) {
            Log.w("ScreenView", "dropFile: could not center pointer: " + e.getMessage());
        }

        final Client client = target.getClient();
        final Window w = target;
        // Give the pointer motion a beat to reach the VM before the drop.
        postDelayed(() -> {
            try {
                EventCode.sendClientMessage32(client, w, aLaunchDrop,
                        _sharedClipboardWindow.getId(), 0, 0, 0, 0);
                Log.i("ScreenView", "dropFile: XdndSqueakLaunchDrop sent for " + path);
            } catch (Exception e) {
                Log.e("ScreenView", "dropFile launch-drop send: " + e.getMessage(), e);
            }
        }, 250);
        return true;
    } catch (Exception e) {
        Log.e("ScreenView", "dropFile failed: " + e.getMessage(), e);
        return false;
    }
}

private void notifyClientsScreenResize(int width, int height) {
    if (_rootWindow == null) return;
    
    try {
        // Enviar ConfigureNotify a todas las ventanas top-level (hijas directas de root)
        Vector<Window> children = _rootWindow.getChildren();
        if (children != null) {
            for (Window child : children) {
                if (child != null && child.isViewable()) {
                    Log.i("ScreenView", "Enviando ConfigureNotify a ventana: " + child.getId());
                    
                    // Redimensionar la ventana al tamaño de la pantalla
                    child.resize(width, height);
                    
                    // Enviar evento ConfigureNotify
                    child.sendConfigureNotify();
                }
            }
        }
    } catch (Exception e) {
        Log.e("ScreenView", "Error notificando resize: " + e.getMessage(), e);
    }
}

/**
 * Once, at startup: poll until a client has mapped a viewable top-level window,
 * then (after a short settle) resize it to fill the screen — the same thing a
 * device rotation does via notifyClientsScreenResize. Fixes "fullscreen only
 * applies after rotating once". No-op if already applied.
 */
private void ensureInitialFullscreen() {
    if (_initialFullscreenApplied) return;
    scheduleInitialFullscreen(0);
}

private void scheduleInitialFullscreen(final int attempt) {
    if (_initialFullscreenApplied || attempt > 80) return;  // ~80 * 250ms = 20s ceiling
    postDelayed(new Runnable() {
        @Override
        public void run() {
            if (_initialFullscreenApplied) return;
            boolean hasClient = false;
            try {
                if (_xServer != null && _xServer.isStarted() && _rootWindow != null) {
                    Vector<Window> children = _rootWindow.getChildren();
                    if (children != null) {
                        for (Window child : children) {
                            if (child != null && child.isViewable()) { hasClient = true; break; }
                        }
                    }
                }
            } catch (Exception e) {
                Log.e("ScreenView", "scheduleInitialFullscreen check error: " + e.getMessage(), e);
            }
            if (hasClient) {
                _initialFullscreenApplied = true;
                // let the client finish its own startup layout, then force fullscreen once
                postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            synchronized (_xServer) {
                                notifyClientsScreenResize(logicalWidth(), logicalHeight());
                            }
                            Log.i("ScreenView", "Initial fullscreen applied (" + logicalWidth() + "x" + logicalHeight() + ")");
                        } catch (Exception e) {
                            Log.e("ScreenView", "Initial fullscreen apply error: " + e.getMessage(), e);
                        }
                    }
                }, 800);
            } else {
                scheduleInitialFullscreen(attempt + 1);
            }
        }
    }, 250);
}
}