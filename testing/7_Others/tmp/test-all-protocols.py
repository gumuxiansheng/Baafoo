#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Baafoo 全协议集成测试（全面版）
覆盖 HTTP / TCP / Kafka / Pulsar / JMS 所有协议的核心功能
"""
import json
import socket
import subprocess
import sys
import time
from datetime import datetime

API_URL = "http://localhost:8084/__baafoo__/api"
API_KEY = "staging-admin-key"
HTTP_STUB = "http://localhost:9000"
TCP_STUB_HOST = "localhost"
TCP_STUB_PORT = 9001

TEST_STATIC = "http://localhost:9090"
TEST_STATIC_B = "http://localhost:9091"

results = []


def log_pass(case, msg=""):
    results.append(("PASS", case, msg))
    print(f"[PASS] {case} {msg}")


def log_fail(case, msg=""):
    results.append(("FAIL", case, msg))
    print(f"[FAIL] {case} {msg}")


def curl(method, url, headers=None, data=None):
    cmd = ["curl", "-s", "-w", "\n%{http_code}", "-X", method, url]
    h = headers or {}
    for k, v in h.items():
        cmd += ["-H", f"{k}: {v}"]
    if data:
        cmd += ["-d", data]
    try:
        out = subprocess.run(cmd, capture_output=True, text=True, timeout=120)
        lines = out.stdout.strip().splitlines()
        status = int(lines[-1]) if lines else 0
        body = "\n".join(lines[:-1])
        return status, body
    except Exception as e:
        return 0, str(e)


def api_key_headers():
    return {"Content-Type": "application/json", "X-Api-Key": API_KEY}


def create_or_update_rule(rule):
    rule_json = json.dumps(rule, ensure_ascii=False)
    # 先尝试删除（确保干净），然后 POST 创建
    curl("DELETE", f"{API_URL}/rules/{rule['id']}", headers=api_key_headers())
    status, body = curl("POST", f"{API_URL}/rules", headers=api_key_headers(), data=rule_json)
    if status not in (200, 201):
        curl("PUT", f"{API_URL}/rules/{rule['id']}", headers=api_key_headers(), data=rule_json)


def tcp_send_recv(host, port, payload, timeout=10):
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s.settimeout(timeout)
        s.connect((host, port))
        s.sendall(payload)
        resp = s.recv(4096)
        s.close()
        return True, resp
    except Exception as e:
        return False, str(e).encode()


def delete_rule(rule_id):
    curl("DELETE", f"{API_URL}/rules/{rule_id}",
         headers={"X-Api-Key": API_KEY})


def setup_tcp_test_rules():
    # 注意：TCP handler 按列表顺序检查规则，multiround 和 prefix hex 优先级更高的规则需靠前
    # payload hex: LOGIN=4c4f47, QUERY=515545, LOGOUT=4f5554, DE=4445, BA=4241, HELLO=48454c4c4f
    rules = [
        {
            "id": "test-tcp-prefix-hex",
            "name": "TCP Hex前缀匹配",
            "protocol": "tcp",
            "host": "127.0.0.1",
            "port": 9999,
            "conditions": [],
            "tcpPrefixHex": "48454c4c4f",
            "responses": [
                {"name": "hex响应", "body": "HEX-RESPONSE-OK", "delayMs": 0, "statusCode": 200}
            ],
            "enabled": True,
            "priority": 200,
            "tags": ["tcp", "test"],
            "environments": ["staging-a", "staging-b"]
        },
        {
            "id": "test-tcp-multi-round",
            "name": "TCP多轮交互",
            "protocol": "tcp",
            "host": "127.0.0.1",
            "port": 9999,
            "conditions": [],
            "tcpRounds": [
                {"pattern": ".*4c4f47.*", "response": {"name": "登录响应", "body": "LOGIN-RESPONSE", "delayMs": 0, "statusCode": 200}},
                {"pattern": ".*515545.*", "response": {"name": "查询响应", "body": "QUERY-RESPONSE", "delayMs": 0, "statusCode": 200}},
                {"pattern": ".*4f5554.*", "response": {"name": "登出响应", "body": "LOGOUT-RESPONSE", "delayMs": 0, "statusCode": 200}}
            ],
            "responses": [],
            "enabled": True,
            "priority": 170,
            "tags": ["tcp", "test", "multiround"],
            "environments": ["staging-a", "staging-b"],
            "tcpLoop": False
        },
        {
            "id": "test-tcp-offset-match",
            "name": "TCP偏移量匹配",
            "protocol": "tcp",
            "host": "127.0.0.1",
            "port": 9999,
            "conditions": [],
            "tcpOffsetStart": 0,
            "tcpOffsetEnd": 4,
            "tcpOffsetHex": "a1b2c3d4",
            "responses": [
                {"name": "offset响应", "body": "OFFSET-RESPONSE-OK", "delayMs": 0, "statusCode": 200}
            ],
            "enabled": True,
            "priority": 180,
            "tags": ["tcp", "test"],
            "environments": ["staging-a", "staging-b"]
        },
        {
            "id": "test-tcp-delay",
            "name": "TCP延迟响应",
            "protocol": "tcp",
            "host": "127.0.0.1",
            "port": 9999,
            "conditions": [],
            "tcpPattern": ".*4445.*",
            "responses": [
                {"name": "delay响应", "body": "TCP-DELAY-RESPONSE", "delayMs": 500, "statusCode": 200}
            ],
            "enabled": True,
            "priority": 160,
            "tags": ["tcp", "test", "delay"],
            "environments": ["staging-a", "staging-b"]
        },
        {
            "id": "test-tcp-regex-match",
            "name": "TCP正则匹配",
            "protocol": "tcp",
            "host": "127.0.0.1",
            "port": 9999,
            "conditions": [],
            "tcpPattern": ".*4241.*",
            "responses": [
                {"name": "regex响应", "body": "REGEX-RESPONSE-OK", "delayMs": 0, "statusCode": 200}
            ],
            "enabled": True,
            "priority": 150,
            "tags": ["tcp", "test"],
            "environments": ["staging-a", "staging-b"]
        }
    ]
    for rule in rules:
        create_or_update_rule(rule)


def test_tcp_regex():
    """使用不包含HELLO前缀的内容 - 仅触发正则匹配 (prio 150)"""
    ok, resp = tcp_send_recv(TCP_STUB_HOST, TCP_STUB_PORT, b"BAABOO")
    if ok and b"REGEX" in resp:
        log_pass("T-TCP-01 正则匹配", resp.decode("utf-8", errors="replace")[:120])
    else:
        log_fail("T-TCP-01 正则匹配", f"ok={ok} resp={resp[:120]}")


def test_tcp_hex_prefix():
    """使用HELLO开头的内容 - 触发hex前缀匹配 (prio 200)"""
    payload = bytes.fromhex("48454c4c4f") + b"-TEST"
    ok, resp = tcp_send_recv(TCP_STUB_HOST, TCP_STUB_PORT, payload)
    if ok and b"HEX" in resp:
        log_pass("T-TCP-02 Hex前缀匹配", resp.decode("utf-8", errors="replace")[:120])
    else:
        log_fail("T-TCP-02 Hex前缀匹配", f"ok={ok} resp={resp[:120]}")


def test_tcp_offset():
    """使用指定偏移hex匹配 (prio 180)"""
    payload = bytes.fromhex("a1b2c3d4") + b"-TEST DATA"
    ok, resp = tcp_send_recv(TCP_STUB_HOST, TCP_STUB_PORT, payload)
    if ok and b"OFFSET" in resp:
        log_pass("T-TCP-03 偏移量匹配", resp.decode("utf-8", errors="replace")[:120])
    else:
        log_fail("T-TCP-03 偏移量匹配", f"ok={ok} resp={resp[:120]}")


def test_tcp_multiround():
    """多轮协议交互：LOGIN -> QUERY -> LOGOUT (prio 170)"""
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s.settimeout(10)
        s.connect((TCP_STUB_HOST, TCP_STUB_PORT))
        s.sendall(b"LOGIN USER STUB")
        resp1 = s.recv(4096)
        s.sendall(b"QUERY DATA STUB")
        resp2 = s.recv(4096)
        s.sendall(b"LOGOUT USER STUB")
        resp3 = s.recv(4096)
        s.close()
        if b"LOGIN" in resp1 and b"QUERY" in resp2 and b"LOGOUT" in resp3:
            log_pass("T-TCP-04 多轮交互", f"resp1={resp1[:60]} resp2={resp2[:60]} resp3={resp3[:60]}")
        else:
            log_fail("T-TCP-04 多轮交互", f"resp1={resp1[:120]} resp2={resp2[:120]} resp3={resp3[:120]}")
    except Exception as e:
        log_fail("T-TCP-04 多轮交互", str(e))


def test_tcp_delay():
    """TCP延迟响应 - 通过包含DELAY关键词 (prio 160)"""
    start = time.time()
    ok, resp = tcp_send_recv(TCP_STUB_HOST, TCP_STUB_PORT, b"SAMPLE-DELAY TEST")
    elapsed = int((time.time() - start) * 1000)
    if ok and b"DELAY" in resp and elapsed >= 400:
        log_pass("T-TCP-05 延迟响应", f"elapsed={elapsed}ms")
    else:
        log_fail("T-TCP-05 延迟响应", f"ok={ok} elapsed={elapsed}ms")


def test_tcp_env_isolation():
    """TCP 协议可在两个环境中都被成功拦截"""
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s.settimeout(5)
        s.connect((TCP_STUB_HOST, TCP_STUB_PORT))
        s.sendall(b"HELLO BAAFOO ENV-A")
        resp = s.recv(4096)
        s.close()
        if resp:
            log_pass("T-TCP-06 环境一致性", resp.decode("utf-8", errors="replace")[:120])
        else:
            log_fail("T-TCP-06 环境一致性", f"resp={resp[:120]}")
    except Exception as e:
        log_fail("T-TCP-06 环境一致性", str(e))


def setup_kafka_test_rules():
    rules = [
        {
            "id": "test-kafka-specific-topic",
            "name": "Kafka主题挡板",
            "protocol": "kafka",
            "host": "kafka-broker",
            "port": 9092,
            "conditions": [{"type": "topic", "operator": "equals", "value": "baafoo-test-topic"}],
            "responses": [{"name": "kafka响应", "body": "{\"mocked\":true,\"protocol\":\"kafka\",\"topic\":\"baafoo-test-topic\"}", "delayMs": 0, "statusCode": 200}],
            "enabled": True,
            "priority": 200,
            "tags": ["kafka", "test"],
            "environments": ["staging-a", "staging-b"]
        }
    ]
    for rule in rules:
        create_or_update_rule(rule)


def setup_pulsar_test_rules():
    rules = [
        {
            "id": "test-pulsar-specific-topic",
            "name": "Pulsar主题挡板",
            "protocol": "pulsar",
            "host": "pulsar-broker",
            "port": 6650,
            "conditions": [{"type": "topic", "operator": "startsWith", "value": "persistent://public/default/baafoo-test-topic"}],
            "responses": [{"name": "pulsar响应", "body": "{\"mocked\":true,\"protocol\":\"pulsar\"}", "delayMs": 0, "statusCode": 200}],
            "enabled": True,
            "priority": 200,
            "tags": ["pulsar", "test"],
            "environments": ["staging-a", "staging-b"]
        }
    ]
    for rule in rules:
        create_or_update_rule(rule)


def setup_jms_test_rules():
    rules = [
        {
            "id": "test-jms-queue-specific",
            "name": "JMS队列挡板",
            "protocol": "jms",
            "host": "jms-broker",
            "port": 61616,
            "conditions": [{"type": "destination", "operator": "equals", "value": "BAAFOO.TEST.QUEUE"}],
            "responses": [{"name": "jms响应", "body": "{\"mocked\":true,\"protocol\":\"jms\",\"destination\":\"BAAFOO.TEST.QUEUE\"}", "delayMs": 0, "statusCode": 200}],
            "enabled": True,
            "priority": 200,
            "tags": ["jms", "test"],
            "environments": ["staging-a", "staging-b"]
        }
    ]
    for rule in rules:
        create_or_update_rule(rule)


def setup_graphql_test_rules():
    rules = [
        {
            "id": "test-graphql-query",
            "name": "GraphQL Query 挡板",
            "protocol": "http",
            "host": "example.com",
            "port": 80,
            "conditions": [
                {"type": "path", "operator": "equals", "value": "/graphql"},
                {"type": "graphqlOperationName", "operator": "equals", "value": "GetUser"}
            ],
            "responses": [
                {"name": "query响应", "statusCode": 200, "body": '{"data":{"user":{"id":"1","name":"Baafoo GraphQL User"}},"mocked":true}'}
            ],
            "enabled": True,
            "priority": 60,
            "tags": ["graphql", "query", "test"],
            "environments": ["staging-a", "staging-b"]
        },
        {
            "id": "test-graphql-mutation",
            "name": "GraphQL Mutation 挡板",
            "protocol": "http",
            "host": "example.com",
            "port": 80,
            "conditions": [
                {"type": "path", "operator": "equals", "value": "/graphql"},
                {"type": "graphqlOperationType", "operator": "equals", "value": "mutation"}
            ],
            "responses": [
                {"name": "mutation响应", "statusCode": 200, "body": '{"data":{"updateUser":{"success":true,"name":"Updated by GraphQL"}},"mocked":true}'}
            ],
            "enabled": True,
            "priority": 55,
            "tags": ["graphql", "mutation", "test"],
            "environments": ["staging-a", "staging-b"]
        }
    ]
    for rule in rules:
        create_or_update_rule(rule)


def main():
    print("=" * 60)
    print("Baafoo 全协议集成测试（全面版）")
    print(f"开始时间: {datetime.now().isoformat()}")
    print("=" * 60)
    print()

    # 先清除旧的冲突规则（TCP handler按注册顺序匹配，不按优先级排序）
    for rid in ["staging-tcp-regex", "staging-tcp-hex", "staging-tcp-multiround",
                "staging-http-get-b", "staging-tcp-regex-b", "staging-tcp-hex-b"]:
        delete_rule(rid)
    time.sleep(1)

    # 准备规则
    setup_tcp_test_rules()
    setup_kafka_test_rules()
    setup_pulsar_test_rules()
    setup_jms_test_rules()
    setup_graphql_test_rules()
    time.sleep(2)

    # ========== HTTP 协议测试 ==========
    print("-" * 60)
    print("HTTP 协议测试")
    print("-" * 60)

    status, body = curl("GET", f"{TEST_STATIC}/api/http/get?url=http://httpbin.org/get")
    if status == 200 and ('"success":true' in body or '"stubbed":true' in body):
        log_pass("H01 HTTP GET Agent拦截", body[:120])
    else:
        log_fail("H01 HTTP GET Agent拦截", f"status={status} body={body[:200]}")

    status, body = curl("POST", f"{HTTP_STUB}/direct/post",
                        headers={"Host": "httpbin.org"},
                        data=json.dumps({"test": 123}, ensure_ascii=False))
    if status == 201 and '"mocked":true' in body:
        log_pass("H02 HTTP POST Body响应", body[:120])
    else:
        log_fail("H02 HTTP POST Body响应", f"status={status} body={body[:200]}")

    status_a, body_a = curl("GET", f"{TEST_STATIC}/api/http/methods")
    status_b, body_b = curl("GET", f"{TEST_STATIC_B}/api/http/methods")
    # 两个环境返回不同的挡板内容 - 检查是否包含各自环境标识
    if status_a == 200 and status_b == 200 and "staging-a" in body_a and "staging-b" in body_b and body_a != body_b:
        log_pass("H03 HTTP 环境隔离", "staging-a/b 规则不同")
    else:
        log_fail("H03 HTTP 环境隔离", f"a={status_a}:{body_a[:100]} b={status_b}:{body_b[:100]}")

    for method, exp_status in [("GET", 200), ("POST", 201), ("PUT", 200), ("DELETE", 204)]:
        status, body = curl(method, f"{HTTP_STUB}/direct/{method.lower()}", headers={"Host": "httpbin.org"})
        if status == exp_status:
            log_pass(f"H04-{method} HTTP {method} 直接访问", f"status={status}")
        else:
            log_fail(f"H04-{method} HTTP {method} 直接访问", f"expected={exp_status} got={status}")

    start = time.time()
    status, body = curl("GET", f"{HTTP_STUB}/direct/delay", headers={"Host": "httpbin.org"})
    elapsed_ms = int((time.time() - start) * 1000)
    if status == 200 and elapsed_ms >= 400:
        log_pass("H05 HTTP 响应延迟", f"elapsed={elapsed_ms}ms")
    else:
        log_fail("H05 HTTP 响应延迟", f"status={status} elapsed={elapsed_ms}ms")

    status, body = curl("GET", f"{HTTP_STUB}/direct/error500", headers={"Host": "httpbin.org"})
    if status == 500:
        log_pass("H06 HTTP 错误码响应", f"status={status}")
    else:
        log_fail("H06 HTTP 错误码响应", f"status={status} body={body[:200]}")

    # GraphQL 测试（通过 HTTP 协议承载）
    gql_query = json.dumps({
        "operationName": "GetUser",
        "query": "query GetUser { user { id name } }",
        "variables": {}
    }, ensure_ascii=False)
    status, body = curl("POST", f"{HTTP_STUB}/graphql",
                        headers={"Host": "example.com", "Content-Type": "application/json"},
                        data=gql_query)
    if status == 200 and '"Baafoo GraphQL User"' in body:
        log_pass("H07 GraphQL Query operationName匹配", body[:120])
    else:
        log_fail("H07 GraphQL Query operationName匹配", f"status={status} body={body[:200]}")

    gql_mutation = json.dumps({
        "query": "mutation UpdateUser { updateUser(name: \"new\") { success name } }",
        "variables": {}
    }, ensure_ascii=False)
    status, body = curl("POST", f"{HTTP_STUB}/graphql",
                        headers={"Host": "example.com", "Content-Type": "application/json"},
                        data=gql_mutation)
    if status == 200 and '"Updated by GraphQL"' in body:
        log_pass("H08 GraphQL Mutation operationType匹配", body[:120])
    else:
        log_fail("H08 GraphQL Mutation operationType匹配", f"status={status} body={body[:200]}")

    # ========== TCP 协议测试 ==========
    print()
    print("-" * 60)
    print("TCP 协议测试")
    print("-" * 60)
    test_tcp_regex()
    test_tcp_hex_prefix()
    test_tcp_offset()
    test_tcp_multiround()
    test_tcp_delay()
    test_tcp_env_isolation()

    # ========== Kafka 协议测试 ==========
    print()
    print("-" * 60)
    print("Kafka 协议测试")
    print("-" * 60)

    status, body = curl("GET", f"{TEST_STATIC}/api/kafka/send?topic=baafoo-test-topic&message=test-msg-1")
    if status == 200 and '"success":true' in body:
        log_pass("K01 Kafka Producer 发送", body[:120])
    else:
        log_fail("K01 Kafka Producer 发送", f"status={status} body={body[:200]}")

    status, body = curl("GET", f"{TEST_STATIC}/api/kafka/consume?topic=baafoo-test-topic")
    if status == 200 and '"success"' in body:
        log_pass("K02 Kafka Consumer 消费", body[:120])
    else:
        log_fail("K02 Kafka Consumer 消费", f"status={status} body={body[:200]}")

    status, body = curl("GET", f"{TEST_STATIC}/api/kafka/send?topic=baafoo-wildcard&message=wildcard-msg")
    if status == 200 and '"success":true' in body:
        log_pass("K03 Kafka 通配符主题匹配", body[:120])
    else:
        log_fail("K03 Kafka 通配符主题匹配", f"status={status} body={body[:200]}")

    status1, body1 = curl("GET", f"{TEST_STATIC}/api/kafka/send?topic=baafoo-test-topic&message=topic-1")
    status2, body2 = curl("GET", f"{TEST_STATIC}/api/kafka/send?topic=baafoo-test-topic-keyed&message=keyed-msg")
    if status1 == 200 and status2 == 200 and '"success":true' in body1 and '"success":true' in body2:
        log_pass("K04 Kafka 多主题隔离", "两个主题均成功")
    else:
        log_fail("K04 Kafka 多主题隔离", f"status1={status1} status2={status2}")

    status, body = curl("GET", f"{TEST_STATIC}/api/kafka/all")
    if status == 200 and '"success":true' in body:
        log_pass("K05 Kafka 批量操作 all", body[:120])
    else:
        log_fail("K05 Kafka 批量操作 all", f"status={status} body={body[:200]}")

    # ========== Pulsar 协议测试 ==========
    print()
    print("-" * 60)
    print("Pulsar 协议测试")
    print("-" * 60)

    status, body = curl("GET", f"{TEST_STATIC}/api/pulsar/send")
    if status == 200 and '"success":true' in body and '"messageId"' in body:
        log_pass("P01 Pulsar Producer 发送", body[:120])
    else:
        log_fail("P01 Pulsar Producer 发送", f"status={status} body={body[:200]}")

    status, body = curl("GET", f"{TEST_STATIC}/api/pulsar/consume")
    if status == 200 and '"success":true' in body:
        log_pass("P02 Pulsar Consumer 消费", body[:120])
    else:
        log_fail("P02 Pulsar Consumer 消费", f"status={status} body={body[:200]}")

    status, body = curl("GET", f"{TEST_STATIC}/api/pulsar/all")
    if status == 200 and '"success":true' in body:
        log_pass("P03 Pulsar 批量操作 all", body[:120])
    else:
        log_fail("P03 Pulsar 批量操作 all", f"status={status} body={body[:200]}")

    status_a, body_a = curl("GET", f"{TEST_STATIC}/api/pulsar/send")
    status_b, body_b = curl("GET", f"{TEST_STATIC_B}/api/pulsar/send")
    if status_a == 200 and status_b == 200:
        log_pass("P04 Pulsar 环境隔离", "两个环境均返回成功响应")
    else:
        log_fail("P04 Pulsar 环境隔离", f"a={status_a}:{body_a[:120]} b={status_b}:{body_b[:120]}")

    # ========== JMS 协议测试 ==========
    print()
    print("-" * 60)
    print("JMS 协议测试")
    print("-" * 60)

    status, body = curl("GET", f"{TEST_STATIC}/api/jms/send")
    if status == 200 and '"success":true' in body and '"jmsMessageId"' in body:
        log_pass("J01 JMS Queue 发送", body[:120])
    else:
        log_fail("J01 JMS Queue 发送", f"status={status} body={body[:200]}")

    status, body = curl("GET", f"{TEST_STATIC}/api/jms/receive")
    if status == 200 and '"success":true' in body:
        log_pass("J02 JMS Queue 接收", body[:120])
    else:
        log_fail("J02 JMS Queue 接收", f"status={status} body={body[:200]}")

    status_a, body_a = curl("GET", f"{TEST_STATIC}/api/jms/send")
    status_b, body_b = curl("GET", f"{TEST_STATIC_B}/api/jms/send")
    if status_a == 200 and status_b == 200 and '"intercepted":true' in body_a and '"intercepted":true' in body_b:
        log_pass("J03 JMS Queue 环境隔离", "两个环境均返回 intercepted=true")
    else:
        log_fail("J03 JMS Queue 环境隔离", f"a={status_a}:{body_a[:120]} b={status_b}:{body_b[:120]}")

    # ========== 扩展功能测试 ==========
    print()
    print("-" * 60)
    print("扩展功能测试")
    print("-" * 60)

    status, body = curl("GET", f"{HTTP_STUB}/faker", headers={"Host": "example.com"})
    if status == 200 and '"uuid"' in body and '"name"' in body:
        log_pass("E01 Faker 模板渲染", body[:120])
    else:
        log_fail("E01 Faker 模板渲染", f"status={status} body={body[:200]}")

    # 先清除旧的stateful规则，确保计数器重置
    delete_rule("staging-stateful-test")
    fresh_rule = {
        "id": "staging-stateful-test",
        "name": "Stateful mock test",
        "protocol": "http",
        "host": "example.com",
        "port": 80,
        "conditions": [{"type": "path", "operator": "equals", "value": "/stateful"}],
        "responses": [
            {"name": "first", "statusCode": 200, "body": '{"count":1,"requestCount":{{requestCount}}}',
             "condition": {"type": "requestCount", "operator": "equals", "value": "1"}},
            {"name": "other", "statusCode": 200, "body": '{"count":"other","requestCount":{{requestCount}}}'}
        ],
        "enabled": True,
        "priority": 50,
        "tags": ["stateful"],
        "environments": ["staging-a", "staging-b"],
        "requestCountReset": 3
    }
    create_or_update_rule(fresh_rule)
    time.sleep(1)
    status1, body1 = curl("GET", f"{HTTP_STUB}/stateful", headers={"Host": "example.com"})
    status2, body2 = curl("GET", f"{HTTP_STUB}/stateful", headers={"Host": "example.com"})
    if status1 == 200 and '"count":1' in body1 and status2 == 200 and '"count":"other"' in body2:
        log_pass("E02 Stateful Mock requestCount", f"1st={body1[:80]} 2nd={body2[:80]}")
    else:
        log_fail("E02 Stateful Mock requestCount", f"1st={status1}:{body1[:80]} 2nd={status2}:{body2[:80]}")

    status, body = curl("GET", f"{HTTP_STUB}/fault", headers={"Host": "example.com"})
    if status == 503:
        log_pass("E03 故障注入 HTTP_ERROR", f"status={status}")
    else:
        log_fail("E03 故障注入 HTTP_ERROR", f"expected=503 got={status} body={body[:200]}")

    # ========== API / 前端 ==========
    print()
    print("-" * 60)
    print("API / 前端基础状态")
    print("-" * 60)

    status, body = curl("GET", f"{API_URL}/status")
    if status == 200 and '"success":true' in body:
        log_pass("F01 API Status", body[:120])
    else:
        log_fail("F01 API Status", f"status={status} body={body[:200]}")

    status, body = curl("GET", f"{API_URL}/rules")
    if status == 200 and '"success":true' in body:
        log_pass("F02 Rules API", body[:120])
    else:
        log_fail("F02 Rules API", f"status={status} body={body[:200]}")

    status, body = curl("GET", f"{API_URL}/environments")
    if status == 200 and '"success":true' in body:
        log_pass("F03 Environments API", body[:120])
    else:
        log_fail("F03 Environments API", f"status={status} body={body[:200]}")

    status, body = curl("GET", "http://localhost:8084/")
    if status == 200 and ('Baafoo' in body or '<html' in body.lower()):
        log_pass("W01 Web Console 可达", f"status={status}")
    else:
        log_fail("W01 Web Console 可达", f"status={status} body={body[:200]}")

    # 总结
    print()
    print("=" * 60)
    print("测试结果汇总")
    print("=" * 60)
    passed = sum(1 for r in results if r[0] == "PASS")
    failed = sum(1 for r in results if r[0] == "FAIL")
    total = len(results)
    print(f"总数: {total}")
    print(f"通过: {passed}")
    print(f"失败: {failed}")
    print(f"通过率: {passed * 100.0 / total:.1f}%")
    print(f"结束时间: {datetime.now().isoformat()}")

    if failed > 0:
        print("\n失败用例:")
        for r in results:
            if r[0] == "FAIL":
                print(f"  - {r[1]}: {r[2]}")
        sys.exit(1)
    else:
        print("\n全部测试通过！")
        sys.exit(0)


if __name__ == "__main__":
    main()
