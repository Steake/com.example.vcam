package com.example.vcam;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
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

    private ActivityResultLauncher<String> pickImageLauncher;
    private ActivityResultLauncher<String> pickVideoLauncher;
    private ActivityResultLauncher<String[]> storagePermsLauncher;

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

        // Register activity result launchers (Photo Picker on 13+, GetContent fallback).
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
            ensureStorageDir();
            pickImageLauncher.launch("image/*");
        });
        findViewById(R.id.btn_pick_video).setOnClickListener(v -> {
            ensureStorageDir();
            pickVideoLauncher.launch("video/*");
        });

        findViewById(R.id.btn_clear).setOnClickListener(v -> clearMedia());
        findViewById(R.id.btn_test).setOnClickListener(v -> openSystemCamera());

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

        if (!prefs.getBoolean(VCAMApp.KEY_ONBOARDING_DONE, false)) {
            startActivity(new Intent(this, OnboardingActivity.class));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        syncStateWithFiles();
    }

    // ---------------------------------------------------------------------
    // Permissions / storage
    // ---------------------------------------------------------------------

    private void ensureStorageDir() {
        if (!hasStoragePermission()) {
            requestStoragePermission();
        }
        MediaPaths.defaultDir();
    }

    private boolean hasStoragePermission() {
        // From Android 13+, READ_MEDIA_IMAGES/VIDEO replace READ_EXTERNAL_STORAGE;
        // we use GetContent which does not require permissions for picking.
        // However writing to /DCIM/Camera1 still needs legacy external-storage write
        // on API <= 29.  On 30+ we use MediaStore-less direct writes, so we still
        // request READ/WRITE when they're declared (for the toggle marker files).
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return true;
        return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestStoragePermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return;
        new AlertDialog.Builder(this)
                .setTitle(R.string.permission_lack_warn)
                .setMessage(R.string.permission_description)
                .setNegativeButton(R.string.negative, (d, w) ->
                        Toast.makeText(this, R.string.permission_lack_warn, Toast.LENGTH_SHORT).show())
                .setPositiveButton(R.string.positive, (d, w) ->
                        storagePermsLauncher.launch(new String[]{
                                Manifest.permission.READ_EXTERNAL_STORAGE,
                                Manifest.permission.WRITE_EXTERNAL_STORAGE
                        }))
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
        try {
            long bytes = MediaPaths.copyUriToFile(this, uri, dst);
            prefs.edit()
                    .putString(isImage ? VCAMApp.KEY_LAST_IMAGE_URI : VCAMApp.KEY_LAST_VIDEO_URI,
                            uri.toString())
                    .apply();
            Toast.makeText(this,
                    getString(R.string.media_imported_format, MediaPaths.humanBytes(bytes)),
                    Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.w(TAG, "Import failed", e);
            Toast.makeText(this, R.string.media_import_failed, Toast.LENGTH_SHORT).show();
        }
        syncStateWithFiles();
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
        if (!hasStoragePermission()) {
            requestStoragePermission();
        } else {
            MediaPaths.defaultDir();
        }

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
        }

        // image & video chips/preview
        File img = MediaPaths.getImageTarget(this);
        File vid = MediaPaths.getVideoTarget(this);

        Bitmap thumb = null;
        if (img.exists() && img.length() > 0) {
            chipImage.setText(R.string.chip_image_loaded);
            thumb = MediaPaths.decodeImageThumb(img, 800);
            int[] res = MediaPaths.imageResolution(img);
            if (res != null) {
                chipRes.setVisibility(View.VISIBLE);
                chipRes.setText(getString(R.string.chip_resolution_format, res[0], res[1]));
            }
            chipSize.setVisibility(View.VISIBLE);
            chipSize.setText(MediaPaths.humanBytes(img.length()));
        } else {
            chipImage.setText(R.string.chip_image_none);
        }

        if (vid.exists() && vid.length() > 0) {
            chipVideo.setText(R.string.chip_video_loaded);
            if (thumb == null) {
                thumb = MediaPaths.decodeVideoFrame(vid);
                int[] res = MediaPaths.videoResolution(vid);
                if (res != null) {
                    chipRes.setVisibility(View.VISIBLE);
                    chipRes.setText(getString(R.string.chip_resolution_format, res[0], res[1]));
                }
                chipSize.setVisibility(View.VISIBLE);
                chipSize.setText(MediaPaths.humanBytes(vid.length()));
            }
        } else {
            chipVideo.setText(R.string.chip_video_none);
        }

        if (thumb != null) {
            preview.setImageBitmap(thumb);
        } else {
            preview.setImageResource(android.R.drawable.ic_menu_gallery);
        }
    }
}
