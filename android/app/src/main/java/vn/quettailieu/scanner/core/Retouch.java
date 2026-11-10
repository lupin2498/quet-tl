package vn.quettailieu.scanner.core;

import java.util.List;

/**
 * Làm đẹp / chỉnh sửa trang (Java thuần, không phụ thuộc Android):
 * phát hiện + gỡ ngón tay giữ giấy, tô lại vùng bằng màu nền giấy, "Xóa thông minh"
 * (bút, hình chữ nhật, thần kỳ, chữ viết tay, văn bản), dọn mép, khử nhiễu, làm nét.
 *
 * Mặt nạ là mảng byte w*h (khác 0 = thuộc vùng). Bản Swift (Retouch.swift) port 1-1 từ file này.
 */
public final class Retouch {
    private Retouch() {}

    /** Các kiểu xoá của "Xóa thông minh". */
    public static final int PEN = 0;    // tô nền đúng theo nét bút
    public static final int RECT = 1;   // tô nền cả hình chữ nhật
    public static final int MAGIC = 2;  // như bút + xoá luôn cả vật thể dính liền với nét tô
    public static final int TEXT = 3;   // chỉ xoá nét chữ dưới nét tô, giữ đường kẻ bảng
    public static final int HAND = 4;   // xoá nét bút màu (chữ viết tay) trên cả trang

    /** Một thao tác xoá; toạ độ chuẩn hoá 0..1 theo ảnh đã nắn phẳng (chưa xoay). */
    public static final class Op {
        public int kind;
        /** Bề rộng nét / bề rộng ảnh. */
        public float width;
        /** x0,y0,x1,y1,... (RECT: 4 góc). */
        public float[] pts;

        public Op() {}

        public Op(int kind, float width, float[] pts) {
            this.kind = kind;
            this.width = width;
            this.pts = pts;
        }
    }

    // ================================================================== mặt nạ

    private static boolean finite(float v) {
        return !Float.isNaN(v) && !Float.isInfinite(v);
    }

    private static int clampI(float v, int lo, int hi) {
        if (!(v > lo)) return lo;
        if (v >= hi) return hi;
        return (int) v;
    }

    /** Tô mặt nạ theo nét vẽ: mọi điểm ảnh cách đường gấp khúc pts (toạ độ pixel) không quá radius. */
    public static void strokeMask(byte[] mask, int w, int h, float[] pts, float radius) {
        if (mask == null || pts == null || pts.length < 2 || w <= 0 || h <= 0) return;
        float rad = radius > 0.5f ? radius : 0.5f;
        if (!finite(rad)) return;
        float r2 = rad * rad;
        int n = pts.length / 2;
        for (int i = 0; i < n; i++) {
            float ax = pts[2 * i], ay = pts[2 * i + 1];
            float bx = ax, by = ay;
            if (i + 1 < n) {
                bx = pts[2 * i + 2];
                by = pts[2 * i + 3];
            } else if (n > 1) {
                break;
            }
            if (!finite(ax) || !finite(ay) || !finite(bx) || !finite(by)) continue;
            int x0 = clampI((float) Math.floor(Math.min(ax, bx) - rad), 0, w - 1);
            int x1 = clampI((float) Math.ceil(Math.max(ax, bx) + rad), 0, w - 1);
            int y0 = clampI((float) Math.floor(Math.min(ay, by) - rad), 0, h - 1);
            int y1 = clampI((float) Math.ceil(Math.max(ay, by) + rad), 0, h - 1);
            float dx = bx - ax, dy = by - ay;
            float len2 = dx * dx + dy * dy;
            for (int y = y0; y <= y1; y++) {
                float py = y + 0.5f - ay;
                for (int x = x0; x <= x1; x++) {
                    float pxx = x + 0.5f - ax;
                    float t = 0f;
                    if (len2 > 0f) {
                        t = (pxx * dx + py * dy) / len2;
                        if (t < 0f) t = 0f;
                        else if (t > 1f) t = 1f;
                    }
                    float ex = pxx - t * dx, ey = py - t * dy;
                    if (ex * ex + ey * ey <= r2) mask[y * w + x] = 1;
                }
            }
        }
    }

    /** Tô mặt nạ đa giác (toạ độ pixel, quy tắc chẵn-lẻ). */
    public static void polyMask(byte[] mask, int w, int h, float[] poly) {
        if (mask == null || poly == null || poly.length < 6 || w <= 0 || h <= 0) return;
        int n = poly.length / 2;
        float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            if (!finite(poly[2 * i]) || !finite(poly[2 * i + 1])) return;
            minY = Math.min(minY, poly[2 * i + 1]);
            maxY = Math.max(maxY, poly[2 * i + 1]);
        }
        int y0 = clampI((float) Math.floor(minY), 0, h - 1), y1 = clampI((float) Math.ceil(maxY), 0, h - 1);
        float[] xs = new float[n];
        for (int y = y0; y <= y1; y++) {
            float sy = y + 0.5f;
            int c = 0;
            for (int i = 0; i < n; i++) {
                int j = (i + 1) % n;
                float ay = poly[2 * i + 1], by = poly[2 * j + 1];
                if ((ay <= sy && by > sy) || (by <= sy && ay > sy)) {
                    float ax = poly[2 * i], bx = poly[2 * j];
                    xs[c++] = ax + (sy - ay) / (by - ay) * (bx - ax);
                }
            }
            // sắp xếp chèn (ít giao điểm)
            for (int a = 1; a < c; a++) {
                float v = xs[a];
                int b = a - 1;
                while (b >= 0 && xs[b] > v) {
                    xs[b + 1] = xs[b];
                    b--;
                }
                xs[b + 1] = v;
            }
            for (int a = 0; a + 1 < c; a += 2) {
                int xa = clampI((float) Math.ceil(xs[a] - 0.5f), 0, w), xb = clampI((float) Math.floor(xs[a + 1] - 0.5f), -1, w - 1);
                for (int x = xa; x <= xb; x++) mask[y * w + x] = 1;
            }
        }
    }

    /** Khung bao {x0,y0,x1,y1} của mặt nạ, null nếu rỗng. */
    static int[] bounds(byte[] mask, int w, int h) {
        int x0 = w, y0 = h, x1 = -1, y1 = -1;
        for (int y = 0; y < h; y++) {
            int off = y * w;
            for (int x = 0; x < w; x++) {
                if (mask[off + x] != 0) {
                    if (x < x0) x0 = x;
                    if (x > x1) x1 = x;
                    if (y < y0) y0 = y;
                    if (y > y1) y1 = y;
                }
            }
        }
        if (x1 < 0) return null;
        return new int[]{x0, y0, x1, y1};
    }

    /** Nở mặt nạ bán kính r (hình vuông), chỉ xét trong khung bb đã nới r. */
    static void dilate(byte[] mask, int w, int h, int r, int[] bb) {
        if (r <= 0 || bb == null) return;
        int x0 = Math.max(0, bb[0] - r), y0 = Math.max(0, bb[1] - r);
        int x1 = Math.min(w - 1, bb[2] + r), y1 = Math.min(h - 1, bb[3] + r);
        int rw = x1 - x0 + 1, rh = y1 - y0 + 1;
        byte[] t = new byte[rw * rh];
        for (int y = 0; y < rh; y++) {
            int off = (y + y0) * w + x0;
            int last = -1 << 20;
            // quét trái -> phải rồi phải -> trái: khoảng cách tới điểm bật gần nhất trên hàng
            for (int x = 0; x < rw; x++) {
                if (mask[off + x] != 0) last = x;
                if (x - last <= r) t[y * rw + x] = 1;
            }
            last = 1 << 20;
            for (int x = rw - 1; x >= 0; x--) {
                if (mask[off + x] != 0) last = x;
                if (last - x <= r) t[y * rw + x] = 1;
            }
        }
        for (int x = 0; x < rw; x++) {
            int last = -1 << 20;
            for (int y = 0; y < rh; y++) {
                if (t[y * rw + x] != 0) last = y;
                if (y - last <= r) mask[(y + y0) * w + x + x0] = 1;
            }
            last = 1 << 20;
            for (int y = rh - 1; y >= 0; y--) {
                if (t[y * rw + x] != 0) last = y;
                if (last - y <= r) mask[(y + y0) * w + x + x0] = 1;
            }
        }
    }

    // ================================================================== tô nền

    /** Tỷ lệ giữa giấy thường và điểm sáng nhất của ô nền (nền ước lượng hơi sáng hơn mặt giấy). */
    private static final float PAPER_K = 0.975f;

    /**
     * Tô lại vùng mặt nạ bằng màu nền giấy ước lượng từ phần còn lại của ảnh (mép vùng được làm mềm).
     */
    public static void fill(int[] px, int w, int h, byte[] mask) {
        if (px == null || mask == null || w < 4 || h < 4 || px.length < w * h || mask.length < w * h) return;
        int[] bb = bounds(mask, w, h);
        if (bb == null) return;
        Enhancer.Bg bg = Enhancer.backgroundFine(px, w, h, mask);
        int f = Math.max(1, Math.round(Math.max(w, h) / 700f));
        int x0 = Math.max(0, bb[0] - f), y0 = Math.max(0, bb[1] - f);
        int x1 = Math.min(w - 1, bb[2] + f), y1 = Math.min(h - 1, bb[3] + f);
        int rw = x1 - x0 + 1, rh = y1 - y0 + 1;
        // độ phủ 0..255: làm mờ hộp mặt nạ bán kính f (tổng trượt, hai chiều)
        int win = 2 * f + 1;
        int[] t = new int[rw * rh];
        for (int y = 0; y < rh; y++) {
            int off = (y + y0) * w;
            int s = 0;
            for (int x = -f; x <= f; x++) {
                int xx = x + x0;
                if (xx >= 0 && xx < w && mask[off + xx] != 0) s++;
            }
            for (int x = 0; x < rw; x++) {
                t[y * rw + x] = s;
                int out = x + x0 - f, in = x + x0 + f + 1;
                if (out >= 0 && out < w && mask[off + out] != 0) s--;
                if (in >= 0 && in < w && mask[off + in] != 0) s++;
            }
        }
        // hàng ngoài khung không có điểm mặt nạ nào (khung đã nới f) nên đóng góp 0
        int[] a = new int[rw * rh];
        int full = win * win;
        for (int x = 0; x < rw; x++) {
            int s = 0;
            for (int y = 0; y <= f && y < rh; y++) s += t[y * rw + x];
            for (int y = 0; y < rh; y++) {
                a[y * rw + x] = Math.min(255, s * 255 / full);
                int out = y - f, in = y + f + 1;
                if (out >= 0) s -= t[out * rw + x];
                if (in < rh) s += t[in * rw + x];
            }
        }
        float[] rowR = new float[w], rowG = new float[w], rowB = new float[w];
        for (int y = y0; y <= y1; y++) {
            bg.row(y, rowR, rowG, rowB);
            int off = y * w;
            for (int x = x0; x <= x1; x++) {
                int al = a[(y - y0) * rw + (x - x0)];
                if (mask[off + x] != 0) al = Math.max(al, 200);
                if (al == 0) continue;
                int fr = (int) (rowR[x] * PAPER_K + 0.5f), fg = (int) (rowG[x] * PAPER_K + 0.5f), fb = (int) (rowB[x] * PAPER_K + 0.5f);
                if (fr > 255) fr = 255;
                if (fg > 255) fg = 255;
                if (fb > 255) fb = 255;
                int c = px[off + x];
                int r = (((c >> 16) & 255) * (255 - al) + fr * al + 127) / 255;
                int g = (((c >> 8) & 255) * (255 - al) + fg * al + 127) / 255;
                int b = ((c & 255) * (255 - al) + fb * al + 127) / 255;
                px[off + x] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }
    }

    // ================================================================== ngón tay

    /**
     * Tìm ngón tay giữ giấy: mảng màu da đặc, bám vào mép ảnh và thò vào trong trang.
     * Trả về mặt nạ w*h (đã nới rộng để phủ viền/bóng sát ngón tay) hoặc null nếu không thấy.
     */
    public static byte[] fingerMask(int[] px, int w, int h) {
        if (px == null || w < 48 || h < 48 || px.length < w * h) return null;
        int k = Math.max(1, Math.round(Math.max(w, h) / 420f));
        int sw = w / k, sh = h / k;
        if (sw < 24 || sh < 24) return null;
        int n = sw * sh;
        int[] sr = new int[n], sg = new int[n], sb = new int[n];
        int kk = k * k;
        for (int y = 0; y < sh; y++) {
            for (int x = 0; x < sw; x++) {
                int r = 0, g = 0, b = 0;
                for (int dy = 0; dy < k; dy++) {
                    int o = (y * k + dy) * w + x * k;
                    for (int dx = 0; dx < k; dx++) {
                        int c = px[o + dx];
                        r += (c >> 16) & 255; g += (c >> 8) & 255; b += c & 255;
                    }
                }
                int i = y * sw + x;
                sr[i] = r / kk; sg[i] = g / kk; sb[i] = b / kk;
            }
        }
        // màu giấy: trung bình các điểm sáng (từ phân vị 75% trở lên)
        int[] hist = new int[256];
        for (int i = 0; i < n; i++) hist[(299 * sr[i] + 587 * sg[i] + 114 * sb[i]) / 1000]++;
        int thr = 0, acc = 0;
        int need = n * 3 / 4;
        for (int l = 0; l < 256; l++) {
            acc += hist[l];
            if (acc >= need) { thr = l; break; }
        }
        long pr = 0, pg = 0, pb = 0;
        int pc = 0;
        for (int i = 0; i < n; i++) {
            if ((299 * sr[i] + 587 * sg[i] + 114 * sb[i]) / 1000 >= thr) { pr += sr[i]; pg += sg[i]; pb += sb[i]; pc++; }
        }
        if (pc == 0) return null;
        int Pr = (int) (pr / pc), Pg = (int) (pg / pc), Pb = (int) (pb / pc);
        if (Pr < 60 || Pg < 60 || Pb < 60) return null;
        // phân loại màu da sau khi cân bằng trắng theo màu giấy (giấy = 1024)
        byte[] s = new byte[n];
        int[] lumN = new int[n];
        for (int i = 0; i < n; i++) {
            int nr = sr[i] * 1024 / Pr, ng = sg[i] * 1024 / Pg, nb = sb[i] * 1024 / Pb;
            int lum = (299 * nr + 587 * ng + 114 * nb) / 1000;
            lumN[i] = lum;
            if (lum < 200 || lum > 1000) continue;
            if (nr * 100 < ng * 112 || nr * 100 > ng * 260) continue;
            if (ng * 100 < nb * 96 || ng * 100 > nb * 200) continue;
            if (nr - nb < 150) continue;
            s[i] = 1;
        }
        // lấp lỗ nhỏ (móng tay, vệt bóng sáng): nở 2 rồi co 2
        closeSmall(s, sw, sh, 2);
        // thành phần liên thông 4 hướng
        int[] label = new int[n];
        int[] queue = new int[n];
        byte[] keep = new byte[n];
        boolean any = false;
        int minDim = Math.min(sw, sh);
        int minArea = Math.max(40, n * 12 / 10000);
        for (int start = 0; start < n; start++) {
            if (s[start] == 0 || label[start] != 0) continue;
            int head = 0, tail = 0;
            queue[tail++] = start;
            label[start] = 1;
            int minX = sw, maxX = -1, minY = sh, maxY = -1;
            while (head < tail) {
                int i = queue[head++];
                int x = i % sw, y = i / sw;
                if (x < minX) minX = x;
                if (x > maxX) maxX = x;
                if (y < minY) minY = y;
                if (y > maxY) maxY = y;
                if (x > 0 && s[i - 1] != 0 && label[i - 1] == 0) { label[i - 1] = 1; queue[tail++] = i - 1; }
                if (x < sw - 1 && s[i + 1] != 0 && label[i + 1] == 0) { label[i + 1] = 1; queue[tail++] = i + 1; }
                if (y > 0 && s[i - sw] != 0 && label[i - sw] == 0) { label[i - sw] = 1; queue[tail++] = i - sw; }
                if (y < sh - 1 && s[i + sw] != 0 && label[i + sw] == 0) { label[i + sw] = 1; queue[tail++] = i + sw; }
            }
            int area = tail;
            boolean tl = minX <= 1, tr = maxX >= sw - 2, tt = minY <= 1, tb = maxY >= sh - 2;
            if (!(tl || tr || tt || tb)) continue;
            // chạm hai mép đối diện: dải dài (mép bàn do vùng cắt rộng), không phải ngón tay
            if ((tl && tr) || (tt && tb)) continue;
            if (area < minArea || area > n * 15 / 100) continue;
            int bw = maxX - minX + 1, bh = maxY - minY + 1;
            if (area * 100 < bw * bh * 38) continue;
            boolean ok = false;
            if ((tl || tr) && bw * 100 >= minDim * 3 && bh * 100 <= sh * 45) ok = true;
            if ((tt || tb) && bh * 100 >= minDim * 3 && bw * 100 <= sw * 45) ok = true;
            if (!ok) continue;
            for (int q = 0; q < tail; q++) keep[queue[q]] = 1;
            any = true;
        }
        if (!any) return null;
        // lan sang phần tối dính liền (mặt khuất của ngón tay, bóng sát ngón tay) trong phạm vi ngắn
        int grow = Math.max(3, Math.round(minDim * 0.03f));
        int[] dist = label;
        int head = 0, tail = 0;
        for (int i = 0; i < n; i++) {
            dist[i] = 0;
            if (keep[i] != 0) { dist[i] = 1; queue[tail++] = i; }
        }
        while (head < tail) {
            int i = queue[head++];
            int d = dist[i];
            if (d > grow) continue;
            int x = i % sw, y = i / sw;
            for (int dy = -1; dy <= 1; dy++) {
                int yy = y + dy;
                if (yy < 0 || yy >= sh) continue;
                for (int dx = -1; dx <= 1; dx++) {
                    int xx = x + dx;
                    if (xx < 0 || xx >= sw) continue;
                    int j = yy * sw + xx;
                    if (dist[j] != 0 || lumN[j] >= 760) continue;
                    dist[j] = d + 1;
                    keep[j] = 1;
                    queue[tail++] = j;
                }
            }
        }
        int[] sbb = bounds(keep, sw, sh);
        dilate(keep, sw, sh, Math.max(2, Math.round(minDim * 0.016f)), sbb);
        byte[] mask = new byte[w * h];
        for (int y = 0; y < h; y++) {
            int sy = Math.min(sh - 1, y / k);
            for (int x = 0; x < w; x++) {
                if (keep[sy * sw + Math.min(sw - 1, x / k)] != 0) mask[y * w + x] = 1;
            }
        }
        return mask;
    }

    /** Phép đóng nhỏ trên mặt nạ (nở r rồi co r). */
    private static void closeSmall(byte[] m, int w, int h, int r) {
        int[] bb = bounds(m, w, h);
        if (bb == null) return;
        dilate(m, w, h, r, bb);
        // co = nở phần bù
        byte[] inv = new byte[w * h];
        for (int i = 0; i < w * h; i++) inv[i] = (byte) (m[i] != 0 ? 0 : 1);
        int[] ib = bounds(inv, w, h);
        if (ib == null) return;
        dilate(inv, w, h, r, ib);
        for (int i = 0; i < w * h; i++) m[i] = (byte) (inv[i] != 0 ? 0 : 1);
    }

    /** Gỡ ngón tay giữ giấy (nếu có). Trả về true nếu đã gỡ. */
    public static boolean removeFingers(int[] px, int w, int h) {
        byte[] m = fingerMask(px, w, h);
        if (m == null) return false;
        fill(px, w, h, m);
        return true;
    }

    public static boolean hasFinger(int[] px, int w, int h) {
        return fingerMask(px, w, h) != null;
    }

    // ================================================================== phân loại mực

    private static final byte C_PAPER = 0, C_DARK = 1, C_BLUE = 2, C_RED = 3, C_GREEN = 4, C_YELLOW = 5;

    /** Phân loại từng điểm ảnh trong khung [x0..x1]x[y0..y1] so với nền giấy: giấy / mực tối / mực màu. */
    private static byte[] classify(int[] px, int w, int h, Enhancer.Bg bg, int x0, int y0, int x1, int y1) {
        int rw = x1 - x0 + 1, rh = y1 - y0 + 1;
        byte[] cls = new byte[rw * rh];
        float[] rowR = new float[w], rowG = new float[w], rowB = new float[w];
        for (int y = y0; y <= y1; y++) {
            bg.row(y, rowR, rowG, rowB);
            int off = y * w;
            for (int x = x0; x <= x1; x++) {
                int c = px[off + x];
                float r = ((c >> 16) & 255) / rowR[x];
                float g = ((c >> 8) & 255) / rowG[x];
                float b = (c & 255) / rowB[x];
                float mx = r > g ? (r > b ? r : b) : (g > b ? g : b);
                float mn = r < g ? (r < b ? r : b) : (g < b ? g : b);
                float ch = mx - mn;
                byte k = C_PAPER;
                if (ch > 0.16f && ch > 0.22f * mx) {
                    if (b >= r && b >= g) k = C_BLUE;
                    else if (b <= r && b <= g && g > 0.8f * r && r > 0.8f * g) k = C_YELLOW;
                    else if (r >= g) k = C_RED;
                    else k = C_GREEN;
                } else if (0.299f * r + 0.587f * g + 0.114f * b < 0.78f) {
                    k = C_DARK;
                }
                cls[(y - y0) * rw + (x - x0)] = k;
            }
        }
        return cls;
    }

    // ================================================================== xoá thông minh

    /**
     * Áp một thao tác xoá lên ảnh (chưa lọc). region: mặt nạ vùng người dùng tô (HAND: có thể null = cả trang).
     * Trả về true nếu ảnh thay đổi.
     */
    public static boolean erase(int[] px, int w, int h, int kind, byte[] region) {
        if (px == null || w < 4 || h < 4 || px.length < w * h) return false;
        if (region != null && region.length < w * h) return false;
        switch (kind) {
            case HAND:
                return eraseHandwriting(px, w, h, region);
            case MAGIC:
                return eraseMagic(px, w, h, region);
            case TEXT:
                return eraseText(px, w, h, region);
            default:
                if (region == null || bounds(region, w, h) == null) return false;
                fill(px, w, h, region);
                return true;
        }
    }

    /** Áp lần lượt danh sách thao tác xoá (các thao tác Bút/Hình chữ nhật liền nhau được gộp lại). */
    public static void applyErase(int[] px, int w, int h, List<Op> ops) {
        if (px == null || ops == null || w < 4 || h < 4 || px.length < w * h) return;
        byte[] pending = null;
        for (int i = 0; i < ops.size(); i++) {
            Op op = ops.get(i);
            if (op == null) continue;
            if (op.kind == HAND) {
                if (pending != null) { fill(px, w, h, pending); pending = null; }
                eraseHandwriting(px, w, h, null);
                continue;
            }
            byte[] m = (op.kind == PEN || op.kind == RECT) && pending != null ? pending : new byte[w * h];
            opMask(m, w, h, op);
            if (op.kind == PEN || op.kind == RECT) {
                pending = m;
            } else {
                if (pending != null) { fill(px, w, h, pending); pending = null; }
                erase(px, w, h, op.kind, m);
            }
        }
        if (pending != null) fill(px, w, h, pending);
    }

    /** Dựng mặt nạ vùng tô của một thao tác (toạ độ chuẩn hoá -> pixel). */
    public static void opMask(byte[] mask, int w, int h, Op op) {
        if (op == null || op.pts == null) return;
        float[] p = new float[op.pts.length];
        for (int i = 0; i + 1 < p.length; i += 2) {
            p[i] = op.pts[i] * w;
            p[i + 1] = op.pts[i + 1] * h;
        }
        if (op.kind == RECT) polyMask(mask, w, h, p);
        else strokeMask(mask, w, h, p, op.width * w / 2f);
    }

    /** Thần kỳ: tô nền vùng đã tô + xoá cả vật thể (mực) dính liền với nét tô trong phạm vi lân cận. */
    static boolean eraseMagic(int[] px, int w, int h, byte[] region) {
        if (region == null) return false;
        int[] bb = bounds(region, w, h);
        if (bb == null) return false;
        int minDim = Math.min(w, h);
        int margin = Math.max(4, Math.round(minDim * 0.2f));
        int x0 = Math.max(0, bb[0] - margin), y0 = Math.max(0, bb[1] - margin);
        int x1 = Math.min(w - 1, bb[2] + margin), y1 = Math.min(h - 1, bb[3] + margin);
        int rw = x1 - x0 + 1, rh = y1 - y0 + 1;
        Enhancer.Bg bg = Enhancer.backgroundFine(px, w, h, null);
        byte[] cls = classify(px, w, h, bg, x0, y0, x1, y1);
        // hạt giống: mực nằm dưới nét tô
        int seeds = 0, colored = 0;
        for (int y = 0; y < rh; y++) {
            for (int x = 0; x < rw; x++) {
                if (region[(y + y0) * w + x + x0] == 0) continue;
                byte k = cls[y * rw + x];
                if (k == C_PAPER) continue;
                seeds++;
                if (k != C_DARK) colored++;
            }
        }
        byte[] out = region.clone();
        if (seeds > 0) {
            boolean byColor = colored * 2 >= seeds;
            // mực tối (chữ in, đường kẻ) chỉ lan gần nét tô để không ăn theo đường kẻ bảng
            int reach = byColor ? margin : Math.max(2, Math.round(minDim * 0.012f));
            int[] dist = new int[rw * rh];
            int[] queue = new int[rw * rh];
            int head = 0, tail = 0;
            for (int y = 0; y < rh; y++) {
                for (int x = 0; x < rw; x++) {
                    int i = y * rw + x;
                    if (region[(y + y0) * w + x + x0] == 0) continue;
                    byte k = cls[i];
                    if (k == C_PAPER || (byColor ? k == C_DARK : k != C_DARK)) continue;
                    dist[i] = 1;
                    queue[tail++] = i;
                }
            }
            while (head < tail) {
                int i = queue[head++];
                int d = dist[i];
                out[(i / rw + y0) * w + (i % rw) + x0] = 1;
                if (d > reach) continue;
                int x = i % rw, y = i / rw;
                for (int dy = -1; dy <= 1; dy++) {
                    int yy = y + dy;
                    if (yy < 0 || yy >= rh) continue;
                    for (int dx = -1; dx <= 1; dx++) {
                        int xx = x + dx;
                        if (xx < 0 || xx >= rw) continue;
                        int j = yy * rw + xx;
                        if (dist[j] != 0) continue;
                        byte k = cls[j];
                        if (k == C_PAPER || (byColor ? k == C_DARK : k != C_DARK)) continue;
                        dist[j] = d + 1;
                        queue[tail++] = j;
                    }
                }
            }
            int[] ob = bounds(out, w, h);
            dilate(out, w, h, Math.max(1, Math.round(Math.max(w, h) / 900f)), ob);
        }
        fill(px, w, h, out);
        return true;
    }

    /** Xoá văn bản: chỉ xoá nét mực dưới nét tô; đường kẻ dài (kẻ bảng, gạch chân dài) được giữ lại. */
    static boolean eraseText(int[] px, int w, int h, byte[] region) {
        if (region == null) return false;
        int[] bb = bounds(region, w, h);
        if (bb == null) return false;
        int minDim = Math.min(w, h);
        int L = Math.max(24, Math.round(minDim * 0.045f));
        int x0 = Math.max(0, bb[0] - L), y0 = Math.max(0, bb[1] - L);
        int x1 = Math.min(w - 1, bb[2] + L), y1 = Math.min(h - 1, bb[3] + L);
        int rw = x1 - x0 + 1, rh = y1 - y0 + 1;
        Enhancer.Bg bg = Enhancer.backgroundFine(px, w, h, null);
        byte[] cls = classify(px, w, h, bg, x0, y0, x1, y1);
        byte[] line = new byte[rw * rh];
        // đường ngang: đoạn mực liên tục dài >= L (cho phép lệch 1 hàng vì trang hơi nghiêng)
        for (int y = 0; y < rh; y++) {
            int run = 0;
            for (int x = 0; x <= rw; x++) {
                boolean ink = false;
                if (x < rw) {
                    ink = cls[y * rw + x] != C_PAPER
                            || (y > 0 && cls[(y - 1) * rw + x] != C_PAPER)
                            || (y < rh - 1 && cls[(y + 1) * rw + x] != C_PAPER);
                }
                if (ink) {
                    run++;
                } else {
                    if (run >= L) for (int q = x - run; q < x; q++) line[y * rw + q] = 1;
                    run = 0;
                }
            }
        }
        for (int x = 0; x < rw; x++) {
            int run = 0;
            for (int y = 0; y <= rh; y++) {
                boolean ink = false;
                if (y < rh) {
                    ink = cls[y * rw + x] != C_PAPER
                            || (x > 0 && cls[y * rw + x - 1] != C_PAPER)
                            || (x < rw - 1 && cls[y * rw + x + 1] != C_PAPER);
                }
                if (ink) {
                    run++;
                } else {
                    if (run >= L) for (int q = y - run; q < y; q++) line[q * rw + x] = 1;
                    run = 0;
                }
            }
        }
        byte[] out = new byte[w * h];
        boolean any = false;
        for (int y = 0; y < rh; y++) {
            for (int x = 0; x < rw; x++) {
                int i = y * rw + x;
                if (cls[i] == C_PAPER || line[i] != 0) continue;
                int gi = (y + y0) * w + x + x0;
                if (region[gi] == 0) continue;
                out[gi] = 1;
                any = true;
            }
        }
        if (!any) return false;
        int[] ob = bounds(out, w, h);
        int d = Math.max(1, Math.round(Math.max(w, h) / 900f));
        dilate(out, w, h, d, ob);
        // không lấn vào đường kẻ và không ra ngoài vùng tô
        for (int y = 0; y < rh; y++) {
            for (int x = 0; x < rw; x++) {
                int gi = (y + y0) * w + x + x0;
                if (out[gi] != 0 && (line[y * rw + x] != 0 || region[gi] == 0)) out[gi] = 0;
            }
        }
        fill(px, w, h, out);
        return true;
    }

    /**
     * Xoá chữ viết tay bằng bút màu (xanh, tím, lục) và các vết mực đỏ nhỏ; mảng đỏ lớn (con dấu) được giữ lại.
     * region != null: chỉ trong vùng đó. Trả về true nếu có gì bị xoá.
     */
    public static boolean eraseHandwriting(int[] px, int w, int h, byte[] region) {
        if (px == null || w < 8 || h < 8 || px.length < w * h) return false;
        Enhancer.Bg bg = Enhancer.backgroundFine(px, w, h, null);
        byte[] cls = classify(px, w, h, bg, 0, 0, w - 1, h - 1);
        byte[] out = new byte[w * h];
        boolean any = false;
        // lưới thô để đo kích thước từng cụm mực đỏ
        int gs = Math.max(2, Math.round(Math.max(w, h) / 320f));
        int gw = (w + gs - 1) / gs, gh = (h + gs - 1) / gs;
        byte[] red = new byte[gw * gh];
        boolean anyRed = false;
        for (int y = 0; y < h; y++) {
            int off = y * w;
            for (int x = 0; x < w; x++) {
                if (region != null && region[off + x] == 0) continue;
                byte k = cls[off + x];
                if (k == C_BLUE || k == C_GREEN) {
                    // viền màu 1 điểm ảnh quanh chữ in đen (sắc sai, nén JPEG) không phải nét bút
                    if (sameAround(cls, w, h, x, y, k) < 5) continue;
                    out[off + x] = 1;
                    any = true;
                } else if (k == C_RED) {
                    red[(y / gs) * gw + x / gs] = 1;
                    anyRed = true;
                }
            }
        }
        if (anyRed) {
            int limit = Math.max(2, Math.round(Math.min(w, h) * 0.06f / gs));
            int[] queue = new int[gw * gh];
            byte[] seen = new byte[gw * gh];
            byte[] small = new byte[gw * gh];
            boolean anySmall = false;
            for (int start = 0; start < gw * gh; start++) {
                if (red[start] == 0 || seen[start] != 0) continue;
                int head = 0, tail = 0;
                queue[tail++] = start;
                seen[start] = 1;
                int minX = gw, maxX = -1, minY = gh, maxY = -1;
                while (head < tail) {
                    int i = queue[head++];
                    int x = i % gw, y = i / gw;
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                    for (int dy = -1; dy <= 1; dy++) {
                        int yy = y + dy;
                        if (yy < 0 || yy >= gh) continue;
                        for (int dx = -1; dx <= 1; dx++) {
                            int xx = x + dx;
                            if (xx < 0 || xx >= gw) continue;
                            int j = yy * gw + xx;
                            if (red[j] != 0 && seen[j] == 0) { seen[j] = 1; queue[tail++] = j; }
                        }
                    }
                }
                if (maxX - minX + 1 <= limit && maxY - minY + 1 <= limit) {
                    for (int q = 0; q < tail; q++) small[queue[q]] = 1;
                    anySmall = true;
                }
            }
            if (anySmall) {
                for (int y = 0; y < h; y++) {
                    int off = y * w;
                    for (int x = 0; x < w; x++) {
                        if (cls[off + x] != C_RED || small[(y / gs) * gw + x / gs] == 0) continue;
                        if (region != null && region[off + x] == 0) continue;
                        out[off + x] = 1;
                        any = true;
                    }
                }
            }
        }
        if (!any) return false;
        int[] ob = bounds(out, w, h);
        dilate(out, w, h, Math.max(1, Math.round(Math.max(w, h) / 450f)), ob);
        fill(px, w, h, out);
        return true;
    }

    /** Số điểm cùng loại mực trong ô 3x3 quanh (x,y), kể cả chính nó. */
    private static int sameAround(byte[] cls, int w, int h, int x, int y, byte k) {
        int c = 0;
        for (int dy = -1; dy <= 1; dy++) {
            int yy = y + dy;
            if (yy < 0 || yy >= h) continue;
            for (int dx = -1; dx <= 1; dx++) {
                int xx = x + dx;
                if (xx >= 0 && xx < w && cls[yy * w + xx] == k) c++;
            }
        }
        return c;
    }

    // ================================================================== dọn mép, làm nét, khử nhiễu

    /**
     * Dọn mép sau khi lọc: mảng tối bám vào mép ảnh và nằm gọn trong dải sát mép (mép bàn, bóng gáy giấy
     * do vùng cắt hơi rộng) được tô trắng. Nội dung chạy sâu vào trong trang (đường kẻ bảng...) được giữ nguyên.
     */
    public static void cleanEdges(int[] px, int w, int h) {
        if (px == null || w < 16 || h < 16 || px.length < w * h) return;
        int band = Math.max(3, Math.round(Math.min(w, h) * 0.025f));
        if (band * 2 >= Math.min(w, h)) return;
        // chỉ xét điểm trong dải sát mép; seen: 0 chưa xét, 1 đã vào hàng đợi
        byte[] seen = new byte[w * h];
        int[] queue = new int[2 * band * (w + h)];
        for (int pass = 0; pass < 4; pass++) {
            int count = pass < 2 ? w : h;
            for (int t = 0; t < count; t++) {
                int sx = pass == 0 ? t : pass == 1 ? t : pass == 2 ? 0 : w - 1;
                int sy = pass == 0 ? 0 : pass == 1 ? h - 1 : t;
                int start = sy * w + sx;
                if (seen[start] != 0 || !dark(px[start])) continue;
                int head = 0, tail = 0;
                queue[tail++] = start;
                seen[start] = 1;
                boolean deep = false;
                while (head < tail) {
                    int i = queue[head++];
                    int x = i % w, y = i / w;
                    for (int dy = -1; dy <= 1; dy++) {
                        int yy = y + dy;
                        if (yy < 0 || yy >= h) continue;
                        for (int dx = -1; dx <= 1; dx++) {
                            int xx = x + dx;
                            if (xx < 0 || xx >= w) continue;
                            int j = yy * w + xx;
                            if (seen[j] != 0 || !dark(px[j])) continue;
                            int depth = Math.min(Math.min(xx, w - 1 - xx), Math.min(yy, h - 1 - yy));
                            if (depth >= band) { deep = true; continue; }
                            seen[j] = 1;
                            if (tail < queue.length) queue[tail++] = j;
                            else deep = true;
                        }
                    }
                }
                if (!deep) for (int q = 0; q < tail; q++) px[queue[q]] = 0xFFFFFFFF;
            }
        }
    }

    private static boolean dark(int c) {
        return 299 * ((c >> 16) & 255) + 587 * ((c >> 8) & 255) + 114 * (c & 255) < 185000;
    }

    /** Làm nét (unsharp mask 3x3), amount tính theo 1/256. Tại chỗ, dùng 3 hàng đệm. */
    public static void sharpen(int[] px, int w, int h, int amount) {
        if (px == null || w < 3 || h < 3 || px.length < w * h) return;
        int[] prev = new int[w], cur = new int[w], next = new int[w];
        System.arraycopy(px, 0, cur, 0, w);
        System.arraycopy(px, 0, prev, 0, w);
        for (int y = 0; y < h; y++) {
            if (y + 1 < h) System.arraycopy(px, (y + 1) * w, next, 0, w);
            else System.arraycopy(cur, 0, next, 0, w);
            int off = y * w;
            for (int x = 0; x < w; x++) {
                int xl = x > 0 ? x - 1 : 0, xr = x < w - 1 ? x + 1 : w - 1;
                int c = cur[x];
                int out = 0xFF000000;
                for (int sh = 16; sh >= 0; sh -= 8) {
                    int s = ((prev[xl] >> sh) & 255) + ((prev[x] >> sh) & 255) + ((prev[xr] >> sh) & 255)
                            + ((cur[xl] >> sh) & 255) + ((c >> sh) & 255) + ((cur[xr] >> sh) & 255)
                            + ((next[xl] >> sh) & 255) + ((next[x] >> sh) & 255) + ((next[xr] >> sh) & 255);
                    int v = (c >> sh) & 255;
                    // v + amount * (v - s/9), làm tròn; chia nguyên nên xử lý dấu thống nhất
                    int diff = 9 * v - s;
                    int add = diff * amount;
                    int q = add >= 0 ? (add + 1152) / 2304 : -((-add + 1152) / 2304);
                    int o = v + q;
                    if (o < 0) o = 0;
                    else if (o > 255) o = 255;
                    out |= o << sh;
                }
                px[off + x] = out;
            }
            int[] t = prev;
            prev = cur;
            cur = next;
            next = t;
        }
    }

    /** Khử nhiễu giữ mép: trung bình các điểm lân cận 3x3 có độ sáng gần điểm giữa. */
    public static void denoise(int[] px, int w, int h) {
        if (px == null || w < 3 || h < 3 || px.length < w * h) return;
        int[] prev = new int[w], cur = new int[w], next = new int[w];
        int[] lp = new int[w], lc = new int[w], ln = new int[w];
        System.arraycopy(px, 0, cur, 0, w);
        System.arraycopy(px, 0, prev, 0, w);
        lumRow(cur, lc, w);
        lumRow(prev, lp, w);
        for (int y = 0; y < h; y++) {
            if (y + 1 < h) System.arraycopy(px, (y + 1) * w, next, 0, w);
            else System.arraycopy(cur, 0, next, 0, w);
            lumRow(next, ln, w);
            int off = y * w;
            for (int x = 0; x < w; x++) {
                int xl = x > 0 ? x - 1 : 0, xr = x < w - 1 ? x + 1 : w - 1;
                int c = cur[x];
                int l0 = lc[x];
                int lo = l0 - 20000, hi = l0 + 20000;
                int sr = (c >> 16) & 255, sg = (c >> 8) & 255, sb = c & 255, cnt = 1;
                int v, l;
                l = lp[xl]; if (l >= lo && l <= hi) { v = prev[xl]; sr += (v >> 16) & 255; sg += (v >> 8) & 255; sb += v & 255; cnt++; }
                l = lp[x]; if (l >= lo && l <= hi) { v = prev[x]; sr += (v >> 16) & 255; sg += (v >> 8) & 255; sb += v & 255; cnt++; }
                l = lp[xr]; if (l >= lo && l <= hi) { v = prev[xr]; sr += (v >> 16) & 255; sg += (v >> 8) & 255; sb += v & 255; cnt++; }
                l = lc[xl]; if (l >= lo && l <= hi) { v = cur[xl]; sr += (v >> 16) & 255; sg += (v >> 8) & 255; sb += v & 255; cnt++; }
                l = lc[xr]; if (l >= lo && l <= hi) { v = cur[xr]; sr += (v >> 16) & 255; sg += (v >> 8) & 255; sb += v & 255; cnt++; }
                l = ln[xl]; if (l >= lo && l <= hi) { v = next[xl]; sr += (v >> 16) & 255; sg += (v >> 8) & 255; sb += v & 255; cnt++; }
                l = ln[x]; if (l >= lo && l <= hi) { v = next[x]; sr += (v >> 16) & 255; sg += (v >> 8) & 255; sb += v & 255; cnt++; }
                l = ln[xr]; if (l >= lo && l <= hi) { v = next[xr]; sr += (v >> 16) & 255; sg += (v >> 8) & 255; sb += v & 255; cnt++; }
                int r = (sr + cnt / 2) / cnt, g = (sg + cnt / 2) / cnt, b = (sb + cnt / 2) / cnt;
                px[off + x] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
            int[] t = prev;
            prev = cur;
            cur = next;
            next = t;
            t = lp;
            lp = lc;
            lc = ln;
            ln = t;
        }
    }

    private static void lumRow(int[] row, int[] out, int w) {
        for (int x = 0; x < w; x++) {
            int v = row[x];
            out[x] = 299 * ((v >> 16) & 255) + 587 * ((v >> 8) & 255) + 114 * (v & 255);
        }
    }

    // ================================================================== đổi vùng cắt

    /**
     * Chuyển toạ độ chuẩn hoá của các chỉnh sửa từ vùng cắt cũ sang vùng cắt mới (cùng hệ toạ độ ảnh gốc),
     * để nét xoá / đánh dấu vẫn nằm đúng chỗ trên trang sau khi cắt lại. Trả về mảng mới (hoặc pts nếu không đổi được).
     */
    public static float[] remap(float[] pts, float[] oldQuad, float[] newQuad) {
        if (pts == null || oldQuad == null || newQuad == null || oldQuad.length < 8 || newQuad.length < 8) return pts;
        float[] unit = {0, 0, 1, 0, 1, 1, 0, 1};
        double[] a = Geom.homography(unit, oldQuad);
        double[] b = Geom.homography(newQuad, unit);
        if (a == null || b == null) return pts;
        float[] out = new float[pts.length];
        for (int i = 0; i + 1 < pts.length; i += 2) {
            double u = pts[i], v = pts[i + 1];
            double d1 = a[6] * u + a[7] * v + a[8];
            double sx = (a[0] * u + a[1] * v + a[2]) / d1, sy = (a[3] * u + a[4] * v + a[5]) / d1;
            double d2 = b[6] * sx + b[7] * sy + b[8];
            double nu = (b[0] * sx + b[1] * sy + b[2]) / d2, nv = (b[3] * sx + b[4] * sy + b[5]) / d2;
            if (Double.isNaN(nu) || Double.isNaN(nv) || Double.isInfinite(nu) || Double.isInfinite(nv)) return pts;
            out[i] = (float) nu;
            out[i + 1] = (float) nv;
        }
        if ((pts.length & 1) == 1) out[pts.length - 1] = pts[pts.length - 1];
        return out;
    }

    /** Hệ số đổi bề rộng chuẩn hoá khi đổi vùng cắt (tỷ lệ bề ngang trung bình cũ / mới). */
    public static float remapScale(float[] oldQuad, float[] newQuad) {
        if (oldQuad == null || newQuad == null || oldQuad.length < 8 || newQuad.length < 8) return 1f;
        double wo = (Geom.dist(oldQuad, 0, 1) + Geom.dist(oldQuad, 3, 2)) / 2;
        double wn = (Geom.dist(newQuad, 0, 1) + Geom.dist(newQuad, 3, 2)) / 2;
        if (!(wo > 1e-6) || !(wn > 1e-6)) return 1f;
        double k = wo / wn;
        if (Double.isNaN(k) || Double.isInfinite(k)) return 1f;
        return (float) Math.max(0.05, Math.min(20.0, k));
    }
}
