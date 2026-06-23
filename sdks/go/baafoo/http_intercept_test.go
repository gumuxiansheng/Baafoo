package baafoo

import (
	"context"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

// 测试 HTTP 拦截 - stub 模式返回 mock 响应
func TestInterceptHTTPStub(t *testing.T) {
	// 创建 mock Baafoo Server
	server, _ := newMockServer(t)

	c := New(Options{
		ServerURL:            server.URL,
		HeartbeatIntervalSec: 100,
		PollIntervalSec:      100,
	})

	// 手动设置规则和模式
	c.rules.Store([]Rule{
		{
			ID:       "rule-001",
			Enabled:  true,
			Priority: 100,
			Conditions: []MatchCondition{
				{Field: "method", Operator: "equals", Value: "GET"},
				{Field: "path", Operator: "prefix", Value: "/api/"},
			},
			Responses: []ResponseEntry{
				{
					StatusCode: 200,
					Headers:    map[string]string{"Content-Type": "application/json"},
					Body:       `{"mocked":true}`,
				},
			},
		},
	})
	c.mode.Store(ModeStub)

	// 启用 HTTP 拦截
	c.InterceptHTTP()
	defer c.RestoreHTTP()

	// 发送请求 — 应该返回 mock 响应
	resp, err := http.Get("http://example.com/api/orders")
	if err != nil {
		t.Fatalf("Request failed: %v", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != 200 {
		t.Errorf("expected status 200, got %d", resp.StatusCode)
	}

	body, _ := io.ReadAll(resp.Body)
	if !strings.Contains(string(body), `"mocked":true`) {
		t.Errorf("expected mocked body, got: %s", string(body))
	}

	ct := resp.Header.Get("Content-Type")
	if ct != "application/json" {
		t.Errorf("expected Content-Type application/json, got %s", ct)
	}
}

// 测试 HTTP 拦截 - 未匹配规则时 passthrough
func TestInterceptHTTPPassthrough(t *testing.T) {
	// 创建后端服务器
	backend := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/plain")
		w.WriteHeader(200)
		w.Write([]byte("real response"))
	}))
	defer backend.Close()

	c := New(Options{})

	// 无规则，passthrough 模式
	c.rules.Store([]Rule{})
	c.mode.Store(ModePassthrough)

	c.InterceptHTTP()
	defer c.RestoreHTTP()

	resp, err := http.Get(backend.URL + "/api/test")
	if err != nil {
		t.Fatalf("Request failed: %v", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != 200 {
		t.Errorf("expected status 200, got %d", resp.StatusCode)
	}

	body, _ := io.ReadAll(resp.Body)
	if string(body) != "real response" {
		t.Errorf("expected 'real response', got: %s", string(body))
	}
}

// 测试 HTTP 拦截 - record 模式录制请求
func TestInterceptHTTPRecord(t *testing.T) {
	server, h := newMockServer(t)

	c := New(Options{
		ServerURL:            server.URL,
		HeartbeatIntervalSec: 100,
		PollIntervalSec:      100,
	})

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	if err := c.Start(ctx); err != nil {
		t.Fatalf("Start failed: %v", err)
	}
	defer c.Close()

	// Start 后覆盖模式为 record
	c.mode.Store(ModeRecord)
	c.rules.Store([]Rule{})

	c.InterceptHTTP()
	defer c.RestoreHTTP()

	// 创建后端服务器
	backend := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/plain")
		w.WriteHeader(200)
		w.Write([]byte("recorded response"))
	}))
	defer backend.Close()

	// 发送请求
	resp, err := http.Get(backend.URL + "/api/test")
	if err != nil {
		t.Fatalf("Request failed: %v", err)
	}
	resp.Body.Close()

	// 关闭 SDK 以 flush 录制
	c.Close()

	if h.recordingCount == 0 {
		t.Error("expected recordings to be uploaded")
	}
}

// 测试 HTTP 拦截 - record-and-stub 模式
func TestInterceptHTTPRecordAndStub(t *testing.T) {
	server, h := newMockServer(t)

	c := New(Options{
		ServerURL:            server.URL,
		HeartbeatIntervalSec: 100,
		PollIntervalSec:      100,
	})

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	if err := c.Start(ctx); err != nil {
		t.Fatalf("Start failed: %v", err)
	}
	defer c.Close()

	// Start 后覆盖规则和模式
	c.rules.Store([]Rule{
		{
			ID:      "rule-001",
			Enabled: true,
			Conditions: []MatchCondition{
				{Field: "method", Operator: "equals", Value: "GET"},
				{Field: "path", Operator: "prefix", Value: "/api/"},
			},
			Responses: []ResponseEntry{
				{StatusCode: 200, Body: `{"stub":true}`},
			},
		},
	})
	c.mode.Store(ModeRecordAndStub)

	c.InterceptHTTP()
	defer c.RestoreHTTP()

	// 匹配规则的请求 — 返回 mock
	resp, err := http.Get("http://example.com/api/orders")
	if err != nil {
		t.Fatalf("Request failed: %v", err)
	}
	body, _ := io.ReadAll(resp.Body)
	resp.Body.Close()

	if !strings.Contains(string(body), `"stub":true`) {
		t.Errorf("expected stub response, got: %s", string(body))
	}

	// 关闭以 flush
	c.Close()

	// 应该有录制（record-and-stub 模式下匹配规则也录制）
	if h.recordingCount == 0 {
		t.Error("expected recordings in record-and-stub mode")
	}
}

// 测试 InterceptHTTPClient 对指定 client 拦截
func TestInterceptHTTPClient(t *testing.T) {
	c := New(Options{})
	c.rules.Store([]Rule{
		{
			ID:      "rule-001",
			Enabled: true,
			Conditions: []MatchCondition{
				{Field: "method", Operator: "equals", Value: "GET"},
				{Field: "path", Operator: "prefix", Value: "/api/"},
			},
			Responses: []ResponseEntry{
				{StatusCode: 200, Body: "intercepted"},
			},
		},
	})
	c.mode.Store(ModeStub)

	client := &http.Client{}
	c.InterceptHTTPClient(client)

	resp, err := client.Get("http://example.com/api/test")
	if err != nil {
		t.Fatalf("Request failed: %v", err)
	}
	defer resp.Body.Close()

	body, _ := io.ReadAll(resp.Body)
	if string(body) != "intercepted" {
		t.Errorf("expected 'intercepted', got: %s", string(body))
	}
}

// 测试延迟注入
func TestInterceptHTTPDelay(t *testing.T) {
	c := New(Options{})
	c.rules.Store([]Rule{
		{
			ID:      "rule-001",
			Enabled: true,
			Conditions: []MatchCondition{
				{Field: "method", Operator: "equals", Value: "GET"},
			},
			Responses: []ResponseEntry{
				{StatusCode: 200, Body: "delayed", DelayMs: 50},
			},
		},
	})
	c.mode.Store(ModeStub)

	c.InterceptHTTP()
	defer c.RestoreHTTP()

	start := time.Now()
	resp, err := http.Get("http://example.com/test")
	elapsed := time.Since(start)
	if err != nil {
		t.Fatalf("Request failed: %v", err)
	}
	defer resp.Body.Close()

	if elapsed < 40*time.Millisecond {
		t.Errorf("expected delay >= 50ms, got %v", elapsed)
	}
}

// 测试便捷方法 HTTPGet
func TestHTTPGet(t *testing.T) {
	c := New(Options{})
	c.rules.Store([]Rule{
		{
			ID:      "rule-001",
			Enabled: true,
			Conditions: []MatchCondition{
				{Field: "method", Operator: "equals", Value: "GET"},
			},
			Responses: []ResponseEntry{
				{StatusCode: 200, Body: "convenience"},
			},
		},
	})
	c.mode.Store(ModeStub)

	c.InterceptHTTP()
	defer c.RestoreHTTP()

	ctx := context.Background()
	resp, err := c.HTTPGet(ctx, "http://example.com/test")
	if err != nil {
		t.Fatalf("HTTPGet failed: %v", err)
	}
	defer resp.Body.Close()

	body, _ := io.ReadAll(resp.Body)
	if string(body) != "convenience" {
		t.Errorf("expected 'convenience', got: %s", string(body))
	}
}
