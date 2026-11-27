package com.agc;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * 1.5 s ring buffer of uncalibrated gyro / accel and game rotation vector at
 * SENSOR_DELAY_FASTEST. SensorEvent.timestamp is CLOCK_BOOTTIME, the same
 * timebase as Camera2 SENSOR_TIMESTAMP when the camera reports REALTIME.
 */
final class Eraw70Imu implements SensorEventListener {
    static final String TAG = Eraw70.TAG;
    static final long RING_NS = 1500000000L;
    static final int MAX_SAMPLES = 8000;

    final SensorManager sm;
    HandlerThread thread;
    Handler handler;
    final ArrayList<ImuSample> gyro = new ArrayList<ImuSample>();
    final ArrayList<ImuSample> accel = new ArrayList<ImuSample>();
    final ArrayList<ImuSample> game = new ArrayList<ImuSample>();
    volatile boolean running;

    Eraw70Imu(Context ctx) {
        sm = (SensorManager) ctx.getSystemService(Context.SENSOR_SERVICE);
    }

    void start() {
        thread = new HandlerThread("eraw70imu");
        thread.start();
        handler = new Handler(thread.getLooper());
        running = true;
        Sensor g = sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE_UNCALIBRATED);
        Sensor a = sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER_UNCALIBRATED);
        Sensor r = sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
        if (g != null) sm.registerListener(this, g, 0, handler);
        if (a != null) sm.registerListener(this, a, 0, handler);
        if (r != null) sm.registerListener(this, r, 0, handler);
        Log.i(TAG, "IMU start gyro=" + (g != null) + " accel=" + (a != null)
                + " game=" + (r != null));
    }

    void stop() {
        running = false;
        try {
            if (sm != null) sm.unregisterListener(this);
        } catch (Throwable ignored) {
        }
        try {
            if (thread != null) thread.quitSafely();
        } catch (Throwable ignored) {
        }
        Log.i(TAG, "IMU stop gyro=" + gyro.size() + " accel=" + accel.size()
                + " game=" + game.size());
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        if (!running || e == null || e.values == null) return;
        ImuSample s = new ImuSample(e.timestamp, e.values);
        int type = e.sensor.getType();
        if (type == Sensor.TYPE_GYROSCOPE_UNCALIBRATED) {
            add(gyro, s);
        } else if (type == Sensor.TYPE_ACCELEROMETER_UNCALIBRATED) {
            add(accel, s);
        } else if (type == Sensor.TYPE_GAME_ROTATION_VECTOR) {
            add(game, s);
        }
    }

    static void add(ArrayList<ImuSample> list, ImuSample s) {
        synchronized (list) {
            list.add(s);
            long cutoff = s.t - RING_NS;
            int drop = 0;
            while (drop < list.size() && list.get(drop).t < cutoff) drop++;
            if (drop > 0) list.subList(0, drop).clear();
            while (list.size() > MAX_SAMPLES) list.remove(0);
        }
    }

    List<ImuSample> snapshot(ArrayList<ImuSample> list) {
        synchronized (list) {
            return new ArrayList<ImuSample>(list);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    static final class ImuSample {
        final long t;
        final float[] v;

        ImuSample(long t, float[] src) {
            this.t = t;
            this.v = new float[src.length];
            System.arraycopy(src, 0, this.v, 0, src.length);
        }
    }
}
