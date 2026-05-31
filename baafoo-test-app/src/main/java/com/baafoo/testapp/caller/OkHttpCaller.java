package com.baafoo.testapp.caller;

import com.baafoo.testapp.BaafooTestApp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class OkHttpCaller implements BaafooTestApp.Caller {

    private static final String TARGET_HOST = "httpbin.org";
    private static final int TARGET_PORT = 80;
    private static final String BASE_URL = "http://" + TARGET_HOST;
    private static final MediaType JSON_TYPE = MediaType.parse("application/json; charset=UTF-8");

    private final OkHttpClient client;

    public OkHttpCaller() {
        this.client = new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .writeTimeout(5, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public String name() {
        return "OkHttp 外调测试 (目标: " + TARGET_HOST + ":" + TARGET_PORT + ")";
    }

    @Override
    public void run() throws Exception {
        testGet();
        testPost();
        testPut();
        testDelete();
        testWithHeaders();
        testWithQueryParams();
    }

    private void testGet() throws IOException {
        System.out.println("  [GET] " + BASE_URL + "/get");
        Request request = new Request.Builder()
                .url(BASE_URL + "/get")
                .get()
                .build();
        try (Response response = client.newCall(request).execute()) {
            printResponse(response);
        }
    }

    private void testPost() throws IOException {
        System.out.println("  [POST] " + BASE_URL + "/post");
        RequestBody body = RequestBody.create(JSON_TYPE,
                "{\"test\":\"baafoo-okhttp\",\"protocol\":\"okhttp\"}".getBytes(StandardCharsets.UTF_8));
        Request request = new Request.Builder()
                .url(BASE_URL + "/post")
                .post(body)
                .build();
        try (Response response = client.newCall(request).execute()) {
            printResponse(response);
        }
    }

    private void testPut() throws IOException {
        System.out.println("  [PUT] " + BASE_URL + "/put");
        RequestBody body = RequestBody.create(JSON_TYPE,
                "{\"test\":\"baafoo-okhttp-put\"}".getBytes(StandardCharsets.UTF_8));
        Request request = new Request.Builder()
                .url(BASE_URL + "/put")
                .put(body)
                .build();
        try (Response response = client.newCall(request).execute()) {
            printResponse(response);
        }
    }

    private void testDelete() throws IOException {
        System.out.println("  [DELETE] " + BASE_URL + "/delete");
        Request request = new Request.Builder()
                .url(BASE_URL + "/delete")
                .delete()
                .build();
        try (Response response = client.newCall(request).execute()) {
            printResponse(response);
        }
    }

    private void testWithHeaders() throws IOException {
        System.out.println("  [GET+Headers] " + BASE_URL + "/get (X-Test-Header: baafoo-okhttp)");
        Request request = new Request.Builder()
                .url(BASE_URL + "/get")
                .get()
                .header("X-Test-Header", "baafoo-okhttp")
                .header("Accept", "application/json")
                .build();
        try (Response response = client.newCall(request).execute()) {
            printResponse(response);
        }
    }

    private void testWithQueryParams() throws IOException {
        System.out.println("  [GET+Query] " + BASE_URL + "/get?foo=bar&baz=qux");
        Request request = new Request.Builder()
                .url(BASE_URL + "/get?foo=bar&baz=qux")
                .get()
                .build();
        try (Response response = client.newCall(request).execute()) {
            printResponse(response);
        }
    }

    private void printResponse(Response response) throws IOException {
        int code = response.code();
        String stubHeader = response.header("X-Baafoo-Stub");
        String ruleId = response.header("X-Baafoo-Rule-Id");

        String body = "";
        if (response.body() != null) {
            body = response.body().string();
            if (body.length() > 500) {
                body = body.substring(0, 500) + "... (truncated)";
            }
        }

        boolean stubbed = "true".equals(stubHeader);
        System.out.println("    状态码: " + code);
        System.out.println("    挡板拦截: " + (stubbed ? "✓ 是" : "✗ 否"));
        if (ruleId != null) {
            System.out.println("    匹配规则: " + ruleId);
        }
        System.out.println("    响应体: " + body);
        System.out.println();
    }
}
