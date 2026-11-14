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

/**
 * Dải trang vuốt ngang (trang bên cạnh ló ra), kèm hiệu ứng quét khi đang xử lý.
 * Hai ngón chụm / dang để phóng to trang đang xem (tới 6 lần) và kéo ảnh; khi đang phóng to, một ngón kéo ảnh,
 * chạm đúp để về vừa khung (chạm đúp lúc vừa khung: phóng 2,5 lần tại chỗ chạm). Lật trang chỉ khi ở mức vừa khung.
 */
public class PageStripView extends View {

    interface Adapter {
        int count();

        Bitmap bitmap(int i);

        boolean processing(int i);

        Bitmap compareBitmap(int i);

        /** Ảnh độ phân giải cao của trang i để xem khi phóng to; null nếu chưa có (dùng bitmap(i)). */
        Bitmap zoomBitmap(int i);
    }

    interface Listener {
        void onPageChanged(int index);
    }

    static final float MAX_ZOOM = 6f;
    /** Mức phóng khi chạm đúp. */
    static final float TAP_ZOOM = 2.5f;

    private Adapter adapter;
    private Listener listener;
    private int index;
    private float offset;
    private float dragStartOffset;
    private float downX;
    private boolean dragging;
    private int activePointer = -1;
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

    // ---- phóng to trang đang xem
    private float zoom = 1f, panX, panY;
    /** Đang chụm / dang hai ngón. */
    private boolean pinching;
    /** Lượt chạm này đã có lúc đặt hai ngón: nhấc tay không tính là chạm, không lật trang. */
    private boolean multiTouch;
    private float lastDist, lastMidX, lastMidY;
    private float downY, panStartX, panStartY;
    /** Kích thước trang đang xem ở mức vừa khung (để giới hạn phần kéo). */
    private float curW, curH;
    private long lastTapAt;
    private float lastTapX, lastTapY;
    private ValueAnimator zoomAnim;
    private final Paint badgeBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badgeText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF badge = new RectF();

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
        badgeBg.setColor(0xB3222124);
        badgeText.setColor(0xFFFFFFFF);
        badgeText.setTextSize(Ui.dpf(13));
        badgeText.setTextAlign(Paint.Align.CENTER);
        updateDesc();
    }

    void setAdapter(Adapter a) {
        adapter = a;
        updateDesc();
        invalidate();
    }

    /** Mức phóng hiện tại (1 = vừa khung). */
    float zoom() {
        return zoom;
    }

    boolean zoomed() {
        return zoom > 1.001f;
    }

    /** Về mức vừa khung ngay (đổi trang, dữ liệu đổi). */
    void resetZoom() {
        if (zoomAnim != null) zoomAnim.cancel();
        if (zoom == 1f && panX == 0f && panY == 0f) return;
        zoom = 1f;
        panX = panY = 0f;
        updateDesc();
        invalidate();
    }

    private static String zoomText(float z) {
        return String.format(java.util.Locale.US, "%.1f", z).replace('.', ',') + "×";
    }

    /** Mô tả cho trình đọc màn hình (và kiểm thử giao diện): trang đang xem + mức phóng. */
    private void updateDesc() {
        int n = adapter == null ? 0 : adapter.count();
        String d = n == 0 ? "Trang" : "Trang " + (index + 1) + "/" + n;
        if (zoomed()) d += ", phóng " + zoomText(zoom);
        setContentDescription(d);
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /** Giữ trang đã phóng không trôi khỏi khung: mỗi chiều chỉ kéo được phần ảnh tràn ra ngoài khung (thêm chút lề). */
    private void clampPan() {
        if (zoom <= 1.001f) {
            panX = panY = 0f;
            return;
        }
        float pad = Ui.dpf(12);
        float w = curW > 0 ? curW : slotW(), h = curH > 0 ? curH : getHeight() - Ui.dpf(28);
        float maxX = Math.max(0f, (w * zoom - getWidth()) / 2f + pad), maxY = Math.max(0f, (h * zoom - getHeight()) / 2f + pad);
        panX = clamp(panX, -maxX, maxX);
        panY = clamp(panY, -maxY, maxY);
    }

    /** Đổi mức phóng, giữ điểm (fx, fy) của khung đứng yên. */
    private void zoomAround(float nz, float fx, float fy) {
        nz = clamp(nz, 1f, MAX_ZOOM);
        float f = nz / zoom;
        float ox = fx - getWidth() / 2f, oy = fy - getHeight() / 2f;
        panX = (panX - ox) * f + ox;
        panY = (panY - oy) * f + oy;
        zoom = nz;
        clampPan();
    }

    /** Chuyển mượt tới mức phóng `target` quanh điểm (fx, fy). */
    private void animateZoom(final float target, final float fx, final float fy) {
        if (zoomAnim != null) zoomAnim.cancel();
        final float from = zoom;
        if (Math.abs(target - from) < 0.01f || getWidth() == 0) {
            zoomAround(target, fx, fy);
            updateDesc();
            invalidate();
            return;
        }
        zoomAnim = ValueAnimator.ofFloat(0f, 1f);
        zoomAnim.setDuration(200);
        zoomAnim.setInterpolator(new DecelerateInterpolator());
        zoomAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                float t = (Float) a.getAnimatedValue();
                zoomAround(from + (target - from) * t, fx, fy);
                if (t >= 1f) updateDesc();
                invalidate();
            }
        });
        zoomAnim.start();
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
            if (settle != null) settle.cancel();
            offset = 0;
            updateDesc();
            invalidate();
            return;
        }
        resetZoom();
        if (!animate || getWidth() == 0) {
            if (settle != null) settle.cancel();
            index = i;
            offset = 0;
            updateDesc();
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
        if (settle != null) settle.cancel();
        if (adapter != null) {
            int n = adapter.count();
            if (index > n - 1) index = Math.max(0, n - 1);
        }
        offset = 0;
        resetZoom();
        updateDesc();
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        clampPan();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (settle != null) settle.cancel();
        if (zoomAnim != null) zoomAnim.cancel();
        if (vt != null) {
            vt.recycle();
            vt = null;
        }
        super.onDetachedFromWindow();
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
        if (zoomed()) {
            // đang phóng to: chỉ vẽ trang đang xem, phóng quanh tâm khung rồi dời theo phần kéo
            float vx = getWidth() / 2f, vy = getHeight() / 2f;
            c.save();
            c.translate(vx + panX, vy + panY);
            c.scale(zoom, zoom);
            c.translate(-vx, -vy);
            anim = drawPage(c, index, vx, true);
            c.restore();
            drawBadge(c);
            if (anim) postInvalidateOnAnimation();
            return;
        }
        // lật nhanh nhiều trang liên tiếp: phần lệch có thể lớn hơn một bước -> vẽ cả các trang xa hơn đang lướt qua
        int span = 1 + (int) Math.ceil(Math.abs(offset) / Math.max(1f, step()));
        for (int k = -span; k <= span; k++) {
            int i = index + k;
            if (i < 0 || i >= n) continue;
            float x = cx + k * step();
            if (x + slotW() / 2 < 0 || x - slotW() / 2 > getWidth()) continue;
            if (drawPage(c, i, x, k == 0)) anim = true;
        }
        if (anim) postInvalidateOnAnimation();
    }

    /** Nhãn mức phóng (vd. "2,5×") ở giữa mép dưới khung. */
    private void drawBadge(Canvas c) {
        String t = zoomText(zoom);
        float tw = badgeText.measureText(t), bh = Ui.dpf(24), bw = tw + Ui.dpf(20);
        float bx = getWidth() / 2f, by = getHeight() - Ui.dpf(10) - bh;
        badge.set(bx - bw / 2, by, bx + bw / 2, by + bh);
        c.drawRoundRect(badge, bh / 2, bh / 2, badgeBg);
        Paint.FontMetrics fm = badgeText.getFontMetrics();
        c.drawText(t, bx, by + bh / 2 - (fm.ascent + fm.descent) / 2, badgeText);
    }

    private boolean drawPage(Canvas c, int i, float centerX, boolean current) {
        float availH = getHeight() - Ui.dpf(28);
        float availW = slotW();
        boolean proc = adapter.processing(i);
        Bitmap b = null;
        if (compare && current) b = adapter.compareBitmap(i);
        // phóng to: dùng ảnh độ phân giải cao nếu đã nạp xong (cùng tỷ lệ với ảnh thường nên khung trang không đổi)
        if (b == null && current && zoom > 1.2f && !proc) {
            b = adapter.zoomBitmap(i);
            if (b != null && b.isRecycled()) b = null;
        }
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
        if (current) {
            curW = w;
            curH = h;
        }
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

    private static float dist(MotionEvent e, int i0, int i1) {
        return (float) Math.hypot(e.getX(i0) - e.getX(i1), e.getY(i0) - e.getY(i1));
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (adapter == null || adapter.count() == 0) return false;
        int n = adapter.count();
        int action = e.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                if (settle != null && settle.isRunning()) {
                    settle.cancel();
                }
                if (zoomAnim != null && zoomAnim.isRunning()) zoomAnim.cancel();
                if (vt != null) vt.recycle();
                vt = VelocityTracker.obtain();
                vt.addMovement(e);
                activePointer = e.getPointerId(0);
                downX = e.getX();
                downY = e.getY();
                dragStartOffset = offset;
                panStartX = panX;
                panStartY = panY;
                dragging = false;
                pinching = false;
                multiTouch = false;
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                if (e.getPointerCount() >= 2) {
                    // hai ngón: phóng to / kéo ảnh; thao tác lật trang đang dở thì trả trang về chỗ cũ
                    if (!pinching) {
                        dragging = false;
                        if (offset != 0f) animateTo(index);
                    }
                    pinching = true;
                    multiTouch = true;
                    lastDist = dist(e, 0, 1);
                    lastMidX = (e.getX(0) + e.getX(1)) / 2f;
                    lastMidY = (e.getY(0) + e.getY(1)) / 2f;
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                }
                return true;
            case MotionEvent.ACTION_MOVE: {
                if (vt != null) vt.addMovement(e);
                if (pinching) {
                    if (e.getPointerCount() < 2) return true;
                    float d = dist(e, 0, 1);
                    float mx = (e.getX(0) + e.getX(1)) / 2f, my = (e.getY(0) + e.getY(1)) / 2f;
                    // giữ điểm giữa hai ngón đứng yên khi phóng, rồi dời ảnh theo điểm giữa
                    if (lastDist > 10f && d > 10f) zoomAround(zoom * d / lastDist, mx, my);
                    if (zoomed()) {
                        panX += mx - lastMidX;
                        panY += my - lastMidY;
                        clampPan();
                    }
                    lastDist = d;
                    lastMidX = mx;
                    lastMidY = my;
                    invalidate();
                    return true;
                }
                int pi = e.findPointerIndex(activePointer);
                if (pi < 0) return true;
                float dx = e.getX(pi) - downX;
                if (zoomed()) {
                    // đang phóng to: một ngón kéo ảnh (không lật trang)
                    float dy = e.getY(pi) - downY;
                    if (!dragging && Math.hypot(dx, dy) > touchSlop) {
                        dragging = true;
                        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    if (dragging) {
                        panX = panStartX + dx;
                        panY = panStartY + dy;
                        clampPan();
                        invalidate();
                    }
                    return true;
                }
                // vừa thu về vừa khung bằng hai ngón: ngón còn chạm không được lật trang
                if (multiTouch) return true;
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
            case MotionEvent.ACTION_POINTER_UP: {
                int up = e.getActionIndex();
                if (pinching) {
                    if (e.getPointerCount() - 1 >= 2) {
                        // còn từ hai ngón: tính tiếp theo hai ngón đầu còn chạm
                        int i0 = up == 0 ? 1 : 0, i1 = up <= 1 ? 2 : 1;
                        lastDist = dist(e, i0, i1);
                        lastMidX = (e.getX(i0) + e.getX(i1)) / 2f;
                        lastMidY = (e.getY(i0) + e.getY(i1)) / 2f;
                    } else {
                        // còn một ngón: nếu đang phóng to thì ngón đó kéo ảnh tiếp
                        pinching = false;
                        int other = up == 0 ? 1 : 0;
                        activePointer = e.getPointerId(other);
                        downX = e.getX(other);
                        downY = e.getY(other);
                        if (zoom < 1.05f) {
                            zoom = 1f;
                            panX = panY = 0f;
                        }
                        panStartX = panX;
                        panStartY = panY;
                        dragging = zoomed();
                        updateDesc();
                        invalidate();
                    }
                    return true;
                }
                // ngón đang vuốt nhấc lên, ngón khác còn chạm: theo ngón còn lại từ vị trí hiện tại (không giật trang)
                if (e.getPointerId(up) == activePointer) {
                    int other = up == 0 ? 1 : 0;
                    if (other < e.getPointerCount()) {
                        activePointer = e.getPointerId(other);
                        downX = e.getX(other);
                        downY = e.getY(other);
                        dragStartOffset = offset;
                        panStartX = panX;
                        panStartY = panY;
                    }
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
                boolean wasDragging = dragging, wasMulti = multiTouch;
                dragging = false;
                pinching = false;
                multiTouch = false;
                activePointer = -1;
                boolean tap = !wasDragging && !wasMulti && action == MotionEvent.ACTION_UP;
                if (wasMulti || zoomed()) {
                    // kết thúc phóng to / kéo ảnh: không lật trang
                    if (zoom < 1.05f) {
                        zoom = 1f;
                        panX = panY = 0f;
                    }
                    if (offset != 0f) animateTo(index);
                    if (tap) {
                        performClick();
                        onTap(e.getX(), e.getY());
                    }
                    updateDesc();
                    invalidate();
                    return true;
                }
                int target = index;
                float st = step();
                if ((offset < -st * 0.22f || (wasDragging && v < -1100)) && index < n - 1) target = index + 1;
                else if ((offset > st * 0.22f || (wasDragging && v > 1100)) && index > 0) target = index - 1;
                if (tap) performClick();
                animateTo(target);
                if (tap && target == index) onTap(e.getX(), e.getY());
                return true;
            }
        }
        return true;
    }

    /** Chạm đúp: đang vừa khung thì phóng TAP_ZOOM lần tại chỗ chạm, đang phóng to thì về vừa khung. */
    private void onTap(float x, float y) {
        long now = SystemClock.uptimeMillis();
        boolean dbl = now - lastTapAt < 320 && Math.hypot(x - lastTapX, y - lastTapY) < Ui.dpf(40);
        if (!dbl) {
            lastTapAt = now;
            lastTapX = x;
            lastTapY = y;
            return;
        }
        lastTapAt = 0;
        if (zoomed()) animateZoom(1f, getWidth() / 2f, getHeight() / 2f);
        else animateZoom(TAP_ZOOM, x, y);
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    /**
     * Chuyển sang trang `target`. Chỉ số trang đổi NGAY (phần lệch còn lại được cuộn về 0 bằng hoạt ảnh),
     * nên thao tác bấm ngay sau khi lật trang (xoay, lọc, xoá...) áp dụng đúng trang đang lật tới,
     * và bấm "trang sau" liên tiếp không bị mất lượt.
     */
    private void animateTo(final int target) {
        if (settle != null) settle.cancel();
        if (target != index) {
            resetZoom();
            offset += (target - index) * step();
            index = target;
            updateDesc();
            if (listener != null) listener.onPageChanged(index);
        }
        final float from = offset;
        if (Math.abs(from) < 0.5f || getWidth() == 0) {
            offset = 0;
            invalidate();
            return;
        }
        settle = ValueAnimator.ofFloat(0f, 1f);
        settle.setDuration(230);
        settle.setInterpolator(new DecelerateInterpolator());
        settle.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                float t = (Float) a.getAnimatedValue();
                offset = from * (1f - t);
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
                offset = 0;
                invalidate();
            }
        });
        settle.start();
    }
}
