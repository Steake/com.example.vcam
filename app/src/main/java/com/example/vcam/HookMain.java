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
                    "system",
                    "com.android.systemui",
                    "com.google.android.systemui",
                    "com.miui.systemui",
                    "com.sec.android.systemui",
                    "com.oplus.systemui",
                    "com.coloros.systemui",
                    "com.oneplus.systemui",
                    "com.android.phone",
                    "com.android.se",
                    "com.android.bluetooth",
                    "com.android.nfc",
                    "com.android.settings",
                    "com.android.shell",
                    "com.android.keyguard",
                    "com.android.launcher",
                    "com.android.launcher3",
                    "com.android.providers.settings",
                    "com.android.providers.media",
                    "com.android.providers.contacts",
                    "com.android.providers.telephony",
                    "de.robv.android.xposed.installer",
                    "org.lsposed.manager"
            ))
    );

    private static boolean isExcludedSystemPackage(@NonNull XC_LoadPackage.LoadPackageParam lpparam) {
        int myAppId = android.os.Process.myUid() % 100000;
        // Never hook core system UIDs (< 10000: root=0, system_server=1000, radio=1001, etc.)
        // or isolated WebView/sandboxed processes (90000..99999)
        if (myAppId < 10000 || (myAppId >= 90000 && myAppId <= 99999)) {
            return true;
        }
        String pkg = lpparam.packageName;
        if (pkg == null || pkg.isEmpty() || "android".equals(pkg) || "system".equals(pkg)) return true;
        if ("system".equals(lpparam.processName) || "android".equals(lpparam.processName)) return true;
        if (EXCLUDED_SYSTEM_PACKAGES.contains(pkg)) return true;
        String lower = pkg.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("systemui") || lower.contains("keyguard")
                || lower.contains(".launcher") || lower.contains("inputmethod")
                || lower.startsWith("com.android.providers.")
                || lower.startsWith("com.android.server.")
                || lower.startsWith("com.qualcomm.")) {
            return true;
        }
        if (lpparam.appInfo != null) {
            int appId = lpparam.appInfo.uid % 100000;
            if (appId < 10000 || (appId >= 90000 && appId <= 99999)) {
                return true;
            }
        }
        if (isUserLockedDirectBoot()) {
            return true;
        }
        return false;
    }

    private static boolean isUserLockedDirectBoot() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                Application app = AndroidAppHelper.currentApplication();
                if (app != null) {
                    android.os.UserManager um = app.getSystemService(android.os.UserManager.class);
                    if (um != null && !um.isUserUnlocked()) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

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
    private static final Set<String> HOOKED_HIERARCHY_METHODS =
            Collections.synchronizedSet(new HashSet<>());
    private static final ThreadLocal<Boolean> IN_INTERNAL_CHECK = new ThreadLocal<Boolean>() {
        @Override
        protected Boolean initialValue() {
            return Boolean.FALSE;
        }
    };
    private static volatile String lastResolvedImageUri = null;
    private static volatile String lastResolvedVideoUri = null;

    /**
     * Walks the class hierarchy starting at {@code clazz} up to {@link Object} to locate
     * a declared method with the exact {@code methodName} and {@code paramTypes}.
     * Fixes XposedHelpers.findAndHookMethod throwing NoSuchMethodError when a callback
     * or framework subclass inherits its implementation from a base class (e.g. CameraX).
     */
    @Nullable
    public static java.lang.reflect.Method findMethodInHierarchy(
            @Nullable Class<?> clazz,
            @NonNull String methodName,
            @NonNull Class<?>... paramTypes) {
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Method m = c.getDeclaredMethod(methodName, paramTypes);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /**
     * Hooks {@code methodName(paramTypes)} on the declaring class in {@code clazz}'s
     * hierarchy, deduplicating so shared base classes are only hooked once.
     */
    public static boolean hookMethodInHierarchy(
            @Nullable Class<?> clazz,
            @NonNull String methodName,
            @NonNull XC_MethodHook hook,
            @NonNull Class<?>... paramTypes) {
        java.lang.reflect.Method m = findMethodInHierarchy(clazz, methodName, paramTypes);
        if (m == null) {
            return false;
        }
        StringBuilder sig = new StringBuilder(m.getDeclaringClass().getName())
                .append('#').append(methodName).append('(');
        for (Class<?> pt : paramTypes) {
            sig.append(pt != null ? pt.getName() : "null").append(',');
        }
        sig.append(')');
        if (!HOOKED_HIERARCHY_METHODS.add(sig.toString())) {
            return true;
        }
        try {
            XposedBridge.hookMethod(m, hook);
            return true;
        } catch (Throwable t) {
            XposedBridge.log("[VCAM] hookMethodInHierarchy failed for " + sig + ": " + t);
            return false;
        }
    }

    /**
     * Hooks all overloads of {@code methodName} in {@code clazz}'s hierarchy on the
     * first class that declares at least one matching method.
     */
    public static int hookAllMethodsInHierarchy(
            @Nullable Class<?> clazz,
            @NonNull String methodName,
            @NonNull XC_MethodHook hook) {
        int count = 0;
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Method[] methods = c.getDeclaredMethods();
                boolean foundInClass = false;
                for (java.lang.reflect.Method m : methods) {
                    if (methodName.equals(m.getName())) {
                        foundInClass = true;
                        StringBuilder sig = new StringBuilder(c.getName())
                                .append('#').append(methodName).append('(');
                        for (Class<?> pt : m.getParameterTypes()) {
                            sig.append(pt.getName()).append(',');
                        }
                        sig.append(')');
                        if (HOOKED_HIERARCHY_METHODS.add(sig.toString())) {
                            m.setAccessible(true);
                            XposedBridge.hookMethod(m, hook);
                            count++;
                        }
                    }
                }
                if (foundInClass) {
                    break;
                }
            } catch (Throwable ignored) {
            }
        }
        return count;
    }

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
        NdkHookEngine.syncSensorProfile(activeLensProfile, currentFacing);
        ImuMotionCorrelator.getInstance().start(inst.toast_content != null ? inst.toast_content : resolveHostContext());
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
        if (!cameraSessionActive) {
            onNdkCameraOpened();
        }

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
    public static File resolveBestVideoFile() {
        try {
            IN_INTERNAL_CHECK.set(Boolean.TRUE);
            File primary = new File(video_path + "virtual.mp4");
            if (primary.exists() && primary.length() > 0 && primary.canRead()) {
                return primary;
            }
            File pub1 = new File(Environment.getExternalStorageDirectory(), "DCIM/Camera1/virtual.mp4");
            if (pub1.exists() && pub1.length() > 0 && pub1.canRead()) {
                return pub1;
            }
            File pub2 = new File("/storage/emulated/0/DCIM/Camera1/virtual.mp4");
            if (pub2.exists() && pub2.length() > 0 && pub2.canRead()) {
                return pub2;
            }
            File pub3 = new File("/sdcard/DCIM/Camera1/virtual.mp4");
            if (pub3.exists() && pub3.length() > 0 && pub3.canRead()) {
                return pub3;
            }
        } catch (Throwable ignored) {
        } finally {
            IN_INTERNAL_CHECK.set(Boolean.FALSE);
        }
        return null;
    }

    @Nullable
    public static File resolveBestImageFile(@Nullable String preferredPath) {
        try {
            IN_INTERNAL_CHECK.set(Boolean.TRUE);
            if (preferredPath != null && !preferredPath.isEmpty()) {
                File pref = new File(preferredPath);
                if (pref.exists() && pref.length() > 0 && pref.canRead()) {
                    return pref;
                }
            }
            File primary = new File(video_path + "1000.bmp");
            if (primary.exists() && primary.length() > 0 && primary.canRead()) {
                return primary;
            }
            File pub1 = new File(Environment.getExternalStorageDirectory(), "DCIM/Camera1/1000.bmp");
            if (pub1.exists() && pub1.length() > 0 && pub1.canRead()) {
                return pub1;
            }
            File pub2 = new File("/storage/emulated/0/DCIM/Camera1/1000.bmp");
            if (pub2.exists() && pub2.length() > 0 && pub2.canRead()) {
                return pub2;
            }
            File pub3 = new File("/sdcard/DCIM/Camera1/1000.bmp");
            if (pub3.exists() && pub3.length() > 0 && pub3.canRead()) {
                return pub3;
            }
        } catch (Throwable ignored) {
        } finally {
            IN_INTERNAL_CHECK.set(Boolean.FALSE);
        }
        return null;
    }

    @Nullable
    public static Bitmap decodeActiveImageFromProvider() {
        Context ctx = resolveHostContext();
        if (ctx == null) {
            return null;
        }
        try {
            String imgUri = lastResolvedImageUri;
            if (imgUri == null || MediaProvider.STATUS_UNSET.equals(imgUri)) {
                imgUri = queryResolve(ctx, currentHostPackage, currentFacing, MediaMappings.TYPE_IMAGE);
            }
            if (imgUri == null || MediaProvider.STATUS_UNSET.equals(imgUri)) {
                return null;
            }
            try (InputStream in = ctx.getContentResolver().openInputStream(Uri.parse(imgUri))) {
                if (in != null) {
                    return BitmapFactory.decodeStream(in);
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    @Nullable
    public static File getActiveVideoFile() {
        return resolveBestVideoFile();
    }

    @Nullable
    public static ParcelFileDescriptor openActiveVideoPfd() {
        Context ctx = resolveHostContext();
        if (ctx == null) {
            return null;
        }
        try {
            String vidUri = lastResolvedVideoUri;
            if (vidUri == null || MediaProvider.STATUS_UNSET.equals(vidUri)) {
                vidUri = queryResolve(ctx, currentHostPackage, currentFacing, MediaMappings.TYPE_VIDEO);
            }
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
        try {
            final XC_MethodHook featureHook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.args == null || param.args.length == 0 || !(param.args[0] instanceof String)) return;
                    String feat = (String) param.args[0];
                    if ("android.hardware.camera".equals(feat)
                            || "android.hardware.camera.front".equals(feat)
                            || "android.hardware.camera.any".equals(feat)
                            || "android.hardware.camera.autofocus".equals(feat)
                            || "android.hardware.camera.flash".equals(feat)
                            || "android.hardware.camera.level.full".equals(feat)
                            || "android.hardware.camera.capability.manual_sensor".equals(feat)
                            || "android.hardware.camera.capability.manual_post_processing".equals(feat)
                            || "android.hardware.camera.capability.raw".equals(feat)) {
                        param.setResult(Boolean.TRUE);
                    }
                }
            };
            XposedHelpers.findAndHookMethod("android.app.ApplicationPackageManager", lpparam.classLoader,
                    "hasSystemFeature", String.class, featureHook);
            XposedHelpers.findAndHookMethod("android.app.ApplicationPackageManager", lpparam.classLoader,
                    "hasSystemFeature", String.class, int.class, featureHook);
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
        if (isExcludedSystemPackage(lpparam)) {
            return;
        }
        sInstance = this;
        if (sModuleApkPath == null) {
            if (lpparam.appInfo != null && BuildConfig.APPLICATION_ID.equals(lpparam.packageName)) {
                sModuleApkPath = lpparam.appInfo.sourceDir;
            } else {
                sModuleApkPath = NdkHookEngine.resolveModuleApkPath();
            }
        }
        if (lpparam.appInfo != null && lpparam.appInfo.dataDir != null) {
            NdkHookEngine.setHostDataDir(lpparam.appInfo.dataDir);
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
                        Context base = (Application) param.args[0];
                        Context appCtx = base.getApplicationContext();
                        ctx = isValidHostContext(appCtx) ? appCtx : (isValidHostContext(base) ? base : null);
                    } else if (param.args[0] instanceof Context) {
                        Context base = (Context) param.args[0];
                        Context appCtx = base.getApplicationContext();
                        ctx = isValidHostContext(appCtx) ? appCtx : (isValidHostContext(base) ? base : null);
                    }
                }
                if (!isValidHostContext(ctx) && param.thisObject instanceof Context) {
                    Context self = (Context) param.thisObject;
                    if (isValidHostContext(self)) {
                        Context appCtx = self.getApplicationContext();
                        ctx = isValidHostContext(appCtx) ? appCtx : self;
                    }
                }
                if (!isValidHostContext(ctx)) {
                    ctx = resolveHostContext();
                }
                if (isValidHostContext(ctx) && (!isValidHostContext(toast_content) || is_first_hook_build)) {
                    boolean wasInvalid = !isValidHostContext(toast_content);
                    toast_content = ctx;
                    if (wasInvalid) {
                        lastStagedKey = null;
                    }
                    try {
                        if (!isSelfManager && ctx instanceof Application) {
                            InAppOverlayManager.registerLifecycle((Application) ctx);
                        }
                        registerMappingUpdatedReceiver(toast_content);
                        resolveWritableVideoPath(toast_content);
                        final Context asyncCtx = toast_content;
                        stagingExecutor.execute(() -> {
                            try {
                                refreshConfigFromProvider(asyncCtx, lpparam.packageName);
                                if (!isSelfManager && cfgOverlayHudEnabled) {
                                    InAppOverlayManager.setOverlayEnabled(true);
                                }
                                ensureStagedNow(lpparam.packageName);
                                if (!isSelfManager) {
                                    NdkHookEngine.ensureInitialized(sModuleApkPath);
                                }
                            } catch (Throwable t) {
                                XposedBridge.log("[VCAM][early-init-async] " + t);
                            }
                        });
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

        try {
            XposedHelpers.findAndHookMethod(android.content.ContextWrapper.class,
                    "attachBaseContext", Context.class, earlyInitHook);
        } catch (Throwable ignored) {}

        try {
            XposedHelpers.findAndHookMethod(Activity.class,
                    "onCreate", Bundle.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!isValidHostContext(toast_content) && param.thisObject instanceof Context) {
                                Context self = (Context) param.thisObject;
                                Context actCtx = self.getApplicationContext();
                                if (!isValidHostContext(actCtx)) actCtx = self;
                                if (isValidHostContext(actCtx)) {
                                    toast_content = actCtx;
                                    lastStagedKey = null;
                                    try {
                                        registerMappingUpdatedReceiver(toast_content);
                                        resolveWritableVideoPath(toast_content);
                                        final Context asyncCtx = toast_content;
                                        stagingExecutor.execute(() -> {
                                            try {
                                                refreshConfigFromProvider(asyncCtx, lpparam.packageName);
                                                ensureStagedNow(lpparam.packageName);
                                            } catch (Throwable ignored) {}
                                        });
                                    } catch (Throwable ignored) {}
                                }
                            }
                        }
                    });
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
                if (Boolean.TRUE.equals(IN_INTERNAL_CHECK.get())) {
                    return;
                }
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
                    if (Boolean.TRUE.equals(IN_INTERNAL_CHECK.get())) {
                        return;
                    }
                    Object selfLoader = param.thisObject;
                    if ( isTrustedFrameworkOrModuleClassLoader(selfLoader)) {
                        return;
                    }
                    String className = (String) param.args[0];
                    if (className != null && (
                            className.equals("de.robv.android.xposed.XposedBridge") ||
                            className.equals("de.robv.android.xposed.XC_MethodHook") ||
                            className.equals("de.robv.android.xposed.XposedHelpers") ||
                            className.startsWith("org.lsposed.lspd") ||
                            className.startsWith("com.example.vcam."))) {
                        if (!isInternalVcamCaller()) {
                            param.setThrowable(new ClassNotFoundException(className));
                        }
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

        // Cloak /Camera1 (both Sumsub EXTERNAL /DCIM/Camera1 & INTERNAL getExternalFilesDir/Camera1),
        // libvcam_ndk/libcam_hal3_ext_ native artifacts, and su/Magisk binaries from host SDK probes
        XC_MethodHook fileBoolCloakHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (Boolean.TRUE.equals(IN_INTERNAL_CHECK.get())) return;
                if (!Boolean.TRUE.equals(param.getResult())) return;
                if (!(param.thisObject instanceof File)) return;
                String path = ((File) param.thisObject).getPath();
                if (isCloakedFilePath(path) && !isInternalVcamCaller()) {
                    param.setResult(Boolean.FALSE);
                }
            }
        };
        try {
            XposedHelpers.findAndHookMethod(File.class, "exists", fileBoolCloakHook);
        } catch (Throwable ignored) {}
        try {
            XposedHelpers.findAndHookMethod(File.class, "canRead", fileBoolCloakHook);
        } catch (Throwable ignored) {}
        try {
            XposedHelpers.findAndHookMethod(File.class, "canWrite", fileBoolCloakHook);
        } catch (Throwable ignored) {}
        try {
            XposedHelpers.findAndHookMethod(File.class, "length", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (Boolean.TRUE.equals(IN_INTERNAL_CHECK.get())) return;
                    if (!(param.thisObject instanceof File)) return;
                    String path = ((File) param.thisObject).getPath();
                    if (isCloakedFilePath(path) && !isInternalVcamCaller()) {
                        param.setResult(0L);
                    }
                }
            });
        } catch (Throwable ignored) {}

        // Sanitize Build.TAGS ("test-keys" -> "release-keys") and emulator Build properties
        sanitizeBuildFieldsForHost();

        // Hook RootBeer (used by Sumsub com.sumsub.sns.internal.util.root.a) if present in host ClassLoader
        try {
            Class<?> rootBeerClass = XposedHelpers.findClassIfExists("com.scottyab.rootbeer.RootBeer", classLoader);
            if (rootBeerClass != null) {
                XC_MethodHook alwaysFalse = new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        param.setResult(Boolean.FALSE);
                    }
                };
                for (String rbMethod : new String[]{
                        "isRooted", "isRootedWithoutBusyBoxCheck", "isRootedWithBusyBoxCheck",
                        "detectRootManagementApps", "detectPotentiallyDangerousApps",
                        "detectTestKeys", "checkForBusyBoxBinary", "checkForSuBinary",
                        "checkSuExists", "checkForRWPaths", "checkForDangerousProps",
                        "checkForRootNative", "detectRootCloakingApps", " checkForMagiskBinary"
                }) {
                    try {
                        XposedBridge.hookAllMethods(rootBeerClass, rbMethod.trim(), alwaysFalse);
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}

        // Hook SensorManager listener dispatch so Persona RealCameraStatsManager never sees
        // dead 0.0 rad/s gyroscope telemetry during an active camera session
        try {
            Class<?> sysSensorMgr = XposedHelpers.findClassIfExists("android.hardware.SystemSensorManager$SensorEventQueue", classLoader);
            if (sysSensorMgr != null) {
                XposedBridge.hookAllMethods(sysSensorMgr, "dispatchSensorEvent", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!cameraSessionActive || param.args == null || param.args.length < 2) return;
                        if (param.args[1] instanceof float[]) {
                            float[] vals = (float[]) param.args[1];
                            if (vals.length >= 3 && vals[0] == 0.0f && vals[1] == 0.0f && vals[2] == 0.0f) {
                                double t = System.nanoTime() * 1e-9;
                                vals[0] = (float) (0.016 * Math.sin(t * 5.7) + 0.005 * Math.cos(t * 11.3));
                                vals[1] = (float) (0.015 * Math.cos(t * 4.9) + 0.004 * Math.sin(t * 9.7));
                                vals[2] = (float) (0.012 * Math.sin(t * 6.3));
                            }
                        }
                    }
                });
            }
        } catch (Throwable ignored) {}
    }

    private static boolean isCloakedFilePath(@Nullable String path) {
        if (path == null || path.isEmpty()) return false;
        if (path.contains("/Camera1") || path.endsWith("/Camera1")
                || path.contains("libvcam_ndk") || path.contains("libcam_hal3_ext_")) {
            return true;
        }
        String lower = path.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith("/su") || lower.endsWith("/superuser.apk")
                || lower.contains("/magisk") || lower.contains("/busybox")) {
            return true;
        }
        return false;
    }

    private static void sanitizeBuildFieldsForHost() {
        try {
            if (Build.TAGS != null && Build.TAGS.contains("test-keys")) {
                XposedHelpers.setStaticObjectField(Build.class, "TAGS", "release-keys");
            }
            if (Build.FINGERPRINT != null && (Build.FINGERPRINT.startsWith("generic")
                    || Build.FINGERPRINT.contains("vbox") || Build.FINGERPRINT.contains("test-keys")
                    || Build.FINGERPRINT.contains("sdk_gphone"))) {
                XposedHelpers.setStaticObjectField(Build.class, "FINGERPRINT",
                        "google/husky/husky:14/UQ1A.240205.004/11269751:user/release-keys");
            }
            if (Build.MODEL != null && (Build.MODEL.contains("google_sdk")
                    || Build.MODEL.contains("Emulator")
                    || Build.MODEL.contains("Android SDK built for")
                    || Build.MODEL.contains("sdk_gphone"))) {
                XposedHelpers.setStaticObjectField(Build.class, "MODEL", "Pixel 8 Pro");
                XposedHelpers.setStaticObjectField(Build.class, "MANUFACTURER", "Google");
                XposedHelpers.setStaticObjectField(Build.class, "BRAND", "google");
                XposedHelpers.setStaticObjectField(Build.class, "DEVICE", "husky");
                XposedHelpers.setStaticObjectField(Build.class, "PRODUCT", "husky");
                XposedHelpers.setStaticObjectField(Build.class, "HARDWARE", "shiba");
            }
        } catch (Throwable ignored) {}
    }

    private static boolean isTrustedFrameworkOrModuleClassLoader(@Nullable Object loaderObj) {
        if (!(loaderObj instanceof ClassLoader)) return false;
        ClassLoader target = (ClassLoader) loaderObj;
        for (ClassLoader cl = HookMain.class.getClassLoader(); cl != null; cl = cl.getParent()) {
            if (cl == target) return true;
        }
        for (ClassLoader cl = XposedBridge.class.getClassLoader(); cl != null; cl = cl.getParent()) {
            if (cl == target) return true;
        }
        return false;
    }

    private static boolean isInternalVcamCaller() {
        boolean prev = Boolean.TRUE.equals(IN_INTERNAL_CHECK.get());
        IN_INTERNAL_CHECK.set(Boolean.TRUE);
        try {
            StackTraceElement[] raw = new Throwable().getStackTrace();
            if (raw == null || raw.length == 0) {
                return true;
            }
            for (StackTraceElement el : raw) {
                String cn = el.getClassName();
                if (cn != null && (cn.startsWith("com.example.vcam.")
                        || cn.startsWith("android.media.")
                        || cn.startsWith("android.graphics.BitmapFactory"))) {
                    return true;
                }
            }
            return false;
        } catch (Throwable ignored) {
            return true;
        } finally {
            IN_INTERNAL_CHECK.set(prev);
        }
    }

    private static StackTraceElement[] filterStackTrace(StackTraceElement[] orig) {
        if (orig == null || orig.length == 0) return orig;
        List<StackTraceElement> cleaned = new ArrayList<>(orig.length);
        for (StackTraceElement elem : orig) {
            String cn = elem.getClassName();
            if (cn != null) {
                String lower = cn.toLowerCase(java.util.Locale.ROOT);
                if (lower.contains("xposed") || lower.contains("lsposed")
                        || lower.contains("lspd") || lower.contains("lsphooker")
                        || lower.contains("edhooker") || lower.contains("com.example.vcam")
                        || lower.contains("sandhook") || lower.contains("pine")
                        || lower.contains("yahfa") || lower.contains("whale")
                        || lower.contains("substrate") || lower.contains("zygisk")
                        || lower.contains("riru")) {
                    continue;
                }
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
                    "getNumberOfCameras", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (param.hasThrowable()) {
                                param.setThrowable(null);
                                param.setResult(2);
                            } else if (param.getResult() instanceof Integer && ((Integer) param.getResult()) <= 0) {
                                param.setResult(2);
                            }
                        }
                    });
        } catch (Throwable ignored) {
        }

        try {
            XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader,
                    "getCameraInfo", int.class, Camera.CameraInfo.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (param.hasThrowable() && param.args.length >= 2 && param.args[1] instanceof Camera.CameraInfo) {
                                int id = (param.args[0] instanceof Integer) ? (Integer) param.args[0] : 0;
                                Camera.CameraInfo info = (Camera.CameraInfo) param.args[1];
                                info.facing = (id == 1)
                                        ? Camera.CameraInfo.CAMERA_FACING_FRONT
                                        : Camera.CameraInfo.CAMERA_FACING_BACK;
                                info.orientation = (id == 1) ? 270 : 90;
                                param.setThrowable(null);
                                param.setResult(null);
                            }
                        }
                    });
        } catch (Throwable ignored) {
        }

        try {
            XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader,
                    "open", int.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            detectFacingCamera1(param);
                            ensureStagedNow(lpparam.packageName);
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (param.hasThrowable() && isInjectionActive() && hasAnyMedia()) {
                                Camera vCam = (Camera) allocateUnsafeInstance(Camera.class);
                                if (vCam != null) {
                                    XposedHelpers.setAdditionalInstanceField(vCam, "vcamVirtualCamera1", Boolean.TRUE);
                                    param.setThrowable(null);
                                    param.setResult(vCam);
                                }
                            }
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

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (param.hasThrowable() && isInjectionActive() && hasAnyMedia()) {
                                Camera vCam = (Camera) allocateUnsafeInstance(Camera.class);
                                if (vCam != null) {
                                    XposedHelpers.setAdditionalInstanceField(vCam, "vcamVirtualCamera1", Boolean.TRUE);
                                    param.setThrowable(null);
                                    param.setResult(vCam);
                                }
                            }
                        }
                    });
        } catch (Throwable ignored) {
        }

        try {
            XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader,
                    "getParameters", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (param.thisObject != null && Boolean.TRUE.equals(
                                    XposedHelpers.getAdditionalInstanceField(param.thisObject, "vcamVirtualCamera1"))) {
                                Object cached = XposedHelpers.getAdditionalInstanceField(param.thisObject, "vcamParams");
                                if (cached instanceof Camera.Parameters) {
                                    param.setResult(cached);
                                    return;
                                }
                                try {
                                    Camera.Parameters p = (Camera.Parameters) allocateUnsafeInstance(Camera.Parameters.class);
                                    if (p != null) {
                                        XposedHelpers.callMethod(p, "unflatten",
                                                "preview-size=1280x720;preview-size-values=1920x1080,1280x720,640x480;"
                                                        + "picture-size=1280x720;picture-size-values=1920x1080,1280x720,640x480;"
                                                        + "preview-format=yuv420sp;preview-format-values=yuv420sp,yuv420p;"
                                                        + "picture-format=jpeg;picture-format-values=jpeg;"
                                                        + "preview-frame-rate=30;preview-frame-rate-values=15,30;"
                                                        + "preview-fps-range=15000,30000;preview-fps-range-values=(15000,30000),(30000,30000);"
                                                        + "focus-mode=continuous-video;focus-mode-values=auto,continuous-picture,continuous-video;"
                                                        + "focal-length=4.25;horizontal-view-angle=60;vertical-view-angle=45");
                                        XposedHelpers.setAdditionalInstanceField(param.thisObject, "vcamParams", p);
                                        param.setResult(p);
                                    }
                                } catch (Throwable ignored) {}
                            }
                        }
                    });
            XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader,
                    "setParameters", Camera.Parameters.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (param.thisObject != null && Boolean.TRUE.equals(
                                    XposedHelpers.getAdditionalInstanceField(param.thisObject, "vcamVirtualCamera1"))) {
                                if (param.args.length > 0 && param.args[0] instanceof Camera.Parameters) {
                                    XposedHelpers.setAdditionalInstanceField(param.thisObject, "vcamParams", param.args[0]);
                                }
                                param.setResult(null);
                            }
                        }
                    });
            XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader,
                    "setDisplayOrientation", int.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (param.thisObject != null && Boolean.TRUE.equals(
                                    XposedHelpers.getAdditionalInstanceField(param.thisObject, "vcamVirtualCamera1"))) {
                                param.setResult(null);
                            }
                        }
                    });
        } catch (Throwable ignored) {}

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
                        if (param.thisObject != null && Boolean.TRUE.equals(
                                XposedHelpers.getAdditionalInstanceField(param.thisObject, "vcamVirtualCamera1"))) {
                            param.setResult(null);
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
                        if (param.thisObject == null || !Boolean.TRUE.equals(
                                XposedHelpers.getAdditionalInstanceField(param.thisObject, "vcamVirtualCamera1"))) {
                            is_hooked = true;
                            try {
                                mcamera1.setPreviewTexture(c1_fake_texture);
                            } catch (IOException e) {
                                XposedBridge.log("[VCAM] " + e);
                            }
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
                        if (!isInjectionActive()) {
                            return;
                        }
                        if (!hasAnyMedia()) {
                            maybeShowMissingMediaToast(lpparam.packageName);
                            return;
                        }
                        start_preview_camera = (Camera) param.thisObject;
                        cameraSessionActive = true;
                        InAppOverlayManager.onCameraSessionActive(true);
                        maybePostInAppNotification(toast_content, lpparam.packageName);
                        showInjectedHookToast("Camera1", mwidth, mhight);
                        startCamera1PreviewFeed(start_preview_camera);
                        if (param.thisObject != null && Boolean.TRUE.equals(
                                XposedHelpers.getAdditionalInstanceField(param.thisObject, "vcamVirtualCamera1"))) {
                            param.setResult(null);
                        }
                    }
                });

        XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader,
                "stopPreview", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        cleanupCamera1Session();
                        if (param.thisObject != null && Boolean.TRUE.equals(
                                XposedHelpers.getAdditionalInstanceField(param.thisObject, "vcamVirtualCamera1"))) {
                            param.setResult(null);
                        }
                    }
                });

        XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader,
                "release", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        cleanupCamera1Session();
                        if (param.thisObject != null && Boolean.TRUE.equals(
                                XposedHelpers.getAdditionalInstanceField(param.thisObject, "vcamVirtualCamera1"))) {
                            param.setResult(null);
                        }
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
                        if (param.thisObject != null && Boolean.TRUE.equals(
                                XposedHelpers.getAdditionalInstanceField(param.thisObject, "vcamVirtualCamera1"))) {
                            param.setResult(null);
                        }
                    }
                });

        XC_MethodHook previewCbHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args[0] != null) {
                    process_callback(param);
                }
                if (param.thisObject != null && Boolean.TRUE.equals(
                        XposedHelpers.getAdditionalInstanceField(param.thisObject, "vcamVirtualCamera1"))) {
                    XposedHelpers.setAdditionalInstanceField(param.thisObject, "vcamPreviewCb",
                            param.args.length > 0 ? param.args[0] : null);
                    param.setResult(null);
                }
            }
        };
        XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader,
                "setPreviewCallbackWithBuffer", Camera.PreviewCallback.class, previewCbHook);
        XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader,
                "setPreviewCallback", Camera.PreviewCallback.class, previewCbHook);
        XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader,
                "setOneShotPreviewCallback", Camera.PreviewCallback.class, previewCbHook);

        try {
            XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader,
                    "autoFocus", Camera.AutoFocusCallback.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (param.thisObject != null && Boolean.TRUE.equals(
                                    XposedHelpers.getAdditionalInstanceField(param.thisObject, "vcamVirtualCamera1"))) {
                                if (param.args.length > 0 && param.args[0] instanceof Camera.AutoFocusCallback) {
                                    final Camera.AutoFocusCallback cb = (Camera.AutoFocusCallback) param.args[0];
                                    final Camera cam = (Camera) param.thisObject;
                                    new Handler(Looper.getMainLooper()).post(() -> {
                                        try { cb.onAutoFocus(true, cam); } catch (Throwable ignored) {}
                                    });
                                }
                                param.setResult(null);
                            }
                        }
                    });
            XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader,
                    "cancelAutoFocus", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (param.thisObject != null && Boolean.TRUE.equals(
                                    XposedHelpers.getAdditionalInstanceField(param.thisObject, "vcamVirtualCamera1"))) {
                                param.setResult(null);
                            }
                        }
                    });
        } catch (Throwable ignored) {}

        XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader,
                "takePicture", Camera.ShutterCallback.class, Camera.PictureCallback.class,
                Camera.PictureCallback.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.thisObject != null && Boolean.TRUE.equals(
                                XposedHelpers.getAdditionalInstanceField(param.thisObject, "vcamVirtualCamera1"))) {
                            dispatchVirtualCamera1Picture((Camera) param.thisObject,
                                    (Camera.ShutterCallback) param.args[0],
                                    (Camera.PictureCallback) param.args[1],
                                    (Camera.PictureCallback) param.args[2]);
                            param.setResult(null);
                        }
                    }

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
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.thisObject != null && Boolean.TRUE.equals(
                                XposedHelpers.getAdditionalInstanceField(param.thisObject, "vcamVirtualCamera1"))) {
                            dispatchVirtualCamera1Picture((Camera) param.thisObject,
                                    (Camera.ShutterCallback) param.args[0],
                                    (Camera.PictureCallback) param.args[1],
                                    (Camera.PictureCallback) param.args[3]);
                            param.setResult(null);
                        }
                    }

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

    private void dispatchVirtualCamera1Picture(@NonNull final Camera cam,
                                               @Nullable final Camera.ShutterCallback shutter,
                                               @Nullable final Camera.PictureCallback rawCb,
                                               @Nullable final Camera.PictureCallback jpegCb) {
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                if (shutter != null) shutter.onShutter();
                Camera.Size sz = cam.getParameters() != null ? cam.getParameters().getPreviewSize() : null;
                int w = sz != null ? sz.width : 1280;
                int h = sz != null ? sz.height : 720;
                Bitmap bmp = obtainCurrentFrameBitmap(w, h);
                if (rawCb != null && bmp != null) {
                    rawCb.onPictureTaken(FrameTransformPipeline.bitmapToNv21(bmp), cam);
                }
                if (jpegCb != null) {
                    byte[] jpeg = buildTransformedJpegBytes(w, h);
                    if (jpeg != null) {
                        jpegCb.onPictureTaken(jpeg, cam);
                    }
                }
            } catch (Throwable t) {
                XposedBridge.log("[VCAM] dispatchVirtualCamera1Picture error: " + t);
            }
        });
    }

    private static volatile Runnable sVirtualCamera1PreviewTask;

    private void startCamera1PreviewFeed(@NonNull final Camera cam) {
        try {
            Camera.Parameters params = cam.getParameters();
            Camera.Size sz = params != null ? params.getPreviewSize() : null;
            final int w = sz != null ? sz.width : 1280;
            final int h = sz != null ? sz.height : 720;
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
            if (Boolean.TRUE.equals(XposedHelpers.getAdditionalInstanceField(cam, "vcamVirtualCamera1"))) {
                getOrCreateVirtualSinkSurface();
                if (sSinkDrainHandler != null) {
                    Runnable old = sVirtualCamera1PreviewTask;
                    if (old != null) sSinkDrainHandler.removeCallbacks(old);
                    final Handler mainHandler = new Handler(Looper.getMainLooper());
                    Runnable ticker = new Runnable() {
                        @Override
                        public void run() {
                            if (sVirtualCamera1PreviewTask != this || !cameraSessionActive) return;
                            Object cbObj = XposedHelpers.getAdditionalInstanceField(cam, "vcamPreviewCb");
                            if (cbObj instanceof Camera.PreviewCallback) {
                                final Camera.PreviewCallback cb = (Camera.PreviewCallback) cbObj;
                                byte[] buf = HOST_CALLBACK_BUFFERS.poll();
                                if (buf == null) {
                                    buf = new byte[w * h * 3 / 2];
                                }
                                final byte[] dispatchBuf = buf;
                                mainHandler.post(() -> {
                                    try { cb.onPreviewFrame(dispatchBuf, cam); } catch (Throwable ignored) {}
                                });
                            }
                            if (sSinkDrainHandler != null && sVirtualCamera1PreviewTask == this) {
                                sSinkDrainHandler.postDelayed(this, 33L);
                            }
                        }
                    };
                    sVirtualCamera1PreviewTask = ticker;
                    sSinkDrainHandler.postDelayed(ticker, 33L);
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
                File localVid = resolveBestVideoFile();
                if (streamUrl != null) {
                    mp.setDataSource(streamUrl);
                } else if (localVid != null) {
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
            File imgTarget = resolveBestImageFile(video_path + "1000.bmp");
            if (imgTarget == null) imgTarget = new File(video_path + "1000.bmp");
            feeder = new StaticBitmapFeeder(targetSurface, imgTarget, w, h,
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
        Runnable oldC1Task = sVirtualCamera1PreviewTask;
        sVirtualCamera1PreviewTask = null;
        if (oldC1Task != null && sSinkDrainHandler != null) {
            sSinkDrainHandler.removeCallbacks(oldC1Task);
        }
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
        hookMethodInHierarchy(preview_cb_class, "onPreviewFrame", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam paramd) throws Throwable {
                        Camera localcam = (Camera) paramd.args[1];
                        if (!isInjectionActive()) {
                            return;
                        }
                        if (!hasAnyMedia()) {
                            maybeShowMissingMediaToast(currentHostPackage);
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
                            showInjectedHookToast("Camera1", mwidth, mhight);

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
                                File localVid = resolveBestVideoFile();
                                if (streamUrl != null) {
                                    hw_decode_obj.decodeStream(streamUrl);
                                } else if (localVid != null) {
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
                }, byte[].class, Camera.class);
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
        hookMethodInHierarchy(callback, "onPictureTaken", new XC_MethodHook() {
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
                }, byte[].class, Camera.class);
    }

    private void process_a_shot_YUV(XC_MethodHook.MethodHookParam param) {
        Class<?> callback = param.args[1].getClass();
        if (!HOOKED_PICTURE_CALLBACKS.add(callback)) {
            return;
        }
        hookMethodInHierarchy(callback, "onPictureTaken", new XC_MethodHook() {
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
                }, byte[].class, Camera.class);
    }

    @Nullable
    private static Object allocateUnsafeInstance(@NonNull Class<?> clazz) {
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            java.lang.reflect.Field f = unsafeClass.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            Object unsafe = f.get(null);
            java.lang.reflect.Method alloc = unsafeClass.getMethod("allocateInstance", Class.class);
            return alloc.invoke(unsafe, clazz);
        } catch (Throwable t) {
            XposedBridge.log("[VCAM] allocateUnsafeInstance(" + clazz.getName() + ") failed: " + t);
            return null;
        }
    }

    @Nullable
    private static CameraCharacteristics createSyntheticCameraCharacteristics(@NonNull String cameraId) {
        try {
            CameraCharacteristics cc = (CameraCharacteristics) allocateUnsafeInstance(CameraCharacteristics.class);
            if (cc != null) {
                XposedHelpers.setAdditionalInstanceField(cc, "vcamSynthetic", Boolean.TRUE);
                XposedHelpers.setAdditionalInstanceField(cc, "vcamCameraId", cameraId);
            }
            return cc;
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Nullable
    private static Object createSyntheticStreamConfigMap() {
        try {
            Class<?> scmClass = Class.forName("android.hardware.camera2.params.StreamConfigurationMap");
            Object scm = allocateUnsafeInstance(scmClass);
            if (scm != null) {
                XposedHelpers.setAdditionalInstanceField(scm, "vcamSynthetic", Boolean.TRUE);
            }
            return scm;
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Nullable
    private static Object getSyntheticCharacteristicValue(@Nullable String keyName, boolean isFrontId) {
        if (keyName == null) return null;
        if (CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP.getName().equals(keyName)) {
            return createSyntheticStreamConfigMap();
        }
        if (CameraCharacteristics.SENSOR_ORIENTATION.getName().equals(keyName)) {
            return isFrontId ? 270 : 90;
        }
        if (CameraCharacteristics.FLASH_INFO_AVAILABLE.getName().equals(keyName)) {
            return !isFrontId;
        }
        if (CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE.getName().equals(keyName)
                || CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE.getName().equals(keyName)) {
            return new Rect(0, 0, 4032, 3024);
        }
        if (CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE.getName().equals(keyName)) {
            return new android.util.Size(4032, 3024);
        }
        if (CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE.getName().equals(keyName)) {
            return new android.util.SizeF(6.40f, 4.80f);
        }
        if (CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS.getName().equals(keyName)) {
            return new float[]{isFrontId ? 2.75f : 4.25f};
        }
        if (CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES.getName().equals(keyName)) {
            return new float[]{isFrontId ? 2.0f : 1.8f};
        }
        if (CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE.getName().equals(keyName)) {
            return isFrontId ? 0.0f : 10.0f;
        }
        if (CameraCharacteristics.LENS_INFO_HYPERFOCAL_DISTANCE.getName().equals(keyName)) {
            return 0.25f;
        }
        if (CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM.getName().equals(keyName)) {
            return 8.0f;
        }
        if (CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES.getName().equals(keyName)) {
            return new android.util.Range[]{
                    new android.util.Range<>(15, 30),
                    new android.util.Range<>(30, 30)
            };
        }
        if (CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES.getName().equals(keyName)) {
            return new int[]{CameraMetadata.CONTROL_AE_MODE_OFF, CameraMetadata.CONTROL_AE_MODE_ON};
        }
        if (CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES.getName().equals(keyName)) {
            return new int[]{
                    CameraMetadata.CONTROL_AF_MODE_OFF,
                    CameraMetadata.CONTROL_AF_MODE_AUTO,
                    CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
                    CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO
            };
        }
        if (CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES.getName().equals(keyName)) {
            return new int[]{CameraMetadata.CONTROL_AWB_MODE_OFF, CameraMetadata.CONTROL_AWB_MODE_AUTO};
        }
        if (CameraCharacteristics.CONTROL_AVAILABLE_MODES.getName().equals(keyName)) {
            return new int[]{CameraMetadata.CONTROL_MODE_OFF, CameraMetadata.CONTROL_MODE_AUTO};
        }
        if (CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES.getName().equals(keyName)) {
            return new int[]{CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE};
        }
        if (CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE.getName().equals(keyName)) {
            return new android.util.Range<>(-12, 12);
        }
        if (CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP.getName().equals(keyName)) {
            return new android.util.Rational(1, 3);
        }
        if (CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE.getName().equals(keyName)
                || CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE.getName().equals(keyName)) {
            return Boolean.TRUE;
        }
        if (CameraCharacteristics.CONTROL_MAX_REGIONS_AE.getName().equals(keyName)
                || CameraCharacteristics.CONTROL_MAX_REGIONS_AF.getName().equals(keyName)) {
            return 1;
        }
        if (CameraCharacteristics.CONTROL_MAX_REGIONS_AWB.getName().equals(keyName)) {
            return 0;
        }
        if (CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES.getName().equals(keyName)) {
            return new int[]{CameraMetadata.STATISTICS_FACE_DETECT_MODE_OFF};
        }
        if (CameraCharacteristics.STATISTICS_INFO_MAX_FACE_COUNT.getName().equals(keyName)) {
            return 0;
        }
        if (CameraCharacteristics.SCALER_CROPPING_TYPE.getName().equals(keyName)) {
            return CameraMetadata.SCALER_CROPPING_TYPE_CENTER_ONLY;
        }
        if (CameraCharacteristics.SYNC_MAX_LATENCY.getName().equals(keyName)) {
            return CameraMetadata.SYNC_MAX_LATENCY_PER_FRAME_CONTROL;
        }
        if (CameraCharacteristics.REQUEST_PIPELINE_MAX_DEPTH.getName().equals(keyName)) {
            return (byte) 4;
        }
        if (CameraCharacteristics.REQUEST_PARTIAL_RESULT_COUNT.getName().equals(keyName)) {
            return 1;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                && CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE.getName().equals(keyName)) {
            return new android.util.Range<>(1.0f, 8.0f);
        }
        return null;
    }

    private static final android.util.Size[] VCAM_DEFAULT_SIZES = new android.util.Size[]{
            new android.util.Size(1920, 1080),
            new android.util.Size(1280, 720),
            new android.util.Size(960, 540),
            new android.util.Size(640, 480)
    };

    private static final Map<CameraDevice.StateCallback, CameraDevice> VIRTUAL_OPENED_DEVICES =
            Collections.synchronizedMap(new WeakHashMap<>());

    @Nullable
    private CameraDevice createVirtualCameraDevice(@NonNull String cameraId) {
        try {
            Class<?> deviceImplClass = Class.forName("android.hardware.camera2.impl.CameraDeviceImpl");
            CameraDevice dev = (CameraDevice) allocateUnsafeInstance(deviceImplClass);
            if (dev != null) {
                XposedHelpers.setAdditionalInstanceField(dev, "vcamVirtualDevice", Boolean.TRUE);
                XposedHelpers.setAdditionalInstanceField(dev, "vcamCameraId", cameraId);
                hookCameraDeviceInstance(deviceImplClass);
            }
            return dev;
        } catch (Throwable t) {
            XposedBridge.log("[VCAM] createVirtualCameraDevice failed: " + t);
            return null;
        }
    }

    @Nullable
    private CameraCaptureSession createVirtualCaptureSession(@Nullable CameraDevice device) {
        try {
            Class<?> sessionImplClass = Class.forName("android.hardware.camera2.impl.CameraCaptureSessionImpl");
            CameraCaptureSession sess = (CameraCaptureSession) allocateUnsafeInstance(sessionImplClass);
            if (sess != null) {
                XposedHelpers.setAdditionalInstanceField(sess, "vcamVirtualSession", Boolean.TRUE);
                if (device != null) {
                    XposedHelpers.setAdditionalInstanceField(sess, "vcamDevice", device);
                }
                hookCaptureSessionInstance(sessionImplClass);
            }
            return sess;
        } catch (Throwable t) {
            XposedBridge.log("[VCAM] createVirtualCaptureSession failed: " + t);
            return null;
        }
    }

    @Nullable
    private CaptureRequest.Builder createVirtualCaptureRequestBuilder(int templateType) {
        try {
            CaptureRequest.Builder builder = (CaptureRequest.Builder) allocateUnsafeInstance(CaptureRequest.Builder.class);
            if (builder != null) {
                XposedHelpers.setAdditionalInstanceField(builder, "vcamVirtualBuilder", Boolean.TRUE);
                XposedHelpers.setAdditionalInstanceField(builder, "vcamTemplate", templateType);
                XposedHelpers.setAdditionalInstanceField(builder, "vcamSettings", new ConcurrentHashMap<Object, Object>());
                process_camera2_CaptureRequest_builder(CaptureRequest.Builder.class);
            }
            return builder;
        } catch (Throwable t) {
            XposedBridge.log("[VCAM] createVirtualCaptureRequestBuilder failed: " + t);
            return null;
        }
    }

    @Nullable
    private static TotalCaptureResult createVirtualTotalCaptureResult(@Nullable CaptureRequest request, long frameNum) {
        try {
            TotalCaptureResult res = (TotalCaptureResult) allocateUnsafeInstance(TotalCaptureResult.class);
            if (res != null) {
                XposedHelpers.setAdditionalInstanceField(res, "vcamVirtualResult", Boolean.TRUE);
                XposedHelpers.setAdditionalInstanceField(res, "vcamFrameNumber", frameNum);
                if (request != null) {
                    XposedHelpers.setAdditionalInstanceField(res, "vcamRequest", request);
                }
            }
            return res;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void dispatchOnCameraCallbackThread(@Nullable Object[] args, @NonNull Runnable task) {
        if (args != null) {
            for (Object arg : args) {
                if (arg instanceof Handler) {
                    ((Handler) arg).post(task);
                    return;
                }
                if (arg instanceof java.util.concurrent.Executor) {
                    try {
                        ((java.util.concurrent.Executor) arg).execute(task);
                        return;
                    } catch (Throwable ignored) {}
                }
            }
        }
        new Handler(Looper.getMainLooper()).post(task);
    }

    // ---------------------------------------------------------------------
    // Camera2 Hooks (No-Escape StateCallback, Static Framework Hooks & Dynamic Feeders)
    // ---------------------------------------------------------------------
    private void installCamera2Hooks(final XC_LoadPackage.LoadPackageParam lpparam) {
        // Ensure CameraManager.getCameraIdList() never throws ArrayIndexOutOfBoundsException
        // (crDroid CameraManagerGlobal.extractCameraIdListLocked bug when cameraserver is down)
        // and always exposes at least {"0", "1"} ("0" = back, "1" = front).
        XC_MethodHook cameraIdListHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (param.hasThrowable()) {
                    XposedBridge.log("[VCAM] Recovered getCameraIdList throwable (" + param.getThrowable() + ") -> {0, 1}");
                    param.setThrowable(null);
                    param.setResult(new String[]{"0", "1"});
                    return;
                }
                Object res = param.getResult();
                if (!(res instanceof String[]) || ((String[]) res).length == 0) {
                    param.setResult(new String[]{"0", "1"});
                }
            }
        };
        try {
            XposedBridge.hookAllMethods(CameraManager.class, "getCameraIdList", cameraIdListHook);
        } catch (Throwable ignored) {}
        try {
            XposedBridge.hookAllMethods(CameraManager.class, "getCameraIdListNoLazy", cameraIdListHook);
        } catch (Throwable ignored) {}
        try {
            Class<?> cmGlobal = Class.forName("android.hardware.camera2.CameraManager$CameraManagerGlobal");
            XposedBridge.hookAllMethods(cmGlobal, "getCameraIdList", cameraIdListHook);
            XposedBridge.hookAllMethods(cmGlobal, "getCameraIdListNoLazy", cameraIdListHook);
            XposedBridge.hookAllMethods(cmGlobal, "extractCameraIdListLocked", cameraIdListHook);
        } catch (Throwable ignored) {}

        // Ensure CameraX / Persona SDK AvailabilityCallback always receives onCameraAvailable("0") and ("1")
        // even when cameraserver is unavailable and CameraManagerGlobal.mDeviceStatus is empty.
        try {
            XposedBridge.hookAllMethods(CameraManager.class, "registerAvailabilityCallback", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.hasThrowable()) {
                        param.setThrowable(null);
                    }
                    if (param.args == null) return;
                    CameraManager.AvailabilityCallback availCb = null;
                    for (Object arg : param.args) {
                        if (arg instanceof CameraManager.AvailabilityCallback) {
                            availCb = (CameraManager.AvailabilityCallback) arg;
                            break;
                        }
                    }
                    if (availCb == null) return;
                    final CameraManager.AvailabilityCallback finalCb = availCb;
                    dispatchOnCameraCallbackThread(param.args, () -> {
                        try {
                            finalCb.onCameraAvailable("0");
                            finalCb.onCameraAvailable("1");
                        } catch (Throwable ignored) {}
                    });
                }
            });
        } catch (Throwable ignored) {}

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                XposedBridge.hookAllMethods(CameraManager.class, "getConcurrentCameraIds", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (param.hasThrowable() || param.getResult() == null) {
                            param.setThrowable(null);
                            param.setResult(Collections.emptySet());
                        }
                    }
                });
                XposedBridge.hookAllMethods(CameraManager.class, "isConcurrentSessionConfigurationSupported", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (param.hasThrowable()) {
                            param.setThrowable(null);
                            param.setResult(Boolean.FALSE);
                        }
                    }
                });
            } catch (Throwable ignored) {}
        }

        // Ensure CameraManager.getCameraCharacteristics(String) returns a valid synthetic
        // CameraCharacteristics when cameraserver is down or throws CameraAccessException
        try {
            XposedBridge.hookAllMethods(CameraManager.class, "getCameraCharacteristics", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    String camId = (param.args != null && param.args.length > 0 && param.args[0] instanceof String)
                            ? (String) param.args[0] : "0";
                    if (param.hasThrowable() || param.getResult() == null) {
                        CameraCharacteristics syn = createSyntheticCameraCharacteristics(camId);
                        if (syn != null) {
                            param.setThrowable(null);
                            param.setResult(syn);
                        }
                    } else if (param.getResult() instanceof CameraCharacteristics) {
                        XposedHelpers.setAdditionalInstanceField(param.getResult(), "vcamCameraId", camId);
                    }
                }
            });
        } catch (Throwable ignored) {}

        // Hook StreamConfigurationMap so synthetic or fallback maps always report standard formats & sizes
        try {
            Class<?> scmClass = Class.forName("android.hardware.camera2.params.StreamConfigurationMap");
            XposedBridge.hookAllMethods(scmClass, "getOutputSizes", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.thisObject != null && Boolean.TRUE.equals(
                            XposedHelpers.getAdditionalInstanceField(param.thisObject, "vcamSynthetic"))) {
                        param.setResult(VCAM_DEFAULT_SIZES.clone());
                    }
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.hasThrowable() || param.getResult() == null
                            || (param.getResult() instanceof android.util.Size[]
                            && ((android.util.Size[]) param.getResult()).length == 0)) {
                        param.setThrowable(null);
                        param.setResult(VCAM_DEFAULT_SIZES.clone());
                    }
                }
            });
            XposedBridge.hookAllMethods(scmClass, "getOutputFormats", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.thisObject != null && Boolean.TRUE.equals(
                            XposedHelpers.getAdditionalInstanceField(param.thisObject, "vcamSynthetic"))) {
                        param.setResult(new int[]{ImageFormat.YUV_420_888, ImageFormat.JPEG, ImageFormat.NV21, 0x22});
                    }
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.hasThrowable() || param.getResult() == null) {
                        param.setThrowable(null);
                        param.setResult(new int[]{ImageFormat.YUV_420_888, ImageFormat.JPEG, ImageFormat.NV21, 0x22});
                    }
                }
            });
            XposedBridge.hookAllMethods(scmClass, "isOutputSupportedFor", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.thisObject != null && Boolean.TRUE.equals(
                            XposedHelpers.getAdditionalInstanceField(param.thisObject, "vcamSynthetic"))) {
                        param.setResult(Boolean.TRUE);
                    }
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.hasThrowable()) {
                        param.setThrowable(null);
                        param.setResult(Boolean.TRUE);
                    }
                }
            });
            XposedBridge.hookAllMethods(scmClass, "getOutputMinFrameDuration", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.thisObject != null && Boolean.TRUE.equals(
                            XposedHelpers.getAdditionalInstanceField(param.thisObject, "vcamSynthetic"))) {
                        param.setResult(33_333_333L);
                    }
                }
            });
            XposedBridge.hookAllMethods(scmClass, "getOutputStallDuration", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.thisObject != null && Boolean.TRUE.equals(
                            XposedHelpers.getAdditionalInstanceField(param.thisObject, "vcamSynthetic"))) {
                        param.setResult(0L);
                    }
                }
            });
            XposedBridge.hookAllMethods(scmClass, "getHighResolutionOutputSizes", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.thisObject != null && Boolean.TRUE.equals(
                            XposedHelpers.getAdditionalInstanceField(param.thisObject, "vcamSynthetic"))) {
                        param.setResult(null);
                    }
                }
            });
            XposedBridge.hookAllMethods(scmClass, "getHighSpeedVideoSizes", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.thisObject != null && Boolean.TRUE.equals(
                            XposedHelpers.getAdditionalInstanceField(param.thisObject, "vcamSynthetic"))) {
                        param.setResult(new android.util.Size[0]);
                    }
                }
            });
            XposedBridge.hookAllMethods(scmClass, "getHighSpeedVideoFpsRanges", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.thisObject != null && Boolean.TRUE.equals(
                            XposedHelpers.getAdditionalInstanceField(param.thisObject, "vcamSynthetic"))) {
                        param.setResult(new android.util.Range[0]);
                    }
                }
            });
        } catch (Throwable ignored) {}

        XC_MethodHook openCameraHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                // Extract Context directly from CameraManager.mContext if earlyInitHook hasn't fired yet
                if (!isValidHostContext(toast_content) && param.thisObject != null) {
                    try {
                        Object cmCtx = XposedHelpers.getObjectField(param.thisObject, "mContext");
                        if (cmCtx instanceof Context) {
                            Context appCtx = ((Context) cmCtx).getApplicationContext();
                            Context candidate = appCtx != null ? appCtx : (Context) cmCtx;
                            if (isValidHostContext(candidate)) {
                                toast_content = candidate;
                                lastStagedKey = "";
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
                detectFacingCamera2(param);
                ensureStagedNow(lpparam.packageName);
                CameraDevice.StateCallback stateCb = extractStateCallback(param.args);
                if (stateCb == null) {
                    return;
                }
                // Avoid re-wrapping our own proxy when openCamera chains into openCameraForUid -> openCameraDeviceUserAsync
                if (stateCb instanceof VcamStateCallbackProxy) {
                    return;
                }
                // Detect Sumsub CameraTrace dummy stack-probe (com.sumsub.sns.internal.util.camera.g on "cam-monitor-thread"):
                // It opens the camera solely to inspect Throwable/Thread/VMStack frames in onOpened and immediately closes it.
                // Do not wrap or hook this dummy probe so its stack is 100% stock and its delayed onClosed never tears down CameraX!
                if (isDummyTraceProbeCallback(stateCb, param.args)) {
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
                try {
                    process_camera2_init(c2_state_callback);
                } catch (Throwable t) {
                    XposedBridge.log("[VCAM] process_camera2_init fallback to proxy: " + t);
                }
                wrapAndReplaceStateCallback(param.args, stateCb);
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!param.hasThrowable()) {
                    return;
                }
                if (!isInjectionActive() || !hasAnyMedia()) {
                    return;
                }
                CameraDevice.StateCallback stateCb = extractStateCallback(param.args);
                if (stateCb == null) {
                    return;
                }
                // Suppress CameraAccessException / RuntimeException when cameraserver is stopped
                // and synthesize a virtual CameraDeviceImpl so Camera2 / CameraX opens seamlessly.
                Throwable origErr = param.getThrowable();
                param.setThrowable(null);
                param.setResult(null);

                CameraDevice.StateCallback rootCb = (stateCb instanceof VcamStateCallbackProxy)
                        ? ((VcamStateCallbackProxy) stateCb).originalCb : stateCb;
                synchronized (VIRTUAL_OPENED_DEVICES) {
                    if (VIRTUAL_OPENED_DEVICES.containsKey(rootCb)) {
                        return;
                    }
                    String camId = (param.args != null && param.args.length > 0 && param.args[0] instanceof String)
                            ? (String) param.args[0] : "0";
                    CameraDevice virtualDevice = createVirtualCameraDevice(camId);
                    if (virtualDevice == null) {
                        param.setThrowable(origErr);
                        return;
                    }
                    VIRTUAL_OPENED_DEVICES.put(rootCb, virtualDevice);
                    XposedBridge.log("[VCAM] Synthesized virtual CameraDevice (id=" + camId + ") after openCamera error: " + origErr);
                    final CameraDevice.StateCallback dispatchTarget = stateCb;
                    Runnable openTask = () -> {
                        try {
                            dispatchTarget.onOpened(virtualDevice);
                        } catch (Throwable t) {
                            XposedBridge.log("[VCAM] virtualDevice onOpened error: " + t);
                        }
                    };
                    dispatchOnCameraCallbackThread(param.args, openTask);
                }
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
        try {
            XposedHelpers.findAndHookMethod(CameraCharacteristics.class, "get",
                    CameraCharacteristics.Key.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (param.thisObject != null
                                    && Boolean.TRUE.equals(XposedHelpers.getAdditionalInstanceField(
                                            param.thisObject, "vcamSynthetic"))) {
                                param.setResult(null);
                            }
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (param.args.length == 0 || param.args[0] == null) {
                                return;
                            }
                            boolean isSynthetic = param.thisObject != null
                                    && Boolean.TRUE.equals(XposedHelpers.getAdditionalInstanceField(
                                            param.thisObject, "vcamSynthetic"));
                            if (!isSynthetic && !isMetadataPatchActive()) {
                                return;
                            }
                            CameraCharacteristics.Key<?> key = (CameraCharacteristics.Key<?>) param.args[0];
                            String name = key.getName();
                            Object res = param.getResult();
                            Object camIdObj = param.thisObject != null
                                    ? XposedHelpers.getAdditionalInstanceField(param.thisObject, "vcamCameraId")
                                    : null;
                            String camId = camIdObj instanceof String ? (String) camIdObj : "0";
                            boolean isFrontId = "1".equals(camId);

                            if (CameraCharacteristics.LENS_FACING.getName().equals(name)) {
                                if (res == null) {
                                    param.setResult(isFrontId
                                            ? CameraMetadata.LENS_FACING_FRONT
                                            : CameraMetadata.LENS_FACING_BACK);
                                } else if (res instanceof Integer && ((Integer) res) == CameraMetadata.LENS_FACING_EXTERNAL) {
                                    param.setResult(MediaMappings.FACING_FRONT.equals(currentFacing)
                                            ? CameraMetadata.LENS_FACING_FRONT
                                            : CameraMetadata.LENS_FACING_BACK);
                                }
                            } else if (CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE.getName().equals(name)) {
                                param.setResult(CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME);
                            } else if (CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL.getName().equals(name)) {
                                if (res == null) {
                                    param.setResult(CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL);
                                } else if (res instanceof Integer) {
                                    int lvl = (Integer) res;
                                    if (lvl == CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY
                                            || lvl == CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL) {
                                        param.setResult(CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL);
                                    }
                                }
                            } else if (res == null) {
                                Object fallback = getSyntheticCharacteristicValue(name, isFrontId);
                                if (fallback != null) {
                                    param.setResult(fallback);
                                }
                            }
                        }
                    });
        } catch (Throwable ignored) {
        }
        try {
            XposedBridge.hookAllMethods(CameraCharacteristics.class, "getKeys", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.thisObject != null
                            && Boolean.TRUE.equals(XposedHelpers.getAdditionalInstanceField(
                                    param.thisObject, "vcamSynthetic"))) {
                        param.setResult(Collections.emptyList());
                    }
                }
            });
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                XposedBridge.hookAllMethods(CameraCharacteristics.class, "getPhysicalCameraIds", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.thisObject != null
                                && Boolean.TRUE.equals(XposedHelpers.getAdditionalInstanceField(
                                        param.thisObject, "vcamSynthetic"))) {
                            param.setResult(Collections.emptySet());
                        }
                    }
                });
                XposedBridge.hookAllMethods(CameraCharacteristics.class, "getAvailableSessionKeys", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.thisObject != null
                                && Boolean.TRUE.equals(XposedHelpers.getAdditionalInstanceField(
                                        param.thisObject, "vcamSynthetic"))) {
                            param.setResult(Collections.emptyList());
                        }
                    }
                });
            }
        } catch (Throwable ignored) {}

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

    private static boolean isDummyTraceProbeCallback(@NonNull CameraDevice.StateCallback stateCb, @Nullable Object[] args) {
        String cbName = stateCb.getClass().getName();
        if (cbName.startsWith("com.sumsub.sns.internal.util.camera.")) {
            return true;
        }
        if (args != null) {
            for (Object arg : args) {
                if (arg instanceof Handler) {
                    Looper looper = ((Handler) arg).getLooper();
                    if (looper != null && looper.getThread() != null) {
                        String tName = looper.getThread().getName();
                        if ("cam-monitor-thread".equals(tName)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private synchronized void onCameraDeviceOpened(@Nullable CameraDevice camera) {
        if (camera != null) {
            if (Boolean.TRUE.equals(INITIALIZED_CAMERA_DEVICES.get(camera))) {
                // Already initialized for this exact CameraDevice open lifecycle
                return;
            }
            INITIALIZED_CAMERA_DEVICES.clear();
            INITIALIZED_CAMERA_DEVICES.put(camera, Boolean.TRUE);
        }
        stopAllCamera2Feeders();
        need_recreate = true;
        getOrCreateVirtualSinkSurface();
        NdkHookEngine.syncSensorProfile(activeLensProfile, currentFacing);
        ImuMotionCorrelator.getInstance().start(toast_content != null ? toast_content : resolveHostContext());
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
            Boolean wasTracked = INITIALIZED_CAMERA_DEVICES.remove(camera);
            if (wasTracked == null || !INITIALIZED_CAMERA_DEVICES.isEmpty()) {
                // Ignore close events from untracked probe devices or superseded sessions
                return;
            }
        }
        cameraSessionActive = false;
        ImuMotionCorrelator.getInstance().stop();
        InAppOverlayManager.onCameraSessionActive(false);
        VirtualAudioFeeder.stopAllSessions();
        stopAllCamera2Feeders();
    }

    private final class VcamStateCallbackProxy extends CameraDevice.StateCallback {
        private final CameraDevice.StateCallback originalCb;

        VcamStateCallbackProxy(@NonNull CameraDevice.StateCallback originalCb) {
            this.originalCb = originalCb;
        }

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
    }

    private void wrapAndReplaceStateCallback(Object[] args, @NonNull final CameraDevice.StateCallback originalCb) {
        if (args == null || originalCb instanceof VcamStateCallbackProxy) return;
        for (int i = 0; i < args.length; i++) {
            if (args[i] == originalCb) {
                final CameraDevice.StateCallback delegate = new VcamStateCallbackProxy(originalCb);
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
    private static final Set<SurfaceTexture> ATTACHED_SINK_TEXTURES =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    private static SurfaceTexture createManagedSinkSurfaceTexture() {
        final SurfaceTexture st;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Create in detached mode so attachToGLContext(texId) succeeds on sSinkDrainThread
            st = new SurfaceTexture(false);
        } else {
            int texId = 32 + sRandom.nextInt(200);
            st = new SurfaceTexture(texId);
            try {
                st.detachFromGLContext();
            } catch (Throwable ignored) {
            }
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
            final int texId = 32 + sRandom.nextInt(200);
            SurfaceTexture.OnFrameAvailableListener listener = surfaceTexture -> {
                try {
                    if (sEglDisplay == EGL14.EGL_NO_DISPLAY || sEglContext == EGL14.EGL_NO_CONTEXT) {
                        initSinkEglContextOnDrainThread();
                    }
                    if (sEglDisplay != EGL14.EGL_NO_DISPLAY && sEglContext != EGL14.EGL_NO_CONTEXT) {
                        EGL14.eglMakeCurrent(sEglDisplay, sEglPbuffer, sEglPbuffer, sEglContext);
                        if (ATTACHED_SINK_TEXTURES.add(surfaceTexture)) {
                            try {
                                surfaceTexture.attachToGLContext(texId);
                            } catch (Throwable ignored) {
                            }
                        }
                        surfaceTexture.updateTexImage();
                    } else {
                        surfaceTexture.releaseTexImage();
                    }
                } catch (Throwable t) {
                    try {
                        surfaceTexture.attachToGLContext(texId);
                        surfaceTexture.updateTexImage();
                    } catch (Throwable ignored) {
                    }
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
        final Surface oldSurface = c2_virtual_surface;
        final SurfaceTexture oldSt = c2_virtual_surfaceTexture;
        c2_virtual_surfaceTexture = createManagedSinkSurfaceTexture();
        c2_virtual_surface = new Surface(c2_virtual_surfaceTexture);
        need_recreate = false;
        NdkHookEngine.updateVirtualSinkSurface(c2_virtual_surface);
        if (oldSurface != null || oldSt != null) {
            Runnable releaseOld = () -> {
                if (oldSurface != null) {
                    try {
                        oldSurface.release();
                    } catch (Throwable ignored) {
                    }
                }
                if (oldSt != null) {
                    ATTACHED_SINK_TEXTURES.remove(oldSt);
                    try {
                        oldSt.release();
                    } catch (Throwable ignored) {
                    }
                }
            };
            if (sSinkDrainHandler != null) {
                sSinkDrainHandler.postDelayed(releaseOld, 3000L);
            } else {
                releaseOld.run();
            }
        }
        return c2_virtual_surface;
    }

    private void process_camera2_init(Class<?> hooked_class) {
        if (hooked_class == null || VcamStateCallbackProxy.class.isAssignableFrom(hooked_class)
                || !HOOKED_STATE_CALLBACKS.add(hooked_class)) {
            return;
        }
        hookMethodInHierarchy(hooked_class, "onOpened", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (!isInjectionActive() || !hasAnyMedia()) {
                    return;
                }
                CameraDevice cam = param.args.length > 0 && param.args[0] instanceof CameraDevice
                        ? (CameraDevice) param.args[0] : null;
                onCameraDeviceOpened(cam);
            }
        }, CameraDevice.class);

        XC_MethodHook cleanupHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                CameraDevice cam = (param.args != null && param.args.length > 0 && param.args[0] instanceof CameraDevice)
                        ? (CameraDevice) param.args[0] : null;
                onCameraDeviceClosed(cam);
            }
        };

        hookMethodInHierarchy(hooked_class, "onDisconnected", cleanupHook, CameraDevice.class);
        hookMethodInHierarchy(hooked_class, "onClosed", cleanupHook, CameraDevice.class);
        hookMethodInHierarchy(hooked_class, "onError", cleanupHook, CameraDevice.class, int.class);
    }

    private static boolean isVirtualCameraDevice(@Nullable Object obj) {
        return obj != null && Boolean.TRUE.equals(XposedHelpers.getAdditionalInstanceField(obj, "vcamVirtualDevice"));
    }

    private static boolean isVirtualCaptureSession(@Nullable Object obj) {
        return obj != null && Boolean.TRUE.equals(XposedHelpers.getAdditionalInstanceField(obj, "vcamVirtualSession"));
    }

    private static boolean isVirtualBuilder(@Nullable Object obj) {
        return obj != null && Boolean.TRUE.equals(XposedHelpers.getAdditionalInstanceField(obj, "vcamVirtualBuilder"));
    }

    private void dispatchVirtualCaptureSession(@NonNull Object deviceObj,
                                               @Nullable CameraCaptureSession.StateCallback stateCb,
                                               @Nullable Object[] args) {
        if (stateCb == null) return;
        process_camera2_capture_session_callback(stateCb);
        CameraDevice cam = deviceObj instanceof CameraDevice ? (CameraDevice) deviceObj : null;
        final CameraCaptureSession virtualSession = createVirtualCaptureSession(cam);
        if (virtualSession == null) return;
        Runnable task = () -> {
            try {
                ensureAllRegisteredFeedersRunning();
                stateCb.onConfigured(virtualSession);
                try {
                    stateCb.onReady(virtualSession);
                } catch (Throwable ignored) {}
            } catch (Throwable t) {
                XposedBridge.log("[VCAM] virtualSession onConfigured error: " + t);
            }
        };
        dispatchOnCameraCallbackThread(args, task);
    }

    private void hookCameraDeviceInstance(Class<?> deviceClass) {
        if (deviceClass == null || !HOOKED_CAMERA_DEVICES.add(deviceClass)) {
            return;
        }

        try {
            XposedHelpers.findAndHookMethod(deviceClass, "getId", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (isVirtualCameraDevice(param.thisObject)) {
                        Object id = XposedHelpers.getAdditionalInstanceField(param.thisObject, "vcamCameraId");
                        param.setResult(id instanceof String ? (String) id : "0");
                    }
                }
            });
        } catch (Throwable ignored) {}

        // Hook both 1-arg createCaptureRequest(int) and 2-arg createCaptureRequest(int, Set)
        XposedBridge.hookAllMethods(deviceClass, "createCaptureRequest", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (isVirtualCameraDevice(param.thisObject)) {
                    int tpl = (param.args != null && param.args.length > 0 && param.args[0] instanceof Integer)
                            ? (Integer) param.args[0] : CameraDevice.TEMPLATE_PREVIEW;
                    CaptureRequest.Builder vb = createVirtualCaptureRequestBuilder(tpl);
                    if (vb != null) {
                        c2_builder = vb;
                        param.setResult(vb);
                    }
                }
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!isInjectionActive() || !hasAnyMedia()) {
                    return;
                }
                if (param.hasThrowable()) {
                    int tpl = (param.args != null && param.args.length > 0 && param.args[0] instanceof Integer)
                            ? (Integer) param.args[0] : CameraDevice.TEMPLATE_PREVIEW;
                    CaptureRequest.Builder vb = createVirtualCaptureRequestBuilder(tpl);
                    if (vb != null) {
                        param.setThrowable(null);
                        param.setResult(vb);
                    }
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
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (isVirtualCameraDevice(param.thisObject)) {
                            CaptureRequest.Builder vb = createVirtualCaptureRequestBuilder(CameraDevice.TEMPLATE_STILL_CAPTURE);
                            if (vb != null) {
                                c2_builder = vb;
                                param.setResult(vb);
                            }
                        }
                    }

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
                        if (isVirtualCameraDevice(param.thisObject) || (isInjectionActive() && hasAnyMedia())) {
                            param.setResult(Boolean.TRUE);
                        }
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
                    if (isVirtualCameraDevice(param.thisObject)) {
                        param.setResult(null);
                    }
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
                                if (paramd.args[1] instanceof CameraCaptureSession.StateCallback) {
                                    CameraCaptureSession.StateCallback cb = (CameraCaptureSession.StateCallback) paramd.args[1];
                                    process_camera2_capture_session_callback(cb);
                                    if (isVirtualCameraDevice(paramd.thisObject)) {
                                        dispatchVirtualCaptureSession(paramd.thisObject, cb, paramd.args);
                                        paramd.setResult(null);
                                    }
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
                                    if (paramd.args[1] instanceof CameraCaptureSession.StateCallback) {
                                        CameraCaptureSession.StateCallback cb = (CameraCaptureSession.StateCallback) paramd.args[1];
                                        process_camera2_capture_session_callback(cb);
                                        if (isVirtualCameraDevice(paramd.thisObject)) {
                                            dispatchVirtualCaptureSession(paramd.thisObject, cb, paramd.args);
                                            paramd.setResult(null);
                                        }
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
                                    if (paramd.args[2] instanceof CameraCaptureSession.StateCallback) {
                                        CameraCaptureSession.StateCallback cb = (CameraCaptureSession.StateCallback) paramd.args[2];
                                        process_camera2_capture_session_callback(cb);
                                        if (isVirtualCameraDevice(paramd.thisObject)) {
                                            dispatchVirtualCaptureSession(paramd.thisObject, cb, paramd.args);
                                            paramd.setResult(null);
                                        }
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
                                    if (param.args[1] instanceof CameraCaptureSession.StateCallback) {
                                        CameraCaptureSession.StateCallback cb = (CameraCaptureSession.StateCallback) param.args[1];
                                        process_camera2_capture_session_callback(cb);
                                        if (isVirtualCameraDevice(param.thisObject)) {
                                            dispatchVirtualCaptureSession(param.thisObject, cb, param.args);
                                            param.setResult(null);
                                        }
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
                                    if (param.args[2] instanceof CameraCaptureSession.StateCallback) {
                                        CameraCaptureSession.StateCallback cb = (CameraCaptureSession.StateCallback) param.args[2];
                                        process_camera2_capture_session_callback(cb);
                                        if (isVirtualCameraDevice(param.thisObject)) {
                                            dispatchVirtualCaptureSession(param.thisObject, cb, param.args);
                                            param.setResult(null);
                                        }
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
                                    CameraCaptureSession.StateCallback cb = sessionConfiguration.getStateCallback();
                                    process_camera2_capture_session_callback(cb);
                                    if (isVirtualCameraDevice(param.thisObject)) {
                                        dispatchVirtualCaptureSession(
                                                param.thisObject, cb, new Object[]{sessionConfiguration.getExecutor()});
                                        param.setResult(null);
                                    }
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

    private static volatile Runnable sVirtualRepeatingTask;
    private static final java.util.concurrent.atomic.AtomicLong sVirtualFrameNumber =
            new java.util.concurrent.atomic.AtomicLong(1000L);

    private static void stopVirtualRepeatingTicker() {
        Runnable old = sVirtualRepeatingTask;
        sVirtualRepeatingTask = null;
        if (old != null && sSinkDrainHandler != null) {
            sSinkDrainHandler.removeCallbacks(old);
        }
    }

    @Nullable
    private static CaptureRequest extractFirstCaptureRequest(@Nullable Object[] args) {
        if (args == null) return null;
        for (Object arg : args) {
            if (arg instanceof CaptureRequest) {
                return (CaptureRequest) arg;
            }
            if (arg instanceof List) {
                List<?> list = (List<?>) arg;
                if (!list.isEmpty() && list.get(0) instanceof CaptureRequest) {
                    return (CaptureRequest) list.get(0);
                }
            }
        }
        return null;
    }

    @Nullable
    private static CameraCaptureSession.CaptureCallback extractCaptureCallback(@Nullable Object[] args) {
        if (args == null) return null;
        for (Object arg : args) {
            if (arg instanceof CameraCaptureSession.CaptureCallback) {
                return (CameraCaptureSession.CaptureCallback) arg;
            }
        }
        return null;
    }

    private void startVirtualRepeatingTicker(@NonNull final CameraCaptureSession session,
                                             @Nullable final Object[] args) {
        stopVirtualRepeatingTicker();
        final CaptureRequest req = extractFirstCaptureRequest(args);
        final CameraCaptureSession.CaptureCallback cb = extractCaptureCallback(args);
        if (cb == null || req == null) {
            return;
        }
        getOrCreateVirtualSinkSurface();
        if (sSinkDrainHandler == null) {
            return;
        }
        final Object[] cbArgs = args;
        Runnable ticker = new Runnable() {
            @Override
            public void run() {
                if (sVirtualRepeatingTask != this || !cameraSessionActive) {
                    return;
                }
                final long fn = sVirtualFrameNumber.incrementAndGet();
                final long ts = android.os.SystemClock.elapsedRealtimeNanos();
                final TotalCaptureResult result = createVirtualTotalCaptureResult(req, fn);
                if (result != null) {
                    dispatchOnCameraCallbackThread(cbArgs, () -> {
                        try {
                            cb.onCaptureStarted(session, req, ts, fn);
                            cb.onCaptureCompleted(session, req, result);
                        } catch (Throwable ignored) {}
                    });
                }
                if (sSinkDrainHandler != null && sVirtualRepeatingTask == this) {
                    sSinkDrainHandler.postDelayed(this, 33L);
                }
            }
        };
        sVirtualRepeatingTask = ticker;
        sSinkDrainHandler.postDelayed(ticker, 16L);
    }

    private void dispatchVirtualSingleCapture(@NonNull final CameraCaptureSession session,
                                              @Nullable final Object[] args) {
        final CaptureRequest req = extractFirstCaptureRequest(args);
        if (req != null) {
            Object targetsObj = XposedHelpers.getAdditionalInstanceField(req, "vcamTargets");
            if (targetsObj instanceof Collection) {
                for (Object item : (Collection<?>) targetsObj) {
                    if (item instanceof Surface) {
                        ActiveSurfaceFeeder feeder = ACTIVE_FEEDERS.get((Surface) item);
                        if (feeder != null && feeder.bitmapFeeder != null
                                && (feeder.format == ImageFormat.JPEG || feeder.format == 256)) {
                            final StaticBitmapFeeder sbf = feeder.bitmapFeeder;
                            stagingExecutor.execute(sbf::triggerSingleFrame);
                        }
                    }
                }
            }
        }
        final CameraCaptureSession.CaptureCallback cb = extractCaptureCallback(args);
        if (cb == null || req == null) {
            return;
        }
        final long fn = sVirtualFrameNumber.incrementAndGet();
        final long ts = android.os.SystemClock.elapsedRealtimeNanos();
        final TotalCaptureResult result = createVirtualTotalCaptureResult(req, fn);
        if (result != null) {
            dispatchOnCameraCallbackThread(args, () -> {
                try {
                    cb.onCaptureStarted(session, req, ts, fn);
                    cb.onCaptureCompleted(session, req, result);
                } catch (Throwable ignored) {}
            });
        }
    }

    private void hookCaptureSessionInstance(Class<?> sessionClass) {
        if (sessionClass == null || !HOOKED_CAPTURE_SESSIONS.add(sessionClass)) {
            return;
        }
        try {
            XposedHelpers.findAndHookMethod(sessionClass, "getDevice", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (isVirtualCaptureSession(param.thisObject)) {
                        param.setResult(XposedHelpers.getAdditionalInstanceField(param.thisObject, "vcamDevice"));
                    }
                }
            });
        } catch (Throwable ignored) {}

        XC_MethodHook repeatingHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (isInjectionActive() && hasAnyMedia()) {
                    ensureAllRegisteredFeedersRunning();
                }
                if (isVirtualCaptureSession(param.thisObject) && param.thisObject instanceof CameraCaptureSession) {
                    startVirtualRepeatingTicker((CameraCaptureSession) param.thisObject, param.args);
                    param.setResult(1);
                }
            }
        };
        XC_MethodHook singleCaptureHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (isInjectionActive() && hasAnyMedia()) {
                    ensureAllRegisteredFeedersRunning();
                }
                if (isVirtualCaptureSession(param.thisObject) && param.thisObject instanceof CameraCaptureSession) {
                    dispatchVirtualSingleCapture((CameraCaptureSession) param.thisObject, param.args);
                    param.setResult(1);
                }
            }
        };
        hookAllMethodsInHierarchy(sessionClass, "setRepeatingRequest", repeatingHook);
        hookAllMethodsInHierarchy(sessionClass, "setSingleRepeatingRequest", repeatingHook);
        hookAllMethodsInHierarchy(sessionClass, "setRepeatingBurst", repeatingHook);
        hookAllMethodsInHierarchy(sessionClass, "setRepeatingBurstRequests", repeatingHook);
        hookAllMethodsInHierarchy(sessionClass, "capture", singleCaptureHook);
        hookAllMethodsInHierarchy(sessionClass, "captureSingleRequest", singleCaptureHook);
        hookAllMethodsInHierarchy(sessionClass, "captureBurst", singleCaptureHook);
        hookAllMethodsInHierarchy(sessionClass, "captureBurstRequests", singleCaptureHook);

        XC_MethodHook virtualNoOpVoidHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (isVirtualCaptureSession(param.thisObject)) {
                    stopVirtualRepeatingTicker();
                    param.setResult(null);
                }
            }
        };
        hookAllMethodsInHierarchy(sessionClass, "stopRepeating", virtualNoOpVoidHook);
        hookAllMethodsInHierarchy(sessionClass, "abortCaptures", virtualNoOpVoidHook);
        hookAllMethodsInHierarchy(sessionClass, "close", virtualNoOpVoidHook);
        try {
            XposedHelpers.findAndHookMethod(sessionClass, "isReprocessable", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (isVirtualCaptureSession(param.thisObject)) {
                        param.setResult(Boolean.FALSE);
                    }
                }
            });
            XposedHelpers.findAndHookMethod(sessionClass, "getInputSurface", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (isVirtualCaptureSession(param.thisObject)) {
                        param.setResult(null);
                    }
                }
            });
        } catch (Throwable ignored) {}

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
        hookMethodInHierarchy(cbClass, "onConfigured", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args.length > 0 && param.args[0] != null) {
                    hookCaptureSessionInstance(param.args[0].getClass());
                }
                if (isInjectionActive() && hasAnyMedia()) {
                    ensureAllRegisteredFeedersRunning();
                }
            }
        }, CameraCaptureSession.class);

        hookMethodInHierarchy(cbClass, "onConfigureFailed", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                XposedBridge.log("[VCAM] Session onConfigureFailed");
            }
        }, CameraCaptureSession.class);
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

        hookMethodInHierarchy(builderClass, "addTarget", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!isInjectionActive() || !hasAnyMedia()) return;
                if (!(param.args[0] instanceof Surface)) return;
                Surface s = (Surface) param.args[0];
                if (s != c2_virtual_surface) {
                    getBuilderTargets(param.thisObject).add(s);
                    registerTargetSurface(s);
                }
                if (isVirtualBuilder(param.thisObject)) {
                    param.setResult(null);
                    return;
                }
                param.args[0] = getOrCreateVirtualSinkSurface();
            }
        }, Surface.class);

        hookMethodInHierarchy(builderClass, "removeTarget", new XC_MethodHook() {
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
                if (isVirtualBuilder(param.thisObject) || !targets.isEmpty()) {
                    // Other targets remain on this Builder! Do NOT remove c2_virtual_surface,
                    // otherwise builder.build() throws IllegalArgumentException: Request had no output targets!
                    param.setResult(null);
                    return;
                }
                param.args[0] = getOrCreateVirtualSinkSurface();
            }
        }, Surface.class);

        try {
            XposedBridge.hookAllMethods(builderClass, "set", new XC_MethodHook() {
                @Override
                @SuppressWarnings("unchecked")
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (isVirtualBuilder(param.thisObject)) {
                        if (param.args != null && param.args.length >= 2 && param.args[0] != null) {
                            Object mapObj = XposedHelpers.getAdditionalInstanceField(param.thisObject, "vcamSettings");
                            if (mapObj instanceof Map && param.args[1] != null) {
                                ((Map<Object, Object>) mapObj).put(param.args[0], param.args[1]);
                            }
                        }
                        param.setResult(null);
                    }
                }
            });
            XposedBridge.hookAllMethods(builderClass, "get", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (isVirtualBuilder(param.thisObject)) {
                        Object mapObj = XposedHelpers.getAdditionalInstanceField(param.thisObject, "vcamSettings");
                        Object val = (mapObj instanceof Map && param.args != null && param.args.length > 0)
                                ? ((Map<?, ?>) mapObj).get(param.args[0]) : null;
                        param.setResult(val);
                    }
                }
            });
            XposedBridge.hookAllMethods(builderClass, "setTag", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (isVirtualBuilder(param.thisObject)) {
                        if (param.args != null && param.args.length > 0) {
                            XposedHelpers.setAdditionalInstanceField(param.thisObject, "vcamTag", param.args[0]);
                        }
                        param.setResult(null);
                    }
                }
            });
            XposedBridge.hookAllMethods(builderClass, "setPhysicalCameraKey", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (isVirtualBuilder(param.thisObject)) {
                        param.setResult(param.thisObject);
                    }
                }
            });
        } catch (Throwable ignored) {}

        hookMethodInHierarchy(builderClass, "build", new XC_MethodHook() {
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
                if (isVirtualBuilder(param.thisObject)) {
                    CaptureRequest vReq = (CaptureRequest) allocateUnsafeInstance(CaptureRequest.class);
                    if (vReq != null) {
                        XposedHelpers.setAdditionalInstanceField(vReq, "vcamVirtualRequest", Boolean.TRUE);
                        XposedHelpers.setAdditionalInstanceField(vReq, "vcamTargets", new ArrayList<>(targets));
                        XposedHelpers.setAdditionalInstanceField(vReq, "vcamTag",
                                XposedHelpers.getAdditionalInstanceField(param.thisObject, "vcamTag"));
                        XposedHelpers.setAdditionalInstanceField(vReq, "vcamSettings",
                                XposedHelpers.getAdditionalInstanceField(param.thisObject, "vcamSettings"));
                        hookCaptureRequestInstance(CaptureRequest.class);
                        param.setResult(vReq);
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
            XposedBridge.hookAllMethods(reqClass, "get", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.thisObject != null && Boolean.TRUE.equals(
                            XposedHelpers.getAdditionalInstanceField(param.thisObject, "vcamVirtualRequest"))) {
                        Object mapObj = XposedHelpers.getAdditionalInstanceField(param.thisObject, "vcamSettings");
                        Object val = (mapObj instanceof Map && param.args != null && param.args.length > 0)
                                ? ((Map<?, ?>) mapObj).get(param.args[0]) : null;
                        param.setResult(val);
                    }
                }
            });
            XposedBridge.hookAllMethods(reqClass, "getTag", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.thisObject != null && Boolean.TRUE.equals(
                            XposedHelpers.getAdditionalInstanceField(param.thisObject, "vcamVirtualRequest"))) {
                        param.setResult(XposedHelpers.getAdditionalInstanceField(param.thisObject, "vcamTag"));
                    }
                }
            });
            XposedBridge.hookAllMethods(reqClass, "getKeys", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.thisObject != null && Boolean.TRUE.equals(
                            XposedHelpers.getAdditionalInstanceField(param.thisObject, "vcamVirtualRequest"))) {
                        param.setResult(Collections.emptyList());
                    }
                }
            });
            XposedBridge.hookAllMethods(reqClass, "isReprocess", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.thisObject != null && Boolean.TRUE.equals(
                            XposedHelpers.getAdditionalInstanceField(param.thisObject, "vcamVirtualRequest"))) {
                        param.setResult(Boolean.FALSE);
                    }
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
        File bestImg = resolveBestImageFile(video_path + "1000.bmp");
        if (bestImg == null) bestImg = new File(video_path + "1000.bmp");

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
                    File localVid = resolveBestVideoFile();
                    if (streamUrl != null) {
                        vtf.decodeStream(streamUrl);
                    } else if (localVid != null) {
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
                    StaticBitmapFeeder sbf = new StaticBitmapFeeder(s, bestImg, w, h, readerFmt);
                    feeder.bitmapFeeder = sbf;
                }
            } else if (imageMode || videoMode) {
                // Pass exact readerFmt (YUV_420_888 or JPEG) so StaticBitmapFeeder uses ImageWriter
                // instead of Surface.lockCanvas (which corrupts ImageReader format to RGBA_8888 0x1!)
                StaticBitmapFeeder sbf = new StaticBitmapFeeder(s, readerFmt, w, h, bestImg.getAbsolutePath());
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
                    File localVid = resolveBestVideoFile();
                    if (streamUrl != null) {
                        mp.setDataSource(streamUrl);
                    } else if (localVid != null) {
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
                    StaticBitmapFeeder sbf = new StaticBitmapFeeder(s, bestImg, w, h,
                            StaticBitmapFeeder.FORMAT_PREVIEW);
                    feeder.bitmapFeeder = sbf;
                }
            } else if (imageMode) {
                StaticBitmapFeeder sbf = new StaticBitmapFeeder(s, bestImg, w, h,
                        StaticBitmapFeeder.FORMAT_PREVIEW);
                feeder.bitmapFeeder = sbf;
            }
        }
    }

    private void stopAllCamera2Feeders() {
        stopVirtualRepeatingTicker();
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

        File imgFile = resolveBestImageFile(video_path + "1000.bmp");
        if (imgFile != null && imgFile.exists()) {
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

        // Provider stream fallback when local staging file is unavailable
        Bitmap providerBmp = decodeActiveImageFromProvider();
        if (providerBmp != null) {
            Bitmap out = FrameTransformPipeline.renderTransformedBitmap(providerBmp, w, h, cfg, 0, hostSensorRot);
            if (cfg.sensorGrainEnabled) {
                FrameTransformPipeline.applyTemporalGrainBitmap(out, cfg.grainStrength);
            }
            ExifSynthesizer.updatePhotometryFromBitmap(out, activeLensProfile, currentFacing);
            return out;
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
        Bitmap fallbackBmp = SyntheticFrameGenerator.renderTestPattern(w, h, currentHostPackage, currentFacing, 30f, cfg);
        if (fallbackBmp != null) {
            try {
                byte[] nv21 = FrameTransformPipeline.bitmapToNv21(fallbackBmp);
                NdkHookEngine.updateFrameBuffer(nv21, fallbackBmp.getWidth(), fallbackBmp.getHeight());
            } catch (Throwable ignored) {}
        }
        return fallbackBmp;
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
        IN_INTERNAL_CHECK.set(Boolean.TRUE);
        try {
            File controlFile = new File(Environment.getExternalStorageDirectory().getPath()
                    + "/DCIM/Camera1/disable.jpg");
            if (controlFile.exists()) {
                return false;
            }
        } finally {
            IN_INTERNAL_CHECK.set(Boolean.FALSE);
        }
        return cfgEnabled && cfgAllowedByFilter && !isPassthroughActive();
    }

    private boolean isSyntheticSourceMode() {
        String mode = activeTransformConfig.sourceMode;
        return TransformConfig.SOURCE_TEST_PATTERN.equals(mode)
                || TransformConfig.SOURCE_QR.equals(mode);
    }

    private boolean hasVideoStaged() {
        if (resolveBestVideoFile() != null) {
            return true;
        }
        return lastResolvedVideoUri != null && !MediaProvider.STATUS_UNSET.equals(lastResolvedVideoUri);
    }

    private boolean hasImageStaged() {
        if (resolveBestImageFile(null) != null) {
            return true;
        }
        return lastResolvedImageUri != null && !MediaProvider.STATUS_UNSET.equals(lastResolvedImageUri);
    }

    private boolean hasAnyMedia() {
        return isSyntheticSourceMode()
                || getActiveStreamUrl() != null
                || hasVideoStaged()
                || hasImageStaged();
    }

    private static volatile long lastMissingToastTime = 0L;

    private void maybeShowMissingMediaToast(final String pkg) {
        long now = System.currentTimeMillis();
        if (now - lastMissingToastTime < 4000L) {
            return;
        }
        lastMissingToastTime = now;
        IN_INTERNAL_CHECK.set(Boolean.TRUE);
        boolean noToastExists;
        try {
            File toastControl = new File(Environment.getExternalStorageDirectory().getPath()
                    + "/DCIM/Camera1/no_toast.jpg");
            noToastExists = toastControl.exists();
        } finally {
            IN_INTERNAL_CHECK.set(Boolean.FALSE);
        }
        need_to_show_toast = cfgToastEnabled && !noToastExists;
        final Context ctx = toast_content != null ? toast_content : resolveHostContext();
        if (ctx != null && need_to_show_toast) {
            new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    Toast.makeText(ctx,
                            "[VCAM] No replacement image or video\n" + pkg + "\nPath: " + video_path,
                            Toast.LENGTH_SHORT).show();
                } catch (Throwable ignored) {
                }
            });
        }
    }

    private static volatile long lastHookToastTime = 0L;

    private void showInjectedHookToast(String camType, int width, int height) {
        long now = System.currentTimeMillis();
        if (now - lastHookToastTime < 4000L) {
            return;
        }
        lastHookToastTime = now;
        IN_INTERNAL_CHECK.set(Boolean.TRUE);
        boolean noToastExists;
        try {
            File toastControl = new File(Environment.getExternalStorageDirectory().getPath()
                    + "/DCIM/Camera1/no_toast.jpg");
            noToastExists = toastControl.exists();
        } finally {
            IN_INTERNAL_CHECK.set(Boolean.FALSE);
        }
        need_to_show_toast = cfgToastEnabled && !noToastExists;
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

    private static boolean isValidHostContext(@Nullable Context ctx) {
        if (ctx == null) return false;
        try {
            String pkg = ctx.getPackageName();
            return pkg != null && !pkg.isEmpty() && !"android".equals(pkg);
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Nullable
    private static Context resolveHostContext() {
        HookMain inst = sInstance;
        if (inst != null && inst.toast_content != null) {
            if (isValidHostContext(inst.toast_content)) {
                return inst.toast_content;
            }
            inst.toast_content = null;
        }
        try {
            Application app = AndroidAppHelper.currentApplication();
            if (app != null) {
                Context appCtx = app.getApplicationContext();
                Context candidate = isValidHostContext(appCtx) ? appCtx : app;
                if (isValidHostContext(candidate)) {
                    return candidate;
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            Class<?> atClass = Class.forName("android.app.ActivityThread");
            Object currentApp = XposedHelpers.callStaticMethod(atClass, "currentApplication");
            if (currentApp instanceof Application) {
                Context appCtx = ((Application) currentApp).getApplicationContext();
                Context candidate = isValidHostContext(appCtx) ? appCtx : (Application) currentApp;
                if (isValidHostContext(candidate)) {
                    return candidate;
                }
            }
            Object at = XposedHelpers.callStaticMethod(atClass, "currentActivityThread");
            if (at != null) {
                Object boundApp = XposedHelpers.getObjectField(at, "mInitialApplication");
                if (boundApp instanceof Context && isValidHostContext((Context) boundApp)) {
                    return (Context) boundApp;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private void resolveWritableVideoPath(@Nullable Context ctx) {
        IN_INTERNAL_CHECK.set(Boolean.TRUE);
        try {
            File publicDir = new File(Environment.getExternalStorageDirectory().getPath() + "/DCIM/Camera1/");
            File forcePrivateMarker = new File(publicDir, "private_dir.jpg");
            boolean publicHasReadableMedia = new File(publicDir, "virtual.mp4").canRead()
                    || new File(publicDir, "1000.bmp").canRead()
                    || new File("/storage/emulated/0/DCIM/Camera1/virtual.mp4").canRead()
                    || new File("/storage/emulated/0/DCIM/Camera1/1000.bmp").canRead();

            if (!isValidHostContext(ctx)) {
                if (publicDir.exists() || publicDir.mkdirs()) {
                    video_path = publicDir.getAbsolutePath() + "/";
                }
                return;
            }

            File priv = null;
            try {
                priv = ctx.getExternalFilesDir(null);
            } catch (Throwable ignored) {
            }
            if (priv == null) {
                try {
                    priv = ctx.getFilesDir();
                } catch (Throwable ignored) {
                }
            }
            if (priv != null) {
                File dir = new File(priv, "Camera1/");
                if (dir.exists() || dir.mkdirs()) {
                    boolean privHasMedia = new File(dir, "virtual.mp4").exists()
                            || new File(dir, "1000.bmp").exists();
                    if (privHasMedia || forcePrivateMarker.exists() || !publicHasReadableMedia) {
                        video_path = dir.getAbsolutePath() + "/";
                        return;
                    }
                }
            }
            if (publicHasReadableMedia || publicDir.exists() || publicDir.mkdirs()) {
                video_path = publicDir.getAbsolutePath() + "/";
            }
        } finally {
            IN_INTERNAL_CHECK.set(Boolean.FALSE);
        }
    }

    private static void detectFacingCamera1(XC_MethodHook.MethodHookParam param) {
        int id = 0;
        if (param.args.length > 0 && param.args[0] instanceof Integer) {
            id = (Integer) param.args[0];
        }
        try {
            Camera.CameraInfo info = new Camera.CameraInfo();
            Camera.getCameraInfo(id, info);
            currentFacing = (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT)
                    ? MediaMappings.FACING_FRONT : MediaMappings.FACING_BACK;
        } catch (Throwable ignored) {
            currentFacing = (id == 1) ? MediaMappings.FACING_FRONT : MediaMappings.FACING_BACK;
        }
    }

    private static void detectFacingCamera2(XC_MethodHook.MethodHookParam param) {
        if (param.args == null || param.args.length == 0 || !(param.args[0] instanceof String)) return;
        String cameraId = (String) param.args[0];
        try {
            Object thisObj = param.thisObject;
            if (thisObj instanceof CameraManager) {
                CameraManager cm = (CameraManager) thisObj;
                CameraCharacteristics cc = cm.getCameraCharacteristics(cameraId);
                Integer lf = cc.get(CameraCharacteristics.LENS_FACING);
                if (lf != null) {
                    currentFacing = (lf == CameraMetadata.LENS_FACING_FRONT)
                            ? MediaMappings.FACING_FRONT : MediaMappings.FACING_BACK;
                    return;
                }
            }
        } catch (Throwable ignored) {
        }
        currentFacing = "1".equals(cameraId) ? MediaMappings.FACING_FRONT : MediaMappings.FACING_BACK;
    }

    private void ensureStagedNow(String hostPkg) {
        Context ctx = isValidHostContext(toast_content) ? toast_content : resolveHostContext();
        if (hostPkg != null && (currentHostPackage == null || currentHostPackage.isEmpty())) {
            currentHostPackage = hostPkg;
        }
        if (isValidHostContext(ctx)) {
            if (!isValidHostContext(toast_content)) {
                toast_content = ctx;
                lastStagedKey = null;
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                android.os.UserManager um = ctx.getSystemService(android.os.UserManager.class);
                if (um != null && !um.isUserUnlocked()) {
                    return;
                }
            } catch (Throwable ignored) {}
        }
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
        IN_INTERNAL_CHECK.set(Boolean.TRUE);
        try {
            // Ensure staging writes go into the app-private writable directory when available,
            // never clobbering global /DCIM/Camera1 files.
            File stageDir = new File(video_path);
            boolean isPublicDcim = video_path.contains("/DCIM/Camera1");
            if (isPublicDcim) {
                File priv = ctx.getExternalFilesDir(null);
                if (priv == null) priv = ctx.getFilesDir();
                if (priv != null) {
                    File privCam = new File(priv, "Camera1/");
                    if (privCam.exists() || privCam.mkdirs()) {
                        stageDir = privCam;
                    }
                }
            } else if (!stageDir.exists()) {
                //noinspection ResultOfMethodCallIgnored
                stageDir.mkdirs();
            }

            boolean stageDirIsPublic = stageDir.getAbsolutePath().contains("/DCIM/Camera1");
            File localImg = new File(stageDir, "1000.bmp");
            File localVid = new File(stageDir, "virtual.mp4");

            String imgUri = queryResolve(ctx, pkg, facing, MediaMappings.TYPE_IMAGE);
            String vidUri = queryResolve(ctx, pkg, facing, MediaMappings.TYPE_VIDEO);

            lastResolvedImageUri = imgUri;
            lastResolvedVideoUri = vidUri;

            boolean imgDone = false;
            boolean vidDone = false;

            if (MediaProvider.STATUS_UNSET.equals(imgUri)) {
                if (!stageDirIsPublic && localImg.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    localImg.delete();
                }
                StaticBitmapFeeder.invalidateAllCaches();
                imgDone = true;
            } else if (imgUri != null) {
                imgDone = copyProviderUri(ctx, Uri.parse(imgUri), localImg);
                if (imgDone && !stageDirIsPublic) {
                    video_path = stageDir.getAbsolutePath() + "/";
                }
                StaticBitmapFeeder.invalidateAllCaches();
            }

            if (MediaProvider.STATUS_UNSET.equals(vidUri)) {
                if (!stageDirIsPublic && localVid.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    localVid.delete();
                }
                vidDone = true;
            } else if (vidUri != null) {
                vidDone = copyProviderUri(ctx, Uri.parse(vidUri), localVid);
                if (vidDone && !stageDirIsPublic) {
                    video_path = stageDir.getAbsolutePath() + "/";
                }
            }

            return imgDone && vidDone;
        } finally {
            IN_INTERNAL_CHECK.set(Boolean.FALSE);
        }
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
