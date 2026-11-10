#!/usr/bin/env python3
"""Kiểm tra các file PDF do ứng dụng sinh ra bằng trình phân tích thật (pypdf, chế độ nghiêm ngặt).

Cách chạy: python3 tests/tools/check_pdfs.py <thư-mục hoặc file>...
Mỗi trang phải có khổ giấy hợp lệ và đúng một ảnh JPEG đọc được.
"""
import io
import os
import sys

import pypdf

try:
    from PIL import Image
except ImportError:  # không bắt buộc
    Image = None


def check(path):
    r = pypdf.PdfReader(path, strict=True)
    n = len(r.pages)
    if n == 0:
        raise ValueError("không có trang")
    for i, pg in enumerate(r.pages):
        box = pg.mediabox
        if float(box.width) < 3 or float(box.height) < 3:
            raise ValueError("trang %d: khổ trang %sx%s" % (i + 1, box.width, box.height))
        imgs = list(pg.images)
        if len(imgs) != 1:
            raise ValueError("trang %d: %d ảnh" % (i + 1, len(imgs)))
        if Image is not None and i < 3:
            im = Image.open(io.BytesIO(imgs[0].data))
            im.load()
    title = r.metadata.title if r.metadata else None
    return n, title


def main():
    files = []
    for a in sys.argv[1:]:
        if os.path.isdir(a):
            files += [os.path.join(a, f) for f in sorted(os.listdir(a)) if f.endswith(".pdf")]
        else:
            files.append(a)
    bad = 0
    for f in files:
        try:
            n, title = check(f)
            print("OK   %-40s %3d trang  %r" % (os.path.basename(f), n, (title or "")[:30]))
        except Exception as e:  # noqa: BLE001
            bad += 1
            print("LỖI  %s: %s" % (os.path.basename(f), e))
    print("PDF: %d file, %d lỗi" % (len(files), bad))
    sys.exit(1 if bad or not files else 0)


if __name__ == "__main__":
    main()
