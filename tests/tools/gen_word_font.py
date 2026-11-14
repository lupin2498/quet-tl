#!/usr/bin/env python3
"""Sinh bảng số đo phông Times New Roman cho việc xuất Word (core/WordFont.java + ios/Sources/WordFont.swift).

Số đo lấy từ Liberation Serif (cùng bề rộng chữ với Times New Roman, giấy phép SIL OFL) — chỉ là các con số bề rộng
ký tự, không nhúng phông. Mỗi ký tự: bước tiến (advance), lề trái (lsb = xMin), lề phải (rsb = advance - xMax), theo
đơn vị 1/2048 em, cho 4 kiểu: thường, đậm, nghiêng, đậm nghiêng.

Chạy lại khi cần đổi bảng:  python3 tests/tools/gen_word_font.py [thư-mục-phông]
"""
import os
import sys
import unicodedata

from fontTools.pens.boundsPen import BoundsPen
from fontTools.ttLib import TTFont

FONT_DIR = sys.argv[1] if len(sys.argv) > 1 else "/usr/share/fonts/truetype/liberation"
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(HERE, "..", ".."))
STYLES = ["LiberationSerif-Regular.ttf", "LiberationSerif-Bold.ttf", "LiberationSerif-Italic.ttf", "LiberationSerif-BoldItalic.ttf"]

ASCII = [chr(c) for c in range(0x20, 0x7F)]
# ký tự ngoài ASCII có số đo riêng (chữ có râu của tiếng Việt, đ, dấu câu / ký hiệu hay gặp trong văn bản)
EXTRA = list("ơưƠƯđĐ–—‘’“”…•·°×÷±§©®™«»¡¿€£¥¢½¼¾₫№‰′″‹›†‡¶")
BASE = ASCII + EXTRA


def base_of(ch):
    """Ký tự trong bảng có cùng bề rộng với ch (chữ Latinh có dấu -> chữ gốc), None nếu không quy được."""
    if ch in BASE:
        return ch
    d = unicodedata.normalize("NFD", ch)
    b = d[0]
    if len(d) > 1 and all(unicodedata.combining(c) for c in d[1:]):
        # dấu râu (U+031B) làm chữ rộng hơn: ơ ư Ơ Ư có số đo riêng
        if "̛" in d[1:] and b in "ouOU":
            return {"o": "ơ", "u": "ư", "O": "Ơ", "U": "Ư"}[b]
        if b in BASE:
            return b
    return None


def main():
    fonts = [TTFont(os.path.join(FONT_DIR, f)) for f in STYLES]
    upm = fonts[0]["head"].unitsPerEm
    assert all(f["head"].unitsPerEm == upm == 2048 for f in fonts)
    adv, lsb, rsb = [], [], []
    for f in fonts:
        cmap = f.getBestCmap()
        gs = f.getGlyphSet()
        hmtx = f["hmtx"]
        a, l, r = [], [], []
        for ch in BASE:
            g = cmap.get(ord(ch))
            if g is None:
                raise SystemExit("phông thiếu ký tự %r" % ch)
            w = hmtx[g][0]
            bp = BoundsPen(gs)
            gs[g].draw(bp)
            if bp.bounds is None:
                a.append(w); l.append(0); r.append(0)
            else:
                x0, _, x1, _ = bp.bounds
                a.append(w); l.append(int(round(x0))); r.append(int(round(w - x1)))
        adv.append(a); lsb.append(l); rsb.append(r)
    # ánh xạ chữ Latinh có dấu -> chỉ số trong bảng
    cps, idx = [], []
    for cp in list(range(0x00A0, 0x0250)) + list(range(0x1E00, 0x1F00)) + [ord(c) for c in EXTRA]:
        ch = chr(cp)
        b = base_of(ch)
        if b is None:
            continue
        cps.append(cp)
        idx.append(BASE.index(b))
    # chỉ giữ ánh xạ khi bề rộng thật của chữ có dấu khớp chữ gốc ở cả 4 kiểu (lệch <= 8/2048); chữ nào lệch hơn
    # (ď, ľ, ŉ, ĳ...) thì để rơi về bề rộng mặc định
    cmaps = [f.getBestCmap() for f in fonts]
    order = []
    worst = 0
    dropped = []
    for cp, i in sorted(set(zip(cps, idx))):
        diff = 0
        for s, f in enumerate(fonts):
            g = cmaps[s].get(cp)
            if g is not None:
                diff = max(diff, abs(f["hmtx"][g][0] - adv[s][i]))
        if diff > 8:
            dropped.append(chr(cp))
            continue
        worst = max(worst, diff)
        order.append((cp, i))
    cps = [c for c, _ in order]
    idx = [i for _, i in order]
    viet = "àáảãạăằắẳẵặâầấẩẫậèéẻẽẹêềếểễệìíỉĩịòóỏõọôồốổỗộơờớởỡợùúủũụưừứửữựỳýỷỹỵđ"
    missing = [c for c in viet + viet.upper() if ord(c) not in cps]
    if missing:
        raise SystemExit("thiếu chữ tiếng Việt trong ánh xạ: %s" % "".join(missing))
    print("bỏ ánh xạ (bề rộng khác chữ gốc): %s" % "".join(dropped))
    # mép trên / dưới của mực từng ký tự so với đường chân chữ (kiểu thường): cho bảng chính và cho từng chữ có dấu
    gs0 = fonts[0].getGlyphSet()

    def vext(cp):
        g = cmaps[0].get(cp)
        if g is None:
            return 0, 0
        bp = BoundsPen(gs0)
        gs0[g].draw(bp)
        if bp.bounds is None:
            return 0, 0
        return int(round(bp.bounds[3])), int(round(bp.bounds[1]))
    top = [vext(ord(ch))[0] for ch in BASE]
    bot = [vext(ord(ch))[1] for ch in BASE]
    mtop = [vext(cp)[0] for cp in cps]
    mbot = [vext(cp)[1] for cp in cps]
    os2 = fonts[0]["OS/2"]
    hhea = fonts[0]["hhea"]
    xh, cap, asc, desc = os2.sxHeight, os2.sCapHeight, hhea.ascent, -hhea.descent
    print("ký tự trong bảng: %d, ánh xạ: %d, sai khác bề rộng lớn nhất của chữ có dấu: %d/2048" % (len(BASE), len(cps), worst))

    def jarr(v):
        return "{" + ", ".join(str(x) for x in v) + "}"

    def sarr(v):
        return "[" + ", ".join(str(x) for x in v) + "]"

    java = []
    java.append("package vn.quettailieu.scanner.core;")
    java.append("")
    java.append("/**")
    java.append(" * Số đo chữ Times New Roman (bước tiến, lề trái, lề phải của từng ký tự; đơn vị 1/2048 em) để ước lượng bề rộng")
    java.append(" * dòng chữ khi xuất Word. FILE SINH TỰ ĐỘNG bằng tests/tools/gen_word_font.py — không sửa tay.")
    java.append(" * Java thuần, không phụ thuộc Android. Bản Swift: ios/Sources/WordFont.swift (cùng bảng số).")
    java.append(" */")
    java.append("public final class WordFont {")
    java.append("    private WordFont() {}")
    java.append("")
    java.append("    public static final int REGULAR = 0, BOLD = 1, ITALIC = 2, BOLD_ITALIC = 3;")
    java.append("    /** Đơn vị của bảng số trên một em. */")
    java.append("    public static final double UPM = 2048.0;")
    java.append("    /** Chiều cao chữ thường (x), chữ hoa, phần trên và phần dưới đường chân chữ — theo em. */")
    java.append("    public static final double X_HEIGHT = %d / UPM, CAP_HEIGHT = %d / UPM, ASCENT = %d / UPM, DESCENT = %d / UPM;" % (xh, cap, asc, desc))
    java.append("    /** Bề rộng dùng cho ký tự không có trong bảng (bằng chữ số). */")
    java.append("    static final int DEFAULT_ADV = %d;" % adv[0][BASE.index("0")])
    java.append("    static final int N_ASCII = %d;" % len(ASCII))
    for name, tab in (("ADV", adv), ("LSB", lsb), ("RSB", rsb)):
        java.append("    static final short[][] %s = {" % name)
        for s in range(4):
            java.append("        " + jarr(tab[s]) + ("," if s < 3 else ""))
        java.append("    };")
    java.append("    /** Mép trên / mép dưới của mực từng ký tự trong bảng so với đường chân chữ (kiểu thường; dưới chân chữ là số âm). */")
    java.append("    static final short[] TOP = " + jarr(top) + ";")
    java.append("    static final short[] BOT = " + jarr(bot) + ";")
    java.append("    /** Ký tự ngoài ASCII (mã Unicode tăng dần) và chỉ số trong bảng của ký tự cùng bề rộng. */")
    java.append("    static final int[] MAP_CP = " + jarr(cps) + ";")
    java.append("    static final short[] MAP_IDX = " + jarr(idx) + ";")
    java.append("    /** Mép trên / dưới của mực từng ký tự trong MAP_CP (chữ có dấu cao / thấp hơn chữ gốc). */")
    java.append("    static final short[] MAP_TOP = " + jarr(mtop) + ";")
    java.append("    static final short[] MAP_BOT = " + jarr(mbot) + ";")
    java.append("""
    /** Chỉ số trong bảng của ký tự cp; -1: không có (dùng DEFAULT_ADV); -2: dấu kết hợp (bề rộng 0). */
    static int index(int cp) {
        if (cp >= 0x20 && cp < 0x7F) return cp - 0x20;
        if ((cp >= 0x300 && cp <= 0x36F) || cp == 0x200B || cp == 0x200C || cp == 0x200D || cp == 0xFEFF) return -2;
        if (cp == 0xA0) return 0;
        int lo = 0, hi = MAP_CP.length - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int v = MAP_CP[mid];
            if (v == cp) return MAP_IDX[mid];
            if (v < cp) lo = mid + 1;
            else hi = mid - 1;
        }
        return -1;
    }

    static int style(boolean bold, boolean italic) {
        return (bold ? 1 : 0) + (italic ? 2 : 0);
    }

    /** Tổng bước tiến của chuỗi s (đơn vị 1/2048 em). */
    public static int advanceUnits(String s, int style) {
        int sum = 0;
        int n = s.length();
        for (int i = 0; i < n; ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            int k = index(cp);
            if (k == -2) continue;
            sum += k < 0 ? DEFAULT_ADV : ADV[style][k];
        }
        return sum;
    }

    /** Số ký tự có bề rộng của chuỗi (không tính dấu kết hợp). */
    public static int glyphCount(String s) {
        int c = 0;
        int n = s.length();
        for (int i = 0; i < n; ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (index(cp) != -2) c++;
        }
        return c;
    }

    /** Lề trái của ký tự có bề rộng đầu tiên trong s (1/2048 em). */
    public static int lsbUnits(String s, int style) {
        int n = s.length();
        for (int i = 0; i < n; ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            int k = index(cp);
            if (k == -2) continue;
            return k < 0 ? 0 : LSB[style][k];
        }
        return 0;
    }

    /** Lề phải của ký tự có bề rộng cuối cùng trong s (1/2048 em). */
    public static int rsbUnits(String s, int style) {
        int last = 0;
        int n = s.length();
        for (int i = 0; i < n; ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            int k = index(cp);
            if (k == -2) continue;
            last = k < 0 ? 0 : RSB[style][k];
        }
        return last;
    }

    /** Bước tiến của dấu cách (1/2048 em). */
    public static int spaceUnits(int style) {
        return ADV[style][0];
    }

    /**
     * Phạm vi dọc của mực của chuỗi s so với đường chân chữ (1/2048 em): out[0] = mép trên cao nhất, out[1] = mép dưới
     * thấp nhất (âm nếu có nét dưới chân chữ). Ký tự không có trong bảng thì bỏ qua. Trả về số ký tự đã tính.
     */
    public static int verticalUnits(String s, int[] out) {
        int top = -100000, bot = 100000, c = 0;
        int n = s.length();
        for (int i = 0; i < n; ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            int t, b;
            if (cp > 0x20 && cp < 0x7F) {
                t = TOP[cp - 0x20];
                b = BOT[cp - 0x20];
            } else {
                int lo = 0, hi = MAP_CP.length - 1, at = -1;
                while (lo <= hi) {
                    int mid = (lo + hi) >>> 1;
                    int v = MAP_CP[mid];
                    if (v == cp) {
                        at = mid;
                        break;
                    }
                    if (v < cp) lo = mid + 1;
                    else hi = mid - 1;
                }
                if (at < 0) continue;
                t = MAP_TOP[at];
                b = MAP_BOT[at];
            }
            if (t == 0 && b == 0) continue;
            if (t > top) top = t;
            if (b < bot) bot = b;
            c++;
        }
        out[0] = c > 0 ? top : 0;
        out[1] = c > 0 ? bot : 0;
        return c;
    }
}""")
    with open(os.path.join(ROOT, "android/app/src/main/java/vn/quettailieu/scanner/core/WordFont.java"), "w", encoding="utf-8") as f:
        f.write("\n".join(java) + "\n")

    sw = []
    sw.append("import Foundation")
    sw.append("")
    sw.append("/// Số đo chữ Times New Roman (bước tiến, lề trái, lề phải của từng ký tự; đơn vị 1/2048 em) để ước lượng bề rộng")
    sw.append("/// dòng chữ khi xuất Word. FILE SINH TỰ ĐỘNG bằng tests/tools/gen_word_font.py — không sửa tay.")
    sw.append("/// Port 1-1 của core/WordFont.java (cùng bảng số).")
    sw.append("enum WordFont {")
    sw.append("    static let regular = 0, bold = 1, italic = 2, boldItalic = 3")
    sw.append("    /// Đơn vị của bảng số trên một em.")
    sw.append("    static let upm = 2048.0")
    sw.append("    /// Chiều cao chữ thường (x), chữ hoa, phần trên và phần dưới đường chân chữ — theo em.")
    sw.append("    static let xHeight = %d.0 / upm, capHeight = %d.0 / upm, ascent = %d.0 / upm, descent = %d.0 / upm" % (xh, cap, asc, desc))
    sw.append("    /// Bề rộng dùng cho ký tự không có trong bảng (bằng chữ số).")
    sw.append("    static let defaultAdv = %d" % adv[0][BASE.index("0")])
    sw.append("    static let nAscii = %d" % len(ASCII))
    for name, tab in (("adv", adv), ("lsb", lsb), ("rsb", rsb)):
        sw.append("    static let %s: [[Int16]] = [" % name)
        for s in range(4):
            sw.append("        " + sarr(tab[s]) + ("," if s < 3 else ""))
        sw.append("    ]")
    sw.append("    /// Mép trên / mép dưới của mực từng ký tự trong bảng so với đường chân chữ (kiểu thường; dưới chân chữ là số âm).")
    sw.append("    static let top: [Int16] = " + sarr(top))
    sw.append("    static let bot: [Int16] = " + sarr(bot))
    sw.append("    /// Ký tự ngoài ASCII (mã Unicode tăng dần) và chỉ số trong bảng của ký tự cùng bề rộng.")
    sw.append("    static let mapCp: [Int32] = " + sarr(cps))
    sw.append("    static let mapIdx: [Int16] = " + sarr(idx))
    sw.append("    /// Mép trên / dưới của mực từng ký tự trong mapCp (chữ có dấu cao / thấp hơn chữ gốc).")
    sw.append("    static let mapTop: [Int16] = " + sarr(mtop))
    sw.append("    static let mapBot: [Int16] = " + sarr(mbot))
    sw.append("""
    /// Chỉ số trong bảng của ký tự cp; -1: không có (dùng defaultAdv); -2: dấu kết hợp (bề rộng 0).
    static func index(_ cp: Int) -> Int {
        if cp >= 0x20 && cp < 0x7F { return cp - 0x20 }
        if (cp >= 0x300 && cp <= 0x36F) || cp == 0x200B || cp == 0x200C || cp == 0x200D || cp == 0xFEFF { return -2 }
        if cp == 0xA0 { return 0 }
        var lo = 0, hi = mapCp.count - 1
        while lo <= hi {
            let mid = (lo + hi) >> 1
            let v = Int(mapCp[mid])
            if v == cp { return Int(mapIdx[mid]) }
            if v < cp { lo = mid + 1 } else { hi = mid - 1 }
        }
        return -1
    }

    static func style(_ bold: Bool, _ italic: Bool) -> Int {
        return (bold ? 1 : 0) + (italic ? 2 : 0)
    }

    /// Tổng bước tiến của chuỗi s (đơn vị 1/2048 em).
    static func advanceUnits(_ s: String, _ style: Int) -> Int {
        var sum = 0
        for u in s.unicodeScalars {
            let k = index(Int(u.value))
            if k == -2 { continue }
            sum += k < 0 ? defaultAdv : Int(adv[style][k])
        }
        return sum
    }

    /// Số ký tự có bề rộng của chuỗi (không tính dấu kết hợp).
    static func glyphCount(_ s: String) -> Int {
        var c = 0
        for u in s.unicodeScalars where index(Int(u.value)) != -2 { c += 1 }
        return c
    }

    /// Lề trái của ký tự có bề rộng đầu tiên trong s (1/2048 em).
    static func lsbUnits(_ s: String, _ style: Int) -> Int {
        for u in s.unicodeScalars {
            let k = index(Int(u.value))
            if k == -2 { continue }
            return k < 0 ? 0 : Int(lsb[style][k])
        }
        return 0
    }

    /// Lề phải của ký tự có bề rộng cuối cùng trong s (1/2048 em).
    static func rsbUnits(_ s: String, _ style: Int) -> Int {
        var last = 0
        for u in s.unicodeScalars {
            let k = index(Int(u.value))
            if k == -2 { continue }
            last = k < 0 ? 0 : Int(rsb[style][k])
        }
        return last
    }

    /// Bước tiến của dấu cách (1/2048 em).
    static func spaceUnits(_ style: Int) -> Int {
        return Int(adv[style][0])
    }

    /// Phạm vi dọc của mực của chuỗi s so với đường chân chữ (1/2048 em): (mép trên cao nhất, mép dưới thấp nhất — âm
    /// nếu có nét dưới chân chữ, số ký tự đã tính). Ký tự không có trong bảng thì bỏ qua.
    static func verticalUnits(_ s: String) -> (top: Int, bot: Int, count: Int) {
        var topV = -100000, botV = 100000, c = 0
        for u in s.unicodeScalars {
            let cp = Int(u.value)
            var t = 0, b = 0
            if cp > 0x20 && cp < 0x7F {
                t = Int(top[cp - 0x20])
                b = Int(bot[cp - 0x20])
            } else {
                var lo = 0, hi = mapCp.count - 1, at = -1
                while lo <= hi {
                    let mid = (lo + hi) >> 1
                    let v = Int(mapCp[mid])
                    if v == cp {
                        at = mid
                        break
                    }
                    if v < cp { lo = mid + 1 } else { hi = mid - 1 }
                }
                if at < 0 { continue }
                t = Int(mapTop[at])
                b = Int(mapBot[at])
            }
            if t == 0 && b == 0 { continue }
            if t > topV { topV = t }
            if b < botV { botV = b }
            c += 1
        }
        return (c > 0 ? topV : 0, c > 0 ? botV : 0, c)
    }
}""")
    with open(os.path.join(ROOT, "ios/Sources/WordFont.swift"), "w", encoding="utf-8") as f:
        f.write("\n".join(sw) + "\n")


if __name__ == "__main__":
    main()
