package vn.quettailieu.scanner;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import vn.quettailieu.scanner.core.Geom;

/**
 * Hiển thị ảnh kèm khung cắt 4 góc: kéo tay cầm ở góc hoặc giữa cạnh để chỉnh,
 * có kính lúp phóng to khi kéo (giống CamScanner).
 */
public class CropView extends View {

    interface Listener {
        void onQuadChanged(boolean byUser);
    }

    private Bitmap bmp;
    private int imgW = 1, imgH = 1;
    private float[] quad;
    private float scale = 1, offX, offY;
    private final RectF dst = new RectF();
    private final Path path = new Path();
    private final Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lineBad = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shade = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final RectF tmp = new RectF();
    private int dragCorner = -1, dragEdge = -1;
    private float downX, downY, grabDx, grabDy;
    private float[] quadStart;
    private Listener listener;
    private ValueAnimator anim;
    private boolean interactive = true;

    public CropView(Context c) {
        super(c);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(Ui.dpf(2.2f));
        line.setColor(Ui.ACCENT);
        line.setStrokeJoin(Paint.Join.ROUND);
        lineBad.set(line);
        lineBad.setColor(Ui.RED);
        fill.setColor(Color.WHITE);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(Ui.dpf(2.2f));
        stroke.setColor(Ui.ACCENT);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(Ui.dpf(3));
        ring.setColor(Color.WHITE);
        shade.setColor(0x40000000);
    }

    void setListener(Listener l) {
        listener = l;
    }

    void setInteractive(boolean v) {
        interactive = v;
    }

    void setImage(Bitmap b, int fullW, int fullH) {
        bmp = b;
        imgW = Math.max(1, fullW);
        imgH = Math.max(1, fullH);
        if (quad == null) quad = Geom.fullQuad(imgW, imgH);
        computeTransform();
        invalidate();
    }

    float[] getQuad() {
        return quad == null ? null : quad.clone();
    }

    boolean isValidQuad() {
        if (quad == null || !Geom.isConvex(quad)) return false;
        return Geom.area(quad) > 0.01 * imgW * imgH;
    }

    void setQuad(float[] q, boolean animate) {
        if (q == null) return;
        final float[] target = q.clone();
        Geom.clamp(target, imgW, imgH);
        if (anim != null) anim.cancel();
        if (!animate || quad == null) {
            quad = target;
            invalidate();
            if (listener != null) listener.onQuadChanged(false);
            return;
        }
        final float[] from = quad.clone();
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(240);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                float t = (Float) a.getAnimatedValue();
                float[] q2 = new float[8];
                for (int i = 0; i < 8; i++) q2[i] = from[i] + (target[i] - from[i]) * t;
                quad = q2;
                invalidate();
            }
        });
        anim.start();
        if (listener != null) listener.onQuadChanged(false);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        computeTransform();
    }

    private void computeTransform() {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        float pad = Ui.dpf(22);
        float aw = Math.max(1, w - 2 * pad), ah = Math.max(1, h - 2 * pad);
        scale = Math.min(aw / imgW, ah / imgH);
        offX = (w - imgW * scale) / 2f;
        offY = (h - imgH * scale) / 2f;
        dst.set(offX, offY, offX + imgW * scale, offY + imgH * scale);
    }

    private float vx(float x) { return offX + x * scale; }

    private float vy(float y) { return offY + y * scale; }

    @Override
    protected void onDraw(Canvas c) {
        if (bmp == null || quad == null) return;
        c.drawBitmap(bmp, null, dst, bmpPaint);
        buildPath();
        boolean valid = Geom.isConvex(quad);
        // làm tối nhẹ phần ngoài vùng cắt
        c.save();
        clip.reset();
        clip.addRect(dst, Path.Direction.CW);
        clip.addPath(path);
        clip.setFillType(Path.FillType.EVEN_ODD);
        c.drawPath(clip, shade);
        c.restore();
        c.drawPath(path, valid ? line : lineBad);
        Paint st = valid ? stroke : lineBad;
        // tay cầm giữa cạnh (hình viên thuốc xoay theo cạnh)
        float hl = Ui.dpf(11), hw = Ui.dpf(3.6f);
        for (int i = 0; i < 4; i++) {
            int j = (i + 1) % 4;
            float ax = vx(quad[2 * i]), ay = vy(quad[2 * i + 1]), bx = vx(quad[2 * j]), by = vy(quad[2 * j + 1]);
            float mx = (ax + bx) / 2, my = (ay + by) / 2;
            float ang = (float) Math.toDegrees(Math.atan2(by - ay, bx - ax));
            c.save();
            c.rotate(ang, mx, my);
            tmp.set(mx - hl, my - hw, mx + hl, my + hw);
            c.drawRoundRect(tmp, hw, hw, fill);
            c.drawRoundRect(tmp, hw, hw, st);
            c.restore();
        }
        float r = Ui.dpf(8.5f);
        for (int i = 0; i < 4; i++) {
            float x = vx(quad[2 * i]), y = vy(quad[2 * i + 1]);
            c.drawCircle(x, y, r, fill);
            c.drawCircle(x, y, r, st);
        }
        if (dragCorner >= 0 || dragEdge >= 0) drawLoupe(c);
    }

    private void buildPath() {
        path.reset();
        path.moveTo(vx(quad[0]), vy(quad[1]));
        for (int i = 1; i < 4; i++) path.lineTo(vx(quad[2 * i]), vy(quad[2 * i + 1]));
        path.close();
    }

    private void drawLoupe(Canvas c) {
        float fx, fy;
        if (dragCorner >= 0) {
            fx = vx(quad[2 * dragCorner]);
            fy = vy(quad[2 * dragCorner + 1]);
        } else {
            int i = dragEdge, j = (i + 1) % 4;
            fx = (vx(quad[2 * i]) + vx(quad[2 * j])) / 2;
            fy = (vy(quad[2 * i + 1]) + vy(quad[2 * j + 1])) / 2;
        }
        float R = Ui.dpf(58);
        float m = Ui.dpf(14);
        float lx = R + m, ly = R + m;
        // tránh che ngón tay: nếu điểm đang kéo ở góc trên-trái thì đặt kính lúp sang phải
        if (fx < getWidth() / 2f && fy < 2.5f * R + m) lx = getWidth() - R - m;
        float zoom = 2.2f;
        c.save();
        clip.reset();
        clip.addCircle(lx, ly, R, Path.Direction.CW);
        c.clipPath(clip);
        c.drawColor(Color.BLACK);
        c.translate(lx, ly);
        c.scale(zoom, zoom);
        c.translate(-fx, -fy);
        c.drawBitmap(bmp, null, dst, bmpPaint);
        Paint lp = new Paint(Geom.isConvex(quad) ? line : lineBad);
        lp.setStrokeWidth(line.getStrokeWidth() / zoom);
        c.drawPath(path, lp);
        c.restore();
        c.drawCircle(lx, ly, R, ring);
        Paint cross = new Paint(Paint.ANTI_ALIAS_FLAG);
        cross.setColor(Ui.ACCENT);
        cross.setStrokeWidth(Ui.dpf(1.5f));
        float cl = Ui.dpf(9);
        c.drawLine(lx - cl, ly, lx + cl, ly, cross);
        c.drawLine(lx, ly - cl, lx, ly + cl, cross);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!interactive || quad == null || bmp == null) return false;
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                if (anim != null) anim.cancel();
                dragCorner = -1;
                dragEdge = -1;
                float best = Ui.dpf(40);
                for (int i = 0; i < 4; i++) {
                    float d = (float) Math.hypot(vx(quad[2 * i]) - x, vy(quad[2 * i + 1]) - y);
                    if (d < best) { best = d; dragCorner = i; }
                }
                if (dragCorner < 0) {
                    best = Ui.dpf(34);
                    for (int i = 0; i < 4; i++) {
                        int j = (i + 1) % 4;
                        float mx = (vx(quad[2 * i]) + vx(quad[2 * j])) / 2, my = (vy(quad[2 * i + 1]) + vy(quad[2 * j + 1])) / 2;
                        float d = (float) Math.hypot(mx - x, my - y);
                        if (d < best) { best = d; dragEdge = i; }
                    }
                }
                if (dragCorner < 0 && dragEdge < 0) return false;
                downX = x;
                downY = y;
                quadStart = quad.clone();
                if (dragCorner >= 0) {
                    grabDx = vx(quad[2 * dragCorner]) - x;
                    grabDy = vy(quad[2 * dragCorner + 1]) - y;
                }
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (dragCorner >= 0) {
                    float ix = (x + grabDx - offX) / scale, iy = (y + grabDy - offY) / scale;
                    quad[2 * dragCorner] = clampF(ix, 0, imgW);
                    quad[2 * dragCorner + 1] = clampF(iy, 0, imgH);
                } else if (dragEdge >= 0) {
                    moveEdge(dragEdge, (x - downX) / scale, (y - downY) / scale);
                }
                invalidate();
                if (listener != null) listener.onQuadChanged(true);
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                dragCorner = -1;
                dragEdge = -1;
                invalidate();
                return true;
            }
        }
        return true;
    }

    /** Dịch cạnh song song theo pháp tuyến; hai đầu trượt dọc theo hai cạnh kề. */
    private void moveEdge(int i, float dxImg, float dyImg) {
        int a = i, b = (i + 1) % 4;
        float ax = quadStart[2 * a], ay = quadStart[2 * a + 1], bx = quadStart[2 * b], by = quadStart[2 * b + 1];
        float len = (float) Math.hypot(bx - ax, by - ay);
        if (len < 1e-3f) return;
        float nx = -(by - ay) / len, ny = (bx - ax) / len;
        float delta = dxImg * nx + dyImg * ny;
        slide(a, (a + 3) % 4, nx, ny, delta);
        slide(b, (b + 1) % 4, nx, ny, delta);
    }

    private void slide(int c, int nb, float nx, float ny, float delta) {
        float cx = quadStart[2 * c], cy = quadStart[2 * c + 1];
        float ux = cx - quadStart[2 * nb], uy = cy - quadStart[2 * nb + 1];
        float l = (float) Math.hypot(ux, uy);
        float tx, ty;
        if (l > 1e-3f) {
            ux /= l;
            uy /= l;
            float dot = ux * nx + uy * ny;
            if (Math.abs(dot) > 0.25f) {
                tx = cx + ux * (delta / dot);
                ty = cy + uy * (delta / dot);
            } else {
                tx = cx + nx * delta;
                ty = cy + ny * delta;
            }
        } else {
            tx = cx + nx * delta;
            ty = cy + ny * delta;
        }
        quad[2 * c] = clampF(tx, 0, imgW);
        quad[2 * c + 1] = clampF(ty, 0, imgH);
    }

    private static float clampF(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
