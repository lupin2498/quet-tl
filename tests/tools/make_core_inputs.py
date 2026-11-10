#!/usr/bin/env python3
"""Sinh dữ liệu TỔNG HỢP để đối chiếu phần thuật toán giữa bản Java (Android) và bản Swift (iOS).

Ghi ra <thư-mục>:
  *.ppm        ảnh RGB (P6): trang giấy trên nền, hoa văn, ảnh rất nhỏ / rất dẹt
  full_*.ppm   ảnh nhỏ dùng để so từng điểm ảnh sau khi lọc
  quads.txt    tứ giác (kể cả suy biến) cho Geom
  jpg/*.jpg    JPEG để dựng PDF
Cách chạy: python3 tests/tools/make_core_inputs.py <thư-mục> [số-ảnh-trang-giấy]
"""
import math
import os
import random
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

OUT = sys.argv[1] if len(sys.argv) > 1 else "out/core-in"
N_DOCS = int(sys.argv[2]) if len(sys.argv) > 2 else 48
os.makedirs(OUT, exist_ok=True)
os.makedirs(os.path.join(OUT, "jpg"), exist_ok=True)
random.seed(20261110)
np.random.seed(20261110)
HERE = os.path.dirname(os.path.abspath(__file__))
IMAGES = os.path.join(HERE, "..", "images")


def save(im, name):
    im.convert("RGB").save(os.path.join(OUT, name + ".ppm"))


def background(kind, w, h):
    if kind == 0:  # một màu tối
        c = random.randint(15, 90)
        return Image.new("RGB", (w, h), (c, max(0, c - random.randint(0, 20)), max(0, c - random.randint(0, 30))))
    if kind == 1:  # vân gỗ (sọc ngang nhiễu)
        base = np.zeros((h, w, 3), np.float32)
        col = np.array([120 + random.randint(-30, 30), 80 + random.randint(-20, 20), 45 + random.randint(-15, 15)], np.float32)
        rows = np.cumsum(np.random.randn(h) * 3.0)
        base[:] = col
        base += rows[:, None, None] * 0.6
        base += np.random.randn(h, w, 1) * 5
        return Image.fromarray(np.clip(base, 0, 255).astype(np.uint8))
    if kind == 2:  # nhiễu hạt
        a = np.random.randint(40, 140, (h, w, 3)).astype(np.uint8)
        return Image.fromarray(a).filter(ImageFilter.GaussianBlur(1.2))
    if kind == 3:  # gradient sáng dần
        x = np.linspace(40, 170, w, dtype=np.float32)[None, :, None]
        y = np.linspace(0, 40, h, dtype=np.float32)[:, None, None]
        return Image.fromarray(np.clip(np.repeat(x + y, 3, axis=2), 0, 255).astype(np.uint8))
    if kind == 4:  # caro (khăn trải bàn)
        c = random.randint(18, 60)
        yy, xx = np.mgrid[0:h, 0:w]
        m = (((xx // c) + (yy // c)) & 1).astype(np.float32)
        a = np.stack([60 + 70 * m, 50 + 40 * m, 90 + 60 * m], axis=2)
        return Image.fromarray(a.astype(np.uint8))
    # nền sáng gần màu giấy (khó)
    c = random.randint(150, 200)
    return Image.new("RGB", (w, h), (c, c - random.randint(0, 10), c - random.randint(5, 25)))


def doc_image(i):
    w = random.choice([320, 480, 640, 768, 900, 1024])
    h = random.choice([320, 480, 640, 768, 900, 1024])
    im = background(i % 6, w, h)
    d = ImageDraw.Draw(im)
    mx, my = 0.06 * w, 0.06 * h
    q = [
        (mx + random.random() * 0.16 * w, my + random.random() * 0.16 * h),
        (w - mx - random.random() * 0.16 * w, my + random.random() * 0.16 * h),
        (w - mx - random.random() * 0.16 * w, h - my - random.random() * 0.16 * h),
        (mx + random.random() * 0.16 * w, h - my - random.random() * 0.16 * h),
    ]
    pv = random.randint(205, 250)
    d.polygon(q, fill=(pv, pv - random.randint(0, 6), pv - random.randint(0, 18)))
    # dòng chữ + bảng trong trang
    for k in range(random.randint(6, 14)):
        t = 0.12 + 0.76 * k / 14.0
        lx = q[0][0] + (q[3][0] - q[0][0]) * t
        ly = q[0][1] + (q[3][1] - q[0][1]) * t
        rx = q[1][0] + (q[2][0] - q[1][0]) * t
        ry = q[1][1] + (q[2][1] - q[1][1]) * t
        a, b = 0.1, 0.1 + random.random() * 0.8
        d.line([(lx + (rx - lx) * a, ly + (ry - ly) * a), (lx + (rx - lx) * b, ly + (ry - ly) * b)], fill=(35, 35, 40), width=random.choice([1, 2, 3]))
    if i % 3 == 0:  # mảng màu lớn trong trang (ảnh, con dấu)
        cx = (q[0][0] + q[2][0]) / 2
        cy = (q[0][1] + q[2][1]) / 2
        r = 0.08 * min(w, h)
        d.ellipse([(cx - r, cy - r), (cx + r, cy + r)], fill=(190, 40, 40))
    if i % 4 == 1:  # bóng đổ / ánh sáng không đều
        shade = np.linspace(1.0, 0.62, w, dtype=np.float32)[None, :, None]
        im = Image.fromarray(np.clip(np.asarray(im).astype(np.float32) * shade, 0, 255).astype(np.uint8))
    if i % 5 == 2:
        im = im.filter(ImageFilter.GaussianBlur(1.0))
    if i % 7 == 3:  # nhiễu cảm biến
        a = np.asarray(im).astype(np.float32) + np.random.randn(h, w, 3) * 6
        im = Image.fromarray(np.clip(a, 0, 255).astype(np.uint8))
    return im, q


def pattern(kind, w, h):
    if kind == 0:
        return Image.new("RGB", (w, h), (0, 0, 0))
    if kind == 1:
        return Image.new("RGB", (w, h), (255, 255, 255))
    if kind == 2:
        return Image.new("RGB", (w, h), (128, 128, 128))
    if kind == 3:
        return Image.fromarray(np.random.randint(0, 256, (h, w, 3)).astype(np.uint8))
    if kind == 4:
        x = np.linspace(0, 255, w, dtype=np.float32)[None, :, None]
        return Image.fromarray(np.repeat(np.repeat(x, h, axis=0), 3, axis=2).astype(np.uint8))
    if kind == 5:
        c = random.randint(2, 30)
        yy, xx = np.mgrid[0:h, 0:w]
        m = ((((xx // c) + (yy // c)) & 1) * 255).astype(np.uint8)
        return Image.fromarray(np.stack([m, m, m], axis=2))
    if kind == 6:
        ang = random.random() * math.pi
        per = random.randint(3, 40)
        yy, xx = np.mgrid[0:h, 0:w]
        m = ((np.floor((xx * math.cos(ang) + yy * math.sin(ang)) / per).astype(np.int64) & 1) * 215 + 20).astype(np.uint8)
        return Image.fromarray(np.stack([m, m, m], axis=2))
    # khung tối sát mép
    a = np.full((h, w, 3), 240, np.uint8)
    b = random.randint(1, 4)
    a[:b, :, :] = 16
    a[-b:, :, :] = 16
    a[:, :b, :] = 16
    a[:, -b:, :] = 16
    return Image.fromarray(a)


def main():
    n = 0
    # 1) ảnh thử của kho mã (thu về cạnh dài 1024 như ứng dụng)
    for f in sorted(os.listdir(IMAGES)):
        if not f.endswith(".jpg"):
            continue
        im = Image.open(os.path.join(IMAGES, f)).convert("RGB")
        k = 1024.0 / max(im.size)
        im = im.resize((round(im.size[0] * k), round(im.size[1] * k)), Image.BILINEAR)
        save(im, "repo_" + f[:-4])
        n += 1
    # 2) trang giấy tổng hợp, kèm toạ độ góc thật
    with open(os.path.join(OUT, "docs_gt.txt"), "w") as gt:
        for i in range(N_DOCS):
            im, q = doc_image(i)
            name = "doc_%03d" % i
            save(im, name)
            gt.write(name + " " + " ".join("%.2f %.2f" % p for p in q) + "\n")
            n += 1
    # 3) hoa văn + kích thước biên
    sizes = [(16, 16), (17, 16), (16, 17), (31, 33), (64, 64), (419, 421), (420, 420), (421, 419), (640, 480), (480, 640),
             (1024, 768), (1024, 17), (17, 1024), (1000, 30), (30, 1000), (16, 1500), (1500, 16), (333, 777), (801, 600), (123, 457)]
    for si, (w, h) in enumerate(sizes):
        for kind in ((si % 8), ((si + 3) % 8), ((si + 5) % 8)):
            save(pattern(kind, w, h), "pat_%02d_k%d_%dx%d" % (si, kind, w, h))
            n += 1
    # 4) ảnh nhỏ để so từng điểm ảnh sau khi lọc (Enhancer)
    full_sizes = [(4, 4), (5, 7), (8, 8), (9, 8), (40, 3), (3, 40), (97, 131), (200, 150), (150, 200), (256, 256), (300, 420), (640, 400)]
    for i, (w, h) in enumerate(full_sizes):
        if i % 2 == 0 and w >= 64 and h >= 64:
            im, _ = doc_image(100 + i)
            im = im.resize((w, h), Image.BILINEAR)
        else:
            im = pattern([3, 4, 5, 6, 7, 2][i % 6], w, h)
        save(im, "full_%02d_%dx%d" % (i, w, h))
        n += 1
    # trang giấy phẳng (ám vàng, ánh sáng không đều) để so bộ lọc trên ảnh thật hơn
    page = Image.open(os.path.join(IMAGES, "page_flat.jpg")).convert("RGB").resize((496, 702), Image.BILINEAR)
    a = np.asarray(page).astype(np.float32)
    shade = np.linspace(1.0, 0.7, a.shape[1], dtype=np.float32)[None, :, None]
    tint = np.array([1.0, 0.96, 0.86], np.float32)[None, None, :]
    save(Image.fromarray(np.clip(a * shade * tint, 0, 255).astype(np.uint8)), "full_90_page_tint")
    n += 1

    # 5) tứ giác cho Geom: toạ độ là bội số của 0.25 (biểu diễn chính xác ở cả float và double)
    def q4(v):
        return [round(x * 4) / 4.0 for x in v]

    quads = []
    for i in range(400):
        W = random.choice([640, 1200, 3000, 4000])
        H = random.choice([480, 1600, 4000, 3000])
        m = i % 10
        if m == 0:
            v = [random.random() * W for _ in range(8)]
        elif m == 1:
            x = random.random() * 100
            v = [x] * 8
        elif m == 2:
            v = [0, 0, 10, 10, 20, 20, 30, 30]
        elif m == 3:
            v = [0, 0, 100, 0, 100, 0, 0, 0]
        elif m == 4:
            v = [0, 0, 100, 100, 100, 0, 0, 100]
        else:
            v = [random.random() * W * 0.3, random.random() * H * 0.3, W - random.random() * W * 0.3, random.random() * H * 0.3,
                 W - random.random() * W * 0.3, H - random.random() * H * 0.3, random.random() * W * 0.3, H - random.random() * H * 0.3]
            if m == 9:  # xáo thứ tự điểm
                pts = [(v[0], v[1]), (v[2], v[3]), (v[4], v[5]), (v[6], v[7])]
                random.shuffle(pts)
                v = [c for p in pts for c in p]
        focal = -1 if i % 3 == 0 else round(random.uniform(0.3, 2.5) * max(W, H))
        quads.append((W, H, focal, i % 2, random.choice([16, 1000, 3508]), q4(v)))
    # A4 / Letter chụp thẳng và nghiêng nhẹ
    quads.append((4000, 3200, -1, 1, 3508, [100, 100, 2200, 100, 2200, 3070, 100, 3070]))
    quads.append((4000, 3200, -1, 1, 3508, [100, 100, 3070, 100, 3070, 2200, 100, 2200]))
    quads.append((3000, 4000, -1, 1, 3508, [200, 200, 2750, 200, 2750, 3500, 200, 3500]))
    with open(os.path.join(OUT, "quads.txt"), "w") as f:
        for W, H, focal, snap, max_long, v in quads:
            f.write("%d %d %d %d %d %s\n" % (W, H, focal, snap, max_long, " ".join("%.2f" % x for x in v)))

    # 6) JPEG cho PDF
    im, _ = doc_image(7)
    im.resize((1240, 1754)).save(os.path.join(OUT, "jpg", "a_portrait.jpg"), quality=88)
    im.resize((1754, 1240)).save(os.path.join(OUT, "jpg", "b_landscape.jpg"), quality=88)
    im.resize((500, 700)).convert("L").save(os.path.join(OUT, "jpg", "c_gray.jpg"), quality=85)
    im.resize((800, 1100)).save(os.path.join(OUT, "jpg", "d_progressive.jpg"), quality=85, progressive=True)
    im.resize((3000, 40)).save(os.path.join(OUT, "jpg", "e_wide.jpg"), quality=85)
    im.resize((16, 16)).save(os.path.join(OUT, "jpg", "f_tiny.jpg"), quality=85)
    # JPEG có EXIF/ICC lớn trước SOF (kiểm tra đọc phần đầu theo luồng)
    exif = Image.Exif()
    exif[0x010E] = "x" * 60000
    im.resize((900, 1200)).save(os.path.join(OUT, "jpg", "g_bigexif.jpg"), quality=85, exif=exif)
    print("đã sinh %d ảnh, %d tứ giác vào %s" % (n, len(quads), OUT))


if __name__ == "__main__":
    main()
