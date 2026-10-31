import Foundation

/// Ghi PDF tối giản nhúng trực tiếp JPEG (port từ PdfWriter.java).
enum PdfWriter {
    static let sizeA4 = 0
    static let sizeLetter = 1
    static let sizeFit = 2

    struct JpegInfo {
        var width = 0, height = 0, components = 3
    }

    static func jpegInfo(_ d: Data) -> JpegInfo? {
        let b = [UInt8](d)
        guard b.count > 4, b[0] == 0xFF, b[1] == 0xD8 else { return nil }
        var i = 2
        while i + 9 < b.count {
            if b[i] != 0xFF { i += 1; continue }
            let marker = b[i + 1]
            if marker == 0xFF { i += 1; continue }
            if marker == 0xD8 || marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7) { i += 2; continue }
            let len = Int(b[i + 2]) << 8 | Int(b[i + 3])
            let sof = marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC
            if sof {
                var info = JpegInfo()
                info.height = Int(b[i + 5]) << 8 | Int(b[i + 6])
                info.width = Int(b[i + 7]) << 8 | Int(b[i + 8])
                info.components = Int(b[i + 9])
                return info
            }
            if marker == 0xDA { break }
            i += 2 + len
        }
        return nil
    }

    private static func fmt(_ v: Double) -> String {
        return String(format: "%.2f", locale: Locale(identifier: "en_US_POSIX"), v)
    }

    private static func pdfText(_ s: String) -> String {
        var out = "<FEFF"
        for u in s.utf16 { out += String(format: "%04X", u) }
        return out + ">"
    }

    static func make(jpegs: [Data], sizeMode: Int, title: String) throws -> Data {
        var out = Data()
        var offsets = [Int](repeating: 0, count: 3 + 3 * jpegs.count + 1)
        func put(_ s: String) { out.append(s.data(using: .ascii)!) }
        put("%PDF-1.4\n")
        out.append(contentsOf: [0x25, 0xE2, 0xE3, 0xCF, 0xD3, 0x0A])
        offsets[1] = out.count
        put("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
        offsets[2] = out.count
        let kids = (0..<jpegs.count).map { "\(4 + 3 * $0) 0 R" }.joined(separator: " ")
        put("2 0 obj\n<< /Type /Pages /Kids [ \(kids) ] /Count \(jpegs.count) >>\nendobj\n")
        offsets[3] = out.count
        put("3 0 obj\n<< /Producer \(pdfText("Quét Tài Liệu")) /Title \(pdfText(title)) >>\nendobj\n")
        for (i, jpg) in jpegs.enumerated() {
            guard let info = jpegInfo(jpg) else {
                throw NSError(domain: "PdfWriter", code: 1, userInfo: [NSLocalizedDescriptionKey: "Ảnh trang \(i + 1) không hợp lệ"])
            }
            let pageObj = 4 + 3 * i, contentObj = pageObj + 1, imgObj = pageObj + 2
            let iw = Double(info.width), ih = Double(info.height)
            var pw: Double, ph: Double
            if sizeMode == sizeFit {
                let k = 842.0 / max(iw, ih)
                pw = iw * k
                ph = ih * k
            } else {
                let a = sizeMode == sizeLetter ? 612.0 : 595.276
                let b = sizeMode == sizeLetter ? 792.0 : 841.89
                if iw > ih { pw = b; ph = a } else { pw = a; ph = b }
            }
            let s = min(pw / iw, ph / ih)
            let dw = iw * s, dh = ih * s
            let ox = (pw - dw) / 2, oy = (ph - dh) / 2
            offsets[pageObj] = out.count
            put("\(pageObj) 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 \(fmt(pw)) \(fmt(ph))] /Resources << /XObject << /Im0 \(imgObj) 0 R >> /ProcSet [/PDF /ImageC /ImageB] >> /Contents \(contentObj) 0 R >>\nendobj\n")
            let content = "q \(fmt(dw)) 0 0 \(fmt(dh)) \(fmt(ox)) \(fmt(oy)) cm /Im0 Do Q\n"
            let cb = content.data(using: .ascii)!
            offsets[contentObj] = out.count
            put("\(contentObj) 0 obj\n<< /Length \(cb.count) >>\nstream\n")
            out.append(cb)
            put("endstream\nendobj\n")
            let cs = info.components == 1 ? "/DeviceGray" : (info.components == 4 ? "/DeviceCMYK" : "/DeviceRGB")
            offsets[imgObj] = out.count
            put("\(imgObj) 0 obj\n<< /Type /XObject /Subtype /Image /Width \(info.width) /Height \(info.height) /ColorSpace \(cs) /BitsPerComponent 8 /Filter /DCTDecode /Length \(jpg.count) >>\nstream\n")
            out.append(jpg)
            put("\nendstream\nendobj\n")
        }
        let total = 3 + 3 * jpegs.count
        let xref = out.count
        var x = "xref\n0 \(total + 1)\n0000000000 65535 f \n"
        for i in 1...total { x += String(format: "%010ld 00000 n \n", offsets[i]) }
        x += "trailer\n<< /Size \(total + 1) /Root 1 0 R /Info 3 0 R >>\nstartxref\n\(xref)\n%%EOF\n"
        put(x)
        return out
    }
}
