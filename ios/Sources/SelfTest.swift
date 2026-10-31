import UIKit

/// Tự kiểm thử khi chạy với tham số `-selftest` (dùng trên iOS Simulator trong CI):
/// kiểm tra thuật toán trên ảnh tổng hợp, dựng tài liệu, đi qua các màn hình và chụp lại giao diện.
/// Kết quả: Documents/selftest/ (results.txt, *.png, test.pdf, done.txt).
final class SelfTest {
    static var enabled: Bool { ProcessInfo.processInfo.arguments.contains("-selftest") }
    private static var runner: SelfTest?

    static var outDir: URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("selftest", isDirectory: true)
    }

    static func runIfRequested(nav: UINavigationController) {
        guard enabled else { return }
        let fm = FileManager.default
        try? fm.removeItem(at: outDir)
        try? fm.createDirectory(at: outDir, withIntermediateDirectories: true)
        if let items = try? fm.contentsOfDirectory(at: DocStore.shared.root, includingPropertiesForKeys: nil) {
            for u in items { try? fm.removeItem(at: u) }
        }
        NSSetUncaughtExceptionHandler { e in
            let s = "EXCEPTION \(e.name.rawValue): \(e.reason ?? "")\n\(e.callStackSymbols.joined(separator: "\n"))"
            try? s.write(to: SelfTest.outDir.appendingPathComponent("crash.txt"), atomically: true, encoding: .utf8)
        }
        CameraViewController.simulatedCapture = { SelfTest.testImage("doc_6_clutter") }
        let r = SelfTest(nav: nav)
        runner = r
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) { r.start() }
    }

    static func testImage(_ name: String) -> UIImage? {
        guard let u = Bundle.main.url(forResource: name, withExtension: "jpg", subdirectory: "TestImages") else { return nil }
        return UIImage(contentsOfFile: u.path)
    }

    // MARK: bộ chạy bước

    private enum Step {
        case run(String, () -> Void)
        case wait(Double)
        case until(String, Double, () -> Bool)
        case snap(String)
    }

    private let nav: UINavigationController
    private var steps = [Step]()
    private var lines = [String]()
    private var passed = 0
    private var failed = 0
    private var docIds = [String]()
    private var algoDone = false

    private init(nav: UINavigationController) {
        self.nav = nav
    }

    private func log(_ s: String) {
        lines.append(s)
        NSLog("[SELFTEST] %@", s)
        flush()
    }

    private func flush() {
        let text = lines.joined(separator: "\n") + "\n"
        try? text.write(to: SelfTest.outDir.appendingPathComponent("results.txt"), atomically: true, encoding: .utf8)
    }

    private func check(_ ok: Bool, _ msg: String) {
        if ok { passed += 1 } else { failed += 1 }
        log((ok ? "PASS " : "FAIL ") + msg)
    }

    private func next() {
        guard !steps.isEmpty else {
            finish()
            return
        }
        let s = steps.removeFirst()
        switch s {
        case .run(let name, let f):
            log("-- \(name)")
            f()
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) { self.next() }
        case .wait(let t):
            DispatchQueue.main.asyncAfter(deadline: .now() + t) { self.next() }
        case .until(let name, let timeout, let cond):
            let start = Date()
            func poll() {
                if cond() {
                    next()
                } else if Date().timeIntervalSince(start) > timeout {
                    check(false, "hết thời gian chờ: \(name) (top=\(String(describing: type(of: topMost()))))")
                    next()
                } else {
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.25) { poll() }
                }
            }
            poll()
        case .snap(let name):
            snap(name)
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.1) { self.next() }
        }
    }

    private func topMost() -> UIViewController {
        var vc: UIViewController = nav
        for _ in 0..<20 {
            if let p = vc.presentedViewController, !p.isBeingDismissed {
                vc = p
                continue
            }
            if let n = vc as? UINavigationController, let t = n.topViewController {
                vc = t
                continue
            }
            break
        }
        return vc
    }

    private func snap(_ name: String) {
        guard let win = nav.view.window else { return }
        let r = UIGraphicsImageRenderer(bounds: win.bounds)
        let img = r.image { _ in
            _ = win.drawHierarchy(in: win.bounds, afterScreenUpdates: true)
        }
        try? img.pngData()?.write(to: SelfTest.outDir.appendingPathComponent(name + ".png"))
        log("ảnh màn hình: \(name).png (\(String(describing: type(of: topMost()))))")
    }

    private var home: HomeViewController? { nav.viewControllers.first as? HomeViewController }
    private var editor: EditorViewController? { topMost() as? EditorViewController }
    private var camera: CameraViewController? {
        var vc: UIViewController? = nav.presentedViewController
        if let n = vc as? UINavigationController { vc = n.viewControllers.first }
        return vc as? CameraViewController
    }
    private var crop: CropViewController? { topMost() as? CropViewController }
    private var docVC: DocumentViewController? { topMost() as? DocumentViewController }

    // MARK: kịch bản

    private func start() {
        let info = Bundle.main.infoDictionary
        log("Quét Tài Liệu \(info?["CFBundleShortVersionString"] ?? "?") (\(info?["CFBundleVersion"] ?? "?")) — \(UIDevice.current.systemName) \(UIDevice.current.systemVersion), \(UIDevice.current.model)")
        steps.removeAll()
        addUntil("màn hình chính", 10) { self.topMost() is HomeViewController }
        addSnap("01_home_empty")
        addRun("thuật toán tìm mép trên ảnh tổng hợp") { self.runAlgorithmTests() }
        addUntil("thuật toán xong", 120) { self.algoDone }
        addRun("dựng tài liệu + PDF") { self.buildDocumentAndPdf() }
        addRun("tải lại danh sách") { self.home?.reload() }
        addWait(1.0)
        addSnap("02_home_list")
        addRun("mở tài liệu") {
                if let d = self.docIds.first.flatMap({ DocStore.shared.load($0) }) { self.home?.openDocument(d) }
            }
        addUntil("màn hình tài liệu", 20) { (self.docVC?.rendering == false) }
        addWait(1.2)
        addSnap("03_document")
        addRun("mở trình chỉnh sửa") { self.docVC?.openEditor(at: 0) }
        addUntil("trình chỉnh sửa sẵn sàng", 40) { (self.editor?.pending ?? 1) == 0 }
        addWait(1.0)
        addSnap("04_editor")
        addRun("mở bảng lọc") { self.editor?.toggleFilterPanel() }
        addWait(1.5)
        addSnap("05_filters")
        addRun("lọc Đen trắng") { self.editor?.applyFilter(Enhancer.bw) }
        addUntil("áp bộ lọc", 40) { (self.editor?.pending ?? 1) == 0 }
        addWait(0.8)
        addSnap("06_filter_bw")
        addRun("kiểm tra bộ lọc") { self.check(self.editor?.current?.filter == Enhancer.bw, "trang 1 dùng bộ lọc Đen trắng") }
        addRun("xoay trái") { self.editor?.rotateLeft() }
        addUntil("xoay", 40) { (self.editor?.pending ?? 1) == 0 }
        addWait(0.8)
        addSnap("07_rotated")
        addRun("kiểm tra xoay") { self.check(self.editor?.current?.rot == 270, "trang 1 xoay 270°") }
        addRun("đóng bảng lọc + mở màn hình cắt") {
                self.editor?.toggleFilterPanel()
                self.editor?.openCrop()
            }
        addUntil("màn hình cắt", 20) { self.crop != nil }
        addWait(1.2)
        addSnap("08_crop_edit")
        addRun("xác nhận vùng cắt") { self.crop?.doneTapped() }
        addUntil("quay lại trình chỉnh sửa", 40) { self.editor != nil && (self.editor?.pending ?? 1) == 0 }
        addWait(0.8)
        addSnap("09_editor_after_crop")
        addRun("sang trang 2") { self.editor?.goTo(1, animated: true) }
        addWait(1.2)
        addSnap("10_editor_page2")
        addRun("lưu tài liệu") { self.editor?.finishEditing(done: true) }
        addUntil("về màn hình tài liệu", 40) { self.docVC != nil && self.docVC?.rendering == false }
        addWait(1.0)
        addSnap("11_document_after_edit")
        addRun("kiểm tra PDF sau chỉnh sửa") { self.verifyCurrentDocPdf() }
        addRun("chia sẻ PDF") { self.docVC?.share(pdf: true) }
        addWait(2.5)
        addSnap("12_share_pdf")
        addRun("đóng bảng chia sẻ") { self.nav.presentedViewController?.dismiss(animated: false) }
        addWait(1.0)
        addRun("về màn hình chính") { self.nav.popToRootViewController(animated: false) }
        addWait(0.8)
        // --- chụp chế độ Đơn (ảnh giả lập) ---
        addRun("mở máy ảnh (Đơn)") { self.home?.openCamera(batch: false) }
        addUntil("máy ảnh", 15) { self.camera != nil && self.nav.presentedViewController?.isBeingPresented == false }
        addWait(1.5)
        addSnap("13_camera_single")
        addRun("chụp") { self.camera?.shoot() }
        addUntil("màn hình cắt sau khi chụp", 30) { self.crop != nil }
        addWait(1.5)
        addSnap("14_crop_capture")
        addRun("Tiếp tục") { self.crop?.doneTapped() }
        addUntil("trình chỉnh sửa (từ máy ảnh)", 40) { self.editor != nil && (self.editor?.pending ?? 1) == 0 }
        addWait(1.0)
        addSnap("15_editor_from_camera")
        addRun("lưu") { self.editor?.finishEditing(done: true) }
        addUntil("tài liệu mới", 40) { self.nav.presentedViewController == nil && self.docVC != nil && self.docVC?.rendering == false }
        addWait(1.0)
        addSnap("16_document_new")
        addRun("kiểm tra tài liệu mới") {
                self.check(self.docVC?.doc.pages.count == 1, "tài liệu chụp Đơn có 1 trang")
                self.check(self.docVC?.doc.draft == false, "tài liệu chụp Đơn đã lưu")
            }
        addRun("về màn hình chính") { self.nav.popToRootViewController(animated: false) }
        addWait(0.8)
        // --- chụp Hàng loạt ---
        addRun("mở máy ảnh (Hàng loạt)") { self.home?.openCamera(batch: true) }
        addUntil("máy ảnh", 15) { self.camera != nil && self.nav.presentedViewController?.isBeingPresented == false }
        addWait(1.2)
        addRun("chụp 1") { self.camera?.shoot() }
        addUntil("cắt 1", 30) { self.crop != nil }
        addWait(1.0)
        addRun("Tiếp tục 1") { self.crop?.doneTapped() }
        addUntil("về máy ảnh 1", 30) { self.topMost() is CameraViewController && (self.camera?.sessionPages.count ?? 0) == 1 }
        addWait(1.0)
        addSnap("17_camera_batch_1")
        addRun("chụp 2") { self.camera?.shoot() }
        addUntil("cắt 2", 30) { self.crop != nil }
        addWait(1.0)
        addRun("Tiếp tục 2") { self.crop?.doneTapped() }
        addUntil("về máy ảnh 2", 30) { self.topMost() is CameraViewController && (self.camera?.sessionPages.count ?? 0) == 2 }
        addWait(1.0)
        addSnap("18_camera_batch_2")
        addRun("xem các trang đã chụp") { self.camera?.openEditor() }
        addUntil("trình chỉnh sửa hàng loạt", 40) { self.editor != nil && (self.editor?.pending ?? 1) == 0 }
        addWait(1.0)
        addSnap("19_editor_batch")
        addRun("lưu") { self.editor?.finishEditing(done: true) }
        addUntil("tài liệu hàng loạt", 40) { self.nav.presentedViewController == nil && self.docVC != nil && self.docVC?.rendering == false }
        addWait(1.0)
        addSnap("20_document_batch")
        addRun("kiểm tra tài liệu hàng loạt") {
                self.check(self.docVC?.doc.pages.count == 2, "tài liệu Hàng loạt có 2 trang")
                self.verifyCurrentDocPdf()
            }
        addRun("chọn khổ giấy PDF") { self.docVC?.choosePdfSize() }
        addWait(1.0)
        addSnap("21_pdf_size")
        addRun("đóng") { self.nav.presentedViewController?.dismiss(animated: false) }
        addWait(0.6)
        addRun("về màn hình chính") { self.nav.popToRootViewController(animated: false) }
        addWait(1.0)
        addSnap("22_home_final")
        addRun("kiểm tra danh sách") { self.check(DocStore.shared.list().count == 3, "có 3 tài liệu đã lưu (\(DocStore.shared.list().count))") }
        next()
    }

    private func addRun(_ name: String, _ f: @escaping () -> Void) { steps.append(.run(name, f)) }
    private func addWait(_ t: Double) { steps.append(.wait(t)) }
    private func addUntil(_ name: String, _ timeout: Double, _ c: @escaping () -> Bool) { steps.append(.until(name, timeout, c)) }
    private func addSnap(_ name: String) { steps.append(.snap(name)) }

    private func finish() {
        log("KẾT QUẢ: \(passed) đạt, \(failed) lỗi")
        let summary = "passed=\(passed)\nfailed=\(failed)\n"
        try? summary.write(to: SelfTest.outDir.appendingPathComponent("done.txt"), atomically: true, encoding: .utf8)
    }

    // MARK: kiểm tra thuật toán

    private func runAlgorithmTests() {
        algoDone = false
        DispatchQueue.global(qos: .userInitiated).async {
            var out = [String]()
            var ok = 0, total = 0
            if let gtURL = Bundle.main.url(forResource: "gt", withExtension: "txt", subdirectory: "TestImages"),
               let text = try? String(contentsOf: gtURL, encoding: .utf8) {
                for line in text.split(separator: "\n") {
                    let parts = line.split(separator: " ").map(String.init)
                    guard parts.count >= 9 else { continue }
                    let name = (parts[0] as NSString).deletingPathExtension
                    let gt = parts[1...8].compactMap { Double($0) }
                    guard gt.count == 8, let img = SelfTest.testImage(name), let cg = img.cgImage else {
                        out.append("FAIL \(name): không đọc được ảnh")
                        total += 1
                        continue
                    }
                    total += 1
                    let t0 = Date()
                    let q = ImageOps.detect(cg)
                    let ms = Int(Date().timeIntervalSince(t0) * 1000)
                    let diag = hypot(Double(cg.width), Double(cg.height))
                    if let q = q {
                        var err = 0.0
                        for i in 0..<4 {
                            err = max(err, hypot(Double(q[i].x) - gt[2 * i], Double(q[i].y) - gt[2 * i + 1]) / diag)
                        }
                        if err < 0.02 { ok += 1 }
                        out.append(String(format: "%@ %@ err=%.4f (%.1fpx) %ldms", err < 0.02 ? "PASS" : "FAIL", name, err, err * diag, ms))
                    } else {
                        out.append("FAIL \(name): không tìm thấy trang (\(ms)ms)")
                    }
                }
            }
            let lines = out, nOK = ok, nTotal = total
            DispatchQueue.main.async {
                for l in lines { self.log("   " + l) }
                self.check(nTotal > 0 && nOK * 3 >= nTotal * 2, "tìm mép đúng (<2% đường chéo) \(nOK)/\(nTotal) ảnh")
                self.algoDone = true
            }
        }
    }

    // MARK: dựng tài liệu + PDF

    private func buildDocumentAndPdf() {
        let names = ["doc_1_wood", "doc_2_dark", "doc_6_clutter"]
        let d = DocStore.shared.create(draft: true)
        for n in names {
            guard let img = SelfTest.testImage(n) else {
                check(false, "đọc ảnh thử \(n)")
                continue
            }
            let norm = ImageOps.normalized(img, maxDim: ImageOps.maxSrc)
            let q = norm.cgImage.flatMap { ImageOps.detect($0) }
            do {
                try DocStore.shared.addPage(d, image: norm, quad: q, auto: true)
            } catch {
                check(false, "thêm trang \(n): \(error.localizedDescription)")
            }
        }
        let dir = DocStore.shared.dir(d)
        var renderOK = true
        for i in 0..<d.pages.count {
            do {
                let np = try ImageOps.render(dir: dir, page: d.pages[i])
                d.pages[i] = np
            } catch {
                renderOK = false
                check(false, "xử lý trang \(i + 1): \(error.localizedDescription)")
            }
        }
        check(renderOK && d.pages.count == names.count, "dựng \(d.pages.count) trang")
        // tỷ lệ khổ giấy A4 sau khi nắn phẳng
        for (i, p) in d.pages.enumerated() {
            guard let o = p.out, let cg = ImageOps.loadCG(DocStore.shared.url(d, o)) else {
                check(false, "ảnh kết quả trang \(i + 1)")
                continue
            }
            let r = Double(min(cg.width, cg.height)) / Double(max(cg.width, cg.height))
            check(abs(r - Geom.a4Ratio) < 0.03, String(format: "trang %ld: %ldx%ld, tỷ lệ %.3f (A4 = %.3f)", i + 1, cg.width, cg.height, r, Geom.a4Ratio))
        }
        d.draft = false
        d.modified = Date()
        DocStore.shared.save(d)
        docIds.append(d.id)
        checkPdf(d, label: "PDF 3 trang")
        // ghi bộ lọc mẫu để xem
        if let p = d.pages.first, let w = p.warp, let cg = ImageOps.loadCG(DocStore.shared.url(d, w)) {
            let small = ImageOps.scaledCG(cg, maxDim: 600)
            for m in Enhancer.modes {
                if let f = ImageOps.filtered(small, mode: m) {
                    try? ImageOps.writeJPEG(f, to: SelfTest.outDir.appendingPathComponent("filter_\(m).jpg"), quality: 0.85)
                }
            }
        }
    }

    private func checkPdf(_ d: Doc, label: String) {
        do {
            let data = try ImageOps.pdfData(doc: d)
            try data.write(to: SelfTest.outDir.appendingPathComponent("test_\(d.pages.count)p.pdf"))
            let head = String(data: data.prefix(8), encoding: .ascii) ?? ""
            let tail = String(data: data.suffix(8), encoding: .ascii) ?? ""
            var pages = 0
            if let prov = CGDataProvider(data: data as CFData), let pdf = CGPDFDocument(prov) {
                pages = pdf.numberOfPages
                if let page = pdf.page(at: 1) {
                    let box = page.getBoxRect(.mediaBox)
                    let k: CGFloat = 1.5
                    let r = UIGraphicsImageRenderer(size: CGSize(width: box.width * k, height: box.height * k))
                    let img = r.image { ctx in
                        UIColor.white.setFill()
                        ctx.fill(CGRect(x: 0, y: 0, width: box.width * k, height: box.height * k))
                        let c = ctx.cgContext
                        c.translateBy(x: 0, y: box.height * k)
                        c.scaleBy(x: k, y: -k)
                        c.drawPDFPage(page)
                    }
                    try? img.pngData()?.write(to: SelfTest.outDir.appendingPathComponent("pdf_\(d.pages.count)p_page1.png"))
                }
            }
            check(head.hasPrefix("%PDF-1.4") && tail.contains("%%EOF") && pages == d.pages.count,
                  "\(label): \(data.count / 1024) KB, \(pages) trang")
        } catch {
            check(false, "\(label): \(error.localizedDescription)")
        }
    }

    private func verifyCurrentDocPdf() {
        guard let d = docVC?.doc else {
            check(false, "không có tài liệu để kiểm tra PDF")
            return
        }
        checkPdf(d, label: "PDF \(d.pages.count) trang")
    }
}
