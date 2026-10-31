import UIKit

/// Trình chỉnh sửa trang: lật trang, bộ lọc, xoay, cắt lại, chụp lại, so sánh, xoá, đổi tên.
/// Ảnh kết quả được sinh ở luồng nền theo thứ tự; mỗi trang có "thế hệ" để bỏ kết quả cũ.
final class EditorViewController: UIViewController, UICollectionViewDataSource, UICollectionViewDelegateFlowLayout {
    enum Context { case camera, newImport, existing }

    /// saved = true: đã lưu tài liệu; false: quay lại (màn hình chụp) hoặc đã bỏ.
    var onDone: ((Bool) -> Void)?

    let doc: Doc
    private let context: Context
    private(set) var index: Int
    private var gen = [String: Int]()
    private let genLock = NSLock()
    private var genShared = [String: Int]()
    private var processing = Set<String>()
    private(set) var pending = 0
    private var afterPending: (() -> Void)?
    private var finishRetried = false
    private var didInitialScroll = false
    private var applyAll = false
    private let renderQueue = DispatchQueue(label: "vn.quettailieu.render", qos: .userInitiated)
    private let cache = NSCache<NSString, UIImage>()

    private var collection: UICollectionView!
    private let layout = UICollectionViewFlowLayout()
    private let indicator = Theme.label("", size: 14, weight: .medium)
    private let prevBtn = Theme.iconButton("chevron.left", size: 16, label: "Trang trước")
    private let nextBtn = Theme.iconButton("chevron.right", size: 16, label: "Trang sau")
    private let compareBtn = UIButton(type: .custom)
    private let compareView = UIImageView()
    private let titleBtn = UIButton(type: .system)
    private let filterPanel = UIView()
    private var filterButtons = [UIButton]()
    private var filterThumbs = [UIImageView]()
    private var filterLabels = [UILabel]()
    private let applyAllBtn = UIButton(type: .custom)
    private let busy = BusyOverlay()

    init(doc: Doc, context: Context, startPage: Int = 0) {
        self.doc = doc
        self.context = context
        self.index = max(0, min(startPage, doc.pages.count - 1))
        super.init(nibName: nil, bundle: nil)
        cache.countLimit = 24
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = Theme.bg
        buildUI()
        for p in doc.pages where !upToDate(p) { schedule(p) }
        updateIndicator()
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        navigationController?.setNavigationBarHidden(false, animated: animated)
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        navigationController?.interactivePopGestureRecognizer?.isEnabled = false
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        navigationController?.interactivePopGestureRecognizer?.isEnabled = true
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        let size = collection.bounds.size
        if size.width > 0 && size.height > 0 && layout.itemSize != size {
            layout.itemSize = size
            layout.invalidateLayout()
            collection.layoutIfNeeded()
            didInitialScroll = false
        }
        if !didInitialScroll && size.width > 0 && !doc.pages.isEmpty {
            didInitialScroll = true
            collection.setContentOffset(CGPoint(x: CGFloat(index) * size.width, y: 0), animated: false)
        }
    }

    // MARK: giao diện

    private func buildUI() {
        navigationItem.hidesBackButton = true
        let back = UIBarButtonItem(image: Theme.symbol("chevron.left", size: 18, weight: .semibold), style: .plain, target: self, action: #selector(backTapped))
        back.accessibilityLabel = "Quay lại"
        navigationItem.leftBarButtonItem = back
        let del = UIBarButtonItem(image: Theme.symbol("trash", size: 17), style: .plain, target: self, action: #selector(deleteCurrent))
        del.accessibilityLabel = "Xoá trang"
        navigationItem.rightBarButtonItem = del
        titleBtn.setTitleColor(.white, for: .normal)
        titleBtn.titleLabel?.font = UIFont.systemFont(ofSize: 16, weight: .semibold)
        titleBtn.titleLabel?.lineBreakMode = .byTruncatingTail
        titleBtn.accessibilityHint = "Đổi tên tài liệu"
        titleBtn.addTarget(self, action: #selector(rename), for: .touchUpInside)
        titleBtn.setTitle(doc.title, for: .normal)
        titleBtn.frame = CGRect(x: 0, y: 0, width: 220, height: 36)
        navigationItem.titleView = titleBtn

        layout.scrollDirection = .horizontal
        layout.minimumLineSpacing = 0
        layout.minimumInteritemSpacing = 0
        collection = UICollectionView(frame: .zero, collectionViewLayout: layout)
        collection.backgroundColor = .clear
        collection.isPagingEnabled = true
        collection.showsHorizontalScrollIndicator = false
        collection.dataSource = self
        collection.delegate = self
        collection.register(PageCell.self, forCellWithReuseIdentifier: "page")
        collection.translatesAutoresizingMaskIntoConstraints = false
        collection.accessibilityIdentifier = "pager"
        view.addSubview(collection)

        compareView.contentMode = .scaleAspectFit
        compareView.isHidden = true
        compareView.backgroundColor = Theme.bg
        compareView.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(compareView)

        var ccfg = UIButton.Configuration.filled()
        ccfg.title = "So sánh"
        ccfg.baseBackgroundColor = UIColor(white: 0, alpha: 0.55)
        ccfg.baseForegroundColor = .white
        ccfg.cornerStyle = .capsule
        ccfg.contentInsets = NSDirectionalEdgeInsets(top: 6, leading: 14, bottom: 6, trailing: 14)
        ccfg.titleTextAttributesTransformer = UIConfigurationTextAttributesTransformer { incoming in
            var out = incoming
            out.font = UIFont.systemFont(ofSize: 13, weight: .medium)
            return out
        }
        compareBtn.configuration = ccfg
        compareBtn.accessibilityHint = "Giữ để xem ảnh trước khi lọc"
        compareBtn.addTarget(self, action: #selector(compareDown), for: .touchDown)
        compareBtn.addTarget(self, action: #selector(compareUp), for: [.touchUpInside, .touchUpOutside, .touchCancel])
        compareBtn.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(compareBtn)

        prevBtn.addTarget(self, action: #selector(prevPage), for: .touchUpInside)
        nextBtn.addTarget(self, action: #selector(nextPage), for: .touchUpInside)
        let nav = UIStackView(arrangedSubviews: [prevBtn, indicator, nextBtn])
        nav.axis = .horizontal
        nav.spacing = 14
        nav.alignment = .center
        nav.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(nav)
        [prevBtn, nextBtn].forEach {
            $0.widthAnchor.constraint(equalToConstant: 40).isActive = true
            $0.heightAnchor.constraint(equalToConstant: 36).isActive = true
        }

        buildFilterPanel()

        let bar = UIView()
        bar.backgroundColor = Theme.bgDark
        bar.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(bar)
        let retake = Theme.toolButton("camera", "Chụp lại")
        retake.addTarget(self, action: #selector(retakeTapped), for: .touchUpInside)
        let rot = Theme.toolButton("rotate.left", "Bên trái")
        rot.addTarget(self, action: #selector(rotateLeft), for: .touchUpInside)
        let filt = Theme.toolButton("camera.filters", "Lọc")
        filt.addTarget(self, action: #selector(toggleFilterPanel), for: .touchUpInside)
        let cropB = Theme.toolButton("crop", "Cắt")
        cropB.addTarget(self, action: #selector(openCrop), for: .touchUpInside)
        let tools = UIStackView(arrangedSubviews: [retake, rot, filt, cropB])
        tools.axis = .horizontal
        tools.distribution = .fillEqually
        tools.translatesAutoresizingMaskIntoConstraints = false
        bar.addSubview(tools)
        let ok = UIButton(type: .custom)
        ok.setImage(Theme.symbol("checkmark", size: 22, weight: .bold), for: .normal)
        ok.tintColor = .white
        ok.backgroundColor = Theme.accent
        ok.layer.cornerRadius = 26
        ok.accessibilityLabel = "Xong"
        ok.accessibilityIdentifier = "editorDone"
        ok.addTarget(self, action: #selector(doneTapped), for: .touchUpInside)
        ok.translatesAutoresizingMaskIntoConstraints = false
        bar.addSubview(ok)

        let g = view.safeAreaLayoutGuide
        NSLayoutConstraint.activate([
            collection.topAnchor.constraint(equalTo: g.topAnchor, constant: 6),
            collection.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            collection.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            collection.bottomAnchor.constraint(equalTo: nav.topAnchor, constant: -4),

            compareView.topAnchor.constraint(equalTo: collection.topAnchor),
            compareView.leadingAnchor.constraint(equalTo: collection.leadingAnchor),
            compareView.trailingAnchor.constraint(equalTo: collection.trailingAnchor),
            compareView.bottomAnchor.constraint(equalTo: collection.bottomAnchor),

            compareBtn.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -14),
            compareBtn.bottomAnchor.constraint(equalTo: collection.bottomAnchor, constant: -10),

            nav.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            nav.heightAnchor.constraint(equalToConstant: 36),
            nav.bottomAnchor.constraint(equalTo: filterPanel.topAnchor, constant: -4),

            filterPanel.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            filterPanel.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            filterPanel.bottomAnchor.constraint(equalTo: bar.topAnchor),

            bar.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            bar.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            bar.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            bar.topAnchor.constraint(equalTo: g.bottomAnchor, constant: -66),

            tools.leadingAnchor.constraint(equalTo: bar.leadingAnchor, constant: 4),
            tools.topAnchor.constraint(equalTo: bar.topAnchor, constant: 4),
            tools.heightAnchor.constraint(equalToConstant: 58),
            tools.trailingAnchor.constraint(equalTo: ok.leadingAnchor, constant: -8),
            ok.trailingAnchor.constraint(equalTo: bar.trailingAnchor, constant: -16),
            ok.centerYAnchor.constraint(equalTo: tools.centerYAnchor),
            ok.widthAnchor.constraint(equalToConstant: 52),
            ok.heightAnchor.constraint(equalToConstant: 52)
        ])
    }

    private var filterPanelHeight: NSLayoutConstraint!

    private func buildFilterPanel() {
        filterPanel.backgroundColor = Theme.bgDark
        filterPanel.clipsToBounds = true
        filterPanel.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(filterPanel)
        filterPanelHeight = filterPanel.heightAnchor.constraint(equalToConstant: 0)
        filterPanelHeight.isActive = true

        let scroll = UIScrollView()
        scroll.showsHorizontalScrollIndicator = false
        scroll.translatesAutoresizingMaskIntoConstraints = false
        filterPanel.addSubview(scroll)
        let row = UIStackView()
        row.axis = .horizontal
        row.spacing = 10
        row.translatesAutoresizingMaskIntoConstraints = false
        scroll.addSubview(row)
        for (k, mode) in Enhancer.modes.enumerated() {
            let b = UIButton(type: .custom)
            b.tag = k
            b.accessibilityLabel = Enhancer.name(mode)
            b.addTarget(self, action: #selector(filterTapped(_:)), for: .touchUpInside)
            let iv = UIImageView()
            iv.contentMode = .scaleAspectFit
            iv.backgroundColor = UIColor(rgb: 0x555458)
            iv.layer.borderWidth = 2
            iv.layer.borderColor = UIColor(rgb: 0x555458).cgColor
            iv.isUserInteractionEnabled = false
            let l = Theme.label(Enhancer.name(mode), size: 12)
            l.textAlignment = .center
            [iv, l].forEach {
                $0.translatesAutoresizingMaskIntoConstraints = false
                b.addSubview($0)
            }
            NSLayoutConstraint.activate([
                b.widthAnchor.constraint(equalToConstant: 68),
                iv.topAnchor.constraint(equalTo: b.topAnchor),
                iv.centerXAnchor.constraint(equalTo: b.centerXAnchor),
                iv.widthAnchor.constraint(equalToConstant: 60),
                iv.heightAnchor.constraint(equalToConstant: 76),
                l.topAnchor.constraint(equalTo: iv.bottomAnchor, constant: 4),
                l.leadingAnchor.constraint(equalTo: b.leadingAnchor),
                l.trailingAnchor.constraint(equalTo: b.trailingAnchor),
                l.bottomAnchor.constraint(equalTo: b.bottomAnchor)
            ])
            row.addArrangedSubview(b)
            filterButtons.append(b)
            filterThumbs.append(iv)
            filterLabels.append(l)
        }

        var acfg = UIButton.Configuration.plain()
        acfg.title = "Áp dụng cho tất cả trang"
        acfg.imagePadding = 8
        acfg.baseForegroundColor = .white
        acfg.titleTextAttributesTransformer = UIConfigurationTextAttributesTransformer { incoming in
            var out = incoming
            out.font = UIFont.systemFont(ofSize: 14)
            return out
        }
        applyAllBtn.configuration = acfg
        applyAllBtn.addTarget(self, action: #selector(toggleApplyAll), for: .touchUpInside)
        applyAllBtn.translatesAutoresizingMaskIntoConstraints = false
        filterPanel.addSubview(applyAllBtn)
        let close = Theme.iconButton("chevron.down", size: 16, label: "Đóng bộ lọc")
        close.addTarget(self, action: #selector(toggleFilterPanel), for: .touchUpInside)
        close.translatesAutoresizingMaskIntoConstraints = false
        filterPanel.addSubview(close)

        NSLayoutConstraint.activate([
            scroll.topAnchor.constraint(equalTo: filterPanel.topAnchor, constant: 10),
            scroll.leadingAnchor.constraint(equalTo: filterPanel.leadingAnchor),
            scroll.trailingAnchor.constraint(equalTo: filterPanel.trailingAnchor),
            scroll.heightAnchor.constraint(equalToConstant: 100),
            row.topAnchor.constraint(equalTo: scroll.contentLayoutGuide.topAnchor),
            row.bottomAnchor.constraint(equalTo: scroll.contentLayoutGuide.bottomAnchor),
            row.leadingAnchor.constraint(equalTo: scroll.contentLayoutGuide.leadingAnchor, constant: 12),
            row.trailingAnchor.constraint(equalTo: scroll.contentLayoutGuide.trailingAnchor, constant: -12),
            row.heightAnchor.constraint(equalTo: scroll.frameLayoutGuide.heightAnchor),
            applyAllBtn.topAnchor.constraint(equalTo: scroll.bottomAnchor, constant: 4),
            applyAllBtn.leadingAnchor.constraint(equalTo: filterPanel.leadingAnchor, constant: 8),
            applyAllBtn.heightAnchor.constraint(equalToConstant: 36),
            close.centerYAnchor.constraint(equalTo: applyAllBtn.centerYAnchor),
            close.trailingAnchor.constraint(equalTo: filterPanel.trailingAnchor, constant: -12),
            close.widthAnchor.constraint(equalToConstant: 44),
            close.heightAnchor.constraint(equalToConstant: 36)
        ])
        updateApplyAll()
    }

    var filterPanelVisible: Bool { filterPanelHeight.constant > 0 }

    private func updateApplyAll() {
        let name = applyAll ? "checkmark.square.fill" : "square"
        applyAllBtn.configuration?.image = Theme.symbol(name, size: 17)?.withTintColor(applyAll ? Theme.accent : Theme.text2, renderingMode: .alwaysOriginal)
        applyAllBtn.accessibilityValue = applyAll ? "Bật" : "Tắt"
    }

    @objc private func toggleApplyAll() {
        applyAll.toggle()
        updateApplyAll()
    }

    // MARK: dữ liệu trang

    var current: Page? {
        guard index >= 0 && index < doc.pages.count else { return nil }
        return doc.pages[index]
    }

    private func upToDate(_ p: Page) -> Bool {
        return p.out == ImageOps.outName(p) && DocStore.shared.outReady(doc, p)
    }

    private func updateIndicator() {
        let n = doc.pages.count
        indicator.text = n == 0 ? "" : "\(index + 1)/\(n)"
        prevBtn.isEnabled = index > 0
        nextBtn.isEnabled = index < n - 1
        prevBtn.alpha = prevBtn.isEnabled ? 1 : 0.35
        nextBtn.alpha = nextBtn.isEnabled ? 1 : 0.35
    }

    private func setShared(_ id: String, _ g: Int) {
        genLock.lock()
        genShared[id] = g
        genLock.unlock()
    }

    private func sharedGen(_ id: String) -> Int {
        genLock.lock()
        defer { genLock.unlock() }
        return genShared[id] ?? -1
    }

    /// Xếp hàng sinh ảnh kết quả cho trang.
    private func schedule(_ p: Page) {
        let g = (gen[p.id] ?? 0) + 1
        gen[p.id] = g
        setShared(p.id, g)
        processing.insert(p.id)
        pending += 1
        reloadCell(p.id)
        let dir = DocStore.shared.dir(doc)
        renderQueue.async { [weak self] in
            guard let self = self else { return }
            let result: Result<Page, Error>?
            if self.sharedGen(p.id) == g {
                do {
                    result = .success(try ImageOps.render(dir: dir, page: p))
                } catch {
                    result = .failure(error)
                }
            } else {
                result = nil
            }
            DispatchQueue.main.async { self.renderDone(p.id, g, result) }
        }
    }

    private func renderDone(_ pid: String, _ g: Int, _ result: Result<Page, Error>?) {
        pending -= 1
        if gen[pid] == g {
            processing.remove(pid)
            if let result = result {
                switch result {
                case .success(let np):
                    if let i = doc.index(of: pid) {
                        var cur = doc.pages[i]
                        if cur.src == np.src && cur.quad == np.quad && cur.filter == np.filter && cur.rot == np.rot {
                            cur.warp = np.warp
                            cur.out = np.out
                            doc.pages[i] = cur
                            DocStore.shared.save(doc)
                        }
                    }
                case .failure(let e):
                    toast("Lỗi xử lý trang: \(e.localizedDescription)")
                }
            }
            reloadCell(pid)
        }
        if pending <= 0 {
            pending = 0
            busy.hide()
            let a = afterPending
            afterPending = nil
            a?()
        }
    }

    private func reloadCell(_ pid: String) {
        guard let i = doc.index(of: pid), collection != nil else { return }
        let ip = IndexPath(item: i, section: 0)
        if let cell = collection.cellForItem(at: ip) as? PageCell {
            configure(cell, doc.pages[i])
        }
    }

    private func showWait() {
        busy.show(in: navigationController?.view ?? view, text: "Đang xử lý ảnh…")
    }

    // MARK: bảng trang

    func collectionView(_ collectionView: UICollectionView, numberOfItemsInSection section: Int) -> Int {
        return doc.pages.count
    }

    func collectionView(_ collectionView: UICollectionView, cellForItemAt indexPath: IndexPath) -> UICollectionViewCell {
        let cell = collectionView.dequeueReusableCell(withReuseIdentifier: "page", for: indexPath) as! PageCell
        configure(cell, doc.pages[indexPath.item])
        return cell
    }

    func collectionView(_ collectionView: UICollectionView, layout collectionViewLayout: UICollectionViewLayout, sizeForItemAt indexPath: IndexPath) -> CGSize {
        return collectionView.bounds.size
    }

    private func configure(_ cell: PageCell, _ p: Page) {
        let working = processing.contains(p.id) || !upToDate(p)
        let samePage = cell.pageId == p.id && cell.shownSrc == p.src && cell.imageView.image != nil
        cell.pageId = p.id
        cell.setScanning(working)
        let dir = DocStore.shared.dir(doc)
        if !working, let o = p.out {
            let key = "out_\(p.id)_\(o)" as NSString
            if let img = cache.object(forKey: key) {
                cell.show(img, src: p.src)
                return
            }
            let url = dir.appendingPathComponent(o)
            DispatchQueue.global(qos: .userInitiated).async { [weak self] in
                let img = ImageOps.thumbnail(url, maxPixel: 1800)
                DispatchQueue.main.async {
                    guard let self = self, let img = img else { return }
                    self.cache.setObject(img, forKey: key)
                    if cell.pageId == p.id { cell.show(img, src: p.src) }
                }
            }
            return
        }
        if samePage { return }
        cell.imageView.image = nil
        let key = "pv_\(ImageOps.warpName(p))_\(p.rot)" as NSString
        if let img = cache.object(forKey: key) {
            cell.show(img, src: p.src)
            return
        }
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            let img = ImageOps.quickPreview(dir: dir, page: p, maxLong: 1000)
            DispatchQueue.main.async {
                guard let self = self, let img = img else { return }
                self.cache.setObject(img, forKey: key)
                if cell.pageId == p.id && cell.imageView.image == nil { cell.show(img, src: p.src) }
            }
        }
    }

    func scrollViewDidEndDecelerating(_ scrollView: UIScrollView) {
        pageChanged()
    }

    func scrollViewDidEndScrollingAnimation(_ scrollView: UIScrollView) {
        pageChanged()
    }

    private func pageChanged() {
        let w = collection.bounds.width
        guard w > 0 else { return }
        let i = max(0, min(doc.pages.count - 1, Int((collection.contentOffset.x / w).rounded())))
        if i != index {
            index = i
            updateIndicator()
            if filterPanelVisible { refreshFilterPanel() }
        }
    }

    func goTo(_ i: Int, animated: Bool) {
        guard i >= 0 && i < doc.pages.count else { return }
        index = i
        collection.setContentOffset(CGPoint(x: CGFloat(i) * collection.bounds.width, y: 0), animated: animated)
        updateIndicator()
        if filterPanelVisible { refreshFilterPanel() }
    }

    @objc private func prevPage() { goTo(index - 1, animated: true) }

    @objc private func nextPage() { goTo(index + 1, animated: true) }

    // MARK: so sánh

    func testCompare(_ on: Bool) {
        if on { compareDown() } else { compareUp() }
    }

    @objc private func compareDown() {
        guard let p = current, let w = p.warp else { return }
        let url = DocStore.shared.url(doc, w)
        guard let img = ImageOps.thumbnail(url, maxPixel: 1800), let cg = img.cgImage else { return }
        let o: UIImage.Orientation
        switch ((p.rot % 360) + 360) % 360 {
        case 90: o = .right
        case 180: o = .down
        case 270: o = .left
        default: o = .up
        }
        compareView.image = UIImage(cgImage: cg, scale: 1, orientation: o)
        compareView.isHidden = false
    }

    @objc private func compareUp() {
        compareView.isHidden = true
        compareView.image = nil
    }

    // MARK: bộ lọc

    @objc func toggleFilterPanel() {
        let show = !filterPanelVisible
        filterPanelHeight.constant = show ? 154 : 0
        UIView.animate(withDuration: 0.2) { self.view.layoutIfNeeded() }
        if show { refreshFilterPanel() }
    }

    private func refreshFilterSelection() {
        guard let p = current else { return }
        for (k, mode) in Enhancer.modes.enumerated() {
            let sel = mode == p.filter
            filterThumbs[k].layer.borderColor = (sel ? Theme.accent : UIColor(rgb: 0x555458)).cgColor
            filterLabels[k].textColor = sel ? Theme.accent : .white
            filterButtons[k].accessibilityTraits = sel ? [.button, .selected] : [.button]
        }
    }

    private func refreshFilterPanel() {
        refreshFilterSelection()
        guard let p = current else { return }
        let dir = DocStore.shared.dir(doc)
        let pid = p.id
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            guard let base = ImageOps.quickPreview(dir: dir, page: p, maxLong: 220)?.cgImage else { return }
            let outs = Enhancer.modes.map { m -> UIImage? in
                ImageOps.filtered(base, mode: m).map { UIImage(cgImage: $0) }
            }
            DispatchQueue.main.async {
                guard let self = self, self.current?.id == pid else { return }
                for (k, img) in outs.enumerated() { self.filterThumbs[k].image = img }
            }
        }
    }

    @objc private func filterTapped(_ b: UIButton) {
        applyFilter(Enhancer.modes[b.tag])
    }

    func applyFilter(_ mode: Int) {
        guard let cur = current else { return }
        if applyAll {
            for i in 0..<doc.pages.count where doc.pages[i].filter != mode {
                doc.pages[i].filter = mode
                schedule(doc.pages[i])
            }
        } else if cur.filter != mode {
            doc.pages[index].filter = mode
            schedule(doc.pages[index])
        }
        DocStore.shared.save(doc)
        refreshFilterSelection()
    }

    // MARK: thao tác

    @objc func rotateLeft() {
        guard current != nil else { return }
        doc.pages[index].rot = (doc.pages[index].rot + 270) % 360
        DocStore.shared.save(doc)
        schedule(doc.pages[index])
        if filterPanelVisible { refreshFilterPanel() }
    }

    @objc func openCrop() {
        guard let p = current else { return }
        let url = DocStore.shared.url(doc, p.src)
        let pid = p.id
        showWait()
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            let img = ImageOps.thumbnail(url, maxPixel: 2048)
            DispatchQueue.main.async {
                guard let self = self else { return }
                self.busy.hide()
                guard let img = img else {
                    self.toast("Không đọc được ảnh")
                    return
                }
                let full = CGSize(width: max(1, p.srcW), height: max(1, p.srcH))
                let crop = CropViewController(image: img, fullSize: full, quad: p.quadPoints, auto: p.auto, mode: .edit)
                crop.onDone = { [weak self, weak crop] q, auto in
                    crop?.dismiss(animated: true)
                    guard let self = self, let i = self.doc.index(of: pid) else { return }
                    self.doc.pages[i].quad = Page.pack(q)
                    self.doc.pages[i].auto = auto
                    DocStore.shared.save(self.doc)
                    self.schedule(self.doc.pages[i])
                    if self.filterPanelVisible { self.refreshFilterPanel() }
                }
                crop.onCancel = { [weak crop] in crop?.dismiss(animated: true) }
                self.present(crop, animated: true)
            }
        }
    }

    @objc func retakeTapped() {
        guard let p = current else { return }
        if pending > 0 {
            showWait()
            afterPending = { [weak self] in self?.retakeTapped() }
            return
        }
        let cam = CameraViewController(mode: .retake(doc, p.id))
        cam.onFinished = { [weak self] d in
            guard let self = self else { return }
            self.dismiss(animated: true)
            guard d != nil else { return }
            self.collection.reloadData()
            for q in self.doc.pages where !self.upToDate(q) { self.schedule(q) }
            self.updateIndicator()
        }
        let nav = UINavigationController(rootViewController: cam)
        nav.modalPresentationStyle = .fullScreen
        present(nav, animated: true)
    }

    @objc private func deleteCurrent() {
        guard let p = current else { return }
        confirm("Xoá trang này?", message: nil, ok: "Xoá") { [weak self] in
            self?.deletePage(p.id)
        }
    }

    func deletePage(_ pid: String) {
        processing.remove(pid)
        gen[pid] = -1
        setShared(pid, -1)
        DocStore.shared.removePage(doc, pageId: pid)
        if doc.pages.isEmpty {
            if context != .camera { DocStore.shared.delete(doc) }
            onDone?(false)
            return
        }
        index = min(index, doc.pages.count - 1)
        collection.reloadData()
        collection.layoutIfNeeded()
        collection.setContentOffset(CGPoint(x: CGFloat(index) * collection.bounds.width, y: 0), animated: false)
        updateIndicator()
        if filterPanelVisible { refreshFilterPanel() }
    }

    @objc private func rename() {
        prompt("Đổi tên tài liệu", initial: doc.title) { [weak self] v in
            guard let self = self else { return }
            self.doc.title = v
            DocStore.shared.save(self.doc)
            self.titleBtn.setTitle(v, for: .normal)
        }
    }

    @objc func doneTapped() { finishEditing(done: true) }

    /// done = true: lưu tài liệu; false: quay lại màn hình chụp.
    func finishEditing(done: Bool) {
        if pending > 0 {
            showWait()
            afterPending = { [weak self] in self?.finishEditing(done: done) }
            return
        }
        if done && !finishRetried {
            var any = false
            for p in doc.pages where !upToDate(p) {
                schedule(p)
                any = true
            }
            if any {
                finishRetried = true
                finishEditing(done: true)
                return
            }
        }
        if done {
            doc.draft = false
            doc.modified = Date()
        }
        DocStore.shared.save(doc)
        DocStore.shared.gc(doc)
        onDone?(done)
    }

    @objc private func backTapped() {
        if filterPanelVisible {
            toggleFilterPanel()
            return
        }
        switch context {
        case .camera:
            finishEditing(done: false)
        case .newImport where doc.draft:
            confirm("Bỏ tài liệu này?", message: "Các trang vừa nhập sẽ không được lưu.", ok: "Bỏ") { [weak self] in
                guard let self = self else { return }
                self.afterPending = nil
                DocStore.shared.delete(self.doc)
                self.onDone?(false)
            }
        default:
            finishEditing(done: true)
        }
    }
}

/// Ô hiển thị một trang, có hiệu ứng quét khi đang xử lý.
final class PageCell: UICollectionViewCell {
    let imageView = UIImageView()
    var pageId: String?
    var shownSrc: String?
    private let scanLine = UIView()
    private let gradient = CAGradientLayer()
    private var scanning = false

    override init(frame: CGRect) {
        super.init(frame: frame)
        imageView.contentMode = .scaleAspectFit
        imageView.layer.shadowColor = UIColor.black.cgColor
        imageView.layer.shadowOpacity = 0.4
        imageView.layer.shadowRadius = 6
        contentView.addSubview(imageView)
        gradient.colors = [Theme.accent.withAlphaComponent(0).cgColor, Theme.accent.withAlphaComponent(0.55).cgColor, Theme.accent.withAlphaComponent(0).cgColor]
        gradient.startPoint = CGPoint(x: 0.5, y: 0)
        gradient.endPoint = CGPoint(x: 0.5, y: 1)
        scanLine.layer.addSublayer(gradient)
        scanLine.isHidden = true
        scanLine.isUserInteractionEnabled = false
        contentView.addSubview(scanLine)
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    override func layoutSubviews() {
        super.layoutSubviews()
        imageView.frame = contentView.bounds.insetBy(dx: 16, dy: 8)
        if scanning { restartScan() }
    }

    override func prepareForReuse() {
        super.prepareForReuse()
        imageView.image = nil
        shownSrc = nil
        pageId = nil
        setScanning(false)
    }

    func show(_ img: UIImage, src: String) {
        imageView.image = img
        shownSrc = src
        if scanning { restartScan() }
    }

    private func imageRect() -> CGRect {
        guard let img = imageView.image, img.size.width > 0, img.size.height > 0 else { return imageView.frame }
        let b = imageView.frame
        let k = min(b.width / img.size.width, b.height / img.size.height)
        let w = img.size.width * k, h = img.size.height * k
        return CGRect(x: b.midX - w / 2, y: b.midY - h / 2, width: w, height: h)
    }

    func setScanning(_ on: Bool) {
        if on == scanning { return }
        scanning = on
        if on { restartScan() } else {
            scanLine.layer.removeAllAnimations()
            scanLine.isHidden = true
        }
    }

    private func restartScan() {
        let r = imageRect()
        guard r.height > 10 else { return }
        scanLine.layer.removeAllAnimations()
        let bandH: CGFloat = min(90, r.height / 3)
        scanLine.frame = CGRect(x: r.minX, y: r.minY, width: r.width, height: bandH)
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        gradient.frame = scanLine.bounds
        CATransaction.commit()
        scanLine.isHidden = false
        let a = CABasicAnimation(keyPath: "position.y")
        a.fromValue = r.minY + bandH / 2
        a.toValue = r.maxY - bandH / 2
        a.duration = 1.1
        a.autoreverses = true
        a.repeatCount = .infinity
        a.timingFunction = CAMediaTimingFunction(name: .easeInEaseOut)
        scanLine.layer.add(a, forKey: "scan")
    }
}
