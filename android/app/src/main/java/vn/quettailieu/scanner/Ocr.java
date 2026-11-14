package vn.quettailieu.scanner;

import android.graphics.Bitmap;

import java.util.List;

import vn.quettailieu.scanner.core.DocLayout;

/**
 * Bộ nhận dạng chữ chạy ngay trên máy (không cần mạng), dùng cho việc chuyển trang quét sang Word.
 * Bản dựng bằng Gradle kèm ML Kit (lớp MlKitOcr trong app/src/mlkit/java); bản dựng tay bằng build_apk.sh không có
 * thư viện đó nên create() trả về null và các trang được đưa vào Word dưới dạng ảnh.
 */
final class Ocr {
    private Ocr() {}

    interface Engine {
        /**
         * Nhận dạng chữ trong ảnh trang (gọi trên luồng nền). Mỗi từ kèm khung theo điểm ảnh của `bmp`, chỉ số dòng và
         * độ tin cậy 0..1.
         */
        List<DocLayout.Word> recognize(Bitmap bmp) throws Exception;

        void close();
    }

    private static final String IMPL = "vn.quettailieu.scanner.MlKitOcr";

    static boolean available() {
        try {
            Class.forName(IMPL);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Bộ nhận dạng mới (dùng xong phải close()); null nếu bản dựng không kèm bộ nhận dạng chữ. */
    static Engine create() {
        try {
            return (Engine) Class.forName(IMPL).newInstance();
        } catch (Throwable t) {
            return null;
        }
    }
}
