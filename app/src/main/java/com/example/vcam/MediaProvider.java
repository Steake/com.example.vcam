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
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Exposes the manager-staged media over cross-app ContentProvider URIs so
 * the VCAM hook — running inside arbitrary host processes — can stream the
 * injected media regardless of whether the host was granted storage
 * permission. This is the preferred path on Android 11+ where hosts almost
 * never hold {@code MANAGE_EXTERNAL_STORAGE} and therefore can't read the
 * legacy {@code /DCIM/Camera1/} location directly.
 *
 * <p>Supported URIs:
 * <ul>
 *     <li>{@code content://com.example.vcam/image} &mdash; legacy global
 *         default image (falls back to {@link MediaPaths#getImageTarget}).</li>
 *     <li>{@code content://com.example.vcam/video} &mdash; legacy global
 *         default video.</li>
 *     <li>{@code content://com.example.vcam/image/{id}} &mdash; a
 *         {@link MediaLibrary} image entry.</li>
 *     <li>{@code content://com.example.vcam/video/{id}} &mdash; a
 *         {@link MediaLibrary} video entry.</li>
 *     <li>{@code content://com.example.vcam/resolve?pkg=X&facing=Y&type=image|video}
 *         &mdash; returns a single-row cursor with column {@code uri}
 *         containing the concrete content URI per
 *         {@link MediaMappings#resolve MediaMappings.resolve}'s resolution
 *         order (or empty row if nothing matched).</li>
 * </ul>
 *
 * <p>The legacy DCIM path remains a supported fallback so pre-existing
 * manual-setup workflows keep working.
 */
public class MediaProvider extends ContentProvider {

    public static final String AUTHORITY = "com.example.vcam";
    public static final Uri URI_IMAGE = Uri.parse("content://" + AUTHORITY + "/image");
    public static final Uri URI_VIDEO = Uri.parse("content://" + AUTHORITY + "/video");
    public static final Uri URI_RESOLVE = Uri.parse("content://" + AUTHORITY + "/resolve");

    private static final int MATCH_IMAGE = 1;
    private static final int MATCH_VIDEO = 2;
    private static final int MATCH_IMAGE_ID = 3;
    private static final int MATCH_VIDEO_ID = 4;
    private static final int MATCH_RESOLVE = 5;

    private static final UriMatcher MATCHER = new UriMatcher(UriMatcher.NO_MATCH);
    static {
        MATCHER.addURI(AUTHORITY, "image", MATCH_IMAGE);
        MATCHER.addURI(AUTHORITY, "video", MATCH_VIDEO);
        MATCHER.addURI(AUTHORITY, "image/#", MATCH_IMAGE_ID);
        MATCHER.addURI(AUTHORITY, "video/#", MATCH_VIDEO_ID);
        MATCHER.addURI(AUTHORITY, "resolve", MATCH_RESOLVE);
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    /**
     * Resolve a URI down to a concrete file the provider can stream.
     * For the legacy {@code /image} / {@code /video} roots we first
     * consult the global default mapping, then fall back to the staged
     * DCIM file so existing manual workflows still work.
     */
    @Nullable
    private File resolveFile(@NonNull Uri uri) {
        Context ctx = getContext();
        if (ctx == null) return null;
        switch (MATCHER.match(uri)) {
            case MATCH_IMAGE: {
                // Prefer the mapped global default, if set.
                String mapped = MediaMappings.resolve(ctx,
                        MediaMappings.PKG_GLOBAL, MediaMappings.FACING_ANY,
                        MediaMappings.TYPE_IMAGE);
                File lib = libraryFileForUri(ctx, mapped, MediaLibrary.TYPE_IMAGE);
                if (lib != null && lib.exists() && lib.length() > 0) return lib;
                return MediaPaths.getImageTarget(ctx);
            }
            case MATCH_VIDEO: {
                String mapped = MediaMappings.resolve(ctx,
                        MediaMappings.PKG_GLOBAL, MediaMappings.FACING_ANY,
                        MediaMappings.TYPE_VIDEO);
                File lib = libraryFileForUri(ctx, mapped, MediaLibrary.TYPE_VIDEO);
                if (lib != null && lib.exists() && lib.length() > 0) return lib;
                return MediaPaths.getVideoTarget(ctx);
            }
            case MATCH_IMAGE_ID: {
                int id = parseIdOrZero(uri);
                return id > 0 ? MediaLibrary.fileFor(ctx, MediaLibrary.TYPE_IMAGE, id) : null;
            }
            case MATCH_VIDEO_ID: {
                int id = parseIdOrZero(uri);
                return id > 0 ? MediaLibrary.fileFor(ctx, MediaLibrary.TYPE_VIDEO, id) : null;
            }
            default: return null;
        }
    }

    @Nullable
    private static File libraryFileForUri(@NonNull Context ctx, @Nullable String uri,
                                          @NonNull String expectedType) {
        if (TextUtils.isEmpty(uri)) return null;
        MediaLibrary.Entry e = MediaLibrary.fromUri(ctx, uri);
        if (e == null || !expectedType.equals(e.type)) return null;
        return MediaLibrary.fileFor(ctx, e);
    }

    private static int parseIdOrZero(@NonNull Uri uri) {
        try {
            String last = uri.getLastPathSegment();
            return last == null ? 0 : Integer.parseInt(last);
        } catch (NumberFormatException e) {
            return 0;
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
            case MATCH_IMAGE:
            case MATCH_IMAGE_ID: return "image/*";
            case MATCH_VIDEO:
            case MATCH_VIDEO_ID: return "video/mp4";
            case MATCH_RESOLVE: return "vnd.android.cursor.item/vcam-resolve";
            default: return null;
        }
    }

    @Nullable
    @Override
    public Cursor query(@NonNull Uri uri, @Nullable String[] projection, @Nullable String selection,
                        @Nullable String[] selectionArgs, @Nullable String sortOrder) {
        // Same caller gate as openFile(): mapping state / file sizes are
        // not privacy-safe to leak to arbitrary processes, so require the
        // caller to hold CAMERA (i.e. a plausibly-hooked camera app) or
        // be our own UID.
        try {
            enforceCallerIsCameraApp();
        } catch (FileNotFoundException denied) {
            return null;
        }
        int match = MATCHER.match(uri);
        if (match == MATCH_RESOLVE) {
            return resolveCursor(uri);
        }
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

    /**
     * Execute the {@link MediaMappings#resolve MediaMappings.resolve}
     * resolution chain and return a single-row cursor with column
     * {@code uri} containing the winning content URI, or an empty row if
     * nothing matched. The hook reads this to decide which concrete
     * media entry to stream into its staging file.
     */
    @NonNull
    private Cursor resolveCursor(@NonNull Uri uri) {
        Context ctx = getContext();
        MatrixCursor c = new MatrixCursor(new String[]{"uri"});
        if (ctx == null) {
            c.addRow(new Object[]{null});
            return c;
        }
        String pkg = uri.getQueryParameter("pkg");
        String facing = uri.getQueryParameter("facing");
        String type = uri.getQueryParameter("type");
        if (TextUtils.isEmpty(pkg) || TextUtils.isEmpty(type)) {
            c.addRow(new Object[]{null});
            return c;
        }
        if (TextUtils.isEmpty(facing)) facing = MediaMappings.FACING_ANY;
        if (!MediaMappings.TYPE_IMAGE.equals(type) && !MediaMappings.TYPE_VIDEO.equals(type)) {
            c.addRow(new Object[]{null});
            return c;
        }
        String resolved = MediaMappings.resolve(ctx, pkg, facing, type);
        if (TextUtils.isEmpty(resolved)) {
            // Fall back to the legacy "/image" or "/video" URI if the
            // manager-staged DCIM file is populated — keeps legacy-only users
            // working even when no explicit mapping was configured.
            File legacy = MediaMappings.TYPE_IMAGE.equals(type)
                    ? MediaPaths.getImageTarget(ctx)
                    : MediaPaths.getVideoTarget(ctx);
            if (legacy.exists() && legacy.length() > 0) {
                resolved = (MediaMappings.TYPE_IMAGE.equals(type) ? URI_IMAGE : URI_VIDEO).toString();
            }
        }
        c.addRow(new Object[]{resolved});
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
