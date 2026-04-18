package com.example.vcam;

import android.app.Application;

import com.google.android.material.color.DynamicColors;

/**
 * Application class: enables Material 3 dynamic color on Android 12+.
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

    @Override
    public void onCreate() {
        super.onCreate();
        DynamicColors.applyToActivitiesIfAvailable(this);
    }
}
