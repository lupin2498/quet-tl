#!/usr/bin/env python3
"""Sinh ảnh TỔNG HỢP để kiểm thử phần làm đẹp / chỉnh sửa trang (Retouch, bộ lọc mới).

Mỗi ảnh là một trang ĐÃ nắn phẳng (như ảnh trung gian trong ứng dụng), ghi dạng PPM:
  rt_plain          trang sạch, ánh sáng hơi lệch
  rt_finger_l/b/c   có ngón tay giữ giấy ở mép trái / mép dưới / góc trên-phải
  rt_stamp          con dấu đỏ gần mép (không phải ngón tay)
  rt_strip          dải mặt bàn màu nâu dọc hết mép trái (vùng cắt rộng, không phải ngón tay)
  rt_photo          ảnh chân dung (mảng màu da) nằm trong trang
  rt_hand           chữ in đen + chữ viết tay bút xanh + con dấu đỏ + dấu tích đỏ nhỏ
  rt_shadow         bóng đổ rõ mép chéo qua trang
  rt_edge           vệt tối sát mép (mép bàn / gáy giấy)
Cách chạy: python3 tests/tools/make_retouch_images.py <thư-mục> [cạnh-dài] [thư-mục-ảnh-chụp]

Nếu có tham số thứ ba, ghi thêm ảnh "chụp" giả lập edit_page.jpg (1200x1600): trang giấy nằm trên mặt bàn sẫm,
có ngón tay giữ ở mép trái, chữ viết tay bút xanh và con dấu đỏ — dùng cho kiểm thử giao diện (nhập ảnh -> tìm mép ->
lời nhắc ngón tay -> bộ lọc Thần kỳ / Xóa thông minh). File này được đưa vào tests/images/.
"""
import math
import os
import random
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

OUT = sys.argv[1] if len(sys.argv) > 1 else "out/retouch-in"
LONG = int(sys.argv[2]) if len(sys.argv) > 2 else 1400
PHOTO_DIR = sys.argv[3] if len(sys.argv) > 3 else None
os.makedirs(OUT, exist_ok=True)
FS = "/usr/share/fonts/truetype/dejavu/DejaVuSerif.ttf"
FB = "/usr/share/fonts/truetype/dejavu/DejaVuSerif-Bold.ttf"
W, H = int(round(LONG * 0.707)), LONG
K = LONG / 1400.0


def font(path, size):
    try:
        return ImageFont.truetype(path, max(6, int(round(size * K))))
    except Exception:
        return ImageFont.load_default()


def s(v):
    return int(round(v * K))


def base_page(paper=(232, 231, 226)):
    """Trang chữ + bảng. Trả về ảnh RGB."""
    im = Image.new("RGB", (W, H), paper)
    d = ImageDraw.Draw(im)
    ink = (38, 38, 42)
    d.text((s(120), s(90)), "ĐƠN VỊ MẪU", font=font(FB, 26), fill=ink)
    d.text((s(430), s(180)), "BẢNG KÊ THIẾT BỊ", font=font(FB, 34), fill=ink)
    f = font(FS, 22)
    y = s(260)
    for line in ["Căn cứ kế hoạch kiểm kê năm 2026, tổ công tác lập bảng kê",
                 "các thiết bị đang sử dụng tại bộ phận như sau:"]:
        d.text((s(110), y), line, font=f, fill=ink)
        y += s(36)
    top = y + s(24)
    cols = [s(100), s(170), s(520), s(680), s(800), s(900)]
    rows = 14
    rh = s(44)
    for r in range(rows + 2):
        d.line([(cols[0], top + r * rh), (cols[-1], top + r * rh)], fill=ink, width=max(1, s(2)))
    for c in cols:
        d.line([(c, top), (c, top + (rows + 1) * rh)], fill=ink, width=max(1, s(2)))
    heads = ["TT", "Tên thiết bị", "Mã hiệu", "ĐVT", "SL"]
    for i, hd in enumerate(heads):
        d.text((cols[i] + s(8), top + s(9)), hd, font=font(FB, 20), fill=ink)
    names = ["Máy in laser", "Màn hình 24 inch", "Bàn phím", "Chuột không dây", "Ổ cứng 1TB", "Bộ phát Wi-Fi", "Máy chiếu",
             "Loa hội nghị", "Bộ lưu điện", "Cáp mạng", "Máy quét", "Đèn bàn", "Tủ hồ sơ", "Ghế xoay"]
    for r in range(rows):
        yy = top + (r + 1) * rh + s(9)
        d.text((cols[0] + s(14), yy), str(r + 1), font=f, fill=ink)
        d.text((cols[1] + s(8), yy), names[r], font=f, fill=ink)
        d.text((cols[2] + s(8), yy), "MH-%03d" % (r * 7 + 3), font=f, fill=ink)
        d.text((cols[3] + s(8), yy), "Cái", font=f, fill=ink)
        d.text((cols[4] + s(20), yy), str((r * 3) % 9 + 1), font=f, fill=ink)
    yb = top + (rows + 1) * rh + s(70)
    d.text((s(150), yb), "NGƯỜI LẬP", font=font(FB, 22), fill=ink)
    d.text((s(640), yb), "THỦ TRƯỞNG", font=font(FB, 22), fill=ink)
    return im


def light(im, lo=0.86, hi=1.0, noise=2.0, seed=1):
    """Ánh sáng lệch dần theo đường chéo + nhiễu cảm biến nhẹ."""
    rng = np.random.RandomState(seed)
    a = np.asarray(im).astype(np.float32)
    yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
    g = lo + (hi - lo) * (0.6 * xx / W + 0.4 * yy / H)
    a = a * g[:, :, None] + rng.randn(H, W, 1) * noise
    return Image.fromarray(np.clip(a, 0, 255).astype(np.uint8))


def finger(im, cx, cy, rx, ry, ang, seed=3):
    """Vẽ ngón tay (elip màu da có vùng khuất tối + móng + bóng trên giấy)."""
    rng = random.Random(seed)
    layer = Image.new("RGBA", im.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    # bóng mềm trên giấy
    sh = Image.new("L", im.size, 0)
    ds = ImageDraw.Draw(sh)
    ds.ellipse([cx - rx * 1.18, cy - ry * 1.12, cx + rx * 1.18, cy + ry * 1.12], fill=120)
    sh = sh.filter(ImageFilter.GaussianBlur(max(2, s(9))))
    a = np.asarray(im).astype(np.float32)
    a *= (1.0 - np.asarray(sh).astype(np.float32)[:, :, None] / 255.0 * 0.45)
    im = Image.fromarray(np.clip(a, 0, 255).astype(np.uint8))
    skin = (196 + rng.randint(-8, 8), 138 + rng.randint(-8, 8), 112 + rng.randint(-8, 8))
    d.ellipse([cx - rx, cy - ry, cx + rx, cy + ry], fill=skin + (255,))
    # mặt khuất tối hơn
    d.ellipse([cx - rx * 0.2, cy - ry * 0.9, cx + rx, cy + ry * 0.9], fill=(150, 98, 78, 255))
    d.ellipse([cx - rx * 0.75, cy - ry * 0.55, cx - rx * 0.1, cy + ry * 0.1], fill=(222, 178, 160, 255))  # móng
    layer = layer.rotate(ang, center=(cx, cy), resample=Image.BILINEAR).filter(ImageFilter.GaussianBlur(max(1, s(2))))
    im = im.convert("RGBA")
    im.alpha_composite(layer)
    return im.convert("RGB")


def stamp(d, cx, cy, r, color=(205, 52, 48)):
    wd = max(2, s(5))
    d.ellipse([cx - r, cy - r, cx + r, cy + r], outline=color, width=wd)
    d.ellipse([cx - r * 0.72, cy - r * 0.72, cx + r * 0.72, cy + r * 0.72], outline=color, width=max(1, s(3)))
    pts = []
    for i in range(10):
        a = -math.pi / 2 + i * math.pi / 5
        rr = r * (0.5 if i % 2 == 0 else 0.2)
        pts.append((cx + rr * math.cos(a), cy + rr * math.sin(a)))
    d.polygon(pts, fill=color)


def scribble(d, x, y, w, h, color, seed, width):
    rng = random.Random(seed)
    pts = []
    n = 26
    for i in range(n):
        t = i / (n - 1.0)
        pts.append((x + w * t + rng.uniform(-0.03, 0.03) * w, y + h * (0.5 + 0.45 * math.sin(t * 9.0 + seed)) + rng.uniform(-0.05, 0.05) * h))
    d.line(pts, fill=color, width=width, joint="curve")


def save(im, name):
    im.convert("RGB").save(os.path.join(OUT, name + ".ppm"))


def hand_page():
    """Trang có chữ viết tay bút xanh + con dấu đỏ + dấu tích đỏ nhỏ (chưa chiếu sáng)."""
    im = base_page()
    d = ImageDraw.Draw(im)
    blue = (36, 62, 170)
    scribble(d, s(150), s(1230), s(250), s(80), blue, 11, max(2, s(4)))
    scribble(d, s(560), s(330), s(300), s(50), (58, 44, 150), 12, max(2, s(4)))
    d.text((s(620), s(1150)), "Đồng ý", font=font(FS, 34), fill=blue)
    stamp(d, s(720), s(1270), s(86))
    d.line([(s(930), s(520)), (s(945), s(540)), (s(975), s(498))], fill=(210, 40, 40), width=max(2, s(4)))  # dấu tích đỏ nhỏ
    return im.filter(ImageFilter.GaussianBlur(0.6))


def persp_coeffs(dst, src):
    """Hệ số cho Image.transform(PERSPECTIVE): ánh xạ điểm ảnh đích -> điểm ảnh nguồn."""
    m = []
    for (x, y), (u, v) in zip(dst, src):
        m.append([x, y, 1, 0, 0, 0, -u * x, -u * y])
        m.append([0, 0, 0, x, y, 1, -v * x, -v * y])
    a = np.array(m, dtype=np.float64)
    b = np.array([c for p in src for c in p], dtype=np.float64)
    return np.linalg.solve(a, b).tolist()


def photo(name, out_w=1200, out_h=1600):
    """Ảnh "chụp" giả lập: trang (viết tay + dấu) trên mặt bàn sẫm, ngón tay giữ giấy thò vào từ mép trái."""
    m = s(150)
    rng = np.random.RandomState(31)
    cw, ch = W + 2 * m, H + 2 * m
    desk = np.zeros((ch, cw, 3), np.float32)
    desk[:] = (66, 50, 40)
    # vân bàn mờ theo chiều ngang
    desk += (np.sin(np.arange(ch)[:, None, None] / max(3.0, s(23))) * 5.0 + rng.randn(ch, 1, 1) * 2.5)
    canvas = Image.fromarray(np.clip(desk, 0, 255).astype(np.uint8))
    page = light(hand_page(), lo=0.88, seed=16)
    # bóng đổ nhẹ quanh tờ giấy
    shadow = Image.new("L", (cw, ch), 0)
    ImageDraw.Draw(shadow).rectangle([m + s(8), m + s(10), m + W + s(8), m + H + s(10)], fill=110)
    shadow = shadow.filter(ImageFilter.GaussianBlur(max(2, s(8))))
    a = np.asarray(canvas).astype(np.float32) * (1.0 - np.asarray(shadow).astype(np.float32)[:, :, None] / 255.0 * 0.5)
    canvas = Image.fromarray(np.clip(a, 0, 255).astype(np.uint8))
    canvas.paste(page, (m, m))
    # ngón tay dài thò từ ngoài khung ảnh vào, đè lên mép trái trang ~7% bề ngang
    canvas = finger(canvas, m - s(66), m + s(640), s(136), s(64), 6, seed=17)
    # đặt cả khung lên ảnh chụp, hơi nghiêng / phối cảnh nhẹ
    dst = [(28, 46), (out_w - 60, 22), (out_w - 22, out_h - 40), (52, out_h - 70)]
    src = [(m * 0.42, m * 0.45), (cw - m * 0.42, m * 0.45), (cw - m * 0.42, ch - m * 0.45), (m * 0.42, ch - m * 0.45)]
    out = canvas.transform((out_w, out_h), Image.PERSPECTIVE, persp_coeffs(dst, src), Image.BICUBIC, fillcolor=(58, 44, 36))
    arr = np.asarray(out).astype(np.float32) + rng.randn(out_h, out_w, 1) * 1.6
    os.makedirs(PHOTO_DIR, exist_ok=True)
    Image.fromarray(np.clip(arr, 0, 255).astype(np.uint8)).save(os.path.join(PHOTO_DIR, name + ".jpg"), quality=88)


def main():
    random.seed(20261110)
    np.random.seed(20261110)
    save(light(base_page(), seed=1), "rt_plain")

    im = light(base_page(), seed=2)
    save(finger(im, s(8), s(520), s(62), s(150), 8, seed=3), "rt_finger_l")

    im = light(base_page(), lo=0.8, seed=4)
    save(finger(im, s(560), H - s(10), s(58), s(130), 82, seed=5), "rt_finger_b")

    im = light(base_page((226, 228, 232)), seed=6)
    save(finger(im, W - s(20), s(40), s(60), s(140), -40, seed=7), "rt_finger_c")

    im = base_page()
    d = ImageDraw.Draw(im)
    stamp(d, s(120), s(1180), s(88))
    d.text((s(520), s(1250)), "ĐÃ KIỂM TRA", font=font(FB, 30), fill=(205, 52, 48))
    save(light(im.filter(ImageFilter.GaussianBlur(0.6)), seed=8), "rt_stamp")

    im = base_page()
    d = ImageDraw.Draw(im)
    d.rectangle([0, 0, s(22), H], fill=(120, 74, 44))
    save(light(im, seed=9), "rt_strip")

    im = base_page()
    d = ImageDraw.Draw(im)
    d.rectangle([s(700), s(60), s(880), s(290)], fill=(190, 140, 118))
    d.ellipse([s(745), s(90), s(835), s(200)], fill=(214, 164, 138))
    save(light(im, seed=10), "rt_photo")

    save(light(hand_page(), seed=13), "rt_hand")

    im = light(base_page(), lo=0.94, seed=14)
    a = np.asarray(im).astype(np.float32)
    yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
    edge = (xx * 0.8 + yy * 0.6) - 0.62 * (W * 0.8 + H * 0.6)
    shade = 1.0 - 0.42 / (1.0 + np.exp(-edge / max(1.0, s(6))))
    save(Image.fromarray(np.clip(a * shade[:, :, None], 0, 255).astype(np.uint8)), "rt_shadow")

    im = base_page()
    d = ImageDraw.Draw(im)
    d.polygon([(0, 0), (W, 0), (W, s(7)), (0, s(14))], fill=(52, 40, 34))
    d.polygon([(W - s(12), s(300)), (W, s(280)), (W, H), (W - s(5), H)], fill=(70, 52, 40))
    d.rectangle([0, H - s(9), s(420), H], fill=(28, 28, 30))
    save(light(im, seed=15), "rt_edge")
    print("đã ghi 10 ảnh %dx%d vào %s" % (W, H, OUT))
    if PHOTO_DIR:
        photo("edit_page")
        print("đã ghi ảnh chụp giả lập edit_page.jpg vào %s" % PHOTO_DIR)


if __name__ == "__main__":
    main()
