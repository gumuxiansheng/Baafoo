package main

import (
	"context"
	"flag"
	"fmt"
	"log"
	"os"
	"os/signal"
	"syscall"

	baafoo "github.com/baafoo/sdk-go"
	"gopkg.in/yaml.v3"
)

func main() {
	configPath := flag.String("config", "proxy.yml", "Path to proxy configuration file")
	flag.Parse()

	// 加载配置
	cfg, err := loadConfig(*configPath)
	if err != nil {
		log.Fatalf("Failed to load config: %v", err)
	}

	if len(cfg.Proxy.Mappings) == 0 {
		log.Fatal("No proxy mappings configured")
	}

	log.Printf("Baafoo Proxy starting with %d mappings", len(cfg.Proxy.Mappings))

	// 创建 SDK 客户端
	sdkOpts := baafoo.Options{
		ServerURL:            cfg.Server.URL,
		Environment:         cfg.Agent.Environment,
		AppName:             cfg.Agent.AppName,
		APIKey:              cfg.Server.APIKey,
		Token:               cfg.Server.Token,
		HeartbeatIntervalSec: cfg.Agent.HeartbeatIntervalSec,
		PollIntervalSec:     cfg.Agent.PollIntervalSec,
	}
	if sdkOpts.ServerURL == "" {
		sdkOpts.ServerURL = "http://127.0.0.1:8084"
	}
	if sdkOpts.HeartbeatIntervalSec <= 0 {
		sdkOpts.HeartbeatIntervalSec = 15
	}
	if sdkOpts.PollIntervalSec <= 0 {
		sdkOpts.PollIntervalSec = 10
	}

	sdk := baafoo.New(sdkOpts)

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	// 启动 SDK
	if err := sdk.Start(ctx); err != nil {
		log.Printf("Warning: SDK start failed: %v (proxy will continue without server connection)", err)
	} else {
		log.Printf("SDK started: agentId=%s, mode=%s", sdk.GetAgentID(), sdk.GetMode())
	}
	defer sdk.Close()

	// 创建并启动代理
	proxy := NewProxy(cfg.Proxy.Mappings, sdk)
	if err := proxy.Start(ctx); err != nil {
		log.Fatalf("Failed to start proxy: %v", err)
	}

	// 等待退出信号
	sigCh := make(chan os.Signal, 1)
	signal.Notify(sigCh, syscall.SIGINT, syscall.SIGTERM)
	sig := <-sigCh
	log.Printf("Received signal %v, shutting down...", sig)
}

// loadConfig 加载 YAML 配置文件
func loadConfig(path string) (*Config, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, fmt.Errorf("read config file: %w", err)
	}

	var cfg Config
	if err := yaml.Unmarshal(data, &cfg); err != nil {
		return nil, fmt.Errorf("parse config: %w", err)
	}

	// 设置默认值
	for i := range cfg.Proxy.Mappings {
		if cfg.Proxy.Mappings[i].Protocol == "" {
			cfg.Proxy.Mappings[i].Protocol = "tcp"
		}
	}

	return &cfg, nil
}
