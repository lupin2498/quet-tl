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

    static double bits(String s) {
        return Double.longBitsToDouble(Long.parseUnsignedLong(s, 16));
    }

    static String cpText(String s) {
        StringBuilder sb = new StringBuilder();
        if (!s.equals("-")) for (String c : s.split(",")) sb.appendCodePoint(Integer.parseInt(c, 16));
        return sb.toString();
    }

    static double[] bitsArray(String[] f) {
        double[] v = new double[f.length - 1];
        for (int i = 1; i < f.length; i++) v[i - 1] = bits(f[i]);
        return v;
    }

    /**
     * Các trang DỰNG TAY (file <thư-mục>/synthetic.wordmodel do tests/tools/make_word_junk.py ghi; số thực ở dạng bit) để
     * đối chiếu DocxWriter ở những nhánh mà kết quả của DocLayout không đi qua: lề ô khác lề bảng, bảng đứng cạnh bảng khác
     * (hộp chữ chứa bảng), hai bảng liền nhau, tab trùng / trước lề, dấu cách lặp, hình không có dữ liệu, trang trống / chỉ
     * có hộp chữ, và giá trị bất thường (NaN, vô cực, rất lớn: ép kiểu bão hoà, cộng trừ long quay vòng).
     */
    static List<WordDoc.Page> readModel(File f) throws IOException {
        List<WordDoc.Page> pages = new ArrayList<>();
        WordDoc.Page page = null;
        WordDoc.Para para = null;
        WordDoc.Table table = null;
        WordDoc.Cell cell = null;
        WordDoc.Box box = null;
        for (String line : readLines(f)) {
            String[] t = line.trim().split(" ");
            String k = t[0];
            if (k.equals("page")) {
                page = new WordDoc.Page();
                page.width = bits(t[1]);
                page.height = bits(t[2]);
                pages.add(page);
            } else if (k.equals("bpara") || k.equals("cpara") || k.equals("xpara")) {
                para = new WordDoc.Para();
                para.top = bits(t[1]);
                para.height = bits(t[2]);
                para.x = bits(t[3]);
                para.x1 = bits(t[4]);
                para.base = bits(t[5]);
                para.size = bits(t[6]);
                para.bold = t[7].equals("1");
                para.italic = t[8].equals("1");
                para.hasTab = t[9].equals("1");
                para.color = Integer.parseInt(t[10]);
                para.align = Integer.parseInt(t[11]);
                if (k.equals("cpara")) {
                    cell.paras.add(para);
                } else if (k.equals("xpara")) {
                    box.para = para;
                } else {
                    WordDoc.Block b = new WordDoc.Block();
                    b.para = para;
                    b.top = para.top;
                    b.bottom = para.top + para.height;
                    b.x0 = para.x;
                    b.x1 = para.x1;
                    page.blocks.add(b);
                }
            } else if (k.equals("tok")) {
                WordDoc.Tok tk = new WordDoc.Tok();
                tk.t = Integer.parseInt(t[1]);
                tk.n = Integer.parseInt(t[2]);
                tk.spacing = bits(t[3]);
                tk.pos = bits(t[4]);
                tk.size = bits(t[5]);
                tk.bold = t[6].equals("1");
                tk.italic = t[7].equals("1");
                tk.underline = t[8].equals("1");
                tk.color = Integer.parseInt(t[9]);
                tk.text = cpText(t[10]);
                para.toks.add(tk);
            } else if (k.equals("table")) {
                table = new WordDoc.Table();
                table.cols = Integer.parseInt(t[1]);
                table.rows = Integer.parseInt(t[2]);
                table.marL = bits(t[3]);
                table.marR = bits(t[4]);
                WordDoc.Block b = new WordDoc.Block();
                b.table = table;
                b.overlaps = t[5].equals("1");
                b.top = bits(t[6]);
                b.bottom = bits(t[7]);
                b.x0 = bits(t[8]);
                b.x1 = bits(t[9]);
                page.blocks.add(b);
            } else if (k.equals("xs")) {
                table.xs = bitsArray(t);
            } else if (k.equals("ys")) {
                table.ys = bitsArray(t);
            } else if (k.equals("hT")) {
                table.hT = bitsArray(t);
            } else if (k.equals("vT")) {
                table.vT = bitsArray(t);
            } else if (k.equals("owner")) {
                table.owner = new int[t.length - 1];
                for (int i = 1; i < t.length; i++) table.owner[i - 1] = Integer.parseInt(t[i]);
            } else if (k.equals("cell")) {
                cell = new WordDoc.Cell();
                cell.c0 = Integer.parseInt(t[1]);
                cell.c1 = Integer.parseInt(t[2]);
                cell.r0 = Integer.parseInt(t[3]);
                cell.r1 = Integer.parseInt(t[4]);
                cell.x0 = bits(t[5]);
                cell.y0 = bits(t[6]);
                cell.x1 = bits(t[7]);
                cell.y1 = bits(t[8]);
                cell.bTop = bits(t[9]);
                cell.bLeft = bits(t[10]);
                cell.bBottom = bits(t[11]);
                cell.bRight = bits(t[12]);
                cell.marL = bits(t[13]);
                cell.marR = bits(t[14]);
                table.cells.add(cell);
            } else if (k.equals("pic")) {
                WordDoc.Pic pc = new WordDoc.Pic();
                pc.x = bits(t[1]);
                pc.y = bits(t[2]);
                pc.w = bits(t[3]);
                pc.h = bits(t[4]);
                pc.px0 = Integer.parseInt(t[5]);
                pc.py0 = Integer.parseInt(t[6]);
                pc.px1 = Integer.parseInt(t[7]);
                pc.py1 = Integer.parseInt(t[8]);
                pc.ext = t[9];
                if (!t[10].equals("-")) {
                    String[] d = t[10].split(",");
                    pc.data = new byte[d.length];
                    for (int i = 0; i < d.length; i++) pc.data[i] = (byte) Integer.parseInt(d[i]);
                }
                page.pics.add(pc);
            } else if (k.equals("rule")) {
                WordDoc.Rule r = new WordDoc.Rule();
                r.horiz = t[1].equals("1");
                r.pos = bits(t[2]);
                r.a = bits(t[3]);
                r.b = bits(t[4]);
                r.t = bits(t[5]);
                page.rules.add(r);
            } else if (k.equals("box")) {
                box = new WordDoc.Box();
                box.x = bits(t[1]);
                box.y = bits(t[2]);
                box.w = bits(t[3]);
                box.h = bits(t[4]);
                page.boxes.add(box);
            }
        }
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
        File mf = new File(in, "synthetic.wordmodel");
        if (mf.exists()) {
            new File(out, "all.synthetic").mkdirs();
            new File(out, "docx_synthetic").mkdirs();
            String title = "Trang dựng tay \u0001<&>";
            for (DocxWriter.Part p : DocxWriter.parts(readModel(mf), title, WORD_TIME)) {
                writeBytes(new File(out, "all.synthetic/" + p.name.replace('/', '~')), p.data);
                pw.println("part synthetic " + p.name + " " + p.data.length + " " + hex16(fnv(p.data)) + " " + (p.deflate ? 1 : 0));
            }
            writeBytes(new File(out, "docx_synthetic/tat_ca.docx"), DocxWriter.toBytes(readModel(mf), title, WORD_TIME));
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
