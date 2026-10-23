# AGENTS.md — Architecture & Developer Guide (`com.example.vcam`)

## 1. Project Overview

**`com.example.vcam`** (Android Virtual Camera / VCAM) is an Xposed/LSPosed module and Material 3 companion application for Android (API 21–34, `minSdk 21`, `targetSdk 34`). It intercepts the camera pipeline in target applications and substitutes live camera sensor frames and still captures with user-supplied video (`virtual.mp4`) or still images (`1000.bmp`).

The project is split into two runtime contexts packaged in the same APK:
1. **The Xposed Hook Runtime** (`HookMain`, `VideoToFrames`, `StaticBitmapFeeder`): Loaded into target ("host") application processes by LSPosed/Xposed via `assets/xposed_init`.
2. **The Companion Manager App** (`VCAMApp`, `MainActivity`, `MediaLibraryActivity`, `PerAppMappingActivity`, `AppMappingEditActivity`, `InjectedConfigActivity`, `CameraVerifyActivity`, `OnboardingActivity`, `MediaProvider`): Runs in its own process (`com.example.vcam`) to manage media libraries, per-app/per-lens mappings, marker flags, and live camera verification.

---

## 2. Directory & Source Structure

```text
com.example.vcam/
├── .github/workflows/
│   ├── build.yml                # CI: Debug APK on push/PR; Debug + Release APKs & GitHub Release on v* tags
│   └── release.yml              # Manual workflow_dispatch to run bumpversion.sh and push tag
├── app/
│   ├── build.gradle             # Module Gradle config (namespace com.example.vcam, SDK 21..34, Xposed API 82)
│   ├── libs/
│   │   ├── api-82.jar           # Xposed Bridge API (compileOnly)
│   │   └── api-82-sources.jar
│   └── src/main/
│       ├── AndroidManifest.xml  # Activities, MediaProvider, Xposed meta-data, permissions
│       ├── assets/
│       │   └── xposed_init      # Entry point declaration: com.example.vcam.HookMain
│       ├── java/com/example/vcam/
│       │   ├── HookMain.java              # Core Xposed hooks (Camera1, Camera2, permissions, staging)
│       │   ├── VideoToFrames.java         # MediaExtractor + MediaCodec H.264/video decoder (Surface / YUV byte[])
│       │   ├── StaticBitmapFeeder.java    # 15fps ImageWriter / Canvas feeder for image-only Camera2 mode
│       │   ├── MediaProvider.java         # Exported ContentProvider streaming library/staged media to hooked hosts
│       │   ├── MediaLibrary.java          # Internal media repository (filesDir/library/ + SharedPreferences JSON)
│       │   ├── MediaMappings.java         # Per-(pkg, facing) media resolution table & broadcast notifier
│       │   ├── MediaMigration.java        # One-shot migration from legacy DCIM files into MediaLibrary
│       │   ├── MediaPaths.java            # Legacy file paths (/DCIM/Camera1/), thumbnail/metadata helpers
│       │   ├── VCAMApp.java               # Application subclass (DynamicColors + SharedPreferences constants)
│       │   ├── MainActivity.java          # Main Material 3 UI, marker switches, live demo, quick import
│       │   ├── InjectedConfigActivity.java# Translucent overlay activity launched via notification from hooked apps
│       │   ├── CameraVerifyActivity.java  # In-manager Camera2 verifier activity
│       │   ├── MediaLibraryActivity.java  # UI for browsing/importing/deleting MediaLibrary entries
│       │   ├── PerAppMappingActivity.java # UI listing camera-capable apps & mapping badges
│       │   ├── AppMappingEditActivity.java# UI for editing per-app or global back/front/any media slots
│       │   └── OnboardingActivity.java    # First-launch 3-page ViewPager2 onboarding flow
│       └── res/                 # Layouts, Material 3 themes, localized strings (en, zh, zh-rCN, zh-rTW, etc.)
├── build.gradle                 # Top-level Gradle configuration (AGP 8.0.2)
├── bumpversion.sh               # Version bumping & git tagging utility script
└── settings.gradle              # Repositories & module inclusion (:app)
```

---

## 3. Core Architecture & Data Flow

### 3.1 Storage & Cross-Process Staging Contract

Historically, VCAM operated solely on files inside `/storage/emulated/0/DCIM/Camera1/`:
- `virtual.mp4` — Replacement video stream.
- `1000.bmp` — Replacement still image (nominal extension; decoded via `BitmapFactory`, so JPEG/PNG/BMP all work).
- **Marker Flag Files** (checked by file existence in `/DCIM/Camera1/`):
  - `disable.jpg` — Temporarily disables all hook interception.
  - `no-silent.jpg` — Enables audio playback for `virtual.mp4` (muted by default).
  - `private_dir.jpg` — Forces redirection of `video_path` to the host app's private external directory (`/sdcard/Android/data/<host_pkg>/files/Camera1/`).
  - `force_show.jpg` — Forces display of the directory-redirection toast every time.
  - `no_toast.jpg` — Suppresses all informational/warning toast messages from `HookMain`.

#### Modern Android (API 30+) & ContentProvider Staging
On Android 11+ (API 30+), scoped storage prevents most hooked target apps from reading or writing `/sdcard/DCIM/Camera1/` directly, even when permission check APIs are spoofed in Java. VCAM solves this with a three-part mechanism:

1. **Permission Spoofing (`HookMain.applyInjectedStoragePermissions`)**:
   Hooks `ContextImpl.checkPermission`, `ContextImpl.checkSelfPermission`, `ApplicationPackageManager.checkPermission`, and `Environment.isExternalStorageManager()` inside target apps (excluding `com.example.vcam` itself) so Java-level storage permission checks return `PERMISSION_GRANTED` / `true`.
2. **Directory Resolution (`Instrumentation.callApplicationOnCreate` hook)**:
   When the host `Application` starts, `HookMain` probes actual filesystem writability in `/DCIM/Camera1/` (and unconditionally forces private storage on `Build.VERSION.SDK_INT >= Build.VERSION_CODES.R` or when `private_dir.jpg` exists). When redirected, `HookMain.video_path` points to `<host_external_files_dir>/Camera1/`.
3. **Cross-App `MediaProvider` Streaming (`HookMain.ensureStagedNow` / `maybeRestage`)**:
   - The manager app exposes `MediaProvider` (`content://com.example.vcam`), guarded by `enforceCallerIsCameraApp()` which verifies via `Binder.getCallingUid()` and `PackageManager` that the calling UID holds `android.Manifest.permission.CAMERA` (or is the manager's own UID).
   - When the host app opens a camera (`Camera.open` or `CameraManager.openCamera`) or initializes its `Application`, `HookMain` detects the camera lens facing (`back` or `front`) and queries:
     `content://com.example.vcam/resolve?pkg=<host_pkg>&facing=<back|front>&type=<image|video>`
   - `MediaProvider` evaluates `MediaMappings.resolve()` in first-match-wins order:
     1. `(pkg, facing)`
     2. `(pkg, "any")`
     3. `("*", facing)`
     4. `("*", "any")`
     5. Fallback to legacy `content://com.example.vcam/image` or `/video` (which stream `/DCIM/Camera1/1000.bmp` or `virtual.mp4`).
   - `HookMain` streams the resolved content URI into `<video_path>/1000.bmp` and `<video_path>/virtual.mp4` atomically (via `.tmp` rename) and caches the tuple in `lastStagedKey = pkg + "|" + facing`.
   - `HookMain` also registers a runtime `BroadcastReceiver` for `MediaMappings.ACTION_UPDATED` (`com.example.vcam.action.MAPPING_UPDATED`) so mapping changes in the manager immediately invalidate `lastStagedKey` in live host processes.

---

### 3.2 Camera1 Interception Pipeline (`android.hardware.Camera`)

1. **Lens Facing Detection**:
   - `Camera.open(int)` inspects `Camera.getCameraInfo(id, info)` to set `currentFacing` (`front` or `back`) and calls `ensureStagedNow()`.
   - `Camera.open()` resets `currentFacing = "back"` and calls `ensureStagedNow()`.
2. **Preview Surface Hijacking**:
   - `setPreviewTexture(SurfaceTexture)`: Saves the host's real `SurfaceTexture` into `mSurfacetexture` and substitutes a dummy `fake_SurfaceTexture = new SurfaceTexture(10)` so the real hardware sensor writes into an unconsumed texture.
   - `setPreviewDisplay(SurfaceHolder)`: Saves the host's `SurfaceHolder` into `ori_holder`, redirects the camera to a dummy `c1_fake_texture = new SurfaceTexture(11)`, and short-circuits `setPreviewDisplay` with `param.setResult(null)`.
   - `startPreview()`: Initializes a looping `MediaPlayer` (`mMediaPlayer` for `SurfaceTexture` or `mplayer1` for `SurfaceHolder`) pointing at `video_path + "virtual.mp4"` and renders directly onto the host's original preview surface. (In image-only mode where only `1000.bmp` is staged, `setPreviewTexture`/`setPreviewDisplay`/`startPreview` let live preview pass through or rely on frame callbacks).
3. **Preview Byte Buffer Callbacks (`onPreviewFrame`)**:
   - Hooks `setPreviewCallback`, `setPreviewCallbackWithBuffer`, `setOneShotPreviewCallback`, and `addCallbackBuffer`.
   - Hooks `onPreviewFrame(byte[] data, Camera camera)` on the callback class:
     - **Video mode**: Starts `VideoToFrames` (`hw_decode_obj`) configured for `OutputImageFormat.NV21`, which decodes `virtual.mp4` via `MediaCodec` into `HookMain.data_buffer` and `System.arraycopy`s each frame into `paramd.args[0]`.
     - **Image-only mode**: Uses `getCachedImageYuv()` to decode `1000.bmp` once (cached by file `mtime` and `size`), converts ARGB to NV21/YCbCr420 via `rgb2YCbCr420`, and copies it into `paramd.args[0]` on every callback tick.
4. **Still Picture Capture (`takePicture`)**:
   - Hooks the 4-argument `Camera.takePicture(ShutterCallback, PictureCallback raw, PictureCallback postview, PictureCallback jpeg)`:
     - If `args[1]` (raw/YUV callback) is present, hooks its `onPictureTaken`, converts `1000.bmp` to YUV via `getYUVByBitmap`, and replaces `paramd.args[0]`.
     - If `args[3]` (JPEG callback) is present, hooks its `onPictureTaken`, compresses `1000.bmp` to JPEG (quality 100), and replaces `paramd.args[0]`.

---

### 3.3 Camera2 Interception Pipeline (`android.hardware.camera2.*`)

1. **Camera Opening & Session Redirection**:
   - Hooks `CameraManager.openCamera(String, StateCallback, Handler)` and (on API 28+) `openCamera(String, Executor, StateCallback)`.
   - Detects lens facing via `CameraCharacteristics.LENS_FACING` and stages media via `ensureStagedNow()`.
   - Hooks `onOpened(CameraDevice)` on the host's `CameraDevice.StateCallback`:
     - Creates a dummy sink surface `c2_virtual_surface` backed by `SurfaceTexture(15)`.
     - Hooks all `CameraDevice` session creation methods on the concrete device class:
       - `createCaptureSession(List<Surface>, StateCallback, Handler)`
       - `createCaptureSessionByOutputConfigurations(...)` (API 24+)
       - `createConstrainedHighSpeedCaptureSession(...)`
       - `createReprocessableCaptureSession(...)` (API 23+)
       - `createReprocessableCaptureSessionByConfigurations(...)` (API 24+)
       - `createCaptureSession(SessionConfiguration)` (API 28+)
     - Replaces the list of target surfaces/configurations passed to the camera HAL with `[c2_virtual_surface]` so the real camera sensor only ever outputs to the dummy surface.
2. **Surface Capture & Classification (`CaptureRequest.Builder`)**:
   - Hooks `ImageReader.newInstance(int width, int height, int format, int maxImages)` to record the host's requested dimensions (`c2_ori_width`, `c2_ori_height`) and format (`imageReaderFormat`).
   - Hooks `CaptureRequest.Builder.addTarget(Surface)`:
     - Inspects `surface.toString()`:
       - Surfaces containing `"Surface(name=null)"` (typical of `ImageReader` surfaces) are stored in `c2_reader_Surfcae` / `c2_reader_Surfcae_1`.
       - Other surfaces (`SurfaceView` / `TextureView` preview surfaces) are stored in `c2_preview_Surfcae` / `c2_preview_Surfcae_1`.
     - Rewrites `param.args[0] = c2_virtual_surface` so the `CaptureRequest` remains valid against the hijacked `CameraCaptureSession`.
   - Hooks `CaptureRequest.Builder.removeTarget(Surface)` to clean up surface references.
3. **Feeding Captured Surfaces (`CaptureRequest.Builder.build` -> `process_camera2_play`)**:
   - **Video Mode (`virtual.mp4` present)**:
     - Preview surfaces (`c2_preview_Surfcae`, `c2_preview_Surfcae_1`) are driven by looping `MediaPlayer` instances (`c2_player`, `c2_player_1`).
     - Reader surfaces (`c2_reader_Surfcae`, `c2_reader_Surfcae_1`) are driven by `VideoToFrames` (`c2_hw_decode_obj`, `c2_hw_decode_obj_1`), which configures `MediaCodec` to render decoded video frames directly onto the `ImageReader` surface.
   - **Image-Only Mode (`1000.bmp` present, no `virtual.mp4`)**:
     - Spawns `StaticBitmapFeeder` daemon threads (~15 fps / 66ms interval) for each active preview and reader surface:
       - `FORMAT_PREVIEW`: Draws the scaled bitmap via `Surface.lockCanvas(null)` and `unlockCanvasAndPost`.
       - `ImageFormat.JPEG` (256): Uses `ImageWriter.newInstance(surface, 2)` to dequeue input images and write cached JPEG-compressed bytes into `plane[0]`.
       - `ImageFormat.YUV_420_888` (35): Uses `ImageWriter` and copies cached BT.601 I420 planes (`Y`, `U`, `V`) respecting target row and pixel strides.

---

### 3.4 In-App Configuration Notification (`InjectedConfigActivity`)

- When a hooked host application starts (`callApplicationOnCreate`), `HookMain.postInAppConfigNotification()` posts a low-importance notification (`VCAM_NOTIF_ID = 0x7CA4`) inside the host app's notification shade.
- Tapping the notification fires a `PendingIntent` targeting `com.example.vcam/.InjectedConfigActivity` with extras `caller_package` and `current_facing`.
- `InjectedConfigActivity` uses `@style/Theme.VCAM.Translucent` (`singleInstance`, `excludeFromRecents="true"`, `noHistory="true"`) so it floats over the target app like a dialog. Users can import an image/video and assign it to a scope (`this_back`, `this_front`, `this_both`, `global_back`, `global_front`, `global_both`) without leaving the host app.

---

### 3.5 Companion Manager UI & Verification

- **`MainActivity`**:
  - Status card with chips for module status (`MediaPaths.isXposedLikelyActive()`), image/video loaded state, resolution, and file size.
  - Source media card to import directly into `/DCIM/Camera1/1000.bmp` and `virtual.mp4`, clear staged media, or navigate to **Media Library**, **Per-App Mapping**, **Global Defaults**, or **Verify with Camera**.
  - Live demo card (`TextureView` + `MediaPlayer` / 15fps `Canvas` loop + `Validate` report dialog) to verify media decodability before opening a target app.
  - Marker switches bound to `/DCIM/Camera1/*.jpg` flag files and custom path override inputs.
- **`MediaLibrary` & `MediaLibraryActivity`**:
  - Stores imported files in `<filesDir>/library/{image|video}_{id}` and metadata in `SharedPreferences("vcam_prefs")` under key `"media_library"`.
  - Uses an `LruCache` and background thread (`VCAM-LibraryThumbs`) to render thumbnails and resolutions without UI jank.
- **`PerAppMappingActivity` & `AppMappingEditActivity`**:
  - Queries `PackageManager` (using `QUERY_ALL_PACKAGES`) for installed apps that request or hold `android.permission.CAMERA`.
  - Lets users configure unified (`facing="any"`) or split (`facing="back"` / `facing="front"`) image and video overrides per package, persisted in `SharedPreferences("vcam_prefs")` under key `"media_mappings"`.
- **`CameraVerifyActivity`**:
  - Opens Camera2 inside `com.example.vcam` itself (since LSPosed automatically scopes a module to its own manager package).
  - Displays the live preview, computes the SHA-256 of the staged `1000.bmp`, and allows taking a still capture via `ImageReader` to confirm end-to-end hook interception.

---

## 4. Build, Test & Release Workflows

###Building Locally
Requires **JDK 17** and **Android SDK Platform 34** (Gradle 8.0 / Android Gradle Plugin 8.0.2):

```bash
# Assemble debug APK
./gradlew :app:assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk

# Assemble release APK (signed with debug keystore for sideloading)
./gradlew :app:assembleRelease
# Output: app/build/outputs/apk/release/app-release.apk

# Run unit tests
./gradlew test
```

### Version Bumping & Releases
Use `./bumpversion.sh` to increment `versionCode`, update `versionName` in `app/build.gradle`, commit the change, and create an annotated git tag `vX.Y`:

```bash
./bumpversion.sh --dry-run 5.3
./bumpversion.sh 5.3
git push origin HEAD && git push origin v5.3
```

Pushing a `v*` tag triggers `.github/workflows/build.yml` to build both debug and release APKs, generate `.sha256` checksums, and publish a GitHub Release.

---

## 5. Key Invariants & Guidelines for Future Agents

1. **Preserve the Legacy Filesystem Contract**:
   Never break `/DCIM/Camera1/virtual.mp4`, `/DCIM/Camera1/1000.bmp`, or the marker files (`disable.jpg`, `no-silent.jpg`, `private_dir.jpg`, `force_show.jpg`, `no_toast.jpg`). Both manual filesystem users and the `MediaProvider` staging pipeline depend on `HookMain` reading `1000.bmp` and `virtual.mp4` from `HookMain.video_path`.
2. **Thread Safety & Host Startup Latency in `HookMain`**:
   `HookMain` runs inside third-party processes. Avoid heavy blocking operations on the main thread during `callApplicationOnCreate`. Keep `ensureStagedNow` / `maybeRestage` deduplicated via `lastStagedKey` so file copying only happens once per `(pkg, facing)` change.
3. **Exclude Manager from Storage Permission Spoofing**:
   `applyInjectedStoragePermissions` must always early-return on `BuildConfig.APPLICATION_ID` (`com.example.vcam`) so `MainActivity` accurately detects whether the user has granted `MANAGE_EXTERNAL_STORAGE` ("All files access").
4. **Keep `MediaProvider` Access Gated**:
   `MediaProvider` must remain `android:exported="true"` for cross-process hook access, and must always enforce `enforceCallerIsCameraApp()` in both `openFile()` and `query()`.
5. **Localization Parity**:
   When adding new UI strings to `app/src/main/res/values/strings.xml`, add corresponding translations across the localized string tables (`values-zh/`, `values-zh-rCN/`, `values-zh-rHK/`, `values-zh-rMO/`, `values-zh-rSG/`, `values-zh-rTW/`).


---

## 6. R&D Forensic Audit & Master 3-Pass Build Plan

### 6.1 Deep Audit Findings: Why Apps Bypass VCAM & Where Aspect Ratio Fails

1. **Aspect Ratio & Orientation Failures Across 5 Layers**:
   - `MainActivity` (`demoTexture` & `startImageLoop`), `CameraVerifyActivity` (`verify_preview`), and `InjectedConfigActivity` stretch frames to fill fixed containers without applying `TextureView.setTransform(Matrix)` or letterboxed `RectF` math.
   - `MediaPaths.videoResolution()` ignores `METADATA_KEY_VIDEO_ROTATION`, reporting portrait phone videos (`1920x1080` rotated `90°`) as landscape.
   - `StaticBitmapFeeder` uses `Bitmap.createScaledBitmap(bmp, width, height, true)` without aspect preservation.
   - `HookMain` does not compensate for host camera `SENSOR_ORIENTATION` (`90°` back, `270°` front) or front-camera mirroring.
   - Camera1 `onPreviewFrame` and `takePicture` do not scale/letterbox `1000.bmp` or `virtual.mp4` frames to the host's requested `(mwidth, mhight)`, scrambling NV21 UV planes when resolutions differ.
2. **10 "No-Escape" Hook Bypass Vectors Identified**:
   - **Vector 1**: `CameraManager.openCamera(String, Executor, StateCallback)` uses `afterHookedMethod` (`HookMain.java:388`), racing `onOpened` in CameraX/WebRTC/MLKit.
   - **Vector 2**: Camera1 Image-Only mode skips `setPreviewTexture`, `setPreviewDisplay`, and `startPreview` (`HookMain.java:301, 632, 743`)—so apps that capture photos by grabbing OpenGL `SurfaceTexture` frames (WhatsApp, Instagram, WeChat) capture the real physical camera!
   - **Vector 3**: `CaptureRequest.Builder.build()` early-returns if `param.thisObject.equals(c2_builder)` (`HookMain.java:878`), ignoring new surfaces added to an existing `Builder`, OR tearing down and restarting video playback from `0:00` whenever a second `Builder` is built for AF/AE or `TEMPLATE_STILL_CAPTURE`.
   - **Vector 4**: Hardcoded 2-preview + 2-reader surface slots (`c2_preview_Surfcae`, `_1`, `c2_reader_Surfcae`, `_1`) and brittle `surface.toString().contains("Surface(name=null)")` classification fail on 3+ surface CameraX pipelines and `ImageReader.Builder` (API 33+).
   - **Vector 5**: `createReprocessableCaptureSessionByConfigurations` overwrites `param.args[0]` (`InputConfiguration`) instead of `param.args[1]` (`List<OutputConfiguration>`), causing `ClassCastException`.
   - **Vector 6**: Deferred `OutputConfiguration` + `CameraCaptureSession.finalizeOutputConfigurations` and Logical Multi-Camera `OutputConfiguration.setPhysicalCameraId` + 2-arg `createCaptureRequest(int, Set)` throw `IllegalArgumentException` when session outputs are replaced with a single unconfigured virtual surface.
   - **Vector 7**: `Camera.addCallbackBuffer` allocates `new byte[...]` on every frame (`HookMain.java:439`), breaking host buffer-pool identity checks and causing 90 MB/s GC churn.
   - **Vector 8**: `HookMain.rgb2YCbCr420` swaps Red and Blue channels (`r = rgb & 0xFF`, `b = (rgb >> 16) & 0xFF`).
   - **Vector 9**: `ImageWriter` can be rejected on custom OEM ROMs or `HardwareBuffer`-backed `ImageReader`s; hooking `ImageReader.acquireLatestImage()` / `acquireNextImage()` provides a CPU-level failsafe.
   - **Vector 10**: Apps delegating capture via `MediaStore.ACTION_IMAGE_CAPTURE` / `ACTION_VIDEO_CAPTURE` execute inside the external system camera process unless intercepted via `Instrumentation.execStartActivity`.
3. **Camera Hardware Fingerprinting & Photometric Consistency**:
   - Physical sensors have static bounds (`CameraCharacteristics` focal length, aperture, ISO range, exposure range, active array size, FPS range) and dynamic per-frame `TotalCaptureResult` metadata (`SENSOR_EXPOSURE_TIME`, `SENSOR_SENSITIVITY`, `COLOR_CORRECTION_GAINS`, `LENS_FOCUS_DISTANCE`, `SENSOR_TIMESTAMP`).
   - When the physical sensor is covered/dark while injecting a bright video, `TotalCaptureResult` contradicts the pixel luminance, and `Bitmap.compress(JPEG)` strips all EXIF tags.
   - Static images produce bit-identical consecutive frames (`Frame[t] XOR Frame[t-1] == 0`) without temporal photon shot noise.

---

### 6.2 Consolidated 3-Pass Master Build Plan

#### PASS 1: Universal "No-Escape" Hook Engine, First-Class Aspect Ratio Pipeline & Hardware Sensor Profiler
- Fix all 10 hook bypass vectors, deduplicate `findAndHookMethod` class registrations, fix lifecycle cleanup (`stopPreview`, `release`, `close`), and fix stale file deletion on mapping clear (`UNSET` vs `ERROR`).
- Unify configuration (`TransformConfig`, `disable`, `mute`, `loop`, `packageFilter`, `virtualMic`, `exifSynth`, `metadataPatch`, `sensorGrain`) and host telemetry via `MediaProvider`.
- Implement `FrameTransformPipeline` supporting `FIT`/`FILL`/`STRETCH` aspect modes, `AUTO`/`0°`/`90°`/`180°`/`270°` rotation with `SENSOR_ORIENTATION` compensation, `zoom`, `panX`/`panY`, `mirrorH`/`mirrorV`, white balance Kelvin shift, exposure offset, and temporal sensor grain.
- Implement `SensorProfiler`, `LiveFramePhotometryAnalyzer`, `CaptureResultPatcher`, and `ExifSynthesizer` so frame rate pacing, `TotalCaptureResult` metadata, and JPEG EXIF headers match the physical phone camera and injected frame photometry.
- Fix aspect ratio rendering across `MainActivity`, `CameraVerifyActivity`, and `InjectedConfigActivity`.

#### PASS 2: In-App Floating Overlay HUD (PTZ Joystick, Pinch-to-Zoom, White Balance/Grain, Cue Points, Presets & Simul-Cam)
- Build `InAppOverlayManager` & `OverlayControlPanelView` in 100% programmatic Android SDK `View`/`Canvas` code (immune to host ClassLoader/Resource isolation), attached via `Application.registerActivityLifecycleCallbacks` (plus optional `SYSTEM_ALERT_WINDOW` mode).
- Include draggable status/PIP bubble + expandable 3-tab HUD:
  1. **PTZ & Aspect**: 2D Virtual Joystick + D-Pad, Pinch-to-Zoom + Zoom slider, `FIT`/`FILL`/`STRETCH`, `AUTO`/`0°`/`90°`/`180°`/`270°`, `Flip H/V`, and Named Presets.
  2. **Playback & Cue Points**: Play/Pause freeze-frame, live video scrub bar, and 4 Cue Point trigger pads (`Cue A–D` + `+ Set Cue`).
  3. **Sensor Match & Source Hot-Swap**: White balance & brightness sliders, sensor grain toggle, Simul-Cam (`Virtual` / `Real Camera Pass-Through` / `PIP Blend`), and quick library switcher.
- Add interactive transform & Cue Point editors to `MainActivity` and `MediaLibraryActivity`.

#### PASS 3: Blue-Sky Power Utilities (Zero-Copy FD Streaming, Synthetic Test Pattern, Live QR Generator, Network Streams, On-Device Normalizer & Virtual Mic)
- Implement zero-copy `ParcelFileDescriptor` media streaming from `MediaProvider`.
- Implement `SyntheticFrameGenerator` (Diagnostic Test Pattern with true-circle aspect gauge, FPS/timestamp clock, and live host telemetry overlay) and `QrCodeGenerator` (live QR projection from Manager or Floating Overlay).
- Implement `StreamMediaSource` for HTTP/HTTPS/HLS/RTSP/MJPEG live stream URLs.
- Surface per-app host camera telemetry badges in `PerAppMappingActivity` & `AppMappingEditActivity` and add an on-device `MediaTranscoder` normalizer in `MediaLibraryActivity`.
- Implement `VirtualAudioFeeder` (`AudioRecord` hook synchronizing decoded video audio track to host PCM parameters).
- Complete i18n translations across all 7 locale directories, add unit tests, verify `./gradlew test :app:assembleDebug :app:assembleRelease`, and update documentation.

---

## 7. v5.2.x – v5.3.0 Native NDK (`libvcam_ndk.so`), IAD Counter-Telemetry & SystemUI Immunity

### 7.1 Native NDK Interception (`app/src/main/cpp/vcam_ndk.cpp` & `NdkHookEngine.java`)
- **Cross-Compiled ABIs**: `arm64-v8a`, `armeabi-v7a`, and `x86_64` built via `app/src/main/cpp/build_ndk.sh` into `app/src/main/jniLibs/<abi>/libvcam_ndk.so`.
- **Dual-Layer Hook Strategy**:
  1. **ARM64 Callee Prologue Inline Trampoline (`install_arm64_inline_hook`)**: Rewrites the target symbol's entry prologue in `libcamera2ndk.so` / `libmediandk.so` with a 16-byte absolute branch (`LDR X16, #8; BR X16`) while preserving displaced instructions in an executable trampoline island.
  2. **Self-Healing Shadow GOT + `dlsym` Interception**: Scans ELF `.rela.plt` / `.rela.dyn` across loaded non-system modules, records original function pointers in `g_shadow_got`, redirects camera/media symbols, and intercepts `dlsym()`.
- **Hooked Native Symbols**:
  - `ACameraManager_openCamera`, `ACameraManager_getCameraIdList`, `ACameraManager_getCameraCharacteristics`
  - `ACameraMetadata_getConstEntry` (spoofs `ACAMERA_LENS_FACING`, `ACAMERA_SENSOR_ORIENTATION`, `ACAMERA_SENSOR_INFO_ACTIVE_ARRAY_SIZE`, `ACAMERA_SENSOR_INFO_PHYSICAL_SIZE`, `ACAMERA_SENSOR_INFO_SENSITIVITY_RANGE`, `ACAMERA_SENSOR_INFO_EXPOSURE_TIME_RANGE`, `ACAMERA_SENSOR_INFO_TIMESTAMP_SOURCE = REALTIME`, and `ACAMERA_INFO_SUPPORTED_HARDWARE_LEVEL = 3`)
  - `ACameraDevice_createCaptureSession`, `ACameraDevice_createCaptureSessionWithSessionParameters`, `ACaptureSessionOutput_create`, `ACaptureSessionOutputContainer_add`, `ACameraOutputTarget_create`, `ACameraCaptureSession_setRepeatingRequest`
  - `AImageReader_new`, `AImageReader_newWithUsage`, `AImageReader_getWindow`, `AImageReader_setImageListener`, `AImageReader_acquireLatestImage`, `AImageReader_acquireNextImage`
  - `AImage_getTimestamp` (synthesizes `CLOCK_BOOTTIME` start-of-exposure timestamps), `AImage_getHardwareBuffer`, `AHardwareBuffer_describe` (injects `USAGE_CAMERA_WRITE` and 64-byte ISP stride), `AImage_getNumberOfPlanes`, `AImage_getPlaneRowStride`, `AImage_getPlanePixelStride`, `AImage_getPlaneData` (applies IMU sub-pixel motion shift + Xorshift32 temporal shot noise).
- **Self-Hiding & `/proc` Scrubber**:
  - `NdkHookEngine` extracts `libvcam_ndk.so` under a randomized HAL name (`libcam_hal3_ext_<hex>.so`), marks it `0400` read-only (satisfying Android 14+ Dynamic Code Loading policy), loads it, and immediately `unlink`s the file on disk.
  - Native `fopen` / `fopen64` / `open` / `open64` / `openat` hooks redirect `/proc/self/maps`, `/proc/self/smaps`, and `/proc/self/status` through an anonymous `memfd_create` descriptor with all `vcam`, `xposed`, `lsposed`, `riru`, `zygisk`, and `frida` lines scrubbed and `TracerPid:	0` enforced.

### 7.2 Direct-Boot & SystemUI Immunity
- Both `HookMain.handleLoadPackage()` (`isExcludedSystemOrKeyguardPackage`) and `vcam_ndk.cpp` (`is_excluded_system_process`) strictly exclude `android`, `system_server`, `com.android.systemui`, `com.android.keyguard`, `com.android.phone`, `com.android.settings`, `com.android.providers.*`, `zygote`, `surfaceflinger`, and `cameraserver`, and skip hooking whenever `UserManager.isUserUnlocked()` is `false` (Direct Boot before first lockscreen unlock).

### 7.3 Zero-Permission Media Resolution (`MediaPaths.resolveEffectiveImageFile` / `resolveEffectiveVideoFile`)
- `MainActivity`, `CameraVerifyActivity`, and `InjectedConfigActivity` resolve staged media via `MediaPaths.resolveEffectiveImageFile(ctx)` and `MediaPaths.resolveEffectiveVideoFile(ctx)`, which check `MediaMappings` + `MediaLibrary` first and fall back to `/sdcard/DCIM/Camera1/1000.bmp` and `virtual.mp4`. This guarantees full preview, validation, and verification functionality on Android 11–14 without requiring `MANAGE_EXTERNAL_STORAGE`.
