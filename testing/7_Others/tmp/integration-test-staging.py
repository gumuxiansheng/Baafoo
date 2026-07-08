#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Baafoo Docker Staging 全协议集成测试
覆盖 HTTP / TCP / Kafka / Pulsar / JMS 以及扩展功能（故障注入、Stateful Mock、Faker）
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
TCP_STUB = ("localhost", 9001)
KAFKA_STUB = ("localhost", 9002)
PULSAR_STUB = ("localhost", 9003)
JMS_STUB = ("localhost", 9004)

HTTPBIN_HOST = "httpbin.org"
EXAMPLE_HOST = "example.com"

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
        out = subprocess.run(cmd, capture_output=True, text=True, timeout=30)
        lines = out.stdout.strip().splitlines()
        status = int(lines[-1]) if lines else 0
        body = "\n".join(lines[:-1])
        return status, body
    except Exception as e:
        return 0, str(e)


def api_key_headers(extra=None):
    h = {"Content-Type": "application/json", "X-Api-Key": API_KEY}
    if extra:
        h.update(extra)
    return h


def create_or_update_rule(rule):
    """创建或更新规则；失败时记录日志但继续。"""
    rule_json = json.dumps(rule, ensure_ascii=False)
    status, body = curl("POST", f"{API_URL}/rules", headers=api_key_headers(), data=rule_json)
    if status not in (200, 201):
        status, body = curl("PUT", f"{API_URL}/rules/{rule['id']}", headers=api_key_headers(), data=rule_json)
    return status, body


def delete_rule(rule_id):
    curl("DELETE", f"{API_URL}/rules/{rule_id}", headers={"X-Api-Key": API_KEY})


def setup_direct_http_rules():
    """为直接访问 stub server 创建环境无关的高优先级规则。"""
    cases = [
        ("GET", "/direct/get", 200),
        ("POST", "/direct/post", 201),
        ("PUT", "/direct/put", 200),
        ("DELETE", "/direct/delete", 204),
    ]
    for method, path, exp_status in cases:
        rule_id = f"staging-direct-http-{method.lower()}"
        delete_rule(rule_id)
        rule = {
            "id": rule_id,
            "name": f"Direct HTTP {method} stub",
            "protocol": "http",
            "host": HTTPBIN_HOST,
            "port": 80,
            "conditions": [
                {"type": "method", "operator": "equals", "value": method},
                {"type": "path", "operator": "startsWith", "value": path},
            ],
            "responses": [
                {
                    "name": f"{method} response",
                    "statusCode": exp_status,
                    "body": json.dumps({"mocked": True, "method": method, "source": "direct"}, separators=(',', ':')),
                }
            ],
            "enabled": True,
            "priority": 10,
            "tags": ["direct", "http"],
            "environments": ["staging-a", "staging-b"],
        }
        status, body = create_or_update_rule(rule)
        if status not in (200, 201):
            log_fail(f"Setup direct {method} rule", f"status={status} body={body[:200]}")


def setup_direct_delay_rule():
    delete_rule("staging-direct-delay")
    rule = {
        "id": "staging-direct-delay",
        "name": "Direct HTTP delay stub",
        "protocol": "http",
        "host": HTTPBIN_HOST,
        "port": 80,
        "conditions": [{"type": "path", "operator": "equals", "value": "/direct/delay"}],
        "responses": [
            {"name": "delay response", "statusCode": 200, "body": '{"mocked":true,"delayed":true}', "delayMs": 500}
        ],
        "enabled": True,
        "priority": 10,
        "tags": ["direct", "delay"],
        "environments": ["staging-a", "staging-b"],
    }
    create_or_update_rule(rule)


def setup_direct_error_rule():
    delete_rule("staging-direct-error")
    rule = {
        "id": "staging-direct-error",
        "name": "Direct HTTP error stub",
        "protocol": "http",
        "host": HTTPBIN_HOST,
        "port": 80,
        "conditions": [{"type": "path", "operator": "equals", "value": "/direct/error500"}],
        "responses": [
            {"name": "error response", "statusCode": 500, "body": '{"mocked":true,"error":true}'}
        ],
        "enabled": True,
        "priority": 10,
        "tags": ["direct", "error"],
        "environments": ["staging-a", "staging-b"],
    }
    create_or_update_rule(rule)


def setup_faker_rule():
    delete_rule("staging-faker-test")
    rule = {
        "id": "staging-faker-test",
        "name": "Faker test rule",
        "protocol": "http",
        "host": EXAMPLE_HOST,
        "port": 80,
        "conditions": [{"type": "path", "operator": "equals", "value": "/faker"}],
        "responses": [
            {"name": "faker", "statusCode": 200, "body": '{"uuid":"{{faker.uuid}}","name":"{{faker.name}}"}'}
        ],
        "enabled": True,
        "priority": 10,
        "tags": ["faker"],
        "environments": ["staging-a", "staging-b"],
    }
    create_or_update_rule(rule)


def setup_stateful_rule():
    delete_rule("staging-stateful-test")
    rule = {
        "id": "staging-stateful-test",
        "name": "Stateful mock test",
        "protocol": "http",
        "host": EXAMPLE_HOST,
        "port": 80,
        "conditions": [{"type": "path", "operator": "equals", "value": "/stateful"}],
        "responses": [
            {
                "name": "first",
                "statusCode": 200,
                "body": '{"count":1,"requestCount":{{requestCount}}}',
                "condition": {"type": "requestCount", "operator": "equals", "value": "1"},
            },
            {"name": "other", "statusCode": 200, "body": '{"count":"other","requestCount":{{requestCount}}}'},
        ],
        "enabled": True,
        "priority": 10,
        "tags": ["stateful"],
        "environments": ["staging-a", "staging-b"],
        "requestCountReset": 3,
    }
    create_or_update_rule(rule)


def setup_fault_rule():
    delete_rule("staging-fault-test")
    rule = {
        "id": "staging-fault-test",
        "name": "Fault injection test",
        "protocol": "http",
        "host": EXAMPLE_HOST,
        "port": 80,
        "conditions": [{"type": "path", "operator": "equals", "value": "/fault"}],
        "responses": [{"name": "ok", "statusCode": 200, "body": '{"ok":true}'}],
        "enabled": True,
        "priority": 10,
        "tags": ["fault"],
        "environments": ["staging-a", "staging-b"],
        "faultInjection": {"faults": [{"type": "HTTP_ERROR", "probability": 1.0, "statusCodes": [503]}]},
    }
    create_or_update_rule(rule)


def test_api_status():
    status, body = curl("GET", f"{API_URL}/status")
    if status == 200 and '"success":true' in body:
        log_pass("F01 API status", body[:120])
    else:
        log_fail("F01 API status", f"status={status} body={body[:200]}")


def test_web_console():
    status, body = curl("GET", "http://localhost:8084/")
    if status == 200 and ("Baafoo" in body or "<html" in body.lower()):
        log_pass("W01 Web console reachable", f"status={status}")
    else:
        log_fail("W01 Web console reachable", f"status={status} body={body[:200]}")


def test_http_basic():
    """Agent 流量拦截：环境 A 调用外部 HTTP 被挡板拦截。"""
    status, body = curl("GET", "http://localhost:9090/api/http/get?url=http://httpbin.org/get")
    if status == 200 and '"stubbed":true' in body and 'mocked' in body and 'staging-a' in body:
        log_pass("H01 HTTP basic stub via Agent", body[:120])
    else:
        log_fail("H01 HTTP basic stub via Agent", f"status={status} body={body[:200]}")


def test_http_env_isolation():
    """Agent 环境隔离：A/B 两个环境返回各自的挡板规则。"""
    status_a, body_a = curl("GET", "http://localhost:9090/api/http/methods")
    status_b, body_b = curl("GET", "http://localhost:9091/api/http/methods")
    ok = (
        status_a == 200
        and '"stubbed":true' in body_a
        and 'mocked' in body_a
        and 'staging-a' in body_a
        and status_b == 200
        and '"stubbed":true' in body_b
        and 'mocked' in body_b
        and 'staging-b' in body_b
    )
    if ok:
        log_pass("H02 HTTP env isolation", f"a={body_a[:80]} b={body_b[:80]}")
    else:
        log_fail("H02 HTTP env isolation", f"a={status_a}:{body_a[:80]} b={status_b}:{body_b[:80]}")


def test_http_methods():
    cases = [
        ("GET", "/direct/get", 200),
        ("POST", "/direct/post", 201),
        ("PUT", "/direct/put", 200),
        ("DELETE", "/direct/delete", 204),
    ]
    all_ok = True
    for method, path, exp_status in cases:
        status, body = curl(method, f"{HTTP_STUB}{path}", headers={"Host": HTTPBIN_HOST})
        ok = status == exp_status
        if method != "DELETE":
            ok = ok and '"mocked":true' in body
        if ok:
            log_pass(f"H03 HTTP {method} stub", f"status={status}")
        else:
            log_fail(f"H03 HTTP {method} stub", f"expected={exp_status} got={status} body={body[:200]}")
            all_ok = False
    return all_ok


def test_http_delay():
    start = time.time()
    status, body = curl("GET", f"{HTTP_STUB}/direct/delay", headers={"Host": HTTPBIN_HOST})
    elapsed = int((time.time() - start) * 1000)
    if status == 200 and elapsed >= 400 and '"delayed":true' in body:
        log_pass("H04 HTTP delay", f"status={status} delay={elapsed}ms")
    else:
        log_fail("H04 HTTP delay", f"status={status} delay={elapsed}ms body={body[:200]}")


def test_http_error():
    status, body = curl("GET", f"{HTTP_STUB}/direct/error500", headers={"Host": HTTPBIN_HOST})
    if status == 500 and '"error":true' in body:
        log_pass("H05 HTTP error status", f"status={status}")
    else:
        log_fail("H05 HTTP error status", f"expected=500 got={status} body={body[:200]}")


def test_http_faker():
    status, body = curl("GET", f"{HTTP_STUB}/faker", headers={"Host": EXAMPLE_HOST})
    if status == 200 and '"uuid"' in body and '"name"' in body:
        log_pass("E01 Faker variable rendering", body[:120])
    else:
        log_fail("E01 Faker variable rendering", f"status={status} body={body[:200]}")


def test_stateful_mock():
    status1, body1 = curl("GET", f"{HTTP_STUB}/stateful", headers={"Host": EXAMPLE_HOST})
    status2, body2 = curl("GET", f"{HTTP_STUB}/stateful", headers={"Host": EXAMPLE_HOST})
    if (
        status1 == 200
        and '"count":1' in body1
        and status2 == 200
        and '"count":"other"' in body2
    ):
        log_pass("E02 Stateful mock requestCount", f"1st={body1[:80]} 2nd={body2[:80]}")
    else:
        log_fail("E02 Stateful mock requestCount", f"1st={status1}:{body1[:80]} 2nd={status2}:{body2[:80]}")


def test_fault_injection():
    status, body = curl("GET", f"{HTTP_STUB}/fault", headers={"Host": EXAMPLE_HOST})
    if status == 503:
        log_pass("E03 Fault injection HTTP_ERROR", f"status={status}")
    else:
        log_fail("E03 Fault injection HTTP_ERROR", f"expected=503 got={status} body={body[:200]}")


def tcp_send_recv(payload, timeout=5):
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s.settimeout(timeout)
        s.connect(TCP_STUB)
        s.sendall(payload)
        resp = s.recv(4096)
        s.close()
        return True, resp
    except Exception as e:
        return False, str(e).encode()


def test_tcp():
    ok, resp = tcp_send_recv(b"HELLO BAFOO")
    if ok and b"TCP" in resp:
        log_pass("T01 TCP regex stub", resp.decode(errors="replace")[:80])
    else:
        log_fail("T01 TCP regex stub", f"ok={ok} resp={resp[:200]}")


def test_kafka_topic():
    url = "http://localhost:9090/api/kafka/send?bootstrapServers=kafka-broker:9092&topic=baafoo-test-topic&message=hello-baafoo"
    status, body = curl("GET", url)
    if status == 200 and '"success":true' in body and '"partition"' in body:
        log_pass("K01 Kafka topic produce via Agent", body[:120])
    else:
        log_fail("K01 Kafka topic produce via Agent", f"status={status} body={body[:200]}")


def test_kafka_wildcard():
    url = "http://localhost:9090/api/kafka/send?bootstrapServers=kafka-broker:9092&topic=baafoo-wildcard&message=hello-wildcard"
    status, body = curl("GET", url)
    if status == 200 and '"success":true' in body:
        log_pass("K02 Kafka wildcard topic via Agent", body[:120])
    else:
        log_fail("K02 Kafka wildcard topic via Agent", f"status={status} body={body[:200]}")


def test_pulsar_topic():
    url = "http://localhost:9090/api/pulsar/send?serviceUrl=pulsar://pulsar-broker:6650&topic=persistent://public/default/baafoo-test-topic&message=hello-pulsar"
    status, body = curl("GET", url)
    if status == 200 and '"success":true' in body and '"messageId"' in body:
        log_pass("P01 Pulsar topic produce via Agent", body[:120])
    else:
        log_fail("P01 Pulsar topic produce via Agent", f"status={status} body={body[:200]}")


def test_jms_queue():
    url = "http://localhost:9090/api/jms/send?brokerUrl=tcp://jms-broker:61616&queueName=BAAFOO.TEST.QUEUE&message=hello-jms"
    status, body = curl("GET", url)
    if status == 200 and '"success":true' in body and '"jmsMessageId"' in body:
        log_pass("J01 JMS queue send via Agent", body[:120])
    else:
        log_fail("J01 JMS queue send via Agent", f"status={status} body={body[:200]}")


def main():
    print("=== Baafoo Docker Staging 全协议集成测试 ===")
    print(f"Start: {datetime.now().isoformat()}")
    print()

    # 清理并创建直接访问 stub server 的规则
    setup_direct_http_rules()
    setup_direct_delay_rule()
    setup_direct_error_rule()
    setup_faker_rule()
    setup_stateful_rule()
    setup_fault_rule()

    # 等待规则生效
    time.sleep(3)

    test_api_status()
    test_web_console()
    test_http_basic()
    test_http_env_isolation()
    test_http_methods()
    test_http_delay()
    test_http_error()
    test_http_faker()
    test_stateful_mock()
    test_fault_injection()
    test_tcp()
    test_kafka_topic()
    test_kafka_wildcard()
    test_pulsar_topic()
    test_jms_queue()

    print()
    print("=== Summary ===")
    passed = sum(1 for r in results if r[0] == "PASS")
    failed = sum(1 for r in results if r[0] == "FAIL")
    print(f"Total: {len(results)}  Passed: {passed}  Failed: {failed}")
    print(f"End: {datetime.now().isoformat()}")

    if failed > 0:
        print("\nFailed cases:")
        for r in results:
            if r[0] == "FAIL":
                print(f"  - {r[1]}: {r[2]}")
        sys.exit(1)
    else:
        print("\nAll tests passed.")
        sys.exit(0)


if __name__ == "__main__":
    main()
