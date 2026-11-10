package vn.quettailieu.scanner;

import android.Manifest;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.io.File;
import java.util.HashSet;

import vn.quettailieu.scanner.core.PdfWriter;

/** Xem tài liệu đã lưu: danh sách trang, Thêm, Sửa, Chia sẻ, Lưu PDF, Lưu ảnh. */
public class DocumentActivity extends BaseActivity {
    static final String EXTRA_DOC = "doc";

    private static final int REQ_EDIT = 31;
    private static final int REQ_ADD = 32;
    private static final int REQ_SAVE_PDF = 33;
    private static final int REQ_STORAGE = 34;

    private DocStore.Doc doc;
    private TextView titleView;
    private ListView list;
    private PageAdapter adapter;
    private LruCache<String, Bitmap> cache;
    private final HashSet<String> loading = new HashSet<>();
    private final HashSet<String> rendering = new HashSet<>();
    /** Trang xử lý lỗi (ảnh gốc hỏng/mất): khoá = id + tên ảnh gốc, để thử lại sau khi chụp lại. */
    private final HashSet<String> failed = new HashSet<>();
    private int itemWidth;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        doc = DocStore.open(this, getIntent().getStringExtra(EXTRA_DOC));
        if (doc == null) {
            Ui.toast(this, "Không mở được tài liệu");
            finish();
            return;
        }
        int maxKb = (int) Math.min(Integer.MAX_VALUE, Runtime.getRuntime().maxMemory() / 1024 / 7);
        cache = new LruCache<String, Bitmap>(Math.max(12 * 1024, maxKb)) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return Math.max(1, value.getByteCount() / 1024);
            }
        };
        itemWidth = getResources().getDisplayMetrics().widthPixels - Ui.dp(32);
        buildUi();
    }

    @Override
    protected void onStart() {
        super.onStart();
        // danh sách trang có thể đã đổi ở màn hình khác (dùng chung tài liệu) -> báo cho ListView trước khi nó vẽ lại
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (doc == null) return;
        // các màn hình dùng chung một đối tượng tài liệu nên thay đổi ở trình chỉnh sửa / màn hình chụp đã có sẵn ở đây
        DocStore.Doc d2 = DocStore.open(this, doc.id);
        if (d2 == null) {
            finish();
            return;
        }
        doc = d2;
        if (doc.pages.isEmpty()) {
            // đã xoá hết trang -> không để lại tài liệu rỗng
            DocStore.delete(doc);
            finish();
            return;
        }
        titleView.setText(doc.title);
        adapter.notifyDataSetChanged();
        for (DocStore.Page p : doc.pages) {
            if (needsRender(p)) renderMissing(p);
        }
    }

    private boolean needsRender(DocStore.Page p) {
        return !doc.outReady(p) || !Processor.outName(p).equals(p.out);
    }

    private static String failKey(DocStore.Page p) {
        return p.id + "/" + p.src;
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG_DARK);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(Ui.dp(4), 0, Ui.dp(4), 0);
        top.addView(Ui.desc(Ui.iconButton(this, R.drawable.ic_back, Color.WHITE, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        }), "Quay lại"));
        titleView = Ui.text(this, doc.title, 18, Ui.TEXT, true);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        titleView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                rename();
            }
        });
        top.addView(titleView, Ui.lin(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        ImageView edit = Ui.iconButton(this, R.drawable.ic_edit, 0xFFCFCED3, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                rename();
            }
        });
        edit.setContentDescription("Đổi tên");
        top.addView(edit, Ui.lin(Ui.dp(40), Ui.dp(48), 0));
        final ImageView more = Ui.desc(Ui.iconButton(this, R.drawable.ic_more, Color.WHITE, null), "Tùy chọn khác");
        more.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showMore(more);
            }
        });
        top.addView(more);
        root.addView(top, Ui.lin(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(56), 0));

        list = new ListView(this);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setSelector(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        list.setPadding(0, Ui.dp(4), 0, Ui.dp(12));
        list.setClipToPadding(false);
        adapter = new PageAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                openEditor(position);
            }
        });
        root.addView(list, Ui.lin(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(Ui.CARD);
        bar.addView(Ui.toolButton(this, R.drawable.ic_add_photo, "Thêm", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent i = new Intent(DocumentActivity.this, CameraActivity.class);
                i.putExtra(CameraActivity.EXTRA_DOC, doc.id);
                startActivityForResult(i, REQ_ADD);
            }
        }));
        bar.addView(Ui.toolButton(this, R.drawable.ic_edit, "Sửa", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openEditor(Math.max(0, list.getFirstVisiblePosition()));
            }
        }));
        bar.addView(Ui.toolButton(this, R.drawable.ic_share, "Chia sẻ", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                share();
            }
        }));
        bar.addView(Ui.toolButton(this, R.drawable.ic_pdf, "Lưu PDF", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                savePdf();
            }
        }));
        bar.addView(Ui.toolButton(this, R.drawable.ic_download, "Lưu ảnh", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveImages();
            }
        }));
        root.addView(bar, Ui.lin(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(64), 0));
        setContentView(root);
    }

    private boolean ready() {
        if (doc.pages.isEmpty()) {
            Ui.toast(this, "Tài liệu không có trang");
            return false;
        }
        for (int i = 0; i < doc.pages.size(); i++) {
            DocStore.Page p = doc.pages.get(i);
            if (rendering.contains(p.id)) {
                Ui.toast(this, "Đang xử lý trang, vui lòng đợi giây lát");
                return false;
            }
            if (needsRender(p)) {
                if (failed.contains(failKey(p))) {
                    // ảnh gốc hỏng: chờ thêm cũng không xong -> nói rõ cách xử lý
                    Ui.toast(this, "Trang " + (i + 1) + " không xử lý được — hãy mở Sửa để chụp lại hoặc xoá trang này");
                } else {
                    Ui.toast(this, "Đang xử lý trang, vui lòng đợi giây lát");
                    renderMissing(p);
                }
                return false;
            }
        }
        return true;
    }

    private void openEditor(int index) {
        Intent i = new Intent(this, EditorActivity.class);
        i.putExtra(EditorActivity.EXTRA_DOC, doc.id);
        i.putExtra(EditorActivity.EXTRA_PAGE, index);
        startActivityForResult(i, REQ_EDIT);
    }

    private void share() {
        if (!ready()) return;
        String[] items = {"Chia sẻ PDF (" + Exporter.pdfSizeName(Exporter.pdfSize(this)) + ")", "Chia sẻ ảnh JPG"};
        Ui.showDialog(this, Ui.dialog(this).setTitle("Chia sẻ").setItems(items, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface d, int which) {
                if (which == 0) Exporter.sharePdf(DocumentActivity.this, doc);
                else Exporter.shareImages(DocumentActivity.this, doc);
            }
        }));
    }

    private void savePdf() {
        if (!ready()) return;
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/pdf");
        i.putExtra(Intent.EXTRA_TITLE, Ui.safeName(doc.title) + ".pdf");
        try {
            startActivityForResult(i, REQ_SAVE_PDF);
        } catch (Throwable t) {
            Exporter.sharePdf(this, doc);
        }
    }

    private void saveImages() {
        if (!ready()) return;
        if (Build.VERSION.SDK_INT < 29 && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
            return;
        }
        Exporter.saveImagesToGallery(this, doc);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (requestCode == REQ_STORAGE) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) Exporter.saveImagesToGallery(this, doc);
            else Ui.toast(this, "Cần quyền bộ nhớ để lưu ảnh");
        }
    }

    private void rename() {
        Ui.prompt(this, "Đổi tên tài liệu", doc.title, new Ui.Cb<String>() {
            @Override
            public void done(String v) {
                if (v.equals(doc.title)) return;
                doc.title = v;
                doc.modified = System.currentTimeMillis();
                DocStore.save(doc);
                titleView.setText(v);
            }
        });
    }

    private void showMore(View anchor) {
        String[] items = {"Đổi tên", "Khổ giấy PDF: " + Exporter.pdfSizeName(Exporter.pdfSize(this)), "Lưu PDF vào máy", "Lưu ảnh vào Thư viện", "Xoá tài liệu"};
        Ui.menu(this, null, items, new Ui.Cb<Integer>() {
            @Override
            public void done(Integer which) {
                switch (which) {
                    case 0: rename(); break;
                    case 1: choosePdfSize(); break;
                    case 2: savePdf(); break;
                    case 3: saveImages(); break;
                    case 4: confirmDelete(); break;
                }
            }
        });
    }

    private void choosePdfSize() {
        final int[] vals = {PdfWriter.SIZE_A4, PdfWriter.SIZE_LETTER, PdfWriter.SIZE_FIT};
        String[] names = {"A4 (210 × 297 mm)", "Letter (216 × 279 mm)", "Theo kích thước ảnh"};
        int cur = Exporter.pdfSize(this);
        int sel = 0;
        for (int i = 0; i < vals.length; i++) if (vals[i] == cur) sel = i;
        Ui.showDialog(this, Ui.dialog(this).setTitle("Khổ giấy PDF").setSingleChoiceItems(names, sel, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface d, int which) {
                Exporter.setPdfSize(DocumentActivity.this, vals[which]);
                d.dismiss();
            }
        }));
    }

    private void confirmDelete() {
        Ui.confirm(this, "Xoá tài liệu?", "\"" + doc.title + "\" sẽ bị xoá vĩnh viễn.", "Xoá", new Runnable() {
            @Override
            public void run() {
                DocStore.delete(doc);
                finish();
            }
        });
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (doc == null) return;
        if (req == REQ_SAVE_PDF && res == RESULT_OK && data != null && data.getData() != null) {
            Uri u = data.getData();
            Exporter.savePdfTo(this, doc, u);
        }
        // REQ_EDIT / REQ_ADD: onResume sẽ nạp lại tài liệu
    }

    /** Trang thiếu ảnh kết quả (ví dụ ứng dụng bị đóng giữa chừng) -> xử lý lại. */
    private void renderMissing(DocStore.Page p) {
        if (rendering.contains(p.id) || failed.contains(failKey(p))) return;
        rendering.add(p.id);
        final String pid = p.id;
        final String fkey = failKey(p);
        final DocStore.Page snap = p.copy();
        final File dir = doc.dir;
        Processor.EXEC.execute(new Runnable() {
            @Override
            public void run() {
                boolean ok = true;
                try {
                    Processor.render(dir, snap);
                } catch (Throwable t) {
                    ok = false;
                }
                final boolean fok = ok;
                Ui.MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        rendering.remove(pid);
                        if (!Ui.alive(DocumentActivity.this)) return;
                        DocStore.Page cur = doc.page(pid);
                        if (cur == null) return;
                        if (!fok) {
                            failed.add(fkey);
                            return;
                        }
                        // trang có thể đã được sửa ở màn hình khác trong lúc xử lý: chỉ nhận kết quả nếu còn đúng tham số
                        if (Processor.outName(cur).equals(snap.out)) {
                            cur.takeResult(snap);
                            DocStore.save(doc);
                            adapter.notifyDataSetChanged();
                        } else if (isResumedNow()) {
                            renderMissing(cur);
                        }
                    }
                });
            }
        });
    }

    private void loadBitmap(final String key, final String path) {
        if (loading.contains(key)) return;
        loading.add(key);
        final int max = itemWidth;
        Processor.IO.execute(new Runnable() {
            @Override
            public void run() {
                final Bitmap b = new File(path).exists() ? ImageUtil.decodeSampled(path, max) : null;
                Ui.MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        loading.remove(key);
                        if (!Ui.alive(DocumentActivity.this)) return;
                        if (b != null) {
                            cache.put(key, b);
                            adapter.notifyDataSetChanged();
                        }
                    }
                });
            }
        });
    }

    private class PageAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return doc.pages.size();
        }

        @Override
        public Object getItem(int position) {
            return doc.pages.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            FrameLayout f;
            AspectImageView iv;
            TextView badge;
            if (convertView == null) {
                f = new FrameLayout(DocumentActivity.this);
                f.setPadding(Ui.dp(16), Ui.dp(8), Ui.dp(16), Ui.dp(8));
                iv = new AspectImageView(DocumentActivity.this);
                iv.setScaleType(ImageView.ScaleType.FIT_XY);
                iv.setBackgroundColor(Color.WHITE);
                f.addView(iv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                badge = Ui.text(DocumentActivity.this, "", 13, Color.WHITE, true);
                badge.setPadding(Ui.dp(7), Ui.dp(2), Ui.dp(7), Ui.dp(2));
                badge.setBackground(Ui.rect(0xAA222124, 4));
                f.addView(badge, Ui.frame(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.LEFT));
                f.setTag(new Object[]{iv, badge});
            } else {
                f = (FrameLayout) convertView;
                Object[] t = (Object[]) f.getTag();
                iv = (AspectImageView) t[0];
                badge = (TextView) t[1];
            }
            DocStore.Page p = doc.pages.get(position);
            badge.setText((position + 1) + "/" + doc.pages.size());
            float ratio = 1.414f;
            Bitmap b = null;
            if (p.out != null && doc.outReady(p)) {
                File of = doc.file(p.out);
                b = cache.get(p.out);
                if (b == null) loadBitmap(p.out, of.getPath());
                int[] bd = b != null ? new int[]{b.getWidth(), b.getHeight()} : ImageUtil.bounds(of.getPath());
                if (bd[0] > 0 && bd[1] > 0) ratio = bd[1] / (float) bd[0];
            }
            iv.setRatio(ratio);
            iv.setImageBitmap(b);
            return f;
        }
    }

    /** ImageView có chiều cao theo tỷ lệ ảnh. */
    static class AspectImageView extends ImageView {
        private float ratio = 1.414f;

        AspectImageView(android.content.Context c) {
            super(c);
        }

        void setRatio(float r) {
            if (Math.abs(r - ratio) > 1e-3f) {
                ratio = r;
                requestLayout();
            }
        }

        @Override
        protected void onMeasure(int wSpec, int hSpec) {
            int w = MeasureSpec.getSize(wSpec);
            setMeasuredDimension(w, Math.round(w * ratio));
        }
    }
}
