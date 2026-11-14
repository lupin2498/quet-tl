package vn.quettailieu.scanner;

import android.graphics.Bitmap;
import android.graphics.Rect;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import vn.quettailieu.scanner.core.DocLayout;

/**
 * Nhận dạng chữ bằng ML Kit (mô hình chữ La-tinh đóng kèm trong ứng dụng: đọc được tiếng Việt có dấu, chạy trên máy,
 * không cần mạng). Chỉ có trong bản dựng bằng Gradle; lớp Ocr nạp lớp này theo tên.
 */
public final class MlKitOcr implements Ocr.Engine {
    static final float CONF_SCALE = 2f;

    private TextRecognizer rec;

    public MlKitOcr() {}

    @Override
    public List<DocLayout.Word> recognize(Bitmap bmp) throws Exception {
        if (rec == null) rec = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        Text text = Tasks.await(rec.process(InputImage.fromBitmap(bmp, 0)), 180, TimeUnit.SECONDS);
        ArrayList<DocLayout.Word> out = new ArrayList<>();
        int line = 0;
        for (Text.TextBlock block : text.getTextBlocks()) {
            for (Text.Line ln : block.getLines()) {
                line++;
                for (Text.Element el : ln.getElements()) {
                    String s = el.getText();
                    Rect r = el.getBoundingBox();
                    if (s == null || r == null) continue;
                    // dấu tiếng Việt về dạng dựng sẵn (một ký tự) để đo bề rộng chữ và ghi ra Word nhất quán
                    s = Normalizer.normalize(s, Normalizer.Form.NFC).trim();
                    if (s.length() == 0) continue;
                    DocLayout.Word w = new DocLayout.Word(s, r.left, r.top, r.right, r.bottom, line);
                    // Thang độ tin cậy của ML Kit thấp hơn thang của lõi (lõi coi dưới 0,5 là không đọc được): chữ đọc đúng
                    // trên ảnh nhỏ thường chỉ được 0,3-0,6, còn rác (đọc nền giấy, nét con dấu) dưới 0,25 -> nhân đôi.
                    w.conf = Math.min(1f, el.getConfidence() * CONF_SCALE);
                    out.add(w);
                }
            }
        }
        return out;
    }

    @Override
    public void close() {
        if (rec != null) {
            try {
                rec.close();
            } catch (Throwable ignored) {
            }
            rec = null;
        }
    }
}
