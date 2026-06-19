package com.baafoo.server.broker;

import com.baafoo.core.model.EnvironmentMode;
import com.baafoo.core.model.RecordingEntry;
import com.baafoo.server.handler.AgentResolver;
import com.baafoo.server.storage.StorageService;
import org.apache.activemq.artemis.api.core.Message;
import org.apache.activemq.artemis.core.server.ServerSession;
import org.apache.activemq.artemis.core.server.plugin.ActiveMQServerPlugin;
import org.apache.activemq.artemis.spi.core.protocol.RemotingConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Collections;

/**
 * ActiveMQ Artemis broker plugin that records JMS producer messages.
 *
 * <p>This plugin is invoked by the broker itself via
 * {@link ActiveMQServerPlugin#afterSend} and
 * therefore captures messages from <b>all protocols</b> accepted by the broker,
 * including OpenWire (used by ActiveMQ 5.x clients redirected by the Baafoo
 * Agent).</p>
 *
 * <p>The plugin resolves the source environment from the session's remoting
 * connection remote address and only persists a recording when the environment
 * is in {@code RECORD} or {@code RECORD_AND_STUB} mode.</p>
 */
public class JmsRecordingPlugin implements ActiveMQServerPlugin {

    private static final Logger log = LoggerFactory.getLogger(JmsRecordingPlugin.class);

    private final StorageService storage;

    public JmsRecordingPlugin(StorageService storage) {
        this.storage = storage;
    }

    @Override
    public void afterSend(ServerSession session, org.apache.activemq.artemis.core.transaction.Transaction tx,
                          Message message, boolean direct, boolean noAutoCreateQueue,
                          org.apache.activemq.artemis.core.postoffice.RoutingStatus result) {
        if (storage == null || message == null) {
            return;
        }

        try {
            String destination = message.getAddress();
            String body = extractBody(message);

            String remoteIp = null;
            if (session != null) {
                RemotingConnection connection = session.getRemotingConnection();
                if (connection != null) {
                    remoteIp = extractIp(connection.getRemoteAddress());
                }
            }

            AgentResolver resolver = new AgentResolver(storage);
            AgentResolver.AgentInfo info = resolver.resolveByIp(remoteIp);
            EnvironmentMode mode = resolver.resolveEnvironmentMode(info.environment);

            if (mode != EnvironmentMode.RECORD && mode != EnvironmentMode.RECORD_AND_STUB) {
                return;
            }

            RecordingEntry rec = new RecordingEntry();
            rec.setRuleId(null);
            rec.setProtocol("jms");
            rec.setPath(destination);
            rec.setRequestBody(body);
            rec.setResponseStatusCode(0);
            rec.setRequestHeaders(Collections.<String, String>emptyMap());
            rec.setResponseHeaders(Collections.<String, String>emptyMap());
            if (info != null) {
                rec.setEnvironmentId(info.environment);
                rec.setAgentId(info.agentId);
                rec.setAgentIp(info.agentIp);
            }
            storage.addRecording(rec);
            log.info("Recorded JMS message: destination={}, bodyLength={}, env={}, agentIp={}",
                    destination, body != null ? body.length() : 0, info.environment, info.agentIp);
        } catch (Exception e) {
            log.warn("Failed to record JMS message: {}", e.getMessage(), e);
        }
    }

    private String extractBody(Message message) {
        if (message == null) {
            return null;
        }
        try {
            if (message.getType() == Message.TEXT_TYPE) {
                String body = message.getStringBody();
                if (body != null) {
                    return body;
                }
            }
            org.apache.activemq.artemis.api.core.ActiveMQBuffer bodyBuffer = message.getBodyBuffer();
            if (bodyBuffer != null && bodyBuffer.readableBytes() > 0) {
                byte[] bytes = new byte[bodyBuffer.readableBytes()];
                bodyBuffer.readBytes(bytes);
                return new String(bytes, StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            log.debug("Unable to extract JMS message body: {}", e.getMessage());
        }
        return null;
    }

    private String extractIp(String remoteAddress) {
        if (remoteAddress == null || remoteAddress.isEmpty()) {
            return null;
        }
        String s = remoteAddress;
        int schemeEnd = s.indexOf("://");
        if (schemeEnd >= 0) {
            s = s.substring(schemeEnd + 3);
        }
        if (s.startsWith("/")) {
            s = s.substring(1);
        }
        int colon = s.lastIndexOf(':');
        if (colon > 0) {
            s = s.substring(0, colon);
        }
        return s;
    }
}
