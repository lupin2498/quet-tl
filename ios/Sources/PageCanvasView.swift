import UIKit

/// Khung hiển thị một trang cho các màn hình chỉnh sửa (Xóa thông minh, Đánh dấu):
/// ảnh nằm vừa khung, xoay theo góc xoay của trang; một ngón để vẽ / chọn, hai ngón để phóng to và kéo.
/// Lớp con làm việc trong hệ toạ độ ẢNH CHƯA XOAY (pixel của ảnh xem trước).
class PageCanvasView: UIView {
    private(set) var image: UIImage?
    private(set) var rot = 0
    /// Ảnh -> màn hình.
    private(set) var toView = CGAffineTransform.identity
    /// Số điểm màn hình ứng với một pixel ảnh.
    private(set) var scale: CGFloat = 1
    private var zoom: CGFloat = 1
    private var pan = CGPoint.zero
    private var active = [UITouch]()
    private var tracking = false
    private var zooming = false
    private var lastDist: CGFloat = 0
    private var lastMid = CGPoint.zero

    override init(frame: CGRect) {
        super.init(frame: frame)
        isMultipleTouchEnabled = true
        backgroundColor = Theme.bg
        contentMode = .redraw
        isOpaque = true
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    func setImage(_ img: UIImage?, rotation: Int) {
        image = img
        rot = ((rotation % 360) + 360) % 360
        updateTransform()
        setNeedsDisplay()
    }

    /// Thay ảnh bằng ảnh cùng kích thước (kết quả xử lý mới), giữ nguyên mức phóng.
    func swapImage(_ img: UIImage?) {
        image = img
        updateTransform()
        setNeedsDisplay()
    }

    var hasImage: Bool { image != nil }
    var imageW: CGFloat { max(1, image?.size.width ?? 1) }
    var imageH: CGFloat { max(1, image?.size.height ?? 1) }
    /// Bề ngang trang như đang hiển thị (đã tính góc xoay), theo pixel ảnh.
    var shownW: CGFloat { rot % 180 == 0 ? imageW : imageH }

    private func clamp(_ v: CGFloat, _ lo: CGFloat, _ hi: CGFloat) -> CGFloat {
        return v < lo ? lo : (v > hi ? hi : v)
    }

    func updateTransform() {
        guard image != nil, bounds.width > 0, bounds.height > 0 else { return }
        let iw = imageW, ih = imageH
        let swap = rot % 180 != 0
        let rw = swap ? ih : iw, rh = swap ? iw : ih
        let pad: CGFloat = 12
        var fit = min((bounds.width - 2 * pad) / rw, (bounds.height - 2 * pad) / rh)
        if !(fit > 0) { fit = 0.01 }
        let s = fit * zoom
        let maxX = max(0, (rw * s - bounds.width) / 2 + pad), maxY = max(0, (rh * s - bounds.height) / 2 + pad)
        pan.x = clamp(pan.x, -maxX, maxX)
        pan.y = clamp(pan.y, -maxY, maxY)
        var t = CGAffineTransform(translationX: -iw / 2, y: -ih / 2)
        t = t.concatenating(CGAffineTransform(rotationAngle: CGFloat(rot) * CGFloat.pi / 180))
        t = t.concatenating(CGAffineTransform(scaleX: s, y: s))
        t = t.concatenating(CGAffineTransform(translationX: bounds.width / 2 + pan.x, y: bounds.height / 2 + pan.y))
        toView = t
        scale = s
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        updateTransform()
        setNeedsDisplay()
    }

    override func draw(_ rect: CGRect) {
        guard let ctx = UIGraphicsGetCurrentContext(), let img = image else { return }
        ctx.saveGState()
        ctx.concatenate(toView)
        ctx.interpolationQuality = .medium
        img.draw(in: CGRect(x: 0, y: 0, width: imageW, height: imageH))
        drawPage(ctx)
        ctx.restoreGState()
        drawOverlay(ctx)
    }

    /// Vẽ thêm lên trang, trong hệ toạ độ ảnh.
    func drawPage(_ ctx: CGContext) {}

    /// Vẽ thêm trong hệ toạ độ màn hình (khung chọn, con trỏ bút...).
    func drawOverlay(_ ctx: CGContext) {}

    /// Một ngón chạm xuống tại điểm ảnh ip / điểm màn hình sp.
    func onDown(_ ip: CGPoint, _ sp: CGPoint) {}

    func onMove(_ ip: CGPoint, _ sp: CGPoint) {}

    /// cancelled: thao tác bị huỷ (đặt thêm ngón thứ hai để phóng to, hoặc hệ thống huỷ).
    func onUp(_ ip: CGPoint, cancelled: Bool) {}

    /// Điểm màn hình -> điểm ảnh.
    func toImage(_ sp: CGPoint) -> CGPoint {
        return sp.applying(toView.inverted())
    }

    /// Điểm ảnh -> điểm màn hình.
    func toScreen(_ ip: CGPoint) -> CGPoint {
        return ip.applying(toView)
    }

    // MARK: chạm

    private func dist() -> CGFloat {
        guard active.count >= 2 else { return 0 }
        let a = active[0].location(in: self), b = active[1].location(in: self)
        return hypot(a.x - b.x, a.y - b.y)
    }

    private func mid() -> CGPoint {
        guard active.count >= 2 else { return .zero }
        let a = active[0].location(in: self), b = active[1].location(in: self)
        return CGPoint(x: (a.x + b.x) / 2, y: (a.y + b.y) / 2)
    }

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard image != nil else { return }
        for t in touches where !active.contains(t) { active.append(t) }
        if active.count == 1 && !zooming {
            tracking = true
            let sp = active[0].location(in: self)
            onDown(toImage(sp), sp)
        } else if active.count >= 2 {
            if tracking {
                tracking = false
                onUp(toImage(active[0].location(in: self)), cancelled: true)
            }
            zooming = true
            lastDist = dist()
            lastMid = mid()
        }
    }

    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard image != nil else { return }
        if zooming && active.count >= 2 {
            let d = dist(), m = mid()
            if lastDist > 10 && d > 10 {
                let nz = clamp(zoom * d / lastDist, 1, 6)
                let f = nz / zoom
                // giữ điểm giữa hai ngón đứng yên khi phóng
                let fx = m.x - bounds.width / 2, fy = m.y - bounds.height / 2
                pan.x = (pan.x - fx) * f + fx
                pan.y = (pan.y - fy) * f + fy
                zoom = nz
            }
            pan.x += m.x - lastMid.x
            pan.y += m.y - lastMid.y
            lastDist = d
            lastMid = m
            updateTransform()
            setNeedsDisplay()
        } else if tracking, let t = active.first {
            let sp = t.location(in: self)
            onMove(toImage(sp), sp)
        }
    }

    private func finishTouches(_ touches: Set<UITouch>, cancelled: Bool) {
        var last = CGPoint.zero
        if let t = active.first { last = t.location(in: self) }
        active.removeAll { touches.contains($0) }
        if tracking && active.isEmpty {
            tracking = false
            onUp(toImage(last), cancelled: cancelled)
        }
        if active.count >= 2 {
            lastDist = dist()
            lastMid = mid()
        } else {
            lastDist = 0
        }
        // còn một ngón sau khi phóng: không coi là nét vẽ mới cho tới khi nhấc hết tay
        if active.isEmpty { zooming = false }
    }

    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
        finishTouches(touches, cancelled: false)
    }

    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) {
        finishTouches(touches, cancelled: true)
    }

    // MARK: tự kiểm thử

    /// Giả lập một nét kéo qua các điểm chuẩn hoá (0..1 theo ảnh chưa xoay).
    func testStroke(_ pts: [CGPoint]) {
        guard image != nil, let first = pts.first else { return }
        func ip(_ p: CGPoint) -> CGPoint { return CGPoint(x: p.x * imageW, y: p.y * imageH) }
        onDown(ip(first), toScreen(ip(first)))
        // chia nhỏ từng đoạn để giống thao tác kéo thật
        for k in 1..<max(1, pts.count) {
            let a = ip(pts[k - 1]), b = ip(pts[k])
            for j in 1...8 {
                let t = CGFloat(j) / 8
                let p = CGPoint(x: a.x + (b.x - a.x) * t, y: a.y + (b.y - a.y) * t)
                onMove(p, toScreen(p))
            }
        }
        onUp(ip(pts[pts.count - 1]), cancelled: false)
    }
}
