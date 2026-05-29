package com.baafoo.agent.transform;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Registry for tracking installed Byte Buddy transforms.
 * Useful for debugging and runtime introspection.
 */
public class TransformRegistry {

    private static final Logger log = LoggerFactory.getLogger(TransformRegistry.class);

    private final List<TransformEntry> transforms = new ArrayList<TransformEntry>();

    public void register(String targetClass, String adviceClass, String protocol) {
        TransformEntry entry = new TransformEntry(targetClass, adviceClass, protocol);
        transforms.add(entry);
        log.info("Registered transform: {} ← {} ({})", targetClass, adviceClass, protocol);
    }

    public List<TransformEntry> getTransforms() {
        return Collections.unmodifiableList(transforms);
    }

    public int getCount() {
        return transforms.size();
    }

    /**
     * A single transform entry.
     */
    public static class TransformEntry {
        private final String targetClass;
        private final String adviceClass;
        private final String protocol;

        TransformEntry(String targetClass, String adviceClass, String protocol) {
            this.targetClass = targetClass;
            this.adviceClass = adviceClass;
            this.protocol = protocol;
        }

        public String getTargetClass() { return targetClass; }
        public String getAdviceClass() { return adviceClass; }
        public String getProtocol() { return protocol; }
    }
}
