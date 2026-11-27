package com.agc;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.OisSample;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.media.Image;
import android.media.ImageReader;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * v7.0 zero-copy 50MP RAW10 dumper with OIS/IMU sidecar.
 * Runs on the cam-56 CameraDevice that GCam already opened (stored by the
 * LEraw50.wrapOpenCb hook). One 6-request RAW10 burst on the Samsung
 * HighResolution RDI stream; each Image.Plane[0] ByteBuffer is written
 * straight to /sdcard and closed immediately.
 */
public final class Eraw70 {
    static final String TAG = "LEraw70";
    public static final int W = 8160;
    public static final int H = 6120;
    public static final int N = 6;
    public static final String CAM_ID = "56";

    static final String P_HIGHRES =
            "first-entrance=false;samsungcamera=true;factorytest=false;shootingmode=40;" +
            "recording-fps=0;sw-vdis=false;video-beautyface=false;vtmode=0;" +
            "operation_mode=none;ssm_shot_mode=2;recording_dr_mode=sdr;" +
            "sw-super_vdis=false;stream_type=0;";

    public static boolean capture(Context context) {
        if (legacyRequested(context)) {
            Log.i(TAG, "pref_50mp_raw_key=0 - falling back to v4.1 merge");
            try {
                Class<?> c = Class.forName("com.agc.Eraw50Burst");
                Object r = c.getMethod("capture", Context.class).invoke(null, context);
                return (r instanceof Boolean) && ((Boolean) r).booleanValue();
            } catch (Throwable t) {
                Log.e(TAG, "legacy fallback EX " + Log.getStackTraceString(t));
                return false;
            }
        }
        Runner runner = new Runner(context);
        try {
            return runner.run();
        } catch (Throwable t) {
            Log.e(TAG, "v7 capture EX " + Log.getStackTraceString(t));
            runner.error = String.valueOf(t);
            try {
                Eraw70Meta.write(runner, runner.imu, runner.ois);
            } catch (Throwable ignored) {
            }
            runner.cleanup();
            return false;
        }
    }

    static boolean legacyRequested(Context ctx) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences(
                    "com.samsung.android.ruler_preferences", Context.MODE_PRIVATE);
            return sp.getInt("pref_50mp_raw_key", 1) == 0;
        } catch (Throwable t) {
            return false;
        }
    }

    private static final AtomicBoolean AUTO_ARMED = new AtomicBoolean();

    /**
     * Auto-capture entry point, called from the cam-56 open callback. The AGC
     * UI task set on this Android 17 build can abort the main thread before the
     * 50MP button is ever reachable, so the burst runs on its own worker thread
     * as soon as the device exists. CrashHandler no longer kills the process on
     * an uncaught main-thread exception, so the worker keeps running while the
     * UI initializers fail around it.
     */
    public static void auto(final Context context) {
        if (context == null) {
            Log.w(TAG, "auto: no context");
            return;
        }
        if (!AUTO_ARMED.compareAndSet(false, true)) return;
        try {
            SharedPreferences sp = context.getSharedPreferences(
                    "com.samsung.android.ruler_preferences", Context.MODE_PRIVATE);
            if (sp.getInt("pref_50mp_auto_key", 1) == 0) {
                Log.i(TAG, "auto: disabled by pref_50mp_auto_key");
                return;
            }
        } catch (Throwable ignored) {
        }
        Log.i(TAG, "auto: armed");
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(400);
                } catch (InterruptedException ignored) {
                }
                Log.i(TAG, "auto: burst start");
                boolean ok = capture(context);
                Log.i(TAG, "auto: burst done ok=" + ok);
            }
        }, "eraw70auto");
        t.start();
    }

    static final class Runner {
        final Context ctx;
        final long epoch = System.currentTimeMillis();
        long tStart;
        long tSessionReady;
        long tFirstImage;
        long tLastImage;
        long tBurstSubmit;
        long tMeta;
        File dir;
        CameraDevice device;
        CameraCaptureSession session;
        HandlerThread camThread;
        Handler camHandler;
        ImageReader rawReader;
        ImageReader prevReader;
        CameraCharacteristics chars;
        Eraw70Imu imu;
        Eraw70Ois ois;
        final ArrayList<Eraw70Meta.FrameRec> frames = new ArrayList<Eraw70Meta.FrameRec>();
        final ArrayList<TotalCaptureResult> results = new ArrayList<TotalCaptureResult>();
        final ArrayList<OisSample> previewOis = new ArrayList<OisSample>();
        final AtomicInteger imagesWritten = new AtomicInteger();
        final AtomicInteger previews = new AtomicInteger();
        final AtomicInteger stillFails = new AtomicInteger();
        volatile Long previewSkew;
        volatile boolean stdAttempted;
        volatile boolean stdAccepted;
        volatile int settlePreviews;
        volatile String error;
        final CountDownLatch sessionLatch = new CountDownLatch(1);

        final Executor executor = new Executor() {
            @Override
            public void execute(Runnable r) {
                camHandler.post(r);
            }
        };

        final CameraCaptureSession.StateCallback sessionCb = new CameraCaptureSession.StateCallback() {
            @Override
            public void onConfigured(CameraCaptureSession s) {
                session = s;
                Log.i(TAG, "session configured");
                sessionLatch.countDown();
            }

            @Override
            public void onConfigureFailed(CameraCaptureSession s) {
                Log.e(TAG, "session CONFIG FAILED");
                sessionLatch.countDown();
            }
        };

        final CameraCaptureSession.CaptureCallback previewCb = new CameraCaptureSession.CaptureCallback() {
            @Override
            public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest req, TotalCaptureResult res) {
                previews.incrementAndGet();
                try {
                    OisSample[] os = (OisSample[]) res.get(CaptureResult.STATISTICS_OIS_SAMPLES);
                    if (os != null && os.length > 0) {
                        synchronized (previewOis) {
                            for (int i = 0; i < os.length; i++) previewOis.add(os[i]);
                        }
                    }
                    Long sk = (Long) res.get(CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW);
                    if (sk != null) previewSkew = sk;
                } catch (Throwable ignored) {
                }
            }
        };

        final CameraCaptureSession.CaptureCallback stillCb = new CameraCaptureSession.CaptureCallback() {
            @Override
            public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest req, TotalCaptureResult res) {
                synchronized (results) {
                    results.add(res);
                }
                Log.i(TAG, "still done frame=" + res.getFrameNumber());
            }

            @Override
            public void onCaptureFailed(CameraCaptureSession s, CaptureRequest req, CaptureFailure f) {
                stillFails.incrementAndGet();
                Log.e(TAG, "still FAILED r=" + f.getReason() + " frame=" + f.getFrameNumber());
            }

            @Override
            public void onCaptureSequenceAborted(CameraCaptureSession s, int seq) {
                stillFails.addAndGet(N);
                Log.e(TAG, "still sequence aborted seq=" + seq);
            }
        };

        final ImageReader.OnImageAvailableListener prevListener = new ImageReader.OnImageAvailableListener() {
            @Override
            public void onImageAvailable(ImageReader reader) {
                Image img = null;
                try {
                    img = reader.acquireLatestImage();
                } catch (Throwable ignored) {
                } finally {
                    if (img != null) {
                        try {
                            img.close();
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
        };

        final ImageReader.OnImageAvailableListener rawListener = new ImageReader.OnImageAvailableListener() {
            @Override
            public void onImageAvailable(ImageReader reader) {
                Image img = null;
                try {
                    img = reader.acquireNextImage();
                    if (img == null) return;
                    int idx = imagesWritten.getAndIncrement();
                    Eraw70Meta.FrameRec fr = new Eraw70Meta.FrameRec();
                    fr.index = idx;
                    fr.width = img.getWidth();
                    fr.height = img.getHeight();
                    fr.ts = img.getTimestamp();
                    Image.Plane plane = img.getPlanes()[0];
                    fr.rowStride = plane.getRowStride();
                    fr.pixelStride = plane.getPixelStride();
                    ByteBuffer buf = plane.getBuffer();
                    buf.position(0);
                    fr.bytes = buf.remaining();
                    File out = new File(dir, "frame_" + idx + ".raw10");
                    long w0 = SystemClock.elapsedRealtimeNanos();
                    FileOutputStream fos = new FileOutputStream(out);
                    FileChannel ch = fos.getChannel();
                    try {
                        while (buf.hasRemaining()) ch.write(buf);
                    } finally {
                        try {
                            ch.close();
                        } finally {
                            fos.close();
                        }
                    }
                    fr.file = out.getName();
                    fr.writeMs = (SystemClock.elapsedRealtimeNanos() - w0) / 1000000.0;
                    fr.arrivalMs = (SystemClock.elapsedRealtimeNanos() - tStart) / 1000000.0;
                    synchronized (frames) {
                        frames.add(fr);
                    }
                    if (idx == 0) tFirstImage = SystemClock.elapsedRealtimeNanos();
                    tLastImage = SystemClock.elapsedRealtimeNanos();
                    Log.i(TAG, "frame " + idx + " " + fr.bytes + "B stride=" + fr.rowStride
                            + " pxstride=" + fr.pixelStride + " write=" + fr.writeMs
                            + "ms arrival=" + fr.arrivalMs + "ms ts=" + fr.ts);
                } catch (Throwable t) {
                    error = "raw cb: " + t;
                    Log.e(TAG, error, t);
                } finally {
                    if (img != null) {
                        try {
                            img.close();
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
        };

        Runner(Context c) {
            ctx = c.getApplicationContext();
        }

        boolean run() {
            tStart = SystemClock.elapsedRealtimeNanos();
            dir = new File(Environment.getExternalStorageDirectory(),
                    "DCIM/AGC_50MP_RAW/burst_" + epoch);
            if (!dir.isDirectory() && !dir.mkdirs()) {
                error = "mkdir failed: " + dir;
                Log.e(TAG, error);
                return false;
            }
            Log.i(TAG, "v7 burst dir " + dir);
            camThread = new HandlerThread("eraw70");
            camThread.start();
            camHandler = new Handler(camThread.getLooper());
            imu = new Eraw70Imu(ctx);
            imu.start();
            ois = new Eraw70Ois();
            ois.start();
            try {
                CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
                chars = cm.getCameraCharacteristics(CAM_ID);
            } catch (Throwable t) {
                Log.w(TAG, "chars EX " + t);
            }
            try {
                device = (CameraDevice) Class.forName("com.agc.LEraw50")
                        .getMethod("getDevice").invoke(null);
            } catch (Throwable t) {
                Log.e(TAG, "getDevice EX " + t);
            }
            if (device == null) {
                error = "no stored cam-56 device";
                Log.e(TAG, error);
                cleanup();
                return false;
            }
            Method setParams = null;
            try {
                Class<?> b = Class.forName("com.agc.Eraw50Bridge");
                setParams = (Method) b.getMethod("getSetParamsMethod").invoke(null);
            } catch (Throwable t) {
                Log.e(TAG, "bridge EX " + t);
            }
            if (setParams == null) {
                error = "no scamera bridge";
                cleanup();
                return false;
            }
            try {
                setParams.invoke(null, device, P_HIGHRES);
                Log.i(TAG, "params ok");
            } catch (Throwable t) {
                error = "setParams: " + t;
                Log.e(TAG, error, t);
                cleanup();
                return false;
            }
            try {
                rawReader = ImageReader.newInstance(W, H, ImageFormat.RAW10, N);
                prevReader = ImageReader.newInstance(1280, 960, ImageFormat.YUV_420_888, N);
                rawReader.setOnImageAvailableListener(rawListener, camHandler);
                prevReader.setOnImageAvailableListener(prevListener, camHandler);
                Method sem = OutputConfiguration.class.getMethod(
                        "semCreateOutputConfiguration",
                        Integer.TYPE, Surface.class, Integer.TYPE, Integer.TYPE);
                OutputConfiguration rawCfg = (OutputConfiguration) sem.invoke(
                        null, Integer.valueOf(-1), rawReader.getSurface(),
                        Integer.valueOf(0), Integer.valueOf(2));
                OutputConfiguration prevCfg = (OutputConfiguration) sem.invoke(
                        null, Integer.valueOf(-1), prevReader.getSurface(),
                        Integer.valueOf(0), Integer.valueOf(1));
                CaptureRequest.Builder lead = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                lead.addTarget(prevReader.getSurface());
                applyKeys(lead);
                SessionConfiguration sc = new SessionConfiguration(
                        SessionConfiguration.SESSION_REGULAR,
                        Arrays.asList(rawCfg, prevCfg), executor, sessionCb);
                sc.setSessionParameters(lead.build());
                device.createCaptureSession(sc);
            } catch (Throwable t) {
                error = "session setup: " + t;
                Log.e(TAG, error, t);
                cleanup();
                return false;
            }
            try {
                if (!sessionLatch.await(12, TimeUnit.SECONDS) || session == null) {
                    error = "session configure timeout/fail";
                    Log.e(TAG, error);
                    cleanup();
                    return false;
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                cleanup();
                return false;
            }
            tSessionReady = SystemClock.elapsedRealtimeNanos();
            try {
                CaptureRequest.Builder rep = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                rep.addTarget(prevReader.getSurface());
                applyKeys(rep);
                session.setRepeatingRequest(rep.build(), previewCb, camHandler);
                long settleEnd = SystemClock.elapsedRealtime() + 500;
                while (previews.get() < 2 && SystemClock.elapsedRealtime() < settleEnd) {
                    Thread.sleep(10);
                }
                settlePreviews = previews.get();
                Log.i(TAG, "previews settled " + settlePreviews + " in "
                        + ((SystemClock.elapsedRealtimeNanos() - tSessionReady) / 1000000L) + "ms");
                ArrayList<CaptureRequest> burst = new ArrayList<CaptureRequest>();
                for (int i = 0; i < N; i++) {
                    CaptureRequest.Builder b = device.createCaptureRequest(
                            CameraDevice.TEMPLATE_STILL_CAPTURE);
                    b.addTarget(rawReader.getSurface());
                    applyKeys(b);
                    burst.add(b.build());
                }
                session.captureBurst(burst, stillCb, camHandler);
                tBurstSubmit = SystemClock.elapsedRealtimeNanos();
                Log.i(TAG, "burst submitted (" + N + " requests)");
            } catch (Throwable t) {
                error = "burst submit: " + t;
                Log.e(TAG, error, t);
                cleanup();
                return false;
            }

            long deadline = SystemClock.elapsedRealtime() + 12000;
            long imagesDoneAt = 0;
            while (SystemClock.elapsedRealtime() < deadline) {
                int imgs = imagesWritten.get();
                int done = results.size() + stillFails.get();
                if (imgs >= N && done >= N) break;
                if (imgs >= N && imagesDoneAt == 0) imagesDoneAt = SystemClock.elapsedRealtime();
                if (imagesDoneAt != 0 && SystemClock.elapsedRealtime() - imagesDoneAt > 2000) break;
                if (done >= N && imgs < N) break;
                try {
                    Thread.sleep(10);
                } catch (InterruptedException ie) {
                    break;
                }
            }
            ois.refreshAux();
            imu.stop();
            ois.stop();
            int got = imagesWritten.get();
            int res = results.size();
            Log.i(TAG, "burst loop done: images=" + got + "/" + N + " results=" + res
                    + " fails=" + stillFails.get());
            mergeResults();
            // The sidecar is part of the dump: write it before the session
            // teardown so tap-to-disk does not include cleanup time.
            boolean metaOk = false;
            if (got == 0) {
                error = "no RAW10 images";
            }
            try {
                Eraw70Meta.write(this, imu, ois);
                metaOk = got > 0;
            } catch (Throwable t) {
                error = "meta write: " + t;
                Log.e(TAG, "meta EX " + Log.getStackTraceString(t));
            }
            releaseLocks();
            cleanup();
            return got >= N && metaOk;
        }

        void applyKeys(CaptureRequest.Builder b) {
            try {
                b.set(CaptureRequest.CONTROL_AE_LOCK, Boolean.TRUE);
            } catch (Throwable t) {
                Log.w(TAG, "AE_LOCK EX " + t);
            }
            try {
                b.set(CaptureRequest.CONTROL_AWB_LOCK, Boolean.TRUE);
            } catch (Throwable t) {
                Log.w(TAG, "AWB_LOCK EX " + t);
            }
            if (!stdAttempted) {
                stdAttempted = true;
                try {
                    b.set(CaptureRequest.STATISTICS_OIS_DATA_MODE,
                            Integer.valueOf(CaptureRequest.STATISTICS_OIS_DATA_MODE_ON));
                    stdAccepted = true;
                    Log.i(TAG, "std OIS DATA_MODE_ON accepted by builder");
                } catch (Throwable t) {
                    Log.w(TAG, "std OIS unsupported: " + t);
                }
            } else if (stdAccepted) {
                try {
                    b.set(CaptureRequest.STATISTICS_OIS_DATA_MODE,
                            Integer.valueOf(CaptureRequest.STATISTICS_OIS_DATA_MODE_ON));
                } catch (Throwable ignored) {
                }
            }
            vkey(b);
        }

        void releaseLocks() {
            try {
                if (device == null || session == null || prevReader == null) return;
                CaptureRequest.Builder b = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                b.addTarget(prevReader.getSurface());
                b.set(CaptureRequest.CONTROL_AE_LOCK, Boolean.FALSE);
                b.set(CaptureRequest.CONTROL_AWB_LOCK, Boolean.FALSE);
                vkey(b);
                session.setRepeatingRequest(b.build(), null, camHandler);
                Log.i(TAG, "AE/AWB locks released");
            } catch (Throwable t) {
                Log.w(TAG, "unlock EX " + t);
            }
        }

        void mergeResults() {
            synchronized (frames) {
                for (int i = 0; i < frames.size(); i++) {
                    Eraw70Meta.FrameRec fr = frames.get(i);
                    TotalCaptureResult best = null;
                    long bestD = Long.MAX_VALUE;
                    synchronized (results) {
                        for (int j = 0; j < results.size(); j++) {
                            TotalCaptureResult r = results.get(j);
                            Long ts = (Long) r.get(CaptureResult.SENSOR_TIMESTAMP);
                            if (ts == null) continue;
                            long d = Math.abs(ts.longValue() - fr.ts);
                            if (d < bestD) {
                                bestD = d;
                                best = r;
                            }
                        }
                    }
                    if (best != null && bestD < 5000000L) fr.result = best;
                }
            }
        }

        void cleanup() {
            try {
                if (session != null) session.close();
            } catch (Throwable t) {
                Log.w(TAG, "session close EX " + t);
            }
            session = null;
            try {
                if (rawReader != null) rawReader.close();
            } catch (Throwable ignored) {
            }
            rawReader = null;
            try {
                if (prevReader != null) prevReader.close();
            } catch (Throwable ignored) {
            }
            prevReader = null;
            try {
                if (imu != null) imu.stop();
            } catch (Throwable ignored) {
            }
            try {
                if (ois != null) ois.stop();
            } catch (Throwable ignored) {
            }
            try {
                if (device != null) device.close();
            } catch (Throwable t) {
                Log.w(TAG, "device close EX " + t);
            }
            device = null;
            try {
                if (camThread != null) camThread.quitSafely();
            } catch (Throwable ignored) {
            }
            camThread = null;
            Log.i(TAG, "cleanup done");
        }
    }

    static void vkey(CaptureRequest.Builder b) {
        try {
            b.set(new CaptureRequest.Key<Integer>(
                    "samsung.android.control.shootingMode", Integer.class), Integer.valueOf(40));
            b.set(new CaptureRequest.Key<Integer>(
                    "samsung.android.control.ssmShotMode", Integer.class), Integer.valueOf(2));
            b.set(new CaptureRequest.Key<Integer>(
                    "samsung.android.control.capturePhysicalId", Integer.class), Integer.valueOf(56));
        } catch (Throwable t) {
            Log.w(TAG, "vkey EX " + t);
        }
    }
}
