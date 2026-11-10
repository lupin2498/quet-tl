package vn.quettailieu.scanner;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;

import java.util.ArrayList;

/**
 * Lớp nền cho các màn hình:
 * - chặn mở trùng một màn hình khi người dùng chạm đúp (hai lần chạm cách nhau vài chục mili-giây);
 * - hoãn việc mở màn hình / hộp thoại sang lúc màn hình đang hiển thị (Android chặn mở Activity
 *   khi ứng dụng đang ở nền, nên việc xong lúc người dùng đã rời đi phải đợi họ quay lại).
 */
abstract class BaseActivity extends Activity {
    private static final long RELAUNCH_GUARD_MS = 700;

    private boolean resumedNow;
    private final ArrayList<Runnable> whenResumed = new ArrayList<>();
    private String lastLaunchKey;
    private long lastLaunchAt;

    boolean isResumedNow() {
        return resumedNow;
    }

    /** Chạy ngay nếu màn hình đang hiển thị, nếu không thì chạy khi người dùng quay lại (bỏ nếu màn hình đã đóng). */
    void runWhenResumed(Runnable r) {
        if (!Ui.alive(this)) return;
        if (resumedNow) r.run();
        else whenResumed.add(r);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumedNow = true;
        if (!whenResumed.isEmpty()) {
            final ArrayList<Runnable> todo = new ArrayList<>(whenResumed);
            whenResumed.clear();
            // chạy sau khi lớp con xử lý xong onResume
            Ui.MAIN.post(new Runnable() {
                @Override
                public void run() {
                    for (Runnable r : todo) runWhenResumed(r);
                }
            });
        }
    }

    @Override
    protected void onPause() {
        resumedNow = false;
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        whenResumed.clear();
        super.onDestroy();
    }

    @Override
    public void startActivityForResult(Intent intent, int requestCode, Bundle options) {
        if (intent != null) {
            ComponentName cn = intent.getComponent();
            String key = cn != null ? cn.getClassName() : intent.getAction();
            long now = SystemClock.uptimeMillis();
            if (key != null) {
                if (key.equals(lastLaunchKey) && now - lastLaunchAt < RELAUNCH_GUARD_MS) return;
                lastLaunchKey = key;
                lastLaunchAt = now;
            }
        }
        super.startActivityForResult(intent, requestCode, options);
    }
}
