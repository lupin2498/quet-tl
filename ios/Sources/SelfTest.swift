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
        for u in DocStore.shared.signLibrary() { try? fm.removeItem(at: u) }
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
    private var retakeSrc = ""
    private var targetId = ""
    private var stressStart = Date()
    private var algoDone = false
    private var recoveredId = ""
    private var dirsBefore = 0
    private var probeDoc: Doc?
    private var modifiedBefore = Date()
    private var discardDoc: Doc?
    private var badDoc: Doc?
    private var beautyId = ""
    private var statBefore = [String: Double]()
    private var marksBefore = [Mark]()

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
        let appWin = (UIApplication.shared.delegate as? AppDelegate)?.window
        guard let win = appWin ?? nav.view.window else {
            log("không chụp được màn hình \(name)")
            return
        }
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
    private var eraser: EraseViewController? { topMost() as? EraseViewController }
    private var markup: MarkupViewController? { topMost() as? MarkupViewController }
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
        addRun("tắt Tự động cắt") { self.crop?.testToggleAuto() }
        addWait(0.8)
        addSnap("08b_crop_auto_off")
        addRun("kiểm tra tắt") { self.check(self.crop?.testAutoOn == false, "Tự động cắt: tắt -> khung toàn ảnh") }
        addRun("bật lại Tự động cắt") { self.crop?.testToggleAuto() }
        addUntil("tìm lại mép", 20) { self.crop?.testAutoOn == true }
        addWait(0.8)
        addRun("kéo góc trên-trái") { self.crop?.testDragCorner(0, by: CGPoint(x: 40, y: 30)) }
        addWait(0.5)
        addSnap("08c_crop_drag_loupe")
        addRun("kiểm tra kéo tay") {
            self.check(self.crop?.testAutoOn == false, "kéo tay cầm -> tự tắt Tự động cắt")
            self.crop?.testEndDrag()
        }
        addRun("xác nhận vùng cắt") { self.crop?.doneTapped() }
        addUntil("quay lại trình chỉnh sửa", 40) { self.editor != nil && (self.editor?.pending ?? 1) == 0 }
        addWait(0.8)
        addSnap("09_editor_after_crop")
        addRun("giữ So sánh") { self.editor?.testCompare(true) }
        addWait(0.5)
        addSnap("09b_compare")
        addRun("thả So sánh") { self.editor?.testCompare(false) }
        addRun("sang trang 2") { self.editor?.goTo(1, animated: true) }
        addWait(1.2)
        addSnap("10_editor_page2")
        addRun("chụp lại trang 2") {
            self.retakeSrc = self.editor?.current?.src ?? ""
            self.editor?.retakeTapped()
        }
        addUntil("máy ảnh (chụp lại)", 15) { self.topMost() is CameraViewController }
        addWait(1.2)
        addSnap("10b_camera_retake")
        addRun("chụp (chụp lại)") { self.camera?.shoot() }
        addUntil("cắt (chụp lại)", 30) { self.crop != nil }
        addWait(1.0)
        addRun("Tiếp tục (chụp lại)") { self.crop?.doneTapped() }
        addUntil("về trình chỉnh sửa sau chụp lại", 40) {
            self.nav.presentedViewController == nil && self.editor != nil && (self.editor?.pending ?? 1) == 0
        }
        addWait(1.0)
        addSnap("10c_after_retake")
        addRun("kiểm tra chụp lại") {
            var src: String?
            var count = 0
            if let ed = self.editor {
                count = ed.doc.pages.count
                if count > 1 { src = ed.doc.pages[1].src }
            }
            self.check(src != nil && src != self.retakeSrc && count == 3, "chụp lại thay ảnh trang 2, vẫn 3 trang")
        }
        addRun("lưu tài liệu") { self.editor?.finishEditing(done: true) }
        addUntil("về màn hình tài liệu", 40) { self.docVC != nil && self.docVC?.rendering == false }
        addWait(1.0)
        addSnap("11_document_after_edit")
        addRun("Thêm trang") { self.docVC?.addPages() }
        addUntil("máy ảnh (thêm trang)", 15) { self.topMost() is CameraViewController }
        addWait(1.0)
        addRun("chụp (thêm trang)") { self.camera?.shoot() }
        addUntil("cắt (thêm trang)", 30) { self.crop != nil }
        addWait(1.0)
        addRun("Tiếp tục (thêm trang)") { self.crop?.doneTapped() }
        addUntil("trình chỉnh sửa (thêm trang)", 40) { self.editor != nil && (self.editor?.pending ?? 1) == 0 }
        addWait(0.8)
        addSnap("11b_editor_add_page")
        addRun("lưu (thêm trang)") { self.editor?.finishEditing(done: true) }
        addUntil("về tài liệu (thêm trang)", 40) {
            self.nav.presentedViewController == nil && self.docVC != nil && self.docVC?.rendering == false
        }
        addWait(1.0)
        addSnap("11c_document_4_pages")
        addRun("kiểm tra thêm trang") { self.check(self.docVC?.doc.pages.count == 4, "Thêm trang: tài liệu có 4 trang") }
        addRun("kiểm tra PDF sau chỉnh sửa") { self.verifyCurrentDocPdf() }
        addRun("chia sẻ PDF") { self.docVC?.share(pdf: true) }
        addUntil("bảng chia sẻ", 20) { self.nav.presentedViewController != nil }
        addWait(2.0)
        addSnap("12_share_pdf")
        addRun("đóng bảng chia sẻ") {
            self.check(self.nav.presentedViewController is UIActivityViewController, "chia sẻ PDF: bảng chia sẻ hiện ra sau khi tạo file ở luồng nền")
            self.nav.presentedViewController?.dismiss(animated: false)
        }
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
        // --- nhập ảnh từ Thư viện (gọi thẳng hàm nhập, bỏ qua bộ chọn ảnh hệ thống) ---
        addRun("nhập 1 ảnh") {
            if let img = SelfTest.testImage("doc_4_beige") { self.home?.importImages([img]) }
        }
        addUntil("màn hình cắt (nhập ảnh)", 20) { self.crop != nil }
        addWait(1.2)
        addSnap("22_import_crop")
        addRun("Xong (nhập ảnh)") { self.crop?.doneTapped() }
        addUntil("trình chỉnh sửa (nhập ảnh)", 40) { self.editor != nil && (self.editor?.pending ?? 1) == 0 }
        addWait(0.8)
        addSnap("23_import_editor")
        addRun("lưu (nhập ảnh)") { self.editor?.finishEditing(done: true) }
        addUntil("tài liệu (nhập ảnh)", 40) { self.docVC != nil && self.docVC?.rendering == false }
        addWait(0.8)
        addSnap("24_import_document")
        addRun("về màn hình chính") { self.nav.popToRootViewController(animated: false) }
        addWait(0.8)
        addRun("nhập nhiều ảnh") {
            let imgs = ["doc_3_gray", "doc_5_blue", "doc_6_clutter"].compactMap { SelfTest.testImage($0) }
            self.home?.importImages(imgs)
        }
        addUntil("trình chỉnh sửa (nhập nhiều ảnh)", 60) { self.editor != nil && (self.editor?.pending ?? 1) == 0 }
        addWait(1.0)
        addSnap("25_import_multi_editor")
        addRun("kiểm tra nhập nhiều ảnh") { self.check(self.editor?.doc.pages.count == 3, "nhập nhiều ảnh: 3 trang") }
        addRun("lưu (nhập nhiều ảnh)") { self.editor?.finishEditing(done: true) }
        addUntil("tài liệu (nhập nhiều ảnh)", 40) { self.docVC != nil && self.docVC?.rendering == false }
        addWait(0.8)
        addSnap("26_import_multi_document")
        addRun("về màn hình chính") { self.nav.popToRootViewController(animated: false) }
        addWait(0.8)
        // --- đổi tên, xoá trang, xoá tài liệu (gọi thẳng thao tác sau hộp thoại xác nhận) ---
        addRun("mở tài liệu 2 trang") {
            if let d = DocStore.shared.list().first(where: { $0.pages.count == 2 }) {
                self.targetId = d.id
                self.home?.openDocument(d)
            }
        }
        addUntil("màn hình tài liệu 2 trang", 20) { self.docVC != nil && self.docVC?.rendering == false }
        addRun("đổi tên") { self.docVC?.applyRename("Biên bản thử nghiệm") }
        addWait(0.6)
        addSnap("28_renamed")
        addRun("kiểm tra đổi tên") {
            self.check(DocStore.shared.load(self.targetId)?.title == "Biên bản thử nghiệm", "đổi tên được lưu")
        }
        addRun("mở trình chỉnh sửa để xoá trang") { self.docVC?.openEditor(at: 0) }
        addUntil("trình chỉnh sửa (xoá trang)", 30) { self.editor != nil && (self.editor?.pending ?? 1) == 0 }
        addRun("xoá trang 1") {
            if let ed = self.editor, let p = ed.current { ed.deletePage(p.id) }
        }
        addWait(1.0)
        addSnap("29_page_deleted")
        addRun("kiểm tra xoá trang") {
            self.check(DocStore.shared.load(self.targetId)?.pages.count == 1, "xoá trang: còn 1 trang")
            self.editor?.finishEditing(done: true)
        }
        addUntil("về tài liệu sau xoá trang", 30) { self.docVC != nil && self.docVC?.rendering == false }
        addWait(0.6)
        addRun("xoá tài liệu") { self.docVC?.performDelete() }
        addUntil("về màn hình chính sau xoá", 15) { self.topMost() is HomeViewController }
        addRun("tải lại") { self.home?.reload() }
        addWait(1.0)
        addSnap("30_home_final")
        addRun("kiểm tra danh sách") {
            let n = DocStore.shared.list().count
            self.check(n == 4 && DocStore.shared.load(self.targetId) == nil, "xoá tài liệu: còn 4 tài liệu (\(n))")
        }
        // --- 12 ảnh lớn (12 MP) cùng lúc: hàng đợi xử lý, bộ nhớ, PDF nhiều trang ---
        addRun("nhập 12 ảnh lớn") {
            self.stressStart = Date()
            let names = ["doc_1_wood", "doc_2_dark", "doc_3_gray", "doc_4_beige", "doc_5_blue", "doc_6_clutter"]
            var imgs = [UIImage]()
            let fmt = UIGraphicsImageRendererFormat()
            fmt.scale = 1
            fmt.opaque = true
            let r = UIGraphicsImageRenderer(size: CGSize(width: 3024, height: 4032), format: fmt)
            for i in 0..<12 {
                guard let src = SelfTest.testImage(names[i % names.count]) else { continue }
                imgs.append(r.image { _ in src.draw(in: CGRect(x: 0, y: 0, width: 3024, height: 4032)) })
            }
            self.home?.importImages(imgs)
        }
        addUntil("trình chỉnh sửa 12 trang", 240) {
            self.editor != nil && (self.editor?.pending ?? 1) == 0 && self.editor?.doc.pages.count == 12
        }
        addRun("thời gian xử lý") {
            self.log(String(format: "   nhập + xử lý 12 ảnh 12 MP: %.1fs", Date().timeIntervalSince(self.stressStart)))
        }
        addWait(0.8)
        addSnap("31_stress_editor")
        addRun("lưu (12 ảnh)") { self.editor?.finishEditing(done: true) }
        addUntil("tài liệu 12 trang", 120) { self.docVC != nil && self.docVC?.rendering == false }
        addWait(1.0)
        addSnap("32_stress_document")
        addRun("kiểm tra 12 trang") {
            self.check(self.docVC?.doc.pages.count == 12, "nhập 12 ảnh lớn: tài liệu 12 trang")
            self.verifyCurrentDocPdf()
        }
        addRun("về màn hình chính") { self.nav.popToRootViewController(animated: false) }
        addWait(0.8)
        addRun("kiểm tra danh sách cuối") {
            let n = DocStore.shared.list().count
            self.check(n == 5, "danh sách cuối: 5 tài liệu (\(n))")
        }
        addBeautifySteps()
        addRegressionSteps()
        next()
    }

    // MARK: làm đẹp / chỉnh sửa trang

    private enum Stat { case dark, red, blue, skin }

    /// Ảnh thử edit_page.jpg (tests/tools/make_retouch_images.py): vị trí các chi tiết trên trang ĐÃ NẮN PHẲNG (chuẩn hoá).
    private static let rFinger = CGRect(x: 0.005, y: 0.42, width: 0.045, height: 0.065)
    private static let rSigner = CGRect(x: 0.15, y: 0.775, width: 0.15, height: 0.022)
    private static let rStamp = CGRect(x: 0.65, y: 0.85, width: 0.16, height: 0.115)
    private static let rScribble = CGRect(x: 0.14, y: 0.87, width: 0.28, height: 0.075)
    private static let rScribbleTop = CGRect(x: 0.565, y: 0.237, width: 0.303, height: 0.036)
    private static let rRow9 = CGRect(x: 0.18, y: 0.546, width: 0.13, height: 0.016)
    private static let rRow9Line = CGRect(x: 0.19, y: 0.533, width: 0.11, height: 0.011)
    private static let rBrush = CGRect(x: 0.25, y: 0.103, width: 0.30, height: 0.010)

    /// Tỷ lệ điểm ảnh thuộc một loại trong vùng chuẩn hoá của ảnh kết quả một trang (-1 nếu không đọc được).
    private func stat(_ doc: Doc?, _ index: Int, _ kind: Stat, _ box: CGRect) -> Double {
        guard let d = doc, index >= 0, index < d.pages.count, let o = d.pages[index].out,
              let cg = ImageOps.loadCG(DocStore.shared.url(d, o)), let px = ImageOps.rgbaPixels(cg, maxDim: 1400) else { return -1 }
        let x0 = max(0, min(px.w - 1, Int(box.minX * CGFloat(px.w)))), x1 = min(px.w, max(x0 + 1, Int(box.maxX * CGFloat(px.w))))
        let y0 = max(0, min(px.h - 1, Int(box.minY * CGFloat(px.h)))), y1 = min(px.h, max(y0 + 1, Int(box.maxY * CGFloat(px.h))))
        var c = 0, n = 0
        for y in y0..<y1 {
            for x in x0..<x1 {
                let i = (y * px.w + x) * 4
                let r = Int(px.data[i]), g = Int(px.data[i + 1]), b = Int(px.data[i + 2])
                var hit = false
                switch kind {
                case .dark: hit = r * 299 + g * 587 + b * 114 < 128000
                case .red: hit = r > 140 && g < 115 && b < 115
                case .blue: hit = b > r + 40 && b > 90
                case .skin: hit = r > 120 && r - b > 45
                }
                if hit { c += 1 }
                n += 1
            }
        }
        return n == 0 ? -1 : Double(c) / Double(n)
    }

    private func cur(_ kind: Stat, _ box: CGRect) -> Double {
        return stat(editor?.doc, editor?.index ?? 0, kind, box)
    }

    private func f3(_ v: Double) -> String { return String(format: "%.4f", v) }

    private func editorIdle() -> Bool {
        return nav.presentedViewController == nil && editor != nil && (editor?.pending ?? 1) == 0
    }

    /// Bộ lọc mới, lời nhắc ngón tay, Xóa thông minh, Đánh dấu, Tiêu đề trang, Cắt dán — trên ảnh thử có ngón tay + chữ viết tay.
    private func addBeautifySteps() {
        addRun("nhập ảnh có ngón tay + chữ viết tay") {
            if let img = SelfTest.testImage("edit_page") { self.home?.importImages([img]) } else { self.check(false, "thiếu ảnh thử edit_page.jpg") }
        }
        addUntil("màn hình cắt (ảnh chỉnh sửa)", 20) { self.crop != nil }
        addWait(1.2)
        addRun("Xong (ảnh chỉnh sửa)") { self.crop?.doneTapped() }
        addUntil("trình chỉnh sửa (ảnh chỉnh sửa)", 60) { self.editorIdle() }
        addWait(1.0)
        addSnap("40_finger_banner")
        addRun("kiểm tra lời nhắc ngón tay") {
            self.beautyId = self.editor?.doc.id ?? ""
            self.check(self.editor?.current?.finger == 1, "phát hiện ngón tay giữ giấy trên ảnh thử (finger=\(String(describing: self.editor?.current?.finger)))")
            self.check(self.editor?.bannerVisible == true, "hiện lời nhắc 'Phát hiện ngón tay'")
            self.statBefore["skin"] = self.cur(.skin, SelfTest.rFinger)
            self.statBefore["signer"] = self.cur(.dark, SelfTest.rSigner)
            self.statBefore["stamp"] = self.cur(.red, SelfTest.rStamp)
            self.statBefore["scribble"] = self.cur(.blue, SelfTest.rScribble)
            self.statBefore["scribbleTop"] = self.cur(.blue, SelfTest.rScribbleTop)
            self.statBefore["row9"] = self.cur(.dark, SelfTest.rRow9)
        }
        addRun("Gỡ bỏ") { self.editor?.removeFinger() }
        addUntil("bộ lọc Thần kỳ", 60) { self.editorIdle() }
        addWait(1.0)
        addSnap("41_finger_removed")
        addRun("kiểm tra gỡ ngón tay") {
            let isPro = self.editor?.current?.filter == Enhancer.magicPro
            let hidden = self.editor?.bannerVisible == false
            self.check(isPro && hidden, "Gỡ bỏ -> bộ lọc Thần kỳ, lời nhắc ẩn")
            let a = self.statBefore["skin"] ?? -1, b = self.cur(.skin, SelfTest.rFinger)
            self.check(a > 0.3 && b >= 0 && b < 0.02, "Thần kỳ gỡ ngón tay (màu da \(self.f3(a)) -> \(self.f3(b)))")
            let d0 = self.statBefore["signer"] ?? -1, d1 = self.cur(.dark, SelfTest.rSigner)
            self.check(d0 > 0.03 && d1 > d0 * 0.5, "Thần kỳ giữ chữ in (\(self.f3(d0)) -> \(self.f3(d1)))")
        }
        addRun("mở bảng lọc") { self.editor?.toggleFilterPanel() }
        addWait(1.5)
        addSnap("42_filters_new")
        addRun("Khử nhiễu") { self.editor?.applyFilter(Enhancer.denoise) }
        addUntil("Khử nhiễu xong", 60) { self.editorIdle() }
        addWait(0.6)
        addSnap("42b_filter_denoise")
        addRun("Không bóng") { self.editor?.applyFilter(Enhancer.noShadow) }
        addUntil("Không bóng xong", 60) { self.editorIdle() }
        addWait(0.6)
        addSnap("42c_filter_noshadow")
        addRun("Ko viết tay") { self.editor?.applyFilter(Enhancer.noHand) }
        addUntil("Ko viết tay xong", 60) { self.editorIdle() }
        addWait(0.6)
        addSnap("42d_filter_nohand")
        addRun("kiểm tra Ko viết tay") {
            let b0 = self.statBefore["scribble"] ?? -1, b1 = self.cur(.blue, SelfTest.rScribble), red = self.cur(.red, SelfTest.rStamp)
            self.check(b0 > 0.01 && b1 >= 0 && b1 < b0 * 0.15, "Ko viết tay xoá nét bút xanh (\(self.f3(b0)) -> \(self.f3(b1)))")
            self.check(red > 0.03, "Ko viết tay giữ con dấu đỏ (\(self.f3(red)))")
        }
        addRun("✓ giữ bộ lọc") { self.editor?.closeFilterPanel(keep: true) }
        addWait(0.6)
        addRun("lời nhắc hiện lại rồi tắt hẳn") {
            self.check(self.editor?.bannerVisible == true, "bỏ bộ lọc Thần kỳ -> lời nhắc ngón tay hiện lại")
            self.editor?.dismissBanner()
            let hidden = self.editor?.bannerVisible == false
            let off = self.editor?.current?.fingerOff == true
            self.check(hidden && off, "✕ tắt lời nhắc cho trang này")
        }
        addRun("mở bảng lọc + Đen trắng") {
            self.editor?.toggleFilterPanel()
            self.editor?.applyFilter(Enhancer.bw)
        }
        addUntil("Đen trắng xong", 60) { self.editorIdle() }
        addRun("✕ trả lại bộ lọc cũ") { self.editor?.closeFilterPanel(keep: false) }
        addUntil("trả lại bộ lọc", 60) { self.editorIdle() }
        addRun("kiểm tra ✕") {
            let restored = self.editor?.current?.filter == Enhancer.noHand
            let closed = self.editor?.filterPanelVisible == false
            self.check(restored && closed, "✕ ở bảng lọc trả lại bộ lọc lúc mở bảng")
        }
        addRun("về bộ lọc Tăng cường") {
            self.editor?.toggleFilterPanel()
            self.editor?.applyFilter(Enhancer.magic)
            self.editor?.closeFilterPanel(keep: true)
        }
        addUntil("Tăng cường xong", 60) { self.editorIdle() }
        // --- Xóa thông minh ---
        addRun("mở Xóa thông minh") { self.editor?.openErase() }
        addUntil("màn hình Xóa thông minh", 30) { self.eraser?.testReady == true }
        addWait(0.8)
        addSnap("43_erase_open")
        addRun("Bút: tô lên dòng NGƯỜI LẬP") { self.eraser?.testStroke([CGPoint(x: 0.11, y: 0.786), CGPoint(x: 0.34, y: 0.786)]) }
        addUntil("bút xong", 30) { self.eraser?.testReady == true }
        addRun("Hình chữ nhật quanh con dấu") {
            self.eraser?.testSelect(Retouch.rect)
            self.eraser?.testStroke([CGPoint(x: 0.60, y: 0.838), CGPoint(x: 0.86, y: 0.985)])
        }
        addUntil("hình chữ nhật xong", 30) { self.eraser?.testReady == true }
        addWait(0.4)
        addSnap("43b_erase_pen_rect")
        addRun("Thần kỳ: tô sơ qua nét bút xanh") {
            self.eraser?.testSelect(Retouch.magic)
            self.eraser?.testStroke([CGPoint(x: 0.16, y: 0.885), CGPoint(x: 0.24, y: 0.93)])
        }
        addUntil("thần kỳ xong", 30) { self.eraser?.testReady == true }
        addRun("Xóa văn bản trong bảng") {
            self.eraser?.testSelect(Retouch.text)
            self.eraser?.testStroke([CGPoint(x: 0.17, y: 0.554), CGPoint(x: 0.33, y: 0.554)])
        }
        addUntil("xóa văn bản xong", 30) { self.eraser?.testReady == true }
        addRun("Viết tay") { self.eraser?.eraseHandwriting() }
        addUntil("viết tay xong", 30) { self.eraser?.testReady == true }
        addWait(0.4)
        addSnap("43c_erase_all")
        addRun("hoàn tác") { self.eraser?.undoTapped() }
        addUntil("hoàn tác xong", 30) { self.eraser?.testReady == true }
        addRun("kiểm tra hoàn tác + làm lại") {
            self.check(self.eraser?.ops.count == 4, "hoàn tác bỏ thao tác cuối (\(self.eraser?.ops.count ?? -1))")
            self.eraser?.redoTapped()
        }
        addUntil("làm lại xong", 30) { self.eraser?.testReady == true }
        addRun("giữ So sánh") { self.eraser?.testCompare(true) }
        addWait(0.4)
        addSnap("43d_erase_compare")
        addRun("thả So sánh + ✓") {
            self.eraser?.testCompare(false)
            let kinds = self.eraser?.ops.map { $0.k } ?? []
            self.check(kinds == [Retouch.pen, Retouch.rect, Retouch.magic, Retouch.text, Retouch.hand], "5 thao tác xoá theo thứ tự (\(kinds))")
            self.eraser?.applyTapped()
        }
        addUntil("dựng lại trang sau Xóa thông minh", 60) { self.editorIdle() }
        addWait(1.0)
        addSnap("44_erase_applied")
        addRun("kiểm tra ảnh sau Xóa thông minh") {
            self.check(self.editor?.current?.erase?.count == 5, "trang lưu 5 thao tác xoá")
            let rows: [(String, String, Stat, CGRect, Double, Double)] = [
                ("chữ 'NGƯỜI LẬP' (Bút)", "signer", .dark, SelfTest.rSigner, 0.03, 0.01),
                ("con dấu (Hình chữ nhật)", "stamp", .red, SelfTest.rStamp, 0.03, 0.003),
                ("nét bút xanh dưới (Thần kỳ)", "scribble", .blue, SelfTest.rScribble, 0.01, 0.002),
                ("chữ trong bảng (Xóa văn bản)", "row9", .dark, SelfTest.rRow9, 0.05, 0.02),
                ("nét bút xanh trên (Viết tay)", "scribbleTop", .blue, SelfTest.rScribbleTop, 0.01, 0.003)
            ]
            for r in rows {
                let a = self.statBefore[r.1] ?? -1, b = self.cur(r.2, r.3)
                self.check(a >= r.4 && b >= 0 && b <= r.5, "xoá \(r.0): \(self.f3(a)) -> \(self.f3(b))")
            }
            let line = self.cur(.dark, SelfTest.rRow9Line)
            self.check(line >= 0.06, "Xóa văn bản giữ đường kẻ bảng (\(self.f3(line)))")
        }
        // --- Đánh dấu ---
        addRun("mở Đánh dấu (Brush)") { self.editor?.openMarkup(MarkupViewController.brush) }
        addUntil("màn hình Đánh dấu", 30) { self.markup?.testReady == true }
        addWait(0.8)
        addRun("Brush đen + đỏ") {
            self.markup?.testStroke([CGPoint(x: 0.04, y: 0.10), CGPoint(x: 0.04, y: 0.30)])
            self.markup?.testPickColor(1)
            self.markup?.testStroke([CGPoint(x: 0.2, y: 0.108), CGPoint(x: 0.6, y: 0.108)])
        }
        addRun("Khảm") {
            self.markup?.testSelect(MarkupViewController.mosaic)
            self.markup?.testStroke([CGPoint(x: 0.2, y: 0.45), CGPoint(x: 0.7, y: 0.45)])
        }
        addWait(0.4)
        addSnap("45_markup_brush_mosaic")
        addRun("Thêm văn bản + hình mờ") {
            self.markup?.testSelect(MarkupViewController.text)
            self.markup?.addText("Da_kiem_tra")
            self.markup?.testSelect(MarkupViewController.watermark)
            self.markup?.setWatermark("MAT")
        }
        addWait(0.4)
        addSnap("45b_markup_text_watermark")
        addRun("Ký tên: mở bảng ký") {
            self.markup?.testSelect(MarkupViewController.sign)
            self.markup?.openPad()
        }
        addWait(0.6)
        addRun("ký") {
            self.markup?.testSign([CGPoint(x: 0.15, y: 0.6), CGPoint(x: 0.4, y: 0.3), CGPoint(x: 0.6, y: 0.65), CGPoint(x: 0.85, y: 0.35)])
        }
        addWait(0.4)
        addSnap("45c_sign_pad")
        addRun("Lưu chữ ký") { self.markup?.savePad() }
        addWait(0.6)
        addSnap("45d_sign_placed")
        addRun("kiểm tra chữ ký + kéo") {
            let padClosed = self.markup?.padOpen == false
            let n = self.markup?.marks.count ?? -1
            self.check(padClosed && n == 6, "lưu chữ ký: bảng ký đóng, có 6 đánh dấu (\(n))")
            self.check(DocStore.shared.signLibrary().count == 1, "chữ ký được lưu vào thư viện")
            self.markup?.testStroke([CGPoint(x: 0.5, y: 0.6), CGPoint(x: 0.72, y: 0.9)])
        }
        addWait(0.4)
        addSnap("45e_sign_moved")
        addRun("kiểm tra kéo + hoàn tác / làm lại") {
            var moved = false
            var mx = -1.0, my = -1.0
            if let m = self.markup?.marks.last, m.t == Mark.sign {
                mx = m.x
                my = m.y
                moved = abs(mx - 0.72) < 0.03 && abs(my - 0.9) < 0.03
            }
            self.check(moved, "kéo chữ ký tới vị trí mới (\(mx), \(my))")
            self.markup?.undoTapped()
            var undone = false
            if let b = self.markup?.marks.last { undone = abs(b.x - 0.5) < 0.001 }
            self.check(undone, "hoàn tác trả chữ ký về chỗ cũ")
            self.markup?.redoTapped()
            let types = self.markup?.marks.map { $0.t } ?? []
            self.check(types == [Mark.brush, Mark.brush, Mark.mosaic, Mark.text, Mark.watermark, Mark.sign], "6 đánh dấu theo thứ tự (\(types))")
            self.markup?.applyTapped()
        }
        addUntil("dựng lại trang sau Đánh dấu", 60) { self.editorIdle() }
        addWait(1.0)
        addSnap("46_markup_applied")
        addRun("kiểm tra ảnh sau Đánh dấu") {
            let p = self.editor?.current
            self.marksBefore = p?.marks ?? []
            self.check(self.marksBefore.count == 6, "trang lưu 6 đánh dấu")
            var signOk = false
            if let d = self.editor?.doc, let f = self.marksBefore.last?.f {
                signOk = f.hasPrefix("sg_") && FileManager.default.fileExists(atPath: DocStore.shared.url(d, f).path)
            }
            self.check(signOk, "file chữ ký nằm trong thư mục tài liệu")
            let red = self.cur(.red, SelfTest.rBrush)
            self.check(red > 0.5, "ảnh kết quả có nét brush đỏ (\(self.f3(red)))")
        }
        // --- Tiêu đề trang ---
        addRun("Tiêu đề trang") {
            self.statBefore["hdrTop"] = self.cur(.dark, CGRect(x: 0.40, y: 0.006, width: 0.20, height: 0.030))
            self.editor?.setHeader(PageHeader(text: "BB_2026", num: true))
        }
        addUntil("dựng lại trang có tiêu đề", 60) { self.editorIdle() }
        addWait(1.0)
        addSnap("48_header")
        addRun("kiểm tra tiêu đề trang") {
            self.check(self.editor?.current?.hdr == "BB_2026\n1/1", "tiêu đề trang được điền số trang (\(self.editor?.current?.hdr ?? "nil"))")
            let a = self.statBefore["hdrTop"] ?? -1, b = self.cur(.dark, CGRect(x: 0.40, y: 0.006, width: 0.20, height: 0.030))
            self.check(a >= 0 && b - a > 0.02, "ảnh kết quả có dòng tiêu đề ở lề trên (\(self.f3(a)) -> \(self.f3(b)))")
        }
        // --- cắt lại + xoay sau khi đánh dấu ---
        addRun("mở màn hình cắt") { self.editor?.openCrop() }
        addUntil("màn hình cắt (sau đánh dấu)", 20) { self.crop != nil }
        addWait(1.0)
        addRun("tắt Tự động cắt") { self.crop?.testToggleAuto() }
        addWait(0.8)
        addRun("Xong (cắt lại)") { self.crop?.doneTapped() }
        addUntil("dựng lại sau khi cắt lại", 60) { self.editorIdle() }
        addWait(0.8)
        addSnap("47_markup_recrop")
        addRun("kiểm tra cắt lại") {
            let now = self.editor?.current?.marks ?? []
            let movedMarks = now != self.marksBefore
            self.check(now.count == 6 && movedMarks, "cắt lại: đánh dấu đi theo vùng cắt mới (còn \(now.count))")
            self.check(self.editor?.current?.erase?.count == 5, "cắt lại: vẫn còn 5 thao tác xoá")
            self.editor?.rotateLeft()
        }
        addUntil("xoay trang đã đánh dấu", 60) { self.editorIdle() }
        addRun("Ký tên trên trang đã xoay") { self.editor?.openMarkup(MarkupViewController.sign) }
        addUntil("màn hình Đánh dấu (trang xoay)", 30) { self.markup?.testReady == true }
        addWait(0.8)
        addRun("chọn chữ ký trong thư viện") {
            self.check(self.markup?.padOpen == false, "đã có chữ ký trong thư viện -> không tự mở bảng ký")
            if let u = DocStore.shared.signLibrary().first { self.markup?.placeSign(u) }
        }
        addWait(0.4)
        addSnap("47b_sign_rotated")
        addRun("✓ (chữ ký thứ hai)") {
            let all = self.markup?.marks ?? []
            var upright = false
            var angle = -1
            if let last = all.last {
                angle = last.a
                upright = all.count == 7 && last.t == Mark.sign && last.a == 90
            }
            self.check(upright, "chữ ký trên trang đã xoay nằm thẳng theo chiều hiển thị (góc \(angle))")
            self.markup?.applyTapped()
        }
        addUntil("dựng lại sau chữ ký thứ hai", 60) { self.editorIdle() }
        // --- Cắt dán ---
        addRun("Cắt dán 4 trang mỗi tờ") { self.editor?.collage(layout: 1) }
        addUntil("tài liệu ghép", 60) {
            // màn hình tài liệu ghép đã hiện và tờ ghép đã dựng xong ảnh kết quả
            guard let dv = self.docVC, dv.doc.id != self.beautyId, !dv.rendering else { return false }
            return dv.doc.pages.first?.out != nil
        }
        addWait(1.0)
        addSnap("49_collage")
        addRun("kiểm tra tài liệu ghép") {
            var made = false
            var title = "nil"
            if let d = self.docVC?.doc {
                title = d.title
                made = d.pages.count == 1 && d.title.hasSuffix(" - ghép") && !d.draft && d.id != self.beautyId
                if let p = d.pages.first {
                    let a4 = p.srcW == 2480 && p.srcH == 3508
                    self.check(a4 && p.out != nil, "tờ ghép là A4 dọc 2480x3508 (\(p.srcW)x\(p.srcH))")
                }
            }
            self.check(made, "Cắt dán tạo tài liệu mới 1 tờ (\(title))")
            self.nav.popViewController(animated: false)
        }
        addUntil("về trình chỉnh sửa sau Cắt dán", 20) { self.editorIdle() }
        addRun("xoá tài liệu ghép + gỡ tiêu đề") {
            if let id = self.editor?.lastCollageId, let d = DocStore.shared.load(id) { DocStore.shared.delete(d) }
            self.editor?.setHeader(nil)
        }
        addUntil("gỡ tiêu đề", 60) { self.editorIdle() }
        addRun("lưu tài liệu đã chỉnh sửa") {
            self.check(self.editor?.current?.hdr == nil, "gỡ tiêu đề trang")
            self.editor?.finishEditing(done: true)
        }
        addUntil("tài liệu đã chỉnh sửa", 60) { self.docVC != nil && self.docVC?.rendering == false }
        addWait(1.0)
        addSnap("50_beauty_document")
        addRun("kiểm tra PDF + dọn dẹp") {
            self.verifyCurrentDocPdf()
            let saved = DocStore.shared.load(self.beautyId)
            var kept = false
            if let d = saved, let p = d.pages.first {
                kept = !d.draft && (p.marks ?? []).count == 7 && (p.erase ?? []).count == 5
            }
            self.check(kept, "tài liệu lưu đủ chỉnh sửa (7 đánh dấu, 5 thao tác xoá)")
            self.nav.popToRootViewController(animated: false)
            if let d = saved { DocStore.shared.delete(d) }
            for u in DocStore.shared.signLibrary() { try? FileManager.default.removeItem(at: u) }
            self.home?.reload()
        }
        addWait(0.8)
    }

    /// Kiểm tra lại các lỗi đã sửa ở bản 1.2 (chạm đúp, đóng giữa chừng, trang lỗi, mở ảnh từ ứng dụng khác...).
    private func addRegressionSteps() {
        addRun("tiện ích: tìm kiếm không dấu, tên file, tên tài liệu") { self.runUtilityChecks() }
        // --- khôi phục tài liệu đang quét dở sau khi ứng dụng bị đóng giữa chừng ---
        addRun("khôi phục bản nháp") { self.runDraftRecovery() }
        addRun("tải lại") { self.home?.reload() }
        addWait(0.8)
        addSnap("33_recovered_draft")
        addRun("xoá tài liệu vừa khôi phục") {
            if let d = DocStore.shared.load(self.recoveredId) { DocStore.shared.delete(d) }
            self.home?.reload()
        }
        addWait(0.5)
        // --- chạm đúp nút Xong ở màn hình cắt và nút ✓ ở trình chỉnh sửa ---
        addRun("nhập 1 ảnh (chạm đúp)") {
            self.dirsBefore = self.docDirCount()
            if let img = SelfTest.testImage("doc_5_blue") { self.home?.importImages([img]) }
        }
        addUntil("màn hình cắt (chạm đúp)", 20) { self.crop != nil }
        addWait(1.5)
        addRun("bấm Xong hai lần liên tiếp") {
            let c = self.crop
            c?.doneTapped()
            c?.doneTapped()
        }
        addUntil("trình chỉnh sửa (chạm đúp)", 40) { self.editor != nil && (self.editor?.pending ?? 1) == 0 }
        addWait(0.8)
        addRun("kiểm tra không tạo trùng") {
            let n = self.docDirCount() - self.dirsBefore
            let pages = self.editor?.doc.pages.count ?? -1
            self.check(n == 1 && pages == 1, "chạm đúp Xong ở màn hình cắt: chỉ tạo 1 tài liệu 1 trang (\(n) tài liệu, \(pages) trang)")
            self.check(self.nav.viewControllers.count == 2, "chỉ một trình chỉnh sửa được mở (ngăn xếp \(self.nav.viewControllers.count))")
        }
        addRun("bấm ✓ hai lần liên tiếp") {
            let ed = self.editor
            self.probeDoc = ed?.doc
            ed?.doneTapped()
            ed?.doneTapped()
        }
        addUntil("màn hình tài liệu (chạm đúp)", 40) { self.docVC != nil && self.docVC?.rendering == false }
        addWait(0.8)
        addRun("kiểm tra một màn hình tài liệu") {
            let docs = self.nav.viewControllers.filter { $0 is DocumentViewController }.count
            self.check(docs == 1 && self.nav.viewControllers.count == 2, "chạm đúp ✓: chỉ mở một màn hình tài liệu (\(docs), ngăn xếp \(self.nav.viewControllers.count))")
            self.check(self.probeDoc?.draft == false, "tài liệu vừa nhập đã được lưu")
        }
        // --- mở trình chỉnh sửa rồi đóng mà không sửa: không đổi thời điểm sửa ---
        addRun("mở trình chỉnh sửa, không sửa gì") {
            self.modifiedBefore = self.docVC?.doc.modified ?? Date()
            self.docVC?.openEditor(at: 0)
        }
        addUntil("trình chỉnh sửa (không sửa)", 30) { self.editor != nil && (self.editor?.pending ?? 1) == 0 }
        addWait(0.6)
        addRun("đóng trình chỉnh sửa") { self.editor?.finishEditing(done: true) }
        addUntil("về tài liệu (không sửa)", 30) { self.docVC != nil && self.docVC?.rendering == false }
        addWait(0.6)
        addRun("kiểm tra thời điểm sửa") {
            self.check(self.docVC?.doc.modified == self.modifiedBefore, "chỉ xem không sửa: giữ nguyên thời điểm sửa")
        }
        // --- bấm Xong khi khung cắt đang chuyển động: phải lấy khung đích ---
        addRun("mở lại trình chỉnh sửa") { self.docVC?.openEditor(at: 0) }
        addUntil("trình chỉnh sửa (cắt)", 30) { self.editor != nil && (self.editor?.pending ?? 1) == 0 }
        addWait(0.6)
        addRun("mở màn hình cắt") { self.editor?.openCrop() }
        addUntil("màn hình cắt (khung chuyển động)", 20) { self.crop != nil }
        addWait(1.0)
        addRun("tắt Tự động cắt rồi bấm Xong ngay") {
            guard let c = self.crop else {
                self.check(false, "không có màn hình cắt")
                return
            }
            self.check(c.testAutoOn, "mở lại màn hình cắt: Tự động cắt vẫn bật")
            c.testToggleAuto()
            c.doneTapped()
        }
        addUntil("về trình chỉnh sửa sau cắt", 40) { self.editor != nil && (self.editor?.pending ?? 1) == 0 }
        addWait(0.8)
        addSnap("34_crop_done_during_animation")
        addRun("kiểm tra khung cắt là khung đích") {
            guard let p = self.editor?.current, let q = p.quad, q.count == 8 else {
                self.check(false, "không đọc được khung cắt")
                return
            }
            let w = Double(p.srcW), h = Double(p.srcH)
            let want: [Double] = [0, 0, w, 0, w, h, 0, h]
            var e = 0.0
            for i in 0..<8 { e = max(e, abs(q[i] - want[i])) }
            self.check(e < 1.0 && p.auto == false, String(format: "Xong giữa lúc khung đang chuyển: lấy khung đích (lệch %.2f px)", e))
        }
        // --- mở ảnh từ ứng dụng khác ("Mở bằng") ---
        addRun("mở ảnh ngoài khi đang chỉnh sửa") {
            let ok = self.openURL(self.writeTempImage("open_test.jpg"))
            self.check(ok == false && self.editor != nil && self.nav.presentedViewController == nil,
                       "đang chỉnh sửa: không đóng ngang phiên để mở ảnh ngoài")
        }
        addWait(0.5)
        addSnap("35_open_url_busy")
        addRun("lưu") { self.editor?.finishEditing(done: true) }
        addUntil("về tài liệu (mở ảnh ngoài)", 30) { self.docVC != nil && self.docVC?.rendering == false }
        addWait(0.6)
        addRun("mở file không phải ảnh") {
            let u = FileManager.default.temporaryDirectory.appendingPathComponent("not_image.jpg")
            try? Data("đây không phải ảnh".utf8).write(to: u)
            let ok = self.openURL(u)
            self.check(ok == false && self.docVC != nil, "file không phải ảnh: từ chối, giữ nguyên màn hình")
        }
        addWait(0.5)
        addRun("mở ảnh ngoài khi đang xem tài liệu") {
            self.check(self.openURL(self.writeTempImage("open_test.jpg")), "nhận ảnh từ ứng dụng khác")
        }
        addUntil("màn hình cắt (ảnh ngoài)", 20) { self.crop != nil }
        addWait(1.2)
        addSnap("36_open_url_crop")
        addRun("Hủy") { self.crop?.testCancel() }
        addUntil("về màn hình chính (ảnh ngoài)", 15) { self.topMost() is HomeViewController }
        addWait(0.6)
        // --- thêm trang rồi xoá hết trang trong trình chỉnh sửa: không để lại tài liệu rỗng ---
        addRun("mở tài liệu thử") {
            if let id = self.probeDoc?.id, let d = DocStore.shared.load(id) {
                self.probeDoc = d
                self.home?.openDocument(d)
            } else {
                self.check(false, "không mở lại được tài liệu thử")
            }
        }
        addUntil("màn hình tài liệu thử", 20) { self.docVC != nil && self.docVC?.rendering == false }
        addWait(0.6)
        addRun("Thêm trang") { self.docVC?.addPages() }
        addUntil("máy ảnh (xoá hết trang)", 15) { self.topMost() is CameraViewController }
        addWait(1.0)
        addRun("chụp") { self.camera?.shoot() }
        addUntil("cắt (xoá hết trang)", 30) { self.crop != nil }
        addWait(1.0)
        addRun("Tiếp tục") { self.crop?.doneTapped() }
        addUntil("trình chỉnh sửa (xoá hết trang)", 40) { self.editor != nil && (self.editor?.pending ?? 1) == 0 }
        addWait(0.6)
        addRun("xoá hết các trang") {
            guard let ed = self.editor else {
                self.check(false, "không có trình chỉnh sửa")
                return
            }
            self.check(ed.doc.pages.count == 2, "tài liệu thử có 2 trang trước khi xoá (\(ed.doc.pages.count))")
            for p in ed.doc.pages { ed.deletePage(p.id) }
        }
        addUntil("về máy ảnh", 20) { self.topMost() is CameraViewController }
        addWait(0.8)
        addRun("đóng máy ảnh") { self.camera?.testClose() }
        addUntil("về màn hình chính sau khi xoá hết trang", 20) {
            self.nav.presentedViewController == nil && self.topMost() is HomeViewController
        }
        addWait(0.8)
        addRun("kiểm tra tài liệu rỗng đã bị xoá") {
            let gone = self.probeDoc.map { !FileManager.default.fileExists(atPath: DocStore.shared.dir($0).path) } ?? false
            self.check(gone && self.nav.viewControllers.count == 1, "xoá hết trang lúc thêm trang: tài liệu rỗng bị xoá, về màn hình chính")
        }
        // --- bỏ tài liệu vừa nhập trong lúc các trang đang được xử lý ---
        addRun("nhập 3 ảnh") {
            let imgs = ["doc_1_wood", "doc_2_dark", "doc_3_gray"].compactMap { SelfTest.testImage($0) }
            self.home?.importImages(imgs)
        }
        addUntil("trình chỉnh sửa (bỏ nháp)", 60) { self.editor != nil }
        addRun("xoay rồi bỏ tài liệu khi đang xử lý") {
            guard let ed = self.editor else {
                self.check(false, "không có trình chỉnh sửa")
                return
            }
            self.discardDoc = ed.doc
            ed.rotateLeft()
            self.check(ed.pending > 0, "đang có \(ed.pending) trang chờ xử lý")
            ed.discardDraft()
            ed.discardDraft()
        }
        addUntil("về màn hình chính (bỏ nháp)", 15) { self.topMost() is HomeViewController }
        addWait(4.0)
        addRun("kiểm tra không còn sót tài liệu") {
            let gone = self.discardDoc.map { !FileManager.default.fileExists(atPath: DocStore.shared.dir($0).path) } ?? false
            self.check(gone && self.nav.viewControllers.count == 1, "bỏ tài liệu lúc đang xử lý: không tạo lại thư mục tài liệu")
        }
        // --- trang mất ảnh gốc: báo lỗi rõ ràng, không chờ mãi; xoá trang cuối thì xoá tài liệu ---
        addRun("tài liệu có trang mất ảnh gốc") {
            guard let img = SelfTest.testImage("doc_3_gray") else { return }
            let d = DocStore.shared.create(draft: false)
            _ = try? DocStore.shared.addPage(d, image: img, quad: nil, auto: true)
            if let p = d.pages.first { try? FileManager.default.removeItem(at: DocStore.shared.url(d, p.src)) }
            self.badDoc = d
            self.home?.openDocument(d)
        }
        addWait(1.0)
        addUntil("màn hình tài liệu (trang lỗi)", 20) { self.docVC != nil && self.docVC?.rendering == false }
        addWait(0.6)
        addSnap("37_failed_page")
        addRun("kiểm tra báo lỗi trang") {
            self.check(self.docVC?.testFailedPages == 1, "trang mất ảnh gốc được đánh dấu lỗi (không quay chờ mãi)")
            self.check(self.docVC?.testReady == false, "không cho xuất khi còn trang lỗi")
        }
        addWait(0.6)
        addUntil("thử lại xong", 20) { self.docVC?.rendering == false }
        addRun("mở trình chỉnh sửa trang lỗi") { self.docVC?.openEditor(at: 0) }
        addUntil("trình chỉnh sửa (trang lỗi)", 30) { self.editor != nil && (self.editor?.pending ?? 1) == 0 }
        addWait(1.0)
        addSnap("38_failed_page_editor")
        addRun("xoá trang lỗi") {
            if let ed = self.editor, let p = ed.current { ed.deletePage(p.id) }
        }
        addUntil("về màn hình chính sau khi xoá trang lỗi", 20) { self.topMost() is HomeViewController }
        addWait(0.8)
        addRun("kiểm tra tài liệu lỗi đã xoá") {
            let gone = self.badDoc.map { !FileManager.default.fileExists(atPath: DocStore.shared.dir($0).path) } ?? false
            self.check(gone && self.nav.viewControllers.count == 1, "xoá trang cuối cùng: tài liệu bị xoá, về màn hình chính")
        }
        addRun("tải lại") { self.home?.reload() }
        addWait(1.0)
        addSnap("39_home_end")
        addRun("kiểm tra không còn tài liệu thừa") {
            let n = DocStore.shared.list().count
            let dirs = self.docDirCount()
            self.check(n == 5 && dirs == 5, "sau đợt kiểm thử lỗi: \(n) tài liệu, \(dirs) thư mục (mong đợi 5/5)")
        }
    }

    private func docDirCount() -> Int {
        let items = try? FileManager.default.contentsOfDirectory(atPath: DocStore.shared.root.path)
        return items?.count ?? -1
    }

    private func writeTempImage(_ name: String) -> URL {
        let fm = FileManager.default
        let u = fm.temporaryDirectory.appendingPathComponent(name)
        try? fm.removeItem(at: u)
        if let src = Bundle.main.url(forResource: "doc_4_beige", withExtension: "jpg", subdirectory: "TestImages") {
            try? fm.copyItem(at: src, to: u)
        }
        return u
    }

    private func openURL(_ url: URL) -> Bool {
        guard let d = UIApplication.shared.delegate as? AppDelegate else { return false }
        return d.application(UIApplication.shared, open: url, options: [:])
    }

    private func runUtilityChecks() {
        let m1 = HomeViewController.matches(title: "Biên bản thử nghiệm", query: "bien ban")
        let m2 = HomeViewController.matches(title: "Đơn đặt hàng số 5", query: " don DAT ")
        let m3 = HomeViewController.matches(title: "Scan 01", query: "  ")
        let m4 = HomeViewController.matches(title: "Scan 01", query: "bien")
        check(m1 && m2 && m3 && !m4, "tìm kiếm không phân biệt dấu / hoa thường")

        let long = ImageOps.safeName(String(repeating: "ă", count: 300))
        let odd = ImageOps.safeName("../..\\a:b*c?\"<>|\n\tx")
        let banned = CharacterSet(charactersIn: "/\\:*?\"<>|\n\t")
        let lenOK: Bool = long.count <= 80 && long.utf8.count <= 180
        let oddOK: Bool = !odd.hasPrefix(".") && odd.rangeOfCharacter(from: banned) == nil
        let dotOK: Bool = ImageOps.safeName(" . ") == "TaiLieu"
        check(lenOK && oddOK && dotOK, "tên file an toàn (\(long.count) ký tự / \(long.utf8.count) byte, '\(odd)')")

        let t1: String = Theme.cleanTitle(" \n ", fallback: "Cũ")
        let t2: String = Theme.cleanTitle(String(repeating: "a", count: 500), fallback: "Cũ")
        let t3: String = Theme.cleanTitle("  Hợp đồng\nsố 1 ", fallback: "Cũ")
        let titleOK: Bool = t1 == "Cũ" && t2.count == Theme.maxTitle && t3 == "Hợp đồng số 1"
        check(titleOK, "chuẩn hoá tên tài liệu (bỏ xuống dòng, giới hạn \(Theme.maxTitle) ký tự)")

        let packed: [Double] = Page.pack([CGPoint(x: CGFloat.nan, y: CGFloat.infinity), CGPoint(x: 1, y: 2)])
        var finite = packed.count == 4
        for v in packed where !v.isFinite { finite = false }
        check(finite, "toạ độ không hữu hạn được thay bằng 0 trước khi lưu")

        var emptyThrows = false
        do {
            _ = try ImageOps.exportJob(doc: Doc(id: "khong_ton_tai", title: "Rỗng", draft: false))
        } catch {
            emptyThrows = true
        }
        var pdfThrows = false
        do {
            _ = try PdfWriter.make(jpegs: [], sizeMode: PdfWriter.sizeA4, title: "x")
        } catch {
            pdfThrows = true
        }
        check(emptyThrows && pdfThrows, "tài liệu không có trang: báo lỗi thay vì tạo file PDF hỏng")

        let ghost = DocStore.shared.create(draft: true)
        DocStore.shared.delete(ghost)
        DocStore.shared.save(ghost)
        check(!FileManager.default.fileExists(atPath: DocStore.shared.dir(ghost).path), "lưu sau khi xoá không tạo lại tài liệu")
    }

    private func runDraftRecovery() {
        let fm = FileManager.default
        let store = DocStore.shared
        guard let img = SelfTest.testImage("doc_3_gray") else {
            check(false, "đọc ảnh thử doc_3_gray")
            return
        }
        let before = store.list().count
        // bản nháp 2 trang (ứng dụng bị đóng giữa lúc quét), một trang mất ảnh gốc
        let a = store.create(draft: true)
        _ = try? store.addPage(a, image: img, quad: nil, auto: true)
        _ = try? store.addPage(a, image: img, quad: nil, auto: true)
        if a.pages.count == 2 { try? fm.removeItem(at: store.url(a, a.pages[1].src)) }
        // bản nháp rỗng
        let b = store.create(draft: true)
        // thư mục chưa kịp ghi doc.json
        let junk = store.root.appendingPathComponent("junk_dir", isDirectory: true)
        try? fm.createDirectory(at: junk, withIntermediateDirectories: true)
        // doc.json hỏng (không được xoá: có thể vẫn còn ảnh của người dùng)
        let broken = store.root.appendingPathComponent("broken_doc", isDirectory: true)
        try? fm.createDirectory(at: broken, withIntermediateDirectories: true)
        try? Data("{ hỏng".utf8).write(to: broken.appendingPathComponent("doc.json"))
        let n = store.startupMaintenance()
        let ra = store.load(a.id)
        let raPages: Int = ra?.pages.count ?? -1
        let raDraft: Bool = ra?.draft ?? true
        check(n == 1 && !raDraft && raPages == 1, "khôi phục tài liệu đang quét dở: \(n) tài liệu, \(raPages) trang còn ảnh gốc")
        let emptyGone: Bool = !fm.fileExists(atPath: store.dir(b).path)
        let junkGone: Bool = !fm.fileExists(atPath: junk.path)
        check(emptyGone && junkGone, "dọn bản nháp rỗng và thư mục chưa có doc.json")
        let brokenKept: Bool = fm.fileExists(atPath: broken.path)
        let listed: Int = store.list().count
        check(brokenKept && listed == before + 1, "thư mục có doc.json hỏng được giữ nguyên; tài liệu khôi phục hiện trong danh sách")
        try? fm.removeItem(at: broken)
        recoveredId = a.id
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
            var okAll = 0, okDD = 0, total = 0
            func err(_ q: [CGPoint]?, _ gt: [Double], _ diag: Double) -> Double {
                guard let q = q, q.count == 4 else { return 1 }
                var e = 0.0
                for i in 0..<4 { e = max(e, hypot(Double(q[i].x) - gt[2 * i], Double(q[i].y) - gt[2 * i + 1]) / diag) }
                return e
            }
            if let gtURL = Bundle.main.url(forResource: "gt", withExtension: "txt", subdirectory: "TestImages"),
               let text = try? String(contentsOf: gtURL, encoding: .utf8) {
                for line in text.split(separator: "\n") {
                    let parts = line.split(separator: " ").map(String.init)
                    guard parts.count >= 9 else { continue }
                    let name = (parts[0] as NSString).deletingPathExtension
                    let gt = parts[1...8].compactMap { Double($0) }
                    total += 1
                    guard gt.count == 8, let img = SelfTest.testImage(name), let cg = img.cgImage else {
                        out.append("FAIL \(name): không đọc được ảnh")
                        continue
                    }
                    let diag = hypot(Double(cg.width), Double(cg.height))
                    let t0 = Date()
                    let det = ImageOps.detectDetailed(cg)
                    let ms = Int(Date().timeIntervalSince(t0) * 1000)
                    let t1 = Date()
                    let dd = ImageOps.docDetector(cg)
                    let msDD = Int(Date().timeIntervalSince(t1) * 1000)
                    let vis = ImageOps.visionSegment(cg)
                    let eAll = err(det.quad, gt, diag), eDD = err(dd?.quad, gt, diag), eVis = err(vis?.quad, gt, diag)
                    if eAll < 0.01 { okAll += 1 }
                    if eDD < 0.01 { okDD += 1 }
                    out.append(String(format: "%@ %@ err=%.4f (%.1fpx) %ldms nguồn=%@ | DocDetector err=%.4f điểm=%.3f biên=%ld %ldms | Vision err=%.4f tin cậy=%.2f",
                                      eAll < 0.01 ? "PASS" : "FAIL", name, eAll, eAll * diag, ms, det.source,
                                      eDD, dd?.score ?? 0, dd?.borders ?? -1, msDD, eVis, vis?.confidence ?? 0))
                }
            }
            let lines = out, nAll = okAll, nDD = okDD, nTotal = total
            DispatchQueue.main.async {
                for l in lines { self.log("   " + l) }
                self.check(nTotal > 0 && nDD == nTotal, "DocDetector (Swift) trùng bản Java: \(nDD)/\(nTotal) ảnh sai số <1% đường chéo")
                self.check(nTotal > 0 && nAll * 6 >= nTotal * 5, "tìm mép (DocDetector + Vision) \(nAll)/\(nTotal) ảnh sai số <1% đường chéo")
                self.algoDone = true
            }
        }
    }

    // MARK: dựng tài liệu + PDF

    /// Nắn phẳng bằng toạ độ góc thật (gt.txt): kiểm tra riêng khâu nắn phẳng + tỷ lệ khổ giấy.
    private func warpWithGroundTruth() {
        guard let gtURL = Bundle.main.url(forResource: "gt", withExtension: "txt", subdirectory: "TestImages"),
              let text = try? String(contentsOf: gtURL, encoding: .utf8),
              let line = text.split(separator: "\n").first(where: { $0.hasPrefix("doc_1_wood") }) else {
            check(false, "đọc gt.txt")
            return
        }
        let gt = line.split(separator: " ").dropFirst().compactMap { Double($0) }
        guard gt.count == 8, let img = SelfTest.testImage("doc_1_wood"), let cg = img.cgImage else {
            check(false, "đọc doc_1_wood")
            return
        }
        let q = (0..<4).map { CGPoint(x: gt[2 * $0], y: gt[2 * $0 + 1]) }
        let size = Geom.outputSize(q, imgW: Double(cg.width), imgH: Double(cg.height), focal: -1, snap: true, maxLong: ImageOps.maxOut)
        guard let w = ImageOps.warp(cg, quad: q, size: size) else {
            check(false, "nắn phẳng với góc thật")
            return
        }
        try? ImageOps.writeJPEG(w, to: SelfTest.outDir.appendingPathComponent("warp_gt_doc_1.jpg"), quality: 0.85)
        let r = Double(w.width) / Double(w.height)
        check(abs(r - Geom.a4Ratio) < 0.02, String(format: "nắn phẳng với góc thật: %ldx%ld, tỷ lệ %.3f", w.width, w.height, r))
        // góc trên-trái của trang phải là giấy trắng (không lẫn nền gỗ), giữa trang có chữ (điểm tối)
        if let px = ImageOps.rgbaPixels(w, maxDim: 400) {
            func lum(_ x: Int, _ y: Int) -> Int {
                let i = (y * px.w + x) * 4
                return (299 * Int(px.data[i]) + 587 * Int(px.data[i + 1]) + 114 * Int(px.data[i + 2])) / 1000
            }
            let corner = lum(px.w / 40 + 2, px.h / 40 + 2)
            var dark = 0
            for y in stride(from: px.h / 10, to: px.h * 9 / 10, by: 2) {
                for x in stride(from: px.w / 10, to: px.w * 9 / 10, by: 2) where lum(x, y) < 100 { dark += 1 }
            }
            check(corner > 150 && dark > 50, "nội dung sau nắn phẳng: góc sáng \(corner), \(dark) điểm chữ")
        }
    }

    private func buildDocumentAndPdf() {
        warpWithGroundTruth()
        let names = ["doc_1_wood", "doc_2_dark", "doc_6_clutter"]
        let d = DocStore.shared.create(draft: true)
        for n in names {
            guard let img = SelfTest.testImage(n) else {
                check(false, "đọc ảnh thử \(n)")
                continue
            }
            let norm = ImageOps.normalized(img, maxDim: ImageOps.maxSrc)
            let q = norm.cgImage.flatMap { ImageOps.detect($0) }
            var desc = "không có"
            if let pts = q {
                var parts = [String]()
                for pt in pts { parts.append("(\(Int(pt.x)),\(Int(pt.y)))") }
                desc = parts.joined(separator: " ")
            }
            let dims = norm.cgImage.map { "\($0.width)x\($0.height)" } ?? "?"
            log("   \(n): \(dims) góc=\(desc)")
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
        // tỷ lệ trang sau khi nắn phẳng, so với bản Java (Geom.java) trên cùng tứ giác:
        // doc_2_dark có phối cảnh tổng hợp không theo mô hình máy ảnh nên ước lượng ra 0.666 (không bắt về A4)
        let expected = [Geom.a4Ratio, 0.666, Geom.a4Ratio]
        for (i, p) in d.pages.enumerated() {
            guard let o = p.out, let cg = ImageOps.loadCG(DocStore.shared.url(d, o)) else {
                check(false, "ảnh kết quả trang \(i + 1)")
                continue
            }
            let r = Double(min(cg.width, cg.height)) / Double(max(cg.width, cg.height))
            let e = i < expected.count ? expected[i] : Geom.a4Ratio
            check(abs(r - e) < 0.01, String(format: "trang %ld: %ldx%ld, tỷ lệ %.3f (bản Java: %.3f)", i + 1, cg.width, cg.height, r, e))
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
            let job = try ImageOps.exportJob(doc: d)
            let data = try Data(contentsOf: try ImageOps.exportPDF(job: job))
            try data.write(to: SelfTest.outDir.appendingPathComponent("test_\(d.pages.count)p.pdf"))
            // bản ghi theo luồng (đọc từng trang từ đĩa) phải trùng từng byte với bản tạo trong bộ nhớ
            let jpegs = try job.files.map { try Data(contentsOf: $0) }
            let same = try PdfWriter.make(jpegs: jpegs, sizeMode: job.pdfSize, title: job.title) == data
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
            check(head.hasPrefix("%PDF-1.4") && tail.contains("%%EOF") && pages == d.pages.count && same,
                  "\(label): \(data.count / 1024) KB, \(pages) trang, ghi theo luồng \(same ? "trùng" : "KHÁC") bản trong bộ nhớ")
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
