# Quét Tài Liệu (bản sao chức năng quét kiểu CamScanner)

Ứng dụng Android quét giấy tờ: chụp → tự tìm mép trang → kéo tay cầm chỉnh vùng cắt → nắn thẳng phối cảnh theo đúng khổ giấy (A4/Letter) → làm rõ chữ → lưu/chia sẻ PDF, JPG.

## Cài đặt nhanh
Cài file `QuetTaiLieu-1.0.apk` lên điện thoại (Android 7.0 trở lên). Trên MIUI/HyperOS cần bật "Cài đặt ứng dụng không rõ nguồn gốc" cho trình quản lý tệp.

## Chức năng
- **Máy ảnh**: chế độ *Đơn* / *Hàng loạt*, đèn pin, HD, lưới căn, nhập ảnh từ thư viện, hoàn tác trang vừa chụp, ảnh thu nhỏ + số trang.
- **Cắt (căn chỉnh trang)**: tự động tìm 4 góc tờ giấy (biến đổi Hough có định hướng + chấm điểm cạnh + tinh chỉnh dưới-pixel); tay cầm ở 4 góc và giữa 4 cạnh, kính lúp khi kéo; nút *Tự động cắt* bật/tắt (tắt = lấy toàn bộ ảnh).
- **Nắn phẳng theo khổ giấy**: ước lượng tỷ lệ thật của tờ giấy từ phối cảnh (dùng tiêu cự camera), tự khớp về đúng A4/Letter khi gần đúng.
- **Chỉnh sửa** (thanh công cụ ba nhóm *Ảnh / Đánh dấu / Trang*): Cắt lại, Xoay trái, Chụp lại, So sánh (giữ để xem ảnh gốc), Xoá trang, đổi tên.
- **Bộ lọc**: Nguyên gốc, Làm sáng, Tăng cường, *Thần kỳ* (gỡ ngón tay giữ giấy, khử bóng, nền trắng, chữ sắc nét), *Làm phẳng* (giấy mấp mô / gấp nếp / nhàu: nắn thẳng dòng chữ và đường kẻ bảng, chỉ giữ chữ + bảng biểu + chữ ký / con dấu, xoá bóng mờ nếp gấp, vết tay, ngón tay giữ giấy), *Khử nhiễu*, *Không bóng*, *Ko viết tay* (xoá nét bút màu), Đen trắng, Thang xám; áp dụng cho tất cả trang; ✕ trả lại bộ lọc cũ. Khi trang có ngón tay giữ giấy, ứng dụng nhắc "Phát hiện ngón tay" kèm nút *Gỡ bỏ*.
- **Xóa thông minh**: Bút, Hình chữ nhật, Thần kỳ (tô sơ qua là xoá cả vật thể: vết bẩn, con dấu, chữ ký), Viết tay (xoá nét bút màu trên cả trang), Xóa văn bản (xoá chữ, giữ đường kẻ bảng); hoàn tác / làm lại / so sánh.
- **Đánh dấu**: Ký tên (bảng ký + thư viện chữ ký), Thêm văn bản, Brush, Khảm (che thông tin), Thêm hình mờ.
- **Trang**: Tiêu đề trang (dòng chữ lề trên + đánh số trang), Cắt dán (ghép 2/4/6/8 trang lên một tờ A4 thành tài liệu mới).
- Mọi chỉnh sửa lưu dạng thao tác trên ảnh gốc (không phá huỷ): cắt lại, đổi bộ lọc hay xoay sau đó vẫn giữ nét xoá, chữ ký, đánh dấu.
- **Phóng to khi chỉnh sửa**: chụm / dang hai ngón để phóng trang tới 6 lần (kéo một ngón để xem chỗ khác, chạm đúp để phóng nhanh / trở về); mức phóng giữ nguyên khi mở bảng bộ lọc và đổi bộ lọc.
- **Tài liệu**: danh sách tìm kiếm được, Thêm trang, Sửa, Chia sẻ PDF/JPG/Word, Lưu PDF (chọn nơi lưu), Lưu ảnh vào Thư viện, khổ giấy A4/Letter/Theo ảnh. Nhận ảnh chia sẻ từ ứng dụng khác.
- **Chuyển sang Word (.docx)**: nút *Word* ở màn hình tài liệu. Từng trang (ảnh đã nắn phẳng + lọc) được nhận dạng chữ ngay trên máy (ML Kit, mô hình đóng kèm, không cần mạng — đọc được tiếng Việt có dấu) rồi dựng lại đúng bố cục: mỗi dòng chữ đúng vị trí, cỡ, đậm / nghiêng / gạch chân / màu; bảng kẻ thành bảng Word thật (ô gộp, độ dày viền); con dấu, chữ ký, hình và những chỗ không đọc được giữ lại dưới dạng hình đúng chỗ; mỗi trang quét một trang Word đúng khổ giấy. Trang không đọc được chữ vào Word nguyên dạng ảnh. Lưu vào máy hoặc chia sẻ; có nút Huỷ khi đang chuyển.

## Cấu trúc mã
- `app/src/main/java/vn/quettailieu/scanner/core/` — xử lý ảnh Java thuần (không phụ thuộc Android): `DocDetector` (tìm mép trang), `Geom` (hình học, tỷ lệ khổ giấy, homography), `Enhancer` (bộ lọc làm rõ chữ), `Retouch` (gỡ ngón tay, xoá thông minh, khử nhiễu, làm nét, dọn mép), `Flatten` (bộ lọc Làm phẳng: làm sạch + nắn thẳng trang cong vênh), `PdfWriter` (ghi PDF nhúng JPEG), `DocLayout` + `DocxWriter` + `WordDoc` + `WordFont` (chuyển trang quét sang Word: dò bảng, đo chữ, dàn trang, ghi DOCX).
- `app/src/main/java/vn/quettailieu/scanner/` — giao diện Android (Camera2). Thư viện ngoài duy nhất là ML Kit Text Recognition (nhận dạng chữ cho tính năng chuyển sang Word); lớp nối `MlKitOcr` nằm riêng ở `app/src/mlkit/java`.
- `tools/desktop/` — chương trình kiểm thử thuật toán trên máy tính (`TestDetect`, `EvalSynth`, `EvalRetouch`, `EvalFlatten`, `EvalKeep`, `EvalWord`, `FuzzCore`, `DumpCore`).

## Build
- **Android Studio**: mở thư mục này, chờ Gradle đồng bộ, chọn *Build > Build APK(s)*. Bản build được ký bằng `keystore/quettailieu.jks` (mật khẩu `quettailieu`) để cài đè lên bản APK kèm theo.
- **Không cần Gradle** (Linux): `./build_apk.sh` (cần `aapt2`, `javac`, `d8` hoặc `dalvik-exchange`, `zipalign`, `apksigner`, `android.jar`). Bản dựng kiểu này không kèm ML Kit: chuyển sang Word vẫn chạy nhưng các trang vào Word dưới dạng ảnh (không nhận dạng chữ).
