import Foundation

/// Mô hình trang đã dàn để ghi ra Word (DocxWriter): mỗi dòng chữ là một đoạn đặt đúng vị trí, bảng kẻ là bảng Word thật,
/// hình / đường kẻ rời / chữ không xếp được vào dòng chảy thì neo theo trang. Đơn vị: point (1/72 inch), gốc trên-trái.
/// Port 1-1 của core/WordDoc.java (chỉ dùng Foundation). Các kiểu đều là final class để giữ ngữ nghĩa tham chiếu như
/// bản Java (DocLayout / DocxWriter sửa trường tại chỗ).
enum WordDoc {
    static let alignLeft = 0, alignCenter = 1, alignRight = 2
    static let tText = 0, tSpace = 1, tTab = 2

    /// Một mẩu của dòng chữ: chữ, dấu cách (có giãn riêng) hoặc tab tới vị trí tuyệt đối.
    final class Tok {
        var t = 0
        /// tText: nội dung.
        var text = ""
        /// tSpace: số dấu cách.
        var n = 1
        /// tText: giãn chữ (pt, cộng sau mỗi ký tự); tSpace: giãn thêm của dấu cách.
        var spacing = 0.0
        /// tTab: vị trí đích (pt, theo trang).
        var pos = 0.0
        var bold = false, italic = false, underline = false
        /// Màu chữ 0xRRGGBB.
        var color = 0
        /// Cỡ chữ (pt).
        var size = 12.0
    }

    /// Một dòng chữ = một đoạn Word có chiều cao dòng chính xác.
    final class Para {
        /// Mép trên và chiều cao hộp dòng; x..x1: phạm vi ngang của chữ; base: đường chân chữ.
        var top = 0.0, height = 0.0, x = 0.0, x1 = 0.0, base = 0.0
        var align = WordDoc.alignLeft
        var hasTab = false
        var toks = [Tok]()
        /// Kiểu chữ chủ đạo của dòng (dùng cho dấu kết đoạn).
        var size = 12.0
        var bold = false, italic = false
        var color = 0
    }

    final class Cell {
        var c0 = 0, c1 = 0, r0 = 0, r1 = 0
        var x0 = 0.0, y0 = 0.0, x1 = 0.0, y1 = 0.0
        /// Độ dày viền trên / trái / dưới / phải (pt); 0 = không kẻ.
        var bTop = 0.0, bLeft = 0.0, bBottom = 0.0, bRight = 0.0
        /// Lề trong trái / phải của ô (pt).
        var marL = 0.0, marR = 0.0
        var paras = [Para]()
    }

    final class Table {
        /// Đường lưới dọc / ngang (pt), tăng dần.
        var xs = [Double](), ys = [Double]()
        var cols = 0, rows = 0
        var cells = [Cell]()
        /// Ô chứa từng ô lưới: owner[hàng * cols + cột] = chỉ số trong cells.
        var owner = [Int]()
        /// Độ dày đường kẻ trên từng đường lưới ngang / dọc (0: đường lưới ảo).
        var hT = [Double](), vT = [Double]()
        var marL = 0.0, marR = 0.0
    }

    /// Một phần của dòng chảy dọc trang: dòng chữ hoặc bảng.
    final class Block {
        var para: Para?
        var table: Table?
        var top = 0.0, bottom = 0.0, x0 = 0.0, x1 = 0.0
        /// Bảng đứng cạnh bảng khác: không vào dòng chảy được, đặt trong hộp chữ đúng chỗ.
        var overlaps = false
    }

    /// Hình neo theo trang; data/ext do lớp nền tảng điền (cắt từ ảnh trang theo px0..py1 rồi nén JPEG / PNG).
    final class Pic {
        var x = 0.0, y = 0.0, w = 0.0, h = 0.0
        var behind = true
        /// Byte của file JPEG / PNG; nil hoặc rỗng: hình bị bỏ qua khi ghi.
        var data: [UInt8]?
        var ext = "jpeg"
        /// Vùng cắt trên ảnh trang (điểm ảnh, nửa mở).
        var px0 = 0, py0 = 0, px1 = 0, py1 = 0
    }

    /// Đường kẻ rời (không thuộc bảng).
    final class Rule {
        var horiz = false
        var pos = 0.0, a = 0.0, b = 0.0, t = 0.0
    }

    /// Dòng chữ không xếp được vào dòng chảy (chồng lên dòng khác, nằm cạnh bảng): hộp chữ neo theo trang.
    final class Box {
        var x = 0.0, y = 0.0, w = 0.0, h = 0.0
        var para = Para()
    }

    final class Page {
        var width = 0.0, height = 0.0
        var blocks = [Block]()
        var pics = [Pic]()
        var rules = [Rule]()
        var boxes = [Box]()
        // ---- lề trang (DocxWriter.planMargins điền)
        var left = 36.0, right = 36.0, top = 36.0, bottom = 36.0, extraHeight = 0.0
        var flow = false, ownLeft = false, ownRight = false
        var minX = 0.0, maxX = 0.0, first = 0.0, room = 0.0
    }
}

// sin / cos của thư viện toán hệ thống, gọi qua tên riêng để không lẫn với JavaNum.sin / JavaNum.cos
private func systemSin(_ x: Double) -> Double {
    return sin(x)
}

private func systemCos(_ x: Double) -> Double {
    return cos(x)
}

/// Phép tính số / chuỗi theo đúng quy tắc của Java, dùng chung cho DocxWriter và DocLayout để bản Swift cho kết quả trùng
/// từng bit với bản Java: ép số thực -> số nguyên (NaN -> 0, tràn -> giá trị biên) thay vì Int(x) (dừng chương trình),
/// Math.min / max (NaN lan truyền, -0.0 < 0.0), Math.round (nửa lên), Arrays.sort trên double (NaN xếp cuối), sắp xếp ổn
/// định, String.trim (chỉ cắt ký tự <= U+0020), StrictMath.atan / sin / cos (fdlibm — kết quả không phụ thuộc thư viện
/// toán của từng nền tảng).
enum JavaNum {
    // MARK: ép kiểu

    /// (long) v của Java: cắt về phía 0, NaN -> 0, ngoài khoảng long -> giá trị biên.
    @inline(__always) static func toLong(_ v: Double) -> Int {
        if v.isNaN { return 0 }
        if v >= 9223372036854775808.0 { return Int.max }
        if v <= -9223372036854775808.0 { return Int.min }
        return Int(v)
    }

    /// (int) v của Java (số thực double): cắt về phía 0, NaN -> 0, ngoài khoảng int -> giá trị biên.
    @inline(__always) static func toInt(_ v: Double) -> Int {
        if v.isNaN { return 0 }
        if v >= 2147483647.0 { return 2147483647 }
        if v <= -2147483648.0 { return -2147483648 }
        return Int(v)
    }

    /// (int) v của Java (số thực float).
    @inline(__always) static func toInt(_ v: Float) -> Int {
        if v.isNaN { return 0 }
        if v >= 2147483648.0 { return 2147483647 }
        if v <= -2147483648.0 { return -2147483648 }
        return Int(v)
    }

    /// (int) x của Java với x kiểu long: giữ 32 bit thấp (quay vòng).
    @inline(__always) static func wrap32(_ v: Int) -> Int {
        return Int(Int32(truncatingIfNeeded: v))
    }

    /// Math.round(double) của Java: floor(v + 1/2) tính chính xác, kết quả long (NaN -> 0, tràn -> biên).
    @inline(__always) static func round(_ v: Double) -> Int {
        let f = v.rounded(.down)
        return toLong(v - f >= 0.5 ? f + 1.0 : f)
    }

    /// Math.round(float) của Java: floor(v + 1/2) tính chính xác, kết quả int (NaN -> 0, tràn -> biên).
    @inline(__always) static func round(_ v: Float) -> Int {
        let f = v.rounded(.down)
        return toInt(v - f >= 0.5 ? f + 1.0 : f)
    }

    // MARK: Math.min / Math.max

    @inline(__always) static func min(_ a: Double, _ b: Double) -> Double {
        if a != a { return a }
        if a == 0.0 && b == 0.0 && b.sign == .minus { return b }
        return a <= b ? a : b
    }

    @inline(__always) static func max(_ a: Double, _ b: Double) -> Double {
        if a != a { return a }
        if a == 0.0 && b == 0.0 && a.sign == .minus { return b }
        return a >= b ? a : b
    }

    @inline(__always) static func min(_ a: Float, _ b: Float) -> Float {
        if a != a { return a }
        if a == 0.0 && b == 0.0 && b.sign == .minus { return b }
        return a <= b ? a : b
    }

    @inline(__always) static func max(_ a: Float, _ b: Float) -> Float {
        if a != a { return a }
        if a == 0.0 && b == 0.0 && a.sign == .minus { return b }
        return a >= b ? a : b
    }

    // MARK: sắp xếp

    /// Thứ tự của Double.compare: -0.0 < 0.0, NaN lớn hơn mọi số (kể cả vô cực).
    @inline(__always) static func less(_ a: Double, _ b: Double) -> Bool {
        if a < b { return true }
        if a > b { return false }
        let x = a.isNaN ? Int64(0x7ff8000000000000) : Int64(bitPattern: a.bitPattern)
        let y = b.isNaN ? Int64(0x7ff8000000000000) : Int64(bitPattern: b.bitPattern)
        return x < y
    }

    /// java.util.Arrays.sort(double[]).
    static func sort(_ a: inout [Double]) {
        a.sort(by: less)
    }

    /// Double.equals của Java (so theo bit, mọi NaN bằng nhau, 0.0 khác -0.0) — dùng cho ArrayList<Double>.contains.
    @inline(__always) static func same(_ a: Double, _ b: Double) -> Bool {
        if a.isNaN || b.isNaN { return a.isNaN && b.isNaN }
        return a.bitPattern == b.bitPattern
    }

    /// Collections.sort với Comparator: sắp xếp ỔN ĐỊNH (phần tử bằng nhau giữ thứ tự cũ). cmp trả về âm / 0 / dương
    /// như Comparator.compare.
    static func stableSort<T>(_ a: inout [T], _ cmp: (T, T) -> Int) {
        if a.count < 2 { return }
        let sorted = a.enumerated().sorted { x, y in
            let c = cmp(x.element, y.element)
            return c != 0 ? c < 0 : x.offset < y.offset
        }
        a = sorted.map { $0.element }
    }

    // MARK: chuỗi

    /// String.trim của Java: chỉ cắt các ký tự <= U+0020 ở hai đầu.
    static func trim(_ s: String) -> String {
        let u = s.unicodeScalars
        var a = u.startIndex, b = u.endIndex
        while a < b && u[a].value <= 0x20 { a = u.index(after: a) }
        while b > a && u[u.index(before: b)].value <= 0x20 { b = u.index(before: b) }
        if a == u.startIndex && b == u.endIndex { return s }
        return String(u[a..<b])
    }

    // MARK: StrictMath (fdlibm)

    @inline(__always) private static func hi(_ x: Double) -> Int32 {
        return Int32(truncatingIfNeeded: x.bitPattern >> 32)
    }

    private static let atanHi: [Double] = [4.63647609000806093515e-01, 7.85398163397448278999e-01, 9.82793723247329054082e-01,
                                           1.57079632679489655800e+00]
    private static let atanLo: [Double] = [2.26987774529616870924e-17, 3.06161699786838301793e-17, 1.39033110312309984516e-17,
                                           6.12323399573676603587e-17]
    private static let aT: [Double] = [3.33333333333329318027e-01, -1.99999999998764832476e-01, 1.42857142725034663711e-01,
                                       -1.11111104054623557880e-01, 9.09088713343650656196e-02, -7.69187620504482999495e-02,
                                       6.66107313738753120669e-02, -5.83357013379057348645e-02, 4.97687799461593236017e-02,
                                       -3.65315727442169155270e-02, 1.62858201153657823623e-02]

    /// StrictMath.atan (fdlibm s_atan.c).
    static func atan(_ v: Double) -> Double {
        var x = v
        let hx = hi(x)
        let ix = hx & 0x7fffffff
        var id = -1
        if ix >= 0x44100000 {
            // |x| >= 2^66
            if x.isNaN { return x + x }
            return hx > 0 ? atanHi[3] + atanLo[3] : -atanHi[3] - atanLo[3]
        }
        if ix < 0x3fdc0000 {
            // |x| < 0.4375
            if ix < 0x3e200000 { return x }
        } else {
            x = abs(x)
            if ix < 0x3ff30000 {
                if ix < 0x3fe60000 {
                    id = 0
                    x = (2.0 * x - 1.0) / (2.0 + x)
                } else {
                    id = 1
                    x = (x - 1.0) / (x + 1.0)
                }
            } else {
                if ix < 0x40038000 {
                    id = 2
                    x = (x - 1.5) / (1.0 + 1.5 * x)
                } else {
                    id = 3
                    x = -1.0 / x
                }
            }
        }
        let z = x * x
        let w = z * z
        let s1 = z * (aT[0] + w * (aT[2] + w * (aT[4] + w * (aT[6] + w * (aT[8] + w * aT[10])))))
        let s2 = w * (aT[1] + w * (aT[3] + w * (aT[5] + w * (aT[7] + w * aT[9]))))
        if id < 0 { return x - x * (s1 + s2) }
        let r = atanHi[id] - ((x * (s1 + s2) - atanLo[id]) - x)
        return hx < 0 ? -r : r
    }

    /// StrictMath.sin cho |x| <= pi/4 (fdlibm __kernel_sin với y = 0); ngoài khoảng đó dùng hàm của hệ thống (lõi Word chỉ
    /// gọi với góc nghiêng trang |x| < 0,14).
    static func sin(_ x: Double) -> Double {
        let ix = hi(x) & 0x7fffffff
        if ix > 0x3fe921fb { return systemSin(x) }
        if ix < 0x3e400000 { return x }
        let z = x * x
        let v = z * x
        let r = 8.33333333332248946124e-03 + z * (-1.98412698298579493134e-04 + z * (2.75573137070700676789e-06
            + z * (-2.50507602534068634195e-08 + z * 1.58969099521155010221e-10)))
        return x + v * (-1.66666666666666324348e-01 + z * r)
    }

    /// StrictMath.cos cho |x| <= pi/4 (fdlibm __kernel_cos với y = 0); ngoài khoảng đó dùng hàm của hệ thống.
    static func cos(_ x: Double) -> Double {
        let ix = hi(x) & 0x7fffffff
        if ix > 0x3fe921fb { return systemCos(x) }
        if ix < 0x3e400000 { return 1.0 }
        let z = x * x
        let r = z * (4.16666666666666019037e-02 + z * (-1.38888888888741095749e-03 + z * (2.48015872894767294178e-05
            + z * (-2.75573143513906633035e-07 + z * (2.08757232129817482790e-09 + z * -1.13596475577881948265e-11)))))
        if ix < 0x3fd33333 { return 1.0 - (0.5 * z - z * r) }
        let qx: Double = ix > 0x3fe90000 ? 0.28125 : Double(bitPattern: UInt64(UInt32(bitPattern: ix - 0x00200000)) << 32)
        let hz = 0.5 * z - qx
        let a = 1.0 - qx
        return a - (hz - z * r)
    }
}
