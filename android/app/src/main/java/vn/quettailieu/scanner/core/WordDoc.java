package vn.quettailieu.scanner.core;

import java.util.ArrayList;

/**
 * Mô hình trang đã dàn để ghi ra Word (DocxWriter): mỗi dòng chữ là một đoạn đặt đúng vị trí, bảng kẻ là bảng Word thật,
 * hình / đường kẻ rời / chữ không xếp được vào dòng chảy thì neo theo trang. Đơn vị: point (1/72 inch), gốc trên-trái.
 * Java thuần, không phụ thuộc Android. Bản Swift: ios/Sources/WordDoc.swift.
 */
public final class WordDoc {
    private WordDoc() {}

    public static final int ALIGN_LEFT = 0, ALIGN_CENTER = 1, ALIGN_RIGHT = 2;
    public static final int T_TEXT = 0, T_SPACE = 1, T_TAB = 2;

    /** Một mẩu của dòng chữ: chữ, dấu cách (có giãn riêng) hoặc tab tới vị trí tuyệt đối. */
    public static final class Tok {
        public int t;
        /** T_TEXT: nội dung. */
        public String text = "";
        /** T_SPACE: số dấu cách. */
        public int n = 1;
        /** T_TEXT: giãn chữ (pt, cộng sau mỗi ký tự); T_SPACE: giãn thêm của dấu cách. */
        public double spacing;
        /** T_TAB: vị trí đích (pt, theo trang). */
        public double pos;
        public boolean bold, italic, underline;
        /** Màu chữ 0xRRGGBB. */
        public int color;
        /** Cỡ chữ (pt). */
        public double size = 12;
    }

    /** Một dòng chữ = một đoạn Word có chiều cao dòng chính xác. */
    public static final class Para {
        /** Mép trên và chiều cao hộp dòng; x..x1: phạm vi ngang của chữ; base: đường chân chữ. */
        public double top, height, x, x1, base;
        public int align = ALIGN_LEFT;
        public boolean hasTab;
        public final ArrayList<Tok> toks = new ArrayList<>();
        /** Kiểu chữ chủ đạo của dòng (dùng cho dấu kết đoạn). */
        public double size = 12;
        public boolean bold, italic;
        public int color;
    }

    public static final class Cell {
        public int c0, c1, r0, r1;
        public double x0, y0, x1, y1;
        /** Độ dày viền trên / trái / dưới / phải (pt); 0 = không kẻ. */
        public double bTop, bLeft, bBottom, bRight;
        /** Lề trong trái / phải của ô (pt). */
        public double marL, marR;
        public final ArrayList<Para> paras = new ArrayList<>();
    }

    public static final class Table {
        /** Đường lưới dọc / ngang (pt), tăng dần. */
        public double[] xs, ys;
        public int cols, rows;
        public final ArrayList<Cell> cells = new ArrayList<>();
        /** Ô chứa từng ô lưới: owner[hàng * cols + cột] = chỉ số trong cells. */
        public int[] owner;
        /** Độ dày đường kẻ trên từng đường lưới ngang / dọc (0: đường lưới ảo). */
        public double[] hT, vT;
        public double marL, marR;
    }

    /** Một phần của dòng chảy dọc trang: dòng chữ hoặc bảng. */
    public static final class Block {
        public Para para;
        public Table table;
        public double top, bottom, x0, x1;
        /** Bảng đứng cạnh bảng khác: không vào dòng chảy được, đặt trong hộp chữ đúng chỗ. */
        public boolean overlaps;
    }

    /** Hình neo theo trang; data/ext do lớp nền tảng điền (cắt từ ảnh trang theo px0..py1 rồi nén JPEG / PNG). */
    public static final class Pic {
        public double x, y, w, h;
        public boolean behind = true;
        public byte[] data;
        public String ext = "jpeg";
        /** Vùng cắt trên ảnh trang (điểm ảnh, nửa mở). */
        public int px0, py0, px1, py1;
    }

    /** Đường kẻ rời (không thuộc bảng). */
    public static final class Rule {
        public boolean horiz;
        public double pos, a, b, t;
    }

    /** Dòng chữ không xếp được vào dòng chảy (chồng lên dòng khác, nằm cạnh bảng): hộp chữ neo theo trang. */
    public static final class Box {
        public double x, y, w, h;
        public Para para;
    }

    public static final class Page {
        public double width, height;
        public final ArrayList<Block> blocks = new ArrayList<>();
        public final ArrayList<Pic> pics = new ArrayList<>();
        public final ArrayList<Rule> rules = new ArrayList<>();
        public final ArrayList<Box> boxes = new ArrayList<>();
        // ---- lề trang (DocxWriter.planMargins điền)
        double left = 36, right = 36, top = 36, bottom = 36, extraHeight;
        boolean flow, ownLeft, ownRight;
        double minX, maxX, first, room;
    }
}
