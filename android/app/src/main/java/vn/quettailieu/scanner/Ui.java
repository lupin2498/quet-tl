package vn.quettailieu.scanner;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Tiện ích giao diện dựng bằng mã (không dùng XML layout). */
final class Ui {
    private Ui() {}

    static final int ACCENT = 0xFF00B596;
    static final int ACCENT_PRESSED = 0xFF00967C;
    static final int BG = 0xFF201F22;
    static final int BG_DARK = 0xFF1B1A1D;
    static final int CARD = 0xFF2C2B2E;
    static final int CARD2 = 0xFF3A393D;
    static final int TEXT = 0xFFFFFFFF;
    static final int TEXT2 = 0xFFA6A5AA;
    static final int DIVIDER = 0x1FFFFFFF;
    static final int RED = 0xFFFF5B4F;
    static final int BADGE = 0xFFFF6A55;

    static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static float density = 3f;

    interface Cb<T> {
        void done(T value);
    }

    static void init(Context c) {
        density = c.getResources().getDisplayMetrics().density;
    }

    static int dp(float v) {
        return Math.round(v * density);
    }

    static float dpf(float v) {
        return v * density;
    }

    static GradientDrawable rect(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dpf(radiusDp));
        return g;
    }

    static GradientDrawable stroke(int fill, int strokeColor, float strokeDp, float radiusDp) {
        GradientDrawable g = rect(fill, radiusDp);
        g.setStroke(Math.max(1, dp(strokeDp)), strokeColor);
        return g;
    }

    static GradientDrawable oval(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        return g;
    }

    /** Hiệu ứng gợn sóng khi chạm; bounded=false cho nút biểu tượng tròn. */
    static Drawable ripple(Drawable content, boolean bounded) {
        ColorStateList rc = ColorStateList.valueOf(0x40FFFFFF);
        if (!bounded) return new RippleDrawable(rc, null, null);
        Drawable mask = content != null ? null : rect(0xFFFFFFFF, 6);
        return new RippleDrawable(rc, content, mask);
    }

    static TextView text(Context c, CharSequence s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setIncludeFontPadding(true);
        return t;
    }

    static ImageView icon(Context c, int res, int tint, int sizeDp) {
        ImageView iv = new ImageView(c);
        iv.setImageResource(res);
        iv.setImageTintList(ColorStateList.valueOf(tint));
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        return iv;
    }

    static <T extends View> T desc(T v, String d) {
        v.setContentDescription(d);
        return v;
    }

    /** Nút biểu tượng 48dp với hiệu ứng chạm tròn. */
    static ImageView iconButton(Context c, int res, int tint, View.OnClickListener l) {
        ImageView iv = new ImageView(c);
        iv.setImageResource(res);
        iv.setImageTintList(ColorStateList.valueOf(tint));
        iv.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        int p = dp(12);
        iv.setPadding(p, p, p, p);
        iv.setBackground(ripple(null, false));
        iv.setOnClickListener(l);
        iv.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(48)));
        return iv;
    }

    /** Nút trên thanh công cụ dưới: biểu tượng + nhãn. */
    static LinearLayout toolButton(Context c, int res, String label, View.OnClickListener l) {
        LinearLayout b = new LinearLayout(c);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(4), dp(6), dp(4), dp(6));
        b.setBackground(ripple(null, true));
        b.setClickable(true);
        b.setOnClickListener(l);
        ImageView iv = icon(c, res, TEXT, 24);
        b.addView(iv);
        TextView t = text(c, label, 11.5f, TEXT, false);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tl.topMargin = dp(3);
        b.addView(t, tl);
        b.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        return b;
    }

    /** Nút chữ nền xanh bo tròn. */
    static TextView primaryButton(Context c, String label, View.OnClickListener l) {
        TextView t = text(c, label, 16, TEXT, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(22), 0, dp(22), 0);
        t.setBackground(new RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), rect(ACCENT, 6), null));
        t.setOnClickListener(l);
        t.setMinWidth(dp(110));
        return t;
    }

    static TextView textButton(Context c, String label, int color, View.OnClickListener l) {
        TextView t = text(c, label, 16, color, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(14), 0, dp(14), 0);
        t.setBackground(ripple(null, true));
        t.setOnClickListener(l);
        return t;
    }

    static FrameLayout.LayoutParams frame(int w, int h, int gravity) {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(w, h);
        p.gravity = gravity;
        return p;
    }

    static LinearLayout.LayoutParams lin(int w, int h, float weight) {
        return new LinearLayout.LayoutParams(w, h, weight);
    }

    static void toast(Context c, String s) {
        Toast.makeText(c.getApplicationContext(), s, Toast.LENGTH_SHORT).show();
    }

    static AlertDialog.Builder dialog(Context c) {
        return new AlertDialog.Builder(c, android.R.style.Theme_Material_Dialog_Alert);
    }

    static void confirm(Context c, String title, String msg, String ok, final Runnable onOk) {
        AlertDialog.Builder b = dialog(c).setTitle(title).setNegativeButton("Hủy", null)
                .setPositiveButton(ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        onOk.run();
                    }
                });
        if (msg != null) b.setMessage(msg);
        b.show();
    }

    /** Hộp thoại nhập chữ (đổi tên). */
    static void prompt(Context c, String title, String initial, final Cb<String> cb) {
        final EditText et = new EditText(c);
        et.setText(initial);
        et.setSingleLine(true);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        et.setImeOptions(EditorInfo.IME_ACTION_DONE);
        et.setSelectAllOnFocus(true);
        FrameLayout box = new FrameLayout(c);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(et);
        final AlertDialog d = dialog(c).setTitle(title).setView(box)
                .setNegativeButton("Hủy", null)
                .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String v = et.getText().toString().trim();
                        if (v.length() > 0) cb.done(v);
                    }
                }).create();
        if (d.getWindow() != null) d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        d.show();
        et.requestFocus();
    }

    /** Danh sách lựa chọn dạng hộp thoại (thay cho PopupMenu). */
    static void menu(Context c, String title, String[] items, final Cb<Integer> cb) {
        AlertDialog.Builder b = dialog(c).setItems(items, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface d, int which) {
                cb.done(which);
            }
        });
        if (title != null) b.setTitle(title);
        b.show();
    }

    static String newTitle(long t) {
        return "Scan " + new SimpleDateFormat("dd-MM-yyyy HH.mm", Locale.US).format(new Date(t));
    }

    static String dateShort(long t) {
        return new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.US).format(new Date(t));
    }

    static boolean alive(Activity a) {
        return a != null && !a.isFinishing() && !a.isDestroyed();
    }

    static String safeName(String s) {
        String r = s.replaceAll("[\\\\/:*?\"<>|\\n\\r\\t]", "_").trim();
        if (r.length() == 0) r = "TaiLieu";
        if (r.length() > 80) r = r.substring(0, 80);
        return r;
    }
}
