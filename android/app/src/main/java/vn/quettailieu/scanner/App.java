package vn.quettailieu.scanner;

import android.app.Application;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;

public class App extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        Ui.init(this);
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                try {
                    StringWriter sw = new StringWriter();
                    PrintWriter pw = new PrintWriter(sw);
                    pw.println("Thiết bị: " + Build.MANUFACTURER + " " + Build.MODEL + " — Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
                    pw.println("Luồng: " + t.getName());
                    e.printStackTrace(pw);
                    pw.flush();
                    FileOutputStream fo = new FileOutputStream(crashFile(App.this));
                    fo.write(sw.toString().getBytes("UTF-8"));
                    fo.close();
                } catch (Throwable ignored) {
                }
                if (prev != null) prev.uncaughtException(t, e);
                else System.exit(2);
            }
        });
        // dọn bản nháp cũ (quá 1 ngày) và file tạm
        Processor.EXEC.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    DocStore.cleanDrafts(App.this, 24L * 3600 * 1000);
                    ImageUtil.cleanCache(App.this);
                } catch (Throwable ignored) {
                }
            }
        });
    }

    static File crashFile(android.content.Context c) {
        return new File(c.getFilesDir(), "last_crash.txt");
    }
}
