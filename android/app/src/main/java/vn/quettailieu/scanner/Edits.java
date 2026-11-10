package vn.quettailieu.scanner;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import vn.quettailieu.scanner.core.Retouch;

/**
 * Các chỉnh sửa trên trang, lưu dạng JSON chuẩn tắc (chỉ số nguyên + chuỗi, khoá theo thứ tự cố định)
 * để cùng một nội dung luôn cho cùng một chuỗi — chuỗi này tham gia vào tên file ảnh kết quả.
 *
 * - Xoá thông minh (Retouch.Op): áp lên ảnh đã nắn phẳng TRƯỚC khi lọc.
 * - Đánh dấu (Mark): chữ ký, văn bản, nét vẽ, khảm, hình mờ — vẽ SAU khi lọc, trước khi xoay.
 * Toạ độ chuẩn hoá theo ảnh đã nắn phẳng (chưa xoay), nhân 100000 khi lưu; độ dài tính theo bề ngang ảnh.
 */
final class Edits {
    private Edits() {}

    static final int SCALE = 100000;

    static final class Mark {
        static final int BRUSH = 0, MOSAIC = 1, TEXT = 2, SIGN = 3, WM = 4;
        int type;
        int color = 0xFF1A1A1A;
        /** Bề rộng nét (BRUSH/MOSAIC) / bề ngang ảnh. */
        float width;
        float[] pts;
        String text;
        /** Tên file chữ ký trong thư mục tài liệu. */
        String file;
        /** Tâm (TEXT/SIGN). */
        float x = 0.5f, y = 0.5f;
        /** TEXT/WM: chiều cao dòng chữ / bề ngang ảnh; SIGN: bề ngang chữ ký / bề ngang ảnh. */
        float size;
        /** Góc quay (độ, theo chiều kim đồng hồ) trong hệ ảnh chưa xoay. */
        int angle;

        Mark copy() {
            Mark m = new Mark();
            m.type = type; m.color = color; m.width = width;
            m.pts = pts == null ? null : pts.clone();
            m.text = text; m.file = file; m.x = x; m.y = y; m.size = size; m.angle = angle;
            return m;
        }
    }

    private static int q(float v) {
        if (Float.isNaN(v) || Float.isInfinite(v)) return 0;
        // giới hạn để điểm nằm xa ngoài trang (sau khi cắt lại) không làm tràn số
        if (v > 20f) v = 20f;
        if (v < -20f) v = -20f;
        return Math.round(v * SCALE);
    }

    private static JSONArray pts(float[] p) {
        JSONArray a = new JSONArray();
        if (p != null) for (float v : p) a.put(q(v));
        return a;
    }

    private static float[] pts(JSONArray a) {
        if (a == null) return new float[0];
        float[] p = new float[a.length()];
        for (int i = 0; i < p.length; i++) p[i] = a.optInt(i, 0) / (float) SCALE;
        return p;
    }

    // ------------------------------------------------------------------ xoá thông minh

    /** null nếu danh sách rỗng. */
    static String eraseToJson(List<Retouch.Op> ops) {
        if (ops == null || ops.isEmpty()) return null;
        try {
            JSONArray arr = new JSONArray();
            for (Retouch.Op op : ops) {
                if (op == null) continue;
                JSONObject o = new JSONObject();
                o.put("k", op.kind);
                o.put("w", q(op.width));
                o.put("p", pts(op.pts));
                arr.put(o);
            }
            return arr.length() == 0 ? null : arr.toString();
        } catch (Exception e) {
            return null;
        }
    }

    static ArrayList<Retouch.Op> eraseFromJson(String json) {
        ArrayList<Retouch.Op> out = new ArrayList<>();
        if (json == null || json.length() == 0) return out;
        try {
            return eraseFromJson(new JSONArray(json));
        } catch (Exception e) {
            return out;
        }
    }

    static ArrayList<Retouch.Op> eraseFromJson(JSONArray arr) {
        ArrayList<Retouch.Op> out = new ArrayList<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            int k = o.optInt("k", -1);
            if (k < Retouch.PEN || k > Retouch.HAND) continue;
            out.add(new Retouch.Op(k, o.optInt("w", 0) / (float) SCALE, pts(o.optJSONArray("p"))));
        }
        return out;
    }

    // ------------------------------------------------------------------ đánh dấu

    static String marksToJson(List<Mark> marks) {
        if (marks == null || marks.isEmpty()) return null;
        try {
            JSONArray arr = new JSONArray();
            for (Mark m : marks) {
                if (m == null) continue;
                JSONObject o = new JSONObject();
                o.put("t", m.type);
                switch (m.type) {
                    case Mark.BRUSH:
                        o.put("c", m.color);
                        o.put("w", q(m.width));
                        o.put("p", pts(m.pts));
                        break;
                    case Mark.MOSAIC:
                        o.put("w", q(m.width));
                        o.put("p", pts(m.pts));
                        break;
                    case Mark.TEXT:
                        o.put("s", m.text == null ? "" : m.text);
                        o.put("c", m.color);
                        o.put("x", q(m.x));
                        o.put("y", q(m.y));
                        o.put("h", q(m.size));
                        o.put("a", m.angle);
                        break;
                    case Mark.SIGN:
                        o.put("f", m.file == null ? "" : m.file);
                        o.put("x", q(m.x));
                        o.put("y", q(m.y));
                        o.put("h", q(m.size));
                        o.put("a", m.angle);
                        break;
                    case Mark.WM:
                        o.put("s", m.text == null ? "" : m.text);
                        o.put("c", m.color);
                        o.put("h", q(m.size));
                        break;
                    default:
                        continue;
                }
                arr.put(o);
            }
            return arr.length() == 0 ? null : arr.toString();
        } catch (Exception e) {
            return null;
        }
    }

    static ArrayList<Mark> marksFromJson(String json) {
        if (json == null || json.length() == 0) return new ArrayList<>();
        try {
            return marksFromJson(new JSONArray(json));
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    static ArrayList<Mark> marksFromJson(JSONArray arr) {
        ArrayList<Mark> out = new ArrayList<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            Mark m = new Mark();
            m.type = o.optInt("t", -1);
            if (m.type < Mark.BRUSH || m.type > Mark.WM) continue;
            m.color = o.optInt("c", 0xFF1A1A1A);
            m.width = o.optInt("w", 0) / (float) SCALE;
            m.pts = o.has("p") ? pts(o.optJSONArray("p")) : null;
            m.text = o.has("s") ? o.optString("s", "") : null;
            m.file = o.has("f") ? o.optString("f", "") : null;
            m.x = o.optInt("x", SCALE / 2) / (float) SCALE;
            m.y = o.optInt("y", SCALE / 2) / (float) SCALE;
            m.size = o.optInt("h", 0) / (float) SCALE;
            m.angle = o.optInt("a", 0);
            out.add(m);
        }
        return out;
    }

    /** Chuẩn hoá chuỗi JSON đọc từ đĩa (để chuỗi trong bộ nhớ luôn ở dạng chuẩn tắc). */
    static String canonErase(JSONArray arr) {
        return eraseToJson(eraseFromJson(arr));
    }

    static String canonMarks(JSONArray arr) {
        return marksToJson(marksFromJson(arr));
    }

    // ------------------------------------------------------------------ cắt lại

    /** Sau khi cắt lại trang: dời các chỉnh sửa theo vùng cắt mới để chúng vẫn nằm đúng chỗ trên giấy. */
    static void remap(DocStore.Page p, float[] oldQuad, float[] newQuad) {
        if (oldQuad == null || newQuad == null || (p.erase == null && p.marks == null)) return;
        boolean same = true;
        for (int i = 0; i < 8; i++) if (Math.abs(oldQuad[i] - newQuad[i]) > 0.25f) same = false;
        if (same) return;
        float k = Retouch.remapScale(oldQuad, newQuad);
        if (p.erase != null) {
            ArrayList<Retouch.Op> ops = eraseFromJson(p.erase);
            for (Retouch.Op op : ops) {
                op.pts = Retouch.remap(op.pts, oldQuad, newQuad);
                op.width *= k;
            }
            p.erase = eraseToJson(ops);
        }
        if (p.marks != null) {
            ArrayList<Mark> ms = marksFromJson(p.marks);
            for (Mark m : ms) {
                if (m.pts != null) m.pts = Retouch.remap(m.pts, oldQuad, newQuad);
                float[] c = Retouch.remap(new float[]{m.x, m.y}, oldQuad, newQuad);
                m.x = c[0];
                m.y = c[1];
                m.width *= k;
                if (m.type != Mark.WM) m.size *= k;
            }
            p.marks = marksToJson(ms);
        }
    }

    // ------------------------------------------------------------------ tiêu đề trang

    /** Tiêu đề trang của tài liệu: dòng chữ ở lề trên + số trang ở lề dưới. */
    static final class Header {
        String text = "";
        boolean pageNum;

        boolean isEmpty() {
            return (text == null || text.trim().length() == 0) && !pageNum;
        }

        /** Nội dung in trên trang thứ index (từ 0) trong tổng số count trang; null nếu không có gì. */
        String resolve(int index, int count) {
            if (isEmpty()) return null;
            String t = text == null ? "" : text.trim();
            return t + "\n" + (pageNum ? (index + 1) + "/" + count : "");
        }
    }
}
