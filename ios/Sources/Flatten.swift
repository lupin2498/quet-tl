import Foundation

/// Bộ lọc "Làm phẳng" (port 1-1 từ Flatten.java; chỉ dùng Foundation nên kiểm thử được trên Linux) cho trang giấy
/// mấp mô / gấp nếp / nhàu:
/// 1. gỡ ngón tay giữ giấy (Retouch.removeFingers);
/// 2. làm sạch: chỉ giữ nét mực (chữ, bảng biểu, chữ ký, con dấu) — bóng mờ nếp gấp, mặt giấy nhàu, vết vân tay,
///    nền giấy được đưa về trắng; đường kẻ bảng (nhận dạng theo lưới) được giữ và làm rõ kể cả khi mảnh, nhạt;
/// 3. làm thẳng: dò dòng chữ và đường kẻ bảng bị uốn lượn, khớp một trường dịch chuyển trơn (bình phương tối thiểu
///    trên lưới, năng lượng uốn nhỏ) rồi nắn lại ảnh để dòng chữ / đường kẻ thẳng. Trang chụp xoay ngang (dòng chữ
///    chạy dọc) được nhận ra tự động.
///
/// Kích thước ảnh giữ nguyên. CI so từng điểm ảnh với bản Java nên mọi phép tính giữ đúng kiểu (Float = float,
/// Double = double, Int = int/long) và đúng thứ tự như bản Java; ép số thực -> số nguyên theo quy tắc của Java
/// (javaInt, floorI: NaN -> 0, tràn -> giá trị biên) thay vì Int(x) (dừng chương trình với NaN / vô cực).
/// Điểm ảnh RGBA 8 bit (con trỏ + bytesPerRow); các mảng phụ (độ sáng, cờ, mặt nạ) dài w*h, không đệm hàng.
enum Flatten {
    /// Trường dịch chuyển trơn: điểm ảnh ra (u, v) lấy màu tại (u + dx, v + dy) của ảnh vào (nội suy song tuyến tính).
    final class Field {
        var gw = 0, gh = 0
        /// Nút (i, j) nằm ở (ox + i * step, ox + j * step) trên ảnh.
        var step: Float = 0, ox: Float = 0
        var dx = [Float](), dy = [Float]()
        /// Chẩn đoán: số dòng chữ, đường kẻ ngang, đường kẻ dọc được dùng; dịch chuyển lớn nhất (điểm ảnh); trang xoay ngang.
        var lines = 0, hrules = 0, vrules = 0
        var maxShift: Float = 0
        var rotated = false
    }

    // MARK: ngưỡng làm sạch (độ sáng chia cho nền giấy, lượng tử 0..255 ứng với 0..1)

    /// Lõi nét chữ nhạt: tối hơn 0,70 lần nền giấy và thuộc một cụm gọn cỡ chữ (ảnh chụp mờ, bản photo nhạt); trang chữ
    /// nhạt nâng tới 0,76 theo độ đậm phổ biến của tâm nét + 0,17.
    static let seedQ = 179, seedMaxQ = 194, seedOffsetQ = 43
    /// Lõi nét đậm: tối hơn 0,52 lần nền giấy (trang chữ nhạt tới 0,62) — luôn được giữ.
    static let darkQ = 133, darkMaxQ = 158, darkOffsetQ = 15
    /// Chỉ xét tâm nét tối hơn 0,62 khi đo độ đậm phổ biến của chữ trên trang.
    static let modeLimitQ = 158
    /// Điểm hơi tối chỉ được giữ khi nối với lõi nét: tối hơn 0,80 (phần nhạt của nét chữ mờ) — lan tới được từ lõi trong
    /// phạm vi cỡ chữ; tối hơn 0,86 (viền khử răng cưa) — chỉ sát điểm đã giữ. Vệt nếp gấp nhạt không lan theo được.
    static let growQ = 204, weakQ = 219
    /// Mực màu (con dấu, bút xanh) là nội dung.
    static let seedChroma: Float = 0.20, weakChroma: Float = 0.12
    /// Ảnh nhỏ (ảnh thu nhỏ của bảng lọc, cạnh dài < 600): nét chữ chỉ còn vài điểm ảnh — mọi điểm tối hơn growQ là lõi.
    static let smallL = 600
    /// Chữ nhỏ (ảnh chụp / ảnh nhập độ phân giải thấp, cỡ chữ <= fineHcLo điểm ảnh): nét chữ chỉ 1-2 điểm ảnh nên dấu
    /// thanh, chấm chữ i, dấu câu nhạt hẳn đi và tách khỏi thân chữ. Cụm nhỏ gọn (<= fineDim cỡ chữ) tối hơn ngưỡng
    /// nâng dần từ ngưỡng lõi nhạt tới growQ, nằm trong fineNear cỡ chữ quanh lõi đậm, cũng là lõi. Giảm dần, hết hẳn ở
    /// cỡ chữ fineHcHi (trang lớn không đổi).
    static let fineHcLo: Float = 10, fineHcHi: Float = 15, fineDim: Float = 1.5, fineNear: Float = 0.6
    static let toneBP: Float = 0.20, toneWP: Float = 0.86
    /// Mặt nạ "mực lỏng" (tối hơn 0,90) để tìm đường kẻ bảng mảnh / nhạt.
    static let looseQ = 230
    /// Điểm thuộc đường kẻ bảng (lưới) tối hơn 0,95 được giữ; bảng kẻ nhạt được làm đậm: v' = 1 - g * (1 - v), g từ 1
    /// (độ đậm trung vị của các đường kẻ tối hơn 0,55) tăng dần tới ruleGain (nhạt hơn 0,78) — bảng in rõ không làm đậm
    /// thêm (tránh đường kẻ lốm đốm).
    static let protectQ = 242
    static let ruleGain: Float = 2.5
    static let gainLoQ = 140, gainHiQ = 200
    /// Đường kẻ nhạt hơn trung vị các đường kẻ của bảng quá 0,12 (vệt nếp gấp vắt qua bảng) không thuộc lưới.
    static let ruleDarkSpreadQ = 31

    // MARK: tham số làm thẳng

    /// Cạnh dài của ảnh phân tích (điểm ảnh).
    static let analysisLong = 1000
    /// Ảnh phân tích nhỏ hơn (ảnh thu nhỏ của bảng lọc): chỉ làm sạch, không nắn.
    static let minAnalysis = 240
    /// Số khoảng lưới theo cạnh dài.
    static let gridCells = 36
    /// Trọng số năng lượng uốn, kéo căng và độ lớn của trường (so với trọng số 1 của mỗi mẫu dòng chữ).
    static let lambdaBend = 0.5, lambdaStretch = 0.05, mu = 0.002
    /// Sai số trung bình (điểm ảnh phân tích) tối thiểu để loại cả một đường không làm thẳng nhất quán được.
    static let lineLim: Float = 1.5
    static let cgIters = 400

    /// Toàn bộ bộ lọc (dewarp = true: gồm cả bước nắn thẳng). Trả về trường dịch chuyển đã dùng (nil nếu không nắn).
    /// dewarp = false: chỉ gỡ ngón tay + làm sạch, giữ nguyên hình học (màn hình "Xóa thông minh": nét xoá được vẽ
    /// trên ảnh chưa nắn và áp trước bộ lọc, nên ảnh xem trước ở đó không được dời điểm ảnh).
    @discardableResult
    static func apply(_ px: UnsafeMutablePointer<UInt8>, _ w: Int, _ h: Int, _ bpr: Int, dewarp: Bool = true) -> Field? {
        if w < 16 || h < 16 { return nil }
        Retouch.removeFingers(px, w, h, bpr)
        let an: Analysis
        do {
            // lq, cls chỉ dùng tới hết bước làm sạch (giải phóng sớm như bản Java)
            let bg = Enhancer.backgroundFine(px, w, h, bpr, nil)
            let n = w * h
            var lq = [UInt8](repeating: 0, count: n)
            var cls = [UInt8](repeating: 0, count: n)
            normalize(px, w, h, bpr, bg, &lq, &cls)
            let mode = inkMode(lq, w, h)
            var tq = seedQ, dq = darkQ
            if mode >= 0 {
                tq = max(seedQ, min(seedMaxQ, mode + seedOffsetQ))
                dq = max(darkQ, min(darkMaxQ, mode + darkOffsetQ))
            }
            an = analyze(lq, w, h, tq)
            clean(px, w, h, bpr, bg, lq, &cls, tq, dq, an)
        }
        Retouch.cleanEdges(px, w, h, bpr)
        if !dewarp || an.hc <= 0 { return nil }
        guard let f = estimate(px, w, h, bpr, an) else { return nil }
        remap(px, w, h, bpr, f)
        Retouch.sharpen(px, w, h, bpr, 48)
        return f
    }

    // MARK: ép kiểu như Java

    /// (int) x của Java cho số thực: cắt về phía 0, NaN -> 0, ngoài khoảng int -> giá trị biên.
    @inline(__always) private static func javaInt(_ v: Float) -> Int {
        if v.isNaN { return 0 }
        if v >= 2147483648 { return 2147483647 }
        if v <= -2147483648 { return -2147483648 }
        return Int(v)
    }

    /// (int) Math.floor(x) của Java.
    @inline(__always) private static func floorI(_ v: Float) -> Int {
        return javaInt(v.rounded(.down))
    }

    // MARK: làm sạch

    /// Độ sáng chuẩn hoá theo nền (lq, 0..255) + cờ màu (cls: bit 0 lõi mực màu, bit 1 hơi có màu).
    static func normalize(_ px: UnsafeMutablePointer<UInt8>, _ w: Int, _ h: Int, _ bpr: Int, _ bg: Enhancer.Bg,
                          _ lq: inout [UInt8], _ cls: inout [UInt8]) {
        var rowR = [Float](repeating: 1, count: w)
        var rowG = [Float](repeating: 1, count: w)
        var rowB = [Float](repeating: 1, count: w)
        for y in 0..<h {
            bg.row(y, &rowR, &rowG, &rowB)
            let off = y * w
            let row = px + y * bpr
            for x in 0..<w {
                let p = row + x * 4
                let r = Float(p[0]) / rowR[x]
                let g = Float(p[1]) / rowG[x]
                let b = Float(p[2]) / rowB[x]
                let lum = 0.299 * r + 0.587 * g + 0.114 * b
                let mx = r > g ? (r > b ? r : b) : (g > b ? g : b)
                let mn = r < g ? (r < b ? r : b) : (g < b ? g : b)
                let ch = mx - mn
                var q = javaInt(lum * 255 + 0.5)
                if q > 255 { q = 255 }
                lq[off + x] = UInt8(truncatingIfNeeded: q)
                var f: UInt8 = 0
                if ch >= seedChroma && q < weakQ { f |= 1 }
                if ch >= weakChroma { f |= 2 }
                cls[off + x] = f
            }
        }
    }

    /// Chỉ giữ nét mực: "lõi" nét và các điểm hơi tối lan tới được từ lõi trong phạm vi cỡ chữ; mọi thứ khác — bóng mờ,
    /// vệt nếp gấp, mặt giấy nhàu, vân tay, sạn giấy — thành trắng. Lõi = tối hẳn so với nền giấy, mực màu, hoặc hơi tối
    /// nhưng thuộc cụm gọn cỡ chữ (vệt nếp gấp dài không tính). Điểm trên đường kẻ bảng đã nhận dạng (an.rule) luôn được
    /// giữ (bảng kẻ nhạt được làm đậm). Ngưỡng tự chỉnh theo độ đậm chữ của trang. cls được dùng làm cờ: bit 2 lõi, bit 3
    /// đường kẻ bảng (bit 5: thuộc mặt nạ lỏng), bit 4 được giữ.
    static func clean(_ px: UnsafeMutablePointer<UInt8>, _ w: Int, _ h: Int, _ bpr: Int, _ bg: Enhancer.Bg, _ lq: [UInt8],
                      _ cls: inout [UInt8], _ tq: Int, _ dq: Int, _ an: Analysis) {
        let n = w * h
        let L = max(w, h)
        let d = max(1, Int((Float(L) / 1750).rounded()))
        let bw = (w + d - 1) / d
        let bh = (h + d - 1) / d, nb = bw * bh
        // giới hạn lan từ lõi qua điểm hơi tối (ô d x d): ~0,3 cỡ chữ — đủ phủ phần nhạt của nét chữ mờ, vệt nếp gấp / vân tay
        // dính vào chữ chỉ còn đoạn ngắn
        var dg = an.hc > 0 ? Int((0.3 * an.hc * Float(an.k) / Float(d)).rounded()) : Int((Float(L) / 500 / Float(d)).rounded())
        if dg < 2 { dg = 2 }
        if dg > 250 { dg = 250 }
        let hcFull: Float = an.hc > 0 ? an.hc * Float(an.k) : Float(L) / 90
        let near: [UInt8]
        do {
            let keepB = clusters(lq, cls, w, h, min(tq + 13, weakQ - 13), d, max(8, L / 20), 1.6 * hcFull)
            // chữ nhỏ: cụm nhỏ gọn hơi tối (dấu thanh, chấm, dấu câu) sát lõi đậm cũng là lõi (keepF = cụm gọn, coreN = gần lõi)
            let fine: Float = hcFull >= fineHcHi ? 0 : (hcFull <= fineHcLo ? 1 : (fineHcHi - hcFull) / (fineHcHi - fineHcLo))
            let fq = tq + Int((fine * Float(growQ - tq)).rounded())
            // vệt nếp gấp nhạt: ô có điểm hơi tối không màu (tối hơn weakQ, không tối hơn tq) và không sát ô có nét (tối hơn
            // tq / mực màu) nối thành vạch mảnh, thẳng, dài (lineB) — không được lan qua, mẩu của nó không được lấy làm lõi;
            // nét chữ chạm vệt chỉ kéo theo phần sát chỗ chạm
            let lineB: [UInt8]
            do {
                var inkB = [UInt8](repeating: 0, count: nb), fb = [UInt8](repeating: 0, count: nb)
                for y in 0..<h {
                    let off = y * w, boff = (y / d) * bw
                    for x in 0..<w {
                        let q = Int(lq[off + x])
                        let f = cls[off + x]
                        let b = boff + x / d
                        if q < tq || (f & 1) != 0 {
                            inkB[b] = 1
                        } else if q < weakQ && (f & 2) == 0 {
                            fb[b] = 1
                        }
                    }
                }
                for by in 0..<bh {
                    for bx in 0..<bw {
                        let b = by * bw + bx
                        if fb[b] == 0 { continue }
                        var nearInk = false
                        for dy in -1...1 where !nearInk {
                            let yy = by + dy
                            if yy < 0 || yy >= bh { continue }
                            for dx in -1...1 {
                                let xx = bx + dx
                                if xx >= 0 && xx < bw && inkB[yy * bw + xx] != 0 {
                                    nearInk = true
                                    break
                                }
                            }
                        }
                        if nearInk { fb[b] = 0 }
                    }
                }
                inkB = []
                lineB = lineBlocks(fb, bw, bh, d, 1.6 * hcFull)
            }
            var keepF = [UInt8](), coreN = [UInt8]()
            let hasF = fine > 0
            if hasF {
                keepF = clusters(lq, cls, w, h, fq, d, max(3, Int((fineDim * hcFull).rounded())), 1.6 * hcFull)
                coreN = [UInt8](repeating: 0, count: nb)
                for y in 0..<h {
                    let off = y * w, boff = (y / d) * bw
                    for x in 0..<w where Int(lq[off + x]) < dq || (cls[off + x] & 1) != 0 {
                        coreN[boff + x / d] = 1
                    }
                }
                let cbb = Retouch.bounds(coreN, bw, bh)
                Retouch.dilate(&coreN, bw, bh, max(1, Int((fineNear * hcFull / Float(d)).rounded())), cbb)
                for i in 0..<nb where lineB[i] != 0 { coreN[i] = 0 }
            }
            // ô: 3 = có lõi đậm (lan xa), 2 = có lõi nhạt (lan ngắn), 1 = có điểm hơi tối (được lan qua); colB: 2 = ô có mực
            // màu rõ, 1 = ô có điểm hơi có màu tối hơn growQ (phần nhạt của nét mực màu: chữ ký bút xanh mảnh)
            var bs = [UInt8](repeating: 0, count: nb)
            var colB = [UInt8](repeating: 0, count: nb)
            let k = an.k, aw = an.aw, ah = an.ah
            let small = L < smallL
            let hasRule = an.rule != nil, hasRule2 = an.rule2 != nil
            let rule = an.rule ?? [], rule2 = an.rule2 ?? []
            let ruleQ = an.ruleQ, ruleQ2 = an.ruleQ2, ruleQE = an.ruleQE, ruleQE2 = an.ruleQE2
            for y in 0..<h {
                let off = y * w, boff = (y / d) * bw
                let ay = y / k
                let roff = ay < ah ? ay * aw : -1
                for x in 0..<w {
                    let i = off + x
                    let q = Int(lq[i])
                    let f = Int(cls[i])
                    let b = boff + x / d
                    var seed = q < dq || (f & 1) != 0 || (q < tq && keepB[b] != 0) || (small && q < growQ)
                        || (hasF && q < fq && keepF[b] != 0 && coreN[b] != 0)
                    var inMask = false
                    // (lõi / điểm đường kẻ luôn tối hơn protectQ: điểm nền giấy khỏi đọc mặt nạ)
                    if roff >= 0 && q < protectQ {
                        let ax = x / k
                        if ax < aw {
                            let m1 = hasRule && rule[roff + ax] != 0, m2 = hasRule2 && rule2[roff + ax] != 0
                            inMask = m1 || m2
                            if inMask {
                                // đường kẻ mảnh ở ảnh nhỏ nằm vắt qua 2 hàng điểm ảnh (mỗi hàng chỉ nhạt): tính cả điểm sát
                                // bên tối nhất (độ đậm hiệu dụng qe); chỉ xét điểm đủ tối (tối hơn tq) để vệt nếp gấp sát
                                // đường kẻ không bị tính
                                var qe = 255
                                if q < tq {
                                    var qn = 255
                                    if x > 0 { qn = min(qn, Int(lq[i - 1])) }
                                    if x + 1 < w { qn = min(qn, Int(lq[i + 1])) }
                                    if y > 0 { qn = min(qn, Int(lq[i - w])) }
                                    if y + 1 < h { qn = min(qn, Int(lq[i + w])) }
                                    qe = 255 - min(255, (255 - q) + (255 - qn))
                                }
                                if m1 && (q < ruleQ || qe < ruleQE) {
                                    cls[i] |= 8
                                    seed = true
                                } else if m2 && (q < ruleQ2 || qe < ruleQE2) {
                                    cls[i] |= 8 | 32
                                    seed = true
                                }
                            }
                        }
                    }
                    if seed {
                        cls[i] |= 4
                        // lõi đậm / mực màu: lan xa (3); lõi nhạt: lan ngắn (2); điểm thuộc (vùng) đường kẻ bảng: chỉ giữ,
                        // không lan (1) — vệt nếp gấp vắt qua / chạm đường kẻ không bị kéo theo
                        let sv: UInt8 = inMask ? 1 : ((q < dq || (f & 1) != 0) ? 3 : 2)
                        if bs[b] < sv { bs[b] = sv }
                    } else if (q < growQ || (f & 2) != 0) && bs[b] == 0 && lineB[b] == 0 {
                        bs[b] = 1
                    }
                    if (f & 1) != 0 {
                        colB[b] = 2
                    } else if (f & 2) != 0 && q < growQ && colB[b] == 0 {
                        colB[b] = 1
                    }
                }
            }
            let reach = grow(bs, bw, bh, dg, max(1, dg / 2))
            // nét mực màu: lan không giới hạn từ ô có mực màu rõ qua các ô hơi có màu (vệt nếp gấp, bóng mờ không có màu)
            let reachC = colorReach(colB, bw, bh)
            let hasC = reachC != nil
            let rc = reachC ?? []
            // giữ: lõi + điểm tối hơn growQ lan tới được + điểm hơi có màu nối với mực màu; rồi viền (tối hơn weakQ) sát các
            // điểm đó
            var nr = [UInt8](repeating: 0, count: n)
            var any = false
            for y in 0..<h {
                let off = y * w, boff = (y / d) * bw
                for x in 0..<w {
                    let i = off + x
                    let f = cls[i]
                    let q = Int(lq[i])
                    let b = boff + x / d
                    if (f & 4) != 0 || (reach[b] != 0 && (q < growQ || (f & 2) != 0))
                        || (hasC && rc[b] != 0 && (f & 2) != 0 && q < growQ) {
                        cls[i] |= 16
                        nr[i] = 1
                        any = true
                    }
                }
            }
            if any {
                let nbb = Retouch.bounds(nr, w, h)
                Retouch.dilate(&nr, w, h, max(1, Int((Float(L) / 1400).rounded())), nbb)
            }
            near = nr
        }
        // đường cong tông: chuẩn hoá v (0..2) -> 0..255, mũ 1,5 (t * sqrt(t))
        let lutScale = 1024, lutN = 2048
        var lut = [UInt8](repeating: 0, count: lutN)
        let inv: Float = 1 / (toneWP - toneBP)
        for i in 0..<lutN {
            let v = Float(i) / Float(lutScale)
            let t = (v - toneBP) * inv
            // (float) Math.sqrt(t) của Java = căn bậc hai Float làm tròn đúng = t.squareRoot()
            if t <= 0 {
                lut[i] = 0
            } else if t >= 1 {
                lut[i] = 255
            } else {
                lut[i] = UInt8(truncatingIfNeeded: javaInt(t * t.squareRoot() * 255 + 0.5))
            }
        }
        let gain1 = an.ruleGain, gain2 = an.ruleGain2
        var rowR = [Float](repeating: 1, count: w)
        var rowG = [Float](repeating: 1, count: w)
        var rowB = [Float](repeating: 1, count: w)
        for y in 0..<h {
            let off = y * w
            let row = px + y * bpr
            var rowAny = false
            var xs = 0
            while xs < w && !rowAny {
                rowAny = keep(lq, cls, near, off + xs)
                xs += 1
            }
            if !rowAny {
                for x in 0..<w {
                    let p = row + x * 4
                    p[0] = 255; p[1] = 255; p[2] = 255; p[3] = 255
                }
                continue
            }
            bg.row(y, &rowR, &rowG, &rowB)
            for x in 0..<w {
                let p = row + x * 4
                if !keep(lq, cls, near, off + x) {
                    p[0] = 255; p[1] = 255; p[2] = 255; p[3] = 255
                    continue
                }
                var r = Float(p[0]) / rowR[x]
                var g = Float(p[1]) / rowG[x]
                var b = Float(p[2]) / rowB[x]
                let lum = 0.299 * r + 0.587 * g + 0.114 * b
                if (cls[off + x] & 8) != 0 {
                    // đường kẻ bảng: làm đậm theo bảng (đường kẻ mảnh, nhạt vẫn rõ sau khi lọc)
                    let gn = (cls[off + x] & 32) != 0 ? gain2 : gain1
                    let v = lut[idx(1 - gn * (1 - lum), lutScale, lutN)]
                    p[0] = v; p[1] = v; p[2] = v; p[3] = 255
                    continue
                }
                let mx = r > g ? (r > b ? r : b) : (g > b ? g : b)
                let mn = r < g ? (r < b ? r : b) : (g < b ? g : b)
                var q = (mx - mn - 0.10) / 0.16
                if q <= 0 {
                    let v = lut[idx(lum, lutScale, lutN)]
                    p[0] = v; p[1] = v; p[2] = v
                } else {
                    if q > 1 { q = 1 }
                    q = q * 1.3
                    r = lum + (r - lum) * q
                    g = lum + (g - lum) * q
                    b = lum + (b - lum) * q
                    p[0] = lut[idx(r, lutScale, lutN)]
                    p[1] = lut[idx(g, lutScale, lutN)]
                    p[2] = lut[idx(b, lutScale, lutN)]
                }
                p[3] = 255
            }
        }
    }

    // MARK: phân tích: cỡ chữ, lưới bảng

    /// Kết quả phân tích ở độ phân giải thấp (cạnh dài ~analysisLong, khối k x k điểm ảnh).
    final class Analysis {
        var k = 1, aw = 0, ah = 0
        /// Cỡ chữ (điểm ảnh phân tích); 0 = không đủ chữ để nắn. hcT: cỡ chữ theo chiều ngang (dùng khi trang xoay ngang).
        var hc: Float = 0, hcT: Float = 0
        var tmax = 0, stride = 0
        /// Mặt nạ đường kẻ bảng (thuộc lưới), đã nới 1 điểm: rule — từ mặt nạ chặt (bảng in rõ), rule2 — từ mặt nạ lỏng
        /// (thêm đường kẻ mảnh / nhạt); nil nếu không có. Điểm trong mặt nạ tối hơn ruleQ / ruleQ2, hoặc có độ đậm hiệu
        /// dụng (cộng điểm sát bên tối nhất) tối hơn ruleQE / ruleQE2, được giữ và làm đậm.
        var rule: [UInt8]? = nil, rule2: [UInt8]? = nil
        var ruleQ = Flatten.protectQ, ruleQ2 = Flatten.protectQ, ruleQE = 0, ruleQE2 = 0
        /// Hệ số làm đậm đường kẻ của từng mặt nạ (1 = giữ nguyên tông).
        var ruleGain: Float = 1, ruleGain2: Float = 1
        /// Mẫu đường tâm các đường kẻ ngang / dọc thuộc lưới (dùng để nắn).
        let hr = Flatten.Lines(), vr = Flatten.Lines()
    }

    /// Ảnh phân tích = độ sáng nhỏ nhất của mỗi khối k x k (giữ được nét mảnh). Đo cỡ chữ trên mặt nạ "chặt" (tối hơn tq);
    /// tìm lưới bảng trên mặt nạ chặt (bảng in rõ — dùng để nắn và giữ nét) và trên mặt nạ "lỏng" (tối hơn 0,90 — thêm
    /// đường kẻ mảnh / nhạt để giữ nét). Vệt nếp gấp, gạch chân đơn lẻ không thuộc lưới nên không được tính.
    static func analyze(_ lq: [UInt8], _ w: Int, _ h: Int, _ tq: Int) -> Analysis {
        let an = Analysis()
        let L = max(w, h)
        let k = max(1, Int((Float(L) / Float(analysisLong)).rounded()))
        let aw = w / k, ah = h / k
        an.k = k
        an.aw = aw
        an.ah = ah
        if aw < 16 || ah < 16 { return an }
        let nA = aw * ah
        var amin = [UInt8](repeating: 0, count: nA)
        var strict = [UInt8](repeating: 0, count: nA)
        var loose = [UInt8](repeating: 0, count: nA)
        for y in 0..<ah {
            for x in 0..<aw {
                var mn = 255
                for dy in 0..<k {
                    let o = (y * k + dy) * w + x * k
                    for dx in 0..<k {
                        let q = Int(lq[o + dx])
                        if q < mn { mn = q }
                    }
                }
                amin[y * aw + x] = UInt8(truncatingIfNeeded: mn)
                if mn < tq { strict[y * aw + x] = 1 }
                if mn < looseQ { loose[y * aw + x] = 1 }
            }
        }
        var lab = [Int](repeating: 0, count: nA)
        var queue = [Int](repeating: 0, count: nA)
        var wOut: Float = 0
        let hc = textHeight(strict, aw, ah, &lab, &queue, &wOut)
        an.hc = hc
        an.hcT = wOut
        if hc <= 0 { return an }
        an.tmax = max(2, Int((hc * 0.35).rounded()))
        an.stride = max(4, Int((hc * 1.25).rounded()))
        // bảng in rõ: tìm trên mặt nạ chặt (vệt nếp gấp nhạt không lọt vào) — dùng để nắn và giữ nét; thêm lượt trên mặt
        // nạ lỏng để giữ cả đường kẻ mảnh / nhạt (chỉ dùng để nắn khi lượt chặt không thấy bảng)
        let strictGrid = grid(an, strict, amin, &lab, &queue, true)
        _ = grid(an, loose, amin, &lab, &queue, !strictGrid)
        return an
    }

    /// Lưới bảng trên mặt nạ m (ảnh phân tích): đường kẻ ngang / dọc dài, mảnh, đủ thẳng; chỉ giữ "lõi lưới" — mỗi đường
    /// cắt ít nhất 2 đường vuông góc còn lại, độ đậm không nhạt hơn hẳn các đường khác của bảng, tổng cộng >= 3 đường
    /// một hướng và >= 2 đường hướng kia; cắt bỏ phần thò ra ngoài các giao điểm. Ghi mặt nạ + mẫu đường tâm vào an.
    /// Trả về true nếu có lưới.
    static func grid(_ an: Analysis, _ m: [UInt8], _ amin: [UInt8], _ lab: inout [Int], _ queue: inout [Int],
                     _ primary: Bool) -> Bool {
        let aw = an.aw, ah = an.ah, nA = aw * ah
        let hc = an.hc
        let tmax = an.tmax
        var th = [UInt8](repeating: 0, count: nA), tv = [UInt8](repeating: 0, count: nA)
        do {
            var hrn = [Int](repeating: 0, count: nA), vrn = [Int](repeating: 0, count: nA)
            runLengths(m, aw, ah, &hrn, &vrn)
            for i in 0..<nA {
                if m[i] == 0 { continue }
                if vrn[i] <= tmax || hrn[i] >= 3 * tmax { th[i] = 1 }
                if hrn[i] <= tmax || vrn[i] >= 3 * tmax { tv[i] = 1 }
            }
        }
        var hId = [Int](repeating: 0, count: nA), vId = [Int](repeating: 0, count: nA)
        let hs = Rules(), vs = Rules()
        findRules(th, amin, aw, ah, true, max(Int((8 * hc).rounded()), aw / 10), tmax, &lab, &queue, &hId, hs)
        findRules(tv, amin, aw, ah, false, max(Int((8 * hc).rounded()), ah / 14), tmax, &lab, &queue, &vId, vs)
        if hs.n == 0 || vs.n == 0 { return false }
        // lưới: cặp (đường ngang, đường dọc) có điểm chung
        let nh = hs.n, nv = vs.n
        var pair = [Bool](repeating: false, count: nh * nv)
        for i in 0..<nA where hId[i] != 0 && vId[i] != 0 {
            pair[(hId[i] - 1) * nv + vId[i] - 1] = true
        }
        // lõi lưới: lặp loại đường cắt < 2 đường vuông góc còn lại, hoặc nhạt hơn hẳn các đường kẻ khác của bảng
        // (vệt nếp gấp vắt qua bảng in đậm) theo cả độ đậm lẫn độ đậm hiệu dụng (đường kẻ mảnh ở ảnh nhỏ nằm vắt qua 2 hàng
        // điểm ảnh thì từng hàng nhạt nhưng tổng vẫn đậm như các đường khác); cần >= 3 đường một hướng và >= 2 đường hướng kia
        var gH = [Bool](repeating: false, count: nh), gV = [Bool](repeating: false, count: nv)
        for a in 0..<nh { gH[a] = hs.ok[a] }
        for b in 0..<nv { gV[b] = vs.ok[b] }
        var lim = 255, limE = 255
        var changed = true
        while changed {
            changed = false
            var dk = [Int]()
            dk.reserveCapacity(nh + nv)
            for a in 0..<nh where gH[a] { dk.append(hs.dark[a]) }
            for b in 0..<nv where gV[b] { dk.append(vs.dark[b]) }
            let nd = dk.count
            if nd == 0 { break }
            dk.sort()
            lim = dk[nd / 2] + ruleDarkSpreadQ
            var dkE = [Int]()
            dkE.reserveCapacity(nh + nv)
            for a in 0..<nh where gH[a] { dkE.append(hs.darkE[a]) }
            for b in 0..<nv where gV[b] { dkE.append(vs.darkE[b]) }
            dkE.sort()
            limE = dkE[nd / 2] + ruleDarkSpreadQ
            for a in 0..<nh {
                if !gH[a] { continue }
                var c = 0
                for b in 0..<nv where gV[b] && pair[a * nv + b] { c += 1 }
                if c < 2 || (hs.dark[a] > lim && hs.darkE[a] > limE) {
                    gH[a] = false
                    changed = true
                }
            }
            for b in 0..<nv {
                if !gV[b] { continue }
                var c = 0
                for a in 0..<nh where gH[a] && pair[a * nv + b] { c += 1 }
                if c < 2 || (vs.dark[b] > lim && vs.darkE[b] > limE) {
                    gV[b] = false
                    changed = true
                }
            }
        }
        var cH = 0, cV = 0
        for a in 0..<nh where gH[a] { cH += 1 }
        for b in 0..<nv where gV[b] { cV += 1 }
        if min(cH, cV) < 2 || max(cH, cV) < 3 { return false }
        // phạm vi giao điểm với các đường còn lại
        var hLo = [Int](repeating: Int(Int32.max), count: nh), hHi = [Int](repeating: -1, count: nh)
        var vLo = [Int](repeating: Int(Int32.max), count: nv), vHi = [Int](repeating: -1, count: nv)
        for i in 0..<nA {
            if hId[i] == 0 || vId[i] == 0 { continue }
            let a = hId[i] - 1, b = vId[i] - 1
            if !gH[a] || !gV[b] { continue }
            let x = i % aw, y = i / aw
            if x < hLo[a] { hLo[a] = x }
            if x > hHi[a] { hHi[a] = x }
            if y < vLo[b] { vLo[b] = y }
            if y > vHi[b] { vHi[b] = y }
        }
        let margin = 2 * tmax + 2
        var rule = [UInt8](repeating: 0, count: nA)
        var anyRule = false
        for i in 0..<nA {
            let x = i % aw, y = i / aw
            let a = hId[i] - 1, b = vId[i] - 1
            let onH = a >= 0 && gH[a] && x >= hLo[a] - margin && x <= hHi[a] + margin
            let onV = b >= 0 && gV[b] && y >= vLo[b] - margin && y <= vHi[b] + margin
            if onH || onV {
                rule[i] = 1
                anyRule = true
            }
        }
        if !anyRule { return false }
        let rb = Retouch.bounds(rule, aw, ah)
        Retouch.dilate(&rule, aw, ah, 1, rb)
        // chỉ giữ điểm không nhạt hơn hẳn các đường kẻ của bảng (vệt nếp gấp dính vào đường kẻ thì không); làm đậm theo
        // độ đậm trung vị của cả bảng
        let med = lim - ruleDarkSpreadQ
        let frac = Float(med - gainLoQ) / Float(gainHiQ - gainLoQ)
        let gain: Float = 1 + (ruleGain - 1) * max(0, min(1, frac))
        if an.rule == nil {
            an.rule = rule
            an.ruleQ = min(protectQ, lim + 1)
            an.ruleQE = min(protectQ, limE + 1)
            an.ruleGain = gain
        } else {
            an.rule2 = rule
            an.ruleQ2 = min(protectQ, lim + 1)
            an.ruleQE2 = min(protectQ, limE + 1)
            an.ruleGain2 = gain
        }
        if !primary { return true }
        let win = 2 * max(2, Int(hc.rounded())) + 1
        for a in 0..<nh where gH[a] {
            hs.sample(a, max(hs.a0[a], hLo[a] - margin), min(hs.a1[a], hHi[a] + margin), an.stride, win, true, an.hr)
        }
        for b in 0..<nv where gV[b] {
            vs.sample(b, max(vs.a0[b], vLo[b] - margin), min(vs.a1[b], vHi[b] + margin), an.stride, win, false, an.vr)
        }
        return true
    }

    /// Các đường kẻ dài tìm được trên một hướng: khoảng a0..a1 theo chiều dọc đường, đường tâm cen (cov = 0: thiếu).
    final class Rules {
        var n = 0
        var a0 = [Int](), a1 = [Int]()
        var cen = [[Float]](), cov = [[Int]]()
        /// Độ đậm (trung vị độ sáng nhỏ nhất theo bề ngang nét, 0..255), độ đậm hiệu dụng (trung vị của 255 - tổng độ tối
        /// điểm tối nhất và điểm sát bên tối hơn theo bề ngang nét, không âm) và cờ "đủ thẳng" của từng đường.
        var dark = [Int](), darkE = [Int](), ok = [Bool]()

        func add(_ s: Int, _ e: Int, _ c: [Float], _ v: [Int], _ dk: Int, _ dkE: Int, _ straight: Bool) {
            a0.append(s)
            a1.append(e)
            cen.append(c)
            cov.append(v)
            dark.append(dk)
            darkE.append(dkE)
            ok.append(straight)
            n += 1
        }

        /// Lấy mẫu đường tâm của đường r trong [s, e] (trung vị trượt cửa sổ win, mỗi strd điểm ảnh) vào out.
        func sample(_ r: Int, _ s: Int, _ e: Int, _ strd: Int, _ win: Int, _ horiz: Bool, _ out: Flatten.Lines) {
            let c = cen[r]
            let v = cov[r]
            let base = a0[r]
            var buf = [Float](repeating: 0, count: win)
            let rad = win / 2
            var ln = -1
            for a in stride(from: s + strd / 2, through: e, by: strd) {
                var m = 0
                for t in (a - rad)...(a + rad) {
                    if t < s || t > e || v[t - base] == 0 { continue }
                    buf[m] = c[t - base]
                    m += 1
                }
                if m * 2 < win { continue }
                Flatten.sortSmall(&buf, m)
                let med = (m & 1) == 1 ? buf[m / 2] : (buf[m / 2 - 1] + buf[m / 2]) * 0.5
                if ln < 0 { ln = out.newLine() }
                if horiz {
                    out.add(ln, Float(a), med, 2)
                } else {
                    out.add(ln, med, Float(a), 2)
                }
            }
        }
    }

    /// Đường kẻ dài (ngang: horiz = true) trong mặt nạ nét mảnh m: thành phần liên thông trải dài >= minLen, phủ kín
    /// gần hết chiều dài và mỏng. Ghi chỉ số (1..) của đường vào id, đường tâm vào out.
    static func findRules(_ m: [UInt8], _ amin: [UInt8], _ w: Int, _ h: Int, _ horiz: Bool, _ minLen: Int, _ tmax: Int,
                          _ lab: inout [Int], _ queue: inout [Int], _ id: inout [Int], _ out: Rules) {
        var dh = [Int](repeating: 0, count: 256), dhE = [Int](repeating: 0, count: 256)
        let n = w * h
        for i in 0..<n { lab[i] = 0 }
        var cur = 0
        for start in 0..<n {
            if m[start] == 0 || lab[start] != 0 { continue }
            cur += 1
            var head = 0, tail = 0
            queue[tail] = start
            tail += 1
            lab[start] = cur
            var minX = w, maxX = -1, minY = h, maxY = -1
            while head < tail {
                let i = queue[head]
                head += 1
                let x = i % w, y = i / w
                if x < minX { minX = x }
                if x > maxX { maxX = x }
                if y < minY { minY = y }
                if y > maxY { maxY = y }
                for dy in -1...1 {
                    let yy = y + dy
                    if yy < 0 || yy >= h { continue }
                    for dx in -1...1 {
                        let xx = x + dx
                        if xx < 0 || xx >= w { continue }
                        let j = yy * w + xx
                        if m[j] != 0 && lab[j] == 0 {
                            lab[j] = cur
                            queue[tail] = j
                            tail += 1
                        }
                    }
                }
            }
            let a0 = horiz ? minX : minY, a1 = horiz ? maxX : maxY
            let b0 = horiz ? minY : minX, b1 = horiz ? maxY : maxX
            let len = a1 - a0 + 1
            if len < minLen { continue }
            var cen = [Float](repeating: 0, count: len)
            var cov = [Int](repeating: 0, count: len)
            var covered = 0
            for q in 0..<256 {
                dh[q] = 0
                dhE[q] = 0
            }
            let bLim = horiz ? h : w
            for a in a0...a1 {
                var c = 0, mn = 255, bm = -1
                var sum = 0
                for b in b0...b1 {
                    let i = horiz ? b * w + a : a * w + b
                    if lab[i] == cur {
                        c += 1
                        sum += b
                        let q = Int(amin[i])
                        if q < mn {
                            mn = q
                            bm = b
                        }
                    }
                }
                cov[a - a0] = c
                if c > 0 {
                    covered += 1
                    cen[a - a0] = Float(sum) / Float(c)
                    dh[mn] += 1
                    // điểm sát bên (theo bề ngang nét) tối hơn của điểm tối nhất
                    var qn = 255
                    if bm > 0 { qn = Int(amin[horiz ? (bm - 1) * w + a : a * w + bm - 1]) }
                    if bm + 1 < bLim { qn = min(qn, Int(amin[horiz ? (bm + 1) * w + a : a * w + bm + 1])) }
                    dhE[255 - min(255, (255 - mn) + (255 - qn))] += 1
                }
            }
            if covered * 4 < len * 3 || tail > covered * (tmax + 2) { continue }
            // độ đậm: trung vị; độ nghiêng đầu - cuối (trung bình đường tâm 1/6 đầu và 1/6 cuối) <= 0,25
            var acc = 0, dk = 255
            for q in 0..<256 {
                acc += dh[q]
                if acc * 2 >= covered {
                    dk = q
                    break
                }
            }
            var accE = 0, dkE = 255
            for q in 0..<256 {
                accE += dhE[q]
                if accE * 2 >= covered {
                    dkE = q
                    break
                }
            }
            let part = max(1, len / 6)
            var s0: Float = 0, s1: Float = 0
            var c0 = 0, c1 = 0
            for a in 0..<part {
                if cov[a] > 0 {
                    s0 += cen[a]
                    c0 += 1
                }
                if cov[len - 1 - a] > 0 {
                    s1 += cen[len - 1 - a]
                    c1 += 1
                }
            }
            var straight = false
            if c0 > 0 && c1 > 0 {
                let tilt: Float = abs(s1 / Float(c1) - s0 / Float(c0))
                straight = tilt <= 0.25 * Float(len)
            }
            out.add(a0, a1, cen, cov, dk, dkE, straight)
            let rid = out.n
            for q in 0..<tail { id[queue[q]] = rid }
        }
    }

    // MARK: ước lượng trường dịch chuyển

    /// Dò dòng chữ trên ảnh đã làm sạch (nền trắng) + đường kẻ bảng của an, khớp trường dịch chuyển làm chúng thẳng.
    /// Dòng chữ chạy dọc (trang chụp xoay ngang) thì làm trên ảnh chuyển vị. Nil nếu không đủ dữ liệu hoặc trang đã phẳng.
    static func estimate(_ px: UnsafeMutablePointer<UInt8>, _ w: Int, _ h: Int, _ bpr: Int, _ an: Analysis) -> Field? {
        let k = an.k, aw = an.aw, ah = an.ah
        if min(aw, ah) < minAnalysis || an.hc < 3 { return nil }
        let nA = aw * ah
        // mực chữ: trung bình khối k x k của ảnh đã làm sạch tối hơn 160, trừ đường kẻ bảng và mực màu rõ (con dấu, chữ ký:
        // vòng dấu cạnh một dòng chữ bị ghép nhầm thành đoạn dòng chữ, làm cong trang phẳng) — trừ khi không có mực màu thì
        // không đủ dòng (trang viết tay bút xanh)
        var ink = [UInt8](repeating: 0, count: nA)
        var inkC = [UInt8](repeating: 0, count: nA)
        let kk = k * k
        let hasRule = an.rule != nil, hasRule2 = an.rule2 != nil
        let rule = an.rule ?? [], rule2 = an.rule2 ?? []
        var anyColor = false
        for y in 0..<ah {
            for x in 0..<aw {
                var s = 0, sc = 0
                for dy in 0..<k {
                    let row = px + (y * k + dy) * bpr + x * k * 4
                    for dx in 0..<k {
                        let p = row + dx * 4
                        let r = Int(p[0]), g = Int(p[1]), b = Int(p[2])
                        let l = 299 * r + 587 * g + 114 * b
                        s += l
                        let mx = r > g ? (r > b ? r : b) : (g > b ? g : b)
                        let mn = r < g ? (r < b ? r : b) : (g < b ? g : b)
                        sc += mx - mn > 60 ? 255000 : l
                    }
                }
                let i = y * aw + x
                if (!hasRule || rule[i] == 0) && (!hasRule2 || rule2[i] == 0) {
                    if s < 160 * 1000 * kk { ink[i] = 1 }
                    if sc < 160 * 1000 * kk { inkC[i] = 1 }
                    if ink[i] != inkC[i] { anyColor = true }
                }
            }
        }
        var tl = Lines()
        var nt = -1
        if anyColor {
            nt = textLines(inkC, aw, ah, an.hc, tl)
            if nt >= 3 {
                ink = inkC
            } else {
                tl = Lines()
                nt = -1
            }
        }
        inkC = []
        if nt < 0 { nt = textLines(ink, aw, ah, an.hc, tl) }
        // hướng dòng chữ: thử cả ảnh chuyển vị (cỡ chữ đo theo chiều ngang); hướng đúng cho dòng dài hơn và mượt hơn hẳn
        var inkT = [UInt8](repeating: 0, count: nA)
        for y in 0..<ah {
            for x in 0..<aw { inkT[x * ah + y] = ink[y * aw + x] }
        }
        let hcT: Float = an.hcT > 0 ? an.hcT : an.hc
        let tlT = Lines()
        let ntT = textLines(inkT, ah, aw, hcT, tlT)
        let rot = lineScore(tlT) > 1.5 * lineScore(tl)
        let strd = rot ? max(4, Int((hcT * 1.25).rounded())) : an.stride
        let hl = Lines(), vl = Lines()
        let nh: Int
        if !rot {
            hl.append(an.hr, false)
            nh = hl.count
            hl.append(tl, false)
            hl.pitch = tl.pitch
            vl.append(an.vr, false)
        } else {
            hl.append(an.vr, true)
            nh = hl.count
            hl.append(tlT, false)
            hl.pitch = tlT.pitch
            vl.append(an.hr, true)
        }
        let fw = rot ? ah : aw, fh = rot ? aw : ah
        let gs = max(8, Int((Float(max(aw, ah)) / Float(gridCells)).rounded()))
        let gw = (fw + gs - 1) / gs + 1, gh = (fh + gs - 1) / gs + 1
        let N = gw * gh
        var dyF = [Double](repeating: 0, count: N), dxF = [Double](repeating: 0, count: N)
        let mx = fitField(fw, fh, gs, gw, gh, hl, nh, vl, strd, &dxF, &dyF)
        if !(mx >= 1.0) { return nil }
        let f = Field()
        f.step = Float(gs * k)
        f.ox = Float(k - 1) / 2
        let kd = Double(k)
        var fdx = [Float](repeating: 0, count: N), fdy = [Float](repeating: 0, count: N)
        if !rot {
            f.gw = gw
            f.gh = gh
            for i in 0..<N {
                fdx[i] = Float(dxF[i] * kd)
                fdy[i] = Float(dyF[i] * kd)
            }
            f.hrules = nh
            f.vrules = vl.count
        } else {
            // chuyển vị trường về toạ độ ảnh: nút (i, j) <- nút (j, i) của ảnh chuyển vị, đổi vai dx / dy
            f.gw = gh
            f.gh = gw
            let fgw = gh, fgh = gw
            for j in 0..<fgh {
                for i in 0..<fgw {
                    fdx[j * fgw + i] = Float(dyF[i * gw + j] * kd)
                    fdy[j * fgw + i] = Float(dxF[i * gw + j] * kd)
                }
            }
            f.hrules = vl.count
            f.vrules = nh
        }
        f.dx = fdx
        f.dy = fdy
        f.lines = rot ? ntT : nt
        f.maxShift = Float(mx * kd)
        f.rotated = rot
        return f
    }

    /// Khớp trường (toạ độ ảnh phân tích fw x fh, lưới gw x gh bước gs) từ đường ngang hl (nh đường kẻ đầu, còn lại dòng
    /// chữ) và đường dọc vl. Ghi vào dxF, dyF (đã nhân hệ số thu nhỏ nếu cần để không gập); trả về dịch chuyển lớn nhất,
    /// 0 nếu không đủ dữ liệu / khớp không tin cậy.
    static func fitField(_ fw: Int, _ fh: Int, _ gs: Int, _ gw: Int, _ gh: Int, _ hl: Lines, _ nh: Int, _ vl: Lines,
                         _ strd: Int, _ dxF: inout [Double], _ dyF: inout [Double]) -> Double {
        if hl.count < 3 { return 0 }
        let N = gw * gh
        let fgs = Float(gs)
        // 1) trường dọc từ dòng chữ + đường kẻ ngang (vị trí ngang lấy đúng toạ độ mẫu)
        var pu = Array(hl.xs[0..<hl.n])
        fitLines(hl, gw, gh, gs, pu, &dyF, true)
        // các đoạn chữ cách quãng trên cùng một dòng (vd. "NGƯỜI LẬP ... THỦ TRƯỞNG") -> một dòng, khớp lại
        if hl.pitch > 0 && mergeLines(hl, nh, 0.3 * hl.pitch, Float(strd)) { fitLines(hl, gw, gh, gs, pu, &dyF, true) }
        // 2) trường ngang từ đường kẻ dọc
        if vl.count > 0 {
            var pv = [Float](repeating: 0, count: vl.n)
            for i in 0..<vl.n { pv[i] = outV(dyF, gw, gh, fgs, vl.xs[i], vl.ys[i]) }
            fitLines(vl, gw, gh, gs, pv, &dxF, false)
            // 3) khớp lại trường dọc với vị trí ngang đã hiệu chỉnh
            let cs = hl.cs ?? []
            for i in 0..<hl.n {
                var u = hl.xs[i]
                for _ in 0..<2 { u = hl.xs[i] - Float(interp(dxF, gw, gh, fgs, u, cs[hl.line[i]])) }
                pu[i] = u
            }
            fitLines(hl, gw, gh, gs, pu, &dyF, true)
        }
        // phần lớn mẫu bị loại: dữ liệu mâu thuẫn (không phải dòng chữ / bảng), không nắn
        if hl.kept < 0.5 { return 0 }
        // vùng xa mọi mẫu (lề trống, vùng không nhận ra dòng): trường tắt dần về 0 thay vì ngoại suy
        fade(hl, vl, gw, gh, gs, &dxF, &dyF)
        // giới hạn độ lớn; không gập (thu nhỏ trường nếu cần); trang đã phẳng thì bỏ qua
        let lim: Float = 0.08 * Float(max(fw, fh))
        let hi = Double(lim), lo = Double(-lim)
        for i in 0..<N {
            if dyF[i] > hi { dyF[i] = hi }
            if dyF[i] < lo { dyF[i] = lo }
            if dxF[i] > hi { dxF[i] = hi }
            if dxF[i] < lo { dxF[i] = lo }
        }
        var scale = 1.0
        var tries = 0
        while tries < 3 && !foldFree(dxF, dyF, gw, gh, gs, scale) {
            scale *= 0.5
            tries += 1
        }
        if !foldFree(dxF, dyF, gw, gh, gs, scale) { return 0 }
        var mx = 0.0
        for i in 0..<N {
            dxF[i] *= scale
            dyF[i] *= scale
            let a = abs(dyF[i]), b = abs(dxF[i])
            if a > mx { mx = a }
            if b > mx { mx = b }
        }
        return mx
    }

    /// Số ô lưới quanh mẫu gần nhất mà trường giữ nguyên; xa hơn fadeEnd ô thì bằng 0.
    static let fadeStart = 3, fadeEnd = 8

    /// Nhân trường với hệ số theo khoảng cách (số ô, 8 hướng) từ nút tới nút gần nhất có mẫu đang được dùng:
    /// <= fadeStart: 1; >= fadeEnd: 0; ở giữa giảm tuyến tính.
    static func fade(_ hl: Lines, _ vl: Lines, _ gw: Int, _ gh: Int, _ gs: Int, _ dxF: inout [Double], _ dyF: inout [Double]) {
        let N = gw * gh
        var dist = [Int](repeating: -1, count: N)
        var queue = [Int](repeating: 0, count: N)
        var tail = 0
        let fgs = Float(gs)
        for pass in 0..<2 {
            let ln = pass == 0 ? hl : vl
            for i in 0..<ln.n {
                if ln.wt[i] <= 0 { continue }
                var gx = floorI(ln.xs[i] / fgs + 0.5), gy = floorI(ln.ys[i] / fgs + 0.5)
                if gx < 0 { gx = 0 }
                if gx > gw - 1 { gx = gw - 1 }
                if gy < 0 { gy = 0 }
                if gy > gh - 1 { gy = gh - 1 }
                let o = gy * gw + gx
                if dist[o] < 0 {
                    dist[o] = 0
                    queue[tail] = o
                    tail += 1
                }
            }
        }
        var head = 0
        while head < tail {
            let i = queue[head]
            head += 1
            let x = i % gw, y = i / gw
            for dy in -1...1 {
                let yy = y + dy
                if yy < 0 || yy >= gh { continue }
                for dx in -1...1 {
                    let xx = x + dx
                    if xx < 0 || xx >= gw { continue }
                    let j = yy * gw + xx
                    if dist[j] >= 0 { continue }
                    dist[j] = dist[i] + 1
                    queue[tail] = j
                    tail += 1
                }
            }
        }
        for i in 0..<N {
            let dd = dist[i]
            if dd < 0 || dd >= fadeEnd {
                dxF[i] = 0
                dyF[i] = 0
            } else if dd > fadeStart {
                let f = Double(fadeEnd - dd) / Double(fadeEnd - fadeStart)
                dxF[i] *= f
                dyF[i] *= f
            }
        }
    }

    // MARK: hàm phụ: làm sạch, cỡ chữ, dòng chữ

    /// Điểm được giữ: lõi nét / điểm lan tới được (bit 4), hoặc viền hơi tối nằm sát các điểm đó.
    @inline(__always) private static func keep(_ lq: [UInt8], _ cls: [UInt8], _ near: [UInt8], _ i: Int) -> Bool {
        if (cls[i] & 16) != 0 { return true }
        return near[i] != 0 && Int(lq[i]) < weakQ
    }

    @inline(__always) private static func idx(_ v: Float, _ scale: Int, _ n: Int) -> Int {
        let i = javaInt(v * Float(scale))
        if i < 0 { return 0 }
        if i >= n { return n - 1 }
        return i
    }

    /// Độ đậm phổ biến nhất (0..255) của tâm nét chữ trên trang: mốt của các điểm cực tiểu địa phương tối hơn 0,62
    /// (làm mượt +-5 mức); -1 nếu quá ít (trang trắng / ảnh).
    static func inkMode(_ lq: [UInt8], _ w: Int, _ h: Int) -> Int {
        if w < 3 || h < 3 { return -1 }
        var hist = [Int](repeating: 0, count: 256)
        var tot = 0
        for y in 1..<(h - 1) {
            let off = y * w
            for x in 1..<(w - 1) {
                let i = off + x
                let q = lq[i]
                if Int(q) >= modeLimitQ { continue }
                // chỉ cực tiểu địa phương (không lớn hơn 8 điểm lân cận)
                let up = i - w, dn = i + w
                if q > lq[i - 1] || q > lq[i + 1] || q > lq[up] || q > lq[dn] { continue }
                if q > lq[up - 1] || q > lq[up + 1] || q > lq[dn - 1] || q > lq[dn + 1] { continue }
                hist[Int(q)] += 1
                tot += 1
            }
        }
        if tot < 200 { return -1 }
        var best = -1, bi = 0
        for i in 0..<modeLimitQ {
            var s = 0
            for j in (i - 5)...(i + 5) where j >= 0 && j < modeLimitQ { s += hist[j] }
            if s > best {
                best = s
                bi = i
            }
        }
        return bi
    }

    /// Cụm nét nhạt có dạng chữ trên lưới ô d x d: thành phần liên thông 8 hướng của các ô có điểm tối hơn tc (ngưỡng hơi
    /// lỏng hơn ngưỡng lõi, để vệt nếp gấp đứt quãng nối liền lại) hoặc mực màu. Cụm được tính nếu gọn (cạnh khung <=
    /// maxDim điểm ảnh, cỡ chữ / từ) và không phải một vạch mảnh, thẳng, dài hơn longLine điểm ảnh (mảnh vệt nếp gấp).
    /// Trả về mảng ô (1 = thuộc cụm dạng chữ).
    static func clusters(_ lq: [UInt8], _ cls: [UInt8], _ w: Int, _ h: Int, _ tc: Int, _ d: Int, _ maxDim: Int,
                         _ longLine: Float) -> [UInt8] {
        let bw = (w + d - 1) / d, bh = (h + d - 1) / d
        let nb = bw * bh
        var blk = [UInt8](repeating: 0, count: nb)
        for y in 0..<h {
            let off = y * w, boff = (y / d) * bw
            for x in 0..<w where Int(lq[off + x]) < tc || (cls[off + x] & 1) != 0 {
                blk[boff + x / d] = 1
            }
        }
        var keepB = [UInt8](repeating: 0, count: nb)
        var seen = [UInt8](repeating: 0, count: nb)
        var queue = [Int](repeating: 0, count: nb)
        for start in 0..<nb {
            if blk[start] == 0 || seen[start] != 0 { continue }
            var head = 0, tail = 0
            queue[tail] = start
            tail += 1
            seen[start] = 1
            var minX = bw, maxX = -1, minY = bh, maxY = -1
            var sx = 0, sy = 0, sxx = 0, syy = 0, sxy = 0
            while head < tail {
                let i = queue[head]
                head += 1
                let x = i % bw, y = i / bw
                if x < minX { minX = x }
                if x > maxX { maxX = x }
                if y < minY { minY = y }
                if y > maxY { maxY = y }
                sx += x
                sy += y
                sxx += x * x
                syy += y * y
                sxy += x * y
                for dy in -1...1 {
                    let yy = y + dy
                    if yy < 0 || yy >= bh { continue }
                    for dx in -1...1 {
                        let xx = x + dx
                        if xx < 0 || xx >= bw { continue }
                        let j = yy * bw + xx
                        if blk[j] != 0 && seen[j] == 0 {
                            seen[j] = 1
                            queue[tail] = j
                            tail += 1
                        }
                    }
                }
            }
            let span = max(maxX - minX + 1, maxY - minY + 1)
            if span * d > maxDim { continue }
            if Float(span * d) > longLine {
                // vạch mảnh thẳng: phương sai theo hướng hẹp rất nhỏ so với hướng dài (tỷ số trị riêng > 30)
                let nq = Double(tail)
                let mx = Double(sx) / nq, my = Double(sy) / nq
                let cxx = Double(sxx) / nq - mx * mx, cyy = Double(syy) / nq - my * my, cxy = Double(sxy) / nq - mx * my
                let tr = cxx + cyy, det = cxx * cyy - cxy * cxy
                let disc = max(0.0, tr * tr * 0.25 - det).squareRoot()
                let l1 = tr * 0.5 + disc, l2 = tr * 0.5 - disc
                if l1 > 30.0 * max(l2, 0.0833) { continue }
            }
            for q in 0..<tail { keepB[queue[q]] = 1 }
        }
        return keepB
    }

    /// Ô thuộc vạch mảnh, thẳng, dài hơn longLine điểm ảnh (thành phần liên thông 8 hướng của các ô blk; tỷ số trị riêng >
    /// 30, như clusters). Trả về mảng ô (1 = thuộc vạch).
    static func lineBlocks(_ blk: [UInt8], _ bw: Int, _ bh: Int, _ d: Int, _ longLine: Float) -> [UInt8] {
        let nb = bw * bh
        var out = [UInt8](repeating: 0, count: nb)
        var seen = [UInt8](repeating: 0, count: nb)
        var queue = [Int](repeating: 0, count: nb)
        for start in 0..<nb {
            if blk[start] == 0 || seen[start] != 0 { continue }
            var head = 0, tail = 0
            queue[tail] = start
            tail += 1
            seen[start] = 1
            var minX = bw, maxX = -1, minY = bh, maxY = -1
            var sx = 0, sy = 0, sxx = 0, syy = 0, sxy = 0
            while head < tail {
                let i = queue[head]
                head += 1
                let x = i % bw, y = i / bw
                if x < minX { minX = x }
                if x > maxX { maxX = x }
                if y < minY { minY = y }
                if y > maxY { maxY = y }
                sx += x
                sy += y
                sxx += x * x
                syy += y * y
                sxy += x * y
                for dy in -1...1 {
                    let yy = y + dy
                    if yy < 0 || yy >= bh { continue }
                    for dx in -1...1 {
                        let xx = x + dx
                        if xx < 0 || xx >= bw { continue }
                        let j = yy * bw + xx
                        if blk[j] != 0 && seen[j] == 0 {
                            seen[j] = 1
                            queue[tail] = j
                            tail += 1
                        }
                    }
                }
            }
            let span = max(maxX - minX + 1, maxY - minY + 1)
            if Float(span * d) <= longLine { continue }
            let nq = Double(tail)
            let mx = Double(sx) / nq, my = Double(sy) / nq
            let cxx = Double(sxx) / nq - mx * mx, cyy = Double(syy) / nq - my * my, cxy = Double(sxy) / nq - mx * my
            let tr = cxx + cyy, det = cxx * cyy - cxy * cxy
            let disc = max(0.0, tr * tr * 0.25 - det).squareRoot()
            let l1 = tr * 0.5 + disc, l2 = tr * 0.5 - disc
            if l1 <= 30.0 * max(l2, 0.0833) { continue }
            for q in 0..<tail { out[queue[q]] = 1 }
        }
        return out
    }

    /// Lan 8 hướng, không giới hạn bước, từ các ô có mực màu rõ (col = 2) qua các ô hơi có màu (col = 1). Trả về mảng ô
    /// (1 = tới được), nil nếu không có ô mực màu rõ nào.
    static func colorReach(_ col: [UInt8], _ bw: Int, _ bh: Int) -> [UInt8]? {
        let nb = bw * bh
        var queue = [Int]()
        var out = [UInt8]()
        var tail = 0
        for i in 0..<nb where col[i] == 2 {
            if out.isEmpty {
                out = [UInt8](repeating: 0, count: nb)
                queue = [Int](repeating: 0, count: nb)
            }
            out[i] = 1
            queue[tail] = i
            tail += 1
        }
        if out.isEmpty { return nil }
        var head = 0
        while head < tail {
            let i = queue[head]
            head += 1
            let x = i % bw, y = i / bw
            for dy in -1...1 {
                let yy = y + dy
                if yy < 0 || yy >= bh { continue }
                for dx in -1...1 {
                    let xx = x + dx
                    if xx < 0 || xx >= bw { continue }
                    let j = yy * bw + xx
                    if col[j] == 0 || out[j] != 0 { continue }
                    out[j] = 1
                    queue[tail] = j
                    tail += 1
                }
            }
        }
        return out
    }

    /// Lan qua các ô có điểm hơi tối (bs >= 1), 8 hướng: từ ô lõi đậm (bs = 3) tối đa lim bước, từ ô lõi nhạt (bs = 2)
    /// tối đa limFaint bước. Trả về mảng ô (1 = tới được).
    static func grow(_ bs: [UInt8], _ bw: Int, _ bh: Int, _ lim: Int, _ limFaint: Int) -> [UInt8] {
        let nb = bw * bh
        var out = [UInt8](repeating: 0, count: nb)
        var dist = [UInt8](repeating: 0, count: nb)
        var queue = [Int](repeating: 0, count: nb)
        for pass in 0..<2 {
            let src: UInt8 = pass == 0 ? 3 : 2
            let lm = pass == 0 ? lim : limFaint
            var head = 0, tail = 0
            for i in 0..<nb {
                dist[i] = 0
                if bs[i] == src {
                    dist[i] = 1
                    queue[tail] = i
                    tail += 1
                }
            }
            while head < tail {
                let i = queue[head]
                head += 1
                out[i] = 1
                let dd = Int(dist[i])
                if dd > lm { continue }
                let x = i % bw, y = i / bw
                for dy in -1...1 {
                    let yy = y + dy
                    if yy < 0 || yy >= bh { continue }
                    for dx in -1...1 {
                        let xx = x + dx
                        if xx < 0 || xx >= bw { continue }
                        let j = yy * bw + xx
                        if bs[j] == 0 || dist[j] != 0 { continue }
                        // bản Java lưu (byte) (dd + 1) rồi đọc & 255: dd <= lim <= 250 nên vừa UInt8
                        dist[j] = UInt8(truncatingIfNeeded: dd + 1)
                        queue[tail] = j
                        tail += 1
                    }
                }
            }
        }
        return out
    }

    /// Chiều cao điển hình của ký tự (phân vị 60% chiều cao khung thành phần cỡ chữ); 0 nếu quá ít chữ. widthOut: bề
    /// rộng điển hình theo cùng cách (cỡ chữ khi trang xoay ngang), 0 nếu quá ít.
    static func textHeight(_ ink: [UInt8], _ w: Int, _ h: Int, _ lab: inout [Int], _ queue: inout [Int],
                           _ widthOut: inout Float) -> Float {
        let n = w * h
        var hist = [Int](repeating: 0, count: h + 1)
        var histW = [Int](repeating: 0, count: w + 1)
        var cnt = 0, cntW = 0
        for i in 0..<n { lab[i] = 0 }
        for start in 0..<n {
            if ink[start] == 0 || lab[start] != 0 { continue }
            var head = 0, tail = 0
            queue[tail] = start
            tail += 1
            lab[start] = 1
            var minX = w, maxX = -1, minY = h, maxY = -1
            while head < tail {
                let i = queue[head]
                head += 1
                let x = i % w, y = i / w
                if x < minX { minX = x }
                if x > maxX { maxX = x }
                if y < minY { minY = y }
                if y > maxY { maxY = y }
                for dy in -1...1 {
                    let yy = y + dy
                    if yy < 0 || yy >= h { continue }
                    for dx in -1...1 {
                        let xx = x + dx
                        if xx < 0 || xx >= w { continue }
                        let j = yy * w + xx
                        if ink[j] != 0 && lab[j] == 0 {
                            lab[j] = 1
                            queue[tail] = j
                            tail += 1
                        }
                    }
                }
            }
            let ch = maxY - minY + 1, cw = maxX - minX + 1
            if tail >= 6 && ch >= 4 && ch <= h / 10 && cw <= 4 * ch {
                hist[ch] += 1
                cnt += 1
            }
            if tail >= 6 && cw >= 4 && cw <= w / 10 && ch <= 4 * cw {
                histW[cw] += 1
                cntW += 1
            }
        }
        widthOut = 0
        if cntW >= 25 {
            let needW = (cntW * 6 + 9) / 10
            var accW = 0
            for v in 0...w {
                accW += histW[v]
                if accW >= needW {
                    widthOut = Float(v)
                    break
                }
            }
        }
        if cnt < 25 { return 0 }
        let need = (cnt * 6 + 9) / 10
        var acc = 0
        for v in 0...h {
            acc += hist[v]
            if acc >= need { return Float(v) }
        }
        return 0
    }

    /// Độ dài đoạn mực liên tục theo hàng (hr) và theo cột (vr) chứa mỗi điểm mực.
    static func runLengths(_ ink: [UInt8], _ w: Int, _ h: Int, _ hr: inout [Int], _ vr: inout [Int]) {
        for y in 0..<h {
            let off = y * w
            var x = 0
            while x < w {
                if ink[off + x] == 0 {
                    x += 1
                    continue
                }
                let s = x
                while x < w && ink[off + x] != 0 { x += 1 }
                for q in s..<x { hr[off + q] = x - s }
            }
        }
        for x in 0..<w {
            var y = 0
            while y < h {
                if ink[y * w + x] == 0 {
                    y += 1
                    continue
                }
                let s = y
                while y < h && ink[y * w + x] != 0 { y += 1 }
                for q in s..<y { vr[q * w + x] = y - s }
            }
        }
    }

    /// Sắp xếp chèn (mảng nhỏ).
    static func sortSmall(_ a: inout [Float], _ n: Int) {
        var i = 1
        while i < n {
            let v = a[i]
            var j = i - 1
            while j >= 0 && a[j] > v {
                a[j + 1] = a[j]
                j -= 1
            }
            a[j + 1] = v
            i += 1
        }
    }

    /// Dòng chữ: chia trang thành các dải dọc hẹp, tìm đỉnh của lược đồ chiếu ngang trong mỗi dải (tâm dòng chữ),
    /// rồi nối đỉnh của các dải liền kề thành đường (dự đoán theo độ dốc). Trả về số dòng chữ tìm được.
    static func textLines(_ ink: [UInt8], _ w: Int, _ h: Int, _ hc: Float, _ out: Lines) -> Int {
        let sw = max(8, min(48, Int((2.5 * hc).rounded())))
        let strd = max(4, sw / 2)
        let ns = (w - sw) / strd + 1
        if ns < 3 { return 0 }
        let m = max(1, Int((hc / 3).rounded()))
        let nmsR = max(2, Int((0.4 * hc).rounded()))
        let win = 2 * m + 1
        let minP = max(1, (sw * win * win * 6 + 50) / 100)
        var prof = [Int](repeating: 0, count: h), t1 = [Int](repeating: 0, count: h), t2 = [Int](repeating: 0, count: h)
        // đỉnh: (dải, y) — bản Java còn lưu độ mạnh của đỉnh nhưng không dùng tới
        var pS = [Int](), pY = [Float]()
        var np = 0
        var pFirst = [Int](repeating: 0, count: ns + 1)
        for s in 0..<ns {
            pFirst[s] = np
            let x0 = s * strd
            for y in 0..<h {
                var c = 0
                let off = y * w + x0
                for x in 0..<sw where ink[off + x] != 0 { c += 1 }
                prof[y] = c
            }
            boxSum(prof, &t1, h, m)
            boxSum(t1, &t2, h, m)
            for y in stride(from: 1, to: h - 1, by: 1) {
                let v = t2[y]
                if v < minP || v <= t2[y - 1] || v < t2[y + 1] { continue }
                var top = true
                var qq = max(0, y - nmsR)
                let qEnd = min(h - 1, y + nmsR)
                while qq <= qEnd && top {
                    if t2[qq] > v || (t2[qq] == v && qq < y) { top = false }
                    qq += 1
                }
                if !top { continue }
                // vị trí dòng = mép dưới của dải chữ (lược đồ giảm còn nửa đỉnh, gần chân chữ): ổn định hơn vị trí
                // cực đại với chữ to (nét ngang / dấu thanh tạo nhiều đỉnh phụ); không tới được nửa đỉnh thì lấy đỉnh
                var cy = Float(y)
                let half = v / 2
                for q in (y + 1)..<h {
                    if t2[q] > t2[q - 1] { break }
                    if t2[q] <= half {
                        cy = Float(q - 1) + Float(t2[q - 1] - half) / Float(t2[q - 1] - t2[q])
                        break
                    }
                }
                pS.append(s)
                pY.append(cy)
                np += 1
            }
        }
        pFirst[ns] = np
        // khoảng cách dòng: trung vị khoảng cách giữa hai đỉnh kề nhau trong cùng dải
        var dh = [Int](repeating: 0, count: h + 1)
        var dc = 0
        for i in stride(from: 0, to: np - 1, by: 1) {
            if pS[i + 1] != pS[i] { continue }
            let d = pY[i + 1] - pY[i]
            if d < hc || d > 5 * hc { continue }
            dh[javaInt(d + 0.5)] += 1
            dc += 1
        }
        var pitch: Float = 1.8 * hc
        if dc >= 10 {
            var acc = 0
            for v in 0...h {
                acc += dh[v]
                if acc * 2 >= dc {
                    pitch = Float(v)
                    break
                }
            }
        }
        out.pitch = pitch
        // dung sai nối đỉnh: nhỏ hơn khoảng cách tới hàng dấu thanh của chữ to (tránh nối nhầm zíc zắc)
        let tol = min(0.35 * pitch, 0.7 * hc)
        // nối đỉnh thành dòng: dòng đang mở = (x cuối, y cuối, độ dốc, dải cuối)
        var act = [Int](repeating: 0, count: np + 1)
        var ax = [Float](repeating: 0, count: np + 1), ay = [Float](repeating: 0, count: np + 1)
        var asl = [Float](repeating: 0, count: np + 1)
        var aLast = [Int](repeating: 0, count: np + 1)
        var aLine = [Int](repeating: 0, count: np + 1)
        var aCnt = [Int](repeating: 0, count: np + 1)
        var na = 0
        // mẫu tạm của từng dòng (theo chỉ số dòng tạm), chỉ đưa vào out nếu đủ dài
        var sLine = [Int](repeating: 0, count: np)
        var sX = [Float](repeating: 0, count: np), sY = [Float](repeating: 0, count: np)
        var nsamp = 0
        var nTmp = 0
        var keys = [Int64]()
        var usedA = [Bool](repeating: false, count: np + 1)
        var usedP = [Bool](repeating: false, count: np + 1)
        for s in 0..<ns {
            let xc = Float(s * strd) + Float(sw - 1) / 2
            let p0 = pFirst[s], p1 = pFirst[s + 1]
            // ghép cặp (dòng đang mở, đỉnh) theo sai lệch dự đoán tăng dần
            keys.removeAll(keepingCapacity: true)
            for a in 0..<na {
                let li = act[a]
                if s - aLast[li] > 3 { continue }
                let pred = ay[li] + asl[li] * (xc - ax[li])
                for p in p0..<p1 {
                    let d = abs(pY[p] - pred)
                    if d > tol { continue }
                    keys.append((Int64(javaInt(d * 1024)) << 40) | (Int64(a) << 20) | Int64(p - p0))
                }
            }
            keys.sort()
            let nk = keys.count
            for a in 0..<na { usedA[a] = false }
            for p in p0..<p1 { usedP[p - p0] = false }
            for q in 0..<nk {
                let a = Int((keys[q] >> 20) & 0xFFFFF), p = Int(keys[q] & 0xFFFFF)
                if usedA[a] || usedP[p] { continue }
                usedA[a] = true
                usedP[p] = true
                let li = act[a]
                let y = pY[p0 + p]
                var sl = (y - ay[li]) / (xc - ax[li])
                if aCnt[li] > 1 { sl = 0.5 * asl[li] + 0.5 * sl }
                if sl > 0.5 { sl = 0.5 }
                if sl < -0.5 { sl = -0.5 }
                asl[li] = sl
                ax[li] = xc
                ay[li] = y
                aLast[li] = s
                aCnt[li] += 1
                sLine[nsamp] = aLine[li]
                sX[nsamp] = xc
                sY[nsamp] = y
                nsamp += 1
            }
            for p in p0..<p1 {
                if usedP[p - p0] { continue }
                let li = na
                act[na] = li
                na += 1
                ax[li] = xc
                ay[li] = pY[p]
                asl[li] = 0
                aLast[li] = s
                aCnt[li] = 1
                aLine[li] = nTmp
                nTmp += 1
                sLine[nsamp] = aLine[li]
                sX[nsamp] = xc
                sY[nsamp] = pY[p]
                nsamp += 1
            }
        }
        // giữ dòng có >= 3 mẫu, đánh lại chỉ số
        var cnt = [Int](repeating: 0, count: nTmp)
        for i in 0..<nsamp { cnt[sLine[i]] += 1 }
        var map = [Int](repeating: -1, count: nTmp)
        var lines = 0
        for t in 0..<nTmp where cnt[t] >= 3 {
            map[t] = out.newLine()
            lines += 1
        }
        for i in 0..<nsamp {
            let t = map[sLine[i]]
            if t >= 0 { out.add(t, sX[i], sY[i], 1) }
        }
        return lines
    }

    /// Điểm "giống dòng chữ" của tập đường: số mẫu trung bình mỗi đường chia cho độ gồ ghề (trung bình |sai phân bậc hai|
    /// của vị trí dọc theo từng đường, tối thiểu 0,05). Hướng đúng của trang cho đường dài và mượt; 0 nếu không đủ mẫu.
    static func lineScore(_ ln: Lines) -> Double {
        if ln.count == 0 || ln.n == 0 { return 0 }
        var p1 = [Float](repeating: 0, count: ln.count), p2 = [Float](repeating: 0, count: ln.count)
        var seen = [Int](repeating: 0, count: ln.count)
        var sum = 0.0
        var rc = 0
        for i in 0..<ln.n {
            let j = ln.line[i]
            let y = ln.ys[i]
            if seen[j] >= 2 {
                let d2: Float = y - 2 * p1[j] + p2[j]
                sum += Double(abs(d2))
                rc += 1
            }
            p2[j] = p1[j]
            p1[j] = y
            seen[j] += 1
        }
        if rc == 0 { return 0 }
        return (Double(ln.n) / Double(ln.count)) / max(0.05, sum / Double(rc))
    }

    /// Tổng trượt cửa sổ [i - r, i + r] (ngoài mảng = 0).
    static func boxSum(_ a: [Int], _ o: inout [Int], _ n: Int, _ r: Int) {
        var s = 0
        var k = 0
        while k <= r && k < n {
            s += a[k]
            k += 1
        }
        for i in 0..<n {
            o[i] = s
            let outIdx = i - r, inIdx = i + r + 1
            if outIdx >= 0 { s -= a[outIdx] }
            if inIdx < n { s += a[inIdx] }
        }
    }

    // MARK: khớp trường trên lưới

    /// Tập mẫu của các đường (dòng chữ / đường kẻ): mẫu i thuộc đường line[i], toạ độ (xs, ys), trọng số wt.
    final class Lines {
        var count = 0
        var n = 0
        var line = [Int]()
        var xs = [Float](), ys = [Float](), wt = [Float]()
        /// Vị trí đích (hằng số) của từng đường sau khi khớp.
        var cs: [Float]? = nil
        /// Khoảng cách dòng chữ (điểm ảnh phân tích).
        var pitch: Float = 0
        /// Tỷ lệ trọng số mẫu còn được dùng sau lần khớp gần nhất.
        var kept: Float = 1

        func newLine() -> Int {
            let c = count
            count += 1
            return c
        }

        func add(_ ln: Int, _ x: Float, _ y: Float, _ weight: Float) {
            line.append(ln)
            xs.append(x)
            ys.append(y)
            wt.append(weight)
            n += 1
        }

        /// Thêm toàn bộ mẫu của o (đánh lại chỉ số đường nối tiếp); swap: đổi x <-> y (ảnh chuyển vị).
        func append(_ o: Lines, _ swap: Bool) {
            let base = count
            for i in 0..<o.n { add(base + o.line[i], swap ? o.ys[i] : o.xs[i], swap ? o.xs[i] : o.ys[i], o.wt[i]) }
            count += o.count
        }
    }

    /// Giá trị trường D (lưới gw x gh, bước gs) tại (u, v), nội suy song tuyến tính, ngoài lưới lấy nút biên.
    static func interp(_ D: [Double], _ gw: Int, _ gh: Int, _ gs: Float, _ u: Float, _ v: Float) -> Double {
        let fu = u / gs, fv = v / gs
        var i0 = floorI(fu), j0 = floorI(fv)
        var tu = fu - Float(i0), tv = fv - Float(j0)
        if i0 < 0 { i0 = 0; tu = 0 }
        if i0 > gw - 2 { i0 = gw - 2; tu = 1 }
        if j0 < 0 { j0 = 0; tv = 0 }
        if j0 > gh - 2 { j0 = gh - 2; tv = 1 }
        let n = j0 * gw + i0
        // (1 - tu) tính bằng float rồi mới đổi sang double, như bản Java
        let a = D[n] * Double(1 - tu) + D[n + 1] * Double(tu)
        let b = D[n + gw] * Double(1 - tu) + D[n + gw + 1] * Double(tu)
        return a * Double(1 - tv) + b * Double(tv)
    }

    /// Toạ độ dọc trên ảnh ra của điểm (x, y) ảnh vào theo trường dọc dyF: v + dy(x, v) = y (lặp điểm bất động).
    static func outV(_ dyF: [Double], _ gw: Int, _ gh: Int, _ gs: Float, _ x: Float, _ y: Float) -> Float {
        var v = y
        for _ in 0..<3 { v = y - Float(interp(dyF, gw, gh, gs, x, v)) }
        return v
    }

    /// Khớp trường một chiều D trên lưới cho tập đường ln: với mẫu i của đường j,
    /// D(pu, pv) + c[j] = toạ độ quan sát (ys nếu horiz, xs nếu không). Vị trí trên ảnh ra: đường ngang (horiz) có
    /// pu = pos[i] (truyền vào), pv = c[j]; đường dọc có pu = c[j], pv = pos[i]. Lặp 3 lần (cập nhật vị trí, loại
    /// mẫu lệch). Kết quả: D (đã khởi đầu từ giá trị truyền vào) và ln.cs.
    static func fitLines(_ ln: Lines, _ gw: Int, _ gh: Int, _ gs: Int, _ pos: [Float], _ D: inout [Double], _ horiz: Bool) {
        let n = ln.n, nl = ln.count
        let obs = horiz ? ln.ys : ln.xs
        let line = ln.line
        var wt = Array(ln.wt[0..<n])
        var c = [Double](repeating: 0, count: nl)
        var pu = [Float](repeating: 0, count: n), pv = [Float](repeating: 0, count: n)
        let fgs = Float(gs)
        // khởi đầu: hằng số của đường = trung bình (quan sát - D)
        meanOffsets(ln, D, gw, gh, gs, pos, obs, wt, &c, horiz)
        for outer in 0..<3 {
            for i in 0..<n {
                if horiz {
                    pu[i] = pos[i]
                    pv[i] = Float(c[line[i]])
                } else {
                    pu[i] = Float(c[line[i]])
                    pv[i] = pos[i]
                }
            }
            solve(gw, gh, fgs, nl, line, pu, pv, obs, wt, n, &D, &c)
            if outer == 2 { break }
            // loại mẫu lệch nhiều (dò nhầm, chữ to / hình): ngưỡng theo trung vị độ lệch tuyệt đối
            var res = [Float]()
            res.reserveCapacity(n)
            for i in 0..<n {
                if wt[i] <= 0 { continue }
                let e = interp(D, gw, gh, fgs, pu[i], pv[i]) + c[line[i]] - Double(obs[i])
                res.append(Float(abs(e)))
            }
            let m = res.count
            if m == 0 { break }
            res.sort()
            let med = res[m / 2]
            let thr: Float = max(1, 5 * med)
            var se = [Double](repeating: 0, count: nl)
            var cnt = [Int](repeating: 0, count: nl)
            for i in 0..<n {
                if wt[i] <= 0 { continue }
                let e = interp(D, gw, gh, fgs, pu[i], pv[i]) + c[line[i]] - Double(obs[i])
                if abs(e) > Double(thr) {
                    wt[i] = 0
                } else {
                    se[line[i]] += e * e
                    cnt[line[i]] += 1
                }
            }
            // loại cả đường không thể làm thẳng nhất quán (con dấu, chữ ký, hình vẽ, nối nhầm): sai số trung bình lớn
            var rms = [Float]()
            for j in 0..<nl where cnt[j] >= 3 { rms.append(Float((se[j] / Double(cnt[j])).squareRoot())) }
            let nr = rms.count
            if nr == 0 { break }
            rms.sort()
            let lim: Float = max(lineLim, 3 * rms[nr / 2])
            for i in 0..<n {
                let j = line[i]
                if cnt[j] < 3 || (se[j] / Double(cnt[j])).squareRoot() > Double(lim) { wt[i] = 0 }
            }
        }
        var cs = [Float](repeating: 0, count: nl)
        for j in 0..<nl { cs[j] = Float(c[j]) }
        ln.cs = cs
        var w0 = 0.0, w1 = 0.0
        for i in 0..<n {
            w0 += Double(ln.wt[i])
            w1 += Double(wt[i])
        }
        ln.kept = w0 > 0 ? Float(w1 / w0) : 0
    }

    static func meanOffsets(_ ln: Lines, _ D: [Double], _ gw: Int, _ gh: Int, _ gs: Int, _ pos: [Float], _ obs: [Float],
                            _ wt: [Float], _ c: inout [Double], _ horiz: Bool) {
        let nl = ln.count
        let fgs = Float(gs)
        var sw = [Double](repeating: 0, count: nl)
        for j in 0..<nl { c[j] = 0 }
        for i in 0..<ln.n {
            let j = ln.line[i]
            c[j] += Double(obs[i]) * Double(wt[i])
            sw[j] += Double(wt[i])
        }
        for j in 0..<nl { c[j] = sw[j] > 0 ? c[j] / sw[j] : 0 }
        // trừ D tại vị trí xấp xỉ
        var s2 = [Double](repeating: 0, count: nl)
        for i in 0..<ln.n {
            let j = ln.line[i]
            let u = horiz ? pos[i] : Float(c[j]), v = horiz ? Float(c[j]) : pos[i]
            s2[j] += (Double(obs[i]) - interp(D, gw, gh, fgs, u, v)) * Double(wt[i])
        }
        for j in 0..<nl { c[j] = sw[j] > 0 ? s2[j] / sw[j] : c[j] }
    }

    /// Gộp các đoạn dòng chữ (chỉ số >= first) nằm trên cùng một dòng nhưng cách quãng xa: vị trí đích sau khi khớp
    /// chênh nhau <= tol và không chồng nhau theo chiều ngang (cho phép chồng <= overlap). Trả về true nếu có gộp.
    static func mergeLines(_ ln: Lines, _ first: Int, _ tol: Float, _ overlap: Float) -> Bool {
        let nl = ln.count
        guard let cs = ln.cs, nl - first >= 2 else { return false }
        var x0 = [Float](repeating: Float.greatestFiniteMagnitude, count: nl)
        var x1 = [Float](repeating: -Float.greatestFiniteMagnitude, count: nl)
        for i in 0..<ln.n {
            let j = ln.line[i]
            if ln.xs[i] < x0[j] { x0[j] = ln.xs[i] }
            if ln.xs[i] > x1[j] { x1[j] = ln.xs[i] }
        }
        var root = [Int](repeating: 0, count: nl)
        for j in 0..<nl { root[j] = j }
        var any = false
        for a in first..<nl {
            if x1[a] < x0[a] { continue }
            for b in (a + 1)..<nl {
                if x1[b] < x0[b] || root[a] == root[b] { continue }
                if abs(cs[a] - cs[b]) > tol { continue }
                let ra = root[a], rb = root[b]
                var ok = true
                var p = first
                while p < nl && ok {
                    if root[p] == ra {
                        var q = first
                        while q < nl && ok {
                            if root[q] == rb {
                                if abs(cs[p] - cs[q]) > tol {
                                    ok = false
                                } else if min(x1[p], x1[q]) - max(x0[p], x0[q]) > overlap {
                                    ok = false
                                }
                            }
                            q += 1
                        }
                    }
                    p += 1
                }
                if !ok { continue }
                for q in first..<nl where root[q] == rb { root[q] = ra }
                any = true
            }
        }
        if !any { return false }
        // đánh lại chỉ số liên tiếp theo thứ tự xuất hiện
        var map = [Int](repeating: -1, count: nl)
        var m = 0
        for j in 0..<nl {
            let r = root[j]
            if map[r] < 0 {
                map[r] = m
                m += 1
            }
        }
        for i in 0..<ln.n { ln.line[i] = map[root[ln.line[i]]] }
        ln.count = m
        return true
    }

    /// Giải bình phương tối thiểu có điều chuẩn bằng gradient liên hợp, ẩn z = [D (gw*gh nút); c (nl hằng số)]:
    /// min sum wt*(B D + c[line] - obs)^2 + lambdaBend*|uốn D|^2 + lambdaStretch*|grad D|^2 + mu*|D|^2.
    static func solve(_ gw: Int, _ gh: Int, _ gs: Float, _ nl: Int, _ line: [Int], _ pu: [Float], _ pv: [Float],
                      _ obs: [Float], _ wt: [Float], _ n: Int, _ D: inout [Double], _ c: inout [Double]) {
        let N = gw * gh
        let Z = N + nl
        var nd = [Int](repeating: 0, count: n)
        var w00 = [Double](repeating: 0, count: n), w10 = [Double](repeating: 0, count: n)
        var w01 = [Double](repeating: 0, count: n), w11 = [Double](repeating: 0, count: n)
        for i in 0..<n {
            let fu = pu[i] / gs, fv = pv[i] / gs
            var i0 = floorI(fu), j0 = floorI(fv)
            var tu = fu - Float(i0), tv = fv - Float(j0)
            if i0 < 0 { i0 = 0; tu = 0 }
            if i0 > gw - 2 { i0 = gw - 2; tu = 1 }
            if j0 < 0 { j0 = 0; tv = 0 }
            if j0 > gh - 2 { j0 = gh - 2; tv = 1 }
            nd[i] = j0 * gw + i0
            let du = Double(tu), dv = Double(tv)
            w00[i] = (1.0 - du) * (1.0 - dv)
            w10[i] = du * (1.0 - dv)
            w01[i] = (1.0 - du) * dv
            w11[i] = du * dv
        }
        var z = [Double](repeating: 0, count: Z), b = [Double](repeating: 0, count: Z)
        var r = [Double](repeating: 0, count: Z), p = [Double](repeating: 0, count: Z), q = [Double](repeating: 0, count: Z)
        for i in 0..<N { z[i] = D[i] }
        for j in 0..<nl { z[N + j] = c[j] }
        for i in 0..<n {
            if wt[i] <= 0 { continue }
            let o = Double(obs[i]) * Double(wt[i])
            let a = nd[i]
            b[a] += w00[i] * o
            b[a + 1] += w10[i] * o
            b[a + gw] += w01[i] * o
            b[a + gw + 1] += w11[i] * o
            b[N + line[i]] += o
        }
        mul(z, &q, gw, gh, nl, line, nd, w00, w10, w01, w11, wt, n)
        var rr = 0.0, bb = 0.0
        for i in 0..<Z {
            r[i] = b[i] - q[i]
            p[i] = r[i]
            rr += r[i] * r[i]
            bb += b[i] * b[i]
        }
        let tol = bb * 1e-14
        var it = 0
        while it < cgIters && rr > tol {
            mul(p, &q, gw, gh, nl, line, nd, w00, w10, w01, w11, wt, n)
            var pq = 0.0
            for i in 0..<Z { pq += p[i] * q[i] }
            if !(pq > 0) { break }
            let alpha = rr / pq
            var rn = 0.0
            for i in 0..<Z {
                z[i] += alpha * p[i]
                r[i] -= alpha * q[i]
                rn += r[i] * r[i]
            }
            let beta = rn / rr
            rr = rn
            for i in 0..<Z { p[i] = r[i] + beta * p[i] }
            it += 1
        }
        for i in 0..<N { D[i] = z[i] }
        for j in 0..<nl { c[j] = z[N + j] }
    }

    /// q = A z (ma trận chuẩn của bài toán bình phương tối thiểu trong solve).
    static func mul(_ z: [Double], _ q: inout [Double], _ gw: Int, _ gh: Int, _ nl: Int, _ line: [Int], _ nd: [Int],
                    _ w00: [Double], _ w10: [Double], _ w01: [Double], _ w11: [Double], _ wt: [Float], _ n: Int) {
        let N = gw * gh
        let Z = N + nl
        for i in 0..<Z { q[i] = 0 }
        for i in 0..<n {
            if wt[i] <= 0 { continue }
            let a = nd[i]
            var s = w00[i] * z[a] + w10[i] * z[a + 1] + w01[i] * z[a + gw] + w11[i] * z[a + gw + 1] + z[N + line[i]]
            s *= Double(wt[i])
            q[a] += w00[i] * s
            q[a + 1] += w10[i] * s
            q[a + gw] += w01[i] * s
            q[a + gw + 1] += w11[i] * s
            q[N + line[i]] += s
        }
        let lb = lambdaBend, ls = lambdaStretch
        for j in 0..<gh {
            let o = j * gw
            for i in stride(from: 1, to: gw - 1, by: 1) {
                let t = (z[o + i - 1] - 2 * z[o + i] + z[o + i + 1]) * lb
                q[o + i - 1] += t
                q[o + i] -= 2 * t
                q[o + i + 1] += t
            }
            for i in stride(from: 0, to: gw - 1, by: 1) {
                let t = (z[o + i + 1] - z[o + i]) * ls
                q[o + i] -= t
                q[o + i + 1] += t
            }
        }
        for j in stride(from: 1, to: gh - 1, by: 1) {
            for i in 0..<gw {
                let o = j * gw + i
                let t = (z[o - gw] - 2 * z[o] + z[o + gw]) * lb
                q[o - gw] += t
                q[o] -= 2 * t
                q[o + gw] += t
            }
        }
        for j in stride(from: 0, to: gh - 1, by: 1) {
            for i in 0..<gw {
                let o = j * gw + i
                let t = (z[o + gw] - z[o]) * ls
                q[o] -= t
                q[o + gw] += t
            }
        }
        for j in stride(from: 0, to: gh - 1, by: 1) {
            for i in stride(from: 0, to: gw - 1, by: 1) {
                let o = j * gw + i
                let t = (z[o] - z[o + 1] - z[o + gw] + z[o + gw + 1]) * (2 * lb)
                q[o] += t
                q[o + 1] -= t
                q[o + gw] -= t
                q[o + gw + 1] += t
            }
        }
        for i in 0..<N { q[i] += mu * z[i] }
        for j in 0..<nl { q[N + j] += 1e-9 * z[N + j] }
    }

    /// Ánh xạ (u, v) -> (u + s*dx, v + s*dy) không gập: định thức Jacobi trên mọi ô lưới trong [0,5; 2].
    static func foldFree(_ dx: [Double], _ dy: [Double], _ gw: Int, _ gh: Int, _ gs: Int, _ s: Double) -> Bool {
        let g = Double(gs)
        for j in stride(from: 0, to: gh - 1, by: 1) {
            for i in stride(from: 0, to: gw - 1, by: 1) {
                let o = j * gw + i
                let xu = (dx[o + 1] - dx[o]) * s / g, xv = (dx[o + gw] - dx[o]) * s / g
                let yu = (dy[o + 1] - dy[o]) * s / g, yv = (dy[o + gw] - dy[o]) * s / g
                let det = (1 + xu) * (1 + yv) - xv * yu
                if det < 0.5 || det > 2.0 { return false }
            }
        }
        return true
    }

    // MARK: nắn ảnh

    /// Nắn ảnh theo trường f (song tuyến tính, điểm lấy mẫu ngoài ảnh = trắng). Đọc từ bản sao liền (w*4 byte mỗi hàng)
    /// của ảnh vào, ghi từng hàng tại px + y*bpr (byte đệm cuối hàng giữ nguyên).
    static func remap(_ px: UnsafeMutablePointer<UInt8>, _ w: Int, _ h: Int, _ bpr: Int, _ f: Field) {
        if w < 2 || h < 2 { return }
        let gw = f.gw, gh = f.gh
        if gw < 2 || gh < 2 || f.dx.count < gw * gh || f.dy.count < gw * gh || !(f.step > 0) { return }
        let rowBytes = w * 4
        let src = UnsafeMutablePointer<UInt8>.allocate(capacity: rowBytes * h)
        defer { src.deallocate() }
        for y in 0..<h { memcpy(src + y * rowBytes, px + y * bpr, rowBytes) }
        let fdx = f.dx, fdy = f.dy
        let ox = f.ox
        var rdx = [Float](repeating: 0, count: gw), rdy = [Float](repeating: 0, count: gw)
        let inv = 1 / f.step
        for v in 0..<h {
            let fv = (Float(v) - ox) * inv
            var j0 = floorI(fv)
            var tv = fv - Float(j0)
            if j0 < 0 { j0 = 0; tv = 0 }
            if j0 > gh - 2 { j0 = gh - 2; tv = 1 }
            let o0 = j0 * gw, o1 = o0 + gw
            for i in 0..<gw {
                rdx[i] = fdx[o0 + i] * (1 - tv) + fdx[o1 + i] * tv
                rdy[i] = fdy[o0 + i] * (1 - tv) + fdy[o1 + i] * tv
            }
            let row = px + v * bpr
            for u in 0..<w {
                let fu = (Float(u) - ox) * inv
                var i0 = floorI(fu)
                var tu = fu - Float(i0)
                if i0 < 0 { i0 = 0; tu = 0 }
                if i0 > gw - 2 { i0 = gw - 2; tu = 1 }
                let sx = Float(u) + (rdx[i0] * (1 - tu) + rdx[i0 + 1] * tu)
                let sy = Float(v) + (rdy[i0] * (1 - tu) + rdy[i0 + 1] * tu)
                sample(src, w, h, sx, sy, row + u * 4)
            }
        }
    }

    /// Lấy mẫu song tuyến tính (trọng số 8 bit) từ ảnh liền src (w*4 byte mỗi hàng), ngoài ảnh = trắng; ghi RGBA vào out.
    @inline(__always)
    private static func sample(_ src: UnsafePointer<UInt8>, _ w: Int, _ h: Int, _ sx: Float, _ sy: Float,
                               _ out: UnsafeMutablePointer<UInt8>) {
        if !(sx > -1 && sy > -1 && sx < Float(w) && sy < Float(h)) {
            out[0] = 255; out[1] = 255; out[2] = 255; out[3] = 255
            return
        }
        let x0 = floorI(sx), y0 = floorI(sy)
        var ax = javaInt((sx - Float(x0)) * 256), ay = javaInt((sy - Float(y0)) * 256)
        if ax > 256 { ax = 256 }
        if ay > 256 { ay = 256 }
        for ch in 0..<3 {
            let a = pick(src, w, h, x0, y0, ch), b = pick(src, w, h, x0 + 1, y0, ch)
            let c = pick(src, w, h, x0, y0 + 1, ch), d = pick(src, w, h, x0 + 1, y0 + 1, ch)
            let top = a * (256 - ax) + b * ax, bot = c * (256 - ax) + d * ax
            let v = (top * (256 - ay) + bot * ay + 32768) >> 16
            out[ch] = UInt8(truncatingIfNeeded: v)
        }
        out[3] = 255
    }

    /// Kênh ch (0 R, 1 G, 2 B) của điểm (x, y) trong ảnh liền src; ngoài ảnh = trắng.
    @inline(__always)
    private static func pick(_ src: UnsafePointer<UInt8>, _ w: Int, _ h: Int, _ x: Int, _ y: Int, _ ch: Int) -> Int {
        if x < 0 || y < 0 || x >= w || y >= h { return 255 }
        return Int(src[(y * w + x) * 4 + ch])
    }
}
