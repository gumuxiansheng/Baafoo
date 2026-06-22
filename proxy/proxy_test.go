package main

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
	"time"

	baafoo "github.com/baafoo/sdk-go"
)

// 测试 HTTP 代理转发
func TestProxyHTTPForward(t *testing.T) {
	// 创建后端 HTTP 服务器
	backend := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(200)
		w.Write([]byte(`{"message":"hello from backend"}`))
	}))
	defer backend.Close()

	// 解析后端地址
	backendHost, backendPort, _ := net.SplitHostPort(backend.Listener.Addr().String())
	var bp int
	fmt.Sscanf(backendPort, "%d", &bp)

	// 创建 mock Baafoo Server
	baafooServer := newMockBaafooServer(t)
	defer baafooServer.Close()

	// 创建 SDK
	sdk := baafoo.New(baafoo.Options{
		ServerURL:            baafooServer.URL,
		HeartbeatIntervalSec: 100,
		PollIntervalSec:     100,
	})
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	if err := sdk.Start(ctx); err != nil {
		t.Fatalf("SDK start failed: %v", err)
	}
	defer sdk.Close()

	// 创建代理
	proxy := NewProxy([]PortMapping{
		{
			Listen:   "127.0.0.1:0",
			Target:   backend.Listener.Addr().String(),
			Protocol: "http",
		},
	}, sdk)

	// 找一个可用端口
	ln, _ := net.Listen("tcp", "127.0.0.1:0")
	proxyAddr := ln.Addr().String()
	ln.Close()

	proxy.mappings[0].Listen = proxyAddr
	go proxy.listen(ctx, proxy.mappings[0])

	time.Sleep(100 * time.Millisecond)

	// 通过代理发送请求（禁用 keep-alive 以确保连接关闭）
	httpClient := &http.Client{
		Transport: &http.Transport{
			DisableKeepAlives: true,
		},
	}
	resp, err := httpClient.Get("http://" + proxyAddr + "/api/test")
	if err != nil {
		t.Fatalf("Request through proxy failed: %v", err)
	}

	if resp.StatusCode != 200 {
		t.Errorf("expected status 200, got %d", resp.StatusCode)
	}

	body, _ := io.ReadAll(resp.Body)
	resp.Body.Close()

	if !strings.Contains(string(body), "hello from backend") {
		t.Errorf("unexpected body: %s", string(body))
	}

	// 等待代理 goroutine 完成录制
	time.Sleep(500 * time.Millisecond)

	// 关闭 SDK 以 flush 录制
	sdk.Close()

	// 验证录制
	recordings := baafooServer.getRecordings()
	if len(recordings) == 0 {
		t.Error("expected recordings to be uploaded")
	}

	_ = backendHost
	_ = bp
}

// 测试 TCP 代理转发
func TestProxyTCPForward(t *testing.T) {
	// 创建后端 TCP 服务器
	backendLn, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("Failed to listen: %v", err)
	}
	defer backendLn.Close()

	go func() {
		for {
			conn, err := backendLn.Accept()
			if err != nil {
				return
			}
			go func(c net.Conn) {
				defer c.Close()
				buf := make([]byte, 1024)
				n, _ := c.Read(buf)
				// 回显（单次写入避免分片）
				response := append([]byte("echo: "), buf[:n]...)
				c.Write(response)
			}(conn)
		}
	}()

	// 创建 mock Baafoo Server
	baafooServer := newMockBaafooServer(t)
	defer baafooServer.Close()

	// 创建 SDK
	sdk := baafoo.New(baafoo.Options{
		ServerURL:            baafooServer.URL,
		HeartbeatIntervalSec: 100,
		PollIntervalSec:     100,
	})
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	if err := sdk.Start(ctx); err != nil {
		t.Fatalf("SDK start failed: %v", err)
	}
	defer sdk.Close()

	// 创建代理
	proxy := NewProxy([]PortMapping{
		{
			Listen:   "127.0.0.1:0",
			Target:   backendLn.Addr().String(),
			Protocol: "tcp",
		},
	}, sdk)

	// 找一个可用端口
	ln, _ := net.Listen("tcp", "127.0.0.1:0")
	proxyAddr := ln.Addr().String()
	ln.Close()

	proxy.mappings[0].Listen = proxyAddr
	go proxy.listen(ctx, proxy.mappings[0])

	time.Sleep(100 * time.Millisecond)

	// 通过代理发送 TCP 数据
	conn, err := net.Dial("tcp", proxyAddr)
	if err != nil {
		t.Fatalf("Failed to connect to proxy: %v", err)
	}
	defer conn.Close()

	conn.Write([]byte("hello tcp"))
	buf := make([]byte, 1024)
	n, _ := conn.Read(buf)

	response := string(buf[:n])
	if !strings.Contains(response, "echo: hello tcp") {
		t.Errorf("unexpected response: %s", response)
	}

	// 等待录制上传
	time.Sleep(1 * time.Second)

	// 关闭 SDK 以 flush 录制
	sdk.Close()

	// 验证录制
	recordings := baafooServer.getRecordings()
	if len(recordings) == 0 {
		t.Error("expected TCP recordings to be uploaded")
	}
}

// 测试配置加载
func TestLoadConfig(t *testing.T) {
	yamlContent := `
proxy:
  mappings:
    - listen: "127.0.0.1:15001"
      target: "order-service:8080"
      protocol: http
    - listen: "127.0.0.1:15002"
      target: "kafka-broker:9092"
server:
  url: "http://localhost:8084"
agent:
  environment: "test"
  appName: "my-proxy"
`

	tmpFile := t.TempDir() + "/proxy.yml"
	if err := writeFile(tmpFile, yamlContent); err != nil {
		t.Fatalf("Failed to write config: %v", err)
	}

	cfg, err := loadConfig(tmpFile)
	if err != nil {
		t.Fatalf("loadConfig failed: %v", err)
	}

	if len(cfg.Proxy.Mappings) != 2 {
		t.Errorf("expected 2 mappings, got %d", len(cfg.Proxy.Mappings))
	}
	if cfg.Proxy.Mappings[0].Protocol != "http" {
		t.Errorf("expected protocol http, got %s", cfg.Proxy.Mappings[0].Protocol)
	}
	if cfg.Proxy.Mappings[1].Protocol != "tcp" {
		t.Errorf("expected default protocol tcp, got %s", cfg.Proxy.Mappings[1].Protocol)
	}
	if cfg.Server.URL != "http://localhost:8084" {
		t.Errorf("expected server URL http://localhost:8084, got %s", cfg.Server.URL)
	}
	if cfg.Agent.Environment != "test" {
		t.Errorf("expected environment test, got %s", cfg.Agent.Environment)
	}
}

// 测试 HTTP 请求解析
func TestParseHTTPRequest(t *testing.T) {
	raw := "GET /api/orders/123 HTTP/1.1\r\nHost: example.com\r\nContent-Type: application/json\r\n\r\n{\"id\":123}"
	method, path, headers, body := parseHTTPRequest([]byte(raw))

	if method != "GET" {
		t.Errorf("expected method GET, got %s", method)
	}
	if path != "/api/orders/123" {
		t.Errorf("expected path /api/orders/123, got %s", path)
	}
	if headers["Host"] != "example.com" {
		t.Errorf("expected Host header example.com, got %s", headers["Host"])
	}
	if body != `{"id":123}` {
		t.Errorf("expected body {\"id\":123}, got %s", body)
	}
}

// 测试 HTTP 响应解析
func TestParseHTTPResponse(t *testing.T) {
	raw := "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n{\"status\":\"ok\"}"
	statusCode, headers, body := parseHTTPResponse([]byte(raw))

	if statusCode != 200 {
		t.Errorf("expected status 200, got %d", statusCode)
	}
	if headers["Content-Type"] != "application/json" {
		t.Errorf("expected Content-Type application/json, got %s", headers["Content-Type"])
	}
	if body != `{"status":"ok"}` {
		t.Errorf("expected body {\"status\":\"ok\"}, got %s", body)
	}
}

// 测试 hex 编码
func TestToHex(t *testing.T) {
	result := toHex([]byte{0x48, 0x65, 0x6c, 0x6c, 0x6f})
	if result != "48656c6c6f" {
		t.Errorf("expected 48656c6c6f, got %s", result)
	}
}

// --- Mock Baafoo Server ---

type mockBaafooServer struct {
	*httptest.Server
	mu          chan struct{}
	recordings  []map[string]interface{}
}

func newMockBaafooServer(t *testing.T) *mockBaafooServer {
	m := &mockBaafooServer{
		mu: make(chan struct{}, 1),
	}
	m.mu <- struct{}{}

	mux := http.NewServeMux()
	mux.HandleFunc("/__baafoo__/api/agent/register", m.handleRegister)
	mux.HandleFunc("/__baafoo__/api/agent/heartbeat", m.handleHeartbeat)
	mux.HandleFunc("/__baafoo__/api/agent/poll", m.handlePoll)
	mux.HandleFunc("/__baafoo__/api/agent/recordings", m.handleRecordings)

	m.Server = httptest.NewServer(mux)
	t.Cleanup(m.Close)
	return m
}

func (m *mockBaafooServer) handleRegister(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, map[string]interface{}{
		"success": true,
		"code":    200,
		"message": "OK",
		"data": map[string]interface{}{
			"agentId":         "proxy-test-001",
			"mode":            "record-and-stub",
			"pollIntervalSec": 100,
		},
		"timestamp": time.Now().UnixMilli(),
	})
}

func (m *mockBaafooServer) handleHeartbeat(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, map[string]interface{}{
		"success":   true,
		"code":      200,
		"message":   "OK",
		"data":      nil,
		"timestamp": time.Now().UnixMilli(),
	})
}

func (m *mockBaafooServer) handlePoll(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, map[string]interface{}{
		"success": true,
		"code":    200,
		"message": "OK",
		"data": map[string]interface{}{
			"rules":   []interface{}{},
			"mode":    "record-and-stub",
			"version": time.Now().UnixMilli(),
		},
		"timestamp": time.Now().UnixMilli(),
	})
}

func (m *mockBaafooServer) handleRecordings(w http.ResponseWriter, r *http.Request) {
	body, _ := io.ReadAll(r.Body)
	var entries []map[string]interface{}
	json.Unmarshal(body, &entries)

	<-m.mu
	m.recordings = append(m.recordings, entries...)
	m.mu <- struct{}{}

	writeJSON(w, map[string]interface{}{
		"success":   true,
		"code":      200,
		"message":   fmt.Sprintf("Recorded %d", len(entries)),
		"data":      nil,
		"timestamp": time.Now().UnixMilli(),
	})
}

func (m *mockBaafooServer) getRecordings() []map[string]interface{} {
	<-m.mu
	result := m.recordings
	m.mu <- struct{}{}
	return result
}

func writeJSON(w http.ResponseWriter, v interface{}) {
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(v)
}

func writeFile(path, content string) error {
	return os.WriteFile(path, []byte(content), 0644)
}
