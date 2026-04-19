package com.example.vcam;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.media.Image;
import android.media.ImageWriter;
import android.os.Build;
import android.view.Surface;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.ByteBuffer;

import de.robv.android.xposed.XposedBridge;

/**
 * Feeds a single staged still image into a Camera2 output {@link Surface}
 * continuously at ~15fps so image-only injection mode behaves identically
 * to the MP4 path (which is driven by {@link VideoToFrames}).
 *
 * <p>Three cases are handled:
 * <ul>
 *   <li>{@link ImageFormat#JPEG} reader surfaces: the bitmap is encoded to a
 *       JPEG byte[] once (invalidated when the source file changes) and
 *       pushed via {@link ImageWriter#queueInputImage(Image)}. This is the
 *       Camera2 still-capture path that previously relied exclusively on
 *       Camera1 {@code takePicture} — it is now fully hijacked.</li>
 *   <li>{@link ImageFormat#YUV_420_888} reader surfaces: the bitmap is
 *       converted to I420 planes once and copied into the writer image
 *       planes, mirroring the continuous-preview contract from
 *       {@code VideoToFrames}.</li>
 *   <li>Preview surfaces (TextureView/SurfaceView-backed): drawn via
 *       {@link Surface#lockCanvas(Rect)} at the same cadence.</li>
 * </ul>
 *
 * <p>Lifecycle: call {@link #start()} to begin feeding, {@link #stop()} to
 * cleanly shut down the writer thread and release the {@link ImageWriter}.
 * The feeder self-detects source-file (mtime/length) changes and rebuilds
 * its cached buffers on the next tick without restart.
 */
public class StaticBitmapFeeder {

    private static final String TAG = "VCAM/StaticBitmapFeeder";
    private static final long FRAME_INTERVAL_MS = 66L; // ~15fps

    /** Use this when feeding a preview (TextureView / SurfaceView) surface. */
    public static final int FORMAT_PREVIEW = -1;

    private final Surface surface;
    private final int surfaceFormat;
    private final int width;
    private final int height;
    private final String bitmapPath;

    private Thread worker;
    private volatile boolean running;

    private ImageWriter writer;

    // Cached per-format encodings of the staged bitmap, keyed on mtime+length.
    private byte[] cachedJpeg;
    private byte[] cachedI420;
    private long cachedMtime;
    private long cachedSize;
    private int cachedWidth;
    private int cachedHeight;

    public StaticBitmapFeeder(Surface surface, int surfaceFormat, int width,
                              int height, String bitmapPath) {
        this.surface = surface;
        this.surfaceFormat = surfaceFormat;
        this.width = width;
        this.height = height;
        this.bitmapPath = bitmapPath;
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
        // Set up the writer lazily so the exact format / buffer count only
        // needs to be negotiated once. Preview surfaces don't use a writer.
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
            try {
                if (!ensureCache()) {
                    // No usable bitmap staged yet; poll again soon.
                    Thread.sleep(FRAME_INTERVAL_MS);
                    continue;
                }
                if (surfaceFormat == FORMAT_PREVIEW) {
                    drawOnCanvas();
                } else if (surfaceFormat == ImageFormat.JPEG) {
                    pushJpegFrame();
                } else if (surfaceFormat == ImageFormat.YUV_420_888) {
                    pushYuvFrame();
                } else {
                    // Unknown reader format — try lockCanvas as a last
                    // resort; most consumers reject but better than silent.
                    drawOnCanvas();
                }
            } catch (InterruptedException ie) {
                return;
            } catch (Throwable t) {
                XposedBridge.log("[VCAM][feeder] tick: " + t);
            }
            nextTick += FRAME_INTERVAL_MS;
            long sleep = nextTick - System.currentTimeMillis();
            if (sleep > 0) {
                try { Thread.sleep(sleep); } catch (InterruptedException ie) { return; }
            } else {
                nextTick = System.currentTimeMillis();
            }
        }
    }

    private ImageWriter createWriter() {
        // The two-arg form inherits the consumer-side format from the Surface
        // (Camera2 ImageReader producer surfaces always advertise a format).
        // Double-buffered so queue/dequeue don't block on the consumer.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return ImageWriter.newInstance(surface, /* maxImages */ 2);
        }
        return null;
    }

    /** (Re)compute cached encodings when the backing file changes. */
    private boolean ensureCache() {
        File f = new File(bitmapPath);
        if (!f.exists() || f.length() == 0) return false;
        long mtime = f.lastModified();
        long size = f.length();
        if (cachedJpeg != null && cachedI420 != null
                && mtime == cachedMtime && size == cachedSize
                && cachedWidth == width && cachedHeight == height) {
            return true;
        }
        Bitmap bmp = BitmapFactory.decodeFile(bitmapPath);
        if (bmp == null) return false;
        try {
            Bitmap scaled = (bmp.getWidth() == width && bmp.getHeight() == height)
                    ? bmp
                    : Bitmap.createScaledBitmap(bmp, width, height, true);
            // Pre-encoded JPEG for ImageFormat.JPEG reader surfaces.
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            scaled.compress(Bitmap.CompressFormat.JPEG, 92, out);
            cachedJpeg = out.toByteArray();
            // Pre-converted I420 (Y,U,V) for YUV_420_888 reader surfaces.
            cachedI420 = bitmapToI420(scaled, width, height);
            cachedMtime = mtime;
            cachedSize = size;
            cachedWidth = width;
            cachedHeight = height;
            if (scaled != bmp) scaled.recycle();
        } finally {
            // Keep the original bmp recycled; we don't need it past conversion.
            if (!bmp.isRecycled()) bmp.recycle();
        }
        return cachedJpeg != null && cachedI420 != null;
    }

    private void pushJpegFrame() {
        ImageWriter w = writer;
        if (w == null || cachedJpeg == null) return;
        Image img = null;
        try {
            img = w.dequeueInputImage();
            ByteBuffer buf = img.getPlanes()[0].getBuffer();
            // JPEG plane has a contiguous byte buffer sized to the largest
            // expected compressed frame. Guard against tiny buffers.
            if (buf.capacity() < cachedJpeg.length) {
                XposedBridge.log("[VCAM][feeder] JPEG buffer too small: "
                        + buf.capacity() + " < " + cachedJpeg.length);
                return;
            }
            buf.rewind();
            buf.put(cachedJpeg);
            // Some drivers require the limit to match the JPEG payload.
            buf.limit(cachedJpeg.length);
            w.queueInputImage(img);
            img = null;
        } finally {
            if (img != null) {
                try { img.close(); } catch (Throwable ignored) {}
            }
        }
    }

    private void pushYuvFrame() {
        ImageWriter w = writer;
        if (w == null || cachedI420 == null) return;
        Image img = null;
        try {
            img = w.dequeueInputImage();
            Image.Plane[] planes = img.getPlanes();
            int ySize = width * height;
            int uvSize = ySize / 4;
            // Y plane
            copyPlane(planes[0], cachedI420, 0, width, height, 1);
            // U plane (I420 → U = cachedI420[ySize..ySize+uvSize])
            copyPlane(planes[1], cachedI420, ySize, width / 2, height / 2, 1);
            // V plane
            copyPlane(planes[2], cachedI420, ySize + uvSize, width / 2, height / 2, 1);
            w.queueInputImage(img);
            img = null;
        } finally {
            if (img != null) {
                try { img.close(); } catch (Throwable ignored) {}
            }
        }
    }

    /**
     * Copy {@code w*h} bytes from {@code src+offset} into {@code plane},
     * honoring the plane's row/pixel strides.
     */
    private static void copyPlane(Image.Plane plane, byte[] src, int offset,
                                  int w, int h, int srcPixelStride) {
        ByteBuffer buf = plane.getBuffer();
        int rowStride = plane.getRowStride();
        int pixelStride = plane.getPixelStride();
        buf.rewind();
        if (pixelStride == srcPixelStride && rowStride == w * srcPixelStride) {
            // Fast path: tight packing matches.
            buf.put(src, offset, w * h * srcPixelStride);
            return;
        }
        byte[] row = new byte[rowStride];
        for (int y = 0; y < h; y++) {
            int srcRow = offset + y * w * srcPixelStride;
            if (pixelStride == srcPixelStride) {
                System.arraycopy(src, srcRow, row, 0, w * srcPixelStride);
            } else {
                for (int x = 0; x < w; x++) {
                    row[x * pixelStride] = src[srcRow + x * srcPixelStride];
                }
            }
            buf.put(row, 0, Math.min(rowStride, buf.remaining()));
        }
    }

    private void drawOnCanvas() {
        Surface s = surface;
        if (s == null || !s.isValid() || cachedJpeg == null) return;
        Bitmap bmp = BitmapFactory.decodeByteArray(cachedJpeg, 0, cachedJpeg.length);
        if (bmp == null) return;
        Canvas c = null;
        try {
            c = s.lockCanvas(null);
            if (c == null) return;
            Rect dst = new Rect(0, 0, c.getWidth(), c.getHeight());
            c.drawBitmap(bmp, null, dst, null);
        } finally {
            if (c != null) {
                try { s.unlockCanvasAndPost(c); } catch (Throwable ignored) {}
            }
            bmp.recycle();
        }
    }

    /** RGBA → I420 conversion using BT.601 coefficients. */
    private static byte[] bitmapToI420(Bitmap bmp, int w, int h) {
        int[] argb = new int[w * h];
        bmp.getPixels(argb, 0, w, 0, 0, w, h);
        byte[] yuv = new byte[w * h * 3 / 2];
        int ySize = w * h;
        int uIndex = ySize;
        int vIndex = ySize + ySize / 4;
        for (int j = 0; j < h; j++) {
            for (int i = 0; i < w; i++) {
                int c = argb[j * w + i];
                int r = (c >> 16) & 0xff;
                int g = (c >> 8) & 0xff;
                int b = c & 0xff;
                int yv = ((66 * r + 129 * g + 25 * b + 128) >> 8) + 16;
                yuv[j * w + i] = (byte) clamp(yv, 0, 255);
                if ((j & 1) == 0 && (i & 1) == 0) {
                    int uv = ((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128;
                    int vv = ((112 * r - 94 * g - 18 * b + 128) >> 8) + 128;
                    yuv[uIndex++] = (byte) clamp(uv, 0, 255);
                    yuv[vIndex++] = (byte) clamp(vv, 0, 255);
                }
            }
        }
        return yuv;
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
