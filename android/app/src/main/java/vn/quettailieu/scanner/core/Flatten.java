package vn.quettailieu.scanner.core;

/**
 * Bộ lọc "Làm phẳng" (Java thuần) cho trang giấy mấp mô / gấp nếp / nhàu:
 * <ol>
 * <li>gỡ ngón tay giữ giấy (Retouch.removeFingers);</li>
 * <li>làm sạch: chỉ giữ nét mực (chữ, bảng biểu, chữ ký, con dấu) — bóng mờ nếp gấp, mặt giấy nhàu,
 * vết vân tay, nền giấy được đưa về trắng; đường kẻ bảng (nhận dạng theo lưới) được giữ và làm rõ kể cả khi mảnh, nhạt;</li>
 * <li>làm thẳng: dò dòng chữ và đường kẻ bảng bị uốn lượn, khớp một trường dịch chuyển trơn
 * (bình phương tối thiểu trên lưới, năng lượng uốn nhỏ) rồi nắn lại ảnh để dòng chữ / đường kẻ thẳng.
 * Trang chụp xoay ngang (dòng chữ chạy dọc) được nhận ra tự động.</li>
 * </ol>
 * Kích thước ảnh giữ nguyên. Bản Swift (Flatten.swift) port 1-1 từ file này; CI so từng điểm ảnh giữa hai bản nên
 * chỉ dùng phép tính cơ bản (+ - * / sqrt, floor) với cùng thứ tự.
 */
public final class Flatten {
    private Flatten() {}

    /** Trường dịch chuyển trơn: điểm ảnh ra (u, v) lấy màu tại (u + dx, v + dy) của ảnh vào (nội suy song tuyến tính). */
    public static final class Field {
        public int gw, gh;
        /** Nút (i, j) nằm ở (ox + i * step, ox + j * step) trên ảnh. */
        public float step, ox;
        public float[] dx, dy;
        /** Chẩn đoán: số dòng chữ, đường kẻ ngang, đường kẻ dọc được dùng; dịch chuyển lớn nhất (điểm ảnh); trang xoay ngang. */
        public int lines, hrules, vrules;
        public float maxShift;
        public boolean rotated;
    }

    // ------------------------------------------------------------------ ngưỡng làm sạch
    // (độ sáng chia cho nền giấy, lượng tử 0..255 ứng với 0..1)

    /**
     * Lõi nét chữ nhạt: tối hơn 0,70 lần nền giấy và thuộc một cụm gọn cỡ chữ (ảnh chụp mờ, bản photo nhạt); trang chữ
     * nhạt nâng tới 0,76 theo độ đậm phổ biến của tâm nét + 0,17.
     */
    static final int SEED_Q = 179, SEED_MAX_Q = 194, SEED_OFFSET_Q = 43;
    /** Lõi nét đậm: tối hơn 0,52 lần nền giấy (trang chữ nhạt tới 0,62) — luôn được giữ. */
    static final int DARK_Q = 133, DARK_MAX_Q = 158, DARK_OFFSET_Q = 15;
    /** Chỉ xét tâm nét tối hơn 0,62 khi đo độ đậm phổ biến của chữ trên trang. */
    static final int MODE_LIMIT_Q = 158;
    /**
     * Điểm hơi tối chỉ được giữ khi nối với lõi nét: tối hơn 0,80 (phần nhạt của nét chữ mờ) — lan tới được từ lõi trong
     * phạm vi cỡ chữ; tối hơn 0,86 (viền khử răng cưa) — chỉ sát điểm đã giữ. Vệt nếp gấp nhạt không lan theo được.
     */
    static final int GROW_Q = 204, WEAK_Q = 219;
    /** Mực màu (con dấu, bút xanh) là nội dung. */
    static final float SEED_CHROMA = 0.20f, WEAK_CHROMA = 0.12f;
    /** Ảnh nhỏ (ảnh thu nhỏ của bảng lọc, cạnh dài < 600): nét chữ chỉ còn vài điểm ảnh — mọi điểm tối hơn GROW_Q là lõi. */
    static final int SMALL_L = 600;
    /**
     * Chữ nhỏ (ảnh chụp / ảnh nhập độ phân giải thấp, cỡ chữ <= FINE_HC_LO điểm ảnh): nét chữ chỉ 1-2 điểm ảnh nên dấu
     * thanh, chấm chữ i, dấu câu nhạt hẳn đi và tách khỏi thân chữ. Cụm nhỏ gọn (<= FINE_DIM cỡ chữ) tối hơn ngưỡng
     * nâng dần từ ngưỡng lõi nhạt tới GROW_Q, nằm trong FINE_NEAR cỡ chữ quanh lõi đậm, cũng là lõi. Giảm dần, hết hẳn ở
     * cỡ chữ FINE_HC_HI (trang lớn không đổi).
     */
    static final float FINE_HC_LO = 10f, FINE_HC_HI = 15f, FINE_DIM = 1.5f, FINE_NEAR = 0.6f;
    static final float TONE_BP = 0.20f, TONE_WP = 0.86f;
    /** Mặt nạ "mực lỏng" (tối hơn 0,90) để tìm đường kẻ bảng mảnh / nhạt. */
    static final int LOOSE_Q = 230;
    /**
     * Điểm thuộc đường kẻ bảng (lưới) tối hơn 0,95 được giữ; bảng kẻ nhạt được làm đậm: v' = 1 - g * (1 - v), g từ 1
     * (độ đậm trung vị của các đường kẻ tối hơn 0,55) tăng dần tới RULE_GAIN (nhạt hơn 0,78) — bảng in rõ không làm đậm
     * thêm (tránh đường kẻ lốm đốm).
     */
    static final int PROTECT_Q = 242;
    static final float RULE_GAIN = 2.5f;
    static final int GAIN_LO_Q = 140, GAIN_HI_Q = 200;
    /** Đường kẻ nhạt hơn trung vị các đường kẻ của bảng quá 0,12 (vệt nếp gấp vắt qua bảng) không thuộc lưới. */
    static final int RULE_DARK_SPREAD_Q = 31;

    // ------------------------------------------------------------------ tham số làm thẳng

    /** Cạnh dài của ảnh phân tích (điểm ảnh). */
    static final int ANALYSIS_LONG = 1000;
    /** Ảnh phân tích nhỏ hơn (ảnh thu nhỏ của bảng lọc): chỉ làm sạch, không nắn. */
    static final int MIN_ANALYSIS = 240;
    /** Số khoảng lưới theo cạnh dài. */
    static final int GRID_CELLS = 36;
    /** Trọng số năng lượng uốn, kéo căng và độ lớn của trường (so với trọng số 1 của mỗi mẫu dòng chữ). */
    static final double LAMBDA_BEND = 0.5, LAMBDA_STRETCH = 0.05, MU = 0.002;
    /** Sai số trung bình (điểm ảnh phân tích) tối thiểu để loại cả một đường không làm thẳng nhất quán được. */
    static final float LINE_LIM = 1.5f;
    static final int CG_ITERS = 400;

    /** Toàn bộ bộ lọc (gồm cả bước nắn thẳng). Trả về trường dịch chuyển đã dùng (null nếu không nắn). */
    public static Field apply(int[] px, int w, int h) {
        return apply(px, w, h, true);
    }

    /**
     * dewarp = false: chỉ gỡ ngón tay + làm sạch, giữ nguyên hình học (màn hình "Xóa thông minh": nét xoá được vẽ
     * trên ảnh chưa nắn và áp trước bộ lọc, nên ảnh xem trước ở đó không được dời điểm ảnh).
     */
    public static Field apply(int[] px, int w, int h, boolean dewarp) {
        if (px == null || w < 16 || h < 16 || px.length < w * h) return null;
        Retouch.removeFingers(px, w, h);
        Enhancer.Bg bg = Enhancer.backgroundFine(px, w, h, null);
        int n = w * h;
        byte[] lq = new byte[n];
        byte[] cls = new byte[n];
        normalize(px, w, h, bg, lq, cls);
        int mode = inkMode(lq, w, h);
        int tq = SEED_Q, dq = DARK_Q;
        if (mode >= 0) {
            tq = Math.max(SEED_Q, Math.min(SEED_MAX_Q, mode + SEED_OFFSET_Q));
            dq = Math.max(DARK_Q, Math.min(DARK_MAX_Q, mode + DARK_OFFSET_Q));
        }
        Analysis an = analyze(lq, w, h, tq);
        clean(px, w, h, bg, lq, cls, tq, dq, an);
        lq = null;
        cls = null;
        Retouch.cleanEdges(px, w, h);
        if (!dewarp || an.hc <= 0f) return null;
        Field f = estimate(px, w, h, an);
        if (f != null) {
            remap(px, w, h, f);
            Retouch.sharpen(px, w, h, 48);
        }
        return f;
    }

    // ================================================================== làm sạch

    /** Độ sáng chuẩn hoá theo nền (lq, 0..255) + cờ màu (cls: bit 0 lõi mực màu, bit 1 hơi có màu). */
    static void normalize(int[] px, int w, int h, Enhancer.Bg bg, byte[] lq, byte[] cls) {
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
                float ch = mx - mn;
                int q = (int) (lum * 255f + 0.5f);
                if (q > 255) q = 255;
                lq[off + x] = (byte) q;
                int f = 0;
                if (ch >= SEED_CHROMA && q < WEAK_Q) f |= 1;
                if (ch >= WEAK_CHROMA) f |= 2;
                cls[off + x] = (byte) f;
            }
        }
    }

    /**
     * Chỉ giữ nét mực: "lõi" nét và các điểm hơi tối lan tới được từ lõi trong phạm vi cỡ chữ; mọi thứ khác — bóng mờ,
     * vệt nếp gấp, mặt giấy nhàu, vân tay, sạn giấy — thành trắng. Lõi = tối hẳn so với nền giấy, mực màu, hoặc hơi tối
     * nhưng thuộc cụm gọn cỡ chữ (vệt nếp gấp dài không tính). Điểm trên đường kẻ bảng đã nhận dạng (an.rule) luôn được
     * giữ (bảng kẻ nhạt được làm đậm). Ngưỡng tự chỉnh theo độ đậm chữ của trang. cls được dùng làm cờ: bit 2 lõi, bit 3
     * đường kẻ bảng (bit 5: thuộc mặt nạ lỏng), bit 4 được giữ.
     */
    static void clean(int[] px, int w, int h, Enhancer.Bg bg, byte[] lq, byte[] cls, int tq, int dq, Analysis an) {
        int n = w * h;
        int L = Math.max(w, h);
        int d = Math.max(1, Math.round(L / 1750f));
        int bw = (w + d - 1) / d;
        int bh = (h + d - 1) / d, nb = bw * bh;
        // giới hạn lan từ lõi qua điểm hơi tối (ô d x d): ~0,3 cỡ chữ — đủ phủ phần nhạt của nét chữ mờ, vệt nếp gấp / vân tay
        // dính vào chữ chỉ còn đoạn ngắn
        int dg = an.hc > 0f ? Math.round(0.3f * an.hc * an.k / d) : Math.round(L / 500f / d);
        if (dg < 2) dg = 2;
        if (dg > 250) dg = 250;
        float hcFull = an.hc > 0f ? an.hc * an.k : L / 90f;
        byte[] keepB = clusters(lq, cls, w, h, Math.min(tq + 13, WEAK_Q - 13), d, Math.max(8, L / 20), 1.6f * hcFull);
        // chữ nhỏ: cụm nhỏ gọn hơi tối (dấu thanh, chấm, dấu câu) sát lõi đậm cũng là lõi (keepF = cụm gọn, coreN = gần lõi)
        float fine = hcFull >= FINE_HC_HI ? 0f : hcFull <= FINE_HC_LO ? 1f : (FINE_HC_HI - hcFull) / (FINE_HC_HI - FINE_HC_LO);
        int fq = tq + Math.round(fine * (GROW_Q - tq));
        // vệt nếp gấp nhạt: ô có điểm hơi tối không màu (tối hơn WEAK_Q, không tối hơn tq) và không sát ô có nét (tối hơn
        // tq / mực màu) nối thành vạch mảnh, thẳng, dài (lineB) — không được lan qua, mẩu của nó không được lấy làm lõi; nét
        // chữ chạm vệt chỉ kéo theo phần sát chỗ chạm
        byte[] lineB;
        {
            byte[] inkB = new byte[nb], fb = new byte[nb];
            for (int y = 0; y < h; y++) {
                int off = y * w, boff = (y / d) * bw;
                for (int x = 0; x < w; x++) {
                    int q = lq[off + x] & 255;
                    int f = cls[off + x];
                    int b = boff + x / d;
                    if (q < tq || (f & 1) != 0) inkB[b] = 1;
                    else if (q < WEAK_Q && (f & 2) == 0) fb[b] = 1;
                }
            }
            for (int by = 0; by < bh; by++) {
                for (int bx = 0; bx < bw; bx++) {
                    int b = by * bw + bx;
                    if (fb[b] == 0) continue;
                    boolean nearInk = false;
                    for (int dy = -1; dy <= 1 && !nearInk; dy++) {
                        int yy = by + dy;
                        if (yy < 0 || yy >= bh) continue;
                        for (int dx = -1; dx <= 1; dx++) {
                            int xx = bx + dx;
                            if (xx >= 0 && xx < bw && inkB[yy * bw + xx] != 0) {
                                nearInk = true;
                                break;
                            }
                        }
                    }
                    if (nearInk) fb[b] = 0;
                }
            }
            inkB = null;
            lineB = lineBlocks(fb, bw, bh, d, 1.6f * hcFull);
        }
        byte[] keepF = null, coreN = null;
        if (fine > 0f) {
            keepF = clusters(lq, cls, w, h, fq, d, Math.max(3, Math.round(FINE_DIM * hcFull)), 1.6f * hcFull);
            coreN = new byte[nb];
            for (int y = 0; y < h; y++) {
                int off = y * w, boff = (y / d) * bw;
                for (int x = 0; x < w; x++) {
                    if ((lq[off + x] & 255) < dq || (cls[off + x] & 1) != 0) coreN[boff + x / d] = 1;
                }
            }
            Retouch.dilate(coreN, bw, bh, Math.max(1, Math.round(FINE_NEAR * hcFull / d)), Retouch.bounds(coreN, bw, bh));
            for (int i = 0; i < nb; i++) if (lineB[i] != 0) coreN[i] = 0;
        }
        // ô: 3 = có lõi đậm (lan xa), 2 = có lõi nhạt (lan ngắn), 1 = có điểm hơi tối (được lan qua); colB: 2 = ô có mực
        // màu rõ, 1 = ô có điểm hơi có màu tối hơn GROW_Q (phần nhạt của nét mực màu: chữ ký bút xanh mảnh)
        byte[] bs = new byte[nb];
        byte[] colB = new byte[nb];
        int k = an.k, aw = an.aw, ah = an.ah;
        byte[] rule = an.rule, rule2 = an.rule2;
        boolean small = L < SMALL_L;
        for (int y = 0; y < h; y++) {
            int off = y * w, boff = (y / d) * bw;
            int ay = y / k;
            int roff = ay < ah ? ay * aw : -1;
            for (int x = 0; x < w; x++) {
                int i = off + x;
                int q = lq[i] & 255;
                int f = cls[i];
                int b = boff + x / d;
                boolean seed = q < dq || (f & 1) != 0 || (q < tq && keepB[b] != 0) || (small && q < GROW_Q)
                        || (keepF != null && q < fq && keepF[b] != 0 && coreN[b] != 0);
                boolean inMask = false;
                // (lõi / điểm đường kẻ luôn tối hơn PROTECT_Q: điểm nền giấy khỏi đọc mặt nạ)
                if (roff >= 0 && q < PROTECT_Q) {
                    int ax = x / k;
                    if (ax < aw) {
                        boolean m1 = rule != null && rule[roff + ax] != 0, m2 = rule2 != null && rule2[roff + ax] != 0;
                        inMask = m1 || m2;
                        // mực màu rõ (con dấu, chữ ký đè lên đường kẻ) không tính là đường kẻ: giữ màu, vẫn là lõi không lan
                        if (inMask && (f & 1) == 0) {
                            // đường kẻ mảnh ở ảnh nhỏ nằm vắt qua 2 hàng điểm ảnh (mỗi hàng chỉ nhạt): tính cả điểm sát bên
                            // tối hơn theo bề ngang nét (độ đậm hiệu dụng qe). Chỉ xét điểm không màu (con dấu, chữ ký đè lên
                            // đường kẻ giữ màu), đủ tối (tối hơn tq) và thuộc nét mảnh: hai điểm cách 2 về hai phía là giấy —
                            // vệt nếp gấp rộng chạm / vắt qua đường kẻ không được tính
                            int qe = 255;
                            if (q < tq && (f & 2) == 0) {
                                int qn = 255;
                                boolean thin = false;
                                if ((y < 2 || (lq[i - 2 * w] & 255) >= LOOSE_Q) && (y + 2 >= h || (lq[i + 2 * w] & 255) >= LOOSE_Q)) {
                                    thin = true;
                                    if (y > 0) qn = Math.min(qn, lq[i - w] & 255);
                                    if (y + 1 < h) qn = Math.min(qn, lq[i + w] & 255);
                                }
                                if ((x < 2 || (lq[i - 2] & 255) >= LOOSE_Q) && (x + 2 >= w || (lq[i + 2] & 255) >= LOOSE_Q)) {
                                    thin = true;
                                    if (x > 0) qn = Math.min(qn, lq[i - 1] & 255);
                                    if (x + 1 < w) qn = Math.min(qn, lq[i + 1] & 255);
                                }
                                if (thin) qe = 255 - Math.min(255, (255 - q) + (255 - qn));
                            }
                            if (m1 && (q < an.ruleQ || qe < an.ruleQE)) {
                                cls[i] |= 8;
                                seed = true;
                            } else if (m2 && (q < an.ruleQ2 || qe < an.ruleQE2)) {
                                cls[i] |= 8 | 32;
                                seed = true;
                            }
                        }
                    }
                }
                if (seed) {
                    cls[i] |= 4;
                    // lõi đậm / mực màu: lan xa (3); lõi nhạt: lan ngắn (2); điểm thuộc (vùng) đường kẻ bảng: chỉ giữ,
                    // không lan (1) — vệt nếp gấp vắt qua / chạm đường kẻ không bị kéo theo
                    int sv = inMask ? 1 : (q < dq || (f & 1) != 0) ? 3 : 2;
                    if (bs[b] < sv) bs[b] = (byte) sv;
                } else if ((q < GROW_Q || (f & 2) != 0) && bs[b] == 0 && lineB[b] == 0) {
                    bs[b] = 1;
                }
                if ((f & 1) != 0) colB[b] = 2;
                else if ((f & 2) != 0 && q < GROW_Q && colB[b] == 0) colB[b] = 1;
            }
        }
        keepB = null;
        keepF = null;
        coreN = null;
        lineB = null;
        byte[] reach = grow(bs, bw, bh, dg, Math.max(1, dg / 2));
        bs = null;
        // nét mực màu: lan từ ô có mực màu rõ qua các ô hơi có màu (vệt nếp gấp, bóng mờ không có màu) tối đa 2 cỡ chữ —
        // nối liền phần nhạt của nét ký mảnh mà không kéo theo cả dòng kẻ in màu nhạt của giấy vở chạm vào nét chữ
        byte[] reachC = colorReach(colB, bw, bh, Math.max(dg, Math.round(2f * hcFull / d)));
        colB = null;
        // giữ: lõi + điểm tối hơn GROW_Q lan tới được + điểm hơi có màu nối với mực màu; rồi viền (tối hơn WEAK_Q) sát các
        // điểm đó
        byte[] near = new byte[n];
        boolean any = false;
        for (int y = 0; y < h; y++) {
            int off = y * w, boff = (y / d) * bw;
            for (int x = 0; x < w; x++) {
                int i = off + x;
                int f = cls[i];
                int q = lq[i] & 255;
                int b = boff + x / d;
                if ((f & 4) != 0 || (reach[b] != 0 && (q < GROW_Q || (f & 2) != 0))
                        || (reachC != null && reachC[b] != 0 && (f & 2) != 0 && q < GROW_Q)) {
                    cls[i] |= 16;
                    near[i] = 1;
                    any = true;
                }
            }
        }
        reach = null;
        reachC = null;
        if (any) Retouch.dilate(near, w, h, Math.max(1, Math.round(L / 1400f)), Retouch.bounds(near, w, h));
        // đường cong tông: chuẩn hoá v (0..2) -> 0..255, mũ 1,5 (t * sqrt(t))
        final int LUT_SCALE = 1024, LUT_N = 2048;
        int[] lut = new int[LUT_N];
        float inv = 1f / (TONE_WP - TONE_BP);
        for (int i = 0; i < LUT_N; i++) {
            float v = i / (float) LUT_SCALE;
            float t = (v - TONE_BP) * inv;
            if (t <= 0f) lut[i] = 0;
            else if (t >= 1f) lut[i] = 255;
            else lut[i] = (int) (t * (float) Math.sqrt(t) * 255f + 0.5f);
        }
        float[] rowR = new float[w], rowG = new float[w], rowB = new float[w];
        for (int y = 0; y < h; y++) {
            int off = y * w;
            boolean rowAny = false;
            for (int x = 0; x < w && !rowAny; x++) rowAny = keep(lq, cls, near, off + x);
            if (!rowAny) {
                for (int x = 0; x < w; x++) px[off + x] = 0xFFFFFFFF;
                continue;
            }
            bg.row(y, rowR, rowG, rowB);
            for (int x = 0; x < w; x++) {
                if (!keep(lq, cls, near, off + x)) {
                    px[off + x] = 0xFFFFFFFF;
                    continue;
                }
                int c = px[off + x];
                float r = ((c >> 16) & 255) / rowR[x];
                float g = ((c >> 8) & 255) / rowG[x];
                float b = (c & 255) / rowB[x];
                float lum = 0.299f * r + 0.587f * g + 0.114f * b;
                int ir, ig, ib;
                if ((cls[off + x] & 8) != 0) {
                    // đường kẻ bảng: làm đậm theo bảng (đường kẻ mảnh, nhạt vẫn rõ sau khi lọc)
                    float gn = (cls[off + x] & 32) != 0 ? an.ruleGain2 : an.ruleGain;
                    ir = ig = ib = lut[idx(1f - gn * (1f - lum), LUT_SCALE, LUT_N)];
                    px[off + x] = 0xFF000000 | (ir << 16) | (ig << 8) | ib;
                    continue;
                }
                float mx = r > g ? (r > b ? r : b) : (g > b ? g : b);
                float mn = r < g ? (r < b ? r : b) : (g < b ? g : b);
                float q = (mx - mn - 0.10f) / 0.16f;
                if (q <= 0f) {
                    ir = ig = ib = lut[idx(lum, LUT_SCALE, LUT_N)];
                } else {
                    if (q > 1f) q = 1f;
                    q = q * 1.3f;
                    r = lum + (r - lum) * q;
                    g = lum + (g - lum) * q;
                    b = lum + (b - lum) * q;
                    ir = lut[idx(r, LUT_SCALE, LUT_N)];
                    ig = lut[idx(g, LUT_SCALE, LUT_N)];
                    ib = lut[idx(b, LUT_SCALE, LUT_N)];
                }
                px[off + x] = 0xFF000000 | (ir << 16) | (ig << 8) | ib;
            }
        }
    }

    // ================================================================== phân tích: cỡ chữ, lưới bảng

    /** Kết quả phân tích ở độ phân giải thấp (cạnh dài ~ANALYSIS_LONG, khối k x k điểm ảnh). */
    static final class Analysis {
        int k, aw, ah;
        /** Cỡ chữ (điểm ảnh phân tích); 0 = không đủ chữ để nắn. hcT: cỡ chữ theo chiều ngang (dùng khi trang xoay ngang). */
        float hc, hcT;
        int tmax, stride;
        /**
         * Mặt nạ đường kẻ bảng (thuộc lưới), đã nới 1 điểm: rule — từ mặt nạ chặt (bảng in rõ), rule2 — từ mặt nạ lỏng
         * (thêm đường kẻ mảnh / nhạt); null nếu không có. Điểm trong mặt nạ tối hơn ruleQ / ruleQ2, hoặc có độ đậm hiệu
         * dụng (cộng điểm sát bên tối nhất) tối hơn ruleQE / ruleQE2, được giữ và làm đậm.
         */
        byte[] rule, rule2;
        int ruleQ = PROTECT_Q, ruleQ2 = PROTECT_Q, ruleQE = 0, ruleQE2 = 0;
        /** Hệ số làm đậm đường kẻ của từng mặt nạ (1 = giữ nguyên tông). */
        float ruleGain = 1f, ruleGain2 = 1f;
        /** Mẫu đường tâm các đường kẻ ngang / dọc thuộc lưới (dùng để nắn). */
        Lines hr = new Lines(), vr = new Lines();
    }

    /**
     * Ảnh phân tích = độ sáng nhỏ nhất của mỗi khối k x k (giữ được nét mảnh). Đo cỡ chữ trên mặt nạ "chặt" (tối hơn tq);
     * tìm lưới bảng trên mặt nạ chặt (bảng in rõ — dùng để nắn và giữ nét) và trên mặt nạ "lỏng" (tối hơn 0,90 — thêm
     * đường kẻ mảnh / nhạt để giữ nét). Vệt nếp gấp, gạch chân đơn lẻ không thuộc lưới nên không được tính.
     */
    static Analysis analyze(byte[] lq, int w, int h, int tq) {
        Analysis an = new Analysis();
        int L = Math.max(w, h);
        int k = Math.max(1, Math.round(L / (float) ANALYSIS_LONG));
        int aw = w / k, ah = h / k;
        an.k = k;
        an.aw = aw;
        an.ah = ah;
        if (aw < 16 || ah < 16) return an;
        int nA = aw * ah;
        byte[] amin = new byte[nA], strict = new byte[nA], loose = new byte[nA];
        for (int y = 0; y < ah; y++) {
            for (int x = 0; x < aw; x++) {
                int mn = 255;
                for (int dy = 0; dy < k; dy++) {
                    int o = (y * k + dy) * w + x * k;
                    for (int dx = 0; dx < k; dx++) {
                        int q = lq[o + dx] & 255;
                        if (q < mn) mn = q;
                    }
                }
                amin[y * aw + x] = (byte) mn;
                if (mn < tq) strict[y * aw + x] = 1;
                if (mn < LOOSE_Q) loose[y * aw + x] = 1;
            }
        }
        int[] lab = new int[nA];
        int[] queue = new int[nA];
        float[] wOut = new float[1];
        float hc = textHeight(strict, aw, ah, lab, queue, wOut);
        an.hc = hc;
        an.hcT = wOut[0];
        if (hc <= 0f) return an;
        an.tmax = Math.max(2, Math.round(hc * 0.35f));
        an.stride = Math.max(4, Math.round(hc * 1.25f));
        // bảng in rõ: tìm trên mặt nạ chặt (vệt nếp gấp nhạt không lọt vào) — dùng để nắn và giữ nét; thêm lượt trên mặt
        // nạ lỏng để giữ cả đường kẻ mảnh / nhạt (chỉ dùng để nắn khi lượt chặt không thấy bảng)
        boolean strictGrid = grid(an, strict, amin, lab, queue, true);
        grid(an, loose, amin, lab, queue, !strictGrid);
        return an;
    }

    /**
     * Lưới bảng trên mặt nạ m (ảnh phân tích): đường kẻ ngang / dọc dài, mảnh, đủ thẳng; chỉ giữ "lõi lưới" — mỗi đường
     * cắt ít nhất 2 đường vuông góc còn lại, độ đậm không nhạt hơn hẳn các đường khác của bảng, tổng cộng >= 3 đường
     * một hướng và >= 2 đường hướng kia; cắt bỏ phần thò ra ngoài các giao điểm. Ghi mặt nạ + mẫu đường tâm vào an.
     * Trả về true nếu có lưới.
     */
    static boolean grid(Analysis an, byte[] m, byte[] amin, int[] lab, int[] queue, boolean primary) {
        int aw = an.aw, ah = an.ah, nA = aw * ah;
        float hc = an.hc;
        int tmax = an.tmax;
        int[] hrn = new int[nA], vrn = new int[nA];
        runLengths(m, aw, ah, hrn, vrn);
        byte[] th = new byte[nA], tv = new byte[nA];
        for (int i = 0; i < nA; i++) {
            if (m[i] == 0) continue;
            if (vrn[i] <= tmax || hrn[i] >= 3 * tmax) th[i] = 1;
            if (hrn[i] <= tmax || vrn[i] >= 3 * tmax) tv[i] = 1;
        }
        hrn = null;
        vrn = null;
        int[] hId = new int[nA], vId = new int[nA];
        Rules hs = new Rules(), vs = new Rules();
        findRules(th, amin, aw, ah, true, Math.max(Math.round(8f * hc), aw / 10), tmax, lab, queue, hId, hs);
        findRules(tv, amin, aw, ah, false, Math.max(Math.round(8f * hc), ah / 14), tmax, lab, queue, vId, vs);
        th = null;
        tv = null;
        if (hs.n == 0 || vs.n == 0) return false;
        // lưới: cặp (đường ngang, đường dọc) có điểm chung
        int nh = hs.n, nv = vs.n;
        boolean[] pair = new boolean[nh * nv];
        for (int i = 0; i < nA; i++) {
            if (hId[i] != 0 && vId[i] != 0) pair[(hId[i] - 1) * nv + vId[i] - 1] = true;
        }
        // lõi lưới: lặp loại đường cắt < 2 đường vuông góc còn lại, hoặc nhạt hơn hẳn các đường kẻ khác của bảng
        // (vệt nếp gấp vắt qua bảng in đậm) theo cả độ đậm lẫn độ đậm hiệu dụng (đường kẻ mảnh ở ảnh nhỏ nằm vắt qua 2 hàng
        // điểm ảnh thì từng hàng nhạt nhưng tổng vẫn đậm như các đường khác); cần >= 3 đường một hướng và >= 2 đường hướng kia
        boolean[] gH = new boolean[nh], gV = new boolean[nv];
        for (int a = 0; a < nh; a++) gH[a] = hs.ok[a];
        for (int b = 0; b < nv; b++) gV[b] = vs.ok[b];
        int[] dk = new int[nh + nv];
        int lim = 255, limE = 255;
        boolean changed = true;
        while (changed) {
            changed = false;
            int nd = 0;
            for (int a = 0; a < nh; a++) if (gH[a]) dk[nd++] = hs.dark[a];
            for (int b = 0; b < nv; b++) if (gV[b]) dk[nd++] = vs.dark[b];
            if (nd == 0) break;
            java.util.Arrays.sort(dk, 0, nd);
            lim = dk[nd / 2] + RULE_DARK_SPREAD_Q;
            nd = 0;
            for (int a = 0; a < nh; a++) if (gH[a]) dk[nd++] = hs.darkE[a];
            for (int b = 0; b < nv; b++) if (gV[b]) dk[nd++] = vs.darkE[b];
            java.util.Arrays.sort(dk, 0, nd);
            limE = dk[nd / 2] + RULE_DARK_SPREAD_Q;
            for (int a = 0; a < nh; a++) {
                if (!gH[a]) continue;
                int c = 0;
                for (int b = 0; b < nv; b++) if (gV[b] && pair[a * nv + b]) c++;
                if (c < 2 || (hs.dark[a] > lim && hs.darkE[a] > limE)) {
                    gH[a] = false;
                    changed = true;
                }
            }
            for (int b = 0; b < nv; b++) {
                if (!gV[b]) continue;
                int c = 0;
                for (int a = 0; a < nh; a++) if (gH[a] && pair[a * nv + b]) c++;
                if (c < 2 || (vs.dark[b] > lim && vs.darkE[b] > limE)) {
                    gV[b] = false;
                    changed = true;
                }
            }
        }
        int cH = 0, cV = 0;
        for (int a = 0; a < nh; a++) if (gH[a]) cH++;
        for (int b = 0; b < nv; b++) if (gV[b]) cV++;
        if (Math.min(cH, cV) < 2 || Math.max(cH, cV) < 3) return false;
        // phạm vi giao điểm với các đường còn lại
        int[] hLo = new int[nh], hHi = new int[nh], vLo = new int[nv], vHi = new int[nv];
        for (int i = 0; i < nh; i++) {
            hLo[i] = Integer.MAX_VALUE;
            hHi[i] = -1;
        }
        for (int i = 0; i < nv; i++) {
            vLo[i] = Integer.MAX_VALUE;
            vHi[i] = -1;
        }
        for (int i = 0; i < nA; i++) {
            if (hId[i] == 0 || vId[i] == 0) continue;
            int a = hId[i] - 1, b = vId[i] - 1;
            if (!gH[a] || !gV[b]) continue;
            int x = i % aw, y = i / aw;
            if (x < hLo[a]) hLo[a] = x;
            if (x > hHi[a]) hHi[a] = x;
            if (y < vLo[b]) vLo[b] = y;
            if (y > vHi[b]) vHi[b] = y;
        }
        int margin = 2 * tmax + 2;
        byte[] rule = new byte[nA];
        boolean anyRule = false;
        for (int i = 0; i < nA; i++) {
            int x = i % aw, y = i / aw;
            int a = hId[i] - 1, b = vId[i] - 1;
            if ((a >= 0 && gH[a] && x >= hLo[a] - margin && x <= hHi[a] + margin)
                    || (b >= 0 && gV[b] && y >= vLo[b] - margin && y <= vHi[b] + margin)) {
                rule[i] = 1;
                anyRule = true;
            }
        }
        if (!anyRule) return false;
        Retouch.dilate(rule, aw, ah, 1, Retouch.bounds(rule, aw, ah));
        // chỉ giữ điểm không nhạt hơn hẳn các đường kẻ của bảng (vệt nếp gấp dính vào đường kẻ thì không); làm đậm theo
        // độ đậm trung vị của cả bảng
        int med = lim - RULE_DARK_SPREAD_Q;
        float gain = 1f + (RULE_GAIN - 1f) * Math.max(0f, Math.min(1f, (med - GAIN_LO_Q) / (float) (GAIN_HI_Q - GAIN_LO_Q)));
        if (an.rule == null) {
            an.rule = rule;
            an.ruleQ = Math.min(PROTECT_Q, lim + 1);
            an.ruleQE = Math.min(PROTECT_Q, limE + 1);
            an.ruleGain = gain;
        } else {
            an.rule2 = rule;
            an.ruleQ2 = Math.min(PROTECT_Q, lim + 1);
            an.ruleQE2 = Math.min(PROTECT_Q, limE + 1);
            an.ruleGain2 = gain;
        }
        if (!primary) return true;
        int win = 2 * Math.max(2, Math.round(hc)) + 1;
        for (int a = 0; a < nh; a++) {
            if (gH[a]) hs.sample(a, Math.max(hs.a0[a], hLo[a] - margin), Math.min(hs.a1[a], hHi[a] + margin), an.stride, win, true, an.hr);
        }
        for (int b = 0; b < nv; b++) {
            if (gV[b]) vs.sample(b, Math.max(vs.a0[b], vLo[b] - margin), Math.min(vs.a1[b], vHi[b] + margin), an.stride, win, false, an.vr);
        }
        return true;
    }

    /** Các đường kẻ dài tìm được trên một hướng: khoảng a0..a1 theo chiều dọc đường, đường tâm cen (cov = 0: thiếu). */
    static final class Rules {
        int n;
        int[] a0 = new int[16], a1 = new int[16];
        float[][] cen = new float[16][];
        int[][] cov = new int[16][];
        /**
         * Độ đậm (trung vị độ sáng nhỏ nhất theo bề ngang nét, 0..255), độ đậm hiệu dụng (trung vị của 255 - tổng độ tối
         * điểm tối nhất và điểm sát bên tối hơn theo bề ngang nét, không âm) và cờ "đủ thẳng" của từng đường.
         */
        int[] dark = new int[16], darkE = new int[16];
        boolean[] ok = new boolean[16];

        void add(int s, int e, float[] c, int[] v, int dk, int dkE, boolean straight) {
            if (n == a0.length) {
                a0 = java.util.Arrays.copyOf(a0, n * 2);
                a1 = java.util.Arrays.copyOf(a1, n * 2);
                cen = java.util.Arrays.copyOf(cen, n * 2);
                cov = java.util.Arrays.copyOf(cov, n * 2);
                dark = java.util.Arrays.copyOf(dark, n * 2);
                darkE = java.util.Arrays.copyOf(darkE, n * 2);
                ok = java.util.Arrays.copyOf(ok, n * 2);
            }
            a0[n] = s;
            a1[n] = e;
            cen[n] = c;
            cov[n] = v;
            dark[n] = dk;
            darkE[n] = dkE;
            ok[n] = straight;
            n++;
        }

        /** Lấy mẫu đường tâm của đường r trong [s, e] (trung vị trượt cửa sổ win, mỗi stride điểm ảnh) vào out. */
        void sample(int r, int s, int e, int stride, int win, boolean horiz, Lines out) {
            float[] c = cen[r];
            int[] v = cov[r];
            int base = a0[r];
            float[] buf = new float[win];
            int rad = win / 2;
            int ln = -1;
            for (int a = s + stride / 2; a <= e; a += stride) {
                int m = 0;
                for (int t = a - rad; t <= a + rad; t++) {
                    if (t < s || t > e || v[t - base] == 0) continue;
                    buf[m++] = c[t - base];
                }
                if (m * 2 < win) continue;
                sortSmall(buf, m);
                float med = (m & 1) == 1 ? buf[m / 2] : (buf[m / 2 - 1] + buf[m / 2]) * 0.5f;
                if (ln < 0) ln = out.newLine();
                if (horiz) out.add(ln, a, med, 2f);
                else out.add(ln, med, a, 2f);
            }
        }
    }

    /**
     * Đường kẻ dài (ngang: horiz = true) trong mặt nạ nét mảnh m: thành phần liên thông trải dài >= minLen, phủ kín
     * gần hết chiều dài và mỏng. Ghi chỉ số (1..) của đường vào id, đường tâm vào out.
     */
    static void findRules(byte[] m, byte[] amin, int w, int h, boolean horiz, int minLen, int tmax, int[] lab,
                          int[] queue, int[] id, Rules out) {
        int[] dh = new int[256], dhE = new int[256];
        int n = w * h;
        for (int i = 0; i < n; i++) lab[i] = 0;
        int cur = 0;
        for (int start = 0; start < n; start++) {
            if (m[start] == 0 || lab[start] != 0) continue;
            cur++;
            int head = 0, tail = 0;
            queue[tail++] = start;
            lab[start] = cur;
            int minX = w, maxX = -1, minY = h, maxY = -1;
            while (head < tail) {
                int i = queue[head++];
                int x = i % w, y = i / w;
                if (x < minX) minX = x;
                if (x > maxX) maxX = x;
                if (y < minY) minY = y;
                if (y > maxY) maxY = y;
                for (int dy = -1; dy <= 1; dy++) {
                    int yy = y + dy;
                    if (yy < 0 || yy >= h) continue;
                    for (int dx = -1; dx <= 1; dx++) {
                        int xx = x + dx;
                        if (xx < 0 || xx >= w) continue;
                        int j = yy * w + xx;
                        if (m[j] != 0 && lab[j] == 0) {
                            lab[j] = cur;
                            queue[tail++] = j;
                        }
                    }
                }
            }
            int a0 = horiz ? minX : minY, a1 = horiz ? maxX : maxY;
            int b0 = horiz ? minY : minX, b1 = horiz ? maxY : maxX;
            int len = a1 - a0 + 1;
            if (len < minLen) continue;
            float[] cen = new float[len];
            int[] cov = new int[len];
            int covered = 0;
            for (int q = 0; q < 256; q++) {
                dh[q] = 0;
                dhE[q] = 0;
            }
            int bLim = horiz ? h : w;
            for (int a = a0; a <= a1; a++) {
                int c = 0, mn = 255, bm = -1;
                long sum = 0;
                for (int b = b0; b <= b1; b++) {
                    int i = horiz ? b * w + a : a * w + b;
                    if (lab[i] == cur) {
                        c++;
                        sum += b;
                        int q = amin[i] & 255;
                        if (q < mn) {
                            mn = q;
                            bm = b;
                        }
                    }
                }
                cov[a - a0] = c;
                if (c > 0) {
                    covered++;
                    cen[a - a0] = sum / (float) c;
                    dh[mn]++;
                    // điểm sát bên (theo bề ngang nét) tối hơn của điểm tối nhất; chỉ nét mảnh (hai điểm cách 2 về hai phía
                    // là giấy) mới được tính độ đậm hiệu dụng — vệt nếp gấp rộng thì lấy đúng điểm tối nhất
                    int qn = 255, far = 255;
                    if (bm > 0) qn = amin[horiz ? (bm - 1) * w + a : a * w + bm - 1] & 255;
                    if (bm + 1 < bLim) qn = Math.min(qn, amin[horiz ? (bm + 1) * w + a : a * w + bm + 1] & 255);
                    if (bm > 1) far = amin[horiz ? (bm - 2) * w + a : a * w + bm - 2] & 255;
                    if (bm + 2 < bLim) far = Math.min(far, amin[horiz ? (bm + 2) * w + a : a * w + bm + 2] & 255);
                    dhE[far >= LOOSE_Q ? 255 - Math.min(255, (255 - mn) + (255 - qn)) : mn]++;
                }
            }
            if (covered * 4 < len * 3 || tail > covered * (tmax + 2)) continue;
            // độ đậm: trung vị; độ nghiêng đầu - cuối (trung bình đường tâm 1/6 đầu và 1/6 cuối) <= 0,25
            int acc = 0, dk = 255;
            for (int q = 0; q < 256; q++) {
                acc += dh[q];
                if (acc * 2 >= covered) {
                    dk = q;
                    break;
                }
            }
            int accE = 0, dkE = 255;
            for (int q = 0; q < 256; q++) {
                accE += dhE[q];
                if (accE * 2 >= covered) {
                    dkE = q;
                    break;
                }
            }
            int part = Math.max(1, len / 6);
            float s0 = 0f, s1 = 0f;
            int c0 = 0, c1 = 0;
            for (int a = 0; a < part; a++) {
                if (cov[a] > 0) {
                    s0 += cen[a];
                    c0++;
                }
                if (cov[len - 1 - a] > 0) {
                    s1 += cen[len - 1 - a];
                    c1++;
                }
            }
            boolean straight = c0 > 0 && c1 > 0 && Math.abs(s1 / c1 - s0 / c0) <= 0.25f * len;
            out.add(a0, a1, cen, cov, dk, dkE, straight);
            int rid = out.n;
            for (int q = 0; q < tail; q++) id[queue[q]] = rid;
        }
    }

    // ================================================================== ước lượng trường dịch chuyển

    /**
     * Dò dòng chữ trên ảnh đã làm sạch (nền trắng) + đường kẻ bảng của an, khớp trường dịch chuyển làm chúng thẳng.
     * Dòng chữ chạy dọc (trang chụp xoay ngang) thì làm trên ảnh chuyển vị. Null nếu không đủ dữ liệu hoặc trang đã phẳng.
     */
    static Field estimate(int[] px, int w, int h, Analysis an) {
        int k = an.k, aw = an.aw, ah = an.ah;
        if (Math.min(aw, ah) < MIN_ANALYSIS || an.hc < 3f) return null;
        int nA = aw * ah;
        // mực chữ: trung bình khối k x k của ảnh đã làm sạch tối hơn 160, trừ đường kẻ bảng và các khối mực màu rõ cỡ con
        // dấu (khung lớn hơn 2,5 cỡ chữ theo cả hai chiều: vòng dấu cạnh một dòng chữ bị ghép nhầm thành đoạn dòng chữ, làm
        // cong trang phẳng); chữ viết bút xanh vẫn được tính — trừ khi bỏ khối màu thì không còn đủ dòng
        byte[] ink = new byte[nA], inkC = new byte[nA];
        int kk = k * k;
        boolean anyColor = false;
        for (int y = 0; y < ah; y++) {
            for (int x = 0; x < aw; x++) {
                int s = 0, sc = 0;
                for (int dy = 0; dy < k; dy++) {
                    int o = (y * k + dy) * w + x * k;
                    for (int dx = 0; dx < k; dx++) {
                        int c = px[o + dx];
                        int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
                        int l = 299 * r + 587 * g + 114 * b;
                        s += l;
                        int mx = r > g ? (r > b ? r : b) : (g > b ? g : b);
                        int mn = r < g ? (r < b ? r : b) : (g < b ? g : b);
                        sc += mx - mn > 60 ? 255000 : l;
                    }
                }
                int i = y * aw + x;
                if ((an.rule == null || an.rule[i] == 0) && (an.rule2 == null || an.rule2[i] == 0)) {
                    if (s < 160 * 1000 * kk) ink[i] = 1;
                    if (sc < 160 * 1000 * kk) inkC[i] = 1;
                    if (ink[i] != inkC[i]) anyColor = true;
                }
            }
        }
        if (anyColor) anyColor = keepSmallColor(ink, inkC, aw, ah, 2.5f * an.hc);
        Lines tl = new Lines();
        int nt = -1;
        if (anyColor) {
            nt = textLines(inkC, aw, ah, an.hc, tl);
            if (nt >= 3) {
                ink = inkC;
            } else {
                tl = new Lines();
                nt = -1;
            }
        }
        inkC = null;
        if (nt < 0) nt = textLines(ink, aw, ah, an.hc, tl);
        // hướng dòng chữ: thử cả ảnh chuyển vị (cỡ chữ đo theo chiều ngang); hướng đúng cho dòng dài hơn và mượt hơn hẳn
        byte[] inkT = new byte[nA];
        for (int y = 0; y < ah; y++) {
            for (int x = 0; x < aw; x++) inkT[x * ah + y] = ink[y * aw + x];
        }
        ink = null;
        float hcT = an.hcT > 0f ? an.hcT : an.hc;
        Lines tlT = new Lines();
        int ntT = textLines(inkT, ah, aw, hcT, tlT);
        inkT = null;
        boolean rot = lineScore(tlT) > 1.5 * lineScore(tl);
        int strd = rot ? Math.max(4, Math.round(hcT * 1.25f)) : an.stride;
        Lines hl = new Lines(), vl = new Lines();
        int nh;
        if (!rot) {
            hl.append(an.hr, false);
            nh = hl.count;
            hl.append(tl, false);
            hl.pitch = tl.pitch;
            vl.append(an.vr, false);
        } else {
            hl.append(an.vr, true);
            nh = hl.count;
            hl.append(tlT, false);
            hl.pitch = tlT.pitch;
            vl.append(an.hr, true);
        }
        int fw = rot ? ah : aw, fh = rot ? aw : ah;
        int gs = Math.max(8, Math.round(Math.max(aw, ah) / (float) GRID_CELLS));
        int gw = (fw + gs - 1) / gs + 1, gh = (fh + gs - 1) / gs + 1;
        int N = gw * gh;
        double[] dyF = new double[N], dxF = new double[N];
        double mx = fitField(fw, fh, gs, gw, gh, hl, nh, vl, strd, dxF, dyF);
        if (!(mx >= 1.0)) return null;
        Field f = new Field();
        f.step = gs * k;
        f.ox = (k - 1) / 2f;
        if (!rot) {
            f.gw = gw;
            f.gh = gh;
            f.dx = new float[N];
            f.dy = new float[N];
            for (int i = 0; i < N; i++) {
                f.dx[i] = (float) (dxF[i] * k);
                f.dy[i] = (float) (dyF[i] * k);
            }
            f.hrules = nh;
            f.vrules = vl.count;
        } else {
            // chuyển vị trường về toạ độ ảnh: nút (i, j) <- nút (j, i) của ảnh chuyển vị, đổi vai dx / dy
            f.gw = gh;
            f.gh = gw;
            f.dx = new float[N];
            f.dy = new float[N];
            for (int j = 0; j < f.gh; j++) {
                for (int i = 0; i < f.gw; i++) {
                    f.dx[j * f.gw + i] = (float) (dyF[i * gw + j] * k);
                    f.dy[j * f.gw + i] = (float) (dxF[i * gw + j] * k);
                }
            }
            f.hrules = vl.count;
            f.vrules = nh;
        }
        f.lines = rot ? ntT : nt;
        f.maxShift = (float) (mx * k);
        f.rotated = rot;
        return f;
    }

    /**
     * Khớp trường (toạ độ ảnh phân tích fw x fh, lưới gw x gh bước gs) từ đường ngang hl (nh đường kẻ đầu, còn lại dòng
     * chữ) và đường dọc vl. Ghi vào dxF, dyF (đã nhân hệ số thu nhỏ nếu cần để không gập); trả về dịch chuyển lớn nhất,
     * 0 nếu không đủ dữ liệu / khớp không tin cậy.
     */
    static double fitField(int fw, int fh, int gs, int gw, int gh, Lines hl, int nh, Lines vl, int stride,
                           double[] dxF, double[] dyF) {
        if (hl.count < 3) return 0;
        int N = gw * gh;
        // 1) trường dọc từ dòng chữ + đường kẻ ngang (vị trí ngang lấy đúng toạ độ mẫu)
        float[] pu = java.util.Arrays.copyOf(hl.xs, hl.n);
        fitLines(hl, gw, gh, gs, pu, dyF, true);
        // các đoạn chữ cách quãng trên cùng một dòng (vd. "NGƯỜI LẬP ... THỦ TRƯỞNG") -> một dòng, khớp lại
        if (hl.pitch > 0f && mergeLines(hl, nh, 0.3f * hl.pitch, stride)) fitLines(hl, gw, gh, gs, pu, dyF, true);
        // 2) trường ngang từ đường kẻ dọc
        if (vl.count > 0) {
            float[] pv = new float[vl.n];
            for (int i = 0; i < vl.n; i++) pv[i] = outV(dyF, gw, gh, gs, vl.xs[i], vl.ys[i]);
            fitLines(vl, gw, gh, gs, pv, dxF, false);
            // 3) khớp lại trường dọc với vị trí ngang đã hiệu chỉnh
            for (int i = 0; i < hl.n; i++) {
                float u = hl.xs[i];
                for (int it = 0; it < 2; it++) u = hl.xs[i] - (float) interp(dxF, gw, gh, gs, u, hl.cs[hl.line[i]]);
                pu[i] = u;
            }
            fitLines(hl, gw, gh, gs, pu, dyF, true);
        }
        // phần lớn mẫu bị loại: dữ liệu mâu thuẫn (không phải dòng chữ / bảng), không nắn
        if (hl.kept < 0.5f) return 0;
        // vùng xa mọi mẫu (lề trống, vùng không nhận ra dòng): trường tắt dần về 0 thay vì ngoại suy
        fade(hl, vl, gw, gh, gs, dxF, dyF);
        // giới hạn độ lớn; không gập (thu nhỏ trường nếu cần); trang đã phẳng thì bỏ qua
        float lim = 0.08f * Math.max(fw, fh);
        for (int i = 0; i < N; i++) {
            if (dyF[i] > lim) dyF[i] = lim;
            if (dyF[i] < -lim) dyF[i] = -lim;
            if (dxF[i] > lim) dxF[i] = lim;
            if (dxF[i] < -lim) dxF[i] = -lim;
        }
        double scale = 1.0;
        for (int tries = 0; tries < 3 && !foldFree(dxF, dyF, gw, gh, gs, scale); tries++) scale *= 0.5;
        if (!foldFree(dxF, dyF, gw, gh, gs, scale)) return 0;
        double mx = 0;
        for (int i = 0; i < N; i++) {
            dxF[i] *= scale;
            dyF[i] *= scale;
            double a = Math.abs(dyF[i]), b = Math.abs(dxF[i]);
            if (a > mx) mx = a;
            if (b > mx) mx = b;
        }
        return mx;
    }

    /** Số ô lưới quanh mẫu gần nhất mà trường giữ nguyên; xa hơn FADE_END ô thì bằng 0. */
    static final int FADE_START = 3, FADE_END = 8;

    /**
     * Nhân trường với hệ số theo khoảng cách (số ô, 8 hướng) từ nút tới nút gần nhất có mẫu đang được dùng:
     * <= FADE_START: 1; >= FADE_END: 0; ở giữa giảm tuyến tính.
     */
    static void fade(Lines hl, Lines vl, int gw, int gh, int gs, double[] dxF, double[] dyF) {
        int N = gw * gh;
        int[] dist = new int[N];
        int[] queue = new int[N];
        for (int i = 0; i < N; i++) dist[i] = -1;
        int tail = 0;
        for (int pass = 0; pass < 2; pass++) {
            Lines ln = pass == 0 ? hl : vl;
            for (int i = 0; i < ln.n; i++) {
                if (ln.wt[i] <= 0f) continue;
                int gx = (int) Math.floor(ln.xs[i] / gs + 0.5f), gy = (int) Math.floor(ln.ys[i] / gs + 0.5f);
                if (gx < 0) gx = 0;
                if (gx > gw - 1) gx = gw - 1;
                if (gy < 0) gy = 0;
                if (gy > gh - 1) gy = gh - 1;
                int o = gy * gw + gx;
                if (dist[o] < 0) {
                    dist[o] = 0;
                    queue[tail++] = o;
                }
            }
        }
        int head = 0;
        while (head < tail) {
            int i = queue[head++];
            int x = i % gw, y = i / gw;
            for (int dy = -1; dy <= 1; dy++) {
                int yy = y + dy;
                if (yy < 0 || yy >= gh) continue;
                for (int dx = -1; dx <= 1; dx++) {
                    int xx = x + dx;
                    if (xx < 0 || xx >= gw) continue;
                    int j = yy * gw + xx;
                    if (dist[j] >= 0) continue;
                    dist[j] = dist[i] + 1;
                    queue[tail++] = j;
                }
            }
        }
        for (int i = 0; i < N; i++) {
            int dd = dist[i];
            if (dd < 0 || dd >= FADE_END) {
                dxF[i] = 0;
                dyF[i] = 0;
            } else if (dd > FADE_START) {
                double f = (FADE_END - dd) / (double) (FADE_END - FADE_START);
                dxF[i] *= f;
                dyF[i] *= f;
            }
        }
    }

    // ================================================================== hàm phụ: làm sạch, cỡ chữ, dòng chữ

    /** Điểm được giữ: lõi nét / điểm lan tới được (bit 4), hoặc viền hơi tối nằm sát các điểm đó. */
    private static boolean keep(byte[] lq, byte[] cls, byte[] near, int i) {
        if ((cls[i] & 16) != 0) return true;
        return near[i] != 0 && (lq[i] & 255) < WEAK_Q;
    }

    private static int idx(float v, int scale, int n) {
        int i = (int) (v * scale);
        if (i < 0) return 0;
        if (i >= n) return n - 1;
        return i;
    }

    /**
     * Độ đậm phổ biến nhất (0..255) của tâm nét chữ trên trang: mốt của các điểm cực tiểu địa phương tối hơn 0,62
     * (làm mượt +-5 mức); -1 nếu quá ít (trang trắng / ảnh).
     */
    static int inkMode(byte[] lq, int w, int h) {
        int[] hist = new int[256];
        int tot = 0;
        for (int y = 1; y < h - 1; y++) {
            int off = y * w;
            for (int x = 1; x < w - 1; x++) {
                int i = off + x;
                int q = lq[i] & 255;
                if (q >= MODE_LIMIT_Q) continue;
                if (q > (lq[i - 1] & 255) || q > (lq[i + 1] & 255) || q > (lq[i - w] & 255) || q > (lq[i + w] & 255)
                        || q > (lq[i - w - 1] & 255) || q > (lq[i - w + 1] & 255) || q > (lq[i + w - 1] & 255)
                        || q > (lq[i + w + 1] & 255)) continue;
                hist[q]++;
                tot++;
            }
        }
        if (tot < 200) return -1;
        int best = -1, bi = 0;
        for (int i = 0; i < MODE_LIMIT_Q; i++) {
            int s = 0;
            for (int j = i - 5; j <= i + 5; j++) if (j >= 0 && j < MODE_LIMIT_Q) s += hist[j];
            if (s > best) {
                best = s;
                bi = i;
            }
        }
        return bi;
    }

    /**
     * Cụm nét nhạt có dạng chữ trên lưới ô d x d: thành phần liên thông 8 hướng của các ô có điểm tối hơn tc (ngưỡng hơi
     * lỏng hơn ngưỡng lõi, để vệt nếp gấp đứt quãng nối liền lại) hoặc mực màu. Cụm được tính nếu gọn (cạnh khung <=
     * maxDim điểm ảnh, cỡ chữ / từ) và không phải một vạch mảnh, thẳng, dài hơn longLine điểm ảnh (mảnh vệt nếp gấp).
     * Trả về mảng ô (1 = thuộc cụm dạng chữ).
     */
    static byte[] clusters(byte[] lq, byte[] cls, int w, int h, int tc, int d, int maxDim, float longLine) {
        int bw = (w + d - 1) / d, bh = (h + d - 1) / d;
        int nb = bw * bh;
        byte[] blk = new byte[nb];
        for (int y = 0; y < h; y++) {
            int off = y * w, boff = (y / d) * bw;
            for (int x = 0; x < w; x++) {
                if ((lq[off + x] & 255) < tc || (cls[off + x] & 1) != 0) blk[boff + x / d] = 1;
            }
        }
        byte[] keepB = new byte[nb];
        byte[] seen = new byte[nb];
        int[] queue = new int[nb];
        for (int start = 0; start < nb; start++) {
            if (blk[start] == 0 || seen[start] != 0) continue;
            int head = 0, tail = 0;
            queue[tail++] = start;
            seen[start] = 1;
            int minX = bw, maxX = -1, minY = bh, maxY = -1;
            long sx = 0, sy = 0, sxx = 0, syy = 0, sxy = 0;
            while (head < tail) {
                int i = queue[head++];
                int x = i % bw, y = i / bw;
                if (x < minX) minX = x;
                if (x > maxX) maxX = x;
                if (y < minY) minY = y;
                if (y > maxY) maxY = y;
                sx += x;
                sy += y;
                sxx += (long) x * x;
                syy += (long) y * y;
                sxy += (long) x * y;
                for (int dy = -1; dy <= 1; dy++) {
                    int yy = y + dy;
                    if (yy < 0 || yy >= bh) continue;
                    for (int dx = -1; dx <= 1; dx++) {
                        int xx = x + dx;
                        if (xx < 0 || xx >= bw) continue;
                        int j = yy * bw + xx;
                        if (blk[j] != 0 && seen[j] == 0) {
                            seen[j] = 1;
                            queue[tail++] = j;
                        }
                    }
                }
            }
            int span = Math.max(maxX - minX + 1, maxY - minY + 1);
            if (span * d > maxDim) continue;
            if (span * d > longLine) {
                // vạch mảnh thẳng: phương sai theo hướng hẹp rất nhỏ so với hướng dài (tỷ số trị riêng > 30)
                double nq = tail;
                double mx = sx / nq, my = sy / nq;
                double cxx = sxx / nq - mx * mx, cyy = syy / nq - my * my, cxy = sxy / nq - mx * my;
                double tr = cxx + cyy, det = cxx * cyy - cxy * cxy;
                double disc = Math.sqrt(Math.max(0.0, tr * tr * 0.25 - det));
                double l1 = tr * 0.5 + disc, l2 = tr * 0.5 - disc;
                if (l1 > 30.0 * Math.max(l2, 0.0833)) continue;
            }
            for (int q = 0; q < tail; q++) keepB[queue[q]] = 1;
        }
        return keepB;
    }

    /**
     * Đưa trở lại inkC các thành phần liên thông (8 hướng) của mực chỉ có màu (ink và không inkC) có khung không vượt big
     * theo ít nhất một chiều (chữ viết bút màu, nét ký); chỉ khối lớn theo cả hai chiều (con dấu) bị bỏ. Trả về true nếu
     * còn khối bị bỏ.
     */
    static boolean keepSmallColor(byte[] ink, byte[] inkC, int w, int h, float big) {
        int n = w * h;
        byte[] seen = new byte[n];
        int[] queue = new int[n];
        boolean any = false;
        for (int start = 0; start < n; start++) {
            if (ink[start] == 0 || inkC[start] != 0 || seen[start] != 0) continue;
            int head = 0, tail = 0;
            queue[tail++] = start;
            seen[start] = 1;
            int x0 = w, x1 = -1, y0 = h, y1 = -1;
            while (head < tail) {
                int i = queue[head++];
                int x = i % w, y = i / w;
                if (x < x0) x0 = x;
                if (x > x1) x1 = x;
                if (y < y0) y0 = y;
                if (y > y1) y1 = y;
                for (int dy = -1; dy <= 1; dy++) {
                    int yy = y + dy;
                    if (yy < 0 || yy >= h) continue;
                    for (int dx = -1; dx <= 1; dx++) {
                        int xx = x + dx;
                        if (xx < 0 || xx >= w) continue;
                        int j = yy * w + xx;
                        if (ink[j] != 0 && inkC[j] == 0 && seen[j] == 0) {
                            seen[j] = 1;
                            queue[tail++] = j;
                        }
                    }
                }
            }
            if (x1 - x0 + 1 > big && y1 - y0 + 1 > big) {
                any = true;
                continue;
            }
            for (int q = 0; q < tail; q++) inkC[queue[q]] = 1;
        }
        return any;
    }

    /**
     * Ô thuộc vạch mảnh, thẳng, dài hơn longLine điểm ảnh (thành phần liên thông 8 hướng của các ô blk; tỷ số trị riêng >
     * 30, như clusters). Trả về mảng ô (1 = thuộc vạch).
     */
    static byte[] lineBlocks(byte[] blk, int bw, int bh, int d, float longLine) {
        int nb = bw * bh;
        byte[] out = new byte[nb];
        byte[] seen = new byte[nb];
        int[] queue = new int[nb];
        for (int start = 0; start < nb; start++) {
            if (blk[start] == 0 || seen[start] != 0) continue;
            int head = 0, tail = 0;
            queue[tail++] = start;
            seen[start] = 1;
            int minX = bw, maxX = -1, minY = bh, maxY = -1;
            long sx = 0, sy = 0, sxx = 0, syy = 0, sxy = 0;
            while (head < tail) {
                int i = queue[head++];
                int x = i % bw, y = i / bw;
                if (x < minX) minX = x;
                if (x > maxX) maxX = x;
                if (y < minY) minY = y;
                if (y > maxY) maxY = y;
                sx += x;
                sy += y;
                sxx += (long) x * x;
                syy += (long) y * y;
                sxy += (long) x * y;
                for (int dy = -1; dy <= 1; dy++) {
                    int yy = y + dy;
                    if (yy < 0 || yy >= bh) continue;
                    for (int dx = -1; dx <= 1; dx++) {
                        int xx = x + dx;
                        if (xx < 0 || xx >= bw) continue;
                        int j = yy * bw + xx;
                        if (blk[j] != 0 && seen[j] == 0) {
                            seen[j] = 1;
                            queue[tail++] = j;
                        }
                    }
                }
            }
            int span = Math.max(maxX - minX + 1, maxY - minY + 1);
            if (span * d <= longLine) continue;
            double nq = tail;
            double mx = sx / nq, my = sy / nq;
            double cxx = sxx / nq - mx * mx, cyy = syy / nq - my * my, cxy = sxy / nq - mx * my;
            double tr = cxx + cyy, det = cxx * cyy - cxy * cxy;
            double disc = Math.sqrt(Math.max(0.0, tr * tr * 0.25 - det));
            double l1 = tr * 0.5 + disc, l2 = tr * 0.5 - disc;
            if (l1 <= 30.0 * Math.max(l2, 0.0833)) continue;
            for (int q = 0; q < tail; q++) out[queue[q]] = 1;
        }
        return out;
    }

    /**
     * Lan 8 hướng, tối đa lim bước, từ các ô có mực màu rõ (col = 2) qua các ô hơi có màu (col = 1). Trả về mảng ô
     * (khác 0 = tới được: số bước + 1, tối đa 255), null nếu không có ô mực màu rõ nào.
     */
    static byte[] colorReach(byte[] col, int bw, int bh, int lim) {
        int nb = bw * bh;
        int[] queue = null;
        byte[] out = null;
        int tail = 0;
        for (int i = 0; i < nb; i++) {
            if (col[i] != 2) continue;
            if (out == null) {
                out = new byte[nb];
                queue = new int[nb];
            }
            out[i] = 1;
            queue[tail++] = i;
        }
        if (out == null) return null;
        int head = 0;
        while (head < tail) {
            int i = queue[head++];
            int dd = out[i] & 255;
            if (dd > lim) continue;
            int x = i % bw, y = i / bw;
            for (int dy = -1; dy <= 1; dy++) {
                int yy = y + dy;
                if (yy < 0 || yy >= bh) continue;
                for (int dx = -1; dx <= 1; dx++) {
                    int xx = x + dx;
                    if (xx < 0 || xx >= bw) continue;
                    int j = yy * bw + xx;
                    if (col[j] == 0 || out[j] != 0) continue;
                    out[j] = (byte) Math.min(255, dd + 1);
                    queue[tail++] = j;
                }
            }
        }
        return out;
    }

    /**
     * Lan qua các ô có điểm hơi tối (bs >= 1), 8 hướng: từ ô lõi đậm (bs = 3) tối đa lim bước, từ ô lõi nhạt (bs = 2)
     * tối đa limFaint bước. Trả về mảng ô (1 = tới được).
     */
    static byte[] grow(byte[] bs, int bw, int bh, int lim, int limFaint) {
        int nb = bw * bh;
        byte[] out = new byte[nb];
        byte[] dist = new byte[nb];
        int[] queue = new int[nb];
        for (int pass = 0; pass < 2; pass++) {
            int src = pass == 0 ? 3 : 2;
            int lm = pass == 0 ? lim : limFaint;
            int head = 0, tail = 0;
            for (int i = 0; i < nb; i++) {
                dist[i] = 0;
                if (bs[i] == src) {
                    dist[i] = 1;
                    queue[tail++] = i;
                }
            }
            while (head < tail) {
                int i = queue[head++];
                out[i] = 1;
                int dd = dist[i] & 255;
                if (dd > lm) continue;
                int x = i % bw, y = i / bw;
                for (int dy = -1; dy <= 1; dy++) {
                    int yy = y + dy;
                    if (yy < 0 || yy >= bh) continue;
                    for (int dx = -1; dx <= 1; dx++) {
                        int xx = x + dx;
                        if (xx < 0 || xx >= bw) continue;
                        int j = yy * bw + xx;
                        if (bs[j] == 0 || dist[j] != 0) continue;
                        dist[j] = (byte) (dd + 1);
                        queue[tail++] = j;
                    }
                }
            }
        }
        return out;
    }

    /**
     * Chiều cao điển hình của ký tự (phân vị 60% chiều cao khung thành phần cỡ chữ); 0 nếu quá ít chữ. widthOut[0]: bề
     * rộng điển hình theo cùng cách (cỡ chữ khi trang xoay ngang), 0 nếu quá ít.
     */
    static float textHeight(byte[] ink, int w, int h, int[] lab, int[] queue, float[] widthOut) {
        int n = w * h;
        int[] hist = new int[h + 1];
        int[] histW = new int[w + 1];
        int cnt = 0, cntW = 0;
        for (int i = 0; i < n; i++) lab[i] = 0;
        for (int start = 0; start < n; start++) {
            if (ink[start] == 0 || lab[start] != 0) continue;
            int head = 0, tail = 0;
            queue[tail++] = start;
            lab[start] = 1;
            int minX = w, maxX = -1, minY = h, maxY = -1;
            while (head < tail) {
                int i = queue[head++];
                int x = i % w, y = i / w;
                if (x < minX) minX = x;
                if (x > maxX) maxX = x;
                if (y < minY) minY = y;
                if (y > maxY) maxY = y;
                for (int dy = -1; dy <= 1; dy++) {
                    int yy = y + dy;
                    if (yy < 0 || yy >= h) continue;
                    for (int dx = -1; dx <= 1; dx++) {
                        int xx = x + dx;
                        if (xx < 0 || xx >= w) continue;
                        int j = yy * w + xx;
                        if (ink[j] != 0 && lab[j] == 0) {
                            lab[j] = 1;
                            queue[tail++] = j;
                        }
                    }
                }
            }
            int ch = maxY - minY + 1, cw = maxX - minX + 1;
            if (tail >= 6 && ch >= 4 && ch <= h / 10 && cw <= 4 * ch) {
                hist[ch]++;
                cnt++;
            }
            if (tail >= 6 && cw >= 4 && cw <= w / 10 && ch <= 4 * cw) {
                histW[cw]++;
                cntW++;
            }
        }
        widthOut[0] = 0f;
        if (cntW >= 25) {
            int needW = (cntW * 6 + 9) / 10, accW = 0;
            for (int v = 0; v <= w; v++) {
                accW += histW[v];
                if (accW >= needW) {
                    widthOut[0] = v;
                    break;
                }
            }
        }
        if (cnt < 25) return 0f;
        int need = (cnt * 6 + 9) / 10, acc = 0;
        for (int v = 0; v <= h; v++) {
            acc += hist[v];
            if (acc >= need) return v;
        }
        return 0f;
    }

    /** Độ dài đoạn mực liên tục theo hàng (hr) và theo cột (vr) chứa mỗi điểm mực. */
    static void runLengths(byte[] ink, int w, int h, int[] hr, int[] vr) {
        for (int y = 0; y < h; y++) {
            int off = y * w;
            int x = 0;
            while (x < w) {
                if (ink[off + x] == 0) { x++; continue; }
                int s = x;
                while (x < w && ink[off + x] != 0) x++;
                for (int q = s; q < x; q++) hr[off + q] = x - s;
            }
        }
        for (int x = 0; x < w; x++) {
            int y = 0;
            while (y < h) {
                if (ink[y * w + x] == 0) { y++; continue; }
                int s = y;
                while (y < h && ink[y * w + x] != 0) y++;
                for (int q = s; q < y; q++) vr[q * w + x] = y - s;
            }
        }
    }

    /** Sắp xếp chèn (mảng nhỏ). */
    static void sortSmall(float[] a, int n) {
        for (int i = 1; i < n; i++) {
            float v = a[i];
            int j = i - 1;
            while (j >= 0 && a[j] > v) {
                a[j + 1] = a[j];
                j--;
            }
            a[j + 1] = v;
        }
    }

    /**
     * Dòng chữ: chia trang thành các dải dọc hẹp, tìm đỉnh của lược đồ chiếu ngang trong mỗi dải (tâm dòng chữ),
     * rồi nối đỉnh của các dải liền kề thành đường (dự đoán theo độ dốc). Trả về số dòng chữ tìm được.
     */
    static int textLines(byte[] ink, int w, int h, float hc, Lines out) {
        int sw = Math.max(8, Math.min(48, Math.round(2.5f * hc)));
        int stride = Math.max(4, sw / 2);
        int ns = (w - sw) / stride + 1;
        if (ns < 3) return 0;
        int m = Math.max(1, Math.round(hc / 3f));
        int nmsR = Math.max(2, Math.round(0.4f * hc));
        int win = 2 * m + 1;
        int minP = Math.max(1, (sw * win * win * 6 + 50) / 100);
        int[] prof = new int[h], t1 = new int[h], t2 = new int[h];
        // đỉnh: (dải, y, độ mạnh)
        int cap = 1024, np = 0;
        int[] pS = new int[cap];
        float[] pY = new float[cap];
        int[] pV = new int[cap];
        int[] pFirst = new int[ns + 1];
        for (int s = 0; s < ns; s++) {
            pFirst[s] = np;
            int x0 = s * stride;
            for (int y = 0; y < h; y++) {
                int c = 0;
                int off = y * w + x0;
                for (int x = 0; x < sw; x++) if (ink[off + x] != 0) c++;
                prof[y] = c;
            }
            boxSum(prof, t1, h, m);
            boxSum(t1, t2, h, m);
            for (int y = 1; y < h - 1; y++) {
                int v = t2[y];
                if (v < minP || v <= t2[y - 1] || v < t2[y + 1]) continue;
                boolean top = true;
                for (int q = Math.max(0, y - nmsR); q <= Math.min(h - 1, y + nmsR) && top; q++) {
                    if (t2[q] > v || (t2[q] == v && q < y)) top = false;
                }
                if (!top) continue;
                // vị trí dòng = mép dưới của dải chữ (lược đồ giảm còn nửa đỉnh, gần chân chữ): ổn định hơn vị trí
                // cực đại với chữ to (nét ngang / dấu thanh tạo nhiều đỉnh phụ); không tới được nửa đỉnh thì lấy đỉnh
                float cy = y;
                int half = v / 2;
                for (int q = y + 1; q < h; q++) {
                    if (t2[q] > t2[q - 1]) break;
                    if (t2[q] <= half) {
                        cy = q - 1 + (t2[q - 1] - half) / (float) (t2[q - 1] - t2[q]);
                        break;
                    }
                }
                if (np == cap) {
                    cap *= 2;
                    pS = java.util.Arrays.copyOf(pS, cap);
                    pY = java.util.Arrays.copyOf(pY, cap);
                    pV = java.util.Arrays.copyOf(pV, cap);
                }
                pS[np] = s;
                pY[np] = cy;
                pV[np] = v;
                np++;
            }
        }
        pFirst[ns] = np;
        // khoảng cách dòng: trung vị khoảng cách giữa hai đỉnh kề nhau trong cùng dải
        int[] dh = new int[h + 1];
        int dc = 0;
        for (int i = 0; i + 1 < np; i++) {
            if (pS[i + 1] != pS[i]) continue;
            float d = pY[i + 1] - pY[i];
            if (d < hc || d > 5f * hc) continue;
            dh[(int) (d + 0.5f)]++;
            dc++;
        }
        float pitch = 1.8f * hc;
        if (dc >= 10) {
            int acc = 0;
            for (int v = 0; v <= h; v++) {
                acc += dh[v];
                if (acc * 2 >= dc) { pitch = v; break; }
            }
        }
        out.pitch = pitch;
        // dung sai nối đỉnh: nhỏ hơn khoảng cách tới hàng dấu thanh của chữ to (tránh nối nhầm zíc zắc)
        float tol = Math.min(0.35f * pitch, 0.7f * hc);
        // nối đỉnh thành dòng: dòng đang mở = (x cuối, y cuối, độ dốc, dải cuối)
        int[] act = new int[np + 1];
        float[] ax = new float[np + 1], ay = new float[np + 1], asl = new float[np + 1];
        int[] aLast = new int[np + 1];
        int[] aLine = new int[np + 1];
        int[] aCnt = new int[np + 1];
        int na = 0;
        // mẫu tạm của từng dòng (theo chỉ số dòng tạm), chỉ đưa vào out nếu đủ dài
        int[] sLine = new int[np];
        float[] sX = new float[np], sY = new float[np];
        int nsamp = 0;
        int nTmp = 0;
        long[] keys = new long[64];
        boolean[] usedA = new boolean[np + 1];
        boolean[] usedP = new boolean[np + 1];
        for (int s = 0; s < ns; s++) {
            float xc = s * stride + (sw - 1) / 2f;
            int p0 = pFirst[s], p1 = pFirst[s + 1];
            // ghép cặp (dòng đang mở, đỉnh) theo sai lệch dự đoán tăng dần
            int nk = 0;
            for (int a = 0; a < na; a++) {
                int li = act[a];
                if (s - aLast[li] > 3) continue;
                float pred = ay[li] + asl[li] * (xc - ax[li]);
                for (int p = p0; p < p1; p++) {
                    float d = Math.abs(pY[p] - pred);
                    if (d > tol) continue;
                    if (nk == keys.length) keys = java.util.Arrays.copyOf(keys, nk * 2);
                    keys[nk++] = ((long) (int) (d * 1024f) << 40) | ((long) a << 20) | (p - p0);
                }
            }
            java.util.Arrays.sort(keys, 0, nk);
            for (int a = 0; a < na; a++) usedA[a] = false;
            for (int p = p0; p < p1; p++) usedP[p - p0] = false;
            for (int q = 0; q < nk; q++) {
                int a = (int) ((keys[q] >> 20) & 0xFFFFF), p = (int) (keys[q] & 0xFFFFF);
                if (usedA[a] || usedP[p]) continue;
                usedA[a] = true;
                usedP[p] = true;
                int li = act[a];
                float y = pY[p0 + p];
                float sl = (y - ay[li]) / (xc - ax[li]);
                if (aCnt[li] > 1) sl = 0.5f * asl[li] + 0.5f * sl;
                if (sl > 0.5f) sl = 0.5f;
                if (sl < -0.5f) sl = -0.5f;
                asl[li] = sl;
                ax[li] = xc;
                ay[li] = y;
                aLast[li] = s;
                aCnt[li]++;
                sLine[nsamp] = aLine[li];
                sX[nsamp] = xc;
                sY[nsamp] = y;
                nsamp++;
            }
            for (int p = p0; p < p1; p++) {
                if (usedP[p - p0]) continue;
                int li = na;
                act[na++] = li;
                ax[li] = xc;
                ay[li] = pY[p];
                asl[li] = 0f;
                aLast[li] = s;
                aCnt[li] = 1;
                aLine[li] = nTmp++;
                sLine[nsamp] = aLine[li];
                sX[nsamp] = xc;
                sY[nsamp] = pY[p];
                nsamp++;
            }
        }
        // giữ dòng có >= 3 mẫu, đánh lại chỉ số
        int[] cnt = new int[nTmp];
        for (int i = 0; i < nsamp; i++) cnt[sLine[i]]++;
        int[] map = new int[nTmp];
        int lines = 0;
        for (int t = 0; t < nTmp; t++) {
            if (cnt[t] >= 3) {
                map[t] = out.newLine();
                lines++;
            } else {
                map[t] = -1;
            }
        }
        for (int i = 0; i < nsamp; i++) {
            int t = map[sLine[i]];
            if (t >= 0) out.add(t, sX[i], sY[i], 1f);
        }
        return lines;
    }

    /**
     * Điểm "giống dòng chữ" của tập đường: số mẫu trung bình mỗi đường chia cho độ gồ ghề (trung bình |sai phân bậc hai|
     * của vị trí dọc theo từng đường, tối thiểu 0,05). Hướng đúng của trang cho đường dài và mượt; 0 nếu không đủ mẫu.
     */
    static double lineScore(Lines ln) {
        if (ln.count == 0 || ln.n == 0) return 0;
        float[] p1 = new float[ln.count], p2 = new float[ln.count];
        int[] seen = new int[ln.count];
        double sum = 0;
        int rc = 0;
        for (int i = 0; i < ln.n; i++) {
            int j = ln.line[i];
            float y = ln.ys[i];
            if (seen[j] >= 2) {
                sum += Math.abs(y - 2f * p1[j] + p2[j]);
                rc++;
            }
            p2[j] = p1[j];
            p1[j] = y;
            seen[j]++;
        }
        if (rc == 0) return 0;
        return ((double) ln.n / ln.count) / Math.max(0.05, sum / rc);
    }

    /** Tổng trượt cửa sổ [i - r, i + r] (ngoài mảng = 0). */
    static void boxSum(int[] a, int[] o, int n, int r) {
        int s = 0;
        for (int i = 0; i <= r && i < n; i++) s += a[i];
        for (int i = 0; i < n; i++) {
            o[i] = s;
            int out = i - r, in = i + r + 1;
            if (out >= 0) s -= a[out];
            if (in < n) s += a[in];
        }
    }

    // ------------------------------------------------------------------ khớp trường trên lưới

    /** Tập mẫu của các đường (dòng chữ / đường kẻ): mẫu i thuộc đường line[i], toạ độ (xs, ys), trọng số wt. */
    static final class Lines {
        int count;
        int n;
        int[] line = new int[256];
        float[] xs = new float[256], ys = new float[256], wt = new float[256];
        /** Vị trí đích (hằng số) của từng đường sau khi khớp. */
        float[] cs;
        /** Khoảng cách dòng chữ (điểm ảnh phân tích). */
        float pitch;
        /** Tỷ lệ trọng số mẫu còn được dùng sau lần khớp gần nhất. */
        float kept = 1f;

        int newLine() {
            return count++;
        }

        void add(int ln, float x, float y, float weight) {
            if (n == line.length) {
                int m = n * 2;
                line = java.util.Arrays.copyOf(line, m);
                xs = java.util.Arrays.copyOf(xs, m);
                ys = java.util.Arrays.copyOf(ys, m);
                wt = java.util.Arrays.copyOf(wt, m);
            }
            line[n] = ln;
            xs[n] = x;
            ys[n] = y;
            wt[n] = weight;
            n++;
        }

        /** Thêm toàn bộ mẫu của o (đánh lại chỉ số đường nối tiếp); swap: đổi x <-> y (ảnh chuyển vị). */
        void append(Lines o, boolean swap) {
            int base = count;
            for (int i = 0; i < o.n; i++) add(base + o.line[i], swap ? o.ys[i] : o.xs[i], swap ? o.xs[i] : o.ys[i], o.wt[i]);
            count += o.count;
        }
    }

    /** Giá trị trường D (lưới gw x gh, bước gs) tại (u, v), nội suy song tuyến tính, ngoài lưới lấy nút biên. */
    static double interp(double[] D, int gw, int gh, float gs, float u, float v) {
        float fu = u / gs, fv = v / gs;
        int i0 = (int) Math.floor(fu), j0 = (int) Math.floor(fv);
        float tu = fu - i0, tv = fv - j0;
        if (i0 < 0) { i0 = 0; tu = 0f; }
        if (i0 > gw - 2) { i0 = gw - 2; tu = 1f; }
        if (j0 < 0) { j0 = 0; tv = 0f; }
        if (j0 > gh - 2) { j0 = gh - 2; tv = 1f; }
        int n = j0 * gw + i0;
        double a = D[n] * (1 - tu) + D[n + 1] * tu;
        double b = D[n + gw] * (1 - tu) + D[n + gw + 1] * tu;
        return a * (1 - tv) + b * tv;
    }

    /** Toạ độ dọc trên ảnh ra của điểm (x, y) ảnh vào theo trường dọc dyF: v + dy(x, v) = y (lặp điểm bất động). */
    static float outV(double[] dyF, int gw, int gh, float gs, float x, float y) {
        float v = y;
        for (int it = 0; it < 3; it++) v = y - (float) interp(dyF, gw, gh, gs, x, v);
        return v;
    }

    /**
     * Khớp trường một chiều D trên lưới cho tập đường lines: với mẫu i của đường j,
     * D(pu, pv) + c[j] = toạ độ quan sát (ys nếu horiz, xs nếu không). Vị trí trên ảnh ra: đường ngang (horiz) có
     * pu = pos[i] (truyền vào), pv = c[j]; đường dọc có pu = c[j], pv = pos[i]. Lặp 3 lần (cập nhật vị trí, loại
     * mẫu lệch). Kết quả: D (đã khởi đầu từ giá trị truyền vào) và lines.cs.
     */
    static void fitLines(Lines ln, int gw, int gh, int gs, float[] pos, double[] D, boolean horiz) {
        int n = ln.n, nl = ln.count;
        float[] obs = horiz ? ln.ys : ln.xs;
        float[] wt = new float[n];
        System.arraycopy(ln.wt, 0, wt, 0, n);
        double[] c = new double[nl];
        float[] pu = new float[n], pv = new float[n];
        // khởi đầu: hằng số của đường = trung bình (quan sát - D)
        meanOffsets(ln, D, gw, gh, gs, pos, obs, wt, c, horiz);
        for (int outer = 0; outer < 3; outer++) {
            for (int i = 0; i < n; i++) {
                if (horiz) {
                    pu[i] = pos[i];
                    pv[i] = (float) c[ln.line[i]];
                } else {
                    pu[i] = (float) c[ln.line[i]];
                    pv[i] = pos[i];
                }
            }
            solve(gw, gh, gs, nl, ln.line, pu, pv, obs, wt, n, D, c);
            if (outer == 2) break;
            // loại mẫu lệch nhiều (dò nhầm, chữ to / hình): ngưỡng theo trung vị độ lệch tuyệt đối
            float[] res = new float[n];
            int m = 0;
            for (int i = 0; i < n; i++) {
                if (wt[i] <= 0f) continue;
                double e = interp(D, gw, gh, gs, pu[i], pv[i]) + c[ln.line[i]] - obs[i];
                res[m++] = (float) Math.abs(e);
            }
            if (m == 0) break;
            float[] sorted = java.util.Arrays.copyOf(res, m);
            java.util.Arrays.sort(sorted);
            float med = sorted[m / 2];
            float thr = Math.max(1.0f, 5f * med);
            double[] se = new double[nl];
            int[] cnt = new int[nl];
            for (int i = 0; i < n; i++) {
                if (wt[i] <= 0f) continue;
                double e = interp(D, gw, gh, gs, pu[i], pv[i]) + c[ln.line[i]] - obs[i];
                if (Math.abs(e) > thr) {
                    wt[i] = 0f;
                } else {
                    se[ln.line[i]] += e * e;
                    cnt[ln.line[i]]++;
                }
            }
            // loại cả đường không thể làm thẳng nhất quán (con dấu, chữ ký, hình vẽ, nối nhầm): sai số trung bình lớn
            float[] rms = new float[nl];
            int nr = 0;
            for (int j = 0; j < nl; j++) {
                if (cnt[j] >= 3) rms[nr++] = (float) Math.sqrt(se[j] / cnt[j]);
            }
            if (nr == 0) break;
            java.util.Arrays.sort(rms, 0, nr);
            float lim = Math.max(LINE_LIM, 3f * rms[nr / 2]);
            for (int i = 0; i < n; i++) {
                int j = ln.line[i];
                if (cnt[j] < 3 || Math.sqrt(se[j] / cnt[j]) > lim) wt[i] = 0f;
            }
        }
        ln.cs = new float[nl];
        for (int j = 0; j < nl; j++) ln.cs[j] = (float) c[j];
        double w0 = 0, w1 = 0;
        for (int i = 0; i < n; i++) {
            w0 += ln.wt[i];
            w1 += wt[i];
        }
        ln.kept = w0 > 0 ? (float) (w1 / w0) : 0f;
    }

    static void meanOffsets(Lines ln, double[] D, int gw, int gh, int gs, float[] pos, float[] obs, float[] wt,
                            double[] c, boolean horiz) {
        int nl = ln.count;
        double[] sw = new double[nl];
        for (int j = 0; j < nl; j++) c[j] = 0;
        for (int i = 0; i < ln.n; i++) {
            int j = ln.line[i];
            c[j] += obs[i] * (double) wt[i];
            sw[j] += wt[i];
        }
        for (int j = 0; j < nl; j++) c[j] = sw[j] > 0 ? c[j] / sw[j] : 0;
        // trừ D tại vị trí xấp xỉ
        double[] s2 = new double[nl];
        for (int i = 0; i < ln.n; i++) {
            int j = ln.line[i];
            float u = horiz ? pos[i] : (float) c[j], v = horiz ? (float) c[j] : pos[i];
            s2[j] += (obs[i] - interp(D, gw, gh, gs, u, v)) * wt[i];
        }
        for (int j = 0; j < nl; j++) c[j] = sw[j] > 0 ? s2[j] / sw[j] : c[j];
    }

    /**
     * Gộp các đoạn dòng chữ (chỉ số >= first) nằm trên cùng một dòng nhưng cách quãng xa: vị trí đích sau khi khớp
     * chênh nhau <= tol và không chồng nhau theo chiều ngang (cho phép chồng <= overlap). Trả về true nếu có gộp.
     */
    static boolean mergeLines(Lines ln, int first, float tol, float overlap) {
        int nl = ln.count;
        if (ln.cs == null || nl - first < 2) return false;
        float[] x0 = new float[nl], x1 = new float[nl];
        for (int j = 0; j < nl; j++) {
            x0[j] = Float.MAX_VALUE;
            x1[j] = -Float.MAX_VALUE;
        }
        for (int i = 0; i < ln.n; i++) {
            int j = ln.line[i];
            if (ln.xs[i] < x0[j]) x0[j] = ln.xs[i];
            if (ln.xs[i] > x1[j]) x1[j] = ln.xs[i];
        }
        int[] root = new int[nl];
        for (int j = 0; j < nl; j++) root[j] = j;
        boolean any = false;
        for (int a = first; a < nl; a++) {
            if (x1[a] < x0[a]) continue;
            for (int b = a + 1; b < nl; b++) {
                if (x1[b] < x0[b] || root[a] == root[b]) continue;
                if (Math.abs(ln.cs[a] - ln.cs[b]) > tol) continue;
                int ra = root[a], rb = root[b];
                boolean ok = true;
                for (int p = first; p < nl && ok; p++) {
                    if (root[p] != ra) continue;
                    for (int q = first; q < nl && ok; q++) {
                        if (root[q] != rb) continue;
                        if (Math.abs(ln.cs[p] - ln.cs[q]) > tol) ok = false;
                        else if (Math.min(x1[p], x1[q]) - Math.max(x0[p], x0[q]) > overlap) ok = false;
                    }
                }
                if (!ok) continue;
                for (int q = first; q < nl; q++) if (root[q] == rb) root[q] = ra;
                any = true;
            }
        }
        if (!any) return false;
        // đánh lại chỉ số liên tiếp theo thứ tự xuất hiện
        int[] map = new int[nl];
        for (int j = 0; j < nl; j++) map[j] = -1;
        int m = 0;
        for (int j = 0; j < nl; j++) {
            int r = root[j];
            if (map[r] < 0) map[r] = m++;
        }
        for (int i = 0; i < ln.n; i++) ln.line[i] = map[root[ln.line[i]]];
        ln.count = m;
        return true;
    }

    /**
     * Giải bình phương tối thiểu có điều chuẩn bằng gradient liên hợp, ẩn z = [D (gw*gh nút); c (nl hằng số)]:
     * min sum wt*(B D + c[line] - obs)^2 + LAMBDA_BEND*|uốn D|^2 + LAMBDA_STRETCH*|grad D|^2 + MU*|D|^2.
     */
    static void solve(int gw, int gh, float gs, int nl, int[] line, float[] pu, float[] pv, float[] obs, float[] wt,
                      int n, double[] D, double[] c) {
        int N = gw * gh;
        int Z = N + nl;
        int[] nd = new int[n];
        double[] w00 = new double[n], w10 = new double[n], w01 = new double[n], w11 = new double[n];
        for (int i = 0; i < n; i++) {
            float fu = pu[i] / gs, fv = pv[i] / gs;
            int i0 = (int) Math.floor(fu), j0 = (int) Math.floor(fv);
            float tu = fu - i0, tv = fv - j0;
            if (i0 < 0) { i0 = 0; tu = 0f; }
            if (i0 > gw - 2) { i0 = gw - 2; tu = 1f; }
            if (j0 < 0) { j0 = 0; tv = 0f; }
            if (j0 > gh - 2) { j0 = gh - 2; tv = 1f; }
            nd[i] = j0 * gw + i0;
            w00[i] = (1.0 - tu) * (1.0 - tv);
            w10[i] = tu * (1.0 - tv);
            w01[i] = (1.0 - tu) * tv;
            w11[i] = tu * (double) tv;
        }
        double[] z = new double[Z], b = new double[Z], r = new double[Z], p = new double[Z], q = new double[Z];
        System.arraycopy(D, 0, z, 0, N);
        System.arraycopy(c, 0, z, N, nl);
        for (int i = 0; i < n; i++) {
            if (wt[i] <= 0f) continue;
            double o = obs[i] * (double) wt[i];
            int a = nd[i];
            b[a] += w00[i] * o;
            b[a + 1] += w10[i] * o;
            b[a + gw] += w01[i] * o;
            b[a + gw + 1] += w11[i] * o;
            b[N + line[i]] += o;
        }
        mul(z, q, gw, gh, nl, line, nd, w00, w10, w01, w11, wt, n);
        double rr = 0, bb = 0;
        for (int i = 0; i < Z; i++) {
            r[i] = b[i] - q[i];
            p[i] = r[i];
            rr += r[i] * r[i];
            bb += b[i] * b[i];
        }
        double tol = bb * 1e-14;
        for (int it = 0; it < CG_ITERS && rr > tol; it++) {
            mul(p, q, gw, gh, nl, line, nd, w00, w10, w01, w11, wt, n);
            double pq = 0;
            for (int i = 0; i < Z; i++) pq += p[i] * q[i];
            if (!(pq > 0)) break;
            double alpha = rr / pq;
            double rn = 0;
            for (int i = 0; i < Z; i++) {
                z[i] += alpha * p[i];
                r[i] -= alpha * q[i];
                rn += r[i] * r[i];
            }
            double beta = rn / rr;
            rr = rn;
            for (int i = 0; i < Z; i++) p[i] = r[i] + beta * p[i];
        }
        System.arraycopy(z, 0, D, 0, N);
        System.arraycopy(z, N, c, 0, nl);
    }

    /** q = A z (ma trận chuẩn của bài toán bình phương tối thiểu trong solve). */
    static void mul(double[] z, double[] q, int gw, int gh, int nl, int[] line, int[] nd, double[] w00, double[] w10,
                    double[] w01, double[] w11, float[] wt, int n) {
        int N = gw * gh;
        int Z = N + nl;
        for (int i = 0; i < Z; i++) q[i] = 0;
        for (int i = 0; i < n; i++) {
            if (wt[i] <= 0f) continue;
            int a = nd[i];
            double s = w00[i] * z[a] + w10[i] * z[a + 1] + w01[i] * z[a + gw] + w11[i] * z[a + gw + 1] + z[N + line[i]];
            s *= wt[i];
            q[a] += w00[i] * s;
            q[a + 1] += w10[i] * s;
            q[a + gw] += w01[i] * s;
            q[a + gw + 1] += w11[i] * s;
            q[N + line[i]] += s;
        }
        double lb = LAMBDA_BEND, ls = LAMBDA_STRETCH;
        for (int j = 0; j < gh; j++) {
            int o = j * gw;
            for (int i = 1; i + 1 < gw; i++) {
                double t = (z[o + i - 1] - 2 * z[o + i] + z[o + i + 1]) * lb;
                q[o + i - 1] += t;
                q[o + i] -= 2 * t;
                q[o + i + 1] += t;
            }
            for (int i = 0; i + 1 < gw; i++) {
                double t = (z[o + i + 1] - z[o + i]) * ls;
                q[o + i] -= t;
                q[o + i + 1] += t;
            }
        }
        for (int j = 1; j + 1 < gh; j++) {
            for (int i = 0; i < gw; i++) {
                int o = j * gw + i;
                double t = (z[o - gw] - 2 * z[o] + z[o + gw]) * lb;
                q[o - gw] += t;
                q[o] -= 2 * t;
                q[o + gw] += t;
            }
        }
        for (int j = 0; j + 1 < gh; j++) {
            for (int i = 0; i < gw; i++) {
                int o = j * gw + i;
                double t = (z[o + gw] - z[o]) * ls;
                q[o] -= t;
                q[o + gw] += t;
            }
        }
        for (int j = 0; j + 1 < gh; j++) {
            for (int i = 0; i + 1 < gw; i++) {
                int o = j * gw + i;
                double t = (z[o] - z[o + 1] - z[o + gw] + z[o + gw + 1]) * (2 * lb);
                q[o] += t;
                q[o + 1] -= t;
                q[o + gw] -= t;
                q[o + gw + 1] += t;
            }
        }
        for (int i = 0; i < N; i++) q[i] += MU * z[i];
        for (int j = 0; j < nl; j++) q[N + j] += 1e-9 * z[N + j];
    }

    /** Ánh xạ (u, v) -> (u + s*dx, v + s*dy) không gập: định thức Jacobi trên mọi ô lưới trong [0,5; 2]. */
    static boolean foldFree(double[] dx, double[] dy, int gw, int gh, int gs, double s) {
        for (int j = 0; j + 1 < gh; j++) {
            for (int i = 0; i + 1 < gw; i++) {
                int o = j * gw + i;
                double xu = (dx[o + 1] - dx[o]) * s / gs, xv = (dx[o + gw] - dx[o]) * s / gs;
                double yu = (dy[o + 1] - dy[o]) * s / gs, yv = (dy[o + gw] - dy[o]) * s / gs;
                double det = (1 + xu) * (1 + yv) - xv * yu;
                if (det < 0.5 || det > 2.0) return false;
            }
        }
        return true;
    }

    // ================================================================== nắn ảnh

    /** Nắn ảnh theo trường f (song tuyến tính, điểm lấy mẫu ngoài ảnh = trắng). */
    public static void remap(int[] px, int w, int h, Field f) {
        if (px == null || f == null || f.dx == null || f.dy == null || w < 2 || h < 2 || px.length < w * h) return;
        int gw = f.gw, gh = f.gh;
        if (gw < 2 || gh < 2 || f.dx.length < gw * gh || f.dy.length < gw * gh || !(f.step > 0f)) return;
        int[] src = px.clone();
        float[] rdx = new float[gw], rdy = new float[gw];
        float inv = 1f / f.step;
        for (int v = 0; v < h; v++) {
            float fv = (v - f.ox) * inv;
            int j0 = (int) Math.floor(fv);
            float tv = fv - j0;
            if (j0 < 0) { j0 = 0; tv = 0f; }
            if (j0 > gh - 2) { j0 = gh - 2; tv = 1f; }
            int o0 = j0 * gw, o1 = o0 + gw;
            for (int i = 0; i < gw; i++) {
                rdx[i] = f.dx[o0 + i] * (1f - tv) + f.dx[o1 + i] * tv;
                rdy[i] = f.dy[o0 + i] * (1f - tv) + f.dy[o1 + i] * tv;
            }
            int off = v * w;
            for (int u = 0; u < w; u++) {
                float fu = (u - f.ox) * inv;
                int i0 = (int) Math.floor(fu);
                float tu = fu - i0;
                if (i0 < 0) { i0 = 0; tu = 0f; }
                if (i0 > gw - 2) { i0 = gw - 2; tu = 1f; }
                float sx = u + (rdx[i0] * (1f - tu) + rdx[i0 + 1] * tu);
                float sy = v + (rdy[i0] * (1f - tu) + rdy[i0 + 1] * tu);
                px[off + u] = sample(src, w, h, sx, sy);
            }
        }
    }

    /** Lấy mẫu song tuyến tính (trọng số 8 bit), ngoài ảnh = trắng. */
    static int sample(int[] src, int w, int h, float sx, float sy) {
        if (!(sx > -1f && sy > -1f && sx < w && sy < h)) return 0xFFFFFFFF;
        int x0 = (int) Math.floor(sx), y0 = (int) Math.floor(sy);
        int ax = (int) ((sx - x0) * 256f), ay = (int) ((sy - y0) * 256f);
        if (ax > 256) ax = 256;
        if (ay > 256) ay = 256;
        int c00 = pick(src, w, h, x0, y0), c10 = pick(src, w, h, x0 + 1, y0);
        int c01 = pick(src, w, h, x0, y0 + 1), c11 = pick(src, w, h, x0 + 1, y0 + 1);
        int out = 0xFF000000;
        for (int sh = 16; sh >= 0; sh -= 8) {
            int a = (c00 >> sh) & 255, b = (c10 >> sh) & 255, c = (c01 >> sh) & 255, d = (c11 >> sh) & 255;
            int top = a * (256 - ax) + b * ax, bot = c * (256 - ax) + d * ax;
            int v = (top * (256 - ay) + bot * ay + 32768) >> 16;
            out |= v << sh;
        }
        return out;
    }

    private static int pick(int[] src, int w, int h, int x, int y) {
        if (x < 0 || y < 0 || x >= w || y >= h) return 0xFFFFFFFF;
        return src[y * w + x];
    }
}
