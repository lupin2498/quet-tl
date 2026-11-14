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

/** Xuất PDF / ảnh, chia sẻ, lưu vào máy (xuất Word: WordExport). */
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

    /** Danh sách file ảnh kết quả của các trang (chụp lại tại thời điểm gọi, trên luồng chính). */
    static ArrayList<File> pageFiles(DocStore.Doc d) throws IOException {
        ArrayList<File> out = new ArrayList<>();
        for (DocStore.Page p : d.pages) {
            if (!d.outReady(p)) throw new IOException("Trang chưa xử lý xong");
            out.add(d.file(p.out));
        }
        if (out.isEmpty()) throw new IOException("Tài liệu không có trang");
        return out;
    }

    /** Ghi PDF theo luồng: ảnh từng trang được chép thẳng từ đĩa, không nạp cả tài liệu vào bộ nhớ. */
    static void writePdf(List<File> pages, int sizeMode, String title, OutputStream os) throws IOException {
        List<PdfWriter.Source> src = new ArrayList<>();
        for (File f : pages) src.add(new PdfWriter.FileSource(f));
        PdfWriter.writeSources(os, src, sizeMode, title);
    }

    static File buildPdf(Context c, List<File> pages, int sizeMode, String title) throws IOException {
        File f = new File(ShareProvider.shareDir(c), Ui.safeName(title) + ".pdf");
        File tmp = new File(f.getPath() + ".part");
        boolean ok = false;
        OutputStream os = new BufferedOutputStream(new FileOutputStream(tmp), 256 * 1024);
        try {
            writePdf(pages, sizeMode, title, os);
            ok = true;
        } finally {
            try {
                os.close();
            } catch (IOException e) {
                ok = false;
            }
            if (!ok) tmp.delete();
        }
        if (!tmp.renameTo(f)) {
            f.delete();
            if (!tmp.renameTo(f)) {
                tmp.delete();
                throw new IOException("Không tạo được file PDF");
            }
        }
        return f;
    }

    static ArrayList<File> buildImages(Context c, List<File> pages, String title) throws IOException {
        ArrayList<File> out = new ArrayList<>();
        String base = Ui.safeName(title);
        int i = 1;
        for (File src : pages) {
            File f = new File(ShareProvider.shareDir(c), pages.size() == 1 ? base + ".jpg" : base + "_" + i + ".jpg");
            DocStore.copy(new FileInputStream(src), f);
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
        try {
            pd.show();
        } catch (RuntimeException ignored) {
            // màn hình vừa đóng: vẫn chạy tác vụ, chỉ không hiện hộp thoại chờ
        }
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

    /** Mở bảng chia sẻ; nếu ứng dụng đang ở nền thì đợi người dùng quay lại (Android chặn mở màn hình từ nền). */
    static void startChooser(final Activity a, final Intent send, final String title) {
        Runnable r = new Runnable() {
            @Override
            public void run() {
                try {
                    a.startActivity(Intent.createChooser(send, title));
                } catch (Throwable t) {
                    Ui.toast(a, "Không có ứng dụng để chia sẻ");
                }
            }
        };
        if (a instanceof BaseActivity) ((BaseActivity) a).runWhenResumed(r);
        else r.run();
    }

    static void sharePdf(final Activity a, final DocStore.Doc d) {
        final ArrayList<File> pages;
        try {
            pages = pageFiles(d);
        } catch (IOException e) {
            Ui.toast(a, "Lỗi tạo PDF: " + Ui.msg(e));
            return;
        }
        final String title = d.title;
        final int sizeMode = pdfSize(a);
        final File[] holder = new File[1];
        runWithProgress(a, "Đang tạo PDF…", new Callable() {
            @Override
            public void call() throws Exception {
                holder[0] = buildPdf(a, pages, sizeMode, title);
            }
        }, new Done() {
            @Override
            public void run(Throwable error) {
                if (error != null) {
                    Ui.toast(a, "Lỗi tạo PDF: " + Ui.msg(error));
                    return;
                }
                Uri u = ShareProvider.uriFor(holder[0]);
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType("application/pdf");
                send.putExtra(Intent.EXTRA_STREAM, u);
                send.putExtra(Intent.EXTRA_SUBJECT, title);
                send.setClipData(ClipData.newRawUri(title, u));
                send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startChooser(a, send, "Chia sẻ PDF");
            }
        });
    }

    static void shareImages(final Activity a, final DocStore.Doc d) {
        final ArrayList<File> pages;
        try {
            pages = pageFiles(d);
        } catch (IOException e) {
            Ui.toast(a, "Lỗi: " + Ui.msg(e));
            return;
        }
        final String title = d.title;
        final List<File> files = new ArrayList<>();
        runWithProgress(a, "Đang chuẩn bị ảnh…", new Callable() {
            @Override
            public void call() throws Exception {
                files.addAll(buildImages(a, pages, title));
            }
        }, new Done() {
            @Override
            public void run(Throwable error) {
                if (error != null || files.isEmpty()) {
                    Ui.toast(a, "Lỗi: " + (error == null ? "không có trang" : Ui.msg(error)));
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
                ClipData cd = ClipData.newRawUri(title, uris.get(0));
                for (int i = 1; i < uris.size(); i++) cd.addItem(new ClipData.Item(uris.get(i)));
                send.setClipData(cd);
                send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startChooser(a, send, "Chia sẻ ảnh");
            }
        });
    }

    /** Ghi PDF vào Uri do người dùng chọn (ACTION_CREATE_DOCUMENT). */
    static void savePdfTo(final Activity a, final DocStore.Doc d, final Uri target) {
        final ContentResolver cr = a.getContentResolver();
        final ArrayList<File> pages;
        try {
            pages = pageFiles(d);
        } catch (IOException e) {
            deleteTarget(cr, target);
            Ui.toast(a, "Lỗi lưu PDF: " + Ui.msg(e));
            return;
        }
        final String title = d.title;
        final int sizeMode = pdfSize(a);
        runWithProgress(a, "Đang lưu PDF…", new Callable() {
            @Override
            public void call() throws Exception {
                boolean ok = false;
                try {
                    OutputStream os = cr.openOutputStream(target, "w");
                    if (os == null) throw new IOException("Không ghi được file");
                    OutputStream bo = new BufferedOutputStream(os, 256 * 1024);
                    try {
                        writePdf(pages, sizeMode, title, bo);
                    } finally {
                        bo.close();
                    }
                    ok = true;
                } finally {
                    // ghi dở -> xoá file rỗng/hỏng vừa tạo ở nơi người dùng chọn
                    if (!ok) deleteTarget(cr, target);
                }
            }
        }, new Done() {
            @Override
            public void run(Throwable error) {
                Ui.toast(a, error == null ? "Đã lưu PDF" : "Lỗi lưu PDF: " + Ui.msg(error));
            }
        });
    }

    static void deleteTarget(ContentResolver cr, Uri target) {
        try {
            android.provider.DocumentsContract.deleteDocument(cr, target);
        } catch (Throwable ignored) {
        }
    }

    /** Lưu ảnh các trang vào Thư viện (Pictures/QuetTaiLieu). */
    static void saveImagesToGallery(final Activity a, final DocStore.Doc d) {
        final ArrayList<File> pages;
        try {
            pages = pageFiles(d);
        } catch (IOException e) {
            Ui.toast(a, "Lỗi lưu ảnh: " + Ui.msg(e));
            return;
        }
        final String base = Ui.safeName(d.title);
        final int[] count = new int[1];
        runWithProgress(a, "Đang lưu ảnh…", new Callable() {
            @Override
            public void call() throws Exception {
                int i = 1;
                for (File src : pages) {
                    String name = pages.size() == 1 ? base : base + "_" + i;
                    i++;
                    saveOneImage(a, src, name);
                    count[0]++;
                }
            }
        }, new Done() {
            @Override
            public void run(Throwable error) {
                if (error != null) Ui.toast(a, "Lỗi lưu ảnh: " + Ui.msg(error) + (count[0] > 0 ? " (đã lưu " + count[0] + " ảnh)" : ""));
                else Ui.toast(a, "Đã lưu " + count[0] + " ảnh vào Thư viện (Pictures/QuetTaiLieu)");
            }
        });
    }

    /** Lưu một ảnh vào Thư viện với tên `base`.jpg (tự thêm " (2)", " (3)"… nếu đã có file trùng tên). */
    private static void saveOneImage(Context c, File src, String base) throws IOException {
        if (Build.VERSION.SDK_INT >= 29) {
            // MediaStore tự đổi tên nếu trùng
            ContentResolver cr = c.getContentResolver();
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, base + ".jpg");
            v.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
            v.put("relative_path", Environment.DIRECTORY_PICTURES + "/QuetTaiLieu");
            v.put("is_pending", 1);
            Uri uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new IOException("Không tạo được ảnh trong Thư viện");
            boolean ok = false;
            try {
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
                ok = true;
            } finally {
                // ghi dở -> xoá mục đang chờ, không để lại ảnh hỏng trong Thư viện
                if (!ok) {
                    try {
                        cr.delete(uri, null, null);
                    } catch (Throwable ignored) {
                    }
                }
            }
        } else {
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "QuetTaiLieu");
            if (!dir.exists() && !dir.mkdirs()) throw new IOException("Không tạo được thư mục ảnh");
            // không ghi đè ảnh đã lưu trước đó của tài liệu khác trùng tên
            File f = new File(dir, base + ".jpg");
            for (int k = 2; f.exists() && k < 1000; k++) f = new File(dir, base + " (" + k + ").jpg");
            boolean ok = false;
            try {
                DocStore.copy(new FileInputStream(src), f);
                ok = true;
            } finally {
                if (!ok) f.delete();
            }
            MediaScannerConnection.scanFile(c, new String[]{f.getPath()}, new String[]{"image/jpeg"}, null);
        }
    }
}
