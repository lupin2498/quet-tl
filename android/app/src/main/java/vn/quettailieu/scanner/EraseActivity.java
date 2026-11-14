package vn.quettailieu.scanner;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import vn.quettailieu.scanner.core.Enhancer;
import vn.quettailieu.scanner.core.Retouch;

/**
 * "Xóa thông minh": Bút (tô nền theo nét), Hình chữ nhật, Thần kỳ (xoá cả vật thể dính liền nét tô),
 * Viết tay (xoá nét bút màu trên cả trang), Xóa văn bản (xoá chữ, giữ đường kẻ bảng). Có hoàn tác / làm lại / so sánh.
 * Kết quả trả về là danh sách thao tác (JSON); ảnh độ phân giải đầy đủ do Processor dựng lại sau.
 * Nét xoá áp lên ảnh TRƯỚC khi lọc nên ảnh xem trước ở đây qua bộ lọc với geometric = false: "Làm phẳng" chỉ làm sạch,
 * bước nắn thẳng chạy khi dựng ảnh kết quả (sau nét xoá).
 */
public class EraseActivity extends BaseActivity {
    static final String EXTRA_DOC = "doc";
    static final String EXTRA_PAGE = "pageId";
    static final String EXTRA_ERASE = "erase";

    private static final int[] TOOLS = {Retouch.PEN, Retouch.RECT, Retouch.MAGIC, Retouch.HAND, Retouch.TEXT};
    private static final String[] TOOL_NAMES = {"Bút", "Hình chữ nhật", "Thần kỳ", "Viết tay", "Xóa văn bản"};
    private static final int[] TOOL_ICONS = {R.drawable.ic_pen, R.drawable.ic_rect, R.drawable.ic_magic, R.drawable.ic_hand, R.drawable.ic_text_erase};
    private static final String[] HINTS = {
            "Tô lên phần muốn xóa",
            "Kéo một hình chữ nhật quanh phần muốn xóa",
            "Tô sơ qua vật thể (vết bẩn, con dấu, chữ ký…) để xóa cả vật thể",
            "Chạm “Viết tay” để xóa nét bút màu trên cả trang",
            "Tô lên chữ muốn xóa — đường kẻ bảng được giữ lại"};
    private static final float[] SIZES = {12f, 22f, 38f};
    private static final String[] SIZE_NAMES = {"Nét nhỏ", "Nét vừa", "Nét lớn"};

    private DocStore.Doc doc;
    private DocStore.Page page;
    private EraseView view;
    private TextView hint;
    private ProgressBar spinner;
    private ImageView undoBtn, redoBtn;
    private LinearLayout sizeRow;
    private final LinearLayout[] toolBtns = new LinearLayout[TOOLS.length];
    private final View[] sizeBtns = new View[SIZES.length];

    private final ArrayList<Retouch.Op> ops = new ArrayList<>();
    private final ArrayList<Retouch.Op> redo = new ArrayList<>();
    private int tool = Retouch.PEN;
    private int sizeIndex = 1;

    // ---- trạng thái xử lý: chỉ luồng worker đụng tới base / erased
    private ExecutorService worker;
    private int[] base, erased;
    private int bw, bh;
    private int filter;
    private int busy;
    private boolean loaded, failed;
    private Bitmap shown, original;
    private boolean comparing;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        doc = DocStore.open(this, getIntent().getStringExtra(EXTRA_DOC));
        page = doc == null ? null : doc.page(getIntent().getStringExtra(EXTRA_PAGE));
        if (page == null || page.warp == null) {
            Ui.toast(this, "Trang chưa sẵn sàng");
            setResult(RESULT_CANCELED);
            finish();
            return;
        }
        filter = page.filter;
        if (state != null) {
            ops.addAll(Edits.eraseFromJson(state.getString("ops")));
            redo.addAll(Edits.eraseFromJson(state.getString("redo")));
            tool = state.getInt("tool", Retouch.PEN);
            sizeIndex = Math.max(0, Math.min(SIZES.length - 1, state.getInt("size", 1)));
        } else {
            ops.addAll(Edits.eraseFromJson(page.erase));
        }
        worker = Executors.newSingleThreadExecutor();
        buildUi();
        selectTool(tool == Retouch.HAND ? Retouch.PEN : tool);
        load();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        String o = Edits.eraseToJson(ops), r = Edits.eraseToJson(redo);
        out.putString("ops", o == null ? "" : o);
        out.putString("redo", r == null ? "" : r);
        out.putInt("tool", tool);
        out.putInt("size", sizeIndex);
    }

    @Override
    protected void onDestroy() {
        if (worker != null) worker.shutdownNow();
        super.onDestroy();
    }

    // =================================================================== giao diện

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG_DARK);

        FrameLayout content = new FrameLayout(this);
        view = new EraseView(this);
        view.setContentDescription("Trang đang xóa");
        view.setListener(new EraseView.Listener() {
            @Override
            public void onOp(Retouch.Op op) {
                addOp(op);
            }
        });
        content.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        hint = Ui.text(this, "", 13, Ui.TEXT, false);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(Ui.dp(12), Ui.dp(6), Ui.dp(12), Ui.dp(6));
        hint.setBackground(Ui.rect(0x99333236, 14));
        FrameLayout.LayoutParams hl = Ui.frame(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        hl.topMargin = Ui.dp(10);
        hl.leftMargin = hl.rightMargin = Ui.dp(16);
        content.addView(hint, hl);

        spinner = new ProgressBar(this);
        spinner.setIndeterminateTintList(ColorStateList.valueOf(Ui.ACCENT));
        spinner.setVisibility(View.GONE);
        content.addView(spinner, Ui.frame(Ui.dp(40), Ui.dp(40), Gravity.CENTER));

        LinearLayout ur = new LinearLayout(this);
        ur.setOrientation(LinearLayout.HORIZONTAL);
        undoBtn = roundButton(R.drawable.ic_undo, "Hoàn tác", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                undo();
            }
        });
        redoBtn = roundButton(R.drawable.ic_redo, "Làm lại", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                redo();
            }
        });
        ur.addView(undoBtn, Ui.lin(Ui.dp(38), Ui.dp(38), 0));
        LinearLayout.LayoutParams rl = Ui.lin(Ui.dp(38), Ui.dp(38), 0);
        rl.leftMargin = Ui.dp(10);
        ur.addView(redoBtn, rl);
        FrameLayout.LayoutParams ul = Ui.frame(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.LEFT);
        ul.leftMargin = Ui.dp(14);
        ul.bottomMargin = Ui.dp(10);
        content.addView(ur, ul);

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
                    setComparing(true);
                    v.setAlpha(0.7f);
                } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                    setComparing(false);
                    v.setAlpha(1f);
                }
                return true;
            }
        });
        FrameLayout.LayoutParams cl = Ui.frame(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(32), Gravity.BOTTOM | Gravity.RIGHT);
        cl.rightMargin = Ui.dp(14);
        cl.bottomMargin = Ui.dp(13);
        content.addView(compare, cl);
        root.addView(content, Ui.lin(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        sizeRow = new LinearLayout(this);
        sizeRow.setOrientation(LinearLayout.HORIZONTAL);
        sizeRow.setGravity(Gravity.CENTER);
        sizeRow.setBackgroundColor(0xFF262528);
        TextView sl = Ui.text(this, "Cỡ nét", 13, Ui.TEXT2, false);
        LinearLayout.LayoutParams sll = Ui.lin(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0);
        sll.rightMargin = Ui.dp(10);
        sizeRow.addView(sl, sll);
        for (int k = 0; k < SIZES.length; k++) {
            final int idx = k;
            FrameLayout cell = new FrameLayout(this);
            View dot = new View(this);
            int d = Ui.dp(8 + 6 * k);
            cell.addView(dot, Ui.frame(d, d, Gravity.CENTER));
            cell.setContentDescription(SIZE_NAMES[k]);
            cell.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    sizeIndex = idx;
                    updateSizes();
                }
            });
            sizeBtns[k] = dot;
            sizeRow.addView(cell, Ui.lin(Ui.dp(52), Ui.dp(40), 0));
        }
        root.addView(sizeRow, Ui.lin(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(40), 0));

        for (int k = 0; k < TOOLS.length; k++) {
            final int t = TOOLS[k];
            toolBtns[k] = Ui.toolButton(this, TOOL_ICONS[k], TOOL_NAMES[k], new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (t == Retouch.HAND) eraseHandwriting();
                    else selectTool(t);
                }
            });
        }
        root.addView(Ui.toolRow(this, toolBtns, Ui.CARD), Ui.lin(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(66), 0));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Ui.BG_DARK);
        bar.setPadding(Ui.dp(4), 0, Ui.dp(4), 0);
        bar.addView(Ui.desc(Ui.iconButton(this, R.drawable.ic_close, Color.WHITE, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cancel();
            }
        }), "Hủy"));
        TextView title = Ui.text(this, "Xóa thông minh", 16, Ui.TEXT, true);
        title.setGravity(Gravity.CENTER);
        bar.addView(title, Ui.lin(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(Ui.desc(Ui.iconButton(this, R.drawable.ic_check, Ui.ACCENT, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                done();
            }
        }), "Áp dụng"));
        root.addView(bar, Ui.lin(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(52), 0));
        setContentView(root);
        updateSizes();
        updateUndo();
    }

    private ImageView roundButton(int icon, String desc, View.OnClickListener l) {
        ImageView b = Ui.iconButton(this, icon, Color.WHITE, l);
        b.setBackground(Ui.oval(0x99333236));
        int p = Ui.dp(9);
        b.setPadding(p, p, p, p);
        b.setContentDescription(desc);
        return b;
    }

    private void selectTool(int t) {
        tool = t;
        view.setTool(t);
        for (int k = 0; k < TOOLS.length; k++) {
            boolean sel = TOOLS[k] == t;
            int col = sel ? Ui.ACCENT : Ui.TEXT;
            ((ImageView) toolBtns[k].getChildAt(0)).setImageTintList(ColorStateList.valueOf(col));
            ((TextView) toolBtns[k].getChildAt(1)).setTextColor(col);
            toolBtns[k].setSelected(sel);
            if (sel) hint.setText(HINTS[k]);
        }
        sizeRow.setVisibility(t == Retouch.RECT ? View.INVISIBLE : View.VISIBLE);
    }

    private void updateSizes() {
        view.setBrushDp(SIZES[sizeIndex]);
        for (int k = 0; k < SIZES.length; k++) sizeBtns[k].setBackground(Ui.oval(k == sizeIndex ? Ui.ACCENT : 0xFF8A898E));
    }

    private void updateUndo() {
        undoBtn.setAlpha(ops.isEmpty() ? 0.35f : 1f);
        redoBtn.setAlpha(redo.isEmpty() ? 0.35f : 1f);
    }

    private void setBusy(int delta) {
        busy = Math.max(0, busy + delta);
        spinner.setVisibility(busy > 0 ? View.VISIBLE : View.GONE);
    }

    // =================================================================== xử lý ảnh xem trước

    private void load() {
        final File dir = doc.dir;
        final DocStore.Page snap = page.copy();
        final ArrayList<Retouch.Op> all = new ArrayList<>(ops);
        setBusy(1);
        worker.execute(new Runnable() {
            @Override
            public void run() {
                Bitmap out = null, orig = null;
                try {
                    Bitmap warp = Processor.loadWarp(dir, snap, Processor.PREVIEW_MAX);
                    if (warp != null) {
                        bw = warp.getWidth();
                        bh = warp.getHeight();
                        base = ImageUtil.pixels(warp);
                        warp.recycle();
                        // nét xoá áp lên ảnh TRƯỚC khi lọc: xem trước bộ lọc không dời điểm ảnh (Làm phẳng chỉ làm sạch)
                        orig = Processor.process(base, bw, bh, null, filter, false);
                        erased = base.clone();
                        if (all.isEmpty()) {
                            out = orig;
                        } else {
                            Retouch.applyErase(erased, bw, bh, all);
                            out = filtered(erased);
                        }
                    }
                } catch (Throwable t) {
                    out = null;
                }
                final Bitmap fo = out, fg = orig;
                Ui.MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!Ui.alive(EraseActivity.this)) return;
                        setBusy(-1);
                        if (fo == null) {
                            failed = true;
                            Ui.toast(EraseActivity.this, "Không mở được trang để chỉnh sửa");
                            setResult(RESULT_CANCELED);
                            finish();
                            return;
                        }
                        loaded = true;
                        original = fg;
                        shown = fo;
                        view.setImage(fo, page.rot);
                    }
                });
            }
        });
    }

    /**
     * Chạy trên worker: áp bộ lọc của trang lên bản sao của ảnh đã xoá — không dời điểm ảnh (bước nắn thẳng của
     * "Làm phẳng" bỏ qua), để nét xoá vẽ trên màn hình trùng vị trí trên ảnh trước khi lọc.
     */
    private Bitmap filtered(int[] src) {
        int[] px = src.clone();
        Enhancer.apply(px, bw, bh, filter, false);
        return Bitmap.createBitmap(px, bw, bh, Bitmap.Config.ARGB_8888);
    }

    private void show(Bitmap b) {
        Bitmap old = shown;
        shown = b;
        if (!comparing) view.swapImage(b);
        if (old != null && old != original && old != b) old.recycle();
    }

    /** Người dùng vừa vẽ xong một thao tác: áp thêm lên ảnh đang có rồi lọc lại. */
    private void addOp(final Retouch.Op op) {
        if (!loaded || failed) return;
        ops.add(op);
        redo.clear();
        updateUndo();
        setBusy(1);
        worker.execute(new Runnable() {
            @Override
            public void run() {
                boolean changed = false;
                Bitmap out = null;
                try {
                    if (op.kind == Retouch.HAND) {
                        changed = Retouch.erase(erased, bw, bh, Retouch.HAND, null);
                    } else {
                        byte[] m = new byte[bw * bh];
                        Retouch.opMask(m, bw, bh, op);
                        changed = Retouch.erase(erased, bw, bh, op.kind, m);
                    }
                    if (changed) out = filtered(erased);
                } catch (Throwable t) {
                    changed = false;
                }
                final boolean ch = changed;
                final Bitmap fo = out;
                Ui.MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!Ui.alive(EraseActivity.this)) return;
                        setBusy(-1);
                        if (!ch || fo == null) {
                            // không có gì bị xoá: bỏ thao tác khỏi danh sách
                            ops.remove(op);
                            redo.remove(op);
                            updateUndo();
                            if (op.kind == Retouch.HAND) Ui.toast(EraseActivity.this, "Không tìm thấy nét bút màu trên trang");
                            else if (op.kind == Retouch.TEXT) Ui.toast(EraseActivity.this, "Không có chữ trong vùng vừa tô");
                            return;
                        }
                        show(fo);
                    }
                });
            }
        });
    }

    /** Dựng lại từ đầu theo danh sách thao tác hiện tại (sau hoàn tác / làm lại). */
    private void rebuild() {
        if (!loaded || failed) return;
        final ArrayList<Retouch.Op> all = new ArrayList<>(ops);
        setBusy(1);
        worker.execute(new Runnable() {
            @Override
            public void run() {
                Bitmap out = null;
                try {
                    erased = base.clone();
                    Retouch.applyErase(erased, bw, bh, all);
                    out = filtered(erased);
                } catch (Throwable ignored) {
                }
                final Bitmap fo = out;
                Ui.MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!Ui.alive(EraseActivity.this)) return;
                        setBusy(-1);
                        if (fo != null) show(fo);
                    }
                });
            }
        });
    }

    private void eraseHandwriting() {
        if (!loaded || failed) return;
        Retouch.Op op = new Retouch.Op(Retouch.HAND, 0f, new float[0]);
        addOp(op);
    }

    private void undo() {
        if (ops.isEmpty() || !loaded) return;
        redo.add(ops.remove(ops.size() - 1));
        updateUndo();
        rebuild();
    }

    private void redo() {
        if (redo.isEmpty() || !loaded) return;
        ops.add(redo.remove(redo.size() - 1));
        updateUndo();
        rebuild();
    }

    private void setComparing(boolean on) {
        if (!loaded || comparing == on) return;
        comparing = on;
        Bitmap b = on ? original : shown;
        if (b != null && !b.isRecycled()) view.swapImage(b);
    }

    // =================================================================== kết thúc

    private void done() {
        if (!loaded) return;
        if (busy > 0) {
            Ui.toast(this, "Đang xử lý, chờ một chút…");
            return;
        }
        Intent data = new Intent();
        String json = Edits.eraseToJson(ops);
        data.putExtra(EXTRA_ERASE, json == null ? "" : json);
        data.putExtra(EXTRA_PAGE, page.id);
        setResult(RESULT_OK, data);
        finish();
    }

    private void cancel() {
        setResult(RESULT_CANCELED);
        finish();
    }

    @Override
    public void onBackPressed() {
        cancel();
    }
}
