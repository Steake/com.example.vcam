package com.example.vcam;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Per-entry media storage backing the Phase 4 per-host / per-camera mapping.
 *
 * <p>Each imported image or video is copied into the manager's private
 * {@code filesDir/library/} with a stable integer id, then exposed to hooked
 * host processes via {@link MediaProvider} as
 * {@code content://com.example.vcam/image/{id}} or
 * {@code content://com.example.vcam/video/{id}}.
 *
 * <p>The metadata index (display name, size, type) is persisted as a JSON
 * array in {@link VCAMApp#PREFS} under {@link #KEY}. We deliberately keep
 * ids separate per type (image / video) so the URIs match the path
 * semantics and migration is trivial.
 */
public final class MediaLibrary {

    public static final String KEY = "media_library";
    private static final String DIR = "library";

    public static final String TYPE_IMAGE = "image";
    public static final String TYPE_VIDEO = "video";

    private MediaLibrary() {}

    /** One library entry. Immutable after insert (except delete). */
    public static final class Entry {
        public final int id;
        @NonNull public final String type;
        @NonNull public final String name;
        public final long size;
        public final long importedAt;

        public Entry(int id, @NonNull String type, @NonNull String name, long size, long importedAt) {
            this.id = id;
            this.type = type;
            this.name = name;
            this.size = size;
            this.importedAt = importedAt;
        }

        /** Content URI the hook can stream from. */
        @NonNull
        public Uri uri() {
            return Uri.parse("content://" + MediaProvider.AUTHORITY + "/" + type + "/" + id);
        }
    }

    /** Root directory holding per-id files. Created lazily. */
    @NonNull
    public static File libraryDir(@NonNull Context ctx) {
        File d = new File(ctx.getFilesDir(), DIR);
        if (!d.exists()) {
            //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
        }
        return d;
    }

    /** The backing file for an entry (may not exist if the entry was deleted). */
    @NonNull
    public static File fileFor(@NonNull Context ctx, @NonNull String type, int id) {
        return new File(libraryDir(ctx), type + "_" + id);
    }

    @NonNull
    public static File fileFor(@NonNull Context ctx, @NonNull Entry e) {
        return fileFor(ctx, e.type, e.id);
    }

    /** Return all library entries sorted by import time ascending (stable for UI). */
    @NonNull
    public static List<Entry> list(@NonNull Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(VCAMApp.PREFS, Context.MODE_PRIVATE);
        String raw = sp.getString(KEY, "[]");
        List<Entry> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out.add(new Entry(
                        o.getInt("id"),
                        o.getString("type"),
                        o.optString("name", "media-" + o.getInt("id")),
                        o.optLong("size", 0L),
                        o.optLong("importedAt", 0L)
                ));
            }
        } catch (Throwable ignored) {}
        Collections.sort(out, new Comparator<Entry>() {
            @Override public int compare(Entry a, Entry b) {
                return Long.compare(a.importedAt, b.importedAt);
            }
        });
        return out;
    }

    @Nullable
    public static Entry get(@NonNull Context ctx, @NonNull String type, int id) {
        for (Entry e : list(ctx)) {
            if (e.id == id && type.equals(e.type)) return e;
        }
        return null;
    }

    /** Parse a {@code content://com.example.vcam/image/{id}} or {@code /video/{id}} URI. */
    @Nullable
    public static Entry fromUri(@NonNull Context ctx, @Nullable String uri) {
        if (TextUtils.isEmpty(uri)) return null;
        try {
            Uri u = Uri.parse(uri);
            if (!MediaProvider.AUTHORITY.equals(u.getAuthority())) return null;
            List<String> seg = u.getPathSegments();
            if (seg.size() != 2) return null;
            String type = seg.get(0);
            int id = Integer.parseInt(seg.get(1));
            return get(ctx, type, id);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Next free id within a given type namespace. */
    private static int nextId(@NonNull Context ctx, @NonNull String type) {
        int max = 0;
        for (Entry e : list(ctx)) {
            if (type.equals(e.type) && e.id > max) max = e.id;
        }
        return max + 1;
    }

    private static synchronized void writeAll(@NonNull Context ctx, @NonNull List<Entry> entries) {
        JSONArray arr = new JSONArray();
        try {
            for (Entry e : entries) {
                JSONObject o = new JSONObject();
                o.put("id", e.id);
                o.put("type", e.type);
                o.put("name", e.name);
                o.put("size", e.size);
                o.put("importedAt", e.importedAt);
                arr.put(o);
            }
        } catch (Throwable ignored) {}
        ctx.getSharedPreferences(VCAMApp.PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, arr.toString()).apply();
    }

    /**
     * Import the given source URI into the library as a new entry with a
     * fresh per-type id, copying bytes into {@code filesDir/library/}.
     * Returns the new entry, or {@code null} on failure.
     */
    @Nullable
    public static synchronized Entry importFromUri(@NonNull Context ctx, @NonNull Uri src,
                                                   @NonNull String type,
                                                   @Nullable String displayName) {
        int id = nextId(ctx, type);
        File dst = fileFor(ctx, type, id);
        long bytes;
        try {
            bytes = copyUriToFile(ctx, src, dst);
        } catch (Throwable t) {
            //noinspection ResultOfMethodCallIgnored
            dst.delete();
            return null;
        }
        if (bytes <= 0) {
            //noinspection ResultOfMethodCallIgnored
            dst.delete();
            return null;
        }
        Entry e = new Entry(id, type,
                TextUtils.isEmpty(displayName) ? (type + "-" + id) : displayName,
                bytes, System.currentTimeMillis());
        List<Entry> all = list(ctx);
        all.add(e);
        writeAll(ctx, all);
        return e;
    }

    /** Import a pre-existing file already sitting on disk (used by migration). */
    @Nullable
    public static synchronized Entry importFromFile(@NonNull Context ctx, @NonNull File src,
                                                    @NonNull String type,
                                                    @Nullable String displayName) {
        if (!src.exists() || src.length() == 0) return null;
        int id = nextId(ctx, type);
        File dst = fileFor(ctx, type, id);
        try {
            try (InputStream in = new java.io.FileInputStream(src);
                 OutputStream out = new FileOutputStream(dst)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                out.flush();
            }
        } catch (Throwable t) {
            //noinspection ResultOfMethodCallIgnored
            dst.delete();
            return null;
        }
        Entry e = new Entry(id, type,
                TextUtils.isEmpty(displayName) ? src.getName() : displayName,
                dst.length(), System.currentTimeMillis());
        List<Entry> all = list(ctx);
        all.add(e);
        writeAll(ctx, all);
        return e;
    }

    public static synchronized boolean delete(@NonNull Context ctx, @NonNull String type, int id) {
        List<Entry> all = list(ctx);
        boolean removed = false;
        for (int i = all.size() - 1; i >= 0; i--) {
            Entry e = all.get(i);
            if (e.id == id && type.equals(e.type)) {
                all.remove(i);
                File f = fileFor(ctx, type, id);
                if (f.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
                removed = true;
                break;
            }
        }
        if (removed) {
            writeAll(ctx, all);
            // Cascade: clear any mapping that pointed at the deleted entry.
            MediaMappings.dropReferencesTo(ctx, type, id);
        }
        return removed;
    }

    /**
     * Copy content from URI into file. Extracted from {@link MediaPaths} so
     * library code doesn't depend on DCIM-targeted helpers.
     */
    public static long copyUriToFile(@NonNull Context ctx, @NonNull Uri src, @NonNull File dst)
            throws Exception {
        ContentResolver resolver = ctx.getContentResolver();
        File parent = dst.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        long total = 0;
        try (InputStream in = resolver.openInputStream(src);
             OutputStream out = new FileOutputStream(dst)) {
            if (in == null) {
                throw new IllegalStateException("Unable to open input stream for " + src);
            }
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                total += n;
            }
            out.flush();
        }
        return total;
    }
}
