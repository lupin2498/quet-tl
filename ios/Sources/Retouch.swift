import Foundation

/// Làm đẹp / chỉnh sửa trang (port 1-1 từ Retouch.java; chỉ dùng Foundation nên kiểm thử được trên Linux):
/// phát hiện + gỡ ngón tay giữ giấy, tô lại vùng bằng màu nền giấy, "Xóa thông minh"
/// (bút, hình chữ nhật, thần kỳ, chữ viết tay, văn bản), dọn mép, khử nhiễu, làm nét.
///
/// Điểm ảnh RGBA 8 bit (con trỏ + bytesPerRow); mặt nạ là mảng UInt8 w*h (khác 0 = thuộc vùng).
enum Retouch {
    /// Các kiểu xoá của "Xóa thông minh".
    static let pen = 0     // tô nền đúng theo nét bút
    static let rect = 1    // tô nền cả hình chữ nhật
    static let magic = 2   // như bút + xoá luôn cả vật thể dính liền với nét tô
    static let text = 3    // chỉ xoá nét chữ dưới nét tô, giữ đường kẻ bảng
    static let hand = 4    // xoá nét bút màu (chữ viết tay) trên cả trang

    /// Một thao tác xoá; toạ độ chuẩn hoá 0..1 theo ảnh đã nắn phẳng (chưa xoay).
    struct Op {
        var kind: Int
        /// Bề rộng nét / bề rộng ảnh.
        var width: Float
        /// x0,y0,x1,y1,... (rect: 4 góc).
        var pts: [Float]
    }

    typealias Box = (x0: Int, y0: Int, x1: Int, y1: Int)

    // MARK: mặt nạ

    private static func clampI(_ v: Float, _ lo: Int, _ hi: Int) -> Int {
        if !(v > Float(lo)) { return lo }
        if v >= Float(hi) { return hi }
        return Int(v)
    }

    /// Tô mặt nạ theo nét vẽ: mọi điểm ảnh cách đường gấp khúc pts (toạ độ pixel) không quá radius.
    static func strokeMask(_ mask: inout [UInt8], _ w: Int, _ h: Int, _ pts: [Float], _ radius: Float) {
        if pts.count < 2 || w <= 0 || h <= 0 || mask.count < w * h { return }
        let rad: Float = radius > 0.5 ? radius : 0.5
        if !rad.isFinite { return }
        let r2 = rad * rad
        let n = pts.count / 2
        for i in 0..<n {
            let ax = pts[2 * i], ay = pts[2 * i + 1]
            var bx = ax, by = ay
            if i + 1 < n {
                bx = pts[2 * i + 2]
                by = pts[2 * i + 3]
            } else if n > 1 {
                break
            }
            if !ax.isFinite || !ay.isFinite || !bx.isFinite || !by.isFinite { continue }
            let x0 = clampI((min(ax, bx) - rad).rounded(.down), 0, w - 1)
            let x1 = clampI((max(ax, bx) + rad).rounded(.up), 0, w - 1)
            let y0 = clampI((min(ay, by) - rad).rounded(.down), 0, h - 1)
            let y1 = clampI((max(ay, by) + rad).rounded(.up), 0, h - 1)
            if x0 > x1 || y0 > y1 { continue }
            let dx = bx - ax, dy = by - ay
            let len2 = dx * dx + dy * dy
            for y in y0...y1 {
                let py = Float(y) + 0.5 - ay
                for x in x0...x1 {
                    let pxx = Float(x) + 0.5 - ax
                    var t: Float = 0
                    if len2 > 0 {
                        t = (pxx * dx + py * dy) / len2
                        if t < 0 { t = 0 } else if t > 1 { t = 1 }
                    }
                    let ex = pxx - t * dx, ey = py - t * dy
                    if ex * ex + ey * ey <= r2 { mask[y * w + x] = 1 }
                }
            }
        }
    }

    /// Tô mặt nạ đa giác (toạ độ pixel, quy tắc chẵn-lẻ).
    static func polyMask(_ mask: inout [UInt8], _ w: Int, _ h: Int, _ poly: [Float]) {
        if poly.count < 6 || w <= 0 || h <= 0 || mask.count < w * h { return }
        let n = poly.count / 2
        var minY = Float.greatestFiniteMagnitude, maxY = -Float.greatestFiniteMagnitude
        for i in 0..<n {
            if !poly[2 * i].isFinite || !poly[2 * i + 1].isFinite { return }
            minY = min(minY, poly[2 * i + 1])
            maxY = max(maxY, poly[2 * i + 1])
        }
        let y0 = clampI(minY.rounded(.down), 0, h - 1), y1 = clampI(maxY.rounded(.up), 0, h - 1)
        if y0 > y1 { return }
        var xs = [Float](repeating: 0, count: n)
        for y in y0...y1 {
            let sy = Float(y) + 0.5
            var c = 0
            for i in 0..<n {
                let j = (i + 1) % n
                let ay = poly[2 * i + 1], by = poly[2 * j + 1]
                if (ay <= sy && by > sy) || (by <= sy && ay > sy) {
                    let ax = poly[2 * i], bx = poly[2 * j]
                    xs[c] = ax + (sy - ay) / (by - ay) * (bx - ax)
                    c += 1
                }
            }
            // sắp xếp chèn (ít giao điểm)
            var a = 1
            while a < c {
                let v = xs[a]
                var b = a - 1
                while b >= 0 && xs[b] > v {
                    xs[b + 1] = xs[b]
                    b -= 1
                }
                xs[b + 1] = v
                a += 1
            }
            a = 0
            while a + 1 < c {
                let xa = clampI((xs[a] - 0.5).rounded(.up), 0, w), xb = clampI((xs[a + 1] - 0.5).rounded(.down), -1, w - 1)
                if xa <= xb {
                    for x in xa...xb { mask[y * w + x] = 1 }
                }
                a += 2
            }
        }
    }

    /// Khung bao của mặt nạ, nil nếu rỗng.
    static func bounds(_ mask: [UInt8], _ w: Int, _ h: Int) -> Box? {
        if mask.count < w * h { return nil }
        var x0 = w, y0 = h, x1 = -1, y1 = -1
        mask.withUnsafeBufferPointer { m in
            for y in 0..<h {
                let off = y * w
                for x in 0..<w where m[off + x] != 0 {
                    if x < x0 { x0 = x }
                    if x > x1 { x1 = x }
                    if y < y0 { y0 = y }
                    if y > y1 { y1 = y }
                }
            }
        }
        if x1 < 0 { return nil }
        return (x0, y0, x1, y1)
    }

    /// Nở mặt nạ bán kính r (hình vuông), chỉ xét trong khung bb đã nới r.
    static func dilate(_ mask: inout [UInt8], _ w: Int, _ h: Int, _ r: Int, _ bb: Box?) {
        guard r > 0, let bb = bb else { return }
        let x0 = max(0, bb.x0 - r), y0 = max(0, bb.y0 - r)
        let x1 = min(w - 1, bb.x1 + r), y1 = min(h - 1, bb.y1 + r)
        let rw = x1 - x0 + 1, rh = y1 - y0 + 1
        if rw <= 0 || rh <= 0 { return }
        var t = [UInt8](repeating: 0, count: rw * rh)
        mask.withUnsafeMutableBufferPointer { m in
            t.withUnsafeMutableBufferPointer { tp in
                for y in 0..<rh {
                    let off = (y + y0) * w + x0
                    var last = -(1 << 20)
                    // quét trái -> phải rồi phải -> trái: khoảng cách tới điểm bật gần nhất trên hàng
                    for x in 0..<rw {
                        if m[off + x] != 0 { last = x }
                        if x - last <= r { tp[y * rw + x] = 1 }
                    }
                    last = 1 << 20
                    var x = rw - 1
                    while x >= 0 {
                        if m[off + x] != 0 { last = x }
                        if last - x <= r { tp[y * rw + x] = 1 }
                        x -= 1
                    }
                }
                for x in 0..<rw {
                    var last = -(1 << 20)
                    for y in 0..<rh {
                        if tp[y * rw + x] != 0 { last = y }
                        if y - last <= r { m[(y + y0) * w + x + x0] = 1 }
                    }
                    last = 1 << 20
                    var y = rh - 1
                    while y >= 0 {
                        if tp[y * rw + x] != 0 { last = y }
                        if last - y <= r { m[(y + y0) * w + x + x0] = 1 }
                        y -= 1
                    }
                }
            }
        }
    }

    // MARK: tô nền

    /// Tỷ lệ giữa giấy thường và điểm sáng nhất của ô nền (nền ước lượng hơi sáng hơn mặt giấy).
    private static let paperK: Float = 0.975

    /// Tô lại vùng mặt nạ bằng màu nền giấy ước lượng từ phần còn lại của ảnh (mép vùng được làm mềm).
    static func fill(_ px: UnsafeMutablePointer<UInt8>, _ w: Int, _ h: Int, _ bpr: Int, _ mask: [UInt8]) {
        if w < 4 || h < 4 || mask.count < w * h { return }
        guard let bb = bounds(mask, w, h) else { return }
        let bg = Enhancer.backgroundFine(px, w, h, bpr, mask)
        let f = max(1, Int((Float(max(w, h)) / 700).rounded()))
        let x0 = max(0, bb.x0 - f), y0 = max(0, bb.y0 - f)
        let x1 = min(w - 1, bb.x1 + f), y1 = min(h - 1, bb.y1 + f)
        let rw = x1 - x0 + 1, rh = y1 - y0 + 1
        let a = coverage(mask, w, x0, y0, rw, rh, f)
        var rowR = [Float](repeating: 1, count: w)
        var rowG = [Float](repeating: 1, count: w)
        var rowB = [Float](repeating: 1, count: w)
        for y in y0...y1 {
            bg.row(y, &rowR, &rowG, &rowB)
            let off = y * w
            let rowp = px + y * bpr
            for x in x0...x1 {
                var al = Int(a[(y - y0) * rw + (x - x0)])
                if mask[off + x] != 0 { al = max(al, 200) }
                if al == 0 { continue }
                var fr = Int(rowR[x] * paperK + 0.5)
                var fg = Int(rowG[x] * paperK + 0.5)
                var fb = Int(rowB[x] * paperK + 0.5)
                if fr > 255 { fr = 255 }
                if fg > 255 { fg = 255 }
                if fb > 255 { fb = 255 }
                let p = rowp + x * 4
                let r = (Int(p[0]) * (255 - al) + fr * al + 127) / 255
                let g = (Int(p[1]) * (255 - al) + fg * al + 127) / 255
                let b = (Int(p[2]) * (255 - al) + fb * al + 127) / 255
                p[0] = UInt8(r)
                p[1] = UInt8(g)
                p[2] = UInt8(b)
                p[3] = 255
            }
        }
    }

    /// Độ phủ 0..255 trong khung (x0, y0, rw x rh): làm mờ hộp mặt nạ bán kính f (tổng trượt, hai chiều).
    private static func coverage(_ mask: [UInt8], _ w: Int, _ x0: Int, _ y0: Int, _ rw: Int, _ rh: Int, _ f: Int) -> [UInt8] {
        let win = 2 * f + 1
        let full = win * win
        var t = [UInt16](repeating: 0, count: rw * rh)
        var a = [UInt8](repeating: 0, count: rw * rh)
        for y in 0..<rh {
            let off = (y + y0) * w
            var s = 0
            for x in -f...f {
                let xx = x + x0
                if xx >= 0 && xx < w && mask[off + xx] != 0 { s += 1 }
            }
            for x in 0..<rw {
                t[y * rw + x] = UInt16(s)
                let o = x + x0 - f, i = x + x0 + f + 1
                if o >= 0 && o < w && mask[off + o] != 0 { s -= 1 }
                if i >= 0 && i < w && mask[off + i] != 0 { s += 1 }
            }
        }
        // hàng ngoài khung không có điểm mặt nạ nào (khung đã nới f) nên đóng góp 0
        for x in 0..<rw {
            var s = 0
            var y = 0
            while y <= f && y < rh {
                s += Int(t[y * rw + x])
                y += 1
            }
            for yy in 0..<rh {
                a[yy * rw + x] = UInt8(min(255, s * 255 / full))
                let o = yy - f, i = yy + f + 1
                if o >= 0 { s -= Int(t[o * rw + x]) }
                if i < rh { s += Int(t[i * rw + x]) }
            }
        }
        return a
    }

    // MARK: ngón tay

    /// Tìm ngón tay giữ giấy: mảng màu da đặc, bám vào mép ảnh và thò vào trong trang.
    /// Trả về mặt nạ w*h (đã nới rộng để phủ viền/bóng sát ngón tay) hoặc nil nếu không thấy.
    static func fingerMask(_ px: UnsafeMutablePointer<UInt8>, _ w: Int, _ h: Int, _ bpr: Int) -> [UInt8]? {
        if w < 48 || h < 48 { return nil }
        let k = max(1, Int((Float(max(w, h)) / 420).rounded()))
        let sw = w / k, sh = h / k
        if sw < 24 || sh < 24 { return nil }
        let n = sw * sh
        var sr = [Int](repeating: 0, count: n), sg = [Int](repeating: 0, count: n), sb = [Int](repeating: 0, count: n)
        let kk = k * k
        for y in 0..<sh {
            for x in 0..<sw {
                var r = 0, g = 0, b = 0
                for dy in 0..<k {
                    var p = px + (y * k + dy) * bpr + x * k * 4
                    for _ in 0..<k {
                        r += Int(p[0]); g += Int(p[1]); b += Int(p[2])
                        p += 4
                    }
                }
                let i = y * sw + x
                sr[i] = r / kk; sg[i] = g / kk; sb[i] = b / kk
            }
        }
        // màu giấy: trung bình các điểm sáng (từ phân vị 75% trở lên)
        var hist = [Int](repeating: 0, count: 256)
        for i in 0..<n { hist[(299 * sr[i] + 587 * sg[i] + 114 * sb[i]) / 1000] += 1 }
        var thr = 0, acc = 0
        let need = n * 3 / 4
        for l in 0..<256 {
            acc += hist[l]
            if acc >= need {
                thr = l
                break
            }
        }
        var pr = 0, pg = 0, pb = 0, pc = 0
        for i in 0..<n where (299 * sr[i] + 587 * sg[i] + 114 * sb[i]) / 1000 >= thr {
            pr += sr[i]; pg += sg[i]; pb += sb[i]; pc += 1
        }
        if pc == 0 { return nil }
        let paperR = pr / pc, paperG = pg / pc, paperB = pb / pc
        if paperR < 60 || paperG < 60 || paperB < 60 { return nil }
        // phân loại màu da sau khi cân bằng trắng theo màu giấy (giấy = 1024)
        var s = [UInt8](repeating: 0, count: n)
        var lumN = [Int](repeating: 0, count: n)
        for i in 0..<n {
            let nr = sr[i] * 1024 / paperR, ng = sg[i] * 1024 / paperG, nb = sb[i] * 1024 / paperB
            let lum = (299 * nr + 587 * ng + 114 * nb) / 1000
            lumN[i] = lum
            if lum < 200 || lum > 1000 { continue }
            if nr * 100 < ng * 112 || nr * 100 > ng * 260 { continue }
            if ng * 100 < nb * 96 || ng * 100 > nb * 200 { continue }
            if nr - nb < 150 { continue }
            s[i] = 1
        }
        // lấp lỗ nhỏ (móng tay, vệt bóng sáng): nở 2 rồi co 2
        closeSmall(&s, sw, sh, 2)
        // thành phần liên thông 4 hướng
        var label = [Int](repeating: 0, count: n)
        var queue = [Int](repeating: 0, count: n)
        var keep = [UInt8](repeating: 0, count: n)
        var any = false
        let minDim = min(sw, sh)
        let minArea = max(40, n * 12 / 10000)
        for start in 0..<n {
            if s[start] == 0 || label[start] != 0 { continue }
            var head = 0, tail = 0
            queue[tail] = start
            tail += 1
            label[start] = 1
            var minX = sw, maxX = -1, minY = sh, maxY = -1
            while head < tail {
                let i = queue[head]
                head += 1
                let x = i % sw, y = i / sw
                if x < minX { minX = x }
                if x > maxX { maxX = x }
                if y < minY { minY = y }
                if y > maxY { maxY = y }
                if x > 0 && s[i - 1] != 0 && label[i - 1] == 0 { label[i - 1] = 1; queue[tail] = i - 1; tail += 1 }
                if x < sw - 1 && s[i + 1] != 0 && label[i + 1] == 0 { label[i + 1] = 1; queue[tail] = i + 1; tail += 1 }
                if y > 0 && s[i - sw] != 0 && label[i - sw] == 0 { label[i - sw] = 1; queue[tail] = i - sw; tail += 1 }
                if y < sh - 1 && s[i + sw] != 0 && label[i + sw] == 0 { label[i + sw] = 1; queue[tail] = i + sw; tail += 1 }
            }
            let area = tail
            let tl = minX <= 1, tr = maxX >= sw - 2, tt = minY <= 1, tb = maxY >= sh - 2
            if !(tl || tr || tt || tb) { continue }
            // chạm hai mép đối diện: dải dài (mép bàn do vùng cắt rộng), không phải ngón tay
            if (tl && tr) || (tt && tb) { continue }
            if area < minArea || area > n * 15 / 100 { continue }
            let bw = maxX - minX + 1, bh = maxY - minY + 1
            if area * 100 < bw * bh * 38 { continue }
            var ok = false
            if (tl || tr) && bw * 100 >= minDim * 3 && bh * 100 <= sh * 45 { ok = true }
            if (tt || tb) && bh * 100 >= minDim * 3 && bw * 100 <= sw * 45 { ok = true }
            if !ok { continue }
            for q in 0..<tail { keep[queue[q]] = 1 }
            any = true
        }
        if !any { return nil }
        // lan sang phần tối dính liền (mặt khuất của ngón tay, bóng sát ngón tay) trong phạm vi ngắn
        let grow = max(3, Int((Float(minDim) * 0.03).rounded()))
        var head = 0, tail = 0
        for i in 0..<n {
            label[i] = 0
            if keep[i] != 0 {
                label[i] = 1
                queue[tail] = i
                tail += 1
            }
        }
        while head < tail {
            let i = queue[head]
            head += 1
            let d = label[i]
            if d > grow { continue }
            let x = i % sw, y = i / sw
            for dy in -1...1 {
                let yy = y + dy
                if yy < 0 || yy >= sh { continue }
                for dx in -1...1 {
                    let xx = x + dx
                    if xx < 0 || xx >= sw { continue }
                    let j = yy * sw + xx
                    if label[j] != 0 || lumN[j] >= 760 { continue }
                    label[j] = d + 1
                    keep[j] = 1
                    queue[tail] = j
                    tail += 1
                }
            }
        }
        let sbb = bounds(keep, sw, sh)
        dilate(&keep, sw, sh, max(2, Int((Float(minDim) * 0.016).rounded())), sbb)
        var mask = [UInt8](repeating: 0, count: w * h)
        mask.withUnsafeMutableBufferPointer { m in
            for y in 0..<h {
                let sy = min(sh - 1, y / k)
                for x in 0..<w where keep[sy * sw + min(sw - 1, x / k)] != 0 {
                    m[y * w + x] = 1
                }
            }
        }
        return mask
    }

    /// Phép đóng nhỏ trên mặt nạ (nở r rồi co r).
    private static func closeSmall(_ m: inout [UInt8], _ w: Int, _ h: Int, _ r: Int) {
        guard let bb = bounds(m, w, h) else { return }
        dilate(&m, w, h, r, bb)
        // co = nở phần bù
        var inv = [UInt8](repeating: 0, count: w * h)
        for i in 0..<(w * h) { inv[i] = m[i] != 0 ? 0 : 1 }
        guard let ib = bounds(inv, w, h) else { return }
        dilate(&inv, w, h, r, ib)
        for i in 0..<(w * h) { m[i] = inv[i] != 0 ? 0 : 1 }
    }

    /// Gỡ ngón tay giữ giấy (nếu có). Trả về true nếu đã gỡ.
    @discardableResult
    static func removeFingers(_ px: UnsafeMutablePointer<UInt8>, _ w: Int, _ h: Int, _ bpr: Int) -> Bool {
        guard let m = fingerMask(px, w, h, bpr) else { return false }
        fill(px, w, h, bpr, m)
        return true
    }

    static func hasFinger(_ px: UnsafeMutablePointer<UInt8>, _ w: Int, _ h: Int, _ bpr: Int) -> Bool {
        return fingerMask(px, w, h, bpr) != nil
    }

    // MARK: phân loại mực

    private static let cPaper: UInt8 = 0, cDark: UInt8 = 1, cBlue: UInt8 = 2, cRed: UInt8 = 3, cGreen: UInt8 = 4, cYellow: UInt8 = 5

    /// Phân loại từng điểm ảnh trong khung [x0..x1]x[y0..y1] so với nền giấy: giấy / mực tối / mực màu.
    private static func classify(_ px: UnsafeMutablePointer<UInt8>, _ w: Int, _ h: Int, _ bpr: Int, _ bg: Enhancer.Bg,
                                 _ x0: Int, _ y0: Int, _ x1: Int, _ y1: Int) -> [UInt8] {
        let rw = x1 - x0 + 1, rh = y1 - y0 + 1
        var cls = [UInt8](repeating: 0, count: rw * rh)
        var rowR = [Float](repeating: 1, count: w)
        var rowG = [Float](repeating: 1, count: w)
        var rowB = [Float](repeating: 1, count: w)
        cls.withUnsafeMutableBufferPointer { cp in
            for y in y0...y1 {
                bg.row(y, &rowR, &rowG, &rowB)
                let rowp = px + y * bpr
                for x in x0...x1 {
                    let p = rowp + x * 4
                    let r = Float(p[0]) / rowR[x]
                    let g = Float(p[1]) / rowG[x]
                    let b = Float(p[2]) / rowB[x]
                    let mx = r > g ? (r > b ? r : b) : (g > b ? g : b)
                    let mn = r < g ? (r < b ? r : b) : (g < b ? g : b)
                    let ch = mx - mn
                    var k = cPaper
                    if ch > 0.16 && ch > 0.22 * mx {
                        if b >= r && b >= g {
                            k = cBlue
                        } else if b <= r && b <= g && g > 0.8 * r && r > 0.8 * g {
                            k = cYellow
                        } else if r >= g {
                            k = cRed
                        } else {
                            k = cGreen
                        }
                    } else if 0.299 * r + 0.587 * g + 0.114 * b < 0.78 {
                        k = cDark
                    }
                    cp[(y - y0) * rw + (x - x0)] = k
                }
            }
        }
        return cls
    }

    // MARK: xoá thông minh

    /// Áp một thao tác xoá lên ảnh (chưa lọc). region: mặt nạ vùng người dùng tô (hand: có thể nil = cả trang).
    /// Trả về true nếu ảnh thay đổi.
    @discardableResult
    static func erase(_ px: UnsafeMutablePointer<UInt8>, _ w: Int, _ h: Int, _ bpr: Int, _ kind: Int, _ region: [UInt8]?) -> Bool {
        if w < 4 || h < 4 { return false }
        if let r = region, r.count < w * h { return false }
        switch kind {
        case hand:
            return eraseHandwriting(px, w, h, bpr, region)
        case magic:
            return eraseMagic(px, w, h, bpr, region)
        case text:
            return eraseText(px, w, h, bpr, region)
        default:
            guard let r = region, bounds(r, w, h) != nil else { return false }
            fill(px, w, h, bpr, r)
            return true
        }
    }

    /// Áp lần lượt danh sách thao tác xoá (các thao tác Bút/Hình chữ nhật liền nhau được gộp lại).
    static func applyErase(_ px: UnsafeMutablePointer<UInt8>, _ w: Int, _ h: Int, _ bpr: Int, _ ops: [Op]) {
        if w < 4 || h < 4 { return }
        var pending: [UInt8]? = nil
        for op in ops {
            if op.kind == hand {
                if let p = pending {
                    fill(px, w, h, bpr, p)
                    pending = nil
                }
                eraseHandwriting(px, w, h, bpr, nil)
                continue
            }
            let simple = op.kind == pen || op.kind == rect
            var m: [UInt8]
            if simple, let p = pending {
                m = p
                pending = nil
            } else {
                m = [UInt8](repeating: 0, count: w * h)
            }
            opMask(&m, w, h, op)
            if simple {
                pending = m
            } else {
                if let p = pending {
                    fill(px, w, h, bpr, p)
                    pending = nil
                }
                erase(px, w, h, bpr, op.kind, m)
            }
        }
        if let p = pending { fill(px, w, h, bpr, p) }
    }

    /// Dựng mặt nạ vùng tô của một thao tác (toạ độ chuẩn hoá -> pixel).
    static func opMask(_ mask: inout [UInt8], _ w: Int, _ h: Int, _ op: Op) {
        var p = [Float](repeating: 0, count: op.pts.count)
        var i = 0
        while i + 1 < p.count {
            p[i] = op.pts[i] * Float(w)
            p[i + 1] = op.pts[i + 1] * Float(h)
            i += 2
        }
        if op.kind == rect {
            polyMask(&mask, w, h, p)
        } else {
            strokeMask(&mask, w, h, p, op.width * Float(w) / 2)
        }
    }

    /// Thần kỳ: tô nền vùng đã tô + xoá cả vật thể (mực) dính liền với nét tô trong phạm vi lân cận.
    static func eraseMagic(_ px: UnsafeMutablePointer<UInt8>, _ w: Int, _ h: Int, _ bpr: Int, _ region: [UInt8]?) -> Bool {
        guard let region = region, let bb = bounds(region, w, h) else { return false }
        let minDim = min(w, h)
        let margin = max(4, Int((Float(minDim) * 0.2).rounded()))
        let x0 = max(0, bb.x0 - margin), y0 = max(0, bb.y0 - margin)
        let x1 = min(w - 1, bb.x1 + margin), y1 = min(h - 1, bb.y1 + margin)
        let rw = x1 - x0 + 1, rh = y1 - y0 + 1
        let bg = Enhancer.backgroundFine(px, w, h, bpr, nil)
        let cls = classify(px, w, h, bpr, bg, x0, y0, x1, y1)
        // hạt giống: mực nằm dưới nét tô
        var seeds = 0, colored = 0
        for y in 0..<rh {
            for x in 0..<rw {
                if region[(y + y0) * w + x + x0] == 0 { continue }
                let k = cls[y * rw + x]
                if k == cPaper { continue }
                seeds += 1
                if k != cDark { colored += 1 }
            }
        }
        var out = region
        if seeds > 0 {
            let byColor = colored * 2 >= seeds
            // mực tối (chữ in, đường kẻ) chỉ lan gần nét tô để không ăn theo đường kẻ bảng
            let reach = byColor ? margin : max(2, Int((Float(minDim) * 0.012).rounded()))
            var dist = [Int32](repeating: 0, count: rw * rh)
            var queue = [Int32](repeating: 0, count: rw * rh)
            var head = 0, tail = 0
            for y in 0..<rh {
                for x in 0..<rw {
                    let i = y * rw + x
                    if region[(y + y0) * w + x + x0] == 0 { continue }
                    let k = cls[i]
                    if k == cPaper || (byColor ? k == cDark : k != cDark) { continue }
                    dist[i] = 1
                    queue[tail] = Int32(i)
                    tail += 1
                }
            }
            while head < tail {
                let i = Int(queue[head])
                head += 1
                let d = Int(dist[i])
                out[(i / rw + y0) * w + (i % rw) + x0] = 1
                if d > reach { continue }
                let x = i % rw, y = i / rw
                for dy in -1...1 {
                    let yy = y + dy
                    if yy < 0 || yy >= rh { continue }
                    for dx in -1...1 {
                        let xx = x + dx
                        if xx < 0 || xx >= rw { continue }
                        let j = yy * rw + xx
                        if dist[j] != 0 { continue }
                        let k = cls[j]
                        if k == cPaper || (byColor ? k == cDark : k != cDark) { continue }
                        dist[j] = Int32(d + 1)
                        queue[tail] = Int32(j)
                        tail += 1
                    }
                }
            }
            let ob = bounds(out, w, h)
            dilate(&out, w, h, max(1, Int((Float(max(w, h)) / 900).rounded())), ob)
        }
        fill(px, w, h, bpr, out)
        return true
    }

    /// Xoá văn bản: chỉ xoá nét mực dưới nét tô; đường kẻ dài (kẻ bảng, gạch chân dài) được giữ lại.
    static func eraseText(_ px: UnsafeMutablePointer<UInt8>, _ w: Int, _ h: Int, _ bpr: Int, _ region: [UInt8]?) -> Bool {
        guard let region = region, let bb = bounds(region, w, h) else { return false }
        let minDim = min(w, h)
        let L = max(24, Int((Float(minDim) * 0.045).rounded()))
        let x0 = max(0, bb.x0 - L), y0 = max(0, bb.y0 - L)
        let x1 = min(w - 1, bb.x1 + L), y1 = min(h - 1, bb.y1 + L)
        let rw = x1 - x0 + 1, rh = y1 - y0 + 1
        let bg = Enhancer.backgroundFine(px, w, h, bpr, nil)
        let cls = classify(px, w, h, bpr, bg, x0, y0, x1, y1)
        var line = [UInt8](repeating: 0, count: rw * rh)
        // đường ngang: đoạn mực liên tục dài >= L (cho phép lệch 1 hàng vì trang hơi nghiêng)
        for y in 0..<rh {
            var run = 0
            for x in 0...rw {
                var ink = false
                if x < rw {
                    ink = cls[y * rw + x] != cPaper
                        || (y > 0 && cls[(y - 1) * rw + x] != cPaper)
                        || (y < rh - 1 && cls[(y + 1) * rw + x] != cPaper)
                }
                if ink {
                    run += 1
                } else {
                    if run >= L {
                        for q in (x - run)..<x { line[y * rw + q] = 1 }
                    }
                    run = 0
                }
            }
        }
        for x in 0..<rw {
            var run = 0
            for y in 0...rh {
                var ink = false
                if y < rh {
                    ink = cls[y * rw + x] != cPaper
                        || (x > 0 && cls[y * rw + x - 1] != cPaper)
                        || (x < rw - 1 && cls[y * rw + x + 1] != cPaper)
                }
                if ink {
                    run += 1
                } else {
                    if run >= L {
                        for q in (y - run)..<y { line[q * rw + x] = 1 }
                    }
                    run = 0
                }
            }
        }
        var out = [UInt8](repeating: 0, count: w * h)
        var any = false
        for y in 0..<rh {
            for x in 0..<rw {
                let i = y * rw + x
                if cls[i] == cPaper || line[i] != 0 { continue }
                let gi = (y + y0) * w + x + x0
                if region[gi] == 0 { continue }
                out[gi] = 1
                any = true
            }
        }
        if !any { return false }
        let ob = bounds(out, w, h)
        let d = max(1, Int((Float(max(w, h)) / 900).rounded()))
        dilate(&out, w, h, d, ob)
        // không lấn vào đường kẻ và không ra ngoài vùng tô
        for y in 0..<rh {
            for x in 0..<rw {
                let gi = (y + y0) * w + x + x0
                if out[gi] != 0 && (line[y * rw + x] != 0 || region[gi] == 0) { out[gi] = 0 }
            }
        }
        fill(px, w, h, bpr, out)
        return true
    }

    /// Xoá chữ viết tay bằng bút màu (xanh, tím, lục) và các vết mực đỏ nhỏ; mảng đỏ lớn (con dấu) được giữ lại.
    /// region != nil: chỉ trong vùng đó. Trả về true nếu có gì bị xoá.
    @discardableResult
    static func eraseHandwriting(_ px: UnsafeMutablePointer<UInt8>, _ w: Int, _ h: Int, _ bpr: Int, _ region: [UInt8]?) -> Bool {
        if w < 8 || h < 8 { return false }
        if let r = region, r.count < w * h { return false }
        let bg = Enhancer.backgroundFine(px, w, h, bpr, nil)
        let cls = classify(px, w, h, bpr, bg, 0, 0, w - 1, h - 1)
        var out = [UInt8](repeating: 0, count: w * h)
        var any = false
        // lưới thô để đo kích thước từng cụm mực đỏ
        let gs = max(2, Int((Float(max(w, h)) / 320).rounded()))
        let gw = (w + gs - 1) / gs, gh = (h + gs - 1) / gs
        var red = [UInt8](repeating: 0, count: gw * gh)
        var anyRed = false
        let hasRegion = region != nil
        let empty = [UInt8]()
        (region ?? empty).withUnsafeBufferPointer { rg in
            cls.withUnsafeBufferPointer { cp in
                out.withUnsafeMutableBufferPointer { op in
                    for y in 0..<h {
                        let off = y * w
                        for x in 0..<w {
                            if hasRegion && rg[off + x] == 0 { continue }
                            let k = cp[off + x]
                            if k == cBlue || k == cGreen {
                                // viền màu 1 điểm ảnh quanh chữ in đen (sắc sai, nén JPEG) không phải nét bút
                                if sameAround(cp, w, h, x, y, k) < 5 { continue }
                                op[off + x] = 1
                                any = true
                            } else if k == cRed {
                                red[(y / gs) * gw + x / gs] = 1
                                anyRed = true
                            }
                        }
                    }
                }
            }
        }
        if anyRed {
            let limit = max(2, Int((Float(min(w, h)) * 0.06 / Float(gs)).rounded()))
            var queue = [Int](repeating: 0, count: gw * gh)
            var seen = [UInt8](repeating: 0, count: gw * gh)
            var small = [UInt8](repeating: 0, count: gw * gh)
            var anySmall = false
            for start in 0..<(gw * gh) {
                if red[start] == 0 || seen[start] != 0 { continue }
                var head = 0, tail = 0
                queue[tail] = start
                tail += 1
                seen[start] = 1
                var minX = gw, maxX = -1, minY = gh, maxY = -1
                while head < tail {
                    let i = queue[head]
                    head += 1
                    let x = i % gw, y = i / gw
                    if x < minX { minX = x }
                    if x > maxX { maxX = x }
                    if y < minY { minY = y }
                    if y > maxY { maxY = y }
                    for dy in -1...1 {
                        let yy = y + dy
                        if yy < 0 || yy >= gh { continue }
                        for dx in -1...1 {
                            let xx = x + dx
                            if xx < 0 || xx >= gw { continue }
                            let j = yy * gw + xx
                            if red[j] != 0 && seen[j] == 0 {
                                seen[j] = 1
                                queue[tail] = j
                                tail += 1
                            }
                        }
                    }
                }
                if maxX - minX + 1 <= limit && maxY - minY + 1 <= limit {
                    for q in 0..<tail { small[queue[q]] = 1 }
                    anySmall = true
                }
            }
            if anySmall {
                for y in 0..<h {
                    let off = y * w
                    for x in 0..<w {
                        if cls[off + x] != cRed || small[(y / gs) * gw + x / gs] == 0 { continue }
                        if let r = region, r[off + x] == 0 { continue }
                        out[off + x] = 1
                        any = true
                    }
                }
            }
        }
        if !any { return false }
        let ob = bounds(out, w, h)
        dilate(&out, w, h, max(1, Int((Float(max(w, h)) / 450).rounded())), ob)
        fill(px, w, h, bpr, out)
        return true
    }

    /// Số điểm cùng loại mực trong ô 3x3 quanh (x,y), kể cả chính nó.
    @inline(__always)
    private static func sameAround(_ cls: UnsafeBufferPointer<UInt8>, _ w: Int, _ h: Int, _ x: Int, _ y: Int, _ k: UInt8) -> Int {
        var c = 0
        for dy in -1...1 {
            let yy = y + dy
            if yy < 0 || yy >= h { continue }
            for dx in -1...1 {
                let xx = x + dx
                if xx >= 0 && xx < w && cls[yy * w + xx] == k { c += 1 }
            }
        }
        return c
    }

    // MARK: dọn mép, làm nét, khử nhiễu

    @inline(__always)
    private static func dark(_ px: UnsafeMutablePointer<UInt8>, _ bpr: Int, _ x: Int, _ y: Int) -> Bool {
        let p = px + y * bpr + x * 4
        return 299 * Int(p[0]) + 587 * Int(p[1]) + 114 * Int(p[2]) < 185000
    }

    /// Dọn mép sau khi lọc: mảng tối bám vào mép ảnh và nằm gọn trong dải sát mép (mép bàn, bóng gáy giấy
    /// do vùng cắt hơi rộng) được tô trắng. Nội dung chạy sâu vào trong trang (đường kẻ bảng...) được giữ nguyên.
    static func cleanEdges(_ px: UnsafeMutablePointer<UInt8>, _ w: Int, _ h: Int, _ bpr: Int) {
        if w < 16 || h < 16 { return }
        let band = max(3, Int((Float(min(w, h)) * 0.025).rounded()))
        if band * 2 >= min(w, h) { return }
        // chỉ xét điểm trong dải sát mép; seen: 0 chưa xét, 1 đã vào hàng đợi
        var seen = [UInt8](repeating: 0, count: w * h)
        let cap = 2 * band * (w + h)
        var queue = [Int32](repeating: 0, count: cap)
        seen.withUnsafeMutableBufferPointer { sp in
            queue.withUnsafeMutableBufferPointer { qp in
                for pass in 0..<4 {
                    let count = pass < 2 ? w : h
                    for t in 0..<count {
                        let sx = pass == 0 ? t : (pass == 1 ? t : (pass == 2 ? 0 : w - 1))
                        let sy = pass == 0 ? 0 : (pass == 1 ? h - 1 : t)
                        let start = sy * w + sx
                        if sp[start] != 0 || !dark(px, bpr, sx, sy) { continue }
                        var head = 0, tail = 0
                        qp[tail] = Int32(start)
                        tail += 1
                        sp[start] = 1
                        var deep = false
                        while head < tail {
                            let i = Int(qp[head])
                            head += 1
                            let x = i % w, y = i / w
                            for dy in -1...1 {
                                let yy = y + dy
                                if yy < 0 || yy >= h { continue }
                                for dx in -1...1 {
                                    let xx = x + dx
                                    if xx < 0 || xx >= w { continue }
                                    let j = yy * w + xx
                                    if sp[j] != 0 || !dark(px, bpr, xx, yy) { continue }
                                    let depth = min(min(xx, w - 1 - xx), min(yy, h - 1 - yy))
                                    if depth >= band {
                                        deep = true
                                        continue
                                    }
                                    sp[j] = 1
                                    if tail < cap {
                                        qp[tail] = Int32(j)
                                        tail += 1
                                    } else {
                                        deep = true
                                    }
                                }
                            }
                        }
                        if !deep {
                            for q in 0..<tail {
                                let i = Int(qp[q])
                                let p = px + (i / w) * bpr + (i % w) * 4
                                p[0] = 255; p[1] = 255; p[2] = 255; p[3] = 255
                            }
                        }
                    }
                }
            }
        }
    }

    /// Làm nét (unsharp mask 3x3), amount tính theo 1/256. Tại chỗ, dùng 3 hàng đệm.
    static func sharpen(_ px: UnsafeMutablePointer<UInt8>, _ w: Int, _ h: Int, _ bpr: Int, _ amount: Int) {
        if w < 3 || h < 3 { return }
        let rowBytes = w * 4
        let buf = UnsafeMutablePointer<UInt8>.allocate(capacity: rowBytes * 3)
        defer { buf.deallocate() }
        var prev = buf, cur = buf + rowBytes, next = buf + 2 * rowBytes
        memcpy(cur, px, rowBytes)
        memcpy(prev, px, rowBytes)
        for y in 0..<h {
            if y + 1 < h { memcpy(next, px + (y + 1) * bpr, rowBytes) } else { memcpy(next, cur, rowBytes) }
            let rowp = px + y * bpr
            for x in 0..<w {
                let xl = (x > 0 ? x - 1 : 0) * 4, xr = (x < w - 1 ? x + 1 : w - 1) * 4, xc = x * 4
                for ch in 0..<3 {
                    let s = Int(prev[xl + ch]) + Int(prev[xc + ch]) + Int(prev[xr + ch])
                        + Int(cur[xl + ch]) + Int(cur[xc + ch]) + Int(cur[xr + ch])
                        + Int(next[xl + ch]) + Int(next[xc + ch]) + Int(next[xr + ch])
                    let v = Int(cur[xc + ch])
                    // v + amount * (v - s/9), làm tròn; chia nguyên nên xử lý dấu thống nhất
                    let diff = 9 * v - s
                    let add = diff * amount
                    let q = add >= 0 ? (add + 1152) / 2304 : -((-add + 1152) / 2304)
                    var o = v + q
                    if o < 0 { o = 0 } else if o > 255 { o = 255 }
                    rowp[xc + ch] = UInt8(o)
                }
                rowp[xc + 3] = 255
            }
            let t = prev
            prev = cur
            cur = next
            next = t
        }
    }

    /// Khử nhiễu giữ mép: trung bình các điểm lân cận 3x3 có độ sáng gần điểm giữa.
    static func denoise(_ px: UnsafeMutablePointer<UInt8>, _ w: Int, _ h: Int, _ bpr: Int) {
        if w < 3 || h < 3 { return }
        let rowBytes = w * 4
        let buf = UnsafeMutablePointer<UInt8>.allocate(capacity: rowBytes * 3)
        let lbuf = UnsafeMutablePointer<Int32>.allocate(capacity: w * 3)
        defer {
            buf.deallocate()
            lbuf.deallocate()
        }
        var prev = buf, cur = buf + rowBytes, next = buf + 2 * rowBytes
        var lp = lbuf, lc = lbuf + w, ln = lbuf + 2 * w
        memcpy(cur, px, rowBytes)
        memcpy(prev, px, rowBytes)
        lumRow(cur, lc, w)
        lumRow(prev, lp, w)
        for y in 0..<h {
            if y + 1 < h { memcpy(next, px + (y + 1) * bpr, rowBytes) } else { memcpy(next, cur, rowBytes) }
            lumRow(next, ln, w)
            let rowp = px + y * bpr
            for x in 0..<w {
                let xl = x > 0 ? x - 1 : 0, xr = x < w - 1 ? x + 1 : w - 1
                let c = cur + x * 4
                let l0 = lc[x]
                let lo = l0 - 20000, hi = l0 + 20000
                var sr = Int32(c[0]), sg = Int32(c[1]), sb = Int32(c[2]), cnt: Int32 = 1
                var l = lp[xl]
                if l >= lo && l <= hi { let v = prev + xl * 4; sr += Int32(v[0]); sg += Int32(v[1]); sb += Int32(v[2]); cnt += 1 }
                l = lp[x]
                if l >= lo && l <= hi { let v = prev + x * 4; sr += Int32(v[0]); sg += Int32(v[1]); sb += Int32(v[2]); cnt += 1 }
                l = lp[xr]
                if l >= lo && l <= hi { let v = prev + xr * 4; sr += Int32(v[0]); sg += Int32(v[1]); sb += Int32(v[2]); cnt += 1 }
                l = lc[xl]
                if l >= lo && l <= hi { let v = cur + xl * 4; sr += Int32(v[0]); sg += Int32(v[1]); sb += Int32(v[2]); cnt += 1 }
                l = lc[xr]
                if l >= lo && l <= hi { let v = cur + xr * 4; sr += Int32(v[0]); sg += Int32(v[1]); sb += Int32(v[2]); cnt += 1 }
                l = ln[xl]
                if l >= lo && l <= hi { let v = next + xl * 4; sr += Int32(v[0]); sg += Int32(v[1]); sb += Int32(v[2]); cnt += 1 }
                l = ln[x]
                if l >= lo && l <= hi { let v = next + x * 4; sr += Int32(v[0]); sg += Int32(v[1]); sb += Int32(v[2]); cnt += 1 }
                l = ln[xr]
                if l >= lo && l <= hi { let v = next + xr * 4; sr += Int32(v[0]); sg += Int32(v[1]); sb += Int32(v[2]); cnt += 1 }
                let o = rowp + x * 4
                o[0] = UInt8((sr + cnt / 2) / cnt)
                o[1] = UInt8((sg + cnt / 2) / cnt)
                o[2] = UInt8((sb + cnt / 2) / cnt)
                o[3] = 255
            }
            let t = prev
            prev = cur
            cur = next
            next = t
            let tl = lp
            lp = lc
            lc = ln
            ln = tl
        }
    }

    @inline(__always)
    private static func lumRow(_ row: UnsafeMutablePointer<UInt8>, _ out: UnsafeMutablePointer<Int32>, _ w: Int) {
        for x in 0..<w {
            let p = row + x * 4
            out[x] = 299 * Int32(p[0]) + 587 * Int32(p[1]) + 114 * Int32(p[2])
        }
    }

    // MARK: đổi vùng cắt

    /// Chuyển toạ độ chuẩn hoá của các chỉnh sửa từ vùng cắt cũ sang vùng cắt mới (cùng hệ toạ độ ảnh gốc),
    /// để nét xoá / đánh dấu vẫn nằm đúng chỗ trên trang sau khi cắt lại. Trả về pts nếu không đổi được.
    static func remap(_ pts: [Float], _ oldQuad: [Float], _ newQuad: [Float]) -> [Float] {
        if oldQuad.count < 8 || newQuad.count < 8 { return pts }
        let unit: [Float] = [0, 0, 1, 0, 1, 1, 0, 1]
        guard let a = Geom.homography(unit, oldQuad), let b = Geom.homography(newQuad, unit) else { return pts }
        var out = [Float](repeating: 0, count: pts.count)
        var i = 0
        while i + 1 < pts.count {
            let u = Double(pts[i]), v = Double(pts[i + 1])
            let d1 = a[6] * u + a[7] * v + a[8]
            let sx = (a[0] * u + a[1] * v + a[2]) / d1, sy = (a[3] * u + a[4] * v + a[5]) / d1
            let d2 = b[6] * sx + b[7] * sy + b[8]
            let nu = (b[0] * sx + b[1] * sy + b[2]) / d2, nv = (b[3] * sx + b[4] * sy + b[5]) / d2
            if !nu.isFinite || !nv.isFinite { return pts }
            out[i] = Float(nu)
            out[i + 1] = Float(nv)
            i += 2
        }
        if pts.count & 1 == 1 { out[pts.count - 1] = pts[pts.count - 1] }
        return out
    }

    /// Hệ số đổi bề rộng chuẩn hoá khi đổi vùng cắt (tỷ lệ bề ngang trung bình cũ / mới).
    static func remapScale(_ oldQuad: [Float], _ newQuad: [Float]) -> Float {
        if oldQuad.count < 8 || newQuad.count < 8 { return 1 }
        func d(_ q: [Float], _ i: Int, _ j: Int) -> Double {
            return hypot(Double(q[2 * i] - q[2 * j]), Double(q[2 * i + 1] - q[2 * j + 1]))
        }
        let wo = (d(oldQuad, 0, 1) + d(oldQuad, 3, 2)) / 2
        let wn = (d(newQuad, 0, 1) + d(newQuad, 3, 2)) / 2
        if !(wo > 1e-6) || !(wn > 1e-6) { return 1 }
        let k = wo / wn
        if !k.isFinite { return 1 }
        return Float(max(0.05, min(20.0, k)))
    }
}
