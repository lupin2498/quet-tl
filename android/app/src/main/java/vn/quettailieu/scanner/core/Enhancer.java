package vn.quettailieu.scanner.core;

/**
 * Bộ lọc làm rõ tài liệu (Java thuần): ước lượng nền giấy (ánh sáng không đều, ám màu),
 * chuẩn hoá về nền trắng rồi áp đường cong tông theo từng chế độ.
 */
public final class Enhancer {
    private Enhancer() {}

    public static final int ORIGINAL = 0;
    public static final int MAGIC = 1;     // Tăng cường (mặc định)
    public static final int LIGHTEN = 2;   // Làm sáng
    public static final int GRAY = 3;      // Thang xám
    public static final int BW = 4;        // Đen trắng

    public static final int[] MODES = {MAGIC, ORIGINAL, LIGHTEN, GRAY, BW};

    /** Áp bộ lọc tại chỗ lên mảng ARGB. */
    public static void apply(int[] px, int w, int h, int mode) {
        if (mode == ORIGINAL || px == null || w < 4 || h < 4) return;
        Bg bg = background(px, w, h);
        float wp, bp, sat, gamma;
        switch (mode) {
            case LIGHTEN: bp = 0.04f; wp = 0.95f; sat = 1.10f; gamma = 1.15f; break;
            case GRAY: bp = 0.18f; wp = 0.90f; sat = 0f; gamma = 1.5f; break;
            case BW: bp = 0.62f; wp = 0.74f; sat = 0f; gamma = 1.0f; break;
            case MAGIC:
            default: bp = 0.20f; wp = 0.88f; sat = 1.35f; gamma = 1.6f; break;
        }
        // Bảng tra: giá trị chuẩn hoá v (0..2) -> 0..255 (mức đen/trắng + gamma)
        final int LUT_SCALE = 1024, LUT_N = 2048;
        int[] lut = new int[LUT_N];
        float inv = 1f / Math.max(1e-3f, wp - bp);
        for (int i = 0; i < LUT_N; i++) {
            float v = i / (float) LUT_SCALE;
            float t = (v - bp) * inv;
            if (t <= 0) lut[i] = 0;
            else if (t >= 1) lut[i] = 255;
            else lut[i] = (int) (Math.pow(t, gamma) * 255.0 + 0.5);
        }
        float[] rowR = new float[w], rowG = new float[w], rowB = new float[w];
        boolean mono = mode == GRAY || mode == BW;
        for (int y = 0; y < h; y++) {
            bg.row(y, rowR, rowG, rowB);
            int off = y * w;
            for (int x = 0; x < w; x++) {
                int c = px[off + x];
                float r = ((c >> 16) & 255) / rowR[x];
                float g = ((c >> 8) & 255) / rowG[x];
                float b = (c & 255) / rowB[x];
                float lum = 0.299f * r + 0.587f * g + 0.114f * b;
                int ir, ig, ib;
                if (mono) {
                    ir = ig = ib = lut[idx(lum, LUT_SCALE, LUT_N)];
                } else {
                    r = lum + (r - lum) * sat;
                    g = lum + (g - lum) * sat;
                    b = lum + (b - lum) * sat;
                    ir = lut[idx(r, LUT_SCALE, LUT_N)];
                    ig = lut[idx(g, LUT_SCALE, LUT_N)];
                    ib = lut[idx(b, LUT_SCALE, LUT_N)];
                }
                px[off + x] = 0xFF000000 | (ir << 16) | (ig << 8) | ib;
            }
        }
    }

    private static int idx(float v, int scale, int n) {
        int i = (int) (v * scale);
        if (i < 0) return 0;
        if (i >= n) return n - 1;
        return i;
    }

    /** Lưới nền theo từng kênh màu, nội suy song tuyến tính. */
    static final class Bg {
        int gw, gh;
        float cell;       // kích thước ô theo pixel ảnh gốc
        float[] r, g, b;  // giá trị nền (0..255)
        float[] tr, tg, tb; // hàng đã nội suy theo chiều dọc

        void row(int y, float[] outR, float[] outG, float[] outB) {
            if (tr == null) { tr = new float[gw]; tg = new float[gw]; tb = new float[gw]; }
            float fy = (y + 0.5f) / cell - 0.5f;
            int y0 = (int) Math.floor(fy);
            float wy = fy - y0;
            if (y0 < 0) { y0 = 0; wy = 0; }
            if (y0 >= gh - 1) { y0 = gh - 1; wy = 0; }
            int y1 = Math.min(gh - 1, y0 + 1);
            for (int i = 0; i < gw; i++) {
                tr[i] = r[y0 * gw + i] * (1 - wy) + r[y1 * gw + i] * wy;
                tg[i] = g[y0 * gw + i] * (1 - wy) + g[y1 * gw + i] * wy;
                tb[i] = b[y0 * gw + i] * (1 - wy) + b[y1 * gw + i] * wy;
            }
            int w = outR.length;
            for (int x = 0; x < w; x++) {
                float fx = (x + 0.5f) / cell - 0.5f;
                int x0 = (int) Math.floor(fx);
                float wx = fx - x0;
                if (x0 < 0) { x0 = 0; wx = 0; }
                if (x0 >= gw - 1) { x0 = gw - 1; wx = 0; }
                int x1 = Math.min(gw - 1, x0 + 1);
                outR[x] = tr[x0] * (1 - wx) + tr[x1] * wx;
                outG[x] = tg[x0] * (1 - wx) + tg[x1] * wx;
                outB[x] = tb[x0] * (1 - wx) + tb[x1] * wx;
            }
        }
    }

    static Bg background(int[] px, int w, int h) {
        // ô lưới ~ 1/48 cạnh dài (tối thiểu 8px)
        int cell = Math.max(8, Math.round(Math.max(w, h) / 48f));
        int gw = (w + cell - 1) / cell, gh = (h + cell - 1) / cell;
        float[] r = new float[gw * gh], g = new float[gw * gh], b = new float[gw * gh];
        // Lấy mẫu: trung bình khối 3x3 rồi lấy giá trị sáng nhất trong ô (bỏ qua chữ, nhiễu)
        int step = Math.max(1, cell / 12);
        for (int gy = 0; gy < gh; gy++) {
            int ys = gy * cell, ye = Math.min(h, ys + cell);
            for (int gx = 0; gx < gw; gx++) {
                int xs = gx * cell, xe = Math.min(w, xs + cell);
                float bestL = -1, br = 0, bgc = 0, bb = 0;
                for (int y = ys + 1; y < ye - 1; y += step) {
                    for (int x = xs + 1; x < xe - 1; x += step) {
                        int sr = 0, sg = 0, sb = 0;
                        for (int dy = -1; dy <= 1; dy++) {
                            int o = (y + dy) * w + x;
                            for (int dx = -1; dx <= 1; dx++) {
                                int c = px[o + dx];
                                sr += (c >> 16) & 255; sg += (c >> 8) & 255; sb += c & 255;
                            }
                        }
                        float l = 0.299f * sr + 0.587f * sg + 0.114f * sb;
                        if (l > bestL) { bestL = l; br = sr / 9f; bgc = sg / 9f; bb = sb / 9f; }
                    }
                }
                if (bestL < 0) {
                    int c = px[Math.min(h - 1, ys) * w + Math.min(w - 1, xs)];
                    br = (c >> 16) & 255; bgc = (c >> 8) & 255; bb = c & 255;
                }
                int i = gy * gw + gx;
                r[i] = br; g[i] = bgc; b[i] = bb;
            }
        }
        // Ô thuộc vùng tối lớn (ảnh, mảng in đậm) -> không phải nền giấy -> nội suy từ lân cận
        int n = gw * gh;
        float[] lum = new float[n];
        float[] sorted = new float[n];
        for (int i = 0; i < n; i++) { lum[i] = 0.299f * r[i] + 0.587f * g[i] + 0.114f * b[i]; sorted[i] = lum[i]; }
        java.util.Arrays.sort(sorted);
        float paper = sorted[Math.min(n - 1, (int) (n * 0.9f))];
        boolean[] valid = new boolean[n];
        int nValid = 0;
        for (int i = 0; i < n; i++) { valid[i] = lum[i] >= 0.62f * paper; if (valid[i]) nValid++; }
        if (nValid == 0) {
            for (int i = 0; i < n; i++) valid[i] = true;
        } else {
            boolean changed = true;
            int guard = 0;
            while (nValid < n && changed && guard++ < gw + gh) {
                changed = false;
                boolean[] nv = valid.clone();
                for (int y = 0; y < gh; y++) {
                    for (int x = 0; x < gw; x++) {
                        int i = y * gw + x;
                        if (valid[i]) continue;
                        float sr = 0, sg = 0, sb = 0; int c = 0;
                        for (int dy = -1; dy <= 1; dy++) {
                            int yy = y + dy;
                            if (yy < 0 || yy >= gh) continue;
                            for (int dx = -1; dx <= 1; dx++) {
                                int xx = x + dx;
                                if (xx < 0 || xx >= gw) continue;
                                int j = yy * gw + xx;
                                if (!valid[j]) continue;
                                sr += r[j]; sg += g[j]; sb += b[j]; c++;
                            }
                        }
                        if (c > 0) {
                            r[i] = sr / c; g[i] = sg / c; b[i] = sb / c;
                            nv[i] = true; nValid++; changed = true;
                        }
                    }
                }
                valid = nv;
            }
        }
        // làm mượt lưới
        for (int it = 0; it < 2; it++) {
            r = box3(r, gw, gh);
            g = box3(g, gw, gh);
            b = box3(b, gw, gh);
        }
        for (int i = 0; i < n; i++) {
            r[i] = Math.max(24f, r[i]);
            g[i] = Math.max(24f, g[i]);
            b[i] = Math.max(24f, b[i]);
        }
        Bg bg = new Bg();
        bg.gw = gw; bg.gh = gh; bg.cell = cell;
        bg.r = r; bg.g = g; bg.b = b;
        return bg;
    }

    private static float[] box3(float[] a, int w, int h) {
        float[] o = new float[a.length];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float s = 0; int c = 0;
                for (int dy = -1; dy <= 1; dy++) {
                    int yy = y + dy;
                    if (yy < 0 || yy >= h) continue;
                    for (int dx = -1; dx <= 1; dx++) {
                        int xx = x + dx;
                        if (xx < 0 || xx >= w) continue;
                        s += a[yy * w + xx]; c++;
                    }
                }
                o[y * w + x] = s / c;
            }
        }
        return o;
    }
}
