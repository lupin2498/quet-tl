import Foundation

/// Bộ lọc làm rõ tài liệu (port từ Enhancer.java): ước lượng nền giấy theo lưới,
/// chuẩn hoá về nền trắng rồi áp đường cong tông. Dữ liệu điểm ảnh RGBA 8 bit.
enum Enhancer {
    static let original = 0
    static let magic = 1
    static let lighten = 2
    static let gray = 3
    static let bw = 4

    /// Thứ tự hiển thị trong bảng lọc.
    static let modes = [original, magic, lighten, gray, bw]
    static func name(_ mode: Int) -> String {
        switch mode {
        case original: return "Gốc"
        case lighten: return "Làm sáng"
        case gray: return "Thang xám"
        case bw: return "Đen trắng"
        default: return "Tăng cường"
        }
    }

    private struct Background {
        var gw = 0, gh = 0
        var cell: Float = 8
        var r = [Float](), g = [Float](), b = [Float]()
    }

    static func apply(_ px: UnsafeMutablePointer<UInt8>, width w: Int, height h: Int, bytesPerRow bpr: Int, mode: Int) {
        if mode == original || w < 4 || h < 4 { return }
        let bg = background(px, w, h, bpr)
        var wp: Float, bp: Float, sat: Float, gamma: Float
        switch mode {
        case lighten: bp = 0.04; wp = 0.95; sat = 1.10; gamma = 1.15
        case gray: bp = 0.18; wp = 0.90; sat = 0; gamma = 1.5
        case bw: bp = 0.62; wp = 0.74; sat = 0; gamma = 1.0
        default: bp = 0.20; wp = 0.88; sat = 1.35; gamma = 1.6
        }
        let lutScale: Float = 1024
        let lutN = 2048
        var lut = [UInt8](repeating: 0, count: lutN)
        let inv = 1 / max(1e-3, wp - bp)
        for i in 0..<lutN {
            let v = Float(i) / lutScale
            let t = (v - bp) * inv
            if t <= 0 { lut[i] = 0 } else if t >= 1 { lut[i] = 255 } else { lut[i] = UInt8(min(255, Int(pow(t, gamma) * 255 + 0.5))) }
        }
        let mono = mode == gray || mode == bw
        var rowR = [Float](repeating: 1, count: w)
        var rowG = [Float](repeating: 1, count: w)
        var rowB = [Float](repeating: 1, count: w)
        var tr = [Float](repeating: 0, count: bg.gw)
        var tg = [Float](repeating: 0, count: bg.gw)
        var tb = [Float](repeating: 0, count: bg.gw)
        @inline(__always) func idx(_ v: Float) -> Int {
            let i = Int(v * lutScale)
            return i < 0 ? 0 : (i >= lutN ? lutN - 1 : i)
        }
        lut.withUnsafeBufferPointer { lutP in
            for y in 0..<h {
                // nội suy lưới nền theo chiều dọc rồi ngang
                var fy = (Float(y) + 0.5) / bg.cell - 0.5
                var y0 = Int(floor(fy))
                var wy = fy - Float(y0)
                if y0 < 0 { y0 = 0; wy = 0 }
                if y0 >= bg.gh - 1 { y0 = bg.gh - 1; wy = 0 }
                let y1 = min(bg.gh - 1, y0 + 1)
                fy = wy
                for i in 0..<bg.gw {
                    tr[i] = bg.r[y0 * bg.gw + i] * (1 - fy) + bg.r[y1 * bg.gw + i] * fy
                    tg[i] = bg.g[y0 * bg.gw + i] * (1 - fy) + bg.g[y1 * bg.gw + i] * fy
                    tb[i] = bg.b[y0 * bg.gw + i] * (1 - fy) + bg.b[y1 * bg.gw + i] * fy
                }
                for x in 0..<w {
                    let fx = (Float(x) + 0.5) / bg.cell - 0.5
                    var x0 = Int(floor(fx))
                    var wx = fx - Float(x0)
                    if x0 < 0 { x0 = 0; wx = 0 }
                    if x0 >= bg.gw - 1 { x0 = bg.gw - 1; wx = 0 }
                    let x1 = min(bg.gw - 1, x0 + 1)
                    rowR[x] = tr[x0] * (1 - wx) + tr[x1] * wx
                    rowG[x] = tg[x0] * (1 - wx) + tg[x1] * wx
                    rowB[x] = tb[x0] * (1 - wx) + tb[x1] * wx
                }
                let row = px + y * bpr
                for x in 0..<w {
                    let p = row + x * 4
                    var r = Float(p[0]) / rowR[x]
                    var g = Float(p[1]) / rowG[x]
                    var b = Float(p[2]) / rowB[x]
                    let lum = 0.299 * r + 0.587 * g + 0.114 * b
                    if mono {
                        let v = lutP[idx(lum)]
                        p[0] = v; p[1] = v; p[2] = v
                    } else {
                        r = lum + (r - lum) * sat
                        g = lum + (g - lum) * sat
                        b = lum + (b - lum) * sat
                        p[0] = lutP[idx(r)]
                        p[1] = lutP[idx(g)]
                        p[2] = lutP[idx(b)]
                    }
                    p[3] = 255
                }
            }
        }
    }

    private static func background(_ px: UnsafeMutablePointer<UInt8>, _ w: Int, _ h: Int, _ bpr: Int) -> Background {
        let cell = max(8, Int((Float(max(w, h)) / 48).rounded()))
        let gw = (w + cell - 1) / cell, gh = (h + cell - 1) / cell
        let n = gw * gh
        var r = [Float](repeating: 0, count: n), g = [Float](repeating: 0, count: n), b = [Float](repeating: 0, count: n)
        let step = max(1, cell / 12)
        for gy in 0..<gh {
            let ys = gy * cell, ye = min(h, ys + cell)
            for gx in 0..<gw {
                let xs = gx * cell, xe = min(w, xs + cell)
                var bestL: Float = -1, br: Float = 0, bgc: Float = 0, bb: Float = 0
                var y = ys + 1
                while y < ye - 1 {
                    var x = xs + 1
                    while x < xe - 1 {
                        var sr = 0, sg = 0, sb = 0
                        for dy in -1...1 {
                            let rowp = px + (y + dy) * bpr
                            for dx in -1...1 {
                                let p = rowp + (x + dx) * 4
                                sr += Int(p[0]); sg += Int(p[1]); sb += Int(p[2])
                            }
                        }
                        let l = 0.299 * Float(sr) + 0.587 * Float(sg) + 0.114 * Float(sb)
                        if l > bestL {
                            bestL = l
                            br = Float(sr) / 9; bgc = Float(sg) / 9; bb = Float(sb) / 9
                        }
                        x += step
                    }
                    y += step
                }
                if bestL < 0 {
                    let p = px + min(h - 1, ys) * bpr + min(w - 1, xs) * 4
                    br = Float(p[0]); bgc = Float(p[1]); bb = Float(p[2])
                }
                let i = gy * gw + gx
                r[i] = br; g[i] = bgc; b[i] = bb
            }
        }
        // ô thuộc vùng tối lớn -> không phải nền giấy -> nội suy từ ô lân cận
        var lum = [Float](repeating: 0, count: n)
        for i in 0..<n { lum[i] = 0.299 * r[i] + 0.587 * g[i] + 0.114 * b[i] }
        let sorted = lum.sorted()
        let paper = sorted[min(n - 1, Int(Float(n) * 0.9))]
        var valid = lum.map { $0 >= 0.62 * paper }
        var nValid = valid.filter { $0 }.count
        if nValid == 0 {
            valid = [Bool](repeating: true, count: n)
        } else {
            var changed = true
            var guardCount = 0
            while nValid < n && changed && guardCount < gw + gh {
                guardCount += 1
                changed = false
                var nv = valid
                for y in 0..<gh {
                    for x in 0..<gw {
                        let i = y * gw + x
                        if valid[i] { continue }
                        var sr: Float = 0, sg: Float = 0, sb: Float = 0
                        var c = 0
                        for dy in -1...1 {
                            let yy = y + dy
                            if yy < 0 || yy >= gh { continue }
                            for dx in -1...1 {
                                let xx = x + dx
                                if xx < 0 || xx >= gw { continue }
                                let j = yy * gw + xx
                                if !valid[j] { continue }
                                sr += r[j]; sg += g[j]; sb += b[j]; c += 1
                            }
                        }
                        if c > 0 {
                            r[i] = sr / Float(c); g[i] = sg / Float(c); b[i] = sb / Float(c)
                            nv[i] = true
                            nValid += 1
                            changed = true
                        }
                    }
                }
                valid = nv
            }
        }
        for _ in 0..<2 {
            r = box3(r, gw, gh)
            g = box3(g, gw, gh)
            b = box3(b, gw, gh)
        }
        for i in 0..<n {
            r[i] = max(24, r[i]); g[i] = max(24, g[i]); b[i] = max(24, b[i])
        }
        var out = Background()
        out.gw = gw; out.gh = gh; out.cell = Float(cell)
        out.r = r; out.g = g; out.b = b
        return out
    }

    private static func box3(_ a: [Float], _ w: Int, _ h: Int) -> [Float] {
        var o = [Float](repeating: 0, count: a.count)
        for y in 0..<h {
            for x in 0..<w {
                var s: Float = 0
                var c = 0
                for dy in -1...1 {
                    let yy = y + dy
                    if yy < 0 || yy >= h { continue }
                    for dx in -1...1 {
                        let xx = x + dx
                        if xx < 0 || xx >= w { continue }
                        s += a[yy * w + xx]
                        c += 1
                    }
                }
                o[y * w + x] = s / Float(c)
            }
        }
        return o
    }
}
