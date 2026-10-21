package com.example.vcam;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ImageFormat;
import android.media.Image;
import android.media.ImageWriter;
import android.os.Build;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.ByteBuffer;

import de.robv.android.xposed.XposedBridge;

/**
 * Feeds a single staged still image (or synthetic Test Pattern / QR Code frame)
 * into a Camera1 or Camera2 output {@link Surface} continuously at the target
 * sensor cadence (~30fps / 33ms when {@code fpsMatchEnabled} is active, or ~15fps).
 *
 * <p>Upgrades over the legacy implementation:
 * <ul>
 *   <li><b>Zero per-tick JPEG re-decoding</b>: Caches the transformed {@link Bitmap}
 *       in memory and draws it directly onto preview canvases with {@link FrameTransformPipeline}.</li>
 *   <li><b>First-class Aspect Ratio, Rotation &amp; PTZ</b>: Rebuilds the cached frame
 *       whenever {@link HookMain#getActiveTransformConfig()} or the source file changes.</li>
 *   <li><b>Temporal Sensor Grain &amp; EXIF Synthesis</b>: Applies subtle +/- 1..2 LSB
 *       temporal photon shot noise on YUV frames and injects hardware-matched EXIF APP1
 *       headers into JPEG captures via {@link ExifSynthesizer}.</li>
 * </ul>
 */
public class StaticBitmapFeeder {

    private static final long DEFAULT_INTERVAL_MS = 33L; // ~30fps sensor match

    /** Use this when feeding a preview (TextureView / SurfaceView / SurfaceTexture) surface. */
    public static final int FORMAT_PREVIEW = -1;

    private final Surface surface;
    private final int surfaceFormat;
    private final int width;
    private final int height;
    private final String bitmapPath;

    private Thread worker;
    private volatile boolean running;

    private ImageWriter writer;

    // Cached source & transformed representations, keyed on mtime + size + transform signature
    private Bitmap rawSourceBitmap;
    private int rawSourceRotation;
    private Bitmap cachedTransformedBitmap;
    private byte[] cachedJpeg;
    private byte[] cachedI420;
    private int[] argbScratch;
    private long cachedMtime = -1L;
    private long cachedSize = -1L;
    private String cachedTransformSig = "";

    private static volatile long cacheInvalidationCounter = 0L;
    private long observedInvalidationCounter = -1L;

    public StaticBitmapFeeder(Surface surface, int surfaceFormat, int width,
                              int height, String bitmapPath) {
        this.surface = surface;
        this.surfaceFormat = surfaceFormat;
        this.width = Math.max(2, width & ~1);
        this.height = Math.max(2, height & ~1);
        this.bitmapPath = bitmapPath;
    }

    public StaticBitmapFeeder(Surface surface, File bitmapFile, int width, int height) {
        this(surface, FORMAT_PREVIEW, width > 0 ? width : 1280, height > 0 ? height : 720,
                bitmapFile != null ? bitmapFile.getAbsolutePath() : "");
        start();
    }

    public boolean isRunning() {
        return running;
    }

    public static void invalidateAllCaches() {
        cacheInvalidationCounter++;
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        worker = new Thread(this::runLoop, "vcam-static-feeder");
        worker.setDaemon(true);
        worker.start();
    }

    public synchronized void stop() {
        running = false;
        if (worker != null) {
            worker.interrupt();
            worker = null;
        }
        if (writer != null) {
            try { writer.close(); } catch (Throwable ignored) {}
            writer = null;
        }
    }

    private void runLoop() {
        try {
            if (surfaceFormat != FORMAT_PREVIEW && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                writer = createWriter();
            }
        } catch (Throwable t) {
            XposedBridge.log("[VCAM][feeder] writer init failed: " + t);
            writer = null;
        }

        long nextTick = System.currentTimeMillis();
        while (running) {
            TransformConfig cfg = HookMain.getActiveTransformConfig();
            long intervalMs = cfg.fpsMatchEnabled ? DEFAULT_INTERVAL_MS : 66L;
            try {
                if (cfg.passthroughRealCamera) {
                    Thread.sleep(intervalMs);
                    continue;
                }
                if (!ensureCache(cfg)) {
                    Thread.sleep(intervalMs);
                    continue;
                }
                if (surfaceFormat == FORMAT_PREVIEW) {
                    drawOnCanvas(cfg);
                } else if (surfaceFormat == ImageFormat.JPEG) {
                    pushJpegFrame(cfg);
                } else if (surfaceFormat == ImageFormat.YUV_420_888
                        || surfaceFormat == ImageFormat.NV21
                        || surfaceFormat == ImageFormat.YV12) {
                    pushYuvFrame(cfg);
                } else {
                    drawOnCanvas(cfg);
                }
            } catch (InterruptedException ie) {
                return;
            } catch (Throwable t) {
                XposedBridge.log("[VCAM][feeder] tick: " + t);
            }
            nextTick += intervalMs;
            long sleep = nextTick - System.currentTimeMillis();
            if (sleep > 0) {
                try { Thread.sleep(sleep); } catch (InterruptedException ie) { return; }
            } else {
                nextTick = System.currentTimeMillis();
            }
        }
    }

    @Nullable
    private ImageWriter createWriter() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && surface != null && surface.isValid()) {
            return ImageWriter.newInstance(surface, /* maxImages */ 2);
        }
        return null;
    }

    /**
     * (Re)compute cached transformed Bitmap, JPEG, and I420 planes when the
     * backing file or active {@link TransformConfig} changes.
     */
    private boolean ensureCache(@NonNull TransformConfig cfg) {
        if (TransformConfig.SOURCE_TEST_PATTERN.equals(cfg.sourceMode)
                || TransformConfig.SOURCE_QR_CODE.equals(cfg.sourceMode)) {
            Bitmap synth = SyntheticFrameGenerator.renderFrame(width, height, cfg);
            if (synth == null) return false;
            cachedTransformedBitmap = synth;
            rebuildEncodedBuffers(synth, cfg);
            return true;
        }

        File f = new File(bitmapPath);
        if (!f.exists() || f.length() == 0) return false;
        long mtime = f.lastModified();
        long size = f.length();
        long inv = cacheInvalidationCounter;
        String sig = buildTransformSignature(cfg);

        if (cachedTransformedBitmap != null && !cachedTransformedBitmap.isRecycled()
                && cachedJpeg != null && cachedI420 != null
                && mtime == cachedMtime && size == cachedSize
                && inv == observedInvalidationCounter
                && sig.equals(cachedTransformSig)) {
            if (cfg.sensorGrainEnabled) {
                // Apply lightweight in-place luma grain so consecutive frames differ
                FrameTransformPipeline.applyLumaGrainInPlace(cachedI420, width, height);
            }
            return true;
        }

        if (rawSourceBitmap == null || rawSourceBitmap.isRecycled()
                || mtime != cachedMtime || size != cachedSize || inv != observedInvalidationCounter) {
            if (rawSourceBitmap != null && !rawSourceBitmap.isRecycled()) {
                rawSourceBitmap.recycle();
            }
            rawSourceBitmap = BitmapFactory.decodeFile(bitmapPath);
            rawSourceRotation = MediaPaths.imageExifRotation(f);
            if (rawSourceBitmap == null) return false;
        }

        cachedTransformedBitmap = FrameTransformPipeline.renderToBitmap(
                rawSourceBitmap, rawSourceRotation, width, height, cfg, cachedTransformedBitmap);
        rebuildEncodedBuffers(cachedTransformedBitmap, cfg);

        cachedMtime = mtime;
        cachedSize = size;
        observedInvalidationCounter = inv;
        cachedTransformSig = sig;
        return cachedJpeg != null && cachedI420 != null;
    }

    private void rebuildEncodedBuffers(@NonNull Bitmap transformed, @NonNull TransformConfig cfg) {
        SensorProfiler.LensProfile profile = HookMain.getActiveLensProfile();
        ExifSynthesizer.updatePhotometryFromBitmap(transformed, profile, HookMain.getCurrentFacing());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        transformed.compress(Bitmap.CompressFormat.JPEG, 94, out);
        byte[] rawJpeg = out.toByteArray();
        cachedJpeg = cfg.exifSynthEnabled
                ? ExifSynthesizer.injectExif(rawJpeg, width, height, profile)
                : rawJpeg;

        int ySize = width * height;
        if (argbScratch == null || argbScratch.length < ySize) {
            argbScratch = new int[ySize];
        }
        cachedI420 = FrameTransformPipeline.bitmapToI420(
                transformed, cfg.sensorGrainEnabled, argbScratch, cachedI420);
    }

    @NonNull
    private String buildTransformSignature(@NonNull TransformConfig c) {
        return c.aspectMode + "|" + c.rotationMode + "|" + c.mirrorH + "|" + c.mirrorV
                + "|" + c.zoom + "|" + c.panX + "|" + c.panY
                + "|" + c.wbKelvinShift + "|" + c.brightnessShift
                + "|" + c.sourceMode + "|" + c.qrPayload
                + "|" + width + "x" + height;
    }

    private void pushJpegFrame(@NonNull TransformConfig cfg) {
        ImageWriter w = writer;
        if (w == null || cachedJpeg == null) return;
        Image img = null;
        try {
            img = w.dequeueInputImage();
            if (img == null) return;
            img.setTimestamp(ExifSynthesizer.CURRENT_PHOTOMETRY.frameTimestampNs);
            ByteBuffer buf = img.getPlanes()[0].getBuffer();
            if (buf.capacity() < cachedJpeg.length) {
                XposedBridge.log("[VCAM][feeder] JPEG buffer too small: "
                        + buf.capacity() + " < " + cachedJpeg.length);
                return;
            }
            buf.rewind();
            buf.put(cachedJpeg);
            buf.limit(cachedJpeg.length);
            w.queueInputImage(img);
            img = null;
        } finally {
            if (img != null) {
                try { img.close(); } catch (Throwable ignored) {}
            }
        }
    }

    private void pushYuvFrame(@NonNull TransformConfig cfg) {
        ImageWriter w = writer;
        if (w == null || cachedI420 == null) return;
        Image img = null;
        try {
            img = w.dequeueInputImage();
            if (img == null) return;
            ExifSynthesizer.CURRENT_PHOTOMETRY.frameTimestampNs = android.os.SystemClock.elapsedRealtimeNanos();
            img.setTimestamp(ExifSynthesizer.CURRENT_PHOTOMETRY.frameTimestampNs);
            Image.Plane[] planes = img.getPlanes();
            int ySize = width * height;
            int uvSize = ySize / 4;
            copyPlane(planes[0], cachedI420, 0, width, height, 1);
            copyPlane(planes[1], cachedI420, ySize, width / 2, height / 2, 1);
            copyPlane(planes[2], cachedI420, ySize + uvSize, width / 2, height / 2, 1);
            w.queueInputImage(img);
            img = null;
        } finally {
            if (img != null) {
                try { img.close(); } catch (Throwable ignored) {}
            }
        }
    }

    public static void copyPlane(Image.Plane plane, byte[] src, int offset,
                                 int w, int h, int srcPixelStride) {
        ByteBuffer buf = plane.getBuffer();
        if (buf.isReadOnly()) return;
        int rowStride = plane.getRowStride();
        int pixelStride = plane.getPixelStride();
        buf.rewind();
        if (pixelStride == srcPixelStride && rowStride == w * srcPixelStride) {
            int len = Math.min(w * h * srcPixelStride, buf.remaining());
            buf.put(src, offset, len);
            return;
        }
        byte[] row = new byte[rowStride];
        for (int y = 0; y < h; y++) {
            int srcRow = offset + y * w * srcPixelStride;
            if (pixelStride == srcPixelStride) {
                int copyLen = Math.min(w * srcPixelStride, src.length - srcRow);
                if (copyLen > 0) System.arraycopy(src, srcRow, row, 0, copyLen);
            } else {
                for (int x = 0; x < w; x++) {
                    int sIdx = srcRow + x * srcPixelStride;
                    int dIdx = x * pixelStride;
                    if (sIdx < src.length && dIdx < row.length) {
                        row[dIdx] = src[sIdx];
                    }
                }
            }
            int toPut = Math.min(rowStride, buf.remaining());
            if (toPut > 0) buf.put(row, 0, toPut);
        }
    }

    private void drawOnCanvas(@NonNull TransformConfig cfg) {
        Surface s = surface;
        Bitmap bmp = cachedTransformedBitmap;
        if (s == null || !s.isValid() || bmp == null || bmp.isRecycled()) return;
        Canvas c = null;
        try {
            c = s.lockCanvas(null);
            if (c == null) return;
            // Draw onto the preview surface preserving aspect ratio / PTZ
            if (rawSourceBitmap != null && !rawSourceBitmap.isRecycled()
                    && TransformConfig.SOURCE_MEDIA.equals(cfg.sourceMode)) {
                FrameTransformPipeline.drawTransformedBitmap(
                        c, rawSourceBitmap, rawSourceRotation, cfg, null);
            } else {
                FrameTransformPipeline.drawTransformedBitmap(
                        c, bmp, 0, cfg, null);
            }
        } catch (Throwable t) {
            // Gracefully handle surfaces that have active hardware renderers (EGL/Vulkan) or are already locked
        } finally {
            if (c != null) {
                try { s.unlockCanvasAndPost(c); } catch (Throwable ignored) {}
            }
        }
    }
}
