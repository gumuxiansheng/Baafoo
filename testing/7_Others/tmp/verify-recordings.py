#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
验证 Baafoo 多协议录制记录完整性
检查 HTTP / TCP / Kafka / Pulsar / JMS 是否都有非空 environmentId 的录制记录
"""
import json
import subprocess
import sys

API_URL = "http://localhost:8084/__baafoo__/api"
API_KEY = "staging-admin-key"


def api(method, path, params=None):
    url = f"{API_URL}{path}"
    if params:
        url += "?" + "&".join(f"{k}={v}" for k, v in params.items())
    cmd = ["curl.exe", "-s", "-X", method, url, "-H", f"X-Api-Key: {API_KEY}"]
    out = subprocess.run(cmd, capture_output=True, text=True, timeout=30)
    if out.returncode != 0:
        raise RuntimeError(out.stderr)
    return json.loads(out.stdout)


def main():
    protocols = ["http", "tcp", "kafka", "pulsar", "jms"]
    ok = True
    print("=" * 60)
    print("多协议录制记录验证")
    print("=" * 60)

    for protocol in protocols:
        resp = api("GET", "/recordings", {"page": "1", "size": "100", "protocol": protocol})
        items = resp.get("data", {}).get("items", [])
        total = resp.get("data", {}).get("total", 0)
        with_env = sum(1 for r in items if r.get("environmentId"))
        without_env = total - with_env

        status = "OK" if total > 0 and without_env == 0 else "FAIL"
        if status == "FAIL":
            ok = False
        print(f"[{status}] {protocol:8s} total={total:3d}  withEnv={with_env:3d}  missingEnv={without_env:3d}")

        # 显示前两条记录概要
        for r in items[:2]:
            env = r.get("environmentId") or "NULL"
            agent = r.get("agentId") or "NULL"
            path = r.get("path") or r.get("serviceName") or "-"
            body = (r.get("requestBody") or "")[:60].replace("\n", " ")
            print(f"       -> env={env} agent={agent} path={path} body={body}")

    print("=" * 60)
    if ok:
        print("所有协议均有 environmentId 完整的录制记录")
        sys.exit(0)
    else:
        print("存在录制记录缺失 environmentId 或某协议无录制记录")
        sys.exit(1)


if __name__ == "__main__":
    main()
