import UIKit

/// Màu sắc và tiện ích giao diện dùng chung (giống bản Android).
enum Theme {
    static let accent = UIColor(rgb: 0x00B596)
    static let bg = UIColor(rgb: 0x201F22)
    static let bgDark = UIColor(rgb: 0x1B1A1D)
    static let card = UIColor(rgb: 0x2C2B2E)
    static let card2 = UIColor(rgb: 0x3A393D)
    static let text = UIColor.white
    static let text2 = UIColor(rgb: 0xA6A5AA)
    static let red = UIColor(rgb: 0xFF5B4F)
    static let badge = UIColor(rgb: 0xFF6A55)
    /// Độ dài tối đa của tên tài liệu.
    static let maxTitle = 120

    static func applyAppearance() {
        let nav = UINavigationBarAppearance()
        nav.configureWithOpaqueBackground()
        nav.backgroundColor = bgDark
        nav.shadowColor = .clear
        nav.titleTextAttributes = [.foregroundColor: UIColor.white, .font: UIFont.systemFont(ofSize: 17, weight: .semibold)]
        UINavigationBar.appearance().standardAppearance = nav
        UINavigationBar.appearance().scrollEdgeAppearance = nav
        UINavigationBar.appearance().compactAppearance = nav
        UINavigationBar.appearance().tintColor = .white
    }

    static func symbol(_ name: String, size: CGFloat = 20, weight: UIImage.SymbolWeight = .regular) -> UIImage? {
        return UIImage(systemName: name, withConfiguration: UIImage.SymbolConfiguration(pointSize: size, weight: weight))
    }

    /// Cỡ chữ nhãn của nút công cụ.
    static let toolFont = UIFont.systemFont(ofSize: 11.5)

    /// Bề rộng cần cho một nút công cụ trong hàng cuộn ngang: theo nhãn (một dòng) cộng lề, không hẹp hơn `minWidth`.
    /// Đặt bề rộng tường minh thay vì dựa vào kích thước nội tại của nút để mọi máy dàn giống nhau.
    static func toolWidth(_ title: String, minWidth: CGFloat) -> CGFloat {
        let w = (title as NSString).size(withAttributes: [.font: toolFont]).width
        return max(minWidth, ceil(w) + 14)
    }

    /// Nút trên thanh công cụ dưới: biểu tượng + nhãn nhỏ.
    static func toolButton(_ symbolName: String, _ title: String) -> UIButton {
        var cfg = UIButton.Configuration.plain()
        cfg.image = symbol(symbolName, size: 19)
        cfg.title = title
        cfg.imagePlacement = .top
        cfg.imagePadding = 5
        cfg.baseForegroundColor = .white
        cfg.contentInsets = NSDirectionalEdgeInsets(top: 6, leading: 2, bottom: 6, trailing: 2)
        cfg.titleLineBreakMode = .byClipping
        cfg.titleTextAttributesTransformer = UIConfigurationTextAttributesTransformer { incoming in
            var out = incoming
            out.font = Theme.toolFont
            return out
        }
        let b = UIButton(configuration: cfg)
        b.accessibilityLabel = title
        return b
    }

    static func iconButton(_ symbolName: String, size: CGFloat = 20, tint: UIColor = .white, label: String) -> UIButton {
        let b = UIButton(type: .system)
        b.setImage(symbol(symbolName, size: size), for: .normal)
        b.tintColor = tint
        b.accessibilityLabel = label
        return b
    }

    static func primaryButton(_ title: String) -> UIButton {
        var cfg = UIButton.Configuration.filled()
        cfg.title = title
        cfg.baseBackgroundColor = accent
        cfg.baseForegroundColor = .white
        cfg.cornerStyle = .medium
        cfg.contentInsets = NSDirectionalEdgeInsets(top: 10, leading: 22, bottom: 10, trailing: 22)
        cfg.titleTextAttributesTransformer = UIConfigurationTextAttributesTransformer { incoming in
            var out = incoming
            out.font = UIFont.systemFont(ofSize: 16, weight: .semibold)
            return out
        }
        return UIButton(configuration: cfg)
    }

    static func textButton(_ title: String, color: UIColor = accent) -> UIButton {
        var cfg = UIButton.Configuration.plain()
        cfg.title = title
        cfg.baseForegroundColor = color
        cfg.titleTextAttributesTransformer = UIConfigurationTextAttributesTransformer { incoming in
            var out = incoming
            out.font = UIFont.systemFont(ofSize: 16, weight: .semibold)
            return out
        }
        return UIButton(configuration: cfg)
    }

    static func label(_ text: String, size: CGFloat, color: UIColor = .white, weight: UIFont.Weight = .regular) -> UILabel {
        let l = UILabel()
        l.text = text
        l.font = UIFont.systemFont(ofSize: size, weight: weight)
        l.textColor = color
        return l
    }

    static func dateString(_ d: Date) -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "vi_VN")
        f.dateFormat = "dd/MM/yyyy HH:mm"
        return f.string(from: d)
    }

    /// Tên tài liệu hợp lệ từ chuỗi người dùng nhập: bỏ khoảng trắng thừa và xuống dòng, tối đa `maxTitle` ký tự;
    /// rỗng thì giữ tên cũ.
    static func cleanTitle(_ raw: String, fallback: String) -> String {
        let oneLine = raw.components(separatedBy: .newlines).joined(separator: " ")
        let v = String(oneLine.trimmingCharacters(in: .whitespacesAndNewlines).prefix(maxTitle))
            .trimmingCharacters(in: .whitespacesAndNewlines)
        return v.isEmpty ? fallback : v
    }

    static func newTitle(_ d: Date) -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "dd-MM-yyyy HH.mm"
        return "Scan " + f.string(from: d)
    }
}

extension UIColor {
    convenience init(rgb: UInt32, alpha: CGFloat = 1) {
        self.init(red: CGFloat((rgb >> 16) & 0xFF) / 255.0,
                  green: CGFloat((rgb >> 8) & 0xFF) / 255.0,
                  blue: CGFloat(rgb & 0xFF) / 255.0,
                  alpha: alpha)
    }
}

extension UIViewController {
    /// Thông báo ngắn kiểu Toast.
    func toast(_ message: String) {
        let windowView: UIView? = view.window
        let host: UIView = windowView ?? view
        let label = PaddingLabel()
        label.text = message
        label.numberOfLines = 0
        label.textAlignment = .center
        label.textColor = .white
        label.font = UIFont.systemFont(ofSize: 14)
        label.backgroundColor = UIColor(white: 0.12, alpha: 0.92)
        label.layer.cornerRadius = 10
        label.clipsToBounds = true
        label.alpha = 0
        label.translatesAutoresizingMaskIntoConstraints = false
        host.addSubview(label)
        NSLayoutConstraint.activate([
            label.centerXAnchor.constraint(equalTo: host.centerXAnchor),
            label.bottomAnchor.constraint(equalTo: host.safeAreaLayoutGuide.bottomAnchor, constant: -110),
            label.widthAnchor.constraint(lessThanOrEqualTo: host.widthAnchor, constant: -48)
        ])
        UIView.animate(withDuration: 0.2) { label.alpha = 1 }
        UIView.animate(withDuration: 0.3, delay: 2.2, options: [], animations: { label.alpha = 0 }) { _ in
            label.removeFromSuperview()
        }
    }

    func confirm(_ title: String, message: String?, ok: String, destructive: Bool = true, action: @escaping () -> Void) {
        let a = UIAlertController(title: title, message: message, preferredStyle: .alert)
        a.addAction(UIAlertAction(title: "Hủy", style: .cancel))
        a.addAction(UIAlertAction(title: ok, style: destructive ? .destructive : .default) { _ in action() })
        present(a, animated: true)
    }

    func prompt(_ title: String, initial: String, done: @escaping (String) -> Void) {
        let a = UIAlertController(title: title, message: nil, preferredStyle: .alert)
        a.addTextField { tf in
            tf.text = String(initial.prefix(Theme.maxTitle))
            tf.clearButtonMode = .whileEditing
        }
        a.addAction(UIAlertAction(title: "Hủy", style: .cancel))
        a.addAction(UIAlertAction(title: "OK", style: .default) { _ in
            done(Theme.cleanTitle(a.textFields?.first?.text ?? "", fallback: initial))
        })
        present(a, animated: true)
    }
}

final class PaddingLabel: UILabel {
    var insets = UIEdgeInsets(top: 10, left: 16, bottom: 10, right: 16)

    override func drawText(in rect: CGRect) {
        super.drawText(in: rect.inset(by: insets))
    }

    override var intrinsicContentSize: CGSize {
        let s = super.intrinsicContentSize
        return CGSize(width: s.width + insets.left + insets.right, height: s.height + insets.top + insets.bottom)
    }
}

/// Màn hình chờ khi đang xử lý.
final class BusyOverlay {
    private let view = UIView()
    private let spinner = UIActivityIndicatorView(style: .large)
    private let label = UILabel()

    func show(in host: UIView, text: String) {
        view.backgroundColor = UIColor(white: 0, alpha: 0.55)
        view.frame = host.bounds
        view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        spinner.color = Theme.accent
        spinner.startAnimating()
        label.text = text
        label.textColor = .white
        label.font = UIFont.systemFont(ofSize: 15)
        label.textAlignment = .center
        let stack = UIStackView(arrangedSubviews: [spinner, label])
        stack.axis = .vertical
        stack.spacing = 12
        stack.translatesAutoresizingMaskIntoConstraints = false
        view.subviews.forEach { $0.removeFromSuperview() }
        view.addSubview(stack)
        NSLayoutConstraint.activate([
            stack.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            stack.centerYAnchor.constraint(equalTo: view.centerYAnchor)
        ])
        host.addSubview(view)
    }

    func hide() {
        view.removeFromSuperview()
    }
}
