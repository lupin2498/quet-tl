import Foundation

/// Tự động tìm vùng trang giấy (4 góc) — port từ DocDetector.java (bản Android), chỉ dùng Foundation.
///
/// Thu nhỏ ảnh -> làm mờ -> gradient màu (Sobel) -> NMS -> Hough có định hướng -> ghép 4 đường thành tứ giác
/// -> chấm điểm (độ phủ cạnh thích nghi, tương phản, diện tích, cạnh "dừng" tại góc) -> tinh chỉnh dưới-pixel.
enum DocDetector {
    struct Result {
        /// TL,TR,BR,BL (x,y) theo toạ độ ảnh đầu vào; nil nếu không tìm thấy.
        var quad: [Float]?
        var score: Float = 0
        var refined = false
        /// Số cạnh là mép ảnh (không thấy mép giấy thật).
        var borders = 0
    }

    static let workMax = 420
    static let maxLinesPerDir = 20
    static let minScore = 0.10
    static let borderQ = 0.22

    /// Double -> Int an toàn (NaN/vô cực không làm ứng dụng dừng như Int(_:) của Swift).
    @inline(__always) static func toInt(_ v: Double) -> Int {
        if !v.isFinite { return v > 0 ? 1_000_000_000 : (v < 0 ? -1_000_000_000 : 0) }
        return Int(max(-1e9, min(1e9, v)))
    }

    /// Làm tròn như Math.round của Java (floor(v + 0.5)) để kết quả trùng bản Android.
    @inline(__always) static func jround(_ v: Double) -> Int {
        return toInt(floor(v + 0.5))
    }

    final class Line {
        var nx = 0.0, ny = 0.0, rho = 0.0
        var dx = 0.0, dy = 0.0
        var ta = 0.0
        var len = 0
        var sup = [Int32]()
        var con = [Float]()
        var acon = [Float]()
        var border = false
        var votes = 0
        var priority = 0.0
        var thetaDeg = 0.0
        var key = 0.0

        func setNormal(_ nx0: Double, _ ny0: Double, _ rho0: Double) {
            let l = max(1e-12, hypot(nx0, ny0))
            var a = nx0 / l, b = ny0 / l, r = rho0 / l
            if b < -1e-12 || (abs(b) <= 1e-12 && a < 0) {
                a = -a
                b = -b
                r = -r
            }
            nx = a
            ny = b
            rho = r
            dx = -ny
            dy = nx
            var th = atan2(ny, nx) * 180 / Double.pi
            if th < 0 { th += 180 }
            if th >= 180 { th -= 180 }
            thetaDeg = th
        }

        var isHorizontal: Bool { thetaDeg >= 45 && thetaDeg < 135 }

        func paramOf(_ x: Double, _ y: Double) -> Double { x * dx + y * dy }

        /// Tỷ lệ mẫu nổi bật trong [t0,t1]; phần ngoài ảnh tính là không có.
        func support(_ t0: Double, _ t1: Double) -> Double {
            let s0 = t0 - ta, s1 = t1 - ta
            let expected = max(1, s1 - s0)
            var i0 = DocDetector.toInt(ceil(s0)), i1 = DocDetector.toInt(floor(s1))
            if i0 < 0 { i0 = 0 }
            if i1 > len - 1 { i1 = len - 1 }
            if i1 < i0 { return 0 }
            return min(1.0, Double(sup[i1 + 1] - sup[i0]) / expected)
        }

        /// Như support() nhưng chỉ tính phần trong ảnh; -1 nếu quá ít mẫu.
        func supportInside(_ t0: Double, _ t1: Double) -> Double {
            let s0 = t0 - ta, s1 = t1 - ta
            var i0 = DocDetector.toInt(ceil(s0)), i1 = DocDetector.toInt(floor(s1))
            if i0 < 0 { i0 = 0 }
            if i1 > len - 1 { i1 = len - 1 }
            if i1 - i0 + 1 < 3 { return -1 }
            return Double(sup[i1 + 1] - sup[i0]) / Double(i1 - i0 + 1)
        }

        func contrast(_ t0: Double, _ t1: Double) -> (Double, Double) {
            var i0 = DocDetector.toInt(ceil(t0 - ta)), i1 = DocDetector.toInt(floor(t1 - ta))
            if i0 < 0 { i0 = 0 }
            if i1 > len - 1 { i1 = len - 1 }
            if i1 < i0 { return (0, 0) }
            let c = Double(i1 - i0 + 1)
            return (Double(con[i1 + 1] - con[i0]) / c, Double(acon[i1 + 1] - acon[i0]) / c)
        }
    }

    /// Ảnh vào: RGBA 8 bit (kênh R,G,B ở byte 0,1,2 của mỗi điểm ảnh).
    static func detect(rgba: UnsafePointer<UInt8>, width w: Int, height h: Int, bytesPerRow bpr: Int) -> Result? {
        guard w >= 16, h >= 16 else { return nil }
        let scale = min(1.0, Double(workMax) / Double(max(w, h)))
        let sw = max(16, Int((Double(w) * scale).rounded()))
        let sh = max(16, Int((Double(h) * scale).rounded()))
        let kx = Float(sw) / Float(w), ky = Float(sh) / Float(h)
        let n = sw * sh

        var cr = [Float](repeating: 0, count: n)
        var cg = [Float](repeating: 0, count: n)
        var cb = [Float](repeating: 0, count: n)
        downscale(rgba, w, h, bpr, &cr, &cg, &cb, sw, sh)
        var tmp = [Float](repeating: 0, count: n)
        blur5(&cr, &tmp, sw, sh)
        blur5(&cg, &tmp, sw, sh)
        blur5(&cb, &tmp, sw, sh)
        var gray = [Float](repeating: 0, count: n)
        for i in 0..<n { gray[i] = 0.299 * cr[i] + 0.587 * cg[i] + 0.114 * cb[i] }

        var gx = [Float](repeating: 0, count: n)
        var gy = [Float](repeating: 0, count: n)
        var mag = [Float](repeating: 0, count: n)
        colorSobel(cr, cg, cb, sw, sh, &gx, &gy, &mag)
        let em = nms(gx, gy, mag, sw, sh)

        let lines = hough(em, gx, gy, sw, sh, 2.0)
        var hs = [Line](), vs = [Line]()
        let cx = Double(sw - 1) / 2, cy = Double(sh - 1) / 2
        for L in lines {
            refineLine(L, em, gx, gy, mag, sw, sh, 2.5)
            if !buildProfile(L, gx, gy, mag, gray, sw, sh) { continue }
            // ưu tiên đường có tương phản (mép giấy) hơn dòng chữ/đường kẻ trong trang
            let acon = Double(L.acon[L.len]) / Double(max(1, L.len))
            L.priority = Double(L.votes) * (0.25 + 0.75 * min(1.0, acon / 15.0))
            if L.isHorizontal {
                L.key = (L.rho - cx * L.nx) / L.ny
                hs.append(L)
            } else {
                L.key = (L.rho - cy * L.ny) / L.nx
                vs.append(L)
            }
        }
        keepTop(&hs, maxLinesPerDir)
        keepTop(&vs, maxLinesPerDir)
        hs.append(borderLine(0, 1, 0, cx, cy))
        hs.append(borderLine(0, 1, Double(sh - 1), cx, cy))
        vs.append(borderLine(1, 0, 0, cx, cy))
        vs.append(borderLine(1, 0, Double(sw - 1), cx, cy))
        sortByKey(&hs)
        sortByKey(&vs)

        var bestQ: [Float]?
        var bestS = -1.0
        var bestB = 0
        let imgArea = Double(sw) * Double(sh)
        let tolX = 0.03 * Float(sw), tolY = 0.03 * Float(sh)
        let maxX = Float(sw - 1) + tolX, maxY = Float(sh - 1) + tolY
        var q = [Float](repeating: 0, count: 8)
        for i in 0..<hs.count {
            for j in (i + 1)..<hs.count {
                let top = hs[i], bot = hs[j]
                if bot.key - top.key < 0.18 * Double(sh) { continue }
                if abs(top.thetaDeg - bot.thetaDeg) > 40 { continue }
                for a in 0..<vs.count {
                    for b in (a + 1)..<vs.count {
                        let left = vs[a], right = vs[b]
                        if right.key - left.key < 0.18 * Double(sw) { continue }
                        var dth = abs(left.thetaDeg - right.thetaDeg)
                        dth = min(dth, 180 - dth)
                        if dth > 40 { continue }
                        let borders = (top.border ? 1 : 0) + (bot.border ? 1 : 0) + (left.border ? 1 : 0) + (right.border ? 1 : 0)
                        if borders > 3 { continue }
                        if !intersect(top, left, &q, 0) { continue }
                        if !intersect(top, right, &q, 2) { continue }
                        if !intersect(bot, right, &q, 4) { continue }
                        if !intersect(bot, left, &q, 6) { continue }
                        var inside = true
                        for k in 0..<4 {
                            let x = q[2 * k], y = q[2 * k + 1]
                            if !(x >= -tolX && x <= maxX && y >= -tolY && y <= maxY) {
                                inside = false
                                break
                            }
                        }
                        if !inside || !isConvex(q) { continue }
                        let area = quadArea(q)
                        if area < 0.10 * imgArea { continue }
                        let ar = angleRange(q)
                        if ar.0 < 40 || ar.1 > 140 { continue }
                        let sc = score(q, top, right, bot, left, area / imgArea) * pow(0.97, Double(borders))
                        if sc > bestS {
                            bestS = sc
                            bestQ = q
                            bestB = borders
                        }
                    }
                }
            }
        }
        guard let best = bestQ, bestS >= minScore else {
            return Result(quad: nil, score: Float(bestS), refined: false, borders: bestB)
        }
        // về toạ độ ảnh đầu vào
        var q0 = [Float](repeating: 0, count: 8)
        for k in 0..<4 {
            q0[2 * k] = (best[2 * k] + 0.5) / kx - 0.5
            q0[2 * k + 1] = (best[2 * k + 1] + 0.5) / ky - 0.5
        }
        var r = Result()
        if let ref = refine(rgba, w, h, bpr, q0, 2.5 / min(kx, ky)) {
            r.quad = ref
            r.refined = true
        } else {
            r.quad = q0
        }
        if var out = r.quad {
            for k in 0..<4 {
                out[2 * k] = max(0, min(Float(w - 1), out[2 * k]))
                out[2 * k + 1] = max(0, min(Float(h - 1), out[2 * k + 1]))
            }
            r.quad = out
        }
        r.score = Float(bestS)
        r.borders = bestB
        return r
    }

    // MARK: chấm điểm

    private static func score(_ q: [Float], _ s0: Line, _ s1: Line, _ s2: Line, _ s3: Line, _ areaFrac: Double) -> Double {
        // cạnh: trên (0->1), phải (1->2), dưới (2->3), trái (3->0)
        let sides = [s0, s1, s2, s3]
        let qx = Double(q[0] + q[2] + q[4] + q[6]) / 4, qy = Double(q[1] + q[3] + q[5] + q[7]) / 4
        var sumQ = 0.0, sumL = 0.0, minQ = 1e9
        var lens = [Double](repeating: 0, count: 4)
        for s in 0..<4 {
            let a = s, b = (s + 1) % 4
            let ax = Double(q[2 * a]), ay = Double(q[2 * a + 1]), bx = Double(q[2 * b]), by = Double(q[2 * b + 1])
            let len = hypot(bx - ax, by - ay)
            lens[s] = len
            let L = sides[s]
            let qs: Double
            if L.border {
                qs = borderQ
            } else {
                let t1 = L.paramOf(ax, ay), t2 = L.paramOf(bx, by)
                let tmin = min(t1, t2), tmax = max(t1, t2)
                let trim = 0.06 * (tmax - tmin)
                let sup = L.support(tmin + trim, tmax - trim)
                let c = L.contrast(tmin + trim, tmax - trim)
                var con = c.0
                let acon = c.1
                // đổi dấu: con > 0 nghĩa là phía trong tứ giác sáng hơn phía ngoài
                if L.nx * qx + L.ny * qy - L.rho < 0 { con = -con }
                // đường kẻ/dòng chữ trong trang có hai phía giống nhau -> tương phản thấp -> điểm thấp
                var wcon = 0.08 + 0.92 * pow(min(1.0, acon / 20.0), 1.2)
                if con < -10 { wcon *= 0.85 }
                qs = sup * wcon
            }
            sumQ += qs * len
            sumL += len
            minQ = min(minQ, qs)
        }
        // góc thật: cạnh phải "dừng" tại góc
        var term = 0.0
        for c in 0..<4 {
            let sPrev = (c + 3) % 4, sNext = c
            let e1 = extensionSupport(sides[sPrev], q, sPrev, true, lens[sPrev])
            let e2 = extensionSupport(sides[sNext], q, sNext, false, lens[sNext])
            term += 1.0 - 0.35 * max(e1, e2)
        }
        term /= 4
        let mean = sumQ / max(1e-9, sumL)
        let minF = 0.4 + 0.6 * min(1.0, minQ / max(0.2, 0.55 * mean))
        return mean * minF * (0.5 + 0.5 * areaFrac.squareRoot()) * term
    }

    /// Độ phủ cạnh trên đoạn kéo dài của cạnh s ra ngoài góc (atEnd: qua điểm cuối; ngược lại: lùi qua điểm đầu).
    private static func extensionSupport(_ L: Line, _ q: [Float], _ s: Int, _ atEnd: Bool, _ len: Double) -> Double {
        if L.border { return 0 }
        let a = s, b = (s + 1) % 4
        let ta = L.paramOf(Double(q[2 * a]), Double(q[2 * a + 1]))
        let tb = L.paramOf(Double(q[2 * b]), Double(q[2 * b + 1]))
        let e = max(6, 0.12 * len)
        let from: Double, to: Double
        if atEnd {
            if tb >= ta {
                from = tb + 2
                to = tb + 2 + e
            } else {
                from = tb - 2 - e
                to = tb - 2
            }
        } else {
            if tb >= ta {
                from = ta - 2 - e
                to = ta - 2
            } else {
                from = ta + 2
                to = ta + 2 + e
            }
        }
        let v = L.supportInside(from, to)
        return v < 0 ? 0 : v
    }

    private static func intersect(_ a: Line, _ b: Line, _ out: inout [Float], _ off: Int) -> Bool {
        let det = a.nx * b.ny - a.ny * b.nx
        if abs(det) < 1e-9 { return false }
        let x = (a.rho * b.ny - b.rho * a.ny) / det
        let y = (a.nx * b.rho - b.nx * a.rho) / det
        if !x.isFinite || !y.isFinite { return false }
        out[off] = Float(x)
        out[off + 1] = Float(y)
        return true
    }

    private static func borderLine(_ nx: Double, _ ny: Double, _ rho: Double, _ cx: Double, _ cy: Double) -> Line {
        let L = Line()
        L.setNormal(nx, ny, rho)
        L.border = true
        L.key = L.isHorizontal ? (L.rho - cx * L.nx) / L.ny : (L.rho - cy * L.ny) / L.nx
        return L
    }

    /// Giữ `maxCount` đường ưu tiên cao nhất (sắp xếp ổn định như Java).
    private static func keepTop(_ l: inout [Line], _ maxCount: Int) {
        if l.count <= maxCount { return }
        let sorted = l.enumerated().sorted { a, b in
            a.element.priority != b.element.priority ? a.element.priority > b.element.priority : a.offset < b.offset
        }
        l = sorted.prefix(maxCount).map { $0.element }
    }

    private static func sortByKey(_ l: inout [Line]) {
        let sorted = l.enumerated().sorted { a, b in
            a.element.key != b.element.key ? a.element.key < b.element.key : a.offset < b.offset
        }
        l = sorted.map { $0.element }
    }

    // MARK: hình học tứ giác (mảng 8 số)

    static func isConvex(_ q: [Float]) -> Bool {
        var sign = 0
        for i in 0..<4 {
            let ax = Double(q[2 * i]), ay = Double(q[2 * i + 1])
            let j = (i + 1) % 4, k = (i + 2) % 4
            let bx = Double(q[2 * j]), by = Double(q[2 * j + 1])
            let cx = Double(q[2 * k]), cy = Double(q[2 * k + 1])
            let cross = (bx - ax) * (cy - by) - (by - ay) * (cx - bx)
            if !(abs(cross) >= 1e-6) { return false }
            let s = cross > 0 ? 1 : -1
            if sign == 0 { sign = s } else if s != sign { return false }
        }
        return true
    }

    static func quadArea(_ q: [Float]) -> Double {
        var a = 0.0
        for i in 0..<4 {
            let j = (i + 1) % 4
            a += Double(q[2 * i]) * Double(q[2 * j + 1]) - Double(q[2 * j]) * Double(q[2 * i + 1])
        }
        return abs(a / 2)
    }

    /// Góc trong nhỏ nhất / lớn nhất (độ).
    static func angleRange(_ q: [Float]) -> (Double, Double) {
        var mn = 360.0, mx = 0.0
        for i in 0..<4 {
            let p = (i + 3) % 4, nn = (i + 1) % 4
            let ux = Double(q[2 * p] - q[2 * i]), uy = Double(q[2 * p + 1] - q[2 * i + 1])
            let vx = Double(q[2 * nn] - q[2 * i]), vy = Double(q[2 * nn + 1] - q[2 * i + 1])
            let lu = hypot(ux, uy), lv = hypot(vx, vy)
            if lu < 1e-9 || lv < 1e-9 { return (0, 180) }
            let c = max(-1, min(1, (ux * vx + uy * vy) / (lu * lv)))
            let a = acos(c) * 180 / Double.pi
            mn = min(mn, a)
            mx = max(mx, a)
        }
        return (mn, mx)
    }

    // MARK: tiền xử lý

    static func downscale(_ px: UnsafePointer<UInt8>, _ w: Int, _ h: Int, _ bpr: Int,
                          _ r: inout [Float], _ g: inout [Float], _ b: inout [Float], _ sw: Int, _ sh: Int) {
        for oy in 0..<sh {
            let y0 = oy * h / sh
            var y1 = (oy + 1) * h / sh
            if y1 <= y0 { y1 = min(h, y0 + 1) }
            for ox in 0..<sw {
                let x0 = ox * w / sw
                var x1 = (ox + 1) * w / sw
                if x1 <= x0 { x1 = min(w, x0 + 1) }
                var sr = 0, sg = 0, sb = 0, cnt = 0
                for y in y0..<y1 {
                    let row = px + y * bpr
                    for x in x0..<x1 {
                        let p = row + x * 4
                        sr += Int(p[0])
                        sg += Int(p[1])
                        sb += Int(p[2])
                        cnt += 1
                    }
                }
                let o = oy * sw + ox
                let fc = Float(max(1, cnt))
                r[o] = Float(sr) / fc
                g[o] = Float(sg) / fc
                b[o] = Float(sb) / fc
            }
        }
    }

    /// Làm mờ Gauss 5 điểm [1 4 6 4 1]/16 (tách hàng/cột).
    static func blur5(_ a: inout [Float], _ tmp: inout [Float], _ w: Int, _ h: Int) {
        let k: Float = 1.0 / 16.0
        for y in 0..<h {
            let row = y * w
            for x in 0..<w {
                let xm2 = max(0, x - 2), xm1 = max(0, x - 1), xp1 = min(w - 1, x + 1), xp2 = min(w - 1, x + 2)
                let s1 = a[row + xm2] + 4 * a[row + xm1] + 6 * a[row + x]
                let s2 = 4 * a[row + xp1] + a[row + xp2]
                tmp[row + x] = (s1 + s2) * k
            }
        }
        for y in 0..<h {
            let ym2 = max(0, y - 2) * w, ym1 = max(0, y - 1) * w, y0 = y * w
            let yp1 = min(h - 1, y + 1) * w, yp2 = min(h - 1, y + 2) * w
            for x in 0..<w {
                let s1 = tmp[ym2 + x] + 4 * tmp[ym1 + x] + 6 * tmp[y0 + x]
                let s2 = 4 * tmp[yp1 + x] + tmp[yp2 + x]
                a[y0 + x] = (s1 + s2) * k
            }
        }
    }

    static func colorSobel(_ r: [Float], _ g: [Float], _ b: [Float], _ w: Int, _ h: Int,
                           _ gx: inout [Float], _ gy: inout [Float], _ mag: inout [Float]) {
        if w < 3 || h < 3 { return }
        for y in 1..<(h - 1) {
            for x in 1..<(w - 1) {
                let i = y * w + x
                var bestM: Float = -1, bx: Float = 0, by: Float = 0
                for c in 0..<3 {
                    let p = c == 0 ? r : (c == 1 ? g : b)
                    let tl = p[i - w - 1], tc = p[i - w], tr = p[i - w + 1]
                    let ml = p[i - 1], mr = p[i + 1]
                    let bl = p[i + w - 1], bc = p[i + w], br = p[i + w + 1]
                    let sx = ((tr + 2 * mr + br) - (tl + 2 * ml + bl)) * 0.125
                    let sy = ((bl + 2 * bc + br) - (tl + 2 * tc + tr)) * 0.125
                    let m = sx * sx + sy * sy
                    if m > bestM {
                        bestM = m
                        bx = sx
                        by = sy
                    }
                }
                gx[i] = bx
                gy[i] = by
                mag[i] = bestM.squareRoot()
            }
        }
    }

    static func nms(_ gx: [Float], _ gy: [Float], _ mag: [Float], _ w: Int, _ h: Int) -> [Float] {
        var out = [Float](repeating: 0, count: w * h)
        if w < 3 || h < 3 { return out }
        for y in 1..<(h - 1) {
            for x in 1..<(w - 1) {
                let i = y * w + x
                let m = mag[i]
                if m < 1 { continue }
                let ax = abs(gx[i]), ay = abs(gy[i])
                let m1: Float, m2: Float
                if ay <= ax * 0.41421356 {
                    m1 = mag[i + 1]
                    m2 = mag[i - 1]
                } else if ay >= ax * 2.41421356 {
                    m1 = mag[i + w]
                    m2 = mag[i - w]
                } else if gx[i] * gy[i] > 0 {
                    m1 = mag[i + w + 1]
                    m2 = mag[i - w - 1]
                } else {
                    m1 = mag[i - w + 1]
                    m2 = mag[i + w - 1]
                }
                if m > m1 && m >= m2 { out[i] = m }
            }
        }
        return out
    }

    // MARK: Hough

    static func hough(_ em: [Float], _ gx: [Float], _ gy: [Float], _ w: Int, _ h: Int, _ minEdge: Float) -> [Line] {
        let T = 180
        let D = Int(ceil(hypot(Double(w), Double(h))))
        let RN = 2 * D + 1
        var acc = [Int32](repeating: 0, count: T * RN)
        var cs = [Double](repeating: 0, count: T), sn = [Double](repeating: 0, count: T)
        for t in 0..<T {
            cs[t] = cos(Double.pi * Double(t) / Double(T))
            sn[t] = sin(Double.pi * Double(t) / Double(T))
        }
        for y in 1..<(h - 1) {
            for x in 1..<(w - 1) {
                let i = y * w + x
                let e = em[i]
                if e < minEdge { continue }
                let wt = Int32(min(16, e))
                var phi = atan2(Double(gy[i]), Double(gx[i]))
                if phi < 0 { phi += Double.pi }
                let tb = jround(phi * Double(T) / Double.pi)
                for d in -2...2 {
                    var t = tb + d
                    if t < 0 { t += T } else if t >= T { t -= T }
                    if t < 0 || t >= T { continue }
                    let r = jround(Double(x) * cs[t] + Double(y) * sn[t]) + D
                    if r >= 0 && r < RN { acc[t * RN + r] += wt }
                }
            }
        }
        let minVotes = Int32(max(40, Int(0.10 * Double(min(w, h)) * 4)))
        var peaks = [(v: Int32, t: Int, r: Int, idx: Int)]()
        for t in 0..<T {
            for r in 0..<RN {
                let v = acc[t * RN + r]
                if v < minVotes { continue }
                var isMax = true
                var dt = -2
                while dt <= 2 && isMax {
                    let tt = t + dt
                    if tt >= 0 && tt < T {
                        for dr in -3...3 {
                            let rr = r + dr
                            if rr < 0 || rr >= RN || (dt == 0 && dr == 0) { continue }
                            let u = acc[tt * RN + rr]
                            if u > v || (u == v && (dt < 0 || (dt == 0 && dr < 0))) {
                                isMax = false
                                break
                            }
                        }
                    }
                    dt += 1
                }
                if isMax {
                    let idx = peaks.count
                    peaks.append((v: v, t: t, r: r - D, idx: idx))
                }
            }
        }
        peaks.sort { a, b in a.v != b.v ? a.v > b.v : a.idx < b.idx }
        var out = [Line]()
        var accepted = [(t: Int, r: Int)]()
        for p in peaks {
            var dup = false
            for a in accepted {
                var dtt = abs(p.t - a.t)
                let rp = p.r
                var ra = a.r
                if dtt > T / 2 {
                    dtt = T - dtt
                    ra = -ra
                }
                if dtt <= 3 && abs(rp - ra) <= 5 {
                    dup = true
                    break
                }
            }
            if dup { continue }
            accepted.append((t: p.t, r: p.r))
            let L = Line()
            let th = Double.pi * Double(p.t) / Double(T)
            L.setNormal(cos(th), sin(th), Double(p.r))
            L.votes = Int(p.v)
            out.append(L)
            if accepted.count >= 64 { break }
        }
        return out
    }

    /// Khớp lại đường thẳng bằng bình phương tối thiểu toàn phần trên các điểm cạnh lân cận.
    static func refineLine(_ L: Line, _ em: [Float], _ gx: [Float], _ gy: [Float], _ mag: [Float], _ w: Int, _ h: Int, _ lowS: Float) {
        for iter in 0..<2 {
            let tol = iter == 0 ? 2.5 : 1.5
            guard let range = clip(L, w, h) else { return }
            var sw = 0.0, sx = 0.0, sy = 0.0, sxx = 0.0, syy = 0.0, sxy = 0.0
            var cnt = 0
            let k = Int(ceil(tol))
            var t = range.0
            while t <= range.1 {
                let px = L.rho * L.nx + t * L.dx, py = L.rho * L.ny + t * L.dy
                for o in -k...k {
                    let xx = jround(px + Double(o) * L.nx), yy = jround(py + Double(o) * L.ny)
                    if xx < 1 || yy < 1 || xx >= w - 1 || yy >= h - 1 { continue }
                    let i = yy * w + xx
                    if em[i] < lowS { continue }
                    let dist = abs(L.nx * Double(xx) + L.ny * Double(yy) - L.rho)
                    if dist > tol { continue }
                    if abs(Double(gx[i]) * L.nx + Double(gy[i]) * L.ny) < 0.8 * Double(mag[i]) { continue }
                    let wt = Double(min(em[i], 40))
                    let fx = Double(xx), fy = Double(yy)
                    sw += wt
                    sx += wt * fx
                    sy += wt * fy
                    sxx += wt * fx * fx
                    syy += wt * fy * fy
                    sxy += wt * fx * fy
                    cnt += 1
                }
                t += 1.0
            }
            if cnt < 12 || sw <= 0 { return }
            let mx = sx / sw, my = sy / sw
            let cxx = sxx / sw - mx * mx, cyy = syy / sw - my * my, cxy = sxy / sw - mx * my
            let tr = cxx + cyy, det = cxx * cyy - cxy * cxy
            let disc = max(0, tr * tr / 4 - det).squareRoot()
            let lmin = tr / 2 - disc
            var nx: Double, ny: Double
            if abs(cxy) > 1e-9 {
                nx = cxy
                ny = lmin - cxx
            } else if cxx < cyy {
                nx = 1
                ny = 0
            } else {
                nx = 0
                ny = 1
            }
            let l = hypot(nx, ny)
            if l < 1e-12 { return }
            nx /= l
            ny /= l
            if abs(nx * L.nx + ny * L.ny) < cos(4 * Double.pi / 180) { return }
            if nx * L.nx + ny * L.ny < 0 {
                nx = -nx
                ny = -ny
            }
            L.setNormal(nx, ny, nx * mx + ny * my)
        }
    }

    static func clip(_ L: Line, _ w: Int, _ h: Int) -> (Double, Double)? {
        let p0 = [L.rho * L.nx, L.rho * L.ny]
        let d = [L.dx, L.dy]
        let mm = [0.0, Double(w - 1), 0.0, Double(h - 1)]
        var lo = -1e9, hi = 1e9
        for a in 0..<2 {
            let mn = mm[2 * a], mx = mm[2 * a + 1]
            if abs(d[a]) < 1e-9 {
                if p0[a] < mn || p0[a] > mx { return nil }
            } else {
                let t1 = (mn - p0[a]) / d[a], t2 = (mx - p0[a]) / d[a]
                lo = max(lo, min(t1, t2))
                hi = min(hi, max(t1, t2))
            }
        }
        if !(hi - lo >= 4) { return nil }
        return (lo, hi)
    }

    /// Hồ sơ dọc đường: mẫu là "cạnh" khi gradient vuông góc nổi bật so với nền quanh (thích nghi kết cấu),
    /// kèm tương phản hai phía.
    static func buildProfile(_ L: Line, _ gx: [Float], _ gy: [Float], _ mag: [Float], _ gray: [Float], _ w: Int, _ h: Int) -> Bool {
        guard let range = clip(L, w, h) else { return false }
        L.ta = range.0
        L.len = toInt(floor(range.1 - range.0)) + 1
        if L.len < 1 || L.len > 100_000 { return false }
        var sup = [Int32](repeating: 0, count: L.len + 1)
        var con = [Float](repeating: 0, count: L.len + 1)
        var acon = [Float](repeating: 0, count: L.len + 1)
        let nx = L.nx, ny = L.ny
        for i in 0..<L.len {
            let t = L.ta + Double(i)
            let px = L.rho * nx + t * L.dx, py = L.rho * ny + t * L.dy
            var on: Float = 0
            var aligned = false
            for o in -1...1 {
                let xx = jround(px + Double(o) * nx), yy = jround(py + Double(o) * ny)
                if xx < 1 || yy < 1 || xx >= w - 1 || yy >= h - 1 { continue }
                let idx = yy * w + xx
                let v = Float(abs(Double(gx[idx]) * nx + Double(gy[idx]) * ny))
                if v > on {
                    on = v
                    aligned = v >= 0.8 * mag[idx]
                }
            }
            var bg: Float = 0
            var bc = 0
            var o = -6
            while o <= 6 {
                if !(o > -4 && o < 4) {
                    let xx = jround(px + Double(o) * nx), yy = jround(py + Double(o) * ny)
                    if !(xx < 1 || yy < 1 || xx >= w - 1 || yy >= h - 1) {
                        let idx = yy * w + xx
                        bg += Float(abs(Double(gx[idx]) * nx + Double(gy[idx]) * ny))
                        bc += 1
                    }
                }
                o += 2
            }
            if bc > 0 { bg /= Float(bc) }
            let hit: Int32 = (aligned && on >= 2.0 && on >= 1.7 * bg + 0.5) ? 1 : 0
            let a = sample(gray, w, h, px + 4 * nx, py + 4 * ny)
            let b = sample(gray, w, h, px - 4 * nx, py - 4 * ny)
            sup[i + 1] = sup[i] + hit
            con[i + 1] = con[i] + (a - b)
            acon[i + 1] = acon[i] + abs(a - b)
        }
        L.sup = sup
        L.con = con
        L.acon = acon
        return true
    }

    static func sample(_ img: [Float], _ w: Int, _ h: Int, _ x0: Double, _ y0: Double) -> Float {
        var x = x0, y = y0
        if !(x >= 0) { x = 0 }
        if !(y >= 0) { y = 0 }
        if x > Double(w - 1) { x = Double(w - 1) }
        if y > Double(h - 1) { y = Double(h - 1) }
        let xi = Int(x), yi = Int(y)
        let x1 = min(w - 1, xi + 1), y1 = min(h - 1, yi + 1)
        let fx = Float(x - Double(xi)), fy = Float(y - Double(yi))
        let a = img[yi * w + xi] * (1 - fx) + img[yi * w + x1] * fx
        let b = img[y1 * w + xi] * (1 - fx) + img[y1 * w + x1] * fx
        return a * (1 - fy) + b * fy
    }

    // MARK: tinh chỉnh dưới-pixel

    /// Tinh chỉnh từng cạnh ở độ phân giải ảnh vào: tìm điểm gradient lớn nhất dọc pháp tuyến,
    /// khớp lại đường thẳng rồi giao các cạnh để ra góc mới.
    static func refine(_ px: UnsafePointer<UInt8>, _ w: Int, _ h: Int, _ bpr: Int, _ q: [Float], _ radius: Float) -> [Float]? {
        let n = w * h
        var gray = [Float](repeating: 0, count: n)
        for y in 0..<h {
            let row = px + y * bpr
            for x in 0..<w {
                let p = row + x * 4
                gray[y * w + x] = 0.299 * Float(p[0]) + 0.587 * Float(p[1]) + 0.114 * Float(p[2])
            }
        }
        var tmp = [Float](repeating: 0, count: n)
        blur5(&gray, &tmp, w, h)
        var lines = [(Double, Double, Double)?](repeating: nil, count: 4)
        var any = false
        let R = max(3, Int(radius.rounded()))
        var prof = [Float](repeating: 0, count: 2 * R + 5)
        for s in 0..<4 {
            let a = s, b = (s + 1) % 4
            let ax = Double(q[2 * a]), ay = Double(q[2 * a + 1]), bx = Double(q[2 * b]), by = Double(q[2 * b + 1])
            let len = hypot(bx - ax, by - ay)
            if !(len >= 10) { continue }
            let ux = (bx - ax) / len, uy = (by - ay) / len
            let nx = -uy, ny = ux
            let W = Double(w), H = Double(h)
            let atBorder = (ax < 2 && bx < 2) || (ay < 2 && by < 2) || (ax > W - 3 && bx > W - 3) || (ay > H - 3 && by > H - 3)
            if atBorder { continue }
            let steps = max(8, min(4000, toInt(len / 2)))
            var pxs = [Double](repeating: 0, count: steps), pys = [Double](repeating: 0, count: steps)
            var sumSigned = 0.0
            for i in 0..<steps {
                let t = 0.06 + 0.88 * (Double(i) + 0.5) / Double(steps)
                pxs[i] = ax + (bx - ax) * t
                pys[i] = ay + (by - ay) * t
                var bestAbs = 0.0, bd = 0.0
                for k in -R...R {
                    let d1 = sample(gray, w, h, pxs[i] + Double(k + 1) * nx, pys[i] + Double(k + 1) * ny)
                    let d0 = sample(gray, w, h, pxs[i] + Double(k - 1) * nx, pys[i] + Double(k - 1) * ny)
                    let d = Double(d1 - d0) * 0.5
                    if abs(d) > bestAbs {
                        bestAbs = abs(d)
                        bd = d
                    }
                }
                sumSigned += bd
            }
            let sign = sumSigned >= 0 ? 1.0 : -1.0
            var ptsX = [Double](repeating: 0, count: steps)
            var ptsY = [Double](repeating: 0, count: steps)
            var ptsW = [Double](repeating: 0, count: steps)
            var cnt = 0
            for i in 0..<steps {
                let cx = pxs[i], cy = pys[i]
                for k in (-R - 1)...(R + 1) {
                    let d1 = sample(gray, w, h, cx + Double(k + 1) * nx, cy + Double(k + 1) * ny)
                    let d0 = sample(gray, w, h, cx + Double(k - 1) * nx, cy + Double(k - 1) * ny)
                    prof[k + R + 1] = Float(Double(d1 - d0) * 0.5 * sign)
                }
                var best = 0.0
                var bk = 0
                for k in -R...R {
                    let d = Double(prof[k + R + 1])
                    if d > best {
                        best = d
                        bk = k
                    }
                }
                if best < 2.5 { continue }
                let dm = Double(prof[bk + R]), d0 = Double(prof[bk + R + 1]), dp = Double(prof[bk + R + 2])
                let den = dm - 2 * d0 + dp
                var off = Double(bk)
                if abs(den) > 1e-6 {
                    let sub = 0.5 * (dm - dp) / den
                    if sub > -1 && sub < 1 { off += sub }
                }
                ptsX[cnt] = cx + off * nx
                ptsY[cnt] = cy + off * ny
                ptsW[cnt] = best
                cnt += 1
            }
            if cnt < max(8, steps / 4) { continue }
            guard let line = fitTLS(ptsX, ptsY, ptsW, cnt, nil) else { continue }
            var keep = [Bool](repeating: false, count: cnt)
            var kept = 0
            for i in 0..<cnt {
                let r = abs(line.0 * ptsX[i] + line.1 * ptsY[i] - line.2)
                keep[i] = r <= 1.5
                if keep[i] { kept += 1 }
            }
            if kept < max(8, steps / 4) { continue }
            guard let line2 = fitTLS(ptsX, ptsY, ptsW, cnt, keep) else { continue }
            if abs(line2.0 * nx + line2.1 * ny) < cos(3 * Double.pi / 180) { continue }
            lines[s] = line2
            any = true
        }
        if !any { return nil }
        var out = [Float](repeating: 0, count: 8)
        let diag = hypot(Double(w), Double(h))
        for c in 0..<4 {
            let sPrev = (c + 3) % 4, sNext = c
            let l1 = lines[sPrev] ?? lineThrough(q, sPrev)
            let l2 = lines[sNext] ?? lineThrough(q, sNext)
            let ox = Double(q[2 * c]), oy = Double(q[2 * c + 1])
            var x = ox, y = oy
            let det = l1.0 * l2.1 - l1.1 * l2.0
            if abs(det) > 1e-9 {
                let ix = (l1.2 * l2.1 - l2.2 * l1.1) / det
                let iy = (l1.0 * l2.2 - l2.0 * l1.2) / det
                if hypot(ix - ox, iy - oy) <= max(6, 0.025 * diag) {
                    x = ix
                    y = iy
                }
            }
            out[2 * c] = Float(x)
            out[2 * c + 1] = Float(y)
        }
        if !isConvex(out) { return nil }
        return out
    }

    private static func lineThrough(_ q: [Float], _ s: Int) -> (Double, Double, Double) {
        let a = s, b = (s + 1) % 4
        let ax = Double(q[2 * a]), ay = Double(q[2 * a + 1]), bx = Double(q[2 * b]), by = Double(q[2 * b + 1])
        let len = max(1e-9, hypot(bx - ax, by - ay))
        let nx = -(by - ay) / len, ny = (bx - ax) / len
        return (nx, ny, nx * ax + ny * ay)
    }

    private static func fitTLS(_ xs: [Double], _ ys: [Double], _ ws: [Double], _ n: Int, _ keep: [Bool]?) -> (Double, Double, Double)? {
        var sw = 0.0, sx = 0.0, sy = 0.0
        for i in 0..<n {
            if let k = keep, !k[i] { continue }
            sw += ws[i]
            sx += ws[i] * xs[i]
            sy += ws[i] * ys[i]
        }
        if sw <= 0 { return nil }
        let mx = sx / sw, my = sy / sw
        var cxx = 0.0, cyy = 0.0, cxy = 0.0
        for i in 0..<n {
            if let k = keep, !k[i] { continue }
            let dx = xs[i] - mx, dy = ys[i] - my
            cxx += ws[i] * dx * dx
            cyy += ws[i] * dy * dy
            cxy += ws[i] * dx * dy
        }
        let tr = cxx + cyy, det = cxx * cyy - cxy * cxy
        let disc = max(0, tr * tr / 4 - det).squareRoot()
        let lmin = tr / 2 - disc
        var nx: Double, ny: Double
        if abs(cxy) > 1e-12 {
            nx = cxy
            ny = lmin - cxx
        } else if cxx < cyy {
            nx = 1
            ny = 0
        } else {
            nx = 0
            ny = 1
        }
        let l = hypot(nx, ny)
        if l < 1e-12 { return nil }
        nx /= l
        ny /= l
        return (nx, ny, nx * mx + ny * my)
    }
}
