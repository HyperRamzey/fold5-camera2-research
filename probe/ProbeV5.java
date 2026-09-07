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

/** ProbeV5: refine 50MP attempt on cam0 — single capture vs repeating, template variants, usage variants. */
public final class ProbeV5 {

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

    /** returns true if image arrived */
    static boolean attempt(String camId, String param, int format, int w, int h, int usage,
                           Integer shootMode, Integer ssm, Integer streamType, Integer physId,
                           int template, boolean repeating) {
        String tag = "cam" + camId + (param != null ? "+P" : "") + " fmt" + format + " " + w + "x" + h +
                     " u=0x" + Integer.toHexString(usage) + " tpl" + template + (repeating ? " rep" : " single")
                     + (physId != null ? " phys" + physId : "");
        p("### ATTEMPT " + tag);
        HandlerThread ht = new HandlerThread("pv5");
        ht.start();
        Handler handler = new Handler(ht.getLooper());
        boolean got = false;
        try {
            CameraManager cm = ctx.getSystemService(CameraManager.class);
            final CountDownLatch open = new CountDownLatch(1);
            final CountDownLatch cfg = new CountDownLatch(1);
            final CountDownLatch img = new CountDownLatch(1);
            final CameraDevice[] dev = new CameraDevice[1];
            final CameraCaptureSession[] sess = new CameraCaptureSession[1];
            final boolean[] devErr = new boolean[1];
            cm.openCamera(camId, new CameraDevice.StateCallback() {
                public void onOpened(CameraDevice d) { dev[0] = d; open.countDown(); }
                public void onDisconnected(CameraDevice d) { p("dev disconnected"); }
                public void onError(CameraDevice d, int e) { p("dev onError " + e); devErr[0] = true; }
            }, handler);
            if (!open.await(8, TimeUnit.SECONDS)) { p("OPEN TIMEOUT"); return false; }
            if (param != null) {
                try { sSetParams.invoke(dev[0], param); p("setParameters OK"); }
                catch (Throwable t) { p("setParameters FAIL: " + t); return false; }
            }
            android.media.ImageReader reader = android.media.ImageReader.newInstance(w, h, format, 3);
            reader.setOnImageAvailableListener(r -> {
                try {
                    android.media.Image i = r.acquireLatestImage();
                    if (i != null) {
                        p("*** IMAGE: fmt=" + i.getFormat() + " " + i.getWidth() + "x" + i.getHeight() + " ts=" + i.getTimestamp());
                        img.countDown();
                    }
                } catch (Throwable t) { p("imgcb " + t); }
            }, handler);
            OutputConfiguration oc = (OutputConfiguration) sSemCreate.invoke(null,
                Integer.valueOf(-1), reader.getSurface(), Integer.valueOf(0), Integer.valueOf(usage));
            if (physId != null) oc.setPhysicalCameraId(String.valueOf(physId));
            CaptureRequest.Builder b = dev[0].createCaptureRequest(template);
            if (shootMode != null) b.set(new CaptureRequest.Key<Integer>("samsung.android.control.shootingMode", Integer.class), shootMode);
            if (ssm != null) b.set(new CaptureRequest.Key<Integer>("samsung.android.control.ssmShotMode", Integer.class), ssm);
            if (streamType != null) b.set(new CaptureRequest.Key<Integer>("samsung.android.sensor.streamType", Integer.class), streamType);
            if (physId != null) b.set(new CaptureRequest.Key<Integer>("samsung.android.control.capturePhysicalId", Integer.class), physId);
            b.addTarget(reader.getSurface());
            SessionConfiguration sc = new SessionConfiguration(SessionConfiguration.SESSION_REGULAR,
                Collections.singletonList(oc),
                new Executor() { public void execute(Runnable r) { handler.post(r); } },
                new CameraCaptureSession.StateCallback() {
                    public void onConfigured(CameraCaptureSession s) { sess[0] = s; p("SESSION CONFIGURED"); cfg.countDown(); }
                    public void onConfigureFailed(CameraCaptureSession s) { p("SESSION CONFIG **FAILED**"); cfg.countDown(); }
                });
            sc.setSessionParameters(b.build());
            dev[0].createCaptureSession(sc);
            if (!cfg.await(10, TimeUnit.SECONDS)) { p("CFG TIMEOUT"); return false; }
            if (sess[0] != null) {
                if (repeating) {
                    sess[0].setRepeatingRequest(b.build(), null, handler);
                } else {
                    sess[0].capture(b.build(), null, handler);
                }
                if (img.await(6, TimeUnit.SECONDS)) {
                    p("RESULT: SUCCESS <<< " + tag);
                    got = true;
                } else {
                    p("RESULT: no image" + (devErr[0] ? " (dev error)" : ""));
                }
                sess[0].close();
            }
        } catch (Throwable t) {
            p("ATTEMPT FAIL: " + t);
        } finally {
            ht.quit();
        }
        p("---");
        return got;
    }

    public static void main(String[] args) {
        try {
            Looper.prepareMainLooper();
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object thread = at.getMethod("systemMain").invoke(null);
            ctx = (Context) thread.getClass().getMethod("getSystemContext").invoke(thread);
            init();
            Integer sm = 40, ssm = 2, st = 0, phys = 5;
            // single-shot still template (Expert RAW uses TEMPLATE_STILL? it creates builders from templates 1=STILL 2=RECORD; picture builder=2? we saw 1 preview, 2 record... MakerBase uses template 1 for preview and 2 for picture -> those are PREVIEW and STILL? verify by testing)
            attempt("0", P_HIGHRES, 37, 8160, 6120, 2, sm, ssm, st, phys, CameraDevice.TEMPLATE_STILL_CAPTURE, false);
            // usage 2 with preview template but single capture
            attempt("0", P_HIGHRES, 37, 8160, 6120, 2, sm, ssm, st, phys, CameraDevice.TEMPLATE_PREVIEW, false);
            // usage 4 (thumbnail)? no -- try usage 1 (preview)
            attempt("0", P_HIGHRES, 37, 8160, 6120, 1, sm, ssm, st, phys, CameraDevice.TEMPLATE_PREVIEW, true);
            // no capturePhysicalId (logical stream)
            attempt("0", P_HIGHRES, 37, 8160, 6120, 2, sm, ssm, st, null, CameraDevice.TEMPLATE_PREVIEW, true);
            // YUV 50MP
            attempt("0", P_HIGHRES, 35, 8160,6120, 2, sm, ssm, st, phys, CameraDevice.TEMPLATE_PREVIEW, true);
        } catch (Throwable t) {
            t.printStackTrace();
        }
        System.exit(0);
    }
}