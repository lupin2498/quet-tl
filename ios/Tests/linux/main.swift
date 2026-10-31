// Kiểm thử nhanh phần thuật toán Swift (DocDetector, Enhancer, PdfWriter) trên Linux — không cần UIKit.
// Cách chạy: swiftc -O -o algotest ios/Sources/DocDetector.swift ios/Sources/Enhancer.swift \
//            ios/Sources/PdfWriter.swift ios/Tests/linux/main.swift && ./algotest tests/images/gt.txt <thư-mục-ppm>
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
    guard b.count - pos >= w * h * 3 else { return nil }
    var rgba = [UInt8](repeating: 255, count: w * h * 4)
    for i in 0..<(w * h) {
        rgba[4 * i] = b[pos + 3 * i]
        rgba[4 * i + 1] = b[pos + 3 * i + 1]
        rgba[4 * i + 2] = b[pos + 3 * i + 2]
    }
    return (w, h, rgba)
}

let args = CommandLine.arguments
guard args.count >= 3, let gtText = try? String(contentsOfFile: args[1], encoding: .utf8) else {
    print("cách dùng: algotest gt.txt thư-mục-ppm")
    exit(2)
}
let dir = args[2]
var ok = 0, total = 0, failures = 0
for line in gtText.split(separator: "\n") {
    let p = line.split(separator: " ").map(String.init)
    guard p.count >= 9 else { continue }
    let name = String(p[0].dropLast(4))
    let gt = p[1...8].compactMap { Double($0) }
    total += 1
    guard let img = readPPM(dir + "/" + name + ".ppm") else {
        print("FAIL \(name): không đọc được ảnh")
        continue
    }
    // giống Android: thu nhỏ về cạnh dài 1024 trước khi tìm
    let t0 = Date()
    let r = img.rgba.withUnsafeBufferPointer { buf in
        DocDetector.detect(rgba: buf.baseAddress!, width: img.w, height: img.h, bytesPerRow: img.w * 4)
    }
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
if ok < total { failures += 1 }

// Enhancer: chạy mọi chế độ trên một ảnh, kiểm tra nền giấy thành trắng
if let img = readPPM(dir + "/page_flat.ppm") {
    for mode in Enhancer.modes {
        var px = img.rgba
        let t0 = Date()
        px.withUnsafeMutableBufferPointer { buf in
            Enhancer.apply(buf.baseAddress!, width: img.w, height: img.h, bytesPerRow: img.w * 4, mode: mode)
        }
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
        for (k, v) in hist.enumerated() { acc += v; if acc >= half { med = k; break } }
        let pass = mode == Enhancer.original || med >= 235
        if !pass { failures += 1 }
        print("\(pass ? "PASS" : "FAIL") Enhancer \(Enhancer.name(mode)): trung vị độ sáng \(med), \(ms)ms")
    }
}

// PdfWriter: tạo PDF từ một JPEG
if let jpg = FileManager.default.contents(atPath: dir + "/sample.jpg") {
    do {
        let pdf = try PdfWriter.make(jpegs: [jpg, jpg], sizeMode: PdfWriter.sizeA4, title: "Biên bản thử")
        let head = String(decoding: pdf.prefix(8), as: UTF8.self)
        let tail = String(decoding: pdf.suffix(8), as: UTF8.self)
        let pass = head.hasPrefix("%PDF-1.4") && tail.contains("%%EOF")
        if !pass { failures += 1 }
        try pdf.write(to: URL(fileURLWithPath: dir + "/test.pdf"))
        print("\(pass ? "PASS" : "FAIL") PdfWriter: \(pdf.count) byte")
    } catch {
        failures += 1
        print("FAIL PdfWriter: \(error)")
    }
}
exit(failures == 0 ? 0 : 1)
