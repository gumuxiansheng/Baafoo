package com.baafoo.core.model;

/**
 * MQ relationship definition.
 *
 * <p>Defines a causal link from a source MQ destination to one or more target
 * destinations. When a message is produced to {@code fromTopic}, the broker
 * derives a new message for {@code toTopic} after {@code delayMs}.</p>
 *
 * <p>Key and value templates support simple variable substitution using the
 * same {@code {{request.body}}}, {@code {{faker.*}}} syntax as stub responses.
 * Additionally, {@code {{topic}}}, {@code {{key}}} and {@code {{partition}}}
 * are available for the source message context.</p>
 */
public class MqRelationship {

    private String id;
    private String name;
    private String fromProtocol;
    private String fromTopic;
    private String toProtocol;
    private String toTopic;
    private String keyTemplate;
    private String valueTemplate;
    private long delayMs;
    private boolean enabled;
    private long createdAt;
    private long updatedAt;

    public MqRelationship() {
        this.fromProtocol = "kafka";
        this.toProtocol = "kafka";
        this.enabled = true;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getFromProtocol() { return fromProtocol; }
    public void setFromProtocol(String fromProtocol) { this.fromProtocol = fromProtocol; }

    public String getFromTopic() { return fromTopic; }
    public void setFromTopic(String fromTopic) { this.fromTopic = fromTopic; }

    public String getToProtocol() { return toProtocol; }
    public void setToProtocol(String toProtocol) { this.toProtocol = toProtocol; }

    public String getToTopic() { return toTopic; }
    public void setToTopic(String toTopic) { this.toTopic = toTopic; }

    public String getKeyTemplate() { return keyTemplate; }
    public void setKeyTemplate(String keyTemplate) { this.keyTemplate = keyTemplate; }

    public String getValueTemplate() { return valueTemplate; }
    public void setValueTemplate(String valueTemplate) { this.valueTemplate = valueTemplate; }

    public long getDelayMs() { return delayMs; }
    public void setDelayMs(long delayMs) { this.delayMs = delayMs; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }

    @Override
    public String toString() {
        return "MqRelationship{" +
                "id='" + id + '\'' +
                ", from='" + fromProtocol + ":" + fromTopic + '\'' +
                ", to='" + toProtocol + ":" + toTopic + '\'' +
                ", enabled=" + enabled +
                '}';
    }
}
