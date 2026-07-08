package com.baafoo.testspring.controller;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Staging-internal "real backend" mock endpoint.
 *
 * <p>During PASSTHROUGH / RECORD modes the Baafoo agent lets the test app's
 * outbound HTTP calls through untouched. Those calls target the internal host
 * {@code real-backend} (a Docker network alias that points at this very
 * container), so this controller answers them with a deterministic echo
 * instead of hitting the public internet (httpbin.org). This keeps the
 * full-chain system test fully hermetic: no external network dependency, yet
 * PASSTHROUGH can still be verified against a genuine (non-stubbed) response.</p>
 */
@RestController
public class BackendEchoController {

    @RequestMapping(
        value = {"/", "/**"},
        method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT,
                  RequestMethod.DELETE, RequestMethod.PATCH, RequestMethod.HEAD,
                  RequestMethod.OPTIONS}
    )
    public Map<String, Object> echo(HttpServletRequest request,
                                    @RequestBody(required = false) String body) {
        String uri = request.getRequestURI();
        // More specific @RequestMapping handlers (e.g. /api/**) win over this
        // catch-all, but guard anyway so we never shadow a real endpoint.
        if (uri.startsWith("/api") || uri.startsWith("/actuator") || uri.startsWith("/error")) {
            Map<String, Object> notFound = new LinkedHashMap<String, Object>();
            notFound.put("error", "not_found");
            notFound.put("path", uri);
            return notFound;
        }

        Map<String, Object> echo = new LinkedHashMap<String, Object>();
        echo.put("realBackend", true);
        echo.put("host", "real-backend");
        echo.put("method", request.getMethod());
        echo.put("path", uri);
        echo.put("query", request.getQueryString());

        Map<String, Object> headers = new LinkedHashMap<String, Object>();
        Enumeration<String> headerNames = request.getHeaderNames();
        if (headerNames != null) {
            while (headerNames.hasMoreElements()) {
                String name = headerNames.nextElement();
                headers.put(name, request.getHeader(name));
            }
        }
        echo.put("headers", headers);

        if (body != null && !body.isEmpty()) {
            echo.put("body", body);
        }
        return echo;
    }
}
