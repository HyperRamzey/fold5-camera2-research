import android.content.Context;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.Surface;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/** ProbeV6: 50MP with sensorPixelMode=1 (remosaic) + secondary streams like Expert RAW uses. */
public final class ProbeV6 {

    static void p(String s) { System.out.println(s); }
    static Context ctx;
    static Method sSetParams;
    static Method sSemCreate;

    static final String P_HIGHRES =
        "first-entrance=false;samsungcamera=true;factorytest=false;shootingmode=40;" +
        "recording-fps=0;sw-vdis=false;video-beautyface=false;vtmode=0;" +
        "operation_mode=none;ssm_shot_mode=2;recording_dr_mode=sdr;sw-super_vdis=false;" +
        "stream_type=0;";

    static void init() throws Exception {
        sSetParams = CameraDevice.class.getMethod("setParameters", String.class);
        sSemCreate = OutputConfiguration.class.getMethod(
            "semCreateOutputConfiguration", int.class, Surface.class, int.class, int.class);
    }

    static void attempt(String tag, String camId, String param, int[][] streams, Integer physId,
                        Integer shootMode, Integer ssm, int template) {
        // streams: {format, w, h, usage, sensorPixelMode}
        p("### " + tag);
        HandlerThread ht = new HandlerThread("pv6");
        ht.start();
        Handler handler = new Handler(ht.getLooper());
        try {
            CameraManager cm = ctx.getSystemService(CameraManager.class);
            final CountDownLatch open = new CountDownLatch(1);
            final CountDownLatch cfg = new CountDownLatch(1);
            final CountDownLatch img = new CountDownLatch(1);
            final CameraDevice[] dev = new CameraDevice[1];
            final CameraCaptureSession[] sess = new CameraCaptureSession[1];
            cm.openCamera(camId, new CameraDevice.StateCallback() {
                public void onOpened(CameraDevice d) { dev[0] = d; open.countDown(); }
                public void onDisconnected(CameraDevice d) { p("dev disconnected"); }
                public void onError(CameraDevice d, int e) { p("dev onError " + e); }
            }, handler);
            if (!open.await(8, TimeUnit.SECONDS)) { p("OPEN TIMEOUT"); return; }
            if (param != null) {
                try { sSetParams.invoke(dev[0], param); p("setParameters OK"); }
                catch (Throwable t) { p("setParameters FAIL: " + t); return; }
            }
            java.util.List<OutputConfiguration> outs = new java.util.ArrayList<>();
            java.util.List<android.media.ImageReader> readers = new java.util.ArrayList<>();
            CaptureRequest.Builder b = dev[0].createCaptureRequest(template);
            for (int[] s : streams) {
                android.media.ImageReader r = android.media.ImageReader.newInstance(s[1], s[2], s[0], 3);
                final int fmt = s[0];
                r.setOnImageAvailableListener(rr -> {
                    try {
                        android.media.Image i = rr.acquireLatestImage();
                        if (i != null) {
                            p("*** IMAGE[" + fmt + "]: " + i.getWidth() + "x" + i.getHeight() + " ts=" + i.getTimestamp());
                            img.countDown();
                        }
                    } catch (Throwable t) { p("imgcb " + t); }
                }, handler);
                readers.add(r);
                OutputConfiguration oc = (OutputConfiguration) sSemCreate.invoke(null,
                    Integer.valueOf(-1), r.getSurface(), Integer.valueOf(0), Integer.valueOf(s[3]));
                if (physId != null) oc.setPhysicalCameraId(String.valueOf(physId));
                if (s[4] > 0) {
                    try {
                        OutputConfiguration.class.getMethod("addSensorPixelModeUsed", int.class)
                            .invoke(oc, Integer.valueOf(s[4]));
                        p("sensorPixelMode=" + s[4] + " added");
                    } catch (Throwable t) { p("spm ERR " + t); }
                }
                b.addTarget(r.getSurface());
                outs.add(oc);
            }
            if (shootMode != null) b.set(new CaptureRequest.Key<Integer>("samsung.android.control.shootingMode", Integer.class), shootMode);
            if (ssm != null) b.set(new CaptureRequest.Key<Integer>("samsung.android.control.ssmShotMode", Integer.class), ssm);
            if (physId != null) b.set(new CaptureRequest.Key<Integer>("samsung.android.control.capturePhysicalId", Integer.class), physId);
            SessionConfiguration sc = new SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outs,
                new Executor() { public void execute(Runnable r) { handler.post(r); } },
                new CameraCaptureSession.StateCallback() {
                    public void onConfigured(CameraCaptureSession s) { sess[0] = s; p("SESSION CONFIGURED"); cfg.countDown(); }
                    public void onConfigureFailed(CameraCaptureSession s) { p("SESSION CONFIG **FAILED**"); cfg.countDown(); }
                });
            sc.setSessionParameters(b.build());
            dev[0].createCaptureSession(sc);
            if (!cfg.await(10, TimeUnit.SECONDS)) { p("CFG TIMEOUT"); return; }
            if (sess[0] != null) {
                sess[0].setRepeatingRequest(b.build(), null, handler);
                if (img.await(8, TimeUnit.SECONDS)) p("RESULT: SUCCESS <<< " + tag);
                else p("RESULT: no image");
                sess[0].close();
            }
        } catch (Throwable t) {
            p("ATTEMPT FAIL: " + t);
        } finally {
            ht.quit();
        }
        p("---");
    }

    public static void main(String[] args) {
        try {
            Looper.prepareMainLooper();
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object thread = at.getMethod("systemMain").invoke(null);
            ctx = (Context) thread.getClass().getMethod("getSystemContext").invoke(thread);
            init();
            // A: RAW10 50MP with sensorPixelMode=1 (MAXIMUM_RESOLUTION / remosaic)
            attempt("A: raw10-50mp spm1", "0", P_HIGHRES,
                new int[][]{{37, 8160, 6120, 2, 1}}, 5, 40, 2, CameraDevice.TEMPLATE_PREVIEW);
            // B: RAW10 50MP spm1 + a preview YUV 4080x3060 (pipeline may need a realtime leg)
            attempt("B: raw10-50mp spm1 + preview", "0", P_HIGHRES,
                new int[][]{{37, 8160, 6120, 2, 1}, {35, 1920, 1440, 1, 0}}, 5, 40, 2, CameraDevice.TEMPLATE_PREVIEW);
            // C: RAW16 (fmt 32? actually RAW_SENSOR=32) 50MP spm1
            attempt("C: raw16-50mp spm1", "0", P_HIGHRES,
                new int[][]{{32, 8160, 6120, 2, 1}}, 5, 40, 2, CameraDevice.TEMPLATE_PREVIEW);
        } catch (Throwable t) {
            t.printStackTrace();
        }
        System.exit(0);
    }
}