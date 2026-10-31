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

    /// Tìm 4 góc trang giấy. Trả về toạ độ pixel của `cg` (gốc trên-trái), hoặc nil.
    static func detect(_ cg: CGImage) -> [CGPoint]? {
        let small = scaledCG(cg, maxDim: 1024)
        let sw = CGFloat(small.width), sh = CGFloat(small.height)
        let kx = CGFloat(cg.width) / sw, ky = CGFloat(cg.height) / sh
        func convert(_ o: VNRectangleObservation) -> [CGPoint] {
            let pts = [o.topLeft, o.topRight, o.bottomRight, o.bottomLeft].map {
                CGPoint(x: $0.x * sw * kx, y: (1 - $0.y) * sh * ky)
            }
            return Geom.order(pts)
        }
        let full = Double(cg.width * cg.height)
        let seg = VNDetectDocumentSegmentationRequest()
        let h1 = VNImageRequestHandler(cgImage: small, orientation: .up, options: [:])
        if (try? h1.perform([seg])) != nil, let obs = seg.results?.first, obs.confidence > 0.4 {
            let q = convert(obs)
            if Geom.isConvex(q) && Geom.area(q) > full * 0.05 {
                return Geom.clamp(q, CGFloat(cg.width), CGFloat(cg.height))
            }
        }
        let rect = VNDetectRectanglesRequest()
        rect.minimumAspectRatio = 0.25
        rect.maximumAspectRatio = 1.0
        rect.minimumSize = 0.2
        rect.quadratureTolerance = 35
        rect.minimumConfidence = 0.4
        rect.maximumObservations = 1
        let h2 = VNImageRequestHandler(cgImage: small, orientation: .up, options: [:])
        if (try? h2.perform([rect])) != nil, let obs = rect.results?.first {
            let q = convert(obs)
            if Geom.isConvex(q) && Geom.area(q) > full * 0.05 {
                return Geom.clamp(q, CGFloat(cg.width), CGFloat(cg.height))
            }
        }
        return nil
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
        return "o_\(p.id)_\(fnv(warpName(p) + "|\(p.filter)|\(p.rot)")).jpg"
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
        guard let base = warped, let f = filtered(base, mode: p.filter), let fin = rotated(f, degrees: p.rot) else {
            throw OpError(message: "Không xử lý được ảnh")
        }
        try writeJPEG(fin, to: outURL, quality: 0.9)
        p.warp = wn
        p.out = on
        return p
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

    static func pdfData(doc: Doc) throws -> Data {
        var jpgs = [Data]()
        for p in doc.pages {
            guard let o = p.out else { throw OpError(message: "Trang chưa xử lý xong") }
            jpgs.append(try Data(contentsOf: DocStore.shared.url(doc, o)))
        }
        return try PdfWriter.make(jpegs: jpgs, sizeMode: DocStore.shared.pdfSize, title: doc.title)
    }

    static func safeName(_ s: String) -> String {
        let bad = CharacterSet(charactersIn: "\\/:*?\"<>|\n\r\t")
        let r = s.components(separatedBy: bad).joined(separator: "_").trimmingCharacters(in: .whitespaces)
        return r.isEmpty ? "TaiLieu" : String(r.prefix(80))
    }

    /// Tạo file PDF tạm để chia sẻ/xuất.
    static func exportPDF(doc: Doc) throws -> URL {
        let data = try pdfData(doc: doc)
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("share", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let url = dir.appendingPathComponent(safeName(doc.title) + ".pdf")
        try data.write(to: url, options: .atomic)
        return url
    }

    static func exportImages(doc: Doc) throws -> [URL] {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("share", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        var urls = [URL]()
        let base = safeName(doc.title)
        for (i, p) in doc.pages.enumerated() {
            guard let o = p.out else { throw OpError(message: "Trang chưa xử lý xong") }
            let dst = dir.appendingPathComponent(doc.pages.count == 1 ? "\(base).jpg" : "\(base)_\(i + 1).jpg")
            try? FileManager.default.removeItem(at: dst)
            try FileManager.default.copyItem(at: DocStore.shared.url(doc, o), to: dst)
            urls.append(dst)
        }
        return urls
    }
}
