// Kiểm thử phần thuật toán Swift (DocDetector, Enhancer, Geom, PdfWriter) trên Linux — không cần UIKit.
//
// Biên dịch:
//   swiftc -O -o algotest ios/Sources/DocDetector.swift ios/Sources/Enhancer.swift ios/Sources/Geom.swift \
//          ios/Sources/PdfWriter.swift ios/Tests/linux/main.swift
// Cách chạy:
//   ./algotest tests/images/gt.txt <thư-mục-ppm>     so với toạ độ góc thật + bộ lọc + PDF
//   ./algotest --dump <dữ-liệu> <kết-quả>            xuất kết quả để đối chiếu với bản Java (DumpCore.java)
//   ./algotest --fuzz [số-vòng] [thư-mục-PDF]        kiểm thử độ bền (ảnh nhỏ/dẹt/nhiễu, tứ giác suy biến, JPEG hỏng)
import Foundation

func readPPM(_ path: String) -> (w: Int, h: Int, rgba: [UInt8])? {
    guard let d = FileManager.default.contents(atPath: path) else { return nil }
    let b = [UInt8](d)
    var pos = 0
    func token() -> String {
        while pos < b.count {
            if b[pos] == 0x23 { while pos < b.count && b[pos] != 0x0A { pos += 1 } }  // chú thích
            else if b[pos] == 0x20 || b[pos] == 0x0A || b[pos] == 0x0D || b[pos] == 0x09 { pos += 1 }
            else { break }
        }
        var s = ""
        while pos < b.count, !(b[pos] == 0x20 || b[pos] == 0x0A || b[pos] == 0x0D || b[pos] == 0x09) {
            s.append(Character(UnicodeScalar(b[pos])))
            pos += 1
        }
        return s
    }
    guard token() == "P6", let w = Int(token()), let h = Int(token()), Int(token()) == 255 else { return nil }
    pos += 1
    guard w > 0, h > 0, b.count - pos >= w * h * 3 else { return nil }
    var rgba = [UInt8](repeating: 255, count: w * h * 4)
    for i in 0..<(w * h) {
        rgba[4 * i] = b[pos + 3 * i]
        rgba[4 * i + 1] = b[pos + 3 * i + 1]
        rgba[4 * i + 2] = b[pos + 3 * i + 2]
    }
    return (w, h, rgba)
}

func writePPM(_ path: String, _ rgba: [UInt8], _ w: Int, _ h: Int) {
    var d = Data("P6\n\(w) \(h)\n255\n".utf8)
    var rgb = [UInt8](repeating: 0, count: w * h * 3)
    for i in 0..<(w * h) {
        rgb[3 * i] = rgba[4 * i]
        rgb[3 * i + 1] = rgba[4 * i + 1]
        rgb[3 * i + 2] = rgba[4 * i + 2]
    }
    d.append(contentsOf: rgb)
    FileManager.default.createFile(atPath: path, contents: d)
}

func detect(_ rgba: [UInt8], _ w: Int, _ h: Int) -> DocDetector.Result? {
    return rgba.withUnsafeBufferPointer { buf -> DocDetector.Result? in
        guard let base = buf.baseAddress else { return nil }
        return DocDetector.detect(rgba: base, width: w, height: h, bytesPerRow: w * 4)
    }
}

func enhance(_ rgba: [UInt8], _ w: Int, _ h: Int, _ mode: Int) -> [UInt8] {
    var px = rgba
    px.withUnsafeMutableBufferPointer { buf in
        if let base = buf.baseAddress { Enhancer.apply(base, width: w, height: h, bytesPerRow: w * 4, mode: mode) }
    }
    return px
}

func f(_ v: Double, _ digits: Int) -> String {
    return String(format: "%.\(digits)f", locale: Locale(identifier: "en_US_POSIX"), v)
}

let pdfTitle = "Biên bản thử nghiệm số 12 — ấệữ 😀"

// MARK: --dump

func runDump(_ inDir: String, _ outDir: String) -> Int32 {
    let fm = FileManager.default
    try? fm.createDirectory(atPath: outDir, withIntermediateDirectories: true)
    var lines = [String]()
    guard let all = try? fm.contentsOfDirectory(atPath: inDir) else {
        print("không đọc được \(inDir)")
        return 2
    }
    var tDet = 0.0, tEnh = 0.0
    var nImg = 0
    for n in all.sorted() where n.hasSuffix(".ppm") {
        let name = String(n.dropLast(4))
        guard let img = readPPM(inDir + "/" + n) else {
            lines.append("det \(name) 0 0 unreadable")
            continue
        }
        nImg += 1
        var t0 = Date()
        let r = detect(img.rgba, img.w, img.h)
        tDet += Date().timeIntervalSince(t0)
        var s = "det \(name) \(img.w) \(img.h)"
        if let r = r {
            if let q = r.quad {
                s += " quad"
                for v in q { s += " " + f(Double(v), 3) }
                s += " " + f(Double(r.score), 4) + " " + (r.refined ? "1" : "0")
            } else {
                s += " none " + f(Double(r.score), 4)
            }
        } else {
            s += " null"
        }
        lines.append(s)
        let full = name.hasPrefix("full_")
        for mode in [Enhancer.magic, Enhancer.lighten, Enhancer.gray, Enhancer.bw] {
            t0 = Date()
            let c = enhance(img.rgba, img.w, img.h, mode)
            tEnh += Date().timeIntervalSince(t0)
            var sr = 0, sg = 0, sb = 0
            var i = 0
            while i < c.count {
                sr += Int(c[i])
                sg += Int(c[i + 1])
                sb += Int(c[i + 2])
                i += 4
            }
            lines.append("enh \(name) \(mode) \(sr) \(sg) \(sb)")
            if full { writePPM("\(outDir)/\(name)_m\(mode).ppm", c, img.w, img.h) }
        }
    }
    // Geom
    if let text = try? String(contentsOfFile: inDir + "/quads.txt", encoding: .utf8) {
        var qi = 0
        for line in text.split(separator: "\n") {
            let p = line.split(separator: " ").map(String.init)
            guard p.count >= 13, let W = Double(p[0]), let H = Double(p[1]), let focal = Double(p[2]), let maxLong = Double(p[4]) else { continue }
            let snap = p[3] == "1"
            let v = p[5...12].compactMap { Double($0) }
            guard v.count == 8 else { continue }
            let pts = (0..<4).map { CGPoint(x: v[2 * $0], y: v[2 * $0 + 1]) }
            let o = Geom.order(pts)
            let convex = Geom.isConvex(o)
            var s = "geom \(qi) order"
            for pt in o { s += " " + f(Double(pt.x), 2) + " " + f(Double(pt.y), 2) }
            s += " convex \(convex ? 1 : 0)"
            s += " area " + f(Geom.area(o), 3)
            if convex {
                let asp = Geom.estimateAspect(o, imgW: W, imgH: H, focal: focal)
                let sz = Geom.outputSize(o, imgW: W, imgH: H, focal: focal, snap: snap, maxLong: maxLong)
                s += " aspect " + f(asp, 6) + " size \(Int(sz.width)) \(Int(sz.height))"
            }
            lines.append(s)
            qi += 1
        }
    }
    for r in [0.5, 0.68, 0.70, 0.7071, 0.735, 0.76, 0.7727, 0.79, 1.0, 1.29, 1.3, 1.38, 1.4142, 1.47, 2.0] {
        lines.append("snap " + f(r, 4) + " " + f(Geom.snapPaper(r), 6))
    }
    // PdfWriter
    let jdir = inDir + "/jpg"
    let files = ((try? fm.contentsOfDirectory(atPath: jdir)) ?? []).filter { $0.hasSuffix(".jpg") }.sorted()
    if !files.isEmpty {
        var k = 0
        for mode in [PdfWriter.sizeA4, PdfWriter.sizeLetter, PdfWriter.sizeFit] {
            for v in 0..<3 {
                var sel = [String]()
                if v == 0 { sel = [files[0]] } else if v == 1 { sel = files } else { sel = (0..<25).map { files[$0 % files.count] } }
                let urls = sel.map { URL(fileURLWithPath: jdir + "/" + $0) }
                let datas = urls.compactMap { try? Data(contentsOf: $0) }
                let title = v == 1 ? pdfTitle : (v == 0 ? "" : "Scan 10-11-2026 08.00")
                let out = URL(fileURLWithPath: "\(outDir)/pdf_\(k).pdf")
                do {
                    let mem = try PdfWriter.make(jpegs: datas, sizeMode: mode, title: title)
                    try PdfWriter.write(files: urls, to: out, sizeMode: mode, title: title)
                    let stream = try Data(contentsOf: out)
                    lines.append("pdf \(k) mode \(mode) pages \(sel.count) bytes \(stream.count) same \(mem == stream ? 1 : 0)")
                } catch {
                    lines.append("pdf \(k) error \(error.localizedDescription)")
                }
                k += 1
            }
        }
        for n in files {
            if let d = fm.contents(atPath: jdir + "/" + n), let info = PdfWriter.jpegInfo(d) {
                lines.append("jpeg \(n) \(info.width) \(info.height) \(info.components)")
            } else {
                lines.append("jpeg \(n) null")
            }
        }
    }
    let text = lines.joined(separator: "\n") + "\n"
    try? text.write(toFile: outDir + "/results.txt", atomically: true, encoding: .utf8)
    print("Swift: \(nImg) ảnh, tìm mép \(Int(tDet * 1000)) ms, lọc \(Int(tEnh * 1000)) ms")
    return 0
}

// MARK: --fuzz

/// Bộ sinh số giả ngẫu nhiên xác định (SplitMix64) để kết quả lặp lại được.
struct Rng {
    var s: UInt64
    mutating func next() -> UInt64 {
        s &+= 0x9E3779B97F4A7C15
        var z = s
        z = (z ^ (z >> 30)) &* 0xBF58476D1CE4E5B9
        z = (z ^ (z >> 27)) &* 0x94D049BB133111EB
        return z ^ (z >> 31)
    }
    mutating func int(_ n: Int) -> Int { n <= 0 ? 0 : Int(next() % UInt64(n)) }
    mutating func unit() -> Double { Double(next() >> 11) / Double(1 << 53) }
}

var fuzzFailures = 0
var fuzzChecks = 0

func fcheck(_ ok: Bool, _ what: @autoclosure () -> String) {
    fuzzChecks += 1
    if !ok {
        fuzzFailures += 1
        print("FAIL " + what())
    }
}

func pattern(_ kind: Int, _ w: Int, _ h: Int, _ rng: inout Rng) -> [UInt8] {
    var px = [UInt8](repeating: 255, count: w * h * 4)
    func set(_ i: Int, _ r: UInt8, _ g: UInt8, _ b: UInt8) {
        px[4 * i] = r
        px[4 * i + 1] = g
        px[4 * i + 2] = b
    }
    switch kind {
    case 0: for i in 0..<(w * h) { set(i, 0, 0, 0) }
    case 1: break
    case 2: for i in 0..<(w * h) { set(i, 128, 128, 128) }
    case 3:
        for i in 0..<(w * h) {
            let v = rng.next()
            set(i, UInt8(truncatingIfNeeded: v), UInt8(truncatingIfNeeded: v >> 8), UInt8(truncatingIfNeeded: v >> 16))
        }
    case 4:
        for y in 0..<h { for x in 0..<w { let v = UInt8(255 * x / max(1, w - 1)); set(y * w + x, v, v, v) } }
    case 5:
        let c = 1 + rng.int(24)
        for y in 0..<h { for x in 0..<w { let v: UInt8 = ((x / c + y / c) & 1) == 0 ? 255 : 0; set(y * w + x, v, v, v) } }
    case 6:
        let ang = rng.unit() * Double.pi, per = Double(3 + rng.int(40))
        let cx = cos(ang), sy = sin(ang)
        for y in 0..<h {
            for x in 0..<w {
                let k = Int(floor((Double(x) * cx + Double(y) * sy) / per))
                let v: UInt8 = (k & 1) == 0 ? 235 : 20
                set(y * w + x, v, v, v)
            }
        }
    case 7:
        // byte bất kỳ ở cả 4 kênh (kể cả alpha)
        for i in 0..<px.count { px[i] = UInt8(truncatingIfNeeded: rng.next()) }
    default:
        let b = rng.int(3)
        for y in 0..<h {
            for x in 0..<w {
                let edge = x < b || y < b || x >= w - b || y >= h - b
                let v: UInt8 = edge ? 16 : 240
                set(y * w + x, v, v, v)
            }
        }
    }
    return px
}

func checkQuad(_ q: [Float], _ w: Int, _ h: Int, _ what: String) {
    fcheck(q.count == 8, what + ": quad.count")
    guard q.count == 8 else { return }
    var finite = true, inside = true
    for v in q where !v.isFinite { finite = false }
    for i in 0..<4 {
        if q[2 * i] < -0.01 || q[2 * i] > Float(w - 1) + 0.01 || q[2 * i + 1] < -0.01 || q[2 * i + 1] > Float(h - 1) + 0.01 { inside = false }
    }
    fcheck(finite, what + ": toạ độ không hữu hạn")
    fcheck(inside, what + ": toạ độ ngoài ảnh \(q)")
    let o = Geom.order((0..<4).map { CGPoint(x: CGFloat(q[2 * $0]), y: CGFloat(q[2 * $0 + 1])) })
    fcheck(Geom.isConvex(o), what + ": tứ giác không lồi \(q)")
}

func runFuzz(_ rounds: Int, _ pdfDir: String?) -> Int32 {
    var rng = Rng(s: 20261110)
    // --- DocDetector
    let sizes: [(Int, Int)] = [(1, 1), (15, 15), (16, 16), (17, 16), (16, 17), (16, 2000), (2000, 16), (31, 33), (64, 64), (419, 421),
                               (420, 420), (421, 419), (640, 480), (480, 640), (1024, 768), (768, 1024), (1024, 17), (17, 1024), (1000, 30), (30, 1000)]
    var t0 = Date()
    func runDetect(_ px: [UInt8], _ w: Int, _ h: Int, _ what: String) {
        let r = detect(px, w, h)
        fuzzChecks += 1
        if w < 16 || h < 16 {
            fcheck(r == nil, what + ": ảnh quá nhỏ phải trả về nil")
            return
        }
        fcheck(r != nil, what + ": kết quả nil")
        if let q = r?.quad { checkQuad(q, w, h, what) }
    }
    for (w, h) in sizes {
        for kind in 0...8 { runDetect(pattern(kind, w, h, &rng), w, h, "mẫu \(kind) \(w)x\(h)") }
    }
    for _ in 0..<rounds {
        let w = 16 + rng.int(700), h = 16 + rng.int(700)
        runDetect(pattern(rng.int(9), w, h, &rng), w, h, "ngẫu nhiên \(w)x\(h)")
    }
    // bytesPerRow lớn hơn w*4 (CGContext thường đệm hàng)
    do {
        let w = 37, h = 29, bpr = w * 4 + 12
        var buf = [UInt8](repeating: 0, count: bpr * h)
        for i in 0..<buf.count { buf[i] = UInt8(truncatingIfNeeded: rng.next()) }
        let r = buf.withUnsafeBufferPointer { DocDetector.detect(rgba: $0.baseAddress!, width: w, height: h, bytesPerRow: bpr) }
        fcheck(r != nil, "bytesPerRow có đệm")
        buf.withUnsafeMutableBufferPointer { Enhancer.apply($0.baseAddress!, width: w, height: h, bytesPerRow: bpr, mode: Enhancer.magic) }
        fuzzChecks += 1
    }
    print("DocDetector: xong (\(Int(Date().timeIntervalSince(t0) * 1000)) ms)")

    // --- Geom
    func randomQuad(_ mode: Int) -> [CGPoint] {
        var v = [Double](repeating: 0, count: 8)
        switch mode {
        case 0: for i in 0..<8 { v[i] = rng.unit() * 4000 }
        case 1: let x = rng.unit() * 100; for i in 0..<8 { v[i] = x }
        case 2: for i in 0..<4 { v[2 * i] = Double(i) * 10; v[2 * i + 1] = Double(i) * 10 }
        case 3: v = [0, 0, 100, 0, 100, 0, 0, 0]
        case 4: v = [0, 0, 0, 0, 0, 100, 0, 100]
        case 5: v = [0, 0, 100, 100, 100, 0, 0, 100]
        case 6: for i in 0..<8 { v[i] = (rng.unit() - 0.5) * 1e7 }
        case 7: v = [0, 0, 1e-4, 0, 1e-4, 1e-4, 0, 1e-4]
        case 8: v = [Double.nan, 0, 100, 0, 100, 100, 0, 100]
        case 9: v = [0, 0, Double.infinity, 0, 100, 100, 0, -Double.infinity]
        default:
            let w = 100 + rng.unit() * 3000, h = 100 + rng.unit() * 3000
            v = [rng.unit() * w * 0.3, rng.unit() * h * 0.3, w - rng.unit() * w * 0.3, rng.unit() * h * 0.3,
                 w - rng.unit() * w * 0.3, h - rng.unit() * h * 0.3, rng.unit() * w * 0.3, h - rng.unit() * h * 0.3]
        }
        return (0..<4).map { CGPoint(x: v[2 * $0], y: v[2 * $0 + 1]) }
    }
    for i in 0..<(rounds * 400) {
        let mode = i < 100 ? i % 10 : (rng.int(4) == 0 ? rng.int(10) : 10)
        let q = randomQuad(mode)
        let o = Geom.order(q)
        fcheck(o.count == 4, "order.count")
        let convex = Geom.isConvex(o)
        if mode == 8 || mode == 9 { fcheck(!convex, "isConvex phải từ chối toạ độ không hữu hạn") }
        _ = Geom.area(o)
        _ = Geom.clamp(o, 4000, 3000)
        let focal = rng.int(3) == 0 ? -1.0 : rng.unit() * 5000
        let asp = Geom.estimateAspect(o, imgW: 4000, imgH: 3000, focal: focal)
        if convex { fcheck(asp.isFinite && asp > 0, "estimateAspect lồi = \(asp) \(o)") }
        for maxLong in [16.0, 1000.0, 3508.0] {
            let sz = Geom.outputSize(o, imgW: 4000, imgH: 3000, focal: focal, snap: rng.int(2) == 0, maxLong: maxLong)
            let ok = sz.width >= 16 && sz.height >= 16 && sz.width <= max(16, maxLong) && sz.height <= max(16, maxLong)
            fcheck(ok, "outputSize = \(sz) (maxLong \(maxLong)) \(o)")
        }
    }
    fcheck(Geom.snapPaper(0.70) == Geom.a4Ratio, "snap A4 dọc")
    fcheck(Geom.snapPaper(1.42) == 1.0 / Geom.a4Ratio, "snap A4 ngang")
    fcheck(Geom.snapPaper(0.775) == Geom.letterRatio, "snap Letter dọc")
    fcheck(Geom.snapPaper(0.5) == 0.5, "không snap tỷ lệ lạ")
    fcheck(Geom.order([CGPoint(x: 1, y: 1)]).count == 1, "order với số điểm khác 4")
    fcheck(Geom.isConvex([]) == false, "isConvex rỗng")
    print("Geom: xong")

    // --- Enhancer
    t0 = Date()
    let esizes: [(Int, Int)] = [(1, 1), (3, 3), (4, 4), (5, 4), (4, 5), (7, 9), (8, 8), (9, 8), (100, 4), (4, 100), (2000, 5), (5, 2000),
                                (97, 131), (640, 480), (1240, 1754)]
    let modes = [Enhancer.original, Enhancer.magic, Enhancer.lighten, Enhancer.gray, Enhancer.bw, 99, -1]
    func runEnhance(_ px: [UInt8], _ w: Int, _ h: Int, _ mode: Int, _ what: String) {
        let out = enhance(px, w, h, mode)
        fuzzChecks += 1
        if mode == Enhancer.original || w < 4 || h < 4 {
            fcheck(out == px, "Enhancer giữ nguyên ảnh (\(what) \(w)x\(h) chế độ \(mode))")
        } else {
            var opaque = true
            var i = 3
            while i < out.count {
                if out[i] != 255 { opaque = false; break }
                i += 4
            }
            fcheck(opaque, "Enhancer alpha = 255 (\(what) \(w)x\(h) chế độ \(mode))")
        }
    }
    for (w, h) in esizes {
        for kind in 0...8 {
            let px = pattern(kind, w, h, &rng)
            for m in modes { runEnhance(px, w, h, m, "mẫu \(kind)") }
        }
    }
    for _ in 0..<rounds {
        let w = 1 + rng.int(400), h = 1 + rng.int(400)
        runEnhance(pattern(rng.int(9), w, h, &rng), w, h, modes[rng.int(modes.count)], "ngẫu nhiên")
    }
    // trang trắng ám vàng + chữ đen: nền phải về trắng, chữ vẫn tối
    do {
        let w = 600, h = 800
        var px = [UInt8](repeating: 255, count: w * h * 4)
        for y in 0..<h {
            for x in 0..<w {
                let shade = 150 + 60 * x / w
                let ink = (y % 40 < 4) && x > 60 && x < 540
                let i = (y * w + x) * 4
                if ink {
                    px[i] = 0x28; px[i + 1] = 0x24; px[i + 2] = 0x20
                } else {
                    px[i] = UInt8(shade); px[i + 1] = UInt8(shade - 8); px[i + 2] = UInt8(shade - 30)
                }
            }
        }
        func lum(_ c: [UInt8], _ x: Int, _ y: Int) -> Int {
            let i = (y * w + x) * 4
            return (299 * Int(c[i]) + 587 * Int(c[i + 1]) + 114 * Int(c[i + 2])) / 1000
        }
        for m in [Enhancer.magic, Enhancer.lighten, Enhancer.gray, Enhancer.bw] {
            let c = enhance(px, w, h, m)
            let pl = lum(c, 300, 20), il = lum(c, 300, 41)
            fcheck(pl >= 235, "Enhancer chế độ \(m): nền giấy \(pl) (phải >= 235)")
            fcheck(il <= 90, "Enhancer chế độ \(m): chữ \(il) (phải <= 90)")
        }
    }
    print("Enhancer: xong (\(Int(Date().timeIntervalSince(t0) * 1000)) ms)")

    // --- PdfWriter
    for _ in 0..<3000 {
        var junk = [UInt8](repeating: 0, count: rng.int(600))
        for i in 0..<junk.count { junk[i] = UInt8(truncatingIfNeeded: rng.next()) }
        if junk.count > 2 && rng.int(2) == 0 { junk[0] = 0xFF; junk[1] = 0xD8 }
        if junk.count > 12 && rng.int(3) == 0 { junk[2] = 0xFF; junk[3] = UInt8(0xC0 + rng.int(16)) }
        if junk.count > 20 && rng.int(4) == 0 { junk[4] = 0; junk[5] = UInt8(rng.int(4)) }  // độ dài đoạn rất nhỏ
        let info = PdfWriter.jpegInfo(Data(junk))
        fuzzChecks += 1
        if let info = info { fcheck(info.width > 0 && info.height > 0, "jpegInfo trả về kích thước <= 0") }
    }
    fcheck(PdfWriter.jpegInfo(Data()) == nil, "jpegInfo rỗng")
    do {
        _ = try PdfWriter.make(jpegs: [Data([1, 2, 3])], sizeMode: PdfWriter.sizeA4, title: "x")
        fcheck(false, "PdfWriter phải từ chối ảnh hỏng")
    } catch {
        fuzzChecks += 1
    }
    do {
        _ = try PdfWriter.make(jpegs: [], sizeMode: PdfWriter.sizeA4, title: "x")
        fcheck(false, "PdfWriter phải từ chối tài liệu rỗng")
    } catch {
        fuzzChecks += 1
    }
    if let dir = pdfDir, let jpg = FileManager.default.contents(atPath: dir + "/sample.jpg") {
        // cắt cụt JPEG ở mọi độ dài: không được dừng đột ngột
        for len in stride(from: 0, to: min(jpg.count, 700), by: 1) {
            _ = PdfWriter.jpegInfo(jpg.prefix(len))
            fuzzChecks += 1
        }
        let titles = ["Biên bản thử nghiệm", "", "Hoá đơn (số 12) \\ <test> 😀 ấệữ", String(repeating: "Tên rất dài ", count: 60)]
        var k = 0
        for mode in [PdfWriter.sizeA4, PdfWriter.sizeLetter, PdfWriter.sizeFit] {
            for (ti, title) in titles.enumerated() {
                let pages = [Data](repeating: jpg, count: ti == 3 ? 40 : ti + 1)
                do {
                    let pdf = try PdfWriter.make(jpegs: pages, sizeMode: mode, title: title)
                    let head = String(decoding: pdf.prefix(8), as: UTF8.self)
                    let tail = String(decoding: pdf.suffix(8), as: UTF8.self)
                    fcheck(head.hasPrefix("%PDF-1.4") && tail.contains("%%EOF"), "PDF đầu/cuối")
                    try pdf.write(to: URL(fileURLWithPath: "\(dir)/swift_fuzz_\(k)_m\(mode)_p\(pages.count).pdf"))
                } catch {
                    fcheck(false, "PdfWriter mode \(mode) tiêu đề \(ti): \(error)")
                }
                k += 1
            }
        }
        // ghi theo luồng vào thư mục không tồn tại -> phải báo lỗi, không dừng đột ngột
        do {
            try PdfWriter.write(files: [URL(fileURLWithPath: dir + "/sample.jpg")], to: URL(fileURLWithPath: dir + "/khong-co/x.pdf"),
                                sizeMode: PdfWriter.sizeA4, title: "x")
            fcheck(false, "PdfWriter.write vào thư mục không tồn tại phải lỗi")
        } catch {
            fuzzChecks += 1
        }
        do {
            try PdfWriter.write(files: [URL(fileURLWithPath: dir + "/khong-co.jpg")], to: URL(fileURLWithPath: dir + "/y.pdf"),
                                sizeMode: PdfWriter.sizeA4, title: "x")
            fcheck(false, "PdfWriter.write với ảnh không tồn tại phải lỗi")
        } catch {
            fuzzChecks += 1
            fcheck(!FileManager.default.fileExists(atPath: dir + "/y.pdf") && !FileManager.default.fileExists(atPath: dir + "/y.pdf.part"),
                   "PdfWriter.write lỗi phải dọn file dở")
        }
    }
    print("PdfWriter: xong")
    print("KẾT QUẢ FUZZ (Swift): \(fuzzChecks) kiểm tra, \(fuzzFailures) lỗi")
    return fuzzFailures == 0 ? 0 : 1
}

// MARK: so với toạ độ góc thật

func runGroundTruth(_ gtPath: String, _ dir: String) -> Int32 {
    guard let gtText = try? String(contentsOfFile: gtPath, encoding: .utf8) else {
        print("không đọc được \(gtPath)")
        return 2
    }
    var ok = 0, total = 0, failures = 0
    for line in gtText.split(separator: "\n") {
        let p = line.split(separator: " ").map(String.init)
        guard p.count >= 9 else { continue }
        let name = String(p[0].dropLast(4))
        let gt = p[1...8].compactMap { Double($0) }
        total += 1
        guard gt.count == 8, let img = readPPM(dir + "/" + name + ".ppm") else {
            print("FAIL \(name): không đọc được ảnh")
            continue
        }
        let t0 = Date()
        let r = detect(img.rgba, img.w, img.h)
        let ms = Int(Date().timeIntervalSince(t0) * 1000)
        let diag = hypot(Double(img.w), Double(img.h))
        var err = 1.0
        if let q = r?.quad {
            err = 0
            for i in 0..<4 { err = max(err, hypot(Double(q[2 * i]) - gt[2 * i], Double(q[2 * i + 1]) - gt[2 * i + 1]) / diag) }
        }
        if err < 0.01 { ok += 1 }
        let nums = String(format: "err=%.4f (%.1fpx) %4ldms score=%.3f", err, err * diag, ms, Double(r?.score ?? 0))
        let padded = name.padding(toLength: 16, withPad: " ", startingAt: 0)
        print("\(err < 0.01 ? "PASS" : "FAIL") \(padded) \(nums) borders=\(r?.borders ?? -1) refined=\(r?.refined ?? false)")
    }
    print("DocDetector: \(ok)/\(total) ảnh sai số <1% đường chéo")
    if ok < total || total == 0 { failures += 1 }

    // Enhancer: chạy mọi chế độ trên một ảnh, kiểm tra nền giấy thành trắng
    if let img = readPPM(dir + "/page_flat.ppm") {
        for mode in Enhancer.modes {
            let t0 = Date()
            let px = enhance(img.rgba, img.w, img.h, mode)
            let ms = Int(Date().timeIntervalSince(t0) * 1000)
            // độ sáng trung vị (phần lớn là nền giấy)
            var hist = [Int](repeating: 0, count: 256)
            var i = 0
            while i < px.count {
                let l = (299 * Int(px[i]) + 587 * Int(px[i + 1]) + 114 * Int(px[i + 2])) / 1000
                hist[l] += 1
                i += 4 * 7
            }
            let half = hist.reduce(0, +) / 2
            var acc = 0, med = 0
            for (k, v) in hist.enumerated() {
                acc += v
                if acc >= half { med = k; break }
            }
            let pass = mode == Enhancer.original || med >= 235
            if !pass { failures += 1 }
            print("\(pass ? "PASS" : "FAIL") Enhancer \(Enhancer.name(mode)): trung vị độ sáng \(med), \(ms)ms")
        }
    } else {
        failures += 1
        print("FAIL Enhancer: không đọc được page_flat.ppm")
    }

    // PdfWriter: tạo PDF từ JPEG (trong bộ nhớ và ghi theo luồng phải giống nhau)
    if let jpg = FileManager.default.contents(atPath: dir + "/sample.jpg") {
        do {
            let pdf = try PdfWriter.make(jpegs: [jpg, jpg], sizeMode: PdfWriter.sizeA4, title: "Biên bản thử")
            let head = String(decoding: pdf.prefix(8), as: UTF8.self)
            let tail = String(decoding: pdf.suffix(8), as: UTF8.self)
            let u = URL(fileURLWithPath: dir + "/sample.jpg")
            let out = URL(fileURLWithPath: dir + "/test.pdf")
            try PdfWriter.write(files: [u, u], to: out, sizeMode: PdfWriter.sizeA4, title: "Biên bản thử")
            let streamed = try Data(contentsOf: out)
            let pass = head.hasPrefix("%PDF-1.4") && tail.contains("%%EOF") && streamed == pdf
            if !pass { failures += 1 }
            print("\(pass ? "PASS" : "FAIL") PdfWriter: \(pdf.count) byte, ghi theo luồng \(streamed == pdf ? "trùng" : "KHÁC")")
        } catch {
            failures += 1
            print("FAIL PdfWriter: \(error)")
        }
    } else {
        failures += 1
        print("FAIL PdfWriter: không đọc được sample.jpg")
    }
    return failures == 0 ? 0 : 1
}

let args = CommandLine.arguments
if args.count >= 4, args[1] == "--dump" {
    exit(runDump(args[2], args[3]))
} else if args.count >= 2, args[1] == "--fuzz" {
    exit(runFuzz(args.count >= 3 ? (Int(args[2]) ?? 60) : 60, args.count >= 4 ? args[3] : nil))
} else if args.count >= 3 {
    exit(runGroundTruth(args[1], args[2]))
} else {
    print("cách dùng: algotest gt.txt thư-mục-ppm | --dump dữ-liệu kết-quả | --fuzz [số-vòng] [thư-mục]")
    exit(2)
}
