package ru.argeda.ai;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/** Serves photos taken by the camera from the app cache dir (cache/camera/<name>). */
public class CameraFileProvider extends ContentProvider {
    static final String AUTHORITY = "ru.argeda.ai.files";

    static Uri uriFor(File f) { return uriFor("camera", f); }
    static Uri uriFor(String kind, File f) {
        return new Uri.Builder().scheme("content").authority(AUTHORITY).appendPath(kind).appendPath(f.getName()).build();
    }

    private File fileFor(Uri uri) throws FileNotFoundException {
        String kind = uri.getPathSegments().size() == 2 ? uri.getPathSegments().get(0) : "";
        if (!"camera".equals(kind) && !"share".equals(kind) && !"update".equals(kind)) throw new FileNotFoundException();
        File dir = new File(getContext().getCacheDir(), kind);
        File f = new File(dir, uri.getLastPathSegment());
        try {
            if (!f.getCanonicalPath().startsWith(dir.getCanonicalPath() + File.separator)) throw new FileNotFoundException();
        } catch (java.io.IOException e) { throw new FileNotFoundException(); }
        return f;
    }

    @Override public boolean onCreate() { return true; }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = fileFor(uri);
        int m = "r".equals(mode) ? ParcelFileDescriptor.MODE_READ_ONLY
                : ParcelFileDescriptor.MODE_READ_WRITE | ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_TRUNCATE;
        return ParcelFileDescriptor.open(f, m);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String sel, String[] args, String order) {
        File f;
        try { f = fileFor(uri); } catch (FileNotFoundException e) { return null; }
        MatrixCursor c = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        c.addRow(new Object[]{f.getName(), f.length()});
        return c;
    }

    @Override public String getType(Uri uri) {
        String n = uri.getLastPathSegment() == null ? "" : uri.getLastPathSegment().toLowerCase();
        String ext = n.contains(".") ? n.substring(n.lastIndexOf('.') + 1) : "";
        String t = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        return t == null ? "application/octet-stream" : t;
    }
    @Override public Uri insert(Uri uri, ContentValues v) { return null; }
    @Override public int delete(Uri uri, String s, String[] a) { return 0; }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
}
