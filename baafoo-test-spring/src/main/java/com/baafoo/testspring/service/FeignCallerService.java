package com.baafoo.testspring.service;

import feign.Feign;
import feign.Headers;
import feign.Param;
import feign.RequestLine;
import feign.Response;
import feign.jackson.JacksonDecoder;
import feign.jackson.JacksonEncoder;
import okhttp3.OkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
public class FeignCallerService {

    private static final Logger log = LoggerFactory.getLogger(FeignCallerService.class);

    public interface HttpbinApi {
        @RequestLine("GET /get")
        Response get();

        @RequestLine("POST /post")
        @Headers("Content-Type: application/json")
        Response post(String body);

        @RequestLine("GET /get?foo={foo}&baz={baz}")
        Response getWithQuery(@Param("foo") String foo, @Param("baz") String baz);
    }

    public Map<String, Object> callViaFeign(String baseUrl) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        OkHttpClient okHttpClient = new OkHttpClient.Builder()
                .connectTimeout(2, TimeUnit.SECONDS)
                .readTimeout(2, TimeUnit.SECONDS)
                .writeTimeout(2, TimeUnit.SECONDS)
                .build();
        feign.okhttp.OkHttpClient feignOkHttp = new feign.okhttp.OkHttpClient(okHttpClient);
        HttpbinApi api = Feign.builder()
                .client(feignOkHttp)
                .encoder(new JacksonEncoder())
                .decoder(new JacksonDecoder())
                .target(HttpbinApi.class, baseUrl);
        try (Response response = api.get()) {
            fillResult(response, result);
        } catch (Exception e) {
            result.put("statusCode", 0);
            result.put("stubbed", false);
            result.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return result;
    }

    public Map<String, Object> callViaFeignPost(String baseUrl, String body) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        OkHttpClient okHttpClient = new OkHttpClient.Builder()
                .connectTimeout(2, TimeUnit.SECONDS)
                .readTimeout(2, TimeUnit.SECONDS)
                .writeTimeout(2, TimeUnit.SECONDS)
                .build();
        feign.okhttp.OkHttpClient feignOkHttp = new feign.okhttp.OkHttpClient(okHttpClient);
        HttpbinApi api = Feign.builder()
                .client(feignOkHttp)
                .encoder(new JacksonEncoder())
                .decoder(new JacksonDecoder())
                .target(HttpbinApi.class, baseUrl);
        try (Response response = api.post(body)) {
            fillResult(response, result);
        } catch (Exception e) {
            result.put("statusCode", 0);
            result.put("stubbed", false);
            result.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return result;
    }

    private void fillResult(Response response, Map<String, Object> result) throws Exception {
        result.put("statusCode", response.status());
        String stubHeader = getHeader(response, "X-Baafoo-Stub");
        String ruleId = getHeader(response, "X-Baafoo-Rule-Id");
        result.put("stubbed", "true".equals(stubHeader));
        result.put("ruleId", ruleId);
        if (response.body() != null) {
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.body().asInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
            }
            String body = sb.toString();
            if (body.length() > 500) body = body.substring(0, 500) + "...";
            result.put("body", body);
        }
        log.info("Feign call complete: status={}, stubbed={}", response.status(), result.get("stubbed"));
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
