#!/usr/bin/env python3
"""Kiểm tra file Word do lõi DocLayout + DocxWriter xuất ra từ các trang tổng hợp của make_word_pages.py.

  python3 tests/tools/check_docx.py <thư-mục-trang-vào> <thư-mục-docx> [--render] [--keep <thư-mục>]

Với mỗi <tên>.gt.json có <tên>.docx tương ứng:
  A. gói OOXML hợp lệ: đủ các phần, XML đúng cú pháp, phần tử con đúng thứ tự lược đồ (Word từ chối / "sửa" file sai
     thứ tự), mỗi hàng bảng phủ đúng số cột lưới, ô và hộp chữ kết thúc bằng đoạn, sau bảng có đoạn, thân kết thúc
     bằng sectPr, số đo là số nguyên, id hình không trùng, hình nhúng có đủ quan hệ;
  B. nội dung so với đáp án: bảng (số cột / hàng, ô gộp ngang / dọc, chữ trong từng ô), mọi dòng chữ có mặt, kiểu chữ
     (đậm / nghiêng / cỡ) của từng dòng, mỗi trang quét là một section cùng khổ giấy, hình (con dấu, chữ ký) được nhúng;
  C. (--render, cần LibreOffice + poppler) dựng lại file Word thành PDF: đúng một trang cho mỗi trang quét, vị trí từng
     từ so với trang gốc (lệch ngang / lệch đường chân chữ, tính bằng point).
Mã thoát khác 0 nếu có kiểm tra không đạt.
"""
import io
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile
import xml.etree.ElementTree as ET

W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
NS = {
    "w": W,
    "wp": "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing",
    "a": "http://schemas.openxmlformats.org/drawingml/2006/main",
    "pic": "http://schemas.openxmlformats.org/drawingml/2006/picture",
    "r": "http://schemas.openxmlformats.org/officeDocument/2006/relationships",
    "mc": "http://schemas.openxmlformats.org/markup-compatibility/2006",
    "wps": "http://schemas.microsoft.com/office/word/2010/wordprocessingShape",
    "v": "urn:schemas-microsoft-com:vml",
    "w10": "urn:schemas-microsoft-com:office:word",
}
PREFIX = {v: k for k, v in NS.items()}

fails = 0
checks = 0


def check(ok, what):
    global fails, checks
    checks += 1
    if not ok:
        fails += 1
    print(("PASS " if ok else "FAIL ") + what)
    return ok


def q(tag):
    """'{ns}local' -> 'prefix:local'."""
    if tag.startswith("{"):
        ns, local = tag[1:].split("}")
        return PREFIX.get(ns, "?") + ":" + local
    return tag


def wq(local):
    return "{%s}%s" % (W, local)


# Thứ tự phần tử con theo lược đồ (tập con mà ứng dụng ghi ra); phần tử con không có trong danh sách bị báo lỗi.
ORDER = {
    "w:pPr": ["w:pStyle", "w:keepNext", "w:keepLines", "w:pageBreakBefore", "w:framePr", "w:widowControl", "w:numPr",
              "w:suppressLineNumbers", "w:pBdr", "w:shd", "w:tabs", "w:suppressAutoHyphens", "w:kinsoku", "w:wordWrap",
              "w:overflowPunct", "w:topLinePunct", "w:autoSpaceDE", "w:autoSpaceDN", "w:bidi", "w:adjustRightInd",
              "w:snapToGrid", "w:spacing", "w:ind", "w:contextualSpacing", "w:mirrorIndents", "w:suppressOverlap", "w:jc",
              "w:textDirection", "w:textAlignment", "w:textboxTightWrap", "w:outlineLvl", "w:divId", "w:cnfStyle", "w:rPr",
              "w:sectPr", "w:pPrChange"],
    "w:rPr": ["w:rStyle", "w:rFonts", "w:b", "w:bCs", "w:i", "w:iCs", "w:caps", "w:smallCaps", "w:strike", "w:dstrike",
              "w:outline", "w:shadow", "w:emboss", "w:imprint", "w:noProof", "w:snapToGrid", "w:vanish", "w:webHidden",
              "w:color", "w:spacing", "w:w", "w:kern", "w:position", "w:sz", "w:szCs", "w:highlight", "w:u", "w:effect",
              "w:bdr", "w:shd", "w:fitText", "w:vertAlign", "w:rtl", "w:cs", "w:em", "w:lang", "w:eastAsianLayout",
              "w:specVanish", "w:oMath"],
    "w:tblPr": ["w:tblStyle", "w:tblpPr", "w:tblOverlap", "w:bidiVisual", "w:tblStyleRowBandSize", "w:tblStyleColBandSize",
                "w:tblW", "w:jc", "w:tblCellSpacing", "w:tblInd", "w:tblBorders", "w:shd", "w:tblLayout", "w:tblCellMar",
                "w:tblLook", "w:tblCaption", "w:tblDescription"],
    "w:tblCellMar": ["w:top", "w:start", "w:left", "w:bottom", "w:end", "w:right"],
    "w:tcMar": ["w:top", "w:start", "w:left", "w:bottom", "w:end", "w:right"],
    "w:tcBorders": ["w:top", "w:start", "w:left", "w:bottom", "w:end", "w:right", "w:insideH", "w:insideV", "w:tl2br", "w:tr2bl"],
    "w:tcPr": ["w:cnfStyle", "w:tcW", "w:gridSpan", "w:hMerge", "w:vMerge", "w:tcBorders", "w:shd", "w:noWrap", "w:tcMar",
               "w:textDirection", "w:tcFitText", "w:vAlign", "w:hideMark"],
    "w:trPr": ["w:cnfStyle", "w:divId", "w:gridBefore", "w:gridAfter", "w:wBefore", "w:wAfter", "w:cantSplit", "w:trHeight",
               "w:tblHeader", "w:tblCellSpacing", "w:jc", "w:hidden"],
    "w:tbl": ["w:tblPr", "w:tblGrid", "w:tr"],
    "w:tr": ["w:tblPrEx", "w:trPr", "w:tc"],
    "w:tc": ["w:tcPr", "w:p", "w:tbl"],
    "w:sectPr": ["w:headerReference", "w:footerReference", "w:footnotePr", "w:endnotePr", "w:type", "w:pgSz", "w:pgMar",
                 "w:paperSrc", "w:pgBorders", "w:lnNumType", "w:pgNumType", "w:cols", "w:formProt", "w:vAlign", "w:noEndnote",
                 "w:titlePg", "w:textDirection", "w:bidi", "w:rtlGutter", "w:docGrid", "w:printerSettings"],
    "w:p": ["w:pPr", "w:r"],
    "wp:anchor": ["wp:simplePos", "wp:positionH", "wp:positionV", "wp:extent", "wp:effectExtent", "wp:wrapNone", "wp:wrapSquare",
                  "wp:wrapTight", "wp:wrapThrough", "wp:wrapTopAndBottom", "wp:docPr", "wp:cNvGraphicFramePr", "a:graphic"],
    "wps:wsp": ["wps:cNvPr", "wps:cNvSpPr", "wps:cNvCnPr", "wps:spPr", "wps:style", "wps:extLst", "wps:txbx", "wps:linkedTxbx", "wps:bodyPr"],
    "wps:spPr": ["a:xfrm", "a:custGeom", "a:prstGeom", "a:noFill", "a:solidFill", "a:gradFill", "a:blipFill", "a:pattFill", "a:grpFill",
                 "a:ln", "a:effectLst"],
    "pic:spPr": ["a:xfrm", "a:custGeom", "a:prstGeom", "a:noFill", "a:solidFill", "a:ln"],
    "pic:pic": ["pic:nvPicPr", "pic:blipFill", "pic:spPr"],
    "pic:blipFill": ["a:blip", "a:srcRect", "a:stretch", "a:tile"],
    "a:ln": ["a:noFill", "a:solidFill", "a:gradFill", "a:pattFill", "a:prstDash", "a:custDash", "a:round", "a:bevel", "a:miter",
             "a:headEnd", "a:tailEnd"],
    "a:xfrm": ["a:off", "a:ext"],
    "mc:AlternateContent": ["mc:Choice", "mc:Fallback"],
    "w:docDefaults": ["w:rPrDefault", "w:pPrDefault"],
}
INT_ATTRS = {
    "w:spacing": ["before", "after", "line", "val"], "w:ind": ["left", "right"], "w:sz": ["val"], "w:szCs": ["val"],
    "w:gridCol": ["w"], "w:tcW": ["w"], "w:tblW": ["w"], "w:tblInd": ["w"], "w:trHeight": ["val"], "w:gridSpan": ["val"],
    "w:tab": ["pos"], "w:pgSz": ["w", "h"], "w:pgMar": ["top", "right", "bottom", "left"],
}
PLAIN_INT = {"wp:extent": ["cx", "cy"], "a:ext": ["cx", "cy"], "a:off": ["x", "y"]}


def wattr(el, name):
    return el.get("{%s}%s" % (W, name))


def validate_part(xml_bytes, name):
    """Danh sách lỗi cấu trúc của một phần XML WordprocessingML."""
    problems = []
    try:
        root = ET.fromstring(xml_bytes)
    except ET.ParseError as e:
        return ["%s không đúng cú pháp XML: %s" % (name, e)]
    ids = set()
    for el in root.iter():
        tag = q(el.tag)
        order = ORDER.get(tag)
        if order:
            last = -1
            for c in el:
                ct = q(c.tag)
                if ct not in order:
                    problems.append("<%s> không được phép trong <%s>" % (ct, tag))
                    continue
                k = order.index(ct)
                if k < last:
                    problems.append("<%s> sai thứ tự trong <%s>" % (ct, tag))
                else:
                    last = k
        for a in INT_ATTRS.get(tag, []):
            v = wattr(el, a)
            if v is not None and not re.match(r"^-?\d+$", v):
                problems.append("%s@w:%s=%r không phải số nguyên" % (tag, a, v))
        for a in PLAIN_INT.get(tag, []):
            v = el.get(a)
            if v is not None and not re.match(r"^-?\d+$", v):
                problems.append("%s@%s=%r không phải số nguyên" % (tag, a, v))
        if tag == "w:tbl":
            grid = el.find("w:tblGrid", NS)
            cols = len(list(grid)) if grid is not None else 0
            for tr in el.findall("w:tr", NS):
                n = 0
                for tc in tr.findall("w:tc", NS):
                    pr = tc.find("w:tcPr", NS)
                    span = pr.find("w:gridSpan", NS) if pr is not None else None
                    n += int(wattr(span, "val")) if span is not None else 1
                    kids = list(tc)
                    if not kids or q(kids[-1].tag) != "w:p":
                        problems.append("ô bảng không kết thúc bằng một đoạn")
                if n != cols:
                    problems.append("một hàng bảng phủ %d / %d cột lưới" % (n, cols))
        if tag == "w:trHeight" and not int(wattr(el, "val") or "0") > 0:
            problems.append("chiều cao hàng không dương")
        if tag == "w:spacing" and wattr(el, "line") is not None and not int(wattr(el, "line")) > 0:
            problems.append("chiều cao dòng không dương")
        if tag == "wp:docPr":
            if el.get("id") in ids:
                problems.append("id hình trùng nhau: %s" % el.get("id"))
            ids.add(el.get("id"))
        if tag == "wp:extent" and (int(el.get("cx")) < 0 or int(el.get("cy")) < 0):
            problems.append("kích thước hình âm")
        if tag == "w:t" and el.text and re.search(r"[\x00-\x08\x0b\x0c\x0e-\x1f]", el.text):
            problems.append("ký tự điều khiển trong chữ")
    body = root.find("w:body", NS)
    if body is not None:
        kids = list(body)
        if not kids or q(kids[-1].tag) != "w:sectPr":
            problems.append("thân tài liệu không kết thúc bằng sectPr")
        for i in range(len(kids) - 1):
            if q(kids[i].tag) == "w:tbl" and q(kids[i + 1].tag) != "w:p":
                problems.append("sau bảng không có đoạn")
    for box in root.iter("{%s}txbxContent" % W):
        kids = list(box)
        if not kids or q(kids[-1].tag) != "w:p":
            problems.append("hộp chữ không kết thúc bằng một đoạn")
    return sorted(set(problems))


def validate_package(z):
    problems = []
    names = set(z.namelist())
    for need in ("[Content_Types].xml", "_rels/.rels", "word/document.xml", "word/styles.xml", "word/settings.xml",
                 "word/_rels/document.xml.rels", "docProps/core.xml", "docProps/app.xml"):
        if need not in names:
            problems.append("thiếu phần %s" % need)
    for n in names:
        if n.endswith(".xml") or n.endswith(".rels"):
            try:
                ET.fromstring(z.read(n))
            except ET.ParseError as e:
                problems.append("%s không đúng cú pháp XML: %s" % (n, e))
    if "word/document.xml" in names:
        problems += validate_part(z.read("word/document.xml"), "word/document.xml")
        doc = z.read("word/document.xml").decode("utf-8")
        rels = z.read("word/_rels/document.xml.rels").decode("utf-8") if "word/_rels/document.xml.rels" in names else ""
        targets = dict(re.findall(r'Id="([^"]+)"[^>]*Target="([^"]+)"', rels))
        used = set(re.findall(r'r:embed="([^"]+)"', doc))
        for rid in used:
            if rid not in targets:
                problems.append("hình %s không có quan hệ" % rid)
            elif "word/" + targets[rid] not in names:
                problems.append("thiếu file hình %s" % targets[rid])
        for rid, t in targets.items():
            if t.startswith("media/") and rid not in used:
                problems.append("hình %s không được dùng" % t)
    return problems


# ---------------------------------------------------------------------- đọc nội dung

def norm(s):
    return re.sub(r"\s+", " ", s.replace("\t", " ")).strip()


def run_props(r):
    pr = r.find("w:rPr", NS)
    d = {"b": False, "i": False, "sz": None, "u": False, "color": None}
    if pr is not None:
        d["b"] = pr.find("w:b", NS) is not None
        d["i"] = pr.find("w:i", NS) is not None
        d["u"] = pr.find("w:u", NS) is not None
        sz = pr.find("w:sz", NS)
        if sz is not None:
            d["sz"] = int(wattr(sz, "val")) / 2.0
        c = pr.find("w:color", NS)
        if c is not None:
            d["color"] = wattr(c, "val")
    return d


def para_info(p, def_sz):
    """Nội dung một đoạn: chữ (tab -> \t), các mẩu (chữ, đậm, nghiêng, cỡ)."""
    text = ""
    runs = []
    for r in p.findall("w:r", NS):
        if r.find("w:tab", NS) is not None:
            text += "\t"
            continue
        t = r.find("w:t", NS)
        if t is None:
            continue
        d = run_props(r)
        if d["sz"] is None:
            d["sz"] = def_sz
        d["text"] = t.text or ""
        text += d["text"]
        runs.append(d)
    return {"text": text, "runs": runs}


def read_doc(z):
    doc = ET.fromstring(z.read("word/document.xml"))
    styles = ET.fromstring(z.read("word/styles.xml"))
    sz = styles.find(".//w:rPrDefault/w:rPr/w:sz", NS)
    def_sz = int(wattr(sz, "val")) / 2.0 if sz is not None else 12.0
    body = doc.find("w:body", NS)
    paras, tables, sections = [], [], []
    for tbl in doc.iter(wq("tbl")):
        grid = [int(wattr(g, "w")) for g in tbl.find("w:tblGrid", NS)]
        rows = []
        for tr in tbl.findall("w:tr", NS):
            row = []
            col = 0
            for tc in tr.findall("w:tc", NS):
                pr = tc.find("w:tcPr", NS)
                span = pr.find("w:gridSpan", NS)
                vm = pr.find("w:vMerge", NS)
                n = int(wattr(span, "val")) if span is not None else 1
                borders = pr.find("w:tcBorders", NS)
                row.append({"col": col, "span": n, "vmerge": None if vm is None else (wattr(vm, "val") or "continue"),
                            "text": norm(" ".join(para_info(p, def_sz)["text"] for p in tc.findall("w:p", NS))),
                            "paras": [para_info(p, def_sz) for p in tc.findall("w:p", NS)],
                            "borders": [] if borders is None else [q(b.tag) for b in borders]})
                col += n
            rows.append(row)
        tables.append({"cols": len(grid), "rows": len(rows), "grid": grid, "cells": rows})
    in_table = set()
    for tbl in doc.iter(wq("tbl")):
        for p in tbl.iter(wq("p")):
            in_table.add(p)
    seen_fallback = set()
    for fb in doc.iter("{%s}Fallback" % NS["mc"]):
        for p in fb.iter(wq("p")):
            seen_fallback.add(p)
    for p in doc.iter(wq("p")):
        if p in in_table or p in seen_fallback:
            continue
        info = para_info(p, def_sz)
        if info["text"].strip():
            paras.append(info)
    for sp in doc.iter(wq("sectPr")):
        pg = sp.find("w:pgSz", NS)
        sections.append((int(wattr(pg, "w")), int(wattr(pg, "h"))))
    pics = len(list(doc.iter("{%s}pic" % NS["pic"])))
    return {"paras": paras, "tables": tables, "sections": sections, "pics": pics, "def_sz": def_sz}


def style_of(info, text):
    """Kiểu chữ chủ đạo (theo số ký tự) của các mẩu chứa `text` trong đoạn."""
    b = i = n = 0
    szs = {}
    for r in info["runs"]:
        k = len(r["text"].strip())
        if k == 0:
            continue
        n += k
        b += k if r["b"] else 0
        i += k if r["i"] else 0
        szs[r["sz"]] = szs.get(r["sz"], 0) + k
    if n == 0:
        return None
    return {"b": b * 2 > n, "i": i * 2 > n, "sz": max(szs.items(), key=lambda kv: kv[1])[0]}


# ---------------------------------------------------------------------- so với đáp án

def gt_cell_texts(gt, table):
    """Chữ trong từng ô của bảng đáp án: {(c0, r0): 'chữ'} theo vị trí các dòng."""
    xs, ys = table["xs"], table["ys"]
    out = {}
    for c in table["cells"]:
        x0, x1, y0, y1 = xs[c["c0"]], xs[c["c1"] + 1], ys[c["r0"]], ys[c["r1"] + 1]
        texts = [ln for ln in gt["lines"] if x0 - 1 <= ln["x"] and ln["x1"] <= x1 + 1 and y0 < ln["base"] - 2 and ln["base"] < y1 + 1]
        texts.sort(key=lambda ln: (ln["base"], ln["x"]))
        out[(c["c0"], c["r0"])] = (norm(" ".join(t["text"] for t in texts)), texts)
    return out


def check_page(name, gt, doc, strict_style=True):
    in_tables = set()
    ok_tables = 0
    for ti, t in enumerate(gt["tables"]):
        cand = [d for d in doc["tables"] if d["cols"] == t["cols"] and d["rows"] == t["rows"]]
        if not check(len(cand) >= 1, "%s: bảng %d có %d cột x %d hàng (các bảng trong file: %s)" % (
                name, ti + 1, t["cols"], t["rows"], [(d["cols"], d["rows"]) for d in doc["tables"]])):
            continue
        d = cand[0]
        ok_tables += 1
        texts = gt_cell_texts(gt, t)
        bad_merge, bad_text, bad_style = [], [], []
        n_cells = 0
        for c in t["cells"]:
            n_cells += 1
            key = (c["c0"], c["r0"])
            row = d["cells"][c["r0"]]
            cell = next((x for x in row if x["col"] == c["c0"]), None)
            if cell is None:
                bad_merge.append("%s: không có ô bắt đầu ở cột này" % (key,))
                continue
            if cell["span"] != c["c1"] - c["c0"] + 1:
                bad_merge.append("%s: gộp %d cột, cần %d" % (key, cell["span"], c["c1"] - c["c0"] + 1))
            if c["r1"] > c["r0"]:
                if cell["vmerge"] != "restart":
                    bad_merge.append("%s: thiếu gộp hàng" % (key,))
                for r in range(c["r0"] + 1, c["r1"] + 1):
                    cont = next((x for x in d["cells"][r] if x["col"] == c["c0"]), None)
                    if cont is None or cont["vmerge"] != "continue":
                        bad_merge.append("%s: hàng %d không nối gộp" % (key, r))
            elif cell["vmerge"] is not None:
                bad_merge.append("%s: gộp hàng thừa" % (key,))
            want, lines = texts[key]
            if cell["text"] != want:
                bad_text.append("%s: %r != %r" % (key, cell["text"], want))
            for ln in lines:
                in_tables.add(id(ln))
            if lines and cell["paras"]:
                st = style_of(cell["paras"][0], want)
                g0 = lines[0]
                if st is not None and (st["b"] != g0["bold"] or abs(st["sz"] - g0["size"]) > 1.01):
                    bad_style.append("%s %r: đậm=%s cỡ=%s, cần đậm=%s cỡ=%s" % (key, want, st["b"], st["sz"], g0["bold"], g0["size"]))
            # viền đủ bốn cạnh
            if set(cell["borders"]) < {"w:top", "w:left", "w:right"} and c["r1"] == c["r0"]:
                bad_merge.append("%s: thiếu viền (%s)" % (key, cell["borders"]))
        check(not bad_merge, "%s: bảng %d — ô gộp / viền đúng (%d ô)%s" % (name, ti + 1, n_cells, "" if not bad_merge else ": " + "; ".join(bad_merge[:4])))
        check(not bad_text, "%s: bảng %d — chữ trong từng ô đúng%s" % (name, ti + 1, "" if not bad_text else ": " + "; ".join(bad_text[:4])))
        check(len(bad_style) <= (0 if strict_style else 2), "%s: bảng %d — kiểu chữ trong ô đúng%s" % (
            name, ti + 1, "" if not bad_style else " (%d lệch): " % len(bad_style) + "; ".join(bad_style[:4])))
    # dòng chữ ngoài bảng: gộp theo số dòng của đáp án
    groups = {}
    for ln in gt["lines"]:
        if id(ln) in in_tables:
            continue
        groups.setdefault(ln["line"], []).append(ln)
    ptexts = [norm(p["text"]) for p in doc["paras"]]
    # chữ trong các bảng không thuộc đáp án (khung quanh một dòng chữ) cũng tính là có mặt
    extra = [c["text"] for t in doc["tables"] for row in t["cells"] for c in row if c["text"]]
    missing, style_bad, n_style = [], [], 0
    for no, segs in sorted(groups.items()):
        segs.sort(key=lambda ln: ln["x"])
        want = norm(" ".join(s["text"] for s in segs))
        if want in ptexts:
            p = doc["paras"][ptexts.index(want)]
        elif all(norm(s["text"]) in ptexts or norm(s["text"]) in extra for s in segs):
            p = None
        else:
            missing.append(want)
            continue
        if p is None:
            continue
        # kiểu chữ từng khúc: dò trong các mẩu của đoạn theo thứ tự
        pos = 0
        flat = []
        for r in p["runs"]:
            for ch in r["text"]:
                flat.append((ch, r))
        ftext = "".join(ch for ch, _ in flat)
        for sgm in segs:
            words = sgm["text"].split(" ")
            at = ftext.find(words[0], pos)
            if at < 0:
                continue
            end = at
            b = i = n = 0
            szs = {}
            for wd in words:
                k = ftext.find(wd, end)
                if k < 0:
                    break
                for j in range(k, k + len(wd)):
                    r = flat[j][1]
                    n += 1
                    b += 1 if r["b"] else 0
                    i += 1 if r["i"] else 0
                    szs[r["sz"]] = szs.get(r["sz"], 0) + 1
                end = k + len(wd)
            pos = end
            if n == 0:
                continue
            n_style += 1
            sz = max(szs.items(), key=lambda kv: kv[1])[0]
            tol = 0.51 if len(sgm["text"]) >= 12 else 1.01
            if (b * 2 > n) != sgm["bold"] or (i * 2 > n) != sgm["italic"] or abs(sz - sgm["size"]) > tol:
                style_bad.append("%r: đậm=%s nghiêng=%s cỡ=%s, cần đậm=%s nghiêng=%s cỡ=%s" % (
                    sgm["text"][:30], b * 2 > n, i * 2 > n, sz, sgm["bold"], sgm["italic"], sgm["size"]))
    check(not missing, "%s: mọi dòng chữ ngoài bảng có mặt (%d dòng)%s" % (name, len(groups), "" if not missing else " — thiếu: " + " | ".join(missing[:3])))
    lim = 0 if strict_style else max(2, n_style // 10)
    check(len(style_bad) <= lim, "%s: kiểu chữ (đậm / nghiêng / cỡ) của %d khúc chữ đúng%s" % (
        name, n_style, "" if not style_bad else " — %d lệch: " % len(style_bad) + "; ".join(style_bad[:4])))
    want_pics = len(gt["pictures"])
    check(doc["pics"] >= (1 if want_pics else 0), "%s: hình (con dấu / chữ ký) được nhúng: %d (đáp án có %d)" % (name, doc["pics"], want_pics))
    check(len(doc["sections"]) == 1 and abs(doc["sections"][0][0] - 11906) <= 2 and abs(doc["sections"][0][1] - 16838) <= 60,
          "%s: một section khổ A4 (%s)" % (name, doc["sections"]))


# ---------------------------------------------------------------------- dựng lại bằng LibreOffice

def have(cmd):
    return shutil.which(cmd) is not None


def render_pdf(docx, outdir):
    subprocess.run(["soffice", "--headless", "--convert-to", "pdf", "--outdir", outdir, docx], stdout=subprocess.DEVNULL,
                   stderr=subprocess.DEVNULL, timeout=180)
    pdf = os.path.join(outdir, os.path.splitext(os.path.basename(docx))[0] + ".pdf")
    return pdf if os.path.exists(pdf) else None


def pdf_words(pdf):
    """[(trang, chữ, xMin, yMin, xMax, yMax)] theo point."""
    out = subprocess.run(["pdftotext", "-bbox", pdf, "-"], capture_output=True, timeout=120).stdout.decode("utf-8", "replace")
    words = []
    page = -1
    for line in out.splitlines():
        if "<page " in line:
            page += 1
        m = re.search(r'<word xMin="([\d.]+)" yMin="([\d.]+)" xMax="([\d.]+)" yMax="([\d.]+)">(.*?)</word>', line)
        if m:
            t = m.group(5).replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", '"').replace("&apos;", "'")
            words.append((page, t, float(m.group(1)), float(m.group(2)), float(m.group(3)), float(m.group(4))))
    return words, page + 1


def check_render(name, gt, docx, tmp, skewed=False):
    pdf = render_pdf(docx, tmp)
    if not check(pdf is not None, "%s: LibreOffice dựng được file Word" % name):
        return
    words, pages = pdf_words(pdf)
    check(pages == 1, "%s: dựng lại đúng 1 trang (được %d)" % (name, pages))
    if skewed:
        return
    # từ của đáp án: đọc lại từ words.tsv không được (khung đã xê dịch) -> dùng dòng của đáp án: vị trí đầu dòng + chân chữ
    k = gt["pt"]
    by_text = {}
    for wd in words:
        by_text.setdefault(wd[1], []).append(wd)
    dxs, dys, n_miss = [], [], 0
    for ln in gt["lines"]:
        first = ln["text"].split(" ")[0]
        cands = by_text.get(first, [])
        if not cands:
            n_miss += 1
            continue
        best = None
        for c in cands:
            hgt = c[5] - c[3]
            size = hgt / 1.107
            base = c[3] + 0.891 * size
            d = abs(c[2] - ln["x"]) + abs(base - ln["base"])
            if best is None or d < best[0]:
                best = (d, c[2] - ln["x"], base - ln["base"])
        dxs.append(abs(best[1]))
        dys.append(abs(best[2]))
    dxs.sort()
    dys.sort()

    def pct(v, p):
        return v[min(len(v) - 1, int(len(v) * p))] if v else 99.0
    print("    %s: lệch đầu dòng — ngang trung vị %.2f pt, P95 %.2f pt; chân chữ trung vị %.2f pt, P95 %.2f pt (%d dòng, %d không dò được)" % (
        name, pct(dxs, 0.5), pct(dxs, 0.95), pct(dys, 0.5), pct(dys, 0.95), len(dxs), n_miss))
    check(len(dxs) >= 0.9 * len(gt["lines"]), "%s: dò được vị trí của >= 90%% số dòng sau khi dựng lại (%d / %d)" % (name, len(dxs), len(gt["lines"])))
    check(pct(dxs, 0.5) <= 1.0 and pct(dxs, 0.95) <= 3.0, "%s: đầu dòng lệch ngang ít (trung vị %.2f <= 1 pt, P95 %.2f <= 3 pt)" % (name, pct(dxs, 0.5), pct(dxs, 0.95)))
    check(pct(dys, 0.5) <= 1.0 and pct(dys, 0.95) <= 2.5, "%s: đường chân chữ lệch ít (trung vị %.2f <= 1 pt, P95 %.2f <= 2,5 pt)" % (name, pct(dys, 0.5), pct(dys, 0.95)))


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    render = "--render" in sys.argv
    if len(args) < 2:
        print(__doc__)
        return 2
    in_dir, out_dir = args[0], args[1]
    keep = None
    if "--keep" in sys.argv:
        keep = sys.argv[sys.argv.index("--keep") + 1]
        os.makedirs(keep, exist_ok=True)
        args = [a for a in args if a != keep]
        in_dir, out_dir = args[0], args[1]
    if render and not (have("soffice") and have("pdftotext")):
        print("Thiếu LibreOffice / poppler: bỏ qua phần dựng lại")
        render = False
    names = sorted(f[:-8] for f in os.listdir(in_dir) if f.endswith(".gt.json"))
    n = 0
    tmp = keep or tempfile.mkdtemp(prefix="docx-check-")
    for name in names:
        docx = os.path.join(out_dir, name + ".docx")
        if not os.path.exists(docx):
            check(False, "%s: không có file Word" % name)
            continue
        n += 1
        gt = json.load(open(os.path.join(in_dir, name + ".gt.json"), encoding="utf-8"))
        z = zipfile.ZipFile(docx)
        probs = validate_package(z)
        check(not probs, "%s: gói OOXML hợp lệ%s" % (name, "" if not probs else " — " + "; ".join(probs[:5])))
        if probs:
            continue
        doc = read_doc(z)
        degraded = name.endswith("_blur") or name.endswith("_skew")
        check_page(name, gt, doc, strict_style=not degraded)
        if render:
            check_render(name, gt, docx, tmp, skewed=name.endswith("_skew"))
    # tài liệu nhiều trang
    allp = os.path.join(out_dir, "tat_ca.docx")
    if os.path.exists(allp) and n > 0:
        z = zipfile.ZipFile(allp)
        probs = validate_package(z)
        check(not probs, "tat_ca: gói OOXML hợp lệ%s" % ("" if not probs else " — " + "; ".join(probs[:5])))
        if not probs:
            doc = read_doc(z)
            check(len(doc["sections"]) == n, "tat_ca: %d trang quét -> %d section" % (n, len(doc["sections"])))
            if render:
                pdf = render_pdf(allp, tmp)
                if check(pdf is not None, "tat_ca: LibreOffice dựng được"):
                    _, pages = pdf_words(pdf)
                    check(pages == n, "tat_ca: dựng lại đúng %d trang (được %d)" % (n, pages))
    if keep is None:
        shutil.rmtree(tmp, ignore_errors=True)
    check(n > 0, "có trang để kiểm tra (%d)" % n)
    print("KẾT QUẢ KIỂM TRA WORD: %d kiểm tra, %d lỗi" % (checks, fails))
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main())
