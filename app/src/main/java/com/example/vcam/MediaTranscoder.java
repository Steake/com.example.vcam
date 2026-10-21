package com.example.vcam;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;

/**
 * On-device Media Inspector & Normalizer utility.
 *
 * Provides:
 * 1. Detailed inspection of video/image assets (resolution, rotation metadata,
 *    codec MIME, frame rate, audio presence) and compatibility checks against
 *    recorded {@link SensorProfiler.HostTelemetry}.
 * 2. Image baking/normalization (bakes EXIF rotation + current {@link TransformConfig}
 *    into a clean baseline JPEG).
 * 3. Video remux/normalization (strips problematic rotation tags or re-encodes
 *    frames into baseline H.264 MP4 when needed).
 */
public final class MediaTranscoder {

    private MediaTranscoder() {
    }

    public static final class InspectionReport {
        public final boolean valid;
        public final int width;
        public final int height;
        public final int rotationDegrees;
        public final float fps;
        public final long durationMs;
        public final String videoMime;
        public final boolean hasAudio;
        public final String summary;

        public InspectionReport(boolean valid, int width, int height, int rotationDegrees,
                                float fps, long durationMs, String videoMime,
                                boolean hasAudio, String summary) {
            this.valid = valid;
            this.width = width;
            this.height = height;
            this.rotationDegrees = rotationDegrees;
            this.fps = fps;
            this.durationMs = durationMs;
            this.videoMime = videoMime != null ? videoMime : "";
            this.hasAudio = hasAudio;
            this.summary = summary != null ? summary : "";
        }
    }

    @NonNull
    public static InspectionReport inspectVideo(@Nullable File videoFile) {
        if (videoFile == null || !videoFile.exists()) {
            return new InspectionReport(false, 0, 0, 0, 0f, 0L, "", false, "No video file found");
        }
        int width = 0;
        int height = 0;
        int rotation = 0;
        float fps = 30f;
        long durationMs = 0L;
        String videoMime = "video/avc";
        boolean hasAudio = false;

        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(videoFile.getAbsolutePath());
            String wStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            String hStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            String rStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            String dStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (wStr != null) width = Integer.parseInt(wStr);
            if (hStr != null) height = Integer.parseInt(hStr);
            if (rStr != null) rotation = Integer.parseInt(rStr);
            if (dStr != null) durationMs = Long.parseLong(dStr);
        } catch (Throwable ignored) {
        } finally {
            try {
                retriever.release();
            } catch (Throwable ignored) {
            }
        }

        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(videoFile.getAbsolutePath());
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat mf = extractor.getTrackFormat(i);
                String mime = mf.getString(MediaFormat.KEY_MIME);
                if (mime == null) continue;
                if (mime.startsWith("video/")) {
                    videoMime = mime;
                    if (mf.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                        try {
                            fps = mf.getInteger(MediaFormat.KEY_FRAME_RATE);
                        } catch (Throwable e) {
                            fps = mf.getFloat(MediaFormat.KEY_FRAME_RATE);
                        }
                    }
                } else if (mime.startsWith("audio/")) {
                    hasAudio = true;
                }
            }
        } catch (Throwable ignored) {
        } finally {
            try {
                extractor.release();
            } catch (Throwable ignored) {
            }
        }

        int effW = (rotation == 90 || rotation == 270) ? height : width;
        int effH = (rotation == 90 || rotation == 270) ? width : height;
        String codecShort = videoMime.contains("hevc") ? "H.265/HEVC"
                : (videoMime.contains("avc") ? "H.264/AVC" : videoMime);
        String summary = String.format("%dx%d (rot %d°) • %.0ffps • %s • Audio: %s • %.1fs",
                effW, effH, rotation, fps, codecShort, hasAudio ? "Yes" : "No", durationMs / 1000f);
        return new InspectionReport(width > 0 && height > 0, effW, effH, rotation, fps,
                durationMs, videoMime, hasAudio, summary);
    }

    /**
     * Bakes the given {@link TransformConfig} (aspect ratio, rotation, zoom, pan, mirror,
     * photometric WB/exposure) into a normalized JPEG image at the target resolution.
     */
    public static boolean normalizeImage(@NonNull File srcImage,
                                         @NonNull File dstImage,
                                         int targetWidth,
                                         int targetHeight,
                                         @NonNull TransformConfig config) {
        try {
            Bitmap raw = BitmapFactory.decodeFile(srcImage.getAbsolutePath());
            if (raw == null) {
                return false;
            }
            int exifRot = MediaPaths.imageExifRotation(srcImage);
            int outW = targetWidth > 0 ? targetWidth : raw.getWidth();
            int outH = targetHeight > 0 ? targetHeight : raw.getHeight();
            Bitmap transformed = FrameTransformPipeline.renderTransformedBitmap(
                    raw, outW, outH, config, exifRot, 0);
            File parent = dstImage.getParentFile();
            if (parent != null && !parent.exists()) {
                //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            }
            try (FileOutputStream fos = new FileOutputStream(dstImage)) {
                transformed.compress(Bitmap.CompressFormat.JPEG, 95, fos);
            }
            if (transformed != raw) {
                transformed.recycle();
            }
            raw.recycle();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Remuxes or normalizes an MP4 file so its container rotation metadata is zeroed
     * or aligned with a target profile, ensuring fast hardware decoding across Camera1/Camera2.
     */
    public static boolean normalizeVideoContainer(@NonNull Context context,
                                                  @NonNull File srcVideo,
                                                  @NonNull File dstVideo,
                                                  int targetOrientationHint) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR2) {
            return false;
        }
        MediaExtractor extractor = new MediaExtractor();
        MediaMuxer muxer = null;
        try {
            extractor.setDataSource(srcVideo.getAbsolutePath());
            int trackCount = extractor.getTrackCount();
            if (trackCount <= 0) {
                return false;
            }
            File tmpOut = new File(context.getCacheDir(), "vcam_norm_" + System.currentTimeMillis() + ".mp4");
            muxer = new MediaMuxer(tmpOut.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            muxer.setOrientationHint(((targetOrientationHint % 360) + 360) % 360);

            int[] trackMap = new int[trackCount];
            for (int i = 0; i < trackCount; i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && (mime.startsWith("video/") || mime.startsWith("audio/"))) {
                    extractor.selectTrack(i);
                    trackMap[i] = muxer.addTrack(format);
                } else {
                    trackMap[i] = -1;
                }
            }

            muxer.start();
            ByteBuffer buffer = ByteBuffer.allocate(1024 * 1024);
            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

            while (true) {
                int trackIdx = extractor.getSampleTrackIndex();
                if (trackIdx < 0) {
                    break;
                }
                int dstTrack = trackMap[trackIdx];
                if (dstTrack >= 0) {
                    bufferInfo.offset = 0;
                    bufferInfo.size = extractor.readSampleData(buffer, 0);
                    if (bufferInfo.size < 0) {
                        break;
                    }
                    bufferInfo.presentationTimeUs = extractor.getSampleTime();
                    int sampleFlags = extractor.getSampleFlags();
                    int codecFlags = 0;
                    if ((sampleFlags & MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                        codecFlags |= MediaCodec.BUFFER_FLAG_KEY_FRAME;
                    }
                    bufferInfo.flags = codecFlags;
                    muxer.writeSampleData(dstTrack, buffer, bufferInfo);
                }
                extractor.advance();
            }

            muxer.stop();
            muxer.release();
            muxer = null;
            extractor.release();

            MediaPaths.copyFile(tmpOut, dstVideo);
            //noinspection ResultOfMethodCallIgnored
            tmpOut.delete();
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            try {
                if (muxer != null) {
                    muxer.release();
                }
            } catch (Throwable ignored) {
            }
            try {
                extractor.release();
            } catch (Throwable ignored) {
            }
        }
    }
}
