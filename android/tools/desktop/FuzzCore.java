import vn.quettailieu.scanner.core.DocDetector;
import vn.quettailieu.scanner.core.DocLayout;
import vn.quettailieu.scanner.core.DocxWriter;
import vn.quettailieu.scanner.core.Enhancer;
import vn.quettailieu.scanner.core.Flatten;
import vn.quettailieu.scanner.core.Geom;
import vn.quettailieu.scanner.core.PdfWriter;
import vn.quettailieu.scanner.core.Retouch;
import vn.quettailieu.scanner.core.WordDoc;

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
 * tứ giác suy biến, JPEG hỏng, trang mấp mô tổng hợp + trường nắn bất thường (Làm phẳng), lõi chuyển sang Word (DocLayout
 * + DocxWriter) với danh sách từ rác... Không được ném ngoại lệ, kết quả phải hợp lệ. File .docx ghi vào thư mục xuất
 * (word_fuzz_*.docx) để kiểm bằng tests/tools/check_docx_zip.py.
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
        t0 = System.currentTimeMillis();
        fuzzFlatten(rounds);
        System.out.printf("Flatten (Làm phẳng): xong (%d ms)%n", System.currentTimeMillis() - t0);
        t0 = System.currentTimeMillis();
        fuzzWord(rounds, outDir);
        System.out.printf("DocLayout + DocxWriter (Word): xong (%d ms)%n", System.currentTimeMillis() - t0);
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
                Enhancer.MAGIC_PRO, Enhancer.DENOISE, Enhancer.NO_SHADOW, Enhancer.NO_HAND, Enhancer.FLATTEN, 99, -1};
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
        for (int m : new int[]{Enhancer.MAGIC, Enhancer.LIGHTEN, Enhancer.GRAY, Enhancer.BW, Enhancer.MAGIC_PRO, Enhancer.NO_SHADOW, Enhancer.NO_HAND,
                Enhancer.FLATTEN}) {
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
            // Làm phẳng bỏ qua ảnh có cạnh < 16 (Flatten.apply trả về null, không đụng tới ảnh)
            if (mode == Enhancer.ORIGINAL || w < 4 || h < 4 || (mode == Enhancer.FLATTEN && (w < 16 || h < 16))) {
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

    // ------------------------------------------------------------------ Flatten (Làm phẳng)

    static boolean opaque(int[] px) {
        for (int c : px) if ((c >>> 24) != 255) return false;
        return true;
    }

    /** Hai trường nắn giống hệt nhau (cùng null, hoặc cùng mọi tham số và giá trị từng nút). */
    static boolean sameField(Flatten.Field a, Flatten.Field b) {
        if (a == null || b == null) return a == b;
        return a.gw == b.gw && a.gh == b.gh && a.lines == b.lines && a.hrules == b.hrules && a.vrules == b.vrules
                && a.rotated == b.rotated && Float.compare(a.step, b.step) == 0 && Float.compare(a.ox, b.ox) == 0
                && Float.compare(a.maxShift, b.maxShift) == 0 && java.util.Arrays.equals(a.dx, b.dx) && java.util.Arrays.equals(a.dy, b.dy);
    }

    /**
     * Trường nắn hợp lệ: lưới >= 2 x 2 khớp độ dài mảng, mọi giá trị hữu hạn và không vượt maxShift, 1 <= maxShift <= 8% cạnh
     * dài ảnh, ánh xạ không gập (định thức Jacobi trên mọi ô lưới trong [0,5; 2], như Flatten.foldFree).
     */
    static void checkField(Flatten.Field f, int w, int h, String what) {
        boolean shape = f.gw >= 2 && f.gh >= 2 && f.dx != null && f.dy != null && f.dx.length == f.gw * f.gh && f.dy.length == f.gw * f.gh
                && f.step > 0f && !Float.isInfinite(f.step) && f.ox >= 0f && !Float.isInfinite(f.ox);
        check(shape, what + ": trường nắn sai kích thước " + f.gw + "x" + f.gh + " bước " + f.step + " gốc " + f.ox);
        if (!shape) return;
        check(f.maxShift >= 1f && f.maxShift <= 0.08f * Math.max(w, h) + 0.01f, what + ": dịch chuyển lớn nhất " + f.maxShift);
        check(f.lines >= 0 && f.hrules >= 0 && f.vrules >= 0, what + ": số đường âm");
        boolean finite = true, bounded = true;
        for (int i = 0; i < f.dx.length; i++) {
            float a = f.dx[i], b = f.dy[i];
            if (Float.isNaN(a) || Float.isNaN(b) || Float.isInfinite(a) || Float.isInfinite(b)) finite = false;
            else if (Math.abs(a) > f.maxShift || Math.abs(b) > f.maxShift) bounded = false;
        }
        check(finite, what + ": trường nắn có giá trị không hữu hạn");
        check(bounded, what + ": giá trị trường vượt dịch chuyển lớn nhất " + f.maxShift);
        double lo = 1, hi = 1;
        for (int j = 0; j + 1 < f.gh; j++) {
            for (int i = 0; i + 1 < f.gw; i++) {
                int o = j * f.gw + i;
                double xu = (f.dx[o + 1] - f.dx[o]) / (double) f.step, xv = (f.dx[o + f.gw] - f.dx[o]) / (double) f.step;
                double yu = (f.dy[o + 1] - f.dy[o]) / (double) f.step, yv = (f.dy[o + f.gw] - f.dy[o]) / (double) f.step;
                double det = (1 + xu) * (1 + yv) - xv * yu;
                lo = Math.min(lo, det);
                hi = Math.max(hi, det);
            }
        }
        check(lo >= 0.499 && hi <= 2.001, what + ": trường nắn gập / co giãn quá mức (định thức " + lo + " .. " + hi + ")");
    }

    /** Một "ký tự" giả (nét dọc / ngang ghép lại) trong khung (x, y, cw, ch), nét dày s. */
    static void glyph(Graphics2D g, float x, float y, float cw, float ch, int s) {
        int x0 = Math.round(x), y0 = Math.round(y), w = Math.max(2, Math.round(cw)), h = Math.max(3, Math.round(ch));
        int xh = h / 3;
        switch (rnd.nextInt(5)) {
            case 0: g.fillRect(x0, y0, s, h); g.fillRect(x0, y0 + h - s, w, s); break;                              // L
            case 1: g.fillRect(x0, y0, w, s); g.fillRect(x0 + (w - s) / 2, y0, s, h); break;                        // T
            case 2: g.fillRect(x0, y0 + xh, w, s); g.fillRect(x0, y0 + h - s, w, s);                                 // o
                g.fillRect(x0, y0 + xh, s, h - xh); g.fillRect(x0 + w - s, y0 + xh, s, h - xh); break;
            case 3: g.fillRect(x0, y0, s, h); g.fillRect(x0 + w - s, y0, s, h); g.fillRect(x0, y0 + h / 2, w, s); break; // H
            default: g.fillRect(x0, y0 + xh, s, h - xh); g.fillRect(x0, y0 + xh, w, s);                              // r
        }
    }

    /**
     * Trang chữ tổng hợp trên giấy mấp mô (cạnh dài L, tỷ lệ cạnh ngắn / dài asp) — đi qua toàn bộ các bước của Flatten:
     * cỡ chữ, lưới bảng, dòng chữ, khớp trường, nắn. Dòng "chữ" giả cỡ ~1-2% cạnh dài, có thể có bảng kẻ, vệt nếp gấp tối,
     * ngón tay ở mép; uốn bằng trường sóng ngẫu nhiên + cong mép + nghiêng. vertical = true: dòng chữ chạy dọc
     * (trang chụp xoay ngang). Trả về {{w, h}, điểm ảnh}.
     */
    static int[][] wavyPage(int L, double asp, boolean landscape, boolean vertical, boolean table, boolean fold, boolean finger) {
        int sh = (int) Math.round(L * asp);
        int w = landscape ? L : sh, h = landscape ? sh : L;
        int pw = vertical ? h : w, ph = vertical ? w : h;   // trang trước khi xoay: dòng chữ nằm ngang
        BufferedImage img = new BufferedImage(pw, ph, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, pw, ph);
        float ch = Math.max(6f, Math.max(pw, ph) * (0.009f + 0.009f * rnd.nextFloat()));
        float pitch = ch * (1.6f + 0.8f * rnd.nextFloat());
        int s = Math.max(1, Math.round(ch / 7f));
        int iv = 15 + rnd.nextInt(80);
        g.setColor(new Color(iv, iv, Math.min(255, iv + rnd.nextInt(20))));
        int mx = Math.round(0.07f * pw), my = Math.round(0.06f * ph);
        int t0 = -1, t1 = -1;
        if (table) {
            int rows = 3 + rnd.nextInt(8), cols = 2 + rnd.nextInt(5);
            float rh = pitch * (1.2f + 0.6f * rnd.nextFloat());
            t0 = my + Math.round((ph - 2 * my) * (0.15f + 0.35f * rnd.nextFloat()));
            t1 = Math.min(ph - my, t0 + Math.round(rows * rh));
            int lw = Math.max(1, s - rnd.nextInt(2));
            for (int r = 0; r <= rows; r++) g.fillRect(mx, Math.min(t1, t0 + Math.round(r * rh)), pw - 2 * mx, lw);
            for (int c = 0; c <= cols; c++) g.fillRect(mx + Math.round(c * (pw - 2 * mx - lw) / (float) cols), t0, lw, t1 - t0 + lw);
            for (int r = 0; r < rows; r++) {
                float y = t0 + r * rh + (rh - ch) / 2;
                if (y + ch >= t1) break;
                for (int c = 0; c < cols; c++) {
                    float x = mx + c * (pw - 2 * mx) / (float) cols + ch * 0.5f;
                    int n = rnd.nextInt(6);
                    for (int k = 0; k < n; k++) {
                        float cw = ch * (0.45f + 0.4f * rnd.nextFloat());
                        glyph(g, x, y, cw, ch, s);
                        x += cw + 0.15f * ch;
                    }
                }
            }
        }
        for (float y = my; y + ch < ph - my; y += pitch) {
            if (table && y + ch >= t0 - 0.5f * pitch && y <= t1 + 0.5f * pitch) continue;
            float x = mx + (rnd.nextInt(4) == 0 ? 2 * ch : 0);
            float end = pw - mx - (rnd.nextInt(3) == 0 ? rnd.nextFloat() * 0.4f * pw : 0);
            while (x + ch < end) {
                int n = 1 + rnd.nextInt(8);
                for (int k = 0; k < n && x + ch < end; k++) {
                    float cw = ch * (0.45f + 0.4f * rnd.nextFloat());
                    glyph(g, x, y, cw, ch, s);
                    x += cw + 0.15f * ch;
                }
                x += 0.6f * ch;
            }
        }
        g.dispose();
        int[] src = img.getRGB(0, 0, pw, ph, null, 0, pw);
        // trường uốn (điểm ảnh quan sát (x, y) lấy từ trang phẳng tại (x + dx, y + dy)): sóng, cong mép, nghiêng
        double amp = Math.max(pw, ph) * (0.004 + 0.02 * rnd.nextDouble());
        double lam = pw * (0.35 + 0.9 * rnd.nextDouble()), lamY = ph * (0.5 + rnd.nextDouble());
        double p1 = rnd.nextDouble() * 2 * Math.PI, p2 = rnd.nextDouble() * 2 * Math.PI;
        double curl = (rnd.nextDouble() - 0.5) * 0.04 * Math.max(pw, ph), tilt = (rnd.nextDouble() - 0.5) * 0.03;
        double ampX = Math.max(pw, ph) * 0.006 * rnd.nextDouble();
        // dịch dọc = wave[x] * mod[y] + bend[x]; dịch ngang = side[y]
        double[] wave = new double[pw], bend = new double[pw], mod = new double[ph], side = new double[ph];
        for (int x = 0; x < pw; x++) {
            double u = x / (double) pw;
            wave[x] = amp * Math.sin(2 * Math.PI * x / lam + p1);
            bend[x] = curl * u * u * u + tilt * (x - pw / 2.0);
        }
        for (int y = 0; y < ph; y++) {
            mod[y] = 0.6 + 0.4 * Math.sin(2 * Math.PI * y / lamY + p2);
            side[y] = ampX * Math.sin(2 * Math.PI * y / lamY + p1);
        }
        // giấy: màu nền, ánh sáng lệch, vệt nếp gấp tối (đường thẳng ngẫu nhiên), nhiễu; ngón tay ở mép
        int pr = 195 + rnd.nextInt(55), pg = pr - rnd.nextInt(8), pb = pr - rnd.nextInt(22);
        double light = 0.15 * rnd.nextDouble(), la = rnd.nextDouble() * 2 * Math.PI;
        double fx0 = rnd.nextDouble() * pw, fy0 = rnd.nextDouble() * ph, fa = rnd.nextDouble() * Math.PI;
        double fnx = -Math.sin(fa), fny = Math.cos(fa);
        double depth = fold ? 0.1 + 0.3 * rnd.nextDouble() : 0, sig = Math.max(1.5, Math.max(pw, ph) * (0.002 + 0.008 * rnd.nextDouble()));
        double drop = fold ? 0.06 * rnd.nextDouble() : 0;   // một bên nếp gấp tối hơn
        int fgx = rnd.nextBoolean() ? 0 : pw - 1, fgy = (int) (ph * (0.2 + 0.6 * rnd.nextDouble()));
        double frx = pw * 0.05, fry = ph * 0.09;
        int[] page = new int[pw * ph];
        for (int y = 0; y < ph; y++) {
            for (int x = 0; x < pw; x++) {
                int sx = (int) Math.round(x + side[y]), sy = (int) Math.round(y + wave[x] * mod[y] + bend[x]);
                int c = sx < 0 || sy < 0 || sx >= pw || sy >= ph ? 0xFFFFFFFF : src[sy * pw + sx];
                double d = (x - fx0) * fnx + (y - fy0) * fny;
                double shade = (1 - light * (0.5 + 0.5 * Math.cos(la + 2 * Math.PI * x / pw))) * (1 - depth * Math.exp(-d * d / (sig * sig)))
                        * (d > 0 ? 1 - drop : 1);
                int n = rnd.nextInt(7) - 3;
                int r = (int) (((c >> 16) & 255) * pr / 255.0 * shade) + n, gg = (int) (((c >> 8) & 255) * pg / 255.0 * shade) + n;
                int b = (int) ((c & 255) * pb / 255.0 * shade) + n;
                if (finger) {
                    double ex = (x - fgx) / frx, ey = (y - fgy) / fry;
                    if (ex * ex + ey * ey <= 1) {
                        r = 205 - (int) (30 * ey * ey);
                        gg = 150 - (int) (25 * ey * ey);
                        b = 125 - (int) (25 * ey * ey);
                    }
                }
                r = Math.max(0, Math.min(255, r));
                gg = Math.max(0, Math.min(255, gg));
                b = Math.max(0, Math.min(255, b));
                page[y * pw + x] = 0xFF000000 | (r << 16) | (gg << 8) | b;
            }
        }
        if (!vertical) return new int[][]{{w, h}, page};
        // xoay 90 độ: dòng ngang của trang (pw = h, ph = w) thành cột của ảnh w x h
        int[] out = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) out[y * w + x] = page[(w - 1 - x) * pw + y];
        }
        return new int[][]{{w, h}, out};
    }

    static Flatten.Field field(int gw, int gh, float step, float ox, float v) {
        Flatten.Field f = new Flatten.Field();
        f.gw = gw;
        f.gh = gh;
        f.step = step;
        f.ox = ox;
        f.dx = new float[Math.max(0, gw * gh)];
        f.dy = new float[Math.max(0, gw * gh)];
        java.util.Arrays.fill(f.dx, v);
        java.util.Arrays.fill(f.dy, v);
        return f;
    }

    /** Flatten.remap không ném lỗi; mọi điểm ảnh ra còn alpha 255 (ảnh vào đục). */
    static void runRemap(int[] px, int w, int h, Flatten.Field f, String what) {
        try {
            int[] c = px.clone();
            Flatten.remap(c, w, h, f);
            check(opaque(c), "Flatten.remap alpha = 255 (" + what + ")");
        } catch (Throwable t) {
            fail("Flatten.remap " + what, t);
        }
    }

    static void fuzzFlatten(int rounds) {
        // tham số null / mảng ngắn / kích thước suy biến: không ném lỗi, không nắn
        try {
            check(Flatten.apply(null, 10, 10) == null, "Flatten.apply(null)");
            check(Flatten.apply(new int[5], 10, 10) == null, "Flatten.apply mảng ngắn, ảnh nhỏ");
            check(Flatten.apply(new int[100], 40, 30) == null, "Flatten.apply mảng ngắn hơn ảnh");
            check(Flatten.apply(new int[10], -5, -5) == null, "Flatten.apply kích thước âm");
            check(Flatten.apply(new int[0], 0, 0, false) == null, "Flatten.apply 0 x 0");
            int[] p = pattern(3, 40, 30);
            int[] q = p.clone();
            Flatten.remap(null, 40, 30, field(4, 4, 16, 0, 1));
            Flatten.remap(q, 40, 30, null);
            Flatten.remap(q, 40, 30, field(1, 5, 16, 0, 3));
            Flatten.remap(q, 40, 30, field(5, 1, 16, 0, 3));
            Flatten.remap(q, 40, 30, field(0, 0, 16, 0, 3));
            Flatten.remap(q, 40, 30, field(-3, -3, 16, 0, 3));
            Flatten.Field shortF = field(4, 4, 16, 0, 3);
            shortF.dx = new float[15];
            Flatten.remap(q, 40, 30, shortF);
            shortF = field(4, 4, 16, 0, 3);
            shortF.dy = new float[3];
            Flatten.remap(q, 40, 30, shortF);
            shortF.dy = null;
            Flatten.remap(q, 40, 30, shortF);
            for (float st : new float[]{0f, -16f, Float.NaN, Float.NEGATIVE_INFINITY}) Flatten.remap(q, 40, 30, field(4, 4, st, 0, 3));
            Flatten.remap(new int[10], 40, 30, field(4, 4, 16, 0, 3));
            Flatten.remap(q, 1, 30, field(4, 4, 16, 0, 3));
            Flatten.remap(q, 40, -2, field(4, 4, 16, 0, 3));
            check(java.util.Arrays.equals(p, q), "Flatten.remap: trường / tham số không hợp lệ -> ảnh giữ nguyên");
            // trường bằng 0: lấy mẫu đúng tại điểm ảnh -> ảnh giữ nguyên
            q = p.clone();
            Flatten.remap(q, 40, 30, field(4, 3, 13.3f, 0.5f, 0f));
            check(java.util.Arrays.equals(p, q), "Flatten.remap: trường bằng 0 -> ảnh giữ nguyên");
            checks++;
        } catch (Throwable t) {
            fail("Flatten tham số null / mảng ngắn / trường không hợp lệ", t);
        }
        // trường có NaN / vô cực / giá trị rất lớn (cả ở bước lưới, gốc lưới): không lỗi, điểm lấy mẫu ngoài ảnh = trắng
        {
            int w = 64, h = 48;
            int[] px = pattern(3, w, h);
            float[] odd = {Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.MAX_VALUE, -Float.MAX_VALUE, 1e30f, -1e30f,
                    1e9f, -1e9f, 3e5f, Float.MIN_VALUE, -0f, 0.5f, -7.25f, 63.9f, -47.99f};
            for (float v : odd) {
                Flatten.Field f = field(5, 4, 16, 0, v);
                runRemap(px, w, h, f, "trường hằng " + v);
                if (Float.isNaN(v) || Float.isInfinite(v) || Math.abs(v) >= 1e9f) {
                    int[] c = px.clone();
                    try {
                        Flatten.remap(c, w, h, f);
                        boolean white = true;
                        for (int x : c) if (x != 0xFFFFFFFF) { white = false; break; }
                        check(white, "Flatten.remap: dịch chuyển " + v + " -> ảnh trắng");
                    } catch (Throwable t) {
                        fail("Flatten.remap trường hằng " + v, t);
                    }
                }
            }
            for (int r = 0; r < 40; r++) {
                Flatten.Field f = field(2 + rnd.nextInt(8), 2 + rnd.nextInt(8), 4 + rnd.nextInt(40), rnd.nextFloat() * 3, 0f);
                for (int i = 0; i < f.dx.length; i++) {
                    f.dx[i] = rnd.nextInt(3) == 0 ? odd[rnd.nextInt(odd.length)] : (rnd.nextFloat() - 0.5f) * 40;
                    f.dy[i] = rnd.nextInt(3) == 0 ? odd[rnd.nextInt(odd.length)] : (rnd.nextFloat() - 0.5f) * 40;
                }
                runRemap(px, w, h, f, "trường ngẫu nhiên có giá trị bất thường #" + r);
            }
            for (float st : new float[]{Float.MIN_VALUE, 1e-30f, 1e30f, Float.MAX_VALUE, Float.POSITIVE_INFINITY}) {
                runRemap(px, w, h, field(5, 4, st, 0, 2.5f), "bước lưới " + st);
            }
            for (float o : new float[]{Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 1e30f, -1e30f, -5f}) {
                runRemap(px, w, h, field(5, 4, 16, o, 2.5f), "gốc lưới " + o);
            }
        }
        // ảnh rất nhỏ / rất dẹt: chỉ làm sạch, không nắn; ảnh có cạnh < 16 giữ nguyên
        int[][] sizes = {{1, 1}, {3, 3}, {15, 15}, {15, 300}, {16, 16}, {16, 17}, {17, 16}, {17, 2000}, {2000, 17}, {31, 33}, {239, 400}};
        for (int[] sz : sizes) {
            int w = sz[0], h = sz[1];
            for (int kind = 0; kind <= 8; kind++) {
                String what = "Flatten mẫu " + kind + " " + w + "x" + h;
                try {
                    int[] px = pattern(kind, w, h);
                    int[] c = px.clone();
                    Flatten.Field f = Flatten.apply(c, w, h);
                    checks++;
                    check(f == null, what + ": ảnh nhỏ / dẹt không được nắn");
                    if (w < 16 || h < 16) check(java.util.Arrays.equals(px, c), what + ": ảnh có cạnh < 16 phải giữ nguyên");
                    else check(opaque(c), what + ": alpha = 255");
                    if (kind == 3 || kind == 5) {
                        // không nắn: chỉ làm sạch (dewarp = false, ảnh xem trước của Xóa thông minh) phải trùng kết quả lọc
                        int[] c2 = px.clone();
                        check(Flatten.apply(c2, w, h, false) == null && java.util.Arrays.equals(c, c2), what + ": dewarp = false trùng kết quả lọc");
                    }
                } catch (Throwable t) {
                    fail(what, t);
                }
            }
        }
        // giấy trắng: kết quả vẫn trắng (>= 250 mọi kênh), không nắn
        for (int[] sz : new int[][]{{16, 16}, {17, 40}, {40, 17}, {300, 420}, {1000, 700}, {1240, 1754}}) {
            int w = sz[0], h = sz[1];
            try {
                int[] px = new int[w * h];
                java.util.Arrays.fill(px, 0xFFFFFFFF);
                Flatten.Field f = Flatten.apply(px, w, h);
                int worst = 255;
                for (int c : px) {
                    int m = Math.min((c >> 16) & 255, Math.min((c >> 8) & 255, c & 255));
                    if (m < worst) worst = m;
                    if ((c >>> 24) != 255) worst = -1;
                }
                check(worst >= 250, "Flatten giấy trắng " + w + "x" + h + ": tối nhất " + worst + " (phải >= 250, alpha 255)");
                check(f == null, "Flatten giấy trắng " + w + "x" + h + ": không nắn");
            } catch (Throwable t) {
                fail("Flatten giấy trắng " + w + "x" + h, t);
            }
        }
        // trang chữ mấp mô tổng hợp (cỡ, hướng, bảng, nếp gấp, ngón tay ngẫu nhiên): không lỗi, alpha 255, trường hợp lệ,
        // chạy lại cho cùng kết quả; dewarp = false không nắn và chỉ khác kết quả lọc ở bước nắn
        int pages = Math.max(4, rounds / 6), nField = 0, nVert = 0, nVertRot = 0, nRot = 0, nSplit = 0;
        float worstShift = 0;
        for (int i = 0; i < pages; i++) {
            // một trang cỡ thật (cạnh dài tới 3508 = A4 300 dpi, khối phân tích k = 3, 4), vài trang k = 2, còn lại k = 1
            int L = i == 0 ? 2500 + rnd.nextInt(1009) : i % 4 == 3 ? 1500 + rnd.nextInt(1000) : 380 + rnd.nextInt(920);
            double asp = 0.65 + 0.35 * rnd.nextDouble();
            boolean landscape = rnd.nextInt(4) == 0, vertical = rnd.nextInt(3) == 0, table = rnd.nextBoolean();
            boolean fold = rnd.nextBoolean(), finger = rnd.nextInt(4) == 0;
            int[][] pg = wavyPage(L, asp, landscape, vertical, table, fold, finger);
            int w = pg[0][0], h = pg[0][1];
            String what = String.format("Flatten trang mấp mô #%d %dx%d%s%s%s%s", i, w, h, vertical ? " dọc" : "", table ? " bảng" : "",
                    fold ? " nếp" : "", finger ? " tay" : "");
            try {
                int[] c = pg[1].clone();
                Flatten.Field f = Flatten.apply(c, w, h);
                checks++;
                check(opaque(c), what + ": alpha = 255");
                if (vertical) nVert++;
                if (f != null) {
                    nField++;
                    if (f.rotated) nRot++;
                    if (f.rotated && vertical) nVertRot++;
                    worstShift = Math.max(worstShift, f.maxShift);
                    checkField(f, w, h, what);
                }
                // trang lớn (L >= 1500): không chạy lại (giữ thời gian chạy) — trừ khi chưa trang nào kiểm tra tách bước nắn
                boolean small = L < 1500;
                if (small && i % 3 == 1) {
                    int[] c2 = pg[1].clone();
                    Flatten.Field f2 = Flatten.apply(c2, w, h);
                    check(java.util.Arrays.equals(c, c2) && sameField(f, f2), what + ": chạy lại phải cho cùng kết quả");
                }
                if ((small && i % 3 != 1) || (f != null && nSplit == 0)) {
                    // dewarp = false (ảnh xem trước của Xóa thông minh, Enhancer geometric = false): cùng bước làm sạch, chỉ bỏ
                    // nắn — ảnh chỉ làm sạch, nắn theo đúng trường Flatten.apply trả về rồi làm nét (Retouch.sharpen 48, như
                    // Flatten.apply) phải trùng kết quả lọc đầy đủ. Đồng thời xác nhận trường trả về (dòng flt của DumpCore,
                    // EvalFlatten dùng) đúng là trường đã dùng để nắn ảnh.
                    int[] c3 = pg[1].clone();
                    check(Flatten.apply(c3, w, h, false) == null, what + ": dewarp = false không được nắn");
                    check(opaque(c3), what + ": dewarp = false: alpha = 255");
                    if (f == null) {
                        check(java.util.Arrays.equals(c, c3), what + ": không nắn -> dewarp = false trùng kết quả lọc");
                    } else {
                        Flatten.remap(c3, w, h, f);
                        Retouch.sharpen(c3, w, h, 48);
                        check(java.util.Arrays.equals(c, c3),
                                what + ": dewarp = false rồi nắn theo trường trả về + làm nét phải trùng kết quả lọc đầy đủ");
                        nSplit++;
                    }
                }
            } catch (Throwable t) {
                fail(what, t);
            }
        }
        // chỉ thống kê (độ chính xác nắn do EvalFlatten kiểm tra): trang dòng chữ dọc có được nhận ra xoay ngang không
        System.out.printf("   trang mấp mô tổng hợp: %d/%d có nắn (%d trang so với dewarp = false), dịch tối đa %.1f; nhận ra xoay ngang"
                + " %d trang (dòng chữ dọc: %d/%d)%n", nField, pages, nSplit, worstShift, nRot, nVertRot, nVert);
        // kiểm tra trường nắn (checkField) và tách bước nắn ở trên chỉ chạy trên trang được nắn: phải có, không thì chúng
        // không kiểm gì (trang được nắn đầu tiên luôn được kiểm tra tách bước nắn, nên nSplit > 0 khi nField > 0)
        check(nField > 0, "Flatten: không trang nào trong " + pages + " trang mấp mô tổng hợp được nắn — kiểm tra trường nắn không chạy");
        check(nField == 0 || nSplit > 0, "Flatten: chưa kiểm tra dewarp = false trên trang được nắn");
        // bộ lọc qua Enhancer: chạy lại cho cùng kết quả (cả mẫu nhiễu / sọc / bàn cờ)
        for (int kind = 0; kind <= 8; kind++) {
            int w = 150 + rnd.nextInt(260), h = 150 + rnd.nextInt(260);
            int[] px = pattern(kind, w, h);
            try {
                int[] a = px.clone(), b = px.clone();
                Enhancer.apply(a, w, h, Enhancer.FLATTEN);
                Enhancer.apply(b, w, h, Enhancer.FLATTEN);
                check(java.util.Arrays.equals(a, b), "Làm phẳng mẫu " + kind + " " + w + "x" + h + ": chạy lại phải cho cùng kết quả");
            } catch (Throwable t) {
                fail("Làm phẳng mẫu " + kind + " " + w + "x" + h, t);
            }
        }
    }

    // ------------------------------------------------------------------ lõi Word (DocLayout + DocxWriter)

    static String repeatStr(String s, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(s);
        return sb.toString();
    }

    /** Chữ rác: rỗng / toàn khoảng trắng / ký tự điều khiển, ký hiệu kẻ vạch, dấu tổ hợp, emoji, nửa cặp thay thế lẻ, chuỗi rất dài. */
    static final String[] JUNK_TEXTS = {"", " ", "\t", "\n", "\r\n", "|", "_", "l", "I", "1", "||", "|a", "a|", "|_", "__", "—", "–", "¦x", "x¦",
            "'", "\"", ".,", "-", "=", "~", "─┼─", "¬¯‾", "(", ")", "[]", "e\u0301", "a\u0323\u0302", "\u0301", "\u0323\u0301\u0302\u0300",
            "Vie\u0323\u0302t", "😀", "😀😀😀", "👨\u200D👩\u200D👧", "中文字", "\u200B\u200C\u200D", "\uFEFF", "a\u0000b", "\u0001\u0002\u001F",
            " x ", "<&>\"'", "\uFFFE\uFFFF", "\uDBFF\uDFFF", "\uD83D", "\uDE00x", "a\uD800", "\uD83C\uDDFB\uD83C\uDDF3", "ỵ", "Ỵ", "ạặậẹệ", "Q,;",
            "{}", "a\u00A0b", "…", "Ǻǻ", "ḁḀ", "0", "00", "1.250.000", "Việt", "NAM", "xã", "hội", "aceimnorsuvwxz", repeatStr("W", 5000),
            repeatStr("i", 3000), repeatStr("\u0301", 500), repeatStr("😀", 400), "|" + repeatStr("m", 40) + "|"};

    static String junkText() {
        if (rnd.nextInt(4) == 0) {
            int[] alphabet = "abcxyzACEMÀẾỊỢỮđĐ0189|_-.,:;'()[]/\\ \u0301\u0323\u00A6\u2014中😀".codePoints().toArray();
            StringBuilder sb = new StringBuilder();
            int n = 1 + rnd.nextInt(12);
            for (int i = 0; i < n; i++) sb.appendCodePoint(alphabet[rnd.nextInt(alphabet.length)]);
            return sb.toString();
        }
        return JUNK_TEXTS[rnd.nextInt(JUNK_TEXTS.length)];
    }

    static float junkCoord(int lim) {
        switch (rnd.nextInt(14)) {
            case 0: return Float.NaN;
            case 1: return Float.POSITIVE_INFINITY;
            case 2: return Float.NEGATIVE_INFINITY;
            case 3: return -1e30f;
            case 4: return 1e30f;
            case 5: return Float.MAX_VALUE;
            case 6: return -5f;
            case 7: return lim + 7f;
            case 8: return lim;
            case 9: return 0f;
            case 10: return -0f;
            case 11: return Float.MIN_VALUE;
            default: return rnd.nextFloat() * lim;
        }
    }

    /** n từ rác trên ảnh w x h: khung NaN / vô cực / âm / ngoài ảnh / đảo ngược / diện tích 0, chữ lạ, conf và số dòng bất thường. */
    static List<DocLayout.Word> junkWords(int w, int h, int n) {
        List<DocLayout.Word> out = new ArrayList<>();
        int[] lines = {-1, 0, 1, 7, Integer.MAX_VALUE, Integer.MIN_VALUE, 0};
        float[] confs = {1f, 1f, 1f, 0.5f, 0.49f, 0f, -1f, 2f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 0f};
        for (int i = 0; i < n; i++) {
            float x0 = rnd.nextFloat() * w, y0 = rnd.nextFloat() * h;
            float x1 = x0 + 1 + rnd.nextFloat() * 90, y1 = y0 + 2 + rnd.nextFloat() * 40;
            switch (rnd.nextInt(10)) {
                case 0: x0 = junkCoord(w); break;
                case 1: y1 = junkCoord(h); break;
                case 2: x0 = junkCoord(w); y0 = junkCoord(h); x1 = junkCoord(w); y1 = junkCoord(h); break;
                case 3: { float t = x0; x0 = x1; x1 = t; t = y0; y0 = y1; y1 = t; break; }
                case 4: x1 = x0; break;
                case 5: x0 = -40; x1 = rnd.nextFloat() * 2; y1 = y0 + 30 + rnd.nextFloat() * h; break;
                case 6: x0 = -50; y0 = -50; x1 = w + 50; y1 = h + 50; break;
                default: break;
            }
            int li = rnd.nextInt(7);
            DocLayout.Word wd = new DocLayout.Word(junkText(), x0, y0, x1, y1, li == 6 ? rnd.nextInt(30) : lines[li]);
            int ci = rnd.nextInt(12);
            wd.conf = ci == 11 ? rnd.nextFloat() : confs[ci];
            int odd = rnd.nextInt(60);
            if (odd == 0) wd.text = null;
            out.add(odd == 1 ? null : wd);
        }
        return out;
    }

    static void fillRect(int[] px, int w, int h, int x0, int y0, int x1, int y1, int argb) {
        for (int y = Math.max(0, y0); y < Math.min(h, y1); y++) {
            for (int x = Math.max(0, x0); x < Math.min(w, x1); x++) px[y * w + x] = argb;
        }
    }

    /** Dòng từ giả (dãy khối chữ nhật) từ xStart tới xEnd, đường chân chữ base (nghiêng theo slope); thêm các từ vào words. */
    static void fakeLine(int[] px, int w, int h, List<DocLayout.Word> words, int[] line, int em, double slope, int xStart, int xEnd, int base) {
        int lw = Math.max(1, em / 2 - 1), gap = Math.max(1, em / 8), xh = Math.max(3, em * 45 / 100), asc = Math.max(4, em * 66 / 100);
        String low = "acemnorsuvwxz", tall = "bdfhklt";
        int x = xStart;
        line[0]++;
        while (x + lw < xEnd) {
            int n = 1 + rnd.nextInt(8);
            StringBuilder sb = new StringBuilder();
            int wx0 = x;
            for (int k = 0; k < n && x + lw < xEnd; k++) {
                boolean isTall = rnd.nextInt(4) == 0;
                int yb = base + (int) (slope * x);
                fillRect(px, w, h, x, yb - (isTall ? asc : xh), x + lw, yb, 0xFF1E1E24);
                sb.append(isTall ? tall.charAt(rnd.nextInt(tall.length())) : low.charAt(rnd.nextInt(low.length())));
                x += lw + gap;
            }
            int yb = base + (int) (slope * wx0);
            float jx = rnd.nextFloat() * 2 - 1, jy = rnd.nextFloat() * 2 - 1;
            DocLayout.Word wd = new DocLayout.Word(sb.toString(), wx0 - 1 + jx, yb - asc - 2 + jy, x - gap + 1 + jx, yb + 2 + jy, line[0]);
            if (rnd.nextInt(3) == 0) wd.conf = 0.5f + 0.5f * rnd.nextFloat();
            words.add(wd);
            x += em / 3 + (rnd.nextInt(9) == 0 ? 3 * em : 0);
        }
    }

    /**
     * Trang "chữ" giả để lõi Word chạy hết các bước (đo từ, dòng, kiểu chữ, bảng, hình): các từ là dãy khối chữ nhật, có
     * bảng kẻ (table), nét bút màu + khung dấu đỏ, dòng chữ nghiêng theo slope. Điểm ảnh ghi vào px; trả về các từ đúng khung.
     */
    static List<DocLayout.Word> wordPage(int[] px, int w, int h, double slope, boolean table) {
        int shade = 236 + rnd.nextInt(20);
        for (int i = 0; i < w * h; i++) px[i] = 0xFF000000 | (shade << 16) | (shade << 8) | (shade - rnd.nextInt(6));
        List<DocLayout.Word> words = new ArrayList<>();
        int em = Math.max(8, h / 48), mx = w / 12;
        int[] line = {0};
        int y = h / 14 + em, pitch = em * 16 / 10;
        int tableAt = table ? 3 + rnd.nextInt(4) : -1;
        for (int k = 0; y + em < h - h / 14; k++) {
            if (k == tableAt && y + 5 * pitch < h - h / 14) {
                // bảng 3 x 3, chữ trong ô
                int t = Math.max(1, em / 8), rh = pitch * 13 / 10;
                int[] cols = {mx, mx + (w - 2 * mx) / 4, mx + (w - 2 * mx) * 6 / 10, w - mx};
                for (int j = 0; j <= 3; j++) fillRect(px, w, h, mx, y + j * rh, w - mx + t, y + j * rh + t, 0xFF18181C);
                for (int c : cols) fillRect(px, w, h, c, y, c + t, y + 3 * rh + t, 0xFF18181C);
                for (int j = 0; j < 3; j++) {
                    for (int c = 0; c < 3; c++) {
                        if (rnd.nextInt(5) != 0) fakeLine(px, w, h, words, line, em, slope, cols[c] + em / 2, cols[c + 1] - em / 2, y + j * rh + rh * 7 / 10);
                    }
                }
                y += 3 * rh + pitch;
            } else {
                int indent = rnd.nextInt(5) == 0 ? 2 * em : 0;
                int end = w - mx - (rnd.nextInt(4) == 0 ? rnd.nextInt(w / 3) : 0);
                fakeLine(px, w, h, words, line, em, slope, mx + indent, end, y);
                y += pitch + (rnd.nextInt(7) == 0 ? pitch / 2 : 0);
            }
        }
        // nét bút xanh chéo + khung dấu đỏ
        int sx = rnd.nextInt(Math.max(1, w / 2)), sy = h / 2 + rnd.nextInt(Math.max(1, h / 3));
        for (int q = 0; q < w / 4; q++) fillRect(px, w, h, sx + q, sy + q / 3, sx + q + 2, sy + q / 3 + 2, 0xFF1E3CBE);
        int bx = w / 2 + rnd.nextInt(Math.max(1, w / 4)), by = h * 3 / 4, bs = 3 * em;
        fillRect(px, w, h, bx, by, bx + bs, by + 2, 0xFFC81E28);
        fillRect(px, w, h, bx, by + bs, bx + bs + 2, by + bs + 2, 0xFFC81E28);
        fillRect(px, w, h, bx, by, bx + 2, by + bs, 0xFFC81E28);
        fillRect(px, w, h, bx + bs, by, bx + bs + 2, by + bs, 0xFFC81E28);
        return words;
    }

    static int wordFuzzFiles = 0;
    static final byte[] TINY_PNG = DumpCore.pngStored(new int[]{0xFFC81E28}, 1, 1);

    static boolean finite(double v) {
        return !Double.isNaN(v) && !Double.isInfinite(v);
    }

    /** Mọi số thực trong mô hình trang (dòng của DumpCore.modelText) phải hữu hạn. */
    static boolean modelFinite(String model) {
        for (String tok : model.split("[ \n]")) {
            if (tok.length() != 16) continue;
            try {
                long v = Long.parseUnsignedLong(tok, 16);
                if (((v >>> 52) & 0x7ff) == 0x7ff) return false;
            } catch (NumberFormatException e) {
                // không phải số hex (chữ của một mẩu)
            }
        }
        return true;
    }

    /**
     * Kiểm một kết quả phân tích: khổ trang hữu hạn, hình nằm trong ảnh và cắt được, mô hình hữu hạn, document.xml đúng cú
     * pháp XML, ghi được file .docx (vào outDir để kiểm bằng Python: mở bằng zipfile, cấu trúc OOXML).
     */
    static void checkWordResult(DocLayout.Result r, int w, int h, String what, File outDir) throws Exception {
        WordDoc.Page page = r.page;
        check(finite(page.width) && finite(page.height) && page.width > 0 && page.height > 0, what + ": khổ trang " + page.width + " x " + page.height);
        for (WordDoc.Pic pic : page.pics) {
            boolean ok = pic.px0 >= 0 && pic.py0 >= 0 && pic.px1 <= Math.max(w, 0) && pic.py1 <= Math.max(h, 0) && pic.px1 > pic.px0 && pic.py1 > pic.py0;
            check(ok || w < 1 || h < 1, what + ": vùng cắt hình ngoài ảnh (" + pic.px0 + ", " + pic.py0 + ", " + pic.px1 + ", " + pic.py1 + ")");
            if (!ok) continue;
            int[] pix = r.picture(pic);
            int pw = pic.px1 - pic.px0, ph = pic.py1 - pic.py0;
            check(pix.length == pw * ph, what + ": picture trả về " + pix.length + " điểm cho hình " + pw + " x " + ph);
            pic.data = pw * ph <= 4096 && pix.length == pw * ph ? DumpCore.pngStored(pix, pw, ph) : TINY_PNG;
            pic.ext = "png";
        }
        check(modelFinite(DumpCore.modelText(r)), what + ": mô hình trang có số không hữu hạn");
        r.release();
        if (!page.pics.isEmpty()) {
            WordDoc.Pic pic = page.pics.get(0);
            if (pic.px1 > pic.px0 && pic.py1 > pic.py0 && (long) (pic.px1 - pic.px0) * (pic.py1 - pic.py0) <= 1 << 22) {
                int[] white = r.picture(pic);
                boolean all = white.length == (pic.px1 - pic.px0) * (pic.py1 - pic.py0);
                for (int c : white) if (c != 0xFFFFFFFF) all = false;
                check(all, what + ": picture sau release phải trắng");
            }
        }
        List<WordDoc.Page> one = new ArrayList<>();
        one.add(page);
        String xml = DocxWriter.documentXml(one);
        check(xml.startsWith("<?xml") && xml.endsWith("</w:body></w:document>"), what + ": document.xml đầu / cuối");
        try {
            javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new java.io.ByteArrayInputStream(xml.getBytes("UTF-8")));
            checks++;
        } catch (org.xml.sax.SAXException e) {
            fail(what + ": document.xml không đúng cú pháp XML", e);
        }
        byte[] docx = DocxWriter.toBytes(one, what, "2026-01-01T00:00:00Z");
        check(docx.length > 100 && docx[0] == 0x50 && docx[1] == 0x4B, what + ": file .docx");
        if (outDir != null && wordFuzzFiles < 600) {
            try (FileOutputStream fo = new FileOutputStream(new File(outDir, "word_fuzz_" + wordFuzzFiles + ".docx"))) { fo.write(docx); }
        }
        wordFuzzFiles++;
    }

    static void fuzzWord(int rounds, File outDir) {
        // khổ trang / trang chỉ có ảnh với kích thước và khổ giấy bất kỳ
        int[] dims = {0, 1, -5, 31, 32, 1000, 100000, Integer.MAX_VALUE};
        for (int w : dims) {
            for (int h : dims) {
                for (int mode : new int[]{-1, 0, 1, 2, 3, 99}) {
                    try {
                        DocLayout.Geometry g = DocLayout.geometry(w, h, mode);
                        check(finite(g.pageW) && finite(g.pageH) && finite(g.scale) && g.scale > 0 && g.pageW > 0 && g.pageH > 0,
                                "geometry " + w + " x " + h + " khổ " + mode);
                        WordDoc.Page page = DocLayout.picturePage(w, h, mode);
                        check(page.pics.size() == 1 && page.width == g.pageW && page.height == g.pageH, "picturePage " + w + " x " + h + " khổ " + mode);
                        if (mode >= 0 && mode <= 2 && (w == 1 || w == 1000)) {
                            page.pics.get(0).data = TINY_PNG;
                            page.pics.get(0).ext = "png";
                            List<WordDoc.Page> one = new ArrayList<>();
                            one.add(page);
                            byte[] docx = DocxWriter.toBytes(one, "ảnh " + w + "x" + h, "2026-01-01T00:00:00Z");
                            check(docx.length > 100, "picturePage " + w + " x " + h + ": file .docx");
                            try (FileOutputStream fo = new FileOutputStream(new File(outDir, "word_fuzz_pic_" + w + "_" + h + "_" + mode + ".docx"))) { fo.write(docx); }
                        }
                    } catch (Throwable t) {
                        fail("Word geometry / picturePage " + w + " x " + h + " khổ " + mode, t);
                    }
                }
            }
        }
        // ảnh rất nhỏ / rất dẹt / một màu / nhiễu với các từ rác
        int[][] sizes = {{1, 1}, {2, 2}, {8, 8}, {31, 31}, {31, 200}, {32, 32}, {33, 32}, {32, 33}, {40, 2000}, {2000, 40}, {47, 61}, {64, 64},
                {100, 100}, {333, 111}, {640, 480}, {480, 640}};
        for (int[] sz : sizes) {
            int w = sz[0], h = sz[1];
            for (int kind = 0; kind <= 8; kind++) {
                String what = "Word mẫu " + kind + " " + w + "x" + h;
                try {
                    int[] px = pattern(kind, w, h);
                    DocLayout.Result r = DocLayout.analyze(px, w, h, junkWords(w, h, rnd.nextInt(60)), rnd.nextInt(3));
                    checks++;
                    checkWordResult(r, w, h, what, outDir);
                    if (w < 32 || h < 32) check(r.page.pics.isEmpty() && r.page.blocks.isEmpty(), "Word " + w + "x" + h + ": ảnh quá nhỏ -> trang trống");
                } catch (Throwable t) {
                    fail(what, t);
                }
            }
        }
        // tham số null / mảng điểm ảnh ngắn hơn ảnh: trang trống, không ném lỗi
        try {
            check(DocLayout.analyze(null, 64, 64, junkWords(64, 64, 5), 0).page.blocks.isEmpty(), "Word: ảnh null");
            DocLayout.Result r = DocLayout.analyze(new int[100], 64, 64, junkWords(64, 64, 5), 0);
            check(r.page.pics.isEmpty() && r.page.blocks.isEmpty() && r.lines == 0, "Word: mảng điểm ảnh ngắn hơn ảnh");
            DocLayout.Result e = DocLayout.analyze(new int[0], 0, 0, null, 0);
            check(e.page.blocks.isEmpty(), "Word: ảnh 0 x 0, không có danh sách từ");
            e.picture(new WordDoc.Pic());
            DocLayout.Result n = DocLayout.analyze(pattern(3, 80, 80), 80, 80, null, 2);
            check(n.page.pics.size() == 1, "Word: danh sách từ null -> trang chỉ có ảnh");
            checks++;
        } catch (Throwable t) {
            fail("Word tham số null / mảng ngắn", t);
        }
        // từ "|x" / "x|" hẹp hơn 1 điểm ảnh sát mép ảnh, cạnh một đường kẻ dọc: gọt ký tự đường kẻ rồi đo lại từng làm
        // measureWord đọc ngoài mảng (ArrayIndexOutOfBoundsException)
        try {
            int w = 300, h = 300;
            int[] px = new int[w * h];
            java.util.Arrays.fill(px, 0xFFFFFFFF);
            fillRect(px, w, h, 20, 0, 23, h, 0xFF101010);
            fillRect(px, w, h, w - 23, 0, w - 20, h, 0xFF101010);
            fillRect(px, w, h, 0, 10, 1, 41, 0xFF101010);
            fillRect(px, w, h, w - 1, 10, w, 41, 0xFF101010);
            List<DocLayout.Word> words = new ArrayList<>();
            words.add(new DocLayout.Word("|x", -3f, -2f, 0.6f, 100f, 1));
            words.add(new DocLayout.Word("x|", w - 0.6f, -2f, w + 3f, 100f, 1));
            words.add(new DocLayout.Word("¦ab", -9f, 150f, 0.9f, 260f, 2));
            checkWordResult(DocLayout.analyze(px, w, h, words, 0), w, h, "Word từ sát mép cạnh đường kẻ dọc", outDir);
            checks++;
        } catch (Throwable t) {
            fail("Word từ sát mép cạnh đường kẻ dọc", t);
        }
        // trang chữ giả (bảng, nghiêng, nét màu) + từ rác: chạy hết các bước; chạy lại phải cho cùng kết quả
        int nTables = 0, nSkew = 0, nLines = 0, nPics = 0;
        for (int i = 0; i < Math.max(8, rounds / 2); i++) {
            int w = 200 + rnd.nextInt(700), h = 200 + rnd.nextInt(900);
            String what = "Word trang chữ giả #" + i + " " + w + "x" + h;
            try {
                double slope = i % 3 == 2 ? (rnd.nextDouble() - 0.5) * 0.08 : 0;
                int[] px = new int[w * h];
                List<DocLayout.Word> words = wordPage(px, w, h, slope, i % 2 == 0);
                if (i % 4 != 3) words.addAll(junkWords(w, h, rnd.nextInt(40)));
                int mode = i % 3;
                DocLayout.Result r = DocLayout.analyze(px, w, h, words, mode);
                checks++;
                nTables += r.tables;
                nLines += r.lines;
                nPics += r.page.pics.size();
                if (r.skew != 0) nSkew++;
                String model = DumpCore.modelText(r);
                checkWordResult(r, w, h, what, outDir);
                if (i % 4 == 0) check(DumpCore.modelText(DocLayout.analyze(px, w, h, words, mode)).equals(model), what + ": chạy lại phải cho cùng kết quả");
            } catch (Throwable t) {
                fail(what, t);
            }
        }
        check(nTables > 0 && nLines > 0 && nPics > 0, "Word: trang chữ giả phải có bảng (" + nTables + "), dòng chữ (" + nLines + "), hình (" + nPics + ")");
        System.out.printf("   Word: trang chữ giả: %d dòng, %d bảng, %d hình, %d trang khử nghiêng%n", nLines, nTables, nPics, nSkew);
        // hàng nghìn từ trùng nhau (dưới và trên ngưỡng 6000 của bước dọn khung trùng), lẫn rác
        for (int n : new int[]{3000, 6500}) {
            String what = "Word " + n + " từ trùng nhau";
            try {
                int w = 420, h = 300;
                int[] px = new int[w * h];
                DocLayout.Word first = wordPage(px, w, h, 0, false).get(0);
                List<DocLayout.Word> words = new ArrayList<>();
                for (int k = 0; k < n; k++) {
                    DocLayout.Word wd = new DocLayout.Word(k % 5 == 4 ? "|" : first.text, first.x0, first.y0, first.x1, first.y1, first.line);
                    if (k % 7 == 3) wd.conf = 0.9f;
                    words.add(wd);
                }
                words.addAll(junkWords(w, h, 50));
                long t0 = System.currentTimeMillis();
                DocLayout.Result r = DocLayout.analyze(px, w, h, words, 0);
                checks++;
                checkWordResult(r, w, h, what, outDir);
                check(System.currentTimeMillis() - t0 < 60000, what + ": quá chậm");
            } catch (Throwable t) {
                fail(what, t);
            }
        }
        // ảnh lớn (cỡ trang thật): trang chữ giả + rác; nhiễu + rác
        try {
            int w = 1240, h = 1754;
            int[] px = new int[w * h];
            List<DocLayout.Word> words = wordPage(px, w, h, 0.01, true);
            words.addAll(junkWords(w, h, 300));
            checkWordResult(DocLayout.analyze(px, w, h, words, 0), w, h, "Word trang lớn " + w + "x" + h, outDir);
            checkWordResult(DocLayout.analyze(pattern(3, 1000, 1400), 1000, 1400, junkWords(1000, 1400, 400), 2), 1000, 1400, "Word nhiễu 1000x1400", outDir);
            checks++;
        } catch (Throwable t) {
            fail("Word ảnh lớn", t);
        }
        // DocxWriter: tài liệu rỗng, tiêu đề / thời điểm lạ, hình không có dữ liệu, nhiều trang
        try {
            try {
                DocxWriter.toBytes(new ArrayList<WordDoc.Page>(), "x", null);
                fail("DocxWriter phải từ chối tài liệu không có trang", null);
            } catch (java.io.IOException ex) {
                checks++;
            }
            List<WordDoc.Page> pages = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                int w = 300 + 40 * i, h = 260 + 55 * i;
                int[] px = new int[w * h];
                DocLayout.Result r = DocLayout.analyze(px, w, h, wordPage(px, w, h, 0, i % 2 == 1), i % 3);
                for (int k = 0; k < r.page.pics.size(); k += 2) r.page.pics.get(k).data = TINY_PNG;
                r.release();
                pages.add(r.page);
            }
            pages.add(new WordDoc.Page());
            pages.add(DocLayout.picturePage(10, 10, 0));
            String[] titles = {"", "Biên bản <số 12> & \"phụ lục\" 😀 ấệữ", "a\u0000b\u0001\uFFFE\tc\n\uD83D", repeatStr("Tên rất dài ", 400), null};
            for (int k = 0; k < titles.length; k++) {
                byte[] docx = DocxWriter.toBytes(pages, titles[k], k == 2 ? "<lạ & \"\u0002>" : (k == 4 ? null : "2026-01-01T00:00:00Z"));
                check(docx.length > 100, "DocxWriter nhiều trang, tiêu đề " + k);
                try (FileOutputStream fo = new FileOutputStream(new File(outDir, "word_fuzz_multi_" + k + ".docx"))) { fo.write(docx); }
            }
        } catch (Throwable t) {
            fail("DocxWriter nhiều trang / tiêu đề lạ", t);
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
