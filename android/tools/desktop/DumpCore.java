import vn.quettailieu.scanner.core.DocDetector;
import vn.quettailieu.scanner.core.Enhancer;
import vn.quettailieu.scanner.core.Geom;
import vn.quettailieu.scanner.core.PdfWriter;
import vn.quettailieu.scanner.core.Retouch;

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
 * để đối chiếu với bản Swift (ios/Tests/linux/main.swift --dump) bằng tests/tools/compare_core.py.
 *
 * Cách chạy: java -cp out DumpCore <thư-mục-dữ-liệu> <thư-mục-kết-quả>
 */
public class DumpCore {
    static final String TITLE = "Biên bản thử nghiệm số 12 — ấệữ 😀";

    public static void main(String[] a) throws Exception {
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
            for (int mode : new int[]{Enhancer.MAGIC, Enhancer.LIGHTEN, Enhancer.GRAY, Enhancer.BW,
                    Enhancer.MAGIC_PRO, Enhancer.DENOISE, Enhancer.NO_SHADOW, Enhancer.NO_HAND}) {
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
                if (full || (rt && mode >= Enhancer.MAGIC_PRO)) writePPM(new File(out, name + "_m" + mode + ".ppm"), c, w, h);
            }
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
