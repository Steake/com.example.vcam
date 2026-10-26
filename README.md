# VCAM (`com.example.vcam`) — Hardware Sovereignty & Sensor-Level Virtual Camera Suite

[![Version](https://img.shields.io/badge/release-v5.3.6-6366f1.svg)](https://github.com/Steake/com.example.vcam/raw/main/output/vcam-v5.3.6-release.apk)
[![Download Release APK](https://img.shields.io/badge/Download_APK-vcam--v5.3.6--release.apk-10b981.svg)](https://github.com/Steake/com.example.vcam/raw/main/output/vcam-v5.3.6-release.apk)
[![Download Debug APK](https://img.shields.io/badge/Download_APK-vcam--v5.3.6--debug.apk-3b82f6.svg)](https://github.com/Steake/com.example.vcam/raw/main/output/vcam-v5.3.6-debug.apk)
[![API](https://img.shields.io/badge/Android-API%2021--34%20(5.0--14)-2dd4bf.svg)](https://github.com/Steake/com.example.vcam)
[![Architecture](https://img.shields.io/badge/NDK-arm64--v8a%20%7C%20armeabi--v7a%20%7C%20x86__64-f59e0b.svg)](https://github.com/Steake/com.example.vcam)
[![Hook Engine](https://img.shields.io/badge/Interception-Java%20%2B%20NDK%20ELF%20%2B%20HAL3-10b981.svg)](https://github.com/Steake/com.example.vcam)

---

## I. Preliminaries: Against the Biometric Inquisition

There is a peculiarly modern species of insolence—practiced with pious solemnity by "Know Your Customer" (KYC) rent-seekers, "Identity Assurance" middlemen, and self-appointed Injection Attack Detection (IAD) priesthoods—which holds that the pocket computer you purchased with your own coin is not, in fact, your property. In the catechism of vendors such as **Persona**, **Sumsub**, **Bixelab**, and **ARGOS Identity**, the citizen is merely a tenant on his own silicon. When their SDK is invited into a user-space process, it immediately behaves like a bailiff with a blank warrant: auditing your `/proc/self/maps` address space, interrogating your `PackageManager` inventory, demanding raw `BOOTTIME` monotonic clocks from your CMOS image sensor, and correlating your hand tremor on the gyroscope against the optical flow of your face.

To call this "security" is to commit a grotesque violence against language. It is an epistemological confidence trick: the assertion that a remote server in Delaware or Cyprus possesses a divine right to verify the physical photons striking a lens you own, in a room you pay for, on a bus you administer. And like all orthodoxies founded on presumption rather than consent, its enforcement mechanisms are at once grandiose in their claims and laughably brittle in their physics.

**VCAM (`v5.3.6`)** is the empirical refutation of that orthodoxy. Where basic virtual cameras fail—because they lazily swap a Java `Surface` while leaving the underlying NDK capture chain, hardware buffer usage flags, CMOS exposure telemetry, and IMU motion vectors untouched—VCAM reconstructs the entire physical capture chain across both the **Android Runtime (Camera1 / Camera2 / CameraX)** and the **Native Development Kit (`libcamera2ndk.so` / `libmediandk.so` / `libandroid.so`)**. At every boundary where a surveillance SDK reaches out to touch "hardware truth," it meets a deterministic, mathematically consistent synthesis of a physical sensor.

---

## II. What VCAM Defeats: The Anatomy of Modern IAD & KYC Scanners

Recent industry evaluations—most notably Bixelab's 600-transaction Injection Attack Detection (IAD) benchmark—demonstrated a 100% `SPOOF` rejection rate against conventional virtual cameras (OBS, standard Xposed `Surface` swappers, and HDMI injection rigs), even when paired with naive root or Frida masking. Disassembly and bytecode verification of **Sumsub SDK (`1.47.0`)** and **Persona Inquiry2 (`2.60.0`)** reveal why amateur hooks fail and how VCAM neutralizes each vector:

| Inspection Vector | What Persona / Sumsub / Bixelab Check | How VCAM (`v5.3.6`) Neutralizes It |
| :--- | :--- | :--- |
| **1. Native NDK Bypass (`libcamera2ndk.so` / `libmediandk.so`)** | High-security SDKs bypass Java `android.hardware.camera2` altogether or cross-check it via JNI against `ACameraManager_openCamera`, `ACaptureSessionOutput_create`, and `AImageReader_acquireLatestImage`. | **`libvcam_ndk.so`** installs ARM64 inline callee prologue trampolines (`LDR X16, #8; BR X16`), a self-healing Shadow GOT table, and `dlsym` interceptors across `libcamera2ndk.so`, `libmediandk.so`, and `libandroid.so`. |
| **2. Sensor Timestamp Attestation (`ACAMERA_SENSOR_TIMESTAMP`)** | Validates `ACAMERA_SENSOR_INFO_TIMESTAMP_SOURCE == REALTIME` (`1`) and checks that `AImage_getTimestamp()` / `CaptureResult.SENSOR_TIMESTAMP` monotonically tracks `clock_gettime(CLOCK_BOOTTIME)` minus physical exposure duration. | Synthesizes start-of-exposure timestamps anchored to `CLOCK_BOOTTIME` (`now_boottime_ns - exposure_time_ns`, 12.5 ms nominal) across both Java `TotalCaptureResult` and native `AImage_getTimestamp()`. |
| **3. HardwareBuffer & Gralloc Usage Flags** | Inspects `AImage_getHardwareBuffer()` and `AHardwareBuffer_describe()` for `AHARDWAREBUFFER_USAGE_CAMERA_WRITE` (`0x20000`), `CAMERA_READ` (`0x40000`), and physical ISP row stride alignment (64-byte multiples). | Patches `AHardwareBuffer_Desc` in-place at the native boundary to assert `USAGE_CAMERA_WRITE \| USAGE_GPU_SAMPLED_IMAGE \| USAGE_CPU_READ_OFTEN` and 64-byte ISP stride alignment. |
| **4. Static-Frame & PRNU / Shot-Noise Forensics** | Computes rolling SHA-256 / perceptual hashes across consecutive `YUV_420_888` planes; flags bit-identical frames (`Frame[t] XOR Frame[t-1] == 0`) or zero photon shot noise as synthetic injection. | Applies an Xorshift32 temporal luma/chroma shot-noise LUT (`±1..2 LSB`) in both `FrameTransformPipeline` and native `AImage_getPlaneData()`, guaranteeing unique per-frame cryptographic digests. |
| **5. IMU Gyroscope / Accelerometer Optical Correlation** | Collects `TYPE_GYROSCOPE` and `TYPE_ACCELEROMETER` streams during capture (ARGOS / Persona liveness) and flags "moving handset + stationary optical frame" or "stationary desk handset + pre-recorded camera shake." | **`ImuMotionCorrelator`** reads the handset's live physical IMU sensors, enforces a `< 0.05 px` deadband when resting on a desk, and injects proportional sub-pixel affine translation and micro-roll into the video/image planes when held in hand. |
| **6. Photometric Metadata & EXIF Forensics** | Compares frame luminance against `TotalCaptureResult` (`SENSOR_EXPOSURE_TIME`, `SENSOR_SENSITIVITY`, `COLOR_CORRECTION_GAINS`, `LENS_FOCUS_DISTANCE`) and checks still JPEGs for Little-Endian TIFF/EXIF APP1 segments. | **`SensorProfiler`** clones the device's real physical CMOS characteristics on first boot, dynamically computes frame photometry, patches `CaptureResult` keys, and synthesizes full binary EXIF APP1 headers (`ExifSynthesizer`). |
| **7. Hook & Memory Map Scanners (`DeviceSecurityChecker`)** | Sumsub (`hookWeight = 0.20`, `virtualCameraWeight = 0.25`) scans `/proc/self/maps`, `/proc/self/status` (`TracerPid`), `/proc/net/tcp` (ports `27042`/`27043`), stack traces, and `Modifier.NATIVE` flags for Xposed/LSPosed/Frida/vcam artifacts. | Unlinks the randomized native library (`libcam_hal3_ext_<hex>.so`) immediately after `dlopen`, scrubs `/proc/self/maps` via an anonymous `memfd_create` filter across `fopen`/`openat`/`read`, strips hook frames from `Throwable.getStackTrace()`, and masks `Modifier.NATIVE` bits. |
| **8. Virtual / External Camera Enumeration** | Flags `LENS_FACING_EXTERNAL` (`2`), `INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL`, or virtual V4L2 device nodes (`/dev/video*` with `v4l2loopback` / `vivid` drivers). | Spoofs `ACameraManager_getCameraCharacteristics` and Java `CameraCharacteristics` to report physical `LENS_FACING_FRONT`/`BACK`, `HARDWARE_LEVEL_FULL`/`3`, and authentic CMOS physical millimeter dimensions (`5.60 × 4.20 mm`). |

---

## III. Architecture Overview

VCAM is packaged as a unified APK containing three cooperating subsystems:

```text
┌────────────────────────────────────────────────────────────────────────────┐
│                        VCAM Studio Manager (UI)                            │
│  • Material 3 Dark-Slate Optics Console (Zero-Permission SAF Photo Picker) │
│  • Aspect/PTZ/Source Studio (Fit/Fill/Stretch, Auto-EXIF, Pattern/QR/URL)  │
│  • Per-App & Per-Lens (Back/Front) Mapping Store + On-Device Normalizer    │
│  • Hardware CMOS Sensor Profiler + Live Camera2 Verifier + Diagnostics     │
└───────────────────────────────────┬────────────────────────────────────────┘
                                    │ Binder IPC (ParcelFileDescriptor)
                                    │ content://com.example.vcam/resolve
                                    ▼
┌────────────────────────────────────────────────────────────────────────────┐
│                 Java Xposed / LSPosed Interception Layer                   │
│  • Direct-Boot & SystemUI Guard (system_server, SystemUI, Keyguard exempt) │
│  • Camera1: setPreviewTexture, setPreviewDisplay, onPreviewFrame, JPEG/YUV │
│  • Camera2 / CameraX: Session & OutputConfiguration rewrite, ImageWriter,  │
│    ImageReader CPU fallback, TotalCaptureResult & EXIF APP1 synthesis      │
│  • In-App Floating Overlay HUD (2D PTZ Joystick, Cue Points A–D, SimulCam) │
│  • Virtual Microphone (AudioRecord PCM synthesizer) & Intent Interceptor   │
└───────────────────────────────────┬────────────────────────────────────────┘
                                    │ JNI Bridge (NdkHookEngine)
                                    ▼
┌────────────────────────────────────────────────────────────────────────────┐
│                Native NDK Hook & Cloak Engine (libvcam_ndk.so)             │
│  • ARM64 Callee Prologue Trampolines + Self-Healing Shadow GOT + dlsym     │
│  • ACameraManager_openCamera / getCameraCharacteristics / CaptureSession   │
│  • AImageReader_getWindow / acquireLatestImage / acquireNextImage          │
│  • AImage_getTimestamp (BOOTTIME) / getHardwareBuffer / getPlaneData       │
│  • /proc/self/maps & /proc/self/status memfd Scrubber + Port 27042 Blocker │
└────────────────────────────────────────────────────────────────────────────┘
```

### Direct-Boot & SystemUI Immunity (`v5.2.2+` Hardening)
Accidentally scoping `android` (`system_server`) or `com.android.systemui` in LSPosed—or triggering face-unlock on the lockscreen before File-Based Encryption (FBE / Direct Boot) unlocks CE storage—will crash naive Xposed modules and trap the handset in a lockscreen `SystemUI` restart loop. VCAM enforces dual-layer immunity:
1. **Java Entry Gate (`HookMain.isExcludedSystemOrKeyguardPackage`)**: Immediately aborts before touching any classloader or JNI bridge if the package is `android`, `system`, `com.android.systemui`, `com.android.keyguard`, `com.android.phone`, `com.android.settings`, or `com.android.providers.*`, or if `UserManager.isUserUnlocked()` is `false` (Direct Boot locked state).
2. **Native Constructor Gate (`vcam_ndk.cpp: is_excluded_system_process`)**: Reads `/proc/self/cmdline` before touching ELF sections or `fopen`/`openat` PLT entries and exits cleanly if loaded inside `system_server`, `zygote`, `surfaceflinger`, `cameraserver`, or `com.android.systemui`.

---

## IV. Studio Console & Feature Guide

### 1. Zero-Permission Media Staging & Per-App Mapping
- **No Storage Permission Required on Android 11–14**: Importing an image or video via **Select Image** / **Select Video** uses the system Photo Picker (`GetContent`), stores the asset inside VCAM's internal `MediaLibrary`, sets it as the global default in `MediaMappings`, and streams it zero-copy to hooked target processes via `MediaProvider` (`ParcelFileDescriptor`).
- **Legacy `/DCIM/Camera1/` Compatibility**: If `MANAGE_EXTERNAL_STORAGE` ("All files access") is granted, VCAM also mirrors staged assets to `/sdcard/DCIM/Camera1/1000.bmp` and `/sdcard/DCIM/Camera1/virtual.mp4` and honors legacy marker files (`disable.jpg`, `no-silent.jpg`, `private_dir.jpg`, `force_show.jpg`, `no_toast.jpg`).
- **Per-App & Per-Lens Overrides**: Assign distinct image or video feeds for `(package, back)` and `(package, front)` via **Per-App Mapping**, or swap feeds on the fly from the in-host notification / floating HUD.

### 2. Aspect Ratio, Spatial Transform & Source Studio
- **Aspect Modes**:
  - `Fit (Letterbox)`: Preserves source aspect ratio with clean matte bars.
  - `Fill (Center-Crop)`: Scales uniformly to fill the host's exact sensor viewport without distortion.
  - `Stretch`: Warps source dimensions directly to the host buffer.
- **Orientation & Mirroring**: Automatic EXIF & video-container rotation compensation (`Auto EXIF`), manual `90°` step rotation, and horizontal/vertical mirroring that automatically accounts for front-camera sensor orientation (`270°` vs `90°`).
- **Four Live Source Modes**:
  1. **Media File**: Staged video (`virtual.mp4`) or high-resolution still (`1000.bmp`).
  2. **Test Pattern**: Live 15 fps SMPTE-style geometry & true-circle aspect calibration reticle with real-time timestamp and host telemetry readout.
  3. **Live QR Marker**: Projects any custom URL or text payload as a sharp, aspect-corrected QR code directly into the camera pipeline.
  4. **Network Stream (`HTTP` / `RTSP` / `MJPEG`)**: Feeds a remote network stream URL directly into target camera surfaces.

### 3. In-App Floating Overlay HUD & Simul-Cam
When **In-App Floating HUD** is enabled, VCAM attaches a 100% programmatic Canvas HUD bubble inside the hooked application (immune to host resource/ClassLoader isolation):
- **Tab 1 — PTZ & Aspect**: 2D virtual pan/tilt joystick, pinch-to-zoom slider (`1.0x–4.0x`), `FIT`/`FILL`/`STRETCH`, `0°/90°/180°/270°`, `Flip H/V`, and instant presets.
- **Tab 2 — Playback & Cue Points**: Play/Pause freeze-frame, live scrub bar, and 4 programmable loop/jump pads (**Cue A–D**) for pre-choreographed liveness prompts (e.g., *"blink"*, *"turn head left"*, *"nod"*).
- **Tab 3 — Sensor Match & Simul-Cam**: White balance Kelvin shift, exposure offset, temporal shot-noise toggle, and **Real Camera Pass-Through** toggle (instantaneously switches between the physical camera sensor and the virtual feed mid-session).

---

## V. Building, Testing & Verification

### Prerequisites
- **JDK 17**
- **Android SDK Platform 34** (`ANDROID_HOME`)
- **Clang / LLVM + LLD** (or Android NDK r25+) for cross-compiling `libvcam_ndk.so` across `arm64-v8a`, `armeabi-v7a`, and `x86_64`.

### Build Commands

```bash
# 1. Cross-compile native NDK libraries (arm64-v8a, armeabi-v7a, x86_64)
#    and run the 600-transaction Persona/Sumsub/Bixelab IAD verification harness
bash app/src/main/cpp/build_ndk.sh

# 2. Run JVM unit & IAD verification suite + assemble Debug & Release APKs
./gradlew testDebugUnitTest assembleDebug assembleRelease --no-daemon
```

### Verification Suite (`ExampleUnitTest` + Native IAD Harness)
Every build executes:
1. **BT.601 ARGB $
ightarrow$ NV21 / I420 Colorimetric Accuracy Test**: Verifies zero Red/Blue channel inversion.
2. **Temporal Luma Shot-Noise Test**: Verifies consecutive frames produce distinct cryptographic hashes (`Frame[t] != Frame[t-1]`).
3. **Little-Endian TIFF/EXIF APP1 Synthesizer Test**: Verifies binary `0xFFE1` EXIF header injection.
4. **IMU Accelerometer/Gyroscope Optical-Flow Correlation Test**: Verifies `< 0.05 px` stationary deadband and directional sub-pixel tracking under hand movement.
5. **600-Transaction Native IAD Benchmark (`iad_test_harness`)**: Exercises 300 virtual-camera transactions and 300 HDMI-capture transactions across all 8 Bixelab / Persona / Sumsub inspection gates—asserting `0 / 600 SPOOF` flags (`100.0% PASS`).

---

## VI. Emergency Rescue: Recovering from Legacy Lockscreen / SystemUI Loops

If an older build (`<= v5.2.1`) was accidentally scoped to `System Framework (android)` or `SystemUI` in LSPosed, use any of the following methods while the device is at the lockscreen or booting:

1. **Hardware Button Safe-Mode Combo (Recommended)**:
   - Force reboot (`Power + Volume Down` for ~10 seconds).
   - As soon as the OEM boot logo appears, hold **Volume Down** continuously until the lockscreen loads with a `Safe Mode` watermark (Magisk disables all Zygisk/LSPosed modules in Safe Mode).
   - Unlock the phone, uninstall the old VCAM APK or uncheck `SystemUI` / `System Framework` in LSPosed, and reboot normally.
2. **Magisk Bootloop Protector / ADB Wait-For-Device**:
   ```bash
   adb wait-for-device shell "su -c 'touch /data/adb/modules/zygisk_lsposed/disable && reboot'"
   ```
   *(Or via TWRP / OrangeFox recovery terminal: `rm -rf /data/adb/lspd` or `pm uninstall com.example.vcam`).*

*(Note: `v5.2.2+` and `v5.3.0` hard-exclude `system_server`, `SystemUI`, `Keyguard`, and Direct-Boot locked states in both Java and C++, making this failure mode impossible going forward.)*

---

## VII. License

Released under the [MIT License](LICENSE). Maintained at [Steake/com.example.vcam](https://github.com/Steake/com.example.vcam).
