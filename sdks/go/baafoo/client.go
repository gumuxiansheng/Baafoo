package baafoo

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/google/uuid"
)

// Mode 环境模式
type Mode string

const (
	ModeStub           Mode = "stub"
	ModeRecord         Mode = "record"
	ModeRecordAndStub  Mode = "record-and-stub"
	ModePassthrough    Mode = "passthrough"
)

// Options SDK 配置选项
type Options struct {
	// ServerURL Baafoo Server 地址，如 http://localhost:8084
	ServerURL string
	// Environment 环境名称，默认 default
	Environment string
	// AppName 应用名，用于生成 agentId
	AppName string
	// APIKey API Key 认证（可选）
	APIKey string
	// Token JWT Token 认证（可选）
	Token string
	// HeartbeatIntervalSec 心跳间隔，默认 15
	HeartbeatIntervalSec int
	// PollIntervalSec 轮询间隔，默认 10
	PollIntervalSec int
	// HTTPTimeout HTTP 超时，默认 30s
	HTTPTimeout time.Duration
}

// Client Baafoo Thin SDK 客户端
type Client struct {
	opts       Options
	httpClient *http.Client
	agentID    string

	rules atomic.Value // []Rule
	mode  atomic.Value // Mode

	recordingMu      sync.Mutex
	recordingBuffer  []RecordingEntry
	recordingFlushAt time.Time

	cancel context.CancelFunc
	wg     sync.WaitGroup
}

// apiResponse 统一响应包装
type apiResponse struct {
	Success   bool            `json:"success"`
	Code      int             `json:"code"`
	Message   string          `json:"message"`
	Data      json.RawMessage `json:"data"`
	Timestamp int64           `json:"timestamp"`
}

// registerRequest 注册请求体
type registerRequest struct {
	AgentID     string   `json:"agentId"`
	Environment string   `json:"environment"`
	Hostname    string   `json:"hostname"`
	Version     string   `json:"version"`
	Protocols   []string `json:"protocols"`
}

// registerResponse 注册响应体
type registerResponse struct {
	AgentID         string `json:"agentId"`
	Mode            string `json:"mode"`
	PollIntervalSec int    `json:"pollIntervalSec"`
}

// heartbeatRequest 心跳请求体
type heartbeatRequest struct {
	AgentID        string                 `json:"agentId"`
	Timestamp      int64                  `json:"timestamp"`
	PluginStatuses map[string]interface{} `json:"pluginStatuses"`
}

// pollResponse 轮询响应体
type pollResponse struct {
	Rules   []Rule `json:"rules"`
	Mode    string `json:"mode"`
	Version int64  `json:"version"`
}

// New 创建 Baafoo SDK 客户端
func New(opts Options) *Client {
	if opts.ServerURL == "" {
		opts.ServerURL = "http://127.0.0.1:8084"
	}
	opts.ServerURL = strings.TrimRight(opts.ServerURL, "/")
	if opts.Environment == "" {
		opts.Environment = "default"
	}
	if opts.HeartbeatIntervalSec <= 0 {
		opts.HeartbeatIntervalSec = 15
	}
	if opts.PollIntervalSec <= 0 {
		opts.PollIntervalSec = 10
	}
	if opts.HTTPTimeout <= 0 {
		opts.HTTPTimeout = 30 * time.Second
	}

	c := &Client{
		opts:       opts,
		httpClient: &http.Client{Timeout: opts.HTTPTimeout},
	}
	c.rules.Store([]Rule{})
	c.mode.Store(ModeRecordAndStub)
	return c
}

// Start 启动 SDK：注册 agent，然后启动心跳和轮询循环
func (c *Client) Start(ctx context.Context) error {
	// 持久化 agentId
	agentID, err := c.loadOrGenerateAgentID()
	if err != nil {
		return fmt.Errorf("failed to init agentId: %w", err)
	}
	c.agentID = agentID

	// 注册
	if err := c.register(ctx); err != nil {
		return fmt.Errorf("register failed: %w", err)
	}

	// 启动后台任务
	childCtx, cancel := context.WithCancel(ctx)
	c.cancel = cancel

	// 先做一次 poll，确保规则立即可用
	_ = c.pollRules(childCtx)

	c.wg.Add(3)
	go c.heartbeatLoop(childCtx)
	go c.pollLoop(childCtx)
	go c.recordingFlushLoop(childCtx)

	return nil
}

// Close 关闭 SDK，flush 录制数据
func (c *Client) Close() error {
	if c.cancel != nil {
		c.cancel()
		c.cancel = nil
	}
	c.wg.Wait()

	// flush 剩余录制数据
	c.flushRecordings()
	return nil
}

// GetAgentID 返回当前 agent ID
func (c *Client) GetAgentID() string {
	return c.agentID
}

// GetRules 返回本地缓存的规则列表
func (c *Client) GetRules() []Rule {
	if v, ok := c.rules.Load().([]Rule); ok {
		return v
	}
	return nil
}

// GetMode 返回当前环境模式
func (c *Client) GetMode() Mode {
	if v, ok := c.mode.Load().(Mode); ok {
		return v
	}
	return ModeRecordAndStub
}

// ReportRecording 上报单条录制数据（缓冲后批量上传）
func (c *Client) ReportRecording(entry RecordingEntry) {
	if entry.ID == "" {
		entry.ID = uuid.New().String()
	}
	if entry.RecordedAt == 0 {
		entry.RecordedAt = time.Now().UnixMilli()
	}
	if entry.AgentID == "" {
		entry.AgentID = c.agentID
	}

	c.recordingMu.Lock()
	c.recordingBuffer = append(c.recordingBuffer, entry)
	shouldFlush := len(c.recordingBuffer) >= 50
	c.recordingMu.Unlock()

	if shouldFlush {
		c.flushRecordings()
	}
}

// MatchRequest 在本地规则中匹配请求
func (c *Client) MatchRequest(method, path string) *Rule {
	rules := c.GetRules()
	for _, rule := range rules {
		if !rule.Enabled {
			continue
		}
		if matchRule(rule, method, path) {
			return &rule
		}
	}
	return nil
}

// matchRule 简单规则匹配
func matchRule(rule Rule, method, path string) bool {
	for _, cond := range rule.Conditions {
		switch cond.Field {
		case "method":
			if !matchOperator(cond.Operator, method, cond.Value) {
				return false
			}
		case "path":
			if !matchOperator(cond.Operator, path, cond.Value) {
				return false
			}
		}
	}
	return true
}

// matchOperator 操作符匹配
func matchOperator(operator, actual, expected string) bool {
	switch operator {
	case "equals", "=":
		return actual == expected
	case "contains":
		return strings.Contains(actual, expected)
	case "prefix":
		return strings.HasPrefix(actual, expected)
	case "suffix":
		return strings.HasSuffix(actual, expected)
	case "regex":
		// 简化实现，实际应使用 regexp
		return strings.Contains(actual, expected)
	case "exists":
		return actual != ""
	default:
		return actual == expected
	}
}

// --- 内部方法 ---

func (c *Client) register(ctx context.Context) error {
	hostname, _ := os.Hostname()
	if hostname == "" {
		hostname = "unknown"
	}

	reqBody := registerRequest{
		AgentID:     c.agentID,
		Environment: c.opts.Environment,
		Hostname:    hostname,
		Version:     "1.0.0",
		Protocols:   []string{"http"},
	}

	var resp registerResponse
	if err := c.doPost(ctx, "/agent/register", reqBody, &resp); err != nil {
		return err
	}

	if resp.AgentID != "" {
		c.agentID = resp.AgentID
	}
	if resp.Mode != "" {
		c.mode.Store(Mode(resp.Mode))
	}
	if resp.PollIntervalSec > 0 {
		c.opts.PollIntervalSec = resp.PollIntervalSec
	}

	return nil
}

func (c *Client) heartbeat(ctx context.Context) error {
	reqBody := heartbeatRequest{
		AgentID:        c.agentID,
		Timestamp:      time.Now().UnixMilli(),
		PluginStatuses: map[string]interface{}{},
	}
	return c.doPost(ctx, "/agent/heartbeat", reqBody, nil)
}

func (c *Client) pollRules(ctx context.Context) error {
	params := url.Values{}
	params.Set("agentId", c.agentID)
	params.Set("environment", c.opts.Environment)

	path := "/agent/poll?" + params.Encode()

	var resp pollResponse
	if err := c.doGet(ctx, path, &resp); err != nil {
		return err
	}

	c.rules.Store(resp.Rules)
	if resp.Mode != "" {
		c.mode.Store(Mode(resp.Mode))
	}
	return nil
}

func (c *Client) heartbeatLoop(ctx context.Context) {
	defer c.wg.Done()
	ticker := time.NewTicker(time.Duration(c.opts.HeartbeatIntervalSec) * time.Second)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			if err := c.heartbeat(ctx); err != nil {
				// 心跳失败静默，下次重试
				_ = err
			}
		}
	}
}

func (c *Client) pollLoop(ctx context.Context) {
	defer c.wg.Done()
	ticker := time.NewTicker(time.Duration(c.opts.PollIntervalSec) * time.Second)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			if err := c.pollRules(ctx); err != nil {
				_ = err
			}
		}
	}
}

func (c *Client) recordingFlushLoop(ctx context.Context) {
	defer c.wg.Done()
	ticker := time.NewTicker(5 * time.Second)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			c.flushRecordings()
		}
	}
}

func (c *Client) flushRecordings() {
	c.recordingMu.Lock()
	if len(c.recordingBuffer) == 0 {
		c.recordingMu.Unlock()
		return
	}
	batch := c.recordingBuffer
	c.recordingBuffer = nil
	c.recordingMu.Unlock()

	params := url.Values{}
	params.Set("agentId", c.agentID)
	params.Set("environment", c.opts.Environment)
	path := "/agent/recordings?" + params.Encode()

	if err := c.doPost(context.Background(), path, batch, nil); err != nil {
		// 上传失败，放回缓冲区
		c.recordingMu.Lock()
		c.recordingBuffer = append(batch, c.recordingBuffer...)
		c.recordingMu.Unlock()
	}
}

// --- HTTP 工具方法 ---

func (c *Client) doPost(ctx context.Context, path string, body interface{}, out interface{}) error {
	bodyBytes, err := json.Marshal(body)
	if err != nil {
		return fmt.Errorf("marshal request: %w", err)
	}

	reqURL := c.opts.ServerURL + "/__baafoo__/api" + path
	req, err := http.NewRequestWithContext(ctx, "POST", reqURL, bytes.NewReader(bodyBytes))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	c.setAuthHeader(req)

	resp, err := c.httpClient.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()

	respBody, err := io.ReadAll(resp.Body)
	if err != nil {
		return err
	}

	var apiResp apiResponse
	if err := json.Unmarshal(respBody, &apiResp); err != nil {
		return fmt.Errorf("unmarshal response: %w, body: %s", err, string(respBody))
	}

	if !apiResp.Success {
		return fmt.Errorf("api error: %s (code %d)", apiResp.Message, apiResp.Code)
	}

	if out != nil && len(apiResp.Data) > 0 {
		if err := json.Unmarshal(apiResp.Data, out); err != nil {
			return fmt.Errorf("unmarshal data: %w", err)
		}
	}
	return nil
}

func (c *Client) doGet(ctx context.Context, path string, out interface{}) error {
	reqURL := c.opts.ServerURL + "/__baafoo__/api" + path
	req, err := http.NewRequestWithContext(ctx, "GET", reqURL, nil)
	if err != nil {
		return err
	}
	c.setAuthHeader(req)

	resp, err := c.httpClient.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()

	if resp.StatusCode == 204 {
		return nil
	}

	respBody, err := io.ReadAll(resp.Body)
	if err != nil {
		return err
	}

	var apiResp apiResponse
	if err := json.Unmarshal(respBody, &apiResp); err != nil {
		return fmt.Errorf("unmarshal response: %w, body: %s", err, string(respBody))
	}

	if !apiResp.Success {
		return fmt.Errorf("api error: %s (code %d)", apiResp.Message, apiResp.Code)
	}

	if out != nil && len(apiResp.Data) > 0 {
		if err := json.Unmarshal(apiResp.Data, out); err != nil {
			return fmt.Errorf("unmarshal data: %w", err)
		}
	}
	return nil
}

func (c *Client) setAuthHeader(req *http.Request) {
	if c.opts.Token != "" {
		req.Header.Set("Authorization", "Bearer "+c.opts.Token)
	} else if c.opts.APIKey != "" {
		req.Header.Set("X-Api-Key", c.opts.APIKey)
	}
}

// --- AgentId 持久化 ---

func (c *Client) loadOrGenerateAgentID() (string, error) {
	dir := agentIDDir()
	if dir == "" {
		return uuid.New().String(), nil
	}

	namePart := c.opts.AppName
	if namePart == "" {
		namePart = "agent"
	}
	path := filepath.Join(dir, "baafoo-"+namePart+".id")

	data, err := os.ReadFile(path)
	if err == nil && len(data) > 0 {
		id := strings.TrimSpace(string(data))
		if id != "" {
			return id, nil
		}
	}

	// 生成新 ID
	id := uuid.New().String()
	if err := os.MkdirAll(dir, 0755); err != nil {
		return id, nil
	}
	_ = os.WriteFile(path, []byte(id), 0644)
	return id, nil
}

func agentIDDir() string {
	home, err := os.UserHomeDir()
	if err != nil {
		return ""
	}
	return filepath.Join(home, ".baafoo")
}

// 用于测试
var _ = runtime.GOOS
