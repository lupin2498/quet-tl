import UIKit

/// Vùng thao tác của "Xóa thông minh": tô bằng bút / kéo hình chữ nhật, báo lại từng thao tác đã vẽ xong.
final class EraseCanvas: PageCanvasView {
    var tool = Retouch.pen
    /// Bề rộng nét theo điểm màn hình.
    var brushPt: CGFloat = 22
    var onOp: ((EraseOp) -> Void)?
    private var pts = [CGPoint]()
    private var drawing = false
    private var cursor = CGPoint.zero

    /// Bề rộng nét theo pixel ảnh ở mức phóng hiện tại.
    private var brushPx: CGFloat { max(1, brushPt / max(0.01, scale)) }

    override func onDown(_ ip: CGPoint, _ sp: CGPoint) {
        drawing = true
        pts = [ip]
        cursor = sp
        setNeedsDisplay()
    }

    override func onMove(_ ip: CGPoint, _ sp: CGPoint) {
        if !drawing { return }
        cursor = sp
        if tool == Retouch.rect {
            if pts.count > 1 { pts[1] = ip } else { pts.append(ip) }
        } else if let last = pts.last {
            let dx = ip.x - last.x, dy = ip.y - last.y
            if dx * dx + dy * dy >= 1.5 {
                if pts.count >= 4096 {
                    // nét quá dài: giữ số điểm trong giới hạn bằng cách bỏ bớt điểm xen kẽ
                    var half = [CGPoint]()
                    half.reserveCapacity(pts.count / 2 + 1)
                    var i = 0
                    while i < pts.count {
                        half.append(pts[i])
                        i += 2
                    }
                    pts = half
                }
                pts.append(ip)
            }
        }
        setNeedsDisplay()
    }

    override func onUp(_ ip: CGPoint, cancelled: Bool) {
        if !drawing { return }
        drawing = false
        let p = pts
        pts = []
        setNeedsDisplay()
        if cancelled || p.isEmpty || !hasImage { return }
        let w = imageW, h = imageH
        if tool == Retouch.rect {
            if p.count < 2 { return }
            let x0 = min(p[0].x, p[1].x), x1 = max(p[0].x, p[1].x)
            let y0 = min(p[0].y, p[1].y), y1 = max(p[0].y, p[1].y)
            // hình quá nhỏ (chạm nhầm) thì bỏ qua
            if (x1 - x0) * scale < 6 || (y1 - y0) * scale < 6 { return }
            let raw: [CGFloat] = [x0 / w, y0 / h, x1 / w, y0 / h, x1 / w, y1 / h, x0 / w, y1 / h]
            onOp?(EraseOp(k: Retouch.rect, w: 0, p: raw.map { Edits.q(Double($0)) }))
        } else {
            var q = [Double]()
            q.reserveCapacity(p.count * 2)
            for pt in p {
                q.append(Edits.q(Double(pt.x / w)))
                q.append(Edits.q(Double(pt.y / h)))
            }
            onOp?(EraseOp(k: tool, w: Edits.q(Double(brushPx / w)), p: q))
        }
    }

    override func drawPage(_ ctx: CGContext) {
        if !drawing || pts.isEmpty { return }
        let plain = tool == Retouch.pen || tool == Retouch.rect
        let color = plain ? UIColor(white: 1, alpha: 0.9) : Theme.accent.withAlphaComponent(0.4)
        if tool == Retouch.rect {
            if pts.count < 2 { return }
            let r = CGRect(x: min(pts[0].x, pts[1].x), y: min(pts[0].y, pts[1].y),
                           width: abs(pts[0].x - pts[1].x), height: abs(pts[0].y - pts[1].y))
            ctx.setFillColor(color.cgColor)
            ctx.fill(r)
            return
        }
        ctx.setStrokeColor(color.cgColor)
        ctx.setLineWidth(brushPx)
        ctx.setLineCap(.round)
        ctx.setLineJoin(.round)
        ctx.move(to: pts[0])
        if pts.count == 1 {
            ctx.addLine(to: CGPoint(x: pts[0].x + 0.01, y: pts[0].y + 0.01))
        } else {
            for k in 1..<pts.count { ctx.addLine(to: pts[k]) }
        }
        ctx.strokePath()
    }

    override func drawOverlay(_ ctx: CGContext) {
        if !drawing || tool == Retouch.rect { return }
        ctx.setStrokeColor(UIColor(white: 1, alpha: 0.8).cgColor)
        ctx.setLineWidth(1.5)
        ctx.strokeEllipse(in: CGRect(x: cursor.x - brushPt / 2, y: cursor.y - brushPt / 2, width: brushPt, height: brushPt))
    }
}

/// "Xóa thông minh": Bút (tô nền theo nét), Hình chữ nhật, Thần kỳ (xoá cả vật thể dính liền nét tô),
/// Viết tay (xoá nét bút màu trên cả trang), Xóa văn bản (xoá chữ, giữ đường kẻ bảng). Có hoàn tác / làm lại / so sánh.
/// Kết quả trả về là danh sách thao tác; ảnh độ phân giải đầy đủ do ImageOps.render dựng lại sau.
final class EraseViewController: EditScreenController {
    /// Danh sách thao tác sau khi bấm ✓.
    var onDone: (([EraseOp]) -> Void)?
    var onCancel: (() -> Void)?

    private static let tools: [(kind: Int, symbol: String, name: String, hint: String)] = [
        (Retouch.pen, "pencil", "Bút", "Tô lên phần muốn xóa"),
        (Retouch.rect, "rectangle.dashed", "Hình chữ nhật", "Kéo một hình chữ nhật quanh phần muốn xóa"),
        (Retouch.magic, "wand.and.stars", "Thần kỳ", "Tô sơ qua vật thể (vết bẩn, con dấu, chữ ký…) để xóa cả vật thể"),
        (Retouch.hand, "scribble", "Viết tay", "Chạm “Viết tay” để xóa nét bút màu trên cả trang"),
        (Retouch.text, "text.badge.minus", "Xóa văn bản", "Tô lên chữ muốn xóa — đường kẻ bảng được giữ lại")
    ]
    private static let sizes: [CGFloat] = [12, 22, 38]
    private static let sizeNames = ["Nét nhỏ", "Nét vừa", "Nét lớn"]

    private let dir: URL
    private let page: Page
    private let canvas = EraseCanvas()
    private let compareBtn = UIButton(type: .custom)
    private(set) var ops: [EraseOp]
    private var redoOps = [EraseOp]()
    private(set) var tool = Retouch.pen
    private var sizeIndex = 1
    private(set) var busy = 0
    private(set) var loaded = false
    private var finished = false
    private var comparing = false
    private var shown: UIImage?
    private var original: UIImage?

    // chỉ hàng đợi worker đụng tới các biến dưới đây
    private let worker = DispatchQueue(label: "vn.quettailieu.erase", qos: .userInitiated)
    private var base = [UInt8]()
    private var erased = [UInt8]()
    private var bw = 0
    private var bh = 0

    init(doc: Doc, page: Page) {
        self.dir = DocStore.shared.dir(doc)
        self.page = page
        self.ops = page.erase ?? []
        super.init(nibName: nil, bundle: nil)
        modalPresentationStyle = .fullScreen
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    override func viewDidLoad() {
        super.viewDidLoad()
        buildChrome(title: "Xóa thông minh", tools: EraseViewController.tools.map { ($0.symbol, $0.name) })
        installCanvas(canvas)
        canvas.accessibilityLabel = "Trang đang xóa"
        canvas.onOp = { [weak self] op in self?.addOp(op) }

        var ccfg = UIButton.Configuration.filled()
        ccfg.title = "So sánh"
        ccfg.baseBackgroundColor = UIColor(white: 0, alpha: 0.55)
        ccfg.baseForegroundColor = .white
        ccfg.cornerStyle = .capsule
        ccfg.contentInsets = NSDirectionalEdgeInsets(top: 6, leading: 14, bottom: 6, trailing: 14)
        ccfg.titleTextAttributesTransformer = UIConfigurationTextAttributesTransformer { incoming in
            var out = incoming
            out.font = UIFont.systemFont(ofSize: 13, weight: .medium)
            return out
        }
        compareBtn.configuration = ccfg
        compareBtn.accessibilityHint = "Giữ để xem trang trước khi xóa"
        compareBtn.addTarget(self, action: #selector(compareDown), for: .touchDown)
        compareBtn.addTarget(self, action: #selector(compareUp), for: [.touchUpInside, .touchUpOutside, .touchCancel])
        compareBtn.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(compareBtn)
        NSLayoutConstraint.activate([
            compareBtn.trailingAnchor.constraint(equalTo: content.trailingAnchor, constant: -14),
            compareBtn.centerYAnchor.constraint(equalTo: undoBtn.centerYAnchor)
        ])

        selectTool(Retouch.pen)
        refreshUndo()
        load()
    }

    // MARK: giao diện

    private func toolIndex(_ kind: Int) -> Int {
        return EraseViewController.tools.firstIndex { $0.kind == kind } ?? 0
    }

    private func selectTool(_ kind: Int) {
        tool = kind
        canvas.tool = kind
        let i = toolIndex(kind)
        setSelectedTool(i)
        hintLabel.text = EraseViewController.tools[i].hint
        refreshSizes()
    }

    private func refreshSizes() {
        clearOptions()
        canvas.brushPt = EraseViewController.sizes[sizeIndex]
        if tool == Retouch.rect { return }
        let l = Theme.label("Cỡ nét", size: 13, color: Theme.text2)
        optionsRow.addArrangedSubview(l)
        for k in 0..<EraseViewController.sizes.count {
            let b = EditScreenController.dot(diameter: CGFloat(8 + 6 * k), color: k == sizeIndex ? Theme.accent : UIColor(rgb: 0x8A898E),
                                             selected: false, label: EraseViewController.sizeNames[k], box: 48)
            b.tag = k
            b.addTarget(self, action: #selector(sizeTapped(_:)), for: .touchUpInside)
            optionsRow.addArrangedSubview(b)
        }
    }

    @objc private func sizeTapped(_ b: UIButton) {
        sizeIndex = max(0, min(EraseViewController.sizes.count - 1, b.tag))
        refreshSizes()
    }

    private func refreshUndo() {
        setUndo(!ops.isEmpty, !redoOps.isEmpty)
    }

    private func setBusy(_ delta: Int) {
        busy = max(0, busy + delta)
        if busy > 0 { spinner.startAnimating() } else { spinner.stopAnimating() }
    }

    override func toolTapped(_ index: Int) {
        let kind = EraseViewController.tools[index].kind
        if kind == Retouch.hand {
            eraseHandwriting()
        } else {
            selectTool(kind)
        }
    }

    // MARK: xử lý ảnh xem trước (trên hàng đợi worker)

    private func image(_ cg: CGImage?) -> UIImage? {
        return cg.map { UIImage(cgImage: $0) }
    }

    private func load() {
        let dir = self.dir
        let snap = page
        let all = ops.map { $0.op }
        setBusy(1)
        worker.async { [weak self] in
            guard let self = self else { return }
            var out: CGImage?
            var orig: CGImage?
            if let px = ImageOps.loadWarp(dir: dir, page: snap, maxDim: ImageOps.previewMax) {
                self.base = px.data
                self.erased = px.data
                self.bw = px.w
                self.bh = px.h
                orig = ImageOps.process(px.data, px.w, px.h, erase: [], filter: snap.filter)
                if all.isEmpty {
                    out = orig
                } else {
                    self.applyAllOnWorker(all)
                    out = ImageOps.process(self.erased, px.w, px.h, erase: [], filter: snap.filter)
                }
            }
            DispatchQueue.main.async {
                self.setBusy(-1)
                guard !self.finished else { return }
                guard let o = self.image(out) else {
                    self.toast("Không mở được trang để chỉnh sửa")
                    self.cancelTapped()
                    return
                }
                self.loaded = true
                self.original = self.image(orig)
                self.shown = o
                self.canvas.setImage(o, rotation: self.page.rot)
            }
        }
    }

    /// (worker) Áp cả danh sách thao tác lên `erased`.
    private func applyAllOnWorker(_ all: [Retouch.Op]) {
        let w = bw, h = bh
        erased.withUnsafeMutableBufferPointer { buf in
            if let p = buf.baseAddress { Retouch.applyErase(p, w, h, w * 4, all) }
        }
    }

    /// (worker) Áp thêm một thao tác lên `erased`. Trả về true nếu ảnh thay đổi.
    private func applyOneOnWorker(_ op: Retouch.Op) -> Bool {
        let w = bw, h = bh
        var changed = false
        erased.withUnsafeMutableBufferPointer { buf in
            guard let p = buf.baseAddress else { return }
            if op.kind == Retouch.hand {
                changed = Retouch.erase(p, w, h, w * 4, Retouch.hand, nil)
            } else {
                var m = [UInt8](repeating: 0, count: w * h)
                Retouch.opMask(&m, w, h, op)
                changed = Retouch.erase(p, w, h, w * 4, op.kind, m)
            }
        }
        return changed
    }

    private func show(_ img: UIImage) {
        shown = img
        if !comparing { canvas.swapImage(img) }
    }

    /// Người dùng vừa vẽ xong một thao tác: áp thêm lên ảnh đang có rồi lọc lại.
    private func addOp(_ op: EraseOp) {
        if !loaded || finished { return }
        ops.append(op)
        redoOps.removeAll()
        refreshUndo()
        setBusy(1)
        let rop = op.op
        let filter = page.filter
        worker.async { [weak self] in
            guard let self = self else { return }
            let changed = self.applyOneOnWorker(rop)
            let out = changed ? ImageOps.process(self.erased, self.bw, self.bh, erase: [], filter: filter) : nil
            DispatchQueue.main.async {
                self.setBusy(-1)
                guard !self.finished else { return }
                guard changed, let img = self.image(out) else {
                    // không có gì bị xoá: bỏ thao tác khỏi danh sách
                    if let i = self.ops.lastIndex(of: op) { self.ops.remove(at: i) }
                    if let i = self.redoOps.lastIndex(of: op) { self.redoOps.remove(at: i) }
                    self.refreshUndo()
                    if op.k == Retouch.hand {
                        self.toast("Không tìm thấy nét bút màu trên trang")
                    } else if op.k == Retouch.text {
                        self.toast("Không có chữ trong vùng vừa tô")
                    }
                    return
                }
                self.show(img)
            }
        }
    }

    /// Dựng lại từ đầu theo danh sách thao tác hiện tại (sau hoàn tác / làm lại).
    private func rebuild() {
        if !loaded || finished { return }
        let all = ops.map { $0.op }
        let filter = page.filter
        setBusy(1)
        worker.async { [weak self] in
            guard let self = self else { return }
            self.erased = self.base
            self.applyAllOnWorker(all)
            let out = ImageOps.process(self.erased, self.bw, self.bh, erase: [], filter: filter)
            DispatchQueue.main.async {
                self.setBusy(-1)
                guard !self.finished, let img = self.image(out) else { return }
                self.show(img)
            }
        }
    }

    func eraseHandwriting() {
        if !loaded || finished { return }
        addOp(EraseOp(k: Retouch.hand, w: 0, p: []))
    }

    override func undoTapped() {
        if ops.isEmpty || !loaded { return }
        redoOps.append(ops.removeLast())
        refreshUndo()
        rebuild()
    }

    override func redoTapped() {
        if redoOps.isEmpty || !loaded { return }
        ops.append(redoOps.removeLast())
        refreshUndo()
        rebuild()
    }

    @objc private func compareDown() { setComparing(true) }
    @objc private func compareUp() { setComparing(false) }

    private func setComparing(_ on: Bool) {
        if !loaded || comparing == on { return }
        comparing = on
        let img = on ? original : shown
        if let i = img { canvas.swapImage(i) }
    }

    // MARK: kết thúc

    override func applyTapped() {
        if !loaded || finished { return }
        if busy > 0 {
            toast("Đang xử lý, chờ một chút…")
            return
        }
        finished = true
        onDone?(ops)
    }

    override func cancelTapped() {
        if finished { return }
        finished = true
        onCancel?()
    }

    // MARK: tự kiểm thử

    var testReady: Bool { loaded && busy == 0 }

    func testSelect(_ kind: Int) { selectTool(kind) }

    func testStroke(_ pts: [CGPoint]) { canvas.testStroke(pts) }

    func testCompare(_ on: Bool) { setComparing(on) }
}
