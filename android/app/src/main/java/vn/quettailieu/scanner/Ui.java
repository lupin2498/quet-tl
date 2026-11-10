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
import android.text.InputFilter;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Normalizer;
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

    /**
     * Hàng công cụ chia đều bề ngang màn hình; nút nào có nhãn dài hơn phần được chia thì nở theo nhãn
     * và cả hàng cuộn ngang (không cắt chữ trên màn hình hẹp hay khi phóng to cỡ chữ).
     */
    static HorizontalScrollView toolRow(Context c, LinearLayout[] buttons, int bg) {
        HorizontalScrollView hs = new HorizontalScrollView(c);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setFillViewport(true);
        hs.setBackgroundColor(bg);
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        int minW = c.getResources().getDisplayMetrics().widthPixels / Math.max(1, buttons.length);
        for (LinearLayout b : buttons) {
            b.setPadding(dp(6), dp(6), dp(6), dp(6));
            b.setMinimumWidth(minW);
            row.addView(b, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        }
        hs.addView(row, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return hs;
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

    /** Độ dài tối đa của tên tài liệu. */
    static final int MAX_TITLE = 120;

    /** Có thể hiện hộp thoại trên màn hình này không (Activity đã đóng thì hiện sẽ làm ứng dụng dừng). */
    private static boolean canShow(Context c) {
        return !(c instanceof Activity) || alive((Activity) c);
    }

    private static long lastDialogAt;

    private static void show(AlertDialog d) {
        // chạm đúp một nút mở hộp thoại -> chỉ hiện một hộp thoại
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastDialogAt < 350) return;
        try {
            d.show();
            lastDialogAt = now;
        } catch (RuntimeException ignored) {
            // cửa sổ vừa bị huỷ (BadTokenException) -> bỏ qua
        }
    }

    static void confirm(Context c, String title, String msg, String ok, final Runnable onOk) {
        if (!canShow(c)) return;
        AlertDialog.Builder b = dialog(c).setTitle(title).setNegativeButton("Hủy", null)
                .setPositiveButton(ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        onOk.run();
                    }
                });
        if (msg != null) b.setMessage(msg);
        show(b.create());
    }

    /** Ẩn bàn phím đang phục vụ ô nhập v (gọi lúc ô còn gắn vào cửa sổ, vd. trong nút của hộp thoại). */
    static void hideKeyboard(View v) {
        if (v == null) return;
        try {
            InputMethodManager imm = (InputMethodManager) v.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null && v.getWindowToken() != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
        } catch (RuntimeException ignored) {
        }
    }

    /** Hộp thoại nhập chữ (đổi tên). */
    static void prompt(Context c, String title, String initial, final Cb<String> cb) {
        if (!canShow(c)) return;
        final EditText et = new EditText(c);
        et.setFilters(new InputFilter[]{new InputFilter.LengthFilter(MAX_TITLE)});
        et.setText(initial);
        et.setSingleLine(true);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        et.setImeOptions(EditorInfo.IME_ACTION_DONE);
        et.setSelectAllOnFocus(true);
        FrameLayout box = new FrameLayout(c);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(et);
        final AlertDialog d = dialog(c).setTitle(title).setView(box)
                .setNegativeButton("Hủy", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        hideKeyboard(et);
                    }
                })
                .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        // Android cũ để bàn phím ở lại sau khi hộp thoại đóng -> che thanh công cụ
                        hideKeyboard(et);
                        String v = et.getText().toString().trim();
                        if (v.length() > 0) cb.done(v);
                    }
                }).create();
        if (d.getWindow() != null) d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        show(d);
        et.requestFocus();
    }

    /** Danh sách lựa chọn dạng hộp thoại (thay cho PopupMenu). */
    static void menu(Context c, String title, String[] items, final Cb<Integer> cb) {
        if (!canShow(c)) return;
        AlertDialog.Builder b = dialog(c).setItems(items, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface d, int which) {
                cb.done(which);
            }
        });
        if (title != null) b.setTitle(title);
        show(b.create());
    }

    /** Hiện hộp thoại dựng sẵn một cách an toàn (bỏ qua nếu màn hình đã đóng). */
    static void showDialog(Context c, AlertDialog.Builder b) {
        if (!canShow(c)) return;
        show(b.create());
    }

    /** Nội dung lỗi để hiện cho người dùng (không bao giờ là "null"). */
    static String msg(Throwable t) {
        if (t == null) return "không rõ nguyên nhân";
        if (t instanceof OutOfMemoryError) return "thiếu bộ nhớ";
        String m = t.getMessage();
        if (m == null || m.trim().length() == 0) return t.getClass().getSimpleName();
        return m;
    }

    /**
     * Chuẩn hoá chuỗi để tìm kiếm: bỏ dấu tiếng Việt, đ -> d, chữ thường
     * ("bien ban" tìm được "Biên bản").
     */
    static String fold(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFD);
        StringBuilder sb = new StringBuilder(n.length());
        for (int i = 0; i < n.length(); i++) {
            char ch = n.charAt(i);
            int type = Character.getType(ch);
            if (type == Character.NON_SPACING_MARK || type == Character.ENCLOSING_MARK || type == Character.COMBINING_SPACING_MARK) continue;
            if (ch == 'đ' || ch == 'Đ') ch = 'd';
            sb.append(Character.toLowerCase(ch));
        }
        return sb.toString();
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

    /**
     * Tên file an toàn từ tên tài liệu: bỏ ký tự cấm và ký tự điều khiển, tối đa 80 ký tự / 180 byte UTF-8
     * (giới hạn tên file 255 byte, còn chừa chỗ cho hậu tố "_12.jpg"), không cắt giữa cặp ký tự thay thế.
     */
    static String safeName(String s) {
        if (s == null) s = "";
        StringBuilder sb = new StringBuilder();
        int bytes = 0, chars = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (cp < 0x20 || cp == 0x7F || "\\/:*?\"<>|".indexOf(cp) >= 0) cp = '_';
            if (cp >= 0xD800 && cp <= 0xDFFF) cp = '_'; // nửa cặp thay thế lẻ
            int b = cp < 0x80 ? 1 : cp < 0x800 ? 2 : cp < 0x10000 ? 3 : 4;
            if (chars + 1 > 80 || bytes + b > 180) break;
            sb.appendCodePoint(cp);
            bytes += b;
            chars++;
        }
        String r = sb.toString().trim();
        while (r.startsWith(".")) r = r.substring(1).trim();
        if (r.length() == 0) r = "TaiLieu";
        return r;
    }
}
