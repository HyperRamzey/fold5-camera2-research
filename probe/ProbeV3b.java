import android.content.Context;
import android.hardware.camera2.params.OutputConfiguration;
import java.lang.reflect.Method;

public final class ProbeV3b {
    public static void main(String[] args) {
        for (Method m : OutputConfiguration.class.getDeclaredMethods()) {
            if (m.getName().startsWith("sem")) {
                StringBuilder sb = new StringBuilder(m.getName() + "(");
                for (Class<?> c : m.getParameterTypes()) sb.append(c.getSimpleName()).append(", ");
                sb.append(") ret=").append(m.getReturnType().getSimpleName());
                System.out.println(sb);
            }
        }
        for (java.lang.reflect.Constructor<?> c : OutputConfiguration.class.getDeclaredConstructors()) {
            StringBuilder sb = new StringBuilder("ctor(");
            for (Class<?> pc : c.getParameterTypes()) sb.append(pc.getSimpleName()).append(", ");
            System.out.println(sb.append(")"));
        }
        System.exit(0);
    }
}