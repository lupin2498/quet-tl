import UIKit

/// Một trang: ảnh gốc + tứ giác cắt + bộ lọc + góc xoay; ảnh kết quả được sinh lại khi tham số đổi.
struct Page: Codable, Equatable {
    var id: String
    var src: String
    var srcW: Int
    var srcH: Int
    var quad: [Double]?
    var auto: Bool = true
    var rot: Int = 0
    var filter: Int = Enhancer.magic
    var warp: String?
    var out: String?

    var quadPoints: [CGPoint]? {
        guard let q = quad, q.count == 8 else { return nil }
        return (0..<4).map { CGPoint(x: q[2 * $0], y: q[2 * $0 + 1]) }
    }

    static func pack(_ pts: [CGPoint]) -> [Double] {
        var r = [Double]()
        for p in pts {
            r.append(Double(p.x))
            r.append(Double(p.y))
        }
        return r
    }
}

final class Doc: Codable {
    var id: String
    var title: String
    var created: Date
    var modified: Date
    var draft: Bool
    var pages: [Page]

    init(id: String, title: String, draft: Bool) {
        self.id = id
        self.title = title
        self.created = Date()
        self.modified = self.created
        self.draft = draft
        self.pages = []
    }

    func index(of pageId: String) -> Int? {
        return pages.firstIndex { $0.id == pageId }
    }
}

/// Lưu tài liệu: Documents/docs/<id>/doc.json + ảnh từng trang.
final class DocStore {
    static let shared = DocStore()
    let root: URL
    private let fm = FileManager.default

    private init() {
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        root = docs.appendingPathComponent("docs", isDirectory: true)
        try? FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    }

    func dir(_ doc: Doc) -> URL {
        return root.appendingPathComponent(doc.id, isDirectory: true)
    }

    func url(_ doc: Doc, _ name: String) -> URL {
        return dir(doc).appendingPathComponent(name)
    }

    func newId(_ prefix: String) -> String {
        let t = Int(Date().timeIntervalSince1970 * 1000)
        return prefix + String(t, radix: 36) + String(Int.random(in: 0..<46656), radix: 36)
    }

    func create(draft: Bool) -> Doc {
        let d = Doc(id: newId("d"), title: Theme.newTitle(Date()), draft: draft)
        try? fm.createDirectory(at: dir(d), withIntermediateDirectories: true)
        save(d)
        return d
    }

    func save(_ doc: Doc) {
        let enc = JSONEncoder()
        enc.dateEncodingStrategy = .millisecondsSince1970
        guard let data = try? enc.encode(doc) else { return }
        try? fm.createDirectory(at: dir(doc), withIntermediateDirectories: true)
        try? data.write(to: url(doc, "doc.json"), options: .atomic)
    }

    func load(_ id: String) -> Doc? {
        let u = root.appendingPathComponent(id, isDirectory: true).appendingPathComponent("doc.json")
        guard let data = try? Data(contentsOf: u) else { return nil }
        let dec = JSONDecoder()
        dec.dateDecodingStrategy = .millisecondsSince1970
        return try? dec.decode(Doc.self, from: data)
    }

    func list() -> [Doc] {
        guard let items = try? fm.contentsOfDirectory(atPath: root.path) else { return [] }
        var out = [Doc]()
        for id in items {
            if let d = load(id), !d.draft, !d.pages.isEmpty { out.append(d) }
        }
        return out.sorted { $0.modified > $1.modified }
    }

    func delete(_ doc: Doc) {
        try? fm.removeItem(at: dir(doc))
    }

    func cleanDrafts() {
        guard let items = try? fm.contentsOfDirectory(atPath: root.path) else { return }
        let now = Date()
        for id in items {
            guard let d = load(id) else { continue }
            if (d.draft && now.timeIntervalSince(d.modified) > 24 * 3600) || (!d.draft && d.pages.isEmpty) {
                delete(d)
            }
        }
    }

    /// Thêm trang mới từ ảnh (đã xoay đúng chiều). Trả về trang vừa thêm.
    @discardableResult
    func addPage(_ doc: Doc, image: UIImage, quad: [CGPoint]?, auto: Bool) throws -> Page {
        let img = ImageOps.normalized(image, maxDim: ImageOps.maxSrc)
        guard let cg = img.cgImage, let data = UIImage(cgImage: cg).jpegData(compressionQuality: 0.92) else {
            throw NSError(domain: "DocStore", code: 1, userInfo: [NSLocalizedDescriptionKey: "Không lưu được ảnh"])
        }
        let pid = newId("p")
        let name = pid + "_src.jpg"
        try data.write(to: url(doc, name), options: .atomic)
        let sx = CGFloat(cg.width) / max(1, img.size.width * img.scale)
        let sy = CGFloat(cg.height) / max(1, img.size.height * img.scale)
        var q: [Double]? = nil
        if let quad = quad { q = Page.pack(Geom.scale(quad, sx, sy)) }
        let p = Page(id: pid, src: name, srcW: cg.width, srcH: cg.height, quad: q, auto: auto)
        doc.pages.append(p)
        doc.modified = Date()
        save(doc)
        return p
    }

    /// Chụp lại: thay ảnh gốc của trang.
    func replaceSource(_ doc: Doc, pageId: String, image: UIImage, quad: [CGPoint]?, auto: Bool) throws {
        guard let i = doc.index(of: pageId) else { return }
        let img = ImageOps.normalized(image, maxDim: ImageOps.maxSrc)
        guard let cg = img.cgImage, let data = UIImage(cgImage: cg).jpegData(compressionQuality: 0.92) else {
            throw NSError(domain: "DocStore", code: 1, userInfo: [NSLocalizedDescriptionKey: "Không lưu được ảnh"])
        }
        removeFiles(doc, doc.pages[i])
        let name = newId("s") + "_src.jpg"
        try data.write(to: url(doc, name), options: .atomic)
        var p = doc.pages[i]
        p.src = name
        p.srcW = cg.width
        p.srcH = cg.height
        p.quad = quad.map { Page.pack($0) }
        p.auto = auto
        p.rot = 0
        p.warp = nil
        p.out = nil
        doc.pages[i] = p
        doc.modified = Date()
        save(doc)
    }

    func removePage(_ doc: Doc, pageId: String) {
        guard let i = doc.index(of: pageId) else { return }
        removeFiles(doc, doc.pages[i])
        doc.pages.remove(at: i)
        doc.modified = Date()
        save(doc)
    }

    private func removeFiles(_ doc: Doc, _ p: Page) {
        for n in [p.src, p.warp, p.out].compactMap({ $0 }) {
            try? fm.removeItem(at: url(doc, n))
        }
    }

    func outReady(_ doc: Doc, _ p: Page) -> Bool {
        guard let o = p.out else { return false }
        return fm.fileExists(atPath: url(doc, o).path)
    }

    /// Xoá ảnh trung gian không còn dùng.
    func gc(_ doc: Doc) {
        var keep: Set<String> = ["doc.json"]
        for p in doc.pages {
            keep.insert(p.src)
            if let w = p.warp { keep.insert(w) }
            if let o = p.out { keep.insert(o) }
        }
        guard let items = try? fm.contentsOfDirectory(atPath: dir(doc).path) else { return }
        for n in items where !keep.contains(n) && (n.hasPrefix("w_") || n.hasPrefix("o_")) {
            try? fm.removeItem(at: url(doc, n))
        }
    }

    static let prefsPdfSize = "pdf_size"

    var pdfSize: Int {
        get { UserDefaults.standard.integer(forKey: DocStore.prefsPdfSize) }
        set { UserDefaults.standard.set(newValue, forKey: DocStore.prefsPdfSize) }
    }

    static func pdfSizeName(_ v: Int) -> String {
        switch v {
        case PdfWriter.sizeLetter: return "Letter"
        case PdfWriter.sizeFit: return "Theo ảnh"
        default: return "A4"
        }
    }
}
