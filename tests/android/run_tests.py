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
    print("[test]", *a, flush=True)


def adb(*args, timeout=60, inp=None):
    try:
        r = subprocess.run(["adb", *args], input=inp, capture_output=True, timeout=timeout)
        return r.stdout.decode("utf-8", "replace") + r.stderr.decode("utf-8", "replace")
    except subprocess.TimeoutExpired:
        return "TIMEOUT"


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


def find(text=None, desc=None, contains=False, root=None):
    root = root if root is not None else dump()
    for n in nodes(root):
        t, d = n.get("text", ""), n.get("content-desc", "")
        if text is not None:
            if (contains and text in t) or t == text:
                return n
        if desc is not None:
            if (contains and desc in d) or d == desc:
                return n
    return None


def tap_xy(x, y):
    sh("input tap %d %d" % (x, y))


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
    out = adb("exec-in", "run-as", PKG, "sh", "-c", "mkdir -p cache/share && cat > cache/share/%s" % name, inp=data, timeout=60)
    ls = sh("run-as %s ls -l cache/share/%s" % (PKG, name))
    if name not in ls:
        raise AssertionError("Không đẩy được ảnh thử: %s %s" % (out, ls))


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
            return
        back()
        # hộp thoại xác nhận bỏ trang
        n = find(text="Bỏ")
        if n is not None:
            tap_xy(*bounds(n))
            time.sleep(0.8)
    sh("am start -W -n %s/.MainActivity" % PKG)
    time.sleep(2)


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


def t_save_images():
    tap(text="Lưu ảnh")
    time.sleep(4)
    shot("save_images")
    q = sh("content query --uri content://media/external/images/media --projection _display_name", timeout=30)
    open(os.path.join(OUT, "mediastore.txt"), "w").write(q)
    if "Scan" not in q:
        raise AssertionError("Không thấy ảnh đã lưu trong Thư viện")


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
    if not exists(text="2/2", timeout=6, contains=True):
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
    share_into_app(names)
    wait_activity("EditorActivity", 40)
    wait_processing(20)
    shot("import_multi_editor")
    if not exists(text="/3", timeout=5, contains=True):
        raise AssertionError("Nhập nhiều ảnh không đủ 3 trang")
    tap(desc="Xong")
    wait_activity("DocumentActivity", 40)
    time.sleep(2)
    shot("import_multi_document")


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


def t_delete_document():
    go_home()
    tap(desc="Tùy chọn tài liệu")
    tap(text="Xoá")
    time.sleep(0.8)
    shot("delete_confirm")
    tap(text="Xoá")
    time.sleep(1.5)
    shot("after_delete")


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
    out = sh("monkey -p %s --pct-syskeys 0 --pct-appswitch 0 --pct-anyevent 0 --throttle 120 -s 1234 -v 2500" % PKG, timeout=900)
    open(os.path.join(OUT, "monkey.txt"), "w").write(out)
    if "// CRASH" in out or "NOT RESPONDING" in out:
        raise AssertionError("Monkey phát hiện lỗi:\n" + out[-3000:])
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
    step("Danh sách + tìm kiếm", t_home_list)
    step("Xoá tài liệu", t_delete_document)
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
    open(os.path.join(OUT, "summary.md"), "w").write("\n".join(md) + "\n")
    json.dump({"passed": passed, "total": len(results), "results": results}, open(os.path.join(OUT, "summary.json"), "w"), ensure_ascii=False, indent=1)
    log("DONE %d/%d" % (passed, len(results)))


if __name__ == "__main__":
    main()
