package com.example.vcam;


import android.Manifest;
import android.app.Application;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.InputConfiguration;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import android.app.AndroidAppHelper;

public class HookMain implements IXposedHookLoadPackage {
    public static Surface mSurface;
    public static SurfaceTexture mSurfacetexture;
    public static MediaPlayer mMediaPlayer;
    public static SurfaceTexture fake_SurfaceTexture;
    public static Camera origin_preview_camera;

    public static Camera camera_onPreviewFrame;
    public static Camera start_preview_camera;
    public static volatile byte[] data_buffer = {0};
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
    // Static-image feeders used when only 1000.bmp is staged (no virtual.mp4);
    // they push the bitmap into the reader / preview surfaces so Camera2
    // still-capture and continuous preview both produce the injected frame.
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

    /**
     * Currently-open camera facing, tracked across Camera1 / Camera2 opens.
     * {@code "back"} or {@code "front"}. Defaults to back so pre-facing-detection
     * code paths still resolve the back-camera global default when Camera1
     * legacy {@code open()} (no id) is used on devices with a single camera.
     */
    private static volatile String currentFacing = MediaMappings.FACING_BACK;

    /**
     * Dedupe key for {@link #maybeRestage}. {@code pkg|facing}; cleared when
     * {@link MediaMappings#ACTION_UPDATED} broadcast fires so the next
     * camera-open cycle re-stages with the new mapping.
     */
    private static volatile String lastStagedKey = null;
    private static volatile boolean mappingReceiverRegistered = false;

    /**
     * Shared single-threaded executor for provider-backed staging work.
     * Using one long-lived executor avoids the per-camera-open thread churn
     * that raw {@code new Thread(...)} created — some host apps open and
     * close the camera rapidly (e.g. pinch-to-zoom facing swaps), and we
     * don't want to spin up a fresh OS thread each time.
     */
    private static final ExecutorService stagingExecutor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "vcam-stage");
                t.setDaemon(true);
                return t;
            });

    /**
     * Permissions that are spoofed as {@code PERMISSION_GRANTED} inside every
     * injected application so the VCAM hook can access the user's
     * {@code /DCIM/Camera1/} directory for image/video injection even when the
     * host app was not granted storage access by the user.
     */
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

    /**
     * Hook the standard Android permission-check entry points so every injected
     * application observes file-system / media permissions as granted. This is
     * required for Image injection: the hook reads the replacement media from
     * {@code /DCIM/Camera1/}, but many target apps have not been granted
     * storage access by the user. Without this override the hook would silently
     * fall back to the app-private external directory and lose access to the
     * shared camera directory.
     *
     * <p>VCAM's own manager package is explicitly excluded so its real
     * permission flow (and "All files access" UX) keeps working unchanged.
     */
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

        // ContextImpl is the concrete implementation that every Context/
        // ContextWrapper delegates to, so hooking it covers checkPermission,
        // checkSelfPermission, checkCallingPermission and
        // checkCallingOrSelfPermission for storage-related permissions.
        try {
            XposedHelpers.findAndHookMethod("android.app.ContextImpl", lpparam.classLoader,
                    "checkPermission", String.class, int.class, int.class, grantIfStorage);
        } catch (Throwable t) {
            XposedBridge.log("[VCAM][perm-hook] ContextImpl.checkPermission: " + t);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                XposedHelpers.findAndHookMethod("android.app.ContextImpl", lpparam.classLoader,
                        "checkSelfPermission", String.class, grantIfStorage);
            } catch (Throwable t) {
                XposedBridge.log("[VCAM][perm-hook] ContextImpl.checkSelfPermission: " + t);
            }
        }
        try {
            XposedHelpers.findAndHookMethod("android.app.ApplicationPackageManager", lpparam.classLoader,
                    "checkPermission", String.class, String.class, grantIfStorage);
        } catch (Throwable t) {
            XposedBridge.log("[VCAM][perm-hook] ApplicationPackageManager.checkPermission: " + t);
        }

        // Environment.isExternalStorageManager() gates "All files access" on
        // API 30+. Force it to true inside target apps so code paths guarded
        // on MANAGE_EXTERNAL_STORAGE are taken.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                XposedHelpers.findAndHookMethod("android.os.Environment", lpparam.classLoader,
                        "isExternalStorageManager", alwaysTrue);
            } catch (Throwable t) {
                XposedBridge.log("[VCAM][perm-hook] Environment.isExternalStorageManager: " + t);
            }
            try {
                XposedHelpers.findAndHookMethod("android.os.Environment", lpparam.classLoader,
                        "isExternalStorageManager", File.class, alwaysTrue);
            } catch (Throwable t) {
                XposedBridge.log("[VCAM][perm-hook] Environment.isExternalStorageManager(File): " + t);
            }
        }
    }

    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) throws Exception {
        // Grant file-system / media permissions to injected applications so
        // the camera-directory based image injection works even when the host
        // app was never granted storage access by the user.
        applyInjectedStoragePermissions(lpparam);

        // Camera1: Camera.open(int) — detect facing from the camera id so
        // the per-(pkg,facing) mapping resolution knows which media to stage.
        // Stage synchronously here so hasAnyMedia() gates further down the
        // pipeline (setPreviewTexture, startPreview) see a populated
        // {@link #video_path} on the very first camera-open. maybeRestage
        // dedupes via lastStagedKey, so this costs one file copy per
        // (pkg,facing) per process.
        try {
            XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader,
                    "open", int.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            detectFacingCamera1(param);
                            ensureStagedNow(lpparam.packageName);
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log("[VCAM][facing] Camera.open(int) hook failed: " + t);
        }

        // Camera1: Camera.open() no-arg — defaults to camera id 0 (back on
        // virtually every device) so we keep the default currentFacing of
        // "back". Purely here to trigger synchronous staging for legacy
        // Camera1 hosts that never call the open(int) overload.
        try {
            XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader,
                    "open", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            ensureStagedNow(lpparam.packageName);
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log("[VCAM][facing] Camera.open() hook failed: " + t);
        }

        XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader, "setPreviewTexture", SurfaceTexture.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (hasVideoStaged()) {
                    File control_file = new File(Environment.getExternalStorageDirectory().getPath() + "/DCIM/Camera1/" + "disable.jpg");
                    if (control_file.exists()){
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
                    if (origin_preview_camera != null && origin_preview_camera.equals(param.thisObject)) {
                        param.args[0] = fake_SurfaceTexture;
                        XposedBridge.log("[VCAM] Duplicate preview camera: " + origin_preview_camera.toString());
                        return;
                    } else {
                        XposedBridge.log("[VCAM] Creating preview");
                    }

                    origin_preview_camera = (Camera) param.thisObject;
                    mSurfacetexture = (SurfaceTexture) param.args[0];
                    if (fake_SurfaceTexture == null) {
                        fake_SurfaceTexture = new SurfaceTexture(10);
                    } else {
                        fake_SurfaceTexture.release();
                        fake_SurfaceTexture = new SurfaceTexture(10);
                    }
                    param.args[0] = fake_SurfaceTexture;
                } else if (!hasImageStaged()) {
                    // Only complain when nothing is staged. Image-only mode
                    // is legitimate and handled by the still-capture path
                    // below; don't blank the preview or spam toasts.
                    File toast_control = new File(Environment.getExternalStorageDirectory().getPath() + "/DCIM/Camera1/" + "no_toast.jpg");
                    need_to_show_toast = !toast_control.exists();
                    if (toast_content != null && need_to_show_toast) {
                        try {
                            Toast.makeText(toast_content, "[VCAM] No replacement image or video\n" + lpparam.packageName + "\nPath: " + video_path, Toast.LENGTH_SHORT).show();
                        } catch (Exception ee) {
                            XposedBridge.log("[VCAM][toast]" + ee.toString());
                        }
                    }
                }
            }
        });

        XposedHelpers.findAndHookMethod("android.hardware.camera2.CameraManager", lpparam.classLoader, "openCamera", String.class, CameraDevice.StateCallback.class, Handler.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                detectFacingCamera2(param);
                ensureStagedNow(lpparam.packageName);
                if (param.args[1] == null) {
                    return;
                }
                if (param.args[1].equals(c2_state_cb)) {
                    return;
                }
                c2_state_cb = (CameraDevice.StateCallback) param.args[1];
                c2_state_callback = param.args[1].getClass();
                File control_file = new File(Environment.getExternalStorageDirectory().getPath() + "/DCIM/Camera1/" + "disable.jpg");
                if (control_file.exists()) {
                    return;
                }
                File file = new File(video_path + "virtual.mp4");
                File toast_control = new File(Environment.getExternalStorageDirectory().getPath() + "/DCIM/Camera1/" + "no_toast.jpg");
                need_to_show_toast = !toast_control.exists();
                if (!hasAnyMedia()) {
                    if (toast_content != null && need_to_show_toast) {
                        try {
                            Toast.makeText(toast_content, "[VCAM] No replacement image or video\n" + lpparam.packageName + "\nPath: " + video_path, Toast.LENGTH_SHORT).show();
                        } catch (Exception ee) {
                            XposedBridge.log("[VCAM][toast]" + ee.toString());
                        }
                    }
                    return;
                }
                XposedBridge.log("[VCAM] 1-arg camera init, class: " + c2_state_callback.toString());
                is_first_hook_build = true;
                process_camera2_init(c2_state_callback);
            }
        });


        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            XposedHelpers.findAndHookMethod("android.hardware.camera2.CameraManager", lpparam.classLoader, "openCamera", String.class, Executor.class, CameraDevice.StateCallback.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    detectFacingCamera2(param);
                    ensureStagedNow(lpparam.packageName);
                    if (param.args[2] == null) {
                        return;
                    }
                    if (param.args[2].equals(c2_state_cb)) {
                        return;
                    }
                    c2_state_cb = (CameraDevice.StateCallback) param.args[2];
                    File control_file = new File(Environment.getExternalStorageDirectory().getPath() + "/DCIM/Camera1/" + "disable.jpg");
                    if (control_file.exists()) {
                        return;
                    }
                    File file = new File(video_path + "virtual.mp4");
                    File toast_control = new File(Environment.getExternalStorageDirectory().getPath() + "/DCIM/Camera1/" + "no_toast.jpg");
                    need_to_show_toast = !toast_control.exists();
                    if (!hasAnyMedia()) {
                        if (toast_content != null && need_to_show_toast) {
                            try {
                                Toast.makeText(toast_content, "[VCAM] No replacement image or video\n" + lpparam.packageName + "\nPath: " + video_path, Toast.LENGTH_SHORT).show();
                            } catch (Exception ee) {
                                XposedBridge.log("[VCAM][toast]" + ee.toString());
                            }
                        }
                        return;
                    }
                    c2_state_callback = param.args[2].getClass();
                    XposedBridge.log("[VCAM] 2-arg camera init, class: " + c2_state_callback.toString());
                    is_first_hook_build = true;
                    process_camera2_init(c2_state_callback);
                }
            });
        }


        XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader, "setPreviewCallbackWithBuffer", Camera.PreviewCallback.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args[0] != null) {
                    process_callback(param);
                }
            }
        });

        XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader, "addCallbackBuffer", byte[].class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args[0] != null) {
                    param.args[0] = new byte[((byte[]) param.args[0]).length];
                }
            }
        });

        XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader, "setPreviewCallback", Camera.PreviewCallback.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args[0] != null) {
                    process_callback(param);
                }
            }
        });

        XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader, "setOneShotPreviewCallback", Camera.PreviewCallback.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args[0] != null) {
                    process_callback(param);
                }
            }
        });

        XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader, "takePicture", Camera.ShutterCallback.class, Camera.PictureCallback.class, Camera.PictureCallback.class, Camera.PictureCallback.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                XposedBridge.log("[VCAM] 4-arg takePicture");
                if (param.args[1] != null) {
                    process_a_shot_YUV(param);
                }

                if (param.args[3] != null) {
                    process_a_shot_jpeg(param, 3);
                }
            }
        });

        XposedHelpers.findAndHookMethod("android.media.MediaRecorder", lpparam.classLoader, "setCamera", Camera.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                super.beforeHookedMethod(param);
                File toast_control = new File(Environment.getExternalStorageDirectory().getPath() + "/DCIM/Camera1/" + "no_toast.jpg");
                need_to_show_toast = !toast_control.exists();
                XposedBridge.log("[VCAM][record]" + lpparam.packageName);
                if (toast_content != null && need_to_show_toast) {
                    try {
                        Toast.makeText(toast_content, "[VCAM] " + lpparam.appInfo.name + " (" + lpparam.packageName + ") triggered video recording, but interception is not supported yet", Toast.LENGTH_SHORT).show();
                    }catch (Exception ee){
                        XposedBridge.log("[VCAM][toast]" + Arrays.toString(ee.getStackTrace()));
                    }
                }
            }
        });

        XposedHelpers.findAndHookMethod("android.app.Instrumentation", lpparam.classLoader, "callApplicationOnCreate", Application.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                super.afterHookedMethod(param);
                if (param.args[0] instanceof Application) {
                    try {
                        toast_content = ((Application) param.args[0]).getApplicationContext();
                    } catch (Exception ee) {
                        XposedBridge.log("[VCAM] " + ee.toString());
                    }
                    // Post an in-app config notification so users can open the VCAM
                    // configuration dialog from inside the hooked target app.
                    postInAppConfigNotification(toast_content, lpparam.packageName);
                    File force_private = new File(Environment.getExternalStorageDirectory().getAbsolutePath() + "/DCIM/Camera1/private_dir.jpg");
                    if (toast_content != null) {// force private directory path
                        // Our spoofed-permission set makes
                        // checkSelfPermission() return GRANTED even when the
                        // host has no real access to /sdcard/DCIM/. Probe
                        // actual writability instead — write a tiny sentinel
                        // into the public path and see if it sticks. On
                        // Android 11+ this fails for almost every host, so we
                        // transparently fall back to the host-private
                        // getExternalFilesDir() path where writes always
                        // succeed.
                        boolean canUsePublicDcim;
                        try {
                            File publicDir = new File(Environment.getExternalStorageDirectory().getPath() + "/DCIM/Camera1/");
                            if (!publicDir.exists()) publicDir.mkdirs();
                            File probe = new File(publicDir, ".vcam_write_probe");
                            // try-with-resources: guarantees the FD is
                            // closed even if write() / flush() throws.
                            try (FileOutputStream probeFos = new FileOutputStream(probe)) {
                                probeFos.write(0);
                                probeFos.flush();
                            }
                            canUsePublicDcim = probe.exists() && probe.length() > 0;
                            //noinspection ResultOfMethodCallIgnored
                            probe.delete();
                        } catch (Throwable probeFail) {
                            canUsePublicDcim = false;
                        }
                        // Android 11+: never trust the public path, even if
                        // the probe happened to succeed in this particular
                        // process (MediaStore quirks). Force private.
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            canUsePublicDcim = false;
                        }
                        // permissions resolved
                        if (!canUsePublicDcim || force_private.exists()) {
                            File shown_file = new File(toast_content.getExternalFilesDir(null).getAbsolutePath() + "/Camera1/");
                            if ((!shown_file.isDirectory()) && shown_file.exists()) {
                                shown_file.delete();
                            }
                            if (!shown_file.exists()) {
                                shown_file.mkdir();
                            }
                            shown_file = new File(toast_content.getExternalFilesDir(null).getAbsolutePath() + "/Camera1/" + "has_shown");
                            File toast_force_file = new File(Environment.getExternalStorageDirectory().getPath()+ "/DCIM/Camera1/force_show.jpg");
                            if ((!lpparam.packageName.equals(BuildConfig.APPLICATION_ID)) && ((!shown_file.exists()) || toast_force_file.exists())) {
                                try {
                                    Toast.makeText(toast_content, "VCAM: Camera1 redirected to private storage for " + lpparam.packageName, Toast.LENGTH_SHORT).show();
                                    FileOutputStream fos = new FileOutputStream(toast_content.getExternalFilesDir(null).getAbsolutePath() + "/Camera1/" + "has_shown");
                                    String info = "shown";
                                    fos.write(info.getBytes());
                                    fos.flush();
                                    fos.close();
                                } catch (Exception e) {
                                    XposedBridge.log("[VCAM] [switch-dir]" + e.toString());
                                }
                            }
                            video_path = toast_content.getExternalFilesDir(null).getAbsolutePath() + "/Camera1/";
                        }else {
                            video_path = Environment.getExternalStorageDirectory().getPath() + "/DCIM/Camera1/";
                        }
                    } else {
                        video_path = Environment.getExternalStorageDirectory().getPath() + "/DCIM/Camera1/";
                        File uni_DCIM_path = new File(Environment.getExternalStorageDirectory().getPath() + "/DCIM/Camera1/");
                        if (uni_DCIM_path.canWrite()) {
                            File uni_Camera1_path = new File(video_path);
                            if (!uni_Camera1_path.exists()) {
                                uni_Camera1_path.mkdir();
                            }
                        }
                    }
                    // ContentProvider staging fallback: if the host has no
                    // storage access (typical on Android 11+) the legacy
                    // /DCIM/Camera1/ branch silently produces an empty
                    // directory. Query the manager's MediaProvider and copy
                    // staged media into video_path so the File-based hook
                    // pipeline below can keep working unchanged.
                    //
                    // Run the copy on a background thread — this hook fires
                    // on the host's main thread during Application startup,
                    // and a multi-MB virtual.mp4 copy here would add visible
                    // latency or even risk ANRs.
                    if (!hasAnyMedia() && toast_content != null) {
                        final Context appContext = toast_content;
                        final String hostPkg = lpparam.packageName;
                        stagingExecutor.execute(new Runnable() {
                            @Override public void run() {
                                try {
                                    maybeRestage(appContext, hostPkg, currentFacing);
                                } catch (Throwable t) {
                                    XposedBridge.log("[VCAM][stage] " + t);
                                }
                            }
                        });
                    }
                    // Register a broadcast receiver — clears the per-(pkg,facing)
                    // stage cache so the next camera-open re-queries the
                    // provider with the updated mapping. Silent failures are
                    // fine: hosts with stripped permissions just keep using
                    // whatever was staged on application start.
                    registerMappingUpdatedReceiver(toast_content);
                }
            }
        });

        XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader, "startPreview", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                File file = new File(video_path + "virtual.mp4");
                File toast_control = new File(Environment.getExternalStorageDirectory().getPath() + "/DCIM/Camera1/" + "no_toast.jpg");
                need_to_show_toast = !toast_control.exists();
                if (!hasAnyMedia()) {
                    if (toast_content != null && need_to_show_toast) {
                        try {
                            Toast.makeText(toast_content, "[VCAM] No replacement image or video\n" + lpparam.packageName + "\nPath: " + video_path, Toast.LENGTH_SHORT).show();
                        } catch (Exception ee) {
                            XposedBridge.log("[VCAM][toast]" + ee.toString());
                        }
                    }
                    return;
                }
                // Video-based preview replacement requires virtual.mp4. In
                // image-only mode we let the real camera preview through
                // untouched; the still-capture hook below still swaps
                // captured frames for 1000.bmp so the user gets the expected
                // result on tap-to-capture.
                if (!hasVideoStaged()) {
                    return;
                }
                File control_file = new File(Environment.getExternalStorageDirectory().getPath() + "/DCIM/Camera1/" + "disable.jpg");
                if (control_file.exists()) {
                    return;
                }
                is_someone_playing = false;
                XposedBridge.log("[VCAM] startPreview");
                start_preview_camera = (Camera) param.thisObject;
                if (ori_holder != null) {

                    if (mplayer1 == null) {
                        mplayer1 = new MediaPlayer();
                    } else {
                        mplayer1.release();
                        mplayer1 = null;
                        mplayer1 = new MediaPlayer();
                    }
                    if (!ori_holder.getSurface().isValid() || ori_holder == null) {
                        return;
                    }
                    mplayer1.setSurface(ori_holder.getSurface());
                    File sfile = new File(Environment.getExternalStorageDirectory().getPath() + "/DCIM/Camera1/" + "no-silent.jpg");
                    if (!(sfile.exists() && (!is_someone_playing))) {
                        mplayer1.setVolume(0, 0);
                        is_someone_playing = false;
                    } else {
                        is_someone_playing = true;
                    }
                    mplayer1.setLooping(true);

                    mplayer1.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                        @Override
                        public void onPrepared(MediaPlayer mp) {
                            mplayer1.start();
                        }
                    });

                    try {
                        mplayer1.setDataSource(video_path + "virtual.mp4");
                        mplayer1.prepare();
                    } catch (IOException e) {
                        XposedBridge.log("[VCAM] " + e.toString());
                    }
                }


                if (mSurfacetexture != null) {
                    if (mSurface == null) {
                        mSurface = new Surface(mSurfacetexture);
                    } else {
                        mSurface.release();
                        mSurface = new Surface(mSurfacetexture);
                    }

                    if (mMediaPlayer == null) {
                        mMediaPlayer = new MediaPlayer();
                    } else {
                        mMediaPlayer.release();
                        mMediaPlayer = new MediaPlayer();
                    }

                    mMediaPlayer.setSurface(mSurface);

                    File sfile = new File(Environment.getExternalStorageDirectory().getPath() + "/DCIM/Camera1/" + "no-silent.jpg");
                    if (!(sfile.exists() && (!is_someone_playing))) {
                        mMediaPlayer.setVolume(0, 0);
                        is_someone_playing = false;
                    } else {
                        is_someone_playing = true;
                    }
                    mMediaPlayer.setLooping(true);

                    mMediaPlayer.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                        @Override
                        public void onPrepared(MediaPlayer mp) {
                            mMediaPlayer.start();
                        }
                    });

                    try {
                        mMediaPlayer.setDataSource(video_path + "virtual.mp4");
                        mMediaPlayer.prepare();
                    } catch (IOException e) {
                        XposedBridge.log("[VCAM] " + e.toString());
                    }
                }
            }
        });

        XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader, "setPreviewDisplay", SurfaceHolder.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                XposedBridge.log("[VCAM] added SurfaceView preview");
                File file = new File(video_path + "virtual.mp4");
                File toast_control = new File(Environment.getExternalStorageDirectory().getPath() + "/DCIM/Camera1/" + "no_toast.jpg");
                need_to_show_toast = !toast_control.exists();
                if (!hasAnyMedia()) {
                    if (toast_content != null && need_to_show_toast) {
                        try {
                            Toast.makeText(toast_content, "[VCAM] No replacement image or video\n" + lpparam.packageName + "\nPath: " + video_path, Toast.LENGTH_SHORT).show();
                        } catch (Exception ee) {
                            XposedBridge.log("[VCAM][toast]" + ee.toString());
                        }
                    }
                    return;
                }
                // Only hijack the SurfaceView preview when we actually have a
                // video to play into it; image-only mode lets the live
                // preview pass through and injects on takePicture.
                if (!hasVideoStaged()) {
                    return;
                }
                File control_file = new File(Environment.getExternalStorageDirectory().getPath() + "/DCIM/Camera1/" + "disable.jpg");
                if (control_file.exists()) {
                    return;
                }
                mcamera1 = (Camera) param.thisObject;
                ori_holder = (SurfaceHolder) param.args[0];
                if (c1_fake_texture == null) {
                    c1_fake_texture = new SurfaceTexture(11);
                } else {
                    c1_fake_texture.release();
                    c1_fake_texture = null;
                    c1_fake_texture = new SurfaceTexture(11);
                }

                if (c1_fake_surface == null) {
                    c1_fake_surface = new Surface(c1_fake_texture);
                } else {
                    c1_fake_surface.release();
                    c1_fake_surface = null;
                    c1_fake_surface = new Surface(c1_fake_texture);
                }
                is_hooked = true;
                mcamera1.setPreviewTexture(c1_fake_texture);
                param.setResult(null);
            }
        });

        XposedHelpers.findAndHookMethod("android.hardware.camera2.CaptureRequest.Builder", lpparam.classLoader, "addTarget", Surface.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {

                if (param.args[0] == null) {
                    return;
                }
                if (param.thisObject == null) {
                    return;
                }
                File file = new File(video_path + "virtual.mp4");
                File toast_control = new File(Environment.getExternalStorageDirectory().getPath() + "/DCIM/Camera1/" + "no_toast.jpg");
                need_to_show_toast = !toast_control.exists();
                if (!hasAnyMedia()) {
                    if (toast_content != null && need_to_show_toast) {
                        try {
                            Toast.makeText(toast_content, "[VCAM] No replacement image or video\n" + lpparam.packageName + "\nPath: " + video_path, Toast.LENGTH_SHORT).show();
                        } catch (Exception ee) {
                            XposedBridge.log("[VCAM][toast]" + ee.toString());
                        }
                    }
                    return;
                }
                if (param.args[0].equals(c2_virtual_surface)) {
                    return;
                }
                File control_file = new File(Environment.getExternalStorageDirectory().getPath() + "/DCIM/Camera1/" + "disable.jpg");
                if (control_file.exists()) {
                    return;
                }
                String surfaceInfo = param.args[0].toString();
                if (surfaceInfo.contains("Surface(name=null)")) {
                    if (c2_reader_Surfcae == null) {
                        c2_reader_Surfcae = (Surface) param.args[0];
                    } else {
                        if ((!c2_reader_Surfcae.equals(param.args[0])) && c2_reader_Surfcae_1 == null) {
                            c2_reader_Surfcae_1 = (Surface) param.args[0];
                        }
                    }
                } else {
                    if (c2_preview_Surfcae == null) {
                        c2_preview_Surfcae = (Surface) param.args[0];
                    } else {
                        if ((!c2_preview_Surfcae.equals(param.args[0])) && c2_preview_Surfcae_1 == null) {
                            c2_preview_Surfcae_1 = (Surface) param.args[0];
                        }
                    }
                }
                XposedBridge.log("[VCAM] added target: " + param.args[0].toString());
                param.args[0] = c2_virtual_surface;

            }
        });

        XposedHelpers.findAndHookMethod("android.hardware.camera2.CaptureRequest.Builder", lpparam.classLoader, "removeTarget", Surface.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {

                if (param.args[0] == null) {
                    return;
                }
                if (param.thisObject == null) {
                    return;
                }
                File file = new File(video_path + "virtual.mp4");
                File toast_control = new File(Environment.getExternalStorageDirectory().getPath() + "/DCIM/Camera1/" + "no_toast.jpg");
                need_to_show_toast = !toast_control.exists();
                if (!hasAnyMedia()) {
                    if (toast_content != null && need_to_show_toast) {
                        try {
                            Toast.makeText(toast_content, "[VCAM] No replacement image or video\n" + lpparam.packageName + "\nPath: " + video_path, Toast.LENGTH_SHORT).show();
                        } catch (Exception ee) {
                            XposedBridge.log("[VCAM][toast]" + ee.toString());
                        }
                    }
                    return;
                }
                File control_file = new File(Environment.getExternalStorageDirectory().getPath() + "/DCIM/Camera1/" + "disable.jpg");
                if (control_file.exists()) {
                    return;
                }
                Surface rm_surf = (Surface) param.args[0];
                if (rm_surf.equals(c2_preview_Surfcae)) {
                    c2_preview_Surfcae = null;
                }
                if (rm_surf.equals(c2_preview_Surfcae_1)) {
                    c2_preview_Surfcae_1 = null;
                }
                if (rm_surf.equals(c2_reader_Surfcae_1)) {
                    c2_reader_Surfcae_1 = null;
                }
                if (rm_surf.equals(c2_reader_Surfcae)) {
                    c2_reader_Surfcae = null;
                }

                XposedBridge.log("[VCAM] removed target: " + param.args[0].toString());
            }
        });

        XposedHelpers.findAndHookMethod("android.hardware.camera2.CaptureRequest.Builder", lpparam.classLoader, "build", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (param.thisObject == null) {
                    return;
                }
                if (param.thisObject.equals(c2_builder)) {
                    return;
                }
                c2_builder = (CaptureRequest.Builder) param.thisObject;
                File file = new File(video_path + "virtual.mp4");
                File toast_control = new File(Environment.getExternalStorageDirectory().getPath() + "/DCIM/Camera1/" + "no_toast.jpg");
                need_to_show_toast = !toast_control.exists();
                if (!hasAnyMedia() && need_to_show_toast) {
                    if (toast_content != null) {
                        try {
                            Toast.makeText(toast_content, "[VCAM] No replacement image or video\n" + lpparam.packageName + "\nPath: " + video_path, Toast.LENGTH_SHORT).show();
                        } catch (Exception ee) {
                            XposedBridge.log("[VCAM][toast]" + ee.toString());
                        }
                    }
                    return;
                }

                File control_file = new File(Environment.getExternalStorageDirectory().getPath() + "/DCIM/Camera1/" + "disable.jpg");
                if (control_file.exists()) {
                    return;
                }
                XposedBridge.log("[VCAM] building capture request");
                process_camera2_play();
            }
        });

/*        XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader, "stopPreview", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.thisObject.equals(HookMain.origin_preview_camera) || param.thisObject.equals(HookMain.camera_onPreviewFrame) || param.thisObject.equals(HookMain.mcamera1)) {
                    if (hw_decode_obj != null) {
                        hw_decode_obj.stopDecode();
                    }
                    if (mplayer1 != null) {
                        mplayer1.release();
                        mplayer1 = null;
                    }
                    if (mMediaPlayer != null) {
                        mMediaPlayer.release();
                        mMediaPlayer = null;
                    }
                    is_someone_playing = false;

                    XposedBridge.log("[VCAM] stopPreview");
                }
            }
        });*/

        XposedHelpers.findAndHookMethod("android.media.ImageReader", lpparam.classLoader, "newInstance", int.class, int.class, int.class, int.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                XposedBridge.log("[VCAM] renderer created width=" + param.args[0] + "  height=" + param.args[1] + " format=" + param.args[2]);
                c2_ori_width = (int) param.args[0];
                c2_ori_height = (int) param.args[1];
                imageReaderFormat = (int) param.args[2];
                File toast_control = new File(Environment.getExternalStorageDirectory().getPath() + "/DCIM/Camera1/" + "no_toast.jpg");
                need_to_show_toast = !toast_control.exists();
                if (toast_content != null && need_to_show_toast) {
                    try {
                        Toast.makeText(toast_content, "[VCAM] Renderer created\nwidth: " + param.args[0] + "\nheight: " + param.args[1] + "\nVideo aspect must match", Toast.LENGTH_SHORT).show();
                    } catch (Exception e) {
                        XposedBridge.log("[VCAM][toast]" + e.toString());
                    }
                }
            }
        });


        XposedHelpers.findAndHookMethod("android.hardware.camera2.CameraCaptureSession.CaptureCallback", lpparam.classLoader, "onCaptureFailed", CameraCaptureSession.class, CaptureRequest.class, CaptureFailure.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        XposedBridge.log("[VCAM] onCaptureFailed" + "reason=" + ((CaptureFailure) param.args[2]).getReason());

                    }
                });
    }

    private void process_camera2_play() {
        // When the manager staged an image but no MP4, drive the Camera2
        // output surfaces from that bitmap via ImageWriter / lockCanvas
        // instead of the MediaCodec-backed VideoToFrames pipeline. This
        // makes Camera2 still-capture (JPEG ImageReader) and continuous
        // preview both inject the staged bitmap — the historical
        // "Camera1 takePicture-only" still-capture fallback is gone.
        boolean imageOnly = !hasVideoStaged() && hasImageStaged();
        if (imageOnly) {
            stopCamera2Feeders();
            String bmpPath = video_path + "1000.bmp";
            if (c2_reader_Surfcae != null) {
                c2_still_feeder = new StaticBitmapFeeder(
                        c2_reader_Surfcae, imageReaderFormat,
                        c2_ori_width, c2_ori_height, bmpPath);
                c2_still_feeder.start();
            }
            if (c2_reader_Surfcae_1 != null) {
                c2_still_feeder_1 = new StaticBitmapFeeder(
                        c2_reader_Surfcae_1, imageReaderFormat,
                        c2_ori_width, c2_ori_height, bmpPath);
                c2_still_feeder_1.start();
            }
            if (c2_preview_Surfcae != null) {
                c2_preview_feeder = new StaticBitmapFeeder(
                        c2_preview_Surfcae, StaticBitmapFeeder.FORMAT_PREVIEW,
                        c2_ori_width, c2_ori_height, bmpPath);
                c2_preview_feeder.start();
            }
            if (c2_preview_Surfcae_1 != null) {
                c2_preview_feeder_1 = new StaticBitmapFeeder(
                        c2_preview_Surfcae_1, StaticBitmapFeeder.FORMAT_PREVIEW,
                        c2_ori_width, c2_ori_height, bmpPath);
                c2_preview_feeder_1.start();
            }
            XposedBridge.log("[VCAM] Camera2 image-only feeders started");
            return;
        }
        stopCamera2Feeders();

        if (c2_reader_Surfcae != null) {
            if (c2_hw_decode_obj != null) {
                c2_hw_decode_obj.stopDecode();
                c2_hw_decode_obj = null;
            }

            c2_hw_decode_obj = new VideoToFrames();
            try {
                if (imageReaderFormat == 256) {
                    c2_hw_decode_obj.setSaveFrames("null", OutputImageFormat.JPEG);
                } else {
                    c2_hw_decode_obj.setSaveFrames("null", OutputImageFormat.NV21);
                }
                c2_hw_decode_obj.set_surfcae(c2_reader_Surfcae);
                c2_hw_decode_obj.decode(video_path + "virtual.mp4");
            } catch (Throwable throwable) {
                XposedBridge.log("[VCAM] " + throwable);
            }
        }

        if (c2_reader_Surfcae_1 != null) {
            if (c2_hw_decode_obj_1 != null) {
                c2_hw_decode_obj_1.stopDecode();
                c2_hw_decode_obj_1 = null;
            }

            c2_hw_decode_obj_1 = new VideoToFrames();
            try {
                if (imageReaderFormat == 256) {
                    c2_hw_decode_obj_1.setSaveFrames("null", OutputImageFormat.JPEG);
                } else {
                    c2_hw_decode_obj_1.setSaveFrames("null", OutputImageFormat.NV21);
                }
                c2_hw_decode_obj_1.set_surfcae(c2_reader_Surfcae_1);
                c2_hw_decode_obj_1.decode(video_path + "virtual.mp4");
            } catch (Throwable throwable) {
                XposedBridge.log("[VCAM] " + throwable);
            }
        }


        if (c2_preview_Surfcae != null) {
            if (c2_player == null) {
                c2_player = new MediaPlayer();
            } else {
                c2_player.release();
                c2_player = new MediaPlayer();
            }
            c2_player.setSurface(c2_preview_Surfcae);
            File sfile = new File(Environment.getExternalStorageDirectory().getPath() + "/DCIM/Camera1/" + "no-silent.jpg");
            if (!sfile.exists()) {
                c2_player.setVolume(0, 0);
            }
            c2_player.setLooping(true);

            try {
                c2_player.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                    public void onPrepared(MediaPlayer mp) {
                        c2_player.start();
                    }
                });
                c2_player.setDataSource(video_path + "virtual.mp4");
                c2_player.prepare();
            } catch (Exception e) {
                XposedBridge.log("[VCAM] [c2player][" + c2_preview_Surfcae.toString() + "]" + e);
            }
        }

        if (c2_preview_Surfcae_1 != null) {
            if (c2_player_1 == null) {
                c2_player_1 = new MediaPlayer();
            } else {
                c2_player_1.release();
                c2_player_1 = new MediaPlayer();
            }
            c2_player_1.setSurface(c2_preview_Surfcae_1);
            File sfile = new File(Environment.getExternalStorageDirectory().getPath() + "/DCIM/Camera1/" + "no-silent.jpg");
            if (!sfile.exists()) {
                c2_player_1.setVolume(0, 0);
            }
            c2_player_1.setLooping(true);

            try {
                c2_player_1.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                    public void onPrepared(MediaPlayer mp) {
                        c2_player_1.start();
                    }
                });
                c2_player_1.setDataSource(video_path + "virtual.mp4");
                c2_player_1.prepare();
            } catch (Exception e) {
                XposedBridge.log("[VCAM] [c2player1]" + "[ " + c2_preview_Surfcae_1.toString() + "]" + e);
            }
        }
        XposedBridge.log("[VCAM] Camera2 processing fully executed");
    }

    /** Tear down any static-bitmap feeders started by an earlier play(). */
    private static void stopCamera2Feeders() {
        if (c2_still_feeder != null) {
            try { c2_still_feeder.stop(); } catch (Throwable ignored) {}
            c2_still_feeder = null;
        }
        if (c2_still_feeder_1 != null) {
            try { c2_still_feeder_1.stop(); } catch (Throwable ignored) {}
            c2_still_feeder_1 = null;
        }
        if (c2_preview_feeder != null) {
            try { c2_preview_feeder.stop(); } catch (Throwable ignored) {}
            c2_preview_feeder = null;
        }
        if (c2_preview_feeder_1 != null) {
            try { c2_preview_feeder_1.stop(); } catch (Throwable ignored) {}
            c2_preview_feeder_1 = null;
        }
    }

    private Surface create_virtual_surface() {
        if (need_recreate) {
            if (c2_virtual_surfaceTexture != null) {
                c2_virtual_surfaceTexture.release();
                c2_virtual_surfaceTexture = null;
            }
            if (c2_virtual_surface != null) {
                c2_virtual_surface.release();
                c2_virtual_surface = null;
            }
            c2_virtual_surfaceTexture = new SurfaceTexture(15);
            c2_virtual_surface = new Surface(c2_virtual_surfaceTexture);
            need_recreate = false;
        } else {
            if (c2_virtual_surface == null) {
                need_recreate = true;
                c2_virtual_surface = create_virtual_surface();
            }
        }
        XposedBridge.log("[VCAM] rebuild dump surface " + c2_virtual_surface.toString());
        return c2_virtual_surface;
    }

    private void process_camera2_init(Class hooked_class) {

        XposedHelpers.findAndHookMethod(hooked_class, "onOpened", CameraDevice.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                need_recreate = true;
                create_virtual_surface();
                if (c2_player != null) {
                    c2_player.stop();
                    c2_player.reset();
                    c2_player.release();
                    c2_player = null;
                }
                if (c2_hw_decode_obj_1 != null) {
                    c2_hw_decode_obj_1.stopDecode();
                    c2_hw_decode_obj_1 = null;
                }
                if (c2_hw_decode_obj != null) {
                    c2_hw_decode_obj.stopDecode();
                    c2_hw_decode_obj = null;
                }
                stopCamera2Feeders();
                if (c2_player_1 != null) {
                    c2_player_1.stop();
                    c2_player_1.reset();
                    c2_player_1.release();
                    c2_player_1 = null;
                }
                c2_preview_Surfcae_1 = null;
                c2_reader_Surfcae_1 = null;
                c2_reader_Surfcae = null;
                c2_preview_Surfcae = null;
                is_first_hook_build = true;
                XposedBridge.log("[VCAM] Camera2 opened");

                File file = new File(video_path + "virtual.mp4");
                File toast_control = new File(Environment.getExternalStorageDirectory().getPath() + "/DCIM/Camera1/" + "no_toast.jpg");
                need_to_show_toast = !toast_control.exists();
                if (!hasAnyMedia()) {
                    if (toast_content != null && need_to_show_toast) {
                        try {
                            Toast.makeText(toast_content, "[VCAM] No replacement image or video\n" + toast_content.getPackageName() + "\nPath: " + video_path, Toast.LENGTH_SHORT).show();
                        } catch (Exception ee) {
                            XposedBridge.log("[VCAM][toast]" + ee.toString());
                        }
                    }
                    return;
                }
                XposedHelpers.findAndHookMethod(param.args[0].getClass(), "createCaptureSession", List.class, CameraCaptureSession.StateCallback.class, Handler.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam paramd) throws Throwable {
                        if (paramd.args[0] != null) {
                            XposedBridge.log("[VCAM] createCaptureSession captured, original=" + paramd.args[0].toString() + "virtual=" + c2_virtual_surface.toString());
                            paramd.args[0] = Arrays.asList(c2_virtual_surface);
                            if (paramd.args[1] != null) {
                                process_camera2Session_callback((CameraCaptureSession.StateCallback) paramd.args[1]);
                            }
                        }
                    }
                });

/*                XposedHelpers.findAndHookMethod(param.args[0].getClass(), "close", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam paramd) throws Throwable {
                        XposedBridge.log("[VCAM] Camera2 stopPreview");
                        if (c2_hw_decode_obj != null) {
                            c2_hw_decode_obj.stopDecode();
                            c2_hw_decode_obj = null;
                        }
                        if (c2_hw_decode_obj_1 != null) {
                            c2_hw_decode_obj_1.stopDecode();
                            c2_hw_decode_obj_1 = null;
                        }
                        if (c2_player != null) {
                            c2_player.release();
                            c2_player = null;
                        }
                        if (c2_player_1 != null){
                            c2_player_1.release();
                            c2_player_1 = null;
                        }
                        c2_preview_Surfcae_1 = null;
                        c2_reader_Surfcae_1 = null;
                        c2_reader_Surfcae = null;
                        c2_preview_Surfcae = null;
                        need_recreate = true;
                        is_first_hook_build= true;
                    }
                });*/

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    XposedHelpers.findAndHookMethod(param.args[0].getClass(), "createCaptureSessionByOutputConfigurations", List.class, CameraCaptureSession.StateCallback.class, Handler.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            super.beforeHookedMethod(param);
                            if (param.args[0] != null) {
                                outputConfiguration = new OutputConfiguration(c2_virtual_surface);
                                param.args[0] = Arrays.asList(outputConfiguration);

                                XposedBridge.log("[VCAM] invokedcreateCaptureSessionByOutputConfigurations-144777");
                                if (param.args[1] != null) {
                                    process_camera2Session_callback((CameraCaptureSession.StateCallback) param.args[1]);
                                }
                            }
                        }
                    });
                }


                    XposedHelpers.findAndHookMethod(param.args[0].getClass(), "createConstrainedHighSpeedCaptureSession", List.class, CameraCaptureSession.StateCallback.class, Handler.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            super.beforeHookedMethod(param);
                            if (param.args[0] != null) {
                                param.args[0] = Arrays.asList(c2_virtual_surface);
                                XposedBridge.log("[VCAM] invoked createConstrainedHighSpeedCaptureSession -5484987");
                                if (param.args[1] != null) {
                                    process_camera2Session_callback((CameraCaptureSession.StateCallback) param.args[1]);
                                }
                            }
                        }
                    });


                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    XposedHelpers.findAndHookMethod(param.args[0].getClass(), "createReprocessableCaptureSession", InputConfiguration.class, List.class, CameraCaptureSession.StateCallback.class, Handler.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            super.beforeHookedMethod(param);
                            if (param.args[1] != null) {
                                param.args[1] = Arrays.asList(c2_virtual_surface);
                                XposedBridge.log("[VCAM] invoked createReprocessableCaptureSession ");
                                if (param.args[2] != null) {
                                    process_camera2Session_callback((CameraCaptureSession.StateCallback) param.args[2]);
                                }
                            }
                        }
                    });
                }


                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    XposedHelpers.findAndHookMethod(param.args[0].getClass(), "createReprocessableCaptureSessionByConfigurations", InputConfiguration.class, List.class, CameraCaptureSession.StateCallback.class, Handler.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            super.beforeHookedMethod(param);
                            if (param.args[1] != null) {
                                outputConfiguration = new OutputConfiguration(c2_virtual_surface);
                                param.args[0] = Arrays.asList(outputConfiguration);
                                XposedBridge.log("[VCAM] invoked createReprocessableCaptureSessionByConfigurations");
                                if (param.args[2] != null) {
                                    process_camera2Session_callback((CameraCaptureSession.StateCallback) param.args[2]);
                                }
                            }
                        }
                    });
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    XposedHelpers.findAndHookMethod(param.args[0].getClass(), "createCaptureSession", SessionConfiguration.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            super.beforeHookedMethod(param);
                            if (param.args[0] != null) {
                                XposedBridge.log("[VCAM] invoked createCaptureSession -5484987");
                                sessionConfiguration = (SessionConfiguration) param.args[0];
                                outputConfiguration = new OutputConfiguration(c2_virtual_surface);
                                fake_sessionConfiguration = new SessionConfiguration(sessionConfiguration.getSessionType(),
                                        Arrays.asList(outputConfiguration),
                                        sessionConfiguration.getExecutor(),
                                        sessionConfiguration.getStateCallback());
                                param.args[0] = fake_sessionConfiguration;
                                process_camera2Session_callback(sessionConfiguration.getStateCallback());
                            }
                        }
                    });
                }
            }
        });


        XposedHelpers.findAndHookMethod(hooked_class, "onError", CameraDevice.class, int.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                XposedBridge.log("[VCAM] Camera error onError: " + (int) param.args[1]);
            }

        });


        XposedHelpers.findAndHookMethod(hooked_class, "onDisconnected", CameraDevice.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                XposedBridge.log("[VCAM] Camera onDisconnected");
            }

        });


    }

    private void process_a_shot_jpeg(XC_MethodHook.MethodHookParam param, int index) {
        try {
            XposedBridge.log("[VCAM] second jpeg: " + param.args[index].toString());
        } catch (Exception eee) {
            XposedBridge.log("[VCAM] " + eee);

        }
        Class callback = param.args[index].getClass();

        XposedHelpers.findAndHookMethod(callback, "onPictureTaken", byte[].class, android.hardware.Camera.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam paramd) throws Throwable {
                try {
                    Camera loaclcam = (Camera) paramd.args[1];
                    onemwidth = loaclcam.getParameters().getPreviewSize().width;
                    onemhight = loaclcam.getParameters().getPreviewSize().height;
                    XposedBridge.log("[VCAM] JPEG capture callback init width=" + onemwidth + " height=" + onemhight + " class=" + loaclcam.toString());
                    File toast_control = new File(Environment.getExternalStorageDirectory().getPath() + "/DCIM/Camera1/" + "no_toast.jpg");
                    need_to_show_toast = !toast_control.exists();
                    if (toast_content != null && need_to_show_toast) {
                        try {
                            Toast.makeText(toast_content, "[VCAM] Capture detected\nwidth: " + onemwidth + "\nheight: " + onemhight + "\nformat: JPEG", Toast.LENGTH_SHORT).show();
                        } catch (Exception e) {
                            XposedBridge.log("[VCAM][toast]" + e.toString());
                        }
                    }
                    File control_file = new File(Environment.getExternalStorageDirectory().getPath() + "/DCIM/Camera1/" + "disable.jpg");
                    if (control_file.exists()) {
                        return;
                    }

                    Bitmap pict = getBMP(video_path + "1000.bmp");
                    ByteArrayOutputStream temp_array = new ByteArrayOutputStream();
                    pict.compress(Bitmap.CompressFormat.JPEG, 100, temp_array);
                    byte[] jpeg_data = temp_array.toByteArray();
                    paramd.args[0] = jpeg_data;
                } catch (Exception ee) {
                    XposedBridge.log("[VCAM] " + ee.toString());
                }
            }
        });
    }

    private void process_a_shot_YUV(XC_MethodHook.MethodHookParam param) {
        try {
            XposedBridge.log("[VCAM] Capture detected YUV: " + param.args[1].toString());
        } catch (Exception eee) {
            XposedBridge.log("[VCAM] " + eee);
        }
        Class callback = param.args[1].getClass();
        XposedHelpers.findAndHookMethod(callback, "onPictureTaken", byte[].class, android.hardware.Camera.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam paramd) throws Throwable {
                try {
                    Camera loaclcam = (Camera) paramd.args[1];
                    onemwidth = loaclcam.getParameters().getPreviewSize().width;
                    onemhight = loaclcam.getParameters().getPreviewSize().height;
                    XposedBridge.log("[VCAM] YUV capture callback init width=" + onemwidth + " height=" + onemhight + " class=" + loaclcam.toString());
                    File toast_control = new File(Environment.getExternalStorageDirectory().getPath() + "/DCIM/Camera1/" + "no_toast.jpg");
                    need_to_show_toast = !toast_control.exists();
                    if (toast_content != null && need_to_show_toast) {
                        try {
                            Toast.makeText(toast_content, "[VCAM] Capture detected\nwidth: " + onemwidth + "\nheight: " + onemhight + "\nformat: YUV_420_888", Toast.LENGTH_SHORT).show();
                        } catch (Exception ee) {
                            XposedBridge.log("[VCAM][toast]" + ee.toString());
                        }
                    }
                    File control_file = new File(Environment.getExternalStorageDirectory().getPath() + "/DCIM/Camera1/" + "disable.jpg");
                    if (control_file.exists()) {
                        return;
                    }
                    input = getYUVByBitmap(getBMP(video_path + "1000.bmp"));
                    paramd.args[0] = input;
                } catch (Exception ee) {
                    XposedBridge.log("[VCAM] " + ee.toString());
                }
            }
        });
    }

    private void process_callback(XC_MethodHook.MethodHookParam param) {
        Class preview_cb_class = param.args[0].getClass();
        int need_stop = 0;
        File control_file = new File(Environment.getExternalStorageDirectory().getPath() + "/DCIM/Camera1/" + "disable.jpg");
        if (control_file.exists()) {
            need_stop = 1;
        }
        File file = new File(video_path + "virtual.mp4");
        File toast_control = new File(Environment.getExternalStorageDirectory().getPath() + "/DCIM/Camera1/" + "no_toast.jpg");
        need_to_show_toast = !toast_control.exists();
        if (!hasAnyMedia()) {
            if (toast_content != null && need_to_show_toast) {
                try {
                    Toast.makeText(toast_content, "[VCAM] No replacement image or video\n" + toast_content.getPackageName() + "\nPath: " + video_path, Toast.LENGTH_SHORT).show();
                } catch (Exception ee) {
                    XposedBridge.log("[VCAM][toast]" + ee);
                }
            }
            need_stop = 1;
        }
        int finalNeed_stop = need_stop;
        XposedHelpers.findAndHookMethod(preview_cb_class, "onPreviewFrame", byte[].class, android.hardware.Camera.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam paramd) throws Throwable {
                Camera localcam = (android.hardware.Camera) paramd.args[1];
                if (localcam.equals(camera_onPreviewFrame)) {
                    while (data_buffer == null) {
                    }
                    System.arraycopy(data_buffer, 0, paramd.args[0], 0, Math.min(data_buffer.length, ((byte[]) paramd.args[0]).length));
                } else {
                    camera_callback_calss = preview_cb_class;
                    camera_onPreviewFrame = (android.hardware.Camera) paramd.args[1];
                    mwidth = camera_onPreviewFrame.getParameters().getPreviewSize().width;
                    mhight = camera_onPreviewFrame.getParameters().getPreviewSize().height;
                    int frame_Rate = camera_onPreviewFrame.getParameters().getPreviewFrameRate();
                    XposedBridge.log("[VCAM] frame preview callback init width=" + mwidth + "  height=" + mhight + " fps=" + frame_Rate);
                    File toast_control = new File(Environment.getExternalStorageDirectory().getPath() + "/DCIM/Camera1/" + "no_toast.jpg");
                    need_to_show_toast = !toast_control.exists();
                    if (toast_content != null && need_to_show_toast) {
                        try {
                            Toast.makeText(toast_content, "[VCAM] Preview detected\nwidth: " + mwidth + "\nheight: " + mhight + "\nVideo resolution must match exactly", Toast.LENGTH_SHORT).show();
                        } catch (Exception ee) {
                            XposedBridge.log("[VCAM][toast]" + ee.toString());
                        }
                    }
                    if (finalNeed_stop == 1) {
                        return;
                    }
                    // Image-only mode: no MP4 to decode, so seed data_buffer
                    // once from the staged 1000.bmp and reuse it as a static
                    // "fake live" preview frame. This matches the ~15fps
                    // host cadence naturally because we copy on every
                    // delivered callback.
                    if (!hasVideoStaged() && hasImageStaged()) {
                        byte[] staticYuv = getCachedImageYuv();
                        if (staticYuv != null) {
                            data_buffer = staticYuv;
                            System.arraycopy(data_buffer, 0, paramd.args[0], 0,
                                    Math.min(data_buffer.length, ((byte[]) paramd.args[0]).length));
                        }
                        return;
                    }
                    if (hw_decode_obj != null) {
                        hw_decode_obj.stopDecode();
                    }
                    hw_decode_obj = new VideoToFrames();
                    hw_decode_obj.setSaveFrames("", OutputImageFormat.NV21);
                    hw_decode_obj.decode(video_path + "virtual.mp4");
                    while (data_buffer == null) {
                    }
                    System.arraycopy(data_buffer, 0, paramd.args[0], 0, Math.min(data_buffer.length, ((byte[]) paramd.args[0]).length));
                }

            }
        });

    }

    private void process_camera2Session_callback(CameraCaptureSession.StateCallback callback_calss){
        if (callback_calss == null){
            return;
        }
        XposedHelpers.findAndHookMethod(callback_calss.getClass(), "onConfigureFailed", CameraCaptureSession.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                XposedBridge.log("[VCAM] onConfigureFailed : " + param.args[0].toString());
            }

        });

        XposedHelpers.findAndHookMethod(callback_calss.getClass(), "onConfigured", CameraCaptureSession.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                XposedBridge.log("[VCAM] onConfigured : " + param.args[0].toString());
            }
        });

        XposedHelpers.findAndHookMethod( callback_calss.getClass(), "onClosed", CameraCaptureSession.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                XposedBridge.log("[VCAM] onClosed : "+ param.args[0].toString());
            }
        });
    }



    // Source: https://blog.csdn.net/jacke121/article/details/73888732
    private Bitmap getBMP(String file) throws Throwable {
        return BitmapFactory.decodeFile(file);
    }

    /** Returns true if the manager has staged a non-empty replacement video at {@link #video_path}. */
    private static boolean hasVideoStaged() {
        File f = new File(video_path + "virtual.mp4");
        return f.exists() && f.length() > 0;
    }

    /** Returns true if the manager has staged a non-empty still image at {@link #video_path}. */
    private static boolean hasImageStaged() {
        File f = new File(video_path + "1000.bmp");
        return f.exists() && f.length() > 0;
    }

    /** True when at least one of {@code virtual.mp4} / {@code 1000.bmp} is present. */
    private static boolean hasAnyMedia() {
        return hasVideoStaged() || hasImageStaged();
    }

    // Cache for the image-only preview YUV so we don't re-decode the bitmap
    // and re-convert to YUV on every new Camera instance. Invalidated when
    // the source bitmap's mtime changes (user swaps images via the manager).
    private static byte[] cachedImageYuv;
    private static long cachedImageYuvMtime;
    private static long cachedImageYuvSize;

    /**
     * Lazily decode {@code 1000.bmp} and convert to YUV once, reusing the
     * buffer until the underlying file is replaced (detected via mtime +
     * length). The preview callback can then {@code arraycopy} a cached
     * buffer instead of paying a bitmap-decode + YUV-convert on every
     * new camera instance.
     */
    private static byte[] getCachedImageYuv() {
        File f = new File(video_path + "1000.bmp");
        if (!f.exists() || f.length() == 0) {
            return null;
        }
        long mtime = f.lastModified();
        long size = f.length();
        byte[] cur = cachedImageYuv;
        if (cur != null && mtime == cachedImageYuvMtime && size == cachedImageYuvSize) {
            return cur;
        }
        try {
            Bitmap bmp = BitmapFactory.decodeFile(f.getAbsolutePath());
            if (bmp == null) return null;
            byte[] yuv = getYUVByBitmap(bmp);
            cachedImageYuv = yuv;
            cachedImageYuvMtime = mtime;
            cachedImageYuvSize = size;
            return yuv;
        } catch (Throwable t) {
            XposedBridge.log("[VCAM][image-cache] " + t);
            return null;
        }
    }

    /**
     * Try to stream staged media bytes from the manager's ContentProvider into
     * the host's private staging directory. This is the Android 11+-friendly
     * replacement for the legacy {@code /DCIM/Camera1/} path: the host process
     * seldom has {@code MANAGE_EXTERNAL_STORAGE}, but cross-app ContentProvider
     * reads succeed without any runtime permission.
     *
     * <p>Only copies when the destination is missing / empty; never overwrites
     * newer manual-setup content. Silent on failure so legacy users with
     * manually-populated DCIM paths keep working.
     *
     * @return true when at least one file was materialised via the provider.
     */
    private static boolean stageFromProvider(Context ctx) {
        if (ctx == null) return false;
        boolean anyCopied = false;
        try {
            File dir = new File(video_path);
            if (!dir.exists()) {
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
            }
            Uri[] srcs = new Uri[]{
                    Uri.parse("content://com.example.vcam/image"),
                    Uri.parse("content://com.example.vcam/video"),
            };
            String[] dstNames = new String[]{"1000.bmp", "virtual.mp4"};
            for (int i = 0; i < srcs.length; i++) {
                File dst = new File(video_path + dstNames[i]);
                if (dst.exists() && dst.length() > 0) continue;
                InputStream in = null;
                OutputStream out = null;
                try {
                    in = ctx.getContentResolver().openInputStream(srcs[i]);
                    if (in == null) continue;
                    out = new FileOutputStream(dst);
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    long total = 0;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        total += n;
                    }
                    out.flush();
                    if (total > 0) {
                        anyCopied = true;
                        XposedBridge.log("[VCAM][provider] staged " + dst.getAbsolutePath()
                                + " (" + total + " bytes)");
                    }
                } catch (Throwable t) {
                    // No staged media of this type in the manager, or host
                    // cannot reach the provider. Silent — fall back to legacy
                    // DCIM path.
                } finally {
                    if (in != null) try { in.close(); } catch (IOException ignored) {}
                    if (out != null) try { out.close(); } catch (IOException ignored) {}
                }
            }
        } catch (Throwable t) {
            XposedBridge.log("[VCAM][provider] " + t);
        }
        return anyCopied;
    }

    /**
     * Posts a notification inside the hooked target-app process that lets the user open the
     * VCAM in-app configuration dialog (InjectedConfigActivity) without leaving the target app.
     * The activity is declared with a translucent theme in the module APK, so tapping the
     * notification opens a floating config dialog. Idempotent per-process.
     */
    private static void postInAppConfigNotification(Context ctx, String targetPackage) {
        if (in_app_ui_notification_posted || ctx == null) return;
        if (targetPackage != null && targetPackage.equals(BuildConfig.APPLICATION_ID)) return;
        try {
            NotificationManager nm =
                    (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel ch = new NotificationChannel(
                        VCAM_CHANNEL_ID, "VCAM", NotificationManager.IMPORTANCE_LOW);
                ch.setDescription("Tap to configure the virtual camera from inside this app");
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !nm.areNotificationsEnabled()) {
                return;
            }

            // Explicit ComponentName targeting the manager app's InjectedConfigActivity.
            // action=CONFIGURE + caller package as extra so the overlay can identify the target app.
            Intent configIntent = new Intent("com.example.vcam.action.CONFIGURE");
            configIntent.setComponent(new ComponentName(
                    BuildConfig.APPLICATION_ID,
                    BuildConfig.APPLICATION_ID + ".InjectedConfigActivity"));
            configIntent.putExtra("caller_package", targetPackage);
            configIntent.putExtra("current_facing", currentFacing);
            configIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_NO_ANIMATION);

            int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                piFlags |= PendingIntent.FLAG_IMMUTABLE;
            }
            PendingIntent pi = PendingIntent.getActivity(
                    ctx, 0x0CAE /* request code */, configIntent, piFlags);

            Notification.Builder b;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                b = new Notification.Builder(ctx, VCAM_CHANNEL_ID);
            } else {
                //noinspection deprecation
                b = new Notification.Builder(ctx);
            }
            b.setSmallIcon(android.R.drawable.ic_menu_camera)
                    .setContentTitle("VCAM")
                    .setContentText("Tap to change the image/video injected here")
                    .setOngoing(false)
                    .setAutoCancel(true)
                    .setContentIntent(pi);

            nm.notify(VCAM_NOTIF_ID, b.build());
            in_app_ui_notification_posted = true;
            XposedBridge.log("[VCAM] posted in-app config notification for " + targetPackage);
        } catch (Throwable t) {
            XposedBridge.log("[VCAM][notif] " + t);
        }
    }

    private static byte[] rgb2YCbCr420(int[] pixels, int width, int height) {
        int len = width * height;
        // yuv array size: Y takes len bytes, U & V each take len/4.
        byte[] yuv = new byte[len * 3 / 2];
        int y, u, v;
        for (int i = 0; i < height; i++) {
            for (int j = 0; j < width; j++) {
                int rgb = (pixels[i * width + j]) & 0x00FFFFFF;
                int r = rgb & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = (rgb >> 16) & 0xFF;
                // apply formula
                y = ((66 * r + 129 * g + 25 * b + 128) >> 8) + 16;
                u = ((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128;
                v = ((112 * r - 94 * g - 18 * b + 128) >> 8) + 128;
                y = y < 16 ? 16 : (Math.min(y, 255));
                u = u < 0 ? 0 : (Math.min(u, 255));
                v = v < 0 ? 0 : (Math.min(v, 255));
                // assign
                yuv[i * width + j] = (byte) y;
                yuv[len + (i >> 1) * width + (j & ~1)] = (byte) u;
                yuv[len + (i >> 1) * width + (j & ~1) + 1] = (byte) v;
            }
        }
        return yuv;
    }

    private static byte[] getYUVByBitmap(Bitmap bitmap) {
        if (bitmap == null) {
            return null;
        }
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int size = width * height;
        int[] pixels = new int[size];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        return rgb2YCbCr420(pixels, width, height);
    }

    // ---------------------------------------------------------------------
    // Phase 4: per-host / per-camera media mapping
    // ---------------------------------------------------------------------

    /**
     * Camera1 facing: read cameraId from the hook param and dispatch to
     * {@code Camera.getCameraInfo(id, info)} to learn front vs back.
     */
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
        } catch (Throwable t) {
            XposedBridge.log("[VCAM][facing] camera1: " + t);
        }
    }

    /**
     * Camera2 facing: pull the cameraId out of openCamera and read
     * {@code CameraCharacteristics.LENS_FACING} from the CameraManager.
     */
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
        } catch (Throwable t) {
            XposedBridge.log("[VCAM][facing] camera2: " + t);
        }
    }

    /**
     * Synchronously stage media for the current host on the caller thread
     * so the gates that follow in the same {@code beforeHookedMethod}
     * (e.g. {@link #hasAnyMedia()}) see the populated {@link #video_path}
     * on the very first camera-open in a host process. {@link #maybeRestage}
     * is deduped via {@link #lastStagedKey}, so subsequent opens for the
     * same {@code (pkg, facing)} tuple return immediately.
     *
     * <p>A prior fire-and-forget background-thread variant raced these
     * gate checks. That race was harmless when {@code video_path} still
     * pointed at the manager-populated public {@code /DCIM/Camera1/}, but
     * on API 30+ the probe-based fallback forces a host-private directory
     * that starts empty, so the gate would fail and the hook would bail
     * out before staging completed — leaving the host showing its real
     * camera feed.
     *
     * <p>{@code toast_content} is set in
     * {@code Instrumentation.callApplicationOnCreate}'s afterHookedMethod,
     * which fires after {@code Application.onCreate()} returns. Hosts
     * that open the camera during their own {@code Application.onCreate()}
     * would otherwise see {@code ctx==null} and skip staging; fall back to
     * {@link AndroidAppHelper#currentApplication()} which resolves via the
     * ActivityThread and is available from the first moment the host has
     * an Application instance.
     */
    private void ensureStagedNow(String hostPkg) {
        Context ctx = toast_content;
        if (ctx == null) {
            try {
                android.app.Application app = AndroidAppHelper.currentApplication();
                if (app != null) ctx = app.getApplicationContext();
            } catch (Exception e) {
                XposedBridge.log("[VCAM][stage-sync] currentApplication: " + e);
            }
        }
        if (ctx == null || hostPkg == null) return;
        try {
            maybeRestage(ctx, hostPkg, currentFacing);
        } catch (Throwable t) {
            XposedBridge.log("[VCAM][stage-sync] " + t);
        }
    }

    /**
     * Stage media for the given {@code (pkg, facing)} iff we haven't just
     * staged the same tuple in this process. Always writes to the legacy
     * {@link #video_path} filenames so the existing File-based hook
     * pipeline picks them up unchanged.
     */
    private static synchronized void maybeRestage(Context ctx, String pkg, String facing) {
        if (ctx == null) return;
        String key = pkg + "|" + facing;
        if (key.equals(lastStagedKey)) return;
        stageFromProviderFor(ctx, pkg, facing);
        lastStagedKey = key;
    }

    /**
     * Query {@code content://com.example.vcam/resolve?pkg=&facing=&type=}
     * for both image and video, and copy whatever bytes the manager returns
     * into the host's staging files. Silent on failure — we retain the
     * previously-staged content and let the legacy DCIM fallback apply.
     */
    private static void stageFromProviderFor(Context ctx, String pkg, String facing) {
        if (ctx == null) return;
        try {
            File dir = new File(video_path);
            if (!dir.exists()) {
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
            }
        } catch (Throwable ignored) {}
        String imgUri = queryResolve(ctx, pkg, facing, MediaMappings.TYPE_IMAGE);
        String vidUri = queryResolve(ctx, pkg, facing, MediaMappings.TYPE_VIDEO);
        // Fall back to the legacy provider roots when no mapping resolves.
        // This covers fresh installs where the user staged a single
        // image/video in the manager but never configured mappings.
        if (imgUri == null) imgUri = "content://com.example.vcam/image";
        if (vidUri == null) vidUri = "content://com.example.vcam/video";
        copyProviderUri(ctx, Uri.parse(imgUri), new File(video_path + "1000.bmp"));
        copyProviderUri(ctx, Uri.parse(vidUri), new File(video_path + "virtual.mp4"));
    }

    private static String queryResolve(Context ctx, String pkg, String facing, String type) {
        try {
            Uri u = Uri.parse("content://com.example.vcam/resolve")
                    .buildUpon()
                    .appendQueryParameter("pkg", pkg)
                    .appendQueryParameter("facing", facing)
                    .appendQueryParameter("type", type)
                    .build();
            Cursor c = ctx.getContentResolver().query(u, null, null, null, null);
            if (c == null) return null;
            try {
                if (c.moveToFirst() && c.getColumnCount() > 0) {
                    String uri = c.getString(0);
                    if (uri != null && !uri.isEmpty() && !"null".equals(uri)) return uri;
                }
            } finally {
                c.close();
            }
        } catch (Throwable t) {
            XposedBridge.log("[VCAM][resolve] " + t);
        }
        return null;
    }

    private static void copyProviderUri(Context ctx, Uri src, File dst) {
        InputStream in = null;
        OutputStream out = null;
        try {
            in = ctx.getContentResolver().openInputStream(src);
            if (in == null) return;
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
                XposedBridge.log("[VCAM][provider] staged " + dst.getAbsolutePath()
                        + " (" + total + " bytes) from " + src);
            } else {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        } catch (Throwable t) {
            // Keep previous staged content on failure.
        } finally {
            if (in != null) try { in.close(); } catch (IOException ignored) {}
            if (out != null) try { out.close(); } catch (IOException ignored) {}
        }
    }

    /**
     * Register a broadcast receiver that invalidates {@link #lastStagedKey}
     * when the manager edits the mapping table. The next camera-open cycle
     * in this host will then re-query the provider with the new rules.
     */
    private static synchronized void registerMappingUpdatedReceiver(Context ctx) {
        if (ctx == null || mappingReceiverRegistered) return;
        try {
            BroadcastReceiver r = new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    lastStagedKey = null;
                    XposedBridge.log("[VCAM][mapping] MAPPING_UPDATED — invalidated cache");
                }
            };
            IntentFilter f = new IntentFilter(MediaMappings.ACTION_UPDATED);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Exported because the manager lives in a separate UID.
                ctx.registerReceiver(r, f, Context.RECEIVER_EXPORTED);
            } else {
                ctx.registerReceiver(r, f);
            }
            mappingReceiverRegistered = true;
        } catch (Throwable t) {
            XposedBridge.log("[VCAM][mapping-recv] " + t);
        }
    }
}
