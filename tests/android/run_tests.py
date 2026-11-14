#!/usr/bin/env python3
"""Kiểm thử tự động ứng dụng Quét Tài Liệu trên máy ảo Android (adb + uiautomator).

Cách chạy:  python3 tests/android/run_tests.py <apk> <thư-mục-kết-quả>
Kết quả: screens/*.png, summary.md, summary.json, logcat.txt, crash.txt, monkey.txt
"""
import json, os, re, subprocess, sys, time, traceback
import xml.etree.ElementTree as ET

PKG = "vn.quettailieu.scanner"
APK = sys.argv[1] if len(sys.argv) > 1 else "app-debug.apk"
OUT = sys.argv[2] if len(sys.argv) > 2 else "test-out"
HERE = os.path.dirname(os.path.abspath(__file__))
IMAGES = os.path.join(HERE, "..", "images")
os.makedirs(os.path.join(OUT, "screens"), exist_ok=True)
os.makedirs(os.path.join(OUT, "ui"), exist_ok=True)

results = []
notes = []
shot_no = [0]
# Chỉ chạy các bước có tên khớp biểu thức này (để lặp nhanh khi sửa một tính năng); "Cài đặt" và "Mở ứng dụng" luôn chạy.
ONLY = os.environ.get("TEST_ONLY", "").strip()
# các bước có tên khớp TEST_ONLY (không bước nào khớp -> báo lỗi, không để kết quả "đạt" mà chẳng kiểm thử gì)
only_hits = []


def log(*a):
    msg = " ".join(str(x) for x in a)
    print("[test]", msg, flush=True)
    try:
        with open(os.path.join(OUT, "test.log"), "a", encoding="utf-8") as f:
            f.write(time.strftime("%H:%M:%S ") + msg + "\n")
    except Exception:
        pass


def adb(*args, timeout=60, inp=None):
    try:
        r = subprocess.run(["adb", *args], input=inp, capture_output=True, timeout=timeout)
        return r.stdout.decode("utf-8", "replace") + r.stderr.decode("utf-8", "replace")
    except subprocess.TimeoutExpired as e:
        part = (e.stdout or b"").decode("utf-8", "replace") if isinstance(e.stdout, bytes) else (e.stdout or "")
        return part + "\nTIMEOUT"


def sh(cmd, timeout=60):
    return adb("shell", cmd, timeout=timeout)


def shot(name):
    shot_no[0] += 1
    path = os.path.join(OUT, "screens", "%02d_%s.png" % (shot_no[0], name))
    try:
        with open(path, "wb") as f:
            subprocess.run(["adb", "exec-out", "screencap", "-p"], stdout=f, timeout=30)
    except Exception as e:
        log("screenshot failed", e)
    return path


def dump(save_as=None):
    for _ in range(4):
        out = sh("uiautomator dump /sdcard/ui.xml", timeout=45)
        if "dumped" in out.lower():
            xml = sh("cat /sdcard/ui.xml", timeout=20)
            start = xml.find("<?xml")
            if start < 0:
                start = xml.find("<hierarchy")
            if start >= 0:
                xml = xml[start:]
                end = xml.rfind("</hierarchy>")
                if end > 0:
                    xml = xml[: end + len("</hierarchy>")]
                try:
                    root = ET.fromstring(xml)
                    if save_as:
                        open(os.path.join(OUT, "ui", save_as + ".xml"), "w").write(xml)
                    return root
                except ET.ParseError as e:
                    log("parse error", e)
        time.sleep(1.0)
    return None


def nodes(root):
    if root is None:
        return []
    return list(root.iter("node"))


def bounds(n):
    m = re.findall(r"\d+", n.get("bounds", ""))
    if len(m) != 4:
        return None
    x1, y1, x2, y2 = map(int, m)
    return (x1 + x2) // 2, (y1 + y2) // 2


def rect(n):
    m = re.findall(r"\d+", n.get("bounds", "")) if n is not None else []
    if len(m) != 4:
        return None
    return tuple(map(int, m))


def parent_map(root):
    return {c: p for p in root.iter() for c in p}


def _match(want, got, contains):
    # nút hộp thoại Material hiển thị chữ in hoa (vd. "XOÁ") -> so khớp không phân biệt hoa/thường
    w, g = want.casefold(), got.casefold()
    return (contains and w in g) or w == g


def find(text=None, desc=None, contains=False, root=None):
    root = root if root is not None else dump()
    for n in nodes(root):
        t, d = n.get("text", ""), n.get("content-desc", "")
        if text is not None and _match(text, t, contains):
            return n
        if desc is not None and _match(desc, d, contains):
            return n
    return None


def dialog_open(root=None):
    root = root if root is not None else dump()
    for n in nodes(root):
        rid = n.get("resource-id", "")
        if rid in ("android:id/button1", "android:id/button2", "android:id/select_dialog_listview", "android:id/alertTitle"):
            return True
    return False


def tap_xy(x, y):
    sh("input tap %d %d" % (x, y))


def fast_taps(points, gap_ms=40):
    """Chạm liên tiếp nhiều điểm với khoảng cách thời gian chính xác (kịch bản monkey) — dùng để thử chạm đúp."""
    lines = ["type= user", "count= 1", "speed= 1.0", "start data >>"]
    for i, (x, y) in enumerate(points):
        if i:
            lines.append("UserWait(%d)" % gap_ms)
        lines.append("Tap(%d, %d)" % (x, y))
    lines.append("UserWait(300)")
    adb("shell", "cat > /sdcard/qt_taps.txt", inp=("\n".join(lines) + "\n").encode(), timeout=20)
    out = sh("monkey -f /sdcard/qt_taps.txt 1", timeout=60)
    if "Events injected" not in out:
        log("monkey script:", out.strip()[-300:])
    time.sleep(0.5)


def find_id(suffix, root=None):
    root = root if root is not None else dump()
    for n in nodes(root):
        if n.get("resource-id", "").endswith(suffix):
            return n
    return None


def run_as(cmd, timeout=60):
    return sh("run-as %s sh -c '%s'" % (PKG, cmd), timeout=timeout)


def pull_file(rel):
    """Đọc một file trong thư mục dữ liệu của ứng dụng (bản debug) về máy chạy kiểm thử."""
    # một chuỗi lệnh duy nhất để shell trên thiết bị xử lý dấu nháy (tên file có khoảng trắng)
    r = subprocess.run(["adb", "exec-out", "run-as %s cat '%s'" % (PKG, rel)], capture_output=True, timeout=120)
    return r.stdout


def doc_ids():
    out = run_as("ls files/docs")
    return [x.strip() for x in out.split() if x.strip().startswith("d")]


def read_doc(doc_id):
    raw = pull_file("files/docs/%s/doc.json" % doc_id)
    try:
        d = json.loads(raw.decode("utf-8"))
        d["id"] = doc_id
        return d
    except Exception:
        return None


def all_docs():
    out = []
    for i in doc_ids():
        d = read_doc(i)
        if d is not None:
            out.append(d)
    return out


def newest_doc(saved_only=True):
    ds = [d for d in all_docs() if d.get("pages") and (not saved_only or not d.get("draft"))]
    ds.sort(key=lambda d: d.get("modified", 0))
    return ds[-1] if ds else None


def app_file_size(rel):
    """Kích thước một file trong thư mục dữ liệu của ứng dụng (-1 nếu không có). Tên file không chứa khoảng trắng."""
    out = sh("run-as %s wc -c %s" % (PKG, rel), timeout=30)
    m = re.match(r"\s*(\d+)\s", out)
    return int(m.group(1)) if m else -1


def write_app_file(rel, data):
    """Ghi một file vào thư mục dữ liệu của ứng dụng (bản debug) rồi kiểm tra lại kích thước.

    adbd đời cũ (API 24) huỷ lệnh nếu kết nối đóng trước khi lệnh trên thiết bị kịp chạy -> file nhỏ không được ghi.
    Vì vậy giữ kết nối thêm một lúc sau khi gửi hết dữ liệu và thử lại nếu kích thước chưa khớp.
    """
    last = -1
    for attempt in range(5):
        p = subprocess.Popen(["adb", "exec-in", "run-as", PKG, "sh", "-c", "cat > %s" % rel],
                             stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        try:
            if data:
                p.stdin.write(data)
            p.stdin.flush()
            time.sleep(0.5 + 0.5 * attempt)
            p.communicate(timeout=120)
        except Exception as e:
            log("write_app_file", rel, e)
            try:
                p.kill()
            except Exception:
                pass
        last = app_file_size(rel)
        if last == len(data):
            return
    raise AssertionError("Không ghi được %s vào thiết bị (%d/%d byte)" % (rel, last, len(data)))


def write_doc(d):
    data = dict(d)
    doc_id = data.pop("id")
    raw = json.dumps(data, ensure_ascii=False).encode("utf-8")
    write_app_file("files/docs/%s/doc.json" % doc_id, raw)
    back = read_doc(doc_id)
    if back is None or back.get("title") != d.get("title"):
        raise AssertionError("Ghi doc.json không thành công (đọc lại: %s)" % (back and back.get("title")))


def activity_instances(name):
    """Số bản thể của một Activity trong ngăn xếp (phát hiện mở trùng màn hình)."""
    out = sh("dumpsys activity activities", timeout=30)
    recs = set(re.findall(r"ActivityRecord\{(\w+) u\d+ " + re.escape(PKG) + r"/\.(\w+)", out))
    return sum(1 for r in recs if r[1] == name)


def app_pid():
    out = sh("pidof %s" % PKG).strip()
    if out and out.split()[0].isdigit():
        return out.split()[0]
    out = sh("ps -A 2>/dev/null | grep %s; ps 2>/dev/null | grep %s" % (PKG, PKG))
    for line in out.splitlines():
        p = line.split()
        if len(p) > 1 and p[-1] == PKG and p[1].isdigit():
            return p[1]
    return ""


def start_app(launcher=False):
    if launcher:
        return sh("am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n %s/.MainActivity" % PKG, timeout=60)
    return sh("am start -W -n %s/.MainActivity" % PKG, timeout=60)


def validate_pdf(data, pages):
    """Kiểm tra file PDF xuất ra: đọc được bằng trình phân tích thật, đủ số trang, mỗi trang có ảnh JPEG."""
    if not data.startswith(b"%PDF-1.4") or b"%%EOF" not in data[-32:]:
        raise AssertionError("PDF không hợp lệ (đầu/cuối file)")
    try:
        import io
        import pypdf
    except ImportError:
        n = len(re.findall(rb"/Type /Page[^s]", data))
        if n != pages:
            raise AssertionError("PDF có %d trang, cần %d" % (n, pages))
        notes.append("Thiếu pypdf: chỉ kiểm tra cấu trúc PDF cơ bản")
        return
    r = pypdf.PdfReader(io.BytesIO(data), strict=True)
    if len(r.pages) != pages:
        raise AssertionError("PDF có %d trang, cần %d" % (len(r.pages), pages))
    for i, pg in enumerate(r.pages):
        box = pg.mediabox
        if float(box.width) < 3 or float(box.height) < 3:
            raise AssertionError("PDF trang %d: khổ trang không hợp lệ" % (i + 1))
        imgs = list(pg.images)
        if len(imgs) != 1 or len(imgs[0].data) < 100:
            raise AssertionError("PDF trang %d không có ảnh" % (i + 1))
    try:
        from PIL import Image
        im = Image.open(io.BytesIO(list(r.pages[0].images)[0].data))
        im.load()
        if im.size[0] < 16 or im.size[1] < 16:
            raise AssertionError("ảnh trong PDF quá nhỏ: %s" % (im.size,))
    except ImportError:
        pass


WORD_NS = "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}"


def strip_accents(s):
    """Bỏ dấu tiếng Việt + chữ thường (so nội dung nhận dạng được mà không phụ thuộc dấu thanh đọc sai)."""
    import unicodedata
    s = unicodedata.normalize("NFD", s.replace("đ", "d").replace("Đ", "D"))
    return "".join(c for c in s if unicodedata.category(c) != "Mn").lower()


def validate_docx(data, pages):
    """Kiểm tra file Word xuất ra: zip nguyên vẹn, đủ các phần, mọi phần XML đọc được, mỗi trang quét là một phần (sectPr),
    hình nhúng mở được. Trả về (văn bản từng đoạn, số bảng, số hàng của bảng lớn nhất, số cột của bảng lớn nhất, số hình)."""
    import zipfile
    import xml.etree.ElementTree as ET
    if not data.startswith(b"PK"):
        raise AssertionError("file Word không phải zip (%d byte)" % len(data))
    z = zipfile.ZipFile(io.BytesIO(data))
    bad = z.testzip()
    if bad is not None:
        raise AssertionError("file Word hỏng ở phần %s" % bad)
    names = set(z.namelist())
    for need in ("[Content_Types].xml", "_rels/.rels", "word/document.xml", "word/styles.xml", "word/_rels/document.xml.rels"):
        if need not in names:
            raise AssertionError("file Word thiếu phần %s" % need)
    for n in names:
        if n.endswith(".xml") or n.endswith(".rels"):
            ET.fromstring(z.read(n))
    root = ET.fromstring(z.read("word/document.xml"))
    sects = list(root.iter(WORD_NS + "sectPr"))
    if len(sects) != pages:
        raise AssertionError("file Word có %d trang (phần), cần %d" % (len(sects), pages))
    for sp in sects:
        sz = sp.find(WORD_NS + "pgSz")
        if sz is None or int(sz.get(WORD_NS + "w")) < 1440 or int(sz.get(WORD_NS + "h")) < 1440:
            raise AssertionError("file Word: khổ trang không hợp lệ")
    lines = []
    for para in root.iter(WORD_NS + "p"):
        t = "".join(x.text or "" for x in para.iter(WORD_NS + "t")).strip()
        if t:
            lines.append(t)
    tables = list(root.iter(WORD_NS + "tbl"))
    rows = cols = 0
    for t in tables:
        trs = t.findall(WORD_NS + "tr")
        if len(trs) > rows:
            rows = len(trs)
            grid = t.find(WORD_NS + "tblGrid")
            cols = len(grid.findall(WORD_NS + "gridCol")) if grid is not None else 0
    media = sorted(n for n in names if n.startswith("word/media/"))
    try:
        from PIL import Image
        for m in media:
            im = Image.open(io.BytesIO(z.read(m)))
            im.load()
    except ImportError:
        pass
    return lines, len(tables), rows, cols, len(media)


def wait_share_file(ext, timeout=240):
    deadline = time.time() + timeout
    while time.time() < deadline:
        time.sleep(1.5)
        name = newest_share_file(ext)
        if name:
            time.sleep(2.0)
            return name
    return None


def close_chooser():
    back()
    time.sleep(1)
    if top_activity() != "DocumentActivity":
        back()
    wait_activity("DocumentActivity", 8)


def t_word_export():
    """Chuyển sang Word: tài liệu 2 trang (biên bản có bảng 13 hàng x 5 cột chụp trên bàn gỗ / nền be) -> nhận dạng chữ trên
    máy, file .docx hợp lệ, mỗi trang một phần đúng khổ giấy, chữ đọc đúng, bảng thành bảng Word; nút Huỷ; bộ chọn nơi lưu."""
    doc_id = import_to_editor(["doc_1_wood.jpg", "doc_4_beige.jpg"])
    wait_doc(doc_id, lambda d: len(d["pages"]) == 2 and all(p.get("out") for p in d["pages"]), 120, "xử lý 2 trang")
    time.sleep(1.5)
    tap(desc="Xong")
    wait_activity("DocumentActivity", 25)
    time.sleep(2.0)
    shot("word_document")
    if not exists(text="Word", timeout=5):
        raise AssertionError("Không thấy nút Word trên thanh công cụ")
    # 1. huỷ giữa chừng: không để lại file, ứng dụng vẫn dùng được
    run_as("rm -f cache/share/*.docx cache/share/*.part")
    tap(text="Word")
    time.sleep(1.0)
    shot("word_menu")
    tap(text="Chia sẻ file Word")
    cancelled = False
    try:
        tap(text="Huỷ", timeout=6)
        cancelled = True
    except Exception:
        log("không kịp bấm Huỷ (chuyển đổi xong quá nhanh)")
    if cancelled:
        time.sleep(12)
        left = run_as("ls cache/share").split()
        if any(n.endswith(".docx") or n.endswith(".part") for n in left):
            raise AssertionError("Huỷ chuyển sang Word vẫn để lại file: %s" % left)
        if top_activity() != "DocumentActivity":
            raise AssertionError("Sau khi huỷ không còn ở màn hình tài liệu: %s" % top_activity())
    else:
        wait_share_file(".docx", 240)
        close_chooser()
    # 2. chia sẻ file Word từ bảng Chia sẻ
    run_as("rm -f cache/share/*.docx cache/share/*.part")
    tap(text="Chia sẻ")
    time.sleep(1.0)
    tap(text="Chia sẻ Word", contains=True)
    time.sleep(1.5)
    shot("word_progress")
    name = wait_share_file(".docx", 300)
    if not name:
        raise AssertionError("Không thấy file Word được tạo")
    shot("word_share_sheet")
    data = pull_file("cache/share/" + name)
    open(os.path.join(OUT, "export_word.docx"), "wb").write(data)
    lines, tables, rows, cols, media = validate_docx(data, 2)
    text = strip_accents(" | ".join(lines))
    want = ["bien ban", "thiet bi", "may in laser", "man hinh", "ban phim", "chuot khong day", "mh-003", "mh-052", "mh-080",
            "ben giao", "ben nhan", "cong ty"]
    hit = [w for w in want if w in text]
    log("Word: %s, %d byte, %d dòng chữ, %d bảng (lớn nhất %d hàng x %d cột), %d hình; khớp %d/%d cụm từ" % (
        name, len(data), len(lines), tables, rows, cols, media, len(hit), len(want)))
    notes.append("Word: %d dòng chữ, %d bảng (lớn nhất %d x %d), %d hình, khớp %d/%d cụm từ mẫu" % (len(lines), tables, rows, cols, media, len(hit), len(want)))
    close_chooser()
    if len(hit) < 8:
        raise AssertionError("Word: chỉ đọc đúng %d/%d cụm từ mẫu (%s)" % (len(hit), len(want), ", ".join(hit)))
    if tables < 2 or rows < 12 or cols < 5:
        raise AssertionError("Word: bảng không được dựng lại đúng (%d bảng, lớn nhất %d hàng x %d cột; cần 2 bảng 13 x 5)" % (tables, rows, cols))
    # 3. lưu vào máy: mở bộ chọn nơi lưu với tên file .docx
    tap(text="Word")
    tap(text="Lưu file Word vào máy")
    time.sleep(4)
    shot("word_save_picker")
    picker = top_activity()
    back()
    time.sleep(1.5)
    if top_activity() != "DocumentActivity":
        back()
    wait_activity("DocumentActivity", 8)
    if picker == "DocumentActivity":
        raise AssertionError("Lưu file Word vào máy: không mở bộ chọn nơi lưu")


def newest_share_file(ext):
    out = run_as("ls -t cache/share")
    for line in out.splitlines():
        line = line.strip()
        if line.lower().endswith(ext):
            return line
    return None


def tap(text=None, desc=None, timeout=12, contains=False):
    deadline = time.time() + timeout
    while time.time() < deadline:
        n = find(text=text, desc=desc, contains=contains)
        if n is not None:
            c = bounds(n)
            if c:
                tap_xy(*c)
                time.sleep(0.6)
                return True
        time.sleep(0.8)
    raise AssertionError("Không thấy phần tử %s" % (text if text is not None else desc))


def exists(text=None, desc=None, timeout=6, contains=False):
    deadline = time.time() + timeout
    while time.time() < deadline:
        if find(text=text, desc=desc, contains=contains) is not None:
            return True
        time.sleep(0.8)
    return False


def top_activity():
    out = sh("dumpsys activity activities", timeout=30)
    for key in ("topResumedActivity", "mResumedActivity", "ResumedActivity"):
        for line in out.splitlines():
            if key in line:
                m = re.search(re.escape(PKG) + r"/\.?([\w.]+)", line)
                if m:
                    return m.group(1).split(".")[-1]
                m = re.search(r"\s([\w.]+)/([\w.$]+)", line)
                if m:
                    return m.group(2).split(".")[-1]
    return ""


def wait_activity(name, timeout=15):
    deadline = time.time() + timeout
    last = ""
    while time.time() < deadline:
        last = top_activity()
        if last == name:
            return True
        time.sleep(0.7)
    raise AssertionError("Đợi %s nhưng màn hình hiện tại là %s" % (name, last or "?"))


def back():
    sh("input keyevent 4")
    time.sleep(0.8)


def crash_buffer():
    return adb("logcat", "-d", "-b", "crash", timeout=30).strip()


def app_crashes(buf):
    """Các khối FATAL EXCEPTION thuộc tiến trình của ứng dụng (bỏ qua crash của dịch vụ hệ thống như Google Play services)."""
    blocks, cur = [], None
    for line in buf.splitlines():
        if "FATAL EXCEPTION" in line:
            cur = [line]
            blocks.append(cur)
        elif cur is not None:
            cur.append(line)
    mine = []
    for b in blocks:
        proc = [l for l in b[:6] if "Process:" in l]
        # không thấy dòng "Process:" thì coi như của ứng dụng (thà báo nhầm còn hơn bỏ sót)
        if not proc or any(("Process: " + PKG) in l for l in proc):
            mine.append("\n".join(b))
    return mine


def check_no_crash(where):
    c = crash_buffer()
    mine = app_crashes(c)
    if mine:
        open(os.path.join(OUT, "crash_%s.txt" % re.sub(r"\W+", "_", where)), "w").write(c)
        adb("logcat", "-b", "crash", "-c")
        raise AssertionError("Ứng dụng bị crash tại %s:\n%s" % (where, "\n".join(mine)[-3000:]))
    if "FATAL EXCEPTION" in c:
        other = sorted(set(re.findall(r"Process: ([\w.:]+)", c)))
        note = "Tiến trình hệ thống bị crash trong lúc kiểm thử (không phải ứng dụng): %s" % ", ".join(other)
        if note not in notes:
            notes.append(note)
        adb("logcat", "-b", "crash", "-c")
    anr = sh("dumpsys activity processes | grep -i 'notResponding' | head -3", timeout=20)
    if PKG in anr:
        raise AssertionError("ANR tại %s" % where)


def only_selects(name):
    """TEST_ONLY có chọn bước này không: tên khớp biểu thức chính quy, hoặc chứa nguyên văn một phần của TEST_ONLY (các
    phần ngăn bởi |). Tên bước có ngoặc đơn, như "Bộ lọc Làm phẳng (giấy nhàu, gấp nếp)", chép nguyên văn từ summary.md
    không khớp dưới dạng biểu thức chính quy (ngoặc là nhóm) nên so thêm theo nguyên văn."""
    try:
        if re.search(ONLY, name):
            return True
    except re.error:
        pass
    return any(p.strip() and p.strip() in name for p in ONLY.split("|"))


def step(name, fn):
    if ONLY:
        if only_selects(name):
            only_hits.append(name)
        elif name not in ("Cài đặt", "Mở ứng dụng"):
            return
    log("=== %s" % name)
    t0 = time.time()
    try:
        fn()
        check_no_crash(name)
        results.append({"step": name, "ok": True, "sec": round(time.time() - t0, 1)})
        log("PASS", name)
    except Exception as e:
        msg = "%s: %s" % (type(e).__name__, e)
        results.append({"step": name, "ok": False, "sec": round(time.time() - t0, 1), "error": msg})
        log("FAIL", name, msg)
        traceback.print_exc()
        shot("FAIL_" + re.sub(r"\W+", "_", name))
        dump("FAIL_" + re.sub(r"\W+", "_", name))


def push_test_image(local, name):
    """Đặt ảnh vào cache/share của ứng dụng (qua run-as) để nhập bằng Intent chia sẻ."""
    data = open(local, "rb").read()
    sh("run-as %s mkdir -p cache/share" % PKG)
    write_app_file("cache/share/%s" % name, data)


def share_into_app(names):
    uris = ["content://%s.share/%s" % (PKG, n) for n in names]
    if len(uris) == 1:
        cmd = "am start -a android.intent.action.SEND -t image/jpeg --eu android.intent.extra.STREAM %s -n %s/.MainActivity" % (uris[0], PKG)
    else:
        cmd = "am start -a android.intent.action.SEND_MULTIPLE -t image/jpeg --eual android.intent.extra.STREAM %s -n %s/.MainActivity" % (",".join(uris), PKG)
    out = sh(cmd)
    log(out.strip())
    if "Error" in out:
        raise AssertionError(out)


def wait_processing(seconds=12):
    """Chờ xử lý ảnh xong (không còn hiệu ứng quét). Đơn giản: chờ cố định rồi chụp."""
    time.sleep(seconds)


def go_home():
    for _ in range(6):
        if top_activity() == "MainActivity":
            for _ in range(3):
                if not dialog_open():
                    return
                back()
                time.sleep(0.8)
            return
        back()
        # hộp thoại xác nhận bỏ trang
        n = find(text="Bỏ")
        if n is not None:
            tap_xy(*bounds(n))
            time.sleep(0.8)
    sh("am start -W -n %s/.MainActivity" % PKG)
    time.sleep(2)


def fresh_home():
    """Khởi động lại ứng dụng: màn hình chính với ô tìm kiếm trống (bước trước có thể còn để lại từ khoá)."""
    sh("am force-stop %s" % PKG)
    time.sleep(1)
    start_app()
    time.sleep(2.5)
    wait_activity("MainActivity", 10)


# ----------------------------------------------------------------------------- các bước

def t_install():
    out = ""
    for attempt in range(3):
        # Máy ảo vừa khởi động có lúc chưa sẵn sàng nhận gói (adb install treo tới hết thời gian chờ):
        # đợi dịch vụ quản lý gói trả lời rồi mới cài, lỗi thì thử lại.
        for _ in range(30):
            if "package:" in sh("pm path android", timeout=20):
                break
            time.sleep(2)
        out = adb("install", "-r", "-g", APK, timeout=240)
        log(out.strip())
        if "Success" in out:
            break
        log("Cài đặt chưa được (lần %d), thử lại" % (attempt + 1))
        time.sleep(5)
    if "Success" not in out:
        raise AssertionError(out)
    sh("pm grant %s android.permission.CAMERA" % PKG)
    adb("logcat", "-c")
    adb("logcat", "-b", "crash", "-c")


def t_launch():
    out = sh("am start -W -n %s/.MainActivity" % PKG, timeout=60)
    log(out.strip())
    time.sleep(2.5)
    wait_activity("MainActivity", 10)
    if not exists(text="Gần đây", timeout=6):
        raise AssertionError("Màn hình chính không có mục 'Gần đây'")
    if not exists(desc="Quét tài liệu", timeout=3):
        raise AssertionError("Không thấy nút máy ảnh")
    shot("home_empty")


def capture_once(label):
    wait_activity("CameraActivity", 12)
    time.sleep(3.5)  # mở camera
    shot("camera_" + label)
    tap(desc="Chụp", timeout=10)
    wait_activity("CropActivity", 20)
    time.sleep(3.0)  # giải mã + tìm mép
    shot("crop_" + label)
    if not exists(text="Tự động cắt", timeout=4):
        raise AssertionError("Màn hình cắt thiếu nút 'Tự động cắt'")


def t_single_scan():
    tap(desc="Quét tài liệu")
    capture_once("single")
    # thử bật/tắt Tự động cắt
    tap(text="Tự động cắt")
    time.sleep(0.8)
    shot("crop_auto_off")
    tap(text="Tự động cắt")
    time.sleep(0.8)
    tap(text="Tiếp tục")
    wait_activity("EditorActivity", 15)
    time.sleep(1.2)
    shot("editor_processing")
    wait_processing(10)
    shot("editor_done")
    if not exists(text="1/1", timeout=4):
        raise AssertionError("Không thấy chỉ số trang 1/1")


def t_editor_tools():
    wait_activity("EditorActivity", 5)
    editor_tool("Bộ lọc")
    time.sleep(2.5)
    shot("filter_panel")
    for name in ["Đen trắng", "Thang xám", "Nguyên gốc", "Làm sáng", "Tăng cường"]:
        filter_pick(name)
        time.sleep(4.5)
        shot("filter_" + re.sub(r"\W+", "_", name))
    tap(desc="Đóng bộ lọc")
    editor_tool("Bên trái")
    time.sleep(5)
    shot("rotated_left")
    # So sánh: nhấn giữ
    n = find(text="So sánh")
    if n is not None:
        x, y = bounds(n)
        sh("input swipe %d %d %d %d 1500" % (x, y, x, y))
    # Cắt lại
    editor_tool("Cắt")
    wait_activity("CropActivity", 10)
    time.sleep(2.5)
    shot("recrop")
    # kéo góc trên-trái vào trong một chút
    w, h = screen_size()
    sh("input swipe %d %d %d %d 600" % (int(w * 0.12), int(h * 0.12), int(w * 0.2), int(h * 0.18)))
    time.sleep(0.5)
    tap(text="Xong")
    wait_activity("EditorActivity", 10)
    wait_processing(8)
    shot("after_recrop")


def t_save_document():
    tap(desc="Xong")
    wait_activity("DocumentActivity", 25)
    time.sleep(2.5)
    shot("document_1page")
    if not exists(text="1/1", timeout=5):
        raise AssertionError("Trang tài liệu không hiển thị")


def t_share_pdf():
    tap(text="Chia sẻ")
    time.sleep(1.2)
    shot("share_dialog")
    tap(text="Chia sẻ PDF", contains=True)
    time.sleep(4)
    shot("share_sheet_pdf")
    back()
    time.sleep(1)
    if top_activity() != "DocumentActivity":
        back()
    tap(text="Chia sẻ")
    tap(text="Chia sẻ ảnh JPG")
    time.sleep(4)
    shot("share_sheet_jpg")
    back()
    time.sleep(1)
    if top_activity() != "DocumentActivity":
        back()
    wait_activity("DocumentActivity", 8)


def saved_image_names():
    q = sh("content query --uri content://media/external/images/media --projection _display_name", timeout=30)
    return q, re.findall(r"_display_name=(Scan[^,\n]*)", q)


def t_save_images():
    tap(text="Lưu ảnh")
    time.sleep(4)
    shot("save_images")
    q, names = saved_image_names()
    open(os.path.join(OUT, "mediastore.txt"), "w").write(q)
    if not names:
        raise AssertionError("Không thấy ảnh đã lưu trong Thư viện")
    # lưu lần nữa (cùng tên): ảnh cũ không được bị ghi đè -> phải thành 2 ảnh tên khác nhau
    tap(text="Lưu ảnh")
    names2 = names
    for _ in range(8):
        time.sleep(2)
        q, names2 = saved_image_names()
        if len(names2) > len(names):
            break
    open(os.path.join(OUT, "mediastore.txt"), "w").write(q)
    log("ảnh trong Thư viện:", names2)
    if len(names2) != len(names) + 1 or len(set(names2)) != len(names2):
        raise AssertionError("Lưu ảnh trùng tên: %s -> %s" % (names, names2))


def t_save_pdf_picker():
    tap(text="Lưu PDF")
    time.sleep(4)
    shot("save_pdf_picker")
    back()
    time.sleep(1.5)
    if top_activity() != "DocumentActivity":
        back()
    wait_activity("DocumentActivity", 8)


def t_add_page():
    tap(text="Thêm")
    capture_once("add")
    tap(text="Tiếp tục")
    wait_activity("EditorActivity", 15)
    wait_processing(10)
    shot("editor_2pages")
    w, h = screen_size()
    sh("input swipe %d %d %d %d 300" % (int(w * 0.8), int(h * 0.45), int(w * 0.2), int(h * 0.45)))
    time.sleep(1.5)
    shot("editor_swipe")
    tap(desc="Xong")
    wait_activity("DocumentActivity", 25)
    time.sleep(2.5)
    shot("document_2pages")
    # trang 1 có thể cao hơn màn hình (ảnh đã xoay) nên kiểm tra nhãn "1/2" của trang đầu
    if not exists(text="1/2", timeout=6):
        raise AssertionError("Tài liệu không có 2 trang")


def t_rename():
    tap(desc="Đổi tên")
    time.sleep(1.2)
    sh("input keyevent KEYCODE_MOVE_END")
    for _ in range(25):
        sh("input keyevent KEYCODE_DEL")
    sh("input text 'Bien_ban_test'")
    time.sleep(0.5)
    tap(text="OK")
    time.sleep(1)
    shot("renamed")
    if not exists(text="Bien_ban_test", timeout=4):
        raise AssertionError("Đổi tên không thành công")


def t_batch_mode():
    go_home()
    tap(text="Hàng loạt")
    wait_activity("CameraActivity", 12)
    time.sleep(3.5)
    tap(desc="Chụp")
    wait_activity("CropActivity", 20)
    time.sleep(2.5)
    tap(text="Tiếp tục")
    wait_activity("CameraActivity", 10)
    time.sleep(3)
    shot("batch_1")
    tap(desc="Chụp")
    wait_activity("CropActivity", 20)
    time.sleep(2.5)
    tap(text="Tiếp tục")
    wait_activity("CameraActivity", 10)
    time.sleep(3)
    shot("batch_2")
    if not exists(desc="Hoàn tác", timeout=4):
        raise AssertionError("Không có nút hoàn tác ở chế độ hàng loạt")
    tap(desc="Hoàn tác")
    time.sleep(1.5)
    shot("batch_undo")
    tap(desc="Xem các trang đã chụp")
    wait_activity("EditorActivity", 12)
    wait_processing(9)
    shot("batch_editor")
    tap(desc="Xong")
    wait_activity("DocumentActivity", 25)
    time.sleep(2)
    shot("batch_document")


def t_camera_controls():
    go_home()
    tap(desc="Quét tài liệu")
    wait_activity("CameraActivity", 12)
    time.sleep(3)
    for d in ["Đèn flash", "Lưới căn", "Chất lượng HD"]:
        if exists(desc=d, timeout=2):
            tap(desc=d)
            time.sleep(2.5)
    shot("camera_toggles")
    tap(desc="Chất lượng HD")
    time.sleep(2.5)
    tap(desc="Lưới căn")
    time.sleep(1)
    tap(desc="Đóng")
    time.sleep(1.5)
    wait_activity("MainActivity", 8)


def t_import(name):
    def run():
        go_home()
        local = os.path.join(IMAGES, name)
        push_test_image(local, name)
        share_into_app([name])
        wait_activity("CropActivity", 25)
        time.sleep(4)
        shot("import_crop_" + name.replace(".jpg", ""))
        tap(text="Xong")
        wait_activity("EditorActivity", 15)
        wait_processing(12)
        shot("import_result_" + name.replace(".jpg", ""))
        tap(desc="Xong")
        wait_activity("DocumentActivity", 25)
        time.sleep(1.5)
    return run


def t_import_multi():
    go_home()
    names = ["doc_2_dark.jpg", "doc_3_gray.jpg", "doc_6_clutter.jpg"]
    for n in names:
        push_test_image(os.path.join(IMAGES, n), n)
    # "am start" không truyền được ArrayList<Uri> (SEND_MULTIPLE) -> dùng lối vào kiểm thử (chỉ có ở bản debug)
    uris = ",".join("content://%s.share/%s" % (PKG, n) for n in names)
    out = sh("am start -a %s.TEST_IMPORT --esa uris %s -n %s/.MainActivity" % (PKG, uris, PKG))
    log(out.strip())
    if "Error" in out:
        raise AssertionError(out)
    wait_activity("EditorActivity", 40)
    wait_processing(20)
    shot("import_multi_editor")
    if not exists(text="/3", timeout=5, contains=True):
        raise AssertionError("Nhập nhiều ảnh không đủ 3 trang")
    tap(desc="Xong")
    wait_activity("DocumentActivity", 40)
    time.sleep(2)
    shot("import_multi_document")


def t_stress_import():
    """Nhập 12 ảnh lớn (12 MP) cùng lúc: kiểm tra bộ nhớ, hàng đợi xử lý, xuất PDF nhiều trang."""
    try:
        from PIL import Image
    except ImportError:
        notes.append("Bỏ qua kiểm thử ảnh lớn (thiếu Pillow)")
        return
    go_home()
    srcs = ["doc_1_wood.jpg", "doc_2_dark.jpg", "doc_3_gray.jpg", "doc_4_beige.jpg", "doc_5_blue.jpg", "doc_6_clutter.jpg"]
    names = []
    for i in range(12):
        im = Image.open(os.path.join(IMAGES, srcs[i % len(srcs)])).convert("RGB").resize((3024, 4032), Image.BILINEAR)
        name = "big_%02d.jpg" % i
        path = os.path.join(OUT, name)
        im.save(path, quality=90)
        push_test_image(path, name)
        os.remove(path)
        names.append(name)
    uris = ",".join("content://%s.share/%s" % (PKG, n) for n in names)
    t0 = time.time()
    out = sh("am start -a %s.TEST_IMPORT --esa uris %s -n %s/.MainActivity" % (PKG, uris, PKG))
    if "Error" in out:
        raise AssertionError(out)
    wait_activity("EditorActivity", 180)
    log("nhập 12 ảnh lớn: %.1fs đến trình chỉnh sửa" % (time.time() - t0))
    if not exists(text="/12", timeout=10, contains=True):
        raise AssertionError("Không đủ 12 trang")
    time.sleep(20)
    shot("stress_editor")
    tap(desc="Xong")
    wait_activity("DocumentActivity", 300)
    log("nhập + xử lý 12 ảnh lớn: %.1fs" % (time.time() - t0))
    time.sleep(3)
    shot("stress_document")
    if not exists(text="1/12", timeout=8):
        raise AssertionError("Tài liệu không đủ 12 trang")
    tap(text="Chia sẻ")
    time.sleep(1)
    tap(text="Chia sẻ PDF", contains=True)
    time.sleep(6)
    shot("stress_share_pdf")
    back()
    time.sleep(1)
    check_no_crash("stress")


def t_home_list():
    go_home()
    time.sleep(2)
    shot("home_list")
    tap(desc="Tùy chọn tài liệu")
    time.sleep(1)
    shot("doc_menu")
    back()
    root = dump()
    edit = None
    for n in nodes(root):
        if n.get("class", "").endswith("EditText"):
            edit = n
            break
    if edit is None:
        raise AssertionError("Không thấy ô tìm kiếm")
    tap_xy(*bounds(edit))
    sh("input text 'Bien'")
    time.sleep(1.5)
    shot("search")
    if not exists(text="Bien_ban_test", timeout=4):
        raise AssertionError("Tìm kiếm không ra tài liệu đã đổi tên")
    back()


def doc_count():
    n = find(text="tài liệu", contains=True)
    if n is None:
        return 0
    m = re.search(r"(\d+)", n.get("text", ""))
    return int(m.group(1)) if m else 0


def t_delete_document():
    go_home()
    before = doc_count()
    tap(desc="Tùy chọn tài liệu")
    tap(text="Xoá")
    time.sleep(0.8)
    shot("delete_confirm")
    tap(text="Xoá")
    time.sleep(1.5)
    shot("after_delete")
    after = doc_count()
    log("số tài liệu: %d -> %d" % (before, after))
    if before and after != before - 1:
        raise AssertionError("Xoá tài liệu không thành công (%d -> %d)" % (before, after))


def first_doc_row():
    """Toạ độ dòng tài liệu đầu tiên trong danh sách (bên trái nút tuỳ chọn)."""
    n = find(desc="Tùy chọn tài liệu")
    if n is None:
        raise AssertionError("Danh sách tài liệu trống")
    x, y = bounds(n)
    w, _ = screen_size()
    return int(w * 0.45), y


def t_double_tap():
    """Chạm đúp rất nhanh (40 ms) lên các nút mở màn hình: chỉ được mở MỘT màn hình."""
    fresh_home()
    n = find(desc="Quét tài liệu")
    x, y = bounds(n)
    fast_taps([(x, y), (x, y)])
    wait_activity("CameraActivity", 12)
    time.sleep(2.5)
    c = activity_instances("CameraActivity")
    shot("double_tap_camera")
    if c != 1:
        raise AssertionError("Chạm đúp nút quét mở %d màn hình chụp" % c)
    tap(desc="Đóng")
    wait_activity("MainActivity", 8)
    x, y = first_doc_row()
    fast_taps([(x, y), (x, y)])
    wait_activity("DocumentActivity", 12)
    time.sleep(1.5)
    c = activity_instances("DocumentActivity")
    if c != 1:
        raise AssertionError("Chạm đúp tài liệu mở %d màn hình tài liệu" % c)
    for label, act in (("Sửa", "EditorActivity"), ("Thêm", "CameraActivity")):
        n = find(text=label)
        x, y = bounds(n)
        fast_taps([(x, y), (x, y)])
        wait_activity(act, 12)
        time.sleep(2.5)
        c = activity_instances(act)
        if c != 1:
            raise AssertionError("Chạm đúp '%s' mở %d màn hình %s" % (label, c, act))
        if act == "CameraActivity":
            tap(desc="Đóng")
        else:
            back()
        wait_activity("DocumentActivity", 10)
        time.sleep(1.0)
    # chạm đúp "Xong" trong trình chỉnh sửa: không được lưu/đóng hai lần hay mở hai màn hình tài liệu
    tap(text="Sửa")
    wait_activity("EditorActivity", 10)
    time.sleep(2)
    x, y = bounds(find(desc="Xong"))
    fast_taps([(x, y), (x, y)])
    wait_activity("DocumentActivity", 15)
    time.sleep(1.5)
    c = activity_instances("DocumentActivity")
    if c != 1:
        raise AssertionError("Chạm đúp 'Xong' để lại %d màn hình tài liệu" % c)
    # chạm đúp "Chia sẻ": chỉ một hộp thoại (một lần Back là đóng)
    x, y = bounds(find(text="Chia sẻ"))
    fast_taps([(x, y), (x, y)])
    time.sleep(1.2)
    shot("double_tap_share")
    back()
    time.sleep(0.8)
    if dialog_open():
        back()
        raise AssertionError("Chạm đúp 'Chia sẻ' mở hai hộp thoại chồng nhau")


def t_flip_then_act():
    """Lật sang trang sau rồi bấm Xoay ngay khi hoạt ảnh lật trang còn đang chạy: phải xoay trang vừa lật tới."""
    go_home()
    target = None
    for d in sorted(all_docs(), key=lambda d: -d.get("modified", 0)):
        if not d.get("draft") and len(d.get("pages", [])) >= 2:
            target = d
            break
    if target is None:
        raise AssertionError("Không có tài liệu nhiều trang để thử")
    # đặt tên riêng để chọn đúng tài liệu trong danh sách (nhiều tài liệu có thể trùng tên theo phút)
    title = "Tai_lieu_nhieu_trang"
    sh("am force-stop %s" % PKG)
    time.sleep(1)
    target["title"] = title
    write_doc(target)
    start_app()
    time.sleep(3)
    wait_activity("MainActivity", 10)
    tap(text=title)
    wait_activity("DocumentActivity", 12)
    time.sleep(1.5)
    tap(text="Sửa")
    wait_activity("EditorActivity", 12)
    time.sleep(3)
    before = [p.get("rot", 0) for p in read_doc(target["id"])["pages"]]
    nx, ny = bounds(find(desc="Trang sau"))
    rx, ry = bounds(find(text="Bên trái"))
    # hai lần "trang sau" liên tiếp (40 ms) rồi xoay sau 80 ms
    n_pages = len(before)
    taps = [(nx, ny)] + ([(nx, ny)] if n_pages >= 3 else []) + [(rx, ry)]
    fast_taps(taps, gap_ms=60)
    time.sleep(7)
    shot("flip_then_rotate")
    expect_index = len(taps) - 1
    if not exists(text="%d/%d" % (expect_index + 1, n_pages), timeout=4):
        raise AssertionError("Bấm 'trang sau' %d lần liên tiếp không tới trang %d" % (len(taps) - 1, expect_index + 1))
    after = [p.get("rot", 0) for p in read_doc(target["id"])["pages"]]
    log("xoay trước/sau:", before, after)
    changed = [i for i in range(n_pages) if before[i] != after[i]]
    if changed != [expect_index]:
        raise AssertionError("Xoay áp dụng sai trang: thay đổi ở %s, cần ở [%d]" % (changed, expect_index))
    # xoay lại cho đủ vòng rồi lưu
    for _ in range(3):
        tap(text="Bên trái")
        time.sleep(0.4)
    time.sleep(6)
    tap(desc="Xong")
    wait_activity("DocumentActivity", 40)


def t_import_corrupt():
    """Nhập tệp không phải ảnh / ảnh hỏng: phải báo lỗi gọn gàng, không tạo trang hỏng, không treo."""
    go_home()
    good = open(os.path.join(IMAGES, "doc_3_gray.jpg"), "rb").read()
    samples = {
        "bad_zero.jpg": b"",
        "bad_text.jpg": b"day khong phai la anh\n" * 40,
        "bad_head.jpg": good[:700],
        "bad_trunc.jpg": good[: len(good) * 35 // 100],
        "bad_noise.png": b"\x89PNG\r\n\x1a\n" + bytes(range(256)) * 8,
    }
    for name, data in samples.items():
        path = os.path.join(OUT, name)
        open(path, "wb").write(data)
        push_test_image(path, name)
        os.remove(path)
    before = len(all_docs())
    for name in ("bad_zero.jpg", "bad_text.jpg", "bad_noise.png"):
        share_into_app([name])
        time.sleep(4)
        check_no_crash("nhập " + name)
        if top_activity() != "MainActivity":
            raise AssertionError("Nhập %s: không ở lại màn hình chính (%s)" % (name, top_activity()))
        if find(text="Gần đây") is None:
            raise AssertionError("Nhập %s: màn hình chính bị treo/che" % name)
    shot("import_corrupt_rejected")
    if len(all_docs()) != before:
        raise AssertionError("Nhập tệp hỏng vẫn tạo tài liệu")
    # lẫn ảnh tốt và tệp hỏng: chỉ ảnh đọc được thành trang
    uris = ",".join("content://%s.share/%s" % (PKG, n) for n in ["bad_zero.jpg", "doc_3_gray.jpg", "bad_text.jpg", "bad_noise.png"])
    out = sh("am start -a %s.TEST_IMPORT --esa uris %s -n %s/.MainActivity" % (PKG, uris, PKG))
    if "Error" in out:
        raise AssertionError(out)
    wait_activity("EditorActivity", 40)
    wait_processing(8)
    shot("import_mixed_editor")
    if not exists(text="1/1", timeout=5):
        raise AssertionError("Nhập lẫn tệp hỏng: số trang không đúng (cần 1)")
    back()
    time.sleep(0.8)
    tap(text="Bỏ")
    wait_activity("MainActivity", 10)
    # ảnh JPEG bị cắt cụt: giải mã được một phần thì thành trang, không thì bị từ chối — cả hai đều phải êm
    for name in ("bad_trunc.jpg", "bad_head.jpg"):
        share_into_app([name])
        time.sleep(5)
        check_no_crash("nhập " + name)
        top = top_activity()
        log("nhập %s -> %s" % (name, top))
        if name == "bad_head.jpg" and top != "MainActivity":
            raise AssertionError("Ảnh JPEG chỉ còn phần đầu vẫn được nhập (thành trang đen): %s" % top)
        if top == "CropActivity":
            time.sleep(2)
            tap(text="Xong")
            wait_activity("EditorActivity", 15)
            wait_processing(8)
            shot("import_" + name.replace(".jpg", ""))
            tap(desc="Xong")
            wait_activity("DocumentActivity", 30)
            time.sleep(2)
            if not exists(text="1/1", timeout=5):
                raise AssertionError("Nhập %s: trang không hiển thị" % name)
            d = newest_doc()
            if d is None or not d["pages"][0].get("out"):
                raise AssertionError("Nhập %s: trang không có ảnh kết quả" % name)
            go_home()
        elif top != "MainActivity":
            raise AssertionError("Nhập %s: màn hình bất thường %s" % (name, top))


def make_format_images():
    """Ảnh thử ở nhiều định dạng / hướng EXIF / kích thước biên. Trả về [(tên, đường dẫn, (rộng, cao) sau khi xoay)]."""
    from PIL import Image, ImageDraw
    base = Image.open(os.path.join(IMAGES, "doc_4_beige.jpg")).convert("RGB")
    out = []

    def add(name, im, oriented, **kw):
        path = os.path.join(OUT, name)
        im.save(path, **kw)
        out.append((name, path, oriented))

    add("fmt_rgba.png", base.resize((611, 803)).convert("RGBA"), (611, 803))
    im = base.resize((604, 806)).convert("RGBA")
    ImageDraw.Draw(im).rectangle([0, 0, 120, 120], fill=(0, 0, 0, 0))
    add("fmt_alpha.png", im, (604, 806))
    try:
        add("fmt_lossy.webp", base.resize((613, 807)), (613, 807), quality=80)
    except Exception as e:
        log("không tạo được WebP:", e)
    add("fmt_pal.gif", base.resize((401, 303)).convert("P"), (401, 303))
    add("fmt_plain.bmp", base.resize((305, 407)), (305, 407))
    add("fmt_gray.jpg", base.resize((615, 809)).convert("L"), (615, 809), quality=85)
    add("fmt_prog.jpg", base.resize((617, 811)), (617, 811), quality=85, progressive=True)
    for orient, size, oriented in ((6, (412, 300), (300, 412)), (8, (420, 310), (310, 420)), (3, (430, 320), (430, 320))):
        ex = Image.Exif()
        ex[0x0112] = orient
        add("fmt_exif%d.jpg" % orient, base.resize(size), oriented, quality=85, exif=ex)
    add("fmt_tiny.png", base.resize((9, 11)), (9, 11))
    add("fmt_wide.jpg", base.resize((3000, 61)), (3000, 61), quality=85)
    add("fmt_huge.jpg", base.resize((6000, 8000), Image.BILINEAR), (6000, 8000), quality=80)
    return out


def t_formats_and_export():
    """Nhập ảnh nhiều định dạng (PNG trong suốt, WebP, GIF, BMP, JPEG xám/progressive/EXIF xoay, ảnh rất nhỏ,
    rất dẹt, 48 MP) rồi kiểm tra hướng ảnh và file PDF/JPG xuất ra bằng trình phân tích thật."""
    try:
        import PIL  # noqa: F401
    except ImportError:
        notes.append("Bỏ qua kiểm thử định dạng ảnh (thiếu Pillow)")
        return
    go_home()
    imgs = make_format_images()
    for name, path, _ in imgs:
        push_test_image(path, name)
        os.remove(path)
    uris = ",".join("content://%s.share/%s" % (PKG, n) for n, _, _ in imgs)
    out = sh("am start -a %s.TEST_IMPORT --esa uris %s -n %s/.MainActivity" % (PKG, uris, PKG))
    if "Error" in out:
        raise AssertionError(out)
    wait_activity("EditorActivity", 150)
    time.sleep(6)
    shot("formats_editor")
    check_no_crash("nhập nhiều định dạng")
    ds = [d for d in all_docs() if d.get("draft") and d.get("pages")]
    ds.sort(key=lambda d: d.get("modified", 0))
    if not ds:
        raise AssertionError("Không thấy tài liệu vừa nhập")
    doc = ds[-1]
    got = [(p["w"], p["h"]) for p in doc["pages"]]
    log("định dạng: %d/%d trang, kích thước %s" % (len(got), len(imgs), got))
    missing = [n for n, _, o in imgs if o not in got]
    if missing:
        log("không nhập được / sai hướng:", missing)
    # EXIF: kích thước sau khi xoay phải đúng (ảnh 412x300 có hướng 6 -> 300x412)
    for n, _, o in imgs:
        if "exif" in n and o not in got:
            raise AssertionError("Ảnh %s: hướng EXIF xử lý sai (cần %s, có %s)" % (n, o, got))
    if len(missing) > 2:
        raise AssertionError("Quá nhiều định dạng không nhập được: %s" % missing)
    if missing:
        notes.append("Định dạng không nhập được trên máy này: %s" % ", ".join(missing))
    n_pages = len(got)
    tap(desc="Xong")
    wait_activity("DocumentActivity", 300)
    time.sleep(3)
    shot("formats_document")
    saved = read_doc(doc["id"])
    if saved is None or saved.get("draft") or len(saved["pages"]) != n_pages:
        raise AssertionError("Tài liệu nhiều định dạng lưu không đúng")
    for i, pg in enumerate(saved["pages"]):
        if not pg.get("out"):
            raise AssertionError("Trang %d (%sx%s) không có ảnh kết quả" % (i + 1, pg["w"], pg["h"]))
    # ảnh kết quả từng trang đọc được và có kích thước hợp lý
    try:
        import io
        from PIL import Image
        for i, pg in enumerate(saved["pages"]):
            data = pull_file("files/docs/%s/%s" % (doc["id"], pg["out"]))
            im = Image.open(io.BytesIO(data))
            im.load()
            if min(im.size) < 16 or max(im.size) > 3508:
                raise AssertionError("Trang %d: kích thước ảnh kết quả bất thường %s" % (i + 1, im.size))
    except ImportError:
        pass
    # PDF ở cả 3 khổ giấy
    sizes = ["Letter", "Theo kích thước ảnh", "A4"]
    for k, size_label in enumerate(sizes):
        tap(desc="Tùy chọn khác")
        tap(text="Khổ giấy PDF", contains=True)
        tap(text=size_label, contains=True)
        time.sleep(0.8)
        run_as("rm -f cache/share/*.pdf")
        tap(text="Chia sẻ")
        tap(text="Chia sẻ PDF", contains=True)
        name = None
        for _ in range(40):
            time.sleep(1.5)
            name = newest_share_file(".pdf")
            if name:
                break
        if not name:
            raise AssertionError("Không thấy file PDF được tạo (%s)" % size_label)
        time.sleep(2.5)
        data = pull_file("cache/share/" + name)
        open(os.path.join(OUT, "export_%d.pdf" % k), "wb").write(data)
        log("PDF %s: %s, %d byte" % (size_label, name, len(data)))
        validate_pdf(data, n_pages)
        back()
        time.sleep(1)
        if top_activity() != "DocumentActivity":
            back()
        wait_activity("DocumentActivity", 8)
    for k in range(len(sizes)):
        os.remove(os.path.join(OUT, "export_%d.pdf" % k))  # không đẩy file lớn lên nhánh kết quả
    # ảnh JPG chia sẻ
    run_as("rm -f cache/share/Scan*")
    tap(text="Chia sẻ")
    tap(text="Chia sẻ ảnh JPG")
    jpgs = []
    for _ in range(30):
        time.sleep(1.5)
        jpgs = [l.strip() for l in run_as("ls cache/share").splitlines() if re.search(r"^Scan.*_\d+\.jpg$", l.strip())]
        if len(jpgs) >= n_pages:
            break
    time.sleep(2)
    back()
    time.sleep(1)
    if top_activity() != "DocumentActivity":
        back()
    if len(jpgs) != n_pages:
        raise AssertionError("Chia sẻ ảnh: có %d/%d file" % (len(jpgs), n_pages))
    try:
        import io
        from PIL import Image
        for name in (jpgs[0], jpgs[-1]):
            im = Image.open(io.BytesIO(pull_file("cache/share/" + name)))
            im.load()
            if im.format != "JPEG" or min(im.size) < 16:
                raise AssertionError("Ảnh chia sẻ %s không hợp lệ" % name)
    except ImportError:
        pass
    # dọn ảnh thử lớn khỏi bộ nhớ đệm
    run_as("rm -f cache/share/fmt_* cache/share/Scan*")


def t_search_and_long_title():
    """Tìm kiếm không dấu ("bien ban" ra "Biên bản…") và tên tài liệu rất dài / ký tự đặc biệt khi xuất file."""
    go_home()
    d = newest_doc()
    if d is None:
        raise AssertionError("Không có tài liệu")
    long_title = "Biên bản nghiệm thu: máy/thiết bị <A42> *2026* " + "rất dài " * 40
    sh("am force-stop %s" % PKG)
    time.sleep(1)
    d["title"] = long_title
    write_doc(d)
    start_app()
    time.sleep(3)
    wait_activity("MainActivity", 10)
    root = dump()
    edit = None
    for n in nodes(root):
        if n.get("class", "").endswith("EditText"):
            edit = n
            break
    tap_xy(*bounds(edit))
    sh("input text 'bien%sban%snghiem'")
    time.sleep(1.5)
    shot("search_no_accent")
    if not exists(text="Biên bản nghiệm thu", timeout=4, contains=True):
        raise AssertionError("Tìm 'bien ban nghiem' không ra tài liệu 'Biên bản nghiệm thu…'")
    # gõ thêm cho không còn tài liệu nào khớp: phải báo "Không tìm thấy kết quả", không để danh sách trống trơn
    sh("input text 'zzqq'")
    time.sleep(1.5)
    shot("search_no_match")
    if not exists(text="Không tìm thấy kết quả", timeout=4, contains=True):
        raise AssertionError("Tìm kiếm không ra kết quả nhưng không có thông báo")
    sh("input keyevent KEYCODE_DEL KEYCODE_DEL KEYCODE_DEL KEYCODE_DEL")
    time.sleep(1.2)
    if not exists(text="Biên bản nghiệm thu", timeout=4, contains=True):
        raise AssertionError("Xoá bớt từ khoá: danh sách không hiện lại tài liệu")
    back()
    tap(text="Biên bản nghiệm thu", contains=True)
    wait_activity("DocumentActivity", 12)
    time.sleep(2.5)
    run_as("rm -f cache/share/*.pdf")
    tap(text="Chia sẻ")
    tap(text="Chia sẻ PDF", contains=True)
    name = None
    for _ in range(20):
        time.sleep(1.5)
        name = newest_share_file(".pdf")
        if name:
            break
    shot("long_title_share")
    if not name:
        raise AssertionError("Tên dài: không tạo được file PDF")
    if len(name.encode("utf-8")) > 200 or any(c in name for c in '/\\:*?"<>|'):
        raise AssertionError("Tên file xuất không an toàn: %r" % name)
    validate_pdf(pull_file("cache/share/" + name), len(d["pages"]))
    back()
    time.sleep(1)
    if top_activity() != "DocumentActivity":
        back()
    # đổi tên qua hộp thoại: giới hạn độ dài, tên chỉ có khoảng trắng bị bỏ qua
    tap(desc="Đổi tên")
    time.sleep(1.2)
    sh("input keyevent KEYCODE_MOVE_END")
    sh("input text '%s'" % ("x" * 60))
    time.sleep(0.5)
    tap(text="OK")
    time.sleep(1)
    cur = read_doc(d["id"])
    if cur is None or len(cur["title"]) > 120:
        raise AssertionError("Tên tài liệu vượt giới hạn 120 ký tự (%d)" % (len(cur["title"]) if cur else -1))
    d["title"] = "Bien_ban_test"
    sh("am force-stop %s" % PKG)
    time.sleep(1)
    cur["title"] = "Bien_ban_test"
    write_doc(cur)
    start_app()
    time.sleep(2.5)


def t_config_change():
    """Đổi cấu hình hệ thống (cỡ chữ) khi đang ở từng màn hình -> Activity bị tạo lại, trạng thái phải còn nguyên."""
    go_home()

    def scale(v):
        sh("settings put system font_scale %s" % v)
        time.sleep(2.5)

    try:
        scale("1.15")
        wait_activity("MainActivity", 10)
        if not exists(text="Gần đây", timeout=5):
            raise AssertionError("Màn hình chính hỏng sau khi đổi cỡ chữ")
        tap(text="Hàng loạt")
        wait_activity("CameraActivity", 12)
        time.sleep(3.5)
        tap(desc="Chụp", timeout=10)
        wait_activity("CropActivity", 25)
        time.sleep(3)
        tap(text="Tự động cắt")  # tắt tự động cắt rồi đổi cấu hình giữa lúc khung đang chuyển động
        scale("1.0")
        wait_activity("CropActivity", 10)
        time.sleep(3)
        shot("config_crop")
        if not exists(text="Tự động cắt", timeout=5):
            raise AssertionError("Màn hình cắt hỏng sau khi đổi cỡ chữ")
        tap(text="Tiếp tục")
        wait_activity("CameraActivity", 20)
        time.sleep(3.5)
        scale("1.15")
        wait_activity("CameraActivity", 10)
        time.sleep(3.5)
        shot("config_camera")
        if not exists(desc="Hoàn tác", timeout=5):
            raise AssertionError("Màn hình chụp mất trang đã chụp sau khi đổi cỡ chữ")
        tap(desc="Xem các trang đã chụp")
        wait_activity("EditorActivity", 20)
        scale("1.0")  # đúng lúc đang xử lý ảnh
        wait_activity("EditorActivity", 10)
        tap(text="Bên trái")
        scale("1.15")  # đúng lúc đang xoay
        wait_activity("EditorActivity", 10)
        wait_processing(9)
        shot("config_editor")
        if not exists(text="1/1", timeout=5):
            raise AssertionError("Trình chỉnh sửa mất trang sau khi đổi cỡ chữ")
        tap(desc="Xong")
        scale("1.0")  # đúng lúc đang lưu / chuyển màn hình
        wait_activity("DocumentActivity", 40)
        time.sleep(2.5)
        shot("config_document")
        if not exists(text="1/1", timeout=6):
            raise AssertionError("Tài liệu không có trang sau khi đổi cỡ chữ")
        d = newest_doc()
        if d is None or len(d["pages"]) != 1 or d["pages"][0].get("rot") != 270 or not d["pages"][0].get("out"):
            raise AssertionError("Tài liệu lưu sai sau khi đổi cấu hình: %s" % (d and d["pages"]))
        if activity_instances("DocumentActivity") != 1 or activity_instances("EditorActivity") != 0:
            raise AssertionError("Ngăn xếp màn hình sai sau khi đổi cấu hình")
    finally:
        sh("settings put system font_scale 1.0")
        time.sleep(1.5)
        go_home()


def t_process_death():
    """Hệ thống thu hồi tiến trình khi ứng dụng ở nền (thiếu bộ nhớ) rồi người dùng quay lại: màn hình và dữ liệu phải được khôi phục."""
    sh("am force-stop %s" % PKG)
    time.sleep(1)
    start_app(launcher=True)
    time.sleep(2.5)
    wait_activity("MainActivity", 10)
    tap(desc="Quét tài liệu")
    wait_activity("CameraActivity", 12)
    time.sleep(3)
    tap(text="Hàng loạt")
    time.sleep(1)
    for _ in range(2):
        tap(desc="Chụp", timeout=10)
        wait_activity("CropActivity", 25)
        time.sleep(3)
        tap(text="Tiếp tục")
        wait_activity("CameraActivity", 20)
        time.sleep(3.5)
    tap(desc="Xem các trang đã chụp")
    wait_activity("EditorActivity", 20)
    wait_processing(9)
    editor_tool("Bộ lọc")
    time.sleep(1.5)
    filter_pick("Đen trắng")
    time.sleep(5)
    # đóng bảng lọc: nếu máy ảo không thu hồi được tiến trình thì thanh công cụ (nút ✓) vẫn phải đang hiện
    tap(desc="Đóng bộ lọc")
    time.sleep(0.8)
    sh("input keyevent KEYCODE_HOME")
    time.sleep(2.5)
    pid = app_pid()
    sh("am kill %s" % PKG)
    time.sleep(1.5)
    pid2 = app_pid()
    log("tiến trình trước/sau am kill: %r -> %r" % (pid, pid2))
    if pid2 and pid2 == pid:
        notes.append("am kill không thu hồi được tiến trình (máy ảo giữ ứng dụng)")
    start_app(launcher=True)
    time.sleep(3)
    wait_activity("EditorActivity", 20)
    wait_processing(7)
    shot("process_death_editor")
    if not exists(text="/2", timeout=6, contains=True):
        raise AssertionError("Sau khi tiến trình bị thu hồi: trình chỉnh sửa không còn 2 trang")
    tap(desc="Xong")
    wait_activity("DocumentActivity", 40)
    time.sleep(2.5)
    shot("process_death_document")
    if not exists(text="1/2", timeout=6):
        raise AssertionError("Sau khi tiến trình bị thu hồi: tài liệu không đủ 2 trang")
    d = newest_doc()
    if d is None or len(d["pages"]) != 2 or d.get("draft"):
        raise AssertionError("Tài liệu lưu sai sau khi tiến trình bị thu hồi")
    if d["pages"][0].get("filter") != 4:
        raise AssertionError("Bộ lọc Đen trắng không được giữ sau khi tiến trình bị thu hồi (%s)" % d["pages"][0].get("filter"))
    go_home()


def t_draft_recovery():
    """Ứng dụng bị buộc dừng giữa lúc quét hàng loạt: các trang đã chụp phải được khôi phục thành tài liệu."""
    go_home()
    time.sleep(1)
    before = doc_count()
    tap(text="Hàng loạt")
    wait_activity("CameraActivity", 12)
    time.sleep(3.5)
    for _ in range(2):
        tap(desc="Chụp", timeout=10)
        wait_activity("CropActivity", 25)
        time.sleep(3)
        tap(text="Tiếp tục")
        wait_activity("CameraActivity", 20)
        time.sleep(3.5)
    sh("am force-stop %s" % PKG)
    time.sleep(1.5)
    start_app()
    time.sleep(4)
    wait_activity("MainActivity", 10)
    shot("draft_recovered_home")
    after = doc_count()
    log("số tài liệu trước/sau khi buộc dừng: %d -> %d" % (before, after))
    if after != before + 1:
        raise AssertionError("Bản nháp 2 trang không được khôi phục (%d -> %d)" % (before, after))
    x, y = first_doc_row()
    tap_xy(x, y)
    wait_activity("DocumentActivity", 12)
    time.sleep(12)  # xử lý các trang còn thiếu ảnh kết quả
    shot("draft_recovered_document")
    if not exists(text="1/2", timeout=8):
        raise AssertionError("Tài liệu khôi phục không đủ 2 trang")
    d = newest_doc()
    if d is None or len(d["pages"]) != 2 or any(not p.get("out") for p in d["pages"]):
        raise AssertionError("Tài liệu khôi phục chưa xử lý xong trang: %s" % (d and d["pages"]))
    # xoá tài liệu ngay trong màn hình tài liệu
    tap(desc="Tùy chọn khác")
    tap(text="Xoá tài liệu")
    tap(text="Xoá")
    wait_activity("MainActivity", 10)
    time.sleep(1.5)
    if doc_count() != before:
        raise AssertionError("Xoá tài liệu khôi phục không thành công")
    # mở lại ứng dụng lần nữa: không được "khôi phục" thêm gì (không hồi sinh tài liệu đã xoá)
    sh("am force-stop %s" % PKG)
    time.sleep(1)
    start_app()
    time.sleep(3.5)
    if doc_count() != before:
        raise AssertionError("Tài liệu đã xoá xuất hiện lại sau khi mở lại ứng dụng")


def t_permission_denied():
    """Từ chối quyền Máy ảnh: hiện hướng dẫn, không treo/không dừng; cấp lại thì chụp được."""
    go_home()
    sh("pm revoke %s android.permission.CAMERA" % PKG)  # hệ thống đóng ứng dụng khi thu hồi quyền
    time.sleep(2)
    try:
        start_app()
        time.sleep(2.5)
        wait_activity("MainActivity", 10)
        tap(desc="Quét tài liệu")
        time.sleep(3)
        shot("perm_dialog")
        deny = find_id("permission_deny_button")
        if deny is None:
            for label in ("Don’t allow", "Don't allow", "Deny", "Từ chối"):
                deny = find(text=label)
                if deny is not None:
                    break
        if deny is None:
            raise AssertionError("Không thấy hộp thoại xin quyền máy ảnh")
        tap_xy(*bounds(deny))
        time.sleep(2)
        wait_activity("CameraActivity", 10)
        shot("perm_denied")
        if not exists(text="cần quyền Máy ảnh", timeout=5, contains=True):
            raise AssertionError("Không hiện hướng dẫn sau khi từ chối quyền")
        # bấm chụp khi chưa có quyền: không được dừng ứng dụng
        tap(desc="Chụp")
        time.sleep(2)
        # lưu ý: phần tử ElementTree không có nút con bị coi là False -> phải so với None, không dùng "or"
        deny = find_id("permission_deny_button")
        if deny is None:
            deny = find_id("permission_deny_and_dont_ask_again_button")
        if deny is not None:
            tap_xy(*bounds(deny))
            time.sleep(1.5)
        check_no_crash("chụp khi bị từ chối quyền")
        # hộp thoại xin quyền là một màn hình riêng của hệ thống: đợi màn hình chụp trở lại;
        # chỉ bấm Back khi hộp thoại vẫn còn (Back trên màn hình chụp sẽ đóng luôn màn hình chụp)
        for _ in range(8):
            if top_activity() == "CameraActivity":
                break
            time.sleep(0.8)
        else:
            back()
            time.sleep(1.5)
        wait_activity("CameraActivity", 8)
        if not exists(text="cần quyền Máy ảnh", timeout=5, contains=True):
            raise AssertionError("Từ chối lần hai: không còn hướng dẫn cấp quyền")
        # vẫn nhập ảnh / đóng màn hình được
        tap(desc="Đóng")
        wait_activity("MainActivity", 10)
    finally:
        sh("am force-stop %s" % PKG)
        sh("pm grant %s android.permission.CAMERA" % PKG)
        # thu hồi rồi cấp lại bằng lệnh có thể để quyền truy cập máy ảnh (app-op) ở trạng thái bị chặn trên vài bản Android
        sh("appops set %s CAMERA allow" % PKG)
        sh("appops set --uid %s CAMERA allow" % PKG)
        time.sleep(1)
        start_app()
        time.sleep(2.5)
    tap(desc="Quét tài liệu")
    wait_activity("CameraActivity", 12)
    time.sleep(3.5)
    tap(desc="Chụp", timeout=10)
    wait_activity("CropActivity", 25)
    time.sleep(2.5)
    tap(text="Chụp lại")
    wait_activity("CameraActivity", 10)
    time.sleep(2)
    tap(desc="Đóng")
    wait_activity("MainActivity", 10)


def t_camera_blocked_retry():
    """Máy ảnh bị chặn / bị ngắt khi đang ở màn hình chụp: phải báo rõ kèm nút "Thử lại" (không để màn hình đen);
    hết bị chặn thì bấm Thử lại là chụp được."""
    go_home()
    tap(desc="Quét tài liệu")
    wait_activity("CameraActivity", 12)
    time.sleep(3.5)
    try:
        # chặn ở cả mức gói và mức uid (Android mới chỉ xét mức uid với quyền thời gian chạy)
        sh("appops set %s CAMERA ignore" % PKG)
        sh("appops set --uid %s CAMERA ignore" % PKG)
        time.sleep(3)
        blocked = exists(text="Thử lại", timeout=4)
        if not blocked:
            # hệ thống không ngắt máy ảnh đang mở: mở lại màn hình chụp để gặp lỗi ngay lúc mở máy ảnh
            tap(desc="Đóng")
            wait_activity("MainActivity", 10)
            tap(desc="Quét tài liệu")
            wait_activity("CameraActivity", 12)
            time.sleep(4)
            blocked = exists(text="Thử lại", timeout=5)
        shot("camera_blocked")
        if not blocked:
            notes.append("Không chặn được máy ảnh bằng appops trên máy ảo này: bỏ qua kiểm thử nút Thử lại")
            tap(desc="Đóng")
            wait_activity("MainActivity", 10)
            return
        # bấm chụp / thử lại khi vẫn đang bị chặn: không treo, không dừng, vẫn ở màn hình chụp và còn nút Thử lại
        tap(desc="Chụp")
        time.sleep(2)
        tap(text="Thử lại")
        time.sleep(3)
        check_no_crash("máy ảnh bị chặn")
        if top_activity() != "CameraActivity" or not exists(text="Thử lại", timeout=5):
            raise AssertionError("Máy ảnh vẫn bị chặn nhưng không còn thông báo / nút Thử lại")
        sh("appops set %s CAMERA allow" % PKG)
        sh("appops set --uid %s CAMERA allow" % PKG)
        time.sleep(1.5)
        tap(text="Thử lại")
        time.sleep(4)
        shot("camera_retry_ok")
        if exists(text="Thử lại", timeout=2):
            raise AssertionError("Hết bị chặn nhưng Thử lại không mở lại được máy ảnh")
        tap(desc="Chụp", timeout=10)
        wait_activity("CropActivity", 25)
        time.sleep(2.5)
        tap(text="Chụp lại")
        wait_activity("CameraActivity", 10)
        time.sleep(2)
        tap(desc="Đóng")
        wait_activity("MainActivity", 10)
    finally:
        sh("appops set %s CAMERA allow" % PKG)
        sh("appops set --uid %s CAMERA allow" % PKG)


def t_dont_keep_activities():
    """Bật "Không giữ hoạt động": mỗi màn hình bị huỷ khi rời đi -> kiểm tra lưu/khôi phục trạng thái."""
    go_home()
    sh("settings put global always_finish_activities 1")
    try:
        # chụp đơn -> cắt -> trình chỉnh sửa -> cắt lại -> lưu
        tap(desc="Quét tài liệu")
        wait_activity("CameraActivity", 12)
        time.sleep(3)
        tap(text="Đơn")
        time.sleep(1)
        tap(desc="Chụp", timeout=10)
        wait_activity("CropActivity", 25)
        time.sleep(3)
        tap(text="Tiếp tục")
        wait_activity("EditorActivity", 25)
        wait_processing(8)
        shot("dka_editor")
        editor_tool("Cắt")
        wait_activity("CropActivity", 15)
        time.sleep(2.5)
        tap(text="Xong")
        wait_activity("EditorActivity", 15)
        wait_processing(6)
        tap(desc="Xong")
        wait_activity("DocumentActivity", 25)
        time.sleep(2)
        shot("dka_document_single")
        if not exists(text="1/1", timeout=5):
            raise AssertionError("Không giữ hoạt động: tài liệu chụp đơn không có trang")
        go_home()
        # hàng loạt: 2 trang, camera bị huỷ/khôi phục sau mỗi lần cắt
        tap(desc="Quét tài liệu")
        wait_activity("CameraActivity", 12)
        time.sleep(3)
        tap(text="Hàng loạt")
        time.sleep(1)
        for k in range(2):
            tap(desc="Chụp", timeout=10)
            wait_activity("CropActivity", 25)
            time.sleep(3)
            tap(text="Tiếp tục")
            wait_activity("CameraActivity", 20)
            time.sleep(3.5)
        shot("dka_batch_2")
        if not exists(text="2", timeout=4):
            raise AssertionError("Không giữ hoạt động: số trang hàng loạt không đúng")
        tap(desc="Xem các trang đã chụp")
        wait_activity("EditorActivity", 25)
        wait_processing(10)
        if not exists(text="/2", timeout=5, contains=True):
            raise AssertionError("Không giữ hoạt động: trình chỉnh sửa không đủ 2 trang")
        tap(desc="Xong")
        wait_activity("DocumentActivity", 30)
        time.sleep(2)
        shot("dka_document_batch")
        if not exists(text="1/2", timeout=5):
            raise AssertionError("Không giữ hoạt động: tài liệu hàng loạt không đủ 2 trang")
    finally:
        sh("settings put global always_finish_activities 0")
        go_home()
        # khởi động lại ứng dụng cho các bước sau
        sh("am force-stop %s" % PKG)
        time.sleep(1)
        sh("am start -W -n %s/.MainActivity" % PKG)
        time.sleep(2)


def t_rotation_and_recreate():
    # Buộc tạo lại Activity (giống khi hệ thống thu hồi bộ nhớ) để kiểm tra lưu/khôi phục trạng thái
    go_home()
    tap(desc="Quét tài liệu")
    wait_activity("CameraActivity", 12)
    time.sleep(3)
    sh("am force-stop %s" % PKG)
    time.sleep(1)
    sh("am start -W -n %s/.MainActivity" % PKG)
    time.sleep(3)
    wait_activity("MainActivity", 10)


def t_monkey():
    go_home()
    # chỉ thao tác chạm/vuốt: phím điều hướng/trackball làm máy ảo chờ cửa sổ có focus rất lâu khi chuyển màn hình
    out = sh("monkey -p %s --pct-syskeys 0 --pct-appswitch 0 --pct-anyevent 0 --pct-nav 0 --pct-majornav 0 "
             "--pct-trackball 0 --ignore-timeouts --throttle 100 -s 1234 -v 2500" % PKG, timeout=1200)
    open(os.path.join(OUT, "monkey.txt"), "w").write(out)
    bad = re.findall(r"// (CRASH|NOT RESPONDING): (\S+)", out)
    mine = [b for b in bad if b[1].startswith(PKG)]
    if bad:
        log("monkey: %s" % bad)
    if mine:
        raise AssertionError("Monkey phát hiện lỗi:\n" + out[-3000:])
    if "Monkey finished" not in out:
        tail = out.strip().splitlines()[-1][:120] if out.strip() else "trống"
        notes.append("Monkey chưa chạy hết (%s)" % tail)
    shot("after_monkey")


# ----------------------------------------------------------------------------- làm đẹp / chỉnh sửa trang

# thứ tự trong bảng lọc = Enhancer.MODES (ORIGINAL, LIGHTEN, MAGIC, MAGIC_PRO, FLATTEN, DENOISE, NO_SHADOW, NO_HAND, BW, GRAY)
FILTER_ORDER = ["Nguyên gốc", "Làm sáng", "Tăng cường", "Thần kỳ", "Làm phẳng", "Khử nhiễu", "Không bóng", "Ko viết tay", "Đen trắng", "Thang xám"]
F_MAGIC, F_GRAY, F_BW, F_PRO, F_DENOISE, F_NOSHADOW, F_NOHAND, F_FLATTEN = 1, 3, 4, 5, 6, 7, 8, 9
# Ảnh thử tests/images/edit_page.jpg (sinh bằng tests/tools/make_retouch_images.py): vị trí các chi tiết trên trang
# ĐÃ NẮN PHẲNG, chuẩn hoá theo bề ngang / bề cao trang: (u1, v1, u2, v2)
R_FINGER = (0.005, 0.42, 0.05, 0.485)        # đầu ngón tay giữ giấy ở mép trái
R_SIGNER = (0.15, 0.775, 0.30, 0.797)        # dòng chữ in "NGƯỜI LẬP"
R_STAMP = (0.65, 0.85, 0.81, 0.965)          # con dấu đỏ
R_SCRIBBLE = (0.14, 0.87, 0.42, 0.945)       # nét bút xanh góc dưới-trái
R_SCRIBBLE_TOP = (0.565, 0.237, 0.868, 0.273)  # nét bút xanh vắt qua đầu bảng
R_ROW9 = (0.18, 0.546, 0.31, 0.562)          # chữ "Bộ lưu điện" trong bảng
R_ROW9_LINE = (0.19, 0.533, 0.30, 0.544)     # đường kẻ ngang phía trên dòng đó
PAGE_RATIO = 970.0 / 1373.0
# Ảnh thử tests/images/wavy_page.jpg (tests/tools/make_wavy_images.py, trang wv_mixed "chụp" nghiêng trên mặt bàn): biểu mẫu
# giấy nhàu — bảng 10 dòng + hàng tiêu đề (12 đường kẻ ngang, 6 đường kẻ dọc) bị uốn lượn, nếp gấp ngang cắt qua giữa bảng,
# nếp gấp dọc giữa trang, chữ ký bút xanh, con dấu đỏ, hai vết vân tay. Vị trí trên trang ĐÃ NẮN PHẲNG theo 4 góc, chuẩn hoá
# (u1, v1, u2, v2):
R_WV_TABLE = (0.12, 0.235, 0.89, 0.625)      # bề ngang bảng (trong hai đường kẻ dọc ngoài cùng) x dải chứa các đường kẻ ngang
R_WV_CLEAN = (0.42, 0.70, 0.58, 0.95)        # giấy trắng giữa chữ ký và con dấu, nếp gấp dọc chạy qua
R_WV_STAMP = (0.62, 0.77, 0.80, 0.90)        # con dấu đỏ
R_WV_SIGN = (0.14, 0.81, 0.40, 0.88)         # chữ ký bút xanh
R_WV_TEXT = (0.185, 0.29, 0.50, 0.59)        # chữ trong cột "Tên thiết bị" (hàng 1–10)


def density():
    m = re.findall(r"(\d+)", sh("wm density"))
    return int(m[-1]) / 160.0 if m else 2.625


def swipe(p1, p2, ms=450):
    sh("input swipe %d %d %d %d %d" % (p1[0], p1[1], p2[0], p2[1], ms))
    time.sleep(0.5)


def hold(text=None, desc=None, ms=1200):
    n = find(text=text, desc=desc)
    if n is None:
        raise AssertionError("Không thấy phần tử %s" % (text if text is not None else desc))
    x, y = bounds(n)
    sh("input swipe %d %d %d %d %d" % (x, y, x, y, ms))


def editor_tool(name):
    """Chạm một công cụ trên dải công cụ cuộn ngang của trình chỉnh sửa; đổi thẻ (Ảnh / Đánh dấu / Trang) nếu nó đang khuất."""
    w, _ = screen_size()
    for tab in (None, "Ảnh", "Đánh dấu", "Trang", None):
        if tab is not None:
            n = find(text=tab)
            if n is None:
                continue
            tap_xy(*bounds(n))
            time.sleep(1.0)
        root = dump()
        n = find(text=name, root=root)
        if n is None:
            continue
        box = rect(parent_map(root).get(n)) or rect(n)
        ok = rect(find(desc="Xong", root=root))
        limit = ok[0] if ok else w
        cx = (box[0] + box[2]) // 2
        # nút bị che một phần (nằm ở mép dải cuộn) thì đổi thẻ để nó hiện trọn
        if box[2] - box[0] >= w * 0.12 and cx < limit - w * 0.02:
            tap_xy(cx, (box[1] + box[3]) // 2)
            time.sleep(0.6)
            return
    raise AssertionError("Không thấy công cụ '%s' trên thanh công cụ" % name)


def filter_pick(name):
    """Chọn một bộ lọc trong bảng lọc (hàng ảnh nhỏ cuộn ngang): vuốt hàng cho tới khi tên bộ lọc hiện trọn."""
    w, _ = screen_size()
    want = FILTER_ORDER.index(name)
    for _ in range(8):
        root = dump()
        seen = {}
        for i, nm in enumerate(FILTER_ORDER):
            n = find(text=nm, root=root)
            if n is not None:
                seen[i] = rect(n)
        if not seen:
            raise AssertionError("Bảng bộ lọc chưa mở (không thấy tên bộ lọc nào)")
        if want in seen:
            x1, y1, x2, y2 = seen[want]
            if x1 > w * 0.03 and x2 < w * 0.97:
                tap_xy((x1 + x2) // 2, (y1 + y2) // 2)
                time.sleep(0.6)
                return
        any_box = list(seen.values())[0]
        y = any_box[1] - int(30 * density())  # vuốt trên hàng ảnh nhỏ, phía trên dòng tên
        if want > max(seen) or (want in seen and seen[want][2] >= w * 0.97):
            sh("input swipe %d %d %d %d 500" % (int(w * 0.75), y, int(w * 0.35), y))
        else:
            sh("input swipe %d %d %d %d 500" % (int(w * 0.35), y, int(w * 0.75), y))
        time.sleep(1.0)
    raise AssertionError("Không cuộn tới được bộ lọc '%s'" % name)


def import_to_editor(names, wait=60):
    """Nhập ảnh thử thẳng vào trình chỉnh sửa (lối vào kiểm thử của bản debug). Trả về mã tài liệu nháp vừa tạo."""
    go_home()
    before = set(doc_ids())
    for n in names:
        push_test_image(os.path.join(IMAGES, n), n)
    uris = ",".join("content://%s.share/%s" % (PKG, n) for n in names)
    out = sh("am start -a %s.TEST_IMPORT --esa uris %s -n %s/.MainActivity" % (PKG, uris, PKG))
    if "Error" in out:
        raise AssertionError(out)
    # một ảnh: ứng dụng mở màn hình cắt trước (tự tìm mép) rồi mới vào trình chỉnh sửa; nhiều ảnh: vào thẳng
    deadline = time.time() + wait
    cur = ""
    while time.time() < deadline:
        cur = top_activity()
        if cur in ("EditorActivity", "CropActivity"):
            break
        time.sleep(0.7)
    if cur == "CropActivity":
        time.sleep(4.0)
        tap(text="Xong")
    wait_activity("EditorActivity", wait)
    new = [i for i in doc_ids() if i not in before]
    if len(new) != 1:
        raise AssertionError("Không xác định được tài liệu vừa nhập: %s" % new)
    return new[0]


def wait_doc(doc_id, pred, timeout=60, what=""):
    """Chờ tới khi doc.json thoả điều kiện (ứng dụng lưu sau mỗi thay đổi và sau mỗi lần xử lý xong một trang)."""
    deadline = time.time() + timeout
    d = None
    while time.time() < deadline:
        d = read_doc(doc_id)
        try:
            if d is not None and pred(d):
                return d
        except (KeyError, IndexError, TypeError):
            pass
        time.sleep(1.0)
    pages = d and [{k: v for k, v in pg.items() if k not in ("quad", "erase", "marks")} for pg in d.get("pages", [])]
    raise AssertionError("Hết thời gian chờ %s: %s" % (what or "tài liệu", pages))


def wait_page(doc_id, pred, timeout=60, what="", index=0):
    d = wait_doc(doc_id, lambda d: pred(d["pages"][index]), timeout, what)
    return d["pages"][index]


def page_image(doc_id, index=0):
    """Ảnh kết quả hiện tại của một trang (PIL) hoặc None nếu máy chạy kiểm thử thiếu Pillow."""
    try:
        import io
        from PIL import Image
    except ImportError:
        if "Thiếu Pillow: bỏ qua các kiểm tra nội dung ảnh" not in notes:
            notes.append("Thiếu Pillow: bỏ qua các kiểm tra nội dung ảnh")
        return None
    d = read_doc(doc_id)
    pg = d["pages"][index]
    data = pull_file("files/docs/%s/%s" % (doc_id, pg["out"]))
    im = Image.open(io.BytesIO(data)).convert("RGB")
    im.load()
    return im


def rgb_pixels(im):
    """Các điểm ảnh (r, g, b) của ảnh RGB, lần lượt từng hàng từ trên xuống. Pillow 12.1+ có get_flattened_data
    (getdata bị đánh dấu bỏ, gỡ hẳn ở Pillow 14 — CI cài Pillow mới nhất); bản cũ hơn dùng getdata."""
    flat = getattr(im, "get_flattened_data", None)
    return list(flat() if flat is not None else im.getdata())


def frac(im, box, kind):
    """Tỷ lệ điểm ảnh thuộc một loại (dark / red / blue / skin / nonwhite) trong vùng chuẩn hoá box của ảnh trang."""
    W, H = im.size
    crop = im.crop((int(box[0] * W), int(box[1] * H), max(int(box[0] * W) + 1, int(box[2] * W)), max(int(box[1] * H) + 1, int(box[3] * H))))
    px = rgb_pixels(crop)
    if kind == "dark":
        c = sum(1 for r, g, b in px if r * 299 + g * 587 + b * 114 < 128000)
    elif kind == "red":
        c = sum(1 for r, g, b in px if r > 140 and g < 115 and b < 115)
    elif kind == "blue":
        c = sum(1 for r, g, b in px if b > r + 40 and b > 90)
    elif kind == "skin":
        c = sum(1 for r, g, b in px if r > 120 and r - b > 45)
    elif kind == "nonwhite":
        # không còn trắng hẳn (độ sáng < 200): vệt nếp gấp, bóng mờ, vết bẩn còn sót trên giấy trắng
        c = sum(1 for r, g, b in px if r * 299 + g * 587 + b * 114 < 200000)
    else:
        raise ValueError(kind)
    return c / float(max(1, len(px)))


def dark_rows(im, box):
    """Tỷ lệ điểm tối (như frac 'dark') của từng hàng điểm ảnh trong vùng chuẩn hoá box, từ trên xuống."""
    W, H = im.size
    x0, y0 = int(box[0] * W), int(box[1] * H)
    x1, y1 = max(x0 + 1, int(box[2] * W)), max(y0 + 1, int(box[3] * H))
    w = x1 - x0
    px = rgb_pixels(im.crop((x0, y0, x1, y1)))
    return [sum(1 for r, g, b in px[i:i + w] if r * 299 + g * 587 + b * 114 < 128000) / float(w) for i in range(0, len(px), w)]


def straight_rows(im, box, cover=0.6):
    """Số hàng điểm ảnh tối trên >= cover bề ngang vùng: đường kẻ ngang thẳng cho vài hàng như vậy, đường kẻ uốn lượn
    thì không (mỗi hàng chỉ cắt qua một đoạn ngắn của nó)."""
    return sum(1 for c in dark_rows(im, box) if c >= cover)


def text_dark(im, box, rule=0.5):
    """Tỷ lệ điểm tối của chữ trong vùng, bỏ các hàng là đường kẻ ngang (tối trên >= rule bề ngang vùng)."""
    rows = [c for c in dark_rows(im, box) if c < rule]
    return sum(rows) / float(max(1, len(rows)))


def save_page(im, name):
    """Lưu ảnh kết quả của trang (đã kéo về) cạnh ảnh chụp màn hình để xem lại."""
    shot_no[0] += 1
    try:
        im.save(os.path.join(OUT, "screens", "%02d_%s.jpg" % (shot_no[0], name)), quality=85)
    except Exception as e:
        log("không lưu được ảnh trang", e)


def page_mapper(view_desc):
    """Hàm đổi toạ độ chuẩn hoá trên trang (u, v) -> điểm màn hình, cho màn hình Xóa thông minh / Đánh dấu (trang chưa xoay)."""
    n = None
    for _ in range(8):
        n = find(desc=view_desc)
        if n is not None:
            break
        time.sleep(0.8)
    if n is None:
        raise AssertionError("Không thấy vùng '%s'" % view_desc)
    x1, y1, x2, y2 = rect(n)
    pad = 12 * density()
    aw, ah = (x2 - x1) - 2 * pad, (y2 - y1) - 2 * pad
    if aw / PAGE_RATIO <= ah:
        dw, dh = aw, aw / PAGE_RATIO
    else:
        dw, dh = ah * PAGE_RATIO, ah
    left, top = (x1 + x2) / 2.0 - dw / 2.0, (y1 + y2) / 2.0 - dh / 2.0
    return lambda u, v: (int(round(left + u * dw)), int(round(top + v * dh)))


def recreate_by_font_scale():
    """Đổi cỡ chữ hệ thống rồi trả lại: màn hình đang mở bị tạo lại hai lần, trạng thái phải còn nguyên."""
    sh("settings put system font_scale 1.15")
    time.sleep(2.5)
    sh("settings put system font_scale 1.0")
    time.sleep(3.0)


def apply_edit_screen(activity):
    """Bấm ✓ ở màn hình Xóa thông minh / Đánh dấu; nếu còn đang xử lý (nút chưa nhận) thì thử lại."""
    for _ in range(6):
        tap(desc="Áp dụng")
        time.sleep(1.5)
        if top_activity() != activity:
            break
        time.sleep(2.0)
    wait_activity("EditorActivity", 15)


def t_filters_new():
    """Lời nhắc "Phát hiện ngón tay" -> Gỡ bỏ (bộ lọc Thần kỳ); các bộ lọc mới; ✕ trả lại bộ lọc cũ, ✓ giữ."""
    doc_id = import_to_editor(["edit_page.jpg"])
    pg = wait_page(doc_id, lambda p: p.get("out") and p.get("finger"), 90, "xử lý trang + dò ngón tay")
    if pg.get("finger") != 1:
        raise AssertionError("Không phát hiện ngón tay trên ảnh thử (finger=%s)" % pg.get("finger"))
    if not exists(text="Phát hiện ngón tay", contains=True, timeout=8):
        raise AssertionError("Không hiện lời nhắc 'Phát hiện ngón tay'")
    time.sleep(1.5)
    shot("finger_banner")
    im0 = page_image(doc_id)
    tap(text="Gỡ bỏ")
    pg = wait_page(doc_id, lambda p: p.get("filter") == F_PRO and p.get("out") != pg["out"], 90, "bộ lọc Thần kỳ sau khi bấm Gỡ bỏ")
    time.sleep(2.0)
    shot("finger_removed")
    if exists(text="Phát hiện ngón tay", contains=True, timeout=1.5):
        raise AssertionError("Lời nhắc ngón tay vẫn còn sau khi đã gỡ")
    im1 = page_image(doc_id)
    if im0 is not None and im1 is not None:
        a, b = frac(im0, R_FINGER, "skin"), frac(im1, R_FINGER, "skin")
        log("ngón tay (tỷ lệ màu da ở mép trái) trước/sau Thần kỳ: %.3f -> %.3f" % (a, b))
        if a < 0.3:
            raise AssertionError("Ảnh thử không có ngón tay ở vị trí mong đợi (%.3f)" % a)
        if b > 0.02:
            raise AssertionError("Bộ lọc Thần kỳ chưa gỡ hết ngón tay (%.3f)" % b)
        d0, d1 = frac(im0, R_SIGNER, "dark"), frac(im1, R_SIGNER, "dark")
        if d1 < d0 * 0.5:
            raise AssertionError("Bộ lọc Thần kỳ làm mất chữ in (%.3f -> %.3f)" % (d0, d1))
    editor_tool("Bộ lọc")
    time.sleep(2.5)
    shot("filter_panel_new")
    blue0 = frac(im0, R_SCRIBBLE, "blue") if im0 is not None else None
    for name, code in (("Khử nhiễu", F_DENOISE), ("Không bóng", F_NOSHADOW), ("Ko viết tay", F_NOHAND)):
        prev = pg["out"]
        filter_pick(name)
        pg = wait_page(doc_id, lambda p: p.get("filter") == code and p.get("out") not in (None, prev), 90, "bộ lọc " + name)
        time.sleep(2.0)
        shot("filter_" + re.sub(r"\W+", "_", name))
    im2 = page_image(doc_id)
    if im2 is not None:
        blue2 = frac(im2, R_SCRIBBLE, "blue")
        red2 = frac(im2, R_STAMP, "red")
        log("Ko viết tay: nét bút xanh %.4f -> %.4f, con dấu đỏ còn %.3f" % (blue0, blue2, red2))
        if blue0 < 0.01:
            raise AssertionError("Ảnh thử không có nét bút xanh ở vị trí mong đợi (%.4f)" % blue0)
        if blue2 > blue0 * 0.15:
            raise AssertionError("Bộ lọc 'Ko viết tay' chưa xoá nét bút xanh (%.4f -> %.4f)" % (blue0, blue2))
        if red2 < 0.03:
            raise AssertionError("Bộ lọc 'Ko viết tay' xoá mất con dấu (%.3f)" % red2)
    tap(desc="Đóng bộ lọc")
    time.sleep(1.0)
    # bộ lọc không còn là Thần kỳ -> lời nhắc hiện lại; bấm ✕ thì tắt hẳn cho trang này
    if not exists(text="Phát hiện ngón tay", contains=True, timeout=5):
        raise AssertionError("Lời nhắc ngón tay không hiện lại khi bỏ bộ lọc Thần kỳ")
    tap(desc="Đóng lời nhắc")
    wait_page(doc_id, lambda p: p.get("fingerOff") is True, 10, "tắt lời nhắc ngón tay")
    if exists(text="Phát hiện ngón tay", contains=True, timeout=1.5):
        raise AssertionError("Lời nhắc ngón tay không tắt")
    # ✕ ở bảng lọc: trả lại bộ lọc lúc mở bảng
    editor_tool("Bộ lọc")
    time.sleep(1.5)
    prev = pg["out"]
    filter_pick("Đen trắng")
    wait_page(doc_id, lambda p: p.get("filter") == F_BW and p.get("out") not in (None, prev), 90, "bộ lọc Đen trắng")
    tap(desc="Hủy bộ lọc")
    wait_page(doc_id, lambda p: p.get("filter") == F_NOHAND and p.get("out") == prev, 60, "trả lại bộ lọc sau khi bấm ✕")
    time.sleep(1.0)
    tap(desc="Xong")
    wait_activity("DocumentActivity", 40)
    time.sleep(2.0)
    shot("filters_document")
    d = read_doc(doc_id)
    if d is None or d.get("draft") or d["pages"][0].get("filter") != F_NOHAND:
        raise AssertionError("Tài liệu lưu sai bộ lọc: %s" % (d and d["pages"][0].get("filter")))


def t_flatten():
    """Bộ lọc "Làm phẳng" trên ảnh chụp biểu mẫu giấy nhàu: so với bộ lọc mặc định (Tăng cường), đường kẻ bảng được nắn
    thẳng, nếp gấp trên giấy trắng bị xoá, chữ trong bảng + con dấu đỏ + chữ ký xanh vẫn còn; tài liệu lưu đúng bộ lọc."""
    doc_id = import_to_editor(["wavy_page.jpg"])
    pg = wait_page(doc_id, lambda p: p.get("out") and p.get("finger"), 90, "xử lý trang (bộ lọc mặc định)")
    time.sleep(1.5)
    shot("flatten_default")
    editor_tool("Bộ lọc")
    time.sleep(2.5)
    shot("flatten_panel")
    if pg.get("filter") != F_MAGIC:
        # ảnh đối chứng luôn là bộ lọc Tăng cường (mặc định hiện nay của trang mới)
        log("bộ lọc mặc định của trang mới: %s -> chọn Tăng cường làm ảnh đối chứng" % pg.get("filter"))
        prev = pg["out"]
        filter_pick("Tăng cường")
        pg = wait_page(doc_id, lambda p: p.get("filter") == F_MAGIC and p.get("out") not in (None, prev), 90, "bộ lọc Tăng cường")
    im1 = page_image(doc_id)
    prev = pg["out"]
    filter_pick("Làm phẳng")
    pg = wait_page(doc_id, lambda p: p.get("filter") == F_FLATTEN and p.get("out") not in (None, prev), 120, "bộ lọc Làm phẳng")
    time.sleep(2.0)
    shot("filter_flatten")
    im9 = page_image(doc_id)
    if im1 is not None and im9 is not None:
        save_page(im1, "flatten_out_default")
        save_page(im9, "flatten_out_flatten")
        rows1, rows9 = straight_rows(im1, R_WV_TABLE), straight_rows(im9, R_WV_TABLE)
        fold1, fold9 = frac(im1, R_WV_CLEAN, "nonwhite"), frac(im9, R_WV_CLEAN, "nonwhite")
        red1, red9 = frac(im1, R_WV_STAMP, "red"), frac(im9, R_WV_STAMP, "red")
        blue1, blue9 = frac(im1, R_WV_SIGN, "blue"), frac(im9, R_WV_SIGN, "blue")
        text1, text9 = text_dark(im1, R_WV_TEXT), text_dark(im9, R_WV_TEXT)
        log("Tăng cường -> Làm phẳng: hàng kẻ thẳng %d -> %d, nếp gấp trên giấy trắng %.4f -> %.4f, con dấu đỏ %.3f -> %.3f, "
            "chữ ký xanh %.4f -> %.4f, chữ trong bảng %.4f -> %.4f" % (rows1, rows9, fold1, fold9, red1, red9, blue1, blue9, text1, text9))
        # ảnh thử phải đúng như mong đợi: bảng uốn lượn, có vệt nếp gấp, con dấu, chữ ký, chữ ở các vùng đo
        if rows1 > 3 or fold1 < 0.006 or red1 < 0.08 or blue1 < 0.02 or text1 < 0.03:
            raise AssertionError("Ảnh thử không có bảng uốn lượn / nếp gấp / con dấu / chữ ký / chữ ở vị trí mong đợi "
                                 "(Tăng cường: %d hàng kẻ thẳng, nếp gấp %.4f, đỏ %.3f, xanh %.4f, chữ %.4f)" % (rows1, fold1, red1, blue1, text1))
        # (a) thẳng: đường kẻ ngang của bảng nằm trên một hàng điểm ảnh (Tăng cường: 0 hàng; Làm phẳng trên máy tính: ~25)
        if rows9 < 7 or rows9 <= rows1:
            raise AssertionError("Làm phẳng chưa nắn thẳng đường kẻ bảng (%d hàng kẻ thẳng, Tăng cường %d)" % (rows9, rows1))
        # (b) sạch: vệt nếp gấp dọc trên giấy trắng bị xoá (Tăng cường ~0,019; Làm phẳng ~0)
        if fold9 > 0.003:
            raise AssertionError("Làm phẳng chưa xoá vệt nếp gấp trên giấy trắng (%.4f, Tăng cường %.4f)" % (fold9, fold1))
        # (c) giữ nội dung: con dấu đỏ, chữ ký xanh, chữ trong bảng (bỏ các hàng là đường kẻ; chữ bị xoá hết thì còn ~0,004)
        if red9 < red1 * 0.5:
            raise AssertionError("Làm phẳng làm mất con dấu đỏ (%.3f -> %.3f)" % (red1, red9))
        if blue9 < blue1 * 0.4:
            raise AssertionError("Làm phẳng làm mất chữ ký bút xanh (%.4f -> %.4f)" % (blue1, blue9))
        if text9 < 0.02:
            raise AssertionError("Làm phẳng làm mất chữ trong bảng (%.4f -> %.4f)" % (text1, text9))
    tap(desc="Đóng bộ lọc")
    time.sleep(1.0)
    tap(desc="Xong")
    wait_activity("DocumentActivity", 40)
    time.sleep(2.0)
    shot("flatten_document")
    d = read_doc(doc_id)
    if d is None or d.get("draft") or d["pages"][0].get("filter") != F_FLATTEN:
        raise AssertionError("Tài liệu lưu sai bộ lọc: %s" % (d and d["pages"][0].get("filter")))


def screen_image():
    """Ảnh chụp màn hình hiện tại (PIL) hoặc None nếu máy chạy kiểm thử thiếu Pillow."""
    try:
        import io
        from PIL import Image
    except ImportError:
        return None
    data = subprocess.run(["adb", "exec-out", "screencap", "-p"], stdout=subprocess.PIPE, timeout=30).stdout
    im = Image.open(io.BytesIO(data)).convert("RGB")
    im.load()
    return im


def screen_page(view_desc, timeout=60):
    """Vùng trang trên ảnh chụp màn hình Xóa thông minh / Đánh dấu (trang chưa xoay, chưa phóng to): chờ tới khi ảnh trang
    đã hiện (giấy sáng có nét tối — lúc đang tải vùng này chỉ có nền tối của màn hình + vòng xoay, tối gần hết) và ổn định
    qua hai lần chụp. None nếu thiếu Pillow."""
    P = page_mapper(view_desc)
    (x1, y1), (x2, y2) = P(0.0, 0.0), P(1.0, 1.0)
    deadline = time.time() + timeout
    last = None
    while time.time() < deadline:
        im = screen_image()
        if im is None:
            return None
        crop = im.crop((x1, y1, x2, y2))
        dark = frac(crop, (0.1, 0.2, 0.9, 0.7), "dark")
        ok = 0.01 < dark < 0.5
        if ok and last is not None and abs(dark - last) < 0.002:
            return crop
        last = dark if ok else None
        time.sleep(2.0)
    raise AssertionError("Ảnh trang không hiện (hoặc không ổn định) ở '%s'" % view_desc)


def rule_rows(im, box, cover=0.6):
    """Toạ độ dọc chuẩn hoá (theo chiều cao ảnh) của tâm từng đường kẻ ngang thẳng trong vùng: nhóm các hàng điểm ảnh liền
    nhau tối trên >= cover bề ngang vùng."""
    H = im.size[1]
    y0 = int(box[1] * H)
    groups = []
    for i, c in enumerate(dark_rows(im, box)):
        if c < cover:
            continue
        if groups and i - groups[-1][-1] <= 1:
            groups[-1].append(i)
        else:
            groups.append([i])
    return [(y0 + (g[0] + g[-1]) / 2.0 + 0.5) / H for g in groups]


def red_center(im, box):
    """(toạ độ dọc chuẩn hoá trung bình, số điểm) của các điểm đỏ trong vùng; (None, 0) nếu không có."""
    W, H = im.size
    x0, y0 = int(box[0] * W), int(box[1] * H)
    x1, y1 = max(x0 + 1, int(box[2] * W)), max(y0 + 1, int(box[3] * H))
    w = x1 - x0
    n, s = 0, 0.0
    for i, (r, g, b) in enumerate(rgb_pixels(im.crop((x0, y0, x1, y1)))):
        if r > 140 and g < 115 and b < 115:
            n += 1
            s += i // w
    return ((y0 + s / n + 0.5) / H, n) if n else (None, 0)


def t_flatten_edit():
    """Xóa thông minh + Đánh dấu trên trang dùng bộ lọc "Làm phẳng". Nét xoá áp lên ảnh TRƯỚC khi lọc nên ảnh xem trước ở
    Xóa thông minh đã làm sạch nhưng CHƯA nắn; đánh dấu vẽ SAU bộ lọc nên ảnh xem trước ở Đánh dấu đã nắn thẳng giống hệt
    ảnh kết quả. Xoá con dấu bằng Hình chữ nhật; nét brush vẽ dọc một đường kẻ bảng (thẳng) nằm đúng trên đường kẻ đó ở
    ảnh kết quả."""
    doc_id = import_to_editor(["wavy_page.jpg"])
    pg = wait_page(doc_id, lambda p: p.get("out") and p.get("finger"), 90, "xử lý trang")
    editor_tool("Bộ lọc")
    time.sleep(2.5)
    prev = pg["out"]
    filter_pick("Làm phẳng")
    pg = wait_page(doc_id, lambda p: p.get("filter") == F_FLATTEN and p.get("out") not in (None, prev), 120, "bộ lọc Làm phẳng")
    time.sleep(1.0)
    tap(desc="Đóng bộ lọc")
    time.sleep(1.0)
    # --- Xóa thông minh: xem trước đã hết nếp gấp, đường kẻ bảng vẫn uốn lượn (chưa nắn)
    editor_tool("Xóa thông minh")
    wait_activity("EraseActivity", 15)
    sc = screen_page("Trang đang xóa")
    shot("flatten_erase_open")
    if sc is not None:
        rows, fold = straight_rows(sc, R_WV_TABLE), frac(sc, R_WV_CLEAN, "nonwhite")
        log("xem trước Xóa thông minh (Làm phẳng): hàng kẻ thẳng %d, nếp gấp trên giấy trắng %.4f" % (rows, fold))
        if rows > 3:
            raise AssertionError("Ảnh xem trước Xóa thông minh của trang Làm phẳng đã bị nắn (%d hàng kẻ thẳng): nét xoá áp lên "
                                 "ảnh TRƯỚC khi lọc nên ảnh xem trước phải chưa nắn" % rows)
        if fold > 0.004:
            raise AssertionError("Ảnh xem trước Xóa thông minh chưa qua bước làm sạch của Làm phẳng (nếp gấp %.4f)" % fold)
    P = page_mapper("Trang đang xóa")
    tap(text="Hình chữ nhật")
    swipe(P(0.60, 0.75), P(0.83, 0.93))
    time.sleep(3.0)
    apply_edit_screen("EraseActivity")
    pg = wait_page(doc_id, lambda p: p.get("erase") and p.get("out") not in (None, pg["out"]), 120, "dựng lại trang sau Xóa thông minh")
    time.sleep(1.5)
    im = page_image(doc_id)
    y_rule = None
    if im is not None:
        save_page(im, "flatten_erased")
        red, blue, rows = frac(im, R_WV_STAMP, "red"), frac(im, R_WV_SIGN, "blue"), straight_rows(im, R_WV_TABLE)
        log("sau xoá con dấu (Làm phẳng): đỏ %.4f, chữ ký xanh %.4f, hàng kẻ thẳng %d" % (red, blue, rows))
        if red > 0.003:
            raise AssertionError("Chưa xoá được con dấu trên trang Làm phẳng (%.4f)" % red)
        if blue < 0.02 or rows < 7:
            raise AssertionError("Xoá con dấu làm mất chữ ký / trang không còn được nắn (xanh %.4f, %d hàng kẻ thẳng)" % (blue, rows))
        rules = rule_rows(im, R_WV_TABLE)
        if not rules:
            raise AssertionError("Không thấy đường kẻ ngang thẳng nào trên ảnh kết quả Làm phẳng")
        mid = (R_WV_TABLE[1] + R_WV_TABLE[3]) / 2.0
        y_rule = min(rules, key=lambda y: abs(y - mid))
    # --- Đánh dấu: xem trước đã nắn thẳng như ảnh kết quả; nét brush dọc đường kẻ nằm trên đường kẻ ở ảnh kết quả
    editor_tool("Brush")
    wait_activity("MarkupActivity", 15)
    sc = screen_page("Trang đang đánh dấu", 90)
    shot("flatten_markup_open")
    if sc is not None and y_rule is not None:
        rows = straight_rows(sc, R_WV_TABLE)
        rs = rule_rows(sc, R_WV_TABLE)
        y_sc = min(rs, key=lambda y: abs(y - y_rule)) if rs else -1.0
        log("xem trước Đánh dấu (Làm phẳng): hàng kẻ thẳng %d, đường kẻ giữa bảng %.4f (ảnh kết quả %.4f)" % (rows, y_sc, y_rule))
        if rows < 7:
            raise AssertionError("Ảnh xem trước Đánh dấu của trang Làm phẳng chưa nắn thẳng (%d hàng kẻ thẳng)" % rows)
        if abs(y_sc - y_rule) > 0.004:
            raise AssertionError("Đường kẻ bảng ở ảnh xem trước Đánh dấu lệch so với ảnh kết quả (%.4f / %.4f): nét đánh dấu "
                                 "sẽ không nằm đúng chỗ" % (y_sc, y_rule))
    P = page_mapper("Trang đang đánh dấu")
    tap(desc="Màu đỏ")
    v = y_rule if y_rule is not None else 0.43
    swipe(P(0.25, v), P(0.75, v), 600)
    time.sleep(1.0)
    shot("flatten_markup_brush")
    apply_edit_screen("MarkupActivity")
    pg = wait_page(doc_id, lambda p: p.get("marks") and p.get("out") not in (None, pg["out"]), 120, "dựng lại trang sau Đánh dấu")
    time.sleep(1.5)
    im = page_image(doc_id)
    if im is not None and y_rule is not None:
        save_page(im, "flatten_marked")
        yc, n = red_center(im, (0.30, y_rule - 0.03, 0.70, y_rule + 0.03))
        log("nét brush đỏ trên ảnh kết quả: tâm %s (%d điểm), đường kẻ %.4f" % (yc, n, y_rule))
        if yc is None or abs(yc - y_rule) > 0.004:
            raise AssertionError("Nét brush vẽ dọc đường kẻ bảng không nằm trên đường kẻ ở ảnh kết quả (%s / %.4f)" % (yc, y_rule))
    tap(desc="Xong")
    wait_activity("DocumentActivity", 40)
    time.sleep(1.5)
    d = read_doc(doc_id)
    p0 = d and d["pages"][0]
    if not p0 or p0.get("filter") != F_FLATTEN or len(p0.get("erase", [])) != 1 or len(p0.get("marks", [])) != 1:
        raise AssertionError("Tài liệu lưu sai: %s" % (p0 and {k: v for k, v in p0.items() if k not in ("quad",)}))


def t_smart_erase():
    """Xóa thông minh: Bút, Hình chữ nhật, Thần kỳ, Viết tay, Xóa văn bản; hoàn tác / làm lại; giữ trạng thái khi màn hình bị tạo lại."""
    doc_id = import_to_editor(["edit_page.jpg"])
    pg = wait_page(doc_id, lambda p: p.get("out") and p.get("finger"), 90, "xử lý trang")
    im0 = page_image(doc_id)
    editor_tool("Xóa thông minh")
    wait_activity("EraseActivity", 15)
    time.sleep(5.0)
    P = page_mapper("Trang đang xóa")
    shot("erase_open")
    # Bút: tô lên dòng "NGƯỜI LẬP"
    swipe(P(0.11, 0.786), P(0.34, 0.786))
    time.sleep(3.0)
    # Hình chữ nhật quanh con dấu
    tap(text="Hình chữ nhật")
    swipe(P(0.60, 0.838), P(0.86, 0.985))
    time.sleep(3.0)
    shot("erase_pen_rect")
    # Thần kỳ: tô sơ qua một đoạn nét bút xanh -> xoá cả nét
    tap(text="Thần kỳ")
    swipe(P(0.16, 0.885), P(0.24, 0.93))
    time.sleep(4.0)
    shot("erase_magic")
    # Xóa văn bản: tô lên chữ trong bảng, đường kẻ phải còn
    tap(text="Xóa văn bản")
    swipe(P(0.17, 0.554), P(0.33, 0.554))
    time.sleep(4.0)
    # Viết tay: một chạm xoá nét bút màu còn lại trên cả trang
    tap(text="Viết tay")
    time.sleep(5.0)
    shot("erase_hand_text")
    # hoàn tác rồi làm lại bước cuối
    tap(desc="Hoàn tác")
    time.sleep(3.0)
    shot("erase_undo")
    tap(desc="Làm lại")
    time.sleep(3.0)
    hold(text="So sánh")
    time.sleep(0.5)
    recreate_by_font_scale()
    wait_activity("EraseActivity", 10)
    time.sleep(5.0)
    shot("erase_recreated")
    apply_edit_screen("EraseActivity")
    pg = wait_page(doc_id, lambda p: p.get("erase") and p.get("out") not in (None, pg["out"]), 120, "dựng lại trang sau Xóa thông minh")
    time.sleep(2.0)
    shot("erase_applied")
    kinds = [o.get("k") for o in pg["erase"]]
    log("thao tác xoá đã lưu:", kinds)
    if kinds != [0, 1, 2, 3, 4]:
        raise AssertionError("Danh sách thao tác xoá sai (cần [0, 1, 2, 3, 4]): %s" % kinds)
    im1 = page_image(doc_id)
    if im0 is not None and im1 is not None:
        checks = [("chữ 'NGƯỜI LẬP' (Bút)", R_SIGNER, "dark", 0.03, 0.01),
                  ("con dấu (Hình chữ nhật)", R_STAMP, "red", 0.03, 0.003),
                  ("nét bút xanh dưới (Thần kỳ)", R_SCRIBBLE, "blue", 0.01, 0.002),
                  ("chữ trong bảng (Xóa văn bản)", R_ROW9, "dark", 0.05, 0.02),
                  ("nét bút xanh trên (Viết tay)", R_SCRIBBLE_TOP, "blue", 0.01, 0.003)]
        for label, box, kind, need_before, max_after in checks:
            a, b = frac(im0, box, kind), frac(im1, box, kind)
            log("xoá %s: %.4f -> %.4f" % (label, a, b))
            if a < need_before:
                raise AssertionError("Ảnh thử thiếu %s ở vị trí mong đợi (%.4f)" % (label, a))
            if b > max_after:
                raise AssertionError("Chưa xoá được %s (%.4f -> %.4f)" % (label, a, b))
        line = frac(im1, R_ROW9_LINE, "dark")
        log("đường kẻ bảng sau Xóa văn bản: %.3f" % line)
        if line < 0.06:
            raise AssertionError("Xóa văn bản làm mất đường kẻ bảng (%.3f)" % line)
    # mở lại: các thao tác cũ được nạp; ✕ thì không đổi gì
    editor_tool("Xóa thông minh")
    wait_activity("EraseActivity", 15)
    time.sleep(4.0)
    tap(desc="Hoàn tác")
    time.sleep(2.0)
    tap(desc="Hủy")
    wait_activity("EditorActivity", 10)
    time.sleep(1.5)
    again = read_doc(doc_id)["pages"][0]
    if [o.get("k") for o in again.get("erase", [])] != kinds or again.get("out") != pg["out"]:
        raise AssertionError("Bấm ✕ ở Xóa thông minh vẫn làm đổi trang")
    tap(desc="Xong")
    wait_activity("DocumentActivity", 40)
    time.sleep(2.0)
    shot("erase_document")


def t_markup():
    """Đánh dấu: Brush, Khảm, Thêm văn bản, Thêm hình mờ, Ký tên (bảng ký, kéo chữ ký); hoàn tác; tạo lại màn hình; cắt lại + xoay sau khi đánh dấu."""
    doc_id = import_to_editor(["edit_page.jpg"])
    pg = wait_page(doc_id, lambda p: p.get("out") and p.get("finger"), 90, "xử lý trang")
    editor_tool("Brush")
    wait_activity("MarkupActivity", 15)
    time.sleep(5.0)
    P = page_mapper("Trang đang đánh dấu")
    swipe(P(0.2, 0.03), P(0.6, 0.03))
    tap(desc="Màu đỏ")
    tap(desc="Nét lớn")
    swipe(P(0.2, 0.108), P(0.6, 0.108))
    time.sleep(0.5)
    shot("markup_brush")
    tap(text="Khảm")
    time.sleep(0.5)
    swipe(P(0.2, 0.45), P(0.7, 0.45))
    time.sleep(1.0)
    shot("markup_mosaic")
    tap(text="Thêm văn bản")
    time.sleep(1.5)
    if not dialog_open():
        tap(text="Thêm chữ", contains=True)
        time.sleep(1.2)
    sh("input text 'Da_kiem_tra'")
    time.sleep(0.5)
    tap(text="OK")
    time.sleep(1.5)
    shot("markup_text")
    if "mInputShown=true" in sh("dumpsys input_method | grep mInputShown"):
        raise AssertionError("Bàn phím không ẩn sau khi đóng hộp thoại nhập chữ (che thanh công cụ)")
    tap(text="Thêm hình mờ")
    time.sleep(1.5)
    if not dialog_open():
        tap(text="Thêm hình mờ", contains=True)
        time.sleep(1.2)
    sh("input text 'MAT'")
    time.sleep(0.5)
    tap(text="OK")
    time.sleep(1.5)
    shot("markup_watermark")
    tap(text="Ký tên")
    time.sleep(1.5)
    if not exists(text="Ký vào khung bên dưới", timeout=3):
        tap(text="Chữ ký mới", contains=True)
        time.sleep(1.0)
    x1, y1, x2, y2 = rect(find(desc="Khung ký tên"))

    def Q(u, v):
        return int(x1 + u * (x2 - x1)), int(y1 + v * (y2 - y1))

    tap(desc="Mực xanh")
    swipe(Q(0.15, 0.6), Q(0.4, 0.3), 300)
    swipe(Q(0.4, 0.3), Q(0.6, 0.65), 300)
    swipe(Q(0.6, 0.65), Q(0.85, 0.35), 300)
    time.sleep(0.5)
    shot("markup_sign_pad")
    tap(text="Lưu chữ ký")
    time.sleep(2.0)
    if exists(text="Ký vào khung bên dưới", timeout=1.5):
        raise AssertionError("Bảng ký không đóng sau khi lưu chữ ký")
    shot("markup_sign_placed")
    # kéo chữ ký vừa đặt (đang ở giữa trang, hơi thấp) xuống góc dưới-phải
    swipe(P(0.5, 0.6), P(0.72, 0.9), 700)
    time.sleep(0.8)
    shot("markup_sign_moved")
    tap(desc="Hoàn tác")
    time.sleep(0.8)
    tap(desc="Làm lại")
    time.sleep(0.8)
    recreate_by_font_scale()
    wait_activity("MarkupActivity", 10)
    time.sleep(5.0)
    shot("markup_recreated")
    apply_edit_screen("MarkupActivity")
    pg = wait_page(doc_id, lambda p: p.get("marks") and p.get("out") not in (None, pg["out"]), 120, "dựng lại trang sau Đánh dấu")
    time.sleep(2.0)
    shot("markup_applied")
    marks = pg["marks"]
    types = [m.get("t") for m in marks]
    log("đánh dấu đã lưu:", types)
    if types != [0, 0, 1, 2, 4, 3]:
        raise AssertionError("Danh sách đánh dấu sai (cần [0, 0, 1, 2, 4, 3]): %s" % types)
    if marks[3].get("s") != "Da_kiem_tra" or marks[4].get("s") != "MAT":
        raise AssertionError("Nội dung chữ / hình mờ sai: %r, %r" % (marks[3].get("s"), marks[4].get("s")))
    sg = marks[5]
    if not str(sg.get("f", "")).startswith("sg_") or app_file_size("files/docs/%s/%s" % (doc_id, sg.get("f"))) <= 0:
        raise AssertionError("Thiếu file chữ ký của trang: %s" % sg.get("f"))
    sx, sy = sg.get("x", 0) / 100000.0, sg.get("y", 0) / 100000.0
    log("vị trí chữ ký sau khi kéo: %.3f, %.3f" % (sx, sy))
    if abs(sx - 0.72) > 0.06 or abs(sy - 0.9) > 0.06:
        raise AssertionError("Kéo chữ ký không tới vị trí mong đợi: (%.3f, %.3f)" % (sx, sy))
    if "s" not in run_as("ls files/signs"):
        raise AssertionError("Chữ ký không được lưu vào thư viện")
    im = page_image(doc_id)
    if im is not None:
        red = frac(im, (0.22, 0.10, 0.58, 0.116), "red")
        log("nét brush đỏ trên ảnh kết quả: %.3f" % red)
        if red < 0.5:
            raise AssertionError("Ảnh kết quả không có nét brush đỏ (%.3f)" % red)
    # cắt lại sau khi đánh dấu: các đánh dấu đi theo vùng cắt mới (không mất, không lỗi)
    editor_tool("Cắt")
    wait_activity("CropActivity", 15)
    time.sleep(2.5)
    tap(text="Tự động cắt")  # tắt tự động cắt -> khung cắt phủ toàn ảnh (khác hẳn vùng cắt cũ)
    time.sleep(1.5)
    shot("markup_recrop_full")
    tap(text="Xong")
    wait_activity("EditorActivity", 15)
    pg2 = wait_page(doc_id, lambda p: p.get("out") not in (None, pg["out"]), 120, "dựng lại trang sau khi cắt lại")
    if [m.get("t") for m in pg2.get("marks", [])] != types:
        raise AssertionError("Cắt lại làm mất đánh dấu: %s" % [m.get("t") for m in pg2.get("marks", [])])
    time.sleep(2.0)
    shot("markup_recrop")
    tap(text="Bên trái")
    pg3 = wait_page(doc_id, lambda p: p.get("rot") == 270 and p.get("out") not in (None, pg2["out"]), 120, "xoay trang đã đánh dấu")
    time.sleep(2.0)
    shot("markup_rotated")
    # trang đã xoay: chữ ký chọn từ thư viện phải nằm thẳng theo chiều đang hiển thị (góc 90 trong hệ ảnh chưa xoay)
    editor_tool("Ký tên")
    wait_activity("MarkupActivity", 15)
    time.sleep(5.0)
    if exists(text="Ký vào khung bên dưới", timeout=1.5):
        raise AssertionError("Đã có chữ ký trong thư viện mà vẫn tự mở bảng ký")
    tap(desc="Chữ ký 1")
    time.sleep(1.0)
    shot("markup_sign_rotated")
    apply_edit_screen("MarkupActivity")
    pg4 = wait_page(doc_id, lambda p: len(p.get("marks", [])) == len(types) + 1 and p.get("out") not in (None, pg3["out"]), 120, "thêm chữ ký thứ hai")
    last = pg4["marks"][-1]
    if last.get("t") != 3 or last.get("a") != 90:
        raise AssertionError("Chữ ký trên trang đã xoay sai góc: %s" % {k: last.get(k) for k in ("t", "a")})
    time.sleep(2.0)
    tap(desc="Xong")
    wait_activity("DocumentActivity", 40)
    time.sleep(2.0)
    shot("markup_document")


def t_header_collage():
    """Tiêu đề trang (dòng chữ lề trên + số trang, tự đánh lại khi xoá trang) và Cắt dán (ghép các trang lên tờ A4)."""
    names = ["doc_2_dark.jpg", "doc_3_gray.jpg", "doc_6_clutter.jpg"]
    doc_id = import_to_editor(names, wait=90)
    d = wait_doc(doc_id, lambda d: len(d["pages"]) == 3 and all(p.get("out") for p in d["pages"]), 180, "xử lý 3 trang")
    before = [p["out"] for p in d["pages"]]
    im0 = page_image(doc_id, 0)
    editor_tool("Tiêu đề trang")
    time.sleep(1.5)
    if not dialog_open():
        raise AssertionError("Không mở hộp thoại Tiêu đề trang")
    box = None
    for n in nodes(dump()):
        if n.get("class", "") == "android.widget.EditText":
            box = n
            break
    if box is None:
        raise AssertionError("Hộp thoại Tiêu đề trang thiếu ô nhập")
    tap_xy(*bounds(box))
    time.sleep(0.8)
    sh("input text 'BB_2026'")
    time.sleep(0.5)
    tap(text="Đánh số trang", contains=True)
    shot("header_dialog")
    tap(text="Áp dụng")
    want = ["BB_2026\n%d/3" % (i + 1) for i in range(3)]
    d = wait_doc(doc_id, lambda d: [p.get("hdr") for p in d["pages"]] == want and all(p.get("out") not in (None, before[i]) for i, p in enumerate(d["pages"])),
                 180, "dựng lại 3 trang có tiêu đề")
    hd = d.get("header") or {}
    if hd.get("text") != "BB_2026" or hd.get("num") is not True:
        raise AssertionError("Tiêu đề trang lưu sai: %s" % hd)
    time.sleep(2.0)
    shot("header_applied")
    im1 = page_image(doc_id, 0)
    if im0 is not None and im1 is not None:
        top_band, bottom_band = (0.42, 0.008, 0.58, 0.034), (0.45, 0.966, 0.55, 0.992)
        t0, t1 = frac(im0, top_band, "dark"), frac(im1, top_band, "dark")
        b0, b1 = frac(im0, bottom_band, "dark"), frac(im1, bottom_band, "dark")
        log("tiêu đề lề trên %.3f -> %.3f, số trang lề dưới %.3f -> %.3f" % (t0, t1, b0, b1))
        if t1 - t0 < 0.02 or b1 - b0 < 0.01:
            raise AssertionError("Ảnh kết quả không có tiêu đề / số trang (trên %.3f -> %.3f, dưới %.3f -> %.3f)" % (t0, t1, b0, b1))
    # xoá trang đang xem: 2 trang còn lại được đánh số lại 1/2, 2/2
    olds = set(p["out"] for p in d["pages"])
    tap(desc="Xoá trang")
    time.sleep(1.0)
    tap(text="Xoá")
    want = ["BB_2026\n%d/2" % (i + 1) for i in range(2)]
    d = wait_doc(doc_id, lambda d: [p.get("hdr") for p in d["pages"]] == want and all(p.get("out") and p["out"] not in olds for p in d["pages"]),
                 180, "đánh lại số trang sau khi xoá trang")
    time.sleep(2.0)
    shot("header_renumbered")
    # Cắt dán: ghép 2 trang lên một tờ A4 -> tài liệu mới
    ids_before = set(doc_ids())
    editor_tool("Cắt dán")
    time.sleep(1.5)
    shot("collage_menu")
    tap(text="2 trang mỗi tờ")
    wait_activity("DocumentActivity", 120)
    time.sleep(3.0)
    shot("collage_document")
    new = [i for i in doc_ids() if i not in ids_before]
    if len(new) != 1:
        raise AssertionError("Cắt dán không tạo đúng một tài liệu mới: %s" % new)
    cd = wait_doc(new[0], lambda d: len(d["pages"]) == 1 and d["pages"][0].get("out"), 120, "xử lý tờ ghép")
    size = sorted([cd["pages"][0].get("w"), cd["pages"][0].get("h")])
    if cd.get("draft") or not str(cd.get("title", "")).endswith(" - ghép") or size != [2480, 3508]:
        raise AssertionError("Tài liệu ghép sai: %s / %s" % (cd.get("title"), size))
    sheet = page_image(new[0], 0)
    if sheet is not None:
        log("tờ ghép: %sx%s" % sheet.size)
        if min(sheet.size) < 1500 or max(sheet.size) > 3508:
            raise AssertionError("Tờ ghép có kích thước lạ: %sx%s" % sheet.size)
        # hai trang dọc nằm cạnh nhau trên tờ A4 ngang: cả hai ô (trái / phải) đều phải có nội dung
        if sheet.size[0] <= sheet.size[1]:
            raise AssertionError("Ghép 2 trang dọc phải cho tờ A4 ngang, nhưng tờ ghép là %sx%s" % sheet.size)
        for label, box in (("ô trái", (0.08, 0.2, 0.45, 0.8)), ("ô phải", (0.55, 0.2, 0.92, 0.8))):
            v = frac(sheet, box, "dark")
            log("tờ ghép %s: %.3f" % (label, v))
            if v < 0.005:
                raise AssertionError("Tờ ghép trống ở %s" % label)
    back()
    wait_activity("EditorActivity", 15)
    time.sleep(1.0)
    # gỡ tiêu đề
    editor_tool("Tiêu đề trang")
    time.sleep(1.5)
    tap(text="Gỡ bỏ")
    d = wait_doc(doc_id, lambda d: all(p.get("hdr") is None and p.get("out") for p in d["pages"]) and not d.get("header"), 120, "gỡ tiêu đề trang")
    time.sleep(6.0)
    tap(desc="Xong")
    wait_activity("DocumentActivity", 60)
    time.sleep(2.0)
    shot("header_document")
    d = read_doc(doc_id)
    if d is None or d.get("draft") or len(d["pages"]) != 2:
        raise AssertionError("Tài liệu lưu sai sau Tiêu đề trang / Cắt dán")



# ----------------------------------------------------------------------------- phóng to bằng hai ngón

def monkey_script(cmds, wait_ms=400):
    """Chạy một kịch bản monkey: thao tác chạm chính xác mà `input` không làm được (PinchZoom hai ngón, chạm đúp...)."""
    lines = ["type= user", "count= 1", "speed= 1.0", "start data >>"] + list(cmds) + ["UserWait(%d)" % wait_ms]
    adb("shell", "cat > /sdcard/qt_script.txt", inp=("\n".join(lines) + "\n").encode(), timeout=20)
    out = sh("monkey -f /sdcard/qt_script.txt 1", timeout=90)
    if "Events injected" not in out:
        log("monkey script:", out.strip()[-300:])
    time.sleep(0.8)


def pinch(cx, cy, d0, d1, steps=20):
    """Hai ngón đặt hai bên điểm (cx, cy), mỗi ngón cách nó d0 điểm ảnh theo phương ngang, rồi dang ra / chụm lại tới d1."""
    monkey_script(["PinchZoom(%d,%d,%d,%d,%d,%d,%d,%d,%d)" % (cx - d0, cy, cx - d1, cy, cx + d0, cy, cx + d1, cy, steps)])


def strip_state():
    """Dải trang của trình chỉnh sửa: (khung, trang đang xem, số trang, mức phóng) đọc từ mô tả của nó
    ("Trang 1/2" hoặc "Trang 1/2, phóng 2,5×")."""
    for _ in range(4):
        root = dump()
        for n in nodes(root):
            m = re.match(r"Trang (\d+)/(\d+)(?:, phóng ([\d,]+)×)?$", n.get("content-desc", ""))
            if m:
                z = float(m.group(3).replace(",", ".")) if m.group(3) else 1.0
                return rect(n), int(m.group(1)), int(m.group(2)), z
        time.sleep(1.0)
    raise AssertionError("Không thấy dải trang của trình chỉnh sửa")


def edge_dark(box):
    """Tỷ lệ điểm không trắng ở dải sát mép trái của dải trang (giữa chiều cao): vừa khung thì đó là nền tối của màn hình,
    phóng to thì trang giấy tràn tới mép. None nếu thiếu Pillow."""
    im = screen_image()
    if im is None:
        return None
    x1, y1, x2, y2 = box
    w, h = x2 - x1, y2 - y1
    return frac(im.crop((x1, y1 + int(h * 0.35), x1 + max(2, int(w * 0.04)), y1 + int(h * 0.65))), (0, 0, 1, 1), "nonwhite")


def t_pinch_zoom():
    """Phóng to bằng hai ngón ở trình chỉnh sửa: dang hai ngón để phóng, một ngón kéo ảnh mà không lật trang, mức phóng giữ
    nguyên khi mở bảng lọc và đổi bộ lọc, chạm đúp về vừa khung / phóng lại, sang trang khác thì về vừa khung, chụm hai ngón
    để thu nhỏ, vừa khung thì vuốt lật trang như cũ."""
    doc_id = import_to_editor(["doc_1_wood.jpg", "doc_4_beige.jpg"])
    d = wait_doc(doc_id, lambda d: len(d["pages"]) == 2 and all(p.get("out") for p in d["pages"]), 150, "xử lý 2 trang")
    time.sleep(2.0)
    box, page, total, z = strip_state()
    if (page, total) != (1, 2) or z != 1.0:
        raise AssertionError("Trình chỉnh sửa không mở ở trang 1/2 vừa khung: trang %d/%d, phóng %.1f" % (page, total, z))
    x1, y1, x2, y2 = box
    cx, cy, w = (x1 + x2) // 2, (y1 + y2) // 2, x2 - x1
    e0 = edge_dark(box)
    shot("zoom_fit")
    # dang hai ngón (khoảng cách x4) -> phóng to quanh giữa trang
    pinch(cx, cy, int(w * 0.07), int(w * 0.28))
    box, page, total, z1 = strip_state()
    e1 = edge_dark(box)
    shot("zoom_in")
    log("phóng to: %.1f× (mép trái không trắng %s -> %s)" % (z1, e0, e1))
    if z1 < 2.0:
        raise AssertionError("Dang hai ngón không phóng to trang (mức phóng %.1f)" % z1)
    if e0 is not None and e1 is not None and not (e0 > 0.9 and e1 < 0.75):
        raise AssertionError("Ảnh trang không tràn khung khi phóng to (mép trái không trắng %.2f -> %.2f)" % (e0, e1))
    # một ngón kéo ngang khi đang phóng: kéo ảnh, KHÔNG lật trang, không đổi mức phóng
    before = screen_image()
    swipe((int(cx + w * 0.25), cy), (int(cx - w * 0.25), cy), 500)
    box, page, total, z2 = strip_state()
    after = screen_image()
    if page != 1 or abs(z2 - z1) > 0.05:
        raise AssertionError("Kéo một ngón khi đang phóng to: trang %d, phóng %.1f (trước đó %.1f)" % (page, z2, z1))
    if before is not None and after is not None:
        a = before.crop((x1, y1, x2, y2)).convert("L").resize((64, 64))
        b = after.crop((x1, y1, x2, y2)).convert("L").resize((64, 64))
        diff = sum(abs(p - q) for p, q in zip(rgb_pixels(a), rgb_pixels(b))) / (64.0 * 64.0)
        log("kéo ảnh khi đang phóng: khác biệt trung bình %.1f" % diff)
        if diff < 2.0:
            raise AssertionError("Kéo một ngón khi đang phóng to không dời ảnh (khác biệt %.1f)" % diff)
    # mở bảng lọc + đổi bộ lọc: vẫn giữ mức phóng (xem chi tiết từng bộ lọc)
    editor_tool("Bộ lọc")
    time.sleep(2.5)
    _, page, total, z3 = strip_state()
    if abs(z3 - z1) > 0.05:
        raise AssertionError("Mở bảng lọc làm mất mức phóng (%.1f -> %.1f)" % (z1, z3))
    prev = d["pages"][0]["out"]
    filter_pick("Đen trắng")
    wait_page(doc_id, lambda p: p.get("filter") == F_BW and p.get("out") not in (None, prev), 90, "bộ lọc Đen trắng khi đang phóng")
    time.sleep(2.5)
    box3, page, total, z4 = strip_state()
    shot("zoom_filter_bw")
    if page != 1 or abs(z4 - z1) > 0.05:
        raise AssertionError("Đổi bộ lọc khi đang phóng to: trang %d, phóng %.1f (trước đó %.1f)" % (page, z4, z1))
    # giữ So sánh khi đang phóng: không được làm mất mức phóng
    hold(text="So sánh", ms=1200)
    time.sleep(0.8)
    _, _, _, z5 = strip_state()
    if abs(z5 - z1) > 0.05:
        raise AssertionError("Giữ So sánh làm mất mức phóng (%.1f -> %.1f)" % (z1, z5))
    tap(desc="Đóng bộ lọc")
    time.sleep(1.2)
    box, page, total, z = strip_state()
    x1, y1, x2, y2 = box
    cx, cy, w = (x1 + x2) // 2, (y1 + y2) // 2, x2 - x1
    # chạm đúp: về vừa khung; chạm đúp lần nữa: phóng 2,5 lần
    fast_taps([(cx, cy), (cx, cy)], gap_ms=90)
    time.sleep(1.0)
    _, _, _, z = strip_state()
    if z != 1.0:
        raise AssertionError("Chạm đúp khi đang phóng to không về vừa khung (%.1f)" % z)
    time.sleep(0.6)
    fast_taps([(cx, cy), (cx, cy)], gap_ms=90)
    time.sleep(1.0)
    _, _, _, z = strip_state()
    shot("zoom_double_tap")
    if abs(z - 2.5) > 0.05:
        raise AssertionError("Chạm đúp ở mức vừa khung không phóng 2,5 lần (%.1f)" % z)
    # sang trang sau: trang mới ở mức vừa khung
    tap(desc="Trang sau")
    time.sleep(1.5)
    box, page, total, z = strip_state()
    if page != 2 or z != 1.0:
        raise AssertionError("Sang trang sau khi đang phóng to: trang %d, phóng %.1f" % (page, z))
    # phóng to trang 2 rồi chụm hai ngón để thu về vừa khung
    pinch(cx, cy, int(w * 0.07), int(w * 0.28))
    _, _, _, z = strip_state()
    if z < 2.0:
        raise AssertionError("Dang hai ngón ở trang 2 không phóng to (%.1f)" % z)
    pinch(cx, cy, int(w * 0.30), int(w * 0.03))
    _, page, _, z = strip_state()
    if page != 2 or z != 1.0:
        raise AssertionError("Chụm hai ngón không thu về vừa khung: trang %d, phóng %.1f" % (page, z))
    # vừa khung: vuốt ngang lật trang như cũ
    swipe((int(cx - w * 0.3), cy), (int(cx + w * 0.3), cy), 300)
    time.sleep(1.2)
    _, page, _, z = strip_state()
    shot("zoom_back_to_page1")
    if page != 1 or z != 1.0:
        raise AssertionError("Vuốt lật trang sau khi thu nhỏ: trang %d, phóng %.1f" % (page, z))
    tap(desc="Xong")
    wait_activity("DocumentActivity", 40)
    time.sleep(1.5)
    d = read_doc(doc_id)
    if d is None or d.get("draft") or len(d["pages"]) != 2 or d["pages"][0].get("filter") != F_BW:
        raise AssertionError("Tài liệu lưu sai sau khi phóng to + đổi bộ lọc")



def screen_size():
    out = sh("wm size")
    m = re.findall(r"(\d+)x(\d+)", out)
    if m:
        w, h = map(int, m[-1])
        return w, h
    return 1080, 2400


def main():
    log("device:", sh("getprop ro.build.version.release").strip(), sh("getprop ro.build.version.sdk").strip(), sh("wm size").strip(), sh("wm density").strip())
    step("Cài đặt", t_install)
    step("Mở ứng dụng", t_launch)
    step("Chụp đơn + màn hình cắt", t_single_scan)
    step("Công cụ chỉnh sửa (lọc, xoay, so sánh, cắt lại)", t_editor_tools)
    step("Lưu tài liệu", t_save_document)
    step("Chia sẻ PDF/JPG", t_share_pdf)
    step("Lưu ảnh vào Thư viện", t_save_images)
    step("Lưu PDF (bộ chọn tệp)", t_save_pdf_picker)
    step("Thêm trang", t_add_page)
    step("Đổi tên", t_rename)
    step("Chế độ hàng loạt + hoàn tác", t_batch_mode)
    step("Nút trên máy ảnh", t_camera_controls)
    for n in ["doc_1_wood.jpg", "doc_4_beige.jpg", "doc_5_blue.jpg"]:
        step("Nhập ảnh %s" % n, t_import(n))
    step("Nhập nhiều ảnh", t_import_multi)
    step("Chuyển sang Word (nhận dạng chữ, bảng, huỷ, chia sẻ / lưu)", t_word_export)
    step("Phóng to bằng hai ngón (trình chỉnh sửa, bộ lọc)", t_pinch_zoom)
    step("Bộ lọc mới + lời nhắc ngón tay", t_filters_new)
    step("Bộ lọc Làm phẳng (giấy nhàu, gấp nếp)", t_flatten)
    step("Làm phẳng + Xóa thông minh + Đánh dấu", t_flatten_edit)
    step("Xóa thông minh", t_smart_erase)
    step("Đánh dấu (ký tên, chữ, brush, khảm, hình mờ)", t_markup)
    step("Tiêu đề trang + Cắt dán", t_header_collage)
    step("Nhập 12 ảnh lớn (12 MP)", t_stress_import)
    step("Danh sách + tìm kiếm", t_home_list)
    step("Xoá tài liệu", t_delete_document)
    step("Chạm đúp không mở trùng màn hình", t_double_tap)
    step("Lật trang rồi thao tác ngay", t_flip_then_act)
    step("Nhập tệp hỏng / không phải ảnh", t_import_corrupt)
    step("Nhiều định dạng ảnh + EXIF + kiểm tra PDF/JPG xuất ra", t_formats_and_export)
    step("Tìm kiếm không dấu + tên dài", t_search_and_long_title)
    step("Đổi cấu hình (cỡ chữ) ở từng màn hình", t_config_change)
    step("Không giữ hoạt động (lưu/khôi phục trạng thái)", t_dont_keep_activities)
    step("Tiến trình bị thu hồi khi ở nền", t_process_death)
    step("Khôi phục bản nháp sau khi bị buộc dừng", t_draft_recovery)
    step("Từ chối quyền máy ảnh", t_permission_denied)
    step("Máy ảnh bị chặn giữa chừng + Thử lại", t_camera_blocked_retry)
    step("Dừng ứng dụng giữa chừng", t_rotation_and_recreate)
    step("Monkey 2500 thao tác ngẫu nhiên", t_monkey)
    if ONLY and not only_hits:
        # TEST_ONLY gõ sai: chỉ "Cài đặt" + "Mở ứng dụng" chạy — báo lỗi thay vì "đạt" mà không kiểm thử bước nào
        log("FAIL TEST_ONLY không khớp bước nào:", ONLY)
        results.append({"step": "TEST_ONLY", "ok": False, "sec": 0, "error": "TEST_ONLY=%r không khớp bước nào" % ONLY})

    adb_log = adb("logcat", "-d", "-v", "threadtime", timeout=120)
    open(os.path.join(OUT, "logcat.txt"), "w").write(adb_log)
    app_log = "\n".join(l for l in adb_log.splitlines() if PKG in l or "AndroidRuntime" in l or "quettailieu" in l or " E " in l)
    open(os.path.join(OUT, "logcat_app.txt"), "w").write(app_log)
    open(os.path.join(OUT, "crash.txt"), "w").write(crash_buffer())
    open(os.path.join(OUT, "app_crash_file.txt"), "w").write(sh("run-as %s cat files/last_crash.txt" % PKG))
    passed = sum(1 for r in results if r["ok"])
    md = ["# Kết quả kiểm thử Android", "", "Thiết bị: API %s" % sh("getprop ro.build.version.sdk").strip(), "",
          "**%d/%d bước đạt**" % (passed, len(results)), "", "| Bước | Kết quả | Giây |", "|---|---|---|"]
    for r in results:
        md.append("| %s | %s | %s |" % (r["step"], "✅" if r["ok"] else "❌ " + r.get("error", "").replace("|", "/").replace("\n", " ")[:300], r["sec"]))
    if notes:
        md += ["", "Ghi chú:"] + ["- " + n for n in notes]
    open(os.path.join(OUT, "summary.md"), "w").write("\n".join(md) + "\n")
    json.dump({"passed": passed, "total": len(results), "results": results}, open(os.path.join(OUT, "summary.json"), "w"), ensure_ascii=False, indent=1)
    log("DONE %d/%d" % (passed, len(results)))
    return passed == len(results)


if __name__ == "__main__":
    sys.exit(0 if main() else 1)
