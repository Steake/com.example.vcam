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
        try {
            if (!SensorProfiler.hasSavedProfiles(this)) {
                SensorProfiler.profileAndSaveAllCameras(this);
            }
        } catch (Throwable ignored) {
        }
    }
}
