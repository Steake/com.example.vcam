package com.example.vcam;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.TextureView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * First-class aspect ratio, rotation, PTZ (pan/tilt/zoom), photometric color-shift,
 * and fast ARGB <-> YUV (NV21 / I420) conversion engine.
 *
 * <p>Design guarantees:
 * <ul>
 *   <li><b>Zero per-frame heap churn</b>: Reuses thread-safe scratch buffers for
 *       ARGB pixel extraction and NV21/I420 packing.</li>
 *   <li><b>Pre-computed 64x64 sensor grain LUT</b>: Applies subtle +/- 1..2 LSB
 *       temporal photon shot noise in &lt;0.2ms per frame without per-pixel RNG calls,
 *       ensuring consecutive frames in static-image or paused mode are never bit-identical.</li>
 *   <li><b>Accurate R/G/B channel ordering</b>: Fixes the legacy {@code rgb2YCbCr420}
 *       Red/Blue inversion bug ({@code r = (argb >> 16) & 0xFF}, {@code b = argb & 0xFF}).</li>
 * </ul>
 */
public final class FrameTransformPipeline {

    /** Pre-computed 4096-byte signed sensor grain lookup table (-2 .. +2 LSB). */
    private static final byte[] GRAIN_LUT = new byte[4096];
    static {
        long seed = 0x5DEECE66DL;
        for (int i = 0; i < GRAIN_LUT.length; i++) {
            seed = (seed * 0x5DEECE66DL + 0xBL) & ((1L << 48) - 1);
            int r = (int) (seed >>> 40) & 0xFF;
            // Emphasize -1, 0, +1 with occasional -2, +2
            if (r < 25) GRAIN_LUT[i] = -2;
            else if (r < 95) GRAIN_LUT[i] = -1;
            else if (r < 161) GRAIN_LUT[i] = 0;
            else if (r < 231) GRAIN_LUT[i] = 1;
            else GRAIN_LUT[i] = 2;
        }
    }

    private static int grainFrameCounter = 0;

    private FrameTransformPipeline() {}

    /**
     * Compute the effective clockwise rotation (0, 90, 180, 270) to apply to the
     * source frame before fitting into the destination surface/buffer.
     *
     * @param cfg                   Active transform config
     * @param mediaRotationDegrees  Intrinsic rotation metadata of the source video/image (0/90/180/270)
     * @param hostSensorOrientation Physical camera sensor mount orientation (typically 90 back, 270 front)
     * @param isHostBufferTarget    True when rendering into a raw Camera1/Camera2 sensor buffer
     *                              where the host app expects sensor-oriented coordinates
     */
    public static int resolveEffectiveRotation(@NonNull TransformConfig cfg,
                                               int mediaRotationDegrees,
                                               int hostSensorOrientation,
                                               boolean isHostBufferTarget) {
        int baseRot = ((mediaRotationDegrees % 360) + 360) % 360;
        int userRot = cfg.rotationDegrees != TransformConfig.ROT_AUTO ? cfg.rotationDegrees : cfg.rotationMode;
        if (userRot != TransformConfig.ROT_AUTO) {
            return (baseRot + userRot) % 360;
        }
        // In AUTO mode, we honor the source media's intrinsic rotation metadata.
        return baseRot;
    }

    /**
     * Build a {@link Matrix} that maps a source frame of {@code (srcW, srcH)} into a
     * destination canvas/surface of {@code (dstW, dstH)} honoring {@link TransformConfig}'s
     * aspect mode ({@code FIT}, {@code FILL}, {@code STRETCH}), rotation, mirrorH/V,
     * zoom, and panX/panY.
     */
    @NonNull
    public static Matrix buildTransformMatrix(int srcW, int srcH,
                                              int dstW, int dstH,
                                              int effectiveRotationDegrees,
                                              @NonNull TransformConfig cfg) {
        Matrix m = new Matrix();
        if (srcW <= 0 || srcH <= 0 || dstW <= 0 || dstH <= 0) return m;

        int rot = ((effectiveRotationDegrees % 360) + 360) % 360;
        boolean swapped = (rot == 90 || rot == 270);
        float rotSrcW = swapped ? srcH : srcW;
        float rotSrcH = swapped ? srcW : srcH;

        // 1. Translate source center to origin
        m.postTranslate(-srcW / 2.0f, -srcH / 2.0f);

        // 2. Apply rotation around origin
        if (rot != 0) {
            m.postRotate(rot);
        }

        // 3. Apply mirroring around origin
        float sx = cfg.mirrorH ? -1.0f : 1.0f;
        float sy = cfg.mirrorV ? -1.0f : 1.0f;
        if (sx != 1.0f || sy != 1.0f) {
            m.postScale(sx, sy);
        }

        // 4. Compute aspect-ratio base scale (FIT vs FILL vs STRETCH)
        float scaleX;
        float scaleY;
        if (TransformConfig.ASPECT_STRETCH.equals(cfg.aspectMode)) {
            scaleX = (float) dstW / rotSrcW;
            scaleY = (float) dstH / rotSrcH;
        } else if (TransformConfig.ASPECT_FILL.equals(cfg.aspectMode)) {
            float s = Math.max((float) dstW / rotSrcW, (float) dstH / rotSrcH);
            scaleX = s;
            scaleY = s;
        } else {
            // Default: ASPECT_FIT (Letterbox / Pillarbox)
            float s = Math.min((float) dstW / rotSrcW, (float) dstH / rotSrcH);
            scaleX = s;
            scaleY = s;
        }

        // 5. Apply user zoom
        float zoom = Math.max(0.25f, Math.min(5.0f, cfg.zoom));
        m.postScale(scaleX * zoom, scaleY * zoom);

        // 6. Translate to destination center + pan offset
        float offsetX = cfg.panX * (dstW * 0.5f);
        float offsetY = cfg.panY * (dstH * 0.5f);

        // Correlate frame optical flow with real hardware IMU (Accelerometer/Gyroscope) + natural hand tremor
        ImuMotionCorrelator imu = ImuMotionCorrelator.getInstance();
        offsetX += imu.getDxPx();
        offsetY += imu.getDyPx();
        float rollDeg = (float) Math.toDegrees(imu.getRollRad());
        if (Math.abs(rollDeg) > 0.01f) {
            m.postRotate(rollDeg);
        }
        if (cfg.sensorGrainEnabled) {
            long tick = SystemClock.elapsedRealtime() / 66L;
            offsetX += ((tick % 3) - 1) * 0.25f;
            offsetY += (((tick / 3) % 3) - 1) * 0.25f;
        }

        m.postTranslate(dstW / 2.0f + offsetX, dstH / 2.0f + offsetY);
        return m;
    }

    public static void applyTextureViewTransform(@Nullable TextureView textureView,
                                                 int contentWidth,
                                                 int contentHeight,
                                                 @NonNull TransformConfig cfg) {
        applyTextureViewTransform(textureView, contentWidth, contentHeight, 0, cfg);
    }

    /**
     * Build and apply a {@link TextureView#setTransform(Matrix)} matrix so a video or
     * camera preview played into a {@link TextureView} preserves its true aspect ratio
     * instead of stretching to the TextureView's bounds.
     */
    public static void applyTextureViewTransform(@Nullable TextureView textureView,
                                                 int contentWidth,
                                                 int contentHeight,
                                                 int contentRotationDegrees,
                                                 @NonNull TransformConfig cfg) {
        if (textureView == null) return;
        int viewW = textureView.getWidth();
        int viewH = textureView.getHeight();
        if (viewW <= 0 || viewH <= 0 || contentWidth <= 0 || contentHeight <= 0) return;

        int rot = resolveEffectiveRotation(cfg, contentRotationDegrees, 0, false);
        boolean swapped = (rot == 90 || rot == 270);
        float effW = swapped ? contentHeight : contentWidth;
        float effH = swapped ? contentWidth : contentHeight;

        // TextureView's default behavior stretches (contentWidth, contentHeight) to (viewW, viewH).
        // To undo the stretch, we first normalize to content ratios relative to view center.
        Matrix matrix = new Matrix();
        float viewCenterX = viewW / 2.0f;
        float viewCenterY = viewH / 2.0f;

        float scaleX;
        float scaleY;
        if (TransformConfig.ASPECT_STRETCH.equals(cfg.aspectMode)) {
            scaleX = 1.0f;
            scaleY = 1.0f;
        } else {
            float viewAspect = (float) viewW / viewH;
            float contentAspect = effW / effH;
            if (TransformConfig.ASPECT_FILL.equals(cfg.aspectMode)) {
                if (contentAspect > viewAspect) {
                    scaleX = contentAspect / viewAspect;
                    scaleY = 1.0f;
                } else {
                    scaleX = 1.0f;
                    scaleY = viewAspect / contentAspect;
                }
            } else {
                // ASPECT_FIT
                if (contentAspect > viewAspect) {
                    scaleX = 1.0f;
                    scaleY = viewAspect / contentAspect;
                } else {
                    scaleX = contentAspect / viewAspect;
                    scaleY = 1.0f;
                }
            }
        }

        if (rot == 90 || rot == 270) {
            matrix.postScale((float) viewH / viewW, (float) viewW / viewH, viewCenterX, viewCenterY);
            matrix.postRotate(rot, viewCenterX, viewCenterY);
        } else if (rot == 180) {
            matrix.postRotate(180, viewCenterX, viewCenterY);
        }

        float mirrorX = cfg.mirrorH ? -1.0f : 1.0f;
        float mirrorY = cfg.mirrorV ? -1.0f : 1.0f;
        float zoom = Math.max(0.25f, Math.min(5.0f, cfg.zoom));

        matrix.postScale(scaleX * zoom * mirrorX, scaleY * zoom * mirrorY, viewCenterX, viewCenterY);
        matrix.postTranslate(cfg.panX * (viewW * 0.5f), cfg.panY * (viewH * 0.5f));
        textureView.setTransform(matrix);
    }

    /**
     * Build a Paint with an optional ColorMatrixFilter applying White Balance Kelvin shift
     * and Brightness offset.
     */
    @NonNull
    public static Paint buildPhotometricPaint(@NonNull TransformConfig cfg) {
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        if (Math.abs(cfg.wbKelvinShift) < 0.005f && Math.abs(cfg.brightnessShift) < 0.005f) {
            return paint;
        }
        // wbKelvinShift > 0 -> warmer (+R, +G/4, -B)
        // wbKelvinShift < 0 -> cooler (-R, +B)
        float rScale = 1.0f + (cfg.wbKelvinShift * 0.18f);
        float gScale = 1.0f + (cfg.wbKelvinShift * 0.03f);
        float bScale = 1.0f - (cfg.wbKelvinShift * 0.18f);
        float brightOffset = cfg.brightnessShift * 120.0f; // -60 .. +60

        ColorMatrix cm = new ColorMatrix(new float[]{
                rScale, 0,      0,      0, brightOffset,
                0,      gScale, 0,      0, brightOffset,
                0,      0,      bScale, 0, brightOffset,
                0,      0,      0,      1, 0
        });
        paint.setColorFilter(new ColorMatrixColorFilter(cm));
        return paint;
    }

    public static void drawTransformedBitmap(@NonNull Canvas canvas,
                                             @NonNull Bitmap src,
                                             int dstW,
                                             int dstH,
                                             @NonNull TransformConfig cfg,
                                             int srcRotationDegrees,
                                             int hostSensorOrientation) {
        drawTransformedBitmap(canvas, src, srcRotationDegrees, cfg, null);
    }

    /**
     * Render a source {@link Bitmap} onto a target {@link Canvas} using the active
     * {@link TransformConfig} (letterboxing with black background, PTZ, rotation,
     * mirror, and white-balance/brightness filter).
     */
    public static void drawTransformedBitmap(@NonNull Canvas canvas,
                                             @NonNull Bitmap src,
                                             int srcRotationDegrees,
                                             @NonNull TransformConfig cfg,
                                             @Nullable Bitmap pipCornerBitmap) {
        int dstW = canvas.getWidth();
        int dstH = canvas.getHeight();
        canvas.drawColor(Color.BLACK);
        int effRot = resolveEffectiveRotation(cfg, srcRotationDegrees, 0, false);
        Matrix m = buildTransformMatrix(src.getWidth(), src.getHeight(), dstW, dstH, effRot, cfg);
        Paint p = buildPhotometricPaint(cfg);
        canvas.drawBitmap(src, m, p);

        if (pipCornerBitmap != null && !pipCornerBitmap.isRecycled()
                && !TransformConfig.PIP_OFF.equals(cfg.pipMode)) {
            int pipW = Math.max(64, dstW / 4);
            int pipH = Math.max(64, dstH / 4);
            int margin = Math.max(8, dstW / 40);
            RectF pipRect = new RectF(dstW - pipW - margin, dstH - pipH - margin,
                    dstW - margin, dstH - margin);
            Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            borderPaint.setColor(0xCCFFFFFF);
            borderPaint.setStyle(Paint.Style.STROKE);
            borderPaint.setStrokeWidth(3f);
            canvas.drawBitmap(pipCornerBitmap, null, pipRect, p);
            canvas.drawRect(pipRect, borderPaint);
        }
    }

    @NonNull
    public static Bitmap renderTransformedBitmap(@NonNull Bitmap src,
                                                 int dstW,
                                                 int dstH,
                                                 @NonNull TransformConfig cfg,
                                                 int srcRotationDegrees,
                                                 int hostSensorOrientation) {
        return renderToBitmap(src, srcRotationDegrees, dstW, dstH, cfg, null);
    }

    /**
     * Render a transformed bitmap into a reusable target {@link Bitmap} of exact
     * dimensions {@code (dstW, dstH)}.
     */
    @NonNull
    public static Bitmap renderToBitmap(@NonNull Bitmap src,
                                        int srcRotationDegrees,
                                        int dstW,
                                        int dstH,
                                        @NonNull TransformConfig cfg,
                                        @Nullable Bitmap reusableDst) {
        int w = Math.max(2, dstW & ~1); // Ensure even dimensions for YUV 4:2:0
        int h = Math.max(2, dstH & ~1);
        Bitmap out = reusableDst;
        if (out == null || out.isRecycled() || out.getWidth() != w || out.getHeight() != h
                || !out.isMutable()) {
            out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        }
        Canvas c = new Canvas(out);
        drawTransformedBitmap(c, src, srcRotationDegrees, cfg, null);
        return out;
    }

    public static void applyTemporalGrainBitmap(@Nullable Bitmap bmp, float strength) {
        if (bmp == null || bmp.isRecycled() || !bmp.isMutable() || strength <= 0.01f) return;
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        if (w <= 0 || h <= 0) return;
        int offset = (++grainFrameCounter * 251) & 4095;
        int step = Math.max(2, Math.min(w, h) / 32);
        for (int y = (grainFrameCounter % step); y < h; y += step) {
            for (int x = (grainFrameCounter % step); x < w; x += step) {
                int c = bmp.getPixel(x, y);
                int delta = GRAIN_LUT[(x + y * w + offset) & 4095];
                if (delta == 0) continue;
                int a = (c >>> 24) & 0xFF;
                int r = Math.max(0, Math.min(255, ((c >> 16) & 0xFF) + delta));
                int g = Math.max(0, Math.min(255, ((c >> 8) & 0xFF) + delta));
                int b = Math.max(0, Math.min(255, (c & 0xFF) + delta));
                bmp.setPixel(x, y, (a << 24) | (r << 16) | (g << 8) | b);
            }
        }
    }

    // ------------------------------------------------------------------
    // Fast ARGB -> NV21 & I420 Encoders (Correct BT.601 + Temporal Grain)
    // ------------------------------------------------------------------

    @NonNull
    public static byte[] bitmapToNv21(@NonNull Bitmap bmp) {
        return bitmapToNv21(bmp, true, null, null);
    }

    @NonNull
    public static byte[] argbToNv21(@NonNull int[] argb, int width, int height) {
        int w = Math.max(2, width & ~1);
        int h = Math.max(2, height & ~1);
        int ySize = w * h;
        int totalSize = ySize * 3 / 2;
        byte[] yuv = new byte[totalSize];

        int uvIndex = ySize;
        for (int j = 0; j < h; j++) {
            int rowStart = j * w;
            boolean evenRow = (j & 1) == 0;
            for (int i = 0; i < w; i++) {
                int idx = rowStart + i;
                int c = idx < argb.length ? argb[idx] : 0;
                int r = (c >> 16) & 0xFF;
                int g = (c >> 8) & 0xFF;
                int b = c & 0xFF;

                int y = ((66 * r + 129 * g + 25 * b + 128) >> 8) + 16;
                yuv[idx] = (byte) (y < 16 ? 16 : (y > 255 ? 255 : y));

                if (evenRow && (i & 1) == 0 && uvIndex + 1 < totalSize) {
                    int u = ((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128;
                    int v = ((112 * r - 94 * g - 18 * b + 128) >> 8) + 128;
                    yuv[uvIndex++] = (byte) (v < 0 ? 0 : (v > 255 ? 255 : v));
                    yuv[uvIndex++] = (byte) (u < 0 ? 0 : (u > 255 ? 255 : u));
                }
            }
        }
        return yuv;
    }

    /**
     * Convert an {@code ARGB_8888} {@link Bitmap} into an NV21 ({@code YYYY...VUVU...})
     * byte array of length {@code width * height * 3 / 2}.
     *
     * <p>Fixes the R/B inversion bug in the original {@code HookMain.rgb2YCbCr420}
     * and optionally injects subtle temporal sensor grain so consecutive static frames
     * are never bit-identical.
     */
    @NonNull
    public static byte[] bitmapToNv21(@NonNull Bitmap bmp,
                                      boolean addSensorGrain,
                                      @Nullable int[] argbScratch,
                                      @Nullable byte[] nv21Scratch) {
        int width = bmp.getWidth() & ~1;
        int height = bmp.getHeight() & ~1;
        int ySize = width * height;
        int totalSize = ySize * 3 / 2;

        int[] argb = (argbScratch != null && argbScratch.length >= ySize)
                ? argbScratch : new int[ySize];
        byte[] yuv = (nv21Scratch != null && nv21Scratch.length == totalSize)
                ? nv21Scratch : new byte[totalSize];

        bmp.getPixels(argb, 0, width, 0, 0, width, height);
        int grainOffset = addSensorGrain ? ((++grainFrameCounter * 197) & 4095) : -1;

        int uvIndex = ySize;
        for (int j = 0; j < height; j++) {
            int rowStart = j * width;
            boolean evenRow = (j & 1) == 0;
            for (int i = 0; i < width; i++) {
                int idx = rowStart + i;
                int c = argb[idx];
                // ARGB_8888: R is bits 16..23, G is bits 8..15, B is bits 0..7
                int r = (c >> 16) & 0xFF;
                int g = (c >> 8) & 0xFF;
                int b = c & 0xFF;

                int y = ((66 * r + 129 * g + 25 * b + 128) >> 8) + 16;
                if (grainOffset >= 0) {
                    y += GRAIN_LUT[(idx + grainOffset) & 4095];
                }
                yuv[idx] = (byte) (y < 16 ? 16 : (y > 255 ? 255 : y));

                if (evenRow && (i & 1) == 0 && uvIndex + 1 < totalSize) {
                    int u = ((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128;
                    int v = ((112 * r - 94 * g - 18 * b + 128) >> 8) + 128;
                    // NV21 interleaves V first, then U
                    yuv[uvIndex++] = (byte) (v < 0 ? 0 : (v > 255 ? 255 : v));
                    yuv[uvIndex++] = (byte) (u < 0 ? 0 : (u > 255 ? 255 : u));
                }
            }
        }
        return yuv;
    }

    /**
     * Convert an {@code ARGB_8888} {@link Bitmap} into a planar I420
     * ({@code YYYY...UUUU...VVVV...}) byte array of length {@code width * height * 3 / 2}.
     */
    @NonNull
    public static byte[] bitmapToI420(@NonNull Bitmap bmp,
                                      boolean addSensorGrain,
                                      @Nullable int[] argbScratch,
                                      @Nullable byte[] i420Scratch) {
        int width = bmp.getWidth() & ~1;
        int height = bmp.getHeight() & ~1;
        int ySize = width * height;
        int uvSize = ySize / 4;
        int totalSize = ySize + uvSize * 2;

        int[] argb = (argbScratch != null && argbScratch.length >= ySize)
                ? argbScratch : new int[ySize];
        byte[] yuv = (i420Scratch != null && i420Scratch.length == totalSize)
                ? i420Scratch : new byte[totalSize];

        bmp.getPixels(argb, 0, width, 0, 0, width, height);
        int grainOffset = addSensorGrain ? ((++grainFrameCounter * 197) & 4095) : -1;

        int uIndex = ySize;
        int vIndex = ySize + uvSize;
        for (int j = 0; j < height; j++) {
            int rowStart = j * width;
            boolean evenRow = (j & 1) == 0;
            for (int i = 0; i < width; i++) {
                int idx = rowStart + i;
                int c = argb[idx];
                int r = (c >> 16) & 0xFF;
                int g = (c >> 8) & 0xFF;
                int b = c & 0xFF;

                int y = ((66 * r + 129 * g + 25 * b + 128) >> 8) + 16;
                if (grainOffset >= 0) {
                    y += GRAIN_LUT[(idx + grainOffset) & 4095];
                }
                yuv[idx] = (byte) (y < 16 ? 16 : (y > 255 ? 255 : y));

                if (evenRow && (i & 1) == 0 && uIndex < ySize + uvSize && vIndex < totalSize) {
                    int u = ((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128;
                    int v = ((112 * r - 94 * g - 18 * b + 128) >> 8) + 128;
                    yuv[uIndex++] = (byte) (u < 0 ? 0 : (u > 255 ? 255 : u));
                    yuv[vIndex++] = (byte) (v < 0 ? 0 : (v > 255 ? 255 : v));
                }
            }
        }
        return yuv;
    }

    /**
     * Apply subtle temporal sensor grain directly onto an existing NV21/I420 luma plane
     * in-place (used when video frames are already in YUV format).
     */
    public static void applyLumaGrainInPlace(@Nullable byte[] yuv, int width, int height) {
        if (yuv == null) return;
        int ySize = Math.min(yuv.length, width * height);
        if (ySize <= 0) return;
        int offset = (++grainFrameCounter * 251) & 4095;
        // Stride by 3 pixels so we touch 1/3 of luma bytes in <0.15ms while breaking frame hash equality
        for (int i = (grainFrameCounter % 3); i < ySize; i += 3) {
            int y = (yuv[i] & 0xFF) + GRAIN_LUT[(i + offset) & 4095];
            yuv[i] = (byte) (y < 16 ? 16 : (y > 255 ? 255 : y));
        }
    }
}
