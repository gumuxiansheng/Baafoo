"""Baafoo Thin SDK tests."""

import json
import os
import tempfile
import threading
import time
import unittest
from http.server import HTTPServer, BaseHTTPRequestHandler
from urllib.parse import urlparse, parse_qs

from baafoo import Client, Options, RecordingEntry


class MockHandler(BaseHTTPRequestHandler):
    """Mock Baafoo Server handler"""

    def log_message(self, format, *args):
        pass  # 静默日志

    def _send_json(self, data, status=200):
        body = json.dumps(data).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        parsed = urlparse(self.path)
        path = parsed.path

        content_length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(content_length) if content_length > 0 else b""

        if path == "/__baafoo__/api/agent/register":
            self.server.register_count += 1
            self._send_json({
                "success": True,
                "code": 200,
                "message": "OK",
                "data": {
                    "agentId": "test-agent-001",
                    "mode": "record-and-stub",
                    "pollIntervalSec": 5,
                },
                "timestamp": int(time.time() * 1000),
            })
        elif path == "/__baafoo__/api/agent/heartbeat":
            self.server.heartbeat_count += 1
            self._send_json({
                "success": True,
                "code": 200,
                "message": "OK",
                "data": None,
                "timestamp": int(time.time() * 1000),
            })
        elif path == "/__baafoo__/api/agent/recordings":
            entries = json.loads(body) if body else []
            self.server.recording_count += len(entries)
            self.server.last_recordings = entries
            self._send_json({
                "success": True,
                "code": 200,
                "message": f"Recorded {len(entries)}",
                "data": None,
                "timestamp": int(time.time() * 1000),
            })
        else:
            self._send_json({"success": False, "code": 404, "message": "Not Found"}, 404)

    def do_GET(self):
        parsed = urlparse(self.path)
        path = parsed.path

        if path == "/__baafoo__/api/agent/poll":
            self.server.poll_count += 1
            self._send_json({
                "success": True,
                "code": 200,
                "message": "OK",
                "data": {
                    "rules": [
                        {
                            "id": "rule-001",
                            "name": "test-rule",
                            "protocol": "http",
                            "enabled": True,
                            "priority": 100,
                            "conditions": [
                                {"field": "method", "operator": "equals", "value": "GET"},
                                {"field": "path", "operator": "prefix", "value": "/api/"},
                            ],
                            "responses": [
                                {"statusCode": 200, "body": '{"ok":true}'},
                            ],
                        }
                    ],
                    "mode": "stub",
                    "version": int(time.time() * 1000),
                },
                "timestamp": int(time.time() * 1000),
            })
        else:
            self._send_json({"success": False, "code": 404, "message": "Not Found"}, 404)


class TestBaafooSDK(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        cls.server = HTTPServer(("127.0.0.1", 0), MockHandler)
        cls.server.register_count = 0
        cls.server.heartbeat_count = 0
        cls.server.poll_count = 0
        cls.server.recording_count = 0
        cls.server.last_recordings = []
        cls.port = cls.server.server_address[1]
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()

    def setUp(self):
        self.server.register_count = 0
        self.server.heartbeat_count = 0
        self.server.poll_count = 0
        self.server.recording_count = 0
        self.server.last_recordings = []

    def _new_client(self, **kwargs) -> Client:
        defaults = dict(
            server_url=f"http://127.0.0.1:{self.port}",
            heartbeat_interval_sec=100,
            poll_interval_sec=100,
        )
        defaults.update(kwargs)
        opts = Options(**defaults)
        return Client(opts)

    def test_new_client(self):
        c = self._new_client()
        self.assertEqual(c.mode, "record-and-stub")
        self.assertEqual(len(c.rules), 0)

    def test_register(self):
        c = self._new_client()
        c.start()
        try:
            self.assertEqual(c.agent_id, "test-agent-001")
            # register 返回 record-and-stub，但初始 poll 可能更新为 stub
            self.assertIn(c.mode, ("record-and-stub", "stub"))
            self.assertGreaterEqual(self.server.register_count, 1)
        finally:
            c.close()

    def test_poll_rules(self):
        c = self._new_client(poll_interval_sec=1)
        c.start()
        try:
            time.sleep(2)
            rules = c.rules
            self.assertEqual(len(rules), 1)
            self.assertEqual(rules[0].id, "rule-001")
            self.assertEqual(c.mode, "stub")
        finally:
            c.close()

    def test_report_recording(self):
        c = self._new_client()
        c.start()
        try:
            entry = RecordingEntry(
                protocol="http",
                host="example.com",
                port=8080,
                method="GET",
                path="/api/test",
                response_status_code=200,
                response_body='{"ok":true}',
            )
            c.report_recording(entry)
        finally:
            c.close()

        self.assertEqual(self.server.recording_count, 1)
        self.assertEqual(len(self.server.last_recordings), 1)
        self.assertEqual(self.server.last_recordings[0]["method"], "GET")

    def test_match_request(self):
        c = self._new_client()
        # 手动设置规则
        from baafoo.models import Rule, MatchCondition
        c._rules = [
            Rule(
                id="rule-001",
                enabled=True,
                conditions=[
                    MatchCondition(field="method", operator="equals", value="GET"),
                    MatchCondition(field="path", operator="prefix", value="/api/"),
                ],
            ),
            Rule(
                id="rule-002",
                enabled=True,
                conditions=[
                    MatchCondition(field="method", operator="equals", value="POST"),
                    MatchCondition(field="path", operator="prefix", value="/api/"),
                ],
            ),
        ]

        rule = c.match_request("GET", "/api/orders")
        self.assertIsNotNone(rule)
        self.assertEqual(rule.id, "rule-001")

        rule = c.match_request("POST", "/api/orders")
        self.assertIsNotNone(rule)
        self.assertEqual(rule.id, "rule-002")

        rule = c.match_request("DELETE", "/api/orders")
        self.assertIsNone(rule)

    def test_auth_headers(self):
        c = self._new_client(api_key="test-api-key")
        c.start()
        try:
            self.assertEqual(c.agent_id, "test-agent-001")
        finally:
            c.close()


if __name__ == "__main__":
    unittest.main()
