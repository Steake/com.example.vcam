package com.example.vcam;

import android.app.Application;

import com.google.android.material.color.DynamicColors;

/**
 * Application class: enables Material 3 dynamic color on Android 12+
 * and initializes hardware camera sensor profiles if not yet captured.
 */
public class VCAMApp extends Application {
    public static final String PREFS = "vcam_prefs";

    // Keys for SharedPreferences
    public static final String KEY_LAST_IMAGE_URI = "last_image_uri";
    public static final String KEY_LAST_VIDEO_URI = "last_video_uri";
    public static final String KEY_ONBOARDING_DONE = "onboarding_done";
    public static final String KEY_CUSTOM_IMAGE_PATH = "custom_image_path";
    public static final String KEY_CUSTOM_VIDEO_PATH = "custom_video_path";
    public static final String KEY_LOOP_VIDEO = "loop_video";
    public static final String KEY_MUTE_VIDEO = "mute_video";
    public static final String KEY_PACKAGE_FILTER = "package_filter";

    public static final String KEY_OVERLAY_HUD_ENABLED = "overlay_hud_enabled";
    public static final String KEY_PASSTHROUGH_MODE = "passthrough_mode";
    public static final String KEY_VIRTUAL_MIC_ENABLED = "virtual_mic_enabled";
    public static final String KEY_INTERCEPT_CAPTURE_INTENTS = "intercept_capture_intents";
    public static final String KEY_METADATA_PATCH_ENABLED = "metadata_patch_enabled";
    public static final String KEY_SENSOR_GRAIN_ENABLED = "sensor_grain_enabled";

    public static final String SHARED_BRIDGE_DIR = "/data/local/tmp/.vcam_shared";
    private static final java.util.concurrent.atomic.AtomicBoolean sSyncRunning =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private static final java.util.concurrent.atomic.AtomicBoolean sSyncPending =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    @Override
    public void onCreate() {
        super.onCreate();
        DynamicColors.applyToActivitiesIfAvailable(this);
        ensureWorldReadableLibraryDirs(this);
        autoGrantPermissionsAsync(getPackageName());
        try {
            if (!SensorProfiler.hasSavedProfiles(this)) {
                SensorProfiler.profileAndSaveAllCameras(this);
            }
        } catch (Throwable ignored) {
        }
        syncSharedStagingAsync(this);
    }

    public static void ensureWorldReadableLibraryDirs(android.content.Context ctx) {
        if (ctx == null) return;
        try {
            java.io.File dataDir = ctx.getDataDir();
            if (dataDir != null) {
                //noinspection ResultOfMethodCallIgnored
                dataDir.setExecutable(true, false);
                //noinspection ResultOfMethodCallIgnored
                dataDir.setReadable(true, false);
            }
            java.io.File filesDir = ctx.getFilesDir();
            if (filesDir != null) {
                //noinspection ResultOfMethodCallIgnored
                filesDir.mkdirs();
                //noinspection ResultOfMethodCallIgnored
                filesDir.setExecutable(true, false);
                //noinspection ResultOfMethodCallIgnored
                filesDir.setReadable(true, false);
            }
            java.io.File libDir = MediaLibrary.libraryDir(ctx);
            //noinspection ResultOfMethodCallIgnored
            libDir.setExecutable(true, false);
            //noinspection ResultOfMethodCallIgnored
            libDir.setReadable(true, false);
            java.io.File[] children = libDir.listFiles();
            if (children != null) {
                for (java.io.File f : children) {
                    if (f != null && f.isFile()) {
                        //noinspection ResultOfMethodCallIgnored
                        f.setReadable(true, false);
                    }
                }
            }
            syncSharedStagingAsync(ctx);
        } catch (Throwable ignored) {
        }
    }

    public static void syncSharedStagingAsync(final android.content.Context ctx) {
        if (ctx == null) return;
        final android.content.Context appCtx = ctx.getApplicationContext() != null
                ? ctx.getApplicationContext() : ctx;
        sSyncPending.set(true);
        if (!sSyncRunning.compareAndSet(false, true)) {
            return;
        }
        new Thread(() -> {
            try {
                while (sSyncPending.getAndSet(false)) {
                    performSharedStagingSync(appCtx);
                }
            } catch (Throwable ignored) {
            } finally {
                sSyncRunning.set(false);
            }
        }, "vcam-shared-sync").start();
    }

    private static void performSharedStagingSync(android.content.Context ctx) {
        try {
            java.io.File bridgeDir = new java.io.File(ctx.getFilesDir(), "shared_bridge");
            //noinspection ResultOfMethodCallIgnored
            bridgeDir.mkdirs();
            //noinspection ResultOfMethodCallIgnored
            bridgeDir.setExecutable(true, false);
            //noinspection ResultOfMethodCallIgnored
            bridgeDir.setReadable(true, false);

            android.content.SharedPreferences sp = ctx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE);
            java.io.File dcim = new java.io.File(android.os.Environment.getExternalStorageDirectory(), "DCIM/Camera1");
            boolean markerDisabled = new java.io.File(dcim, "disable.jpg").exists()
                    || sp.getBoolean("pref_disable_module", false);
            boolean enabled = !markerDisabled;
            boolean loop = sp.getBoolean(KEY_LOOP_VIDEO, true);
            boolean markerPlaySound = new java.io.File(dcim, "no-silent.jpg").exists();
            boolean prefMute = sp.getBoolean(KEY_MUTE_VIDEO, true);
            boolean mute = !markerPlaySound && prefMute;
            boolean forceShow = new java.io.File(dcim, "force_show.jpg").exists()
                    || sp.getBoolean("pref_force_show", false);
            boolean noToast = new java.io.File(dcim, "no_toast.jpg").exists()
                    || sp.getBoolean("pref_disable_toast", false);
            boolean toastEnabled = !noToast;

            TransformConfig tc = TransformConfig.loadGlobal(ctx);
            boolean overlayHud = sp.getBoolean(KEY_OVERLAY_HUD_ENABLED, tc.overlayEnabled);
            boolean passthrough = sp.getBoolean(KEY_PASSTHROUGH_MODE, tc.passthroughRealCamera);
            boolean virtualMic = sp.getBoolean(KEY_VIRTUAL_MIC_ENABLED, tc.virtualMicEnabled);
            boolean interceptIntents = sp.getBoolean(KEY_INTERCEPT_CAPTURE_INTENTS, tc.interceptCaptureIntents);
            boolean metadataPatch = sp.getBoolean(KEY_METADATA_PATCH_ENABLED, tc.metadataPatchEnabled);
            boolean sensorGrain = sp.getBoolean(KEY_SENSOR_GRAIN_ENABLED, tc.sensorGrainEnabled);

            java.io.File effVid = MediaPaths.resolveEffectiveVideoFile(ctx);
            java.io.File effImg = MediaPaths.resolveEffectiveImageFile(ctx);

            java.io.File bridgeVid = new java.io.File(bridgeDir, "virtual.mp4");
            java.io.File bridgeImg = new java.io.File(bridgeDir, "1000.bmp");
            if (effVid != null && effVid.exists() && effVid.length() > 0) {
                copyIfChanged(effVid, bridgeVid);
                //noinspection ResultOfMethodCallIgnored
                bridgeVid.setReadable(true, false);
            } else if (bridgeVid.exists()) {
                //noinspection ResultOfMethodCallIgnored
                bridgeVid.delete();
            }
            if (effImg != null && effImg.exists() && effImg.length() > 0) {
                copyIfChanged(effImg, bridgeImg);
                //noinspection ResultOfMethodCallIgnored
                bridgeImg.setReadable(true, false);
            } else if (bridgeImg.exists()) {
                //noinspection ResultOfMethodCallIgnored
                bridgeImg.delete();
            }

            org.json.JSONObject cfgObj = new org.json.JSONObject();
            cfgObj.put("enabled", enabled);
            cfgObj.put("mute", mute);
            cfgObj.put("loop", loop);
            cfgObj.put("toastEnabled", toastEnabled);
            cfgObj.put("inAppUiEnabled", forceShow);
            cfgObj.put("overlayHudEnabled", overlayHud);
            cfgObj.put("passthroughMode", passthrough);
            cfgObj.put("virtualMicEnabled", virtualMic);
            cfgObj.put("interceptCaptureIntents", interceptIntents);
            cfgObj.put("metadataPatchEnabled", metadataPatch);
            cfgObj.put("sensorGrainEnabled", sensorGrain);
            cfgObj.put("packageFilter", sp.getString(KEY_PACKAGE_FILTER, ""));
            cfgObj.put("transformJson", tc.toJson().toString());
            cfgObj.put("sensorProfileBackJson", SensorProfiler.getProfileForFacing(ctx, MediaMappings.FACING_BACK).toJson().toString());
            cfgObj.put("sensorProfileFrontJson", SensorProfiler.getProfileForFacing(ctx, MediaMappings.FACING_FRONT).toJson().toString());
            cfgObj.put("hasVideo", bridgeVid.exists() && bridgeVid.length() > 0);
            cfgObj.put("hasImage", bridgeImg.exists() && bridgeImg.length() > 0);

            java.io.File cfgFile = new java.io.File(bridgeDir, "config.json");
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(cfgFile)) {
                fos.write(cfgObj.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                fos.flush();
            }
            //noinspection ResultOfMethodCallIgnored
            cfgFile.setReadable(true, false);

            java.util.Set<String> targetPkgs = new java.util.HashSet<>();
            targetPkgs.add("com.trycrew.crew");
            try {
                for (MediaMappings.Mapping m : MediaMappings.all(ctx)) {
                    if (m.pkg != null && !m.pkg.isEmpty() && !"*".equals(m.pkg)
                            && !m.pkg.contains("webview")) {
                        targetPkgs.add(m.pkg);
                    }
                }
            } catch (Throwable ignored) {}

            StringBuilder sb = new StringBuilder();
            sb.append("mkdir -p ").append(SHARED_BRIDGE_DIR).append(" 2>/dev/null; ");
            sb.append("cp -f ").append(cfgFile.getAbsolutePath()).append(" ").append(SHARED_BRIDGE_DIR).append("/config.json 2>/dev/null; ");
            if (bridgeVid.exists() && bridgeVid.length() > 0) {
                sb.append("cp -f ").append(bridgeVid.getAbsolutePath()).append(" ").append(SHARED_BRIDGE_DIR).append("/virtual.mp4 2>/dev/null; ");
            } else {
                sb.append("rm -f ").append(SHARED_BRIDGE_DIR).append("/virtual.mp4 2>/dev/null; ");
            }
            if (bridgeImg.exists() && bridgeImg.length() > 0) {
                sb.append("cp -f ").append(bridgeImg.getAbsolutePath()).append(" ").append(SHARED_BRIDGE_DIR).append("/1000.bmp 2>/dev/null; ");
            } else {
                sb.append("rm -f ").append(SHARED_BRIDGE_DIR).append("/1000.bmp 2>/dev/null; ");
            }
            sb.append("chmod 755 /data/local/tmp ").append(SHARED_BRIDGE_DIR)
                    .append(" /data/data/com.example.vcam /data/data/com.example.vcam/files /data/data/com.example.vcam/files/library /data/data/com.example.vcam/files/shared_bridge 2>/dev/null; ");
            sb.append("chmod 644 ").append(SHARED_BRIDGE_DIR).append("/* /data/data/com.example.vcam/files/library/* /data/data/com.example.vcam/files/shared_bridge/* 2>/dev/null; ");
            sb.append("chcon -R u:object_r:apk_data_file:s0 ").append(SHARED_BRIDGE_DIR)
                    .append(" /data/data/com.example.vcam/files/library /data/data/com.example.vcam/files/shared_bridge 2>/dev/null; ");
            for (String tPkg : targetPkgs) {
                sb.append("pm grant ").append(tPkg).append(" android.permission.READ_EXTERNAL_STORAGE 2>/dev/null; ");
                sb.append("pm grant ").append(tPkg).append(" android.permission.WRITE_EXTERNAL_STORAGE 2>/dev/null; ");
                sb.append("pm grant ").append(tPkg).append(" android.permission.READ_MEDIA_VIDEO 2>/dev/null; ");
                sb.append("pm grant ").append(tPkg).append(" android.permission.READ_MEDIA_IMAGES 2>/dev/null; ");
                sb.append("appops set ").append(tPkg).append(" MANAGE_EXTERNAL_STORAGE allow 2>/dev/null; ");
                sb.append("appops set ").append(tPkg).append(" READ_MEDIA_VIDEO allow 2>/dev/null; ");
                sb.append("appops set ").append(tPkg).append(" READ_MEDIA_IMAGES allow 2>/dev/null; ");
                sb.append("appops set ").append(tPkg).append(" LEGACY_STORAGE allow 2>/dev/null; ");
            }

            Process p = null;
            try {
                p = new ProcessBuilder("su", "-c", sb.toString()).redirectErrorStream(true).start();
                p.waitFor();
            } catch (Throwable ignored) {
            } finally {
                if (p != null) {
                    try { p.destroy(); } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void copyIfChanged(java.io.File src, java.io.File dst) {
        if (src == null || dst == null || !src.exists()) return;
        if (src.getAbsolutePath().equals(dst.getAbsolutePath())) return;
        if (dst.exists() && dst.length() == src.length()
                && Math.abs(dst.lastModified() - src.lastModified()) < 2000L) {
            return;
        }
        java.io.File tmp = new java.io.File(dst.getAbsolutePath() + ".tmp");
        try (java.io.FileInputStream in = new java.io.FileInputStream(src);
             java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            out.flush();
        } catch (Throwable t) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        tmp.renameTo(dst);
        //noinspection ResultOfMethodCallIgnored
        dst.setLastModified(src.lastModified());
    }

    public static void autoGrantPermissionsAsync(final String pkg) {
        if (pkg == null || pkg.isEmpty() || pkg.contains("webview")) return;
        new Thread(() -> {
            Process p = null;
            try {
                String cmd = "pm grant " + pkg + " android.permission.POST_NOTIFICATIONS 2>/dev/null; "
                        + "pm grant " + pkg + " android.permission.READ_EXTERNAL_STORAGE 2>/dev/null; "
                        + "pm grant " + pkg + " android.permission.WRITE_EXTERNAL_STORAGE 2>/dev/null; "
                        + "pm grant " + pkg + " android.permission.READ_MEDIA_VIDEO 2>/dev/null; "
                        + "pm grant " + pkg + " android.permission.READ_MEDIA_IMAGES 2>/dev/null; "
                        + "appops set " + pkg + " POST_NOTIFICATION allow 2>/dev/null; "
                        + "appops set " + pkg + " SYSTEM_ALERT_WINDOW allow 2>/dev/null; "
                        + "appops set " + pkg + " MANAGE_EXTERNAL_STORAGE allow 2>/dev/null; "
                        + "appops set " + pkg + " READ_MEDIA_VIDEO allow 2>/dev/null; "
                        + "appops set " + pkg + " READ_MEDIA_IMAGES allow 2>/dev/null; "
                        + "appops set " + pkg + " LEGACY_STORAGE allow 2>/dev/null; "
                        + "chmod 755 /data/data/com.example.vcam /data/data/com.example.vcam/files /data/data/com.example.vcam/files/library 2>/dev/null; "
                        + "chmod 644 /data/data/com.example.vcam/files/library/* 2>/dev/null; "
                        + "chcon -R u:object_r:apk_data_file:s0 /data/data/com.example.vcam/files/library 2>/dev/null";
                p = new ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start();
                p.waitFor();
            } catch (Throwable ignored) {
            } finally {
                if (p != null) {
                    try { p.destroy(); } catch (Throwable ignored) {}
                }
            }
        }, "vcam-perm-grant").start();
    }
}
