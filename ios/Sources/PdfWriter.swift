import Foundation

/// Ghi PDF tối giản nhúng trực tiếp JPEG (port từ PdfWriter.java).
/// Ảnh từng trang được đọc và ghi lần lượt nên không giữ cả tài liệu trong bộ nhớ.
enum PdfWriter {
    static let sizeA4 = 0
    static let sizeLetter = 1
    static let sizeFit = 2

    struct JpegInfo {
        var width = 0, height = 0, components = 3
    }

    struct PdfError: LocalizedError {
        let message: String
        var errorDescription: String? { message }
    }

    /// Đọc kích thước ảnh từ phần đầu JPEG (đi qua từng đoạn cho tới SOF). nil nếu không phải JPEG hợp lệ.
    static func jpegInfo(_ d: Data) -> JpegInfo? {
        return d.withUnsafeBytes { (raw: UnsafeRawBufferPointer) -> JpegInfo? in
            let n = raw.count
            guard n > 4, raw[0] == 0xFF, raw[1] == 0xD8 else { return nil }
            var i = 2
            while i < n {
                if raw[i] != 0xFF {
                    i += 1
                    continue
                }
                // bỏ các byte đệm 0xFF liên tiếp
                var m = i + 1
                while m < n && raw[m] == 0xFF { m += 1 }
                if m >= n { return nil }
                let marker = raw[m]
                if marker == 0xD8 || marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7) {
                    i = m + 1
                    continue
                }
                if m + 2 >= n { return nil }
                let len = Int(raw[m + 1]) << 8 | Int(raw[m + 2])
                let sof = marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC
                if sof {
                    if m + 8 >= n { return nil }
                    var info = JpegInfo()
                    info.height = Int(raw[m + 4]) << 8 | Int(raw[m + 5])
                    info.width = Int(raw[m + 6]) << 8 | Int(raw[m + 7])
                    info.components = Int(raw[m + 8])
                    if info.width <= 0 || info.height <= 0 { return nil }
                    if info.components != 1 && info.components != 3 && info.components != 4 { return nil }
                    return info
                }
                if marker == 0xDA { return nil }
                i = m + 1 + max(2, len)
            }
            return nil
        }
    }

    private static func fmt(_ v: Double) -> String {
        return String(format: "%.2f", locale: Locale(identifier: "en_US_POSIX"), v)
    }

    private static func pdfText(_ s: String) -> String {
        var out = "<FEFF"
        for u in s.utf16 { out += String(format: "%04X", u) }
        return out + ">"
    }

    @inline(__always) private static func pool<T>(_ body: () throws -> T) rethrows -> T {
        #if canImport(ObjectiveC)
        return try autoreleasepool(invoking: body)
        #else
        return try body()
        #endif
    }

    /// Lõi sinh PDF: `page(i)` trả về dữ liệu JPEG của trang i (có thể là file ánh xạ bộ nhớ),
    /// `emit` nhận lần lượt từng khối byte của file PDF.
    private static func generate(count n: Int, sizeMode: Int, title: String,
                                 page: (Int) throws -> Data, emit: (Data) throws -> Void) throws {
        guard n > 0 else { throw PdfError(message: "Tài liệu không có trang") }
        var offsets = [Int](repeating: 0, count: 3 + 3 * n + 1)
        var written = 0
        var pending = Data()
        func put(_ s: String) { pending.append(contentsOf: Array(s.utf8)) }
        func flush() throws {
            if pending.isEmpty { return }
            try emit(pending)
            written += pending.count
            pending.removeAll(keepingCapacity: true)
        }
        var pos: Int { written + pending.count }

        put("%PDF-1.4\n")
        pending.append(contentsOf: [0x25, 0xE2, 0xE3, 0xCF, 0xD3, 0x0A])
        offsets[1] = pos
        put("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
        offsets[2] = pos
        var kids = ""
        for i in 0..<n { kids += "\(4 + 3 * i) 0 R " }
        put("2 0 obj\n<< /Type /Pages /Kids [ \(kids)] /Count \(n) >>\nendobj\n")
        offsets[3] = pos
        put("3 0 obj\n<< /Producer \(pdfText("Quét Tài Liệu")) /Title \(pdfText(title)) >>\nendobj\n")
        for i in 0..<n {
            try pool {
                let jpg = try page(i)
                guard let info = jpegInfo(jpg) else {
                    throw PdfError(message: "Ảnh trang \(i + 1) không hợp lệ")
                }
                let pageObj = 4 + 3 * i, contentObj = pageObj + 1, imgObj = pageObj + 2
                let iw = Double(info.width), ih = Double(info.height)
                var pw: Double, ph: Double
                if sizeMode == sizeFit {
                    let k = 842.0 / max(iw, ih)
                    // ảnh rất dẹt: mỗi cạnh trang tối thiểu 3 pt (giới hạn của nhiều trình đọc PDF)
                    pw = max(3.0, iw * k)
                    ph = max(3.0, ih * k)
                } else {
                    let a = sizeMode == sizeLetter ? 612.0 : 595.276
                    let b = sizeMode == sizeLetter ? 792.0 : 841.89
                    if iw > ih { pw = b; ph = a } else { pw = a; ph = b }
                }
                let s = min(pw / iw, ph / ih)
                let dw = iw * s, dh = ih * s
                let ox = (pw - dw) / 2, oy = (ph - dh) / 2
                offsets[pageObj] = pos
                put("\(pageObj) 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 \(fmt(pw)) \(fmt(ph))] /Resources << /XObject << /Im0 \(imgObj) 0 R >> /ProcSet [/PDF /ImageC /ImageB] >> /Contents \(contentObj) 0 R >>\nendobj\n")
                let content = "q \(fmt(dw)) 0 0 \(fmt(dh)) \(fmt(ox)) \(fmt(oy)) cm /Im0 Do Q\n"
                offsets[contentObj] = pos
                put("\(contentObj) 0 obj\n<< /Length \(content.utf8.count) >>\nstream\n")
                put(content)
                put("endstream\nendobj\n")
                let cs = info.components == 1 ? "/DeviceGray" : (info.components == 4 ? "/DeviceCMYK" : "/DeviceRGB")
                offsets[imgObj] = pos
                put("\(imgObj) 0 obj\n<< /Type /XObject /Subtype /Image /Width \(info.width) /Height \(info.height) /ColorSpace \(cs) /BitsPerComponent 8 /Filter /DCTDecode /Length \(jpg.count) >>\nstream\n")
                try flush()
                try emit(jpg)
                written += jpg.count
                put("\nendstream\nendobj\n")
            }
        }
        let total = 3 + 3 * n
        let xref = pos
        var x = "xref\n0 \(total + 1)\n0000000000 65535 f \n"
        for i in 1...total { x += String(format: "%010ld 00000 n \n", offsets[i]) }
        x += "trailer\n<< /Size \(total + 1) /Root 1 0 R /Info 3 0 R >>\nstartxref\n\(xref)\n%%EOF\n"
        put(x)
        try flush()
    }

    /// Tạo PDF trong bộ nhớ (dùng cho kiểm thử và tài liệu nhỏ).
    static func make(jpegs: [Data], sizeMode: Int, title: String) throws -> Data {
        var out = Data()
        try generate(count: jpegs.count, sizeMode: sizeMode, title: title, page: { jpegs[$0] }, emit: { out.append($0) })
        return out
    }

    /// Ghi PDF ra file, đọc ảnh từng trang từ đĩa (ánh xạ bộ nhớ) rồi ghi nối tiếp.
    static func write(files: [URL], to url: URL, sizeMode: Int, title: String) throws {
        let fm = FileManager.default
        let part = url.appendingPathExtension("part")
        try? fm.removeItem(at: part)
        guard fm.createFile(atPath: part.path, contents: nil) else {
            throw PdfError(message: "Không tạo được file PDF")
        }
        do {
            let handle = try FileHandle(forWritingTo: part)
            defer { try? handle.close() }
            try generate(count: files.count, sizeMode: sizeMode, title: title,
                         page: { try Data(contentsOf: files[$0], options: .mappedIfSafe) },
                         emit: { try handle.write(contentsOf: $0) })
        } catch {
            try? fm.removeItem(at: part)
            throw error
        }
        try? fm.removeItem(at: url)
        try fm.moveItem(at: part, to: url)
    }
}
