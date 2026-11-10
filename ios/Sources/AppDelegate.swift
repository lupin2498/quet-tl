import UIKit

@main
final class AppDelegate: UIResponder, UIApplicationDelegate {
    var window: UIWindow?

    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
        Theme.applyAppearance()
        // dọn bản nháp rỗng + khôi phục tài liệu đang quét dở (lần trước ứng dụng bị đóng giữa chừng)
        let recovered = DocStore.shared.startupMaintenance()
        let window = UIWindow(frame: UIScreen.main.bounds)
        window.overrideUserInterfaceStyle = .dark
        window.backgroundColor = Theme.bg
        let home = HomeViewController()
        if !SelfTest.enabled { home.recoveredCount = recovered }
        let nav = UINavigationController(rootViewController: home)
        window.rootViewController = nav
        window.makeKeyAndVisible()
        self.window = window
        SelfTest.runIfRequested(nav: nav)
        return true
    }

    /// Đang ở giữa một phiên quét / chỉnh sửa (đóng ngang sẽ làm mất các trang chưa lưu).
    private func sessionActive(_ nav: UINavigationController, _ home: HomeViewController) -> Bool {
        if home.isImporting { return true }
        if nav.viewControllers.contains(where: { $0 is EditorViewController }) { return true }
        if let p = nav.presentedViewController, !p.isBeingDismissed {
            // máy ảnh (bọc trong UINavigationController) hoặc màn hình cắt đang mở
            if p is UINavigationController || p is CropViewController { return true }
        }
        return false
    }

    /// Mở ảnh được chia sẻ vào ứng dụng ("Mở bằng Quét Tài Liệu").
    func application(_ app: UIApplication, open url: URL, options: [UIApplication.OpenURLOptionsKey: Any] = [:]) -> Bool {
        let access = url.startAccessingSecurityScopedResource()
        defer {
            if access { url.stopAccessingSecurityScopedResource() }
            // bản sao hệ thống đặt vào Documents/Inbox -> xoá sau khi đã đọc
            if url.path.contains("/Documents/Inbox/") { try? FileManager.default.removeItem(at: url) }
        }
        guard let nav = window?.rootViewController as? UINavigationController,
              let home = nav.viewControllers.first as? HomeViewController else { return false }
        if sessionActive(nav, home) {
            // không đóng ngang phiên đang mở (sẽ bỏ dở tài liệu chưa lưu): báo người dùng hoàn tất trước
            var top: UIViewController = nav
            while let p = top.presentedViewController, !p.isBeingDismissed { top = p }
            top.toast("Hãy hoàn tất thao tác đang mở trước khi nhập ảnh")
            return false
        }
        // giải mã có thu nhỏ (ảnh 48 MP không cần nạp nguyên cỡ vào bộ nhớ), đã xoay đúng chiều
        guard let img = ImageOps.thumbnail(url, maxPixel: ImageOps.maxSrc) else {
            home.toast("Không đọc được ảnh")
            return false
        }
        let start = {
            nav.popToRootViewController(animated: false)
            nav.view.layoutIfNeeded()
            // đợi màn hình chính vào lại cửa sổ rồi mới mở màn hình cắt
            DispatchQueue.main.async { home.importImages([img]) }
        }
        if nav.presentedViewController != nil {
            nav.dismiss(animated: false, completion: start)
        } else {
            start()
        }
        return true
    }
}
