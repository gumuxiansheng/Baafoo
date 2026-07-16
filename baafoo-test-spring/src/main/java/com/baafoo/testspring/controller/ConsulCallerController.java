package com.baafoo.testspring.controller;

import com.baafoo.testspring.service.ConsulCallerService;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Consul 外调端点（补全 Consul 协议在非 STUB 模式下的覆盖）。
 *
 * <ul>
 *   <li>/api/consul/http?path=/v1/agent/services —— 调用 consul HTTP API，
 *       返回 {statusCode, stubbed, ruleId, body}，由 agent 的 ConsulHttpAdvice
 *       决定是否拦截（STUB）或直连真实 consul（PASSTHROUGH / RECORD）。</li>
 *   <li>/api/consul/dns?name=my-service.service.consul —— 触发 agent 的
 *       DnsResolveAdvice，返回 {resolved, hostName, hostAddress}，用于证明
 *       DNS 重定向在非 PASSTHROUGH 模式下生效。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/consul")
public class ConsulCallerController {

    private final ConsulCallerService consulCallerService;

    public ConsulCallerController(ConsulCallerService consulCallerService) {
        this.consulCallerService = consulCallerService;
    }

    @GetMapping("/http")
    public Map<String, Object> consulHttp(
            @RequestParam(defaultValue = "/v1/agent/services") String path) {
        return consulCallerService.callConsulHttp(path);
    }

    @GetMapping("/dns")
    public Map<String, Object> consulDns(
            @RequestParam(defaultValue = "my-service.service.consul") String name) {
        return consulCallerService.resolveDns(name);
    }
}
