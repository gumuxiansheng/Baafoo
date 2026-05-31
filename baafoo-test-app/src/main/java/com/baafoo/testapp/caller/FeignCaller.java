package com.baafoo.testapp.caller;

import com.baafoo.testapp.BaafooTestApp;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import feign.Feign;
import feign.Headers;
import feign.Param;
import feign.RequestLine;
import feign.Response;
import feign.jackson.JacksonDecoder;
import feign.jackson.JacksonEncoder;

public class FeignCaller implements BaafooTestApp.Caller {

    private static final String TARGET_HOST = "httpbin.org";
    private static final int TARGET_PORT = 80;
    private static final String BASE_URL = "http://" + TARGET_HOST;

    interface HttpbinApi {
        @RequestLine("GET /get")
        Response get();

        @RequestLine("POST /post")
        @Headers("Content-Type: application/json")
        Response post(String body);

        @RequestLine("PUT /put")
        @Headers("Content-Type: application/json")
        Response put(String body);

        @RequestLine("DELETE /delete")
        Response delete();

        @RequestLine("GET /get")
        @Headers({"X-Test-Header: baafoo-feign", "Accept: application/json"})
        Response getWithHeaders();

        @RequestLine("GET /get?foo={foo}&baz={baz}")
        Response getWithQueryParams(@Param("foo") String foo, @Param("baz") String baz);
    }

    private final HttpbinApi api;

    public FeignCaller() {
        okhttp3.OkHttpClient okHttpClient = new okhttp3.OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .writeTimeout(5, TimeUnit.SECONDS)
                .build();

        feign.okhttp.OkHttpClient feignOkHttp = new feign.okhttp.OkHttpClient(okHttpClient);

        this.api = Feign.builder()
                .client(feignOkHttp)
                .encoder(new JacksonEncoder())
                .decoder(new JacksonDecoder())
                .target(HttpbinApi.class, BASE_URL);
    }

    @Override
    public String name() {
        return "Feign+OkHttp 外调测试 (目标: " + TARGET_HOST + ":" + TARGET_PORT + ")";
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

    private void testGet() throws Exception {
        System.out.println("  [GET] " + BASE_URL + "/get");
        try (Response response = api.get()) {
            printResponse(response);
        }
    }

    private void testPost() throws Exception {
        System.out.println("  [POST] " + BASE_URL + "/post");
        try (Response response = api.post("{\"test\":\"baafoo-feign\",\"protocol\":\"feign\"}")) {
            printResponse(response);
        }
    }

    private void testPut() throws Exception {
        System.out.println("  [PUT] " + BASE_URL + "/put");
        try (Response response = api.put("{\"test\":\"baafoo-feign-put\"}")) {
            printResponse(response);
        }
    }

    private void testDelete() throws Exception {
        System.out.println("  [DELETE] " + BASE_URL + "/delete");
        try (Response response = api.delete()) {
            printResponse(response);
        }
    }

    private void testWithHeaders() throws Exception {
        System.out.println("  [GET+Headers] " + BASE_URL + "/get (X-Test-Header: baafoo-feign)");
        try (Response response = api.getWithHeaders()) {
            printResponse(response);
        }
    }

    private void testWithQueryParams() throws Exception {
        System.out.println("  [GET+Query] " + BASE_URL + "/get?foo=bar&baz=qux");
        try (Response response = api.getWithQueryParams("bar", "qux")) {
            printResponse(response);
        }
    }

    private void printResponse(Response response) throws Exception {
        int code = response.status();
        String stubHeader = getHeader(response, "X-Baafoo-Stub");
        String ruleId = getHeader(response, "X-Baafoo-Rule-Id");

        String body = "";
        if (response.body() != null) {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.body().asInputStream(), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            body = sb.toString();
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

    private String getHeader(Response response, String headerName) {
        Map<String, Collection<String>> headers = response.headers();
        Collection<String> values = headers.get(headerName);
        if (values != null && !values.isEmpty()) {
            return values.iterator().next();
        }
        return null;
    }
}
