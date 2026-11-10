#!/usr/bin/env python3
"""Đối chiếu kết quả phần thuật toán giữa bản Java (DumpCore) và bản Swift (algotest --dump).

Cách chạy: python3 tests/tools/compare_core.py <dữ-liệu> <kết-quả-java> <kết-quả-swift>
Mã thoát 0 nếu hai bản cho cùng kết quả (trong sai số làm tròn), 1 nếu lệch.
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
    for a, b in zip(ja, sw):
        pa, pb = a.split(" "), b.split(" ")
        tag = pa[0]
        if pa[0] != pb[0] or pa[1] != pb[1]:
            bad("thứ tự dòng khác nhau: %s | %s" % (a[:60], b[:60]))
            continue
        if tag == "det":
            n_det += 1
            name = pa[1]
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
            sa, sb = nums(pa[3:6]), nums(pb[3:6])
            if sa == sb:
                n_enh_exact += 1
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
    for f in sorted(os.listdir(JAVA)):
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
        n_full += 1
        n_px += len(da)
        if da != db:
            diffs = [abs(x - y) for x, y in zip(da, db) if x != y]
            n_diff_px += len(diffs)
            m = max(diffs)
            max_px = max(max_px, m)
            if m > 2 or len(diffs) > 0.005 * len(da):
                bad("%s: %d giá trị lệch, lớn nhất %d" % (f, len(diffs), m))

    # PDF: trùng từng byte
    n_pdf = 0
    for f in sorted(os.listdir(JAVA)):
        if not f.endswith(".pdf"):
            continue
        other = os.path.join(SWIFT, f)
        if not os.path.exists(other):
            bad("thiếu %s ở bản Swift" % f)
            continue
        n_pdf += 1
        if open(os.path.join(JAVA, f), "rb").read() != open(other, "rb").read():
            bad("%s: nội dung PDF khác nhau" % f)

    print("Tìm mép: %d ảnh, %d có tứ giác, %d trùng tới 0.001 px, lệch lớn nhất %.4f px" % (n_det, n_quad, n_exact, max_det))
    if n_gt:
        print("Trang giấy tổng hợp có toạ độ thật: Java đúng %d/%d, Swift đúng %d/%d (sai số <1.5%% đường chéo)" % (acc["java"], n_gt, acc["swift"], n_gt))
        if acc["java"] != acc["swift"]:
            bad("độ chính xác tìm mép khác nhau giữa hai bản")
        if acc["java"] < 0.6 * n_gt:
            bad("độ chính xác tìm mép thấp bất thường: %d/%d" % (acc["java"], n_gt))
    print("Bộ lọc: %d lượt, %d trùng tuyệt đối; %d ảnh so từng điểm (%d/%d giá trị lệch, lớn nhất %d)" % (n_enh, n_enh_exact, n_full, n_diff_px, n_px, max_px))
    print("Hình học: %d tứ giác; PDF: %d file trùng từng byte" % (n_geom, n_pdf))
    if n_det == 0 or n_enh == 0 or n_geom == 0 or n_pdf == 0 or n_full == 0:
        bad("thiếu dữ liệu để đối chiếu")
    print("KẾT QUẢ ĐỐI CHIẾU JAVA/SWIFT: %s (%d điểm lệch)" % ("TRÙNG" if not problems else "LỆCH", len(problems)))
    sys.exit(0 if not problems else 1)


if __name__ == "__main__":
    main()
