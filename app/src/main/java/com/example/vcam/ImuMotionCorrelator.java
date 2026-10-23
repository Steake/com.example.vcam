package com.example.vcam;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.SystemClock;

import androidx.annotation.Nullable;

/**
 * Real-time IMU (Accelerometer + Gyroscope) to Optical-Flow Motion Correlator.
 *
 * <p>Addresses ARGOS Identity / Persona / Sumsub sensor-attestation checks that collect
 * physical motion sensor signals during camera capture to flag static/uncorrelated
 * pre-recorded video or image streams.
 *
 * <p>Reads the device's real hardware {@link Sensor#TYPE_GYROSCOPE} and
 * {@link Sensor#TYPE_ACCELEROMETER} streams (never faking IMU data in isolation) and
 * computes a critically damped sub-pixel / pixel displacement {@code (dxPx, dyPx)}
 * and micro-roll angle {@code rollDeg} proportional to the live angular velocity
 * and linear acceleration of the handset:
 * <ul>
 *   <li>When the device is resting stationary on a surface ({@code |omega| < 0.01 rad/s}),
 *       induced frame displacement is near zero ({@code < 0.15 px}).</li>
 *   <li>When the user holds or tilts the device during a liveness challenge, the injected
 *       frame shifts in phase with the physical pitch/yaw/roll of the phone.</li>
 * </ul>
 */
public final class ImuMotionCorrelator implements SensorEventListener {

    private static final ImuMotionCorrelator INSTANCE = new ImuMotionCorrelator();

    private SensorManager sensorManager;
    private boolean registered = false;

    private volatile float currentDxPx = 0.0f;
    private volatile float currentDyPx = 0.0f;
    private volatile float currentPitchRad = 0.0f;
    private volatile float currentRollRad = 0.0f;
    private volatile long lastSensorTimestampNs = 0L;

    // Low-pass filtered gravity vector for accelerometer fallback when gyroscope is absent
    private final float[] gravity = new float[]{0.0f, 0.0f, 9.81f};

    private ImuMotionCorrelator() {}

    public static ImuMotionCorrelator getInstance() {
        return INSTANCE;
    }

    public synchronized void start(@Nullable Context context) {
        if (registered || context == null) return;
        try {
            sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
            if (sensorManager == null) return;
            Sensor gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
            Sensor accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            if (gyro != null) {
                sensorManager.registerListener(this, gyro, SensorManager.SENSOR_DELAY_GAME);
            }
            if (accel != null) {
                sensorManager.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME);
            }
            registered = true;
        } catch (Throwable ignored) {
        }
    }

    public synchronized void stop() {
        if (!registered || sensorManager == null) return;
        try {
            sensorManager.unregisterListener(this);
        } catch (Throwable ignored) {
        }
        registered = false;
        currentDxPx = 0.0f;
        currentDyPx = 0.0f;
        currentPitchRad = 0.0f;
        currentRollRad = 0.0f;
        NdkHookEngine.updateImuMotion(0.0f, 0.0f, 0.0f, 0.0f);
    }

    public float getDxPx() {
        return currentDxPx;
    }

    public float getDyPx() {
        return currentDyPx;
    }

    public float getPitchRad() {
        return currentPitchRad;
    }

    public float getRollRad() {
        return currentRollRad;
    }

    /**
     * Pure calculation helper (also used by unit/validation tests) that maps
     * angular velocity {@code (wx, wy, wz)} in rad/s and linear acceleration
     * {@code (ax, ay, az)} in m/s^2 into correlated optical-flow pixel shifts.
     */
    public static float[] computeCorrelatedShift(float wx, float wy, float wz,
                                                 float ax, float ay, float az,
                                                 float prevDx, float prevDy) {
        // Spring-damper integration: angular pitch (wx) shifts Y, yaw (wy) shifts X
        // Typical handheld tremor is 0.03 .. 0.25 rad/s -> 0.5 .. 4.5 pixels at 1080p
        float targetDx = clamp(-wy * 18.0f + ax * 0.65f, -12.0f, 12.0f);
        float targetDy = clamp(wx * 18.0f + ay * 0.65f, -12.0f, 12.0f);

        // Deadband when device is completely still on a table
        if (Math.abs(wx) < 0.008f && Math.abs(wy) < 0.008f && Math.abs(wz) < 0.008f) {
            targetDx *= 0.1f;
            targetDy *= 0.1f;
        }

        float dx = prevDx * 0.72f + targetDx * 0.28f;
        float dy = prevDy * 0.72f + targetDy * 0.28f;
        float pitch = clamp(wx * 0.05f, -0.08f, 0.08f);
        float roll = clamp(wz * 0.05f, -0.08f, 0.08f);
        return new float[]{dx, dy, pitch, roll};
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event == null || event.values == null || event.values.length < 3) return;
        lastSensorTimestampNs = event.timestamp > 0 ? event.timestamp : SystemClock.elapsedRealtimeNanos();

        if (event.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
            float wx = event.values[0];
            float wy = event.values[1];
            float wz = event.values[2];
            float[] out = computeCorrelatedShift(wx, wy, wz, 0.0f, 0.0f, 0.0f, currentDxPx, currentDyPx);
            currentDxPx = out[0];
            currentDyPx = out[1];
            currentPitchRad = out[2];
            currentRollRad = out[3];
            NdkHookEngine.updateImuMotion(currentDxPx, currentDyPx, currentPitchRad, currentRollRad);
        } else if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            final float alpha = 0.85f;
            gravity[0] = alpha * gravity[0] + (1.0f - alpha) * event.values[0];
            gravity[1] = alpha * gravity[1] + (1.0f - alpha) * event.values[1];
            gravity[2] = alpha * gravity[2] + (1.0f - alpha) * event.values[2];
            float linAx = event.values[0] - gravity[0];
            float linAy = event.values[1] - gravity[1];
            float linAz = event.values[2] - gravity[2];
            float[] out = computeCorrelatedShift(0.0f, 0.0f, 0.0f, linAx, linAy, linAz, currentDxPx, currentDyPx);
            currentDxPx = out[0];
            currentDyPx = out[1];
            NdkHookEngine.updateImuMotion(currentDxPx, currentDyPx, currentPitchRad, currentRollRad);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }
}
