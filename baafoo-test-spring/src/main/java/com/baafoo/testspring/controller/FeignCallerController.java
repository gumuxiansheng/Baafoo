package com.baafoo.testspring.controller;

import com.baafoo.testspring.service.FeignCallerService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/feign")
public class FeignCallerController {

    private final FeignCallerService feignCallerService;

    public FeignCallerController(FeignCallerService feignCallerService) {
        this.feignCallerService = feignCallerService;
    }

    @GetMapping("/get")
    public Map<String, Object> callGet(@RequestParam(defaultValue = "http://httpbin.org") String baseUrl) {
        return feignCallerService.callViaFeign(baseUrl);
    }

    @GetMapping("/post")
    public Map<String, Object> callPost(
            @RequestParam(defaultValue = "http://httpbin.org") String baseUrl,
            @RequestParam(defaultValue = "{\"test\":\"baafoo-feign\"}") String body) {
        return feignCallerService.callViaFeignPost(baseUrl, body);
    }

    @GetMapping("/all")
    public Map<String, Object> allMethods(@RequestParam(defaultValue = "http://httpbin.org") String baseUrl) {
        Map<String, Object> results = new LinkedHashMap<String, Object>();
        results.put("get", feignCallerService.callViaFeign(baseUrl));
        results.put("post", feignCallerService.callViaFeignPost(baseUrl, "{\"test\":\"baafoo-feign\"}"));
        return results;
    }
}
