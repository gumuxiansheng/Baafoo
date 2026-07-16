"""Baafoo Thin SDK client."""

import json
import os
import socket
import threading
import time
import uuid
from pathlib import Path
from typing import List, Optional
from urllib.request import Request, urlopen
from urllib.error import URLError, HTTPError
from urllib.parse import urlencode

from .models import RecordingEntry, Rule, MatchCondition


class Options:
    """SDK 配置选项"""

    def __init__(
        self,
        server_url: str = "http://127.0.0.1:8084",
        environment: str = "default",
        app_name: str = "",
        api_key: str = "",
        token: str = "",
        heartbeat_interval_sec: int = 15,
        poll_interval_sec: int = 10,
        http_timeout: float = 30.0,
    ):
        self.server_url = server_url.rstrip("/")
        self.environment = environment
        self.app_name = app_name
        self.api_key = api_key
        self.token = token
        self.heartbeat_interval_sec = heartbeat_interval_sec
        self.poll_interval_sec = poll_interval_sec
        self.http_timeout = http_timeout


class Client:
    """Baafoo Thin SDK 客户端"""

    def __init__(self, options: Options):
        self.opts = options
        self._agent_id = ""
        self._rules: List[Rule] = []
        self._mode = "record-and-stub"
        self._lock = threading.Lock()
        self._recording_buffer: List[RecordingEntry] = []
        self._stop_event = threading.Event()
        self._threads: List[threading.Thread] = []

    # --- 生命周期 ---

    def start(self) -> None:
        """启动 SDK：注册 agent，然后启动心跳和轮询线程"""
        self._agent_id = self._load_or_generate_agent_id()
        self._register()

        # 先做一次 poll，确保规则立即可用
        self._poll_rules()

        # 启动后台线程
        self._stop_event.clear()
        t1 = threading.Thread(target=self._heartbeat_loop, daemon=True, name="baafoo-heartbeat")
        t2 = threading.Thread(target=self._poll_loop, daemon=True, name="baafoo-poll")
        t3 = threading.Thread(target=self._recording_flush_loop, daemon=True, name="baafoo-flush")
        self._threads = [t1, t2, t3]
        t1.start()
        t2.start()
        t3.start()

    def close(self) -> None:
        """关闭 SDK，flush 录制数据"""
        self._stop_event.set()
        for t in self._threads:
            if t.is_alive():
                t.join(timeout=5)
        self._flush_recordings()

    # --- 公共 API ---

    @property
    def agent_id(self) -> str:
        return self._agent_id

    @property
    def rules(self) -> List[Rule]:
        with self._lock:
            return list(self._rules)

    @property
    def mode(self) -> str:
        return self._mode

    def report_recording(self, entry: RecordingEntry) -> None:
        """上报单条录制数据（缓冲后批量上传）"""
        if not entry.id:
            entry.id = str(uuid.uuid4())
        if entry.recorded_at == 0:
            entry.recorded_at = int(time.time() * 1000)
        if not entry.agent_id:
            entry.agent_id = self._agent_id

        should_flush = False
        with self._lock:
            self._recording_buffer.append(entry)
            if len(self._recording_buffer) >= 50:
                should_flush = True

        if should_flush:
            self._flush_recordings()

    def match_request(self, method: str, path: str) -> Optional[Rule]:
        """在本地规则中匹配请求"""
        rules = self.rules
        for rule in rules:
            if not rule.enabled:
                continue
            if self._match_rule(rule, method, path):
                return rule
        return None

    # --- 内部方法 ---

    def _match_rule(self, rule: Rule, method: str, path: str) -> bool:
        for cond in rule.conditions:
            if cond.type == "method":
                if not self._match_operator(cond.operator, method, cond.value):
                    return False
            elif cond.type == "path":
                if not self._match_operator(cond.operator, path, cond.value):
                    return False
        return True

    def _match_operator(self, operator: str, actual: str, expected: str) -> bool:
        if operator in ("equals", "="):
            return actual == expected
        elif operator == "contains":
            return expected in actual
        elif operator == "prefix":
            return actual.startswith(expected)
        elif operator == "suffix":
            return actual.endswith(expected)
        elif operator == "exists":
            return actual != ""
        else:
            return actual == expected

    def _register(self) -> None:
        hostname = socket.gethostname() or "unknown"
        body = {
            "agentId": self._agent_id,
            "environment": self.opts.environment,
            "hostname": hostname,
            "version": "1.0.0",
            "protocols": ["http"],
        }
        data = self._do_post("/agent/register", body)
        if data:
            self._agent_id = data.get("agentId", self._agent_id)
            mode = data.get("mode", "")
            if mode:
                self._mode = mode
            poll_interval = data.get("pollIntervalSec", 0)
            if poll_interval > 0:
                self.opts.poll_interval_sec = poll_interval

    def _heartbeat(self) -> None:
        body = {
            "agentId": self._agent_id,
            "timestamp": int(time.time() * 1000),
            "pluginStatuses": {},
        }
        try:
            self._do_post("/agent/heartbeat", body)
        except Exception:
            pass

    def _poll_rules(self) -> None:
        params = urlencode({
            "agentId": self._agent_id,
            "environment": self.opts.environment,
        })
        data = self._do_get(f"/agent/poll?{params}")
        if data:
            rules_data = data.get("rules", [])
            rules = [self._parse_rule(r) for r in rules_data]
            with self._lock:
                self._rules = rules
            mode = data.get("mode", "")
            if mode:
                self._mode = mode

    def _parse_rule(self, data: dict) -> Rule:
        rule = Rule()
        rule.id = data.get("id", "")
        rule.name = data.get("name", "")
        rule.protocol = data.get("protocol", "")
        rule.service_name = data.get("serviceName", "")
        rule.host = data.get("host", "")
        rule.port = data.get("port", 0)
        rule.enabled = data.get("enabled", True)
        rule.priority = data.get("priority", 100)

        conds = data.get("conditions", [])
        rule.conditions = [
            MatchCondition(
                field=c.get("field", ""),
                operator=c.get("operator", ""),
                value=c.get("value", ""),
            )
            for c in conds
        ]

        return rule

    def _heartbeat_loop(self) -> None:
        while not self._stop_event.is_set():
            self._heartbeat()
            self._stop_event.wait(self.opts.heartbeat_interval_sec)

    def _poll_loop(self) -> None:
        while not self._stop_event.is_set():
            try:
                self._poll_rules()
            except Exception:
                pass
            self._stop_event.wait(self.opts.poll_interval_sec)

    def _recording_flush_loop(self) -> None:
        while not self._stop_event.is_set():
            self._flush_recordings()
            self._stop_event.wait(5)

    def _flush_recordings(self) -> None:
        with self._lock:
            if not self._recording_buffer:
                return
            batch = self._recording_buffer
            self._recording_buffer = []

        params = urlencode({
            "agentId": self._agent_id,
            "environment": self.opts.environment,
        })
        body = [entry.to_dict() for entry in batch]
        try:
            self._do_post(f"/agent/recordings?{params}", body)
        except Exception:
            # 上传失败，放回缓冲区
            with self._lock:
                self._recording_buffer = batch + self._recording_buffer

    # --- HTTP 工具方法 ---

    def _do_post(self, path: str, body: dict) -> Optional[dict]:
        url = f"{self.opts.server_url}/__baafoo__/api{path}"
        data = json.dumps(body).encode("utf-8")
        req = Request(url, data=data, method="POST")
        req.add_header("Content-Type", "application/json")
        self._set_auth(req)
        return self._do_request(req)

    def _do_get(self, path: str) -> Optional[dict]:
        url = f"{self.opts.server_url}/__baafoo__/api{path}"
        req = Request(url, method="GET")
        self._set_auth(req)
        return self._do_request(req)

    def _do_request(self, req: Request) -> Optional[dict]:
        try:
            with urlopen(req, timeout=self.opts.http_timeout) as resp:
                if resp.status == 204:
                    return None
                body = resp.read().decode("utf-8")
                api_resp = json.loads(body)
                if not api_resp.get("success"):
                    raise Exception(f"api error: {api_resp.get('message')} (code {api_resp.get('code')})")
                return api_resp.get("data")
        except HTTPError as e:
            body = e.read().decode("utf-8") if e.fp else ""
            raise Exception(f"HTTP {e.code}: {body}")

    def _set_auth(self, req: Request) -> None:
        if self.opts.token:
            req.add_header("Authorization", f"Bearer {self.opts.token}")
        elif self.opts.api_key:
            req.add_header("X-Api-Key", self.opts.api_key)

    # --- AgentId 持久化 ---

    def _load_or_generate_agent_id(self) -> str:
        home = str(Path.home())
        dir_path = os.path.join(home, ".baafoo")
        name_part = self.opts.app_name or "agent"
        id_file = os.path.join(dir_path, f"baafoo-{name_part}.id")

        try:
            with open(id_file, "r") as f:
                agent_id = f.read().strip()
                if agent_id:
                    return agent_id
        except (IOError, OSError):
            pass

        agent_id = str(uuid.uuid4())
        try:
            os.makedirs(dir_path, exist_ok=True)
            with open(id_file, "w") as f:
                f.write(agent_id)
        except (IOError, OSError):
            pass

        return agent_id
