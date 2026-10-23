package com.example.vcam;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Environment;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Utility helpers for locating and writing the files the Xposed hook reads,
 * plus rotation-aware media resolution &amp; thumbnail decoding.
 *
 * <p>The hook contract (do not break): images are read from
 * {@code /DCIM/Camera1/1000.bmp} and videos from {@code /DCIM/Camera1/virtual.mp4}.
 * The user may override these targets via SharedPreferences.
 */
public final class MediaPaths {

    public static final String DEFAULT_DIR =
            Environment.getExternalStorageDirectory().getAbsolutePath() + "/DCIM/Camera1";
    public static final String DEFAULT_IMAGE_NAME = "1000.bmp";
    public static final String DEFAULT_VIDEO_NAME = "virtual.mp4";

    private MediaPaths() {}

    public static File defaultDir() {
        File dir = new File(DEFAULT_DIR);
        if (!dir.exists()) {
            // best effort, ignore failure
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return dir;
    }

    public static File getImageTarget(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(VCAMApp.PREFS, Context.MODE_PRIVATE);
        String custom = sp.getString(VCAMApp.KEY_CUSTOM_IMAGE_PATH, null);
        if (!TextUtils.isEmpty(custom)) {
            return new File(custom);
        }
        return new File(defaultDir(), DEFAULT_IMAGE_NAME);
    }

    public static File getVideoTarget(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(VCAMApp.PREFS, Context.MODE_PRIVATE);
        String custom = sp.getString(VCAMApp.KEY_CUSTOM_VIDEO_PATH, null);
        if (!TextUtils.isEmpty(custom)) {
            return new File(custom);
        }
        return new File(defaultDir(), DEFAULT_VIDEO_NAME);
    }

    /**
     * Resolves the effective staged image file: checks the global/default
     * {@link MediaMappings} entry in {@link MediaLibrary} first (which works
     * without {@code MANAGE_EXTERNAL_STORAGE}), then falls back to the legacy
     * {@code /sdcard/DCIM/Camera1/1000.bmp} file, and finally any imported
     * image in {@link MediaLibrary}.
     */
    @NonNull
    public static File resolveEffectiveImageFile(@NonNull Context ctx) {
        try {
            String uri = MediaMappings.resolve(ctx,
                    MediaMappings.PKG_GLOBAL, MediaMappings.FACING_ANY, MediaMappings.TYPE_IMAGE);
            if (!TextUtils.isEmpty(uri)) {
                MediaLibrary.Entry e = MediaLibrary.fromUri(ctx, uri);
                if (e != null) {
                    File libFile = MediaLibrary.fileFor(ctx, e);
                    if (libFile.exists() && libFile.length() > 0) {
                        return libFile;
                    }
                }
            }
        } catch (Throwable ignored) {}
        File legacy = getImageTarget(ctx);
        if (legacy.exists() && legacy.length() > 0) {
            return legacy;
        }
        try {
            java.util.List<MediaLibrary.Entry> all = MediaLibrary.list(ctx);
            for (MediaLibrary.Entry e : all) {
                if (MediaLibrary.TYPE_IMAGE.equals(e.type)) {
                    File f = MediaLibrary.fileFor(ctx, e);
                    if (f.exists() && f.length() > 0) return f;
                }
            }
        } catch (Throwable ignored) {}
        return legacy;
    }

    /**
     * Resolves the effective staged video file: checks the global/default
     * {@link MediaMappings} entry in {@link MediaLibrary} first, then falls
     * back to the legacy {@code /sdcard/DCIM/Camera1/virtual.mp4} file, and
     * finally any imported video in {@link MediaLibrary}.
     */
    @NonNull
    public static File resolveEffectiveVideoFile(@NonNull Context ctx) {
        try {
            String uri = MediaMappings.resolve(ctx,
                    MediaMappings.PKG_GLOBAL, MediaMappings.FACING_ANY, MediaMappings.TYPE_VIDEO);
            if (!TextUtils.isEmpty(uri)) {
                MediaLibrary.Entry e = MediaLibrary.fromUri(ctx, uri);
                if (e != null) {
                    File libFile = MediaLibrary.fileFor(ctx, e);
                    if (libFile.exists() && libFile.length() > 0) {
                        return libFile;
                    }
                }
            }
        } catch (Throwable ignored) {}
        File legacy = getVideoTarget(ctx);
        if (legacy.exists() && legacy.length() > 0) {
            return legacy;
        }
        try {
            java.util.List<MediaLibrary.Entry> all = MediaLibrary.list(ctx);
            for (MediaLibrary.Entry e : all) {
                if (MediaLibrary.TYPE_VIDEO.equals(e.type)) {
                    File f = MediaLibrary.fileFor(ctx, e);
                    if (f.exists() && f.length() > 0) return f;
                }
            }
        } catch (Throwable ignored) {}
        return legacy;
    }

    /**
     * Copy content from the given URI into the destination file.
     * Caller must ensure it owns the destination location.
     * @return number of bytes copied
     */
    public static long copyUriToFile(Context ctx, Uri src, File dst) throws Exception {
        ContentResolver resolver = ctx.getContentResolver();
        File parent = dst.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        long total = 0;
        try (InputStream in = resolver.openInputStream(src);
             OutputStream out = new FileOutputStream(dst)) {
            if (in == null) {
                throw new IllegalStateException("Unable to open input stream for " + src);
            }
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                total += n;
            }
            out.flush();
        }
        return total;
    }

    public static long copyFile(@NonNull File src, @NonNull File dst) throws Exception {
        File parent = dst.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        long total = 0;
        try (InputStream in = new java.io.FileInputStream(src);
             OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                total += n;
            }
            out.flush();
        }
        return total;
    }

    /**
     * Read the EXIF clockwise rotation (0, 90, 180, 270) of an image file.
     */
    public static int imageExifRotation(@Nullable File f) {
        if (f == null || !f.exists()) return 0;
        try {
            ExifInterface exif = new ExifInterface(f.getAbsolutePath());
            int orient = exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            switch (orient) {
                case ExifInterface.ORIENTATION_ROTATE_90:
                case ExifInterface.ORIENTATION_TRANSPOSE:
                    return 90;
                case ExifInterface.ORIENTATION_ROTATE_180:
                case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                    return 180;
                case ExifInterface.ORIENTATION_ROTATE_270:
                case ExifInterface.ORIENTATION_TRANSVERSE:
                    return 270;
                default:
                    return 0;
            }
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /**
     * Read the MP4 container rotation metadata (0, 90, 180, 270) of a video file.
     */
    public static int videoRotation(@Nullable File f) {
        if (f == null || !f.exists()) return 0;
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(f.getAbsolutePath());
            String rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            if (rot != null) {
                return ((Integer.parseInt(rot.trim()) % 360) + 360) % 360;
            }
        } catch (Throwable ignored) {
        } finally {
            try { r.release(); } catch (Throwable ignored) {}
        }
        return 0;
    }

    /** Decodes a bounded thumbnail (upright according to EXIF) so we never blow up memory. */
    @Nullable
    public static Bitmap decodeImageThumb(File f, int maxSide) {
        if (f == null || !f.exists()) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        int sample = 1;
        int longer = Math.max(o.outWidth, o.outHeight);
        while (longer / sample > maxSide) sample *= 2;
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = sample;
        Bitmap bmp = BitmapFactory.decodeFile(f.getAbsolutePath(), o2);
        if (bmp == null) return null;
        int rot = imageExifRotation(f);
        if (rot != 0) {
            try {
                Matrix m = new Matrix();
                m.postRotate(rot);
                Bitmap rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
                if (rotated != bmp) bmp.recycle();
                return rotated;
            } catch (Throwable ignored) {}
        }
        return bmp;
    }

    /** Extracts the first frame of a video as a thumbnail bitmap. */
    @Nullable
    public static Bitmap decodeVideoFrame(File f) {
        if (f == null || !f.exists()) return null;
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(f.getAbsolutePath());
            return r.getFrameAtTime();
        } catch (Throwable t) {
            return null;
        } finally {
            try { r.release(); } catch (Throwable ignored) {}
        }
    }

    /**
     * Returns the display resolution {@code [width, height]} of an image,
     * swapping width and height when EXIF rotation is 90 or 270 degrees.
     */
    @Nullable
    public static int[] imageResolution(File f) {
        if (f == null || !f.exists()) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        int rot = imageExifRotation(f);
        if (rot == 90 || rot == 270) {
            return new int[]{o.outHeight, o.outWidth};
        }
        return new int[]{o.outWidth, o.outHeight};
    }

    /**
     * Returns the display resolution {@code [width, height]} of a video,
     * honoring {@link MediaMetadataRetriever#METADATA_KEY_VIDEO_ROTATION}
     * so portrait phone videos (e.g. 1920x1080 rotated 90 deg) are reported
     * and rendered as 1080x1920 rather than stretched landscape!
     */
    @Nullable
    public static int[] videoResolution(File f) {
        if (f == null || !f.exists()) return null;
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(f.getAbsolutePath());
            String wStr = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            String hStr = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            String rotStr = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            if (wStr == null || hStr == null) return null;
            int w = Integer.parseInt(wStr.trim());
            int h = Integer.parseInt(hStr.trim());
            int rot = 0;
            if (rotStr != null) {
                try { rot = ((Integer.parseInt(rotStr.trim()) % 360) + 360) % 360; } catch (Throwable ignored) {}
            }
            if (rot == 90 || rot == 270) {
                return new int[]{h, w};
            }
            return new int[]{w, h};
        } catch (Throwable t) {
            return null;
        } finally {
            try { r.release(); } catch (Throwable ignored) {}
        }
    }

    /**
     * Returns {@code [rawWidth, rawHeight, rotationDegrees, durationMs]} for a video file.
     */
    @NonNull
    public static int[] inspectVideoMetrics(@Nullable File f) {
        if (f == null || !f.exists()) return new int[]{1280, 720, 0, 0};
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(f.getAbsolutePath());
            int w = parseSafe(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH), 1280);
            int h = parseSafe(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT), 720);
            int rot = ((parseSafe(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION), 0) % 360) + 360) % 360;
            int dur = parseSafe(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION), 0);
            return new int[]{w, h, rot, dur};
        } catch (Throwable t) {
            return new int[]{1280, 720, 0, 0};
        } finally {
            try { r.release(); } catch (Throwable ignored) {}
        }
    }

    private static int parseSafe(@Nullable String s, int fallback) {
        if (s == null) return fallback;
        try { return Integer.parseInt(s.trim()); } catch (Throwable t) { return fallback; }
    }

    public static String humanBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(java.util.Locale.US, "%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(java.util.Locale.US, "%.1f MB", mb);
        return String.format(java.util.Locale.US, "%.2f GB", mb / 1024.0);
    }

    /**
     * Heuristic check for whether an Xposed/LSPosed framework is active in this process.
     * The host process is rarely hooked, but this gives the user an indication on some roots.
     */
    public static boolean isXposedLikelyActive() {
        try {
            String prop = System.getProperty("vxp.loader");
            if (prop != null) return true;
        } catch (Throwable ignored) {}
        try {
            Class.forName("de.robv.android.xposed.XposedBridge");
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
