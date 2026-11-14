package vn.quettailieu.scanner.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

import vn.quettailieu.scanner.core.WordDoc.Block;
import vn.quettailieu.scanner.core.WordDoc.Box;
import vn.quettailieu.scanner.core.WordDoc.Cell;
import vn.quettailieu.scanner.core.WordDoc.Page;
import vn.quettailieu.scanner.core.WordDoc.Para;
import vn.quettailieu.scanner.core.WordDoc.Pic;
import vn.quettailieu.scanner.core.WordDoc.Rule;
import vn.quettailieu.scanner.core.WordDoc.Table;
import vn.quettailieu.scanner.core.WordDoc.Tok;

/**
 * Ghi file Word (.docx) từ các trang đã dàn (WordDoc.Page), giữ nguyên bố cục trang quét:
 * - mỗi trang quét là một section cùng khổ giấy; mỗi dòng chữ là một đoạn có chiều cao dòng CHÍNH XÁC, đặt bằng khoảng
 *   cách trước đoạn + thụt lề (hộp dòng cao 1,125 cỡ chữ thì đường chân chữ nằm ở 80% hộp trong cả Word lẫn LibreOffice);
 * - bảng kẻ là bảng Word thật: bề rộng cột cố định, chiều cao hàng tối thiểu bằng khoảng cách giữa hai đường kẻ
 *   (Word tính cả viền trên vào chiều cao hàng), ô gộp ngang (gridSpan) / dọc (vMerge), viền từng cạnh;
 * - hình, đường kẻ rời, dòng chữ không xếp được vào dòng chảy: neo theo trang (wp:anchor).
 * Thứ tự phần tử con trong pPr / rPr / tblPr / tcPr / sectPr / wp:anchor theo đúng lược đồ OOXML (Word từ chối hoặc
 * "sửa" file sai thứ tự). XML viết tay, nén zip bằng Deflater. Java thuần, không phụ thuộc Android.
 * Bản Swift: ios/Sources/DocxWriter.swift (document.xml trùng từng ký tự).
 */
public final class DocxWriter {
    private DocxWriter() {}

    static final Charset UTF8 = Charset.forName("UTF-8");
    static final String FAMILY = "Times New Roman";
    static final String NS =
            "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" "
                    + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" "
                    + "xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\" "
                    + "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" "
                    + "xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\" "
                    + "xmlns:mc=\"http://schemas.openxmlformats.org/markup-compatibility/2006\" "
                    + "xmlns:wps=\"http://schemas.microsoft.com/office/word/2010/wordprocessingShape\" "
                    + "xmlns:v=\"urn:schemas-microsoft-com:vml\" "
                    + "xmlns:o=\"urn:schemas-microsoft-com:office:office\" "
                    + "xmlns:w10=\"urn:schemas-microsoft-com:office:word\"";

    /** Sai số (pt) khi coi hai mép chữ là "cùng một lề" — trang quét xê dịch nhiều hơn PDF gốc. */
    static final double EDGE_TOL = 2.5;
    /** Hai giá trị lề cách nhau không quá mức này thuộc cùng một nhóm. */
    static final double CLUSTER_TOL = 2.0;

    /** Làm tròn nửa lên (giống Math.round của JavaScript) — dùng chung cho bản Swift. */
    static long rnd(double v) {
        return (long) Math.floor(v + 0.5);
    }

    /** point -> twip (1/20 pt). */
    static long tw(double pt) {
        return rnd(pt * 20.0);
    }

    /** point -> EMU. */
    static long emu(double pt) {
        return rnd(pt * 12700.0);
    }

    /** Thoát ký tự XML; bỏ ký tự điều khiển không hợp lệ trong XML 1.0. */
    static String esc(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        int n = s.length();
        for (int i = 0; i < n; ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == '&') sb.append("&amp;");
            else if (cp == '<') sb.append("&lt;");
            else if (cp == '>') sb.append("&gt;");
            else if (cp == '"') sb.append("&quot;");
            else if (cp < 0x20 && cp != 0x09) continue;
            else if (cp == 0xFFFE || cp == 0xFFFF || (cp >= 0xD800 && cp <= 0xDFFF)) continue;
            else sb.appendCodePoint(cp);
        }
        return sb.toString();
    }

    static String hex6(int color) {
        String h = Integer.toHexString(color & 0xFFFFFF).toUpperCase();
        while (h.length() < 6) h = "0" + h;
        return h;
    }

    // ================================================================== chữ và đoạn

    /** Thuộc tính ký tự của một mẩu chữ (rỗng nếu trùng mặc định). defSz: cỡ chữ mặc định (nửa point). */
    static String rprXml(boolean bold, boolean italic, int color, double spacing, double size, boolean underline, long defSz) {
        StringBuilder sb = new StringBuilder();
        if (bold) sb.append("<w:b/><w:bCs/>");
        if (italic) sb.append("<w:i/><w:iCs/>");
        if ((color & 0xFFFFFF) != 0) sb.append("<w:color w:val=\"").append(hex6(color)).append("\"/>");
        long sp = tw(spacing);
        if (sp != 0) sb.append("<w:spacing w:val=\"").append(sp).append("\"/>");
        long sz = Math.max(2, rnd(size * 2.0));
        if (sz != defSz) sb.append("<w:sz w:val=\"").append(sz).append("\"/><w:szCs w:val=\"").append(sz).append("\"/>");
        if (underline) sb.append("<w:u w:val=\"single\"/>");
        return sb.toString();
    }

    /** Ngữ cảnh đặt đoạn: gốc ngang của vùng chứa, vị trí dọc đã dùng (twip), hai mép vùng chữ. */
    static final class Ctx {
        double originX, areaLeft, areaRight;
        long yTw;
        boolean clampIndent;
        long defSz;

        Ctx copy() {
            Ctx c = new Ctx();
            c.originX = originX;
            c.areaLeft = areaLeft;
            c.areaRight = areaRight;
            c.yTw = yTw;
            c.clampIndent = clampIndent;
            c.defSz = defSz;
            return c;
        }
    }

    /** Đoạn Word cho một dòng chữ. after: khoảng cách sau đoạn (twip); sectPr: kết thúc section tại đoạn này (hoặc ""). */
    static String linePara(Para p, Ctx ctx, long after, String sectPr) {
        long topTw = tw(p.top);
        long before = Math.max(0, topTw - ctx.yTw);
        long lineTw = Math.max(10, tw(p.height));
        ctx.yTw += before + lineTw;
        StringBuilder ppr = new StringBuilder();
        // tab: vị trí tính từ mép vùng chứa, không trùng, tăng dần
        ArrayList<Long> tabs = new ArrayList<>();
        for (Tok t : p.toks) {
            if (t.t != WordDoc.T_TAB) continue;
            long v = tw(t.pos - ctx.originX);
            if (v <= 0 || tabs.contains(v)) continue;
            int at = 0;
            while (at < tabs.size() && tabs.get(at) < v) at++;
            tabs.add(at, v);
        }
        if (!tabs.isEmpty()) {
            ppr.append("<w:tabs>");
            for (long v : tabs) ppr.append("<w:tab w:val=\"left\" w:pos=\"").append(v).append("\"/>");
            ppr.append("</w:tabs>");
        }
        ppr.append("<w:spacing w:before=\"").append(before).append("\" w:after=\"").append(after).append("\" w:line=\"")
                .append(lineTw).append("\" w:lineRule=\"exact\"/>");
        // Phông trên máy khác có thể làm dòng rộng hơn một chút: cho đoạn lấn ra ngoài vùng chữ `need` về phía chữ dài ra
        // để dòng không bao giờ bị ngắt xuống hàng.
        double width = p.x1 - p.x;
        double need = Math.max(9.0, 0.15 * width);
        double left, right;
        String jc = "";
        if (p.align == WordDoc.ALIGN_CENTER) {
            // căn giữa quanh đúng tâm của dòng trong vùng chữ
            double off = 2.0 * ((p.x + p.x1) / 2.0) - ctx.areaLeft - ctx.areaRight;
            left = off > 0.3 ? off : 0.0;
            right = off < -0.3 ? -off : 0.0;
            double room = (ctx.areaRight - ctx.areaLeft - left - right - width) / 2.0;
            if (room < need / 2.0) {
                left -= need / 2.0 - room;
                right -= need / 2.0 - room;
            }
            jc = "<w:jc w:val=\"center\"/>";
        } else if (p.align == WordDoc.ALIGN_RIGHT) {
            right = Math.max(0.0, ctx.areaRight - p.x1);
            if (right < 0.05) right = 0.0;
            double room = p.x - ctx.areaLeft;
            left = room < need ? room - need : 0.0;
            jc = "<w:jc w:val=\"right\"/>";
        } else {
            left = p.x - ctx.originX;
            if (ctx.clampIndent && left < 0) left = 0.0;
            double room = ctx.areaRight - p.x1;
            right = room < need ? room - need : 0.0;
        }
        long lt = tw(left), rt = tw(right);
        if (lt != 0 || rt != 0) {
            ppr.append("<w:ind w:left=\"").append(lt).append("\"");
            if (rt != 0) ppr.append(" w:right=\"").append(rt).append("\"");
            ppr.append("/>");
        }
        ppr.append(jc);
        String mark = rprXml(p.bold, p.italic, p.color, 0.0, p.size, false, ctx.defSz);
        if (mark.length() > 0) ppr.append("<w:rPr>").append(mark).append("</w:rPr>");
        ppr.append(sectPr);

        // các mẩu chữ: gộp mẩu liền nhau cùng thuộc tính
        StringBuilder body = new StringBuilder();
        String curRpr = null;
        StringBuilder curText = new StringBuilder();
        for (Tok t : p.toks) {
            if (t.t == WordDoc.T_TAB) {
                flushRun(body, curRpr, curText);
                curRpr = null;
                body.append("<w:r><w:tab/></w:r>");
                continue;
            }
            String rpr;
            String text;
            if (t.t == WordDoc.T_SPACE) {
                rpr = rprXml(t.bold, t.italic, t.color, t.spacing, t.size, t.underline, ctx.defSz);
                StringBuilder sp = new StringBuilder();
                for (int k = 0; k < Math.max(1, t.n); k++) sp.append(' ');
                text = sp.toString();
            } else {
                rpr = rprXml(t.bold, t.italic, t.color, t.spacing, t.size, t.underline, ctx.defSz);
                text = t.text;
            }
            if (curRpr != null && curRpr.equals(rpr)) {
                curText.append(text);
            } else {
                flushRun(body, curRpr, curText);
                curRpr = rpr;
                curText.append(text);
            }
        }
        flushRun(body, curRpr, curText);
        return "<w:p><w:pPr>" + ppr + "</w:pPr>" + body + "</w:p>";
    }

    private static void flushRun(StringBuilder body, String rpr, StringBuilder text) {
        if (rpr == null) return;
        body.append("<w:r>");
        if (rpr.length() > 0) body.append("<w:rPr>").append(rpr).append("</w:rPr>");
        body.append("<w:t xml:space=\"preserve\">").append(esc(text.toString())).append("</w:t></w:r>");
        text.setLength(0);
    }

    /** Đoạn trống có chiều cao chính xác (cỡ chữ 1 pt). content: các hình neo đặt trong đoạn. */
    static String spacerPara(long heightTw, long after, String sectPr, String content) {
        long line = Math.max(1, heightTw);
        return "<w:p><w:pPr><w:spacing w:before=\"0\" w:after=\"" + after + "\" w:line=\"" + line
                + "\" w:lineRule=\"exact\"/><w:rPr><w:sz w:val=\"2\"/><w:szCs w:val=\"2\"/></w:rPr>" + sectPr + "</w:pPr>" + content + "</w:p>";
    }

    // ================================================================== bảng

    /** Bề dày viền theo đơn vị của Word (1/8 pt): 0 nếu không kẻ. */
    static long borderSz(double t) {
        if (!(t > 0)) return 0;
        return Math.max(2, Math.min(96, rnd(t * 8.0)));
    }

    static String borderXml(String tag, double t) {
        long sz = borderSz(t);
        if (sz == 0) return "";
        return "<w:" + tag + " w:val=\"single\" w:sz=\"" + sz + "\" w:space=\"0\" w:color=\"000000\"/>";
    }

    static String tableXml(Block block, Ctx ctx) {
        Table t = block.table;
        int cols = t.cols, rows = t.rows;
        long[] xTw = new long[cols + 1];
        for (int i = 0; i <= cols; i++) xTw[i] = tw(t.xs[i]);
        long[] colW = new long[cols];
        long total = 0;
        for (int i = 0; i < cols; i++) {
            colW[i] = Math.max(1, xTw[i + 1] - xTw[i]);
            total += colW[i];
        }
        StringBuilder out = new StringBuilder();
        out.append("<w:tbl><w:tblPr>");
        out.append("<w:tblW w:w=\"").append(total).append("\" w:type=\"dxa\"/>");
        out.append("<w:tblInd w:w=\"").append(xTw[0] - tw(ctx.areaLeft)).append("\" w:type=\"dxa\"/>");
        out.append("<w:tblLayout w:type=\"fixed\"/>");
        long marL = tw(t.marL), marR = tw(t.marR);
        out.append("<w:tblCellMar><w:top w:w=\"0\" w:type=\"dxa\"/><w:left w:w=\"").append(marL)
                .append("\" w:type=\"dxa\"/><w:bottom w:w=\"0\" w:type=\"dxa\"/><w:right w:w=\"").append(marR)
                .append("\" w:type=\"dxa\"/></w:tblCellMar>");
        out.append("<w:tblLook w:val=\"0000\" w:firstRow=\"0\" w:lastRow=\"0\" w:firstColumn=\"0\" w:lastColumn=\"0\" w:noHBand=\"1\" w:noVBand=\"1\"/>");
        out.append("</w:tblPr><w:tblGrid>");
        for (int i = 0; i < cols; i++) out.append("<w:gridCol w:w=\"").append(colW[i]).append("\"/>");
        out.append("</w:tblGrid>");
        // Chiều cao hàng "tối thiểu" (atLeast): nội dung đã đặt vừa trong ô nên hàng giữ đúng chiều cao, nhưng người dùng
        // gõ thêm chữ thì hàng tự giãn (chiều cao cố định "exact" sẽ cắt mất chữ). Với atLeast, Word hiểu chiều cao hàng
        // là khoảng trống GIỮA hai đường viền — viền trên của hàng cộng thêm bề dày của nó (khác "exact": viền vẽ lấn vào
        // trong hàng) — nên phải trừ bề dày viền, nếu không mỗi hàng cao thêm một bề dày viền và cả bảng dài dần ra.
        // bw: bề dày viền (twip) trên từng đường lưới ngang, đúng như sẽ ghi ở các ô; rowTop: mép trên của viền từng hàng.
        double[] bw = new double[rows + 1];
        for (Cell c : t.cells) {
            bw[c.r0] = Math.max(bw[c.r0], borderSz(c.bTop) * 2.5);
            bw[c.r1 + 1] = Math.max(bw[c.r1 + 1], borderSz(c.bBottom) * 2.5);
        }
        long[] rowHs = new long[rows];
        double[] rowTop = new double[rows];
        double cur = t.ys[0] * 20.0 - bw[0] / 2.0;
        for (int j = 0; j < rows; j++) {
            double nextTop = t.ys[j + 1] * 20.0 - bw[j + 1] / 2.0;
            rowTop[j] = cur;
            rowHs[j] = Math.max(1, rnd(nextTop - cur - bw[j]));
            cur += bw[j] + rowHs[j];
        }
        for (int j = 0; j < rows; j++) {
            long rowH = rowHs[j];
            out.append("<w:tr><w:trPr><w:cantSplit/><w:trHeight w:val=\"").append(rowH).append("\" w:hRule=\"atLeast\"/></w:trPr>");
            // các ô có mặt ở hàng j, theo thứ tự cột
            int prev = -1;
            for (int i = 0; i < cols; i++) {
                int ci = t.owner[j * cols + i];
                if (ci == prev) continue;
                prev = ci;
                Cell c = t.cells.get(ci);
                if (c.c0 != i) continue;
                long w = 0;
                for (int k = c.c0; k <= c.c1; k++) w += colW[k];
                int span = c.c1 - c.c0 + 1;
                boolean first = j == c.r0, last = j == c.r1;
                out.append("<w:tc><w:tcPr><w:tcW w:w=\"").append(w).append("\" w:type=\"dxa\"/>");
                if (span > 1) out.append("<w:gridSpan w:val=\"").append(span).append("\"/>");
                if (c.r1 > c.r0) out.append(first ? "<w:vMerge w:val=\"restart\"/>" : "<w:vMerge/>");
                String borders = borderXml("top", first ? c.bTop : 0) + borderXml("left", c.bLeft)
                        + borderXml("bottom", last ? c.bBottom : 0) + borderXml("right", c.bRight);
                if (borders.length() > 0) out.append("<w:tcBorders>").append(borders).append("</w:tcBorders>");
                long cl = tw(c.marL), cr = tw(c.marR);
                if (cl != marL || cr != marR) {
                    out.append("<w:tcMar><w:left w:w=\"").append(cl).append("\" w:type=\"dxa\"/><w:right w:w=\"").append(cr)
                            .append("\" w:type=\"dxa\"/></w:tcMar>");
                }
                out.append("</w:tcPr>");
                if (first && !c.paras.isEmpty()) {
                    Ctx cc = new Ctx();
                    cc.originX = c.x0 + c.marL;
                    // nội dung ô bắt đầu ngay dưới viền trên của hàng
                    cc.yTw = rnd(rowTop[c.r0] + bw[c.r0]);
                    cc.areaLeft = c.x0 + c.marL;
                    cc.areaRight = c.x1 - c.marR;
                    cc.clampIndent = true;
                    cc.defSz = ctx.defSz;
                    for (Para p : c.paras) out.append(linePara(p, cc, 0, ""));
                } else {
                    // ô trống: giữ cỡ chữ mặc định để gõ chữ vào trông đúng (hộp dòng không cao quá hàng)
                    long line = Math.max(10, Math.min(rnd(ctx.defSz * 11.5), rowH - 10));
                    out.append("<w:p><w:pPr><w:spacing w:before=\"0\" w:after=\"0\" w:line=\"").append(line)
                            .append("\" w:lineRule=\"exact\"/></w:pPr></w:p>");
                }
                out.append("</w:tc>");
            }
            out.append("</w:tr>");
        }
        out.append("</w:tbl>");
        return out.toString();
    }

    // ================================================================== hình neo theo trang

    static String anchorOpen(double x, double y, double w, double h, boolean behind, long z) {
        return "<wp:anchor distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\" simplePos=\"0\" relativeHeight=\"" + (251658240L + z)
                + "\" behindDoc=\"" + (behind ? 1 : 0) + "\" locked=\"0\" layoutInCell=\"0\" allowOverlap=\"1\">"
                + "<wp:simplePos x=\"0\" y=\"0\"/>"
                + "<wp:positionH relativeFrom=\"page\"><wp:posOffset>" + emu(x) + "</wp:posOffset></wp:positionH>"
                + "<wp:positionV relativeFrom=\"page\"><wp:posOffset>" + emu(y) + "</wp:posOffset></wp:positionV>"
                + "<wp:extent cx=\"" + Math.max(0, emu(w)) + "\" cy=\"" + Math.max(0, emu(h)) + "\"/>"
                + "<wp:effectExtent l=\"0\" t=\"0\" r=\"0\" b=\"0\"/><wp:wrapNone/>";
    }

    static String pictureXml(Pic f, long id, String rid, long z) {
        long cx = Math.max(1, emu(f.w)), cy = Math.max(1, emu(f.h));
        return "<w:r><w:drawing>" + anchorOpen(f.x, f.y, f.w, f.h, f.behind, z) + "<wp:docPr id=\"" + id + "\" name=\"Picture " + id + "\"/>"
                + "<wp:cNvGraphicFramePr><a:graphicFrameLocks noChangeAspect=\"1\"/></wp:cNvGraphicFramePr>"
                + "<a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"><pic:pic>"
                + "<pic:nvPicPr><pic:cNvPr id=\"" + id + "\" name=\"Picture " + id + "\"/><pic:cNvPicPr/></pic:nvPicPr>"
                + "<pic:blipFill><a:blip r:embed=\"" + rid + "\"></a:blip><a:stretch><a:fillRect/></a:stretch></pic:blipFill>"
                + "<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"" + cx + "\" cy=\"" + cy + "\"/></a:xfrm>"
                + "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr>"
                + "</pic:pic></a:graphicData></a:graphic></wp:anchor></w:drawing></w:r>";
    }

    /** Khung chung của một hình vẽ (wps). connector: đường nối thẳng; txbx: nội dung hộp chữ; fallback: bản VML dự phòng. */
    static String shapeWrap(double x, double y, double w, double h, long id, boolean behind, long z, String geom, String fill, String ln,
                            String body, String txbx, String fallback, boolean connector) {
        long cx = Math.max(0, emu(w)), cy = Math.max(0, emu(h));
        return "<w:r><mc:AlternateContent><mc:Choice Requires=\"wps\"><w:drawing>"
                + anchorOpen(x, y, w, h, behind, z)
                + "<wp:docPr id=\"" + id + "\" name=\"Shape " + id + "\"/><wp:cNvGraphicFramePr/>"
                + "<a:graphic><a:graphicData uri=\"http://schemas.microsoft.com/office/word/2010/wordprocessingShape\"><wps:wsp>"
                + (connector ? "<wps:cNvCnPr/>" : "<wps:cNvSpPr" + (txbx.length() > 0 ? " txBox=\"1\"" : "") + "/>")
                + "<wps:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"" + cx + "\" cy=\"" + cy + "\"/></a:xfrm>" + geom + fill + ln + "</wps:spPr>"
                + txbx + body
                + "</wps:wsp></a:graphicData></a:graphic></wp:anchor></w:drawing></mc:Choice>"
                + "<mc:Fallback>" + fallback + "</mc:Fallback></mc:AlternateContent></w:r>";
    }

    /** Đường kẻ rời: đường nối thẳng (như Word tự ghi; LibreOffice vẽ sai hình "line" dọc cao gần bằng trang). */
    static String lineShapeXml(Rule f, long id, long z) {
        double x = f.horiz ? f.a : f.pos, y = f.horiz ? f.pos : f.a;
        double w = f.horiz ? f.b - f.a : 0.0, h = f.horiz ? 0.0 : f.b - f.a;
        String ln = "<a:ln w=\"" + Math.max(3175, emu(f.t)) + "\" cap=\"flat\"><a:solidFill><a:srgbClr val=\"000000\"></a:srgbClr></a:solidFill></a:ln>";
        return shapeWrap(x, y, w, h, id, false, z, "<a:prstGeom prst=\"straightConnector1\"><a:avLst/></a:prstGeom>", "", ln,
                "<wps:bodyPr/>", "", "", true);
    }

    static String fixed2(double v) {
        // số thập phân 2 chữ số, không phụ thuộc ngôn ngữ máy
        long c = rnd(v * 100.0);
        boolean neg = c < 0;
        if (neg) c = -c;
        String frac = String.valueOf(c % 100);
        if (frac.length() < 2) frac = "0" + frac;
        return (neg ? "-" : "") + (c / 100) + "." + frac;
    }

    /** Hộp chữ một dòng, không viền, không lề trong, không tự xuống dòng. */
    static String textBoxXml(Box f, long id, long z, long defSz) {
        // dài hơn một chút để dòng không bị ngắt khi phông khác đi
        double slack = Math.max(4.0, f.w * 0.04);
        double len = f.w + slack;
        Ctx ctx = new Ctx();
        ctx.originX = f.x;
        ctx.yTw = tw(f.y);
        ctx.areaLeft = f.x;
        ctx.areaRight = f.x + len;
        ctx.clampIndent = true;
        ctx.defSz = defSz;
        int keep = f.para.align;
        f.para.align = WordDoc.ALIGN_LEFT;
        String content = linePara(f.para, ctx, 0, "");
        f.para.align = keep;
        String txbx = "<wps:txbx><w:txbxContent>" + content + "</w:txbxContent></wps:txbx>";
        String body = "<wps:bodyPr rot=\"0\" vert=\"horz\" wrap=\"none\" lIns=\"0\" tIns=\"0\" rIns=\"0\" bIns=\"0\" anchor=\"t\" anchorCtr=\"0\"><a:noAutofit/></wps:bodyPr>";
        String style = "position:absolute;margin-left:" + fixed2(f.x) + "pt;margin-top:" + fixed2(f.y) + "pt;width:" + fixed2(len)
                + "pt;height:" + fixed2(Math.max(1.0, f.h)) + "pt;z-index:" + (251658240L + z)
                + ";mso-wrap-style:none;mso-position-horizontal-relative:page;mso-position-vertical-relative:page";
        String fallback = "<w:pict><v:rect id=\"tb" + id + "\" style=\"" + style + "\" filled=\"f\" stroked=\"f\"><v:textbox inset=\"0,0,0,0\"><w:txbxContent>"
                + content + "</w:txbxContent></v:textbox><w10:wrap anchorx=\"page\" anchory=\"page\"/></v:rect></w:pict>";
        return shapeWrap(f.x, f.y, len, f.h, id, false, z, "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom>", "<a:noFill/>",
                "<a:ln><a:noFill/></a:ln>", body, txbx, fallback, false);
    }

    /** Bảng không vào dòng chảy được (đứng cạnh bảng khác): đặt trong hộp chữ đúng chỗ trên trang. */
    static String tableBoxXml(Block block, long id, long z, long defSz) {
        Table t = block.table;
        double pad = 2.0 + t.vT[0] / 2.0;
        double x = t.xs[0] - pad, y = block.top;
        double w = t.xs[t.cols] - t.xs[0] + 2.0 * pad + 2.0, h = block.bottom - block.top + 3.0;
        Ctx ctx = new Ctx();
        ctx.originX = x;
        ctx.yTw = tw(y);
        ctx.areaLeft = x;
        ctx.areaRight = x + w;
        ctx.defSz = defSz;
        String content = tableXml(block, ctx) + spacerPara(20, 0, "", "");
        String txbx = "<wps:txbx><w:txbxContent>" + content + "</w:txbxContent></wps:txbx>";
        String body = "<wps:bodyPr rot=\"0\" vert=\"horz\" wrap=\"square\" lIns=\"0\" tIns=\"0\" rIns=\"0\" bIns=\"0\" anchor=\"t\" anchorCtr=\"0\"><a:noAutofit/></wps:bodyPr>";
        return shapeWrap(x, y, w, h, id, false, z, "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom>", "<a:noFill/>",
                "<a:ln><a:noFill/></a:ln>", body, txbx, "", false);
    }

    // ================================================================== lề trang

    /**
     * Căn lề của một dòng trong vùng chữ [left, right]: giữa khi tâm dòng trùng tâm vùng, phải khi dòng kết thúc ở mép
     * phải — cả hai chỉ khi dòng bắt đầu lùi hẳn vào trong (dòng đầy / căn đều hai lề vẫn là căn trái).
     */
    static int alignmentOf(Para p, double left, double right) {
        if (p.hasTab) return WordDoc.ALIGN_LEFT;
        double width = right - left;
        if (!(width > 0) || p.x - left <= Math.max(3.0, width * 0.1)) return WordDoc.ALIGN_LEFT;
        double tol = Math.max(2.0, width * 0.012);
        if (Math.abs((p.x + p.x1) / 2.0 - (left + right) / 2.0) <= tol) return WordDoc.ALIGN_CENTER;
        if (Math.abs(right - p.x1) <= EDGE_TOL) return WordDoc.ALIGN_RIGHT;
        return WordDoc.ALIGN_LEFT;
    }

    static double leftEdge(Block b) {
        return b.table != null ? b.x0 + b.table.marL : b.x0;
    }

    static double rightEdge(Block b) {
        return b.table != null ? b.x1 - b.table.marR : b.x1;
    }

    /** Mép ngoài cùng được một bảng hoặc ít nhất ba dòng dùng chung (NaN nếu không có): nhãn lẻ thò ra không tính. */
    static double definedEdge(ArrayList<Block> flow, boolean leftSide) {
        int n = flow.size();
        double[] vals = new double[n];
        for (int i = 0; i < n; i++) vals[i] = leftSide ? leftEdge(flow.get(i)) : rightEdge(flow.get(i));
        java.util.Arrays.sort(vals);
        for (int k = 0; k < n; k++) {
            double at = leftSide ? vals[k] : vals[n - 1 - k];
            int near = 0;
            boolean table = false;
            for (Block b : flow) {
                double e = leftSide ? leftEdge(b) : rightEdge(b);
                if (Math.abs(e - at) <= EDGE_TOL) {
                    near++;
                    if (b.table != null) table = true;
                }
            }
            if (near >= 3 || table) return at;
        }
        return Double.NaN;
    }

    /** Nhóm các giá trị gần nhau; mỗi nhóm đại diện bằng giá trị hay gặp nhất (làm tròn 0,05). */
    static final class Clusters {
        final ArrayList<double[]> groups = new ArrayList<>();   // {lo, last, rep, count}
        double common = Double.NaN;

        Clusters(ArrayList<Double> values, boolean pickMin) {
            int n = values.size();
            double[] v = new double[n];
            for (int i = 0; i < n; i++) v[i] = values.get(i);
            java.util.Arrays.sort(v);
            int s = 0;
            while (s < n) {
                int e = s;
                while (e + 1 < n && v[e + 1] - v[e] <= CLUSTER_TOL) e++;
                // giá trị hay gặp nhất trong nhóm (bằng nhau thì lấy nhỏ nhất / lớn nhất theo pickMin)
                double rep = 0;
                int best = 0;
                for (int i = s; i <= e; i++) {
                    double k = rnd(v[i] * 20.0) / 20.0;
                    int c = 0;
                    for (int j = s; j <= e; j++) if (rnd(v[j] * 20.0) == rnd(v[i] * 20.0)) c++;
                    if (c > best || (c == best && (pickMin ? k < rep : k > rep))) {
                        best = c;
                        rep = k;
                    }
                }
                groups.add(new double[]{v[s], v[e], rep, e - s + 1});
                s = e + 1;
            }
            double[] top = null;
            for (double[] g : groups) {
                if (top == null || g[3] > top[3] || (g[3] == top[3] && (pickMin ? g[2] < top[2] : g[2] > top[2]))) top = g;
            }
            if (top != null) common = top[2];
        }

        double snap(double x) {
            for (double[] g : groups) if (x >= g[0] - 1e-6 && x <= g[1] + 1e-6) return g[2];
            return x;
        }
    }

    /**
     * Lề trang, sát với lề của văn bản gốc: vùng chữ của một trang xác định bởi bảng và mép mà nhiều dòng dùng chung; các
     * trang cùng khổ dùng chung lề (trang chỉ có tiêu đề / hình lấy theo đa số). Lề trên / dưới là nhỏ nhất cần có.
     */
    static void planMargins(List<Page> pages) {
        ArrayList<String> keys = new ArrayList<>();
        ArrayList<ArrayList<Page>> lists = new ArrayList<>();
        for (Page page : pages) {
            ArrayList<Block> flow = new ArrayList<>();
            for (Block b : page.blocks) if (!b.overlaps) flow.add(b);
            page.flow = !flow.isEmpty();
            page.left = 36;
            page.right = 36;
            page.top = 36;
            page.bottom = 36;
            page.extraHeight = 0;
            if (page.flow) {
                double ownL = definedEdge(flow, true), ownR = definedEdge(flow, false);
                page.ownLeft = !Double.isNaN(ownL);
                page.ownRight = !Double.isNaN(ownR);
                double minL = Double.MAX_VALUE, maxR = -Double.MAX_VALUE, minTop = Double.MAX_VALUE, maxBottom = -Double.MAX_VALUE;
                for (Block b : flow) {
                    minL = Math.min(minL, leftEdge(b));
                    maxR = Math.max(maxR, rightEdge(b));
                    minTop = Math.min(minTop, b.top);
                    maxBottom = Math.max(maxBottom, b.bottom);
                }
                page.minX = Math.max(0.0, Math.min(page.width / 2.0, page.ownLeft ? ownL : minL));
                page.maxX = Math.min(page.width, Math.max(page.minX + 1.0, page.ownRight ? ownR : maxR));
                page.first = Math.max(0.0, minTop - 1.0);
                page.room = Math.max(0.0, page.height - maxBottom);
            }
            String key = rnd(page.width) + "x" + rnd(page.height);
            int at = keys.indexOf(key);
            if (at < 0) {
                keys.add(key);
                lists.add(new ArrayList<Page>());
                at = keys.size() - 1;
            }
            lists.get(at).add(page);
        }
        for (ArrayList<Page> list : lists) {
            ArrayList<Page> used = new ArrayList<>();
            for (Page pg : list) if (pg.flow) used.add(pg);
            if (used.isEmpty()) continue;
            double width = list.get(0).width;
            boolean anyLeft = false, anyRight = false;
            for (Page pl : used) {
                anyLeft |= pl.ownLeft;
                anyRight |= pl.ownRight;
            }
            ArrayList<Double> lv = new ArrayList<>(), rv = new ArrayList<>();
            double farLeft = Double.MAX_VALUE, farRight = -Double.MAX_VALUE, top = Double.MAX_VALUE, bottom = 43.0;
            for (Page pl : used) {
                if (!anyLeft || pl.ownLeft) lv.add(pl.minX);
                if (!anyRight || pl.ownRight) rv.add(pl.maxX);
                farLeft = Math.min(farLeft, pl.minX);
                farRight = Math.max(farRight, pl.maxX);
                top = Math.min(top, pl.first);
                // dưới nội dung: đoạn 1 pt kết thúc trang + phần dự trữ để bản dựng cao hơn chút không tràn sang trang mới
                bottom = Math.min(bottom, pl.room - Math.max(6.0, pl.room * 0.3));
            }
            bottom = Math.max(0.0, bottom);
            Clusters lefts = new Clusters(lv, true), rights = new Clusters(rv, false);
            // mép không có nội dung nào xác định (chỉ có dòng ngắn) thì đối xứng theo mép kia
            if (!anyLeft && !anyRight) {
                double m = Math.max(0.0, Math.min(farLeft, width - farRight));
                lefts.common = m;
                rights.common = width - m;
            } else if (!anyRight) {
                rights.common = Math.max(farRight, width - lefts.common);
            } else if (!anyLeft) {
                lefts.common = Math.max(0.0, Math.min(farLeft, width - rights.common));
            }
            for (Page pl : list) {
                double minX = lefts.common, maxX = rights.common;
                if (pl.flow) {
                    if (pl.ownLeft) minX = lefts.snap(pl.minX);
                    else if (pl.minX < minX - 0.5) minX = pl.minX;
                    if (pl.ownRight) maxX = rights.snap(pl.maxX);
                    else if (pl.maxX > maxX + 0.5) maxX = pl.maxX;
                }
                if (maxX - minX < 36.0) {
                    minX = Math.min(minX, pl.flow ? pl.minX : minX);
                    maxX = Math.max(Math.max(maxX, pl.flow ? pl.maxX : maxX), minX + 36.0);
                }
                pl.left = Math.floor(Math.max(0.0, minX) * 20.0) / 20.0;
                pl.right = Math.max(0.0, Math.floor((pl.width - maxX) * 20.0) / 20.0);
                pl.top = Math.floor(top * 20.0) / 20.0;
                pl.bottom = Math.floor(bottom * 20.0) / 20.0;
                // nội dung chạm mép dưới: cho trang cao thêm chút thay vì để đoạn cuối rơi sang trang mới
                double spare = pl.flow ? pl.room - pl.bottom - 1.2 : 1.0;
                if (spare < 0) pl.extraHeight = Math.ceil(-spare * 20.0) / 20.0;
            }
        }
    }

    // ================================================================== tài liệu

    private static void countPara(Para p, ArrayList<long[]> count) {
        long sz = Math.max(2, rnd(p.size * 2.0));
        long chars = 0;
        for (Tok t : p.toks) if (t.t == WordDoc.T_TEXT) chars += t.text.length();
        for (long[] e : count) {
            if (e[0] == sz) {
                e[1] += chars;
                return;
            }
        }
        count.add(new long[]{sz, chars});
    }

    /** Cỡ chữ chủ đạo của tài liệu (nửa point) theo số ký tự. */
    static long dominantSize(List<Page> pages) {
        ArrayList<long[]> count = new ArrayList<>();
        for (Page page : pages) {
            for (Block b : page.blocks) {
                if (b.para != null) countPara(b.para, count);
                if (b.table != null) for (Cell c : b.table.cells) for (Para p : c.paras) countPara(p, count);
            }
            for (Box x : page.boxes) countPara(x.para, count);
        }
        long best = 24, n = -1;
        for (long[] e : count) {
            if (e[1] > n || (e[1] == n && e[0] < best)) {
                n = e[1];
                best = e[0];
            }
        }
        return best;
    }

    /** Hình có đè lên dòng chữ nào của trang không (để đặt ra sau chữ). */
    static boolean overlapsText(Pic f, Page page) {
        for (Block b : page.blocks) {
            if (b.para != null && hits(f, b.para)) return true;
            if (b.table != null) for (Cell c : b.table.cells) for (Para p : c.paras) if (hits(f, p)) return true;
        }
        for (Box x : page.boxes) if (hits(f, x.para)) return true;
        return false;
    }

    private static boolean hits(Pic f, Para p) {
        return p.x1 >= f.x && p.x <= f.x + f.w && p.top + p.height >= f.y && p.top <= f.y + f.h;
    }

    /** Các phần XML của tài liệu: [0] document.xml, [1] rels của document. media: hình được nhúng (theo thứ tự rId). */
    static String[] buildXml(List<Page> pages, ArrayList<Pic> media) {
        long defSz = dominantSize(pages);
        planMargins(pages);
        StringBuilder body = new StringBuilder();
        long nextId = 1;
        int np = pages.size();
        for (int pi = 0; pi < np; pi++) {
            Page page = pages.get(pi);
            boolean lastPage = pi == np - 1;
            ArrayList<Block> flow = new ArrayList<>(), floating = new ArrayList<>();
            for (Block b : page.blocks) {
                if (b.overlaps) floating.add(b);
                else flow.add(b);
            }
            Ctx ctx = new Ctx();
            ctx.originX = page.left;
            ctx.yTw = tw(page.top);
            ctx.areaLeft = page.left;
            ctx.areaRight = page.width - page.right;
            ctx.defSz = defSz;
            for (Block b : flow) if (b.para != null) b.para.align = alignmentOf(b.para, ctx.areaLeft, ctx.areaRight);

            // ---- hình neo: đặt trong đoạn đầu tiên của trang
            StringBuilder drawings = new StringBuilder();
            long z = 0;
            for (Pic f : page.pics) {
                long id = nextId++;
                if (f.data == null || f.data.length == 0) continue;
                f.behind = overlapsText(f, page);
                media.add(f);
                drawings.append(pictureXml(f, id, "rIdImg" + media.size(), z));
                z += 16;
            }
            for (Rule r : page.rules) {
                drawings.append(lineShapeXml(r, nextId++, z));
                z += 16;
            }
            for (Box x : page.boxes) {
                drawings.append(textBoxXml(x, nextId++, z, defSz));
                z += 16;
            }
            for (Block b : floating) drawings.append(tableBoxXml(b, nextId++, 0, defSz));

            // ---- dòng chảy: vị trí tính trước, XML sinh sau để còn chỉnh được khoảng cách sau đoạn
            int nItems = flow.size() * 2 + 4;
            int[] kind = new int[nItems];           // 0 đoạn trống, 1 bảng, 2 dòng chữ
            long[] height = new long[nItems], after = new long[nItems], ctxBefore = new long[nItems];
            String[] xml = new String[nItems];
            Para[] para = new Para[nItems];
            int ni = 0;
            kind[ni] = 0;
            height[ni] = 20;
            xml[ni] = drawings.toString();
            ni++;
            ctx.yTw += 20;
            int lastPara = 0;
            for (Block b : flow) {
                if (b.table != null) {
                    long gap = tw(b.top) - ctx.yTw;
                    if (lastPara >= 0) {
                        if (gap > 0) {
                            after[lastPara] += gap;
                            ctx.yTw += gap;
                        }
                    } else {
                        // hai bảng liền nhau cần một đoạn ở giữa
                        long h = Math.max(20, gap);
                        kind[ni] = 0;
                        height[ni] = h;
                        xml[ni] = "";
                        ni++;
                        ctx.yTw += h;
                    }
                    kind[ni] = 1;
                    xml[ni] = tableXml(b, ctx);
                    ni++;
                    ctx.yTw = Math.max(ctx.yTw, tw(b.bottom));
                    lastPara = -1;
                } else {
                    kind[ni] = 2;
                    para[ni] = b.para;
                    ctxBefore[ni] = ctx.yTw;
                    long topTw = tw(b.para.top);
                    long before = Math.max(0, topTw - ctx.yTw);
                    long lineTw = Math.max(10, tw(b.para.height));
                    ctx.yTw += before + lineTw;
                    lastPara = ni;
                    ni++;
                }
            }
            String sectPr = "<w:sectPr><w:pgSz w:w=\"" + tw(page.width) + "\" w:h=\"" + tw(page.height + page.extraHeight) + "\""
                    + (page.width > page.height ? " w:orient=\"landscape\"" : "") + "/>"
                    + "<w:pgMar w:top=\"" + tw(page.top) + "\" w:right=\"" + tw(page.right) + "\" w:bottom=\"" + tw(page.bottom)
                    + "\" w:left=\"" + tw(page.left) + "\" w:header=\"0\" w:footer=\"0\" w:gutter=\"0\"/></w:sectPr>";
            for (int k = 0; k < ni; k++) {
                if (kind[k] == 1) body.append(xml[k]);
                else if (kind[k] == 0) body.append(spacerPara(height[k], after[k], "", xml[k]));
                else {
                    Ctx lc = ctx.copy();
                    lc.yTw = ctxBefore[k];
                    body.append(linePara(para[k], lc, after[k], ""));
                }
            }
            // đoạn kết thúc section (và trang)
            body.append(spacerPara(20, 0, lastPage ? "" : sectPr, ""));
            if (lastPage) body.append(sectPr);
        }
        String doc = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><w:document " + NS + "><w:body>" + body + "</w:body></w:document>";
        StringBuilder rels = new StringBuilder();
        rels.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">");
        rels.append("<Relationship Id=\"rIdStyles\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>");
        rels.append("<Relationship Id=\"rIdSettings\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/settings\" Target=\"settings.xml\"/>");
        for (int i = 0; i < media.size(); i++) {
            rels.append("<Relationship Id=\"rIdImg").append(i + 1)
                    .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"media/")
                    .append(mediaName(media.get(i), i)).append("\"/>");
        }
        rels.append("</Relationships>");
        return new String[]{doc, rels.toString(), String.valueOf(defSz)};
    }

    static String mediaName(Pic p, int i) {
        return "image" + (i + 1) + "." + ("png".equals(p.ext) ? "png" : "jpeg");
    }

    /** document.xml của các trang (cho kiểm thử / đối chiếu Java ↔ Swift). */
    public static String documentXml(List<Page> pages) {
        return buildXml(pages, new ArrayList<Pic>())[0];
    }

    static String stylesXml(long defSz) {
        String fam = esc(FAMILY);
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><w:styles xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
                + "<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"" + fam + "\" w:hAnsi=\"" + fam + "\" w:eastAsia=\"" + fam + "\" w:cs=\"" + fam
                + "\"/><w:noProof/><w:sz w:val=\"" + defSz + "\"/><w:szCs w:val=\"" + defSz + "\"/></w:rPr></w:rPrDefault>"
                + "<w:pPrDefault><w:pPr><w:widowControl w:val=\"0\"/><w:autoSpaceDE w:val=\"0\"/><w:autoSpaceDN w:val=\"0\"/><w:spacing w:before=\"0\" w:after=\"0\" w:line=\"240\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault></w:docDefaults>"
                + "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/><w:qFormat/></w:style>"
                + "<w:style w:type=\"character\" w:default=\"1\" w:styleId=\"DefaultParagraphFont\"><w:name w:val=\"Default Paragraph Font\"/><w:uiPriority w:val=\"1\"/><w:semiHidden/><w:unhideWhenUsed/></w:style>"
                + "<w:style w:type=\"table\" w:default=\"1\" w:styleId=\"TableNormal\"><w:name w:val=\"Normal Table\"/><w:uiPriority w:val=\"99\"/><w:semiHidden/><w:unhideWhenUsed/><w:tblPr><w:tblInd w:w=\"0\" w:type=\"dxa\"/><w:tblCellMar><w:top w:w=\"0\" w:type=\"dxa\"/><w:left w:w=\"108\" w:type=\"dxa\"/><w:bottom w:w=\"0\" w:type=\"dxa\"/><w:right w:w=\"108\" w:type=\"dxa\"/></w:tblCellMar></w:tblPr></w:style>"
                + "</w:styles>";
    }

    static final String SETTINGS_XML =
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><w:settings xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
                    + "<w:zoom w:percent=\"100\"/><w:defaultTabStop w:val=\"720\"/><w:characterSpacingControl w:val=\"doNotCompress\"/>"
                    + "<w:compat><w:compatSetting w:name=\"compatibilityMode\" w:uri=\"http://schemas.microsoft.com/office/word\" w:val=\"15\"/>"
                    + "<w:compatSetting w:name=\"overrideTableStyleFontSizeAndJustification\" w:uri=\"http://schemas.microsoft.com/office/word\" w:val=\"1\"/>"
                    + "<w:compatSetting w:name=\"enableOpenTypeFeatures\" w:uri=\"http://schemas.microsoft.com/office/word\" w:val=\"1\"/>"
                    + "<w:compatSetting w:name=\"doNotFlipMirrorIndents\" w:uri=\"http://schemas.microsoft.com/office/word\" w:val=\"1\"/>"
                    + "<w:compatSetting w:name=\"differentiateMultirowTableHeaders\" w:uri=\"http://schemas.microsoft.com/office/word\" w:val=\"1\"/></w:compat>"
                    + "</w:settings>";

    static final String CONTENT_TYPES_XML =
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                    + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                    + "<Default Extension=\"png\" ContentType=\"image/png\"/><Default Extension=\"jpeg\" ContentType=\"image/jpeg\"/>"
                    + "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
                    + "<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>"
                    + "<Override PartName=\"/word/settings.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.settings+xml\"/>"
                    + "<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/>"
                    + "<Override PartName=\"/docProps/app.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.extended-properties+xml\"/></Types>";

    static final String ROOT_RELS_XML =
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>"
                    + "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\" Target=\"docProps/core.xml\"/>"
                    + "<Relationship Id=\"rId3\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties\" Target=\"docProps/app.xml\"/></Relationships>";

    static final String APP_XML =
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Properties xmlns=\"http://schemas.openxmlformats.org/officeDocument/2006/extended-properties\"><Application>Quét Tài Liệu</Application></Properties>";

    static String coreXml(String title, String isoTime) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:dcterms=\"http://purl.org/dc/terms/\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">"
                + "<dc:title>" + esc(title == null ? "" : title) + "</dc:title><dc:creator>Quét Tài Liệu</dc:creator><dcterms:created xsi:type=\"dcterms:W3CDTF\">" + esc(isoTime)
                + "</dcterms:created><dcterms:modified xsi:type=\"dcterms:W3CDTF\">" + esc(isoTime) + "</dcterms:modified></cp:coreProperties>";
    }

    /**
     * Ghi file .docx. isoTime: thời điểm tạo dạng 2026-01-31T08:00:00Z.
     * Hình của từng trang (Pic.data / ext) phải được điền trước; hình không có dữ liệu thì bỏ qua.
     */
    public static void write(OutputStream os, List<Page> pages, String title, String isoTime) throws IOException {
        if (pages == null || pages.isEmpty()) throw new IOException("Tài liệu không có trang");
        ArrayList<Pic> media = new ArrayList<>();
        String[] parts = buildXml(pages, media);
        long defSz = Long.parseLong(parts[2]);
        Zip zip = new Zip(os);
        zip.add("[Content_Types].xml", CONTENT_TYPES_XML.getBytes(UTF8), true);
        zip.add("_rels/.rels", ROOT_RELS_XML.getBytes(UTF8), true);
        zip.add("docProps/core.xml", coreXml(title, isoTime == null ? "2026-01-01T00:00:00Z" : isoTime).getBytes(UTF8), true);
        zip.add("docProps/app.xml", APP_XML.getBytes(UTF8), true);
        zip.add("word/document.xml", parts[0].getBytes(UTF8), true);
        zip.add("word/styles.xml", stylesXml(defSz).getBytes(UTF8), true);
        zip.add("word/settings.xml", SETTINGS_XML.getBytes(UTF8), true);
        zip.add("word/_rels/document.xml.rels", parts[1].getBytes(UTF8), true);
        for (int i = 0; i < media.size(); i++) zip.add("word/media/" + mediaName(media.get(i), i), media.get(i).data, false);
        zip.finish();
    }

    public static byte[] toBytes(List<Page> pages, String title, String isoTime) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        write(bo, pages, title, isoTime);
        return bo.toByteArray();
    }

    // ================================================================== zip

    /** Bộ ghi zip tối giản: mỗi mục nén Deflate (văn bản) hoặc lưu thẳng (ảnh đã nén), tên ASCII, mốc thời gian cố định. */
    static final class Zip {
        private final OutputStream out;
        private long offset;
        private final ArrayList<byte[]> central = new ArrayList<>();
        private int count;

        Zip(OutputStream o) {
            out = o;
        }

        private static void u16(ByteArrayOutputStream b, int v) {
            b.write(v & 255);
            b.write((v >>> 8) & 255);
        }

        private static void u32(ByteArrayOutputStream b, long v) {
            b.write((int) (v & 255));
            b.write((int) ((v >>> 8) & 255));
            b.write((int) ((v >>> 16) & 255));
            b.write((int) ((v >>> 24) & 255));
        }

        void add(String name, byte[] data, boolean deflate) throws IOException {
            byte[] nm = name.getBytes(UTF8);
            CRC32 crc = new CRC32();
            crc.update(data, 0, data.length);
            byte[] payload = data;
            int method = 0;
            if (deflate && data.length > 0) {
                Deflater d = new Deflater(6, true);
                d.setInput(data);
                d.finish();
                ByteArrayOutputStream bo = new ByteArrayOutputStream(data.length / 4 + 64);
                byte[] buf = new byte[16 * 1024];
                while (!d.finished()) {
                    int n = d.deflate(buf);
                    bo.write(buf, 0, n);
                }
                d.end();
                byte[] z = bo.toByteArray();
                if (z.length < data.length) {
                    payload = z;
                    method = 8;
                }
            }
            // 2026-01-01 00:00:00 theo định dạng DOS
            int dosTime = 0, dosDate = ((2026 - 1980) << 9) | (1 << 5) | 1;
            ByteArrayOutputStream h = new ByteArrayOutputStream(64);
            u32(h, 0x04034b50L);
            u16(h, 20);
            u16(h, 0);
            u16(h, method);
            u16(h, dosTime);
            u16(h, dosDate);
            u32(h, crc.getValue());
            u32(h, payload.length);
            u32(h, data.length);
            u16(h, nm.length);
            u16(h, 0);
            h.write(nm, 0, nm.length);
            byte[] hb = h.toByteArray();
            out.write(hb);
            out.write(payload);
            ByteArrayOutputStream c = new ByteArrayOutputStream(64);
            u32(c, 0x02014b50L);
            u16(c, 20);
            u16(c, 20);
            u16(c, 0);
            u16(c, method);
            u16(c, dosTime);
            u16(c, dosDate);
            u32(c, crc.getValue());
            u32(c, payload.length);
            u32(c, data.length);
            u16(c, nm.length);
            u16(c, 0);
            u16(c, 0);
            u16(c, 0);
            u16(c, 0);
            u32(c, 0);
            u32(c, offset);
            c.write(nm, 0, nm.length);
            central.add(c.toByteArray());
            offset += hb.length + payload.length;
            count++;
        }

        void finish() throws IOException {
            long start = offset;
            long size = 0;
            for (byte[] c : central) {
                out.write(c);
                size += c.length;
            }
            ByteArrayOutputStream e = new ByteArrayOutputStream(32);
            u32(e, 0x06054b50L);
            u16(e, 0);
            u16(e, 0);
            u16(e, count);
            u16(e, count);
            u32(e, size);
            u32(e, start);
            u16(e, 0);
            out.write(e.toByteArray());
            out.flush();
        }
    }
}
