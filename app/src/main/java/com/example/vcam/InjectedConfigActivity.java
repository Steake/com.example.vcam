package com.example.vcam;

import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.ImageView;
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
 *
 * <p>Phase 4 scope picker: the user selects where the imported media lands in
 * {@link MediaMappings}. Options cover back / front / both for either the
 * caller package (when launched from inside a host) or the global
 * {@code ("*", …)} defaults.
 */
public class InjectedConfigActivity extends AppCompatActivity {

    public static final String EXTRA_CALLER_PACKAGE = "caller_package";
    /** Optional: pre-select the scope chip matching the facing the host is currently using. */
    public static final String EXTRA_CURRENT_FACING = "current_facing";

    /** Scope tag values stored on each chip via {@link View#setTag}. */
    private static final String SCOPE_THIS_BOTH   = "this_both";
    private static final String SCOPE_THIS_BACK   = "this_back";
    private static final String SCOPE_THIS_FRONT  = "this_front";
    private static final String SCOPE_GLOBAL_BOTH  = "global_both";
    private static final String SCOPE_GLOBAL_BACK  = "global_back";
    private static final String SCOPE_GLOBAL_FRONT = "global_front";

    private ImageView preview;
    private TextView subtitle;
    private TextView effective;
    private ChipGroup chips;
    private ChipGroup scopeChips;

    private ActivityResultLauncher<String> pickImageLauncher;
    private ActivityResultLauncher<String> pickVideoLauncher;

    private String callerPackage;
    private String currentFacing; // "back" / "front" / null (unknown)

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Theme is set via manifest (@style/Theme.VCAM.Translucent) so the
        // window is already drawn translucent by the time we get here.
        setContentView(R.layout.activity_injected_config);

        callerPackage = getIntent().getStringExtra(EXTRA_CALLER_PACKAGE);
        if (callerPackage == null && getCallingPackage() != null) {
            callerPackage = getCallingPackage();
        }
        currentFacing = getIntent().getStringExtra(EXTRA_CURRENT_FACING);

        subtitle = findViewById(R.id.injected_subtitle);
        preview = findViewById(R.id.injected_preview);
        chips = findViewById(R.id.injected_chips);
        scopeChips = findViewById(R.id.injected_scope_chips);
        effective = findViewById(R.id.injected_effective);

        String subtitleText = callerPackage != null
                ? getString(R.string.injected_subtitle_format, callerPackage)
                : getString(R.string.injected_subtitle_global);
        if (!TextUtils.isEmpty(currentFacing)) {
            subtitleText += "\n" + getString(R.string.injected_scope_current_facing_format,
                    facingLabel(currentFacing));
        }
        subtitle.setText(subtitleText);

        populateScopeChips();

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
        findViewById(R.id.injected_clear_scoped).setOnClickListener(v -> clearScoped());

        refresh();
    }

    private void populateScopeChips() {
        scopeChips.removeAllViews();
        boolean hasCaller = !TextUtils.isEmpty(callerPackage);
        Chip preselect = null;
        if (hasCaller) {
            Chip both  = addScopeChip(SCOPE_THIS_BOTH,  getString(R.string.injected_scope_this_both));
            Chip back  = addScopeChip(SCOPE_THIS_BACK,  getString(R.string.injected_scope_this_back));
            Chip front = addScopeChip(SCOPE_THIS_FRONT, getString(R.string.injected_scope_this_front));
            if (MediaMappings.FACING_FRONT.equals(currentFacing))      preselect = front;
            else if (MediaMappings.FACING_BACK.equals(currentFacing))  preselect = back;
            else                                                        preselect = both;
        }
        Chip gBoth  = addScopeChip(SCOPE_GLOBAL_BOTH,  getString(R.string.injected_scope_global_both));
        addScopeChip(SCOPE_GLOBAL_BACK,  getString(R.string.injected_scope_global_back));
        addScopeChip(SCOPE_GLOBAL_FRONT, getString(R.string.injected_scope_global_front));
        if (preselect == null) preselect = gBoth;
        scopeChips.check(preselect.getId());
    }

    private Chip addScopeChip(@NonNull String tag, @NonNull String text) {
        Chip c = new Chip(this);
        // Use a generated id to avoid collisions with other views in the
        // hierarchy; semantic lookup happens via the chip's tag instead.
        c.setId(View.generateViewId());
        c.setTag(tag);
        c.setText(text);
        c.setCheckable(true);
        scopeChips.addView(c);
        return c;
    }

    /** Return the {@code (pkg, facing)} tuple selected via the scope chips. */
    @NonNull
    private String[] selectedScope() {
        int checkedId = scopeChips.getCheckedChipId();
        View v = checkedId != View.NO_ID ? scopeChips.findViewById(checkedId) : null;
        Object tag = v != null ? v.getTag() : null;
        String pkg, facing;
        if (SCOPE_THIS_BACK.equals(tag))         { pkg = safePkg(); facing = MediaMappings.FACING_BACK; }
        else if (SCOPE_THIS_FRONT.equals(tag))   { pkg = safePkg(); facing = MediaMappings.FACING_FRONT; }
        else if (SCOPE_THIS_BOTH.equals(tag))    { pkg = safePkg(); facing = MediaMappings.FACING_ANY; }
        else if (SCOPE_GLOBAL_BACK.equals(tag))  { pkg = MediaMappings.PKG_GLOBAL; facing = MediaMappings.FACING_BACK; }
        else if (SCOPE_GLOBAL_FRONT.equals(tag)) { pkg = MediaMappings.PKG_GLOBAL; facing = MediaMappings.FACING_FRONT; }
        else                                     { pkg = MediaMappings.PKG_GLOBAL; facing = MediaMappings.FACING_ANY; }
        return new String[]{pkg, facing};
    }

    @NonNull
    private String safePkg() {
        return TextUtils.isEmpty(callerPackage) ? MediaMappings.PKG_GLOBAL : callerPackage;
    }

    private void safeLaunch(ActivityResultLauncher<String> l, String type) {
        try {
            l.launch(type);
        } catch (Throwable t) {
            Toast.makeText(this, R.string.injected_launch_failed, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Import a picked URI into the library, then associate the new entry
     * with the currently-selected scope chip.
     */
    private void importMedia(@NonNull Uri uri, boolean isImage) {
        String type = isImage ? MediaLibrary.TYPE_IMAGE : MediaLibrary.TYPE_VIDEO;
        MediaLibrary.Entry e = MediaLibrary.importFromUri(
                this, uri, type, uri.getLastPathSegment());
        if (e == null) {
            Toast.makeText(this, R.string.media_import_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        String[] scope = selectedScope();
        MediaMappings.setOne(this, scope[0], scope[1], type, e.uri().toString());

        // Also mirror into the legacy staged file so non-provider-aware hooks
        // and users who rely on the DCIM workflow keep seeing the most
        // recently imported media.
        File dst = isImage ? MediaPaths.getImageTarget(this) : MediaPaths.getVideoTarget(this);
        try {
            MediaPaths.copyUriToFile(this, uri, dst);
        } catch (Throwable ignored) {}

        Toast.makeText(this,
                getString(R.string.media_imported_format, MediaPaths.humanBytes(e.size)),
                Toast.LENGTH_SHORT).show();
        refresh();
    }

    /** Clear the legacy staged image/video files. Scoped clearing uses {@link #clearScoped()}. */
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

    /** Remove any mapping for the currently-selected scope chip. */
    private void clearScoped() {
        String[] scope = selectedScope();
        MediaMappings.clear(this, scope[0], scope[1]);
        Toast.makeText(this, R.string.map_cleared, Toast.LENGTH_SHORT).show();
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

        // Show the current effective mapping for (pkg, facing).
        String pkg = TextUtils.isEmpty(callerPackage) ? MediaMappings.PKG_GLOBAL : callerPackage;
        String facing = TextUtils.isEmpty(currentFacing) ? MediaMappings.FACING_ANY : currentFacing;
        String iUri = MediaMappings.resolve(this, pkg, facing, MediaMappings.TYPE_IMAGE);
        String vUri = MediaMappings.resolve(this, pkg, facing, MediaMappings.TYPE_VIDEO);
        effective.setText(getString(R.string.injected_effective_format,
                shortName(iUri), shortName(vUri)));
    }

    @NonNull
    private String shortName(@Nullable String uri) {
        if (TextUtils.isEmpty(uri)) return getString(R.string.map_default_short);
        MediaLibrary.Entry e = MediaLibrary.fromUri(this, uri);
        return e != null ? e.name : uri;
    }

    @NonNull
    private String facingLabel(@Nullable String f) {
        if (MediaMappings.FACING_FRONT.equals(f)) return getString(R.string.injected_facing_front);
        if (MediaMappings.FACING_BACK.equals(f)) return getString(R.string.injected_facing_back);
        return getString(R.string.injected_facing_any);
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
