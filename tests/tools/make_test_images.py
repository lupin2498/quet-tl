#!/usr/bin/env python3
"""Sinh ảnh thử: trang giấy mẫu (nội dung chung chung) đặt nghiêng trên nhiều loại nền.
Ghi ra tests/images/*.jpg và tests/images/gt.txt (toạ độ 4 góc thật)."""
import json, random, sys, os
import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

OUT = sys.argv[1] if len(sys.argv) > 1 else "tests/images"
os.makedirs(OUT, exist_ok=True)
random.seed(11); np.random.seed(11)
FS = "/usr/share/fonts/truetype/dejavu/DejaVuSerif.ttf"
FB = "/usr/share/fonts/truetype/dejavu/DejaVuSerif-Bold.ttf"

def page():
    W, H = 1240, 1754  # A4 ~150dpi
    im = Image.new("RGB", (W, H), (250, 250, 247))
    d = ImageDraw.Draw(im)
    f = ImageFont.truetype(FS, 26); fb = ImageFont.truetype(FB, 30); ft = ImageFont.truetype(FB, 40)
    d.text((90, 90), "CÔNG TY TNHH MẪU", font=fb, fill=(20, 20, 20))
    d.text((90, 135), "Số: 123/BB-2026", font=f, fill=(40, 40, 40))
    d.text((700, 90), "CỘNG HOÀ XÃ HỘI CHỦ NGHĨA VIỆT NAM", font=ImageFont.truetype(FB, 22), fill=(20, 20, 20))
    d.text((760, 125), "Độc lập - Tự do - Hạnh phúc", font=f, fill=(30, 30, 30))
    d.text((360, 260), "BIÊN BẢN BÀN GIAO THIẾT BỊ", font=ft, fill=(10, 10, 10))
    y = 360
    para = ("Hôm nay, ngày 31 tháng 10 năm 2026, tại văn phòng công ty, hai bên tiến hành bàn giao "
            "các thiết bị dưới đây. Bên nhận đã kiểm tra số lượng, chất lượng và đồng ý tiếp nhận.")
    words = para.split(); line = ""
    for w in words:
        t = (line + " " + w).strip()
        if d.textlength(t, font=f) > W - 180:
            d.text((90, y), line, font=f, fill=(30, 30, 30)); y += 40; line = w
        else:
            line = t
    d.text((90, y), line, font=f, fill=(30, 30, 30)); y += 70
    cols = [90, 170, 640, 800, 960, 1150]
    heads = ["STT", "Tên thiết bị", "Mã hiệu", "ĐVT", "Số lượng"]
    rows = 12
    top = y
    for r in range(rows + 2):
        d.line([(cols[0], y + r * 48), (cols[-1], y + r * 48)], fill=(30, 30, 30), width=2)
    for c in cols:
        d.line([(c, top), (c, top + (rows + 1) * 48)], fill=(30, 30, 30), width=2)
    for i, h in enumerate(heads):
        d.text((cols[i] + 10, top + 10), h, font=ImageFont.truetype(FB, 24), fill=(20, 20, 20))
    names = ["Máy in laser", "Màn hình 24 inch", "Bàn phím", "Chuột không dây", "Ổ cứng 1TB", "Bộ phát Wi-Fi",
             "Máy chiếu", "Loa hội nghị", "Bộ lưu điện", "Cáp mạng", "Máy quét", "Đèn bàn"]
    for r in range(rows):
        yy = top + (r + 1) * 48 + 10
        d.text((cols[0] + 15, yy), str(r + 1), font=f, fill=(30, 30, 30))
        d.text((cols[1] + 10, yy), names[r], font=f, fill=(30, 30, 30))
        d.text((cols[2] + 10, yy), "MH-%03d" % (r * 7 + 3), font=f, fill=(30, 30, 30))
        d.text((cols[3] + 10, yy), "Cái", font=f, fill=(30, 30, 30))
        d.text((cols[4] + 30, yy), str((r * 3) % 9 + 1), font=f, fill=(30, 30, 30))
    y = top + (rows + 1) * 48 + 90
    d.text((150, y), "BÊN GIAO", font=fb, fill=(20, 20, 20)); d.text((850, y), "BÊN NHẬN", font=fb, fill=(20, 20, 20))
    d.line([(860, y + 120), (1010, y + 80)], fill=(30, 60, 160), width=3)
    d.ellipse([(170, y + 60), (290, y + 180)], outline=(200, 40, 40), width=5)
    return im

def background(kind, W, H):
    if kind == "wood":
        a = np.zeros((H, W, 3), np.float32); a[:] = [125, 85, 52]
        a += (np.sin(np.linspace(0, 70, W))[None, :] * 14 + np.random.randn(H, W) * 5)[..., None]
        return a
    if kind == "dark":
        return np.ones((H, W, 3), np.float32) * [38, 40, 46] + np.random.randn(H, W, 1) * 5
    if kind == "gray":
        return np.ones((H, W, 3), np.float32) * [118, 120, 124] + np.random.randn(H, W, 1) * 4
    if kind == "beige":
        return np.ones((H, W, 3), np.float32) * [200, 188, 166] + np.random.randn(H, W, 1) * 4
    if kind == "blue":
        return np.ones((H, W, 3), np.float32) * [40, 70, 120] + np.random.randn(H, W, 1) * 5
    im = Image.new("RGB", (W, H), (90, 92, 98)); d = ImageDraw.Draw(im)
    for _ in range(50):
        x, y = random.randint(0, W), random.randint(0, H)
        d.rectangle([x, y, x + random.randint(30, 260), y + random.randint(30, 260)], fill=tuple(random.randint(30, 200) for _ in range(3)))
    return np.asarray(im).astype(np.float32)

def coeffs(src, dst):
    A, B = [], []
    for (x, y), (u, v) in zip(dst, src):
        A.append([x, y, 1, 0, 0, 0, -u * x, -u * y]); B.append(u)
        A.append([0, 0, 0, x, y, 1, -v * x, -v * y]); B.append(v)
    return np.linalg.solve(np.array(A, float), np.array(B, float))

pg = page()
pg.save(os.path.join(OUT, "page_flat.jpg"), quality=90)
W, H = 1200, 1600
gt = []
for i, kind in enumerate(["wood", "dark", "gray", "beige", "blue", "clutter"]):
    b = background(kind, W, H)
    s = random.uniform(0.62, 0.82)
    ph = H * s; pw = ph * pg.size[0] / pg.size[1]
    cx = W / 2 + random.uniform(-60, 60); cy = H / 2 + random.uniform(-60, 60)
    ang = np.radians(random.uniform(-12, 12))
    rect = np.array([[-pw / 2, -ph / 2], [pw / 2, -ph / 2], [pw / 2, ph / 2], [-pw / 2, ph / 2]])
    R = np.array([[np.cos(ang), -np.sin(ang)], [np.sin(ang), np.cos(ang)]])
    pts = rect @ R.T + [cx, cy]
    k = random.uniform(0.02, 0.12)
    tm = (pts[0] + pts[1]) / 2; pts[0] += (tm - pts[0]) * k; pts[1] += (tm - pts[1]) * k
    c = coeffs([(0, 0), (pg.size[0], 0), (pg.size[0], pg.size[1]), (0, pg.size[1])], [tuple(p) for p in pts])
    warped = pg.transform((W, H), Image.PERSPECTIVE, tuple(c), Image.BILINEAR)
    mask = Image.new("L", pg.size, 255).transform((W, H), Image.PERSPECTIVE, tuple(c), Image.BILINEAR)
    m = np.asarray(mask).astype(np.float32)[..., None] / 255
    img = b * (1 - m) + np.asarray(warped).astype(np.float32) * 0.93 * m
    yy, xx = np.mgrid[0:H, 0:W]
    img *= (1.0 - 0.22 * (xx / W) - 0.1 * (yy / H))[..., None]
    img += np.random.randn(H, W, 1) * 2.5
    out = Image.fromarray(np.clip(img, 0, 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(0.7))
    name = "doc_%d_%s.jpg" % (i + 1, kind)
    out.save(os.path.join(OUT, name), quality=88)
    gt.append(name + " " + " ".join("%.1f" % v for p in pts for v in p))
open(os.path.join(OUT, "gt.txt"), "w").write("\n".join(gt) + "\n")
print("\n".join(gt))
