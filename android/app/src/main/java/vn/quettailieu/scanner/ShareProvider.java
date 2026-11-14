package vn.quettailieu.scanner;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/** Cung cấp file trong thư mục cache/share cho ứng dụng khác (thay cho FileProvider). */
public class ShareProvider extends ContentProvider {
    static final String AUTHORITY = "vn.quettailieu.scanner.share";

    static File shareDir(Context c) {
        File d = new File(c.getCacheDir(), "share");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    static Uri uriFor(File f) {
        return new Uri.Builder().scheme("content").authority(AUTHORITY).appendPath(f.getName()).build();
    }

    private File fileFor(Uri uri) throws FileNotFoundException {
        Context c = getContext();
        if (c == null) throw new FileNotFoundException();
        String name = uri.getLastPathSegment();
        if (name == null || name.contains("/") || name.equals("..")) throw new FileNotFoundException();
        File dir = shareDir(c);
        File f = new File(dir, name);
        try {
            if (!f.getCanonicalPath().startsWith(dir.getCanonicalPath() + File.separator)) throw new FileNotFoundException();
        } catch (IOException e) {
            throw new FileNotFoundException();
        }
        if (!f.exists()) throw new FileNotFoundException(name);
        return f;
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public String getType(Uri uri) {
        String n = uri.getLastPathSegment();
        if (n == null) return "application/octet-stream";
        n = n.toLowerCase();
        if (n.endsWith(".pdf")) return "application/pdf";
        if (n.endsWith(".docx")) return WordExport.MIME;
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".png")) return "image/png";
        return "application/octet-stream";
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        return ParcelFileDescriptor.open(fileFor(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        File f;
        try {
            f = fileFor(uri);
        } catch (FileNotFoundException e) {
            return null;
        }
        if (projection == null) projection = new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        String[] cols = new String[projection.length];
        Object[] vals = new Object[projection.length];
        int n = 0;
        for (String col : projection) {
            if (OpenableColumns.DISPLAY_NAME.equals(col)) {
                cols[n] = OpenableColumns.DISPLAY_NAME;
                vals[n++] = f.getName();
            } else if (OpenableColumns.SIZE.equals(col)) {
                cols[n] = OpenableColumns.SIZE;
                vals[n++] = f.length();
            }
        }
        String[] c2 = new String[n];
        Object[] v2 = new Object[n];
        System.arraycopy(cols, 0, c2, 0, n);
        System.arraycopy(vals, 0, v2, 0, n);
        MatrixCursor mc = new MatrixCursor(c2, 1);
        mc.addRow(v2);
        return mc;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("read-only");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
