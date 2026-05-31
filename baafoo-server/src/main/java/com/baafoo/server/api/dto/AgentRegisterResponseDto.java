package com.baafoo.server.api.dto;

public class AgentRegisterResponseDto {
    public String agentId;
    public String mode;
    public int pollIntervalSec;

    public AgentRegisterResponseDto agentId(String v) { this.agentId = v; return this; }
    public AgentRegisterResponseDto mode(String v) { this.mode = v; return this; }
    public AgentRegisterResponseDto pollIntervalSec(int v) { this.pollIntervalSec = v; return this; }
}
