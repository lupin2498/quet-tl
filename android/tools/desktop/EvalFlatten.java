import vn.quettailieu.scanner.core.Flatten;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Kiểm thử bộ lọc "Làm phẳng" trên trang tổng hợp của tests/tools/make_wavy_images.py, có đáp án hình học:
 * với mỗi điểm ảnh kết quả, ghép trường nắn của bộ lọc với bản đồ biến dạng thật để biết nó lấy từ đâu trên trang phẳng.
 * <ul>
 * <li>độ thẳng: một hàng ngang của ảnh kết quả phải ứng với một hàng ngang của trang phẳng (độ lệch dọc P10–P90);
 * đường kẻ dọc của bảng cũng vậy theo chiều ngang;</li>
 * <li>độ sạch: điểm ứng với nền giấy (xa nét mực) phải trắng — gồm cả vùng vệt nếp gấp, vân tay;</li>
 * <li>giữ nội dung: điểm ứng với lõi nét chữ / đường kẻ phải còn đậm; con dấu đỏ, chữ ký xanh còn màu.</li>
 * </ul>
 * Cách chạy: java -cp out EvalFlatten <thư-mục-ảnh> [thư-mục-ghi-kết-quả] (đáp án ở <thư-mục-ảnh>/gt/).
 * Mã thoát khác 0 nếu có kiểm tra không đạt.
 */
public class EvalFlatten {
    static int fails = 0, checks = 0;
    static final boolean DEBUG = System.getenv("EVAL_DEBUG") != null;

    static void check(boolean ok, String what) {
        checks++;
        if (!ok) fails++;
        System.out.println((ok ? "PASS " : "FAIL ") + what);
    }

    static final class Page {
        String name;
        int w, h;
        int[] px, flat;
        float[] fx, fy;
        int[] box;
        List<int[]> hrule = new ArrayList<>(), vrule = new ArrayList<>(), crease = new ArrayList<>(), print = new ArrayList<>();
        int[] stamp, sign, finger;
    }

    static Page load(File dir, String name) throws Exception {
        Page p = new Page();
        p.name = name;
        int[][] r = DumpCore.readPPM(new File(dir, name + ".ppm"));
        p.w = r[0][0];
        p.h = r[0][1];
        p.px = r[1];
        p.flat = DumpCore.readPPM(new File(dir, "gt/" + name + "_flat.ppm"))[1];
        byte[] mb = DumpCore.readAll(new File(dir, "gt/" + name + "_map.f32"));
        ByteBuffer bb = ByteBuffer.wrap(mb).order(ByteOrder.LITTLE_ENDIAN);
        int n = p.w * p.h;
        p.fx = new float[n];
        p.fy = new float[n];
        for (int i = 0; i < n; i++) p.fx[i] = bb.getFloat();
        for (int i = 0; i < n; i++) p.fy[i] = bb.getFloat();
        for (String line : DumpCore.readLines(new File(dir, "gt/" + name + ".txt"))) {
            String[] t = line.trim().split(" ");
            int[] v = new int[t.length - 1];
            for (int i = 1; i < t.length; i++) v[i - 1] = Integer.parseInt(t[i]);
            switch (t[0]) {
                case "box": p.box = v; break;
                case "hrule": p.hrule.add(v); break;
                case "vrule": p.vrule.add(v); break;
                case "crease": p.crease.add(v); break;
                case "print": p.print.add(v); break;
                case "stamp": p.stamp = v; break;
                case "sign": p.sign = v; break;
                case "finger": p.finger = v; break;
                default: break;
            }
        }
        return p;
    }

    static int lum(int c) {
        return (299 * ((c >> 16) & 255) + 587 * ((c >> 8) & 255) + 114 * (c & 255)) / 1000;
    }

    /** Vị trí lấy mẫu trên ảnh vào của điểm ảnh ra (u, v) theo trường f (như Flatten.remap). */
    static float[] source(Flatten.Field f, int u, int v) {
        if (f == null) return new float[]{u, v};
        int gw = f.gw, gh = f.gh;
        float inv = 1f / f.step;
        float fv = (v - f.ox) * inv, fu = (u - f.ox) * inv;
        int j0 = (int) Math.floor(fv), i0 = (int) Math.floor(fu);
        float tv = fv - j0, tu = fu - i0;
        if (j0 < 0) { j0 = 0; tv = 0f; }
        if (j0 > gh - 2) { j0 = gh - 2; tv = 1f; }
        if (i0 < 0) { i0 = 0; tu = 0f; }
        if (i0 > gw - 2) { i0 = gw - 2; tu = 1f; }
        int o = j0 * gw + i0;
        float dx = (f.dx[o] * (1 - tu) + f.dx[o + 1] * tu) * (1 - tv) + (f.dx[o + gw] * (1 - tu) + f.dx[o + gw + 1] * tu) * tv;
        float dy = (f.dy[o] * (1 - tu) + f.dy[o + 1] * tu) * (1 - tv) + (f.dy[o + gw] * (1 - tu) + f.dy[o + gw + 1] * tu) * tv;
        return new float[]{u + dx, v + dy};
    }

    /** Toạ độ trên trang phẳng của điểm ảnh vào (x, y) (nội suy bản đồ biến dạng thật); null nếu ngoài ảnh. */
    static float[] flatPos(Page p, float x, float y) {
        if (!(x >= 0 && y >= 0 && x <= p.w - 1 && y <= p.h - 1)) return null;
        int x0 = Math.min(p.w - 2, (int) x), y0 = Math.min(p.h - 2, (int) y);
        float ax = x - x0, ay = y - y0;
        int o = y0 * p.w + x0;
        float gx = (p.fx[o] * (1 - ax) + p.fx[o + 1] * ax) * (1 - ay) + (p.fx[o + p.w] * (1 - ax) + p.fx[o + p.w + 1] * ax) * ay;
        float gy = (p.fy[o] * (1 - ax) + p.fy[o + 1] * ax) * (1 - ay) + (p.fy[o + p.w] * (1 - ax) + p.fy[o + p.w + 1] * ax) * ay;
        return new float[]{gx, gy};
    }

    static float pct(float[] a, int n, double q) {
        float[] s = Arrays.copyOf(a, n);
        Arrays.sort(s);
        return s[Math.min(n - 1, (int) (n * q))];
    }

    /** Độ lệch trung bình / lớn nhất (P10–P90) của toạ độ trang phẳng dọc theo các hàng (rows) hoặc cột của ảnh ra. */
    static float[] straightness(Page p, Flatten.Field f, boolean rows, int a0, int a1, int b0, int b1, int[] inkNear) {
        float sum = 0, worst = 0;
        int cnt = 0;
        float[] vals = new float[Math.max(1, (b1 - b0) / 2 + 2)];
        for (int a = a0; a <= a1; a += 3) {
            int n = 0;
            for (int b = b0; b <= b1; b += 2) {
                int u = rows ? b : a, v = rows ? a : b;
                float[] s = source(f, u, v);
                float[] g = flatPos(p, s[0], s[1]);
                if (g == null) continue;
                int gx = Math.round(g[0]), gy = Math.round(g[1]);
                if (inkNear != null && (gx < 0 || gy < 0 || gx >= p.w || gy >= p.h || inkNear[gy * p.w + gx] == 0)) continue;
                vals[n++] = rows ? g[1] : g[0];
            }
            if (n < 8) continue;
            float d = pct(vals, n, 0.9) - pct(vals, n, 0.1);
            if (DEBUG && f != null && d > 4f) System.out.printf(Locale.US, "              %s %d: lệch %.1f (n=%d)%n", rows ? "hàng" : "cột", a, d, n);
            sum += d;
            worst = Math.max(worst, d);
            cnt++;
        }
        return new float[]{cnt > 0 ? sum / cnt : 0, worst};
    }

    public static void main(String[] a) throws Exception {
        File dir = new File(a[0]);
        File outDir = a.length > 1 ? new File(a[1]) : null;
        if (outDir != null) outDir.mkdirs();
        String[] names = dir.list();
        Arrays.sort(names);
        long total = 0;
        for (String fn : names) {
            if (!fn.startsWith("wv_") || !fn.endsWith(".ppm")) continue;
            String name = fn.substring(0, fn.length() - 4);
            Page p = load(dir, name);
            int w = p.w, h = p.h;
            int[] out = p.px.clone();
            long t0 = System.nanoTime();
            Flatten.Field f = Flatten.apply(out, w, h);
            long ms = (System.nanoTime() - t0) / 1000000;
            total += ms;
            if (outDir != null) DumpCore.writePPM(new File(outDir, name + "_out.ppm"), out, w, h);
            boolean blank = name.equals("wv_blank");
            int[] box = p.box != null && p.box[2] > p.box[0] ? p.box : new int[]{0, 0, w - 1, h - 1};
            int[] inkNear = inkNear(p, 4);
            float[] r0 = straightness(p, null, true, box[1], box[3], box[0], box[2], inkNear);
            float[] r1 = straightness(p, f, true, box[1], box[3], box[0], box[2], inkNear);
            String diag = f == null ? "không nắn" : String.format(Locale.US, "%d dòng, %d kẻ ngang, %d kẻ dọc, dịch tối đa %.1f",
                    f.lines, f.hrules, f.vrules, f.maxShift);
            System.out.printf(Locale.US, "%-11s %4d ms  %s  | lệch hàng %.2f/%.2f -> %.2f/%.2f%n", name, ms, diag, r0[0], r0[1], r1[0], r1[1]);
            // ---- độ thẳng
            if (name.equals("wv_plain")) {
                check(f == null || f.maxShift <= 2.0f, name + ": trang phẳng không bị nắn méo (dịch tối đa " + (f == null ? 0 : f.maxShift) + ")");
                check(r1[0] <= 0.8f, String.format(Locale.US, "%s: hàng vẫn thẳng (%.2f)", name, r1[0]));
            } else if (!blank) {
                check(f != null, name + ": phát hiện trang cong vênh");
                check(r1[0] <= Math.max(1.6f, 0.3f * r0[0]), String.format(Locale.US, "%s: dòng chữ / kẻ ngang thẳng lại (lệch TB %.2f -> %.2f)", name, r0[0], r1[0]));
                check(r1[1] <= Math.max(4f, 0.45f * r0[1]), String.format(Locale.US, "%s: không còn đoạn cong lớn (lệch max %.2f -> %.2f)", name, r0[1], r1[1]));
            }
            if (!p.vrule.isEmpty()) {
                int x0 = Integer.MAX_VALUE, x1 = 0, y0 = Integer.MAX_VALUE, y1 = 0;
                for (int[] v : p.vrule) {
                    x0 = Math.min(x0, v[0]);
                    x1 = Math.max(x1, v[0]);
                    y0 = Math.min(y0, v[1]);
                    y1 = Math.max(y1, v[2]);
                }
                float[] c0 = straightness(p, null, false, x0, x1, y0, y1, inkNear);
                float[] c1 = straightness(p, f, false, x0, x1, y0, y1, inkNear);
                System.out.printf(Locale.US, "            lệch cột (vùng bảng) %.2f/%.2f -> %.2f/%.2f%n", c0[0], c0[1], c1[0], c1[1]);
                if (!name.equals("wv_plain")) {
                    check(c1[0] <= Math.max(1.6f, 0.45f * c0[0]), String.format(Locale.US, "%s: đường kẻ dọc thẳng lại (lệch TB %.2f -> %.2f)", name, c0[0], c1[0]));
                }
            }
            // ---- độ sạch / giữ nội dung (ghép trường nắn + bản đồ thật, lấy điểm gần nhất trên trang phẳng)
            // vùng nếp gấp / vân tay: chỉ xét phần cách xa nét chữ (> 12 điểm ảnh ở cạnh dài 1400) — vệt nếp gấp cắt qua
            // chữ còn lại đoạn ngắn sát nét là chấp nhận được
            int[] farInk = inkNear(p, Math.max(4, Math.round(12f * Math.max(w, h) / 1400f)));
            int[] fl = new int[w * h];
            for (int i = 0; i < w * h; i++) fl[i] = lum(p.flat[i]);
            int paper = 0, dirt = 0, core = 0, kept = 0, regP = 0, regD = 0, red0 = 0, red1 = 0, blue0 = 0, blue1 = 0;
            for (int v = 0; v < h; v++) {
                for (int u = 0; u < w; u++) {
                    float[] s = source(f, u, v);
                    float[] g = flatPos(p, s[0], s[1]);
                    if (g == null) continue;
                    int gx = Math.round(g[0]), gy = Math.round(g[1]);
                    if (gx < 3 || gy < 3 || gx >= w - 3 || gy >= h - 3) continue;
                    int o = out[v * w + u];
                    int ol = lum(o);
                    int mn = 255, mxv = 0;
                    for (int dy = -3; dy <= 3; dy++) {
                        for (int dx = -3; dx <= 3; dx++) {
                            int q = fl[(gy + dy) * w + gx + dx];
                            if (q < mn) mn = q;
                            if (dx >= -1 && dx <= 1 && dy >= -1 && dy <= 1 && q > mxv) mxv = q;
                        }
                    }
                    if (mn >= 245) {
                        paper++;
                        boolean d = ol < 215;
                        if (d) dirt++;
                        if (farInk[gy * w + gx] == 0 && inRegions(p, gx, gy)) {
                            regP++;
                            if (d) regD++;
                        }
                    }
                    int c = p.flat[gy * w + gx];
                    int cr = (c >> 16) & 255, cg = (c >> 8) & 255, cb = c & 255;
                    boolean colored = Math.max(cr, Math.max(cg, cb)) - Math.min(cr, Math.min(cg, cb)) > 60;
                    if (!colored && fl[gy * w + gx] <= 150 && mxv <= 190) {
                        core++;
                        if (ol <= 170) kept++;
                    }
                    int orr = (o >> 16) & 255, og = (o >> 8) & 255, ob = o & 255;
                    if (cr > cg + 80 && cr > cb + 80) {
                        red0++;
                        if (orr > og + 50 && orr > ob + 50) red1++;
                    }
                    if (cb > cr + 70 && cb > cg + 50) {
                        blue0++;
                        if (ob > orr + 30 && ob > og + 20) blue1++;
                    }
                }
            }
            float dirtPct = paper > 0 ? 100f * dirt / paper : 0f;
            float regPct = regP > 0 ? 100f * regD / regP : 0f;
            float keptPct = core > 0 ? 100f * kept / core : 100f;
            System.out.printf(Locale.US, "            nền bẩn %.2f%% (vùng nếp/vân tay %.2f%% / %d điểm), giữ lõi nét %.1f%%%s%n", dirtPct, regPct, regP, keptPct,
                    red0 > 0 || blue0 > 0 ? String.format(Locale.US, ", đỏ %d->%d, xanh %d->%d", red0, red1, blue0, blue1) : "");
            check(dirtPct <= (blank ? 0.2f : 0.6f), String.format(Locale.US, "%s: nền giấy sạch (%.2f%% điểm bẩn)", name, dirtPct));
            if (regP > 0) check(regPct <= 1.5f, String.format(Locale.US, "%s: hết vệt nếp gấp / vân tay (%.2f%%)", name, regPct));
            if (!blank) check(keptPct >= 93f, String.format(Locale.US, "%s: giữ nét chữ / đường kẻ (%.1f%%)", name, keptPct));
            if (red0 > 50) check(red1 * 100 >= red0 * 85, name + ": giữ con dấu đỏ (" + red0 + " -> " + red1 + ")");
            if (blue0 > 50) check(blue1 * 100 >= blue0 * 80, name + ": giữ chữ ký bút xanh (" + blue0 + " -> " + blue1 + ")");
            if (p.finger != null) {
                int skin = 0;
                for (int i = 0; i < w * h; i++) {
                    int c = out[i];
                    int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
                    if (r - b > 36 && r > g + 14 && g >= b - 6 && r > 70) skin++;
                }
                check(skin <= 20, name + ": không còn ngón tay (" + skin + " điểm màu da)");
            }
        }
        System.out.printf(Locale.US, "Tổng thời gian %d ms. %d/%d kiểm tra đạt%n", total, checks - fails, checks);
        if (fails > 0) System.exit(1);
    }

    /** Mặt nạ các điểm trang phẳng cách nét mực (độ sáng <= 160) không quá r điểm ảnh. */
    static int[] inkNear(Page p, int r) {
        int w = p.w, h = p.h;
        int[] m = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (lum(p.flat[y * w + x]) > 160) continue;
                for (int dy = -r; dy <= r; dy++) {
                    int yy = y + dy;
                    if (yy < 0 || yy >= h) continue;
                    for (int dx = -r; dx <= r; dx++) {
                        int xx = x + dx;
                        if (xx >= 0 && xx < w) m[yy * w + xx] = 1;
                    }
                }
            }
        }
        return m;
    }

    /** Điểm trang phẳng (x, y) nằm trong vùng vệt nếp gấp (cách đường nếp <= 8 điểm ảnh) hoặc vết vân tay. */
    static boolean inRegions(Page p, int x, int y) {
        for (int[] c : p.crease) {
            double nx = c[3] - c[1], ny = -(c[2] - c[0]);
            double ln = Math.hypot(nx, ny);
            if (ln < 1) continue;
            double d = Math.abs((x - c[0]) * nx + (y - c[1]) * ny) / ln;
            double t = ((x - c[0]) * (c[2] - c[0]) + (y - c[1]) * (c[3] - c[1])) / (ln * ln);
            if (d <= 8 && t >= 0 && t <= 1) return true;
        }
        for (int[] q : p.print) {
            double dx = x - q[0], dy = y - q[1];
            if (dx * dx + dy * dy <= (double) q[2] * q[2]) return true;
        }
        return false;
    }
}
