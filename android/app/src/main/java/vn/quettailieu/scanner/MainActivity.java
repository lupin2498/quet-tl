package vn.quettailieu.scanner;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcelable;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** Màn hình chính: tìm kiếm, công cụ (Quét, Hàng loạt, Nhập ảnh), tài liệu gần đây, nút máy ảnh. */
public class MainActivity extends BaseActivity {
    private static final int REQ_PICK = 41;
    private static final int REQ_IMPORT_CROP = 42;

    private final List<DocStore.Doc> all = new ArrayList<>();
    private final List<DocStore.Doc> shown = new ArrayList<>();
    private DocAdapter adapter;
    private ListView list;
    private View empty;
    private TextView countView;
    private EditText search;
    private LruCache<String, Bitmap> thumbs;
    private final HashSet<String> loading = new HashSet<>();
    private View busyView;
    private String importPath;
    /** Giữ tài liệu vừa tạo cho tới khi trình chỉnh sửa mở nó (để không bị coi là bản nháp bỏ dở). */
    @SuppressWarnings({"unused", "FieldCanBeLocal"})
    private DocStore.Doc lastCreated;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        thumbs = new LruCache<String, Bitmap>(8 * 1024) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return Math.max(1, value.getByteCount() / 1024);
            }
        };
        if (state != null) importPath = state.getString("importPath");
        buildUi();
        showCrashIfAny();
        if (state == null) {
            recoverDrafts();
            handleIncoming(getIntent());
        }
    }

    /**
     * Mở ứng dụng mới: các bản nháp còn trang mà không màn hình nào đang dùng là do lần trước ứng dụng
     * bị đóng giữa lúc quét -> đưa trở lại danh sách thay vì để mất.
     */
    private void recoverDrafts() {
        final Context app = getApplicationContext();
        Processor.IO.execute(new Runnable() {
            @Override
            public void run() {
                final List<String> ids;
                try {
                    ids = DocStore.orphanDrafts(app);
                } catch (Throwable t) {
                    return;
                }
                if (ids.isEmpty()) return;
                Ui.MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        int n = DocStore.recoverDrafts(app, ids);
                        if (n > 0 && Ui.alive(MainActivity.this)) {
                            Ui.toast(MainActivity.this, "Đã khôi phục " + n + " tài liệu đang quét dở");
                            reload();
                        }
                    }
                });
            }
        });
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (importPath != null) out.putString("importPath", importPath);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncoming(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    // =================================================================== giao diện

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        // ô tìm kiếm
        LinearLayout sb = new LinearLayout(this);
        sb.setOrientation(LinearLayout.HORIZONTAL);
        sb.setGravity(Gravity.CENTER_VERTICAL);
        sb.setBackground(Ui.rect(Ui.CARD, 10));
        sb.setPadding(Ui.dp(12), 0, Ui.dp(8), 0);
        sb.addView(Ui.icon(this, R.drawable.ic_search, Ui.TEXT2, 22));
        search = new EditText(this);
        search.setHint("Tìm kiếm");
        search.setHintTextColor(Ui.TEXT2);
        search.setTextColor(Ui.TEXT);
        search.setTextSize(16);
        search.setBackground(null);
        search.setSingleLine(true);
        search.setPadding(Ui.dp(10), 0, 0, 0);
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
                applyFilter();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        sb.addView(search, Ui.lin(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        LinearLayout.LayoutParams sbl = Ui.lin(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(46), 0);
        sbl.setMargins(Ui.dp(16), Ui.dp(12), Ui.dp(16), Ui.dp(8));
        col.addView(sb, sbl);

        // công cụ
        LinearLayout tools = new LinearLayout(this);
        tools.setOrientation(LinearLayout.HORIZONTAL);
        tools.setPadding(Ui.dp(8), Ui.dp(4), Ui.dp(8), Ui.dp(4));
        tools.addView(tool(R.drawable.ic_scan, 0xFF26C6A6, "Quét", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startScan(null);
            }
        }));
        tools.addView(tool(R.drawable.ic_batch, 0xFF4A90E2, "Hàng loạt", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startScan(Boolean.TRUE);
            }
        }));
        tools.addView(tool(R.drawable.ic_photo_library, 0xFF7C6CF2, "Nhập ảnh", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickImages();
            }
        }));
        col.addView(tools, Ui.lin(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(88), 0));

        View div = new View(this);
        div.setBackgroundColor(Ui.DIVIDER);
        LinearLayout.LayoutParams dl = Ui.lin(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(1), 0);
        dl.setMargins(0, Ui.dp(6), 0, 0);
        col.addView(div, dl);

        LinearLayout hdr = new LinearLayout(this);
        hdr.setOrientation(LinearLayout.HORIZONTAL);
        hdr.setGravity(Gravity.CENTER_VERTICAL);
        hdr.setPadding(Ui.dp(16), 0, Ui.dp(16), 0);
        hdr.addView(Ui.text(this, "Gần đây", 19, Ui.TEXT, true), Ui.lin(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        countView = Ui.text(this, "", 13, Ui.TEXT2, false);
        hdr.addView(countView);
        col.addView(hdr, Ui.lin(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(48), 0));

        FrameLayout listBox = new FrameLayout(this);
        list = new ListView(this);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setClipToPadding(false);
        list.setPadding(0, 0, 0, Ui.dp(96));
        adapter = new DocAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                openDoc(shown.get(position));
            }
        });
        listBox.addView(list, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        empty = buildEmpty();
        listBox.addView(empty, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        col.addView(listBox, Ui.lin(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // nút máy ảnh nổi
        ImageView fab = new ImageView(this);
        fab.setImageResource(R.drawable.ic_camera);
        fab.setImageTintList(ColorStateList.valueOf(Color.WHITE));
        fab.setScaleType(ImageView.ScaleType.CENTER);
        fab.setBackground(new android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), Ui.oval(Ui.ACCENT), null));
        fab.setElevation(Ui.dpf(6));
        fab.setContentDescription("Quét tài liệu");
        fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startScan(null);
            }
        });
        FrameLayout.LayoutParams fl = Ui.frame(Ui.dp(62), Ui.dp(62), Gravity.BOTTOM | Gravity.RIGHT);
        fl.setMargins(0, 0, Ui.dp(22), Ui.dp(26));
        root.addView(fab, fl);

        busyView = new FrameLayout(this);
        busyView.setBackgroundColor(0x99000000);
        busyView.setClickable(true);
        android.widget.ProgressBar pb = new android.widget.ProgressBar(this);
        pb.setIndeterminateTintList(ColorStateList.valueOf(Ui.ACCENT));
        ((FrameLayout) busyView).addView(pb, Ui.frame(Ui.dp(52), Ui.dp(52), Gravity.CENTER));
        busyView.setVisibility(View.GONE);
        root.addView(busyView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
    }

    private View tool(int icon, int color, String label, View.OnClickListener l) {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setGravity(Gravity.CENTER);
        b.setBackground(Ui.ripple(null, true));
        b.setOnClickListener(l);
        FrameLayout ic = new FrameLayout(this);
        ic.setBackground(Ui.rect((color & 0x00FFFFFF) | 0x26000000, 12));
        ImageView iv = Ui.icon(this, icon, color, 26);
        ic.addView(iv, Ui.frame(Ui.dp(26), Ui.dp(26), Gravity.CENTER));
        b.addView(ic, new LinearLayout.LayoutParams(Ui.dp(46), Ui.dp(46)));
        TextView t = Ui.text(this, label, 12.5f, Ui.TEXT, false);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tl.topMargin = Ui.dp(6);
        b.addView(t, tl);
        b.setLayoutParams(Ui.lin(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        return b;
    }

    private View buildEmpty() {
        LinearLayout e = new LinearLayout(this);
        e.setOrientation(LinearLayout.VERTICAL);
        e.setGravity(Gravity.CENTER);
        e.setPadding(Ui.dp(40), 0, Ui.dp(40), Ui.dp(80));
        e.addView(Ui.icon(this, R.drawable.ic_doc, 0xFF5A595E, 64));
        TextView t = Ui.text(this, "Chưa có tài liệu nào", 17, Ui.TEXT, true);
        t.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams l1 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        l1.topMargin = Ui.dp(14);
        e.addView(t, l1);
        TextView t2 = Ui.text(this, "Nhấn nút máy ảnh để quét giấy tờ. Ứng dụng tự tìm mép trang, nắn thẳng và làm rõ chữ.", 14, Ui.TEXT2, false);
        t2.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams l2 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        l2.topMargin = Ui.dp(6);
        e.addView(t2, l2);
        e.setVisibility(View.GONE);
        return e;
    }

    // =================================================================== dữ liệu

    private void reload() {
        final Context app = getApplicationContext();
        Processor.IO.execute(new Runnable() {
            @Override
            public void run() {
                final List<DocStore.Doc> docs = DocStore.list(app);
                Ui.MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!Ui.alive(MainActivity.this)) return;
                        all.clear();
                        all.addAll(docs);
                        applyFilter();
                    }
                });
            }
        });
    }

    private void applyFilter() {
        // tìm không phân biệt hoa/thường và dấu tiếng Việt ("bien ban" ra "Biên bản")
        String q = search == null ? "" : Ui.fold(search.getText().toString().trim());
        shown.clear();
        for (DocStore.Doc d : all) {
            if (q.length() == 0 || Ui.fold(d.title).contains(q)) shown.add(d);
        }
        adapter.notifyDataSetChanged();
        empty.setVisibility(all.isEmpty() ? View.VISIBLE : View.GONE);
        countView.setText(all.isEmpty() ? "" : all.size() + " tài liệu");
    }

    private void openDoc(DocStore.Doc d) {
        Intent i = new Intent(this, DocumentActivity.class);
        i.putExtra(DocumentActivity.EXTRA_DOC, d.id);
        startActivity(i);
    }

    private void startScan(Boolean batch) {
        Intent i = new Intent(this, CameraActivity.class);
        if (batch != null) i.putExtra(CameraActivity.EXTRA_BATCH, batch.booleanValue());
        startActivity(i);
    }

    // =================================================================== nhập ảnh

    private void pickImages() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(Intent.createChooser(i, "Chọn ảnh"), REQ_PICK);
        } catch (Throwable t) {
            Ui.toast(this, "Không mở được thư viện ảnh");
        }
    }

    private static final String TEST_IMPORT = "vn.quettailieu.scanner.TEST_IMPORT";

    private void handleIncoming(Intent intent) {
        if (intent == null) return;
        // mở lại từ danh sách "Gần đây" của hệ thống: Intent chia sẻ cũ được gửi lại -> không nhập lần nữa
        if ((intent.getFlags() & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0) return;
        List<Uri> uris = new ArrayList<>();
        try {
            String act = intent.getAction();
            if (Intent.ACTION_SEND.equals(act)) {
                Parcelable p = intent.getParcelableExtra(Intent.EXTRA_STREAM);
                if (p instanceof Uri) uris.add((Uri) p);
            } else if (Intent.ACTION_SEND_MULTIPLE.equals(act)) {
                ArrayList<Parcelable> ps = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
                if (ps != null) for (Parcelable p : ps) if (p instanceof Uri) uris.add((Uri) p);
            } else if (TEST_IMPORT.equals(act) && (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
                // chỉ bản debug: kiểm thử tự động nhập nhiều ảnh ("am start" không gửi được ArrayList<Uri>)
                String[] arr = intent.getStringArrayExtra("uris");
                if (arr != null) for (String u : arr) uris.add(Uri.parse(u));
            } else {
                return;
            }
            intent.setAction(Intent.ACTION_MAIN);
        } catch (RuntimeException e) {
            // Intent từ ứng dụng khác có thể chứa dữ liệu không đọc được
            Ui.toast(this, "Không đọc được ảnh được chia sẻ");
            return;
        }
        if (!uris.isEmpty()) importUris(uris);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_PICK) {
            if (res != RESULT_OK || data == null) return;
            List<Uri> uris = new ArrayList<>();
            ClipData cd = data.getClipData();
            if (cd != null) {
                for (int i = 0; i < cd.getItemCount(); i++) {
                    Uri u = cd.getItemAt(i).getUri();
                    if (u != null) uris.add(u);
                }
            } else if (data.getData() != null) {
                uris.add(data.getData());
            }
            importUris(uris);
        } else if (req == REQ_IMPORT_CROP) {
            String path = importPath;
            importPath = null;
            if (path == null) return;
            if (res != RESULT_OK || data == null) {
                new File(path).delete();
                return;
            }
            float[] quad = data.getFloatArrayExtra(CropActivity.EXTRA_QUAD);
            int[] size = data.getIntArrayExtra(CropActivity.EXTRA_SIZE);
            if (size == null) size = ImageUtil.orientedSize(path);
            DocStore.Doc d = null;
            try {
                d = DocStore.create(this, true);
                DocStore.addPage(d, new File(path), size, quad, data.getBooleanExtra(CropActivity.EXTRA_AUTO, true), -1);
                DocStore.save(d);
                openEditorForNew(d);
            } catch (Exception e) {
                if (d != null) DocStore.delete(d);
                Ui.toast(this, "Lỗi nhập ảnh: " + Ui.msg(e));
            }
        }
    }

    private void importUris(final List<Uri> uris) {
        if (uris.isEmpty()) return;
        busyView.setVisibility(View.VISIBLE);
        final Context app = getApplicationContext();
        Processor.IO.execute(new Runnable() {
            @Override
            public void run() {
                final List<File> files = new ArrayList<>();
                final List<Processor.Detection> dets = new ArrayList<>();
                int failed = 0;
                for (Uri u : uris) {
                    try {
                        File f = ImageUtil.importUri(app, u);
                        files.add(f);
                        if (uris.size() > 1) dets.add(Processor.detect(f.getPath()));
                    } catch (Throwable t) {
                        failed++;
                    }
                }
                final int nFailed = failed;
                Ui.MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!Ui.alive(MainActivity.this)) {
                            for (File f : files) f.delete();
                            return;
                        }
                        busyView.setVisibility(View.GONE);
                        if (nFailed > 0) Ui.toast(MainActivity.this, nFailed + " ảnh không đọc được");
                        if (files.isEmpty()) return;
                        // nhập xong lúc người dùng đã rời ứng dụng -> đợi họ quay lại rồi mới mở màn hình tiếp theo
                        runWhenResumed(new Runnable() {
                            @Override
                            public void run() {
                                onImported(files, dets);
                            }
                        });
                    }
                });
            }
        });
    }

    private void onImported(List<File> files, List<Processor.Detection> dets) {
        if (files.size() == 1 && dets.isEmpty()) {
            // một ảnh: mở màn hình chọn vùng cắt trước
            importPath = files.get(0).getPath();
            Intent i = new Intent(this, CropActivity.class);
            i.putExtra(CropActivity.EXTRA_PATH, importPath);
            i.putExtra(CropActivity.EXTRA_MODE, CropActivity.MODE_EDIT);
            startActivityForResult(i, REQ_IMPORT_CROP);
            return;
        }
        DocStore.Doc d = null;
        try {
            d = DocStore.create(this, true);
            for (int i = 0; i < files.size(); i++) {
                Processor.Detection det = dets.get(i);
                DocStore.addPage(d, files.get(i), det.size, det.quad, true, -1);
            }
            DocStore.save(d);
            openEditorForNew(d);
        } catch (Exception e) {
            if (d != null) DocStore.delete(d);
            Ui.toast(this, "Lỗi nhập ảnh: " + Ui.msg(e));
        }
    }

    private void openEditorForNew(DocStore.Doc d) {
        lastCreated = d;
        Intent i = new Intent(this, EditorActivity.class);
        i.putExtra(EditorActivity.EXTRA_DOC, d.id);
        i.putExtra(EditorActivity.EXTRA_NEW, true);
        startActivity(i);
    }

    // =================================================================== thao tác tài liệu

    private void docMenu(View anchor, final DocStore.Doc d) {
        String[] items = {"Đổi tên", "Chia sẻ PDF", "Chia sẻ ảnh", "Xoá"};
        Ui.menu(this, d.title, items, new Ui.Cb<Integer>() {
            @Override
            public void done(Integer which) {
                switch (which) {
                    case 0:
                        Ui.prompt(MainActivity.this, "Đổi tên tài liệu", d.title, new Ui.Cb<String>() {
                            @Override
                            public void done(String v) {
                                // sửa trên đối tượng dùng chung (d chỉ là bản đọc để hiển thị danh sách)
                                DocStore.Doc cur = DocStore.open(MainActivity.this, d.id);
                                if (cur != null) {
                                    cur.title = v;
                                    cur.modified = System.currentTimeMillis();
                                    DocStore.save(cur);
                                }
                                reload();
                            }
                        });
                        break;
                    case 1:
                        if (allReady(d)) Exporter.sharePdf(MainActivity.this, d);
                        break;
                    case 2:
                        if (allReady(d)) Exporter.shareImages(MainActivity.this, d);
                        break;
                    case 3:
                        Ui.confirm(MainActivity.this, "Xoá tài liệu?", "\"" + d.title + "\" sẽ bị xoá vĩnh viễn.", "Xoá", new Runnable() {
                            @Override
                            public void run() {
                                DocStore.delete(d);
                                reload();
                            }
                        });
                        break;
                }
            }
        });
    }

    private boolean allReady(DocStore.Doc d) {
        for (DocStore.Page p : d.pages) {
            if (!d.outReady(p)) {
                Ui.toast(this, "Hãy mở tài liệu để hoàn tất xử lý trang");
                return false;
            }
        }
        return true;
    }

    private void showCrashIfAny() {
        final File f = App.crashFile(this);
        if (!f.exists()) return;
        String text;
        try {
            text = DocStore.readText(f);
        } catch (Exception e) {
            text = "";
        }
        f.delete();
        if (text.length() == 0) return;
        final String report = text;
        TextView tv = Ui.text(this, report, 11, 0xFFDDDDDD, false);
        tv.setTextIsSelectable(true);
        tv.setPadding(Ui.dp(20), Ui.dp(8), Ui.dp(20), Ui.dp(8));
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        AlertDialog.Builder b = Ui.dialog(this).setTitle("Lần trước ứng dụng gặp lỗi").setView(sv)
                .setPositiveButton("Sao chép", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("crash", report));
                        Ui.toast(MainActivity.this, "Đã sao chép nội dung lỗi");
                    }
                })
                .setNegativeButton("Đóng", null);
        Ui.showDialog(this, b);
    }

    private class DocAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return shown.size();
        }

        @Override
        public Object getItem(int position) {
            return shown.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Holder h;
            if (convertView == null) {
                h = new Holder();
                LinearLayout row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(Ui.dp(16), Ui.dp(9), Ui.dp(4), Ui.dp(9));
                row.setBackground(Ui.ripple(null, true));
                h.thumb = new ImageView(MainActivity.this);
                h.thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
                h.thumb.setBackgroundColor(Color.WHITE);
                row.addView(h.thumb, new LinearLayout.LayoutParams(Ui.dp(58), Ui.dp(74)));
                LinearLayout texts = new LinearLayout(MainActivity.this);
                texts.setOrientation(LinearLayout.VERTICAL);
                texts.setPadding(Ui.dp(14), 0, Ui.dp(4), 0);
                h.title = Ui.text(MainActivity.this, "", 16, Ui.TEXT, true);
                h.title.setSingleLine(true);
                h.title.setEllipsize(TextUtils.TruncateAt.END);
                texts.addView(h.title);
                h.sub = Ui.text(MainActivity.this, "", 13, Ui.TEXT2, false);
                LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                sl.topMargin = Ui.dp(6);
                texts.addView(h.sub, sl);
                row.addView(texts, Ui.lin(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                h.more = Ui.desc(Ui.iconButton(MainActivity.this, R.drawable.ic_more, Ui.TEXT2, null), "Tùy chọn tài liệu");
                row.addView(h.more);
                row.setTag(h);
                convertView = row;
            } else {
                h = (Holder) convertView.getTag();
            }
            final DocStore.Doc d = shown.get(position);
            h.title.setText(d.title);
            h.sub.setText(Ui.dateShort(d.modified) + "   ▢ " + d.pages.size());
            h.more.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    docMenu(v, d);
                }
            });
            h.thumb.setImageBitmap(null);
            DocStore.Page first = d.pages.isEmpty() ? null : d.pages.get(0);
            if (first != null && first.out != null) {
                final String key = d.id + "/" + first.out;
                Bitmap b = thumbs.get(key);
                if (b != null) h.thumb.setImageBitmap(b);
                else loadThumb(key, d.file(first.out).getPath());
            }
            return convertView;
        }
    }

    private static class Holder {
        ImageView thumb, more;
        TextView title, sub;
    }

    private void loadThumb(final String key, final String path) {
        if (loading.contains(key)) return;
        loading.add(key);
        Processor.IO.execute(new Runnable() {
            @Override
            public void run() {
                final Bitmap b = new File(path).exists() ? ImageUtil.decodeSampled(path, 220) : null;
                Ui.MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        loading.remove(key);
                        if (!Ui.alive(MainActivity.this) || b == null) return;
                        thumbs.put(key, b);
                        adapter.notifyDataSetChanged();
                    }
                });
            }
        });
    }
}
