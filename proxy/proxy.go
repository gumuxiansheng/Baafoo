package main

import (
	"context"
	"fmt"
	"log"
	"net"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	baafoo "github.com/baafoo/sdk-go"
)

// Proxy TCP 透明代理
type Proxy struct {
	mappings []PortMapping
	sdk      *baafoo.Client
	mode     atomic.Value // baafoo.Mode

	// 录制缓冲
	recMu  sync.Mutex
	recBuf []baafoo.RecordingEntry
}

// NewProxy 创建代理实例
func NewProxy(mappings []PortMapping, sdk *baafoo.Client) *Proxy {
	p := &Proxy{
		mappings: mappings,
		sdk:      sdk,
	}
	p.mode.Store(baafoo.ModeRecordAndStub)
	return p
}

// Start 启动所有端口监听
func (p *Proxy) Start(ctx context.Context) error {
	// 更新当前模式
	p.mode.Store(p.sdk.GetMode())

	for _, m := range p.mappings {
		go p.listen(ctx, m)
	}
	return nil
}

// listen 监听单个端口
func (p *Proxy) listen(ctx context.Context, m PortMapping) {
	ln, err := net.Listen("tcp", m.Listen)
	if err != nil {
		log.Printf("Failed to listen on %s: %v", m.Listen, err)
		return
	}
	log.Printf("Listening on %s -> %s (%s)", m.Listen, m.Target, m.Protocol)

	for {
		select {
		case <-ctx.Done():
			ln.Close()
			return
		default:
		}

		conn, err := ln.Accept()
		if err != nil {
			select {
			case <-ctx.Done():
				ln.Close()
				return
			default:
			}
			log.Printf("Accept error on %s: %v", m.Listen, err)
			continue
		}

		go p.handleConn(ctx, conn, m)
	}
}

// handleConn 处理单个连接
func (p *Proxy) handleConn(ctx context.Context, clientConn net.Conn, m PortMapping) {
	defer clientConn.Close()

	sessionID := generateSessionID()
	startTime := time.Now()

	switch strings.ToLower(m.Protocol) {
	case "http", "https":
		p.handleHTTP(ctx, clientConn, m, sessionID, startTime)
	default:
		p.handleTCP(ctx, clientConn, m, sessionID, startTime)
	}
}

// handleHTTP 处理 HTTP 协议
func (p *Proxy) handleHTTP(ctx context.Context, clientConn net.Conn, m PortMapping, sessionID string, startTime time.Time) {
	// HTTP 需要解析请求，所以用 http.Server 的方式更合适
	// 但作为透明代理，我们直接在 TCP 层做双向转发 + 录制

	// 先尝试连接后端
	backendConn, err := net.DialTimeout("tcp", m.Target, 30*time.Second)
	if err != nil {
		log.Printf("Failed to connect to backend %s: %v", m.Target, err)
		return
	}
	defer backendConn.Close()

	// 双向转发 + 录制
	var reqBuf, respBuf []byte
	var wg sync.WaitGroup
	wg.Add(2)

	// client -> backend (录制请求)
	// 当 client 关闭时，关闭 backend 连接以解除 backend->client 的阻塞
	go func() {
		defer wg.Done()
		defer backendConn.Close()
		buf := make([]byte, 32*1024)
		for {
			n, err := clientConn.Read(buf)
			if n > 0 {
				reqBuf = append(reqBuf, buf[:n]...)
				backendConn.Write(buf[:n])
			}
			if err != nil {
				break
			}
		}
	}()

	// backend -> client (录制响应)
	// 当 backend 关闭时，关闭 client 连接以解除 client->backend 的阻塞
	go func() {
		defer wg.Done()
		defer clientConn.Close()
		buf := make([]byte, 32*1024)
		for {
			n, err := backendConn.Read(buf)
			if n > 0 {
				respBuf = append(respBuf, buf[:n]...)
				clientConn.Write(buf[:n])
			}
			if err != nil {
				break
			}
		}
	}()

	wg.Wait()

	// 录制
	duration := time.Since(startTime).Milliseconds()
	p.recordHTTP(m, sessionID, reqBuf, respBuf, duration)
}

// handleTCP 处理 TCP 协议
func (p *Proxy) handleTCP(ctx context.Context, clientConn net.Conn, m PortMapping, sessionID string, startTime time.Time) {
	backendConn, err := net.DialTimeout("tcp", m.Target, 30*time.Second)
	if err != nil {
		log.Printf("Failed to connect to backend %s: %v", m.Target, err)
		return
	}
	defer backendConn.Close()

	var reqBuf, respBuf []byte
	var wg sync.WaitGroup
	wg.Add(2)

	// client -> backend
	go func() {
		defer wg.Done()
		defer backendConn.Close()
		buf := make([]byte, 32*1024)
		for {
			n, err := clientConn.Read(buf)
			if n > 0 {
				reqBuf = append(reqBuf, buf[:n]...)
				backendConn.Write(buf[:n])
			}
			if err != nil {
				break
			}
		}
	}()

	// backend -> client
	go func() {
		defer wg.Done()
		defer clientConn.Close()
		buf := make([]byte, 32*1024)
		for {
			n, err := backendConn.Read(buf)
			if n > 0 {
				respBuf = append(respBuf, buf[:n]...)
				clientConn.Write(buf[:n])
			}
			if err != nil {
				break
			}
		}
	}()

	wg.Wait()

	duration := time.Since(startTime).Milliseconds()
	p.recordTCP(m, sessionID, reqBuf, respBuf, duration)
}

// recordHTTP 录制 HTTP 请求/响应
func (p *Proxy) recordHTTP(m PortMapping, sessionID string, reqBuf, respBuf []byte, durationMs int64) {
	// 检查模式：只在 record/record-and-stub/record-all 模式下录制
	mode := p.sdk.GetMode()
	if mode != baafoo.ModeRecord && mode != baafoo.ModeRecordAndStub && mode != baafoo.ModeRecordAll {
		return
	}

	if len(reqBuf) == 0 && len(respBuf) == 0 {
		return
	}

	method, reqPath, reqHeaders, reqBody := parseHTTPRequest(reqBuf)
	statusCode, respHeaders, respBody := parseHTTPResponse(respBuf)

	host, port := splitHostPort(m.Target, 80)

	entry := baafoo.RecordingEntry{
		Protocol:           "http",
		Host:               host,
		Port:               port,
		Method:             method,
		Path:               reqPath,
		RequestHeaders:     reqHeaders,
		RequestBody:        reqBody,
		ResponseStatusCode: statusCode,
		ResponseHeaders:    respHeaders,
		ResponseBody:       respBody,
		ResponseTimeMs:     durationMs,
		Direction:          "request",
		SessionID:          sessionID,
		DurationMs:        durationMs,
	}

	p.sdk.ReportRecording(entry)
}

// recordTCP 录制 TCP 请求/响应
func (p *Proxy) recordTCP(m PortMapping, sessionID string, reqBuf, respBuf []byte, durationMs int64) {
	// 检查模式：只在 record/record-and-stub/record-all 模式下录制
	mode := p.sdk.GetMode()
	if mode != baafoo.ModeRecord && mode != baafoo.ModeRecordAndStub && mode != baafoo.ModeRecordAll {
		return
	}

	if len(reqBuf) == 0 && len(respBuf) == 0 {
		return
	}

	host, port := splitHostPort(m.Target, 0)

	// 录制请求
	if len(reqBuf) > 0 {
		p.sdk.ReportRecording(baafoo.RecordingEntry{
			Protocol:   m.Protocol,
			Host:       host,
			Port:       port,
			Direction:   "request",
			SessionID:  sessionID,
			DataHex:    toHex(reqBuf),
			DurationMs: durationMs,
		})
	}

	// 录制响应
	if len(respBuf) > 0 {
		p.sdk.ReportRecording(baafoo.RecordingEntry{
			Protocol:   m.Protocol,
			Host:       host,
			Port:       port,
			Direction:   "response",
			SessionID:  sessionID,
			DataHex:    toHex(respBuf),
			DurationMs: durationMs,
		})
	}
}

// --- 工具函数 ---

func generateSessionID() string {
	return fmt.Sprintf("proxy-%d", time.Now().UnixNano())
}

func splitHostPort(addr string, defaultPort int) (string, int) {
	host, port, err := net.SplitHostPort(addr)
	if err != nil {
		return addr, defaultPort
	}
	var p int
	fmt.Sscanf(port, "%d", &p)
	if p == 0 {
		p = defaultPort
	}
	return host, p
}

func toHex(data []byte) string {
	const hexChars = "0123456789abcdef"
	buf := make([]byte, len(data)*2)
	for i, b := range data {
		buf[i*2] = hexChars[b>>4]
		buf[i*2+1] = hexChars[b&0x0f]
	}
	return string(buf)
}

// parseHTTPRequest 解析 HTTP 请求
func parseHTTPRequest(data []byte) (method, path string, headers map[string]string, body string) {
	headers = make(map[string]string)
	if len(data) == 0 {
		return
	}

	// 找到 header/body 分隔符
	idx := bytesIndex(data, []byte("\r\n\r\n"))
	var headerPart, bodyPart []byte
	if idx >= 0 {
		headerPart = data[:idx]
		bodyPart = data[idx+4:]
	} else {
		headerPart = data
	}

	lines := strings.Split(string(headerPart), "\r\n")
	if len(lines) == 0 {
		return
	}

	// 解析请求行
	parts := strings.Fields(lines[0])
	if len(parts) >= 2 {
		method = parts[0]
		path = parts[1]
	}

	// 解析 headers
	for _, line := range lines[1:] {
		colonIdx := strings.Index(line, ": ")
		if colonIdx > 0 {
			key := strings.TrimSpace(line[:colonIdx])
			val := strings.TrimSpace(line[colonIdx+2:])
			headers[key] = val
		}
	}

	body = string(bodyPart)
	return
}

// parseHTTPResponse 解析 HTTP 响应
func parseHTTPResponse(data []byte) (statusCode int, headers map[string]string, body string) {
	headers = make(map[string]string)
	if len(data) == 0 {
		return
	}

	idx := bytesIndex(data, []byte("\r\n\r\n"))
	var headerPart, bodyPart []byte
	if idx >= 0 {
		headerPart = data[:idx]
		bodyPart = data[idx+4:]
	} else {
		headerPart = data
	}

	lines := strings.Split(string(headerPart), "\r\n")
	if len(lines) == 0 {
		return
	}

	// 解析状态行: HTTP/1.1 200 OK
	parts := strings.Fields(lines[0])
	if len(parts) >= 2 {
		fmt.Sscanf(parts[1], "%d", &statusCode)
	}

	for _, line := range lines[1:] {
		colonIdx := strings.Index(line, ": ")
		if colonIdx > 0 {
			key := strings.TrimSpace(line[:colonIdx])
			val := strings.TrimSpace(line[colonIdx+2:])
			headers[key] = val
		}
	}

	body = string(bodyPart)
	return
}

func bytesIndex(s, sep []byte) int {
	if len(sep) == 0 || len(s) < len(sep) {
		return -1
	}
	for i := 0; i <= len(s)-len(sep); i++ {
		match := true
		for j := 0; j < len(sep); j++ {
			if s[i+j] != sep[j] {
				match = false
				break
			}
		}
		if match {
			return i
		}
	}
	return -1
}
