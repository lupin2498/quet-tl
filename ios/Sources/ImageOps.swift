import CoreImage
import ImageIO
import UIKit
import Vision

/// Xử lý ảnh: tìm mép trang (Vision), nắn phẳng (CoreImage), lọc, xoay, lưu.
enum ImageOps {
    static let maxSrc: CGFloat = 4096
    static let maxOut: Double = 3508
    static let ciContext = CIContext(options: [.cacheIntermediates: false])

    struct OpError: LocalizedError {
        let message: String
        var errorDescription: String? { message }
    }

    /// Ảnh hướng .up, cạnh dài không quá maxDim, tỷ lệ 1 điểm = 1 pixel.
    static func normalized(_ image: UIImage, maxDim: CGFloat) -> UIImage {
        let pxW = image.size.width * image.scale, pxH = image.size.height * image.scale
        guard pxW > 0, pxH > 0 else { return image }
        let k = min(1, maxDim / max(pxW, pxH))
        if image.imageOrientation == .up && k >= 0.999 && image.scale == 1 && image.cgImage != nil {
            return image
        }
        let target = CGSize(width: max(1, (pxW * k).rounded()), height: max(1, (pxH * k).rounded()))
        let fmt = UIGraphicsImageRendererFormat()
        fmt.scale = 1
        fmt.opaque = true
        fmt.preferredRange = .standard
        let r = UIGraphicsImageRenderer(size: target, format: fmt)
        return r.image { _ in
            image.draw(in: CGRect(origin: .zero, size: target))
        }
    }

    static func loadCG(_ url: URL) -> CGImage? {
        guard let src = CGImageSourceCreateWithURL(url as CFURL, nil) else { return nil }
        let opts: [CFString: Any] = [kCGImageSourceShouldCacheImmediately: true]
        return CGImageSourceCreateImageAtIndex(src, 0, opts as CFDictionary)
    }

    static func thumbnail(_ url: URL, maxPixel: CGFloat) -> UIImage? {
        guard let src = CGImageSourceCreateWithURL(url as CFURL, nil) else { return nil }
        let opts: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceShouldCacheImmediately: true,
            kCGImageSourceThumbnailMaxPixelSize: Int(maxPixel)
        ]
        guard let cg = CGImageSourceCreateThumbnailAtIndex(src, 0, opts as CFDictionary) else { return nil }
        return UIImage(cgImage: cg)
    }

    static func scaledCG(_ cg: CGImage, maxDim: CGFloat) -> CGImage {
        let w = CGFloat(cg.width), h = CGFloat(cg.height)
        let k = min(1, maxDim / max(w, h))
        if k >= 0.999 { return cg }
        let tw = max(1, Int((w * k).rounded())), th = max(1, Int((h * k).rounded()))
        guard let ctx = CGContext(data: nil, width: tw, height: th, bitsPerComponent: 8, bytesPerRow: 0,
                                  space: CGColorSpaceCreateDeviceRGB(),
                                  bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return cg }
        ctx.interpolationQuality = .high
        ctx.draw(cg, in: CGRect(x: 0, y: 0, width: tw, height: th))
        return ctx.makeImage() ?? cg
    }

    /// Điểm ảnh RGBX 8 bit (R ở byte 0), cạnh dài không quá maxDim.
    static func rgbaPixels(_ cg: CGImage, maxDim: CGFloat) -> (data: [UInt8], w: Int, h: Int)? {
        let W = CGFloat(cg.width), H = CGFloat(cg.height)
        guard W > 0, H > 0 else { return nil }
        let k = min(1, maxDim / max(W, H))
        let w = max(1, Int((W * k).rounded())), h = max(1, Int((H * k).rounded()))
        var data = [UInt8](repeating: 0, count: w * h * 4)
        let ok: Bool = data.withUnsafeMutableBytes { buf -> Bool in
            guard let ctx = CGContext(data: buf.baseAddress, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4,
                                      space: CGColorSpaceCreateDeviceRGB(),
                                      bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue) else { return false }
            ctx.interpolationQuality = .high
            ctx.draw(cg, in: CGRect(x: 0, y: 0, width: w, height: h))
            return true
        }
        return ok ? (data, w, h) : nil
    }

    struct Detection {
        var quad: [CGPoint]?
        var source: String
        var score: Double
    }

    /// Bộ tìm mép giống bản Android (DocDetector). Toạ độ pixel của `cg`.
    static func docDetector(_ cg: CGImage) -> (quad: [CGPoint], score: Double, borders: Int)? {
        guard let px = rgbaPixels(cg, maxDim: 1024) else { return nil }
        let r: DocDetector.Result? = px.data.withUnsafeBufferPointer { buf -> DocDetector.Result? in
            guard let base = buf.baseAddress else { return nil }
            return DocDetector.detect(rgba: base, width: px.w, height: px.h, bytesPerRow: px.w * 4)
        }
        guard let res = r, let q = res.quad, q.count == 8 else { return nil }
        let kx = CGFloat(cg.width) / CGFloat(px.w), ky = CGFloat(cg.height) / CGFloat(px.h)
        let pts = (0..<4).map { CGPoint(x: (CGFloat(q[2 * $0]) + 0.5) * kx - 0.5, y: (CGFloat(q[2 * $0 + 1]) + 0.5) * ky - 0.5) }
        let o = Geom.order(pts)
        guard Geom.isConvex(o) else { return nil }
        return (Geom.clamp(o, CGFloat(cg.width), CGFloat(cg.height)), Double(res.score), res.borders)
    }

    /// Tách vùng tài liệu bằng Vision (học máy). Toạ độ pixel của `cg`.
    static func visionSegment(_ cg: CGImage) -> (quad: [CGPoint], confidence: Double)? {
        let small = scaledCG(cg, maxDim: 1024)
        let sw = CGFloat(small.width), sh = CGFloat(small.height)
        let kx = CGFloat(cg.width) / sw, ky = CGFloat(cg.height) / sh
        let seg = VNDetectDocumentSegmentationRequest()
        let handler = VNImageRequestHandler(cgImage: small, orientation: .up, options: [:])
        guard (try? handler.perform([seg])) != nil, let obs = seg.results?.first else { return nil }
        let pts = [obs.topLeft, obs.topRight, obs.bottomRight, obs.bottomLeft].map {
            CGPoint(x: $0.x * sw * kx, y: (1 - $0.y) * sh * ky)
        }
        let q = Geom.order(pts)
        guard Geom.isConvex(q), Geom.area(q) > Double(cg.width * cg.height) * 0.1 else { return nil }
        return (Geom.clamp(q, CGFloat(cg.width), CGFloat(cg.height)), Double(obs.confidence))
    }

    /// Tìm 4 góc trang giấy: ưu tiên DocDetector khi chắc chắn (thấy đủ 4 mép), nếu không thì Vision,
    /// cuối cùng là kết quả yếu của DocDetector.
    static func detectDetailed(_ cg: CGImage) -> Detection {
        let dd = docDetector(cg)
        if let d = dd, d.borders == 0, d.score >= 0.3 {
            return Detection(quad: d.quad, source: "docdetector", score: d.score)
        }
        if let v = visionSegment(cg), v.confidence >= 0.6 {
            return Detection(quad: v.quad, source: "vision", score: v.confidence)
        }
        if let d = dd {
            return Detection(quad: d.quad, source: "docdetector-weak", score: d.score)
        }
        return Detection(quad: nil, source: "none", score: 0)
    }

    /// Tìm 4 góc trang giấy. Trả về toạ độ pixel của `cg` (gốc trên-trái), hoặc nil.
    static func detect(_ cg: CGImage) -> [CGPoint]? {
        return detectDetailed(cg).quad
    }

    /// Nắn phẳng tứ giác (toạ độ pixel gốc trên-trái) thành ảnh chữ nhật kích thước `size`.
    static func warp(_ cg: CGImage, quad: [CGPoint], size: CGSize) -> CGImage? {
        let h = CGFloat(cg.height)
        let input = CIImage(cgImage: cg)
        func vec(_ p: CGPoint) -> CIVector { CIVector(x: p.x, y: h - p.y) }
        guard let f = CIFilter(name: "CIPerspectiveCorrection") else { return nil }
        f.setValue(input, forKey: kCIInputImageKey)
        f.setValue(vec(quad[0]), forKey: "inputTopLeft")
        f.setValue(vec(quad[1]), forKey: "inputTopRight")
        f.setValue(vec(quad[2]), forKey: "inputBottomRight")
        f.setValue(vec(quad[3]), forKey: "inputBottomLeft")
        guard let out = f.outputImage else { return nil }
        let e = out.extent
        guard e.width > 1, e.height > 1, e.width.isFinite, e.height.isFinite else { return nil }
        let tw = max(16, size.width.rounded()), th = max(16, size.height.rounded())
        let moved = out.transformed(by: CGAffineTransform(translationX: -e.origin.x, y: -e.origin.y))
        let scaled = moved.transformed(by: CGAffineTransform(scaleX: tw / e.width, y: th / e.height))
        return ciContext.createCGImage(scaled, from: CGRect(x: 0, y: 0, width: tw, height: th))
    }

    /// Áp bộ lọc (Enhancer) lên ảnh.
    static func filtered(_ cg: CGImage, mode: Int) -> CGImage? {
        if mode == Enhancer.original { return cg }
        let w = cg.width, h = cg.height, bpr = w * 4
        guard let ctx = CGContext(data: nil, width: w, height: h, bitsPerComponent: 8, bytesPerRow: bpr,
                                  space: CGColorSpaceCreateDeviceRGB(),
                                  bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return nil }
        ctx.draw(cg, in: CGRect(x: 0, y: 0, width: w, height: h))
        guard let data = ctx.data else { return nil }
        let px = data.bindMemory(to: UInt8.self, capacity: bpr * h)
        Enhancer.apply(px, width: w, height: h, bytesPerRow: bpr, mode: mode)
        return ctx.makeImage()
    }

    /// Xoay theo chiều kim đồng hồ `degrees` (0/90/180/270).
    static func rotated(_ cg: CGImage, degrees: Int) -> CGImage? {
        let d = ((degrees % 360) + 360) % 360
        if d == 0 { return cg }
        let w = cg.width, h = cg.height
        let nw = d == 180 ? w : h, nh = d == 180 ? h : w
        guard let ctx = CGContext(data: nil, width: nw, height: nh, bitsPerComponent: 8, bytesPerRow: 0,
                                  space: CGColorSpaceCreateDeviceRGB(),
                                  bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return nil }
        ctx.translateBy(x: CGFloat(nw) / 2, y: CGFloat(nh) / 2)
        ctx.rotate(by: -CGFloat(d) * .pi / 180)
        ctx.draw(cg, in: CGRect(x: -CGFloat(w) / 2, y: -CGFloat(h) / 2, width: CGFloat(w), height: CGFloat(h)))
        return ctx.makeImage()
    }

    static func writeJPEG(_ cg: CGImage, to url: URL, quality: CGFloat) throws {
        guard let data = UIImage(cgImage: cg).jpegData(compressionQuality: quality) else {
            throw OpError(message: "Không mã hoá được ảnh")
        }
        try data.write(to: url, options: .atomic)
    }

    private static func fnv(_ s: String) -> String {
        var h: UInt32 = 2166136261
        for b in s.utf8 {
            h ^= UInt32(b)
            h = h &* 16777619
        }
        return String(h, radix: 16)
    }

    static func warpName(_ p: Page) -> String {
        let q = (p.quad ?? []).map { String(Int(($0 * 4).rounded())) }.joined(separator: ",")
        return "w_\(p.id)_\(fnv(p.src + "|" + q)).jpg"
    }

    static func outName(_ p: Page) -> String {
        var key = warpName(p) + "|\(p.filter)|\(p.rot)"
        // chỉ trộn khi có chỉnh sửa: trang không chỉnh sửa giữ nguyên tên file như các bản trước
        if let e = p.erase, !e.isEmpty { key += "|e" + Edits.key(e) }
        if let m = p.marks, !m.isEmpty { key += "|m" + Edits.key(m) }
        if let h = p.hdr { key += "|h" + h }
        return "o_\(p.id)_\(fnv(key)).jpg"
    }

    /// Sinh ảnh kết quả cho trang (chạy ở luồng nền). Tên file suy ra từ tham số nên dùng lại được.
    static func render(dir: URL, page: Page) throws -> Page {
        var p = page
        let wn = warpName(p), on = outName(p)
        let fm = FileManager.default
        let outURL = dir.appendingPathComponent(on)
        if fm.fileExists(atPath: outURL.path) {
            p.warp = wn
            p.out = on
            return p
        }
        let warpURL = dir.appendingPathComponent(wn)
        var warped: CGImage? = fm.fileExists(atPath: warpURL.path) ? loadCG(warpURL) : nil
        if warped == nil {
            guard let src = loadCG(dir.appendingPathComponent(p.src)) else { throw OpError(message: "Không đọc được ảnh gốc") }
            let W = CGFloat(src.width), H = CGFloat(src.height)
            let sx = p.srcW > 0 ? W / CGFloat(p.srcW) : 1
            let sy = p.srcH > 0 ? H / CGFloat(p.srcH) : 1
            var q = p.quadPoints.map { Geom.scale($0, sx, sy) } ?? Geom.fullQuad(W, H)
            if !Geom.isConvex(q) { q = Geom.fullQuad(W, H) }
            let size = Geom.outputSize(q, imgW: Double(W), imgH: Double(H), focal: -1, snap: true, maxLong: maxOut)
            guard let w = warp(src, quad: q, size: size) else { throw OpError(message: "Không nắn phẳng được ảnh") }
            try writeJPEG(w, to: warpURL, quality: 0.92)
            warped = w
        }
        guard let base = warped else { throw OpError(message: "Không xử lý được ảnh") }
        // chuỗi xử lý: nắn phẳng -> (dò ngón tay, một lần cho mỗi ảnh nắn phẳng) -> xoá thông minh -> lọc -> đánh dấu -> xoay -> tiêu đề
        // (bộ lọc "Làm phẳng" còn nắn thẳng nội dung: nét xoá theo toạ độ ảnh trước khi lọc, đánh dấu theo ảnh sau khi lọc)
        let erase = (p.erase ?? []).map { $0.op }
        let marks = p.marks ?? []
        let needFinger = p.fingerKey != wn
        var cur: CGImage = base
        if p.filter != Enhancer.original || !erase.isEmpty || !marks.isEmpty || needFinger {
            let w = base.width, h = base.height, bpr = w * 4
            guard let ctx = CGContext(data: nil, width: w, height: h, bitsPerComponent: 8, bytesPerRow: bpr,
                                      space: CGColorSpaceCreateDeviceRGB(),
                                      bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue),
                  let data = ctx.data else { throw OpError(message: "Không đủ bộ nhớ để xử lý ảnh") }
            ctx.draw(base, in: CGRect(x: 0, y: 0, width: w, height: h))
            let px = data.bindMemory(to: UInt8.self, capacity: bpr * h)
            if needFinger {
                p.finger = Retouch.hasFinger(px, w, h, bpr) ? 1 : 2
                p.fingerKey = wn
            }
            if !erase.isEmpty { Retouch.applyErase(px, w, h, bpr, erase) }
            Enhancer.apply(px, width: w, height: h, bytesPerRow: bpr, mode: p.filter)
            if !marks.isEmpty {
                // khảm lấy màu từ ảnh nền lúc CHƯA vẽ đánh dấu nào (giống màn hình chỉnh sửa)
                let clean = ctx.makeImage()
                MarkRenderer(dir: dir).render(into: ctx, width: w, height: h, marks: marks, base: clean)
            }
            guard let img = ctx.makeImage() else { throw OpError(message: "Không xử lý được ảnh") }
            cur = img
        }
        guard var fin = rotated(cur, degrees: p.rot) else { throw OpError(message: "Không xử lý được ảnh") }
        if let hdr = p.hdr, let withHdr = MarkRenderer.withHeader(fin, hdr) { fin = withHdr }
        try writeJPEG(fin, to: outURL, quality: 0.9)
        p.warp = wn
        p.out = on
        return p
    }

    /// Cạnh dài của ảnh xem trước ở màn hình Xóa thông minh / Đánh dấu.
    static let previewMax: CGFloat = 1500

    /// Điểm ảnh RGBA của ảnh đã nắn phẳng (chưa lọc, chưa xoay), cạnh dài không quá maxDim.
    static func loadWarp(dir: URL, page p: Page, maxDim: CGFloat) -> (data: [UInt8], w: Int, h: Int)? {
        guard let wn = p.warp, let cg = loadCG(dir.appendingPathComponent(wn)) else { return nil }
        return rgbaPixels(cg, maxDim: maxDim)
    }

    /// Nền cho màn hình Đánh dấu: ảnh nắn phẳng qua xoá thông minh + bộ lọc đầy đủ (đánh dấu vẽ SAU bộ lọc, như render),
    /// cạnh dài ~previewMax. Bộ lọc dời điểm ảnh ("Làm phẳng"): trường nắn phụ thuộc độ phân giải nên lọc trên ảnh nắn
    /// phẳng đầy đủ — giống hệt ảnh kết quả — rồi mới thu nhỏ, để nét đánh dấu đặt trên ảnh xem trước nằm đúng chỗ trên
    /// ảnh kết quả. Chạy trên luồng nền.
    static func markupBase(dir: URL, page p: Page) -> CGImage? {
        let erase = (p.erase ?? []).map { $0.op }
        if Enhancer.movesPixels(p.filter), let px = loadWarp(dir: dir, page: p, maxDim: CGFloat(maxOut)),
           let full = process(px.data, px.w, px.h, erase: erase, filter: p.filter, geometric: true) {
            return scaledCG(full, maxDim: previewMax)
        }
        guard let px = loadWarp(dir: dir, page: p, maxDim: previewMax) else { return nil }
        return process(px.data, px.w, px.h, erase: erase, filter: p.filter, geometric: true)
    }

    /// Ảnh từ mảng điểm ảnh RGBX (byte thứ tư bị bỏ qua).
    static func cgImage(rgba: [UInt8], w: Int, h: Int) -> CGImage? {
        guard w > 0, h > 0, rgba.count >= w * h * 4, let provider = CGDataProvider(data: Data(rgba) as CFData) else { return nil }
        return CGImage(width: w, height: h, bitsPerComponent: 8, bitsPerPixel: 32, bytesPerRow: w * 4,
                       space: CGColorSpaceCreateDeviceRGB(),
                       bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.noneSkipLast.rawValue),
                       provider: provider, decode: nil, shouldInterpolate: true, intent: .defaultIntent)
    }

    /// Áp chuỗi xử lý điểm ảnh (xoá thông minh + bộ lọc) lên bản sao của ảnh xem trước; trả về ảnh mới.
    /// geometric = false: bộ lọc dời điểm ảnh (Làm phẳng) chỉ làm sạch, không nắn (xem trước của Xóa thông minh).
    static func process(_ base: [UInt8], _ w: Int, _ h: Int, erase: [Retouch.Op], filter: Int,
                        geometric: Bool = true) -> CGImage? {
        var px = base
        px.withUnsafeMutableBufferPointer { buf in
            guard let p = buf.baseAddress else { return }
            if !erase.isEmpty { Retouch.applyErase(p, w, h, w * 4, erase) }
            Enhancer.apply(p, width: w, height: h, bytesPerRow: w * 4, mode: filter, geometric: geometric)
        }
        return cgImage(rgba: px, w: w, h: h)
    }

    /// Nắn phẳng nhanh ở độ phân giải thấp để xem trước.
    static func quickPreview(dir: URL, page p: Page, maxLong: CGFloat) -> UIImage? {
        guard let thumb = thumbnail(dir.appendingPathComponent(p.src), maxPixel: maxLong * 1.5), let cg = thumb.cgImage else { return nil }
        let W = CGFloat(cg.width), H = CGFloat(cg.height)
        let sx = p.srcW > 0 ? W / CGFloat(p.srcW) : 1
        let sy = p.srcH > 0 ? H / CGFloat(p.srcH) : 1
        var q = p.quadPoints.map { Geom.scale($0, sx, sy) } ?? Geom.fullQuad(W, H)
        if !Geom.isConvex(q) { q = Geom.fullQuad(W, H) }
        let size = Geom.outputSize(q, imgW: Double(W), imgH: Double(H), focal: -1, snap: true, maxLong: Double(maxLong))
        guard let w = warp(cg, quad: q, size: size), let r = rotated(w, degrees: p.rot) else { return nil }
        return UIImage(cgImage: r)
    }

    /// Tên file an toàn từ tên tài liệu: bỏ ký tự cấm và ký tự điều khiển, tối đa 80 ký tự / 180 byte UTF-8
    /// (giới hạn tên file 255 byte, còn chừa chỗ cho hậu tố "_12.jpg"), không bắt đầu bằng dấu chấm.
    static func safeName(_ s: String) -> String {
        var out = ""
        var bytes = 0, chars = 0
        for ch in s {
            var c = ch
            let bad = ch.unicodeScalars.contains { $0.value < 0x20 || $0.value == 0x7F } || "\\/:*?\"<>|".contains(ch)
            if bad { c = "_" }
            let b = String(c).utf8.count
            if chars + 1 > 80 || bytes + b > 180 { break }
            out.append(c)
            bytes += b
            chars += 1
        }
        var r = out.trimmingCharacters(in: .whitespaces)
        while r.hasPrefix(".") { r = String(r.dropFirst()).trimmingCharacters(in: .whitespaces) }
        return r.isEmpty ? "TaiLieu" : r
    }

    /// Chuẩn hoá chuỗi để tìm kiếm: bỏ dấu tiếng Việt, đ -> d, chữ thường ("bien ban" tìm được "Biên bản").
    static func fold(_ s: String) -> String {
        let d = s.replacingOccurrences(of: "đ", with: "d").replacingOccurrences(of: "Đ", with: "D")
        return d.folding(options: [.diacriticInsensitive, .caseInsensitive, .widthInsensitive], locale: Locale(identifier: "en_US_POSIX"))
    }

    /// Thông tin cần để xuất tài liệu, chụp lại trên luồng chính (Doc không an toàn khi đọc từ luồng nền).
    struct ExportJob {
        let title: String
        let files: [URL]
        let pdfSize: Int
    }

    static func exportJob(doc: Doc) throws -> ExportJob {
        guard !doc.pages.isEmpty else { throw OpError(message: "Tài liệu không có trang") }
        var files = [URL]()
        for p in doc.pages {
            guard let o = p.out, FileManager.default.fileExists(atPath: DocStore.shared.url(doc, o).path) else {
                throw OpError(message: "Trang chưa xử lý xong")
            }
            files.append(DocStore.shared.url(doc, o))
        }
        return ExportJob(title: doc.title, files: files, pdfSize: DocStore.shared.pdfSize)
    }

    static func shareDir() -> URL {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("share", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    /// Tạo file PDF tạm để chia sẻ/xuất. Ảnh từng trang được ghi nối tiếp từ đĩa (không nạp cả tài liệu vào bộ nhớ).
    static func exportPDF(job: ExportJob) throws -> URL {
        let url = shareDir().appendingPathComponent(safeName(job.title) + ".pdf")
        try PdfWriter.write(files: job.files, to: url, sizeMode: job.pdfSize, title: job.title)
        return url
    }

    static func exportPDF(doc: Doc) throws -> URL {
        return try exportPDF(job: exportJob(doc: doc))
    }

    static func exportImages(job: ExportJob) throws -> [URL] {
        let dir = shareDir()
        var urls = [URL]()
        let base = safeName(job.title)
        for (i, src) in job.files.enumerated() {
            let dst = dir.appendingPathComponent(job.files.count == 1 ? "\(base).jpg" : "\(base)_\(i + 1).jpg")
            try? FileManager.default.removeItem(at: dst)
            try FileManager.default.copyItem(at: src, to: dst)
            urls.append(dst)
        }
        return urls
    }

    static func exportImages(doc: Doc) throws -> [URL] {
        return try exportImages(job: exportJob(doc: doc))
    }
}
