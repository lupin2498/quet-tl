#!/usr/bin/env python3
"""Sinh trang quét TỔNG HỢP để kiểm thử việc chuyển trang quét sang Word (core/DocLayout + DocxWriter).

Mỗi trang là một trang A4 ĐÃ nắn phẳng (như ảnh kết quả trong ứng dụng), ghi dạng PPM, kèm:
  <tên>.words.tsv   kết quả "nhận dạng chữ" giả lập: mỗi dòng  x0 y0 x1 y1 dòng chữ   (toạ độ điểm ảnh, khung theo
                    chiều cao dòng như các bộ nhận dạng thật, xê dịch ngẫu nhiên vài điểm ảnh)
  <tên>.gt.json     đáp án: kích thước, các dòng chữ (nội dung, vị trí, cỡ, đậm), bảng (đường kẻ, ô gộp), hình

Các trang:
  wd_congvan   công văn: tiêu ngữ hai cột, tiêu đề giữa, đoạn văn căn đều hai lề, bảng 5 cột có hàng gộp, khối chữ ký
               có con dấu đỏ + chữ ký bút xanh
  wd_bieumau   biểu mẫu: dòng điền chấm chấm, bảng tiêu đề nhiều tầng (gộp cột + gộp hàng), ô ảnh, ô đánh dấu
  wd_vanban    văn bản thường: đề mục đậm, danh sách đánh số, đoạn thụt dòng, số trang
Biến thể (hậu tố): _blur (mờ + nhiễu + ánh sáng lệch), _skew (nghiêng 0,5 độ)

Cách chạy: python3 tests/tools/make_word_pages.py <thư-mục> [cạnh-dài=2200]
"""
import json
import math
import os
import random
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

OUT = sys.argv[1] if len(sys.argv) > 1 else "out/word-in"
LONG = int(sys.argv[2]) if len(sys.argv) > 2 else 2200
os.makedirs(OUT, exist_ok=True)
FD = "/usr/share/fonts/truetype/liberation/"
FONTS = {(False, False): "LiberationSerif-Regular.ttf", (True, False): "LiberationSerif-Bold.ttf",
         (False, True): "LiberationSerif-Italic.ttf", (True, True): "LiberationSerif-BoldItalic.ttf"}
PAGE_W_PT, PAGE_H_PT = 595.276, 841.89
W, H = int(round(LONG * PAGE_W_PT / PAGE_H_PT)), LONG
K = H / PAGE_H_PT          # điểm ảnh trên một point
INK = (28, 28, 32)
_font_cache = {}


def font(size_pt, bold=False, italic=False):
    key = (round(size_pt * K, 3), bold, italic)
    if key not in _font_cache:
        _font_cache[key] = ImageFont.truetype(FD + FONTS[(bold, italic)], size_pt * K)
    return _font_cache[key]


def px(pt):
    return pt * K


class Page:
    def __init__(self, name):
        self.name = name
        self.im = Image.new("RGB", (W, H), (255, 255, 255))
        self.d = ImageDraw.Draw(self.im)
        self.words = []      # (x0, y0, x1, y1, line, text) — khung mực thật
        self.lines = []      # đáp án từng dòng
        self.tables = []
        self.pictures = []
        self.rules = []
        self.line_no = 0

    # ------------------------------------------------------------------ chữ
    def text_width(self, s, f):
        return f.getlength(s)

    def line(self, x_pt, base_pt, s, size=13, bold=False, italic=False, color=INK, align="left", width_pt=None,
             justify_to=None, underline=False, same_line=False):
        """Vẽ một dòng chữ có đường chân chữ tại base_pt. align theo khoảng [x_pt, x_pt + width_pt].
        justify_to: bề rộng (pt) để dãn đều khoảng cách giữa các từ (căn đều hai lề).
        same_line: nối vào dòng vừa vẽ (cùng số dòng của bộ nhận dạng)."""
        f = font(size, bold, italic)
        words = s.split(" ")
        sp = f.getlength(" ")
        natural = sum(f.getlength(w) for w in words) + sp * (len(words) - 1)
        x = px(x_pt)
        extra = 0.0
        if justify_to is not None and len(words) > 1:
            extra = (px(justify_to) - natural) / (len(words) - 1)
            natural = px(justify_to)
        elif width_pt is not None:
            if align == "center":
                x += (px(width_pt) - natural) / 2.0
            elif align == "right":
                x += px(width_pt) - natural
        if not same_line:
            self.line_no += 1
        y = px(base_pt)
        x_start = x
        for w in words:
            if w:
                self.d.text((x, y), w, font=f, fill=color, anchor="ls")
                bb = self.d.textbbox((x, y), w, font=f, anchor="ls")
                self.words.append((bb[0], bb[1], bb[2], bb[3], self.line_no, w, size, x, y, f.getlength(w)))
            x += f.getlength(w) + sp + extra
        x_end = x - sp - extra
        if underline:
            uy = y + px(size * 0.12)
            self.d.line([(x_start, uy), (x_end, uy)], fill=color, width=max(1, int(round(px(size * 0.05)))))
        self.lines.append({"text": s, "x": x_start / K, "x1": x_end / K, "base": base_pt, "size": size, "bold": bold,
                           "italic": italic, "align": align, "line": self.line_no})
        return x_end / K

    def para(self, x_pt, base_pt, width_pt, s, size=13, lead=1.3, indent=0.0, justify=True, bold=False, italic=False):
        """Đoạn văn tự xuống dòng trong bề rộng width_pt; trả về đường chân chữ của dòng kế tiếp."""
        f = font(size, bold, italic)
        sp = f.getlength(" ")
        words = s.split(" ")
        cur, cur_w, first = [], 0.0, True
        lines = []
        for w in words:
            ww = f.getlength(w)
            avail = px(width_pt - (indent if first else 0.0))
            if cur and cur_w + sp + ww > avail:
                lines.append((cur, first))
                cur, cur_w, first = [w], ww, False
            else:
                cur_w = cur_w + (sp if cur else 0.0) + ww
                cur.append(w)
        if cur:
            lines.append((cur, first))
        y = base_pt
        for i, (ws, fst) in enumerate(lines):
            last = i == len(lines) - 1
            x = x_pt + (indent if fst else 0.0)
            wd = width_pt - (indent if fst else 0.0)
            self.line(x, y, " ".join(ws), size=size, bold=bold, italic=italic,
                      justify_to=wd if (justify and not last) else None)
            y += size * lead
        return y

    # ------------------------------------------------------------------ bảng
    def table(self, xs, ys, merges=(), lw=0.75, skip_v=(), skip_h=()):
        """Lưới bảng: xs, ys (pt). merges: [(c0, r0, c1, r1)] ô gộp (không kẻ đường bên trong).
        Trả về hàm cell(c, r) -> (x0, y0, x1, y1) của ô (đã tính gộp)."""
        cols, rows = len(xs) - 1, len(ys) - 1
        owner = {}
        for (c0, r0, c1, r1) in merges:
            for c in range(c0, c1 + 1):
                for r in range(r0, r1 + 1):
                    owner[(c, r)] = (c0, r0, c1, r1)
        wd = max(1, int(round(px(lw))))

        def same(a, b):
            return a in owner and b in owner and owner[a] == owner[b]

        # đường kẻ ngang: từng đoạn giữa hai đường dọc, bỏ đoạn nằm trong ô gộp
        for j in range(rows + 1):
            for i in range(cols):
                if 0 < j < rows and same((i, j - 1), (i, j)):
                    continue
                if (i, j) in skip_h:
                    continue
                self.d.line([(px(xs[i]), px(ys[j])), (px(xs[i + 1]), px(ys[j]))], fill=INK, width=wd)
        for i in range(cols + 1):
            for j in range(rows):
                if 0 < i < cols and same((i - 1, j), (i, j)):
                    continue
                if (i, j) in skip_v:
                    continue
                self.d.line([(px(xs[i]), px(ys[j])), (px(xs[i]), px(ys[j + 1]))], fill=INK, width=wd)
        cells = []
        seen = set()
        for r in range(rows):
            for c in range(cols):
                o = owner.get((c, r), (c, r, c, r))
                if o in seen:
                    continue
                seen.add(o)
                cells.append({"c0": o[0], "r0": o[1], "c1": o[2], "r1": o[3]})
        self.tables.append({"xs": list(xs), "ys": list(ys), "cols": cols, "rows": rows, "cells": cells, "lw": lw})

        def cell(c, r):
            o = owner.get((c, r), (c, r, c, r))
            return xs[o[0]], ys[o[1]], xs[o[2] + 1], ys[o[3] + 1]
        return cell

    def cell_text(self, box, s, size=12, bold=False, align="left", pad=4.0, dy=None, italic=False):
        x0, y0, x1, y1 = box
        base = (y0 + y1) / 2.0 + size * 0.33 if dy is None else y0 + dy
        self.line(x0 + pad, base, s, size=size, bold=bold, italic=italic, align=align, width_pt=x1 - x0 - 2 * pad)

    # ------------------------------------------------------------------ hình
    def stamp(self, cx_pt, cy_pt, r_pt, color=(205, 52, 48)):
        cx, cy, r = px(cx_pt), px(cy_pt), px(r_pt)
        wd = max(2, int(round(px(1.6))))
        self.d.ellipse([cx - r, cy - r, cx + r, cy + r], outline=color, width=wd)
        self.d.ellipse([cx - r * 0.72, cy - r * 0.72, cx + r * 0.72, cy + r * 0.72], outline=color, width=max(1, wd // 2))
        pts = []
        for i in range(10):
            a = -math.pi / 2 + i * math.pi / 5
            rr = r * (0.5 if i % 2 == 0 else 0.2)
            pts.append((cx + rr * math.cos(a), cy + rr * math.sin(a)))
        self.d.polygon(pts, fill=color)
        self.pictures.append({"x": cx_pt - r_pt, "y": cy_pt - r_pt, "w": 2 * r_pt, "h": 2 * r_pt, "kind": "stamp"})

    def scribble(self, x_pt, y_pt, w_pt, h_pt, color=(36, 62, 170), seed=11):
        rng = random.Random(seed)
        pts = []
        n = 30
        for i in range(n):
            t = i / (n - 1.0)
            pts.append((px(x_pt + w_pt * t + rng.uniform(-0.03, 0.03) * w_pt),
                        px(y_pt + h_pt * (0.5 + 0.45 * math.sin(t * 9.0 + seed)) + rng.uniform(-0.05, 0.05) * h_pt)))
        self.d.line(pts, fill=color, width=max(2, int(round(px(1.3)))), joint="curve")
        self.pictures.append({"x": x_pt, "y": y_pt, "w": w_pt, "h": h_pt, "kind": "sign"})

    def rule(self, x0_pt, y_pt, x1_pt, lw=0.75):
        self.d.line([(px(x0_pt), px(y_pt)), (px(x1_pt), px(y_pt))], fill=INK, width=max(1, int(round(px(lw)))))
        self.rules.append({"o": "h", "pos": y_pt, "a": x0_pt, "b": x1_pt})

    def box(self, x0, y0, x1, y1, lw=0.75):
        self.d.rectangle([px(x0), px(y0), px(x1), px(y1)], outline=INK, width=max(1, int(round(px(lw)))))

    # ------------------------------------------------------------------ ghi file
    def save(self, suffix="", im=None, words=None, seed=5):
        im = im if im is not None else self.im.filter(ImageFilter.GaussianBlur(0.5))
        words = words if words is not None else self.words
        name = self.name + suffix
        im.convert("RGB").save(os.path.join(OUT, name + ".ppm"))
        rng = random.Random(seed)
        # khung "nhận dạng chữ": cao theo dòng (từ đỉnh chữ hoa tới chân chữ có đuôi), xê dịch +-1,5 điểm ảnh
        with open(os.path.join(OUT, name + ".words.tsv"), "w", encoding="utf-8") as f:
            for (x0, y0, x1, y1, ln, text, size, bx, by, adv) in words:
                j = lambda: rng.uniform(-1.5, 1.5) * max(1.0, K / 2.6)
                top = by - px(size) * 0.74 + j()
                bot = by + px(size) * 0.23 + j()
                f.write("%.1f\t%.1f\t%.1f\t%.1f\t%d\t%s\n" % (x0 - 1 + j(), top, x1 + 1 + j(), bot, ln, text))
        gt = {"name": name, "w": W, "h": H, "pt": K, "lines": self.lines, "tables": self.tables, "pictures": self.pictures,
              "rules": self.rules}
        with open(os.path.join(OUT, name + ".gt.json"), "w", encoding="utf-8") as f:
            json.dump(gt, f, ensure_ascii=False, indent=1)


def degrade(im, seed=3):
    """Mờ nhẹ + ánh sáng lệch + nhiễu cảm biến (như ảnh chụp bằng điện thoại sau bộ lọc Nguyên gốc)."""
    rng = np.random.RandomState(seed)
    a = np.asarray(im.filter(ImageFilter.GaussianBlur(max(0.8, K * 0.32)))).astype(np.float32)
    yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
    g = 0.84 + 0.14 * (0.6 * xx / W + 0.4 * yy / H)
    a = a * g[:, :, None] * np.array([1.0, 0.985, 0.95], np.float32)[None, None, :] + rng.randn(H, W, 1) * 2.2
    return Image.fromarray(np.clip(a, 0, 255).astype(np.uint8))


def skew(page, deg):
    """Xoay cả trang quanh tâm một góc nhỏ; trả về (ảnh, danh sách từ với khung đã xoay)."""
    im = page.im.filter(ImageFilter.GaussianBlur(0.5)).rotate(deg, resample=Image.BICUBIC, fillcolor=(255, 255, 255))
    a = -math.radians(deg)
    cs, sn = math.cos(a), math.sin(a)
    cx, cy = W / 2.0, H / 2.0

    def rot(x, y):
        return cx + (x - cx) * cs - (y - cy) * sn, cy + (x - cx) * sn + (y - cy) * cs
    out = []
    for (x0, y0, x1, y1, ln, text, size, bx, by, adv) in page.words:
        mx, my = rot((x0 + x1) / 2.0, (y0 + y1) / 2.0)
        hw, hh = (x1 - x0) / 2.0, (y1 - y0) / 2.0
        nbx, nby = rot(bx, by)
        # đường chân chữ tại tâm từ
        _, cby = rot((x0 + x1) / 2.0, by)
        out.append((mx - hw, my - hh, mx + hw, my + hh, ln, text, size, nbx, cby, adv))
    return im, out


# ---------------------------------------------------------------------- nội dung

BODY1 = ("Thực hiện kế hoạch kiểm kê tài sản năm 2026 của Công ty, phòng Hành chính đã phối hợp với các bộ phận "
         "tiến hành rà soát toàn bộ thiết bị đang sử dụng tại văn phòng. Kết quả cho thấy phần lớn thiết bị còn "
         "hoạt động tốt, một số máy in và bộ lưu điện đã hết thời hạn khấu hao cần được thay thế trong quý tới.")
BODY2 = ("Để bảo đảm công việc không bị gián đoạn, phòng Hành chính đề nghị Ban Giám đốc xem xét phê duyệt danh mục "
         "thiết bị cần mua sắm bổ sung theo bảng kê dưới đây, với tổng kinh phí dự kiến không vượt quá ngân sách đã "
         "được giao từ đầu năm.")
BODY3 = ("Phòng Hành chính xin cam đoan số liệu nêu trên là đúng với thực tế kiểm kê và chịu trách nhiệm về tính "
         "chính xác của báo cáo này. Kính đề nghị Ban Giám đốc xem xét, quyết định.")


def congvan():
    p = Page("wd_congvan")
    L, R = 70.0, 538.0          # lề chữ (pt)
    # tiêu ngữ hai cột: mỗi khối căn giữa trong cột của nó
    p.line(L - 10, 62, "CÔNG TY TNHH THIẾT BỊ MẪU", size=12, bold=True, align="center", width_pt=200)
    p.line(260, 62, "CỘNG HÒA XÃ HỘI CHỦ NGHĨA VIỆT NAM", size=12, bold=True, align="center", width_pt=278, same_line=True)
    p.line(L - 10, 79, "Số: 128/CV-TBM", size=13, align="center", width_pt=200)
    p.line(260, 79, "Độc lập - Tự do - Hạnh phúc", size=13, bold=True, align="center", width_pt=278, same_line=True)
    p.rule(323, 84.5, 475)
    p.line(260, 106, "Hà Nội, ngày 14 tháng 11 năm 2026", size=13, italic=True, align="center", width_pt=278)
    p.line(L - 10, 106, "V/v kiểm kê thiết bị", size=12, align="center", width_pt=200, same_line=True)
    p.line(L, 146, "Kính gửi: Ban Giám đốc Công ty", size=14, bold=True, align="center", width_pt=R - L)
    y = 176.0
    for body in (BODY1, BODY2):
        y = p.para(L, y, R - L, body, size=13, lead=1.32, indent=28.0) + 5.0
    # bảng 5 cột, hàng cuối gộp 4 cột đầu
    top = y + 2
    xs = [L, L + 34, L + 246, L + 338, L + 392, R]
    rh = 22.0
    rows = 7
    ys = [top + i * rh for i in range(rows + 1)]
    cell = p.table(xs, ys, merges=[(0, rows - 1, 3, rows - 1)])
    heads = ["TT", "Tên thiết bị", "Mã hiệu", "ĐVT", "Số lượng"]
    for i, hd in enumerate(heads):
        p.cell_text(cell(i, 0), hd, size=12, bold=True, align="center")
    names = ["Máy in laser khổ A4", "Màn hình 24 inch", "Bộ lưu điện 1000VA", "Máy quét tài liệu", "Bộ phát Wi-Fi"]
    qty = [3, 12, 4, 2, 5]
    for r in range(5):
        p.cell_text(cell(0, r + 1), str(r + 1), align="center")
        p.cell_text(cell(1, r + 1), names[r])
        p.cell_text(cell(2, r + 1), "TB-%03d" % (r * 17 + 104), align="center")
        p.cell_text(cell(3, r + 1), "Cái" if r != 2 else "Bộ", align="center")
        p.cell_text(cell(4, r + 1), str(qty[r]), align="right")
    p.cell_text(cell(0, rows - 1), "Tổng cộng", bold=True, align="center")
    p.cell_text(cell(4, rows - 1), str(sum(qty)), bold=True, align="right")
    y = ys[-1] + 26
    y = p.para(L, y, R - L, BODY3, size=13, lead=1.32, indent=28.0) + 18
    # khối chữ ký
    p.line(L, y, "Nơi nhận:", size=11, bold=True, italic=True)
    p.line(330, y, "GIÁM ĐỐC", size=13, bold=True, align="center", width_pt=190, same_line=True)
    p.line(L, y + 14, "- Như trên;", size=11)
    p.line(330, y + 15, "(Ký, ghi rõ họ tên và đóng dấu)", size=12, italic=True, align="center", width_pt=190, same_line=True)
    p.line(L, y + 28, "- Lưu: VT, HC.", size=11)
    p.stamp(392, y + 62, 34)
    p.scribble(412, y + 40, 86, 34)
    p.line(330, y + 112, "Nguyễn Văn An", size=13, bold=True, align="center", width_pt=190)
    return p


def bieumau():
    p = Page("wd_bieumau")
    L, R = 62.0, 540.0
    p.line(L, 58, "Mẫu số 02/KK", size=11, italic=True, align="right", width_pt=R - L)
    p.line(L, 92, "PHIẾU KIỂM KÊ TÀI SẢN CỐ ĐỊNH", size=16, bold=True, align="center", width_pt=R - L)
    p.line(L, 111, "(Dùng cho các bộ phận thuộc khối văn phòng)", size=12, italic=True, align="center", width_pt=R - L)
    # ô ảnh ở góc phải (khung không có chữ bên trong ngoài nhãn nhỏ)
    p.box(470, 126, 540, 212)
    p.line(470, 172, "Ảnh 3x4", size=10, align="center", width_pt=70)
    y = 142.0
    dots = "." * 46
    for label in ("Bộ phận sử dụng:", "Người phụ trách:", "Thời điểm kiểm kê:"):
        x = p.line(L, y, label, size=13)
        p.line(x + 6, y, dots[: int((455 - x - 6) / 3.25)], size=13, same_line=True)
        y += 22
    # ô đánh dấu
    y += 4
    p.line(L, y, "Hình thức:", size=13)
    for i, t in enumerate(("Định kỳ", "Đột xuất", "Bàn giao")):
        bx = L + 78 + i * 118
        p.box(bx, y - 10, bx + 11, y + 1)
        p.line(bx + 17, y, t, size=13, same_line=True)
    # bảng tiêu đề hai tầng
    top = y + 24
    xs = [L, L + 30, L + 190, L + 250, L + 310, L + 370, L + 420, R]
    ys = [top, top + 20, top + 40] + [top + 40 + (i + 1) * 21 for i in range(8)]
    merges = [(0, 0, 0, 1), (1, 0, 1, 1), (2, 0, 3, 0), (4, 0, 5, 0), (6, 0, 6, 1)]
    cell = p.table(xs, ys, merges=merges)
    p.cell_text(cell(0, 0), "TT", size=11, bold=True, align="center")
    p.cell_text(cell(1, 0), "Tên tài sản", size=11, bold=True, align="center")
    p.cell_text(cell(2, 0), "Theo sổ sách", size=11, bold=True, align="center")
    p.cell_text(cell(4, 0), "Theo kiểm kê", size=11, bold=True, align="center")
    p.cell_text(cell(6, 0), "Ghi chú", size=11, bold=True, align="center")
    for c, t in ((2, "Số lượng"), (3, "Giá trị"), (4, "Số lượng"), (5, "Giá trị")):
        p.cell_text(cell(c, 1), t, size=11, bold=True, align="center")
    items = [("Bàn làm việc gỗ", 24, "36.000", 24, "36.000", ""), ("Ghế xoay lưng lưới", 30, "21.000", 29, "20.300", "Hỏng 01"),
             ("Tủ hồ sơ sắt 4 ngăn", 12, "30.000", 12, "30.000", ""), ("Máy tính để bàn", 26, "312.000", 26, "312.000", ""),
             ("Máy chiếu hội trường", 2, "44.000", 2, "44.000", ""), ("Điều hòa 12000 BTU", 8, "96.000", 8, "96.000", "Bảo trì"),
             ("Máy hủy tài liệu", 3, "13.500", 3, "13.500", ""), ("Két sắt chống cháy", 1, "18.000", 1, "18.000", "")]
    for r, it in enumerate(items):
        rr = r + 2
        p.cell_text(cell(0, rr), str(r + 1), size=11, align="center")
        p.cell_text(cell(1, rr), it[0], size=11)
        p.cell_text(cell(2, rr), str(it[1]), size=11, align="center")
        p.cell_text(cell(3, rr), it[2], size=11, align="right")
        p.cell_text(cell(4, rr), str(it[3]), size=11, align="center")
        p.cell_text(cell(5, rr), it[4], size=11, align="right")
        if it[5]:
            p.cell_text(cell(6, rr), it[5], size=11)
    y = ys[-1] + 24
    p.line(L, y, "Giá trị tính bằng nghìn đồng. Phiếu lập thành 02 bản, mỗi bên giữ 01 bản.", size=12, italic=True)
    y += 30
    p.line(L, y, "NGƯỜI LẬP PHIẾU", size=12, bold=True, align="center", width_pt=150)
    p.line(L + 165, y, "TRƯỞNG BỘ PHẬN", size=12, bold=True, align="center", width_pt=150, same_line=True)
    p.line(L + 330, y, "KẾ TOÁN TRƯỞNG", size=12, bold=True, align="center", width_pt=148, same_line=True)
    p.line(L, y + 15, "(Ký, họ tên)", size=11, italic=True, align="center", width_pt=150)
    p.line(L + 165, y + 15, "(Ký, họ tên)", size=11, italic=True, align="center", width_pt=150, same_line=True)
    p.line(L + 330, y + 15, "(Ký, họ tên)", size=11, italic=True, align="center", width_pt=148, same_line=True)
    p.scribble(L + 28, y + 30, 90, 36, seed=7)
    return p


def vanban():
    p = Page("wd_vanban")
    L, R = 78.0, 532.0
    p.line(L, 74, "QUY ĐỊNH VỀ QUẢN LÝ VÀ SỬ DỤNG THIẾT BỊ VĂN PHÒNG", size=15, bold=True, align="center", width_pt=R - L)
    p.line(L, 93, "(Ban hành kèm theo Quyết định số 45/QĐ-TBM ngày 02 tháng 01 năm 2026)", size=12, italic=True, align="center", width_pt=R - L)
    y = 128.0
    sections = [
        ("Điều 1. Phạm vi áp dụng",
         ["Quy định này áp dụng cho toàn bộ cán bộ, nhân viên đang làm việc tại trụ sở chính và các chi nhánh của Công ty.",
          "Thiết bị văn phòng bao gồm máy tính, máy in, máy quét, máy chiếu, thiết bị mạng và các vật dụng đi kèm."]),
        ("Điều 2. Trách nhiệm của người sử dụng",
         ["Giữ gìn, bảo quản thiết bị được giao; không tự ý tháo lắp, sửa chữa hoặc mang thiết bị ra khỏi cơ quan.",
          "Báo ngay cho phòng Hành chính khi thiết bị hư hỏng, mất mát để lập biên bản và xử lý kịp thời.",
          "Tắt nguồn thiết bị khi hết giờ làm việc nhằm tiết kiệm điện và phòng chống cháy nổ."]),
        ("Điều 3. Kiểm kê định kỳ",
         ["Việc kiểm kê được thực hiện hai lần mỗi năm vào tháng 6 và tháng 12, kết quả được lập thành biên bản có chữ ký "
          "của người sử dụng và đại diện phòng Hành chính."]),
    ]
    for head, items in sections:
        p.line(L, y, head, size=13, bold=True)
        y += 19
        for k, it in enumerate(items):
            y = p.para(L, y, R - L, "%d. %s" % (k + 1, it), size=13, lead=1.3, indent=24.0) + 3
        y += 8
    p.line(L, y + 6, "Mọi vướng mắc trong quá trình thực hiện đề nghị phản ánh về phòng Hành chính.", size=13, italic=True)
    p.line(L, 800, "2", size=12, align="center", width_pt=R - L)
    return p


def main():
    n = 0
    for make in (congvan, bieumau, vanban):
        p = make()
        p.save()
        p.save("_blur", im=degrade(p.im))
        im, words = skew(p, 0.5)
        p.save("_skew", im=im, words=words)
        n += 3
    print("đã ghi %d trang %dx%d vào %s" % (n, W, H, OUT))


if __name__ == "__main__":
    main()
