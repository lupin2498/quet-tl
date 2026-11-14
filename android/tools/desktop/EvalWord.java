import vn.quettailieu.scanner.core.DocLayout;
import vn.quettailieu.scanner.core.DocxWriter;
import vn.quettailieu.scanner.core.PdfWriter;
import vn.quettailieu.scanner.core.WordDoc;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/**
 * Chuyển các trang quét tổng hợp của tests/tools/make_word_pages.py sang Word bằng lõi DocLayout + DocxWriter:
 * mỗi trang <tên>.ppm + <tên>.words.tsv (kết quả "nhận dạng chữ" giả lập) -> <thư-mục-ra>/<tên>.docx, kèm một file
 * tat_ca.docx gồm mọi trang (kiểm tra tài liệu nhiều trang). Kết quả được kiểm bằng tests/tools/check_docx.py
 * (cấu trúc OOXML, bảng / chữ so với đáp án, dựng lại bằng LibreOffice để so vị trí từng từ).
 * Cách chạy: java -cp out EvalWord <thư-mục-vào> <thư-mục-ra> [khổ: a4|letter|fit]
 */
public class EvalWord {
    static List<DocLayout.Word> readWords(File f) throws Exception {
        List<DocLayout.Word> out = new ArrayList<>();
        for (String line : DumpCore.readLines(f)) {
            String[] t = line.split("\t");
            if (t.length < 6) continue;
            DocLayout.Word wd = new DocLayout.Word(t[5], Float.parseFloat(t[0]), Float.parseFloat(t[1]), Float.parseFloat(t[2]),
                    Float.parseFloat(t[3]), Integer.parseInt(t[4]));
            // cột thứ 7 (tuỳ chọn): độ tin cậy 0..1
            if (t.length > 6) wd.conf = Float.parseFloat(t[6]);
            out.add(wd);
        }
        return out;
    }

    static byte[] jpeg(int[] px, int w, int h) throws Exception {
        BufferedImage bi = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        bi.setRGB(0, 0, w, h, px, 0, w);
        ImageWriter wr = ImageIO.getImageWritersByFormatName("jpeg").next();
        ImageWriteParam p = wr.getDefaultWriteParam();
        p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        p.setCompressionQuality(0.85f);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        MemoryCacheImageOutputStream ios = new MemoryCacheImageOutputStream(bo);
        wr.setOutput(ios);
        wr.write(null, new IIOImage(bi, null, null), p);
        ios.close();
        wr.dispose();
        return bo.toByteArray();
    }

    static int countParas(WordDoc.Page p) {
        int n = p.boxes.size();
        for (WordDoc.Block b : p.blocks) {
            if (b.para != null) n++;
            if (b.table != null) for (WordDoc.Cell c : b.table.cells) n += c.paras.size();
        }
        return n;
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("Cách dùng: EvalWord <thư-mục-vào> <thư-mục-ra> [a4|letter|fit]");
            System.exit(2);
        }
        File in = new File(args[0]), out = new File(args[1]);
        out.mkdirs();
        int mode = PdfWriter.SIZE_A4;
        if (args.length > 2 && args[2].equals("letter")) mode = PdfWriter.SIZE_LETTER;
        if (args.length > 2 && args[2].equals("fit")) mode = PdfWriter.SIZE_FIT;
        String[] names = in.list();
        if (names == null) throw new RuntimeException("không đọc được " + in);
        Arrays.sort(names);
        List<WordDoc.Page> all = new ArrayList<>();
        long total = 0;
        int pages = 0;
        for (String n : names) {
            if (!n.endsWith(".ppm")) continue;
            String name = n.substring(0, n.length() - 4);
            File wf = new File(in, name + ".words.tsv");
            if (!wf.exists()) continue;
            int[][] img = DumpCore.readPPM(new File(in, n));
            int w = img[0][0], h = img[0][1];
            List<DocLayout.Word> words = readWords(wf);
            long t0 = System.nanoTime();
            DocLayout.Result r = DocLayout.analyze(img[1], w, h, words, mode);
            long ms = (System.nanoTime() - t0) / 1000000;
            total += ms;
            pages++;
            for (WordDoc.Pic pic : r.page.pics) {
                int pw = pic.px1 - pic.px0, ph = pic.py1 - pic.py0;
                pic.data = jpeg(r.picture(pic), pw, ph);
                pic.ext = "jpeg";
            }
            r.release();
            List<WordDoc.Page> one = new ArrayList<>();
            one.add(r.page);
            FileOutputStream fo = new FileOutputStream(new File(out, name + ".docx"));
            DocxWriter.write(fo, one, name, "2026-01-01T00:00:00Z");
            fo.close();
            all.add(r.page);
            int tables = 0, cells = 0;
            for (WordDoc.Block b : r.page.blocks) {
                if (b.table != null) {
                    tables++;
                    cells += b.table.cells.size();
                }
            }
            System.out.println(String.format(Locale.US, "%s %dx%d: %d từ -> %d dòng, %d bảng (%d ô), %d hộp chữ, %d hình, %d đường kẻ rời, nghiêng %.2f độ, %d ms",
                    name, w, h, words.size(), countParas(r.page), tables, cells, r.page.boxes.size(), r.page.pics.size(), r.page.rules.size(),
                    Math.toDegrees(r.skew), ms));
        }
        if (!all.isEmpty()) {
            FileOutputStream fo = new FileOutputStream(new File(out, "tat_ca.docx"));
            DocxWriter.write(fo, all, "Tất cả các trang", "2026-01-01T00:00:00Z");
            fo.close();
        }
        System.out.println(String.format(Locale.US, "EvalWord: %d trang, phân tích trung bình %d ms/trang", pages, pages == 0 ? 0 : total / pages));
    }
}
