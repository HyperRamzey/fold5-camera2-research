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

/** ProbeV4: real 50MP Samsung-private capture attempt on cam 5/56, RAW10 8160x6120. */
public final class ProbeV4 {

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

    static void attempt(String camId, String param, int format, int w, int h, int usage,
                        Integer shootMode, Integer ssm, Integer streamType, Integer physId) {
        String tag = "cam" + camId + (param != null ? "+param" : "") + " fmt" + format + " " + w + "x" + h +
                     " usage=0x" + Integer.toHexString(usage) + (physId != null ? " phys" + physId : "");
        p("### ATTEMPT " + tag);
        HandlerThread ht = new HandlerThread("pv4");
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
                catch (Throwable t) { p("setParameters FAIL: " + t); }
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
            CaptureRequest.Builder b = dev[0].createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            if (shootMode != null) try { b.set(new CaptureRequest.Key<Integer>("samsung.android.control.shootingMode", Integer.class), shootMode); } catch (Throwable t) { p("shootMode key ERR " + t); }
            if (ssm != null) try { b.set(new CaptureRequest.Key<Integer>("samsung.android.control.ssmShotMode", Integer.class), ssm); } catch (Throwable t) { p("ssm key ERR " + t); }
            if (streamType != null) try { b.set(new CaptureRequest.Key<Integer>("samsung.android.sensor.streamType", Integer.class), streamType); } catch (Throwable t) { p("streamType key ERR " + t); }
            if (physId != null) try { b.set(new CaptureRequest.Key<Integer>("samsung.android.control.capturePhysicalId", Integer.class), physId); } catch (Throwable t) { p("physId key ERR " + t); }
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
            if (!cfg.await(10, TimeUnit.SECONDS)) { p("CFG TIMEOUT"); return; }
            if (sess[0] != null) {
                p("firing repeating");
                sess[0].setRepeatingRequest(b.build(), null, handler);
                if (img.await(6, TimeUnit.SECONDS)) {
                    p("RESULT: SUCCESS <<< " + tag);
                } else {
                    p("RESULT: no image in 6s");
                }
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
            // 1) cam5 direct: ExpertRAW highres RAW10 stream (deviceId 56, 8160x6120, fmt 37=RAW10), usage 2 (still-capture pic stream)
            attempt("5", P_HIGHRES, 37, 8160, 6120, 2, 40, 2, 0, null);
            // 2) cam5 same without setParameters (isolate param dependency)
            attempt("5", null, 37, 8160, 6120, 2, 40, 2, 0, null);
            // 3) cam5 YUV 8160x6120 (private picture table lists YUV 33 at 8160x6120)
            attempt("5", P_HIGHRES, 35, 8160, 6120, 2, 40, 2, 0, null);
            // 4) cam0 logical with physical 56? physicals are 2/5/6 -> use 5 as physical + highres tables from 5
            attempt("0", P_HIGHRES, 37, 8160, 6120, 2, 40, 2, 0, 5);
        } catch (Throwable t) {
            t.printStackTrace();
        }
        System.exit(0);
    }
}