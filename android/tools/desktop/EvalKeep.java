import vn.quettailieu.scanner.core.Enhancer;
import vn.quettailieu.scanner.core.Flatten;
import vn.quettailieu.scanner.core.Geom;

import javax.imageio.ImageIO;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Bộ lọc "Làm phẳng" giữ nội dung ở mọi độ phân giải và không nắn méo trang phẳng.
 * <ol>
 * <li>Ảnh chụp tài liệu PHẲNG (tests/images/doc_*.jpg nắn theo toạ độ góc thật gt.txt; page_flat.jpg) ở nhiều cỡ, cạnh
 * dài từ ~500 tới 1754 điểm ảnh (ảnh nhập / ảnh chụp độ phân giải thấp): so Làm phẳng (chỉ làm sạch, geometric = false)
 * với Tăng cường — trên trang phẳng mọi nét tối của Tăng cường (kể cả dấu thanh, chấm chữ i, dấu câu nhạt, đường kẻ bảng
 * mảnh) là nội dung thật nên phải còn. Đếm cụm nét bị xoá hẳn và điểm mực mất (cụm sát mép ảnh — mép bàn, Retouch.cleanEdges
 * xoá — không tính).</li>
 * <li>Trang phẳng tổng hợp (make_retouch_images.py; các thư mục truyền thêm, mỗi thư mục một cỡ): Làm phẳng không được nắn
 * (không có trường nắn, hoặc dịch tối đa <= 2 điểm ảnh như wv_plain của EvalFlatten) — trước đây vòng con dấu cạnh một
 * dòng chữ làm cong trang phẳng tới 17 điểm ảnh ở vài cỡ ảnh.</li>
 * </ol>
 * usage: java EvalKeep &lt;tests/images&gt; [thư-mục-rt ...]
 */
public class EvalKeep {
    /** Nét trên ảnh Tăng cường: tối hơn INK; còn giữ trên ảnh Làm phẳng: tối hơn KEEP. */
    static final int INK = 200, KEEP = 228;
    /** Ngưỡng đạt cho mỗi ảnh: số cụm nét bị xoá hẳn, tỷ lệ điểm mực mất. */
    static final int MAX_LOST = 6;
    static final double MAX_LOST_INK = 0.006;

    static int checks, fails;

    static void check(boolean ok, String what) {
        checks++;
        if (!ok) fails++;
        System.out.println((ok ? "PASS " : "FAIL ") + what);
    }

    static BufferedImage scale(BufferedImage src, int w, int h) {
        Image s = src.getScaledInstance(w, h, Image.SCALE_AREA_AVERAGING);
        BufferedImage o = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        o.getGraphics().drawImage(s, 0, 0, null);
        return o;
    }

    static int lum(int c) {
        return (299 * ((c >> 16) & 255) + 587 * ((c >> 8) & 255) + 114 * (c & 255)) / 1000;
    }

    static void keep(String name, int[] px, int w, int h) {
        int[] m = px.clone();
        Enhancer.apply(m, w, h, Enhancer.MAGIC, true);
        int[] f = px.clone();
        Enhancer.apply(f, w, h, Enhancer.FLATTEN, false);
        int n = w * h;
        boolean[] ink = new boolean[n];
        int inkPx = 0, lostPx = 0;
        for (int i = 0; i < n; i++) {
            int x = i % w, y = i / w;
            ink[i] = lum(m[i]) < INK && x > 3 && y > 3 && x < w - 4 && y < h - 4;
            if (ink[i]) {
                inkPx++;
                if (lum(f[i]) >= KEEP) lostPx++;
            }
        }
        // cụm nét (8 hướng) của Tăng cường không còn điểm nào trên ảnh Làm phẳng
        boolean[] seen = new boolean[n];
        int[] q = new int[n];
        int comps = 0, lost = 0;
        StringBuilder where = new StringBuilder();
        for (int s = 0; s < n; s++) {
            if (!ink[s] || seen[s]) continue;
            int head = 0, tail = 0;
            q[tail++] = s;
            seen[s] = true;
            boolean kept = false;
            int x0 = w, x1 = -1, y0 = h, y1 = -1;
            while (head < tail) {
                int i = q[head++];
                int x = i % w, y = i / w;
                x0 = Math.min(x0, x);
                x1 = Math.max(x1, x);
                y0 = Math.min(y0, y);
                y1 = Math.max(y1, y);
                if (lum(f[i]) < KEEP) kept = true;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int xx = x + dx, yy = y + dy;
                        if (xx < 0 || yy < 0 || xx >= w || yy >= h) continue;
                        int j = yy * w + xx;
                        if (ink[j] && !seen[j]) {
                            seen[j] = true;
                            q[tail++] = j;
                        }
                    }
                }
            }
            if (tail < 2) continue;
            comps++;
            if (!kept) {
                lost++;
                if (lost <= 4) where.append(String.format(Locale.US, " (%d,%d %dx%d)", x0, y0, x1 - x0 + 1, y1 - y0 + 1));
            }
        }
        double frac = inkPx == 0 ? 0 : lostPx / (double) inkPx;
        System.out.printf(Locale.US, "%-22s %4dx%-4d  %4d cụm nét, mất hẳn %d%s; điểm mực mất %d/%d (%.2f%%)%n", name, w, h, comps,
                lost, where, lostPx, inkPx, 100 * frac);
        check(lost <= MAX_LOST && frac <= MAX_LOST_INK, String.format(Locale.US,
                "%s: Làm phẳng giữ nét chữ / dấu / đường kẻ (mất hẳn %d cụm, %.2f%% điểm mực)", name, lost, 100 * frac));
    }

    public static void main(String[] a) throws Exception {
        long t0 = System.currentTimeMillis();
        File dir = new File(a[0]);
        Map<String, float[]> gt = new LinkedHashMap<>();
        for (String line : Files.readAllLines(new File(dir, "gt.txt").toPath())) {
            String[] t = line.trim().split("\\s+");
            if (t.length < 9) continue;
            float[] qd = new float[8];
            for (int i = 0; i < 8; i++) qd[i] = Float.parseFloat(t[i + 1]);
            gt.put(t[0], qd);
        }
        // 1) ảnh chụp tài liệu phẳng: ảnh gốc thu nhỏ (như ảnh nhập độ phân giải thấp) rồi nắn theo 4 góc ở cỡ tự nhiên
        for (Map.Entry<String, float[]> e : gt.entrySet()) {
            BufferedImage img = ImageIO.read(new File(dir, e.getKey()));
            for (float s : new float[]{1f, 0.85f, 0.7f, 0.6f, 0.5f}) {
                int W = Math.round(img.getWidth() * s), H = Math.round(img.getHeight() * s);
                BufferedImage src = s == 1f ? img : scale(img, W, H);
                int[] spx = src.getRGB(0, 0, W, H, null, 0, W);
                float[] qd = new float[8];
                for (int i = 0; i < 8; i++) qd[i] = e.getValue()[i] * s;
                int[] os = Geom.outputSize(qd, W, H, -1, true, 3508);
                int[] warp = Geom.warp(spx, W, H, qd, os[0], os[1]);
                keep(e.getKey().replace(".jpg", "") + "_" + Math.round(s * 100), warp, os[0], os[1]);
            }
        }
        BufferedImage pf = ImageIO.read(new File(dir, "page_flat.jpg"));
        for (int L : new int[]{1754, 1400, 1200, 1000, 900, 800, 700, 600, 520}) {
            float k = L / (float) Math.max(pf.getWidth(), pf.getHeight());
            int w = Math.round(pf.getWidth() * k), h = Math.round(pf.getHeight() * k);
            BufferedImage b = k == 1f ? pf : scale(pf, w, h);
            keep("page_flat_" + L, b.getRGB(0, 0, w, h, null, 0, w), w, h);
        }
        // 2) trang phẳng tổng hợp ở nhiều cỡ: không được nắn
        for (int i = 1; i < a.length; i++) {
            File[] fs = new File(a[i]).listFiles((d, nm) -> nm.startsWith("rt_") && nm.endsWith(".ppm"));
            if (fs == null || fs.length == 0) {
                check(false, a[i] + ": không có ảnh rt_*.ppm");
                continue;
            }
            Arrays.sort(fs);
            List<String> bent = new ArrayList<>();
            int w = 0, h = 0;
            for (File f : fs) {
                int[][] r = DumpCore.readPPM(f);
                w = r[0][0];
                h = r[0][1];
                Flatten.Field fl = Flatten.apply(r[1], w, h);
                if (fl != null && fl.maxShift > 2.0f) {
                    bent.add(String.format(Locale.US, "%s %.1f", f.getName().replace(".ppm", ""), fl.maxShift));
                }
            }
            check(bent.isEmpty(), String.format(Locale.US, "trang phẳng %dx%d (%d trang): Làm phẳng không nắn méo%s", w, h, fs.length,
                    bent.isEmpty() ? "" : " — bị nắn: " + bent));
        }
        System.out.printf(Locale.US, "Tổng thời gian %d ms. %d/%d kiểm tra đạt%n", System.currentTimeMillis() - t0, checks - fails, checks);
        if (fails > 0) System.exit(1);
    }
}
