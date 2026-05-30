import java.net.Socket;
import java.lang.reflect.Method;

public class SocketMethodCheck {
    public static void main(String[] args) {
        for (Method m : Socket.class.getDeclaredMethods()) {
            if (m.getName().equals("connect")) {
                Class<?>[] params = m.getParameterTypes();
                StringBuilder sb = new StringBuilder();
                sb.append(m.getName()).append("(");
                for (int i = 0; i < params.length; i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(params[i].getName());
                }
                sb.append(") declared in: ").append(m.getDeclaringClass().getName());
                System.out.println(sb.toString());
            }
        }
    }
}