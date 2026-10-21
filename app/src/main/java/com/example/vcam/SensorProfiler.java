package com.example.vcam;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Build;
import android.text.TextUtils;
import android.util.Range;
import android.util.Size;
import android.util.SizeF;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Hardware camera sensor profiler and host camera telemetry repository.
 *
 * <p>1. <b>Hardware Sensor Fingerprinting</b>: Queries {@link CameraManager#getCameraCharacteristics}
 * (which reads static HAL metadata from {@code cameraserver} in &lt;2ms without opening the
 * physical camera or triggering privacy indicators) to capture the exact physical capabilities
 * of the phone's front and back sensors:
 * <ul>
 *   <li>Active pixel array size ({@code SENSOR_INFO_ACTIVE_ARRAY_SIZE})</li>
 *   <li>Physical sensor dimensions in mm ({@code SENSOR_INFO_PHYSICAL_SIZE})</li>
 *   <li>Focal lengths &amp; apertures ({@code LENS_INFO_AVAILABLE_FOCAL_LENGTHS}, {@code APERTURES})</li>
 *   <li>ISO sensitivity range ({@code SENSOR_INFO_SENSITIVITY_RANGE})</li>
 *   <li>Exposure time range in ns ({@code SENSOR_INFO_EXPOSURE_TIME_RANGE})</li>
 *   <li>Target AE FPS ranges ({@code CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES})</li>
 *   <li>Sensor mount orientation ({@code SENSOR_ORIENTATION}) &amp; timestamp source</li>
 *   <li>Device build identity ({@code Build.MANUFACTURER}, {@code Build.MODEL}, {@code Build.DEVICE})</li>
 * </ul>
 *
 * <p>2. <b>Live Host Camera Telemetry</b>: Records the exact preview/capture dimensions,
 * image format, API level (Camera1 vs Camera2), and sensor orientation requested by each
 * hooked host application so the Manager UI can display badges and 1-tap normalize videos.
 */
public final class SensorProfiler {

    public static final String KEY_SENSOR_PROFILES = "device_sensor_profiles_json";
    public static final String KEY_HOST_TELEMETRY = "host_camera_telemetry_json";

    /** Flag allowing calibration tools to bypass HookMain interception inside com.example.vcam. */
    public static volatile boolean bypassHookForProfiler = false;

    private SensorProfiler() {}

    /** Hardware fingerprint for one physical/logical camera lens on the device. */
    public static final class LensProfile {
        @NonNull public String cameraId = "0";
        @NonNull public String facing = MediaMappings.FACING_BACK;
        public int sensorOrientation = 90;
        public int activeArrayWidth = 4032;
        public int activeArrayHeight = 3024;
        public int activeWidth = 4032;
        public int activeHeight = 3024;
        public float physicalWidthMm = 5.6f;
        public float physicalHeightMm = 4.2f;
        public float focalLengthMm = 4.25f;
        public float apertureFNumber = 1.8f;
        public int minIso = 50;
        public int maxIso = 3200;
        public long minExposureNs = 100_000L;       // 0.1 ms
        public long maxExposureNs = 100_000_000L;   // 100 ms
        public int minFps = 15;
        public int maxFps = 30;
        public int hardwareLevel = 1;
        public int timestampSource = 0; // 0 = UNKNOWN/纳米, 1 = REALTIME
        @NonNull public String manufacturer = Build.MANUFACTURER != null ? Build.MANUFACTURER : "Generic";
        @NonNull public String model = Build.MODEL != null ? Build.MODEL : "Android";
        @NonNull public String buildFingerprint = Build.FINGERPRINT != null ? Build.FINGERPRINT : "";
        @NonNull public List<String> supportedYuvSizes = new ArrayList<>();
        @NonNull public List<String> supportedJpegSizes = new ArrayList<>();
        public long profiledAt = 0L;

        @NonNull
        public static LensProfile defaultForFacing(@Nullable String facing) {
            LensProfile p = new LensProfile();
            if (MediaMappings.FACING_FRONT.equals(facing)) {
                p.cameraId = "1";
                p.facing = MediaMappings.FACING_FRONT;
                p.sensorOrientation = 270;
                p.activeArrayWidth = 3264;
                p.activeArrayHeight = 2448;
                p.activeWidth = 3264;
                p.activeHeight = 2448;
                p.focalLengthMm = 2.85f;
                p.apertureFNumber = 2.2f;
            } else {
                p.cameraId = "0";
                p.facing = MediaMappings.FACING_BACK;
                p.sensorOrientation = 90;
            }
            return p;
        }

        @NonNull
        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                int w = activeArrayWidth > 0 ? activeArrayWidth : activeWidth;
                int h = activeArrayHeight > 0 ? activeArrayHeight : activeHeight;
                o.put("cameraId", cameraId);
                o.put("facing", facing);
                o.put("sensorOrientation", sensorOrientation);
                o.put("activeArrayWidth", w);
                o.put("activeArrayHeight", h);
                o.put("physicalWidthMm", (double) physicalWidthMm);
                o.put("physicalHeightMm", (double) physicalHeightMm);
                o.put("focalLengthMm", (double) focalLengthMm);
                o.put("apertureFNumber", (double) apertureFNumber);
                o.put("minIso", minIso);
                o.put("maxIso", maxIso);
                o.put("minExposureNs", minExposureNs);
                o.put("maxExposureNs", maxExposureNs);
                o.put("minFps", minFps);
                o.put("maxFps", maxFps);
                o.put("hardwareLevel", hardwareLevel);
                o.put("timestampSource", timestampSource);
                o.put("manufacturer", manufacturer);
                o.put("model", model);
                o.put("buildFingerprint", buildFingerprint);
                o.put("profiledAt", profiledAt);
                JSONArray yuvArr = new JSONArray();
                for (String s : supportedYuvSizes) yuvArr.put(s);
                o.put("supportedYuvSizes", yuvArr);
                JSONArray jpgArr = new JSONArray();
                for (String s : supportedJpegSizes) jpgArr.put(s);
                o.put("supportedJpegSizes", jpgArr);
            } catch (Throwable ignored) {}
            return o;
        }

        @NonNull
        public static LensProfile fromJson(@Nullable String raw) {
            if (TextUtils.isEmpty(raw)) return new LensProfile();
            try {
                return fromJson(new JSONObject(raw));
            } catch (Throwable t) {
                return new LensProfile();
            }
        }

        @NonNull
        public static LensProfile fromJson(@Nullable JSONObject o) {
            LensProfile p = new LensProfile();
            if (o == null) return p;
            p.cameraId = o.optString("cameraId", "0");
            p.facing = o.optString("facing", MediaMappings.FACING_BACK);
            p.sensorOrientation = o.optInt("sensorOrientation", 90);
            p.activeArrayWidth = o.optInt("activeArrayWidth", 4032);
            p.activeArrayHeight = o.optInt("activeArrayHeight", 3024);
            p.activeWidth = p.activeArrayWidth;
            p.activeHeight = p.activeArrayHeight;
            p.physicalWidthMm = (float) o.optDouble("physicalWidthMm", 5.6);
            p.physicalHeightMm = (float) o.optDouble("physicalHeightMm", 4.2);
            p.focalLengthMm = (float) o.optDouble("focalLengthMm", 4.25);
            p.apertureFNumber = (float) o.optDouble("apertureFNumber", 1.8);
            p.minIso = o.optInt("minIso", 50);
            p.maxIso = o.optInt("maxIso", 3200);
            p.minExposureNs = o.optLong("minExposureNs", 100_000L);
            p.maxExposureNs = o.optLong("maxExposureNs", 100_000_000L);
            p.minFps = o.optInt("minFps", 15);
            p.maxFps = o.optInt("maxFps", 30);
            p.hardwareLevel = o.optInt("hardwareLevel", 1);
            p.timestampSource = o.optInt("timestampSource", 0);
            p.manufacturer = o.optString("manufacturer", p.manufacturer);
            p.model = o.optString("model", p.model);
            p.buildFingerprint = o.optString("buildFingerprint", p.buildFingerprint);
            p.profiledAt = o.optLong("profiledAt", 0L);

            JSONArray yuvArr = o.optJSONArray("supportedYuvSizes");
            if (yuvArr != null) {
                for (int i = 0; i < yuvArr.length(); i++) {
                    p.supportedYuvSizes.add(yuvArr.optString(i));
                }
            }
            JSONArray jpgArr = o.optJSONArray("supportedJpegSizes");
            if (jpgArr != null) {
                for (int i = 0; i < jpgArr.length(); i++) {
                    p.supportedJpegSizes.add(jpgArr.optString(i));
                }
            }
            return p;
        }

        /** Compute 35mm-equivalent focal length for EXIF TagFocalLengthIn35mmFilm. */
        public int focalLength35mmEquivalent() {
            if (physicalWidthMm <= 0.1f) return 26;
            double diagSensor = Math.hypot(physicalWidthMm, physicalHeightMm);
            double diag35mm = 43.2666; // sqrt(36^2 + 24^2)
            return Math.max(12, Math.min(200, (int) Math.round(focalLengthMm * (diag35mm / diagSensor))));
        }

        @NonNull
        public String summary() {
            return summaryLine();
        }

        @NonNull
        public String summaryLine() {
            return String.format(Locale.US,
                    "%s (ID %s): %dx%d · %.2fmm f/%.1f · ISO %d–%d · %d° · %d–%dfps",
                    facing.toUpperCase(Locale.US), cameraId,
                    activeArrayWidth, activeArrayHeight,
                    focalLengthMm, apertureFNumber,
                    minIso, maxIso, sensorOrientation, minFps, maxFps);
        }
    }

    /** Telemetry snapshot of what a hooked host application requested from the camera. */
    public static final class HostTelemetry {
        @NonNull public final String pkg;
        @NonNull public final String facing;
        @NonNull public final String api; // "Camera1" or "Camera2"
        public final int previewWidth;
        public final int previewHeight;
        public final int captureWidth;
        public final int captureHeight;
        public final int imageFormat;
        public final int sensorOrientation;
        public final int targetFps;
        public final long updatedAt;

        public HostTelemetry(@NonNull String pkg, @NonNull String facing, @NonNull String api,
                             int previewWidth, int previewHeight, int captureWidth, int captureHeight,
                             int imageFormat, int sensorOrientation, int targetFps, long updatedAt) {
            this.pkg = pkg;
            this.facing = facing;
            this.api = api;
            this.previewWidth = previewWidth;
            this.previewHeight = previewHeight;
            this.captureWidth = captureWidth;
            this.captureHeight = captureHeight;
            this.imageFormat = imageFormat;
            this.sensorOrientation = sensorOrientation;
            this.targetFps = targetFps;
            this.updatedAt = updatedAt;
        }

        public HostTelemetry(@NonNull String pkg, @NonNull String api, @NonNull String facing,
                             int width, int height, int imageFormat, int minFps, int maxFps, long updatedAt) {
            this(pkg, facing, api, width, height, width, height, imageFormat,
                    MediaMappings.FACING_FRONT.equals(facing) ? 270 : 90, maxFps, updatedAt);
        }

        @NonNull
        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("pkg", pkg);
                o.put("facing", facing);
                o.put("api", api);
                o.put("previewWidth", previewWidth);
                o.put("previewHeight", previewHeight);
                o.put("captureWidth", captureWidth);
                o.put("captureHeight", captureHeight);
                o.put("imageFormat", imageFormat);
                o.put("sensorOrientation", sensorOrientation);
                o.put("targetFps", targetFps);
                o.put("updatedAt", updatedAt);
            } catch (Throwable ignored) {}
            return o;
        }

        @Nullable
        public static HostTelemetry fromJson(@Nullable JSONObject o) {
            if (o == null) return null;
            String pkg = o.optString("pkg", "");
            if (TextUtils.isEmpty(pkg)) return null;
            return new HostTelemetry(
                    pkg,
                    o.optString("facing", MediaMappings.FACING_BACK),
                    o.optString("api", "Camera2"),
                    o.optInt("previewWidth", 1280),
                    o.optInt("previewHeight", 720),
                    o.optInt("captureWidth", 1280),
                    o.optInt("captureHeight", 720),
                    o.optInt("imageFormat", ImageFormat.YUV_420_888),
                    o.optInt("sensorOrientation", 90),
                    o.optInt("targetFps", 30),
                    o.optLong("updatedAt", 0L)
            );
        }

        @NonNull
        public String badgeSummary() {
            int w = previewWidth > 0 ? previewWidth : captureWidth;
            int h = previewHeight > 0 ? previewHeight : captureHeight;
            return String.format(Locale.US, "%s · %dx%d · %s · %d°",
                    api, w, h, formatName(imageFormat), sensorOrientation);
        }

        @NonNull
        public static String formatName(int fmt) {
            switch (fmt) {
                case ImageFormat.JPEG: return "JPEG";
                case ImageFormat.YUV_420_888: return "YUV_420_888";
                case ImageFormat.NV21: return "NV21";
                case ImageFormat.YV12: return "YV12";
                default: return "fmt=" + fmt;
            }
        }
    }

    // ------------------------------------------------------------------
    // Hardware Sensor Profiling Execution
    // ------------------------------------------------------------------

    /**
     * Profile all cameras on the current device using {@link CameraManager}
     * static characteristics and persist to {@link VCAMApp#PREFS}.
     */
    @NonNull
    public static synchronized List<LensProfile> profileDeviceCameras(@NonNull Context ctx) {
        List<LensProfile> profiles = new ArrayList<>();
        try {
            CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
            if (cm != null) {
                String[] ids = cm.getCameraIdList();
                for (String id : ids) {
                    LensProfile lp = inspectCameraCharacteristics(cm, id);
                    if (lp != null) profiles.add(lp);
                }
            }
        } catch (Throwable ignored) {}

        if (profiles.isEmpty()) {
            // Fallback synthetic profiles (e.g. emulator with no camera HAL)
            LensProfile back = new LensProfile();
            back.cameraId = "0";
            back.facing = MediaMappings.FACING_BACK;
            back.sensorOrientation = 90;
            back.profiledAt = System.currentTimeMillis();
            profiles.add(back);

            LensProfile front = new LensProfile();
            front.cameraId = "1";
            front.facing = MediaMappings.FACING_FRONT;
            front.sensorOrientation = 270;
            front.focalLengthMm = 2.85f;
            front.apertureFNumber = 2.2f;
            front.profiledAt = System.currentTimeMillis();
            profiles.add(front);
        }

        JSONArray arr = new JSONArray();
        for (LensProfile p : profiles) {
            arr.put(p.toJson());
        }
        ctx.getSharedPreferences(VCAMApp.PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_SENSOR_PROFILES, arr.toString()).apply();
        return profiles;
    }

    @Nullable
    private static LensProfile inspectCameraCharacteristics(@NonNull CameraManager cm,
                                                            @NonNull String id) {
        try {
            CameraCharacteristics cc = cm.getCameraCharacteristics(id);
            LensProfile p = new LensProfile();
            p.cameraId = id;
            p.profiledAt = System.currentTimeMillis();

            Integer lf = cc.get(CameraCharacteristics.LENS_FACING);
            if (lf != null && lf == CameraMetadata.LENS_FACING_FRONT) {
                p.facing = MediaMappings.FACING_FRONT;
                p.sensorOrientation = 270;
            } else {
                p.facing = MediaMappings.FACING_BACK;
                p.sensorOrientation = 90;
            }

            Integer orient = cc.get(CameraCharacteristics.SENSOR_ORIENTATION);
            if (orient != null) p.sensorOrientation = orient;

            Rect active = cc.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            if (active != null && active.width() > 0 && active.height() > 0) {
                p.activeArrayWidth = active.width();
                p.activeArrayHeight = active.height();
                p.activeWidth = p.activeArrayWidth;
                p.activeHeight = p.activeArrayHeight;
            }

            SizeF phys = cc.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
            if (phys != null && phys.getWidth() > 0 && phys.getHeight() > 0) {
                p.physicalWidthMm = phys.getWidth();
                p.physicalHeightMm = phys.getHeight();
            }

            float[] focals = cc.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
            if (focals != null && focals.length > 0 && focals[0] > 0) {
                p.focalLengthMm = focals[0];
            }

            float[] apertures = cc.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES);
            if (apertures != null && apertures.length > 0 && apertures[0] > 0) {
                p.apertureFNumber = apertures[0];
            }

            Range<Integer> isoRange = cc.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
            if (isoRange != null) {
                p.minIso = Math.max(10, isoRange.getLower());
                p.maxIso = Math.max(p.minIso + 100, isoRange.getUpper());
            }

            Range<Long> expRange = cc.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
            if (expRange != null) {
                p.minExposureNs = Math.max(1000L, expRange.getLower());
                p.maxExposureNs = Math.max(p.minExposureNs + 1000L, expRange.getUpper());
            }

            Range<Integer>[] fpsRanges = cc.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
            if (fpsRanges != null && fpsRanges.length > 0) {
                int minF = 30;
                int maxF = 30;
                for (Range<Integer> r : fpsRanges) {
                    if (r.getLower() < minF) minF = r.getLower();
                    if (r.getUpper() > maxF) maxF = r.getUpper();
                }
                p.minFps = minF;
                p.maxFps = maxF;
            }

            Integer hwLevel = cc.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
            if (hwLevel != null) p.hardwareLevel = hwLevel;

            Integer tsSource = cc.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE);
            if (tsSource != null) p.timestampSource = tsSource;

            StreamConfigurationMap map = cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map != null) {
                Size[] yuvs = map.getOutputSizes(ImageFormat.YUV_420_888);
                if (yuvs != null) {
                    for (int i = 0; i < Math.min(yuvs.length, 12); i++) {
                        p.supportedYuvSizes.add(yuvs[i].getWidth() + "x" + yuvs[i].getHeight());
                    }
                }
                Size[] jpgs = map.getOutputSizes(ImageFormat.JPEG);
                if (jpgs != null) {
                    for (int i = 0; i < Math.min(jpgs.length, 12); i++) {
                        p.supportedJpegSizes.add(jpgs[i].getWidth() + "x" + jpgs[i].getHeight());
                    }
                }
            }
            return p;
        } catch (Throwable t) {
            return null;
        }
    }

    public static boolean hasSavedProfiles(@NonNull Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(VCAMApp.PREFS, Context.MODE_PRIVATE);
        String raw = sp.getString(KEY_SENSOR_PROFILES, null);
        return !TextUtils.isEmpty(raw);
    }

    public static int profileAndSaveAllCameras(@NonNull Context ctx) {
        return profileDeviceCameras(ctx).size();
    }

    @NonNull
    public static LensProfile loadProfile(@NonNull Context ctx, @Nullable String facing) {
        return getProfileForFacing(ctx, facing);
    }

    @NonNull
    public static List<LensProfile> getProfiles(@NonNull Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(VCAMApp.PREFS, Context.MODE_PRIVATE);
        String raw = sp.getString(KEY_SENSOR_PROFILES, null);
        if (TextUtils.isEmpty(raw)) {
            return profileDeviceCameras(ctx);
        }
        List<LensProfile> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                out.add(LensProfile.fromJson(arr.optJSONObject(i)));
            }
        } catch (Throwable ignored) {}
        return out.isEmpty() ? profileDeviceCameras(ctx) : out;
    }

    @NonNull
    public static LensProfile getProfileForFacing(@NonNull Context ctx, @Nullable String facing) {
        String want = MediaMappings.FACING_FRONT.equals(facing)
                ? MediaMappings.FACING_FRONT : MediaMappings.FACING_BACK;
        List<LensProfile> all = getProfiles(ctx);
        for (LensProfile p : all) {
            if (want.equals(p.facing)) return p;
        }
        return all.isEmpty() ? LensProfile.defaultForFacing(want) : all.get(0);
    }

    // ------------------------------------------------------------------
    // Host Telemetry Persistence
    // ------------------------------------------------------------------

    @Nullable
    public static HostTelemetry loadTelemetry(@NonNull Context ctx, @Nullable String pkg) {
        if (TextUtils.isEmpty(pkg)) return null;
        return getAllHostTelemetry(ctx).get(pkg);
    }

    public static synchronized void recordHostTelemetry(@NonNull Context ctx,
                                                        @NonNull HostTelemetry t) {
        SharedPreferences sp = ctx.getSharedPreferences(VCAMApp.PREFS, Context.MODE_PRIVATE);
        String raw = sp.getString(KEY_HOST_TELEMETRY, "[]");
        List<HostTelemetry> list = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                HostTelemetry item = HostTelemetry.fromJson(arr.optJSONObject(i));
                if (item != null && !item.pkg.equals(t.pkg)) {
                    list.add(item);
                }
            }
        } catch (Throwable ignored) {}
        list.add(t);

        JSONArray out = new JSONArray();
        for (HostTelemetry item : list) {
            out.put(item.toJson());
        }
        sp.edit().putString(KEY_HOST_TELEMETRY, out.toString()).apply();
    }

    @NonNull
    public static Map<String, HostTelemetry> getAllHostTelemetry(@NonNull Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(VCAMApp.PREFS, Context.MODE_PRIVATE);
        String raw = sp.getString(KEY_HOST_TELEMETRY, "[]");
        Map<String, HostTelemetry> map = new HashMap<>();
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                HostTelemetry item = HostTelemetry.fromJson(arr.optJSONObject(i));
                if (item != null) map.put(item.pkg, item);
            }
        } catch (Throwable ignored) {}
        return map;
    }
}
