package vn.quettailieu.scanner;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

import vn.quettailieu.scanner.core.PdfWriter;

/** Xuất PDF / ảnh, chia sẻ, lưu vào máy. */
final class Exporter {
    private Exporter() {}

    static final String PREFS = "settings";
    static final String KEY_PDF_SIZE = "pdf_size";

    static int pdfSize(Context c) {
        return c.getSharedPreferences(PREFS, 0).getInt(KEY_PDF_SIZE, PdfWriter.SIZE_A4);
    }

    static void setPdfSize(Context c, int v) {
        c.getSharedPreferences(PREFS, 0).edit().putInt(KEY_PDF_SIZE, v).apply();
    }

    static String pdfSizeName(int v) {
        switch (v) {
            case PdfWriter.SIZE_LETTER: return "Letter";
            case PdfWriter.SIZE_FIT: return "Theo ảnh";
            default: return "A4";
        }
    }

    static void writePdf(Context c, DocStore.Doc d, OutputStream os) throws IOException {
        List<byte[]> jpgs = new ArrayList<>();
        for (DocStore.Page p : d.pages) {
            if (!d.outReady(p)) throw new IOException("Trang chưa xử lý xong");
            jpgs.add(DocStore.readBytes(d.file(p.out)));
        }
        PdfWriter.write(os, jpgs, pdfSize(c), d.title);
    }

    static File buildPdf(Context c, DocStore.Doc d) throws IOException {
        File f = new File(ShareProvider.shareDir(c), Ui.safeName(d.title) + ".pdf");
        OutputStream os = new BufferedOutputStream(new FileOutputStream(f), 256 * 1024);
        try {
            writePdf(c, d, os);
        } finally {
            os.close();
        }
        return f;
    }

    static ArrayList<File> buildImages(Context c, DocStore.Doc d) throws IOException {
        ArrayList<File> out = new ArrayList<>();
        String base = Ui.safeName(d.title);
        int i = 1;
        for (DocStore.Page p : d.pages) {
            if (!d.outReady(p)) throw new IOException("Trang chưa xử lý xong");
            File f = new File(ShareProvider.shareDir(c), d.pages.size() == 1 ? base + ".jpg" : base + "_" + i + ".jpg");
            DocStore.copy(new FileInputStream(d.file(p.out)), f);
            out.add(f);
            i++;
        }
        return out;
    }

    interface Done {
        void run(Throwable error);
    }

    /** Chạy tác vụ nền kèm hộp thoại chờ. */
    @SuppressWarnings("deprecation")
    static void runWithProgress(final Activity a, String msg, final Callable job, final Done done) {
        final ProgressDialog pd = new ProgressDialog(a, android.R.style.Theme_Material_Dialog_Alert);
        pd.setMessage(msg);
        pd.setCancelable(false);
        pd.show();
        Processor.EXEC.execute(new Runnable() {
            @Override
            public void run() {
                Throwable err = null;
                try {
                    job.call();
                } catch (Throwable t) {
                    err = t;
                }
                final Throwable e = err;
                Ui.MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            pd.dismiss();
                        } catch (Throwable ignored) {
                        }
                        if (Ui.alive(a)) done.run(e);
                    }
                });
            }
        });
    }

    interface Callable {
        void call() throws Exception;
    }

    static void sharePdf(final Activity a, final DocStore.Doc d) {
        final File[] holder = new File[1];
        runWithProgress(a, "Đang tạo PDF…", new Callable() {
            @Override
            public void call() throws Exception {
                holder[0] = buildPdf(a, d);
            }
        }, new Done() {
            @Override
            public void run(Throwable error) {
                if (error != null) {
                    Ui.toast(a, "Lỗi tạo PDF: " + error.getMessage());
                    return;
                }
                Uri u = ShareProvider.uriFor(holder[0]);
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType("application/pdf");
                send.putExtra(Intent.EXTRA_STREAM, u);
                send.putExtra(Intent.EXTRA_SUBJECT, d.title);
                send.setClipData(ClipData.newRawUri(d.title, u));
                send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try {
                    a.startActivity(Intent.createChooser(send, "Chia sẻ PDF"));
                } catch (Throwable t) {
                    Ui.toast(a, "Không có ứng dụng để chia sẻ");
                }
            }
        });
    }

    static void shareImages(final Activity a, final DocStore.Doc d) {
        final List<File> files = new ArrayList<>();
        runWithProgress(a, "Đang chuẩn bị ảnh…", new Callable() {
            @Override
            public void call() throws Exception {
                files.addAll(buildImages(a, d));
            }
        }, new Done() {
            @Override
            public void run(Throwable error) {
                if (error != null || files.isEmpty()) {
                    Ui.toast(a, "Lỗi: " + (error == null ? "không có trang" : error.getMessage()));
                    return;
                }
                Intent send;
                ArrayList<Uri> uris = new ArrayList<>();
                for (File f : files) uris.add(ShareProvider.uriFor(f));
                if (uris.size() == 1) {
                    send = new Intent(Intent.ACTION_SEND);
                    send.putExtra(Intent.EXTRA_STREAM, uris.get(0));
                } else {
                    send = new Intent(Intent.ACTION_SEND_MULTIPLE);
                    send.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
                }
                send.setType("image/jpeg");
                ClipData cd = ClipData.newRawUri(d.title, uris.get(0));
                for (int i = 1; i < uris.size(); i++) cd.addItem(new ClipData.Item(uris.get(i)));
                send.setClipData(cd);
                send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try {
                    a.startActivity(Intent.createChooser(send, "Chia sẻ ảnh"));
                } catch (Throwable t) {
                    Ui.toast(a, "Không có ứng dụng để chia sẻ");
                }
            }
        });
    }

    /** Ghi PDF vào Uri do người dùng chọn (ACTION_CREATE_DOCUMENT). */
    static void savePdfTo(final Activity a, final DocStore.Doc d, final Uri target) {
        runWithProgress(a, "Đang lưu PDF…", new Callable() {
            @Override
            public void call() throws Exception {
                OutputStream os = a.getContentResolver().openOutputStream(target, "w");
                if (os == null) throw new IOException("Không ghi được file");
                OutputStream bo = new BufferedOutputStream(os, 256 * 1024);
                try {
                    writePdf(a, d, bo);
                } finally {
                    bo.close();
                }
            }
        }, new Done() {
            @Override
            public void run(Throwable error) {
                Ui.toast(a, error == null ? "Đã lưu PDF" : "Lỗi lưu PDF: " + error.getMessage());
            }
        });
    }

    /** Lưu ảnh các trang vào Thư viện (Pictures/QuetTaiLieu). */
    static void saveImagesToGallery(final Activity a, final DocStore.Doc d) {
        final int[] count = new int[1];
        runWithProgress(a, "Đang lưu ảnh…", new Callable() {
            @Override
            public void call() throws Exception {
                String base = Ui.safeName(d.title);
                int i = 1;
                for (DocStore.Page p : d.pages) {
                    if (!d.outReady(p)) continue;
                    String name = d.pages.size() == 1 ? base + ".jpg" : base + "_" + i + ".jpg";
                    i++;
                    saveOneImage(a, d.file(p.out), name);
                    count[0]++;
                }
            }
        }, new Done() {
            @Override
            public void run(Throwable error) {
                if (error != null) Ui.toast(a, "Lỗi lưu ảnh: " + error.getMessage());
                else Ui.toast(a, "Đã lưu " + count[0] + " ảnh vào Thư viện (Pictures/QuetTaiLieu)");
            }
        });
    }

    private static void saveOneImage(Context c, File src, String name) throws IOException {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentResolver cr = c.getContentResolver();
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
            v.put("relative_path", Environment.DIRECTORY_PICTURES + "/QuetTaiLieu");
            v.put("is_pending", 1);
            Uri uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new IOException("Không tạo được ảnh trong Thư viện");
            OutputStream os = cr.openOutputStream(uri);
            if (os == null) throw new IOException("Không ghi được ảnh");
            InputStream in = new FileInputStream(src);
            try {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            } finally {
                in.close();
                os.close();
            }
            ContentValues done = new ContentValues();
            done.put("is_pending", 0);
            cr.update(uri, done, null, null);
        } else {
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "QuetTaiLieu");
            if (!dir.exists() && !dir.mkdirs()) throw new IOException("Không tạo được thư mục ảnh");
            File f = new File(dir, name);
            DocStore.copy(new FileInputStream(src), f);
            MediaScannerConnection.scanFile(c, new String[]{f.getPath()}, new String[]{"image/jpeg"}, null);
        }
    }
}
