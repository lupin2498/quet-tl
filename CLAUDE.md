# Quét Tài Liệu — hướng dẫn cho Claude Code

Ứng dụng quét giấy tờ kiểu CamScanner: chụp → tự tìm mép trang → kéo tay cầm chỉnh vùng cắt →
nắn phẳng theo khổ giấy (A4/Letter) → bộ lọc làm rõ chữ (có "Làm phẳng" cho giấy mấp mô / gấp nếp: nắn thẳng dòng chữ,
bảng biểu và chỉ giữ nội dung) → chỉnh sửa (xoá thông minh, đánh dấu, tiêu đề trang, cắt dán) → lưu/chia sẻ PDF, JPG.
Giao diện tiếng Việt.

## Cấu trúc
- `android/` — dự án Android Studio (Java, không thư viện ngoài, minSdk 24, targetSdk 34).
  - `app/src/main/java/vn/quettailieu/scanner/core/` — thuật toán Java thuần, KHÔNG phụ thuộc Android
    (`DocDetector` tìm mép, `Geom` hình học + tỷ lệ khổ giấy, `Enhancer` bộ lọc, `Retouch` gỡ ngón tay / xoá thông minh /
    khử nhiễu / làm nét / dọn mép, `Flatten` bộ lọc "Làm phẳng": làm sạch (chỉ giữ nét mực, giữ + làm rõ lưới bảng) rồi dò
    dòng chữ / đường kẻ bảng bị uốn lượn, khớp trường dịch chuyển trơn (bình phương tối thiểu trên lưới, gradient liên hợp)
    và nắn lại ảnh — kích thước ảnh giữ nguyên, `PdfWriter`).
  - Các Activity (đều kế thừa `BaseActivity`: chặn mở trùng màn hình khi chạm đúp, `runWhenResumed`): `MainActivity`
    (trang chủ), `CameraActivity` (Camera2, Đơn/Hàng loạt), `CropActivity` + `CropView` (chỉnh 4 góc, kính lúp),
    `EditorActivity` + `PageStripView` (thanh công cụ ba nhóm Ảnh / Đánh dấu / Trang; lọc, xoay, cắt lại, so sánh,
    lời nhắc ngón tay, tiêu đề trang, cắt dán), `EraseActivity` + `EraseView` (Xóa thông minh), `MarkupActivity` +
    `MarkView` (Đánh dấu; `MarkRender` vẽ chung cho màn hình chỉnh sửa và ảnh kết quả), `DocumentActivity`.
  - Chỉnh sửa trang KHÔNG phá huỷ: `Page.erase` / `Page.marks` (JSON chuẩn tắc trong `Edits`) + `Doc.header`; `Processor.render`
    dựng lại theo thứ tự nắn phẳng → dò ngón tay → xoá → lọc → đánh dấu → xoay → tiêu đề. Toạ độ chuẩn hoá theo ảnh đã nắn
    phẳng (chưa xoay); cắt lại thì `Edits.remap` dời theo vùng cắt mới. Tên ảnh kết quả = băm của mọi tham số.
    Bộ lọc "Làm phẳng" dời điểm ảnh: nét xoá lưu theo ảnh TRƯỚC khi lọc nên màn hình Xóa thông minh xem trước bộ lọc với
    `geometric = false` (chỉ làm sạch, không nắn); đánh dấu vẽ SAU bộ lọc trên ảnh đã nắn. Trường nắn phụ thuộc độ phân
    giải nên nền của màn hình Đánh dấu (`Processor.markupBase` / `ImageOps.markupBase`) lọc ảnh nắn phẳng ĐẦY ĐỦ như ảnh kết
    quả rồi mới thu nhỏ (`Enhancer.movesPixels`).
  - `DocStore.open()` trả về MỘT đối tượng `Doc` dùng chung cho mọi màn hình đang mở tài liệu đó (không nạp bản sao
    riêng rồi ghi đè nhau); tài liệu đã xoá thì `save()` bỏ qua. Bản nháp bị bỏ dở được khôi phục khi mở lại ứng dụng.
  - `tools/desktop/` — chạy thuật toán trên JVM: `TestDetect`, `EvalSynth` (so với toạ độ thật), `EvalRetouch` (gỡ ngón tay,
    xoá thông minh, bộ lọc mới trên trang tổng hợp), `EvalFlatten` (Làm phẳng: độ thẳng / độ sạch / giữ nội dung trên trang
    nhàu tổng hợp có đáp án hình học), `EvalKeep` (Làm phẳng giữ dấu thanh / chấm / đường kẻ mảnh trên ảnh chụp tài liệu
    phẳng cạnh dài 500-1754 điểm ảnh, không nắn méo trang phẳng tổng hợp nhiều cỡ), `FuzzCore` (kiểm thử độ bền), `DumpCore`
    (xuất kết quả để đối chiếu với bản Swift).
- `ios/` — bản iOS (Swift/UIKit, CoreImage), dự án sinh bằng XcodeGen (`ios/project.yml`).
  - `ios/Sources/DocDetector.swift`, `Enhancer.swift`, `Retouch.swift`, `Flatten.swift` port 1-1 từ bản Java (sửa thuật toán
    thì sửa cả hai; CI so từng điểm ảnh giữa hai bản); Vision chỉ là dự phòng khi tìm mép.
  - Giao diện chỉnh sửa: `EditorViewController`, `EraseViewController`, `MarkupViewController` (chung khung `EditScreen.swift`,
    vùng vẽ `PageCanvasView`), `MarkRender.swift`, `Collage.swift`, mô hình `Edits.swift` (`EraseOp`, `Mark`, `PageHeader`).
  - `Enhancer.swift`, `Retouch.swift`, `Flatten.swift`, `PdfWriter.swift`, `DocDetector.swift` chỉ dùng Foundation -> kiểm thử nhanh trên Linux bằng
    `ios/Tests/linux/main.swift` (job `algo-linux`, kết quả ở nhánh `ci/ios-algo`).
  - Chạy app với tham số `-selftest` để tự kiểm thử trên Simulator (kết quả: Documents/selftest/).
- `tests/android/run_tests.py` — kiểm thử giao diện tự động trên máy ảo (adb + uiautomator); mã thoát khác 0 nếu có bước lỗi.
- `tests/images/` — ảnh thử TỔNG HỢP (sinh bằng `tests/tools/make_test_images.py`), kèm `gt.txt` toạ độ góc thật;
  `edit_page.jpg` (trang có ngón tay giữ giấy + chữ viết tay + con dấu, sinh bằng `make_retouch_images.py <thư-mục> 1400 <thư-mục-ảnh>`)
  dùng cho kiểm thử giao diện phần chỉnh sửa; `wavy_page.jpg` (biểu mẫu nhàu, gấp nếp, có vân tay, sinh bằng
  `make_wavy_images.py <thư-mục> 1400 <thư-mục-ảnh>`) cho bộ lọc Làm phẳng (bản sao ở `ios/Resources/TestImages/`).
- `tests/tools/` — `make_core_inputs.py` + `make_retouch_images.py` + `make_wavy_images.py` + `compare_core.py` (đối chiếu
  Java ↔ Swift trên cùng dữ liệu tổng hợp), `check_pdfs.py` (kiểm tra PDF bằng pypdf). `make_wavy_images.py` sinh trang
  nhàu / gấp nếp / gợn sóng `wv_*.ppm` kèm đáp án trong thư mục con `gt/` (trang phẳng, bản đồ biến dạng, vị trí nếp gấp...).

## Lệnh thường dùng
- Build Android: `cd android && ./gradlew assembleDebug assembleRelease`
- Build Android không cần Gradle (Linux, dùng android.jar API 23): `android/build_apk.sh`
- Kiểm thử thuật toán: `javac -encoding UTF-8 -d out android/app/src/main/java/vn/quettailieu/scanner/core/*.java android/tools/desktop/*.java && java -cp out EvalSynth tests/images/gt.txt out/det`
- Kiểm thử phần làm đẹp / xoá thông minh: `python3 tests/tools/make_retouch_images.py out/rt 1400 && java -cp out EvalRetouch out/rt`.
- Kiểm thử bộ lọc Làm phẳng: `python3 tests/tools/make_wavy_images.py out/wv 1400 && java -cp out EvalFlatten out/wv [thư-mục-ghi-ảnh-kết-quả]`;
  giữ nội dung ở ảnh nhỏ + không nắn méo trang phẳng: `python3 tests/tools/make_retouch_images.py out/f1500 1500 && java -cp out EvalKeep tests/images out/f1500`.
- Kiểm thử độ bền: `java -cp out FuzzCore out/fuzz 60` (Java), `algotest --fuzz 60 <thư-mục-có-sample.jpg>` (Swift).
- Đối chiếu Java ↔ Swift: `python3 tests/tools/make_core_inputs.py in && python3 tests/tools/make_retouch_images.py in 700 && python3 tests/tools/make_wavy_images.py in 700 && java -cp out DumpCore in j && algotest --dump in s && python3 tests/tools/compare_core.py in j s`
  (`algotest` = `swiftc -O ios/Sources/{DocDetector,Enhancer,Retouch,Flatten,Geom,PdfWriter}.swift ios/Tests/linux/main.swift`).
- Chạy riêng vài bước kiểm thử máy ảo: `gh workflow run android.yml --ref <nhánh> -f only='Xóa thông minh|Đánh dấu' -f apis='[34]'`
  (biến `TEST_ONLY` của `run_tests.py`; kết quả vẫn đẩy lên các nhánh `ci/android-*`).
- Build iOS (macOS): `cd ios && xcodegen generate && xcodebuild -project QuetTaiLieu.xcodeproj -scheme QuetTaiLieu -sdk iphoneos -configuration Release CODE_SIGNING_ALLOWED=NO build`

## CI (GitHub Actions)
- `.github/workflows/android.yml`: build APK, kiểm thử thuật toán (tìm mép, Retouch, Làm phẳng) + fuzz, chạy máy ảo API 24, 29 & 34 với
  `tests/android/run_tests.py` (job lỗi nếu có bước không đạt).
- `.github/workflows/ios.yml`: kiểm thử thuật toán Swift trên Linux (so toạ độ thật, fuzz, đối chiếu với bản Java),
  build IPA (ký ad-hoc, bỏ ảnh thử) + chạy tự kiểm thử trên iOS Simulator (`SelfTest.swift`), chụp màn hình.
- Kết quả (APK/IPA, ảnh màn hình, logcat, summary.md) được đẩy đè lên các nhánh `ci/android-build`, `ci/android-24`,
  `ci/android-29`, `ci/android-34`, `ci/ios`, `ci/ios-algo`.
  Xem bằng `git fetch origin '+refs/heads/ci/*:refs/remotes/origin/ci/*' && git show origin/ci/android-34:summary.md`
  (log job trên GitHub có thể không tải được qua proxy — đọc kết quả từ các nhánh này).

## Quy ước
- Chuỗi giao diện tiếng Việt, đặt trực tiếp trong mã (UI dựng bằng code, không dùng XML layout).
- Android: cú pháp Java 8 nhưng KHÔNG dùng lambda/method reference (để `build_apk.sh` với `dx` vẫn chạy).
- Không thêm thư viện ngoài nếu không thật cần.
- Không đưa ảnh/tài liệu thật của người dùng lên repo (repo công khai) — chỉ dùng ảnh tổng hợp.
- Khoá ký `android/keystore/` không nằm trong repo; bản release từ CI là APK chưa ký.
