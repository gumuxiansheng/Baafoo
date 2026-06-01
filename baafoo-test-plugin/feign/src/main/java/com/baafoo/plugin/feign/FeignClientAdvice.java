package com.baafoo.plugin.feign;

import feign.Request;
import feign.Response;
import net.bytebuddy.asm.Advice;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class FeignClientAdvice {

    @Advice.OnMethodEnter
    public static void onExecute(
            @Advice.Argument(value = 0, readOnly = false) Request request) {
        try {
            if (request == null) {
                return;
            }

            String url = request.url();
            String method = request.httpMethod().name();
            URI uri = new URI(url);
            String path = uri.getPath();

            System.out.println("[FeignClientAdvice] Intercepted feign.Client.execute()");
            System.out.println("[FeignClientAdvice]   Method: " + method);
            System.out.println("[FeignClientAdvice]   URL:   " + url);
            System.out.println("[FeignClientAdvice]   Host:  " + uri.getHost());
            System.out.println("[FeignClientAdvice]   Port:  " + uri.getPort());

            Map<String, Collection<String>> headers = new LinkedHashMap<String, Collection<String>>(request.headers());
            headers.put("X-Feign-Method", Collections.<String>singletonList(method));
            headers.put("X-Feign-Path", Collections.<String>singletonList(path != null ? path : "/"));
            headers.put("X-Feign-Intercepted", Collections.<String>singletonList("true"));

            request = Request.create(
                    request.httpMethod(),
                    request.url(),
                    headers,
                    request.body(),
                    request.charset()
            );

        } catch (Throwable t) {
            System.out.println("[FeignClientAdvice] Error: " + t.getMessage());
        }
    }

    @Advice.OnMethodExit
    public static void afterExecute(
            @Advice.Return(readOnly = false) Response response) {
        try {
            if (response == null) {
                return;
            }

            Collection<String> intercepted = response.headers().get("X-Feign-Intercepted");
            if (intercepted != null && !intercepted.isEmpty()) {
                System.out.println("[FeignClientAdvice] Response status: " + response.status());
                System.out.println("[FeignClientAdvice] Response intercepted flag present");
            }
        } catch (Throwable t) {
            System.out.println("[FeignClientAdvice] Exit error: " + t.getMessage());
        }
    }
}
