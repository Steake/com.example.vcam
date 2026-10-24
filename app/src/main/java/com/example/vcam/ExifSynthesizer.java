package com.example.vcam;

import android.graphics.Bitmap;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.params.RggbChannelVector;
import android.os.Build;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Synthesizes hardware-matched EXIF APP1 headers into in-memory JPEG byte arrays
 * and patches Camera2 {@link CaptureResult} metadata keys so every captured image
 * and live preview frame exhibits photometric consistency with the physical phone
 * camera and the actual brightness/chromaticity of the injected frame.
 */
public final class ExifSynthesizer {

    private static volatile boolean captureResultHooked = false;

    /** Latest computed photometric state from the active frame pipeline. */
    public static final class FramePhotometry {
        public volatile float meanLuma = 128.0f;       // 0..255
        public volatile float rGain = 1.65f;           // RGGB white balance
        public volatile float gEvenGain = 1.00f;
        public volatile float gOddGain = 1.00f;
        public volatile float bGain = 1.55f;
        public volatile int computedIso = 160;
        public volatile long computedExposureNs = 16_666_666L; // 1/60s
        public volatile float focusDistanceDiopters = 1.2f;
        public volatile long frameTimestampNs = SystemClock.elapsedRealtimeNanos();
        public volatile long frameNumber = 1L;
    }

    public static final FramePhotometry CURRENT_PHOTOMETRY = new FramePhotometry();

    private ExifSynthesizer() {}

    /**
     * Sample a sparse 8x8 grid of the current frame bitmap to compute real-time
     * scene luminance (Y) and RGB white-balance ratios, then map them into the
     * physical camera's ISO and exposure-time bounds from {@link SensorProfiler.LensProfile}.
     */
    public static void updatePhotometryFromBitmap(@Nullable Bitmap bmp,
                                                  @Nullable SensorProfiler.LensProfile profile,
                                                  @Nullable String facing) {
        if (bmp == null || bmp.isRecycled()) return;
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        if (w <= 0 || h <= 0) return;

        long sumY = 0;
        long sumR = 1;
        long sumG = 1;
        long sumB = 1;
        int count = 0;

        int stepX = Math.max(1, w / 8);
        int stepY = Math.max(1, h / 8);
        for (int y = stepY / 2; y < h; y += stepY) {
            for (int x = stepX / 2; x < w; x += stepX) {
                int c = bmp.getPixel(x, y);
                int r = (c >> 16) & 0xFF;
                int g = (c >> 8) & 0xFF;
                int b = c & 0xFF;
                int luma = (66 * r + 129 * g + 25 * b + 128) >> 8;
                sumY += luma;
                sumR += r;
                sumG += g;
                sumB += b;
                count++;
            }
        }
        if (count == 0) return;

        float meanY = (float) sumY / count;
        CURRENT_PHOTOMETRY.meanLuma = meanY;

        // Compute realistic AWB RGGB channel gains inversely proportional to scene color cast
        float avgR = (float) sumR / count;
        float avgG = Math.max(16.0f, (float) sumG / count);
        float avgB = (float) sumB / count;
        CURRENT_PHOTOMETRY.rGain = clamp(avgG / Math.max(16.0f, avgR) * 1.45f, 1.05f, 2.85f);
        CURRENT_PHOTOMETRY.gEvenGain = 1.00f;
        CURRENT_PHOTOMETRY.gOddGain = 1.00f;
        CURRENT_PHOTOMETRY.bGain = clamp(avgG / Math.max(16.0f, avgB) * 1.40f, 1.05f, 2.85f);

        // Map meanY (0=dark .. 255=bright) to realistic ISO & ExposureTime within sensor bounds
        int minIso = profile != null ? profile.minIso : 50;
        int maxIso = profile != null ? profile.maxIso : 3200;
        long minExp = profile != null ? profile.minExposureNs : 100_000L;
        long maxExp = profile != null ? profile.maxExposureNs : 100_000_000L;

        // Bright scene (meanY ~ 200) -> ISO ~ minIso..100, Exp ~ 1/250s (4ms)
        // Medium indoor (meanY ~ 120) -> ISO ~ 160..320, Exp ~ 1/60s (16.6ms)
        // Dim scene (meanY ~ 40) -> ISO ~ 800..1600, Exp ~ 1/30s (33.3ms)
        float darkness = 1.0f - clamp(meanY / 240.0f, 0.05f, 0.98f);
        long jitterNs = ((SystemClock.elapsedRealtimeNanos() >> 4) % 90_000L) - 45_000L;
        long targetExpNs = (long) (2_000_000L + darkness * 31_333_333L) + jitterNs;
        CURRENT_PHOTOMETRY.computedExposureNs = Math.max(minExp, Math.min(maxExp, targetExpNs));

        int rawIso = (int) (minIso + Math.pow(darkness, 1.6) * Math.min(1600, maxIso - minIso));
        int isoJitter = (int) ((SystemClock.elapsedRealtime() % 5) - 2);
        CURRENT_PHOTOMETRY.computedIso = Math.max(minIso, Math.min(maxIso, rawIso + isoJitter));

        boolean isFront = MediaMappings.FACING_FRONT.equals(facing)
                || (profile != null && MediaMappings.FACING_FRONT.equals(profile.facing));
        float baseDiopters = isFront ? 1.85f /* ~54cm */ : 0.75f /* ~1.33m */;
        float focusJitter = (((SystemClock.elapsedRealtime() >> 3) % 7) - 3) * 0.005f;
        CURRENT_PHOTOMETRY.focusDistanceDiopters = Math.max(0.1f, baseDiopters + focusJitter);
        CURRENT_PHOTOMETRY.frameTimestampNs = SystemClock.elapsedRealtimeNanos();
        CURRENT_PHOTOMETRY.frameNumber++;
    }

    @NonNull
    public static byte[] injectExifIntoJpeg(@NonNull byte[] jpegBytes,
                                            int width,
                                            int height,
                                            @Nullable SensorProfiler.LensProfile profile,
                                            @Nullable FramePhotometry photometry) {
        return injectExif(jpegBytes, width, height, profile);
    }

    /**
     * Inject a standard TIFF/EXIF APP1 segment into a compressed JPEG byte array
     * so any {@code ExifInterface} inspection sees authentic camera hardware tags.
     */
    @NonNull
    public static byte[] injectExif(@NonNull byte[] jpegBytes,
                                    int width,
                                    int height,
                                    @Nullable SensorProfiler.LensProfile profile) {
        if (jpegBytes.length < 4 || (jpegBytes[0] & 0xFF) != 0xFF || (jpegBytes[1] & 0xFF) != 0xD8) {
            return jpegBytes;
        }
        try {
            byte[] app1 = buildExifApp1Segment(width, height, profile);
            // Strip an existing APP0/APP1 only if needed, or insert right after SOI (FF D8)
            ByteArrayOutputStream out = new ByteArrayOutputStream(jpegBytes.length + app1.length);
            out.write(0xFF);
            out.write(0xD8);
            out.write(app1);
            out.write(jpegBytes, 2, jpegBytes.length - 2);
            return out.toByteArray();
        } catch (Throwable t) {
            return jpegBytes;
        }
    }

    /**
     * Construct a spec-compliant Little-Endian ("II") EXIF APP1 segment containing:
     * IFD0: Make, Model, Orientation, Software, DateTime, ExifIFDPointer
     * ExifIFD: ExposureTime, FNumber, ISOSpeedRatings, DateTimeOriginal, DateTimeDigitized,
     *          SubSecTimeOriginal, FocalLength, FocalLengthIn35mmFilm, PixelXDimension,
     *          PixelYDimension, WhiteBalance, ExposureMode, SceneCaptureType.
     */
    @NonNull
    private static byte[] buildExifApp1Segment(int width, int height,
                                               @Nullable SensorProfiler.LensProfile p) {
        String make = (p != null && !p.manufacturer.isEmpty()) ? p.manufacturer : Build.MANUFACTURER;
        String model = (p != null && !p.model.isEmpty()) ? p.model : Build.MODEL;
        if (make == null || make.isEmpty()) make = "Google";
        if (model == null || model.isEmpty()) model = "Pixel";
        String software = Build.ID != null ? Build.ID : "Android";

        Date now = new Date();
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US);
        String dateTime = sdf.format(now);
        String subSec = String.format(Locale.US, "%03d", System.currentTimeMillis() % 1000);

        float focalMm = p != null ? p.focalLengthMm : 4.25f;
        float aperture = p != null ? p.apertureFNumber : 1.8f;
        int focal35mm = p != null ? p.focalLength35mmEquivalent() : 26;
        int iso = CURRENT_PHOTOMETRY.computedIso;
        long expNs = CURRENT_PHOTOMETRY.computedExposureNs;

        byte[] makeBytes = asciiNullTerm(make);
        byte[] modelBytes = asciiNullTerm(model);
        byte[] softwareBytes = asciiNullTerm(software);
        byte[] dateBytes = asciiNullTerm(dateTime);
        byte[] subSecBytes = asciiNullTerm(subSec);

        // Build TIFF body in a ByteBuffer (Little Endian 'II')
        ByteBuffer tiff = ByteBuffer.allocate(2048).order(ByteOrder.LITTLE_ENDIAN);
        tiff.put((byte) 'I').put((byte) 'I');
        tiff.putShort((short) 0x002A);
        tiff.putInt(8); // Offset to IFD0

        // IFD0 has 6 entries:
        // 0x010F Make (ASCII)
        // 0x0110 Model (ASCII)
        // 0x0112 Orientation (SHORT = 1 Normal)
        // 0x0131 Software (ASCII)
        // 0x0132 DateTime (ASCII)
        // 0x8769 ExifIFDPointer (LONG)
        int ifd0Count = 6;
        int ifd0Start = 8;
        int ifd0End = ifd0Start + 2 + (ifd0Count * 12) + 4;

        // Data area after IFD0
        int dataCursor = ifd0End;
        int makeOffset = dataCursor; dataCursor += align2(makeBytes.length);
        int modelOffset = dataCursor; dataCursor += align2(modelBytes.length);
        int softOffset = dataCursor; dataCursor += align2(softwareBytes.length);
        int dateOffset = dataCursor; dataCursor += align2(dateBytes.length);
        int exifIfdOffset = dataCursor;

        // Write IFD0
        tiff.position(ifd0Start);
        tiff.putShort((short) ifd0Count);
        putIfdEntry(tiff, 0x010F, 2, makeBytes.length, makeBytes.length <= 4 ? packInline(makeBytes) : makeOffset);
        putIfdEntry(tiff, 0x0110, 2, modelBytes.length, modelBytes.length <= 4 ? packInline(modelBytes) : modelOffset);
        putIfdEntry(tiff, 0x0112, 3, 1, 1); // Orientation = 1 (Top-Left)
        putIfdEntry(tiff, 0x0131, 2, softwareBytes.length, softwareBytes.length <= 4 ? packInline(softwareBytes) : softOffset);
        putIfdEntry(tiff, 0x0132, 2, dateBytes.length, dateOffset);
        putIfdEntry(tiff, 0x8769, 4, 1, exifIfdOffset);
        tiff.putInt(0); // Next IFD = 0

        // Write IFD0 overflow data
        writeBytesAt(tiff, makeOffset, makeBytes);
        writeBytesAt(tiff, modelOffset, modelBytes);
        writeBytesAt(tiff, softOffset, softwareBytes);
        writeBytesAt(tiff, dateOffset, dateBytes);

        // ExifIFD has 12 entries (sorted by tag ascending):
        // 0x829A ExposureTime (RATIONAL)
        // 0x829D FNumber (RATIONAL)
        // 0x8827 ISOSpeedRatings (SHORT)
        // 0x9003 DateTimeOriginal (ASCII)
        // 0x9004 DateTimeDigitized (ASCII)
        // 0x920A FocalLength (RATIONAL)
        // 0x9291 SubSecTimeOriginal (ASCII)
        // 0xA002 PixelXDimension (LONG)
        // 0xA003 PixelYDimension (LONG)
        // 0xA402 ExposureMode (SHORT = 0 Auto)
        // 0xA403 WhiteBalance (SHORT = 0 Auto)
        // 0xA405 FocalLengthIn35mmFilm (SHORT)
        int exifCount = 12;
        int exifEnd = exifIfdOffset + 2 + (exifCount * 12) + 4;
        int exifDataCursor = exifEnd;

        int expTimeOffset = exifDataCursor; exifDataCursor += 8;
        int fNumOffset = exifDataCursor; exifDataCursor += 8;
        int dtoOffset = exifDataCursor; exifDataCursor += align2(dateBytes.length);
        int dtdOffset = exifDataCursor; exifDataCursor += align2(dateBytes.length);
        int focalOffset = exifDataCursor; exifDataCursor += 8;

        tiff.position(exifIfdOffset);
        tiff.putShort((short) exifCount);
        putIfdEntry(tiff, 0x829A, 5, 1, expTimeOffset);
        putIfdEntry(tiff, 0x829D, 5, 1, fNumOffset);
        putIfdEntry(tiff, 0x8827, 3, 1, iso & 0xFFFF);
        putIfdEntry(tiff, 0x9003, 2, dateBytes.length, dtoOffset);
        putIfdEntry(tiff, 0x9004, 2, dateBytes.length, dtdOffset);
        putIfdEntry(tiff, 0x920A, 5, 1, focalOffset);
        putIfdEntry(tiff, 0x9291, 2, subSecBytes.length, packInline(subSecBytes));
        putIfdEntry(tiff, 0xA002, 4, 1, Math.max(1, width));
        putIfdEntry(tiff, 0xA003, 4, 1, Math.max(1, height));
        putIfdEntry(tiff, 0xA402, 3, 1, 0); // Auto exposure
        putIfdEntry(tiff, 0xA403, 3, 1, 0); // Auto white balance
        putIfdEntry(tiff, 0xA405, 3, 1, focal35mm & 0xFFFF);
        tiff.putInt(0);

        // Write ExifIFD RATIONALs and strings
        int expNum = (int) Math.max(1L, expNs / 1000L); // microseconds
        int expDen = 1_000_000;
        tiff.position(expTimeOffset);
        tiff.putInt(expNum).putInt(expDen);

        tiff.position(fNumOffset);
        tiff.putInt(Math.round(aperture * 100)).putInt(100);

        writeBytesAt(tiff, dtoOffset, dateBytes);
        writeBytesAt(tiff, dtdOffset, dateBytes);

        tiff.position(focalOffset);
        tiff.putInt(Math.round(focalMm * 1000)).putInt(1000);

        int tiffLength = exifDataCursor;
        byte[] exifHeader = "Exif\0\0".getBytes(StandardCharsets.US_ASCII);
        int app1PayloadLen = 2 + exifHeader.length + tiffLength;

        ByteBuffer seg = ByteBuffer.allocate(2 + app1PayloadLen).order(ByteOrder.BIG_ENDIAN);
        seg.put((byte) 0xFF).put((byte) 0xE1);
        seg.putShort((short) app1PayloadLen);
        seg.put(exifHeader);
        seg.put(tiff.array(), 0, tiffLength);
        return seg.array();
    }

    private static void putIfdEntry(ByteBuffer b, int tag, int type, int count, int valueOrOffset) {
        b.putShort((short) tag);
        b.putShort((short) type);
        b.putInt(count);
        b.putInt(valueOrOffset);
    }

    private static int packInline(byte[] b) {
        int v = 0;
        for (int i = 0; i < Math.min(4, b.length); i++) {
            v |= (b[i] & 0xFF) << (i * 8);
        }
        return v;
    }

    private static void writeBytesAt(ByteBuffer b, int offset, byte[] data) {
        int saved = b.position();
        b.position(offset);
        b.put(data);
        b.position(saved);
    }

    private static int align2(int len) {
        return (len & 1) == 1 ? len + 1 : len;
    }

    private static byte[] asciiNullTerm(@NonNull String s) {
        byte[] raw = s.getBytes(StandardCharsets.US_ASCII);
        byte[] out = new byte[raw.length + 1];
        System.arraycopy(raw, 0, out, 0, raw.length);
        out[raw.length] = 0;
        return out;
    }

    // ------------------------------------------------------------------
    // Camera2 CaptureResult Photometric Metadata Patcher
    // ------------------------------------------------------------------

    /**
     * Hook {@link CaptureResult#get(CaptureResult.Key)} once per process so
     * that when a hooked host app inspects {@code TotalCaptureResult} for
     * exposure time, ISO sensitivity, AE/AF/AWB convergence state, color gains,
     * focus distance, or frame timestamp, it receives values matching the
     * injected video/image photometry rather than a dark/covered physical sensor.
     */
    public static synchronized void installCaptureResultPatcher(@NonNull ClassLoader classLoader) {
        if (captureResultHooked) return;
        try {
            XposedHelpers.findAndHookMethod(
                    "android.hardware.camera2.CaptureResult",
                    classLoader,
                    "get",
                    CaptureResult.Key.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (param.thisObject != null
                                    && Boolean.TRUE.equals(XposedHelpers.getAdditionalInstanceField(
                                            param.thisObject, "vcamVirtualResult"))) {
                                param.setResult(null);
                            }
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (SensorProfiler.bypassHookForProfiler) return;
                            boolean isVirtual = param.thisObject != null
                                    && Boolean.TRUE.equals(XposedHelpers.getAdditionalInstanceField(
                                            param.thisObject, "vcamVirtualResult"));
                            if (!isVirtual && !HookMain.isMetadataPatchActive()) return;
                            if (param.args.length == 0 || !(param.args[0] instanceof CaptureResult.Key)) {
                                return;
                            }
                            CaptureResult.Key<?> key = (CaptureResult.Key<?>) param.args[0];
                            String name = key.getName();
                            if (name == null) return;

                            if (CaptureResult.SENSOR_EXPOSURE_TIME.getName().equals(name)) {
                                param.setResult(CURRENT_PHOTOMETRY.computedExposureNs);
                            } else if (CaptureResult.SENSOR_SENSITIVITY.getName().equals(name)) {
                                param.setResult(CURRENT_PHOTOMETRY.computedIso);
                            } else if (CaptureResult.SENSOR_TIMESTAMP.getName().equals(name)) {
                                if (isVirtual || param.getResult() == null) {
                                    Object vts = param.thisObject != null
                                            ? XposedHelpers.getAdditionalInstanceField(param.thisObject, "vcamTimestamp")
                                            : null;
                                    param.setResult(vts instanceof Long ? (Long) vts : CURRENT_PHOTOMETRY.frameTimestampNs);
                                }
                            } else if (CaptureResult.CONTROL_AE_STATE.getName().equals(name)) {
                                param.setResult(CameraMetadata.CONTROL_AE_STATE_CONVERGED);
                            } else if (CaptureResult.CONTROL_AF_STATE.getName().equals(name)) {
                                param.setResult(CameraMetadata.CONTROL_AF_STATE_FOCUSED_LOCKED);
                            } else if (CaptureResult.CONTROL_AWB_STATE.getName().equals(name)) {
                                param.setResult(CameraMetadata.CONTROL_AWB_STATE_CONVERGED);
                            } else if (CaptureResult.CONTROL_AE_MODE.getName().equals(name)) {
                                if (isVirtual || param.getResult() == null) {
                                    param.setResult(CameraMetadata.CONTROL_AE_MODE_ON);
                                }
                            } else if (CaptureResult.CONTROL_AF_MODE.getName().equals(name)) {
                                if (isVirtual || param.getResult() == null) {
                                    param.setResult(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                                }
                            } else if (CaptureResult.CONTROL_AWB_MODE.getName().equals(name)) {
                                if (isVirtual || param.getResult() == null) {
                                    param.setResult(CameraMetadata.CONTROL_AWB_MODE_AUTO);
                                }
                            } else if (CaptureResult.FLASH_STATE.getName().equals(name)) {
                                if (isVirtual || param.getResult() == null) {
                                    param.setResult(CameraMetadata.FLASH_STATE_READY);
                                }
                            } else if (CaptureResult.SCALER_CROP_REGION.getName().equals(name)) {
                                if (isVirtual || param.getResult() == null) {
                                    param.setResult(new android.graphics.Rect(0, 0, 4032, 3024));
                                }
                            } else if (CaptureResult.JPEG_ORIENTATION.getName().equals(name)) {
                                if (isVirtual || param.getResult() == null) {
                                    param.setResult(0);
                                }
                            } else if (CaptureResult.LENS_STATE.getName().equals(name)) {
                                param.setResult(CameraMetadata.LENS_STATE_STATIONARY);
                            } else if (CaptureResult.LENS_FOCUS_DISTANCE.getName().equals(name)) {
                                param.setResult(CURRENT_PHOTOMETRY.focusDistanceDiopters);
                            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP
                                    && CaptureResult.COLOR_CORRECTION_GAINS.getName().equals(name)) {
                                param.setResult(new RggbChannelVector(
                                        CURRENT_PHOTOMETRY.rGain,
                                        CURRENT_PHOTOMETRY.gEvenGain,
                                        CURRENT_PHOTOMETRY.gOddGain,
                                        CURRENT_PHOTOMETRY.bGain
                                ));
                            } else if (CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW.getName().equals(name)) {
                                param.setResult(12_500_000L); // ~12.5ms typical CMOS readout skew
                            }
                        }
                    });
            try {
                XposedHelpers.findAndHookMethod("android.hardware.camera2.CaptureResult", classLoader,
                        "getRequest", new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                if (param.thisObject != null
                                        && Boolean.TRUE.equals(XposedHelpers.getAdditionalInstanceField(
                                                param.thisObject, "vcamVirtualResult"))) {
                                    param.setResult(XposedHelpers.getAdditionalInstanceField(
                                            param.thisObject, "vcamRequest"));
                                }
                            }
                        });
                XposedHelpers.findAndHookMethod("android.hardware.camera2.CaptureResult", classLoader,
                        "getFrameNumber", new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                if (param.thisObject != null
                                        && Boolean.TRUE.equals(XposedHelpers.getAdditionalInstanceField(
                                                param.thisObject, "vcamVirtualResult"))) {
                                    Object fn = XposedHelpers.getAdditionalInstanceField(param.thisObject, "vcamFrameNumber");
                                    param.setResult(fn instanceof Long ? (Long) fn : CURRENT_PHOTOMETRY.frameNumber);
                                }
                            }
                        });
                XposedHelpers.findAndHookMethod("android.hardware.camera2.CaptureResult", classLoader,
                        "getSequenceId", new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                if (param.thisObject != null
                                        && Boolean.TRUE.equals(XposedHelpers.getAdditionalInstanceField(
                                                param.thisObject, "vcamVirtualResult"))) {
                                    param.setResult(1);
                                }
                            }
                        });
                XposedHelpers.findAndHookMethod("android.hardware.camera2.TotalCaptureResult", classLoader,
                        "getPartialResults", new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                if (param.thisObject != null
                                        && Boolean.TRUE.equals(XposedHelpers.getAdditionalInstanceField(
                                                param.thisObject, "vcamVirtualResult"))) {
                                    param.setResult(java.util.Collections.emptyList());
                                }
                            }
                        });
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    XposedHelpers.findAndHookMethod("android.hardware.camera2.TotalCaptureResult", classLoader,
                            "getPhysicalCameraResults", new XC_MethodHook() {
                                @Override
                                protected void beforeHookedMethod(MethodHookParam param) {
                                    if (param.thisObject != null
                                            && Boolean.TRUE.equals(XposedHelpers.getAdditionalInstanceField(
                                                    param.thisObject, "vcamVirtualResult"))) {
                                        param.setResult(java.util.Collections.emptyMap());
                                    }
                                }
                            });
                }
            } catch (Throwable ignored) {}
            captureResultHooked = true;
        } catch (Throwable t) {
            XposedBridge.log("[VCAM][metadata-patch] " + t);
        }
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
