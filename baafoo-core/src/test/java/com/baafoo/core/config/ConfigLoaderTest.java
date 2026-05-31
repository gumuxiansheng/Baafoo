package com.baafoo.core.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.*;

public class ConfigLoaderTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void testLoadAgentConfigFileNotFound() throws IOException {
        AgentConfig config = ConfigLoader.loadAgentConfig("nonexistent-file.yml");
        assertNotNull(config);
        assertEquals(30, config.getHeartbeatIntervalSec());
    }

    @Test
    public void testLoadServerConfigFileNotFound() throws IOException {
        ServerConfig config = ConfigLoader.loadServerConfig("nonexistent-file.yml");
        assertNotNull(config);
        assertEquals(8080, config.getHttpPort());
    }

    @Test
    public void testLoadAgentConfigFromFile() throws IOException {
        File configFile = tempFolder.newFile("agent.yml");
        ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("agentId", "test-agent");
        data.put("environment", "staging");
        data.put("serverUrl", "http://test:8080");
        yamlMapper.writeValue(configFile, data);

        AgentConfig config = ConfigLoader.loadAgentConfig(configFile.getAbsolutePath());
        assertEquals("test-agent", config.getAgentId());
        assertEquals("staging", config.getEnvironment());
        assertEquals("http://test:8080", config.getServerUrl());
    }

    @Test
    public void testLoadServerConfigFromFile() throws IOException {
        File configFile = tempFolder.newFile("server.yml");
        ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("httpPort", 9090);
        data.put("unmatchedDefault", "404");
        yamlMapper.writeValue(configFile, data);

        ServerConfig config = ConfigLoader.loadServerConfig(configFile.getAbsolutePath());
        assertEquals(9090, config.getHttpPort());
        assertEquals("404", config.getUnmatchedDefault());
    }

    @Test(expected = IOException.class)
    public void testLoadFromClasspathNotFound() throws IOException {
        ConfigLoader.loadFromClasspath("nonexistent-resource.yml", AgentConfig.class);
    }

    @Test
    public void testSaveToFile() throws IOException {
        File file = tempFolder.newFile("output.yml");
        ServerConfig config = new ServerConfig();
        config.setHttpPort(1234);

        ConfigLoader.saveToFile(file.getAbsolutePath(), config);
        assertTrue(file.exists());
        assertTrue(file.length() > 0);

        ServerConfig loaded = ConfigLoader.loadServerConfig(file.getAbsolutePath());
        assertEquals(1234, loaded.getHttpPort());
    }
}
