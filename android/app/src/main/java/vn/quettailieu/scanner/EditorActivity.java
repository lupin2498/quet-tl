package vn.quettailieu.scanner;

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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

import vn.quettailieu.scanner.core.Enhancer;

/**
 * Chỉnh sửa các trang. Thanh công cụ chia ba nhóm như CamScanner:
 * Ảnh (Cắt, Xoay trái, Bộ lọc, Xóa thông minh, Chụp lại) · Đánh dấu (Ký tên, Thêm văn bản, Brush, Khảm, Thêm hình mờ)
 * · Trang (Cắt dán, Tiêu đề trang). Kèm So sánh, Xoá trang, lời nhắc gỡ ngón tay; ✓ để lưu.
 */
public class EditorActivity extends BaseActivity {
    static final String EXTRA_DOC = "doc";
    static final String EXTRA_FROM_CAMERA = "fromCamera";
    static final String EXTRA_PAGE = "page";
    static final String EXTRA_NEW = "isNew";

    private static final int REQ_CROP = 21;
    private static final int REQ_RETAKE = 22;
    private static final int REQ_ERASE = 23;
    private static final int REQ_MARKUP = 24;

    private static final int[] FILTER_MODES = Enhancer.MODES;
    private static final String[] TAB_NAMES = {"Ảnh", "Đánh dấu", "Trang"};

    private DocStore.Doc doc;
    private boolean fromCamera;
    private boolean isNew;
    private PageStripView strip;
    private TextView titleView, indicator;
    private ImageView prevBtn, nextBtn;
    private LinearLayout filterPanel, filterRow;
    private TextView applyAllBtn;
    private boolean applyAll;
    private LinearLayout toolArea, banner;
    private HorizontalScrollView toolScroll;
    private final TextView[] tabViews = new TextView[TAB_NAMES.length];
    private final View[] tabAnchors = new View[TAB_NAMES.length];
    private int activeTab = -1;
    /** Bộ lọc của từng trang lúc mở bảng lọc (để nút ✕ trả lại). */
    private HashMap<String, Integer> filterBackup;
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
    /** Trang đang mở ở màn hình Xóa thông minh / Đánh dấu. */
    private String editPageId;
    private int dispMax;
    private boolean finishRetried;
    /** Có thay đổi nội dung (lọc, xoay, cắt, xoá, chụp lại, đổi tên) -> cập nhật thời điểm sửa khi lưu. */
    private boolean dirty;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        doc = DocStore.open(this, getIntent().getStringExtra(EXTRA_DOC));
        if (doc == null || doc.pages.isEmpty()) {
            Ui.toast(this, "Tài liệu trống");
            setResult(RESULT_CANCELED);
            finish();
            return;
        }
        fromCamera = getIntent().getBooleanExtra(EXTRA_FROM_CAMERA, false);
        isNew = getIntent().getBooleanExtra(EXTRA_NEW, false);
        if (state != null) {
            cropPageId = state.getString("cropPage");
            editPageId = state.getString("editPage");
            dirty = state.getBoolean("dirty", false);
        }
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
        if (editPageId != null) out.putString("editPage", editPageId);
        out.putBoolean("dirty", dirty);
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
                updateBanner();
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

        banner = buildBanner();
        banner.setVisibility(View.GONE);
        FrameLayout.LayoutParams bl = Ui.frame(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        bl.topMargin = Ui.dp(52);
        bl.leftMargin = bl.rightMargin = Ui.dp(14);
        content.addView(banner, bl);
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

        toolArea = new LinearLayout(this);
        toolArea.setOrientation(LinearLayout.VERTICAL);
        toolArea.setBackgroundColor(Ui.CARD);

        // hàng thẻ: Ảnh | Đánh dấu | Trang — chạm để cuộn tới nhóm công cụ tương ứng
        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setGravity(Gravity.CENTER_VERTICAL);
        tabs.setPadding(Ui.dp(8), 0, Ui.dp(8), 0);
        for (int k = 0; k < TAB_NAMES.length; k++) {
            final int idx = k;
            TextView t = Ui.text(this, TAB_NAMES[k], 14, Ui.TEXT2, true);
            t.setGravity(Gravity.CENTER);
            t.setPadding(Ui.dp(12), 0, Ui.dp(12), 0);
            t.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    scrollToTab(idx);
                }
            });
            tabViews[k] = t;
            tabs.addView(t, Ui.lin(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(34), 0));
        }
        toolArea.addView(tabs, Ui.lin(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(34), 0));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dp(4), 0, Ui.dp(10), 0);
        toolScroll = new HorizontalScrollView(this);
        toolScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout strip2 = new LinearLayout(this);
        strip2.setOrientation(LinearLayout.HORIZONTAL);
        // ---- Ảnh
        tabAnchors[0] = addTool(strip2, R.drawable.ic_crop, "Cắt", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openCrop();
            }
        });
        addTool(strip2, R.drawable.ic_rotate_left, "Bên trái", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                rotateLeft();
            }
        });
        addTool(strip2, R.drawable.ic_filter, "Bộ lọc", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleFilterPanel();
            }
        });
        addTool(strip2, R.drawable.ic_erase, "Xóa thông minh", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openErase();
            }
        });
        addTool(strip2, R.drawable.ic_camera, "Chụp lại", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                retake();
            }
        });
        addDivider(strip2);
        // ---- Đánh dấu
        tabAnchors[1] = addTool(strip2, R.drawable.ic_sign, "Ký tên", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openMarkup(MarkupActivity.SIGN);
            }
        });
        addTool(strip2, R.drawable.ic_text, "Thêm văn bản", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openMarkup(MarkupActivity.TEXT);
            }
        });
        addTool(strip2, R.drawable.ic_brush, "Brush", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openMarkup(MarkupActivity.BRUSH);
            }
        });
        addTool(strip2, R.drawable.ic_mosaic, "Khảm", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openMarkup(MarkupActivity.MOSAIC);
            }
        });
        addTool(strip2, R.drawable.ic_watermark, "Thêm hình mờ", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openMarkup(MarkupActivity.WM);
            }
        });
        addDivider(strip2);
        // ---- Trang
        tabAnchors[2] = addTool(strip2, R.drawable.ic_collage, "Cắt dán", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                collage();
            }
        });
        addTool(strip2, R.drawable.ic_header, "Tiêu đề trang", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                editHeader();
            }
        });
        toolScroll.addView(strip2, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        toolScroll.getViewTreeObserver().addOnScrollChangedListener(new android.view.ViewTreeObserver.OnScrollChangedListener() {
            @Override
            public void onScrollChanged() {
                syncTab();
            }
        });
        bar.addView(toolScroll, Ui.lin(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
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
        LinearLayout.LayoutParams okl = Ui.lin(Ui.dp(60), Ui.dp(42), 0);
        okl.leftMargin = Ui.dp(6);
        bar.addView(ok, okl);
        toolArea.addView(bar, Ui.lin(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(66), 0));
        root.addView(toolArea, Ui.lin(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0));
        setContentView(root);
        setActiveTab(0);
        updateBanner();
    }

    private View addTool(LinearLayout parent, int icon, String label, View.OnClickListener l) {
        LinearLayout b = Ui.toolButton(this, icon, label, l);
        // rộng theo nhãn (tối thiểu 72dp): nhãn dài như "Xóa thông minh" không bị cắt, cả dải cuộn ngang
        b.setPadding(Ui.dp(8), Ui.dp(6), Ui.dp(8), Ui.dp(6));
        b.setMinimumWidth(Ui.dp(72));
        parent.addView(b, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return b;
    }

    private void addDivider(LinearLayout parent) {
        View d = new View(this);
        d.setBackgroundColor(Ui.DIVIDER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(1), Ui.dp(34));
        lp.gravity = Gravity.CENTER_VERTICAL;
        lp.leftMargin = lp.rightMargin = Ui.dp(4);
        parent.addView(d, lp);
    }

    private void setActiveTab(int idx) {
        if (activeTab == idx) return;
        activeTab = idx;
        for (int k = 0; k < tabViews.length; k++) tabViews[k].setTextColor(k == idx ? Ui.ACCENT : Ui.TEXT2);
    }

    private void scrollToTab(int idx) {
        setActiveTab(idx);
        toolScroll.smoothScrollTo(Math.max(0, tabAnchors[idx].getLeft() - Ui.dp(4)), 0);
    }

    /** Khi người dùng tự cuộn dải công cụ: thẻ sáng lên theo nhóm đang nằm ở mép trái. */
    private void syncTab() {
        if (toolScroll == null || tabAnchors[2] == null) return;
        int x = toolScroll.getScrollX() + Ui.dp(40);
        int max = toolScroll.getChildAt(0).getWidth() - toolScroll.getWidth();
        int idx = 0;
        for (int k = 0; k < tabAnchors.length; k++) if (x >= tabAnchors[k].getLeft()) idx = k;
        // cuộn hết sang phải mà nhóm cuối chưa chạm mép trái thì vẫn tính là nhóm cuối
        if (max > 0 && toolScroll.getScrollX() >= max - Ui.dp(2)) idx = tabAnchors.length - 1;
        setActiveTab(idx);
    }

    /** Lời nhắc "Phát hiện ngón tay" kèm nút Gỡ bỏ (chuyển trang sang bộ lọc Thần kỳ). */
    private LinearLayout buildBanner() {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.HORIZONTAL);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setBackground(Ui.rect(0xF22C2B2E, 12));
        b.setPadding(Ui.dp(14), Ui.dp(6), Ui.dp(4), Ui.dp(6));
        TextView t = Ui.text(this, "Phát hiện ngón tay. Thử bộ lọc Thần kỳ.", 13.5f, Ui.TEXT, false);
        b.addView(t, Ui.lin(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView go = Ui.text(this, "Gỡ bỏ", 13.5f, Color.WHITE, true);
        go.setGravity(Gravity.CENTER);
        go.setPadding(Ui.dp(14), 0, Ui.dp(14), 0);
        go.setBackground(Ui.ripple(Ui.rect(Ui.ACCENT, 16), true));
        go.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                removeFinger();
            }
        });
        LinearLayout.LayoutParams gl = Ui.lin(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(32), 0);
        gl.leftMargin = Ui.dp(8);
        b.addView(go, gl);
        ImageView x = Ui.iconButton(this, R.drawable.ic_close, Ui.TEXT2, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                DocStore.Page p = current();
                if (p != null) {
                    p.fingerOff = true;
                    DocStore.save(doc);
                }
                updateBanner();
            }
        });
        x.setContentDescription("Đóng lời nhắc");
        int pad = Ui.dp(10);
        x.setPadding(pad, pad, pad, pad);
        b.addView(x, Ui.lin(Ui.dp(40), Ui.dp(40), 0));
        return b;
    }

    private void updateBanner() {
        if (banner == null) return;
        DocStore.Page p = current();
        boolean show = p != null && p.finger == 1 && !p.fingerOff && p.filter != Enhancer.MAGIC_PRO
                && filterPanel != null && filterPanel.getVisibility() != View.VISIBLE;
        banner.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void removeFinger() {
        DocStore.Page p = current();
        if (p == null) return;
        if (p.filter != Enhancer.MAGIC_PRO) {
            p.filter = Enhancer.MAGIC_PRO;
            dirty = true;
            DocStore.save(doc);
            schedule(p);
        }
        updateBanner();
    }

    private LinearLayout buildFilterPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(0xFF262528);
        panel.setPadding(0, Ui.dp(10), 0, 0);
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
            item.setPadding(Ui.dp(5), 0, Ui.dp(5), 0);
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setBackgroundColor(Color.WHITE);
            int pad = Ui.dp(2);
            iv.setPadding(pad, pad, pad, pad);
            item.addView(iv, new LinearLayout.LayoutParams(Ui.dp(64), Ui.dp(82)));
            TextView t = Ui.text(this, Enhancer.name(mode), 12, Ui.TEXT, false);
            t.setGravity(Gravity.CENTER);
            t.setSingleLine(true);
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
        row.addView(applyAllBtn, Ui.lin(0, Ui.dp(40), 1f));
        panel.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(40)));

        // hàng dưới: ✕ (trả lại bộ lọc cũ) · Bộ lọc · ✓ (giữ)
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Ui.CARD);
        bar.setPadding(Ui.dp(4), 0, Ui.dp(4), 0);
        bar.addView(Ui.desc(Ui.iconButton(this, R.drawable.ic_close, Color.WHITE, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeFilterPanel(false);
            }
        }), "Hủy bộ lọc"));
        TextView title = Ui.text(this, "Bộ lọc", 16, Ui.TEXT, true);
        title.setGravity(Gravity.CENTER);
        title.setContentDescription("Tiêu đề bộ lọc");
        bar.addView(title, Ui.lin(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(Ui.desc(Ui.iconButton(this, R.drawable.ic_check, Ui.ACCENT, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeFilterPanel(true);
            }
        }), "Đóng bộ lọc"));
        panel.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(52)));
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
        // Màn hình đã đóng hoặc đã bị hệ thống tạo lại (đổi cấu hình, thu hồi bộ nhớ): bản mới sẽ tự xử lý tiếp.
        // Bản cũ không được ghi tài liệu hay chạy tiếp việc đang chờ (lưu/đóng) nữa.
        if (!Ui.alive(this)) {
            afterPending = null;
            return;
        }
        DocStore.Page p = doc.page(pid);
        Integer latest = gen.get(pid);
        if (p != null && latest != null && latest == g) {
            processing.remove(pid);
            if (err == null) {
                // chỉ nhận kết quả nếu tham số trang vẫn như lúc bắt đầu xử lý
                if (Processor.outName(p).equals(snap.out)) {
                    p.takeResult(snap);
                    DocStore.save(doc);
                    loadOut(p);
                    updateBanner();
                }
            } else {
                Ui.toast(this, "Lỗi xử lý trang: " + Ui.msg(err));
            }
        }
        if (pending <= 0) {
            pending = 0;
            if (afterPending != null) {
                final Runnable r = afterPending;
                afterPending = null;
                dismissWait();
                // xử lý xong lúc ứng dụng đang ở nền -> đợi người dùng quay lại rồi mới chuyển màn hình
                runWhenResumed(r);
            }
        }
        strip.invalidate();
    }

    @SuppressWarnings("deprecation")
    private void showWait(String msg) {
        if (waitDialog != null || !Ui.alive(this)) return;
        waitDialog = new ProgressDialog(this, android.R.style.Theme_Material_Dialog_Alert);
        waitDialog.setMessage(msg);
        waitDialog.setCancelable(false);
        try {
            waitDialog.show();
        } catch (RuntimeException e) {
            waitDialog = null;
        }
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
        dirty = true;
        DocStore.save(doc);
        schedule(p);
    }

    private void toggleFilterPanel() {
        if (filterPanel.getVisibility() == View.VISIBLE) {
            closeFilterPanel(true);
        } else {
            filterBackup = new HashMap<>();
            for (DocStore.Page p : doc.pages) filterBackup.put(p.id, p.filter);
            filterPanel.setVisibility(View.VISIBLE);
            toolArea.setVisibility(View.GONE);
            updateBanner();
            refreshFilterPanel();
        }
    }

    /** keep=false: trả các trang về bộ lọc lúc mở bảng. */
    private void closeFilterPanel(boolean keep) {
        if (filterPanel.getVisibility() != View.VISIBLE) return;
        if (!keep && filterBackup != null) {
            boolean any = false;
            for (DocStore.Page p : doc.pages) {
                Integer old = filterBackup.get(p.id);
                if (old != null && old != p.filter) {
                    p.filter = old;
                    any = true;
                    schedule(p);
                }
            }
            if (any) DocStore.save(doc);
        }
        filterBackup = null;
        filterPanel.setVisibility(View.GONE);
        toolArea.setVisibility(View.VISIBLE);
        updateBanner();
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
                final Bitmap[] outs = new Bitmap[FILTER_MODES.length];
                try {
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
                    for (int k = 0; k < FILTER_MODES.length; k++) {
                        int[] px = src.clone();
                        Enhancer.apply(px, bw, bh, FILTER_MODES[k]);
                        outs[k] = Bitmap.createBitmap(px, bw, bh, Bitmap.Config.ARGB_8888);
                    }
                } catch (Throwable t) {
                    // thiếu bộ nhớ khi dựng ảnh xem trước bộ lọc: bỏ qua (không được làm ứng dụng dừng)
                    return;
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
                    dirty = true;
                    schedule(p);
                }
            }
        } else if (cur.filter != mode) {
            cur.filter = mode;
            dirty = true;
            schedule(cur);
        }
        DocStore.save(doc);
        refreshFilterPanelSelection();
        updateBanner();
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

    /** Mở màn hình chỉnh sửa trên trang hiện tại; nếu trang còn đang xử lý thì chờ xong rồi mở. */
    private void openErase() {
        final DocStore.Page p = current();
        if (p == null) return;
        if (pending > 0 || p.warp == null || !doc.file(p.warp).exists()) {
            if (pending <= 0) {
                if (processing.contains(p.id)) return;
                schedule(p);
            }
            showWait("Đang xử lý ảnh…");
            afterPending = new Runnable() {
                @Override
                public void run() {
                    DocStore.Page c = current();
                    if (c != null && c.warp != null && doc.file(c.warp).exists()) openErase();
                }
            };
            return;
        }
        editPageId = p.id;
        Intent i = new Intent(this, EraseActivity.class);
        i.putExtra(EraseActivity.EXTRA_DOC, doc.id);
        i.putExtra(EraseActivity.EXTRA_PAGE, p.id);
        startActivityForResult(i, REQ_ERASE);
    }

    private void openMarkup(final int tool) {
        final DocStore.Page p = current();
        if (p == null) return;
        if (pending > 0 || p.warp == null || !doc.file(p.warp).exists()) {
            if (pending <= 0) {
                if (processing.contains(p.id)) return;
                schedule(p);
            }
            showWait("Đang xử lý ảnh…");
            afterPending = new Runnable() {
                @Override
                public void run() {
                    DocStore.Page c = current();
                    if (c != null && c.warp != null && doc.file(c.warp).exists()) openMarkup(tool);
                }
            };
            return;
        }
        editPageId = p.id;
        Intent i = new Intent(this, MarkupActivity.class);
        i.putExtra(MarkupActivity.EXTRA_DOC, doc.id);
        i.putExtra(MarkupActivity.EXTRA_PAGE, p.id);
        i.putExtra(MarkupActivity.EXTRA_TOOL, tool);
        startActivityForResult(i, REQ_MARKUP);
    }

    /** Tiêu đề trang: dòng chữ ở lề trên và/hoặc số trang ở lề dưới, áp cho mọi trang của tài liệu. */
    private void editHeader() {
        final android.widget.EditText et = new android.widget.EditText(this);
        et.setSingleLine(true);
        et.setHint("Dòng chữ ở lề trên (có thể để trống)");
        et.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(Ui.MAX_TITLE)});
        et.setText(doc.header == null ? "" : doc.header.text);
        final android.widget.CheckBox cb = new android.widget.CheckBox(this);
        cb.setText("Đánh số trang ở lề dưới (1/" + doc.pages.size() + ")");
        cb.setTextColor(Ui.TEXT);
        cb.setChecked(doc.header != null && doc.header.pageNum);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(20), Ui.dp(8), Ui.dp(20), 0);
        box.addView(et);
        box.addView(cb);
        android.app.AlertDialog.Builder b = Ui.dialog(this).setTitle("Tiêu đề trang").setView(box)
                .setNegativeButton("Hủy", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        Ui.hideKeyboard(et);
                    }
                })
                .setPositiveButton("Áp dụng", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        Ui.hideKeyboard(et);
                        Edits.Header h = new Edits.Header();
                        h.text = et.getText().toString().trim();
                        h.pageNum = cb.isChecked();
                        setHeader(h.isEmpty() ? null : h);
                    }
                });
        if (doc.header != null) {
            b.setNeutralButton("Gỡ bỏ", new android.content.DialogInterface.OnClickListener() {
                @Override
                public void onClick(android.content.DialogInterface dialog, int which) {
                    Ui.hideKeyboard(et);
                    setHeader(null);
                }
            });
        }
        Ui.showDialog(this, b);
    }

    private void setHeader(Edits.Header h) {
        String before = doc.header == null ? "" : doc.header.text + "|" + doc.header.pageNum;
        String after = h == null ? "" : h.text + "|" + h.pageNum;
        if (before.equals(after)) return;
        doc.header = h;
        dirty = true;
        // save() điền lại tiêu đề từng trang; trang nào đổi thì dựng lại
        DocStore.save(doc);
        for (DocStore.Page p : doc.pages) if (!upToDate(p)) schedule(p);
        Ui.toast(this, h == null ? "Đã gỡ tiêu đề trang" : "Đã áp dụng tiêu đề trang cho " + doc.pages.size() + " trang");
    }

    /** Cắt dán: ghép các trang của tài liệu lên tờ A4 và lưu thành tài liệu mới. */
    private void collage() {
        if (pending > 0) {
            showWait("Đang xử lý ảnh…");
            afterPending = new Runnable() {
                @Override
                public void run() {
                    collage();
                }
            };
            return;
        }
        Ui.menu(this, "Cắt dán: ghép " + doc.pages.size() + " trang lên tờ A4", Collage.LAYOUT_NAMES, new Ui.Cb<Integer>() {
            @Override
            public void done(final Integer which) {
                final ArrayList<File> files;
                try {
                    files = Collage.sources(doc);
                } catch (Exception e) {
                    Ui.toast(EditorActivity.this, Ui.msg(e));
                    return;
                }
                String baseTitle = doc.title == null ? "" : doc.title;
                int max = Ui.MAX_TITLE - 7;
                if (baseTitle.length() > max) {
                    int cut = max;
                    if (Character.isHighSurrogate(baseTitle.charAt(cut - 1))) cut--;
                    baseTitle = baseTitle.substring(0, cut);
                }
                final String title = baseTitle + " - ghép";
                final DocStore.Doc[] made = new DocStore.Doc[1];
                Exporter.runWithProgress(EditorActivity.this, "Đang ghép trang…", new Exporter.Callable() {
                    @Override
                    public void call() throws Exception {
                        made[0] = Collage.make(getApplicationContext(), files, title, which);
                    }
                }, new Exporter.Done() {
                    @Override
                    public void run(Throwable error) {
                        if (error != null || made[0] == null) {
                            Ui.toast(EditorActivity.this, "Không ghép được: " + Ui.msg(error));
                            return;
                        }
                        final String id = made[0].id;
                        runWhenResumed(new Runnable() {
                            @Override
                            public void run() {
                                Ui.toast(EditorActivity.this, "Đã tạo tài liệu ghép");
                                Intent d = new Intent(EditorActivity.this, DocumentActivity.class);
                                d.putExtra(DocumentActivity.EXTRA_DOC, id);
                                startActivity(d);
                            }
                        });
                    }
                });
            }
        });
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
                if (doc.page(p.id) != p) return; // đã xoá (bấm xoá hai lần)
                processing.remove(p.id);
                gen.put(p.id, -1);
                DocStore.removePage(doc, p);
                dirty = true;
                DocStore.save(doc);
                if (doc.pages.isEmpty()) {
                    if (!fromCamera) DocStore.delete(doc);
                    setResult(fromCamera ? RESULT_CANCELED : RESULT_OK);
                    finish();
                    return;
                }
                strip.dataChanged();
                updateIndicator();
                updateBanner();
                if (filterPanel.getVisibility() == View.VISIBLE) refreshFilterPanel();
                // số trang đổi -> tiêu đề trang (nếu có đánh số) của các trang còn lại đổi theo
                for (DocStore.Page q : doc.pages) if (!upToDate(q) && !processing.contains(q.id)) schedule(q);
            }
        });
    }

    private void rename() {
        Ui.prompt(this, "Đổi tên tài liệu", doc.title, new Ui.Cb<String>() {
            @Override
            public void done(String v) {
                if (v.equals(doc.title)) return;
                doc.title = v;
                dirty = true;
                DocStore.save(doc);
                titleView.setText(v);
            }
        });
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (doc == null || strip == null) return; // onCreate đã đóng màn hình (tài liệu không còn)
        if (req == REQ_CROP) {
            if (res != RESULT_OK || data == null || cropPageId == null) return;
            DocStore.Page p = doc.page(cropPageId);
            float[] q = data.getFloatArrayExtra(CropActivity.EXTRA_QUAD);
            if (p == null || q == null || q.length != 8) return;
            // nét xoá / đánh dấu đi theo vùng cắt mới để vẫn nằm đúng chỗ trên giấy
            Edits.remap(p, p.quad, q);
            p.quad = q;
            p.auto = data.getBooleanExtra(CropActivity.EXTRA_AUTO, false);
            int[] sz = data.getIntArrayExtra(CropActivity.EXTRA_SIZE);
            if (sz != null && sz.length == 2 && sz[0] > 0 && sz[1] > 0) {
                p.srcW = sz[0];
                p.srcH = sz[1];
            }
            dirty = true;
            DocStore.save(doc);
            schedule(p);
        } else if (req == REQ_RETAKE) {
            if (res != RESULT_OK) return;
            // màn hình chụp dùng chung đối tượng tài liệu nên trang đã được thay ảnh; chỉ cần vẽ lại và xử lý
            if (doc.deleted || doc.pages.isEmpty()) {
                setResult(RESULT_CANCELED);
                finish();
                return;
            }
            dirty = true;
            int idx = strip.getIndex();
            strip.dataChanged();
            strip.setIndex(idx, false);
            for (DocStore.Page p : doc.pages) if (!upToDate(p)) schedule(p);
            updateIndicator();
            updateBanner();
        } else if (req == REQ_ERASE || req == REQ_MARKUP) {
            String pid = editPageId;
            editPageId = null;
            if (res != RESULT_OK || data == null || pid == null) return;
            DocStore.Page p = doc.page(pid);
            if (p == null) return;
            if (req == REQ_ERASE) {
                String json = data.getStringExtra(EraseActivity.EXTRA_ERASE);
                String v = Edits.eraseToJson(Edits.eraseFromJson(json));
                if (TextUtils.equals(v, p.erase)) return;
                p.erase = v;
            } else {
                String json = data.getStringExtra(MarkupActivity.EXTRA_MARKS);
                String v = Edits.marksToJson(Edits.marksFromJson(json));
                if (TextUtils.equals(v, p.marks)) return;
                p.marks = v;
            }
            dirty = true;
            DocStore.save(doc);
            schedule(p);
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
            // chỉ xem mà không sửa gì thì giữ nguyên thời điểm sửa (không đẩy tài liệu lên đầu danh sách)
            if (doc.draft || dirty) doc.modified = System.currentTimeMillis();
            doc.draft = false;
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
            closeFilterPanel(true);
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
