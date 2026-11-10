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


def check_no_crash(where):
    c = crash_buffer()
    if "FATAL EXCEPTION" in c or ("AndroidRuntime" in c and PKG in c):
        open(os.path.join(OUT, "crash_%s.txt" % where), "w").write(c)
        adb("logcat", "-b", "crash", "-c")
        raise AssertionError("Ứng dụng bị crash tại %s:\n%s" % (where, c[-3000:]))
    anr = sh("dumpsys activity processes | grep -i 'notResponding' | head -3", timeout=20)
    if PKG in anr:
        raise AssertionError("ANR tại %s" % where)


def step(name, fn):
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
    out = adb("install", "-r", "-g", APK, timeout=240)
    log(out.strip())
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
    tap(text="Lọc")
    time.sleep(2.5)
    shot("filter_panel")
    for name in ["Đen trắng", "Thang xám", "Gốc", "Làm sáng", "Tăng cường"]:
        tap(text=name)
        time.sleep(4.5)
        shot("filter_" + re.sub(r"\W+", "_", name))
    tap(desc="Đóng bộ lọc")
    tap(text="Bên trái")
    time.sleep(5)
    shot("rotated_left")
    # So sánh: nhấn giữ
    n = find(text="So sánh")
    if n is not None:
        x, y = bounds(n)
        sh("input swipe %d %d %d %d 1500" % (x, y, x, y))
    # Cắt lại
    tap(text="Cắt")
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
    tap(text="Lọc")
    time.sleep(1.5)
    tap(text="Đen trắng")
    time.sleep(5)
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
        deny = find_id("permission_deny_button") or find_id("permission_deny_and_dont_ask_again_button")
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
        sh("appops set %s CAMERA ignore" % PKG)
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
        tap(text="Cắt")
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
