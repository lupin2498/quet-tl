#!/usr/bin/env python3
"""Kiểm tra các file .docx do kiểm thử độ bền của lõi Word ghi ra (FuzzCore.java, algotest --fuzz) hoặc do bản đối chiếu
ghi (DumpCore --word, algotest --dump-word): với đầu vào rác tới đâu thì file vẫn phải là một gói OOXML đọc được.

  python3 tests/tools/check_docx_zip.py <thư-mục hoặc file .docx>...

Với mỗi file (thư mục được duyệt đệ quy): mở được bằng zipfile, CRC mọi mục đúng, đủ các phần, mọi phần .xml / .rels
đúng cú pháp XML, cấu trúc WordprocessingML hợp lệ (dùng lại check_docx.validate_package: thứ tự phần tử con theo lược
đồ, mỗi hàng bảng phủ đủ cột lưới, ô / hộp chữ kết thúc bằng đoạn, số đo nguyên, id hình không trùng, hình có quan hệ).
Mã thoát khác 0 nếu có file không đạt hoặc không có file nào.
"""
import os
import sys
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import check_docx  # noqa: E402  (validate_package)


def main():
    paths = []
    for a in sys.argv[1:]:
        if os.path.isdir(a):
            for root, _, files in os.walk(a):
                paths += [os.path.join(root, f) for f in sorted(files) if f.endswith(".docx")]
        elif os.path.exists(a):
            paths.append(a)
        else:
            print("FAIL %s: không có" % a)
            return 1
    bad = 0
    entries = deflated = size = 0
    for p in paths:
        try:
            with zipfile.ZipFile(p) as z:
                broken = z.testzip()
                problems = ["mục %s hỏng CRC" % broken] if broken else check_docx.validate_package(z)
                entries += len(z.namelist())
                deflated += sum(1 for i in z.infolist() if i.compress_type == zipfile.ZIP_DEFLATED)
                size += os.path.getsize(p)
        except (zipfile.BadZipFile, OSError, KeyError, ValueError) as e:
            problems = ["không mở được bằng zipfile: %s" % e]
        if problems:
            bad += 1
            if bad <= 30:
                print("FAIL %s: %s" % (p, "; ".join(problems[:6])))
    print("KẾT QUẢ KIỂM TRA DOCX: %d file (%d mục, %d mục nén, %d byte), %d file lỗi" % (len(paths), entries, deflated, size, bad))
    return 1 if bad or not paths else 0


if __name__ == "__main__":
    sys.exit(main())
