package com.baafoo.core.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

public class ConfigLoader {

    private static final Logger log = LoggerFactory.getLogger(ConfigLoader.class);

    private static final ObjectMapper YAML_MAPPER = createSafeYamlMapper();

    private static ObjectMapper createSafeYamlMapper() {
        YAMLFactory yamlFactory = YAMLFactory.builder()
                .disable(YAMLGenerator.Feature.USE_NATIVE_TYPE_ID)
                .build();
        return new ObjectMapper(yamlFactory);
    }

    /**
     * Load AgentConfig from YAML file.
     *
     * @param filePath path to agent config YAML
     * @return loaded config
     * @throws IOException on file read error
     */
    public static AgentConfig loadAgentConfig(String filePath) throws IOException {
        File file = new File(filePath);
        if (!file.exists()) {
            log.warn("Agent config file not found at {}, using defaults", filePath);
            return new AgentConfig();
        }
        try (InputStream in = new FileInputStream(file)) {
            AgentConfig config = YAML_MAPPER.readValue(in, AgentConfig.class);
            log.info("Loaded agent config: {}", config);
            return config;
        }
    }

    /**
     * Load ServerConfig from YAML file.
     *
     * @param filePath path to server config YAML
     * @return loaded config
     * @throws IOException on file read error
     */
    public static ServerConfig loadServerConfig(String filePath) throws IOException {
        File file = new File(filePath);
        if (!file.exists()) {
            log.warn("Server config file not found at {}, using defaults", filePath);
            return new ServerConfig();
        }
        try (InputStream in = new FileInputStream(file)) {
            ServerConfig config = YAML_MAPPER.readValue(in, ServerConfig.class);
            log.info("Loaded server config: {}", config);
            return config;
        }
    }

    /**
     * Load YAML config from classpath resource.
     *
     * @param resourcePath classpath resource path
     * @param configClass  target config class
     * @param <T>          config type
     * @return loaded config
     * @throws IOException on read error
     */
    public static <T> T loadFromClasspath(String resourcePath, Class<T> configClass) throws IOException {
        try (InputStream in = ConfigLoader.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IOException("Resource not found: " + resourcePath);
            }
            return YAML_MAPPER.readValue(in, configClass);
        }
    }

    /**
     * Save an object as YAML to file.
     */
    public static <T> void saveToFile(String filePath, T config) throws IOException {
        YAML_MAPPER.writeValue(new File(filePath), config);
        log.info("Saved config to: {}", filePath);
    }
}
