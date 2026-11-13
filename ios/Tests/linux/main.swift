// Kiểm thử phần thuật toán Swift (DocDetector, Enhancer, Retouch, Flatten, Geom, PdfWriter) trên Linux — không cần UIKit.
//
// Biên dịch:
//   swiftc -O -o algotest ios/Sources/DocDetector.swift ios/Sources/Enhancer.swift ios/Sources/Retouch.swift \
//          ios/Sources/Flatten.swift ios/Sources/Geom.swift ios/Sources/PdfWriter.swift ios/Tests/linux/main.swift
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

func sums(_ c: [UInt8]) -> String {
    var sr = 0, sg = 0, sb = 0
    var i = 0
    while i < c.count {
        sr += Int(c[i])
        sg += Int(c[i + 1])
        sb += Int(c[i + 2])
        i += 4
    }
    return " \(sr) \(sg) \(sb)"
}

/// Chạy một phép biến đổi tại chỗ trên bản sao của ảnh.
func edited(_ rgba: [UInt8], _ body: (UnsafeMutablePointer<UInt8>) -> Void) -> [UInt8] {
    var px = rgba
    px.withUnsafeMutableBufferPointer { buf in
        if let base = buf.baseAddress { body(base) }
    }
    return px
}

/// Các thao tác "Xóa thông minh" cố định dùng để đối chiếu (giống DumpCore.retouchOps).
func retouchOps() -> [Retouch.Op] {
    return [
        Retouch.Op(kind: Retouch.pen, width: 0.03, pts: [0.15, 0.78, 0.30, 0.79]),
        Retouch.Op(kind: Retouch.rect, width: 0, pts: [0.6, 0.84, 0.86, 0.84, 0.86, 0.985, 0.6, 0.985]),
        Retouch.Op(kind: Retouch.magic, width: 0.03, pts: [0.16, 0.885, 0.24, 0.93]),
        Retouch.Op(kind: Retouch.text, width: 0.055, pts: [0.17, 0.554, 0.33, 0.554]),
        Retouch.Op(kind: Retouch.hand, width: 0, pts: []),
    ]
}

func dumpRetouch(_ lines: inout [String], _ outDir: String, _ name: String, _ rgba: [UInt8], _ w: Int, _ h: Int) {
    let bpr = w * 4
    var fm: [UInt8]? = nil
    _ = edited(rgba) { fm = Retouch.fingerMask($0, w, h, bpr) }
    var cnt = 0
    if let m = fm { for b in m where b != 0 { cnt += 1 } }
    lines.append("ret \(name) finger \(fm != nil ? 1 : 0) \(cnt)")
    let ops = retouchOps()
    let kinds = ["pen", "rect", "magic", "text", "hand"]
    for (k, op) in ops.enumerated() {
        var changed = false
        let c = edited(rgba) { p in
            if op.kind == Retouch.hand {
                changed = Retouch.erase(p, w, h, bpr, Retouch.hand, nil)
            } else {
                var m = [UInt8](repeating: 0, count: w * h)
                Retouch.opMask(&m, w, h, op)
                changed = Retouch.erase(p, w, h, bpr, op.kind, m)
            }
        }
        lines.append("ret \(name) \(kinds[k]) \(changed ? 1 : 0)" + sums(c))
    }
    var c = edited(rgba) { Retouch.applyErase($0, w, h, bpr, ops) }
    lines.append("ret \(name) all" + sums(c))
    writePPM("\(outDir)/\(name)_all.ppm", c, w, h)
    c = edited(rgba) { Retouch.sharpen($0, w, h, bpr, 96) }
    lines.append("ret \(name) sharpen" + sums(c))
    c = edited(rgba) { Retouch.cleanEdges($0, w, h, bpr) }
    lines.append("ret \(name) clean" + sums(c))
}

func f(_ v: Double, _ digits: Int) -> String {
    return String(format: "%.\(digits)f", locale: Locale(identifier: "en_US_POSIX"), v)
}

/// Bộ lọc "Làm phẳng" đầy đủ (gồm nắn thẳng) trên bản sao của ảnh: thông số trường dịch chuyển (giống DumpCore.java).
func dumpFlatten(_ lines: inout [String], _ name: String, _ rgba: [UInt8], _ w: Int, _ h: Int) {
    var field: Flatten.Field? = nil
    _ = edited(rgba) { field = Flatten.apply($0, w, h, w * 4, dewarp: true) }
    guard let fl = field else {
        lines.append("flt \(name) 0 0 0 0 0 0 0 0.000 0.000 0.000")
        return
    }
    var sdx = 0.0, sdy = 0.0
    for v in fl.dx { sdx += Double(v) }
    for v in fl.dy { sdy += Double(v) }
    lines.append("flt \(name) 1 \(fl.gw) \(fl.gh) \(fl.lines) \(fl.hrules) \(fl.vrules) \(fl.rotated ? 1 : 0) "
                 + f(Double(fl.maxShift), 3) + " " + f(sdx, 3) + " " + f(sdy, 3))
}

/// Trang chữ giả (khối chữ nhật xếp thành từ) có dòng chữ cong hình parabol (lệch tới 10 điểm ảnh ở hai mép) để thử
/// bước nắn thẳng của "Làm phẳng". Chỉ dùng phép tính số nguyên (lw0: bề rộng ký tự nhỏ nhất).
func curvedPage(_ w: Int, _ h: Int, _ lw0: Int) -> [UInt8] {
    var px = [UInt8](repeating: 255, count: w * h * 4)
    for i in 0..<(w * h) {
        px[4 * i] = 236
        px[4 * i + 1] = 233
        px[4 * i + 2] = 226
    }
    var seed = 12345
    for r in 0..<18 {
        let base = 60 + 30 * r
        var x0 = 40 + (r * 7) % 13
        var inWord = 0, wordLen = 3
        while true {
            seed = (seed * 1103515245 + 12345) & 0x7FFFFFFF
            let lw = lw0 + (seed >> 8) % 3
            let tall = (seed >> 12) % 4 == 0 ? 3 : 0
            if x0 + lw > 440 { break }
            let off = (x0 - 240) * (x0 - 240) / 4000
            for y in (base - 9 - tall + off)..<(base + off) {
                for x in x0..<(x0 + lw) {
                    let i = (y * w + x) * 4
                    px[i] = 40
                    px[i + 1] = 40
                    px[i + 2] = 46
                }
            }
            x0 += lw + 3
            inWord += 1
            if inWord >= wordLen {
                inWord = 0
                wordLen = 2 + (seed >> 16) % 5
                x0 += 7
            }
        }
    }
    return px
}

/// Chạy body trên ảnh liền (bytesPerRow = w*4) và trên bản đệm thêm pad byte mỗi hàng: kết quả phải giống hệt,
/// byte đệm phải giữ nguyên.
func samePadded(_ flat: [UInt8], _ w: Int, _ h: Int, _ pad: Int, _ body: (UnsafeMutablePointer<UInt8>, Int) -> Void) -> Bool {
    let bpr = w * 4 + pad
    var padded = [UInt8](repeating: 0x5A, count: bpr * h)
    for y in 0..<h { for k in 0..<(w * 4) { padded[y * bpr + k] = flat[y * w * 4 + k] } }
    let a = edited(flat) { body($0, w * 4) }
    let b = edited(padded) { body($0, bpr) }
    for y in 0..<h {
        for k in 0..<(w * 4) where a[y * w * 4 + k] != b[y * bpr + k] { return false }
        for k in (w * 4)..<bpr where b[y * bpr + k] != 0x5A { return false }
    }
    return true
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
        // ảnh trang đã nắn phẳng của make_retouch_images.py: so từng điểm ảnh ở các bộ lọc mới
        let rt = name.hasPrefix("rt_")
        // trang mấp mô / gấp nếp của make_wavy_images.py: so từng điểm ảnh ở bộ lọc Làm phẳng + thông số trường nắn
        let wv = name.hasPrefix("wv_")
        for mode in [Enhancer.magic, Enhancer.lighten, Enhancer.gray, Enhancer.bw,
                     Enhancer.magicPro, Enhancer.denoise, Enhancer.noShadow, Enhancer.noHand, Enhancer.flatten] {
            t0 = Date()
            let c = enhance(img.rgba, img.w, img.h, mode)
            tEnh += Date().timeIntervalSince(t0)
            lines.append("enh \(name) \(mode)" + sums(c))
            if full || (rt && mode >= Enhancer.magicPro) || (wv && mode == Enhancer.flatten) {
                writePPM("\(outDir)/\(name)_m\(mode).ppm", c, img.w, img.h)
            }
        }
        if wv { dumpFlatten(&lines, name, img.rgba, img.w, img.h) }
        if rt { dumpRetouch(&lines, outDir, name, img.rgba, img.w, img.h) }
    }
    // Retouch.remap: đổi vùng cắt
    do {
        let quads: [[Float]] = [[100, 80, 900, 120, 880, 1300, 60, 1250], [0, 0, 1000, 0, 1000, 1400, 0, 1400], [50, 60, 950, 40, 990, 1380, 20, 1390]]
        let pts: [Float] = [0.25, 0.5, 0.9, 0.1, 0, 0, 1, 1]
        var ri = 0
        for qa in quads {
            for qb in quads {
                var s = "remap \(ri)"
                ri += 1
                for v in Retouch.remap(pts, qa, qb) { s += " " + f(Double(v), 5) }
                s += " " + f(Double(Retouch.remapScale(qa, qb)), 5)
                lines.append(s)
            }
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
    let modes = [Enhancer.original, Enhancer.magic, Enhancer.lighten, Enhancer.gray, Enhancer.bw,
                 Enhancer.magicPro, Enhancer.denoise, Enhancer.noShadow, Enhancer.noHand, Enhancer.flatten, 99, -1]
    func runEnhance(_ px: [UInt8], _ w: Int, _ h: Int, _ mode: Int, _ what: String) {
        let out = enhance(px, w, h, mode)
        fuzzChecks += 1
        // Làm phẳng bỏ qua ảnh có cạnh dưới 16 điểm ảnh (giữ nguyên ảnh, như bản Java)
        if mode == Enhancer.original || w < 4 || h < 4 || (mode == Enhancer.flatten && (w < 16 || h < 16)) {
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
        for m in [Enhancer.magic, Enhancer.lighten, Enhancer.gray, Enhancer.bw, Enhancer.magicPro, Enhancer.noShadow, Enhancer.noHand,
                  Enhancer.flatten] {
            let c = enhance(px, w, h, m)
            let pl = lum(c, 300, 20), il = lum(c, 300, 41)
            fcheck(pl >= 235, "Enhancer chế độ \(m): nền giấy \(pl) (phải >= 235)")
            fcheck(il <= 90, "Enhancer chế độ \(m): chữ \(il) (phải <= 90)")
        }
    }
    print("Enhancer: xong (\(Int(Date().timeIntervalSince(t0) * 1000)) ms)")

    // --- Retouch
    t0 = Date()
    /// Toạ độ chuẩn hoá ngẫu nhiên, thỉnh thoảng là giá trị bất thường.
    func coord() -> Float {
        switch rng.int(14) {
        case 0: return Float.nan
        case 1: return Float.infinity
        case 2: return -Float.infinity
        case 3: return -1e9
        case 4: return 1e9
        case 5: return -0.5
        case 6: return 1.5
        default: return Float(rng.unit())
        }
    }
    func randomOp(_ kind: Int) -> Retouch.Op {
        var n = kind == Retouch.rect ? 4 : rng.int(6)
        if rng.int(10) == 0 { n = rng.int(3) }
        var p = [Float](repeating: 0, count: n * 2 + (rng.int(8) == 0 ? 1 : 0))
        for i in 0..<p.count { p[i] = coord() }
        let width: Float = rng.int(6) == 0 ? coord() * 1e6 : Float(rng.unit()) * 0.2
        return Retouch.Op(kind: kind, width: width, pts: p)
    }
    func runRetouch(_ px: [UInt8], _ w: Int, _ h: Int, _ what: String) {
        let bpr = w * 4
        var fm: [UInt8]? = nil
        _ = edited(px) { fm = Retouch.fingerMask($0, w, h, bpr) }
        fcheck(fm == nil || fm!.count == w * h, "fingerMask kích thước (\(what) \(w)x\(h))")
        _ = edited(px) { Retouch.removeFingers($0, w, h, bpr) }
        var ops = [Retouch.Op]()
        for _ in 0..<rng.int(5) { ops.append(randomOp(rng.int(5))) }
        _ = edited(px) { Retouch.applyErase($0, w, h, bpr, ops) }
        for k in 0..<5 {
            var m = [UInt8](repeating: 0, count: w * h)
            Retouch.opMask(&m, w, h, randomOp(k))
            _ = edited(px) { Retouch.erase($0, w, h, bpr, k, m) }
            _ = edited(px) { Retouch.erase($0, w, h, bpr, k, nil) }
        }
        let c = edited(px) {
            Retouch.cleanEdges($0, w, h, bpr)
            Retouch.sharpen($0, w, h, bpr, 96)
            Retouch.denoise($0, w, h, bpr)
        }
        fcheck(c.count == px.count, "Retouch giữ kích thước ảnh (\(what) \(w)x\(h))")
    }
    let rsizes: [(Int, Int)] = [(1, 1), (3, 3), (4, 4), (5, 4), (8, 8), (9, 7), (16, 16), (17, 33), (48, 48), (47, 200), (200, 47), (100, 4), (4, 100),
                                (2000, 5), (5, 2000), (97, 131), (420, 300), (640, 480)]
    for (w, h) in rsizes {
        for kind in 0...8 { runRetouch(pattern(kind, w, h, &rng), w, h, "mẫu \(kind)") }
    }
    for _ in 0..<rounds {
        let w = 1 + rng.int(300), h = 1 + rng.int(300)
        runRetouch(pattern(rng.int(9), w, h, &rng), w, h, "ngẫu nhiên")
    }
    // mặt nạ ngắn hơn ảnh / vùng tô phủ kín ảnh: không được dừng đột ngột
    do {
        let w = 60, h = 50
        let px = pattern(3, w, h, &rng)
        let short = [UInt8](repeating: 1, count: 10)
        let all = [UInt8](repeating: 1, count: w * h)
        for k in 0..<5 {
            var changed = true
            _ = edited(px) { changed = Retouch.erase($0, w, h, w * 4, k, short) }
            fcheck(!changed, "erase: vùng tô ngắn hơn ảnh -> false (kiểu \(k))")
            _ = edited(px) { Retouch.erase($0, w, h, w * 4, k, all) }
        }
        _ = edited(px) { Retouch.fill($0, w, h, w * 4, short) }
        var m = short
        Retouch.strokeMask(&m, w, h, [1, 1, 5, 5], 3)
        Retouch.polyMask(&m, w, h, [1, 1, 5, 1, 5, 5])
        fcheck(m == short, "mặt nạ ngắn hơn ảnh phải được giữ nguyên")
        fcheck(Retouch.remap([0.5, 0.5], [], []) == [0.5, 0.5], "remap với tứ giác rỗng")
        fcheck(Retouch.remap([0.5, 0.5], [0, 0, 0, 0, 0, 0, 0, 0], [0, 0, 1, 0, 1, 1, 0, 1]) == [0.5, 0.5], "remap với tứ giác suy biến")
        fcheck(Retouch.remapScale([], []) == 1, "remapScale với tứ giác rỗng")
    }
    // bytesPerRow có đệm (CGContext thường đệm hàng): kết quả phải giống hệt khi không đệm
    do {
        let w = 157, h = 211, pad = 20
        let bpr = w * 4 + pad
        var flat = [UInt8](repeating: 255, count: w * h * 4)
        for y in 0..<h {
            for x in 0..<w {
                let i = (y * w + x) * 4
                let shade = 170 + 50 * x / w
                var r = shade, g = shade - 6, b = shade - 22
                if y % 18 < 2 && x > 20 && x < 140 { r = 40; g = 38; b = 36 }             // dòng chữ / đường kẻ
                if x < 9 && y > 80 && y < 130 { r = 200; g = 140; b = 112 }               // ngón tay ở mép trái
                if (x + y) % 37 < 2 && y > 150 { r = 40; g = 62; b = 170 }                 // nét bút xanh
                if y < 3 || x > w - 3 { r = 30; g = 26; b = 24 }                           // mép bàn tối
                flat[i] = UInt8(r); flat[i + 1] = UInt8(g); flat[i + 2] = UInt8(b)
            }
        }
        var padded = [UInt8](repeating: 0x5A, count: bpr * h)
        for y in 0..<h { for k in 0..<(w * 4) { padded[y * bpr + k] = flat[y * w * 4 + k] } }
        func same(_ what: String, _ body: (UnsafeMutablePointer<UInt8>, Int) -> Void) {
            let a = edited(flat) { body($0, w * 4) }
            let b = edited(padded) { body($0, bpr) }
            var ok = true
            for y in 0..<h {
                for k in 0..<(w * 4) where a[y * w * 4 + k] != b[y * bpr + k] { ok = false }
                for k in (w * 4)..<bpr where b[y * bpr + k] != 0x5A { ok = false }
            }
            fcheck(ok, "bytesPerRow có đệm: \(what)")
        }
        for mode in [Enhancer.magic, Enhancer.magicPro, Enhancer.denoise, Enhancer.noShadow, Enhancer.noHand, Enhancer.flatten] {
            same("bộ lọc \(mode)") { Enhancer.apply($0, width: w, height: h, bytesPerRow: $1, mode: mode) }
        }
        same("Làm phẳng chỉ làm sạch (geometric = false)") {
            Enhancer.apply($0, width: w, height: h, bytesPerRow: $1, mode: Enhancer.flatten, geometric: false)
        }
        let ops = [Retouch.Op(kind: Retouch.pen, width: 0.05, pts: [0.2, 0.3, 0.7, 0.35]),
                   Retouch.Op(kind: Retouch.rect, width: 0, pts: [0.5, 0.5, 0.8, 0.5, 0.8, 0.7, 0.5, 0.7]),
                   Retouch.Op(kind: Retouch.magic, width: 0.06, pts: [0.3, 0.8, 0.5, 0.85]),
                   Retouch.Op(kind: Retouch.text, width: 0.08, pts: [0.2, 0.09, 0.8, 0.09]),
                   Retouch.Op(kind: Retouch.hand, width: 0, pts: [])]
        same("applyErase") { Retouch.applyErase($0, w, h, $1, ops) }
        same("removeFingers") { Retouch.removeFingers($0, w, h, $1) }
        same("cleanEdges") { Retouch.cleanEdges($0, w, h, $1) }
        same("sharpen") { Retouch.sharpen($0, w, h, $1, 96) }
        var fa: [UInt8]? = nil, fb: [UInt8]? = nil
        _ = edited(flat) { fa = Retouch.fingerMask($0, w, h, w * 4) }
        _ = edited(padded) { fb = Retouch.fingerMask($0, w, h, bpr) }
        fcheck(fa != nil && fa == fb, "bytesPerRow có đệm: fingerMask (\(fa == nil ? "không thấy ngón tay" : "khác nhau"))")
    }
    print("Retouch: xong (\(Int(Date().timeIntervalSince(t0) * 1000)) ms)")

    // --- Flatten (Làm phẳng)
    t0 = Date()
    // trang chữ cong: bước nắn thẳng chạy thật (ước lượng trường + nắn ảnh); bytesPerRow có đệm phải cho kết quả giống hệt
    do {
        let w = 480, h = 640
        for lw0 in [5, 6] {
            let page = curvedPage(w, h, lw0)
            var fa: Flatten.Field? = nil, fb: Flatten.Field? = nil
            let ok = samePadded(page, w, h, 28) { p, rb in
                if rb == w * 4 {
                    fa = Flatten.apply(p, w, h, rb)
                } else {
                    fb = Flatten.apply(p, w, h, rb)
                }
            }
            fcheck(ok, "bytesPerRow có đệm: Làm phẳng (trang chữ cong \(lw0))")
            var sameField = (fa == nil) == (fb == nil)
            if let ra = fa, let rb = fb {
                sameField = ra.gw == rb.gw && ra.gh == rb.gh && ra.dx == rb.dx && ra.dy == rb.dy && ra.rotated == rb.rotated
            }
            fcheck(sameField, "bytesPerRow có đệm: trường dịch chuyển của Làm phẳng (trang chữ cong \(lw0))")
            if lw0 == 5 { fcheck(fa != nil, "Làm phẳng: trang chữ cong phải được nắn thẳng") }
            if let fl = fa {
                print("Làm phẳng: trang chữ cong \(lw0): lưới \(fl.gw)x\(fl.gh), \(fl.lines) dòng, dịch tối đa "
                      + f(Double(fl.maxShift), 2) + ", xoay ngang \(fl.rotated ? 1 : 0)")
            }
            var clean: Flatten.Field? = Flatten.Field()
            let cleaned = edited(page) { clean = Flatten.apply($0, w, h, w * 4, dewarp: false) }
            fcheck(clean == nil, "Làm phẳng chỉ làm sạch (dewarp = false) không được nắn")
            // như FuzzCore: chỉ làm sạch rồi nắn theo đúng trường Flatten.apply trả về + làm nét 48 (như Flatten.apply) phải
            // trùng kết quả lọc đầy đủ — trường trả về (dòng flt của --dump) đúng là trường đã dùng để nắn ảnh
            if let fl = fa {
                let full = edited(page) { _ = Flatten.apply($0, w, h, w * 4) }
                let split = edited(cleaned) { p in
                    Flatten.remap(p, w, h, w * 4, fl)
                    Retouch.sharpen(p, w, h, w * 4, 48)
                }
                fcheck(split == full, "Làm phẳng (trang chữ cong \(lw0)): dewarp = false + nắn theo trường trả về + làm nét phải trùng kết quả lọc")
            }
        }
    }
    /// Mọi điểm ảnh RGBA có alpha = 255.
    func allOpaque(_ c: [UInt8]) -> Bool {
        var i = 3
        while i < c.count {
            if c[i] != 255 { return false }
            i += 4
        }
        return true
    }
    // nắn bằng trường cho sẵn: đệm hàng giữ nguyên; trường bất thường (NaN, vô cực, bước 0...) không được dừng đột ngột
    do {
        let w = 61, h = 47
        let src = pattern(3, w, h, &rng)
        func field(_ gw: Int, _ gh: Int, _ step: Float, _ ox: Float, _ v: (Int) -> Float) -> Flatten.Field {
            let fl = Flatten.Field()
            fl.gw = gw
            fl.gh = gh
            fl.step = step
            fl.ox = ox
            fl.dx = (0..<(gw * gh)).map { v($0) }
            fl.dy = (0..<(gw * gh)).map { v($0 * 7 + 3) }
            return fl
        }
        let fields: [Flatten.Field] = [
            field(4, 3, 20, 0.5, { Float($0 % 5) - 2 }),
            field(6, 5, 12, 0, { Float(($0 * 3) % 7) * 0.75 - 2 }),
            field(2, 2, 100, 0, { _ in 1e30 }),
            field(3, 3, 25, 0, { $0 % 2 == 0 ? Float.nan : Float.infinity }),
            field(3, 3, 0, 0, { _ in 1 }),
            field(3, 3, Float.nan, 0, { _ in 1 }),
            field(3, 3, Float.leastNonzeroMagnitude, Float.nan, { _ in -1 }),
            field(3, 3, 30, 1e30, { _ in 2 }),
            field(3, 3, 30, -Float.infinity, { _ in 2 }),
            field(1, 5, 30, 0, { _ in 2 }),
        ]
        for (k, fl) in fields.enumerated() {
            let ok = samePadded(src, w, h, 12) { Flatten.remap($0, w, h, $1, fl) }
            fcheck(ok, "bytesPerRow có đệm: Flatten.remap (trường \(k))")
        }
        let short = Flatten.Field()
        short.gw = 3
        short.gh = 3
        short.step = 10
        short.dx = [0, 0]
        short.dy = [0, 0]
        let kept = edited(src) { Flatten.remap($0, w, h, w * 4, short) }
        fcheck(kept == src, "Flatten.remap: trường thiếu nút phải giữ nguyên ảnh")
        // trường hằng có giá trị bất thường, bước / gốc lưới bất thường (cùng danh sách với FuzzCore): không dừng đột ngột,
        // alpha 255; dịch chuyển không hữu hạn / rất xa -> mọi điểm lấy mẫu ngoài ảnh -> ảnh trắng
        let odd: [Float] = [Float.nan, Float.infinity, -Float.infinity, Float.greatestFiniteMagnitude, -Float.greatestFiniteMagnitude,
                            1e30, -1e30, 1e9, -1e9, 3e5, Float.leastNonzeroMagnitude, -0.0, 0.5, -7.25, 60.9, -46.99]
        for v in odd {
            let fl = field(5, 4, 16, 0, { _ in v })
            let c = edited(src) { Flatten.remap($0, w, h, w * 4, fl) }
            fcheck(allOpaque(c), "Flatten.remap alpha = 255 (trường hằng \(v))")
            if v.isNaN || v.isInfinite || abs(v) >= 1e9 {
                fcheck(c.allSatisfy { $0 == 255 }, "Flatten.remap: dịch chuyển \(v) -> ảnh trắng")
            }
        }
        let steps: [Float] = [Float.leastNonzeroMagnitude, 1e-30, 1e30, Float.greatestFiniteMagnitude, Float.infinity]
        for st in steps {
            let fl = field(5, 4, st, 0, { _ in 2.5 })
            let c = edited(src) { Flatten.remap($0, w, h, w * 4, fl) }
            fcheck(allOpaque(c), "Flatten.remap alpha = 255 (bước lưới \(st))")
        }
        let origins: [Float] = [Float.nan, Float.infinity, -Float.infinity, 1e30, -1e30, -5]
        for o in origins {
            let fl = field(5, 4, 16, o, { _ in 2.5 })
            let c = edited(src) { Flatten.remap($0, w, h, w * 4, fl) }
            fcheck(allOpaque(c), "Flatten.remap alpha = 255 (gốc lưới \(o))")
        }
    }
    // ảnh rất nhỏ / rất dẹt (cùng danh sách với FuzzCore.fuzzFlatten): không nắn; cạnh < 16 giữ nguyên ảnh; khi không nắn,
    // chỉ làm sạch (dewarp = false) phải trùng kết quả lọc
    do {
        let fsizes: [(Int, Int)] = [(1, 1), (3, 3), (15, 15), (15, 300), (16, 16), (16, 17), (17, 16), (17, 2000), (2000, 17), (31, 33), (239, 400)]
        for (w, h) in fsizes {
            for kind in 0...8 {
                let px = pattern(kind, w, h, &rng)
                let what = "Flatten mẫu \(kind) \(w)x\(h)"
                var fl: Flatten.Field? = Flatten.Field()
                let c = edited(px) { fl = Flatten.apply($0, w, h, w * 4) }
                fcheck(fl == nil, what + ": ảnh nhỏ / dẹt không được nắn")
                if w < 16 || h < 16 {
                    fcheck(c == px, what + ": ảnh có cạnh < 16 phải giữ nguyên")
                } else {
                    fcheck(allOpaque(c), what + ": alpha = 255")
                }
                if kind == 3 || kind == 5 {
                    var fl2: Flatten.Field? = Flatten.Field()
                    let c2 = edited(px) { fl2 = Flatten.apply($0, w, h, w * 4, dewarp: false) }
                    fcheck(fl2 == nil && c2 == c, what + ": dewarp = false trùng kết quả lọc")
                }
            }
        }
        // giấy trắng: kết quả vẫn trắng (>= 250 mọi kênh, alpha 255), không nắn
        let wsizes: [(Int, Int)] = [(16, 16), (17, 40), (40, 17), (300, 420), (1000, 700), (1240, 1754)]
        for (w, h) in wsizes {
            var fl: Flatten.Field? = Flatten.Field()
            let c = edited([UInt8](repeating: 255, count: w * h * 4)) { fl = Flatten.apply($0, w, h, w * 4) }
            var worst = 255
            var i = 0
            while i < c.count {
                worst = min(worst, Int(c[i]), Int(c[i + 1]), Int(c[i + 2]))
                if c[i + 3] != 255 { worst = -1 }
                i += 4
            }
            fcheck(worst >= 250, "Flatten giấy trắng \(w)x\(h): tối nhất \(worst) (phải >= 250, alpha 255)")
            fcheck(fl == nil, "Flatten giấy trắng \(w)x\(h): không nắn")
        }
    }
    print("Flatten: xong (\(Int(Date().timeIntervalSince(t0) * 1000)) ms)")

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
            // Nguyên gốc và Khử nhiễu giữ nguyên tông ảnh; các bộ lọc còn lại phải đưa nền giấy về trắng
            let pass = mode == Enhancer.original || mode == Enhancer.denoise || med >= 235
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
