// Kiểm thử phần thuật toán Swift (DocDetector, Enhancer, Retouch, Flatten, Geom, PdfWriter, lõi Word: WordFont, WordDoc,
// DocxWriter, DocLayout) trên Linux — không cần UIKit.
//
// Biên dịch:
//   swiftc -O -o algotest ios/Sources/DocDetector.swift ios/Sources/Enhancer.swift ios/Sources/Retouch.swift \
//          ios/Sources/Flatten.swift ios/Sources/Geom.swift ios/Sources/PdfWriter.swift ios/Sources/WordFont.swift \
//          ios/Sources/WordDoc.swift ios/Sources/DocxWriter.swift ios/Sources/DocLayout.swift ios/Tests/linux/main.swift
// Cách chạy:
//   ./algotest tests/images/gt.txt <thư-mục-ppm>     so với toạ độ góc thật + bộ lọc + PDF
//   ./algotest --dump <dữ-liệu> <kết-quả>            xuất kết quả để đối chiếu với bản Java (DumpCore.java)
//   ./algotest --dump-word <trang> <kết-quả>         lõi chuyển sang Word trên các trang <tên>.ppm + <tên>.words.tsv
//                                                    (đối chiếu với DumpCore --word bằng compare_core.py --word)
//   ./algotest --fuzz [số-vòng] [thư-mục-PDF]        kiểm thử độ bền (ảnh nhỏ/dẹt/nhiễu, tứ giác suy biến, JPEG hỏng,
//                                                    từ rác cho lõi Word; file .docx ghi vào thư mục để kiểm bằng Python)
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

// MARK: --fuzz: lõi Word

/// Chữ rác: rỗng / toàn khoảng trắng / ký tự điều khiển, ký hiệu kẻ vạch, dấu tổ hợp, emoji, ký tự ngoài bảng số đo, chuỗi rất dài.
let junkTexts: [String] = ["", " ", "\t", "\n", "\r\n", "|", "_", "l", "I", "1", "||", "|a", "a|", "|_", "__", "—", "–", "¦x", "x¦", "'", "\"", ".,",
                           "-", "=", "~", "─┼─", "¬¯‾", "(", ")", "[]", "e\u{0301}", "a\u{0323}\u{0302}", "\u{0301}", "\u{0323}\u{0301}\u{0302}\u{0300}",
                           "Vie\u{0323}\u{0302}t", "😀", "😀😀😀", "👨\u{200D}👩\u{200D}👧", "中文字", "\u{200B}\u{200C}\u{200D}", "\u{FEFF}",
                           "a\u{0000}b", "\u{0001}\u{0002}\u{001F}", " x ", "<&>\"'", "\u{FFFE}\u{FFFF}", "\u{10FFFF}", "\u{1F1FB}\u{1F1F3}", "ỵ", "Ỵ",
                           "ạặậẹệ", "Q,;", "{}", "a\u{00A0}b", "…", "Ǻǻ", "ḁḀ", "0", "00", "1.250.000", "Việt", "NAM", "xã", "hội", "aceimnorsuvwxz",
                           String(repeating: "W", count: 5000), String(repeating: "i", count: 3000), String(repeating: "\u{0301}", count: 500),
                           String(repeating: "😀", count: 400), "|" + String(repeating: "m", count: 40) + "|"]

func junkText(_ rng: inout Rng) -> String {
    if rng.int(4) == 0 {
        // chuỗi ngẫu nhiên từ một bảng ký tự hỗn hợp
        let alphabet = Array("abcxyzACEMÀẾỊỢỮđĐ0189|_-.,:;'()[]/\\ \u{0301}\u{0323}\u{00A6}\u{2014}中😀".unicodeScalars)
        var u = String.UnicodeScalarView()
        for _ in 0..<(1 + rng.int(12)) { u.append(alphabet[rng.int(alphabet.count)]) }
        return String(u)
    }
    return junkTexts[rng.int(junkTexts.count)]
}

func junkCoord(_ rng: inout Rng, _ lim: Int) -> Float {
    switch rng.int(14) {
    case 0: return Float.nan
    case 1: return Float.infinity
    case 2: return -Float.infinity
    case 3: return -1e30
    case 4: return 1e30
    case 5: return Float.greatestFiniteMagnitude
    case 6: return -5
    case 7: return Float(lim) + 7
    case 8: return Float(lim)
    case 9: return 0
    case 10: return -0.0
    case 11: return Float.leastNonzeroMagnitude
    default: return Float(rng.unit()) * Float(lim)
    }
}

/// n từ rác trên ảnh w x h: khung NaN / vô cực / âm / ngoài ảnh / đảo ngược / diện tích 0, chữ lạ, conf và số dòng bất thường.
func junkWords(_ rng: inout Rng, _ w: Int, _ h: Int, _ n: Int) -> [DocLayout.Word] {
    var out = [DocLayout.Word]()
    for _ in 0..<n {
        var x0 = Float(rng.unit()) * Float(w), y0 = Float(rng.unit()) * Float(h)
        var x1 = x0 + 1 + Float(rng.unit()) * 90, y1 = y0 + 2 + Float(rng.unit()) * 40
        switch rng.int(10) {
        case 0: x0 = junkCoord(&rng, w)
        case 1: y1 = junkCoord(&rng, h)
        case 2:
            x0 = junkCoord(&rng, w); y0 = junkCoord(&rng, h); x1 = junkCoord(&rng, w); y1 = junkCoord(&rng, h)
        case 3: swap(&x0, &x1); swap(&y0, &y1)
        case 4: x1 = x0
        case 5: x0 = -40; x1 = Float(rng.unit()) * 2; y1 = y0 + 30 + Float(rng.unit()) * Float(h)
        case 6: x0 = -50; y0 = -50; x1 = Float(w) + 50; y1 = Float(h) + 50
        default: break
        }
        let wd = DocLayout.Word(text: junkText(&rng), x0: x0, y0: y0, x1: x1, y1: y1,
                                line: [-1, 0, 1, 7, Int(Int32.max), Int(Int32.min), rng.int(30)][rng.int(7)])
        wd.conf = [1, 1, 1, 0.5, 0.49, 0, -1, 2, Float.nan, Float.infinity, -Float.infinity, Float(rng.unit())][rng.int(12)]
        out.append(wd)
    }
    return out
}

/// Trang "chữ" giả để lõi Word chạy hết các bước (đo từ, dòng, kiểu chữ, bảng, hình): các từ là dãy khối chữ nhật, có bảng
/// kẻ (table), nét bút màu + khung dấu đỏ, dòng chữ nghiêng theo slope; trả về ảnh RGBX + các từ đúng khung (xê dịch nhẹ).
func wordPage(_ rng: inout Rng, _ w: Int, _ h: Int, _ slope: Double, _ table: Bool) -> (px: [UInt8], words: [DocLayout.Word]) {
    var px = [UInt8](repeating: 255, count: w * h * 4)
    let shade = UInt8(236 + rng.int(20))
    for i in 0..<(w * h) {
        px[4 * i] = shade
        px[4 * i + 1] = shade
        px[4 * i + 2] = shade &- UInt8(rng.int(6))
    }
    func fill(_ x0: Int, _ y0: Int, _ x1: Int, _ y1: Int, _ r: UInt8, _ g: UInt8, _ b: UInt8) {
        var y = max(0, y0)
        while y < min(h, y1) {
            var x = max(0, x0)
            while x < min(w, x1) {
                let i = (y * w + x) * 4
                px[i] = r
                px[i + 1] = g
                px[i + 2] = b
                x += 1
            }
            y += 1
        }
    }
    var words = [DocLayout.Word]()
    let em = max(8, h / 48), mx = w / 12
    let lw = max(1, em / 2 - 1), gap = max(1, em / 8), xh = max(3, em * 45 / 100), asc = max(4, em * 66 / 100)
    let low = Array("acemnorsuvwxz".unicodeScalars), tall = Array("bdfhklt".unicodeScalars)
    var line = 0
    /// Một dòng từ x tới xEnd, đường chân chữ base; trả về sau khi vẽ.
    func textLine(_ xStart: Int, _ xEnd: Int, _ base: Int, _ r: UInt8, _ g: UInt8, _ b: UInt8) {
        var x = xStart
        line += 1
        while x + lw < xEnd {
            let n = 1 + rng.int(8)
            var u = String.UnicodeScalarView()
            let wx0 = x
            var top = base
            for _ in 0..<n where x + lw < xEnd {
                let isTall = rng.int(4) == 0
                let yb = base + Int(slope * Double(x))
                let yt = yb - (isTall ? asc : xh)
                fill(x, yt, x + lw, yb, r, g, b)
                top = min(top, yt)
                u.append(isTall ? tall[rng.int(tall.count)] : low[rng.int(low.count)])
                x += lw + gap
            }
            let yb = base + Int(slope * Double(wx0))
            let jx = Float(rng.unit()) * 2 - 1, jy = Float(rng.unit()) * 2 - 1
            let wd = DocLayout.Word(text: String(u), x0: Float(wx0) - 1 + jx, y0: Float(yb - asc) - 2 + jy, x1: Float(x - gap) + 1 + jx,
                                    y1: Float(yb) + 2 + jy, line: line)
            if rng.int(3) == 0 { wd.conf = 0.5 + 0.5 * Float(rng.unit()) }
            words.append(wd)
            x += em / 3 + (rng.int(9) == 0 ? 3 * em : 0)
        }
    }
    var y = h / 14 + em
    let pitch = em * 16 / 10
    let tableAt = table ? 3 + rng.int(4) : -1
    var k = 0
    while y + em < h - h / 14 {
        if k == tableAt && y + 5 * pitch < h - h / 14 {
            // bảng 3 x 3, chữ trong ô
            let t = max(1, em / 8), rh = pitch * 13 / 10
            let cols = [mx, mx + (w - 2 * mx) / 4, mx + (w - 2 * mx) * 6 / 10, w - mx]
            for j in 0...3 { fill(mx, y + j * rh, w - mx + t, y + j * rh + t, 24, 24, 28) }
            for c in cols { fill(c, y, c + t, y + 3 * rh + t, 24, 24, 28) }
            for j in 0..<3 {
                for c in 0..<3 where rng.int(5) != 0 { textLine(cols[c] + em / 2, cols[c + 1] - em / 2, y + j * rh + rh * 7 / 10, 30, 30, 36) }
            }
            y += 3 * rh + pitch
        } else {
            let indent = rng.int(5) == 0 ? 2 * em : 0
            let end = w - mx - (rng.int(4) == 0 ? rng.int(w / 3) : 0)
            textLine(mx + indent, end, y, 30, 30, 36)
            y += pitch + (rng.int(7) == 0 ? pitch / 2 : 0)
        }
        k += 1
    }
    // nét bút xanh chéo + khung dấu đỏ
    let sx = rng.int(max(1, w / 2)), sy = h / 2 + rng.int(max(1, h / 3))
    for q in 0..<(w / 4) { fill(sx + q, sy + q / 3, sx + q + 2, sy + q / 3 + 2, 30, 60, 190) }
    let bx = w / 2 + rng.int(max(1, w / 4)), by = h * 3 / 4, bs = 3 * em
    fill(bx, by, bx + bs, by + 2, 200, 30, 40)
    fill(bx, by + bs, bx + bs + 2, by + bs + 2, 200, 30, 40)
    fill(bx, by, bx + 2, by + bs, 200, 30, 40)
    fill(bx + bs, by, bx + bs + 2, by + bs, 200, 30, 40)
    return (px, words)
}

var wordFuzzFiles = 0

/// Mọi số thực trong mô hình trang (dòng của modelText) phải hữu hạn.
func modelFinite(_ model: String) -> Bool {
    for tok in model.split(whereSeparator: { $0 == " " || $0 == "\n" }) where tok.utf8.count == 16 {
        if let v = UInt64(tok, radix: 16), (v >> 52) & 0x7ff == 0x7ff { return false }
    }
    return true
}

/// Kiểm một kết quả phân tích: khổ trang hữu hạn, hình nằm trong ảnh và cắt được, mô hình hữu hạn, ghi được file .docx
/// (vào outDir để kiểm bằng Python: mở bằng zipfile, XML hợp lệ).
func checkWordResult(_ r: DocLayout.Result, _ w: Int, _ h: Int, _ what: String, _ outDir: String?) {
    let page = r.page
    fcheck(page.width.isFinite && page.height.isFinite && page.width > 0 && page.height > 0, what + ": khổ trang \(page.width) x \(page.height)")
    for pic in page.pics {
        let ok = pic.px0 >= 0 && pic.py0 >= 0 && pic.px1 <= max(w, 0) && pic.py1 <= max(h, 0) && pic.px1 > pic.px0 && pic.py1 > pic.py0
        fcheck(ok || w < 1 || h < 1, what + ": vùng cắt hình ngoài ảnh (\(pic.px0), \(pic.py0), \(pic.px1), \(pic.py1))")
        if !ok { continue }
        let pix = r.picture(pic)
        let pw = pic.px1 - pic.px0, ph = pic.py1 - pic.py0
        fcheck(pix.count == pw * ph * 4, what + ": picture trả về \(pix.count) byte cho hình \(pw) x \(ph)")
        pic.data = pw * ph <= 4096 && pix.count == pw * ph * 4 ? pngStored(pix, pw, ph) : pngStored([200, 30, 40, 255], 1, 1)
        pic.ext = "png"
    }
    fcheck(modelFinite(modelText(r)), what + ": mô hình trang có số không hữu hạn")
    r.release()
    if let pic = page.pics.first, pic.px1 > pic.px0, pic.py1 > pic.py0, (pic.px1 - pic.px0) * (pic.py1 - pic.py0) <= 1 << 22 {
        let white = r.picture(pic)
        fcheck(white.count == (pic.px1 - pic.px0) * (pic.py1 - pic.py0) * 4 && white.allSatisfy { $0 == 255 }, what + ": picture sau release phải trắng")
    }
    let xml = DocxWriter.documentXml([page])
    fcheck(xml.hasPrefix("<?xml") && xml.hasSuffix("</w:body></w:document>"), what + ": document.xml đầu / cuối")
    let docx = DocxWriter.write(pages: [page], title: what, isoTime: wordTime, deflate: wordFuzzFiles % 2 == 0 ? tinyDeflate : nil)
    fcheck(docx.count > 100 && docx[docx.startIndex] == 0x50 && docx[docx.startIndex + 1] == 0x4B, what + ": file .docx")
    if let dir = outDir, wordFuzzFiles < 600 {
        writeFile("\(dir)/swift_word_fuzz_\(wordFuzzFiles).docx", [UInt8](docx))
    }
    wordFuzzFiles += 1
}

func fuzzWord(_ rng: inout Rng, _ rounds: Int, _ outDir: String?) {
    // khổ trang / trang chỉ có ảnh với kích thước và khổ giấy bất kỳ
    let dims = [0, 1, -5, 31, 32, 1000, 100000, Int(Int32.max)]
    for w in dims {
        for h in dims {
            for mode in [-1, 0, 1, 2, 3, 99] {
                let g = DocLayout.geometry(w, h, mode)
                fcheck(g.pageW.isFinite && g.pageH.isFinite && g.scale.isFinite && g.scale > 0 && g.pageW > 0 && g.pageH > 0,
                       "geometry \(w) x \(h) khổ \(mode)")
                let page = DocLayout.picturePage(w, h, mode)
                fcheck(page.pics.count == 1 && page.width == g.pageW && page.height == g.pageH, "picturePage \(w) x \(h) khổ \(mode)")
                if mode >= 0 && mode <= 2 && (w == 1 || w == 1000) {
                    page.pics[0].data = pngStored([10, 20, 30, 255], 1, 1)
                    page.pics[0].ext = "png"
                    let docx = DocxWriter.write(pages: [page], title: "ảnh \(w)x\(h)", isoTime: wordTime)
                    fcheck(docx.count > 100, "picturePage \(w) x \(h): file .docx")
                    if let dir = outDir { writeFile("\(dir)/swift_word_fuzz_pic_\(w)_\(h)_\(mode).docx", [UInt8](docx)) }
                }
            }
        }
    }
    // ảnh rất nhỏ / rất dẹt / một màu / nhiễu với các từ rác
    let sizes: [(Int, Int)] = [(1, 1), (2, 2), (8, 8), (31, 31), (31, 200), (32, 32), (33, 32), (32, 33), (40, 2000), (2000, 40), (47, 61),
                               (64, 64), (100, 100), (333, 111), (640, 480), (480, 640)]
    for (w, h) in sizes {
        for kind in 0...8 {
            let px = pattern(kind, w, h, &rng)
            let words = junkWords(&rng, w, h, rng.int(60))
            let mode = rng.int(3)
            let r = DocLayout.analyze(px, w, h, words, mode)
            checkWordResult(r, w, h, "Word mẫu \(kind) \(w)x\(h)", outDir)
            if w < 32 || h < 32 { fcheck(r.page.pics.isEmpty && r.page.blocks.isEmpty, "Word \(w)x\(h): ảnh quá nhỏ -> trang trống") }
        }
    }
    // mảng điểm ảnh ngắn hơn ảnh: trang trống, không đọc ngoài mảng
    do {
        let r = DocLayout.analyze([UInt8](repeating: 0, count: 100), 64, 64, junkWords(&rng, 64, 64, 5), 0)
        fcheck(r.page.pics.isEmpty && r.page.blocks.isEmpty && r.lines == 0, "Word: mảng điểm ảnh ngắn hơn ảnh")
        let e = DocLayout.analyze([], 0, 0, [], 0)
        fcheck(e.page.blocks.isEmpty, "Word: ảnh 0 x 0")
        _ = e.picture(WordDoc.Pic())
    }
    // từ "|x" / "x|" hẹp hơn 1 điểm ảnh sát mép ảnh, cạnh một đường kẻ dọc: gọt ký tự đường kẻ rồi đo lại từng làm
    // measureWord đọc ngoài mảng (bản Java: ArrayIndexOutOfBoundsException)
    do {
        let w = 300, h = 300
        var px = [UInt8](repeating: 255, count: w * h * 4)
        func bar(_ x0: Int, _ y0: Int, _ x1: Int, _ y1: Int) {
            for y in y0..<y1 {
                for x in x0..<x1 {
                    let i = (y * w + x) * 4
                    px[i] = 16
                    px[i + 1] = 16
                    px[i + 2] = 16
                }
            }
        }
        bar(20, 0, 23, h)
        bar(w - 23, 0, w - 20, h)
        bar(0, 10, 1, 41)
        bar(w - 1, 10, w, 41)
        let words = [DocLayout.Word(text: "|x", x0: -3, y0: -2, x1: 0.6, y1: 100, line: 1),
                     DocLayout.Word(text: "x|", x0: Float(w) - 0.6, y0: -2, x1: Float(w) + 3, y1: 100, line: 1),
                     DocLayout.Word(text: "¦ab", x0: -9, y0: 150, x1: 0.9, y1: 260, line: 2)]
        checkWordResult(DocLayout.analyze(px, w, h, words, 0), w, h, "Word từ sát mép cạnh đường kẻ dọc", outDir)
    }
    // trang chữ giả (bảng, nghiêng, nét màu) + từ rác: chạy hết các bước; chạy lại phải cho cùng kết quả
    var nTables = 0, nSkew = 0, nLines = 0, nPics = 0
    for i in 0..<max(8, rounds / 2) {
        let w = 200 + rng.int(700), h = 200 + rng.int(900)
        let slope = i % 3 == 2 ? (rng.unit() - 0.5) * 0.08 : 0
        let pg = wordPage(&rng, w, h, slope, i % 2 == 0)
        var words = pg.words
        if i % 4 != 3 { words.append(contentsOf: junkWords(&rng, w, h, rng.int(40))) }
        let mode = i % 3
        let r = DocLayout.analyze(pg.px, w, h, words, mode)
        nTables += r.tables
        nLines += r.lines
        nPics += r.page.pics.count
        if r.skew != 0 { nSkew += 1 }
        let what = "Word trang chữ giả #\(i) \(w)x\(h)"
        let model = modelText(r)
        checkWordResult(r, w, h, what, outDir)
        if i % 4 == 0 {
            let r2 = DocLayout.analyze(pg.px, w, h, words, mode)
            fcheck(modelText(r2) == model, what + ": chạy lại phải cho cùng kết quả")
        }
    }
    fcheck(nTables > 0 && nLines > 0 && nPics > 0, "Word: trang chữ giả phải có bảng (\(nTables)), dòng chữ (\(nLines)), hình (\(nPics))")
    print("   Word: trang chữ giả: \(nLines) dòng, \(nTables) bảng, \(nPics) hình, \(nSkew) trang khử nghiêng")
    // hàng nghìn từ trùng nhau (dưới và trên ngưỡng 6000 của bước dọn khung trùng), lẫn rác
    for n in [3000, 6500] {
        let w = 420, h = 300
        let pg = wordPage(&rng, w, h, 0, false)
        var words = [DocLayout.Word]()
        let first = pg.words[0]
        for k in 0..<n {
            let wd = DocLayout.Word(text: k % 5 == 4 ? "|" : first.text, x0: first.x0, y0: first.y0, x1: first.x1, y1: first.y1, line: first.line)
            if k % 7 == 3 { wd.conf = 0.9 }
            words.append(wd)
        }
        words.append(contentsOf: junkWords(&rng, w, h, 50))
        let r = DocLayout.analyze(pg.px, w, h, words, 0)
        checkWordResult(r, w, h, "Word \(n) từ trùng nhau", outDir)
    }
    // ảnh lớn (cỡ trang thật): trang chữ giả + rác; nhiễu + rác
    do {
        let w = 1240, h = 1754
        let pg = wordPage(&rng, w, h, 0.01, true)
        let r = DocLayout.analyze(pg.px, w, h, pg.words + junkWords(&rng, w, h, 300), 0)
        checkWordResult(r, w, h, "Word trang lớn \(w)x\(h)", outDir)
        let noise = pattern(3, 1000, 1400, &rng)
        let r2 = DocLayout.analyze(noise, 1000, 1400, junkWords(&rng, 1000, 1400, 400), 2)
        checkWordResult(r2, 1000, 1400, "Word nhiễu 1000x1400", outDir)
    }
    // DocxWriter: tài liệu rỗng, tiêu đề / thời điểm lạ, hình không có dữ liệu, nhiều trang
    do {
        fcheck(DocxWriter.write(pages: [], title: "x", isoTime: wordTime).isEmpty, "DocxWriter: tài liệu không có trang -> Data rỗng")
        var pages = [WordDoc.Page]()
        for i in 0..<6 {
            let w = 300 + 40 * i, h = 260 + 55 * i
            let pg = wordPage(&rng, w, h, 0, i % 2 == 1)
            let r = DocLayout.analyze(pg.px, w, h, pg.words, i % 3)
            for (k, pic) in r.page.pics.enumerated() where k % 2 == 0 { pic.data = pngStored([1, 2, 3, 255], 1, 1) }
            r.release()
            pages.append(r.page)
        }
        pages.append(WordDoc.Page())
        pages.append(DocLayout.picturePage(10, 10, 0))
        let titles = ["", "Biên bản <số 12> & \"phụ lục\" 😀 ấệữ", "a\u{0000}b\u{0001}\u{FFFE}\tc\n", String(repeating: "Tên rất dài ", count: 400)]
        for (k, title) in titles.enumerated() {
            let docx = DocxWriter.write(pages: pages, title: title, isoTime: k == 2 ? "<lạ & \"\u{0002}>" : wordTime, deflate: k % 2 == 0 ? tinyDeflate : nil)
            fcheck(docx.count > 100, "DocxWriter nhiều trang, tiêu đề \(k)")
            if let dir = outDir { writeFile("\(dir)/swift_word_fuzz_multi_\(k).docx", [UInt8](docx)) }
        }
    }
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

    // --- lõi Word (DocLayout + DocxWriter)
    t0 = Date()
    fuzzWord(&rng, rounds, pdfDir)
    print("DocLayout + DocxWriter (Word): xong (\(Int(Date().timeIntervalSince(t0) * 1000)) ms)")

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

// MARK: lõi Word (DocLayout + DocxWriter): --dump-word

let wordModes = ["a4", "letter", "fit"]
let wordTime = "2026-01-01T00:00:00Z"

/// FNV-1a 64 bit (giống DumpCore.fnv).
func fnv(_ d: [UInt8]) -> UInt64 {
    var h: UInt64 = 0xcbf29ce484222325
    d.withUnsafeBufferPointer { p in
        for b in p { h = (h ^ UInt64(b)) &* 0x100000001b3 }
    }
    return h
}

func hex16(_ v: UInt64) -> String {
    let s = String(v, radix: 16)
    return String(repeating: "0", count: 16 - s.utf8.count) + s
}

/// Số thực dưới dạng 16 chữ số hex của bit IEEE 754 (mọi NaN như nhau, như Double.doubleToLongBits).
func hx(_ v: Double) -> String {
    return hex16(v.isNaN ? 0x7ff8000000000000 : v.bitPattern)
}

/// Float.parseFloat của Java cho các file thử: bỏ khoảng trắng hai đầu, nhận NaN / Infinity.
func parseFloatJava(_ t: String) -> Float {
    let s = JavaNum.trim(t)
    if s == "NaN" { return Float.nan }
    if s == "Infinity" || s == "+Infinity" { return Float.infinity }
    if s == "-Infinity" { return -Float.infinity }
    return Float(s) ?? 0
}

/// Các từ của <tên>.words.tsv: x0 y0 x1 y1 dòng chữ [độ-tin-cậy], ngăn bằng tab. Tách dòng / trường theo byte như
/// DumpCore.readWords (String.split của Java: giữ trường rỗng ở giữa, bỏ trường rỗng ở cuối dòng).
func readWordsTSV(_ path: String) -> [DocLayout.Word]? {
    guard let d = FileManager.default.contents(atPath: path) else { return nil }
    var out = [DocLayout.Word]()
    for line in [UInt8](d).split(separator: 0x0A, omittingEmptySubsequences: true) {
        if !line.contains(where: { $0 > 0x20 }) { continue }
        var f = line.split(separator: 0x09, omittingEmptySubsequences: false).map { String(decoding: $0, as: UTF8.self) }
        while let l = f.last, l.unicodeScalars.isEmpty { f.removeLast() }
        if f.count < 6 { continue }
        let wd = DocLayout.Word(text: f[5], x0: parseFloatJava(f[0]), y0: parseFloatJava(f[1]), x1: parseFloatJava(f[2]),
                                y1: parseFloatJava(f[3]), line: Int(JavaNum.trim(f[4])) ?? -1)
        if f.count > 6 { wd.conf = parseFloatJava(f[6]) }
        out.append(wd)
    }
    return out
}

func be32(_ o: inout [UInt8], _ v: Int) {
    o.append(UInt8(truncatingIfNeeded: v >> 24))
    o.append(UInt8(truncatingIfNeeded: v >> 16))
    o.append(UInt8(truncatingIfNeeded: v >> 8))
    o.append(UInt8(truncatingIfNeeded: v))
}

func pngChunk(_ o: inout [UInt8], _ type: String, _ data: [UInt8]) {
    be32(&o, data.count)
    var td = Array(type.utf8)
    td.append(contentsOf: data)
    o.append(contentsOf: td)
    be32(&o, Int(DocxWriter.crc32(td)))
}

/// PNG RGB 8 bit KHÔNG nén (khối "stored" của zlib) từ điểm ảnh RGBX: cùng các byte với DumpCore.pngStored, nên so được
/// nội dung phần word/media của file .docx giữa hai bản.
func pngStored(_ px: [UInt8], _ w: Int, _ h: Int) -> [UInt8] {
    var raw = [UInt8](repeating: 0, count: h * (1 + 3 * w))
    var k = 0
    for y in 0..<h {
        raw[k] = 0
        k += 1
        for x in 0..<w {
            let i = (y * w + x) * 4
            raw[k] = px[i]
            raw[k + 1] = px[i + 1]
            raw[k + 2] = px[i + 2]
            k += 3
        }
    }
    var z = [UInt8]()
    z.reserveCapacity(raw.count + raw.count / 65535 * 5 + 16)
    z.append(0x78)
    z.append(0x01)
    var pos = 0
    repeat {
        let n = min(65535, raw.count - pos)
        z.append(pos + n >= raw.count ? 1 : 0)
        z.append(UInt8(truncatingIfNeeded: n))
        z.append(UInt8(truncatingIfNeeded: n >> 8))
        z.append(UInt8(truncatingIfNeeded: ~n))
        z.append(UInt8(truncatingIfNeeded: ~n >> 8))
        z.append(contentsOf: raw[pos..<(pos + n)])
        pos += n
    } while pos < raw.count
    var a: UInt32 = 1, b: UInt32 = 0
    for v in raw {
        a = (a + UInt32(v)) % 65521
        b = (b + a) % 65521
    }
    be32(&z, Int((b << 16) | a))
    var o: [UInt8] = [0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]
    var hd = [UInt8]()
    be32(&hd, w)
    be32(&hd, h)
    hd.append(contentsOf: [8, 2, 0, 0, 0])
    pngChunk(&o, "IHDR", hd)
    pngChunk(&o, "IDAT", z)
    pngChunk(&o, "IEND", [])
    return o
}

/// Bộ nén DEFLATE thô tối giản CHỈ để kiểm thử nhánh "mục zip có nén" của DocxWriter trên Linux (trên iOS lớp ứng dụng
/// truyền hàm của framework Compression): LZ77 với bảng băm 3 byte + mã Huffman cố định (RFC 1951, khối BTYPE = 01).
func tinyDeflate(_ data: Data) -> Data? {
    let src = [UInt8](data)
    let n = src.count
    let lenBase = [3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31, 35, 43, 51, 59, 67, 83, 99, 115, 131, 163, 195, 227, 258]
    let lenExtra = [0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0]
    let distBase = [1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193, 257, 385, 513, 769, 1025, 1537, 2049, 3073, 4097, 6145,
                    8193, 12289, 16385, 24577]
    let distExtra = [0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13]
    var out = [UInt8]()
    out.reserveCapacity(n / 2 + 16)
    var bitBuf: UInt64 = 0
    var bitCnt = 0
    func put(_ v: Int, _ nbits: Int) {
        bitBuf |= UInt64(v) << UInt64(bitCnt)
        bitCnt += nbits
        while bitCnt >= 8 {
            out.append(UInt8(truncatingIfNeeded: bitBuf))
            bitBuf >>= 8
            bitCnt -= 8
        }
    }
    // mã Huffman ghi từ bit cao xuống
    func putHuff(_ code: Int, _ len: Int) {
        var r = 0
        for i in 0..<len where (code >> i) & 1 != 0 { r |= 1 << (len - 1 - i) }
        put(r, len)
    }
    func sym(_ v: Int) {
        if v < 144 {
            putHuff(0x30 + v, 8)
        } else if v < 256 {
            putHuff(0x190 + v - 144, 9)
        } else if v < 280 {
            putHuff(v - 256, 7)
        } else {
            putHuff(0xC0 + v - 280, 8)
        }
    }
    put(1, 1)
    put(1, 2)
    var head = [Int](repeating: -1, count: 1 << 15)
    var i = 0
    while i < n {
        var bestLen = 0, bestDist = 0
        if i + 2 < n {
            let hsh = ((Int(src[i]) << 10) ^ (Int(src[i + 1]) << 5) ^ Int(src[i + 2])) & 0x7FFF
            let c = head[hsh]
            if c >= 0 && i - c <= 32768 {
                var l = 0
                while l < 258 && i + l < n && src[c + l] == src[i + l] { l += 1 }
                if l >= 3 {
                    bestLen = l
                    bestDist = i - c
                }
            }
            head[hsh] = i
        }
        if bestLen >= 3 {
            var lc = 0
            while lc < 28 && lenBase[lc + 1] <= bestLen { lc += 1 }
            sym(257 + lc)
            put(bestLen - lenBase[lc], lenExtra[lc])
            var dc = 0
            while dc < 29 && distBase[dc + 1] <= bestDist { dc += 1 }
            putHuff(dc, 5)
            put(bestDist - distBase[dc], distExtra[dc])
            i += bestLen
        } else {
            sym(Int(src[i]))
            i += 1
        }
    }
    sym(256)
    if bitCnt > 0 { out.append(UInt8(truncatingIfNeeded: bitBuf)) }
    return Data(out)
}

func modelPara(_ sb: inout String, _ p: WordDoc.Para) {
    sb += "para \(hx(p.top)) \(hx(p.height)) \(hx(p.x)) \(hx(p.x1)) \(hx(p.base)) \(p.align) \(p.hasTab ? 1 : 0) \(hx(p.size))"
    sb += " \(p.bold ? 1 : 0) \(p.italic ? 1 : 0) \(p.color) \(p.toks.count)\n"
    for t in p.toks {
        sb += "tok \(t.t) \(t.n) \(hx(t.spacing)) \(hx(t.pos)) \(t.bold ? 1 : 0) \(t.italic ? 1 : 0) \(t.underline ? 1 : 0)"
        sb += " \(t.color) \(hx(t.size)) text="
        if t.text.unicodeScalars.isEmpty { sb += "-" }
        var first = true
        for u in t.text.unicodeScalars {
            if !first { sb += "," }
            first = false
            sb += String(u.value, radix: 16, uppercase: true)
        }
        sb += "\n"
    }
}

func modelArray(_ sb: inout String, _ tag: String, _ v: [Double]) {
    sb += tag
    for x in v { sb += " " + hx(x) }
    sb += "\n"
}

/// Mô hình trang do DocLayout.analyze dựng, từng trường một, số thực ở dạng bit (đúng các dòng của DumpCore.modelText).
func modelText(_ r: DocLayout.Result) -> String {
    let pg = r.page
    var sb = ""
    sb += "page \(hx(pg.width)) \(hx(pg.height))\n"
    sb += "res \(hx(r.skew)) \(r.tables) \(r.lines)\n"
    var bi = 0
    for b in pg.blocks {
        sb += "block \(bi) \(b.table != nil ? "table" : (b.para != nil ? "para" : "none")) \(hx(b.top)) \(hx(b.bottom)) \(hx(b.x0)) \(hx(b.x1))"
        sb += " \(b.overlaps ? 1 : 0)\n"
        bi += 1
        if let p = b.para { modelPara(&sb, p) }
        if let t = b.table {
            sb += "table \(t.cols) \(t.rows) \(hx(t.marL)) \(hx(t.marR)) \(t.cells.count)\n"
            modelArray(&sb, "xs", t.xs)
            modelArray(&sb, "ys", t.ys)
            modelArray(&sb, "hT", t.hT)
            modelArray(&sb, "vT", t.vT)
            sb += "owner"
            for o in t.owner { sb += " \(o)" }
            sb += "\n"
            for c in t.cells {
                sb += "cell \(c.c0) \(c.c1) \(c.r0) \(c.r1) \(hx(c.x0)) \(hx(c.y0)) \(hx(c.x1)) \(hx(c.y1))"
                sb += " \(hx(c.bTop)) \(hx(c.bLeft)) \(hx(c.bBottom)) \(hx(c.bRight)) \(hx(c.marL)) \(hx(c.marR)) \(c.paras.count)\n"
                for p in c.paras { modelPara(&sb, p) }
            }
        }
    }
    for p in pg.pics {
        sb += "pic \(hx(p.x)) \(hx(p.y)) \(hx(p.w)) \(hx(p.h)) \(p.behind ? 1 : 0) \(p.px0) \(p.py0) \(p.px1) \(p.py1)\n"
    }
    for q in pg.rules {
        sb += "rule \(q.horiz ? 1 : 0) \(hx(q.pos)) \(hx(q.a)) \(hx(q.b)) \(hx(q.t))\n"
    }
    for x in pg.boxes {
        sb += "box \(hx(x.x)) \(hx(x.y)) \(hx(x.w)) \(hx(x.h))\n"
        modelPara(&sb, x.para)
    }
    return sb
}

/// Các trang DỰNG TAY để đối chiếu DocxWriter ở những nhánh mà kết quả của DocLayout không đi qua (lề ô riêng, bảng đứng
/// cạnh nhau, hai bảng liền nhau, tab trùng, giá trị NaN / vô cực / rất lớn...). Sinh từ cùng một mô tả với
/// DumpCore.syntheticPages nên hai bản dựng đúng cùng một mô hình.
func syntheticPages() -> [WordDoc.Page] {
    var pages = [WordDoc.Page]()
    let g1 = WordDoc.Page()
    g1.width = 595.276
    g1.height = 841.89
    let b2 = WordDoc.Block()
    let p3 = WordDoc.Para()
    p3.top = 40.0
    p3.height = 15.0
    p3.x = 50.0
    p3.x1 = 300.0
    p3.base = 52.0
    p3.size = 13.0
    p3.bold = true
    p3.italic = false
    p3.hasTab = true
    p3.color = 16711680
    p3.align = 0
    let k4 = WordDoc.Tok()
    k4.t = 0
    k4.text = "Xin <ch\u{E0}o> & \"b\u{1EA1}n\" 'x'"
    k4.n = 1
    k4.spacing = 0.26
    k4.pos = 0.0
    k4.size = 13.0
    k4.bold = true
    k4.italic = false
    k4.underline = false
    k4.color = 0
    p3.toks.append(k4)
    let k5 = WordDoc.Tok()
    k5.t = 1
    k5.text = ""
    k5.n = 3
    k5.spacing = 0.3
    k5.pos = 0.0
    k5.size = 13.0
    k5.bold = true
    k5.italic = false
    k5.underline = false
    k5.color = 0
    p3.toks.append(k5)
    let k6 = WordDoc.Tok()
    k6.t = 0
    k6.text = "a\u{1}b\u{9}c\u{FFFE}\u{1F600}e\u{301}"
    k6.n = 1
    k6.spacing = -0.2
    k6.pos = 0.0
    k6.size = 11.5
    k6.bold = false
    k6.italic = true
    k6.underline = true
    k6.color = 1193046
    p3.toks.append(k6)
    let k7 = WordDoc.Tok()
    k7.t = 2
    k7.text = ""
    k7.n = 1
    k7.spacing = 0.0
    k7.pos = 400.0
    k7.size = 13.0
    k7.bold = false
    k7.italic = false
    k7.underline = false
    k7.color = 0
    p3.toks.append(k7)
    let k8 = WordDoc.Tok()
    k8.t = 2
    k8.text = ""
    k8.n = 1
    k8.spacing = 0.0
    k8.pos = 400.0
    k8.size = 13.0
    k8.bold = false
    k8.italic = false
    k8.underline = false
    k8.color = 0
    p3.toks.append(k8)
    let k9 = WordDoc.Tok()
    k9.t = 2
    k9.text = ""
    k9.n = 1
    k9.spacing = 0.0
    k9.pos = 20.0
    k9.size = 13.0
    k9.bold = false
    k9.italic = false
    k9.underline = false
    k9.color = 0
    p3.toks.append(k9)
    let k10 = WordDoc.Tok()
    k10.t = 2
    k10.text = ""
    k10.n = 1
    k10.spacing = 0.0
    k10.pos = 250.5
    k10.size = 13.0
    k10.bold = false
    k10.italic = false
    k10.underline = false
    k10.color = 0
    p3.toks.append(k10)
    let k11 = WordDoc.Tok()
    k11.t = 0
    k11.text = "sau tab"
    k11.n = 1
    k11.spacing = 0.0
    k11.pos = 0.0
    k11.size = 13.0
    k11.bold = false
    k11.italic = false
    k11.underline = false
    k11.color = 0
    p3.toks.append(k11)
    let k12 = WordDoc.Tok()
    k12.t = 1
    k12.text = ""
    k12.n = 0
    k12.spacing = 0.0
    k12.pos = 0.0
    k12.size = 13.0
    k12.bold = false
    k12.italic = false
    k12.underline = false
    k12.color = 0
    p3.toks.append(k12)
    let k13 = WordDoc.Tok()
    k13.t = 1
    k13.text = ""
    k13.n = 1
    k13.spacing = 0.0
    k13.pos = 0.0
    k13.size = 13.0
    k13.bold = false
    k13.italic = false
    k13.underline = false
    k13.color = 0
    p3.toks.append(k13)
    let k14 = WordDoc.Tok()
    k14.t = 0
    k14.text = ""
    k14.n = 1
    k14.spacing = 0.0
    k14.pos = 0.0
    k14.size = 13.0
    k14.bold = false
    k14.italic = false
    k14.underline = false
    k14.color = 0
    p3.toks.append(k14)
    b2.para = p3
    b2.top = 40.0
    b2.bottom = p3.top + p3.height
    b2.x0 = 50.0
    b2.x1 = 300.0
    g1.blocks.append(b2)
    let b15 = WordDoc.Block()
    let p16 = WordDoc.Para()
    p16.top = 60.0
    p16.height = 14.625
    p16.x = 50.0
    p16.x1 = 540.0
    p16.base = 71.7
    p16.size = 13.0
    p16.bold = false
    p16.italic = false
    p16.hasTab = false
    p16.color = 0
    p16.align = 0
    let k17 = WordDoc.Tok()
    k17.t = 0
    k17.text = "D\u{F2}ng th\u{1EE9} 0 c\u{1EE7}a trang d\u{1EF1}ng tay"
    k17.n = 1
    k17.spacing = 0.0
    k17.pos = 0.0
    k17.size = 13.0
    k17.bold = false
    k17.italic = false
    k17.underline = false
    k17.color = 0
    p16.toks.append(k17)
    b15.para = p16
    b15.top = 60.0
    b15.bottom = p16.top + p16.height
    b15.x0 = 50.0
    b15.x1 = 540.0
    g1.blocks.append(b15)
    let b18 = WordDoc.Block()
    let p19 = WordDoc.Para()
    p19.top = 76.0
    p19.height = 14.625
    p19.x = 50.0
    p19.x1 = 537.0
    p19.base = 87.7
    p19.size = 13.0
    p19.bold = false
    p19.italic = false
    p19.hasTab = false
    p19.color = 0
    p19.align = 0
    let k20 = WordDoc.Tok()
    k20.t = 0
    k20.text = "D\u{F2}ng th\u{1EE9} 1 c\u{1EE7}a trang d\u{1EF1}ng tay"
    k20.n = 1
    k20.spacing = 0.05
    k20.pos = 0.0
    k20.size = 13.0
    k20.bold = false
    k20.italic = false
    k20.underline = false
    k20.color = 0
    p19.toks.append(k20)
    b18.para = p19
    b18.top = 76.0
    b18.bottom = p19.top + p19.height
    b18.x0 = 50.0
    b18.x1 = 537.0
    g1.blocks.append(b18)
    let b21 = WordDoc.Block()
    let p22 = WordDoc.Para()
    p22.top = 92.0
    p22.height = 14.625
    p22.x = 50.0
    p22.x1 = 540.0
    p22.base = 103.7
    p22.size = 13.0
    p22.bold = false
    p22.italic = false
    p22.hasTab = false
    p22.color = 0
    p22.align = 0
    let k23 = WordDoc.Tok()
    k23.t = 0
    k23.text = "D\u{F2}ng th\u{1EE9} 2 c\u{1EE7}a trang d\u{1EF1}ng tay"
    k23.n = 1
    k23.spacing = 0.1
    k23.pos = 0.0
    k23.size = 13.0
    k23.bold = false
    k23.italic = false
    k23.underline = false
    k23.color = 0
    p22.toks.append(k23)
    b21.para = p22
    b21.top = 92.0
    b21.bottom = p22.top + p22.height
    b21.x0 = 50.0
    b21.x1 = 540.0
    g1.blocks.append(b21)
    let b24 = WordDoc.Block()
    let p25 = WordDoc.Para()
    p25.top = 108.0
    p25.height = 14.625
    p25.x = 50.0
    p25.x1 = 537.0
    p25.base = 119.7
    p25.size = 13.0
    p25.bold = false
    p25.italic = false
    p25.hasTab = false
    p25.color = 0
    p25.align = 0
    let k26 = WordDoc.Tok()
    k26.t = 0
    k26.text = "D\u{F2}ng th\u{1EE9} 3 c\u{1EE7}a trang d\u{1EF1}ng tay"
    k26.n = 1
    k26.spacing = 0.15000000000000002
    k26.pos = 0.0
    k26.size = 13.0
    k26.bold = false
    k26.italic = false
    k26.underline = false
    k26.color = 0
    p25.toks.append(k26)
    b24.para = p25
    b24.top = 108.0
    b24.bottom = p25.top + p25.height
    b24.x0 = 50.0
    b24.x1 = 537.0
    g1.blocks.append(b24)
    let b27 = WordDoc.Block()
    let p28 = WordDoc.Para()
    p28.top = 130.0
    p28.height = 16.0
    p28.x = 200.0
    p28.x1 = 395.276
    p28.base = 143.0
    p28.size = 14.0
    p28.bold = true
    p28.italic = false
    p28.hasTab = false
    p28.color = 0
    p28.align = 0
    let k29 = WordDoc.Tok()
    k29.t = 0
    k29.text = "\u{1EDE} GI\u{1EEE}A"
    k29.n = 1
    k29.spacing = 0.0
    k29.pos = 0.0
    k29.size = 14.0
    k29.bold = true
    k29.italic = false
    k29.underline = false
    k29.color = 0
    p28.toks.append(k29)
    b27.para = p28
    b27.top = 130.0
    b27.bottom = p28.top + p28.height
    b27.x0 = 200.0
    b27.x1 = 395.276
    g1.blocks.append(b27)
    let b30 = WordDoc.Block()
    let p31 = WordDoc.Para()
    p31.top = 150.0
    p31.height = 14.0
    p31.x = 430.0
    p31.x1 = 541.2
    p31.base = 161.0
    p31.size = 12.0
    p31.bold = false
    p31.italic = true
    p31.hasTab = false
    p31.color = 0
    p31.align = 0
    let k32 = WordDoc.Tok()
    k32.t = 0
    k32.text = "b\u{EA}n ph\u{1EA3}i"
    k32.n = 1
    k32.spacing = 0.0
    k32.pos = 0.0
    k32.size = 12.0
    k32.bold = false
    k32.italic = true
    k32.underline = false
    k32.color = 0
    p31.toks.append(k32)
    b30.para = p31
    b30.top = 150.0
    b30.bottom = p31.top + p31.height
    b30.x0 = 430.0
    b30.x1 = 541.2
    g1.blocks.append(b30)
    let b33 = WordDoc.Block()
    let t34 = WordDoc.Table()
    t34.cols = 3
    t34.rows = 2
    t34.xs = [50.0, 150.0, 300.0, 540.0]
    t34.ys = [200.0, 230.0, 270.0]
    t34.hT = [1.0, 0.5, 1.0]
    t34.vT = [1.0, 1.0, 1.0, 1.0]
    t34.owner = [0, 1, 1, 0, 2, 3]
    t34.marL = 3.0
    t34.marR = 3.0
    let c35 = WordDoc.Cell()
    c35.c0 = 0
    c35.c1 = 0
    c35.r0 = 0
    c35.r1 = 1
    c35.x0 = 50.0
    c35.y0 = 200.0
    c35.x1 = 150.0
    c35.y1 = 270.0
    c35.bTop = 1.0
    c35.bLeft = 1.0
    c35.bBottom = 1.0
    c35.bRight = 1.0
    c35.marL = 3.0
    c35.marR = 3.0
    let p36 = WordDoc.Para()
    p36.top = 203.0
    p36.height = 13.0
    p36.x = 53.0
    p36.x1 = 120.0
    p36.base = 213.4
    p36.size = 11.0
    p36.bold = false
    p36.italic = false
    p36.hasTab = false
    p36.color = 0
    p36.align = 0
    let k37 = WordDoc.Tok()
    k37.t = 0
    k37.text = "g\u{1ED9}p d\u{1ECD}c"
    k37.n = 1
    k37.spacing = 0.0
    k37.pos = 0.0
    k37.size = 11.0
    k37.bold = false
    k37.italic = false
    k37.underline = false
    k37.color = 0
    p36.toks.append(k37)
    c35.paras.append(p36)
    t34.cells.append(c35)
    let c38 = WordDoc.Cell()
    c38.c0 = 1
    c38.c1 = 2
    c38.r0 = 0
    c38.r1 = 0
    c38.x0 = 150.0
    c38.y0 = 200.0
    c38.x1 = 540.0
    c38.y1 = 230.0
    c38.bTop = 0.1
    c38.bLeft = 20.0
    c38.bBottom = 0.0
    c38.bRight = 1.0
    c38.marL = 3.0
    c38.marR = 3.0
    let p39 = WordDoc.Para()
    p39.top = 204.0
    p39.height = 13.0
    p39.x = 300.0
    p39.x1 = 390.0
    p39.base = 214.4
    p39.size = 11.0
    p39.bold = false
    p39.italic = false
    p39.hasTab = false
    p39.color = 0
    p39.align = 1
    let k40 = WordDoc.Tok()
    k40.t = 0
    k40.text = "g\u{1ED9}p ngang"
    k40.n = 1
    k40.spacing = 0.0
    k40.pos = 0.0
    k40.size = 11.0
    k40.bold = false
    k40.italic = false
    k40.underline = false
    k40.color = 0
    p39.toks.append(k40)
    c38.paras.append(p39)
    let p41 = WordDoc.Para()
    p41.top = 217.0
    p41.height = 12.0
    p41.x = 153.0
    p41.x1 = 200.0
    p41.base = 226.6
    p41.size = 10.0
    p41.bold = false
    p41.italic = false
    p41.hasTab = false
    p41.color = 0
    p41.align = 2
    let k42 = WordDoc.Tok()
    k42.t = 0
    k42.text = "d\u{F2}ng 2"
    k42.n = 1
    k42.spacing = 0.0
    k42.pos = 0.0
    k42.size = 10.0
    k42.bold = false
    k42.italic = false
    k42.underline = false
    k42.color = 0
    p41.toks.append(k42)
    c38.paras.append(p41)
    t34.cells.append(c38)
    let c43 = WordDoc.Cell()
    c43.c0 = 1
    c43.c1 = 1
    c43.r0 = 1
    c43.r1 = 1
    c43.x0 = 150.0
    c43.y0 = 230.0
    c43.x1 = 300.0
    c43.y1 = 270.0
    c43.bTop = 0.5
    c43.bLeft = 1.0
    c43.bBottom = 1.0
    c43.bRight = 0.0
    c43.marL = 1.0
    c43.marR = 4.0
    let p44 = WordDoc.Para()
    p44.top = 233.0
    p44.height = 13.0
    p44.x = 151.0
    p44.x1 = 250.0
    p44.base = 243.4
    p44.size = 11.0
    p44.bold = false
    p44.italic = false
    p44.hasTab = false
    p44.color = 0
    p44.align = 0
    let k45 = WordDoc.Tok()
    k45.t = 0
    k45.text = "l\u{1EC1} ri\u{EA}ng"
    k45.n = 1
    k45.spacing = 0.0
    k45.pos = 0.0
    k45.size = 11.0
    k45.bold = false
    k45.italic = false
    k45.underline = false
    k45.color = 0
    p44.toks.append(k45)
    c43.paras.append(p44)
    t34.cells.append(c43)
    let c46 = WordDoc.Cell()
    c46.c0 = 2
    c46.c1 = 2
    c46.r0 = 1
    c46.r1 = 1
    c46.x0 = 300.0
    c46.y0 = 230.0
    c46.x1 = 540.0
    c46.y1 = 270.0
    c46.bTop = 0.0
    c46.bLeft = 0.0
    c46.bBottom = 0.0
    c46.bRight = 0.0
    c46.marL = 3.0
    c46.marR = 3.0
    t34.cells.append(c46)
    b33.table = t34
    b33.top = 200.0
    b33.bottom = 270.0
    b33.x0 = 50.0
    b33.x1 = 540.0
    b33.overlaps = false
    g1.blocks.append(b33)
    let b47 = WordDoc.Block()
    let t48 = WordDoc.Table()
    t48.cols = 1
    t48.rows = 1
    t48.xs = [50.0, 290.0]
    t48.ys = [270.5, 300.0]
    t48.hT = [1.0, 1.0]
    t48.vT = [1.0, 1.0]
    t48.owner = [0]
    t48.marL = 2.0
    t48.marR = 2.0
    let c49 = WordDoc.Cell()
    c49.c0 = 0
    c49.c1 = 0
    c49.r0 = 0
    c49.r1 = 0
    c49.x0 = 50.0
    c49.y0 = 270.5
    c49.x1 = 290.0
    c49.y1 = 300.0
    c49.bTop = 1.0
    c49.bLeft = 1.0
    c49.bBottom = 1.0
    c49.bRight = 1.0
    c49.marL = 2.0
    c49.marR = 2.0
    t48.cells.append(c49)
    b47.table = t48
    b47.top = 270.5
    b47.bottom = 300.0
    b47.x0 = 50.0
    b47.x1 = 290.0
    b47.overlaps = false
    g1.blocks.append(b47)
    let b50 = WordDoc.Block()
    let t51 = WordDoc.Table()
    t51.cols = 2
    t51.rows = 1
    t51.xs = [320.0, 430.0, 540.0]
    t51.ys = [280.0, 310.0]
    t51.hT = [0.75, 0.75]
    t51.vT = [0.75, 0.75, 0.75]
    t51.owner = [0, 1]
    t51.marL = 1.5
    t51.marR = 1.5
    let c52 = WordDoc.Cell()
    c52.c0 = 0
    c52.c1 = 0
    c52.r0 = 0
    c52.r1 = 0
    c52.x0 = 320.0
    c52.y0 = 280.0
    c52.x1 = 430.0
    c52.y1 = 310.0
    c52.bTop = 0.75
    c52.bLeft = 0.75
    c52.bBottom = 0.75
    c52.bRight = 0.75
    c52.marL = 1.5
    c52.marR = 1.5
    let p53 = WordDoc.Para()
    p53.top = 283.0
    p53.height = 12.0
    p53.x = 322.0
    p53.x1 = 380.0
    p53.base = 292.6
    p53.size = 10.0
    p53.bold = false
    p53.italic = false
    p53.hasTab = false
    p53.color = 0
    p53.align = 0
    let k54 = WordDoc.Tok()
    k54.t = 0
    k54.text = "c\u{1EA1}nh b\u{1EA3}ng"
    k54.n = 1
    k54.spacing = 0.0
    k54.pos = 0.0
    k54.size = 10.0
    k54.bold = false
    k54.italic = false
    k54.underline = false
    k54.color = 0
    p53.toks.append(k54)
    c52.paras.append(p53)
    t51.cells.append(c52)
    let c55 = WordDoc.Cell()
    c55.c0 = 1
    c55.c1 = 1
    c55.r0 = 0
    c55.r1 = 0
    c55.x0 = 430.0
    c55.y0 = 280.0
    c55.x1 = 540.0
    c55.y1 = 310.0
    c55.bTop = 0.75
    c55.bLeft = 0.75
    c55.bBottom = 0.75
    c55.bRight = 0.75
    c55.marL = 1.5
    c55.marR = 1.5
    t51.cells.append(c55)
    b50.table = t51
    b50.top = 280.0
    b50.bottom = 310.0
    b50.x0 = 320.0
    b50.x1 = 540.0
    b50.overlaps = true
    g1.blocks.append(b50)
    let b56 = WordDoc.Block()
    let p57 = WordDoc.Para()
    p57.top = 320.0
    p57.height = 14.625
    p57.x = 50.0
    p57.x1 = 300.0
    p57.base = 331.7
    p57.size = 13.0
    p57.bold = false
    p57.italic = false
    p57.hasTab = false
    p57.color = 0
    p57.align = 0
    let k58 = WordDoc.Tok()
    k58.t = 0
    k58.text = "Sau hai b\u{1EA3}ng li\u{1EC1}n nhau"
    k58.n = 1
    k58.spacing = 0.0
    k58.pos = 0.0
    k58.size = 13.0
    k58.bold = false
    k58.italic = false
    k58.underline = false
    k58.color = 0
    p57.toks.append(k58)
    b56.para = p57
    b56.top = 320.0
    b56.bottom = p57.top + p57.height
    b56.x0 = 50.0
    b56.x1 = 300.0
    g1.blocks.append(b56)
    let f59 = WordDoc.Pic()
    f59.x = 100.0
    f59.y = 60.0
    f59.w = 80.0
    f59.h = 40.0
    f59.data = [1, 2, 3]
    f59.ext = "png"
    f59.px0 = 0
    f59.py0 = 0
    f59.px1 = 10
    f59.py1 = 5
    g1.pics.append(f59)
    let f60 = WordDoc.Pic()
    f60.x = 10.0
    f60.y = 500.0
    f60.w = 50.0
    f60.h = 50.0
    f60.ext = "jpeg"
    f60.px0 = 0
    f60.py0 = 0
    f60.px1 = 1
    f60.py1 = 1
    g1.pics.append(f60)
    let f61 = WordDoc.Pic()
    f61.x = 300.0
    f61.y = 700.0
    f61.w = 120.5
    f61.h = 60.25
    f61.data = [9]
    f61.ext = "jpeg"
    f61.px0 = 5
    f61.py0 = 6
    f61.px1 = 7
    f61.py1 = 8
    g1.pics.append(f61)
    let r62 = WordDoc.Rule()
    r62.horiz = true
    r62.pos = 345.5
    r62.a = 50.0
    r62.b = 540.0
    r62.t = 0.01
    g1.rules.append(r62)
    let r63 = WordDoc.Rule()
    r63.horiz = false
    r63.pos = 560.25
    r63.a = 100.0
    r63.b = 400.0
    r63.t = 3.0
    g1.rules.append(r63)
    let x64 = WordDoc.Box()
    x64.x = 60.0
    x64.y = 400.0
    x64.w = 180.0
    x64.h = 14.0
    let p65 = WordDoc.Para()
    p65.top = 400.0
    p65.height = 14.0
    p65.x = 60.0
    p65.x1 = 240.0
    p65.base = 411.2
    p65.size = 13.0
    p65.bold = false
    p65.italic = false
    p65.hasTab = false
    p65.color = 0
    p65.align = 1
    let k66 = WordDoc.Tok()
    k66.t = 0
    k66.text = "h\u{1ED9}p ch\u{1EEF}"
    k66.n = 1
    k66.spacing = 0.0
    k66.pos = 0.0
    k66.size = 13.0
    k66.bold = false
    k66.italic = false
    k66.underline = false
    k66.color = 0
    p65.toks.append(k66)
    let k67 = WordDoc.Tok()
    k67.t = 1
    k67.text = ""
    k67.n = 1
    k67.spacing = 0.0
    k67.pos = 0.0
    k67.size = 13.0
    k67.bold = false
    k67.italic = false
    k67.underline = false
    k67.color = 0
    p65.toks.append(k67)
    let k68 = WordDoc.Tok()
    k68.t = 0
    k68.text = "neo"
    k68.n = 1
    k68.spacing = 0.0
    k68.pos = 0.0
    k68.size = 13.0
    k68.bold = true
    k68.italic = false
    k68.underline = false
    k68.color = 0
    p65.toks.append(k68)
    x64.para = p65
    g1.boxes.append(x64)
    pages.append(g1)
    let g69 = WordDoc.Page()
    g69.width = 842.0
    g69.height = 595.0
    let b70 = WordDoc.Block()
    let p71 = WordDoc.Para()
    p71.top = 1e+300
    p71.height = Double.nan
    p71.x = -Double.infinity
    p71.x1 = Double.infinity
    p71.base = 0.0
    p71.size = Double.nan
    p71.bold = false
    p71.italic = false
    p71.hasTab = true
    p71.color = 0
    p71.align = 0
    let k72 = WordDoc.Tok()
    k72.t = 0
    k72.text = "v\u{F4} c\u{1EF1}c"
    k72.n = 1
    k72.spacing = Double.infinity
    k72.pos = 0.0
    k72.size = 1e+308
    k72.bold = false
    k72.italic = false
    k72.underline = false
    k72.color = 0
    p71.toks.append(k72)
    let k73 = WordDoc.Tok()
    k73.t = 2
    k73.text = ""
    k73.n = 1
    k73.spacing = 0.0
    k73.pos = Double.nan
    k73.size = 13.0
    k73.bold = false
    k73.italic = false
    k73.underline = false
    k73.color = 0
    p71.toks.append(k73)
    let k74 = WordDoc.Tok()
    k74.t = 1
    k74.text = ""
    k74.n = 1
    k74.spacing = Double.nan
    k74.pos = 0.0
    k74.size = Double.nan
    k74.bold = false
    k74.italic = false
    k74.underline = false
    k74.color = 0
    p71.toks.append(k74)
    let k75 = WordDoc.Tok()
    k75.t = 2
    k75.text = ""
    k75.n = 1
    k75.spacing = 0.0
    k75.pos = 1e+300
    k75.size = 13.0
    k75.bold = false
    k75.italic = false
    k75.underline = false
    k75.color = 0
    p71.toks.append(k75)
    b70.para = p71
    b70.top = 1e+300
    b70.bottom = p71.top + p71.height
    b70.x0 = -Double.infinity
    b70.x1 = Double.infinity
    g69.blocks.append(b70)
    let b76 = WordDoc.Block()
    let p77 = WordDoc.Para()
    p77.top = -1e+300
    p77.height = 1e+300
    p77.x = Double.nan
    p77.x1 = Double.nan
    p77.base = Double.nan
    p77.size = 0.0
    p77.bold = false
    p77.italic = false
    p77.hasTab = false
    p77.color = 0
    p77.align = 0
    let k78 = WordDoc.Tok()
    k78.t = 0
    k78.text = "\u{E2}m"
    k78.n = 1
    k78.spacing = 0.0
    k78.pos = 0.0
    k78.size = -5.0
    k78.bold = false
    k78.italic = false
    k78.underline = false
    k78.color = -1
    p77.toks.append(k78)
    b76.para = p77
    b76.top = -1e+300
    b76.bottom = p77.top + p77.height
    b76.x0 = Double.nan
    b76.x1 = Double.nan
    g69.blocks.append(b76)
    let b79 = WordDoc.Block()
    let p80 = WordDoc.Para()
    p80.top = 5e+17
    p80.height = 4e+17
    p80.x = 1e+19
    p80.x1 = -1e+19
    p80.base = 0.0
    p80.size = 4.6e+18
    p80.bold = false
    p80.italic = false
    p80.hasTab = false
    p80.color = 0
    p80.align = 0
    let k81 = WordDoc.Tok()
    k81.t = 0
    k81.text = "tr\u{E0}n"
    k81.n = 1
    k81.spacing = 0.0
    k81.pos = 0.0
    k81.size = 4.6e+18
    k81.bold = false
    k81.italic = false
    k81.underline = false
    k81.color = 0
    p80.toks.append(k81)
    b79.para = p80
    b79.top = 5e+17
    b79.bottom = p80.top + p80.height
    b79.x0 = 1e+19
    b79.x1 = -1e+19
    g69.blocks.append(b79)
    let b82 = WordDoc.Block()
    let t83 = WordDoc.Table()
    t83.cols = 2
    t83.rows = 2
    t83.xs = [Double.nan, 10.0, Double.infinity]
    t83.ys = [-1e+300, 1e+300, Double.nan]
    t83.hT = [Double.infinity, Double.nan, -1.0]
    t83.vT = [Double.nan, Double.infinity, 0.0]
    t83.owner = [0, 1, 0, 1]
    t83.marL = Double.infinity
    t83.marR = Double.nan
    let c84 = WordDoc.Cell()
    c84.c0 = 0
    c84.c1 = 0
    c84.r0 = 0
    c84.r1 = 1
    c84.x0 = Double.nan
    c84.y0 = -1e+300
    c84.x1 = 10.0
    c84.y1 = Double.nan
    c84.bTop = Double.infinity
    c84.bLeft = Double.nan
    c84.bBottom = -1.0
    c84.bRight = 1e+300
    c84.marL = Double.nan
    c84.marR = -Double.infinity
    let p85 = WordDoc.Para()
    p85.top = Double.nan
    p85.height = Double.infinity
    p85.x = Double.nan
    p85.x1 = 1e+300
    p85.base = 0.0
    p85.size = 13.0
    p85.bold = false
    p85.italic = false
    p85.hasTab = false
    p85.color = 0
    p85.align = 0
    let k86 = WordDoc.Tok()
    k86.t = 0
    k86.text = "\u{F4}"
    k86.n = 1
    k86.spacing = 0.0
    k86.pos = 0.0
    k86.size = 13.0
    k86.bold = false
    k86.italic = false
    k86.underline = false
    k86.color = 0
    p85.toks.append(k86)
    c84.paras.append(p85)
    t83.cells.append(c84)
    let c87 = WordDoc.Cell()
    c87.c0 = 1
    c87.c1 = 1
    c87.r0 = 0
    c87.r1 = 1
    c87.x0 = 10.0
    c87.y0 = -1e+300
    c87.x1 = Double.infinity
    c87.y1 = Double.nan
    c87.bTop = 0.0001
    c87.bLeft = 12.0
    c87.bBottom = 13.0
    c87.bRight = 1e-300
    c87.marL = Double.infinity
    c87.marR = 2.0
    t83.cells.append(c87)
    b82.table = t83
    b82.top = -1e+300
    b82.bottom = Double.nan
    b82.x0 = Double.nan
    b82.x1 = Double.infinity
    b82.overlaps = false
    g69.blocks.append(b82)
    let b88 = WordDoc.Block()
    let p89 = WordDoc.Para()
    p89.top = 4.6e+17
    p89.height = 4.6e+17
    p89.x = 0.0
    p89.x1 = 1.0
    p89.base = 0.0
    p89.size = 13.0
    p89.bold = false
    p89.italic = false
    p89.hasTab = false
    p89.color = 0
    p89.align = 0
    let k90 = WordDoc.Tok()
    k90.t = 0
    k90.text = "x"
    k90.n = 1
    k90.spacing = 0.0
    k90.pos = 0.0
    k90.size = 13.0
    k90.bold = false
    k90.italic = false
    k90.underline = false
    k90.color = 0
    p89.toks.append(k90)
    b88.para = p89
    b88.top = 4.6e+17
    b88.bottom = p89.top + p89.height
    b88.x0 = 0.0
    b88.x1 = 1.0
    g69.blocks.append(b88)
    let f91 = WordDoc.Pic()
    f91.x = Double.nan
    f91.y = Double.infinity
    f91.w = Double.infinity
    f91.h = Double.nan
    f91.data = [0]
    f91.ext = "png"
    f91.px0 = 0
    f91.py0 = 0
    f91.px1 = 0
    f91.py1 = 0
    g69.pics.append(f91)
    let f92 = WordDoc.Pic()
    f92.x = -1e+300
    f92.y = 1e+300
    f92.w = -5.0
    f92.h = 0.0
    f92.data = [1]
    f92.ext = "gif"
    f92.px0 = 0
    f92.py0 = 0
    f92.px1 = 0
    f92.py1 = 0
    g69.pics.append(f92)
    let r93 = WordDoc.Rule()
    r93.horiz = true
    r93.pos = Double.nan
    r93.a = -1e+300
    r93.b = 1e+300
    r93.t = Double.infinity
    g69.rules.append(r93)
    let r94 = WordDoc.Rule()
    r94.horiz = false
    r94.pos = Double.infinity
    r94.a = Double.nan
    r94.b = 5.0
    r94.t = -1.0
    g69.rules.append(r94)
    let x95 = WordDoc.Box()
    x95.x = Double.nan
    x95.y = -5.0
    x95.w = Double.nan
    x95.h = -5.0
    let p96 = WordDoc.Para()
    p96.top = 0.0
    p96.height = 0.0
    p96.x = 0.0
    p96.x1 = 0.0
    p96.base = 0.0
    p96.size = 13.0
    p96.bold = false
    p96.italic = false
    p96.hasTab = false
    p96.color = 0
    p96.align = 2
    x95.para = p96
    g69.boxes.append(x95)
    let x97 = WordDoc.Box()
    x97.x = 1e+300
    x97.y = 1e+300
    x97.w = 1e+300
    x97.h = Double.infinity
    let p98 = WordDoc.Para()
    p98.top = Double.infinity
    p98.height = Double.infinity
    p98.x = Double.infinity
    p98.x1 = Double.infinity
    p98.base = 0.0
    p98.size = 13.0
    p98.bold = false
    p98.italic = false
    p98.hasTab = false
    p98.color = 0
    p98.align = 1
    let k99 = WordDoc.Tok()
    k99.t = 0
    k99.text = "xa"
    k99.n = 1
    k99.spacing = 0.0
    k99.pos = 0.0
    k99.size = 13.0
    k99.bold = false
    k99.italic = false
    k99.underline = false
    k99.color = 0
    p98.toks.append(k99)
    x97.para = p98
    g69.boxes.append(x97)
    pages.append(g69)
    let g100 = WordDoc.Page()
    g100.width = 100.0
    g100.height = 100.0
    let f101 = WordDoc.Pic()
    f101.x = 0.0
    f101.y = 0.0
    f101.w = 100.0
    f101.h = 100.0
    f101.ext = "png"
    f101.px0 = 0
    f101.py0 = 0
    f101.px1 = 1
    f101.py1 = 1
    g100.pics.append(f101)
    pages.append(g100)
    let g102 = WordDoc.Page()
    g102.width = 595.276
    g102.height = 841.89
    let b103 = WordDoc.Block()
    let p104 = WordDoc.Para()
    p104.top = 700.0
    p104.height = 14.625
    p104.x = 250.0
    p104.x1 = 320.0
    p104.base = 711.7
    p104.size = 13.0
    p104.bold = false
    p104.italic = false
    p104.hasTab = false
    p104.color = 0
    p104.align = 0
    let k105 = WordDoc.Tok()
    k105.t = 0
    k105.text = "ng\u{1EAF}n"
    k105.n = 1
    k105.spacing = 0.0
    k105.pos = 0.0
    k105.size = 13.0
    k105.bold = false
    k105.italic = false
    k105.underline = false
    k105.color = 0
    p104.toks.append(k105)
    b103.para = p104
    b103.top = 700.0
    b103.bottom = p104.top + p104.height
    b103.x0 = 250.0
    b103.x1 = 320.0
    g102.blocks.append(b103)
    pages.append(g102)
    let g106 = WordDoc.Page()
    g106.width = 595.276
    g106.height = 841.89
    let x107 = WordDoc.Box()
    x107.x = 72.0
    x107.y = 72.0
    x107.w = 100.0
    x107.h = 14.0
    let p108 = WordDoc.Para()
    p108.top = 72.0
    p108.height = 14.0
    p108.x = 72.0
    p108.x1 = 172.0
    p108.base = 83.2
    p108.size = 13.0
    p108.bold = false
    p108.italic = false
    p108.hasTab = false
    p108.color = 0
    p108.align = 0
    let k109 = WordDoc.Tok()
    k109.t = 0
    k109.text = "ch\u{1EC9} c\u{F3} h\u{1ED9}p ch\u{1EEF}"
    k109.n = 1
    k109.spacing = 0.0
    k109.pos = 0.0
    k109.size = 13.0
    k109.bold = false
    k109.italic = false
    k109.underline = false
    k109.color = 0
    p108.toks.append(k109)
    x107.para = p108
    g106.boxes.append(x107)
    pages.append(g106)
    let g110 = WordDoc.Page()
    g110.width = Double.nan
    g110.height = Double.infinity
    let b111 = WordDoc.Block()
    let p112 = WordDoc.Para()
    p112.top = 10.0
    p112.height = 10.0
    p112.x = 10.0
    p112.x1 = 20.0
    p112.base = 18.0
    p112.size = 13.0
    p112.bold = false
    p112.italic = false
    p112.hasTab = false
    p112.color = 0
    p112.align = 0
    let k113 = WordDoc.Tok()
    k113.t = 0
    k113.text = "kh\u{1ED5} l\u{1EA1}"
    k113.n = 1
    k113.spacing = 0.0
    k113.pos = 0.0
    k113.size = 13.0
    k113.bold = false
    k113.italic = false
    k113.underline = false
    k113.color = 0
    p112.toks.append(k113)
    b111.para = p112
    b111.top = 10.0
    b111.bottom = p112.top + p112.height
    b111.x0 = 10.0
    b111.x1 = 20.0
    g110.blocks.append(b111)
    pages.append(g110)
    return pages
}

func writeFile(_ path: String, _ d: [UInt8]) {
    FileManager.default.createFile(atPath: path, contents: Data(d))
}

/// Như DumpCore.dumpWord: với mỗi trang <tên>.ppm + <tên>.words.tsv và mỗi khổ giấy ghi mô hình trang, document.xml của
/// tài liệu một trang, file .docx; với từng hình: khung cắt + mã băm điểm ảnh; với tài liệu gồm mọi trang: từng phần của DOCX.
func runDumpWord(_ inDir: String, _ outDir: String) -> Int32 {
    let fm = FileManager.default
    guard let names = try? fm.contentsOfDirectory(atPath: inDir) else {
        print("không đọc được \(inDir)")
        return 2
    }
    try? fm.createDirectory(atPath: outDir, withIntermediateDirectories: true)
    var lines = [String]()
    var all = [[WordDoc.Page]](repeating: [], count: 3)
    for m in 0..<3 {
        try? fm.createDirectory(atPath: "\(outDir)/docx_\(wordModes[m])", withIntermediateDirectories: true)
        try? fm.createDirectory(atPath: "\(outDir)/all.\(wordModes[m])", withIntermediateDirectories: true)
    }
    var tAna = 0.0, tPic = 0.0, tXml = 0.0
    var nPages = 0
    for n in names.sorted() where n.hasSuffix(".ppm") {
        let name = String(n.dropLast(4))
        guard let words = readWordsTSV("\(inDir)/\(name).words.tsv") else { continue }
        guard let img = readPPM(inDir + "/" + n) else {
            print("không đọc được \(n)")
            return 2
        }
        nPages += 1
        for m in 0..<3 {
            let tag = "\(name) \(wordModes[m])"
            var t0 = Date()
            let r = DocLayout.analyze(img.rgba, img.w, img.h, words, m)
            tAna += Date().timeIntervalSince(t0)
            let page = r.page
            t0 = Date()
            var picLines = [String]()
            for (pi, pic) in page.pics.enumerated() {
                let pix = r.picture(pic)
                let pwd = max(1, pic.px1 - pic.px0), phd = max(1, pic.py1 - pic.py0)
                let data = pngStored(pix, pwd, phd)
                pic.data = data
                pic.ext = "png"
                picLines.append("pic \(tag) \(pi) \(pic.px0) \(pic.py0) \(pic.px1) \(pic.py1) \(pix.count / 4) \(hex16(fnv(pix))) \(data.count)")
            }
            tPic += Date().timeIntervalSince(t0)
            r.release()
            writeFile("\(outDir)/\(name).\(wordModes[m]).model.txt", Array(modelText(r).utf8))
            t0 = Date()
            let xml = Array(DocxWriter.documentXml([page]).utf8)
            tXml += Date().timeIntervalSince(t0)
            writeFile("\(outDir)/\(name).\(wordModes[m]).document.xml", xml)
            var nTables = 0, nParas = 0
            for b in page.blocks {
                if b.table != nil {
                    nTables += 1
                } else {
                    nParas += 1
                }
            }
            var s = "word \(tag) \(img.w) \(img.h) words=\(words.count) skew=\(hx(r.skew)) tables=\(r.tables) lines=\(r.lines)"
            s += " blocks=\(page.blocks.count) tableBlocks=\(nTables) paras=\(nParas) boxes=\(page.boxes.count) pics=\(page.pics.count)"
            s += " rules=\(page.rules.count) xml=\(xml.count) \(hex16(fnv(xml)))"
            lines.append(s)
            lines.append(contentsOf: picLines)
            let docx = DocxWriter.write(pages: [page], title: name, isoTime: wordTime, deflate: tinyDeflate)
            writeFile("\(outDir)/docx_\(wordModes[m])/\(name).docx", [UInt8](docx))
            all[m].append(page)
        }
    }
    for m in 0..<3 where !all[m].isEmpty {
        let title = "Tất cả các trang — \(wordModes[m]) <&\">"
        for p in DocxWriter.parts(all[m], title, wordTime) {
            writeFile("\(outDir)/all.\(wordModes[m])/" + p.name.replacingOccurrences(of: "/", with: "~"), p.data)
            lines.append("part \(wordModes[m]) \(p.name) \(p.data.count) \(hex16(fnv(p.data))) \(p.deflate ? 1 : 0)")
        }
        let docx = DocxWriter.write(pages: all[m], title: title, isoTime: wordTime, deflate: tinyDeflate)
        writeFile("\(outDir)/docx_\(wordModes[m])/tat_ca.docx", [UInt8](docx))
    }
    // các trang dựng tay (không qua DocLayout): từng phần của DOCX + file .docx
    do {
        try? fm.createDirectory(atPath: "\(outDir)/all.synthetic", withIntermediateDirectories: true)
        try? fm.createDirectory(atPath: "\(outDir)/docx_synthetic", withIntermediateDirectories: true)
        let title = "Trang dựng tay \u{1}<&>"
        for p in DocxWriter.parts(syntheticPages(), title, wordTime) {
            writeFile("\(outDir)/all.synthetic/" + p.name.replacingOccurrences(of: "/", with: "~"), p.data)
            lines.append("part synthetic \(p.name) \(p.data.count) \(hex16(fnv(p.data))) \(p.deflate ? 1 : 0)")
        }
        let docx = DocxWriter.write(pages: syntheticPages(), title: title, isoTime: wordTime, deflate: tinyDeflate)
        writeFile("\(outDir)/docx_synthetic/tat_ca.docx", [UInt8](docx))
    }
    let text = lines.joined(separator: "\n") + "\n"
    try? text.write(toFile: outDir + "/word_results.txt", atomically: true, encoding: .utf8)
    print("Swift (Word): \(nPages) trang x 3 khổ giấy, phân tích \(Int(tAna * 1000)) ms, cắt hình \(Int(tPic * 1000)) ms, document.xml \(Int(tXml * 1000)) ms")
    return 0
}

let args = CommandLine.arguments
if args.count >= 4, args[1] == "--dump" {
    exit(runDump(args[2], args[3]))
} else if args.count >= 4, args[1] == "--dump-word" {
    exit(runDumpWord(args[2], args[3]))
} else if args.count >= 2, args[1] == "--fuzz" {
    exit(runFuzz(args.count >= 3 ? (Int(args[2]) ?? 60) : 60, args.count >= 4 ? args[3] : nil))
} else if args.count >= 3 {
    exit(runGroundTruth(args[1], args[2]))
} else {
    print("cách dùng: algotest gt.txt thư-mục-ppm | --dump dữ-liệu kết-quả | --dump-word trang kết-quả | --fuzz [số-vòng] [thư-mục]")
    exit(2)
}
