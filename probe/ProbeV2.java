import android.content.Context;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Looper;
import android.util.Size;
import android.graphics.Rect;

/** ProbeV2: Samsung TETRA / ExpertRAW vendor stream-config tags on public + physical cams. */
public final class ProbeV2 {

    private ProbeV2() {
    }

    static void p(String s) { System.out.println(s); }

    static void dumpTags(CameraCharacteristics ch, String id) {
        String[] names = {
            "samsung.android.scaler.availableTetraPictureStreamConfigurations",
            "samsung.android.scaler.availableFullTetraPictureStreamConfigurations",
            "samsung.android.scaler.availableCropTetraPictureStreamConfigurations",
            "samsung.android.scaler.availableExpertRawStreamConfigurations",
            "samsung.android.scaler.availableExpertRawHighresRawStreamConfigurations",
            "samsung.android.scaler.availableExpertRawHighresYuvStreamConfigurations",
            "samsung.android.scaler.availableHighresRawStreamConfigurations",
            "samsung.android.scaler.availableAiFusionHighresRawStreamConfigurations",
            "samsung.android.scaler.availableFullPictureStreamConfigurations",
            "samsung.android.scaler.availablePictureStreamConfigurations",
            "samsung.android.scaler.availableCropPictureStreamConfigurations",
            "samsung.android.control.availableFeatures",
            "samsung.android.control.highresModeInfo",
            "samsung.android.request.availableSessionKeys",
            "samsung.android.scaler.rawSensorInfo",
            "samsung.android.sensor.pixelMode",
            "samsung.android.control.sdkAvailableFeatures",
        };
        p("== cam " + id + " ==");
        for (String n : names) {
            try {
                CameraCharacteristics.Key<int[]> k = new CameraCharacteristics.Key<>(n, int[].class);
                int[] v = ch.get(k);
                if (v == null) { p("  " + n + ": null"); }
                else {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < v.length; i++) {
                        if (i % 5 == 0 && i > 0) sb.append(" | ");
                        sb.append(v[i]).append(i % 5 == 4 ? "" : ",");
                        if (n.contains("StreamConfigurations") && i % 5 == 4) sb.append("  ");
                    }
                    p("  " + n + " [" + v.length + "]: " + sb);
                }
            } catch (Throwable t) {
                p("  " + n + " ERR: " + t);
            }
        }
        // pixel array + active array for context
        try {
            android.util.Size pa = ch.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE);
            android.graphics.Rect aa = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            p("  pixelArray=" + pa + " activeArray=" + aa);
        } catch (Throwable t) { p("  rect ERR " + t); }
        // front RAW check (low-priority user request): std RAW_SENSOR sizes
        try {
            Size[] rawStd = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP).getOutputSizes(32);
            p("  std RAW_SENSOR: " + (rawStd == null ? "null" : java.util.Arrays.toString(rawStd)));
        } catch (Throwable t) { p("  rawstd ERR " + t); }
    }

    public static void main(String[] args) {
        try {
            Looper.prepareMainLooper();
            Class<?> atClass = Class.forName("android.app.ActivityThread");
            Object thread = atClass.getMethod("systemMain").invoke(null);
            Context context = (Context) thread.getClass()
                .getMethod("getSystemContext").invoke(thread);
            CameraManager cm = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            String[] ids = cm.getCameraIdList();
            p("PIDS: " + java.util.Arrays.toString(ids));
            String[] probeIds = {"0", "1", "5", "6", "20", "21", "23", "52", "56", "58", "71", "73"};
            for (String id : probeIds) {
                try {
                    dumpTags(cm.getCameraCharacteristics(id), id);
                } catch (Throwable t) {
                    p("== cam " + id + " OPEN-ERR: " + t);
                }
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
        System.exit(0);
    }
}
