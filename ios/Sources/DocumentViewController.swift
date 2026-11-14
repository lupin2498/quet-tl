import Photos
import UIKit
import UniformTypeIdentifiers

/// Màn hình tài liệu: lưới trang, Thêm / Sửa / Chia sẻ / Word / Lưu PDF / Lưu ảnh, khổ giấy PDF + Word.
final class DocumentViewController: UIViewController, UICollectionViewDataSource, UICollectionViewDelegateFlowLayout, UIDocumentPickerDelegate {
    let doc: Doc
    private var collection: UICollectionView!
    private let layout = UICollectionViewFlowLayout()
    private let infoLabel = Theme.label("", size: 13, color: Theme.text2)
    private let titleBtn = UIButton(type: .system)
    private let cache = NSCache<NSString, UIImage>()
    private(set) var rendering = false
    /// Có yêu cầu xử lý lại trong lúc đang xử lý (chạy tiếp một lượt sau khi lượt hiện tại xong).
    private var renderAgain = false
    /// Trang xử lý lỗi (khoá = tên ảnh kết quả mong muốn): báo rõ thay vì "đang xử lý" mãi.
    private var failed = Set<String>()
    private let busy = BusyOverlay()
    private var exporting = false
    private var closed = false
    /// Loại file vừa đưa vào bộ chọn nơi lưu (để báo "Đã lưu …" cho đúng).
    private var savingWord = false
    /// Cờ huỷ của lần chuyển sang Word đang chạy.
    private var wordCancel: WordExport.CancelFlag?

    init(doc: Doc) {
        self.doc = doc
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = Theme.bg
        buildUI()
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        navigationController?.setNavigationBarHidden(false, animated: animated)
        reload()
        ensureRendered()
    }

    /// Màn hình này đang ở trên cùng và không có gì che (chống mở trùng khi chạm đúp).
    private var isFrontmost: Bool {
        if closed || exporting { return false }
        // hộp thoại đang đóng dở (vừa chọn một mục trong đó) không tính là đang che
        if let p = presentedViewController, !p.isBeingDismissed { return false }
        return navigationController?.topViewController === self
    }

    /// Tài liệu không còn trang nào (đã xoá hết trong trình chỉnh sửa / lúc thêm trang) -> xoá và về màn hình chính.
    /// Trả về true nếu đã đóng.
    @discardableResult
    private func closeIfEmpty() -> Bool {
        if closed { return true }
        guard doc.deleted || doc.pages.isEmpty else { return false }
        closed = true
        if !doc.deleted { DocStore.shared.delete(doc) }
        navigationController?.popToRootViewController(animated: true)
        return true
    }

    private func buildUI() {
        titleBtn.setTitleColor(.white, for: .normal)
        titleBtn.titleLabel?.font = UIFont.systemFont(ofSize: 16, weight: .semibold)
        titleBtn.titleLabel?.lineBreakMode = .byTruncatingTail
        titleBtn.addTarget(self, action: #selector(rename), for: .touchUpInside)
        titleBtn.frame = CGRect(x: 0, y: 0, width: 220, height: 36)
        titleBtn.accessibilityHint = "Đổi tên tài liệu"
        navigationItem.titleView = titleBtn
        let more = UIBarButtonItem(image: Theme.symbol("ellipsis.circle", size: 18), style: .plain, target: self, action: #selector(moreTapped))
        more.accessibilityLabel = "Tùy chọn khác"
        navigationItem.rightBarButtonItem = more
        navigationItem.backButtonDisplayMode = .minimal

        layout.minimumLineSpacing = 18
        layout.minimumInteritemSpacing = 14
        layout.sectionInset = UIEdgeInsets(top: 14, left: 16, bottom: 20, right: 16)
        collection = UICollectionView(frame: .zero, collectionViewLayout: layout)
        collection.backgroundColor = .clear
        collection.dataSource = self
        collection.delegate = self
        collection.register(ThumbCell.self, forCellWithReuseIdentifier: "thumb")
        collection.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(collection)
        infoLabel.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(infoLabel)

        let bar = UIView()
        bar.backgroundColor = Theme.bgDark
        bar.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(bar)
        let items: [(String, String, Selector)] = [
            ("plus.rectangle.on.rectangle", "Thêm", #selector(addPages)),
            ("slider.horizontal.3", "Sửa", #selector(editPages)),
            ("square.and.arrow.up", "Chia sẻ", #selector(shareTapped)),
            ("doc.text", "Word", #selector(wordTapped)),
            ("doc.richtext", "Lưu PDF", #selector(savePDF)),
            ("square.and.arrow.down", "Lưu ảnh", #selector(saveImages))
        ]
        let tools = UIStackView()
        tools.axis = .horizontal
        tools.distribution = .fillEqually
        tools.translatesAutoresizingMaskIntoConstraints = false
        for (sym, title, sel) in items {
            let b = Theme.toolButton(sym, title)
            b.addTarget(self, action: sel, for: .touchUpInside)
            tools.addArrangedSubview(b)
        }
        bar.addSubview(tools)

        let g = view.safeAreaLayoutGuide
        NSLayoutConstraint.activate([
            infoLabel.topAnchor.constraint(equalTo: g.topAnchor, constant: 8),
            infoLabel.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 16),
            collection.topAnchor.constraint(equalTo: infoLabel.bottomAnchor, constant: 2),
            collection.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            collection.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            collection.bottomAnchor.constraint(equalTo: bar.topAnchor),
            bar.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            bar.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            bar.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            bar.topAnchor.constraint(equalTo: g.bottomAnchor, constant: -64),
            tools.leadingAnchor.constraint(equalTo: bar.leadingAnchor, constant: 4),
            tools.trailingAnchor.constraint(equalTo: bar.trailingAnchor, constant: -4),
            tools.topAnchor.constraint(equalTo: bar.topAnchor, constant: 4),
            tools.heightAnchor.constraint(equalToConstant: 58)
        ])
    }

    func reload() {
        titleBtn.setTitle(doc.title, for: .normal)
        infoLabel.text = "\(Theme.dateString(doc.modified))   ·   \(doc.pages.count) trang   ·   PDF \(DocStore.pdfSizeName(DocStore.shared.pdfSize))"
        collection?.reloadData()
    }

    private func needsRender(_ p: Page) -> Bool {
        return p.out != ImageOps.outName(p) || !DocStore.shared.outReady(doc, p)
    }

    /// Sinh ảnh kết quả cho các trang còn thiếu (vd. lần trước bị ngắt giữa chừng).
    /// retryFailed = true: thử lại cả những trang lần trước xử lý lỗi.
    private func ensureRendered(retryFailed: Bool = false) {
        if rendering {
            // đang chạy một lượt: không chạy song song lượt thứ hai trên cùng các trang, chỉ ghi nhận để chạy tiếp
            renderAgain = true
            return
        }
        if retryFailed { failed.removeAll() }
        let todo = doc.pages.filter { needsRender($0) && !failed.contains(ImageOps.outName($0)) }
        guard !todo.isEmpty, !doc.deleted else { return }
        rendering = true
        let dir = DocStore.shared.dir(doc)
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            var rendered = [Page]()
            var bad = [String]()
            for p in todo {
                if let np = try? ImageOps.render(dir: dir, page: p) {
                    rendered.append(np)
                } else {
                    bad.append(ImageOps.outName(p))
                }
            }
            let done = rendered, failedNow = bad
            DispatchQueue.main.async {
                guard let self = self else { return }
                var changed = false
                for np in done {
                    guard let i = self.doc.index(of: np.id) else { continue }
                    // trang có thể đã được sửa ở màn hình khác trong lúc xử lý: chỉ nhận kết quả nếu còn đúng tham số
                    if ImageOps.outName(self.doc.pages[i]) == np.out {
                        self.doc.pages[i].takeResult(np)
                        changed = true
                    }
                }
                self.failed.formUnion(failedNow)
                if changed { DocStore.shared.save(self.doc) }
                self.rendering = false
                self.reload()
                if self.renderAgain {
                    self.renderAgain = false
                    self.ensureRendered()
                }
            }
        }
    }

    /// Mọi trang đã có ảnh kết quả chưa; nếu chưa thì báo lý do (đang xử lý / trang nào bị lỗi).
    private func ready() -> Bool {
        if doc.pages.isEmpty { return false }
        if rendering {
            toast("Đang xử lý trang, vui lòng đợi giây lát")
            return false
        }
        guard let i = doc.pages.firstIndex(where: { needsRender($0) }) else { return true }
        if let bad = doc.pages.firstIndex(where: { needsRender($0) && failed.contains(ImageOps.outName($0)) }) {
            toast("Trang \(bad + 1) không xử lý được — hãy mở Sửa để chụp lại hoặc xoá trang này")
        } else {
            toast("Đang xử lý trang \(i + 1), vui lòng đợi giây lát")
        }
        ensureRendered(retryFailed: true)
        return false
    }

    /// Móc kiểm thử (SelfTest): trạng thái sẵn sàng xuất và số trang xử lý lỗi.
    var testReady: Bool { ready() }
    var testFailedPages: Int { doc.pages.filter { needsRender($0) && failed.contains(ImageOps.outName($0)) }.count }

    // MARK: lưới trang

    func collectionView(_ collectionView: UICollectionView, numberOfItemsInSection section: Int) -> Int {
        return doc.pages.count
    }

    func collectionView(_ collectionView: UICollectionView, layout collectionViewLayout: UICollectionViewLayout, sizeForItemAt indexPath: IndexPath) -> CGSize {
        let w = floor((collectionView.bounds.width - 16 * 2 - 14) / 2)
        return CGSize(width: max(60, w), height: max(60, w) * 1.414 + 26)
    }

    func collectionView(_ collectionView: UICollectionView, cellForItemAt indexPath: IndexPath) -> UICollectionViewCell {
        let cell = collectionView.dequeueReusableCell(withReuseIdentifier: "thumb", for: indexPath) as! ThumbCell
        cell.imageView.image = nil
        cell.failedMark.isHidden = true
        // tài liệu dùng chung với trình chỉnh sửa / máy ảnh: số trang có thể vừa đổi trước khi lưới kịp tải lại
        guard indexPath.item < doc.pages.count else {
            cell.pageId = nil
            cell.number.text = ""
            cell.spinner.stopAnimating()
            return cell
        }
        let p = doc.pages[indexPath.item]
        cell.number.text = "\(indexPath.item + 1)"
        cell.pageId = p.id
        cell.spinner.startAnimating()
        guard let o = p.out, !needsRender(p) else {
            if failed.contains(ImageOps.outName(p)) {
                // trang lỗi: không quay vòng chờ mãi
                cell.spinner.stopAnimating()
                cell.failedMark.isHidden = false
            }
            return cell
        }
        let key = "\(p.id)_\(o)" as NSString
        if let img = cache.object(forKey: key) {
            cell.imageView.image = img
            cell.spinner.stopAnimating()
            return cell
        }
        let url = DocStore.shared.url(doc, o)
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            let img = ImageOps.thumbnail(url, maxPixel: 520)
            DispatchQueue.main.async {
                guard let self = self, let img = img else { return }
                self.cache.setObject(img, forKey: key)
                if cell.pageId == p.id {
                    cell.imageView.image = img
                    cell.spinner.stopAnimating()
                }
            }
        }
        return cell
    }

    func collectionView(_ collectionView: UICollectionView, didSelectItemAt indexPath: IndexPath) {
        guard indexPath.item < doc.pages.count else { return }
        openEditor(at: indexPath.item)
    }

    // MARK: thao tác

    func openEditor(at page: Int) {
        // chạm đúp một trang / nút Sửa: chỉ mở một trình chỉnh sửa
        guard isFrontmost, !doc.pages.isEmpty else { return }
        let ed = EditorViewController(doc: doc, context: .existing, startPage: page)
        ed.onDone = { [weak self] _ in
            guard let self = self else { return }
            // trang cuối cùng bị xoá trong trình chỉnh sửa -> tài liệu đã bị xoá
            if self.closeIfEmpty() { return }
            self.navigationController?.popToViewController(self, animated: true)
            self.failed.removeAll()
            self.reload()
        }
        navigationController?.pushViewController(ed, animated: true)
    }

    @objc private func editPages() { openEditor(at: 0) }

    @objc func addPages() {
        guard isFrontmost else { return }
        let cam = CameraViewController(mode: .add(doc))
        cam.onFinished = { [weak self] _ in
            self?.dismiss(animated: true) {
                guard let self = self else { return }
                // xoá hết trang trong lúc xem lại các trang vừa chụp -> không để lại tài liệu rỗng
                if self.closeIfEmpty() { return }
                self.failed.removeAll()
                self.reload()
                self.ensureRendered()
            }
        }
        let nav = UINavigationController(rootViewController: cam)
        nav.modalPresentationStyle = .fullScreen
        present(nav, animated: true)
    }

    @objc private func rename() {
        guard isFrontmost else { return }
        prompt("Đổi tên tài liệu", initial: doc.title) { [weak self] v in
            self?.applyRename(v)
        }
    }

    func applyRename(_ v: String) {
        let t = Theme.cleanTitle(v, fallback: doc.title)
        guard t != doc.title, !doc.deleted else { return }
        doc.title = t
        doc.modified = Date()
        DocStore.shared.save(doc)
        reload()
    }

    @objc private func moreTapped() {
        guard isFrontmost else { return }
        let a = UIAlertController(title: nil, message: nil, preferredStyle: .actionSheet)
        a.addAction(UIAlertAction(title: "Đổi tên", style: .default) { [weak self] _ in self?.rename() })
        a.addAction(UIAlertAction(title: "Khổ giấy PDF / Word: \(DocStore.pdfSizeName(DocStore.shared.pdfSize))", style: .default) { [weak self] _ in
            self?.choosePdfSize()
        })
        a.addAction(UIAlertAction(title: "Xoá tài liệu", style: .destructive) { [weak self] _ in self?.deleteDoc() })
        a.addAction(UIAlertAction(title: "Hủy", style: .cancel))
        a.popoverPresentationController?.barButtonItem = navigationItem.rightBarButtonItem
        present(a, animated: true)
    }

    func choosePdfSize() {
        guard isFrontmost else { return }
        let names = ["A4 (210 × 297 mm)", "Letter (216 × 279 mm)", "Theo kích thước ảnh"]
        let values = [PdfWriter.sizeA4, PdfWriter.sizeLetter, PdfWriter.sizeFit]
        let cur = DocStore.shared.pdfSize
        let a = UIAlertController(title: "Khổ giấy PDF / Word", message: nil, preferredStyle: .actionSheet)
        for (i, n) in names.enumerated() {
            let v = values[i]
            a.addAction(UIAlertAction(title: (v == cur ? "✓ " : "") + n, style: .default) { [weak self] _ in
                DocStore.shared.pdfSize = v
                self?.reload()
            })
        }
        a.addAction(UIAlertAction(title: "Hủy", style: .cancel))
        a.popoverPresentationController?.sourceView = view
        present(a, animated: true)
    }

    private func deleteDoc() {
        guard isFrontmost else { return }
        confirm("Xoá tài liệu?", message: "\"\(doc.title)\" sẽ bị xoá vĩnh viễn.", ok: "Xoá") { [weak self] in
            self?.performDelete()
        }
    }

    func performDelete() {
        if closed { return }
        closed = true
        DocStore.shared.delete(doc)
        navigationController?.popToRootViewController(animated: true)
    }

    private enum ExportKind {
        case pdf, images, word
    }

    /// Tạo file xuất ở luồng nền (tài liệu nhiều trang mất vài giây) rồi gọi `then` ở luồng chính.
    /// Word: nhận dạng chữ từng trang nên lâu hơn — màn hình chờ hiện số trang và có nút Huỷ.
    private func export(_ kind: ExportKind, then: @escaping ([URL]) -> Void) {
        guard !exporting, !closed else { return }
        let job: ImageOps.ExportJob
        do {
            job = try ImageOps.exportJob(doc: doc)
        } catch {
            toast("Lỗi: \(error.localizedDescription)")
            return
        }
        exporting = true
        let host: UIView = navigationController?.view ?? view
        let cancel = WordExport.CancelFlag()
        wordCancel = kind == .word ? cancel : nil
        switch kind {
        case .pdf: busy.show(in: host, text: "Đang tạo PDF…")
        case .images: busy.show(in: host, text: "Đang chuẩn bị ảnh…")
        case .word: busy.show(in: host, text: "Đang chuyển sang Word…", onCancel: { cancel.cancel() })
        }
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            var note = ""
            let result: Result<[URL], Error> = Result {
                switch kind {
                case .pdf:
                    return [try ImageOps.exportPDF(job: job)]
                case .images:
                    return try ImageOps.exportImages(job: job)
                case .word:
                    let r = try WordExport.export(job: job, cancel: cancel) { index, total in
                        DispatchQueue.main.async {
                            self?.busy.setText(index < total ? "Đang chuyển sang Word… (trang \(index + 1)/\(total))" : "Đang ghi file Word…")
                        }
                    }
                    note = r.stats.note
                    return [r.url]
                }
            }
            let noteText = note
            DispatchQueue.main.async {
                guard let self = self else { return }
                self.busy.hide()
                self.exporting = false
                self.wordCancel = nil
                switch result {
                case .success(let urls):
                    // người dùng đã rời màn hình trong lúc tạo file -> không bật bảng chia sẻ lên màn hình khác
                    guard self.isFrontmost else { return }
                    if !noteText.isEmpty { self.toast("Đã tạo file Word" + noteText) }
                    then(urls)
                case .failure(let error):
                    if error is WordExport.Cancelled { return }
                    self.toast("Lỗi: \(error.localizedDescription)")
                }
            }
        }
    }

    @objc private func shareTapped() {
        guard isFrontmost, ready() else { return }
        let a = UIAlertController(title: "Chia sẻ", message: nil, preferredStyle: .actionSheet)
        a.addAction(UIAlertAction(title: "Chia sẻ PDF (\(DocStore.pdfSizeName(DocStore.shared.pdfSize)))", style: .default) { [weak self] _ in
            self?.share(pdf: true)
        })
        a.addAction(UIAlertAction(title: "Chia sẻ ảnh JPG", style: .default) { [weak self] _ in
            self?.share(pdf: false)
        })
        a.addAction(UIAlertAction(title: "Chia sẻ Word (.docx)", style: .default) { [weak self] _ in
            self?.shareWord()
        })
        a.addAction(UIAlertAction(title: "Hủy", style: .cancel))
        a.popoverPresentationController?.sourceView = view
        present(a, animated: true)
    }

    private func presentShare(_ items: [URL]) {
        let vc = UIActivityViewController(activityItems: items, applicationActivities: nil)
        vc.popoverPresentationController?.sourceView = view
        present(vc, animated: true)
    }

    func share(pdf: Bool) {
        guard isFrontmost, ready() else { return }
        export(pdf ? .pdf : .images) { [weak self] items in
            self?.presentShare(items)
        }
    }

    /// Chuyển sang Word: nhận dạng chữ rồi dàn lại đúng bố cục (chữ, bảng; con dấu / chữ ký giữ dạng hình).
    @objc private func wordTapped() {
        guard isFrontmost, ready() else { return }
        let a = UIAlertController(title: "Chuyển sang Word (.docx)", message: nil, preferredStyle: .actionSheet)
        a.addAction(UIAlertAction(title: "Lưu file Word vào máy", style: .default) { [weak self] _ in
            self?.saveWord()
        })
        a.addAction(UIAlertAction(title: "Chia sẻ file Word", style: .default) { [weak self] _ in
            self?.shareWord()
        })
        a.addAction(UIAlertAction(title: "Hủy", style: .cancel))
        a.popoverPresentationController?.sourceView = view
        present(a, animated: true)
    }

    func shareWord() {
        guard isFrontmost, ready() else { return }
        export(.word) { [weak self] items in
            self?.presentShare(items)
        }
    }

    func saveWord() {
        guard isFrontmost, ready() else { return }
        export(.word) { [weak self] urls in
            self?.presentSavePicker(urls, word: true)
        }
    }

    private func presentSavePicker(_ urls: [URL], word: Bool) {
        savingWord = word
        let picker = UIDocumentPickerViewController(forExporting: urls, asCopy: true)
        picker.delegate = self
        present(picker, animated: true)
    }

    @objc private func savePDF() {
        guard isFrontmost, ready() else { return }
        export(.pdf) { [weak self] urls in
            self?.presentSavePicker(urls, word: false)
        }
    }

    func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        toast(savingWord ? "Đã lưu file Word" : "Đã lưu PDF")
    }

    /// Móc kiểm thử (SelfTest): đang tạo file xuất; bấm Huỷ trong lúc chuyển sang Word.
    var testExporting: Bool { exporting }
    func testCancelWord() { wordCancel?.cancel() }

    @objc private func saveImages() {
        guard isFrontmost, ready() else { return }
        export(.images) { [weak self] urls in
            self?.addToPhotos(urls)
        }
    }

    private func addToPhotos(_ urls: [URL]) {
        PHPhotoLibrary.requestAuthorization(for: .addOnly) { [weak self] status in
            guard status == .authorized || status == .limited else {
                DispatchQueue.main.async {
                    self?.toast("Cần quyền Ảnh để lưu ảnh — hãy cấp quyền trong Cài đặt")
                }
                return
            }
            PHPhotoLibrary.shared().performChanges({
                for u in urls {
                    PHAssetCreationRequest.forAsset().addResource(with: .photo, fileURL: u, options: nil)
                }
            }, completionHandler: { ok, err in
                DispatchQueue.main.async {
                    if ok {
                        self?.toast("Đã lưu \(urls.count) ảnh vào Thư viện")
                    } else {
                        self?.toast("Lỗi lưu ảnh: \(err?.localizedDescription ?? "không rõ nguyên nhân")")
                    }
                }
            })
        }
    }
}

final class ThumbCell: UICollectionViewCell {
    let imageView = UIImageView()
    let number = Theme.label("", size: 12, color: Theme.text2)
    let spinner = UIActivityIndicatorView(style: .medium)
    /// Dấu báo trang không xử lý được.
    let failedMark = UIImageView(image: Theme.symbol("exclamationmark.triangle.fill", size: 26))
    var pageId: String?

    override init(frame: CGRect) {
        super.init(frame: frame)
        imageView.contentMode = .scaleAspectFit
        imageView.backgroundColor = UIColor(white: 1, alpha: 0.06)
        spinner.color = Theme.accent
        spinner.hidesWhenStopped = true
        number.textAlignment = .center
        failedMark.tintColor = Theme.red
        failedMark.isHidden = true
        failedMark.accessibilityLabel = "Trang không xử lý được"
        [imageView, number, spinner, failedMark].forEach {
            $0.translatesAutoresizingMaskIntoConstraints = false
            contentView.addSubview($0)
        }
        NSLayoutConstraint.activate([
            imageView.topAnchor.constraint(equalTo: contentView.topAnchor),
            imageView.leadingAnchor.constraint(equalTo: contentView.leadingAnchor),
            imageView.trailingAnchor.constraint(equalTo: contentView.trailingAnchor),
            imageView.bottomAnchor.constraint(equalTo: number.topAnchor, constant: -6),
            number.leadingAnchor.constraint(equalTo: contentView.leadingAnchor),
            number.trailingAnchor.constraint(equalTo: contentView.trailingAnchor),
            number.bottomAnchor.constraint(equalTo: contentView.bottomAnchor),
            number.heightAnchor.constraint(equalToConstant: 18),
            spinner.centerXAnchor.constraint(equalTo: imageView.centerXAnchor),
            spinner.centerYAnchor.constraint(equalTo: imageView.centerYAnchor),
            failedMark.centerXAnchor.constraint(equalTo: imageView.centerXAnchor),
            failedMark.centerYAnchor.constraint(equalTo: imageView.centerYAnchor)
        ])
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    override func prepareForReuse() {
        super.prepareForReuse()
        imageView.image = nil
        failedMark.isHidden = true
        pageId = nil
    }
}
