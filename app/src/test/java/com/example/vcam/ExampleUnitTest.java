package com.example.vcam;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.Arrays;

import static org.junit.Assert.*;

/**
 * Unit & IAD Validation Harness tests for VCAM:
 * - BT.601 ARGB -> NV21 / I420 color accuracy (verifying R/B channel fix)
 * - Temporal sensor grain LUT injection (verifying anti-duplicate frame hashes)
 * - Little-Endian TIFF/EXIF APP1 segment synthesis
 * - Package filter whitelist evaluation
 * - IMU Accelerometer/Gyroscope to Optical-Flow motion correlation (ARGOS / Persona check)
 * - NDK ELF shared library presence & 600-transaction Persona/Sumsub/Bixelab IAD harness
 */
public class ExampleUnitTest {

    @Test
    public void argbToNv21_preservesRedAndBlueCorrectly() {
        int[] redPixels = new int[]{
                0xFFFF0000, 0xFFFF0000,
                0xFFFF0000, 0xFFFF0000
        };
        byte[] nv21Red = FrameTransformPipeline.argbToNv21(redPixels, 2, 2);
        assertEquals(6, nv21Red.length);
        int yRed = nv21Red[0] & 0xFF;
        int vRed = nv21Red[4] & 0xFF;
        int uRed = nv21Red[5] & 0xFF;
        assertTrue("Red Y should be around 81..82", yRed >= 75 && yRed <= 90);
        assertTrue("Red Cr (V) must be high (>200)", vRed > 200);
        assertTrue("Red Cb (U) must be low (<100)", uRed < 100);

        int[] bluePixels = new int[]{
                0xFF0000FF, 0xFF0000FF,
                0xFF0000FF, 0xFF0000FF
        };
        byte[] nv21Blue = FrameTransformPipeline.argbToNv21(bluePixels, 2, 2);
        int vBlue = nv21Blue[4] & 0xFF;
        int uBlue = nv21Blue[5] & 0xFF;
        assertTrue("Blue Cr (V) must be low (<120)", vBlue < 120);
        assertTrue("Blue Cb (U) must be high (>200)", uBlue > 200);
    }

    @Test
    public void temporalLumaGrain_producesDistinctFrameHashes() {
        byte[] frameA = new byte[64 * 64 * 3 / 2];
        Arrays.fill(frameA, (byte) 128);
        byte[] frameB = frameA.clone();

        FrameTransformPipeline.applyLumaGrainInPlace(frameA, 64, 64);
        FrameTransformPipeline.applyLumaGrainInPlace(frameB, 64, 64);

        assertFalse("Consecutive frames with temporal grain must not be bit-identical",
                Arrays.equals(frameA, frameB));
    }

    @Test
    public void exifSynthesizer_injectsValidApp1Segment() {
        byte[] minimalJpeg = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xD9};
        SensorProfiler.LensProfile profile = SensorProfiler.LensProfile.defaultForFacing("back");
        ExifSynthesizer.FramePhotometry phot = new ExifSynthesizer.FramePhotometry();

        byte[] out = ExifSynthesizer.injectExifIntoJpeg(minimalJpeg, 1920, 1080, profile, phot);
        assertNotNull(out);
        assertTrue(out.length > minimalJpeg.length);
        assertEquals((byte) 0xFF, out[0]);
        assertEquals((byte) 0xD8, out[1]);
        assertEquals((byte) 0xFF, out[2]);
        assertEquals((byte) 0xE1, out[3]);
        assertEquals('E', (char) out[6]);
        assertEquals('x', (char) out[7]);
        assertEquals('i', (char) out[8]);
        assertEquals('f', (char) out[9]);
    }

    @Test
    public void packageFilter_evaluatesCorrectly() {
        assertTrue(MediaProvider.isPackageAllowedByFilter("", "com.whatsapp"));
        assertTrue(MediaProvider.isPackageAllowedByFilter("com.whatsapp, org.telegram.messenger", "com.whatsapp"));
        assertFalse(MediaProvider.isPackageAllowedByFilter("com.whatsapp, org.telegram.messenger", "com.instagram.android"));
        assertTrue(MediaProvider.isPackageAllowedByFilter("com.whatsapp", BuildConfig.APPLICATION_ID));
    }

    @Test
    public void updateLatestVideoFrame_synchronizesBuffers() {
        byte[] sampleNv21 = new byte[16 * 16 * 3 / 2];
        Arrays.fill(sampleNv21, (byte) 0x5A);
        HookMain.updateLatestVideoFrame(sampleNv21, 16, 16);
        assertSame(sampleNv21, HookMain.data_buffer);
        assertSame(sampleNv21, HookMain.latestVideoNv21);
        assertEquals(16, HookMain.latestVideoWidth);
        assertEquals(16, HookMain.latestVideoHeight);
    }

    @Test
    public void imuMotionCorrelator_correlatesHandMovementAndHonorsStillDeadband() {
        // 1. Device resting stationary on desk -> near-zero shift (< 0.05 px)
        float[] still = ImuMotionCorrelator.computeCorrelatedShift(
                0.001f, -0.001f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f);
        assertTrue("Stationary device must have minimal jitter", Math.abs(still[0]) < 0.05f && Math.abs(still[1]) < 0.05f);

        // 2. User pitches & yaws phone during liveness prompt -> proportional directional optical shift
        float[] moving = ImuMotionCorrelator.computeCorrelatedShift(
                0.20f, -0.15f, 0.05f, 0.4f, 0.3f, 0.0f, 0.0f, 0.0f);
        assertTrue("Positive yaw/accel must induce positive X shift", moving[0] > 0.5f);
        assertTrue("Positive pitch/accel must induce positive Y shift", moving[1] > 0.5f);
        assertTrue("Roll rate must induce non-zero roll angle", Math.abs(moving[3]) > 0.001f);
    }

    @Test
    public void ndkLibraries_presentAndPass600TransactionIadHarness() throws Exception {
        String[] abis = new String[]{"arm64-v8a", "armeabi-v7a", "x86_64"};
        for (String abi : abis) {
            File soFile = new File("src/main/jniLibs/" + abi + "/libvcam_ndk.so");
            if (!soFile.exists()) {
                soFile = new File("app/src/main/jniLibs/" + abi + "/libvcam_ndk.so");
            }
            assertTrue("Missing libvcam_ndk.so for " + abi, soFile.exists() && soFile.length() > 4096);
            byte[] magic = new byte[4];
            try (FileInputStream fis = new FileInputStream(soFile)) {
                assertEquals(4, fis.read(magic));
            }
            assertEquals(0x7F, magic[0] & 0xFF);
            assertEquals('E', (char) magic[1]);
            assertEquals('L', (char) magic[2]);
            assertEquals('F', (char) magic[3]);
        }

        File harnessBin = new File("/tmp/vcam_iad_harness/vcam_iad_harness");
        if (harnessBin.exists() && harnessBin.canExecute()) {
            ProcessBuilder pb = new ProcessBuilder(harnessBin.getAbsolutePath());
            pb.directory(harnessBin.getParentFile());
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    out.append(line).append("\n");
                }
            }
            int exitCode = proc.waitFor();
            assertEquals("Native IAD harness failed: " + out, 0, exitCode);
            assertTrue(out.toString().contains("Transactions=600 LIVE=600 SPOOF=0"));
        }
    }

    // -------------------------------------------------------------------------
    // Controlled App-Injection Simulator Tests (Fixes for Uninjected Apps)
    // -------------------------------------------------------------------------

    static abstract class BaseSdkStateCallback extends android.hardware.camera2.CameraDevice.StateCallback {
        @Override
        public void onOpened(android.hardware.camera2.CameraDevice camera) {
        }

        @Override
        public void onDisconnected(android.hardware.camera2.CameraDevice camera) {
        }

        @Override
        public void onError(android.hardware.camera2.CameraDevice camera, int error) {
        }
    }

    static final class LeafCameraXStateCallback extends BaseSdkStateCallback {
        // Intentionally does NOT override onOpened(CameraDevice) — inherits from BaseSdkStateCallback,
        // replicating CameraX CameraDeviceStateCallbacks$ComboDeviceStateCallback & obfuscated KYC SDKs.
    }

    @Test
    public void hierarchyMethodResolution_findsInheritedCallbackMethods() {
        // 1. Prove that standard getDeclaredMethod (used by XposedHelpers.findAndHookMethod) fails on leaf class:
        boolean declaredFailed = false;
        try {
            LeafCameraXStateCallback.class.getDeclaredMethod(
                    "onOpened", android.hardware.camera2.CameraDevice.class);
        } catch (NoSuchMethodException expected) {
            declaredFailed = true;
        }
        assertTrue("Leaf subclass must not declare onOpened directly", declaredFailed);

        // 2. Prove that HookMain.findMethodInHierarchy walks superclasses and finds BaseSdkStateCallback.onOpened:
        java.lang.reflect.Method resolved = HookMain.findMethodInHierarchy(
                LeafCameraXStateCallback.class,
                "onOpened",
                android.hardware.camera2.CameraDevice.class);
        assertNotNull("findMethodInHierarchy must resolve inherited onOpened", resolved);
        assertEquals(BaseSdkStateCallback.class, resolved.getDeclaringClass());
    }

    @Test
    public void stateCallbackProxy_preventsTripleWrappingAcrossCameraManagerChain() throws Exception {
        HookMain hookMain = new HookMain();
        android.hardware.camera2.CameraDevice.StateCallback origCb = new LeafCameraXStateCallback();
        Object[] args = new Object[]{"0", origCb, null};

        java.lang.reflect.Method wrapMethod = HookMain.class.getDeclaredMethod(
                "wrapAndReplaceStateCallback",
                Object[].class,
                android.hardware.camera2.CameraDevice.StateCallback.class);
        wrapMethod.setAccessible(true);

        // Simulate openCamera() wrapping the callback:
        wrapMethod.invoke(hookMain, args, origCb);
        Object firstWrapped = args[1];
        assertNotNull(firstWrapped);
        assertNotSame(origCb, firstWrapped);
        assertEquals("VcamStateCallbackProxy", firstWrapped.getClass().getSimpleName());

        // Simulate chained openCameraForUid() and openCameraDeviceUserAsync() attempting to re-wrap:
        wrapMethod.invoke(hookMain, args, (android.hardware.camera2.CameraDevice.StateCallback) firstWrapped);
        assertSame("VcamStateCallbackProxy must not be double-wrapped", firstWrapped, args[1]);
    }

    @Test
    public void scopedStorageFallback_resolvesMediaWhenAppPrivateDirIsEmpty() throws Exception {
        File tmpRoot = new File(System.getProperty("java.io.tmpdir"), "vcam_sim_" + System.nanoTime());
        File emptyPrivateDir = new File(tmpRoot, "Android/data/com.target.app/files/Camera1");
        File fallbackDir = new File(tmpRoot, "fallback_media");
        assertTrue(emptyPrivateDir.mkdirs());
        assertTrue(fallbackDir.mkdirs());

        File stagedImg = new File(fallbackDir, "1000.bmp");
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(stagedImg)) {
            fos.write(new byte[]{0x42, 0x4D, 0x01, 0x02, 0x03, 0x04});
        }

        String prevVideoPath = HookMain.video_path;
        try {
            // Point video_path at the empty private dir (simulating Android 11+ un-staged private dir)
            HookMain.video_path = emptyPrivateDir.getAbsolutePath() + "/";
            File resolvedExplicit = HookMain.resolveBestImageFile(stagedImg.getAbsolutePath());
            assertNotNull("resolveBestImageFile must locate readable fallback image", resolvedExplicit);
            assertEquals(stagedImg.getAbsolutePath(), resolvedExplicit.getAbsolutePath());

            // Now copy into video_path and verify primary resolution
            File privImg = new File(emptyPrivateDir, "1000.bmp");
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(privImg)) {
                fos.write(new byte[]{0x42, 0x4D, 0x05, 0x06});
            }
            File resolvedPrimary = HookMain.resolveBestImageFile(null);
            assertNotNull("resolveBestImageFile(null) must locate media in video_path", resolvedPrimary);
            assertEquals(privImg.getAbsolutePath(), resolvedPrimary.getAbsolutePath());
        } finally {
            HookMain.video_path = prevVideoPath;
            // Cleanup temp files
            new File(emptyPrivateDir, "1000.bmp").delete();
            stagedImg.delete();
            emptyPrivateDir.delete();
            fallbackDir.delete();
            tmpRoot.delete();
        }
    }

    @Test
    public void manifest_declaresForceQueryableAndDirectBootProvider() throws Exception {
        File manifestFile = new File("src/main/AndroidManifest.xml");
        if (!manifestFile.exists()) {
            manifestFile = new File("app/src/main/AndroidManifest.xml");
        }
        assertTrue(manifestFile.exists());
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new java.io.FileReader(manifestFile))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }
        String xml = sb.toString();
        assertTrue("AndroidManifest must declare android:forceQueryable=true for API 30+ package visibility",
                xml.contains("android:forceQueryable=\"true\""));
        assertTrue("AndroidManifest must declare android:requestLegacyExternalStorage=true",
                xml.contains("android:requestLegacyExternalStorage=\"true\""));
        assertTrue("MediaProvider must declare android:directBootAware=true",
                xml.contains("android:directBootAware=\"true\""));
    }

    @Test
    public void ndkLibraries_exportSetSurfaceBuffersGeometrySymbol() throws Exception {
        String[] abis = new String[]{"arm64-v8a", "armeabi-v7a", "x86_64"};
        for (String abi : abis) {
            File soFile = new File("src/main/jniLibs/" + abi + "/libvcam_ndk.so");
            if (!soFile.exists()) {
                soFile = new File("app/src/main/jniLibs/" + abi + "/libvcam_ndk.so");
            }
            byte[] bytes = new byte[(int) soFile.length()];
            try (FileInputStream fis = new FileInputStream(soFile)) {
                int read = 0;
                while (read < bytes.length) {
                    int n = fis.read(bytes, read, bytes.length - read);
                    if (n <= 0) break;
                    read += n;
                }
            }
            String ascii = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
            assertTrue("libvcam_ndk.so (" + abi + ") must export nativeSetSurfaceBuffersGeometry",
                    ascii.contains("Java_com_example_vcam_NdkHookEngine_nativeSetSurfaceBuffersGeometry"));
            assertTrue("libvcam_ndk.so (" + abi + ") must reference ANativeWindow_setBuffersGeometry",
                    ascii.contains("ANativeWindow_setBuffersGeometry"));
        }
    }

    @Test
    public void hudTransformUpdate_switchesActiveSourceModeImmediately() {
        TransformConfig imgCfg = new TransformConfig();
        imgCfg.sourceMode = TransformConfig.SOURCE_MEDIA;
        imgCfg.preferredMediaType = TransformConfig.MEDIA_IMAGE;
        HookMain.updateActiveTransformFromHud(imgCfg);
        assertEquals(TransformConfig.SOURCE_MEDIA, HookMain.getActiveTransformConfig().sourceMode);
        assertEquals(TransformConfig.MEDIA_IMAGE, HookMain.getActiveTransformConfig().preferredMediaType);
        assertFalse(HookMain.isVideoModeActive());

        TransformConfig gridCfg = imgCfg.copy();
        gridCfg.sourceMode = TransformConfig.SOURCE_TEST_PATTERN;
        HookMain.updateActiveTransformFromHud(gridCfg);
        assertEquals(TransformConfig.SOURCE_TEST_PATTERN, HookMain.getActiveTransformConfig().sourceMode);

        TransformConfig vidCfg = gridCfg.copy();
        vidCfg.sourceMode = TransformConfig.SOURCE_MEDIA;
        vidCfg.preferredMediaType = TransformConfig.MEDIA_VIDEO;
        HookMain.updateActiveTransformFromHud(vidCfg);
        assertEquals(TransformConfig.SOURCE_MEDIA, HookMain.getActiveTransformConfig().sourceMode);
        assertEquals(TransformConfig.MEDIA_VIDEO, HookMain.getActiveTransformConfig().preferredMediaType);
    }
}
