package com.example.vcam;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-host &amp; per-camera media mapping store.
 *
 * <p>Persisted as a JSON array in {@link VCAMApp#PREFS} under {@link #KEY}:
 * <pre>
 * [ { "pkg": "com.whatsapp", "facing": "back",  "imageUri": "content://...", "videoUri": null },
 *   { "pkg": "*",            "facing": "any",   "imageUri": "...",           "videoUri": "..." } ]
 * </pre>
 *
 * <p>{@code facing} is one of {@link #FACING_BACK}, {@link #FACING_FRONT},
 * {@link #FACING_ANY}. Resolution walks, first-match-wins:
 * <ol>
 *     <li>{@code (pkg, facing)}</li>
 *     <li>{@code (pkg, "any")}</li>
 *     <li>{@code ("*", facing)}</li>
 *     <li>{@code ("*", "any")}</li>
 * </ol>
 */
public final class MediaMappings {

    public static final String KEY = "media_mappings";
    public static final String ACTION_UPDATED = "com.example.vcam.action.MAPPING_UPDATED";

    public static final String PKG_GLOBAL = "*";
    public static final String FACING_BACK = "back";
    public static final String FACING_FRONT = "front";
    public static final String FACING_ANY = "any";

    public static final String TYPE_IMAGE = "image";
    public static final String TYPE_VIDEO = "video";

    private MediaMappings() {}

    /** One (pkg, facing) mapping row. */
    public static final class Mapping {
        @NonNull public final String pkg;
        @NonNull public final String facing;
        @Nullable public final String imageUri;
        @Nullable public final String videoUri;

        public Mapping(@NonNull String pkg, @NonNull String facing,
                       @Nullable String imageUri, @Nullable String videoUri) {
            this.pkg = pkg;
            this.facing = facing;
            this.imageUri = imageUri;
            this.videoUri = videoUri;
        }

        public boolean isEmpty() {
            return TextUtils.isEmpty(imageUri) && TextUtils.isEmpty(videoUri);
        }
    }

    @NonNull
    public static List<Mapping> all(@NonNull Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(VCAMApp.PREFS, Context.MODE_PRIVATE);
        return parse(sp.getString(KEY, "[]"));
    }

    @NonNull
    private static List<Mapping> parse(@Nullable String raw) {
        List<Mapping> out = new ArrayList<>();
        if (TextUtils.isEmpty(raw)) return out;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out.add(new Mapping(
                        o.optString("pkg", PKG_GLOBAL),
                        normalizeFacing(o.optString("facing", FACING_ANY)),
                        nullable(o.optString("imageUri", null)),
                        nullable(o.optString("videoUri", null))
                ));
            }
        } catch (Throwable ignored) {}
        return out;
    }

    @Nullable
    private static String nullable(@Nullable String s) {
        return (s == null || s.isEmpty() || "null".equals(s)) ? null : s;
    }

    @NonNull
    public static String normalizeFacing(@Nullable String f) {
        if (FACING_FRONT.equals(f) || FACING_BACK.equals(f)) return f;
        return FACING_ANY;
    }

    /** Returns the mapping for exact (pkg, facing), or {@code null}. */
    @Nullable
    public static Mapping get(@NonNull Context ctx, @NonNull String pkg, @NonNull String facing) {
        for (Mapping m : all(ctx)) {
            if (m.pkg.equals(pkg) && m.facing.equals(facing)) return m;
        }
        return null;
    }

    /**
     * Walk the resolution order and return the matching URI string for the
     * given {@code type} ({@link #TYPE_IMAGE} or {@link #TYPE_VIDEO}), or
     * {@code null} if nothing is configured.
     */
    @Nullable
    public static String resolve(@NonNull Context ctx, @NonNull String pkg,
                                 @NonNull String facing, @NonNull String type) {
        // Defend against typos / new-type additions: unknown type values
        // silently routing to TYPE_VIDEO would be very hard to debug.
        if (!TYPE_IMAGE.equals(type) && !TYPE_VIDEO.equals(type)) {
            return null;
        }
        facing = normalizeFacing(facing);
        List<Mapping> all = all(ctx);
        String[][] probes = new String[][] {
                {pkg, facing},
                {pkg, FACING_ANY},
                {PKG_GLOBAL, facing},
                {PKG_GLOBAL, FACING_ANY}
        };
        for (String[] p : probes) {
            if (FACING_ANY.equals(p[1]) && FACING_ANY.equals(facing) && p == probes[1]) {
                // Probe 2 and probe 1 collapse when facing=="any"; skip the dup.
                continue;
            }
            for (Mapping m : all) {
                if (!m.pkg.equals(p[0]) || !m.facing.equals(p[1])) continue;
                String uri = TYPE_IMAGE.equals(type) ? m.imageUri : m.videoUri;
                if (!TextUtils.isEmpty(uri)) return uri;
            }
        }
        return null;
    }

    /**
     * Create / update the mapping for {@code (pkg, facing)}. Passing
     * {@code null} for a URI clears it. If the resulting mapping is empty
     * it's removed from the table. Broadcasts {@link #ACTION_UPDATED}.
     */
    public static synchronized void set(@NonNull Context ctx, @NonNull String pkg,
                                        @NonNull String facing,
                                        @Nullable String imageUri,
                                        @Nullable String videoUri) {
        facing = normalizeFacing(facing);
        List<Mapping> all = new ArrayList<>(all(ctx));
        int found = -1;
        for (int i = 0; i < all.size(); i++) {
            Mapping m = all.get(i);
            if (m.pkg.equals(pkg) && m.facing.equals(facing)) { found = i; break; }
        }
        Mapping next = new Mapping(pkg, facing, nullable(imageUri), nullable(videoUri));
        if (found >= 0) {
            if (next.isEmpty()) all.remove(found);
            else all.set(found, next);
        } else if (!next.isEmpty()) {
            all.add(next);
        }
        writeAll(ctx, all);
        broadcastUpdated(ctx);
    }

    /**
     * Update only one of the two URIs, preserving the other.
     */
    public static void setOne(@NonNull Context ctx, @NonNull String pkg, @NonNull String facing,
                              @NonNull String type, @Nullable String uri) {
        Mapping cur = get(ctx, pkg, facing);
        String img = cur == null ? null : cur.imageUri;
        String vid = cur == null ? null : cur.videoUri;
        if (TYPE_IMAGE.equals(type)) img = uri;
        else vid = uri;
        set(ctx, pkg, facing, img, vid);
    }

    public static void clear(@NonNull Context ctx, @NonNull String pkg, @NonNull String facing) {
        set(ctx, pkg, facing, null, null);
    }

    /** Remove any mapping that still references {@code (type, id)} in the library. */
    public static synchronized void dropReferencesTo(@NonNull Context ctx,
                                                     @NonNull String type, int id) {
        String target = "content://" + MediaProvider.AUTHORITY + "/" + type + "/" + id;
        List<Mapping> all = new ArrayList<>(all(ctx));
        boolean dirty = false;
        for (int i = 0; i < all.size(); i++) {
            Mapping m = all.get(i);
            boolean imgHit = target.equals(m.imageUri);
            boolean vidHit = target.equals(m.videoUri);
            if (!imgHit && !vidHit) continue;
            all.set(i, new Mapping(m.pkg, m.facing,
                    imgHit ? null : m.imageUri,
                    vidHit ? null : m.videoUri));
            dirty = true;
        }
        if (dirty) {
            // Drop now-empty rows.
            for (int i = all.size() - 1; i >= 0; i--) {
                if (all.get(i).isEmpty()) all.remove(i);
            }
            writeAll(ctx, all);
            broadcastUpdated(ctx);
        }
    }

    private static synchronized void writeAll(@NonNull Context ctx, @NonNull List<Mapping> all) {
        JSONArray arr = new JSONArray();
        try {
            for (Mapping m : all) {
                JSONObject o = new JSONObject();
                o.put("pkg", m.pkg);
                o.put("facing", m.facing);
                o.put("imageUri", m.imageUri == null ? JSONObject.NULL : m.imageUri);
                o.put("videoUri", m.videoUri == null ? JSONObject.NULL : m.videoUri);
                arr.put(o);
            }
        } catch (Throwable ignored) {}
        ctx.getSharedPreferences(VCAMApp.PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, arr.toString()).apply();
    }

    private static void broadcastUpdated(@NonNull Context ctx) {
        try {
            Intent i = new Intent(ACTION_UPDATED);
            // Keep the broadcast scoped to apps that are actually watching via
            // their own exported receivers — but don't restrict to a package
            // so hooked host processes (under unpredictable UIDs) can observe.
            ctx.sendBroadcast(i);
        } catch (Throwable ignored) {}
    }
}
