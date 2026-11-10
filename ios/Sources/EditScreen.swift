import UIKit

/// Khung chung của hai màn hình chỉnh sửa trang ("Xóa thông minh", "Đánh dấu"):
/// vùng trang ở trên, hàng tuỳ chọn, hàng công cụ, thanh dưới ✕ · tên màn hình · ✓; nút hoàn tác / làm lại.
class EditScreenController: UIViewController {
    let content = UIView()
    let hintLabel = PaddingLabel()
    let undoBtn = EditScreenController.roundButton("arrow.uturn.backward", "Hoàn tác")
    let redoBtn = EditScreenController.roundButton("arrow.uturn.forward", "Làm lại")
    let optionsRow = UIStackView()
    let spinner = UIActivityIndicatorView(style: .large)
    private(set) var toolButtons = [UIButton]()
    private let optionsScroll = UIScrollView()
    private let toolsScroll = UIScrollView()
    private let toolsRow = UIStackView()
    /// Bề rộng từng nút công cụ: theo nhãn (toolNatural) cộng phần thừa của màn hình chia đều.
    private var toolWidths = [NSLayoutConstraint]()
    private var toolNatural = [CGFloat]()

    static func roundButton(_ symbol: String, _ label: String) -> UIButton {
        let b = UIButton(type: .system)
        b.setImage(Theme.symbol(symbol, size: 16, weight: .semibold), for: .normal)
        b.tintColor = .white
        b.backgroundColor = UIColor(white: 0.2, alpha: 0.6)
        b.layer.cornerRadius = 19
        b.accessibilityLabel = label
        b.translatesAutoresizingMaskIntoConstraints = false
        b.widthAnchor.constraint(equalToConstant: 38).isActive = true
        b.heightAnchor.constraint(equalToConstant: 38).isActive = true
        return b
    }

    /// Nút chữ bo tròn trong hàng tuỳ chọn.
    static func chip(_ title: String) -> UIButton {
        var cfg = UIButton.Configuration.filled()
        cfg.title = title
        cfg.baseBackgroundColor = Theme.card2
        cfg.baseForegroundColor = .white
        cfg.cornerStyle = .capsule
        cfg.contentInsets = NSDirectionalEdgeInsets(top: 7, leading: 12, bottom: 7, trailing: 12)
        cfg.titleTextAttributesTransformer = UIConfigurationTextAttributesTransformer { incoming in
            var out = incoming
            out.font = UIFont.systemFont(ofSize: 13)
            return out
        }
        let b = UIButton(configuration: cfg)
        b.accessibilityLabel = title
        return b
    }

    /// Chấm tròn chọn cỡ nét / màu.
    static func dot(diameter: CGFloat, color: UIColor, selected: Bool, label: String, box: CGFloat = 40) -> UIButton {
        let b = UIButton(type: .custom)
        b.accessibilityLabel = label
        b.translatesAutoresizingMaskIntoConstraints = false
        b.widthAnchor.constraint(equalToConstant: box).isActive = true
        b.heightAnchor.constraint(equalToConstant: 40).isActive = true
        let d = UIView()
        d.isUserInteractionEnabled = false
        d.backgroundColor = color
        d.layer.cornerRadius = diameter / 2
        d.layer.borderWidth = selected ? 3 : 1
        d.layer.borderColor = (selected ? Theme.accent : UIColor(rgb: 0x8A898E)).cgColor
        d.translatesAutoresizingMaskIntoConstraints = false
        b.addSubview(d)
        NSLayoutConstraint.activate([
            d.centerXAnchor.constraint(equalTo: b.centerXAnchor),
            d.centerYAnchor.constraint(equalTo: b.centerYAnchor),
            d.widthAnchor.constraint(equalToConstant: diameter),
            d.heightAnchor.constraint(equalToConstant: diameter)
        ])
        return b
    }

    /// Dựng khung màn hình. tools: (biểu tượng, nhãn) của từng công cụ.
    func buildChrome(title: String, tools: [(String, String)]) {
        view.backgroundColor = Theme.bgDark
        content.backgroundColor = Theme.bg
        content.clipsToBounds = true
        content.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(content)

        hintLabel.insets = UIEdgeInsets(top: 6, left: 12, bottom: 6, right: 12)
        hintLabel.font = UIFont.systemFont(ofSize: 13)
        hintLabel.textColor = .white
        hintLabel.textAlignment = .center
        hintLabel.numberOfLines = 2
        hintLabel.backgroundColor = UIColor(white: 0.2, alpha: 0.6)
        hintLabel.layer.cornerRadius = 12
        hintLabel.clipsToBounds = true
        hintLabel.isUserInteractionEnabled = false
        hintLabel.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(hintLabel)

        spinner.color = Theme.accent
        spinner.hidesWhenStopped = true
        spinner.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(spinner)

        undoBtn.addTarget(self, action: #selector(undoAction), for: .touchUpInside)
        redoBtn.addTarget(self, action: #selector(redoAction), for: .touchUpInside)
        view.addSubview(undoBtn)
        view.addSubview(redoBtn)

        optionsScroll.showsHorizontalScrollIndicator = false
        optionsScroll.backgroundColor = UIColor(rgb: 0x262528)
        optionsScroll.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(optionsScroll)
        optionsRow.axis = .horizontal
        optionsRow.spacing = 6
        optionsRow.alignment = .center
        optionsRow.translatesAutoresizingMaskIntoConstraints = false
        optionsScroll.addSubview(optionsRow)

        toolsScroll.showsHorizontalScrollIndicator = false
        toolsScroll.backgroundColor = Theme.card
        toolsScroll.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(toolsScroll)
        toolsRow.axis = .horizontal
        toolsRow.distribution = .fill
        toolsRow.translatesAutoresizingMaskIntoConstraints = false
        toolsScroll.addSubview(toolsRow)
        for (k, t) in tools.enumerated() {
            let b = Theme.toolButton(t.0, t.1)
            b.tag = k
            b.addTarget(self, action: #selector(toolAction(_:)), for: .touchUpInside)
            b.translatesAutoresizingMaskIntoConstraints = false
            toolsRow.addArrangedSubview(b)
            // mỗi nút rộng theo nhãn; layoutToolRow() chia đều phần thừa, không đủ chỗ thì cả hàng cuộn ngang
            let natural = Theme.toolWidth(t.1, minWidth: 56)
            let c = b.widthAnchor.constraint(equalToConstant: natural)
            c.isActive = true
            toolWidths.append(c)
            toolNatural.append(natural)
            toolButtons.append(b)
        }

        let bar = UIView()
        bar.backgroundColor = Theme.bgDark
        bar.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(bar)
        let cancel = Theme.iconButton("xmark", size: 18, label: "Hủy")
        cancel.addTarget(self, action: #selector(cancelAction), for: .touchUpInside)
        let apply = Theme.iconButton("checkmark", size: 19, tint: Theme.accent, label: "Áp dụng")
        apply.addTarget(self, action: #selector(applyAction), for: .touchUpInside)
        let titleLabel = Theme.label(title, size: 16, weight: .semibold)
        titleLabel.textAlignment = .center
        [cancel, apply, titleLabel].forEach {
            $0.translatesAutoresizingMaskIntoConstraints = false
            bar.addSubview($0)
        }

        let g = view.safeAreaLayoutGuide
        NSLayoutConstraint.activate([
            content.topAnchor.constraint(equalTo: g.topAnchor),
            content.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            content.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            content.bottomAnchor.constraint(equalTo: optionsScroll.topAnchor),

            hintLabel.topAnchor.constraint(equalTo: content.topAnchor, constant: 10),
            hintLabel.centerXAnchor.constraint(equalTo: content.centerXAnchor),
            hintLabel.widthAnchor.constraint(lessThanOrEqualTo: content.widthAnchor, constant: -32),

            spinner.centerXAnchor.constraint(equalTo: content.centerXAnchor),
            spinner.centerYAnchor.constraint(equalTo: content.centerYAnchor),

            undoBtn.leadingAnchor.constraint(equalTo: content.leadingAnchor, constant: 14),
            undoBtn.bottomAnchor.constraint(equalTo: content.bottomAnchor, constant: -10),
            redoBtn.leadingAnchor.constraint(equalTo: undoBtn.trailingAnchor, constant: 10),
            redoBtn.centerYAnchor.constraint(equalTo: undoBtn.centerYAnchor),

            optionsScroll.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            optionsScroll.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            optionsScroll.heightAnchor.constraint(equalToConstant: 48),
            optionsScroll.bottomAnchor.constraint(equalTo: toolsScroll.topAnchor),
            optionsRow.topAnchor.constraint(equalTo: optionsScroll.contentLayoutGuide.topAnchor),
            optionsRow.bottomAnchor.constraint(equalTo: optionsScroll.contentLayoutGuide.bottomAnchor),
            optionsRow.leadingAnchor.constraint(equalTo: optionsScroll.contentLayoutGuide.leadingAnchor, constant: 10),
            optionsRow.trailingAnchor.constraint(equalTo: optionsScroll.contentLayoutGuide.trailingAnchor, constant: -10),
            optionsRow.heightAnchor.constraint(equalTo: optionsScroll.frameLayoutGuide.heightAnchor),

            toolsScroll.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            toolsScroll.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            toolsScroll.heightAnchor.constraint(equalToConstant: 62),
            toolsScroll.bottomAnchor.constraint(equalTo: bar.topAnchor),
            toolsRow.topAnchor.constraint(equalTo: toolsScroll.contentLayoutGuide.topAnchor),
            toolsRow.bottomAnchor.constraint(equalTo: toolsScroll.contentLayoutGuide.bottomAnchor),
            toolsRow.leadingAnchor.constraint(equalTo: toolsScroll.contentLayoutGuide.leadingAnchor),
            toolsRow.trailingAnchor.constraint(equalTo: toolsScroll.contentLayoutGuide.trailingAnchor),
            toolsRow.heightAnchor.constraint(equalTo: toolsScroll.frameLayoutGuide.heightAnchor),

            bar.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            bar.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            bar.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            bar.topAnchor.constraint(equalTo: g.bottomAnchor, constant: -50),
            cancel.leadingAnchor.constraint(equalTo: bar.leadingAnchor, constant: 6),
            cancel.topAnchor.constraint(equalTo: bar.topAnchor, constant: 3),
            cancel.widthAnchor.constraint(equalToConstant: 48),
            cancel.heightAnchor.constraint(equalToConstant: 44),
            apply.trailingAnchor.constraint(equalTo: bar.trailingAnchor, constant: -6),
            apply.centerYAnchor.constraint(equalTo: cancel.centerYAnchor),
            apply.widthAnchor.constraint(equalToConstant: 48),
            apply.heightAnchor.constraint(equalToConstant: 44),
            titleLabel.centerXAnchor.constraint(equalTo: bar.centerXAnchor),
            titleLabel.centerYAnchor.constraint(equalTo: cancel.centerYAnchor)
        ])
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        layoutToolRow()
    }

    /// Hàng công cụ trải hết bề ngang màn hình: mỗi nút rộng theo nhãn, phần thừa chia đều cho các nút
    /// (nhãn dài như "Thêm hình mờ" không bị đẩy ra ngoài mép); màn hình hẹp quá thì cả hàng cuộn ngang.
    private func layoutToolRow() {
        let avail = toolsScroll.bounds.width
        guard avail > 0, !toolNatural.isEmpty else { return }
        let sum = toolNatural.reduce(0, +)
        let extra = sum < avail ? (avail - sum) / CGFloat(toolNatural.count) : 0
        for (k, c) in toolWidths.enumerated() {
            let w = toolNatural[k] + extra
            if abs(c.constant - w) > 0.25 { c.constant = w }
        }
    }

    /// Đặt vùng trang vào khung (dưới lời nhắc và các nút nổi).
    func installCanvas(_ canvas: UIView) {
        canvas.translatesAutoresizingMaskIntoConstraints = false
        content.addSubview(canvas)
        NSLayoutConstraint.activate([
            canvas.topAnchor.constraint(equalTo: content.topAnchor),
            canvas.bottomAnchor.constraint(equalTo: content.bottomAnchor),
            canvas.leadingAnchor.constraint(equalTo: content.leadingAnchor),
            canvas.trailingAnchor.constraint(equalTo: content.trailingAnchor)
        ])
    }

    func setSelectedTool(_ index: Int) {
        for (k, b) in toolButtons.enumerated() {
            b.configuration?.baseForegroundColor = k == index ? Theme.accent : .white
            b.accessibilityTraits = k == index ? [.button, .selected] : [.button]
        }
    }

    func setUndo(_ canUndo: Bool, _ canRedo: Bool) {
        undoBtn.alpha = canUndo ? 1 : 0.35
        redoBtn.alpha = canRedo ? 1 : 0.35
    }

    func clearOptions() {
        for v in optionsRow.arrangedSubviews {
            optionsRow.removeArrangedSubview(v)
            v.removeFromSuperview()
        }
    }

    @objc private func toolAction(_ b: UIButton) { toolTapped(b.tag) }
    @objc private func undoAction() { undoTapped() }
    @objc private func redoAction() { redoTapped() }
    @objc private func cancelAction() { cancelTapped() }
    @objc private func applyAction() { applyTapped() }

    func toolTapped(_ index: Int) {}
    func undoTapped() {}
    func redoTapped() {}
    func cancelTapped() {}
    func applyTapped() {}
}
