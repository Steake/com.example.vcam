package com.example.vcam;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.*;

/**
 * Unit tests for VCAM's core pure-Java pipelines:
 * - BT.601 ARGB -> NV21 / I420 color accuracy (verifying R/B channel fix)
 * - Temporal sensor grain LUT injection (verifying anti-duplicate frame hashes)
 * - Little-Endian TIFF/EXIF APP1 segment synthesis
 * - Package filter whitelist evaluation
 */
public class ExampleUnitTest {

    @Test
    public void argbToNv21_preservesRedAndBlueCorrectly() {
        // Pure Red pixel in Android ARGB_8888: 0xFFFF0000 (R=255, G=0, B=0)
        int[] redPixels = new int[]{
                0xFFFF0000, 0xFFFF0000,
                0xFFFF0000, 0xFFFF0000
        };
        byte[] nv21Red = FrameTransformPipeline.argbToNv21(redPixels, 2, 2);
        assertEquals(6, nv21Red.length);
        int yRed = nv21Red[0] & 0xFF;
        int vRed = nv21Red[4] & 0xFF; // NV21 puts V (Cr) first
        int uRed = nv21Red[5] & 0xFF; // NV21 puts U (Cb) second
        // For pure red: Y ~ 82, V (Cr) > 200, U (Cb) < 100
        assertTrue("Red Y should be around 81..82", yRed >= 75 && yRed <= 90);
        assertTrue("Red Cr (V) must be high (>200)", vRed > 200);
        assertTrue("Red Cb (U) must be low (<100)", uRed < 100);

        // Pure Blue pixel in Android ARGB_8888: 0xFF0000FF (R=0, G=0, B=255)
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
        // Minimal valid JPEG SOI + EOI
        byte[] minimalJpeg = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xD9};
        SensorProfiler.LensProfile profile = SensorProfiler.LensProfile.defaultForFacing("back");
        ExifSynthesizer.FramePhotometry phot = new ExifSynthesizer.FramePhotometry();

        byte[] out = ExifSynthesizer.injectExifIntoJpeg(minimalJpeg, 1920, 1080, profile, phot);
        assertNotNull(out);
        assertTrue(out.length > minimalJpeg.length);
        // Starts with SOI (FF D8) followed by APP1 marker (FF E1)
        assertEquals((byte) 0xFF, out[0]);
        assertEquals((byte) 0xD8, out[1]);
        assertEquals((byte) 0xFF, out[2]);
        assertEquals((byte) 0xE1, out[3]);
        // Contains "Exif\0\0" at offset 6..11
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
}