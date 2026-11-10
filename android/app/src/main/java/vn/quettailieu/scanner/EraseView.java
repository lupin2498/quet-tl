package vn.quettailieu.scanner;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;

import vn.quettailieu.scanner.core.Retouch;

/** Vùng thao tác của "Xóa thông minh": tô bằng bút / kéo hình chữ nhật, báo lại từng thao tác đã vẽ xong. */
final class EraseView extends PageCanvasView {
    interface Listener {
        void onOp(Retouch.Op op);
    }

    private Listener listener;
    private int tool = Retouch.PEN;
    private float brushDp = 22f;
    private float[] pts = new float[256];
    private int n;
    private boolean drawing;
    private float curX, curY;
    private final Path path = new Path();
    private final Paint live = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);

    EraseView(Context c) {
        super(c);
        live.setStrokeCap(Paint.Cap.ROUND);
        live.setStrokeJoin(Paint.Join.ROUND);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(Ui.dpf(1.5f));
        ring.setColor(0xCCFFFFFF);
        ring.setShadowLayer(Ui.dpf(2), 0, 0, 0x99000000);
    }

    void setListener(Listener l) {
        listener = l;
    }

    void setTool(int t) {
        tool = t;
    }

    int tool() {
        return tool;
    }

    void setBrushDp(float dp) {
        brushDp = dp;
    }

    /** Bề rộng nét theo pixel ảnh ở mức phóng hiện tại. */
    private float brushPx() {
        return Math.max(1f, Ui.dp(brushDp) / Math.max(0.01f, scale));
    }

    private void add(float x, float y) {
        if (n * 2 + 2 > pts.length) {
            // nét quá dài: giữ số điểm trong giới hạn bằng cách bỏ bớt điểm xen kẽ
            if (pts.length >= 8192) {
                int m = 0;
                for (int i = 0; i < n; i += 2) {
                    pts[2 * m] = pts[2 * i];
                    pts[2 * m + 1] = pts[2 * i + 1];
                    m++;
                }
                n = m;
            } else {
                float[] np = new float[pts.length * 2];
                System.arraycopy(pts, 0, np, 0, n * 2);
                pts = np;
            }
        }
        pts[2 * n] = x;
        pts[2 * n + 1] = y;
        n++;
    }

    @Override
    protected void onDown(float ix, float iy, float sx, float sy) {
        drawing = true;
        n = 0;
        add(ix, iy);
        curX = sx;
        curY = sy;
        invalidate();
    }

    @Override
    protected void onMove(float ix, float iy, float sx, float sy) {
        if (!drawing) return;
        curX = sx;
        curY = sy;
        if (tool == Retouch.RECT) {
            n = 1;
            add(ix, iy);
        } else {
            float dx = ix - pts[2 * n - 2], dy = iy - pts[2 * n - 1];
            if (dx * dx + dy * dy >= 1.5f) add(ix, iy);
        }
        invalidate();
    }

    @Override
    protected void onUp(float ix, float iy, boolean cancelled) {
        if (!drawing) return;
        drawing = false;
        int count = n;
        n = 0;
        invalidate();
        if (cancelled || count == 0 || listener == null || !hasImage()) return;
        float w = imageW(), h = imageH();
        Retouch.Op op = new Retouch.Op();
        op.kind = tool;
        if (tool == Retouch.RECT) {
            if (count < 2) return;
            float x0 = Math.min(pts[0], pts[2]), x1 = Math.max(pts[0], pts[2]);
            float y0 = Math.min(pts[1], pts[3]), y1 = Math.max(pts[1], pts[3]);
            // hình quá nhỏ (chạm nhầm) thì bỏ qua
            if ((x1 - x0) * scale < Ui.dp(6) || (y1 - y0) * scale < Ui.dp(6)) return;
            op.width = 0f;
            op.pts = new float[]{x0 / w, y0 / h, x1 / w, y0 / h, x1 / w, y1 / h, x0 / w, y1 / h};
        } else {
            op.width = brushPx() / w;
            op.pts = new float[count * 2];
            for (int i = 0; i < count; i++) {
                op.pts[2 * i] = pts[2 * i] / w;
                op.pts[2 * i + 1] = pts[2 * i + 1] / h;
            }
        }
        listener.onOp(op);
    }

    @Override
    protected void drawPage(Canvas c) {
        if (!drawing || n == 0) return;
        boolean plain = tool == Retouch.PEN || tool == Retouch.RECT;
        live.setColor(plain ? 0xE6FFFFFF : 0x6600B596);
        if (tool == Retouch.RECT) {
            if (n < 2) return;
            live.setStyle(Paint.Style.FILL);
            c.drawRect(Math.min(pts[0], pts[2]), Math.min(pts[1], pts[3]), Math.max(pts[0], pts[2]), Math.max(pts[1], pts[3]), live);
            return;
        }
        live.setStyle(Paint.Style.STROKE);
        live.setStrokeWidth(brushPx());
        path.rewind();
        path.moveTo(pts[0], pts[1]);
        if (n == 1) path.lineTo(pts[0] + 0.01f, pts[1] + 0.01f);
        for (int i = 1; i < n; i++) path.lineTo(pts[2 * i], pts[2 * i + 1]);
        c.drawPath(path, live);
    }

    @Override
    protected void drawOverlay(Canvas c) {
        if (drawing && tool != Retouch.RECT) c.drawCircle(curX, curY, Ui.dp(brushDp) / 2f, ring);
    }
}
