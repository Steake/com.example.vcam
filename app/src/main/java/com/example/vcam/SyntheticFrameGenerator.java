package com.example.vcam;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

/**
 * Generates real-time synthetic frames for:
 * <ol>
 *   <li><b>Diagnostic Test Pattern ({@link TransformConfig#SOURCE_TEST_PATTERN})</b>:
 *       SMPTE-inspired color bars, a true-circle aspect ratio gauge (instantly reveals
 *       horizontal or vertical stretching), animated bouncing reticle, live FPS &amp;
 *       frame counter, millisecond timestamp, and host camera telemetry readout.</li>
 *   <li><b>Live QR Code Projection ({@link TransformConfig#SOURCE_QR_CODE})</b>:
 *       Self-contained ISO/IEC 18004 QR Code Version 1–6 (Byte mode, ECC Level L)
 *       generator with zero external dependencies, rendered crisp and aspect-corrected
 *       into the virtual camera feed.</li>
 * </ol>
 */
public final class SyntheticFrameGenerator {

    private static Bitmap reusableFrame;
    private static Bitmap cachedQrBitmap;
    private static String cachedQrPayload = "";
    private static long frameCounter = 0L;
    private static long lastFpsWindowStart = SystemClock.elapsedRealtime();
    private static int framesInWindow = 0;
    private static float measuredFps = 30.0f;

    private SyntheticFrameGenerator() {}

    @NonNull
    public static synchronized Bitmap renderTestPattern(int width, int height,
                                                        @Nullable String pkg,
                                                        @Nullable String facing,
                                                        float fps,
                                                        @NonNull TransformConfig cfg) {
        TransformConfig tmp = cfg.copy();
        tmp.sourceMode = TransformConfig.SOURCE_TEST_PATTERN;
        Bitmap b = renderFrame(width, height, tmp);
        return b != null ? b : Bitmap.createBitmap(Math.max(2, width), Math.max(2, height), Bitmap.Config.ARGB_8888);
    }

    @NonNull
    public static synchronized Bitmap renderQrFrame(int width, int height, @Nullable String payload) {
        TransformConfig tmp = new TransformConfig();
        tmp.sourceMode = TransformConfig.SOURCE_QR_CODE;
        tmp.qrPayload = payload != null ? payload : "";
        Bitmap b = renderFrame(width, height, tmp);
        return b != null ? b : Bitmap.createBitmap(Math.max(2, width), Math.max(2, height), Bitmap.Config.ARGB_8888);
    }

    @Nullable
    public static synchronized Bitmap renderFrame(int width, int height,
                                                  @NonNull TransformConfig cfg) {
        int w = Math.max(160, width & ~1);
        int h = Math.max(120, height & ~1);
        if (reusableFrame == null || reusableFrame.isRecycled()
                || reusableFrame.getWidth() != w || reusableFrame.getHeight() != h) {
            reusableFrame = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        }
        frameCounter++;
        framesInWindow++;
        long now = SystemClock.elapsedRealtime();
        long elapsed = now - lastFpsWindowStart;
        if (elapsed >= 500L) {
            measuredFps = (framesInWindow * 1000.0f) / elapsed;
            framesInWindow = 0;
            lastFpsWindowStart = now;
        }

        Canvas c = new Canvas(reusableFrame);
        if (TransformConfig.SOURCE_QR_CODE.equals(cfg.sourceMode)) {
            drawQrCodeFrame(c, w, h, cfg);
        } else {
            drawDiagnosticPatternFrame(c, w, h, cfg);
        }
        return reusableFrame;
    }

    private static void drawDiagnosticPatternFrame(@NonNull Canvas c, int w, int h,
                                                   @NonNull TransformConfig cfg) {
        c.drawColor(0xFF141820);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        // 1. Top color bars (25% height)
        int[] barColors = {
                0xFFE0E0E0, 0xFFE5D610, 0xFF10D8E5, 0xFF10E528,
                0xFFE510D4, 0xFFE51818, 0xFF183AE5
        };
        float barW = (float) w / barColors.length;
        float barH = h * 0.22f;
        for (int i = 0; i < barColors.length; i++) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(barColors[i]);
            c.drawRect(i * barW, 0, (i + 1) * barW, barH, p);
        }

        // 2. Outer border & grid lines
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(2f, w / 320f));
        p.setColor(0x55FFFFFF);
        c.drawRect(4, 4, w - 4, h - 4, p);
        c.drawLine(w / 2f, 0, w / 2f, h, p);
        c.drawLine(0, h / 2f, w, h / 2f, p);

        // 3. True-circle Aspect Ratio Gauge in the center
        // If this circle looks like an oval in the host app, the aspect ratio is stretched!
        float radius = Math.min(w, h) * 0.28f * cfg.zoom;
        float cx = w / 2f + cfg.panX * (w * 0.3f);
        float cy = h / 2f + cfg.panY * (h * 0.3f);
        p.setColor(0xFF00E5FF);
        p.setStrokeWidth(Math.max(3f, w / 200f));
        c.drawCircle(cx, cy, radius, p);
        c.drawCircle(cx, cy, radius * 0.5f, p);

        // 4. Animated orbiting dot on the circle (proves live cadence)
        double angle = (SystemClock.elapsedRealtime() % 3000L) * (2.0 * Math.PI / 3000.0);
        float dotX = cx + (float) (Math.cos(angle) * radius);
        float dotY = cy + (float) (Math.sin(angle) * radius);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFFFF5252);
        c.drawCircle(dotX, dotY, Math.max(8f, Math.min(w, h) * 0.025f), p);

        // 5. Telemetry & Diagnostics HUD Box
        float textSize = Math.max(14f, Math.min(w, h) * 0.036f);
        p.setTextSize(textSize);
        p.setFakeBoldText(true);
        p.setColor(Color.WHITE);

        String timeStr = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
        String line1 = String.format(Locale.US, "VCAM TEST · %dx%d · %.1f FPS · #%d",
                w, h, measuredFps, frameCounter);
        String line2 = String.format(Locale.US, "Host: %s (%s) · Aspect: %s",
                HookMain.getCurrentHostPackage(), HookMain.getCurrentFacing().toUpperCase(Locale.US),
                cfg.aspectMode.toUpperCase(Locale.US));
        String line3 = String.format(Locale.US, "Time: %s · Zoom: %.2fx · Rot: %d",
                timeStr, cfg.zoom, cfg.rotationMode);

        float boxTop = h * 0.72f;
        Paint bgPaint = new Paint();
        bgPaint.setColor(0xB0000000);
        c.drawRoundRect(new RectF(w * 0.05f, boxTop, w * 0.95f, h * 0.95f), 12f, 12f, bgPaint);

        c.drawText(line1, w * 0.08f, boxTop + textSize * 1.3f, p);
        p.setFakeBoldText(false);
        c.drawText(line2, w * 0.08f, boxTop + textSize * 2.5f, p);
        c.drawText(line3, w * 0.08f, boxTop + textSize * 3.7f, p);
    }

    private static void drawQrCodeFrame(@NonNull Canvas c, int w, int h,
                                        @NonNull TransformConfig cfg) {
        c.drawColor(Color.WHITE);
        String payload = TextUtils.isEmpty(cfg.qrPayload)
                ? "https://github.com/Steake/com.example.vcam" : cfg.qrPayload;
        if (cachedQrBitmap == null || cachedQrBitmap.isRecycled() || !payload.equals(cachedQrPayload)) {
            cachedQrBitmap = generateQrBitmap(payload, 512);
            cachedQrPayload = payload;
        }
        if (cachedQrBitmap != null) {
            float side = Math.min(w, h) * 0.72f * Math.max(0.4f, Math.min(1.35f, cfg.zoom));
            float left = (w - side) / 2f + cfg.panX * (w * 0.25f);
            float top = (h - side) / 2f + cfg.panY * (h * 0.25f);
            Paint qrPaint = new Paint();
            qrPaint.setFilterBitmap(false); // Keep sharp QR module edges
            c.drawBitmap(cachedQrBitmap, null, new RectF(left, top, left + side, top + side), qrPaint);

            Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            textPaint.setColor(0xFF202020);
            textPaint.setTextSize(Math.max(12f, Math.min(w, h) * 0.03f));
            textPaint.setTextAlign(Paint.Align.CENTER);
            String label = payload.length() > 48 ? payload.substring(0, 45) + "..." : payload;
            c.drawText(label, w / 2f, Math.min(h - 16f, top + side + textPaint.getTextSize() * 1.4f), textPaint);
        }
    }

    // ------------------------------------------------------------------
    // Compact Self-Contained ISO/IEC 18004 QR Code Generator (Byte Mode, ECC L)
    // Supports Versions 1..6 (up to 134 bytes of UTF-8 payload)
    // ------------------------------------------------------------------

    private static final int[] VERSION_TOTAL_CODEWORDS = {0, 26, 44, 70, 100, 134, 172};
    private static final int[] VERSION_ECC_CODEWORDS   = {0,  7, 10, 15,  20,  26,  18}; // V6 has 2 blocks of 18
    private static final int[] VERSION_NUM_BLOCKS      = {0,  1,  1,  1,   1,   1,   2};
    private static final int[][] ALIGNMENT_POS = {
            {}, {}, {6, 18}, {6, 22}, {6, 26}, {6, 30}, {6, 34}
    };

    @NonNull
    public static Bitmap generateQrBitmap(@NonNull String text, int pixelSize) {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        if (data.length > 132) {
            data = Arrays.copyOf(data, 132);
        }
        // Choose smallest version 1..6 that fits Byte mode (4 bits mode + 8 bits len + 8*N + 4 term)
        int version = 6;
        for (int v = 1; v <= 6; v++) {
            int dataCap = VERSION_TOTAL_CODEWORDS[v] - VERSION_ECC_CODEWORDS[v] * VERSION_NUM_BLOCKS[v];
            if (data.length + 2 <= dataCap) {
                version = v;
                break;
            }
        }
        boolean[][] modules = encodeQrMatrix(data, version);
        int n = modules.length;
        int quiet = 4;
        int totalModules = n + quiet * 2;
        int scale = Math.max(2, pixelSize / totalModules);
        int bmpSize = totalModules * scale;

        Bitmap bmp = Bitmap.createBitmap(bmpSize, bmpSize, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        canvas.drawColor(Color.WHITE);
        Paint black = new Paint();
        black.setColor(Color.BLACK);
        black.setStyle(Paint.Style.FILL);

        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                if (modules[y][x]) {
                    int px = (x + quiet) * scale;
                    int py = (y + quiet) * scale;
                    canvas.drawRect(px, py, px + scale, py + scale, black);
                }
            }
        }
        return bmp;
    }

    @NonNull
    private static boolean[][] encodeQrMatrix(byte[] payload, int version) {
        int numBlocks = VERSION_NUM_BLOCKS[version];
        int eccLen = VERSION_ECC_CODEWORDS[version];
        int totalCodewords = VERSION_TOTAL_CODEWORDS[version];
        int dataCodewords = totalCodewords - eccLen * numBlocks;

        // 1. Pack bitstream: mode 0100 (4 bits), count (8 bits), payload, terminator (up to 4 bits)
        byte[] dataBytes = new byte[dataCodewords];
        int bitPos = 0;
        bitPos = appendBits(dataBytes, bitPos, 0b0100, 4);
        bitPos = appendBits(dataBytes, bitPos, payload.length, 8);
        for (byte b : payload) {
            bitPos = appendBits(dataBytes, bitPos, b & 0xFF, 8);
        }
        int remBits = dataCodewords * 8 - bitPos;
        bitPos = appendBits(dataBytes, bitPos, 0, Math.min(4, remBits));
        while ((bitPos & 7) != 0) {
            bitPos = appendBits(dataBytes, bitPos, 0, 1);
        }
        int padByte = 0xEC;
        for (int idx = bitPos >> 3; idx < dataCodewords; idx++) {
            dataBytes[idx] = (byte) padByte;
            padByte = (padByte == 0xEC) ? 0x11 : 0xEC;
        }

        // 2. Reed-Solomon ECC & block interleaving
        byte[] finalCodewords = new byte[totalCodewords];
        if (numBlocks == 1) {
            System.arraycopy(dataBytes, 0, finalCodewords, 0, dataCodewords);
            byte[] ecc = reedSolomonRemainder(dataBytes, eccLen);
            System.arraycopy(ecc, 0, finalCodewords, dataCodewords, eccLen);
        } else {
            int blockDataLen = dataCodewords / numBlocks;
            byte[][] dataBlocks = new byte[numBlocks][blockDataLen];
            byte[][] eccBlocks = new byte[numBlocks][eccLen];
            for (int b = 0; b < numBlocks; b++) {
                System.arraycopy(dataBytes, b * blockDataLen, dataBlocks[b], 0, blockDataLen);
                eccBlocks[b] = reedSolomonRemainder(dataBlocks[b], eccLen);
            }
            int k = 0;
            for (int i = 0; i < blockDataLen; i++) {
                for (int b = 0; b < numBlocks; b++) finalCodewords[k++] = dataBlocks[b][i];
            }
            for (int i = 0; i < eccLen; i++) {
                for (int b = 0; b < numBlocks; b++) finalCodewords[k++] = eccBlocks[b][i];
            }
        }

        // 3. Construct function patterns
        int size = version * 4 + 17;
        boolean[][] modules = new boolean[size][size];
        boolean[][] isFunction = new boolean[size][size];

        // Timing patterns
        for (int i = 0; i < size; i++) {
            setFunctionModule(modules, isFunction, 6, i, i % 2 == 0);
            setFunctionModule(modules, isFunction, i, 6, i % 2 == 0);
        }
        // Finder patterns (3 corners)
        drawFinderPattern(modules, isFunction, 3, 3);
        drawFinderPattern(modules, isFunction, size - 4, 3);
        drawFinderPattern(modules, isFunction, 3, size - 4);

        // Alignment patterns
        int[] align = ALIGNMENT_POS[version];
        for (int ay : align) {
            for (int ax : align) {
                if ((ax == 6 && ay == 6) || (ax == 6 && ay == size - 7) || (ax == size - 7 && ay == 6)) {
                    continue;
                }
                drawAlignmentPattern(modules, isFunction, ax, ay);
            }
        }

        // Reserve format info bits + dark module
        for (int i = 0; i <= 8; i++) {
            if (i != 6) {
                isFunction[8][i] = true;
                isFunction[i][8] = true;
            }
        }
        for (int i = 0; i < 8; i++) {
            isFunction[8][size - 1 - i] = true;
            isFunction[size - 1 - i][8] = true;
        }
        setFunctionModule(modules, isFunction, 8, size - 8, true);

        // 4. Zigzag data placement
        int bitIdx = 0;
        int totalBits = finalCodewords.length * 8;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5;
            for (int vert = 0; vert < size; vert++) {
                for (int j = 0; j < 2; j++) {
                    int x = right - j;
                    boolean upward = ((right + 1) & 2) == 0;
                    int y = upward ? size - 1 - vert : vert;
                    if (!isFunction[y][x] && bitIdx < totalBits) {
                        boolean bit = ((finalCodewords[bitIdx >>> 3] >>> (7 - (bitIdx & 7))) & 1) != 0;
                        // Mask 0: (x + y) % 2 == 0
                        if (((x + y) & 1) == 0) bit = !bit;
                        modules[y][x] = bit;
                        bitIdx++;
                    }
                }
            }
        }

        // 5. Write Format Information for ECC Level L (01) and Mask 0 (000) -> 0x77C4
        int formatBits = 0x77C4;
        for (int i = 0; i <= 5; i++) setFunctionModule(modules, isFunction, 8, i, ((formatBits >>> i) & 1) != 0);
        setFunctionModule(modules, isFunction, 8, 7, ((formatBits >>> 6) & 1) != 0);
        setFunctionModule(modules, isFunction, 8, 8, ((formatBits >>> 7) & 1) != 0);
        setFunctionModule(modules, isFunction, 7, 8, ((formatBits >>> 8) & 1) != 0);
        for (int i = 9; i < 15; i++) setFunctionModule(modules, isFunction, 14 - i, 8, ((formatBits >>> i) & 1) != 0);

        for (int i = 0; i < 8; i++) setFunctionModule(modules, isFunction, size - 1 - i, 8, ((formatBits >>> i) & 1) != 0);
        for (int i = 8; i < 15; i++) setFunctionModule(modules, isFunction, 8, size - 15 + i, ((formatBits >>> i) & 1) != 0);

        return modules;
    }

    private static int appendBits(byte[] buf, int bitPos, int val, int numBits) {
        for (int i = numBits - 1; i >= 0; i--, bitPos++) {
            if (((val >>> i) & 1) != 0) {
                buf[bitPos >>> 3] |= (byte) (1 << (7 - (bitPos & 7)));
            }
        }
        return bitPos;
    }

    private static void setFunctionModule(boolean[][] modules, boolean[][] isFunc, int x, int y, boolean black) {
        if (x >= 0 && y >= 0 && y < modules.length && x < modules.length) {
            modules[y][x] = black;
            isFunc[y][x] = true;
        }
    }

    private static void drawFinderPattern(boolean[][] modules, boolean[][] isFunc, int cx, int cy) {
        for (int dy = -4; dy <= 4; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                int dist = Math.max(Math.abs(dx), Math.abs(dy));
                int x = cx + dx;
                int y = cy + dy;
                if (x >= 0 && x < modules.length && y >= 0 && y < modules.length) {
                    setFunctionModule(modules, isFunc, x, y, dist != 2 && dist != 4);
                }
            }
        }
    }

    private static void drawAlignmentPattern(boolean[][] modules, boolean[][] isFunc, int cx, int cy) {
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                setFunctionModule(modules, isFunc, cx + dx, cy + dy, Math.max(Math.abs(dx), Math.abs(dy)) != 1);
            }
        }
    }

    private static byte[] reedSolomonRemainder(byte[] data, int degree) {
        byte[] gen = new byte[degree];
        gen[degree - 1] = 1;
        int root = 1;
        for (int i = 0; i < degree; i++) {
            for (int j = 0; j < degree; j++) {
                gen[j] = (byte) gfMultiply(gen[j] & 0xFF, root);
                if (j + 1 < degree) gen[j] ^= gen[j + 1];
            }
            root = gfMultiply(root, 0x02);
        }
        byte[] result = new byte[degree];
        for (byte b : data) {
            int factor = (b ^ result[0]) & 0xFF;
            System.arraycopy(result, 1, result, 0, degree - 1);
            result[degree - 1] = 0;
            for (int i = 0; i < degree; i++) {
                result[i] ^= (byte) gfMultiply(gen[i] & 0xFF, factor);
            }
        }
        return result;
    }

    private static int gfMultiply(int x, int y) {
        int z = 0;
        for (int i = 7; i >= 0; i--) {
            z = (z << 1) ^ ((z >>> 7) * 0x11D);
            z ^= ((y >>> i) & 1) * x;
        }
        return z;
    }
}
