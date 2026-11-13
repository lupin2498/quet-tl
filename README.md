# Quét Tài Liệu

Ứng dụng quét giấy tờ kiểu CamScanner cho **Android** và **iOS**: tự tìm mép trang, kéo tay cầm chỉnh vùng cắt,
nắn thẳng theo đúng khổ giấy (A4/Letter), làm rõ chữ, xuất PDF/JPG. Từ bản 1.3 có thêm phần làm đẹp trang và chỉnh sửa:
bộ lọc Thần kỳ (gỡ ngón tay giữ giấy, khử bóng), Khử nhiễu, Không bóng, Ko viết tay; Xóa thông minh; Đánh dấu (ký tên,
thêm văn bản, brush, khảm, hình mờ); Tiêu đề trang; Cắt dán nhiều trang lên một tờ A4. Bản 1.4 thêm bộ lọc **Làm phẳng**
cho giấy mấp mô / gấp nếp / nhàu: nắn thẳng lại dòng chữ và đường kẻ bảng bị uốn lượn, chỉ giữ nội dung + bảng biểu, xoá bóng mờ
nếp gấp, vết tay, ngón tay giữ giấy.

- Android: thư mục [`android/`](android/) — xem [android/README.md](android/README.md).
- iOS: thư mục [`ios/`](ios/) — Swift/UIKit, iOS 15+. Bộ tìm mép `DocDetector.swift` là bản port từ Java
  (dùng chung thuật toán với Android), Vision làm dự phòng; dự án Xcode sinh bằng XcodeGen (`ios/project.yml`).
- Kiểm thử tự động: [`tests/`](tests/) và GitHub Actions (`.github/workflows/`).

## Cài đặt
- **Android**: cài file APK (Android 7.0+).
- **iOS**: file IPA chưa ký — cài bằng Sideloadly/AltStore (Apple ID cá nhân), eSign/Scarlet hoặc TrollStore.
