package com.example.vcam;

import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * "Per-app mapping" screen: lists installed apps that have camera
 * permission (a reasonable proxy for apps a user would want to hook) and
 * any packages we already have mappings for. Tapping a row opens
 * {@link AppMappingEditActivity} for that package.
 *
 * <p>Each row shows a compact badge summarising the current mapping
 * state for the package: unused, single slot mapped, or distinct
 * back / front assignments.
 */
public class PerAppMappingActivity extends AppCompatActivity {

    private AppAdapter adapter;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_per_app_mapping);

        MaterialToolbar tb = findViewById(R.id.toolbar);
        setSupportActionBar(tb);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        tb.setNavigationOnClickListener(v -> finish());

        RecyclerView rv = findViewById(R.id.apps_rv);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new AppAdapter();
        rv.setAdapter(adapter);

        findViewById(R.id.btn_global_defaults).setOnClickListener(v -> {
            Intent i = new Intent(this, AppMappingEditActivity.class);
            i.putExtra(AppMappingEditActivity.EXTRA_PKG, MediaMappings.PKG_GLOBAL);
            startActivity(i);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        adapter.setItems(loadApps());
    }

    private List<AppRow> loadApps() {
        PackageManager pm = getPackageManager();
        List<AppRow> rows = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        // Apps with CAMERA permission declared — a decent proxy for "hookable".
        List<ApplicationInfo> installed = pm.getInstalledApplications(PackageManager.GET_META_DATA);
        for (ApplicationInfo ai : installed) {
            if (ai.packageName == null) continue;
            if (ai.packageName.equals(getPackageName())) continue;
            if (!hasCameraPermission(pm, ai.packageName)) continue;
            if (!seen.add(ai.packageName)) continue;
            rows.add(new AppRow(ai.packageName,
                    String.valueOf(pm.getApplicationLabel(ai)),
                    safeIcon(pm, ai)));
        }
        // Plus any packages that already have mappings, even if they're not installed here.
        for (MediaMappings.Mapping m : MediaMappings.all(this)) {
            if (MediaMappings.PKG_GLOBAL.equals(m.pkg)) continue;
            if (!seen.add(m.pkg)) continue;
            String label = m.pkg;
            Drawable icon = null;
            try {
                ApplicationInfo ai = pm.getApplicationInfo(m.pkg, 0);
                label = String.valueOf(pm.getApplicationLabel(ai));
                icon = safeIcon(pm, ai);
            } catch (Throwable ignored) {}
            rows.add(new AppRow(m.pkg, label, icon));
        }

        Collections.sort(rows, new Comparator<AppRow>() {
            @Override public int compare(AppRow a, AppRow b) {
                return a.label.compareToIgnoreCase(b.label);
            }
        });
        return rows;
    }

    private static boolean hasCameraPermission(PackageManager pm, String pkg) {
        try {
            return pm.checkPermission(android.Manifest.permission.CAMERA, pkg)
                    == PackageManager.PERMISSION_GRANTED
                    || declaresCameraInManifest(pm, pkg);
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean declaresCameraInManifest(PackageManager pm, String pkg) {
        try {
            android.content.pm.PackageInfo pi =
                    pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS);
            if (pi.requestedPermissions == null) return false;
            for (String p : pi.requestedPermissions) {
                if (android.Manifest.permission.CAMERA.equals(p)) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    @Nullable
    private static Drawable safeIcon(PackageManager pm, ApplicationInfo ai) {
        try { return pm.getApplicationIcon(ai); } catch (Throwable t) { return null; }
    }

    private static final class AppRow {
        final String pkg;
        final String label;
        @Nullable final Drawable icon;
        AppRow(String pkg, String label, @Nullable Drawable icon) {
            this.pkg = pkg; this.label = label; this.icon = icon;
        }
    }

    private final class AppAdapter extends RecyclerView.Adapter<AppAdapter.VH> {
        private List<AppRow> items = new ArrayList<>();

        void setItems(List<AppRow> items) { this.items = items; notifyDataSetChanged(); }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int vt) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_per_app, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            AppRow row = items.get(pos);
            h.label.setText(row.label);
            h.pkg.setText(row.pkg);
            if (row.icon != null) h.icon.setImageDrawable(row.icon);
            else h.icon.setImageResource(android.R.drawable.sym_def_app_icon);
            h.badge.setText(summary(row.pkg));
            h.itemView.setOnClickListener(v -> {
                Intent i = new Intent(PerAppMappingActivity.this, AppMappingEditActivity.class);
                i.putExtra(AppMappingEditActivity.EXTRA_PKG, row.pkg);
                i.putExtra(AppMappingEditActivity.EXTRA_LABEL, row.label);
                startActivity(i);
            });
        }

        @Override public int getItemCount() { return items.size(); }

        final class VH extends RecyclerView.ViewHolder {
            final ImageView icon;
            final TextView label;
            final TextView pkg;
            final TextView badge;
            VH(@NonNull View v) {
                super(v);
                icon = v.findViewById(R.id.app_icon);
                label = v.findViewById(R.id.app_label);
                pkg = v.findViewById(R.id.app_pkg);
                badge = v.findViewById(R.id.app_badge);
            }
        }
    }

    /** Compact human-readable mapping summary for the row badge. */
    @NonNull
    private String summary(@NonNull String pkg) {
        MediaMappings.Mapping any = MediaMappings.get(this, pkg, MediaMappings.FACING_ANY);
        MediaMappings.Mapping back = MediaMappings.get(this, pkg, MediaMappings.FACING_BACK);
        MediaMappings.Mapping front = MediaMappings.get(this, pkg, MediaMappings.FACING_FRONT);
        if (any == null && back == null && front == null) {
            return getString(R.string.map_using_default);
        }
        if (any != null && back == null && front == null) {
            return getString(R.string.map_both_format,
                    shortFor(any.imageUri), shortFor(any.videoUri));
        }
        String backLabel = back != null
                ? shortFor(first(back.imageUri, back.videoUri))
                : getString(R.string.map_default_short);
        String frontLabel = front != null
                ? shortFor(first(front.imageUri, front.videoUri))
                : getString(R.string.map_default_short);
        return getString(R.string.map_back_front_format, backLabel, frontLabel);
    }

    @Nullable
    private static String first(@Nullable String a, @Nullable String b) {
        return !TextUtils.isEmpty(a) ? a : b;
    }

    @NonNull
    private String shortFor(@Nullable String uri) {
        if (TextUtils.isEmpty(uri)) return getString(R.string.map_default_short);
        MediaLibrary.Entry e = MediaLibrary.fromUri(this, uri);
        return e != null ? e.name : uri;
    }
}
