import Foundation
#if canImport(CoreGraphics)
import CoreGraphics
#endif

/// Hình học tứ giác trang giấy (port từ Geom.java). Thứ tự góc: TL, TR, BR, BL; gốc toạ độ trên-trái.
/// Chỉ dùng Foundation/CoreGraphics nên biên dịch được trên Linux để đối chiếu với bản Java.
enum Geom {
    static let a4Ratio = 210.0 / 297.0
    static let letterRatio = 8.5 / 11.0

    static func fullQuad(_ w: CGFloat, _ h: CGFloat) -> [CGPoint] {
        return [CGPoint(x: 0, y: 0), CGPoint(x: w, y: 0), CGPoint(x: w, y: h), CGPoint(x: 0, y: h)]
    }

    static func scale(_ q: [CGPoint], _ sx: CGFloat, _ sy: CGFloat) -> [CGPoint] {
        return q.map { CGPoint(x: $0.x * sx, y: $0.y * sy) }
    }

    static func clamp(_ q: [CGPoint], _ w: CGFloat, _ h: CGFloat) -> [CGPoint] {
        return q.map { CGPoint(x: min(max($0.x, 0), w), y: min(max($0.y, 0), h)) }
    }

    /// Sắp 4 điểm bất kỳ thành TL, TR, BR, BL (chiều kim đồng hồ trên màn hình).
    static func order(_ p: [CGPoint]) -> [CGPoint] {
        guard p.count == 4 else { return p }
        let cx = p.map { $0.x }.reduce(0, +) / 4
        let cy = p.map { $0.y }.reduce(0, +) / 4
        let sorted = p.sorted { atan2(Double($0.y - cy), Double($0.x - cx)) < atan2(Double($1.y - cy), Double($1.x - cx)) }
        var start = 0
        var best = CGFloat.greatestFiniteMagnitude
        for (i, pt) in sorted.enumerated() where pt.x + pt.y < best {
            best = pt.x + pt.y
            start = i
        }
        return (0..<4).map { sorted[(start + $0) % 4] }
    }

    static func signedArea(_ q: [CGPoint]) -> Double {
        guard q.count == 4 else { return 0 }
        var a = 0.0
        for i in 0..<4 {
            let j = (i + 1) % 4
            a += Double(q[i].x * q[j].y - q[j].x * q[i].y)
        }
        return a / 2
    }

    static func area(_ q: [CGPoint]) -> Double { abs(signedArea(q)) }

    static func isConvex(_ q: [CGPoint]) -> Bool {
        guard q.count == 4 else { return false }
        for p in q where !p.x.isFinite || !p.y.isFinite { return false }
        var sign = 0
        for i in 0..<4 {
            let a = q[i], b = q[(i + 1) % 4], c = q[(i + 2) % 4]
            let cross = Double((b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x))
            if !(abs(cross) >= 1e-6) { return false }
            let s = cross > 0 ? 1 : -1
            if sign == 0 { sign = s } else if s != sign { return false }
        }
        return true
    }

    static func dist(_ a: CGPoint, _ b: CGPoint) -> Double {
        return Double(hypot(a.x - b.x, a.y - b.y))
    }

    private static func cross(_ a: [Double], _ b: [Double]) -> [Double] {
        return [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]]
    }

    private static func dot(_ a: [Double], _ b: [Double]) -> Double {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
    }

    /// Tỷ lệ rộng/cao thật của trang (Zhang & He, "Whiteboard scanning and image enhancement").
    static func estimateAspect(_ q: [CGPoint], imgW: Double, imgH: Double, focal: Double) -> Double {
        guard q.count == 4 else { return 1 }
        let wTop = dist(q[0], q[1]), wBot = dist(q[3], q[2])
        let hL = dist(q[0], q[3]), hR = dist(q[1], q[2])
        let affine = (wTop + wBot) / max(1e-6, hL + hR)
        let u0 = imgW / 2, v0 = imgH / 2
        let m1 = [Double(q[0].x), Double(q[0].y), 1.0]
        let m2 = [Double(q[1].x), Double(q[1].y), 1.0]
        let m3 = [Double(q[3].x), Double(q[3].y), 1.0]
        let m4 = [Double(q[2].x), Double(q[2].y), 1.0]
        let k2 = dot(cross(m1, m4), m3) / dot(cross(m2, m4), m3)
        let k3 = dot(cross(m1, m4), m2) / dot(cross(m3, m4), m2)
        if !k2.isFinite || !k3.isFinite { return affine }
        let n2 = [k2 * m2[0] - m1[0], k2 * m2[1] - m1[1], k2 * m2[2] - m1[2]]
        let n3 = [k3 * m3[0] - m1[0], k3 * m3[1] - m1[1], k3 * m3[2] - m1[2]]
        if abs(n2[2]) < 1e-3 && abs(n3[2]) < 1e-3 { return affine }
        let scaleRef = max(imgW, imgH)
        var f = focal
        var fEst = -1.0
        if abs(n2[2] * n3[2]) > 1e-12 {
            let num = (n2[0] * n3[0] - (n2[0] * n3[2] + n2[2] * n3[0]) * u0 + n2[2] * n3[2] * u0 * u0)
                + (n2[1] * n3[1] - (n2[1] * n3[2] + n2[2] * n3[1]) * v0 + n2[2] * n3[2] * v0 * v0)
            let f2 = -num / (n2[2] * n3[2])
            if f2 > 0 { fEst = sqrt(f2) }
        }
        if f <= 0 {
            f = (fEst > 0.45 * scaleRef && fEst < 3.0 * scaleRef) ? fEst : 0.78 * scaleRef
        }
        func quadForm(_ n: [Double]) -> Double {
            let a = (n[0] - u0 * n[2]) / f
            let b = (n[1] - v0 * n[2]) / f
            let c = n[2]
            return a * a + b * b + c * c
        }
        let r2 = quadForm(n2), r3 = quadForm(n3)
        if !(r2 > 0) || !(r3 > 0) { return affine }
        let ratio = sqrt(r2 / r3)
        if !ratio.isFinite || ratio <= 0.05 || ratio > 20 { return affine }
        if ratio < affine / 1.6 || ratio > affine * 1.6 { return affine }
        return ratio
    }

    static func snapPaper(_ ratio: Double) -> Double {
        let std = [a4Ratio, 1.0 / a4Ratio, letterRatio, 1.0 / letterRatio]
        let tol = [0.045, 0.045, 0.02, 0.02]
        for i in 0..<std.count where abs(ratio - std[i]) / std[i] < tol[i] {
            return std[i]
        }
        return ratio
    }

    static func outputSize(_ q: [CGPoint], imgW: Double, imgH: Double, focal: Double, snap: Bool, maxLong: Double) -> CGSize {
        guard q.count == 4 else { return CGSize(width: 16, height: 16) }
        var ratio = estimateAspect(q, imgW: imgW, imgH: imgH, focal: focal)
        if snap { ratio = snapPaper(ratio) }
        let wq = max(dist(q[0], q[1]), dist(q[3], q[2]))
        let hq = max(dist(q[0], q[3]), dist(q[1], q[2]))
        // tứ giác suy biến / toạ độ không hữu hạn -> kích thước tối thiểu (không để tràn số)
        if !(ratio > 0) || !ratio.isFinite || !(wq > 0) || !(hq > 0) || !wq.isFinite || !hq.isFinite {
            return CGSize(width: 16, height: 16)
        }
        var outW: Double, outH: Double
        if wq / hq > ratio {
            outW = wq
            outH = wq / ratio
        } else {
            outH = hq
            outW = hq * ratio
        }
        let longSide = max(outW, outH)
        if longSide > maxLong {
            let k = maxLong / longSide
            outW *= k
            outH *= k
        }
        return CGSize(width: max(16, outW.rounded()), height: max(16, outH.rounded()))
    }
}
