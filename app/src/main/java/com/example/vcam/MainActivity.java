package com.example.vcam;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Material 3 companion UI for the VCAM Xposed module.
 *
 * <p>Preserves the legacy file-based toggle contract (marker files under
 * {@code /DCIM/Camera1/}) and the hook's expected media paths
 * ({@code virtual.mp4} / {@code 1000.bmp}) so previously installed hooks
 * keep working unchanged.
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "VCAM";

    private MaterialSwitch force_show_switch;
    private MaterialSwitch disable_switch;
    private MaterialSwitch play_sound_switch;
    private MaterialSwitch force_private_dir;
    private MaterialSwitch disable_toast_switch;
    private MaterialSwitch loopSwitch;
    private MaterialSwitch muteSwitch;

    private Chip chipModule, chipImage, chipVideo, chipRes, chipSize;
    private ImageView preview;
    private TextInputEditText pkgFilter, customImagePath, customVideoPath;

    // Demo card (Bug 4)
    private TextureView demoTexture;
    private Chip demoOkChip;
    private TextView demoReport;
    private MediaPlayer demoPlayer;
    private Handler demoHandler;
    private Runnable demoImageLoop;
    private Surface demoSurface;

    private ActivityResultLauncher<String> pickImageLauncher;
    private ActivityResultLauncher<String> pickVideoLauncher;
    private ActivityResultLauncher<String[]> storagePermsLauncher;
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor(
            runnable -> new Thread(runnable, "VCAM-IO"));
    private int syncRequestId = 0;

    private SharedPreferences prefs;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences(VCAMApp.PREFS, MODE_PRIVATE);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.action_onboarding) {
                startActivity(new Intent(this, OnboardingActivity.class));
                return true;
            } else if (id == R.id.action_injected_ui) {
                startActivity(new Intent(this, InjectedConfigActivity.class));
                return true;
            }
            return false;
        });

        // Switches
        force_show_switch = findViewById(R.id.switch1);
        disable_switch = findViewById(R.id.switch2);
        play_sound_switch = findViewById(R.id.switch3);
        force_private_dir = findViewById(R.id.switch4);
        disable_toast_switch = findViewById(R.id.switch5);
        loopSwitch = findViewById(R.id.switch_loop);
        muteSwitch = findViewById(R.id.switch_mute);

        // Status chips + preview
        chipModule = findViewById(R.id.chip_module);
        chipImage = findViewById(R.id.chip_image);
        chipVideo = findViewById(R.id.chip_video);
        chipRes = findViewById(R.id.chip_resolution);
        chipSize = findViewById(R.id.chip_size);
        preview = findViewById(R.id.image_preview);

        // Advanced inputs
        pkgFilter = findViewById(R.id.edit_package_filter);
        customImagePath = findViewById(R.id.edit_custom_image_path);
        customVideoPath = findViewById(R.id.edit_custom_video_path);
        pkgFilter.setText(prefs.getString(VCAMApp.KEY_PACKAGE_FILTER, ""));
        customImagePath.setText(prefs.getString(VCAMApp.KEY_CUSTOM_IMAGE_PATH, ""));
        customVideoPath.setText(prefs.getString(VCAMApp.KEY_CUSTOM_VIDEO_PATH, ""));

        loopSwitch.setChecked(prefs.getBoolean(VCAMApp.KEY_LOOP_VIDEO, true));
        muteSwitch.setChecked(prefs.getBoolean(VCAMApp.KEY_MUTE_VIDEO, false));

        loopSwitch.setOnCheckedChangeListener((b, c) ->
                prefs.edit().putBoolean(VCAMApp.KEY_LOOP_VIDEO, c).apply());
        muteSwitch.setOnCheckedChangeListener((b, c) ->
                prefs.edit().putBoolean(VCAMApp.KEY_MUTE_VIDEO, c).apply());

        // Register content pickers for image/video import.
        pickImageLauncher = registerForActivityResult(
                new ActivityResultContracts.GetContent(),
                uri -> {
                    if (uri != null) importMedia(uri, true);
                });
        pickVideoLauncher = registerForActivityResult(
                new ActivityResultContracts.GetContent(),
                uri -> {
                    if (uri != null) importMedia(uri, false);
                });
        storagePermsLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                res -> syncStateWithFiles());

        // Buttons
        findViewById(R.id.btn_pick_image).setOnClickListener(v -> {
            if (!hasStoragePermission()) { requestStoragePermission(); return; }
            MediaPaths.defaultDir();
            pickImageLauncher.launch("image/*");
        });
        findViewById(R.id.btn_pick_video).setOnClickListener(v -> {
            if (!hasStoragePermission()) { requestStoragePermission(); return; }
            MediaPaths.defaultDir();
            pickVideoLauncher.launch("video/*");
        });

        findViewById(R.id.btn_clear).setOnClickListener(v -> clearMedia());
        findViewById(R.id.btn_test).setOnClickListener(v -> launchVerifier());

        findViewById(R.id.btn_save_paths).setOnClickListener(v -> savePaths());

        findViewById(R.id.button).setOnClickListener(v -> {
            Uri uri = Uri.parse("https://github.com/w2016561536/android_virtual_cam");
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        });
        findViewById(R.id.button2).setOnClickListener(v -> {
            Uri uri = Uri.parse("https://gitee.com/w2016561536/android_virtual_cam");
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        });

        wireSwitchesToFiles();
        wireDemoCard();

        if (!hasStoragePermission()) {
            // Don't block; just surface once so the user isn't left wondering why imports fail.
            requestStoragePermission();
        }

        if (!prefs.getBoolean(VCAMApp.KEY_ONBOARDING_DONE, false)) {
            startActivity(new Intent(this, OnboardingActivity.class));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        syncStateWithFiles();
    }

    @Override
    protected void onDestroy() {
        stopDemo();
        ioExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopDemo();
    }

    // ---------------------------------------------------------------------
    // Demo card: exercises the same MediaPlayer / bitmap pipeline the hook
    // uses so the user can confirm media is decodable before launching a
    // host app. (Bug 4)
    // ---------------------------------------------------------------------

    private void wireDemoCard() {
        demoTexture = findViewById(R.id.demo_texture);
        demoOkChip = findViewById(R.id.demo_chip_ok);
        demoReport = findViewById(R.id.demo_report);
        demoHandler = new Handler(Looper.getMainLooper());

        findViewById(R.id.demo_btn_validate).setOnClickListener(v -> validateStagedMedia());

        demoTexture.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(@NonNull SurfaceTexture s, int w, int h) {
                startDemoPlayback();
            }
            @Override public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture s, int w, int h) {}
            @Override public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture s) {
                stopDemo();
                return true;
            }
            @Override public void onSurfaceTextureUpdated(@NonNull SurfaceTexture s) {}
        });
    }

    private void startDemoPlayback() {
        stopDemo();
        SurfaceTexture tex = demoTexture.getSurfaceTexture();
        if (tex == null) return;
        demoSurface = new Surface(tex);

        final File vid = MediaPaths.getVideoTarget(this);
        final File img = MediaPaths.getImageTarget(this);
        if (vid.exists() && vid.length() > 0) {
            final MediaPlayer player = new MediaPlayer();
            demoPlayer = player;
            player.setSurface(demoSurface);
            player.setLooping(prefs.getBoolean(VCAMApp.KEY_LOOP_VIDEO, true));
            if (prefs.getBoolean(VCAMApp.KEY_MUTE_VIDEO, false)) {
                player.setVolume(0f, 0f);
            }
            // Guard against async prepare completing after stopDemo() —
            // player may have been released, or a newer player may have
            // replaced it. Only start when we're still the active one.
            player.setOnPreparedListener(mp -> {
                if (demoPlayer != mp) return;
                try {
                    mp.start();
                } catch (IllegalStateException e) {
                    Log.w(TAG, "demo video start skipped after lifecycle cleanup", e);
                }
            });
            try {
                player.setDataSource(vid.getAbsolutePath());
                player.prepareAsync();
            } catch (IOException e) {
                Log.w(TAG, "demo video prepare failed", e);
            }
        } else if (img.exists() && img.length() > 0) {
            // Render the staged bitmap onto the demo surface at ~15fps,
            // matching the image-only fallback behaviour documented in
            // HookMain#process_callback. Decode off the UI thread and
            // sample down to the TextureView size to avoid OOM / jank on
            // large staged images.
            final int maxSide = Math.max(
                    Math.max(demoTexture.getWidth(), demoTexture.getHeight()),
                    800);
            final int requestedSync = syncRequestId;
            ioExecutor.execute(() -> {
                final Bitmap bmp = MediaPaths.decodeImageThumb(img, maxSide);
                if (bmp == null) return;
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    if (requestedSync != syncRequestId && demoSurface == null) return;
                    startImageLoop(bmp);
                });
            });
        }
    }

    private void startImageLoop(@NonNull Bitmap bmp) {
        demoImageLoop = new Runnable() {
            @Override public void run() {
                try {
                    Surface s = demoSurface;
                    if (s == null || !s.isValid()) return;
                    Canvas c = s.lockCanvas(null);
                    if (c != null) {
                        Rect dst = new Rect(0, 0, c.getWidth(), c.getHeight());
                        c.drawBitmap(bmp, null, dst, null);
                        s.unlockCanvasAndPost(c);
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "demo image loop", t);
                } finally {
                    if (demoHandler != null && demoImageLoop == this) {
                        demoHandler.postDelayed(this, 66L /* ~15fps */);
                    }
                }
            }
        };
        demoHandler.post(demoImageLoop);
    }

    private void stopDemo() {
        if (demoHandler != null && demoImageLoop != null) {
            demoHandler.removeCallbacks(demoImageLoop);
            demoImageLoop = null;
        }
        if (demoPlayer != null) {
            try { demoPlayer.setOnPreparedListener(null); } catch (Throwable ignored) {}
            try { demoPlayer.stop(); } catch (Throwable ignored) {}
            demoPlayer.release();
            demoPlayer = null;
        }
        if (demoSurface != null) {
            demoSurface.release();
            demoSurface = null;
        }
    }

    private void validateStagedMedia() {
        File vid = MediaPaths.getVideoTarget(this);
        File img = MediaPaths.getImageTarget(this);
        StringBuilder report = new StringBuilder();
        boolean anyOk = false;
        boolean warn = false;

        if (img.exists() && img.length() > 0) {
            int[] res = MediaPaths.imageResolution(img);
            String codec = "bitmap";
            if (res != null) {
                report.append(getString(R.string.demo_report_image_format,
                        codec, res[0], res[1], MediaPaths.humanBytes(img.length()))).append('\n');
                anyOk = true;
                if (res[0] < 160 || res[1] < 120 || res[0] > 4096 || res[1] > 4096) warn = true;
            }
        }
        if (vid.exists() && vid.length() > 0) {
            MediaMetadataRetriever r = new MediaMetadataRetriever();
            try {
                r.setDataSource(vid.getAbsolutePath());
                String mime = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE);
                String bitrate = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE);
                int[] res = MediaPaths.videoResolution(vid);
                int w = res != null ? res[0] : 0;
                int h = res != null ? res[1] : 0;
                String rate = bitrate != null
                        ? MediaPaths.humanBytes(Long.parseLong(bitrate) / 8) + "/s" : "?";
                report.append(getString(R.string.demo_report_video_format,
                        mime == null ? "video" : mime, w, h, rate)).append('\n');
                anyOk = true;
                if (w < 160 || h < 120 || w > 4096 || h > 4096) warn = true;
            } catch (Throwable t) {
                Log.w(TAG, "validate video", t);
            } finally {
                try { r.release(); } catch (Throwable ignored) {}
            }
        }
        if (!anyOk) {
            demoReport.setText(R.string.demo_no_media);
            demoOkChip.setVisibility(View.GONE);
            return;
        }
        if (warn) report.append(getString(R.string.demo_warn_resolution));
        demoReport.setText(report.toString().trim());
        demoOkChip.setVisibility(View.VISIBLE);

        new AlertDialog.Builder(this)
                .setTitle(R.string.demo_validate)
                .setMessage(report.toString().trim())
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    // ---------------------------------------------------------------------
    // Permissions / storage
    // ---------------------------------------------------------------------

    private boolean hasStoragePermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        // On Android 11+ we need "All files access" to write into /DCIM/Camera1/
        // because that's where the Xposed hook reads. Scoped-media APIs are unsuitable
        // since the hook uses plain File I/O.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestStoragePermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        new AlertDialog.Builder(this)
                .setTitle(R.string.permission_lack_warn)
                .setMessage(R.string.permission_description)
                .setNegativeButton(R.string.negative, (d, w) ->
                        Toast.makeText(this, R.string.permission_lack_warn, Toast.LENGTH_SHORT).show())
                .setPositiveButton(R.string.positive, (d, w) -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        try {
                            Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                            i.setData(Uri.parse("package:" + getPackageName()));
                            startActivity(i);
                        } catch (Throwable t) {
                            startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                        }
                    } else {
                        storagePermsLauncher.launch(new String[]{
                                Manifest.permission.READ_EXTERNAL_STORAGE,
                                Manifest.permission.WRITE_EXTERNAL_STORAGE
                        });
                    }
                })
                .show();
    }

    // ---------------------------------------------------------------------
    // Toggle switches backed by marker files (legacy contract)
    // ---------------------------------------------------------------------

    private void wireSwitchesToFiles() {
        bindMarker(force_show_switch, "force_show.jpg");
        bindMarker(disable_switch, "disable.jpg");
        bindMarker(play_sound_switch, "no-silent.jpg");
        bindMarker(force_private_dir, "private_dir.jpg");
        bindMarker(disable_toast_switch, "no_toast.jpg");
    }

    private void bindMarker(MaterialSwitch sw, String fileName) {
        sw.setOnCheckedChangeListener((view, checked) -> {
            if (!view.isPressed()) return;
            if (!hasStoragePermission()) {
                requestStoragePermission();
                return;
            }
            File marker = new File(Environment.getExternalStorageDirectory()
                    + "/DCIM/Camera1/" + fileName);
            if (marker.exists() != checked) {
                if (checked) {
                    try {
                        //noinspection ResultOfMethodCallIgnored
                        marker.getParentFile().mkdirs();
                        marker.createNewFile();
                    } catch (IOException e) {
                        Log.w(TAG, "Failed to create " + marker, e);
                    }
                } else {
                    //noinspection ResultOfMethodCallIgnored
                    marker.delete();
                }
            }
            syncStateWithFiles();
        });
    }

    // ---------------------------------------------------------------------
    // Picker handling
    // ---------------------------------------------------------------------

    private void importMedia(@NonNull Uri uri, boolean isImage) {
        File dst = isImage ? MediaPaths.getImageTarget(this) : MediaPaths.getVideoTarget(this);
        ioExecutor.execute(() -> {
            try {
                long bytes = MediaPaths.copyUriToFile(this, uri, dst);
                prefs.edit()
                        .putString(isImage ? VCAMApp.KEY_LAST_IMAGE_URI : VCAMApp.KEY_LAST_VIDEO_URI,
                                uri.toString())
                        .apply();
                runOnUiThread(() -> {
                    Toast.makeText(this,
                            getString(R.string.media_imported_format, MediaPaths.humanBytes(bytes)),
                            Toast.LENGTH_SHORT).show();
                    syncStateWithFiles();
                });
            } catch (Exception e) {
                Log.w(TAG, "Import failed (dst=" + dst + ")", e);
                runOnUiThread(() -> {
                    if (!hasStoragePermission()) {
                        Toast.makeText(this, R.string.media_import_failed_no_perm, Toast.LENGTH_LONG).show();
                        requestStoragePermission();
                    } else {
                        Toast.makeText(this,
                                getString(R.string.media_import_failed_format, e.getMessage()),
                                Toast.LENGTH_LONG).show();
                    }
                    syncStateWithFiles();
                });
            }
        });
    }

    private void clearMedia() {
        File img = MediaPaths.getImageTarget(this);
        File vid = MediaPaths.getVideoTarget(this);
        //noinspection ResultOfMethodCallIgnored
        if (img.exists()) img.delete();
        //noinspection ResultOfMethodCallIgnored
        if (vid.exists()) vid.delete();
        prefs.edit()
                .remove(VCAMApp.KEY_LAST_IMAGE_URI)
                .remove(VCAMApp.KEY_LAST_VIDEO_URI)
                .apply();
        Toast.makeText(this, R.string.cleared, Toast.LENGTH_SHORT).show();
        syncStateWithFiles();
    }

    private void openSystemCamera() {
        Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        if (i.resolveActivity(getPackageManager()) != null) {
            startActivity(i);
        } else {
            Toast.makeText(this, R.string.test_camera_failed, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Launch the in-manager {@link CameraVerifyActivity} which opens Camera2
     * inside this process so the VCAM hook — always scoped to its own
     * manager — applies and the user can visually confirm injection without
     * needing to add the external system camera to LSPosed scope.
     */
    private void launchVerifier() {
        startActivity(new Intent(this, CameraVerifyActivity.class));
    }

    private void savePaths() {
        String pkg = safe(pkgFilter.getText());
        String img = safe(customImagePath.getText());
        String vid = safe(customVideoPath.getText());
        if (!TextUtils.isEmpty(img) && !isSaneAbsolutePath(img)) {
            Toast.makeText(this, R.string.invalid_path, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!TextUtils.isEmpty(vid) && !isSaneAbsolutePath(vid)) {
            Toast.makeText(this, R.string.invalid_path, Toast.LENGTH_SHORT).show();
            return;
        }
        prefs.edit()
                .putString(VCAMApp.KEY_PACKAGE_FILTER, pkg)
                .putString(VCAMApp.KEY_CUSTOM_IMAGE_PATH, img)
                .putString(VCAMApp.KEY_CUSTOM_VIDEO_PATH, vid)
                .apply();
        Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show();
        syncStateWithFiles();
    }

    private static String safe(@Nullable CharSequence cs) {
        return cs == null ? "" : cs.toString().trim();
    }

    /**
     * Minimal guardrail: absolute, no traversal segments, no nulls.
     * We intentionally keep validation lightweight because Xposed usage
     * legitimately spans many vendor-specific paths.
     */
    private static boolean isSaneAbsolutePath(@NonNull String path) {
        if (!path.startsWith("/")) return false;
        if (path.contains("\0")) return false;
        for (String seg : path.split("/")) {
            if ("..".equals(seg)) return false;
        }
        return true;
    }

    // ---------------------------------------------------------------------
    // Status sync
    // ---------------------------------------------------------------------

    private void syncStateWithFiles() {
        Log.d(TAG, "[sync] syncing state with files");
        if (hasStoragePermission()) {
            MediaPaths.defaultDir();
        }

        chipRes.setVisibility(View.GONE);
        chipRes.setText("");
        chipSize.setVisibility(View.GONE);
        chipSize.setText("");

        // marker switches
        File base = new File(Environment.getExternalStorageDirectory() + "/DCIM/Camera1/");
        disable_switch.setChecked(new File(base, "disable.jpg").exists());
        force_show_switch.setChecked(new File(base, "force_show.jpg").exists());
        play_sound_switch.setChecked(new File(base, "no-silent.jpg").exists());
        force_private_dir.setChecked(new File(base, "private_dir.jpg").exists());
        disable_toast_switch.setChecked(new File(base, "no_toast.jpg").exists());

        // module status chip
        if (MediaPaths.isXposedLikelyActive()) {
            chipModule.setText(R.string.chip_module_enabled);
            chipModule.setChipIconResource(android.R.drawable.checkbox_on_background);
        } else {
            chipModule.setText(R.string.chip_module_unknown);
            chipModule.setChipIcon(null);
        }

        // image & video chips/preview
        File img = MediaPaths.getImageTarget(this);
        File vid = MediaPaths.getVideoTarget(this);
        boolean hasImage = img.exists() && img.length() > 0;
        boolean hasVideo = vid.exists() && vid.length() > 0;
        chipImage.setText(hasImage ? R.string.chip_image_loaded : R.string.chip_image_none);
        chipVideo.setText(hasVideo ? R.string.chip_video_loaded : R.string.chip_video_none);
        preview.setImageResource(android.R.drawable.ic_menu_gallery);

        final int requestId = ++syncRequestId;
        ioExecutor.execute(() -> {
            MediaPreviewState state = loadMediaPreviewState(img, vid);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || requestId != syncRequestId) return;
                applyMediaPreviewState(state);
            });
        });
    }

    private MediaPreviewState loadMediaPreviewState(File img, File vid) {
        MediaPreviewState state = new MediaPreviewState();
        if (img.exists() && img.length() > 0) {
            state.thumb = MediaPaths.decodeImageThumb(img, 800);
            state.resolution = MediaPaths.imageResolution(img);
            state.sizeLabel = MediaPaths.humanBytes(img.length());
        }
        if ((state.thumb == null) && vid.exists() && vid.length() > 0) {
            state.thumb = MediaPaths.decodeVideoFrame(vid);
            state.resolution = MediaPaths.videoResolution(vid);
            state.sizeLabel = MediaPaths.humanBytes(vid.length());
        }
        return state;
    }

    private void applyMediaPreviewState(MediaPreviewState state) {
        if (state.resolution != null) {
            chipRes.setVisibility(View.VISIBLE);
            chipRes.setText(getString(R.string.chip_resolution_format,
                    state.resolution[0], state.resolution[1]));
        } else {
            chipRes.setVisibility(View.GONE);
            chipRes.setText("");
        }

        if (!TextUtils.isEmpty(state.sizeLabel)) {
            chipSize.setVisibility(View.VISIBLE);
            chipSize.setText(state.sizeLabel);
        } else {
            chipSize.setVisibility(View.GONE);
            chipSize.setText("");
        }

        if (state.thumb != null) {
            preview.setImageBitmap(state.thumb);
        } else {
            preview.setImageResource(android.R.drawable.ic_menu_gallery);
        }
    }

    private static final class MediaPreviewState {
        private Bitmap thumb;
        private int[] resolution;
        private String sizeLabel;
    }
}
