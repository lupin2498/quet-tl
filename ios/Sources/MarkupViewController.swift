import UIKit

/// Vùng thao tác của "Đánh dấu": vẽ nét (Brush / Khảm) hoặc chọn - kéo - co giãn - xoá chữ và chữ ký.
/// Các đánh dấu được vẽ bằng đúng MarkRenderer dùng cho ảnh kết quả nên thấy sao lưu vậy.
final class MarkCanvas: PageCanvasView {
    enum Tool { case move, brush, mosaic }
    private enum Mode { case none, move, scale, delete, stroke }

    var marks = [Mark]()
    var renderer: MarkRenderer?
    var tool = Tool.move {
        didSet { if tool != .move { select(-1) } }
    }
    var color: UInt32 = 0xFF1A1A1A
    /// Bề rộng nét theo điểm màn hình.
    var brushPt: CGFloat = 8
    private(set) var selected = -1

    /// Gọi ngay TRƯỚC khi danh sách đánh dấu bị thay đổi (để lưu bước hoàn tác).
    var onBeforeChange: (() -> Void)?
    var onChanged: (() -> Void)?
    var onSelected: (() -> Void)?
    /// Chạm vào mục đang được chọn mà không kéo (để sửa nội dung chữ).
    var onTapSelected: (() -> Void)?

    private var mode = Mode.none
    private var moved = false
    private var wasSelected = false
    private var downI = CGPoint.zero
    private var downS = CGPoint.zero
    private var startPos = CGPoint.zero
    private var startSize: Double = 0
    private var startDist: CGFloat = 1
    private var pts = [CGPoint]()

    var selectedMark: Mark? {
        return selected >= 0 && selected < marks.count ? marks[selected] : nil
    }

    func select(_ i: Int) {
        let v = i >= marks.count ? -1 : i
        if selected == v { return }
        selected = v
        onSelected?()
        setNeedsDisplay()
    }

    /// Thay toàn bộ danh sách (hoàn tác / làm lại / nạp lại).
    func setMarks(_ list: [Mark]) {
        marks = list
        selected = -1
        mode = .none
        onSelected?()
        setNeedsDisplay()
    }

    private func block(_ m: Mark) -> CGSize {
        guard let r = renderer else { return CGSize(width: 10, height: 10) }
        return m.t == Mark.sign ? r.signBlock(m, imageW) : r.textBlock(m, imageW)
    }

    /// 4 góc của mục (theo pixel ảnh): trên-trái, trên-phải, dưới-phải, dưới-trái.
    private func corners(_ m: Mark) -> [CGPoint] {
        let b = block(m)
        let padI = 4 / max(0.01, scale)
        let hw = b.width / 2 + padI, hh = b.height / 2 + padI
        let a = CGFloat(m.a) * CGFloat.pi / 180
        let cs = cos(a), sn = sin(a)
        let cx = CGFloat(m.x) * imageW, cy = CGFloat(m.y) * imageH
        let dx: [CGFloat] = [-hw, hw, hw, -hw], dy: [CGFloat] = [-hh, -hh, hh, hh]
        var out = [CGPoint]()
        for i in 0..<4 {
            out.append(CGPoint(x: cx + dx[i] * cs - dy[i] * sn, y: cy + dx[i] * sn + dy[i] * cs))
        }
        return out
    }

    private func hit(_ m: Mark, _ ip: CGPoint) -> Bool {
        let b = block(m)
        let pad = 10 / max(0.01, scale)
        let a = CGFloat(m.a) * CGFloat.pi / 180
        let cs = cos(a), sn = sin(a)
        let px = ip.x - CGFloat(m.x) * imageW, py = ip.y - CGFloat(m.y) * imageH
        let lx = px * cs + py * sn, ly = -px * sn + py * cs
        return abs(lx) <= b.width / 2 + pad && abs(ly) <= b.height / 2 + pad
    }

    private var brushNorm: Double {
        return Double(max(1, brushPt / max(0.01, scale)) / imageW)
    }

    private func strokeMark() -> Mark {
        var m = Mark(t: tool == .mosaic ? Mark.mosaic : Mark.brush)
        m.c = color
        m.w = Edits.q(brushNorm * (tool == .mosaic ? 2.2 : 1))
        var q = [Double]()
        q.reserveCapacity(pts.count * 2)
        for p in pts {
            q.append(Edits.q(Double(p.x / imageW)))
            q.append(Edits.q(Double(p.y / imageH)))
        }
        m.p = q
        return m
    }

    override func onDown(_ ip: CGPoint, _ sp: CGPoint) {
        downI = ip
        downS = sp
        moved = false
        wasSelected = false
        mode = .none
        if tool == .brush || tool == .mosaic {
            mode = .stroke
            pts = [ip]
            setNeedsDisplay()
            return
        }
        if let sel = selectedMark, sel.movable {
            let c = corners(sel)
            let br = toScreen(c[2])
            if hypot(sp.x - br.x, sp.y - br.y) <= 26 {
                mode = .scale
                let center = toScreen(CGPoint(x: CGFloat(sel.x) * imageW, y: CGFloat(sel.y) * imageH))
                startDist = max(8, hypot(sp.x - center.x, sp.y - center.y))
                startSize = sel.h
                return
            }
            let tl = toScreen(c[0])
            if hypot(sp.x - tl.x, sp.y - tl.y) <= 24 {
                mode = .delete
                return
            }
        }
        var found = -1
        var i = marks.count - 1
        while i >= 0 {
            if marks[i].movable && hit(marks[i], ip) {
                found = i
                break
            }
            i -= 1
        }
        if found >= 0 {
            wasSelected = found == selected
            select(found)
            mode = .move
            startPos = CGPoint(x: marks[found].x, y: marks[found].y)
        } else {
            select(-1)
        }
    }

    private func clampD(_ v: Double, _ lo: Double, _ hi: Double) -> Double {
        return v < lo ? lo : (v > hi ? hi : v)
    }

    override func onMove(_ ip: CGPoint, _ sp: CGPoint) {
        if mode == .stroke {
            if let last = pts.last {
                let dx = ip.x - last.x, dy = ip.y - last.y
                if dx * dx + dy * dy >= 1.5 && pts.count < 4096 { pts.append(ip) }
            }
            setNeedsDisplay()
            return
        }
        guard selected >= 0, selected < marks.count else { return }
        if !moved {
            if hypot(sp.x - downS.x, sp.y - downS.y) < 6 { return }
            if mode == .delete {
                mode = .none
                return
            }
            if mode != .move && mode != .scale { return }
            moved = true
            onBeforeChange?()
        }
        if mode == .move {
            marks[selected].x = Edits.q(clampD(Double(startPos.x) + Double((ip.x - downI.x) / imageW), -0.2, 1.2))
            marks[selected].y = Edits.q(clampD(Double(startPos.y) + Double((ip.y - downI.y) / imageH), -0.2, 1.2))
            setNeedsDisplay()
        } else if mode == .scale {
            let m = marks[selected]
            let center = toScreen(CGPoint(x: CGFloat(m.x) * imageW, y: CGFloat(m.y) * imageH))
            let d = hypot(sp.x - center.x, sp.y - center.y)
            let lo = m.t == Mark.sign ? 0.05 : 0.012, hi = m.t == Mark.sign ? 1.6 : 0.6
            marks[selected].h = Edits.q(clampD(startSize * Double(d / startDist), lo, hi))
            setNeedsDisplay()
        }
    }

    override func onUp(_ ip: CGPoint, cancelled: Bool) {
        let m = mode
        mode = .none
        if m == .stroke {
            let has = !pts.isEmpty
            let mk = strokeMark()
            pts = []
            setNeedsDisplay()
            if cancelled || !has || !hasImage { return }
            onBeforeChange?()
            marks.append(mk)
            onChanged?()
            return
        }
        if m == .delete {
            if cancelled || selected < 0 || selected >= marks.count { return }
            onBeforeChange?()
            marks.remove(at: selected)
            selected = -1
            onSelected?()
            onChanged?()
            setNeedsDisplay()
            return
        }
        if moved {
            onChanged?()
        } else if m == .move && wasSelected && !cancelled {
            onTapSelected?()
        }
    }

    override func drawPage(_ ctx: CGContext) {
        guard let r = renderer else { return }
        let base = image?.cgImage
        r.draw(marks, w: imageW, h: imageH, base: base)
        if mode == .stroke && !pts.isEmpty {
            r.drawOne(strokeMark(), w: imageW, h: imageH, base: base)
        }
    }

    override func drawOverlay(_ ctx: CGContext) {
        guard let sel = selectedMark, sel.movable else { return }
        let s = corners(sel).map { toScreen($0) }
        ctx.saveGState()
        ctx.setStrokeColor(Theme.accent.cgColor)
        ctx.setLineWidth(1.5)
        ctx.setLineDash(phase: 0, lengths: [6, 4])
        ctx.move(to: s[0])
        ctx.addLine(to: s[1])
        ctx.addLine(to: s[2])
        ctx.addLine(to: s[3])
        ctx.closePath()
        ctx.strokePath()
        ctx.setLineDash(phase: 0, lengths: [])
        let r: CGFloat = 11, g: CGFloat = 4.5
        // nút xoá (góc trên-trái)
        ctx.setFillColor(Theme.red.cgColor)
        ctx.fillEllipse(in: CGRect(x: s[0].x - r, y: s[0].y - r, width: 2 * r, height: 2 * r))
        ctx.setStrokeColor(UIColor.white.cgColor)
        ctx.setLineWidth(2)
        ctx.setLineCap(.round)
        ctx.move(to: CGPoint(x: s[0].x - g, y: s[0].y - g))
        ctx.addLine(to: CGPoint(x: s[0].x + g, y: s[0].y + g))
        ctx.move(to: CGPoint(x: s[0].x - g, y: s[0].y + g))
        ctx.addLine(to: CGPoint(x: s[0].x + g, y: s[0].y - g))
        ctx.strokePath()
        // nút co giãn (góc dưới-phải)
        ctx.setFillColor(Theme.accent.cgColor)
        ctx.fillEllipse(in: CGRect(x: s[2].x - r, y: s[2].y - r, width: 2 * r, height: 2 * r))
        ctx.move(to: CGPoint(x: s[2].x - g, y: s[2].y - g))
        ctx.addLine(to: CGPoint(x: s[2].x + g, y: s[2].y + g))
        ctx.move(to: CGPoint(x: s[2].x + g - 4, y: s[2].y + g))
        ctx.addLine(to: CGPoint(x: s[2].x + g, y: s[2].y + g))
        ctx.addLine(to: CGPoint(x: s[2].x + g, y: s[2].y + g - 4))
        ctx.move(to: CGPoint(x: s[2].x - g + 4, y: s[2].y - g))
        ctx.addLine(to: CGPoint(x: s[2].x - g, y: s[2].y - g))
        ctx.addLine(to: CGPoint(x: s[2].x - g, y: s[2].y - g + 4))
        ctx.strokePath()
        ctx.restoreGState()
    }
}

/// Khung ký tên: vẽ bằng ngón tay, xuất ảnh PNG nền trong suốt cắt sát nét ký.
final class SignPadView: UIView {
    private var strokes = [(path: UIBezierPath, color: UIColor)]()
    private var current: UIBezierPath?
    private var last = CGPoint.zero
    private var box = CGRect.null
    var ink = UIColor(rgb: 0x111111)
    private let lineWidth: CGFloat = 3.2

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .white
        layer.cornerRadius = 10
        clipsToBounds = true
        isMultipleTouchEnabled = false
        contentMode = .redraw
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    var isEmpty: Bool { strokes.isEmpty }

    func clear() {
        strokes.removeAll()
        current = nil
        box = CGRect.null
        setNeedsDisplay()
    }

    private func grow(_ p: CGPoint) {
        box = box.union(CGRect(x: p.x, y: p.y, width: 0.01, height: 0.01))
    }

    private func begin(_ p: CGPoint) {
        let path = UIBezierPath()
        path.lineWidth = lineWidth
        path.lineCapStyle = .round
        path.lineJoinStyle = .round
        path.move(to: p)
        path.addLine(to: CGPoint(x: p.x + 0.01, y: p.y + 0.01))
        current = path
        strokes.append((path, ink))
        last = p
        grow(p)
        setNeedsDisplay()
    }

    private func extend(_ p: CGPoint) {
        guard let path = current else { return }
        // làm mượt nét bằng đường cong bậc hai qua trung điểm
        path.addQuadCurve(to: CGPoint(x: (p.x + last.x) / 2, y: (p.y + last.y) / 2), controlPoint: last)
        last = p
        grow(p)
        setNeedsDisplay()
    }

    private func end(_ p: CGPoint) {
        if let path = current {
            path.addLine(to: p)
            grow(p)
        }
        current = nil
        setNeedsDisplay()
    }

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
        if let t = touches.first { begin(t.location(in: self)) }
    }

    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {
        if let t = touches.first { extend(t.location(in: self)) }
    }

    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
        if let t = touches.first { end(t.location(in: self)) }
    }

    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) {
        if let t = touches.first { end(t.location(in: self)) }
    }

    override func draw(_ rect: CGRect) {
        let y = bounds.height * 0.74
        let guide = UIBezierPath()
        guide.move(to: CGPoint(x: 20, y: y))
        guide.addLine(to: CGPoint(x: bounds.width - 20, y: y))
        guide.lineWidth = 1
        UIColor(rgb: 0xBDBDBD).setStroke()
        guide.stroke()
        for s in strokes {
            s.color.setStroke()
            s.path.stroke()
        }
    }

    /// Ảnh PNG nền trong suốt cắt sát nét ký; nil nếu chưa ký gì đáng kể.
    func exportPNG() -> Data? {
        if strokes.isEmpty || box.isNull { return nil }
        let pad = lineWidth * 1.5
        let r = box.insetBy(dx: -pad, dy: -pad).intersection(bounds)
        if r.isNull || (r.width < 24 && r.height < 24) { return nil }
        let size = CGSize(width: max(8, r.width), height: max(8, r.height))
        let fmt = UIGraphicsImageRendererFormat()
        fmt.scale = 3
        fmt.opaque = false
        let img = UIGraphicsImageRenderer(size: size, format: fmt).image { c in
            c.cgContext.translateBy(x: -r.minX, y: -r.minY)
            for s in self.strokes {
                s.color.setStroke()
                s.path.stroke()
            }
        }
        return img.pngData()
    }

    // MARK: tự kiểm thử

    /// Giả lập một nét ký qua các điểm chuẩn hoá (0..1 theo khung ký).
    func testStroke(_ pts: [CGPoint]) {
        guard let first = pts.first else { return }
        func p(_ n: CGPoint) -> CGPoint { return CGPoint(x: n.x * bounds.width, y: n.y * bounds.height) }
        begin(p(first))
        for k in 1..<max(1, pts.count) { extend(p(pts[k])) }
        end(p(pts[pts.count - 1]))
    }
}

/// "Đánh dấu": Ký tên, Thêm văn bản, Brush (vẽ tay), Khảm (che thông tin), Thêm hình mờ.
/// Kết quả trả về là danh sách đánh dấu; ảnh độ phân giải đầy đủ do ImageOps.render dựng lại sau.
final class MarkupViewController: EditScreenController {
    static let sign = 0, text = 1, brush = 2, mosaic = 3, watermark = 4

    var onDone: (([Mark]) -> Void)?
    var onCancel: (() -> Void)?

    private static let tools: [(symbol: String, name: String, hint: String)] = [
        ("signature", "Ký tên", "Chọn chữ ký rồi kéo tới vị trí cần ký"),
        ("textformat", "Thêm văn bản", "Kéo để di chuyển, kéo nút góc để đổi cỡ, chạm lần nữa để sửa"),
        ("paintbrush", "Brush", "Vẽ trực tiếp lên trang"),
        ("square.grid.3x3.fill", "Khảm", "Tô lên phần cần che"),
        ("drop", "Thêm hình mờ", "Hình mờ phủ chéo toàn trang")
    ]
    static let colors: [UInt32] = [0xFF1A1A1A, 0xFFE53935, 0xFF1E50C8, 0xFF1B8E3E, 0xFFF9A825, 0xFFFFFFFF]
    private static let colorNames = ["Màu đen", "Màu đỏ", "Màu xanh dương", "Màu xanh lá", "Màu vàng", "Màu trắng"]
    private static let sizes: [CGFloat] = [4, 8, 15]
    private static let sizeNames = ["Nét nhỏ", "Nét vừa", "Nét lớn"]
    private static let maxUndo = 40

    private let dir: URL
    private let page: Page
    private let canvas = MarkCanvas()
    private let renderer: MarkRenderer
    private(set) var tool: Int
    private var colorIndex = 0
    private var sizeIndex = 1
    private(set) var loaded = false
    private var finished = false
    private var undoStack = [[Mark]]()
    private var redoStack = [[Mark]]()
    private let padLayer = UIView()
    private let pad = SignPadView()
    private var inkDots = [UIButton]()
    private var inkIndex = 0
    private static let inks: [UInt32] = [0x111111, 0x1E3FAE, 0xD32F2F]
    private static let inkNames = ["Mực đen", "Mực xanh", "Mực đỏ"]

    init(doc: Doc, page: Page, tool: Int) {
        self.dir = DocStore.shared.dir(doc)
        self.page = page
        self.renderer = MarkRenderer(dir: DocStore.shared.dir(doc))
        self.tool = max(MarkupViewController.sign, min(MarkupViewController.watermark, tool))
        super.init(nibName: nil, bundle: nil)
        modalPresentationStyle = .fullScreen
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    var marks: [Mark] { canvas.marks }

    override func viewDidLoad() {
        super.viewDidLoad()
        buildChrome(title: "Đánh dấu", tools: MarkupViewController.tools.map { ($0.symbol, $0.name) })
        installCanvas(canvas)
        canvas.accessibilityLabel = "Trang đang đánh dấu"
        canvas.renderer = renderer
        canvas.onBeforeChange = { [weak self] in self?.pushUndo() }
        canvas.onChanged = { [weak self] in self?.refreshUndo() }
        canvas.onSelected = { [weak self] in self?.refreshOptions() }
        canvas.onTapSelected = { [weak self] in
            guard let self = self, let m = self.canvas.selectedMark, m.t == Mark.text else { return }
            self.editText(self.canvas.selected)
        }
        buildPad()
        canvas.setMarks(page.marks ?? [])
        selectTool(tool, user: false)
        refreshUndo()
        spinner.startAnimating()
        load()
    }

    // MARK: giao diện

    private func chipButton(_ title: String, _ action: Selector) -> UIButton {
        let b = EditScreenController.chip(title)
        b.addTarget(self, action: action, for: .touchUpInside)
        return b
    }

    private func addColors() {
        for k in 0..<MarkupViewController.colors.count {
            let b = EditScreenController.dot(diameter: 26, color: Edits.color(MarkupViewController.colors[k]), selected: k == colorIndex,
                                             label: MarkupViewController.colorNames[k])
            b.tag = k
            b.addTarget(self, action: #selector(colorTapped(_:)), for: .touchUpInside)
            optionsRow.addArrangedSubview(b)
        }
    }

    private func addSizes() {
        for k in 0..<MarkupViewController.sizes.count {
            let b = EditScreenController.dot(diameter: CGFloat(8 + 6 * k), color: k == sizeIndex ? Theme.accent : UIColor(rgb: 0x8A898E),
                                             selected: false, label: MarkupViewController.sizeNames[k], box: 44)
            b.tag = k
            b.addTarget(self, action: #selector(sizeTapped(_:)), for: .touchUpInside)
            optionsRow.addArrangedSubview(b)
        }
    }

    private var watermarkIndex: Int? {
        return canvas.marks.firstIndex { $0.t == Mark.watermark }
    }

    /// Dựng lại hàng tuỳ chọn theo công cụ và mục đang chọn.
    private func refreshOptions() {
        clearOptions()
        switch tool {
        case MarkupViewController.sign:
            optionsRow.addArrangedSubview(chipButton("＋ Chữ ký mới", #selector(openPad)))
            let lib = DocStore.shared.signLibrary()
            for (k, u) in lib.prefix(12).enumerated() {
                let b = UIButton(type: .custom)
                b.backgroundColor = .white
                b.layer.cornerRadius = 6
                b.clipsToBounds = true
                b.imageView?.contentMode = .scaleAspectFit
                b.setImage(UIImage(contentsOfFile: u.path), for: .normal)
                b.contentEdgeInsets = UIEdgeInsets(top: 3, left: 3, bottom: 3, right: 3)
                b.accessibilityLabel = "Chữ ký \(k + 1)"
                b.tag = k
                b.addTarget(self, action: #selector(libraryTapped(_:)), for: .touchUpInside)
                b.addGestureRecognizer(UILongPressGestureRecognizer(target: self, action: #selector(libraryLongPressed(_:))))
                b.translatesAutoresizingMaskIntoConstraints = false
                b.widthAnchor.constraint(equalToConstant: 76).isActive = true
                b.heightAnchor.constraint(equalToConstant: 38).isActive = true
                optionsRow.addArrangedSubview(b)
            }
        case MarkupViewController.text:
            optionsRow.addArrangedSubview(chipButton("＋ Thêm chữ", #selector(newText)))
            if let m = canvas.selectedMark, m.t == Mark.text {
                optionsRow.addArrangedSubview(chipButton("Sửa chữ", #selector(editSelectedText)))
            }
            addColors()
        case MarkupViewController.brush:
            addColors()
            addSizes()
        case MarkupViewController.mosaic:
            addSizes()
        default:
            let has = watermarkIndex != nil
            optionsRow.addArrangedSubview(chipButton(has ? "Sửa nội dung" : "＋ Thêm hình mờ", #selector(editWatermark)))
            if has { optionsRow.addArrangedSubview(chipButton("Gỡ hình mờ", #selector(removeWatermark))) }
            addColors()
        }
    }

    private func selectTool(_ t: Int, user: Bool) {
        tool = t
        setSelectedTool(t)
        hintLabel.text = MarkupViewController.tools[t].hint
        canvas.tool = t == MarkupViewController.brush ? .brush : (t == MarkupViewController.mosaic ? .mosaic : .move)
        canvas.color = MarkupViewController.colors[colorIndex]
        canvas.brushPt = MarkupViewController.sizes[sizeIndex]
        refreshOptions()
        if user && loaded { startTool() }
    }

    override func toolTapped(_ index: Int) {
        selectTool(index, user: true)
    }

    /// Việc cần làm ngay khi vào một công cụ: mở bảng ký / hỏi nội dung chữ / hỏi nội dung hình mờ.
    private func startTool() {
        if finished || presentedViewController != nil { return }
        if tool == MarkupViewController.sign {
            if DocStore.shared.signLibrary().isEmpty { openPad() }
        } else if tool == MarkupViewController.text {
            if !canvas.marks.contains(where: { $0.t == Mark.text }) { newText() }
        } else if tool == MarkupViewController.watermark {
            if watermarkIndex == nil { editWatermark() }
        }
    }

    @objc private func colorTapped(_ b: UIButton) {
        let idx = max(0, min(MarkupViewController.colors.count - 1, b.tag))
        colorIndex = idx
        let c = MarkupViewController.colors[idx]
        canvas.color = c
        let sel = canvas.selected
        if tool == MarkupViewController.text, sel >= 0, sel < canvas.marks.count, canvas.marks[sel].t == Mark.text, canvas.marks[sel].c != c {
            pushUndo()
            canvas.marks[sel].c = c
            canvas.setNeedsDisplay()
            refreshUndo()
        } else if tool == MarkupViewController.watermark, let w = watermarkIndex {
            pushUndo()
            canvas.marks[w].c = MarkupViewController.watermarkColor(c)
            canvas.setNeedsDisplay()
            refreshUndo()
        }
        refreshOptions()
    }

    @objc private func sizeTapped(_ b: UIButton) {
        sizeIndex = max(0, min(MarkupViewController.sizes.count - 1, b.tag))
        canvas.brushPt = MarkupViewController.sizes[sizeIndex]
        refreshOptions()
    }

    /// Màu hình mờ: màu đã chọn với độ đục ~28%.
    private static func watermarkColor(_ c: UInt32) -> UInt32 {
        return (c & 0x00FFFFFF) | 0x48000000
    }

    // MARK: nạp ảnh nền

    private func load() {
        let dir = self.dir
        let snap = page
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            // đánh dấu vẽ SAU bộ lọc (như ImageOps.render): xem trước với đủ bộ lọc, kể cả bước nắn thẳng của "Làm phẳng"
            // (nắn ở đúng kích thước ảnh kết quả rồi mới thu nhỏ)
            let out = ImageOps.markupBase(dir: dir, page: snap)
            DispatchQueue.main.async {
                guard let self = self, !self.finished else { return }
                self.spinner.stopAnimating()
                guard let cg = out else {
                    self.toast("Không mở được trang để chỉnh sửa")
                    self.cancelTapped()
                    return
                }
                self.loaded = true
                self.canvas.setImage(UIImage(cgImage: cg), rotation: self.page.rot)
                self.startTool()
            }
        }
    }

    // MARK: hoàn tác

    private func pushUndo() {
        undoStack.append(canvas.marks)
        if undoStack.count > MarkupViewController.maxUndo { undoStack.removeFirst() }
        redoStack.removeAll()
    }

    private func refreshUndo() {
        setUndo(!undoStack.isEmpty, !redoStack.isEmpty)
    }

    override func undoTapped() {
        guard let prev = undoStack.popLast() else { return }
        redoStack.append(canvas.marks)
        canvas.setMarks(prev)
        refreshUndo()
    }

    override func redoTapped() {
        guard let next = redoStack.popLast() else { return }
        undoStack.append(canvas.marks)
        canvas.setMarks(next)
        refreshUndo()
    }

    // MARK: chữ, hình mờ

    /// Góc để mục mới nằm thẳng theo chiều trang đang hiển thị.
    private var uprightAngle: Int { (360 - page.rot) % 360 }

    /// Hỏi một dòng chữ; chuỗi rỗng thì bỏ qua.
    private func ask(_ title: String, _ initial: String, _ done: @escaping (String) -> Void) {
        if finished || presentedViewController != nil { return }
        prompt(title, initial: initial) { v in
            if !v.isEmpty { done(v) }
        }
    }

    @objc func newText() {
        if !loaded { return }
        ask("Thêm văn bản", "") { [weak self] v in self?.addText(v) }
    }

    func addText(_ v: String) {
        if !loaded || finished || v.isEmpty { return }
        var m = Mark(t: Mark.text)
        m.s = v
        m.c = MarkupViewController.colors[colorIndex]
        m.h = Edits.q(0.042 * Double(canvas.shownW / canvas.imageW))
        m.a = uprightAngle
        // mỗi mục mới lệch xuống một chút để không chồng khít lên mục trước
        let cnt = canvas.marks.filter { $0.t == Mark.text }.count
        m.x = 0.5
        m.y = min(0.9, 0.42 + 0.05 * Double(cnt % 8))
        pushUndo()
        canvas.marks.append(m)
        canvas.select(canvas.marks.count - 1)
        canvas.setNeedsDisplay()
        refreshUndo()
        refreshOptions()
    }

    @objc private func editSelectedText() {
        editText(canvas.selected)
    }

    private func editText(_ index: Int) {
        guard index >= 0, index < canvas.marks.count, canvas.marks[index].t == Mark.text else { return }
        let old = canvas.marks[index]
        ask("Sửa văn bản", old.s ?? "") { [weak self] v in
            guard let self = self, let i = self.canvas.marks.firstIndex(of: old), v != old.s else { return }
            self.pushUndo()
            self.canvas.marks[i].s = v
            self.canvas.setNeedsDisplay()
            self.refreshUndo()
        }
    }

    @objc func editWatermark() {
        if !loaded { return }
        let cur = watermarkIndex.map { canvas.marks[$0].s ?? "" } ?? ""
        ask("Nội dung hình mờ", cur) { [weak self] v in self?.setWatermark(v) }
    }

    func setWatermark(_ v: String) {
        if !loaded || finished || v.isEmpty { return }
        if let w = watermarkIndex {
            if canvas.marks[w].s == v { return }
            pushUndo()
            canvas.marks[w].s = v
        } else {
            var m = Mark(t: Mark.watermark)
            m.c = MarkupViewController.watermarkColor(MarkupViewController.colors[colorIndex == 5 ? 0 : colorIndex])
            m.h = 0.036
            m.s = v
            pushUndo()
            canvas.marks.append(m)
        }
        canvas.setNeedsDisplay()
        refreshUndo()
        refreshOptions()
    }

    @objc private func removeWatermark() {
        guard let w = watermarkIndex else { return }
        pushUndo()
        canvas.marks.remove(at: w)
        canvas.select(-1)
        canvas.setNeedsDisplay()
        refreshUndo()
        refreshOptions()
    }

    // MARK: chữ ký

    @objc private func libraryTapped(_ b: UIButton) {
        let lib = DocStore.shared.signLibrary()
        if b.tag >= 0 && b.tag < lib.count { placeSign(lib[b.tag]) }
    }

    @objc private func libraryLongPressed(_ g: UILongPressGestureRecognizer) {
        guard g.state == .began, let b = g.view as? UIButton, presentedViewController == nil else { return }
        let lib = DocStore.shared.signLibrary()
        guard b.tag >= 0 && b.tag < lib.count else { return }
        let u = lib[b.tag]
        confirm("Xoá chữ ký đã lưu này?", message: nil, ok: "Xoá") { [weak self] in
            try? FileManager.default.removeItem(at: u)
            self?.refreshOptions()
        }
    }

    /// Đặt chữ ký (file trong thư viện) lên trang; file được chép vào thư mục tài liệu.
    func placeSign(_ lib: URL) {
        if !loaded || finished { return }
        let name = "sg_" + lib.lastPathComponent
        let dst = dir.appendingPathComponent(name)
        let fm = FileManager.default
        if !fm.fileExists(atPath: dst.path) {
            do {
                try fm.copyItem(at: lib, to: dst)
            } catch {
                toast("Không dùng được chữ ký: \(error.localizedDescription)")
                return
            }
        }
        var m = Mark(t: Mark.sign)
        m.f = name
        m.h = Edits.q(0.34 * Double(canvas.shownW / canvas.imageW))
        m.a = uprightAngle
        let cnt = canvas.marks.filter { $0.t == Mark.sign }.count
        m.x = min(0.85, 0.5 + 0.04 * Double(cnt % 6))
        m.y = min(0.9, 0.6 + 0.04 * Double(cnt % 6))
        pushUndo()
        canvas.marks.append(m)
        canvas.select(canvas.marks.count - 1)
        canvas.setNeedsDisplay()
        refreshUndo()
    }

    private func buildPad() {
        padLayer.backgroundColor = UIColor(rgb: 0x101012, alpha: 0.9)
        padLayer.isHidden = true
        padLayer.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(padLayer)
        let title = Theme.label("Ký vào khung bên dưới", size: 16, weight: .semibold)
        title.textAlignment = .center
        pad.accessibilityLabel = "Khung ký tên"
        let inkRow = UIStackView()
        inkRow.axis = .horizontal
        inkRow.spacing = 8
        for k in 0..<MarkupViewController.inks.count {
            let b = EditScreenController.dot(diameter: 26, color: UIColor(rgb: MarkupViewController.inks[k]), selected: k == inkIndex,
                                             label: MarkupViewController.inkNames[k], box: 48)
            b.tag = k
            b.addTarget(self, action: #selector(inkTapped(_:)), for: .touchUpInside)
            inkRow.addArrangedSubview(b)
            inkDots.append(b)
        }
        let close = Theme.textButton("Đóng", color: .white)
        close.addTarget(self, action: #selector(closePad), for: .touchUpInside)
        let again = Theme.textButton("Ký lại", color: .white)
        again.addTarget(self, action: #selector(clearPad), for: .touchUpInside)
        let save = Theme.primaryButton("Lưu chữ ký")
        save.addTarget(self, action: #selector(savePad), for: .touchUpInside)
        let btns = UIStackView(arrangedSubviews: [close, again, save])
        btns.axis = .horizontal
        // "Lưu chữ ký" cần rộng hơn một phần ba hàng mới đủ một dòng; hai nút chữ chia đều phần còn lại
        btns.distribution = .fill
        btns.spacing = 8
        [close, again, save].forEach { $0.translatesAutoresizingMaskIntoConstraints = false }
        save.widthAnchor.constraint(equalToConstant: 140).isActive = true
        close.widthAnchor.constraint(equalTo: again.widthAnchor).isActive = true
        [title, pad, inkRow, btns].forEach {
            $0.translatesAutoresizingMaskIntoConstraints = false
            padLayer.addSubview($0)
        }
        NSLayoutConstraint.activate([
            padLayer.topAnchor.constraint(equalTo: view.topAnchor),
            padLayer.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            padLayer.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            padLayer.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            pad.centerYAnchor.constraint(equalTo: padLayer.centerYAnchor, constant: -20),
            pad.leadingAnchor.constraint(equalTo: padLayer.leadingAnchor, constant: 16),
            pad.trailingAnchor.constraint(equalTo: padLayer.trailingAnchor, constant: -16),
            pad.heightAnchor.constraint(equalToConstant: 230),
            title.bottomAnchor.constraint(equalTo: pad.topAnchor, constant: -12),
            title.centerXAnchor.constraint(equalTo: padLayer.centerXAnchor),
            inkRow.topAnchor.constraint(equalTo: pad.bottomAnchor, constant: 8),
            inkRow.centerXAnchor.constraint(equalTo: padLayer.centerXAnchor),
            btns.topAnchor.constraint(equalTo: inkRow.bottomAnchor, constant: 8),
            btns.leadingAnchor.constraint(equalTo: pad.leadingAnchor),
            btns.trailingAnchor.constraint(equalTo: pad.trailingAnchor),
            btns.heightAnchor.constraint(equalToConstant: 46)
        ])
    }

    @objc private func inkTapped(_ b: UIButton) {
        inkIndex = max(0, min(MarkupViewController.inks.count - 1, b.tag))
        pad.ink = UIColor(rgb: MarkupViewController.inks[inkIndex])
        for (k, d) in inkDots.enumerated() {
            if let dot = d.subviews.first {
                dot.layer.borderWidth = k == inkIndex ? 3 : 1
                dot.layer.borderColor = (k == inkIndex ? Theme.accent : UIColor(rgb: 0x8A898E)).cgColor
            }
        }
    }

    var padOpen: Bool { !padLayer.isHidden }

    @objc func openPad() {
        if finished { return }
        pad.clear()
        padLayer.isHidden = false
        view.bringSubviewToFront(padLayer)
    }

    @objc func closePad() {
        padLayer.isHidden = true
    }

    @objc private func clearPad() {
        pad.clear()
    }

    @objc func savePad() {
        guard let data = pad.exportPNG() else {
            toast("Hãy ký vào khung trước khi lưu")
            return
        }
        let name = "s" + String(Int(Date().timeIntervalSince1970 * 1000), radix: 36) + ".png"
        let u = DocStore.shared.signDir.appendingPathComponent(name)
        do {
            try data.write(to: u, options: .atomic)
        } catch {
            toast("Không lưu được chữ ký: \(error.localizedDescription)")
            return
        }
        closePad()
        refreshOptions()
        placeSign(u)
    }

    // MARK: kết thúc

    override func applyTapped() {
        if !loaded || finished { return }
        finished = true
        onDone?(canvas.marks)
    }

    override func cancelTapped() {
        if finished { return }
        if padOpen {
            closePad()
            return
        }
        finished = true
        onCancel?()
    }

    // MARK: tự kiểm thử

    var testReady: Bool { loaded }

    /// Ảnh nền đang hiển thị (đã qua bộ lọc đầy đủ của trang, như ảnh kết quả trước khi vẽ đánh dấu).
    var testImage: UIImage? { canvas.image }

    func testSelect(_ t: Int) { selectTool(t, user: false) }

    func testStroke(_ pts: [CGPoint]) { canvas.testStroke(pts) }

    func testPickColor(_ index: Int) {
        colorIndex = max(0, min(MarkupViewController.colors.count - 1, index))
        canvas.color = MarkupViewController.colors[colorIndex]
        refreshOptions()
    }

    func testSign(_ pts: [CGPoint]) { pad.testStroke(pts) }

    var testSelected: Int { canvas.selected }
}
