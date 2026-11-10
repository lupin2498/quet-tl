import vn.quettailieu.scanner.core.DocDetector;
import vn.quettailieu.scanner.core.Enhancer;
import vn.quettailieu.scanner.core.Geom;
import vn.quettailieu.scanner.core.PdfWriter;
import vn.quettailieu.scanner.core.Retouch;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Kiểm thử độ bền (fuzz) cho phần thuật toán: ảnh rất nhỏ / rất dẹt / một màu / nhiễu / sọc,
 * tứ giác suy biến, JPEG hỏng... Không được ném ngoại lệ, kết quả phải hợp lệ.
 *
 * Cách chạy: java -cp out FuzzCore [thư-mục-xuất-PDF] [số-vòng]
 */
public class FuzzCore {
    static int failures = 0, checks = 0;
    static final Random rnd = new Random(20261110L);

    static void fail(String what, Throwable t) {
        failures++;
        System.out.println("FAIL " + what + (t == null ? "" : ": " + t));
        if (t != null) {
            StackTraceElement[] st = t.getStackTrace();
            for (int i = 0; i < Math.min(6, st.length); i++) System.out.println("      at " + st[i]);
        }
    }

    static void check(boolean ok, String what) {
        checks++;
        if (!ok) fail(what, null);
    }

    public static void main(String[] a) throws Exception {
        File outDir = new File(a.length > 0 ? a[0] : "out/fuzz");
        outDir.mkdirs();
        int rounds = a.length > 1 ? Integer.parseInt(a[1]) : 60;
        long t0 = System.currentTimeMillis();
        fuzzDetector(rounds);
        System.out.printf("DocDetector: xong (%d ms)%n", System.currentTimeMillis() - t0);
        t0 = System.currentTimeMillis();
        syntheticAccuracy(rounds);
        System.out.printf("DocDetector trên tứ giác tổng hợp: xong (%d ms)%n", System.currentTimeMillis() - t0);
        fuzzGeom(rounds * 400);
        System.out.println("Geom: xong");
        fuzzEnhancer(rounds);
        System.out.println("Enhancer: xong");
        fuzzRetouch(rounds);
        System.out.println("Retouch: xong");
        fuzzPdf(outDir);
        System.out.println("PdfWriter: xong");
        System.out.printf("KẾT QUẢ FUZZ: %d kiểm tra, %d lỗi%n", checks, failures);
        if (failures > 0) System.exit(1);
    }

    // ------------------------------------------------------------------ DocDetector

    static int[] pattern(int kind, int w, int h) {
        int[] px = new int[w * h];
        switch (kind) {
            case 0: java.util.Arrays.fill(px, 0xFF000000); break;
            case 1: java.util.Arrays.fill(px, 0xFFFFFFFF); break;
            case 2: java.util.Arrays.fill(px, 0xFF808080); break;
            case 3:
                for (int i = 0; i < px.length; i++) px[i] = 0xFF000000 | rnd.nextInt(0x1000000);
                break;
            case 4:
                for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                    int v = (int) (255L * x / Math.max(1, w - 1));
                    px[y * w + x] = 0xFF000000 | (v << 16) | (v << 8) | v;
                }
                break;
            case 5: {
                int c = 1 + rnd.nextInt(24);
                for (int y = 0; y < h; y++) for (int x = 0; x < w; x++)
                    px[y * w + x] = (((x / c) + (y / c)) & 1) == 0 ? 0xFFFFFFFF : 0xFF000000;
                break;
            }
            case 6: {
                double ang = rnd.nextDouble() * Math.PI, per = 3 + rnd.nextInt(40);
                double cx = Math.cos(ang), sy = Math.sin(ang);
                for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                    int v = ((int) Math.floor((x * cx + y * sy) / per) & 1) == 0 ? 235 : 20;
                    px[y * w + x] = 0xFF000000 | (v << 16) | (v << 8) | v;
                }
                break;
            }
            case 7:
                // alpha bất kỳ (ảnh PNG trong suốt)
                for (int i = 0; i < px.length; i++) px[i] = rnd.nextInt();
                break;
            default: {
                // khung trắng sát mép ảnh
                java.util.Arrays.fill(px, 0xFFF0F0F0);
                int b = rnd.nextInt(3);
                for (int y = 0; y < h; y++) for (int x = 0; x < w; x++)
                    if (x < b || y < b || x >= w - b || y >= h - b) px[y * w + x] = 0xFF101010;
            }
        }
        return px;
    }

    static void checkQuad(float[] q, int w, int h, String what) {
        boolean finite = true, inside = true;
        for (int i = 0; i < 8; i++) {
            if (Float.isNaN(q[i]) || Float.isInfinite(q[i])) finite = false;
        }
        for (int i = 0; i < 4; i++) {
            if (q[2 * i] < -0.01f || q[2 * i] > w - 1 + 0.01f || q[2 * i + 1] < -0.01f || q[2 * i + 1] > h - 1 + 0.01f) inside = false;
        }
        check(finite, what + ": toạ độ không hữu hạn");
        check(inside, what + ": toạ độ ngoài ảnh " + java.util.Arrays.toString(q));
        float[] o = Geom.order(q);
        check(Geom.isConvex(o), what + ": tứ giác không lồi " + java.util.Arrays.toString(q));
    }

    static void fuzzDetector(int rounds) {
        int[][] sizes = {
            {1, 1}, {15, 15}, {16, 16}, {17, 16}, {16, 17}, {16, 2000}, {2000, 16}, {31, 33}, {64, 64}, {419, 421},
            {420, 420}, {421, 419}, {640, 480}, {480, 640}, {1024, 768}, {768, 1024}, {1024, 17}, {17, 1024}, {1000, 30}, {30, 1000}
        };
        check(DocDetector.detect(null, 100, 100) == null, "detect(null)");
        for (int[] s : sizes) {
            for (int kind = 0; kind <= 8; kind++) runDetect(pattern(kind, s[0], s[1]), s[0], s[1], "mẫu " + kind + " " + s[0] + "x" + s[1]);
        }
        for (int r = 0; r < rounds; r++) {
            int w = 16 + rnd.nextInt(700), h = 16 + rnd.nextInt(700);
            runDetect(pattern(rnd.nextInt(9), w, h), w, h, "ngẫu nhiên " + w + "x" + h);
        }
    }

    static DocDetector.Result runDetect(int[] px, int w, int h, String what) {
        try {
            DocDetector.Result r = DocDetector.detect(px, w, h);
            checks++;
            if (w < 16 || h < 16) {
                check(r == null, what + ": ảnh quá nhỏ phải trả về null");
                return r;
            }
            check(r != null, what + ": kết quả null");
            if (r != null && r.quad != null) {
                check(r.quad.length == 8, what + ": quad.length");
                checkQuad(r.quad, w, h, what);
            }
            return r;
        } catch (Throwable t) {
            fail("DocDetector " + what, t);
            return null;
        }
    }

    /** Trang giấy sáng trên nền tối với phối cảnh ngẫu nhiên: phải tìm đúng 4 góc. */
    static void syntheticAccuracy(int rounds) {
        int ok = 0, total = 0;
        double worst = 0;
        for (int r = 0; r < rounds; r++) {
            int w = 480 + rnd.nextInt(545), h = 480 + rnd.nextInt(545);
            float mx = 0.08f * w, my = 0.08f * h;
            float[] q = {
                mx + rnd.nextFloat() * 0.12f * w, my + rnd.nextFloat() * 0.12f * h,
                w - mx - rnd.nextFloat() * 0.12f * w, my + rnd.nextFloat() * 0.12f * h,
                w - mx - rnd.nextFloat() * 0.12f * w, h - my - rnd.nextFloat() * 0.12f * h,
                mx + rnd.nextFloat() * 0.12f * w, h - my - rnd.nextFloat() * 0.12f * h
            };
            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int bgv = 25 + rnd.nextInt(70);
            g.setColor(new Color(bgv, Math.max(0, bgv - rnd.nextInt(20)), Math.max(0, bgv - rnd.nextInt(25))));
            g.fillRect(0, 0, w, h);
            Path2D.Float p = new Path2D.Float();
            p.moveTo(q[0], q[1]); p.lineTo(q[2], q[3]); p.lineTo(q[4], q[5]); p.lineTo(q[6], q[7]); p.closePath();
            int pv = 205 + rnd.nextInt(50);
            g.setColor(new Color(pv, pv, Math.max(0, pv - rnd.nextInt(12))));
            g.fill(p);
            // vài "dòng chữ" trong trang
            g.setColor(new Color(40, 40, 40));
            for (int k = 0; k < 10; k++) {
                float t = 0.15f + 0.07f * k;
                float lx = q[0] + (q[6] - q[0]) * t, ly = q[1] + (q[7] - q[1]) * t;
                float rx = q[2] + (q[4] - q[2]) * t, ry = q[3] + (q[5] - q[3]) * t;
                g.drawLine(Math.round(lx + (rx - lx) * 0.12f), Math.round(ly + (ry - ly) * 0.12f),
                        Math.round(lx + (rx - lx) * 0.8f), Math.round(ly + (ry - ly) * 0.8f));
            }
            g.dispose();
            int[] px = img.getRGB(0, 0, w, h, null, 0, w);
            DocDetector.Result res = runDetect(px, w, h, "tổng hợp #" + r);
            total++;
            double err = 1;
            if (res != null && res.quad != null) {
                err = 0;
                double diag = Math.hypot(w, h);
                for (int i = 0; i < 4; i++) err = Math.max(err, Math.hypot(res.quad[2 * i] - q[2 * i], res.quad[2 * i + 1] - q[2 * i + 1]) / diag);
            }
            worst = Math.max(worst, err);
            if (err < 0.012) ok++;
            else System.out.printf("   tổng hợp #%d %dx%d err=%.4f %s%n", r, w, h, err, res == null ? "null" : res.debug);
        }
        System.out.printf("   tứ giác tổng hợp: %d/%d đúng (sai số <1.2%% đường chéo), tệ nhất %.4f%n", ok, total, worst);
        check(ok >= total * 0.95, "tìm mép trên tứ giác tổng hợp: " + ok + "/" + total);
    }

    // ------------------------------------------------------------------ Geom

    static float[] randomQuad(int mode) {
        float[] q = new float[8];
        switch (mode) {
            case 0: for (int i = 0; i < 8; i++) q[i] = rnd.nextFloat() * 4000; break;
            case 1: { float v = rnd.nextFloat() * 100; for (int i = 0; i < 8; i++) q[i] = v; break; }          // 4 điểm trùng
            case 2: for (int i = 0; i < 4; i++) { q[2 * i] = i * 10; q[2 * i + 1] = i * 10; } break;            // thẳng hàng
            case 3: q = new float[]{0, 0, 100, 0, 100, 0, 0, 0}; break;                                          // cao = 0
            case 4: q = new float[]{0, 0, 0, 0, 0, 100, 0, 100}; break;                                          // rộng = 0
            case 5: q = new float[]{0, 0, 100, 100, 100, 0, 0, 100}; break;                                      // tự cắt
            case 6: for (int i = 0; i < 8; i++) q[i] = (rnd.nextFloat() - 0.5f) * 1e7f; break;                   // rất lớn / âm
            case 7: q = new float[]{0, 0, 1e-4f, 0, 1e-4f, 1e-4f, 0, 1e-4f}; break;                              // rất nhỏ
            case 9: q = new float[]{Float.NaN, 0, 100, 0, 100, 100, 0, 100}; break;                              // NaN
            case 10: q = new float[]{0, 0, Float.POSITIVE_INFINITY, 0, 100, 100, 0, Float.NEGATIVE_INFINITY}; break; // vô cực
            default: {
                float w = 100 + rnd.nextFloat() * 3000, h = 100 + rnd.nextFloat() * 3000;
                q = new float[]{rnd.nextFloat() * w * 0.3f, rnd.nextFloat() * h * 0.3f,
                        w - rnd.nextFloat() * w * 0.3f, rnd.nextFloat() * h * 0.3f,
                        w - rnd.nextFloat() * w * 0.3f, h - rnd.nextFloat() * h * 0.3f,
                        rnd.nextFloat() * w * 0.3f, h - rnd.nextFloat() * h * 0.3f};
            }
        }
        return q;
    }

    static void fuzzGeom(int n) {
        for (int i = 0; i < n; i++) {
            int mode = i < 110 ? i % 11 : (rnd.nextInt(4) == 0 ? rnd.nextInt(11) : 8);
            float[] q = randomQuad(mode);
            try {
                float[] o = Geom.order(q);
                check(o.length == 8, "order.length");
                boolean convex = Geom.isConvex(o);
                if (mode == 9 || mode == 10) check(!convex, "isConvex phải từ chối toạ độ không hữu hạn");
                Geom.area(o);
                Geom.angleRange(o);
                double focal = rnd.nextInt(3) == 0 ? -1 : rnd.nextDouble() * 5000;
                double asp = Geom.estimateAspect(o, 4000, 3000, focal);
                if (convex) check(!Double.isNaN(asp) && asp > 0 && !Double.isInfinite(asp), "estimateAspect lồi = " + asp + " " + java.util.Arrays.toString(o));
                for (int maxLong : new int[]{16, 1000, 3508}) {
                    int[] sz = Geom.outputSize(o, 4000, 3000, focal, rnd.nextBoolean(), maxLong);
                    check(sz[0] >= 16 && sz[1] >= 16 && sz[0] <= Math.max(16, maxLong) && sz[1] <= Math.max(16, maxLong),
                            "outputSize = " + sz[0] + "x" + sz[1] + " (maxLong " + maxLong + ") " + java.util.Arrays.toString(o));
                }
                double[] hm = Geom.homography(new float[]{0, 0, 64, 0, 64, 64, 0, 64}, o);
                if (convex && Geom.area(o) > 1) check(hm != null, "homography null với tứ giác lồi " + java.util.Arrays.toString(o));
                if (i % 200 == 0) {
                    int sw = 40, sh = 30;
                    int[] src = pattern(3, sw, sh);
                    int[] out = Geom.warp(src, sw, sh, Geom.scale(o, 0.01f, 0.01f), 24, 32);
                    check(out.length == 24 * 32, "warp.length");
                }
            } catch (Throwable t) {
                fail("Geom mode " + mode + " " + java.util.Arrays.toString(q), t);
            }
        }
        // khổ giấy chuẩn
        check(Geom.snapPaper(0.70) == Geom.A4_RATIO, "snap A4 dọc");
        check(Geom.snapPaper(1.42) == 1.0 / Geom.A4_RATIO, "snap A4 ngang");
        check(Geom.snapPaper(0.775) == Geom.LETTER_RATIO, "snap Letter dọc");
        check(Geom.snapPaper(0.5) == 0.5, "không snap tỷ lệ lạ");
        // tứ giác A4 chụp thẳng: tỷ lệ phải đúng A4
        float[] a4 = {100, 100, 100 + 2100, 100, 100 + 2100, 100 + 2970, 100, 100 + 2970};
        int[] sz = Geom.outputSize(a4, 4000, 3200, -1, true, 3508);
        check(Math.abs(sz[0] / (double) sz[1] - Geom.A4_RATIO) < 0.002, "outputSize A4 = " + sz[0] + "x" + sz[1]);
    }

    // ------------------------------------------------------------------ Enhancer

    static void fuzzEnhancer(int rounds) {
        int[][] sizes = {{1, 1}, {3, 3}, {4, 4}, {5, 4}, {4, 5}, {7, 9}, {8, 8}, {9, 8}, {100, 4}, {4, 100}, {2000, 5}, {5, 2000},
                {97, 131}, {640, 480}, {1240, 1754}};
        int[] modes = {Enhancer.ORIGINAL, Enhancer.MAGIC, Enhancer.LIGHTEN, Enhancer.GRAY, Enhancer.BW,
                Enhancer.MAGIC_PRO, Enhancer.DENOISE, Enhancer.NO_SHADOW, Enhancer.NO_HAND, 99, -1};
        for (int[] s : sizes) {
            for (int kind = 0; kind <= 8; kind++) {
                for (int m : modes) runEnhance(pattern(kind, s[0], s[1]), s[0], s[1], m, "mẫu " + kind);
            }
        }
        for (int r = 0; r < rounds; r++) {
            int w = 1 + rnd.nextInt(400), h = 1 + rnd.nextInt(400);
            runEnhance(pattern(rnd.nextInt(9), w, h), w, h, modes[rnd.nextInt(modes.length)], "ngẫu nhiên");
        }
        try { Enhancer.apply(null, 10, 10, Enhancer.MAGIC); checks++; } catch (Throwable t) { fail("Enhancer null", t); }
        // trang trắng ám vàng + chữ đen: nền phải về trắng, chữ vẫn tối
        int w = 600, h = 800;
        int[] px = new int[w * h];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int shade = 150 + (int) (60.0 * x / w);
            boolean ink = (y % 40 < 4) && x > 60 && x < 540;
            px[y * w + x] = ink ? 0xFF282420 : (0xFF000000 | (shade << 16) | ((shade - 8) << 8) | (shade - 30));
        }
        for (int m : new int[]{Enhancer.MAGIC, Enhancer.LIGHTEN, Enhancer.GRAY, Enhancer.BW, Enhancer.MAGIC_PRO, Enhancer.NO_SHADOW, Enhancer.NO_HAND}) {
            int[] c = px.clone();
            Enhancer.apply(c, w, h, m);
            int paper = c[20 * w + 300], ink = c[41 * w + 300];
            int pl = lum(paper), il = lum(ink);
            check(pl >= 235, "Enhancer chế độ " + m + ": nền giấy " + pl + " (phải >= 235)");
            check(il <= 90, "Enhancer chế độ " + m + ": chữ " + il + " (phải <= 90)");
        }
    }

    static int lum(int c) {
        return (299 * ((c >> 16) & 255) + 587 * ((c >> 8) & 255) + 114 * (c & 255)) / 1000;
    }

    static void runEnhance(int[] px, int w, int h, int mode, String what) {
        try {
            int[] before = px.clone();
            Enhancer.apply(px, w, h, mode);
            checks++;
            if (mode == Enhancer.ORIGINAL || w < 4 || h < 4) {
                check(java.util.Arrays.equals(before, px), "Enhancer giữ nguyên ảnh (" + what + " " + w + "x" + h + " chế độ " + mode + ")");
            } else {
                boolean opaque = true;
                for (int c : px) if ((c >>> 24) != 255) { opaque = false; break; }
                check(opaque, "Enhancer alpha = 255 (" + what + " " + w + "x" + h + " chế độ " + mode + ")");
            }
        } catch (Throwable t) {
            fail("Enhancer " + what + " " + w + "x" + h + " chế độ " + mode, t);
        }
    }

    // ------------------------------------------------------------------ Retouch

    /** Toạ độ chuẩn hoá ngẫu nhiên, thỉnh thoảng là giá trị bất thường. */
    static float coord() {
        switch (rnd.nextInt(14)) {
            case 0: return Float.NaN;
            case 1: return Float.POSITIVE_INFINITY;
            case 2: return Float.NEGATIVE_INFINITY;
            case 3: return -1e9f;
            case 4: return 1e9f;
            case 5: return -0.5f;
            case 6: return 1.5f;
            default: return rnd.nextFloat();
        }
    }

    static Retouch.Op randomOp(int kind) {
        int n = kind == Retouch.RECT ? 4 : rnd.nextInt(6);
        if (rnd.nextInt(10) == 0) n = rnd.nextInt(3);
        float[] p = new float[n * 2 + (rnd.nextInt(8) == 0 ? 1 : 0)];
        for (int i = 0; i < p.length; i++) p[i] = coord();
        float width = rnd.nextInt(6) == 0 ? coord() * 1e6f : rnd.nextFloat() * 0.2f;
        return new Retouch.Op(kind, width, rnd.nextInt(25) == 0 ? null : p);
    }

    static void fuzzRetouch(int rounds) {
        int[][] sizes = {{1, 1}, {3, 3}, {4, 4}, {5, 4}, {8, 8}, {9, 7}, {16, 16}, {17, 33}, {48, 48}, {47, 200}, {200, 47}, {100, 4}, {4, 100},
                {2000, 5}, {5, 2000}, {97, 131}, {420, 300}, {640, 480}};
        for (int[] s : sizes) {
            for (int kind = 0; kind <= 8; kind++) runRetouch(pattern(kind, s[0], s[1]), s[0], s[1], "mẫu " + kind);
        }
        for (int r = 0; r < rounds; r++) {
            int w = 1 + rnd.nextInt(300), h = 1 + rnd.nextInt(300);
            runRetouch(pattern(rnd.nextInt(9), w, h), w, h, "ngẫu nhiên");
        }
        // tham số null / mảng ngắn hơn ảnh: không được ném lỗi
        try {
            int[] px = pattern(3, 40, 30);
            Retouch.fill(null, 40, 30, new byte[1200]);
            Retouch.fill(px, 40, 30, null);
            Retouch.fill(px, 40, 30, new byte[10]);
            Retouch.fill(new int[10], 40, 30, new byte[1200]);
            Retouch.strokeMask(null, 40, 30, new float[]{1, 1}, 3);
            Retouch.strokeMask(new byte[1200], 40, 30, null, 3);
            Retouch.polyMask(null, 40, 30, new float[]{1, 1, 5, 1, 5, 5});
            Retouch.polyMask(new byte[1200], 40, 30, null);
            Retouch.opMask(new byte[1200], 40, 30, null);
            Retouch.applyErase(px, 40, 30, null);
            Retouch.applyErase(null, 40, 30, new ArrayList<Retouch.Op>());
            check(!Retouch.erase(px, 40, 30, Retouch.PEN, null), "erase: không có vùng tô -> false");
            check(!Retouch.erase(px, 40, 30, Retouch.MAGIC, new byte[10]), "erase: vùng tô ngắn hơn ảnh -> false");
            check(!Retouch.erase(null, 40, 30, Retouch.HAND, null), "erase: ảnh null -> false");
            check(Retouch.fingerMask(null, 100, 100) == null, "fingerMask null");
            check(Retouch.fingerMask(new int[10], 100, 100) == null, "fingerMask mảng ngắn");
            Retouch.cleanEdges(null, 40, 30);
            Retouch.sharpen(null, 40, 30, 96);
            Retouch.denoise(null, 40, 30);
            Retouch.cleanEdges(new int[10], 40, 30);
            Retouch.sharpen(new int[10], 40, 30, 96);
            Retouch.denoise(new int[10], 40, 30);
            check(Retouch.remap(null, null, null) == null, "remap null");
            check(Retouch.remapScale(null, null) == 1f, "remapScale null");
            checks++;
        } catch (Throwable t) {
            fail("Retouch tham số null / mảng ngắn", t);
        }
        // vùng tô phủ kín ảnh: tô nền không còn mẫu nào để ước lượng -> vẫn phải chạy xong, ảnh còn nguyên kích thước
        try {
            int w = 60, h = 50;
            int[] px = pattern(3, w, h);
            byte[] all = new byte[w * h];
            java.util.Arrays.fill(all, (byte) 1);
            for (int k = 0; k < 5; k++) Retouch.erase(px.clone(), w, h, k, all);
            checks++;
        } catch (Throwable t) {
            fail("Retouch vùng tô phủ kín ảnh", t);
        }
    }

    static void runRetouch(int[] px, int w, int h, String what) {
        try {
            byte[] fm = Retouch.fingerMask(px, w, h);
            check(fm == null || fm.length == w * h, "fingerMask kích thước (" + what + " " + w + "x" + h + ")");
            Retouch.removeFingers(px.clone(), w, h);
            List<Retouch.Op> ops = new ArrayList<>();
            int count = rnd.nextInt(5);
            for (int i = 0; i < count; i++) ops.add(rnd.nextInt(12) == 0 ? null : randomOp(rnd.nextInt(5)));
            Retouch.applyErase(px.clone(), w, h, ops);
            for (int k = 0; k < 5; k++) {
                byte[] m = new byte[w * h];
                Retouch.opMask(m, w, h, randomOp(k));
                Retouch.erase(px.clone(), w, h, k, m);
                Retouch.erase(px.clone(), w, h, k, null);
            }
            int[] c = px.clone();
            Retouch.cleanEdges(c, w, h);
            Retouch.sharpen(c, w, h, 96);
            Retouch.denoise(c, w, h);
            checks++;
        } catch (Throwable t) {
            fail("Retouch " + what + " " + w + "x" + h, t);
        }
    }

    // ------------------------------------------------------------------ PdfWriter

    static byte[] jpeg(int w, int h, boolean gray) throws Exception {
        BufferedImage img = new BufferedImage(w, h, gray ? BufferedImage.TYPE_BYTE_GRAY : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.setColor(new Color(30, 60, 160));
        g.drawLine(0, 0, w, h);
        g.fillOval(w / 4, h / 4, Math.max(1, w / 2), Math.max(1, h / 2));
        g.dispose();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", bo);
        return bo.toByteArray();
    }

    static void fuzzPdf(File outDir) throws Exception {
        byte[] a = jpeg(1240, 1754, false), b = jpeg(1754, 1240, false), c = jpeg(16, 16, true), d = jpeg(3000, 40, false), e = jpeg(1, 1, false);
        String[] titles = {"Biên bản thử nghiệm", "", null, "Hoá đơn (số 12) \\ <test> 😀 ấệữ", repeat("Tên rất dài ", 60)};
        int file = 0;
        for (int mode : new int[]{PdfWriter.SIZE_A4, PdfWriter.SIZE_LETTER, PdfWriter.SIZE_FIT}) {
            for (int v = 0; v < 5; v++) {
                List<byte[]> pages = new ArrayList<>();
                switch (v) {
                    case 0: pages.add(a); break;
                    case 1: pages.add(a); pages.add(b); pages.add(c); break;
                    case 2: pages.add(d); pages.add(e); break;
                    case 3: for (int i = 0; i < 60; i++) pages.add(i % 2 == 0 ? a : b); break;
                    default: pages.add(c); pages.add(c); pages.add(d); pages.add(a);
                }
                try {
                    byte[] pdf = PdfWriter.toBytes(pages, mode, titles[v]);
                    checks++;
                    String head = new String(pdf, 0, 8, "ISO-8859-1");
                    String tail = new String(pdf, pdf.length - 6, 6, "ISO-8859-1");
                    check(head.startsWith("%PDF-1.4") && tail.contains("%%EOF"), "PDF đầu/cuối");
                    File f = new File(outDir, String.format("fuzz_%02d_m%d_p%d.pdf", file++, mode, pages.size()));
                    try (FileOutputStream fo = new FileOutputStream(f)) { fo.write(pdf); }
                } catch (Throwable t) {
                    fail("PdfWriter mode " + mode + " v" + v, t);
                }
            }
        }
        // JPEG hỏng: không ném ngoại lệ ngoài IOException khi ghi; readJpegInfo không được lỗi
        for (int len = 0; len <= Math.min(a.length, 700); len++) {
            byte[] cut = java.util.Arrays.copyOf(a, len);
            try { PdfWriter.readJpegInfo(cut); checks++; } catch (Throwable t) { fail("readJpegInfo cắt " + len, t); }
        }
        for (int i = 0; i < 3000; i++) {
            byte[] junk = new byte[rnd.nextInt(600)];
            rnd.nextBytes(junk);
            if (junk.length > 2 && rnd.nextBoolean()) { junk[0] = (byte) 0xFF; junk[1] = (byte) 0xD8; }
            if (junk.length > 12 && rnd.nextInt(3) == 0) { junk[2] = (byte) 0xFF; junk[3] = (byte) (0xC0 + rnd.nextInt(16)); }
            try {
                PdfWriter.JpegInfo info = PdfWriter.readJpegInfo(junk);
                checks++;
                if (info != null) check(info.width > 0 && info.height > 0, "readJpegInfo trả về kích thước <= 0");
            } catch (Throwable t) {
                fail("readJpegInfo rác", t);
            }
        }
        check(PdfWriter.readJpegInfo((byte[]) null) == null, "readJpegInfo(null)");
        try {
            List<byte[]> bad = new ArrayList<>();
            bad.add(new byte[]{1, 2, 3});
            PdfWriter.toBytes(bad, PdfWriter.SIZE_A4, "x");
            fail("PdfWriter phải từ chối ảnh hỏng", null);
        } catch (java.io.IOException ex) {
            checks++;
        } catch (Throwable t) {
            fail("PdfWriter ảnh hỏng", t);
        }
    }

    static String repeat(String s, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(s);
        return sb.toString();
    }
}
