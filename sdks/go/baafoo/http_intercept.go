package baafoo

import (
	"bytes"
	"context"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"
)

// baafooRoundTripper 自定义 RoundTripper，拦截所有 HTTP 请求
type baafooRoundTripper struct {
	sdk       *Client
	transport http.RoundTripper
}

// InterceptHTTP 替换 http.DefaultTransport 为 Baafoo RoundTripper
// 调用后，所有 http.Get/http.Post/http.DefaultClient 请求都会经过 Baafoo
func (c *Client) InterceptHTTP() {
	original := http.DefaultTransport
	if original == nil {
		original = http.DefaultTransport
	}
	rt := &baafooRoundTripper{
		sdk:       c,
		transport: original.(http.RoundTripper),
	}
	http.DefaultTransport = rt
}

// InterceptHTTPClient 对指定的 http.Client 启用拦截
func (c *Client) InterceptHTTPClient(client *http.Client) {
	original := client.Transport
	if original == nil {
		original = http.DefaultTransport
	}
	client.Transport = &baafooRoundTripper{
		sdk:       c,
		transport: original.(http.RoundTripper),
	}
}

// RestoreHTTP 恢复原始的 http.DefaultTransport
func (c *Client) RestoreHTTP() {
	if rt, ok := http.DefaultTransport.(*baafooRoundTripper); ok {
		http.DefaultTransport = rt.transport
	}
}

// RoundTrip 实现 http.RoundTripper 接口
func (rt *baafooRoundTripper) RoundTrip(req *http.Request) (*http.Response, error) {
	mode := rt.sdk.GetMode()

	// record-all 模式：所有请求都 passthrough + 录制（不返回 mock）
	if mode == ModeRecordAll {
		startTime := time.Now()
		resp, err := rt.transport.RoundTrip(req)
		if err != nil {
			return resp, err
		}
		duration := time.Since(startTime).Milliseconds()
		rt.recordRequest(req, resp, duration)
		return resp, nil
	}

	// 在 stub/record-and-stub 模式下尝试匹配规则
	if mode == ModeStub || mode == ModeRecordAndStub {
		if rule := rt.sdk.MatchRequest(req.Method, req.URL.Path); rule != nil {
			// 匹配到规则，返回 mock 响应
			resp := rt.buildMockResponse(req, rule)
			// 如果是录制模式，也录制请求
			if mode == ModeRecordAndStub {
				rt.recordRequest(req, resp, 0)
			}
			return resp, nil
		}
	}

	// 未匹配规则：passthrough 或 record 模式
	startTime := time.Now()
	resp, err := rt.transport.RoundTrip(req)
	if err != nil {
		return resp, err
	}

	// 录制模式：记录请求和响应
	if mode == ModeRecord || mode == ModeRecordAndStub {
		duration := time.Since(startTime).Milliseconds()
		rt.recordRequest(req, resp, duration)
	}

	return resp, nil
}

// buildMockResponse 根据规则构建 mock 响应
func (rt *baafooRoundTripper) buildMockResponse(req *http.Request, rule *Rule) *http.Response {
	if len(rule.Responses) == 0 {
		// 无响应配置，返回 200 空响应
		return &http.Response{
			StatusCode: 200,
			Header:     make(http.Header),
			Body:       http.NoBody,
			Request:    req,
		}
	}

	resp := rule.Responses[0]
	statusCode := resp.StatusCode
	if statusCode == 0 {
		statusCode = 200
	}

	header := make(http.Header)
	for k, v := range resp.Headers {
		header.Set(k, v)
	}

	// 延迟
	if resp.DelayMs > 0 {
		time.Sleep(time.Duration(resp.DelayMs) * time.Millisecond)
	}

	body := resp.Body
	var bodyReader io.ReadCloser
	if body != "" {
		bodyReader = io.NopCloser(strings.NewReader(body))
	} else {
		bodyReader = http.NoBody
	}

	return &http.Response{
		StatusCode: statusCode,
		Header:     header,
		Body:       bodyReader,
		Request:    req,
	}
}

// recordRequest 录制 HTTP 请求/响应
func (rt *baafooRoundTripper) recordRequest(req *http.Request, resp *http.Response, durationMs int64) {
	// 读取请求体
	var reqBody string
	if req.Body != nil {
		bodyBytes, err := io.ReadAll(req.Body)
		if err == nil {
			reqBody = string(bodyBytes)
			// 恢复 body 供后续使用
			req.Body = io.NopCloser(bytes.NewReader(bodyBytes))
		}
	}

	// 读取响应体
	var respBody string
	var respBodyBytes []byte
	if resp != nil && resp.Body != nil {
		var err error
		respBodyBytes, err = io.ReadAll(resp.Body)
		if err == nil {
			respBody = string(respBodyBytes)
			// 恢复 body 供后续使用
			resp.Body = io.NopCloser(bytes.NewReader(respBodyBytes))
		}
	}

	// 解析 host 和 port
	host := req.URL.Hostname()
	port := 80
	if p := req.URL.Port(); p != "" {
		fmt.Sscanf(p, "%d", &port)
	} else if req.URL.Scheme == "https" {
		port = 443
	}

	// 请求头
	reqHeaders := make(map[string]string)
	for k, v := range req.Header {
		if len(v) > 0 {
			reqHeaders[k] = v[0]
		}
	}

	// 响应头
	respHeaders := make(map[string]string)
	if resp != nil {
		for k, v := range resp.Header {
			if len(v) > 0 {
				respHeaders[k] = v[0]
			}
		}
	}

	statusCode := 0
	if resp != nil {
		statusCode = resp.StatusCode
	}

	entry := RecordingEntry{
		Protocol:           "http",
		Host:               host,
		Port:               port,
		Method:             req.Method,
		Path:               req.URL.Path,
		RequestHeaders:     reqHeaders,
		RequestBody:        reqBody,
		ResponseStatusCode: statusCode,
		ResponseHeaders:    respHeaders,
		ResponseBody:       respBody,
		ResponseTimeMs:     durationMs,
		Direction:          "request",
	}

	rt.sdk.ReportRecording(entry)
}

// --- 便捷方法 ---

// HTTPGet 发送 GET 请求（经过 Baafoo 拦截）
func (c *Client) HTTPGet(ctx context.Context, urlStr string) (*http.Response, error) {
	req, err := http.NewRequestWithContext(ctx, "GET", urlStr, nil)
	if err != nil {
		return nil, err
	}
	return c.httpClient.Do(req)
}

// HTTPPost 发送 POST 请求（经过 Baafoo 拦截）
func (c *Client) HTTPPost(ctx context.Context, urlStr, contentType string, body io.Reader) (*http.Response, error) {
	req, err := http.NewRequestWithContext(ctx, "POST", urlStr, body)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", contentType)
	return c.httpClient.Do(req)
}

// MatchRequestWithUrl 根据 URL 解析 host/port/path 后匹配规则
func (c *Client) MatchRequestWithUrl(method, urlStr string) *Rule {
	parsed, err := url.Parse(urlStr)
	if err != nil {
		return nil
	}
	return c.MatchRequest(method, parsed.Path)
}
