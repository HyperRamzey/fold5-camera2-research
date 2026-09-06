import android.content.Context;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Looper;
import android.util.Size;

/** Diagnostic probe: dump RAW stream tables (std + MAX_RESOLUTION) for public & hidden cams. */
public final class Probe {

    private Probe() {
    }

    private static void dumpCam(CameraManager cm, String id) {
        try {
            CameraCharacteristics ch = cm.getCameraCharacteristics(id);
            System.out.println("== cam " + id + " ==");
            StreamConfigurationMap std = ch.get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            StreamConfigurationMap max = ch.get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION);
            if (std != null) {
                Size[] rawStd = std.getOutputSizes(32); // RAW_SENSOR
                System.out.println("  std RAW_SENSOR: "
                    + (rawStd == null ? "null" : java.util.Arrays.toString(rawStd)));
            } else {
                System.out.println("  std map: null");
            }
            if (max != null) {
                Size[] rawMax = max.getOutputSizes(32);
                System.out.println("  MAXRES RAW_SENSOR: "
                    + (rawMax == null ? "null" : java.util.Arrays.toString(rawMax)));
                Size[] raw10 = max.getOutputSizes(37); // RAW10
                System.out.println("  MAXRES RAW10: "
                    + (raw10 == null ? "null" : java.util.Arrays.toString(raw10)));
                Size[] raw12 = max.getOutputSizes(39); // RAW12
                System.out.println("  MAXRES RAW12: "
                    + (raw12 == null ? "null" : java.util.Arrays.toString(raw12)));
            } else {
                System.out.println("  MAXRES map: null");
            }
        } catch (Throwable t) {
            System.out.println("== cam " + id + " ERR: " + t);
        }
    }

    public static void main(String[] args) {
        CameraManager cm = null;
        try {
            Looper.prepareMainLooper();
            Class<?> atClass = Class.forName("android.app.ActivityThread");
            Object thread = atClass.getMethod("systemMain").invoke(null);
            Context context = (Context) thread.getClass()
                .getMethod("getSystemContext").invoke(thread);
            cm = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            String[] ids = cm.getCameraIdList();
            System.out.println("PIDS: " + java.util.Arrays.toString(ids));
            for (String id : ids) {
                dumpCam(cm, id);
            }
            String[] hidden = {"5", "6", "20", "21", "23", "52", "56", "58", "71", "73"};
            System.out.println("--- hidden IDs ---");
            for (String id : hidden) {
                dumpCam(cm, id);
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
        System.exit(0);
    }
}
