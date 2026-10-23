package com.example.vcam;

import android.Manifest;
import android.app.Activity;
import android.app.AndroidAppHelper;
import android.app.Application;
import android.app.Instrumentation;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.InputConfiguration;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.net.Uri;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.view.PixelCopy;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class HookMain implements IXposedHookLoadPackage, IXposedHookZygoteInit {
    @Override
    public void initZygote(StartupParam startupParam) throws Throwable {
        if (startupParam != null && startupParam.modulePath != null) {
            sModuleApkPath = startupParam.modulePath;
        }
    }
    private static volatile HookMain sInstance;
    private static volatile String sModuleApkPath;

    // Legacy public static fields retained for full backwards compatibility
    public static Surface mSurface;
    public static SurfaceTexture mSurfacetexture;
    public static MediaPlayer mMediaPlayer;
    public static SurfaceTexture fake_SurfaceTexture;
    public static Camera origin_preview_camera;

    public static Camera camera_onPreviewFrame;
    public static Camera start_preview_camera;
    public static volatile byte[] data_buffer = {0};
    public static volatile byte[] latestVideoNv21 = null;
    public static volatile int latestVideoWidth = 0;
    public static volatile int latestVideoHeight = 0;
    public static byte[] input;
    public static int mhight;
    public static int mwidth;
    public static boolean is_someone_playing;
    public static boolean is_hooked;
    public static VideoToFrames hw_decode_obj;
    public static VideoToFrames c2_hw_decode_obj;
    public static VideoToFrames c2_hw_decode_obj_1;
    public static SurfaceTexture c1_fake_texture;
    public static Surface c1_fake_surface;
    public static SurfaceHolder ori_holder;
    public static MediaPlayer mplayer1;
    public static Camera mcamera1;
    public int imageReaderFormat = 0;
    public static boolean is_first_hook_build = true;

    public static int onemhight;
    public static int onemwidth;
    public static Class camera_callback_calss;

    public static String video_path = "/storage/emulated/0/DCIM/Camera1/";

    public static Surface c2_preview_Surfcae;
    public static Surface c2_preview_Surfcae_1;
    public static Surface c2_reader_Surfcae;
    public static Surface c2_reader_Surfcae_1;
    public static MediaPlayer c2_player;
    public static MediaPlayer c2_player_1;
    public static StaticBitmapFeeder c2_still_feeder;
    public static StaticBitmapFeeder c2_still_feeder_1;
    public static StaticBitmapFeeder c2_preview_feeder;
    public static StaticBitmapFeeder c2_preview_feeder_1;
    public static Surface c2_virtual_surface;
    public static SurfaceTexture c2_virtual_surfaceTexture;
    public boolean need_recreate;
    public static CameraDevice.StateCallback c2_state_cb;
    public static CaptureRequest.Builder c2_builder;
    public static SessionConfiguration fake_sessionConfiguration;
    public static SessionConfiguration sessionConfiguration;
    public static OutputConfiguration outputConfiguration;
    public boolean need_to_show_toast = true;
    private static boolean in_app_ui_notification_posted = false;
    private static final int VCAM_NOTIF_ID = 0x7CA4;
    private static final String VCAM_CHANNEL_ID = "vcam_inapp_config";

    public int c2_ori_width = 1280;
    public int c2_ori_height = 720;

    public static Class c2_state_callback;
    public Context toast_content;

    // Camera1 StaticBitmapFeeder for image-only / synthetic modes
    private static StaticBitmapFeeder c1_surface_feeder;
    private static StaticBitmapFeeder c1_holder_feeder;

    // Background GL sink consumer thread so dummy SurfaceTextures never stall HAL BufferQueue
    private static HandlerThread sSinkDrainThread;
    private static Handler sSinkDrainHandler;
    private static EGLDisplay sEglDisplay = EGL14.EGL_NO_DISPLAY;
    private static EGLContext sEglContext = EGL14.EGL_NO_CONTEXT;
    private static EGLSurface sEglPbuffer = EGL14.EGL_NO_SURFACE;
    private static final Random sRandom = new Random();

    // ---------------------------------------------------------------------
    // Core System Package Exclusions & Hook Deduplication Registries
    // ---------------------------------------------------------------------
    private static final Set<String> EXCLUDED_SYSTEM_PACKAGES = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    "android",
                    "com.android.systemui",
                    "com.android.phone",
                    "com.android.se",
                    "com.android.bluetooth",
                    "com.android.nfc",
                    "com.android.providers.settings",
                    "com.android.providers.media",
                    "com.android.providers.contacts",
                    "com.android.providers.telephony"
            ))
    );

    private static final Set<Class<?>> HOOKED_STATE_CALLBACKS =
            Collections.synchronizedSet(new HashSet<>());
    private static final Set<Class<?>> HOOKED_CAMERA_DEVICES =
            Collections.synchronizedSet(new HashSet<>());
    private static final Set<Class<?>> HOOKED_SESSION_CALLBACKS =
            Collections.synchronizedSet(new HashSet<>());
    private static final Set<Class<?>> HOOKED_CAPTURE_SESSIONS =
            Collections.synchronizedSet(new HashSet<>());
    private static final Set<Class<?>> HOOKED_PREVIEW_CALLBACKS =
            Collections.synchronizedSet(new HashSet<>());
    private static final Set<Class<?>> HOOKED_PICTURE_CALLBACKS =
            Collections.synchronizedSet(new HashSet<>());
    private static final Set<Class<?>> HOOKED_BUILDERS =
            Collections.synchronizedSet(new HashSet<>());
    private static final Map<CameraDevice, Boolean> INITIALIZED_CAMERA_DEVICES =
            Collections.synchronizedMap(new WeakHashMap<>());

    // ---------------------------------------------------------------------
    // ImageReader Surface Metadata Tracking & Dynamic Surface Feeder Registry
    // ---------------------------------------------------------------------
    public static final class ReaderSurfaceMeta {
        public final int width;
        public final int height;
        public final int format;
        public final ImageReader reader;
        public final int requestedFormat;

        ReaderSurfaceMeta(int width, int height, int format, ImageReader reader) {
            this(width, height, format, reader, format);
        }

        ReaderSurfaceMeta(int width, int height, int format, ImageReader reader, int requestedFormat) {
            this.width = width;
            this.height = height;
            this.format = format;
            this.reader = reader;
            this.requestedFormat = requestedFormat;
        }
    }

    private static final Map<ImageReader, ReaderSurfaceMeta> IMAGE_READER_META =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Surface, ReaderSurfaceMeta> SURFACE_META_MAP =
            Collections.synchronizedMap(new WeakHashMap<>());

    private static final class ActiveSurfaceFeeder {
        final Surface surface;
        final boolean isReaderSurface;
        final int width;
        final int height;
        final int format;
        MediaPlayer mediaPlayer;
        ParcelFileDescriptor playerPfd;
        VideoToFrames videoDecoder;
        StaticBitmapFeeder bitmapFeeder;

        ActiveSurfaceFeeder(Surface surface, boolean isReaderSurface, int width, int height, int format) {
            this.surface = surface;
            this.isReaderSurface = isReaderSurface;
            this.width = width;
            this.height = height;
            this.format = format;
        }

        boolean isRunning() {
            if (mediaPlayer != null) {
                return true;
            }
            if (videoDecoder != null) {
                return true;
            }
            return bitmapFeeder != null && bitmapFeeder.isRunning();
        }

        void stop() {
            if (mediaPlayer != null) {
                try {
                    mediaPlayer.stop();
                } catch (Throwable ignored) {
                }
                try {
                    mediaPlayer.release();
                } catch (Throwable ignored) {
                }
                mediaPlayer = null;
            }
            if (playerPfd != null) {
                try {
                    playerPfd.close();
                } catch (Throwable ignored) {
                }
                playerPfd = null;
            }
            if (videoDecoder != null) {
                try {
                    videoDecoder.stopDecode();
                } catch (Throwable ignored) {
                }
                videoDecoder = null;
            }
            if (bitmapFeeder != null) {
                try {
                    bitmapFeeder.stop();
                } catch (Throwable ignored) {
                }
                bitmapFeeder = null;
            }
        }
    }

    private static final Map<Surface, ActiveSurfaceFeeder> ACTIVE_FEEDERS = new ConcurrentHashMap<>();
    private static final ConcurrentLinkedQueue<byte[]> HOST_CALLBACK_BUFFERS = new ConcurrentLinkedQueue<>();

    // ---------------------------------------------------------------------
    // Active Session & Configuration State
    // ---------------------------------------------------------------------
    private static volatile String currentFacing = MediaMappings.FACING_BACK;
    private static volatile String currentHostPackage = "";
    private static volatile String lastStagedKey = null;
    private static volatile boolean mappingReceiverRegistered = false;
    private static volatile boolean cameraSessionActive = false;
    private static volatile boolean playbackPaused = false;

    // Provider-driven configuration flags
    private static volatile boolean cfgEnabled = true;
    private static volatile boolean cfgMute = true;
    private static volatile boolean cfgLoopVideo = true;
    private static volatile boolean cfgToastEnabled = true;
    private static volatile boolean cfgInAppUiEnabled = false;
    private static volatile boolean cfgOverlayHudEnabled = false;
    private static volatile boolean cfgPassthroughMode = false;
    private static volatile boolean cfgVirtualMicEnabled = false;
    private static volatile boolean cfgInterceptCaptureIntents = true;
    private static volatile boolean cfgMetadataPatchEnabled = true;
    private static volatile boolean cfgSensorGrainEnabled = true;
    private static volatile boolean cfgAllowedByFilter = true;

    @NonNull
    private static volatile TransformConfig activeTransformConfig = new TransformConfig();
    @Nullable
    private static volatile SensorProfiler.LensProfile activeLensProfile = null;
    @NonNull
    private static final List<TransformConfig.CuePoint> activeCuePoints =
            Collections.synchronizedList(new ArrayList<>());

    private static final ExecutorService stagingExecutor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "vcam-stage");
                t.setDaemon(true);
                return t;
            });

    // ---------------------------------------------------------------------
    // Public Static Accessors for Feeders, Overlay HUD, NDK & Synthesizers
    // ---------------------------------------------------------------------
    @NonNull
    public static TransformConfig getActiveTransformConfig() {
        return activeTransformConfig;
    }

    @Nullable
    public static SensorProfiler.LensProfile getActiveLensProfile() {
        return activeLensProfile;
    }

    @NonNull
    public static String getCurrentFacing() {
        return currentFacing != null ? currentFacing : MediaMappings.FACING_BACK;
    }

    @NonNull
    public static String getCurrentHostPackage() {
        return currentHostPackage != null ? currentHostPackage : "";
    }

    public static boolean isMetadataPatchActive() {
        return cfgEnabled && cfgMetadataPatchEnabled && !cfgPassthroughMode && !activeTransformConfig.passthroughMode;
    }

    public static boolean isLoopVideoActive() {
        return cfgLoopVideo;
    }

    public static boolean isVirtualMicActive() {
        return cfgEnabled && cfgVirtualMicEnabled && !isPassthroughActive();
    }

    public static boolean isCameraSessionActive() {
        return cameraSessionActive;
    }

    public static boolean isPlaybackPaused() {
        return playbackPaused;
    }

    public static boolean isPassthroughActive() {
        return cfgPassthroughMode || activeTransformConfig.passthroughMode;
    }

    public static void updateLatestVideoFrame(@Nullable byte[] nv21, int width, int height) {
        if (nv21 == null || nv21.length <= 16 || width <= 0 || height <= 0) {
            return;
        }
        data_buffer = nv21;
        latestVideoNv21 = nv21;
        latestVideoWidth = width;
        latestVideoHeight = height;
    }

    @Nullable
    public static Bitmap getLatestVideoFrameBitmap(int targetW, int targetH) {
        byte[] nv21 = latestVideoNv21;
        int w = latestVideoWidth;
        int h = latestVideoHeight;
        if (nv21 != null && w > 0 && h > 0 && nv21.length >= w * h * 3 / 2) {
            Bitmap bmp = nv21ToBitmap(nv21, w, h);
            if (bmp != null) {
                return bmp;
            }
        }
        // Fallback if MediaPlayer is rendering directly to a Surface without VideoToFrames active:
        // extract the current frame from the staged video via MediaMetadataRetriever
        File vidFile = getActiveVideoFile();
        if (vidFile != null && vidFile.exists()) {
            MediaMetadataRetriever mmr = new MediaMetadataRetriever();
            try {
                mmr.setDataSource(vidFile.getAbsolutePath());
                long posUs = getActiveVideoPositionMs() * 1000L;
                Bitmap frame = mmr.getFrameAtTime(posUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                if (frame != null) {
                    return frame;
                }
            } catch (Throwable ignored) {
            } finally {
                try { mmr.release(); } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    @Nullable
    private static Bitmap nv21ToBitmap(@NonNull byte[] nv21, int width, int height) {
        try {
            int ySize = width * height;
            if (nv21.length < ySize * 3 / 2) return null;
            int[] argb = new int[ySize];
            for (int j = 0, yp = 0; j < height; j++) {
                int uvp = ySize + (j >> 1) * width;
                int u = 0, v = 0;
                for (int i = 0; i < width; i++, yp++) {
                    int y = (0xff & ((int) nv21[yp])) - 16;
                    if (y < 0) y = 0;
                    if ((i & 1) == 0) {
                        if (uvp + 1 < nv21.length) {
                            v = (0xff & nv21[uvp++]) - 128;
                            u = (0xff & nv21[uvp++]) - 128;
                        }
                    }
                    int y1192 = 1192 * y;
                    int r = (y1192 + 1634 * v);
                    int g = (y1192 - 833 * v - 400 * u);
                    int b = (y1192 + 2066 * u);
                    if (r < 0) r = 0; else if (r > 262143) r = 262143;
                    if (g < 0) g = 0; else if (g > 262143) g = 262143;
                    if (b < 0) b = 0; else if (b > 262143) b = 262143;
                    argb[yp] = 0xff000000 | ((r << 6) & 0xff0000) | ((g >> 2) & 0xff00) | ((b >> 10) & 0xff);
                }
            }
            return Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * NDK Bridge Callback: invoked when native code calls ACameraManager_openCamera.
     */
    public static void onNdkCameraOpened() {
        HookMain inst = sInstance;
        if (inst == null) return;
        inst.ensureStagedNow(currentHostPackage);
        if (!inst.isInjectionActive() || !inst.hasAnyMedia()) return;
        Surface sink = inst.getOrCreateVirtualSinkSurface();
        NdkHookEngine.updateVirtualSinkSurface(sink);
        cameraSessionActive = true;
        InAppOverlayManager.onCameraSessionActive(true);
        inst.maybePostInAppNotification(inst.toast_content, currentHostPackage);
        inst.showInjectedHookToast("Camera2-NDK", inst.c2_ori_width, inst.c2_ori_height);
    }

    /**
     * NDK Bridge Callback: invoked when native code wraps an ANativeWindow into
     * ACaptureSessionOutput_create, ACameraOutputTarget_create, or AImageReader_getWindow.
     */
    public static void onNdkSurfaceDiscovered(Surface surface, int width, int height, int format, boolean isImageReader) {
        HookMain inst = sInstance;
        if (inst == null || surface == null || !surface.isValid()) return;
        inst.ensureStagedNow(currentHostPackage);
        if (!inst.isInjectionActive() || !inst.hasAnyMedia()) return;
        if (surface == c2_virtual_surface) return;

        if (isImageReader || format == ImageFormat.YUV_420_888 || format == ImageFormat.JPEG || format == ImageFormat.NV21) {
            int w = width > 0 ? width : inst.c2_ori_width;
            int h = height > 0 ? height : inst.c2_ori_height;
            int fmt = format > 0 ? format : ImageFormat.YUV_420_888;
            SURFACE_META_MAP.put(surface, new ReaderSurfaceMeta(w, h, fmt, null, fmt));
        }
        Surface sink = inst.getOrCreateVirtualSinkSurface();
        NdkHookEngine.updateVirtualSinkSurface(sink);
        inst.registerTargetSurface(surface);
        inst.ensureAllRegisteredFeedersRunning();
    }

    /**
     * NDK Bridge Callback: invoked when ACameraDevice_createCaptureSession completes.
     */
    public static void onNdkSessionCreated() {
        HookMain inst = sInstance;
        if (inst == null) return;
        if (!inst.isInjectionActive() || !inst.hasAnyMedia()) return;
        inst.ensureAllRegisteredFeedersRunning();
    }

    @Nullable
    public static String getActiveStreamUrl() {
        TransformConfig cfg = activeTransformConfig;
        if (TransformConfig.SOURCE_STREAM.equals(cfg.sourceMode) && !cfg.streamUrl.isEmpty()) {
            return cfg.streamUrl;
        }
        return null;
    }

    @Nullable
    public static File getActiveVideoFile() {
        File f = new File(video_path + "virtual.mp4");
        return f.exists() ? f : null;
    }

    @Nullable
    public static ParcelFileDescriptor openActiveVideoPfd() {
        Context ctx = resolveHostContext();
        if (ctx == null) {
            return null;
        }
        try {
            String vidUri = queryResolve(ctx, currentHostPackage, currentFacing, MediaMappings.TYPE_VIDEO);
            if (vidUri == null || MediaProvider.STATUS_UNSET.equals(vidUri)) {
                return null;
            }
            return ctx.getContentResolver().openFileDescriptor(Uri.parse(vidUri), "r");
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Nullable
    public static TransformConfig.CuePoint findCueById(int id) {
        if (id < 0) return null;
        synchronized (activeCuePoints) {
            for (TransformConfig.CuePoint cue : activeCuePoints) {
                if (cue.id == id) {
                    return cue;
                }
            }
        }
        return null;
    }

    public static void setPlaybackPaused(boolean paused) {
        playbackPaused = paused;
        if (hw_decode_obj != null) {
            hw_decode_obj.setPaused(paused);
        }
        for (ActiveSurfaceFeeder feeder : ACTIVE_FEEDERS.values()) {
            if (feeder.videoDecoder != null) {
                feeder.videoDecoder.setPaused(paused);
            }
            if (feeder.mediaPlayer != null) {
                try {
                    if (paused && feeder.mediaPlayer.isPlaying()) {
                        feeder.mediaPlayer.pause();
                    } else if (!paused && !feeder.mediaPlayer.isPlaying()) {
                        feeder.mediaPlayer.start();
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        for (MediaPlayer mp : Arrays.asList(mMediaPlayer, mplayer1, c2_player, c2_player_1)) {
            if (mp != null) {
                try {
                    if (paused && mp.isPlaying()) {
                        mp.pause();
                    } else if (!paused && !mp.isPlaying()) {
                        mp.start();
                    }
                } catch (Throwable ignored) {
                }
            }
        }
    }

    public static void seekActiveVideo(long positionMs) {
        long clamped = Math.max(0L, positionMs);
        if (hw_decode_obj != null) {
            hw_decode_obj.seekToMs(clamped);
        }
        for (ActiveSurfaceFeeder feeder : ACTIVE_FEEDERS.values()) {
            if (feeder.videoDecoder != null) {
                feeder.videoDecoder.seekToMs(clamped);
            }
            if (feeder.mediaPlayer != null) {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        feeder.mediaPlayer.seekTo(clamped, MediaPlayer.SEEK_CLOSEST);
                    } else {
                        feeder.mediaPlayer.seekTo((int) clamped);
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        for (MediaPlayer mp : Arrays.asList(mMediaPlayer, mplayer1, c2_player, c2_player_1)) {
            if (mp != null) {
                try {
                    mp.seekTo((int) clamped);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    public static long getActiveVideoDurationMs() {
        for (ActiveSurfaceFeeder feeder : ACTIVE_FEEDERS.values()) {
            if (feeder.videoDecoder != null && feeder.videoDecoder.getDurationMs() > 0) {
                return feeder.videoDecoder.getDurationMs();
            }
            if (feeder.mediaPlayer != null) {
                try {
                    int dur = feeder.mediaPlayer.getDuration();
                    if (dur > 0) return dur;
                } catch (Throwable ignored) {
                }
            }
        }
        if (hw_decode_obj != null && hw_decode_obj.getDurationMs() > 0) {
            return hw_decode_obj.getDurationMs();
        }
        for (MediaPlayer mp : Arrays.asList(mMediaPlayer, mplayer1, c2_player, c2_player_1)) {
            if (mp != null) {
                try {
                    int d = mp.getDuration();
                    if (d > 0) return d;
                } catch (Throwable ignored) {
                }
            }
        }
        return 0L;
    }

    public static long getActiveVideoPositionMs() {
        for (ActiveSurfaceFeeder feeder : ACTIVE_FEEDERS.values()) {
            if (feeder.videoDecoder != null && feeder.videoDecoder.getCurrentPositionMs() > 0) {
                return feeder.videoDecoder.getCurrentPositionMs();
            }
            if (feeder.mediaPlayer != null) {
                try {
                    return feeder.mediaPlayer.getCurrentPosition();
                } catch (Throwable ignored) {
                }
            }
        }
        if (hw_decode_obj != null) {
            return hw_decode_obj.getCurrentPositionMs();
        }
        for (MediaPlayer mp : Arrays.asList(mMediaPlayer, mplayer1, c2_player, c2_player_1)) {
            if (mp != null) {
                try {
                    return mp.getCurrentPosition();
                } catch (Throwable ignored) {
                }
            }
        }
        return 0L;
    }

    public static void triggerCuePoint(int cueId) {
        TransformConfig cfg = activeTransformConfig.copy();
        cfg.activeCueId = cueId;
        activeTransformConfig = cfg;
        TransformConfig.CuePoint cue = findCueById(cueId);
        if (cue != null) {
            seekActiveVideo(cue.timestampMs);
            if (cue.action == TransformConfig.CuePoint.ACTION_PAUSE_AT_START) {
                setPlaybackPaused(true);
            } else {
                setPlaybackPaused(false);
            }
        }
    }

    public static void bookmarkCuePoint(int cueId, String label, long posMs) {
        long duration = getActiveVideoDurationMs();
        long endMs = duration > posMs + 500L ? Math.min(duration, posMs + 3000L) : 0L;
        TransformConfig.CuePoint created = new TransformConfig.CuePoint(
                cueId, label, posMs, endMs, TransformConfig.CuePoint.ACTION_LOOP_SEGMENT);
        synchronized (activeCuePoints) {
            for (int i = activeCuePoints.size() - 1; i >= 0; i--) {
                if (activeCuePoints.get(i).id == cueId) {
                    activeCuePoints.remove(i);
                }
            }
            activeCuePoints.add(created);
        }
        triggerCuePoint(cueId);
        final Context ctx = resolveHostContext();
        if (ctx != null) {
            stagingExecutor.execute(() -> {
                try {
                    Bundle extras = new Bundle();
                    extras.putString("media_id", "global");
                    extras.putString("cues_json", TransformConfig.cuesToJson(activeCuePoints));
                    ctx.getContentResolver().call(MediaProvider.CONTENT_URI,
                            MediaProvider.METHOD_SAVE_CUES, null, extras);
                } catch (Throwable ignored) {
                }
            });
        }
    }

    public static void updateActiveTransformInProcess(@NonNull TransformConfig cfg) {
        activeTransformConfig = cfg;
        StaticBitmapFeeder.invalidateAllCaches();
        if (hw_decode_obj == null && latestVideoNv21 == null) {
            data_buffer = new byte[]{0};
        }
        int scalingMode = TransformConfig.ASPECT_FILL.equals(cfg.aspectMode)
                ? MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
                : MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT;
        for (ActiveSurfaceFeeder feeder : ACTIVE_FEEDERS.values()) {
            if (feeder.mediaPlayer != null) {
                try {
                    feeder.mediaPlayer.setVideoScalingMode(scalingMode);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    // ---------------------------------------------------------------------
    // Storage Permission Spoofing for Injected Host Apps
    // ---------------------------------------------------------------------
    private static final Set<String> SPOOFED_STORAGE_PERMISSIONS;
    static {
        Set<String> s = new HashSet<>(Arrays.asList(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.MANAGE_EXTERNAL_STORAGE,
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
                Manifest.permission.ACCESS_MEDIA_LOCATION
        ));
        SPOOFED_STORAGE_PERMISSIONS = Collections.unmodifiableSet(s);
    }

    private void applyInjectedStoragePermissions(final XC_LoadPackage.LoadPackageParam lpparam) {
        if (BuildConfig.APPLICATION_ID.equals(lpparam.packageName)) {
            return;
        }
        final XC_MethodHook grantIfStorage = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args.length > 0 && param.args[0] instanceof String
                        && SPOOFED_STORAGE_PERMISSIONS.contains(param.args[0])) {
                    param.setResult(PackageManager.PERMISSION_GRANTED);
                }
            }
        };
        final XC_MethodHook alwaysTrue = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                param.setResult(Boolean.TRUE);
            }
        };

        try {
            XposedHelpers.findAndHookMethod("android.app.ContextImpl", lpparam.classLoader,
                    "checkPermission", String.class, int.class, int.class, grantIfStorage);
        } catch (Throwable ignored) {
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                XposedHelpers.findAndHookMethod("android.app.ContextImpl", lpparam.classLoader,
                        "checkSelfPermission", String.class, grantIfStorage);
            } catch (Throwable ignored) {
            }
        }
        try {
            XposedHelpers.findAndHookMethod("android.app.ApplicationPackageManager", lpparam.classLoader,
                    "checkPermission", String.class, String.class, grantIfStorage);
        } catch (Throwable ignored) {
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                XposedHelpers.findAndHookMethod("android.os.Environment", lpparam.classLoader,
                        "isExternalStorageManager", alwaysTrue);
            } catch (Throwable ignored) {
            }
            try {
                XposedHelpers.findAndHookMethod("android.os.Environment", lpparam.classLoader,
                        "isExternalStorageManager", File.class, alwaysTrue);
            } catch (Throwable ignored) {
            }
        }
    }

    // ---------------------------------------------------------------------
    // IXposedHookLoadPackage Entry Point
    // ---------------------------------------------------------------------
    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) throws Exception {
        if (lpparam == null || lpparam.packageName == null) {
            return;
        }
        if (EXCLUDED_SYSTEM_PACKAGES.contains(lpparam.packageName)) {
            return;
        }
        sInstance = this;
        if (sModuleApkPath == null && lpparam.appInfo != null && BuildConfig.APPLICATION_ID.equals(lpparam.packageName)) {
            sModuleApkPath = lpparam.appInfo.sourceDir;
        }
        final boolean isSelfManager = BuildConfig.APPLICATION_ID.equals(lpparam.packageName);

        currentHostPackage = lpparam.packageName;
        applyInjectedStoragePermissions(lpparam);

        // Install CaptureResult metadata patcher (Bypass Vector #10 & Photometric alignment)
        ExifSynthesizer.installCaptureResultPatcher(lpparam.classLoader);

        // Install Virtual Microphone AudioRecord hooks
        if (!isSelfManager) {
            VirtualAudioFeeder.installHooks();
        }

        // Install Native NDK Camera2 / Media / ANativeWindow PLT/GOT & dlsym hooks
        if (!isSelfManager) {
            NdkHookEngine.ensureInitialized(sModuleApkPath);
        }

        // Install Anti-Detection Callstack, File & Reflection Cloaking hooks
        if (!isSelfManager) {
            installAntiDetectionHooks(lpparam.classLoader);
        }

        // Install ImageReader surface dimension/format tracking & CPU fallback hooks (Bypass Vector #3 & #4)
        installImageReaderHooks(lpparam.classLoader);

        // Install External Camera Capture Intent interception (Bypass Vector #5)
        if (!isSelfManager) {
            installCaptureIntentHooks(lpparam.classLoader, lpparam.packageName);
        }

        // Early Context & Lifecycle Initialization (Immunity against early ContentProvider/SDK startup)
        XC_MethodHook earlyInitHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                super.afterHookedMethod(param);
                Context ctx = null;
                if (param.args != null && param.args.length > 0) {
                    if (param.args[0] instanceof Application) {
                        ctx = ((Application) param.args[0]).getApplicationContext();
                    } else if (param.args[0] instanceof Context) {
                        ctx = ((Context) param.args[0]).getApplicationContext();
                    }
                }
                if (ctx == null) {
                    ctx = resolveHostContext();
                }
                if (ctx != null && (toast_content == null || is_first_hook_build)) {
                    toast_content = ctx;
                    try {
                        if (!isSelfManager && ctx instanceof Application) {
                            InAppOverlayManager.registerLifecycle((Application) ctx);
                        }
                        registerMappingUpdatedReceiver(toast_content);
                        refreshConfigFromProvider(toast_content, lpparam.packageName);
                        if (!isSelfManager && cfgOverlayHudEnabled) {
                            InAppOverlayManager.setOverlayEnabled(true);
                        }
                        resolveWritableVideoPath(toast_content);
                        if (!isSelfManager) {
                            NdkHookEngine.ensureInitialized(sModuleApkPath);
                        }
                    } catch (Throwable t) {
                        XposedBridge.log("[VCAM][early-init] " + t);
                    }
                }
            }
        };

        try {
            XposedHelpers.findAndHookMethod("android.app.Instrumentation", lpparam.classLoader,
                    "callApplicationOnCreate", Application.class, earlyInitHook);
        } catch (Throwable ignored) {}

        try {
            XposedHelpers.findAndHookMethod("android.app.ActivityThread", lpparam.classLoader,
                    "handleBindApplication", "android.app.ActivityThread$AppBindData", earlyInitHook);
        } catch (Throwable ignored) {}

        // Install Direct TextureView, SurfaceView & PixelCopy UI frame tap hooks
        if (!isSelfManager) {
            installUiFrameScraperHooks(lpparam.classLoader);
        }

        // -----------------------------------------------------------------
        // Camera1 Hooks
        // -----------------------------------------------------------------
        installCamera1Hooks(lpparam);

        // -----------------------------------------------------------------
        // Camera2 Hooks (CameraManager + Static Boot-Class Framework Impl Hooks)
        // -----------------------------------------------------------------
        installCamera2Hooks(lpparam);
    }

    // ---------------------------------------------------------------------
    // Evasion & Anti-Detection Hooks (Callstack, File & Reflection Cloaking)
    // ---------------------------------------------------------------------
    private void installAntiDetectionHooks(ClassLoader classLoader) {
        XC_MethodHook stackFilterHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Object res = param.getResult();
                if (res instanceof StackTraceElement[]) {
                    param.setResult(filterStackTrace((StackTraceElement[]) res));
                }
            }
        };

        try {
            XposedHelpers.findAndHookMethod(Thread.class, "getStackTrace", stackFilterHook);
        } catch (Throwable ignored) {}

        try {
            XposedHelpers.findAndHookMethod(Throwable.class, "getStackTrace", stackFilterHook);
        } catch (Throwable ignored) {}

        try {
            Class<?> vmStackClass = Class.forName("dalvik.system.VMStack");
            XposedHelpers.findAndHookMethod(vmStackClass, "getThreadStackTrace", Thread.class, stackFilterHook);
        } catch (Throwable ignored) {}

        try {
            XposedHelpers.findAndHookMethod(ClassLoader.class, "loadClass", String.class, boolean.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (param.thisObject == HookMain.class.getClassLoader()) {
                        return;
                    }
                    String className = (String) param.args[0];
                    if (className != null && (
                            className.equals("de.robv.android.xposed.XposedBridge") ||
                            className.equals("de.robv.android.xposed.XC_MethodHook") ||
                            className.equals("de.robv.android.xposed.XposedHelpers") ||
                            className.startsWith("org.lsposed.lspd") ||
                            className.startsWith("com.example.vcam."))) {
                        param.setThrowable(new ClassNotFoundException(className));
                    }
                }
            });
        } catch (Throwable ignored) {}

        try {
            XposedHelpers.findAndHookMethod(java.lang.reflect.Method.class, "getModifiers", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.thisObject instanceof java.lang.reflect.Method) {
                        java.lang.reflect.Method m = (java.lang.reflect.Method) param.thisObject;
                        Class<?> declaring = m.getDeclaringClass();
                        if (declaring != null) {
                            String name = declaring.getName();
                            if (name.startsWith("android.hardware.") || name.startsWith("android.media.ImageReader")) {
                                int mod = (int) param.getResult();
                                String mName = m.getName();
                                if (java.lang.reflect.Modifier.isNative(mod) && !isNaturallyNativeMethod(mName)) {
                                    param.setResult(mod & ~java.lang.reflect.Modifier.NATIVE);
                                }
                            }
                        }
                    }
                }
            });
        } catch (Throwable ignored) {}

        // Cloak /DCIM/Camera1 & vcam_native artifacts from host SDK file-existence probes
        try {
            XposedHelpers.findAndHookMethod(File.class, "exists", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!Boolean.TRUE.equals(param.getResult())) return;
                    if (!(param.thisObject instanceof File)) return;
                    String path = ((File) param.thisObject).getPath();
                    if (path == null) return;
                    if (path.contains("/DCIM/Camera1") || path.contains("libvcam_ndk")) {
                        if (!isInternalVcamCaller()) {
                            param.setResult(Boolean.FALSE);
                        }
                    }
                }
            });
        } catch (Throwable ignored) {}
    }

    private static boolean isInternalVcamCaller() {
        try {
            Throwable t = new Throwable();
            // Access raw stack trace via Object/Throwable internals or check classloader frames
            for (Class<?> cls : ClassSaveHelper.getCallingClasses()) {
                if (cls != null && cls.getName().startsWith("com.example.vcam.")) {
                    return true;
                }
            }
        } catch (Throwable ignored) {}
        return true; // Fail-open for internal file operations if SecurityManager helper unavailable
    }

    private static final class ClassSaveHelper extends SecurityManager {
        static final ClassSaveHelper INSTANCE = new ClassSaveHelper();
        static Class<?>[] getCallingClasses() {
            return INSTANCE.getClassContext();
        }
    }

    private static StackTraceElement[] filterStackTrace(StackTraceElement[] orig) {
        if (orig == null || orig.length == 0) return orig;
        List<StackTraceElement> cleaned = new ArrayList<>(orig.length);
        for (StackTraceElement elem : orig) {
            String cn = elem.getClassName();
            if (cn != null && (cn.contains("xposed") || cn.contains("lspd")
                    || cn.contains("com.example.vcam") || cn.contains("sandhook")
                    || cn.contains("edxposed"))) {
                continue;
            }
            cleaned.add(elem);
        }
        return cleaned.toArray(new StackTraceElement[0]);
    }

    private static boolean isNaturallyNativeMethod(String methodName) {
        return methodName != null && (methodName.startsWith("native_")
                || "native_setup".equals(methodName) || "native_init".equals(methodName)
                || "native_release".equals(methodName));
    }

    // ---------------------------------------------------------------------
    // ImageReader Hooks (Exact Surface Dimensions, Formats & CPU Fallback)
    // ---------------------------------------------------------------------
    private void installImageReaderHooks(ClassLoader classLoader) {
        try {
            XposedHelpers.findAndHookMethod(ImageReader.class, "newInstance",
                    int.class, int.class, int.class, int.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            int requestedFormat = (int) param.args[2];
                            param.setObjectExtra("requestedFormat", requestedFormat);
                            if (requestedFormat == 0x22) {
                                param.args[2] = ImageFormat.YUV_420_888;
                            }
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            int w = (int) param.args[0];
                            int h = (int) param.args[1];
                            int fmt = (int) param.args[2];
                            Object req = param.getObjectExtra("requestedFormat");
                            int requestedFormat = req instanceof Integer ? (Integer) req : fmt;
                            c2_ori_width = w;
                            c2_ori_height = h;
                            imageReaderFormat = fmt;
                            if (param.getResult() instanceof ImageReader) {
                                ImageReader ir = (ImageReader) param.getResult();
                                IMAGE_READER_META.put(ir, new ReaderSurfaceMeta(w, h, fmt, ir, requestedFormat));
                            }
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log("[VCAM] ImageReader.newInstance(4) hook failed: " + t);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                XposedHelpers.findAndHookMethod(ImageReader.class, "newInstance",
                        int.class, int.class, int.class, int.class, long.class, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                int requestedFormat = (int) param.args[2];
                                param.setObjectExtra("requestedFormat", requestedFormat);
                                if (requestedFormat == 0x22) {
                                    param.args[2] = ImageFormat.YUV_420_888;
                                }
                            }

                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                int w = (int) param.args[0];
                                int h = (int) param.args[1];
                                int fmt = (int) param.args[2];
                                Object req = param.getObjectExtra("requestedFormat");
                                int requestedFormat = req instanceof Integer ? (Integer) req : fmt;
                                c2_ori_width = w;
                                c2_ori_height = h;
                                imageReaderFormat = fmt;
                                if (param.getResult() instanceof ImageReader) {
                                    ImageReader ir = (ImageReader) param.getResult();
                                    IMAGE_READER_META.put(ir, new ReaderSurfaceMeta(w, h, fmt, ir, requestedFormat));
                                }
                            }
                        });
            } catch (Throwable ignored) {
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                XposedHelpers.findAndHookMethod(ImageReader.Builder.class, "setImageFormat", int.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        int requestedFormat = (int) param.args[0];
                        XposedHelpers.setAdditionalInstanceField(param.thisObject, "requestedFormat", requestedFormat);
                        if (requestedFormat == 0x22) {
                            param.args[0] = ImageFormat.YUV_420_888;
                        }
                    }
                });
            } catch (Throwable ignored) {
            }

            try {
                XposedHelpers.findAndHookMethod(ImageReader.Builder.class, "build", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (param.getResult() instanceof ImageReader) {
                            ImageReader ir = (ImageReader) param.getResult();
                            int w = ir.getWidth();
                            int h = ir.getHeight();
                            int fmt = ir.getImageFormat();
                            c2_ori_width = w;
                            c2_ori_height = h;
                            imageReaderFormat = fmt;
                            Object req = XposedHelpers.getAdditionalInstanceField(param.thisObject, "requestedFormat");
                            int requestedFormat = req instanceof Integer ? (Integer) req : fmt;
                            IMAGE_READER_META.put(ir, new ReaderSurfaceMeta(w, h, fmt, ir, requestedFormat));
                        }
                    }
                });
            } catch (Throwable ignored) {
            }
        }

        try {
            XposedHelpers.findAndHookMethod(ImageReader.class, "getImageFormat", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.thisObject instanceof ImageReader) {
                        ImageReader ir = (ImageReader) param.thisObject;
                        ReaderSurfaceMeta meta = IMAGE_READER_META.get(ir);
                        if (meta != null && meta.requestedFormat == 0x22) {
                            param.setResult(0x22);
                        }
                    }
                }
            });
        } catch (Throwable ignored) {
        }

        try {
            XposedHelpers.findAndHookMethod(ImageReader.class, "getSurface", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.thisObject instanceof ImageReader && param.getResult() instanceof Surface) {
                        ImageReader ir = (ImageReader) param.thisObject;
                        Surface s = (Surface) param.getResult();
                        ReaderSurfaceMeta meta = IMAGE_READER_META.get(ir);
                        if (meta == null) {
                            meta = new ReaderSurfaceMeta(ir.getWidth(), ir.getHeight(), ir.getImageFormat(), ir);
                            IMAGE_READER_META.put(ir, meta);
                        }
                        SURFACE_META_MAP.put(s, meta);
                    }
                }
            });
        } catch (Throwable ignored) {
        }

        // CPU Plane Patching Fallback for acquireLatestImage / acquireNextImage
        XC_MethodHook acquireHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!isInjectionActive()) {
                    return;
                }
                Object res = param.getResult();
                if (!(res instanceof Image)) {
                    return;
                }
                Image img = (Image) res;
                if (param.thisObject instanceof ImageReader) {
                    ImageReader ir = (ImageReader) param.thisObject;
                    try {
                        Surface s = ir.getSurface();
                        if (s != null && ACTIVE_FEEDERS.containsKey(s)) {
                            return;
                        }
                    } catch (Throwable ignored) {
                    }
                }
                patchAcquiredImagePlanes(img);
            }
        };
        try {
            XposedHelpers.findAndHookMethod(ImageReader.class, "acquireLatestImage", acquireHook);
            XposedHelpers.findAndHookMethod(ImageReader.class, "acquireNextImage", acquireHook);
        } catch (Throwable ignored) {
        }
    }

    private void patchAcquiredImagePlanes(@NonNull Image img) {
        try {
            int fmt = img.getFormat();
            int w = img.getWidth();
            int h = img.getHeight();
            Image.Plane[] planes = img.getPlanes();
            if (planes == null || planes.length == 0) {
                return;
            }
            if (fmt == ImageFormat.JPEG && planes.length >= 1) {
                byte[] jpeg = buildTransformedJpegBytes(w, h);
                if (jpeg != null) {
                    ByteBuffer buf = planes[0].getBuffer();
                    if (buf != null && !buf.isReadOnly() && buf.capacity() >= jpeg.length) {
                        buf.rewind();
                        buf.put(jpeg);
                    }
                }
            } else if ((fmt == ImageFormat.YUV_420_888 || fmt == ImageFormat.NV21 || fmt == ImageFormat.YV12)
                    && planes.length >= 3) {
                byte[] nv21 = data_buffer;
                if (nv21 == null || nv21.length < w * h * 3 / 2) {
                    Bitmap bmp = obtainCurrentFrameBitmap(w, h);
                    if (bmp != null) {
                        nv21 = FrameTransformPipeline.bitmapToNv21(bmp);
                    }
                }
                if (nv21 != null && nv21.length >= w * h * 3 / 2) {
                    int ySize = w * h;
                    StaticBitmapFeeder.copyPlane(planes[0], nv21, 0, w, h, 1);
                    StaticBitmapFeeder.copyPlane(planes[1], nv21, ySize + 1, w / 2, h / 2, 2);
                    StaticBitmapFeeder.copyPlane(planes[2], nv21, ySize, w / 2, h / 2, 2);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    // ---------------------------------------------------------------------
    // External Camera Intent Interception (MediaStore.ACTION_IMAGE_CAPTURE)
    // ---------------------------------------------------------------------
    private void installCaptureIntentHooks(ClassLoader classLoader, final String hostPkg) {
        try {
            XposedHelpers.findAndHookMethod(Instrumentation.class, "execStartActivity",
                    Context.class, IBinder.class, IBinder.class, Activity.class,
                    Intent.class, int.class, Bundle.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!(param.args[4] instanceof Intent) || !(param.args[3] instanceof Activity)) {
                                return;
                            }
                            Intent intent = (Intent) param.args[4];
                            String action = intent.getAction();
                            if (!MediaStore.ACTION_IMAGE_CAPTURE.equals(action)
                                    && !"android.media.action.IMAGE_CAPTURE_SECURE".equals(action)) {
                                return;
                            }
                            ensureStagedNow(hostPkg);
                            if (!isInjectionActive() || !cfgInterceptCaptureIntents || !hasAnyMedia()) {
                                return;
                            }
                            Activity caller = (Activity) param.args[3];
                            int requestCode = (int) param.args[5];
                            Uri outputUri = intent.getParcelableExtra(MediaStore.EXTRA_OUTPUT);
                            int targetW = activeLensProfile != null ? activeLensProfile.activeWidth : 1920;
                            int targetH = activeLensProfile != null ? activeLensProfile.activeHeight : 1080;

                            byte[] jpegBytes = buildTransformedJpegBytes(targetW, targetH);
                            if (jpegBytes == null) {
                                return;
                            }

                            final Intent resultIntent = new Intent();
                            if (outputUri != null) {
                                try (OutputStream os = caller.getContentResolver().openOutputStream(outputUri)) {
                                    if (os != null) {
                                        os.write(jpegBytes);
                                        os.flush();
                                    }
                                } catch (Throwable t) {
                                    XposedBridge.log("[VCAM][intent] Failed writing EXTRA_OUTPUT: " + t);
                                    return;
                                }
                            } else {
                                Bitmap thumb = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.length);
                                if (thumb != null) {
                                    Bitmap scaled = Bitmap.createScaledBitmap(thumb, 256, 256, true);
                                    resultIntent.putExtra("data", scaled);
                                }
                            }

                            new Handler(Looper.getMainLooper()).post(() -> {
                                try {
                                    XposedHelpers.callMethod(caller, "onActivityResult",
                                            requestCode, Activity.RESULT_OK, resultIntent);
                                } catch (Throwable t) {
                                    XposedBridge.log("[VCAM][intent] onActivityResult dispatch failed: " + t);
                                }
                            });
                            param.setResult(null);
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log("[VCAM][intent] execStartActivity hook failed: " + t);
        }
    }

    // ---------------------------------------------------------------------
    // UI Direct Frame Scraper Interception (TextureView.getBitmap & PixelCopy)
    // ---------------------------------------------------------------------
    private void installUiFrameScraperHooks(ClassLoader classLoader) {
        try {
            XC_MethodHook getBitmapHook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!isInjectionActive() || !hasAnyMedia()) return;
                    if (!(param.thisObject instanceof TextureView)) return;
                    TextureView tv = (TextureView) param.thisObject;
                    int w = tv.getWidth();
                    int h = tv.getHeight();
                    if (w <= 0 || h <= 0) {
                        w = c2_ori_width > 0 ? c2_ori_width : 1280;
                        h = c2_ori_height > 0 ? c2_ori_height : 720;
                    }
                    Bitmap virtualBmp = obtainCurrentFrameBitmap(w, h);
                    if (virtualBmp == null) return;

                    Object res = param.getResult();
                    if (res instanceof Bitmap) {
                        param.setResult(virtualBmp);
                    } else if (param.args != null && param.args.length > 0 && param.args[0] instanceof Bitmap) {
                        Bitmap dest = (Bitmap) param.args[0];
                        try {
                            android.graphics.Canvas canvas = new android.graphics.Canvas(dest);
                            Rect src = new Rect(0, 0, virtualBmp.getWidth(), virtualBmp.getHeight());
                            Rect dst = new Rect(0, 0, dest.getWidth(), dest.getHeight());
                            canvas.drawBitmap(virtualBmp, src, dst, null);
                            param.setResult(dest);
                        } catch (Throwable ignored) {}
                    }
                }
            };
            XposedHelpers.findAndHookMethod(TextureView.class, "getBitmap", getBitmapHook);
            XposedHelpers.findAndHookMethod(TextureView.class, "getBitmap", Bitmap.class, getBitmapHook);
            XposedHelpers.findAndHookMethod(TextureView.class, "getBitmap", int.class, int.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!isInjectionActive() || !hasAnyMedia()) return;
                    int w = (int) param.args[0];
                    int h = (int) param.args[1];
                    Bitmap virtualBmp = obtainCurrentFrameBitmap(w, h);
                    if (virtualBmp != null) {
                        param.setResult(virtualBmp);
                    }
                }
            });
        } catch (Throwable ignored) {}

        // Intercept PixelCopy.request(SurfaceView / Surface, ..., Bitmap, OnPixelCopyFinishedListener, Handler)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                XposedBridge.hookAllMethods(PixelCopy.class, "request", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!isInjectionActive() || !hasAnyMedia() || !cameraSessionActive) return;
                        if (param.args == null || param.args.length < 3) return;
                        Object srcObj = param.args[0];
                        if (!(srcObj instanceof SurfaceView) && !(srcObj instanceof Surface)) return;

                        Bitmap destBmp = null;
                        PixelCopy.OnPixelCopyFinishedListener listener = null;
                        Handler handler = null;
                        for (Object arg : param.args) {
                            if (arg instanceof Bitmap) destBmp = (Bitmap) arg;
                            else if (arg instanceof PixelCopy.OnPixelCopyFinishedListener)
                                listener = (PixelCopy.OnPixelCopyFinishedListener) arg;
                            else if (arg instanceof Handler) handler = (Handler) arg;
                        }
                        if (destBmp == null || listener == null) return;

                        Bitmap virtualBmp = obtainCurrentFrameBitmap(destBmp.getWidth(), destBmp.getHeight());
                        if (virtualBmp != null) {
                            try {
                                android.graphics.Canvas c = new android.graphics.Canvas(destBmp);
                                c.drawBitmap(virtualBmp,
                                        new Rect(0, 0, virtualBmp.getWidth(), virtualBmp.getHeight()),
                                        new Rect(0, 0, destBmp.getWidth(), destBmp.getHeight()),
                                        null);
                                final PixelCopy.OnPixelCopyFinishedListener finalListener = listener;
                                Runnable notify = () -> finalListener.onPixelCopyFinished(PixelCopy.SUCCESS);
                                if (handler != null) {
                                    handler.post(notify);
                                } else {
                                    new Handler(Looper.getMainLooper()).post(notify);
                                }
                                param.setResult(null);
                            } catch (Throwable ignored) {}
                        }
                    }
                });
            } catch (Throwable ignored) {}
        }
    }

    // ---------------------------------------------------------------------
    // Camera1 Hooks (Complete Lifecycle, Preview, Still & Buffer Identity)
    // ---------------------------------------------------------------------
    private void installCamera1Hooks(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader,
                    "open", int.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            detectFacingCamera1(param);
                            ensureStagedNow(lpparam.packageName);
                        }
                    });
        } catch (Throwable ignored) {
        }

        try {
            XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader,
                    "open", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            currentFacing = MediaMappings.FACING_BACK;
                            ensureStagedNow(lpparam.packageName);
                        }
                    });
        } catch (Throwable ignored) {
        }

        XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader,
                "setPreviewTexture", SurfaceTexture.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        ensureStagedNow(lpparam.packageName);
                        if (!isInjectionActive()) {
                            return;
                        }
                        if (is_hooked) {
                            is_hooked = false;
                            return;
                        }
                        if (param.args[0] == null) {
                            return;
                        }
                        if (param.args[0].equals(c1_fake_texture)) {
                            return;
                        }
                        if (!hasAnyMedia()) {
                            maybeShowMissingMediaToast(lpparam.packageName);
                            return;
                        }
                        origin_preview_camera = (Camera) param.thisObject;
                        mSurfacetexture = (SurfaceTexture) param.args[0];
                        if (fake_SurfaceTexture == null) {
                            fake_SurfaceTexture = createManagedSinkSurfaceTexture();
                        }
                        param.args[0] = fake_SurfaceTexture;

                        if (cameraSessionActive && start_preview_camera != null
                                && start_preview_camera.equals(param.thisObject)) {
                            startCamera1PreviewFeed((Camera) param.thisObject);
                        }
                    }
                });

        XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader,
                "setPreviewDisplay", SurfaceHolder.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        ensureStagedNow(lpparam.packageName);
                        if (!isInjectionActive()) {
                            return;
                        }
                        if (param.args[0] == null) {
                            return;
                        }
                        if (!hasAnyMedia()) {
                            maybeShowMissingMediaToast(lpparam.packageName);
                            return;
                        }
                        mcamera1 = (Camera) param.thisObject;
                        ori_holder = (SurfaceHolder) param.args[0];
                        if (c1_fake_texture == null) {
                            c1_fake_texture = createManagedSinkSurfaceTexture();
                        }
                        if (c1_fake_surface != null) {
                            c1_fake_surface.release();
                        }
                        c1_fake_surface = new Surface(c1_fake_texture);
                        is_hooked = true;
                        try {
                            mcamera1.setPreviewTexture(c1_fake_texture);
                        } catch (IOException e) {
                            XposedBridge.log("[VCAM] " + e);
                        }
                        param.setResult(null);

                        if (cameraSessionActive && start_preview_camera != null
                                && start_preview_camera.equals(param.thisObject)) {
                            startCamera1PreviewFeed(mcamera1);
                        }
                    }
                });

        XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader,
                "startPreview", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        ensureStagedNow(lpparam.packageName);
                        if (!isInjectionActive() || !hasAnyMedia()) {
                            return;
                        }
                        start_preview_camera = (Camera) param.thisObject;
                        cameraSessionActive = true;
                        InAppOverlayManager.onCameraSessionActive(true);
                        maybePostInAppNotification(toast_content, lpparam.packageName);
                        showInjectedHookToast("Camera1", mwidth, mhight);
                        startCamera1PreviewFeed(start_preview_camera);
                    }
                });

        XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader,
                "stopPreview", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        cleanupCamera1Session();
                    }
                });

        XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader,
                "release", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        cleanupCamera1Session();
                    }
                });

        XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader,
                "addCallbackBuffer", byte[].class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args[0] instanceof byte[]) {
                            byte[] hostBuf = (byte[]) param.args[0];
                            if (HOST_CALLBACK_BUFFERS.size() < 16 && !HOST_CALLBACK_BUFFERS.contains(hostBuf)) {
                                HOST_CALLBACK_BUFFERS.offer(hostBuf);
                            }
                        }
                    }
                });

        XC_MethodHook previewCbHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args[0] != null) {
                    process_callback(param);
                }
            }
        };
        XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader,
                "setPreviewCallbackWithBuffer", Camera.PreviewCallback.class, previewCbHook);
        XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader,
                "setPreviewCallback", Camera.PreviewCallback.class, previewCbHook);
        XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader,
                "setOneShotPreviewCallback", Camera.PreviewCallback.class, previewCbHook);

        XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader,
                "takePicture", Camera.ShutterCallback.class, Camera.PictureCallback.class,
                Camera.PictureCallback.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!isInjectionActive()) return;
                        if (param.args[1] != null) {
                            process_a_shot_YUV(param);
                        }
                        if (param.args[2] != null) {
                            process_a_shot_jpeg(param, 2);
                        }
                    }
                });

        XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader,
                "takePicture", Camera.ShutterCallback.class, Camera.PictureCallback.class,
                Camera.PictureCallback.class, Camera.PictureCallback.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!isInjectionActive()) return;
                        if (param.args[1] != null) {
                            process_a_shot_YUV(param);
                        }
                        if (param.args[3] != null) {
                            process_a_shot_jpeg(param, 3);
                        }
                    }
                });
    }

    private void startCamera1PreviewFeed(@NonNull Camera cam) {
        try {
            Camera.Parameters params = cam.getParameters();
            Camera.Size sz = params != null ? params.getPreviewSize() : null;
            int w = sz != null ? sz.width : 1280;
            int h = sz != null ? sz.height : 720;
            int fmt = params != null ? params.getPreviewFormat() : ImageFormat.NV21;
            reportHostTelemetry("Camera1", w, h, fmt);

            if (ori_holder != null) {
                Surface holderSurface = ori_holder.getSurface();
                if (holderSurface != null && holderSurface.isValid()) {
                    startSurfaceFeedForCamera1(holderSurface, w, h, true);
                }
            }
            if (mSurfacetexture != null) {
                if (mSurface != null) {
                    mSurface.release();
                }
                mSurface = new Surface(mSurfacetexture);
                if (mSurface.isValid()) {
                    startSurfaceFeedForCamera1(mSurface, w, h, false);
                }
            }
        } catch (Throwable t) {
            XposedBridge.log("[VCAM] startCamera1PreviewFeed error: " + t);
        }
    }

    private void startSurfaceFeedForCamera1(@NonNull Surface targetSurface, int w, int h, boolean isHolder) {
        boolean synthetic = isSyntheticSourceMode();
        if (!synthetic && (hasVideoStaged() || getActiveStreamUrl() != null)) {
            MediaPlayer mp = isHolder ? mplayer1 : mMediaPlayer;
            if (mp != null) {
                try {
                    mp.stop();
                    mp.release();
                } catch (Throwable ignored) {
                }
            }
            mp = new MediaPlayer();
            if (isHolder) {
                mplayer1 = mp;
            } else {
                mMediaPlayer = mp;
            }
            mp.setSurface(targetSurface);
            if (cfgMute) {
                mp.setVolume(0f, 0f);
            }
            mp.setLooping(cfgLoopVideo);
            int scalingMode = TransformConfig.ASPECT_FILL.equals(activeTransformConfig.aspectMode)
                    ? MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
                    : MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT;
            try {
                mp.setVideoScalingMode(scalingMode);
            } catch (Throwable ignored) {
            }
            mp.setOnPreparedListener(MediaPlayer::start);
            try {
                String streamUrl = getActiveStreamUrl();
                File localVid = new File(video_path + "virtual.mp4");
                if (streamUrl != null) {
                    mp.setDataSource(streamUrl);
                } else if (localVid.exists() && localVid.canRead()) {
                    mp.setDataSource(localVid.getAbsolutePath());
                } else {
                    ParcelFileDescriptor pfd = openActiveVideoPfd();
                    if (pfd != null) {
                        mp.setDataSource(pfd.getFileDescriptor());
                    }
                }
                mp.prepare();
            } catch (IOException e) {
                XposedBridge.log("[VCAM] Camera1 MediaPlayer error: " + e);
            }
        } else if (synthetic || hasImageStaged()) {
            StaticBitmapFeeder feeder = isHolder ? c1_holder_feeder : c1_surface_feeder;
            if (feeder != null) {
                feeder.stop();
            }
            feeder = new StaticBitmapFeeder(targetSurface, new File(video_path + "1000.bmp"), w, h,
                    StaticBitmapFeeder.FORMAT_PREVIEW);
            if (isHolder) {
                c1_holder_feeder = feeder;
            } else {
                c1_surface_feeder = feeder;
            }
            feeder.start();
        }
    }

    private void cleanupCamera1Session() {
        cameraSessionActive = false;
        camera_onPreviewFrame = null;
        InAppOverlayManager.onCameraSessionActive(false);
        VirtualAudioFeeder.stopAllSessions();
        if (hw_decode_obj != null) {
            hw_decode_obj.stopDecode();
            hw_decode_obj = null;
        }
        if (c1_surface_feeder != null) {
            c1_surface_feeder.stop();
            c1_surface_feeder = null;
        }
        if (c1_holder_feeder != null) {
            c1_holder_feeder.stop();
            c1_holder_feeder = null;
        }
        if (mMediaPlayer != null) {
            try {
                mMediaPlayer.stop();
                mMediaPlayer.release();
            } catch (Throwable ignored) {
            }
            mMediaPlayer = null;
        }
        if (mplayer1 != null) {
            try {
                mplayer1.stop();
                mplayer1.release();
            } catch (Throwable ignored) {
            }
            mplayer1 = null;
        }
        HOST_CALLBACK_BUFFERS.clear();
    }

    private void process_callback(XC_MethodHook.MethodHookParam param) {
        Class<?> preview_cb_class = param.args[0].getClass();
        if (!HOOKED_PREVIEW_CALLBACKS.add(preview_cb_class)) {
            return;
        }
        XposedHelpers.findAndHookMethod(preview_cb_class, "onPreviewFrame",
                byte[].class, Camera.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam paramd) throws Throwable {
                        Camera localcam = (Camera) paramd.args[1];
                        if (!isInjectionActive() || !hasAnyMedia()) {
                            return;
                        }
                        if (localcam.equals(camera_onPreviewFrame)) {
                            if (hw_decode_obj == null && (data_buffer == null || data_buffer.length < mwidth * mhight
                                    || TransformConfig.SOURCE_TEST_PATTERN.equals(activeTransformConfig.sourceMode))) {
                                Bitmap bmp = obtainCurrentFrameBitmap(mwidth, mhight);
                                if (bmp != null) {
                                    data_buffer = FrameTransformPipeline.bitmapToNv21(bmp);
                                }
                            }
                            waitForFirstDecodedFrame(mwidth, mhight);
                            if (paramd.args[0] instanceof byte[]) {
                                byte[] targetBuf = (byte[]) paramd.args[0];
                                byte[] src = data_buffer;
                                if (src != null && src.length > 1) {
                                    System.arraycopy(src, 0, targetBuf, 0,
                                            Math.min(src.length, targetBuf.length));
                                    if (hw_decode_obj == null && activeTransformConfig.sensorGrainEnabled) {
                                        FrameTransformPipeline.applyLumaGrainInPlace(targetBuf, mwidth, mhight);
                                    }
                                }
                            }
                        } else {
                            camera_callback_calss = preview_cb_class;
                            camera_onPreviewFrame = (Camera) paramd.args[1];
                            mwidth = camera_onPreviewFrame.getParameters().getPreviewSize().width;
                            mhight = camera_onPreviewFrame.getParameters().getPreviewSize().height;
                            reportHostTelemetry("Camera1", mwidth, mhight, ImageFormat.NV21);
                            maybePostInAppNotification(toast_content, currentHostPackage);

                            if (hw_decode_obj != null) {
                                hw_decode_obj.stopDecode();
                                hw_decode_obj = null;
                            }
                            if (!isSyntheticSourceMode() && (hasVideoStaged() || getActiveStreamUrl() != null)) {
                                data_buffer = new byte[]{0};
                                hw_decode_obj = new VideoToFrames();
                                hw_decode_obj.setTargetDimensions(mwidth, mhight);
                                hw_decode_obj.setSaveFrames("", OutputImageFormat.NV21);
                                String streamUrl = getActiveStreamUrl();
                                File localVid = new File(video_path + "virtual.mp4");
                                if (streamUrl != null) {
                                    hw_decode_obj.decodeStream(streamUrl);
                                } else if (localVid.exists() && localVid.canRead()) {
                                    hw_decode_obj.decode(localVid.getAbsolutePath());
                                } else {
                                    ParcelFileDescriptor pfd = openActiveVideoPfd();
                                    if (pfd != null) {
                                        hw_decode_obj.decode(pfd.getFileDescriptor());
                                    }
                                }
                                waitForFirstDecodedFrame(mwidth, mhight);
                                if (paramd.args[0] instanceof byte[]) {
                                    byte[] targetBuf = (byte[]) paramd.args[0];
                                    byte[] src = data_buffer;
                                    if (src != null && src.length > 1) {
                                        System.arraycopy(src, 0, targetBuf, 0,
                                                Math.min(src.length, targetBuf.length));
                                    }
                                }
                            } else {
                                Bitmap bmp = obtainCurrentFrameBitmap(mwidth, mhight);
                                if (bmp != null) {
                                    byte[] nv21 = FrameTransformPipeline.bitmapToNv21(bmp);
                                    data_buffer = nv21;
                                    if (paramd.args[0] instanceof byte[]) {
                                        byte[] targetBuf = (byte[]) paramd.args[0];
                                        System.arraycopy(nv21, 0, targetBuf, 0,
                                                Math.min(nv21.length, targetBuf.length));
                                    }
                                }
                            }
                        }
                    }
                });
    }

    /**
     * Waits up to 250ms for {@link #data_buffer} to be populated with a full NV21 frame
     * (never leaving {@code data_buffer} as the 1-byte sentinel {@code {0}}, which would
     * leak the real camera image in {@code targetBuf[1..end]} on the first callback!).
     */
    private void waitForFirstDecodedFrame(int w, int h) {
        int waited = 0;
        while ((data_buffer == null || data_buffer.length <= 1) && waited < 250) {
            try {
                Thread.sleep(2);
                waited += 2;
            } catch (InterruptedException e) {
                break;
            }
        }
        if (data_buffer == null || data_buffer.length <= 1) {
            Bitmap fallback = obtainCurrentFrameBitmap(w, h);
            if (fallback != null) {
                data_buffer = FrameTransformPipeline.bitmapToNv21(fallback);
            }
        }
    }

    private void process_a_shot_jpeg(XC_MethodHook.MethodHookParam param, int index) {
        Class<?> callback = param.args[index].getClass();
        if (!HOOKED_PICTURE_CALLBACKS.add(callback)) {
            return;
        }
        XposedHelpers.findAndHookMethod(callback, "onPictureTaken",
                byte[].class, Camera.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam paramd) throws Throwable {
                        if (!isInjectionActive() || !hasAnyMedia()) {
                            return;
                        }
                        Camera loaclcam = (Camera) paramd.args[1];
                        onemwidth = loaclcam.getParameters().getPreviewSize().width;
                        onemhight = loaclcam.getParameters().getPreviewSize().height;
                        Camera.Size picSz = loaclcam.getParameters().getPictureSize();
                        int targetW = picSz != null ? picSz.width : onemwidth;
                        int targetH = picSz != null ? picSz.height : onemhight;
                        byte[] jpegBytes = buildTransformedJpegBytes(targetW, targetH);
                        if (jpegBytes != null) {
                            paramd.args[0] = jpegBytes;
                        }
                    }
                });
    }

    private void process_a_shot_YUV(XC_MethodHook.MethodHookParam param) {
        Class<?> callback = param.args[1].getClass();
        if (!HOOKED_PICTURE_CALLBACKS.add(callback)) {
            return;
        }
        XposedHelpers.findAndHookMethod(callback, "onPictureTaken",
                byte[].class, Camera.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam paramd) throws Throwable {
                        if (!isInjectionActive() || !hasAnyMedia()) {
                            return;
                        }
                        Camera loaclcam = (Camera) paramd.args[1];
                        onemwidth = loaclcam.getParameters().getPreviewSize().width;
                        onemhight = loaclcam.getParameters().getPreviewSize().height;
                        Bitmap bmp = obtainCurrentFrameBitmap(onemwidth, onemhight);
                        if (bmp != null) {
                            paramd.args[0] = FrameTransformPipeline.bitmapToNv21(bmp);
                        }
                    }
                });
    }

    // ---------------------------------------------------------------------
    // Camera2 Hooks (No-Escape StateCallback, Static Framework Hooks & Dynamic Feeders)
    // ---------------------------------------------------------------------
    private void installCamera2Hooks(final XC_LoadPackage.LoadPackageParam lpparam) {
        XC_MethodHook openCameraHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                detectFacingCamera2(param);
                ensureStagedNow(lpparam.packageName);
                CameraDevice.StateCallback stateCb = extractStateCallback(param.args);
                if (stateCb == null) {
                    return;
                }
                c2_state_cb = stateCb;
                c2_state_callback = stateCb.getClass();
                if (!isInjectionActive()) {
                    return;
                }
                if (!hasAnyMedia()) {
                    maybeShowMissingMediaToast(lpparam.packageName);
                    return;
                }
                is_first_hook_build = true;
                process_camera2_init(c2_state_callback);
                wrapAndReplaceStateCallback(param.args, stateCb);
            }
        };

        try {
            XposedBridge.hookAllMethods(CameraManager.class, "openCamera", openCameraHook);
        } catch (Throwable t) {
            XposedBridge.log("[VCAM] hookAllMethods(CameraManager.openCamera) failed: " + t);
        }
        try {
            XposedBridge.hookAllMethods(CameraManager.class, "openCameraForUid", openCameraHook);
        } catch (Throwable ignored) {
        }
        try {
            XposedBridge.hookAllMethods(CameraManager.class, "openCameraDeviceUserAsync", openCameraHook);
        } catch (Throwable ignored) {
        }

        // Static Boot-ClassLoader Hooks on Framework CameraDeviceImpl, CameraCaptureSessionImpl & CaptureRequest.Builder
        // Guarantees interception even if an SDK bypasses CameraManager.openCamera or uses cached/JNI CameraDevice
        try {
            Class<?> deviceImplClass = Class.forName("android.hardware.camera2.impl.CameraDeviceImpl");
            hookCameraDeviceInstance(deviceImplClass);
        } catch (Throwable ignored) {
        }
        try {
            Class<?> sessionImplClass = Class.forName("android.hardware.camera2.impl.CameraCaptureSessionImpl");
            hookCaptureSessionInstance(sessionImplClass);
        } catch (Throwable ignored) {
        }
        try {
            process_camera2_CaptureRequest_builder(CaptureRequest.Builder.class);
        } catch (Throwable ignored) {
        }
        try {
            hookCaptureRequestInstance(CaptureRequest.class);
        } catch (Throwable ignored) {
        }

        // Hook OutputConfiguration addSurface & setPhysicalCameraId
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                XposedBridge.hookAllMethods(OutputConfiguration.class, "addSurface", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!isInjectionActive() || !hasAnyMedia()) return;
                        if (param.args.length > 0 && param.args[0] instanceof Surface) {
                            Surface s = (Surface) param.args[0];
                            if (s != c2_virtual_surface) {
                                registerTargetSurface(s);
                                if (param.thisObject instanceof OutputConfiguration) {
                                    OutputConfiguration oc = (OutputConfiguration) param.thisObject;
                                    if (oc.getSurface() == null) {
                                        // Deferred OutputConfiguration(Size, Class): bind our virtual sink as the primary surface
                                        param.args[0] = getOrCreateVirtualSinkSurface();
                                        return;
                                    }
                                }
                                // Surface-sharing OutputConfiguration that already has c2_virtual_surface:
                                // suppress duplicate addSurface so framework doesn't throw IllegalArgumentException
                                param.setResult(null);
                            }
                        }
                    }
                });
            } catch (Throwable ignored) {
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                XposedBridge.hookAllMethods(OutputConfiguration.class, "setPhysicalCameraId", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!isInjectionActive() || !hasAnyMedia()) return;
                        if (param.args.length > 0 && param.thisObject instanceof OutputConfiguration) {
                            OutputConfiguration oc = (OutputConfiguration) param.thisObject;
                            if (oc.getSurface() == c2_virtual_surface) {
                                param.args[0] = null;
                            }
                        }
                    }
                });
            } catch (Throwable ignored) {
            }
        }
    }

    private synchronized void onCameraDeviceOpened(@Nullable CameraDevice camera) {
        if (camera != null && Boolean.TRUE.equals(INITIALIZED_CAMERA_DEVICES.put(camera, Boolean.TRUE))) {
            // Already initialized for this exact CameraDevice open lifecycle
            return;
        }
        stopAllCamera2Feeders();
        getOrCreateVirtualSinkSurface();
        cameraSessionActive = true;
        InAppOverlayManager.onCameraSessionActive(true);
        maybePostInAppNotification(toast_content, currentHostPackage);
        showInjectedHookToast("Camera2", c2_ori_width, c2_ori_height);
        if (camera != null) {
            hookCameraDeviceInstance(camera.getClass());
        }
    }

    private synchronized void onCameraDeviceClosed(@Nullable CameraDevice camera) {
        if (camera != null) {
            INITIALIZED_CAMERA_DEVICES.remove(camera);
        }
        cameraSessionActive = false;
        InAppOverlayManager.onCameraSessionActive(false);
        VirtualAudioFeeder.stopAllSessions();
        stopAllCamera2Feeders();
    }

    private void wrapAndReplaceStateCallback(Object[] args, @NonNull final CameraDevice.StateCallback originalCb) {
        if (args == null) return;
        for (int i = 0; i < args.length; i++) {
            if (args[i] == originalCb) {
                final CameraDevice.StateCallback delegate = new CameraDevice.StateCallback() {
                    @Override
                    public void onOpened(@NonNull CameraDevice camera) {
                        try {
                            if (isInjectionActive() && hasAnyMedia()) {
                                onCameraDeviceOpened(camera);
                            }
                        } catch (Throwable t) {
                            XposedBridge.log("[VCAM][proxy-onOpened] " + t);
                        }
                        originalCb.onOpened(camera);
                    }

                    @Override
                    public void onDisconnected(@NonNull CameraDevice camera) {
                        onCameraDeviceClosed(camera);
                        originalCb.onDisconnected(camera);
                    }

                    @Override
                    public void onError(@NonNull CameraDevice camera, int error) {
                        onCameraDeviceClosed(camera);
                        originalCb.onError(camera, error);
                    }

                    @Override
                    public void onClosed(@NonNull CameraDevice camera) {
                        onCameraDeviceClosed(camera);
                        originalCb.onClosed(camera);
                    }
                };
                args[i] = delegate;
                c2_state_cb = delegate;
                break;
            }
        }
    }

    @Nullable
    private static CameraDevice.StateCallback extractStateCallback(Object[] args) {
        if (args == null) return null;
        for (Object arg : args) {
            if (arg instanceof CameraDevice.StateCallback) {
                return (CameraDevice.StateCallback) arg;
            }
        }
        return null;
    }

    /**
     * Creates a SurfaceTexture with a randomized GL texture ID and attaches a background
     * EGL 1x1 Pbuffer consumer that drains {@code updateTexImage()} on every incoming HAL frame
     * so multi-stream / RECORD / STILL_CAPTURE sessions never stall the HAL BufferQueue.
     */
    private static SurfaceTexture createManagedSinkSurfaceTexture() {
        int texId = 32 + sRandom.nextInt(200);
        final SurfaceTexture st;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            st = new SurfaceTexture(texId, false);
        } else {
            st = new SurfaceTexture(texId);
        }
        st.setDefaultBufferSize(640, 480);
        attachSinkDrainListener(st);
        return st;
    }

    private static synchronized void attachSinkDrainListener(@NonNull final SurfaceTexture st) {
        try {
            if (sSinkDrainThread == null) {
                sSinkDrainThread = new HandlerThread("vcam-sink-drain");
                sSinkDrainThread.setDaemon(true);
                sSinkDrainThread.start();
                sSinkDrainHandler = new Handler(sSinkDrainThread.getLooper());
                sSinkDrainHandler.post(HookMain::initSinkEglContextOnDrainThread);
            }
            SurfaceTexture.OnFrameAvailableListener listener = surfaceTexture -> {
                try {
                    if (sEglDisplay != EGL14.EGL_NO_DISPLAY && sEglContext != EGL14.EGL_NO_CONTEXT) {
                        surfaceTexture.updateTexImage();
                    } else {
                        surfaceTexture.releaseTexImage();
                    }
                } catch (Throwable ignored) {
                }
            };
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                st.setOnFrameAvailableListener(listener, sSinkDrainHandler);
            } else {
                st.setOnFrameAvailableListener(listener);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void initSinkEglContextOnDrainThread() {
        try {
            sEglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            if (sEglDisplay == EGL14.EGL_NO_DISPLAY) return;
            int[] version = new int[2];
            if (!EGL14.eglInitialize(sEglDisplay, version, 0, version, 1)) return;
            int[] attribList = {
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_NONE
            };
            EGLConfig[] configs = new EGLConfig[1];
            int[] numConfigs = new int[1];
            if (!EGL14.eglChooseConfig(sEglDisplay, attribList, 0, configs, 0, 1, numConfigs, 0)
                    || numConfigs[0] <= 0) {
                return;
            }
            int[] ctxAttribs = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE};
            sEglContext = EGL14.eglCreateContext(sEglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttribs, 0);
            int[] pbufAttribs = {EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE};
            sEglPbuffer = EGL14.eglCreatePbufferSurface(sEglDisplay, configs[0], pbufAttribs, 0);
            EGL14.eglMakeCurrent(sEglDisplay, sEglPbuffer, sEglPbuffer, sEglContext);
        } catch (Throwable ignored) {
        }
    }

    private synchronized Surface getOrCreateVirtualSinkSurface() {
        if (c2_virtual_surface != null && c2_virtual_surface.isValid() && !need_recreate) {
            NdkHookEngine.updateVirtualSinkSurface(c2_virtual_surface);
            return c2_virtual_surface;
        }
        if (c2_virtual_surface != null) {
            try {
                c2_virtual_surface.release();
            } catch (Throwable ignored) {
            }
        }
        if (c2_virtual_surfaceTexture != null) {
            try {
                c2_virtual_surfaceTexture.release();
            } catch (Throwable ignored) {
            }
        }
        c2_virtual_surfaceTexture = createManagedSinkSurfaceTexture();
        c2_virtual_surface = new Surface(c2_virtual_surfaceTexture);
        need_recreate = false;
        NdkHookEngine.updateVirtualSinkSurface(c2_virtual_surface);
        return c2_virtual_surface;
    }

    private void process_camera2_init(Class<?> hooked_class) {
        if (hooked_class == null || !HOOKED_STATE_CALLBACKS.add(hooked_class)) {
            return;
        }
        XposedHelpers.findAndHookMethod(hooked_class, "onOpened", CameraDevice.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (!isInjectionActive() || !hasAnyMedia()) {
                    return;
                }
                CameraDevice cam = param.args.length > 0 && param.args[0] instanceof CameraDevice
                        ? (CameraDevice) param.args[0] : null;
                onCameraDeviceOpened(cam);
            }
        });

        XC_MethodHook cleanupHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                CameraDevice cam = (param.args != null && param.args.length > 0 && param.args[0] instanceof CameraDevice)
                        ? (CameraDevice) param.args[0] : null;
                onCameraDeviceClosed(cam);
            }
        };

        try {
            XposedHelpers.findAndHookMethod(hooked_class, "onDisconnected", CameraDevice.class, cleanupHook);
        } catch (Throwable ignored) {
        }
        try {
            XposedHelpers.findAndHookMethod(hooked_class, "onClosed", CameraDevice.class, cleanupHook);
        } catch (Throwable ignored) {
        }
        try {
            XposedHelpers.findAndHookMethod(hooked_class, "onError", CameraDevice.class, int.class, cleanupHook);
        } catch (Throwable ignored) {
        }
    }

    private void hookCameraDeviceInstance(Class<?> deviceClass) {
        if (deviceClass == null || !HOOKED_CAMERA_DEVICES.add(deviceClass)) {
            return;
        }

        // Hook both 1-arg createCaptureRequest(int) and 2-arg createCaptureRequest(int, Set)
        XposedBridge.hookAllMethods(deviceClass, "createCaptureRequest", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!isInjectionActive() || !hasAnyMedia()) {
                    return;
                }
                if (param.getResult() instanceof CaptureRequest.Builder) {
                    c2_builder = (CaptureRequest.Builder) param.getResult();
                    process_camera2_CaptureRequest_builder(c2_builder.getClass());
                }
            }
        });

        // Also hook createReprocessCaptureRequest(TotalCaptureResult)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                XposedBridge.hookAllMethods(deviceClass, "createReprocessCaptureRequest", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!isInjectionActive() || !hasAnyMedia()) return;
                        if (param.getResult() instanceof CaptureRequest.Builder) {
                            c2_builder = (CaptureRequest.Builder) param.getResult();
                            process_camera2_CaptureRequest_builder(c2_builder.getClass());
                        }
                    }
                });
            } catch (Throwable ignored) {
            }
        }

        // Hook isSessionConfigurationSupported so multi-stream / exotic SessionConfigurations never get rejected
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                XposedBridge.hookAllMethods(deviceClass, "isSessionConfigurationSupported", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!isInjectionActive() || !hasAnyMedia()) return;
                        param.setResult(Boolean.TRUE);
                    }
                });
            } catch (Throwable ignored) {
            }
        }

        try {
            XposedHelpers.findAndHookMethod(deviceClass, "close", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    CameraDevice cam = param.thisObject instanceof CameraDevice ? (CameraDevice) param.thisObject : null;
                    onCameraDeviceClosed(cam);
                }
            });
        } catch (Throwable ignored) {
        }

        // Hook all createCaptureSession variants safely
        try {
            XposedHelpers.findAndHookMethod(deviceClass, "createCaptureSession",
                    List.class, CameraCaptureSession.StateCallback.class, Handler.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam paramd) {
                            ensureStagedNow(currentHostPackage);
                            if (!isInjectionActive() || !hasAnyMedia()) return;
                            if (paramd.args[0] instanceof List) {
                                harvestSurfacesFromList((List<?>) paramd.args[0]);
                                paramd.args[0] = Collections.singletonList(getOrCreateVirtualSinkSurface());
                                if (paramd.args[1] != null) {
                                    process_camera2_capture_session_callback((CameraCaptureSession.StateCallback) paramd.args[1]);
                                }
                            }
                        }
                    });
        } catch (Throwable ignored) {
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                XposedHelpers.findAndHookMethod(deviceClass, "createConstrainedHighSpeedCaptureSession",
                        List.class, CameraCaptureSession.StateCallback.class, Handler.class, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam paramd) {
                                ensureStagedNow(currentHostPackage);
                                if (!isInjectionActive() || !hasAnyMedia()) return;
                                if (paramd.args[0] instanceof List) {
                                    harvestSurfacesFromList((List<?>) paramd.args[0]);
                                    paramd.args[0] = Collections.singletonList(getOrCreateVirtualSinkSurface());
                                    if (paramd.args[1] != null) {
                                        process_camera2_capture_session_callback((CameraCaptureSession.StateCallback) paramd.args[1]);
                                    }
                                }
                            }
                        });
            } catch (Throwable ignored) {
            }

            try {
                XposedHelpers.findAndHookMethod(deviceClass, "createReprocessableCaptureSession",
                        InputConfiguration.class, List.class, CameraCaptureSession.StateCallback.class,
                        Handler.class, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam paramd) {
                                ensureStagedNow(currentHostPackage);
                                if (!isInjectionActive() || !hasAnyMedia()) return;
                                if (paramd.args[1] instanceof List) {
                                    harvestSurfacesFromList((List<?>) paramd.args[1]);
                                    paramd.args[1] = Collections.singletonList(getOrCreateVirtualSinkSurface());
                                    if (paramd.args[2] != null) {
                                        process_camera2_capture_session_callback((CameraCaptureSession.StateCallback) paramd.args[2]);
                                    }
                                }
                            }
                        });
            } catch (Throwable ignored) {
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                XposedHelpers.findAndHookMethod(deviceClass, "createCaptureSessionByOutputConfigurations",
                        List.class, CameraCaptureSession.StateCallback.class, Handler.class, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                ensureStagedNow(currentHostPackage);
                                if (!isInjectionActive() || !hasAnyMedia()) return;
                                if (param.args[0] instanceof List) {
                                    harvestSurfacesFromOutputConfigs((List<?>) param.args[0]);
                                    outputConfiguration = new OutputConfiguration(getOrCreateVirtualSinkSurface());
                                    param.args[0] = Collections.singletonList(outputConfiguration);
                                    if (param.args[1] != null) {
                                        process_camera2_capture_session_callback((CameraCaptureSession.StateCallback) param.args[1]);
                                    }
                                }
                            }
                        });
            } catch (Throwable ignored) {
            }

            try {
                XposedHelpers.findAndHookMethod(deviceClass, "createReprocessableCaptureSessionByConfigurations",
                        InputConfiguration.class, List.class, CameraCaptureSession.StateCallback.class,
                        Handler.class, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                ensureStagedNow(currentHostPackage);
                                if (!isInjectionActive() || !hasAnyMedia()) return;
                                if (param.args[1] instanceof List) {
                                    harvestSurfacesFromOutputConfigs((List<?>) param.args[1]);
                                    outputConfiguration = new OutputConfiguration(getOrCreateVirtualSinkSurface());
                                    param.args[1] = Collections.singletonList(outputConfiguration);
                                    if (param.args[2] != null) {
                                        process_camera2_capture_session_callback((CameraCaptureSession.StateCallback) param.args[2]);
                                    }
                                }
                            }
                        });
            } catch (Throwable ignored) {
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                XposedHelpers.findAndHookMethod(deviceClass, "createCaptureSession",
                        SessionConfiguration.class, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                ensureStagedNow(currentHostPackage);
                                if (!isInjectionActive() || !hasAnyMedia()) return;
                                if (param.args[0] instanceof SessionConfiguration) {
                                    sessionConfiguration = (SessionConfiguration) param.args[0];
                                    harvestSurfacesFromOutputConfigs(sessionConfiguration.getOutputConfigurations());
                                    outputConfiguration = new OutputConfiguration(getOrCreateVirtualSinkSurface());
                                    fake_sessionConfiguration = new SessionConfiguration(
                                            sessionConfiguration.getSessionType(),
                                            Collections.singletonList(outputConfiguration),
                                            sessionConfiguration.getExecutor(),
                                            sessionConfiguration.getStateCallback());
                                    param.args[0] = fake_sessionConfiguration;
                                    process_camera2_capture_session_callback(sessionConfiguration.getStateCallback());
                                }
                            }
                        });
            } catch (Throwable ignored) {
            }
        }

        // Android 12+ (API 31): CameraX Vendor Extensions (Bokeh, Night, HDR, Face Retouch)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                XposedBridge.hookAllMethods(deviceClass, "createExtensionSession", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        ensureStagedNow(currentHostPackage);
                        if (!isInjectionActive() || !hasAnyMedia()) return;
                        if (param.args.length > 0 && param.args[0] != null) {
                            Object extConfig = param.args[0];
                            try {
                                Object outConfigs = XposedHelpers.callMethod(extConfig, "getOutputConfigurations");
                                if (outConfigs instanceof List) {
                                    harvestSurfacesFromOutputConfigs((List<?>) outConfigs);
                                }
                                Object postviewConfig = XposedHelpers.callMethod(extConfig, "getPostviewOutputConfiguration");
                                if (postviewConfig instanceof OutputConfiguration) {
                                    harvestSurfacesFromOutputConfigs(Collections.singletonList(postviewConfig));
                                }
                                ensureAllRegisteredFeedersRunning();
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                });
            } catch (Throwable ignored) {
            }
        }
    }

    private void hookCaptureSessionInstance(Class<?> sessionClass) {
        if (sessionClass == null || !HOOKED_CAPTURE_SESSIONS.add(sessionClass)) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                XposedBridge.hookAllMethods(sessionClass, "finalizeOutputConfigurations", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam fParam) {
                        if (!isInjectionActive() || !hasAnyMedia()) return;
                        if (fParam.args.length > 0 && fParam.args[0] instanceof List) {
                            harvestSurfacesFromOutputConfigs((List<?>) fParam.args[0]);
                            ensureAllRegisteredFeedersRunning();
                            fParam.setResult(null);
                        }
                    }
                });
            } catch (Throwable ignored) {
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                XposedBridge.hookAllMethods(sessionClass, "prepare", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!isInjectionActive() || !hasAnyMedia()) return;
                        if (param.args.length > 0 && param.args[0] instanceof Surface) {
                            Surface s = (Surface) param.args[0];
                            if (s != c2_virtual_surface) {
                                registerTargetSurface(s);
                                param.setResult(null);
                            }
                        }
                    }
                });
            } catch (Throwable ignored) {
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                XposedBridge.hookAllMethods(sessionClass, "updateOutputConfiguration", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!isInjectionActive() || !hasAnyMedia()) return;
                        if (param.args.length > 0 && param.args[0] instanceof OutputConfiguration) {
                            harvestSurfacesFromOutputConfigs(Collections.singletonList(param.args[0]));
                            ensureAllRegisteredFeedersRunning();
                            param.setResult(null);
                        }
                    }
                });
            } catch (Throwable ignored) {
            }
        }
    }

    private void process_camera2_capture_session_callback(CameraCaptureSession.StateCallback callback_instance) {
        if (callback_instance == null) {
            return;
        }
        Class<?> cbClass = callback_instance.getClass();
        if (!HOOKED_SESSION_CALLBACKS.add(cbClass)) {
            return;
        }
        try {
            XposedHelpers.findAndHookMethod(cbClass, "onConfigured", CameraCaptureSession.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args.length > 0 && param.args[0] != null) {
                        hookCaptureSessionInstance(param.args[0].getClass());
                    }
                    if (isInjectionActive() && hasAnyMedia()) {
                        ensureAllRegisteredFeedersRunning();
                    }
                }
            });
        } catch (Throwable ignored) {
        }

        try {
            XposedHelpers.findAndHookMethod(cbClass, "onConfigureFailed",
                    CameraCaptureSession.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            XposedBridge.log("[VCAM] Session onConfigureFailed");
                        }
                    });
        } catch (Throwable ignored) {
        }
    }

    @SuppressWarnings("unchecked")
    private static Set<Surface> getBuilderTargets(@NonNull Object builder) {
        Object existing = XposedHelpers.getAdditionalInstanceField(builder, "vcamTargets");
        if (existing instanceof Set) {
            return (Set<Surface>) existing;
        }
        Set<Surface> set = Collections.synchronizedSet(new HashSet<>());
        XposedHelpers.setAdditionalInstanceField(builder, "vcamTargets", set);
        return set;
    }

    private void process_camera2_CaptureRequest_builder(Class<?> builderClass) {
        if (builderClass == null || !HOOKED_BUILDERS.add(builderClass)) {
            return;
        }

        XposedHelpers.findAndHookMethod(builderClass, "addTarget", Surface.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!isInjectionActive() || !hasAnyMedia()) return;
                if (!(param.args[0] instanceof Surface)) return;
                Surface s = (Surface) param.args[0];
                if (s != c2_virtual_surface) {
                    getBuilderTargets(param.thisObject).add(s);
                    registerTargetSurface(s);
                }
                param.args[0] = getOrCreateVirtualSinkSurface();
            }
        });

        XposedHelpers.findAndHookMethod(builderClass, "removeTarget", Surface.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!isInjectionActive() || !hasAnyMedia()) return;
                if (!(param.args[0] instanceof Surface)) return;
                Surface s = (Surface) param.args[0];
                Set<Surface> targets = getBuilderTargets(param.thisObject);
                targets.remove(s);
                ActiveSurfaceFeeder removed = ACTIVE_FEEDERS.remove(s);
                if (removed != null) {
                    removed.stop();
                }
                if (!targets.isEmpty()) {
                    // Other targets remain on this Builder! Do NOT remove c2_virtual_surface,
                    // otherwise builder.build() throws IllegalArgumentException: Request had no output targets!
                    param.setResult(null);
                    return;
                }
                param.args[0] = getOrCreateVirtualSinkSurface();
            }
        });

        XposedHelpers.findAndHookMethod(builderClass, "build", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!isInjectionActive() || !hasAnyMedia()) return;
                ensureAllRegisteredFeedersRunning();
                // Trigger on-demand frame push for any JPEG still-capture surface targeted by this request
                Set<Surface> targets = getBuilderTargets(param.thisObject);
                for (Surface s : targets) {
                    ActiveSurfaceFeeder feeder = ACTIVE_FEEDERS.get(s);
                    if (feeder != null && feeder.bitmapFeeder != null
                            && (feeder.format == ImageFormat.JPEG || feeder.format == 256)) {
                        final StaticBitmapFeeder sbf = feeder.bitmapFeeder;
                        stagingExecutor.execute(sbf::triggerSingleFrame);
                    }
                }
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!isInjectionActive() || !hasAnyMedia()) return;
                if (param.getResult() instanceof CaptureRequest) {
                    CaptureRequest req = (CaptureRequest) param.getResult();
                    Set<Surface> targets = getBuilderTargets(param.thisObject);
                    if (!targets.isEmpty()) {
                        XposedHelpers.setAdditionalInstanceField(req, "vcamTargets", new ArrayList<>(targets));
                    }
                    hookCaptureRequestInstance(req.getClass());
                }
            }
        });
    }

    private static final Set<Class<?>> HOOKED_CAPTURE_REQUESTS = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private void hookCaptureRequestInstance(Class<?> reqClass) {
        if (reqClass == null || !HOOKED_CAPTURE_REQUESTS.add(reqClass)) {
            return;
        }
        try {
            // If an SDK outside android.hardware.camera2.* reflects on CaptureRequest.getTargets()
            // to verify its Surface is bound, return its original registered surfaces!
            XposedHelpers.findAndHookMethod(reqClass, "getTargets", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!isInjectionActive() || !hasAnyMedia()) return;
                    Object saved = XposedHelpers.getAdditionalInstanceField(param.thisObject, "vcamTargets");
                    if (!(saved instanceof Collection) || ((Collection<?>) saved).isEmpty()) return;
                    // Check if caller is internal android.hardware.camera2 implementation
                    for (Class<?> caller : ClassSaveHelper.getCallingClasses()) {
                        if (caller != null && caller.getName().startsWith("android.hardware.camera2.")) {
                            return;
                        }
                    }
                    param.setResult(saved);
                }
            });
        } catch (Throwable ignored) {}
    }

    private void harvestSurfacesFromList(@Nullable List<?> list) {
        if (list == null) return;
        for (Object item : list) {
            if (item instanceof Surface) {
                Surface s = (Surface) item;
                if (s != c2_virtual_surface) {
                    registerTargetSurface(s);
                }
            }
        }
    }

    private void harvestSurfacesFromOutputConfigs(@Nullable List<?> configs) {
        if (configs == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return;
        for (Object obj : configs) {
            if (obj instanceof OutputConfiguration) {
                OutputConfiguration oc = (OutputConfiguration) obj;
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        List<Surface> surfaces = oc.getSurfaces();
                        if (surfaces != null) {
                            for (Surface s : surfaces) {
                                if (s != null && s != c2_virtual_surface) {
                                    registerTargetSurface(s);
                                }
                            }
                        }
                    } else {
                        Surface s = oc.getSurface();
                        if (s != null && s != c2_virtual_surface) {
                            registerTargetSurface(s);
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private void registerTargetSurface(@Nullable Surface s) {
        if (s == null || s == c2_virtual_surface || !s.isValid()) {
            return;
        }
        if (ACTIVE_FEEDERS.containsKey(s)) {
            return;
        }
        ReaderSurfaceMeta meta = SURFACE_META_MAP.get(s);
        boolean isReader = (meta != null);
        if (!isReader) {
            String str = s.toString();
            if (str != null && str.contains("Surface(name=null)")) {
                isReader = true;
            }
        }
        int w = meta != null ? meta.width : c2_ori_width;
        int h = meta != null ? meta.height : c2_ori_height;
        int fmt = meta != null ? meta.format : (isReader ? ImageFormat.YUV_420_888 : StaticBitmapFeeder.FORMAT_PREVIEW);

        if (isReader) {
            if (c2_reader_Surfcae == null) c2_reader_Surfcae = s;
            else if (c2_reader_Surfcae_1 == null && !s.equals(c2_reader_Surfcae)) c2_reader_Surfcae_1 = s;
        } else {
            if (c2_preview_Surfcae == null) c2_preview_Surfcae = s;
            else if (c2_preview_Surfcae_1 == null && !s.equals(c2_preview_Surfcae)) c2_preview_Surfcae_1 = s;
        }

        ACTIVE_FEEDERS.put(s, new ActiveSurfaceFeeder(s, isReader, w, h, fmt));
        reportHostTelemetry("Camera2", w, h, fmt);
    }

    private void ensureAllRegisteredFeedersRunning() {
        boolean synthetic = isSyntheticSourceMode();
        boolean videoMode = !synthetic && (hasVideoStaged() || getActiveStreamUrl() != null);
        boolean imageMode = synthetic || hasImageStaged();

        for (Map.Entry<Surface, ActiveSurfaceFeeder> entry : ACTIVE_FEEDERS.entrySet()) {
            Surface s = entry.getKey();
            ActiveSurfaceFeeder feeder = entry.getValue();
            if (s == null || !s.isValid()) {
                feeder.stop();
                ACTIVE_FEEDERS.remove(s);
                continue;
            }
            if (feeder.isRunning()) {
                continue;
            }
            startFeederInstance(feeder, videoMode, imageMode);
        }
    }

    private void startFeederInstance(@NonNull ActiveSurfaceFeeder feeder, boolean videoMode, boolean imageMode) {
        Surface s = feeder.surface;
        int w = feeder.width > 0 ? feeder.width : 1280;
        int h = feeder.height > 0 ? feeder.height : 720;

        if (feeder.isReaderSurface) {
            int readerFmt = feeder.format > 0 ? feeder.format : ImageFormat.YUV_420_888;
            boolean isJpegReader = (readerFmt == ImageFormat.JPEG || readerFmt == 256);
            boolean useYuvVideoDecoder = videoMode && !isJpegReader
                    && (readerFmt == ImageFormat.YUV_420_888 || readerFmt == ImageFormat.NV21 || readerFmt == ImageFormat.YV12);
            if (useYuvVideoDecoder) {
                try {
                    VideoToFrames vtf = new VideoToFrames();
                    vtf.set_surfcae(s);
                    vtf.setTargetDimensions(w, h);
                    vtf.setUseImageWriterForSurface(true);
                    vtf.setSaveFrames("", OutputImageFormat.NV21);
                    String streamUrl = getActiveStreamUrl();
                    File localVid = new File(video_path + "virtual.mp4");
                    if (streamUrl != null) {
                        vtf.decodeStream(streamUrl);
                    } else if (localVid.exists() && localVid.canRead()) {
                        vtf.decode(localVid.getAbsolutePath());
                    } else {
                        ParcelFileDescriptor pfd = openActiveVideoPfd();
                        if (pfd != null) {
                            feeder.playerPfd = pfd;
                            vtf.decode(pfd.getFileDescriptor());
                        }
                    }
                    feeder.videoDecoder = vtf;
                } catch (Throwable t) {
                    XposedBridge.log("[VCAM] VideoToFrames start failed, falling back to StaticBitmapFeeder: " + t);
                    StaticBitmapFeeder sbf = new StaticBitmapFeeder(s, new File(video_path + "1000.bmp"), w, h, readerFmt);
                    feeder.bitmapFeeder = sbf;
                }
            } else if (imageMode || videoMode) {
                // Pass exact readerFmt (YUV_420_888 or JPEG) so StaticBitmapFeeder uses ImageWriter
                // instead of Surface.lockCanvas (which corrupts ImageReader format to RGBA_8888 0x1!)
                File imgFile = new File(video_path + "1000.bmp");
                StaticBitmapFeeder sbf = new StaticBitmapFeeder(s, readerFmt, w, h, imgFile.getAbsolutePath());
                feeder.bitmapFeeder = sbf;
                if (!isJpegReader) {
                    sbf.start();
                } else {
                    // For JPEG still-capture ImageReader, queue an initial frame and trigger on CaptureRequest.build()
                    stagingExecutor.execute(sbf::triggerSingleFrame);
                }
            }
        } else {
            // Preview / Encoder / SurfaceView / TextureView surface:
            if (videoMode) {
                try {
                    MediaPlayer mp = new MediaPlayer();
                    mp.setSurface(s);
                    if (cfgMute) {
                        mp.setVolume(0f, 0f);
                    }
                    mp.setLooping(cfgLoopVideo);
                    int scalingMode = TransformConfig.ASPECT_FILL.equals(activeTransformConfig.aspectMode)
                            ? MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
                            : MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT;
                    try {
                        mp.setVideoScalingMode(scalingMode);
                    } catch (Throwable ignored) {
                    }
                    mp.setOnPreparedListener(MediaPlayer::start);
                    String streamUrl = getActiveStreamUrl();
                    File localVid = new File(video_path + "virtual.mp4");
                    if (streamUrl != null) {
                        mp.setDataSource(streamUrl);
                    } else if (localVid.exists() && localVid.canRead()) {
                        mp.setDataSource(localVid.getAbsolutePath());
                    } else {
                        ParcelFileDescriptor pfd = openActiveVideoPfd();
                        if (pfd != null) {
                            feeder.playerPfd = pfd;
                            mp.setDataSource(pfd.getFileDescriptor());
                        }
                    }
                    mp.prepare();
                    feeder.mediaPlayer = mp;
                } catch (Throwable t) {
                    XposedBridge.log("[VCAM] Preview MediaPlayer failed, using StaticBitmapFeeder: " + t);
                    StaticBitmapFeeder sbf = new StaticBitmapFeeder(s, new File(video_path + "1000.bmp"), w, h,
                            StaticBitmapFeeder.FORMAT_PREVIEW);
                    feeder.bitmapFeeder = sbf;
                }
            } else if (imageMode) {
                StaticBitmapFeeder sbf = new StaticBitmapFeeder(s, new File(video_path + "1000.bmp"), w, h,
                        StaticBitmapFeeder.FORMAT_PREVIEW);
                feeder.bitmapFeeder = sbf;
            }
        }
    }

    private void stopAllCamera2Feeders() {
        for (ActiveSurfaceFeeder feeder : ACTIVE_FEEDERS.values()) {
            feeder.stop();
        }
        ACTIVE_FEEDERS.clear();
        if (c2_hw_decode_obj != null) {
            c2_hw_decode_obj.stopDecode();
            c2_hw_decode_obj = null;
        }
        if (c2_hw_decode_obj_1 != null) {
            c2_hw_decode_obj_1.stopDecode();
            c2_hw_decode_obj_1 = null;
        }
        if (c2_player != null) {
            try { c2_player.stop(); c2_player.release(); } catch (Throwable ignored) {}
            c2_player = null;
        }
        if (c2_player_1 != null) {
            try { c2_player_1.stop(); c2_player_1.release(); } catch (Throwable ignored) {}
            c2_player_1 = null;
        }
        if (c2_still_feeder != null) {
            c2_still_feeder.stop();
            c2_still_feeder = null;
        }
        if (c2_still_feeder_1 != null) {
            c2_still_feeder_1.stop();
            c2_still_feeder_1 = null;
        }
        if (c2_preview_feeder != null) {
            c2_preview_feeder.stop();
            c2_preview_feeder = null;
        }
        if (c2_preview_feeder_1 != null) {
            c2_preview_feeder_1.stop();
            c2_preview_feeder_1 = null;
        }
        c2_preview_Surfcae = null;
        c2_preview_Surfcae_1 = null;
        c2_reader_Surfcae = null;
        c2_reader_Surfcae_1 = null;
    }

    // ---------------------------------------------------------------------
    // Frame & JPEG Builders with Aspect Ratio, Transform & EXIF Synthesis
    // ---------------------------------------------------------------------
    @Nullable
    private Bitmap obtainCurrentFrameBitmap(int targetW, int targetH) {
        int w = targetW > 0 ? targetW : 1280;
        int h = targetH > 0 ? targetH : 720;
        TransformConfig cfg = activeTransformConfig;
        int hostSensorRot = activeLensProfile != null ? activeLensProfile.sensorOrientation : 0;

        if (TransformConfig.SOURCE_TEST_PATTERN.equals(cfg.sourceMode)) {
            Bitmap raw = SyntheticFrameGenerator.renderTestPattern(
                    w, h, currentHostPackage, currentFacing, 30f, cfg);
            Bitmap out = FrameTransformPipeline.renderTransformedBitmap(raw, w, h, cfg, 0, hostSensorRot);
            ExifSynthesizer.updatePhotometryFromBitmap(out, activeLensProfile, currentFacing);
            return out;
        }
        if (TransformConfig.SOURCE_QR.equals(cfg.sourceMode)) {
            Bitmap raw = SyntheticFrameGenerator.renderQrFrame(w, h, cfg.qrPayload);
            Bitmap out = FrameTransformPipeline.renderTransformedBitmap(raw, w, h, cfg, 0, hostSensorRot);
            ExifSynthesizer.updatePhotometryFromBitmap(out, activeLensProfile, currentFacing);
            return out;
        }

        File imgFile = new File(video_path + "1000.bmp");
        if (imgFile.exists()) {
            Bitmap raw = BitmapFactory.decodeFile(imgFile.getAbsolutePath());
            if (raw != null) {
                int exifRot = MediaPaths.imageExifRotation(imgFile);
                Bitmap out = FrameTransformPipeline.renderTransformedBitmap(raw, w, h, cfg, exifRot, hostSensorRot);
                if (cfg.sensorGrainEnabled) {
                    FrameTransformPipeline.applyTemporalGrainBitmap(out, cfg.grainStrength);
                }
                ExifSynthesizer.updatePhotometryFromBitmap(out, activeLensProfile, currentFacing);
                return out;
            }
        }

        // Video-mode fallback: extract current decoded video frame so still captures,
        // TextureView.getBitmap(), and PixelCopy return the live video frame instead of test pattern!
        Bitmap vidFrame = getLatestVideoFrameBitmap(w, h);
        if (vidFrame != null) {
            Bitmap out = FrameTransformPipeline.renderTransformedBitmap(vidFrame, w, h, cfg, 0, hostSensorRot);
            if (cfg.sensorGrainEnabled) {
                FrameTransformPipeline.applyTemporalGrainBitmap(out, cfg.grainStrength);
            }
            ExifSynthesizer.updatePhotometryFromBitmap(out, activeLensProfile, currentFacing);
            return out;
        }

        // Final fallback: generate test pattern if neither image nor video could be decoded
        return SyntheticFrameGenerator.renderTestPattern(w, h, currentHostPackage, currentFacing, 30f, cfg);
    }

    @Nullable
    private byte[] buildTransformedJpegBytes(int targetW, int targetH) {
        Bitmap bmp = obtainCurrentFrameBitmap(targetW, targetH);
        if (bmp == null) {
            return null;
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.JPEG, 95, baos);
        byte[] rawJpeg = baos.toByteArray();
        if (isMetadataPatchActive()) {
            SensorProfiler.LensProfile profile = activeLensProfile != null
                    ? activeLensProfile
                    : SensorProfiler.LensProfile.defaultForFacing(currentFacing);
            return ExifSynthesizer.injectExifIntoJpeg(
                    rawJpeg, bmp.getWidth(), bmp.getHeight(), profile, ExifSynthesizer.CURRENT_PHOTOMETRY);
        }
        return rawJpeg;
    }

    // ---------------------------------------------------------------------
    // Media Staging, Config Resolution & Telemetry Reporting
    // ---------------------------------------------------------------------
    private boolean isInjectionActive() {
        File controlFile = new File(Environment.getExternalStorageDirectory().getPath()
                + "/DCIM/Camera1/disable.jpg");
        if (controlFile.exists()) {
            return false;
        }
        return cfgEnabled && cfgAllowedByFilter && !isPassthroughActive();
    }

    private boolean isSyntheticSourceMode() {
        String mode = activeTransformConfig.sourceMode;
        return TransformConfig.SOURCE_TEST_PATTERN.equals(mode)
                || TransformConfig.SOURCE_QR.equals(mode);
    }

    private boolean hasVideoStaged() {
        return new File(video_path + "virtual.mp4").exists();
    }

    private boolean hasImageStaged() {
        return new File(video_path + "1000.bmp").exists();
    }

    private boolean hasAnyMedia() {
        return isSyntheticSourceMode()
                || getActiveStreamUrl() != null
                || hasVideoStaged()
                || hasImageStaged();
    }

    private void maybeShowMissingMediaToast(String pkg) {
        File toastControl = new File(Environment.getExternalStorageDirectory().getPath()
                + "/DCIM/Camera1/no_toast.jpg");
        need_to_show_toast = cfgToastEnabled && !toastControl.exists();
        if (toast_content != null && need_to_show_toast) {
            try {
                Toast.makeText(toast_content,
                        "[VCAM] No replacement image or video\n" + pkg + "\nPath: " + video_path,
                        Toast.LENGTH_SHORT).show();
            } catch (Throwable ignored) {
            }
        }
    }

    private static volatile long lastHookToastTime = 0L;

    private void showInjectedHookToast(String camType, int width, int height) {
        long now = System.currentTimeMillis();
        if (now - lastHookToastTime < 4000L) {
            return;
        }
        lastHookToastTime = now;
        File toastControl = new File(Environment.getExternalStorageDirectory().getPath()
                + "/DCIM/Camera1/no_toast.jpg");
        need_to_show_toast = cfgToastEnabled && !toastControl.exists();
        Context ctx = toast_content != null ? toast_content : resolveHostContext();
        if (ctx != null && need_to_show_toast) {
            new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    String facing = currentFacing != null ? currentFacing : "back";
                    String res = (width > 0 && height > 0) ? (width + "x" + height) : "";
                    String msg = "[VCAM] Injected: " + camType + " (" + facing + ") " + res;
                    Toast.makeText(ctx, msg.trim(), Toast.LENGTH_SHORT).show();
                } catch (Throwable ignored) {
                }
            });
        }
    }

    @Nullable
    private static Context resolveHostContext() {
        try {
            Application app = AndroidAppHelper.currentApplication();
            if (app != null) {
                return app.getApplicationContext();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private void resolveWritableVideoPath(@Nullable Context ctx) {
        File publicDir = new File(Environment.getExternalStorageDirectory().getPath() + "/DCIM/Camera1/");
        File forcePrivateMarker = new File(publicDir, "private_dir.jpg");
        if (ctx == null) {
            if (publicDir.exists() || publicDir.mkdirs()) {
                video_path = publicDir.getAbsolutePath() + "/";
            }
            return;
        }

        // On Android 11+ (API 30+), Scoped Storage FUSE hides or blocks cross-app non-media files
        // (.bmp) in /sdcard/DCIM/Camera1/. Prefer the app-private staging directory unless the user
        // already placed readable media in /sdcard/DCIM/Camera1/ and did not set private_dir.jpg.
        boolean publicHasReadableMedia = new File(publicDir, "virtual.mp4").canRead()
                || new File(publicDir, "1000.bmp").canRead();
        if (!forcePrivateMarker.exists() && Build.VERSION.SDK_INT < Build.VERSION_CODES.R && publicHasReadableMedia) {
            video_path = publicDir.getAbsolutePath() + "/";
            return;
        }

        File priv = ctx.getExternalFilesDir(null);
        if (priv == null) {
            priv = ctx.getFilesDir();
        }
        if (priv != null) {
            File dir = new File(priv, "Camera1/");
            if (dir.exists() || dir.mkdirs()) {
                video_path = dir.getAbsolutePath() + "/";
                return;
            }
        }
        if (publicDir.exists() || publicDir.mkdirs()) {
            video_path = publicDir.getAbsolutePath() + "/";
        }
    }

    private static void detectFacingCamera1(XC_MethodHook.MethodHookParam param) {
        try {
            int id = 0;
            if (param.args.length > 0 && param.args[0] instanceof Integer) {
                id = (Integer) param.args[0];
            }
            Camera.CameraInfo info = new Camera.CameraInfo();
            Camera.getCameraInfo(id, info);
            currentFacing = (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT)
                    ? MediaMappings.FACING_FRONT : MediaMappings.FACING_BACK;
        } catch (Throwable ignored) {
        }
    }

    private static void detectFacingCamera2(XC_MethodHook.MethodHookParam param) {
        try {
            if (param.args.length == 0 || !(param.args[0] instanceof String)) return;
            String cameraId = (String) param.args[0];
            Object thisObj = param.thisObject;
            if (!(thisObj instanceof CameraManager)) return;
            CameraManager cm = (CameraManager) thisObj;
            CameraCharacteristics cc = cm.getCameraCharacteristics(cameraId);
            Integer lf = cc.get(CameraCharacteristics.LENS_FACING);
            if (lf != null) {
                currentFacing = (lf == CameraMetadata.LENS_FACING_FRONT)
                        ? MediaMappings.FACING_FRONT : MediaMappings.FACING_BACK;
            }
        } catch (Throwable ignored) {
        }
    }

    private void ensureStagedNow(String hostPkg) {
        Context ctx = toast_content != null ? toast_content : resolveHostContext();
        if (hostPkg != null && (currentHostPackage == null || currentHostPackage.isEmpty())) {
            currentHostPackage = hostPkg;
        }
        if (ctx != null) {
            if (toast_content == null) {
                toast_content = ctx;
                refreshConfigFromProvider(ctx, currentHostPackage);
                resolveWritableVideoPath(ctx);
            }
            try {
                maybeRestage(ctx, currentHostPackage, currentFacing);
            } catch (Throwable t) {
                XposedBridge.log("[VCAM][stage-sync] " + t);
            }
        } else {
            resolveWritableVideoPath(null);
        }
    }

    private static synchronized void maybeRestage(Context ctx, String pkg, String facing) {
        if (ctx == null) return;
        String key = pkg + "|" + facing;
        if (key.equals(lastStagedKey)) return;
        refreshConfigFromProvider(ctx, pkg);
        boolean completed = stageFromProviderFor(ctx, pkg, facing);
        if (completed) {
            lastStagedKey = key;
        }
    }

    private static void refreshConfigFromProvider(@NonNull Context ctx, @NonNull String pkg) {
        Cursor c = null;
        try {
            Uri u = Uri.parse("content://com.example.vcam/config")
                    .buildUpon()
                    .appendQueryParameter("pkg", pkg)
                    .appendQueryParameter("facing", currentFacing)
                    .build();
            c = ctx.getContentResolver().query(u, null, null, null, null);
            if (c != null && c.moveToFirst()) {
                cfgEnabled = c.getInt(0) != 0;
                cfgMute = c.getInt(1) != 0;
                cfgLoopVideo = c.getInt(2) != 0;
                cfgToastEnabled = c.getInt(3) != 0;
                cfgInAppUiEnabled = c.getInt(4) != 0;
                if (c.getColumnCount() > 5) cfgOverlayHudEnabled = c.getInt(5) != 0;
                if (c.getColumnCount() > 6) cfgPassthroughMode = c.getInt(6) != 0;
                if (c.getColumnCount() > 7) cfgVirtualMicEnabled = c.getInt(7) != 0;
                if (c.getColumnCount() > 8) cfgInterceptCaptureIntents = c.getInt(8) != 0;
                if (c.getColumnCount() > 9) cfgMetadataPatchEnabled = c.getInt(9) != 0;
                if (c.getColumnCount() > 10) cfgSensorGrainEnabled = c.getInt(10) != 0;
                if (c.getColumnCount() > 11) {
                    String tfJson = c.getString(11);
                    if (tfJson != null && !tfJson.isEmpty()) {
                        TransformConfig parsed = TransformConfig.fromJson(tfJson);
                        parsed.sensorGrainEnabled = cfgSensorGrainEnabled;
                        activeTransformConfig = parsed;
                        StaticBitmapFeeder.invalidateAllCaches();
                    }
                }
                if (c.getColumnCount() > 12) {
                    String lpJson = c.getString(12);
                    if (lpJson != null && !lpJson.isEmpty()) {
                        activeLensProfile = SensorProfiler.LensProfile.fromJson(lpJson);
                    }
                }
                if (c.getColumnCount() > 14) {
                    String cuesJson = c.getString(14);
                    List<TransformConfig.CuePoint> parsedCues = TransformConfig.cuesFromJson(cuesJson);
                    synchronized (activeCuePoints) {
                        activeCuePoints.clear();
                        activeCuePoints.addAll(parsedCues);
                    }
                }
                if (c.getColumnCount() > 15) {
                    cfgAllowedByFilter = c.getInt(15) != 0;
                }
                InAppOverlayManager.setOverlayEnabled(cfgOverlayHudEnabled);
            }
        } catch (Throwable t) {
            XposedBridge.log("[VCAM][config] " + t);
        } finally {
            if (c != null) {
                try { c.close(); } catch (Throwable ignored) {}
            }
        }
    }

    private static boolean stageFromProviderFor(Context ctx, String pkg, String facing) {
        if (ctx == null) return false;
        try {
            File dir = new File(video_path);
            if (!dir.exists()) {
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
            }
        } catch (Throwable ignored) {
        }

        File localImg = new File(video_path + "1000.bmp");
        File localVid = new File(video_path + "virtual.mp4");

        String imgUri = queryResolve(ctx, pkg, facing, MediaMappings.TYPE_IMAGE);
        String vidUri = queryResolve(ctx, pkg, facing, MediaMappings.TYPE_VIDEO);

        boolean imgDone = false;
        boolean vidDone = false;

        if (MediaProvider.STATUS_UNSET.equals(imgUri)) {
            if (localImg.exists()) {
                //noinspection ResultOfMethodCallIgnored
                localImg.delete();
            }
            StaticBitmapFeeder.invalidateAllCaches();
            imgDone = true;
        } else if (imgUri != null) {
            imgDone = copyProviderUri(ctx, Uri.parse(imgUri), localImg);
            StaticBitmapFeeder.invalidateAllCaches();
        }

        if (MediaProvider.STATUS_UNSET.equals(vidUri)) {
            if (localVid.exists()) {
                //noinspection ResultOfMethodCallIgnored
                localVid.delete();
            }
            vidDone = true;
        } else if (vidUri != null) {
            vidDone = copyProviderUri(ctx, Uri.parse(vidUri), localVid);
        }

        return imgDone && vidDone;
    }

    @Nullable
    private static String queryResolve(Context ctx, String pkg, String facing, String type) {
        Cursor c = null;
        try {
            Uri u = Uri.parse("content://com.example.vcam/resolve")
                    .buildUpon()
                    .appendQueryParameter("pkg", pkg)
                    .appendQueryParameter("facing", facing)
                    .appendQueryParameter("type", type)
                    .build();
            c = ctx.getContentResolver().query(u, null, null, null, null);
            if (c == null) return null;
            if (c.moveToFirst()) {
                if (c.getColumnCount() >= 2) {
                    String status = c.getString(1);
                    if (MediaProvider.STATUS_UNSET.equals(status)) {
                        return MediaProvider.STATUS_UNSET;
                    }
                }
                String uri = c.getString(0);
                if (uri != null && !uri.isEmpty() && !"null".equals(uri)) {
                    return uri;
                }
                return MediaProvider.STATUS_UNSET;
            }
        } catch (Throwable t) {
            XposedBridge.log("[VCAM][resolve] " + t);
        } finally {
            if (c != null) {
                try { c.close(); } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    private static boolean copyProviderUri(Context ctx, Uri src, File dst) {
        InputStream in = null;
        OutputStream out = null;
        try {
            in = ctx.getContentResolver().openInputStream(src);
            if (in == null) return false;
            File tmp = new File(dst.getAbsolutePath() + ".tmp");
            out = new FileOutputStream(tmp);
            byte[] buf = new byte[64 * 1024];
            int n;
            long total = 0;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                total += n;
            }
            out.flush();
            try { out.close(); } catch (IOException ignored) {}
            out = null;
            if (total > 0) {
                //noinspection ResultOfMethodCallIgnored
                tmp.renameTo(dst);
                return true;
            } else {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                return false;
            }
        } catch (Throwable t) {
            return false;
        } finally {
            if (in != null) try { in.close(); } catch (IOException ignored) {}
            if (out != null) try { out.close(); } catch (IOException ignored) {}
        }
    }

    private static void reportHostTelemetry(final String apiType, final int w, final int h, final int fmt) {
        final Context ctx = resolveHostContext();
        final String pkg = currentHostPackage;
        final String facing = currentFacing;
        if (ctx == null || pkg == null || pkg.isEmpty()) return;
        stagingExecutor.execute(() -> {
            try {
                SensorProfiler.HostTelemetry ht = new SensorProfiler.HostTelemetry(
                        pkg, apiType, facing, w, h, fmt, 30, 30, System.currentTimeMillis());
                Bundle extras = new Bundle();
                extras.putString("pkg", pkg);
                extras.putString("telemetry_json", ht.toJson().toString());
                ctx.getContentResolver().call(MediaProvider.CONTENT_URI,
                        MediaProvider.METHOD_REPORT_TELEMETRY, null, extras);
            } catch (Throwable ignored) {
            }
        });
    }

    private static synchronized void registerMappingUpdatedReceiver(Context ctx) {
        if (ctx == null || mappingReceiverRegistered) return;
        try {
            BroadcastReceiver r = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    lastStagedKey = null;
                    refreshConfigFromProvider(context, currentHostPackage);
                }
            };
            IntentFilter f = new IntentFilter(MediaMappings.ACTION_UPDATED);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ctx.registerReceiver(r, f, Context.RECEIVER_EXPORTED);
            } else {
                ctx.registerReceiver(r, f);
            }
            mappingReceiverRegistered = true;
        } catch (Throwable ignored) {
        }
    }

    private void maybePostInAppNotification(Context hostCtx, String targetPackage) {
        if (in_app_ui_notification_posted || hostCtx == null || !cfgInAppUiEnabled) {
            return;
        }
        try {
            Intent launch = new Intent();
            launch.setComponent(new ComponentName(
                    BuildConfig.APPLICATION_ID,
                    BuildConfig.APPLICATION_ID + ".InjectedConfigActivity"));
            launch.putExtra("target_package", targetPackage != null ? targetPackage : "");
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

            int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                piFlags |= PendingIntent.FLAG_IMMUTABLE;
            }
            PendingIntent pi = PendingIntent.getActivity(hostCtx, 0x7CA4, launch, piFlags);
            NotificationManager nm = (NotificationManager)
                    hostCtx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;

            Notification.Builder b;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel ch = new NotificationChannel(
                        VCAM_CHANNEL_ID, "VCAM In-App Control", NotificationManager.IMPORTANCE_LOW);
                nm.createNotificationChannel(ch);
                b = new Notification.Builder(hostCtx, VCAM_CHANNEL_ID);
            } else {
                b = new Notification.Builder(hostCtx);
            }
            b.setSmallIcon(android.R.drawable.ic_menu_camera)
                    .setContentTitle("VCAM Active (" + currentFacing + ")")
                    .setContentText("Tap to configure media or toggle HUD for " + targetPackage)
                    .setOngoing(false)
                    .setAutoCancel(true)
                    .setContentIntent(pi);

            nm.notify(VCAM_NOTIF_ID, b.build());
            in_app_ui_notification_posted = true;
        } catch (Throwable ignored) {
        }
    }
}
