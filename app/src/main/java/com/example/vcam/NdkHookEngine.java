package com.example.vcam;

import android.app.AndroidAppHelper;
import android.app.Application;
import android.os.Build;
import android.view.Surface;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Native NDK Camera2 & Media Hook Engine (libcamera2ndk.so / libmediandk.so / libandroid.so).
 *
 * <p>Capabilities:
 * <ul>
 *   <li>Callee inline prologue trampolines (ARM64) + self-healing Shadow GOT table</li>
 *   <li>Full NDK capture chain interception: {@code openCamera} &rarr; {@code getCameraCharacteristics}
 *       &rarr; {@code createCaptureSession} &rarr; {@code AImageReader_getWindow} &rarr;
 *       {@code acquireLatestImage} &rarr; {@code AImage_getTimestamp} (start-of-exposure clock) &rarr;
 *       {@code AImage_getHardwareBuffer} / {@code AHardwareBuffer_describe} (ISP usage/stride) &rarr;
 *       {@code AImage_getPlaneData} (IMU-correlated jitter + temporal photon shot noise)</li>
 *   <li>Self-hiding loader: extracts under a randomized HAL name ({@code libcam_hal3_ext_<hex>.so}),
 *       unlinks the file immediately after {@code dlopen}, and scrubs {@code /proc/self/maps} via {@code memfd_create}.</li>
 * </ul>
 */
public final class NdkHookEngine {

    private static final AtomicBoolean sLoaded = new AtomicBoolean(false);
    private static final AtomicBoolean sExtractionFailed = new AtomicBoolean(false);
    private static final AtomicBoolean sLoadHooksInstalled = new AtomicBoolean(false);
    private static volatile String sCachedModuleApkPath = null;
    private static volatile String sHostDataDir = null;

    private NdkHookEngine() {}

    public static boolean isNativeLoaded() {
        return sLoaded.get();
    }

    public static native void nativeInstallHooks();
    public static native void nativeSetVirtualSinkSurface(Surface surface);
    public static native void nativeUpdateFrameBuffer(byte[] nv21, int width, int height);
    public static native void nativeConfigureSensorProfile(
            int facing, int orientation, int activeW, int activeH,
            float physW, float physH, int isoMin, int isoMax,
            long expMinNs, long expMaxNs);
    public static native void nativeUpdateImuMotion(
            float dxPx, float dyPx, float pitchRad, float rollRad);
    public static native int nativeSetSurfaceBuffersGeometry(
            Surface surface, int width, int height, int format);

    public static void setHostDataDir(@Nullable String dataDir) {
        if (dataDir != null && !dataDir.isEmpty()) {
            sHostDataDir = dataDir;
        }
    }

    public static void ensureInitialized(final String moduleApkPath) {
        if (moduleApkPath != null && !moduleApkPath.isEmpty()) {
            sCachedModuleApkPath = moduleApkPath;
        } else if (sCachedModuleApkPath == null) {
            sCachedModuleApkPath = resolveModuleApkPath();
        }
        final String resolvedApkPath = sCachedModuleApkPath;
        installLibraryLoadInterceptors(resolvedApkPath);
        if (sLoaded.get()) {
            try {
                nativeInstallHooks();
            } catch (Throwable ignored) {}
            return;
        }
        tryLoadNativeLibrary(resolvedApkPath);
    }

    @Nullable
    public static String resolveModuleApkPath() {
        try {
            if (NdkHookEngine.class.getProtectionDomain() != null
                    && NdkHookEngine.class.getProtectionDomain().getCodeSource() != null
                    && NdkHookEngine.class.getProtectionDomain().getCodeSource().getLocation() != null) {
                String path = NdkHookEngine.class.getProtectionDomain().getCodeSource().getLocation().getPath();
                if (path != null && path.endsWith(".apk") && new File(path).exists()) {
                    return path;
                }
            }
        } catch (Throwable ignored) {}
        try {
            URL url = NdkHookEngine.class.getClassLoader() != null
                    ? NdkHookEngine.class.getClassLoader().getResource("AndroidManifest.xml")
                    : null;
            if (url != null) {
                String spec = url.getFile();
                int bang = spec.indexOf('!');
                if (bang > 0) {
                    String jarPath = spec.substring(0, bang);
                    if (jarPath.startsWith("file:")) {
                        jarPath = jarPath.substring(5);
                    }
                    if (new File(jarPath).exists()) {
                        return jarPath;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static synchronized void tryLoadNativeLibrary(String moduleApkPath) {
        if (sLoaded.get() || sExtractionFailed.get()) return;

        // 1. Try direct load if LSPosed native_init already mapped the library
        try {
            System.loadLibrary("vcam_ndk");
            sLoaded.set(true);
            nativeInstallHooks();
            syncSensorProfile(HookMain.getActiveLensProfile(), HookMain.getCurrentFacing());
            return;
        } catch (Throwable ignored) {}

        // 2. Extract to a randomized camera HAL extension filename and unlink immediately after dlopen
        try {
            File targetDir = resolveWritableNativeDir();
            if (targetDir == null) return;

            File[] staleFiles = targetDir.listFiles();
            if (staleFiles != null) {
                for (File stale : staleFiles) {
                    if (stale != null && stale.getName().startsWith("libcam_hal3_ext_")) {
                        //noinspection ResultOfMethodCallIgnored
                        stale.delete();
                    }
                }
            }

            boolean is64Bit = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || android.os.Process.is64Bit();
            String[] rawAbis = is64Bit
                    ? (Build.SUPPORTED_64_BIT_ABIS != null && Build.SUPPORTED_64_BIT_ABIS.length > 0
                            ? Build.SUPPORTED_64_BIT_ABIS : new String[]{"arm64-v8a", "x86_64"})
                    : (Build.SUPPORTED_32_BIT_ABIS != null && Build.SUPPORTED_32_BIT_ABIS.length > 0
                            ? Build.SUPPORTED_32_BIT_ABIS : new String[]{"armeabi-v7a"});

            ClassLoader cl = NdkHookEngine.class.getClassLoader();
            Throwable firstLoadError = null;
            boolean attemptedLoad = false;
            for (String abi : rawAbis) {
                if (abi == null || abi.isEmpty()) continue;
                String resPath = "lib/" + abi + "/libvcam_ndk.so";
                InputStream in = cl != null ? cl.getResourceAsStream(resPath) : null;
                if (in == null) {
                    URL url = NdkHookEngine.class.getResource("/" + resPath);
                    if (url != null) in = url.openStream();
                }
                ZipFile zf = null;
                if (in == null && moduleApkPath != null && !moduleApkPath.isEmpty()) {
                    try {
                        zf = new ZipFile(moduleApkPath);
                        ZipEntry ze = zf.getEntry(resPath);
                        if (ze != null) in = zf.getInputStream(ze);
                    } catch (Throwable ignored) {}
                }
                if (in != null) {
                    String randHex = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
                    File outSo = new File(targetDir, "libcam_hal3_ext_" + randHex + ".so");
                    HookMain.IN_INTERNAL_CHECK.set(Boolean.TRUE);
                    try {
                        try (InputStream src = in; FileOutputStream fos = new FileOutputStream(outSo, false)) {
                            byte[] buf = new byte[8192];
                            int n;
                            while ((n = src.read(buf)) > 0) {
                                fos.write(buf, 0, n);
                            }
                            fos.getFD().sync();
                        } finally {
                            if (zf != null) {
                                try { zf.close(); } catch (Throwable ignored) {}
                            }
                        }
                        // Android 14+ (API 34+) Dynamic Code Loading policy requires dynamically loaded
                        // binaries to be strictly read-only (0400 / non-writable) before System.load().
                        //noinspection ResultOfMethodCallIgnored
                        outSo.setReadable(true, false);
                        //noinspection ResultOfMethodCallIgnored
                        outSo.setExecutable(true, false);
                        //noinspection ResultOfMethodCallIgnored
                        outSo.setWritable(false, false);
                        //noinspection ResultOfMethodCallIgnored
                        outSo.setReadOnly();
                        attemptedLoad = true;
                        System.load(outSo.getAbsolutePath());
                        sLoaded.set(true);
                        nativeInstallHooks();
                        syncSensorProfile(HookMain.getActiveLensProfile(), HookMain.getCurrentFacing());
                        return;
                    } catch (Throwable loadErr) {
                        if (firstLoadError == null) {
                            firstLoadError = loadErr;
                        }
                    } finally {
                        // Unlink immediately after dlopen (or failed dlopen) so no .so artifact remains on filesystem
                        //noinspection ResultOfMethodCallIgnored
                        outSo.setWritable(true, true);
                        //noinspection ResultOfMethodCallIgnored
                        outSo.delete();
                        HookMain.IN_INTERNAL_CHECK.set(Boolean.FALSE);
                    }
                }
                if (zf != null) {
                    try { zf.close(); } catch (Throwable ignored) {}
                }
            }
            if (attemptedLoad && !sLoaded.get()) {
                sExtractionFailed.set(true);
                if (firstLoadError != null) {
                    XposedBridge.log("【VCAM】[NDK] Extraction load fallback: " + firstLoadError.getMessage());
                }
            }
        } catch (Throwable t) {
            sExtractionFailed.set(true);
            XposedBridge.log("【VCAM】[NDK] Extraction load fallback: " + t.getMessage());
        }
    }

    private static File resolveWritableNativeDir() {
        try {
            Application app = AndroidAppHelper.currentApplication();
            if (app != null) {
                File dir = new File(app.getCodeCacheDir(), ".hal_cache");
                if (dir.exists() || dir.mkdirs()) return dir;
                File filesDir = new File(app.getFilesDir(), ".hal_cache");
                if (filesDir.exists() || filesDir.mkdirs()) return filesDir;
            }
        } catch (Throwable ignored) {}
        try {
            String hostData = sHostDataDir;
            if (hostData != null && !hostData.isEmpty()) {
                File codeCache = new File(new File(hostData, "code_cache"), ".hal_cache");
                if (codeCache.exists() || codeCache.mkdirs()) return codeCache;
                File filesDir = new File(new File(hostData, "files"), ".hal_cache");
                if (filesDir.exists() || filesDir.mkdirs()) return filesDir;
            }
        } catch (Throwable ignored) {}
        try {
            String tmp = System.getProperty("java.io.tmpdir");
            if (tmp != null) {
                File dir = new File(tmp, ".hal_cache");
                if (dir.exists() || dir.mkdirs()) return dir;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static void installLibraryLoadInterceptors(final String moduleApkPath) {
        if (!sLoadHooksInstalled.compareAndSet(false, true)) return;
        try {
            XposedBridge.hookAllMethods(Runtime.class, "loadLibrary0", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    String libName = null;
                    if (param.args != null) {
                        for (Object arg : param.args) {
                            if (arg instanceof String) {
                                libName = (String) arg;
                                break;
                            }
                        }
                    }
                    if (libName != null && (libName.contains("vcam_ndk") || libName.contains("libcam_hal3_ext_"))) {
                        return;
                    }
                    onHostNativeLibraryLoaded(moduleApkPath);
                }
            });
            XposedBridge.hookAllMethods(Runtime.class, "load0", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    String fileName = null;
                    if (param.args != null) {
                        for (Object arg : param.args) {
                            if (arg instanceof String) {
                                fileName = (String) arg;
                                break;
                            }
                        }
                    }
                    if (fileName != null && (fileName.contains("vcam_ndk") || fileName.contains("libcam_hal3_ext_"))) {
                        return;
                    }
                    onHostNativeLibraryLoaded(moduleApkPath);
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private static void onHostNativeLibraryLoaded(String moduleApkPath) {
        try {
            if (!sLoaded.get()) {
                tryLoadNativeLibrary(moduleApkPath);
            }
            if (sLoaded.get()) {
                nativeInstallHooks();
            }
        } catch (Throwable ignored) {}
    }

    public static void syncSensorProfile(@Nullable SensorProfiler.LensProfile profile, @Nullable String facingStr) {
        if (!sLoaded.get()) return;
        try {
            SensorProfiler.LensProfile p = profile != null
                    ? profile
                    : SensorProfiler.LensProfile.defaultForFacing(facingStr);
            int facing = MediaMappings.FACING_FRONT.equals(p.facing) ? 0 : 1;
            int activeW = p.activeArrayWidth > 0 ? p.activeArrayWidth : p.activeWidth;
            int activeH = p.activeArrayHeight > 0 ? p.activeArrayHeight : p.activeHeight;
            nativeConfigureSensorProfile(
                    facing, p.sensorOrientation, activeW, activeH,
                    p.physicalWidthMm, p.physicalHeightMm,
                    p.minIso, p.maxIso, p.minExposureNs, p.maxExposureNs);
        } catch (Throwable ignored) {}
    }

    public static void updateImuMotion(float dxPx, float dyPx, float pitchRad, float rollRad) {
        if (!sLoaded.get()) return;
        try {
            nativeUpdateImuMotion(dxPx, dyPx, pitchRad, rollRad);
        } catch (Throwable ignored) {}
    }

    public static void updateVirtualSinkSurface(Surface sinkSurface) {
        if (!sLoaded.get()) return;
        try {
            nativeSetVirtualSinkSurface(sinkSurface);
        } catch (Throwable ignored) {}
    }

    public static void updateFrameBuffer(byte[] nv21, int width, int height) {
        if (!sLoaded.get() || nv21 == null || nv21.length < 16 || width <= 0 || height <= 0) return;
        try {
            nativeUpdateFrameBuffer(nv21, width, height);
        } catch (Throwable ignored) {}
    }

    public static int setSurfaceBuffersGeometry(Surface surface, int width, int height, int format) {
        if (surface == null || width <= 0 || height <= 0) return -22;
        try {
            if (!sLoaded.get()) {
                ensureInitialized(sCachedModuleApkPath);
            }
            if (sLoaded.get()) {
                return nativeSetSurfaceBuffersGeometry(surface, width, height, format);
            }
        } catch (Throwable ignored) {}
        return -38;
    }

    public static void onNdkCameraOpened() {
        try {
            HookMain.onNdkCameraOpened();
        } catch (Throwable ignored) {}
    }

    public static void onNdkSurfaceDiscovered(Surface surface, int width, int height, int format, boolean isImageReader) {
        try {
            HookMain.onNdkSurfaceDiscovered(surface, width, height, format, isImageReader);
        } catch (Throwable ignored) {}
    }

    public static void onNdkSessionCreated() {
        try {
            HookMain.onNdkSessionCreated();
        } catch (Throwable ignored) {}
    }
}
