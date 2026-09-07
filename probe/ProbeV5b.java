import android.hardware.camera2.params.OutputConfiguration;
import java.lang.reflect.Method;

public final class ProbeV5b {
    public static void main(String[] args) {
        for (Method m : OutputConfiguration.class.getMethods()) {
            String s = m.getName();
            if (s.toLowerCase().contains("pixel") || s.startsWith("sem")) {
                StringBuilder sb = new StringBuilder(s + "(");
                for (Class<?> c : m.getParameterTypes()) sb.append(c.getSimpleName()).append(",");
                System.out.println(sb.append(")"));
            }
        }
        System.exit(0);
    }
}