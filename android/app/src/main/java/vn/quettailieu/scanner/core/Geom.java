package vn.quettailieu.scanner.core;

/**
 * Hình học cho tứ giác tài liệu. Tứ giác luôn lưu dạng float[8]:
 * x0,y0 (trên-trái), x1,y1 (trên-phải), x2,y2 (dưới-phải), x3,y3 (dưới-trái).
 * Không phụ thuộc Android để có thể kiểm thử trên JVM.
 */
public final class Geom {
    private Geom() {}

    /** Tỷ lệ rộng/cao khổ A4 (dọc). */
    public static final double A4_RATIO = 210.0 / 297.0;
    /** Tỷ lệ rộng/cao khổ Letter (dọc). */
    public static final double LETTER_RATIO = 8.5 / 11.0;

    public static float[] fullQuad(float w, float h) {
        return new float[]{0, 0, w, 0, w, h, 0, h};
    }

    public static float[] copy(float[] q) {
        float[] r = new float[8];
        System.arraycopy(q, 0, r, 0, 8);
        return r;
    }

    public static float[] scale(float[] q, float sx, float sy) {
        float[] r = new float[8];
        for (int i = 0; i < 4; i++) {
            r[2 * i] = q[2 * i] * sx;
            r[2 * i + 1] = q[2 * i + 1] * sy;
        }
        return r;
    }

    public static void clamp(float[] q, float w, float h) {
        for (int i = 0; i < 4; i++) {
            q[2 * i] = Math.max(0, Math.min(w, q[2 * i]));
            q[2 * i + 1] = Math.max(0, Math.min(h, q[2 * i + 1]));
        }
    }

    /** Sắp xếp 4 điểm bất kỳ thành thứ tự TL, TR, BR, BL (theo chiều kim đồng hồ trên màn hình). */
    public static float[] order(float[] p) {
        double cx = 0, cy = 0;
        for (int i = 0; i < 4; i++) { cx += p[2 * i]; cy += p[2 * i + 1]; }
        cx /= 4; cy /= 4;
        final double[] ang = new double[4];
        Integer[] idx = new Integer[]{0, 1, 2, 3};
        for (int i = 0; i < 4; i++) ang[i] = Math.atan2(p[2 * i + 1] - cy, p[2 * i] - cx);
        // sắp theo góc tăng dần (y hướng xuống => chiều kim đồng hồ trên màn hình)
        for (int i = 0; i < 4; i++)
            for (int j = i + 1; j < 4; j++)
                if (ang[idx[j]] < ang[idx[i]]) { Integer t = idx[i]; idx[i] = idx[j]; idx[j] = t; }
        // điểm bắt đầu: x+y nhỏ nhất
        int start = 0;
        double best = Double.MAX_VALUE;
        for (int k = 0; k < 4; k++) {
            int i = idx[k];
            double v = p[2 * i] + p[2 * i + 1];
            if (v < best) { best = v; start = k; }
        }
        float[] r = new float[8];
        for (int k = 0; k < 4; k++) {
            int i = idx[(start + k) % 4];
            r[2 * k] = p[2 * i];
            r[2 * k + 1] = p[2 * i + 1];
        }
        return r;
    }

    /** Diện tích có dấu (dương nếu theo chiều kim đồng hồ trên màn hình, y hướng xuống). */
    public static double signedArea(float[] q) {
        double a = 0;
        for (int i = 0; i < 4; i++) {
            int j = (i + 1) % 4;
            a += (double) q[2 * i] * q[2 * j + 1] - (double) q[2 * j] * q[2 * i + 1];
        }
        return a / 2;
    }

    public static double area(float[] q) {
        return Math.abs(signedArea(q));
    }

    /** Tứ giác lồi, không tự cắt và các góc trong nằm trong khoảng hợp lý. */
    public static boolean isConvex(float[] q) {
        if (q == null || q.length < 8) return false;
        for (int i = 0; i < 8; i++) if (Float.isNaN(q[i]) || Float.isInfinite(q[i])) return false;
        int sign = 0;
        for (int i = 0; i < 4; i++) {
            float ax = q[2 * i], ay = q[2 * i + 1];
            float bx = q[2 * ((i + 1) % 4)], by = q[2 * ((i + 1) % 4) + 1];
            float cx = q[2 * ((i + 2) % 4)], cy = q[2 * ((i + 2) % 4) + 1];
            double cross = (double) (bx - ax) * (cy - by) - (double) (by - ay) * (cx - bx);
            if (!(Math.abs(cross) >= 1e-6)) return false;
            int s = cross > 0 ? 1 : -1;
            if (sign == 0) sign = s;
            else if (s != sign) return false;
        }
        return true;
    }

    /** Góc trong nhỏ nhất/lớn nhất (độ). */
    public static double[] angleRange(float[] q) {
        double mn = 360, mx = 0;
        for (int i = 0; i < 4; i++) {
            int p = (i + 3) % 4, n = (i + 1) % 4;
            double ux = q[2 * p] - q[2 * i], uy = q[2 * p + 1] - q[2 * i + 1];
            double vx = q[2 * n] - q[2 * i], vy = q[2 * n + 1] - q[2 * i + 1];
            double lu = Math.hypot(ux, uy), lv = Math.hypot(vx, vy);
            if (lu < 1e-9 || lv < 1e-9) return new double[]{0, 180};
            double c = (ux * vx + uy * vy) / (lu * lv);
            c = Math.max(-1, Math.min(1, c));
            double a = Math.toDegrees(Math.acos(c));
            mn = Math.min(mn, a);
            mx = Math.max(mx, a);
        }
        return new double[]{mn, mx};
    }

    public static double dist(float[] q, int i, int j) {
        return Math.hypot(q[2 * i] - q[2 * j], q[2 * i + 1] - q[2 * j + 1]);
    }

    /**
     * Ước lượng tỷ lệ thực rộng/cao của trang giấy từ tứ giác phối cảnh
     * (Zhang &amp; He, "Whiteboard scanning and image enhancement").
     *
     * @param focalPx tiêu cự tính theo pixel của ảnh (<=0 nếu không biết)
     */
    public static double estimateAspect(float[] q, float imgW, float imgH, double focalPx) {
        double wTop = dist(q, 0, 1), wBot = dist(q, 3, 2);
        double hL = dist(q, 0, 3), hR = dist(q, 1, 2);
        double affine = (wTop + wBot) / Math.max(1e-6, hL + hR);
        double u0 = imgW / 2.0, v0 = imgH / 2.0;
        // m1=TL, m2=TR, m3=BL, m4=BR (hệ toạ độ ảnh)
        double[] m1 = {q[0], q[1], 1}, m2 = {q[2], q[3], 1}, m3 = {q[6], q[7], 1}, m4 = {q[4], q[5], 1};
        double k2 = dot(cross(m1, m4), m3) / dot(cross(m2, m4), m3);
        double k3 = dot(cross(m1, m4), m2) / dot(cross(m3, m4), m2);
        if (Double.isNaN(k2) || Double.isNaN(k3) || Double.isInfinite(k2) || Double.isInfinite(k3)) return affine;
        double[] n2 = {k2 * m2[0] - m1[0], k2 * m2[1] - m1[1], k2 * m2[2] - m1[2]};
        double[] n3 = {k3 * m3[0] - m1[0], k3 * m3[1] - m1[1], k3 * m3[2] - m1[2]};
        double scaleRef = Math.max(imgW, imgH);
        // Gần như không có phối cảnh -> dùng tỷ lệ affine
        if (Math.abs(n2[2]) < 1e-3 && Math.abs(n3[2]) < 1e-3) return affine;
        double f = focalPx;
        double fEst = -1;
        if (Math.abs(n2[2] * n3[2]) > 1e-12) {
            double num = (n2[0] * n3[0] - (n2[0] * n3[2] + n2[2] * n3[0]) * u0 + n2[2] * n3[2] * u0 * u0)
                    + (n2[1] * n3[1] - (n2[1] * n3[2] + n2[2] * n3[1]) * v0 + n2[2] * n3[2] * v0 * v0);
            double f2 = -num / (n2[2] * n3[2]);
            if (f2 > 0) fEst = Math.sqrt(f2);
        }
        if (f <= 0) {
            if (fEst > 0.45 * scaleRef && fEst < 3.0 * scaleRef) f = fEst;
            else f = 0.78 * scaleRef; // camera điện thoại điển hình (~26mm tương đương)
        }
        // A^-T A^-1 với A = [[f,0,u0],[0,f,v0],[0,0,1]]
        double r2 = quadForm(n2, f, u0, v0);
        double r3 = quadForm(n3, f, u0, v0);
        if (!(r2 > 0) || !(r3 > 0)) return affine;
        double ratio = Math.sqrt(r2 / r3);
        if (Double.isNaN(ratio) || ratio <= 0.05 || ratio > 20) return affine;
        // Tránh sai lệch quá mức so với ước lượng affine (ảnh không phải chữ nhật thật)
        double lo = affine / 1.6, hi = affine * 1.6;
        if (ratio < lo || ratio > hi) return affine;
        return ratio;
    }

    private static double quadForm(double[] n, double f, double u0, double v0) {
        // n^T * (A^-T A^-1) * n = |A^-1 n|^2, A^-1 = [[1/f,0,-u0/f],[0,1/f,-v0/f],[0,0,1]]
        double a = (n[0] - u0 * n[2]) / f;
        double b = (n[1] - v0 * n[2]) / f;
        double c = n[2];
        return a * a + b * b + c * c;
    }

    /** Nắn tỷ lệ về đúng khổ giấy tiêu chuẩn nếu đủ gần (A4/Letter, dọc hoặc ngang). */
    public static double snapPaper(double ratio) {
        double[] std = {A4_RATIO, 1.0 / A4_RATIO, LETTER_RATIO, 1.0 / LETTER_RATIO};
        double[] tol = {0.045, 0.045, 0.02, 0.02};
        for (int i = 0; i < std.length; i++) {
            if (Math.abs(ratio - std[i]) / std[i] < tol[i]) return std[i];
        }
        return ratio;
    }

    /**
     * Kích thước ảnh đầu ra (rộng, cao) sau khi nắn phẳng.
     * @param maxLong cạnh dài tối đa của ảnh đầu ra
     */
    public static int[] outputSize(float[] q, float imgW, float imgH, double focalPx, boolean snap, int maxLong) {
        double ratio = estimateAspect(q, imgW, imgH, focalPx);
        if (snap) ratio = snapPaper(ratio);
        double wq = Math.max(dist(q, 0, 1), dist(q, 3, 2));
        double hq = Math.max(dist(q, 0, 3), dist(q, 1, 2));
        // tứ giác suy biến / toạ độ không hữu hạn -> kích thước tối thiểu (không để tràn số)
        if (!(ratio > 0) || Double.isInfinite(ratio) || !(wq > 0) || !(hq > 0) || Double.isInfinite(wq) || Double.isInfinite(hq)) {
            return new int[]{16, 16};
        }
        double outW, outH;
        if (wq / hq > ratio) { outW = wq; outH = wq / ratio; }
        else { outH = hq; outW = hq * ratio; }
        double longSide = Math.max(outW, outH);
        if (longSide > maxLong) {
            double k = maxLong / longSide;
            outW *= k; outH *= k;
        }
        int ow = Math.max(16, (int) Math.round(outW));
        int oh = Math.max(16, (int) Math.round(outH));
        return new int[]{ow, oh};
    }

    static double[] cross(double[] a, double[] b) {
        return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    /**
     * Ma trận đồng nhất 3x3 (hàng-chính, 9 phần tử) ánh xạ 4 điểm src -> 4 điểm dst.
     */
    public static double[] homography(float[] src, float[] dst) {
        double[][] a = new double[8][9];
        for (int i = 0; i < 4; i++) {
            double x = src[2 * i], y = src[2 * i + 1];
            double u = dst[2 * i], v = dst[2 * i + 1];
            a[2 * i] = new double[]{x, y, 1, 0, 0, 0, -u * x, -u * y, u};
            a[2 * i + 1] = new double[]{0, 0, 0, x, y, 1, -v * x, -v * y, v};
        }
        // Khử Gauss có chọn trụ
        for (int c = 0; c < 8; c++) {
            int piv = c;
            for (int r = c + 1; r < 8; r++) if (Math.abs(a[r][c]) > Math.abs(a[piv][c])) piv = r;
            double[] t = a[c]; a[c] = a[piv]; a[piv] = t;
            double d = a[c][c];
            if (Math.abs(d) < 1e-12) return null;
            for (int k = c; k < 9; k++) a[c][k] /= d;
            for (int r = 0; r < 8; r++) {
                if (r == c) continue;
                double f = a[r][c];
                if (f == 0) continue;
                for (int k = c; k < 9; k++) a[r][k] -= f * a[c][k];
            }
        }
        return new double[]{a[0][8], a[1][8], a[2][8], a[3][8], a[4][8], a[5][8], a[6][8], a[7][8], 1};
    }

    /** Nắn phẳng bằng Java thuần (dùng khi kiểm thử trên máy tính). Lấy mẫu song tuyến tính. */
    public static int[] warp(int[] src, int sw, int sh, float[] quad, int ow, int oh) {
        float[] dstRect = {0, 0, ow, 0, ow, oh, 0, oh};
        double[] m = homography(dstRect, quad);
        int[] out = new int[ow * oh];
        if (m == null) return out;
        for (int y = 0; y < oh; y++) {
            double py = y + 0.5;
            for (int x = 0; x < ow; x++) {
                double px = x + 0.5;
                double den = m[6] * px + m[7] * py + m[8];
                double sx = (m[0] * px + m[1] * py + m[2]) / den - 0.5;
                double sy = (m[3] * px + m[4] * py + m[5]) / den - 0.5;
                out[y * ow + x] = sampleBilinear(src, sw, sh, sx, sy);
            }
        }
        return out;
    }

    private static int sampleBilinear(int[] p, int w, int h, double x, double y) {
        if (x < 0) x = 0;
        if (y < 0) y = 0;
        if (x > w - 1) x = w - 1;
        if (y > h - 1) y = h - 1;
        int x0 = (int) x, y0 = (int) y;
        int x1 = Math.min(w - 1, x0 + 1), y1 = Math.min(h - 1, y0 + 1);
        double fx = x - x0, fy = y - y0;
        int c00 = p[y0 * w + x0], c10 = p[y0 * w + x1], c01 = p[y1 * w + x0], c11 = p[y1 * w + x1];
        int r = 0;
        for (int sh2 = 16; sh2 >= 0; sh2 -= 8) {
            double v00 = (c00 >> sh2) & 255, v10 = (c10 >> sh2) & 255, v01 = (c01 >> sh2) & 255, v11 = (c11 >> sh2) & 255;
            double v = (v00 * (1 - fx) + v10 * fx) * (1 - fy) + (v01 * (1 - fx) + v11 * fx) * fy;
            int iv = (int) (v + 0.5);
            if (iv > 255) iv = 255;
            r |= iv << sh2;
        }
        return 0xFF000000 | r;
    }
}
