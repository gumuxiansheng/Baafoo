package baafoo

// MatchCondition 规则匹配条件
type MatchCondition struct {
	Type    string `json:"type"`
	Operator string `json:"operator"`
	Value    string `json:"value"`
}

// ResponseEntry 挡板响应配置
type ResponseEntry struct {
	StatusCode       int               `json:"statusCode"`
	Headers          map[string]string `json:"headers"`
	Body             string            `json:"body"`
	DelayMs          int               `json:"delayMs"`
	GrpcStatus       int               `json:"grpcStatus"`
	GrpcStatusMessage string           `json:"grpcStatusMessage"`
}

// TcpRound TCP 多轮交互轮次
type TcpRound struct {
	RequestHex  string `json:"requestHex"`
	ResponseHex string `json:"responseHex"`
	DelayMs     int    `json:"delayMs"`
}

// FaultInjection 故障注入配置
type FaultInjection struct {
	Type      string `json:"type"`
	DelayMs   int    `json:"delayMs"`
	ErrorRate int    `json:"errorRate"`
}

// Rule 挡板规则
type Rule struct {
	ID                string            `json:"id"`
	Name              string            `json:"name"`
	Protocol          string            `json:"protocol"`
	ServiceName       string            `json:"serviceName"`
	Host              string            `json:"host"`
	Port              int               `json:"port"`
	Conditions       []MatchCondition  `json:"conditions"`
	Responses        []ResponseEntry   `json:"responses"`
	Enabled           bool              `json:"enabled"`
	Priority          int               `json:"priority"`
	Tags              []string          `json:"tags"`
	Environments      []string          `json:"environments"`
	TcpRounds         []TcpRound        `json:"tcpRounds"`
	TcpLoop           bool              `json:"tcpLoop"`
	TcpPattern        string            `json:"tcpPattern"`
	TcpPrefixHex      string            `json:"tcpPrefixHex"`
	TcpOffsetStart    int               `json:"tcpOffsetStart"`
	TcpOffsetEnd      int               `json:"tcpOffsetEnd"`
	TcpOffsetHex      string            `json:"tcpOffsetHex"`
	FakerSeed         *int64            `json:"fakerSeed"`
	RequestCountReset *int              `json:"requestCountReset"`
	FaultInjection    *FaultInjection   `json:"faultInjection"`
	GrpcService       string            `json:"grpcService"`
	GrpcMethod        string            `json:"grpcMethod"`
	GrpcStreaming     string            `json:"grpcStreaming"`
	Version           int               `json:"version"`
	CreatedAt         int64             `json:"createdAt"`
	UpdatedAt         int64             `json:"updatedAt"`
}

// RecordingEntry 录制数据
type RecordingEntry struct {
	ID                string            `json:"id"`
	RuleID            string            `json:"ruleId"`
	EnvironmentID     string            `json:"environmentId"`
	AgentID           string            `json:"agentId"`
	AgentIP           string            `json:"agentIp"`
	Protocol          string            `json:"protocol"`
	Host              string            `json:"host"`
	Port              int               `json:"port"`
	ServiceName       string            `json:"serviceName"`
	Method            string            `json:"method"`
	Path              string            `json:"path"`
	RequestHeaders    map[string]string `json:"requestHeaders"`
	RequestBody       string            `json:"requestBody"`
	ResponseStatusCode int              `json:"responseStatusCode"`
	ResponseHeaders   map[string]string `json:"responseHeaders"`
	ResponseBody      string            `json:"responseBody"`
	ResponseTimeMs    int64             `json:"responseTimeMs"`
	RecordedAt        int64             `json:"recordedAt"`
	Tags              map[string]string `json:"tags"`
	Direction         string            `json:"direction"`
	SessionID         string            `json:"sessionId"`
	DataHex           string            `json:"dataHex"`
	DurationMs        int64             `json:"durationMs"`
	Unmatched         bool              `json:"unmatched"`
	GrpcService       string            `json:"grpcService"`
	GrpcMethod        string            `json:"grpcMethod"`
	GrpcStatus        int               `json:"grpcStatus"`
	GrpcContentType   string            `json:"grpcContentType"`
}
