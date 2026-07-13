package com.baafoo.server.broker;

import com.baafoo.core.model.EnvironmentMode;
import com.baafoo.core.model.RecordingEntry;
import com.baafoo.core.model.Rule;
import com.baafoo.core.util.MatchEngine;
import com.baafoo.server.handler.AgentResolver;
import com.baafoo.server.storage.StorageService;
import org.apache.activemq.artemis.api.core.Message;
import org.apache.activemq.artemis.core.server.ActiveMQServer;
import org.apache.activemq.artemis.core.server.MessageReference;
import org.apache.activemq.artemis.core.server.ServerConsumer;
import org.apache.activemq.artemis.core.server.ServerSession;
import org.apache.activemq.artemis.core.server.plugin.ActiveMQServerPlugin;
import org.apache.activemq.artemis.core.transaction.Transaction;
import org.apache.activemq.artemis.spi.core.protocol.RemotingConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * ActiveMQ Artemis broker plugin that records JMS messages on both the
 * producer side ({@code afterSend}) and the consumer side ({@code afterDeliver}).
 *
 * <p>This plugin is invoked by the broker itself and therefore captures messages
 * from <b>all protocols</b> accepted by the broker, including OpenWire (used by
 * ActiveMQ 5.x clients redirected by the Baafoo Agent).</p>
 *
 * <p>The plugin resolves the source environment from the session's remoting
 * connection remote address and only persists a recording when the environment
 * is in {@code RECORD} or {@code RECORD_AND_STUB} mode. Each recording is tagged
 * with a {@code direction} field: {@code "produce"} for sends and
 * {@code "consume"} for deliveries.</p>
 */
public class JmsRecordingPlugin implements ActiveMQServerPlugin {

    private static final Logger log = LoggerFactory.getLogger(JmsRecordingPlugin.class);

    private final StorageService storage;
    private final ActiveMQServer server;

    public JmsRecordingPlugin(StorageService storage, ActiveMQServer server) {
        this.storage = storage;
        this.server = server;
    }

    @Override
    public void afterSend(ServerSession session, Transaction tx,
                          Message message, boolean direct, boolean noAutoCreateQueue,
                          org.apache.activemq.artemis.core.postoffice.RoutingStatus result) {
        recordMessage(session, message, "produce");
    }

    @Override
    public void afterDeliver(ServerConsumer consumer, MessageReference reference) {
        Message message = reference != null ? reference.getMessage() : null;
        if (log.isDebugEnabled()) {
            log.debug("afterDeliver called: consumer={}, hasMessage={}, queue={}",
                    consumer != null ? consumer.getQueue() : null,
                    message != null,
                    consumer != null && consumer.getQueue() != null ? consumer.getQueue().getName() : null);
        }
        // ServerConsumer doesn't expose ServerSession directly; resolve the
        // RemotingConnection via the broker's RemotingService using the
        // consumer's connection ID so we can still extract the client IP.
        //
        // TYPE MISMATCH NOTE: consumer.getConnectionID() returns a String
        // (e.g., "482ba29b"), but RemotingConnection.getID() may return a
        // different type (e.g., io.netty.channel.DefaultChannelId). The values
        // are equal but Object.equals() returns false across types. We compare
        // by toString() to handle both String and non-String ID types.
        if (consumer != null && server != null) {
            try {
                Object connId = consumer.getConnectionID();
                if (connId != null) {
                    String connIdStr = connId.toString();
                    Set<RemotingConnection> conns = server.getRemotingService().getConnections();
                    if (conns != null) {
                        for (RemotingConnection conn : conns) {
                            if (conn == null) continue;
                            Object connGetId = conn.getID();
                            if (connGetId != null && connIdStr.equals(connGetId.toString())) {
                                String remoteIp = extractIp(conn.getRemoteAddress());
                                recordMessageWithIp(remoteIp, message, "consume");
                                return;
                            }
                        }
                        log.debug("afterDeliver: connection ID {} not found among {} active connections, using null-IP fallback",
                                connId, conns.size());
                    }
                }
            } catch (Exception e) {
                log.debug("Failed to resolve consumer connection for afterDeliver: {}", e.getMessage());
            }
        }
        // Fallback: record without IP resolution. resolveByIp(null) will use the
        // unique-environment / server-subnet fallbacks to resolve the environment
        // when there is exactly one online environment (common in tests and
        // single-env deployments).
        recordMessageWithIp(null, message, "consume");
    }

    /**
     * Shared recording path for both producer sends and consumer deliveries.
     *
     * @param session    the broker session (used to resolve agent/environment by IP)
     * @param message    the JMS message to record
     * @param direction  "produce" for sends, "consume" for deliveries
     */
    private void recordMessage(ServerSession session, Message message, String direction) {
        String remoteIp = null;
        if (session != null) {
            RemotingConnection connection = session.getRemotingConnection();
            if (connection != null) {
                remoteIp = extractIp(connection.getRemoteAddress());
            }
        }
        recordMessageWithIp(remoteIp, message, direction);
    }

    /**
     * Recording path that resolves agent/environment directly from a remote IP.
     * Used by {@link #afterDeliver} where only the RemotingConnection (not
     * ServerSession) is available.
     *
     * @param remoteIp   the client IP (may be null if unresolvable)
     * @param message    the JMS message to record
     * @param direction  "produce" for sends, "consume" for deliveries
     */
    private void recordMessageWithIp(String remoteIp, Message message, String direction) {
        if (storage == null || message == null) {
            return;
        }

        try {
            String destination = message.getAddress();
            String body = extractBody(message);

            AgentResolver resolver = new AgentResolver(storage);
            AgentResolver.AgentInfo info = resolver.resolveByIp(remoteIp);
            EnvironmentMode mode = resolver.resolveEnvironmentMode(info.environment);

            if (mode != EnvironmentMode.RECORD && mode != EnvironmentMode.RECORD_AND_STUB && mode != EnvironmentMode.RECORD_ALL) {
                return;
            }

            // Match against rules — only record if a rule matches.
            List<Rule> allRules = storage.listRules();
            List<Rule> rules = resolver.filterRulesByEnvironment(allRules, info.environment);
            MqMatchHelper matchHelper = new MqMatchHelper(storage);
            MatchEngine.MatchResult m = matchHelper.match(rules, "jms", destination, body);
            if (!m.isMatched()) {
                return;
            }

            RecordingEntry rec = new RecordingEntry();
            rec.setRuleId(m.getRule().getId());
            rec.setProtocol("jms");
            rec.setPath(destination);
            rec.setRequestBody(body);
            rec.setResponseStatusCode(0);
            rec.setRequestHeaders(Collections.<String, String>emptyMap());
            rec.setResponseHeaders(Collections.<String, String>emptyMap());
            rec.setDirection(direction);
            rec.setResponseSource("STUB");
            if (info != null) {
                rec.setEnvironmentId(info.environment);
                rec.setAgentId(info.agentId);
                rec.setAgentIp(info.agentIp);
            }
            storage.addRecording(rec);
            log.info("Recorded JMS message: destination={}, direction={}, ruleId={}, bodyLength={}, env={}, agentIp={}",
                    destination, direction, m.getRule().getId(), body != null ? body.length() : 0, info.environment, info.agentIp);
        } catch (Exception e) {
            log.warn("Failed to record JMS message (direction={}): {}", direction, e.getMessage(), e);
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
                // Check for BaafooCharset property set by JmsMockBroker.sendPresetMessage
                // when the original rule declared a non-UTF-8 response charset (e.g., GBK).
                String charsetName = null;
                try {
                    charsetName = message.getStringProperty("BaafooCharset");
                } catch (Exception ignored) {
                    // Property may not exist — fall through to UTF-8 default.
                }
                if (charsetName != null && !charsetName.isEmpty()
                        && !"UTF-8".equalsIgnoreCase(charsetName)) {
                    try {
                        return new String(bytes, java.nio.charset.Charset.forName(charsetName));
                    } catch (Exception e) {
                        log.debug("Unsupported charset '{}', falling back to UTF-8", charsetName);
                    }
                }
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
