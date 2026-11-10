package vn.quettailieu.scanner;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;

import java.io.File;
import java.util.HashMap;
import java.util.List;

/**
 * Vẽ lớp "Đánh dấu" (chữ ký, văn bản, nét vẽ, khảm, hình mờ) và tiêu đề trang lên Canvas.
 * Dùng chung cho ảnh kết quả (độ phân giải đầy đủ) và màn hình chỉnh sửa (ảnh xem trước),
 * nên mọi kích thước đều tính theo bề ngang ảnh W.
 */
final class MarkRender {
    private final File dir;
    private final HashMap<String, Bitmap> signs = new HashMap<>();
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint bmp = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint mosaic = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private Bitmap mosaicSrc, mosaicSmall;
    private int mosaicW, mosaicH;

    /** dir: thư mục tài liệu (chứa file chữ ký). */
    MarkRender(File dir) {
        this.dir = dir;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        mosaic.setStyle(Paint.Style.STROKE);
        mosaic.setStrokeCap(Paint.Cap.ROUND);
        mosaic.setStrokeJoin(Paint.Join.ROUND);
        mosaic.setFilterBitmap(false);
        text.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
    }

    void release() {
        for (Bitmap b : signs.values()) if (b != null) b.recycle();
        signs.clear();
        if (mosaicSmall != null) {
            mosaicSmall.recycle();
            mosaicSmall = null;
        }
        mosaicSrc = null;
    }

    /** Vẽ toàn bộ đánh dấu lên canvas có hệ toạ độ ảnh W x H. base: ảnh nền (đã lọc) để làm khảm. */
    void draw(Canvas c, int W, int H, List<Edits.Mark> marks, Bitmap base) {
        if (marks == null) return;
        // khảm lấy màu từ ảnh nền lúc CHƯA vẽ đánh dấu nào (canvas có thể đang vẽ thẳng lên chính ảnh nền),
        // để ảnh kết quả giống hệt màn hình chỉnh sửa
        if (base != null && !base.isRecycled()) {
            for (int i = 0; i < marks.size(); i++) {
                Edits.Mark m = marks.get(i);
                if (m != null && m.type == Edits.Mark.MOSAIC) {
                    prepareMosaic(base, W, H);
                    break;
                }
            }
        }
        for (int i = 0; i < marks.size(); i++) drawOne(c, W, H, marks.get(i), base);
    }

    void drawOne(Canvas c, int W, int H, Edits.Mark m, Bitmap base) {
        if (m == null) return;
        switch (m.type) {
            case Edits.Mark.BRUSH:
                stroke.setColor(m.color);
                stroke.setStrokeWidth(Math.max(1f, m.width * W));
                drawStroke(c, W, H, m.pts, stroke);
                break;
            case Edits.Mark.MOSAIC:
                if (base == null || base.isRecycled()) break;
                prepareMosaic(base, W, H);
                if (mosaicSmall == null) break;
                mosaic.setStrokeWidth(Math.max(2f, m.width * W));
                drawStroke(c, W, H, m.pts, mosaic);
                break;
            case Edits.Mark.TEXT:
                drawText(c, W, H, m);
                break;
            case Edits.Mark.SIGN:
                drawSign(c, W, H, m);
                break;
            case Edits.Mark.WM:
                drawWatermark(c, W, H, m);
                break;
            default:
                break;
        }
    }

    private void drawStroke(Canvas c, int W, int H, float[] pts, Paint p) {
        if (pts == null || pts.length < 2) return;
        path.rewind();
        float x0 = pts[0] * W, y0 = pts[1] * H;
        path.moveTo(x0, y0);
        if (pts.length < 4) {
            path.lineTo(x0 + 0.01f, y0 + 0.01f);
        } else {
            for (int i = 2; i + 1 < pts.length; i += 2) path.lineTo(pts[i] * W, pts[i + 1] * H);
        }
        c.drawPath(path, p);
    }

    /** Ảnh khảm: thu nhỏ ảnh nền (lấy trung bình) rồi phóng lại không nội suy -> các ô vuông. */
    private void prepareMosaic(Bitmap base, int W, int H) {
        if (mosaicSrc == base && mosaicSmall != null && mosaicW == W && mosaicH == H) return;
        if (mosaicSmall != null) mosaicSmall.recycle();
        mosaicSmall = null;
        mosaicSrc = base;
        mosaicW = W;
        mosaicH = H;
        int cells = 72;
        float blk = Math.max(W, H) / (float) cells;
        int sw = Math.max(1, Math.round(W / blk)), sh = Math.max(1, Math.round(H / blk));
        try {
            mosaicSmall = Bitmap.createScaledBitmap(base, sw, sh, true);
            if (mosaicSmall == base) mosaicSmall = base.copy(Bitmap.Config.ARGB_8888, false);
        } catch (OutOfMemoryError e) {
            mosaicSmall = null;
            return;
        }
        BitmapShader sh2 = new BitmapShader(mosaicSmall, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        Matrix m = new Matrix();
        m.setScale(W / (float) sw, H / (float) sh);
        sh2.setLocalMatrix(m);
        mosaic.setShader(sh2);
    }

    static String[] lines(String s) {
        if (s == null) return new String[]{""};
        return s.split("\n", -1);
    }

    /** Kích thước khối chữ {rộng, cao} theo pixel ảnh. */
    float[] textBlock(Edits.Mark m, int W) {
        float lh = Math.max(4f, m.size * W);
        text.setTextSize(lh * 0.8f);
        String[] ls = lines(m.text);
        float bw = 0;
        for (String l : ls) bw = Math.max(bw, text.measureText(l));
        return new float[]{Math.max(bw, lh * 0.5f), lh * ls.length};
    }

    private void drawText(Canvas c, int W, int H, Edits.Mark m) {
        if (m.text == null || m.text.length() == 0) return;
        float[] b = textBlock(m, W);
        float lh = Math.max(4f, m.size * W);
        text.setColor(m.color);
        text.setTextSize(lh * 0.8f);
        float cx = m.x * W, cy = m.y * H;
        c.save();
        c.rotate(m.angle, cx, cy);
        String[] ls = lines(m.text);
        float top = cy - b[1] / 2f, left = cx - b[0] / 2f;
        Paint.FontMetrics fm = text.getFontMetrics();
        for (int i = 0; i < ls.length; i++) {
            float base = top + lh * i + (lh - (fm.descent - fm.ascent)) / 2f - fm.ascent;
            c.drawText(ls[i], left, base, text);
        }
        c.restore();
    }

    Bitmap sign(String file) {
        if (file == null || file.length() == 0 || dir == null) return null;
        if (signs.containsKey(file)) return signs.get(file);
        Bitmap b = null;
        try {
            // tên file chỉ gồm ký tự an toàn (do ứng dụng đặt); vẫn chặn đường dẫn lạ
            if (file.indexOf('/') < 0 && file.indexOf("..") < 0) b = BitmapFactory.decodeFile(new File(dir, file).getPath());
        } catch (Throwable ignored) {
        }
        signs.put(file, b);
        return b;
    }

    /** Kích thước chữ ký {rộng, cao} theo pixel ảnh. */
    float[] signBlock(Edits.Mark m, int W) {
        Bitmap b = sign(m.file);
        float w = Math.max(8f, m.size * W);
        float ratio = b == null || b.getWidth() <= 0 ? 0.4f : b.getHeight() / (float) b.getWidth();
        return new float[]{w, w * ratio};
    }

    private void drawSign(Canvas c, int W, int H, Edits.Mark m) {
        Bitmap b = sign(m.file);
        if (b == null || b.isRecycled()) return;
        float[] s = signBlock(m, W);
        float cx = m.x * W, cy = m.y * H;
        c.save();
        c.rotate(m.angle, cx, cy);
        c.drawBitmap(b, null, new RectF(cx - s[0] / 2f, cy - s[1] / 2f, cx + s[0] / 2f, cy + s[1] / 2f), bmp);
        c.restore();
    }

    private void drawWatermark(Canvas c, int W, int H, Edits.Mark m) {
        if (m.text == null || m.text.trim().length() == 0) return;
        String s = m.text.replace('\n', ' ');
        float lh = Math.max(6f, m.size * W);
        text.setColor(m.color);
        text.setTextSize(lh);
        float tw = Math.max(lh, text.measureText(s));
        float stepX = tw + lh * 2.2f, stepY = lh * 4.4f;
        float diag = (float) Math.hypot(W, H);
        c.save();
        c.clipRect(0, 0, W, H);
        c.rotate(-30f, W / 2f, H / 2f);
        int row = 0;
        for (float y = H / 2f - diag / 2f; y < H / 2f + diag / 2f + stepY; y += stepY) {
            float shift = (row % 2 == 0) ? 0f : stepX / 2f;
            for (float x = W / 2f - diag / 2f - shift; x < W / 2f + diag / 2f; x += stepX) c.drawText(s, x, y, text);
            row++;
        }
        c.restore();
    }

    /** Tiêu đề trang: dòng 1 = chữ ở lề trên (giữa), dòng 2 = số trang ở lề dưới (giữa). Vẽ trên ảnh đã xoay. */
    static void drawHeader(Canvas c, int W, int H, String hdr) {
        if (hdr == null) return;
        int nl = hdr.indexOf('\n');
        String title = nl < 0 ? hdr : hdr.substring(0, nl);
        String num = nl < 0 ? "" : hdr.substring(nl + 1);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        p.setColor(0xFF3C3C3C);
        float size = Math.max(8f, Math.max(W, H) * 0.0155f);
        p.setTextSize(size);
        p.setTextAlign(Paint.Align.CENTER);
        float margin = Math.max(W, H) * 0.012f;
        if (title.length() > 0) {
            // chữ dài hơn bề ngang trang: co lại cho vừa
            float tw = p.measureText(title);
            float max = W * 0.92f;
            if (tw > max) p.setTextSize(Math.max(6f, size * max / tw));
            c.drawText(title, W / 2f, margin - p.getFontMetrics().ascent, p);
            p.setTextSize(size);
        }
        if (num.length() > 0) c.drawText(num, W / 2f, H - margin - p.getFontMetrics().descent, p);
    }
}
