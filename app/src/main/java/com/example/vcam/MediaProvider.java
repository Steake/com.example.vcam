package com.example.vcam;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.UriMatcher;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.List;

/**
 * Exposes the manager-staged media, live {@link TransformConfig}, marker-switch
 * states, hardware {@link SensorProfiler.LensProfile}, and bi-directional host
 * camera telemetry over cross-app ContentProvider URIs &amp; {@code call()} RPCs.
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
 *         &mdash; returns a single-row cursor with columns {@code [uri, status]}
 *         where {@code status} is {@code "OK"} or {@code "UNSET"} so the hook
 *         can distinguish an intentionally cleared mapping from an unreachable provider.</li>
 *     <li>{@code content://com.example.vcam/config?pkg=X&facing=Y}
 *         &mdash; returns the resolved runtime config, marker flags, sensor profile,
 *         and cue points for the calling host package.</li>
 * </ul>
 *
 * <p>Supported {@link #call(String, String, Bundle)} RPC methods (for in-host overlay &amp; telemetry):
 * <ul>
 *     <li>{@code "report_telemetry"} &mdash; records {@link SensorProfiler.HostTelemetry}</li>
 *     <li>{@code "update_transform"} &mdash; saves live PTZ / aspect / pause / cue updates from the
 *         in-app Floating Overlay directly into the manager's SharedPreferences</li>
 *     <li>{@code "add_cue"} &mdash; bookmarks a timestamp CuePoint from the in-app overlay</li>
 * </ul>
 */
public class MediaProvider extends ContentProvider {

    public static final String AUTHORITY = "com.example.vcam";
    public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY);
    public static final Uri URI_IMAGE = Uri.parse("content://" + AUTHORITY + "/image");
    public static final Uri URI_VIDEO = Uri.parse("content://" + AUTHORITY + "/video");
    public static final Uri URI_RESOLVE = Uri.parse("content://" + AUTHORITY + "/resolve");
    public static final Uri URI_CONFIG = Uri.parse("content://" + AUTHORITY + "/config");

    public static final String METHOD_REPORT_TELEMETRY = "report_telemetry";
    public static final String METHOD_UPDATE_TRANSFORM = "update_transform";
    public static final String METHOD_SAVE_CUES = "save_cues";

    public static final String STATUS_OK = "OK";
    public static final String STATUS_UNSET = "UNSET";

    private static final int MATCH_IMAGE = 1;
    private static final int MATCH_VIDEO = 2;
    private static final int MATCH_IMAGE_ID = 3;
    private static final int MATCH_VIDEO_ID = 4;
    private static final int MATCH_RESOLVE = 5;
    private static final int MATCH_CONFIG = 6;

    private static final UriMatcher MATCHER = new UriMatcher(UriMatcher.NO_MATCH);
    static {
        MATCHER.addURI(AUTHORITY, "image", MATCH_IMAGE);
        MATCHER.addURI(AUTHORITY, "video", MATCH_VIDEO);
        MATCHER.addURI(AUTHORITY, "image/#", MATCH_IMAGE_ID);
        MATCHER.addURI(AUTHORITY, "video/#", MATCH_VIDEO_ID);
        MATCHER.addURI(AUTHORITY, "resolve", MATCH_RESOLVE);
        MATCHER.addURI(AUTHORITY, "config", MATCH_CONFIG);
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Nullable
    private File resolveFile(@NonNull Uri uri) {
        Context ctx = getContext();
        if (ctx == null) return null;
        switch (MATCHER.match(uri)) {
            case MATCH_IMAGE: {
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

    private void enforceCallerIsCameraApp() throws FileNotFoundException {
        Context ctx = getContext();
        if (ctx == null) return;
        int callingUid = Binder.getCallingUid();
        if (callingUid == android.os.Process.myUid()) return;
        String[] pkgs = ctx.getPackageManager().getPackagesForUid(callingUid);
        boolean granted = false;
        if (pkgs != null) {
            for (String pkg : pkgs) {
                if (ctx.getPackageManager().checkPermission(
                        android.Manifest.permission.CAMERA, pkg)
                        == PackageManager.PERMISSION_GRANTED) {
                    granted = true;
                    break;
                }
            }
        }
        if (!granted) {
            throw new FileNotFoundException("Caller uid=" + callingUid
                    + " lacks CAMERA permission — staged media not shared.");
        }
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
            case MATCH_CONFIG: return "vnd.android.cursor.item/vcam-config";
            default: return null;
        }
    }

    @Nullable
    @Override
    public Cursor query(@NonNull Uri uri, @Nullable String[] projection, @Nullable String selection,
                        @Nullable String[] selectionArgs, @Nullable String sortOrder) {
        try {
            enforceCallerIsCameraApp();
        } catch (FileNotFoundException denied) {
            return null;
        }
        int match = MATCHER.match(uri);
        if (match == MATCH_RESOLVE) {
            return resolveCursor(uri);
        }
        if (match == MATCH_CONFIG) {
            return configCursor(uri);
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
     * Execute the {@link MediaMappings#resolve} resolution chain and return a
     * single-row cursor with columns {@code ["uri", "status"]}.
     *
     * <p>Crucial fix: {@code status} is {@code "OK"} when a valid non-empty media file
     * resolves, or {@code "UNSET"} when the user intentionally cleared the slot so
     * {@code HookMain} knows to delete its stale local copy rather than keeping old media!
     */
    @NonNull
    private Cursor resolveCursor(@NonNull Uri uri) {
        Context ctx = getContext();
        MatrixCursor c = new MatrixCursor(new String[]{"uri", "status"});
        if (ctx == null) {
            c.addRow(new Object[]{null, STATUS_UNSET});
            return c;
        }
        String pkg = uri.getQueryParameter("pkg");
        String facing = uri.getQueryParameter("facing");
        String type = uri.getQueryParameter("type");
        if (TextUtils.isEmpty(pkg) || TextUtils.isEmpty(type)) {
            c.addRow(new Object[]{null, STATUS_UNSET});
            return c;
        }
        if (TextUtils.isEmpty(facing)) facing = MediaMappings.FACING_ANY;
        if (!MediaMappings.TYPE_IMAGE.equals(type) && !MediaMappings.TYPE_VIDEO.equals(type)) {
            c.addRow(new Object[]{null, STATUS_UNSET});
            return c;
        }
        String resolved = MediaMappings.resolve(ctx, pkg, facing, type);
        if (!TextUtils.isEmpty(resolved)) {
            // Verify the backing library file actually exists and is non-empty
            File libFile = libraryFileForUri(ctx, resolved, type);
            if (libFile == null || !libFile.exists() || libFile.length() == 0) {
                resolved = null;
            }
        }
        if (TextUtils.isEmpty(resolved)) {
            // Fall back to legacy staged file if present
            File legacy = MediaMappings.TYPE_IMAGE.equals(type)
                    ? MediaPaths.getImageTarget(ctx)
                    : MediaPaths.getVideoTarget(ctx);
            if (legacy.exists() && legacy.length() > 0) {
                resolved = (MediaMappings.TYPE_IMAGE.equals(type) ? URI_IMAGE : URI_VIDEO).toString();
            }
        }
        String status = !TextUtils.isEmpty(resolved) ? STATUS_OK : STATUS_UNSET;
        c.addRow(new Object[]{resolved, status});
        return c;
    }

    /**
     * Return a single-row cursor with the unified configuration for {@code (pkg, facing)}:
     * <ul>
     *   <li>{@code disabled}: 1 if module disabled (or excluded by package filter), else 0</li>
     *   <li>{@code loop}: 1 if video looping enabled, else 0</li>
     *   <li>{@code playAudio}: 1 if video audio enabled (not muted), else 0</li>
     *   <li>{@code forcePrivateDir}: 1 if private_dir forced, else 0</li>
     *   <li>{@code forceShowToast}: 1 if force_show enabled, else 0</li>
     *   <li>{@code disableToast}: 1 if toasts suppressed, else 0</li>
     *   <li>{@code transformJson}: serialized {@link TransformConfig}</li>
     *   <li>{@code sensorProfileJson}: serialized {@link SensorProfiler.LensProfile}</li>
     *   <li>{@code cuesJson}: serialized JSONArray of {@link TransformConfig.CuePoint}</li>
     *   <li>{@code librarySummaryJson}: JSONArray of available MediaLibrary items for hot-swap</li>
     * </ul>
     */
    @NonNull
    private Cursor configCursor(@NonNull Uri uri) {
        String[] cols = new String[]{
                "enabled", "mute", "loop", "toastEnabled", "inAppUiEnabled",
                "overlayHudEnabled", "passthroughMode", "virtualMicEnabled",
                "interceptCaptureIntents", "metadataPatchEnabled", "sensorGrainEnabled",
                "transformJson", "sensorProfileJson", "librarySummaryJson",
                "cuesJson", "allowedByFilter"
        };
        MatrixCursor c = new MatrixCursor(cols);
        Context ctx = getContext();
        if (ctx == null) return c;

        String pkg = uri.getQueryParameter("pkg");
        String facing = uri.getQueryParameter("facing");
        if (TextUtils.isEmpty(pkg)) pkg = MediaMappings.PKG_GLOBAL;
        if (TextUtils.isEmpty(facing)) facing = MediaMappings.FACING_BACK;

        SharedPreferences sp = ctx.getSharedPreferences(VCAMApp.PREFS, Context.MODE_PRIVATE);
        File dcim = new File(Environment.getExternalStorageDirectory(), "DCIM/Camera1");

        boolean markerDisabled = new File(dcim, "disable.jpg").exists()
                || sp.getBoolean("pref_disable_module", false);
        boolean pkgAllowed = isPackageAllowedByFilter(sp.getString(VCAMApp.KEY_PACKAGE_FILTER, ""), pkg);
        boolean enabled = !markerDisabled && pkgAllowed;

        boolean loop = sp.getBoolean(VCAMApp.KEY_LOOP_VIDEO, true);
        boolean markerPlaySound = new File(dcim, "no-silent.jpg").exists();
        boolean prefMute = sp.getBoolean(VCAMApp.KEY_MUTE_VIDEO, true);
        boolean mute = !markerPlaySound && prefMute;

        boolean forceShow = new File(dcim, "force_show.jpg").exists()
                || sp.getBoolean("pref_force_show", false);
        boolean noToast = new File(dcim, "no_toast.jpg").exists()
                || sp.getBoolean("pref_disable_toast", false);
        boolean toastEnabled = !noToast;

        TransformConfig tc = TransformConfig.resolve(ctx, pkg, facing);
        boolean overlayHud = sp.getBoolean(VCAMApp.KEY_OVERLAY_HUD_ENABLED, tc.overlayEnabled);
        boolean passthrough = sp.getBoolean(VCAMApp.KEY_PASSTHROUGH_MODE, tc.passthroughRealCamera);
        boolean virtualMic = sp.getBoolean(VCAMApp.KEY_VIRTUAL_MIC_ENABLED, tc.virtualMicEnabled);
        boolean interceptIntents = sp.getBoolean(VCAMApp.KEY_INTERCEPT_CAPTURE_INTENTS, tc.interceptCaptureIntents);
        boolean metadataPatch = sp.getBoolean(VCAMApp.KEY_METADATA_PATCH_ENABLED, tc.metadataPatchEnabled);
        boolean sensorGrain = sp.getBoolean(VCAMApp.KEY_SENSOR_GRAIN_ENABLED, tc.sensorGrainEnabled);

        SensorProfiler.LensProfile lp = SensorProfiler.getProfileForFacing(ctx, facing);

        String resolvedVidUri = MediaMappings.resolve(ctx, pkg, facing, MediaMappings.TYPE_VIDEO);
        List<TransformConfig.CuePoint> cues = TransformConfig.listCues(ctx, resolvedVidUri);
        JSONArray cuesArr = new JSONArray();
        for (TransformConfig.CuePoint cp : cues) {
            cuesArr.put(cp.toJson());
        }

        List<MediaLibrary.Entry> libEntries = MediaLibrary.list(ctx);
        JSONArray libArr = new JSONArray();
        try {
            for (MediaLibrary.Entry e : libEntries) {
                org.json.JSONObject eo = new org.json.JSONObject();
                eo.put("id", e.id);
                eo.put("type", e.type);
                eo.put("name", e.name);
                eo.put("uri", e.uri().toString());
                libArr.put(eo);
            }
        } catch (Throwable ignored) {}

        c.addRow(new Object[]{
                enabled ? 1 : 0,
                mute ? 1 : 0,
                loop ? 1 : 0,
                toastEnabled ? 1 : 0,
                forceShow ? 1 : 0,
                overlayHud ? 1 : 0,
                passthrough ? 1 : 0,
                virtualMic ? 1 : 0,
                interceptIntents ? 1 : 0,
                metadataPatch ? 1 : 0,
                sensorGrain ? 1 : 0,
                tc.toJson().toString(),
                lp.toJson().toString(),
                libArr.toString(),
                cuesArr.toString(),
                pkgAllowed ? 1 : 0
        });
        return c;
    }

    /**
     * Evaluate comma-separated package filter. Empty filter allows all packages.
     * Supports prefixing with {@code !} or {@code -} for blocklist entries.
     */
    public static boolean isPackageAllowedByFilter(@Nullable String filterRaw, @Nullable String pkg) {
        if (TextUtils.isEmpty(filterRaw) || TextUtils.isEmpty(pkg)) return true;
        if (BuildConfig.APPLICATION_ID.equals(pkg)) return true;
        String[] tokens = filterRaw.split(",");
        boolean hasAllowRules = false;
        boolean matchedAllow = false;
        for (String raw : tokens) {
            String t = raw.trim();
            if (t.isEmpty()) continue;
            if (t.startsWith("!") || t.startsWith("-")) {
                String blocked = t.substring(1).trim();
                if (!blocked.isEmpty() && pkg.equalsIgnoreCase(blocked)) {
                    return false;
                }
            } else {
                hasAllowRules = true;
                if (pkg.equalsIgnoreCase(t) || pkg.contains(t)) {
                    matchedAllow = true;
                }
            }
        }
        return !hasAllowRules || matchedAllow;
    }

    @Nullable
    @Override
    public Bundle call(@NonNull String method, @Nullable String arg, @Nullable Bundle extras) {
        try {
            enforceCallerIsCameraApp();
        } catch (FileNotFoundException denied) {
            return null;
        }
        Context ctx = getContext();
        if (ctx == null) return null;
        Bundle reply = new Bundle();

        if ("report_telemetry".equals(method) && extras != null) {
            String json = extras.getString("telemetry_json");
            if (!TextUtils.isEmpty(json)) {
                try {
                    SensorProfiler.HostTelemetry ht = SensorProfiler.HostTelemetry.fromJson(
                            new org.json.JSONObject(json));
                    if (ht != null) {
                        SensorProfiler.recordHostTelemetry(ctx, ht);
                        reply.putBoolean("ok", true);
                    }
                } catch (Throwable ignored) {}
            }
            return reply;
        }

        if ("update_transform".equals(method) && extras != null) {
            String pkg = extras.getString("pkg", MediaMappings.PKG_GLOBAL);
            String facing = extras.getString("facing", MediaMappings.FACING_ANY);
            String json = extras.getString("transform_json");
            if (!TextUtils.isEmpty(json)) {
                TransformConfig cfg = TransformConfig.fromJsonString(json);
                TransformConfig.saveScoped(ctx, pkg, facing, cfg);
                reply.putBoolean("ok", true);
            }
            return reply;
        }

        if ("select_library_media".equals(method) && extras != null) {
            String pkg = extras.getString("pkg", MediaMappings.PKG_GLOBAL);
            String facing = extras.getString("facing", MediaMappings.FACING_ANY);
            String type = extras.getString("type", MediaMappings.TYPE_VIDEO);
            String uri = extras.getString("uri");
            MediaMappings.setOne(ctx, pkg, facing, type, uri);
            reply.putBoolean("ok", true);
            return reply;
        }

        if ("save_preset".equals(method) && extras != null) {
            String name = extras.getString("name", "Preset");
            String json = extras.getString("transform_json");
            if (!TextUtils.isEmpty(json)) {
                TransformConfig.savePreset(ctx, name, TransformConfig.fromJsonString(json));
                reply.putBoolean("ok", true);
            }
            return reply;
        }

        if ("save_cues".equals(method) && extras != null) {
            String mediaKey = extras.getString("media_key");
            String cuesJson = extras.getString("cues_json");
            if (!TextUtils.isEmpty(cuesJson)) {
                try {
                    JSONArray arr = new JSONArray(cuesJson);
                    java.util.List<TransformConfig.CuePoint> list = new java.util.ArrayList<>();
                    for (int i = 0; i < arr.length(); i++) {
                        TransformConfig.CuePoint cp = TransformConfig.CuePoint.fromJson(arr.optJSONObject(i));
                        if (cp != null) list.add(cp);
                    }
                    TransformConfig.saveCues(ctx, mediaKey, list);
                    reply.putBoolean("ok", true);
                } catch (Throwable ignored) {}
            }
            return reply;
        }

        return reply;
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
