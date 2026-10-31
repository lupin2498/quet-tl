package vn.quettailieu.scanner;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ImageSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.File;

import vn.quettailieu.scanner.core.Geom;

/** Màn hình chỉnh vùng cắt sau khi chụp (Tự động cắt / Chụp lại / Tiếp tục). */
public class CropActivity extends Activity {
    static final String EXTRA_PATH = "path";
    static final String EXTRA_QUAD = "quad";
    static final String EXTRA_AUTO = "auto";
    static final String EXTRA_SIZE = "size";
    static final String EXTRA_MODE = "mode";
    static final int MODE_CAPTURE = 0;
    static final int MODE_EDIT = 1;
    static final int RESULT_RETAKE = RESULT_FIRST_USER + 1;

    private CropView crop;
    private ProgressBar spinner;
    private TextView autoBtn;
    private String path;
    private int mode;
    private int[] size;
    private float[] detected;
    private boolean autoOn = true;
    private boolean loaded;
    private boolean detecting;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        path = getIntent().getStringExtra(EXTRA_PATH);
        mode = getIntent().getIntExtra(EXTRA_MODE, MODE_CAPTURE);
        float[] init = getIntent().getFloatArrayExtra(EXTRA_QUAD);
        autoOn = getIntent().getBooleanExtra(EXTRA_AUTO, true);
        if (state != null) {
            float[] q = state.getFloatArray("q");
            if (q != null) init = q;
            autoOn = state.getBoolean("auto", autoOn);
            detected = state.getFloatArray("det");
        }
        if (path == null || !new File(path).exists()) {
            Ui.toast(this, "Không tìm thấy ảnh");
            setResult(RESULT_CANCELED);
            finish();
            return;
        }
        buildUi();
        load(init);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (crop != null && loaded) out.putFloatArray("q", crop.getQuad());
        out.putBoolean("auto", autoOn);
        if (detected != null) out.putFloatArray("det", detected);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);

        FrameLayout area = new FrameLayout(this);
        crop = new CropView(this);
        area.addView(crop, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        spinner = new ProgressBar(this);
        spinner.setIndeterminateTintList(ColorStateList.valueOf(Ui.ACCENT));
        area.addView(spinner, Ui.frame(Ui.dp(48), Ui.dp(48), Gravity.CENTER));
        root.addView(area, Ui.lin(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView hint = Ui.text(this, "", 15, Ui.TEXT, true);
        SpannableStringBuilder sb = new SpannableStringBuilder("Kéo tay cầm trong hình để điều chỉnh phạm vi cắt hoặc sử dụng công cụ cắt ");
        int start = sb.length();
        sb.append("  ");
        Drawable ic = getResources().getDrawable(R.drawable.ic_crop, getTheme()).mutate();
        ic.setTint(Ui.TEXT);
        int s = Ui.dp(17);
        ic.setBounds(0, 0, s, s);
        sb.setSpan(new ImageSpan(ic, ImageSpan.ALIGN_BOTTOM), start, start + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.append(" sau đó để điều chỉnh");
        hint.setText(sb);
        hint.setLineSpacing(Ui.dpf(3), 1f);
        hint.setPadding(Ui.dp(18), Ui.dp(10), Ui.dp(18), Ui.dp(6));
        if (mode == MODE_EDIT) hint.setText("Kéo 4 góc hoặc cạnh để chọn đúng vùng trang giấy");
        root.addView(hint, Ui.lin(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(12), 0, Ui.dp(16), 0);

        autoBtn = Ui.text(this, "Tự động cắt", 16, Ui.TEXT, true);
        autoBtn.setGravity(Gravity.CENTER_VERTICAL);
        autoBtn.setCompoundDrawablePadding(Ui.dp(8));
        autoBtn.setPadding(Ui.dp(6), 0, Ui.dp(6), 0);
        autoBtn.setBackground(Ui.ripple(null, true));
        autoBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleAuto();
            }
        });
        row.addView(autoBtn, Ui.lin(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(44), 0));
        row.addView(new View(this), Ui.lin(0, 1, 1f));

        TextView second = Ui.textButton(this, mode == MODE_CAPTURE ? "Chụp lại" : "Hủy", Ui.ACCENT, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setResult(mode == MODE_CAPTURE ? RESULT_RETAKE : RESULT_CANCELED);
                finish();
            }
        });
        row.addView(second, Ui.lin(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(44), 0));
        TextView ok = Ui.primaryButton(this, mode == MODE_CAPTURE ? "Tiếp tục" : "Xong", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                done();
            }
        });
        LinearLayout.LayoutParams okLp = Ui.lin(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(46), 0);
        okLp.leftMargin = Ui.dp(8);
        row.addView(ok, okLp);
        root.addView(row, Ui.lin(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(76), 0));

        setContentView(root);
        crop.setListener(new CropView.Listener() {
            @Override
            public void onQuadChanged(boolean byUser) {
                if (byUser && autoOn) {
                    autoOn = false;
                    updateAuto();
                }
            }
        });
        updateAuto();
    }

    private void updateAuto() {
        int s = Ui.dp(20);
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setColor(Color.TRANSPARENT);
        ring.setStroke(Ui.dp(2), autoOn ? Ui.ACCENT : 0xFF9A999E);
        Drawable d;
        if (autoOn) {
            GradientDrawable dot = Ui.oval(Ui.ACCENT);
            LayerDrawable ld = new LayerDrawable(new Drawable[]{ring, dot});
            int in = Ui.dp(5);
            ld.setLayerInset(1, in, in, in, in);
            d = ld;
        } else {
            d = ring;
        }
        d.setBounds(0, 0, s, s);
        autoBtn.setCompoundDrawables(d, null, null, null);
    }

    private void load(final float[] init) {
        final int maxDisp = Math.min(2048, Math.max(getResources().getDisplayMetrics().widthPixels, getResources().getDisplayMetrics().heightPixels));
        Processor.IO.execute(new Runnable() {
            @Override
            public void run() {
                final int[] sz = ImageUtil.orientedSize(path);
                final Bitmap bm = ImageUtil.decodeOriented(path, maxDisp);
                Processor.Detection det = null;
                if (init == null && bm != null) det = Processor.detect(bm, sz[0] > 0 ? sz[0] : bm.getWidth(), sz[1] > 0 ? sz[1] : bm.getHeight());
                final Processor.Detection fd = det;
                Ui.MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!Ui.alive(CropActivity.this)) return;
                        spinner.setVisibility(View.GONE);
                        if (bm == null) {
                            Ui.toast(CropActivity.this, "Không đọc được ảnh");
                            setResult(RESULT_CANCELED);
                            finish();
                            return;
                        }
                        size = (sz[0] > 0 && sz[1] > 0) ? sz : new int[]{bm.getWidth(), bm.getHeight()};
                        crop.setImage(bm, size[0], size[1]);
                        if (fd != null) {
                            detected = fd.quad;
                            crop.setQuad(fd.quad, false);
                            autoOn = true;
                            if (!fd.found) Ui.toast(CropActivity.this, "Chưa nhận ra mép giấy — hãy kéo các góc để chọn vùng");
                        } else {
                            crop.setQuad(init, false);
                        }
                        loaded = true;
                        updateAuto();
                    }
                });
            }
        });
    }

    private void toggleAuto() {
        if (!loaded) return;
        if (autoOn) {
            autoOn = false;
            crop.setQuad(Geom.fullQuad(size[0], size[1]), true);
            updateAuto();
            return;
        }
        if (detected != null) {
            autoOn = true;
            crop.setQuad(detected, true);
            updateAuto();
            return;
        }
        if (detecting) return;
        detecting = true;
        spinner.setVisibility(View.VISIBLE);
        Processor.IO.execute(new Runnable() {
            @Override
            public void run() {
                final Processor.Detection d = Processor.detect(path);
                Ui.MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        detecting = false;
                        if (!Ui.alive(CropActivity.this)) return;
                        spinner.setVisibility(View.GONE);
                        detected = d.quad;
                        autoOn = true;
                        crop.setQuad(d.quad, true);
                        updateAuto();
                        if (!d.found) Ui.toast(CropActivity.this, "Chưa nhận ra mép giấy");
                    }
                });
            }
        });
    }

    private void done() {
        if (!loaded) return;
        if (!crop.isValidQuad()) {
            Ui.toast(this, "Vùng cắt không hợp lệ — hãy chỉnh lại các góc");
            return;
        }
        float[] q = Geom.order(crop.getQuad());
        if (!Geom.isConvex(q)) q = crop.getQuad();
        Intent data = new Intent();
        data.putExtra(EXTRA_PATH, path);
        data.putExtra(EXTRA_QUAD, q);
        data.putExtra(EXTRA_AUTO, autoOn);
        data.putExtra(EXTRA_SIZE, size);
        setResult(RESULT_OK, data);
        finish();
    }

    @Override
    public void onBackPressed() {
        setResult(RESULT_CANCELED);
        finish();
    }
}
