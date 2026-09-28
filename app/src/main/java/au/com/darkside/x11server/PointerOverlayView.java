package au.com.darkside.x11server;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.view.MotionEvent;
import android.view.View;

/**
 * The mouse pointer, drawn ON TOP of the Smalltalk world.
 *
 * <p>It has to be a separate view. On the X path the arrow is painted inside
 * {@code ScreenView.onDraw}, but here the world is a {@link android.view.SurfaceView} whose
 * content is the buffer the VM writes into — the view's own {@code onDraw} paints into the
 * window layer, not into that buffer. So the arrow lives in a sibling view added to the same
 * FrameLayout, above the surface and below the floating pill, exactly as the pill itself
 * already renders over the world.
 *
 * <p>And it is not a nicety here: the native display driver stubs out the cursor primitives
 * (a touchscreen has no hardware cursor to move), so without this there is no pointer on
 * screen at all — which matters most in trackpad mode, where the whole point is a cursor
 * that does not sit under your finger.
 */
final class PointerOverlayView extends View {

    private final Path _path = new Path();
    private final Paint _fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint _stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float _uiScale;
    private final Rect _bounds = new Rect();

    private float _x = -1, _y = -1;        // physical px, arrow TIP; < 0 = no pointer yet
    private boolean _visible = true;

    PointerOverlayView(Context context) {
        super(context);

        // The same ~11x20 arrow the X path draws, tip at (0,0).
        _path.moveTo(0, 0);
        _path.lineTo(0, 17);
        _path.lineTo(4, 13);
        _path.lineTo(7, 20);
        _path.lineTo(9, 19);
        _path.lineTo(6, 12);
        _path.lineTo(11, 12);
        _path.close();

        _uiScale = getResources().getDisplayMetrics().density;
        _fill.setStyle(Paint.Style.FILL);
        _fill.setColor(0xFF000000);
        _stroke.setStyle(Paint.Style.STROKE);
        _stroke.setStrokeWidth(1.5f);        // scaled with the canvas, so it stays visible
        _stroke.setColor(0xFFFFFFFF);

        // Never take touch, focus or the IME: this view sits over the whole world, and the
        // surface below it must keep receiving every finger, every focus request and the
        // keyboard binding.
        setFocusable(false);
        setFocusableInTouchMode(false);
        setClickable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override public boolean dispatchTouchEvent(MotionEvent e) { return false; }

    @Override public boolean onTouchEvent(MotionEvent e) { return false; }

    /** Move the arrow. Physical pixels, safe from any thread. */
    void moveTo(float px, float py) {
        if (px == _x && py == _y) return;
        Rect dirty = new Rect();
        boundsInto(dirty);          // where it was
        _x = px;
        _y = py;
        boundsInto(_bounds);        // where it now is
        dirty.union(_bounds);
        // Per-vsync coalescing: a drag delivers ~120 events a second and this collapses
        // them to one redraw each frame. Also safe off the UI thread, which invalidate()
        // is not — dropFile() moves the pointer from wherever the caller happens to be.
        postInvalidateOnAnimation(dirty.left, dirty.top, dirty.right, dirty.bottom);
    }

    void setVisible(boolean visible) {
        if (_visible == visible) return;
        _visible = visible;
        boundsInto(_bounds);
        postInvalidateOnAnimation(_bounds.left, _bounds.top, _bounds.right, _bounds.bottom);
    }

    private void boundsInto(Rect out) {
        if (_x < 0) { out.setEmpty(); return; }
        int pad = (int) Math.ceil(2 * _uiScale);   // stroke width plus antialias slop
        out.set((int) _x - pad, (int) _y - pad,
                (int) (_x + 12 * _uiScale) + pad, (int) (_y + 21 * _uiScale) + pad);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!_visible || _x < 0) return;
        canvas.save();
        canvas.translate(_x, _y);
        // Density, NOT the display scale: zooming here shrinks the logical screen so the
        // world's content gets physically bigger. Scaling the arrow by the zoom would make
        // it shrink as everything else grows.
        canvas.scale(_uiScale, _uiScale);
        canvas.drawPath(_path, _fill);
        canvas.drawPath(_path, _stroke);
        canvas.restore();
    }
}
