package com.example.vcam;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.UriMatcher;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Exposes the manager-staged {@code 1000.bmp} / {@code virtual.mp4} over
 * {@code content://com.example.vcam/image} and {@code content://com.example.vcam/video}
 * so the VCAM hook — running inside arbitrary host processes — can stream the
 * injected media regardless of whether the host was granted storage
 * permission. This is the preferred path on Android 11+ where hosts almost
 * never hold {@code MANAGE_EXTERNAL_STORAGE} and therefore can't read the
 * legacy {@code /DCIM/Camera1/} location directly.
 *
 * <p>The legacy DCIM path remains a supported fallback so pre-existing
 * manual-setup workflows keep working.
 */
public class MediaProvider extends ContentProvider {

    public static final String AUTHORITY = "com.example.vcam";
    public static final Uri URI_IMAGE = Uri.parse("content://" + AUTHORITY + "/image");
    public static final Uri URI_VIDEO = Uri.parse("content://" + AUTHORITY + "/video");

    private static final int MATCH_IMAGE = 1;
    private static final int MATCH_VIDEO = 2;

    private static final UriMatcher MATCHER = new UriMatcher(UriMatcher.NO_MATCH);
    static {
        MATCHER.addURI(AUTHORITY, "image", MATCH_IMAGE);
        MATCHER.addURI(AUTHORITY, "video", MATCH_VIDEO);
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Nullable
    private File resolveFile(@NonNull Uri uri) {
        if (getContext() == null) return null;
        switch (MATCHER.match(uri)) {
            case MATCH_IMAGE: return MediaPaths.getImageTarget(getContext());
            case MATCH_VIDEO: return MediaPaths.getVideoTarget(getContext());
            default: return null;
        }
    }

    @Nullable
    @Override
    public ParcelFileDescriptor openFile(@NonNull Uri uri, @NonNull String mode)
            throws FileNotFoundException {
        enforceCallerIsCameraApp();
        File f = resolveFile(uri);
        if (f == null || !f.exists() || f.length() == 0) {
            throw new FileNotFoundException("No staged media for " + uri);
        }
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    /**
     * The provider has to be exported so the VCAM hook can read it from
     * arbitrary host processes (host UIDs aren't known in advance and aren't
     * signed by us, ruling out allow-lists and signature permissions). As a
     * privacy guardrail, require the calling UID to hold
     * {@code android.permission.CAMERA}: the only legitimate consumer is a
     * camera-using app that VCAM is injecting into. Manager-side callers
     * (our own UID) are always allowed.
     */
    private void enforceCallerIsCameraApp() throws FileNotFoundException {
        Context ctx = getContext();
        if (ctx == null) return;
        int callingUid = Binder.getCallingUid();
        if (callingUid == android.os.Process.myUid()) return;
        if (ctx.getPackageManager().checkPermission(
                android.Manifest.permission.CAMERA,
                firstPackageForUid(ctx, callingUid))
                != PackageManager.PERMISSION_GRANTED) {
            throw new FileNotFoundException("Caller uid=" + callingUid
                    + " lacks CAMERA permission — staged media not shared.");
        }
    }

    @NonNull
    private static String firstPackageForUid(@NonNull Context ctx, int uid) {
        String[] pkgs = ctx.getPackageManager().getPackagesForUid(uid);
        return (pkgs != null && pkgs.length > 0) ? pkgs[0] : "";
    }

    @Nullable
    @Override
    public String getType(@NonNull Uri uri) {
        switch (MATCHER.match(uri)) {
            case MATCH_IMAGE: return "image/*";
            case MATCH_VIDEO: return "video/mp4";
            default: return null;
        }
    }

    @Nullable
    @Override
    public Cursor query(@NonNull Uri uri, @Nullable String[] projection, @Nullable String selection,
                        @Nullable String[] selectionArgs, @Nullable String sortOrder) {
        File f = resolveFile(uri);
        if (f == null) return null;
        String[] cols = projection != null
                ? projection
                : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor c = new MatrixCursor(cols);
        Object[] row = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) {
                row[i] = f.getName();
            } else if (OpenableColumns.SIZE.equals(cols[i])) {
                row[i] = f.exists() ? f.length() : 0L;
            }
        }
        c.addRow(row);
        return c;
    }

    @Nullable
    @Override
    public Uri insert(@NonNull Uri uri, @Nullable ContentValues values) { return null; }

    @Override
    public int delete(@NonNull Uri uri, @Nullable String selection, @Nullable String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(@NonNull Uri uri, @Nullable ContentValues values,
                      @Nullable String selection, @Nullable String[] selectionArgs) {
        return 0;
    }
}
