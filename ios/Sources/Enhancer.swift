import Foundation

/// Bộ lọc làm rõ tài liệu (port từ Enhancer.java): ước lượng nền giấy theo lưới,
/// chuẩn hoá về nền trắng rồi áp đường cong tông. Dữ liệu điểm ảnh RGBA 8 bit.
enum Enhancer {
    static let original = 0
    static let magic = 1      // Tăng cường (mặc định)
    static let lighten = 2
    static let gray = 3
    static let bw = 4
    static let magicPro = 5   // Thần kỳ: gỡ ngón tay, khử bóng, nền trắng, chữ sắc nét
    static let denoise = 6    // Khử nhiễu: làm mịn hạt/vân, giữ nguyên tông ảnh
    static let noShadow = 7   // Không bóng: khử bóng đổ, giữ màu tự nhiên
    static let noHand = 8     // Ko viết tay: xoá nét bút màu
    static let flatten = 9    // Làm phẳng: giấy mấp mô / gấp nếp — chỉ giữ chữ + bảng, nắn thẳng (Flatten)

    /// Thứ tự hiển thị trong bảng lọc.
    static let modes = [original, lighten, magic, magicPro, flatten, denoise, noShadow, noHand, bw, gray]
    static func name(_ mode: Int) -> String {
        switch mode {
        case original: return "Nguyên gốc"
        case lighten: return "Làm sáng"
        case gray: return "Thang xám"
        case bw: return "Đen trắng"
        case magicPro: return "Thần kỳ"
        case denoise: return "Khử nhiễu"
        case noShadow: return "Không bóng"
        case noHand: return "Ko viết tay"
        case flatten: return "Làm phẳng"
        default: return "Tăng cường"
        }
    }

    /// Bộ lọc có gỡ ngón tay giữ giấy (khi đã chọn thì không cần lời nhắc ngón tay).
    static func removesFingers(_ mode: Int) -> Bool {
        return mode == magicPro || mode == flatten
    }

    /// Lưới nền theo từng kênh màu, nội suy song tuyến tính.
    final class Bg {
        var gw = 0, gh = 0
        var cell: Float = 8       // kích thước ô theo pixel ảnh gốc
        var r = [Float](), g = [Float](), b = [Float]()   // giá trị nền (0..255)
        private var tr = [Float](), tg = [Float](), tb = [Float]()   // hàng đã nội suy theo chiều dọc

        /// Nền của hàng y cho từng cột (out* dài bằng bề ngang ảnh).
        func row(_ y: Int, _ outR: inout [Float], _ outG: inout [Float], _ outB: inout [Float]) {
            if tr.count != gw {
                tr = [Float](repeating: 0, count: gw)
                tg = [Float](repeating: 0, count: gw)
                tb = [Float](repeating: 0, count: gw)
            }
            let fy = (Float(y) + 0.5) / cell - 0.5
            var y0 = Int(floor(fy))
            var wy = fy - Float(y0)
            if y0 < 0 { y0 = 0; wy = 0 }
            if y0 >= gh - 1 { y0 = gh - 1; wy = 0 }
            let y1 = min(gh - 1, y0 + 1)
            for i in 0..<gw {
                tr[i] = r[y0 * gw + i] * (1 - wy) + r[y1 * gw + i] * wy
                tg[i] = g[y0 * gw + i] * (1 - wy) + g[y1 * gw + i] * wy
                tb[i] = b[y0 * gw + i] * (1 - wy) + b[y1 * gw + i] * wy
            }
            let w = outR.count
            for x in 0..<w {
                let fx = (Float(x) + 0.5) / cell - 0.5
                var x0 = Int(floor(fx))
                var wx = fx - Float(x0)
                if x0 < 0 { x0 = 0; wx = 0 }
                if x0 >= gw - 1 { x0 = gw - 1; wx = 0 }
                let x1 = min(gw - 1, x0 + 1)
                outR[x] = tr[x0] * (1 - wx) + tr[x1] * wx
                outG[x] = tg[x0] * (1 - wx) + tg[x1] * wx
                outB[x] = tb[x0] * (1 - wx) + tb[x1] * wx
            }
        }
    }

    /// Áp bộ lọc tại chỗ. geometric = false: bộ lọc dời điểm ảnh (Làm phẳng) bỏ bước nắn thẳng, chỉ làm sạch — dùng cho
    /// ảnh xem trước của "Xóa thông minh" (nét xoá được áp lên ảnh trước khi lọc nên phải vẽ trên ảnh chưa nắn).
    static func apply(_ px: UnsafeMutablePointer<UInt8>, width w: Int, height h: Int, bytesPerRow bpr: Int, mode: Int,
                      geometric: Bool = true) {
        if mode == original || w < 4 || h < 4 { return }
        switch mode {
        case flatten:
            _ = Flatten.apply(px, w, h, bpr, dewarp: geometric)
            return
        case magicPro:
            _ = Retouch.removeFingers(px, w, h, bpr)
            tonePro(px, w, h, bpr, backgroundFine(px, w, h, bpr, nil), 0.24, 0.86, 1.45)
            Retouch.sharpen(px, w, h, bpr, 96)
            Retouch.cleanEdges(px, w, h, bpr)
            return
        case denoise:
            Retouch.denoise(px, w, h, bpr)
            return
        case noShadow:
            tone(px, w, h, bpr, backgroundFine(px, w, h, bpr, nil), 0.02, 0.94, 1.0, 1.0, false)
            return
        case noHand:
            _ = Retouch.eraseHandwriting(px, w, h, bpr, nil)
            tone(px, w, h, bpr, background(px, w, h, bpr), 0.04, 0.95, 1.10, 1.15, false)
            return
        default:
            break
        }
        let bg = background(px, w, h, bpr)
        var wp: Float, bp: Float, sat: Float, gamma: Float
        switch mode {
        case lighten: bp = 0.04; wp = 0.95; sat = 1.10; gamma = 1.15
        case gray: bp = 0.18; wp = 0.90; sat = 0; gamma = 1.5
        case bw: bp = 0.62; wp = 0.74; sat = 0; gamma = 1.0
        default: bp = 0.20; wp = 0.88; sat = 1.35; gamma = 1.6
        }
        tone(px, w, h, bpr, bg, bp, wp, sat, gamma, mode == gray || mode == bw)
    }

    private static let lutScale: Float = 1024
    private static let lutN = 2048

    /// Bảng tra: giá trị chuẩn hoá v (0..2) -> 0..255 (mức đen/trắng + gamma).
    private static func makeLut(_ bp: Float, _ wp: Float, _ gamma: Float) -> [UInt8] {
        var lut = [UInt8](repeating: 0, count: lutN)
        let inv = 1 / max(1e-3, wp - bp)
        for i in 0..<lutN {
            let v = Float(i) / lutScale
            let t = (v - bp) * inv
            // luỹ thừa tính bằng Double như bản Java (Math.pow) để bảng tra trùng nhau
            if t <= 0 { lut[i] = 0 } else if t >= 1 { lut[i] = 255 } else { lut[i] = UInt8(min(255, max(0, Int(pow(Double(t), Double(gamma)) * 255.0 + 0.5)))) }
        }
        return lut
    }

    @inline(__always) private static func idx(_ v: Float) -> Int {
        let i = Int(v * lutScale)
        return i < 0 ? 0 : (i >= lutN ? lutN - 1 : i)
    }

    /// Chuẩn hoá theo nền rồi áp mức đen/trắng + gamma + độ bão hoà.
    static func tone(_ px: UnsafeMutablePointer<UInt8>, _ w: Int, _ h: Int, _ bpr: Int, _ bg: Bg,
                     _ bp: Float, _ wp: Float, _ sat: Float, _ gamma: Float, _ mono: Bool) {
        let lut = makeLut(bp, wp, gamma)
        var rowR = [Float](repeating: 1, count: w)
        var rowG = [Float](repeating: 1, count: w)
        var rowB = [Float](repeating: 1, count: w)
        lut.withUnsafeBufferPointer { lutP in
            for y in 0..<h {
                bg.row(y, &rowR, &rowG, &rowB)
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

    /// Đường cong tông cho "Thần kỳ": như tone() nhưng màu gần trung tính (chữ in đen, viền nhiễu màu do nén ảnh)
    /// được đưa hẳn về xám, còn mực màu thật (con dấu, bút xanh) được giữ và làm đậm.
    static func tonePro(_ px: UnsafeMutablePointer<UInt8>, _ w: Int, _ h: Int, _ bpr: Int, _ bg: Bg,
                        _ bp: Float, _ wp: Float, _ gamma: Float) {
        let lut = makeLut(bp, wp, gamma)
        var rowR = [Float](repeating: 1, count: w)
        var rowG = [Float](repeating: 1, count: w)
        var rowB = [Float](repeating: 1, count: w)
        lut.withUnsafeBufferPointer { lutP in
            for y in 0..<h {
                bg.row(y, &rowR, &rowG, &rowB)
                let row = px + y * bpr
                for x in 0..<w {
                    let p = row + x * 4
                    var r = Float(p[0]) / rowR[x]
                    var g = Float(p[1]) / rowG[x]
                    var b = Float(p[2]) / rowB[x]
                    let lum = 0.299 * r + 0.587 * g + 0.114 * b
                    let mx = r > g ? (r > b ? r : b) : (g > b ? g : b)
                    let mn = r < g ? (r < b ? r : b) : (g < b ? g : b)
                    var k = (mx - mn - 0.10) / 0.16
                    if k <= 0 {
                        let v = lutP[idx(lum)]
                        p[0] = v; p[1] = v; p[2] = v
                    } else {
                        if k > 1 { k = 1 }
                        k = k * 1.3
                        r = lum + (r - lum) * k
                        g = lum + (g - lum) * k
                        b = lum + (b - lum) * k
                        p[0] = lutP[idx(r)]
                        p[1] = lutP[idx(g)]
                        p[2] = lutP[idx(b)]
                    }
                    p[3] = 255
                }
            }
        }
    }

    static func background(_ px: UnsafeMutablePointer<UInt8>, _ w: Int, _ h: Int, _ bpr: Int) -> Bg {
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
        let nValid = valid.filter { $0 }.count
        if nValid == 0 {
            valid = [Bool](repeating: true, count: n)
        } else {
            fillInvalid(&r, &g, &b, &valid, nValid, gw, gh)
        }
        limitChroma(&r, &g, &b, lum, paper)
        for _ in 0..<2 {
            r = box3(r, gw, gh)
            g = box3(g, gw, gh)
            b = box3(b, gw, gh)
        }
        for i in 0..<n {
            r[i] = max(24, r[i]); g[i] = max(24, g[i]); b[i] = max(24, b[i])
        }
        let out = Bg()
        out.gw = gw; out.gh = gh; out.cell = Float(cell)
        out.r = r; out.g = g; out.b = b
        return out
    }

    /// Nền giấy lưới mịn (ô ~1/320 cạnh dài) cho ảnh có bóng đổ rõ mép:
    /// điểm sáng nhất trong ô -> bỏ ô quá tối -> phép đóng hình thái (xoá chữ đậm, hình nhỏ khỏi nền, giữ bóng lớn)
    /// -> giới hạn sắc độ -> làm mượt. mask != 0: điểm ảnh bị bỏ qua (vùng sẽ được tô lại).
    static func backgroundFine(_ px: UnsafeMutablePointer<UInt8>, _ w: Int, _ h: Int, _ bpr: Int, _ mask: [UInt8]?) -> Bg {
        let cell = max(3, Int((Float(max(w, h)) / 320).rounded()))
        let gw = (w + cell - 1) / cell, gh = (h + cell - 1) / cell
        let n = gw * gh
        var r = [Float](repeating: 0, count: n), g = [Float](repeating: 0, count: n), b = [Float](repeating: 0, count: n)
        var valid = [Bool](repeating: false, count: n)
        let step = max(1, cell / 4)
        let useMask = mask != nil && mask!.count >= w * h
        let empty = [UInt8]()
        (useMask ? mask! : empty).withUnsafeBufferPointer { mk in
            for gy in 0..<gh {
                let ys = gy * cell, ye = min(h, ys + cell)
                for gx in 0..<gw {
                    let xs = gx * cell, xe = min(w, xs + cell)
                    var bestL = -1, br = 0, bgc = 0, bb = 0
                    var y = ys + 1
                    while y < ye - 1 {
                        var x = xs + 1
                        while x < xe - 1 {
                            if useMask && mk[y * w + x] != 0 {
                                x += step
                                continue
                            }
                            var sr = 0, sg = 0, sb = 0
                            for dy in -1...1 {
                                let rowp = px + (y + dy) * bpr
                                for dx in -1...1 {
                                    let p = rowp + (x + dx) * 4
                                    sr += Int(p[0]); sg += Int(p[1]); sb += Int(p[2])
                                }
                            }
                            let l = 299 * sr + 587 * sg + 114 * sb
                            if l > bestL {
                                bestL = l
                                br = sr; bgc = sg; bb = sb
                            }
                            x += step
                        }
                        y += step
                    }
                    let i = gy * gw + gx
                    if bestL >= 0 {
                        r[i] = Float(br) / 9; g[i] = Float(bgc) / 9; b[i] = Float(bb) / 9
                        valid[i] = true
                    }
                }
            }
        }
        var lum = [Float](repeating: 0, count: n)
        var sorted = [Float]()
        sorted.reserveCapacity(n)
        for i in 0..<n {
            lum[i] = valid[i] ? 0.299 * r[i] + 0.587 * g[i] + 0.114 * b[i] : 0
            if valid[i] { sorted.append(lum[i]) }
        }
        let out = Bg()
        out.gw = gw; out.gh = gh; out.cell = Float(cell)
        let nv0 = sorted.count
        if nv0 == 0 {
            // không có mẫu nào (ảnh quá nhỏ hoặc bị che hết): nền trắng
            out.r = [Float](repeating: 255, count: n)
            out.g = out.r
            out.b = out.r
            return out
        }
        sorted.sort()
        let paper = sorted[min(nv0 - 1, Int(Float(nv0) * 0.9))]
        var nValid = 0
        for i in 0..<n {
            if valid[i] && lum[i] < 0.45 * paper {
                valid[i] = false
                lum[i] = 0
            }
            if valid[i] { nValid += 1 }
        }
        if nValid == 0 {
            for i in 0..<n {
                valid[i] = r[i] > 0 || g[i] > 0 || b[i] > 0
                if valid[i] { nValid += 1 }
            }
        }
        fillInvalid(&r, &g, &b, &valid, nValid, gw, gh)
        // phép đóng: lọc cực đại rồi cực tiểu, bán kính 5 ô (cửa sổ ~3,4% cạnh dài)
        let rad = 5
        r = morph(morph(r, gw, gh, rad, true), gw, gh, rad, false)
        g = morph(morph(g, gw, gh, rad, true), gw, gh, rad, false)
        b = morph(morph(b, gw, gh, rad, true), gw, gh, rad, false)
        limitChroma(&r, &g, &b, lum, paper)
        // chỉ làm mượt một lượt: làm mượt nhiều sẽ để lại vệt xám dọc mép bóng
        r = box3(r, gw, gh)
        g = box3(g, gw, gh)
        b = box3(b, gw, gh)
        for i in 0..<n {
            r[i] = max(24, r[i]); g[i] = max(24, g[i]); b[i] = max(24, b[i])
        }
        out.r = r; out.g = g; out.b = b
        return out
    }

    /// Lấp ô không hợp lệ bằng trung bình các ô hợp lệ lân cận (lan dần).
    private static func fillInvalid(_ r: inout [Float], _ g: inout [Float], _ b: inout [Float], _ valid: inout [Bool],
                                    _ nValidIn: Int, _ gw: Int, _ gh: Int) {
        let n = gw * gh
        var nValid = nValidIn
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

    /// Lọc cực đại (isMax) hoặc cực tiểu theo cửa sổ vuông bán kính rad (tách hai chiều).
    private static func morph(_ a: [Float], _ w: Int, _ h: Int, _ rad: Int, _ isMax: Bool) -> [Float] {
        var t = [Float](repeating: 0, count: a.count), o = [Float](repeating: 0, count: a.count)
        for y in 0..<h {
            for x in 0..<w {
                let x0 = max(0, x - rad), x1 = min(w - 1, x + rad)
                var v = a[y * w + x0]
                var k = x0 + 1
                while k <= x1 {
                    let c = a[y * w + k]
                    if isMax ? c > v : c < v { v = c }
                    k += 1
                }
                t[y * w + x] = v
            }
        }
        for y in 0..<h {
            let y0 = max(0, y - rad), y1 = min(h - 1, y + rad)
            for x in 0..<w {
                var v = t[y0 * w + x]
                var k = y0 + 1
                while k <= y1 {
                    let c = t[k * w + x]
                    if isMax ? c > v : c < v { v = c }
                    k += 1
                }
                o[y * w + x] = v
            }
        }
        return o
    }

    /// Giới hạn sắc độ của nền quanh màu giấy chung: mảng màu lớn (ảnh, nền màu) không bị coi là màu giấy
    /// -> tránh đổi màu giả; vẫn sửa được ám màu nhẹ cục bộ. lum: độ sáng ô trước khi nội suy.
    private static func limitChroma(_ r: inout [Float], _ g: inout [Float], _ b: inout [Float], _ lum: [Float], _ paper: Float) {
        let n = r.count
        var pr: Float = 0, pg: Float = 0, pb: Float = 0
        var pc = 0
        for i in 0..<n where lum[i] >= 0.9 * paper {
            pr += r[i]; pg += g[i]; pb += b[i]; pc += 1
        }
        if pc == 0 { return }
        let fc = Float(pc)
        let pl = max(1, 0.299 * pr / fc + 0.587 * pg / fc + 0.114 * pb / fc)
        let gr = max(1e-3, pr / fc / pl), gg = max(1e-3, pg / fc / pl), gb = max(1e-3, pb / fc / pl)
        for i in 0..<n {
            let l = max(1, 0.299 * r[i] + 0.587 * g[i] + 0.114 * b[i])
            let lr = r[i] / l, lg = g[i] / l, lb = b[i] / l
            // lệch ít (<8%): giữ sắc độ cục bộ; lệch nhiều (>15%): dùng màu giấy chung
            let dev = max(abs(lr / gr - 1), max(abs(lg / gg - 1), abs(lb / gb - 1)))
            let wgt = min(1, max(0, (0.15 - dev) / 0.07))
            let cr = gr + (lr - gr) * wgt, cg = gg + (lg - gg) * wgt, cb = gb + (lb - gb) * wgt
            let k = l / max(1e-3, 0.299 * cr + 0.587 * cg + 0.114 * cb)
            r[i] = cr * k
            g[i] = cg * k
            b[i] = cb * k
        }
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
