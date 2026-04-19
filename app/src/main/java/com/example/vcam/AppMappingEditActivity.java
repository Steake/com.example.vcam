package com.example.vcam;

import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.materialswitch.MaterialSwitch;

/**
 * Edit the 4 mapping slots (back/front image + back/front video) for a
 * single package. Reused for the global defaults screen with
 * {@code pkg="*"}.
 *
 * <p>A "Same for both cameras" switch collapses the view into 2 slots,
 * stored as {@code facing="any"}. When toggled off, any existing
 * {@code "any"} row is split into {@code "back"} and {@code "front"}
 * rows so per-camera edits can proceed.
 */
public class AppMappingEditActivity extends AppCompatActivity {

    public static final String EXTRA_PKG = "pkg";
    public static final String EXTRA_LABEL = "label";

    /** Preselect facing for the back-camera slots when opened from a live session. */
    public static final String EXTRA_PRESELECT_FACING = "preselect_facing";

    private String pkg;
    private MaterialSwitch sameForBoth;

    private TextView slotBackImage, slotBackVideo, slotFrontImage, slotFrontVideo;
    private TextView slotAnyImage, slotAnyVideo;
    private View groupAny, groupBackFront;

    private ActivityResultLauncher<Intent> pickerLauncher;
    // In-flight pick state: what to write the result to.
    private String pickFacing;
    private String pickType;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_app_mapping_edit);

        pkg = getIntent().getStringExtra(EXTRA_PKG);
        if (TextUtils.isEmpty(pkg)) {
            finish();
            return;
        }

        MaterialToolbar tb = findViewById(R.id.toolbar);
        setSupportActionBar(tb);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            String label = getIntent().getStringExtra(EXTRA_LABEL);
            if (MediaMappings.PKG_GLOBAL.equals(pkg)) {
                getSupportActionBar().setTitle(R.string.map_global_defaults_title);
            } else {
                getSupportActionBar().setTitle(!TextUtils.isEmpty(label) ? label : pkg);
            }
        }
        tb.setNavigationOnClickListener(v -> finish());

        TextView pkgLine = findViewById(R.id.edit_pkg_line);
        pkgLine.setText(pkg);

        // Resolve a human label lazily when we weren't given one.
        if (TextUtils.isEmpty(getIntent().getStringExtra(EXTRA_LABEL))
                && !MediaMappings.PKG_GLOBAL.equals(pkg)) {
            try {
                ApplicationInfo ai = getPackageManager().getApplicationInfo(pkg, 0);
                CharSequence label = getPackageManager().getApplicationLabel(ai);
                if (getSupportActionBar() != null && !TextUtils.isEmpty(label)) {
                    getSupportActionBar().setTitle(label);
                }
            } catch (Throwable ignored) {}
        }

        sameForBoth = findViewById(R.id.switch_same_both);
        slotBackImage = findViewById(R.id.slot_back_image);
        slotBackVideo = findViewById(R.id.slot_back_video);
        slotFrontImage = findViewById(R.id.slot_front_image);
        slotFrontVideo = findViewById(R.id.slot_front_video);
        slotAnyImage = findViewById(R.id.slot_any_image);
        slotAnyVideo = findViewById(R.id.slot_any_video);
        groupAny = findViewById(R.id.group_any);
        groupBackFront = findViewById(R.id.group_back_front);

        sameForBoth.setOnCheckedChangeListener((b, c) -> {
            if (!b.isPressed()) return;
            toggleMode(c);
        });

        wireSlot(slotBackImage, MediaMappings.FACING_BACK, MediaMappings.TYPE_IMAGE);
        wireSlot(slotBackVideo, MediaMappings.FACING_BACK, MediaMappings.TYPE_VIDEO);
        wireSlot(slotFrontImage, MediaMappings.FACING_FRONT, MediaMappings.TYPE_IMAGE);
        wireSlot(slotFrontVideo, MediaMappings.FACING_FRONT, MediaMappings.TYPE_VIDEO);
        wireSlot(slotAnyImage, MediaMappings.FACING_ANY, MediaMappings.TYPE_IMAGE);
        wireSlot(slotAnyVideo, MediaMappings.FACING_ANY, MediaMappings.TYPE_VIDEO);

        pickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                (ActivityResult r) -> {
                    if (r == null || r.getResultCode() != RESULT_OK || r.getData() == null) return;
                    String u = r.getData().getStringExtra(MediaLibraryActivity.RESULT_URI);
                    if (TextUtils.isEmpty(u)) return;
                    if (pickFacing != null && pickType != null) {
                        MediaMappings.setOne(this, pkg, pickFacing, pickType, u);
                        refresh();
                    }
                });

        sameForBoth.setChecked(detectMode());
        applyMode(sameForBoth.isChecked());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private boolean detectMode() {
        MediaMappings.Mapping any = MediaMappings.get(this, pkg, MediaMappings.FACING_ANY);
        MediaMappings.Mapping back = MediaMappings.get(this, pkg, MediaMappings.FACING_BACK);
        MediaMappings.Mapping front = MediaMappings.get(this, pkg, MediaMappings.FACING_FRONT);
        // Default to collapsed "any" mode when nothing is set yet, or when
        // only the "any" row is populated.
        return (back == null && front == null);
    }

    private void toggleMode(boolean same) {
        if (same) {
            // Collapse: merge current back/front into any. Prefer back
            // values over front; fall back to a pre-existing any value only
            // when neither per-facing slot is populated.
            MediaMappings.Mapping back = MediaMappings.get(this, pkg, MediaMappings.FACING_BACK);
            MediaMappings.Mapping front = MediaMappings.get(this, pkg, MediaMappings.FACING_FRONT);
            MediaMappings.Mapping any = MediaMappings.get(this, pkg, MediaMappings.FACING_ANY);
            String imgUri = pick(back == null ? null : back.imageUri,
                    front == null ? null : front.imageUri,
                    any == null ? null : any.imageUri);
            String vidUri = pick(back == null ? null : back.videoUri,
                    front == null ? null : front.videoUri,
                    any == null ? null : any.videoUri);
            MediaMappings.clear(this, pkg, MediaMappings.FACING_BACK);
            MediaMappings.clear(this, pkg, MediaMappings.FACING_FRONT);
            if (imgUri != null || vidUri != null) {
                MediaMappings.set(this, pkg, MediaMappings.FACING_ANY, imgUri, vidUri);
            }
        } else {
            // Split: copy any → both back and front so per-camera edits
            // start from the current effective values.
            MediaMappings.Mapping any = MediaMappings.get(this, pkg, MediaMappings.FACING_ANY);
            if (any != null) {
                MediaMappings.set(this, pkg, MediaMappings.FACING_BACK, any.imageUri, any.videoUri);
                MediaMappings.set(this, pkg, MediaMappings.FACING_FRONT, any.imageUri, any.videoUri);
                MediaMappings.clear(this, pkg, MediaMappings.FACING_ANY);
            }
        }
        applyMode(same);
        refresh();
    }

    @Nullable
    private static String pick(@Nullable String a, @Nullable String b, @Nullable String c) {
        if (!TextUtils.isEmpty(a)) return a;
        if (!TextUtils.isEmpty(b)) return b;
        if (!TextUtils.isEmpty(c)) return c;
        return null;
    }

    private void applyMode(boolean same) {
        groupAny.setVisibility(same ? View.VISIBLE : View.GONE);
        groupBackFront.setVisibility(same ? View.GONE : View.VISIBLE);
    }

    private void wireSlot(@NonNull TextView slot, @NonNull String facing, @NonNull String type) {
        slot.setOnClickListener(v -> launchPicker(facing, type));
        slot.setOnLongClickListener(v -> {
            MediaMappings.setOne(this, pkg, facing, type, null);
            Toast.makeText(this, R.string.map_cleared, Toast.LENGTH_SHORT).show();
            refresh();
            return true;
        });
    }

    private void launchPicker(@NonNull String facing, @NonNull String type) {
        pickFacing = facing;
        pickType = type;
        Intent i = new Intent(this, MediaLibraryActivity.class);
        i.putExtra(MediaLibraryActivity.EXTRA_PICK_TYPE, type);
        pickerLauncher.launch(i);
    }

    private void refresh() {
        fillSlot(slotBackImage, MediaMappings.FACING_BACK, MediaMappings.TYPE_IMAGE);
        fillSlot(slotBackVideo, MediaMappings.FACING_BACK, MediaMappings.TYPE_VIDEO);
        fillSlot(slotFrontImage, MediaMappings.FACING_FRONT, MediaMappings.TYPE_IMAGE);
        fillSlot(slotFrontVideo, MediaMappings.FACING_FRONT, MediaMappings.TYPE_VIDEO);
        fillSlot(slotAnyImage, MediaMappings.FACING_ANY, MediaMappings.TYPE_IMAGE);
        fillSlot(slotAnyVideo, MediaMappings.FACING_ANY, MediaMappings.TYPE_VIDEO);
    }

    private void fillSlot(@NonNull TextView slot, @NonNull String facing, @NonNull String type) {
        MediaMappings.Mapping m = MediaMappings.get(this, pkg, facing);
        String uri = m == null ? null
                : (MediaMappings.TYPE_IMAGE.equals(type) ? m.imageUri : m.videoUri);
        if (TextUtils.isEmpty(uri)) {
            slot.setText(R.string.map_slot_unset);
            return;
        }
        MediaLibrary.Entry e = MediaLibrary.fromUri(this, uri);
        slot.setText(e != null ? e.name : uri);
    }
}
