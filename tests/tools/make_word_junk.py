#!/usr/bin/env python3
"""Sinh trang quét TỔNG HỢP có kết quả "nhận dạng chữ" đầy rác để đối chiếu lõi Word (DocLayout + DocxWriter) giữa bản
Java và bản Swift trên các nhánh lọc rác (trang của make_word_pages.py có kết quả nhận dạng sạch nên không đi qua).

Mỗi trang <tên>.ppm kèm <tên>.words.tsv (x0 y0 x1 y1 dòng chữ [độ-tin-cậy], ngăn bằng tab — như make_word_pages.py):
  wj_page_*   trang chữ + bảng kẻ + con dấu đỏ + nét bút xanh, các từ thật xê dịch và lẫn rác: từ trùng nhau, khung trùm
              lên từ bên cạnh, ký tự đường kẻ dính vào đầu / cuối từ ("|Máy", "2_"), từ chỉ gồm ký hiệu kẻ vạch, khung
              NaN / vô cực / âm / ngoài ảnh / đảo ngược / diện tích 0, độ tin cậy thấp hoặc bất thường, số dòng bất thường,
              chữ có dấu tổ hợp / emoji / ký tự không có trong bảng số đo phông
  wj_skew_*   như trên nhưng trang nghiêng (khử nghiêng theo đường kẻ hoặc theo dòng chữ)
  wj_noise, wj_white, wj_black, wj_grid   trang nhiễu / trắng / đen / chỉ có lưới kẻ với các từ ngẫu nhiên
  wj_dups     hàng nghìn từ trùng nhau
  wj_small_*, wj_flat_*   ảnh rất nhỏ / rất dẹt
Không có đáp án: chỉ dùng để so hai bản (DumpCore --word, algotest --dump-word, compare_core.py --word) và kiểm tra file
.docx ghi ra (check_docx_zip.py). Kết quả sinh ra cố định theo hạt giống.

Cách chạy: python3 tests/tools/make_word_junk.py <thư-mục> [cạnh-dài=760]
"""
import math
import os
import random
import sys

from PIL import Image, ImageDraw, ImageFilter, ImageFont

OUT = sys.argv[1] if len(sys.argv) > 1 else "out/word-junk"
LONG = int(sys.argv[2]) if len(sys.argv) > 2 else 760
os.makedirs(OUT, exist_ok=True)
FONT_DIRS = ["/usr/share/fonts/truetype/liberation/", "/usr/share/fonts/truetype/dejavu/"]
FONT_NAMES = {(False, False): ["LiberationSerif-Regular.ttf", "DejaVuSerif.ttf"], (True, False): ["LiberationSerif-Bold.ttf", "DejaVuSerif-Bold.ttf"],
              (False, True): ["LiberationSerif-Italic.ttf", "DejaVuSerif.ttf"]}
VI = ("CỘNG HOÀ XÃ HỘI CHỦ NGHĨA VIỆT NAM Độc lập Tự do Hạnh phúc Biên bản kiểm kê tài sản số 12/BB ngày tháng năm "
      "Kính gửi: Ban giám đốc công ty thiết bị về việc đề nghị thanh toán hợp đồng Máy quét Bàn phím Chuột quang "
      "Tổng cộng 1.250.000 đồng người lập biểu ký tên ghi rõ họ ỵ ợ ặ ễ ữ").split(" ")
# chữ lạ: dấu tổ hợp (dạng tách rời), emoji, ký tự ngoài bảng số đo, ký hiệu kẻ vạch, khoảng trắng / ký tự điều khiển
ODD = ["|", "_", "l", "I", "1", "—", "||", "|_", "__", "─┼─", "¦", "=", "~~", "'", "¬¯", ".,", "(", "[]",
       "é", "ậ", "́", "̣́̂", "Việt", "😀", "😀😀😀", "👨‍👩‍👧", "中文字", "日本",
       "​‌", "﻿", "a\u0000b", "\u0001\u0002", " x ", "  ", "<&>\"'", "W" * 300, "i" * 700, "Ω≈ç√", "ﬁ", "№5", "x²",
       "𝔘𝔫𝔦", "\U0001F1FB\U0001F1F3", "ỵ", "Ỵ", "ạặậ", "Q,;", "{}", "a b", "…", "•", "‰", "Ǻǻ", "ḁḀ", "0", "00", "1.250.000"]
SPECIAL = ["NaN", "Infinity", "-Infinity", "-1e30", "1e30", "3.4028235e38", "-0.0", "-5", "1e-30"]


def font(size, bold=False, italic=False):
    for d in FONT_DIRS:
        for n in FONT_NAMES[(bold, italic)]:
            if os.path.exists(d + n):
                return ImageFont.truetype(d + n, size)
    return ImageFont.load_default()


class Page:
    def __init__(self, name, w, h, rnd, bg=(255, 255, 255)):
        self.name, self.w, self.h, self.rnd = name, w, h, rnd
        self.im = Image.new("RGB", (w, h), bg)
        self.d = ImageDraw.Draw(self.im)
        self.words = []   # (x0, y0, x1, y1, dòng, chữ, độ tin cậy hoặc None) — các số là chuỗi đã định dạng
        self.line_no = 0

    def add(self, x0, y0, x1, y1, line, text, conf=None):
        def f(v):
            return v if isinstance(v, str) else "%.1f" % v
        self.words.append((f(x0), f(y0), f(x1), f(y1), str(line), text, conf))

    def text_line(self, x, base, words, size, bold=False, italic=False, color=(28, 28, 32), jitter=1.5, gap=None):
        """Một dòng chữ; khung từ theo mực thật + xê dịch ngẫu nhiên. Trả về x sau từ cuối."""
        f = font(size, bold, italic)
        self.line_no += 1
        sp = f.getlength(" ") if gap is None else gap
        r = self.rnd
        for wd in words:
            box = self.d.textbbox((x, base), wd, font=f, anchor="ls")
            self.d.text((x, base), wd, font=f, fill=color, anchor="ls")
            j = lambda: (r.random() - 0.5) * 2 * jitter
            conf = None if r.random() < 0.5 else "%.2f" % (0.55 + 0.45 * r.random())
            self.add(box[0] + j(), box[1] - 2 + j(), box[2] + j(), box[3] + 2 + j(), self.line_no, wd, conf)
            x += f.getlength(wd) + sp
        return x

    def junk_words(self, n):
        """n từ rác: khung / chữ / độ tin cậy / số dòng bất thường."""
        r = self.rnd
        for _ in range(n):
            x0, y0 = r.random() * self.w, r.random() * self.h
            x1, y1 = x0 + 2 + r.random() * 90, y0 + 3 + r.random() * 40
            v = [x0, y0, x1, y1]
            kind = r.randrange(12)
            if kind == 0:
                v[r.randrange(4)] = r.choice(SPECIAL)
            elif kind == 1:
                v = [r.choice(SPECIAL) if r.random() < 0.5 else x for x in v]
            elif kind == 2:
                v = [x1, y1, x0, y0]                                  # đảo ngược
            elif kind == 3:
                v = [x0, y0, x0, y1] if r.random() < 0.5 else [x0, y0, x1, y0 + 1.5]   # diện tích 0 / quá dẹt
            elif kind == 4:
                v = [-30 + r.random() * 40, y0, r.random() * 3, y1]   # sát / ngoài mép trái
            elif kind == 5:
                v = [x0, self.h - 3, x1, self.h + 50]                 # tràn mép dưới
            elif kind == 6:
                v = [-50, -50, self.w + 50, self.h + 50]              # trùm cả trang
            text = r.choice(ODD) if r.random() < 0.6 else r.choice(VI)
            if r.random() < 0.15:
                text = r.choice(["|", "_", "¦", "l", "'", "-"]) + r.choice(VI)
            if r.random() < 0.15:
                text = r.choice(VI) + r.choice(["|", "_", "¦", "1", ".", ","])
            conf = r.choice([None, None, "0.3", "0.5", "0.49", "1", "2", "-1", "NaN", "Infinity", "%.2f" % r.random()])
            line = r.choice([-1, -1, 0, 3, 2147483647, -2147483648, r.randrange(40)])
            self.add(v[0], v[1], v[2], v[3], line, text, conf)

    def save(self):
        self.im.save(os.path.join(OUT, self.name + ".ppm"))
        with open(os.path.join(OUT, self.name + ".words.tsv"), "w", encoding="utf-8", newline="\n") as f:
            for w in self.words:
                text = w[5].replace("\t", " ").replace("\n", " ").replace("\r", " ")
                f.write("\t".join(w[:5]) + "\t" + text + ("" if w[6] is None else "\t" + w[6]) + "\n")


def document(name, w, h, seed, skew=0.0, lines_only=False, blur=False):
    """Trang chữ + bảng + dấu + nét bút, các từ thật lẫn rác."""
    r = random.Random(seed)
    p = Page(name, w, h, r, bg=(250, 249, 246) if blur else (255, 255, 255))
    em = h / 62.0
    mx = w * 0.09
    y = h * 0.07
    p.text_line(mx, y, VI[0:4], em * 0.95, bold=True)
    p.text_line(w * 0.55, y, VI[4:9], em * 0.95, bold=True)          # tiêu ngữ hai cột (cùng hàng, cách xa)
    y += em * 1.5
    x = p.text_line(w * 0.3, y, VI[14:20], em * 1.25, bold=True)
    p.d.line((w * 0.3, y + em * 0.18, x - em * 0.3, y + em * 0.18), fill=(30, 30, 30), width=max(1, round(em / 14)))   # gạch chân
    y += em * 2.0
    for k in range(5):
        words = [r.choice(VI) for _ in range(r.randrange(5, 11))]
        p.text_line(mx + (em * 2 if k == 0 else 0), y, words, em, italic=(k == 3))
        y += em * 1.45
    if not lines_only:
        # bảng 4 cột x 4 hàng, hàng đầu đậm, một ô gộp ngang
        tx0, tx1, ty0 = mx, w - mx, y
        cols = [tx0, tx0 + (tx1 - tx0) * 0.12, tx0 + (tx1 - tx0) * 0.5, tx0 + (tx1 - tx0) * 0.75, tx1]
        rh = em * 1.9
        lw = max(1, round(em / 9))
        for j in range(5):
            p.d.line((tx0, ty0 + j * rh, tx1, ty0 + j * rh), fill=(20, 20, 24), width=lw)
        for i, cx in enumerate(cols):
            y1 = ty0 + (3 if i == 3 else 4) * rh
            p.d.line((cx, ty0, cx, y1), fill=(20, 20, 24), width=lw)
        for j in range(4):
            for i in range(4):
                if j == 3 and i == 3:
                    continue
                words = [r.choice(VI) for _ in range(1 + (i % 3))]
                p.text_line(cols[i] + em * 0.4, ty0 + j * rh + rh * 0.68, words, em * 0.9, bold=(j == 0))
        # ký tự đường kẻ dính vào từ, từ nằm trên đường kẻ
        p.add(cols[1] - 2, ty0 + rh + 4, cols[1] + em * 2.2, ty0 + 2 * rh - 4, p.line_no, "|" + VI[30], "0.71")
        p.add(cols[2] - 1, ty0 + 3, cols[2] + 3, ty0 + 4 * rh - 3, 0, "|", "0.9")
        p.add(tx0 + 5, ty0 + 2 * rh - 3, tx0 + (tx1 - tx0) * 0.4, ty0 + 2 * rh + 3, 0, "____", None)
        p.add(tx0 + 2, ty0 - 4, tx1 - 2, ty0 + 4, 0, "—¬", None)
        y = ty0 + 4 * rh + em * 2.2
    p.text_line(mx, y, VI[46:52], em)
    y += em * 3
    # con dấu đỏ + chữ ký xanh vắt qua dòng chữ đen
    cx, cy, rad = w * 0.7, min(h * 0.86, y + em * 3), em * 2.6
    p.d.ellipse((cx - rad, cy - rad, cx + rad, cy + rad), outline=(200, 30, 40), width=max(2, round(em / 5)))
    p.text_line(cx - rad * 0.6, cy + em * 0.3, ["ĐÃ", "THU"], em * 0.8, bold=True, color=(200, 30, 40))
    p.text_line(w * 0.52, cy - em * 0.1, VI[52:56], em)
    pts = [(w * 0.5 + t * em * 0.9, cy + em * 1.6 + math.sin(t * 1.3) * em * 0.8 - t * em * 0.05) for t in range(12)]
    p.d.line(pts, fill=(30, 60, 190), width=max(2, round(em / 7)), joint="curve")
    p.add(w * 0.5, cy + em * 0.7, w * 0.5 + em * 10, cy + em * 2.5, p.line_no + 1, "Nguyễn", "0.62")   # chữ ký bị đọc thành chữ
    # từ trùng nhau, khung trùm lên từ bên cạnh, độ tin cậy thấp
    real = list(p.words)
    for k in range(0, len(real), 7):
        x0, y0, x1, y1, ln, text, _ = real[k]
        p.add(float(x0) + 0.4, float(y0) - 0.3, float(x1) + 0.6, float(y1) + 0.2, ln, text, "0.6")
    for k in range(3, len(real) - 1, 11):
        x0, y0, _, y1, ln, text, _ = real[k]
        p.add(float(x0), float(y0), float(real[k + 1][2]), float(y1), ln, text + "x", "0.52")
    for k in range(5, len(real), 13):
        x0, y0, x1, y1, ln, text, _ = real[k]
        p.add(x0, y0, x1, y1, ln, "rác", "0.2")
    p.junk_words(45)
    r.shuffle(p.words)
    if skew:
        p.im = p.im.rotate(skew, resample=Image.BICUBIC, fillcolor=(255, 255, 255))
        a = math.radians(-skew)
        cs, sn, cx0, cy0 = math.cos(a), math.sin(a), w / 2.0, h / 2.0
        moved = []
        for x0, y0, x1, y1, ln, text, conf in p.words:
            try:
                mxw, myw = (float(x0) + float(x1)) / 2, (float(y0) + float(y1)) / 2
                hw, hh = (float(x1) - float(x0)) / 2, (float(y1) - float(y0)) / 2
                nx, ny = cx0 + (mxw - cx0) * cs - (myw - cy0) * sn, cy0 + (mxw - cx0) * sn + (myw - cy0) * cs
                if not all(math.isfinite(v) for v in (nx, ny, hw, hh)):
                    raise ValueError
                moved.append(("%.1f" % (nx - hw), "%.1f" % (ny - hh), "%.1f" % (nx + hw), "%.1f" % (ny + hh), ln, text, conf))
            except (ValueError, OverflowError):
                moved.append((x0, y0, x1, y1, ln, text, conf))
        p.words = moved
    if blur:
        p.im = p.im.filter(ImageFilter.GaussianBlur(0.8))
        px = p.im.load()
        for _ in range(w * h // 9):
            x, yy = r.randrange(w), r.randrange(h)
            c = px[x, yy]
            n = r.randrange(-14, 15)
            px[x, yy] = tuple(max(0, min(255, v + n)) for v in c)
    p.save()


def plain(name, w, h, seed, kind, n_words):
    r = random.Random(seed)
    p = Page(name, w, h, r)
    if kind == "black":
        p.d.rectangle((0, 0, w, h), fill=(8, 8, 10))
    elif kind == "noise":
        p.im = Image.frombytes("RGB", (w, h), bytes(r.getrandbits(8) for _ in range(w * h * 3)))
        p.d = ImageDraw.Draw(p.im)
    elif kind == "grid":
        step = max(12, h // 18)
        for yy in range(step, h - step // 2, step):
            p.d.line((w * 0.06, yy, w * 0.94, yy), fill=(25, 25, 25), width=2)
        for i in range(7):
            xx = w * 0.06 + i * (w * 0.88) / 6
            p.d.line((xx, step, xx, (h - step // 2 - 1) // step * step), fill=(25, 25, 25), width=2)
    elif kind == "blobs":
        for _ in range(40):
            x, yy, s = r.random() * w, r.random() * h, 3 + r.random() * h / 12
            col = r.choice([(20, 20, 25), (190, 30, 40), (30, 60, 190), (120, 120, 120)])
            if r.random() < 0.5:
                p.d.ellipse((x, yy, x + s, yy + s * (0.4 + r.random())), fill=col)
            else:
                p.d.line((x, yy, x + (r.random() - 0.5) * 6 * s, yy + (r.random() - 0.5) * 6 * s), fill=col, width=1 + r.randrange(4))
    p.junk_words(n_words)
    p.save()


def dups(name, w, h, seed, n):
    r = random.Random(seed)
    p = Page(name, w, h, r)
    em = h / 30.0
    p.text_line(w * 0.1, h * 0.3, VI[14:19], em)
    first = p.words[0]
    for k in range(n):
        p.words.append(first if k % 3 else (first[0], first[1], first[2], first[3], first[4], first[5], "0.%d" % (50 + k % 50)))
    p.junk_words(30)
    p.save()


def main():
    L = LONG
    W = int(round(L * 595.276 / 841.89))
    document("wj_page_a", W, L, 11)
    document("wj_page_b", W, L, 12, blur=True)
    document("wj_page_c", L, W, 13)                       # trang ngang
    document("wj_page_k2", int(round(1640 * 0.707)), 1640, 14)   # khối phân tích k = 2
    document("wj_skew_rules", W, L, 21, skew=1.4)
    document("wj_skew_lines", W, L, 22, skew=-2.2, lines_only=True)
    document("wj_skew_big", W, L, 23, skew=6.5)           # nghiêng quá 8 độ theo độ dốc thì không khử
    plain("wj_white", W // 2, L // 2, 31, "white", 60)
    plain("wj_black", W // 2, L // 2, 32, "black", 60)
    plain("wj_noise", W // 2, L // 2, 33, "noise", 80)
    plain("wj_grid", W, L, 34, "grid", 120)
    plain("wj_blobs", W, L, 35, "blobs", 150)
    plain("wj_empty", 200, 300, 36, "white", 0)           # không có từ nào: trang chỉ gồm ảnh
    dups("wj_dups", 420, 300, 41, 2500)
    dups("wj_dups_many", 420, 300, 42, 6100)              # quá 6000 từ: bỏ bước dọn khung trùng
    for k, (w, h) in enumerate([(31, 60), (32, 32), (33, 47), (64, 40)]):
        plain("wj_small_%d" % k, w, h, 50 + k, "blobs" if k % 2 else "noise", 25)
    plain("wj_flat_0", 900, 33, 61, "blobs", 60)
    plain("wj_flat_1", 34, 1100, 62, "blobs", 60)
    plain("wj_flat_2", 1800, 40, 63, "grid", 60)
    print("%d trang rác trong %s" % (len([f for f in os.listdir(OUT) if f.endswith(".ppm")]), OUT))


if __name__ == "__main__":
    main()
