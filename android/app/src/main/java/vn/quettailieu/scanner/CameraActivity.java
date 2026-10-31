package vn.quettailieu.scanner;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Paint;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Size;
import android.util.SizeF;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.OrientationEventListener;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** Màn hình chụp: chế độ Đơn / Hàng loạt, đèn, HD, lưới, nhập ảnh. Dùng Camera2. */
public class CameraActivity extends Activity {
    static final String EXTRA_DOC = "doc";
    static final String EXTRA_BATCH = "batch";
    static final String EXTRA_RETAKE = "retake";

    private static final int REQ_PERM = 11;
    private static final int REQ_CROP = 12;
    private static final int REQ_EDITOR = 13;
    private static final int REQ_PICK = 14;

    private static final int STATE_IDLE = 0;
    private static final int STATE_PREVIEW = 1;
    private static final int STATE_WAITING_LOCK = 2;
    private static final int STATE_CAPTURING = 3;

    // ---- tài liệu
    private DocStore.Doc doc;
    private boolean newDoc;
    private final ArrayList<String> sessionPages = new ArrayList<>();
    private boolean batch;
    private String retakeId;
    private String pendingPath;
    private double pendingFocal = -1;

    // ---- camera
    private TextureView texture;
    private AspectFrame previewFrame;
    private HandlerThread bgThread;
    private Handler bg;
    private final Semaphore openLock = new Semaphore(1);
    private volatile boolean opening;
    private CameraDevice device;
    private CameraCaptureSession captureSession;
    private ImageReader reader;
    private Surface previewSurface;
    private CaptureRequest.Builder previewBuilder;
    private String cameraId;
    private Size previewSize, jpegSize;
    private int sensorOrientation = 90;
    private boolean flashSupported, afContinuous, afAuto;
    private double focalPx = -1;
    private volatile int state = STATE_IDLE;
    private volatile long lockStart;
    private volatile int deviceOrientation = 0;
    private boolean torch, hd = true, grid;
    private OrientationEventListener orientationListener;
    private boolean permAsked;

    // ---- giao diện
    private View flashView;
    private ProgressBar spinner;
    private LinearLayout permPanel;
    private LinearLayout tabs;
    private TextView tabSingle, tabBatch, retakeLabel;
    private ShutterButton shutter;
    private FrameLayout leftSlot, rightSlot;
    private ImageView flashBtn, hdBtn, gridBtn;
    private GridOverlay gridView;
    private ImageView thumbView;
    private boolean busy;
    private boolean resumed;
    private boolean skipNextOpen;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        SharedPreferences sp = getSharedPreferences(Exporter.PREFS, 0);
        hd = sp.getBoolean("hd", true);
        grid = sp.getBoolean("grid", false);
        Intent it = getIntent();
        batch = it.hasExtra(EXTRA_BATCH) ? it.getBooleanExtra(EXTRA_BATCH, false) : sp.getBoolean("batch", false);
        retakeId = it.getStringExtra(EXTRA_RETAKE);
        String docId = it.getStringExtra(EXTRA_DOC);
        if (state != null) {
            docId = state.getString("doc", docId);
            newDoc = state.getBoolean("newDoc", false);
            ArrayList<String> s = state.getStringArrayList("session");
            if (s != null) sessionPages.addAll(s);
            batch = state.getBoolean("batch", batch);
            pendingPath = state.getString("pending");
            pendingFocal = state.getDouble("pendingFocal", -1);
        }
        if (docId != null) {
            doc = DocStore.load(this, docId);
            if (doc == null) {
                Ui.toast(this, "Không mở được tài liệu");
                finish();
                return;
            }
            for (int i = sessionPages.size() - 1; i >= 0; i--) if (doc.page(sessionPages.get(i)) == null) sessionPages.remove(i);
        }
        if (retakeId != null) batch = false;
        buildUi();
        orientationListener = new OrientationEventListener(this) {
            @Override
            public void onOrientationChanged(int o) {
                if (o == ORIENTATION_UNKNOWN) return;
                deviceOrientation = ((o + 45) / 90 * 90) % 360;
            }
        };
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (doc != null) out.putString("doc", doc.id);
        out.putBoolean("newDoc", newDoc);
        out.putStringArrayList("session", sessionPages);
        out.putBoolean("batch", batch);
        if (pendingPath != null) out.putString("pending", pendingPath);
        out.putDouble("pendingFocal", pendingFocal);
    }

    // =================================================================== giao diện

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        // vùng xem trước
        AspectFrame preview = new AspectFrame(this);
        previewFrame = preview;
        texture = new TextureView(this);
        preview.addView(texture, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        gridView = new GridOverlay(this);
        gridView.setVisibility(grid ? View.VISIBLE : View.GONE);
        preview.addView(gridView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        flashView = new View(this);
        flashView.setBackgroundColor(Color.WHITE);
        flashView.setAlpha(0f);
        preview.addView(flashView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        spinner = new ProgressBar(this);
        spinner.setIndeterminateTintList(ColorStateList.valueOf(Ui.ACCENT));
        spinner.setVisibility(View.GONE);
        preview.addView(spinner, Ui.frame(Ui.dp(56), Ui.dp(56), Gravity.CENTER));
        permPanel = buildPermPanel();
        permPanel.setVisibility(View.GONE);
        preview.addView(permPanel, Ui.frame(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(320), Gravity.CENTER));
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.topMargin = Ui.dp(56);
        root.addView(preview, plp);

        // thanh trên
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setBackgroundColor(Color.BLACK);
        top.setPadding(Ui.dp(4), 0, Ui.dp(4), 0);
        top.addView(Ui.desc(Ui.iconButton(this, R.drawable.ic_close, Color.WHITE, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                attemptClose();
            }
        }), "Đóng"));
        top.addView(new View(this), Ui.lin(0, 1, 1f));
        flashBtn = Ui.iconButton(this, R.drawable.ic_flash_off, Color.WHITE, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleTorch();
            }
        });
        flashBtn.setContentDescription("Đèn flash");
        top.addView(flashBtn);
        hdBtn = Ui.iconButton(this, R.drawable.ic_hd, Color.WHITE, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleHd();
            }
        });
        hdBtn.setContentDescription("Chất lượng HD");
        top.addView(hdBtn);
        gridBtn = Ui.iconButton(this, R.drawable.ic_grid, Color.WHITE, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                grid = !grid;
                getSharedPreferences(Exporter.PREFS, 0).edit().putBoolean("grid", grid).apply();
                gridView.setVisibility(grid ? View.VISIBLE : View.GONE);
                updateTopIcons();
            }
        });
        gridBtn.setContentDescription("Lưới căn");
        top.addView(gridBtn);
        root.addView(top, Ui.frame(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(56), Gravity.TOP));

        // thanh dưới
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setGravity(Gravity.CENTER_HORIZONTAL);
        bottom.setBackgroundColor(0xE6000000);
        bottom.setPadding(0, Ui.dp(6), 0, Ui.dp(14));

        tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setGravity(Gravity.CENTER);
        tabSingle = tab("Đơn", false);
        tabBatch = tab("Hàng loạt", true);
        tabs.addView(tabSingle);
        tabs.addView(tabBatch);
        bottom.addView(tabs, Ui.lin(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(40), 0));
        retakeLabel = Ui.text(this, "Chụp lại trang", 15, Ui.ACCENT, true);
        retakeLabel.setGravity(Gravity.CENTER);
        bottom.addView(retakeLabel, Ui.lin(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(40), 0));

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER);
        leftSlot = new FrameLayout(this);
        controls.addView(leftSlot, Ui.lin(Ui.dp(84), Ui.dp(84), 0));
        shutter = new ShutterButton(this);
        shutter.setContentDescription("Chụp");
        shutter.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                shoot();
            }
        });
        LinearLayout.LayoutParams slp = Ui.lin(Ui.dp(80), Ui.dp(80), 0);
        slp.leftMargin = Ui.dp(30);
        slp.rightMargin = Ui.dp(30);
        controls.addView(shutter, slp);
        rightSlot = new FrameLayout(this);
        controls.addView(rightSlot, Ui.lin(Ui.dp(84), Ui.dp(84), 0));
        bottom.addView(controls, Ui.lin(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(90), 0));

        root.addView(bottom, Ui.frame(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        setContentView(root);
        updateTopIcons();
        refreshBottom();
    }

    private TextView tab(String label, final boolean isBatch) {
        TextView t = Ui.text(this, label, 15, Ui.TEXT, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(Ui.dp(16), 0, Ui.dp(16), 0);
        t.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (batch == isBatch) return;
                if (!sessionPages.isEmpty()) {
                    Ui.toast(CameraActivity.this, "Hãy hoàn tất các trang đã chụp trước khi đổi chế độ");
                    return;
                }
                batch = isBatch;
                getSharedPreferences(Exporter.PREFS, 0).edit().putBoolean("batch", batch).apply();
                refreshBottom();
            }
        });
        return t;
    }

    private LinearLayout buildPermPanel() {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setGravity(Gravity.CENTER);
        p.setPadding(Ui.dp(32), 0, Ui.dp(32), 0);
        TextView t = Ui.text(this, "Ứng dụng cần quyền Máy ảnh để quét tài liệu", 16, Ui.TEXT, false);
        t.setGravity(Gravity.CENTER);
        p.addView(t);
        TextView b = Ui.primaryButton(this, "Cấp quyền", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                    requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_PERM);
                } else {
                    try {
                        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", getPackageName(), null)));
                    } catch (Throwable ignored) {
                    }
                }
            }
        });
        LinearLayout.LayoutParams lp = Ui.lin(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(44), 0);
        lp.topMargin = Ui.dp(18);
        p.addView(b, lp);
        return p;
    }

    private void updateTopIcons() {
        flashBtn.setImageResource(torch ? R.drawable.ic_flash_on : R.drawable.ic_flash_off);
        flashBtn.setImageTintList(ColorStateList.valueOf(torch ? Ui.ACCENT : Color.WHITE));
        hdBtn.setImageTintList(ColorStateList.valueOf(hd ? Color.WHITE : 0x80FFFFFF));
        gridBtn.setImageTintList(ColorStateList.valueOf(grid ? Ui.ACCENT : Color.WHITE));
    }

    private void refreshBottom() {
        boolean retake = retakeId != null;
        tabs.setVisibility(retake ? View.GONE : View.VISIBLE);
        retakeLabel.setVisibility(retake ? View.VISIBLE : View.GONE);
        styleTab(tabSingle, !batch);
        styleTab(tabBatch, batch);
        leftSlot.removeAllViews();
        rightSlot.removeAllViews();
        thumbView = null;
        if (batch && !sessionPages.isEmpty()) {
            ImageView undo = Ui.iconButton(this, R.drawable.ic_undo, Color.WHITE, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    undoLast();
                }
            });
            undo.setContentDescription("Hoàn tác");
            leftSlot.addView(undo, Ui.frame(Ui.dp(52), Ui.dp(52), Gravity.CENTER));
            rightSlot.addView(buildThumb(), Ui.frame(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
            DocStore.Page last = doc == null ? null : doc.page(sessionPages.get(sessionPages.size() - 1));
            if (last != null) loadThumb(last);
        } else {
            LinearLayout imp = Ui.toolButton(this, R.drawable.ic_photo_library, "Nhập ảnh", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    pickImages();
                }
            });
            leftSlot.addView(imp, Ui.frame(Ui.dp(84), Ui.dp(72), Gravity.CENTER));
        }
    }

    private void styleTab(TextView t, boolean selected) {
        t.setTextColor(selected ? Ui.ACCENT : 0xCCFFFFFF);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        if (selected) {
            android.graphics.drawable.GradientDrawable bar = Ui.rect(Ui.ACCENT, 2);
            bar.setBounds(0, 0, Ui.dp(18), Ui.dp(3));
            t.setCompoundDrawables(null, bar, null, null);
            t.setCompoundDrawablePadding(Ui.dp(2));
        } else {
            t.setCompoundDrawables(null, null, null, null);
        }
    }

    private View buildThumb() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setBackground(Ui.ripple(null, true));
        box.setContentDescription("Xem các trang đã chụp");
        box.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openEditor();
            }
        });
        FrameLayout f = new FrameLayout(this);
        thumbView = new ImageView(this);
        thumbView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumbView.setBackgroundColor(0xFF555555);
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(Ui.dp(46), Ui.dp(58));
        tl.topMargin = Ui.dp(8);
        tl.rightMargin = Ui.dp(8);
        tl.gravity = Gravity.BOTTOM | Gravity.LEFT;
        f.addView(thumbView, tl);
        TextView badge = Ui.text(this, String.valueOf(sessionPages.size()), 12, Color.WHITE, true);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(Ui.oval(Ui.BADGE));
        f.addView(badge, Ui.frame(Ui.dp(22), Ui.dp(22), Gravity.TOP | Gravity.RIGHT));
        box.addView(f, new LinearLayout.LayoutParams(Ui.dp(56), Ui.dp(68)));
        box.addView(Ui.icon(this, R.drawable.ic_chevron_right, Color.WHITE, 20));
        return box;
    }

    private void loadThumb(DocStore.Page p) {
        if (doc == null || p == null || p.src == null) return;
        final String path = doc.file(p.src).getPath();
        final int w = p.srcW, h = p.srcH;
        final float[] q = p.quad == null ? null : p.quad.clone();
        Processor.IO.execute(new Runnable() {
            @Override
            public void run() {
                final Bitmap b = ImageUtil.quickWarp(path, w, h, q, 240);
                Ui.MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        if (Ui.alive(CameraActivity.this) && thumbView != null && b != null) thumbView.setImageBitmap(b);
                    }
                });
            }
        });
    }

    // =================================================================== vòng đời

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (skipNextOpen) {
            // sắp chuyển sang màn hình khác -> không mở máy ảnh
            skipNextOpen = false;
            return;
        }
        if (orientationListener != null && orientationListener.canDetectOrientation()) orientationListener.enable();
        startBg();
        if (hasCameraPermission()) {
            permPanel.setVisibility(View.GONE);
            if (texture.isAvailable()) openCamera();
            else texture.setSurfaceTextureListener(surfaceListener);
        } else if (!permAsked) {
            permAsked = true;
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_PERM);
        } else {
            permPanel.setVisibility(View.VISIBLE);
        }
    }

    @Override
    protected void onPause() {
        resumed = false;
        closeCamera();
        stopBg();
        if (orientationListener != null) orientationListener.disable();
        super.onPause();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (requestCode != REQ_PERM) return;
        boolean ok = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
        permPanel.setVisibility(ok ? View.GONE : View.VISIBLE);
        if (ok && bg != null) {
            if (texture.isAvailable()) openCamera();
            else texture.setSurfaceTextureListener(surfaceListener);
        }
    }

    private boolean hasCameraPermission() {
        return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private void startBg() {
        if (bgThread != null) return;
        bgThread = new HandlerThread("camera");
        bgThread.start();
        bg = new Handler(bgThread.getLooper());
    }

    private void stopBg() {
        if (bgThread == null) return;
        bgThread.quitSafely();
        try {
            bgThread.join(1500);
        } catch (InterruptedException ignored) {
        }
        bgThread = null;
        bg = null;
    }

    private final TextureView.SurfaceTextureListener surfaceListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
            openCamera();
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture st) {
        }
    };

    // =================================================================== Camera2

    private void openCamera() {
        if (device != null || opening || bg == null || !hasCameraPermission()) return;
        CameraManager mgr = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        if (mgr == null) return;
        try {
            if (!setupCamera(mgr)) {
                Ui.toast(this, "Không tìm thấy máy ảnh sau");
                return;
            }
        } catch (Throwable t) {
            Ui.toast(this, "Không đọc được thông tin máy ảnh");
            return;
        }
        boolean swapped = sensorOrientation == 90 || sensorOrientation == 270;
        if (swapped) previewFrame.setAspectRatio(previewSize.getHeight(), previewSize.getWidth());
        else previewFrame.setAspectRatio(previewSize.getWidth(), previewSize.getHeight());
        boolean acquired = false;
        try {
            if (!openLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                Ui.toast(this, "Máy ảnh đang bận");
                return;
            }
            acquired = true;
            synchronized (this) {
                opening = true;
            }
            mgr.openCamera(cameraId, deviceCallback, bg);
        } catch (Throwable t) {
            synchronized (this) {
                if (opening) {
                    opening = false;
                    openLock.release();
                } else if (acquired) {
                    openLock.release();
                }
            }
            Ui.toast(this, "Không mở được máy ảnh: " + t.getMessage());
        }
    }

    private boolean setupCamera(CameraManager mgr) throws CameraAccessException {
        String chosen = null;
        CameraCharacteristics ch = null;
        String[] ids = mgr.getCameraIdList();
        for (String id : ids) {
            CameraCharacteristics c = mgr.getCameraCharacteristics(id);
            Integer f = c.get(CameraCharacteristics.LENS_FACING);
            if (f != null && f == CameraCharacteristics.LENS_FACING_BACK) {
                chosen = id;
                ch = c;
                break;
            }
        }
        if (chosen == null && ids.length > 0) {
            chosen = ids[0];
            ch = mgr.getCameraCharacteristics(chosen);
        }
        if (chosen == null) return false;
        StreamConfigurationMap map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map == null) return false;
        Size[] jpegs = map.getOutputSizes(ImageFormat.JPEG);
        Size[] previews = map.getOutputSizes(SurfaceTexture.class);
        if (jpegs == null || jpegs.length == 0 || previews == null || previews.length == 0) return false;
        jpegSize = chooseJpeg(jpegs, hd ? 13_000_000L : 5_200_000L);
        previewSize = choosePreview(previews, jpegSize);
        Integer so = ch.get(CameraCharacteristics.SENSOR_ORIENTATION);
        sensorOrientation = so == null ? 90 : so;
        Boolean fa = ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
        flashSupported = fa != null && fa;
        int[] af = ch.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
        afContinuous = contains(af, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
        afAuto = contains(af, CameraMetadata.CONTROL_AF_MODE_AUTO);
        float[] fl = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
        SizeF phys = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
        if (fl != null && fl.length > 0 && phys != null && phys.getWidth() > 0 && phys.getHeight() > 0) {
            focalPx = fl[0] / Math.max(phys.getWidth(), phys.getHeight()) * Math.max(jpegSize.getWidth(), jpegSize.getHeight());
        } else {
            focalPx = -1;
        }
        if (reader != null) {
            reader.close();
            reader = null;
        }
        reader = ImageReader.newInstance(jpegSize.getWidth(), jpegSize.getHeight(), ImageFormat.JPEG, 2);
        reader.setOnImageAvailableListener(imageListener, bg);
        cameraId = chosen;
        flashBtn.setVisibility(flashSupported ? View.VISIBLE : View.GONE);
        return true;
    }

    private static boolean contains(int[] arr, int v) {
        if (arr == null) return false;
        for (int a : arr) if (a == v) return true;
        return false;
    }

    private static long area(Size s) {
        return (long) s.getWidth() * s.getHeight();
    }

    private static boolean is43(Size s) {
        float r = Math.max(s.getWidth(), s.getHeight()) / (float) Math.min(s.getWidth(), s.getHeight());
        return Math.abs(r - 4f / 3f) < 0.02f;
    }

    private static Size chooseJpeg(Size[] sizes, long maxPx) {
        Size best = null;
        for (Size s : sizes) {
            if (area(s) > maxPx || !is43(s)) continue;
            if (best == null || area(s) > area(best)) best = s;
        }
        if (best == null) {
            for (Size s : sizes) {
                if (area(s) > maxPx) continue;
                if (best == null || area(s) > area(best)) best = s;
            }
        }
        if (best == null) {
            for (Size s : sizes) if (best == null || area(s) < area(best)) best = s;
        }
        return best;
    }

    private static Size choosePreview(Size[] sizes, Size jpeg) {
        float target = Math.max(jpeg.getWidth(), jpeg.getHeight()) / (float) Math.min(jpeg.getWidth(), jpeg.getHeight());
        Size best = null;
        for (Size s : sizes) {
            int L = Math.max(s.getWidth(), s.getHeight()), S = Math.min(s.getWidth(), s.getHeight());
            if (L > 1920 || S > 1440) continue;
            if (Math.abs(L / (float) S - target) > 0.02f) continue;
            if (best == null || area(s) > area(best)) best = s;
        }
        if (best == null) {
            float bestDiff = Float.MAX_VALUE;
            for (Size s : sizes) {
                int L = Math.max(s.getWidth(), s.getHeight()), S = Math.min(s.getWidth(), s.getHeight());
                if (L > 1920 || S > 1440) continue;
                float d = Math.abs(L / (float) S - target);
                if (best == null || d < bestDiff - 1e-3f || (Math.abs(d - bestDiff) <= 1e-3f && area(s) > area(best))) {
                    best = s;
                    bestDiff = d;
                }
            }
        }
        if (best == null) best = sizes[0];
        return best;
    }

    private final CameraDevice.StateCallback deviceCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice cd) {
            device = cd;
            releaseOpening();
            createSession();
        }

        @Override
        public void onDisconnected(CameraDevice cd) {
            releaseOpening();
            cd.close();
            if (device == cd) device = null;
        }

        @Override
        public void onError(CameraDevice cd, final int error) {
            releaseOpening();
            cd.close();
            if (device == cd) device = null;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (Ui.alive(CameraActivity.this)) Ui.toast(CameraActivity.this, "Lỗi máy ảnh (" + error + ")");
                }
            });
        }
    };

    private synchronized void releaseOpening() {
        if (opening) {
            opening = false;
            openLock.release();
        }
    }

    private void createSession() {
        try {
            SurfaceTexture st = texture.getSurfaceTexture();
            if (st == null || device == null || reader == null) return;
            st.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            previewSurface = new Surface(st);
            previewBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewBuilder.addTarget(previewSurface);
            device.createCaptureSession(Arrays.asList(previewSurface, reader.getSurface()), new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession s) {
                    if (device == null) return;
                    captureSession = s;
                    applyCommon(previewBuilder);
                    previewBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE);
                    state = STATE_PREVIEW;
                    repeat();
                }

                @Override
                public void onConfigureFailed(CameraCaptureSession s) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (Ui.alive(CameraActivity.this)) Ui.toast(CameraActivity.this, "Không cấu hình được máy ảnh");
                        }
                    });
                }
            }, bg);
        } catch (Throwable t) {
            // thiết bị đã đóng giữa chừng
        }
    }

    private void applyCommon(CaptureRequest.Builder b) {
        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
        if (afContinuous) b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
        else if (afAuto) b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO);
        b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON);
        if (flashSupported) b.set(CaptureRequest.FLASH_MODE, torch ? CameraMetadata.FLASH_MODE_TORCH : CameraMetadata.FLASH_MODE_OFF);
    }

    private void repeat() {
        try {
            if (captureSession != null && previewBuilder != null) captureSession.setRepeatingRequest(previewBuilder.build(), captureCallback, bg);
        } catch (Throwable ignored) {
        }
    }

    private final CameraCaptureSession.CaptureCallback captureCallback = new CameraCaptureSession.CaptureCallback() {
        private void process(CaptureResult r, boolean total) {
            if (state != STATE_WAITING_LOCK) return;
            Integer af = r.get(CaptureResult.CONTROL_AF_STATE);
            boolean timeout = SystemClock.uptimeMillis() - lockStart > 1500;
            if (af == null && !total && !timeout) return;
            if (af == null || timeout || af == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED
                    || af == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED
                    || (!afContinuous && !afAuto && af == CaptureResult.CONTROL_AF_STATE_INACTIVE)) {
                state = STATE_CAPTURING;
                captureStill();
            }
        }

        @Override
        public void onCaptureProgressed(CameraCaptureSession s, CaptureRequest req, CaptureResult partial) {
            process(partial, false);
        }

        @Override
        public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest req, TotalCaptureResult result) {
            process(result, true);
        }
    };

    private boolean takePicture() {
        if (captureSession == null || device == null || state != STATE_PREVIEW) return false;
        try {
            if (afContinuous || afAuto) {
                lockStart = SystemClock.uptimeMillis();
                state = STATE_WAITING_LOCK;
                previewBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START);
                captureSession.capture(previewBuilder.build(), captureCallback, bg);
                previewBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE);
            } else {
                state = STATE_CAPTURING;
                captureStill();
            }
            return true;
        } catch (Throwable t) {
            state = STATE_PREVIEW;
            return false;
        }
    }

    private void captureStill() {
        try {
            if (device == null || captureSession == null || reader == null) {
                state = STATE_PREVIEW;
                onCaptureError();
                return;
            }
            CaptureRequest.Builder b = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            b.addTarget(reader.getSurface());
            applyCommon(b);
            b.set(CaptureRequest.JPEG_ORIENTATION, (sensorOrientation + deviceOrientation + 360) % 360);
            b.set(CaptureRequest.JPEG_QUALITY, (byte) 94);
            captureSession.capture(b.build(), new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest req, TotalCaptureResult result) {
                    unlockFocus();
                }

                @Override
                public void onCaptureFailed(CameraCaptureSession s, CaptureRequest req, CaptureFailure failure) {
                    unlockFocus();
                    onCaptureError();
                }
            }, bg);
        } catch (Throwable t) {
            state = STATE_PREVIEW;
            onCaptureError();
        }
    }

    private void unlockFocus() {
        try {
            if (captureSession == null || previewBuilder == null) return;
            previewBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL);
            captureSession.capture(previewBuilder.build(), captureCallback, bg);
            previewBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE);
            state = STATE_PREVIEW;
            captureSession.setRepeatingRequest(previewBuilder.build(), captureCallback, bg);
        } catch (Throwable ignored) {
            state = STATE_PREVIEW;
        }
    }

    private final ImageReader.OnImageAvailableListener imageListener = new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(ImageReader r) {
            Image img = null;
            try {
                img = r.acquireNextImage();
                if (img == null) return;
                ByteBuffer buf = img.getPlanes()[0].getBuffer();
                byte[] bytes = new byte[buf.remaining()];
                buf.get(bytes);
                final File f = ImageUtil.newCaptureFile(CameraActivity.this);
                FileOutputStream fo = new FileOutputStream(f);
                try {
                    fo.write(bytes);
                } finally {
                    fo.close();
                }
                final double fpx = focalPx;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        onCaptured(f, fpx);
                    }
                });
            } catch (Throwable t) {
                onCaptureError();
            } finally {
                if (img != null) img.close();
            }
        }
    };

    private void closeCamera() {
        boolean acquired = false;
        try {
            acquired = openLock.tryAcquire(2500, TimeUnit.MILLISECONDS);
            state = STATE_IDLE;
            if (captureSession != null) {
                captureSession.close();
                captureSession = null;
            }
            if (device != null) {
                device.close();
                device = null;
            }
            if (reader != null) {
                reader.close();
                reader = null;
            }
            if (previewSurface != null) {
                previewSurface.release();
                previewSurface = null;
            }
        } catch (Throwable ignored) {
        } finally {
            synchronized (this) {
                if (acquired) openLock.release();
                else if (opening) {
                    // máy ảnh treo khi mở: bỏ qua để không chặn giao diện
                    opening = false;
                }
            }
        }
    }

    private void toggleTorch() {
        if (!flashSupported) return;
        torch = !torch;
        updateTopIcons();
        if (previewBuilder != null && captureSession != null) {
            applyCommon(previewBuilder);
            repeat();
        }
    }

    private void toggleHd() {
        hd = !hd;
        getSharedPreferences(Exporter.PREFS, 0).edit().putBoolean("hd", hd).apply();
        updateTopIcons();
        Ui.toast(this, hd ? "Chất lượng cao (HD): Bật" : "Chất lượng cao (HD): Tắt");
        if (busy) return;
        closeCamera();
        if (texture.isAvailable()) openCamera();
    }

    // =================================================================== chụp

    private void shoot() {
        if (busy) return;
        if (!hasCameraPermission()) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_PERM);
            return;
        }
        if (!takePicture()) {
            Ui.toast(this, "Máy ảnh chưa sẵn sàng");
            return;
        }
        busy = true;
        spinner.setVisibility(View.VISIBLE);
        flashView.setAlpha(0.65f);
        flashView.animate().alpha(0f).setDuration(260).start();
        // phòng khi máy ảnh không trả ảnh
        Ui.MAIN.postDelayed(watchdog, 9000);
    }

    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            if (busy && pendingPath == null && Ui.alive(CameraActivity.this)) {
                busy = false;
                spinner.setVisibility(View.GONE);
                state = STATE_PREVIEW;
                Ui.toast(CameraActivity.this, "Chụp không thành công, hãy thử lại");
            }
        }
    };

    private void onCaptureError() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (!Ui.alive(CameraActivity.this)) return;
                Ui.MAIN.removeCallbacks(watchdog);
                busy = false;
                spinner.setVisibility(View.GONE);
                Ui.toast(CameraActivity.this, "Chụp không thành công, hãy thử lại");
            }
        });
    }

    private void onCaptured(File f, double focal) {
        Ui.MAIN.removeCallbacks(watchdog);
        if (!Ui.alive(this) || !resumed) {
            f.delete();
            busy = false;
            if (spinner != null) spinner.setVisibility(View.GONE);
            return;
        }
        pendingPath = f.getPath();
        pendingFocal = focal;
        Intent i = new Intent(this, CropActivity.class);
        i.putExtra(CropActivity.EXTRA_PATH, pendingPath);
        i.putExtra(CropActivity.EXTRA_MODE, CropActivity.MODE_CAPTURE);
        startActivityForResult(i, REQ_CROP);
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_CROP) {
            busy = false;
            spinner.setVisibility(View.GONE);
            String path = data != null && data.getStringExtra(CropActivity.EXTRA_PATH) != null ? data.getStringExtra(CropActivity.EXTRA_PATH) : pendingPath;
            if (res == RESULT_OK && data != null && path != null && new File(path).exists()) {
                float[] quad = data.getFloatArrayExtra(CropActivity.EXTRA_QUAD);
                boolean auto = data.getBooleanExtra(CropActivity.EXTRA_AUTO, true);
                int[] size = data.getIntArrayExtra(CropActivity.EXTRA_SIZE);
                if (size == null) size = ImageUtil.orientedSize(path);
                acceptPage(new File(path), size, quad, auto, pendingFocal, true);
            } else if (pendingPath != null) {
                new File(pendingPath).delete();
            }
            pendingPath = null;
        } else if (req == REQ_EDITOR) {
            if (res == RESULT_OK) {
                if (newDoc && doc != null) {
                    Intent d = new Intent(this, DocumentActivity.class);
                    d.putExtra(DocumentActivity.EXTRA_DOC, doc.id);
                    startActivity(d);
                }
                setResult(RESULT_OK);
                finish();
            } else if (doc != null) {
                DocStore.Doc d2 = DocStore.load(this, doc.id);
                if (d2 != null) doc = d2;
                for (int i = sessionPages.size() - 1; i >= 0; i--) if (doc.page(sessionPages.get(i)) == null) sessionPages.remove(i);
                refreshBottom();
            }
        } else if (req == REQ_PICK) {
            if (res != RESULT_OK || data == null) return;
            List<Uri> uris = new ArrayList<>();
            ClipData cd = data.getClipData();
            if (cd != null) {
                for (int i = 0; i < cd.getItemCount(); i++) {
                    Uri u = cd.getItemAt(i).getUri();
                    if (u != null) uris.add(u);
                }
            } else if (data.getData() != null) {
                uris.add(data.getData());
            }
            importUris(uris);
        }
    }

    private void ensureDoc() {
        if (doc == null) {
            doc = DocStore.create(this, true);
            newDoc = true;
        }
    }

    /** Thêm (hoặc thay) trang từ ảnh đã chọn vùng cắt. */
    private boolean acceptPage(File file, int[] size, float[] quad, boolean auto, double focal, boolean fromCamera) {
        try {
            ensureDoc();
            if (retakeId != null) {
                DocStore.Page p = doc.page(retakeId);
                if (p == null) {
                    file.delete();
                    finish();
                    return false;
                }
                DocStore.replaceSource(doc, p, file, size, quad, auto, focal);
                DocStore.save(doc);
                setResult(RESULT_OK);
                finish();
                return false;
            }
            DocStore.Page p = DocStore.addPage(doc, file, size, quad, auto, focal);
            DocStore.save(doc);
            sessionPages.add(p.id);
            if (fromCamera) {
                if (batch) refreshBottom();
                else openEditor();
            }
            return true;
        } catch (IOException e) {
            Ui.toast(this, "Lỗi lưu ảnh: " + e.getMessage());
            return false;
        }
    }

    private void openEditor() {
        if (doc == null || doc.pages.isEmpty()) return;
        if (!resumed) skipNextOpen = true;
        Intent i = new Intent(this, EditorActivity.class);
        i.putExtra(EditorActivity.EXTRA_DOC, doc.id);
        i.putExtra(EditorActivity.EXTRA_FROM_CAMERA, true);
        int first = sessionPages.isEmpty() ? 0 : Math.max(0, doc.indexOf(sessionPages.get(0)));
        i.putExtra(EditorActivity.EXTRA_PAGE, first);
        startActivityForResult(i, REQ_EDITOR);
    }

    private void undoLast() {
        if (doc == null || sessionPages.isEmpty() || busy) return;
        String id = sessionPages.remove(sessionPages.size() - 1);
        DocStore.Page p = doc.page(id);
        if (p != null) {
            DocStore.removePage(doc, p);
            DocStore.save(doc);
        }
        refreshBottom();
    }

    private void pickImages() {
        if (busy) return;
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, retakeId == null);
        try {
            startActivityForResult(Intent.createChooser(i, "Chọn ảnh"), REQ_PICK);
        } catch (Throwable t) {
            Ui.toast(this, "Không mở được thư viện ảnh");
        }
    }

    private void importUris(final List<Uri> uris) {
        if (uris.isEmpty()) return;
        busy = true;
        spinner.setVisibility(View.VISIBLE);
        final Context app = getApplicationContext();
        Processor.IO.execute(new Runnable() {
            @Override
            public void run() {
                final List<File> files = new ArrayList<>();
                final List<Processor.Detection> dets = new ArrayList<>();
                int failed = 0;
                for (Uri u : uris) {
                    try {
                        File f = ImageUtil.importUri(app, u);
                        files.add(f);
                        dets.add(Processor.detect(f.getPath()));
                    } catch (Throwable t) {
                        failed++;
                    }
                }
                final int nFailed = failed;
                Ui.MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!Ui.alive(CameraActivity.this)) return;
                        busy = false;
                        spinner.setVisibility(View.GONE);
                        if (nFailed > 0) Ui.toast(CameraActivity.this, nFailed + " ảnh không đọc được");
                        boolean added = false;
                        for (int i = 0; i < files.size(); i++) {
                            Processor.Detection d = dets.get(i);
                            if (!acceptPage(files.get(i), d.size, d.quad, true, -1, false)) return;
                            added = true;
                        }
                        if (added) {
                            if (batch) refreshBottom();
                            openEditor();
                        }
                    }
                });
            }
        });
    }

    private void attemptClose() {
        if (busy) return;
        if (sessionPages.isEmpty()) {
            if (newDoc && doc != null && doc.pages.isEmpty()) DocStore.delete(doc);
            finish();
            return;
        }
        Ui.confirm(this, "Bỏ các trang vừa chụp?", sessionPages.size() + " trang vừa chụp sẽ bị xoá.", "Bỏ", new Runnable() {
            @Override
            public void run() {
                if (newDoc) {
                    DocStore.delete(doc);
                } else {
                    for (String id : sessionPages) {
                        DocStore.Page p = doc.page(id);
                        if (p != null) DocStore.removePage(doc, p);
                    }
                    DocStore.save(doc);
                }
                sessionPages.clear();
                finish();
            }
        });
    }

    @Override
    public void onBackPressed() {
        attemptClose();
    }

    @Override
    protected void onDestroy() {
        Ui.MAIN.removeCallbacks(watchdog);
        super.onDestroy();
    }

    // =================================================================== view phụ

    /** Khung xem trước giữ đúng tỷ lệ của luồng camera; mọi lớp con có cùng kích thước. */
    static class AspectFrame extends FrameLayout {
        private int ratioW = 3, ratioH = 4;

        AspectFrame(Context c) {
            super(c);
        }

        void setAspectRatio(final int w, final int h) {
            if (w <= 0 || h <= 0) return;
            post(new Runnable() {
                @Override
                public void run() {
                    ratioW = w;
                    ratioH = h;
                    requestLayout();
                }
            });
        }

        @Override
        protected void onMeasure(int wSpec, int hSpec) {
            int w = MeasureSpec.getSize(wSpec);
            int h = Math.round(w * ratioH / (float) ratioW);
            super.onMeasure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
        }
    }

    /** Lưới 3x3 hỗ trợ căn thẳng trang giấy. */
    static class GridOverlay extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        GridOverlay(Context c) {
            super(c);
            p.setColor(0x66FFFFFF);
            p.setStrokeWidth(Ui.dpf(1));
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            for (int i = 1; i <= 2; i++) {
                c.drawLine(w * i / 3f, 0, w * i / 3f, h, p);
                c.drawLine(0, h * i / 3f, w, h * i / 3f, p);
            }
        }
    }

    /** Nút chụp tròn trắng viền xanh. */
    static class ShutterButton extends View {
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean down;

        ShutterButton(Context c) {
            super(c);
            ring.setStyle(Paint.Style.STROKE);
            ring.setColor(Ui.ACCENT);
            ring.setStrokeWidth(Ui.dpf(4.5f));
            fill.setColor(Color.WHITE);
            setClickable(true);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) { down = true; invalidate(); }
            else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) { down = false; invalidate(); }
            return super.onTouchEvent(e);
        }

        @Override
        protected void onDraw(Canvas c) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            float r = Math.min(cx, cy) - ring.getStrokeWidth();
            c.drawCircle(cx, cy, r, ring);
            c.drawCircle(cx, cy, r - Ui.dpf(down ? 9 : 6.5f), fill);
        }
    }

    @SuppressWarnings("unused")
    private static float sp(Context c, float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, c.getResources().getDisplayMetrics());
    }
}
