# Quét Tài Liệu

Ứng dụng quét giấy tờ kiểu CamScanner cho **Android** và **iOS**: tự tìm mép trang, kéo tay cầm chỉnh vùng cắt,
nắn thẳng theo đúng khổ giấy (A4/Letter), làm rõ chữ, xuất PDF/JPG.

- Android: thư mục [`android/`](android/) — xem [android/README.md](android/README.md).
- iOS: thư mục [`ios/`](ios/) — Swift/UIKit, iOS 15+. Bộ tìm mép `DocDetector.swift` là bản port từ Java
  (dùng chung thuật toán với Android), Vision làm dự phòng; dự án Xcode sinh bằng XcodeGen (`ios/project.yml`).
- Kiểm thử tự động: [`tests/`](tests/) và GitHub Actions (`.github/workflows/`).

## Cài đặt
- **Android**: cài file APK (Android 7.0+).
- **iOS**: file IPA chưa ký — cài bằng Sideloadly/AltStore (Apple ID cá nhân), eSign/Scarlet hoặc TrollStore.
