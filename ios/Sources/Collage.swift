import UIKit

/// "Cắt dán": ghép nhiều trang lên một tờ A4 (2, 4, 6 hoặc 8 trang mỗi tờ) và lưu thành tài liệu mới.
/// Mỗi tờ ghép là một trang bình thường (ảnh gốc = ảnh ghép, không cắt, không lọc).
/// Bố cục 2 trang: trang dọc nằm cạnh nhau trên tờ A4 ngang, trang ngang xếp chồng trên tờ A4 dọc.
enum Collage {
    static let layoutNames = ["2 trang mỗi tờ", "4 trang mỗi tờ", "6 trang mỗi tờ (thẻ, giấy tờ nhỏ)", "8 trang mỗi tờ (thẻ, giấy tờ nhỏ)"]
    private static let layouts: [(cols: Int, rows: Int)] = [(1, 2), (2, 2), (2, 3), (2, 4)]
    /// A4 ở 300 dpi.
    private static let sheetW: CGFloat = 2480
    private static let sheetH: CGFloat = 3508

    /// Vị trí ô thứ k trên tờ w x h chia cols x rows.
    static func cell(_ cols: Int, _ rows: Int, _ k: Int, _ w: CGFloat, _ h: CGFloat) -> CGRect {
        let margin = min(w, h) * 0.04, gap = min(w, h) * 0.025
        let cw = (w - 2 * margin - CGFloat(cols - 1) * gap) / CGFloat(cols)
        let ch = (h - 2 * margin - CGFloat(rows - 1) * gap) / CGFloat(rows)
        let c = k % cols, r = k / cols
        return CGRect(x: margin + CGFloat(c) * (cw + gap), y: margin + CGFloat(r) * (ch + gap), width: cw, height: ch)
    }

    /// Ghép các ảnh trang (file JPEG kết quả) thành tài liệu mới đã lưu. Chạy ở luồng nền.
    static func make(files: [URL], title: String, layout: Int) throws -> Doc {
        guard !files.isEmpty else { throw ImageOps.OpError(message: "Tài liệu chưa có trang nào") }
        let li = max(0, min(layouts.count - 1, layout))
        let per = layouts[li].cols * layouts[li].rows
        var portrait = 0
        for f in files {
            if let t = ImageOps.thumbnail(f, maxPixel: 64), t.size.height >= t.size.width { portrait += 1 }
        }
        let landscape = li == 0 && portrait * 2 >= files.count
        let cols = landscape ? 2 : layouts[li].cols, rows = landscape ? 1 : layouts[li].rows
        let w = landscape ? sheetH : sheetW, h = landscape ? sheetW : sheetH
        let doc = DocStore.shared.create(draft: true)
        do {
            let fmt = UIGraphicsImageRendererFormat()
            fmt.scale = 1
            fmt.opaque = true
            fmt.preferredRange = .standard
            let renderer = UIGraphicsImageRenderer(size: CGSize(width: w, height: h), format: fmt)
            let sheets = (files.count + per - 1) / per
            for s in 0..<sheets {
                let img = renderer.image { c in
                    UIColor.white.setFill()
                    c.fill(CGRect(x: 0, y: 0, width: w, height: h))
                    c.cgContext.interpolationQuality = .high
                    for k in 0..<per {
                        let idx = s * per + k
                        if idx >= files.count { break }
                        let r = cell(cols, rows, k, w, h)
                        guard let page = ImageOps.thumbnail(files[idx], maxPixel: max(r.width, r.height)),
                              page.size.width > 0, page.size.height > 0 else { continue }
                        let k2 = min(r.width / page.size.width, r.height / page.size.height)
                        let pw = page.size.width * k2, ph = page.size.height * k2
                        page.draw(in: CGRect(x: r.midX - pw / 2, y: r.midY - ph / 2, width: pw, height: ph))
                    }
                }
                try DocStore.shared.addPage(doc, image: img, quad: nil, auto: false)
                doc.pages[doc.pages.count - 1].filter = Enhancer.original
            }
            doc.title = title
            doc.draft = false
            doc.modified = Date()
            DocStore.shared.save(doc)
            return doc
        } catch {
            DocStore.shared.delete(doc)
            throw error
        }
    }
}
