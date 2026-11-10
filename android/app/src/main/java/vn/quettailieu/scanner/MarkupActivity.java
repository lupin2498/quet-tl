package vn.quettailieu.scanner;

import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;

/**
 * "Đánh dấu": Ký tên, Thêm văn bản, Brush (vẽ tay), Khảm (che thông tin), Thêm hình mờ.
 * Kết quả trả về là danh sách đánh dấu (JSON); ảnh độ phân giải đầy đủ do Processor dựng lại sau.
 */
public class MarkupActivity extends BaseActivity {
    static final String EXTRA_DOC = "doc";
    static final String EXTRA_PAGE = "pageId";
    static final String EXTRA_TOOL = "tool";
    static final String EXTRA_MARKS = "marks";

    static final int SIGN = 0, TEXT = 1, BRUSH = 2, MOSAIC = 3, WM = 4;
    private static final String[] TOOL_NAMES = {"Ký tên", "Thêm văn bản", "Brush", "Khảm", "Thêm hình mờ"};
    private static final int[] TOOL_ICONS = {R.drawable.ic_sign, R.drawable.ic_text, R.drawable.ic_brush, R.drawable.ic_mosaic, R.drawable.ic_watermark};
    private static final String[] HINTS = {
            "Chọn chữ ký rồi kéo tới vị trí cần ký",
            "Kéo để di chuyển, kéo nút góc để đổi cỡ, chạm lần nữa để sửa",
            "Vẽ trực tiếp lên trang",
            "Tô lên phần cần che",
            "Hình mờ phủ chéo toàn trang"};
    static final int[] COLORS = {0xFF1A1A1A, 0xFFE53935, 0xFF1E50C8, 0xFF1B8E3E, 0xFFF9A825, 0xFFFFFFFF};
    private static final String[] COLOR_NAMES = {"Màu đen", "Màu đỏ", "Màu xanh dương", "Màu xanh lá", "Màu vàng", "Màu trắng"};
    private static final float[] SIZES = {4f, 8f, 15f};
    private static final String[] SIZE_NAMES = {"Nét nhỏ", "Nét vừa", "Nét lớn"};
    private static final int MAX_UNDO = 40;

    private DocStore.Doc doc;
    private DocStore.Page page;
    private MarkView view;
    private MarkRender render;
    private TextView hint;
    private ProgressBar spinner;
    private ImageView undoBtn, redoBtn;
    private LinearLayout options;
    private final LinearLayout[] toolBtns = new LinearLayout[TOOL_NAMES.length];
    private FrameLayout padLayer;
    private SignPadView pad;

    private int tool = BRUSH;
    private int colorIndex = 0;
    private int sizeIndex = 1;
    private boolean loaded;
    private boolean padOpen;
    private Bitmap base;
    private final ArrayList<String> undo = new ArrayList<>();
    private final ArrayList<String> redo = new ArrayList<>();

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
        render = new MarkRender(doc.dir);
        buildUi();
        ArrayList<Edits.Mark> initial;
        boolean first = state == null;
        if (state != null) {
            initial = Edits.marksFromJson(state.getString("marks"));
            ArrayList<String> u = state.getStringArrayList("undo"), r = state.getStringArrayList("redo");
            if (u != null) undo.addAll(u);
            if (r != null) redo.addAll(r);
            tool = state.getInt("tool", BRUSH);
            colorIndex = Math.max(0, Math.min(COLORS.length - 1, state.getInt("color", 0)));
            sizeIndex = Math.max(0, Math.min(SIZES.length - 1, state.getInt("size", 1)));
            padOpen = state.getBoolean("pad", false);
        } else {
            initial = Edits.marksFromJson(page.marks);
            tool = Math.max(SIGN, Math.min(WM, getIntent().getIntExtra(EXTRA_TOOL, BRUSH)));
        }
        view.setMarks(initial);
        selectTool(tool, false);
        updateUndo();
        load(first);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (view == null) return;
        out.putString("marks", json());
        out.putStringArrayList("undo", undo);
        out.putStringArrayList("redo", redo);
        out.putInt("tool", tool);
        out.putInt("color", colorIndex);
        out.putInt("size", sizeIndex);
        out.putBoolean("pad", padOpen);
    }

    @Override
    protected void onDestroy() {
        if (render != null) render.release();
        super.onDestroy();
    }

    private String json() {
        String j = Edits.marksToJson(view.marks);
        return j == null ? "" : j;
    }

    // =================================================================== giao diện

    private void buildUi() {
        FrameLayout frame = new FrameLayout(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG_DARK);

        FrameLayout content = new FrameLayout(this);
        view = new MarkView(this);
        view.setContentDescription("Trang đang đánh dấu");
        view.setRender(render);
        view.setListener(new MarkView.Listener() {
            @Override
            public void onBeforeChange() {
                pushUndo();
            }

            @Override
            public void onChanged() {
                updateUndo();
            }

            @Override
            public void onSelected(int index) {
                refreshOptions();
            }

            @Override
            public void onTapSelected(int index) {
                Edits.Mark m = view.selectedMark();
                if (m != null && m.type == Edits.Mark.TEXT) editText(m);
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
        root.addView(content, Ui.lin(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setBackgroundColor(0xFF262528);
        hs.setFillViewport(true);
        options = new LinearLayout(this);
        options.setOrientation(LinearLayout.HORIZONTAL);
        options.setGravity(Gravity.CENTER_VERTICAL);
        options.setPadding(Ui.dp(10), 0, Ui.dp(10), 0);
        hs.addView(options, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(hs, Ui.lin(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(52), 0));

        for (int k = 0; k < TOOL_NAMES.length; k++) {
            final int t = k;
            toolBtns[k] = Ui.toolButton(this, TOOL_ICONS[k], TOOL_NAMES[k], new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    selectTool(t, true);
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
        TextView title = Ui.text(this, "Đánh dấu", 16, Ui.TEXT, true);
        title.setGravity(Gravity.CENTER);
        bar.addView(title, Ui.lin(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(Ui.desc(Ui.iconButton(this, R.drawable.ic_check, Ui.ACCENT, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                done();
            }
        }), "Áp dụng"));
        root.addView(bar, Ui.lin(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(52), 0));

        frame.addView(root, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        padLayer = buildPad();
        padLayer.setVisibility(View.GONE);
        frame.addView(padLayer, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(frame);
    }

    private ImageView roundButton(int icon, String desc, View.OnClickListener l) {
        ImageView b = Ui.iconButton(this, icon, Color.WHITE, l);
        b.setBackground(Ui.oval(0x99333236));
        int p = Ui.dp(9);
        b.setPadding(p, p, p, p);
        b.setContentDescription(desc);
        return b;
    }

    private TextView chip(String label, View.OnClickListener l) {
        TextView t = Ui.text(this, label, 13, Ui.TEXT, false);
        t.setGravity(Gravity.CENTER);
        t.setPadding(Ui.dp(12), 0, Ui.dp(12), 0);
        t.setBackground(Ui.ripple(Ui.rect(Ui.CARD2, 16), true));
        t.setOnClickListener(l);
        LinearLayout.LayoutParams lp = Ui.lin(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(34), 0);
        lp.rightMargin = Ui.dp(8);
        t.setLayoutParams(lp);
        return t;
    }

    private void addColors() {
        for (int k = 0; k < COLORS.length; k++) {
            final int idx = k;
            FrameLayout cell = new FrameLayout(this);
            View dot = new View(this);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(COLORS[k]);
            g.setStroke(Ui.dp(k == colorIndex ? 3 : 1), k == colorIndex ? Ui.ACCENT : 0xFF8A898E);
            dot.setBackground(g);
            cell.addView(dot, Ui.frame(Ui.dp(26), Ui.dp(26), Gravity.CENTER));
            cell.setContentDescription(COLOR_NAMES[k]);
            cell.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    pickColor(idx);
                }
            });
            options.addView(cell, Ui.lin(Ui.dp(40), Ui.dp(44), 0));
        }
    }

    private void addSizes() {
        for (int k = 0; k < SIZES.length; k++) {
            final int idx = k;
            FrameLayout cell = new FrameLayout(this);
            View dot = new View(this);
            dot.setBackground(Ui.oval(k == sizeIndex ? Ui.ACCENT : 0xFF8A898E));
            int d = Ui.dp(8 + 6 * k);
            cell.addView(dot, Ui.frame(d, d, Gravity.CENTER));
            cell.setContentDescription(SIZE_NAMES[k]);
            cell.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    sizeIndex = idx;
                    view.setBrushDp(SIZES[sizeIndex]);
                    refreshOptions();
                }
            });
            options.addView(cell, Ui.lin(Ui.dp(44), Ui.dp(44), 0));
        }
    }

    /** Dựng lại hàng tuỳ chọn theo công cụ và mục đang chọn. */
    private void refreshOptions() {
        if (options == null) return;
        options.removeAllViews();
        switch (tool) {
            case SIGN: {
                options.addView(chip("＋ Chữ ký mới", new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        openPad();
                    }
                }));
                File[] lib = signLibrary(this);
                for (int k = 0; k < lib.length && k < 12; k++) {
                    final File f = lib[k];
                    ImageView iv = new ImageView(this);
                    iv.setBackground(Ui.rect(0xFFFFFFFF, 6));
                    iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    int p = Ui.dp(3);
                    iv.setPadding(p, p, p, p);
                    try {
                        BitmapFactory.Options o = new BitmapFactory.Options();
                        o.inSampleSize = 4;
                        iv.setImageBitmap(BitmapFactory.decodeFile(f.getPath(), o));
                    } catch (Throwable ignored) {
                    }
                    iv.setContentDescription("Chữ ký " + (k + 1));
                    iv.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            placeSign(f);
                        }
                    });
                    iv.setOnLongClickListener(new View.OnLongClickListener() {
                        @Override
                        public boolean onLongClick(View v) {
                            Ui.confirm(MarkupActivity.this, "Xoá chữ ký đã lưu này?", null, "Xoá", new Runnable() {
                                @Override
                                public void run() {
                                    f.delete();
                                    refreshOptions();
                                }
                            });
                            return true;
                        }
                    });
                    LinearLayout.LayoutParams lp = Ui.lin(Ui.dp(76), Ui.dp(40), 0);
                    lp.rightMargin = Ui.dp(8);
                    options.addView(iv, lp);
                }
                break;
            }
            case TEXT: {
                options.addView(chip("＋ Thêm chữ", new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        newText();
                    }
                }));
                final Edits.Mark sel = view.selectedMark();
                if (sel != null && sel.type == Edits.Mark.TEXT) {
                    options.addView(chip("Sửa chữ", new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            editText(sel);
                        }
                    }));
                }
                addColors();
                break;
            }
            case BRUSH:
                addColors();
                addSizes();
                break;
            case MOSAIC:
                addSizes();
                break;
            case WM: {
                options.addView(chip(watermark() == null ? "＋ Thêm hình mờ" : "Sửa nội dung", new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        editWatermark();
                    }
                }));
                if (watermark() != null) {
                    options.addView(chip("Gỡ hình mờ", new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            Edits.Mark w = watermark();
                            if (w == null) return;
                            pushUndo();
                            view.marks.remove(w);
                            view.invalidate();
                            updateUndo();
                            refreshOptions();
                        }
                    }));
                }
                addColors();
                break;
            }
            default:
                break;
        }
    }

    private void selectTool(int t, boolean user) {
        tool = t;
        for (int k = 0; k < toolBtns.length; k++) {
            int col = k == t ? Ui.ACCENT : Ui.TEXT;
            ((ImageView) toolBtns[k].getChildAt(0)).setImageTintList(ColorStateList.valueOf(col));
            ((TextView) toolBtns[k].getChildAt(1)).setTextColor(col);
            toolBtns[k].setSelected(k == t);
        }
        hint.setText(HINTS[t]);
        view.setTool(t == BRUSH ? MarkView.T_BRUSH : t == MOSAIC ? MarkView.T_MOSAIC : MarkView.T_MOVE);
        view.setColor(COLORS[colorIndex]);
        view.setBrushDp(SIZES[sizeIndex]);
        refreshOptions();
        if (user && loaded) startTool();
    }

    /** Việc cần làm ngay khi vào một công cụ: mở bảng ký / hỏi nội dung chữ / hỏi nội dung hình mờ. */
    private void startTool() {
        if (tool == SIGN) {
            if (signLibrary(this).length == 0) openPad();
        } else if (tool == TEXT) {
            boolean any = false;
            for (Edits.Mark m : view.marks) if (m.type == Edits.Mark.TEXT) any = true;
            if (!any) newText();
        } else if (tool == WM) {
            if (watermark() == null) editWatermark();
        }
    }

    private void pickColor(int idx) {
        colorIndex = idx;
        view.setColor(COLORS[idx]);
        Edits.Mark sel = view.selectedMark();
        if (tool == TEXT && sel != null && sel.type == Edits.Mark.TEXT && sel.color != COLORS[idx]) {
            pushUndo();
            sel.color = COLORS[idx];
            view.invalidate();
            updateUndo();
        } else if (tool == WM && watermark() != null) {
            pushUndo();
            watermark().color = wmColor(COLORS[idx]);
            view.invalidate();
            updateUndo();
        }
        refreshOptions();
    }

    /** Màu hình mờ: màu đã chọn với độ đục ~28%. */
    private static int wmColor(int c) {
        return (c & 0x00FFFFFF) | 0x48000000;
    }

    // =================================================================== nạp ảnh nền

    private void load(final boolean first) {
        final File dir = doc.dir;
        final DocStore.Page snap = page.copy();
        Processor.IO.execute(new Runnable() {
            @Override
            public void run() {
                Bitmap out = null;
                try {
                    Bitmap warp = Processor.loadWarp(dir, snap, Processor.PREVIEW_MAX);
                    if (warp != null) {
                        int w = warp.getWidth(), h = warp.getHeight();
                        int[] px = ImageUtil.pixels(warp);
                        warp.recycle();
                        out = Processor.process(px, w, h, Edits.eraseFromJson(snap.erase), snap.filter);
                    }
                } catch (Throwable t) {
                    out = null;
                }
                final Bitmap fo = out;
                Ui.MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!Ui.alive(MarkupActivity.this)) return;
                        spinner.setVisibility(View.GONE);
                        if (fo == null) {
                            Ui.toast(MarkupActivity.this, "Không mở được trang để chỉnh sửa");
                            setResult(RESULT_CANCELED);
                            finish();
                            return;
                        }
                        base = fo;
                        loaded = true;
                        view.setImage(fo, page.rot);
                        if (padOpen) openPad();
                        else if (first) runWhenResumed(new Runnable() {
                            @Override
                            public void run() {
                                startTool();
                            }
                        });
                    }
                });
            }
        });
    }

    // =================================================================== hoàn tác

    private void pushUndo() {
        undo.add(json());
        if (undo.size() > MAX_UNDO) undo.remove(0);
        redo.clear();
    }

    private void updateUndo() {
        undoBtn.setAlpha(undo.isEmpty() ? 0.35f : 1f);
        redoBtn.setAlpha(redo.isEmpty() ? 0.35f : 1f);
    }

    private void undo() {
        if (undo.isEmpty()) return;
        redo.add(json());
        view.setMarks(Edits.marksFromJson(undo.remove(undo.size() - 1)));
        updateUndo();
        refreshOptions();
    }

    private void redo() {
        if (redo.isEmpty()) return;
        undo.add(json());
        view.setMarks(Edits.marksFromJson(redo.remove(redo.size() - 1)));
        updateUndo();
        refreshOptions();
    }

    // =================================================================== chữ, hình mờ

    /** Góc để mục mới nằm thẳng theo chiều trang đang hiển thị. */
    private int uprightAngle() {
        return (360 - page.rot) % 360;
    }

    private void newText() {
        if (!loaded) return;
        Ui.prompt(this, "Thêm văn bản", "", new Ui.Cb<String>() {
            @Override
            public void done(String v) {
                if (!loaded || !Ui.alive(MarkupActivity.this)) return;
                Edits.Mark m = new Edits.Mark();
                m.type = Edits.Mark.TEXT;
                m.text = v;
                m.color = COLORS[colorIndex];
                m.size = 0.042f * view.shownW() / view.imageW();
                m.angle = uprightAngle();
                // mỗi mục mới lệch xuống một chút để không chồng khít lên mục trước
                int cnt = 0;
                for (Edits.Mark o : view.marks) if (o.type == Edits.Mark.TEXT) cnt++;
                m.x = 0.5f;
                m.y = Math.min(0.9f, 0.42f + 0.05f * (cnt % 8));
                pushUndo();
                view.marks.add(m);
                view.select(view.marks.size() - 1);
                view.invalidate();
                updateUndo();
                refreshOptions();
            }
        });
    }

    private void editText(final Edits.Mark m) {
        Ui.prompt(this, "Sửa văn bản", m.text == null ? "" : m.text, new Ui.Cb<String>() {
            @Override
            public void done(String v) {
                if (!view.marks.contains(m) || v.equals(m.text)) return;
                pushUndo();
                m.text = v;
                view.invalidate();
                updateUndo();
            }
        });
    }

    private Edits.Mark watermark() {
        for (Edits.Mark m : view.marks) if (m.type == Edits.Mark.WM) return m;
        return null;
    }

    private void editWatermark() {
        if (!loaded) return;
        final Edits.Mark cur = watermark();
        Ui.prompt(this, "Nội dung hình mờ", cur == null ? "" : cur.text, new Ui.Cb<String>() {
            @Override
            public void done(String v) {
                if (!loaded || !Ui.alive(MarkupActivity.this)) return;
                Edits.Mark w = watermark();
                if (w != null && v.equals(w.text)) return;
                pushUndo();
                if (w == null) {
                    w = new Edits.Mark();
                    w.type = Edits.Mark.WM;
                    w.color = wmColor(COLORS[colorIndex == 5 ? 0 : colorIndex]);
                    w.size = 0.036f;
                    view.marks.add(w);
                }
                w.text = v;
                view.invalidate();
                updateUndo();
                refreshOptions();
            }
        });
    }

    // =================================================================== chữ ký

    static File signDir(Context c) {
        File d = new File(c.getFilesDir(), "signs");
        d.mkdirs();
        return d;
    }

    /** Các chữ ký đã lưu, mới nhất trước. */
    static File[] signLibrary(Context c) {
        File[] fs = signDir(c).listFiles();
        if (fs == null) return new File[0];
        ArrayList<File> out = new ArrayList<>();
        for (File f : fs) if (f.isFile() && f.getName().endsWith(".png") && f.length() > 0) out.add(f);
        File[] arr = out.toArray(new File[0]);
        Arrays.sort(arr, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return Long.compare(b.lastModified(), a.lastModified());
            }
        });
        return arr;
    }

    /** Đặt chữ ký (file trong thư viện) lên giữa trang; file được chép vào thư mục tài liệu. */
    private void placeSign(File lib) {
        if (!loaded) return;
        String name = "sg_" + lib.getName();
        File dst = new File(doc.dir, name);
        try {
            if (!dst.exists() || dst.length() != lib.length()) {
                FileInputStream in = new FileInputStream(lib);
                try {
                    DocStore.copy(in, dst);
                } finally {
                    in.close();
                }
            }
        } catch (Exception e) {
            Ui.toast(this, "Không dùng được chữ ký: " + Ui.msg(e));
            return;
        }
        Edits.Mark m = new Edits.Mark();
        m.type = Edits.Mark.SIGN;
        m.file = name;
        m.size = 0.34f * view.shownW() / view.imageW();
        m.angle = uprightAngle();
        int cnt = 0;
        for (Edits.Mark o : view.marks) if (o.type == Edits.Mark.SIGN) cnt++;
        m.x = Math.min(0.85f, 0.5f + 0.04f * (cnt % 6));
        m.y = Math.min(0.9f, 0.6f + 0.04f * (cnt % 6));
        pushUndo();
        view.marks.add(m);
        view.select(view.marks.size() - 1);
        view.invalidate();
        updateUndo();
    }

    private FrameLayout buildPad() {
        FrameLayout layer = new FrameLayout(this);
        layer.setBackgroundColor(0xE6101012);
        layer.setClickable(true);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(16), Ui.dp(12), Ui.dp(16), Ui.dp(12));
        TextView title = Ui.text(this, "Ký vào khung bên dưới", 16, Ui.TEXT, true);
        title.setGravity(Gravity.CENTER);
        box.addView(title, Ui.lin(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(40), 0));
        pad = new SignPadView(this);
        pad.setBackground(Ui.rect(0xFFFFFFFF, 10));
        pad.setContentDescription("Khung ký tên");
        box.addView(pad, Ui.lin(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(230), 0));

        LinearLayout colors = new LinearLayout(this);
        colors.setOrientation(LinearLayout.HORIZONTAL);
        colors.setGravity(Gravity.CENTER);
        final int[] inks = {0xFF111111, 0xFF1E3FAE, 0xFFD32F2F};
        final String[] inkNames = {"Mực đen", "Mực xanh", "Mực đỏ"};
        final View[] dots = new View[inks.length];
        for (int k = 0; k < inks.length; k++) {
            final int idx = k;
            FrameLayout cell = new FrameLayout(this);
            View dot = new View(this);
            dots[k] = dot;
            cell.addView(dot, Ui.frame(Ui.dp(26), Ui.dp(26), Gravity.CENTER));
            cell.setContentDescription(inkNames[k]);
            cell.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    pad.setInk(inks[idx]);
                    for (int j = 0; j < dots.length; j++) {
                        GradientDrawable g = new GradientDrawable();
                        g.setShape(GradientDrawable.OVAL);
                        g.setColor(inks[j]);
                        g.setStroke(Ui.dp(j == idx ? 3 : 1), j == idx ? Ui.ACCENT : 0xFF8A898E);
                        dots[j].setBackground(g);
                    }
                }
            });
            colors.addView(cell, Ui.lin(Ui.dp(48), Ui.dp(48), 0));
        }
        box.addView(colors, Ui.lin(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(52), 0));
        ((View) dots[0].getParent()).performClick();

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.CENTER_VERTICAL);
        btns.addView(Ui.textButton(this, "Đóng", Ui.TEXT, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closePad();
            }
        }), Ui.lin(0, Ui.dp(46), 1f));
        btns.addView(Ui.textButton(this, "Ký lại", Ui.TEXT, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pad.clear();
            }
        }), Ui.lin(0, Ui.dp(46), 1f));
        btns.addView(Ui.primaryButton(this, "Lưu chữ ký", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                savePad();
            }
        }), Ui.lin(0, Ui.dp(46), 1.3f));
        box.addView(btns, Ui.lin(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(52), 0));
        layer.addView(box, Ui.frame(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        return layer;
    }

    private void openPad() {
        padOpen = true;
        pad.clear();
        padLayer.setVisibility(View.VISIBLE);
    }

    private void closePad() {
        padOpen = false;
        padLayer.setVisibility(View.GONE);
    }

    private void savePad() {
        Bitmap b = pad.export();
        if (b == null) {
            Ui.toast(this, "Hãy ký vào khung trước khi lưu");
            return;
        }
        File f = new File(signDir(this), "s" + Long.toString(System.currentTimeMillis(), 36) + ".png");
        try {
            OutputStream os = new BufferedOutputStream(new FileOutputStream(f));
            try {
                if (!b.compress(Bitmap.CompressFormat.PNG, 100, os)) throw new java.io.IOException("Không ghi được ảnh chữ ký");
            } finally {
                os.close();
            }
        } catch (Exception e) {
            f.delete();
            Ui.toast(this, "Không lưu được chữ ký: " + Ui.msg(e));
            return;
        } finally {
            b.recycle();
        }
        closePad();
        refreshOptions();
        placeSign(f);
    }

    // =================================================================== kết thúc

    private void done() {
        if (!loaded) return;
        Intent data = new Intent();
        data.putExtra(EXTRA_MARKS, json());
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
        if (padOpen) {
            closePad();
            return;
        }
        cancel();
    }

    // =================================================================== khung ký

    /** Khung ký tên: vẽ bằng ngón tay, xuất ảnh PNG nền trong suốt cắt sát nét ký. */
    static final class SignPadView extends View {
        private final ArrayList<Path> paths = new ArrayList<>();
        private final ArrayList<Integer> inks = new ArrayList<>();
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint guide = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Path cur;
        private float lx, ly;
        private int ink = 0xFF111111;
        private final RectF bounds = new RectF();
        private boolean any;

        SignPadView(Context c) {
            super(c);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeWidth(Ui.dpf(3.2f));
            guide.setColor(0xFFBDBDBD);
            guide.setStrokeWidth(Ui.dpf(1));
        }

        void setInk(int c) {
            ink = c;
        }

        void clear() {
            paths.clear();
            inks.clear();
            cur = null;
            any = false;
            bounds.setEmpty();
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float y = getHeight() * 0.74f;
            c.drawLine(Ui.dp(20), y, getWidth() - Ui.dp(20), y, guide);
            for (int i = 0; i < paths.size(); i++) {
                paint.setColor(inks.get(i));
                c.drawPath(paths.get(i), paint);
            }
        }

        private void grow(float x, float y) {
            if (!any) {
                bounds.set(x, y, x, y);
                any = true;
            } else {
                bounds.union(x, y);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            float x = e.getX(), y = e.getY();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    getParent().requestDisallowInterceptTouchEvent(true);
                    cur = new Path();
                    cur.moveTo(x, y);
                    cur.lineTo(x + 0.01f, y + 0.01f);
                    paths.add(cur);
                    inks.add(ink);
                    lx = x;
                    ly = y;
                    grow(x, y);
                    invalidate();
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (cur != null) {
                        // làm mượt nét bằng đường cong bậc hai qua trung điểm
                        cur.quadTo(lx, ly, (x + lx) / 2f, (y + ly) / 2f);
                        lx = x;
                        ly = y;
                        grow(x, y);
                        invalidate();
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (cur != null) {
                        cur.lineTo(x, y);
                        grow(x, y);
                    }
                    cur = null;
                    invalidate();
                    break;
                default:
                    break;
            }
            return true;
        }

        /** Ảnh nền trong suốt cắt sát nét ký; null nếu chưa ký gì đáng kể. */
        Bitmap export() {
            if (!any || paths.isEmpty()) return null;
            float pad = paint.getStrokeWidth() * 1.5f;
            RectF r = new RectF(bounds);
            r.inset(-pad, -pad);
            r.intersect(0, 0, getWidth(), getHeight());
            int w = Math.round(r.width()), h = Math.round(r.height());
            if (w < Ui.dp(24) && h < Ui.dp(24)) return null;
            w = Math.max(8, w);
            h = Math.max(8, h);
            try {
                Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(b);
                c.translate(-r.left, -r.top);
                for (int i = 0; i < paths.size(); i++) {
                    paint.setColor(inks.get(i));
                    c.drawPath(paths.get(i), paint);
                }
                return b;
            } catch (OutOfMemoryError e) {
                return null;
            }
        }
    }
}
