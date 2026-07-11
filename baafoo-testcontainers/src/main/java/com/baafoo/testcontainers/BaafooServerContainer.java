package com.baafoo.testcontainers;

import com.baafoo.core.model.Environment;
import com.baafoo.core.model.EnvironmentMode;
import com.baafoo.core.model.Rule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;
import org.testcontainers.utility.DockerImageName;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public class BaafooServerContainer extends GenericContainer<BaafooServerContainer> {

    private static final Logger log = LoggerFactory.getLogger(BaafooServerContainer.class);

    private static final DockerImageName DEFAULT_IMAGE_NAME = DockerImageName.parse("baafoo-server:latest");

    private static final int API_PORT = 8084;

    private final List<PreloadAction> preloadActions = new ArrayList<>();

    private String apiKey;

    private BaafooClient client;

    public BaafooServerContainer() {
        this(DEFAULT_IMAGE_NAME);
    }

    public BaafooServerContainer(String dockerImageName) {
        this(DockerImageName.parse(dockerImageName));
    }

    public BaafooServerContainer(DockerImageName dockerImageName) {
        super(dockerImageName);
        withExposedPorts(API_PORT);
        withEnv("BAAFOO_HTTP_PORT", String.valueOf(API_PORT));
        withEnv("BAAFOO_DB_TYPE", "h2");
        waitingFor(new HttpWaitStrategy()
                .forPath("/__baafoo__/api/status")
                .forStatusCode(200)
                .withStartupTimeout(Duration.ofSeconds(60)));
    }

    /**
     * Set the API key for authenticated communication with the server.
     */
    public BaafooServerContainer withApiKey(String apiKey) {
        this.apiKey = apiKey;
        withEnv("BAAFOO_API_KEY", apiKey);
        return this;
    }

    /**
     * Preload a rule into the server after startup.
     */
    public BaafooServerContainer withRule(Rule rule) {
        preloadActions.add(new PreloadAction(PreloadActionType.RULE, rule));
        return this;
    }

    /**
     * Preload a rule from a classpath JSON resource.
     */
    public BaafooServerContainer withRuleFromClasspath(String classpathResource) {
        try {
            String json = readClasspathResource(classpathResource);
            Rule rule = BaafooClient.MAPPER.readValue(json, Rule.class);
            preloadActions.add(new PreloadAction(PreloadActionType.RULE, rule));
            return this;
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to load rule from classpath: " + classpathResource, e);
        }
    }

    /**
     * Preload a rule from a file on disk.
     */
    public BaafooServerContainer withRuleFromFile(String filePath) {
        try {
            File file = new File(filePath);
            Rule rule = BaafooClient.MAPPER.readValue(file, Rule.class);
            preloadActions.add(new PreloadAction(PreloadActionType.RULE, rule));
            return this;
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to load rule from file: " + filePath, e);
        }
    }

    /**
     * Preload a rule from a JSON string.
     */
    public BaafooServerContainer withRuleFromJson(String json) {
        try {
            Rule rule = BaafooClient.MAPPER.readValue(json, Rule.class);
            preloadActions.add(new PreloadAction(PreloadActionType.RULE, rule));
            return this;
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to parse rule JSON", e);
        }
    }

    /**
     * Preload an environment into the server after startup.
     */
    public BaafooServerContainer withEnvironment(String name, EnvironmentMode mode) {
        Environment env = new Environment();
        env.setName(name);
        env.setMode(mode);
        preloadActions.add(new PreloadAction(PreloadActionType.ENVIRONMENT, env));
        return this;
    }

    /**
     * Preload an environment into the server after startup.
     */
    public BaafooServerContainer withEnvironment(String name, String mode) {
        return withEnvironment(name, EnvironmentMode.fromValue(mode));
    }

    /**
     * Preload an environment from a JSON string.
     */
    public BaafooServerContainer withEnvironmentFromJson(String json) {
        try {
            Environment env = BaafooClient.MAPPER.readValue(json, Environment.class);
            preloadActions.add(new PreloadAction(PreloadActionType.ENVIRONMENT, env));
            return this;
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to parse environment JSON", e);
        }
    }

    @Override
    public void start() {
        super.start();
        this.client = new BaafooClient(getHttpBaseUrl(), apiKey);
        applyPreloadActions();
    }

    private void applyPreloadActions() {
        for (PreloadAction action : preloadActions) {
            switch (action.type) {
                case RULE:
                    Rule createdRule = client.createRule((Rule) action.payload);
                    log.info("Preloaded rule: {} (id={})", ((Rule) action.payload).getName(), createdRule.getId());
                    break;
                case ENVIRONMENT:
                    Environment createdEnv = client.createEnvironment((Environment) action.payload);
                    log.info("Preloaded environment: {} (mode={})",
                            createdEnv.getName(), createdEnv.getMode().getValue());
                    break;
                default:
                    break;
            }
        }
    }

    /**
     * Get the base URL of the Baafoo Server HTTP API.
     */
    public String getHttpBaseUrl() {
        return "http://localhost:" + getMappedPort(API_PORT);
    }

    /**
     * Get the Baafoo API client for programmatic configuration.
     */
    public BaafooClient getClient() {
        if (client == null) {
            client = new BaafooClient(getHttpBaseUrl(), apiKey);
        }
        return client;
    }

    private static String readClasspathResource(String resource) throws IOException {
        try (InputStream is = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(resource)) {
            if (is == null) {
                throw new IOException("Resource not found: " + resource);
            }
            byte[] buf = new byte[4096];
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            int n;
            while ((n = is.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    // ------------------------------------------------------------------
    // Internal types
    // ------------------------------------------------------------------

    private enum PreloadActionType {
        RULE, ENVIRONMENT
    }

    private static class PreloadAction {
        final PreloadActionType type;
        final Object payload;

        PreloadAction(PreloadActionType type, Object payload) {
            this.type = type;
            this.payload = payload;
        }
    }
}
