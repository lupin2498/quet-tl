package vn.quettailieu.scanner;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Log;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import vn.quettailieu.scanner.core.DocLayout;
import vn.quettailieu.scanner.core.DocxWriter;
import vn.quettailieu.scanner.core.WordDoc;

/**
 * Chuyển tài liệu đã quét sang Word (.docx) giữ nguyên bố cục: với từng trang (ảnh kết quả đã nắn phẳng + lọc) chạy bộ
 * nhận dạng chữ trên máy rồi core/DocLayout dàn lại — chữ thành chữ Word đúng vị trí và cỡ, bảng kẻ thành bảng Word, con
 * dấu / chữ ký / phần không đọc được giữ lại dưới dạng hình đúng chỗ; core/DocxWriter ghi file. Trang không nhận dạng
 * được (hoặc bản dựng không kèm bộ nhận dạng chữ) được đưa vào nguyên dạng ảnh.
 */
final class WordExport {
    private WordExport() {}

    static final String TAG = "QuetWord";
    static final String MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    /** Cạnh dài tối đa của ảnh trang đem nhận dạng + phân tích (khoảng 200 dpi với trang A4). */
    static final int MAX_LONG = 2400;
    /**
     * Trang độ phân giải thấp (ảnh nhập từ nguồn khác, cạnh dài dưới mức này): chữ chỉ cao 7-10 điểm ảnh, bộ nhận dạng đọc
     * sai dấu và đặt khung lệch -> phóng ảnh lên (tối đa 3 lần, tới cạnh dài MAX_LONG) riêng cho bước nhận dạng; việc dàn
     * trang vẫn dùng ảnh gốc.
     */
    static final int OCR_MIN_LONG = 1600;
    static final int JPEG_QUALITY = 90;

    /** Số liệu của một lần chuyển (để báo cho người dùng và ghi log). */
    static final class Stats {
        int pages, textPages, words, tables, pictures;
        boolean engine;
        long ms;
    }

    interface Progress {
        /** Bắt đầu trang thứ index (từ 0) trên tổng số total; trả về false để dừng. */
        boolean page(int index, int total);
    }

    static final class Cancelled extends IOException {
        Cancelled() {
            super("Đã huỷ");
        }
    }

    static String isoNow() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date());
    }

    /** Giải mã ảnh trang ở dạng ARGB, thu nhỏ để cạnh dài không quá maxLong. */
    static Bitmap decodePage(File f, int maxLong) throws IOException {
        int[] b = ImageUtil.bounds(f.getPath());
        if (b[0] <= 0 || b[1] <= 0) throw new IOException("Không đọc được ảnh trang");
        int longSide = Math.max(b[0], b[1]);
        int sample = 1;
        while (longSide / (sample * 2) >= maxLong) sample *= 2;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bm = BitmapFactory.decodeFile(f.getPath(), o);
        if (bm == null) throw new IOException("Không đọc được ảnh trang");
        if (bm.getConfig() != Bitmap.Config.ARGB_8888) {
            Bitmap c = bm.copy(Bitmap.Config.ARGB_8888, false);
            bm.recycle();
            if (c == null) throw new IOException("Không đọc được ảnh trang");
            bm = c;
        }
        int w = bm.getWidth(), h = bm.getHeight();
        int l = Math.max(w, h);
        if (l > maxLong) {
            int nw = Math.max(1, Math.round(w * (float) maxLong / l)), nh = Math.max(1, Math.round(h * (float) maxLong / l));
            Bitmap s = Bitmap.createScaledBitmap(bm, nw, nh, true);
            if (s != bm) bm.recycle();
            bm = s;
        }
        return bm;
    }

    static byte[] jpeg(Bitmap b) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream(Math.max(1024, b.getWidth() * b.getHeight() / 6));
        if (!b.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, bo)) throw new IOException("Không nén được hình");
        return bo.toByteArray();
    }

    /** Trang chỉ gồm ảnh trang. */
    static WordDoc.Page picturePage(Bitmap bmp, int sizeMode) throws IOException {
        WordDoc.Page page = DocLayout.picturePage(bmp.getWidth(), bmp.getHeight(), sizeMode);
        for (WordDoc.Pic pic : page.pics) {
            pic.data = jpeg(bmp);
            pic.ext = "jpeg";
        }
        return page;
    }

    /** Nhận dạng chữ; ảnh độ phân giải thấp được phóng lên trước, khung các từ quy về điểm ảnh của `bmp`. */
    static List<DocLayout.Word> recognize(Ocr.Engine eng, Bitmap bmp) throws Exception {
        int w = bmp.getWidth(), h = bmp.getHeight();
        int l = Math.max(w, h);
        if (l >= OCR_MIN_LONG) return eng.recognize(bmp);
        float k = Math.min(3f, (float) MAX_LONG / l);
        Bitmap big = null;
        try {
            big = Bitmap.createScaledBitmap(bmp, Math.max(1, Math.round(w * k)), Math.max(1, Math.round(h * k)), true);
        } catch (OutOfMemoryError e) {
            big = null;
        }
        if (big == null || big == bmp) return eng.recognize(bmp);
        try {
            List<DocLayout.Word> words = eng.recognize(big);
            float kx = (float) w / big.getWidth(), ky = (float) h / big.getHeight();
            for (DocLayout.Word wd : words) {
                wd.x0 *= kx;
                wd.x1 *= kx;
                wd.y0 *= ky;
                wd.y1 *= ky;
            }
            return words;
        } finally {
            big.recycle();
        }
    }

    /**
     * Thư mục ghi dữ liệu gỡ lỗi (chỉ bản debug; null ở bản phát hành): ảnh trang đem phân tích + các từ nhận dạng được,
     * đúng định dạng của android/tools/desktop/EvalWord để tái hiện việc dàn trang trên máy tính.
     */
    static File debugDir(Context c) {
        if ((c.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0) return null;
        File d = new File(c.getCacheDir(), "word_debug");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private static void dumpDebug(File dir, int index, Bitmap bmp, List<DocLayout.Word> words) {
        try {
            ImageUtil.saveJpeg(bmp, new File(dir, "p" + index + ".jpg"), 95);
            Writer wr = new OutputStreamWriter(new FileOutputStream(new File(dir, "p" + index + ".words.tsv")), "UTF-8");
            try {
                if (words != null) {
                    for (DocLayout.Word wd : words) {
                        wr.write(wd.x0 + "\t" + wd.y0 + "\t" + wd.x1 + "\t" + wd.y1 + "\t" + wd.line + "\t"
                                + wd.text.replace('\t', ' ').replace('\n', ' ') + "\t" + wd.conf + "\n");
                    }
                }
            } finally {
                wr.close();
            }
        } catch (Throwable t) {
            Log.w(TAG, "không ghi được dữ liệu gỡ lỗi", t);
        }
    }

    /** Một trang: nhận dạng chữ -> dàn trang; lỗi ở bước nào thì trang đó vào Word dưới dạng ảnh. */
    static WordDoc.Page convertPage(File f, int sizeMode, Ocr.Engine eng, Stats st, int index, File debugDir) throws IOException {
        long t0 = System.currentTimeMillis();
        Bitmap bmp;
        try {
            bmp = decodePage(f, MAX_LONG);
        } catch (OutOfMemoryError e) {
            bmp = decodePage(f, MAX_LONG / 2);
        }
        try {
            int w = bmp.getWidth(), h = bmp.getHeight();
            List<DocLayout.Word> words = null;
            if (eng != null && w >= 32 && h >= 32) {
                try {
                    words = recognize(eng, bmp);
                } catch (Throwable t) {
                    Log.w(TAG, "trang " + (index + 1) + ": nhận dạng chữ lỗi", t);
                }
            }
            if (debugDir != null) dumpDebug(debugDir, index, bmp, words);
            long t1 = System.currentTimeMillis();
            WordDoc.Page page = null;
            int tables = 0;
            if (words != null && !words.isEmpty()) {
                try {
                    int[] px = new int[w * h];
                    bmp.getPixels(px, 0, w, 0, 0, w, h);
                    DocLayout.Result r = DocLayout.analyze(px, w, h, words, sizeMode);
                    for (WordDoc.Pic pic : r.page.pics) {
                        int pw = Math.max(1, pic.px1 - pic.px0), ph = Math.max(1, pic.py1 - pic.py0);
                        Bitmap crop = Bitmap.createBitmap(r.picture(pic), pw, ph, Bitmap.Config.ARGB_8888);
                        try {
                            pic.data = jpeg(crop);
                            pic.ext = "jpeg";
                        } finally {
                            crop.recycle();
                        }
                    }
                    r.release();
                    for (WordDoc.Block b : r.page.blocks) if (b.table != null) tables++;
                    if (r.lines > 0) {
                        page = r.page;
                        st.textPages++;
                        st.tables += tables;
                        st.pictures += r.page.pics.size();
                    }
                } catch (Throwable t) {
                    // thiếu bộ nhớ / dữ liệu bất thường: trang này vào Word dưới dạng ảnh
                    Log.w(TAG, "trang " + (index + 1) + ": dàn trang lỗi", t);
                    page = null;
                }
            }
            if (page == null) page = picturePage(bmp, sizeMode);
            st.pages++;
            if (words != null) st.words += words.size();
            Log.i(TAG, "trang " + (index + 1) + ": " + w + "x" + h + ", " + (words == null ? -1 : words.size()) + " từ, " + tables + " bảng, "
                    + page.pics.size() + " hình, nhận dạng " + (t1 - t0) + " ms, dàn trang " + (System.currentTimeMillis() - t1) + " ms");
            return page;
        } finally {
            bmp.recycle();
        }
    }

    /** Chuyển các trang rồi ghi DOCX vào os (không đóng os). Gọi trên luồng nền. */
    static Stats write(List<File> pageFiles, int sizeMode, String title, OutputStream os, Progress pr, File debugDir) throws IOException {
        long t0 = System.currentTimeMillis();
        Stats st = new Stats();
        Ocr.Engine eng = Ocr.create();
        st.engine = eng != null;
        ArrayList<WordDoc.Page> pages = new ArrayList<>();
        try {
            for (int i = 0; i < pageFiles.size(); i++) {
                if (pr != null && !pr.page(i, pageFiles.size())) throw new Cancelled();
                pages.add(convertPage(pageFiles.get(i), sizeMode, eng, st, i, debugDir));
            }
        } finally {
            if (eng != null) eng.close();
        }
        if (pr != null && !pr.page(pageFiles.size(), pageFiles.size())) throw new Cancelled();
        DocxWriter.write(os, pages, title, isoNow());
        st.ms = System.currentTimeMillis() - t0;
        Log.i(TAG, "xong: " + st.pages + " trang (" + st.textPages + " trang có chữ), " + st.words + " từ, " + st.tables + " bảng, " + st.pictures
                + " hình, " + st.ms + " ms, bộ nhận dạng: " + (st.engine ? "có" : "không"));
        return st;
    }

    static File buildFile(Context c, List<File> pageFiles, int sizeMode, String title, Progress pr, Stats[] out) throws IOException {
        File f = new File(ShareProvider.shareDir(c), Ui.safeName(title) + ".docx");
        File tmp = new File(f.getPath() + ".part");
        boolean ok = false;
        OutputStream os = new BufferedOutputStream(new FileOutputStream(tmp), 256 * 1024);
        try {
            out[0] = write(pageFiles, sizeMode, title, os, pr, debugDir(c));
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
                throw new IOException("Không tạo được file Word");
            }
        }
        return f;
    }

    /** Ghi chú kèm thông báo kết quả khi có trang không chuyển được thành chữ. */
    static String note(Stats st) {
        if (st == null) return "";
        if (!st.engine) return " — bản cài này không kèm bộ nhận dạng chữ nên các trang được đưa vào dưới dạng ảnh";
        if (st.textPages == 0) return " — không nhận dạng được chữ nên các trang được đưa vào dưới dạng ảnh";
        if (st.textPages < st.pages) return " — " + (st.pages - st.textPages) + " trang không nhận dạng được chữ, giữ dạng ảnh";
        return "";
    }

    interface Job {
        void run(Progress pr) throws Exception;
    }

    /** Chạy việc chuyển đổi ở nền kèm hộp thoại chờ có số trang và nút Huỷ. */
    @SuppressWarnings("deprecation")
    static void runWithProgress(final Activity a, final Job job, final Exporter.Done done) {
        final ProgressDialog pd = new ProgressDialog(a, android.R.style.Theme_Material_Dialog_Alert);
        final boolean[] cancel = new boolean[1];
        pd.setMessage("Đang chuyển sang Word…");
        pd.setCancelable(false);
        pd.setButton(DialogInterface.BUTTON_NEGATIVE, "Huỷ", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface d, int which) {
                cancel[0] = true;
            }
        });
        try {
            pd.show();
        } catch (RuntimeException ignored) {
            // màn hình vừa đóng: vẫn chạy tác vụ, chỉ không hiện hộp thoại chờ
        }
        final Progress pr = new Progress() {
            @Override
            public boolean page(final int index, final int total) {
                if (cancel[0]) return false;
                Ui.MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            if (pd.isShowing()) {
                                pd.setMessage(index < total ? "Đang chuyển sang Word… (trang " + (index + 1) + "/" + total + ")" : "Đang ghi file Word…");
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                });
                return true;
            }
        };
        Processor.EXEC.execute(new Runnable() {
            @Override
            public void run() {
                Throwable err = null;
                try {
                    job.run(pr);
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

    private static String errorText(Throwable t) {
        if (t instanceof OutOfMemoryError) return "không đủ bộ nhớ";
        return Ui.msg(t);
    }

    /** Chuyển sang Word rồi mở bảng chia sẻ. */
    static void share(final Activity a, final DocStore.Doc d) {
        final ArrayList<File> pages;
        try {
            pages = Exporter.pageFiles(d);
        } catch (IOException e) {
            Ui.toast(a, "Lỗi tạo file Word: " + Ui.msg(e));
            return;
        }
        final String title = d.title;
        final int sizeMode = Exporter.pdfSize(a);
        final File[] holder = new File[1];
        final Stats[] stats = new Stats[1];
        runWithProgress(a, new Job() {
            @Override
            public void run(Progress pr) throws Exception {
                holder[0] = buildFile(a, pages, sizeMode, title, pr, stats);
            }
        }, new Exporter.Done() {
            @Override
            public void run(Throwable error) {
                if (error instanceof Cancelled) return;
                if (error != null) {
                    Ui.toast(a, "Lỗi tạo file Word: " + errorText(error));
                    return;
                }
                String note = note(stats[0]);
                if (note.length() > 0) Ui.toast(a, "Đã tạo file Word" + note);
                Uri u = ShareProvider.uriFor(holder[0]);
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType(MIME);
                send.putExtra(Intent.EXTRA_STREAM, u);
                send.putExtra(Intent.EXTRA_SUBJECT, title);
                send.setClipData(ClipData.newRawUri(title, u));
                send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                Exporter.startChooser(a, send, "Chia sẻ file Word");
            }
        });
    }

    /** Chuyển sang Word rồi ghi vào Uri do người dùng chọn (ACTION_CREATE_DOCUMENT). */
    static void saveTo(final Activity a, final DocStore.Doc d, final Uri target) {
        final ContentResolver cr = a.getContentResolver();
        final ArrayList<File> pages;
        try {
            pages = Exporter.pageFiles(d);
        } catch (IOException e) {
            Exporter.deleteTarget(cr, target);
            Ui.toast(a, "Lỗi lưu file Word: " + Ui.msg(e));
            return;
        }
        final String title = d.title;
        final int sizeMode = Exporter.pdfSize(a);
        final Stats[] stats = new Stats[1];
        final File dbg = debugDir(a);
        runWithProgress(a, new Job() {
            @Override
            public void run(Progress pr) throws Exception {
                boolean ok = false;
                try {
                    // chuyển xong mới mở file đích: huỷ / lỗi giữa chừng không để lại file dở
                    ByteArrayOutputStream bo = new ByteArrayOutputStream(256 * 1024);
                    stats[0] = write(pages, sizeMode, title, bo, pr, dbg);
                    OutputStream os = cr.openOutputStream(target, "w");
                    if (os == null) throw new IOException("Không ghi được file");
                    try {
                        bo.writeTo(os);
                    } finally {
                        os.close();
                    }
                    ok = true;
                } finally {
                    // ghi dở / huỷ -> xoá file rỗng vừa tạo ở nơi người dùng chọn
                    if (!ok) Exporter.deleteTarget(cr, target);
                }
            }
        }, new Exporter.Done() {
            @Override
            public void run(Throwable error) {
                if (error instanceof Cancelled) {
                    Ui.toast(a, "Đã huỷ chuyển sang Word");
                    return;
                }
                Ui.toast(a, error == null ? "Đã lưu file Word" + note(stats[0]) : "Lỗi lưu file Word: " + errorText(error));
            }
        });
    }
}
