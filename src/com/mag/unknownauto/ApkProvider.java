package com.mag.unknownauto;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Minimal content provider that hands the staged APK to the system package
 * installer. We serve a single file, {@code cacheDir/install.apk}, under
 * {@code content://com.mag.unknownauto.apk/install.apk}.
 *
 * <p>A custom provider (instead of androidx FileProvider) keeps this app on the
 * platform android.jar only, like the other apps in this repo — no Gradle, no
 * support libraries.
 */
public final class ApkProvider extends ContentProvider {

    static final String AUTHORITY = "com.mag.unknownauto.apk";
    static final String NAME = "install.apk";      // the APK being Play-attributed
    static final String SHIZUKU = "shizuku.apk";   // the downloaded Shizuku installer

    // Only these fixed names may be served (no path traversal).
    private static final Set<String> ALLOWED = new HashSet<>(Arrays.asList(NAME, SHIZUKU));

    static Uri uriFor() {
        return uriFor(NAME);
    }

    static Uri uriFor(String name) {
        return new Uri.Builder().scheme("content").authority(AUTHORITY).path(name).build();
    }

    private File fileFor(Uri uri) {
        String name = uri.getLastPathSegment();
        if (name == null || !ALLOWED.contains(name)) {
            throw new SecurityException("not served: " + name);
        }
        return new File(getContext().getCacheDir(), name);
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        // Read only: the installer must never write our cache.
        return ParcelFileDescriptor.open(fileFor(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        // The installer asks for DISPLAY_NAME and SIZE before reading.
        File f = fileFor(uri);
        String[] cols = projection != null ? projection
                : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor c = new MatrixCursor(cols);
        Object[] row = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) {
                row[i] = f.getName();
            } else if (OpenableColumns.SIZE.equals(cols[i])) {
                row[i] = f.length();
            } else {
                row[i] = null;
            }
        }
        c.addRow(row);
        return c;
    }

    @Override
    public String getType(Uri uri) {
        return "application/vnd.android.package-archive";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }
}
