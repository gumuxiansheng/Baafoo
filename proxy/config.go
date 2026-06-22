package main

// Config Proxy 配置文件结构
type Config struct {
	Proxy   ProxyConfig   `yaml:"proxy"`
	Server  ServerConfig  `yaml:"server"`
	Agent   AgentConfig   `yaml:"agent"`
}

// ProxyConfig 代理配置
type ProxyConfig struct {
	Mappings []PortMapping `yaml:"mappings"`
}

// PortMapping 端口映射配置
type PortMapping struct {
	Listen   string `yaml:"listen"`   // 监听地址，如 127.0.0.1:15001
	Target   string `yaml:"target"`   // 目标地址，如 order-service:8080
	Protocol string `yaml:"protocol"` // 协议：http/tcp/kafka/pulsar/grpc
}

// ServerConfig Baafoo Server 连接配置
type ServerConfig struct {
	URL     string `yaml:"url"`
	APIKey  string `yaml:"apiKey"`
	Token   string `yaml:"token"`
}

// AgentConfig Agent 配置
type AgentConfig struct {
	Environment          string `yaml:"environment"`
	AppName              string `yaml:"appName"`
	HeartbeatIntervalSec int    `yaml:"heartbeatIntervalSec"`
	PollIntervalSec      int    `yaml:"pollIntervalSec"`
}
