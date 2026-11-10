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
    public static final int MAGIC_PRO = 5; // Thần kỳ: gỡ ngón tay, khử bóng, nền trắng, chữ sắc nét
    public static final int DENOISE = 6;   // Khử nhiễu: làm mịn hạt/vân, giữ nguyên tông ảnh
    public static final int NO_SHADOW = 7; // Không bóng: khử bóng đổ, giữ màu tự nhiên
    public static final int NO_HAND = 8;   // Ko viết tay: xoá nét bút màu

    /** Thứ tự hiển thị trong bảng lọc. */
    public static final int[] MODES = {ORIGINAL, LIGHTEN, MAGIC, MAGIC_PRO, DENOISE, NO_SHADOW, NO_HAND, BW, GRAY};

    public static String name(int mode) {
        switch (mode) {
            case ORIGINAL: return "Nguyên gốc";
            case LIGHTEN: return "Làm sáng";
            case GRAY: return "Thang xám";
            case BW: return "Đen trắng";
            case MAGIC_PRO: return "Thần kỳ";
            case DENOISE: return "Khử nhiễu";
            case NO_SHADOW: return "Không bóng";
            case NO_HAND: return "Ko viết tay";
            default: return "Tăng cường";
        }
    }

    /** Áp bộ lọc tại chỗ lên mảng ARGB. */
    public static void apply(int[] px, int w, int h, int mode) {
        if (mode == ORIGINAL || px == null || w < 4 || h < 4) return;
        if (px.length < w * h) return;
        switch (mode) {
            case MAGIC_PRO:
                Retouch.removeFingers(px, w, h);
                tonePro(px, w, h, backgroundFine(px, w, h, null), 0.24f, 0.86f, 1.45f);
                Retouch.sharpen(px, w, h, 96);
                Retouch.cleanEdges(px, w, h);
                return;
            case DENOISE:
                Retouch.denoise(px, w, h);
                return;
            case NO_SHADOW:
                tone(px, w, h, backgroundFine(px, w, h, null), 0.02f, 0.94f, 1.0f, 1.0f, false);
                return;
            case NO_HAND:
                Retouch.eraseHandwriting(px, w, h, null);
                tone(px, w, h, background(px, w, h), 0.04f, 0.95f, 1.10f, 1.15f, false);
                return;
            default:
                break;
        }
        Bg bg = background(px, w, h);
        float wp, bp, sat, gamma;
        switch (mode) {
            case LIGHTEN: bp = 0.04f; wp = 0.95f; sat = 1.10f; gamma = 1.15f; break;
            case GRAY: bp = 0.18f; wp = 0.90f; sat = 0f; gamma = 1.5f; break;
            case BW: bp = 0.62f; wp = 0.74f; sat = 0f; gamma = 1.0f; break;
            case MAGIC:
            default: bp = 0.20f; wp = 0.88f; sat = 1.35f; gamma = 1.6f; break;
        }
        tone(px, w, h, bg, bp, wp, sat, gamma, mode == GRAY || mode == BW);
    }

    /** Chuẩn hoá theo nền rồi áp mức đen/trắng + gamma + độ bão hoà. */
    static void tone(int[] px, int w, int h, Bg bg, float bp, float wp, float sat, float gamma, boolean mono) {
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

    /**
     * Đường cong tông cho "Thần kỳ": như tone() nhưng màu gần trung tính (chữ in đen, viền nhiễu màu do nén ảnh)
     * được đưa hẳn về xám, còn mực màu thật (con dấu, bút xanh) được giữ và làm đậm.
     */
    static void tonePro(int[] px, int w, int h, Bg bg, float bp, float wp, float gamma) {
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
        for (int y = 0; y < h; y++) {
            bg.row(y, rowR, rowG, rowB);
            int off = y * w;
            for (int x = 0; x < w; x++) {
                int c = px[off + x];
                float r = ((c >> 16) & 255) / rowR[x];
                float g = ((c >> 8) & 255) / rowG[x];
                float b = (c & 255) / rowB[x];
                float lum = 0.299f * r + 0.587f * g + 0.114f * b;
                float mx = r > g ? (r > b ? r : b) : (g > b ? g : b);
                float mn = r < g ? (r < b ? r : b) : (g < b ? g : b);
                float k = (mx - mn - 0.10f) / 0.16f;
                int ir, ig, ib;
                if (k <= 0f) {
                    ir = ig = ib = lut[idx(lum, LUT_SCALE, LUT_N)];
                } else {
                    if (k > 1f) k = 1f;
                    k = k * 1.3f;
                    r = lum + (r - lum) * k;
                    g = lum + (g - lum) * k;
                    b = lum + (b - lum) * k;
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
        limitChroma(r, g, b, lum, paper);
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

    /**
     * Nền giấy lưới mịn (ô ~1/320 cạnh dài) cho ảnh có bóng đổ rõ mép:
     * điểm sáng nhất trong ô -> bỏ ô quá tối -> phép đóng hình thái (xoá chữ đậm, hình nhỏ khỏi nền, giữ bóng lớn)
     * -> giới hạn sắc độ -> làm mượt. mask != 0: điểm ảnh bị bỏ qua (vùng sẽ được tô lại).
     */
    static Bg backgroundFine(int[] px, int w, int h, byte[] mask) {
        int cell = Math.max(3, Math.round(Math.max(w, h) / 320f));
        int gw = (w + cell - 1) / cell, gh = (h + cell - 1) / cell;
        int n = gw * gh;
        float[] r = new float[n], g = new float[n], b = new float[n];
        boolean[] valid = new boolean[n];
        int step = Math.max(1, cell / 4);
        for (int gy = 0; gy < gh; gy++) {
            int ys = gy * cell, ye = Math.min(h, ys + cell);
            for (int gx = 0; gx < gw; gx++) {
                int xs = gx * cell, xe = Math.min(w, xs + cell);
                int bestL = -1, br = 0, bgc = 0, bb = 0;
                for (int y = ys + 1; y < ye - 1; y += step) {
                    for (int x = xs + 1; x < xe - 1; x += step) {
                        if (mask != null && mask[y * w + x] != 0) continue;
                        int sr = 0, sg = 0, sb = 0;
                        for (int dy = -1; dy <= 1; dy++) {
                            int o = (y + dy) * w + x;
                            for (int dx = -1; dx <= 1; dx++) {
                                int c = px[o + dx];
                                sr += (c >> 16) & 255; sg += (c >> 8) & 255; sb += c & 255;
                            }
                        }
                        int l = 299 * sr + 587 * sg + 114 * sb;
                        if (l > bestL) { bestL = l; br = sr; bgc = sg; bb = sb; }
                    }
                }
                int i = gy * gw + gx;
                if (bestL >= 0) {
                    r[i] = br / 9f; g[i] = bgc / 9f; b[i] = bb / 9f;
                    valid[i] = true;
                }
            }
        }
        float[] lum = new float[n];
        float[] sorted = new float[n];
        int nv0 = 0;
        for (int i = 0; i < n; i++) {
            lum[i] = valid[i] ? 0.299f * r[i] + 0.587f * g[i] + 0.114f * b[i] : 0f;
            if (valid[i]) sorted[nv0++] = lum[i];
        }
        if (nv0 == 0) {
            // không có mẫu nào (ảnh quá nhỏ hoặc bị che hết): nền trắng
            for (int i = 0; i < n; i++) { r[i] = 255f; g[i] = 255f; b[i] = 255f; }
            Bg e = new Bg();
            e.gw = gw; e.gh = gh; e.cell = cell;
            e.r = r; e.g = g; e.b = b;
            return e;
        }
        java.util.Arrays.sort(sorted, 0, nv0);
        float paper = sorted[Math.min(nv0 - 1, (int) (nv0 * 0.9f))];
        int nValid = 0;
        for (int i = 0; i < n; i++) {
            if (valid[i] && lum[i] < 0.45f * paper) { valid[i] = false; lum[i] = 0f; }
            if (valid[i]) nValid++;
        }
        if (nValid == 0) {
            for (int i = 0; i < n; i++) { valid[i] = r[i] > 0f || g[i] > 0f || b[i] > 0f; if (valid[i]) nValid++; }
        }
        fillInvalid(r, g, b, valid, nValid, gw, gh);
        // phép đóng: lọc cực đại rồi cực tiểu, bán kính 5 ô (cửa sổ ~3,4% cạnh dài)
        int rad = 5;
        r = morph(morph(r, gw, gh, rad, true), gw, gh, rad, false);
        g = morph(morph(g, gw, gh, rad, true), gw, gh, rad, false);
        b = morph(morph(b, gw, gh, rad, true), gw, gh, rad, false);
        limitChroma(r, g, b, lum, paper);
        // chỉ làm mượt một lượt: làm mượt nhiều sẽ để lại vệt xám dọc mép bóng
        r = box3(r, gw, gh);
        g = box3(g, gw, gh);
        b = box3(b, gw, gh);
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

    /** Lấp ô không hợp lệ bằng trung bình các ô hợp lệ lân cận (lan dần). */
    static void fillInvalid(float[] r, float[] g, float[] b, boolean[] valid, int nValid, int gw, int gh) {
        int n = gw * gh;
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
            System.arraycopy(nv, 0, valid, 0, n);
        }
    }

    /** Lọc cực đại (max=true) hoặc cực tiểu theo cửa sổ vuông bán kính rad (tách hai chiều). */
    static float[] morph(float[] a, int w, int h, int rad, boolean max) {
        float[] t = new float[a.length], o = new float[a.length];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int x0 = Math.max(0, x - rad), x1 = Math.min(w - 1, x + rad);
                float v = a[y * w + x0];
                for (int k = x0 + 1; k <= x1; k++) {
                    float c = a[y * w + k];
                    if (max ? c > v : c < v) v = c;
                }
                t[y * w + x] = v;
            }
        }
        for (int y = 0; y < h; y++) {
            int y0 = Math.max(0, y - rad), y1 = Math.min(h - 1, y + rad);
            for (int x = 0; x < w; x++) {
                float v = t[y0 * w + x];
                for (int k = y0 + 1; k <= y1; k++) {
                    float c = t[k * w + x];
                    if (max ? c > v : c < v) v = c;
                }
                o[y * w + x] = v;
            }
        }
        return o;
    }

    /**
     * Giới hạn sắc độ của nền quanh màu giấy chung: mảng màu lớn (ảnh, nền màu) không bị coi là
     * màu giấy -> tránh đổi màu giả (vùng màu bị tẩy trắng, viền tím/xanh); vẫn sửa được ám màu nhẹ cục bộ.
     * lum: độ sáng ô trước khi nội suy; paper: độ sáng giấy (phân vị 90%).
     */
    static void limitChroma(float[] r, float[] g, float[] b, float[] lum, float paper) {
        int n = r.length;
        float pr = 0, pg = 0, pb = 0;
        int pc = 0;
        for (int i = 0; i < n; i++) {
            if (lum[i] >= 0.9f * paper) { pr += r[i]; pg += g[i]; pb += b[i]; pc++; }
        }
        if (pc == 0) return;
        float pl = Math.max(1f, 0.299f * pr / pc + 0.587f * pg / pc + 0.114f * pb / pc);
        float gr = Math.max(1e-3f, pr / pc / pl), gg = Math.max(1e-3f, pg / pc / pl), gb = Math.max(1e-3f, pb / pc / pl);
        for (int i = 0; i < n; i++) {
            float l = Math.max(1f, 0.299f * r[i] + 0.587f * g[i] + 0.114f * b[i]);
            float lr = r[i] / l, lg = g[i] / l, lb = b[i] / l;
            // lệch ít (<8%): giữ sắc độ cục bộ (ám màu do đèn); lệch nhiều (>15%): dùng màu giấy chung
            float dev = Math.max(Math.abs(lr / gr - 1f), Math.max(Math.abs(lg / gg - 1f), Math.abs(lb / gb - 1f)));
            float wgt = clampF((0.15f - dev) / 0.07f, 0f, 1f);
            float cr = gr + (lr - gr) * wgt, cg = gg + (lg - gg) * wgt, cb = gb + (lb - gb) * wgt;
            float k = l / Math.max(1e-3f, 0.299f * cr + 0.587f * cg + 0.114f * cb);
            r[i] = cr * k;
            g[i] = cg * k;
            b[i] = cb * k;
        }
    }

    private static float clampF(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
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
