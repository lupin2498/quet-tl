import UIKit

/// Vẽ lớp "Đánh dấu" (chữ ký, văn bản, nét vẽ, khảm, hình mờ) và tiêu đề trang.
/// Dùng chung cho ảnh kết quả (độ phân giải đầy đủ) và màn hình chỉnh sửa (ảnh xem trước), nên mọi kích thước
/// đều tính theo bề ngang ảnh W. Các hàm draw vẽ lên ngữ cảnh UIKit hiện tại (gốc toạ độ trên-trái)
/// trong hệ toạ độ ảnh W x H.
final class MarkRenderer {
    private let dir: URL
    private var signs = [String: UIImage]()
    private var missing = Set<String>()
    private var mosaicSmall: UIImage?
    private var mosaicSrc: CGImage?

    /// dir: thư mục tài liệu (chứa file chữ ký).
    init(dir: URL) {
        self.dir = dir
    }

    /// Vẽ toàn bộ đánh dấu. base: ảnh nền (đã lọc, chưa có đánh dấu) để làm khảm.
    func draw(_ marks: [Mark], w: CGFloat, h: CGFloat, base: CGImage?) {
        for m in marks { drawOne(m, w: w, h: h, base: base) }
    }

    /// Vẽ đánh dấu lên ngữ cảnh bitmap (gốc toạ độ CoreGraphics ở dưới-trái) của ảnh width x height.
    func render(into ctx: CGContext, width: Int, height: Int, marks: [Mark], base: CGImage?) {
        ctx.saveGState()
        ctx.translateBy(x: 0, y: CGFloat(height))
        ctx.scaleBy(x: 1, y: -1)
        UIGraphicsPushContext(ctx)
        draw(marks, w: CGFloat(width), h: CGFloat(height), base: base)
        UIGraphicsPopContext()
        ctx.restoreGState()
    }

    func drawOne(_ m: Mark, w: CGFloat, h: CGFloat, base: CGImage?) {
        guard let ctx = UIGraphicsGetCurrentContext() else { return }
        switch m.t {
        case Mark.brush:
            guard let path = MarkRenderer.strokePath(m.p, w, h) else { return }
            ctx.saveGState()
            ctx.setStrokeColor(Edits.color(m.c).cgColor)
            ctx.setLineWidth(max(1, CGFloat(m.w) * w))
            ctx.setLineCap(.round)
            ctx.setLineJoin(.round)
            ctx.addPath(path)
            ctx.strokePath()
            ctx.restoreGState()
        case Mark.mosaic:
            guard let b = base, let path = MarkRenderer.strokePath(m.p, w, h), let small = mosaicImage(b) else { return }
            ctx.saveGState()
            ctx.setLineWidth(max(2, CGFloat(m.w) * w))
            ctx.setLineCap(.round)
            ctx.setLineJoin(.round)
            ctx.addPath(path)
            ctx.replacePathWithStrokedPath()
            ctx.clip()
            ctx.interpolationQuality = .none
            small.draw(in: CGRect(x: 0, y: 0, width: w, height: h))
            ctx.restoreGState()
        case Mark.text:
            drawText(ctx, m, w, h)
        case Mark.sign:
            drawSign(ctx, m, w, h)
        case Mark.watermark:
            drawWatermark(ctx, m, w, h)
        default:
            break
        }
    }

    private static func strokePath(_ pts: [Double]?, _ w: CGFloat, _ h: CGFloat) -> CGPath? {
        guard let p = pts, p.count >= 2 else { return nil }
        let path = CGMutablePath()
        let x0 = CGFloat(p[0]) * w, y0 = CGFloat(p[1]) * h
        path.move(to: CGPoint(x: x0, y: y0))
        if p.count < 4 {
            path.addLine(to: CGPoint(x: x0 + 0.01, y: y0 + 0.01))
        } else {
            var i = 2
            while i + 1 < p.count {
                path.addLine(to: CGPoint(x: CGFloat(p[i]) * w, y: CGFloat(p[i + 1]) * h))
                i += 2
            }
        }
        return path
    }

    /// Ảnh khảm: thu nhỏ ảnh nền (lấy trung bình) rồi phóng lại không nội suy -> các ô vuông.
    private func mosaicImage(_ base: CGImage) -> UIImage? {
        if let s = mosaicSmall, mosaicSrc === base { return s }
        let bw = CGFloat(base.width), bh = CGFloat(base.height)
        let blk = max(bw, bh) / 72
        let sw = max(1, Int((bw / blk).rounded())), sh = max(1, Int((bh / blk).rounded()))
        guard let c = CGContext(data: nil, width: sw, height: sh, bitsPerComponent: 8, bytesPerRow: 0,
                                space: CGColorSpaceCreateDeviceRGB(),
                                bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return nil }
        c.interpolationQuality = .high
        c.draw(base, in: CGRect(x: 0, y: 0, width: sw, height: sh))
        guard let img = c.makeImage() else { return nil }
        let ui = UIImage(cgImage: img)
        mosaicSmall = ui
        mosaicSrc = base
        return ui
    }

    private static func lines(_ s: String?) -> [String] {
        return (s ?? "").components(separatedBy: "\n")
    }

    /// Kích thước khối chữ theo pixel ảnh.
    func textBlock(_ m: Mark, _ w: CGFloat) -> CGSize {
        let lh = max(4, CGFloat(m.h) * w)
        let font = UIFont.systemFont(ofSize: lh * 0.8)
        var bw: CGFloat = 0
        let ls = MarkRenderer.lines(m.s)
        for l in ls {
            bw = max(bw, (l as NSString).size(withAttributes: [NSAttributedString.Key.font: font]).width)
        }
        return CGSize(width: max(bw, lh * 0.5), height: lh * CGFloat(ls.count))
    }

    private func drawText(_ ctx: CGContext, _ m: Mark, _ w: CGFloat, _ h: CGFloat) {
        guard let s = m.s, !s.isEmpty else { return }
        let b = textBlock(m, w)
        let lh = max(4, CGFloat(m.h) * w)
        let font = UIFont.systemFont(ofSize: lh * 0.8)
        let attrs: [NSAttributedString.Key: Any] = [.font: font, .foregroundColor: Edits.color(m.c)]
        ctx.saveGState()
        ctx.translateBy(x: CGFloat(m.x) * w, y: CGFloat(m.y) * h)
        ctx.rotate(by: CGFloat(m.a) * CGFloat.pi / 180)
        let ls = MarkRenderer.lines(s)
        for (i, l) in ls.enumerated() {
            let y = -b.height / 2 + lh * CGFloat(i) + (lh - font.lineHeight) / 2
            (l as NSString).draw(at: CGPoint(x: -b.width / 2, y: y), withAttributes: attrs)
        }
        ctx.restoreGState()
    }

    func sign(_ file: String?) -> UIImage? {
        guard let f = file, !f.isEmpty else { return nil }
        if let img = signs[f] { return img }
        if missing.contains(f) { return nil }
        // tên file do ứng dụng đặt; vẫn chặn đường dẫn lạ
        if !f.contains("/") && !f.contains(".."), let img = UIImage(contentsOfFile: dir.appendingPathComponent(f).path) {
            signs[f] = img
            return img
        }
        missing.insert(f)
        return nil
    }

    /// Kích thước chữ ký theo pixel ảnh.
    func signBlock(_ m: Mark, _ w: CGFloat) -> CGSize {
        let sw = max(8, CGFloat(m.h) * w)
        var ratio: CGFloat = 0.4
        if let img = sign(m.f), img.size.width > 0 { ratio = img.size.height / img.size.width }
        return CGSize(width: sw, height: sw * ratio)
    }

    private func drawSign(_ ctx: CGContext, _ m: Mark, _ w: CGFloat, _ h: CGFloat) {
        guard let img = sign(m.f) else { return }
        let s = signBlock(m, w)
        ctx.saveGState()
        ctx.translateBy(x: CGFloat(m.x) * w, y: CGFloat(m.y) * h)
        ctx.rotate(by: CGFloat(m.a) * CGFloat.pi / 180)
        ctx.interpolationQuality = .high
        img.draw(in: CGRect(x: -s.width / 2, y: -s.height / 2, width: s.width, height: s.height))
        ctx.restoreGState()
    }

    private func drawWatermark(_ ctx: CGContext, _ m: Mark, _ w: CGFloat, _ h: CGFloat) {
        guard let raw = m.s, !raw.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        let s = raw.replacingOccurrences(of: "\n", with: " ") as NSString
        let lh = max(6, CGFloat(m.h) * w)
        let font = UIFont.systemFont(ofSize: lh)
        let attrs: [NSAttributedString.Key: Any] = [.font: font, .foregroundColor: Edits.color(m.c)]
        let tw = max(lh, s.size(withAttributes: attrs).width)
        let stepX = tw + lh * 2.2, stepY = lh * 4.4
        let diag = hypot(w, h)
        ctx.saveGState()
        ctx.clip(to: CGRect(x: 0, y: 0, width: w, height: h))
        ctx.translateBy(x: w / 2, y: h / 2)
        ctx.rotate(by: -30 * CGFloat.pi / 180)
        ctx.translateBy(x: -w / 2, y: -h / 2)
        var row = 0
        var y = h / 2 - diag / 2
        while y < h / 2 + diag / 2 + stepY {
            let shift: CGFloat = row % 2 == 0 ? 0 : stepX / 2
            var x = w / 2 - diag / 2 - shift
            while x < w / 2 + diag / 2 {
                s.draw(at: CGPoint(x: x, y: y - font.ascender), withAttributes: attrs)
                x += stepX
            }
            y += stepY
            row += 1
        }
        ctx.restoreGState()
    }

    // MARK: tiêu đề trang

    /// Ảnh mới có in tiêu đề trang (vẽ trên ảnh đã xoay); nil nếu không dựng được.
    static func withHeader(_ cg: CGImage, _ hdr: String) -> CGImage? {
        let w = CGFloat(cg.width), h = CGFloat(cg.height)
        guard let ctx = CGContext(data: nil, width: cg.width, height: cg.height, bitsPerComponent: 8, bytesPerRow: 0,
                                  space: CGColorSpaceCreateDeviceRGB(),
                                  bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return nil }
        ctx.draw(cg, in: CGRect(x: 0, y: 0, width: w, height: h))
        ctx.translateBy(x: 0, y: h)
        ctx.scaleBy(x: 1, y: -1)
        UIGraphicsPushContext(ctx)
        drawHeader(hdr, w, h)
        UIGraphicsPopContext()
        return ctx.makeImage()
    }

    /// Dòng 1 = chữ ở lề trên (giữa), dòng 2 = số trang ở lề dưới (giữa).
    static func drawHeader(_ hdr: String, _ w: CGFloat, _ h: CGFloat) {
        let parts = hdr.components(separatedBy: "\n")
        let title = parts.first ?? ""
        let num = parts.count > 1 ? parts[1] : ""
        let color = UIColor(rgb: 0x3C3C3C)
        let size = max(8, max(w, h) * 0.0155)
        let margin = max(w, h) * 0.012
        if !title.isEmpty {
            var font = UIFont.systemFont(ofSize: size)
            var tw = (title as NSString).size(withAttributes: [NSAttributedString.Key.font: font]).width
            let maxW = w * 0.92
            if tw > maxW {
                // chữ dài hơn bề ngang trang: co lại cho vừa
                font = UIFont.systemFont(ofSize: max(6, size * maxW / tw))
                tw = (title as NSString).size(withAttributes: [NSAttributedString.Key.font: font]).width
            }
            let attrs: [NSAttributedString.Key: Any] = [.font: font, .foregroundColor: color]
            (title as NSString).draw(at: CGPoint(x: (w - tw) / 2, y: margin), withAttributes: attrs)
        }
        if !num.isEmpty {
            let font = UIFont.systemFont(ofSize: size)
            let attrs: [NSAttributedString.Key: Any] = [.font: font, .foregroundColor: color]
            let sz = (num as NSString).size(withAttributes: attrs)
            (num as NSString).draw(at: CGPoint(x: (w - sz.width) / 2, y: h - margin - sz.height), withAttributes: attrs)
        }
    }
}
