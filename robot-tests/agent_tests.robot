*** Settings ***
Documentation     Baafoo Agent 控制通道集成测试 - 注册、心跳、轮询
Library           RequestsLibrary
Library           Collections
Library           BuiltIn

*** Variables ***
${BASE_URL}       http://localhost:8080/__baafoo__/api
${CONTENT_TYPE}   application/json
${TEST_AGENT_ID}  robot-test-agent-001

*** Keywords ***
创建 API 会话
    Create Session    baafoo    ${BASE_URL}    verify=True

获取请求头
    ${headers}=    Create Dictionary    Content-Type=${CONTENT_TYPE}
    RETURN    ${headers}

*** Test Cases ***
Agent 注册
    [Documentation]    测试 Agent 向 Server 注册
    [Tags]    agent    registration
    创建 API 会话
    ${headers}=    获取请求头
    &{body}=    Create Dictionary
    ...    agentId=${TEST_AGENT_ID}
    ...    environment=robot-test
    ${response}=    POST On Session    baafoo    /agent/register    json=${body}    headers=${headers}
    Should Be Equal As Strings    ${response.status_code}    200

Agent 心跳
    [Documentation]    测试 Agent 发送心跳
    [Tags]    agent    heartbeat
    创建 API 会话
    ${headers}=    获取请求头
    &{body}=    Create Dictionary
    ...    agentId=${TEST_AGENT_ID}
    ${response}=    POST On Session    baafoo    /agent/heartbeat    json=${body}    headers=${headers}
    Should Be Equal As Strings    ${response.status_code}    200

Agent 轮询规则
    [Documentation]    测试 Agent 拉取规则和模式
    [Tags]    agent    poll
    创建 API 会话
    ${headers}=    获取请求头
    ${response}=    GET On Session    baafoo    /agent/poll?agentId=${TEST_AGENT_ID}    headers=${headers}
    Should Be Equal As Strings    ${response.status_code}    200

Agent 上传录制数据
    [Documentation]    测试 Agent 上传录制记录
    [Tags]    agent    recordings
    创建 API 会话
    ${headers}=    获取请求头
    @{recordings}=    Create List
    &{entry}=    Create Dictionary
    ...    protocol=http
    ...    method=GET
    ...    host=test.example.com
    ...    path=/api/test
    ...    statusCode=200
    ...    requestBody=
    ...    responseBody={"ok":true}
    Append To List    ${recordings}    ${entry}
    ${response}=    POST On Session    baafoo    /agent/recordings    json=${recordings}    headers=${headers}
    Should Be Equal As Strings    ${response.status_code}    200
