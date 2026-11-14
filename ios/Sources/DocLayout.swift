import Foundation

/// Phân tích bố cục một trang quét (ảnh đã nắn phẳng + các từ do bộ nhận dạng chữ trả về) thành mô hình trang cho Word
/// (WordDoc.Page), giữ nguyên bố cục:
///  1. độ sáng chuẩn hoá theo nền giấy -> mặt nạ mực; ảnh phân tích thu nhỏ (cạnh dài ~1000) để dò đường kẻ;
///  2. đường kẻ ngang / dọc dài, mảnh (Flatten.findRules) rồi kiểm lại ở độ phân giải đầy đủ (phủ kín, mảnh, thẳng);
///  3. khử nghiêng toạ độ theo độ dốc đường kẻ / dòng chữ (không lấy mẫu lại ảnh);
///  4. lưới bảng từ đường kẻ (phương pháp "lattice": gộp đường thẳng hàng, nhóm các đường chạm nhau, ô lưới không bị
///     đường kẻ ngăn thì gộp -> ô gộp ngang / dọc, viền từng cạnh);
///  5. từ -> ô bảng hoặc chữ tự do; gom thành dòng; đo đường chân chữ, cỡ chữ (theo bề rộng với số đo Times New Roman,
///     theo chiều cao thân chữ khi dòng ngắn), nét đậm (bề dày nét so với cả trang), màu mực;
///  6. mỗi dòng thành một đoạn đặt đúng chỗ (giãn chữ / giãn dấu cách để từng từ nằm đúng vị trí, tab cho khoảng trống
///     lớn); dòng đứng cạnh bảng hoặc chồng lên dòng khác thành hộp chữ;
///  7. mực còn lại không thuộc chữ đã nhận dạng hay đường kẻ (con dấu, chữ ký, hình, chữ viết tay không đọc được) thành
///     hình cắt từ ảnh trang; đường kẻ rời thành đường vẽ (hoặc gạch chân nếu nằm sát dưới chữ).
///
/// Port 1-1 của core/DocLayout.java (chỉ dùng Foundation; CI đối chiếu từng bit với bản Java): mọi phép tính giữ đúng kiểu
/// (Float = float, Double = double) và đúng thứ tự như bản Java; ép số thực -> số nguyên, Math.min / max / round, sắp xếp
/// ổn định, atan / sin / cos theo quy tắc Java (JavaNum); chuỗi duyệt theo mã Unicode (unicodeScalars) như codePointAt.
/// Điểm ảnh RGBX 8 bit, 4 byte mỗi điểm (R ở byte 0, byte thứ tư bỏ qua), không đệm hàng.
enum DocLayout {
    /// Một từ do bộ nhận dạng chữ trả về: nội dung + khung trên ảnh trang (điểm ảnh, gốc trên-trái).
    final class Word {
        var text: String
        var x0: Float, y0: Float, x1: Float, y1: Float
        /// Chỉ số dòng theo bộ nhận dạng (-1: không biết).
        var line: Int
        /// Độ tin cậy 0..1 do bộ nhận dạng báo (1 nếu không biết); từ dưới minConf bị bỏ (thành hình nếu có mực).
        var conf: Float = 1

        init(text: String, x0: Float, y0: Float, x1: Float, y1: Float, line: Int = -1) {
            self.text = text
            self.x0 = x0
            self.y0 = y0
            self.x1 = x1
            self.y1 = y1
            self.line = line
        }
    }

    /// Khổ trang Word và cách đặt ảnh trang lên đó (giống PdfWriter): điểm ảnh (x, y) -> (ox + x*scale, oy + y*scale) pt.
    struct Geometry {
        var pageW = 0.0, pageH = 0.0, scale = 0.0, ox = 0.0, oy = 0.0
    }

    static func geometry(_ w: Int, _ h: Int, _ sizeMode: Int) -> Geometry {
        var g = Geometry()
        let iw = Double(max(1, w)), ih = Double(max(1, h))
        if sizeMode == PdfWriter.sizeFit {
            let k = 842.0 / JavaNum.max(iw, ih)
            g.pageW = JavaNum.max(72.0, iw * k)
            g.pageH = JavaNum.max(72.0, ih * k)
        } else {
            let a = sizeMode == PdfWriter.sizeLetter ? 612.0 : 595.276
            let b = sizeMode == PdfWriter.sizeLetter ? 792.0 : 841.89
            if iw > ih {
                g.pageW = b
                g.pageH = a
            } else {
                g.pageW = a
                g.pageH = b
            }
        }
        g.scale = JavaNum.min(g.pageW / iw, g.pageH / ih)
        g.ox = (g.pageW - iw * g.scale) / 2.0
        g.oy = (g.pageH - ih * g.scale) / 2.0
        return g
    }

    // MARK: hằng số

    /// Cạnh dài của ảnh phân tích (dò đường kẻ, hình).
    static let analysisLong = 1000
    /// Ngưỡng mực của đường kẻ trên ảnh phân tích: tối hơn 0,80 nền giấy.
    static let ruleQ = 204
    /// Dung sai lưới theo cỡ chữ (điểm ảnh đầy đủ): gộp vị trí, nối đoạn đứt, coi là chạm nhau.
    static let snapK = 0.40, joinK = 1.0, touchK = 0.65
    /// Một cạnh ô lưới được coi là có kẻ khi đường kẻ phủ >= 72% cạnh.
    static let edgeCover = 0.72
    /// Hộp dòng cao 1,125 cỡ chữ: đường chân chữ ở 80% hộp trong cả Word lẫn LibreOffice (Times New Roman).
    static let lineK = (WordFont.ascent - WordFont.descent) / 0.6
    /// Lề trong tối đa của ô bảng (pt).
    static let maxCellMargin = 5.4
    static let maxPictures = 160
    /// Từ có độ tin cậy thấp hơn mức này coi như không đọc được.
    static let minConf: Float = 0.5

    @inline(__always) static func rnd(_ v: Double) -> Int {
        return JavaNum.toLong((v + 0.5).rounded(.down))
    }

    static func r20(_ v: Double) -> Double {
        return Double(rnd(v * 20.0)) / 20.0
    }

    /// Giãn chữ dưới độ phân giải hữu ích của Word coi như 0.
    static func quant(_ v: Double) -> Double {
        let q = r20(v)
        return abs(q) < 0.075 ? 0.0 : q
    }

    @inline(__always) static func clampD(_ v: Double, _ lo: Double, _ hi: Double) -> Double {
        return v < lo ? lo : (v > hi ? hi : v)
    }

    // MARK: cấu trúc làm việc

    /// Từ đang xử lý: khung mực đo trên ảnh + các số đo.
    final class W {
        var text = ""
        var glyphs = 0, idx = 0, line = 0
        var conf: Float = 1
        /// Khung của bộ nhận dạng (ảnh gốc).
        var ox0 = 0.0, oy0 = 0.0, ox1 = 0.0, oy1 = 0.0
        /// Khung mực (toạ độ đã khử nghiêng).
        var x0 = 0.0, y0 = 0.0, x1 = 0.0, y1 = 0.0
        /// Đường chân chữ (đã khử nghiêng); NaN nếu không đo được.
        var base = Double.nan
        /// Cỡ chữ (em, điểm ảnh) đo theo chiều cao thân chữ; 0 nếu không đo được.
        var emH = 0.0
        /// Bề dày nét (điểm ảnh); 0 nếu không đo được.
        var stroke = 0.0
        /// Độ "sắc" của hình chiếu mực theo cột khi để nguyên (sh0) và khi nắn nghiêng 0,14 / 0,27 (chữ nghiêng).
        var sh0 = 0.0, sh1 = 0.0, sh2 = 0.0
        var colored = false, bold = false, italic = false, drop = false
        var color = 0
        /// Số điểm mực trong khung mực (không tính đường kẻ); -1: chưa đo.
        var inkN = -1
        var table = -1, cell = -1
        /// Số đo Times New Roman của từ (1/2048 em) theo kiểu đang chọn.
        var adv = 0, lsb = 0, rsb = 0
    }

    /// Đường kẻ (điểm ảnh đầy đủ): ngang thì pos = y, a..b = x.
    final class Seg {
        var h = false
        var pos = 0.0, a = 0.0, b = 0.0, t = 0.0, slope = 0.0
        var drop = false
        var longest = 0.0
    }

    final class TCell {
        var c0 = 0, c1 = 0, r0 = 0, r1 = 0
        var x0 = 0.0, y0 = 0.0, x1 = 0.0, y1 = 0.0
        var bTop = 0.0, bLeft = 0.0, bBottom = 0.0, bRight = 0.0
        var words = [W]()
        var rows = [Row]()
    }

    final class Tbl {
        var xs = [Double](), ys = [Double](), hT = [Double](), vT = [Double]()
        var cols = 0, rows = 0
        var owner = [Int]()
        var cells = [TCell]()
        var extra = [Seg]()
        var x0 = 0.0, y0 = 0.0, x1 = 0.0, y1 = 0.0
    }

    /// Một khúc của dòng chữ (các từ liền nhau, không cách nhau bằng khoảng trống lớn): có kiểu chữ riêng.
    final class Part {
        var ws = [W]()
        var x0 = 0.0, x1 = 0.0
        /// Em theo bề rộng / chiều cao (điểm ảnh), cỡ chữ đã chọn (pt).
        var emW = 0.0, emH = 0.0, size = 0.0
        var glyphs = 0
        var bold = false, italic = false, underline = false
        var color = 0
    }

    /// Một dòng chữ (các từ cùng hàng trong một vùng chứa), chia thành các khúc cách nhau bằng tab.
    final class Row {
        var ws = [W]()
        var parts = [Part]()
        var x0 = 0.0, x1 = 0.0, y0 = 0.0, y1 = 0.0
        /// Đường chân chữ (điểm ảnh); cỡ chữ lớn nhất của các khúc (pt).
        var base = 0.0, size = 0.0
        var glyphs = 0
    }

    /// Kết quả phân tích một trang; giữ dữ liệu ảnh để cắt hình cho tới khi gọi release().
    final class Result {
        var page = WordDoc.Page()
        /// Góc nghiêng đã khử (radian) và số bảng / dòng tìm được (để kiểm thử).
        var skew = 0.0
        var tables = 0, lines = 0
        fileprivate var px: [UInt8]?
        fileprivate var w = 0, h = 0, tq = 0
        fileprivate var lq: [UInt8]?, cls: [UInt8]?
        fileprivate var bg: Enhancer.Bg?
        fileprivate var words: [W]?

        /// Điểm ảnh RGBX của hình `pic` (kích thước (px1-px0) x (py1-py0), byte thứ tư = 255): cắt từ ảnh trang, chuẩn
        /// hoá nền về trắng, xoá nét chữ đen của các từ đã nhận dạng nằm trong vùng (chữ đó đã thành chữ Word — không
        /// để bóng chữ phía sau). Vùng cắt phải nằm trong ảnh trang (phần ngoài ảnh, nếu có, để trống).
        func picture(_ pic: WordDoc.Pic) -> [UInt8] {
            let x0 = pic.px0, y0 = pic.py0, x1 = pic.px1, y1 = pic.py1
            let pw = max(1, x1 &- x0), ph = max(1, y1 &- y0)
            // vùng cắt vô lý (lớn hơn mọi ảnh trang): bản Java ném lỗi; ở đây trả về một điểm trắng
            if pw > 1 << 20 || ph > 1 << 20 { return [255, 255, 255, 255] }
            guard let px = px, let lq = lq, let cls = cls, let bg = bg else {
                return [UInt8](repeating: 255, count: pw * ph * 4)
            }
            var out = [UInt8](repeating: 0, count: pw * ph * 4)
            let w = self.w, h = self.h
            // phần của vùng cắt nằm trong ảnh
            let cx0 = max(0, x0), cy0 = max(0, y0), cx1 = min(w, x1), cy1 = min(h, y1)
            var rowR = [Float](repeating: 0, count: w), rowG = [Float](repeating: 0, count: w)
            var rowB = [Float](repeating: 0, count: w)
            out.withUnsafeMutableBufferPointer { o in
                px.withUnsafeBufferPointer { p in
                    var y = cy0
                    while y < cy1 {
                        bg.row(y, &rowR, &rowG, &rowB)
                        var x = cx0
                        while x < cx1 {
                            let i = (y * w + x) * 4
                            var r = JavaNum.toInt(Float(p[i]) * 255 / JavaNum.max(Float(1), rowR[x]) + 0.5)
                            var g = JavaNum.toInt(Float(p[i + 1]) * 255 / JavaNum.max(Float(1), rowG[x]) + 0.5)
                            var b = JavaNum.toInt(Float(p[i + 2]) * 255 / JavaNum.max(Float(1), rowB[x]) + 0.5)
                            if r > 255 { r = 255 }
                            if g > 255 { g = 255 }
                            if b > 255 { b = 255 }
                            let k = ((y - y0) * pw + (x - x0)) * 4
                            o[k] = UInt8(truncatingIfNeeded: r)
                            o[k + 1] = UInt8(truncatingIfNeeded: g)
                            o[k + 2] = UInt8(truncatingIfNeeded: b)
                            o[k + 3] = 255
                            x += 1
                        }
                        y += 1
                    }
                }
                guard let words = words else { return }
                lq.withUnsafeBufferPointer { lqP in
                    cls.withUnsafeBufferPointer { clsP in
                        for wd in words {
                            if wd.drop { continue }
                            let ax0 = max(cx0, JavaNum.toInt(wd.ox0.rounded(.down)) - 2)
                            let ay0 = max(cy0, JavaNum.toInt(wd.oy0.rounded(.down)) - 2)
                            let ax1 = min(cx1, JavaNum.toInt(wd.ox1.rounded(.up)) + 2)
                            let ay1 = min(cy1, JavaNum.toInt(wd.oy1.rounded(.up)) + 2)
                            var y = ay0
                            while y < ay1 {
                                var x = ax0
                                while x < ax1 {
                                    let i = y * w + x
                                    // nét của từ đã thành chữ Word -> trắng: từ mực đen thì chỉ xoá nét đen (giữ mực màu của
                                    // con dấu / chữ ký vắt qua), từ mực màu thì xoá mọi nét trong khung
                                    if Int(lqP[i]) < 235 && (wd.colored || (clsP[i] & 1) == 0) {
                                        let k = ((y - y0) * pw + (x - x0)) * 4
                                        o[k] = 255
                                        o[k + 1] = 255
                                        o[k + 2] = 255
                                        o[k + 3] = 255
                                    }
                                    x += 1
                                }
                                y += 1
                            }
                        }
                    }
                }
            }
            return out
        }

        func release() {
            px = nil
            lq = nil
            cls = nil
            bg = nil
            words = nil
        }
    }

    /// Trang chỉ gồm ảnh trang (khi không nhận dạng được chữ / phân tích lỗi): một hình phủ kín vùng ảnh.
    static func picturePage(_ w: Int, _ h: Int, _ sizeMode: Int) -> WordDoc.Page {
        let g = geometry(w, h, sizeMode)
        let page = WordDoc.Page()
        page.width = g.pageW
        page.height = g.pageH
        let p = WordDoc.Pic()
        p.x = g.ox
        p.y = g.oy
        p.w = Double(w) * g.scale
        p.h = Double(h) * g.scale
        p.px0 = 0
        p.py0 = 0
        p.px1 = w
        p.py1 = h
        page.pics.append(p)
        return page
    }

    // MARK: phân tích

    /// Các mảng của Scan dưới dạng con trỏ, dùng trong các vòng lặp theo điểm ảnh (chỉ hợp lệ bên trong Scan.withInk).
    struct Ink {
        let px: UnsafeBufferPointer<UInt8>
        let lq: UnsafeBufferPointer<UInt8>, cls: UnsafeBufferPointer<UInt8>
        let hId: UnsafeBufferPointer<Int>, vId: UnsafeBufferPointer<Int>
        let hKeep: UnsafeBufferPointer<Bool>, vKeep: UnsafeBufferPointer<Bool>
        let w: Int, tq: Int, k: Int, aw: Int, ah: Int

        @inline(__always) func ink(_ x: Int, _ y: Int) -> Bool {
            return Int(lq[y * w + x]) < tq
        }

        /// Mực của chữ tại (x, y): không tính đường kẻ đã nhận; noColor: không tính mực màu (nét bút / con dấu vắt qua chữ đen).
        @inline(__always) func textInk(_ x: Int, _ y: Int, _ noColor: Bool) -> Bool {
            let i = y * w + x
            if Int(lq[i]) >= tq { return false }
            if noColor && (cls[i] & 1) != 0 { return false }
            return !onRule(x, y)
        }

        /// Điểm (x, y) thuộc một đường kẻ đã nhận (theo ảnh phân tích).
        @inline(__always) func onRule(_ x: Int, _ y: Int) -> Bool {
            let ax = x / k, ay = y / k
            if ax >= aw || ay >= ah { return false }
            let i = ay * aw + ax
            let a = hId[i], b = vId[i]
            return (a > 0 && hKeep[a]) || (b > 0 && vKeep[b])
        }
    }

    /// Trạng thái dùng chung trong một lần phân tích.
    final class Scan {
        var px = [UInt8]()
        var w = 0, h = 0, tq = 0
        var lq = [UInt8](), cls = [UInt8]()
        /// Ảnh phân tích: k điểm ảnh gốc mỗi chiều cho một điểm; amin = độ sáng nhỏ nhất của khối.
        var k = 1, aw = 0, ah = 0
        var amin = [UInt8]()
        /// Chỉ số (1..) đường kẻ ngang / dọc đã nhận tại từng điểm ảnh phân tích (0: không).
        var hId = [Int](), vId = [Int]()
        var hKeep = [Bool](), vKeep = [Bool]()
        /// Cỡ chữ điển hình (điểm ảnh đầy đủ).
        var hc = 0.0
        /// Khử nghiêng: quay quanh tâm trang góc -skew.
        var cs = 1.0, sn = 0.0, cx = 0.0, cy = 0.0

        func rx(_ x: Double, _ y: Double) -> Double {
            return cx + (x - cx) * cs + (y - cy) * sn
        }

        func ry(_ x: Double, _ y: Double) -> Double {
            return cy - (x - cx) * sn + (y - cy) * cs
        }

        func withInk<R>(_ body: (Ink) -> R) -> R {
            return px.withUnsafeBufferPointer { pxP in
                lq.withUnsafeBufferPointer { lqP in
                    cls.withUnsafeBufferPointer { clsP in
                        hId.withUnsafeBufferPointer { hP in
                            vId.withUnsafeBufferPointer { vP in
                                hKeep.withUnsafeBufferPointer { hkP in
                                    vKeep.withUnsafeBufferPointer { vkP in
                                        body(Ink(px: pxP, lq: lqP, cls: clsP, hId: hP, vId: vP, hKeep: hkP, vKeep: vkP,
                                                 w: w, tq: tq, k: k, aw: aw, ah: ah))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /// Phân tích trang. px: ảnh trang RGBX w x h (ảnh kết quả đã nắn phẳng / lọc), 4 byte mỗi điểm, không đệm hàng;
    /// words: các từ đã nhận dạng; sizeMode: khổ giấy (PdfWriter.size*). Không dừng chương trình do dữ liệu xấu: tệ
    /// nhất trả về trang chỉ có ảnh.
    static func analyze(_ px: [UInt8], _ w: Int, _ h: Int, _ words: [Word], _ sizeMode: Int) -> Result {
        let res = Result()
        let g = geometry(w, h, sizeMode)
        res.page.width = g.pageW
        res.page.height = g.pageH
        if w < 32 || h < 32 || w > px.count / 4 / h { return res }
        let s = Scan()
        s.px = px
        s.w = w
        s.h = h
        s.cx = Double(w) / 2.0
        s.cy = Double(h) / 2.0
        let n = w * h
        var lq = [UInt8](repeating: 0, count: n)
        var cls = [UInt8](repeating: 0, count: n)
        let bg: Enhancer.Bg = px.withUnsafeBufferPointer { buf -> Enhancer.Bg in
            // các hàm dưới đây chỉ đọc điểm ảnh
            let p = UnsafeMutablePointer<UInt8>(mutating: buf.baseAddress!)
            let b = Enhancer.backgroundFine(p, w, h, w * 4, nil)
            Flatten.normalize(p, w, h, w * 4, b, &lq, &cls)
            return b
        }
        let mode = Flatten.inkMode(lq, w, h)
        s.tq = Flatten.seedQ
        if mode >= 0 { s.tq = max(Flatten.seedQ, min(Flatten.seedMaxQ, mode + Flatten.seedOffsetQ)) }
        let inkLevel = mode >= 0 ? mode : 60
        s.lq = lq
        s.cls = cls
        res.px = px
        res.w = w
        res.h = h
        res.tq = s.tq
        res.lq = lq
        res.cls = cls
        res.bg = bg

        // ---- các từ: bỏ từ rỗng / khung hỏng
        var ws = [W]()
        var idx = 0
        for o in words {
            let t = JavaNum.trim(o.text)
            if t.unicodeScalars.isEmpty { continue }
            let x0 = Double(JavaNum.min(o.x0, o.x1)), x1 = Double(JavaNum.max(o.x0, o.x1))
            let y0 = Double(JavaNum.min(o.y0, o.y1)), y1 = Double(JavaNum.max(o.y0, o.y1))
            if !(x1 - x0 >= 1.0) || !(y1 - y0 >= 2.0) { continue }
            if x1 <= 0 || y1 <= 0 || x0 >= Double(w) || y0 >= Double(h) { continue }
            if o.conf < minConf { continue }
            let wd = W()
            wd.text = t
            wd.glyphs = max(1, WordFont.glyphCount(t))
            wd.ox0 = JavaNum.max(0.0, x0)
            wd.oy0 = JavaNum.max(0.0, y0)
            wd.ox1 = JavaNum.min(Double(w), x1)
            wd.oy1 = JavaNum.min(Double(h), y1)
            wd.line = o.line
            wd.conf = o.conf
            wd.idx = idx
            idx += 1
            ws.append(wd)
        }
        dedupeWords(&ws)
        res.words = ws
        if ws.isEmpty {
            // không nhận dạng được chữ nào: giữ nguyên trang dưới dạng một hình
            let pp = picturePage(w, h, sizeMode)
            res.page.pics.append(contentsOf: pp.pics)
            return res
        }

        // ---- ảnh phân tích + đường kẻ
        var segs = findSegs(s, ws, inkLevel)

        // ---- đo từng từ trên ảnh gốc
        s.withInk { v in
            for wd in ws { measureWord(s, v, wd, inkLevel) }
        }
        dropRuleJunk(s, ws, &segs, inkLevel)

        // ---- khử nghiêng
        let skew = estimateSkew(s, ws, segs)
        res.skew = skew
        s.cs = JavaNum.cos(skew)
        s.sn = JavaNum.sin(skew)
        for sg in segs { deskewSeg(s, sg) }
        for wd in ws { deskewWord(s, wd) }

        // ---- bảng
        var loose = [Seg]()
        var tables = detectTables(s, segs, &loose)
        // từ -> ô bảng
        for wd in ws {
            if wd.drop { continue }
            let mx = (wd.x0 + wd.x1) / 2.0, my = (wd.y0 + wd.y1) / 2.0
            var ti = 0
            while ti < tables.count && wd.table < 0 {
                let t = tables[ti]
                let ci = cellOfPoint(t, mx, my)
                if ci >= 0 {
                    wd.table = ti
                    wd.cell = ci
                    t.cells[ci].words.append(wd)
                }
                ti += 1
            }
        }
        // khung không có chữ (quanh một hình...) là hình vẽ, không phải bảng
        var ti = tables.count - 1
        while ti >= 0 {
            let t = tables[ti]
            if t.cells.count != 1 || !t.cells[0].words.isEmpty {
                ti -= 1
                continue
            }
            let c = t.cells[0]
            if c.bTop > 0 { loose.append(seg(true, c.y0, c.x0, c.x1, c.bTop)) }
            if c.bBottom > 0 { loose.append(seg(true, c.y1, c.x0, c.x1, c.bBottom)) }
            if c.bLeft > 0 { loose.append(seg(false, c.x0, c.y0, c.y1, c.bLeft)) }
            if c.bRight > 0 { loose.append(seg(false, c.x1, c.y0, c.y1, c.bRight)) }
            tables.remove(at: ti)
            for wd in ws where wd.table > ti { wd.table -= 1 }
            ti -= 1
        }
        for t in tables { loose.append(contentsOf: t.extra) }
        res.tables = tables.count

        // ---- dòng chữ
        var free = [W]()
        for wd in ws where !wd.drop && wd.table < 0 { free.append(wd) }
        let freeRows = chainRows(free, tables)
        var allRows = freeRows
        for t in tables {
            for c in t.cells {
                c.rows.append(contentsOf: chainRows(c.words, nil))
                allRows.append(contentsOf: c.rows)
            }
        }
        res.lines = allRows.count
        styleRows(allRows, g)
        loose = applyUnderlines(s, freeRows, loose, g)

        // ---- dựng trang
        let page = res.page
        var tableBlocks = [WordDoc.Block]()
        for t in tables { tableBlocks.append(tableBlock(t, g)) }
        layoutFree(page, freeRows, tableBlocks, g)
        for sg in loose {
            if sg.drop { continue }
            let len = sg.b - sg.a
            if len < (sg.h ? 3.0 : 4.0) * s.hc { continue }
            let r = WordDoc.Rule()
            r.horiz = sg.h
            r.pos = (sg.h ? g.oy : g.ox) + sg.pos * g.scale
            r.a = (sg.h ? g.ox : g.oy) + sg.a * g.scale
            r.b = (sg.h ? g.ox : g.oy) + sg.b * g.scale
            r.t = JavaNum.max(0.25, sg.t * g.scale)
            page.rules.append(r)
        }
        findPictures(s, ws, page, g)
        return res
    }

    /// Dọn các khung từ chồng nhau do bộ nhận dạng trả về: hai khung gần trùng nhau (IoU >= 0,5) là một từ bị trả hai lần
    /// -> giữ từ tin cậy hơn; một khung quá rộng trùm lên khung của từ bên cạnh thì cắt bớt về phía mà nội dung của nó
    /// (theo bề rộng tự nhiên) không với tới; chồng nhau thật sự thì giữ từ tin cậy hơn.
    static func dedupeWords(_ ws: inout [W]) {
        let n = ws.count
        if n > 6000 { return }
        var dead = [Bool](repeating: false, count: n)
        for i in 0..<n {
            var j = i + 1
            while j < n && !dead[i] {
                defer { j += 1 }
                if dead[j] { continue }
                let a = ws[i], b = ws[j]
                let ox = JavaNum.min(a.ox1, b.ox1) - JavaNum.max(a.ox0, b.ox0)
                if ox <= 0 { continue }
                let oy = JavaNum.min(a.oy1, b.oy1) - JavaNum.max(a.oy0, b.oy0)
                let ha = a.oy1 - a.oy0, hb = b.oy1 - b.oy0
                if oy < 0.5 * JavaNum.min(ha, hb) { continue }
                let wa = a.ox1 - a.ox0, wb = b.ox1 - b.ox0
                let inter = ox * oy, uni = wa * ha + wb * hb - inter
                let aBetter = a.conf > b.conf || (a.conf == b.conf && a.glyphs >= b.glyphs)
                if inter >= 0.5 * uni {
                    if aBetter {
                        dead[j] = true
                    } else {
                        dead[i] = true
                    }
                    continue
                }
                if ox < 0.5 * JavaNum.min(wa, wb) { continue }
                // khung rộng hơn (big) trùm lên khung kia (small)
                let big = wa >= wb ? a : b, small = wa >= wb ? b : a
                let em = (big.oy1 - big.oy0) / 0.95
                let nat = Double(advUnits(big.text, WordFont.regular)) / WordFont.upm * em
                let sc = (small.ox0 + small.ox1) / 2.0
                if sc > big.ox0 + 0.9 * nat && small.ox0 - 1.0 > big.ox0 + 2.0 {
                    big.ox1 = JavaNum.min(big.ox1, small.ox0 - 1.0)
                } else if sc < big.ox1 - 0.9 * nat && small.ox1 + 1.0 < big.ox1 - 2.0 {
                    big.ox0 = JavaNum.max(big.ox0, small.ox1 + 1.0)
                } else if aBetter {
                    dead[j] = true
                } else {
                    dead[i] = true
                }
            }
        }
        var kept = [W]()
        kept.reserveCapacity(n)
        for i in 0..<n where !dead[i] { kept.append(ws[i]) }
        ws = kept
    }

    static func seg(_ h: Bool, _ pos: Double, _ a: Double, _ b: Double, _ t: Double) -> Seg {
        let sg = Seg()
        sg.h = h
        sg.pos = pos
        sg.a = a
        sg.b = b
        sg.t = t
        sg.longest = b - a
        return sg
    }

    /// WordFont.advanceUnits với kết quả quay vòng 32 bit như int của Java (chuỗi dài bất thường).
    @inline(__always) static func advUnits(_ t: String, _ style: Int) -> Int {
        return JavaNum.wrap32(WordFont.advanceUnits(t, style))
    }

    // MARK: đường kẻ

    /// Ảnh phân tích, cỡ chữ, đường kẻ ngang / dọc đã kiểm ở độ phân giải đầy đủ (toạ độ ảnh gốc).
    static func findSegs(_ s: Scan, _ ws: [W], _ inkLevel: Int) -> [Seg] {
        let w = s.w, h = s.h
        let L = max(w, h)
        let k = max(1, JavaNum.round(Float(L) / Float(analysisLong)))
        let aw = w / k, ah = h / k
        s.k = k
        s.aw = aw
        s.ah = ah
        let nA = aw * ah
        var amin = [UInt8](repeating: 0, count: nA), strict = [UInt8](repeating: 0, count: nA)
        var rule = [UInt8](repeating: 0, count: nA)
        let tq = s.tq
        s.lq.withUnsafeBufferPointer { lq in
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
                    if mn < ruleQ { rule[y * aw + x] = 1 }
                }
            }
        }
        s.amin = amin
        var hId = [Int](repeating: 0, count: nA), vId = [Int](repeating: 0, count: nA)
        var out = [Seg]()
        if aw < 16 || ah < 16 {
            s.hId = hId
            s.vId = vId
            s.hKeep = [false]
            s.vKeep = [false]
            s.hc = wordHeight(ws, 12.0)
            return out
        }
        var lab = [Int](repeating: 0, count: nA), queue = [Int](repeating: 0, count: nA)
        var wOut: Float = 0
        let hcA = Flatten.textHeight(strict, aw, ah, &lab, &queue, &wOut)
        // cỡ chữ: theo thành phần liên thông; trang quá ít chữ thì theo khung của bộ nhận dạng
        let hc: Double = hcA > 0 ? Double(hcA * Float(k)) : wordHeight(ws, 12.0 * Double(k))
        s.hc = JavaNum.max(4.0, hc)
        let hcU = Float(s.hc / Double(k))
        let tmax = max(2, JavaNum.round(hcU * 0.35))
        var th = [UInt8](repeating: 0, count: nA), tv = [UInt8](repeating: 0, count: nA)
        do {
            var hrn = [Int](repeating: 0, count: nA), vrn = [Int](repeating: 0, count: nA)
            Flatten.runLengths(rule, aw, ah, &hrn, &vrn)
            for i in 0..<nA {
                if rule[i] == 0 { continue }
                if vrn[i] <= tmax || hrn[i] >= 3 * tmax { th[i] = 1 }
                if hrn[i] <= tmax || vrn[i] >= 3 * tmax { tv[i] = 1 }
            }
        }
        let hs = Flatten.Rules(), vs = Flatten.Rules()
        Flatten.findRules(th, amin, aw, ah, true, max(6, JavaNum.round(2.5 * hcU)), tmax, &lab, &queue, &hId, hs)
        Flatten.findRules(tv, amin, aw, ah, false, max(6, JavaNum.round(2.0 * hcU)), tmax, &lab, &queue, &vId, vs)
        s.hId = hId
        s.vId = vId
        s.hKeep = [Bool](repeating: false, count: hs.n + 1)
        s.vKeep = [Bool](repeating: false, count: vs.n + 1)
        let maxThick = (tmax + 2) * k
        for r in 0..<hs.n {
            if let sg = refine(s, hs, r, true, maxThick, inkLevel) {
                s.hKeep[r + 1] = true
                out.append(sg)
            }
        }
        for r in 0..<vs.n {
            if let sg = refine(s, vs, r, false, maxThick, inkLevel) {
                s.vKeep[r + 1] = true
                out.append(sg)
            }
        }
        // vệt tối sát mép ảnh (mép giấy / bóng mép máy quét) không phải đường kẻ
        let edge = 0.012 * Double(L)
        out.removeAll { sg in
            let lim = Double(sg.h ? h : w)
            return sg.pos < edge || sg.pos > lim - edge
        }
        return out
    }

    /// Chiều cao điển hình của khung từ (trung vị) * 0,6; def nếu không có từ.
    static func wordHeight(_ ws: [W], _ def: Double) -> Double {
        let n = ws.count
        if n == 0 { return def }
        var hs = [Double](repeating: 0, count: n)
        for i in 0..<n { hs[i] = ws[i].oy1 - ws[i].oy0 }
        JavaNum.sort(&hs)
        return JavaNum.max(4.0, hs[n / 2] * 0.6)
    }

    /// Kiểm một đường kẻ của ảnh phân tích ở độ phân giải đầy đủ: dọc theo đường, tại mỗi vị trí tìm nét mực mảnh cắt
    /// ngang qua tâm dự đoán. Đường kẻ thật phủ kín gần hết chiều dài bằng nét mảnh (chỗ giao với đường vuông góc là nét
    /// dày — vẫn tính là phủ); chuỗi chân chữ dính nhau thì đứt quãng. Trả về nil nếu không đạt.
    static func refine(_ s: Scan, _ rs: Flatten.Rules, _ r: Int, _ horiz: Bool, _ maxThick: Int, _ inkLevel: Int) -> Seg? {
        let k = s.k, w = s.w, h = s.h
        let a0 = rs.a0[r], a1 = rs.a1[r]
        let cen = rs.cen[r]
        let cov = rs.cov[r]
        let len = a1 - a0 + 1
        let pStart = a0 * k, pEnd = min(horiz ? w : h, (a1 + 1) * k)
        let lim = horiz ? h : w
        let total = pEnd - pStart
        if total < 4 { return nil }
        var centers = [Double](repeating: 0, count: total)
        var at = [Double](repeating: 0, count: total)
        var thicks = [Double](repeating: 0, count: total)
        let denom = JavaNum.max(40.0, 255.0 - Double(inkLevel))
        var thin = 0, thick = 0, first = -1, last = -1
        var lastCov = -1
        s.lq.withUnsafeBufferPointer { lq in
            for p in pStart..<pEnd {
                let i = min(len - 1, p / k - a0)
                // chỗ đường kẻ đứt trên ảnh phân tích: lấy tâm của vị trí có mực gần nhất phía trước
                if cov[i] > 0 { lastCov = i }
                let ci = lastCov >= 0 ? lastCov : i
                if cov[ci] == 0 { continue }
                let c = JavaNum.toInt((cen[ci] + 0.5) * Float(k))
                let lo = max(0, c - k - 1), hi = min(lim - 1, c + k + 1)
                var best = 256, bq = -1
                var q = lo
                while q <= hi {
                    let v = Int(lq[horiz ? q * w + p : p * w + q])
                    if v < best {
                        best = v
                        bq = q
                    }
                    q += 1
                }
                if best >= ruleQ { continue }
                var q0 = bq, q1 = bq
                while q0 > 0 && q1 - q0 <= maxThick && Int(lq[horiz ? (q0 - 1) * w + p : p * w + q0 - 1]) < ruleQ { q0 -= 1 }
                while q1 < lim - 1 && q1 - q0 <= maxThick && Int(lq[horiz ? (q1 + 1) * w + p : p * w + q1 + 1]) < ruleQ { q1 += 1 }
                if first < 0 { first = p }
                last = p
                if q1 - q0 + 1 > maxThick {
                    thick += 1
                } else {
                    centers[thin] = Double(q0 + q1 + 1) / 2.0
                    at[thin] = Double(p)
                    // độ dày dưới điểm ảnh: tổng độ tối cắt ngang nét (kể cả hai mép nhoè) chia cho độ tối của mực đặc
                    var sum = 0
                    q = max(0, q0 - 1)
                    let qe = min(lim - 1, q1 + 1)
                    while q <= qe {
                        sum += 255 - Int(lq[horiz ? q * w + p : p * w + q])
                        q += 1
                    }
                    thicks[thin] = Double(sum) / denom
                    thin += 1
                }
            }
        }
        if first < 0 { return nil }
        let span = last - first + 1
        if (thin + thick) * 100 < span * 80 || thin * 100 < span * 45 || thin < 4 { return nil }
        // vị trí = trung vị tâm nét sau khi trừ độ dốc (bình phương tối thiểu)
        var sx = 0.0, sy = 0.0, sxx = 0.0, sxy = 0.0
        for i in 0..<thin {
            sx += at[i]
            sy += centers[i]
            sxx += at[i] * at[i]
            sxy += at[i] * centers[i]
        }
        let den = Double(thin) * sxx - sx * sx
        let slope = abs(den) > 1e-9 ? (Double(thin) * sxy - sx * sy) / den : 0.0
        if abs(slope) > 0.12 { return nil }
        let mid = Double(first + last + 1) / 2.0
        var res = [Double](repeating: 0, count: thin)
        for i in 0..<thin { res[i] = centers[i] - slope * (at[i] - mid) }
        JavaNum.sort(&res)
        // đường kẻ thật thì thẳng: 80% tâm nét nằm trong dải hẹp quanh đường khớp (cung tròn của con dấu, nét chữ ký
        // lượn thì lệch nhiều điểm ảnh)
        let spread = res[min(thin - 1, JavaNum.toInt(Double(thin) * 0.9))] - res[JavaNum.toInt(Double(thin) * 0.1)]
        if spread > JavaNum.max(2.0, 0.6 * Double(k) + 1.0) { return nil }
        var tk = Array(thicks[0..<thin])
        JavaNum.sort(&tk)
        let sg = Seg()
        sg.h = horiz
        sg.pos = res[thin / 2]
        sg.a = Double(first)
        sg.b = Double(last + 1)
        sg.t = JavaNum.max(0.6, tk[thin / 2])
        sg.slope = slope
        sg.longest = sg.b - sg.a
        return sg
    }

    // MARK: đo từ

    private static func cps(_ s: String) -> [UInt32] {
        return s.unicodeScalars.map { $0.value }
    }

    /// String.indexOf(cp) >= 0 của Java trên một tập ký tự nhỏ.
    @inline(__always) private static func has(_ set: [UInt32], _ cp: Int) -> Bool {
        if cp < 0 || cp > 0x10FFFF { return false }
        let v = UInt32(cp)
        for c in set where c == v { return true }
        return false
    }

    // các tập ký tự (ký tự ngoài ASCII viết bằng mã để không phụ thuộc cách lưu file): ơ ư | ỵ | chữ có dấu nặng dựng
    // sẵn + ç Ç | – | — – ¦ | ¬ ¯ ‾ | ‘ ’ “ ” ´
    private static let xOnlySet = cps("aceimnorsuvwxz\u{01A1}\u{01B0}")
    private static let belowSet = cps("gjpqyQ,;()[]{}/|_\u{1EF5}")
    private static let dotBelowSet = cps("\u{1EA1}\u{1EB7}\u{1EAD}\u{1EB9}\u{1EC7}\u{1ECB}\u{1ECD}\u{1ED9}\u{1EE3}\u{1EE5}\u{1EF1}\u{1EF5}"
        + "\u{1EA0}\u{1EB6}\u{1EAC}\u{1EB8}\u{1EC6}\u{1ECA}\u{1ECC}\u{1ED8}\u{1EE2}\u{1EE4}\u{1EF0}\u{1EF4}\u{00E7}\u{00C7}")
    private static let lowPunctSet = cps(".,-:\u{2013}")
    private static let ruleLikeSet = cps("|lI1!_-\u{2014}\u{2013}[]()/\\.,':;\u{00A6}=~Li")
    private static let strokeSet = cps("|_-\u{2014}\u{2013}\u{00A6}=~\u{00AC}\u{00AF}\u{203E}")
    private static let vRuleSet = cps("|lI1!![]()/\\\u{00A6}'\"\u{2018}\u{2019}\u{201C}\u{201D}`\u{00B4}ijtf:;.,")
    private static let hRuleSet = cps("_-\u{2014}\u{2013}=~.,")
    private static let strongSet = cps("|_\u{00A6}")

    /// Character.isLowerCase của Java (Ll hoặc Other_Lowercase).
    static func isLower(_ cp: Int) -> Bool {
        guard cp >= 0, let u = Unicode.Scalar(UInt32(truncatingIfNeeded: cp)) else { return false }
        return u.properties.isLowercase
    }

    /// Character.isLetter của Java: Lu, Ll, Lt, Lm, Lo.
    @inline(__always) private static func isLetter(_ u: Unicode.Scalar) -> Bool {
        switch u.properties.generalCategory {
        case .uppercaseLetter, .lowercaseLetter, .titlecaseLetter, .modifierLetter, .otherLetter: return true
        default: return false
        }
    }

    /// Chữ thường chỉ cao bằng thân chữ (không có nét vươn lên / thả xuống), kể cả khi mang dấu tiếng Việt.
    static func xOnly(_ base: Int) -> Bool {
        return has(xOnlySet, base)
    }

    /// Chữ gốc của một chữ có dấu (theo bảng WordFont): 'ấ' -> 'a'; ký tự ngoài bảng trả về chính nó.
    static func baseOf(_ cp: Int) -> Int {
        if cp < 0x80 { return cp }
        let k = WordFont.index(cp)
        if k >= 0 && k < WordFont.nAscii { return k + 0x20 }
        if k >= WordFont.nAscii {
            // các ký tự riêng: ơ ư Ơ Ư đ Đ đứng đầu bảng phụ
            let j = k - WordFont.nAscii
            if j == 0 { return 0x01A1 }
            if j == 1 { return 0x01B0 }
            if j == 2 { return 0x4F }
            if j == 3 { return 0x55 }
            if j == 4 { return 0x64 }
            if j == 5 { return 0x44 }
        }
        return cp
    }

    /// Dấu nằm dưới đường chân chữ (dấu nặng) hoặc chữ có đuôi thả xuống.
    static func hasBelow(_ t: String) -> Bool {
        for u in t.unicodeScalars {
            let cp = Int(u.value)
            if has(belowSet, cp) { return true }
            if cp == 0x0323 { return true }
            // chữ có dấu nặng dựng sẵn
            if has(dotBelowSet, cp) { return true }
            let b = baseOf(cp)
            if b == 0x67 || b == 0x6A || b == 0x70 || b == 0x71 || b == 0x79 { return true }
        }
        return false
    }

    /// 1: mọi chữ cái là chữ thường chỉ cao bằng thân chữ; 2: toàn chữ hoa / chữ số; 0: khác (không đo cỡ theo chiều cao).
    static func heightKind(_ t: String) -> Int {
        var letters = 0, xs = 0, caps = 0
        for u in t.unicodeScalars {
            let cp = Int(u.value)
            if cp >= 0x300 && cp <= 0x36F { continue }
            if u.properties.generalCategory == .decimalNumber {
                letters += 1
                caps += 1
                continue
            }
            if !isLetter(u) {
                // dấu câu thấp (. , - :) không ảnh hưởng dải thân chữ; ký hiệu cao thì bỏ từ này
                if has(lowPunctSet, cp) { continue }
                return 0
            }
            letters += 1
            let b = baseOf(cp)
            if u.properties.isUppercase {
                caps += 1
            } else if xOnly(b) {
                xs += 1
            }
        }
        if letters < 2 { return 0 }
        if xs == letters { return 1 }
        if caps == letters { return 2 }
        return 0
    }

    /// Đo một từ trên ảnh gốc: khung mực (co / nới khung của bộ nhận dạng theo mực thật), dải thân chữ -> đường chân chữ
    /// và cỡ chữ theo chiều cao, bề dày nét (dưới điểm ảnh: tổng độ tối của nét chia cho độ tối của mực đặc), màu mực.
    static func measureWord(_ s: Scan, _ wd: W, _ inkLevel: Int) {
        s.withInk { v in measureWord(s, v, wd, inkLevel) }
    }

    static func measureWord(_ s: Scan, _ v: Ink, _ wd: W, _ inkLevel: Int) {
        let w = s.w, h = s.h
        let bx0 = JavaNum.toInt(wd.ox0.rounded(.down)), by0 = JavaNum.toInt(wd.oy0.rounded(.down))
        let bx1 = min(w, JavaNum.toInt(wd.ox1.rounded(.up))), by1 = min(h, JavaNum.toInt(wd.oy1.rounded(.up)))
        wd.x0 = wd.ox0
        wd.y0 = wd.oy0
        wd.x1 = wd.ox1
        wd.y1 = wd.oy1
        wd.base = Double.nan
        wd.emH = 0
        wd.stroke = 0
        wd.sh0 = 0
        wd.sh1 = 0
        wd.sh2 = 0
        wd.colored = false
        wd.color = 0
        wd.inkN = 0
        if bx1 - bx0 < 1 || by1 - by0 < 2 { return }
        let bh = by1 - by0
        // từ chủ yếu là mực đen mà có lẫn mực màu (nét bút, con dấu vắt qua): chỉ đo trên mực đen
        var allN = 0, colorN = 0
        for y in by0..<by1 {
            for x in bx0..<bx1 {
                let i = y * w + x
                if Int(v.lq[i]) >= v.tq { continue }
                allN += 1
                if (v.cls[i] & 1) != 0 { colorN += 1 }
            }
        }
        let nc = colorN > 0 && (allN - colorN) * 10 >= allN * 4
        let mx = max(2, JavaNum.wrap32(JavaNum.round(0.30 * Double(bh)))), my = max(1, JavaNum.wrap32(JavaNum.round(0.18 * Double(bh))))
        // cx: phạm vi dời mép trái / phải; cột mực lấy rộng hơn thế (để đo được bề rộng khe trắng bên ngoài mép ứng viên)
        let cx = max(2, JavaNum.wrap32(JavaNum.round(0.45 * Double(bh))))
        let rx0 = max(0, bx0 - cx - mx), rx1 = min(w, bx1 + cx + mx)
        let ry0 = max(0, by0 - my), ry1 = min(h, by1 + my)
        // mực theo cột (trong các hàng của khung nhận dạng)
        var col = [Int](repeating: 0, count: rx1 - rx0)
        for y in by0..<by1 {
            for x in rx0..<rx1 where v.textInk(x, y, nc) { col[x - rx0] += 1 }
        }
        // Mép trái / phải của khung mực = chỗ bắt đầu / kết thúc một nét mực gần mép khung nhận dạng (trong phạm vi 0,45
        // chiều cao khung): khung của bộ nhận dạng có thể hụt mất một phần chữ đầu / cuối, lấn sang chữ của từ bên cạnh
        // hoặc lệch hẳn đi nửa chữ (ML Kit trên ảnh nhỏ), còn khe trắng giữa các chữ thì luôn có thật trên ảnh. Trong các
        // chỗ bắt đầu / kết thúc nét ở gần, ưu tiên chỗ có khe trắng RỘNG phía ngoài (khe giữa hai từ) hơn chỗ gần nhất
        // nhưng chỉ cách nét kế một khe hẹp (khe giữa hai chữ trong một từ): điểm = khoảng cách - 1,5 x bề rộng khe (khe
        // tính tối đa mx = 0,3 chiều cao khung).
        var tx0 = -1, tx1 = -1
        var best0 = Double.greatestFiniteMagnitude, best1 = Double.greatestFiniteMagnitude
        var x = max(rx0, bx0 - cx)
        while x <= bx0 + cx && x < rx1 && x < bx1 {
            defer { x += 1 }
            if col[x - rx0] == 0 { continue }
            if x > 0 && (x == rx0 || col[x - 1 - rx0] != 0) { continue }
            var gap = 0
            while gap < mx && x - 1 - gap >= rx0 && col[x - 1 - gap - rx0] == 0 { gap += 1 }
            if x - 1 - gap < 0 { gap = mx }
            let cost = Double(abs(x - bx0)) - 1.5 * Double(gap)
            if cost < best0 {
                best0 = cost
                tx0 = x
            }
        }
        x = min(rx1, bx1 + cx)
        while x >= bx1 - cx && x > rx0 && x > bx0 {
            defer { x -= 1 }
            if col[x - 1 - rx0] == 0 { continue }
            if x < w && (x == rx1 || col[x - rx0] != 0) { continue }
            var gap = 0
            while gap < mx && x + gap < rx1 && col[x + gap - rx0] == 0 { gap += 1 }
            if x + gap >= w { gap = mx }
            let cost = Double(abs(x - bx1)) - 1.5 * Double(gap)
            if cost < best1 {
                best1 = cost
                tx1 = x
            }
        }
        if tx0 < 0 {
            tx0 = bx0
            while tx0 < bx1 && col[tx0 - rx0] == 0 { tx0 += 1 }
        }
        if tx1 < 0 {
            tx1 = bx1
            while tx1 > tx0 && col[tx1 - 1 - rx0] == 0 { tx1 -= 1 }
        }
        if tx1 <= tx0 { return }
        // mực theo hàng (trong các cột của khung mực)
        var row = [Int](repeating: 0, count: ry1 - ry0)
        for y in ry0..<ry1 {
            var c = 0
            for x in tx0..<tx1 where v.textInk(x, y, nc) { c += 1 }
            row[y - ry0] = c
        }
        var ty0 = by0, ty1 = by1
        while ty0 < ty1 && row[ty0 - ry0] == 0 { ty0 += 1 }
        while ty1 > ty0 && row[ty1 - 1 - ry0] == 0 { ty1 -= 1 }
        if ty1 <= ty0 { return }
        while ty0 > ry0 && row[ty0 - 1 - ry0] > 0 { ty0 -= 1 }
        while ty1 < ry1 && row[ty1 - ry0] > 0 { ty1 += 1 }
        wd.x0 = Double(tx0)
        wd.x1 = Double(tx1)
        wd.y0 = Double(ty0)
        wd.y1 = Double(ty1)
        // màu mực
        var sr = 0, sg = 0, sb = 0
        var inkN = 0, colN = 0
        for y in ty0..<ty1 {
            for x in tx0..<tx1 {
                let i = y * w + x
                if !v.textInk(x, y, nc) { continue }
                inkN += 1
                if (v.cls[i] & 1) != 0 {
                    colN += 1
                    sr += Int(v.px[4 * i])
                    sg += Int(v.px[4 * i + 1])
                    sb += Int(v.px[4 * i + 2])
                }
            }
        }
        wd.inkN = inkN
        if inkN > 0 && colN * 10 >= inkN * 6 {
            wd.colored = true
            // đậm hơn một chút so với trung bình (mép nét bị nhoè làm nhạt màu)
            let r = JavaNum.toInt(Double(sr / colN) * 0.85), g = JavaNum.toInt(Double(sg / colN) * 0.85)
            let b = JavaNum.toInt(Double(sb / colN) * 0.85)
            wd.color = (r << 16) | (g << 8) | b
        }
        // dải thân chữ: đoạn hàng liên tục dài nhất có lượng mực >= nửa TRUNG VỊ của các hàng có mực (cho hở một hàng).
        // Không so với hàng nhiều mực nhất: nét ngang của T, E, B, chữ số... làm vài hàng vọt lên, thân chữ thì không.
        var srt = [Int](repeating: 0, count: ty1 - ty0)
        var ns = 0
        for y in ty0..<ty1 where row[y - ry0] > 0 {
            srt[ns] = row[y - ry0]
            ns += 1
        }
        if ns == 0 { return }
        srt[0..<ns].sort()
        let thr = max(1, JavaNum.toInt((0.5 * Double(srt[ns / 2])).rounded(.up)))
        var bestTop = -1, bestBot = -1, curTop = -1, gap = 0
        for y in ty0...ty1 {
            let on = y < ty1 && row[y - ry0] >= thr
            if on {
                if curTop < 0 { curTop = y }
                gap = 0
            } else if curTop >= 0 {
                gap += 1
                if gap > 1 || y >= ty1 {
                    let bot = y - gap
                    if bestTop < 0 || bot - curTop > bestBot - bestTop {
                        bestTop = curTop
                        bestBot = bot
                    }
                    curTop = -1
                    gap = 0
                }
            }
        }
        if bestTop < 0 { return }
        let band = bestBot - bestTop + 1
        let kind = heightKind(wd.text)
        // cỡ chữ + đường chân chữ theo nội dung: chiều cao khung mực chia cho phạm vi dọc của các ký tự đã nhận dạng
        let ve = WordFont.verticalUnits(wd.text)
        let expH = Double(ve.top - ve.bot) / WordFont.upm
        let emT = ve.count > 0 && expH >= 0.4 ? Double(ty1 - ty0) / expH : 0.0
        let emB = kind == 1 ? Double(band) / WordFont.xHeight : (kind == 2 ? Double(band) / WordFont.capHeight : 0.0)
        if emT > 0 {
            wd.emH = emT
            wd.base = Double(ty1) + Double(ve.bot) / WordFont.upm * emT
        }
        if emB > 0 {
            // từ chỉ gồm chữ thường thấp / chữ hoa: dải thân chữ không phụ thuộc việc đọc đúng dấu
            wd.emH = emT > 0 && abs(emT - emB) <= 0.12 * emB ? (emT + emB) / 2.0 : emB
            wd.base = Double(bestBot + 1)
        }
        // bề dày nét: các đoạn mực ngang trong dải thân chữ; đoạn dài (nét ngang, bụng chữ) không tính
        let maxRun = max(3, JavaNum.toInt(0.45 * Double(band)))
        let denom = JavaNum.max(40.0, 255.0 - Double(inkLevel))
        var hist = [Int](repeating: 0, count: 64)
        var cnt = 0
        var yy = bestTop
        while yy <= bestBot {
            var x = tx0
            while x < tx1 {
                if !v.textInk(x, yy, nc) {
                    x += 1
                    continue
                }
                let st = x
                while x < tx1 && v.textInk(x, yy, nc) { x += 1 }
                let rl = x - st
                if rl > maxRun { continue }
                // tổng độ tối của nét, kể cả hai mép nhoè
                let a = max(0, st - 2), b = min(w, x + 2)
                var sum = 0
                var q = a
                while q < b {
                    sum += 255 - Int(v.lq[yy * w + q])
                    q += 1
                }
                var vv = JavaNum.toInt(Double(sum) * 4.0 / denom + 0.5)
                if vv > 63 { vv = 63 }
                hist[vv] += 1
                cnt += 1
            }
            yy += 1
        }
        if cnt >= 4 {
            var acc = 0
            for q in 0..<64 {
                acc += hist[q]
                if acc * 2 >= cnt {
                    wd.stroke = Double(q) / 4.0
                    break
                }
            }
        }
        // chữ nghiêng: nét sổ của chữ đứng xếp thẳng cột nên hình chiếu theo cột "sắc" (tổng bình phương lớn); chữ
        // nghiêng thì phải nắn nghiêng lại (dời hàng trên sang trái) mới sắc. Đo ở ba độ nghiêng 0 / 0,14 / 0,27.
        let tw = tx1 - tx0, thh = ty1 - ty0
        if tw >= 6 && thh >= 8 {
            let pad = JavaNum.toInt(0.27 * Double(thh)) + 2
            var c0 = [Int](repeating: 0, count: tw + pad), c1 = [Int](repeating: 0, count: tw + pad)
            var c2 = [Int](repeating: 0, count: tw + pad)
            for y in ty0..<ty1 {
                let up = ty1 - 1 - y
                let d1 = JavaNum.toInt(Double(up) * 0.14 + 0.5), d2 = JavaNum.toInt(Double(up) * 0.27 + 0.5)
                for x in tx0..<tx1 {
                    if !v.textInk(x, y, nc) { continue }
                    let q = x - tx0
                    c0[q] += 1
                    c1[q + pad - d1] += 1
                    c2[q + pad - d2] += 1
                }
            }
            var a0 = 0.0, a1 = 0.0, a2 = 0.0
            for i in 0..<(tw + pad) {
                a0 += Double(c0[i]) * Double(c0[i])
                a1 += Double(c1[i]) * Double(c1[i])
                a2 += Double(c2[i]) * Double(c2[i])
            }
            wd.sh0 = a0
            wd.sh1 = a1
            wd.sh2 = a2
        }
    }

    static func ruleLike(_ t: String) -> Bool {
        if t.unicodeScalars.isEmpty { return false }
        for u in t.unicodeScalars where !has(ruleLikeSet, Int(u.value)) { return false }
        return true
    }

    /// Chuỗi chỉ gồm ký hiệu kẻ vạch (không chữ, không số): "—¬", "|_", "═"...
    static func onlyStrokes(_ t: String) -> Bool {
        if t.unicodeScalars.isEmpty { return false }
        for u in t.unicodeScalars {
            let cp = Int(u.value)
            if !has(strokeSet, cp) && !(cp >= 0x2500 && cp <= 0x257F) { return false }
        }
        return true
    }

    /// Ký tự mà bộ nhận dạng hay đọc ra từ một đường kẻ dọc / ngang.
    static func vRuleChar(_ cp: Int) -> Bool {
        return has(vRuleSet, cp)
    }

    static func hRuleChar(_ cp: Int) -> Bool {
        return has(hRuleSet, cp)
    }

    /// Bề rộng mực tự nhiên (1/2048 em, kiểu thường) của chuỗi t.
    static func natInkUnits(_ t: String) -> Int {
        return JavaNum.wrap32(advUnits(t, WordFont.regular) - WordFont.lsbUnits(t, WordFont.regular) - WordFont.rsbUnits(t, WordFont.regular))
    }

    /// Độ lệch (theo em) giữa bề rộng mực đo được và bề rộng tự nhiên của chuỗi t, với em suy từ chiều cao khung mực và
    /// phạm vi dọc của chính chuỗi đó (mỗi giả thuyết về nội dung tự suy ra cỡ chữ của nó).
    static func widthMisfit(_ t: String, _ inkW: Double, _ inkH: Double, _ wf: Double) -> Double {
        let ve = WordFont.verticalUnits(t)
        let expH = Double(ve.top - ve.bot) / WordFont.upm
        let em = ve.count > 0 && expH >= 0.25 ? inkH / expH : inkH / 0.9
        if !(em > 0) { return 99.0 }
        return abs(inkW / em - Double(natInkUnits(t)) / WordFont.upm * wf)
    }

    /// Phông của trang rộng / hẹp hơn Times New Roman bao nhiêu lần: trung vị (theo số ký tự) của tỷ số giữa bề rộng mực
    /// đo được và bề rộng tự nhiên, với em suy từ chiều cao khung mực. 1 nếu trang quá ít chữ.
    static func widthFactor(_ ws: [W]) -> Double {
        var items = [(Double, Double)]()
        for wd in ws {
            if wd.drop || wd.glyphs < 3 || wd.inkN <= 0 { continue }
            let ve = WordFont.verticalUnits(wd.text)
            let expH = Double(ve.top - ve.bot) / WordFont.upm
            let nat = natInkUnits(wd.text)
            if ve.count == 0 || expH < 0.4 || nat <= 0 { continue }
            let em = (wd.y1 - wd.y0) / expH
            if !(em > 0) { continue }
            items.append((((wd.x1 - wd.x0) / em) / (Double(nat) / WordFont.upm), Double(wd.glyphs)))
        }
        return items.count >= 8 ? clampD(wmedian(items), 0.75, 1.4) : 1.0
    }

    /// Cỡ chữ điển hình của trang (em, điểm ảnh): trung vị theo số ký tự của em suy từ chiều cao khung mực; 0 nếu ít chữ.
    static func typicalEm(_ ws: [W]) -> Double {
        var items = [(Double, Double)]()
        for wd in ws {
            if wd.drop || wd.glyphs < 3 || wd.inkN <= 0 { continue }
            let ve = WordFont.verticalUnits(wd.text)
            let expH = Double(ve.top - ve.bot) / WordFont.upm
            if ve.count == 0 || expH < 0.4 { continue }
            let em = (wd.y1 - wd.y0) / expH
            if em > 0 { items.append((em, Double(wd.glyphs))) }
        }
        return items.count >= 8 ? wmedian(items) : 0.0
    }

    /// Có đường kẻ đã nhận chạy qua một đầu của khung từ không. side: 0 trái, 1 phải (đường dọc), 2 đáy (đường ngang).
    static func ruleAtEnd(_ segs: [Seg], _ wd: W, _ side: Int) -> Seg? {
        let hgt = wd.oy1 - wd.oy0, wid = wd.ox1 - wd.ox0
        for sg in segs {
            if sg.drop { continue }
            if side == 2 {
                if !sg.h { continue }
                if JavaNum.min(sg.b, wd.ox1) - JavaNum.max(sg.a, wd.ox0) < 0.5 * wid { continue }
                if sg.pos >= wd.oy1 - 0.35 * hgt && sg.pos <= wd.oy1 + 0.6 * hgt { return sg }
            } else {
                if sg.h { continue }
                if JavaNum.min(sg.b, wd.oy1) - JavaNum.max(sg.a, wd.oy0) < 0.5 * hgt { continue }
                // đường kẻ nằm trong đầu khung hoặc ngay sát ngoài (bộ nhận dạng đặt khung lệch khỏi đường kẻ)
                if side == 0 && sg.pos >= wd.ox0 - 0.6 * hgt && sg.pos <= wd.ox0 + 0.4 * hgt { return sg }
                if side == 1 && sg.pos >= wd.ox1 - 0.4 * hgt && sg.pos <= wd.ox1 + 0.6 * hgt { return sg }
            }
        }
        return nil
    }

    /// Số thành phần liên thông (>= 3 điểm) của mực trong khung mực của từ (toạ độ ảnh gốc).
    static func inkComponents(_ s: Scan, _ x0: Int, _ y0: Int, _ x1: Int, _ y1: Int) -> Int {
        let bw = x1 - x0, bh = y1 - y0
        if bw <= 0 || bh <= 0 || bw * bh > 4000000 { return 0 }
        // khung ngoài ảnh (không xảy ra với khung mực đã đo): bản Java ném lỗi; ở đây coi như không có mực
        if x0 < 0 || y0 < 0 || x1 > s.w || y1 > s.h { return 0 }
        var seen = [UInt8](repeating: 0, count: bw * bh)
        var stack = [Int](repeating: 0, count: bw * bh)
        var comps = 0
        s.withInk { v in
            for start in 0..<(bw * bh) {
                if seen[start] != 0 { continue }
                let sx = start % bw, sy = start / bw
                if !v.ink(x0 + sx, y0 + sy) { continue }
                var top = 0, size = 0
                stack[top] = start
                top += 1
                seen[start] = 1
                while top > 0 {
                    top -= 1
                    let i = stack[top]
                    size += 1
                    let x = i % bw, y = i / bw
                    for dy in -1...1 {
                        let yy = y + dy
                        if yy < 0 || yy >= bh { continue }
                        for dx in -1...1 {
                            let xx = x + dx
                            if xx < 0 || xx >= bw { continue }
                            let j = yy * bw + xx
                            if seen[j] == 0 && v.ink(x0 + xx, y0 + yy) {
                                seen[j] = 1
                                stack[top] = j
                                top += 1
                            }
                        }
                    }
                }
                if size >= 3 { comps += 1 }
            }
        }
        return comps
    }

    /// Lọc "rác" do bộ nhận dạng đọc nhầm:
    /// - đường kẻ nằm gọn trong khung một từ nhiều chữ là nét chữ (chân chữ dính nhau, gạch dưới "____") -> bỏ đường kẻ;
    /// - từ một ký tự kiểu "|", "l", "_" trùng với đường kẻ, từ không có điểm mực nào ngoài đường kẻ -> bỏ từ;
    /// - ký tự đầu / cuối từ do đường kẻ sát bên bị đọc thành chữ ("|Máy", "2_") -> gọt đi khi bề rộng mực khớp hơn;
    /// - nét mực màu liền một mạch bị đọc thành vài chữ (chữ ký, nét bút) -> bỏ từ, để phần mực thành hình.
    static func dropRuleJunk(_ s: Scan, _ ws: [W], _ segs: inout [Seg], _ inkLevel: Int) {
        for sg in segs {
            let len = sg.b - sg.a
            for wd in ws {
                if wd.drop { continue }
                let x0 = wd.ox0 - 2, y0 = wd.oy0 - 2, x1 = wd.ox1 + 2, y1 = wd.oy1 + 2
                var lo = 0.0, hi = 0.0, across0 = 0.0, across1 = 0.0
                if sg.h {
                    lo = x0
                    hi = x1
                    across0 = y0
                    across1 = y1
                } else {
                    lo = y0
                    hi = y1
                    across0 = x0
                    across1 = x1
                }
                if sg.pos < across0 || sg.pos > across1 { continue }
                let ol = JavaNum.min(sg.b, hi) - JavaNum.max(sg.a, lo)
                if ol <= 0 { continue }
                let thinLim = JavaNum.max(3.0 * sg.t, 0.6 * s.hc)
                let thinWord = (across1 - across0 - 4) <= thinLim
                if ol >= 0.8 * len {
                    // đường kẻ nằm trong khung từ
                    if wd.glyphs >= 2 || !thinWord || !ruleLike(wd.text) {
                        sg.drop = true
                    } else {
                        wd.drop = true
                    }
                } else if ruleLike(wd.text) && thinWord && ol >= 0.6 * (hi - lo - 4) {
                    // từ mảnh nằm dọc theo một đường kẻ dài hơn nó
                    wd.drop = true
                } else if onlyStrokes(wd.text) && ol >= 0.6 * (hi - lo - 4) {
                    // "từ" chỉ gồm ký hiệu kẻ vạch mà có đường kẻ dài chạy xuyên qua khung: đọc ra từ chỗ các đường kẻ giao nhau
                    wd.drop = true
                }
            }
        }
        segs.removeAll { $0.drop }
        let wf = widthFactor(ws), em0 = typicalEm(ws)
        for wd in ws {
            if wd.drop { continue }
            // không có điểm mực nào (ngoài đường kẻ) trong khung: bộ nhận dạng "đọc" ra từ đường kẻ / nền giấy
            if wd.inkN == 0 {
                wd.drop = true
                continue
            }
            // gọt ký tự rác ở hai đầu: ký tự kiểu đường kẻ ("|", "_"...) mà bỏ đi thì phần chữ còn lại khớp bề rộng mực
            // hơn hẳn; có đường kẻ chạy sát đầu đó thì xét cả các ký tự mảnh khác (', l, 1...) và chỉ cần khớp hơn một chút
            var changed = false
            var pass = 0
            while pass < 3 && wd.glyphs >= 2 {
                let t = wd.text
                let us = t.unicodeScalars
                guard let fu = us.first, let lu = us.last else { break }
                let ink = wd.x1 - wd.x0, hgt = wd.y1 - wd.y0
                let first = Int(fu.value), last = Int(lu.value)
                var cand: String? = nil
                var nearRule = false
                let strong1 = has(strongSet, first), strong2 = has(strongSet, last)
                let left: Seg? = vRuleChar(first) ? ruleAtEnd(segs, wd, 0) : nil
                let right: Seg? = left == nil && vRuleChar(last) ? ruleAtEnd(segs, wd, 1) : nil
                var sure = false
                if left != nil || (hRuleChar(first) && ruleAtEnd(segs, wd, 2) != nil) {
                    cand = JavaNum.trim(String(us.dropFirst()))
                    nearRule = true
                    // "|" / "¦" ngay tại một đường kẻ dọc: chắc chắn là đường kẻ bị đọc thành chữ
                    sure = left != nil && (first == 0x7C || first == 0xA6)
                } else if right != nil || (hRuleChar(last) && ruleAtEnd(segs, wd, 2) != nil) {
                    cand = JavaNum.trim(String(us.dropLast()))
                    nearRule = true
                    sure = right != nil && (last == 0x7C || last == 0xA6)
                } else if strong1 {
                    cand = JavaNum.trim(String(us.dropFirst()))
                } else if strong2 {
                    cand = JavaNum.trim(String(us.dropLast()))
                }
                guard let c = cand, !c.unicodeScalars.isEmpty else { break }
                if !sure {
                    let fitFull = widthMisfit(t, ink, hgt, wf), fitCand = widthMisfit(c, ink, hgt, wf)
                    if fitCand + (nearRule ? 0.02 : 0.15) >= fitFull { break }
                } else {
                    // khung từ lùi khỏi đường kẻ (và viền nhoè của nó) để mực đường kẻ không tính vào chữ; khung vẫn phải nằm
                    // trong ảnh (từ hẹp hơn 1 điểm ảnh sát mép: ox1 - 1 < 0 làm measureWord đọc ngoài mảng)
                    if let l = left {
                        let pad = 0.5 * l.t + 1.0 + 0.12 * s.hc
                        wd.ox0 = JavaNum.max(0.0, JavaNum.min(JavaNum.max(wd.ox0, l.pos + pad), wd.ox1 - 1.0))
                    } else if let r = right {
                        let pad = 0.5 * r.t + 1.0 + 0.12 * s.hc
                        wd.ox1 = JavaNum.min(Double(s.w), JavaNum.max(JavaNum.min(wd.ox1, r.pos - pad), wd.ox0 + 1.0))
                    }
                }
                wd.text = c
                wd.glyphs = max(1, WordFont.glyphCount(c))
                changed = true
                if sure { measureWord(s, wd, inkLevel) }
                pass += 1
            }
            if changed { measureWord(s, wd, inkLevel) }
            // hình dạng mực trái hẳn với nội dung đọc được (bề rộng lệch quá 2 lần so với số ký tự ở cỡ chữ suy từ
            // chiều cao): bộ nhận dạng đọc bừa trên hình / nhiều dòng chồng nhau -> bỏ từ, phần mực sẽ thành hình
            if wd.glyphs >= 2 && wd.inkN > 0 {
                let ve = WordFont.verticalUnits(wd.text)
                let expH = Double(ve.top - ve.bot) / WordFont.upm
                let nat = natInkUnits(wd.text)
                if ve.count > 0 && expH >= 0.4 && nat > 0 {
                    let em = (wd.y1 - wd.y0) / expH
                    let ratio = ((wd.x1 - wd.x0) / em) / (Double(nat) / WordFont.upm * wf)
                    // (khung bé tí so với chữ của trang: bộ nhận dạng đặt khung sai chỗ)
                    if ratio < 0.5 || ratio > 2.0 || (em0 > 0 && em < 0.3 * em0) {
                        wd.drop = true
                        continue
                    }
                }
            }
            // một ký tự mà hình dạng mực khác hẳn ký tự đó (rộng / hẹp hơn 2,5 lần so với tỷ lệ rộng : cao của nó) hoặc to
            // gấp 2,5 lần chữ của trang: bộ nhận dạng đọc một hình thành chữ (vòng con dấu thành "(", nét ký thành "/"...)
            // -> bỏ từ, phần mực thành hình
            if wd.glyphs == 1 && wd.inkN > 0 {
                let ve = WordFont.verticalUnits(wd.text)
                let expH = Double(ve.top - ve.bot) / WordFont.upm
                let nat = natInkUnits(wd.text)
                if ve.count > 0 && expH >= 0.25 && nat > 0 && wd.y1 > wd.y0 {
                    let ratio = ((wd.x1 - wd.x0) / (wd.y1 - wd.y0)) / (Double(nat) / WordFont.upm * wf / expH)
                    if ratio < 0.4 || ratio > 2.5 || (em0 > 0 && (wd.y1 - wd.y0) / expH > 2.5 * em0) {
                        wd.drop = true
                        continue
                    }
                }
            }
            // mực màu liền một mạch mà đọc ra nhiều chữ: chữ ký / nét bút, không phải chữ in
            if wd.colored && wd.glyphs >= 3 {
                let comps = inkComponents(s, JavaNum.toInt(wd.x0), JavaNum.toInt(wd.y0), JavaNum.toInt(wd.x1), JavaNum.toInt(wd.y1))
                if comps * 2 < wd.glyphs { wd.drop = true }
            }
        }
    }

    // MARK: khử nghiêng

    /// Góc nghiêng của trang (radian, dương = nội dung quay theo chiều kim đồng hồ): từ đường kẻ, không có thì từ dòng chữ.
    static func estimateSkew(_ s: Scan, _ ws: [W], _ segs: [Seg]) -> Double {
        var votes = [(Double, Double)]()   // (góc, trọng số)
        var total = 0.0
        for sg in segs {
            let len = sg.b - sg.a
            if len < 8.0 * s.hc { continue }
            votes.append((sg.h ? sg.slope : -sg.slope, len))
            total += len
        }
        if total < 0.5 * Double(max(s.w, s.h)) {
            // dòng chữ theo bộ nhận dạng: khớp đường thẳng qua tâm đáy các từ
            votes.removeAll()
            var sorted = [W]()
            for wd in ws where !wd.drop && wd.line >= 0 { sorted.append(wd) }
            JavaNum.stableSort(&sorted) { a, b in
                if a.line != b.line { return a.line < b.line ? -1 : 1 }
                return a.idx < b.idx ? -1 : (a.idx > b.idx ? 1 : 0)
            }
            var i = 0
            while i < sorted.count {
                var j = i
                while j + 1 < sorted.count && sorted[j + 1].line == sorted[i].line { j += 1 }
                let cnt = j - i + 1
                if cnt >= 4 {
                    var sx = 0.0, sy = 0.0, sxx = 0.0, sxy = 0.0
                    var minX = Double.greatestFiniteMagnitude, maxX = -Double.greatestFiniteMagnitude
                    for q in i...j {
                        let wd = sorted[q]
                        let x = (wd.x0 + wd.x1) / 2.0, y = (wd.y0 + wd.y1) / 2.0
                        sx += x
                        sy += y
                        sxx += x * x
                        sxy += x * y
                        minX = JavaNum.min(minX, x)
                        maxX = JavaNum.max(maxX, x)
                    }
                    let den = Double(cnt) * sxx - sx * sx
                    if maxX - minX >= 0.2 * Double(s.w) && abs(den) > 1e-9 {
                        votes.append(((Double(cnt) * sxy - sx * sy) / den, maxX - minX))
                    }
                }
                i = j + 1
            }
        }
        if votes.isEmpty { return 0.0 }
        // trung vị có trọng số
        JavaNum.stableSort(&votes) { a, b in a.0 < b.0 ? -1 : (a.0 > b.0 ? 1 : 0) }
        var sum = 0.0
        for v in votes { sum += v.1 }
        var acc = 0.0, slope = 0.0
        for v in votes {
            acc += v.1
            if acc * 2 >= sum {
                slope = v.0
                break
            }
        }
        // nghiêng dưới 0,1 độ coi như thẳng; quá 8 độ thì không tin
        if abs(slope) < 0.0017 || abs(slope) > 0.14 { return 0.0 }
        return JavaNum.atan(slope)
    }

    static func deskewSeg(_ s: Scan, _ sg: Seg) {
        if s.sn == 0 { return }
        let mid = (sg.a + sg.b) / 2.0, half = (sg.b - sg.a) / 2.0
        let x = sg.h ? mid : sg.pos, y = sg.h ? sg.pos : mid
        let nx = s.rx(x, y), ny = s.ry(x, y)
        if sg.h {
            sg.pos = ny
            sg.a = nx - half
            sg.b = nx + half
        } else {
            sg.pos = nx
            sg.a = ny - half
            sg.b = ny + half
        }
    }

    static func deskewWord(_ s: Scan, _ wd: W) {
        if s.sn == 0 { return }
        let mx = (wd.x0 + wd.x1) / 2.0, my = (wd.y0 + wd.y1) / 2.0
        let hw = (wd.x1 - wd.x0) / 2.0, hh = (wd.y1 - wd.y0) / 2.0
        let nx = s.rx(mx, my), ny = s.ry(mx, my)
        if !wd.base.isNaN { wd.base = s.ry(mx, wd.base) }
        wd.x0 = nx - hw
        wd.x1 = nx + hw
        wd.y0 = ny - hh
        wd.y1 = ny + hh
    }

    // MARK: bảng (lattice)

    private static func byPos(_ a: Seg, _ b: Seg) -> Int {
        if a.pos != b.pos { return a.pos < b.pos ? -1 : 1 }
        return a.a < b.a ? -1 : (a.a > b.a ? 1 : 0)
    }

    private static func byA(_ a: Seg, _ b: Seg) -> Int {
        if a.a != b.a { return a.a < b.a ? -1 : 1 }
        return a.pos < b.pos ? -1 : (a.pos > b.pos ? 1 : 0)
    }

    /// Gộp các đường kẻ cùng hướng nằm thẳng hàng thành đoạn dài nhất có thể.
    static func mergeRules(_ rules: [Seg], _ snap: Double, _ join: Double, _ minLen: Double) -> [Seg] {
        var sorted = rules
        JavaNum.stableSort(&sorted, byPos)
        var out = [Seg]()
        var i = 0
        let n = sorted.count
        while i < n {
            var j = i
            let mn = sorted[i].pos
            var mx = mn
            while j + 1 < n && sorted[j + 1].pos - mx <= snap && sorted[j + 1].pos - mn <= snap * 2.5 {
                j += 1
                mx = sorted[j].pos
            }
            var sw = 0.0, sv = 0.0
            var items = [Seg]()
            for q in i...j {
                let r = sorted[q]
                let wt = JavaNum.max(0.5, r.b - r.a)
                sw += wt
                sv += r.pos * wt
                items.append(r)
            }
            let center = sv / sw
            JavaNum.stableSort(&items, byA)
            var cur: Seg? = nil
            for r in items {
                if let c = cur, r.a <= c.b + join {
                    if r.b > c.b { c.b = r.b }
                    if r.b - r.a > c.longest {
                        c.longest = r.b - r.a
                        c.t = r.t
                    }
                } else {
                    let c = seg(r.h, center, r.a, r.b, r.t)
                    cur = c
                    out.append(c)
                }
            }
            i = j + 1
        }
        out.removeAll { $0.b - $0.a < minLen }
        return out
    }

    static func ufFind(_ p: inout [Int], _ start: Int) -> Int {
        var i = start
        while p[i] != i {
            p[i] = p[p[i]]
            i = p[i]
        }
        return i
    }

    static func ufUnion(_ p: inout [Int], _ x: Int, _ y: Int) {
        let a = ufFind(&p, x)
        let b = ufFind(&p, y)
        if a != b { p[b] = a }
    }

    /// Gom các giá trị gần nhau (<= tol, cả nhóm không rộng quá 2,5 tol) thành một, lấy trung bình.
    static func uniqueSorted(_ values: [Double], _ tol: Double) -> [Double] {
        let n = values.count
        var v = values
        JavaNum.sort(&v)
        var out = [Double]()
        var i = 0
        while i < n {
            var j = i
            var sum = v[i]
            while j + 1 < n && v[j + 1] - v[j] <= tol && v[j + 1] - v[i] <= tol * 2.5 {
                j += 1
                sum += v[j]
            }
            out.append(sum / Double(j - i + 1))
            i = j + 1
        }
        return out
    }

    static func near(_ arr: [Double], _ v: Double, _ tol: Double) -> Bool {
        for x in arr where abs(x - v) <= tol { return true }
        return false
    }

    /// Đường kẻ phủ cạnh lưới [lo, hi] tại vị trí pos (>= edgeCover): trả về đoạn phủ nhiều nhất, nil nếu không đủ.
    static func cover(_ segs: [Seg], _ pos: Double, _ lo: Double, _ hi: Double, _ snap: Double) -> Seg? {
        var len = 0.0, bestO = 0.0
        var best: Seg? = nil
        for sg in segs {
            if abs(sg.pos - pos) > snap * 1.5 { continue }
            let o = JavaNum.min(sg.b, hi) - JavaNum.max(sg.a, lo)
            if o > 0 {
                len += o
                if best == nil || o > bestO {
                    bestO = o
                    best = sg
                }
            }
        }
        return len >= (hi - lo) * edgeCover ? best : nil
    }

    static func thick(_ segs: [Seg], _ pos: Double, _ snap: Double) -> Double {
        var t = 0.0
        for sg in segs where abs(sg.pos - pos) <= snap * 1.5 { t = JavaNum.max(t, sg.t) }
        return t
    }

    /// Dựng lưới bảng từ một nhóm đường kẻ chạm nhau; nil nếu không thành bảng (không có ô nào kín bốn phía).
    static func buildTable(_ hs: [Seg], _ vs: [Seg], _ snap: Double, _ touch: Double) -> Tbl? {
        var xsRaw = [Double](), ysRaw = [Double]()
        for v in vs { xsRaw.append(v.pos) }
        for hgt in hs { ysRaw.append(hgt.pos) }
        let xs0 = uniqueSorted(xsRaw, snap), ys0 = uniqueSorted(ysRaw, snap)
        // đường lưới: vị trí đường kẻ + đầu mút đường kẻ không chạm đường vuông góc nào
        var extraX = [Double](), extraY = [Double]()
        for hgt in hs {
            if !near(xs0, hgt.a, touch) && !near(extraX, hgt.a, touch) { extraX.append(hgt.a) }
            if !near(xs0, hgt.b, touch) && !near(extraX, hgt.b, touch) { extraX.append(hgt.b) }
        }
        for v in vs {
            if !near(ys0, v.a, touch) && !near(extraY, v.a, touch) { extraY.append(v.a) }
            if !near(ys0, v.b, touch) && !near(extraY, v.b, touch) { extraY.append(v.b) }
        }
        for x in xs0 { extraX.append(x) }
        for y in ys0 { extraY.append(y) }
        let xs = uniqueSorted(extraX, snap), ys = uniqueSorted(extraY, snap)
        let m = xs.count - 1, n = ys.count - 1
        if m < 1 || n < 1 || m > 200 || n > 400 { return nil }
        // vEdge[i][j] -> vEdge[i * n + j]; hEdge[j][i] -> hEdge[j * m + i]
        var vEdge = [Seg?](repeating: nil, count: (m + 1) * n), hEdge = [Seg?](repeating: nil, count: (n + 1) * m)
        for i in 0...m {
            for j in 0..<n { vEdge[i * n + j] = cover(vs, xs[i], ys[j], ys[j + 1], snap) }
        }
        for j in 0...n {
            for i in 0..<m { hEdge[j * m + i] = cover(hs, ys[j], xs[i], xs[i + 1], snap) }
        }
        // gộp các ô lưới không bị đường kẻ ngăn
        var uf = [Int](repeating: 0, count: m * n)
        for i in 0..<(m * n) { uf[i] = i }
        for j in 0..<n {
            for i in 0..<m {
                if i + 1 < m && vEdge[(i + 1) * n + j] == nil { ufUnion(&uf, j * m + i, j * m + i + 1) }
                if j + 1 < n && hEdge[(j + 1) * m + i] == nil { ufUnion(&uf, j * m + i, (j + 1) * m + i) }
            }
        }
        // chia mỗi vùng thành hình chữ nhật (tham lam theo hàng)
        let t = Tbl()
        t.owner = [Int](repeating: -1, count: m * n)
        for j in 0..<n {
            for i in 0..<m {
                if t.owner[j * m + i] >= 0 { continue }
                let region = ufFind(&uf, j * m + i)
                var c1 = i
                while c1 + 1 < m && t.owner[j * m + c1 + 1] < 0 && ufFind(&uf, j * m + c1 + 1) == region && vEdge[(c1 + 1) * n + j] == nil {
                    c1 += 1
                }
                var r1 = j
                while r1 + 1 < n {
                    var ok = true
                    var q = i
                    while q <= c1 && ok {
                        if t.owner[(r1 + 1) * m + q] >= 0 || ufFind(&uf, (r1 + 1) * m + q) != region || hEdge[(r1 + 1) * m + q] != nil {
                            ok = false
                        }
                        q += 1
                    }
                    // hình chữ nhật không được bị đường kẻ dọc cắt ở hàng mới
                    q = i + 1
                    while q <= c1 && ok {
                        if vEdge[q * n + r1 + 1] != nil { ok = false }
                        q += 1
                    }
                    if !ok { break }
                    r1 += 1
                }
                let c = TCell()
                c.c0 = i
                c.c1 = c1
                c.r0 = j
                c.r1 = r1
                let index = t.cells.count
                for jj in j...r1 {
                    for ii in i...c1 { t.owner[jj * m + ii] = index }
                }
                t.cells.append(c)
            }
        }
        // viền: một cạnh ô có viền khi mọi cạnh lưới dọc theo nó đều có đường kẻ
        var claimedH = [Bool](repeating: false, count: (n + 1) * m), claimedV = [Bool](repeating: false, count: (m + 1) * n)
        var closed = 0
        for c in t.cells {
            var top = Double.greatestFiniteMagnitude, bottom = Double.greatestFiniteMagnitude
            var left = Double.greatestFiniteMagnitude, right = Double.greatestFiniteMagnitude
            var topT = 0.0, bottomT = 0.0, leftT = 0.0, rightT = 0.0
            for i in c.c0...c.c1 {
                if let e = hEdge[c.r0 * m + i] {
                    topT = JavaNum.max(topT, e.t)
                } else {
                    top = -1
                }
                if let e = hEdge[(c.r1 + 1) * m + i] {
                    bottomT = JavaNum.max(bottomT, e.t)
                } else {
                    bottom = -1
                }
            }
            for j in c.r0...c.r1 {
                if let e = vEdge[c.c0 * n + j] {
                    leftT = JavaNum.max(leftT, e.t)
                } else {
                    left = -1
                }
                if let e = vEdge[(c.c1 + 1) * n + j] {
                    rightT = JavaNum.max(rightT, e.t)
                } else {
                    right = -1
                }
            }
            c.bTop = top < 0 ? 0 : JavaNum.max(0.5, topT)
            c.bBottom = bottom < 0 ? 0 : JavaNum.max(0.5, bottomT)
            c.bLeft = left < 0 ? 0 : JavaNum.max(0.5, leftT)
            c.bRight = right < 0 ? 0 : JavaNum.max(0.5, rightT)
            if c.bTop > 0 {
                for i in c.c0...c.c1 { claimedH[c.r0 * m + i] = true }
            }
            if c.bBottom > 0 {
                for i in c.c0...c.c1 { claimedH[(c.r1 + 1) * m + i] = true }
            }
            if c.bLeft > 0 {
                for j in c.r0...c.r1 { claimedV[c.c0 * n + j] = true }
            }
            if c.bRight > 0 {
                for j in c.r0...c.r1 { claimedV[(c.c1 + 1) * n + j] = true }
            }
            if c.bTop > 0 && c.bBottom > 0 && c.bLeft > 0 && c.bRight > 0 { closed += 1 }
            c.x0 = xs[c.c0]
            c.x1 = xs[c.c1 + 1]
            c.y0 = ys[c.r0]
            c.y1 = ys[c.r1 + 1]
        }
        if closed == 0 { return nil }
        // mẩu đường kẻ không thành viền ô nào: vẽ như đường kẻ rời
        for j in 0...n {
            for i in 0..<m {
                if let e = hEdge[j * m + i], !claimedH[j * m + i] { t.extra.append(seg(true, ys[j], xs[i], xs[i + 1], e.t)) }
            }
        }
        for i in 0...m {
            for j in 0..<n {
                if let e = vEdge[i * n + j], !claimedV[i * n + j] { t.extra.append(seg(false, xs[i], ys[j], ys[j + 1], e.t)) }
            }
        }
        t.xs = xs
        t.ys = ys
        t.cols = m
        t.rows = n
        t.hT = [Double](repeating: 0, count: n + 1)
        t.vT = [Double](repeating: 0, count: m + 1)
        for j in 0...n { t.hT[j] = thick(hs, ys[j], snap) }
        for i in 0...m { t.vT[i] = thick(vs, xs[i], snap) }
        t.x0 = xs[0]
        t.x1 = xs[m]
        t.y0 = ys[0]
        t.y1 = ys[n]
        return t
    }

    /// Tìm các bảng trong tập đường kẻ; đường kẻ không thuộc bảng nào đưa vào `loose`.
    static func detectTables(_ s: Scan, _ segs: [Seg], _ loose: inout [Seg]) -> [Tbl] {
        let snap = snapK * s.hc, join = joinK * s.hc, touch = touchK * s.hc
        var hIn = [Seg](), vIn = [Seg]()
        for sg in segs {
            if sg.h {
                hIn.append(sg)
            } else {
                vIn.append(sg)
            }
        }
        let hs = mergeRules(hIn, snap, join, 1.5 * s.hc), vs = mergeRules(vIn, snap, join, 1.5 * s.hc)
        let nh = hs.count, nv = vs.count
        var uf = [Int](repeating: 0, count: nh + nv)
        for i in 0..<(nh + nv) { uf[i] = i }
        for i in 0..<nh {
            let hgt = hs[i]
            for q in 0..<nv {
                let v = vs[q]
                if v.pos >= hgt.a - touch && v.pos <= hgt.b + touch && hgt.pos >= v.a - touch && hgt.pos <= v.b + touch {
                    ufUnion(&uf, i, nh + q)
                }
            }
        }
        var tables = [Tbl]()
        var done = [Bool](repeating: false, count: nh + nv)
        let pageArea = Double(s.w) * Double(s.h)
        for i in 0..<(nh + nv) {
            let root = ufFind(&uf, i)
            if done[root] { continue }
            done[root] = true
            var gh = [Seg](), gv = [Seg]()
            for q in 0..<(nh + nv) {
                if ufFind(&uf, q) != root { continue }
                if q < nh {
                    gh.append(hs[q])
                } else {
                    gv.append(vs[q - nh])
                }
            }
            var t: Tbl? = nil
            if gh.count >= 2 && gv.count >= 2 { t = buildTable(gh, gv, snap, touch) }
            // một khung đơn quanh gần hết trang là khung trang, không phải bảng
            if let tb = t, tb.cells.count == 1 && (tb.x1 - tb.x0) * (tb.y1 - tb.y0) > pageArea * 0.45 { t = nil }
            if let tb = t {
                tables.append(tb)
            } else {
                loose.append(contentsOf: gh)
                loose.append(contentsOf: gv)
            }
        }
        JavaNum.stableSort(&tables) { a, b in
            if a.y0 != b.y0 { return a.y0 < b.y0 ? -1 : 1 }
            return a.x0 < b.x0 ? -1 : (a.x0 > b.x0 ? 1 : 0)
        }
        return tables
    }

    /// Chỉ số ô chứa điểm (x, y); -1 nếu điểm nằm ngoài bảng.
    static func cellOfPoint(_ t: Tbl, _ x: Double, _ y: Double) -> Int {
        if x < t.x0 || x > t.x1 || y < t.y0 || y > t.y1 { return -1 }
        var i = 0
        while i + 1 < t.cols && x >= t.xs[i + 1] { i += 1 }
        var j = 0
        while j + 1 < t.rows && y >= t.ys[j + 1] { j += 1 }
        return t.owner[j * t.cols + i]
    }

    // MARK: dòng chữ

    private static func byX(_ a: W, _ b: W) -> Int {
        if a.x0 != b.x0 { return a.x0 < b.x0 ? -1 : 1 }
        if a.y0 != b.y0 { return a.y0 < b.y0 ? -1 : 1 }
        return a.idx < b.idx ? -1 : (a.idx > b.idx ? 1 : 0)
    }

    /// Gom các từ của một vùng chứa thành dòng: duyệt từ trái sang phải, mỗi từ nối vào dòng có từ cuối chồng dọc với nó
    /// (>= 50% chiều cao từ thấp hơn) và gần nó nhất về bên trái — chịu được trang hơi nghiêng. tables != nil: không nối
    /// hai từ nằm hai bên một bảng.
    static func chainRows(_ ws: [W], _ tables: [Tbl]?) -> [Row] {
        var sorted = ws
        JavaNum.stableSort(&sorted, byX)
        var rows = [Row]()
        for wd in sorted {
            var best: Row? = nil
            var bestGap = Double.greatestFiniteMagnitude
            let hw = wd.y1 - wd.y0
            for r in rows {
                let last = r.ws[r.ws.count - 1]
                let hl = last.y1 - last.y0
                let ov = JavaNum.min(wd.y1, last.y1) - JavaNum.max(wd.y0, last.y0)
                let minH = JavaNum.min(hw, hl)
                if ov < 0.5 * minH { continue }
                let gap = wd.x0 - last.x1
                if gap < -0.5 * minH { continue }
                if let tb = tables, tableBetween(tb, last, wd) { continue }
                if gap < bestGap {
                    bestGap = gap
                    best = r
                }
            }
            if let b = best {
                b.ws.append(wd)
            } else {
                let b = Row()
                rows.append(b)
                b.ws.append(wd)
            }
        }
        var hs = [Double](repeating: 0, count: 64)
        for r in rows {
            r.x0 = Double.greatestFiniteMagnitude
            r.y0 = Double.greatestFiniteMagnitude
            r.x1 = -Double.greatestFiniteMagnitude
            r.y1 = -Double.greatestFiniteMagnitude
            let n = r.ws.count
            if hs.count < n { hs = [Double](repeating: 0, count: n * 2) }
            for i in 0..<n {
                let wd = r.ws[i]
                r.x0 = JavaNum.min(r.x0, wd.x0)
                r.x1 = JavaNum.max(r.x1, wd.x1)
                r.y0 = JavaNum.min(r.y0, wd.y0)
                r.y1 = JavaNum.max(r.y1, wd.y1)
                r.glyphs += wd.glyphs
                hs[i] = wd.oy1 - wd.oy0
            }
            // chia khúc tại khoảng trống lớn (>= 1,5 em; em sơ bộ = chiều cao khung của bộ nhận dạng): mỗi khúc sẽ bắt
            // đầu bằng một tab và có kiểu chữ riêng (tiêu ngữ hai cột, nhãn - giá trị)
            let em0 = JavaNum.max(4.0, median(hs, n))
            var cur: Part? = nil
            for i in 0..<n {
                let wd = r.ws[i]
                if cur == nil || wd.x0 - r.ws[i - 1].x1 >= 1.5 * em0 {
                    let c = Part()
                    c.x0 = wd.x0
                    r.parts.append(c)
                    cur = c
                }
                if let c = cur {
                    c.ws.append(wd)
                    c.x1 = wd.x1
                    c.glyphs += wd.glyphs
                }
            }
        }
        JavaNum.stableSort(&rows) { a, b in
            let ya = (a.y0 + a.y1) / 2.0, yb = (b.y0 + b.y1) / 2.0
            if ya != yb { return ya < yb ? -1 : 1 }
            return a.x0 < b.x0 ? -1 : (a.x0 > b.x0 ? 1 : 0)
        }
        return rows
    }

    static func tableBetween(_ tables: [Tbl], _ a: W, _ b: W) -> Bool {
        let y0 = JavaNum.max(a.y0, b.y0), y1 = JavaNum.min(a.y1, b.y1)
        for t in tables {
            if t.y1 < y0 || t.y0 > y1 { continue }
            if t.x0 >= a.x1 - 1 && t.x1 <= b.x0 + 1 { return true }
            // bảng nằm chắn giữa (một phần)
            let mid = (t.x0 + t.x1) / 2.0
            if mid > a.x1 && mid < b.x0 { return true }
        }
        return false
    }

    static func median(_ v: [Double], _ n: Int) -> Double {
        if n == 0 { return 0.0 }
        var c = Array(v[0..<n])
        JavaNum.sort(&c)
        return (n & 1) == 1 ? c[n / 2] : (c[n / 2 - 1] + c[n / 2]) / 2.0
    }

    /// Trung vị có trọng số của các cặp (giá trị, trọng số).
    static func wmedian(_ items: [(Double, Double)]) -> Double {
        if items.isEmpty { return 0.0 }
        var list = items
        JavaNum.stableSort(&list) { a, b in a.0 < b.0 ? -1 : (a.0 > b.0 ? 1 : 0) }
        var sum = 0.0
        for e in list { sum += e.1 }
        var acc = 0.0
        for e in list {
            acc += e.1
            if acc * 2 >= sum { return e.0 }
        }
        return list[list.count - 1].0
    }

    static func setMetrics(_ wd: W, _ style: Int) {
        wd.adv = advUnits(wd.text, style)
        wd.lsb = WordFont.lsbUnits(wd.text, style)
        wd.rsb = WordFont.rsbUnits(wd.text, style)
    }

    /// Kiểu chữ của mọi dòng, tính theo từng khúc: đường chân chữ của dòng; chữ nghiêng (hình chiếu theo cột sắc hơn
    /// khi nắn nghiêng lại); nét đậm (bề dày nét / cỡ chữ so với trung vị cả trang); cỡ chữ (theo bề rộng mực của các
    /// từ với số đo Times New Roman; khúc ngắn thì theo chiều cao thân chữ nhân hệ số hiệu chỉnh của trang); rồi gom cỡ
    /// chữ về vài cỡ chính của trang.
    static func styleRows(_ rows: [Row], _ g: Geometry) {
        var tmp = [Double](repeating: 0, count: 64)
        var parts = [Part]()
        // ---- đường chân chữ của dòng + em theo chiều cao, màu của từng khúc
        for r in rows {
            let n = r.ws.count
            if tmp.count < n { tmp = [Double](repeating: 0, count: n * 2) }
            var c = 0
            for wd in r.ws where !wd.base.isNaN {
                tmp[c] = wd.base
                c += 1
            }
            var rowEms = [(Double, Double)]()
            for wd in r.ws where wd.emH > 0 { rowEms.append((wd.emH, Double(wd.glyphs))) }
            if c > 0 {
                r.base = median(tmp, c)
            } else {
                // không từ nào đo được: đáy khung mực trừ phần đuôi chữ
                let em = !rowEms.isEmpty ? wmedian(rowEms) : (r.y1 - r.y0) / (WordFont.capHeight + WordFont.descent)
                r.base = r.y1 - WordFont.descent * em
            }
            for pt in r.parts {
                var ems = [(Double, Double)]()
                var colored = 0, col = 0
                var s0 = 0.0, s1 = 0.0, s2 = 0.0
                for wd in pt.ws {
                    if wd.emH > 0 { ems.append((wd.emH, Double(wd.glyphs))) }
                    if wd.colored {
                        colored += wd.glyphs
                        col = wd.color
                    }
                    s0 += wd.sh0
                    s1 += wd.sh1
                    s2 += wd.sh2
                }
                pt.emH = wmedian(ems)
                pt.color = colored * 2 > pt.glyphs ? col : 0
                // nghiêng: nắn nghiêng làm hình chiếu sắc hơn hẳn so với để nguyên
                pt.italic = s0 > 0 && pt.glyphs >= 3 && JavaNum.max(s1, s2) > 1.025 * s0
                for wd in pt.ws { wd.italic = pt.italic }
                parts.append(pt)
            }
        }
        // ---- em sơ bộ theo bề rộng (chưa biết đậm) để chuẩn hoá bề dày nét
        for pt in parts {
            for wd in pt.ws { setMetrics(wd, WordFont.style(false, pt.italic)) }
            pt.emW = widthEm(pt)
        }
        // ---- nét đậm
        var ratios = [(Double, Double)]()
        for pt in parts {
            let em = pt.emW > 0 ? pt.emW : pt.emH
            if !(em >= 14.0) { continue }
            for wd in pt.ws where wd.stroke > 0 && wd.glyphs >= 2 { ratios.append((wd.stroke / em, Double(wd.glyphs))) }
        }
        let ref = wmedian(ratios)
        if ref > 0 {
            for pt in parts {
                let em = pt.emW > 0 ? pt.emW : pt.emH
                if !(em >= 14.0) { continue }
                var boldG = 0, tot = 0
                for wd in pt.ws {
                    if !(wd.stroke > 0) || wd.glyphs < 2 { continue }
                    tot += wd.glyphs
                    wd.bold = wd.stroke / em > 1.27 * ref
                    if wd.bold { boldG += wd.glyphs }
                }
                if tot == 0 { continue }
                let nw = pt.ws.count
                if boldG * 10 >= tot * 7 {
                    pt.bold = true
                    for wd in pt.ws { wd.bold = true }
                } else {
                    // đậm một phần: chỉ nhận cụm từ đậm ở đầu khúc (nhãn kiểu "Điều 1.", "Kính gửi:")
                    var lead = 0
                    while lead < nw && (pt.ws[lead].bold || (pt.ws[lead].glyphs < 2 && lead > 0)) { lead += 1 }
                    var rest = false
                    var i = lead
                    while i < nw {
                        rest = rest || (pt.ws[i].bold && pt.ws[i].glyphs >= 4)
                        i += 1
                    }
                    if lead < 1 || boldG * 10 < tot * 2 || rest { lead = 0 }
                    for i in 0..<nw { pt.ws[i].bold = i < lead }
                }
            }
        }
        // ---- em theo bề rộng với số đo đúng kiểu
        var rho = [(Double, Double)]()
        for pt in parts {
            for wd in pt.ws { setMetrics(wd, WordFont.style(wd.bold, pt.italic)) }
            pt.emW = widthEm(pt)
            if pt.emW > 0 && pt.emH > 0 && pt.glyphs >= 12 { rho.append((pt.emW / pt.emH, Double(pt.glyphs))) }
        }
        // hệ số trang: em theo bề rộng / em theo chiều cao (1 với Times New Roman; khác 1 khi phông gốc rộng / hẹp hơn)
        let k = rho.count >= 3 ? clampD(wmedian(rho), 0.8, 1.25) : 1.0
        var sizes = [(Double, Double)]()
        for pt in parts {
            var em = 0.0
            if pt.emW > 0 && pt.glyphs >= 12 {
                em = pt.emW
            } else if pt.emH > 0 && pt.emW > 0 && pt.glyphs >= 5 {
                em = (pt.emH * k + pt.emW) / 2.0
            } else if pt.emH > 0 {
                em = pt.emH * k
            } else if pt.emW > 0 && pt.glyphs >= 4 {
                em = pt.emW
            } else {
                // một hai ký tự: theo chiều cao khung mực (chữ số / chữ hoa cao bằng chữ hoa)
                var hgt = 0.0
                for wd in pt.ws { hgt = JavaNum.max(hgt, wd.y1 - wd.y0) }
                em = hgt / WordFont.capHeight * k
            }
            pt.size = clampD(em * g.scale, 4.0, 96.0)
            if pt.glyphs >= 12 { sizes.append((pt.size, Double(pt.glyphs))) }
        }
        // ---- gom cỡ chữ: lần lượt lấy cỡ nhiều chữ nhất, hút các khúc lệch <= 6% về nó
        var modes = [Double]()
        var pool = sizes
        while !pool.isEmpty && modes.count < 8 {
            var best = 0.0, bestW = -1.0
            for a in pool {
                var wsum = 0.0
                for b in pool where abs(b.0 - a.0) <= 0.035 * a.0 { wsum += b.1 }
                if wsum > bestW || (wsum == bestW && a.0 < best) {
                    bestW = wsum
                    best = a.0
                }
            }
            // tâm của cụm = trung bình có trọng số
            var sw = 0.0, sv = 0.0
            for b in pool where abs(b.0 - best) <= 0.06 * best {
                sw += b.1
                sv += b.0 * b.1
            }
            let center = sv / sw
            var m = Double(rnd(center * 2.0)) / 2.0
            // cỡ chữ văn bản thường là số nguyên: lệch dưới 0,3 pt thì lấy số nguyên
            let whole = Double(rnd(center))
            if abs(center - whole) <= 0.3 { m = whole }
            if !modes.contains(where: { JavaNum.same($0, m) }) { modes.append(m) }
            pool.removeAll { abs($0.0 - best) <= 0.06 * best }
        }
        for pt in parts {
            let tol = pt.glyphs >= 12 ? 0.07 : (pt.glyphs >= 4 ? 0.08 : 0.35)
            var pick = -1.0, bd = Double.greatestFiniteMagnitude
            for m in modes {
                let d = abs(m - pt.size)
                if d <= tol * pt.size && d < bd {
                    bd = d
                    pick = m
                }
            }
            if pick > 0 {
                pt.size = pick
            } else {
                pt.size = JavaNum.max(4.0, Double(rnd(pt.size * 2.0)) / 2.0)
            }
        }
        for r in rows {
            r.size = 0
            for pt in r.parts { r.size = JavaNum.max(r.size, pt.size) }
        }
    }

    /// Em (điểm ảnh) sao cho tổng bề rộng mực của các từ khớp số đo Times New Roman; 0 nếu không đủ dữ liệu.
    static func widthEm(_ r: Part) -> Double {
        var ink = 0.0, nat = 0.0
        for wd in r.ws {
            if wd.glyphs < 2 { continue }
            let u = JavaNum.wrap32(wd.adv - wd.lsb - wd.rsb)
            if u <= 0 { continue }
            ink += wd.x1 - wd.x0
            nat += Double(u) / WordFont.upm
        }
        return nat > 0.5 ? ink / nat : 0.0
    }

    // MARK: dòng -> đoạn Word

    static func tok(_ t: Int, _ pt: Part, _ wd: W?) -> WordDoc.Tok {
        let k = WordDoc.Tok()
        k.t = t
        k.size = pt.size
        k.color = pt.color
        k.bold = wd != nil ? wd!.bold : pt.bold
        k.italic = pt.italic
        k.underline = pt.underline
        return k
    }

    /// Đoạn Word của một dòng: toạ độ pt; mỗi khúc sau khúc đầu bắt đầu bằng tab tới đúng vị trí; trong một khúc, nếu
    /// một giá trị giãn chữ chung giải thích được vị trí mọi từ thì cả khúc là một mẩu chữ, không thì mỗi dấu cách mang
    /// độ giãn riêng để từ kế tiếp bắt đầu đúng chỗ (dòng căn đều hai lề).
    static func makePara(_ r: Row, _ g: Geometry) -> WordDoc.Para {
        let p = WordDoc.Para()
        p.base = g.oy + r.base * g.scale
        // kiểu của dấu kết đoạn: khúc nhiều chữ nhất
        var main = r.parts[0]
        for pt in r.parts where pt.glyphs > main.glyphs { main = pt }
        p.size = main.size
        p.bold = main.bold
        p.italic = main.italic
        p.color = main.color
        var pen = 0.0
        for pi in 0..<r.parts.count {
            let pt = r.parts[pi]
            let s = pt.size
            let ws = pt.ws
            let n = ws.count
            var x0 = [Double](repeating: 0, count: n), x1 = [Double](repeating: 0, count: n)
            var adv = [Double](repeating: 0, count: n), lsb = [Double](repeating: 0, count: n)
            var rsb = [Double](repeating: 0, count: n), sp = [Double](repeating: 0, count: n)
            for i in 0..<n {
                let wd = ws[i]
                x0[i] = g.ox + wd.x0 * g.scale
                x1[i] = g.ox + wd.x1 * g.scale
                adv[i] = Double(wd.adv) / WordFont.upm * s
                lsb[i] = Double(wd.lsb) / WordFont.upm * s
                rsb[i] = Double(wd.rsb) / WordFont.upm * s
                // dấu cách sau từ i mang kiểu của từ i (đậm chỉ khi từ kế cũng đậm)
                let sb = wd.bold && i + 1 < n && ws[i + 1].bold
                sp[i] = Double(WordFont.spaceUnits(WordFont.style(sb, pt.italic))) / WordFont.upm * s
            }
            let start = x0[0] - lsb[0]
            if pi == 0 {
                p.x = start
            } else {
                let tb = tok(WordDoc.tTab, pt, nil)
                tb.pos = start
                p.toks.append(tb)
                p.hasTab = true
            }
            pen = start
            // giãn chung của cả khúc
            var num = 0.0, den = 0.0
            for q in 0..<n {
                let wd = ws[q]
                num += (x1[q] - x0[q]) - (adv[q] - lsb[q] - rsb[q])
                den += Double(wd.glyphs - 1)
                if q > 0 {
                    num += (x0[q] - x1[q - 1]) - (rsb[q - 1] + sp[q - 1] + lsb[q])
                    den += 2
                }
            }
            let lo = -0.06 * s, hi = 0.12 * s
            let shared = den > 0 ? clampD(quant(num / den), lo, hi) : 0.0
            var uniform = true
            var q = 0
            while q < n && uniform {
                let wd = ws[q]
                let wTol = JavaNum.max(1.0, 0.06 * (x1[q] - x0[q]))
                if abs((x1[q] - x0[q]) - (adv[q] - lsb[q] - rsb[q]) - shared * Double(wd.glyphs - 1)) > wTol { uniform = false }
                if q > 0 && abs((x0[q] - x1[q - 1]) - (rsb[q - 1] + sp[q - 1] + lsb[q]) - 2.0 * shared) > JavaNum.max(1.5, 0.12 * s) {
                    uniform = false
                }
                q += 1
            }
            var letter = shared
            if !uniform {
                // chữ trước (dấu cách có thể bị kéo giãn do căn đều): trung vị có trọng số của độ giãn từng từ
                var items = [(Double, Double)]()
                for q in 0..<n {
                    let wd = ws[q]
                    if wd.glyphs >= 2 {
                        items.append((((x1[q] - x0[q]) - (adv[q] - lsb[q] - rsb[q])) / Double(wd.glyphs - 1), Double(wd.glyphs - 1)))
                    }
                }
                letter = clampD(quant(wmedian(items)), lo, hi)
            }
            for q in 0..<n {
                let wd = ws[q]
                if q > 0 {
                    let sk = tok(WordDoc.tSpace, pt, ws[q - 1])
                    sk.bold = ws[q - 1].bold && wd.bold
                    var extra = shared
                    if !uniform {
                        // bút đang ở `pen`; từ kế bắt đầu tại x0 - lsb
                        extra = quant((x0[q] - lsb[q]) - pen - sp[q - 1])
                        if extra < -0.75 * sp[q - 1] { extra = r20(-0.75 * sp[q - 1]) }
                    }
                    sk.spacing = extra
                    p.toks.append(sk)
                    pen += sp[q - 1] + extra
                }
                let tk = tok(WordDoc.tText, pt, wd)
                tk.text = wd.text
                tk.spacing = letter
                p.toks.append(tk)
                pen += adv[q] + letter * Double(wd.glyphs)
            }
        }
        p.x1 = JavaNum.max(p.x + 1.0, pen)
        return p
    }

    /// Chiều cao hộp dòng tự nhiên của cỡ chữ s (pt).
    static func lineHeight(_ s: Double) -> Double {
        return clampD(lineK, 1.02, 1.32) * s
    }

    /// Các dòng của một vùng chứa -> đoạn có hộp dòng chính xác. top: mép trên vùng chứa (dòng không được bắt đầu cao
    /// hơn); bottom: mép dưới (hộp dòng cuối không vượt quá).
    static func placeRows(_ rows: [Row], _ top: Double, _ bottom: Double, _ g: Geometry) -> [WordDoc.Para] {
        let n = rows.count
        var out = [WordDoc.Para]()
        var hgt = [Double](repeating: 0, count: n), ys = [Double](repeating: 0, count: n)
        for i in 0..<n {
            ys[i] = g.oy + rows[i].base * g.scale
            hgt[i] = lineHeight(rows[i].size)
        }
        for i in 0..<n {
            if i > 0 { hgt[i] = JavaNum.min(hgt[i], ys[i] - ys[i - 1]) }
            if i + 1 < n { hgt[i] = JavaNum.min(hgt[i], ys[i + 1] - ys[i]) }
            hgt[i] = JavaNum.max(hgt[i], 1.0)
        }
        for i in 0..<n {
            var hh = hgt[i]
            var t = ys[i] - 0.8 * hh
            if t < top {
                // chữ bắt đầu cao hơn vùng chứa: dùng hộp thấp hơn
                hh = JavaNum.max(1.0, JavaNum.min(hh, (ys[i] - top) / 0.8))
                t = JavaNum.max(top, ys[i] - 0.8 * hh)
            }
            if t + hh > bottom { hh = JavaNum.max(1.0, bottom - t) }
            let p = makePara(rows[i], g)
            p.top = t
            p.height = hh
            out.append(p)
        }
        return out
    }

    static func tableBlock(_ t: Tbl, _ g: Geometry) -> WordDoc.Block {
        let wt = WordDoc.Table()
        let m = t.cols, n = t.rows
        wt.cols = m
        wt.rows = n
        wt.xs = [Double](repeating: 0, count: m + 1)
        wt.ys = [Double](repeating: 0, count: n + 1)
        wt.hT = [Double](repeating: 0, count: n + 1)
        wt.vT = [Double](repeating: 0, count: m + 1)
        for i in 0...m {
            wt.xs[i] = g.ox + t.xs[i] * g.scale
            wt.vT[i] = t.vT[i] * g.scale
        }
        for j in 0...n {
            wt.ys[j] = g.oy + t.ys[j] * g.scale
            wt.hT[j] = t.hT[j] * g.scale
        }
        wt.owner = t.owner
        // lề trong của bảng: khoảng cách nhỏ nhất giữa đường kẻ trái và chữ (tối đa 5,4 pt)
        var margin = maxCellMargin
        for c in t.cells {
            for r in c.rows {
                margin = JavaNum.min(margin, (r.x0 - c.x0) * g.scale)
                margin = JavaNum.min(margin, (c.x1 - r.x1) * g.scale)
            }
        }
        margin = JavaNum.max(0.0, r20(margin))
        wt.marL = margin
        wt.marR = margin
        for c in t.cells {
            let wc = WordDoc.Cell()
            wc.c0 = c.c0
            wc.c1 = c.c1
            wc.r0 = c.r0
            wc.r1 = c.r1
            wc.x0 = wt.xs[c.c0]
            wc.x1 = wt.xs[c.c1 + 1]
            wc.y0 = wt.ys[c.r0]
            wc.y1 = wt.ys[c.r1 + 1]
            wc.bTop = c.bTop * g.scale
            wc.bBottom = c.bBottom * g.scale
            wc.bLeft = c.bLeft * g.scale
            wc.bRight = c.bRight * g.scale
            wc.marL = margin
            wc.marR = margin
            let interiorTop = wc.y0 + wt.hT[c.r0] / 2.0
            let interiorBottom = wc.y1 - wt.hT[c.r1 + 1] / 2.0 - 0.5
            let paras = placeRows(c.rows, interiorTop, interiorBottom, g)
            for p in paras {
                p.align = DocxWriter.alignmentOf(p, wc.x0 + wc.marL, wc.x1 - wc.marR)
                wc.paras.append(p)
            }
            wt.cells.append(wc)
        }
        let b = WordDoc.Block()
        b.table = wt
        b.top = wt.ys[0] - wt.hT[0] / 2.0
        b.bottom = wt.ys[n] + wt.hT[n] / 2.0
        b.x0 = wt.xs[0]
        b.x1 = wt.xs[m]
        return b
    }

    /// Chữ ngoài bảng: xếp vào dòng chảy khi được; dòng nằm cạnh bảng, hoặc quá sát dòng trước (hai cột lệch dòng, chữ in
    /// đè) thì thành hộp chữ neo theo trang. Dòng chảy được cắt thành từng khúc giữa các bảng để hộp dòng không lấn vào bảng.
    static func layoutFree(_ page: WordDoc.Page, _ freeRows: [Row], _ tableBlocks: [WordDoc.Block], _ g: Geometry) {
        var flow = [Row](), boxed = [Row]()
        for r in freeRows {
            let hgt = lineHeight(r.size)
            let y = g.oy + r.base * g.scale
            let top = y - 0.8 * hgt, bottom = y + 0.2 * hgt
            var beside = false
            for b in tableBlocks where top < b.bottom - 0.5 && bottom > b.top + 0.5 { beside = true }
            if beside {
                boxed.append(r)
            } else {
                flow.append(r)
            }
        }
        JavaNum.stableSort(&flow) { a, b in
            if a.base != b.base { return a.base < b.base ? -1 : 1 }
            return a.x0 < b.x0 ? -1 : (a.x0 > b.x0 ? 1 : 0)
        }
        var stack = [Row]()
        for r in flow {
            let prev: Row? = stack.isEmpty ? nil : stack[stack.count - 1]
            let need = 0.85 * JavaNum.min(lineHeight(r.size), lineHeight(prev != nil ? prev!.size : r.size))
            if let pv = prev, (r.base - pv.base) * g.scale < need {
                boxed.append(r)
            } else {
                stack.append(r)
            }
        }
        var blocks = tableBlocks
        var sortedTables = tableBlocks
        JavaNum.stableSort(&sortedTables) { a, b in a.top < b.top ? -1 : (a.top > b.top ? 1 : 0) }
        var segment = [Row]()
        var segTop = 0.0
        var ti = 0
        for i in 0...stack.count {
            let r: Row? = i < stack.count ? stack[i] : nil
            let y = r != nil ? g.oy + r!.base * g.scale : Double.greatestFiniteMagnitude
            while ti < sortedTables.count && (r == nil || sortedTables[ti].top < y) {
                flushSegment(&blocks, &segment, segTop, sortedTables[ti].top, g)
                segTop = JavaNum.max(segTop, sortedTables[ti].bottom)
                ti += 1
            }
            if let rr = r { segment.append(rr) }
        }
        flushSegment(&blocks, &segment, segTop, page.height, g)
        JavaNum.stableSort(&blocks) { a, b in
            if a.top != b.top { return a.top < b.top ? -1 : 1 }
            return a.x0 < b.x0 ? -1 : (a.x0 > b.x0 ? 1 : 0)
        }
        // bảng chồng dọc lên nhau (đứng cạnh nhau) không thể cùng nằm trong dòng chảy
        var cursor = -Double.greatestFiniteMagnitude
        for b in blocks {
            if b.table != nil && b.top < cursor - 0.5 {
                b.overlaps = true
            } else {
                cursor = JavaNum.max(cursor, b.bottom)
            }
        }
        page.blocks.append(contentsOf: blocks)
        for r in boxed {
            let p = placeRows([r], -Double.greatestFiniteMagnitude, Double.greatestFiniteMagnitude, g)[0]
            let bx = WordDoc.Box()
            bx.x = p.x
            bx.y = p.top
            bx.w = p.x1 - p.x
            bx.h = p.height
            bx.para = p
            page.boxes.append(bx)
        }
    }

    static func flushSegment(_ blocks: inout [WordDoc.Block], _ segment: inout [Row], _ segTop: Double, _ limit: Double, _ g: Geometry) {
        if segment.isEmpty { return }
        let paras = placeRows(segment, segTop, Double.greatestFiniteMagnitude, g)
        for p in paras {
            if p.top + p.height > limit { p.height = JavaNum.max(1.0, limit - p.top) }
            let b = WordDoc.Block()
            b.para = p
            b.top = p.top
            b.bottom = p.top + p.height
            b.x0 = p.x
            b.x1 = p.x1
            blocks.append(b)
        }
        segment.removeAll()
    }

    // MARK: gạch chân, hình

    /// Đường kẻ ngang mảnh nằm sát dưới một dòng chữ và dài đúng bằng dòng (hoặc cụm từ liền nhau) là gạch chân: đánh dấu
    /// dòng, bỏ đường kẻ. Trả về các đường kẻ còn lại.
    static func applyUnderlines(_ s: Scan, _ rows: [Row], _ loose: [Seg], _ g: Geometry) -> [Seg] {
        var rest = [Seg]()
        for sg in loose {
            var used = false
            if sg.h {
                for r in rows {
                    for pt in r.parts {
                        let em = pt.size / g.scale
                        if sg.t > JavaNum.max(2.0, 0.14 * em) { continue }
                        let dy = sg.pos - r.base
                        if dy < 0.02 * em || dy > 0.32 * em { continue }
                        if abs(sg.a - pt.x0) > 0.45 * em || abs(sg.b - pt.x1) > 0.45 * em { continue }
                        pt.underline = true
                        used = true
                        break
                    }
                    if used { break }
                }
            }
            if !used { rest.append(sg) }
        }
        return rest
    }

    /// Hình: mực còn lại sau khi bỏ đường kẻ và khung các từ đã nhận dạng (con dấu, chữ ký, ảnh, chữ viết tay không đọc
    /// được). Gom các nét gần nhau thành vùng; vùng quá nhỏ (đốm bẩn, dấu câu sót) bỏ qua.
    static func findPictures(_ s: Scan, _ ws: [W], _ page: WordDoc.Page, _ g: Geometry) {
        let aw = s.aw, ah = s.ah, k = s.k
        if aw < 16 || ah < 16 { return }
        let nA = aw * ah
        var m = [UInt8](repeating: 0, count: nA)
        var any = 0
        let amin = s.amin, hId = s.hId, vId = s.vId, hKeep = s.hKeep, vKeep = s.vKeep
        for i in 0..<nA {
            if Int(amin[i]) >= s.tq { continue }
            let a = hId[i], b = vId[i]
            if (a > 0 && hKeep[a]) || (b > 0 && vKeep[b]) { continue }
            m[i] = 1
            any += 1
        }
        if any == 0 { return }
        // bỏ mực trong khung các từ (nới một điểm ảnh phân tích) và quanh đường kẻ
        let kd = Double(k)
        for wd in ws {
            if wd.drop { continue }
            let x0 = max(0, JavaNum.toInt((JavaNum.min(wd.ox0, wd.x0) / kd).rounded(.down)) - 1)
            let y0 = max(0, JavaNum.toInt((wd.oy0 / kd).rounded(.down)) - 1)
            let x1 = min(aw, JavaNum.toInt((JavaNum.max(wd.ox1, wd.x1) / kd).rounded(.up)) + 1)
            let y1 = min(ah, JavaNum.toInt((wd.oy1 / kd).rounded(.up)) + 1)
            var y = y0
            while y < y1 {
                var x = x0
                while x < x1 {
                    m[y * aw + x] = 0
                    x += 1
                }
                y += 1
            }
        }
        for y in 0..<ah {
            for x in 0..<aw {
                let i = y * aw + x
                if m[i] == 0 { continue }
                // sát đường kẻ đã nhận (viền nhoè của đường kẻ)
                var nearRule = false
                for dy in -1...1 where !nearRule {
                    let yy = y + dy
                    if yy < 0 || yy >= ah { continue }
                    for dx in -1...1 {
                        let xx = x + dx
                        if xx < 0 || xx >= aw { continue }
                        let j = yy * aw + xx
                        let a = hId[j], b = vId[j]
                        if (a > 0 && hKeep[a]) || (b > 0 && vKeep[b]) {
                            nearRule = true
                            break
                        }
                    }
                }
                if nearRule { m[i] = 0 }
            }
        }
        let ink = m
        guard let bb = Retouch.bounds(m, aw, ah) else { return }
        let hcA = s.hc / kd
        let d = max(1, JavaNum.wrap32(JavaNum.round(hcA * 0.6)))
        Retouch.dilate(&m, aw, ah, d, bb)
        var lab = [UInt8](repeating: 0, count: nA)
        var queue = [Int](repeating: 0, count: nA)
        var boxes = [PBox]()
        let edge = max(2, JavaNum.wrap32(JavaNum.round(0.012 * Double(max(aw, ah)))))
        for start in 0..<nA {
            if m[start] == 0 || lab[start] != 0 { continue }
            var head = 0, tail = 0
            queue[tail] = start
            tail += 1
            lab[start] = 1
            var minX = aw, maxX = -1, minY = ah, maxY = -1, cnt = 0
            while head < tail {
                let i = queue[head]
                head += 1
                let x = i % aw, y = i / aw
                if ink[i] != 0 {
                    cnt += 1
                    if x < minX { minX = x }
                    if x > maxX { maxX = x }
                    if y < minY { minY = y }
                    if y > maxY { maxY = y }
                }
                if x > 0 && m[i - 1] != 0 && lab[i - 1] == 0 {
                    lab[i - 1] = 1
                    queue[tail] = i - 1
                    tail += 1
                }
                if x + 1 < aw && m[i + 1] != 0 && lab[i + 1] == 0 {
                    lab[i + 1] = 1
                    queue[tail] = i + 1
                    tail += 1
                }
                if y > 0 && m[i - aw] != 0 && lab[i - aw] == 0 {
                    lab[i - aw] = 1
                    queue[tail] = i - aw
                    tail += 1
                }
                if y + 1 < ah && m[i + aw] != 0 && lab[i + aw] == 0 {
                    lab[i + aw] = 1
                    queue[tail] = i + aw
                    tail += 1
                }
            }
            if maxX < 0 { continue }
            let bw = maxX - minX + 1, bh = maxY - minY + 1
            // quá nhỏ (đốm bẩn, dấu chấm / dấu thanh sót) thì bỏ; từ cỡ một ký tự trở lên mà bộ nhận dạng bỏ sót thì giữ
            // (kể cả ký tự hẹp như số 1: cao cỡ một chữ, đủ mực) dưới dạng hình để không mất nội dung
            if Double(max(bw, bh)) < 0.9 * hcA || cnt < 8 { continue }
            let tallGlyph = Double(bh) >= 0.9 * hcA && Double(bh) <= 2.5 * hcA && Double(bw) >= JavaNum.max(2.0, 0.18 * hcA)
                && Double(cnt) >= 0.12 * hcA * hcA
            if !tallGlyph && (Double(min(bw, bh)) < 0.45 * hcA || Double(bw) * Double(bh) < 0.6 * hcA * hcA) { continue }
            // vệt nằm sát mép ảnh: bóng mép giấy / mép bàn lọt vào — dải mỏng hoặc dài chạy dọc mép thì bỏ; hình gọn
            // (dấu tích, con dấu sát lề) thì giữ
            let atV = minX < edge || maxX >= aw - edge, atH = minY < edge || maxY >= ah - edge
            if (atV || atH) && Double(min(bw, bh)) < 1.0 * hcA { continue }
            if atV && bh >= 4 * bw { continue }
            if atH && bw >= 4 * bh { continue }
            boxes.append(PBox(x0: minX, y0: minY, x1: maxX, y1: maxY, cnt: cnt))
        }
        // nhiều quá (trang nhiễu): chỉ giữ các vùng lớn nhất
        JavaNum.stableSort(&boxes) { a, b in
            let sa = (a.x1 - a.x0 + 1) * (a.y1 - a.y0 + 1), sb = (b.x1 - b.x0 + 1) * (b.y1 - b.y0 + 1)
            if sa != sb { return sa > sb ? -1 : 1 }
            if a.y0 != b.y0 { return a.y0 < b.y0 ? -1 : 1 }
            return a.x0 < b.x0 ? -1 : (a.x0 > b.x0 ? 1 : 0)
        }
        if boxes.count > maxPictures { boxes.removeLast(boxes.count - maxPictures) }
        JavaNum.stableSort(&boxes) { a, b in
            if a.y0 != b.y0 { return a.y0 < b.y0 ? -1 : 1 }
            return a.x0 < b.x0 ? -1 : (a.x0 > b.x0 ? 1 : 0)
        }
        for b in boxes {
            let p = WordDoc.Pic()
            p.px0 = max(0, (b.x0 - 1) * k)
            p.py0 = max(0, (b.y0 - 1) * k)
            p.px1 = min(s.w, (b.x1 + 2) * k)
            p.py1 = min(s.h, (b.y1 + 2) * k)
            // vị trí trên trang: tâm vùng cắt sau khi khử nghiêng
            let mx = Double(p.px0 + p.px1) / 2.0, my = Double(p.py0 + p.py1) / 2.0
            let nx = s.rx(mx, my), ny = s.ry(mx, my)
            let pw = Double(p.px1 - p.px0) * g.scale, ph = Double(p.py1 - p.py0) * g.scale
            p.x = g.ox + nx * g.scale - pw / 2.0
            p.y = g.oy + ny * g.scale - ph / 2.0
            p.w = pw
            p.h = ph
            page.pics.append(p)
        }
    }

    /// Khung một vùng mực (điểm ảnh phân tích, hai đầu đều tính) + số điểm mực.
    private struct PBox {
        var x0: Int, y0: Int, x1: Int, y1: Int, cnt: Int
    }
}
