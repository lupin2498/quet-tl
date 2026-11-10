package vn.quettailieu.scanner.core;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Ghi PDF tối giản, nhúng trực tiếp dữ liệu JPEG (DCTDecode) nên file nhỏ và nhanh.
 * Ảnh từng trang được chép theo luồng (không giữ cả tài liệu trong bộ nhớ).
 * Java thuần, không phụ thuộc Android.
 */
public final class PdfWriter {
    private PdfWriter() {}

    public static final int SIZE_A4 = 0;
    public static final int SIZE_LETTER = 1;
    public static final int SIZE_FIT = 2;

    private static final Charset ASCII = Charset.forName("US-ASCII");

    /** Thông tin cơ bản của ảnh JPEG đọc từ phần đầu (SOF). */
    public static final class JpegInfo {
        public int width, height, components;
    }

    /** Nguồn dữ liệu JPEG của một trang. */
    public interface Source {
        long length() throws IOException;

        InputStream open() throws IOException;
    }

    /** Trang lấy từ file JPEG trên đĩa. */
    public static final class FileSource implements Source {
        private final File file;

        public FileSource(File f) {
            file = f;
        }

        @Override
        public long length() {
            return file.length();
        }

        @Override
        public InputStream open() throws IOException {
            return new FileInputStream(file);
        }
    }

    private static final class BytesSource implements Source {
        private final byte[] data;

        BytesSource(byte[] d) {
            data = d;
        }

        @Override
        public long length() {
            return data == null ? 0 : data.length;
        }

        @Override
        public InputStream open() {
            return new ByteArrayInputStream(data == null ? new byte[0] : data);
        }
    }

    public static JpegInfo readJpegInfo(byte[] d) {
        if (d == null) return null;
        try {
            return readJpegInfo(new ByteArrayInputStream(d));
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Đọc kích thước ảnh từ phần đầu JPEG (đi qua từng đoạn cho tới SOF, không đọc cả file).
     * Trả về null nếu không phải JPEG hợp lệ.
     */
    public static JpegInfo readJpegInfo(InputStream in) throws IOException {
        if (in.read() != 0xFF || in.read() != 0xD8) return null;
        int b = in.read();
        while (b >= 0) {
            if (b != 0xFF) {
                b = in.read();
                continue;
            }
            int marker = in.read();
            while (marker == 0xFF) marker = in.read();
            if (marker < 0) return null;
            if (marker == 0xD8 || marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) {
                b = in.read();
                continue;
            }
            int l1 = in.read(), l2 = in.read();
            if (l2 < 0) return null;
            int len = (l1 << 8) | l2;
            boolean sof = (marker >= 0xC0 && marker <= 0xCF) && marker != 0xC4 && marker != 0xC8 && marker != 0xCC;
            if (sof) {
                int[] v = new int[6];
                for (int i = 0; i < 6; i++) {
                    v[i] = in.read();
                    if (v[i] < 0) return null;
                }
                JpegInfo info = new JpegInfo();
                info.height = (v[1] << 8) | v[2];
                info.width = (v[3] << 8) | v[4];
                info.components = v[5];
                if (info.width <= 0 || info.height <= 0) return null;
                if (info.components != 1 && info.components != 3 && info.components != 4) return null;
                return info;
            }
            if (marker == 0xDA) return null;
            long skip = len - 2;
            while (skip > 0) {
                long s = in.skip(skip);
                if (s <= 0) {
                    if (in.read() < 0) return null;
                    s = 1;
                }
                skip -= s;
            }
            b = in.read();
        }
        return null;
    }

    private static final class Counter extends OutputStream {
        final OutputStream out;
        long count;
        Counter(OutputStream o) { out = o; }
        @Override public void write(int b) throws IOException { out.write(b); count++; }
        @Override public void write(byte[] b, int off, int len) throws IOException { out.write(b, off, len); count += len; }
        @Override public void flush() throws IOException { out.flush(); }
    }

    /**
     * @param jpegs  dữ liệu JPEG của từng trang
     * @param sizeMode SIZE_A4 / SIZE_LETTER / SIZE_FIT (khổ trang theo đúng tỷ lệ ảnh)
     */
    public static void write(OutputStream os, List<byte[]> jpegs, int sizeMode, String title) throws IOException {
        List<Source> src = new ArrayList<>();
        for (byte[] j : jpegs) src.add(new BytesSource(j));
        writeSources(os, src, sizeMode, title);
    }

    /** Ghi PDF, ảnh từng trang được đọc từ nguồn và chép thẳng ra luồng. */
    public static void writeSources(OutputStream os, List<? extends Source> pages, int sizeMode, String title) throws IOException {
        Counter out = new Counter(os);
        List<Long> offsets = new ArrayList<>();
        int n = pages.size();
        if (n == 0) throw new IOException("Tài liệu không có trang");
        // Số đối tượng: 1 catalog, 2 pages, 3 info, rồi mỗi trang 3 đối tượng (page, content, image)
        int total = 3 + 3 * n;
        for (int i = 0; i <= total; i++) offsets.add(0L);
        str(out, "%PDF-1.4\n");
        out.write(new byte[]{'%', (byte) 0xE2, (byte) 0xE3, (byte) 0xCF, (byte) 0xD3, '\n'});

        offsets.set(1, out.count);
        str(out, "1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n");

        offsets.set(2, out.count);
        StringBuilder kids = new StringBuilder();
        for (int i = 0; i < n; i++) kids.append(4 + 3 * i).append(" 0 R ");
        str(out, "2 0 obj\n<< /Type /Pages /Kids [ " + kids + "] /Count " + n + " >>\nendobj\n");

        offsets.set(3, out.count);
        str(out, "3 0 obj\n<< /Producer " + pdfText("Quét Tài Liệu") + " /Title " + pdfText(title == null ? "" : title) + " >>\nendobj\n");

        byte[] buf = new byte[64 * 1024];
        for (int i = 0; i < n; i++) {
            Source src = pages.get(i);
            JpegInfo info;
            InputStream hin = new BufferedInputStream(src.open(), 16 * 1024);
            try {
                info = readJpegInfo(hin);
            } finally {
                hin.close();
            }
            if (info == null) throw new IOException("Ảnh trang " + (i + 1) + " không hợp lệ");
            long length = src.length();
            int pageObj = 4 + 3 * i, contentObj = pageObj + 1, imgObj = pageObj + 2;
            double iw = info.width, ih = info.height;
            double pw, ph;
            if (sizeMode == SIZE_FIT) {
                double k = 842.0 / Math.max(iw, ih);
                // ảnh rất dẹt: mỗi cạnh trang tối thiểu 3 pt (giới hạn của nhiều trình đọc PDF)
                pw = Math.max(3.0, iw * k);
                ph = Math.max(3.0, ih * k);
            } else {
                double a = sizeMode == SIZE_LETTER ? 612.0 : 595.276;
                double b = sizeMode == SIZE_LETTER ? 792.0 : 841.89;
                if (iw > ih) { pw = b; ph = a; } else { pw = a; ph = b; }
            }
            double scale = Math.min(pw / iw, ph / ih);
            double dw = iw * scale, dh = ih * scale;
            double ox = (pw - dw) / 2, oy = (ph - dh) / 2;

            offsets.set(pageObj, out.count);
            str(out, pageObj + " 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 " + f(pw) + " " + f(ph) + "]"
                    + " /Resources << /XObject << /Im0 " + imgObj + " 0 R >> /ProcSet [/PDF /ImageC /ImageB] >>"
                    + " /Contents " + contentObj + " 0 R >>\nendobj\n");

            String content = "q " + f(dw) + " 0 0 " + f(dh) + " " + f(ox) + " " + f(oy) + " cm /Im0 Do Q\n";
            byte[] cb = content.getBytes(ASCII);
            offsets.set(contentObj, out.count);
            str(out, contentObj + " 0 obj\n<< /Length " + cb.length + " >>\nstream\n");
            out.write(cb);
            str(out, "endstream\nendobj\n");

            String cs = info.components == 1 ? "/DeviceGray" : info.components == 4 ? "/DeviceCMYK" : "/DeviceRGB";
            offsets.set(imgObj, out.count);
            str(out, imgObj + " 0 obj\n<< /Type /XObject /Subtype /Image /Width " + info.width + " /Height " + info.height
                    + " /ColorSpace " + cs + " /BitsPerComponent 8 /Filter /DCTDecode /Length " + length + " >>\nstream\n");
            long copied = 0;
            InputStream in = src.open();
            try {
                int r;
                while ((r = in.read(buf)) > 0) {
                    out.write(buf, 0, r);
                    copied += r;
                }
            } finally {
                in.close();
            }
            if (copied != length) throw new IOException("Ảnh trang " + (i + 1) + " thay đổi trong lúc ghi");
            str(out, "\nendstream\nendobj\n");
        }

        long xref = out.count;
        StringBuilder sb = new StringBuilder();
        sb.append("xref\n0 ").append(total + 1).append("\n");
        sb.append("0000000000 65535 f \n");
        for (int i = 1; i <= total; i++) sb.append(String.format(Locale.US, "%010d 00000 n \n", offsets.get(i)));
        sb.append("trailer\n<< /Size ").append(total + 1).append(" /Root 1 0 R /Info 3 0 R >>\nstartxref\n").append(xref).append("\n%%EOF\n");
        str(out, sb.toString());
        out.flush();
    }

    private static String f(double v) {
        return String.format(Locale.US, "%.2f", v);
    }

    /** Chuỗi văn bản PDF dạng UTF-16BE (hỗ trợ tiếng Việt). */
    private static String pdfText(String s) {
        StringBuilder sb = new StringBuilder("<FEFF");
        for (int i = 0; i < s.length(); i++) sb.append(String.format(Locale.US, "%04X", (int) s.charAt(i)));
        return sb.append(">").toString();
    }

    private static void str(OutputStream o, String s) throws IOException {
        o.write(s.getBytes(ASCII));
    }

    /** Tiện ích cho kiểm thử. */
    public static byte[] toBytes(List<byte[]> jpegs, int mode, String title) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        write(bo, jpegs, mode, title);
        return bo.toByteArray();
    }
}
