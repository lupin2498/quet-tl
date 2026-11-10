package vn.quettailieu.scanner;

import android.graphics.Bitmap;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import vn.quettailieu.scanner.core.DocDetector;
import vn.quettailieu.scanner.core.Enhancer;
import vn.quettailieu.scanner.core.Geom;

/** Xử lý ảnh nền: tìm vùng trang, nắn phẳng, lọc. */
final class Processor {
    private Processor() {}

    /** Hàng đợi xử lý nặng (tuần tự). */
    static final ExecutorService EXEC = Executors.newSingleThreadExecutor();
    /** Giải mã ảnh để hiển thị. */
    static final ExecutorService IO = Executors.newFixedThreadPool(2);

    static final int MAX_SRC = 4096;
    static final int MAX_OUT = 3508;   // A4 ở 300 dpi
    static final int DETECT_DIM = 1024;

    static final class Detection {
        float[] quad;
        boolean found;
        int[] size;
    }

    /** Tìm 4 góc trang giấy; toạ độ theo ảnh gốc đã xoay EXIF. */
    static Detection detect(String path) {
        int[] size = ImageUtil.orientedSize(path);
        Bitmap b = null;
        try {
            b = ImageUtil.decodeOriented(path, DETECT_DIM);
        } catch (Throwable ignored) {
        }
        if (b == null) {
            Detection d = new Detection();
            d.size = size;
            d.quad = Geom.fullQuad(Math.max(1, size[0]), Math.max(1, size[1]));
            return d;
        }
        if (size[0] <= 0 || size[1] <= 0) size = new int[]{b.getWidth(), b.getHeight()};
        Detection d = detect(b, size[0], size[1]);
        b.recycle();
        return d;
    }

    /** Tìm 4 góc trên ảnh đã giải mã (tỷ lệ bất kỳ so với ảnh gốc fullW x fullH). */
    static Detection detect(Bitmap src, int fullW, int fullH) {
        Detection d = new Detection();
        d.size = new int[]{fullW, fullH};
        Bitmap b = src;
        boolean own = false;
        try {
            int L = Math.max(src.getWidth(), src.getHeight());
            if (L > DETECT_DIM) {
                float s = DETECT_DIM / (float) L;
                b = Bitmap.createScaledBitmap(src, Math.max(16, Math.round(src.getWidth() * s)), Math.max(16, Math.round(src.getHeight() * s)), true);
                own = b != src;
            }
            int w = b.getWidth(), h = b.getHeight();
            DocDetector.Result r = DocDetector.detect(ImageUtil.pixels(b), w, h);
            if (r != null && r.quad != null) {
                d.quad = Geom.scale(r.quad, fullW / (float) w, fullH / (float) h);
                Geom.clamp(d.quad, fullW, fullH);
                d.found = true;
            }
        } catch (Throwable ignored) {
        } finally {
            if (own) b.recycle();
        }
        if (d.quad == null) d.quad = Geom.fullQuad(Math.max(1, fullW), Math.max(1, fullH));
        return d;
    }

    static String warpName(DocStore.Page p) {
        int h = (p.src == null ? 0 : p.src.hashCode()) * 31 + quadHash(p.quad);
        return "w_" + p.id + "_" + Integer.toHexString(h) + ".jpg";
    }

    static String outName(DocStore.Page p) {
        int h = warpName(p).hashCode();
        h = h * 31 + p.filter;
        h = h * 31 + p.rot;
        return "o_" + p.id + "_" + Integer.toHexString(h) + ".jpg";
    }

    private static int quadHash(float[] q) {
        if (q == null) return 0;
        int[] r = new int[8];
        for (int i = 0; i < 8; i++) r[i] = Math.round(q[i] * 4f);
        return Arrays.hashCode(r);
    }

    /**
     * Tạo ảnh kết quả cho trang (chạy trên luồng nền). Thao tác trên bản sao của trang;
     * tên file được suy ra từ tham số nên có thể dùng lại kết quả cũ.
     */
    static void render(File dir, DocStore.Page snap) throws IOException {
        String wn = warpName(snap), on = outName(snap);
        File outF = new File(dir, on);
        if (outF.exists() && outF.length() > 0) {
            snap.warp = wn;
            snap.out = on;
            return;
        }
        // Nhanh: đã có ảnh kết quả cùng bộ lọc nhưng khác góc xoay -> chỉ cần xoay
        int wantRot = snap.rot;
        for (int r = 0; r < 360; r += 90) {
            if (r == wantRot) continue;
            snap.rot = r;
            File alt = new File(dir, outName(snap));
            snap.rot = wantRot;
            if (!alt.exists() || alt.length() == 0) continue;
            Bitmap b = null;
            try {
                b = android.graphics.BitmapFactory.decodeFile(alt.getPath());
            } catch (OutOfMemoryError ignored) {
            }
            if (b == null) continue;
            Bitmap rb = ImageUtil.rotate(b, wantRot - r);
            if (rb != b) b.recycle();
            ImageUtil.saveJpeg(rb, outF, 90);
            rb.recycle();
            snap.warp = wn;
            snap.out = on;
            return;
        }
        File wf = new File(dir, wn);
        Bitmap warped = null;
        if (wf.exists()) {
            try {
                warped = ImageUtil.decodeMutable(wf.getPath());
            } catch (OutOfMemoryError e) {
                warped = null;
            }
        }
        if (warped == null) {
            warped = makeWarp(dir, snap);
            ImageUtil.saveJpeg(warped, wf, 92);
        }
        if (snap.filter != Enhancer.ORIGINAL) {
            int w = warped.getWidth(), h = warped.getHeight();
            int[] px = new int[w * h];
            warped.getPixels(px, 0, w, 0, 0, w, h);
            Enhancer.apply(px, w, h, snap.filter);
            if (!warped.isMutable()) {
                Bitmap m = warped.copy(Bitmap.Config.ARGB_8888, true);
                warped.recycle();
                warped = m;
            }
            warped.setPixels(px, 0, w, 0, 0, w, h);
        }
        Bitmap fin = ImageUtil.rotate(warped, snap.rot);
        if (fin != warped) warped.recycle();
        ImageUtil.saveJpeg(fin, outF, 90);
        fin.recycle();
        snap.warp = wn;
        snap.out = on;
    }

    private static Bitmap makeWarp(File dir, DocStore.Page p) throws IOException {
        String srcPath = new File(dir, p.src).getPath();
        Bitmap src = ImageUtil.decodeOriented(srcPath, MAX_SRC);
        if (src == null) throw new IOException("Không đọc được ảnh gốc");
        try {
            int sw = src.getWidth(), sh = src.getHeight();
            int fw = p.srcW > 0 ? p.srcW : sw, fh = p.srcH > 0 ? p.srcH : sh;
            float kx = sw / (float) fw, ky = sh / (float) fh;
            float[] q = p.quad != null ? Geom.scale(p.quad, kx, ky) : Geom.fullQuad(sw, sh);
            if (!Geom.isConvex(q)) q = Geom.fullQuad(sw, sh);
            double focal = p.focal > 0 ? p.focal * kx : -1;
            int[] os = Geom.outputSize(q, sw, sh, focal, true, MAX_OUT);
            return ImageUtil.warp(src, q, os[0], os[1]);
        } finally {
            src.recycle();
        }
    }

    /** Danh sách file đang được tham chiếu (gọi trên luồng chính). */
    static java.util.HashSet<String> referenced(DocStore.Doc d) {
        java.util.HashSet<String> keep = new java.util.HashSet<>();
        keep.add("doc.json");
        for (DocStore.Page p : d.pages) {
            if (p.src != null) keep.add(p.src);
            if (p.warp != null) keep.add(p.warp);
            if (p.out != null) keep.add(p.out);
        }
        return keep;
    }

    /** Xoá file trong thư mục tài liệu không còn được tham chiếu (chỉ gọi khi không còn việc đang chờ). */
    static void gc(final File dir, final java.util.Set<String> keep) {
        EXEC.execute(new Runnable() {
            @Override
            public void run() {
                File[] fs = dir.listFiles();
                if (fs == null) return;
                // chạy trên cùng hàng đợi với việc xử lý ảnh nên lúc này không có file .tmp nào đang được ghi
                for (File f : fs) {
                    String n = f.getName();
                    if (!keep.contains(n) && (n.startsWith("w_") || n.startsWith("o_"))) f.delete();
                }
            }
        });
    }
}
