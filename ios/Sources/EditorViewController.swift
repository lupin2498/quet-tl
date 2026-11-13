import UIKit

/// Trình chỉnh sửa trang. Thanh công cụ chia ba nhóm như CamScanner:
/// Ảnh (Cắt, Xoay trái, Bộ lọc, Xóa thông minh, Chụp lại) · Đánh dấu (Ký tên, Thêm văn bản, Brush, Khảm, Thêm hình mờ)
/// · Trang (Cắt dán, Tiêu đề trang). Kèm So sánh, Xoá trang, đổi tên, lời nhắc gỡ ngón tay; ✓ để lưu.
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
    /// Có thay đổi nội dung (lọc, xoay, cắt, xoá, chụp lại, đổi tên) -> cập nhật thời điểm sửa khi lưu.
    private var dirty = false
    /// Đã báo kết quả cho màn hình gọi (chống lưu/đóng hai lần khi chạm đúp).
    private var finished = false
    /// Trang xử lý lỗi (khoá = tên ảnh kết quả mong muốn): không hiện hiệu ứng "đang quét" mãi.
    private var failed = Set<String>()
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
    private static let tabNames = ["Ảnh", "Đánh dấu", "Trang"]
    private let toolBar = UIView()
    /// Thẻ nhóm + dải công cụ cuộn ngang + nút ✓.
    private let toolsArea = UIView()
    /// ✕ · Bộ lọc · ✓ (thay cho dải công cụ khi bảng lọc đang mở).
    private let filterBar = UIView()
    private let toolScroll = UIScrollView()
    private var tabButtons = [UIButton]()
    private var tabAnchors = [UIView]()
    private var activeTab = -1
    /// Bộ lọc của từng trang lúc mở bảng lọc (để nút ✕ trả lại).
    private var filterBackup: [String: Int]?
    private let banner = UIView()
    /// Tài liệu ghép vừa tạo bằng "Cắt dán" (cho tự kiểm thử).
    private(set) var lastCollageId: String?

    init(doc: Doc, context: Context, startPage: Int = 0) {
        self.doc = doc
        self.context = context
        self.index = max(0, min(startPage, doc.pages.count - 1))
        super.init(nibName: nil, bundle: nil)
        // giới hạn theo dung lượng điểm ảnh (ảnh xem 1800 px ~ 9 MB mỗi trang)
        cache.countLimit = 24
        cache.totalCostLimit = 96 * 1024 * 1024
    }

    private func cacheSet(_ img: UIImage, _ key: NSString) {
        let cost = Int(img.size.width * img.scale * img.size.height * img.scale * 4)
        cache.setObject(img, forKey: key, cost: cost)
    }

    /// Báo kết quả cho màn hình gọi — đúng một lần.
    private func complete(_ saved: Bool) {
        if finished { return }
        finished = true
        afterPending = nil
        busy.hide()
        onDone?(saved)
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = Theme.bg
        buildUI()
        setActiveTab(0)
        for p in doc.pages where !upToDate(p) { schedule(p) }
        updateIndicator()
        updateBanner()
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
        buildBanner()

        toolBar.backgroundColor = Theme.bgDark
        toolBar.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(toolBar)
        toolsArea.translatesAutoresizingMaskIntoConstraints = false
        toolBar.addSubview(toolsArea)

        // hàng thẻ: Ảnh | Đánh dấu | Trang — chạm để cuộn tới nhóm công cụ tương ứng
        let tabs = UIStackView()
        tabs.axis = .horizontal
        tabs.spacing = 2
        tabs.translatesAutoresizingMaskIntoConstraints = false
        toolsArea.addSubview(tabs)
        for (k, name) in EditorViewController.tabNames.enumerated() {
            let b = UIButton(type: .system)
            b.setTitle(name, for: .normal)
            b.titleLabel?.font = UIFont.systemFont(ofSize: 14, weight: .medium)
            b.tintColor = Theme.text2
            b.tag = k
            b.addTarget(self, action: #selector(tabTapped(_:)), for: .touchUpInside)
            b.widthAnchor.constraint(greaterThanOrEqualToConstant: 62).isActive = true
            tabs.addArrangedSubview(b)
            tabButtons.append(b)
        }

        toolScroll.showsHorizontalScrollIndicator = false
        toolScroll.delegate = self
        toolScroll.translatesAutoresizingMaskIntoConstraints = false
        toolsArea.addSubview(toolScroll)
        let strip = UIStackView()
        strip.axis = .horizontal
        strip.alignment = .center
        strip.spacing = 0
        strip.translatesAutoresizingMaskIntoConstraints = false
        toolScroll.addSubview(strip)
        // ---- Ảnh
        tabAnchors.append(addTool(strip, "crop", "Cắt", #selector(openCrop)))
        addTool(strip, "rotate.left", "Bên trái", #selector(rotateLeft))
        addTool(strip, "camera.filters", "Bộ lọc", #selector(toggleFilterPanel))
        addTool(strip, "wand.and.rays", "Xóa thông minh", #selector(openErase))
        addTool(strip, "camera", "Chụp lại", #selector(retakeTapped))
        addDivider(strip)
        // ---- Đánh dấu
        tabAnchors.append(addTool(strip, "signature", "Ký tên", #selector(markSign)))
        addTool(strip, "textformat", "Thêm văn bản", #selector(markText))
        addTool(strip, "paintbrush", "Brush", #selector(markBrush))
        addTool(strip, "square.grid.3x3.fill", "Khảm", #selector(markMosaic))
        addTool(strip, "drop", "Thêm hình mờ", #selector(markWatermark))
        addDivider(strip)
        // ---- Trang
        tabAnchors.append(addTool(strip, "square.grid.2x2", "Cắt dán", #selector(collageTapped)))
        addTool(strip, "doc.plaintext", "Tiêu đề trang", #selector(editHeader))

        let ok = UIButton(type: .custom)
        ok.setImage(Theme.symbol("checkmark", size: 22, weight: .bold), for: .normal)
        ok.tintColor = .white
        ok.backgroundColor = Theme.accent
        ok.layer.cornerRadius = 26
        ok.accessibilityLabel = "Xong"
        ok.accessibilityIdentifier = "editorDone"
        ok.addTarget(self, action: #selector(doneTapped), for: .touchUpInside)
        ok.translatesAutoresizingMaskIntoConstraints = false
        toolsArea.addSubview(ok)

        filterBar.isHidden = true
        filterBar.translatesAutoresizingMaskIntoConstraints = false
        toolBar.addSubview(filterBar)
        let fCancel = Theme.iconButton("xmark", size: 18, label: "Hủy bộ lọc")
        fCancel.addTarget(self, action: #selector(cancelFilters), for: .touchUpInside)
        let fKeep = Theme.iconButton("checkmark", size: 19, tint: Theme.accent, label: "Đóng bộ lọc")
        fKeep.addTarget(self, action: #selector(keepFilters), for: .touchUpInside)
        let fTitle = Theme.label("Bộ lọc", size: 16, weight: .semibold)
        [fCancel, fKeep, fTitle].forEach {
            $0.translatesAutoresizingMaskIntoConstraints = false
            filterBar.addSubview($0)
        }

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

            banner.topAnchor.constraint(equalTo: collection.topAnchor, constant: 2),
            banner.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 14),
            banner.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -14),

            nav.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            nav.heightAnchor.constraint(equalToConstant: 36),
            nav.bottomAnchor.constraint(equalTo: filterPanel.topAnchor, constant: -4),

            filterPanel.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            filterPanel.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            filterPanel.bottomAnchor.constraint(equalTo: toolBar.topAnchor),

            toolBar.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            toolBar.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            toolBar.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            toolBar.topAnchor.constraint(equalTo: g.bottomAnchor, constant: -96),

            toolsArea.topAnchor.constraint(equalTo: toolBar.topAnchor),
            toolsArea.leadingAnchor.constraint(equalTo: toolBar.leadingAnchor),
            toolsArea.trailingAnchor.constraint(equalTo: toolBar.trailingAnchor),
            toolsArea.heightAnchor.constraint(equalToConstant: 96),
            filterBar.topAnchor.constraint(equalTo: toolBar.topAnchor),
            filterBar.leadingAnchor.constraint(equalTo: toolBar.leadingAnchor),
            filterBar.trailingAnchor.constraint(equalTo: toolBar.trailingAnchor),
            filterBar.heightAnchor.constraint(equalToConstant: 96),

            tabs.topAnchor.constraint(equalTo: toolsArea.topAnchor, constant: 2),
            tabs.leadingAnchor.constraint(equalTo: toolsArea.leadingAnchor, constant: 6),
            tabs.heightAnchor.constraint(equalToConstant: 30),

            toolScroll.topAnchor.constraint(equalTo: tabs.bottomAnchor, constant: 2),
            toolScroll.leadingAnchor.constraint(equalTo: toolsArea.leadingAnchor, constant: 4),
            toolScroll.heightAnchor.constraint(equalToConstant: 58),
            toolScroll.trailingAnchor.constraint(equalTo: ok.leadingAnchor, constant: -8),
            strip.topAnchor.constraint(equalTo: toolScroll.contentLayoutGuide.topAnchor),
            strip.bottomAnchor.constraint(equalTo: toolScroll.contentLayoutGuide.bottomAnchor),
            strip.leadingAnchor.constraint(equalTo: toolScroll.contentLayoutGuide.leadingAnchor),
            strip.trailingAnchor.constraint(equalTo: toolScroll.contentLayoutGuide.trailingAnchor),
            strip.heightAnchor.constraint(equalTo: toolScroll.frameLayoutGuide.heightAnchor),

            ok.trailingAnchor.constraint(equalTo: toolsArea.trailingAnchor, constant: -16),
            ok.centerYAnchor.constraint(equalTo: toolScroll.centerYAnchor),
            ok.widthAnchor.constraint(equalToConstant: 52),
            ok.heightAnchor.constraint(equalToConstant: 52),

            fCancel.leadingAnchor.constraint(equalTo: filterBar.leadingAnchor, constant: 8),
            fCancel.centerYAnchor.constraint(equalTo: filterBar.centerYAnchor, constant: -8),
            fCancel.widthAnchor.constraint(equalToConstant: 48),
            fCancel.heightAnchor.constraint(equalToConstant: 44),
            fKeep.trailingAnchor.constraint(equalTo: filterBar.trailingAnchor, constant: -8),
            fKeep.centerYAnchor.constraint(equalTo: fCancel.centerYAnchor),
            fKeep.widthAnchor.constraint(equalToConstant: 48),
            fKeep.heightAnchor.constraint(equalToConstant: 44),
            fTitle.centerXAnchor.constraint(equalTo: filterBar.centerXAnchor),
            fTitle.centerYAnchor.constraint(equalTo: fCancel.centerYAnchor)
        ])
    }

    /// Thêm một công cụ vào dải cuộn ngang: rộng theo nhãn (tối thiểu 60) để nhãn dài không bị cắt.
    @discardableResult
    private func addTool(_ strip: UIStackView, _ symbol: String, _ title: String, _ action: Selector) -> UIButton {
        let b = Theme.toolButton(symbol, title)
        b.addTarget(self, action: action, for: .touchUpInside)
        b.translatesAutoresizingMaskIntoConstraints = false
        b.widthAnchor.constraint(equalToConstant: Theme.toolWidth(title, minWidth: 60)).isActive = true
        b.heightAnchor.constraint(equalToConstant: 58).isActive = true
        strip.addArrangedSubview(b)
        return b
    }

    private func addDivider(_ strip: UIStackView) {
        let d = UIView()
        d.backgroundColor = UIColor(white: 1, alpha: 0.14)
        d.translatesAutoresizingMaskIntoConstraints = false
        d.widthAnchor.constraint(equalToConstant: 1).isActive = true
        d.heightAnchor.constraint(equalToConstant: 30).isActive = true
        if let last = strip.arrangedSubviews.last { strip.setCustomSpacing(4, after: last) }
        strip.addArrangedSubview(d)
        strip.setCustomSpacing(4, after: d)
    }

    private func setActiveTab(_ i: Int) {
        if activeTab == i { return }
        activeTab = i
        for (k, b) in tabButtons.enumerated() { b.tintColor = k == i ? Theme.accent : Theme.text2 }
    }

    @objc private func tabTapped(_ b: UIButton) {
        let i = b.tag
        guard i >= 0, i < tabAnchors.count else { return }
        setActiveTab(i)
        let maxX = max(0, toolScroll.contentSize.width - toolScroll.bounds.width)
        let x = min(maxX, max(0, tabAnchors[i].frame.minX - 4))
        toolScroll.setContentOffset(CGPoint(x: x, y: 0), animated: true)
    }

    /// Khi người dùng tự cuộn dải công cụ: thẻ sáng lên theo nhóm đang nằm ở mép trái.
    func scrollViewDidScroll(_ scrollView: UIScrollView) {
        guard scrollView === toolScroll, !tabAnchors.isEmpty, scrollView.isDragging || scrollView.isDecelerating else { return }
        let x = scrollView.contentOffset.x + 40
        let maxX = scrollView.contentSize.width - scrollView.bounds.width
        var idx = 0
        for (k, a) in tabAnchors.enumerated() where x >= a.frame.minX { idx = k }
        // cuộn hết sang phải mà nhóm cuối chưa chạm mép trái thì vẫn tính là nhóm cuối
        if maxX > 0 && scrollView.contentOffset.x >= maxX - 2 { idx = tabAnchors.count - 1 }
        setActiveTab(idx)
    }

    /// Lời nhắc "Phát hiện ngón tay" kèm nút Gỡ bỏ (chuyển trang sang bộ lọc Thần kỳ).
    private func buildBanner() {
        banner.backgroundColor = UIColor(rgb: 0x2C2B2E, alpha: 0.96)
        banner.layer.cornerRadius = 12
        banner.isHidden = true
        banner.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(banner)
        let l = Theme.label("Phát hiện ngón tay. Thử bộ lọc Thần kỳ.", size: 13.5)
        l.numberOfLines = 2
        var cfg = UIButton.Configuration.filled()
        cfg.title = "Gỡ bỏ"
        cfg.baseBackgroundColor = Theme.accent
        cfg.baseForegroundColor = .white
        cfg.cornerStyle = .capsule
        cfg.contentInsets = NSDirectionalEdgeInsets(top: 6, leading: 14, bottom: 6, trailing: 14)
        cfg.titleTextAttributesTransformer = UIConfigurationTextAttributesTransformer { incoming in
            var out = incoming
            out.font = UIFont.systemFont(ofSize: 13.5, weight: .semibold)
            return out
        }
        let go = UIButton(configuration: cfg)
        go.addTarget(self, action: #selector(removeFinger), for: .touchUpInside)
        go.setContentHuggingPriority(.required, for: .horizontal)
        go.setContentCompressionResistancePriority(.required, for: .horizontal)
        let x = Theme.iconButton("xmark", size: 13, tint: Theme.text2, label: "Đóng lời nhắc")
        x.addTarget(self, action: #selector(dismissBanner), for: .touchUpInside)
        [l, go, x].forEach {
            $0.translatesAutoresizingMaskIntoConstraints = false
            banner.addSubview($0)
        }
        NSLayoutConstraint.activate([
            l.leadingAnchor.constraint(equalTo: banner.leadingAnchor, constant: 14),
            l.topAnchor.constraint(equalTo: banner.topAnchor, constant: 8),
            l.bottomAnchor.constraint(equalTo: banner.bottomAnchor, constant: -8),
            go.leadingAnchor.constraint(equalTo: l.trailingAnchor, constant: 8),
            go.centerYAnchor.constraint(equalTo: banner.centerYAnchor),
            x.leadingAnchor.constraint(equalTo: go.trailingAnchor, constant: 2),
            x.trailingAnchor.constraint(equalTo: banner.trailingAnchor, constant: -2),
            x.centerYAnchor.constraint(equalTo: banner.centerYAnchor),
            x.widthAnchor.constraint(equalToConstant: 40),
            x.heightAnchor.constraint(equalToConstant: 40),
            banner.heightAnchor.constraint(greaterThanOrEqualToConstant: 44)
        ])
    }

    var bannerVisible: Bool { !banner.isHidden }

    /// (tự kiểm thử) bề ngang nhìn thấy của dải công cụ và khung từng nút công cụ, theo thứ tự từ trái sang.
    var testToolFrames: (visible: CGFloat, tools: [(title: String, frame: CGRect)]) {
        view.layoutIfNeeded()
        var out = [(title: String, frame: CGRect)]()
        if let strip = toolScroll.subviews.first(where: { $0 is UIStackView }) as? UIStackView {
            for case let b as UIButton in strip.arrangedSubviews {
                out.append((title: b.configuration?.title ?? "", frame: b.frame))
            }
        }
        return (visible: toolScroll.bounds.width, tools: out)
    }

    private func updateBanner() {
        var show = false
        if let p = current {
            // bộ lọc đã gỡ ngón tay (Thần kỳ, Làm phẳng): không cần nhắc
            show = p.finger == 1 && p.fingerOff != true && !Enhancer.removesFingers(p.filter) && !filterPanelVisible
        }
        banner.isHidden = !show
    }

    /// "Gỡ bỏ": chuyển trang sang bộ lọc Thần kỳ (gỡ ngón tay giữ giấy); trang đang dùng bộ lọc đã gỡ ngón tay
    /// (Thần kỳ, Làm phẳng) thì giữ nguyên bộ lọc.
    @objc func removeFinger() {
        guard current != nil, !finished else { return }
        if !Enhancer.removesFingers(doc.pages[index].filter) {
            doc.pages[index].filter = Enhancer.magicPro
            dirty = true
            DocStore.shared.save(doc)
            schedule(doc.pages[index])
        }
        updateBanner()
    }

    /// ✕ trên lời nhắc: tắt hẳn cho trang này.
    @objc func dismissBanner() {
        guard current != nil, !finished else { return }
        doc.pages[index].fingerOff = true
        DocStore.shared.save(doc)
        updateBanner()
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
            applyAllBtn.heightAnchor.constraint(equalToConstant: 36)
        ])
        updateApplyAll()
    }

    var filterPanelVisible: Bool { filterPanelHeight != nil && filterPanelHeight.constant > 0 }

    private func updateApplyAll() {
        let name = applyAll ? "checkmark.square.fill" : "square"
        applyAllBtn.configuration?.image = Theme.symbol(name, size: 17)?.withTintColor(applyAll ? Theme.accent : Theme.text2, renderingMode: .alwaysOriginal)
        applyAllBtn.accessibilityValue = applyAll ? "Bật" : "Tắt"
    }

    @objc private func toggleApplyAll() {
        applyAll.toggle()
        updateApplyAll()
        // vừa bật: áp bộ lọc của trang đang xem cho mọi trang
        if applyAll, let p = current { applyFilter(p.filter) }
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
                    // chỉ nhận kết quả nếu tham số trang vẫn như lúc bắt đầu xử lý
                    if let i = doc.index(of: pid), ImageOps.outName(doc.pages[i]) == np.out {
                        doc.pages[i].takeResult(np)
                        DocStore.shared.save(doc)
                        updateBanner()
                    }
                case .failure(let e):
                    if let i = doc.index(of: pid) { failed.insert(ImageOps.outName(doc.pages[i])) }
                    if !finished { toast("Lỗi xử lý trang: \(e.localizedDescription)") }
                }
            }
            reloadCell(pid)
        }
        if pending <= 0 {
            pending = 0
            busy.hide()
            let a = afterPending
            afterPending = nil
            if !finished { a?() }
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
        // danh sách trang có thể vừa đổi (xoá trang) trước khi bảng kịp tải lại -> không đọc quá số trang
        guard indexPath.item < doc.pages.count else {
            cell.pageId = nil
            cell.shownSrc = nil
            cell.imageView.image = nil
            cell.setScanning(false)
            return cell
        }
        configure(cell, doc.pages[indexPath.item])
        return cell
    }

    func collectionView(_ collectionView: UICollectionView, layout collectionViewLayout: UICollectionViewLayout, sizeForItemAt indexPath: IndexPath) -> CGSize {
        return collectionView.bounds.size
    }

    private func configure(_ cell: PageCell, _ p: Page) {
        let working = processing.contains(p.id) || (!upToDate(p) && !failed.contains(ImageOps.outName(p)))
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
                    self.cacheSet(img, key)
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
                self.cacheSet(img, key)
                if cell.pageId == p.id && cell.imageView.image == nil { cell.show(img, src: p.src) }
            }
        }
    }

    func scrollViewDidEndDecelerating(_ scrollView: UIScrollView) {
        if scrollView === collection { pageChanged() }
    }

    func scrollViewDidEndScrollingAnimation(_ scrollView: UIScrollView) {
        if scrollView === collection { pageChanged() }
    }

    private func pageChanged() {
        let w = collection.bounds.width
        guard w > 0 else { return }
        let i = max(0, min(doc.pages.count - 1, Int((collection.contentOffset.x / w).rounded())))
        if i != index {
            index = i
            updateIndicator()
            updateBanner()
            if filterPanelVisible { refreshFilterPanel() }
        }
    }

    func goTo(_ i: Int, animated: Bool) {
        guard i >= 0 && i < doc.pages.count else { return }
        index = i
        collection.setContentOffset(CGPoint(x: CGFloat(i) * collection.bounds.width, y: 0), animated: animated)
        updateIndicator()
        updateBanner()
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
        if filterPanelVisible {
            closeFilterPanel(keep: true)
            return
        }
        if finished { return }
        var backup = [String: Int]()
        for p in doc.pages { backup[p.id] = p.filter }
        filterBackup = backup
        filterPanelHeight.constant = 154
        toolsArea.isHidden = true
        filterBar.isHidden = false
        UIView.animate(withDuration: 0.2) { self.view.layoutIfNeeded() }
        updateBanner()
        refreshFilterPanel()
    }

    /// keep = false: trả các trang về bộ lọc lúc mở bảng.
    func closeFilterPanel(keep: Bool) {
        guard filterPanelVisible else { return }
        if !keep, let backup = filterBackup {
            var any = false
            for i in 0..<doc.pages.count {
                if let old = backup[doc.pages[i].id], old != doc.pages[i].filter {
                    doc.pages[i].filter = old
                    any = true
                    schedule(doc.pages[i])
                }
            }
            if any { DocStore.shared.save(doc) }
        }
        filterBackup = nil
        filterPanelHeight.constant = 0
        toolsArea.isHidden = false
        filterBar.isHidden = true
        UIView.animate(withDuration: 0.2) { self.view.layoutIfNeeded() }
        updateBanner()
    }

    @objc private func cancelFilters() { closeFilterPanel(keep: false) }

    @objc private func keepFilters() { closeFilterPanel(keep: true) }

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
                dirty = true
                schedule(doc.pages[i])
            }
        } else if cur.filter != mode {
            doc.pages[index].filter = mode
            dirty = true
            schedule(doc.pages[index])
        }
        DocStore.shared.save(doc)
        refreshFilterSelection()
        updateBanner()
    }

    // MARK: thao tác

    @objc func rotateLeft() {
        guard current != nil, !finished else { return }
        doc.pages[index].rot = (doc.pages[index].rot + 270) % 360
        dirty = true
        DocStore.shared.save(doc)
        schedule(doc.pages[index])
        if filterPanelVisible { refreshFilterPanel() }
    }

    @objc func openCrop() {
        guard let p = current, !finished, presentedViewController == nil else { return }
        let url = DocStore.shared.url(doc, p.src)
        let pid = p.id
        showWait()
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            let img = ImageOps.thumbnail(url, maxPixel: 2048)
            DispatchQueue.main.async {
                guard let self = self else { return }
                self.busy.hide()
                guard !self.finished, self.presentedViewController == nil else { return }
                guard let img = img else {
                    self.toast("Không đọc được ảnh")
                    return
                }
                let full = CGSize(width: max(1, p.srcW), height: max(1, p.srcH))
                let crop = CropViewController(image: img, fullSize: full, quad: p.quadPoints, auto: p.auto, mode: .edit)
                crop.onDone = { [weak self, weak crop] q, auto in
                    crop?.dismiss(animated: true)
                    guard let self = self, let i = self.doc.index(of: pid) else { return }
                    var pg = self.doc.pages[i]
                    let newQuad = Page.pack(q)
                    // nét xoá / đánh dấu đi theo vùng cắt mới để vẫn nằm đúng chỗ trên giấy
                    Edits.remap(&pg, from: pg.quad, to: newQuad)
                    pg.quad = newQuad
                    pg.auto = auto
                    self.doc.pages[i] = pg
                    self.dirty = true
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
        guard let p = current, !finished, presentedViewController == nil else { return }
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
            self.dirty = true
            self.collection.reloadData()
            for q in self.doc.pages where !self.upToDate(q) { self.schedule(q) }
            self.updateIndicator()
            self.updateBanner()
        }
        let nav = UINavigationController(rootViewController: cam)
        nav.modalPresentationStyle = .fullScreen
        present(nav, animated: true)
    }

    @objc private func deleteCurrent() {
        guard let p = current, !finished, presentedViewController == nil else { return }
        confirm("Xoá trang này?", message: nil, ok: "Xoá") { [weak self] in
            self?.deletePage(p.id)
        }
    }

    func deletePage(_ pid: String) {
        guard !finished, doc.index(of: pid) != nil else { return }
        processing.remove(pid)
        gen[pid] = -1
        setShared(pid, -1)
        DocStore.shared.removePage(doc, pageId: pid)
        dirty = true
        if doc.pages.isEmpty {
            if context != .camera { DocStore.shared.delete(doc) }
            // đồng bộ bảng với danh sách trang (rỗng) trước khi màn hình đóng: trong lúc chuyển cảnh
            // hệ thống còn có thể hỏi ô của trang vừa xoá
            collection.reloadData()
            updateIndicator()
            complete(false)
            return
        }
        index = min(index, doc.pages.count - 1)
        collection.reloadData()
        collection.layoutIfNeeded()
        collection.setContentOffset(CGPoint(x: CGFloat(index) * collection.bounds.width, y: 0), animated: false)
        updateIndicator()
        updateBanner()
        if filterPanelVisible { refreshFilterPanel() }
        // số trang đổi -> tiêu đề trang (nếu có đánh số) của các trang còn lại đổi theo
        for q in doc.pages where !upToDate(q) && !processing.contains(q.id) { schedule(q) }
    }

    // MARK: xóa thông minh, đánh dấu

    /// Trang hiện tại nếu đã có ảnh nắn phẳng; nếu còn đang xử lý thì chờ xong rồi gọi lại `retry` và trả về nil.
    private func pageWithWarp(_ retry: @escaping () -> Void) -> Page? {
        guard let p = current, !finished, presentedViewController == nil else { return nil }
        let fm = FileManager.default
        let has = p.warp.map { fm.fileExists(atPath: DocStore.shared.url(doc, $0).path) } ?? false
        if pending > 0 || !has {
            if pending == 0 {
                if processing.contains(p.id) { return nil }
                schedule(p)
            }
            showWait()
            afterPending = { [weak self] in
                guard let self = self, let c = self.current, let w = c.warp,
                      FileManager.default.fileExists(atPath: DocStore.shared.url(self.doc, w).path) else { return }
                retry()
            }
            return nil
        }
        return p
    }

    @objc func openErase() {
        guard let p = pageWithWarp({ [weak self] in self?.openErase() }) else { return }
        let pid = p.id
        let vc = EraseViewController(doc: doc, page: p)
        vc.onCancel = { [weak vc] in vc?.dismiss(animated: true) }
        vc.onDone = { [weak self, weak vc] ops in
            vc?.dismiss(animated: true)
            guard let self = self, let i = self.doc.index(of: pid) else { return }
            if (self.doc.pages[i].erase ?? []) == ops { return }
            self.doc.pages[i].erase = ops.isEmpty ? nil : ops
            self.dirty = true
            DocStore.shared.save(self.doc)
            self.schedule(self.doc.pages[i])
        }
        present(vc, animated: true)
    }

    func openMarkup(_ tool: Int) {
        guard let p = pageWithWarp({ [weak self] in self?.openMarkup(tool) }) else { return }
        let pid = p.id
        let vc = MarkupViewController(doc: doc, page: p, tool: tool)
        vc.onCancel = { [weak vc] in vc?.dismiss(animated: true) }
        vc.onDone = { [weak self, weak vc] marks in
            vc?.dismiss(animated: true)
            guard let self = self, let i = self.doc.index(of: pid) else { return }
            if (self.doc.pages[i].marks ?? []) == marks { return }
            self.doc.pages[i].marks = marks.isEmpty ? nil : marks
            self.dirty = true
            DocStore.shared.save(self.doc)
            self.schedule(self.doc.pages[i])
        }
        present(vc, animated: true)
    }

    @objc private func markSign() { openMarkup(MarkupViewController.sign) }
    @objc private func markText() { openMarkup(MarkupViewController.text) }
    @objc private func markBrush() { openMarkup(MarkupViewController.brush) }
    @objc private func markMosaic() { openMarkup(MarkupViewController.mosaic) }
    @objc private func markWatermark() { openMarkup(MarkupViewController.watermark) }

    // MARK: tiêu đề trang, cắt dán

    /// Tiêu đề trang: dòng chữ ở lề trên và/hoặc số trang ở lề dưới, áp cho mọi trang của tài liệu.
    @objc func editHeader() {
        guard !finished, presentedViewController == nil else { return }
        let a = UIAlertController(title: "Tiêu đề trang", message: "Dòng chữ in ở lề trên và số trang ở lề dưới của mọi trang.",
                                  preferredStyle: .alert)
        let old = doc.header?.text ?? ""
        a.addTextField { tf in
            tf.text = old
            tf.placeholder = "Dòng chữ ở lề trên (có thể để trống)"
            tf.clearButtonMode = .whileEditing
        }
        func header(_ alert: UIAlertController?, _ num: Bool) -> PageHeader? {
            let h = PageHeader(text: Theme.cleanTitle(alert?.textFields?.first?.text ?? "", fallback: ""), num: num)
            return h.isEmpty ? nil : h
        }
        a.addAction(UIAlertAction(title: "Áp dụng + đánh số trang (1/\(doc.pages.count))", style: .default) { [weak self, weak a] _ in
            self?.setHeader(header(a, true))
        })
        a.addAction(UIAlertAction(title: "Áp dụng, không đánh số", style: .default) { [weak self, weak a] _ in
            self?.setHeader(header(a, false))
        })
        if doc.header != nil {
            a.addAction(UIAlertAction(title: "Gỡ bỏ", style: .destructive) { [weak self] _ in self?.setHeader(nil) })
        }
        a.addAction(UIAlertAction(title: "Hủy", style: .cancel))
        present(a, animated: true)
    }

    func setHeader(_ h: PageHeader?) {
        if finished || doc.header == h { return }
        doc.header = h
        dirty = true
        // save() điền lại tiêu đề từng trang; trang nào đổi thì dựng lại
        DocStore.shared.save(doc)
        for p in doc.pages where !upToDate(p) { schedule(p) }
        toast(h == nil ? "Đã gỡ tiêu đề trang" : "Đã áp dụng tiêu đề trang cho \(doc.pages.count) trang")
    }

    /// Cắt dán: ghép các trang của tài liệu lên tờ A4 và lưu thành tài liệu mới.
    @objc func collageTapped() {
        guard !finished, presentedViewController == nil else { return }
        let a = UIAlertController(title: "Cắt dán: ghép \(doc.pages.count) trang lên tờ A4", message: nil, preferredStyle: .actionSheet)
        for (k, name) in Collage.layoutNames.enumerated() {
            a.addAction(UIAlertAction(title: name, style: .default) { [weak self] _ in self?.collage(layout: k) })
        }
        a.addAction(UIAlertAction(title: "Hủy", style: .cancel))
        present(a, animated: true)
    }

    func collage(layout: Int) {
        if finished { return }
        if pending > 0 {
            showWait()
            afterPending = { [weak self] in self?.collage(layout: layout) }
            return
        }
        let job: ImageOps.ExportJob
        do {
            job = try ImageOps.exportJob(doc: doc)
        } catch {
            toast(error.localizedDescription)
            return
        }
        let title = String(doc.title.prefix(Theme.maxTitle - 7)) + " - ghép"
        busy.show(in: navigationController?.view ?? view, text: "Đang ghép trang…")
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            let result = Result<Doc, Error> { try Collage.make(files: job.files, title: title, layout: layout) }
            DispatchQueue.main.async {
                guard let self = self else { return }
                self.busy.hide()
                switch result {
                case .failure(let e):
                    self.toast("Không ghép được: \(e.localizedDescription)")
                case .success(let d):
                    self.lastCollageId = d.id
                    if self.finished { return }
                    self.toast("Đã tạo tài liệu ghép")
                    self.navigationController?.pushViewController(DocumentViewController(doc: d), animated: true)
                }
            }
        }
    }

    @objc private func rename() {
        guard !finished, presentedViewController == nil else { return }
        prompt("Đổi tên tài liệu", initial: doc.title) { [weak self] v in
            guard let self = self, v != self.doc.title else { return }
            self.doc.title = v
            self.dirty = true
            DocStore.shared.save(self.doc)
            self.titleBtn.setTitle(v, for: .normal)
        }
    }

    @objc func doneTapped() { finishEditing(done: true) }

    /// done = true: lưu tài liệu; false: quay lại màn hình chụp.
    func finishEditing(done: Bool) {
        if finished { return }
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
            // chỉ xem mà không sửa gì thì giữ nguyên thời điểm sửa (không đẩy tài liệu lên đầu danh sách)
            if doc.draft || dirty { doc.modified = Date() }
            doc.draft = false
        }
        DocStore.shared.save(doc)
        DocStore.shared.gc(doc)
        complete(done)
    }

    /// Bỏ tài liệu vừa nhập (sau hộp thoại xác nhận) — cũng dùng cho tự kiểm thử.
    func discardDraft() {
        if finished { return }
        afterPending = nil
        DocStore.shared.delete(doc)
        complete(false)
    }

    @objc private func backTapped() {
        if finished || presentedViewController != nil { return }
        if filterPanelVisible {
            toggleFilterPanel()
            return
        }
        switch context {
        case .camera:
            finishEditing(done: false)
        case .newImport where doc.draft:
            confirm("Bỏ tài liệu này?", message: "Các trang vừa nhập sẽ không được lưu.", ok: "Bỏ") { [weak self] in
                self?.discardDraft()
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
