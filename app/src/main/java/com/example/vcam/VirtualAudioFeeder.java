package com.example.vcam;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Virtual Microphone engine that hooks {@link AudioRecord} inside target apps
 * when {@code virtualMicEnabled} is active during an active camera session.
 *
 * Decodes the audio track from the active virtual video (via local File or
 * ContentProvider ParcelFileDescriptor), resamples 16-bit PCM to match the
 * host AudioRecord's sample rate and channel count, and feeds read() calls.
 */
public final class VirtualAudioFeeder {
    private static final String TAG = "VCAM_VirtualAudio";
    private static final AtomicBoolean HOOKS_INSTALLED = new AtomicBoolean(false);
    private static final Map<AudioRecord, DecoderSession> SESSIONS = new ConcurrentHashMap<>();

    private VirtualAudioFeeder() {
    }

    public static void installHooks() {
        if (!HOOKS_INSTALLED.compareAndSet(false, true)) {
            return;
        }
        try {
            XposedBridge.hookAllMethods(AudioRecord.class, "startRecording", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!(param.thisObject instanceof AudioRecord)) {
                        return;
                    }
                    AudioRecord ar = (AudioRecord) param.thisObject;
                    if (shouldIntercept()) {
                        ensureSession(ar);
                    }
                }
            });

            XposedBridge.hookAllMethods(AudioRecord.class, "stop", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.thisObject instanceof AudioRecord) {
                        stopSession((AudioRecord) param.thisObject);
                    }
                }
            });

            XposedBridge.hookAllMethods(AudioRecord.class, "release", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.thisObject instanceof AudioRecord) {
                        stopSession((AudioRecord) param.thisObject);
                    }
                }
            });

            XposedBridge.hookAllMethods(AudioRecord.class, "read", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!(param.thisObject instanceof AudioRecord)) {
                        return;
                    }
                    if (!shouldIntercept()) {
                        return;
                    }
                    Object resObj = param.getResult();
                    if (!(resObj instanceof Integer)) {
                        return;
                    }
                    int bytesOrShortsRead = (Integer) resObj;
                    if (bytesOrShortsRead <= 0) {
                        return;
                    }
                    AudioRecord ar = (AudioRecord) param.thisObject;
                    DecoderSession session = ensureSession(ar);
                    if (session == null || !session.hasAudioTrack()) {
                        return;
                    }
                    Object arg0 = param.args[0];
                    if (arg0 instanceof byte[] && param.args.length >= 3) {
                        byte[] buf = (byte[]) arg0;
                        int offset = (Integer) param.args[1];
                        session.fillBytes(buf, offset, bytesOrShortsRead);
                    } else if (arg0 instanceof short[] && param.args.length >= 3) {
                        short[] buf = (short[]) arg0;
                        int offset = (Integer) param.args[1];
                        session.fillShorts(buf, offset, bytesOrShortsRead);
                    } else if (arg0 instanceof ByteBuffer && param.args.length >= 2) {
                        ByteBuffer bb = (ByteBuffer) arg0;
                        session.fillByteBuffer(bb, bytesOrShortsRead);
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Failed to hook AudioRecord: " + t);
        }
    }

    private static boolean shouldIntercept() {
        return HookMain.isVirtualMicActive() && HookMain.isCameraSessionActive();
    }

    @Nullable
    private static DecoderSession ensureSession(@NonNull AudioRecord ar) {
        DecoderSession existing = SESSIONS.get(ar);
        if (existing != null && existing.isAlive()) {
            return existing;
        }
        int targetRate = Math.max(8000, ar.getSampleRate());
        int targetChannels = Math.max(1, ar.getChannelCount());
        int audioFormat = ar.getAudioFormat();
        if (audioFormat != AudioFormat.ENCODING_PCM_16BIT) {
            // Only intercept standard 16-bit PCM AudioRecord streams safely
            return null;
        }
        DecoderSession created = new DecoderSession(targetRate, targetChannels);
        SESSIONS.put(ar, created);
        created.start();
        return created;
    }

    private static void stopSession(@NonNull AudioRecord ar) {
        DecoderSession session = SESSIONS.remove(ar);
        if (session != null) {
            session.close();
        }
    }

    public static void stopAllSessions() {
        for (DecoderSession s : SESSIONS.values()) {
            s.close();
        }
        SESSIONS.clear();
    }

    private static final class DecoderSession {
        private final int targetSampleRate;
        private final int targetChannels;
        private final byte[] ringBuffer = new byte[65536];
        private int writePos = 0;
        private int readPos = 0;
        private int available = 0;
        private volatile boolean running = true;
        private volatile boolean audioTrackFound = false;
        private Thread worker;

        DecoderSession(int targetSampleRate, int targetChannels) {
            this.targetSampleRate = targetSampleRate;
            this.targetChannels = targetChannels;
        }

        void start() {
            worker = new Thread(this::decodeLoop, "vcam-virtual-mic");
            worker.setDaemon(true);
            worker.start();
        }

        boolean isAlive() {
            return running && worker != null && worker.isAlive();
        }

        boolean hasAudioTrack() {
            return audioTrackFound;
        }

        void close() {
            running = false;
            if (worker != null) {
                worker.interrupt();
            }
        }

        void fillBytes(byte[] dst, int offset, int length) {
            if (HookMain.isPlaybackPaused()) {
                fillComfortNoiseBytes(dst, offset, length);
                return;
            }
            synchronized (ringBuffer) {
                if (available < length) {
                    // Not enough decoded samples yet; leave or pad cleanly
                    int toCopy = available;
                    for (int i = 0; i < toCopy; i++) {
                        dst[offset + i] = ringBuffer[readPos];
                        readPos = (readPos + 1) % ringBuffer.length;
                    }
                    available = 0;
                    fillComfortNoiseBytes(dst, offset + toCopy, length - toCopy);
                    ringBuffer.notifyAll();
                    return;
                }
                for (int i = 0; i < length; i++) {
                    dst[offset + i] = ringBuffer[readPos];
                    readPos = (readPos + 1) % ringBuffer.length;
                }
                available -= length;
                ringBuffer.notifyAll();
            }
        }

        void fillShorts(short[] dst, int offset, int count) {
            byte[] tmp = new byte[count * 2];
            fillBytes(tmp, 0, tmp.length);
            ByteBuffer.wrap(tmp).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(dst, offset, count);
        }

        void fillByteBuffer(ByteBuffer bb, int bytesRead) {
            int pos = bb.position();
            byte[] tmp = new byte[Math.min(bytesRead, bb.capacity())];
            fillBytes(tmp, 0, tmp.length);
            try {
                bb.position(0);
                bb.put(tmp, 0, tmp.length);
                bb.position(pos);
            } catch (Throwable ignored) {
            }
        }

        private void fillComfortNoiseBytes(byte[] dst, int offset, int len) {
            // Very low-amplitude (-66dBFS) thermal dither so silence detectors do not flag all-zero buffers
            for (int i = 0; i < len - 1; i += 2) {
                short noise = (short) ((System.nanoTime() & 7) - 3);
                dst[offset + i] = (byte) (noise & 0xFF);
                dst[offset + i + 1] = (byte) ((noise >> 8) & 0xFF);
            }
        }

        private void enqueuePcmBytes(byte[] pcm, int len) {
            int idx = 0;
            while (idx < len && running) {
                synchronized (ringBuffer) {
                    while (available >= ringBuffer.length - 4 && running) {
                        try {
                            ringBuffer.wait(20L);
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                    int space = ringBuffer.length - available;
                    int chunk = Math.min(space, len - idx);
                    for (int i = 0; i < chunk; i++) {
                        ringBuffer[writePos] = pcm[idx + i];
                        writePos = (writePos + 1) % ringBuffer.length;
                    }
                    available += chunk;
                    idx += chunk;
                }
            }
        }

        private void decodeLoop() {
            while (running && shouldIntercept()) {
                MediaExtractor extractor = new MediaExtractor();
                MediaCodec codec = null;
                ParcelFileDescriptor pfd = null;
                try {
                    String streamUrl = HookMain.getActiveStreamUrl();
                    File localVideo = HookMain.getActiveVideoFile();
                    if (streamUrl != null && !streamUrl.isEmpty()) {
                        extractor.setDataSource(streamUrl);
                    } else if (localVideo != null && localVideo.exists() && localVideo.canRead()) {
                        extractor.setDataSource(localVideo.getAbsolutePath());
                    } else {
                        pfd = HookMain.openActiveVideoPfd();
                        if (pfd != null) {
                            extractor.setDataSource(pfd.getFileDescriptor());
                        } else {
                            running = false;
                            break;
                        }
                    }

                    int audioTrack = -1;
                    MediaFormat format = null;
                    for (int i = 0; i < extractor.getTrackCount(); i++) {
                        MediaFormat mf = extractor.getTrackFormat(i);
                        String mime = mf.getString(MediaFormat.KEY_MIME);
                        if (mime != null && mime.startsWith("audio/")) {
                            audioTrack = i;
                            format = mf;
                            break;
                        }
                    }
                    if (audioTrack < 0 || format == null) {
                        audioTrackFound = false;
                        running = false;
                        break;
                    }
                    audioTrackFound = true;
                    extractor.selectTrack(audioTrack);
                    String mime = format.getString(MediaFormat.KEY_MIME);
                    int srcRate = format.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                            ? format.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;
                    int srcChannels = format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                            ? format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 1;

                    codec = MediaCodec.createDecoderByType(mime);
                    codec.configure(format, null, null, 0);
                    codec.start();

                    MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                    boolean inputEos = false;
                    boolean outputEos = false;

                    while (running && !outputEos && shouldIntercept()) {
                        if (HookMain.isPlaybackPaused()) {
                            Thread.sleep(30L);
                            continue;
                        }
                        if (!inputEos) {
                            int inIdx = codec.dequeueInputBuffer(10000L);
                            if (inIdx >= 0) {
                                ByteBuffer inBuf = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP
                                        ? codec.getInputBuffer(inIdx)
                                        : codec.getInputBuffers()[inIdx];
                                if (inBuf != null) {
                                    int sampleSize = extractor.readSampleData(inBuf, 0);
                                    if (sampleSize < 0) {
                                        codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                        inputEos = true;
                                    } else {
                                        long pts = extractor.getSampleTime();
                                        codec.queueInputBuffer(inIdx, 0, sampleSize, pts, 0);
                                        extractor.advance();
                                    }
                                }
                            }
                        }

                        int outIdx = codec.dequeueOutputBuffer(info, 10000L);
                        if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            MediaFormat outFmt = codec.getOutputFormat();
                            if (outFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                                srcRate = outFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                            }
                            if (outFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                                srcChannels = outFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                            }
                        } else if (outIdx >= 0) {
                            ByteBuffer outBuf = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP
                                    ? codec.getOutputBuffer(outIdx)
                                    : codec.getOutputBuffers()[outIdx];
                            if (outBuf != null && info.size > 0) {
                                outBuf.position(info.offset);
                                outBuf.limit(info.offset + info.size);
                                byte[] raw = new byte[info.size];
                                outBuf.get(raw);
                                byte[] resampled = resamplePcm16(raw, srcRate, srcChannels, targetSampleRate, targetChannels);
                                enqueuePcmBytes(resampled, resampled.length);
                            }
                            codec.releaseOutputBuffer(outIdx, false);
                            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                outputEos = true;
                            }
                        }
                    }
                } catch (Throwable t) {
                    running = false;
                } finally {
                    try {
                        if (codec != null) {
                            codec.stop();
                            codec.release();
                        }
                    } catch (Throwable ignored) {
                    }
                    try {
                        extractor.release();
                    } catch (Throwable ignored) {
                    }
                    try {
                        if (pfd != null) {
                            pfd.close();
                        }
                    } catch (Throwable ignored) {
                    }
                }
                if (!HookMain.isLoopVideoActive()) {
                    break;
                }
            }
        }

        private static byte[] resamplePcm16(byte[] srcBytes, int srcRate, int srcCh, int dstRate, int dstCh) {
            if (srcRate == dstRate && srcCh == dstCh) {
                return srcBytes;
            }
            int totalShorts = srcBytes.length / 2;
            int srcFrames = Math.max(1, totalShorts / Math.max(1, srcCh));
            int dstFrames = Math.max(1, (int) ((long) srcFrames * dstRate / Math.max(1, srcRate)));
            short[] srcShorts = new short[totalShorts];
            ByteBuffer.wrap(srcBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(srcShorts);

            byte[] outBytes = new byte[dstFrames * dstCh * 2];
            ByteBuffer outBuf = ByteBuffer.wrap(outBytes).order(ByteOrder.LITTLE_ENDIAN);

            for (int f = 0; f < dstFrames; f++) {
                int srcFrame = Math.min(srcFrames - 1, (int) ((long) f * srcRate / Math.max(1, dstRate)));
                int baseIdx = srcFrame * srcCh;
                short monoOrLeft = baseIdx < srcShorts.length ? srcShorts[baseIdx] : 0;
                short right = (srcCh > 1 && baseIdx + 1 < srcShorts.length) ? srcShorts[baseIdx + 1] : monoOrLeft;
                if (dstCh == 1) {
                    short mixed = (short) ((monoOrLeft + right) / 2);
                    outBuf.putShort(mixed);
                } else {
                    outBuf.putShort(monoOrLeft);
                    outBuf.putShort(right);
                    for (int c = 2; c < dstCh; c++) {
                        outBuf.putShort(monoOrLeft);
                    }
                }
            }
            return outBytes;
        }
    }
}
