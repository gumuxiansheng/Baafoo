package com.baafoo.core.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ConfigLoader {

    private static final Logger log = LoggerFactory.getLogger(ConfigLoader.class);

    private static final ObjectMapper YAML_MAPPER = createSafeYamlMapper();

    /**
     * M17: Pattern for ${ENV_VAR:default} or ${ENV_VAR} in config values.
     *
     * <p>M4: Substitution is single-pass and NON-recursive — the replacement
     * value of an env var is inserted verbatim and is NOT re-scanned for
     * further {@code ${...}} placeholders. This avoids unbounded expansion
     * loops (e.g. an env var whose value itself contains {@code ${SELF}}).
     * Nested placeholders like {@code ${${FOO}}} therefore resolve the
     * <em>outer</em> placeholder to the literal text of the inner one, not to
     * the value of {@code FOO}. Callers needing recursive resolution must
     * invoke {@link #resolveEnvVars(String)} explicitly in a loop and
     * bound the iteration count.</p>
     */
    private static final Pattern ENV_PATTERN = Pattern.compile("\\$\\{([^}:]+)(?::([^}]*))?\\}");

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
        try {
            String yamlContent = readYamlWithEnvSubstitution(file);
            AgentConfig config = YAML_MAPPER.readValue(yamlContent, AgentConfig.class);
            log.info("Loaded agent config: {}", config);
            return config;
        } catch (IOException e) {
            log.error("Failed to load agent config from {}: {}", filePath, e.getMessage());
            throw e;
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
        try {
            String yamlContent = readYamlWithEnvSubstitution(file);
            ServerConfig config = YAML_MAPPER.readValue(yamlContent, ServerConfig.class);
            log.info("Loaded server config: {}", config);
            return config;
        } catch (IOException e) {
            log.error("Failed to load server config from {}: {}", filePath, e.getMessage());
            throw e;
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
            // M5: delegate to the shared readAndResolve helper (was a duplicate
            // copy of the buffer-read loop that also lived in readYamlWithEnvSubstitution).
            String yamlContent = readAndResolve(in);
            return YAML_MAPPER.readValue(yamlContent, configClass);
        }
    }

    /**
     * Save an object as YAML to file.
     */
    public static <T> void saveToFile(String filePath, T config) throws IOException {
        YAML_MAPPER.writeValue(new File(filePath), config);
        log.info("Saved config to: {}", filePath);
    }

    /**
     * M17: Replace ${ENV_VAR:default} placeholders in YAML content with environment
     * variable values. Falls back to the default value if the env var is not set.
     * If no default is provided and the env var is missing, the placeholder is left as-is.
     */
    private static String resolveEnvVars(String yamlContent) {
        Matcher matcher = ENV_PATTERN.matcher(yamlContent);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String envName = matcher.group(1);
            String defaultValue = matcher.group(2);
            String envValue = System.getenv(envName);
            if (envValue != null && !envValue.isEmpty()) {
                matcher.appendReplacement(sb, Matcher.quoteReplacement(envValue));
            } else if (defaultValue != null) {
                matcher.appendReplacement(sb, Matcher.quoteReplacement(defaultValue));
            } else {
                // No env var and no default — leave placeholder as-is
                matcher.appendReplacement(sb, Matcher.quoteReplacement(matcher.group(0)));
            }
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * Read a YAML file and apply environment variable substitution.
     */
    private static String readYamlWithEnvSubstitution(File file) throws IOException {
        try (InputStream in = Files.newInputStream(file.toPath())) {
            // M5: reuse the shared readAndResolve helper instead of duplicating
            // the buffer-read loop here.
            return readAndResolve(in);
        }
    }

    /**
     * M5: Shared helper — fully reads an {@link InputStream} into a UTF-8
     * string and applies environment variable substitution. Extracted from
     * the previously duplicated implementations in
     * {@link #loadFromClasspath} and {@link #readYamlWithEnvSubstitution}.
     *
     * <p>The caller is responsible for closing the stream (try-with-resources).</p>
     *
     * @param in the input stream to read (not closed by this method)
     * @return the stream content with env vars resolved
     * @throws IOException on read error
     */
    private static String readAndResolve(InputStream in) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) != -1) {
            baos.write(buffer, 0, n);
        }
        String yamlContent = new String(baos.toByteArray(), StandardCharsets.UTF_8);
        return resolveEnvVars(yamlContent);
    }
}
