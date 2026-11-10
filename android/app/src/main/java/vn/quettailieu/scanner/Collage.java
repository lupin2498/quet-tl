package vn.quettailieu.scanner;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * "Cắt dán": ghép nhiều trang lên một tờ A4 (2, 4, 6 hoặc 8 trang mỗi tờ) và lưu thành tài liệu mới.
 * Mỗi tờ ghép là một trang bình thường (ảnh gốc = ảnh ghép, không cắt, không lọc).
 * Bố cục 2 trang: trang dọc nằm cạnh nhau trên tờ A4 ngang, trang ngang xếp chồng trên tờ A4 dọc.
 */
final class Collage {
    private Collage() {}

    static final String[] LAYOUT_NAMES = {"2 trang mỗi tờ", "4 trang mỗi tờ", "6 trang mỗi tờ (thẻ, giấy tờ nhỏ)", "8 trang mỗi tờ (thẻ, giấy tờ nhỏ)"};
    private static final int[][] LAYOUTS = {{1, 2}, {2, 2}, {2, 3}, {2, 4}};

    /** A4 ở 300 dpi. */
    private static final int SHEET_W = 2480, SHEET_H = 3508;

    static int perSheet(int layout) {
        return LAYOUTS[layout][0] * LAYOUTS[layout][1];
    }

    /** Tờ A4 xoay ngang? Chỉ với bố cục 2 trang khi phần lớn trang là trang dọc. */
    static boolean landscapeSheet(int layout, int portraitPages, int totalPages) {
        return layout == 0 && portraitPages * 2 >= totalPages;
    }

    /** Vị trí ô thứ k trên tờ W x H chia cols x rows: {trái, trên, phải, dưới}. */
    static float[] cell(int cols, int rows, int k, float W, float H) {
        float margin = Math.min(W, H) * 0.04f, gap = Math.min(W, H) * 0.025f;
        float cw = (W - 2 * margin - (cols - 1) * gap) / cols, ch = (H - 2 * margin - (rows - 1) * gap) / rows;
        int c = k % cols, r = k / cols;
        float l = margin + c * (cw + gap), t = margin + r * (ch + gap);
        return new float[]{l, t, l + cw, t + ch};
    }

    /**
     * Ghép các ảnh trang (file JPEG kết quả) thành tài liệu mới. Chạy ở luồng nền.
     * Trả về tài liệu đã lưu (chưa có ảnh kết quả; màn hình tài liệu sẽ tự xử lý).
     */
    static DocStore.Doc make(Context ctx, List<File> pages, String title, int layout) throws IOException {
        if (pages == null || pages.isEmpty()) throw new IOException("Tài liệu chưa có trang nào");
        int per = perSheet(layout);
        int portrait = 0;
        for (File f : pages) {
            int[] sz = ImageUtil.orientedSize(f.getPath());
            if (sz != null && sz[1] >= sz[0]) portrait++;
        }
        boolean landscape = landscapeSheet(layout, portrait, pages.size());
        final int cols = landscape ? 2 : LAYOUTS[layout][0], rows = landscape ? 1 : LAYOUTS[layout][1];
        final int SW = landscape ? SHEET_H : SHEET_W, SH = landscape ? SHEET_W : SHEET_H;
        DocStore.Doc d = DocStore.create(ctx, true);
        try {
            Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
            Bitmap sheet;
            try {
                sheet = Bitmap.createBitmap(SW, SH, Bitmap.Config.ARGB_8888);
            } catch (OutOfMemoryError e) {
                throw new IOException("Không đủ bộ nhớ để ghép trang");
            }
            Canvas c = new Canvas(sheet);
            int sheets = (pages.size() + per - 1) / per;
            for (int s = 0; s < sheets; s++) {
                c.drawColor(0xFFFFFFFF);
                for (int k = 0; k < per; k++) {
                    int idx = s * per + k;
                    if (idx >= pages.size()) break;
                    float[] r = cell(cols, rows, k, SW, SH);
                    float cw = r[2] - r[0], ch = r[3] - r[1];
                    Bitmap b = ImageUtil.decodeSampled(pages.get(idx).getPath(), (int) Math.max(cw, ch));
                    if (b == null) continue;
                    float k2 = Math.min(cw / b.getWidth(), ch / b.getHeight());
                    float w = b.getWidth() * k2, h = b.getHeight() * k2;
                    float l = r[0] + (cw - w) / 2f, t = r[1] + (ch - h) / 2f;
                    c.drawBitmap(b, null, new RectF(l, t, l + w, t + h), paint);
                    b.recycle();
                }
                File tmp = new File(d.dir, "collage_" + s + ".tmp.jpg");
                ImageUtil.saveJpeg(sheet, tmp, 92);
                float[] quad = {0, 0, SW, 0, SW, SH, 0, SH};
                DocStore.Page p = DocStore.addPage(d, tmp, new int[]{SW, SH}, quad, false, 0);
                p.filter = vn.quettailieu.scanner.core.Enhancer.ORIGINAL;
            }
            sheet.recycle();
            d.title = title;
            d.draft = false;
            d.modified = System.currentTimeMillis();
            DocStore.save(d);
            return d;
        } catch (IOException e) {
            DocStore.delete(d);
            throw e;
        } catch (RuntimeException e) {
            DocStore.delete(d);
            throw e;
        }
    }

    /** Danh sách file ảnh kết quả của tài liệu (phải đã xử lý xong). */
    static ArrayList<File> sources(DocStore.Doc d) throws IOException {
        return Exporter.pageFiles(d);
    }
}
