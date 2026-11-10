# Quét Tài Liệu — hướng dẫn cho Claude Code

Ứng dụng quét giấy tờ kiểu CamScanner: chụp → tự tìm mép trang → kéo tay cầm chỉnh vùng cắt →
nắn phẳng theo khổ giấy (A4/Letter) → bộ lọc làm rõ chữ → lưu/chia sẻ PDF, JPG. Giao diện tiếng Việt.

## Cấu trúc
- `android/` — dự án Android Studio (Java, không thư viện ngoài, minSdk 24, targetSdk 34).
  - `app/src/main/java/vn/quettailieu/scanner/core/` — thuật toán Java thuần, KHÔNG phụ thuộc Android
    (`DocDetector` tìm mép, `Geom` hình học + tỷ lệ khổ giấy, `Enhancer` bộ lọc, `PdfWriter`).
  - Các Activity (đều kế thừa `BaseActivity`: chặn mở trùng màn hình khi chạm đúp, `runWhenResumed`): `MainActivity`
    (trang chủ), `CameraActivity` (Camera2, Đơn/Hàng loạt), `CropActivity` + `CropView` (chỉnh 4 góc, kính lúp),
    `EditorActivity` + `PageStripView` (lọc, xoay, cắt lại, so sánh), `DocumentActivity`.
  - `DocStore.open()` trả về MỘT đối tượng `Doc` dùng chung cho mọi màn hình đang mở tài liệu đó (không nạp bản sao
    riêng rồi ghi đè nhau); tài liệu đã xoá thì `save()` bỏ qua. Bản nháp bị bỏ dở được khôi phục khi mở lại ứng dụng.
  - `tools/desktop/` — chạy thuật toán trên JVM: `TestDetect`, `EvalSynth` (so với toạ độ thật), `FuzzCore` (kiểm thử độ bền),
    `DumpCore` (xuất kết quả để đối chiếu với bản Swift).
- `ios/` — bản iOS (Swift/UIKit, CoreImage), dự án sinh bằng XcodeGen (`ios/project.yml`).
  - `ios/Sources/DocDetector.swift` port 1-1 từ `DocDetector.java` (sửa thuật toán thì sửa cả hai); Vision chỉ là dự phòng.
  - `Enhancer.swift`, `PdfWriter.swift`, `DocDetector.swift` chỉ dùng Foundation -> kiểm thử nhanh trên Linux bằng
    `ios/Tests/linux/main.swift` (job `algo-linux`, kết quả ở nhánh `ci/ios-algo`).
  - Chạy app với tham số `-selftest` để tự kiểm thử trên Simulator (kết quả: Documents/selftest/).
- `tests/android/run_tests.py` — kiểm thử giao diện tự động trên máy ảo (adb + uiautomator); mã thoát khác 0 nếu có bước lỗi.
- `tests/images/` — ảnh thử TỔNG HỢP (sinh bằng `tests/tools/make_test_images.py`), kèm `gt.txt` toạ độ góc thật.
- `tests/tools/` — `make_core_inputs.py` + `compare_core.py` (đối chiếu Java ↔ Swift trên cùng dữ liệu tổng hợp),
  `check_pdfs.py` (kiểm tra PDF bằng pypdf).

## Lệnh thường dùng
- Build Android: `cd android && ./gradlew assembleDebug assembleRelease`
- Build Android không cần Gradle (Linux, dùng android.jar API 23): `android/build_apk.sh`
- Kiểm thử thuật toán: `javac -encoding UTF-8 -d out android/app/src/main/java/vn/quettailieu/scanner/core/*.java android/tools/desktop/*.java && java -cp out EvalSynth tests/images/gt.txt out/det`
- Kiểm thử độ bền: `java -cp out FuzzCore out/fuzz 60` (Java), `algotest --fuzz 60 <thư-mục-có-sample.jpg>` (Swift).
- Đối chiếu Java ↔ Swift: `python3 tests/tools/make_core_inputs.py in && java -cp out DumpCore in j && algotest --dump in s && python3 tests/tools/compare_core.py in j s`
  (`algotest` = `swiftc -O ios/Sources/{DocDetector,Enhancer,Geom,PdfWriter}.swift ios/Tests/linux/main.swift`).
- Build iOS (macOS): `cd ios && xcodegen generate && xcodebuild -project QuetTaiLieu.xcodeproj -scheme QuetTaiLieu -sdk iphoneos -configuration Release CODE_SIGNING_ALLOWED=NO build`

## CI (GitHub Actions)
- `.github/workflows/android.yml`: build APK, kiểm thử thuật toán + fuzz, chạy máy ảo API 24, 29 & 34 với
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
