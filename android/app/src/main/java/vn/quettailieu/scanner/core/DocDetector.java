package vn.quettailieu.scanner.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Tự động tìm vùng trang giấy (4 góc) trong ảnh chụp.
 *
 * Quy trình: thu nhỏ ảnh -> làm mờ -> gradient màu (Sobel) -> triệt tiêu cực đại (NMS)
 * -> biến đổi Hough có định hướng (bỏ phiếu theo độ lớn gradient) -> ghép 4 đường thẳng thành tứ giác
 * -> chấm điểm theo độ phủ cạnh (thích nghi với kết cấu nền), độ tương phản, diện tích và
 * việc các cạnh có "dừng" đúng tại góc hay không -> tinh chỉnh dưới-pixel ở độ phân giải cao.
 *
 * Java thuần, không phụ thuộc Android.
 */
public final class DocDetector {
    private DocDetector() {}

    public static final class Result {
        /** TL,TR,BR,BL theo toạ độ ảnh đầu vào; null nếu không tìm thấy. */
        public float[] quad;
        public float score;
        public boolean refined;
        public String debug = "";
    }

    public static boolean DEBUG = false;

    static final int WORK_MAX = 420;
    static final int MAX_LINES_PER_DIR = 20;
    static final float MIN_SCORE = 0.10f;
    static final double BORDER_Q = 0.22;

    static final class Line {
        double nx, ny, rho;  // nx*x + ny*y = rho, ny >= 0
        double dx, dy;       // hướng = (-ny, nx)
        double ta;           // tham số t của mẫu đầu tiên
        int len;
        int[] sup;           // tổng tiền tố: mẫu nổi bật như một cạnh
        float[] con;         // tổng tiền tố: tương phản có dấu (phía +n trừ phía -n)
        float[] acon;        // tổng tiền tố: |tương phản|
        boolean border;
        int votes;
        double priority;
        double thetaDeg;
        double key;          // toạ độ cắt tại tâm ảnh (y cho đường ngang, x cho đường dọc)

        void setNormal(double nx_, double ny_, double rho_) {
            double l = Math.hypot(nx_, ny_);
            nx_ /= l; ny_ /= l; rho_ /= l;
            if (ny_ < -1e-12 || (Math.abs(ny_) <= 1e-12 && nx_ < 0)) { nx_ = -nx_; ny_ = -ny_; rho_ = -rho_; }
            nx = nx_; ny = ny_; rho = rho_;
            dx = -ny; dy = nx;
            double th = Math.toDegrees(Math.atan2(ny, nx));
            if (th < 0) th += 180;
            if (th >= 180) th -= 180;
            thetaDeg = th;
        }

        boolean isHorizontal() { return thetaDeg >= 45 && thetaDeg < 135; }

        double paramOf(double x, double y) { return x * dx + y * dy; }

        /** Tỷ lệ mẫu nổi bật trong khoảng tham số [t0,t1]; phần nằm ngoài ảnh tính là không có. */
        double support(double t0, double t1) {
            double s0 = t0 - ta, s1 = t1 - ta;
            double expected = Math.max(1, s1 - s0);
            int i0 = (int) Math.ceil(s0), i1 = (int) Math.floor(s1);
            if (i0 < 0) i0 = 0;
            if (i1 > len - 1) i1 = len - 1;
            if (i1 < i0) return 0;
            return Math.min(1.0, (sup[i1 + 1] - sup[i0]) / expected);
        }

        /** Như support() nhưng chỉ tính phần nằm trong ảnh; trả về -1 nếu quá ít mẫu. */
        double supportInside(double t0, double t1) {
            double s0 = t0 - ta, s1 = t1 - ta;
            int i0 = (int) Math.ceil(s0), i1 = (int) Math.floor(s1);
            if (i0 < 0) i0 = 0;
            if (i1 > len - 1) i1 = len - 1;
            if (i1 - i0 + 1 < 3) return -1;
            return (sup[i1 + 1] - sup[i0]) / (double) (i1 - i0 + 1);
        }

        double[] contrast(double t0, double t1) {
            int i0 = (int) Math.ceil(t0 - ta), i1 = (int) Math.floor(t1 - ta);
            if (i0 < 0) i0 = 0;
            if (i1 > len - 1) i1 = len - 1;
            if (i1 < i0) return new double[]{0, 0};
            int c = i1 - i0 + 1;
            return new double[]{(con[i1 + 1] - con[i0]) / c, (acon[i1 + 1] - acon[i0]) / c};
        }
    }

    public static Result detect(int[] argb, int w, int h) {
        if (argb == null || w < 16 || h < 16) return null;
        float scale = Math.min(1f, WORK_MAX / (float) Math.max(w, h));
        int sw = Math.max(16, Math.round(w * scale));
        int sh = Math.max(16, Math.round(h * scale));
        float kx = sw / (float) w, ky = sh / (float) h;
        int n = sw * sh;

        float[] cr = new float[n], cg = new float[n], cb = new float[n];
        downscale(argb, w, h, cr, cg, cb, sw, sh);
        float[] tmp = new float[n];
        blur5(cr, tmp, sw, sh);
        blur5(cg, tmp, sw, sh);
        blur5(cb, tmp, sw, sh);
        float[] gray = new float[n];
        for (int i = 0; i < n; i++) gray[i] = 0.299f * cr[i] + 0.587f * cg[i] + 0.114f * cb[i];

        float[] gx = new float[n], gy = new float[n], mag = new float[n];
        colorSobel(cr, cg, cb, sw, sh, gx, gy, mag);
        float[] em = nms(gx, gy, mag, sw, sh);

        List<Line> lines = hough(em, gx, gy, sw, sh, 2.0f);
        List<Line> hs = new ArrayList<>(), vs = new ArrayList<>();
        double cx = (sw - 1) / 2.0, cy = (sh - 1) / 2.0;
        for (Line L : lines) {
            refineLine(L, em, gx, gy, mag, sw, sh, 2.5f);
            if (!buildProfile(L, gx, gy, mag, gray, sw, sh)) continue;
            // ưu tiên đường có tương phản (mép giấy) hơn dòng chữ/đường kẻ trong trang
            double acon = L.acon[L.len] / Math.max(1, L.len);
            L.priority = L.votes * (0.25 + 0.75 * Math.min(1.0, acon / 15.0));
            if (L.isHorizontal()) {
                L.key = (L.rho - cx * L.nx) / L.ny;
                hs.add(L);
            } else {
                L.key = (L.rho - cy * L.ny) / L.nx;
                vs.add(L);
            }
        }
        keepTop(hs, MAX_LINES_PER_DIR);
        keepTop(vs, MAX_LINES_PER_DIR);
        hs.add(borderLine(0, 1, 0, cx, cy));
        hs.add(borderLine(0, 1, sh - 1, cx, cy));
        vs.add(borderLine(1, 0, 0, cx, cy));
        vs.add(borderLine(1, 0, sw - 1, cx, cy));
        sortByKey(hs);
        sortByKey(vs);
        if (DEBUG) {
            for (Line L : hs) dumpLine("H", L);
            for (Line L : vs) dumpLine("V", L);
        }

        final int K = 1;
        float[][] topQ = new float[K][];
        double[] topS = new double[K];
        int[] topB = new int[K];
        java.util.Arrays.fill(topS, -1);
        double imgArea = (double) sw * sh;
        float tolX = 0.03f * sw, tolY = 0.03f * sh;
        double[] dbgOut = DEBUG ? new double[24] : null;
        List<String> allDbg = new ArrayList<>();
        for (int i = 0; i < hs.size(); i++) {
            for (int j = i + 1; j < hs.size(); j++) {
                Line top = hs.get(i), bot = hs.get(j);
                if (bot.key - top.key < 0.18 * sh) continue;
                if (Math.abs(top.thetaDeg - bot.thetaDeg) > 40) continue;
                for (int a = 0; a < vs.size(); a++) {
                    for (int b = a + 1; b < vs.size(); b++) {
                        Line left = vs.get(a), right = vs.get(b);
                        if (right.key - left.key < 0.18 * sw) continue;
                        double dth = Math.abs(left.thetaDeg - right.thetaDeg);
                        dth = Math.min(dth, 180 - dth);
                        if (dth > 40) continue;
                        int borders = (top.border ? 1 : 0) + (bot.border ? 1 : 0) + (left.border ? 1 : 0) + (right.border ? 1 : 0);
                        if (borders > 3) continue;
                        float[] q = new float[8];
                        if (!intersect(top, left, q, 0) || !intersect(top, right, q, 2)
                                || !intersect(bot, right, q, 4) || !intersect(bot, left, q, 6)) continue;
                        boolean inside = true;
                        for (int k = 0; k < 4; k++) {
                            if (q[2 * k] < -tolX || q[2 * k] > sw - 1 + tolX || q[2 * k + 1] < -tolY || q[2 * k + 1] > sh - 1 + tolY) {
                                inside = false;
                                break;
                            }
                        }
                        if (!inside || !Geom.isConvex(q)) continue;
                        double area = Geom.area(q);
                        if (area < 0.10 * imgArea) continue;
                        double[] ar = Geom.angleRange(q);
                        if (ar[0] < 40 || ar[1] > 140) continue;
                        double sc = score(q, new Line[]{top, right, bot, left}, area / imgArea, dbgOut) * Math.pow(0.97, borders);
                        if (DEBUG && sc > 0.2) {
                            StringBuilder sb = new StringBuilder();
                            for (int s2 = 0; s2 < 4; s2++)
                                sb.append(String.format(java.util.Locale.US, " [sup=%.2f con=%.0f acon=%.0f q=%.2f ext=%.2f]", dbgOut[s2 * 5], dbgOut[s2 * 5 + 1], dbgOut[s2 * 5 + 2], dbgOut[s2 * 5 + 3], dbgOut[s2 * 5 + 4]));
                            allDbg.add(String.format(java.util.Locale.US, "%.3f keys(t=%.0f b=%.0f l=%.0f r=%.0f) area=%.2f%s", sc, top.key, bot.key, left.key, right.key, area / imgArea, sb));
                        }
                        if (sc > topS[K - 1]) {
                            int pos = K - 1;
                            while (pos > 0 && topS[pos - 1] < sc) {
                                topS[pos] = topS[pos - 1];
                                topQ[pos] = topQ[pos - 1];
                                topB[pos] = topB[pos - 1];
                                pos--;
                            }
                            topS[pos] = sc;
                            topQ[pos] = q;
                            topB[pos] = borders;
                        }
                    }
                }
            }
        }
        if (DEBUG) {
            java.util.Collections.sort(allDbg, java.util.Collections.reverseOrder());
            for (int i = 0; i < Math.min(8, allDbg.size()); i++) System.out.println("   TOP " + allDbg.get(i));
        }
        float[] best = topQ[0];
        double bestScore = topS[0];
        String bestDbg = String.format(java.util.Locale.US, "score=%.3f borders=%d", topS[0], topB[0]);
        Result r = new Result();
        if (best == null || bestScore < MIN_SCORE) {
            r.quad = null;
            r.score = (float) bestScore;
            r.debug = "none " + bestDbg;
            return r;
        }
        // Đưa về toạ độ ảnh đầu vào
        float[] q0 = new float[8];
        for (int k = 0; k < 4; k++) {
            q0[2 * k] = (best[2 * k] + 0.5f) / kx - 0.5f;
            q0[2 * k + 1] = (best[2 * k + 1] + 0.5f) / ky - 0.5f;
        }
        float[] ref = refine(argb, w, h, q0, 2.5f / Math.min(kx, ky));
        if (ref != null) { r.quad = ref; r.refined = true; }
        else r.quad = q0;
        Geom.clamp(r.quad, w - 1, h - 1);
        r.score = (float) bestScore;
        r.debug = bestDbg + (r.refined ? " refined" : "");
        return r;
    }

    // ------------------------------------------------------------------ chấm điểm

    private static double score(float[] q, Line[] sides, double areaFrac, double[] dbg) {
        // sides: top(0->1), right(1->2), bottom(2->3), left(3->0)
        double qx = (q[0] + q[2] + q[4] + q[6]) / 4, qy = (q[1] + q[3] + q[5] + q[7]) / 4;
        double sumQ = 0, sumL = 0, minQ = 1e9;
        double[] lens = new double[4];
        for (int s = 0; s < 4; s++) {
            int a = s, b = (s + 1) % 4;
            double ax = q[2 * a], ay = q[2 * a + 1], bx = q[2 * b], by = q[2 * b + 1];
            double len = Math.hypot(bx - ax, by - ay);
            lens[s] = len;
            Line L = sides[s];
            double qs, sup = 0, con = 0, acon = 0;
            if (L.border) {
                qs = BORDER_Q;
            } else {
                double t1 = L.paramOf(ax, ay), t2 = L.paramOf(bx, by);
                double tmin = Math.min(t1, t2), tmax = Math.max(t1, t2);
                double trim = 0.06 * (tmax - tmin);
                sup = L.support(tmin + trim, tmax - trim);
                double[] c = L.contrast(tmin + trim, tmax - trim);
                con = c[0];
                acon = c[1];
                // đổi dấu để con > 0 nghĩa là phía trong tứ giác sáng hơn phía ngoài
                if (L.nx * qx + L.ny * qy - L.rho < 0) con = -con;
                // đường kẻ/dòng chữ bên trong trang có hai phía giống nhau -> tương phản thấp -> điểm thấp
                double wcon = 0.08 + 0.92 * Math.pow(Math.min(1.0, acon / 20.0), 1.2);
                if (con < -10) wcon *= 0.85;
                qs = sup * wcon;
            }
            if (dbg != null) { dbg[s * 5] = sup; dbg[s * 5 + 1] = con; dbg[s * 5 + 2] = acon; dbg[s * 5 + 3] = qs; }
            sumQ += qs * len;
            sumL += len;
            minQ = Math.min(minQ, qs);
        }
        // Góc thật: cạnh phải "dừng" tại góc (phần kéo dài ra ngoài không còn là cạnh)
        double term = 0;
        for (int c = 0; c < 4; c++) {
            int sPrev = (c + 3) % 4, sNext = c;
            double e1 = extension(sides[sPrev], q, sPrev, true, lens[sPrev]);
            double e2 = extension(sides[sNext], q, sNext, false, lens[sNext]);
            double e = Math.max(e1, e2);
            if (dbg != null) dbg[c * 5 + 4] = e;
            term += 1.0 - 0.35 * e;
        }
        term /= 4;
        double mean = sumQ / Math.max(1e-9, sumL);
        double minF = 0.4 + 0.6 * Math.min(1.0, minQ / Math.max(0.2, 0.55 * mean));
        return mean * minF * (0.5 + 0.5 * Math.sqrt(areaFrac)) * term;
    }

    /**
     * Độ phủ cạnh trên đoạn kéo dài của cạnh s ra ngoài góc.
     * atEnd=true: kéo dài qua điểm cuối của cạnh; false: kéo dài lùi qua điểm đầu.
     */
    private static double extension(Line L, float[] q, int s, boolean atEnd, double len) {
        if (L.border) return 0;
        int a = s, b = (s + 1) % 4;
        double ta = L.paramOf(q[2 * a], q[2 * a + 1]), tb = L.paramOf(q[2 * b], q[2 * b + 1]);
        double e = Math.max(6, 0.12 * len);
        double from, to;
        if (atEnd) {
            if (tb >= ta) { from = tb + 2; to = tb + 2 + e; }
            else { from = tb - 2 - e; to = tb - 2; }
        } else {
            if (tb >= ta) { from = ta - 2 - e; to = ta - 2; }
            else { from = ta + 2; to = ta + 2 + e; }
        }
        double v = L.supportInside(from, to);
        return v < 0 ? 0 : v;
    }

    private static void dumpLine(String tag, Line L) {
        if (L.border) { System.out.printf("  %s border key=%.1f%n", tag, L.key); return; }
        double sup = L.sup[L.len] / (double) L.len, acon = L.acon[L.len] / L.len;
        System.out.printf("  %s key=%6.1f th=%5.1f votes=%5d len=%4d sup=%.2f acon=%5.1f%n", tag, L.key, L.thetaDeg, L.votes, L.len, sup, acon);
    }

    private static boolean intersect(Line a, Line b, float[] out, int off) {
        double det = a.nx * b.ny - a.ny * b.nx;
        if (Math.abs(det) < 1e-9) return false;
        double x = (a.rho * b.ny - b.rho * a.ny) / det;
        double y = (a.nx * b.rho - b.nx * a.rho) / det;
        out[off] = (float) x;
        out[off + 1] = (float) y;
        return true;
    }

    private static Line borderLine(double nx, double ny, double rho, double cx, double cy) {
        Line L = new Line();
        L.setNormal(nx, ny, rho);
        L.border = true;
        L.key = L.isHorizontal() ? (L.rho - cx * L.nx) / L.ny : (L.rho - cy * L.ny) / L.nx;
        return L;
    }

    private static void keepTop(List<Line> l, int max) {
        if (l.size() <= max) return;
        Line[] arr = l.toArray(new Line[0]);
        Arrays.sort(arr, new java.util.Comparator<Line>() {
            @Override
            public int compare(Line o1, Line o2) { return Double.compare(o2.priority, o1.priority); }
        });
        l.clear();
        for (int i = 0; i < max; i++) l.add(arr[i]);
    }

    private static void sortByKey(List<Line> l) {
        Line[] arr = l.toArray(new Line[0]);
        Arrays.sort(arr, new java.util.Comparator<Line>() {
            @Override
            public int compare(Line o1, Line o2) { return Double.compare(o1.key, o2.key); }
        });
        l.clear();
        l.addAll(Arrays.asList(arr));
    }

    // ------------------------------------------------------------------ tiền xử lý

    static void downscale(int[] argb, int w, int h, float[] r, float[] g, float[] b, int sw, int sh) {
        for (int oy = 0; oy < sh; oy++) {
            int y0 = (int) ((long) oy * h / sh), y1 = (int) ((long) (oy + 1) * h / sh);
            if (y1 <= y0) y1 = Math.min(h, y0 + 1);
            for (int ox = 0; ox < sw; ox++) {
                int x0 = (int) ((long) ox * w / sw), x1 = (int) ((long) (ox + 1) * w / sw);
                if (x1 <= x0) x1 = Math.min(w, x0 + 1);
                int sr = 0, sg = 0, sb = 0, cnt = 0;
                for (int y = y0; y < y1; y++) {
                    int row = y * w;
                    for (int x = x0; x < x1; x++) {
                        int c = argb[row + x];
                        sr += (c >> 16) & 255;
                        sg += (c >> 8) & 255;
                        sb += c & 255;
                        cnt++;
                    }
                }
                int o = oy * sw + ox;
                r[o] = sr / (float) cnt;
                g[o] = sg / (float) cnt;
                b[o] = sb / (float) cnt;
            }
        }
    }

    /** Làm mờ Gauss 5 điểm [1 4 6 4 1]/16 (tách hàng/cột). */
    static void blur5(float[] a, float[] tmp, int w, int h) {
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                int xm2 = Math.max(0, x - 2), xm1 = Math.max(0, x - 1), xp1 = Math.min(w - 1, x + 1), xp2 = Math.min(w - 1, x + 2);
                tmp[row + x] = (a[row + xm2] + 4 * a[row + xm1] + 6 * a[row + x] + 4 * a[row + xp1] + a[row + xp2]) * (1f / 16f);
            }
        }
        for (int y = 0; y < h; y++) {
            int ym2 = Math.max(0, y - 2) * w, ym1 = Math.max(0, y - 1) * w, y0 = y * w, yp1 = Math.min(h - 1, y + 1) * w, yp2 = Math.min(h - 1, y + 2) * w;
            for (int x = 0; x < w; x++) {
                a[y0 + x] = (tmp[ym2 + x] + 4 * tmp[ym1 + x] + 6 * tmp[y0 + x] + 4 * tmp[yp1 + x] + tmp[yp2 + x]) * (1f / 16f);
            }
        }
    }

    static void colorSobel(float[] r, float[] g, float[] b, int w, int h, float[] gx, float[] gy, float[] mag) {
        float[][] ch = {r, g, b};
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                int i = y * w + x;
                float bestM = -1, bx = 0, by = 0;
                for (int c = 0; c < 3; c++) {
                    float[] p = ch[c];
                    float tl = p[i - w - 1], tc = p[i - w], tr = p[i - w + 1];
                    float ml = p[i - 1], mr = p[i + 1];
                    float bl = p[i + w - 1], bc = p[i + w], br = p[i + w + 1];
                    float sx = ((tr + 2 * mr + br) - (tl + 2 * ml + bl)) * 0.125f;
                    float sy = ((bl + 2 * bc + br) - (tl + 2 * tc + tr)) * 0.125f;
                    float m = sx * sx + sy * sy;
                    if (m > bestM) { bestM = m; bx = sx; by = sy; }
                }
                gx[i] = bx;
                gy[i] = by;
                mag[i] = (float) Math.sqrt(bestM);
            }
        }
    }

    static float[] nms(float[] gx, float[] gy, float[] mag, int w, int h) {
        float[] out = new float[w * h];
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                int i = y * w + x;
                float m = mag[i];
                if (m < 1f) continue;
                float ax = Math.abs(gx[i]), ay = Math.abs(gy[i]);
                float m1, m2;
                if (ay <= ax * 0.41421356f) { m1 = mag[i + 1]; m2 = mag[i - 1]; }
                else if (ay >= ax * 2.41421356f) { m1 = mag[i + w]; m2 = mag[i - w]; }
                else if (gx[i] * gy[i] > 0) { m1 = mag[i + w + 1]; m2 = mag[i - w - 1]; }
                else { m1 = mag[i - w + 1]; m2 = mag[i + w - 1]; }
                if (m > m1 && m >= m2) out[i] = m;
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ Hough

    static List<Line> hough(float[] em, float[] gx, float[] gy, int w, int h, float minEdge) {
        final int T = 180;
        int D = (int) Math.ceil(Math.hypot(w, h));
        int RN = 2 * D + 1;
        int[] acc = new int[T * RN];
        double[] cs = new double[T], sn = new double[T];
        for (int t = 0; t < T; t++) {
            cs[t] = Math.cos(Math.PI * t / T);
            sn[t] = Math.sin(Math.PI * t / T);
        }
        long total = 0;
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                int i = y * w + x;
                float e = em[i];
                if (e < minEdge) continue;
                int wt = (int) Math.min(16f, e);
                double phi = Math.atan2(gy[i], gx[i]);
                if (phi < 0) phi += Math.PI;
                int tb = (int) Math.round(phi * T / Math.PI);
                for (int d = -2; d <= 2; d++) {
                    int t = tb + d;
                    if (t < 0) t += T;
                    else if (t >= T) t -= T;
                    int r = (int) Math.round(x * cs[t] + y * sn[t]) + D;
                    if (r >= 0 && r < RN) acc[t * RN + r] += wt;
                }
                total += wt;
            }
        }
        int minVotes = Math.max(40, (int) (0.10 * Math.min(w, h) * 4));
        List<int[]> peaks = new ArrayList<>();
        for (int t = 0; t < T; t++) {
            for (int r = 0; r < RN; r++) {
                int v = acc[t * RN + r];
                if (v < minVotes) continue;
                boolean isMax = true;
                for (int dt = -2; dt <= 2 && isMax; dt++) {
                    int tt = t + dt;
                    if (tt < 0 || tt >= T) continue;
                    for (int dr = -3; dr <= 3; dr++) {
                        int rr = r + dr;
                        if (rr < 0 || rr >= RN || (dt == 0 && dr == 0)) continue;
                        int u = acc[tt * RN + rr];
                        if (u > v || (u == v && (dt < 0 || (dt == 0 && dr < 0)))) { isMax = false; break; }
                    }
                }
                if (isMax) peaks.add(new int[]{v, t, r - D});
            }
        }
        int[][] arr = peaks.toArray(new int[0][]);
        Arrays.sort(arr, new java.util.Comparator<int[]>() {
            @Override
            public int compare(int[] a, int[] b) { return Integer.compare(b[0], a[0]); }
        });
        List<Line> out = new ArrayList<>();
        List<int[]> accepted = new ArrayList<>();
        for (int[] p : arr) {
            boolean dup = false;
            for (int[] a : accepted) {
                int dt = Math.abs(p[1] - a[1]);
                int rp = p[2], ra = a[2];
                if (dt > T / 2) { dt = T - dt; ra = -ra; }
                if (dt <= 3 && Math.abs(rp - ra) <= 5) { dup = true; break; }
            }
            if (dup) continue;
            accepted.add(p);
            Line L = new Line();
            double th = Math.PI * p[1] / T;
            L.setNormal(Math.cos(th), Math.sin(th), p[2]);
            L.votes = p[0];
            out.add(L);
            if (accepted.size() >= 64) break;
        }
        return out;
    }

    /** Khớp lại đường thẳng bằng bình phương tối thiểu toàn phần trên các điểm cạnh lân cận. */
    static void refineLine(Line L, float[] em, float[] gx, float[] gy, float[] mag, int w, int h, float lowS) {
        for (int iter = 0; iter < 2; iter++) {
            double tol = iter == 0 ? 2.5 : 1.5;
            double[] range = clip(L, w, h);
            if (range == null) return;
            double sw = 0, sx = 0, sy = 0, sxx = 0, syy = 0, sxy = 0;
            int cnt = 0;
            int k = (int) Math.ceil(tol);
            for (double t = range[0]; t <= range[1]; t += 1.0) {
                double px = L.rho * L.nx + t * L.dx, py = L.rho * L.ny + t * L.dy;
                for (int o = -k; o <= k; o++) {
                    int xx = (int) Math.round(px + o * L.nx), yy = (int) Math.round(py + o * L.ny);
                    if (xx < 1 || yy < 1 || xx >= w - 1 || yy >= h - 1) continue;
                    int i = yy * w + xx;
                    if (em[i] < lowS) continue;
                    double dist = Math.abs(L.nx * xx + L.ny * yy - L.rho);
                    if (dist > tol) continue;
                    if (Math.abs(gx[i] * L.nx + gy[i] * L.ny) < 0.8 * mag[i]) continue;
                    double wt = Math.min(em[i], 40f);
                    sw += wt; sx += wt * xx; sy += wt * yy;
                    sxx += wt * xx * xx; syy += wt * yy * yy; sxy += wt * xx * yy;
                    cnt++;
                }
            }
            if (cnt < 12 || sw <= 0) return;
            double mx = sx / sw, my = sy / sw;
            double cxx = sxx / sw - mx * mx, cyy = syy / sw - my * my, cxy = sxy / sw - mx * my;
            double tr = cxx + cyy, det = cxx * cyy - cxy * cxy;
            double disc = Math.sqrt(Math.max(0, tr * tr / 4 - det));
            double lmin = tr / 2 - disc;
            double nx, ny;
            if (Math.abs(cxy) > 1e-9) { nx = cxy; ny = lmin - cxx; }
            else if (cxx < cyy) { nx = 1; ny = 0; }
            else { nx = 0; ny = 1; }
            double l = Math.hypot(nx, ny);
            if (l < 1e-12) return;
            nx /= l; ny /= l;
            if (Math.abs(nx * L.nx + ny * L.ny) < Math.cos(Math.toRadians(4))) return;
            if (nx * L.nx + ny * L.ny < 0) { nx = -nx; ny = -ny; }
            L.setNormal(nx, ny, nx * mx + ny * my);
        }
    }

    static double[] clip(Line L, int w, int h) {
        double x0 = L.rho * L.nx, y0 = L.rho * L.ny;
        double lo = -1e9, hi = 1e9;
        double[] mm = {0, w - 1, 0, h - 1};
        double[] p0 = {x0, y0}, d = {L.dx, L.dy};
        for (int a = 0; a < 2; a++) {
            double mn = mm[2 * a], mx = mm[2 * a + 1];
            if (Math.abs(d[a]) < 1e-9) {
                if (p0[a] < mn || p0[a] > mx) return null;
            } else {
                double t1 = (mn - p0[a]) / d[a], t2 = (mx - p0[a]) / d[a];
                lo = Math.max(lo, Math.min(t1, t2));
                hi = Math.min(hi, Math.max(t1, t2));
            }
        }
        if (hi - lo < 4) return null;
        return new double[]{lo, hi};
    }

    /**
     * Hồ sơ dọc đường thẳng: mẫu được coi là "cạnh" khi gradient vuông góc với đường nổi bật
     * so với nền xung quanh (thích nghi với kết cấu), kèm độ tương phản hai phía.
     */
    static boolean buildProfile(Line L, float[] gx, float[] gy, float[] mag, float[] gray, int w, int h) {
        double[] range = clip(L, w, h);
        if (range == null) return false;
        L.ta = range[0];
        L.len = (int) Math.floor(range[1] - range[0]) + 1;
        L.sup = new int[L.len + 1];
        L.con = new float[L.len + 1];
        L.acon = new float[L.len + 1];
        double nx = L.nx, ny = L.ny;
        for (int i = 0; i < L.len; i++) {
            double t = L.ta + i;
            double px = L.rho * nx + t * L.dx, py = L.rho * ny + t * L.dy;
            float on = 0;
            boolean aligned = false;
            for (int o = -1; o <= 1; o++) {
                int xx = (int) Math.round(px + o * nx), yy = (int) Math.round(py + o * ny);
                if (xx < 1 || yy < 1 || xx >= w - 1 || yy >= h - 1) continue;
                int idx = yy * w + xx;
                float v = (float) Math.abs(gx[idx] * nx + gy[idx] * ny);
                if (v > on) { on = v; aligned = v >= 0.8f * mag[idx]; }
            }
            float bg = 0;
            int bc = 0;
            for (int o = -6; o <= 6; o += 2) {
                if (o > -4 && o < 4) continue;
                int xx = (int) Math.round(px + o * nx), yy = (int) Math.round(py + o * ny);
                if (xx < 1 || yy < 1 || xx >= w - 1 || yy >= h - 1) continue;
                int idx = yy * w + xx;
                bg += (float) Math.abs(gx[idx] * nx + gy[idx] * ny);
                bc++;
            }
            if (bc > 0) bg /= bc;
            int hit = (aligned && on >= 2.0f && on >= 1.7f * bg + 0.5f) ? 1 : 0;
            float a = sample(gray, w, h, px + 4 * nx, py + 4 * ny);
            float b = sample(gray, w, h, px - 4 * nx, py - 4 * ny);
            L.sup[i + 1] = L.sup[i] + hit;
            L.con[i + 1] = L.con[i] + (a - b);
            L.acon[i + 1] = L.acon[i] + Math.abs(a - b);
        }
        return true;
    }

    static float sample(float[] img, int w, int h, double x, double y) {
        if (x < 0) x = 0;
        if (y < 0) y = 0;
        if (x > w - 1) x = w - 1;
        if (y > h - 1) y = h - 1;
        int x0 = (int) x, y0 = (int) y;
        int x1 = Math.min(w - 1, x0 + 1), y1 = Math.min(h - 1, y0 + 1);
        float fx = (float) (x - x0), fy = (float) (y - y0);
        float a = img[y0 * w + x0] * (1 - fx) + img[y0 * w + x1] * fx;
        float b = img[y1 * w + x0] * (1 - fx) + img[y1 * w + x1] * fx;
        return a * (1 - fy) + b * fy;
    }

    // ------------------------------------------------------------------ tinh chỉnh dưới-pixel

    /**
     * Tinh chỉnh từng cạnh của tứ giác ở độ phân giải ảnh đầu vào: tìm điểm có gradient lớn nhất
     * dọc theo pháp tuyến, khớp lại đường thẳng, rồi giao các cạnh để ra góc mới.
     */
    static float[] refine(int[] argb, int w, int h, float[] q, float radius) {
        int n = w * h;
        float[] gray = new float[n];
        for (int i = 0; i < n; i++) {
            int c = argb[i];
            gray[i] = 0.299f * ((c >> 16) & 255) + 0.587f * ((c >> 8) & 255) + 0.114f * (c & 255);
        }
        blur5(gray, new float[n], w, h);
        double[][] lines = new double[4][];
        boolean any = false;
        int R = Math.max(3, Math.round(radius));
        float[] prof = new float[2 * R + 5];
        for (int s = 0; s < 4; s++) {
            int a = s, b = (s + 1) % 4;
            double ax = q[2 * a], ay = q[2 * a + 1], bx = q[2 * b], by = q[2 * b + 1];
            double len = Math.hypot(bx - ax, by - ay);
            if (len < 10) continue;
            double ux = (bx - ax) / len, uy = (by - ay) / len;
            double nx = -uy, ny = ux;
            boolean atBorder = (ax < 2 && bx < 2) || (ay < 2 && by < 2)
                    || (ax > w - 3 && bx > w - 3) || (ay > h - 3 && by > h - 3);
            if (atBorder) continue;
            int steps = Math.max(8, (int) (len / 2));
            double[] px = new double[steps], py = new double[steps];
            double sumSigned = 0;
            for (int i = 0; i < steps; i++) {
                double t = 0.06 + 0.88 * (i + 0.5) / steps;
                px[i] = ax + (bx - ax) * t;
                py[i] = ay + (by - ay) * t;
                double bestAbs = 0, bd = 0;
                for (int k = -R; k <= R; k++) {
                    double d = (sample(gray, w, h, px[i] + (k + 1) * nx, py[i] + (k + 1) * ny)
                            - sample(gray, w, h, px[i] + (k - 1) * nx, py[i] + (k - 1) * ny)) * 0.5;
                    if (Math.abs(d) > bestAbs) { bestAbs = Math.abs(d); bd = d; }
                }
                sumSigned += bd;
            }
            double sign = sumSigned >= 0 ? 1 : -1;
            double[] ptsX = new double[steps], ptsY = new double[steps], ptsW = new double[steps];
            int cnt = 0;
            for (int i = 0; i < steps; i++) {
                double cx = px[i], cy = py[i];
                for (int k = -R - 1; k <= R + 1; k++) {
                    double d = (sample(gray, w, h, cx + (k + 1) * nx, cy + (k + 1) * ny)
                            - sample(gray, w, h, cx + (k - 1) * nx, cy + (k - 1) * ny)) * 0.5 * sign;
                    prof[k + R + 1] = (float) d;
                }
                double best = 0;
                int bk = 0;
                for (int k = -R; k <= R; k++) {
                    double d = prof[k + R + 1];
                    if (d > best) { best = d; bk = k; }
                }
                if (best < 2.5) continue;
                double dm = prof[bk + R], d0 = prof[bk + R + 1], dp = prof[bk + R + 2];
                double den = dm - 2 * d0 + dp;
                double off = bk;
                if (Math.abs(den) > 1e-6) {
                    double sub = 0.5 * (dm - dp) / den;
                    if (sub > -1 && sub < 1) off += sub;
                }
                ptsX[cnt] = cx + off * nx;
                ptsY[cnt] = cy + off * ny;
                ptsW[cnt] = best;
                cnt++;
            }
            if (cnt < Math.max(8, steps / 4)) continue;
            double[] line = fitTLS(ptsX, ptsY, ptsW, cnt, null);
            if (line == null) continue;
            boolean[] keep = new boolean[cnt];
            int kept = 0;
            for (int i = 0; i < cnt; i++) {
                double r = Math.abs(line[0] * ptsX[i] + line[1] * ptsY[i] - line[2]);
                keep[i] = r <= 1.5;
                if (keep[i]) kept++;
            }
            if (kept < Math.max(8, steps / 4)) continue;
            double[] line2 = fitTLS(ptsX, ptsY, ptsW, cnt, keep);
            if (line2 == null) continue;
            if (Math.abs(line2[0] * nx + line2[1] * ny) < Math.cos(Math.toRadians(3))) continue;
            lines[s] = line2;
            any = true;
        }
        if (!any) return null;
        float[] out = new float[8];
        double diag = Math.hypot(w, h);
        for (int c = 0; c < 4; c++) {
            int sPrev = (c + 3) % 4, sNext = c;
            double[] l1 = lines[sPrev], l2 = lines[sNext];
            double ox = q[2 * c], oy = q[2 * c + 1];
            double x = ox, y = oy;
            if (l1 == null) l1 = lineThrough(q, sPrev);
            if (l2 == null) l2 = lineThrough(q, sNext);
            double det = l1[0] * l2[1] - l1[1] * l2[0];
            if (Math.abs(det) > 1e-9) {
                double ix = (l1[2] * l2[1] - l2[2] * l1[1]) / det;
                double iy = (l1[0] * l2[2] - l2[0] * l1[2]) / det;
                if (Math.hypot(ix - ox, iy - oy) <= Math.max(6, 0.025 * diag)) { x = ix; y = iy; }
            }
            out[2 * c] = (float) x;
            out[2 * c + 1] = (float) y;
        }
        if (!Geom.isConvex(out)) return null;
        return out;
    }

    private static double[] lineThrough(float[] q, int s) {
        int a = s, b = (s + 1) % 4;
        double ax = q[2 * a], ay = q[2 * a + 1], bx = q[2 * b], by = q[2 * b + 1];
        double len = Math.max(1e-9, Math.hypot(bx - ax, by - ay));
        double nx = -(by - ay) / len, ny = (bx - ax) / len;
        return new double[]{nx, ny, nx * ax + ny * ay};
    }

    private static double[] fitTLS(double[] xs, double[] ys, double[] ws, int n, boolean[] keep) {
        double sw = 0, sx = 0, sy = 0;
        for (int i = 0; i < n; i++) {
            if (keep != null && !keep[i]) continue;
            sw += ws[i]; sx += ws[i] * xs[i]; sy += ws[i] * ys[i];
        }
        if (sw <= 0) return null;
        double mx = sx / sw, my = sy / sw;
        double cxx = 0, cyy = 0, cxy = 0;
        for (int i = 0; i < n; i++) {
            if (keep != null && !keep[i]) continue;
            double dx = xs[i] - mx, dy = ys[i] - my;
            cxx += ws[i] * dx * dx; cyy += ws[i] * dy * dy; cxy += ws[i] * dx * dy;
        }
        double tr = cxx + cyy, det = cxx * cyy - cxy * cxy;
        double disc = Math.sqrt(Math.max(0, tr * tr / 4 - det));
        double lmin = tr / 2 - disc;
        double nx, ny;
        if (Math.abs(cxy) > 1e-12) { nx = cxy; ny = lmin - cxx; }
        else if (cxx < cyy) { nx = 1; ny = 0; }
        else { nx = 0; ny = 1; }
        double l = Math.hypot(nx, ny);
        if (l < 1e-12) return null;
        nx /= l; ny /= l;
        return new double[]{nx, ny, nx * mx + ny * my};
    }
}
