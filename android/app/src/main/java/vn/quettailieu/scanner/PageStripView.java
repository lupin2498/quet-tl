package vn.quettailieu.scanner;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;

/** Dải trang vuốt ngang (trang bên cạnh ló ra), kèm hiệu ứng quét khi đang xử lý. */
public class PageStripView extends View {

    interface Adapter {
        int count();

        Bitmap bitmap(int i);

        boolean processing(int i);

        Bitmap compareBitmap(int i);
    }

    interface Listener {
        void onPageChanged(int index);
    }

    private Adapter adapter;
    private Listener listener;
    private int index;
    private float offset;
    private float dragStartOffset;
    private float downX;
    private boolean dragging;
    private VelocityTracker vt;
    private ValueAnimator settle;
    private boolean compare;
    private final int touchSlop;
    private final Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint grayPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint placeholder = new Paint();
    private final Paint dim = new Paint();
    private final Paint band = new Paint();
    private final Paint lineP = new Paint();
    private final Paint spark = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadow = new Paint();
    private final RectF r = new RectF();
    private final long t0 = SystemClock.uptimeMillis();

    public PageStripView(Context c) {
        super(c);
        touchSlop = ViewConfiguration.get(c).getScaledTouchSlop();
        ColorMatrix cm = new ColorMatrix();
        cm.setSaturation(0);
        grayPaint.setColorFilter(new ColorMatrixColorFilter(cm));
        placeholder.setColor(0xFF3A393D);
        dim.setColor(0x66000000);
        lineP.setColor(0xF2FFFFFF);
        spark.setColor(0xFFFFFFFF);
        shadow.setColor(0x33000000);
    }

    void setAdapter(Adapter a) {
        adapter = a;
        invalidate();
    }

    void setListener(Listener l) {
        listener = l;
    }

    int getIndex() {
        return index;
    }

    void setIndex(int i, boolean animate) {
        if (adapter == null) return;
        int n = adapter.count();
        if (n == 0) return;
        i = Math.max(0, Math.min(n - 1, i));
        if (i == index) {
            offset = 0;
            invalidate();
            return;
        }
        if (!animate || getWidth() == 0) {
            if (settle != null) settle.cancel();
            index = i;
            offset = 0;
            invalidate();
            if (listener != null) listener.onPageChanged(index);
            return;
        }
        animateTo(i);
    }

    void setCompare(boolean on) {
        compare = on;
        invalidate();
    }

    void dataChanged() {
        if (adapter != null) {
            int n = adapter.count();
            if (index > n - 1) index = Math.max(0, n - 1);
        }
        offset = 0;
        invalidate();
    }

    private float step() {
        return slotW() + gap();
    }

    private float slotW() {
        return getWidth() * 0.82f;
    }

    private float gap() {
        return Ui.dpf(14);
    }

    @Override
    protected void onDraw(Canvas c) {
        if (adapter == null) return;
        int n = adapter.count();
        if (n == 0) return;
        float cx = getWidth() / 2f + offset;
        boolean anim = false;
        for (int k = -1; k <= 1; k++) {
            int i = index + k;
            if (i < 0 || i >= n) continue;
            float x = cx + k * step();
            if (x + slotW() / 2 < 0 || x - slotW() / 2 > getWidth()) continue;
            if (drawPage(c, i, x, k == 0)) anim = true;
        }
        if (anim) postInvalidateOnAnimation();
    }

    private boolean drawPage(Canvas c, int i, float centerX, boolean current) {
        float availH = getHeight() - Ui.dpf(28);
        float availW = slotW();
        boolean proc = adapter.processing(i);
        Bitmap b = null;
        if (compare && current) b = adapter.compareBitmap(i);
        if (b == null) b = adapter.bitmap(i);
        float w, h;
        if (b == null || b.isRecycled()) {
            h = availH;
            w = h * 0.707f;
            if (w > availW) {
                w = availW;
                h = w / 0.707f;
            }
        } else {
            float s = Math.min(availW / b.getWidth(), availH / b.getHeight());
            w = b.getWidth() * s;
            h = b.getHeight() * s;
        }
        float top = (getHeight() - h) / 2f;
        r.set(centerX - w / 2, top, centerX + w / 2, top + h);
        c.drawRect(r.left + Ui.dpf(2), r.top + Ui.dpf(3), r.right + Ui.dpf(2), r.bottom + Ui.dpf(3), shadow);
        if (b == null || b.isRecycled()) c.drawRect(r, placeholder);
        else c.drawBitmap(b, null, r, proc && !compare ? grayPaint : bmpPaint);
        if (proc && !(compare && current)) {
            drawScan(c, r, i);
            return true;
        }
        return false;
    }

    /** Hiệu ứng "đang quét": vạch sáng chạy dọc trang kèm đốm lấp lánh. */
    private void drawScan(Canvas c, RectF rect, int seed) {
        long t = (SystemClock.uptimeMillis() - t0 + seed * 377L) % 1700L;
        float p = t / 1700f;
        float y = rect.top + rect.height() * p;
        float bandH = rect.height() * 0.2f;
        c.save();
        c.clipRect(rect);
        c.drawRect(rect.left, y, rect.right, rect.bottom, dim);
        band.setShader(new LinearGradient(0, y - bandH, 0, y, 0x00FFFFFF, 0x8CFFFFFF, Shader.TileMode.CLAMP));
        c.drawRect(rect.left, y - bandH, rect.right, y, band);
        c.drawRect(rect.left, y - Ui.dpf(1.2f), rect.right, y + Ui.dpf(1.2f), lineP);
        for (int k = 0; k < 22; k++) {
            float fx = frac(k * 0.6180339f + seed * 0.13f);
            float fy = frac(k * 0.4142135f + 0.37f);
            float sx = rect.left + fx * rect.width();
            float sy = y - fy * bandH * 1.2f + Ui.dpf(4);
            float a = 0.5f + 0.5f * (float) Math.sin(t / 110.0 + k * 1.7);
            spark.setAlpha((int) (40 + 215 * a));
            c.drawCircle(sx, sy, Ui.dpf(1.1f + (k % 3) * 0.7f), spark);
        }
        c.restore();
    }

    private static float frac(float v) {
        return v - (float) Math.floor(v);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (adapter == null || adapter.count() == 0) return false;
        int n = adapter.count();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (settle != null && settle.isRunning()) {
                    settle.cancel();
                }
                if (vt != null) vt.recycle();
                vt = VelocityTracker.obtain();
                vt.addMovement(e);
                downX = e.getX();
                dragStartOffset = offset;
                dragging = false;
                return true;
            case MotionEvent.ACTION_MOVE: {
                if (vt != null) vt.addMovement(e);
                float dx = e.getX() - downX;
                if (!dragging && Math.abs(dx) > touchSlop) {
                    dragging = true;
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                }
                if (dragging) {
                    float o = dragStartOffset + dx;
                    if ((index == 0 && o > 0) || (index == n - 1 && o < 0)) o *= 0.35f;
                    offset = o;
                    invalidate();
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                float v = 0;
                if (vt != null) {
                    vt.addMovement(e);
                    vt.computeCurrentVelocity(1000);
                    v = vt.getXVelocity();
                    vt.recycle();
                    vt = null;
                }
                int target = index;
                float st = step();
                if ((offset < -st * 0.22f || (dragging && v < -1100)) && index < n - 1) target = index + 1;
                else if ((offset > st * 0.22f || (dragging && v > 1100)) && index > 0) target = index - 1;
                if (!dragging && e.getActionMasked() == MotionEvent.ACTION_UP) performClick();
                animateTo(target);
                dragging = false;
                return true;
            }
        }
        return true;
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    private void animateTo(final int target) {
        if (settle != null) settle.cancel();
        final float from = offset;
        final float to = (index - target) * step();
        settle = ValueAnimator.ofFloat(0f, 1f);
        settle.setDuration(230);
        settle.setInterpolator(new DecelerateInterpolator());
        settle.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                float t = (Float) a.getAnimatedValue();
                offset = from + (to - from) * t;
                invalidate();
            }
        });
        settle.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled;

            @Override
            public void onAnimationCancel(Animator animation) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (cancelled) return;
                boolean changed = target != index;
                index = target;
                offset = 0;
                invalidate();
                if (changed && listener != null) listener.onPageChanged(index);
            }
        });
        settle.start();
    }
}
