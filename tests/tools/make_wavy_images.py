#!/usr/bin/env python3
"""Sinh ảnh TỔNG HỢP trang giấy mấp mô / gấp nếp / nhàu để kiểm thử bộ lọc "Làm phẳng" (Flatten).

Mỗi trang là ảnh ĐÃ nắn phẳng theo 4 góc (như ảnh trung gian trong ứng dụng) nhưng mặt giấy còn cong vênh:
  <tên>.ppm            ảnh quan sát: chữ / bảng bị uốn lượn + bóng mờ nếp gấp + vết vân tay, ngón tay ...
  gt/<tên>_flat.ppm    đáp án: trang phẳng, chỉ có nội dung trên nền trắng
  gt/<tên>_map.f32     với mỗi điểm ảnh quan sát (x, y): toạ độ (fx, fy) tương ứng trên trang phẳng
                       (float32 little-endian, toàn bộ fx theo hàng rồi toàn bộ fy)
  gt/<tên>.txt         thông tin đáp án: vùng nội dung, đường kẻ bảng, nếp gấp, vết tay (toạ độ trang phẳng)
(đáp án nằm trong thư mục con gt/ để các công cụ đọc mọi *.ppm của thư mục — DumpCore, algotest --dump — bỏ qua)
Các trang:
  wv_waves    bảng kê, giấy gợn sóng
  wv_folds    văn bản gấp ba (2 nếp ngang + 1 nếp dọc): đường gãy, bậc bóng, vệt nếp tối
  wv_crumple  bảng kê trên giấy nhàu: nhiều mặt gãy sáng tối, nếp nhỏ, mặt giấy lồi lõm
  wv_smudge   văn bản có vết vân tay bẩn + ngón tay giữ giấy ở mép dưới
  wv_curl     trang cong mạnh về phía mép phải (như trang sách)
  wv_tilt     văn bản nghiêng 1,5 độ + gợn nhẹ
  wv_mixed    biểu mẫu: bảng + chữ ký bút xanh + con dấu đỏ + nếp gấp + vân tay
  wv_gray     chữ in xám nhạt (bản photo mờ) trên giấy gợn: phải giữ được chữ
  wv_plain    đối chứng: trang phẳng, sạch (không được làm méo)
  wv_blank    đối chứng: giấy trắng chỉ có nếp gấp (kết quả phải trắng, không lỗi)
Cách chạy: python3 tests/tools/make_wavy_images.py <thư-mục> [cạnh-dài] [thư-mục-ảnh-chụp]
Có tham số thứ ba: ghi thêm ảnh "chụp" giả lập wavy_page.jpg (trang biểu mẫu nhàu nằm trên mặt bàn, phối cảnh nhẹ)
dùng cho kiểm thử giao diện (tests/images/, bản sao ở ios/Resources/TestImages/).
"""
import math
import os
import random
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

OUT = sys.argv[1] if len(sys.argv) > 1 else "out/wavy-in"
LONG = int(sys.argv[2]) if len(sys.argv) > 2 else 1400
PHOTO_DIR = sys.argv[3] if len(sys.argv) > 3 else None
GT = os.path.join(OUT, "gt")
os.makedirs(GT, exist_ok=True)
FS = "/usr/share/fonts/truetype/dejavu/DejaVuSerif.ttf"
FB = "/usr/share/fonts/truetype/dejavu/DejaVuSerif-Bold.ttf"
W, H = int(round(LONG * 0.707)), LONG
K = LONG / 1400.0
INK = (34, 34, 38)
PAPER = (236, 234, 228)

WORDS = ("căn cứ kế hoạch công tác năm nay đơn vị tổ chức kiểm tra thiết bị vật tư theo quy định hiện hành "
         "các bộ phận báo cáo kết quả thực hiện nhiệm vụ đảm bảo an toàn chất lượng tiến độ được giao trong "
         "quý ba đề nghị phòng kỹ thuật tổng hợp số liệu gửi về ban chỉ huy trước ngày mười lăm tháng mười một "
         "để xem xét quyết định bổ sung kinh phí sửa chữa thay thế phụ tùng và trang bị mới cho phân xưởng").split()


def font(path, size):
    try:
        return ImageFont.truetype(path, max(6, int(round(size * K))))
    except Exception:
        return ImageFont.load_default()


def s(v):
    return int(round(v * K))


class Page:
    """Nội dung trang phẳng (vẽ trên nền trắng) + thông tin đáp án."""

    def __init__(self):
        self.im = Image.new("RGB", (W, H), (255, 255, 255))
        self.d = ImageDraw.Draw(self.im)
        self.info = []
        self.box = [W, H, 0, 0]

    def grow(self, x0, y0, x1, y1):
        b = self.box
        self.box = [min(b[0], x0), min(b[1], y0), max(b[2], x1), max(b[3], y1)]

    def text(self, x, y, t, f, fill=INK):
        self.d.text((x, y), t, font=f, fill=fill)
        bb = self.d.textbbox((x, y), t, font=f)
        self.grow(*bb)
        return bb

    def para(self, x, y, width, nlines, size=22, seed=0, fill=INK, gap=36):
        rng = random.Random(seed)
        f = font(FS, size)
        for i in range(nlines):
            words = []
            while True:
                wd = rng.choice(WORDS)
                trial = " ".join(words + [wd])
                if self.d.textlength(trial, font=f) > width:
                    break
                words.append(wd)
            if i == nlines - 1:
                words = words[:max(2, len(words) * 2 // 3)]
            bb = self.text(x, y, " ".join(words), f, fill)
            self.info.append("text %d %d %d" % ((bb[1] + bb[3]) // 2, bb[0], bb[2]))
            y += s(gap)
        return y

    def table(self, top, cols, rows, rh, seed=1):
        d = self.d
        lw = max(1, s(2))
        for r in range(rows + 2):
            yy = top + r * rh
            d.line([(cols[0], yy), (cols[-1], yy)], fill=INK, width=lw)
            self.info.append("hrule %d %d %d" % (yy, cols[0], cols[-1]))
        for c in cols:
            d.line([(c, top), (c, top + (rows + 1) * rh)], fill=INK, width=lw)
            self.info.append("vrule %d %d %d" % (c, top, top + (rows + 1) * rh))
        self.grow(cols[0], top, cols[-1], top + (rows + 1) * rh)
        heads = ["TT", "Tên thiết bị", "Mã hiệu", "ĐVT", "SL"]
        for i, hd in enumerate(heads[:len(cols) - 1]):
            self.text(cols[i] + s(8), top + s(9), hd, font(FB, 20))
        names = ["Máy in laser", "Màn hình 24 inch", "Bàn phím", "Chuột không dây", "Ổ cứng 1TB", "Bộ phát Wi-Fi",
                 "Máy chiếu", "Loa hội nghị", "Bộ lưu điện", "Cáp mạng", "Máy quét", "Đèn bàn", "Tủ hồ sơ", "Ghế xoay"]
        f = font(FS, 22)
        for r in range(rows):
            yy = top + (r + 1) * rh + s(9)
            self.text(cols[0] + s(14), yy, str(r + 1), f)
            self.text(cols[1] + s(8), yy, names[(r + seed) % len(names)], f)
            if len(cols) > 3:
                self.text(cols[2] + s(8), yy, "MH-%03d" % (r * 7 + 3), f)
            if len(cols) > 4:
                self.text(cols[3] + s(8), yy, "Cái", f)
            if len(cols) > 5:
                self.text(cols[4] + s(20), yy, str((r * 3) % 9 + 1), f)
        return top + (rows + 1) * rh


def table_page(rows=14, seed=1):
    p = Page()
    p.text(s(120), s(90), "ĐƠN VỊ MẪU", font(FB, 26))
    p.text(s(430), s(180), "BẢNG KÊ THIẾT BỊ", font(FB, 34))
    y = p.para(s(110), s(260), s(780), 2, seed=seed)
    cols = [s(100), s(170), s(520), s(680), s(800), s(900)]
    yb = p.table(y + s(24), cols, rows, s(44), seed=seed) + s(60)
    p.text(s(150), yb, "NGƯỜI LẬP", font(FB, 22))
    p.text(s(640), yb, "THỦ TRƯỞNG", font(FB, 22))
    return p


def text_page(seed=2, fill=INK):
    p = Page()
    p.text(s(330), s(100), "THÔNG BÁO KẾT QUẢ", font(FB, 32), fill)
    y = s(190)
    for k in range(4):
        y = p.para(s(100), y, s(790), 6 if k % 2 == 0 else 5, seed=seed * 10 + k, fill=fill) + s(30)
    p.text(s(600), y + s(20), "TRƯỞNG PHÒNG", font(FB, 22), fill)
    return p


def stamp(d, cx, cy, r, color=(205, 52, 48)):
    d.ellipse([cx - r, cy - r, cx + r, cy + r], outline=color, width=max(2, s(5)))
    d.ellipse([cx - r * 0.72, cy - r * 0.72, cx + r * 0.72, cy + r * 0.72], outline=color, width=max(1, s(3)))
    pts = []
    for i in range(10):
        a = -math.pi / 2 + i * math.pi / 5
        rr = r * (0.5 if i % 2 == 0 else 0.2)
        pts.append((cx + rr * math.cos(a), cy + rr * math.sin(a)))
    d.polygon(pts, fill=color)


def signature(d, x, y, w, h, color, seed):
    rng = random.Random(seed)
    pts = []
    n = 30
    for i in range(n):
        t = i / (n - 1.0)
        pts.append((x + w * t + rng.uniform(-0.03, 0.03) * w,
                    y + h * (0.5 + 0.45 * math.sin(t * 11.0 + seed)) + rng.uniform(-0.06, 0.06) * h))
    d.line(pts, fill=color, width=max(2, s(3)), joint="curve")


# ------------------------------------------------------------------ biến dạng (toạ độ ảnh quan sát -> trang phẳng)

YY, XX = np.mgrid[0:H, 0:W].astype(np.float64)


def gauss(cx, cy, sg):
    return np.exp(-((XX - cx) ** 2 + (YY - cy) ** 2) / (2.0 * sg * sg))


def bumps(rng, n, amp, smin, smax):
    dx = np.zeros((H, W))
    dy = np.zeros((H, W))
    for _ in range(n):
        cx, cy = rng.uniform(0, W), rng.uniform(0, H)
        sg = rng.uniform(smin, smax) * W
        g = gauss(cx, cy, sg)
        dx += rng.uniform(-amp, amp) * 0.6 * g
        dy += rng.uniform(-amp, amp) * g
    return dx, dy


def waves(rng, amp, lam):
    p1, p2, p3 = rng.uniform(0, 6.28, 3)
    dy = amp * np.sin(2 * math.pi * XX / lam + p1) * (0.65 + 0.35 * np.sin(2 * math.pi * YY / (1.7 * lam) + p2))
    dx = 0.35 * amp * np.sin(2 * math.pi * YY / (1.3 * lam) + p3)
    return dx, dy


# ------------------------------------------------------------------ bóng / nếp (trên trang phẳng, nhân vào màu giấy)

def soft_line_dist(x0, y0, x1, y1):
    """Khoảng cách có dấu từ mọi điểm (trang phẳng) tới đường thẳng qua 2 điểm."""
    nx, ny = y1 - y0, -(x1 - x0)
    ln = math.hypot(nx, ny)
    return ((XX - x0) * nx + (YY - y0) * ny) / ln


def crease(shade, info, x0, y0, x1, y1, step=0.86, depth=0.30, width=2.6):
    """Nếp gấp: một bên tối hơn (bậc bóng mềm), vệt nếp tối mảnh + vệt sáng sát bên."""
    dist = soft_line_dist(x0, y0, x1, y1)
    side = 1.0 / (1.0 + np.exp(-dist / max(1.0, s(3))))
    shade *= 1.0 - (1.0 - step) * side
    shade *= 1.0 - depth * np.exp(-(dist / (width * K)) ** 2)
    shade *= 1.0 + 0.05 * np.exp(-((dist + s(4)) / (2.0 * K)) ** 2)
    info.append("crease %d %d %d %d" % (x0, y0, x1, y1))


def facets(rng, n, lo):
    """Mặt giấy nhàu: các mảng Voronoi sáng tối khác nhau, mép mảng hơi mềm."""
    pts = [(rng.uniform(0, W), rng.uniform(0, H)) for _ in range(n)]
    val = [rng.uniform(lo, 1.0) for _ in range(n)]
    best = np.full((H, W), 1e18)
    out = np.ones((H, W))
    for (px, py), v in zip(pts, val):
        d2 = (XX - px) ** 2 + (YY - py) ** 2
        m = d2 < best
        best[m] = d2[m]
        out[m] = v
    img = Image.fromarray(np.clip(out * 255, 0, 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(max(1, s(2))))
    return np.asarray(img).astype(np.float64) / 255.0


def fingerprint(alb, info, cx, cy, rx, ry, ang, strength=0.22, seed=0):
    """Vết vân tay bẩn: vân elip đồng tâm, tương phản thấp, màu nâu xám."""
    rng = np.random.RandomState(seed)
    c, sn = math.cos(ang), math.sin(ang)
    u = ((XX - cx) * c + (YY - cy) * sn) / rx
    v = (-(XX - cx) * sn + (YY - cy) * c) / ry
    r = np.sqrt(u * u + v * v)
    env = np.clip(1.0 - r * r, 0, 1) ** 0.7
    period = 5.2 * K / ((rx + ry) / 2.0)
    ridge = 0.5 + 0.5 * np.cos(2 * math.pi * (r + 0.04 * np.sin(6 * np.arctan2(v, u))) / period)
    noise = rng.rand(H, W)
    a = strength * env * ridge * (0.6 + 0.4 * noise)
    alb[:, :, 0] *= 1.0 - a * 0.80
    alb[:, :, 1] *= 1.0 - a * 0.92
    alb[:, :, 2] *= 1.0 - a * 1.05
    info.append("print %d %d %d %d" % (cx, cy, max(rx, ry), min(rx, ry)))


def finger(im, cx, cy, rx, ry, ang, seed=3):
    """Ngón tay giữ giấy (elip màu da, mặt khuất tối, móng, bóng mềm trên giấy)."""
    rng = random.Random(seed)
    layer = Image.new("RGBA", im.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    sh = Image.new("L", im.size, 0)
    ImageDraw.Draw(sh).ellipse([cx - rx * 1.18, cy - ry * 1.12, cx + rx * 1.18, cy + ry * 1.12], fill=120)
    sh = sh.filter(ImageFilter.GaussianBlur(max(2, s(9))))
    a = np.asarray(im).astype(np.float32)
    a *= (1.0 - np.asarray(sh).astype(np.float32)[:, :, None] / 255.0 * 0.45)
    im = Image.fromarray(np.clip(a, 0, 255).astype(np.uint8))
    skin = (196 + rng.randint(-8, 8), 138 + rng.randint(-8, 8), 112 + rng.randint(-8, 8))
    d.ellipse([cx - rx, cy - ry, cx + rx, cy + ry], fill=skin + (255,))
    d.ellipse([cx - rx * 0.2, cy - ry * 0.9, cx + rx, cy + ry * 0.9], fill=(150, 98, 78, 255))
    d.ellipse([cx - rx * 0.75, cy - ry * 0.55, cx - rx * 0.1, cy + ry * 0.1], fill=(222, 178, 160, 255))
    layer = layer.rotate(ang, center=(cx, cy), resample=Image.BILINEAR).filter(ImageFilter.GaussianBlur(max(1, s(2))))
    im = im.convert("RGBA")
    im.alpha_composite(layer)
    return im.convert("RGB")


def sample(img, fx, fy, fill):
    """Lấy mẫu song tuyến tính img (H x W x C) tại (fx, fy); ngoài ảnh = fill."""
    hh, ww = img.shape[:2]
    x0 = np.floor(fx).astype(np.int64)
    y0 = np.floor(fy).astype(np.int64)
    ax = (fx - x0)[..., None]
    ay = (fy - y0)[..., None]
    pad = np.empty((hh + 2, ww + 2, img.shape[2]), np.float64)
    pad[:] = fill
    pad[1:-1, 1:-1] = img
    xi = np.clip(x0 + 1, 0, ww)
    yi = np.clip(y0 + 1, 0, hh)
    xj = np.clip(x0 + 2, 0, ww + 1)
    yj = np.clip(y0 + 2, 0, hh + 1)
    xi = np.where(x0 + 1 < 0, 0, xi)
    yi = np.where(y0 + 1 < 0, 0, yi)
    out = (pad[yi, xi] * (1 - ax) * (1 - ay) + pad[yi, xj] * ax * (1 - ay)
           + pad[yj, xi] * (1 - ax) * ay + pad[yj, xj] * ax * ay)
    return out


def render(name, page, dx, dy, shade=None, prints=(), light=(0.88, 1.0), noise=1.6, finger_args=None, seed=0,
           extra=None):
    """Ghép trang: albedo = nội dung x màu giấy x bóng (trên trang phẳng) -> uốn theo (dx, dy) -> ánh sáng, nhiễu."""
    rng = np.random.RandomState(seed)
    flat = np.asarray(page.im).astype(np.float64)
    alb = flat / 255.0 * np.array(PAPER, np.float64)
    info = list(page.info)
    bx = page.box
    info.insert(0, "box %d %d %d %d" % (bx[0], bx[1], bx[2], bx[3]))
    for pr in prints:
        fingerprint(alb, info, *pr)
    if shade is not None:
        alb *= shade[:, :, None]
    fx = XX + dx
    fy = YY + dy
    obs = sample(alb, fx, fy, np.array(PAPER, np.float64) * 0.97)
    lo, hi = light
    g = lo + (hi - lo) * (0.55 * XX / W + 0.45 * YY / H)
    obs = obs * g[:, :, None]
    im = Image.fromarray(np.clip(obs, 0, 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(0.55 * K))
    if finger_args is not None:
        im = finger(im, *finger_args)
        info.append("finger %d %d %d %d" % (finger_args[0], finger_args[1], finger_args[2], finger_args[3]))
    a = np.asarray(im).astype(np.float64) + rng.randn(H, W, 1) * noise
    im = Image.fromarray(np.clip(a, 0, 255).astype(np.uint8))
    if extra is not None:
        im = extra(im)
    im.save(os.path.join(OUT, name + ".ppm"))
    page.im.save(os.path.join(GT, name + "_flat.ppm"))
    with open(os.path.join(GT, name + "_map.f32"), "wb") as f:
        f.write(fx.astype("<f4").tobytes())
        f.write(fy.astype("<f4").tobytes())
    with open(os.path.join(GT, name + ".txt"), "w") as f:
        f.write("size %d %d\n" % (W, H))
        f.write("\n".join(info) + "\n")
    return im


def main():
    rng = np.random.RandomState(20261113)
    random.seed(20261113)
    one = np.ones((H, W))

    # 1. bảng kê trên giấy gợn sóng
    p = table_page(seed=1)
    dx, dy = waves(rng, 0.011 * H, 0.62 * W)
    shade = 1.0 - 0.07 * (1 + np.sin(2 * math.pi * XX / (0.62 * W) + 1.0)) / 2.0
    render("wv_waves", p, dx, dy, shade=shade, seed=1)

    # 2. văn bản gấp ba: nếp ngang ở 1/3, 2/3 + nếp dọc giữa trang
    p = text_page(seed=2)
    sh = one.copy()
    info = p.info
    crease(sh, info, 0, H / 3.0, W, H / 3.0 + s(6), step=0.84)
    crease(sh, info, 0, 2 * H / 3.0, W, 2 * H / 3.0 - s(8), step=0.9, depth=0.36)
    crease(sh, info, W / 2.0 + s(10), 0, W / 2.0 - s(6), H, step=0.88, depth=0.26)
    dy = 0.032 * np.abs(XX - W / 2.0) - 0.032 * W / 4.0
    dx = 0.022 * np.abs(YY - H / 3.0) * (YY < H / 2) + 0.022 * np.abs(YY - 2 * H / 3.0) * (YY >= H / 2) - 0.01 * H / 3
    render("wv_folds", p, dx, dy, shade=sh, seed=2)

    # 3. bảng kê trên giấy nhàu
    p = table_page(seed=3)
    sh = facets(rng, 30, 0.8)
    for _ in range(18):
        x0, y0 = rng.uniform(0, W), rng.uniform(0, H)
        ang = rng.uniform(0, math.pi)
        ln = rng.uniform(0.1, 0.35) * W
        crease(sh, p.info, x0, y0, x0 + ln * math.cos(ang), y0 + ln * math.sin(ang), step=rng.uniform(0.9, 1.0),
               depth=rng.uniform(0.12, 0.3), width=rng.uniform(1.6, 3.2))
    bx, by = bumps(rng, 12, 0.014 * H, 0.06, 0.16)
    wx, wy = waves(rng, 0.004 * H, 0.45 * W)
    render("wv_crumple", p, bx + wx, by + wy, shade=sh, light=(0.84, 1.0), seed=3)

    # 4. văn bản có vết vân tay + ngón tay giữ giấy mép dưới
    p = text_page(seed=4)
    prints = [(s(250), s(420), s(34), s(46), 0.3, 0.26, 41), (s(720), s(830), s(30), s(42), -0.5, 0.2, 42),
              (s(160), s(1180), s(36), s(48), 0.9, 0.3, 43), (s(560), s(260), s(28), s(40), 1.4, 0.16, 44)]
    dx, dy = waves(rng, 0.005 * H, 0.8 * W)
    render("wv_smudge", p, dx, dy, prints=prints, finger_args=(s(520), H - s(8), s(56), s(124), 84, 5), seed=4)

    # 5. trang cong mạnh về phía mép phải (gáy sách)
    p = table_page(rows=12, seed=5)
    t = np.clip((XX - 0.55 * W) / (0.45 * W), 0, None)
    dy = 0.045 * H * t * t * (YY - H / 2.0) / (H / 2.0) + 0.012 * H * t * t
    dx = 0.05 * W * t * t
    sh = 1.0 - 0.18 * t * t
    render("wv_curl", p, dx, dy, shade=sh, seed=5)

    # 6. văn bản nghiêng 1,5 độ + gợn nhẹ
    p = text_page(seed=6)
    th = math.radians(1.5)
    wx, wy = waves(rng, 0.003 * H, 0.7 * W)
    dx = -math.sin(th) * (YY - H / 2.0) + (math.cos(th) - 1) * (XX - W / 2.0) + wx
    dy = math.sin(th) * (XX - W / 2.0) + (math.cos(th) - 1) * (YY - H / 2.0) + wy
    render("wv_tilt", p, dx, dy, seed=6)

    # 7. biểu mẫu: bảng + chữ ký xanh + dấu đỏ + nếp gấp + vân tay
    p = table_page(rows=10, seed=7)
    stamp(p.d, s(700), s(1170), s(80))
    signature(p.d, s(150), s(1150), s(230), s(70), (36, 62, 170), 7)
    p.info.append("stamp %d %d %d" % (s(700), s(1170), s(80)))
    p.info.append("sign %d %d %d %d" % (s(150), s(1150), s(380), s(1220)))
    sh = one.copy()
    crease(sh, p.info, 0, H / 2.0 + s(10), W, H / 2.0 - s(12), step=0.85, depth=0.34)
    crease(sh, p.info, W * 0.48, 0, W * 0.52, H, step=0.9, depth=0.28)
    bx, by = bumps(rng, 6, 0.01 * H, 0.1, 0.2)
    dy = by + 0.025 * np.abs(XX - W / 2.0) - 0.025 * W / 4.0
    dx = bx + 0.018 * np.abs(YY - H / 2.0) - 0.018 * H / 4.0
    prints = [(s(820), s(420), s(32), s(44), 0.4, 0.24, 71), (s(300), s(900), s(30), s(40), -0.3, 0.2, 72)]
    render("wv_mixed", p, dx, dy, shade=sh, prints=prints, seed=7)

    # 8. chữ in xám nhạt (bản photo mờ) trên giấy gợn
    p = text_page(seed=8, fill=(112, 112, 116))
    dx, dy = waves(rng, 0.008 * H, 0.55 * W)
    render("wv_gray", p, dx, dy, seed=8)

    # 9. đối chứng: trang phẳng sạch
    p = table_page(seed=9)
    render("wv_plain", p, np.zeros((H, W)), np.zeros((H, W)), seed=9)

    # 10. đối chứng: giấy trắng chỉ có nếp gấp
    p = Page()
    sh = one.copy()
    crease(sh, p.info, 0, H * 0.4, W, H * 0.42, step=0.85, depth=0.34)
    crease(sh, p.info, W * 0.3, 0, W * 0.35, H, step=0.9, depth=0.3)
    render("wv_blank", p, np.zeros((H, W)), 0.01 * np.abs(XX - W * 0.32), shade=sh, seed=10)
    print("đã ghi 10 trang %dx%d vào %s" % (W, H, OUT))
    if PHOTO_DIR:
        photo()
        print("đã ghi ảnh chụp giả lập wavy_page.jpg vào %s" % PHOTO_DIR)


def persp_coeffs(dst, src):
    """Hệ số cho Image.transform(PERSPECTIVE): ánh xạ điểm ảnh đích -> điểm ảnh nguồn."""
    m = []
    for (x, y), (u, v) in zip(dst, src):
        m.append([x, y, 1, 0, 0, 0, -u * x, -u * y])
        m.append([0, 0, 0, x, y, 1, -v * x, -v * y])
    a = np.array(m, dtype=np.float64)
    b = np.array([c for p in src for c in p], dtype=np.float64)
    return np.linalg.solve(a, b).tolist()


def photo(out_w=1200, out_h=1600):
    """Ảnh "chụp" giả lập: biểu mẫu nhàu (wv_mixed) nằm trên mặt bàn sẫm, phối cảnh nhẹ."""
    page = Image.open(os.path.join(OUT, "wv_mixed.ppm")).convert("RGB")
    m = s(150)
    rng = np.random.RandomState(53)
    cw, ch = W + 2 * m, H + 2 * m
    desk = np.zeros((ch, cw, 3), np.float32)
    desk[:] = (62, 48, 40)
    desk += (np.sin(np.arange(ch)[:, None, None] / max(3.0, s(23))) * 5.0 + rng.randn(ch, 1, 1) * 2.5)
    canvas = Image.fromarray(np.clip(desk, 0, 255).astype(np.uint8))
    shadow = Image.new("L", (cw, ch), 0)
    ImageDraw.Draw(shadow).rectangle([m + s(8), m + s(10), m + W + s(8), m + H + s(10)], fill=110)
    shadow = shadow.filter(ImageFilter.GaussianBlur(max(2, s(8))))
    a = np.asarray(canvas).astype(np.float32) * (1.0 - np.asarray(shadow).astype(np.float32)[:, :, None] / 255.0 * 0.5)
    canvas = Image.fromarray(np.clip(a, 0, 255).astype(np.uint8))
    canvas.paste(page, (m, m))
    dst = [(34, 40), (out_w - 50, 28), (out_w - 30, out_h - 36), (46, out_h - 60)]
    src = [(m * 0.42, m * 0.45), (cw - m * 0.42, m * 0.45), (cw - m * 0.42, ch - m * 0.45), (m * 0.42, ch - m * 0.45)]
    out = canvas.transform((out_w, out_h), Image.PERSPECTIVE, persp_coeffs(dst, src), Image.BICUBIC, fillcolor=(56, 44, 36))
    arr = np.asarray(out).astype(np.float32) + rng.randn(out_h, out_w, 1) * 1.6
    os.makedirs(PHOTO_DIR, exist_ok=True)
    Image.fromarray(np.clip(arr, 0, 255).astype(np.uint8)).save(os.path.join(PHOTO_DIR, "wavy_page.jpg"), quality=88)


if __name__ == "__main__":
    main()
