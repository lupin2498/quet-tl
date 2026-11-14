# Quét Tài Liệu — hướng dẫn cho Claude Code

Ứng dụng quét giấy tờ kiểu CamScanner: chụp → tự tìm mép trang → kéo tay cầm chỉnh vùng cắt →
nắn phẳng theo khổ giấy (A4/Letter) → bộ lọc làm rõ chữ (có "Làm phẳng" cho giấy mấp mô / gấp nếp: nắn thẳng dòng chữ,
bảng biểu và chỉ giữ nội dung) → chỉnh sửa (xoá thông minh, đánh dấu, tiêu đề trang, cắt dán; phóng to bằng hai ngón) →
lưu/chia sẻ PDF, JPG, hoặc chuyển sang Word (.docx) giữ nguyên bố cục (nhận dạng chữ trên máy, bảng kẻ thành bảng Word).
Giao diện tiếng Việt.

## Cấu trúc
- `android/` — dự án Android Studio (Java, không thư viện ngoài, minSdk 24, targetSdk 34).
  - `app/src/main/java/vn/quettailieu/scanner/core/` — thuật toán Java thuần, KHÔNG phụ thuộc Android
    (`DocDetector` tìm mép, `Geom` hình học + tỷ lệ khổ giấy, `Enhancer` bộ lọc, `Retouch` gỡ ngón tay / xoá thông minh /
    khử nhiễu / làm nét / dọn mép, `Flatten` bộ lọc "Làm phẳng": làm sạch (chỉ giữ nét mực, giữ + làm rõ lưới bảng) rồi dò
    dòng chữ / đường kẻ bảng bị uốn lượn, khớp trường dịch chuyển trơn (bình phương tối thiểu trên lưới, gradient liên hợp)
    và nắn lại ảnh — kích thước ảnh giữ nguyên, `PdfWriter`; chuyển sang Word: `DocLayout` phân tích ảnh trang + các từ do
    bộ nhận dạng chữ trả về -> đường kẻ bảng (gộp, lưới, ô gộp), đo từng từ theo số đo phông Times New Roman của `WordFont`
    (cỡ, đậm, nghiêng, gạch chân, màu), khử nghiêng, lọc rác của bộ nhận dạng, xếp dòng / hộp chữ, phần mực không đọc được
    thành hình; `WordDoc` là mô hình trang; `DocxWriter` ghi DOCX — mỗi dòng một đoạn chiều cao chính xác, bảng Word thật,
    hình / đường kẻ neo theo trang, tự ghi zip).
  - Các Activity (đều kế thừa `BaseActivity`: chặn mở trùng màn hình khi chạm đúp, `runWhenResumed`): `MainActivity`
    (trang chủ), `CameraActivity` (Camera2, Đơn/Hàng loạt), `CropActivity` + `CropView` (chỉnh 4 góc, kính lúp),
    `EditorActivity` + `PageStripView` (thanh công cụ ba nhóm Ảnh / Đánh dấu / Trang; lọc, xoay, cắt lại, so sánh,
    lời nhắc ngón tay, tiêu đề trang, cắt dán; chụm / dang hai ngón phóng trang tới 6 lần, giữ mức phóng khi đổi bộ lọc),
    `EraseActivity` + `EraseView` (Xóa thông minh), `MarkupActivity` + `MarkView` (Đánh dấu; `MarkRender` vẽ chung cho
    màn hình chỉnh sửa và ảnh kết quả), `DocumentActivity` (Thêm / Sửa / Chia sẻ / Word / Lưu PDF / Lưu ảnh).
  - Chuyển sang Word: `WordExport` (từng trang: ảnh kết quả cạnh dài tối đa 2400 -> nhận dạng chữ, trang dưới 1600 điểm
    ảnh được phóng lên trước -> `DocLayout` -> `DocxWriter`; trang không đọc được chữ vào Word dạng ảnh; hộp thoại chờ có
    nút Huỷ). Bộ nhận dạng chữ là ML Kit Text Recognition (mô hình La-tinh đóng kèm, chạy trên máy) — thư viện ngoài DUY
    NHẤT; lớp nối `MlKitOcr` nằm ở `app/src/mlkit/java` và được `Ocr` nạp theo tên lớp, nên `build_apk.sh` (không Gradle,
    không ML Kit) vẫn dựng được (bản đó đưa trang vào Word dạng ảnh). Manifest gỡ quyền INTERNET mà ML Kit khai báo: ứng
    dụng không dùng mạng.
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
    (xuất kết quả để đối chiếu với bản Swift), `EvalWord` (chuyển các trang `<tên>.ppm` + `<tên>.words.tsv` — kết quả
    "nhận dạng chữ" cho sẵn — sang .docx; kiểm bằng `tests/tools/check_docx.py`).
- `ios/` — bản iOS (Swift/UIKit, CoreImage), dự án sinh bằng XcodeGen (`ios/project.yml`).
  - `ios/Sources/DocDetector.swift`, `Enhancer.swift`, `Retouch.swift`, `Flatten.swift`, `DocLayout.swift`, `DocxWriter.swift`,
    `WordDoc.swift` port 1-1 từ bản Java (sửa thuật toán thì sửa cả hai; CI so từng điểm ảnh / từng ký tự XML giữa hai
    bản); `WordFont.swift` + `WordFont.java` sinh bằng `tests/tools/gen_word_font.py` (không sửa tay). Vision là dự phòng
    khi tìm mép và là bộ nhận dạng chữ của tính năng chuyển sang Word (`WordExport.swift`; tiếng Việt cần iOS 16+).
  - Giao diện chỉnh sửa: `EditorViewController` (ô trang `PageCell` là UIScrollView: chụm / dang hai ngón phóng tới 6 lần),
    `EraseViewController`, `MarkupViewController` (chung khung `EditScreen.swift`, vùng vẽ `PageCanvasView`),
    `MarkRender.swift`, `Collage.swift`, mô hình `Edits.swift` (`EraseOp`, `Mark`, `PageHeader`).
  - `Enhancer.swift`, `Retouch.swift`, `Flatten.swift`, `PdfWriter.swift`, `DocDetector.swift`, `DocLayout.swift`,
    `DocxWriter.swift`, `WordDoc.swift`, `WordFont.swift` chỉ dùng Foundation -> kiểm thử nhanh trên Linux bằng
    `ios/Tests/linux/main.swift` (job `algo-linux`, kết quả ở nhánh `ci/ios-algo`). `DocxWriter.swift` nhận hàm nén DEFLATE
    từ lớp ứng dụng (framework Compression không có trên Linux; không truyền thì ghi zip không nén).
  - Chạy app với tham số `-selftest` để tự kiểm thử trên Simulator (kết quả: Documents/selftest/).
- `tests/android/run_tests.py` — kiểm thử giao diện tự động trên máy ảo (adb + uiautomator); mã thoát khác 0 nếu có bước lỗi.
- `tests/images/` — ảnh thử TỔNG HỢP (sinh bằng `tests/tools/make_test_images.py`), kèm `gt.txt` toạ độ góc thật;
  `edit_page.jpg` (trang có ngón tay giữ giấy + chữ viết tay + con dấu, sinh bằng `make_retouch_images.py <thư-mục> 1400 <thư-mục-ảnh>`)
  dùng cho kiểm thử giao diện phần chỉnh sửa; `wavy_page.jpg` (biểu mẫu nhàu, gấp nếp, có vân tay, sinh bằng
  `make_wavy_images.py <thư-mục> 1400 <thư-mục-ảnh>`) cho bộ lọc Làm phẳng (bản sao ở `ios/Resources/TestImages/`).
- `tests/tools/` — `make_core_inputs.py` + `make_retouch_images.py` + `make_wavy_images.py` + `compare_core.py` (đối chiếu
  Java ↔ Swift trên cùng dữ liệu tổng hợp), `check_pdfs.py` (kiểm tra PDF bằng pypdf). `make_wavy_images.py` sinh trang
  nhàu / gấp nếp / gợn sóng `wv_*.ppm` kèm đáp án trong thư mục con `gt/` (trang phẳng, bản đồ biến dạng, vị trí nếp gấp...);
  `make_flatten_cases.py` sinh các ca hồi quy cùng định dạng (con dấu đè lên bảng, chữ viết bút xanh dưới tiêu đề in đen,
  bảng gấp nếp sâu, giấy vở kẻ dòng; trang `wv_flat_*` là trang phẳng đối chứng) — chạy bằng `EvalFlatten`.
  Chuyển sang Word: `make_word_pages.py` vẽ công văn / biểu mẫu / văn bản (+ bản mờ, bản nghiêng) bằng Liberation Serif kèm
  `<tên>.words.tsv` ("nhận dạng chữ" lý tưởng) và đáp án `<tên>.gt.json`; `make_word_junk.py` sinh trang có kết quả nhận
  dạng đầy rác; `check_docx.py` kiểm file Word (cấu trúc OOXML, bảng / chữ / kiểu chữ so với đáp án; `--render`: dựng lại
  bằng LibreOffice + poppler và đo độ lệch vị trí từng dòng); `check_docx_zip.py` kiểm gói .docx bất kỳ; `gen_word_font.py`
  sinh bảng số đo phông; `make_release.sh` xuất bộ cài từ kết quả CI (xem cuối file).

## Lệnh thường dùng
- Build Android: `cd android && ./gradlew assembleDebug assembleRelease`
- Build Android không cần Gradle (Linux, dùng android.jar API 23): `android/build_apk.sh`
- Kiểm thử thuật toán: `javac -encoding UTF-8 -d out android/app/src/main/java/vn/quettailieu/scanner/core/*.java android/tools/desktop/*.java && java -cp out EvalSynth tests/images/gt.txt out/det`
- Kiểm thử phần làm đẹp / xoá thông minh: `python3 tests/tools/make_retouch_images.py out/rt 1400 && java -cp out EvalRetouch out/rt`.
- Kiểm thử bộ lọc Làm phẳng: `python3 tests/tools/make_wavy_images.py out/wv 1400 && java -cp out EvalFlatten out/wv [thư-mục-ghi-ảnh-kết-quả]`;
  giữ nội dung ở ảnh nhỏ + không nắn méo trang phẳng: `python3 tests/tools/make_retouch_images.py out/f1500 1500 && java -cp out EvalKeep tests/images out/f1500`.
- Kiểm thử chuyển sang Word (cần phông `fonts-liberation`; `--render` cần LibreOffice + poppler):
  `python3 tests/tools/make_word_pages.py out/wd 2200 && java -cp out EvalWord out/wd out/wd-docx && python3 tests/tools/check_docx.py out/wd out/wd-docx --render`.
  Đối chiếu lõi Word Java ↔ Swift (mô hình trang, document.xml, hình cắt, từng phần của DOCX, cả ba khổ giấy):
  `java -cp out DumpCore --word out/wd out/wd-j && algotest --dump-word out/wd out/wd-s && python3 tests/tools/compare_core.py --word out/wd-j out/wd-s`
  (thêm `python3 tests/tools/make_word_junk.py out/wj` cho các trang rác).
- Kiểm thử độ bền: `java -cp out FuzzCore out/fuzz 60` (Java), `algotest --fuzz 60 <thư-mục-có-sample.jpg>` (Swift); file
  .docx do fuzz ghi ra kiểm bằng `python3 tests/tools/check_docx_zip.py out/fuzz`.
- Đối chiếu Java ↔ Swift: `python3 tests/tools/make_core_inputs.py in && python3 tests/tools/make_retouch_images.py in 700 && python3 tests/tools/make_wavy_images.py in 700 && java -cp out DumpCore in j && algotest --dump in s && python3 tests/tools/compare_core.py in j s`
  (`algotest` = `swiftc -O ios/Sources/{DocDetector,Enhancer,Retouch,Flatten,Geom,PdfWriter,WordFont,WordDoc,DocxWriter,DocLayout}.swift ios/Tests/linux/main.swift`).
- Chạy riêng vài bước kiểm thử máy ảo: `gh workflow run android.yml --ref <nhánh> -f only='Xóa thông minh|Đánh dấu' -f apis='[34]'`
  (biến `TEST_ONLY` của `run_tests.py`; kết quả vẫn đẩy lên các nhánh `ci/android-*`).
- Build iOS (macOS): `cd ios && xcodegen generate && xcodebuild -project QuetTaiLieu.xcodeproj -scheme QuetTaiLieu -sdk iphoneos -configuration Release CODE_SIGNING_ALLOWED=NO build`

## CI (GitHub Actions)
- `.github/workflows/android.yml`: build APK (Gradle, kèm ML Kit), kiểm thử thuật toán (tìm mép, Retouch, Làm phẳng, chuyển sang
  Word — dựng lại bằng LibreOffice) + fuzz, chạy máy ảo API 24, 29 & 34 với `tests/android/run_tests.py` (job lỗi nếu có
  bước không đạt; bước "Chuyển sang Word" chạy ML Kit thật trên máy ảo, file .docx + dữ liệu nhận dạng đẩy về nhánh kết quả).
- `.github/workflows/ios.yml`: kiểm thử thuật toán Swift trên Linux (so toạ độ thật, fuzz, đối chiếu với bản Java — kể cả lõi
  Word), build IPA (ký ad-hoc, bỏ ảnh thử) + chạy tự kiểm thử trên iOS Simulator (`SelfTest.swift`: có chuyển sang Word bằng
  Vision), chụp màn hình.
- Kết quả (APK/IPA, ảnh màn hình, logcat, summary.md) được đẩy đè lên các nhánh `ci/android-build`, `ci/android-24`,
  `ci/android-29`, `ci/android-34`, `ci/ios`, `ci/ios-algo`.
  Xem bằng `git fetch origin '+refs/heads/ci/*:refs/remotes/origin/ci/*' && git show origin/ci/android-34:summary.md`
  (log job trên GitHub có thể không tải được qua proxy — đọc kết quả từ các nhánh này).

## Quy ước
- Chuỗi giao diện tiếng Việt, đặt trực tiếp trong mã (UI dựng bằng code, không dùng XML layout).
- Android: cú pháp Java 8 nhưng KHÔNG dùng lambda/method reference (để `build_apk.sh` với `dx` vẫn chạy).
- Không thêm thư viện ngoài nếu không thật cần (hiện chỉ có ML Kit Text Recognition ở bản Android dựng bằng Gradle).
- File Word: hàng bảng ghi `hRule="atLeast"` với `trHeight` = khoảng trống GIỮA hai viền (Word và LibreOffice >= 25 cộng
  bề dày viền vào hàng atLeast; LibreOffice 24.2 trở về trước thì không — `check_docx.py` tự dò trình dựng và bù). Kết quả
  dàn trang phải được kiểm bằng dữ liệu của bộ nhận dạng thật (ML Kit / Vision), không chỉ "nhận dạng lý tưởng" tổng hợp:
  khung từ của ML Kit có thể lệch nửa chữ, độ tin cậy theo thang riêng (lớp nối quy đổi về thang của lõi).
- Không đưa ảnh/tài liệu thật của người dùng lên repo (repo công khai) — chỉ dùng ảnh tổng hợp.
- Khoá ký `android/keystore/` không nằm trong repo; bản release từ CI là APK chưa ký. Xuất bộ cài:
  `tests/tools/make_release.sh <phiên-bản> <thư-mục-ra> [keystore]` — chỉ chạy khi sáu nhánh `ci/*` đều đạt trên đúng commit
  HEAD, ký APK bằng khoá gốc (kiểm SHA-256 chứng thư; `build_apk.sh` tự sinh khoá MỚI khi thiếu file — không dùng khoá đó
  để phát hành) và lấy IPA từ `ci/ios`.
