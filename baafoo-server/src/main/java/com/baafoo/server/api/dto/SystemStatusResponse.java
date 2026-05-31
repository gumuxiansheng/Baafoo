package com.baafoo.server.api.dto;

public class SystemStatusResponse {
    public String version;
    public int rules;
    public int environments;
    public int agents;
    public long onlineAgents;
    public int scenes;
    public long uptime;
    public boolean authEnabled;

    public SystemStatusResponse version(String v) { this.version = v; return this; }
    public SystemStatusResponse rules(int v) { this.rules = v; return this; }
    public SystemStatusResponse environments(int v) { this.environments = v; return this; }
    public SystemStatusResponse agents(int v) { this.agents = v; return this; }
    public SystemStatusResponse onlineAgents(long v) { this.onlineAgents = v; return this; }
    public SystemStatusResponse scenes(int v) { this.scenes = v; return this; }
    public SystemStatusResponse uptime(long v) { this.uptime = v; return this; }
    public SystemStatusResponse authEnabled(boolean v) { this.authEnabled = v; return this; }
}
