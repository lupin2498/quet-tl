#!/usr/bin/env python3
"""Đối chiếu kết quả phần thuật toán giữa bản Java (DumpCore) và bản Swift (algotest --dump).

Dữ liệu: make_core_inputs.py + make_retouch_images.py (rt_*) + make_wavy_images.py (wv_*: bộ lọc Làm phẳng, dòng "flt")
ghi chung một thư mục.
Cách chạy: python3 tests/tools/compare_core.py <dữ-liệu> <kết-quả-java> <kết-quả-swift>
Mã thoát 0 nếu hai bản cho cùng kết quả (trong sai số làm tròn), 1 nếu lệch.
Bộ lọc Làm phẳng (chế độ 9) chỉ dùng phép tính cơ bản nên phải trùng TUYỆT ĐỐI: tổng kênh, từng điểm ảnh (*_m9.ppm),
thông số trường nắn (dòng "flt", số thực chỉ được lệch một đơn vị chữ số cuối do cách làm tròn khi in); phải có ít nhất
một trang được nắn và mọi trang wv_* đều được so từng điểm ảnh — không thì coi là thiếu dữ liệu.
"""
import math
import os
import sys

IN, JAVA, SWIFT = sys.argv[1], sys.argv[2], sys.argv[3]
problems = []
stats = {}


def bad(msg):
    problems.append(msg)
    if len(problems) <= 40:
        print("LỆCH " + msg)


def read(d):
    with open(os.path.join(d, "results.txt"), encoding="utf-8") as f:
        return [line.rstrip("\n") for line in f if line.strip()]


def nums(parts):
    return [float(x) for x in parts]


def read_ppm(path):
    with open(path, "rb") as f:
        data = f.read()
    parts = data.split(None, 4)
    w, h = int(parts[1]), int(parts[2])
    return w, h, parts[4][-w * h * 3:]


def close_fields(tag, a, b, fa, fb, tol_of):
    """Các trường số: trùng chuỗi, cùng NaN, hoặc lệch không quá tol_of(giá trị java). False (đã báo lệch) nếu khác."""
    for x, y in zip(fa, fb):
        if x == y:
            continue
        try:
            fx, fy = float(x), float(y)
        except ValueError:
            bad("%s: java '%s' | swift '%s'" % (tag, a, b))
            return False
        if fx == fy or (math.isnan(fx) and math.isnan(fy)):  # "NaN"/"nan", "Infinity"/"inf", "-0.000"/"0.000"
            continue
        if not (abs(fx - fy) <= tol_of(fx)):
            bad("%s: java '%s' | swift '%s'" % (tag, a, b))
            return False
    return True


def main():
    ja, sw = read(JAVA), read(SWIFT)
    if len(ja) != len(sw):
        bad("số dòng kết quả khác nhau: java %d, swift %d" % (len(ja), len(sw)))
    gt = {}
    gt_path = os.path.join(IN, "docs_gt.txt")
    if os.path.exists(gt_path):
        for line in open(gt_path):
            p = line.split()
            gt[p[0]] = nums(p[1:9])
    n_det = n_quad = n_exact = 0
    max_det = 0.0
    acc = {"java": 0, "swift": 0}
    n_gt = 0
    n_enh = n_enh_exact = 0
    n_geom = 0
    n_ret = 0
    # Làm phẳng (Enhancer.FLATTEN = 9): lượt lọc, dòng trường nắn "flt"
    n_enh9 = n_enh9_exact = 0
    n_flt = n_flt_field = n_flt_exact = 0
    # trang mấp mô wv_* (theo dòng det) và các trang có dòng flt: mỗi trang wv_* phải có dòng flt + ảnh _m9 so từng điểm
    wv_pages, flt_names = [], []
    for a, b in zip(ja, sw):
        pa, pb = a.split(" "), b.split(" ")
        tag = pa[0]
        if pa[0] != pb[0] or pa[1] != pb[1]:
            bad("thứ tự dòng khác nhau: %s | %s" % (a[:60], b[:60]))
            continue
        if tag == "det":
            n_det += 1
            name = pa[1]
            if name.startswith("wv_"):
                wv_pages.append(name)
            if pa[4] != pb[4]:
                bad("det %s: java %s, swift %s" % (name, pa[4], pb[4]))
                continue
            if pa[4] == "quad":
                n_quad += 1
                qa, qb = nums(pa[5:13]), nums(pb[5:13])
                d = max(abs(x - y) for x, y in zip(qa, qb))
                max_det = max(max_det, d)
                if d <= 0.0015:
                    n_exact += 1
                if d > 0.5:
                    bad("det %s: toạ độ lệch %.3f px" % (name, d))
                if abs(float(pa[13]) - float(pb[13])) > 0.005:
                    bad("det %s: điểm số %s vs %s" % (name, pa[13], pb[13]))
                if pa[14] != pb[14]:
                    bad("det %s: cờ tinh chỉnh khác nhau" % name)
                if name in gt:
                    w, h = float(pa[2]), float(pa[3])
                    diag = math.hypot(w, h)
                    for key, q in (("java", qa), ("swift", qb)):
                        e = max(math.hypot(q[2 * i] - gt[name][2 * i], q[2 * i + 1] - gt[name][2 * i + 1]) for i in range(4)) / diag
                        if e < 0.015:
                            acc[key] += 1
            elif pa[4] == "none":
                if abs(float(pa[5]) - float(pb[5])) > 0.005:
                    bad("det %s: điểm số %s vs %s" % (name, pa[5], pb[5]))
            if name in gt:
                n_gt += 1
        elif tag == "enh":
            n_enh += 1
            if pa[2] != pb[2]:
                bad("enh %s: thứ tự chế độ lọc khác nhau: java %s, swift %s" % (pa[1], pa[2], pb[2]))
                continue
            flat = pa[2] == "9"
            if flat:
                n_enh9 += 1
            sa, sb = nums(pa[3:6]), nums(pb[3:6])
            if sa == sb:
                n_enh_exact += 1
                if flat:
                    n_enh9_exact += 1
            elif flat:
                # Làm phẳng: bản port phải trùng tuyệt đối (không có sai số pow / exp như các bộ lọc khác)
                bad("enh %s Làm phẳng: tổng kênh khác nhau: java %s | swift %s" % (pa[1], " ".join(pa[3:6]), " ".join(pb[3:6])))
            else:
                rel = max(abs(x - y) / max(1.0, x) for x, y in zip(sa, sb))
                if rel > 2e-4:
                    bad("enh %s chế độ %s: tổng kênh lệch %.2e" % (pa[1], pa[2], rel))
        elif tag == "geom":
            n_geom += 1
            ia, ib = pa.index("convex"), pb.index("convex")
            oa, ob = nums(pa[3:ia]), nums(pb[3:ib])
            if len(oa) != len(ob) or max(abs(x - y) for x, y in zip(oa, ob)) > 0.006:
                bad("geom %s: thứ tự góc khác nhau" % pa[1])
                continue
            if pa[ia + 1] != pb[ib + 1]:
                bad("geom %s: isConvex java %s, swift %s" % (pa[1], pa[ia + 1], pb[ib + 1]))
                continue
            aa, ab = float(pa[ia + 3]), float(pb[ib + 3])
            if abs(aa - ab) > max(0.002, 1e-9 * abs(aa)):
                bad("geom %s: diện tích %s vs %s" % (pa[1], pa[ia + 3], pb[ib + 3]))
            if "aspect" in pa:
                ka, kb = pa.index("aspect"), pb.index("aspect")
                if abs(float(pa[ka + 1]) - float(pb[kb + 1])) > 2e-6 * max(1.0, float(pa[ka + 1])):
                    bad("geom %s: tỷ lệ %s vs %s" % (pa[1], pa[ka + 1], pb[kb + 1]))
                if pa[ka + 3:ka + 5] != pb[kb + 3:kb + 5]:
                    bad("geom %s: kích thước %s vs %s" % (pa[1], pa[ka + 3:ka + 5], pb[kb + 3:kb + 5]))
        elif tag in ("ret", "remap"):
            # Retouch (xóa thông minh, dò ngón tay, làm nét, dọn mép, đổi vùng cắt): cờ phải trùng, số trong sai số làm tròn
            n_ret += 1
            if len(pa) != len(pb):
                bad("%s %s: số trường khác nhau" % (tag, pa[1]))
                continue
            if tag == "remap":
                close_fields(tag, a, b, pa[2:], pb[2:], lambda fx: 1e-4)
            else:
                close_fields(tag, a, b, pa[2:], pb[2:], lambda fx: 2e-4 * max(1.0, abs(fx)))
        elif tag == "flt":
            # Làm phẳng: flt <tên> <có trường> <gw> <gh> <dòng chữ> <kẻ ngang> <kẻ dọc> <xoay> <dịch tối đa> <tổng dx> <tổng dy>
            # số nguyên / cờ phải trùng; số thực (%.3f) tính bằng cùng phép tính nên chỉ được lệch MỘT đơn vị chữ số cuối
            # (Java làm tròn nửa lên theo chuỗi thập phân ngắn nhất, printf theo giá trị nhị phân) — tổng dx / dy của
            # cả lưới là chỗ nhạy nhất với lệch nhỏ của trường nắn (điểm ảnh lấy mẫu 8 bit có thể vẫn trùng)
            n_flt += 1
            flt_names.append(pa[1])
            if len(pa) != 12 or len(pb) != 12:
                bad("flt %s: số trường khác nhau (java %d, swift %d)" % (pa[1], len(pa), len(pb)))
                continue
            if pa[2:9] != pb[2:9]:
                bad("flt %s: trường nắn khác nhau: java '%s' | swift '%s'" % (pa[1], " ".join(pa[2:9]), " ".join(pb[2:9])))
                continue
            if pa[2] == "1":
                n_flt_field += 1
            if close_fields(tag, a, b, pa[9:], pb[9:], lambda fx: 1.0005e-3) and pa == pb:
                n_flt_exact += 1
        else:  # snap, pdf, jpeg: phải trùng hoàn toàn
            if a != b:
                bad("%s: java '%s' | swift '%s'" % (tag, a, b))
            if tag == "pdf" and not a.endswith("same 1"):
                bad("pdf %s: ghi theo luồng khác ghi trong bộ nhớ" % pa[1])

    # ảnh sau khi lọc: so từng điểm ảnh
    n_full = 0
    max_px = 0
    n_diff_px = 0
    n_px = 0
    # ảnh sau bộ lọc Làm phẳng (*_m9.ppm: trang wv_*, rt_*, full_*) — phải trùng từng điểm ảnh
    n_m9 = n_m9_px = n_m9_diff = max_m9 = 0
    m9_compared = set()
    java_files = set(os.listdir(JAVA))
    for f in sorted(java_files):
        if not f.endswith(".ppm"):
            continue
        other = os.path.join(SWIFT, f)
        if not os.path.exists(other):
            bad("thiếu ảnh %s ở bản Swift" % f)
            continue
        wa, ha, da = read_ppm(os.path.join(JAVA, f))
        wb, hb, db = read_ppm(other)
        if (wa, ha) != (wb, hb) or len(da) != len(db):
            bad("%s: kích thước khác nhau" % f)
            continue
        flat = f.endswith("_m9.ppm")
        n_full += 1
        n_px += len(da)
        if flat:
            n_m9 += 1
            n_m9_px += len(da)
            m9_compared.add(f[:-len("_m9.ppm")])
        if da != db:
            diffs = [abs(x - y) for x, y in zip(da, db) if x != y]
            n_diff_px += len(diffs)
            m = max(diffs)
            max_px = max(max_px, m)
            if flat:
                n_m9_diff += len(diffs)
                max_m9 = max(max_m9, m)
                bad("%s: Làm phẳng phải trùng từng điểm ảnh (%d giá trị lệch, lớn nhất %d)" % (f, len(diffs), m))
            elif m > 2 or len(diffs) > 0.005 * len(da):
                bad("%s: %d giá trị lệch, lớn nhất %d" % (f, len(diffs), m))

    # PDF: trùng từng byte
    n_pdf = 0
    for f in sorted(java_files):
        if not f.endswith(".pdf"):
            continue
        other = os.path.join(SWIFT, f)
        if not os.path.exists(other):
            bad("thiếu %s ở bản Swift" % f)
            continue
        n_pdf += 1
        if open(os.path.join(JAVA, f), "rb").read() != open(other, "rb").read():
            bad("%s: nội dung PDF khác nhau" % f)

    # bản Swift ghi ảnh / PDF mà bản Java không ghi: hai bản xuất khác bộ dữ liệu (vd. ảnh _m9 của trang wv_* chỉ có ở một
    # bản thì phần so từng điểm ảnh của Làm phẳng bị bỏ qua mà không ai biết)
    for f in sorted(os.listdir(SWIFT)):
        if (f.endswith(".ppm") or f.endswith(".pdf")) and f not in java_files:
            bad("thừa %s ở bản Swift (bản Java không ghi)" % f)

    # Làm phẳng: mọi trang wv_* có dòng flt và ảnh _m9 đã so từng điểm; ít nhất một trang được nắn (nếu không, bước nắn
    # thẳng — phần dễ lệch nhất của bản port — chưa hề được đối chiếu)
    if sorted(wv_pages) != sorted(flt_names):
        bad("trang wv_* và dòng flt không khớp: trang %s | flt %s" % (" ".join(wv_pages), " ".join(flt_names)))
    for name in wv_pages:
        if name not in m9_compared:
            bad("trang %s: chưa so từng điểm ảnh kết quả Làm phẳng (%s_m9.ppm)" % (name, name))
    if n_flt and n_flt_field == 0:
        bad("Làm phẳng không nắn trang nào: chưa đối chiếu bước nắn thẳng (trang wv_* quá nhỏ hoặc bước nắn không chạy)")

    print("Tìm mép: %d ảnh, %d có tứ giác, %d trùng tới 0.001 px, lệch lớn nhất %.4f px" % (n_det, n_quad, n_exact, max_det))
    if n_gt:
        print("Trang giấy tổng hợp có toạ độ thật: Java đúng %d/%d, Swift đúng %d/%d (sai số <1.5%% đường chéo)" % (acc["java"], n_gt, acc["swift"], n_gt))
        if acc["java"] != acc["swift"]:
            bad("độ chính xác tìm mép khác nhau giữa hai bản")
        if acc["java"] < 0.6 * n_gt:
            bad("độ chính xác tìm mép thấp bất thường: %d/%d" % (acc["java"], n_gt))
    print("Bộ lọc: %d lượt, %d trùng tuyệt đối; %d ảnh so từng điểm (%d/%d giá trị lệch, lớn nhất %d)" % (n_enh, n_enh_exact, n_full, n_diff_px, n_px, max_px))
    print("Hình học: %d tứ giác; PDF: %d file trùng từng byte; Retouch: %d dòng" % (n_geom, n_pdf, n_ret))
    print("Làm phẳng: %d lượt lọc (%d trùng tuyệt đối), %d ảnh so từng điểm (%d/%d giá trị lệch, lớn nhất %d); "
          "%d dòng trường nắn (%d có nắn, %d trùng tuyệt đối)"
          % (n_enh9, n_enh9_exact, n_m9, n_m9_diff, n_m9_px, max_m9, n_flt, n_flt_field, n_flt_exact))
    if n_det == 0 or n_enh == 0 or n_geom == 0 or n_pdf == 0 or n_full == 0:
        bad("thiếu dữ liệu để đối chiếu")
    if n_enh9 == 0 or n_m9 == 0 or n_flt == 0:
        bad("thiếu dữ liệu Làm phẳng")
    print("KẾT QUẢ ĐỐI CHIẾU JAVA/SWIFT: %s (%d điểm lệch)" % ("TRÙNG" if not problems else "LỆCH", len(problems)))
    sys.exit(0 if not problems else 1)


if __name__ == "__main__":
    main()
