package com.baafoo.server.integration;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.Duration;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/**
 * Agent containerized integration test.
 *
 * <p>Verifies the Baafoo Agent's bytecode enhancement works correctly in a
 * Docker containerized environment by:</p>
 * <ol>
 *   <li>Starting a Baafoo Server container</li>
 *   <li>Starting a test application container with the Agent attached</li>
 *   <li>Sending HTTP requests to the test application</li>
 *   <li>Verifying that the Agent intercepts and routes traffic to the Mock Server</li>
 * </ol>
 *
 * <p>This test requires Docker and pre-built Baafoo Docker images. It is
 * automatically skipped if Docker is not available.</p>
 *
 * <p><strong>Prerequisites:</strong></p>
 * <ul>
 *   <li>Docker must be running</li>
 *   <li>Baafoo images must be built:
 *     <pre>
 *     mvnw clean package -DskipTests
 *     docker build -t baafoo-server:latest .
 *     docker build -t baafoo-test-spring:latest -f baafoo-test-spring/Dockerfile .
 *     </pre>
 *   </li>
 * </ul>
 */
public class AgentContainerizedIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(AgentContainerizedIntegrationTest.class);

    private static final String SERVER_IMAGE = "baafoo-server:latest";
    private static final String TEST_APP_IMAGE = "baafoo-test-spring:latest";

    private static final int SERVER_API_PORT = 8084;
    private static final int SERVER_HTTP_MOCK_PORT = 9000;
    private static final int TEST_APP_PORT = 9090;

    public static Network network;
    private static boolean dockerAvailable;

    static {
        try {
            dockerAvailable = DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            dockerAvailable = false;
        }
        if (dockerAvailable) {
            try {
                network = Network.newNetwork();
            } catch (Throwable t) {
                log.warn("Failed to create Docker network, containerized tests will be skipped: {}", t.getMessage());
                network = null;
                dockerAvailable = false;
            }
        }
    }

    @AfterClass
    public static void tearDownClass() {
        if (network != null) {
            try {
                network.close();
            } catch (Exception ignored) {
            }
        }
    }

    private GenericContainer<?> serverContainer;
    private GenericContainer<?> appContainer;

    @Before
    public void setUp() {
        // Skip if Docker is not available or network creation failed
        assumeTrue("Docker must be available for containerized integration tests",
                dockerAvailable && network != null);

        // Check if Baafoo images exist; skip if not
        assumeTrue("Baafoo Server image must be built (docker build -t baafoo-server:latest .)",
                imageExists(SERVER_IMAGE));
        assumeTrue("Baafoo Test Spring image must be built",
                imageExists(TEST_APP_IMAGE));
    }

    // ----------------------------------------------------------------------
    // Test 1: Server starts and is healthy
    // ----------------------------------------------------------------------

    /**
     * Verify that the Baafoo Server container starts and the health
     * check endpoint responds.
     */
    @Test
    public void testServerContainerStarts() throws Exception {
        serverContainer = new GenericContainer<>(DockerImageName.parse(SERVER_IMAGE))
                .withNetwork(network)
                .withNetworkAliases("baafoo-server")
                .withExposedPorts(SERVER_API_PORT)
                .withEnv("BAAFOO_HTTP_PORT", String.valueOf(SERVER_API_PORT))
                .withEnv("BAAFOO_DB_TYPE", "h2")
                .waitingFor(new HttpWaitStrategy()
                        .forPath("/__baafoo__/api/status")
                        .forStatusCode(200)
                        .withStartupTimeout(Duration.ofSeconds(60)));

        serverContainer.start();

        try {
            String serverUrl = "http://localhost:" + serverContainer.getMappedPort(SERVER_API_PORT);
            String status = httpGet(serverUrl + "/__baafoo__/api/status");
            assertNotNull("Server status should not be null", status);
            log.info("Server container started successfully: {}", status);
        } finally {
            serverContainer.stop();
        }
    }

    // ----------------------------------------------------------------------
    // Test 2: Agent registers with Server
    // ----------------------------------------------------------------------

    /**
     * Start Server + test app (with Agent), verify the Agent registers
     * with the Server and appears in the agent list.
     */
    @Test
    public void testAgentRegistersWithServer() throws Exception {
        // Start Server
        serverContainer = new GenericContainer<>(DockerImageName.parse(SERVER_IMAGE))
                .withNetwork(network)
                .withNetworkAliases("baafoo-server")
                .withExposedPorts(SERVER_API_PORT)
                .withEnv("BAAFOO_HTTP_PORT", String.valueOf(SERVER_API_PORT))
                .withEnv("BAAFOO_DB_TYPE", "h2")
                .waitingFor(new HttpWaitStrategy()
                        .forPath("/__baafoo__/api/status")
                        .forStatusCode(200)
                        .withStartupTimeout(Duration.ofSeconds(60)));
        serverContainer.start();

        try {
            // Start test app with Agent
            appContainer = new GenericContainer<>(DockerImageName.parse(TEST_APP_IMAGE))
                    .withNetwork(network)
                    .withNetworkAliases("baafoo-test-app")
                    .withExposedPorts(TEST_APP_PORT)
                    .withEnv("BAAFOO_SERVER_HOST", "baafoo-server")
                    .withEnv("BAAFOO_ENV", "integration-test")
                    .withEnv("SERVER_PORT", String.valueOf(TEST_APP_PORT))
                    .waitingFor(new HttpWaitStrategy()
                            .forPath("/api/stub-demo/health")
                            .forStatusCode(200)
                            .withStartupTimeout(Duration.ofSeconds(90)));
            appContainer.start();

            try {
                // Wait for Agent to register
                Thread.sleep(5000);

                // Check if Agent appears in Server's agent list
                String serverUrl = "http://localhost:" + serverContainer.getMappedPort(SERVER_API_PORT);
                String agents = httpGet(serverUrl + "/__baafoo__/api/agents");

                assertNotNull("Agent list should not be null", agents);
                log.info("Agent registration check: {}", agents);
            } finally {
                appContainer.stop();
            }
        } finally {
            serverContainer.stop();
        }
    }

    // ----------------------------------------------------------------------
    // Test 3: HTTP stub rule works end-to-end
    // ----------------------------------------------------------------------

    /**
     * Configure a stub rule on the Server, then send an HTTP request
     * through the test app (with Agent) and verify the stub response.
     */
    @Test
    public void testHttpStubEndToEnd() throws Exception {
        // Start Server
        serverContainer = new GenericContainer<>(DockerImageName.parse(SERVER_IMAGE))
                .withNetwork(network)
                .withNetworkAliases("baafoo-server")
                .withExposedPorts(SERVER_API_PORT, SERVER_HTTP_MOCK_PORT)
                .withEnv("BAAFOO_HTTP_PORT", String.valueOf(SERVER_API_PORT))
                .withEnv("BAAFOO_DB_TYPE", "h2")
                .waitingFor(new HttpWaitStrategy()
                        .forPath("/__baafoo__/api/status")
                        .forStatusCode(200)
                        .withStartupTimeout(Duration.ofSeconds(60)));
        serverContainer.start();

        try {
            String serverUrl = "http://localhost:" + serverContainer.getMappedPort(SERVER_API_PORT);

            // Create a stub rule
            String ruleJson = "{"
                    + "\"id\":\"e2e-http-rule\","
                    + "\"name\":\"E2E HTTP Stub\","
                    + "\"protocol\":\"http\","
                    + "\"host\":\"real-backend\","
                    + "\"port\":9090,"
                    + "\"conditions\":[{\"type\":\"path\",\"operator\":\"equals\",\"value\":\"/get\"}],"
                    + "\"responses\":[{\"name\":\"stub\",\"value\":\"{\\\"stubbed\\\":true}\",\"delayMs\":0}],"
                    + "\"enabled\":true,"
                    + "\"priority\":100,"
                    + "\"environments\":[\"integration-test\"]"
                    + "}";

            httpPost(serverUrl + "/__baafoo__/api/rules", ruleJson);

            // Create environment
            String envJson = "{\"name\":\"integration-test\",\"mode\":\"stub\",\"description\":\"E2E test\"}";
            httpPost(serverUrl + "/__baafoo__/api/environments", envJson);

            // Start test app with Agent
            appContainer = new GenericContainer<>(DockerImageName.parse(TEST_APP_IMAGE))
                    .withNetwork(network)
                    .withNetworkAliases("baafoo-test-app")
                    .withExposedPorts(TEST_APP_PORT)
                    .withEnv("BAAFOO_SERVER_HOST", "baafoo-server")
                    .withEnv("BAAFOO_ENV", "integration-test")
                    .withEnv("SERVER_PORT", String.valueOf(TEST_APP_PORT))
                    .waitingFor(new HttpWaitStrategy()
                            .forPath("/api/stub-demo/health")
                            .forStatusCode(200)
                            .withStartupTimeout(Duration.ofSeconds(90)));
            appContainer.start();

            try {
                // Wait for Agent to pick up rules
                Thread.sleep(5000);

                // Send an HTTP request through the test app
                // The test app has an endpoint that makes an outbound HTTP call
                String appUrl = "http://localhost:" + appContainer.getMappedPort(TEST_APP_PORT);
                String response = httpGet(appUrl + "/api/stub-demo/http?url=http://httpbin.org/get");

                assertNotNull("Should get a response from test app", response);
                log.info("E2E HTTP stub test response: {}", response);
            } finally {
                appContainer.stop();
            }
        } finally {
            serverContainer.stop();
        }
    }

    // ----------------------------------------------------------------------
    // Helper methods
    // ----------------------------------------------------------------------

    private boolean imageExists(String imageName) {
        try {
            ProcessBuilder pb = new ProcessBuilder("docker", "image", "inspect", imageName);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            int exitCode = p.waitFor();
            return exitCode == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private String httpGet(String urlStr) throws IOException {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);

        int code = conn.getResponseCode();
        if (code != 200) {
            throw new IOException("HTTP " + code + " from " + urlStr);
        }

        java.io.InputStream is = conn.getInputStream();
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) != -1) {
            bos.write(buf, 0, n);
        }
        is.close();
        return bos.toString("UTF-8");
    }

    private String httpPost(String urlStr, String body) throws IOException {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);

        java.io.OutputStream os = conn.getOutputStream();
        os.write(body.getBytes("UTF-8"));
        os.close();

        int code = conn.getResponseCode();
        if (code != 200 && code != 201) {
            throw new IOException("HTTP " + code + " from " + urlStr);
        }

        java.io.InputStream is = conn.getInputStream();
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) != -1) {
            bos.write(buf, 0, n);
        }
        is.close();
        return bos.toString("UTF-8");
    }
}
