package vn.quettailieu.scanner;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Random;

import vn.quettailieu.scanner.core.Enhancer;

/**
 * Lưu tài liệu: mỗi tài liệu là một thư mục chứa doc.json + ảnh gốc/ảnh đã xử lý từng trang.
 *
 * Các màn hình mở tài liệu bằng {@link #open}: mọi màn hình dùng chung MỘT đối tượng Doc cho cùng một tài liệu,
 * nên thay đổi ở màn hình này không bị màn hình khác ghi đè bằng bản cũ. {@link #load} luôn đọc bản mới từ đĩa
 * (chỉ để liệt kê / dọn dẹp, không dùng để sửa).
 */
final class DocStore {
    private DocStore() {}

    private static final Object LOCK = new Object();
    private static final Random RND = new Random();
    /** Tài liệu đang được các màn hình sử dụng (tham chiếu yếu: tự mất khi không còn màn hình nào giữ). */
    private static final HashMap<String, WeakReference<Doc>> OPEN = new HashMap<>();

    static final class Page {
        String id;
        String src;          // ảnh gốc (giữ EXIF)
        int srcW, srcH;      // kích thước ảnh gốc sau khi xoay theo EXIF
        float[] quad;        // 4 góc theo toạ độ ảnh gốc (đã xoay)
        boolean auto = true; // tứ giác do máy tự tìm
        double focal = -1;   // tiêu cự (pixel) nếu biết
        int rot;             // xoay thêm 0/90/180/270
        int filter = Enhancer.MAGIC;
        String warp;         // ảnh đã nắn phẳng (chưa lọc, chưa xoay)
        String out;          // ảnh kết quả
        long ver;            // tăng mỗi lần ảnh kết quả thay đổi

        Page copy() {
            Page p = new Page();
            p.id = id; p.src = src; p.srcW = srcW; p.srcH = srcH;
            p.quad = quad == null ? null : quad.clone();
            p.auto = auto; p.focal = focal; p.rot = rot; p.filter = filter;
            p.warp = warp; p.out = out; p.ver = ver;
            return p;
        }
    }

    static final class Doc {
        String id;
        File dir;
        String title;
        long created, modified;
        boolean draft;
        /** Đã bị xoá: mọi lần lưu sau đó bị bỏ qua (không tạo lại thư mục). */
        volatile boolean deleted;
        final ArrayList<Page> pages = new ArrayList<>();

        File file(String name) {
            return name == null ? null : new File(dir, name);
        }

        Page page(String pid) {
            for (Page p : pages) if (p.id.equals(pid)) return p;
            return null;
        }

        int indexOf(String pid) {
            for (int i = 0; i < pages.size(); i++) if (pages.get(i).id.equals(pid)) return i;
            return -1;
        }

        boolean outReady(Page p) {
            return p.out != null && file(p.out).exists();
        }
    }

    static File root(Context c) {
        File r = new File(c.getFilesDir(), "docs");
        if (!r.exists()) r.mkdirs();
        return r;
    }

    static String newId(String prefix) {
        return prefix + Long.toString(System.currentTimeMillis(), 36) + Integer.toString(RND.nextInt(36 * 36 * 36), 36);
    }

    static Doc create(Context c, boolean draft) {
        Doc d = new Doc();
        d.id = newId("d");
        d.dir = new File(root(c), d.id);
        d.dir.mkdirs();
        d.created = d.modified = System.currentTimeMillis();
        d.title = Ui.newTitle(d.created);
        d.draft = draft;
        save(d);
        synchronized (LOCK) {
            OPEN.put(d.id, new WeakReference<>(d));
        }
        return d;
    }

    /**
     * Mở tài liệu để dùng trong màn hình (gọi trên luồng chính). Nếu tài liệu đang được màn hình khác dùng
     * thì trả về chính đối tượng đó.
     */
    static Doc open(Context c, String id) {
        if (id == null) return null;
        synchronized (LOCK) {
            WeakReference<Doc> ref = OPEN.get(id);
            Doc d = ref == null ? null : ref.get();
            if (d != null && !d.deleted && d.dir.exists()) return d;
            d = load(c, id);
            if (d != null) OPEN.put(id, new WeakReference<>(d));
            else OPEN.remove(id);
            return d;
        }
    }

    /** Tài liệu có đang được màn hình nào đó giữ không. */
    static boolean isOpen(String id) {
        synchronized (LOCK) {
            WeakReference<Doc> ref = OPEN.get(id);
            Doc d = ref == null ? null : ref.get();
            return d != null && !d.deleted;
        }
    }

    static Doc load(Context c, String id) {
        if (id == null) return null;
        File dir = new File(root(c), id);
        File f = new File(dir, "doc.json");
        if (!f.exists()) {
            // lần ghi trước bị ngắt giữa lúc đổi tên file tạm -> dùng file tạm
            File tmp = new File(dir, "doc.json.tmp");
            if (!tmp.exists() || tmp.length() == 0) return null;
            f = tmp;
        }
        try {
            JSONObject o = new JSONObject(readText(f));
            Doc d = new Doc();
            d.id = id;
            d.dir = dir;
            d.title = o.optString("title", "Tài liệu");
            d.created = o.optLong("created", f.lastModified());
            d.modified = o.optLong("modified", d.created);
            d.draft = o.optBoolean("draft", false);
            JSONArray arr = o.optJSONArray("pages");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject po = arr.getJSONObject(i);
                    Page p = new Page();
                    p.id = po.getString("id");
                    p.src = po.optString("src", null);
                    p.srcW = po.optInt("w");
                    p.srcH = po.optInt("h");
                    JSONArray q = po.optJSONArray("quad");
                    if (q != null && q.length() == 8) {
                        p.quad = new float[8];
                        for (int k = 0; k < 8; k++) p.quad[k] = (float) q.getDouble(k);
                    }
                    p.auto = po.optBoolean("auto", true);
                    p.focal = po.optDouble("focal", -1);
                    p.rot = po.optInt("rot", 0);
                    p.filter = po.optInt("filter", Enhancer.MAGIC);
                    p.warp = po.has("warp") ? po.optString("warp", null) : null;
                    p.out = po.has("out") ? po.optString("out", null) : null;
                    p.ver = po.optLong("ver", 0);
                    d.pages.add(p);
                }
            }
            return d;
        } catch (Exception e) {
            return null;
        }
    }

    static void save(Doc d) {
        synchronized (LOCK) {
            // tài liệu đã xoá (hoặc thư mục không còn) thì không ghi lại, tránh "hồi sinh" tài liệu rỗng
            if (d == null || d.deleted || d.dir == null || !d.dir.exists()) return;
            try {
                JSONObject o = new JSONObject();
                o.put("title", d.title);
                o.put("created", d.created);
                o.put("modified", d.modified);
                o.put("draft", d.draft);
                JSONArray arr = new JSONArray();
                for (Page p : d.pages) {
                    JSONObject po = new JSONObject();
                    po.put("id", p.id);
                    po.put("src", p.src);
                    po.put("w", p.srcW);
                    po.put("h", p.srcH);
                    if (p.quad != null) {
                        JSONArray q = new JSONArray();
                        for (float v : p.quad) q.put(Float.isNaN(v) || Float.isInfinite(v) ? 0.0 : (double) v);
                        po.put("quad", q);
                    }
                    po.put("auto", p.auto);
                    po.put("focal", Double.isNaN(p.focal) || Double.isInfinite(p.focal) ? -1 : p.focal);
                    po.put("rot", p.rot);
                    po.put("filter", p.filter);
                    if (p.warp != null) po.put("warp", p.warp);
                    if (p.out != null) po.put("out", p.out);
                    po.put("ver", p.ver);
                    arr.put(po);
                }
                o.put("pages", arr);
                File tmp = new File(d.dir, "doc.json.tmp");
                FileOutputStream fo = new FileOutputStream(tmp);
                fo.write(o.toString().getBytes("UTF-8"));
                fo.getFD().sync();
                fo.close();
                File f = new File(d.dir, "doc.json");
                if (!tmp.renameTo(f)) {
                    f.delete();
                    tmp.renameTo(f);
                }
            } catch (Exception ignored) {
            }
        }
    }

    /** Danh sách tài liệu đã lưu (không gồm bản nháp), mới nhất trước. */
    static List<Doc> list(Context c) {
        List<Doc> out = new ArrayList<>();
        File[] dirs = root(c).listFiles();
        if (dirs == null) return out;
        for (File dir : dirs) {
            if (!dir.isDirectory()) continue;
            Doc d = load(c, dir.getName());
            if (d != null && !d.draft && !d.pages.isEmpty()) out.add(d);
        }
        Collections.sort(out, new Comparator<Doc>() {
            @Override
            public int compare(Doc a, Doc b) {
                return Long.compare(b.modified, a.modified);
            }
        });
        return out;
    }

    static void delete(Doc d) {
        if (d == null) return;
        synchronized (LOCK) {
            d.deleted = true;
            WeakReference<Doc> ref = OPEN.remove(d.id);
            Doc shared = ref == null ? null : ref.get();
            if (shared != null) shared.deleted = true;
        }
        deleteRec(d.dir);
    }

    /**
     * Dọn thư mục rác: bản nháp/tài liệu KHÔNG có trang nào và đã cũ, thư mục hỏng không có doc.json.
     * Bản nháp còn trang không bị xoá ở đây — chúng được {@link #recoverDrafts} đưa trở lại danh sách.
     */
    static void cleanDrafts(Context c, long olderThanMs) {
        File[] dirs = root(c).listFiles();
        if (dirs == null) return;
        long now = System.currentTimeMillis();
        for (File dir : dirs) {
            if (!dir.isDirectory()) continue;
            if (isOpen(dir.getName())) continue;
            Doc d = load(c, dir.getName());
            if (d == null) {
                File j = new File(dir, "doc.json");
                if (!j.exists() && now - dir.lastModified() > olderThanMs) deleteRec(dir);
                continue;
            }
            if (d.pages.isEmpty() && now - Math.max(d.modified, dir.lastModified()) > (d.draft ? olderThanMs : 3600L * 1000)) deleteRec(dir);
        }
    }

    /**
     * Tìm các bản nháp còn trang nhưng không màn hình nào đang dùng (ứng dụng bị đóng giữa lúc quét)
     * — gọi trên luồng nền; kết quả đưa cho {@link #recoverDrafts}.
     */
    static List<String> orphanDrafts(Context c) {
        List<String> out = new ArrayList<>();
        File[] dirs = root(c).listFiles();
        if (dirs == null) return out;
        for (File dir : dirs) {
            if (!dir.isDirectory() || isOpen(dir.getName())) continue;
            Doc d = load(c, dir.getName());
            if (d != null && d.draft && !d.pages.isEmpty()) out.add(d.id);
        }
        return out;
    }

    /** Đưa các bản nháp bị bỏ dở trở lại danh sách tài liệu (gọi trên luồng chính). Trả về số tài liệu đã khôi phục. */
    static int recoverDrafts(Context c, List<String> ids) {
        int n = 0;
        for (String id : ids) {
            if (isOpen(id)) continue;
            Doc d = open(c, id);
            if (d == null || !d.draft || d.pages.isEmpty()) continue;
            // bỏ trang mất ảnh gốc (không thể xử lý lại)
            for (int i = d.pages.size() - 1; i >= 0; i--) {
                Page p = d.pages.get(i);
                File src = p.src == null ? null : d.file(p.src);
                if (src == null || !src.exists() || src.length() == 0) d.pages.remove(i);
            }
            if (d.pages.isEmpty()) {
                delete(d);
                continue;
            }
            d.draft = false;
            save(d);
            n++;
        }
        return n;
    }

    /** Thêm trang mới từ file ảnh tạm (file được chuyển vào thư mục tài liệu). */
    static Page addPage(Doc d, File srcFile, int[] orientedSize, float[] quad, boolean auto, double focal) throws IOException {
        Page p = new Page();
        p.id = newId("p");
        p.src = p.id + "_src.jpg";
        File dst = new File(d.dir, p.src);
        moveFile(srcFile, dst);
        p.srcW = orientedSize[0];
        p.srcH = orientedSize[1];
        p.quad = quad;
        p.auto = auto;
        p.focal = focal;
        d.pages.add(p);
        d.modified = System.currentTimeMillis();
        return p;
    }

    /** Thay ảnh gốc của trang (chụp lại). */
    static void replaceSource(Doc d, Page p, File srcFile, int[] orientedSize, float[] quad, boolean auto, double focal) throws IOException {
        // chép ảnh mới xong mới xoá ảnh cũ: nếu chép lỗi (hết dung lượng) thì trang cũ vẫn còn nguyên
        String newSrc = newId("s") + "_src.jpg";
        moveFile(srcFile, new File(d.dir, newSrc));
        deletePageFiles(d, p);
        p.src = newSrc;
        p.srcW = orientedSize[0];
        p.srcH = orientedSize[1];
        p.quad = quad;
        p.auto = auto;
        p.focal = focal;
        p.rot = 0;
        p.warp = null;
        p.out = null;
        p.ver++;
        d.modified = System.currentTimeMillis();
    }

    static void removePage(Doc d, Page p) {
        deletePageFiles(d, p);
        d.pages.remove(p);
        d.modified = System.currentTimeMillis();
    }

    private static void deletePageFiles(Doc d, Page p) {
        if (p.src != null) d.file(p.src).delete();
        if (p.warp != null) d.file(p.warp).delete();
        if (p.out != null) d.file(p.out).delete();
    }

    static void moveFile(File from, File to) throws IOException {
        if (from.renameTo(to)) return;
        copy(new FileInputStream(from), to);
        from.delete();
    }

    static void copy(InputStream in, File to) throws IOException {
        FileOutputStream fo = new FileOutputStream(to);
        try {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) fo.write(buf, 0, n);
        } finally {
            try { in.close(); } catch (IOException ignored) { }
            fo.close();
        }
    }

    static byte[] readBytes(File f) throws IOException {
        FileInputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream((int) Math.max(1024, f.length()));
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        } finally {
            in.close();
        }
    }

    static String readText(File f) throws IOException {
        return new String(readBytes(f), "UTF-8");
    }

    static void deleteRec(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRec(k);
        f.delete();
    }
}
