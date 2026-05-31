#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Baafoo API 批量测试脚本
测试所有管理 API 接口（正常/异常/边界场景）
"""

import requests
import json
import time
from datetime import datetime

# 配置
BASE_URL = "http://localhost:8080/__baafoo__/api"
TIMEOUT = 10

# 测试结果统计
test_results = {
    "total": 0,
    "passed": 0,
    "failed": 0,
    "errors": []
}

def log_test(name, method, url, expected_status, actual_status, response, error=None):
    """记录测试结果"""
    test_results["total"] += 1
    
    result = {
        "name": name,
        "method": method,
        "url": url,
        "expected_status": expected_status,
        "actual_status": actual_status,
        "response": response[:500] if isinstance(response, str) else str(response)[:500],
        "timestamp": datetime.now().isoformat()
    }
    
    if error:
        result["error"] = str(error)
        test_results["failed"] += 1
        test_results["errors"].append(result)
        print(f"[FAIL] {name}")
        print(f"   错误: {error}")
    elif actual_status == expected_status:
        test_results["passed"] += 1
        print(f"[PASS] {name}")
    else:
        test_results["failed"] += 1
        test_results["errors"].append(result)
        print(f"[FAIL] {name}")
        print(f"   期望状态码: {expected_status}, 实际状态码: {actual_status}")
    
    return result

def test_get_rules():
    """测试 GET /rules - 列出所有规则"""
    print("\n>>> 测试规则管理 API")
    print("=" * 60)
    
    # 正常场景
    try:
        url = f"{BASE_URL}/rules"
        resp = requests.get(url, timeout=TIMEOUT)
        log_test(
            "列出所有规则",
            "GET",
            url,
            200,
            resp.status_code,
            resp.text
        )
    except Exception as e:
        log_test("列出所有规则", "GET", url, 200, 0, None, e)
    
    # 测试分页参数（边界场景）
    try:
        url = f"{BASE_URL}/rules?page=1&size=10"
        resp = requests.get(url, timeout=TIMEOUT)
        log_test(
            "列出规则（带分页参数）",
            "GET",
            url,
            200,
            resp.status_code,
            resp.text
        )
    except Exception as e:
        log_test("列出规则（带分页参数）", "GET", url, 200, 0, None, e)

def test_create_rule():
    """测试 POST /rules - 创建规则"""
    # 正常场景
    rule_data = {
        "name": f"test-rule-{int(time.time())}",
        "protocol": "http",
        "host": "api.example.com",
        "port": 8080,
        "conditions": [
            {"type": "method", "operator": "equals", "value": "GET"},
            {"type": "path", "operator": "startsWith", "value": "/api/users"}
        ],
        "responses": [
            {
                "name": "成功响应",
                "statusCode": 200,
                "headers": {"Content-Type": "application/json"},
                "body": json.dumps({"code": 0, "data": []})
            }
        ],
        "enabled": True,
        "priority": 100,
        "tags": ["test"],
        "environments": []
    }
    
    try:
        url = f"{BASE_URL}/rules"
        resp = requests.post(url, json=rule_data, timeout=TIMEOUT)
        log_test(
            "创建规则（正常场景）",
            "POST",
            url,
            200,
            resp.status_code,
            resp.text
        )
        
        if resp.status_code == 200:
            return resp.json().get("data", {}).get("id")
    except Exception as e:
        log_test("创建规则（正常场景）", "POST", url, 200, 0, None, e)
    
    return None

def test_get_rule_by_id(rule_id):
    """测试 GET /rules/{id} - 获取规则详情"""
    if not rule_id:
        return
    
    # 正常场景
    try:
        url = f"{BASE_URL}/rules/{rule_id}"
        resp = requests.get(url, timeout=TIMEOUT)
        log_test(
            "获取规则详情",
            "GET",
            url,
            200,
            resp.status_code,
            resp.text
        )
    except Exception as e:
        log_test("获取规则详情", "GET", url, 200, 0, None, e)
    
    # 异常场景：不存在的 ID
    try:
        url = f"{BASE_URL}/rules/non-existent-id"
        resp = requests.get(url, timeout=TIMEOUT)
        log_test(
            "获取规则详情（不存在的ID）",
            "GET",
            url,
            404,
            resp.status_code,
            resp.text
        )
    except Exception as e:
        log_test("获取规则详情（不存在的ID）", "GET", url, 404, 0, None, e)

def test_update_rule(rule_id):
    """测试 PUT /rules/{id} - 更新规则"""
    if not rule_id:
        return
    
    # 正常场景
    update_data = {
        "name": f"updated-rule-{int(time.time())}",
        "priority": 200
    }
    
    try:
        url = f"{BASE_URL}/rules/{rule_id}"
        resp = requests.put(url, json=update_data, timeout=TIMEOUT)
        log_test(
            "更新规则",
            "PUT",
            url,
            200,
            resp.status_code,
            resp.text
        )
    except Exception as e:
        log_test("更新规则", "PUT", url, 200, 0, None, e)
    
    # 异常场景：不存在的 ID
    try:
        url = f"{BASE_URL}/rules/non-existent-id"
        resp = requests.put(url, json=update_data, timeout=TIMEOUT)
        log_test(
            "更新规则（不存在的ID）",
            "PUT",
            url,
            404,
            resp.status_code,
            resp.text
        )
    except Exception as e:
        log_test("更新规则（不存在的ID）", "PUT", url, 404, 0, None, e)

def test_delete_rule(rule_id):
    """测试 DELETE /rules/{id} - 删除规则"""
    if not rule_id:
        return
    
    # 正常场景
    try:
        url = f"{BASE_URL}/rules/{rule_id}"
        resp = requests.delete(url, timeout=TIMEOUT)
        log_test(
            "删除规则",
            "DELETE",
            url,
            200,
            resp.status_code,
            resp.text
        )
    except Exception as e:
        log_test("删除规则", "DELETE", url, 200, 0, None, e)
    
    # 异常场景：重复删除
    try:
        url = f"{BASE_URL}/rules/{rule_id}"
        resp = requests.delete(url, timeout=TIMEOUT)
        log_test(
            "删除规则（重复删除）",
            "DELETE",
            url,
            404,
            resp.status_code,
            resp.text
        )
    except Exception as e:
        log_test("删除规则（重复删除）", "DELETE", url, 404, 0, None, e)

def test_environments():
    """测试环境管理 API"""
    print("\n>>> 测试环境管理 API")
    print("=" * 60)
    
    # 列出所有环境
    try:
        url = f"{BASE_URL}/environments"
        resp = requests.get(url, timeout=TIMEOUT)
        log_test(
            "列出所有环境",
            "GET",
            url,
            200,
            resp.status_code,
            resp.text
        )
    except Exception as e:
        log_test("列出所有环境", "GET", url, 200, 0, None, e)
    
    # 创建环境
    env_data = {
        "name": f"test-env-{int(time.time())}",
        "mode": "stub",
        "description": "自动化测试环境"
    }
    
    try:
        url = f"{BASE_URL}/environments"
        resp = requests.post(url, json=env_data, timeout=TIMEOUT)
        log_test(
            "创建环境",
            "POST",
            url,
            200,
            resp.status_code,
            resp.text
        )
        
        if resp.status_code == 200:
            env_id = resp.json().get("data", {}).get("id")
            return env_id
    except Exception as e:
        log_test("创建环境", "POST", url, 200, 0, None, e)
    
    return None

def test_scenes():
    """测试场景集管理 API"""
    print("\n>>> 测试场景集管理 API")
    print("=" * 60)
    
    # 列出所有场景集
    try:
        url = f"{BASE_URL}/scenes"
        resp = requests.get(url, timeout=TIMEOUT)
        log_test(
            "列出所有场景集",
            "GET",
            url,
            200,
            resp.status_code,
            resp.text
        )
    except Exception as e:
        log_test("列出所有场景集", "GET", url, 200, 0, None, e)

def test_agent_apis():
    """测试 Agent 控制通道 API"""
    print("\n>>> 测试 Agent 控制通道 API")
    print("=" * 60)
    
    # Agent 注册
    agent_data = {
        "agentId": f"test-agent-{int(time.time())}",
        "environment": "test",
        "hostname": "test-host",
        "version": "1.0.0",
        "protocols": ["http"]
    }
    
    try:
        url = f"{BASE_URL}/agent/register"
        resp = requests.post(url, json=agent_data, timeout=TIMEOUT)
        log_test(
            "Agent 注册",
            "POST",
            url,
            200,
            resp.status_code,
            resp.text
        )
    except Exception as e:
        log_test("Agent 注册", "POST", url, 200, 0, None, e)
    
    # Agent 心跳
    try:
        url = f"{BASE_URL}/agent/heartbeat"
        resp = requests.post(url, json={"agentId": agent_data["agentId"]}, timeout=TIMEOUT)
        log_test(
            "Agent 心跳",
            "POST",
            url,
            200,
            resp.status_code,
            resp.text
        )
    except Exception as e:
        log_test("Agent 心跳", "POST", url, 200, 0, None, e)

def test_system_api():
    """测试系统 API"""
    print("\n>>> 测试系统 API")
    print("=" * 60)
    
    # 系统状态
    try:
        url = f"{BASE_URL}/status"
        resp = requests.get(url, timeout=TIMEOUT)
        log_test(
            "获取系统状态",
            "GET",
            url,
            200,
            resp.status_code,
            resp.text
        )
    except Exception as e:
        log_test("获取系统状态", "GET", url, 200, 0, None, e)

def generate_report():
    """生成测试报告"""
    print("\n" + "=" * 60)
    print(">>> 测试报告")
    print("=" * 60)
    
    print(f"\n总测试用例数: {test_results['total']}")
    print(f"[PASS] 通过: {test_results['passed']}")
    print(f"[FAIL] 失败: {test_results['failed']}")
    print(f"通过率: {test_results['passed']/test_results['total']*100:.2f}%")
    
    if test_results['errors']:
        print("\n[FAIL] 失败用例详情:")
        for i, error in enumerate(test_results['errors'], 1):
            print(f"\n{i}. {error['name']}")
            print(f"   方法: {error['method']}")
            print(f"   URL: {error['url']}")
            print(f"   期望状态码: {error['expected_status']}")
            print(f"   实际状态码: {error['actual_status']}")
            if 'error' in error:
                print(f"   错误: {error['error']}")
            print(f"   响应: {error['response'][:200]}...")
    
    # 保存报告到文件
    report = {
        "timestamp": datetime.now().isoformat(),
        "summary": {
            "total": test_results['total'],
            "passed": test_results['passed'],
            "failed": test_results['failed'],
            "pass_rate": f"{test_results['passed']/test_results['total']*100:.2f}%"
        },
        "details": test_results['errors']
    }
    
    report_file = f"C:\\Dev\\Projects\\Baafoo\\api_test_report_{int(time.time())}.json"
    with open(report_file, 'w', encoding='utf-8') as f:
        json.dump(report, f, ensure_ascii=False, indent=2)
    
    print(f"\n详细报告已保存到: {report_file}")
    
    return report_file

def main():
    """主测试流程"""
    print("=" * 60)
    print(">>> 开始 Baafoo API 批量测试")
    print(f"测试时间: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
    print(f"目标服务器: {BASE_URL}")
    print("=" * 60)
    
    # 测试系统 API
    test_system_api()
    
    # 测试规则管理 API
    test_get_rules()
    rule_id = test_create_rule()
    test_get_rule_by_id(rule_id)
    test_update_rule(rule_id)
    
    # 测试环境管理 API
    env_id = test_environments()
    
    # 测试场景集管理 API
    test_scenes()
    
    # 测试 Agent 控制通道 API
    test_agent_apis()
    
    # 清理测试数据
    test_delete_rule(rule_id)
    
    # 生成测试报告
    report_file = generate_report()
    
    print("\n>>> 测试完成！")
    
    return report_file

if __name__ == "__main__":
    main()
