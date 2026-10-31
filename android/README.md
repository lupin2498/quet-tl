# Quét Tài Liệu (bản sao chức năng quét kiểu CamScanner)

Ứng dụng Android quét giấy tờ: chụp → tự tìm mép trang → kéo tay cầm chỉnh vùng cắt → nắn thẳng phối cảnh theo đúng khổ giấy (A4/Letter) → làm rõ chữ → lưu/chia sẻ PDF, JPG.

## Cài đặt nhanh
Cài file `QuetTaiLieu-1.0.apk` lên điện thoại (Android 7.0 trở lên). Trên MIUI/HyperOS cần bật "Cài đặt ứng dụng không rõ nguồn gốc" cho trình quản lý tệp.

## Chức năng
- **Máy ảnh**: chế độ *Đơn* / *Hàng loạt*, đèn pin, HD, lưới căn, nhập ảnh từ thư viện, hoàn tác trang vừa chụp, ảnh thu nhỏ + số trang.
- **Cắt (căn chỉnh trang)**: tự động tìm 4 góc tờ giấy (biến đổi Hough có định hướng + chấm điểm cạnh + tinh chỉnh dưới-pixel); tay cầm ở 4 góc và giữa 4 cạnh, kính lúp khi kéo; nút *Tự động cắt* bật/tắt (tắt = lấy toàn bộ ảnh).
- **Nắn phẳng theo khổ giấy**: ước lượng tỷ lệ thật của tờ giấy từ phối cảnh (dùng tiêu cự camera), tự khớp về đúng A4/Letter khi gần đúng.
- **Chỉnh sửa**: Chụp lại, Xoay trái, Lọc (Gốc, Tăng cường, Làm sáng, Thang xám, Đen trắng; áp dụng cho tất cả trang), Cắt lại, So sánh (giữ để xem ảnh gốc), Xoá trang, đổi tên.
- **Tài liệu**: danh sách tìm kiếm được, Thêm trang, Sửa, Chia sẻ PDF/JPG, Lưu PDF (chọn nơi lưu), Lưu ảnh vào Thư viện, khổ PDF A4/Letter/Theo ảnh. Nhận ảnh chia sẻ từ ứng dụng khác.

## Cấu trúc mã
- `app/src/main/java/vn/quettailieu/scanner/core/` — xử lý ảnh Java thuần (không phụ thuộc Android): `DocDetector` (tìm mép trang), `Geom` (hình học, tỷ lệ khổ giấy, homography), `Enhancer` (bộ lọc làm rõ chữ), `PdfWriter` (ghi PDF nhúng JPEG).
- `app/src/main/java/vn/quettailieu/scanner/` — giao diện Android (Camera2, không dùng thư viện ngoài).
- `tools/desktop/` — chương trình kiểm thử thuật toán trên máy tính (`TestDetect`, `EvalSynth`).

## Build
- **Android Studio**: mở thư mục này, chờ Gradle đồng bộ, chọn *Build > Build APK(s)*. Bản build được ký bằng `keystore/quettailieu.jks` (mật khẩu `quettailieu`) để cài đè lên bản APK kèm theo.
- **Không cần Gradle** (Linux): `./build_apk.sh` (cần `aapt2`, `javac`, `d8` hoặc `dalvik-exchange`, `zipalign`, `apksigner`, `android.jar`).
