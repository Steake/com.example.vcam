package com.example.vcam;

import android.app.AndroidAppHelper;
import android.app.Application;
import android.os.Build;
import android.view.Surface;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Native NDK Camera2 & Media Hook Engine (libcamera2ndk.so / libmediandk.so / libandroid.so).
 *
 * Bridges native C/C++ camera pipelines (OpenCV, WebRTC NDK, Unity, Flutter, KYC/Liveness SDKs)
 * into HookMain's Surface & frame injection pipeline via PLT/GOT relocation patching,
 * dlsym interception, and LSPosed native_init hooks.
 */
public final class NdkHookEngine {

    private static final String TAG = "VCAM_NDK";
    private static final AtomicBoolean sLoaded = new AtomicBoolean(false);
    private static final AtomicBoolean sLoadHooksInstalled = new AtomicBoolean(false);

    private NdkHookEngine() {}

    public static native void nativeInstallHooks();
    public static native void nativeSetVirtualSinkSurface(Surface surface);
    public static native void nativeUpdateFrameBuffer(byte[] nv21, int width, int height);

    public static void ensureInitialized(final String moduleApkPath) {
        installLibraryLoadInterceptors(moduleApkPath);
        if (sLoaded.get()) {
            try {
                nativeInstallHooks();
            } catch (Throwable ignored) {}
            return;
        }
        tryLoadNativeLibrary(moduleApkPath);
    }

    private static synchronized void tryLoadNativeLibrary(String moduleApkPath) {
        if (sLoaded.get()) return;

        // 1. Try direct load if LSPosed native_init or library path already mapped it
        try {
            System.loadLibrary("vcam_ndk");
            sLoaded.set(true);
            nativeInstallHooks();
            XposedBridge.log("【VCAM】[NDK] Loaded libvcam_ndk.so via System.loadLibrary");
            return;
        } catch (Throwable ignored) {}

        // 2. Extract from module classloader resources (/lib/<abi>/libvcam_ndk.so) into host codeCacheDir
        try {
            File targetDir = resolveWritableNativeDir();
            if (targetDir == null) return;

            String[] abis = Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0
                    ? Build.SUPPORTED_ABIS
                    : new String[]{"arm64-v8a", "armeabi-v7a", "x86_64"};

            ClassLoader cl = NdkHookEngine.class.getClassLoader();
            for (String abi : abis) {
                String resPath = "lib/" + abi + "/libvcam_ndk.so";
                InputStream in = cl != null ? cl.getResourceAsStream(resPath) : null;
                if (in == null) {
                    URL url = NdkHookEngine.class.getResource("/" + resPath);
                    if (url != null) in = url.openStream();
                }
                java.util.zip.ZipFile zf = null;
                if (in == null && moduleApkPath != null && !moduleApkPath.isEmpty()) {
                    try {
                        zf = new java.util.zip.ZipFile(moduleApkPath);
                        java.util.zip.ZipEntry ze = zf.getEntry(resPath);
                        if (ze != null) in = zf.getInputStream(ze);
                    } catch (Throwable ignored) {}
                }
                if (in != null) {
                    File outSo = new File(targetDir, "libvcam_ndk_" + abi.replace('-', '_') + ".so");
                    try (InputStream src = in; FileOutputStream fos = new FileOutputStream(outSo, false)) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = src.read(buf)) > 0) {
                            fos.write(buf, 0, n);
                        }
                        fos.getFD().sync();
                    }
                    outSo.setReadable(true, false);
                    outSo.setExecutable(true, false);
                    if (zf != null) {
                        try { zf.close(); } catch (Throwable ignored) {}
                    }
                    System.load(outSo.getAbsolutePath());
                    sLoaded.set(true);
                    nativeInstallHooks();
                    XposedBridge.log("【VCAM】[NDK] Extracted & loaded " + outSo.getAbsolutePath());
                    return;
                }
                if (zf != null) {
                    try { zf.close(); } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable t) {
            XposedBridge.log("【VCAM】[NDK] Extraction load fallback: " + t.getMessage());
        }
    }

    private static File resolveWritableNativeDir() {
        try {
            Application app = AndroidAppHelper.currentApplication();
            if (app != null) {
                File dir = new File(app.getCodeCacheDir(), "vcam_native");
                if (dir.exists() || dir.mkdirs()) return dir;
                File filesDir = new File(app.getFilesDir(), "vcam_native");
                if (filesDir.exists() || filesDir.mkdirs()) return filesDir;
            }
        } catch (Throwable ignored) {}
        try {
            String tmp = System.getProperty("java.io.tmpdir");
            if (tmp != null) {
                File dir = new File(tmp, "vcam_native");
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
                    if (libName != null && libName.contains("vcam_ndk")) return;
                    onHostNativeLibraryLoaded(moduleApkPath, libName);
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
                    if (fileName != null && fileName.contains("vcam_ndk")) return;
                    onHostNativeLibraryLoaded(moduleApkPath, fileName);
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("【VCAM】[NDK] Failed to hook Runtime.loadLibrary0: " + t.getMessage());
        }
    }

    private static void onHostNativeLibraryLoaded(String moduleApkPath, String libName) {
        try {
            if (!sLoaded.get()) {
                tryLoadNativeLibrary(moduleApkPath);
            }
            if (sLoaded.get()) {
                nativeInstallHooks();
            }
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

    /**
     * Invoked from JNI when ACameraManager_openCamera is called by native code.
     */
    public static void onNdkCameraOpened() {
        try {
            HookMain.onNdkCameraOpened();
        } catch (Throwable t) {
            XposedBridge.log("【VCAM】[NDK] onNdkCameraOpened error: " + t.getMessage());
        }
    }

    /**
     * Invoked from JNI when an ANativeWindow is passed to ACaptureSessionOutput_create,
     * ACameraOutputTarget_create, or AImageReader_getWindow.
     */
    public static void onNdkSurfaceDiscovered(Surface surface, int width, int height, int format, boolean isImageReader) {
        try {
            HookMain.onNdkSurfaceDiscovered(surface, width, height, format, isImageReader);
        } catch (Throwable t) {
            XposedBridge.log("【VCAM】[NDK] onNdkSurfaceDiscovered error: " + t.getMessage());
        }
    }

    /**
     * Invoked from JNI when ACameraDevice_createCaptureSession completes.
     */
    public static void onNdkSessionCreated() {
        try {
            HookMain.onNdkSessionCreated();
        } catch (Throwable t) {
            XposedBridge.log("【VCAM】[NDK] onNdkSessionCreated error: " + t.getMessage());
        }
    }
}
