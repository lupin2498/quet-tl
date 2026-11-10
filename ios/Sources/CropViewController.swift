import UIKit

/// Màn hình chọn vùng cắt: 4 góc + tay cầm giữa cạnh, kính lúp, "Tự động cắt".
final class CropViewController: UIViewController {
    enum Mode { case capture, edit }

    /// Tứ giác theo toạ độ ảnh đầy đủ (fullSize) + trạng thái "Tự động cắt".
    var onDone: (([CGPoint], Bool) -> Void)?
    /// Chế độ chụp: "Chụp lại"; chế độ sửa: "Hủy".
    var onCancel: (() -> Void)?

    private let mode: Mode
    private let display: UIImage
    private let fullSize: CGSize
    private let initQuad: [CGPoint]?
    private var autoOn: Bool
    private var detected: [CGPoint]?
    private var detecting = false
    private var loaded = false
    /// Đã bấm Xong/Hủy (chống báo kết quả hai lần khi chạm đúp -> thêm trùng trang).
    private var finished = false

    private let crop = CropOverlayView()
    private let spinner = UIActivityIndicatorView(style: .large)
    private let autoBtn = UIButton(type: .custom)

    /// - image: ảnh hiển thị (có thể đã thu nhỏ); fullSize: kích thước ảnh gốc dùng cho toạ độ tứ giác.
    init(image: UIImage, fullSize: CGSize? = nil, quad: [CGPoint]?, auto: Bool, mode: Mode) {
        let norm = ImageOps.normalized(image, maxDim: 2048)
        self.display = norm
        let px = CGSize(width: image.size.width * image.scale, height: image.size.height * image.scale)
        self.fullSize = fullSize ?? px
        self.initQuad = quad
        self.autoOn = quad == nil ? true : auto
        self.mode = mode
        super.init(nibName: nil, bundle: nil)
        modalPresentationStyle = .fullScreen
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    override var preferredStatusBarStyle: UIStatusBarStyle { .lightContent }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = Theme.bgDark
        buildUI()
        load()
    }

    private func buildUI() {
        crop.translatesAutoresizingMaskIntoConstraints = false
        crop.accessibilityIdentifier = "cropView"
        view.addSubview(crop)
        spinner.color = Theme.accent
        spinner.hidesWhenStopped = true
        spinner.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(spinner)
        spinner.startAnimating()

        let hint = UILabel()
        hint.numberOfLines = 0
        hint.textColor = .white
        hint.font = UIFont.systemFont(ofSize: 15, weight: .medium)
        if mode == .capture {
            let s = NSMutableAttributedString(string: "Kéo tay cầm trong hình để điều chỉnh phạm vi cắt hoặc sử dụng công cụ cắt ")
            if let icon = Theme.symbol("crop", size: 15)?.withTintColor(.white, renderingMode: .alwaysOriginal) {
                let att = NSTextAttachment()
                att.image = icon
                s.append(NSAttributedString(attachment: att))
            }
            s.append(NSAttributedString(string: " sau đó để điều chỉnh"))
            let para = NSMutableParagraphStyle()
            para.lineSpacing = 3
            s.addAttributes([.foregroundColor: UIColor.white, .font: UIFont.systemFont(ofSize: 15, weight: .medium), .paragraphStyle: para],
                            range: NSRange(location: 0, length: s.length))
            hint.attributedText = s
        } else {
            hint.text = "Kéo 4 góc hoặc cạnh để chọn đúng vùng trang giấy"
        }
        hint.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(hint)

        var cfg = UIButton.Configuration.plain()
        cfg.title = "Tự động cắt"
        cfg.imagePadding = 7
        cfg.baseForegroundColor = .white
        cfg.titleLineBreakMode = .byClipping
        cfg.contentInsets = NSDirectionalEdgeInsets(top: 8, leading: 2, bottom: 8, trailing: 4)
        cfg.titleTextAttributesTransformer = UIConfigurationTextAttributesTransformer { incoming in
            var out = incoming
            out.font = UIFont.systemFont(ofSize: 15, weight: .medium)
            return out
        }
        autoBtn.configuration = cfg
        autoBtn.accessibilityIdentifier = "autoCrop"
        autoBtn.addTarget(self, action: #selector(toggleAuto), for: .touchUpInside)

        let second = Theme.textButton(mode == .capture ? "Chụp lại" : "Hủy")
        second.configuration?.contentInsets = NSDirectionalEdgeInsets(top: 8, leading: 6, bottom: 8, trailing: 6)
        second.addTarget(self, action: #selector(cancelTapped), for: .touchUpInside)
        let ok = Theme.primaryButton(mode == .capture ? "Tiếp tục" : "Xong")
        ok.configuration?.contentInsets = NSDirectionalEdgeInsets(top: 10, leading: 16, bottom: 10, trailing: 16)
        ok.addTarget(self, action: #selector(doneTapped), for: .touchUpInside)
        ok.accessibilityIdentifier = "cropDone"

        let spacer = UIView()
        let row = UIStackView(arrangedSubviews: [autoBtn, spacer, second, ok])
        row.axis = .horizontal
        row.alignment = .center
        row.spacing = 4
        row.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(row)
        spacer.setContentHuggingPriority(.defaultLow, for: .horizontal)
        spacer.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        for b in [autoBtn, second, ok] {
            b.setContentHuggingPriority(.required, for: .horizontal)
            b.setContentCompressionResistancePriority(.required, for: .horizontal)
            b.titleLabel?.numberOfLines = 1
        }

        let g = view.safeAreaLayoutGuide
        NSLayoutConstraint.activate([
            crop.topAnchor.constraint(equalTo: g.topAnchor, constant: 4),
            crop.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            crop.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            crop.bottomAnchor.constraint(equalTo: hint.topAnchor, constant: -6),
            spinner.centerXAnchor.constraint(equalTo: crop.centerXAnchor),
            spinner.centerYAnchor.constraint(equalTo: crop.centerYAnchor),
            hint.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 18),
            hint.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -18),
            hint.bottomAnchor.constraint(equalTo: row.topAnchor, constant: -8),
            row.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 10),
            row.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -14),
            row.heightAnchor.constraint(equalToConstant: 60),
            row.bottomAnchor.constraint(equalTo: g.bottomAnchor, constant: -8)
        ])
        crop.onChange = { [weak self] byUser in
            guard let self = self else { return }
            if byUser && self.autoOn {
                self.autoOn = false
                self.updateAuto()
            }
        }
        updateAuto()
    }

    private func updateAuto() {
        let s: CGFloat = 20
        let r = UIGraphicsImageRenderer(size: CGSize(width: s, height: s))
        let on = autoOn
        let img = r.image { ctx in
            let c = ctx.cgContext
            c.setLineWidth(2)
            c.setStrokeColor((on ? Theme.accent : UIColor(rgb: 0x9A999E)).cgColor)
            c.strokeEllipse(in: CGRect(x: 1, y: 1, width: s - 2, height: s - 2))
            if on {
                c.setFillColor(Theme.accent.cgColor)
                c.fillEllipse(in: CGRect(x: 5, y: 5, width: s - 10, height: s - 10))
            }
        }
        autoBtn.configuration?.image = img.withRenderingMode(.alwaysOriginal)
        autoBtn.accessibilityValue = autoOn ? "Bật" : "Tắt"
    }

    private func scaleToFull(_ q: [CGPoint]) -> [CGPoint] {
        let kx = fullSize.width / max(1, display.size.width)
        let ky = fullSize.height / max(1, display.size.height)
        return Geom.scale(q, kx, ky)
    }

    private func load() {
        crop.setImage(display, fullSize: fullSize)
        if let q = initQuad {
            crop.setQuad(q, animated: false)
            loaded = true
            spinner.stopAnimating()
            updateAuto()
            return
        }
        crop.setQuad(Geom.fullQuad(fullSize.width, fullSize.height), animated: false)
        let img = display
        DispatchQueue.global(qos: .userInitiated).async {
            let q = img.cgImage.flatMap { ImageOps.detect($0) }
            DispatchQueue.main.async {
                self.spinner.stopAnimating()
                self.loaded = true
                if let q = q {
                    let full = self.scaleToFull(q)
                    self.detected = full
                    self.crop.setQuad(full, animated: false)
                } else {
                    self.toast("Chưa nhận ra mép giấy — hãy kéo các góc để chọn vùng")
                }
                self.autoOn = true
                self.updateAuto()
            }
        }
    }

    @objc private func toggleAuto() {
        guard loaded, !finished else { return }
        if autoOn {
            autoOn = false
            crop.setQuad(Geom.fullQuad(fullSize.width, fullSize.height), animated: true)
            updateAuto()
            return
        }
        if let d = detected {
            autoOn = true
            crop.setQuad(d, animated: true)
            updateAuto()
            return
        }
        if detecting { return }
        detecting = true
        spinner.startAnimating()
        let img = display
        DispatchQueue.global(qos: .userInitiated).async {
            let q = img.cgImage.flatMap { ImageOps.detect($0) }
            DispatchQueue.main.async {
                self.detecting = false
                self.spinner.stopAnimating()
                let full = q.map { self.scaleToFull($0) } ?? Geom.fullQuad(self.fullSize.width, self.fullSize.height)
                self.detected = full
                self.autoOn = true
                self.crop.setQuad(full, animated: true)
                self.updateAuto()
                if q == nil { self.toast("Chưa nhận ra mép giấy") }
            }
        }
    }

    // MARK: móc kiểm thử (SelfTest)

    var testAutoOn: Bool { autoOn }
    func testToggleAuto() { toggleAuto() }
    func testDragCorner(_ i: Int, by d: CGPoint) { crop.testDrag(corner: i, by: d) }
    func testEndDrag() { crop.testEndDrag() }
    func testCancel() { cancelTapped() }

    @objc private func cancelTapped() {
        if finished { return }
        finished = true
        if let c = onCancel { c() } else { dismiss(animated: true) }
    }

    @objc func doneTapped() {
        guard loaded, !finished else { return }
        // kết thúc hoạt ảnh chuyển khung (nếu đang chạy) để lấy đúng khung đích, không phải khung trung gian
        crop.finishAnimation()
        guard crop.isValidQuad else {
            toast("Vùng cắt không hợp lệ — hãy chỉnh lại các góc")
            return
        }
        var q = Geom.order(crop.quad)
        if !Geom.isConvex(q) { q = crop.quad }
        finished = true
        onDone?(q, autoOn)
    }

    /// Khung cắt hiện tại (toạ độ ảnh đầy đủ) — dùng cho tự kiểm thử.
    var testQuad: [CGPoint] { crop.quad }
    var testFullSize: CGSize { fullSize }
}

/// Ảnh + khung cắt. Ảnh và kính lúp do GPU vẽ (UIImageView), khung do CAShapeLayer vẽ nên kéo mượt.
final class CropOverlayView: UIView {
    var onChange: ((Bool) -> Void)?
    private(set) var quad: [CGPoint] = []
    private var imgW: CGFloat = 1, imgH: CGFloat = 1
    private var scale: CGFloat = 1
    private var off = CGPoint.zero
    private let imageView = UIImageView()
    private let shade = CAShapeLayer()
    private let outline = CAShapeLayer()
    private let handles = CAShapeLayer()
    private let loupe = UIView()
    private let loupeContent = UIView()
    private let loupeImage = UIImageView()
    private let loupeLine = CAShapeLayer()
    private let loupeCross = CAShapeLayer()
    private var dragCorner = -1, dragEdge = -1
    private var downPt = CGPoint.zero, grab = CGPoint.zero
    private var quadStart: [CGPoint] = []
    private var link: CADisplayLink?
    private var animFrom: [CGPoint] = [], animTo: [CGPoint] = []
    private var animStart: CFTimeInterval = 0
    private let loupeR: CGFloat = 58
    private let zoom: CGFloat = 2.2

    override init(frame: CGRect) {
        super.init(frame: frame)
        isMultipleTouchEnabled = false
        imageView.contentMode = .scaleToFill
        addSubview(imageView)
        shade.fillRule = .evenOdd
        shade.fillColor = UIColor(white: 0, alpha: 0.25).cgColor
        layer.addSublayer(shade)
        outline.fillColor = UIColor.clear.cgColor
        outline.lineWidth = 2.2
        outline.lineJoin = .round
        layer.addSublayer(outline)
        handles.fillColor = UIColor.white.cgColor
        handles.lineWidth = 2.2
        layer.addSublayer(handles)

        loupe.frame = CGRect(x: 0, y: 0, width: loupeR * 2, height: loupeR * 2)
        loupe.layer.cornerRadius = loupeR
        loupe.clipsToBounds = true
        loupe.backgroundColor = .black
        loupe.layer.borderColor = UIColor.white.cgColor
        loupe.layer.borderWidth = 3
        loupe.isHidden = true
        loupe.isUserInteractionEnabled = false
        loupeContent.layer.anchorPoint = .zero
        loupeImage.contentMode = .scaleToFill
        loupeContent.addSubview(loupeImage)
        loupeLine.fillColor = UIColor.clear.cgColor
        loupeLine.lineWidth = 2.2 / zoom
        loupeContent.layer.addSublayer(loupeLine)
        loupe.addSubview(loupeContent)
        let cross = UIBezierPath()
        cross.move(to: CGPoint(x: loupeR - 9, y: loupeR))
        cross.addLine(to: CGPoint(x: loupeR + 9, y: loupeR))
        cross.move(to: CGPoint(x: loupeR, y: loupeR - 9))
        cross.addLine(to: CGPoint(x: loupeR, y: loupeR + 9))
        loupeCross.path = cross.cgPath
        loupeCross.strokeColor = Theme.accent.cgColor
        loupeCross.lineWidth = 1.5
        loupe.layer.addSublayer(loupeCross)
        addSubview(loupe)
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    func setImage(_ img: UIImage, fullSize: CGSize) {
        imageView.image = img
        loupeImage.image = img
        imgW = max(1, fullSize.width)
        imgH = max(1, fullSize.height)
        if quad.isEmpty { quad = Geom.fullQuad(imgW, imgH) }
        setNeedsLayout()
    }

    var isValidQuad: Bool {
        guard quad.count == 4, Geom.isConvex(quad) else { return false }
        return Geom.area(quad) > 0.01 * Double(imgW * imgH)
    }

    func setQuad(_ q: [CGPoint], animated: Bool) {
        guard q.count == 4 else { return }
        let finite = q.map { CGPoint(x: $0.x.isFinite ? $0.x : 0, y: $0.y.isFinite ? $0.y : 0) }
        let target = Geom.clamp(finite, imgW, imgH)
        finishAnimation()
        if !animated || quad.count != 4 {
            quad = target
            redraw()
            onChange?(false)
            return
        }
        animFrom = quad
        animTo = target
        animStart = CACurrentMediaTime()
        let l = CADisplayLink(target: self, selector: #selector(step))
        l.add(to: .main, forMode: .common)
        link = l
        onChange?(false)
    }

    private func stopAnim() {
        link?.invalidate()
        link = nil
    }

    /// Kết thúc ngay hoạt ảnh chuyển khung: nhảy tới khung đích để không ai đọc phải khung trung gian.
    func finishAnimation() {
        guard link != nil else { return }
        stopAnim()
        if animTo.count == 4 {
            quad = animTo
            redraw()
        }
    }

    @objc private func step() {
        let t = min(1, (CACurrentMediaTime() - animStart) / 0.24)
        let e = CGFloat(1 - pow(1 - t, 2))
        quad = (0..<4).map { i in
            CGPoint(x: animFrom[i].x + (animTo[i].x - animFrom[i].x) * e,
                    y: animFrom[i].y + (animTo[i].y - animFrom[i].y) * e)
        }
        redraw()
        if t >= 1 { stopAnim() }
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        let pad: CGFloat = 22
        let aw = max(1, bounds.width - 2 * pad), ah = max(1, bounds.height - 2 * pad)
        scale = min(aw / imgW, ah / imgH)
        off = CGPoint(x: (bounds.width - imgW * scale) / 2, y: (bounds.height - imgH * scale) / 2)
        let r = CGRect(x: off.x, y: off.y, width: imgW * scale, height: imgH * scale)
        imageView.frame = r
        loupeContent.bounds = bounds
        loupeImage.frame = r
        redraw()
    }

    private func v(_ p: CGPoint) -> CGPoint {
        return CGPoint(x: off.x + p.x * scale, y: off.y + p.y * scale)
    }

    private func quadPath() -> UIBezierPath {
        let path = UIBezierPath()
        guard quad.count == 4 else { return path }
        path.move(to: v(quad[0]))
        for i in 1..<4 { path.addLine(to: v(quad[i])) }
        path.close()
        return path
    }

    private func redraw() {
        guard quad.count == 4 else { return }
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        let valid = Geom.isConvex(quad)
        let color = (valid ? Theme.accent : Theme.red).cgColor
        let path = quadPath()
        let sh = UIBezierPath(rect: imageView.frame)
        sh.append(path)
        shade.path = sh.cgPath
        outline.path = path.cgPath
        outline.strokeColor = color
        let hp = UIBezierPath()
        let hl: CGFloat = 11, hw: CGFloat = 3.6
        for i in 0..<4 {
            let a = v(quad[i]), b = v(quad[(i + 1) % 4])
            let m = CGPoint(x: (a.x + b.x) / 2, y: (a.y + b.y) / 2)
            let ang = atan2(b.y - a.y, b.x - a.x)
            let pill = UIBezierPath(roundedRect: CGRect(x: -hl, y: -hw, width: 2 * hl, height: 2 * hw), cornerRadius: hw)
            pill.apply(CGAffineTransform(rotationAngle: ang))
            pill.apply(CGAffineTransform(translationX: m.x, y: m.y))
            hp.append(pill)
        }
        let r: CGFloat = 8.5
        for i in 0..<4 {
            let c = v(quad[i])
            hp.append(UIBezierPath(ovalIn: CGRect(x: c.x - r, y: c.y - r, width: 2 * r, height: 2 * r)))
        }
        handles.path = hp.cgPath
        handles.strokeColor = color
        if dragCorner >= 0 || dragEdge >= 0 {
            updateLoupe(path: path, color: color)
        } else {
            loupe.isHidden = true
        }
        CATransaction.commit()
    }

    private func updateLoupe(path: UIBezierPath, color: CGColor) {
        let f: CGPoint
        if dragCorner >= 0 {
            f = v(quad[dragCorner])
        } else {
            let a = v(quad[dragEdge]), b = v(quad[(dragEdge + 1) % 4])
            f = CGPoint(x: (a.x + b.x) / 2, y: (a.y + b.y) / 2)
        }
        let m: CGFloat = 14
        var lx = loupeR + m
        let ly = loupeR + m
        if f.x < bounds.width / 2 && f.y < 2.5 * loupeR + m { lx = bounds.width - loupeR - m }
        loupe.center = CGPoint(x: lx, y: ly)
        loupeContent.layer.position = CGPoint(x: loupeR - f.x * zoom, y: loupeR - f.y * zoom)
        loupeContent.transform = CGAffineTransform(scaleX: zoom, y: zoom)
        loupeLine.path = path.cgPath
        loupeLine.strokeColor = color
        loupe.isHidden = false
    }

    // MARK: chạm

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard let t = touches.first, quad.count == 4 else { return }
        finishAnimation()
        let p = t.location(in: self)
        dragCorner = -1
        dragEdge = -1
        var best: CGFloat = 40
        for i in 0..<4 {
            let c = v(quad[i])
            let d = hypot(c.x - p.x, c.y - p.y)
            if d < best { best = d; dragCorner = i }
        }
        if dragCorner < 0 {
            best = 34
            for i in 0..<4 {
                let a = v(quad[i]), b = v(quad[(i + 1) % 4])
                let d = hypot((a.x + b.x) / 2 - p.x, (a.y + b.y) / 2 - p.y)
                if d < best { best = d; dragEdge = i }
            }
        }
        guard dragCorner >= 0 || dragEdge >= 0 else { return }
        downPt = p
        quadStart = quad
        if dragCorner >= 0 {
            let c = v(quad[dragCorner])
            grab = CGPoint(x: c.x - p.x, y: c.y - p.y)
        }
        redraw()
    }

    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard let t = touches.first, dragCorner >= 0 || dragEdge >= 0 else { return }
        let p = t.location(in: self)
        if dragCorner >= 0 {
            let ix = (p.x + grab.x - off.x) / scale, iy = (p.y + grab.y - off.y) / scale
            quad[dragCorner] = CGPoint(x: min(max(ix, 0), imgW), y: min(max(iy, 0), imgH))
        } else {
            moveEdge(dragEdge, (p.x - downPt.x) / scale, (p.y - downPt.y) / scale)
        }
        redraw()
        onChange?(true)
    }

    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
        endDrag()
    }

    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) {
        endDrag()
    }

    /// Mô phỏng kéo góc (toạ độ ảnh) cho tự kiểm thử; giữ trạng thái đang kéo để thấy kính lúp.
    func testDrag(corner i: Int, by d: CGPoint) {
        guard quad.count == 4, i >= 0, i < 4 else { return }
        finishAnimation()
        dragCorner = i
        dragEdge = -1
        quad[i] = CGPoint(x: min(max(quad[i].x + d.x, 0), imgW), y: min(max(quad[i].y + d.y, 0), imgH))
        redraw()
        onChange?(true)
    }

    func testEndDrag() { endDrag() }

    private func endDrag() {
        dragCorner = -1
        dragEdge = -1
        redraw()
    }

    /// Dịch cạnh song song theo pháp tuyến; hai đầu trượt dọc theo hai cạnh kề.
    private func moveEdge(_ i: Int, _ dx: CGFloat, _ dy: CGFloat) {
        let a = i, b = (i + 1) % 4
        let pa = quadStart[a], pb = quadStart[b]
        let len = hypot(pb.x - pa.x, pb.y - pa.y)
        if len < 1e-3 { return }
        let nx = -(pb.y - pa.y) / len, ny = (pb.x - pa.x) / len
        let delta = dx * nx + dy * ny
        slide(a, (a + 3) % 4, nx, ny, delta)
        slide(b, (b + 1) % 4, nx, ny, delta)
    }

    private func slide(_ c: Int, _ nb: Int, _ nx: CGFloat, _ ny: CGFloat, _ delta: CGFloat) {
        let pc = quadStart[c], pn = quadStart[nb]
        var ux = pc.x - pn.x, uy = pc.y - pn.y
        let l = hypot(ux, uy)
        var tx: CGFloat, ty: CGFloat
        if l > 1e-3 {
            ux /= l
            uy /= l
            let dot = ux * nx + uy * ny
            if abs(dot) > 0.25 {
                tx = pc.x + ux * (delta / dot)
                ty = pc.y + uy * (delta / dot)
            } else {
                tx = pc.x + nx * delta
                ty = pc.y + ny * delta
            }
        } else {
            tx = pc.x + nx * delta
            ty = pc.y + ny * delta
        }
        quad[c] = CGPoint(x: min(max(tx, 0), imgW), y: min(max(ty, 0), imgH))
    }
}
