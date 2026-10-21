# android_virtual_cam (VCAM)

[English](./README.md) | [简体中文](./README_zh.md) | [繁體中文](./README_tc.md)

> *"The essence of the independent mind lies not in what it thinks, but in how it thinks."* — Christopher Hitchens

A virtual camera & sensor emulation studio for Android, implemented as an Xposed/LSPosed module (`com.example.vcam`). It intercepts the Camera1, Camera2, CameraX, WebRTC, and external capture intent pipelines of target applications and feeds them a video, still image, network stream, live QR code, or diagnostic test pattern with first-class aspect ratio preservation, real-time PTZ (Pan/Tilt/Zoom) HUD controls, and hardware-matched EXIF/CaptureResult metadata synthesis.

---

## 0. A word before the manual

Software like this attracts two kinds of user. The first is a tinkerer: the developer testing a camera-facing flow without a camera, the QA engineer reproducing a bug on a buildserver, the researcher examining a closed-source app's frame-handling code. That is honest work.

The second kind wishes to deceive another human being — a friend, a counterparty, an institution, a court. The documentation is going to be of no comfort to you. Bluntly: **if your objective requires lying to someone who has not consented to be lied to, this module is not a tool, it is an accomplice, and the law in most jurisdictions has a view on accomplices.** You assume full civil and criminal responsibility for what you do with it. The authors do not.

---

## 1. Architecture & Capabilities

VCAM combines a Material 3 Manager & Studio app with an Xposed/LSPosed hook engine (`HookMain`) bridged over an exported, zero-storage-permission `ContentProvider` (`content://com.example.vcam`):

### 1.1 Universal "No-Escape" Hook Engine (`HookMain.java`)
- **Camera2 Complete Coverage**:
  - Hooks all `CameraManager.openCamera` and `openCameraForUid` overloads in `beforeHookedMethod` so fast native camera callbacks never escape.
  - Dynamically tracks unlimited output surfaces (`SurfaceView`, `TextureView`, `ImageReader` YUV/JPEG/PRIVATE, `MediaCodec`, `MediaRecorder`) via `ACTIVE_FEEDERS` without restarting decoders when `CaptureRequest.Builder.build()` is called repeatedly.
  - Hooks `ImageReader.newInstance`, `ImageReader.Builder.build` (API 33+), and `ImageReader.getSurface()` to capture exact per-surface `(width, height, format)` dimensions.
  - Includes a CPU plane-patching fallback on `ImageReader.acquireLatestImage()` / `acquireNextImage()` when `ImageWriter` cannot attach to hardware buffers.
  - Hooks `OutputConfiguration.addSurface`, `OutputConfiguration.setPhysicalCameraId`, `CameraCaptureSession.finalizeOutputConfigurations` (deferred surfaces), and 2-arg `createCaptureRequest(int, Set)` (physical sub-camera requests).
- **Camera1 Complete Coverage**:
  - Feeds `SurfaceTexture` and `SurfaceHolder` previews in both video and still-image/synthetic modes (even when `setPreviewTexture` is called *after* `startPreview()`).
  - Hooks both 3-arg and 4-arg `Camera.takePicture(...)` overloads.
  - Preserves host `byte[]` buffer reference identity in `addCallbackBuffer(byte[])` for zero-copy WebRTC / ZXing / MLKit buffer pools.
- **External Camera Intent Interception**:
  - Hooks `Instrumentation.execStartActivity` for `MediaStore.ACTION_IMAGE_CAPTURE`, writing the transformed + EXIF-synthesized JPEG directly into the caller's `EXTRA_OUTPUT` `Uri`.

### 1.2 First-Class Aspect Ratio & Spatial Transform Pipeline (`FrameTransformPipeline.java`, `TransformConfig.java`)
- **Aspect Ratio Modes**: `FIT` (letterbox/pillarbox preserving source proportions), `FILL` (center-crop filling target viewport without distortion), and `STRETCH`.
- **Orientation & PTZ**: `AUTO` (honoring JPEG EXIF & MP4 container `METADATA_KEY_VIDEO_ROTATION` + host `SENSOR_ORIENTATION`), `0°`, `90°`, `180°`, `270°`, `mirrorH`, `mirrorV`, `zoom` (`0.5x–8.0x`), and normalized `panX`/`panY`.
- **Fixed BT.601 Color Pipeline**: Accurate `ARGB_8888` $\rightarrow$ `NV21` / `I420` conversion (fixing the legacy Red/Blue channel inversion bug) and aspect-aware NV21 nearest-neighbor rescaling (`VideoToFrames.scaleNv21Nearest`).

### 1.3 In-App Floating Overlay HUD (`InAppOverlayManager.java`)
- **Zero-Permission Window Attachment**: Attaches a draggable reticle bubble and 3-tab control panel (`PTZ & Aspect`, `Play & Cues`, `Sensor & Src`) to the foreground `Activity` window using `WindowManager.LayoutParams.TYPE_APPLICATION_PANEL`.
- **Interactive Controls**:
  - 2D Virtual Joystick + D-Pad step buttons + Pinch-to-Zoom & Zoom slider.
  - Aspect mode (`Fit`/`Fill`/`Stretch`), Rotation (`Auto`/`+90°`), and Mirror (`Flip H`/`Flip V`) toggles.
  - Play/Pause freeze-frame, timeline scrub bar, and 4 programmable **Cue Point** pads (`Cue A–D`) with segment loop support.
  - White Balance Kelvin (`-100..+100`), Exposure EV (`-2..+2`), Temporal Grain toggle, Source switcher (`Media`/`Pattern`/`QR`), and **Real Camera Pass-Through** toggle.

### 1.4 Camera Hardware Fingerprinting & Photometric Alignment (`SensorProfiler.java`, `ExifSynthesizer.java`)
- **Device Sensor Profiler**: Reads physical `CameraCharacteristics` (`SENSOR_INFO_ACTIVE_ARRAY_SIZE`, `SENSOR_INFO_PHYSICAL_SIZE`, `LENS_INFO_AVAILABLE_FOCAL_LENGTHS`, `LENS_INFO_AVAILABLE_APERTURES`, `SENSOR_INFO_SENSITIVITY_RANGE`, `SENSOR_INFO_EXPOSURE_TIME_RANGE`, `SENSOR_ORIENTATION`) for both Back and Front lenses.
- **Photometric Scene Coupling**: Computes mean luminance and RGB ratios on an 8×8 grid (`CURRENT_PHOTOMETRY`) to derive realistic `SENSOR_SENSITIVITY` (ISO), `SENSOR_EXPOSURE_TIME` (ns), and `COLOR_CORRECTION_GAINS` (`RggbChannelVector`).
- **EXIF APP1 & CaptureResult Synthesis**: Injects a spec-compliant Little-Endian TIFF/EXIF APP1 segment into captured JPEGs and patches `CaptureResult.get(Key)` inside Camera2 callbacks.
- **Temporal Sensor Grain (`GRAIN_LUT`)**: Applies a fast pre-computed 4096-byte Gaussian noise LUT to static images and paused video frames so consecutive frames are never bit-identical.

### 1.5 Blue-Sky Power Tools
- **Synthetic Frame Generators (`SyntheticFrameGenerator.java`)**:
  - **Diagnostic Test Pattern**: Renders SMPTE color bars, a true-circle aspect ratio calibration gauge, an orbiting 30fps sync dot, and a live telemetry HUD.
  - **Live ISO/IEC 18004 QR Code Generator**: Self-contained Reed-Solomon GF(256) QR encoder (Versions 1–6, Mode Byte) for instant scanner testing.
- **Virtual Microphone (`VirtualAudioFeeder.java`)**: Hooks `AudioRecord` to decode and resample AAC audio from the active video into the host app's microphone stream.
- **On-Device Media Inspector & Normalizer (`MediaTranscoder.java`)**: Inspects codec/FPS/rotation metadata and normalizes MP4 orientation hints or bakes transforms into library images.

---

## 2. Building & Installing

```bash
git clone https://github.com/Steake/com.example.vcam.git
cd com.example.vcam
./gradlew :app:assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Requirements: JDK 17, Android SDK platform 34, AGP 8.0.2, Gradle 8.0.

In LSPosed: **Modules → VCAM → enable → select target apps** (do not select `android` / System Framework) and force-stop the target app.

---

## 3. Credits

- Original hook concept: [wangwei1237/CameraHook](https://github.com/wangwei1237/CameraHook)
- H.264 hardware decode base: [zhantong/Android-VideoToImages](https://github.com/zhantong/Android-VideoToImages)
