package app.photovault;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.widget.ImageView;

/**
 * The viewer's photo: pinch to zoom (up to 8x), drag to move around, double-tap to zoom in or back to fit.
 * At 1x it is a normal fitted ImageView, so the swipe to the next item works; zoomed in, the swipe is for panning.
 */
final class Zoom extends ImageView {
    private static final float MAX = 8f;
    private final Matrix m = new Matrix();
    private final ScaleGestureDetector pinch;
    private final GestureDetector taps;
    private float scale = 1, lastX, lastY;
    private boolean pinching;

    Zoom(Context c) {
        super(c);
        setScaleType(ScaleType.FIT_CENTER);
        pinch = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScaleBegin(ScaleGestureDetector d) { pinching = true; start(); return true; }
            @Override public boolean onScale(ScaleGestureDetector d) { zoomBy(d.getScaleFactor(), d.getFocusX(), d.getFocusY()); return true; }
        });
        taps = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDoubleTap(MotionEvent e) {
                if (zoomed()) reset(); else { start(); zoomBy(2.5f, e.getX(), e.getY()); }
                return true;
            }
        });
    }

    /** Zoomed in, or a pinch under way: the viewer's swipe must not change the item. */
    boolean busy() { return pinching || zoomed(); }

    boolean zoomed() { return scale > 1.001f; }

    /** From the fitted image to a free matrix, starting where the fit put it. */
    private void start() {
        if (getScaleType() == ScaleType.MATRIX) return;
        m.set(getImageMatrix());
        setScaleType(ScaleType.MATRIX);
        setImageMatrix(m);
    }

    private void reset() {
        scale = 1;
        setScaleType(ScaleType.FIT_CENTER);
    }

    private void zoomBy(float f, float x, float y) {
        float next = Math.max(1, Math.min(MAX, scale * f));
        f = next / scale;
        scale = next;
        if (!zoomed()) { reset(); return; }
        m.postScale(f, f, x, y);
        keepInView();
    }

    /** The image never leaves a gap at an edge it can cover, and stays centred where it is smaller than the view. */
    private void keepInView() {
        if (getDrawable() == null) return;
        RectF r = new RectF(0, 0, getDrawable().getIntrinsicWidth(), getDrawable().getIntrinsicHeight());
        m.mapRect(r);
        float w = getWidth(), h = getHeight(), dx, dy;
        if (r.width() <= w) dx = (w - r.width()) / 2 - r.left; else dx = r.left > 0 ? -r.left : r.right < w ? w - r.right : 0;
        if (r.height() <= h) dy = (h - r.height()) / 2 - r.top; else dy = r.top > 0 ? -r.top : r.bottom < h ? h - r.bottom : 0;
        m.postTranslate(dx, dy);
        setImageMatrix(m);
    }

    /** A new image (the original after the preview) starts fitted: the old matrix was for the other size. */
    @Override public void setImageBitmap(android.graphics.Bitmap b) {
        super.setImageBitmap(b);
        reset();
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        pinch.onTouchEvent(e);
        taps.onTouchEvent(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: lastX = e.getX(); lastY = e.getY(); break;
            case MotionEvent.ACTION_POINTER_UP: // a finger lifted: go on from the one that stays
                if (e.getActionIndex() == 0 && e.getPointerCount() > 1) { lastX = e.getX(1); lastY = e.getY(1); } else { lastX = e.getX(0); lastY = e.getY(0); }
                break;
            case MotionEvent.ACTION_MOVE:
                if (zoomed() && !pinch.isInProgress() && e.getPointerCount() == 1) {
                    m.postTranslate(e.getX() - lastX, e.getY() - lastY);
                    keepInView();
                }
                lastX = e.getX(); lastY = e.getY();
                break;
            case MotionEvent.ACTION_UP: case MotionEvent.ACTION_CANCEL: pinching = false; break;
        }
        return true;
    }
}
