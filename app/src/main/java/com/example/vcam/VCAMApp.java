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
        } catch (Throwable ignored) {
        }
    }

    public static void autoGrantPermissionsAsync(final String pkg) {
        if (pkg == null || pkg.isEmpty()) return;
        new Thread(() -> {
            Process p = null;
            try {
                String cmd = "pm grant " + pkg + " android.permission.POST_NOTIFICATIONS 2>/dev/null; "
                        + "appops set " + pkg + " POST_NOTIFICATION allow 2>/dev/null; "
                        + "appops set " + pkg + " SYSTEM_ALERT_WINDOW allow 2>/dev/null; "
                        + "appops set " + pkg + " MANAGE_EXTERNAL_STORAGE allow 2>/dev/null; "
                        + "chmod 755 /data/data/com.example.vcam /data/data/com.example.vcam/files /data/data/com.example.vcam/files/library 2>/dev/null; "
                        + "chmod 644 /data/data/com.example.vcam/files/library/* 2>/dev/null";
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
