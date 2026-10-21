package com.example.vcam;

import android.annotation.SuppressLint;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;

import de.robv.android.xposed.XposedBridge;

// Adapted from https://github.com/zhantong/Android-VideoToImages
public class VideoToFrames implements Runnable {
    private static final String TAG = "VideoToFrames";
    private static final boolean VERBOSE = false;
    private static final long DEFAULT_TIMEOUT_US = 10000;

    private static final int COLOR_FormatI420 = 1;
    private static final int COLOR_FormatNV21 = 2;

    private final int decodeColorFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible;

    private LinkedBlockingQueue<byte[]> mQueue;
    private OutputImageFormat outputImageFormat;
    private volatile boolean stopDecode = false;

    private String videoFilePath;
    private FileDescriptor videoFd;
    private Throwable throwable;
    private Thread childThread;
    private Surface play_surf;

    private int targetWidth = 0;
    private int targetHeight = 0;
    private volatile long currentPositionMs = 0L;
    private volatile long durationMs = 0L;
    private long lastHandledSeekTimestamp = 0L;
    private volatile boolean explicitPaused = false;
    private volatile long explicitSeekMs = -1L;

    private Callback callback;

    public interface Callback {
        void onFinishDecode();

        void onDecodeFrame(int index);
    }

    public void setCallback(Callback callback) {
        this.callback = callback;
    }

    public void setEnqueue(LinkedBlockingQueue<byte[]> queue) {
        mQueue = queue;
    }

    public void setTargetDimensions(int width, int height) {
        this.targetWidth = width & ~1;
        this.targetHeight = height & ~1;
    }

    public void setPaused(boolean paused) {
        this.explicitPaused = paused;
    }

    public void seekToMs(long positionMs) {
        this.explicitSeekMs = Math.max(0L, positionMs);
    }

    public long getCurrentPositionMs() {
        return currentPositionMs;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public void setSaveFrames(String dir, OutputImageFormat imageFormat) throws IOException {
        outputImageFormat = imageFormat;
    }

    public void set_surfcae(Surface player_surface) {
        if (player_surface != null) {
            play_surf = player_surface;
        }
    }

    public void stopDecode() {
        stopDecode = true;
        if (childThread != null) {
            childThread.interrupt();
        }
    }

    public void decode(String videoFilePath) throws Throwable {
        this.videoFilePath = videoFilePath;
        if (childThread == null) {
            childThread = new Thread(this, "decode");
            childThread.setDaemon(true);
            childThread.start();
            if (throwable != null) {
                throw throwable;
            }
        }
    }

    public void decode(FileDescriptor fd) throws Throwable {
        decodeFd(fd, "");
    }

    public void decodeStream(String streamUrl) throws Throwable {
        this.videoFilePath = streamUrl;
        if (childThread == null) {
            childThread = new Thread(this, "decode-stream");
            childThread.setDaemon(true);
            childThread.start();
            if (throwable != null) {
                throw throwable;
            }
        }
    }

    public void decodeFd(FileDescriptor fd, String fallbackPath) throws Throwable {
        this.videoFd = fd;
        this.videoFilePath = fallbackPath;
        if (childThread == null) {
            childThread = new Thread(this, "decode-fd");
            childThread.setDaemon(true);
            childThread.start();
            if (throwable != null) {
                throw throwable;
            }
        }
    }

    public void run() {
        try {
            videoDecode(videoFilePath);
        } catch (Throwable t) {
            throwable = t;
        }
    }

    @SuppressLint("WrongConstant")
    public void videoDecode(String videoFilePath) throws IOException {
        XposedBridge.log("[VCAM][decoder] Starting decode");
        MediaExtractor extractor = null;
        MediaCodec decoder = null;
        try {
            extractor = new MediaExtractor();
            TransformConfig initCfg = HookMain.getActiveTransformConfig();
            if (TransformConfig.SOURCE_STREAM.equals(initCfg.sourceMode)
                    && initCfg.streamUrl != null && !initCfg.streamUrl.isEmpty()) {
                extractor.setDataSource(initCfg.streamUrl);
            } else if (videoFilePath != null && (videoFilePath.startsWith("http://")
                    || videoFilePath.startsWith("https://") || videoFilePath.startsWith("rtsp://"))) {
                extractor.setDataSource(videoFilePath);
            } else if (videoFd != null && videoFd.valid()) {
                extractor.setDataSource(videoFd);
            } else {
                File videoFile = new File(videoFilePath);
                if (!videoFile.exists()) return;
                extractor.setDataSource(videoFilePath);
            }
            int trackIndex = selectTrack(extractor);
            if (trackIndex < 0) {
                XposedBridge.log("[VCAM][decoder] No video track found in " + videoFilePath);
                return;
            }
            extractor.selectTrack(trackIndex);
            MediaFormat mediaFormat = extractor.getTrackFormat(trackIndex);
            if (mediaFormat.containsKey(MediaFormat.KEY_DURATION)) {
                durationMs = mediaFormat.getLong(MediaFormat.KEY_DURATION) / 1000L;
            }
            String mime = mediaFormat.getString(MediaFormat.KEY_MIME);
            decoder = MediaCodec.createDecoderByType(mime);
            showSupportedColorFormat(decoder.getCodecInfo().getCapabilitiesForType(mime));
            if (isColorFormatSupported(decodeColorFormat, decoder.getCodecInfo().getCapabilitiesForType(mime))) {
                mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, decodeColorFormat);
                XposedBridge.log("[VCAM][decoder] Set decode color format to type " + decodeColorFormat);
            } else {
                Log.i(TAG, "unable to set decode color format, color format type " + decodeColorFormat + " not supported");
                XposedBridge.log("[VCAM][decoder] Unable to set decode color format, color format type " + decodeColorFormat + " not supported");
            }
            decodeFramesToImage(decoder, extractor, mediaFormat);
            decoder.stop();
            while (!stopDecode && HookMain.isLoopVideoActive()) {
                extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                decodeFramesToImage(decoder, extractor, mediaFormat);
                decoder.stop();
            }
        } catch (Exception e) {
            XposedBridge.log("[VCAM][videofile] " + e.toString());
        } finally {
            if (decoder != null) {
                try { decoder.stop(); } catch (Throwable ignored) {}
                try { decoder.release(); } catch (Throwable ignored) {}
                decoder = null;
            }
            if (extractor != null) {
                try { extractor.release(); } catch (Throwable ignored) {}
                extractor = null;
            }
        }
    }

    private void showSupportedColorFormat(MediaCodecInfo.CodecCapabilities caps) {
        System.out.print("supported color format: ");
        for (int c : caps.colorFormats) {
            System.out.print(c + "\t");
        }
        System.out.println();
    }

    private boolean isColorFormatSupported(int colorFormat, MediaCodecInfo.CodecCapabilities caps) {
        for (int c : caps.colorFormats) {
            if (c == colorFormat) {
                return true;
            }
        }
        return false;
    }

    private void decodeFramesToImage(MediaCodec decoder, MediaExtractor extractor, MediaFormat mediaFormat) {
        boolean is_first = false;
        long startWhen = 0;
        long basePtsUs = 0;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        decoder.configure(mediaFormat, play_surf, null, 0);
        boolean sawInputEOS = false;
        boolean sawOutputEOS = false;
        decoder.start();
        final int width = mediaFormat.getInteger(MediaFormat.KEY_WIDTH);
        final int height = mediaFormat.getInteger(MediaFormat.KEY_HEIGHT);
        int outputFrameCount = 0;
        while (!sawOutputEOS && !stopDecode) {
            TransformConfig cfg = HookMain.getActiveTransformConfig();

            // 1. Handle pause (freeze-frame): keep HookMain.data_buffer alive with sensor grain
            if (cfg.paused || explicitPaused) {
                if (cfg.sensorGrainEnabled && HookMain.data_buffer != null && HookMain.data_buffer.length > 16) {
                    int w = targetWidth > 0 ? targetWidth : width;
                    int h = targetHeight > 0 ? targetHeight : height;
                    FrameTransformPipeline.applyLumaGrainInPlace(HookMain.data_buffer, w, h);
                }
                ExifSynthesizer.CURRENT_PHOTOMETRY.frameTimestampNs = SystemClock.elapsedRealtimeNanos();
                try {
                    Thread.sleep(33L);
                } catch (InterruptedException e) {
                    return;
                }
                startWhen = System.currentTimeMillis() - (info.presentationTimeUs - basePtsUs) / 1000L;
                continue;
            }

            // 2. Handle explicit seek requests or active CuePoint boundaries
            long reqSeekMs = explicitSeekMs;
            if (reqSeekMs >= 0) {
                explicitSeekMs = -1L;
                extractor.seekTo(reqSeekMs * 1000L, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                try { decoder.flush(); } catch (Throwable ignored) {}
                sawInputEOS = false;
                is_first = false;
            } else if (cfg.seekRequestMs >= 0 && cfg.seekRequestTimestamp > lastHandledSeekTimestamp) {
                lastHandledSeekTimestamp = cfg.seekRequestTimestamp;
                long seekUs = cfg.seekRequestMs * 1000L;
                extractor.seekTo(seekUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                try { decoder.flush(); } catch (Throwable ignored) {}
                sawInputEOS = false;
                is_first = false;
            } else if (cfg.activeCueId > 0) {
                TransformConfig.CuePoint cue = HookMain.findCueById(cfg.activeCueId);
                if (cue != null && cue.endMs > cue.startMs && currentPositionMs >= cue.endMs) {
                    if (cue.loop) {
                        extractor.seekTo(cue.startMs * 1000L, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                        try { decoder.flush(); } catch (Throwable ignored) {}
                        sawInputEOS = false;
                        is_first = false;
                    }
                }
            }

            if (!sawInputEOS) {
                int inputBufferId = decoder.dequeueInputBuffer(DEFAULT_TIMEOUT_US);
                if (inputBufferId >= 0) {
                    ByteBuffer inputBuffer = decoder.getInputBuffer(inputBufferId);
                    int sampleSize = extractor.readSampleData(inputBuffer, 0);
                    if (sampleSize < 0) {
                        decoder.queueInputBuffer(inputBufferId, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        sawInputEOS = true;
                    } else {
                        long presentationTimeUs = extractor.getSampleTime();
                        decoder.queueInputBuffer(inputBufferId, 0, sampleSize, presentationTimeUs, 0);
                        extractor.advance();
                    }
                }
            }
            int outputBufferId = decoder.dequeueOutputBuffer(info, DEFAULT_TIMEOUT_US);
            if (outputBufferId >= 0) {
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    sawOutputEOS = true;
                }
                boolean doRender = (info.size != 0);
                if (doRender) {
                    outputFrameCount++;
                    currentPositionMs = Math.max(0L, info.presentationTimeUs / 1000L);
                    ExifSynthesizer.CURRENT_PHOTOMETRY.frameTimestampNs = SystemClock.elapsedRealtimeNanos();
                    ExifSynthesizer.CURRENT_PHOTOMETRY.frameNumber++;

                    if (callback != null) {
                        callback.onDecodeFrame(outputFrameCount);
                    }
                    if (!is_first) {
                        startWhen = System.currentTimeMillis();
                        basePtsUs = info.presentationTimeUs;
                        is_first = true;
                    }
                    if (play_surf == null) {
                        Image image = decoder.getOutputImage(outputBufferId);
                        if (image != null) {
                            try {
                                if (mQueue != null) {
                                    ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                                    byte[] arr = new byte[buffer.remaining()];
                                    buffer.get(arr);
                                    try {
                                        mQueue.put(arr);
                                    } catch (InterruptedException e) {
                                        XposedBridge.log("[VCAM]" + e.toString());
                                    }
                                }
                                if (outputImageFormat != null) {
                                    byte[] rawNv21 = getDataFromImage(image, COLOR_FormatNV21);
                                    Rect crop = image.getCropRect();
                                    int srcW = crop.width();
                                    int srcH = crop.height();
                                    // Update photometry from luma plane
                                    updateLumaPhotometry(rawNv21, srcW, srcH);

                                    int dstW = targetWidth > 0 ? targetWidth : srcW;
                                    int dstH = targetHeight > 0 ? targetHeight : srcH;
                                    if (srcW == dstW && srcH == dstH) {
                                        if (cfg.sensorGrainEnabled) {
                                            FrameTransformPipeline.applyLumaGrainInPlace(rawNv21, dstW, dstH);
                                        }
                                        HookMain.data_buffer = rawNv21;
                                    } else {
                                        // Scale NV21 to the exact target (dstW, dstH) so UV planes never misalign
                                        byte[] scaled = scaleNv21Nearest(rawNv21, srcW, srcH, dstW, dstH, cfg);
                                        if (cfg.sensorGrainEnabled) {
                                            FrameTransformPipeline.applyLumaGrainInPlace(scaled, dstW, dstH);
                                        }
                                        HookMain.data_buffer = scaled;
                                    }
                                }
                            } finally {
                                image.close();
                            }
                        }
                    }
                    long sleepTime = (info.presentationTimeUs - basePtsUs) / 1000L - (System.currentTimeMillis() - startWhen);
                    if (sleepTime > 0) {
                        try {
                            Thread.sleep(Math.min(sleepTime, 100L));
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                    decoder.releaseOutputBuffer(outputBufferId, true);
                } else {
                    decoder.releaseOutputBuffer(outputBufferId, false);
                }
            }
        }
        if (callback != null) {
            callback.onFinishDecode();
        }
    }

    private static void updateLumaPhotometry(byte[] nv21, int w, int h) {
        int ySize = w * h;
        if (nv21 == null || nv21.length < ySize || ySize <= 0) return;
        long sum = 0;
        int count = 0;
        int stride = Math.max(1, ySize / 64);
        for (int i = 0; i < ySize; i += stride) {
            sum += (nv21[i] & 0xFF);
            count++;
        }
        if (count > 0) {
            float meanY = (float) sum / count;
            ExifSynthesizer.CURRENT_PHOTOMETRY.meanLuma = meanY;
            float darkness = 1.0f - Math.max(0.05f, Math.min(0.98f, meanY / 240.0f));
            ExifSynthesizer.CURRENT_PHOTOMETRY.computedExposureNs =
                    (long) (2_000_000L + darkness * 30_000_000L);
            ExifSynthesizer.CURRENT_PHOTOMETRY.computedIso =
                    (int) (60 + darkness * 1200);
        }
    }

    /**
     * Fast nearest-neighbor aspect-aware NV21 resampler so Camera1 callbacks
     * always receive a buffer whose Y and interleaved VU planes match the exact
     * {@code (dstW, dstH)} requested by the host's {@code Camera.Parameters}.
     */
    private static byte[] scaleNv21Nearest(byte[] src, int srcW, int srcH,
                                           int dstW, int dstH,
                                           TransformConfig cfg) {
        dstW &= ~1;
        dstH &= ~1;
        int dstYSize = dstW * dstH;
        byte[] dst = new byte[dstYSize * 3 / 2];
        // Initialize black Y=16, V=128, U=128 for letterbox bars
        java.util.Arrays.fill(dst, 0, dstYSize, (byte) 16);
        java.util.Arrays.fill(dst, dstYSize, dst.length, (byte) 128);

        float scaleX = (float) dstW / srcW;
        float scaleY = (float) dstH / srcH;
        float scale;
        if (TransformConfig.ASPECT_STRETCH.equals(cfg.aspectMode)) {
            scaleX *= cfg.zoom;
            scaleY *= cfg.zoom;
        } else if (TransformConfig.ASPECT_FILL.equals(cfg.aspectMode)) {
            scale = Math.max(scaleX, scaleY) * cfg.zoom;
            scaleX = scale;
            scaleY = scale;
        } else {
            scale = Math.min(scaleX, scaleY) * cfg.zoom;
            scaleX = scale;
            scaleY = scale;
        }

        int targetBoxW = Math.max(2, Math.round(srcW * scaleX));
        int targetBoxH = Math.max(2, Math.round(srcH * scaleY));
        int offsetX = (dstW - targetBoxW) / 2 + Math.round(cfg.panX * dstW * 0.5f);
        int offsetY = (dstH - targetBoxH) / 2 + Math.round(cfg.panY * dstH * 0.5f);

        int srcYSize = srcW * srcH;
        for (int dy = 0; dy < dstH; dy++) {
            int sy = ((dy - offsetY) * srcH) / targetBoxH;
            if (cfg.mirrorV) sy = srcH - 1 - sy;
            if (sy < 0 || sy >= srcH) continue;
            int dstRow = dy * dstW;
            int srcRow = sy * srcW;
            int dstUvRow = dstYSize + (dy >> 1) * dstW;
            int srcUvRow = srcYSize + (sy >> 1) * srcW;
            boolean evenRow = (dy & 1) == 0;

            for (int dx = 0; dx < dstW; dx++) {
                int sx = ((dx - offsetX) * srcW) / targetBoxW;
                if (cfg.mirrorH) sx = srcW - 1 - sx;
                if (sx < 0 || sx >= srcW) continue;
                dst[dstRow + dx] = src[srcRow + sx];
                if (evenRow && (dx & 1) == 0) {
                    int sUv = srcUvRow + (sx & ~1);
                    int dUv = dstUvRow + dx;
                    if (sUv + 1 < src.length && dUv + 1 < dst.length) {
                        dst[dUv] = src[sUv];
                        dst[dUv + 1] = src[sUv + 1];
                    }
                }
            }
        }
        return dst;
    }

    private static int selectTrack(MediaExtractor extractor) {
        int numTracks = extractor.getTrackCount();
        for (int i = 0; i < numTracks; i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("video/")) {
                if (VERBOSE) {
                    Log.d(TAG, "Extractor selected track " + i + " (" + mime + "): " + format);
                }
                return i;
            }
        }
        return -1;
    }

    private static boolean isImageFormatSupported(Image image) {
        int format = image.getFormat();
        switch (format) {
            case ImageFormat.YUV_420_888:
            case ImageFormat.NV21:
            case ImageFormat.YV12:
                return true;
        }
        return false;
    }

    private static byte[] getDataFromImage(Image image, int colorFormat) {
        if (colorFormat != COLOR_FormatI420 && colorFormat != COLOR_FormatNV21) {
            throw new IllegalArgumentException("only support COLOR_FormatI420 and COLOR_FormatNV21");
        }
        if (!isImageFormatSupported(image)) {
            throw new RuntimeException("can't convert Image to byte array, format " + image.getFormat());
        }
        Rect crop = image.getCropRect();
        int format = image.getFormat();
        int width = crop.width();
        int height = crop.height();
        Image.Plane[] planes = image.getPlanes();
        byte[] data = new byte[width * height * ImageFormat.getBitsPerPixel(format) / 8];
        byte[] rowData = new byte[planes[0].getRowStride()];
        if (VERBOSE) Log.v(TAG, "get data from " + planes.length + " planes");
        int channelOffset = 0;
        int outputStride = 1;
        for (int i = 0; i < planes.length; i++) {
            switch (i) {
                case 0:
                    channelOffset = 0;
                    outputStride = 1;
                    break;
                case 1:
                    if (colorFormat == COLOR_FormatI420) {
                        channelOffset = width * height;
                        outputStride = 1;
                    } else if (colorFormat == COLOR_FormatNV21) {
                        channelOffset = width * height + 1;
                        outputStride = 2;
                    }
                    break;
                case 2:
                    if (colorFormat == COLOR_FormatI420) {
                        channelOffset = (int) (width * height * 1.25);
                        outputStride = 1;
                    } else if (colorFormat == COLOR_FormatNV21) {
                        channelOffset = width * height;
                        outputStride = 2;
                    }
                    break;
            }
            ByteBuffer buffer = planes[i].getBuffer();
            int rowStride = planes[i].getRowStride();
            int pixelStride = planes[i].getPixelStride();
            int shift = (i == 0) ? 0 : 1;
            int w = width >> shift;
            int h = height >> shift;
            buffer.position(rowStride * (crop.top >> shift) + pixelStride * (crop.left >> shift));
            for (int row = 0; row < h; row++) {
                int length;
                if (pixelStride == 1 && outputStride == 1) {
                    length = w;
                    buffer.get(data, channelOffset, length);
                    channelOffset += length;
                } else {
                    length = (w - 1) * pixelStride + 1;
                    buffer.get(rowData, 0, length);
                    for (int col = 0; col < w; col++) {
                        data[channelOffset] = rowData[col * pixelStride];
                        channelOffset += outputStride;
                    }
                }
                if (row < h - 1) {
                    buffer.position(buffer.position() + rowStride - length);
                }
            }
        }
        return data;
    }
}

enum OutputImageFormat {
    I420("I420"),
    NV21("NV21"),
    JPEG("JPEG");
    private final String friendlyName;

    OutputImageFormat(String friendlyName) {
        this.friendlyName = friendlyName;
    }

    public String toString() {
        return friendlyName;
    }
}
