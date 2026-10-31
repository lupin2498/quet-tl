# iOS CI — run 8 (729b234)

- Xcode: Xcode 16.4
- Simulator: iPhone Air (6C2153FE-DEAE-4712-8816-98E12A7AFEE0) (Shutdown) 
- IPA: 355399 bytes
- Crash logs: 0

```
Quét Tài Liệu 1.1 (2) — iOS 26.2, iPhone
ảnh màn hình: 01_home_empty.png (HomeViewController)
-- thuật toán tìm mép trên ảnh tổng hợp
   PASS doc_1_wood err=0.0005 (1.0px) 86ms nguồn=docdetector | DocDetector err=0.0005 điểm=0.661 biên=0 48ms | Vision err=0.1779 tin cậy=0.00
   PASS doc_2_dark err=0.0005 (1.0px) 77ms nguồn=docdetector | DocDetector err=0.0005 điểm=0.848 biên=0 34ms | Vision err=0.5119 tin cậy=0.97
   PASS doc_3_gray err=0.0005 (1.0px) 48ms nguồn=docdetector | DocDetector err=0.0005 điểm=0.803 biên=0 36ms | Vision err=0.5164 tin cậy=0.83
   PASS doc_4_beige err=0.0005 (1.0px) 51ms nguồn=docdetector | DocDetector err=0.0005 điểm=0.797 biên=0 45ms | Vision err=0.4628 tin cậy=0.99
   PASS doc_5_blue err=0.0005 (0.9px) 63ms nguồn=docdetector | DocDetector err=0.0005 điểm=0.813 biên=0 44ms | Vision err=0.5275 tin cậy=0.95
   PASS doc_6_clutter err=0.0005 (1.0px) 72ms nguồn=docdetector | DocDetector err=0.0005 điểm=0.834 biên=0 67ms | Vision err=0.5463 tin cậy=0.99
PASS DocDetector (Swift) trùng bản Java: 6/6 ảnh sai số <1% đường chéo
PASS tìm mép (DocDetector + Vision) 6/6 ảnh sai số <1% đường chéo
-- dựng tài liệu + PDF
PASS nắn phẳng với góc thật: 804x1137, tỷ lệ 0.707
PASS nội dung sau nắn phẳng: góc sáng 215, 137 điểm chữ
   doc_1_wood: 1200x1600 góc=(225,287) (971,276) (1016,1412) (213,1424)
   doc_2_dark: 1200x1600 góc=(218,191) (968,232) (945,1412) (113,1367)
   doc_6_clutter: 1200x1600 góc=(125,280) (902,141) (1123,1283) (313,1428)
PASS dựng 3 trang
PASS trang 1: 804x1137, tỷ lệ 0.707 (bản Java: 0.707)
PASS trang 2: 834x1252, tỷ lệ 0.666 (bản Java: 0.666)
PASS trang 3: 822x1163, tỷ lệ 0.707 (bản Java: 0.707)
PASS PDF 3 trang: 562 KB, 3 trang
-- tải lại danh sách
ảnh màn hình: 02_home_list.png (HomeViewController)
-- mở tài liệu
ảnh màn hình: 03_document.png (DocumentViewController)
-- mở trình chỉnh sửa
ảnh màn hình: 04_editor.png (EditorViewController)
-- mở bảng lọc
ảnh màn hình: 05_filters.png (EditorViewController)
-- lọc Đen trắng
ảnh màn hình: 06_filter_bw.png (EditorViewController)
-- kiểm tra bộ lọc
PASS trang 1 dùng bộ lọc Đen trắng
-- xoay trái
ảnh màn hình: 07_rotated.png (EditorViewController)
-- kiểm tra xoay
PASS trang 1 xoay 270°
-- đóng bảng lọc + mở màn hình cắt
ảnh màn hình: 08_crop_edit.png (CropViewController)
-- tắt Tự động cắt
ảnh màn hình: 08b_crop_auto_off.png (CropViewController)
-- kiểm tra tắt
PASS Tự động cắt: tắt -> khung toàn ảnh
-- bật lại Tự động cắt
-- kéo góc trên-trái
ảnh màn hình: 08c_crop_drag_loupe.png (CropViewController)
-- kiểm tra kéo tay
PASS kéo tay cầm -> tự tắt Tự động cắt
-- xác nhận vùng cắt
ảnh màn hình: 09_editor_after_crop.png (EditorViewController)
-- giữ So sánh
ảnh màn hình: 09b_compare.png (EditorViewController)
-- thả So sánh
-- sang trang 2
ảnh màn hình: 10_editor_page2.png (EditorViewController)
-- chụp lại trang 2
ảnh màn hình: 10b_camera_retake.png (CameraViewController)
-- chụp (chụp lại)
-- Tiếp tục (chụp lại)
ảnh màn hình: 10c_after_retake.png (EditorViewController)
-- kiểm tra chụp lại
PASS chụp lại thay ảnh trang 2, vẫn 3 trang
-- lưu tài liệu
ảnh màn hình: 11_document_after_edit.png (DocumentViewController)
-- Thêm trang
-- chụp (thêm trang)
-- Tiếp tục (thêm trang)
ảnh màn hình: 11b_editor_add_page.png (EditorViewController)
-- lưu (thêm trang)
ảnh màn hình: 11c_document_4_pages.png (DocumentViewController)
-- kiểm tra thêm trang
PASS Thêm trang: tài liệu có 4 trang
-- kiểm tra PDF sau chỉnh sửa
PASS PDF 4 trang: 783 KB, 4 trang
-- chia sẻ PDF
ảnh màn hình: 12_share_pdf.png (UIActivityViewController)
-- đóng bảng chia sẻ
-- về màn hình chính
-- mở máy ảnh (Đơn)
ảnh màn hình: 13_camera_single.png (CameraViewController)
-- chụp
ảnh màn hình: 14_crop_capture.png (CropViewController)
-- Tiếp tục
ảnh màn hình: 15_editor_from_camera.png (EditorViewController)
-- lưu
ảnh màn hình: 16_document_new.png (DocumentViewController)
-- kiểm tra tài liệu mới
PASS tài liệu chụp Đơn có 1 trang
PASS tài liệu chụp Đơn đã lưu
-- về màn hình chính
-- mở máy ảnh (Hàng loạt)
-- chụp 1
-- Tiếp tục 1
ảnh màn hình: 17_camera_batch_1.png (CameraViewController)
-- chụp 2
-- Tiếp tục 2
ảnh màn hình: 18_camera_batch_2.png (CameraViewController)
-- xem các trang đã chụp
ảnh màn hình: 19_editor_batch.png (EditorViewController)
-- lưu
ảnh màn hình: 20_document_batch.png (DocumentViewController)
-- kiểm tra tài liệu hàng loạt
PASS tài liệu Hàng loạt có 2 trang
PASS PDF 2 trang: 382 KB, 2 trang
-- chọn khổ giấy PDF
ảnh màn hình: 21_pdf_size.png (UIAlertController)
-- đóng
-- về màn hình chính
-- nhập 1 ảnh
ảnh màn hình: 22_import_crop.png (CropViewController)
-- Xong (nhập ảnh)
ảnh màn hình: 23_import_editor.png (EditorViewController)
-- lưu (nhập ảnh)
ảnh màn hình: 24_import_document.png (DocumentViewController)
-- về màn hình chính
-- nhập nhiều ảnh
ảnh màn hình: 25_import_multi_editor.png (EditorViewController)
-- kiểm tra nhập nhiều ảnh
PASS nhập nhiều ảnh: 3 trang
-- lưu (nhập nhiều ảnh)
ảnh màn hình: 26_import_multi_document.png (DocumentViewController)
-- về màn hình chính
-- mở tài liệu 2 trang
-- đổi tên
ảnh màn hình: 28_renamed.png (DocumentViewController)
-- kiểm tra đổi tên
PASS đổi tên được lưu
-- mở trình chỉnh sửa để xoá trang
-- xoá trang 1
ảnh màn hình: 29_page_deleted.png (EditorViewController)
-- kiểm tra xoá trang
PASS xoá trang: còn 1 trang
-- xoá tài liệu
-- tải lại
ảnh màn hình: 30_home_final.png (HomeViewController)
-- kiểm tra danh sách
PASS xoá tài liệu: còn 4 tài liệu (4)
-- nhập 12 ảnh lớn
-- thời gian xử lý
   nhập + xử lý 12 ảnh 12 MP: 29.8s
ảnh màn hình: 31_stress_editor.png (EditorViewController)
-- lưu (12 ảnh)
ảnh màn hình: 32_stress_document.png (DocumentViewController)
-- kiểm tra 12 trang
PASS nhập 12 ảnh lớn: tài liệu 12 trang
PASS PDF 12 trang: 6897 KB, 12 trang
-- về màn hình chính
-- kiểm tra danh sách cuối
PASS danh sách cuối: 5 tài liệu (5)
KẾT QUẢ: 27 đạt, 0 lỗi
```
