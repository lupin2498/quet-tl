import AVFoundation
import PhotosUI
import UIKit

/// Màn hình chụp: Đơn / Hàng loạt, đèn, lưới căn, nhập ảnh; chụp xong -> màn hình cắt.
final class CameraViewController: UIViewController, AVCapturePhotoCaptureDelegate, PHPickerViewControllerDelegate {
    enum Mode {
        case new(batch: Bool?)
        case add(Doc)
        case retake(Doc, String)
    }

    /// Nguồn ảnh giả lập khi không có máy ảnh (máy ảo) — dùng cho tự kiểm thử.
    static var simulatedCapture: (() -> UIImage?)?

    /// Gọi khi xong (doc != nil: đã lưu) hoặc khi đóng (nil).
    var onFinished: ((Doc?) -> Void)?

    private var doc: Doc?
    private var newDoc = false
    private var retakeId: String?
    private var batch = false
    private(set) var sessionPages = [String]()
    private var busy = false {
        didSet {
            if busy { spinner.startAnimating() } else { spinner.stopAnimating() }
            shutter.isEnabled = !busy
        }
    }
    private var torch = false
    private var grid = false
    private var cameraReady = false
    private var configured = false

    private let session: AVCaptureSession
    private let sessionQueue = DispatchQueue(label: "vn.quettailieu.camera")
    private let photoOutput = AVCapturePhotoOutput()
    private var device: AVCaptureDevice?
    private let previewLayer: AVCaptureVideoPreviewLayer
    private let preview = UIView()
    private let gridView = GridView()
    private let flashView = UIView()
    private let message = UILabel()
    private let settingsBtn = Theme.primaryButton("Mở Cài đặt")
    private let flashBtn = Theme.iconButton("bolt.slash.fill", size: 20, label: "Đèn flash")
    private let gridBtn = Theme.iconButton("grid", size: 20, label: "Lưới căn")
    private let tabSingle = UIButton(type: .custom)
    private let tabBatch = UIButton(type: .custom)
    private let tabs = UIStackView()
    private let retakeLabel = Theme.label("Chụp lại trang", size: 15, color: Theme.accent, weight: .semibold)
    private let shutter = ShutterButton()
    private let leftSlot = UIView()
    private let rightSlot = UIView()
    private let spinner = UIActivityIndicatorView(style: .large)
    private weak var thumbView: UIImageView?

    init(mode: Mode) {
        let s = AVCaptureSession()
        session = s
        previewLayer = AVCaptureVideoPreviewLayer(session: s)
        super.init(nibName: nil, bundle: nil)
        switch mode {
        case .new(let b):
            batch = b ?? UserDefaults.standard.bool(forKey: "batch")
        case .add(let d):
            doc = d
            batch = UserDefaults.standard.bool(forKey: "batch")
        case .retake(let d, let pid):
            doc = d
            retakeId = pid
            batch = false
        }
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    override var preferredStatusBarStyle: UIStatusBarStyle { .lightContent }
    override var prefersStatusBarHidden: Bool { false }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        UIDevice.current.beginGeneratingDeviceOrientationNotifications()
        buildUI()
        refreshBottom()
    }

    deinit {
        UIDevice.current.endGeneratingDeviceOrientationNotifications()
        let s = session
        sessionQueue.async { if s.isRunning { s.stopRunning() } }
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        navigationController?.setNavigationBarHidden(true, animated: animated)
        checkPermissionAndStart()
    }

    override func viewDidDisappear(_ animated: Bool) {
        super.viewDidDisappear(animated)
        let wasOn = torch
        torch = false
        updateTopIcons()
        let s = session
        let dev = device
        sessionQueue.async {
            if wasOn, let dev = dev, (try? dev.lockForConfiguration()) != nil {
                if dev.isTorchModeSupported(.off) { dev.torchMode = .off }
                dev.unlockForConfiguration()
            }
            if s.isRunning { s.stopRunning() }
        }
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        previewLayer.frame = preview.bounds
        let r = AVMakeRect(aspectRatio: CGSize(width: 3, height: 4), insideRect: preview.bounds)
        gridView.frame = r
        flashView.frame = r
    }

    // MARK: giao diện

    private func buildUI() {
        let close = Theme.iconButton("xmark", size: 20, label: "Đóng")
        close.addTarget(self, action: #selector(attemptClose), for: .touchUpInside)
        flashBtn.addTarget(self, action: #selector(toggleTorch), for: .touchUpInside)
        gridBtn.addTarget(self, action: #selector(toggleGrid), for: .touchUpInside)
        let top = UIStackView(arrangedSubviews: [close, UIView(), flashBtn, gridBtn])
        top.axis = .horizontal
        top.spacing = 6
        top.alignment = .center
        top.translatesAutoresizingMaskIntoConstraints = false
        [close, flashBtn, gridBtn].forEach {
            $0.widthAnchor.constraint(equalToConstant: 46).isActive = true
            $0.heightAnchor.constraint(equalToConstant: 46).isActive = true
        }
        view.addSubview(top)

        preview.backgroundColor = .black
        preview.clipsToBounds = true
        preview.translatesAutoresizingMaskIntoConstraints = false
        previewLayer.videoGravity = .resizeAspect
        preview.layer.addSublayer(previewLayer)
        gridView.isHidden = true
        preview.addSubview(gridView)
        flashView.backgroundColor = .white
        flashView.alpha = 0
        flashView.isUserInteractionEnabled = false
        preview.addSubview(flashView)
        preview.addGestureRecognizer(UITapGestureRecognizer(target: self, action: #selector(focusTap(_:))))
        view.addSubview(preview)

        message.textColor = .white
        message.font = UIFont.systemFont(ofSize: 16)
        message.numberOfLines = 0
        message.textAlignment = .center
        message.isHidden = true
        message.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(message)
        settingsBtn.isHidden = true
        settingsBtn.addTarget(self, action: #selector(openSettings), for: .touchUpInside)
        settingsBtn.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(settingsBtn)

        let bottom = UIView()
        bottom.backgroundColor = .black
        bottom.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(bottom)

        for (b, t) in [(tabSingle, "Đơn"), (tabBatch, "Hàng loạt")] {
            b.setTitle(t, for: .normal)
            b.titleLabel?.font = UIFont.systemFont(ofSize: 15, weight: .medium)
            b.accessibilityLabel = t
            b.addTarget(self, action: #selector(tabTapped(_:)), for: .touchUpInside)
            b.widthAnchor.constraint(equalToConstant: 96).isActive = true
        }
        tabs.addArrangedSubview(tabSingle)
        tabs.addArrangedSubview(tabBatch)
        tabs.axis = .horizontal
        tabs.spacing = 12
        tabs.translatesAutoresizingMaskIntoConstraints = false
        bottom.addSubview(tabs)
        retakeLabel.translatesAutoresizingMaskIntoConstraints = false
        bottom.addSubview(retakeLabel)

        shutter.accessibilityLabel = "Chụp"
        shutter.accessibilityIdentifier = "shutter"
        shutter.addTarget(self, action: #selector(shoot), for: .touchUpInside)
        [shutter, leftSlot, rightSlot].forEach {
            $0.translatesAutoresizingMaskIntoConstraints = false
            bottom.addSubview($0)
        }
        spinner.color = Theme.accent
        spinner.hidesWhenStopped = true
        spinner.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(spinner)

        let g = view.safeAreaLayoutGuide
        let previewH = preview.heightAnchor.constraint(equalTo: preview.widthAnchor, multiplier: 4.0 / 3.0)
        previewH.priority = .defaultHigh
        NSLayoutConstraint.activate([
            top.topAnchor.constraint(equalTo: g.topAnchor, constant: 2),
            top.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 8),
            top.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -8),
            top.heightAnchor.constraint(equalToConstant: 48),

            preview.topAnchor.constraint(equalTo: top.bottomAnchor, constant: 2),
            preview.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            preview.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            preview.bottomAnchor.constraint(lessThanOrEqualTo: bottom.topAnchor),
            previewH,

            message.centerYAnchor.constraint(equalTo: preview.centerYAnchor, constant: -20),
            message.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 32),
            message.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -32),
            settingsBtn.topAnchor.constraint(equalTo: message.bottomAnchor, constant: 18),
            settingsBtn.centerXAnchor.constraint(equalTo: view.centerXAnchor),

            bottom.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            bottom.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            bottom.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            bottom.topAnchor.constraint(equalTo: g.bottomAnchor, constant: -142),

            tabs.topAnchor.constraint(equalTo: bottom.topAnchor, constant: 6),
            tabs.centerXAnchor.constraint(equalTo: bottom.centerXAnchor),
            tabs.heightAnchor.constraint(equalToConstant: 36),
            retakeLabel.centerYAnchor.constraint(equalTo: tabs.centerYAnchor),
            retakeLabel.centerXAnchor.constraint(equalTo: bottom.centerXAnchor),

            shutter.centerXAnchor.constraint(equalTo: bottom.centerXAnchor),
            shutter.topAnchor.constraint(equalTo: tabs.bottomAnchor, constant: 10),
            shutter.widthAnchor.constraint(equalToConstant: 76),
            shutter.heightAnchor.constraint(equalToConstant: 76),

            leftSlot.centerYAnchor.constraint(equalTo: shutter.centerYAnchor),
            leftSlot.leadingAnchor.constraint(equalTo: bottom.leadingAnchor, constant: 12),
            leftSlot.trailingAnchor.constraint(equalTo: shutter.leadingAnchor, constant: -12),
            leftSlot.heightAnchor.constraint(equalToConstant: 80),
            rightSlot.centerYAnchor.constraint(equalTo: shutter.centerYAnchor),
            rightSlot.leadingAnchor.constraint(equalTo: shutter.trailingAnchor, constant: 12),
            rightSlot.trailingAnchor.constraint(equalTo: bottom.trailingAnchor, constant: -12),
            rightSlot.heightAnchor.constraint(equalToConstant: 80),

            spinner.centerXAnchor.constraint(equalTo: preview.centerXAnchor),
            spinner.centerYAnchor.constraint(equalTo: preview.centerYAnchor)
        ])
        updateTopIcons()
    }

    private func updateTopIcons() {
        flashBtn.setImage(Theme.symbol(torch ? "bolt.fill" : "bolt.slash.fill", size: 20), for: .normal)
        flashBtn.tintColor = torch ? Theme.accent : .white
        gridBtn.tintColor = grid ? Theme.accent : .white
        gridView.isHidden = !grid
    }

    private func styleTabs() {
        for (b, sel) in [(tabSingle, !batch), (tabBatch, batch)] {
            b.setTitleColor(sel ? Theme.accent : UIColor(white: 1, alpha: 0.8), for: .normal)
            b.accessibilityTraits = sel ? [.button, .selected] : [.button]
            b.viewWithTag(77)?.removeFromSuperview()
            if sel {
                let bar = UIView()
                bar.tag = 77
                bar.backgroundColor = Theme.accent
                bar.layer.cornerRadius = 1.5
                bar.translatesAutoresizingMaskIntoConstraints = false
                b.addSubview(bar)
                NSLayoutConstraint.activate([
                    bar.centerXAnchor.constraint(equalTo: b.centerXAnchor),
                    bar.bottomAnchor.constraint(equalTo: b.bottomAnchor, constant: -1),
                    bar.widthAnchor.constraint(equalToConstant: 18),
                    bar.heightAnchor.constraint(equalToConstant: 3)
                ])
            }
        }
    }

    private func place(_ v: UIView, in slot: UIView, width: CGFloat, height: CGFloat) {
        v.translatesAutoresizingMaskIntoConstraints = false
        slot.addSubview(v)
        NSLayoutConstraint.activate([
            v.centerXAnchor.constraint(equalTo: slot.centerXAnchor),
            v.centerYAnchor.constraint(equalTo: slot.centerYAnchor),
            v.widthAnchor.constraint(equalToConstant: width),
            v.heightAnchor.constraint(equalToConstant: height)
        ])
    }

    private func refreshBottom() {
        let retake = retakeId != nil
        tabs.isHidden = retake
        retakeLabel.isHidden = !retake
        styleTabs()
        leftSlot.subviews.forEach { $0.removeFromSuperview() }
        rightSlot.subviews.forEach { $0.removeFromSuperview() }
        if batch && !sessionPages.isEmpty {
            let undo = Theme.iconButton("arrow.uturn.backward", size: 22, label: "Hoàn tác")
            undo.addTarget(self, action: #selector(undoLast), for: .touchUpInside)
            place(undo, in: leftSlot, width: 52, height: 52)
            let box = UIControl()
            box.accessibilityLabel = "Xem các trang đã chụp"
            box.isAccessibilityElement = true
            box.accessibilityIdentifier = "batchThumb"
            box.addTarget(self, action: #selector(openEditorTapped), for: .touchUpInside)
            let img = UIImageView()
            img.contentMode = .scaleAspectFill
            img.clipsToBounds = true
            img.backgroundColor = UIColor(rgb: 0x555555)
            img.isUserInteractionEnabled = false
            let badge = Theme.label("\(sessionPages.count)", size: 12, weight: .semibold)
            badge.textAlignment = .center
            badge.backgroundColor = Theme.badge
            badge.layer.cornerRadius = 11
            badge.clipsToBounds = true
            let chev = UIImageView(image: Theme.symbol("chevron.right", size: 16))
            chev.tintColor = .white
            [img, badge, chev].forEach {
                $0.translatesAutoresizingMaskIntoConstraints = false
                box.addSubview($0)
            }
            NSLayoutConstraint.activate([
                img.leadingAnchor.constraint(equalTo: box.leadingAnchor),
                img.bottomAnchor.constraint(equalTo: box.bottomAnchor),
                img.widthAnchor.constraint(equalToConstant: 46),
                img.heightAnchor.constraint(equalToConstant: 58),
                badge.centerXAnchor.constraint(equalTo: img.trailingAnchor),
                badge.centerYAnchor.constraint(equalTo: img.topAnchor),
                badge.widthAnchor.constraint(equalToConstant: 22),
                badge.heightAnchor.constraint(equalToConstant: 22),
                chev.leadingAnchor.constraint(equalTo: img.trailingAnchor, constant: 14),
                chev.centerYAnchor.constraint(equalTo: img.centerYAnchor)
            ])
            place(box, in: rightSlot, width: 84, height: 70)
            thumbView = img
            if let d = doc, let last = sessionPages.last, let i = d.index(of: last) {
                let p = d.pages[i]
                let dir = DocStore.shared.dir(d)
                DispatchQueue.global(qos: .userInitiated).async {
                    let t = ImageOps.quickPreview(dir: dir, page: p, maxLong: 240)
                    DispatchQueue.main.async { [weak self] in
                        if let t = t { self?.thumbView?.image = t }
                    }
                }
            }
        } else {
            let imp = Theme.toolButton("photo.on.rectangle", "Nhập ảnh")
            imp.addTarget(self, action: #selector(pickImages), for: .touchUpInside)
            place(imp, in: leftSlot, width: 84, height: 72)
        }
    }

    // MARK: máy ảnh

    private func checkPermissionAndStart() {
        guard AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back) != nil else {
            cameraReady = false
            let sim = CameraViewController.simulatedCapture != nil
            showMessage(sim ? "Máy ảo không có máy ảnh — nút chụp dùng ảnh thử" : "Không tìm thấy máy ảnh — có thể nhập ảnh từ Thư viện", settings: false)
            return
        }
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            startSession()
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { ok in
                DispatchQueue.main.async {
                    if ok { self.startSession() } else { self.showMessage("Ứng dụng cần quyền Máy ảnh để quét tài liệu", settings: true) }
                }
            }
        default:
            showMessage("Ứng dụng cần quyền Máy ảnh để quét tài liệu", settings: true)
        }
    }

    private func showMessage(_ text: String?, settings: Bool) {
        message.text = text
        message.isHidden = text == nil
        settingsBtn.isHidden = !settings
    }

    private func startSession() {
        sessionQueue.async {
            if !self.configured {
                self.configured = true
                let ok = self.configureSession()
                DispatchQueue.main.async {
                    self.cameraReady = ok
                    if !ok {
                        let sim = CameraViewController.simulatedCapture != nil
                        self.showMessage(sim ? "Máy ảo không có máy ảnh — nút chụp dùng ảnh thử" : "Không tìm thấy máy ảnh — có thể nhập ảnh từ Thư viện", settings: false)
                    } else {
                        self.showMessage(nil, settings: false)
                    }
                }
            }
            if self.device != nil && !self.session.isRunning {
                self.session.startRunning()
            }
        }
    }

    /// Chạy trên sessionQueue.
    private func configureSession() -> Bool {
        guard let dev = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back),
              let input = try? AVCaptureDeviceInput(device: dev) else { return false }
        session.beginConfiguration()
        session.sessionPreset = .photo
        guard session.canAddInput(input), session.canAddOutput(photoOutput) else {
            session.commitConfiguration()
            return false
        }
        session.addInput(input)
        session.addOutput(photoOutput)
        session.commitConfiguration()
        if #available(iOS 16.0, *) {
            let dims = dev.activeFormat.supportedMaxPhotoDimensions
            if let best = dims.max(by: { Int($0.width) * Int($0.height) < Int($1.width) * Int($1.height) }) {
                photoOutput.maxPhotoDimensions = best
            }
        } else {
            photoOutput.isHighResolutionCaptureEnabled = true
        }
        if (try? dev.lockForConfiguration()) != nil {
            if dev.isFocusModeSupported(.continuousAutoFocus) { dev.focusMode = .continuousAutoFocus }
            if dev.isExposureModeSupported(.continuousAutoExposure) { dev.exposureMode = .continuousAutoExposure }
            dev.unlockForConfiguration()
        }
        device = dev
        return true
    }

    @objc private func focusTap(_ g: UITapGestureRecognizer) {
        guard let dev = device, cameraReady else { return }
        let p = previewLayer.captureDevicePointConverted(fromLayerPoint: g.location(in: preview))
        sessionQueue.async {
            guard (try? dev.lockForConfiguration()) != nil else { return }
            if dev.isFocusPointOfInterestSupported && dev.isFocusModeSupported(.autoFocus) {
                dev.focusPointOfInterest = p
                dev.focusMode = .autoFocus
            }
            if dev.isExposurePointOfInterestSupported && dev.isExposureModeSupported(.continuousAutoExposure) {
                dev.exposurePointOfInterest = p
                dev.exposureMode = .continuousAutoExposure
            }
            dev.unlockForConfiguration()
        }
    }

    @objc private func toggleTorch() {
        guard let dev = device, dev.hasTorch else {
            toast("Thiết bị không có đèn flash")
            return
        }
        torch.toggle()
        let on = torch
        sessionQueue.async {
            guard (try? dev.lockForConfiguration()) != nil else { return }
            if dev.isTorchModeSupported(on ? .on : .off) { dev.torchMode = on ? .on : .off }
            dev.unlockForConfiguration()
        }
        updateTopIcons()
    }

    @objc private func toggleGrid() {
        grid.toggle()
        updateTopIcons()
    }

    @objc private func openSettings() {
        if let u = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(u) }
    }

    private func videoOrientation() -> AVCaptureVideoOrientation {
        switch UIDevice.current.orientation {
        case .landscapeLeft: return .landscapeRight
        case .landscapeRight: return .landscapeLeft
        case .portraitUpsideDown: return .portraitUpsideDown
        default: return .portrait
        }
    }

    private func flash() {
        flashView.alpha = 0.85
        UIView.animate(withDuration: 0.25) { self.flashView.alpha = 0 }
    }

    @objc func shoot() {
        if busy { return }
        if !cameraReady {
            if let sim = CameraViewController.simulatedCapture, let img = sim() {
                busy = true
                flash()
                DispatchQueue.global(qos: .userInitiated).async {
                    let norm = ImageOps.normalized(img, maxDim: ImageOps.maxSrc)
                    DispatchQueue.main.async { self.onCaptured(norm) }
                }
            } else {
                toast("Máy ảnh chưa sẵn sàng")
            }
            return
        }
        busy = true
        flash()
        let orientation = videoOrientation()
        sessionQueue.async {
            guard self.session.isRunning else {
                DispatchQueue.main.async {
                    self.busy = false
                    self.toast("Máy ảnh chưa sẵn sàng")
                }
                return
            }
            if let conn = self.photoOutput.connection(with: .video), conn.isVideoOrientationSupported {
                conn.videoOrientation = orientation
            }
            let settings: AVCapturePhotoSettings
            if self.photoOutput.availablePhotoCodecTypes.contains(.jpeg) {
                settings = AVCapturePhotoSettings(format: [AVVideoCodecKey: AVVideoCodecType.jpeg])
            } else {
                settings = AVCapturePhotoSettings()
            }
            if #available(iOS 16.0, *) {
                settings.maxPhotoDimensions = self.photoOutput.maxPhotoDimensions
            } else {
                settings.isHighResolutionPhotoEnabled = true
            }
            self.photoOutput.capturePhoto(with: settings, delegate: self)
        }
    }

    func photoOutput(_ output: AVCapturePhotoOutput, didFinishProcessingPhoto photo: AVCapturePhoto, error: Error?) {
        guard error == nil, let data = photo.fileDataRepresentation(), let img = UIImage(data: data) else {
            DispatchQueue.main.async {
                self.busy = false
                self.toast("Chụp không thành công, hãy thử lại")
            }
            return
        }
        DispatchQueue.global(qos: .userInitiated).async {
            let norm = ImageOps.normalized(img, maxDim: ImageOps.maxSrc)
            DispatchQueue.main.async { self.onCaptured(norm) }
        }
    }

    private func onCaptured(_ img: UIImage) {
        guard view.window != nil, presentedViewController == nil else {
            busy = false
            return
        }
        let crop = CropViewController(image: img, quad: nil, auto: true, mode: .capture)
        crop.onDone = { [weak self, weak crop] q, auto in
            guard let self = self else { return }
            crop?.dismiss(animated: false) {
                self.busy = true
                DispatchQueue.main.async {
                    self.busy = false
                    self.acceptPage(img, quad: q, auto: auto, fromCamera: true)
                }
            }
        }
        crop.onCancel = { [weak self, weak crop] in
            crop?.dismiss(animated: true)
            self?.busy = false
        }
        present(crop, animated: true) {
            self.busy = false
        }
    }

    // MARK: trang

    private func ensureDoc() -> Doc {
        if let d = doc { return d }
        let d = DocStore.shared.create(draft: true)
        doc = d
        newDoc = true
        return d
    }

    /// Thêm (hoặc thay) trang. Trả về false nếu màn hình đã đóng.
    @discardableResult
    private func acceptPage(_ img: UIImage, quad: [CGPoint]?, auto: Bool, fromCamera: Bool) -> Bool {
        do {
            let d = ensureDoc()
            if let rid = retakeId {
                guard d.index(of: rid) != nil else {
                    finish(nil)
                    return false
                }
                try DocStore.shared.replaceSource(d, pageId: rid, image: img, quad: quad, auto: auto)
                finish(d)
                return false
            }
            let p = try DocStore.shared.addPage(d, image: img, quad: quad, auto: auto)
            sessionPages.append(p.id)
            if fromCamera {
                if batch { refreshBottom() } else { openEditor() }
            }
            return true
        } catch {
            toast("Lỗi lưu ảnh: \(error.localizedDescription)")
            return false
        }
    }

    @objc private func openEditorTapped() { openEditor() }

    func openEditor() {
        guard let d = doc, !d.pages.isEmpty else { return }
        let first = sessionPages.first.flatMap { d.index(of: $0) } ?? 0
        let ed = EditorViewController(doc: d, context: .camera, startPage: first)
        ed.onDone = { [weak self] saved in
            guard let self = self else { return }
            if saved {
                self.finish(d)
            } else {
                self.navigationController?.popToViewController(self, animated: true)
                self.sessionPages = self.sessionPages.filter { d.index(of: $0) != nil }
                self.refreshBottom()
            }
        }
        navigationController?.pushViewController(ed, animated: true)
    }

    @objc private func undoLast() {
        guard let d = doc, !busy, let id = sessionPages.popLast() else { return }
        DocStore.shared.removePage(d, pageId: id)
        refreshBottom()
    }

    @objc private func tabTapped(_ b: UIButton) {
        let isBatch = b === tabBatch
        if batch == isBatch { return }
        if !sessionPages.isEmpty {
            toast("Hãy hoàn tất các trang đã chụp trước khi đổi chế độ")
            return
        }
        batch = isBatch
        UserDefaults.standard.set(batch, forKey: "batch")
        refreshBottom()
    }

    @objc private func pickImages() {
        if busy { return }
        var cfg = PHPickerConfiguration()
        cfg.filter = .images
        cfg.selectionLimit = retakeId == nil ? 0 : 1
        let picker = PHPickerViewController(configuration: cfg)
        picker.delegate = self
        present(picker, animated: true)
    }

    func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
        picker.dismiss(animated: true)
        guard !results.isEmpty else { return }
        busy = true
        ImageLoader.load(results) { [weak self] images in
            let failed = results.count - images.count
            DispatchQueue.global(qos: .userInitiated).async {
                let quads = images.map { img in img.cgImage.flatMap { ImageOps.detect($0) } }
                DispatchQueue.main.async {
                    guard let self = self else { return }
                    self.busy = false
                    if failed > 0 { self.toast("\(failed) ảnh không đọc được") }
                    var added = false
                    for (i, img) in images.enumerated() {
                        if !self.acceptPage(img, quad: quads[i], auto: true, fromCamera: false) { return }
                        added = true
                    }
                    if added {
                        if self.batch { self.refreshBottom() }
                        self.openEditor()
                    }
                }
            }
        }
    }

    private func finish(_ d: Doc?) {
        if let cb = onFinished { cb(d) } else { dismiss(animated: true) }
    }

    @objc private func attemptClose() {
        if busy { return }
        if sessionPages.isEmpty {
            if newDoc, let d = doc, d.pages.isEmpty { DocStore.shared.delete(d) }
            finish(nil)
            return
        }
        confirm("Bỏ các trang vừa chụp?", message: "\(sessionPages.count) trang vừa chụp sẽ bị xoá.", ok: "Bỏ") { [weak self] in
            guard let self = self, let d = self.doc else { return }
            if self.newDoc {
                DocStore.shared.delete(d)
            } else {
                for id in self.sessionPages { DocStore.shared.removePage(d, pageId: id) }
            }
            self.sessionPages.removeAll()
            self.finish(nil)
        }
    }
}

/// Lưới 3x3 hỗ trợ căn thẳng trang giấy.
final class GridView: UIView {
    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .clear
        isUserInteractionEnabled = false
        contentMode = .redraw
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    override func draw(_ rect: CGRect) {
        guard let c = UIGraphicsGetCurrentContext() else { return }
        c.setStrokeColor(UIColor(white: 1, alpha: 0.4).cgColor)
        c.setLineWidth(1)
        let w = bounds.width, h = bounds.height
        for i in 1...2 {
            let x = w * CGFloat(i) / 3, y = h * CGFloat(i) / 3
            c.move(to: CGPoint(x: x, y: 0))
            c.addLine(to: CGPoint(x: x, y: h))
            c.move(to: CGPoint(x: 0, y: y))
            c.addLine(to: CGPoint(x: w, y: y))
        }
        c.strokePath()
    }
}

/// Nút chụp tròn trắng viền xanh.
final class ShutterButton: UIControl {
    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .clear
        isAccessibilityElement = true
        accessibilityTraits = .button
        contentMode = .redraw
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    override var isHighlighted: Bool { didSet { setNeedsDisplay() } }
    override var isEnabled: Bool { didSet { alpha = isEnabled ? 1 : 0.5 } }

    override func draw(_ rect: CGRect) {
        guard let c = UIGraphicsGetCurrentContext() else { return }
        let ring: CGFloat = 4.5
        let r = min(bounds.width, bounds.height) / 2 - ring
        let cx = bounds.midX, cy = bounds.midY
        c.setStrokeColor(Theme.accent.cgColor)
        c.setLineWidth(ring)
        c.strokeEllipse(in: CGRect(x: cx - r, y: cy - r, width: 2 * r, height: 2 * r))
        let ir = r - (isHighlighted ? 9 : 6.5)
        c.setFillColor(UIColor.white.cgColor)
        c.fillEllipse(in: CGRect(x: cx - ir, y: cy - ir, width: 2 * ir, height: 2 * ir))
    }
}
