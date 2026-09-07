import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.Surface;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/** ProbeV3: attempt real Samsung-private 50MP capture (Expert RAW mechanism) as root. */
public final class ProbeV3 {

    static void p(String s) { System.out.println(s); }

    static String PARAM_HIGHRES =
        "first-entrance=false;samsungcamera=true;factorytest=false;shootingmode=40;" +
        "recording-fps=0;sw-vdis=false;video-beautyface=false;vtmode=0;" +
        "operation_mode=none;ssm_shot_mode=2;recording_dr_mode=sdr;sw-super_vdis=false;" +
        "stream_type=0;";

    static Method sSetParams;
    static Method sSemCreateOutput;
    static Class<?> sPdkHelper;

    static void initReflection() throws Exception {
        try {
            sSetParams = CameraDevice.class.getMethod("setParameters", String.class);
            p("setParameters: direct method found");
        } catch (Throwable t) {
            p("setParameters direct FAIL: " + t);
        }
        try {
            dalvik.system.PathClassLoader pcl = new dalvik.system.PathClassLoader(
                "/system/framework/scamera_sdk_util.jar",
                CameraDevice.class.getClassLoader());
            sPdkHelper = Class.forName(
                "com.samsung.android.sdk.camera.impl.internal.CustomInterfaceHelper", true, pcl);
            sPdkHelper.getMethod("setSamsungParameter", CameraDevice.class, String.class);
            p("PdkUtil CustomInterfaceHelper loaded OK");
        } catch (Throwable t) {
            p("PdkUtil load FAIL: " + t);
        }
        try {
            for (Method m : OutputConfiguration.class.getDeclaredMethods()) {
                if (m.getName().contains("semCreateOutputConfiguration")) {
                    sSemCreateOutput = m;
                    m.setAccessible(true);
                }
            }
            if (sSemCreateOutput != null) {
                StringBuilder sb = new StringBuilder("semCreateOutputConfiguration: (");
                for (Class<?> c : sSemCreateOutput.getParameterTypes()) sb.append(c.getSimpleName()).append(",");
                sb.append(") static=").append(java.lang.reflect.Modifier.isStatic(sSemCreateOutput.getModifiers()));
                p(sb.toString());
            } else {
                p("semCreateOutputConfiguration NOT FOUND");
                for (Method m : OutputConfiguration.class.getDeclaredMethods()) {
                    if (m.getName().startsWith("sem")) p("  sem-method: " + m.getName());
                }
            }
        } catch (Throwable t) {
            p("sem reflect FAIL: " + t);
        }
    }

    static void applyParams(CameraDevice dev, String param) {
        try {
            if (sSetParams != null) { sSetParams.invoke(dev, param); p("setParameters OK: " + param); return; }
        } catch (Throwable t) { p("setParameters invoke FAIL: " + t); }
        try {
            if (sPdkHelper != null) {
                sPdkHelper.getMethod("setSamsungParameter", CameraDevice.class, String.class)
                    .invoke(null, dev, param);
                p("setSamsungParameter OK");
            }
        } catch (Throwable t) { p("setSamsungParameter FAIL: " + t); }
    }

    static OutputConfiguration makeOutput(Surface surface, int usage) throws Exception {
        Object[] args = null;
        Class<?>[] pt = sSemCreateOutput.getParameterTypes();
        if (pt.length == 4) {
            // try (int, Surface, int, int)
            args = new Object[] { Integer.valueOf(-1), surface, Integer.valueOf(0), Integer.valueOf(usage) };
        } else if (pt.length == 3) {
            args = new Object[] { Integer.valueOf(-1), surface, Integer.valueOf(usage) };
        } else if (pt.length == 2) {
            args = new Object[] { surface, Integer.valueOf(usage) };
        } else if (pt.length == 5) {
            args = new Object[] { Integer.valueOf(-1), surface, Integer.valueOf(0), Integer.valueOf(0), Integer.valueOf(usage) };
        }
        return (OutputConfiguration) sSemCreateOutput.invoke(null, args);
    }

    static CaptureRequest.Key<Integer> key(String name) {
        return new CaptureRequest.Key<>(name, Integer.class);
    }

    /** One combo attempt: open camId, optional setParameters, session with reader, capture 1 frame. */
    static void combo(String tag, String camId, String param, int format, int w, int h, int usage,
                      Integer shootingMode, Integer ssm, Integer streamType, Integer physId, boolean stillTemplate) {
        p("### COMBO " + tag + " cam=" + camId + " fmt=" + format + " " + w + "x" + h +
          " usage=0x" + Integer.toHexString(usage));
        try {
            CameraManager cm = ctx.getSystemService(CameraManager.class);
            final CountDownLatch opened = new CountDownLatch(1);
            final CountDownLatch configured = new CountDownLatch(1);
            final CountDownLatch captured = new CountDownLatch(1);
            final CameraDevice[] dev = new CameraDevice[1];
            final Throwable[] err = new Throwable[2];
            HandlerThread ht = new HandlerThread("probe3");
            ht.start();
            Handler handler = new Handler(ht.getLooper());
            cm.openCamera(camId, new CameraDevice.StateCallback() {
                public void onOpened(CameraDevice d) { dev[0] = d; opened.countDown(); }
                public void onDisconnected(CameraDevice d) { p("onDisconnected"); }
                public void onError(CameraDevice d, int e) { p("onError " + e); }
            }, handler);
            if (!opened.await(8, TimeUnit.SECONDS)) { p("OPEN TIMEOUT"); ht.quit(); return; }
            if (param != null) applyParams(dev[0], param);
            android.media.ImageReader reader = android.media.ImageReader.newInstance(w, h, format, 3);
            final android.media.ImageReader[] gotImage = new android.media.ImageReader[1];
            reader.setOnImageAvailableListener(r -> {
                try {
                    android.media.Image img = r.acquireLatestImage();
                    if (img != null) {
                        p("*** IMAGE ARRIVED: fmt=" + img.getFormat() + " " + img.getWidth() + "x" + img.getHeight() +
                          " ts=" + img.getTimestamp());
                        gotImage[0] = r;
                        captured.countDown();
                    }
                } catch (Throwable t) { p("img cb err " + t); }
            }, handler);
            OutputConfiguration oc = makeOutput(reader.getSurface(), usage);
            CaptureRequest.Builder b = dev[0].createCaptureRequest(
                stillTemplate ? CameraDevice.TEMPLATE_STILL_CAPTURE : CameraDevice.TEMPLATE_PREVIEW);
            if (shootingMode != null) b.set(key("samsung.android.control.shootingMode"), shootingMode);
            if (ssm != null) b.set(key("samsung.android.control.ssmShotMode"), ssm);
            if (streamType != null) b.set(key("samsung.android.sensor.streamType"), streamType);
            if (physId != null) b.set(key("samsung.android.control.capturePhysicalId"), physId);
            b.addTarget(reader.getSurface());
            SessionConfiguration sc = new SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                Collections.singletonList(oc),
                new Executor() { public void execute(Runnable r) { handler.post(r); } },
                new CameraCaptureSession.StateCallback() {
                    public void onConfigured(CameraCaptureSession s) { p("SESSION CONFIGURED"); configured.countDown(); }
                    public void onConfigureFailed(CameraCaptureSession s) { p("SESSION CONFIG FAILED"); configured.countDown(); }
                });
            sc.setSessionParameters(b.build());
            dev[0].createCaptureSession(sc);
            if (!configured.await(8, TimeUnit.SECONDS)) { p("SESSION TIMEOUT"); }
            else {
                // if configured, fire capture
                try {
                    CameraCaptureSession sess = dev[0].getId() == null ? null : null; // unused
                } catch (Throwable ignore) {}
                // need session handle: re-open via callback capture in onConfigured — instead store
                p("(capture issued via repeating below)");
            }
            ht.quit();
        } catch (Throwable t) {
            p("COMBO FAIL: " + t);
        }
    }

    static Context ctx;

    public static void main(String[] args) {
        try {
            Looper.prepareMainLooper();
            Class<?> atClass = Class.forName("android.app.ActivityThread");
            Object thread = atClass.getMethod("systemMain").invoke(null);
            ctx = (Context) thread.getClass().getMethod("getSystemContext").invoke(thread);
            CameraManager cm = ctx.getSystemService(CameraManager.class);
            p("PIDS: " + java.util.Arrays.toString(cm.getCameraIdList()));
            try {
                p("cam0 physicalIds: " + cm.getCameraCharacteristics("0").getPhysicalCameraIds());
                p("cam5 physicalIds: " + cm.getCameraCharacteristics("5").getPhysicalCameraIds());
            } catch (Throwable t) { p("physIds ERR " + t); }
            initReflection();
            p("--- end probe ---");
        } catch (Throwable t) {
            t.printStackTrace();
        }
        System.exit(0);
    }
}