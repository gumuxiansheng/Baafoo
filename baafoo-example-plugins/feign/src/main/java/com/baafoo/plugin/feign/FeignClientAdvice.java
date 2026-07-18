package com.baafoo.plugin.feign;

import feign.Request;
import feign.Response;
import net.bytebuddy.asm.Advice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class FeignClientAdvice {

    // L-1: Use SLF4J instead of System.out — the host app's logging config will then
    // govern the verbosity and destination of these trace messages.
    private static final Logger log = LoggerFactory.getLogger(FeignClientAdvice.class);

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

            log.debug("[FeignClientAdvice] Intercepted feign.Client.execute() method={} url={} host={} port={}",
                    method, url, uri.getHost(), uri.getPort());

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
            log.warn("[FeignClientAdvice] Error: {}", t.getMessage());
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
                log.debug("[FeignClientAdvice] Response status={} intercepted flag present", response.status());
            }
        } catch (Throwable t) {
            log.warn("[FeignClientAdvice] Exit error: {}", t.getMessage());
        }
    }
}
