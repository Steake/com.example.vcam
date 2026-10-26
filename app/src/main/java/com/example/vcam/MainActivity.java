package com.example.vcam;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
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
import android.view.Menu;
import android.view.MenuItem;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.EditText;
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
import com.google.android.material.chip.Chip;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;

import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Material 3 companion UI for the VCAM Xposed module.
 *
 * Includes:
 * - First-class aspect ratio & spatial transform preview (Fit/Fill/Stretch, Rotation, Mirror)
 * - Source mode switcher (Media File, Diagnostic Test Pattern, Live QR Code, Network Stream URL)
 * - Hardware Camera Sensor Profiler & Anti-Detection controls
 * - Floating In-App Overlay HUD, Virtual Microphone, Intent Interception & Pass-Through toggles
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

    // Hardware & HUD switches
    private MaterialSwitch switchOverlayHud;
    private MaterialSwitch switchMetadataPatch;
    private MaterialSwitch switchSensorGrain;
    private MaterialSwitch switchInterceptIntents;
    private MaterialSwitch switchVirtualMic;
    private MaterialSwitch switchPassthrough;
    private TextView sensorProfileSummary;
    private TextView transformStatusLine;

    private Chip chipModule, chipNdkStatus, chipImage, chipVideo, chipRes, chipSize;
    private com.google.android.material.button.MaterialButton btnAspectFit;
    private com.google.android.material.button.MaterialButton btnAspectFill;
    private com.google.android.material.button.MaterialButton btnAspectStretch;
    private com.google.android.material.button.MaterialButton btnRotateAuto;
    private com.google.android.material.button.MaterialButton btnRotate90;
    private com.google.android.material.button.MaterialButton btnMirrorH;
    private com.google.android.material.button.MaterialButton btnSrcMedia;
    private com.google.android.material.button.MaterialButton btnSrcPattern;
    private com.google.android.material.button.MaterialButton btnSrcQr;
    private com.google.android.material.button.MaterialButton btnSrcStream;
    private ImageView preview;
    private TextInputEditText pkgFilter, customImagePath, customVideoPath;

    // Demo card (Live Preview)
    private TextureView demoTexture;
    private Chip demoOkChip;
    private TextView demoReport;
    private MediaPlayer demoPlayer;
    private Handler demoHandler;
    private Runnable demoImageLoop;
    private Surface demoSurface;
    private int currentDemoVideoW = 1280;
    private int currentDemoVideoH = 720;

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

        // Switches
        force_show_switch = findViewById(R.id.switch1);
        disable_switch = findViewById(R.id.switch2);
        play_sound_switch = findViewById(R.id.switch3);
        force_private_dir = findViewById(R.id.switch4);
        disable_toast_switch = findViewById(R.id.switch5);
        loopSwitch = findViewById(R.id.switch_loop);
        muteSwitch = findViewById(R.id.switch_mute);

        // Hardware & HUD controls
        sensorProfileSummary = findViewById(R.id.sensor_profile_summary);
        transformStatusLine = findViewById(R.id.transform_status_line);
        switchOverlayHud = findViewById(R.id.switch_overlay_hud);
        switchMetadataPatch = findViewById(R.id.switch_metadata_patch);
        switchSensorGrain = findViewById(R.id.switch_sensor_grain);
        switchInterceptIntents = findViewById(R.id.switch_intercept_intents);
        switchVirtualMic = findViewById(R.id.switch_virtual_mic);
        switchPassthrough = findViewById(R.id.switch_passthrough);

        // Status chips + preview
        chipModule = findViewById(R.id.chip_module);
        chipNdkStatus = findViewById(R.id.chip_ndk_status);
        if (chipNdkStatus != null) {
            chipNdkStatus.setOnClickListener(v -> showDiagnosticsDialog());
        }
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

        loopSwitch.setOnCheckedChangeListener((b, c) -> {
            prefs.edit().putBoolean(VCAMApp.KEY_LOOP_VIDEO, c).apply();
            MediaMappings.notifyUpdated(this);
            Toast.makeText(this, c ? R.string.toast_loop_enabled : R.string.toast_loop_disabled, Toast.LENGTH_SHORT).show();
        });
        muteSwitch.setOnCheckedChangeListener((b, c) -> {
            prefs.edit().putBoolean(VCAMApp.KEY_MUTE_VIDEO, c).apply();
            if (play_sound_switch.isChecked() == c) {
                play_sound_switch.setChecked(!c);
            }
            if (demoPlayer != null) {
                try {
                    demoPlayer.setVolume(c ? 0f : 1f, c ? 0f : 1f);
                } catch (Throwable ignored) {}
            }
            MediaMappings.notifyUpdated(this);
            Toast.makeText(this, c ? R.string.toast_mute_enabled : R.string.toast_mute_disabled, Toast.LENGTH_SHORT).show();
        });

        wireTransformAndSourceStudio();
        wireHardwareAndHudCard();

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

        // Buttons — SAF GetContent + MediaLibrary + MediaProvider work with zero storage permissions!
        findViewById(R.id.btn_pick_image).setOnClickListener(v -> {
            if (hasStoragePermission()) MediaPaths.defaultDir();
            pickImageLauncher.launch("image/*");
        });
        findViewById(R.id.btn_pick_video).setOnClickListener(v -> {
            if (hasStoragePermission()) MediaPaths.defaultDir();
            pickVideoLauncher.launch("video/*");
        });
        if (chipImage != null) {
            chipImage.setOnClickListener(v -> {
                TransformConfig.updateSourceAndMediaTypeEverywhere(
                        this, MediaMappings.PKG_GLOBAL, MediaMappings.FACING_ANY,
                        TransformConfig.SOURCE_MEDIA, TransformConfig.MEDIA_IMAGE);
                MediaMappings.notifyUpdated(this);
                refreshTransformStatusUi();
                applyDemoTextureTransform();
                syncStateWithFiles();
                startDemoPlayback();
                Toast.makeText(this, "Active feed: Still Image", Toast.LENGTH_SHORT).show();
            });
        }
        if (chipVideo != null) {
            chipVideo.setOnClickListener(v -> {
                TransformConfig.updateSourceAndMediaTypeEverywhere(
                        this, MediaMappings.PKG_GLOBAL, MediaMappings.FACING_ANY,
                        TransformConfig.SOURCE_MEDIA, TransformConfig.MEDIA_VIDEO);
                MediaMappings.notifyUpdated(this);
                refreshTransformStatusUi();
                applyDemoTextureTransform();
                syncStateWithFiles();
                startDemoPlayback();
                Toast.makeText(this, "Active feed: Video Loop", Toast.LENGTH_SHORT).show();
            });
        }

        findViewById(R.id.btn_clear).setOnClickListener(v -> clearMedia());
        findViewById(R.id.btn_test).setOnClickListener(v -> launchVerifier());
        findViewById(R.id.btn_save_paths).setOnClickListener(v -> savePaths());

        findViewById(R.id.btn_open_library).setOnClickListener(v ->
                startActivity(new Intent(this, MediaLibraryActivity.class)));
        findViewById(R.id.btn_open_mapping).setOnClickListener(v ->
                startActivity(new Intent(this, PerAppMappingActivity.class)));
        findViewById(R.id.btn_open_defaults).setOnClickListener(v -> {
            Intent i = new Intent(this, AppMappingEditActivity.class);
            i.putExtra(AppMappingEditActivity.EXTRA_PKG, MediaMappings.PKG_GLOBAL);
            startActivity(i);
        });

        MediaMigration.migrateIfNeeded(this);

        findViewById(R.id.button).setOnClickListener(v -> {
            Uri uri = Uri.parse("https://github.com/Steake/com.example.vcam");
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        });
        findViewById(R.id.button2).setOnClickListener(v -> showDiagnosticsDialog());

        wireSwitchesToFiles();
        wireDemoCard();

        if (!prefs.getBoolean(VCAMApp.KEY_ONBOARDING_DONE, false)) {
            startActivity(new Intent(this, OnboardingActivity.class));
        }
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                if (checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                        != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1009);
                }
            } catch (Throwable ignored) {}
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        syncStateWithFiles();
        refreshTransformStatusUi();
        refreshSensorProfileUi();
        if (demoTexture != null && demoTexture.isAvailable()) {
            startDemoPlayback();
        }
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

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_verify_camera) {
            launchVerifier();
            return true;
        } else if (id == R.id.action_media_library) {
            startActivity(new Intent(this, MediaLibraryActivity.class));
            return true;
        } else if (id == R.id.action_per_app_mapping) {
            startActivity(new Intent(this, PerAppMappingActivity.class));
            return true;
        } else if (id == R.id.action_global_defaults) {
            Intent i = new Intent(this, AppMappingEditActivity.class);
            i.putExtra(AppMappingEditActivity.EXTRA_PKG, MediaMappings.PKG_GLOBAL);
            startActivity(i);
            return true;
        } else if (id == R.id.action_injected_ui) {
            startActivity(new Intent(this, InjectedConfigActivity.class));
            return true;
        } else if (id == R.id.action_diagnostics) {
            showDiagnosticsDialog();
            return true;
        } else if (id == R.id.action_reset_all) {
            confirmResetAll();
            return true;
        } else if (id == R.id.action_onboarding) {
            startActivity(new Intent(this, OnboardingActivity.class));
            return true;
        } else if (id == R.id.action_repo) {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Steake/com.example.vcam")));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void showDiagnosticsDialog() {
        StringBuilder diag = new StringBuilder();
        diag.append("• Version: ").append(BuildConfig.VERSION_NAME).append(" (code ").append(BuildConfig.VERSION_CODE).append(")\n");
        diag.append("• LSPosed / Xposed Hook: ").append(MediaPaths.isXposedLikelyActive() ? "ACTIVE" : "STANDBY (Enable in LSPosed)").append("\n");
        diag.append("• Native NDK Engine (libvcam_ndk.so): ").append(NdkHookEngine.isNativeLoaded() ? "LOADED (ELF PLT/GOT + dlsym + /proc cloak)" : "ARMED (Loads in target process)").append("\n");
        diag.append("• Direct-Boot / SystemUI Guard: ARMED (system_server & SystemUI excluded)\n");
        diag.append("• Sensor Attestation & IMU Correlator: ARMED (Persona 2.60 / Sumsub 1.47)\n");
        diag.append("• Storage Mode: ").append(hasStoragePermission() ? "All-Files + ContentProvider" : "Zero-Permission ContentProvider").append("\n");
        diag.append("• Target SDK: ").append(getApplicationInfo().targetSdkVersion).append(" (Android ").append(Build.VERSION.RELEASE).append(")\n");

        File img = MediaPaths.resolveEffectiveImageFile(this);
        File vid = MediaPaths.resolveEffectiveVideoFile(this);
        diag.append("• Effective Image: ").append(img.exists() && img.length() > 0 ? (img.getName() + " (" + MediaPaths.humanBytes(img.length()) + ")") : "None").append("\n");
        diag.append("• Effective Video: ").append(vid.exists() && vid.length() > 0 ? (vid.getName() + " (" + MediaPaths.humanBytes(vid.length()) + ")") : "None").append("\n");

        TransformConfig cfg = TransformConfig.loadGlobal(this);
        diag.append("• Global Source Mode: ").append(cfg.sourceMode).append("\n");
        diag.append("• Aspect Ratio: ").append(cfg.aspectMode).append("\n");
        diag.append("• HUD Enabled: ").append(cfg.overlayEnabled ? "YES" : "NO").append("\n");
        diag.append("• Sensor Grain: ").append(cfg.sensorGrainEnabled ? "YES" : "NO").append("\n");
        diag.append("• EXIF Patch: ").append(prefs.getBoolean(VCAMApp.KEY_METADATA_PATCH_ENABLED, true) ? "YES" : "NO").append("\n");
        diag.append("• Anti-Detection Cloaking: ACTIVE (Stack + /proc/self/maps + PackageManager)\n");

        new AlertDialog.Builder(this)
                .setTitle(R.string.menu_diagnostics)
                .setMessage(diag.toString())
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void confirmResetAll() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.reset_all_confirm_title)
                .setMessage(R.string.reset_all_confirm_body)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    clearMedia();
                    prefs.edit().clear().apply();
                    syncStateWithFiles();
                    refreshTransformStatusUi();
                    MediaMappings.notifyUpdated(this);
                    Toast.makeText(this, R.string.reset_all_done, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ---------------------------------------------------------------------
    // Aspect Ratio, Rotation, Mirror & Source Mode Studio
    // ---------------------------------------------------------------------
    private void wireTransformAndSourceStudio() {
        btnAspectFit = findViewById(R.id.btn_aspect_fit);
        btnAspectFill = findViewById(R.id.btn_aspect_fill);
        btnAspectStretch = findViewById(R.id.btn_aspect_stretch);
        btnRotateAuto = findViewById(R.id.btn_rotate_auto);
        btnRotate90 = findViewById(R.id.btn_rotate_90);
        btnMirrorH = findViewById(R.id.btn_mirror_h);
        btnSrcMedia = findViewById(R.id.btn_src_media);
        btnSrcPattern = findViewById(R.id.btn_src_pattern);
        btnSrcQr = findViewById(R.id.btn_src_qr);
        btnSrcStream = findViewById(R.id.btn_src_stream);

        btnAspectFit.setOnClickListener(v -> {
            TransformConfig cfg = TransformConfig.loadGlobal(this);
            cfg.aspectMode = TransformConfig.ASPECT_FIT;
            saveGlobalTransformAndRefresh(cfg);
        });
        findViewById(R.id.btn_aspect_fill).setOnClickListener(v -> {
            TransformConfig cfg = TransformConfig.loadGlobal(this);
            cfg.aspectMode = TransformConfig.ASPECT_FILL;
            saveGlobalTransformAndRefresh(cfg);
        });
        findViewById(R.id.btn_aspect_stretch).setOnClickListener(v -> {
            TransformConfig cfg = TransformConfig.loadGlobal(this);
            cfg.aspectMode = TransformConfig.ASPECT_STRETCH;
            saveGlobalTransformAndRefresh(cfg);
        });
        findViewById(R.id.btn_rotate_auto).setOnClickListener(v -> {
            TransformConfig cfg = TransformConfig.loadGlobal(this);
            cfg.setRotation(TransformConfig.ROTATION_AUTO);
            saveGlobalTransformAndRefresh(cfg);
        });
        findViewById(R.id.btn_rotate_90).setOnClickListener(v -> {
            TransformConfig cfg = TransformConfig.loadGlobal(this);
            int cur = cfg.rotationMode == TransformConfig.ROTATION_AUTO ? 0 : cfg.rotationMode;
            cfg.setRotation((cur + 90) % 360);
            saveGlobalTransformAndRefresh(cfg);
        });
        findViewById(R.id.btn_mirror_h).setOnClickListener(v -> {
            TransformConfig cfg = TransformConfig.loadGlobal(this);
            cfg.mirrorH = !cfg.mirrorH;
            saveGlobalTransformAndRefresh(cfg);
        });
        findViewById(R.id.btn_reset_transform).setOnClickListener(v -> {
            TransformConfig cfg = TransformConfig.loadGlobal(this);
            cfg.resetSpatial();
            cfg.resetPhotometric();
            saveGlobalTransformAndRefresh(cfg);
        });

        findViewById(R.id.btn_src_media).setOnClickListener(v -> {
            TransformConfig cfg = TransformConfig.loadGlobal(this);
            cfg.sourceMode = TransformConfig.SOURCE_MEDIA;
            TransformConfig.updateSourceAndMediaTypeEverywhere(
                    this, MediaMappings.PKG_GLOBAL, MediaMappings.FACING_ANY,
                    TransformConfig.SOURCE_MEDIA, cfg.preferredMediaType);
            saveGlobalTransformAndRefresh(cfg);
            syncStateWithFiles();
            startDemoPlayback();
        });
        findViewById(R.id.btn_src_pattern).setOnClickListener(v -> {
            TransformConfig cfg = TransformConfig.loadGlobal(this);
            cfg.sourceMode = TransformConfig.SOURCE_TEST_PATTERN;
            TransformConfig.updateSourceAndMediaTypeEverywhere(
                    this, MediaMappings.PKG_GLOBAL, MediaMappings.FACING_ANY,
                    TransformConfig.SOURCE_TEST_PATTERN, cfg.preferredMediaType);
            saveGlobalTransformAndRefresh(cfg);
            syncStateWithFiles();
            startDemoPlayback();
        });
        findViewById(R.id.btn_src_qr).setOnClickListener(v -> promptQrPayloadDialog());
        findViewById(R.id.btn_src_stream).setOnClickListener(v -> promptStreamUrlDialog());
    }

    private void promptQrPayloadDialog() {
        TransformConfig cfg = TransformConfig.loadGlobal(this);
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(cfg.qrPayload);
        input.setHint("Enter URL or text for Live QR");
        new AlertDialog.Builder(this)
                .setTitle("Live QR Code Source")
                .setView(input)
                .setPositiveButton(R.string.positive, (d, w) -> {
                    TransformConfig updated = TransformConfig.loadGlobal(this);
                    String txt = input.getText() != null ? input.getText().toString().trim() : "";
                    if (!txt.isEmpty()) {
                        updated.qrPayload = txt;
                    }
                    updated.sourceMode = TransformConfig.SOURCE_QR;
                    TransformConfig.updateSourceAndMediaTypeEverywhere(
                            this, MediaMappings.PKG_GLOBAL, MediaMappings.FACING_ANY,
                            TransformConfig.SOURCE_QR, updated.preferredMediaType);
                    saveGlobalTransformAndRefresh(updated);
                    startDemoPlayback();
                })
                .setNegativeButton(R.string.negative, null)
                .show();
    }

    private void promptStreamUrlDialog() {
        TransformConfig cfg = TransformConfig.loadGlobal(this);
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(cfg.streamUrl);
        input.setHint("http:// or rtsp:// stream URL");
        new AlertDialog.Builder(this)
                .setTitle("Network Stream Source URL")
                .setView(input)
                .setPositiveButton(R.string.positive, (d, w) -> {
                    TransformConfig updated = TransformConfig.loadGlobal(this);
                    String url = input.getText() != null ? input.getText().toString().trim() : "";
                    updated.streamUrl = url;
                    updated.sourceMode = url.isEmpty()
                            ? TransformConfig.SOURCE_MEDIA
                            : TransformConfig.SOURCE_STREAM;
                    TransformConfig.updateSourceAndMediaTypeEverywhere(
                            this, MediaMappings.PKG_GLOBAL, MediaMappings.FACING_ANY,
                            updated.sourceMode, updated.preferredMediaType);
                    saveGlobalTransformAndRefresh(updated);
                    startDemoPlayback();
                })
                .setNegativeButton(R.string.negative, null)
                .show();
    }

    private void saveGlobalTransformAndRefresh(@NonNull TransformConfig cfg) {
        TransformConfig.saveGlobal(this, cfg);
        MediaMappings.notifyUpdated(this);
        refreshTransformStatusUi();
        applyDemoTextureTransform();
    }

    private void styleToggleButton(@Nullable com.google.android.material.button.MaterialButton btn, boolean active) {
        if (btn == null) return;
        int primaryContainer = com.google.android.material.color.MaterialColors.getColor(
                btn, com.google.android.material.R.attr.colorPrimaryContainer, 0xFF312E81);
        int onPrimaryContainer = com.google.android.material.color.MaterialColors.getColor(
                btn, com.google.android.material.R.attr.colorOnPrimaryContainer, 0xFFE0E7FF);
        int surface = com.google.android.material.color.MaterialColors.getColor(
                btn, com.google.android.material.R.attr.colorSurface, 0xFF0B0F17);
        int onSurface = com.google.android.material.color.MaterialColors.getColor(
                btn, com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF94A3B8);
        int outline = com.google.android.material.color.MaterialColors.getColor(
                btn, com.google.android.material.R.attr.colorOutline, 0xFF475569);
        int primary = com.google.android.material.color.MaterialColors.getColor(
                btn, com.google.android.material.R.attr.colorPrimary, 0xFF818CF8);

        btn.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                active ? primaryContainer : surface));
        btn.setTextColor(active ? onPrimaryContainer : onSurface);
        int strokePx = (int) (1.5f * getResources().getDisplayMetrics().density);
        btn.setStrokeWidth(strokePx);
        btn.setStrokeColor(android.content.res.ColorStateList.valueOf(
                active ? primary : outline));
    }

    private void refreshTransformStatusUi() {
        TransformConfig cfg = TransformConfig.loadGlobal(this);
        styleToggleButton(btnAspectFit, TransformConfig.ASPECT_FIT.equals(cfg.aspectMode));
        styleToggleButton(btnAspectFill, TransformConfig.ASPECT_FILL.equals(cfg.aspectMode));
        styleToggleButton(btnAspectStretch, TransformConfig.ASPECT_STRETCH.equals(cfg.aspectMode));
        styleToggleButton(btnRotateAuto, cfg.rotationDegrees == TransformConfig.ROTATION_AUTO);
        styleToggleButton(btnRotate90, cfg.rotationDegrees != TransformConfig.ROTATION_AUTO);
        if (btnRotate90 != null) {
            btnRotate90.setText(cfg.rotationDegrees == TransformConfig.ROTATION_AUTO
                    ? "Rotate +90°" : ("Rot " + cfg.rotationDegrees + "°"));
        }
        styleToggleButton(btnMirrorH, cfg.mirrorH);
        styleToggleButton(btnSrcMedia, TransformConfig.SOURCE_MEDIA.equals(cfg.sourceMode));
        styleToggleButton(btnSrcPattern, TransformConfig.SOURCE_TEST_PATTERN.equals(cfg.sourceMode));
        styleToggleButton(btnSrcQr, TransformConfig.SOURCE_QR.equals(cfg.sourceMode));
        styleToggleButton(btnSrcStream, TransformConfig.SOURCE_STREAM.equals(cfg.sourceMode));

        if (transformStatusLine == null) return;
        String rotStr = cfg.rotationDegrees == TransformConfig.ROTATION_AUTO
                ? "Auto" : (cfg.rotationDegrees + "°");
        String srcStr = TransformConfig.SOURCE_MEDIA.equals(cfg.sourceMode)
                ? ("MEDIA(" + cfg.preferredMediaType.toUpperCase(Locale.US) + ")")
                : cfg.sourceMode.toUpperCase(Locale.US);
        transformStatusLine.setText(String.format(Locale.US,
                "Active: Aspect=%s • Rot=%s • Zoom=%.2fx • MirrorH=%s • Source=%s",
                cfg.aspectMode.toUpperCase(Locale.US),
                rotStr,
                cfg.zoom,
                cfg.mirrorH ? "ON" : "OFF",
                srcStr));
    }

    // ---------------------------------------------------------------------
    // Hardware Sensor Profile & HUD Card
    // ---------------------------------------------------------------------
    private void wireHardwareAndHudCard() {
        switchOverlayHud.setChecked(prefs.getBoolean(VCAMApp.KEY_OVERLAY_HUD_ENABLED, false));
        switchMetadataPatch.setChecked(prefs.getBoolean(VCAMApp.KEY_METADATA_PATCH_ENABLED, true));
        switchSensorGrain.setChecked(prefs.getBoolean(VCAMApp.KEY_SENSOR_GRAIN_ENABLED, true));
        switchInterceptIntents.setChecked(prefs.getBoolean(VCAMApp.KEY_INTERCEPT_CAPTURE_INTENTS, true));
        switchVirtualMic.setChecked(prefs.getBoolean(VCAMApp.KEY_VIRTUAL_MIC_ENABLED, false));
        switchPassthrough.setChecked(prefs.getBoolean(VCAMApp.KEY_PASSTHROUGH_MODE, false));

        switchOverlayHud.setOnCheckedChangeListener((b, c) -> {
            prefs.edit().putBoolean(VCAMApp.KEY_OVERLAY_HUD_ENABLED, c).apply();
            TransformConfig cfg = TransformConfig.loadGlobal(this);
            cfg.overlayEnabled = c;
            TransformConfig.saveGlobal(this, cfg);
            MediaMappings.notifyUpdated(this);
            Toast.makeText(this, c ? R.string.toast_hud_enabled : R.string.toast_hud_disabled, Toast.LENGTH_SHORT).show();
        });
        switchMetadataPatch.setOnCheckedChangeListener((b, c) -> {
            prefs.edit().putBoolean(VCAMApp.KEY_METADATA_PATCH_ENABLED, c).apply();
            MediaMappings.notifyUpdated(this);
            Toast.makeText(this, c ? R.string.toast_metadata_patch_enabled : R.string.toast_metadata_patch_disabled, Toast.LENGTH_SHORT).show();
        });
        switchSensorGrain.setOnCheckedChangeListener((b, c) -> {
            prefs.edit().putBoolean(VCAMApp.KEY_SENSOR_GRAIN_ENABLED, c).apply();
            TransformConfig cfg = TransformConfig.loadGlobal(this);
            cfg.sensorGrainEnabled = c;
            TransformConfig.saveGlobal(this, cfg);
            MediaMappings.notifyUpdated(this);
            Toast.makeText(this, c ? R.string.toast_sensor_grain_enabled : R.string.toast_sensor_grain_disabled, Toast.LENGTH_SHORT).show();
        });
        switchInterceptIntents.setOnCheckedChangeListener((b, c) -> {
            prefs.edit().putBoolean(VCAMApp.KEY_INTERCEPT_CAPTURE_INTENTS, c).apply();
            MediaMappings.notifyUpdated(this);
            Toast.makeText(this, c ? R.string.toast_intercept_intents_enabled : R.string.toast_intercept_intents_disabled, Toast.LENGTH_SHORT).show();
        });
        switchVirtualMic.setOnCheckedChangeListener((b, c) -> {
            prefs.edit().putBoolean(VCAMApp.KEY_VIRTUAL_MIC_ENABLED, c).apply();
            MediaMappings.notifyUpdated(this);
            Toast.makeText(this, c ? R.string.toast_virtual_mic_enabled : R.string.toast_virtual_mic_disabled, Toast.LENGTH_SHORT).show();
        });
        switchPassthrough.setOnCheckedChangeListener((b, c) -> {
            TransformConfig.updatePassthroughEverywhere(this, c);
            MediaMappings.notifyUpdated(this);
            Toast.makeText(this, c ? R.string.toast_passthrough_enabled : R.string.toast_passthrough_disabled, Toast.LENGTH_SHORT).show();
        });

        findViewById(R.id.btn_profile_sensors).setOnClickListener(v -> {
            int count = SensorProfiler.profileAndSaveAllCameras(this);
            refreshSensorProfileUi();
            MediaMappings.notifyUpdated(this);
            Toast.makeText(this,
                    "Profiled " + count + " hardware camera lens(es)",
                    Toast.LENGTH_SHORT).show();
        });
    }

    private void refreshSensorProfileUi() {
        if (sensorProfileSummary == null) return;
        SensorProfiler.LensProfile back = SensorProfiler.loadProfile(this, MediaMappings.FACING_BACK);
        SensorProfiler.LensProfile front = SensorProfiler.loadProfile(this, MediaMappings.FACING_FRONT);
        sensorProfileSummary.setText("Back Lens: " + back.summary()
                + "\nFront Lens: " + front.summary());
    }

    // ---------------------------------------------------------------------
    // Demo card: exercises the same MediaPlayer / bitmap pipeline the hook
    // uses so the user can confirm media is decodable and aspect ratio is
    // respected before launching a host app.
    // ---------------------------------------------------------------------
    private void wireDemoCard() {
        demoOkChip = findViewById(R.id.demo_chip_ok);
        demoReport = findViewById(R.id.demo_report);
        demoHandler = new Handler(Looper.getMainLooper());

        findViewById(R.id.demo_btn_validate).setOnClickListener(v -> validateStagedMedia());

        android.widget.FrameLayout container = findViewById(R.id.demo_texture_container);
        try {
            demoTexture = new TextureView(this);
            container.addView(demoTexture,
                    new android.widget.FrameLayout.LayoutParams(
                            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                            android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
        } catch (Exception t) {
            Log.w(TAG, "TextureView unavailable, disabling live demo preview", t);
            demoTexture = null;
        }

        if (demoTexture == null) return;
        demoTexture.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(@NonNull SurfaceTexture s, int w, int h) {
                startDemoPlayback();
            }

            @Override
            public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture s, int w, int h) {
                applyDemoTextureTransform();
            }

            @Override
            public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture s) {
                stopDemo();
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(@NonNull SurfaceTexture s) {
            }
        });
    }

    private void applyDemoTextureTransform() {
        if (demoTexture == null) return;
        TransformConfig cfg = TransformConfig.loadGlobal(this);
        if (demoPlayer != null) {
            FrameTransformPipeline.applyTextureViewTransform(
                    demoTexture, currentDemoVideoW, currentDemoVideoH, cfg);
        } else {
            // For Canvas-rendered bitmaps, reset TextureView matrix to identity because
            // FrameTransformPipeline.drawTransformedBitmap handles the matrix inside the Canvas
            demoTexture.setTransform(null);
        }
    }

    private void startDemoPlayback() {
        stopDemo();
        if (demoTexture == null) return;
        SurfaceTexture tex = demoTexture.getSurfaceTexture();
        if (tex == null) return;
        demoSurface = new Surface(tex);

        TransformConfig cfg = TransformConfig.loadGlobal(this);
        if (TransformConfig.SOURCE_TEST_PATTERN.equals(cfg.sourceMode)
                || TransformConfig.SOURCE_QR.equals(cfg.sourceMode)) {
            demoTexture.setTransform(null);
            startSyntheticDemoLoop();
            return;
        }

        final File vid = MediaPaths.resolveEffectiveVideoFile(this);
        final File img = MediaPaths.resolveEffectiveImageFile(this);
        final boolean isStream = TransformConfig.SOURCE_STREAM.equals(cfg.sourceMode)
                && !cfg.streamUrl.isEmpty();
        final boolean hasVid = vid.exists() && vid.length() > 0;
        final boolean hasImg = img.exists() && img.length() > 0;
        final boolean preferVideo = hasVid && (!TransformConfig.MEDIA_IMAGE.equals(cfg.preferredMediaType) || !hasImg);

        if (isStream || preferVideo) {
            if (!isStream) {
                int[] vRes = MediaPaths.videoResolution(vid);
                if (vRes != null) {
                    currentDemoVideoW = vRes[0];
                    currentDemoVideoH = vRes[1];
                }
            }
            final MediaPlayer player = new MediaPlayer();
            demoPlayer = player;
            player.setSurface(demoSurface);
            player.setLooping(prefs.getBoolean(VCAMApp.KEY_LOOP_VIDEO, true));
            if (prefs.getBoolean(VCAMApp.KEY_MUTE_VIDEO, false)) {
                player.setVolume(0f, 0f);
            }
            player.setOnVideoSizeChangedListener((mp, width, height) -> {
                if (width > 0 && height > 0) {
                    currentDemoVideoW = width;
                    currentDemoVideoH = height;
                    applyDemoTextureTransform();
                }
            });
            player.setOnPreparedListener(mp -> {
                if (demoPlayer != mp) return;
                try {
                    applyDemoTextureTransform();
                    mp.start();
                } catch (IllegalStateException e) {
                    Log.w(TAG, "demo video start skipped after lifecycle cleanup", e);
                }
            });
            try {
                if (isStream) {
                    player.setDataSource(cfg.streamUrl);
                } else {
                    player.setDataSource(vid.getAbsolutePath());
                }
                player.prepareAsync();
            } catch (IOException e) {
                Log.w(TAG, "demo video prepare failed", e);
            }
        } else if (img.exists() && img.length() > 0) {
            demoTexture.setTransform(null);
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
        } else {
            // No staged media yet: show diagnostic test pattern so user sees live aspect-accurate preview
            demoTexture.setTransform(null);
            startSyntheticDemoLoop();
        }
    }

    private void startImageLoop(@NonNull Bitmap bmp) {
        demoImageLoop = new Runnable() {
            @Override
            public void run() {
                try {
                    Surface s = demoSurface;
                    if (s == null || !s.isValid()) return;
                    TransformConfig cfg = TransformConfig.loadGlobal(MainActivity.this);
                    Canvas c = s.lockCanvas(null);
                    if (c != null) {
                        FrameTransformPipeline.drawTransformedBitmap(
                                c, bmp, c.getWidth(), c.getHeight(), cfg, 0, 0);
                        s.unlockCanvasAndPost(c);
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "demo image loop", t);
                } finally {
                    if (demoHandler != null && demoImageLoop == this) {
                        demoHandler.postDelayed(this, 66L);
                    }
                }
            }
        };
        demoHandler.post(demoImageLoop);
    }

    private void startSyntheticDemoLoop() {
        demoImageLoop = new Runnable() {
            @Override
            public void run() {
                try {
                    Surface s = demoSurface;
                    if (s == null || !s.isValid()) return;
                    TransformConfig cfg = TransformConfig.loadGlobal(MainActivity.this);
                    Canvas c = s.lockCanvas(null);
                    if (c != null) {
                        int w = Math.max(320, c.getWidth());
                        int h = Math.max(240, c.getHeight());
                        Bitmap syn = TransformConfig.SOURCE_QR.equals(cfg.sourceMode)
                                ? SyntheticFrameGenerator.renderQrFrame(w, h, cfg.qrPayload)
                                : SyntheticFrameGenerator.renderTestPattern(
                                        w, h, getPackageName(), "preview", 15f, cfg);
                        FrameTransformPipeline.drawTransformedBitmap(c, syn, w, h, cfg, 0, 0);
                        s.unlockCanvasAndPost(c);
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "synthetic demo loop", t);
                } finally {
                    if (demoHandler != null && demoImageLoop == this) {
                        demoHandler.postDelayed(this, 66L);
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
        File vid = MediaPaths.resolveEffectiveVideoFile(this);
        File img = MediaPaths.resolveEffectiveImageFile(this);
        StringBuilder report = new StringBuilder();
        boolean anyOk = false;
        boolean warn = false;

        if (img.exists() && img.length() > 0) {
            int[] res = MediaPaths.imageResolution(img);
            int exifRot = MediaPaths.imageExifRotation(img);
            String codec = "bitmap (EXIF " + exifRot + "°)";
            if (res != null) {
                report.append(getString(R.string.demo_report_image_format,
                        codec, res[0], res[1], MediaPaths.humanBytes(img.length()))).append('\n');
                anyOk = true;
                if (res[0] < 160 || res[1] < 120 || res[0] > 4096 || res[1] > 4096) warn = true;
            }
        }
        if (vid.exists() && vid.length() > 0) {
            MediaTranscoder.InspectionReport rep = MediaTranscoder.inspectVideo(vid);
            if (rep.valid) {
                report.append(rep.summary).append('\n');
                anyOk = true;
                if (rep.width < 160 || rep.height < 120 || rep.width > 4096 || rep.height > 4096) {
                    warn = true;
                }
            } else {
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
                } catch (Throwable t) {
                    Log.w(TAG, "validate video", t);
                } finally {
                    try { r.release(); } catch (Throwable ignored) {}
                }
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
    // Toggle switches backed by marker files (legacy contract + provider sync)
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
            if ("no-silent.jpg".equals(fileName)) {
                prefs.edit().putBoolean(VCAMApp.KEY_MUTE_VIDEO, !checked).apply();
                if (muteSwitch.isChecked() == checked) {
                    muteSwitch.setChecked(!checked);
                }
            } else if ("force_show.jpg".equals(fileName)) {
                prefs.edit().putBoolean("pref_force_show", checked).apply();
            } else if ("disable.jpg".equals(fileName)) {
                prefs.edit().putBoolean("pref_disable_module", checked).apply();
            } else if ("no_toast.jpg".equals(fileName)) {
                prefs.edit().putBoolean("pref_disable_toast", checked).apply();
            } else if ("private_dir.jpg".equals(fileName)) {
                prefs.edit().putBoolean("pref_private_dir", checked).apply();
            }
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
            MediaMappings.notifyUpdated(this);
            syncStateWithFiles();
            Toast.makeText(this, (checked ? "Set marker: " : "Cleared marker: ") + fileName, Toast.LENGTH_SHORT).show();
        });
    }

    // ---------------------------------------------------------------------
    // Picker handling (Synced with MediaLibrary & Global MediaMappings)
    // ---------------------------------------------------------------------
    private void importMedia(@NonNull Uri uri, boolean isImage) {
        File dst = isImage ? MediaPaths.getImageTarget(this) : MediaPaths.getVideoTarget(this);
        ioExecutor.execute(() -> {
            try {
                // Import into internal MediaLibrary FIRST and wire as the global MediaMappings default
                // so MediaProvider /resolve ALWAYS serves the newly picked file even when Android 11+
                // Scoped Storage restricts direct writes to /sdcard/DCIM/Camera1/!
                String type = isImage ? MediaLibrary.TYPE_IMAGE : MediaLibrary.TYPE_VIDEO;
                MediaLibrary.Entry libEntry = MediaLibrary.importFromUri(
                        this, uri, type, uri.getLastPathSegment());
                long bytes = libEntry != null ? libEntry.size : 0L;
                if (libEntry != null) {
                    MediaMappings.setOne(this, MediaMappings.PKG_GLOBAL,
                            MediaMappings.FACING_ANY, type, libEntry.uri().toString());
                }
                try {
                    long copied = MediaPaths.copyUriToFile(this, uri, dst);
                    if (copied > 0) bytes = copied;
                } catch (Throwable publicCopyErr) {
                    Log.w(TAG, "Public /DCIM/Camera1 copy skipped (using MediaLibrary provider): " + publicCopyErr);
                }
                if (bytes <= 0 && libEntry == null) {
                    throw new IOException("Unable to stage media into MediaLibrary or " + dst);
                }
                TransformConfig cfg = TransformConfig.loadGlobal(this);
                cfg.sourceMode = TransformConfig.SOURCE_MEDIA;
                cfg.preferredMediaType = isImage ? TransformConfig.MEDIA_IMAGE : TransformConfig.MEDIA_VIDEO;
                TransformConfig.saveGlobal(this, cfg);
                MediaMappings.notifyUpdated(this);

                final long finalBytes = bytes;
                prefs.edit()
                        .putString(isImage ? VCAMApp.KEY_LAST_IMAGE_URI : VCAMApp.KEY_LAST_VIDEO_URI,
                                uri.toString())
                        .apply();
                VCAMApp.ensureWorldReadableLibraryDirs(this);
                runOnUiThread(() -> {
                    String msg = getString(R.string.media_imported_format, MediaPaths.humanBytes(finalBytes));
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
                    MediaProvider.postStickyStatusNotification(
                            this,
                            "VCAM Media Staged (" + (isImage ? "image" : "video") + ")",
                            msg + " • Ready for camera injection",
                            MediaMappings.PKG_GLOBAL,
                            MediaMappings.FACING_ANY,
                            true);
                    syncStateWithFiles();
                    startDemoPlayback();
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
        MediaMappings.clear(this, MediaMappings.PKG_GLOBAL, MediaMappings.FACING_ANY);
        MediaMappings.clear(this, MediaMappings.PKG_GLOBAL, MediaMappings.FACING_BACK);
        MediaMappings.clear(this, MediaMappings.PKG_GLOBAL, MediaMappings.FACING_FRONT);
        prefs.edit()
                .remove(VCAMApp.KEY_LAST_IMAGE_URI)
                .remove(VCAMApp.KEY_LAST_VIDEO_URI)
                .apply();
        Toast.makeText(this, R.string.cleared, Toast.LENGTH_SHORT).show();
        syncStateWithFiles();
        startDemoPlayback();
    }

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
        MediaMappings.notifyUpdated(this);
        Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show();
        syncStateWithFiles();
    }

    private static String safe(@Nullable CharSequence cs) {
        return cs == null ? "" : cs.toString().trim();
    }

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

        if (chipNdkStatus != null) {
            chipNdkStatus.setText("NDK + IAD Cloak: Armed");
            chipNdkStatus.setChipIconResource(android.R.drawable.checkbox_on_background);
        }

        // image & video chips/preview (resolves MediaLibrary + legacy /DCIM/Camera1/)
        File img = MediaPaths.resolveEffectiveImageFile(this);
        File vid = MediaPaths.resolveEffectiveVideoFile(this);
        TransformConfig cfg = TransformConfig.loadGlobal(this);
        boolean hasImage = img.exists() && img.length() > 0;
        boolean hasVideo = vid.exists() && vid.length() > 0;
        boolean mediaMode = TransformConfig.SOURCE_MEDIA.equals(cfg.sourceMode);
        boolean activeVid = mediaMode && hasVideo && (!TransformConfig.MEDIA_IMAGE.equals(cfg.preferredMediaType) || !hasImage);
        boolean activeImg = mediaMode && hasImage && !activeVid;
        if (hasImage) {
            chipImage.setText(activeImg ? "★ Image: ACTIVE" : getString(R.string.chip_image_loaded));
        } else {
            chipImage.setText(R.string.chip_image_none);
        }
        if (hasVideo) {
            chipVideo.setText(activeVid ? "★ Video: ACTIVE" : getString(R.string.chip_video_loaded));
        } else {
            chipVideo.setText(R.string.chip_video_none);
        }
        if (hasVideo || hasImage) {
            String activeDesc = (hasVideo ? ("Video: " + vid.getName() + " (" + MediaPaths.humanBytes(vid.length()) + ") ") : "")
                    + (hasImage ? ("Image: " + img.getName() + " (" + MediaPaths.humanBytes(img.length()) + ")") : "");
            MediaProvider.postStickyStatusNotification(
                    this,
                    "VCAM Armed & Ready",
                    activeDesc.trim() + " • Tap to configure",
                    MediaMappings.PKG_GLOBAL,
                    MediaMappings.FACING_ANY,
                    true);
        }
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
        TransformConfig cfg = TransformConfig.loadGlobal(this);
        boolean hasImg = img.exists() && img.length() > 0;
        boolean hasVid = vid.exists() && vid.length() > 0;
        boolean preferVid = hasVid && (!TransformConfig.MEDIA_IMAGE.equals(cfg.preferredMediaType) || !hasImg);
        if (preferVid) {
            state.thumb = MediaPaths.decodeVideoFrame(vid);
            state.resolution = MediaPaths.videoResolution(vid);
            state.sizeLabel = MediaPaths.humanBytes(vid.length());
        }
        if (state.thumb == null && hasImg) {
            state.thumb = MediaPaths.decodeImageThumb(img, 800);
            state.resolution = MediaPaths.imageResolution(img);
            state.sizeLabel = MediaPaths.humanBytes(img.length());
        }
        if (state.thumb == null && hasVid) {
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
