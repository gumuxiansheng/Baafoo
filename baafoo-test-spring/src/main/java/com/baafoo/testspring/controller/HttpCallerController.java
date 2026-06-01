package com.baafoo.testspring.controller;

import com.baafoo.testspring.service.HttpCallerService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/http")
public class HttpCallerController {

    private final HttpCallerService httpCallerService;

    public HttpCallerController(HttpCallerService httpCallerService) {
        this.httpCallerService = httpCallerService;
    }

    @GetMapping("/get")
    public Map<String, Object> doGet(@RequestParam(defaultValue = "http://httpbin.org/get") String url) throws Exception {
        return httpCallerService.doGet(url);
    }

    @PostMapping("/post")
    public Map<String, Object> doPost(
            @RequestParam(defaultValue = "http://httpbin.org/post") String url,
            @RequestParam(defaultValue = "{\"test\":\"baafoo\"}") String body) throws Exception {
        return httpCallerService.doPost(url, body);
    }

    @GetMapping("/methods")
    public Map<String, Object> allMethods() {
        Map<String, Object> results = new LinkedHashMap<String, Object>();
        try { results.put("get", httpCallerService.doGet("http://httpbin.org/get")); } catch (Exception e) { results.put("get", errorMap("GET", e)); }
        try { results.put("post", httpCallerService.doPost("http://httpbin.org/post", "{\"test\":\"baafoo\"}")); } catch (Exception e) { results.put("post", errorMap("POST", e)); }
        try { results.put("put", httpCallerService.doPut("http://httpbin.org/put", "{\"test\":\"baafoo-put\"}")); } catch (Exception e) { results.put("put", errorMap("PUT", e)); }
        try { results.put("delete", httpCallerService.doDelete("http://httpbin.org/delete")); } catch (Exception e) { results.put("delete", errorMap("DELETE", e)); }
        return results;
    }

    private Map<String, Object> errorMap(String method, Exception e) {
        Map<String, Object> err = new LinkedHashMap<String, Object>();
        err.put("method", method);
        err.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        return err;
    }

    @GetMapping("/health")
    public String health() {
        return "OK";
    }
}
