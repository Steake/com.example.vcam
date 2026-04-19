package com.example.vcam;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Manager-side "Media library" screen: lists every imported image / video
 * with a thumbnail + metadata and lets the user import more or delete
 * existing entries. Entries are stored in {@link MediaLibrary} and served
 * over the ContentProvider URIs the hook resolves through.
 *
 * <p>Also exposes a "Set as default" row action which writes the entry
 * into the global {@code ("*", "any")} mapping so unmapped hosts /
 * cameras fall back to it.
 */
public class MediaLibraryActivity extends AppCompatActivity {

    public static final String EXTRA_PICK_TYPE = "pick_type"; // "image" | "video" | null (browse)
    public static final String RESULT_URI = "result_uri";

    private LibraryAdapter adapter;
    private TextView emptyView;
    private ActivityResultLauncher<String> pickImage;
    private ActivityResultLauncher<String> pickVideo;

    /** LRU thumbnail cache, keyed by "{type}:{id}:{mtime}". */
    private final LruCache<String, Bitmap> thumbCache = new LruCache<>(32);
    /** Precomputed resolution labels keyed by "{type}:{id}" — decoded once per entry. */
    private final Map<String, String> resolutionCache = new HashMap<>();
    private ExecutorService thumbExecutor;
    private Handler mainHandler;

    private boolean isPickMode() {
        return !TextUtils.isEmpty(getIntent().getStringExtra(EXTRA_PICK_TYPE));
    }

    @Nullable
    private String pickType() {
        return getIntent().getStringExtra(EXTRA_PICK_TYPE);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_media_library);

        MaterialToolbar tb = findViewById(R.id.toolbar);
        setSupportActionBar(tb);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            if (isPickMode()) {
                getSupportActionBar().setTitle(MediaLibrary.TYPE_VIDEO.equals(pickType())
                        ? R.string.lib_pick_title_video : R.string.lib_pick_title_image);
            }
        }
        tb.setNavigationOnClickListener(v -> finish());

        RecyclerView rv = findViewById(R.id.library_rv);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new LibraryAdapter();
        rv.setAdapter(adapter);
        emptyView = findViewById(R.id.library_empty);

        pickImage = registerForActivityResult(new ActivityResultContracts.GetContent(),
                uri -> { if (uri != null) importMedia(uri, MediaLibrary.TYPE_IMAGE); });
        pickVideo = registerForActivityResult(new ActivityResultContracts.GetContent(),
                uri -> { if (uri != null) importMedia(uri, MediaLibrary.TYPE_VIDEO); });

        findViewById(R.id.btn_import_image).setOnClickListener(v -> pickImage.launch("image/*"));
        findViewById(R.id.btn_import_video).setOnClickListener(v -> pickVideo.launch("video/*"));

        mainHandler = new Handler(Looper.getMainLooper());
        thumbExecutor = Executors.newSingleThreadExecutor(
                r -> new Thread(r, "VCAM-LibraryThumbs"));
    }

    @Override
    protected void onDestroy() {
        if (thumbExecutor != null) thumbExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        List<MediaLibrary.Entry> items = MediaLibrary.list(this);
        if (isPickMode()) {
            // Filter to requested type in pick mode.
            String want = pickType();
            for (int i = items.size() - 1; i >= 0; i--) {
                if (!items.get(i).type.equals(want)) items.remove(i);
            }
        }
        adapter.setItems(items);
        emptyView.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void importMedia(@NonNull Uri src, @NonNull String type) {
        String name = src.getLastPathSegment();
        MediaLibrary.Entry e = MediaLibrary.importFromUri(this, src, type, name);
        if (e == null) {
            Toast.makeText(this, R.string.media_import_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this,
                getString(R.string.media_imported_format, MediaPaths.humanBytes(e.size)),
                Toast.LENGTH_SHORT).show();
        refresh();
    }

    private void confirmDelete(@NonNull MediaLibrary.Entry e) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.lib_delete_confirm_title)
                .setMessage(getString(R.string.lib_delete_confirm_body, e.name))
                .setNegativeButton(R.string.negative, null)
                .setPositiveButton(R.string.lib_delete, (d, w) -> {
                    MediaLibrary.delete(this, e.type, e.id);
                    refresh();
                })
                .show();
    }

    private void setAsDefault(@NonNull MediaLibrary.Entry e) {
        MediaMappings.setOne(this, MediaMappings.PKG_GLOBAL, MediaMappings.FACING_ANY,
                e.type, e.uri().toString());
        Toast.makeText(this, R.string.lib_set_default_done, Toast.LENGTH_SHORT).show();
    }

    private void pickResult(@NonNull MediaLibrary.Entry e) {
        Intent out = new Intent();
        out.putExtra(RESULT_URI, e.uri().toString());
        setResult(RESULT_OK, out);
        finish();
    }

    private final class LibraryAdapter extends RecyclerView.Adapter<LibraryAdapter.VH> {
        private List<MediaLibrary.Entry> items = new java.util.ArrayList<>();

        void setItems(@NonNull List<MediaLibrary.Entry> items) {
            this.items = items;
            notifyDataSetChanged();
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_media_library, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            MediaLibrary.Entry e = items.get(position);
            h.title.setText(e.name);
            File f = MediaLibrary.fileFor(MediaLibraryActivity.this, e);

            // Resolution: compute once, then memoise. MediaMetadataRetriever
            // is expensive enough that pulling it on every bind during
            // scroll causes visible jank.
            String resKey = e.type + ":" + e.id;
            String resStr = resolutionCache.get(resKey);
            h.subtitle.setText(getString(R.string.lib_entry_meta_format,
                    e.type,
                    resStr != null ? resStr : getString(R.string.lib_resolution_unknown),
                    MediaPaths.humanBytes(e.size)));

            // Thumbnail: try cache; otherwise decode off-thread and swap in.
            String thumbKey = e.type + ":" + e.id + ":" + f.lastModified();
            h.thumbKey = thumbKey;
            Bitmap cached = thumbCache.get(thumbKey);
            if (cached != null) {
                h.thumb.setImageBitmap(cached);
            } else {
                h.thumb.setImageResource(android.R.drawable.ic_menu_gallery);
                loadThumbAsync(e, f, h, resKey, thumbKey);
            }

            h.itemView.setOnClickListener(v -> {
                if (isPickMode()) pickResult(e);
            });
            h.btnDelete.setVisibility(isPickMode() ? View.GONE : View.VISIBLE);
            h.btnSetDefault.setVisibility(isPickMode() ? View.GONE : View.VISIBLE);
            h.btnDelete.setOnClickListener(v -> confirmDelete(e));
            h.btnSetDefault.setOnClickListener(v -> setAsDefault(e));
        }

        private void loadThumbAsync(@NonNull MediaLibrary.Entry e, @NonNull File f,
                                    @NonNull VH h, @NonNull String resKey,
                                    @NonNull String thumbKey) {
            if (thumbExecutor == null || thumbExecutor.isShutdown()) return;
            thumbExecutor.execute(() -> {
                Bitmap bmp = MediaLibrary.TYPE_IMAGE.equals(e.type)
                        ? MediaPaths.decodeImageThumb(f, 256)
                        : MediaPaths.decodeVideoFrame(f);
                int[] res = MediaLibrary.TYPE_IMAGE.equals(e.type)
                        ? MediaPaths.imageResolution(f) : MediaPaths.videoResolution(f);
                String resStr = res != null
                        ? getString(R.string.chip_resolution_format, res[0], res[1])
                        : null;
                mainHandler.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    if (bmp != null) thumbCache.put(thumbKey, bmp);
                    if (resStr != null) resolutionCache.put(resKey, resStr);
                    // Only swap if this ViewHolder is still bound to the
                    // same entry (it may have been recycled during scroll).
                    if (thumbKey.equals(h.thumbKey)) {
                        if (bmp != null) h.thumb.setImageBitmap(bmp);
                        h.subtitle.setText(getString(R.string.lib_entry_meta_format,
                                e.type,
                                resStr != null ? resStr : getString(R.string.lib_resolution_unknown),
                                MediaPaths.humanBytes(e.size)));
                    }
                });
            });
        }

        @Override public int getItemCount() { return items.size(); }

        final class VH extends RecyclerView.ViewHolder {
            final ImageView thumb;
            final TextView title;
            final TextView subtitle;
            final View btnDelete;
            final View btnSetDefault;
            /** Key of the thumbnail currently requested for this view holder. */
            @Nullable String thumbKey;

            VH(@NonNull View v) {
                super(v);
                thumb = v.findViewById(R.id.lib_thumb);
                title = v.findViewById(R.id.lib_title);
                subtitle = v.findViewById(R.id.lib_subtitle);
                btnDelete = v.findViewById(R.id.lib_delete);
                btnSetDefault = v.findViewById(R.id.lib_set_default);
            }
        }
    }
}
