"""Baafoo Full SDK interception tests."""

import json
import threading
import time
import unittest
from http.server import HTTPServer, BaseHTTPRequestHandler
from urllib.parse import urlparse

from baafoo import Client, Options, RecordingEntry, Rule, MatchCondition, ResponseEntry
from baafoo import patch, unpatch
from baafoo.intercept import _original_urlopen


class MockHandler(BaseHTTPRequestHandler):
    """Mock backend HTTP server"""

    def log_message(self, format, *args):
        pass

    def do_GET(self):
        self.send_response(200)
        self.send_header("Content-Type", "text/plain")
        self.end_headers()
        self.wfile.write(b"real response from backend")

    def do_POST(self):
        content_length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(content_length)
        self.send_response(201)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(b'{"created":true}')


class TestIntercept(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        # 启动后端 HTTP 服务器
        cls.backend = HTTPServer(("127.0.0.1", 0), MockHandler)
        cls.backend_port = cls.backend.server_address[1]
        cls.backend_thread = threading.Thread(target=cls.backend.serve_forever, daemon=True)
        cls.backend_thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.backend.shutdown()

    def setUp(self):
        # 确保每次测试前 unpatch
        unpatch()

    def tearDown(self):
        unpatch()

    def _new_client(self):
        """创建一个不连接真实 Server 的 client"""
        opts = Options(
            server_url="http://127.0.0.1:1",  # 不会真正连接
            heartbeat_interval_sec=999,
            poll_interval_sec=999,
        )
        return Client(opts)

    def test_patch_unpatch(self):
        """测试 patch/unpatch 基本功能"""
        c = self._new_client()
        patch(c)
        # patch 后 urlopen 应该被替换
        import urllib.request
        self.assertIsNot(urllib.request.urlopen, _original_urlopen)
        unpatch()
        self.assertIs(urllib.request.urlopen, _original_urlopen)

    def test_stub_mode_returns_mock(self):
        """测试 stub 模式返回 mock 响应"""
        c = self._new_client()
        c._mode = "stub"
        c._rules = [
            Rule(
                id="rule-001",
                enabled=True,
                conditions=[
                    MatchCondition(field="method", operator="equals", value="GET"),
                    MatchCondition(field="path", operator="prefix", value="/api/"),
                ],
                responses=[
                    ResponseEntry(
                        status_code=200,
                        headers={"Content-Type": "application/json"},
                        body='{"mocked":true}',
                    ),
                ],
            ),
        ]
        patch(c)
        try:
            import urllib.request
            resp = urllib.request.urlopen(f"http://127.0.0.1:{self.backend_port}/api/test")
            self.assertEqual(resp.status, 200)
            body = resp.read().decode("utf-8")
            self.assertIn('"mocked":true', body)
        finally:
            unpatch()

    def test_passthrough_mode_forwards(self):
        """测试 passthrough 模式转发真实请求"""
        c = self._new_client()
        c._mode = "passthrough"
        c._rules = []
        patch(c)
        try:
            import urllib.request
            resp = urllib.request.urlopen(f"http://127.0.0.1:{self.backend_port}/api/test")
            self.assertEqual(resp.status, 200)
            body = resp.read().decode("utf-8")
            self.assertEqual(body, "real response from backend")
        finally:
            unpatch()

    def test_record_mode_records(self):
        """测试 record 模式：匹配规则时录制，未匹配时不录制"""
        c = self._new_client()
        c._mode = "record"
        # 设置匹配规则
        c._rules = [
            Rule(
                id="rule-001",
                enabled=True,
                conditions=[
                    MatchCondition(field="method", operator="equals", value="GET"),
                    MatchCondition(field="path", operator="prefix", value="/api/"),
                ],
            ),
        ]
        patch(c)
        try:
            import urllib.request
            # 发送请求（路径匹配规则）- 应该录制
            resp = urllib.request.urlopen(f"http://127.0.0.1:{self.backend_port}/api/test")
            resp.read()

            # 发送第二个请求（路径不匹配规则）- 不应该录制
            resp2 = urllib.request.urlopen(f"http://127.0.0.1:{self.backend_port}/other/test")
            resp2.read()
        finally:
            unpatch()

        # 验证录制 - 应该只录制了 1 个请求（匹配规则的）
        self.assertEqual(len(c._recording_buffer), 1)
        entry = c._recording_buffer[0]
        self.assertEqual(entry.method, "GET")
        self.assertEqual(entry.path, "/api/test")
        self.assertEqual(entry.response_status_code, 200)

    def test_record_and_stub_mode(self):
        """测试 record-and-stub 模式：匹配规则返回 mock + 录制，未匹配 passthrough（不录制）"""
        c = self._new_client()
        c._mode = "record-and-stub"
        c._rules = [
            Rule(
                id="rule-001",
                enabled=True,
                conditions=[
                    MatchCondition(field="method", operator="equals", value="GET"),
                    MatchCondition(field="path", operator="prefix", value="/api/"),
                ],
                responses=[
                    ResponseEntry(status_code=200, body='{"stub":true}'),
                ],
            ),
        ]
        patch(c)
        try:
            import urllib.request
            # 匹配规则 — 返回 mock，应该录制
            resp = urllib.request.urlopen(f"http://127.0.0.1:{self.backend_port}/api/orders")
            body = resp.read().decode("utf-8")
            self.assertIn('"stub":true', body)

            # 不匹配规则 — passthrough，不录制
            resp2 = urllib.request.urlopen(f"http://127.0.0.1:{self.backend_port}/other")
            body2 = resp2.read().decode("utf-8")
            self.assertEqual(body2, "real response from backend")
        finally:
            unpatch()

        # 验证录制 - 应该只录制了 1 个请求（匹配规则的）
        self.assertEqual(len(c._recording_buffer), 1)

    def test_delay_injection(self):
        """测试延迟注入"""
        c = self._new_client()
        c._mode = "stub"
        c._rules = [
            Rule(
                id="rule-001",
                enabled=True,
                conditions=[
                    MatchCondition(field="method", operator="equals", value="GET"),
                ],
                responses=[
                    ResponseEntry(status_code=200, body="delayed", delay_ms=50),
                ],
            ),
        ]
        patch(c)
        try:
            import urllib.request
            start = time.time()
            resp = urllib.request.urlopen(f"http://127.0.0.1:{self.backend_port}/test")
            resp.read()
            elapsed = time.time() - start
            self.assertGreaterEqual(elapsed, 0.04)  # 允许 10ms 误差
        finally:
            unpatch()

    def test_post_request(self):
        """测试 POST 请求拦截"""
        c = self._new_client()
        c._mode = "stub"
        c._rules = [
            Rule(
                id="rule-001",
                enabled=True,
                conditions=[
                    MatchCondition(field="method", operator="equals", value="POST"),
                ],
                responses=[
                    ResponseEntry(status_code=201, body='{"created":false}'),
                ],
            ),
        ]
        patch(c)
        try:
            import urllib.request
            req = urllib.request.Request(
                f"http://127.0.0.1:{self.backend_port}/api/create",
                data=b'{"name":"test"}',
                method="POST",
            )
            resp = urllib.request.urlopen(req)
            self.assertEqual(resp.status, 201)
            body = resp.read().decode("utf-8")
            self.assertIn('"created":false', body)
        finally:
            unpatch()

    def test_record_all_mode(self):
        """测试 record-all 模式：所有请求都 passthrough + 录制（即使有规则匹配也不返回 mock）"""
        c = self._new_client()
        c._mode = "record-all"
        # 设置规则（匹配 /api/ 路径）
        c._rules = [
            Rule(
                id="rule-001",
                enabled=True,
                conditions=[
                    MatchCondition(field="method", operator="equals", value="GET"),
                    MatchCondition(field="path", operator="prefix", value="/api/"),
                ],
                responses=[
                    ResponseEntry(status_code=200, body='{"mock":true}'),
                ],
            ),
        ]
        patch(c)
        try:
            import urllib.request
            # 发送请求（路径匹配规则，但 record-all 模式不返回 mock）
            resp = urllib.request.urlopen(f"http://127.0.0.1:{self.backend_port}/api/test")
            body = resp.read().decode("utf-8")
            # 应该返回真实响应（不是 mock）
            self.assertEqual(body, "real response from backend")

            # 发送第二个请求（不匹配规则）
            resp2 = urllib.request.urlopen(f"http://127.0.0.1:{self.backend_port}/other/test")
            resp2.read()
        finally:
            unpatch()

        # 验证录制（应该录制了两个请求：匹配规则的和不匹配规则的）
        self.assertGreaterEqual(len(c._recording_buffer), 2)


if __name__ == "__main__":
    unittest.main()
