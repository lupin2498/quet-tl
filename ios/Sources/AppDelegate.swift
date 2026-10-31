import UIKit

@main
final class AppDelegate: UIResponder, UIApplicationDelegate {
    var window: UIWindow?

    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
        Theme.applyAppearance()
        DocStore.shared.cleanDrafts()
        let window = UIWindow(frame: UIScreen.main.bounds)
        window.overrideUserInterfaceStyle = .dark
        window.backgroundColor = Theme.bg
        let nav = UINavigationController(rootViewController: HomeViewController())
        window.rootViewController = nav
        window.makeKeyAndVisible()
        self.window = window
        SelfTest.runIfRequested(nav: nav)
        return true
    }

    /// Mở ảnh/PDF được chia sẻ vào ứng dụng ("Mở bằng Quét Tài Liệu").
    func application(_ app: UIApplication, open url: URL, options: [UIApplication.OpenURLOptionsKey: Any] = [:]) -> Bool {
        let access = url.startAccessingSecurityScopedResource()
        defer {
            if access { url.stopAccessingSecurityScopedResource() }
            // bản sao hệ thống đặt vào Documents/Inbox -> xoá sau khi đã đọc
            if url.path.contains("/Documents/Inbox/") { try? FileManager.default.removeItem(at: url) }
        }
        guard let data = try? Data(contentsOf: url), let img = UIImage(data: data),
              let nav = window?.rootViewController as? UINavigationController,
              let home = nav.viewControllers.first as? HomeViewController else { return false }
        nav.dismiss(animated: false)
        nav.popToRootViewController(animated: false)
        home.importImages([ImageOps.normalized(img, maxDim: ImageOps.maxSrc)])
        return true
    }
}
