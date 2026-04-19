package com.example.vcam;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.graphics.ImageFormat;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.MediaStore;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;

import java.io.File;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * In-manager camera verifier (Bug 5 fix). Opens Camera2 inside this process
 * so the VCAM hook — which always includes its own manager package in
 * scope — applies and the user can visually confirm preview replacement
 * and still-capture injection without relying on an external camera app.
 *
 * <p>Keep logic deliberately small: we only need to prove the hook is
 * intercepting Camera2 calls in this process. The preview surface ID is
 * logged by {@link HookMain} under {@code [VCAM]} already; when the hook
 * is active, this activity's preview will be fed video frames from
 * {@code virtual.mp4} (or the static {@code 1000.bmp} fallback) rather
 * than the real camera sensor.
 */
public class CameraVerifyActivity extends AppCompatActivity {

    private static final String TAG = "VCAM-Verify";

    private TextureView previewView;
    private Chip hookChip;
    private TextView hashView;
    private ImageView lastCaptureView;

    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private ImageReader captureReader;
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private String cameraId;

    private ActivityResultLauncher<String> cameraPermLauncher;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera_verify);

        MaterialToolbar toolbar = findViewById(R.id.verify_toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        previewView = findViewById(R.id.verify_preview);
        hookChip = findViewById(R.id.verify_chip_hook);
        hashView = findViewById(R.id.verify_hash);
        lastCaptureView = findViewById(R.id.verify_last_capture);

        MaterialButton btnCapture = findViewById(R.id.verify_btn_capture);
        MaterialButton btnSystem = findViewById(R.id.verify_btn_system);

        btnCapture.setOnClickListener(v -> takeStillCapture());
        btnSystem.setOnClickListener(v -> launchSystemCameraWithScopeWarning());

        cameraPermLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> {
                    if (granted) startCameraWhenReady();
                    else {
                        Toast.makeText(this, R.string.verify_permission_denied,
                                Toast.LENGTH_LONG).show();
                        finish();
                    }
                });

        updateHookChip();
        updateStagedImageHash();

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            cameraPermLauncher.launch(Manifest.permission.CAMERA);
        } else {
            startCameraWhenReady();
        }
    }

    private void updateHookChip() {
        // Best-effort: the hook registers [VCAM] handlers in all scoped
        // processes, and since LSPosed always scopes the module to itself,
        // presence of the XposedBridge class in this process implies the
        // hook is loaded and will intercept the Camera2 calls below.
        boolean active = MediaPaths.isXposedLikelyActive();
        hookChip.setText(active
                ? R.string.verify_hook_active
                : R.string.verify_hook_inactive);
    }

    private void updateStagedImageHash() {
        File img = MediaPaths.getImageTarget(this);
        if (!img.exists() || img.length() == 0) {
            hashView.setText(R.string.verify_no_staged_image);
            return;
        }
        try (FileInputStream in = new FileInputStream(img)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b));
            hashView.setText(getString(R.string.verify_hash_format, sb.toString()));
        } catch (Exception e) {
            Log.w(TAG, "hash failed", e);
            hashView.setText("");
        }
    }

    // ------------------------------------------------------------------
    // Camera2 plumbing (intentionally small)
    // ------------------------------------------------------------------

    private void startCameraWhenReady() {
        cameraThread = new HandlerThread("vcam-verify-camera");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());

        if (previewView.isAvailable()) {
            openCamera();
        } else {
            previewView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override public void onSurfaceTextureAvailable(@NonNull SurfaceTexture s, int w, int h) { openCamera(); }
                @Override public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture s, int w, int h) {}
                @Override public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture s) { return true; }
                @Override public void onSurfaceTextureUpdated(@NonNull SurfaceTexture s) {}
            });
        }
    }

    @SuppressWarnings("MissingPermission")
    private void openCamera() {
        CameraManager mgr = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        if (mgr == null) return;
        try {
            String[] ids = mgr.getCameraIdList();
            if (ids.length == 0) {
                Toast.makeText(this, R.string.test_camera_failed, Toast.LENGTH_SHORT).show();
                return;
            }
            cameraId = ids[0];
            Size jpegSize = pickJpegSize(mgr, cameraId);
            captureReader = ImageReader.newInstance(
                    jpegSize.getWidth(), jpegSize.getHeight(),
                    ImageFormat.JPEG, 2);
            captureReader.setOnImageAvailableListener(this::onStillAvailable, cameraHandler);
            mgr.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override public void onOpened(@NonNull CameraDevice camera) {
                    cameraDevice = camera;
                    createPreviewSession();
                }
                @Override public void onDisconnected(@NonNull CameraDevice camera) { camera.close(); }
                @Override public void onError(@NonNull CameraDevice camera, int error) {
                    Log.w(TAG, "camera error " + error);
                    camera.close();
                }
            }, cameraHandler);
        } catch (CameraAccessException | SecurityException e) {
            Log.w(TAG, "openCamera failed", e);
        }
    }

    /**
     * Choose a JPEG output size the camera actually supports. Hardcoding
     * 640x480 trips devices whose camera doesn't list that exact size in
     * its {@code SCALER_STREAM_CONFIGURATION_MAP}. We pick the smallest
     * supported size at least ~720p or the largest smaller one, so the
     * still capture round-trips cleanly without bloating the capture
     * buffer.
     */
    @NonNull
    private Size pickJpegSize(@NonNull CameraManager mgr, @NonNull String id)
            throws CameraAccessException {
        CameraCharacteristics ch = mgr.getCameraCharacteristics(id);
        StreamConfigurationMap map = ch.get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Size fallback = new Size(640, 480);
        if (map == null) return fallback;
        Size[] sizes = map.getOutputSizes(ImageFormat.JPEG);
        if (sizes == null || sizes.length == 0) return fallback;
        Size best = null;
        int targetArea = 1280 * 720;
        int bestDelta = Integer.MAX_VALUE;
        for (Size s : sizes) {
            int delta = Math.abs(s.getWidth() * s.getHeight() - targetArea);
            if (delta < bestDelta) {
                bestDelta = delta;
                best = s;
            }
        }
        return best != null ? best : sizes[0];
    }

    private void createPreviewSession() {
        try {
            SurfaceTexture tex = previewView.getSurfaceTexture();
            if (tex == null) return;
            tex.setDefaultBufferSize(1280, 720);
            Surface previewSurface = new Surface(tex);

            CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(
                    CameraDevice.TEMPLATE_PREVIEW);
            builder.addTarget(previewSurface);

            cameraDevice.createCaptureSession(
                    Arrays.asList(previewSurface, captureReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override public void onConfigured(@NonNull CameraCaptureSession session) {
                            captureSession = session;
                            try {
                                session.setRepeatingRequest(builder.build(), null, cameraHandler);
                            } catch (CameraAccessException e) {
                                Log.w(TAG, "setRepeatingRequest failed", e);
                            }
                        }
                        @Override public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            Log.w(TAG, "preview configure failed");
                        }
                    }, cameraHandler);
        } catch (CameraAccessException e) {
            Log.w(TAG, "createPreviewSession failed", e);
        }
    }

    private void takeStillCapture() {
        if (cameraDevice == null || captureSession == null || captureReader == null) {
            return;
        }
        try {
            CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(
                    CameraDevice.TEMPLATE_STILL_CAPTURE);
            builder.addTarget(captureReader.getSurface());
            captureSession.capture(builder.build(), null, cameraHandler);
        } catch (CameraAccessException e) {
            Log.w(TAG, "capture failed", e);
        }
    }

    private void onStillAvailable(ImageReader reader) {
        Image image = null;
        try {
            image = reader.acquireLatestImage();
            if (image == null) return;
            ByteBuffer buf = image.getPlanes()[0].getBuffer();
            byte[] bytes = new byte[buf.remaining()];
            buf.get(bytes);
            Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (bmp != null) {
                runOnUiThread(() -> {
                    lastCaptureView.setVisibility(View.VISIBLE);
                    lastCaptureView.setImageBitmap(bmp);
                });
            }
        } catch (Throwable t) {
            Log.w(TAG, "onStillAvailable", t);
        } finally {
            if (image != null) image.close();
        }
    }

    // ------------------------------------------------------------------
    // System camera fallback with scope warning
    // ------------------------------------------------------------------

    private void launchSystemCameraWithScopeWarning() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.verify_scope_warning_title)
                .setMessage(R.string.verify_scope_warning_body)
                .setPositiveButton(R.string.positive, (d, w) -> {
                    Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                    if (i.resolveActivity(getPackageManager()) != null) {
                        startActivity(i);
                    } else {
                        Toast.makeText(this, R.string.test_camera_failed,
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(R.string.negative, null)
                .show();
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    @Override
    protected void onPause() {
        super.onPause();
        closeCamera();
    }

    private void closeCamera() {
        try {
            if (captureSession != null) {
                captureSession.close();
                captureSession = null;
            }
            if (cameraDevice != null) {
                cameraDevice.close();
                cameraDevice = null;
            }
            if (captureReader != null) {
                captureReader.close();
                captureReader = null;
            }
            if (cameraThread != null) {
                cameraThread.quitSafely();
                cameraThread = null;
            }
        } catch (Throwable t) {
            Log.w(TAG, "closeCamera", t);
        }
    }
}
