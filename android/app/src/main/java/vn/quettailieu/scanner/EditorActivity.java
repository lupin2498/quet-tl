package vn.quettailieu.scanner;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.LruCache;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.util.HashMap;
import java.util.HashSet;

import vn.quettailieu.scanner.core.Enhancer;

/** Xem lại & chỉnh các trang: Chụp lại, Xoay trái, Lọc, Cắt, So sánh, Xoá; ✓ để lưu. */
public class EditorActivity extends Activity {
    static final String EXTRA_DOC = "doc";
    static final String EXTRA_FROM_CAMERA = "fromCamera";
    static final String EXTRA_PAGE = "page";
    static final String EXTRA_NEW = "isNew";

    private static final int REQ_CROP = 21;
    private static final int REQ_RETAKE = 22;

    private static final String[] FILTER_NAMES = {"Gốc", "Tăng cường", "Làm sáng", "Thang xám", "Đen trắng"};
    private static final int[] FILTER_MODES = {Enhancer.ORIGINAL, Enhancer.MAGIC, Enhancer.LIGHTEN, Enhancer.GRAY, Enhancer.BW};

    private DocStore.Doc doc;
    private boolean fromCamera;
    private boolean isNew;
    private PageStripView strip;
    private TextView titleView, indicator;
    private ImageView prevBtn, nextBtn;
    private LinearLayout filterPanel, filterRow;
    private TextView applyAllBtn;
    private boolean applyAll;
    private final View[] filterItems = new View[FILTER_MODES.length];
    private final ImageView[] filterThumbs = new ImageView[FILTER_MODES.length];

    private LruCache<String, Bitmap> cache;
    private final HashSet<String> loading = new HashSet<>();
    private final HashSet<String> processing = new HashSet<>();
    private final HashMap<String, Integer> gen = new HashMap<>();
    private int pending;
    private Runnable afterPending;
    private ProgressDialog waitDialog;
    private String cropPageId;
    private int dispMax;
    private boolean finishRetried;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        doc = DocStore.load(this, getIntent().getStringExtra(EXTRA_DOC));
        if (doc == null || doc.pages.isEmpty()) {
            Ui.toast(this, "Tài liệu trống");
            setResult(RESULT_CANCELED);
            finish();
            return;
        }
        fromCamera = getIntent().getBooleanExtra(EXTRA_FROM_CAMERA, false);
        isNew = getIntent().getBooleanExtra(EXTRA_NEW, false);
        if (state != null) cropPageId = state.getString("cropPage");
        int maxKb = (int) Math.min(Integer.MAX_VALUE, Runtime.getRuntime().maxMemory() / 1024 / 6);
        cache = new LruCache<String, Bitmap>(Math.max(16 * 1024, maxKb)) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return Math.max(1, value.getByteCount() / 1024);
            }
        };
        dispMax = Math.min(1600, (int) (getResources().getDisplayMetrics().widthPixels * 1.25f));
        buildUi();
        int start = state != null ? state.getInt("index", 0) : getIntent().getIntExtra(EXTRA_PAGE, 0);
        strip.setIndex(Math.max(0, Math.min(doc.pages.size() - 1, start)), false);
        for (DocStore.Page p : doc.pages) {
            if (!upToDate(p)) schedule(p);
        }
        updateIndicator();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (strip != null) out.putInt("index", strip.getIndex());
        if (cropPageId != null) out.putString("cropPage", cropPageId);
    }

    private boolean upToDate(DocStore.Page p) {
        return doc.outReady(p) && Processor.outName(p).equals(p.out);
    }

    private DocStore.Page current() {
        int i = strip.getIndex();
        if (i < 0 || i >= doc.pages.size()) return null;
        return doc.pages.get(i);
    }

    // =================================================================== giao diện

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG_DARK);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(Ui.dp(4), 0, Ui.dp(12), 0);
        top.addView(Ui.desc(Ui.iconButton(this, R.drawable.ic_back, Color.WHITE, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onBackPressed();
            }
        }), "Quay lại"));
        titleView = Ui.text(this, doc.title, 18, Ui.TEXT, true);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        titleView.setPaintFlags(titleView.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
        titleView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                rename();
            }
        });
        LinearLayout.LayoutParams tl = Ui.lin(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tl.leftMargin = Ui.dp(4);
        top.addView(titleView, tl);
        root.addView(top, Ui.lin(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(56), 0));

        FrameLayout content = new FrameLayout(this);
        strip = new PageStripView(this);
        strip.setAdapter(new PageStripView.Adapter() {
            @Override
            public int count() {
                return doc.pages.size();
            }

            @Override
            public Bitmap bitmap(int i) {
                return pageBitmap(i);
            }

            @Override
            public boolean processing(int i) {
                return i >= 0 && i < doc.pages.size() && processing.contains(doc.pages.get(i).id);
            }

            @Override
            public Bitmap compareBitmap(int i) {
                return sourceBitmap(i);
            }
        });
        strip.setListener(new PageStripView.Listener() {
            @Override
            public void onPageChanged(int index) {
                updateIndicator();
                if (filterPanel.getVisibility() == View.VISIBLE) refreshFilterPanel();
            }
        });
        content.addView(strip, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView compare = Ui.text(this, "So sánh", 13, Ui.TEXT, true);
        compare.setGravity(Gravity.CENTER);
        compare.setPadding(Ui.dp(10), 0, Ui.dp(12), 0);
        compare.setBackground(Ui.rect(0x99333236, 16));
        Drawable cmpIc = getResources().getDrawable(R.drawable.ic_compare, getTheme()).mutate();
        cmpIc.setTint(Ui.TEXT);
        cmpIc.setBounds(0, 0, Ui.dp(16), Ui.dp(16));
        compare.setCompoundDrawables(cmpIc, null, null, null);
        compare.setCompoundDrawablePadding(Ui.dp(5));
        compare.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                int a = e.getActionMasked();
                if (a == MotionEvent.ACTION_DOWN) {
                    DocStore.Page p = current();
                    if (p != null) ensureSource(p);
                    strip.setCompare(true);
                    v.setAlpha(0.7f);
                } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                    strip.setCompare(false);
                    v.setAlpha(1f);
                }
                return true;
            }
        });
        FrameLayout.LayoutParams clp = Ui.frame(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(32), Gravity.TOP | Gravity.RIGHT);
        clp.topMargin = Ui.dp(10);
        clp.rightMargin = Ui.dp(14);
        content.addView(compare, clp);

        ImageView del = Ui.iconButton(this, R.drawable.ic_delete, Color.WHITE, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                deleteCurrent();
            }
        });
        del.setBackground(Ui.oval(0x99333236));
        del.setContentDescription("Xoá trang");
        int dpad = Ui.dp(8);
        del.setPadding(dpad, dpad, dpad, dpad);
        FrameLayout.LayoutParams dlp = Ui.frame(Ui.dp(36), Ui.dp(36), Gravity.TOP | Gravity.LEFT);
        dlp.topMargin = Ui.dp(8);
        dlp.leftMargin = Ui.dp(14);
        content.addView(del, dlp);
        root.addView(content, Ui.lin(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout ind = new LinearLayout(this);
        ind.setOrientation(LinearLayout.HORIZONTAL);
        ind.setGravity(Gravity.CENTER);
        prevBtn = Ui.iconButton(this, R.drawable.ic_chevron_left, Color.WHITE, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                strip.setIndex(strip.getIndex() - 1, true);
            }
        });
        prevBtn.setBackground(Ui.oval(0xFF333236));
        prevBtn.setContentDescription("Trang trước");
        prevBtn.setPadding(Ui.dp(4), Ui.dp(4), Ui.dp(4), Ui.dp(4));
        ind.addView(prevBtn, Ui.lin(Ui.dp(28), Ui.dp(28), 0));
        indicator = Ui.text(this, "", 16, Ui.TEXT, true);
        indicator.setGravity(Gravity.CENTER);
        ind.addView(indicator, Ui.lin(Ui.dp(76), ViewGroup.LayoutParams.WRAP_CONTENT, 0));
        nextBtn = Ui.iconButton(this, R.drawable.ic_chevron_right, Color.WHITE, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                strip.setIndex(strip.getIndex() + 1, true);
            }
        });
        nextBtn.setBackground(Ui.oval(0xFF333236));
        nextBtn.setContentDescription("Trang sau");
        nextBtn.setPadding(Ui.dp(4), Ui.dp(4), Ui.dp(4), Ui.dp(4));
        ind.addView(nextBtn, Ui.lin(Ui.dp(28), Ui.dp(28), 0));
        root.addView(ind, Ui.lin(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(48), 0));

        filterPanel = buildFilterPanel();
        filterPanel.setVisibility(View.GONE);
        root.addView(filterPanel, Ui.lin(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Ui.CARD);
        bar.setPadding(Ui.dp(4), 0, Ui.dp(10), 0);
        bar.addView(Ui.toolButton(this, R.drawable.ic_camera, "Chụp lại", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                retake();
            }
        }));
        bar.addView(Ui.toolButton(this, R.drawable.ic_rotate_left, "Bên trái", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                rotateLeft();
            }
        }));
        bar.addView(Ui.toolButton(this, R.drawable.ic_filter, "Lọc", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleFilterPanel();
            }
        }));
        bar.addView(Ui.toolButton(this, R.drawable.ic_crop, "Cắt", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openCrop();
            }
        }));
        ImageView ok = new ImageView(this);
        ok.setImageResource(R.drawable.ic_check);
        ok.setImageTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));
        ok.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        ok.setContentDescription("Xong");
        ok.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x40FFFFFF), Ui.rect(Ui.ACCENT, 6), null));
        ok.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finishEditing(true);
            }
        });
        LinearLayout.LayoutParams okl = Ui.lin(Ui.dp(68), Ui.dp(42), 0);
        okl.leftMargin = Ui.dp(6);
        bar.addView(ok, okl);
        root.addView(bar, Ui.lin(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(66), 0));
        setContentView(root);
    }

    private LinearLayout buildFilterPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(0xFF262528);
        panel.setPadding(0, Ui.dp(10), 0, Ui.dp(4));
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        filterRow = new LinearLayout(this);
        filterRow.setOrientation(LinearLayout.HORIZONTAL);
        filterRow.setPadding(Ui.dp(10), 0, Ui.dp(10), 0);
        for (int k = 0; k < FILTER_MODES.length; k++) {
            final int mode = FILTER_MODES[k];
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER_HORIZONTAL);
            item.setPadding(Ui.dp(6), 0, Ui.dp(6), 0);
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setBackgroundColor(Color.WHITE);
            int pad = Ui.dp(2);
            iv.setPadding(pad, pad, pad, pad);
            item.addView(iv, new LinearLayout.LayoutParams(Ui.dp(62), Ui.dp(80)));
            TextView t = Ui.text(this, FILTER_NAMES[k], 12, Ui.TEXT, false);
            t.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tl.topMargin = Ui.dp(4);
            item.addView(t, tl);
            item.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    applyFilter(mode);
                }
            });
            filterItems[k] = item;
            filterThumbs[k] = iv;
            filterRow.addView(item);
        }
        hs.addView(filterRow);
        panel.addView(hs, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(12), 0, Ui.dp(4), 0);
        applyAllBtn = Ui.text(this, "Áp dụng cho tất cả trang", 14, Ui.TEXT, false);
        applyAllBtn.setGravity(Gravity.CENTER_VERTICAL);
        applyAllBtn.setCompoundDrawablePadding(Ui.dp(8));
        applyAllBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                applyAll = !applyAll;
                updateApplyAll();
                if (applyAll) {
                    DocStore.Page p = current();
                    if (p != null) applyFilter(p.filter);
                }
            }
        });
        row.addView(applyAllBtn, Ui.lin(0, Ui.dp(44), 1f));
        row.addView(Ui.desc(Ui.iconButton(this, R.drawable.ic_check, Ui.ACCENT, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                filterPanel.setVisibility(View.GONE);
            }
        }), "Đóng bộ lọc"));
        panel.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(48)));
        updateApplyAll();
        return panel;
    }

    private void updateApplyAll() {
        int s = Ui.dp(18);
        GradientDrawable box = Ui.stroke(applyAll ? Ui.ACCENT : Color.TRANSPARENT, applyAll ? Ui.ACCENT : 0xFF9A999E, 2, 3);
        Drawable d = box;
        if (applyAll) {
            Drawable ck = getResources().getDrawable(R.drawable.ic_check, getTheme()).mutate();
            ck.setTint(Color.WHITE);
            LayerDrawable ld = new LayerDrawable(new Drawable[]{box, ck});
            ld.setLayerInset(1, Ui.dp(2), Ui.dp(2), Ui.dp(2), Ui.dp(2));
            d = ld;
        }
        d.setBounds(0, 0, s, s);
        applyAllBtn.setCompoundDrawables(d, null, null, null);
    }

    private void updateIndicator() {
        int n = doc.pages.size();
        int i = strip.getIndex();
        indicator.setText((i + 1) + "/" + n);
        prevBtn.setAlpha(i > 0 ? 1f : 0.35f);
        nextBtn.setAlpha(i < n - 1 ? 1f : 0.35f);
    }

    // =================================================================== ảnh hiển thị

    private Bitmap pageBitmap(int i) {
        if (i < 0 || i >= doc.pages.size()) return null;
        DocStore.Page p = doc.pages.get(i);
        if (processing.contains(p.id)) {
            Bitmap pv = cache.get(previewKey(p));
            if (pv != null) return pv;
            ensurePreview(p);
            if (p.out != null) {
                Bitmap old = cache.get(p.out);
                if (old != null) return old;
            }
            return null;
        }
        if (p.out == null) return null;
        Bitmap b = cache.get(p.out);
        if (b == null) loadOut(p);
        return b;
    }

    private Bitmap sourceBitmap(int i) {
        if (i < 0 || i >= doc.pages.size()) return null;
        DocStore.Page p = doc.pages.get(i);
        Bitmap b = cache.get("src_" + p.src);
        if (b == null) ensureSource(p);
        return b;
    }

    private String previewKey(DocStore.Page p) {
        return "pv_" + Processor.warpName(p) + "_" + p.rot;
    }

    private void loadOut(DocStore.Page p) {
        final String key = p.out;
        if (key == null || loading.contains(key)) return;
        loading.add(key);
        final String path = doc.file(p.out).getPath();
        final int max = dispMax;
        Processor.IO.execute(new Runnable() {
            @Override
            public void run() {
                final Bitmap b = new File(path).exists() ? ImageUtil.decodeSampled(path, max) : null;
                Ui.MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        loading.remove(key);
                        if (!Ui.alive(EditorActivity.this)) return;
                        if (b != null) cache.put(key, b);
                        strip.invalidate();
                    }
                });
            }
        });
    }

    private void ensurePreview(DocStore.Page p) {
        final String key = previewKey(p);
        if (cache.get(key) != null || loading.contains(key) || p.src == null) return;
        loading.add(key);
        final String path = doc.file(p.src).getPath();
        final int w = p.srcW, h = p.srcH, rot = p.rot;
        final float[] q = p.quad == null ? null : p.quad.clone();
        Processor.IO.execute(new Runnable() {
            @Override
            public void run() {
                Bitmap b = null;
                try {
                    b = ImageUtil.quickWarp(path, w, h, q, 900);
                    if (b != null && rot != 0) {
                        Bitmap r = ImageUtil.rotate(b, rot);
                        if (r != b) b.recycle();
                        b = r;
                    }
                } catch (Throwable ignored) {
                }
                final Bitmap fb = b;
                Ui.MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        loading.remove(key);
                        if (!Ui.alive(EditorActivity.this)) return;
                        if (fb != null) cache.put(key, fb);
                        strip.invalidate();
                    }
                });
            }
        });
    }

    private void ensureSource(DocStore.Page p) {
        final String key = "src_" + p.src;
        if (p.src == null || cache.get(key) != null || loading.contains(key)) return;
        loading.add(key);
        final String path = doc.file(p.src).getPath();
        final int max = dispMax;
        Processor.IO.execute(new Runnable() {
            @Override
            public void run() {
                final Bitmap b = ImageUtil.decodeOriented(path, max);
                Ui.MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        loading.remove(key);
                        if (!Ui.alive(EditorActivity.this)) return;
                        if (b != null) cache.put(key, b);
                        strip.invalidate();
                    }
                });
            }
        });
    }

    // =================================================================== xử lý

    private void schedule(DocStore.Page p) {
        final String pid = p.id;
        Integer old = gen.get(pid);
        final int g = old == null ? 1 : old + 1;
        gen.put(pid, g);
        final DocStore.Page snap = p.copy();
        final File dir = doc.dir;
        processing.add(pid);
        pending++;
        ensurePreview(p);
        strip.invalidate();
        Processor.EXEC.execute(new Runnable() {
            @Override
            public void run() {
                Throwable err = null;
                try {
                    Processor.render(dir, snap);
                } catch (Throwable t) {
                    err = t;
                }
                final Throwable e = err;
                Ui.MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        onRendered(pid, g, snap, e);
                    }
                });
            }
        });
    }

    private void onRendered(String pid, int g, DocStore.Page snap, Throwable err) {
        pending--;
        DocStore.Page p = doc.page(pid);
        Integer latest = gen.get(pid);
        if (p != null && latest != null && latest == g) {
            processing.remove(pid);
            if (err == null) {
                p.warp = snap.warp;
                p.out = snap.out;
                p.ver++;
                DocStore.save(doc);
                if (Ui.alive(this)) loadOut(p);
            } else if (Ui.alive(this)) {
                String m = err instanceof OutOfMemoryError ? "thiếu bộ nhớ" : err.getMessage();
                Ui.toast(this, "Lỗi xử lý trang: " + m);
            }
        }
        if (pending <= 0) {
            pending = 0;
            if (afterPending != null) {
                Runnable r = afterPending;
                afterPending = null;
                dismissWait();
                r.run();
            }
        }
        if (Ui.alive(this)) strip.invalidate();
    }

    @SuppressWarnings("deprecation")
    private void showWait(String msg) {
        if (waitDialog != null) return;
        waitDialog = new ProgressDialog(this, android.R.style.Theme_Material_Dialog_Alert);
        waitDialog.setMessage(msg);
        waitDialog.setCancelable(false);
        waitDialog.show();
    }

    private void dismissWait() {
        if (waitDialog != null) {
            try {
                waitDialog.dismiss();
            } catch (Throwable ignored) {
            }
            waitDialog = null;
        }
    }

    // =================================================================== thao tác

    private void rotateLeft() {
        DocStore.Page p = current();
        if (p == null) return;
        p.rot = (p.rot + 270) % 360;
        DocStore.save(doc);
        schedule(p);
    }

    private void toggleFilterPanel() {
        if (filterPanel.getVisibility() == View.VISIBLE) {
            filterPanel.setVisibility(View.GONE);
        } else {
            filterPanel.setVisibility(View.VISIBLE);
            refreshFilterPanel();
        }
    }

    private void refreshFilterPanel() {
        DocStore.Page p = current();
        if (p == null) return;
        for (int k = 0; k < FILTER_MODES.length; k++) {
            boolean sel = FILTER_MODES[k] == p.filter;
            filterThumbs[k].setBackgroundColor(sel ? Ui.ACCENT : 0xFF555458);
            ((TextView) ((LinearLayout) filterItems[k]).getChildAt(1)).setTextColor(sel ? Ui.ACCENT : Ui.TEXT);
        }
        final String path = p.src == null ? null : doc.file(p.src).getPath();
        if (path == null) return;
        final int w = p.srcW, h = p.srcH, rot = p.rot;
        final float[] q = p.quad == null ? null : p.quad.clone();
        final String pid = p.id;
        Processor.IO.execute(new Runnable() {
            @Override
            public void run() {
                Bitmap base = ImageUtil.quickWarp(path, w, h, q, 220);
                if (base == null) return;
                if (rot != 0) {
                    Bitmap r = ImageUtil.rotate(base, rot);
                    if (r != base) base.recycle();
                    base = r;
                }
                int bw = base.getWidth(), bh = base.getHeight();
                int[] src = ImageUtil.pixels(base);
                base.recycle();
                final Bitmap[] outs = new Bitmap[FILTER_MODES.length];
                for (int k = 0; k < FILTER_MODES.length; k++) {
                    int[] px = src.clone();
                    Enhancer.apply(px, bw, bh, FILTER_MODES[k]);
                    outs[k] = Bitmap.createBitmap(px, bw, bh, Bitmap.Config.ARGB_8888);
                }
                Ui.MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!Ui.alive(EditorActivity.this)) return;
                        DocStore.Page cur = current();
                        if (cur == null || !cur.id.equals(pid)) return;
                        for (int k = 0; k < outs.length; k++) filterThumbs[k].setImageBitmap(outs[k]);
                    }
                });
            }
        });
    }

    private void applyFilter(int mode) {
        DocStore.Page cur = current();
        if (cur == null) return;
        if (applyAll) {
            for (DocStore.Page p : doc.pages) {
                if (p.filter != mode) {
                    p.filter = mode;
                    schedule(p);
                }
            }
        } else if (cur.filter != mode) {
            cur.filter = mode;
            schedule(cur);
        }
        DocStore.save(doc);
        refreshFilterPanelSelection();
    }

    private void refreshFilterPanelSelection() {
        DocStore.Page p = current();
        if (p == null) return;
        for (int k = 0; k < FILTER_MODES.length; k++) {
            boolean sel = FILTER_MODES[k] == p.filter;
            filterThumbs[k].setBackgroundColor(sel ? Ui.ACCENT : 0xFF555458);
            ((TextView) ((LinearLayout) filterItems[k]).getChildAt(1)).setTextColor(sel ? Ui.ACCENT : Ui.TEXT);
        }
    }

    private void openCrop() {
        DocStore.Page p = current();
        if (p == null || p.src == null) return;
        cropPageId = p.id;
        Intent i = new Intent(this, CropActivity.class);
        i.putExtra(CropActivity.EXTRA_PATH, doc.file(p.src).getPath());
        i.putExtra(CropActivity.EXTRA_MODE, CropActivity.MODE_EDIT);
        if (p.quad != null) i.putExtra(CropActivity.EXTRA_QUAD, p.quad);
        i.putExtra(CropActivity.EXTRA_AUTO, p.auto);
        startActivityForResult(i, REQ_CROP);
    }

    private void retake() {
        final DocStore.Page p = current();
        if (p == null) return;
        if (pending > 0) {
            showWait("Đang xử lý ảnh…");
            afterPending = new Runnable() {
                @Override
                public void run() {
                    retake();
                }
            };
            return;
        }
        Intent i = new Intent(this, CameraActivity.class);
        i.putExtra(CameraActivity.EXTRA_DOC, doc.id);
        i.putExtra(CameraActivity.EXTRA_RETAKE, p.id);
        startActivityForResult(i, REQ_RETAKE);
    }

    private void deleteCurrent() {
        final DocStore.Page p = current();
        if (p == null) return;
        Ui.confirm(this, "Xoá trang này?", null, "Xoá", new Runnable() {
            @Override
            public void run() {
                processing.remove(p.id);
                gen.put(p.id, -1);
                DocStore.removePage(doc, p);
                DocStore.save(doc);
                if (doc.pages.isEmpty()) {
                    if (!fromCamera) DocStore.delete(doc);
                    setResult(fromCamera ? RESULT_CANCELED : RESULT_OK);
                    finish();
                    return;
                }
                strip.dataChanged();
                updateIndicator();
                if (filterPanel.getVisibility() == View.VISIBLE) refreshFilterPanel();
            }
        });
    }

    private void rename() {
        Ui.prompt(this, "Đổi tên tài liệu", doc.title, new Ui.Cb<String>() {
            @Override
            public void done(String v) {
                doc.title = v;
                DocStore.save(doc);
                titleView.setText(v);
            }
        });
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_CROP) {
            if (res != RESULT_OK || data == null || cropPageId == null) return;
            DocStore.Page p = doc.page(cropPageId);
            float[] q = data.getFloatArrayExtra(CropActivity.EXTRA_QUAD);
            if (p == null || q == null) return;
            p.quad = q;
            p.auto = data.getBooleanExtra(CropActivity.EXTRA_AUTO, false);
            int[] sz = data.getIntArrayExtra(CropActivity.EXTRA_SIZE);
            if (sz != null && sz.length == 2 && sz[0] > 0) {
                p.srcW = sz[0];
                p.srcH = sz[1];
            }
            DocStore.save(doc);
            schedule(p);
        } else if (req == REQ_RETAKE) {
            if (res != RESULT_OK) return;
            DocStore.Doc d2 = DocStore.load(this, doc.id);
            if (d2 == null) return;
            int idx = strip.getIndex();
            doc = d2;
            gen.clear();
            processing.clear();
            strip.dataChanged();
            strip.setIndex(idx, false);
            for (DocStore.Page p : doc.pages) if (!upToDate(p)) schedule(p);
            updateIndicator();
        }
    }

    /** done=true: lưu tài liệu; false: quay lại màn hình chụp. */
    private void finishEditing(final boolean done) {
        if (pending > 0) {
            showWait("Đang xử lý ảnh…");
            afterPending = new Runnable() {
                @Override
                public void run() {
                    finishEditing(done);
                }
            };
            return;
        }
        if (done && !finishRetried) {
            boolean any = false;
            for (DocStore.Page p : doc.pages) {
                if (!upToDate(p)) {
                    // trang chưa xử lý được -> thử lại một lần
                    schedule(p);
                    any = true;
                }
            }
            if (any) {
                finishRetried = true;
                finishEditing(true);
                return;
            }
        }
        if (done) {
            doc.draft = false;
            doc.modified = System.currentTimeMillis();
        }
        DocStore.save(doc);
        Processor.gc(doc.dir, Processor.referenced(doc));
        if (done) {
            if (!fromCamera && isNew) {
                Intent d = new Intent(this, DocumentActivity.class);
                d.putExtra(DocumentActivity.EXTRA_DOC, doc.id);
                startActivity(d);
            }
            setResult(RESULT_OK);
        } else {
            setResult(RESULT_CANCELED);
        }
        finish();
    }

    @Override
    public void onBackPressed() {
        if (filterPanel != null && filterPanel.getVisibility() == View.VISIBLE) {
            filterPanel.setVisibility(View.GONE);
            return;
        }
        if (fromCamera) {
            finishEditing(false);
            return;
        }
        if (isNew && doc.draft) {
            Ui.confirm(this, "Bỏ tài liệu này?", "Các trang vừa nhập sẽ không được lưu.", "Bỏ", new Runnable() {
                @Override
                public void run() {
                    afterPending = null;
                    DocStore.delete(doc);
                    setResult(RESULT_CANCELED);
                    finish();
                }
            });
            return;
        }
        finishEditing(true);
    }

    @Override
    protected void onDestroy() {
        dismissWait();
        super.onDestroy();
    }
}
