package com.baafoo.testspring.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Consul 外调测试服务（HTTP API + DNS 解析两套路径）。
 *
 * <p>用于补全 Consul 协议的<b>非 STUB 模式</b>覆盖（PASSTHROUGH / RECORD /
 * RECORD_AND_STUB / RECORD_ALL）：</p>
 *
 * <ul>
 *   <li>{@link #callConsulHttp(String)} —— 走 HTTP 路径，命中
 *       {@code http-consul} 规则。STUB 模式下 agent 注入
 *       {@code X-Baafoo-Stub:true} 响应头；PASSTHROUGH 下直连真实 consul；
 *       RECORD 系下转发真实 consul 并落 recording。</li>
 *   <li>{@link #resolveDns(String)} —— 走 DNS 路径，触发 agent 的
 *       {@code DnsResolveAdvice}。非 PASSTHROUGH 模式下
 *       {@code *.service.consul} 会被重定向到 Baafoo Server IP（保留原 hostName）；
 *       PASSTHROUGH 下做真实 DNS 解析（对 .service.consul 必然失败）。</li>
 * </ul>
 */
@Service
public class ConsulCallerService {

    private static final Logger log = LoggerFactory.getLogger(ConsulCallerService.class);

    private static final String CONSUL_HTTP_BASE = "http://consul-server:8500";

    private final HttpCallerService httpCallerService;

    public ConsulCallerService(HttpCallerService httpCallerService) {
        this.httpCallerService = httpCallerService;
    }

    public Map<String, Object> callConsulHttp(String path) {
        if (path == null || path.isEmpty()) {
            path = "/v1/agent/services";
        }
        String url = CONSUL_HTTP_BASE + path;
        log.info("Consul HTTP call: {}", url);
        try {
            return httpCallerService.doGet(url);
        } catch (Exception e) {
            Map<String, Object> err = new LinkedHashMap<String, Object>();
            err.put("statusCode", 0);
            err.put("stubbed", false);
            err.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
            return err;
        }
    }

    public Map<String, Object> resolveDns(String name) {
        if (name == null || name.isEmpty()) {
            name = "my-service.service.consul";
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        log.info("Consul DNS resolve: {}", name);
        try {
            InetAddress addr = InetAddress.getByName(name);
            result.put("resolved", true);
            result.put("hostName", addr.getHostName());
            result.put("hostAddress", addr.getHostAddress());
            result.put("canonicalHostName", addr.getCanonicalHostName());
            log.info("DNS resolved: {} -> {} (canonical={})",
                    name, addr.getHostAddress(), addr.getCanonicalHostName());
        } catch (Exception e) {
            result.put("resolved", false);
            result.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
            log.warn("DNS resolve failed for {}: {}", name, e.getMessage());
        }
        return result;
    }
}
