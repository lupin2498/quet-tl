package vn.quettailieu.scanner;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;

/**
 * Khung hiển thị một trang cho các màn hình chỉnh sửa (Xóa thông minh, Đánh dấu):
 * ảnh nằm vừa khung, xoay theo góc xoay của trang; một ngón để vẽ / chọn, hai ngón để phóng to và kéo.
 * Lớp con làm việc trong hệ toạ độ ẢNH CHƯA XOAY (pixel của ảnh xem trước).
 */
abstract class PageCanvasView extends View {
    protected Bitmap image;
    protected int rot;
    protected final Matrix mtx = new Matrix(), inv = new Matrix();
    /** Số pixel màn hình ứng với một pixel ảnh. */
    protected float scale = 1f;
    private final Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private float zoom = 1f, panX, panY;
    private boolean tracking, zooming;
    private float lastDist, lastMidX, lastMidY;
    private final float[] tmp = new float[2];

    PageCanvasView(Context c) {
        super(c);
    }

    void setImage(Bitmap b, int rotation) {
        image = b;
        rot = ((rotation % 360) + 360) % 360;
        updateMatrix();
        invalidate();
    }

    /** Thay ảnh bằng ảnh cùng kích thước (kết quả xử lý mới), giữ nguyên mức phóng. */
    void swapImage(Bitmap b) {
        image = b;
        updateMatrix();
        invalidate();
    }

    boolean hasImage() {
        return image != null && !image.isRecycled();
    }

    int imageW() {
        return image == null ? 1 : image.getWidth();
    }

    int imageH() {
        return image == null ? 1 : image.getHeight();
    }

    /** Bề ngang trang như đang hiển thị (đã tính góc xoay), theo pixel ảnh. */
    int shownW() {
        return rot % 180 == 0 ? imageW() : imageH();
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    protected void updateMatrix() {
        if (image == null || getWidth() == 0 || getHeight() == 0) return;
        float iw = image.getWidth(), ih = image.getHeight();
        boolean swap = rot % 180 != 0;
        float rw = swap ? ih : iw, rh = swap ? iw : ih;
        float pad = Ui.dp(12);
        float fit = Math.min((getWidth() - 2 * pad) / rw, (getHeight() - 2 * pad) / rh);
        if (!(fit > 0)) fit = 0.01f;
        float s = fit * zoom;
        float maxX = Math.max(0f, (rw * s - getWidth()) / 2f + pad), maxY = Math.max(0f, (rh * s - getHeight()) / 2f + pad);
        panX = clamp(panX, -maxX, maxX);
        panY = clamp(panY, -maxY, maxY);
        mtx.reset();
        mtx.postTranslate(-iw / 2f, -ih / 2f);
        mtx.postRotate(rot);
        mtx.postScale(s, s);
        mtx.postTranslate(getWidth() / 2f + panX, getHeight() / 2f + panY);
        mtx.invert(inv);
        scale = s;
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        updateMatrix();
    }

    @Override
    protected void onDraw(Canvas c) {
        if (image == null || image.isRecycled()) return;
        c.save();
        c.concat(mtx);
        c.drawBitmap(image, 0, 0, bmpPaint);
        drawPage(c);
        c.restore();
        drawOverlay(c);
    }

    /** Vẽ thêm lên trang, trong hệ toạ độ ảnh. */
    protected abstract void drawPage(Canvas c);

    /** Vẽ thêm trong hệ toạ độ màn hình (khung chọn, con trỏ bút...). */
    protected void drawOverlay(Canvas c) {
    }

    /** Một ngón chạm xuống tại điểm ảnh (ix, iy) / điểm màn hình (sx, sy). */
    protected abstract void onDown(float ix, float iy, float sx, float sy);

    protected abstract void onMove(float ix, float iy, float sx, float sy);

    /** cancelled: thao tác bị huỷ (đặt thêm ngón thứ hai để phóng to, hoặc hệ thống huỷ). */
    protected abstract void onUp(float ix, float iy, boolean cancelled);

    /** Điểm ảnh -> điểm màn hình. */
    protected void toScreen(float ix, float iy, float[] out) {
        out[0] = ix;
        out[1] = iy;
        mtx.mapPoints(out);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (image == null || image.isRecycled()) return true;
        int a = e.getActionMasked();
        tmp[0] = e.getX();
        tmp[1] = e.getY();
        inv.mapPoints(tmp);
        switch (a) {
            case MotionEvent.ACTION_DOWN:
                tracking = true;
                zooming = false;
                onDown(tmp[0], tmp[1], e.getX(), e.getY());
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                if (tracking) {
                    tracking = false;
                    onUp(tmp[0], tmp[1], true);
                }
                if (e.getPointerCount() >= 2) {
                    zooming = true;
                    lastDist = dist(e);
                    lastMidX = (e.getX(0) + e.getX(1)) / 2f;
                    lastMidY = (e.getY(0) + e.getY(1)) / 2f;
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (zooming && e.getPointerCount() >= 2) {
                    float d = dist(e);
                    float mx = (e.getX(0) + e.getX(1)) / 2f, my = (e.getY(0) + e.getY(1)) / 2f;
                    if (lastDist > 10f && d > 10f) {
                        float nz = clamp(zoom * d / lastDist, 1f, 6f);
                        float f = nz / zoom;
                        // giữ điểm giữa hai ngón đứng yên khi phóng
                        float fx = mx - getWidth() / 2f, fy = my - getHeight() / 2f;
                        panX = (panX - fx) * f + fx;
                        panY = (panY - fy) * f + fy;
                        zoom = nz;
                    }
                    panX += mx - lastMidX;
                    panY += my - lastMidY;
                    lastDist = d;
                    lastMidX = mx;
                    lastMidY = my;
                    updateMatrix();
                    invalidate();
                } else if (tracking) {
                    onMove(tmp[0], tmp[1], e.getX(), e.getY());
                }
                break;
            case MotionEvent.ACTION_POINTER_UP:
                // còn một ngón: không coi là nét vẽ mới cho tới khi nhấc hết tay
                if (e.getPointerCount() - 1 >= 2) {
                    int up = e.getActionIndex();
                    int i0 = up == 0 ? 1 : 0, i1 = up <= 1 ? 2 : 1;
                    lastDist = (float) Math.hypot(e.getX(i0) - e.getX(i1), e.getY(i0) - e.getY(i1));
                    lastMidX = (e.getX(i0) + e.getX(i1)) / 2f;
                    lastMidY = (e.getY(i0) + e.getY(i1)) / 2f;
                } else {
                    lastDist = 0f;
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (tracking) onUp(tmp[0], tmp[1], a == MotionEvent.ACTION_CANCEL);
                tracking = false;
                zooming = false;
                break;
            default:
                break;
        }
        return true;
    }

    private static float dist(MotionEvent e) {
        return (float) Math.hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1));
    }
}
