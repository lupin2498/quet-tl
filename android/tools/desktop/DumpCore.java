import vn.quettailieu.scanner.core.DocDetector;
import vn.quettailieu.scanner.core.DocLayout;
import vn.quettailieu.scanner.core.DocxWriter;
import vn.quettailieu.scanner.core.Enhancer;
import vn.quettailieu.scanner.core.Flatten;
import vn.quettailieu.scanner.core.Geom;
import vn.quettailieu.scanner.core.PdfWriter;
import vn.quettailieu.scanner.core.Retouch;
import vn.quettailieu.scanner.core.WordDoc;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Xuất kết quả phần thuật toán (bản Java) trên bộ dữ liệu của tests/tools/make_core_inputs.py
 * (+ make_retouch_images.py: ảnh rt_*, make_wavy_images.py: ảnh wv_* cùng thư mục)
 * để đối chiếu với bản Swift (ios/Tests/linux/main.swift --dump) bằng tests/tools/compare_core.py.
 *
 * Cách chạy: java -cp out DumpCore <thư-mục-dữ-liệu> <thư-mục-kết-quả>
 *
 * Lõi chuyển trang quét sang Word (DocLayout + DocxWriter) xuất riêng — các phần xuất khác không chạy trên trang Word:
 *   java -cp out DumpCore --word <thư-mục-trang> <thư-mục-kết-quả>
 * với mỗi trang <tên>.ppm có kèm <tên>.words.tsv (tests/tools/make_word_pages.py, make_word_junk.py), ở cả ba khổ giấy
 * a4 / letter / fit; bản Swift: algotest --dump-word; đối chiếu: compare_core.py --word <kết-quả-java> <kết-quả-swift>.
 */
public class DumpCore {
    static final String TITLE = "Biên bản thử nghiệm số 12 — ấệữ 😀";

    public static void main(String[] a) throws Exception {
        if (a.length >= 3 && a[0].equals("--word")) {
            dumpWord(new File(a[1]), new File(a[2]));
            return;
        }
        File in = new File(a[0]), out = new File(a[1]);
        out.mkdirs();
        PrintWriter pw = new PrintWriter(new File(out, "results.txt"), "UTF-8");
        String[] names = in.list();
        Arrays.sort(names);
        long tDet = 0, tEnh = 0;
        int nImg = 0;
        for (String n : names) {
            if (!n.endsWith(".ppm")) continue;
            String name = n.substring(0, n.length() - 4);
            int[][] img = readPPM(new File(in, n));
            int w = img[0][0], h = img[0][1];
            int[] px = img[1];
            nImg++;
            long t0 = System.nanoTime();
            DocDetector.Result r = DocDetector.detect(px.clone(), w, h);
            tDet += System.nanoTime() - t0;
            StringBuilder sb = new StringBuilder("det " + name + " " + w + " " + h);
            if (r == null) sb.append(" null");
            else if (r.quad == null) sb.append(String.format(Locale.US, " none %.4f", r.score));
            else {
                sb.append(" quad");
                for (float v : r.quad) sb.append(String.format(Locale.US, " %.3f", v));
                sb.append(String.format(Locale.US, " %.4f %d", r.score, r.refined ? 1 : 0));
            }
            pw.println(sb);
            boolean full = name.startsWith("full_");
            // ảnh trang đã nắn phẳng của make_retouch_images.py: so từng điểm ảnh ở các bộ lọc mới
            boolean rt = name.startsWith("rt_");
            // trang giấy mấp mô / gấp nếp của make_wavy_images.py: so từng điểm ảnh ở bộ lọc Làm phẳng + trường nắn (dòng flt)
            boolean wv = name.startsWith("wv_");
            for (int mode : new int[]{Enhancer.MAGIC, Enhancer.LIGHTEN, Enhancer.GRAY, Enhancer.BW,
                    Enhancer.MAGIC_PRO, Enhancer.DENOISE, Enhancer.NO_SHADOW, Enhancer.NO_HAND, Enhancer.FLATTEN}) {
                int[] c = px.clone();
                t0 = System.nanoTime();
                Enhancer.apply(c, w, h, mode);
                tEnh += System.nanoTime() - t0;
                long sr = 0, sg = 0, sb2 = 0;
                for (int v : c) {
                    sr += (v >> 16) & 255;
                    sg += (v >> 8) & 255;
                    sb2 += v & 255;
                }
                pw.println("enh " + name + " " + mode + " " + sr + " " + sg + " " + sb2);
                if (full || (rt && mode >= Enhancer.MAGIC_PRO) || (wv && mode == Enhancer.FLATTEN)) {
                    writePPM(new File(out, name + "_m" + mode + ".ppm"), c, w, h);
                }
            }
            if (wv) pw.println(flattenLine(name, px, w, h));
            if (rt) dumpRetouch(pw, out, name, px, w, h);
        }
        // Retouch.remap: đổi vùng cắt
        {
            float[][] quads = {{100, 80, 900, 120, 880, 1300, 60, 1250}, {0, 0, 1000, 0, 1000, 1400, 0, 1400}, {50, 60, 950, 40, 990, 1380, 20, 1390}};
            float[] pts = {0.25f, 0.5f, 0.9f, 0.1f, 0f, 0f, 1f, 1f};
            int ri = 0;
            for (float[] qa : quads) {
                for (float[] qb : quads) {
                    StringBuilder sb = new StringBuilder("remap " + ri++);
                    for (float v : Retouch.remap(pts, qa, qb)) sb.append(String.format(Locale.US, " %.5f", v));
                    sb.append(String.format(Locale.US, " %.5f", Retouch.remapScale(qa, qb)));
                    pw.println(sb);
                }
            }
        }
        // Geom
        List<String> lines = readLines(new File(in, "quads.txt"));
        int qi = 0;
        for (String line : lines) {
            String[] p = line.trim().split(" ");
            if (p.length < 13) continue;
            int W = Integer.parseInt(p[0]), H = Integer.parseInt(p[1]);
            double focal = Double.parseDouble(p[2]);
            boolean snap = p[3].equals("1");
            int maxLong = Integer.parseInt(p[4]);
            float[] q = new float[8];
            for (int i = 0; i < 8; i++) q[i] = Float.parseFloat(p[5 + i]);
            float[] o = Geom.order(q);
            boolean convex = Geom.isConvex(o);
            StringBuilder sb = new StringBuilder("geom " + qi + " order");
            for (float v : o) sb.append(String.format(Locale.US, " %.2f", v));
            sb.append(" convex ").append(convex ? 1 : 0);
            sb.append(String.format(Locale.US, " area %.3f", Geom.area(o)));
            if (convex) {
                double asp = Geom.estimateAspect(o, W, H, focal);
                int[] sz = Geom.outputSize(o, W, H, focal, snap, maxLong);
                sb.append(String.format(Locale.US, " aspect %.6f size %d %d", asp, sz[0], sz[1]));
            }
            pw.println(sb);
            qi++;
        }
        for (double r : new double[]{0.5, 0.68, 0.70, 0.7071, 0.735, 0.76, 0.7727, 0.79, 1.0, 1.29, 1.3, 1.38, 1.4142, 1.47, 2.0}) {
            pw.println(String.format(Locale.US, "snap %.4f %.6f", r, Geom.snapPaper(r)));
        }
        // PdfWriter: so từng byte
        File jdir = new File(in, "jpg");
        String[] jn = jdir.list();
        Arrays.sort(jn);
        List<File> files = new ArrayList<>();
        for (String n : jn) if (n.endsWith(".jpg")) files.add(new File(jdir, n));
        int k = 0;
        for (int mode : new int[]{PdfWriter.SIZE_A4, PdfWriter.SIZE_LETTER, PdfWriter.SIZE_FIT}) {
            for (int v = 0; v < 3; v++) {
                List<File> sel = new ArrayList<>();
                if (v == 0) sel.add(files.get(0));
                else if (v == 1) sel.addAll(files);
                else for (int i = 0; i < 25; i++) sel.add(files.get(i % files.size()));
                List<byte[]> bytes = new ArrayList<>();
                List<PdfWriter.Source> srcs = new ArrayList<>();
                for (File f : sel) {
                    bytes.add(readAll(f));
                    srcs.add(new PdfWriter.FileSource(f));
                }
                String title = v == 1 ? TITLE : (v == 0 ? "" : "Scan 10-11-2026 08.00");
                byte[] mem = PdfWriter.toBytes(bytes, mode, title);
                File pf = new File(out, "pdf_" + k + ".pdf");
                OutputStream os = new BufferedOutputStream(new FileOutputStream(pf));
                try {
                    PdfWriter.writeSources(os, srcs, mode, title);
                } finally {
                    os.close();
                }
                byte[] stream = readAll(pf);
                pw.println("pdf " + k + " mode " + mode + " pages " + sel.size() + " bytes " + stream.length + " same " + (Arrays.equals(mem, stream) ? 1 : 0));
                k++;
            }
        }
        for (File f : files) {
            PdfWriter.JpegInfo info = PdfWriter.readJpegInfo(readAll(f));
            pw.println("jpeg " + f.getName() + " " + (info == null ? "null" : info.width + " " + info.height + " " + info.components));
        }
        pw.close();
        System.out.printf(Locale.US, "Java: %d ảnh, tìm mép %.0f ms, lọc %.0f ms%n", nImg, tDet / 1e6, tEnh / 1e6);
    }

    /**
     * Dòng "flt": trường nắn của bộ lọc Làm phẳng (Flatten.apply đầy đủ, có nắn, trên bản sao ảnh vào) —
     * có trường hay không, kích thước lưới, số dòng chữ / kẻ ngang / kẻ dọc, trang xoay ngang, dịch chuyển lớn nhất,
     * tổng dx, tổng dy (cộng dồn kiểu double theo thứ tự chỉ số). Bản Swift (main.swift --dump) in đúng dòng này.
     */
    static String flattenLine(String name, int[] px, int w, int h) {
        Flatten.Field f = Flatten.apply(px.clone(), w, h);
        if (f == null) return "flt " + name + " 0 0 0 0 0 0 0 0.000 0.000 0.000";
        double sx = 0, sy = 0;
        for (int i = 0; i < f.dx.length; i++) sx += f.dx[i];
        for (int i = 0; i < f.dy.length; i++) sy += f.dy[i];
        return "flt " + name + " 1 " + f.gw + " " + f.gh + " " + f.lines + " " + f.hrules + " " + f.vrules + " " + (f.rotated ? 1 : 0)
                + String.format(Locale.US, " %.3f %.3f %.3f", (double) f.maxShift, sx, sy);
    }

    /** Các thao tác "Xóa thông minh" cố định dùng để đối chiếu (toạ độ chuẩn hoá trên trang của make_retouch_images.py). */
    static List<Retouch.Op> retouchOps() {
        List<Retouch.Op> ops = new ArrayList<>();
        ops.add(new Retouch.Op(Retouch.PEN, 0.03f, new float[]{0.15f, 0.78f, 0.30f, 0.79f}));
        ops.add(new Retouch.Op(Retouch.RECT, 0f, new float[]{0.6f, 0.84f, 0.86f, 0.84f, 0.86f, 0.985f, 0.6f, 0.985f}));
        ops.add(new Retouch.Op(Retouch.MAGIC, 0.03f, new float[]{0.16f, 0.885f, 0.24f, 0.93f}));
        ops.add(new Retouch.Op(Retouch.TEXT, 0.055f, new float[]{0.17f, 0.554f, 0.33f, 0.554f}));
        ops.add(new Retouch.Op(Retouch.HAND, 0f, new float[0]));
        return ops;
    }

    static String sums(int[] c) {
        long sr = 0, sg = 0, sb = 0;
        for (int v : c) {
            sr += (v >> 16) & 255;
            sg += (v >> 8) & 255;
            sb += v & 255;
        }
        return " " + sr + " " + sg + " " + sb;
    }

    static void dumpRetouch(PrintWriter pw, File out, String name, int[] px, int w, int h) throws IOException {
        byte[] fm = Retouch.fingerMask(px.clone(), w, h);
        int cnt = 0;
        if (fm != null) for (byte b : fm) if (b != 0) cnt++;
        pw.println("ret " + name + " finger " + (fm != null ? 1 : 0) + " " + cnt);
        List<Retouch.Op> ops = retouchOps();
        String[] kinds = {"pen", "rect", "magic", "text", "hand"};
        for (int k = 0; k < ops.size(); k++) {
            int[] c = px.clone();
            Retouch.Op op = ops.get(k);
            boolean changed;
            if (op.kind == Retouch.HAND) {
                changed = Retouch.erase(c, w, h, Retouch.HAND, null);
            } else {
                byte[] m = new byte[w * h];
                Retouch.opMask(m, w, h, op);
                changed = Retouch.erase(c, w, h, op.kind, m);
            }
            pw.println("ret " + name + " " + kinds[k] + " " + (changed ? 1 : 0) + sums(c));
        }
        int[] c = px.clone();
        Retouch.applyErase(c, w, h, ops);
        pw.println("ret " + name + " all" + sums(c));
        writePPM(new File(out, name + "_all.ppm"), c, w, h);
        c = px.clone();
        Retouch.sharpen(c, w, h, 96);
        pw.println("ret " + name + " sharpen" + sums(c));
        c = px.clone();
        Retouch.cleanEdges(c, w, h);
        pw.println("ret " + name + " clean" + sums(c));
    }

    // ------------------------------------------------------------------ lõi Word (DocLayout + DocxWriter)

    static final String[] WORD_MODES = {"a4", "letter", "fit"};
    static final String WORD_TIME = "2026-01-01T00:00:00Z";

    /** FNV-1a 64 bit. */
    static long fnv(long h, int b) {
        return (h ^ (b & 255)) * 0x100000001b3L;
    }

    static long fnv(byte[] d) {
        long h = 0xcbf29ce484222325L;
        for (byte b : d) h = fnv(h, b);
        return h;
    }

    static String hex16(long v) {
        String s = Long.toHexString(v);
        return "0000000000000000".substring(s.length()) + s;
    }

    /** Số thực dưới dạng 16 chữ số hex của bit IEEE 754 (mọi NaN như nhau): không phụ thuộc cách in số của ngôn ngữ. */
    static String hx(double v) {
        return hex16(Double.doubleToLongBits(v));
    }

    static int parseIntStrict(String s) {
        return Integer.parseInt(s.trim());
    }

    /** Các từ của <tên>.words.tsv: x0 y0 x1 y1 dòng chữ [độ-tin-cậy], ngăn bằng tab (như EvalWord.readWords). */
    static List<DocLayout.Word> readWords(File f) throws IOException {
        List<DocLayout.Word> out = new ArrayList<>();
        for (String line : readLines(f)) {
            String[] t = line.split("\t");
            if (t.length < 6) continue;
            DocLayout.Word wd = new DocLayout.Word(t[5], Float.parseFloat(t[0]), Float.parseFloat(t[1]), Float.parseFloat(t[2]),
                    Float.parseFloat(t[3]), parseIntStrict(t[4]));
            if (t.length > 6) wd.conf = Float.parseFloat(t[6]);
            out.add(wd);
        }
        return out;
    }

    static void be32(ByteArrayOutputStream o, long v) {
        o.write((int) (v >>> 24) & 255);
        o.write((int) (v >>> 16) & 255);
        o.write((int) (v >>> 8) & 255);
        o.write((int) v & 255);
    }

    static void pngChunk(ByteArrayOutputStream o, String type, byte[] data) {
        byte[] t = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        be32(o, data.length);
        o.write(t, 0, 4);
        o.write(data, 0, data.length);
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(t, 0, 4);
        crc.update(data, 0, data.length);
        be32(o, crc.getValue());
    }

    /**
     * PNG RGB 8 bit KHÔNG nén (khối "stored" của zlib) từ điểm ảnh ARGB: hai bản Java / Swift ghi ra đúng cùng các byte nên
     * so được nội dung phần word/media của file .docx (bộ nén JPEG / PNG của từng nền tảng thì không).
     */
    static byte[] pngStored(int[] px, int w, int h) {
        byte[] raw = new byte[h * (1 + 3 * w)];
        int k = 0;
        for (int y = 0; y < h; y++) {
            raw[k++] = 0;
            for (int x = 0; x < w; x++) {
                int c = px[y * w + x];
                raw[k++] = (byte) (c >> 16);
                raw[k++] = (byte) (c >> 8);
                raw[k++] = (byte) c;
            }
        }
        ByteArrayOutputStream z = new ByteArrayOutputStream(raw.length + raw.length / 65535 * 5 + 16);
        z.write(0x78);
        z.write(0x01);
        int pos = 0;
        do {
            int n = Math.min(65535, raw.length - pos);
            z.write(pos + n >= raw.length ? 1 : 0);
            z.write(n & 255);
            z.write((n >>> 8) & 255);
            z.write(~n & 255);
            z.write((~n >>> 8) & 255);
            z.write(raw, pos, n);
            pos += n;
        } while (pos < raw.length);
        java.util.zip.Adler32 ad = new java.util.zip.Adler32();
        ad.update(raw, 0, raw.length);
        be32(z, ad.getValue());
        ByteArrayOutputStream o = new ByteArrayOutputStream(z.size() + 64);
        o.write(new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A}, 0, 8);
        ByteArrayOutputStream hd = new ByteArrayOutputStream(13);
        be32(hd, w);
        be32(hd, h);
        hd.write(8);
        hd.write(2);
        hd.write(0);
        hd.write(0);
        hd.write(0);
        pngChunk(o, "IHDR", hd.toByteArray());
        pngChunk(o, "IDAT", z.toByteArray());
        pngChunk(o, "IEND", new byte[0]);
        return o.toByteArray();
    }

    static void modelPara(StringBuilder sb, WordDoc.Para p) {
        sb.append("para ").append(hx(p.top)).append(' ').append(hx(p.height)).append(' ').append(hx(p.x)).append(' ').append(hx(p.x1))
                .append(' ').append(hx(p.base)).append(' ').append(p.align).append(' ').append(p.hasTab ? 1 : 0).append(' ').append(hx(p.size))
                .append(' ').append(p.bold ? 1 : 0).append(' ').append(p.italic ? 1 : 0).append(' ').append(p.color).append(' ')
                .append(p.toks.size()).append('\n');
        for (WordDoc.Tok t : p.toks) {
            sb.append("tok ").append(t.t).append(' ').append(t.n).append(' ').append(hx(t.spacing)).append(' ').append(hx(t.pos)).append(' ')
                    .append(t.bold ? 1 : 0).append(' ').append(t.italic ? 1 : 0).append(' ').append(t.underline ? 1 : 0).append(' ')
                    .append(t.color).append(' ').append(hx(t.size)).append(" text=");
            String s = t.text == null ? "" : t.text;
            if (s.length() == 0) sb.append('-');
            for (int i = 0; i < s.length(); ) {
                int cp = s.codePointAt(i);
                if (i > 0) sb.append(',');
                i += Character.charCount(cp);
                sb.append(Integer.toHexString(cp).toUpperCase());
            }
            sb.append('\n');
        }
    }

    static void modelArray(StringBuilder sb, String tag, double[] v) {
        sb.append(tag);
        for (double x : v) sb.append(' ').append(hx(x));
        sb.append('\n');
    }

    /**
     * Mô hình trang (WordDoc.Page) do DocLayout.analyze dựng, từng trường một, số thực ở dạng bit: chặt hơn nhiều so với
     * document.xml (XML làm tròn tới 1/20 pt nên che mất lệch nhỏ). Bản Swift (main.swift --dump-word) in đúng các dòng này.
     */
    static String modelText(DocLayout.Result r) {
        WordDoc.Page pg = r.page;
        StringBuilder sb = new StringBuilder();
        sb.append("page ").append(hx(pg.width)).append(' ').append(hx(pg.height)).append('\n');
        sb.append("res ").append(hx(r.skew)).append(' ').append(r.tables).append(' ').append(r.lines).append('\n');
        int bi = 0;
        for (WordDoc.Block b : pg.blocks) {
            sb.append("block ").append(bi++).append(' ').append(b.table != null ? "table" : (b.para != null ? "para" : "none")).append(' ')
                    .append(hx(b.top)).append(' ').append(hx(b.bottom)).append(' ').append(hx(b.x0)).append(' ').append(hx(b.x1)).append(' ')
                    .append(b.overlaps ? 1 : 0).append('\n');
            if (b.para != null) modelPara(sb, b.para);
            if (b.table != null) {
                WordDoc.Table t = b.table;
                sb.append("table ").append(t.cols).append(' ').append(t.rows).append(' ').append(hx(t.marL)).append(' ').append(hx(t.marR))
                        .append(' ').append(t.cells.size()).append('\n');
                modelArray(sb, "xs", t.xs);
                modelArray(sb, "ys", t.ys);
                modelArray(sb, "hT", t.hT);
                modelArray(sb, "vT", t.vT);
                sb.append("owner");
                for (int o : t.owner) sb.append(' ').append(o);
                sb.append('\n');
                for (WordDoc.Cell c : t.cells) {
                    sb.append("cell ").append(c.c0).append(' ').append(c.c1).append(' ').append(c.r0).append(' ').append(c.r1).append(' ')
                            .append(hx(c.x0)).append(' ').append(hx(c.y0)).append(' ').append(hx(c.x1)).append(' ').append(hx(c.y1)).append(' ')
                            .append(hx(c.bTop)).append(' ').append(hx(c.bLeft)).append(' ').append(hx(c.bBottom)).append(' ').append(hx(c.bRight))
                            .append(' ').append(hx(c.marL)).append(' ').append(hx(c.marR)).append(' ').append(c.paras.size()).append('\n');
                    for (WordDoc.Para p : c.paras) modelPara(sb, p);
                }
            }
        }
        for (WordDoc.Pic p : pg.pics) {
            sb.append("pic ").append(hx(p.x)).append(' ').append(hx(p.y)).append(' ').append(hx(p.w)).append(' ').append(hx(p.h)).append(' ')
                    .append(p.behind ? 1 : 0).append(' ').append(p.px0).append(' ').append(p.py0).append(' ').append(p.px1).append(' ')
                    .append(p.py1).append('\n');
        }
        for (WordDoc.Rule q : pg.rules) {
            sb.append("rule ").append(q.horiz ? 1 : 0).append(' ').append(hx(q.pos)).append(' ').append(hx(q.a)).append(' ').append(hx(q.b))
                    .append(' ').append(hx(q.t)).append('\n');
        }
        for (WordDoc.Box x : pg.boxes) {
            sb.append("box ").append(hx(x.x)).append(' ').append(hx(x.y)).append(' ').append(hx(x.w)).append(' ').append(hx(x.h)).append('\n');
            modelPara(sb, x.para);
        }
        return sb.toString();
    }

    /**
     * Các trang DỰNG TAY để đối chiếu DocxWriter ở những nhánh mà kết quả của DocLayout không đi qua: lề ô khác lề bảng, bảng
     * đứng cạnh bảng khác (hộp chữ chứa bảng), hai bảng liền nhau, tab trùng / trước lề, dấu cách lặp, hình không có dữ liệu,
     * trang trống / chỉ có hộp chữ, và giá trị bất thường (NaN, vô cực, rất lớn: ép kiểu bão hoà, cộng trừ long quay vòng).
     * Mã dưới đây và bản Swift (main.swift syntheticPages) được sinh từ cùng một mô tả nên dựng đúng cùng một mô hình.
     */
    static List<WordDoc.Page> syntheticPages() {
        List<WordDoc.Page> pages = new ArrayList<>();
        WordDoc.Page g1 = new WordDoc.Page();
        g1.width = 595.276;
        g1.height = 841.89;
        WordDoc.Block b2 = new WordDoc.Block();
        WordDoc.Para p3 = new WordDoc.Para();
        p3.top = 40.0;
        p3.height = 15.0;
        p3.x = 50.0;
        p3.x1 = 300.0;
        p3.base = 52.0;
        p3.size = 13.0;
        p3.bold = true;
        p3.italic = false;
        p3.hasTab = true;
        p3.color = 16711680;
        p3.align = 0;
        WordDoc.Tok k4 = new WordDoc.Tok();
        k4.t = 0;
        k4.text = "Xin <ch\u00E0o> & \"b\u1EA1n\" 'x'";
        k4.n = 1;
        k4.spacing = 0.26;
        k4.pos = 0.0;
        k4.size = 13.0;
        k4.bold = true;
        k4.italic = false;
        k4.underline = false;
        k4.color = 0;
        p3.toks.add(k4);
        WordDoc.Tok k5 = new WordDoc.Tok();
        k5.t = 1;
        k5.text = "";
        k5.n = 3;
        k5.spacing = 0.3;
        k5.pos = 0.0;
        k5.size = 13.0;
        k5.bold = true;
        k5.italic = false;
        k5.underline = false;
        k5.color = 0;
        p3.toks.add(k5);
        WordDoc.Tok k6 = new WordDoc.Tok();
        k6.t = 0;
        k6.text = "a\u0001b\u0009c\uFFFE\uD83D\uDE00e\u0301";
        k6.n = 1;
        k6.spacing = -0.2;
        k6.pos = 0.0;
        k6.size = 11.5;
        k6.bold = false;
        k6.italic = true;
        k6.underline = true;
        k6.color = 1193046;
        p3.toks.add(k6);
        WordDoc.Tok k7 = new WordDoc.Tok();
        k7.t = 2;
        k7.text = "";
        k7.n = 1;
        k7.spacing = 0.0;
        k7.pos = 400.0;
        k7.size = 13.0;
        k7.bold = false;
        k7.italic = false;
        k7.underline = false;
        k7.color = 0;
        p3.toks.add(k7);
        WordDoc.Tok k8 = new WordDoc.Tok();
        k8.t = 2;
        k8.text = "";
        k8.n = 1;
        k8.spacing = 0.0;
        k8.pos = 400.0;
        k8.size = 13.0;
        k8.bold = false;
        k8.italic = false;
        k8.underline = false;
        k8.color = 0;
        p3.toks.add(k8);
        WordDoc.Tok k9 = new WordDoc.Tok();
        k9.t = 2;
        k9.text = "";
        k9.n = 1;
        k9.spacing = 0.0;
        k9.pos = 20.0;
        k9.size = 13.0;
        k9.bold = false;
        k9.italic = false;
        k9.underline = false;
        k9.color = 0;
        p3.toks.add(k9);
        WordDoc.Tok k10 = new WordDoc.Tok();
        k10.t = 2;
        k10.text = "";
        k10.n = 1;
        k10.spacing = 0.0;
        k10.pos = 250.5;
        k10.size = 13.0;
        k10.bold = false;
        k10.italic = false;
        k10.underline = false;
        k10.color = 0;
        p3.toks.add(k10);
        WordDoc.Tok k11 = new WordDoc.Tok();
        k11.t = 0;
        k11.text = "sau tab";
        k11.n = 1;
        k11.spacing = 0.0;
        k11.pos = 0.0;
        k11.size = 13.0;
        k11.bold = false;
        k11.italic = false;
        k11.underline = false;
        k11.color = 0;
        p3.toks.add(k11);
        WordDoc.Tok k12 = new WordDoc.Tok();
        k12.t = 1;
        k12.text = "";
        k12.n = 0;
        k12.spacing = 0.0;
        k12.pos = 0.0;
        k12.size = 13.0;
        k12.bold = false;
        k12.italic = false;
        k12.underline = false;
        k12.color = 0;
        p3.toks.add(k12);
        WordDoc.Tok k13 = new WordDoc.Tok();
        k13.t = 1;
        k13.text = "";
        k13.n = 1;
        k13.spacing = 0.0;
        k13.pos = 0.0;
        k13.size = 13.0;
        k13.bold = false;
        k13.italic = false;
        k13.underline = false;
        k13.color = 0;
        p3.toks.add(k13);
        WordDoc.Tok k14 = new WordDoc.Tok();
        k14.t = 0;
        k14.text = "";
        k14.n = 1;
        k14.spacing = 0.0;
        k14.pos = 0.0;
        k14.size = 13.0;
        k14.bold = false;
        k14.italic = false;
        k14.underline = false;
        k14.color = 0;
        p3.toks.add(k14);
        b2.para = p3;
        b2.top = 40.0;
        b2.bottom = p3.top + p3.height;
        b2.x0 = 50.0;
        b2.x1 = 300.0;
        g1.blocks.add(b2);
        WordDoc.Block b15 = new WordDoc.Block();
        WordDoc.Para p16 = new WordDoc.Para();
        p16.top = 60.0;
        p16.height = 14.625;
        p16.x = 50.0;
        p16.x1 = 540.0;
        p16.base = 71.7;
        p16.size = 13.0;
        p16.bold = false;
        p16.italic = false;
        p16.hasTab = false;
        p16.color = 0;
        p16.align = 0;
        WordDoc.Tok k17 = new WordDoc.Tok();
        k17.t = 0;
        k17.text = "D\u00F2ng th\u1EE9 0 c\u1EE7a trang d\u1EF1ng tay";
        k17.n = 1;
        k17.spacing = 0.0;
        k17.pos = 0.0;
        k17.size = 13.0;
        k17.bold = false;
        k17.italic = false;
        k17.underline = false;
        k17.color = 0;
        p16.toks.add(k17);
        b15.para = p16;
        b15.top = 60.0;
        b15.bottom = p16.top + p16.height;
        b15.x0 = 50.0;
        b15.x1 = 540.0;
        g1.blocks.add(b15);
        WordDoc.Block b18 = new WordDoc.Block();
        WordDoc.Para p19 = new WordDoc.Para();
        p19.top = 76.0;
        p19.height = 14.625;
        p19.x = 50.0;
        p19.x1 = 537.0;
        p19.base = 87.7;
        p19.size = 13.0;
        p19.bold = false;
        p19.italic = false;
        p19.hasTab = false;
        p19.color = 0;
        p19.align = 0;
        WordDoc.Tok k20 = new WordDoc.Tok();
        k20.t = 0;
        k20.text = "D\u00F2ng th\u1EE9 1 c\u1EE7a trang d\u1EF1ng tay";
        k20.n = 1;
        k20.spacing = 0.05;
        k20.pos = 0.0;
        k20.size = 13.0;
        k20.bold = false;
        k20.italic = false;
        k20.underline = false;
        k20.color = 0;
        p19.toks.add(k20);
        b18.para = p19;
        b18.top = 76.0;
        b18.bottom = p19.top + p19.height;
        b18.x0 = 50.0;
        b18.x1 = 537.0;
        g1.blocks.add(b18);
        WordDoc.Block b21 = new WordDoc.Block();
        WordDoc.Para p22 = new WordDoc.Para();
        p22.top = 92.0;
        p22.height = 14.625;
        p22.x = 50.0;
        p22.x1 = 540.0;
        p22.base = 103.7;
        p22.size = 13.0;
        p22.bold = false;
        p22.italic = false;
        p22.hasTab = false;
        p22.color = 0;
        p22.align = 0;
        WordDoc.Tok k23 = new WordDoc.Tok();
        k23.t = 0;
        k23.text = "D\u00F2ng th\u1EE9 2 c\u1EE7a trang d\u1EF1ng tay";
        k23.n = 1;
        k23.spacing = 0.1;
        k23.pos = 0.0;
        k23.size = 13.0;
        k23.bold = false;
        k23.italic = false;
        k23.underline = false;
        k23.color = 0;
        p22.toks.add(k23);
        b21.para = p22;
        b21.top = 92.0;
        b21.bottom = p22.top + p22.height;
        b21.x0 = 50.0;
        b21.x1 = 540.0;
        g1.blocks.add(b21);
        WordDoc.Block b24 = new WordDoc.Block();
        WordDoc.Para p25 = new WordDoc.Para();
        p25.top = 108.0;
        p25.height = 14.625;
        p25.x = 50.0;
        p25.x1 = 537.0;
        p25.base = 119.7;
        p25.size = 13.0;
        p25.bold = false;
        p25.italic = false;
        p25.hasTab = false;
        p25.color = 0;
        p25.align = 0;
        WordDoc.Tok k26 = new WordDoc.Tok();
        k26.t = 0;
        k26.text = "D\u00F2ng th\u1EE9 3 c\u1EE7a trang d\u1EF1ng tay";
        k26.n = 1;
        k26.spacing = 0.15000000000000002;
        k26.pos = 0.0;
        k26.size = 13.0;
        k26.bold = false;
        k26.italic = false;
        k26.underline = false;
        k26.color = 0;
        p25.toks.add(k26);
        b24.para = p25;
        b24.top = 108.0;
        b24.bottom = p25.top + p25.height;
        b24.x0 = 50.0;
        b24.x1 = 537.0;
        g1.blocks.add(b24);
        WordDoc.Block b27 = new WordDoc.Block();
        WordDoc.Para p28 = new WordDoc.Para();
        p28.top = 130.0;
        p28.height = 16.0;
        p28.x = 200.0;
        p28.x1 = 395.276;
        p28.base = 143.0;
        p28.size = 14.0;
        p28.bold = true;
        p28.italic = false;
        p28.hasTab = false;
        p28.color = 0;
        p28.align = 0;
        WordDoc.Tok k29 = new WordDoc.Tok();
        k29.t = 0;
        k29.text = "\u1EDE GI\u1EEEA";
        k29.n = 1;
        k29.spacing = 0.0;
        k29.pos = 0.0;
        k29.size = 14.0;
        k29.bold = true;
        k29.italic = false;
        k29.underline = false;
        k29.color = 0;
        p28.toks.add(k29);
        b27.para = p28;
        b27.top = 130.0;
        b27.bottom = p28.top + p28.height;
        b27.x0 = 200.0;
        b27.x1 = 395.276;
        g1.blocks.add(b27);
        WordDoc.Block b30 = new WordDoc.Block();
        WordDoc.Para p31 = new WordDoc.Para();
        p31.top = 150.0;
        p31.height = 14.0;
        p31.x = 430.0;
        p31.x1 = 541.2;
        p31.base = 161.0;
        p31.size = 12.0;
        p31.bold = false;
        p31.italic = true;
        p31.hasTab = false;
        p31.color = 0;
        p31.align = 0;
        WordDoc.Tok k32 = new WordDoc.Tok();
        k32.t = 0;
        k32.text = "b\u00EAn ph\u1EA3i";
        k32.n = 1;
        k32.spacing = 0.0;
        k32.pos = 0.0;
        k32.size = 12.0;
        k32.bold = false;
        k32.italic = true;
        k32.underline = false;
        k32.color = 0;
        p31.toks.add(k32);
        b30.para = p31;
        b30.top = 150.0;
        b30.bottom = p31.top + p31.height;
        b30.x0 = 430.0;
        b30.x1 = 541.2;
        g1.blocks.add(b30);
        WordDoc.Block b33 = new WordDoc.Block();
        WordDoc.Table t34 = new WordDoc.Table();
        t34.cols = 3;
        t34.rows = 2;
        t34.xs = new double[]{50.0, 150.0, 300.0, 540.0};
        t34.ys = new double[]{200.0, 230.0, 270.0};
        t34.hT = new double[]{1.0, 0.5, 1.0};
        t34.vT = new double[]{1.0, 1.0, 1.0, 1.0};
        t34.owner = new int[]{0, 1, 1, 0, 2, 3};
        t34.marL = 3.0;
        t34.marR = 3.0;
        WordDoc.Cell c35 = new WordDoc.Cell();
        c35.c0 = 0;
        c35.c1 = 0;
        c35.r0 = 0;
        c35.r1 = 1;
        c35.x0 = 50.0;
        c35.y0 = 200.0;
        c35.x1 = 150.0;
        c35.y1 = 270.0;
        c35.bTop = 1.0;
        c35.bLeft = 1.0;
        c35.bBottom = 1.0;
        c35.bRight = 1.0;
        c35.marL = 3.0;
        c35.marR = 3.0;
        WordDoc.Para p36 = new WordDoc.Para();
        p36.top = 203.0;
        p36.height = 13.0;
        p36.x = 53.0;
        p36.x1 = 120.0;
        p36.base = 213.4;
        p36.size = 11.0;
        p36.bold = false;
        p36.italic = false;
        p36.hasTab = false;
        p36.color = 0;
        p36.align = 0;
        WordDoc.Tok k37 = new WordDoc.Tok();
        k37.t = 0;
        k37.text = "g\u1ED9p d\u1ECDc";
        k37.n = 1;
        k37.spacing = 0.0;
        k37.pos = 0.0;
        k37.size = 11.0;
        k37.bold = false;
        k37.italic = false;
        k37.underline = false;
        k37.color = 0;
        p36.toks.add(k37);
        c35.paras.add(p36);
        t34.cells.add(c35);
        WordDoc.Cell c38 = new WordDoc.Cell();
        c38.c0 = 1;
        c38.c1 = 2;
        c38.r0 = 0;
        c38.r1 = 0;
        c38.x0 = 150.0;
        c38.y0 = 200.0;
        c38.x1 = 540.0;
        c38.y1 = 230.0;
        c38.bTop = 0.1;
        c38.bLeft = 20.0;
        c38.bBottom = 0.0;
        c38.bRight = 1.0;
        c38.marL = 3.0;
        c38.marR = 3.0;
        WordDoc.Para p39 = new WordDoc.Para();
        p39.top = 204.0;
        p39.height = 13.0;
        p39.x = 300.0;
        p39.x1 = 390.0;
        p39.base = 214.4;
        p39.size = 11.0;
        p39.bold = false;
        p39.italic = false;
        p39.hasTab = false;
        p39.color = 0;
        p39.align = 1;
        WordDoc.Tok k40 = new WordDoc.Tok();
        k40.t = 0;
        k40.text = "g\u1ED9p ngang";
        k40.n = 1;
        k40.spacing = 0.0;
        k40.pos = 0.0;
        k40.size = 11.0;
        k40.bold = false;
        k40.italic = false;
        k40.underline = false;
        k40.color = 0;
        p39.toks.add(k40);
        c38.paras.add(p39);
        WordDoc.Para p41 = new WordDoc.Para();
        p41.top = 217.0;
        p41.height = 12.0;
        p41.x = 153.0;
        p41.x1 = 200.0;
        p41.base = 226.6;
        p41.size = 10.0;
        p41.bold = false;
        p41.italic = false;
        p41.hasTab = false;
        p41.color = 0;
        p41.align = 2;
        WordDoc.Tok k42 = new WordDoc.Tok();
        k42.t = 0;
        k42.text = "d\u00F2ng 2";
        k42.n = 1;
        k42.spacing = 0.0;
        k42.pos = 0.0;
        k42.size = 10.0;
        k42.bold = false;
        k42.italic = false;
        k42.underline = false;
        k42.color = 0;
        p41.toks.add(k42);
        c38.paras.add(p41);
        t34.cells.add(c38);
        WordDoc.Cell c43 = new WordDoc.Cell();
        c43.c0 = 1;
        c43.c1 = 1;
        c43.r0 = 1;
        c43.r1 = 1;
        c43.x0 = 150.0;
        c43.y0 = 230.0;
        c43.x1 = 300.0;
        c43.y1 = 270.0;
        c43.bTop = 0.5;
        c43.bLeft = 1.0;
        c43.bBottom = 1.0;
        c43.bRight = 0.0;
        c43.marL = 1.0;
        c43.marR = 4.0;
        WordDoc.Para p44 = new WordDoc.Para();
        p44.top = 233.0;
        p44.height = 13.0;
        p44.x = 151.0;
        p44.x1 = 250.0;
        p44.base = 243.4;
        p44.size = 11.0;
        p44.bold = false;
        p44.italic = false;
        p44.hasTab = false;
        p44.color = 0;
        p44.align = 0;
        WordDoc.Tok k45 = new WordDoc.Tok();
        k45.t = 0;
        k45.text = "l\u1EC1 ri\u00EAng";
        k45.n = 1;
        k45.spacing = 0.0;
        k45.pos = 0.0;
        k45.size = 11.0;
        k45.bold = false;
        k45.italic = false;
        k45.underline = false;
        k45.color = 0;
        p44.toks.add(k45);
        c43.paras.add(p44);
        t34.cells.add(c43);
        WordDoc.Cell c46 = new WordDoc.Cell();
        c46.c0 = 2;
        c46.c1 = 2;
        c46.r0 = 1;
        c46.r1 = 1;
        c46.x0 = 300.0;
        c46.y0 = 230.0;
        c46.x1 = 540.0;
        c46.y1 = 270.0;
        c46.bTop = 0.0;
        c46.bLeft = 0.0;
        c46.bBottom = 0.0;
        c46.bRight = 0.0;
        c46.marL = 3.0;
        c46.marR = 3.0;
        t34.cells.add(c46);
        b33.table = t34;
        b33.top = 200.0;
        b33.bottom = 270.0;
        b33.x0 = 50.0;
        b33.x1 = 540.0;
        b33.overlaps = false;
        g1.blocks.add(b33);
        WordDoc.Block b47 = new WordDoc.Block();
        WordDoc.Table t48 = new WordDoc.Table();
        t48.cols = 1;
        t48.rows = 1;
        t48.xs = new double[]{50.0, 290.0};
        t48.ys = new double[]{270.5, 300.0};
        t48.hT = new double[]{1.0, 1.0};
        t48.vT = new double[]{1.0, 1.0};
        t48.owner = new int[]{0};
        t48.marL = 2.0;
        t48.marR = 2.0;
        WordDoc.Cell c49 = new WordDoc.Cell();
        c49.c0 = 0;
        c49.c1 = 0;
        c49.r0 = 0;
        c49.r1 = 0;
        c49.x0 = 50.0;
        c49.y0 = 270.5;
        c49.x1 = 290.0;
        c49.y1 = 300.0;
        c49.bTop = 1.0;
        c49.bLeft = 1.0;
        c49.bBottom = 1.0;
        c49.bRight = 1.0;
        c49.marL = 2.0;
        c49.marR = 2.0;
        t48.cells.add(c49);
        b47.table = t48;
        b47.top = 270.5;
        b47.bottom = 300.0;
        b47.x0 = 50.0;
        b47.x1 = 290.0;
        b47.overlaps = false;
        g1.blocks.add(b47);
        WordDoc.Block b50 = new WordDoc.Block();
        WordDoc.Table t51 = new WordDoc.Table();
        t51.cols = 2;
        t51.rows = 1;
        t51.xs = new double[]{320.0, 430.0, 540.0};
        t51.ys = new double[]{280.0, 310.0};
        t51.hT = new double[]{0.75, 0.75};
        t51.vT = new double[]{0.75, 0.75, 0.75};
        t51.owner = new int[]{0, 1};
        t51.marL = 1.5;
        t51.marR = 1.5;
        WordDoc.Cell c52 = new WordDoc.Cell();
        c52.c0 = 0;
        c52.c1 = 0;
        c52.r0 = 0;
        c52.r1 = 0;
        c52.x0 = 320.0;
        c52.y0 = 280.0;
        c52.x1 = 430.0;
        c52.y1 = 310.0;
        c52.bTop = 0.75;
        c52.bLeft = 0.75;
        c52.bBottom = 0.75;
        c52.bRight = 0.75;
        c52.marL = 1.5;
        c52.marR = 1.5;
        WordDoc.Para p53 = new WordDoc.Para();
        p53.top = 283.0;
        p53.height = 12.0;
        p53.x = 322.0;
        p53.x1 = 380.0;
        p53.base = 292.6;
        p53.size = 10.0;
        p53.bold = false;
        p53.italic = false;
        p53.hasTab = false;
        p53.color = 0;
        p53.align = 0;
        WordDoc.Tok k54 = new WordDoc.Tok();
        k54.t = 0;
        k54.text = "c\u1EA1nh b\u1EA3ng";
        k54.n = 1;
        k54.spacing = 0.0;
        k54.pos = 0.0;
        k54.size = 10.0;
        k54.bold = false;
        k54.italic = false;
        k54.underline = false;
        k54.color = 0;
        p53.toks.add(k54);
        c52.paras.add(p53);
        t51.cells.add(c52);
        WordDoc.Cell c55 = new WordDoc.Cell();
        c55.c0 = 1;
        c55.c1 = 1;
        c55.r0 = 0;
        c55.r1 = 0;
        c55.x0 = 430.0;
        c55.y0 = 280.0;
        c55.x1 = 540.0;
        c55.y1 = 310.0;
        c55.bTop = 0.75;
        c55.bLeft = 0.75;
        c55.bBottom = 0.75;
        c55.bRight = 0.75;
        c55.marL = 1.5;
        c55.marR = 1.5;
        t51.cells.add(c55);
        b50.table = t51;
        b50.top = 280.0;
        b50.bottom = 310.0;
        b50.x0 = 320.0;
        b50.x1 = 540.0;
        b50.overlaps = true;
        g1.blocks.add(b50);
        WordDoc.Block b56 = new WordDoc.Block();
        WordDoc.Para p57 = new WordDoc.Para();
        p57.top = 320.0;
        p57.height = 14.625;
        p57.x = 50.0;
        p57.x1 = 300.0;
        p57.base = 331.7;
        p57.size = 13.0;
        p57.bold = false;
        p57.italic = false;
        p57.hasTab = false;
        p57.color = 0;
        p57.align = 0;
        WordDoc.Tok k58 = new WordDoc.Tok();
        k58.t = 0;
        k58.text = "Sau hai b\u1EA3ng li\u1EC1n nhau";
        k58.n = 1;
        k58.spacing = 0.0;
        k58.pos = 0.0;
        k58.size = 13.0;
        k58.bold = false;
        k58.italic = false;
        k58.underline = false;
        k58.color = 0;
        p57.toks.add(k58);
        b56.para = p57;
        b56.top = 320.0;
        b56.bottom = p57.top + p57.height;
        b56.x0 = 50.0;
        b56.x1 = 300.0;
        g1.blocks.add(b56);
        WordDoc.Pic f59 = new WordDoc.Pic();
        f59.x = 100.0;
        f59.y = 60.0;
        f59.w = 80.0;
        f59.h = 40.0;
        f59.data = new byte[]{1, 2, 3};
        f59.ext = "png";
        f59.px0 = 0;
        f59.py0 = 0;
        f59.px1 = 10;
        f59.py1 = 5;
        g1.pics.add(f59);
        WordDoc.Pic f60 = new WordDoc.Pic();
        f60.x = 10.0;
        f60.y = 500.0;
        f60.w = 50.0;
        f60.h = 50.0;
        f60.ext = "jpeg";
        f60.px0 = 0;
        f60.py0 = 0;
        f60.px1 = 1;
        f60.py1 = 1;
        g1.pics.add(f60);
        WordDoc.Pic f61 = new WordDoc.Pic();
        f61.x = 300.0;
        f61.y = 700.0;
        f61.w = 120.5;
        f61.h = 60.25;
        f61.data = new byte[]{9};
        f61.ext = "jpeg";
        f61.px0 = 5;
        f61.py0 = 6;
        f61.px1 = 7;
        f61.py1 = 8;
        g1.pics.add(f61);
        WordDoc.Rule r62 = new WordDoc.Rule();
        r62.horiz = true;
        r62.pos = 345.5;
        r62.a = 50.0;
        r62.b = 540.0;
        r62.t = 0.01;
        g1.rules.add(r62);
        WordDoc.Rule r63 = new WordDoc.Rule();
        r63.horiz = false;
        r63.pos = 560.25;
        r63.a = 100.0;
        r63.b = 400.0;
        r63.t = 3.0;
        g1.rules.add(r63);
        WordDoc.Box x64 = new WordDoc.Box();
        x64.x = 60.0;
        x64.y = 400.0;
        x64.w = 180.0;
        x64.h = 14.0;
        WordDoc.Para p65 = new WordDoc.Para();
        p65.top = 400.0;
        p65.height = 14.0;
        p65.x = 60.0;
        p65.x1 = 240.0;
        p65.base = 411.2;
        p65.size = 13.0;
        p65.bold = false;
        p65.italic = false;
        p65.hasTab = false;
        p65.color = 0;
        p65.align = 1;
        WordDoc.Tok k66 = new WordDoc.Tok();
        k66.t = 0;
        k66.text = "h\u1ED9p ch\u1EEF";
        k66.n = 1;
        k66.spacing = 0.0;
        k66.pos = 0.0;
        k66.size = 13.0;
        k66.bold = false;
        k66.italic = false;
        k66.underline = false;
        k66.color = 0;
        p65.toks.add(k66);
        WordDoc.Tok k67 = new WordDoc.Tok();
        k67.t = 1;
        k67.text = "";
        k67.n = 1;
        k67.spacing = 0.0;
        k67.pos = 0.0;
        k67.size = 13.0;
        k67.bold = false;
        k67.italic = false;
        k67.underline = false;
        k67.color = 0;
        p65.toks.add(k67);
        WordDoc.Tok k68 = new WordDoc.Tok();
        k68.t = 0;
        k68.text = "neo";
        k68.n = 1;
        k68.spacing = 0.0;
        k68.pos = 0.0;
        k68.size = 13.0;
        k68.bold = true;
        k68.italic = false;
        k68.underline = false;
        k68.color = 0;
        p65.toks.add(k68);
        x64.para = p65;
        g1.boxes.add(x64);
        pages.add(g1);
        WordDoc.Page g69 = new WordDoc.Page();
        g69.width = 842.0;
        g69.height = 595.0;
        WordDoc.Block b70 = new WordDoc.Block();
        WordDoc.Para p71 = new WordDoc.Para();
        p71.top = 1e+300;
        p71.height = Double.NaN;
        p71.x = Double.NEGATIVE_INFINITY;
        p71.x1 = Double.POSITIVE_INFINITY;
        p71.base = 0.0;
        p71.size = Double.NaN;
        p71.bold = false;
        p71.italic = false;
        p71.hasTab = true;
        p71.color = 0;
        p71.align = 0;
        WordDoc.Tok k72 = new WordDoc.Tok();
        k72.t = 0;
        k72.text = "v\u00F4 c\u1EF1c";
        k72.n = 1;
        k72.spacing = Double.POSITIVE_INFINITY;
        k72.pos = 0.0;
        k72.size = 1e+308;
        k72.bold = false;
        k72.italic = false;
        k72.underline = false;
        k72.color = 0;
        p71.toks.add(k72);
        WordDoc.Tok k73 = new WordDoc.Tok();
        k73.t = 2;
        k73.text = "";
        k73.n = 1;
        k73.spacing = 0.0;
        k73.pos = Double.NaN;
        k73.size = 13.0;
        k73.bold = false;
        k73.italic = false;
        k73.underline = false;
        k73.color = 0;
        p71.toks.add(k73);
        WordDoc.Tok k74 = new WordDoc.Tok();
        k74.t = 1;
        k74.text = "";
        k74.n = 1;
        k74.spacing = Double.NaN;
        k74.pos = 0.0;
        k74.size = Double.NaN;
        k74.bold = false;
        k74.italic = false;
        k74.underline = false;
        k74.color = 0;
        p71.toks.add(k74);
        WordDoc.Tok k75 = new WordDoc.Tok();
        k75.t = 2;
        k75.text = "";
        k75.n = 1;
        k75.spacing = 0.0;
        k75.pos = 1e+300;
        k75.size = 13.0;
        k75.bold = false;
        k75.italic = false;
        k75.underline = false;
        k75.color = 0;
        p71.toks.add(k75);
        b70.para = p71;
        b70.top = 1e+300;
        b70.bottom = p71.top + p71.height;
        b70.x0 = Double.NEGATIVE_INFINITY;
        b70.x1 = Double.POSITIVE_INFINITY;
        g69.blocks.add(b70);
        WordDoc.Block b76 = new WordDoc.Block();
        WordDoc.Para p77 = new WordDoc.Para();
        p77.top = -1e+300;
        p77.height = 1e+300;
        p77.x = Double.NaN;
        p77.x1 = Double.NaN;
        p77.base = Double.NaN;
        p77.size = 0.0;
        p77.bold = false;
        p77.italic = false;
        p77.hasTab = false;
        p77.color = 0;
        p77.align = 0;
        WordDoc.Tok k78 = new WordDoc.Tok();
        k78.t = 0;
        k78.text = "\u00E2m";
        k78.n = 1;
        k78.spacing = 0.0;
        k78.pos = 0.0;
        k78.size = -5.0;
        k78.bold = false;
        k78.italic = false;
        k78.underline = false;
        k78.color = -1;
        p77.toks.add(k78);
        b76.para = p77;
        b76.top = -1e+300;
        b76.bottom = p77.top + p77.height;
        b76.x0 = Double.NaN;
        b76.x1 = Double.NaN;
        g69.blocks.add(b76);
        WordDoc.Block b79 = new WordDoc.Block();
        WordDoc.Para p80 = new WordDoc.Para();
        p80.top = 5e+17;
        p80.height = 4e+17;
        p80.x = 1e+19;
        p80.x1 = -1e+19;
        p80.base = 0.0;
        p80.size = 4.6e+18;
        p80.bold = false;
        p80.italic = false;
        p80.hasTab = false;
        p80.color = 0;
        p80.align = 0;
        WordDoc.Tok k81 = new WordDoc.Tok();
        k81.t = 0;
        k81.text = "tr\u00E0n";
        k81.n = 1;
        k81.spacing = 0.0;
        k81.pos = 0.0;
        k81.size = 4.6e+18;
        k81.bold = false;
        k81.italic = false;
        k81.underline = false;
        k81.color = 0;
        p80.toks.add(k81);
        b79.para = p80;
        b79.top = 5e+17;
        b79.bottom = p80.top + p80.height;
        b79.x0 = 1e+19;
        b79.x1 = -1e+19;
        g69.blocks.add(b79);
        WordDoc.Block b82 = new WordDoc.Block();
        WordDoc.Table t83 = new WordDoc.Table();
        t83.cols = 2;
        t83.rows = 2;
        t83.xs = new double[]{Double.NaN, 10.0, Double.POSITIVE_INFINITY};
        t83.ys = new double[]{-1e+300, 1e+300, Double.NaN};
        t83.hT = new double[]{Double.POSITIVE_INFINITY, Double.NaN, -1.0};
        t83.vT = new double[]{Double.NaN, Double.POSITIVE_INFINITY, 0.0};
        t83.owner = new int[]{0, 1, 0, 1};
        t83.marL = Double.POSITIVE_INFINITY;
        t83.marR = Double.NaN;
        WordDoc.Cell c84 = new WordDoc.Cell();
        c84.c0 = 0;
        c84.c1 = 0;
        c84.r0 = 0;
        c84.r1 = 1;
        c84.x0 = Double.NaN;
        c84.y0 = -1e+300;
        c84.x1 = 10.0;
        c84.y1 = Double.NaN;
        c84.bTop = Double.POSITIVE_INFINITY;
        c84.bLeft = Double.NaN;
        c84.bBottom = -1.0;
        c84.bRight = 1e+300;
        c84.marL = Double.NaN;
        c84.marR = Double.NEGATIVE_INFINITY;
        WordDoc.Para p85 = new WordDoc.Para();
        p85.top = Double.NaN;
        p85.height = Double.POSITIVE_INFINITY;
        p85.x = Double.NaN;
        p85.x1 = 1e+300;
        p85.base = 0.0;
        p85.size = 13.0;
        p85.bold = false;
        p85.italic = false;
        p85.hasTab = false;
        p85.color = 0;
        p85.align = 0;
        WordDoc.Tok k86 = new WordDoc.Tok();
        k86.t = 0;
        k86.text = "\u00F4";
        k86.n = 1;
        k86.spacing = 0.0;
        k86.pos = 0.0;
        k86.size = 13.0;
        k86.bold = false;
        k86.italic = false;
        k86.underline = false;
        k86.color = 0;
        p85.toks.add(k86);
        c84.paras.add(p85);
        t83.cells.add(c84);
        WordDoc.Cell c87 = new WordDoc.Cell();
        c87.c0 = 1;
        c87.c1 = 1;
        c87.r0 = 0;
        c87.r1 = 1;
        c87.x0 = 10.0;
        c87.y0 = -1e+300;
        c87.x1 = Double.POSITIVE_INFINITY;
        c87.y1 = Double.NaN;
        c87.bTop = 0.0001;
        c87.bLeft = 12.0;
        c87.bBottom = 13.0;
        c87.bRight = 1e-300;
        c87.marL = Double.POSITIVE_INFINITY;
        c87.marR = 2.0;
        t83.cells.add(c87);
        b82.table = t83;
        b82.top = -1e+300;
        b82.bottom = Double.NaN;
        b82.x0 = Double.NaN;
        b82.x1 = Double.POSITIVE_INFINITY;
        b82.overlaps = false;
        g69.blocks.add(b82);
        WordDoc.Block b88 = new WordDoc.Block();
        WordDoc.Para p89 = new WordDoc.Para();
        p89.top = 4.6e+17;
        p89.height = 4.6e+17;
        p89.x = 0.0;
        p89.x1 = 1.0;
        p89.base = 0.0;
        p89.size = 13.0;
        p89.bold = false;
        p89.italic = false;
        p89.hasTab = false;
        p89.color = 0;
        p89.align = 0;
        WordDoc.Tok k90 = new WordDoc.Tok();
        k90.t = 0;
        k90.text = "x";
        k90.n = 1;
        k90.spacing = 0.0;
        k90.pos = 0.0;
        k90.size = 13.0;
        k90.bold = false;
        k90.italic = false;
        k90.underline = false;
        k90.color = 0;
        p89.toks.add(k90);
        b88.para = p89;
        b88.top = 4.6e+17;
        b88.bottom = p89.top + p89.height;
        b88.x0 = 0.0;
        b88.x1 = 1.0;
        g69.blocks.add(b88);
        WordDoc.Pic f91 = new WordDoc.Pic();
        f91.x = Double.NaN;
        f91.y = Double.POSITIVE_INFINITY;
        f91.w = Double.POSITIVE_INFINITY;
        f91.h = Double.NaN;
        f91.data = new byte[]{0};
        f91.ext = "png";
        f91.px0 = 0;
        f91.py0 = 0;
        f91.px1 = 0;
        f91.py1 = 0;
        g69.pics.add(f91);
        WordDoc.Pic f92 = new WordDoc.Pic();
        f92.x = -1e+300;
        f92.y = 1e+300;
        f92.w = -5.0;
        f92.h = 0.0;
        f92.data = new byte[]{1};
        f92.ext = "gif";
        f92.px0 = 0;
        f92.py0 = 0;
        f92.px1 = 0;
        f92.py1 = 0;
        g69.pics.add(f92);
        WordDoc.Rule r93 = new WordDoc.Rule();
        r93.horiz = true;
        r93.pos = Double.NaN;
        r93.a = -1e+300;
        r93.b = 1e+300;
        r93.t = Double.POSITIVE_INFINITY;
        g69.rules.add(r93);
        WordDoc.Rule r94 = new WordDoc.Rule();
        r94.horiz = false;
        r94.pos = Double.POSITIVE_INFINITY;
        r94.a = Double.NaN;
        r94.b = 5.0;
        r94.t = -1.0;
        g69.rules.add(r94);
        WordDoc.Box x95 = new WordDoc.Box();
        x95.x = Double.NaN;
        x95.y = -5.0;
        x95.w = Double.NaN;
        x95.h = -5.0;
        WordDoc.Para p96 = new WordDoc.Para();
        p96.top = 0.0;
        p96.height = 0.0;
        p96.x = 0.0;
        p96.x1 = 0.0;
        p96.base = 0.0;
        p96.size = 13.0;
        p96.bold = false;
        p96.italic = false;
        p96.hasTab = false;
        p96.color = 0;
        p96.align = 2;
        x95.para = p96;
        g69.boxes.add(x95);
        WordDoc.Box x97 = new WordDoc.Box();
        x97.x = 1e+300;
        x97.y = 1e+300;
        x97.w = 1e+300;
        x97.h = Double.POSITIVE_INFINITY;
        WordDoc.Para p98 = new WordDoc.Para();
        p98.top = Double.POSITIVE_INFINITY;
        p98.height = Double.POSITIVE_INFINITY;
        p98.x = Double.POSITIVE_INFINITY;
        p98.x1 = Double.POSITIVE_INFINITY;
        p98.base = 0.0;
        p98.size = 13.0;
        p98.bold = false;
        p98.italic = false;
        p98.hasTab = false;
        p98.color = 0;
        p98.align = 1;
        WordDoc.Tok k99 = new WordDoc.Tok();
        k99.t = 0;
        k99.text = "xa";
        k99.n = 1;
        k99.spacing = 0.0;
        k99.pos = 0.0;
        k99.size = 13.0;
        k99.bold = false;
        k99.italic = false;
        k99.underline = false;
        k99.color = 0;
        p98.toks.add(k99);
        x97.para = p98;
        g69.boxes.add(x97);
        pages.add(g69);
        WordDoc.Page g100 = new WordDoc.Page();
        g100.width = 100.0;
        g100.height = 100.0;
        WordDoc.Pic f101 = new WordDoc.Pic();
        f101.x = 0.0;
        f101.y = 0.0;
        f101.w = 100.0;
        f101.h = 100.0;
        f101.ext = "png";
        f101.px0 = 0;
        f101.py0 = 0;
        f101.px1 = 1;
        f101.py1 = 1;
        g100.pics.add(f101);
        pages.add(g100);
        WordDoc.Page g102 = new WordDoc.Page();
        g102.width = 595.276;
        g102.height = 841.89;
        WordDoc.Block b103 = new WordDoc.Block();
        WordDoc.Para p104 = new WordDoc.Para();
        p104.top = 700.0;
        p104.height = 14.625;
        p104.x = 250.0;
        p104.x1 = 320.0;
        p104.base = 711.7;
        p104.size = 13.0;
        p104.bold = false;
        p104.italic = false;
        p104.hasTab = false;
        p104.color = 0;
        p104.align = 0;
        WordDoc.Tok k105 = new WordDoc.Tok();
        k105.t = 0;
        k105.text = "ng\u1EAFn";
        k105.n = 1;
        k105.spacing = 0.0;
        k105.pos = 0.0;
        k105.size = 13.0;
        k105.bold = false;
        k105.italic = false;
        k105.underline = false;
        k105.color = 0;
        p104.toks.add(k105);
        b103.para = p104;
        b103.top = 700.0;
        b103.bottom = p104.top + p104.height;
        b103.x0 = 250.0;
        b103.x1 = 320.0;
        g102.blocks.add(b103);
        pages.add(g102);
        WordDoc.Page g106 = new WordDoc.Page();
        g106.width = 595.276;
        g106.height = 841.89;
        WordDoc.Box x107 = new WordDoc.Box();
        x107.x = 72.0;
        x107.y = 72.0;
        x107.w = 100.0;
        x107.h = 14.0;
        WordDoc.Para p108 = new WordDoc.Para();
        p108.top = 72.0;
        p108.height = 14.0;
        p108.x = 72.0;
        p108.x1 = 172.0;
        p108.base = 83.2;
        p108.size = 13.0;
        p108.bold = false;
        p108.italic = false;
        p108.hasTab = false;
        p108.color = 0;
        p108.align = 0;
        WordDoc.Tok k109 = new WordDoc.Tok();
        k109.t = 0;
        k109.text = "ch\u1EC9 c\u00F3 h\u1ED9p ch\u1EEF";
        k109.n = 1;
        k109.spacing = 0.0;
        k109.pos = 0.0;
        k109.size = 13.0;
        k109.bold = false;
        k109.italic = false;
        k109.underline = false;
        k109.color = 0;
        p108.toks.add(k109);
        x107.para = p108;
        g106.boxes.add(x107);
        pages.add(g106);
        WordDoc.Page g110 = new WordDoc.Page();
        g110.width = Double.NaN;
        g110.height = Double.POSITIVE_INFINITY;
        WordDoc.Block b111 = new WordDoc.Block();
        WordDoc.Para p112 = new WordDoc.Para();
        p112.top = 10.0;
        p112.height = 10.0;
        p112.x = 10.0;
        p112.x1 = 20.0;
        p112.base = 18.0;
        p112.size = 13.0;
        p112.bold = false;
        p112.italic = false;
        p112.hasTab = false;
        p112.color = 0;
        p112.align = 0;
        WordDoc.Tok k113 = new WordDoc.Tok();
        k113.t = 0;
        k113.text = "kh\u1ED5 l\u1EA1";
        k113.n = 1;
        k113.spacing = 0.0;
        k113.pos = 0.0;
        k113.size = 13.0;
        k113.bold = false;
        k113.italic = false;
        k113.underline = false;
        k113.color = 0;
        p112.toks.add(k113);
        b111.para = p112;
        b111.top = 10.0;
        b111.bottom = p112.top + p112.height;
        b111.x0 = 10.0;
        b111.x1 = 20.0;
        g110.blocks.add(b111);
        pages.add(g110);
        return pages;
    }

    static void writeBytes(File f, byte[] d) throws IOException {
        FileOutputStream os = new FileOutputStream(f);
        try {
            os.write(d);
        } finally {
            os.close();
        }
    }

    /**
     * Với mỗi trang <tên>.ppm + <tên>.words.tsv và mỗi khổ giấy: mô hình trang (<tên>.<khổ>.model.txt), document.xml của
     * tài liệu một trang (<tên>.<khổ>.document.xml), file .docx (docx_<khổ>/<tên>.docx); với từng hình: khung cắt + mã băm
     * điểm ảnh của Result.picture; với tài liệu gồm mọi trang: nội dung từng phần của DOCX (all.<khổ>/...) +
     * docx_<khổ>/tat_ca.docx. Các dòng tóm tắt ghi vào word_results.txt.
     */
    static void dumpWord(File in, File out) throws Exception {
        out.mkdirs();
        PrintWriter pw = new PrintWriter(new File(out, "word_results.txt"), "UTF-8");
        String[] names = in.list();
        if (names == null) throw new IOException("không đọc được " + in);
        Arrays.sort(names);
        List<List<WordDoc.Page>> all = new ArrayList<>();
        for (int m = 0; m < 3; m++) {
            all.add(new ArrayList<WordDoc.Page>());
            new File(out, "docx_" + WORD_MODES[m]).mkdirs();
            new File(out, "all." + WORD_MODES[m]).mkdirs();
        }
        long tAna = 0, tPic = 0, tXml = 0;
        int nPages = 0;
        for (String n : names) {
            if (!n.endsWith(".ppm")) continue;
            String name = n.substring(0, n.length() - 4);
            File wf = new File(in, name + ".words.tsv");
            if (!wf.exists()) continue;
            int[][] img = readPPM(new File(in, n));
            int w = img[0][0], h = img[0][1];
            List<DocLayout.Word> words = readWords(wf);
            nPages++;
            for (int m = 0; m < 3; m++) {
                String tag = name + " " + WORD_MODES[m];
                long t0 = System.nanoTime();
                DocLayout.Result r = DocLayout.analyze(img[1], w, h, words, m);
                tAna += System.nanoTime() - t0;
                WordDoc.Page page = r.page;
                t0 = System.nanoTime();
                int pi = 0;
                List<String> picLines = new ArrayList<>();
                for (WordDoc.Pic pic : page.pics) {
                    int[] pix = r.picture(pic);
                    int pwd = Math.max(1, pic.px1 - pic.px0), phd = Math.max(1, pic.py1 - pic.py0);
                    long hsh = 0xcbf29ce484222325L;
                    for (int c : pix) {
                        hsh = fnv(hsh, c >> 16);
                        hsh = fnv(hsh, c >> 8);
                        hsh = fnv(hsh, c);
                        hsh = fnv(hsh, c >>> 24);
                    }
                    pic.data = pngStored(pix, pwd, phd);
                    pic.ext = "png";
                    picLines.add("pic " + tag + " " + (pi++) + " " + pic.px0 + " " + pic.py0 + " " + pic.px1 + " " + pic.py1 + " " + pix.length
                            + " " + hex16(hsh) + " " + pic.data.length);
                }
                tPic += System.nanoTime() - t0;
                r.release();
                writeBytes(new File(out, name + "." + WORD_MODES[m] + ".model.txt"), modelText(r).getBytes("UTF-8"));
                List<WordDoc.Page> one = new ArrayList<>();
                one.add(page);
                t0 = System.nanoTime();
                byte[] xml = DocxWriter.documentXml(one).getBytes("UTF-8");
                tXml += System.nanoTime() - t0;
                writeBytes(new File(out, name + "." + WORD_MODES[m] + ".document.xml"), xml);
                int nTables = 0, nParas = 0;
                for (WordDoc.Block b : page.blocks) {
                    if (b.table != null) nTables++;
                    else nParas++;
                }
                pw.println("word " + tag + " " + w + " " + h + " words=" + words.size() + " skew=" + hx(r.skew) + " tables=" + r.tables
                        + " lines=" + r.lines + " blocks=" + page.blocks.size() + " tableBlocks=" + nTables + " paras=" + nParas + " boxes="
                        + page.boxes.size() + " pics=" + page.pics.size() + " rules=" + page.rules.size() + " xml=" + xml.length + " "
                        + hex16(fnv(xml)));
                for (String s : picLines) pw.println(s);
                writeBytes(new File(out, "docx_" + WORD_MODES[m] + "/" + name + ".docx"), DocxWriter.toBytes(one, name, WORD_TIME));
                all.get(m).add(page);
            }
        }
        for (int m = 0; m < 3; m++) {
            if (all.get(m).isEmpty()) continue;
            String title = "Tất cả các trang — " + WORD_MODES[m] + " <&\">";
            for (DocxWriter.Part p : DocxWriter.parts(all.get(m), title, WORD_TIME)) {
                writeBytes(new File(out, "all." + WORD_MODES[m] + "/" + p.name.replace('/', '~')), p.data);
                pw.println("part " + WORD_MODES[m] + " " + p.name + " " + p.data.length + " " + hex16(fnv(p.data)) + " " + (p.deflate ? 1 : 0));
            }
            writeBytes(new File(out, "docx_" + WORD_MODES[m] + "/tat_ca.docx"), DocxWriter.toBytes(all.get(m), title, WORD_TIME));
        }
        // các trang dựng tay (không qua DocLayout): từng phần của DOCX + file .docx
        {
            new File(out, "all.synthetic").mkdirs();
            new File(out, "docx_synthetic").mkdirs();
            String title = "Trang dựng tay \u0001<&>";
            for (DocxWriter.Part p : DocxWriter.parts(syntheticPages(), title, WORD_TIME)) {
                writeBytes(new File(out, "all.synthetic/" + p.name.replace('/', '~')), p.data);
                pw.println("part synthetic " + p.name + " " + p.data.length + " " + hex16(fnv(p.data)) + " " + (p.deflate ? 1 : 0));
            }
            writeBytes(new File(out, "docx_synthetic/tat_ca.docx"), DocxWriter.toBytes(syntheticPages(), title, WORD_TIME));
        }
        pw.close();
        System.out.printf(Locale.US, "Java (Word): %d trang x 3 khổ giấy, phân tích %.0f ms, cắt hình %.0f ms, document.xml %.0f ms%n", nPages,
                tAna / 1e6, tPic / 1e6, tXml / 1e6);
    }

    static List<String> readLines(File f) throws IOException {
        List<String> out = new ArrayList<>();
        for (String s : new String(readAll(f), "UTF-8").split("\n")) if (s.trim().length() > 0) out.add(s);
        return out;
    }

    static byte[] readAll(File f) throws IOException {
        FileInputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream((int) Math.max(1024, f.length()));
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        } finally {
            in.close();
        }
    }

    /** Đọc PPM (P6, 8 bit). Trả về {{w,h}, điểm ảnh ARGB}. */
    static int[][] readPPM(File f) throws IOException {
        byte[] b = readAll(f);
        int[] pos = {0};
        String magic = token(b, pos);
        int w = Integer.parseInt(token(b, pos)), h = Integer.parseInt(token(b, pos));
        int max = Integer.parseInt(token(b, pos));
        if (!magic.equals("P6") || max != 255) throw new IOException("PPM không hỗ trợ: " + f);
        int p = pos[0] + 1;
        int[] px = new int[w * h];
        for (int i = 0; i < w * h; i++) {
            px[i] = 0xFF000000 | ((b[p + 3 * i] & 255) << 16) | ((b[p + 3 * i + 1] & 255) << 8) | (b[p + 3 * i + 2] & 255);
        }
        return new int[][]{{w, h}, px};
    }

    static String token(byte[] b, int[] pos) {
        int p = pos[0];
        while (p < b.length) {
            if (b[p] == '#') { while (p < b.length && b[p] != '\n') p++; }
            else if (b[p] == ' ' || b[p] == '\n' || b[p] == '\r' || b[p] == '\t') p++;
            else break;
        }
        StringBuilder sb = new StringBuilder();
        while (p < b.length && !(b[p] == ' ' || b[p] == '\n' || b[p] == '\r' || b[p] == '\t')) sb.append((char) b[p++]);
        pos[0] = p;
        return sb.toString();
    }

    static void writePPM(File f, int[] px, int w, int h) throws IOException {
        OutputStream os = new BufferedOutputStream(new FileOutputStream(f));
        try {
            os.write(("P6\n" + w + " " + h + "\n255\n").getBytes("US-ASCII"));
            byte[] row = new byte[w * 3];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int c = px[y * w + x];
                    row[3 * x] = (byte) (c >> 16);
                    row[3 * x + 1] = (byte) (c >> 8);
                    row[3 * x + 2] = (byte) c;
                }
                os.write(row);
            }
        } finally {
            os.close();
        }
    }
}
