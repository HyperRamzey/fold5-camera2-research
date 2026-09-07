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

public class TestActivity extends Activity {
    static final String TAG = "ERAWTEST";
    static final String P_HIGHRES =
        "first-entrance=false;samsungcamera=true;factorytest=false;shootingmode=40;" +
        "recording-fps=0;sw-vdis=false;video-beautyface=false;vtmode=0;" +
        "operation_mode=none;ssm_shot_mode=2;recording_dr_mode=sdr;sw-super_vdis=false;" +
        "stream_type=0;";

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
        // dump vendor tags as seen by APP identity
        try {
            CameraManager cm = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            for (String id : new String[]{"0", "1", "5", "52", "56", "58"}) {
                try {
                    CameraCharacteristics ch = cm.getCameraCharacteristics(id);
                    for (String n : new String[]{
                            "samsung.android.scaler.availableExpertRawHighresRawStreamConfigurations",
                            "samsung.android.scaler.availableHighresRawStreamConfigurations",
                            "samsung.android.scaler.availableTetraPictureStreamConfigurations",
                            "samsung.android.scaler.availableFullTetraPictureStreamConfigurations"}) {
                        try {
                            CameraCharacteristics.Key<int[]> k = new CameraCharacteristics.Key<>(n, int[].class);
                            int[] v = ch.get(k);
                            p("cam" + id + " " + n + " = " + (v == null ? "null" : Arrays.toString(v)));
                        } catch (Throwable t) { p("cam" + id + " " + n + " ERR " + t); }
                    }
                } catch (Throwable t) { p("cam" + id + " chars ERR: " + t); }
            }
            p("cameraIdList: " + Arrays.toString(cm.getCameraIdList()));
        } catch (Throwable t) { p("dump ERR " + t); }
        new Thread(this::runTest).start();
    }

    void runTest() {
        p("=== TEST A: no params, raw50+preview (baseline, expect OK) ===");
        boolean base = attempt("A", null, false);
        p("=== TEST B: setParameters(shootingmode=40), raw50+preview ===");
        boolean withP = attempt("B", P_HIGHRES, true);
        p("=== RESULTS: baseline=" + base + " withParams=" + withP + " ===");
    }

    boolean attempt(String tag, String param, boolean vendorKeys) {
        try {
            CameraManager cm = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            HandlerThread ht = new HandlerThread("t");
            ht.start();
            Handler h = new Handler(ht.getLooper());
            final CountDownLatch open = new CountDownLatch(1);
            final CountDownLatch cfg = new CountDownLatch(1);
            final CountDownLatch img = new CountDownLatch(1);
            final CameraDevice[] dev = new CameraDevice[1];
            final CameraCaptureSession[] sess = new CameraCaptureSession[1];
            cm.openCamera("0", new CameraDevice.StateCallback() {
                public void onOpened(CameraDevice d) { dev[0] = d; open.countDown(); }
                public void onDisconnected(CameraDevice d) { p(tag + " disconnected"); }
                public void onError(CameraDevice d, int e) { p(tag + " dev onError " + e); }
            }, h);
            if (!open.await(8, TimeUnit.SECONDS)) { p(tag + " OPEN TIMEOUT"); return false; }
            if (param != null) {
                Method m = (pdkSetParam != null) ? pdkSetParam : setParams;
                if (m == null) { p(tag + " no setParameters method"); return false; }
                try { m.invoke(null, new Object[]{dev[0], param}); p(tag + " setParameters OK: " + param); }
                catch (Throwable t) { p(tag + " setParameters FAIL: " + t); return false; }
            }
            // 50MP mode: YUV + JPEG + preview (Samsung HAL Bayer->Yuv->Jpeg pipeline)
            android.media.ImageReader raw = android.media.ImageReader.newInstance(8160, 6120, 37, 8);
            android.media.ImageReader jpg = android.media.ImageReader.newInstance(8160, 6120, 256, 8);
            android.media.ImageReader prev = android.media.ImageReader.newInstance(1920, 1440, 35, 6);
            jpg.setOnImageAvailableListener(r -> {
                try {
                    android.media.Image i = r.acquireLatestImage();
                    if (i != null) {
                        p(tag + " *** JPEG " + i.getWidth() + "x" + i.getHeight() + " fmt=" + i.getFormat());
                        try {
                            java.nio.ByteBuffer bb = i.getPlanes()[0].getBuffer();
                            byte[] buf = new byte[bb.remaining()];
                            bb.get(buf);
                            java.io.File out = new java.io.File(getExternalFilesDir(null), "still50mp.jpg");
                            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(out)) { fos.write(buf); }
                            p(tag + " saved JPEG " + buf.length + " bytes");
                        } catch (Throwable t) { p(tag + " jpg save ERR " + t); }
                        i.close();
                    }
                } catch (Throwable t) { p(tag + " jpgcb " + t); }
            }, h);
            raw.setOnImageAvailableListener(r -> {
                try {
                    android.media.Image i = r.acquireLatestImage();
                    if (i != null) {
                        p(tag + " *** RAW " + i.getWidth() + "x" + i.getHeight() + " ts=" + i.getTimestamp());
                        if (tag.equals("B")) {
                            try {
                                java.nio.ByteBuffer bb = i.getPlanes()[0].getBuffer();
                                byte[] buf = new byte[bb.remaining()];
                                bb.get(buf);
                                java.io.File out = new java.io.File(getExternalFilesDir(null), "still50mp.raw10");
                                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(out)) { fos.write(buf); }
                                p(tag + " saved " + buf.length + " bytes to " + out);
                            } catch (Throwable t) { p(tag + " save ERR " + t); }
                        }
                        i.close();
                        img.countDown();
                    }
                } catch (Throwable t) { p(tag + " rawcb " + t); }
            }, h);
            prev.setOnImageAvailableListener(r -> {
                try {
                    android.media.Image i = r.acquireLatestImage();
                    if (i != null) i.close();
                } catch (Throwable t) { }
            }, h);
            OutputConfiguration ocRaw = (OutputConfiguration) semCreate.invoke(null,
                Integer.valueOf(-1), raw.getSurface(), Integer.valueOf(0), Integer.valueOf(2));
            OutputConfiguration ocJpg = (OutputConfiguration) semCreate.invoke(null,
                Integer.valueOf(-1), jpg.getSurface(), Integer.valueOf(0), Integer.valueOf(2));
            OutputConfiguration ocPrev = (OutputConfiguration) semCreate.invoke(null,
                Integer.valueOf(-1), prev.getSurface(), Integer.valueOf(0), Integer.valueOf(1));
            CaptureRequest.Builder rb = dev[0].createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            if (vendorKeys) {
                try {
                    rb.set(new CaptureRequest.Key<Integer>("samsung.android.control.shootingMode", Integer.class), Integer.valueOf(40));
                    rb.set(new CaptureRequest.Key<Integer>("samsung.android.control.ssmShotMode", Integer.class), Integer.valueOf(2));
                    rb.set(new CaptureRequest.Key<Integer>("samsung.android.control.capturePhysicalId", Integer.class), Integer.valueOf(56));
                    p(tag + " vendor keys set");
                } catch (Throwable t) { p(tag + " vkey ERR " + t); }
            }
            rb.addTarget(raw.getSurface());
            rb.addTarget(prev.getSurface());
            SessionConfiguration sc = new SessionConfiguration(SessionConfiguration.SESSION_REGULAR,
                Arrays.asList(ocRaw, ocJpg, ocPrev),
                new Executor() { public void execute(Runnable r) { h.post(r); } },
                new CameraCaptureSession.StateCallback() {
                    public void onConfigured(CameraCaptureSession s) { sess[0] = s; p(tag + " SESSION CONFIGURED"); cfg.countDown(); }
                    public void onConfigureFailed(CameraCaptureSession s) { p(tag + " SESSION CONFIG FAILED"); cfg.countDown(); }
                });
            sc.setSessionParameters(rb.build());
            dev[0].createCaptureSession(sc);
            if (!cfg.await(10, TimeUnit.SECONDS)) { p(tag + " CFG TIMEOUT"); return false; }
            if (sess[0] == null) return false;
            // repeating: PREVIEW-ONLY (like Expert RAW's repeating preview)
            final int[] pcount = new int[1];
            CaptureRequest.Builder pb = dev[0].createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            pb.addTarget(prev.getSurface());
            if (vendorKeys) {
                try {
                    pb.set(new CaptureRequest.Key<Integer>("samsung.android.control.shootingMode", Integer.class), Integer.valueOf(40));
                    pb.set(new CaptureRequest.Key<Integer>("samsung.android.control.ssmShotMode", Integer.class), Integer.valueOf(2));
                    pb.set(new CaptureRequest.Key<Integer>("samsung.android.control.capturePhysicalId", Integer.class), Integer.valueOf(56));
                    p(tag + " vendor keys on repeating too");
                } catch (Throwable t) { p(tag + " vkey-rep ERR " + t); }
            }
            sess[0].setRepeatingRequest(pb.build(), new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest req, TotalCaptureResult res) {
                    Integer px = res.get(CaptureResult.SENSOR_PIXEL_MODE);
                    pcount[0]++;
                    p(tag + " PREVIEW frame=" + res.getFrameNumber() + " SENSOR_PIXEL_MODE=" + px);
                }
                @Override
                public void onCaptureFailed(CameraCaptureSession s, CaptureRequest req, CaptureFailure f) {
                    p(tag + " PREVIEW FAILED reason=" + f.getReason() + " frame=" + f.getFrameNumber());
                }
            }, h);
            long t0 = System.currentTimeMillis();
            while (pcount[0] < 5 && System.currentTimeMillis() - t0 < 8000) {
                try { Thread.sleep(50); } catch (InterruptedException ie) { break; }
            }
            p(tag + " preview completions before still: " + pcount[0]);
            {
                // still capture with raw target + vendor keys
                try {
                    CaptureRequest.Builder sb = dev[0].createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                    sb.addTarget(raw.getSurface());
                    sb.addTarget(jpg.getSurface());
                    sb.set(new CaptureRequest.Key<Integer>("samsung.android.control.shootingMode", Integer.class), Integer.valueOf(40));
                    sb.set(new CaptureRequest.Key<Integer>("samsung.android.control.ssmShotMode", Integer.class), Integer.valueOf(2));
                    sb.set(new CaptureRequest.Key<Integer>("samsung.android.control.capturePhysicalId", Integer.class), Integer.valueOf(56));
                    p(tag + " firing STILL capture");
                    sess[0].capture(sb.build(), new CameraCaptureSession.CaptureCallback() {
                        @Override
                        public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest req, TotalCaptureResult res) {
                            p(tag + " STILL COMPLETED frame=" + res.getFrameNumber() + " px=" + res.get(CaptureResult.SENSOR_PIXEL_MODE));
                        }
                        @Override
                        public void onCaptureFailed(CameraCaptureSession s, CaptureRequest req, CaptureFailure f) {
                            p(tag + " STILL FAILED reason=" + f.getReason());
                        }
                    }, h);
                } catch (Throwable t) { p(tag + " still ERR " + t); }
            }
            boolean got = img.await(10, TimeUnit.SECONDS);
            p(tag + (got ? " RESULT: SUCCESS" : " RESULT: no image"));
            Thread.sleep(1500);
            sess[0].close();
            dev[0].close();
            ht.quit();
            return got;
        } catch (Throwable t) {
            p(tag + " attempt ERR: " + Log.getStackTraceString(t));
            return false;
        }
    }
}
