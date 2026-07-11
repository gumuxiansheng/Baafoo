package com.baafoo.testspring.controller;

import com.baafoo.testspring.service.SocketCallerService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/socket")
public class SocketCallerController {

    private final SocketCallerService socketCallerService;

    public SocketCallerController(SocketCallerService socketCallerService) {
        this.socketCallerService = socketCallerService;
    }

    @GetMapping("/bio")
    public Map<String, Object> testBiologicalSocket(
            @RequestParam(defaultValue = "127.0.0.1") String host,
            @RequestParam(defaultValue = "9999") int port) {
        return socketCallerService.testBiologicalSocket(host, port);
    }

    @GetMapping("/nio")
    public Map<String, Object> testNioSocket(
            @RequestParam(defaultValue = "127.0.0.1") String host,
            @RequestParam(defaultValue = "9999") int port) {
        return socketCallerService.testNioSocket(host, port);
    }

    @GetMapping("/all")
    public Map<String, Object> allSocketTests(
            @RequestParam(defaultValue = "127.0.0.1") String host,
            @RequestParam(defaultValue = "9999") int port) {
        Map<String, Object> results = new LinkedHashMap<String, Object>();
        results.put("bio", socketCallerService.testBiologicalSocket(host, port));
        results.put("nio", socketCallerService.testNioSocket(host, port));
        return results;
    }

    @GetMapping("/multiround")
    public Map<String, Object> testMultiroundSocket(
            @RequestParam(defaultValue = "127.0.0.1") String host,
            @RequestParam(defaultValue = "9999") int port) {
        return socketCallerService.testMultiroundSocket(host, port);
    }

    /**
     * BIO socket test with explicit charset — drives the CH (multi-charset)
     * full-chain test cases. The request {@code message} is encoded to bytes
     * using {@code charset}; the response bytes are decoded back to a string
     * using the same {@code charset} (matching the rule's
     * {@code ResponseEntry.charset}).
     */
    @GetMapping("/bio-charset")
    public Map<String, Object> testBioSocketWithCharset(
            @RequestParam(defaultValue = "127.0.0.1") String host,
            @RequestParam(defaultValue = "9999") int port,
            @RequestParam(defaultValue = "你好世界") String message,
            @RequestParam(defaultValue = "GBK") String charset) {
        return socketCallerService.testBioSocketWithCharset(host, port, message, charset);
    }
}
