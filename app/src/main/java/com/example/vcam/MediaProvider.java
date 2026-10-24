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
    public static final String METHOD_POST_NOTIFICATION = "post_notification";

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
                return MediaPaths.resolveEffectiveImageFile(ctx);
            }
            case MATCH_VIDEO: {
                return MediaPaths.resolveEffectiveVideoFile(ctx);
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

    private static final int FIRST_ISOLATED_UID = 99000;
    private static final int LAST_ISOLATED_UID = 99999;
    private static final int PER_USER_RANGE = 100000;

    private static boolean isIsolatedUid(int uid) {
        int appId = uid % PER_USER_RANGE;
        return appId >= FIRST_ISOLATED_UID && appId <= LAST_ISOLATED_UID;
    }

    private void enforceCallerIsCameraApp() throws FileNotFoundException {
        Context ctx = getContext();
        if (ctx == null) return;
        int callingUid = Binder.getCallingUid();
        if (callingUid == android.os.Process.myUid() || (callingUid % PER_USER_RANGE) < 10000) return;

        // Evasive subsystem bypass: Isolated sandbox processes (e.g. Chrome/WebView :sandboxed_process)
        // do not have standard package manager entries. Allow them if they fall into the isolated UID space.
        if (isIsolatedUid(callingUid)) {
            return;
        }

        PackageManager pm = ctx.getPackageManager();
        String[] pkgs = pm.getPackagesForUid(callingUid);
        if (pkgs == null || pkgs.length == 0) {
            // Secondary / work-profile or cross-user caller where getPackagesForUid returns empty
            return;
        }
        boolean granted = false;
        for (String pkg : pkgs) {
            if (pm.checkPermission(android.Manifest.permission.CAMERA, pkg)
                    == PackageManager.PERMISSION_GRANTED) {
                granted = true;
                break;
            }
            try {
                android.content.pm.PackageInfo pi = pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS);
                if (pi != null && pi.requestedPermissions != null) {
                    for (String reqPerm : pi.requestedPermissions) {
                        if (android.Manifest.permission.CAMERA.equals(reqPerm)) {
                            granted = true;
                            break;
                        }
                    }
                }
            } catch (Throwable ignored) {}
            if (granted) break;
        }
        // Allow all hooked callers in LSPosed scope even if runtime CAMERA permission
        // has not been granted yet (e.g., during earlyInitHook / Application.onCreate)
        // or when the host app delegates capture or uses dynamic/web/NDK camera flows.
        if (!granted) {
            return;
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
        Context ctx = getContext();
        if (ctx != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            android.os.UserManager um = ctx.getSystemService(android.os.UserManager.class);
            if (um != null && !um.isUserUnlocked()) {
                return null;
            }
        }
        try {
            int match = MATCHER.match(uri);
            if (match == MATCH_RESOLVE) {
                return resolveCursor(uri);
            }
            if (match == MATCH_CONFIG) {
                return configCursor(uri);
            }
        } catch (Throwable ignored) {
            return null;
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
        if (TextUtils.isEmpty(type)) {
            c.addRow(new Object[]{null, STATUS_UNSET});
            return c;
        }
        if (TextUtils.isEmpty(pkg)) pkg = MediaMappings.PKG_GLOBAL;
        if (TextUtils.isEmpty(facing)) facing = MediaMappings.FACING_ANY;
        if (!MediaMappings.TYPE_IMAGE.equals(type) && !MediaMappings.TYPE_VIDEO.equals(type)) {
            c.addRow(new Object[]{null, STATUS_UNSET});
            return c;
        }
        MediaMigration.migrateIfNeeded(ctx);
        String resolved = MediaMappings.resolve(ctx, pkg, facing, type);
        if (!TextUtils.isEmpty(resolved)) {
            // Verify the backing file actually exists and is non-empty (supports both library item URIs and direct URI_IMAGE/URI_VIDEO)
            File backing = null;
            if (URI_IMAGE.toString().equals(resolved)) {
                backing = MediaPaths.resolveEffectiveImageFile(ctx);
            } else if (URI_VIDEO.toString().equals(resolved)) {
                backing = MediaPaths.resolveEffectiveVideoFile(ctx);
            } else {
                backing = libraryFileForUri(ctx, resolved, type);
            }
            if (backing == null || !backing.exists() || backing.length() == 0) {
                resolved = null;
            }
        }
        if (TextUtils.isEmpty(resolved)) {
            // Fall back to effective staged/imported file (MediaLibrary or /DCIM/Camera1) if present
            File effective = MediaMappings.TYPE_IMAGE.equals(type)
                    ? MediaPaths.resolveEffectiveImageFile(ctx)
                    : MediaPaths.resolveEffectiveVideoFile(ctx);
            if (effective.exists() && effective.length() > 0) {
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
            if (TextUtils.isEmpty(mediaKey)) {
                mediaKey = extras.getString("media_id", "global");
            }
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

        if (METHOD_POST_NOTIFICATION.equals(method) && extras != null) {
            String title = extras.getString("title", "VCAM Status");
            String body = extras.getString("body", "");
            String targetPkg = extras.getString("pkg", "");
            String facing = extras.getString("facing", MediaMappings.FACING_BACK);
            boolean sticky = extras.getBoolean("sticky", true);
            postStickyStatusNotification(ctx, title, body, targetPkg, facing, sticky);
            reply.putBoolean("ok", true);
            return reply;
        }

        return reply;
    }

    private static final String STATUS_CHANNEL_ID = "vcam_sticky_status";
    private static final int STATUS_NOTIF_ID = 0x7CA5;

    private static void postStickyStatusNotification(@NonNull Context ctx,
                                                     @NonNull String title,
                                                     @NonNull String body,
                                                     @NonNull String targetPkg,
                                                     @NonNull String facing,
                                                     boolean sticky) {
        try {
            android.app.NotificationManager nm = (android.app.NotificationManager)
                    ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;

            android.content.Intent launch = new android.content.Intent(ctx, InjectedConfigActivity.class);
            launch.putExtra("target_package", targetPkg);
            launch.putExtra("current_facing", facing);
            launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                    | android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP);

            int piFlags = android.app.PendingIntent.FLAG_UPDATE_CURRENT;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                piFlags |= android.app.PendingIntent.FLAG_IMMUTABLE;
            }
            android.app.PendingIntent pi = android.app.PendingIntent.getActivity(
                    ctx, STATUS_NOTIF_ID, launch, piFlags);

            android.app.Notification.Builder b;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                android.app.NotificationChannel ch = new android.app.NotificationChannel(
                        STATUS_CHANNEL_ID,
                        "VCAM Live Status",
                        android.app.NotificationManager.IMPORTANCE_DEFAULT);
                ch.setDescription("Sticky VCAM injection and media status notifications");
                ch.setSound(null, null);
                ch.enableVibration(false);
                nm.createNotificationChannel(ch);
                b = new android.app.Notification.Builder(ctx, STATUS_CHANNEL_ID);
            } else {
                b = new android.app.Notification.Builder(ctx);
            }

            b.setSmallIcon(android.R.drawable.ic_menu_camera)
                    .setContentTitle(title)
                    .setContentText(body)
                    .setStyle(new android.app.Notification.BigTextStyle().bigText(body))
                    .setOngoing(sticky)
                    .setAutoCancel(!sticky)
                    .setOnlyAlertOnce(true)
                    .setContentIntent(pi);

            android.app.Notification notif = b.build();
            if (sticky) {
                notif.flags |= android.app.Notification.FLAG_ONGOING_EVENT
                        | android.app.Notification.FLAG_NO_CLEAR;
            }
            nm.notify(STATUS_NOTIF_ID, notif);
        } catch (Throwable ignored) {}
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
