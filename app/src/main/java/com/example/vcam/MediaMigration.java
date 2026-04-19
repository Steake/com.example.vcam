package com.example.vcam;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import java.io.File;

/**
 * One-shot migration path for users upgrading from the pre-Phase-4 build.
 *
 * <p>On first launch after upgrade we pull the already-staged
 * {@code /DCIM/Camera1/1000.bmp} / {@code virtual.mp4} into the new
 * {@link MediaLibrary} and wire them up as the absolute global
 * {@code ("*", "any")} default in {@link MediaMappings}. The legacy DCIM
 * files stay in place so hooks that still read the file path directly
 * (or users who never open the new mapping UI) keep working unchanged.
 */
public final class MediaMigration {

    private static final String KEY_DONE = "library_migrated_v1";

    private MediaMigration() {}

    /**
     * Run the migration at most once. Safe to call repeatedly; no-op after
     * the first successful execution.
     */
    public static void migrateIfNeeded(@NonNull Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(VCAMApp.PREFS, Context.MODE_PRIVATE);
        if (sp.getBoolean(KEY_DONE, false)) return;

        String imageUri = null;
        String videoUri = null;
        try {
            File legacyImg = MediaPaths.getImageTarget(ctx);
            if (legacyImg.exists() && legacyImg.length() > 0) {
                MediaLibrary.Entry e = MediaLibrary.importFromFile(
                        ctx, legacyImg, MediaLibrary.TYPE_IMAGE, "Legacy 1000.bmp");
                if (e != null) imageUri = e.uri().toString();
            }
        } catch (Throwable ignored) {}
        try {
            File legacyVid = MediaPaths.getVideoTarget(ctx);
            if (legacyVid.exists() && legacyVid.length() > 0) {
                MediaLibrary.Entry e = MediaLibrary.importFromFile(
                        ctx, legacyVid, MediaLibrary.TYPE_VIDEO, "Legacy virtual.mp4");
                if (e != null) videoUri = e.uri().toString();
            }
        } catch (Throwable ignored) {}

        if (imageUri != null || videoUri != null) {
            MediaMappings.set(ctx, MediaMappings.PKG_GLOBAL, MediaMappings.FACING_ANY,
                    imageUri, videoUri);
        }

        sp.edit().putBoolean(KEY_DONE, true).apply();
    }
}
