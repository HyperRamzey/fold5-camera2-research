package com.agc;

import android.os.SystemClock;
import android.util.Log;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Vendor OIS telemetry. The Android OIS keys are absent from cam 56, so the
 * live source is the Samsung MCU sysfs node
 * /sys/class/camera/ois/ois_hall_position (8 u32: targetX/Y for 2 modules,
 * then hallX/Y for the same 2 modules). The node is mode 0444 system:system;
 * untrusted_app SELinux normally blocks it, so a KernelSU root `su -c` loop
 * is used when the direct read is denied.
 *
 * A kernel read costs ~125 ms (unconditional msleep(100) + retries), so this
 * is ~8 Hz telemetry, NOT 500 Hz. The 500 Hz trajectory comes from
 * Eraw70Imu. Units are raw hall ADC counts - no px calibration is invented.
 */
final class Eraw70Ois {
    static final String TAG = Eraw70.TAG;
    static final String NODE = "/sys/class/camera/ois/ois_hall_position";
    static final String EXIF = "/sys/class/camera/ois/ois_exif";
    static final String GAIN = "/sys/class/camera/ois/ois_gain_rear";
    static final String RAW = "/sys/class/camera/ois/ois_rawdata";
    static final String ADC = "/sys/class/camera/ois/adc";
    /** App-process PATH does not resolve "su" on this build; use the absolute path. */
    static final String SU = "/system/bin/su";

    final ArrayList<VendorSample> samples = new ArrayList<VendorSample>();
    volatile boolean running;
    volatile boolean directUsed;
    volatile boolean rootUsed;
    volatile String error;
    volatile String exif;
    volatile String gain;
    volatile String raw;
    volatile String adc;
    volatile int parseFails;
    Thread thread;
    Process proc;

    /** Re-read the one-shot vendor nodes while the sensor is still streaming. */
    void refreshAux() {
        try {
            exif = suCat(EXIF, 2000);
            gain = suCat(GAIN, 2000);
            raw = suCat(RAW, 2000);
            adc = suCat(ADC, 2000);
        } catch (Throwable ignored) {
        }
    }

    void start() {
        refreshAux();
        String probe = null;
        try {
            probe = readDirect();
        } catch (Throwable t) {
            error = "direct probe: " + t;
        }
        directUsed = probe != null && parse(probe, SystemClock.elapsedRealtimeNanos());
        running = true;
        if (directUsed) {
            Log.i(TAG, "OIS hall direct read OK: " + probe);
            thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    directLoop();
                }
            }, "eraw70ois");
        } else {
            Log.i(TAG, "OIS hall direct denied - using su -c root loop");
            thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    rootLoop();
                }
            }, "eraw70ois");
        }
        thread.start();
        Log.i(TAG, "OIS poller start direct=" + directUsed + " exif=" + exif
                + " gain=" + gain + " raw=" + raw + " adc=" + adc);
    }

    void stop() {
        running = false;
        try {
            if (proc != null) proc.destroyForcibly();
        } catch (Throwable ignored) {
        }
        proc = null;
        try {
            if (thread != null) thread.interrupt();
        } catch (Throwable ignored) {
        }
        try {
            if (thread != null) thread.join(400);
        } catch (Throwable ignored) {
        }
        Log.i(TAG, "OIS stop samples=" + samples.size() + " root=" + rootUsed
                + " direct=" + directUsed + " parseFails=" + parseFails);
    }

    void directLoop() {
        int fails = 0;
        while (running) {
            try {
                String s = readDirect();
                long t = SystemClock.elapsedRealtimeNanos();
                if (!parse(s, t)) fails++;
                else fails = 0;
                if (fails >= 3) {
                    error = "direct read failed 3x";
                    break;
                }
            } catch (Throwable t) {
                error = "direct loop: " + t;
                break;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException ie) {
                break;
            }
        }
        Log.i(TAG, "OIS direct loop exit: " + error);
    }

    void rootLoop() {
        try {
            ProcessBuilder pb = new ProcessBuilder(SU, "-c",
                    "while true; do cat " + NODE + "; done");
            pb.redirectErrorStream(true);
            proc = pb.start();
            rootUsed = true;
            BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream()));
            String line;
            while (running && (line = r.readLine()) != null) {
                long t = SystemClock.elapsedRealtimeNanos();
                if (!parse(line, t)) {
                    parseFails++;
                    String s = line.trim();
                    if (s.length() > 0 && error == null && !s.contains(",")) error = s;
                }
            }
            Log.i(TAG, "OIS root loop exit lines parseFails=" + parseFails);
        } catch (Throwable t) {
            error = "root loop: " + t;
            Log.e(TAG, "OIS root loop EX " + t);
        }
    }

    static String readDirect() throws Exception {
        FileInputStream fis = new FileInputStream(NODE);
        try {
            byte[] b = new byte[256];
            int n = fis.read(b);
            if (n <= 0) return null;
            return new String(b, 0, n, "UTF-8").trim();
        } finally {
            try {
                fis.close();
            } catch (Throwable ignored) {
            }
        }
    }

    boolean parse(String line, long t) {
        if (line == null) return false;
        String[] p = line.trim().split(",");
        if (p.length < 8) return false;
        int[] v = new int[8];
        try {
            for (int i = 0; i < 8; i++) v[i] = Integer.parseInt(p[i].trim());
        } catch (Throwable x) {
            return false;
        }
        synchronized (samples) {
            samples.add(new VendorSample(t, v));
            while (samples.size() > 2000) samples.remove(0);
        }
        return true;
    }

    List<VendorSample> snapshot() {
        synchronized (samples) {
            return new ArrayList<VendorSample>(samples);
        }
    }

    static String suCat(String path, int timeoutMs) {
        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(SU, "-c", "cat " + path);
            pb.redirectErrorStream(true);
            p = pb.start();
            if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                try {
                    p.destroyForcibly();
                } catch (Throwable ignored) {
                }
                return null;
            }
            InputStream in = p.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] b = new byte[512];
            int n;
            while (bos.size() < 8192 && (n = in.read(b)) > 0) bos.write(b, 0, n);
            return new String(bos.toByteArray(), "UTF-8").trim();
        } catch (Throwable t) {
            return null;
        } finally {
            try {
                if (p != null) p.destroy();
            } catch (Throwable ignored) {
            }
        }
    }

    static final class VendorSample {
        final long t;
        final int[] v;

        VendorSample(long t, int[] v) {
            this.t = t;
            this.v = v;
        }
    }
}
