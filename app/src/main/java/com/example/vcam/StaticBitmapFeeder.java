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
 * Feeds a single staged still image (or synthetic Test Pattern / QR Code frame,
 * or live decoded video frame fallback for JPEG still captures) into a Camera1
 * or Camera2 output {@link Surface}.
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
        this.width = Math.max(2, (width > 0 ? width : 1280) & ~1);
        this.height = Math.max(2, (height > 0 ? height : 720) & ~1);
        this.bitmapPath = bitmapPath != null ? bitmapPath : "";
    }

    public StaticBitmapFeeder(Surface surface, File bitmapFile, int width, int height) {
        this(surface, FORMAT_PREVIEW, width > 0 ? width : 1280, height > 0 ? height : 720,
                bitmapFile != null ? bitmapFile.getAbsolutePath() : "");
        start();
    }

    public StaticBitmapFeeder(Surface surface, File bitmapFile, int width, int height, int surfaceFormat) {
        this(surface, surfaceFormat, width > 0 ? width : 1280, height > 0 ? height : 720,
                bitmapFile != null ? bitmapFile.getAbsolutePath() : "");
        start();
    }

    public boolean isRunning() {
        return running;
    }

    public int getSurfaceFormat() {
        return surfaceFormat;
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

    /**
     * Pushes a single frame on demand (used when a CaptureRequest targets a JPEG
     * still-capture ImageReader surface so JPEG buffers are only queued when requested).
     */
    public synchronized void triggerSingleFrame() {
        try {
            TransformConfig cfg = HookMain.getActiveTransformConfig();
            if (cfg.passthroughRealCamera) return;
            if (writer == null && surfaceFormat != FORMAT_PREVIEW && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                writer = createWriter();
            }
            if (!ensureCache(cfg)) return;
            if (surfaceFormat == ImageFormat.JPEG || surfaceFormat == 256) {
                pushJpegFrame(cfg);
            } else if (surfaceFormat == ImageFormat.YUV_420_888
                    || surfaceFormat == ImageFormat.NV21
                    || surfaceFormat == ImageFormat.YV12) {
                pushYuvFrame(cfg);
            } else {
                drawOnCanvas(cfg);
            }
        } catch (Throwable t) {
            XposedBridge.log("[VCAM][feeder] triggerSingleFrame: " + t);
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
                } else if (surfaceFormat == ImageFormat.JPEG || surfaceFormat == 256) {
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
            // Reset file-based cache keys so switching back to SOURCE_MEDIA always re-renders!
            cachedMtime = -1L;
            cachedSize = -1L;
            cachedTransformSig = "SYNTH|" + cfg.sourceMode + "|" + buildTransformSignature(cfg);
            return surfaceFormat == FORMAT_PREVIEW ? (cachedTransformedBitmap != null)
                    : ((surfaceFormat == ImageFormat.JPEG || surfaceFormat == 256) ? cachedJpeg != null : cachedI420 != null);
        }

        // Live video frame priority: when a video is actively decoded, render the live video frame
        // with full PTZ transforms so preview surfaces and JPEG captures reflect live video + PTZ!
        if (HookMain.hasActiveVideoFrame()) {
            Bitmap videoBmp = HookMain.getLatestVideoFrameBitmap(width, height);
            if (videoBmp != null) {
                cachedTransformedBitmap = FrameTransformPipeline.renderToBitmap(
                        videoBmp, 0, width, height, cfg, cachedTransformedBitmap);
                rebuildEncodedBuffers(cachedTransformedBitmap, cfg);
                cachedMtime = -1L;
                cachedSize = -1L;
                cachedTransformSig = "VIDEO|" + buildTransformSignature(cfg);
                return surfaceFormat == FORMAT_PREVIEW ? (cachedTransformedBitmap != null)
                        : ((surfaceFormat == ImageFormat.JPEG || surfaceFormat == 256) ? cachedJpeg != null : cachedI420 != null);
            }
        }

        File f = HookMain.resolveBestImageFile(bitmapPath);
        if (f == null || !f.exists() || f.length() == 0) {
            // Provider stream fallback when disk staging is unavailable
            Bitmap providerBmp = HookMain.decodeActiveImageFromProvider();
            if (providerBmp != null) {
                cachedTransformedBitmap = FrameTransformPipeline.renderToBitmap(
                        providerBmp, 0, width, height, cfg, cachedTransformedBitmap);
                rebuildEncodedBuffers(cachedTransformedBitmap, cfg);
                return surfaceFormat == FORMAT_PREVIEW ? (cachedTransformedBitmap != null)
                        : ((surfaceFormat == ImageFormat.JPEG || surfaceFormat == 256) ? cachedJpeg != null : cachedI420 != null);
            }
            // Video fallback via MediaMetadataRetriever if VideoToFrames hasn't emitted its first frame yet
            Bitmap videoBmp = HookMain.getLatestVideoFrameBitmap(width, height);
            if (videoBmp != null) {
                cachedTransformedBitmap = FrameTransformPipeline.renderToBitmap(
                        videoBmp, 0, width, height, cfg, cachedTransformedBitmap);
                rebuildEncodedBuffers(cachedTransformedBitmap, cfg);
                return surfaceFormat == FORMAT_PREVIEW ? (cachedTransformedBitmap != null)
                        : ((surfaceFormat == ImageFormat.JPEG || surfaceFormat == 256) ? cachedJpeg != null : cachedI420 != null);
            }
            return false;
        }
        long mtime = f.lastModified();
        long size = f.length();
        long inv = cacheInvalidationCounter;
        String sig = buildTransformSignature(cfg);

        boolean buffersReady = (surfaceFormat == FORMAT_PREVIEW)
                || ((surfaceFormat == ImageFormat.JPEG || surfaceFormat == 256) ? cachedJpeg != null : cachedI420 != null);

        if (cachedTransformedBitmap != null && !cachedTransformedBitmap.isRecycled()
                && buffersReady
                && mtime == cachedMtime && size == cachedSize
                && inv == observedInvalidationCounter
                && sig.equals(cachedTransformSig)) {
            if (cfg.sensorGrainEnabled && cachedI420 != null && surfaceFormat != FORMAT_PREVIEW) {
                FrameTransformPipeline.applyLumaGrainInPlace(cachedI420, width, height);
            }
            return true;
        }

        if (rawSourceBitmap == null || rawSourceBitmap.isRecycled()
                || mtime != cachedMtime || size != cachedSize || inv != observedInvalidationCounter) {
            if (rawSourceBitmap != null && !rawSourceBitmap.isRecycled()) {
                rawSourceBitmap.recycle();
            }
            rawSourceBitmap = BitmapFactory.decodeFile(f.getAbsolutePath());
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
        return surfaceFormat == FORMAT_PREVIEW ? (cachedTransformedBitmap != null)
                : ((surfaceFormat == ImageFormat.JPEG || surfaceFormat == 256) ? cachedJpeg != null : cachedI420 != null);
    }

    private void rebuildEncodedBuffers(@NonNull Bitmap transformed, @NonNull TransformConfig cfg) {
        SensorProfiler.LensProfile profile = HookMain.getActiveLensProfile();
        ExifSynthesizer.updatePhotometryFromBitmap(transformed, profile, HookMain.getCurrentFacing());

        if (surfaceFormat == FORMAT_PREVIEW) {
            return;
        }

        if (surfaceFormat == ImageFormat.JPEG || surfaceFormat == 256) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            transformed.compress(Bitmap.CompressFormat.JPEG, 94, out);
            byte[] rawJpeg = out.toByteArray();
            cachedJpeg = cfg.exifSynthEnabled
                    ? ExifSynthesizer.injectExif(rawJpeg, width, height, profile)
                    : rawJpeg;
            return;
        }

        int ySize = width * height;
        if (argbScratch == null || argbScratch.length < ySize) {
            argbScratch = new int[ySize];
        }
        cachedI420 = FrameTransformPipeline.bitmapToI420(
                transformed, cfg.sensorGrainEnabled, argbScratch, cachedI420);
        try {
            byte[] nv21 = FrameTransformPipeline.bitmapToNv21(transformed);
            if (nv21 != null && nv21.length > 0) {
                HookMain.data_buffer = nv21;
                NdkHookEngine.updateFrameBuffer(nv21, width, height);
            }
        } catch (Throwable ignored) {}
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
            if (planes == null || planes.length < 3) return;
            int imgW = img.getWidth() & ~1;
            int imgH = img.getHeight() & ~1;
            byte[] i420 = cachedI420;
            int useW = width;
            int useH = height;
            if (imgW > 0 && imgH > 0 && (imgW != width || imgH != height) && cachedTransformedBitmap != null) {
                Bitmap scaled = Bitmap.createScaledBitmap(cachedTransformedBitmap, imgW, imgH, true);
                int[] scratch = new int[imgW * imgH];
                i420 = FrameTransformPipeline.bitmapToI420(scaled, cfg.sensorGrainEnabled, scratch, null);
                useW = imgW;
                useH = imgH;
            }
            int ySize = useW * useH;
            int uvSize = ySize / 4;
            copyPlane(planes[0], i420, 0, useW, useH, 1);
            copyPlane(planes[1], i420, ySize, useW / 2, useH / 2, 1);
            copyPlane(planes[2], i420, ySize + uvSize, useW / 2, useH / 2, 1);
            w.queueInputImage(img);
            img = null;
        } finally {
            if (img != null) {
                try { img.close(); } catch (Throwable ignored) {}
            }
        }
    }

    /**
     * Copies a planar channel into an {@link Image.Plane}, preserving interleaved
     * neighbor bytes when {@code pixelStride > 1} (e.g. semi-planar NV12/NV21 backing
     * {@link ImageFormat#YUV_420_888} where U and V share the same memory block).
     */
    public static void copyPlane(Image.Plane plane, byte[] src, int offset,
                                 int w, int h, int srcPixelStride) {
        ByteBuffer buf = plane.getBuffer();
        if (buf.isReadOnly()) return;
        int rowStride = plane.getRowStride();
        int pixelStride = plane.getPixelStride();
        buf.rewind();
        if (pixelStride == srcPixelStride && rowStride == w * srcPixelStride) {
            int len = Math.min(w * h * srcPixelStride, Math.min(src.length - offset, buf.remaining()));
            if (len > 0) buf.put(src, offset, len);
            return;
        }
        if (pixelStride == 1 && srcPixelStride == 1) {
            int cap = buf.capacity();
            for (int y = 0; y < h; y++) {
                int srcRow = offset + y * w;
                int dstRow = y * rowStride;
                int copyLen = Math.min(w, Math.min(src.length - srcRow, cap - dstRow));
                if (copyLen > 0) {
                    buf.position(dstRow);
                    buf.put(src, srcRow, copyLen);
                }
            }
            buf.rewind();
            return;
        }
        // Interleaved chroma plane (pixelStride == 2): write individual samples by index
        // so we never clobber the adjacent interleaved V/U channel sharing the same buffer!
        int cap = buf.capacity();
        for (int y = 0; y < h; y++) {
            int srcRow = offset + y * w * srcPixelStride;
            int dstRow = y * rowStride;
            for (int x = 0; x < w; x++) {
                int sIdx = srcRow + x * srcPixelStride;
                int dIdx = dstRow + x * pixelStride;
                if (sIdx < src.length && dIdx >= 0 && dIdx < cap) {
                    buf.put(dIdx, src[sIdx]);
                }
            }
        }
        buf.rewind();
    }

    private volatile boolean preferSoftwareCanvas = false;

    private void drawOnCanvas(@NonNull TransformConfig cfg) {
        Surface s = surface;
        Bitmap bmp = cachedTransformedBitmap;
        if (s == null || !s.isValid() || bmp == null || bmp.isRecycled()) return;
        HookMain.ensurePreviewSurfaceBufferSize(s, width, height);
        Canvas c = null;
        try {
            // Try lockHardwareCanvas() FIRST on Android M+ so GPU-backed PreviewView
            // SurfaceTexture / SurfaceView consumers (like Persona / CameraX OpenGlRenderer)
            // receive hardware GraphicBuffers with proper geometry instead of 1x1 CPU-locked buffers.
            if (!preferSoftwareCanvas && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    c = s.lockHardwareCanvas();
                } catch (Throwable hwErr) {
                    preferSoftwareCanvas = true;
                    c = s.lockCanvas(null);
                }
            } else {
                try {
                    c = s.lockCanvas(null);
                } catch (Throwable swErr) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        preferSoftwareCanvas = false;
                        c = s.lockHardwareCanvas();
                    }
                }
            }
            if (c == null) return;
            c.drawColor(android.graphics.Color.BLACK);
            android.graphics.Rect dstRect = new android.graphics.Rect(0, 0, c.getWidth(), c.getHeight());
            c.drawBitmap(bmp, null, dstRect, null);
        } catch (Throwable t) {
            // Gracefully handle surfaces that have active hardware renderers (EGL/Vulkan) or are already locked
        } finally {
            if (c != null) {
                try { s.unlockCanvasAndPost(c); } catch (Throwable ignored) {}
            }
        }
    }
}
