package com.example.vcam;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.widget.ImageView;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;

import java.io.File;

/**
 * In-injected-context UI for quick config changes.
 *
 * <p>Launched either from the manager UI overflow menu, or from hooks that want
 * to surface a lightweight config overlay inside target camera apps. Because we
 * run as a normal Activity with a translucent theme, we behave like a dialog and
 * return cleanly when the user dismisses via "Return to app".
 */
public class InjectedConfigActivity extends AppCompatActivity {

    public static final String EXTRA_CALLER_PACKAGE = "caller_package";

    private SharedPreferences prefs;

    private ImageView preview;
    private TextView subtitle;
    private ChipGroup chips;
    private RadioGroup modeGroup;

    private ActivityResultLauncher<String> pickImageLauncher;
    private ActivityResultLauncher<String> pickVideoLauncher;

    private String callerPackage;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTheme(R.style.Theme_VCAM_Translucent);
        setContentView(R.layout.activity_injected_config);

        prefs = getSharedPreferences(VCAMApp.PREFS, MODE_PRIVATE);

        callerPackage = getIntent().getStringExtra(EXTRA_CALLER_PACKAGE);
        if (callerPackage == null && getCallingPackage() != null) {
            callerPackage = getCallingPackage();
        }

        subtitle = findViewById(R.id.injected_subtitle);
        preview = findViewById(R.id.injected_preview);
        chips = findViewById(R.id.injected_chips);
        modeGroup = findViewById(R.id.injected_mode);

        if (callerPackage != null) {
            subtitle.setText(getString(R.string.injected_subtitle_format, callerPackage));
        } else {
            subtitle.setText(R.string.injected_subtitle_global);
        }

        String mode = prefs.getString(keyMode(), "global");
        ((com.google.android.material.radiobutton.MaterialRadioButton)
                findViewById("per_app".equals(mode)
                        ? R.id.injected_mode_per_app
                        : R.id.injected_mode_global)).setChecked(true);

        modeGroup.setOnCheckedChangeListener((group, checkedId) -> {
            String newMode = checkedId == R.id.injected_mode_per_app ? "per_app" : "global";
            prefs.edit().putString(keyMode(), newMode).apply();
        });

        pickImageLauncher = registerForActivityResult(
                new ActivityResultContracts.GetContent(),
                uri -> { if (uri != null) importMedia(uri, true); });
        pickVideoLauncher = registerForActivityResult(
                new ActivityResultContracts.GetContent(),
                uri -> { if (uri != null) importMedia(uri, false); });

        findViewById(R.id.injected_pick_image).setOnClickListener(v -> safeLaunch(pickImageLauncher, "image/*"));
        findViewById(R.id.injected_pick_video).setOnClickListener(v -> safeLaunch(pickVideoLauncher, "video/*"));
        findViewById(R.id.injected_clear).setOnClickListener(v -> clearMedia());
        findViewById(R.id.injected_return).setOnClickListener(v -> returnToCaller());

        refresh();
    }

    private String keyMode() {
        return "injection_mode_" + (callerPackage == null ? "default" : callerPackage);
    }

    private void safeLaunch(ActivityResultLauncher<String> l, String type) {
        try {
            l.launch(type);
        } catch (Throwable t) {
            Toast.makeText(this, R.string.injected_launch_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void importMedia(@NonNull Uri uri, boolean isImage) {
        File dst = isImage ? MediaPaths.getImageTarget(this) : MediaPaths.getVideoTarget(this);
        try {
            long bytes = MediaPaths.copyUriToFile(this, uri, dst);
            Toast.makeText(this,
                    getString(R.string.media_imported_format, MediaPaths.humanBytes(bytes)),
                    Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, R.string.media_import_failed, Toast.LENGTH_SHORT).show();
        }
        refresh();
    }

    private void clearMedia() {
        File img = MediaPaths.getImageTarget(this);
        File vid = MediaPaths.getVideoTarget(this);
        //noinspection ResultOfMethodCallIgnored
        if (img.exists()) img.delete();
        //noinspection ResultOfMethodCallIgnored
        if (vid.exists()) vid.delete();
        Toast.makeText(this, R.string.cleared, Toast.LENGTH_SHORT).show();
        refresh();
    }

    private void refresh() {
        chips.removeAllViews();
        File img = MediaPaths.getImageTarget(this);
        File vid = MediaPaths.getVideoTarget(this);

        Bitmap thumb = null;
        if (img.exists() && img.length() > 0) {
            addChip(getString(R.string.chip_image_loaded));
            int[] r = MediaPaths.imageResolution(img);
            if (r != null) addChip(getString(R.string.chip_resolution_format, r[0], r[1]));
            addChip(MediaPaths.humanBytes(img.length()));
            thumb = MediaPaths.decodeImageThumb(img, 800);
        } else {
            addChip(getString(R.string.chip_image_none));
        }

        if (vid.exists() && vid.length() > 0) {
            addChip(getString(R.string.chip_video_loaded));
            if (thumb == null) {
                thumb = MediaPaths.decodeVideoFrame(vid);
                int[] r = MediaPaths.videoResolution(vid);
                if (r != null) addChip(getString(R.string.chip_resolution_format, r[0], r[1]));
                addChip(MediaPaths.humanBytes(vid.length()));
            }
        } else {
            addChip(getString(R.string.chip_video_none));
        }

        if (thumb != null) preview.setImageBitmap(thumb);
        else preview.setImageResource(android.R.drawable.ic_menu_gallery);
    }

    private void addChip(String text) {
        Chip c = new Chip(this);
        c.setText(text);
        c.setClickable(false);
        c.setCheckable(false);
        chips.addView(c);
    }

    private void returnToCaller() {
        if (callerPackage != null) {
            Intent i = getPackageManager().getLaunchIntentForPackage(callerPackage);
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    startActivity(i);
                } catch (Throwable ignored) {}
            }
        }
        finish();
    }
}
