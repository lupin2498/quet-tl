package vn.quettailieu.scanner;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.media.ExifInterface;
import android.net.Uri;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import vn.quettailieu.scanner.core.Geom;

/** Giải mã/xoay/nắn phẳng/lưu ảnh. */
final class ImageUtil {
    private ImageUtil() {}

    static int exifDegrees(String path) {
        try {
            ExifInterface ex = new ExifInterface(path);
            int o = ex.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            switch (o) {
                case ExifInterface.ORIENTATION_ROTATE_90:
                case ExifInterface.ORIENTATION_TRANSPOSE:
                    return 90;
                case ExifInterface.ORIENTATION_ROTATE_180:
                case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                    return 180;
                case ExifInterface.ORIENTATION_ROTATE_270:
                case ExifInterface.ORIENTATION_TRANSVERSE:
                    return 270;
                default:
                    return 0;
            }
        } catch (Throwable e) {
            return 0;
        }
    }

    static int[] bounds(String path) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, o);
        return new int[]{o.outWidth, o.outHeight};
    }

    /** Kích thước ảnh sau khi xoay theo EXIF. */
    static int[] orientedSize(String path) {
        int[] b = bounds(path);
        int deg = exifDegrees(path);
        if (deg == 90 || deg == 270) return new int[]{b[1], b[0]};
        return b;
    }

    /** Giải mã ảnh, xoay theo EXIF, cạnh dài không vượt quá maxDim. */
    static Bitmap decodeOriented(String path, int maxDim) {
        int[] b = bounds(path);
        if (b[0] <= 0 || b[1] <= 0) return null;
        int deg = exifDegrees(path);
        int longSide = Math.max(b[0], b[1]);
        int sample = 1;
        while (longSide / (sample * 2) >= maxDim) sample *= 2;
        // ảnh rất lớn (48–200 MP): bản giải mã đầy đủ có thể chiếm phần lớn bộ nhớ -> giảm mẫu thêm
        // sao cho ảnh giải mã không quá 1/4 bộ nhớ tối đa của ứng dụng
        long budget = Math.max(48L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 4);
        while (4L * b[0] * b[1] / ((long) sample * sample) > budget && sample < 64) sample *= 2;
        Bitmap bm = null;
        for (int attempt = 0; attempt < 3 && bm == null; attempt++) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            try {
                bm = BitmapFactory.decodeFile(path, o);
            } catch (OutOfMemoryError e) {
                bm = null;
                sample *= 2;
                System.gc();
            }
            if (bm == null && attempt == 0 && o.outWidth <= 0) return null;
        }
        if (bm == null) return null;
        int L = Math.max(bm.getWidth(), bm.getHeight());
        float s = L > maxDim ? (float) maxDim / L : 1f;
        if (s < 0.999f || deg != 0) {
            Matrix m = new Matrix();
            if (s < 0.999f) m.postScale(s, s);
            if (deg != 0) m.postRotate(deg);
            try {
                Bitmap r = Bitmap.createBitmap(bm, 0, 0, bm.getWidth(), bm.getHeight(), m, true);
                if (r != bm) bm.recycle();
                bm = r;
            } catch (OutOfMemoryError e) {
                // giữ ảnh chưa thu nhỏ/xoay nếu thiếu bộ nhớ (hiếm)
                if (deg != 0) return null;
            }
        }
        return bm;
    }

    /** Giải mã ảnh do ứng dụng tạo (không có EXIF), giảm mẫu để cạnh dài ~ maxDim. */
    static Bitmap decodeSampled(String path, int maxDim) {
        int[] b = bounds(path);
        if (b[0] <= 0 || b[1] <= 0) return null;
        int longSide = Math.max(b[0], b[1]);
        int sample = 1;
        while (longSide / (sample * 2) >= maxDim) sample *= 2;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        try {
            return BitmapFactory.decodeFile(path, o);
        } catch (OutOfMemoryError e) {
            o.inSampleSize = sample * 2;
            try {
                return BitmapFactory.decodeFile(path, o);
            } catch (OutOfMemoryError e2) {
                return null;
            }
        }
    }

    static Bitmap decodeMutable(String path) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inMutable = true;
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeFile(path, o);
    }

    /** Nắn phẳng vùng tứ giác (toạ độ theo src) thành ảnh chữ nhật outW x outH. */
    static Bitmap warp(Bitmap src, float[] quad, int outW, int outH) {
        Bitmap out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        c.drawColor(Color.WHITE);
        Matrix m = new Matrix();
        float[] dst = {0, 0, outW, 0, outW, outH, 0, outH};
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        if (m.setPolyToPoly(quad, 0, dst, 0, 4)) {
            c.drawBitmap(src, m, p);
        } else {
            m.setScale(outW / (float) src.getWidth(), outH / (float) src.getHeight());
            c.drawBitmap(src, m, p);
        }
        return out;
    }

    static Bitmap rotate(Bitmap b, int deg) {
        deg = ((deg % 360) + 360) % 360;
        if (deg == 0) return b;
        Matrix m = new Matrix();
        m.postRotate(deg);
        return Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
    }

    static void saveJpeg(Bitmap b, File f, int quality) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        OutputStream os = new BufferedOutputStream(new FileOutputStream(tmp), 256 * 1024);
        try {
            if (!b.compress(Bitmap.CompressFormat.JPEG, quality, os)) throw new IOException("Không ghi được ảnh");
        } finally {
            os.close();
        }
        if (!tmp.renameTo(f)) {
            f.delete();
            if (!tmp.renameTo(f)) throw new IOException("Không đổi tên được file ảnh");
        }
    }

    static int[] pixels(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        return px;
    }

    /** Nắn phẳng nhanh ở độ phân giải thấp (xem trước / ảnh thu nhỏ). */
    static Bitmap quickWarp(String srcPath, int srcW, int srcH, float[] quad, int maxLong) {
        Bitmap src = decodeOriented(srcPath, Math.max(400, maxLong + maxLong / 2));
        if (src == null) return null;
        if (quad == null || srcW <= 0 || srcH <= 0) return src;
        float sx = src.getWidth() / (float) srcW, sy = src.getHeight() / (float) srcH;
        float[] q = Geom.scale(quad, sx, sy);
        int[] os = Geom.outputSize(q, src.getWidth(), src.getHeight(), -1, true, maxLong);
        Bitmap w = warp(src, q, os[0], os[1]);
        src.recycle();
        return w;
    }

    static File captureDir(Context c) {
        File d = new File(c.getCacheDir(), "captures");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    static File newCaptureFile(Context c) {
        return new File(captureDir(c), "cap_" + System.currentTimeMillis() + "_" + (int) (Math.random() * 1000) + ".jpg");
    }

    /** Sao chép ảnh từ Uri (thư viện, ứng dụng khác) vào file tạm. */
    static File importUri(Context c, Uri uri) throws IOException {
        InputStream in = c.getContentResolver().openInputStream(uri);
        if (in == null) throw new IOException("Không mở được ảnh");
        File f = newCaptureFile(c);
        try {
            DocStore.copy(in, f);
        } catch (IOException e) {
            f.delete();
            throw e;
        } catch (RuntimeException e) {
            // nhà cung cấp nội dung của ứng dụng khác có thể ném lỗi bất kỳ khi đang đọc
            f.delete();
            throw new IOException("Không đọc được ảnh");
        }
        int[] b = bounds(f.getPath());
        if (b[0] <= 0 || b[1] <= 0) {
            f.delete();
            throw new IOException("Định dạng ảnh không hỗ trợ");
        }
        // phần đầu file hợp lệ chưa chắc giải mã được (file hỏng) -> thử giải mã bản thu nhỏ;
        // nếu không được thì từ chối ngay, tránh tạo trang không bao giờ xử lý xong
        Bitmap probe = null;
        try {
            probe = decodeSampled(f.getPath(), 64);
        } catch (Throwable ignored) {
        }
        if (probe == null) {
            f.delete();
            throw new IOException("Ảnh bị hỏng hoặc không giải mã được");
        }
        // JPEG bị cắt cụt gần như ngay sau phần đầu: bộ giải mã vẫn trả về ảnh nhưng hầu hết chỉ là màu tô bù
        // (trang đen/xám vô dụng) -> từ chối. Ảnh cụt nhưng còn phần nhìn thấy được thì vẫn nhận.
        boolean blank = false;
        try {
            blank = mostlyFill(probe) && isJpegWithoutEnd(f);
        } catch (Throwable ignored) {
        }
        probe.recycle();
        if (blank) {
            f.delete();
            throw new IOException("Ảnh bị hỏng (thiếu dữ liệu)");
        }
        return f;
    }

    /** Ít nhất 95% điểm ảnh trùng màu với điểm cuối cùng (phần bộ giải mã tô bù khi file thiếu dữ liệu). */
    static boolean mostlyFill(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        if (w <= 0 || h <= 0) return true;
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        int fill = px[px.length - 1];
        int same = 0;
        for (int v : px) if (v == fill) same++;
        return same >= px.length * 0.95;
    }

    /** File JPEG (bắt đầu bằng FF D8) mà trong 64 KB cuối không có dấu kết thúc ảnh FF D9. */
    static boolean isJpegWithoutEnd(File f) throws IOException {
        java.io.RandomAccessFile r = new java.io.RandomAccessFile(f, "r");
        try {
            long len = r.length();
            if (len < 4 || r.read() != 0xFF || r.read() != 0xD8) return false;
            int n = (int) Math.min(len, 65536);
            byte[] tail = new byte[n];
            r.seek(len - n);
            r.readFully(tail);
            for (int i = n - 2; i >= 0; i--) {
                if ((tail[i] & 0xFF) == 0xFF && (tail[i + 1] & 0xFF) == 0xD9) return false;
            }
            return true;
        } finally {
            r.close();
        }
    }

    static void cleanCache(Context c) {
        long now = System.currentTimeMillis();
        File[] fs = captureDir(c).listFiles();
        if (fs != null) for (File f : fs) if (now - f.lastModified() > 24L * 3600 * 1000) f.delete();
        File share = new File(c.getCacheDir(), "share");
        File[] ss = share.listFiles();
        if (ss != null) for (File f : ss) if (now - f.lastModified() > 3600L * 1000) f.delete();
    }
}
