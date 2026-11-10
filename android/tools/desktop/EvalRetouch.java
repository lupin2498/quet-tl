import vn.quettailieu.scanner.core.Enhancer;
import vn.quettailieu.scanner.core.Retouch;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Kiểm thử phần làm đẹp / chỉnh sửa trang (Retouch + bộ lọc mới) trên ảnh tổng hợp của
 * tests/tools/make_retouch_images.py.
 *
 * Cách chạy: java -cp out EvalRetouch <thư-mục-ảnh-ppm> [thư-mục-ghi-ảnh-kết-quả]
 * Mã thoát khác 0 nếu có kiểm tra không đạt.
 */
public class EvalRetouch {
    static int fails = 0, checks = 0;
    static File outDir;

    static void check(boolean ok, String what) {
        checks++;
        if (!ok) fails++;
        System.out.println((ok ? "PASS " : "FAIL ") + what);
    }

    static final class Img {
        int w, h;
        int[] px;

        Img copy() {
            Img c = new Img();
            c.w = w; c.h = h; c.px = px.clone();
            return c;
        }
    }

    static Img load(File dir, String name) throws Exception {
        int[][] r = DumpCore.readPPM(new File(dir, name + ".ppm"));
        Img i = new Img();
        i.w = r[0][0]; i.h = r[0][1]; i.px = r[1];
        return i;
    }

    static void dump(Img i, String name) throws Exception {
        if (outDir != null) DumpCore.writePPM(new File(outDir, name + ".ppm"), i.px, i.w, i.h);
    }

    static int lum(int c) {
        return (299 * ((c >> 16) & 255) + 587 * ((c >> 8) & 255) + 114 * (c & 255)) / 1000;
    }

    interface Test {
        boolean is(int c);
    }

    static final Test SKIN = new Test() {
        public boolean is(int c) {
            int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
            return r - b > 36 && r > g + 14 && g >= b - 6 && r > 70;
        }
    };
    static final Test BLUE = new Test() {
        public boolean is(int c) {
            int r = (c >> 16) & 255, b = c & 255;
            return b - r > 45;
        }
    };
    static final Test RED = new Test() {
        public boolean is(int c) {
            int r = (c >> 16) & 255, g = (c >> 8) & 255;
            return r - g > 70;
        }
    };
    static final Test DARK = new Test() {
        public boolean is(int c) {
            return lum(c) < 110;
        }
    };

    /** Đếm điểm ảnh thoả điều kiện trong khung chuẩn hoá [x0,x1]x[y0,y1]. */
    static int count(Img im, double x0, double y0, double x1, double y1, Test t) {
        int ax = Math.max(0, (int) Math.round(x0 * im.w)), bx = Math.min(im.w, (int) Math.round(x1 * im.w));
        int ay = Math.max(0, (int) Math.round(y0 * im.h)), by = Math.min(im.h, (int) Math.round(y1 * im.h));
        int n = 0;
        for (int y = ay; y < by; y++) for (int x = ax; x < bx; x++) if (t.is(im.px[y * im.w + x])) n++;
        return n;
    }

    static int count(Img im, Test t) {
        return count(im, 0, 0, 1, 1, t);
    }

    static double meanLum(Img im, double x0, double y0, double x1, double y1) {
        int ax = (int) Math.round(x0 * im.w), bx = (int) Math.round(x1 * im.w);
        int ay = (int) Math.round(y0 * im.h), by = (int) Math.round(y1 * im.h);
        long s = 0;
        int n = 0;
        for (int y = ay; y < by; y++) for (int x = ax; x < bx; x++) { s += lum(im.px[y * im.w + x]); n++; }
        return n == 0 ? 0 : s / (double) n;
    }

    static Retouch.Op op(int kind, double width, double... pts) {
        float[] p = new float[pts.length];
        for (int i = 0; i < p.length; i++) p[i] = (float) pts[i];
        return new Retouch.Op(kind, (float) width, p);
    }

    static List<Retouch.Op> list(Retouch.Op... ops) {
        List<Retouch.Op> l = new ArrayList<>();
        for (Retouch.Op o : ops) l.add(o);
        return l;
    }

    public static void main(String[] a) throws Exception {
        File in = new File(a[0]);
        outDir = a.length > 1 ? new File(a[1]) : null;
        if (outDir != null) outDir.mkdirs();
        long t0 = System.nanoTime();

        // ---- phát hiện ngón tay
        String[] yes = {"rt_finger_l", "rt_finger_b", "rt_finger_c"};
        String[] no = {"rt_plain", "rt_stamp", "rt_strip", "rt_photo", "rt_hand", "rt_shadow", "rt_edge"};
        for (String n : yes) {
            Img im = load(in, n);
            check(Retouch.hasFinger(im.px, im.w, im.h), n + ": phát hiện ngón tay");
            int before = count(im, SKIN);
            Img c = im.copy();
            boolean removed = Retouch.removeFingers(c.px, c.w, c.h);
            int after = count(c, SKIN);
            dump(c, n + "_nofinger");
            check(removed && after * 100 <= before * 3, String.format(Locale.US, "%s: gỡ ngón tay (điểm màu da %d -> %d)", n, before, after));
            Img m = im.copy();
            Enhancer.apply(m.px, m.w, m.h, Enhancer.MAGIC_PRO);
            dump(m, n + "_pro");
            int left = count(m, SKIN);
            check(left * 200 <= before, String.format(Locale.US, "%s: Thần kỳ không còn ngón tay (%d -> %d)", n, before, left));
        }
        for (String n : no) {
            Img im = load(in, n);
            check(!Retouch.hasFinger(im.px, im.w, im.h), n + ": không báo nhầm ngón tay");
        }

        // ---- bộ lọc mới trên trang sạch: nền trắng / chữ còn đậm
        Img plain = load(in, "rt_plain");
        for (int mode : new int[]{Enhancer.MAGIC_PRO, Enhancer.NO_SHADOW, Enhancer.NO_HAND, Enhancer.DENOISE}) {
            Img c = plain.copy();
            Enhancer.apply(c.px, c.w, c.h, mode);
            dump(c, "rt_plain_f" + mode);
            double paper = meanLum(c, 0.30, 0.02, 0.70, 0.05);
            int ink = count(c, 0.17, 0.25, 0.53, 0.60, DARK);
            int ink0 = count(plain, 0.17, 0.25, 0.53, 0.60, DARK);
            boolean white = mode == Enhancer.DENOISE ? paper > 150 : paper >= (mode == Enhancer.MAGIC_PRO ? 250 : 236);
            check(white, String.format(Locale.US, "%s: nền giấy %.1f", Enhancer.name(mode), paper));
            check(ink * 10 >= ink0 * 7, String.format(Locale.US, "%s: chữ còn đậm (%d / %d điểm tối)", Enhancer.name(mode), ink, ink0));
        }
        // trang không có gì để gỡ: Thần kỳ không được làm mất nội dung
        {
            Img c = plain.copy();
            Enhancer.apply(c.px, c.w, c.h, Enhancer.MAGIC_PRO);
            Img m = plain.copy();
            Enhancer.apply(m.px, m.w, m.h, Enhancer.MAGIC);
            int a1 = count(c, DARK), a2 = count(m, DARK);
            check(a1 * 100 >= a2 * 80 && a1 * 100 <= a2 * 135, "Thần kỳ giữ nguyên nội dung trang sạch (" + a1 + " so với Tăng cường " + a2 + ")");
        }

        // ---- bóng đổ
        Img shadow = load(in, "rt_shadow");
        double d0 = Math.abs(meanLum(shadow, 0.05, 0.02, 0.25, 0.05) - meanLum(shadow, 0.72, 0.955, 0.95, 0.985));
        for (int mode : new int[]{Enhancer.NO_SHADOW, Enhancer.MAGIC_PRO}) {
            Img c = shadow.copy();
            Enhancer.apply(c.px, c.w, c.h, mode);
            dump(c, "rt_shadow_f" + mode);
            double lit = meanLum(c, 0.05, 0.02, 0.25, 0.05), dim = meanLum(c, 0.72, 0.955, 0.95, 0.985);
            check(Math.abs(lit - dim) <= 10 && dim >= 228, String.format(Locale.US, "%s: hết bóng (chênh %.1f -> %.1f, vùng bóng %.1f)", Enhancer.name(mode), d0, Math.abs(lit - dim), dim));
            // dải chuyển sáng-tối không để lại vệt xám
            double worst = 255;
            for (int k = 0; k < 20; k++) {
                double x = 0.3 + 0.03 * k;
                worst = Math.min(worst, meanLum(c, x, 0.955, x + 0.02, 0.985));
            }
            check(worst >= 205, String.format(Locale.US, "%s: mép bóng không còn vệt (tối nhất %.1f)", Enhancer.name(mode), worst));
        }

        // ---- dọn mép
        {
            Img e = load(in, "rt_edge");
            Img c = e.copy();
            Enhancer.apply(c.px, c.w, c.h, Enhancer.MAGIC_PRO);
            dump(c, "rt_edge_pro");
            int top = count(c, 0, 0, 1, 0.012, DARK), right = count(c, 0.985, 0.2, 1, 1, DARK), bottom = count(c, 0, 0.992, 0.45, 1, DARK);
            check(top + right + bottom <= 40, "Thần kỳ dọn vệt tối sát mép (còn " + (top + right + bottom) + " điểm)");
            int ink = count(c, 0.17, 0.25, 0.53, 0.60, DARK), ink0 = count(e, 0.17, 0.25, 0.53, 0.60, DARK);
            check(ink * 10 >= ink0 * 7, "Dọn mép không làm mất chữ (" + ink + " / " + ink0 + ")");
        }

        // ---- chữ viết tay
        {
            Img hd = load(in, "rt_hand");
            Img c = hd.copy();
            int b0 = count(hd, BLUE), r0 = count(hd, 0.60, 0.82, 0.86, 0.985, RED), k0 = count(hd, 0.10, 0.25, 0.92, 0.75, DARK);
            int tick0 = count(hd, 0.92, 0.34, 1.0, 0.40, RED);
            boolean any = Retouch.eraseHandwriting(c.px, c.w, c.h, null);
            dump(c, "rt_hand_erased");
            int b1 = count(c, BLUE), r1 = count(c, 0.60, 0.82, 0.86, 0.985, RED), k1 = count(c, 0.10, 0.25, 0.92, 0.75, DARK);
            int tick1 = count(c, 0.92, 0.34, 1.0, 0.40, RED);
            check(any && b0 > 500 && b1 * 100 <= b0 * 2, "Xoá bút xanh/tím (" + b0 + " -> " + b1 + ")");
            check(r1 * 100 >= r0 * 85, "Giữ con dấu đỏ (" + r0 + " -> " + r1 + ")");
            check(tick0 > 20 && tick1 * 10 <= tick0, "Xoá dấu tích đỏ nhỏ (" + tick0 + " -> " + tick1 + ")");
            check(Math.abs(k1 - k0) * 100 <= k0 * 6, "Giữ chữ in đen (" + k0 + " -> " + k1 + ")");
            Img f = hd.copy();
            Enhancer.apply(f.px, f.w, f.h, Enhancer.NO_HAND);
            dump(f, "rt_hand_f8");
            check(count(f, BLUE) * 100 <= b0 * 2, "Bộ lọc Ko viết tay xoá bút xanh");
            Img p = plain.copy();
            check(!Retouch.eraseHandwriting(p.px, p.w, p.h, null), "Trang không có bút màu: không đổi");
            // Thần kỳ (xoá thông minh): tô một đoạn giữa nét chữ ký xanh -> mất cả nét
            Img mg = hd.copy();
            int s0 = count(hd, 0.13, 0.86, 0.43, 0.95, BLUE);
            Retouch.applyErase(mg.px, mg.w, mg.h, list(op(Retouch.MAGIC, 0.03, 0.25, 0.89, 0.30, 0.92)));
            dump(mg, "rt_hand_magic");
            int s1 = count(mg, 0.13, 0.86, 0.43, 0.95, BLUE);
            check(s0 > 300 && s1 * 100 <= s0 * 6, "Thần kỳ xoá cả nét bút dính liền (" + s0 + " -> " + s1 + ")");
            check(count(mg, 0.60, 0.82, 0.86, 0.985, RED) * 100 >= r0 * 97, "Thần kỳ không đụng tới con dấu ở xa");
        }

        // ---- bút / hình chữ nhật / xoá văn bản trên trang sạch
        {
            // hàng "Bàn phím" của bảng: y 488..532 (trên 1400), cột tên 170..520 (trên 990)
            double ry0 = 488 / 1400.0, ry1 = 532 / 1400.0, rc = 510 / 1400.0;
            Img c = plain.copy();
            int t0c = count(plain, 0.18, ry0 + 0.004, 0.50, ry1 - 0.004, DARK);
            Retouch.applyErase(c.px, c.w, c.h, list(op(Retouch.PEN, 0.03, 0.19, rc, 0.50, rc)));
            dump(c, "rt_plain_pen");
            int t1c = count(c, 0.19, rc - 0.008, 0.50, rc + 0.008, DARK);
            check(t0c > 200 && t1c == 0, "Bút xoá chữ dưới nét tô (" + t0c + " -> " + t1c + ")");
            double paperNear = meanLum(c, 0.25, rc - 0.004, 0.45, rc + 0.004), paperRef = meanLum(plain, 0.25, 0.02, 0.45, 0.04);
            check(Math.abs(paperNear - paperRef) <= 14, String.format(Locale.US, "Bút tô đúng màu giấy (%.1f so với %.1f)", paperNear, paperRef));

            Img r = plain.copy();
            Retouch.applyErase(r.px, r.w, r.h, list(op(Retouch.RECT, 0, 0.12, 0.40, 0.88, 0.40, 0.88, 0.50, 0.12, 0.50)));
            dump(r, "rt_plain_rect");
            check(count(r, 0.125, 0.405, 0.875, 0.495, DARK) == 0, "Hình chữ nhật xoá sạch vùng chọn");
            check(count(r, 0.12, 0.52, 0.88, 0.60, DARK) == count(plain, 0.12, 0.52, 0.88, 0.60, DARK), "Hình chữ nhật không đụng ngoài vùng chọn");

            Img t = plain.copy();
            // tô dọc theo hàng, cắt qua các đường kẻ dọc của bảng
            Retouch.applyErase(t.px, t.w, t.h, list(op(Retouch.TEXT, 0.034, 0.12, rc, 0.89, rc)));
            dump(t, "rt_plain_text");
            int inside = count(t, 0.18, rc - 0.010, 0.515, rc + 0.010, DARK);
            // đường kẻ dọc tại x = 170/990 và 520/990 phải còn
            int line0 = count(plain, 168 / 990.0, rc - 0.008, 174 / 990.0, rc + 0.008, DARK);
            int line1 = count(t, 168 / 990.0, rc - 0.008, 174 / 990.0, rc + 0.008, DARK);
            check(inside * 50 <= t0c, "Xoá văn bản: mất chữ trong ô (" + t0c + " -> " + inside + ")");
            check(line0 > 10 && line1 * 100 >= line0 * 80, "Xoá văn bản: giữ đường kẻ bảng (" + line0 + " -> " + line1 + ")");

            // gộp nhiều thao tác + toạ độ bất thường không làm hỏng
            Img z = plain.copy();
            Retouch.applyErase(z.px, z.w, z.h, list(op(Retouch.PEN, 0.02, 0.2, 0.2, 0.4, 0.2), op(Retouch.RECT, 0, 0.5, 0.1, 0.6, 0.1, 0.6, 0.15, 0.5, 0.15),
                    op(Retouch.PEN, 0.5, -3, -3, 9, 9), op(Retouch.PEN, 0.01, Float.NaN, 0.5, 0.5, Float.POSITIVE_INFINITY), op(Retouch.TEXT, 0.02), op(Retouch.HAND, 0),
                    op(Retouch.MAGIC, 1e9, 0.5, 0.5), null));
            boolean opaque = true;
            for (int v : z.px) if ((v >>> 24) != 255) { opaque = false; break; }
            check(opaque, "Chuỗi thao tác bất thường: không lỗi, ảnh còn nguyên alpha");
        }

        // ---- đổi vùng cắt
        {
            float[] q = {100, 80, 900, 120, 880, 1300, 60, 1250};
            float[] pts = {0.25f, 0.5f, 0.9f, 0.1f};
            float[] same = Retouch.remap(pts, q, q);
            boolean ok = true;
            for (int i = 0; i < pts.length; i++) if (Math.abs(same[i] - pts[i]) > 1e-4) ok = false;
            check(ok, "Đổi vùng cắt: vùng cũ = vùng mới -> toạ độ giữ nguyên");
            float[] full = {0, 0, 1000, 0, 1000, 1400, 0, 1400};
            float[] half = {0, 0, 500, 0, 500, 700, 0, 700};
            float[] mv = Retouch.remap(new float[]{0.25f, 0.25f}, full, half);
            check(Math.abs(mv[0] - 0.5f) < 1e-4 && Math.abs(mv[1] - 0.5f) < 1e-4, "Đổi vùng cắt: điểm theo đúng vị trí trên giấy");
            check(Math.abs(Retouch.remapScale(full, half) - 2f) < 1e-4, "Đổi vùng cắt: bề rộng nét đổi theo tỷ lệ");
            check(Retouch.remap(pts, new float[]{0, 0, 0, 0, 0, 0, 0, 0}, q) == pts, "Đổi vùng cắt: vùng suy biến -> giữ nguyên");
        }

        System.out.printf(Locale.US, "Retouch: %d kiểm tra, %d lỗi, %.1f giây%n", checks, fails, (System.nanoTime() - t0) / 1e9);
        if (fails > 0) System.exit(1);
    }
}
