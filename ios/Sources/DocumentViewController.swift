import Photos
import UIKit
import UniformTypeIdentifiers

/// Màn hình tài liệu: lưới trang, Thêm / Sửa / Chia sẻ / Lưu PDF / Lưu ảnh, khổ giấy PDF.
final class DocumentViewController: UIViewController, UICollectionViewDataSource, UICollectionViewDelegateFlowLayout, UIDocumentPickerDelegate {
    let doc: Doc
    private var collection: UICollectionView!
    private let layout = UICollectionViewFlowLayout()
    private let infoLabel = Theme.label("", size: 13, color: Theme.text2)
    private let titleBtn = UIButton(type: .system)
    private let cache = NSCache<NSString, UIImage>()
    private(set) var rendering = false
    private var renderToken = 0

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
    private func ensureRendered() {
        let todo = doc.pages.filter { needsRender($0) }
        guard !todo.isEmpty else {
            rendering = false
            return
        }
        rendering = true
        renderToken += 1
        let token = renderToken
        let dir = DocStore.shared.dir(doc)
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            var rendered = [Page]()
            for p in todo {
                if let np = try? ImageOps.render(dir: dir, page: p) { rendered.append(np) }
            }
            let done = rendered
            DispatchQueue.main.async {
                guard let self = self, token == self.renderToken else { return }
                for np in done {
                    guard let i = self.doc.index(of: np.id) else { continue }
                    let cur = self.doc.pages[i]
                    if cur.src == np.src && cur.quad == np.quad && cur.filter == np.filter && cur.rot == np.rot {
                        self.doc.pages[i].warp = np.warp
                        self.doc.pages[i].out = np.out
                    }
                }
                DocStore.shared.save(self.doc)
                self.rendering = false
                self.reload()
            }
        }
    }

    private func ready() -> Bool {
        if rendering || doc.pages.contains(where: { needsRender($0) }) {
            toast("Đang xử lý trang, vui lòng đợi giây lát")
            if !rendering { ensureRendered() }
            return false
        }
        return true
    }

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
        let p = doc.pages[indexPath.item]
        cell.number.text = "\(indexPath.item + 1)"
        cell.pageId = p.id
        cell.imageView.image = nil
        cell.spinner.startAnimating()
        guard let o = p.out, !needsRender(p) else { return cell }
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
        openEditor(at: indexPath.item)
    }

    // MARK: thao tác

    func openEditor(at page: Int) {
        let ed = EditorViewController(doc: doc, context: .existing, startPage: page)
        ed.onDone = { [weak self] _ in
            guard let self = self else { return }
            if self.doc.pages.isEmpty {
                self.navigationController?.popToRootViewController(animated: true)
                return
            }
            self.navigationController?.popToViewController(self, animated: true)
            self.reload()
        }
        navigationController?.pushViewController(ed, animated: true)
    }

    @objc private func editPages() { openEditor(at: 0) }

    @objc func addPages() {
        let cam = CameraViewController(mode: .add(doc))
        cam.onFinished = { [weak self] _ in
            self?.dismiss(animated: true) {
                self?.reload()
                self?.ensureRendered()
            }
        }
        let nav = UINavigationController(rootViewController: cam)
        nav.modalPresentationStyle = .fullScreen
        present(nav, animated: true)
    }

    @objc private func rename() {
        prompt("Đổi tên tài liệu", initial: doc.title) { [weak self] v in
            guard let self = self else { return }
            self.doc.title = v
            self.doc.modified = Date()
            DocStore.shared.save(self.doc)
            self.reload()
        }
    }

    @objc private func moreTapped() {
        let a = UIAlertController(title: nil, message: nil, preferredStyle: .actionSheet)
        a.addAction(UIAlertAction(title: "Đổi tên", style: .default) { [weak self] _ in self?.rename() })
        a.addAction(UIAlertAction(title: "Khổ giấy PDF: \(DocStore.pdfSizeName(DocStore.shared.pdfSize))", style: .default) { [weak self] _ in
            self?.choosePdfSize()
        })
        a.addAction(UIAlertAction(title: "Xoá tài liệu", style: .destructive) { [weak self] _ in self?.deleteDoc() })
        a.addAction(UIAlertAction(title: "Hủy", style: .cancel))
        a.popoverPresentationController?.barButtonItem = navigationItem.rightBarButtonItem
        present(a, animated: true)
    }

    func choosePdfSize() {
        let names = ["A4 (210 × 297 mm)", "Letter (216 × 279 mm)", "Theo kích thước ảnh"]
        let values = [PdfWriter.sizeA4, PdfWriter.sizeLetter, PdfWriter.sizeFit]
        let cur = DocStore.shared.pdfSize
        let a = UIAlertController(title: "Khổ giấy PDF", message: nil, preferredStyle: .actionSheet)
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
        confirm("Xoá tài liệu?", message: "\"\(doc.title)\" sẽ bị xoá vĩnh viễn.", ok: "Xoá") { [weak self] in
            guard let self = self else { return }
            DocStore.shared.delete(self.doc)
            self.navigationController?.popToRootViewController(animated: true)
        }
    }

    @objc private func shareTapped() {
        guard ready() else { return }
        let a = UIAlertController(title: "Chia sẻ", message: nil, preferredStyle: .actionSheet)
        a.addAction(UIAlertAction(title: "Chia sẻ PDF (\(DocStore.pdfSizeName(DocStore.shared.pdfSize)))", style: .default) { [weak self] _ in
            self?.share(pdf: true)
        })
        a.addAction(UIAlertAction(title: "Chia sẻ ảnh JPG", style: .default) { [weak self] _ in
            self?.share(pdf: false)
        })
        a.addAction(UIAlertAction(title: "Hủy", style: .cancel))
        a.popoverPresentationController?.sourceView = view
        present(a, animated: true)
    }

    func share(pdf: Bool) {
        do {
            let items: [URL] = pdf ? [try ImageOps.exportPDF(doc: doc)] : try ImageOps.exportImages(doc: doc)
            let vc = UIActivityViewController(activityItems: items, applicationActivities: nil)
            vc.popoverPresentationController?.sourceView = view
            present(vc, animated: true)
        } catch {
            toast("Lỗi: \(error.localizedDescription)")
        }
    }

    @objc private func savePDF() {
        guard ready() else { return }
        do {
            let url = try ImageOps.exportPDF(doc: doc)
            let picker = UIDocumentPickerViewController(forExporting: [url], asCopy: true)
            picker.delegate = self
            present(picker, animated: true)
        } catch {
            toast("Lỗi: \(error.localizedDescription)")
        }
    }

    func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        toast("Đã lưu PDF")
    }

    @objc private func saveImages() {
        guard ready() else { return }
        let urls: [URL]
        do {
            urls = try ImageOps.exportImages(doc: doc)
        } catch {
            toast("Lỗi: \(error.localizedDescription)")
            return
        }
        PHPhotoLibrary.requestAuthorization(for: .addOnly) { status in
            guard status == .authorized || status == .limited else {
                DispatchQueue.main.async { self.toast("Cần quyền Ảnh để lưu ảnh") }
                return
            }
            PHPhotoLibrary.shared().performChanges({
                for u in urls {
                    PHAssetCreationRequest.forAsset().addResource(with: .photo, fileURL: u, options: nil)
                }
            }, completionHandler: { ok, err in
                DispatchQueue.main.async {
                    if ok {
                        self.toast("Đã lưu \(urls.count) ảnh vào Thư viện")
                    } else {
                        self.toast("Lỗi lưu ảnh: \(err?.localizedDescription ?? "")")
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
    var pageId: String?

    override init(frame: CGRect) {
        super.init(frame: frame)
        imageView.contentMode = .scaleAspectFit
        imageView.backgroundColor = UIColor(white: 1, alpha: 0.06)
        spinner.color = Theme.accent
        spinner.hidesWhenStopped = true
        number.textAlignment = .center
        [imageView, number, spinner].forEach {
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
            spinner.centerYAnchor.constraint(equalTo: imageView.centerYAnchor)
        ])
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    override func prepareForReuse() {
        super.prepareForReuse()
        imageView.image = nil
        pageId = nil
    }
}
