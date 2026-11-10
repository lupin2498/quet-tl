package vn.quettailieu.scanner;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;

import java.util.ArrayList;

/**
 * Vùng thao tác của "Đánh dấu": vẽ nét (Brush / Khảm) hoặc chọn - kéo - co giãn - xoá chữ và chữ ký.
 * Các đánh dấu được vẽ bằng đúng MarkRender dùng cho ảnh kết quả nên thấy sao lưu vậy.
 */
final class MarkView extends PageCanvasView {
    static final int T_MOVE = 0, T_BRUSH = 1, T_MOSAIC = 2;

    interface Listener {
        /** Gọi ngay TRƯỚC khi danh sách đánh dấu bị thay đổi (để lưu bước hoàn tác). */
        void onBeforeChange();

        void onChanged();

        void onSelected(int index);

        /** Chạm vào mục đang được chọn mà không kéo (để sửa nội dung chữ). */
        void onTapSelected(int index);
    }

    final ArrayList<Edits.Mark> marks = new ArrayList<>();
    private MarkRender render;
    private Listener listener;
    private int tool = T_MOVE;
    private int color = 0xFF1A1A1A;
    private float brushDp = 8f;
    private int selected = -1;

    private static final int M_NONE = 0, M_MOVE = 1, M_SCALE = 2, M_DELETE = 3, M_STROKE = 4;
    private int mode = M_NONE;
    private boolean moved, wasSelected;
    private float downIx, downIy, downSx, downSy, startX, startY, startSize, startDist;
    private float[] pts = new float[256];
    private int n;
    private final Edits.Mark liveMark = new Edits.Mark();
    private final float[] corners = new float[8];
    private final float[] pt = new float[2];
    private final Paint frame = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handle = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glyph = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    MarkView(Context c) {
        super(c);
        frame.setStyle(Paint.Style.STROKE);
        frame.setStrokeWidth(Ui.dpf(1.5f));
        frame.setColor(Ui.ACCENT);
        frame.setPathEffect(new DashPathEffect(new float[]{Ui.dpf(6), Ui.dpf(4)}, 0));
        glyph.setStyle(Paint.Style.STROKE);
        glyph.setStrokeWidth(Ui.dpf(2));
        glyph.setStrokeCap(Paint.Cap.ROUND);
        glyph.setColor(0xFFFFFFFF);
    }

    void setRender(MarkRender r) {
        render = r;
    }

    void setListener(Listener l) {
        listener = l;
    }

    void setTool(int t) {
        tool = t;
        if (t != T_MOVE) select(-1);
    }

    void setColor(int c) {
        color = c;
    }

    void setBrushDp(float dp) {
        brushDp = dp;
    }

    int selected() {
        return selected;
    }

    Edits.Mark selectedMark() {
        return selected >= 0 && selected < marks.size() ? marks.get(selected) : null;
    }

    void select(int i) {
        if (i >= marks.size()) i = -1;
        if (selected == i) return;
        selected = i;
        if (listener != null) listener.onSelected(i);
        invalidate();
    }

    /** Thay toàn bộ danh sách (hoàn tác / làm lại / nạp lại). */
    void setMarks(ArrayList<Edits.Mark> list) {
        marks.clear();
        marks.addAll(list);
        selected = -1;
        mode = M_NONE;
        if (listener != null) listener.onSelected(-1);
        invalidate();
    }

    private static boolean movable(Edits.Mark m) {
        return m.type == Edits.Mark.TEXT || m.type == Edits.Mark.SIGN;
    }

    private float[] block(Edits.Mark m) {
        if (render == null) return new float[]{10f, 10f};
        return m.type == Edits.Mark.SIGN ? render.signBlock(m, imageW()) : render.textBlock(m, imageW());
    }

    /** 4 góc của mục (theo pixel ảnh): trên-trái, trên-phải, dưới-phải, dưới-trái. */
    private void cornersOf(Edits.Mark m, float[] out) {
        float[] b = block(m);
        float hw = b[0] / 2f + Ui.dp(4) / Math.max(0.01f, scale), hh = b[1] / 2f + Ui.dp(4) / Math.max(0.01f, scale);
        double a = Math.toRadians(m.angle);
        float cs = (float) Math.cos(a), sn = (float) Math.sin(a);
        float cx = m.x * imageW(), cy = m.y * imageH();
        float[] dx = {-hw, hw, hw, -hw}, dy = {-hh, -hh, hh, hh};
        for (int i = 0; i < 4; i++) {
            out[2 * i] = cx + dx[i] * cs - dy[i] * sn;
            out[2 * i + 1] = cy + dx[i] * sn + dy[i] * cs;
        }
    }

    private boolean hit(Edits.Mark m, float ix, float iy) {
        float[] b = block(m);
        float pad = Ui.dp(10) / Math.max(0.01f, scale);
        double a = Math.toRadians(m.angle);
        float cs = (float) Math.cos(a), sn = (float) Math.sin(a);
        float px = ix - m.x * imageW(), py = iy - m.y * imageH();
        float lx = px * cs + py * sn, ly = -px * sn + py * cs;
        return Math.abs(lx) <= b[0] / 2f + pad && Math.abs(ly) <= b[1] / 2f + pad;
    }

    private void add(float x, float y) {
        if (n * 2 + 2 > pts.length) {
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

    private float brushNorm() {
        return Math.max(1f, Ui.dp(brushDp) / Math.max(0.01f, scale)) / imageW();
    }

    @Override
    protected void onDown(float ix, float iy, float sx, float sy) {
        downIx = ix;
        downIy = iy;
        downSx = sx;
        downSy = sy;
        moved = false;
        wasSelected = false;
        mode = M_NONE;
        if (tool == T_BRUSH || tool == T_MOSAIC) {
            mode = M_STROKE;
            n = 0;
            add(ix, iy);
            invalidate();
            return;
        }
        Edits.Mark sel = selectedMark();
        if (sel != null && movable(sel)) {
            cornersOf(sel, corners);
            toScreen(corners[4], corners[5], pt);
            if (Math.hypot(sx - pt[0], sy - pt[1]) <= Ui.dp(26)) {
                mode = M_SCALE;
                toScreen(sel.x * imageW(), sel.y * imageH(), pt);
                startDist = Math.max(Ui.dp(8), (float) Math.hypot(sx - pt[0], sy - pt[1]));
                startSize = sel.size;
                return;
            }
            toScreen(corners[0], corners[1], pt);
            if (Math.hypot(sx - pt[0], sy - pt[1]) <= Ui.dp(24)) {
                mode = M_DELETE;
                return;
            }
        }
        int found = -1;
        for (int i = marks.size() - 1; i >= 0; i--) {
            Edits.Mark m = marks.get(i);
            if (movable(m) && hit(m, ix, iy)) {
                found = i;
                break;
            }
        }
        if (found >= 0) {
            wasSelected = found == selected;
            select(found);
            Edits.Mark m = marks.get(found);
            mode = M_MOVE;
            startX = m.x;
            startY = m.y;
        } else {
            select(-1);
        }
    }

    @Override
    protected void onMove(float ix, float iy, float sx, float sy) {
        if (mode == M_STROKE) {
            float dx = ix - pts[2 * n - 2], dy = iy - pts[2 * n - 1];
            if (dx * dx + dy * dy >= 1.5f) add(ix, iy);
            invalidate();
            return;
        }
        Edits.Mark sel = selectedMark();
        if (sel == null) return;
        if (!moved) {
            if (Math.hypot(sx - downSx, sy - downSy) < Ui.dp(6)) return;
            if (mode == M_DELETE) {
                mode = M_NONE;
                return;
            }
            moved = true;
            if (listener != null) listener.onBeforeChange();
        }
        if (mode == M_MOVE) {
            sel.x = clampF(startX + (ix - downIx) / imageW(), -0.2f, 1.2f);
            sel.y = clampF(startY + (iy - downIy) / imageH(), -0.2f, 1.2f);
            invalidate();
        } else if (mode == M_SCALE) {
            toScreen(sel.x * imageW(), sel.y * imageH(), pt);
            float d = (float) Math.hypot(sx - pt[0], sy - pt[1]);
            float lo = sel.type == Edits.Mark.SIGN ? 0.05f : 0.012f, hi = sel.type == Edits.Mark.SIGN ? 1.6f : 0.6f;
            sel.size = clampF(startSize * d / startDist, lo, hi);
            invalidate();
        }
    }

    private static float clampF(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    @Override
    protected void onUp(float ix, float iy, boolean cancelled) {
        int m = mode;
        mode = M_NONE;
        if (m == M_STROKE) {
            int count = n;
            n = 0;
            invalidate();
            if (cancelled || count == 0 || !hasImage()) return;
            Edits.Mark mk = new Edits.Mark();
            mk.type = tool == T_MOSAIC ? Edits.Mark.MOSAIC : Edits.Mark.BRUSH;
            mk.color = color;
            mk.width = brushNorm() * (tool == T_MOSAIC ? 2.2f : 1f);
            mk.pts = new float[count * 2];
            float w = imageW(), h = imageH();
            for (int i = 0; i < count; i++) {
                mk.pts[2 * i] = pts[2 * i] / w;
                mk.pts[2 * i + 1] = pts[2 * i + 1] / h;
            }
            if (listener != null) listener.onBeforeChange();
            marks.add(mk);
            if (listener != null) listener.onChanged();
            return;
        }
        if (m == M_DELETE) {
            if (cancelled || selected < 0 || selected >= marks.size()) return;
            if (listener != null) listener.onBeforeChange();
            marks.remove(selected);
            selected = -1;
            if (listener != null) {
                listener.onSelected(-1);
                listener.onChanged();
            }
            invalidate();
            return;
        }
        if (moved) {
            if (listener != null) listener.onChanged();
        } else if (m == M_MOVE && wasSelected && !cancelled && listener != null) {
            listener.onTapSelected(selected);
        }
    }

    @Override
    protected void drawPage(Canvas c) {
        if (render == null) return;
        render.draw(c, imageW(), imageH(), marks, image);
        if (mode == M_STROKE && n > 0) {
            liveMark.type = tool == T_MOSAIC ? Edits.Mark.MOSAIC : Edits.Mark.BRUSH;
            liveMark.color = color;
            liveMark.width = brushNorm() * (tool == T_MOSAIC ? 2.2f : 1f);
            float[] p = new float[n * 2];
            float w = imageW(), h = imageH();
            for (int i = 0; i < n; i++) {
                p[2 * i] = pts[2 * i] / w;
                p[2 * i + 1] = pts[2 * i + 1] / h;
            }
            liveMark.pts = p;
            render.drawOne(c, imageW(), imageH(), liveMark, image);
        }
    }

    @Override
    protected void drawOverlay(Canvas c) {
        Edits.Mark sel = selectedMark();
        if (sel == null || !movable(sel)) return;
        cornersOf(sel, corners);
        float[] s = new float[8];
        for (int i = 0; i < 4; i++) {
            toScreen(corners[2 * i], corners[2 * i + 1], pt);
            s[2 * i] = pt[0];
            s[2 * i + 1] = pt[1];
        }
        path.rewind();
        path.moveTo(s[0], s[1]);
        path.lineTo(s[2], s[3]);
        path.lineTo(s[4], s[5]);
        path.lineTo(s[6], s[7]);
        path.close();
        c.drawPath(path, frame);
        float r = Ui.dp(11), g = Ui.dp(4.5f);
        // nút xoá (góc trên-trái)
        handle.setColor(Ui.RED);
        c.drawCircle(s[0], s[1], r, handle);
        c.drawLine(s[0] - g, s[1] - g, s[0] + g, s[1] + g, glyph);
        c.drawLine(s[0] - g, s[1] + g, s[0] + g, s[1] - g, glyph);
        // nút co giãn (góc dưới-phải)
        handle.setColor(Ui.ACCENT);
        c.drawCircle(s[4], s[5], r, handle);
        c.drawLine(s[4] - g, s[5] - g, s[4] + g, s[5] + g, glyph);
        c.drawLine(s[4] + g, s[5] + g, s[4] + g - Ui.dp(4), s[5] + g, glyph);
        c.drawLine(s[4] + g, s[5] + g, s[4] + g, s[5] + g - Ui.dp(4), glyph);
        c.drawLine(s[4] - g, s[5] - g, s[4] - g + Ui.dp(4), s[5] - g, glyph);
        c.drawLine(s[4] - g, s[5] - g, s[4] - g, s[5] - g + Ui.dp(4), glyph);
    }
}
