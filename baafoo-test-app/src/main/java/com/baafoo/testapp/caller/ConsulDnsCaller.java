package com.baafoo.testapp.caller;

import com.baafoo.testapp.BaafooTestApp;

import java.net.InetAddress;

public class ConsulDnsCaller implements BaafooTestApp.Caller {

    private static final String SERVICE_NAME = "my-service.service.consul";

    @Override
    public String name() {
        return "Consul DNS 外调测试 (目标: " + SERVICE_NAME + ")";
    }

    @Override
    public void run() throws Exception {
        testGetByName();
        testGetAllByName();
    }

    private void testGetByName() throws Exception {
        System.out.println("  [DNS 解析] InetAddress.getByName(\"" + SERVICE_NAME + "\")");
        try {
            InetAddress addr = InetAddress.getByName(SERVICE_NAME);
            System.out.println("    解析结果: " + addr.getHostAddress());

            // M-18: Use InetAddress.isLoopbackAddress() instead of string-comparing "127.0.0.1" —
            // the agent may redirect to any loopback address (127.0.0.2, ::1, etc.), and the
            // string comparison would miss those cases.
            boolean redirected = addr.isLoopbackAddress();
            System.out.println("    挡板拦截: " + (redirected ? "✓ 是 (DNS 被重定向到本地)" : "✗ 否"));
        } catch (java.net.UnknownHostException e) {
            System.out.println("    解析失败: " + e.getMessage());
            System.out.println("    (无 Agent 时 DNS 解析失败属正常行为)");
        }
        System.out.println();
    }

    private void testGetAllByName() throws Exception {
        System.out.println("  [DNS 解析] InetAddress.getAllByName(\"" + SERVICE_NAME + "\")");
        try {
            InetAddress[] addrs = InetAddress.getAllByName(SERVICE_NAME);
            System.out.println("    解析结果数量: " + addrs.length);
            for (InetAddress addr : addrs) {
                System.out.println("      → " + addr.getHostAddress());
            }

            // M-18: Use isLoopbackAddress() to robustly detect any loopback redirect (see testGetByName)
            boolean redirected = addrs.length > 0 && addrs[0].isLoopbackAddress();
            System.out.println("    挡板拦截: " + (redirected ? "✓ 是 (DNS 被重定向到本地)" : "✗ 否"));
        } catch (java.net.UnknownHostException e) {
            System.out.println("    解析失败: " + e.getMessage());
            System.out.println("    (无 Agent 时 DNS 解析失败属正常行为)");
        }
        System.out.println();
    }
}
