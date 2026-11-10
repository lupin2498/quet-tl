import PhotosUI
import UIKit

/// Màn hình chính: tìm kiếm, công cụ (Quét, Hàng loạt, Nhập ảnh), tài liệu gần đây, nút máy ảnh.
final class HomeViewController: UIViewController, UITableViewDataSource, UITableViewDelegate, UISearchBarDelegate, PHPickerViewControllerDelegate {
    private var all = [Doc]()
    private var shown = [Doc]()
    private let table = UITableView(frame: .zero, style: .plain)
    private let search = UISearchBar()
    private let countLabel = Theme.label("", size: 13, color: Theme.text2)
    private let empty = UIStackView()
    /// Báo khi tìm kiếm không ra kết quả (thay vì để danh sách trống trơn).
    private let noMatch = Theme.label("", size: 15, color: Theme.text2)
    private let busy = BusyOverlay()
    private let thumbCache = NSCache<NSString, UIImage>()
    /// Số tài liệu đang quét dở vừa được khôi phục lúc mở ứng dụng (báo một lần khi màn hình hiện ra).
    var recoveredCount = 0
    private var importing = false

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = Theme.bg
        navigationItem.backButtonDisplayMode = .minimal
        buildUI()
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        navigationController?.setNavigationBarHidden(true, animated: animated)
        reload()
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        navigationController?.setNavigationBarHidden(false, animated: animated)
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        if recoveredCount > 0 {
            toast("Đã khôi phục \(recoveredCount) tài liệu đang quét dở")
            recoveredCount = 0
        }
    }

    /// Màn hình chính đang hiển thị trên cùng và không có gì che (để không mở trùng màn hình khi chạm đúp).
    private var isFrontmost: Bool {
        if importing { return false }
        // hộp thoại / bộ chọn ảnh đang đóng dở không tính là đang che
        if let p = presentedViewController, !p.isBeingDismissed { return false }
        return navigationController?.topViewController === self
    }

    /// Đang ở giữa việc nhập ảnh (đọc ảnh / tìm mép ở luồng nền).
    var isImporting: Bool { importing }

    private func buildUI() {
        search.searchBarStyle = .minimal
        search.placeholder = "Tìm kiếm"
        search.delegate = self
        search.searchTextField.textColor = .white
        search.searchTextField.backgroundColor = Theme.card
        search.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(search)

        let tools = UIStackView(arrangedSubviews: [
            tool("doc.viewfinder", UIColor(rgb: 0x26C6A6), "Quét", #selector(scanTapped)),
            tool("square.stack.3d.up", UIColor(rgb: 0x4A90E2), "Hàng loạt", #selector(batchTapped)),
            tool("photo.on.rectangle", UIColor(rgb: 0x7C6CF2), "Nhập ảnh", #selector(importTapped))
        ])
        tools.axis = .horizontal
        tools.distribution = .fillEqually
        tools.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(tools)

        let divider = UIView()
        divider.backgroundColor = UIColor(white: 1, alpha: 0.12)
        divider.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(divider)

        let header = Theme.label("Gần đây", size: 19, weight: .semibold)
        header.translatesAutoresizingMaskIntoConstraints = false
        countLabel.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(header)
        view.addSubview(countLabel)

        table.backgroundColor = .clear
        table.separatorStyle = .none
        table.dataSource = self
        table.delegate = self
        table.rowHeight = 92
        table.register(DocCell.self, forCellReuseIdentifier: "doc")
        table.contentInset = UIEdgeInsets(top: 0, left: 0, bottom: 96, right: 0)
        table.translatesAutoresizingMaskIntoConstraints = false
        table.keyboardDismissMode = .onDrag
        view.addSubview(table)

        let icon = UIImageView(image: Theme.symbol("doc.text", size: 52))
        icon.tintColor = UIColor(rgb: 0x5A595E)
        let t1 = Theme.label("Chưa có tài liệu nào", size: 17, weight: .semibold)
        let t2 = Theme.label("Nhấn nút máy ảnh để quét giấy tờ. Ứng dụng tự tìm mép trang, nắn thẳng và làm rõ chữ.", size: 14, color: Theme.text2)
        t2.numberOfLines = 0
        t2.textAlignment = .center
        [icon, t1, t2].forEach { empty.addArrangedSubview($0) }
        empty.axis = .vertical
        empty.alignment = .center
        empty.spacing = 10
        empty.translatesAutoresizingMaskIntoConstraints = false
        empty.isHidden = true
        view.addSubview(empty)

        noMatch.numberOfLines = 0
        noMatch.textAlignment = .center
        noMatch.isHidden = true
        noMatch.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(noMatch)

        let fab = UIButton(type: .custom)
        fab.setImage(Theme.symbol("camera.fill", size: 22), for: .normal)
        fab.tintColor = .white
        fab.backgroundColor = Theme.accent
        fab.layer.cornerRadius = 31
        fab.layer.shadowColor = UIColor.black.cgColor
        fab.layer.shadowOpacity = 0.35
        fab.layer.shadowRadius = 6
        fab.layer.shadowOffset = CGSize(width: 0, height: 3)
        fab.accessibilityLabel = "Quét tài liệu"
        fab.addTarget(self, action: #selector(scanTapped), for: .touchUpInside)
        fab.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(fab)

        let g = view.safeAreaLayoutGuide
        NSLayoutConstraint.activate([
            search.topAnchor.constraint(equalTo: g.topAnchor, constant: 6),
            search.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 8),
            search.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -8),

            tools.topAnchor.constraint(equalTo: search.bottomAnchor, constant: 6),
            tools.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 8),
            tools.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -8),
            tools.heightAnchor.constraint(equalToConstant: 84),

            divider.topAnchor.constraint(equalTo: tools.bottomAnchor, constant: 8),
            divider.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            divider.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            divider.heightAnchor.constraint(equalToConstant: 1),

            header.topAnchor.constraint(equalTo: divider.bottomAnchor, constant: 12),
            header.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 16),
            countLabel.centerYAnchor.constraint(equalTo: header.centerYAnchor),
            countLabel.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -16),

            table.topAnchor.constraint(equalTo: header.bottomAnchor, constant: 8),
            table.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            table.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            table.bottomAnchor.constraint(equalTo: view.bottomAnchor),

            empty.centerXAnchor.constraint(equalTo: table.centerXAnchor),
            empty.centerYAnchor.constraint(equalTo: table.centerYAnchor, constant: -40),
            empty.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 40),
            empty.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -40),

            noMatch.topAnchor.constraint(equalTo: table.topAnchor, constant: 48),
            noMatch.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 32),
            noMatch.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -32),

            fab.widthAnchor.constraint(equalToConstant: 62),
            fab.heightAnchor.constraint(equalToConstant: 62),
            fab.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -22),
            fab.bottomAnchor.constraint(equalTo: g.bottomAnchor, constant: -18)
        ])
    }

    private func tool(_ symbol: String, _ color: UIColor, _ title: String, _ action: Selector) -> UIView {
        var cfg = UIButton.Configuration.plain()
        cfg.image = Theme.symbol(symbol, size: 24)
        cfg.title = title
        cfg.imagePlacement = .top
        cfg.imagePadding = 8
        cfg.baseForegroundColor = color
        cfg.titleTextAttributesTransformer = UIConfigurationTextAttributesTransformer { incoming in
            var out = incoming
            out.font = UIFont.systemFont(ofSize: 12.5)
            out.foregroundColor = .white
            return out
        }
        let b = UIButton(configuration: cfg)
        b.accessibilityLabel = title
        b.addTarget(self, action: action, for: .touchUpInside)
        return b
    }

    func reload() {
        DispatchQueue.global(qos: .userInitiated).async {
            let docs = DocStore.shared.list()
            DispatchQueue.main.async {
                self.all = docs
                self.applyFilter()
            }
        }
    }

    /// Tìm không phân biệt hoa/thường và dấu tiếng Việt ("bien ban" ra "Biên bản").
    static func matches(title: String, query: String) -> Bool {
        let q = ImageOps.fold(query.trimmingCharacters(in: .whitespaces))
        return q.isEmpty || ImageOps.fold(title).contains(q)
    }

    private func applyFilter() {
        let q = search.text ?? ""
        shown = all.filter { HomeViewController.matches(title: $0.title, query: q) }
        table.reloadData()
        empty.isHidden = !all.isEmpty
        let none = !all.isEmpty && shown.isEmpty
        if none { noMatch.text = "Không tìm thấy kết quả cho “\(q.trimmingCharacters(in: .whitespaces))”" }
        noMatch.isHidden = !none
        countLabel.text = all.isEmpty ? "" : "\(all.count) tài liệu"
    }

    // MARK: thao tác

    @objc private func scanTapped() { openCamera(batch: nil) }

    @objc private func batchTapped() { openCamera(batch: true) }

    func openCamera(batch: Bool?) {
        guard isFrontmost else { return }
        let cam = CameraViewController(mode: .new(batch: batch))
        cam.onFinished = { [weak self] doc in
            guard let self = self else { return }
            self.dismiss(animated: true) {
                if let doc = doc { self.openDocument(doc) }
                self.reload()
            }
        }
        let nav = UINavigationController(rootViewController: cam)
        nav.modalPresentationStyle = .fullScreen
        present(nav, animated: true)
    }

    func openDocument(_ doc: Doc) {
        // chạm đúp một dòng / gọi lại hai lần: chỉ mở một màn hình tài liệu
        guard navigationController?.topViewController === self else { return }
        navigationController?.pushViewController(DocumentViewController(doc: doc), animated: true)
    }

    @objc private func importTapped() {
        guard isFrontmost else { return }
        var cfg = PHPickerConfiguration()
        cfg.filter = .images
        cfg.selectionLimit = 0
        let picker = PHPickerViewController(configuration: cfg)
        picker.delegate = self
        present(picker, animated: true)
    }

    func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
        // đợi bộ chọn ảnh đóng xong rồi mới xử lý: mở màn hình khác trong lúc nó đang đóng sẽ bị hệ thống bỏ qua
        picker.dismiss(animated: true) { [weak self] in
            self?.handlePicked(results)
        }
    }

    private func handlePicked(_ results: [PHPickerResult]) {
        guard !results.isEmpty, !importing else { return }
        importing = true
        if results.count == 1 {
            busy.show(in: view, text: "Đang nhập ảnh…")
            let slot = PickedImage()
            ImageLoader.forEach(results, { img in
                slot.image = img
            }, completion: { [weak self] _, _ in
                guard let self = self else { return }
                self.busy.hide()
                self.importing = false
                if let img = slot.image {
                    self.importImages([img])
                } else {
                    self.toast("Không đọc được ảnh")
                }
            })
            return
        }
        // nhiều ảnh: đọc, tìm mép và lưu lần lượt từng ảnh rồi mở trình chỉnh sửa
        busy.show(in: view, text: "Đang nhập \(results.count) ảnh…")
        let doc = DocStore.shared.create(draft: true)
        ImageLoader.forEach(results, { img in
            let q = img.cgImage.flatMap { ImageOps.detect($0) }
            _ = try? DocStore.shared.addPage(doc, image: img, quad: q, auto: true)
        }, completion: { [weak self] _, failed in
            guard let self = self else {
                DocStore.shared.delete(doc)
                return
            }
            self.busy.hide()
            self.importing = false
            if doc.pages.isEmpty {
                DocStore.shared.delete(doc)
                self.toast("Không đọc được ảnh")
                return
            }
            if failed > 0 { self.toast("\(failed) ảnh không đọc được") }
            self.openEditor(doc, isNew: true)
        })
    }

    private final class PickedImage {
        var image: UIImage?
    }

    /// Nhập ảnh: 1 ảnh -> màn hình cắt; nhiều ảnh -> tự tìm mép rồi mở trình chỉnh sửa.
    func importImages(_ images: [UIImage]) {
        guard !images.isEmpty else {
            toast("Không đọc được ảnh")
            return
        }
        guard isFrontmost else {
            toast("Hãy hoàn tất thao tác đang mở trước khi nhập ảnh")
            return
        }
        if images.count == 1 {
            let img = ImageOps.normalized(images[0], maxDim: ImageOps.maxSrc)
            let crop = CropViewController(image: img, quad: nil, auto: true, mode: .edit)
            crop.onDone = { [weak self] quad, auto in
                guard let self = self else { return }
                self.dismiss(animated: true) {
                    let doc = DocStore.shared.create(draft: true)
                    do {
                        try DocStore.shared.addPage(doc, image: img, quad: quad, auto: auto)
                        self.openEditor(doc, isNew: true)
                    } catch {
                        DocStore.shared.delete(doc)
                        self.toast("Lỗi nhập ảnh: \(error.localizedDescription)")
                    }
                }
            }
            crop.onCancel = { [weak self] in self?.dismiss(animated: true) }
            crop.modalPresentationStyle = .fullScreen
            present(crop, animated: true)
            return
        }
        importing = true
        busy.show(in: view, text: "Đang tìm mép trang…")
        DispatchQueue.global(qos: .userInitiated).async {
            let doc = DocStore.shared.create(draft: true)
            var failed = 0
            for raw in images {
                autoreleasepool {
                    let img = ImageOps.normalized(raw, maxDim: ImageOps.maxSrc)
                    let q = img.cgImage.flatMap { ImageOps.detect($0) }
                    if (try? DocStore.shared.addPage(doc, image: img, quad: q, auto: true)) == nil { failed += 1 }
                }
            }
            let nFailed = failed
            DispatchQueue.main.async {
                self.busy.hide()
                self.importing = false
                if doc.pages.isEmpty {
                    DocStore.shared.delete(doc)
                    self.toast("Không đọc được ảnh")
                } else {
                    if nFailed > 0 { self.toast("\(nFailed) ảnh không đọc được") }
                    self.openEditor(doc, isNew: true)
                }
            }
        }
    }

    func openEditor(_ doc: Doc, isNew: Bool) {
        guard navigationController?.topViewController === self else {
            // màn hình chính không còn ở trên cùng (hiếm): giữ tài liệu vừa nhập như một tài liệu thường thay vì để mất
            if isNew {
                doc.draft = false
                DocStore.shared.save(doc)
            }
            return
        }
        let ed = EditorViewController(doc: doc, context: isNew ? .newImport : .existing)
        ed.onDone = { [weak self] saved in
            guard let self = self else { return }
            self.navigationController?.popToViewController(self, animated: false)
            if saved { self.openDocument(doc) }
            self.reload()
        }
        navigationController?.pushViewController(ed, animated: true)
    }

    // MARK: bảng

    func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int { shown.count }

    func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = tableView.dequeueReusableCell(withIdentifier: "doc", for: indexPath) as! DocCell
        let d = shown[indexPath.row]
        cell.titleLabel.text = d.title
        cell.subLabel.text = "\(Theme.dateString(d.modified))   ▢ \(d.pages.count)"
        cell.thumb.image = nil
        cell.docId = d.id
        cell.onMore = { [weak self] in self?.docMenu(d) }
        if let first = d.pages.first, let out = first.out {
            let key = "\(d.id)/\(out)" as NSString
            if let img = thumbCache.object(forKey: key) {
                cell.thumb.image = img
            } else {
                let url = DocStore.shared.url(d, out)
                DispatchQueue.global(qos: .userInitiated).async {
                    let img = ImageOps.thumbnail(url, maxPixel: 220)
                    DispatchQueue.main.async {
                        guard let img = img else { return }
                        self.thumbCache.setObject(img, forKey: key)
                        if cell.docId == d.id { cell.thumb.image = img }
                    }
                }
            }
        }
        return cell
    }

    func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        tableView.deselectRow(at: indexPath, animated: true)
        openDocument(shown[indexPath.row])
    }

    func tableView(_ tableView: UITableView, trailingSwipeActionsConfigurationForRowAt indexPath: IndexPath) -> UISwipeActionsConfiguration? {
        let d = shown[indexPath.row]
        let del = UIContextualAction(style: .destructive, title: "Xoá") { [weak self] _, _, done in
            guard let self = self, self.isFrontmost else {
                done(false)
                return
            }
            self.confirm("Xoá tài liệu?", message: "\"\(d.title)\" sẽ bị xoá vĩnh viễn.", ok: "Xoá") { [weak self] in
                DocStore.shared.delete(d)
                self?.reload()
            }
            done(true)
        }
        return UISwipeActionsConfiguration(actions: [del])
    }

    private func docMenu(_ d: Doc) {
        guard isFrontmost else { return }
        let a = UIAlertController(title: d.title, message: nil, preferredStyle: .actionSheet)
        a.addAction(UIAlertAction(title: "Đổi tên", style: .default) { [weak self] _ in
            self?.prompt("Đổi tên tài liệu", initial: d.title) { v in
                guard v != d.title else { return }
                d.title = v
                d.modified = Date()
                DocStore.shared.save(d)
                self?.reload()
            }
        })
        a.addAction(UIAlertAction(title: "Chia sẻ PDF", style: .default) { [weak self] _ in
            self?.share(doc: d, pdf: true)
        })
        a.addAction(UIAlertAction(title: "Chia sẻ ảnh", style: .default) { [weak self] _ in
            self?.share(doc: d, pdf: false)
        })
        a.addAction(UIAlertAction(title: "Xoá", style: .destructive) { [weak self] _ in
            self?.confirm("Xoá tài liệu?", message: "\"\(d.title)\" sẽ bị xoá vĩnh viễn.", ok: "Xoá") {
                DocStore.shared.delete(d)
                self?.reload()
            }
        })
        a.addAction(UIAlertAction(title: "Hủy", style: .cancel))
        a.popoverPresentationController?.sourceView = view
        present(a, animated: true)
    }

    private func share(doc: Doc, pdf: Bool) {
        for p in doc.pages where !DocStore.shared.outReady(doc, p) {
            toast("Hãy mở tài liệu để hoàn tất xử lý trang")
            return
        }
        let job: ImageOps.ExportJob
        do {
            job = try ImageOps.exportJob(doc: doc)
        } catch {
            toast("Lỗi: \(error.localizedDescription)")
            return
        }
        // tạo file ở luồng nền (tài liệu nhiều trang mất vài giây), giao diện không bị đứng
        busy.show(in: view, text: pdf ? "Đang tạo PDF…" : "Đang chuẩn bị ảnh…")
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            let result: Result<[URL], Error> = Result {
                try (pdf ? [ImageOps.exportPDF(job: job)] : ImageOps.exportImages(job: job))
            }
            DispatchQueue.main.async {
                guard let self = self else { return }
                self.busy.hide()
                switch result {
                case .success(let items):
                    guard self.isFrontmost else { return }
                    let vc = UIActivityViewController(activityItems: items, applicationActivities: nil)
                    vc.popoverPresentationController?.sourceView = self.view
                    self.present(vc, animated: true)
                case .failure(let error):
                    self.toast("Lỗi: \(error.localizedDescription)")
                }
            }
        }
    }

    func searchBar(_ searchBar: UISearchBar, textDidChange searchText: String) { applyFilter() }

    func searchBarSearchButtonClicked(_ searchBar: UISearchBar) { searchBar.resignFirstResponder() }
}

final class DocCell: UITableViewCell {
    let thumb = UIImageView()
    let titleLabel = Theme.label("", size: 16, weight: .medium)
    let subLabel = Theme.label("", size: 13, color: Theme.text2)
    private let more = UIButton(type: .system)
    var onMore: (() -> Void)?
    var docId: String?

    override init(style: UITableViewCell.CellStyle, reuseIdentifier: String?) {
        super.init(style: style, reuseIdentifier: reuseIdentifier)
        backgroundColor = .clear
        let sel = UIView()
        sel.backgroundColor = UIColor(white: 1, alpha: 0.08)
        selectedBackgroundView = sel
        thumb.contentMode = .scaleAspectFill
        thumb.clipsToBounds = true
        thumb.backgroundColor = .white
        more.setImage(Theme.symbol("ellipsis", size: 18), for: .normal)
        more.tintColor = Theme.text2
        more.accessibilityLabel = "Tùy chọn tài liệu"
        more.addTarget(self, action: #selector(moreTapped), for: .touchUpInside)
        titleLabel.lineBreakMode = .byTruncatingTail
        let texts = UIStackView(arrangedSubviews: [titleLabel, subLabel])
        texts.axis = .vertical
        texts.spacing = 6
        [thumb, texts, more].forEach {
            $0.translatesAutoresizingMaskIntoConstraints = false
            contentView.addSubview($0)
        }
        NSLayoutConstraint.activate([
            thumb.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 16),
            thumb.centerYAnchor.constraint(equalTo: contentView.centerYAnchor),
            thumb.widthAnchor.constraint(equalToConstant: 58),
            thumb.heightAnchor.constraint(equalToConstant: 74),
            texts.leadingAnchor.constraint(equalTo: thumb.trailingAnchor, constant: 14),
            texts.centerYAnchor.constraint(equalTo: contentView.centerYAnchor),
            texts.trailingAnchor.constraint(lessThanOrEqualTo: more.leadingAnchor, constant: -4),
            more.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -6),
            more.centerYAnchor.constraint(equalTo: contentView.centerYAnchor),
            more.widthAnchor.constraint(equalToConstant: 44),
            more.heightAnchor.constraint(equalToConstant: 44)
        ])
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    @objc private func moreTapped() { onMore?() }
}

/// Đọc ảnh từ PHPicker lần lượt từng ảnh (không giữ nhiều ảnh 12 MP trong bộ nhớ cùng lúc).
enum ImageLoader {
    private final class Slot {
        var image: UIImage?
    }

    /// `body` chạy ở luồng nền cho từng ảnh (đã chuẩn hoá hướng, cạnh dài ≤ maxSrc); `completion` chạy ở luồng chính.
    static func forEach(_ results: [PHPickerResult], _ body: @escaping (UIImage) -> Void,
                        completion: @escaping (_ ok: Int, _ failed: Int) -> Void) {
        DispatchQueue(label: "vn.quettailieu.import", qos: .userInitiated).async {
            var ok = 0, failed = 0
            for r in results {
                let provider = r.itemProvider
                let slot = Slot()
                if provider.canLoadObject(ofClass: UIImage.self) {
                    let sem = DispatchSemaphore(value: 0)
                    provider.loadObject(ofClass: UIImage.self) { obj, _ in
                        slot.image = obj as? UIImage
                        sem.signal()
                    }
                    _ = sem.wait(timeout: .now() + 60)
                }
                if let img = slot.image {
                    autoreleasepool {
                        body(ImageOps.normalized(img, maxDim: ImageOps.maxSrc))
                    }
                    slot.image = nil
                    ok += 1
                } else {
                    failed += 1
                }
            }
            let nOK = ok, nFailed = failed
            DispatchQueue.main.async { completion(nOK, nFailed) }
        }
    }
}
