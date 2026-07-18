package com.baafoo.testspring.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * TDMQ for Pulsar test service.
 *
 * TDMQ (Tencent Distributed Message Queue) for Pulsar is Tencent Cloud's managed
 * Pulsar service. The binary protocol is identical to Apache Pulsar, so TDMQ
 * compatibility is covered by {@link PulsarCallerService}.
 *
 * To test with the TDMQ SDK (com.tencent.tdmq:tdmq-client) instead of the
 * open-source Pulsar client, add the following dependency to pom.xml:
 * <pre>
 *   &lt;dependency&gt;
 *       &lt;groupId&gt;com.tencent.tdmq&lt;/groupId&gt;
 *       &lt;artifactId&gt;tdmq-client&lt;/artifactId&gt;
 *       &lt;version&gt;{tdmq-version}&lt;/version&gt;
 *   &lt;/dependency&gt;
 * </pre>
 *
 * Then configure {@code serviceUrl} to your TDMQ cluster address, e.g.
 * {@code pulsar://pulsar-tdmq.dev:6650}.
 */
@Service
public class TdmqCallerService {

    private static final Logger log = LoggerFactory.getLogger(TdmqCallerService.class);

    private final PulsarCallerService pulsarCallerService;

    public TdmqCallerService(PulsarCallerService pulsarCallerService) {
        this.pulsarCallerService = pulsarCallerService;
    }

    public Map<String, Object> sendMessage(String tdmqServiceUrl, String topic, String message) {
        log.info("TDMQ test via Pulsar protocol: serviceUrl={}, topic={}", tdmqServiceUrl, topic);
        Map<String, Object> result = pulsarCallerService.sendMessage(tdmqServiceUrl, topic, message);
        result.put("tdmqCompatible", true);
        result.put("note", "TDMQ for Pulsar uses the same binary protocol as Apache Pulsar");
        return result;
    }

    public Map<String, Object> checkConfig() {
        Map<String, Object> config = new LinkedHashMap<String, Object>();
        config.put("protocol", "Pulsar Binary Protocol (compatible with TDMQ)");
        config.put("defaultPort", 6650);
        config.put("sdk", "org.apache.pulsar:pulsar-client (also covers com.tencent.tdmq:tdmq-client)");
        // M-21: tdmqSdkAvailable refers ONLY to Tencent's repackaged tdmq-client jar — the existing
        // pulsar-client already speaks the same binary protocol and works against TDMQ clusters out
        // of the box. The flag is intentionally false here because this module does not pull in
        // the Tencent-specific jar (it's optional for users who want Tencent's branded entry points).
        config.put("tdmqSdkAvailable", false);
        config.put("tdmqSdkNote", "Optional: add com.tencent.tdmq:tdmq-client to use Tencent's SDK entry points; pulsar-client already covers TDMQ");
        return config;
    }
}
