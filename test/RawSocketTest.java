import java.net.*;
import java.io.*;

public class RawSocketTest {
    public static void main(String[] args) throws Exception {
        // This should be intercepted by agent and redirected to 127.0.0.1:9000
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress("httpbin.org", 80), 5000);
        
        // Send raw HTTP request
        OutputStream out = socket.getOutputStream();
        out.write("GET /get HTTP/1.1\r\nHost: httpbin.org\r\nConnection: close\r\n\r\n".getBytes());
        out.flush();
        
        // Read response
        BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        String line;
        StringBuilder sb = new StringBuilder();
        while ((line = reader.readLine()) != null) {
            sb.append(line).append("\n");
        }
        System.out.println("=== RESPONSE ===");
        System.out.println(sb.toString());
        socket.close();
    }
}