package com.samsung.android.ruler;

import android.app.Activity;
import android.content.Context;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Gate-1/2 probe: can the Samsung HighResolution usecase (shootingmode=40) deliver a
 * 25-frame RAW10 8160x6120 burst in one session, and does per-request manual exposure stick?
 *
 * Runs the PROVEN ErawTest50Mp session recipe (params via PDK bridge, semCreate streams,
 * vendor keys on preview+still), then fires 25 sequential STILL captures:
 *   frames 0-11 : AE (auto)
 *   frames 12-23: manual exposure ramp (sensor-exposureTime 40ms,10ms,80ms,20ms cycling x2, ISO ramp)
 *   frame 24    : AE again
 * Records per-frame: completion, exposureTime/sensitivity from the RESULT, RAW10 arrival,
 * row0/row-mid/row-last luma (data completeness), and wall time.
 */
public class TestActivity extends Activity {
    static final String TAG = "ERAWBURST";
    static final String P_HIGHRES =
        "first-entrance=false;samsungcamera=true;factorytest=false;shootingmode=40;" +
        "recording-fps=0;sw-vdis=false;video-beautyface=false;vtmode=0;" +
        "operation_mode=none;ssm_shot_mode=2;recording_dr_mode=sdr;sw-super_vdis=false;" +
        "stream_type=0;";
    static final int N_FRAMES = 25;

    void p(String s) { Log.i(TAG, s); }

    Method semCreate;
    Method setParams;
    Method pdkSetParam;

    void initPdk() {
        try {
            dalvik.system.PathClassLoader pcl = new dalvik.system.PathClassLoader(
                "/system/framework/scamera_sdk_util.jar",
                TestActivity.class.getClassLoader());
            Class<?> helper = Class.forName(
                "com.samsung.android.sdk.camera.impl.internal.CustomInterfaceHelper", true, pcl);
            pdkSetParam = helper.getMethod("setSamsungParameter", CameraDevice.class, String.class);
            p("PDK setSamsungParameter loaded: " + pdkSetParam);
        } catch (Throwable t) {
            p("PDK load FAIL: " + t);
        }
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        try {
            semCreate = OutputConfiguration.class.getMethod(
                "semCreateOutputConfiguration", int.class, Surface.class, int.class, int.class);
            p("semCreateOutputConfiguration found: " + semCreate);
        } catch (Throwable t) { p("semCreate MISSING: " + t); }
        try {
            setParams = CameraDevice.class.getMethod("setParameters", String.class);
            p("CameraDevice.setParameters found: " + setParams);
        } catch (Throwable t) { p("setParameters MISSING: " + t); }
        initPdk();
        new Thread(this::runTest).start();
    }

    void runTest() {
        attempt();
    }

    void attempt() {
        try {
            CameraManager cm = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            HandlerThread ht = new HandlerThread("t");
            ht.start();
            Handler h = new Handler(ht.getLooper());
            final CountDownLatch open = new CountDownLatch(1);
            final CountDownLatch cfg = new CountDownLatch(1);
            final CameraDevice[] dev = new CameraDevice[1];
            final CameraCaptureSession[] sess = new CameraCaptureSession[1];
            cm.openCamera("0", new CameraDevice.StateCallback() {
                public void onOpened(CameraDevice d) { dev[0] = d; open.countDown(); }
                public void onDisconnected(CameraDevice d) { p("disconnected"); }
                public void onError(CameraDevice d, int e) { p("dev onError " + e); }
            }, h);
            if (!open.await(8, TimeUnit.SECONDS)) { p("OPEN TIMEOUT"); return; }

            // params FIRST (proven recipe)
            Method m = (pdkSetParam != null) ? pdkSetParam : setParams;
            try { m.invoke(null, new Object[]{dev[0], P_HIGHRES}); p("setParameters OK"); }
            catch (Throwable t) { p("setParameters FAIL: " + t); return; }

            final int RAW_W = 8160, RAW_H = 6120;
            // RAW10 with more buffers for the burst: 25 stills; ImageReader maxImages must cover
            // the in-flight count. GCam uses AHardwareBuffer mapping in libgcam, we use plain reader.
            android.media.ImageReader raw = android.media.ImageReader.newInstance(RAW_W, RAW_H, 37, 25);
            android.media.ImageReader prev = android.media.ImageReader.newInstance(1280, 960, 35, 6);

            final long[][] frameStats = new long[N_FRAMES][4]; // expNs, iso, ts, arrivalFlag
            final int[] rawCount = new int[1];
            raw.setOnImageAvailableListener(r -> {
                try {
                    android.media.Image i = r.acquireLatestImage();
                    if (i != null) {
                        int idx = rawCount[0]++;
                        long ts = i.getTimestamp();
                        long row0 = 0, rowMid = 0, rowLast = 0;
                        try {
                            java.nio.ByteBuffer bb = i.getPlanes()[0].getBuffer();
                            int stride = i.getPlanes()[0].getRowStride();
                            int cap = bb.remaining();
                            // sample row 0, mid, last (packed RAW10: stride bytes/row)
                            bb.rewind();
                            int lastRowOff = cap - stride;
                            if (lastRowOff > 0) {
                                long sum = 0; int n = 0;
                                bb.position(0);
                                for (int x = 0; x + 4 < stride; x += 500) { sum += bb.get(x) & 0xFF; n++; }
                                row0 = n > 0 ? sum / n : -1;
                                bb.position((cap / stride / 2) * stride);
                                sum = 0; n = 0;
                                for (int x = 0; x + 4 < stride; x += 500) { sum += bb.get(x) & 0xFF; n++; }
                                rowMid = n > 0 ? sum / n : -1;
                                bb.position(lastRowOff);
                                sum = 0; n = 0;
                                for (int x = 0; x + 4 < stride; x += 500) { sum += bb.get(x) & 0xFF; n++; }
                                rowLast = n > 0 ? sum / n : -1;
                            }
                        } catch (Throwable t) { row0 = rowMid = rowLast = -2; }
                        p("RAW[" + idx + "] ts=" + ts + " stride_rows0_mid_last=" + row0 + "/" + rowMid + "/" + rowLast);
                        i.close();
                    }
                } catch (Throwable t) { p("rawcb ERR " + t); }
            }, h);
            prev.setOnImageAvailableListener(r -> {
                try { android.media.Image i = r.acquireLatestImage(); if (i != null) i.close(); }
                catch (Throwable t) { }
            }, h);

            OutputConfiguration ocRaw = (OutputConfiguration) semCreate.invoke(null,
                Integer.valueOf(-1), raw.getSurface(), Integer.valueOf(0), Integer.valueOf(2));
            OutputConfiguration ocPrev = (OutputConfiguration) semCreate.invoke(null,
                Integer.valueOf(-1), prev.getSurface(), Integer.valueOf(0), Integer.valueOf(1));

            CaptureRequest.Builder rb = dev[0].createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            rb.addTarget(prev.getSurface());
            vkey(rb);
            SessionConfiguration sc = new SessionConfiguration(SessionConfiguration.SESSION_REGULAR,
                Arrays.asList(ocRaw, ocPrev),
                new Executor() { public void execute(Runnable r) { h.post(r); } },
                new CameraCaptureSession.StateCallback() {
                    public void onConfigured(CameraCaptureSession s) { sess[0] = s; p("SESSION CONFIGURED"); cfg.countDown(); }
                    public void onConfigureFailed(CameraCaptureSession s) { p("SESSION CONFIG FAILED"); cfg.countDown(); }
                });
            sc.setSessionParameters(rb.build());
            dev[0].createCaptureSession(sc);
            if (!cfg.await(10, TimeUnit.SECONDS)) { p("CFG TIMEOUT"); return; }
            if (sess[0] == null) return;

            // repeating preview (3A settle), drain only
            final AtomicInteger prevCount = new AtomicInteger(0);
            CaptureRequest.Builder pb = dev[0].createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            pb.addTarget(prev.getSurface());
            vkey(pb);
            sess[0].setRepeatingRequest(pb.build(), new CameraCaptureSession.CaptureCallback() {
                public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest req, TotalCaptureResult res) {
                    prevCount.incrementAndGet();
                }
                public void onCaptureFailed(CameraCaptureSession s, CaptureRequest req, CaptureFailure f) {
                    p("PREVIEW FAILED r=" + f.getReason());
                }
            }, h);
            long t0 = System.currentTimeMillis();
            while (prevCount.get() < 6 && System.currentTimeMillis() - t0 < 8000) {
                try { Thread.sleep(50); } catch (InterruptedException ie) { break; }
            }
            p("previews settled: " + prevCount.get());

            // ==== THE BURST: 25 sequential stills, raw target each ====
            long[] manualExp = new long[]{40_000_000L, 10_000_000L, 80_000_000L, 20_000_000L};
            int[] manualIso = new int[]{100, 200, 400, 800};
            int okCount = 0, failCount = 0;
            long burstT0 = System.currentTimeMillis();
            for (int k = 0; k < N_FRAMES; k++) {
                final int idx = k;
                try {
                    CaptureRequest.Builder sb = dev[0].createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                    sb.addTarget(raw.getSurface());
                    vkey(sb);
                    sb.set(CaptureRequest.CONTROL_AE_MODE, new Byte(CaptureRequest.CONTROL_AE_MODE_ON)); // per-request AE on
                    if (k >= 12 && k < 24) {
                        // manual exposure frame (AE off)
                        sb.set(CaptureRequest.CONTROL_AE_MODE, new Byte(CaptureRequest.CONTROL_AE_MODE_OFF));
                        long exp = manualExp[(k - 12) % 4];
                        int iso = manualIso[(k - 12) % 4];
                        sb.set(CaptureRequest.SENSOR_EXPOSURE_TIME, Long.valueOf(exp));
                        sb.set(CaptureRequest.SENSOR_SENSITIVITY, Integer.valueOf(iso));
                        p("frame[" + k + "] manual exp=" + (exp / 1_000_000) + "ms iso=" + iso);
                    }
                    sess[0].capture(sb.build(), new CameraCaptureSession.CaptureCallback() {
                        public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest req, TotalCaptureResult res) {
                            Long e = res.get(CaptureResult.SENSOR_EXPOSURE_TIME);
                            Integer iso = res.get(CaptureResult.SENSOR_SENSITIVITY);
                            Integer ae = res.get(CaptureResult.CONTROL_AE_STATE);
                            frameStats[idx][0] = e == null ? -1 : e;
                            frameStats[idx][1] = iso == null ? -1 : iso;
                            p("STILL[" + idx + "] COMPLETED frame=" + res.getFrameNumber()
                                + " exp=" + e + " iso=" + iso + " aeState=" + ae);
                        }
                        public void onCaptureFailed(CameraCaptureSession s, CaptureRequest req, CaptureFailure f) {
                            p("STILL[" + idx + "] FAILED reason=" + f.getReason());
                        }
                    }, h);
                    okCount++;
                } catch (Throwable t) {
                    p("STILL[" + k + "] submit ERR " + t);
                }
                // pacing: let each still complete before the next (sequential, not pipelined)
                try { Thread.sleep(400); } catch (InterruptedException ie) { }
            }
            long burstMs = System.currentTimeMillis() - burstT0;
            // wait for all raws
            long tw = System.currentTimeMillis();
            while (rawCount[0] < N_FRAMES && System.currentTimeMillis() - tw < 15000) {
                try { Thread.sleep(200); } catch (InterruptedException ie) { break; }
            }
            p("=== BURST DONE: submitted=" + okCount + " raws=" + rawCount[0] + " in " + burstMs + "ms ===");
            StringBuilder sb = new StringBuilder();
            for (int k = 0; k < N_FRAMES; k++) {
                sb.append("f").append(k).append("(exp=").append(frameStats[k][0])
                  .append(",iso=").append(frameStats[k][1]).append(") ");
            }
            p("FRAMESTATS " + sb);

            Thread.sleep(1000);
            sess[0].close();
            dev[0].close();
            ht.quit();
        } catch (Throwable t) {
            p("attempt ERR " + Log.getStackTraceString(t));
        }
    }

    void vkey(CaptureRequest.Builder b) {
        try {
            b.set(new CaptureRequest.Key<Integer>("samsung.android.control.shootingMode", Integer.class), Integer.valueOf(40));
            b.set(new CaptureRequest.Key<Integer>("samsung.android.control.ssmShotMode", Integer.class), Integer.valueOf(2));
            b.set(new CaptureRequest.Key<Integer>("samsung.android.control.capturePhysicalId", Integer.class), Integer.valueOf(56));
        } catch (Throwable t) { p("vkey ERR " + t); }
    }
}
