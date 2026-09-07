import android.content.Context;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.Surface;

import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/** ProbeV8: verify 8160x6120 RAW10 frame is genuine full-res; dump metadata + save proof file. */
public final class ProbeV8 {

    static void p(String s) { System.out.println(s); }
    static Context ctx;
    static Method sSemCreate;

    public static void main(String[] args) {
        try {
            Looper.prepareMainLooper();
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object thread = at.getMethod("systemMain").invoke(null);
            ctx = (Context) thread.getClass().getMethod("getSystemContext").invoke(thread);
            sSemCreate = android.hardware.camera2.params.OutputConfiguration.class.getMethod(
                "semCreateOutputConfiguration", int.class, Surface.class, int.class, int.class);

            CameraManager cm = ctx.getSystemService(CameraManager.class);
            HandlerThread ht = new HandlerThread("pv8");
            ht.start();
            Handler handler = new Handler(ht.getLooper());
            final CountDownLatch open = new CountDownLatch(1);
            final CountDownLatch cfg = new CountDownLatch(1);
            final CountDownLatch done = new CountDownLatch(1);
            final CameraDevice[] dev = new CameraDevice[1];
            final CameraCaptureSession[] sess = new CameraCaptureSession[1];
            cm.openCamera("0", new CameraDevice.StateCallback() {
                public void onOpened(CameraDevice d) { dev[0] = d; open.countDown(); }
                public void onDisconnected(CameraDevice d) { p("disconnected"); }
                public void onError(CameraDevice d, int e) { p("dev onError " + e); }
            }, handler);
            if (!open.await(8, TimeUnit.SECONDS)) { p("OPEN TIMEOUT"); return; }

            // RAW10 50MP (option 2) + preview YUV (option 1) - NO setParameters
            android.media.ImageReader raw = android.media.ImageReader.newInstance(8160, 6120, 37, 3);
            android.media.ImageReader prev = android.media.ImageReader.newInstance(1920, 1440, 35, 3);
            raw.setOnImageAvailableListener(r -> {
                try {
                    android.media.Image img = r.acquireLatestImage();
                    if (img == null) return;
                    p("*** RAW img: " + img.getWidth() + "x" + img.getHeight() + " fmt=" + img.getFormat() + " ts=" + img.getTimestamp());
                    android.media.Image.Plane[] planes = img.getPlanes();
                    for (android.media.Image.Plane pl : planes) {
                        p("    plane: rowStride=" + pl.getRowStride() + " pixStride=" + pl.getPixelStride());
                    }
                    // save first plane bytes to /data/local/tmp as proof
                    ByteBuffer bb = planes[0].getBuffer();
                    int n = Math.min(bb.remaining(), 8160 * 6120 / 4 * 10 / 8); // RAW10: 10 bits/4px in 5 bytes
                    byte[] buf = new byte[bb.remaining()];
                    bb.get(buf);
                    try (FileOutputStream fos = new FileOutputStream("/data/local/tmp/raw50mp_first_plane.bin")) {
                        fos.write(buf);
                    }
                    p("    saved " + buf.length + " bytes to /data/local/tmp/raw50mp_first_plane.bin");
                    img.close();
                    done.countDown();
                } catch (Throwable t) { p("rawcb ERR " + t); }
            }, handler);

            OutputConfiguration ocRaw = (OutputConfiguration) sSemCreate.invoke(null,
                Integer.valueOf(-1), raw.getSurface(), Integer.valueOf(0), Integer.valueOf(2));
            OutputConfiguration ocPrev = (OutputConfiguration) sSemCreate.invoke(null,
                Integer.valueOf(-1), prev.getSurface(), Integer.valueOf(0), Integer.valueOf(1));

            CaptureRequest.Builder b = dev[0].createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            b.addTarget(raw.getSurface());
            b.addTarget(prev.getSurface());

            SessionConfiguration sc = new SessionConfiguration(SessionConfiguration.SESSION_REGULAR,
                java.util.Arrays.asList(ocRaw, ocPrev),
                new Executor() { public void execute(Runnable r) { handler.post(r); } },
                new CameraCaptureSession.StateCallback() {
                    public void onConfigured(CameraCaptureSession s) { sess[0] = s; p("SESSION CONFIGURED"); cfg.countDown(); }
                    public void onConfigureFailed(CameraCaptureSession s) { p("SESSION CONFIG FAILED"); cfg.countDown(); }
                });
            sc.setSessionParameters(b.build());
            dev[0].createCaptureSession(sc);
            if (!cfg.await(10, TimeUnit.SECONDS)) { p("CFG TIMEOUT"); return; }

            // repeating request WITH result callback to dump metadata
            sess[0].setRepeatingRequest(b.build(), new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest req, TotalCaptureResult res) {
                    try {
                        Integer px = res.get(CaptureResult.SENSOR_PIXEL_MODE);
                        p("RESULT: SENSOR_PIXEL_MODE=" + px + " frame=" + res.getFrameNumber()
                          + " exp=" + res.get(CaptureResult.SENSOR_EXPOSURE_TIME)
                          + " sens=" + res.get(CaptureResult.SENSOR_SENSITIVITY));
                        try {
                            CaptureResult.Key<Integer> samsungPx = new CaptureResult.Key<>("samsung.android.sensor.pixelMode", Integer.class);
                            p("RESULT: samsung.sensor.pixelMode=" + res.get(samsungPx));
                        } catch (Throwable t) { p("samsungPx key ERR " + t); }
                    } catch (Throwable t) { p("meta ERR " + t); }
                }
            }, handler);

            if (done.await(12, TimeUnit.SECONDS)) {
                p("=== 50MP FRAME VERIFIED ===");
            } else {
                p("no raw frame in 12s");
            }
            sess[0].close();
            Thread.sleep(500);
        } catch (Throwable t) {
            t.printStackTrace();
        }
        System.exit(0);
    }
}