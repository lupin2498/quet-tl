import Foundation

/// Ghi file Word (.docx) từ các trang đã dàn (WordDoc.Page), giữ nguyên bố cục trang quét:
/// - mỗi trang quét là một section cùng khổ giấy; mỗi dòng chữ là một đoạn có chiều cao dòng CHÍNH XÁC, đặt bằng khoảng
///   cách trước đoạn + thụt lề (hộp dòng cao 1,125 cỡ chữ thì đường chân chữ nằm ở 80% hộp trong cả Word lẫn LibreOffice);
/// - bảng kẻ là bảng Word thật: bề rộng cột cố định, chiều cao hàng tối thiểu bằng khoảng trống giữa hai đường viền
///   (Word cộng thêm bề dày viền trên vào hàng "atLeast"), ô gộp ngang (gridSpan) / dọc (vMerge), viền từng cạnh;
/// - hình, đường kẻ rời, dòng chữ không xếp được vào dòng chảy: neo theo trang (wp:anchor).
/// Thứ tự phần tử con trong pPr / rPr / tblPr / tcPr / sectPr / wp:anchor theo đúng lược đồ OOXML (Word từ chối hoặc
/// "sửa" file sai thứ tự). XML viết tay; zip tự ghi (CRC-32 tính bằng bảng), nén DEFLATE do lớp ứng dụng truyền vào
/// (framework Compression không có trên Linux) — không có thì các mục được lưu thẳng ("stored").
///
/// Port 1-1 của core/DocxWriter.java (chỉ dùng Foundation): nội dung mọi phần XML trùng từng ký tự với bản Java (CI
/// đối chiếu). Số nguyên dài (twip, EMU) là Int 64 bit, cộng trừ quay vòng (&+ &-) như long của Java; ép số thực ->
/// số nguyên và Math.min / max theo quy tắc Java (JavaNum).
enum DocxWriter {
    static let family = "Times New Roman"
    static let ns = """
        xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" \
        xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" \
        xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing" \
        xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" \
        xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture" \
        xmlns:mc="http://schemas.openxmlformats.org/markup-compatibility/2006" \
        xmlns:wps="http://schemas.microsoft.com/office/word/2010/wordprocessingShape" \
        xmlns:v="urn:schemas-microsoft-com:vml" \
        xmlns:o="urn:schemas-microsoft-com:office:office" \
        xmlns:w10="urn:schemas-microsoft-com:office:word"
        """

    /// Sai số (pt) khi coi hai mép chữ là "cùng một lề" — trang quét xê dịch nhiều hơn PDF gốc.
    static let edgeTol = 2.5
    /// Hai giá trị lề cách nhau không quá mức này thuộc cùng một nhóm.
    static let clusterTol = 2.0

    /// Làm tròn nửa lên: (long) Math.floor(v + 0.5) của bản Java.
    @inline(__always) static func rnd(_ v: Double) -> Int {
        return JavaNum.toLong((v + 0.5).rounded(.down))
    }

    /// point -> twip (1/20 pt).
    @inline(__always) static func tw(_ pt: Double) -> Int {
        return rnd(pt * 20.0)
    }

    /// point -> EMU.
    @inline(__always) static func emu(_ pt: Double) -> Int {
        return rnd(pt * 12700.0)
    }

    /// Thoát ký tự XML; bỏ ký tự điều khiển không hợp lệ trong XML 1.0. Duyệt theo mã Unicode (unicodeScalars) như
    /// codePointAt của Java; chuỗi Swift không chứa nửa cặp thay thế lẻ nên không cần lọc D800..DFFF.
    static func esc(_ s: String) -> String {
        var out = String.UnicodeScalarView()
        for u in s.unicodeScalars {
            let cp = u.value
            if cp == 0x26 {
                out.append(contentsOf: "&amp;".unicodeScalars)
            } else if cp == 0x3C {
                out.append(contentsOf: "&lt;".unicodeScalars)
            } else if cp == 0x3E {
                out.append(contentsOf: "&gt;".unicodeScalars)
            } else if cp == 0x22 {
                out.append(contentsOf: "&quot;".unicodeScalars)
            } else if cp < 0x20 && cp != 0x09 {
                continue
            } else if cp == 0xFFFE || cp == 0xFFFF || (cp >= 0xD800 && cp <= 0xDFFF) {
                continue
            } else {
                out.append(u)
            }
        }
        return String(out)
    }

    static func hex6(_ color: Int) -> String {
        var h = String(color & 0xFFFFFF, radix: 16, uppercase: true)
        while h.utf8.count < 6 { h = "0" + h }
        return h
    }

    // MARK: chữ và đoạn

    /// Thuộc tính ký tự của một mẩu chữ (rỗng nếu trùng mặc định). defSz: cỡ chữ mặc định (nửa point).
    static func rprXml(_ bold: Bool, _ italic: Bool, _ color: Int, _ spacing: Double, _ size: Double, _ underline: Bool,
                       _ defSz: Int) -> String {
        var sb = ""
        if bold { sb += "<w:b/><w:bCs/>" }
        if italic { sb += "<w:i/><w:iCs/>" }
        if (color & 0xFFFFFF) != 0 { sb += "<w:color w:val=\"\(hex6(color))\"/>" }
        let sp = tw(spacing)
        if sp != 0 { sb += "<w:spacing w:val=\"\(sp)\"/>" }
        let sz = max(2, rnd(size * 2.0))
        if sz != defSz { sb += "<w:sz w:val=\"\(sz)\"/><w:szCs w:val=\"\(sz)\"/>" }
        if underline { sb += "<w:u w:val=\"single\"/>" }
        return sb
    }

    /// Ngữ cảnh đặt đoạn: gốc ngang của vùng chứa, vị trí dọc đã dùng (twip), hai mép vùng chữ.
    final class Ctx {
        var originX = 0.0, areaLeft = 0.0, areaRight = 0.0
        var yTw = 0
        var clampIndent = false
        var defSz = 0

        func copy() -> Ctx {
            let c = Ctx()
            c.originX = originX
            c.areaLeft = areaLeft
            c.areaRight = areaRight
            c.yTw = yTw
            c.clampIndent = clampIndent
            c.defSz = defSz
            return c
        }
    }

    /// Đoạn Word cho một dòng chữ. after: khoảng cách sau đoạn (twip); sectPr: kết thúc section tại đoạn này (hoặc "").
    static func linePara(_ p: WordDoc.Para, _ ctx: Ctx, _ after: Int, _ sectPr: String) -> String {
        let topTw = tw(p.top)
        let before = max(0, topTw &- ctx.yTw)
        let lineTw = max(10, tw(p.height))
        ctx.yTw = ctx.yTw &+ (before &+ lineTw)
        var ppr = ""
        // tab: vị trí tính từ mép vùng chứa, không trùng, tăng dần
        var tabs = [Int]()
        for t in p.toks {
            if t.t != WordDoc.tTab { continue }
            let v = tw(t.pos - ctx.originX)
            if v <= 0 || tabs.contains(v) { continue }
            var at = 0
            while at < tabs.count && tabs[at] < v { at += 1 }
            tabs.insert(v, at: at)
        }
        if !tabs.isEmpty {
            ppr += "<w:tabs>"
            for v in tabs { ppr += "<w:tab w:val=\"left\" w:pos=\"\(v)\"/>" }
            ppr += "</w:tabs>"
        }
        ppr += "<w:spacing w:before=\"\(before)\" w:after=\"\(after)\" w:line=\"\(lineTw)\" w:lineRule=\"exact\"/>"
        // Phông trên máy khác có thể làm dòng rộng hơn một chút: cho đoạn lấn ra ngoài vùng chữ `need` về phía chữ dài ra
        // để dòng không bao giờ bị ngắt xuống hàng.
        let width = p.x1 - p.x
        let need = JavaNum.max(9.0, 0.15 * width)
        var left = 0.0, right = 0.0
        var jc = ""
        if p.align == WordDoc.alignCenter {
            // căn giữa quanh đúng tâm của dòng trong vùng chữ
            let off = 2.0 * ((p.x + p.x1) / 2.0) - ctx.areaLeft - ctx.areaRight
            left = off > 0.3 ? off : 0.0
            right = off < -0.3 ? -off : 0.0
            let room = (ctx.areaRight - ctx.areaLeft - left - right - width) / 2.0
            if room < need / 2.0 {
                left -= need / 2.0 - room
                right -= need / 2.0 - room
            }
            jc = "<w:jc w:val=\"center\"/>"
        } else if p.align == WordDoc.alignRight {
            right = JavaNum.max(0.0, ctx.areaRight - p.x1)
            if right < 0.05 { right = 0.0 }
            let room = p.x - ctx.areaLeft
            left = room < need ? room - need : 0.0
            jc = "<w:jc w:val=\"right\"/>"
        } else {
            left = p.x - ctx.originX
            if ctx.clampIndent && left < 0 { left = 0.0 }
            let room = ctx.areaRight - p.x1
            right = room < need ? room - need : 0.0
        }
        let lt = tw(left), rt = tw(right)
        if lt != 0 || rt != 0 {
            ppr += "<w:ind w:left=\"\(lt)\""
            if rt != 0 { ppr += " w:right=\"\(rt)\"" }
            ppr += "/>"
        }
        ppr += jc
        let mark = rprXml(p.bold, p.italic, p.color, 0.0, p.size, false, ctx.defSz)
        if !mark.isEmpty {
            ppr += "<w:rPr>"
            ppr += mark
            ppr += "</w:rPr>"
        }
        ppr += sectPr

        // các mẩu chữ: gộp mẩu liền nhau cùng thuộc tính
        var body = ""
        var curRpr: String? = nil
        var curText = ""
        for t in p.toks {
            if t.t == WordDoc.tTab {
                flushRun(&body, curRpr, &curText)
                curRpr = nil
                body += "<w:r><w:tab/></w:r>"
                continue
            }
            let rpr = rprXml(t.bold, t.italic, t.color, t.spacing, t.size, t.underline, ctx.defSz)
            let text: String = t.t == WordDoc.tSpace ? String(repeating: " ", count: max(1, t.n)) : t.text
            if let cur = curRpr, cur == rpr {
                curText.unicodeScalars.append(contentsOf: text.unicodeScalars)
            } else {
                flushRun(&body, curRpr, &curText)
                curRpr = rpr
                curText.unicodeScalars.append(contentsOf: text.unicodeScalars)
            }
        }
        flushRun(&body, curRpr, &curText)
        var out = "<w:p><w:pPr>"
        out += ppr
        out += "</w:pPr>"
        out += body
        out += "</w:p>"
        return out
    }

    private static func flushRun(_ body: inout String, _ rpr: String?, _ text: inout String) {
        guard let rpr = rpr else { return }
        body += "<w:r>"
        if !rpr.isEmpty {
            body += "<w:rPr>"
            body += rpr
            body += "</w:rPr>"
        }
        body += "<w:t xml:space=\"preserve\">"
        body.unicodeScalars.append(contentsOf: esc(text).unicodeScalars)
        body += "</w:t></w:r>"
        text = ""
    }

    /// Đoạn trống có chiều cao chính xác (cỡ chữ 1 pt). content: các hình neo đặt trong đoạn.
    static func spacerPara(_ heightTw: Int, _ after: Int, _ sectPr: String, _ content: String) -> String {
        let line = max(1, heightTw)
        var out = "<w:p><w:pPr><w:spacing w:before=\"0\" w:after=\"\(after)\" w:line=\"\(line)\""
        out += " w:lineRule=\"exact\"/><w:rPr><w:sz w:val=\"2\"/><w:szCs w:val=\"2\"/></w:rPr>"
        out += sectPr
        out += "</w:pPr>"
        out += content
        out += "</w:p>"
        return out
    }

    // MARK: bảng

    /// Bề dày viền theo đơn vị của Word (1/8 pt): 0 nếu không kẻ.
    static func borderSz(_ t: Double) -> Int {
        if !(t > 0) { return 0 }
        return max(2, min(96, rnd(t * 8.0)))
    }

    static func borderXml(_ tag: String, _ t: Double) -> String {
        let sz = borderSz(t)
        if sz == 0 { return "" }
        return "<w:\(tag) w:val=\"single\" w:sz=\"\(sz)\" w:space=\"0\" w:color=\"000000\"/>"
    }

    static func tableXml(_ block: WordDoc.Block, _ ctx: Ctx) -> String {
        guard let t = block.table else { return "" }
        let cols = t.cols, rows = t.rows
        // bảng hỏng (mảng không khớp số cột / hàng): bản Java ném ngoại lệ; ở đây bỏ qua bảng thay vì dừng chương trình
        if cols < 0 || rows < 0 || t.xs.count < cols + 1 || t.ys.count < rows + 1 || t.owner.count < cols * rows { return "" }
        for c in t.cells where c.r0 < 0 || c.r1 >= rows || c.c0 < 0 || c.c1 >= cols { return "" }
        var xTw = [Int](repeating: 0, count: cols + 1)
        for i in 0...cols { xTw[i] = tw(t.xs[i]) }
        var colW = [Int](repeating: 0, count: cols)
        var total = 0
        for i in 0..<cols {
            colW[i] = max(1, xTw[i + 1] &- xTw[i])
            total = total &+ colW[i]
        }
        var out = "<w:tbl><w:tblPr>"
        out += "<w:tblW w:w=\"\(total)\" w:type=\"dxa\"/>"
        out += "<w:tblInd w:w=\"\(xTw[0] &- tw(ctx.areaLeft))\" w:type=\"dxa\"/>"
        out += "<w:tblLayout w:type=\"fixed\"/>"
        let marL = tw(t.marL), marR = tw(t.marR)
        out += "<w:tblCellMar><w:top w:w=\"0\" w:type=\"dxa\"/><w:left w:w=\"\(marL)\""
        out += " w:type=\"dxa\"/><w:bottom w:w=\"0\" w:type=\"dxa\"/><w:right w:w=\"\(marR)\""
        out += " w:type=\"dxa\"/></w:tblCellMar>"
        out += "<w:tblLook w:val=\"0000\" w:firstRow=\"0\" w:lastRow=\"0\" w:firstColumn=\"0\" w:lastColumn=\"0\" w:noHBand=\"1\" w:noVBand=\"1\"/>"
        out += "</w:tblPr><w:tblGrid>"
        for i in 0..<cols { out += "<w:gridCol w:w=\"\(colW[i])\"/>" }
        out += "</w:tblGrid>"
        // Chiều cao hàng "tối thiểu" (atLeast): nội dung đã đặt vừa trong ô nên hàng giữ đúng chiều cao, nhưng người dùng
        // gõ thêm chữ thì hàng tự giãn (chiều cao cố định "exact" sẽ cắt mất chữ). Với atLeast, Word hiểu chiều cao hàng
        // là khoảng trống GIỮA hai đường viền — viền trên của hàng cộng thêm bề dày của nó (khác "exact": viền vẽ lấn vào
        // trong hàng) — nên phải trừ bề dày viền, nếu không mỗi hàng cao thêm một bề dày viền và cả bảng dài dần ra.
        // bw: bề dày viền (twip) trên từng đường lưới ngang, đúng như sẽ ghi ở các ô; rowTop: mép trên của viền từng hàng.
        var bw = [Double](repeating: 0, count: rows + 1)
        for c in t.cells {
            bw[c.r0] = JavaNum.max(bw[c.r0], Double(borderSz(c.bTop)) * 2.5)
            bw[c.r1 + 1] = JavaNum.max(bw[c.r1 + 1], Double(borderSz(c.bBottom)) * 2.5)
        }
        var rowHs = [Int](repeating: 0, count: rows)
        var rowTop = [Double](repeating: 0, count: rows)
        if rows > 0 {
            var cur = t.ys[0] * 20.0 - bw[0] / 2.0
            for j in 0..<rows {
                let nextTop = t.ys[j + 1] * 20.0 - bw[j + 1] / 2.0
                rowTop[j] = cur
                rowHs[j] = max(1, rnd(nextTop - cur - bw[j]))
                cur += bw[j] + Double(rowHs[j])
            }
        }
        for j in 0..<rows {
            let rowH = rowHs[j]
            out += "<w:tr><w:trPr><w:cantSplit/><w:trHeight w:val=\"\(rowH)\" w:hRule=\"atLeast\"/></w:trPr>"
            // các ô có mặt ở hàng j, theo thứ tự cột
            var prev = -1
            for i in 0..<cols {
                let ci = t.owner[j * cols + i]
                if ci == prev { continue }
                prev = ci
                let c = t.cells[ci]
                if c.c0 != i { continue }
                var w = 0
                var k = c.c0
                while k <= c.c1 {
                    w = w &+ colW[k]
                    k += 1
                }
                let span = c.c1 - c.c0 + 1
                let first = j == c.r0, last = j == c.r1
                out += "<w:tc><w:tcPr><w:tcW w:w=\"\(w)\" w:type=\"dxa\"/>"
                if span > 1 { out += "<w:gridSpan w:val=\"\(span)\"/>" }
                if c.r1 > c.r0 { out += first ? "<w:vMerge w:val=\"restart\"/>" : "<w:vMerge/>" }
                var borders = borderXml("top", first ? c.bTop : 0)
                borders += borderXml("left", c.bLeft)
                borders += borderXml("bottom", last ? c.bBottom : 0)
                borders += borderXml("right", c.bRight)
                if !borders.isEmpty {
                    out += "<w:tcBorders>"
                    out += borders
                    out += "</w:tcBorders>"
                }
                let cl = tw(c.marL), cr = tw(c.marR)
                if cl != marL || cr != marR {
                    out += "<w:tcMar><w:left w:w=\"\(cl)\" w:type=\"dxa\"/><w:right w:w=\"\(cr)\" w:type=\"dxa\"/></w:tcMar>"
                }
                out += "</w:tcPr>"
                if first && !c.paras.isEmpty {
                    let cc = Ctx()
                    cc.originX = c.x0 + c.marL
                    // nội dung ô bắt đầu ngay dưới viền trên của hàng
                    cc.yTw = rnd(rowTop[c.r0] + bw[c.r0])
                    cc.areaLeft = c.x0 + c.marL
                    cc.areaRight = c.x1 - c.marR
                    cc.clampIndent = true
                    cc.defSz = ctx.defSz
                    for p in c.paras { out += linePara(p, cc, 0, "") }
                } else {
                    // ô trống: giữ cỡ chữ mặc định để gõ chữ vào trông đúng (hộp dòng không cao quá hàng)
                    let line = max(10, min(rnd(Double(ctx.defSz) * 11.5), rowH &- 10))
                    out += "<w:p><w:pPr><w:spacing w:before=\"0\" w:after=\"0\" w:line=\"\(line)\" w:lineRule=\"exact\"/></w:pPr></w:p>"
                }
                out += "</w:tc>"
            }
            out += "</w:tr>"
        }
        out += "</w:tbl>"
        return out
    }

    // MARK: hình neo theo trang

    static func anchorOpen(_ x: Double, _ y: Double, _ w: Double, _ h: Double, _ behind: Bool, _ z: Int) -> String {
        var s = "<wp:anchor distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\" simplePos=\"0\" relativeHeight=\"\(251658240 &+ z)\""
        s += " behindDoc=\"\(behind ? 1 : 0)\" locked=\"0\" layoutInCell=\"0\" allowOverlap=\"1\">"
        s += "<wp:simplePos x=\"0\" y=\"0\"/>"
        s += "<wp:positionH relativeFrom=\"page\"><wp:posOffset>\(emu(x))</wp:posOffset></wp:positionH>"
        s += "<wp:positionV relativeFrom=\"page\"><wp:posOffset>\(emu(y))</wp:posOffset></wp:positionV>"
        s += "<wp:extent cx=\"\(max(0, emu(w)))\" cy=\"\(max(0, emu(h)))\"/>"
        s += "<wp:effectExtent l=\"0\" t=\"0\" r=\"0\" b=\"0\"/><wp:wrapNone/>"
        return s
    }

    static func pictureXml(_ f: WordDoc.Pic, _ id: Int, _ rid: String, _ z: Int) -> String {
        let cx = max(1, emu(f.w)), cy = max(1, emu(f.h))
        var s = "<w:r><w:drawing>"
        s += anchorOpen(f.x, f.y, f.w, f.h, f.behind, z)
        s += "<wp:docPr id=\"\(id)\" name=\"Picture \(id)\"/>"
        s += "<wp:cNvGraphicFramePr><a:graphicFrameLocks noChangeAspect=\"1\"/></wp:cNvGraphicFramePr>"
        s += "<a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"><pic:pic>"
        s += "<pic:nvPicPr><pic:cNvPr id=\"\(id)\" name=\"Picture \(id)\"/><pic:cNvPicPr/></pic:nvPicPr>"
        s += "<pic:blipFill><a:blip r:embed=\"\(rid)\"></a:blip><a:stretch><a:fillRect/></a:stretch></pic:blipFill>"
        s += "<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"\(cx)\" cy=\"\(cy)\"/></a:xfrm>"
        s += "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr>"
        s += "</pic:pic></a:graphicData></a:graphic></wp:anchor></w:drawing></w:r>"
        return s
    }

    /// Khung chung của một hình vẽ (wps). connector: đường nối thẳng; txbx: nội dung hộp chữ; fallback: bản VML dự phòng.
    static func shapeWrap(_ x: Double, _ y: Double, _ w: Double, _ h: Double, _ id: Int, _ behind: Bool, _ z: Int, _ geom: String,
                          _ fill: String, _ ln: String, _ body: String, _ txbx: String, _ fallback: String, _ connector: Bool) -> String {
        let cx = max(0, emu(w)), cy = max(0, emu(h))
        var s = "<w:r><mc:AlternateContent><mc:Choice Requires=\"wps\"><w:drawing>"
        s += anchorOpen(x, y, w, h, behind, z)
        s += "<wp:docPr id=\"\(id)\" name=\"Shape \(id)\"/><wp:cNvGraphicFramePr/>"
        s += "<a:graphic><a:graphicData uri=\"http://schemas.microsoft.com/office/word/2010/wordprocessingShape\"><wps:wsp>"
        if connector {
            s += "<wps:cNvCnPr/>"
        } else {
            s += "<wps:cNvSpPr"
            if !txbx.isEmpty { s += " txBox=\"1\"" }
            s += "/>"
        }
        s += "<wps:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"\(cx)\" cy=\"\(cy)\"/></a:xfrm>"
        s += geom
        s += fill
        s += ln
        s += "</wps:spPr>"
        s += txbx
        s += body
        s += "</wps:wsp></a:graphicData></a:graphic></wp:anchor></w:drawing></mc:Choice>"
        s += "<mc:Fallback>"
        s += fallback
        s += "</mc:Fallback></mc:AlternateContent></w:r>"
        return s
    }

    /// Đường kẻ rời: đường nối thẳng (như Word tự ghi; LibreOffice vẽ sai hình "line" dọc cao gần bằng trang).
    static func lineShapeXml(_ f: WordDoc.Rule, _ id: Int, _ z: Int) -> String {
        let x = f.horiz ? f.a : f.pos, y = f.horiz ? f.pos : f.a
        let w = f.horiz ? f.b - f.a : 0.0, h = f.horiz ? 0.0 : f.b - f.a
        let ln = "<a:ln w=\"\(max(3175, emu(f.t)))\" cap=\"flat\"><a:solidFill><a:srgbClr val=\"000000\"></a:srgbClr></a:solidFill></a:ln>"
        return shapeWrap(x, y, w, h, id, false, z, "<a:prstGeom prst=\"straightConnector1\"><a:avLst/></a:prstGeom>", "", ln,
                         "<wps:bodyPr/>", "", "", true)
    }

    static func fixed2(_ v: Double) -> String {
        // số thập phân 2 chữ số, không phụ thuộc ngôn ngữ máy
        var c = rnd(v * 100.0)
        let neg = c < 0
        if neg { c = 0 &- c }
        var frac = String(c % 100)
        if frac.utf8.count < 2 { frac = "0" + frac }
        return (neg ? "-" : "") + String(c / 100) + "." + frac
    }

    /// Hộp chữ một dòng, không viền, không lề trong, không tự xuống dòng.
    static func textBoxXml(_ f: WordDoc.Box, _ id: Int, _ z: Int, _ defSz: Int) -> String {
        // dài hơn một chút để dòng không bị ngắt khi phông khác đi
        let slack = JavaNum.max(4.0, f.w * 0.04)
        let len = f.w + slack
        let ctx = Ctx()
        ctx.originX = f.x
        ctx.yTw = tw(f.y)
        ctx.areaLeft = f.x
        ctx.areaRight = f.x + len
        ctx.clampIndent = true
        ctx.defSz = defSz
        let keep = f.para.align
        f.para.align = WordDoc.alignLeft
        let content = linePara(f.para, ctx, 0, "")
        f.para.align = keep
        var txbx = "<wps:txbx><w:txbxContent>"
        txbx += content
        txbx += "</w:txbxContent></wps:txbx>"
        let body = "<wps:bodyPr rot=\"0\" vert=\"horz\" wrap=\"none\" lIns=\"0\" tIns=\"0\" rIns=\"0\" bIns=\"0\" anchor=\"t\" anchorCtr=\"0\"><a:noAutofit/></wps:bodyPr>"
        var style = "position:absolute;margin-left:\(fixed2(f.x))pt;margin-top:\(fixed2(f.y))pt;width:\(fixed2(len))"
        style += "pt;height:\(fixed2(JavaNum.max(1.0, f.h)))pt;z-index:\(251658240 &+ z)"
        style += ";mso-wrap-style:none;mso-position-horizontal-relative:page;mso-position-vertical-relative:page"
        var fallback = "<w:pict><v:rect id=\"tb\(id)\" style=\"\(style)\" filled=\"f\" stroked=\"f\"><v:textbox inset=\"0,0,0,0\"><w:txbxContent>"
        fallback += content
        fallback += "</w:txbxContent></v:textbox><w10:wrap anchorx=\"page\" anchory=\"page\"/></v:rect></w:pict>"
        return shapeWrap(f.x, f.y, len, f.h, id, false, z, "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom>", "<a:noFill/>",
                         "<a:ln><a:noFill/></a:ln>", body, txbx, fallback, false)
    }

    /// Bảng không vào dòng chảy được (đứng cạnh bảng khác): đặt trong hộp chữ đúng chỗ trên trang.
    static func tableBoxXml(_ block: WordDoc.Block, _ id: Int, _ z: Int, _ defSz: Int) -> String {
        guard let t = block.table, t.cols >= 0, t.vT.count > 0, t.xs.count > t.cols else { return "" }
        let pad = 2.0 + t.vT[0] / 2.0
        let x = t.xs[0] - pad, y = block.top
        let w = t.xs[t.cols] - t.xs[0] + 2.0 * pad + 2.0, h = block.bottom - block.top + 3.0
        let ctx = Ctx()
        ctx.originX = x
        ctx.yTw = tw(y)
        ctx.areaLeft = x
        ctx.areaRight = x + w
        ctx.defSz = defSz
        var content = tableXml(block, ctx)
        content += spacerPara(20, 0, "", "")
        var txbx = "<wps:txbx><w:txbxContent>"
        txbx += content
        txbx += "</w:txbxContent></wps:txbx>"
        let body = "<wps:bodyPr rot=\"0\" vert=\"horz\" wrap=\"square\" lIns=\"0\" tIns=\"0\" rIns=\"0\" bIns=\"0\" anchor=\"t\" anchorCtr=\"0\"><a:noAutofit/></wps:bodyPr>"
        return shapeWrap(x, y, w, h, id, false, z, "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom>", "<a:noFill/>",
                         "<a:ln><a:noFill/></a:ln>", body, txbx, "", false)
    }

    // MARK: lề trang

    /// Căn lề của một dòng trong vùng chữ [left, right]: giữa khi tâm dòng trùng tâm vùng, phải khi dòng kết thúc ở mép
    /// phải — cả hai chỉ khi dòng bắt đầu lùi hẳn vào trong (dòng đầy / căn đều hai lề vẫn là căn trái).
    static func alignmentOf(_ p: WordDoc.Para, _ left: Double, _ right: Double) -> Int {
        if p.hasTab { return WordDoc.alignLeft }
        let width = right - left
        if !(width > 0) || p.x - left <= JavaNum.max(3.0, width * 0.1) { return WordDoc.alignLeft }
        let tol = JavaNum.max(2.0, width * 0.012)
        if abs((p.x + p.x1) / 2.0 - (left + right) / 2.0) <= tol { return WordDoc.alignCenter }
        if abs(right - p.x1) <= edgeTol { return WordDoc.alignRight }
        return WordDoc.alignLeft
    }

    static func leftEdge(_ b: WordDoc.Block) -> Double {
        if let t = b.table { return b.x0 + t.marL }
        return b.x0
    }

    static func rightEdge(_ b: WordDoc.Block) -> Double {
        if let t = b.table { return b.x1 - t.marR }
        return b.x1
    }

    /// Mép ngoài cùng được một bảng hoặc ít nhất ba dòng dùng chung (NaN nếu không có): nhãn lẻ thò ra không tính.
    static func definedEdge(_ flow: [WordDoc.Block], _ leftSide: Bool) -> Double {
        let n = flow.count
        var vals = [Double](repeating: 0, count: n)
        for i in 0..<n { vals[i] = leftSide ? leftEdge(flow[i]) : rightEdge(flow[i]) }
        JavaNum.sort(&vals)
        for k in 0..<n {
            let at = leftSide ? vals[k] : vals[n - 1 - k]
            var near = 0
            var table = false
            for b in flow {
                let e = leftSide ? leftEdge(b) : rightEdge(b)
                if abs(e - at) <= edgeTol {
                    near += 1
                    if b.table != nil { table = true }
                }
            }
            if near >= 3 || table { return at }
        }
        return Double.nan
    }

    /// Nhóm các giá trị gần nhau; mỗi nhóm đại diện bằng giá trị hay gặp nhất (làm tròn 0,05).
    final class Clusters {
        /// Mỗi nhóm: giá trị nhỏ nhất, lớn nhất, đại diện, số phần tử.
        var groups = [(lo: Double, last: Double, rep: Double, count: Double)]()
        var common = Double.nan

        init(_ values: [Double], _ pickMin: Bool) {
            let n = values.count
            var v = values
            JavaNum.sort(&v)
            var s = 0
            while s < n {
                var e = s
                while e + 1 < n && v[e + 1] - v[e] <= DocxWriter.clusterTol { e += 1 }
                // giá trị hay gặp nhất trong nhóm (bằng nhau thì lấy nhỏ nhất / lớn nhất theo pickMin)
                var rep = 0.0
                var best = 0
                for i in s...e {
                    let k = Double(DocxWriter.rnd(v[i] * 20.0)) / 20.0
                    var c = 0
                    for j in s...e where DocxWriter.rnd(v[j] * 20.0) == DocxWriter.rnd(v[i] * 20.0) { c += 1 }
                    if c > best || (c == best && (pickMin ? k < rep : k > rep)) {
                        best = c
                        rep = k
                    }
                }
                groups.append((v[s], v[e], rep, Double(e - s + 1)))
                s = e + 1
            }
            var top: (lo: Double, last: Double, rep: Double, count: Double)? = nil
            for g in groups {
                if let t = top {
                    if g.count > t.count || (g.count == t.count && (pickMin ? g.rep < t.rep : g.rep > t.rep)) { top = g }
                } else {
                    top = g
                }
            }
            if let t = top { common = t.rep }
        }

        func snap(_ x: Double) -> Double {
            for g in groups where x >= g.lo - 1e-6 && x <= g.last + 1e-6 { return g.rep }
            return x
        }
    }

    /// Lề trang, sát với lề của văn bản gốc: vùng chữ của một trang xác định bởi bảng và mép mà nhiều dòng dùng chung; các
    /// trang cùng khổ dùng chung lề (trang chỉ có tiêu đề / hình lấy theo đa số). Lề trên / dưới là nhỏ nhất cần có.
    static func planMargins(_ pages: [WordDoc.Page]) {
        var keys = [String]()
        var lists = [[WordDoc.Page]]()
        for page in pages {
            var flow = [WordDoc.Block]()
            for b in page.blocks where !b.overlaps { flow.append(b) }
            page.flow = !flow.isEmpty
            page.left = 36
            page.right = 36
            page.top = 36
            page.bottom = 36
            page.extraHeight = 0
            if page.flow {
                let ownL = definedEdge(flow, true), ownR = definedEdge(flow, false)
                page.ownLeft = !ownL.isNaN
                page.ownRight = !ownR.isNaN
                var minL = Double.greatestFiniteMagnitude, maxR = -Double.greatestFiniteMagnitude
                var minTop = Double.greatestFiniteMagnitude, maxBottom = -Double.greatestFiniteMagnitude
                for b in flow {
                    minL = JavaNum.min(minL, leftEdge(b))
                    maxR = JavaNum.max(maxR, rightEdge(b))
                    minTop = JavaNum.min(minTop, b.top)
                    maxBottom = JavaNum.max(maxBottom, b.bottom)
                }
                page.minX = JavaNum.max(0.0, JavaNum.min(page.width / 2.0, page.ownLeft ? ownL : minL))
                page.maxX = JavaNum.min(page.width, JavaNum.max(page.minX + 1.0, page.ownRight ? ownR : maxR))
                page.first = JavaNum.max(0.0, minTop - 1.0)
                page.room = JavaNum.max(0.0, page.height - maxBottom)
            }
            let key = "\(rnd(page.width))x\(rnd(page.height))"
            var at = -1
            for (i, k) in keys.enumerated() where k == key {
                at = i
                break
            }
            if at < 0 {
                keys.append(key)
                lists.append([])
                at = keys.count - 1
            }
            lists[at].append(page)
        }
        for list in lists {
            var used = [WordDoc.Page]()
            for pg in list where pg.flow { used.append(pg) }
            if used.isEmpty { continue }
            let width = list[0].width
            var anyLeft = false, anyRight = false
            for pl in used {
                anyLeft = anyLeft || pl.ownLeft
                anyRight = anyRight || pl.ownRight
            }
            var lv = [Double](), rv = [Double]()
            var farLeft = Double.greatestFiniteMagnitude, farRight = -Double.greatestFiniteMagnitude
            var top = Double.greatestFiniteMagnitude, bottom = 43.0
            for pl in used {
                if !anyLeft || pl.ownLeft { lv.append(pl.minX) }
                if !anyRight || pl.ownRight { rv.append(pl.maxX) }
                farLeft = JavaNum.min(farLeft, pl.minX)
                farRight = JavaNum.max(farRight, pl.maxX)
                top = JavaNum.min(top, pl.first)
                // dưới nội dung: đoạn 1 pt kết thúc trang + phần dự trữ để bản dựng cao hơn chút không tràn sang trang mới
                bottom = JavaNum.min(bottom, pl.room - JavaNum.max(6.0, pl.room * 0.3))
            }
            bottom = JavaNum.max(0.0, bottom)
            let lefts = Clusters(lv, true), rights = Clusters(rv, false)
            // mép không có nội dung nào xác định (chỉ có dòng ngắn) thì đối xứng theo mép kia
            if !anyLeft && !anyRight {
                let m = JavaNum.max(0.0, JavaNum.min(farLeft, width - farRight))
                lefts.common = m
                rights.common = width - m
            } else if !anyRight {
                rights.common = JavaNum.max(farRight, width - lefts.common)
            } else if !anyLeft {
                lefts.common = JavaNum.max(0.0, JavaNum.min(farLeft, width - rights.common))
            }
            for pl in list {
                var minX = lefts.common, maxX = rights.common
                if pl.flow {
                    if pl.ownLeft {
                        minX = lefts.snap(pl.minX)
                    } else if pl.minX < minX - 0.5 {
                        minX = pl.minX
                    }
                    if pl.ownRight {
                        maxX = rights.snap(pl.maxX)
                    } else if pl.maxX > maxX + 0.5 {
                        maxX = pl.maxX
                    }
                }
                if maxX - minX < 36.0 {
                    minX = JavaNum.min(minX, pl.flow ? pl.minX : minX)
                    maxX = JavaNum.max(JavaNum.max(maxX, pl.flow ? pl.maxX : maxX), minX + 36.0)
                }
                pl.left = (JavaNum.max(0.0, minX) * 20.0).rounded(.down) / 20.0
                pl.right = JavaNum.max(0.0, ((pl.width - maxX) * 20.0).rounded(.down) / 20.0)
                pl.top = (top * 20.0).rounded(.down) / 20.0
                pl.bottom = (bottom * 20.0).rounded(.down) / 20.0
                // nội dung chạm mép dưới: cho trang cao thêm chút thay vì để đoạn cuối rơi sang trang mới
                let spare = pl.flow ? pl.room - pl.bottom - 1.2 : 1.0
                if spare < 0 { pl.extraHeight = (-spare * 20.0).rounded(.up) / 20.0 }
            }
        }
    }

    // MARK: tài liệu

    private static func countPara(_ p: WordDoc.Para, _ count: inout [(sz: Int, chars: Int)]) {
        let sz = max(2, rnd(p.size * 2.0))
        var chars = 0
        // String.length của Java: số đơn vị UTF-16
        for t in p.toks where t.t == WordDoc.tText { chars += t.text.utf16.count }
        for i in 0..<count.count where count[i].sz == sz {
            count[i].chars += chars
            return
        }
        count.append((sz, chars))
    }

    /// Cỡ chữ chủ đạo của tài liệu (nửa point) theo số ký tự.
    static func dominantSize(_ pages: [WordDoc.Page]) -> Int {
        var count = [(sz: Int, chars: Int)]()
        for page in pages {
            for b in page.blocks {
                if let p = b.para { countPara(p, &count) }
                if let t = b.table {
                    for c in t.cells {
                        for p in c.paras { countPara(p, &count) }
                    }
                }
            }
            for x in page.boxes { countPara(x.para, &count) }
        }
        var best = 24, n = -1
        for e in count {
            if e.chars > n || (e.chars == n && e.sz < best) {
                n = e.chars
                best = e.sz
            }
        }
        return best
    }

    /// Hình có đè lên dòng chữ nào của trang không (để đặt ra sau chữ).
    static func overlapsText(_ f: WordDoc.Pic, _ page: WordDoc.Page) -> Bool {
        for b in page.blocks {
            if let p = b.para, hits(f, p) { return true }
            if let t = b.table {
                for c in t.cells {
                    for p in c.paras where hits(f, p) { return true }
                }
            }
        }
        for x in page.boxes where hits(f, x.para) { return true }
        return false
    }

    private static func hits(_ f: WordDoc.Pic, _ p: WordDoc.Para) -> Bool {
        return p.x1 >= f.x && p.x <= f.x + f.w && p.top + p.height >= f.y && p.top <= f.y + f.h
    }

    /// Các phần XML của tài liệu: document.xml, rels của document, cỡ chữ mặc định. media: hình được nhúng (theo thứ tự rId).
    static func buildXml(_ pages: [WordDoc.Page], _ media: inout [WordDoc.Pic]) -> (doc: String, rels: String, defSz: Int) {
        let defSz = dominantSize(pages)
        planMargins(pages)
        var body = ""
        var nextId = 1
        let np = pages.count
        for pi in 0..<np {
            let page = pages[pi]
            let lastPage = pi == np - 1
            var flow = [WordDoc.Block](), floating = [WordDoc.Block]()
            for b in page.blocks {
                if b.overlaps {
                    floating.append(b)
                } else {
                    flow.append(b)
                }
            }
            let ctx = Ctx()
            ctx.originX = page.left
            ctx.yTw = tw(page.top)
            ctx.areaLeft = page.left
            ctx.areaRight = page.width - page.right
            ctx.defSz = defSz
            for b in flow {
                if let p = b.para { p.align = alignmentOf(p, ctx.areaLeft, ctx.areaRight) }
            }

            // ---- hình neo: đặt trong đoạn đầu tiên của trang
            var drawings = ""
            var z = 0
            for f in page.pics {
                let id = nextId
                nextId += 1
                guard let data = f.data, !data.isEmpty else { continue }
                f.behind = overlapsText(f, page)
                media.append(f)
                drawings += pictureXml(f, id, "rIdImg\(media.count)", z)
                z += 16
            }
            for r in page.rules {
                drawings += lineShapeXml(r, nextId, z)
                nextId += 1
                z += 16
            }
            for x in page.boxes {
                drawings += textBoxXml(x, nextId, z, defSz)
                nextId += 1
                z += 16
            }
            for b in floating {
                drawings += tableBoxXml(b, nextId, 0, defSz)
                nextId += 1
            }

            // ---- dòng chảy: vị trí tính trước, XML sinh sau để còn chỉnh được khoảng cách sau đoạn
            let nItems = flow.count * 2 + 4
            var kind = [Int](repeating: 0, count: nItems)           // 0 đoạn trống, 1 bảng, 2 dòng chữ
            var height = [Int](repeating: 0, count: nItems), after = [Int](repeating: 0, count: nItems)
            var ctxBefore = [Int](repeating: 0, count: nItems)
            var xml = [String](repeating: "", count: nItems)
            var para = [WordDoc.Para?](repeating: nil, count: nItems)
            var ni = 0
            kind[ni] = 0
            height[ni] = 20
            xml[ni] = drawings
            ni += 1
            ctx.yTw = ctx.yTw &+ 20
            var lastPara = 0
            for b in flow {
                if b.table != nil {
                    let gap = tw(b.top) &- ctx.yTw
                    if lastPara >= 0 {
                        if gap > 0 {
                            after[lastPara] = after[lastPara] &+ gap
                            ctx.yTw = ctx.yTw &+ gap
                        }
                    } else {
                        // hai bảng liền nhau cần một đoạn ở giữa
                        let h = max(20, gap)
                        kind[ni] = 0
                        height[ni] = h
                        xml[ni] = ""
                        ni += 1
                        ctx.yTw = ctx.yTw &+ h
                    }
                    kind[ni] = 1
                    xml[ni] = tableXml(b, ctx)
                    ni += 1
                    ctx.yTw = max(ctx.yTw, tw(b.bottom))
                    lastPara = -1
                } else if let p = b.para {
                    kind[ni] = 2
                    para[ni] = p
                    ctxBefore[ni] = ctx.yTw
                    let topTw = tw(p.top)
                    let before = max(0, topTw &- ctx.yTw)
                    let lineTw = max(10, tw(p.height))
                    ctx.yTw = ctx.yTw &+ (before &+ lineTw)
                    lastPara = ni
                    ni += 1
                }
            }
            var sectPr = "<w:sectPr><w:pgSz w:w=\"\(tw(page.width))\" w:h=\"\(tw(page.height + page.extraHeight))\""
            if page.width > page.height { sectPr += " w:orient=\"landscape\"" }
            sectPr += "/><w:pgMar w:top=\"\(tw(page.top))\" w:right=\"\(tw(page.right))\" w:bottom=\"\(tw(page.bottom))\""
            sectPr += " w:left=\"\(tw(page.left))\" w:header=\"0\" w:footer=\"0\" w:gutter=\"0\"/></w:sectPr>"
            for k in 0..<ni {
                if kind[k] == 1 {
                    body += xml[k]
                } else if kind[k] == 0 {
                    body += spacerPara(height[k], after[k], "", xml[k])
                } else if let p = para[k] {
                    let lc = ctx.copy()
                    lc.yTw = ctxBefore[k]
                    body += linePara(p, lc, after[k], "")
                }
            }
            // đoạn kết thúc section (và trang)
            body += spacerPara(20, 0, lastPage ? "" : sectPr, "")
            if lastPage { body += sectPr }
        }
        var doc = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><w:document "
        doc += ns
        doc += "><w:body>"
        doc += body
        doc += "</w:body></w:document>"
        var rels = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
        rels += "<Relationship Id=\"rIdStyles\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>"
        rels += "<Relationship Id=\"rIdSettings\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/settings\" Target=\"settings.xml\"/>"
        for i in 0..<media.count {
            rels += "<Relationship Id=\"rIdImg\(i + 1)\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"media/"
            rels += mediaName(media[i], i)
            rels += "\"/>"
        }
        rels += "</Relationships>"
        return (doc, rels, defSz)
    }

    static func mediaName(_ p: WordDoc.Pic, _ i: Int) -> String {
        return "image\(i + 1)." + (p.ext == "png" ? "png" : "jpeg")
    }

    /// document.xml của các trang (cho kiểm thử / đối chiếu Java ↔ Swift).
    static func documentXml(_ pages: [WordDoc.Page]) -> String {
        var media = [WordDoc.Pic]()
        return buildXml(pages, &media).doc
    }

    static func stylesXml(_ defSz: Int) -> String {
        let fam = esc(family)
        var s = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><w:styles xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
        s += "<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"\(fam)\" w:hAnsi=\"\(fam)\" w:eastAsia=\"\(fam)\" w:cs=\"\(fam)"
        s += "\"/><w:noProof/><w:sz w:val=\"\(defSz)\"/><w:szCs w:val=\"\(defSz)\"/></w:rPr></w:rPrDefault>"
        s += "<w:pPrDefault><w:pPr><w:widowControl w:val=\"0\"/><w:autoSpaceDE w:val=\"0\"/><w:autoSpaceDN w:val=\"0\"/><w:spacing w:before=\"0\" w:after=\"0\" w:line=\"240\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault></w:docDefaults>"
        s += "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/><w:qFormat/></w:style>"
        s += "<w:style w:type=\"character\" w:default=\"1\" w:styleId=\"DefaultParagraphFont\"><w:name w:val=\"Default Paragraph Font\"/><w:uiPriority w:val=\"1\"/><w:semiHidden/><w:unhideWhenUsed/></w:style>"
        s += "<w:style w:type=\"table\" w:default=\"1\" w:styleId=\"TableNormal\"><w:name w:val=\"Normal Table\"/><w:uiPriority w:val=\"99\"/><w:semiHidden/><w:unhideWhenUsed/><w:tblPr><w:tblInd w:w=\"0\" w:type=\"dxa\"/><w:tblCellMar><w:top w:w=\"0\" w:type=\"dxa\"/><w:left w:w=\"108\" w:type=\"dxa\"/><w:bottom w:w=\"0\" w:type=\"dxa\"/><w:right w:w=\"108\" w:type=\"dxa\"/></w:tblCellMar></w:tblPr></w:style>"
        s += "</w:styles>"
        return s
    }

    static let settingsXml = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:settings xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">\
        <w:zoom w:percent="100"/><w:defaultTabStop w:val="720"/><w:characterSpacingControl w:val="doNotCompress"/>\
        <w:compat><w:compatSetting w:name="compatibilityMode" w:uri="http://schemas.microsoft.com/office/word" w:val="15"/>\
        <w:compatSetting w:name="overrideTableStyleFontSizeAndJustification" w:uri="http://schemas.microsoft.com/office/word" w:val="1"/>\
        <w:compatSetting w:name="enableOpenTypeFeatures" w:uri="http://schemas.microsoft.com/office/word" w:val="1"/>\
        <w:compatSetting w:name="doNotFlipMirrorIndents" w:uri="http://schemas.microsoft.com/office/word" w:val="1"/>\
        <w:compatSetting w:name="differentiateMultirowTableHeaders" w:uri="http://schemas.microsoft.com/office/word" w:val="1"/></w:compat>\
        </w:settings>
        """

    static let contentTypesXml = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">\
        <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/>\
        <Default Extension="png" ContentType="image/png"/><Default Extension="jpeg" ContentType="image/jpeg"/>\
        <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>\
        <Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>\
        <Override PartName="/word/settings.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.settings+xml"/>\
        <Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>\
        <Override PartName="/docProps/app.xml" ContentType="application/vnd.openxmlformats-officedocument.extended-properties+xml"/></Types>
        """

    static let rootRelsXml = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">\
        <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>\
        <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/>\
        <Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties" Target="docProps/app.xml"/></Relationships>
        """

    static let appXml = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?><Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/extended-properties">\
        <Application>Quét Tài Liệu</Application></Properties>
        """

    static func coreXml(_ title: String, _ isoTime: String) -> String {
        var s = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:dcterms=\"http://purl.org/dc/terms/\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">"
        s += "<dc:title>"
        s.unicodeScalars.append(contentsOf: esc(title).unicodeScalars)
        s += "</dc:title><dc:creator>Quét Tài Liệu</dc:creator><dcterms:created xsi:type=\"dcterms:W3CDTF\">"
        s.unicodeScalars.append(contentsOf: esc(isoTime).unicodeScalars)
        s += "</dcterms:created><dcterms:modified xsi:type=\"dcterms:W3CDTF\">"
        s.unicodeScalars.append(contentsOf: esc(isoTime).unicodeScalars)
        s += "</dcterms:modified></cp:coreProperties>"
        return s
    }

    /// Một phần (mục zip) của file .docx: tên, nội dung, có nên nén hay không (hình đã nén thì lưu thẳng).
    struct Part {
        let name: String
        let data: [UInt8]
        let deflate: Bool
    }

    /// Mọi phần của file .docx theo đúng thứ tự ghi (dùng chung cho write và xuất đối chiếu Java ↔ Swift).
    /// isoTime: thời điểm tạo dạng 2026-01-31T08:00:00Z. Hình của từng trang (Pic.data / ext) phải được điền trước;
    /// hình không có dữ liệu thì bỏ qua.
    static func parts(_ pages: [WordDoc.Page], _ title: String, _ isoTime: String) -> [Part] {
        var media = [WordDoc.Pic]()
        let x = buildXml(pages, &media)
        var out = [Part]()
        out.append(Part(name: "[Content_Types].xml", data: Array(contentTypesXml.utf8), deflate: true))
        out.append(Part(name: "_rels/.rels", data: Array(rootRelsXml.utf8), deflate: true))
        out.append(Part(name: "docProps/core.xml", data: Array(coreXml(title, isoTime).utf8), deflate: true))
        out.append(Part(name: "docProps/app.xml", data: Array(appXml.utf8), deflate: true))
        out.append(Part(name: "word/document.xml", data: Array(x.doc.utf8), deflate: true))
        out.append(Part(name: "word/styles.xml", data: Array(stylesXml(x.defSz).utf8), deflate: true))
        out.append(Part(name: "word/settings.xml", data: Array(settingsXml.utf8), deflate: true))
        out.append(Part(name: "word/_rels/document.xml.rels", data: Array(x.rels.utf8), deflate: true))
        for i in 0..<media.count {
            out.append(Part(name: "word/media/" + mediaName(media[i], i), data: media[i].data ?? [], deflate: false))
        }
        return out
    }

    /// Ghi file .docx vào bộ nhớ. deflate: hàm nén DEFLATE thô (RFC 1951, không có đầu zlib) do lớp ứng dụng truyền vào;
    /// nil, hoặc hàm trả về nil / kết quả không nhỏ hơn dữ liệu gốc, thì mục zip được lưu thẳng. Tài liệu không có trang
    /// (bản Java ném IOException) -> Data rỗng.
    static func write(pages: [WordDoc.Page], title: String, isoTime: String, deflate: ((Data) -> Data?)? = nil) -> Data {
        if pages.isEmpty { return Data() }
        let zip = Zip(deflate)
        for p in parts(pages, title, isoTime) { zip.add(p.name, p.data, p.deflate) }
        return zip.finish()
    }

    // MARK: zip

    private static let crcTable: [UInt32] = {
        var t = [UInt32](repeating: 0, count: 256)
        for i in 0..<256 {
            var c = UInt32(i)
            for _ in 0..<8 { c = (c & 1) != 0 ? 0xEDB88320 ^ (c >> 1) : c >> 1 }
            t[i] = c
        }
        return t
    }()

    /// CRC-32 (đa thức 0xEDB88320) như java.util.zip.CRC32.
    static func crc32(_ data: [UInt8]) -> UInt32 {
        var c: UInt32 = 0xFFFFFFFF
        crcTable.withUnsafeBufferPointer { t in
            data.withUnsafeBufferPointer { d in
                for b in d { c = t[Int((c ^ UInt32(b)) & 0xFF)] ^ (c >> 8) }
            }
        }
        return c ^ 0xFFFFFFFF
    }

    /// Bộ ghi zip tối giản: mỗi mục nén Deflate (văn bản) hoặc lưu thẳng (ảnh đã nén), tên ASCII, mốc thời gian cố định.
    final class Zip {
        private var out = [UInt8]()
        private var offset = 0
        private var central = [[UInt8]]()
        private var count = 0
        private let deflater: ((Data) -> Data?)?

        init(_ deflater: ((Data) -> Data?)?) {
            self.deflater = deflater
        }

        private static func u16(_ b: inout [UInt8], _ v: Int) {
            b.append(UInt8(truncatingIfNeeded: v))
            b.append(UInt8(truncatingIfNeeded: v >> 8))
        }

        private static func u32(_ b: inout [UInt8], _ v: Int) {
            b.append(UInt8(truncatingIfNeeded: v))
            b.append(UInt8(truncatingIfNeeded: v >> 8))
            b.append(UInt8(truncatingIfNeeded: v >> 16))
            b.append(UInt8(truncatingIfNeeded: v >> 24))
        }

        func add(_ name: String, _ data: [UInt8], _ deflate: Bool) {
            let nm = Array(name.utf8)
            let crc = Int(DocxWriter.crc32(data))
            var payload = data
            var method = 0
            if deflate && !data.isEmpty, let f = deflater, let z = f(Data(data)), !z.isEmpty, z.count < data.count {
                payload = [UInt8](z)
                method = 8
            }
            // 2026-01-01 00:00:00 theo định dạng DOS
            let dosTime = 0, dosDate = ((2026 - 1980) << 9) | (1 << 5) | 1
            var h = [UInt8]()
            h.reserveCapacity(30 + nm.count)
            Zip.u32(&h, 0x04034b50)
            Zip.u16(&h, 20)
            Zip.u16(&h, 0)
            Zip.u16(&h, method)
            Zip.u16(&h, dosTime)
            Zip.u16(&h, dosDate)
            Zip.u32(&h, crc)
            Zip.u32(&h, payload.count)
            Zip.u32(&h, data.count)
            Zip.u16(&h, nm.count)
            Zip.u16(&h, 0)
            h.append(contentsOf: nm)
            out.append(contentsOf: h)
            out.append(contentsOf: payload)
            var c = [UInt8]()
            c.reserveCapacity(46 + nm.count)
            Zip.u32(&c, 0x02014b50)
            Zip.u16(&c, 20)
            Zip.u16(&c, 20)
            Zip.u16(&c, 0)
            Zip.u16(&c, method)
            Zip.u16(&c, dosTime)
            Zip.u16(&c, dosDate)
            Zip.u32(&c, crc)
            Zip.u32(&c, payload.count)
            Zip.u32(&c, data.count)
            Zip.u16(&c, nm.count)
            Zip.u16(&c, 0)
            Zip.u16(&c, 0)
            Zip.u16(&c, 0)
            Zip.u16(&c, 0)
            Zip.u32(&c, 0)
            Zip.u32(&c, offset)
            c.append(contentsOf: nm)
            central.append(c)
            offset += h.count + payload.count
            count += 1
        }

        func finish() -> Data {
            let start = offset
            var size = 0
            for c in central {
                out.append(contentsOf: c)
                size += c.count
            }
            var e = [UInt8]()
            Zip.u32(&e, 0x06054b50)
            Zip.u16(&e, 0)
            Zip.u16(&e, 0)
            Zip.u16(&e, count)
            Zip.u16(&e, count)
            Zip.u32(&e, size)
            Zip.u32(&e, start)
            Zip.u16(&e, 0)
            out.append(contentsOf: e)
            return Data(out)
        }
    }
}
