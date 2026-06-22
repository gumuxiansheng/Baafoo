package baafoo

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

// 测试用 mock server
func newMockServer(t *testing.T) (*httptest.Server, *mockHandler) {
	mux := http.NewServeMux()
	h := &mockHandler{}

	mux.HandleFunc("/__baafoo__/api/agent/register", h.handleRegister)
	mux.HandleFunc("/__baafoo__/api/agent/heartbeat", h.handleHeartbeat)
	mux.HandleFunc("/__baafoo__/api/agent/poll", h.handlePoll)
	mux.HandleFunc("/__baafoo__/api/agent/recordings", h.handleRecordings)

	server := httptest.NewServer(mux)
	t.Cleanup(server.Close)
	return server, h
}

type mockHandler struct {
	registerCount  int32
	heartbeatCount int32
	pollCount      int32
	recordingCount int32
	lastRecordings []RecordingEntry
}

func (h *mockHandler) handleRegister(w http.ResponseWriter, r *http.Request) {
	h.registerCount++
	resp := map[string]interface{}{
		"success": true,
		"code":    200,
		"message": "OK",
		"data": map[string]interface{}{
			"agentId":         "test-agent-001",
			"mode":            "record-and-stub",
			"pollIntervalSec": 5,
		},
		"timestamp": time.Now().UnixMilli(),
	}
	writeJSON(w, resp)
}

func (h *mockHandler) handleHeartbeat(w http.ResponseWriter, r *http.Request) {
	h.heartbeatCount++
	resp := map[string]interface{}{
		"success":   true,
		"code":      200,
		"message":   "OK",
		"data":      nil,
		"timestamp": time.Now().UnixMilli(),
	}
	writeJSON(w, resp)
}

func (h *mockHandler) handlePoll(w http.ResponseWriter, r *http.Request) {
	h.pollCount++
	rules := []Rule{
		{
			ID:       "rule-001",
			Name:     "test-rule",
			Protocol: "http",
			Enabled:  true,
			Priority: 100,
			Conditions: []MatchCondition{
				{Field: "method", Operator: "equals", Value: "GET"},
				{Field: "path", Operator: "prefix", Value: "/api/"},
			},
			Responses: []ResponseEntry{
				{StatusCode: 200, Body: `{"ok":true}`},
			},
		},
	}
	resp := map[string]interface{}{
		"success": true,
		"code":    200,
		"message": "OK",
		"data": map[string]interface{}{
			"rules":   rules,
			"mode":    "stub",
			"version": time.Now().UnixMilli(),
		},
		"timestamp": time.Now().UnixMilli(),
	}
	writeJSON(w, resp)
}

func (h *mockHandler) handleRecordings(w http.ResponseWriter, r *http.Request) {
	var entries []RecordingEntry
	json.NewDecoder(r.Body).Decode(&entries)
	h.recordingCount += int32(len(entries))
	h.lastRecordings = entries
	resp := map[string]interface{}{
		"success":   true,
		"code":      200,
		"message":   fmt.Sprintf("Recorded %d", len(entries)),
		"data":      nil,
		"timestamp": time.Now().UnixMilli(),
	}
	writeJSON(w, resp)
}

func writeJSON(w http.ResponseWriter, v interface{}) {
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(v)
}

func TestNew(t *testing.T) {
	c := New(Options{ServerURL: "http://localhost:8084"})
	if c == nil {
		t.Fatal("expected non-nil client")
	}
	if c.GetMode() != ModeRecordAndStub {
		t.Errorf("expected default mode record-and-stub, got %s", c.GetMode())
	}
	if len(c.GetRules()) != 0 {
		t.Errorf("expected empty rules initially")
	}
}

func TestRegister(t *testing.T) {
	server, h := newMockServer(t)

	c := New(Options{
		ServerURL:            server.URL,
		Environment:          "test-env",
		HeartbeatIntervalSec: 1,
		PollIntervalSec:      1,
	})

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	if err := c.Start(ctx); err != nil {
		t.Fatalf("Start failed: %v", err)
	}
	defer c.Close()

	if c.GetAgentID() != "test-agent-001" {
		t.Errorf("expected agentId test-agent-001, got %s", c.GetAgentID())
	}
	// register 返回 record-and-stub，但初始 poll 可能更新为 stub
	mode := c.GetMode()
	if mode != ModeRecordAndStub && mode != ModeStub {
		t.Errorf("expected mode record-and-stub or stub, got %s", mode)
	}
	if h.registerCount != 1 {
		t.Errorf("expected 1 register call, got %d", h.registerCount)
	}
}

func TestPollRules(t *testing.T) {
	server, _ := newMockServer(t)

	c := New(Options{
		ServerURL:            server.URL,
		HeartbeatIntervalSec: 100,
		PollIntervalSec:      1,
	})

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	if err := c.Start(ctx); err != nil {
		t.Fatalf("Start failed: %v", err)
	}
	defer c.Close()

	// 等待 poll 执行
	time.Sleep(2 * time.Second)

	rules := c.GetRules()
	if len(rules) != 1 {
		t.Fatalf("expected 1 rule, got %d", len(rules))
	}
	if rules[0].ID != "rule-001" {
		t.Errorf("expected rule ID rule-001, got %s", rules[0].ID)
	}
	if c.GetMode() != ModeStub {
		t.Errorf("expected mode stub after poll, got %s", c.GetMode())
	}
}

func TestReportRecording(t *testing.T) {
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

	// 上报录制数据
	entry := RecordingEntry{
		Protocol:           "http",
		Host:               "example.com",
		Port:               8080,
		Method:             "GET",
		Path:               "/api/test",
		ResponseStatusCode: 200,
		ResponseBody:       `{"ok":true}`,
	}
	c.ReportRecording(entry)

	// 触发 flush
	c.Close()

	if h.recordingCount != 1 {
		t.Errorf("expected 1 recording, got %d", h.recordingCount)
	}
	if len(h.lastRecordings) != 1 {
		t.Fatalf("expected 1 recording in last batch, got %d", len(h.lastRecordings))
	}
	if h.lastRecordings[0].Method != "GET" {
		t.Errorf("expected method GET, got %s", h.lastRecordings[0].Method)
	}
}

func TestMatchRequest(t *testing.T) {
	c := New(Options{})
	c.rules.Store([]Rule{
		{
			ID:      "rule-001",
			Enabled: true,
			Conditions: []MatchCondition{
				{Field: "method", Operator: "equals", Value: "GET"},
				{Field: "path", Operator: "prefix", Value: "/api/"},
			},
		},
		{
			ID:      "rule-002",
			Enabled: true,
			Conditions: []MatchCondition{
				{Field: "method", Operator: "equals", Value: "POST"},
				{Field: "path", Operator: "prefix", Value: "/api/"},
			},
		},
	})

	rule := c.MatchRequest("GET", "/api/orders")
	if rule == nil {
		t.Fatal("expected match for GET /api/orders")
	}
	if rule.ID != "rule-001" {
		t.Errorf("expected rule-001, got %s", rule.ID)
	}

	rule = c.MatchRequest("POST", "/api/orders")
	if rule == nil {
		t.Fatal("expected match for POST /api/orders")
	}
	if rule.ID != "rule-002" {
		t.Errorf("expected rule-002, got %s", rule.ID)
	}

	rule = c.MatchRequest("DELETE", "/api/orders")
	if rule != nil {
		t.Error("expected no match for DELETE /api/orders")
	}
}

func TestAuthHeaders(t *testing.T) {
	server, _ := newMockServer(t)

	c := New(Options{
		ServerURL:            server.URL,
		APIKey:               "test-api-key",
		HeartbeatIntervalSec: 100,
		PollIntervalSec:      100,
	})

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	if err := c.Start(ctx); err != nil {
		t.Fatalf("Start failed: %v", err)
	}
	defer c.Close()

	// 如果 API Key 认证有问题，register 会返回错误
	// 这里仅验证 Start 不报错
}
