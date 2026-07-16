"""Baafoo data models."""

from dataclasses import dataclass, field
from typing import List, Dict, Optional


@dataclass
class MatchCondition:
    """规则匹配条件"""
    type: str = ""
    operator: str = ""
    value: str = ""


@dataclass
class ResponseEntry:
    """挡板响应配置"""
    status_code: int = 0
    headers: Dict[str, str] = field(default_factory=dict)
    body: str = ""
    delay_ms: int = 0
    grpc_status: int = 0
    grpc_status_message: str = ""


@dataclass
class TcpRound:
    """TCP 多轮交互轮次"""
    request_hex: str = ""
    response_hex: str = ""
    delay_ms: int = 0


@dataclass
class FaultInjection:
    """故障注入配置"""
    type: str = ""
    delay_ms: int = 0
    error_rate: int = 0


@dataclass
class Rule:
    """挡板规则"""
    id: str = ""
    name: str = ""
    protocol: str = ""
    service_name: str = ""
    host: str = ""
    port: int = 0
    conditions: List[MatchCondition] = field(default_factory=list)
    responses: List[ResponseEntry] = field(default_factory=list)
    enabled: bool = True
    priority: int = 100
    tags: List[str] = field(default_factory=list)
    environments: List[str] = field(default_factory=list)
    tcp_rounds: List[TcpRound] = field(default_factory=list)
    tcp_loop: bool = False
    tcp_pattern: str = ""
    tcp_prefix_hex: str = ""
    tcp_offset_start: int = -1
    tcp_offset_end: int = -1
    tcp_offset_hex: str = ""
    faker_seed: Optional[int] = None
    request_count_reset: Optional[int] = None
    fault_injection: Optional[FaultInjection] = None
    grpc_service: str = ""
    grpc_method: str = ""
    grpc_streaming: str = ""
    version: int = 1
    created_at: int = 0
    updated_at: int = 0


@dataclass
class RecordingEntry:
    """录制数据"""
    id: str = ""
    rule_id: str = ""
    environment_id: str = ""
    agent_id: str = ""
    agent_ip: str = ""
    protocol: str = ""
    host: str = ""
    port: int = 0
    service_name: str = ""
    method: str = ""
    path: str = ""
    request_headers: Dict[str, str] = field(default_factory=dict)
    request_body: str = ""
    response_status_code: int = 0
    response_headers: Dict[str, str] = field(default_factory=dict)
    response_body: str = ""
    response_time_ms: int = 0
    recorded_at: int = 0
    tags: Dict[str, str] = field(default_factory=dict)
    direction: str = ""
    session_id: str = ""
    data_hex: str = ""
    duration_ms: int = 0
    unmatched: bool = False
    grpc_service: str = ""
    grpc_method: str = ""
    grpc_status: int = 0
    grpc_content_type: str = ""

    def to_dict(self) -> dict:
        """转换为字典，使用 camelCase 键名以匹配 Server API"""
        return {
            "id": self.id,
            "ruleId": self.rule_id,
            "environmentId": self.environment_id,
            "agentId": self.agent_id,
            "agentIp": self.agent_ip,
            "protocol": self.protocol,
            "host": self.host,
            "port": self.port,
            "serviceName": self.service_name,
            "method": self.method,
            "path": self.path,
            "requestHeaders": self.request_headers,
            "requestBody": self.request_body,
            "responseStatusCode": self.response_status_code,
            "responseHeaders": self.response_headers,
            "responseBody": self.response_body,
            "responseTimeMs": self.response_time_ms,
            "recordedAt": self.recorded_at,
            "tags": self.tags,
            "direction": self.direction,
            "sessionId": self.session_id,
            "dataHex": self.data_hex,
            "durationMs": self.duration_ms,
            "unmatched": self.unmatched,
            "grpcService": self.grpc_service,
            "grpcMethod": self.grpc_method,
            "grpcStatus": self.grpc_status,
            "grpcContentType": self.grpc_content_type,
        }
