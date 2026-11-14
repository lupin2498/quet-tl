import Compression
import UIKit
import Vision

/// Chuyển tài liệu đã quét sang Word (.docx) giữ nguyên bố cục: với từng trang (ảnh kết quả đã nắn phẳng + lọc) Vision
/// nhận dạng chữ ngay trên máy rồi DocLayout dàn lại — chữ thành chữ Word đúng vị trí và cỡ, bảng kẻ thành bảng Word, con
/// dấu / chữ ký / phần không đọc được giữ lại dưới dạng hình đúng chỗ; DocxWriter ghi file. Trang không nhận dạng được chữ
/// được đưa vào nguyên dạng ảnh. Bản Android: WordExport.java (ML Kit).
enum WordExport {
    /// Cạnh dài tối đa của ảnh trang đem nhận dạng + phân tích (khoảng 200 dpi với trang A4).
    static let maxLong: CGFloat = 2400
    /// Trang độ phân giải thấp (ảnh nhập từ nguồn khác, cạnh dài dưới mức này): chữ chỉ cao 7-10 điểm ảnh, bộ nhận dạng đọc
    /// sai dấu và đặt khung lệch -> phóng ảnh lên (tối đa 3 lần, tới cạnh dài maxLong) riêng cho bước nhận dạng; việc dàn
    /// trang vẫn dùng ảnh gốc.
    static let ocrMinLong: CGFloat = 1600
    static let confScale: Float = 2
    static let jpegQuality: CGFloat = 0.9

    struct Cancelled: Error {}

    /// Cờ huỷ dùng chung giữa luồng chính (nút Huỷ) và luồng chuyển đổi.
    final class CancelFlag {
        private let lock = NSLock()
        private var flag = false

        func cancel() {
            lock.lock()
            flag = true
            lock.unlock()
        }

        var isCancelled: Bool {
            lock.lock()
            defer { lock.unlock() }
            return flag
        }
    }

    /// Số liệu của một lần chuyển (để báo cho người dùng và kiểm thử).
    struct Stats {
        var pages = 0
        var textPages = 0
        var words = 0
        var lines = 0
        var tables = 0
        var pictures = 0
        /// Bộ nhận dạng của máy đọc được tiếng Việt (iOS 16 trở lên).
        var vietnamese = true

        /// Ghi chú kèm thông báo kết quả khi có trang không chuyển được thành chữ.
        var note: String {
            if textPages == 0 { return " — không nhận dạng được chữ nên các trang được đưa vào dưới dạng ảnh" }
            var s = ""
            if textPages < pages { s = " — \(pages - textPages) trang không nhận dạng được chữ, giữ dạng ảnh" }
            if !vietnamese { s += " — iOS 15 chưa nhận dạng được dấu tiếng Việt, nên cập nhật iOS 16 trở lên" }
            return s
        }
    }

    // MARK: nhận dạng chữ

    /// Ngôn ngữ nhận dạng: tiếng Việt (Vision hỗ trợ từ iOS 16) rồi tiếng Anh.
    static func languages(_ req: VNRecognizeTextRequest) -> [String] {
        let supported = (try? req.supportedRecognitionLanguages()) ?? []
        var out = [String]()
        for want in ["vi-VT", "vi-VN", "vi", "en-US"] where supported.contains(want) {
            if want.hasPrefix("vi"), out.contains(where: { $0.hasPrefix("vi") }) { continue }
            out.append(want)
        }
        return out
    }

    /// Ảnh phóng to cho bước nhận dạng (nil nếu ảnh đã đủ lớn hoặc không tạo được).
    private static func upscaled(_ cg: CGImage) -> CGImage? {
        let l = CGFloat(max(cg.width, cg.height))
        guard l > 0, l < ocrMinLong else { return nil }
        let k = min(3, maxLong / l)
        let tw = max(1, Int((CGFloat(cg.width) * k).rounded())), th = max(1, Int((CGFloat(cg.height) * k).rounded()))
        guard let ctx = CGContext(data: nil, width: tw, height: th, bitsPerComponent: 8, bytesPerRow: 0,
                                  space: CGColorSpaceCreateDeviceRGB(),
                                  bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue) else { return nil }
        ctx.interpolationQuality = .high
        ctx.draw(cg, in: CGRect(x: 0, y: 0, width: tw, height: th))
        return ctx.makeImage()
    }

    /// Nhận dạng chữ trong ảnh trang: mỗi từ kèm khung theo điểm ảnh của `cg` (gốc trên-trái), chỉ số dòng, độ tin cậy.
    /// Ảnh độ phân giải thấp được phóng lên trước khi nhận dạng (khung trả về chuẩn hoá nên vẫn quy về `cg`).
    static func recognize(_ cg: CGImage) throws -> (words: [DocLayout.Word], vietnamese: Bool) {
        let req = VNRecognizeTextRequest()
        req.recognitionLevel = .accurate
        req.usesLanguageCorrection = true
        let langs = languages(req)
        if !langs.isEmpty { req.recognitionLanguages = langs }
        let handler = VNImageRequestHandler(cgImage: upscaled(cg) ?? cg, options: [:])
        try handler.perform([req])
        let W = Float(cg.width), H = Float(cg.height)
        var out = [DocLayout.Word]()
        var line = 0
        for obs in req.results ?? [] {
            guard let cand = obs.topCandidates(1).first else { continue }
            line += 1
            let s = cand.string
            var i = s.startIndex
            while i < s.endIndex {
                // từng từ (ngăn bởi khoảng trắng) với khung riêng của nó
                while i < s.endIndex, s[i].isWhitespace { i = s.index(after: i) }
                var j = i
                while j < s.endIndex, !s[j].isWhitespace { j = s.index(after: j) }
                if j > i {
                    let range = i..<j
                    // dấu tiếng Việt về dạng dựng sẵn (một ký tự) để đo bề rộng chữ và ghi ra Word nhất quán
                    let text = String(s[range]).precomposedStringWithCanonicalMapping
                    if let box = try? cand.boundingBox(for: range), !text.isEmpty {
                        let r = box.boundingBox
                        let x0 = Float(r.minX) * W, x1 = Float(r.maxX) * W
                        let y0 = (1 - Float(r.maxY)) * H, y1 = (1 - Float(r.minY)) * H
                        if x0.isFinite, x1.isFinite, y0.isFinite, y1.isFinite, x1 > x0, y1 > y0 {
                            let w = DocLayout.Word(text: text, x0: x0, y0: y0, x1: x1, y1: y1, line: line)
                            // Vision chỉ báo ba mức 0,3 / 0,5 / 1,0 và mức 0,3 vẫn thường đọc đúng; lõi coi dưới 0,5 là
                            // không đọc được -> nhân đôi (rác do đọc hình thành chữ đã có các phép lọc hình học của lõi)
                            w.conf = min(1, cand.confidence * confScale)
                            out.append(w)
                        }
                    }
                }
                i = j
            }
        }
        return (out, langs.contains(where: { $0.hasPrefix("vi") }))
    }

    // MARK: từng trang

    private static func jpeg(_ cg: CGImage) -> [UInt8]? {
        guard let d = UIImage(cgImage: cg).jpegData(compressionQuality: jpegQuality) else { return nil }
        return [UInt8](d)
    }

    /// Trang chỉ gồm ảnh trang.
    static func picturePage(_ cg: CGImage, sizeMode: Int) throws -> WordDoc.Page {
        let page = DocLayout.picturePage(cg.width, cg.height, sizeMode)
        guard let data = jpeg(cg) else { throw ImageOps.OpError(message: "Không nén được ảnh trang") }
        for pic in page.pics {
            pic.data = data
            pic.ext = "jpeg"
        }
        return page
    }

    /// Một trang: nhận dạng chữ -> dàn trang; không đọc được chữ thì trang đó vào Word dưới dạng ảnh.
    static func convertPage(_ url: URL, sizeMode: Int, stats: inout Stats) throws -> WordDoc.Page {
        guard let full = ImageOps.loadCG(url) else { throw ImageOps.OpError(message: "Không đọc được ảnh trang") }
        let cg = ImageOps.scaledCG(full, maxDim: maxLong)
        var words = [DocLayout.Word]()
        if cg.width >= 32, cg.height >= 32 {
            if let r = try? recognize(cg) {
                words = r.words
                if !r.vietnamese { stats.vietnamese = false }
            }
        }
        var page: WordDoc.Page?
        if !words.isEmpty, let px = ImageOps.rgbaPixels(cg, maxDim: CGFloat(max(cg.width, cg.height))) {
            let r = DocLayout.analyze(px.data, px.w, px.h, words, sizeMode)
            var ok = r.lines > 0
            if ok {
                for pic in r.page.pics {
                    let pw = max(1, pic.px1 - pic.px0), ph = max(1, pic.py1 - pic.py0)
                    guard let crop = ImageOps.cgImage(rgba: r.picture(pic), w: pw, h: ph), let data = jpeg(crop) else {
                        ok = false
                        break
                    }
                    pic.data = data
                    pic.ext = "jpeg"
                }
            }
            r.release()
            if ok {
                page = r.page
                stats.textPages += 1
                stats.lines += r.lines
                stats.tables += r.tables
                stats.pictures += r.page.pics.count
            }
        }
        stats.pages += 1
        stats.words += words.count
        if let p = page { return p }
        return try picturePage(cg, sizeMode: sizeMode)
    }

    // MARK: cả tài liệu

    /// Nén DEFLATE thô (RFC 1951) cho các phần XML của file .docx; nil -> DocxWriter ghi không nén.
    static func deflate(_ data: Data) -> Data? {
        if data.isEmpty { return nil }
        let cap = data.count + data.count / 8 + 256
        var out = Data(count: cap)
        let n: Int = out.withUnsafeMutableBytes { (dst: UnsafeMutableRawBufferPointer) -> Int in
            data.withUnsafeBytes { (src: UnsafeRawBufferPointer) -> Int in
                guard let d = dst.bindMemory(to: UInt8.self).baseAddress, let s = src.bindMemory(to: UInt8.self).baseAddress else { return 0 }
                return compression_encode_buffer(d, cap, s, data.count, nil, COMPRESSION_ZLIB)
            }
        }
        guard n > 0, n < data.count else { return nil }
        out.count = n
        return out
    }

    /// Chuyển các trang rồi dựng file DOCX trong bộ nhớ. Gọi trên luồng nền.
    /// progress(index, total): bắt đầu trang thứ index (từ 0); index == total: đang ghi file.
    static func make(job: ImageOps.ExportJob, cancel: CancelFlag? = nil, progress: ((Int, Int) -> Void)? = nil) throws -> (data: Data, stats: Stats) {
        var stats = Stats()
        var pages = [WordDoc.Page]()
        let total = job.files.count
        for (i, url) in job.files.enumerated() {
            if cancel?.isCancelled == true { throw Cancelled() }
            progress?(i, total)
            // ảnh trang + mảng điểm ảnh của từng trang được giải phóng ngay sau khi xong trang đó
            let page: WordDoc.Page = try autoreleasepool {
                try convertPage(url, sizeMode: job.pdfSize, stats: &stats)
            }
            pages.append(page)
        }
        if cancel?.isCancelled == true { throw Cancelled() }
        progress?(total, total)
        let iso = ISO8601DateFormatter().string(from: Date())
        let data = DocxWriter.write(pages: pages, title: job.title, isoTime: iso, deflate: { WordExport.deflate($0) })
        return (data, stats)
    }

    /// Tạo file .docx tạm để chia sẻ / lưu.
    static func export(job: ImageOps.ExportJob, cancel: CancelFlag? = nil, progress: ((Int, Int) -> Void)? = nil) throws -> (url: URL, stats: Stats) {
        let r = try make(job: job, cancel: cancel, progress: progress)
        let url = ImageOps.shareDir().appendingPathComponent(ImageOps.safeName(job.title) + ".docx")
        try r.data.write(to: url, options: .atomic)
        return (url, r.stats)
    }
}
