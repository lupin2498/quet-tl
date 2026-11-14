package vn.quettailieu.scanner.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Phân tích bố cục một trang quét (ảnh đã nắn phẳng + các từ do bộ nhận dạng chữ trả về) thành mô hình trang cho Word
 * (WordDoc.Page), giữ nguyên bố cục:
 *  1. độ sáng chuẩn hoá theo nền giấy -> mặt nạ mực; ảnh phân tích thu nhỏ (cạnh dài ~1000) để dò đường kẻ;
 *  2. đường kẻ ngang / dọc dài, mảnh (Flatten.findRules) rồi kiểm lại ở độ phân giải đầy đủ (phủ kín, mảnh, thẳng);
 *  3. khử nghiêng toạ độ theo độ dốc đường kẻ / dòng chữ (không lấy mẫu lại ảnh);
 *  4. lưới bảng từ đường kẻ (phương pháp "lattice": gộp đường thẳng hàng, nhóm các đường chạm nhau, ô lưới không bị
 *     đường kẻ ngăn thì gộp -> ô gộp ngang / dọc, viền từng cạnh);
 *  5. từ -> ô bảng hoặc chữ tự do; gom thành dòng; đo đường chân chữ, cỡ chữ (theo bề rộng với số đo Times New Roman,
 *     theo chiều cao thân chữ khi dòng ngắn), nét đậm (bề dày nét so với cả trang), màu mực;
 *  6. mỗi dòng thành một đoạn đặt đúng chỗ (giãn chữ / giãn dấu cách để từng từ nằm đúng vị trí, tab cho khoảng trống
 *     lớn); dòng đứng cạnh bảng hoặc chồng lên dòng khác thành hộp chữ;
 *  7. mực còn lại không thuộc chữ đã nhận dạng hay đường kẻ (con dấu, chữ ký, hình, chữ viết tay không đọc được) thành
 *     hình cắt từ ảnh trang; đường kẻ rời thành đường vẽ (hoặc gạch chân nếu nằm sát dưới chữ).
 * Java thuần, không phụ thuộc Android. Bản Swift: ios/Sources/DocLayout.swift (port 1-1, CI đối chiếu kết quả).
 */
public final class DocLayout {
    private DocLayout() {}

    /** Một từ do bộ nhận dạng chữ trả về: nội dung + khung trên ảnh trang (điểm ảnh, gốc trên-trái). */
    public static final class Word {
        public String text;
        public float x0, y0, x1, y1;
        /** Chỉ số dòng theo bộ nhận dạng (-1: không biết). */
        public int line = -1;
        /** Độ tin cậy 0..1 do bộ nhận dạng báo (1 nếu không biết); từ dưới MIN_CONF bị bỏ (thành hình nếu có mực). */
        public float conf = 1f;

        public Word() {}

        public Word(String text, float x0, float y0, float x1, float y1, int line) {
            this.text = text;
            this.x0 = x0;
            this.y0 = y0;
            this.x1 = x1;
            this.y1 = y1;
            this.line = line;
        }
    }

    /** Khổ trang Word và cách đặt ảnh trang lên đó (giống PdfWriter): điểm ảnh (x, y) -> (ox + x*scale, oy + y*scale) pt. */
    public static final class Geometry {
        public double pageW, pageH, scale, ox, oy;
    }

    public static Geometry geometry(int w, int h, int sizeMode) {
        Geometry g = new Geometry();
        double iw = Math.max(1, w), ih = Math.max(1, h);
        if (sizeMode == PdfWriter.SIZE_FIT) {
            double k = 842.0 / Math.max(iw, ih);
            g.pageW = Math.max(72.0, iw * k);
            g.pageH = Math.max(72.0, ih * k);
        } else {
            double a = sizeMode == PdfWriter.SIZE_LETTER ? 612.0 : 595.276;
            double b = sizeMode == PdfWriter.SIZE_LETTER ? 792.0 : 841.89;
            if (iw > ih) {
                g.pageW = b;
                g.pageH = a;
            } else {
                g.pageW = a;
                g.pageH = b;
            }
        }
        g.scale = Math.min(g.pageW / iw, g.pageH / ih);
        g.ox = (g.pageW - iw * g.scale) / 2.0;
        g.oy = (g.pageH - ih * g.scale) / 2.0;
        return g;
    }

    // ------------------------------------------------------------------ hằng số

    /** Cạnh dài của ảnh phân tích (dò đường kẻ, hình). */
    static final int ANALYSIS_LONG = 1000;
    /** Ngưỡng mực của đường kẻ trên ảnh phân tích: tối hơn 0,80 nền giấy. */
    static final int RULE_Q = 204;
    /** Dung sai lưới theo cỡ chữ (điểm ảnh đầy đủ): gộp vị trí, nối đoạn đứt, coi là chạm nhau. */
    static final double SNAP_K = 0.40, JOIN_K = 1.0, TOUCH_K = 0.65;
    /** Một cạnh ô lưới được coi là có kẻ khi đường kẻ phủ >= 72% cạnh. */
    static final double EDGE_COVER = 0.72;
    /** Hộp dòng cao 1,125 cỡ chữ: đường chân chữ ở 80% hộp trong cả Word lẫn LibreOffice (Times New Roman). */
    static final double LINE_K = (WordFont.ASCENT - WordFont.DESCENT) / 0.6;
    /** Lề trong tối đa của ô bảng (pt). */
    static final double MAX_CELL_MARGIN = 5.4;
    static final int MAX_PICTURES = 160;
    /** Từ có độ tin cậy thấp hơn mức này coi như không đọc được. */
    static final float MIN_CONF = 0.5f;

    static long rnd(double v) {
        return (long) Math.floor(v + 0.5);
    }

    static double r20(double v) {
        return rnd(v * 20.0) / 20.0;
    }

    /** Giãn chữ dưới độ phân giải hữu ích của Word coi như 0. */
    static double quant(double v) {
        double q = r20(v);
        return Math.abs(q) < 0.075 ? 0.0 : q;
    }

    static double clampD(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    // ------------------------------------------------------------------ cấu trúc làm việc

    /** Từ đang xử lý: khung mực đo trên ảnh + các số đo. */
    static final class W {
        String text;
        int glyphs, idx, line;
        float conf = 1f;
        /** Khung của bộ nhận dạng (ảnh gốc). */
        double ox0, oy0, ox1, oy1;
        /** Khung mực (toạ độ đã khử nghiêng). */
        double x0, y0, x1, y1;
        /** Đường chân chữ (đã khử nghiêng); NaN nếu không đo được. */
        double base = Double.NaN;
        /** Cỡ chữ (em, điểm ảnh) đo theo chiều cao thân chữ; 0 nếu không đo được. */
        double emH;
        /** Bề dày nét (điểm ảnh); 0 nếu không đo được. */
        double stroke;
        /** Độ "sắc" của hình chiếu mực theo cột khi để nguyên (sh0) và khi nắn nghiêng 0,14 / 0,27 (chữ nghiêng). */
        double sh0, sh1, sh2;
        boolean colored, bold, italic, drop;
        int color;
        /** Số điểm mực trong khung mực (không tính đường kẻ); -1: chưa đo. */
        int inkN = -1;
        int table = -1, cell = -1;
        /** Số đo Times New Roman của từ (1/2048 em) theo kiểu đang chọn. */
        int adv, lsb, rsb;
    }

    /** Đường kẻ (điểm ảnh đầy đủ): ngang thì pos = y, a..b = x. */
    static final class Seg {
        boolean h;
        double pos, a, b, t, slope;
        boolean drop;
        double longest;
    }

    static final class TCell {
        int c0, c1, r0, r1;
        double x0, y0, x1, y1;
        double bTop, bLeft, bBottom, bRight;
        final ArrayList<W> words = new ArrayList<>();
        final ArrayList<Row> rows = new ArrayList<>();
    }

    static final class Tbl {
        double[] xs, ys, hT, vT;
        int cols, rows;
        int[] owner;
        final ArrayList<TCell> cells = new ArrayList<>();
        final ArrayList<Seg> extra = new ArrayList<>();
        double x0, y0, x1, y1;
    }

    /** Một khúc của dòng chữ (các từ liền nhau, không cách nhau bằng khoảng trống lớn): có kiểu chữ riêng. */
    static final class Part {
        final ArrayList<W> ws = new ArrayList<>();
        double x0, x1;
        /** Em theo bề rộng / chiều cao (điểm ảnh), cỡ chữ đã chọn (pt). */
        double emW, emH, size;
        int glyphs;
        boolean bold, italic, underline;
        int color;
    }

    /** Một dòng chữ (các từ cùng hàng trong một vùng chứa), chia thành các khúc cách nhau bằng tab. */
    static final class Row {
        final ArrayList<W> ws = new ArrayList<>();
        final ArrayList<Part> parts = new ArrayList<>();
        double x0, x1, y0, y1;
        /** Đường chân chữ (điểm ảnh); cỡ chữ lớn nhất của các khúc (pt). */
        double base, size;
        int glyphs;
    }

    /** Kết quả phân tích một trang; giữ dữ liệu ảnh để cắt hình cho tới khi gọi release(). */
    public static final class Result {
        public WordDoc.Page page = new WordDoc.Page();
        /** Góc nghiêng đã khử (radian) và số bảng / dòng tìm được (để kiểm thử). */
        public double skew;
        public int tables, lines;
        int[] px;
        int w, h, tq;
        byte[] lq, cls;
        Enhancer.Bg bg;
        ArrayList<W> words;

        /**
         * Điểm ảnh ARGB của hình `pic` (kích thước (px1-px0) x (py1-py0)): cắt từ ảnh trang, chuẩn hoá nền về trắng,
         * xoá nét chữ đen của các từ đã nhận dạng nằm trong vùng (chữ đó đã thành chữ Word — không để bóng chữ phía sau).
         */
        public int[] picture(WordDoc.Pic pic) {
            int x0 = pic.px0, y0 = pic.py0, x1 = pic.px1, y1 = pic.py1;
            int pw = Math.max(1, x1 - x0), ph = Math.max(1, y1 - y0);
            int[] out = new int[pw * ph];
            if (px == null) {
                java.util.Arrays.fill(out, 0xFFFFFFFF);
                return out;
            }
            float[] rowR = new float[w], rowG = new float[w], rowB = new float[w];
            for (int y = y0; y < y1; y++) {
                bg.row(y, rowR, rowG, rowB);
                for (int x = x0; x < x1; x++) {
                    int c = px[y * w + x];
                    int r = (int) (((c >> 16) & 255) * 255f / Math.max(1f, rowR[x]) + 0.5f);
                    int g = (int) (((c >> 8) & 255) * 255f / Math.max(1f, rowG[x]) + 0.5f);
                    int b = (int) ((c & 255) * 255f / Math.max(1f, rowB[x]) + 0.5f);
                    if (r > 255) r = 255;
                    if (g > 255) g = 255;
                    if (b > 255) b = 255;
                    out[(y - y0) * pw + (x - x0)] = 0xFF000000 | (r << 16) | (g << 8) | b;
                }
            }
            if (words != null) {
                for (W wd : words) {
                    if (wd.drop) continue;
                    int ax0 = Math.max(x0, (int) Math.floor(wd.ox0) - 2), ay0 = Math.max(y0, (int) Math.floor(wd.oy0) - 2);
                    int ax1 = Math.min(x1, (int) Math.ceil(wd.ox1) + 2), ay1 = Math.min(y1, (int) Math.ceil(wd.oy1) + 2);
                    for (int y = ay0; y < ay1; y++) {
                        for (int x = ax0; x < ax1; x++) {
                            int i = y * w + x;
                            // nét của từ đã thành chữ Word -> trắng: từ mực đen thì chỉ xoá nét đen (giữ mực màu của con
                            // dấu / chữ ký vắt qua), từ mực màu thì xoá mọi nét trong khung
                            if ((lq[i] & 255) < 235 && (wd.colored || (cls[i] & 1) == 0)) out[(y - y0) * pw + (x - x0)] = 0xFFFFFFFF;
                        }
                    }
                }
            }
            return out;
        }

        public void release() {
            px = null;
            lq = null;
            cls = null;
            bg = null;
            words = null;
        }
    }

    /** Trang chỉ gồm ảnh trang (khi không nhận dạng được chữ / phân tích lỗi): một hình phủ kín vùng ảnh. */
    public static WordDoc.Page picturePage(int w, int h, int sizeMode) {
        Geometry g = geometry(w, h, sizeMode);
        WordDoc.Page page = new WordDoc.Page();
        page.width = g.pageW;
        page.height = g.pageH;
        WordDoc.Pic p = new WordDoc.Pic();
        p.x = g.ox;
        p.y = g.oy;
        p.w = w * g.scale;
        p.h = h * g.scale;
        p.px0 = 0;
        p.py0 = 0;
        p.px1 = w;
        p.py1 = h;
        page.pics.add(p);
        return page;
    }

    // ================================================================== phân tích

    /** Trạng thái dùng chung trong một lần phân tích. */
    static final class Scan {
        int[] px;
        int w, h, tq;
        byte[] lq, cls;
        /** Ảnh phân tích: k điểm ảnh gốc mỗi chiều cho một điểm; amin = độ sáng nhỏ nhất của khối. */
        int k, aw, ah;
        byte[] amin;
        /** Chỉ số (1..) đường kẻ ngang / dọc đã nhận tại từng điểm ảnh phân tích (0: không). */
        int[] hId, vId;
        boolean[] hKeep, vKeep;
        /** Cỡ chữ điển hình (điểm ảnh đầy đủ). */
        double hc;
        /** Khử nghiêng: quay quanh tâm trang góc -skew. */
        double cs = 1, sn = 0, cx, cy;

        boolean ink(int x, int y) {
            return (lq[y * w + x] & 255) < tq;
        }

        /** Mực của chữ tại (x, y): không tính đường kẻ đã nhận; noColor: không tính mực màu (nét bút / con dấu vắt qua chữ đen). */
        boolean textInk(int x, int y, boolean noColor) {
            int i = y * w + x;
            if ((lq[i] & 255) >= tq) return false;
            if (noColor && (cls[i] & 1) != 0) return false;
            return !onRule(x, y);
        }

        /** Điểm (x, y) thuộc một đường kẻ đã nhận (theo ảnh phân tích). */
        boolean onRule(int x, int y) {
            int ax = x / k, ay = y / k;
            if (ax >= aw || ay >= ah) return false;
            int i = ay * aw + ax;
            int a = hId[i], b = vId[i];
            return (a > 0 && hKeep[a]) || (b > 0 && vKeep[b]);
        }

        double rx(double x, double y) {
            return cx + (x - cx) * cs + (y - cy) * sn;
        }

        double ry(double x, double y) {
            return cy - (x - cx) * sn + (y - cy) * cs;
        }
    }

    /**
     * Phân tích trang. px: ảnh trang ARGB w x h (ảnh kết quả đã nắn phẳng / lọc); words: các từ đã nhận dạng;
     * sizeMode: khổ giấy (PdfWriter.SIZE_*). Không bao giờ ném lỗi do dữ liệu xấu: tệ nhất trả về trang chỉ có ảnh.
     */
    public static Result analyze(int[] px, int w, int h, List<Word> words, int sizeMode) {
        Result res = new Result();
        Geometry g = geometry(w, h, sizeMode);
        res.page.width = g.pageW;
        res.page.height = g.pageH;
        if (px == null || w < 32 || h < 32 || px.length < w * h) return res;
        Scan s = new Scan();
        s.px = px;
        s.w = w;
        s.h = h;
        s.cx = w / 2.0;
        s.cy = h / 2.0;
        int n = w * h;
        Enhancer.Bg bg = Enhancer.backgroundFine(px, w, h, null);
        s.lq = new byte[n];
        s.cls = new byte[n];
        Flatten.normalize(px, w, h, bg, s.lq, s.cls);
        int mode = Flatten.inkMode(s.lq, w, h);
        s.tq = Flatten.SEED_Q;
        if (mode >= 0) s.tq = Math.max(Flatten.SEED_Q, Math.min(Flatten.SEED_MAX_Q, mode + Flatten.SEED_OFFSET_Q));
        int inkLevel = mode >= 0 ? mode : 60;
        res.px = px;
        res.w = w;
        res.h = h;
        res.tq = s.tq;
        res.lq = s.lq;
        res.cls = s.cls;
        res.bg = bg;

        // ---- các từ: bỏ từ rỗng / khung hỏng
        ArrayList<W> ws = new ArrayList<>();
        if (words != null) {
            int idx = 0;
            for (Word o : words) {
                if (o == null || o.text == null) continue;
                String t = o.text.trim();
                if (t.length() == 0) continue;
                double x0 = Math.min(o.x0, o.x1), x1 = Math.max(o.x0, o.x1), y0 = Math.min(o.y0, o.y1), y1 = Math.max(o.y0, o.y1);
                if (!(x1 - x0 >= 1.0) || !(y1 - y0 >= 2.0)) continue;
                if (x1 <= 0 || y1 <= 0 || x0 >= w || y0 >= h) continue;
                if (o.conf < MIN_CONF) continue;
                W wd = new W();
                wd.text = t;
                wd.glyphs = Math.max(1, WordFont.glyphCount(t));
                wd.ox0 = Math.max(0.0, x0);
                wd.oy0 = Math.max(0.0, y0);
                wd.ox1 = Math.min(w, x1);
                wd.oy1 = Math.min(h, y1);
                wd.line = o.line;
                wd.conf = o.conf;
                wd.idx = idx++;
                ws.add(wd);
            }
        }
        dedupeWords(ws);
        res.words = ws;
        if (ws.isEmpty()) {
            // không nhận dạng được chữ nào: giữ nguyên trang dưới dạng một hình
            WordDoc.Page pp = picturePage(w, h, sizeMode);
            res.page.pics.addAll(pp.pics);
            return res;
        }

        // ---- ảnh phân tích + đường kẻ
        ArrayList<Seg> segs = findSegs(s, ws, inkLevel);

        // ---- đo từng từ trên ảnh gốc
        for (W wd : ws) measureWord(s, wd, inkLevel);
        dropRuleJunk(s, ws, segs, inkLevel);

        // ---- khử nghiêng
        double skew = estimateSkew(s, ws, segs);
        res.skew = skew;
        // StrictMath (fdlibm): kết quả giống hệt trên mọi máy — Math.sin / cos của từng JVM được phép lệch 1 ulp, bản Swift
        // (JavaNum) không thể trùng từng bit với chúng
        s.cs = StrictMath.cos(skew);
        s.sn = StrictMath.sin(skew);
        for (Seg sg : segs) deskewSeg(s, sg);
        for (W wd : ws) deskewWord(s, wd);

        // ---- bảng
        ArrayList<Seg> loose = new ArrayList<>();
        ArrayList<Tbl> tables = detectTables(s, segs, loose);
        // từ -> ô bảng
        for (W wd : ws) {
            if (wd.drop) continue;
            double mx = (wd.x0 + wd.x1) / 2.0, my = (wd.y0 + wd.y1) / 2.0;
            for (int ti = 0; ti < tables.size() && wd.table < 0; ti++) {
                Tbl t = tables.get(ti);
                int ci = cellOfPoint(t, mx, my);
                if (ci >= 0) {
                    wd.table = ti;
                    wd.cell = ci;
                    t.cells.get(ci).words.add(wd);
                }
            }
        }
        // khung không có chữ (quanh một hình...) là hình vẽ, không phải bảng
        for (int ti = tables.size() - 1; ti >= 0; ti--) {
            Tbl t = tables.get(ti);
            if (t.cells.size() != 1 || !t.cells.get(0).words.isEmpty()) continue;
            TCell c = t.cells.get(0);
            if (c.bTop > 0) loose.add(seg(true, c.y0, c.x0, c.x1, c.bTop));
            if (c.bBottom > 0) loose.add(seg(true, c.y1, c.x0, c.x1, c.bBottom));
            if (c.bLeft > 0) loose.add(seg(false, c.x0, c.y0, c.y1, c.bLeft));
            if (c.bRight > 0) loose.add(seg(false, c.x1, c.y0, c.y1, c.bRight));
            tables.remove(ti);
            for (W wd : ws) if (wd.table > ti) wd.table--;
        }
        for (Tbl t : tables) loose.addAll(t.extra);
        res.tables = tables.size();

        // ---- dòng chữ
        ArrayList<W> free = new ArrayList<>();
        for (W wd : ws) if (!wd.drop && wd.table < 0) free.add(wd);
        ArrayList<Row> freeRows = chainRows(free, tables);
        ArrayList<Row> allRows = new ArrayList<>(freeRows);
        for (Tbl t : tables) {
            for (TCell c : t.cells) {
                c.rows.addAll(chainRows(c.words, null));
                allRows.addAll(c.rows);
            }
        }
        res.lines = allRows.size();
        styleRows(allRows, g);
        loose = applyUnderlines(s, freeRows, loose, g);

        // ---- dựng trang
        WordDoc.Page page = res.page;
        ArrayList<WordDoc.Block> tableBlocks = new ArrayList<>();
        for (Tbl t : tables) tableBlocks.add(tableBlock(t, g));
        layoutFree(page, freeRows, tableBlocks, g);
        for (Seg sg : loose) {
            if (sg.drop) continue;
            double len = sg.b - sg.a;
            if (len < (sg.h ? 3.0 : 4.0) * s.hc) continue;
            WordDoc.Rule r = new WordDoc.Rule();
            r.horiz = sg.h;
            r.pos = (sg.h ? g.oy : g.ox) + sg.pos * g.scale;
            r.a = (sg.h ? g.ox : g.oy) + sg.a * g.scale;
            r.b = (sg.h ? g.ox : g.oy) + sg.b * g.scale;
            r.t = Math.max(0.25, sg.t * g.scale);
            page.rules.add(r);
        }
        findPictures(s, ws, page, g);
        return res;
    }

    /**
     * Dọn các khung từ chồng nhau do bộ nhận dạng trả về: hai khung gần trùng nhau (IoU >= 0,5) là một từ bị trả hai lần
     * -> giữ từ tin cậy hơn; một khung quá rộng trùm lên khung của từ bên cạnh thì cắt bớt về phía mà nội dung của nó
     * (theo bề rộng tự nhiên) không với tới; chồng nhau thật sự thì giữ từ tin cậy hơn.
     */
    static void dedupeWords(ArrayList<W> ws) {
        int n = ws.size();
        if (n > 6000) return;
        boolean[] dead = new boolean[n];
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n && !dead[i]; j++) {
                if (dead[j]) continue;
                W a = ws.get(i), b = ws.get(j);
                double ox = Math.min(a.ox1, b.ox1) - Math.max(a.ox0, b.ox0);
                if (ox <= 0) continue;
                double oy = Math.min(a.oy1, b.oy1) - Math.max(a.oy0, b.oy0);
                double ha = a.oy1 - a.oy0, hb = b.oy1 - b.oy0;
                if (oy < 0.5 * Math.min(ha, hb)) continue;
                double wa = a.ox1 - a.ox0, wb = b.ox1 - b.ox0;
                double inter = ox * oy, uni = wa * ha + wb * hb - inter;
                boolean aBetter = a.conf > b.conf || (a.conf == b.conf && a.glyphs >= b.glyphs);
                if (inter >= 0.5 * uni) {
                    if (aBetter) dead[j] = true;
                    else dead[i] = true;
                    continue;
                }
                if (ox < 0.5 * Math.min(wa, wb)) continue;
                // khung rộng hơn (big) trùm lên khung kia (small)
                W big = wa >= wb ? a : b, small = wa >= wb ? b : a;
                double em = (big.oy1 - big.oy0) / 0.95;
                double nat = WordFont.advanceUnits(big.text, WordFont.REGULAR) / WordFont.UPM * em;
                double sc = (small.ox0 + small.ox1) / 2.0;
                if (sc > big.ox0 + 0.9 * nat && small.ox0 - 1.0 > big.ox0 + 2.0) {
                    big.ox1 = Math.min(big.ox1, small.ox0 - 1.0);
                } else if (sc < big.ox1 - 0.9 * nat && small.ox1 + 1.0 < big.ox1 - 2.0) {
                    big.ox0 = Math.max(big.ox0, small.ox1 + 1.0);
                } else if (aBetter) {
                    dead[j] = true;
                } else {
                    dead[i] = true;
                }
            }
        }
        for (int i = n - 1; i >= 0; i--) if (dead[i]) ws.remove(i);
    }

    static Seg seg(boolean h, double pos, double a, double b, double t) {
        Seg sg = new Seg();
        sg.h = h;
        sg.pos = pos;
        sg.a = a;
        sg.b = b;
        sg.t = t;
        sg.longest = b - a;
        return sg;
    }

    // ================================================================== đường kẻ

    /** Ảnh phân tích, cỡ chữ, đường kẻ ngang / dọc đã kiểm ở độ phân giải đầy đủ (toạ độ ảnh gốc). */
    static ArrayList<Seg> findSegs(Scan s, ArrayList<W> ws, int inkLevel) {
        int w = s.w, h = s.h;
        int L = Math.max(w, h);
        int k = Math.max(1, Math.round(L / (float) ANALYSIS_LONG));
        int aw = w / k, ah = h / k;
        s.k = k;
        s.aw = aw;
        s.ah = ah;
        int nA = aw * ah;
        byte[] amin = new byte[nA], strict = new byte[nA], rule = new byte[nA];
        for (int y = 0; y < ah; y++) {
            for (int x = 0; x < aw; x++) {
                int mn = 255;
                for (int dy = 0; dy < k; dy++) {
                    int o = (y * k + dy) * w + x * k;
                    for (int dx = 0; dx < k; dx++) {
                        int q = s.lq[o + dx] & 255;
                        if (q < mn) mn = q;
                    }
                }
                amin[y * aw + x] = (byte) mn;
                if (mn < s.tq) strict[y * aw + x] = 1;
                if (mn < RULE_Q) rule[y * aw + x] = 1;
            }
        }
        s.amin = amin;
        s.hId = new int[nA];
        s.vId = new int[nA];
        ArrayList<Seg> out = new ArrayList<>();
        if (aw < 16 || ah < 16) {
            s.hKeep = new boolean[1];
            s.vKeep = new boolean[1];
            s.hc = wordHeight(ws, 12.0);
            return out;
        }
        int[] lab = new int[nA], queue = new int[nA];
        float[] wOut = new float[1];
        float hcA = Flatten.textHeight(strict, aw, ah, lab, queue, wOut);
        // cỡ chữ: theo thành phần liên thông; trang quá ít chữ thì theo khung của bộ nhận dạng
        double hc = hcA > 0 ? hcA * k : wordHeight(ws, 12.0 * k);
        s.hc = Math.max(4.0, hc);
        float hcU = (float) (s.hc / k);
        int tmax = Math.max(2, Math.round(hcU * 0.35f));
        int[] hrn = new int[nA], vrn = new int[nA];
        Flatten.runLengths(rule, aw, ah, hrn, vrn);
        byte[] th = new byte[nA], tv = new byte[nA];
        for (int i = 0; i < nA; i++) {
            if (rule[i] == 0) continue;
            if (vrn[i] <= tmax || hrn[i] >= 3 * tmax) th[i] = 1;
            if (hrn[i] <= tmax || vrn[i] >= 3 * tmax) tv[i] = 1;
        }
        Flatten.Rules hs = new Flatten.Rules(), vs = new Flatten.Rules();
        Flatten.findRules(th, amin, aw, ah, true, Math.max(6, Math.round(2.5f * hcU)), tmax, lab, queue, s.hId, hs);
        Flatten.findRules(tv, amin, aw, ah, false, Math.max(6, Math.round(2.0f * hcU)), tmax, lab, queue, s.vId, vs);
        s.hKeep = new boolean[hs.n + 1];
        s.vKeep = new boolean[vs.n + 1];
        int maxThick = (tmax + 2) * k;
        for (int r = 0; r < hs.n; r++) {
            Seg sg = refine(s, hs, r, true, maxThick, inkLevel);
            if (sg != null) {
                s.hKeep[r + 1] = true;
                out.add(sg);
            }
        }
        for (int r = 0; r < vs.n; r++) {
            Seg sg = refine(s, vs, r, false, maxThick, inkLevel);
            if (sg != null) {
                s.vKeep[r + 1] = true;
                out.add(sg);
            }
        }
        // vệt tối sát mép ảnh (mép giấy / bóng mép máy quét) không phải đường kẻ
        double edge = 0.012 * L;
        for (int i = out.size() - 1; i >= 0; i--) {
            Seg sg = out.get(i);
            double lim = sg.h ? h : w;
            if (sg.pos < edge || sg.pos > lim - edge) out.remove(i);
        }
        return out;
    }

    /** Chiều cao điển hình của khung từ (trung vị) * 0,6; def nếu không có từ. */
    static double wordHeight(ArrayList<W> ws, double def) {
        int n = ws.size();
        if (n == 0) return def;
        double[] hs = new double[n];
        for (int i = 0; i < n; i++) hs[i] = ws.get(i).oy1 - ws.get(i).oy0;
        java.util.Arrays.sort(hs);
        return Math.max(4.0, hs[n / 2] * 0.6);
    }

    /**
     * Kiểm một đường kẻ của ảnh phân tích ở độ phân giải đầy đủ: dọc theo đường, tại mỗi vị trí tìm nét mực mảnh cắt
     * ngang qua tâm dự đoán. Đường kẻ thật phủ kín gần hết chiều dài bằng nét mảnh (chỗ giao với đường vuông góc là nét
     * dày — vẫn tính là phủ); chuỗi chân chữ dính nhau thì đứt quãng. Trả về null nếu không đạt.
     */
    static Seg refine(Scan s, Flatten.Rules rs, int r, boolean horiz, int maxThick, int inkLevel) {
        int k = s.k, w = s.w, h = s.h;
        int a0 = rs.a0[r], a1 = rs.a1[r];
        float[] cen = rs.cen[r];
        int[] cov = rs.cov[r];
        int len = a1 - a0 + 1;
        int pStart = a0 * k, pEnd = Math.min(horiz ? w : h, (a1 + 1) * k);
        int lim = horiz ? h : w;
        int total = pEnd - pStart;
        if (total < 4) return null;
        double[] centers = new double[total];
        double[] at = new double[total];
        double[] thicks = new double[total];
        double denom = Math.max(40.0, 255.0 - inkLevel);
        int thin = 0, thick = 0, first = -1, last = -1;
        int lastCov = -1;
        for (int p = pStart; p < pEnd; p++) {
            int i = Math.min(len - 1, p / k - a0);
            // chỗ đường kẻ đứt trên ảnh phân tích: lấy tâm của vị trí có mực gần nhất phía trước
            if (cov[i] > 0) lastCov = i;
            int ci = lastCov >= 0 ? lastCov : i;
            if (cov[ci] == 0) continue;
            int c = (int) ((cen[ci] + 0.5f) * k);
            int lo = Math.max(0, c - k - 1), hi = Math.min(lim - 1, c + k + 1);
            int best = 256, bq = -1;
            for (int q = lo; q <= hi; q++) {
                int v = s.lq[horiz ? q * w + p : p * w + q] & 255;
                if (v < best) {
                    best = v;
                    bq = q;
                }
            }
            if (best >= RULE_Q) continue;
            int q0 = bq, q1 = bq;
            while (q0 > 0 && q1 - q0 <= maxThick && (s.lq[horiz ? (q0 - 1) * w + p : p * w + q0 - 1] & 255) < RULE_Q) q0--;
            while (q1 < lim - 1 && q1 - q0 <= maxThick && (s.lq[horiz ? (q1 + 1) * w + p : p * w + q1 + 1] & 255) < RULE_Q) q1++;
            if (first < 0) first = p;
            last = p;
            if (q1 - q0 + 1 > maxThick) {
                thick++;
            } else {
                centers[thin] = (q0 + q1 + 1) / 2.0;
                at[thin] = p;
                // độ dày dưới điểm ảnh: tổng độ tối cắt ngang nét (kể cả hai mép nhoè) chia cho độ tối của mực đặc
                int sum = 0;
                for (int q = Math.max(0, q0 - 1); q <= Math.min(lim - 1, q1 + 1); q++) sum += 255 - (s.lq[horiz ? q * w + p : p * w + q] & 255);
                thicks[thin] = sum / denom;
                thin++;
            }
        }
        if (first < 0) return null;
        int span = last - first + 1;
        if ((thin + thick) * 100 < span * 80 || thin * 100 < span * 45 || thin < 4) return null;
        // vị trí = trung vị tâm nét sau khi trừ độ dốc (bình phương tối thiểu)
        double sx = 0, sy = 0, sxx = 0, sxy = 0;
        for (int i = 0; i < thin; i++) {
            sx += at[i];
            sy += centers[i];
            sxx += at[i] * at[i];
            sxy += at[i] * centers[i];
        }
        double den = thin * sxx - sx * sx;
        double slope = Math.abs(den) > 1e-9 ? (thin * sxy - sx * sy) / den : 0.0;
        if (Math.abs(slope) > 0.12) return null;
        double mid = (first + last + 1) / 2.0;
        double[] res = new double[thin];
        for (int i = 0; i < thin; i++) res[i] = centers[i] - slope * (at[i] - mid);
        java.util.Arrays.sort(res);
        // đường kẻ thật thì thẳng: 80% tâm nét nằm trong dải hẹp quanh đường khớp (cung tròn của con dấu, nét chữ ký
        // lượn thì lệch nhiều điểm ảnh)
        double spread = res[Math.min(thin - 1, (int) (thin * 0.9))] - res[(int) (thin * 0.1)];
        if (spread > Math.max(2.0, 0.6 * k + 1.0)) return null;
        double[] tk = java.util.Arrays.copyOf(thicks, thin);
        java.util.Arrays.sort(tk);
        Seg sg = new Seg();
        sg.h = horiz;
        sg.pos = res[thin / 2];
        sg.a = first;
        sg.b = last + 1;
        sg.t = Math.max(0.6, tk[thin / 2]);
        sg.slope = slope;
        sg.longest = sg.b - sg.a;
        return sg;
    }

    // ================================================================== đo từ

    static boolean isLower(int cp) {
        return Character.isLowerCase(cp);
    }

    /** Chữ thường chỉ cao bằng thân chữ (không có nét vươn lên / thả xuống), kể cả khi mang dấu tiếng Việt. */
    static boolean xOnly(int base) {
        return "aceimnorsuvwxzơư".indexOf(base) >= 0;
    }

    /** Chữ gốc của một chữ có dấu (theo bảng WordFont): 'ấ' -> 'a'; ký tự ngoài bảng trả về chính nó. */
    static int baseOf(int cp) {
        if (cp < 0x80) return cp;
        int k = WordFont.index(cp);
        if (k >= 0 && k < WordFont.N_ASCII) return k + 0x20;
        if (k >= WordFont.N_ASCII) {
            // các ký tự riêng: ơ ư Ơ Ư đ Đ đứng đầu bảng phụ
            int j = k - WordFont.N_ASCII;
            if (j == 0) return 'ơ';
            if (j == 1) return 'ư';
            if (j == 2) return 'O';
            if (j == 3) return 'U';
            if (j == 4) return 'd';
            if (j == 5) return 'D';
        }
        return cp;
    }

    /** Dấu nằm dưới đường chân chữ (dấu nặng) hoặc chữ có đuôi thả xuống. */
    static boolean hasBelow(String t) {
        int n = t.length();
        for (int i = 0; i < n; ) {
            int cp = t.codePointAt(i);
            i += Character.charCount(cp);
            if ("gjpqyQ,;()[]{}/|_ỵ".indexOf(cp) >= 0) return true;
            if (cp == 0x0323) return true;
            // chữ có dấu nặng dựng sẵn
            if ("ạặậẹệịọộợụựỵẠẶẬẸỆỊỌỘỢỤỰỴçÇ".indexOf(cp) >= 0) return true;
            int b = baseOf(cp);
            if (b == 'g' || b == 'j' || b == 'p' || b == 'q' || b == 'y') return true;
        }
        return false;
    }

    /** 1: mọi chữ cái là chữ thường chỉ cao bằng thân chữ; 2: toàn chữ hoa / chữ số; 0: khác (không đo cỡ theo chiều cao). */
    static int heightKind(String t) {
        int n = t.length();
        int letters = 0, xs = 0, caps = 0;
        for (int i = 0; i < n; ) {
            int cp = t.codePointAt(i);
            i += Character.charCount(cp);
            if (cp >= 0x300 && cp <= 0x36F) continue;
            if (Character.isDigit(cp)) {
                letters++;
                caps++;
                continue;
            }
            if (!Character.isLetter(cp)) {
                // dấu câu thấp (. , - :) không ảnh hưởng dải thân chữ; ký hiệu cao thì bỏ từ này
                if (".,-:–".indexOf(cp) >= 0) continue;
                return 0;
            }
            letters++;
            int b = baseOf(cp);
            if (Character.isUpperCase(cp)) caps++;
            else if (xOnly(b)) xs++;
        }
        if (letters < 2) return 0;
        if (xs == letters) return 1;
        if (caps == letters) return 2;
        return 0;
    }

    /**
     * Đo một từ trên ảnh gốc: khung mực (co / nới khung của bộ nhận dạng theo mực thật), dải thân chữ -> đường chân chữ
     * và cỡ chữ theo chiều cao, bề dày nét (dưới điểm ảnh: tổng độ tối của nét chia cho độ tối của mực đặc), màu mực.
     */
    static void measureWord(Scan s, W wd, int inkLevel) {
        int w = s.w, h = s.h;
        int bx0 = (int) Math.floor(wd.ox0), by0 = (int) Math.floor(wd.oy0);
        int bx1 = Math.min(w, (int) Math.ceil(wd.ox1)), by1 = Math.min(h, (int) Math.ceil(wd.oy1));
        wd.x0 = wd.ox0;
        wd.y0 = wd.oy0;
        wd.x1 = wd.ox1;
        wd.y1 = wd.oy1;
        wd.base = Double.NaN;
        wd.emH = 0;
        wd.stroke = 0;
        wd.sh0 = 0;
        wd.sh1 = 0;
        wd.sh2 = 0;
        wd.colored = false;
        wd.color = 0;
        wd.inkN = 0;
        if (bx1 - bx0 < 1 || by1 - by0 < 2) return;
        int bh = by1 - by0;
        // từ chủ yếu là mực đen mà có lẫn mực màu (nét bút, con dấu vắt qua): chỉ đo trên mực đen
        int allN = 0, colorN = 0;
        for (int y = by0; y < by1; y++) {
            for (int x = bx0; x < bx1; x++) {
                int i = y * w + x;
                if ((s.lq[i] & 255) >= s.tq) continue;
                allN++;
                if ((s.cls[i] & 1) != 0) colorN++;
            }
        }
        boolean nc = colorN > 0 && (allN - colorN) * 10 >= allN * 4;
        int mx = Math.max(2, (int) Math.round(0.30 * bh)), my = Math.max(1, (int) Math.round(0.18 * bh));
        // cx: phạm vi dời mép trái / phải; cột mực lấy rộng hơn thế (để đo được bề rộng khe trắng bên ngoài mép ứng viên)
        int cx = Math.max(2, (int) Math.round(0.45 * bh));
        int rx0 = Math.max(0, bx0 - cx - mx), rx1 = Math.min(w, bx1 + cx + mx);
        int ry0 = Math.max(0, by0 - my), ry1 = Math.min(h, by1 + my);
        // mực theo cột (trong các hàng của khung nhận dạng)
        int[] col = new int[rx1 - rx0];
        for (int y = by0; y < by1; y++) {
            for (int x = rx0; x < rx1; x++) {
                if (s.textInk(x, y, nc)) col[x - rx0]++;
            }
        }
        // Mép trái / phải của khung mực = chỗ bắt đầu / kết thúc một nét mực gần mép khung nhận dạng (trong phạm vi 0,45
        // chiều cao khung): khung của bộ nhận dạng có thể hụt mất một phần chữ đầu / cuối, lấn sang chữ của từ bên cạnh
        // hoặc lệch hẳn đi nửa chữ (ML Kit trên ảnh nhỏ), còn khe trắng giữa các chữ thì luôn có thật trên ảnh. Trong các
        // chỗ bắt đầu / kết thúc nét ở gần, ưu tiên chỗ có khe trắng RỘNG phía ngoài (khe giữa hai từ) hơn chỗ gần nhất
        // nhưng chỉ cách nét kế một khe hẹp (khe giữa hai chữ trong một từ): điểm = khoảng cách - 1,5 x bề rộng khe (khe
        // tính tối đa mx = 0,3 chiều cao khung).
        int tx0 = -1, tx1 = -1;
        double best0 = Double.MAX_VALUE, best1 = Double.MAX_VALUE;
        for (int x = Math.max(rx0, bx0 - cx); x <= bx0 + cx && x < rx1 && x < bx1; x++) {
            if (col[x - rx0] == 0) continue;
            if (x > 0 && (x == rx0 || col[x - 1 - rx0] != 0)) continue;
            int gap = 0;
            while (gap < mx && x - 1 - gap >= rx0 && col[x - 1 - gap - rx0] == 0) gap++;
            if (x - 1 - gap < 0) gap = mx;
            double cost = Math.abs(x - bx0) - 1.5 * gap;
            if (cost < best0) {
                best0 = cost;
                tx0 = x;
            }
        }
        for (int x = Math.min(rx1, bx1 + cx); x >= bx1 - cx && x > rx0 && x > bx0; x--) {
            if (col[x - 1 - rx0] == 0) continue;
            if (x < w && (x == rx1 || col[x - rx0] != 0)) continue;
            int gap = 0;
            while (gap < mx && x + gap < rx1 && col[x + gap - rx0] == 0) gap++;
            if (x + gap >= w) gap = mx;
            double cost = Math.abs(x - bx1) - 1.5 * gap;
            if (cost < best1) {
                best1 = cost;
                tx1 = x;
            }
        }
        if (tx0 < 0) {
            tx0 = bx0;
            while (tx0 < bx1 && col[tx0 - rx0] == 0) tx0++;
        }
        if (tx1 < 0) {
            tx1 = bx1;
            while (tx1 > tx0 && col[tx1 - 1 - rx0] == 0) tx1--;
        }
        if (tx1 <= tx0) return;
        // mực theo hàng (trong các cột của khung mực)
        int[] row = new int[ry1 - ry0];
        for (int y = ry0; y < ry1; y++) {
            int c = 0;
            for (int x = tx0; x < tx1; x++) {
                if (s.textInk(x, y, nc)) c++;
            }
            row[y - ry0] = c;
        }
        int ty0 = by0, ty1 = by1;
        while (ty0 < ty1 && row[ty0 - ry0] == 0) ty0++;
        while (ty1 > ty0 && row[ty1 - 1 - ry0] == 0) ty1--;
        if (ty1 <= ty0) return;
        while (ty0 > ry0 && row[ty0 - 1 - ry0] > 0) ty0--;
        while (ty1 < ry1 && row[ty1 - ry0] > 0) ty1++;
        wd.x0 = tx0;
        wd.x1 = tx1;
        wd.y0 = ty0;
        wd.y1 = ty1;
        // màu mực
        long sr = 0, sg = 0, sb = 0;
        int inkN = 0, colN = 0;
        for (int y = ty0; y < ty1; y++) {
            for (int x = tx0; x < tx1; x++) {
                int i = y * w + x;
                if (!s.textInk(x, y, nc)) continue;
                inkN++;
                if ((s.cls[i] & 1) != 0) {
                    colN++;
                    int c = s.px[i];
                    sr += (c >> 16) & 255;
                    sg += (c >> 8) & 255;
                    sb += c & 255;
                }
            }
        }
        wd.inkN = inkN;
        if (inkN > 0 && colN * 10 >= inkN * 6) {
            wd.colored = true;
            // đậm hơn một chút so với trung bình (mép nét bị nhoè làm nhạt màu)
            int r = (int) (sr / colN * 0.85), g = (int) (sg / colN * 0.85), b = (int) (sb / colN * 0.85);
            wd.color = (r << 16) | (g << 8) | b;
        }
        // dải thân chữ: đoạn hàng liên tục dài nhất có lượng mực >= nửa TRUNG VỊ của các hàng có mực (cho hở một hàng).
        // Không so với hàng nhiều mực nhất: nét ngang của T, E, B, chữ số... làm vài hàng vọt lên, thân chữ thì không.
        int[] srt = new int[ty1 - ty0];
        int ns = 0;
        for (int y = ty0; y < ty1; y++) if (row[y - ry0] > 0) srt[ns++] = row[y - ry0];
        if (ns == 0) return;
        java.util.Arrays.sort(srt, 0, ns);
        int thr = Math.max(1, (int) Math.ceil(0.5 * srt[ns / 2]));
        int bestTop = -1, bestBot = -1, curTop = -1, gap = 0;
        for (int y = ty0; y <= ty1; y++) {
            boolean on = y < ty1 && row[y - ry0] >= thr;
            if (on) {
                if (curTop < 0) curTop = y;
                gap = 0;
            } else if (curTop >= 0) {
                gap++;
                if (gap > 1 || y >= ty1) {
                    int bot = y - gap;
                    if (bestTop < 0 || bot - curTop > bestBot - bestTop) {
                        bestTop = curTop;
                        bestBot = bot;
                    }
                    curTop = -1;
                    gap = 0;
                }
            }
        }
        if (bestTop < 0) return;
        int band = bestBot - bestTop + 1;
        int kind = heightKind(wd.text);
        // cỡ chữ + đường chân chữ theo nội dung: chiều cao khung mực chia cho phạm vi dọc của các ký tự đã nhận dạng
        int[] ve = new int[2];
        int vc = WordFont.verticalUnits(wd.text, ve);
        double expH = (ve[0] - ve[1]) / WordFont.UPM;
        double emT = vc > 0 && expH >= 0.4 ? (ty1 - ty0) / expH : 0.0;
        double emB = kind == 1 ? band / WordFont.X_HEIGHT : (kind == 2 ? band / WordFont.CAP_HEIGHT : 0.0);
        if (emT > 0) {
            wd.emH = emT;
            wd.base = ty1 + ve[1] / WordFont.UPM * emT;
        }
        if (emB > 0) {
            // từ chỉ gồm chữ thường thấp / chữ hoa: dải thân chữ không phụ thuộc việc đọc đúng dấu
            wd.emH = emT > 0 && Math.abs(emT - emB) <= 0.12 * emB ? (emT + emB) / 2.0 : emB;
            wd.base = bestBot + 1;
        }
        // bề dày nét: các đoạn mực ngang trong dải thân chữ; đoạn dài (nét ngang, bụng chữ) không tính
        int maxRun = Math.max(3, (int) (0.45 * band));
        double denom = Math.max(40.0, 255.0 - inkLevel);
        int[] hist = new int[64];
        int cnt = 0;
        for (int y = bestTop; y <= bestBot; y++) {
            int x = tx0;
            while (x < tx1) {
                if (!s.textInk(x, y, nc)) {
                    x++;
                    continue;
                }
                int st = x;
                while (x < tx1 && s.textInk(x, y, nc)) x++;
                int rl = x - st;
                if (rl > maxRun) continue;
                // tổng độ tối của nét, kể cả hai mép nhoè
                int a = Math.max(0, st - 2), b = Math.min(w, x + 2);
                int sum = 0;
                for (int q = a; q < b; q++) sum += 255 - (s.lq[y * w + q] & 255);
                int v = (int) (sum * 4.0 / denom + 0.5);
                if (v > 63) v = 63;
                hist[v]++;
                cnt++;
            }
        }
        if (cnt >= 4) {
            int acc = 0;
            for (int v = 0; v < 64; v++) {
                acc += hist[v];
                if (acc * 2 >= cnt) {
                    wd.stroke = v / 4.0;
                    break;
                }
            }
        }
        // chữ nghiêng: nét sổ của chữ đứng xếp thẳng cột nên hình chiếu theo cột "sắc" (tổng bình phương lớn); chữ
        // nghiêng thì phải nắn nghiêng lại (dời hàng trên sang trái) mới sắc. Đo ở ba độ nghiêng 0 / 0,14 / 0,27.
        int tw = tx1 - tx0, thh = ty1 - ty0;
        if (tw >= 6 && thh >= 8) {
            int pad = (int) (0.27 * thh) + 2;
            int[] c0 = new int[tw + pad], c1 = new int[tw + pad], c2 = new int[tw + pad];
            for (int y = ty0; y < ty1; y++) {
                int up = ty1 - 1 - y;
                int d1 = (int) (up * 0.14 + 0.5), d2 = (int) (up * 0.27 + 0.5);
                for (int x = tx0; x < tx1; x++) {
                    if (!s.textInk(x, y, nc)) continue;
                    int q = x - tx0;
                    c0[q]++;
                    c1[q + pad - d1]++;
                    c2[q + pad - d2]++;
                }
            }
            double a0 = 0, a1 = 0, a2 = 0;
            for (int i = 0; i < tw + pad; i++) {
                a0 += (double) c0[i] * c0[i];
                a1 += (double) c1[i] * c1[i];
                a2 += (double) c2[i] * c2[i];
            }
            wd.sh0 = a0;
            wd.sh1 = a1;
            wd.sh2 = a2;
        }
    }

    static boolean ruleLike(String t) {
        int n = t.length();
        if (n == 0) return false;
        for (int i = 0; i < n; ) {
            int cp = t.codePointAt(i);
            i += Character.charCount(cp);
            if ("|lI1!_-—–[]()/\\.,':;¦=~Li".indexOf(cp) < 0) return false;
        }
        return true;
    }

    /** Chuỗi chỉ gồm ký hiệu kẻ vạch (không chữ, không số): "—¬", "|_", "═"... */
    static boolean onlyStrokes(String t) {
        int n = t.length();
        if (n == 0) return false;
        for (int i = 0; i < n; ) {
            int cp = t.codePointAt(i);
            i += Character.charCount(cp);
            if ("|_-—–¦=~¬¯‾".indexOf(cp) < 0 && !(cp >= 0x2500 && cp <= 0x257F)) return false;
        }
        return true;
    }

    /** Ký tự mà bộ nhận dạng hay đọc ra từ một đường kẻ dọc / ngang. */
    static boolean vRuleChar(int cp) {
        return "|lI1!![]()/\\¦'\"‘’“”`´ijtf:;.,".indexOf(cp) >= 0;
    }

    static boolean hRuleChar(int cp) {
        return "_-—–=~.,".indexOf(cp) >= 0;
    }

    /** Bề rộng mực tự nhiên (1/2048 em, kiểu thường) của chuỗi t. */
    static int natInkUnits(String t) {
        return WordFont.advanceUnits(t, WordFont.REGULAR) - WordFont.lsbUnits(t, WordFont.REGULAR) - WordFont.rsbUnits(t, WordFont.REGULAR);
    }

    /**
     * Độ lệch (theo em) giữa bề rộng mực đo được và bề rộng tự nhiên của chuỗi t, với em suy từ chiều cao khung mực và
     * phạm vi dọc của chính chuỗi đó (mỗi giả thuyết về nội dung tự suy ra cỡ chữ của nó).
     */
    static double widthMisfit(String t, double inkW, double inkH, double wf) {
        int[] ve = new int[2];
        int vc = WordFont.verticalUnits(t, ve);
        double expH = (ve[0] - ve[1]) / WordFont.UPM;
        double em = vc > 0 && expH >= 0.25 ? inkH / expH : inkH / 0.9;
        if (!(em > 0)) return 99.0;
        return Math.abs(inkW / em - natInkUnits(t) / WordFont.UPM * wf);
    }

    /**
     * Phông của trang rộng / hẹp hơn Times New Roman bao nhiêu lần: trung vị (theo số ký tự) của tỷ số giữa bề rộng mực
     * đo được và bề rộng tự nhiên, với em suy từ chiều cao khung mực. 1 nếu trang quá ít chữ.
     */
    static double widthFactor(ArrayList<W> ws) {
        ArrayList<double[]> items = new ArrayList<>();
        int[] ve = new int[2];
        for (W wd : ws) {
            if (wd.drop || wd.glyphs < 3 || wd.inkN <= 0) continue;
            int vc = WordFont.verticalUnits(wd.text, ve);
            double expH = (ve[0] - ve[1]) / WordFont.UPM;
            int nat = natInkUnits(wd.text);
            if (vc == 0 || expH < 0.4 || nat <= 0) continue;
            double em = (wd.y1 - wd.y0) / expH;
            if (!(em > 0)) continue;
            items.add(new double[]{((wd.x1 - wd.x0) / em) / (nat / WordFont.UPM), wd.glyphs});
        }
        return items.size() >= 8 ? clampD(wmedian(items), 0.75, 1.4) : 1.0;
    }

    /** Cỡ chữ điển hình của trang (em, điểm ảnh): trung vị theo số ký tự của em suy từ chiều cao khung mực; 0 nếu ít chữ. */
    static double typicalEm(ArrayList<W> ws) {
        ArrayList<double[]> items = new ArrayList<>();
        int[] ve = new int[2];
        for (W wd : ws) {
            if (wd.drop || wd.glyphs < 3 || wd.inkN <= 0) continue;
            int vc = WordFont.verticalUnits(wd.text, ve);
            double expH = (ve[0] - ve[1]) / WordFont.UPM;
            if (vc == 0 || expH < 0.4) continue;
            double em = (wd.y1 - wd.y0) / expH;
            if (em > 0) items.add(new double[]{em, wd.glyphs});
        }
        return items.size() >= 8 ? wmedian(items) : 0.0;
    }

    /** Có đường kẻ đã nhận chạy qua một đầu của khung từ không. side: 0 trái, 1 phải (đường dọc), 2 đáy (đường ngang). */
    static Seg ruleAtEnd(ArrayList<Seg> segs, W wd, int side) {
        double hgt = wd.oy1 - wd.oy0, wid = wd.ox1 - wd.ox0;
        for (Seg sg : segs) {
            if (sg.drop) continue;
            if (side == 2) {
                if (!sg.h) continue;
                if (Math.min(sg.b, wd.ox1) - Math.max(sg.a, wd.ox0) < 0.5 * wid) continue;
                if (sg.pos >= wd.oy1 - 0.35 * hgt && sg.pos <= wd.oy1 + 0.6 * hgt) return sg;
            } else {
                if (sg.h) continue;
                if (Math.min(sg.b, wd.oy1) - Math.max(sg.a, wd.oy0) < 0.5 * hgt) continue;
                // đường kẻ nằm trong đầu khung hoặc ngay sát ngoài (bộ nhận dạng đặt khung lệch khỏi đường kẻ)
                if (side == 0 && sg.pos >= wd.ox0 - 0.6 * hgt && sg.pos <= wd.ox0 + 0.4 * hgt) return sg;
                if (side == 1 && sg.pos >= wd.ox1 - 0.4 * hgt && sg.pos <= wd.ox1 + 0.6 * hgt) return sg;
            }
        }
        return null;
    }

    /** Số thành phần liên thông (>= 3 điểm) của mực trong khung mực của từ (toạ độ ảnh gốc). */
    static int inkComponents(Scan s, int x0, int y0, int x1, int y1) {
        int bw = x1 - x0, bh = y1 - y0;
        if (bw <= 0 || bh <= 0 || (long) bw * bh > 4000000L) return 0;
        byte[] seen = new byte[bw * bh];
        int[] stack = new int[bw * bh];
        int comps = 0;
        for (int start = 0; start < bw * bh; start++) {
            if (seen[start] != 0) continue;
            int sx = start % bw, sy = start / bw;
            if (!s.ink(x0 + sx, y0 + sy)) continue;
            int top = 0, size = 0;
            stack[top++] = start;
            seen[start] = 1;
            while (top > 0) {
                int i = stack[--top];
                size++;
                int x = i % bw, y = i / bw;
                for (int dy = -1; dy <= 1; dy++) {
                    int yy = y + dy;
                    if (yy < 0 || yy >= bh) continue;
                    for (int dx = -1; dx <= 1; dx++) {
                        int xx = x + dx;
                        if (xx < 0 || xx >= bw) continue;
                        int j = yy * bw + xx;
                        if (seen[j] == 0 && s.ink(x0 + xx, y0 + yy)) {
                            seen[j] = 1;
                            stack[top++] = j;
                        }
                    }
                }
            }
            if (size >= 3) comps++;
        }
        return comps;
    }

    /**
     * Lọc "rác" do bộ nhận dạng đọc nhầm:
     * - đường kẻ nằm gọn trong khung một từ nhiều chữ là nét chữ (chân chữ dính nhau, gạch dưới "____") -> bỏ đường kẻ;
     * - từ một ký tự kiểu "|", "l", "_" trùng với đường kẻ, từ không có điểm mực nào ngoài đường kẻ -> bỏ từ;
     * - ký tự đầu / cuối từ do đường kẻ sát bên bị đọc thành chữ ("|Máy", "2_") -> gọt đi khi bề rộng mực khớp hơn;
     * - nét mực màu liền một mạch bị đọc thành vài chữ (chữ ký, nét bút) -> bỏ từ, để phần mực thành hình.
     */
    static void dropRuleJunk(Scan s, ArrayList<W> ws, ArrayList<Seg> segs, int inkLevel) {
        for (Seg sg : segs) {
            double len = sg.b - sg.a;
            for (W wd : ws) {
                if (wd.drop) continue;
                double x0 = wd.ox0 - 2, y0 = wd.oy0 - 2, x1 = wd.ox1 + 2, y1 = wd.oy1 + 2;
                double lo, hi, across0, across1;
                if (sg.h) {
                    lo = x0;
                    hi = x1;
                    across0 = y0;
                    across1 = y1;
                } else {
                    lo = y0;
                    hi = y1;
                    across0 = x0;
                    across1 = x1;
                }
                if (sg.pos < across0 || sg.pos > across1) continue;
                double ol = Math.min(sg.b, hi) - Math.max(sg.a, lo);
                if (ol <= 0) continue;
                double thinLim = Math.max(3.0 * sg.t, 0.6 * s.hc);
                boolean thinWord = (across1 - across0 - 4) <= thinLim;
                if (ol >= 0.8 * len) {
                    // đường kẻ nằm trong khung từ
                    if (wd.glyphs >= 2 || !thinWord || !ruleLike(wd.text)) {
                        sg.drop = true;
                    } else {
                        wd.drop = true;
                    }
                } else if (ruleLike(wd.text) && thinWord && ol >= 0.6 * (hi - lo - 4)) {
                    // từ mảnh nằm dọc theo một đường kẻ dài hơn nó
                    wd.drop = true;
                } else if (onlyStrokes(wd.text) && ol >= 0.6 * (hi - lo - 4)) {
                    // "từ" chỉ gồm ký hiệu kẻ vạch mà có đường kẻ dài chạy xuyên qua khung: đọc ra từ chỗ các đường kẻ giao nhau
                    wd.drop = true;
                }
            }
        }
        for (int i = segs.size() - 1; i >= 0; i--) if (segs.get(i).drop) segs.remove(i);
        double wf = widthFactor(ws), em0 = typicalEm(ws);
        for (W wd : ws) {
            if (wd.drop) continue;
            // không có điểm mực nào (ngoài đường kẻ) trong khung: bộ nhận dạng "đọc" ra từ đường kẻ / nền giấy
            if (wd.inkN == 0) {
                wd.drop = true;
                continue;
            }
            // gọt ký tự rác ở hai đầu: ký tự kiểu đường kẻ ("|", "_"...) mà bỏ đi thì phần chữ còn lại khớp bề rộng mực
            // hơn hẳn; có đường kẻ chạy sát đầu đó thì xét cả các ký tự mảnh khác (', l, 1...) và chỉ cần khớp hơn một chút
            boolean changed = false;
            for (int pass = 0; pass < 3 && wd.glyphs >= 2; pass++) {
                String t = wd.text;
                double ink = wd.x1 - wd.x0, hgt = wd.y1 - wd.y0;
                int first = t.codePointAt(0), last = t.codePointBefore(t.length());
                String cand = null;
                boolean nearRule = false;
                boolean strong1 = "|_¦".indexOf(first) >= 0, strong2 = "|_¦".indexOf(last) >= 0;
                Seg left = vRuleChar(first) ? ruleAtEnd(segs, wd, 0) : null;
                Seg right = left == null && vRuleChar(last) ? ruleAtEnd(segs, wd, 1) : null;
                boolean sure = false;
                if (left != null || (hRuleChar(first) && ruleAtEnd(segs, wd, 2) != null)) {
                    cand = t.substring(Character.charCount(first)).trim();
                    nearRule = true;
                    // "|" / "¦" ngay tại một đường kẻ dọc: chắc chắn là đường kẻ bị đọc thành chữ
                    sure = left != null && (first == '|' || first == '¦');
                } else if (right != null || (hRuleChar(last) && ruleAtEnd(segs, wd, 2) != null)) {
                    cand = t.substring(0, t.length() - Character.charCount(last)).trim();
                    nearRule = true;
                    sure = right != null && (last == '|' || last == '¦');
                } else if (strong1) {
                    cand = t.substring(Character.charCount(first)).trim();
                } else if (strong2) {
                    cand = t.substring(0, t.length() - Character.charCount(last)).trim();
                }
                if (cand == null || cand.length() == 0) break;
                if (!sure) {
                    double fitFull = widthMisfit(t, ink, hgt, wf), fitCand = widthMisfit(cand, ink, hgt, wf);
                    if (fitCand + (nearRule ? 0.02 : 0.15) >= fitFull) break;
                } else {
                    // khung từ lùi khỏi đường kẻ (và viền nhoè của nó) để mực đường kẻ không tính vào chữ; khung vẫn phải nằm
                    // trong ảnh (từ hẹp hơn 1 điểm ảnh sát mép: ox1 - 1 < 0 làm measureWord đọc ngoài mảng)
                    double pad = 0.5 * (left != null ? left.t : right.t) + 1.0 + 0.12 * s.hc;
                    if (left != null) wd.ox0 = Math.max(0.0, Math.min(Math.max(wd.ox0, left.pos + pad), wd.ox1 - 1.0));
                    else wd.ox1 = Math.min(s.w, Math.max(Math.min(wd.ox1, right.pos - pad), wd.ox0 + 1.0));
                }
                wd.text = cand;
                wd.glyphs = Math.max(1, WordFont.glyphCount(cand));
                changed = true;
                if (sure) measureWord(s, wd, inkLevel);
            }
            if (changed) measureWord(s, wd, inkLevel);
            // hình dạng mực trái hẳn với nội dung đọc được (bề rộng lệch quá 2 lần so với số ký tự ở cỡ chữ suy từ
            // chiều cao): bộ nhận dạng đọc bừa trên hình / nhiều dòng chồng nhau -> bỏ từ, phần mực sẽ thành hình
            if (wd.glyphs >= 2 && wd.inkN > 0) {
                int[] ve = new int[2];
                int vc = WordFont.verticalUnits(wd.text, ve);
                double expH = (ve[0] - ve[1]) / WordFont.UPM;
                int nat = natInkUnits(wd.text);
                if (vc > 0 && expH >= 0.4 && nat > 0) {
                    double em = (wd.y1 - wd.y0) / expH;
                    double ratio = ((wd.x1 - wd.x0) / em) / (nat / WordFont.UPM * wf);
                    // (khung bé tí so với chữ của trang: bộ nhận dạng đặt khung sai chỗ)
                    if (ratio < 0.5 || ratio > 2.0 || (em0 > 0 && em < 0.3 * em0)) {
                        wd.drop = true;
                        continue;
                    }
                }
            }
            // một ký tự mà hình dạng mực khác hẳn ký tự đó (rộng / hẹp hơn 2,5 lần so với tỷ lệ rộng : cao của nó) hoặc to
            // gấp 2,5 lần chữ của trang: bộ nhận dạng đọc một hình thành chữ (vòng con dấu thành "(", nét ký thành "/"...)
            // -> bỏ từ, phần mực thành hình
            if (wd.glyphs == 1 && wd.inkN > 0) {
                int[] ve = new int[2];
                int vc = WordFont.verticalUnits(wd.text, ve);
                double expH = (ve[0] - ve[1]) / WordFont.UPM;
                int nat = natInkUnits(wd.text);
                if (vc > 0 && expH >= 0.25 && nat > 0 && wd.y1 > wd.y0) {
                    double ratio = ((wd.x1 - wd.x0) / (wd.y1 - wd.y0)) / (nat / WordFont.UPM * wf / expH);
                    if (ratio < 0.4 || ratio > 2.5 || (em0 > 0 && (wd.y1 - wd.y0) / expH > 2.5 * em0)) {
                        wd.drop = true;
                        continue;
                    }
                }
            }
            // mực màu liền một mạch mà đọc ra nhiều chữ: chữ ký / nét bút, không phải chữ in
            if (wd.colored && wd.glyphs >= 3) {
                int comps = inkComponents(s, (int) wd.x0, (int) wd.y0, (int) wd.x1, (int) wd.y1);
                if (comps * 2 < wd.glyphs) wd.drop = true;
            }
        }
    }

    // ================================================================== khử nghiêng

    /** Góc nghiêng của trang (radian, dương = nội dung quay theo chiều kim đồng hồ): từ đường kẻ, không có thì từ dòng chữ. */
    static double estimateSkew(Scan s, ArrayList<W> ws, ArrayList<Seg> segs) {
        ArrayList<double[]> votes = new ArrayList<>();   // {góc, trọng số}
        double total = 0;
        for (Seg sg : segs) {
            double len = sg.b - sg.a;
            if (len < 8.0 * s.hc) continue;
            votes.add(new double[]{sg.h ? sg.slope : -sg.slope, len});
            total += len;
        }
        if (total < 0.5 * Math.max(s.w, s.h)) {
            // dòng chữ theo bộ nhận dạng: khớp đường thẳng qua tâm đáy các từ
            votes.clear();
            ArrayList<W> sorted = new ArrayList<>();
            for (W wd : ws) if (!wd.drop && wd.line >= 0) sorted.add(wd);
            Collections.sort(sorted, new Comparator<W>() {
                @Override
                public int compare(W a, W b) {
                    if (a.line != b.line) return a.line < b.line ? -1 : 1;
                    return a.idx < b.idx ? -1 : (a.idx > b.idx ? 1 : 0);
                }
            });
            int i = 0;
            while (i < sorted.size()) {
                int j = i;
                while (j + 1 < sorted.size() && sorted.get(j + 1).line == sorted.get(i).line) j++;
                int cnt = j - i + 1;
                if (cnt >= 4) {
                    double sx = 0, sy = 0, sxx = 0, sxy = 0, minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
                    for (int q = i; q <= j; q++) {
                        W wd = sorted.get(q);
                        double x = (wd.x0 + wd.x1) / 2.0, y = (wd.y0 + wd.y1) / 2.0;
                        sx += x;
                        sy += y;
                        sxx += x * x;
                        sxy += x * y;
                        minX = Math.min(minX, x);
                        maxX = Math.max(maxX, x);
                    }
                    double den = cnt * sxx - sx * sx;
                    if (maxX - minX >= 0.2 * s.w && Math.abs(den) > 1e-9) {
                        votes.add(new double[]{(cnt * sxy - sx * sy) / den, maxX - minX});
                    }
                }
                i = j + 1;
            }
        }
        if (votes.isEmpty()) return 0.0;
        // trung vị có trọng số
        Collections.sort(votes, new Comparator<double[]>() {
            @Override
            public int compare(double[] a, double[] b) {
                return a[0] < b[0] ? -1 : (a[0] > b[0] ? 1 : 0);
            }
        });
        double sum = 0;
        for (double[] v : votes) sum += v[1];
        double acc = 0, slope = 0;
        for (double[] v : votes) {
            acc += v[1];
            if (acc * 2 >= sum) {
                slope = v[0];
                break;
            }
        }
        // nghiêng dưới 0,1 độ coi như thẳng; quá 8 độ thì không tin
        if (Math.abs(slope) < 0.0017 || Math.abs(slope) > 0.14) return 0.0;
        return StrictMath.atan(slope);
    }

    static void deskewSeg(Scan s, Seg sg) {
        if (s.sn == 0) return;
        double mid = (sg.a + sg.b) / 2.0, half = (sg.b - sg.a) / 2.0;
        double x = sg.h ? mid : sg.pos, y = sg.h ? sg.pos : mid;
        double nx = s.rx(x, y), ny = s.ry(x, y);
        if (sg.h) {
            sg.pos = ny;
            sg.a = nx - half;
            sg.b = nx + half;
        } else {
            sg.pos = nx;
            sg.a = ny - half;
            sg.b = ny + half;
        }
    }

    static void deskewWord(Scan s, W wd) {
        if (s.sn == 0) return;
        double mx = (wd.x0 + wd.x1) / 2.0, my = (wd.y0 + wd.y1) / 2.0;
        double hw = (wd.x1 - wd.x0) / 2.0, hh = (wd.y1 - wd.y0) / 2.0;
        double nx = s.rx(mx, my), ny = s.ry(mx, my);
        if (!Double.isNaN(wd.base)) wd.base = s.ry(mx, wd.base);
        wd.x0 = nx - hw;
        wd.x1 = nx + hw;
        wd.y0 = ny - hh;
        wd.y1 = ny + hh;
    }

    // ================================================================== bảng (lattice)

    static final Comparator<Seg> BY_POS = new Comparator<Seg>() {
        @Override
        public int compare(Seg a, Seg b) {
            if (a.pos != b.pos) return a.pos < b.pos ? -1 : 1;
            return a.a < b.a ? -1 : (a.a > b.a ? 1 : 0);
        }
    };
    static final Comparator<Seg> BY_A = new Comparator<Seg>() {
        @Override
        public int compare(Seg a, Seg b) {
            if (a.a != b.a) return a.a < b.a ? -1 : 1;
            return a.pos < b.pos ? -1 : (a.pos > b.pos ? 1 : 0);
        }
    };

    /** Gộp các đường kẻ cùng hướng nằm thẳng hàng thành đoạn dài nhất có thể. */
    static ArrayList<Seg> mergeRules(ArrayList<Seg> rules, double snap, double join, double minLen) {
        ArrayList<Seg> sorted = new ArrayList<>(rules);
        Collections.sort(sorted, BY_POS);
        ArrayList<Seg> out = new ArrayList<>();
        int i = 0, n = sorted.size();
        while (i < n) {
            int j = i;
            double min = sorted.get(i).pos, max = min;
            while (j + 1 < n && sorted.get(j + 1).pos - max <= snap && sorted.get(j + 1).pos - min <= snap * 2.5) {
                j++;
                max = sorted.get(j).pos;
            }
            double sw = 0, sv = 0;
            ArrayList<Seg> items = new ArrayList<>();
            for (int q = i; q <= j; q++) {
                Seg r = sorted.get(q);
                double wt = Math.max(0.5, r.b - r.a);
                sw += wt;
                sv += r.pos * wt;
                items.add(r);
            }
            double center = sv / sw;
            Collections.sort(items, BY_A);
            Seg cur = null;
            for (Seg r : items) {
                if (cur != null && r.a <= cur.b + join) {
                    if (r.b > cur.b) cur.b = r.b;
                    if (r.b - r.a > cur.longest) {
                        cur.longest = r.b - r.a;
                        cur.t = r.t;
                    }
                } else {
                    cur = seg(r.h, center, r.a, r.b, r.t);
                    out.add(cur);
                }
            }
            i = j + 1;
        }
        for (int q = out.size() - 1; q >= 0; q--) if (out.get(q).b - out.get(q).a < minLen) out.remove(q);
        return out;
    }

    static int ufFind(int[] p, int i) {
        while (p[i] != i) {
            p[i] = p[p[i]];
            i = p[i];
        }
        return i;
    }

    static void ufUnion(int[] p, int a, int b) {
        a = ufFind(p, a);
        b = ufFind(p, b);
        if (a != b) p[b] = a;
    }

    /** Gom các giá trị gần nhau (<= tol, cả nhóm không rộng quá 2,5 tol) thành một, lấy trung bình. */
    static double[] uniqueSorted(ArrayList<Double> values, double tol) {
        int n = values.size();
        double[] v = new double[n];
        for (int i = 0; i < n; i++) v[i] = values.get(i);
        java.util.Arrays.sort(v);
        double[] out = new double[n];
        int m = 0, i = 0;
        while (i < n) {
            int j = i;
            double sum = v[i];
            while (j + 1 < n && v[j + 1] - v[j] <= tol && v[j + 1] - v[i] <= tol * 2.5) {
                j++;
                sum += v[j];
            }
            out[m++] = sum / (j - i + 1);
            i = j + 1;
        }
        return java.util.Arrays.copyOf(out, m);
    }

    static boolean near(ArrayList<Double> arr, double v, double tol) {
        for (double x : arr) if (Math.abs(x - v) <= tol) return true;
        return false;
    }

    static boolean nearA(double[] arr, double v, double tol) {
        for (double x : arr) if (Math.abs(x - v) <= tol) return true;
        return false;
    }

    /** Đường kẻ phủ cạnh lưới [lo, hi] tại vị trí pos (>= EDGE_COVER): trả về đoạn phủ nhiều nhất, null nếu không đủ. */
    static Seg cover(ArrayList<Seg> segs, double pos, double lo, double hi, double snap) {
        double len = 0, bestO = 0;
        Seg best = null;
        for (Seg sg : segs) {
            if (Math.abs(sg.pos - pos) > snap * 1.5) continue;
            double o = Math.min(sg.b, hi) - Math.max(sg.a, lo);
            if (o > 0) {
                len += o;
                if (best == null || o > bestO) {
                    bestO = o;
                    best = sg;
                }
            }
        }
        return len >= (hi - lo) * EDGE_COVER ? best : null;
    }

    static double thick(ArrayList<Seg> segs, double pos, double snap) {
        double t = 0;
        for (Seg sg : segs) if (Math.abs(sg.pos - pos) <= snap * 1.5) t = Math.max(t, sg.t);
        return t;
    }

    /** Dựng lưới bảng từ một nhóm đường kẻ chạm nhau; null nếu không thành bảng (không có ô nào kín bốn phía). */
    static Tbl buildTable(ArrayList<Seg> hs, ArrayList<Seg> vs, double snap, double touch) {
        ArrayList<Double> xsRaw = new ArrayList<>(), ysRaw = new ArrayList<>();
        for (Seg v : vs) xsRaw.add(v.pos);
        for (Seg hgt : hs) ysRaw.add(hgt.pos);
        double[] xs0 = uniqueSorted(xsRaw, snap), ys0 = uniqueSorted(ysRaw, snap);
        // đường lưới: vị trí đường kẻ + đầu mút đường kẻ không chạm đường vuông góc nào
        ArrayList<Double> extraX = new ArrayList<>(), extraY = new ArrayList<>();
        for (Seg hgt : hs) {
            if (!nearA(xs0, hgt.a, touch) && !near(extraX, hgt.a, touch)) extraX.add(hgt.a);
            if (!nearA(xs0, hgt.b, touch) && !near(extraX, hgt.b, touch)) extraX.add(hgt.b);
        }
        for (Seg v : vs) {
            if (!nearA(ys0, v.a, touch) && !near(extraY, v.a, touch)) extraY.add(v.a);
            if (!nearA(ys0, v.b, touch) && !near(extraY, v.b, touch)) extraY.add(v.b);
        }
        for (double x : xs0) extraX.add(x);
        for (double y : ys0) extraY.add(y);
        double[] xs = uniqueSorted(extraX, snap), ys = uniqueSorted(extraY, snap);
        int m = xs.length - 1, n = ys.length - 1;
        if (m < 1 || n < 1 || m > 200 || n > 400) return null;
        Seg[][] vEdge = new Seg[m + 1][n], hEdge = new Seg[n + 1][m];
        for (int i = 0; i <= m; i++) for (int j = 0; j < n; j++) vEdge[i][j] = cover(vs, xs[i], ys[j], ys[j + 1], snap);
        for (int j = 0; j <= n; j++) for (int i = 0; i < m; i++) hEdge[j][i] = cover(hs, ys[j], xs[i], xs[i + 1], snap);
        // gộp các ô lưới không bị đường kẻ ngăn
        int[] uf = new int[m * n];
        for (int i = 0; i < m * n; i++) uf[i] = i;
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < m; i++) {
                if (i + 1 < m && vEdge[i + 1][j] == null) ufUnion(uf, j * m + i, j * m + i + 1);
                if (j + 1 < n && hEdge[j + 1][i] == null) ufUnion(uf, j * m + i, (j + 1) * m + i);
            }
        }
        // chia mỗi vùng thành hình chữ nhật (tham lam theo hàng)
        Tbl t = new Tbl();
        t.owner = new int[m * n];
        java.util.Arrays.fill(t.owner, -1);
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < m; i++) {
                if (t.owner[j * m + i] >= 0) continue;
                int region = ufFind(uf, j * m + i);
                int c1 = i;
                while (c1 + 1 < m && t.owner[j * m + c1 + 1] < 0 && ufFind(uf, j * m + c1 + 1) == region && vEdge[c1 + 1][j] == null) c1++;
                int r1 = j;
                while (r1 + 1 < n) {
                    boolean ok = true;
                    for (int q = i; q <= c1 && ok; q++) {
                        if (t.owner[(r1 + 1) * m + q] >= 0 || ufFind(uf, (r1 + 1) * m + q) != region || hEdge[r1 + 1][q] != null) ok = false;
                    }
                    // hình chữ nhật không được bị đường kẻ dọc cắt ở hàng mới
                    for (int q = i + 1; q <= c1 && ok; q++) if (vEdge[q][r1 + 1] != null) ok = false;
                    if (!ok) break;
                    r1++;
                }
                TCell c = new TCell();
                c.c0 = i;
                c.c1 = c1;
                c.r0 = j;
                c.r1 = r1;
                int index = t.cells.size();
                for (int jj = j; jj <= r1; jj++) for (int ii = i; ii <= c1; ii++) t.owner[jj * m + ii] = index;
                t.cells.add(c);
            }
        }
        // viền: một cạnh ô có viền khi mọi cạnh lưới dọc theo nó đều có đường kẻ
        boolean[][] claimedH = new boolean[n + 1][m], claimedV = new boolean[m + 1][n];
        int closed = 0;
        for (TCell c : t.cells) {
            double top = Double.MAX_VALUE, bottom = Double.MAX_VALUE, left = Double.MAX_VALUE, right = Double.MAX_VALUE;
            double topT = 0, bottomT = 0, leftT = 0, rightT = 0;
            for (int i = c.c0; i <= c.c1; i++) {
                if (hEdge[c.r0][i] == null) top = -1;
                else topT = Math.max(topT, hEdge[c.r0][i].t);
                if (hEdge[c.r1 + 1][i] == null) bottom = -1;
                else bottomT = Math.max(bottomT, hEdge[c.r1 + 1][i].t);
            }
            for (int j = c.r0; j <= c.r1; j++) {
                if (vEdge[c.c0][j] == null) left = -1;
                else leftT = Math.max(leftT, vEdge[c.c0][j].t);
                if (vEdge[c.c1 + 1][j] == null) right = -1;
                else rightT = Math.max(rightT, vEdge[c.c1 + 1][j].t);
            }
            c.bTop = top < 0 ? 0 : Math.max(0.5, topT);
            c.bBottom = bottom < 0 ? 0 : Math.max(0.5, bottomT);
            c.bLeft = left < 0 ? 0 : Math.max(0.5, leftT);
            c.bRight = right < 0 ? 0 : Math.max(0.5, rightT);
            if (c.bTop > 0) for (int i = c.c0; i <= c.c1; i++) claimedH[c.r0][i] = true;
            if (c.bBottom > 0) for (int i = c.c0; i <= c.c1; i++) claimedH[c.r1 + 1][i] = true;
            if (c.bLeft > 0) for (int j = c.r0; j <= c.r1; j++) claimedV[c.c0][j] = true;
            if (c.bRight > 0) for (int j = c.r0; j <= c.r1; j++) claimedV[c.c1 + 1][j] = true;
            if (c.bTop > 0 && c.bBottom > 0 && c.bLeft > 0 && c.bRight > 0) closed++;
            c.x0 = xs[c.c0];
            c.x1 = xs[c.c1 + 1];
            c.y0 = ys[c.r0];
            c.y1 = ys[c.r1 + 1];
        }
        if (closed == 0) return null;
        // mẩu đường kẻ không thành viền ô nào: vẽ như đường kẻ rời
        for (int j = 0; j <= n; j++) {
            for (int i = 0; i < m; i++) {
                Seg e = hEdge[j][i];
                if (e != null && !claimedH[j][i]) t.extra.add(seg(true, ys[j], xs[i], xs[i + 1], e.t));
            }
        }
        for (int i = 0; i <= m; i++) {
            for (int j = 0; j < n; j++) {
                Seg e = vEdge[i][j];
                if (e != null && !claimedV[i][j]) t.extra.add(seg(false, xs[i], ys[j], ys[j + 1], e.t));
            }
        }
        t.xs = xs;
        t.ys = ys;
        t.cols = m;
        t.rows = n;
        t.hT = new double[n + 1];
        t.vT = new double[m + 1];
        for (int j = 0; j <= n; j++) t.hT[j] = thick(hs, ys[j], snap);
        for (int i = 0; i <= m; i++) t.vT[i] = thick(vs, xs[i], snap);
        t.x0 = xs[0];
        t.x1 = xs[m];
        t.y0 = ys[0];
        t.y1 = ys[n];
        return t;
    }

    /** Tìm các bảng trong tập đường kẻ; đường kẻ không thuộc bảng nào đưa vào `loose`. */
    static ArrayList<Tbl> detectTables(Scan s, ArrayList<Seg> segs, ArrayList<Seg> loose) {
        double snap = SNAP_K * s.hc, join = JOIN_K * s.hc, touch = TOUCH_K * s.hc;
        ArrayList<Seg> hIn = new ArrayList<>(), vIn = new ArrayList<>();
        for (Seg sg : segs) {
            if (sg.h) hIn.add(sg);
            else vIn.add(sg);
        }
        ArrayList<Seg> hs = mergeRules(hIn, snap, join, 1.5 * s.hc), vs = mergeRules(vIn, snap, join, 1.5 * s.hc);
        int nh = hs.size(), nv = vs.size();
        int[] uf = new int[nh + nv];
        for (int i = 0; i < nh + nv; i++) uf[i] = i;
        for (int i = 0; i < nh; i++) {
            Seg hgt = hs.get(i);
            for (int q = 0; q < nv; q++) {
                Seg v = vs.get(q);
                if (v.pos >= hgt.a - touch && v.pos <= hgt.b + touch && hgt.pos >= v.a - touch && hgt.pos <= v.b + touch) ufUnion(uf, i, nh + q);
            }
        }
        ArrayList<Tbl> tables = new ArrayList<>();
        boolean[] done = new boolean[nh + nv];
        double pageArea = (double) s.w * s.h;
        for (int i = 0; i < nh + nv; i++) {
            int root = ufFind(uf, i);
            if (done[root]) continue;
            done[root] = true;
            ArrayList<Seg> gh = new ArrayList<>(), gv = new ArrayList<>();
            for (int q = 0; q < nh + nv; q++) {
                if (ufFind(uf, q) != root) continue;
                if (q < nh) gh.add(hs.get(q));
                else gv.add(vs.get(q - nh));
            }
            Tbl t = null;
            if (gh.size() >= 2 && gv.size() >= 2) t = buildTable(gh, gv, snap, touch);
            // một khung đơn quanh gần hết trang là khung trang, không phải bảng
            if (t != null && t.cells.size() == 1 && (t.x1 - t.x0) * (t.y1 - t.y0) > pageArea * 0.45) t = null;
            if (t != null) {
                tables.add(t);
            } else {
                loose.addAll(gh);
                loose.addAll(gv);
            }
        }
        Collections.sort(tables, new Comparator<Tbl>() {
            @Override
            public int compare(Tbl a, Tbl b) {
                if (a.y0 != b.y0) return a.y0 < b.y0 ? -1 : 1;
                return a.x0 < b.x0 ? -1 : (a.x0 > b.x0 ? 1 : 0);
            }
        });
        return tables;
    }

    /** Chỉ số ô chứa điểm (x, y); -1 nếu điểm nằm ngoài bảng. */
    static int cellOfPoint(Tbl t, double x, double y) {
        if (x < t.x0 || x > t.x1 || y < t.y0 || y > t.y1) return -1;
        int i = 0;
        while (i + 1 < t.cols && x >= t.xs[i + 1]) i++;
        int j = 0;
        while (j + 1 < t.rows && y >= t.ys[j + 1]) j++;
        return t.owner[j * t.cols + i];
    }

    // ================================================================== dòng chữ

    static final Comparator<W> BY_X = new Comparator<W>() {
        @Override
        public int compare(W a, W b) {
            if (a.x0 != b.x0) return a.x0 < b.x0 ? -1 : 1;
            if (a.y0 != b.y0) return a.y0 < b.y0 ? -1 : 1;
            return a.idx < b.idx ? -1 : (a.idx > b.idx ? 1 : 0);
        }
    };

    /**
     * Gom các từ của một vùng chứa thành dòng: duyệt từ trái sang phải, mỗi từ nối vào dòng có từ cuối chồng dọc với nó
     * (>= 50% chiều cao từ thấp hơn) và gần nó nhất về bên trái — chịu được trang hơi nghiêng. tables != null: không nối
     * hai từ nằm hai bên một bảng.
     */
    static ArrayList<Row> chainRows(ArrayList<W> ws, ArrayList<Tbl> tables) {
        ArrayList<W> sorted = new ArrayList<>(ws);
        Collections.sort(sorted, BY_X);
        ArrayList<Row> rows = new ArrayList<>();
        for (W wd : sorted) {
            Row best = null;
            double bestGap = Double.MAX_VALUE;
            double hw = wd.y1 - wd.y0;
            for (Row r : rows) {
                W last = r.ws.get(r.ws.size() - 1);
                double hl = last.y1 - last.y0;
                double ov = Math.min(wd.y1, last.y1) - Math.max(wd.y0, last.y0);
                double minH = Math.min(hw, hl);
                if (ov < 0.5 * minH) continue;
                double gap = wd.x0 - last.x1;
                if (gap < -0.5 * minH) continue;
                if (tables != null && tableBetween(tables, last, wd)) continue;
                if (gap < bestGap) {
                    bestGap = gap;
                    best = r;
                }
            }
            if (best == null) {
                best = new Row();
                rows.add(best);
            }
            best.ws.add(wd);
        }
        double[] hs = new double[64];
        for (Row r : rows) {
            r.x0 = Double.MAX_VALUE;
            r.y0 = Double.MAX_VALUE;
            r.x1 = -Double.MAX_VALUE;
            r.y1 = -Double.MAX_VALUE;
            int n = r.ws.size();
            if (hs.length < n) hs = new double[n * 2];
            for (int i = 0; i < n; i++) {
                W wd = r.ws.get(i);
                r.x0 = Math.min(r.x0, wd.x0);
                r.x1 = Math.max(r.x1, wd.x1);
                r.y0 = Math.min(r.y0, wd.y0);
                r.y1 = Math.max(r.y1, wd.y1);
                r.glyphs += wd.glyphs;
                hs[i] = wd.oy1 - wd.oy0;
            }
            // chia khúc tại khoảng trống lớn (>= 1,5 em; em sơ bộ = chiều cao khung của bộ nhận dạng): mỗi khúc sẽ bắt
            // đầu bằng một tab và có kiểu chữ riêng (tiêu ngữ hai cột, nhãn - giá trị)
            double em0 = Math.max(4.0, median(hs, n));
            Part cur = null;
            for (int i = 0; i < n; i++) {
                W wd = r.ws.get(i);
                if (cur == null || wd.x0 - r.ws.get(i - 1).x1 >= 1.5 * em0) {
                    cur = new Part();
                    cur.x0 = wd.x0;
                    r.parts.add(cur);
                }
                cur.ws.add(wd);
                cur.x1 = wd.x1;
                cur.glyphs += wd.glyphs;
            }
        }
        Collections.sort(rows, new Comparator<Row>() {
            @Override
            public int compare(Row a, Row b) {
                double ya = (a.y0 + a.y1) / 2.0, yb = (b.y0 + b.y1) / 2.0;
                if (ya != yb) return ya < yb ? -1 : 1;
                return a.x0 < b.x0 ? -1 : (a.x0 > b.x0 ? 1 : 0);
            }
        });
        return rows;
    }

    static boolean tableBetween(ArrayList<Tbl> tables, W a, W b) {
        double y0 = Math.max(a.y0, b.y0), y1 = Math.min(a.y1, b.y1);
        for (Tbl t : tables) {
            if (t.y1 < y0 || t.y0 > y1) continue;
            if (t.x0 >= a.x1 - 1 && t.x1 <= b.x0 + 1) return true;
            // bảng nằm chắn giữa (một phần)
            double mid = (t.x0 + t.x1) / 2.0;
            if (mid > a.x1 && mid < b.x0) return true;
        }
        return false;
    }

    static double median(double[] v, int n) {
        if (n == 0) return 0.0;
        double[] c = java.util.Arrays.copyOf(v, n);
        java.util.Arrays.sort(c);
        return (n & 1) == 1 ? c[n / 2] : (c[n / 2 - 1] + c[n / 2]) / 2.0;
    }

    /** Trung vị có trọng số của các cặp {giá trị, trọng số}. */
    static double wmedian(ArrayList<double[]> items) {
        if (items.isEmpty()) return 0.0;
        ArrayList<double[]> list = new ArrayList<>(items);
        Collections.sort(list, new Comparator<double[]>() {
            @Override
            public int compare(double[] a, double[] b) {
                return a[0] < b[0] ? -1 : (a[0] > b[0] ? 1 : 0);
            }
        });
        double sum = 0;
        for (double[] e : list) sum += e[1];
        double acc = 0;
        for (double[] e : list) {
            acc += e[1];
            if (acc * 2 >= sum) return e[0];
        }
        return list.get(list.size() - 1)[0];
    }

    static void setMetrics(W wd, int style) {
        wd.adv = WordFont.advanceUnits(wd.text, style);
        wd.lsb = WordFont.lsbUnits(wd.text, style);
        wd.rsb = WordFont.rsbUnits(wd.text, style);
    }

    /**
     * Kiểu chữ của mọi dòng, tính theo từng khúc: đường chân chữ của dòng; chữ nghiêng (hình chiếu theo cột sắc hơn
     * khi nắn nghiêng lại); nét đậm (bề dày nét / cỡ chữ so với trung vị cả trang); cỡ chữ (theo bề rộng mực của các
     * từ với số đo Times New Roman; khúc ngắn thì theo chiều cao thân chữ nhân hệ số hiệu chỉnh của trang); rồi gom cỡ
     * chữ về vài cỡ chính của trang.
     */
    static void styleRows(ArrayList<Row> rows, Geometry g) {
        double[] tmp = new double[64];
        ArrayList<Part> parts = new ArrayList<>();
        // ---- đường chân chữ của dòng + em theo chiều cao, màu của từng khúc
        for (Row r : rows) {
            int n = r.ws.size();
            if (tmp.length < n) tmp = new double[n * 2];
            int c = 0;
            for (W wd : r.ws) if (!Double.isNaN(wd.base)) tmp[c++] = wd.base;
            ArrayList<double[]> rowEms = new ArrayList<>();
            for (W wd : r.ws) if (wd.emH > 0) rowEms.add(new double[]{wd.emH, wd.glyphs});
            if (c > 0) {
                r.base = median(tmp, c);
            } else {
                // không từ nào đo được: đáy khung mực trừ phần đuôi chữ
                double em = !rowEms.isEmpty() ? wmedian(rowEms) : (r.y1 - r.y0) / (WordFont.CAP_HEIGHT + WordFont.DESCENT);
                r.base = r.y1 - WordFont.DESCENT * em;
            }
            for (Part pt : r.parts) {
                ArrayList<double[]> ems = new ArrayList<>();
                int colored = 0, col = 0;
                double s0 = 0, s1 = 0, s2 = 0;
                for (W wd : pt.ws) {
                    if (wd.emH > 0) ems.add(new double[]{wd.emH, wd.glyphs});
                    if (wd.colored) {
                        colored += wd.glyphs;
                        col = wd.color;
                    }
                    s0 += wd.sh0;
                    s1 += wd.sh1;
                    s2 += wd.sh2;
                }
                pt.emH = wmedian(ems);
                pt.color = colored * 2 > pt.glyphs ? col : 0;
                // nghiêng: nắn nghiêng làm hình chiếu sắc hơn hẳn so với để nguyên
                pt.italic = s0 > 0 && pt.glyphs >= 3 && Math.max(s1, s2) > 1.025 * s0;
                for (W wd : pt.ws) wd.italic = pt.italic;
                parts.add(pt);
            }
        }
        // ---- em sơ bộ theo bề rộng (chưa biết đậm) để chuẩn hoá bề dày nét
        for (Part pt : parts) {
            for (W wd : pt.ws) setMetrics(wd, WordFont.style(false, pt.italic));
            pt.emW = widthEm(pt);
        }
        // ---- nét đậm
        ArrayList<double[]> ratios = new ArrayList<>();
        for (Part pt : parts) {
            double em = pt.emW > 0 ? pt.emW : pt.emH;
            if (!(em >= 14.0)) continue;
            for (W wd : pt.ws) if (wd.stroke > 0 && wd.glyphs >= 2) ratios.add(new double[]{wd.stroke / em, wd.glyphs});
        }
        double ref = wmedian(ratios);
        if (ref > 0) {
            for (Part pt : parts) {
                double em = pt.emW > 0 ? pt.emW : pt.emH;
                if (!(em >= 14.0)) continue;
                int boldG = 0, tot = 0;
                for (W wd : pt.ws) {
                    if (!(wd.stroke > 0) || wd.glyphs < 2) continue;
                    tot += wd.glyphs;
                    wd.bold = wd.stroke / em > 1.27 * ref;
                    if (wd.bold) boldG += wd.glyphs;
                }
                if (tot == 0) continue;
                int nw = pt.ws.size();
                if (boldG * 10 >= tot * 7) {
                    pt.bold = true;
                    for (W wd : pt.ws) wd.bold = true;
                } else {
                    // đậm một phần: chỉ nhận cụm từ đậm ở đầu khúc (nhãn kiểu "Điều 1.", "Kính gửi:")
                    int lead = 0;
                    while (lead < nw && (pt.ws.get(lead).bold || (pt.ws.get(lead).glyphs < 2 && lead > 0))) lead++;
                    boolean rest = false;
                    for (int i = lead; i < nw; i++) rest |= pt.ws.get(i).bold && pt.ws.get(i).glyphs >= 4;
                    if (lead < 1 || boldG * 10 < tot * 2 || rest) lead = 0;
                    for (int i = 0; i < nw; i++) pt.ws.get(i).bold = i < lead;
                }
            }
        }
        // ---- em theo bề rộng với số đo đúng kiểu
        ArrayList<double[]> rho = new ArrayList<>();
        for (Part pt : parts) {
            for (W wd : pt.ws) setMetrics(wd, WordFont.style(wd.bold, pt.italic));
            pt.emW = widthEm(pt);
            if (pt.emW > 0 && pt.emH > 0 && pt.glyphs >= 12) rho.add(new double[]{pt.emW / pt.emH, pt.glyphs});
        }
        // hệ số trang: em theo bề rộng / em theo chiều cao (1 với Times New Roman; khác 1 khi phông gốc rộng / hẹp hơn)
        double k = rho.size() >= 3 ? clampD(wmedian(rho), 0.8, 1.25) : 1.0;
        ArrayList<double[]> sizes = new ArrayList<>();
        for (Part pt : parts) {
            double em;
            if (pt.emW > 0 && pt.glyphs >= 12) em = pt.emW;
            else if (pt.emH > 0 && pt.emW > 0 && pt.glyphs >= 5) em = (pt.emH * k + pt.emW) / 2.0;
            else if (pt.emH > 0) em = pt.emH * k;
            else if (pt.emW > 0 && pt.glyphs >= 4) em = pt.emW;
            else {
                // một hai ký tự: theo chiều cao khung mực (chữ số / chữ hoa cao bằng chữ hoa)
                double hgt = 0;
                for (W wd : pt.ws) hgt = Math.max(hgt, wd.y1 - wd.y0);
                em = hgt / WordFont.CAP_HEIGHT * k;
            }
            pt.size = clampD(em * g.scale, 4.0, 96.0);
            if (pt.glyphs >= 12) sizes.add(new double[]{pt.size, pt.glyphs});
        }
        // ---- gom cỡ chữ: lần lượt lấy cỡ nhiều chữ nhất, hút các khúc lệch <= 6% về nó
        ArrayList<Double> modes = new ArrayList<>();
        ArrayList<double[]> pool = new ArrayList<>(sizes);
        while (!pool.isEmpty() && modes.size() < 8) {
            double best = 0, bestW = -1;
            for (double[] a : pool) {
                double wsum = 0;
                for (double[] b : pool) if (Math.abs(b[0] - a[0]) <= 0.035 * a[0]) wsum += b[1];
                if (wsum > bestW || (wsum == bestW && a[0] < best)) {
                    bestW = wsum;
                    best = a[0];
                }
            }
            // tâm của cụm = trung bình có trọng số
            double sw = 0, sv = 0;
            for (double[] b : pool) {
                if (Math.abs(b[0] - best) <= 0.06 * best) {
                    sw += b[1];
                    sv += b[0] * b[1];
                }
            }
            double center = sv / sw;
            double m = rnd(center * 2.0) / 2.0;
            // cỡ chữ văn bản thường là số nguyên: lệch dưới 0,3 pt thì lấy số nguyên
            double whole = rnd(center);
            if (Math.abs(center - whole) <= 0.3) m = whole;
            if (!modes.contains(m)) modes.add(m);
            for (int i = pool.size() - 1; i >= 0; i--) if (Math.abs(pool.get(i)[0] - best) <= 0.06 * best) pool.remove(i);
        }
        for (Part pt : parts) {
            double tol = pt.glyphs >= 12 ? 0.07 : (pt.glyphs >= 4 ? 0.08 : 0.35);
            double pick = -1, bd = Double.MAX_VALUE;
            for (double m : modes) {
                double d = Math.abs(m - pt.size);
                if (d <= tol * pt.size && d < bd) {
                    bd = d;
                    pick = m;
                }
            }
            if (pick > 0) pt.size = pick;
            else pt.size = Math.max(4.0, rnd(pt.size * 2.0) / 2.0);
        }
        for (Row r : rows) {
            r.size = 0;
            for (Part pt : r.parts) r.size = Math.max(r.size, pt.size);
        }
    }

    /** Em (điểm ảnh) sao cho tổng bề rộng mực của các từ khớp số đo Times New Roman; 0 nếu không đủ dữ liệu. */
    static double widthEm(Part r) {
        double ink = 0, nat = 0;
        for (W wd : r.ws) {
            if (wd.glyphs < 2) continue;
            int u = wd.adv - wd.lsb - wd.rsb;
            if (u <= 0) continue;
            ink += wd.x1 - wd.x0;
            nat += u / WordFont.UPM;
        }
        return nat > 0.5 ? ink / nat : 0.0;
    }

    // ================================================================== dòng -> đoạn Word

    static WordDoc.Tok tok(int t, Part pt, W wd) {
        WordDoc.Tok k = new WordDoc.Tok();
        k.t = t;
        k.size = pt.size;
        k.color = pt.color;
        k.bold = wd != null ? wd.bold : pt.bold;
        k.italic = pt.italic;
        k.underline = pt.underline;
        return k;
    }

    /**
     * Đoạn Word của một dòng: toạ độ pt; mỗi khúc sau khúc đầu bắt đầu bằng tab tới đúng vị trí; trong một khúc, nếu
     * một giá trị giãn chữ chung giải thích được vị trí mọi từ thì cả khúc là một mẩu chữ, không thì mỗi dấu cách mang
     * độ giãn riêng để từ kế tiếp bắt đầu đúng chỗ (dòng căn đều hai lề).
     */
    static WordDoc.Para makePara(Row r, Geometry g) {
        WordDoc.Para p = new WordDoc.Para();
        p.base = g.oy + r.base * g.scale;
        // kiểu của dấu kết đoạn: khúc nhiều chữ nhất
        Part main = r.parts.get(0);
        for (Part pt : r.parts) if (pt.glyphs > main.glyphs) main = pt;
        p.size = main.size;
        p.bold = main.bold;
        p.italic = main.italic;
        p.color = main.color;
        double pen = 0;
        for (int pi = 0; pi < r.parts.size(); pi++) {
            Part pt = r.parts.get(pi);
            double s = pt.size;
            ArrayList<W> ws = pt.ws;
            int n = ws.size();
            double[] x0 = new double[n], x1 = new double[n], adv = new double[n], lsb = new double[n], rsb = new double[n], sp = new double[n];
            for (int i = 0; i < n; i++) {
                W wd = ws.get(i);
                x0[i] = g.ox + wd.x0 * g.scale;
                x1[i] = g.ox + wd.x1 * g.scale;
                adv[i] = wd.adv / WordFont.UPM * s;
                lsb[i] = wd.lsb / WordFont.UPM * s;
                rsb[i] = wd.rsb / WordFont.UPM * s;
                // dấu cách sau từ i mang kiểu của từ i (đậm chỉ khi từ kế cũng đậm)
                boolean sb = wd.bold && i + 1 < n && ws.get(i + 1).bold;
                sp[i] = WordFont.spaceUnits(WordFont.style(sb, pt.italic)) / WordFont.UPM * s;
            }
            double start = x0[0] - lsb[0];
            if (pi == 0) {
                p.x = start;
            } else {
                WordDoc.Tok tb = tok(WordDoc.T_TAB, pt, null);
                tb.pos = start;
                p.toks.add(tb);
                p.hasTab = true;
            }
            pen = start;
            // giãn chung của cả khúc
            double num = 0, den = 0;
            for (int q = 0; q < n; q++) {
                W wd = ws.get(q);
                num += (x1[q] - x0[q]) - (adv[q] - lsb[q] - rsb[q]);
                den += wd.glyphs - 1;
                if (q > 0) {
                    num += (x0[q] - x1[q - 1]) - (rsb[q - 1] + sp[q - 1] + lsb[q]);
                    den += 2;
                }
            }
            double lo = -0.06 * s, hi = 0.12 * s;
            double shared = den > 0 ? clampD(quant(num / den), lo, hi) : 0.0;
            boolean uniform = true;
            for (int q = 0; q < n && uniform; q++) {
                W wd = ws.get(q);
                double wTol = Math.max(1.0, 0.06 * (x1[q] - x0[q]));
                if (Math.abs((x1[q] - x0[q]) - (adv[q] - lsb[q] - rsb[q]) - shared * (wd.glyphs - 1)) > wTol) uniform = false;
                if (q > 0 && Math.abs((x0[q] - x1[q - 1]) - (rsb[q - 1] + sp[q - 1] + lsb[q]) - 2.0 * shared) > Math.max(1.5, 0.12 * s)) uniform = false;
            }
            double letter = shared;
            if (!uniform) {
                // chữ trước (dấu cách có thể bị kéo giãn do căn đều): trung vị có trọng số của độ giãn từng từ
                ArrayList<double[]> items = new ArrayList<>();
                for (int q = 0; q < n; q++) {
                    W wd = ws.get(q);
                    if (wd.glyphs >= 2) items.add(new double[]{((x1[q] - x0[q]) - (adv[q] - lsb[q] - rsb[q])) / (wd.glyphs - 1), wd.glyphs - 1});
                }
                letter = clampD(quant(wmedian(items)), lo, hi);
            }
            for (int q = 0; q < n; q++) {
                W wd = ws.get(q);
                if (q > 0) {
                    WordDoc.Tok sk = tok(WordDoc.T_SPACE, pt, ws.get(q - 1));
                    sk.bold = ws.get(q - 1).bold && wd.bold;
                    double extra = shared;
                    if (!uniform) {
                        // bút đang ở `pen`; từ kế bắt đầu tại x0 - lsb
                        extra = quant((x0[q] - lsb[q]) - pen - sp[q - 1]);
                        if (extra < -0.75 * sp[q - 1]) extra = r20(-0.75 * sp[q - 1]);
                    }
                    sk.spacing = extra;
                    p.toks.add(sk);
                    pen += sp[q - 1] + extra;
                }
                WordDoc.Tok tk = tok(WordDoc.T_TEXT, pt, wd);
                tk.text = wd.text;
                tk.spacing = letter;
                p.toks.add(tk);
                pen += adv[q] + letter * wd.glyphs;
            }
        }
        p.x1 = Math.max(p.x + 1.0, pen);
        return p;
    }

    /** Chiều cao hộp dòng tự nhiên của cỡ chữ s (pt). */
    static double lineHeight(double s) {
        return clampD(LINE_K, 1.02, 1.32) * s;
    }

    /**
     * Các dòng của một vùng chứa -> đoạn có hộp dòng chính xác. top: mép trên vùng chứa (dòng không được bắt đầu cao
     * hơn); bottom: mép dưới (hộp dòng cuối không vượt quá).
     */
    static ArrayList<WordDoc.Para> placeRows(ArrayList<Row> rows, double top, double bottom, Geometry g) {
        int n = rows.size();
        ArrayList<WordDoc.Para> out = new ArrayList<>();
        double[] hgt = new double[n], ys = new double[n];
        for (int i = 0; i < n; i++) {
            ys[i] = g.oy + rows.get(i).base * g.scale;
            hgt[i] = lineHeight(rows.get(i).size);
        }
        for (int i = 0; i < n; i++) {
            if (i > 0) hgt[i] = Math.min(hgt[i], ys[i] - ys[i - 1]);
            if (i + 1 < n) hgt[i] = Math.min(hgt[i], ys[i + 1] - ys[i]);
            hgt[i] = Math.max(hgt[i], 1.0);
        }
        for (int i = 0; i < n; i++) {
            double hh = hgt[i];
            double t = ys[i] - 0.8 * hh;
            if (t < top) {
                // chữ bắt đầu cao hơn vùng chứa: dùng hộp thấp hơn
                hh = Math.max(1.0, Math.min(hh, (ys[i] - top) / 0.8));
                t = Math.max(top, ys[i] - 0.8 * hh);
            }
            if (t + hh > bottom) hh = Math.max(1.0, bottom - t);
            WordDoc.Para p = makePara(rows.get(i), g);
            p.top = t;
            p.height = hh;
            out.add(p);
        }
        return out;
    }

    static WordDoc.Block tableBlock(Tbl t, Geometry g) {
        WordDoc.Table wt = new WordDoc.Table();
        int m = t.cols, n = t.rows;
        wt.cols = m;
        wt.rows = n;
        wt.xs = new double[m + 1];
        wt.ys = new double[n + 1];
        wt.hT = new double[n + 1];
        wt.vT = new double[m + 1];
        for (int i = 0; i <= m; i++) {
            wt.xs[i] = g.ox + t.xs[i] * g.scale;
            wt.vT[i] = t.vT[i] * g.scale;
        }
        for (int j = 0; j <= n; j++) {
            wt.ys[j] = g.oy + t.ys[j] * g.scale;
            wt.hT[j] = t.hT[j] * g.scale;
        }
        wt.owner = t.owner.clone();
        // lề trong của bảng: khoảng cách nhỏ nhất giữa đường kẻ trái và chữ (tối đa 5,4 pt)
        double margin = MAX_CELL_MARGIN;
        for (TCell c : t.cells) {
            for (Row r : c.rows) {
                margin = Math.min(margin, (r.x0 - c.x0) * g.scale);
                margin = Math.min(margin, (c.x1 - r.x1) * g.scale);
            }
        }
        margin = Math.max(0.0, r20(margin));
        wt.marL = margin;
        wt.marR = margin;
        for (TCell c : t.cells) {
            WordDoc.Cell wc = new WordDoc.Cell();
            wc.c0 = c.c0;
            wc.c1 = c.c1;
            wc.r0 = c.r0;
            wc.r1 = c.r1;
            wc.x0 = wt.xs[c.c0];
            wc.x1 = wt.xs[c.c1 + 1];
            wc.y0 = wt.ys[c.r0];
            wc.y1 = wt.ys[c.r1 + 1];
            wc.bTop = c.bTop * g.scale;
            wc.bBottom = c.bBottom * g.scale;
            wc.bLeft = c.bLeft * g.scale;
            wc.bRight = c.bRight * g.scale;
            wc.marL = margin;
            wc.marR = margin;
            double interiorTop = wc.y0 + wt.hT[c.r0] / 2.0;
            double interiorBottom = wc.y1 - wt.hT[c.r1 + 1] / 2.0 - 0.5;
            ArrayList<WordDoc.Para> paras = placeRows(c.rows, interiorTop, interiorBottom, g);
            for (WordDoc.Para p : paras) {
                p.align = DocxWriter.alignmentOf(p, wc.x0 + wc.marL, wc.x1 - wc.marR);
                wc.paras.add(p);
            }
            wt.cells.add(wc);
        }
        WordDoc.Block b = new WordDoc.Block();
        b.table = wt;
        b.top = wt.ys[0] - wt.hT[0] / 2.0;
        b.bottom = wt.ys[n] + wt.hT[n] / 2.0;
        b.x0 = wt.xs[0];
        b.x1 = wt.xs[m];
        return b;
    }

    /**
     * Chữ ngoài bảng: xếp vào dòng chảy khi được; dòng nằm cạnh bảng, hoặc quá sát dòng trước (hai cột lệch dòng, chữ in
     * đè) thì thành hộp chữ neo theo trang. Dòng chảy được cắt thành từng khúc giữa các bảng để hộp dòng không lấn vào bảng.
     */
    static void layoutFree(WordDoc.Page page, ArrayList<Row> freeRows, ArrayList<WordDoc.Block> tableBlocks, Geometry g) {
        ArrayList<Row> flow = new ArrayList<>(), boxed = new ArrayList<>();
        for (Row r : freeRows) {
            double hgt = lineHeight(r.size);
            double y = g.oy + r.base * g.scale;
            double top = y - 0.8 * hgt, bottom = y + 0.2 * hgt;
            boolean beside = false;
            for (WordDoc.Block b : tableBlocks) if (top < b.bottom - 0.5 && bottom > b.top + 0.5) beside = true;
            if (beside) boxed.add(r);
            else flow.add(r);
        }
        Collections.sort(flow, new Comparator<Row>() {
            @Override
            public int compare(Row a, Row b) {
                if (a.base != b.base) return a.base < b.base ? -1 : 1;
                return a.x0 < b.x0 ? -1 : (a.x0 > b.x0 ? 1 : 0);
            }
        });
        ArrayList<Row> stack = new ArrayList<>();
        for (Row r : flow) {
            Row prev = stack.isEmpty() ? null : stack.get(stack.size() - 1);
            double need = 0.85 * Math.min(lineHeight(r.size), lineHeight(prev != null ? prev.size : r.size));
            if (prev != null && (r.base - prev.base) * g.scale < need) boxed.add(r);
            else stack.add(r);
        }
        ArrayList<WordDoc.Block> blocks = new ArrayList<>(tableBlocks);
        ArrayList<WordDoc.Block> sortedTables = new ArrayList<>(tableBlocks);
        Collections.sort(sortedTables, new Comparator<WordDoc.Block>() {
            @Override
            public int compare(WordDoc.Block a, WordDoc.Block b) {
                return a.top < b.top ? -1 : (a.top > b.top ? 1 : 0);
            }
        });
        ArrayList<Row> segment = new ArrayList<>();
        double segTop = 0;
        int ti = 0;
        for (int i = 0; i <= stack.size(); i++) {
            Row r = i < stack.size() ? stack.get(i) : null;
            double y = r != null ? g.oy + r.base * g.scale : Double.MAX_VALUE;
            while (ti < sortedTables.size() && (r == null || sortedTables.get(ti).top < y)) {
                flushSegment(blocks, segment, segTop, sortedTables.get(ti).top, g);
                segTop = Math.max(segTop, sortedTables.get(ti).bottom);
                ti++;
            }
            if (r != null) segment.add(r);
        }
        flushSegment(blocks, segment, segTop, page.height, g);
        Collections.sort(blocks, new Comparator<WordDoc.Block>() {
            @Override
            public int compare(WordDoc.Block a, WordDoc.Block b) {
                if (a.top != b.top) return a.top < b.top ? -1 : 1;
                return a.x0 < b.x0 ? -1 : (a.x0 > b.x0 ? 1 : 0);
            }
        });
        // bảng chồng dọc lên nhau (đứng cạnh nhau) không thể cùng nằm trong dòng chảy
        double cursor = -Double.MAX_VALUE;
        for (WordDoc.Block b : blocks) {
            if (b.table != null && b.top < cursor - 0.5) b.overlaps = true;
            else cursor = Math.max(cursor, b.bottom);
        }
        page.blocks.addAll(blocks);
        for (Row r : boxed) {
            ArrayList<Row> one = new ArrayList<>();
            one.add(r);
            WordDoc.Para p = placeRows(one, -Double.MAX_VALUE, Double.MAX_VALUE, g).get(0);
            WordDoc.Box bx = new WordDoc.Box();
            bx.x = p.x;
            bx.y = p.top;
            bx.w = p.x1 - p.x;
            bx.h = p.height;
            bx.para = p;
            page.boxes.add(bx);
        }
    }

    static void flushSegment(ArrayList<WordDoc.Block> blocks, ArrayList<Row> segment, double segTop, double limit, Geometry g) {
        if (segment.isEmpty()) return;
        ArrayList<WordDoc.Para> paras = placeRows(segment, segTop, Double.MAX_VALUE, g);
        for (WordDoc.Para p : paras) {
            if (p.top + p.height > limit) p.height = Math.max(1.0, limit - p.top);
            WordDoc.Block b = new WordDoc.Block();
            b.para = p;
            b.top = p.top;
            b.bottom = p.top + p.height;
            b.x0 = p.x;
            b.x1 = p.x1;
            blocks.add(b);
        }
        segment.clear();
    }

    // ================================================================== gạch chân, hình

    /**
     * Đường kẻ ngang mảnh nằm sát dưới một dòng chữ và dài đúng bằng dòng (hoặc cụm từ liền nhau) là gạch chân: đánh dấu
     * dòng, bỏ đường kẻ. Trả về các đường kẻ còn lại.
     */
    static ArrayList<Seg> applyUnderlines(Scan s, ArrayList<Row> rows, ArrayList<Seg> loose, Geometry g) {
        ArrayList<Seg> rest = new ArrayList<>();
        for (Seg sg : loose) {
            boolean used = false;
            if (sg.h) {
                for (Row r : rows) {
                    for (Part pt : r.parts) {
                        double em = pt.size / g.scale;
                        if (sg.t > Math.max(2.0, 0.14 * em)) continue;
                        double dy = sg.pos - r.base;
                        if (dy < 0.02 * em || dy > 0.32 * em) continue;
                        if (Math.abs(sg.a - pt.x0) > 0.45 * em || Math.abs(sg.b - pt.x1) > 0.45 * em) continue;
                        pt.underline = true;
                        used = true;
                        break;
                    }
                    if (used) break;
                }
            }
            if (!used) rest.add(sg);
        }
        return rest;
    }

    /**
     * Hình: mực còn lại sau khi bỏ đường kẻ và khung các từ đã nhận dạng (con dấu, chữ ký, ảnh, chữ viết tay không đọc
     * được). Gom các nét gần nhau thành vùng; vùng quá nhỏ (đốm bẩn, dấu câu sót) bỏ qua.
     */
    static void findPictures(Scan s, ArrayList<W> ws, WordDoc.Page page, Geometry g) {
        int aw = s.aw, ah = s.ah, k = s.k;
        if (aw < 16 || ah < 16) return;
        int nA = aw * ah;
        byte[] m = new byte[nA];
        int any = 0;
        for (int i = 0; i < nA; i++) {
            if ((s.amin[i] & 255) >= s.tq) continue;
            int a = s.hId[i], b = s.vId[i];
            if ((a > 0 && s.hKeep[a]) || (b > 0 && s.vKeep[b])) continue;
            m[i] = 1;
            any++;
        }
        if (any == 0) return;
        // bỏ mực trong khung các từ (nới một điểm ảnh phân tích) và quanh đường kẻ
        for (W wd : ws) {
            if (wd.drop) continue;
            int x0 = Math.max(0, (int) Math.floor(Math.min(wd.ox0, wd.x0) / k) - 1), y0 = Math.max(0, (int) Math.floor(wd.oy0 / k) - 1);
            int x1 = Math.min(aw, (int) Math.ceil(Math.max(wd.ox1, wd.x1) / k) + 1), y1 = Math.min(ah, (int) Math.ceil(wd.oy1 / k) + 1);
            for (int y = y0; y < y1; y++) for (int x = x0; x < x1; x++) m[y * aw + x] = 0;
        }
        for (int y = 0; y < ah; y++) {
            for (int x = 0; x < aw; x++) {
                int i = y * aw + x;
                if (m[i] == 0) continue;
                // sát đường kẻ đã nhận (viền nhoè của đường kẻ)
                boolean nearRule = false;
                for (int dy = -1; dy <= 1 && !nearRule; dy++) {
                    int yy = y + dy;
                    if (yy < 0 || yy >= ah) continue;
                    for (int dx = -1; dx <= 1; dx++) {
                        int xx = x + dx;
                        if (xx < 0 || xx >= aw) continue;
                        int j = yy * aw + xx;
                        int a = s.hId[j], b = s.vId[j];
                        if ((a > 0 && s.hKeep[a]) || (b > 0 && s.vKeep[b])) {
                            nearRule = true;
                            break;
                        }
                    }
                }
                if (nearRule) m[i] = 0;
            }
        }
        byte[] ink = m.clone();
        int[] bb = Retouch.bounds(m, aw, ah);
        if (bb == null) return;
        double hcA = s.hc / k;
        int d = Math.max(1, (int) Math.round(hcA * 0.6));
        Retouch.dilate(m, aw, ah, d, bb);
        int[] lab = new int[nA], queue = new int[nA];
        ArrayList<int[]> boxes = new ArrayList<>();   // {x0, y0, x1, y1, số điểm mực}
        int edge = Math.max(2, (int) Math.round(0.012 * Math.max(aw, ah)));
        for (int start = 0; start < nA; start++) {
            if (m[start] == 0 || lab[start] != 0) continue;
            int head = 0, tail = 0;
            queue[tail++] = start;
            lab[start] = 1;
            int minX = aw, maxX = -1, minY = ah, maxY = -1, cnt = 0;
            while (head < tail) {
                int i = queue[head++];
                int x = i % aw, y = i / aw;
                if (ink[i] != 0) {
                    cnt++;
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
                if (x > 0 && m[i - 1] != 0 && lab[i - 1] == 0) {
                    lab[i - 1] = 1;
                    queue[tail++] = i - 1;
                }
                if (x + 1 < aw && m[i + 1] != 0 && lab[i + 1] == 0) {
                    lab[i + 1] = 1;
                    queue[tail++] = i + 1;
                }
                if (y > 0 && m[i - aw] != 0 && lab[i - aw] == 0) {
                    lab[i - aw] = 1;
                    queue[tail++] = i - aw;
                }
                if (y + 1 < ah && m[i + aw] != 0 && lab[i + aw] == 0) {
                    lab[i + aw] = 1;
                    queue[tail++] = i + aw;
                }
            }
            if (maxX < 0) continue;
            int bw = maxX - minX + 1, bh = maxY - minY + 1;
            // quá nhỏ (đốm bẩn, dấu chấm / dấu thanh sót) thì bỏ; từ cỡ một ký tự trở lên mà bộ nhận dạng bỏ sót thì giữ
            // (kể cả ký tự hẹp như số 1: cao cỡ một chữ, đủ mực) dưới dạng hình để không mất nội dung
            if (Math.max(bw, bh) < 0.9 * hcA || cnt < 8) continue;
            boolean tallGlyph = bh >= 0.9 * hcA && bh <= 2.5 * hcA && bw >= Math.max(2.0, 0.18 * hcA) && cnt >= 0.12 * hcA * hcA;
            if (!tallGlyph && (Math.min(bw, bh) < 0.45 * hcA || (double) bw * bh < 0.6 * hcA * hcA)) continue;
            // vệt nằm sát mép ảnh: bóng mép giấy / mép bàn lọt vào — dải mỏng hoặc dài chạy dọc mép thì bỏ; hình gọn
            // (dấu tích, con dấu sát lề) thì giữ
            boolean atV = minX < edge || maxX >= aw - edge, atH = minY < edge || maxY >= ah - edge;
            if ((atV || atH) && Math.min(bw, bh) < 1.0 * hcA) continue;
            if (atV && bh >= 4 * bw) continue;
            if (atH && bw >= 4 * bh) continue;
            boxes.add(new int[]{minX, minY, maxX, maxY, cnt});
        }
        // nhiều quá (trang nhiễu): chỉ giữ các vùng lớn nhất
        Collections.sort(boxes, new Comparator<int[]>() {
            @Override
            public int compare(int[] a, int[] b) {
                long sa = (long) (a[2] - a[0] + 1) * (a[3] - a[1] + 1), sb = (long) (b[2] - b[0] + 1) * (b[3] - b[1] + 1);
                if (sa != sb) return sa > sb ? -1 : 1;
                if (a[1] != b[1]) return a[1] < b[1] ? -1 : 1;
                return a[0] < b[0] ? -1 : (a[0] > b[0] ? 1 : 0);
            }
        });
        while (boxes.size() > MAX_PICTURES) boxes.remove(boxes.size() - 1);
        Collections.sort(boxes, new Comparator<int[]>() {
            @Override
            public int compare(int[] a, int[] b) {
                if (a[1] != b[1]) return a[1] < b[1] ? -1 : 1;
                return a[0] < b[0] ? -1 : (a[0] > b[0] ? 1 : 0);
            }
        });
        for (int[] b : boxes) {
            WordDoc.Pic p = new WordDoc.Pic();
            p.px0 = Math.max(0, (b[0] - 1) * k);
            p.py0 = Math.max(0, (b[1] - 1) * k);
            p.px1 = Math.min(s.w, (b[2] + 2) * k);
            p.py1 = Math.min(s.h, (b[3] + 2) * k);
            // vị trí trên trang: tâm vùng cắt sau khi khử nghiêng
            double mx = (p.px0 + p.px1) / 2.0, my = (p.py0 + p.py1) / 2.0;
            double nx = s.rx(mx, my), ny = s.ry(mx, my);
            double pw = (p.px1 - p.px0) * g.scale, ph = (p.py1 - p.py0) * g.scale;
            p.x = g.ox + nx * g.scale - pw / 2.0;
            p.y = g.oy + ny * g.scale - ph / 2.0;
            p.w = pw;
            p.h = ph;
            page.pics.add(p);
        }
    }
}
