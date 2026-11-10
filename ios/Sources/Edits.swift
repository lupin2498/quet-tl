import UIKit

/// Một thao tác "Xóa thông minh": áp lên ảnh đã nắn phẳng TRƯỚC khi lọc.
/// Toạ độ chuẩn hoá 0..1 theo ảnh đã nắn phẳng (chưa xoay); bề rộng tính theo bề ngang ảnh.
struct EraseOp: Codable, Equatable {
    /// Retouch.pen / rect / magic / text / hand
    var k: Int
    var w: Double
    /// x0,y0,x1,y1,... (rect: 4 góc)
    var p: [Double]

    var op: Retouch.Op {
        return Retouch.Op(kind: k, width: Float(w), pts: p.map { Float($0) })
    }
}

/// Một "Đánh dấu": chữ ký, văn bản, nét vẽ, khảm, hình mờ — vẽ SAU khi lọc, trước khi xoay.
struct Mark: Codable, Equatable {
    static let brush = 0, mosaic = 1, text = 2, sign = 3, watermark = 4

    var t: Int
    /// Màu ARGB.
    var c: UInt32 = 0xFF1A1A1A
    /// Bề rộng nét (brush / mosaic) / bề ngang ảnh.
    var w: Double = 0
    /// Nét vẽ: x0,y0,x1,y1,...
    var p: [Double]?
    /// Nội dung chữ / hình mờ.
    var s: String?
    /// Tên file chữ ký trong thư mục tài liệu.
    var f: String?
    /// Tâm (text / sign).
    var x: Double = 0.5
    var y: Double = 0.5
    /// text / watermark: chiều cao dòng chữ / bề ngang ảnh; sign: bề ngang chữ ký / bề ngang ảnh.
    var h: Double = 0
    /// Góc quay (độ, theo chiều kim đồng hồ) trong hệ ảnh chưa xoay.
    var a: Int = 0

    var movable: Bool { t == Mark.text || t == Mark.sign }
}

/// Tiêu đề trang của tài liệu: dòng chữ ở lề trên + số trang ở lề dưới.
struct PageHeader: Codable, Equatable {
    var text: String
    var num: Bool

    var isEmpty: Bool {
        return text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && !num
    }

    /// Nội dung in trên trang thứ index (từ 0) trong tổng số count trang; nil nếu không có gì.
    func resolve(_ index: Int, _ count: Int) -> String? {
        if isEmpty { return nil }
        return text.trimmingCharacters(in: .whitespacesAndNewlines) + "\n" + (num ? "\(index + 1)/\(count)" : "")
    }
}

enum Edits {
    static let scale = 100000.0

    /// Làm tròn tới 1/100000 và chặn giá trị bất thường (JSONEncoder không ghi được NaN / vô cực;
    /// điểm nằm xa ngoài trang sau khi cắt lại không được làm tràn số).
    static func q(_ v: Double) -> Double {
        if !v.isFinite { return 0 }
        return (min(20, max(-20, v)) * scale).rounded() / scale
    }

    static func q(_ pts: [Double]) -> [Double] {
        return pts.map { q($0) }
    }

    private static func i(_ v: Double) -> String {
        return String(Int((q(v) * scale).rounded()))
    }

    private static func ints(_ pts: [Double]) -> String {
        var parts = [String]()
        parts.reserveCapacity(pts.count)
        for v in pts { parts.append(i(v)) }
        return parts.joined(separator: ",")
    }

    /// Chuỗi chuẩn tắc của danh sách thao tác xoá (tham gia vào tên file ảnh kết quả).
    static func key(_ ops: [EraseOp]) -> String {
        var parts = [String]()
        for op in ops {
            parts.append(String(op.k) + ":" + i(op.w) + ":" + ints(op.p))
        }
        return parts.joined(separator: ";")
    }

    static func key(_ marks: [Mark]) -> String {
        var parts = [String]()
        for m in marks {
            var s = String(m.t) + ":" + String(m.c) + ":" + i(m.w) + ":" + i(m.x) + ":" + i(m.y)
            s += ":" + i(m.h) + ":" + String(m.a) + ":" + ints(m.p ?? [])
            s += ":" + (m.f ?? "") + ":" + (m.s ?? "")
            parts.append(s)
        }
        return parts.joined(separator: ";")
    }

    static func color(_ argb: UInt32) -> UIColor {
        return UIColor(red: CGFloat((argb >> 16) & 0xFF) / 255.0, green: CGFloat((argb >> 8) & 0xFF) / 255.0,
                       blue: CGFloat(argb & 0xFF) / 255.0, alpha: CGFloat((argb >> 24) & 0xFF) / 255.0)
    }

    /// Sau khi cắt lại trang: dời các chỉnh sửa theo vùng cắt mới để chúng vẫn nằm đúng chỗ trên giấy.
    static func remap(_ p: inout Page, from oldQuad: [Double]?, to newQuad: [Double]?) {
        guard let o = oldQuad, let n = newQuad, o.count == 8, n.count == 8 else { return }
        if (p.erase ?? []).isEmpty && (p.marks ?? []).isEmpty { return }
        var same = true
        for k in 0..<8 where abs(o[k] - n[k]) > 0.25 { same = false }
        if same { return }
        let of = o.map { Float($0) }, nf = n.map { Float($0) }
        let k = Double(Retouch.remapScale(of, nf))
        func move(_ pts: [Double]) -> [Double] {
            return q(Retouch.remap(pts.map { Float($0) }, of, nf).map { Double($0) })
        }
        if var ops = p.erase {
            for j in 0..<ops.count {
                ops[j].p = move(ops[j].p)
                ops[j].w = q(ops[j].w * k)
            }
            p.erase = ops
        }
        if var ms = p.marks {
            for j in 0..<ms.count {
                if let pts = ms[j].p { ms[j].p = move(pts) }
                let c = move([ms[j].x, ms[j].y])
                ms[j].x = c[0]
                ms[j].y = c[1]
                ms[j].w = q(ms[j].w * k)
                if ms[j].t != Mark.watermark { ms[j].h = q(ms[j].h * k) }
            }
            p.marks = ms
        }
    }
}
