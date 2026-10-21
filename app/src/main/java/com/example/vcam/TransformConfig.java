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
import java.util.Collections;
import java.util.List;

/**
 * First-class spatial transform, photometric, playback, cue-point, and source
 * configuration model for VCAM.
 *
 * <p>Can be scoped globally ({@code pkg="*"}) or per-host/per-facing
 * ({@code pkg="com.whatsapp", facing="front"}). Served cross-process to
 * {@link HookMain} via {@link MediaProvider} and updated in real time from
 * both the Manager UI and the in-app Floating Overlay HUD.
 */
public final class TransformConfig {

    public static final String KEY_GLOBAL_TRANSFORM = "transform_global_json";
    public static final String KEY_SCOPED_PREFIX = "transform_scoped_";
    public static final String KEY_PRESETS = "transform_presets_json";
    public static final String KEY_CUES_PREFIX = "media_cues_";

    // Aspect ratio modes
    public static final String ASPECT_FIT = "fit";         // Letterbox / pillarbox (preserves aspect ratio)
    public static final String ASPECT_FILL = "fill";       // Center-crop (fills target frame, preserves aspect ratio)
    public static final String ASPECT_STRETCH = "stretch"; // Legacy stretch-to-bounds

    // Rotation modes
    public static final int ROT_AUTO = -1; // Auto-compensate using host SENSOR_ORIENTATION + media rotation
    public static final int ROT_0 = 0;
    public static final int ROT_90 = 90;
    public static final int ROT_180 = 180;
    public static final int ROT_270 = 270;

    // Source modes
    public static final String SOURCE_MEDIA = "media";               // Staged file / MediaLibrary
    public static final String SOURCE_STREAM = "stream";             // Network URL (HTTP/HLS/RTSP/MJPEG)
    public static final String SOURCE_TEST_PATTERN = "test_pattern"; // Diagnostic pattern with aspect gauge
    public static final String SOURCE_QR_CODE = "qr_code";           // Live projected QR code

    // Simul-Cam / PIP modes
    public static final String PIP_OFF = "off";
    public static final String PIP_REAL_IN_CORNER = "real_in_corner";
    public static final String PIP_VIRTUAL_IN_CORNER = "virtual_in_corner";

    @NonNull public String aspectMode = ASPECT_FIT;
    public int rotationMode = ROT_AUTO;
    public boolean mirrorH = false;
    public boolean mirrorV = false;
    public float zoom = 1.0f;       // 0.25f .. 5.0f
    public float panX = 0.0f;       // -1.0f .. +1.0f (normalized viewport offset)
    public float panY = 0.0f;       // -1.0f .. +1.0f

    // Photometric & Sensor Fingerprinting Alignment
    public float wbKelvinShift = 0.0f;   // -1.0f (cool/blue) .. +1.0f (warm/amber)
    public float brightnessShift = 0.0f; // -0.5f .. +0.5f
    public boolean sensorGrainEnabled = true;
    public boolean exifSynthEnabled = true;
    public boolean metadataPatchEnabled = true;
    public boolean fpsMatchEnabled = true;

    // Playback & Live Control
    public boolean paused = false;
    public boolean passthroughRealCamera = false;
    @NonNull public String pipMode = PIP_OFF;
    @NonNull public String sourceMode = SOURCE_MEDIA;
    @NonNull public String streamUrl = "";
    @NonNull public String qrPayload = "https://github.com/Steake/com.example.vcam";
    public int activeCueId = -1;
    public long seekRequestMs = -1L;
    public long seekRequestTimestamp = 0L;

    // Advanced Interception Toggles
    public boolean virtualMicEnabled = false;
    public boolean interceptCaptureIntents = false;
    public boolean overlayEnabled = true;

    /** One video bookmark / loop cue point. */
    public static final class CuePoint {
        public final int id;
        @NonNull public final String label;
        public final long startMs;
        public final long endMs; // <= startMs means single-point seek
        public final boolean loop;

        public CuePoint(int id, @NonNull String label, long startMs, long endMs, boolean loop) {
            this.id = id;
            this.label = label;
            this.startMs = Math.max(0L, startMs);
            this.endMs = endMs;
            this.loop = loop;
        }

        @NonNull
        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("id", id);
                o.put("label", label);
                o.put("startMs", startMs);
                o.put("endMs", endMs);
                o.put("loop", loop);
            } catch (Throwable ignored) {}
            return o;
        }

        @Nullable
        public static CuePoint fromJson(@Nullable JSONObject o) {
            if (o == null) return null;
            return new CuePoint(
                    o.optInt("id", 1),
                    o.optString("label", "Cue"),
                    o.optLong("startMs", 0L),
                    o.optLong("endMs", 0L),
                    o.optBoolean("loop", false)
            );
        }
    }

    /** Saved named PTZ/Aspect/Photometric preset. */
    public static final class Preset {
        @NonNull public final String name;
        @NonNull public final TransformConfig config;

        public Preset(@NonNull String name, @NonNull TransformConfig config) {
            this.name = name;
            this.config = config;
        }
    }

    public TransformConfig copy() {
        return fromJson(toJson());
    }

    public void resetSpatial() {
        aspectMode = ASPECT_FIT;
        rotationMode = ROT_AUTO;
        mirrorH = false;
        mirrorV = false;
        zoom = 1.0f;
        panX = 0.0f;
        panY = 0.0f;
        wbKelvinShift = 0.0f;
        brightnessShift = 0.0f;
    }

    /** True when no geometric or color adjustment is required if dimensions already match. */
    public boolean isIdentityTransform() {
        return ASPECT_STRETCH.equals(aspectMode)
                && rotationMode == ROT_0
                && !mirrorH
                && !mirrorV
                && Math.abs(zoom - 1.0f) < 0.001f
                && Math.abs(panX) < 0.001f
                && Math.abs(panY) < 0.001f
                && Math.abs(wbKelvinShift) < 0.001f
                && Math.abs(brightnessShift) < 0.001f
                && PIP_OFF.equals(pipMode);
    }

    @NonNull
    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("aspectMode", aspectMode);
            o.put("rotationMode", rotationMode);
            o.put("mirrorH", mirrorH);
            o.put("mirrorV", mirrorV);
            o.put("zoom", (double) zoom);
            o.put("panX", (double) panX);
            o.put("panY", (double) panY);
            o.put("wbKelvinShift", (double) wbKelvinShift);
            o.put("brightnessShift", (double) brightnessShift);
            o.put("sensorGrainEnabled", sensorGrainEnabled);
            o.put("exifSynthEnabled", exifSynthEnabled);
            o.put("metadataPatchEnabled", metadataPatchEnabled);
            o.put("fpsMatchEnabled", fpsMatchEnabled);
            o.put("paused", paused);
            o.put("passthroughRealCamera", passthroughRealCamera);
            o.put("pipMode", pipMode);
            o.put("sourceMode", sourceMode);
            o.put("streamUrl", streamUrl);
            o.put("qrPayload", qrPayload);
            o.put("activeCueId", activeCueId);
            o.put("seekRequestMs", seekRequestMs);
            o.put("seekRequestTimestamp", seekRequestTimestamp);
            o.put("virtualMicEnabled", virtualMicEnabled);
            o.put("interceptCaptureIntents", interceptCaptureIntents);
            o.put("overlayEnabled", overlayEnabled);
        } catch (Throwable ignored) {}
        return o;
    }

    @NonNull
    public static TransformConfig fromJson(@Nullable JSONObject o) {
        TransformConfig c = new TransformConfig();
        if (o == null) return c;
        String aspect = o.optString("aspectMode", ASPECT_FIT);
        if (ASPECT_FIT.equals(aspect) || ASPECT_FILL.equals(aspect) || ASPECT_STRETCH.equals(aspect)) {
            c.aspectMode = aspect;
        }
        c.rotationMode = o.optInt("rotationMode", ROT_AUTO);
        c.mirrorH = o.optBoolean("mirrorH", false);
        c.mirrorV = o.optBoolean("mirrorV", false);
        c.zoom = clamp((float) o.optDouble("zoom", 1.0), 0.25f, 5.0f);
        c.panX = clamp((float) o.optDouble("panX", 0.0), -1.0f, 1.0f);
        c.panY = clamp((float) o.optDouble("panY", 0.0), -1.0f, 1.0f);
        c.wbKelvinShift = clamp((float) o.optDouble("wbKelvinShift", 0.0), -1.0f, 1.0f);
        c.brightnessShift = clamp((float) o.optDouble("brightnessShift", 0.0), -0.5f, 0.5f);
        c.sensorGrainEnabled = o.optBoolean("sensorGrainEnabled", true);
        c.exifSynthEnabled = o.optBoolean("exifSynthEnabled", true);
        c.metadataPatchEnabled = o.optBoolean("metadataPatchEnabled", true);
        c.fpsMatchEnabled = o.optBoolean("fpsMatchEnabled", true);
        c.paused = o.optBoolean("paused", false);
        c.passthroughRealCamera = o.optBoolean("passthroughRealCamera", false);
        c.pipMode = o.optString("pipMode", PIP_OFF);
        c.sourceMode = o.optString("sourceMode", SOURCE_MEDIA);
        c.streamUrl = o.optString("streamUrl", "");
        c.qrPayload = o.optString("qrPayload", "https://github.com/Steake/com.example.vcam");
        c.activeCueId = o.optInt("activeCueId", -1);
        c.seekRequestMs = o.optLong("seekRequestMs", -1L);
        c.seekRequestTimestamp = o.optLong("seekRequestTimestamp", 0L);
        c.virtualMicEnabled = o.optBoolean("virtualMicEnabled", false);
        c.interceptCaptureIntents = o.optBoolean("interceptCaptureIntents", false);
        c.overlayEnabled = o.optBoolean("overlayEnabled", true);
        return c;
    }

    @NonNull
    public static TransformConfig fromJsonString(@Nullable String raw) {
        if (TextUtils.isEmpty(raw)) return new TransformConfig();
        try {
            return fromJson(new JSONObject(raw));
        } catch (Throwable t) {
            return new TransformConfig();
        }
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    // ------------------------------------------------------------------
    // Persistence & Resolution Helpers
    // ------------------------------------------------------------------

    @NonNull
    public static TransformConfig loadGlobal(@NonNull Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(VCAMApp.PREFS, Context.MODE_PRIVATE);
        return fromJsonString(sp.getString(KEY_GLOBAL_TRANSFORM, null));
    }

    public static void saveGlobal(@NonNull Context ctx, @NonNull TransformConfig cfg) {
        SharedPreferences sp = ctx.getSharedPreferences(VCAMApp.PREFS, Context.MODE_PRIVATE);
        sp.edit().putString(KEY_GLOBAL_TRANSFORM, cfg.toJson().toString()).apply();
        notifyUpdated(ctx);
    }

    @NonNull
    public static TransformConfig resolve(@NonNull Context ctx, @Nullable String pkg, @Nullable String facing) {
        SharedPreferences sp = ctx.getSharedPreferences(VCAMApp.PREFS, Context.MODE_PRIVATE);
        if (!TextUtils.isEmpty(pkg) && !MediaMappings.PKG_GLOBAL.equals(pkg)) {
            if (!TextUtils.isEmpty(facing) && !MediaMappings.FACING_ANY.equals(facing)) {
                String exact = sp.getString(KEY_SCOPED_PREFIX + pkg + "|" + facing, null);
                if (!TextUtils.isEmpty(exact)) return fromJsonString(exact);
            }
            String anyFacing = sp.getString(KEY_SCOPED_PREFIX + pkg + "|" + MediaMappings.FACING_ANY, null);
            if (!TextUtils.isEmpty(anyFacing)) return fromJsonString(anyFacing);
        }
        if (!TextUtils.isEmpty(facing) && !MediaMappings.FACING_ANY.equals(facing)) {
            String globalFacing = sp.getString(KEY_SCOPED_PREFIX + MediaMappings.PKG_GLOBAL + "|" + facing, null);
            if (!TextUtils.isEmpty(globalFacing)) return fromJsonString(globalFacing);
        }
        return loadGlobal(ctx);
    }

    public static void saveScoped(@NonNull Context ctx, @Nullable String pkg, @Nullable String facing,
                                  @NonNull TransformConfig cfg) {
        if (TextUtils.isEmpty(pkg) || (MediaMappings.PKG_GLOBAL.equals(pkg)
                && (TextUtils.isEmpty(facing) || MediaMappings.FACING_ANY.equals(facing)))) {
            saveGlobal(ctx, cfg);
            return;
        }
        String normFacing = MediaMappings.normalizeFacing(facing);
        SharedPreferences sp = ctx.getSharedPreferences(VCAMApp.PREFS, Context.MODE_PRIVATE);
        sp.edit().putString(KEY_SCOPED_PREFIX + pkg + "|" + normFacing, cfg.toJson().toString()).apply();
        notifyUpdated(ctx);
    }

    // ------------------------------------------------------------------
    // Presets
    // ------------------------------------------------------------------

    @NonNull
    public static List<Preset> listPresets(@NonNull Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(VCAMApp.PREFS, Context.MODE_PRIVATE);
        String raw = sp.getString(KEY_PRESETS, null);
        List<Preset> list = new ArrayList<>();
        if (TextUtils.isEmpty(raw)) {
            // Built-in helpful defaults
            TransformConfig fitAuto = new TransformConfig();
            fitAuto.aspectMode = ASPECT_FIT;
            fitAuto.rotationMode = ROT_AUTO;
            list.add(new Preset("Auto Fit", fitAuto));

            TransformConfig selfieMirror = new TransformConfig();
            selfieMirror.aspectMode = ASPECT_FILL;
            selfieMirror.rotationMode = ROT_AUTO;
            selfieMirror.mirrorH = true;
            list.add(new Preset("Selfie Fill + Mirror", selfieMirror));

            TransformConfig closeup = new TransformConfig();
            closeup.aspectMode = ASPECT_FILL;
            closeup.rotationMode = ROT_AUTO;
            closeup.zoom = 1.4f;
            list.add(new Preset("1.4x Center Zoom", closeup));
            return list;
        }
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                list.add(new Preset(
                        o.optString("name", "Preset " + (i + 1)),
                        fromJson(o.optJSONObject("config"))
                ));
            }
        } catch (Throwable ignored) {}
        return list;
    }

    public static synchronized void savePreset(@NonNull Context ctx, @NonNull String name,
                                               @NonNull TransformConfig config) {
        List<Preset> all = new ArrayList<>(listPresets(ctx));
        int idx = -1;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).name.equalsIgnoreCase(name)) {
                idx = i;
                break;
            }
        }
        Preset p = new Preset(name, config.copy());
        if (idx >= 0) all.set(idx, p);
        else all.add(p);

        JSONArray arr = new JSONArray();
        try {
            for (Preset item : all) {
                JSONObject o = new JSONObject();
                o.put("name", item.name);
                o.put("config", item.config.toJson());
                arr.put(o);
            }
        } catch (Throwable ignored) {}
        ctx.getSharedPreferences(VCAMApp.PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_PRESETS, arr.toString()).apply();
    }

    // ------------------------------------------------------------------
    // Cue Points
    // ------------------------------------------------------------------

    @NonNull
    public static List<CuePoint> listCues(@NonNull Context ctx, @Nullable String mediaKey) {
        String key = KEY_CUES_PREFIX + (TextUtils.isEmpty(mediaKey) ? "default" : mediaKey);
        SharedPreferences sp = ctx.getSharedPreferences(VCAMApp.PREFS, Context.MODE_PRIVATE);
        String raw = sp.getString(key, null);
        if (TextUtils.isEmpty(raw)) return Collections.emptyList();
        List<CuePoint> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                CuePoint cp = CuePoint.fromJson(arr.optJSONObject(i));
                if (cp != null) out.add(cp);
            }
        } catch (Throwable ignored) {}
        return out;
    }

    public static synchronized void saveCues(@NonNull Context ctx, @Nullable String mediaKey,
                                             @NonNull List<CuePoint> cues) {
        String key = KEY_CUES_PREFIX + (TextUtils.isEmpty(mediaKey) ? "default" : mediaKey);
        JSONArray arr = new JSONArray();
        for (CuePoint cp : cues) {
            arr.put(cp.toJson());
        }
        ctx.getSharedPreferences(VCAMApp.PREFS, Context.MODE_PRIVATE)
                .edit().putString(key, arr.toString()).apply();
        notifyUpdated(ctx);
    }

    public static void notifyUpdated(@NonNull Context ctx) {
        try {
            Intent i = new Intent(MediaMappings.ACTION_UPDATED);
            ctx.sendBroadcast(i);
        } catch (Throwable ignored) {}
    }
}
