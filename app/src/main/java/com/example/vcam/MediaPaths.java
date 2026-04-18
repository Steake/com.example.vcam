package com.example.vcam;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Environment;
import android.text.TextUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Utility helpers for locating and writing the files the Xposed hook reads.
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

    /** Decodes a bounded thumbnail so we never blow up memory. */
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
        return BitmapFactory.decodeFile(f.getAbsolutePath(), o2);
    }

    /** Extracts the first frame of a video as a thumbnail bitmap. */
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

    public static int[] imageResolution(File f) {
        if (f == null || !f.exists()) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        return new int[]{o.outWidth, o.outHeight};
    }

    public static int[] videoResolution(File f) {
        if (f == null || !f.exists()) return null;
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(f.getAbsolutePath());
            String w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            String h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            if (w == null || h == null) return null;
            return new int[]{Integer.parseInt(w), Integer.parseInt(h)};
        } catch (Throwable t) {
            return null;
        } finally {
            try { r.release(); } catch (Throwable ignored) {}
        }
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
            // LSPosed/EdXposed expose de.robv.android.xposed.XposedBridge in hooked apps,
            // but in the manager app we check whether the framework ever loaded our jar
            // by looking at the system property or class at runtime.
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
