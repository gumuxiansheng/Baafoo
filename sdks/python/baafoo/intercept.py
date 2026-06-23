"""Baafoo Full SDK - Python HTTP interception via monkey-patching.

Replaces urllib.request.urlopen and http.client.HTTPConnection to intercept
all outbound HTTP requests. Supports stub, record, record-and-stub, and
passthrough modes.
"""

import io
import time
import urllib.request
from urllib.parse import urlparse

from .models import RecordingEntry


# 保存原始函数的引用
_original_urlopen = urllib.request.urlopen
_original_http_connection_init = None
_patched = False
_sdk_instance = None


def patch(client):
    """启用 HTTP 拦截：monkey-patch urllib.request.urlopen

    Args:
        client: baafoo.Client 实例
    """
    global _patched, _sdk_instance
    if _patched:
        return
    _patched = True
    _sdk_instance = client
    urllib.request.urlopen = _patched_urlopen


def unpatch():
    """恢复原始的 urllib.request.urlopen"""
    global _patched, _sdk_instance
    if not _patched:
        return
    _patched = False
    _sdk_instance = None
    urllib.request.urlopen = _original_urlopen


def _patched_urlopen(url, data=None, timeout=None, **kwargs):
    """拦截后的 urlopen 实现"""
    if _sdk_instance is None:
        return _original_urlopen(url, data, timeout, **kwargs)

    # 解析 URL
    if isinstance(url, str):
        parsed = urlparse(url)
        method = "GET" if data is None else "POST"
        path = parsed.path or "/"
        host = parsed.hostname or ""
        port = parsed.port or (443 if parsed.scheme == "https" else 80)
    elif hasattr(url, "full_url"):
        # Request 对象
        parsed = urlparse(url.full_url)
        method = url.get_method()
        path = parsed.path or "/"
        host = parsed.hostname or ""
        port = parsed.port or (443 if parsed.scheme == "https" else 80)
        data = url.data if data is None else data
    else:
        return _original_urlopen(url, data, timeout, **kwargs)

    mode = _sdk_instance.mode
    rules = _sdk_instance.rules

    # 在 stub/record-and-stub 模式下尝试匹配规则
    matched_rule = None
    if mode in ("stub", "record-and-stub"):
        for rule in rules:
            if not rule.enabled:
                continue
            if _match_rule(rule, method, path):
                matched_rule = rule
                break

    if matched_rule:
        # 返回 mock 响应
        resp = _build_mock_response(matched_rule, method, path)
        if mode == "record-and-stub":
            _record_request(_sdk_instance, method, path, host, port,
                            data, resp, 0)
        return resp

    # 未匹配规则：passthrough 或 record 模式
    start_time = time.time()
    resp = _original_urlopen(url, data, timeout, **kwargs)
    duration_ms = int((time.time() - start_time) * 1000)

    if mode in ("record", "record-and-stub"):
        _record_request(_sdk_instance, method, path, host, port,
                        data, resp, duration_ms)

    return resp


def _match_rule(rule, method, path):
    """规则匹配"""
    for cond in rule.conditions:
        if cond.field == "method":
            if not _match_operator(cond.operator, method, cond.value):
                return False
        elif cond.field == "path":
            if not _match_operator(cond.operator, path, cond.value):
                return False
    return True


def _match_operator(operator, actual, expected):
    """操作符匹配"""
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


def _build_mock_response(rule, method, path):
    """构建 mock 响应"""
    if not rule.responses:
        return _MockResponse(200, {}, b"")

    resp_config = rule.responses[0]
    status_code = resp_config.status_code or 200
    body = resp_config.body or ""
    body_bytes = body.encode("utf-8") if isinstance(body, str) else body

    # 延迟
    if resp_config.delay_ms > 0:
        time.sleep(resp_config.delay_ms / 1000.0)

    return _MockResponse(status_code, resp_config.headers, body_bytes)


def _record_request(sdk, method, path, host, port, req_data, resp, duration_ms):
    """录制请求/响应"""
    req_body = ""
    if req_data:
        req_body = req_data.decode("utf-8") if isinstance(req_data, bytes) else str(req_data)

    resp_body = ""
    resp_status = 200
    resp_headers = {}

    if resp is not None:
        if hasattr(resp, "status"):
            resp_status = resp.status
        elif hasattr(resp, "code"):
            resp_status = resp.code

        if hasattr(resp, "headers"):
            resp_headers = dict(resp.headers)
        elif hasattr(resp, "info"):
            try:
                resp_headers = dict(resp.info())
            except Exception:
                pass

        # 读取响应体
        if hasattr(resp, "read"):
            try:
                body_bytes = resp.read()
                resp_body = body_bytes.decode("utf-8", errors="replace")
                # 恢复可读状态
                resp.read = lambda: body_bytes
            except Exception:
                pass

    entry = RecordingEntry(
        protocol="http",
        host=host,
        port=port,
        method=method,
        path=path,
        request_body=req_body,
        response_status_code=resp_status,
        response_headers=resp_headers,
        response_body=resp_body,
        response_time_ms=duration_ms,
        direction="request",
    )
    sdk.report_recording(entry)


class _MockResponse:
    """模拟 HTTP 响应对象，兼容 urllib 的 addinfourl 接口"""

    def __init__(self, status_code, headers, body_bytes):
        self.status = status_code
        self.code = status_code
        self._body = body_bytes
        self._read = False
        self.headers = {}
        for k, v in (headers or {}).items():
            self.headers[k] = v

    def read(self, amt=None):
        if self._read:
            return b""
        self._read = True
        if amt is None:
            return self._body
        return self._body[:amt]

    def info(self):
        return _DictHeaders(self.headers)

    def getcode(self):
        return self.status

    def geturl(self):
        return ""

    def __enter__(self):
        return self

    def __exit__(self, *args):
        pass


class _DictHeaders:
    """简单的 headers 字典包装"""

    def __init__(self, headers):
        self._headers = headers or {}

    def __getitem__(self, key):
        return self._headers.get(key, "")

    def get(self, key, default=None):
        return self._headers.get(key, default)

    def items(self):
        return self._headers.items()

    def keys(self):
        return self._headers.keys()
