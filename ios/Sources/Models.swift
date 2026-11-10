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
            // giá trị không hữu hạn làm JSONEncoder báo lỗi -> tài liệu không lưu được nữa
            r.append(p.x.isFinite ? Double(p.x) : 0)
            r.append(p.y.isFinite ? Double(p.y) : 0)
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
    /// Đã bị xoá: mọi lần lưu sau đó bị bỏ qua (không tạo lại thư mục). Không ghi vào doc.json.
    var deleted = false

    private enum CodingKeys: String, CodingKey {
        case id, title, created, modified, draft, pages
    }

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

/// Lưu tài liệu: Application Support/docs/<id>/doc.json + ảnh từng trang (không lộ ra ứng dụng Tệp).
final class DocStore {
    static let shared = DocStore()
    let root: URL
    private let fm = FileManager.default

    private init() {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        root = base.appendingPathComponent("docs", isDirectory: true)
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
        // tài liệu đã xoá (hoặc thư mục không còn) thì không ghi lại, tránh "hồi sinh" tài liệu rỗng
        // khi một việc xử lý ảnh ở luồng nền xong muộn
        guard !doc.deleted, fm.fileExists(atPath: dir(doc).path) else { return }
        let enc = JSONEncoder()
        enc.dateEncodingStrategy = .millisecondsSince1970
        guard let data = try? enc.encode(doc) else { return }
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
        doc.deleted = true
        try? fm.removeItem(at: dir(doc))
    }

    /// Gọi một lần lúc mở ứng dụng (chưa màn hình nào dùng tài liệu):
    /// - bản nháp còn trang là do lần trước ứng dụng bị đóng giữa lúc quét -> đưa trở lại danh sách thay vì để mất;
    /// - bản nháp / tài liệu không có trang, thư mục chưa kịp ghi doc.json -> xoá;
    /// - dọn file tạm đã chia sẻ.
    /// Thư mục có doc.json nhưng không đọc được thì để nguyên (không xoá ảnh của người dùng).
    /// Trả về số tài liệu được khôi phục.
    @discardableResult
    func startupMaintenance() -> Int {
        var recovered = 0
        if let items = try? fm.contentsOfDirectory(atPath: root.path) {
            for id in items {
                let dirURL = root.appendingPathComponent(id, isDirectory: true)
                guard let d = load(id) else {
                    // tạo tài liệu bị ngắt trước khi ghi doc.json -> thư mục rác
                    if !fm.fileExists(atPath: dirURL.appendingPathComponent("doc.json").path) {
                        try? fm.removeItem(at: dirURL)
                    }
                    continue
                }
                if d.draft {
                    // bỏ trang mất ảnh gốc (không thể xử lý lại)
                    d.pages.removeAll { !fm.fileExists(atPath: url(d, $0.src).path) }
                }
                if d.pages.isEmpty {
                    delete(d)
                } else if d.draft {
                    d.draft = false
                    save(d)
                    recovered += 1
                }
            }
        }
        let share = fm.temporaryDirectory.appendingPathComponent("share", isDirectory: true)
        try? fm.removeItem(at: share)
        return recovered
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
        // ghi ảnh mới xong mới xoá ảnh cũ: nếu ghi lỗi (hết dung lượng) thì trang cũ vẫn còn nguyên
        let name = newId("s") + "_src.jpg"
        try data.write(to: url(doc, name), options: .atomic)
        removeFiles(doc, doc.pages[i])
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
