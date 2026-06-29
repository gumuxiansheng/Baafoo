package com.baafoo.server.handler;

import com.baafoo.core.config.ServerConfig;
import com.baafoo.core.model.Environment;
import com.baafoo.core.model.EnvironmentMode;
import com.baafoo.core.model.Rule;
import com.baafoo.server.storage.StorageService;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.Channel;
import org.junit.Before;
import org.junit.Test;

import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class AgentResolverTest {

    private StorageService storage;

    @Before
    public void setUp() {
        storage = mock(StorageService.class);
    }

    // --- resolveByIp ---

    @Test
    public void resolveByIpWithNoAgentsReturnsNullFields() {
        when(storage.listAgents()).thenReturn(new ArrayList<>());
        AgentResolver resolver = new AgentResolver(storage);

        AgentResolver.AgentInfo info = resolver.resolveByIp("10.0.0.1");

        assertNull(info.environment);
        assertNull(info.agentId);
        assertEquals("10.0.0.1", info.agentIp);
    }

    @Test
    public void resolveByIpMatchesExactIp() {
        List<StorageService.AgentRegistration> agents = new ArrayList<>();
        StorageService.AgentRegistration reg = new StorageService.AgentRegistration();
        reg.agentId = "agent-1";
        reg.environment = "staging";
        reg.agentIp = "10.0.0.1";
        reg.lastHeartbeat = System.currentTimeMillis();
        agents.add(reg);
        when(storage.listAgents()).thenReturn(agents);

        AgentResolver resolver = new AgentResolver(storage);
        AgentResolver.AgentInfo info = resolver.resolveByIp("10.0.0.1");

        assertEquals("staging", info.environment);
        assertEquals("agent-1", info.agentId);
        assertEquals("10.0.0.1", info.agentIp);
    }

    @Test
    public void resolveByIpSkipsOfflineAgents() {
        List<StorageService.AgentRegistration> agents = new ArrayList<>();
        StorageService.AgentRegistration reg = new StorageService.AgentRegistration();
        reg.agentId = "agent-1";
        reg.environment = "staging";
        reg.agentIp = "10.0.0.1";
        reg.lastHeartbeat = System.currentTimeMillis() - 300000;
        agents.add(reg);
        when(storage.listAgents()).thenReturn(agents);

        AgentResolver resolver = new AgentResolver(storage);
        AgentResolver.AgentInfo info = resolver.resolveByIp("10.0.0.1");

        assertNull(info.environment);
        assertNull(info.agentId);
    }

    @Test
    public void resolveByIpWithNullIpReturnsNullEnvironment() {
        when(storage.listAgents()).thenReturn(new ArrayList<>());
        AgentResolver resolver = new AgentResolver(storage);

        AgentResolver.AgentInfo info = resolver.resolveByIp(null);

        assertNull(info.environment);
        assertNull(info.agentId);
        assertNull(info.agentIp);
    }

    @Test
    public void resolveByIpPrefersMostRecentHeartbeatForSameEnv() {
        List<StorageService.AgentRegistration> agents = new ArrayList<>();
        StorageService.AgentRegistration oldReg = new StorageService.AgentRegistration();
        oldReg.agentId = "agent-old";
        oldReg.environment = "staging";
        oldReg.agentIp = "10.0.0.1";
        oldReg.lastHeartbeat = System.currentTimeMillis() - 10000;
        agents.add(oldReg);

        StorageService.AgentRegistration newReg = new StorageService.AgentRegistration();
        newReg.agentId = "agent-new";
        newReg.environment = "staging";
        newReg.agentIp = "10.0.0.1";
        newReg.lastHeartbeat = System.currentTimeMillis();
        agents.add(newReg);

        when(storage.listAgents()).thenReturn(agents);

        AgentResolver resolver = new AgentResolver(storage);
        AgentResolver.AgentInfo info = resolver.resolveByIp("10.0.0.1");

        assertEquals("agent-new", info.agentId);
    }

    @Test
    public void resolveByIpAmbiguousEnvironmentsReturnsNull() {
        List<StorageService.AgentRegistration> agents = new ArrayList<>();
        StorageService.AgentRegistration reg1 = new StorageService.AgentRegistration();
        reg1.agentId = "agent-a";
        reg1.environment = "staging-a";
        reg1.agentIp = "10.0.0.5";
        reg1.lastHeartbeat = System.currentTimeMillis();
        agents.add(reg1);

        StorageService.AgentRegistration reg2 = new StorageService.AgentRegistration();
        reg2.agentId = "agent-b";
        reg2.environment = "staging-b";
        reg2.agentIp = "10.0.0.5";
        reg2.lastHeartbeat = System.currentTimeMillis();
        agents.add(reg2);

        when(storage.listAgents()).thenReturn(agents);

        AgentResolver resolver = new AgentResolver(storage);
        AgentResolver.AgentInfo info = resolver.resolveByIp("10.0.0.5");

        assertNull(info.environment);
        assertNull(info.agentId);
    }

    // --- filterRulesByEnvironment ---

    @Test
    public void filterRulesByEnvironmentNullEnvReturnsEmpty() {
        AgentResolver resolver = new AgentResolver(storage);
        List<Rule> rules = Arrays.asList(new Rule());

        List<Rule> result = resolver.filterRulesByEnvironment(rules, null);

        assertTrue(result.isEmpty());
    }

    @Test
    public void filterRulesByEnvironmentReturnsGlobalRules() {
        Rule globalRule = new Rule();
        globalRule.setId("global");
        globalRule.setEnabled(true);

        AgentResolver resolver = new AgentResolver(storage);
        List<Rule> result = resolver.filterRulesByEnvironment(Arrays.asList(globalRule), "staging");

        assertEquals(1, result.size());
        assertEquals("global", result.get(0).getId());
    }

    @Test
    public void filterRulesByEnvironmentSkipsDisabledRules() {
        Rule disabledRule = new Rule();
        disabledRule.setId("disabled");
        disabledRule.setEnabled(false);

        AgentResolver resolver = new AgentResolver(storage);
        List<Rule> result = resolver.filterRulesByEnvironment(Arrays.asList(disabledRule), "staging");

        assertTrue(result.isEmpty());
    }

    @Test
    public void filterRulesByEnvironmentMatchesByEnvName() {
        Rule envRule = new Rule();
        envRule.setId("env-specific");
        envRule.setEnabled(true);
        envRule.setEnvironments(Arrays.asList("staging"));

        Rule otherEnvRule = new Rule();
        otherEnvRule.setId("other-env");
        otherEnvRule.setEnabled(true);
        otherEnvRule.setEnvironments(Arrays.asList("production"));

        AgentResolver resolver = new AgentResolver(storage);
        List<Rule> result = resolver.filterRulesByEnvironment(Arrays.asList(envRule, otherEnvRule), "staging");

        assertEquals(1, result.size());
        assertEquals("env-specific", result.get(0).getId());
    }

    @Test
    public void filterRulesByEnvironmentFiltersGlobalAndEnvSpecific() {
        Rule global = new Rule();
        global.setId("global");
        global.setEnabled(true);

        Rule specific = new Rule();
        specific.setId("specific");
        specific.setEnabled(true);
        specific.setEnvironments(Arrays.asList("staging"));

        Rule other = new Rule();
        other.setId("other");
        other.setEnabled(true);
        other.setEnvironments(Arrays.asList("production"));

        AgentResolver resolver = new AgentResolver(storage);
        List<Rule> result = resolver.filterRulesByEnvironment(Arrays.asList(global, specific, other), "staging");

        assertEquals(2, result.size());
        assertTrue(result.stream().anyMatch(r -> "global".equals(r.getId())));
        assertTrue(result.stream().anyMatch(r -> "specific".equals(r.getId())));
    }

    // --- resolveEnvironmentMode ---

    @Test
    public void resolveEnvironmentModeReturnsMatchingEnvMode() {
        Environment env = new Environment();
        env.setName("staging");
        env.setMode(EnvironmentMode.RECORD);
        when(storage.listEnvironments()).thenReturn(Arrays.asList(env));

        AgentResolver resolver = new AgentResolver(storage);
        assertEquals(EnvironmentMode.RECORD, resolver.resolveEnvironmentMode("staging"));
    }

    @Test
    public void resolveEnvironmentModeUnknownEnvReturnsPassthrough() {
        when(storage.listEnvironments()).thenReturn(new ArrayList<>());

        AgentResolver resolver = new AgentResolver(storage);
        assertEquals(EnvironmentMode.PASSTHROUGH, resolver.resolveEnvironmentMode("unknown"));
    }

    @Test
    public void resolveEnvironmentModeNullEnvReturnsPassthrough() {
        when(storage.listEnvironments()).thenReturn(new ArrayList<>());

        AgentResolver resolver = new AgentResolver(storage);
        assertEquals(EnvironmentMode.PASSTHROUGH, resolver.resolveEnvironmentMode(null));
    }

    @Test
    public void resolveEnvironmentModeWithConfigDefaultStub() {
        ServerConfig config = new ServerConfig();
        config.setUnknownEnvironmentDefault("stub");

        AgentResolver resolver = new AgentResolver(storage, config);
        assertEquals(EnvironmentMode.STUB, resolver.resolveEnvironmentMode("unknown"));
    }

    // --- resolveAgentIpFromChannel ---

    @Test
    public void resolveAgentIpFromChannelWithoutRemoteAddressReturnsNull() {
        AgentResolver resolver = new AgentResolver(storage);
        Channel channel = mock(Channel.class);
        when(channel.remoteAddress()).thenReturn(null);
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
        when(ctx.channel()).thenReturn(channel);

        String ip = resolver.resolveAgentIpFromChannel(ctx);

        assertNull(ip);
    }

    @Test
    public void resolveAgentIpFromChannelExtractsIp() {
        AgentResolver resolver = new AgentResolver(storage);
        Channel channel = mock(Channel.class);
        SocketAddress addr = mock(SocketAddress.class);
        when(addr.toString()).thenReturn("/10.0.0.1:12345");
        when(channel.remoteAddress()).thenReturn(addr);
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
        when(ctx.channel()).thenReturn(channel);

        String ip = resolver.resolveAgentIpFromChannel(ctx);

        assertEquals("10.0.0.1", ip);
    }

    @Test
    public void resolveAllWithNullContextReturnsNullEnvironment() {
        when(storage.listAgents()).thenReturn(new ArrayList<>());
        AgentResolver resolver = new AgentResolver(storage);

        AgentResolver.AgentInfo info = resolver.resolveAll(null);

        assertNull(info.environment);
        assertNull(info.agentId);
    }

    // --- deprecated methods ---

    @Test
    public void deprecatedResolveMethods() {
        when(storage.listAgents()).thenReturn(new ArrayList<>());
        AgentResolver resolver = new AgentResolver(storage);

        assertNull(resolver.resolveAgentEnvironment("host", 8080));
        assertNull(resolver.resolveAgentId("env"));
        assertNull(resolver.resolveAgentIp("env"));
    }
}
