"""Sinh các ca hồi quy TỔNG HỢP cho bộ lọc "Làm phẳng" (Flatten) — các lỗi đã gặp ở những đợt rà soát trước khi phát hành:

  wv_stampover       con dấu đỏ đè lên các hàng của bảng (giấy gợn nhẹ): con dấu phải giữ màu đỏ, không bị coi là đường kẻ
  wv_flat_stamp      như trên, trang phẳng: không được nắn méo trang (vòng dấu cạnh dòng chữ từng làm cong trang phẳng)
  wv_bluebody        3 dòng tiêu đề in đen + 15 dòng chữ viết bút xanh (giấy gợn): các dòng chữ xanh phải được nắn thẳng
  wv_formblue        biểu mẫu in đen, phần điền viết bút xanh trên cùng dòng (giấy gợn): phải được nắn thẳng
  wv_foldtable       bảng gấp ba: hai nếp gấp sâu nằm ngang cắt giữa hai hàng của bảng (giấy gợn): nếp gấp không được
                     giữ lại thành vạch đậm vắt ngang bảng
  wv_flat_fold       như trên, trang phẳng
  wv_notebook        giấy vở kẻ dòng xanh nhạt + lề đỏ + chữ viết bút xanh (giấy gợn): dòng kẻ in sẵn không được giữ
                     lại nguyên dòng (gt/wv_notebook.rules: x0 x1 của vùng không có chữ, rồi toạ độ dọc các dòng kẻ trên
                     trang phẳng)

Định dạng như make_wavy_images.py (dùng chung các hàm vẽ): <tên>.ppm + gt/ (trang phẳng, bản đồ biến dạng, thông tin đáp
án) để chạy EvalFlatten; trang tên bắt đầu bằng wv_flat_ được EvalFlatten kiểm tra như trang phẳng đối chứng.
Cách chạy: python3 tests/tools/make_flatten_cases.py <thư-mục> [cạnh-dài, mặc định 1400]
"""
import os
import random
import sys

import numpy as np

OUT = sys.argv[1] if len(sys.argv) > 1 else "out/flatten-cases"
LONG = sys.argv[2] if len(sys.argv) > 2 else "1400"
# make_wavy_images đọc thư mục ra + cạnh dài từ sys.argv khi được nạp
sys.argv = [sys.argv[0], OUT, LONG]
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import make_wavy_images as mw  # noqa: E402

W, H, s, font = mw.W, mw.H, mw.s, mw.font
FI = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Oblique.ttf"
if not os.path.exists(FI):
    FI = mw.FS
BLUE = (30, 58, 170)
FLAT = (np.zeros((H, W)), np.zeros((H, W)))


def stamp_over(warp):
    # table_page(rows=10): đầu bảng ở y ~354 (đơn vị cạnh dài 1400), hàng cao 44 — con dấu phủ hàng 4-9
    p = mw.table_page(rows=10, seed=17)
    mw.stamp(p.d, s(640), s(600), s(80))
    p.info.append("stamp %d %d %d" % (s(640), s(600), s(80)))
    dx, dy = mw.waves(np.random.RandomState(101), 0.006 * H, 0.6 * W) if warp else FLAT
    return p, dx, dy, None


def header(p):
    p.text(s(240), s(70), "CỘNG HÒA XÃ HỘI CHỦ NGHĨA VIỆT NAM", font(mw.FB, 24))
    p.text(s(330), s(110), "Độc lập - Tự do - Hạnh phúc", font(mw.FB, 22))
    p.text(s(300), s(180), "GIẤY ĐỀ NGHỊ THANH TOÁN", font(mw.FB, 30))


def words(rr, f, width, d):
    out = []
    while True:
        wd = rr.choice(mw.WORDS)
        if d.textlength(" ".join(out + [wd]), font=f) > width:
            return " ".join(out)
        out.append(wd)


def blue_body():
    p = mw.Page()
    header(p)
    rr = random.Random(4)
    f = font(FI, 24)
    y = s(260)
    for _ in range(15):
        bb = p.text(s(100), y, words(rr, f, s(780), p.d), f, BLUE)
        p.info.append("text %d %d %d" % ((bb[1] + bb[3]) // 2, bb[0], bb[2]))
        y += s(50)
    return p, *mw.waves(np.random.RandomState(5), 0.010 * H, 0.6 * W), None


def form_blue():
    p = mw.Page()
    header(p)
    rr = random.Random(3)
    f, fh = font(mw.FS, 22), font(FI, 25)
    y = s(270)
    for lb in ("Kính gửi:", "Họ và tên:", "Bộ phận:", "Nội dung:", "Số tiền:", "Bằng chữ:", "Kèm theo:", "Ghi chú:",
               "Người nhận:", "Địa chỉ:", "Lý do:"):
        bb = p.text(s(100), y, lb, f)
        bb2 = p.text(s(270), y - s(2), words(rr, fh, s(rr.uniform(480, 600)), p.d), fh, BLUE)
        p.info.append("text %d %d %d" % ((bb[1] + bb[3]) // 2, bb[0], bb2[2]))
        y += s(64)
    p.text(s(620), y + s(30), "NGƯỜI ĐỀ NGHỊ", font(mw.FB, 22))
    return p, *mw.waves(np.random.RandomState(5), 0.010 * H, 0.6 * W), None


def fold_table(warp):
    p = mw.table_page(rows=14, seed=3)
    sh = np.ones((H, W))
    # hai nếp gấp ngang sâu (tối 0,48) cắt giữa hàng 5 và hàng 11
    y1, y2 = s(354 + 44 * 5 + 20), s(354 + 44 * 11 + 24)
    mw.crease(sh, p.info, 0, y1, W, y1 + s(4), step=0.87, depth=0.48, width=2.4)
    mw.crease(sh, p.info, 0, y2, W, y2 - s(5), step=0.9, depth=0.48, width=2.4)
    dx, dy = mw.waves(np.random.RandomState(8), 0.007 * H, 0.6 * W) if warp else FLAT
    return p, dx, dy, sh


def notebook():
    p = mw.Page()
    rules = []
    for i in range(32):
        yy = s(120) + i * s(38)
        p.d.line([(0, yy), (W, yy)], fill=(170, 192, 222), width=max(1, s(2)))
        rules.append(yy)
    p.d.line([(s(110), 0), (s(110), H)], fill=(220, 120, 120), width=max(1, s(2)))
    rr = random.Random(12)
    f = font(FI, 24)
    for i in range(2, 31):
        if i % 6 == 5:
            continue
        bb = p.text(s(130), rules[i] - s(27), words(rr, f, s(rr.uniform(450, 750)), p.d), f, BLUE)
        p.info.append("text %d %d %d" % ((bb[1] + bb[3]) // 2, bb[0], bb[2]))
    with open(os.path.join(mw.GT, "wv_notebook.rules"), "w") as fo:
        fo.write("%d %d\n" % (s(920), W - s(25)))  # vùng không có chữ (chữ dài tối đa tới x = 130 + 750)
        fo.write(" ".join(map(str, rules)) + "\n")
    return p, *mw.waves(np.random.RandomState(31), 0.007 * H, 0.6 * W), None


CASES = [
    ("wv_stampover", lambda: stamp_over(True), 51),
    ("wv_flat_stamp", lambda: stamp_over(False), 51),
    ("wv_bluebody", blue_body, 61),
    ("wv_formblue", form_blue, 61),
    ("wv_foldtable", lambda: fold_table(True), 71),
    ("wv_flat_fold", lambda: fold_table(False), 70),
    ("wv_notebook", notebook, 81),
]

for name, make, seed in CASES:
    page, dx, dy, shade = make()
    if shade is None:
        mw.render(name, page, dx, dy, seed=seed)
    else:
        mw.render(name, page, dx, dy, shade=shade, seed=seed)
print("đã ghi %d trang %dx%d vào %s" % (len(CASES), W, H, OUT))
