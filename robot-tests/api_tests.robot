*** Settings ***
Documentation     Baafoo Server REST API 自动化测试套件
Library           RequestsLibrary
Library           Collections
Library           OperatingSystem
Library           String
Library           BuiltIn

*** Variables ***
${BASE_URL}       http://localhost:8080/__baafoo__/api
${CONTENT_TYPE}   application/json

*** Keywords ***
创建 API 会话
    [Documentation]    创建与 Baafoo Server 的会话
    Create Session    baafoo    ${BASE_URL}    verify=True

获取请求头
    [Documentation]    返回标准 JSON 请求头
    ${headers}=    Create Dictionary    Content-Type=${CONTENT_TYPE}
    RETURN    ${headers}

准备空列表
    [Documentation]    返回一个空列表用于条件或响应
    @{empty_list}=    Create List
    RETURN    @{empty_list}

*** Test Cases ***
01 - 系统状态检查
    [Documentation]    验证系统状态接口可访问且返回正确结构
    [Tags]    smoke    status
    创建 API 会话
    ${headers}=    获取请求头
    ${response}=    GET On Session    baafoo    /status    headers=${headers}
    Should Be Equal As Strings    ${response.status_code}    200
    ${json}=    Set Variable    ${response.json()}
    Dictionary Should Contain Key    ${json}    agents
    Dictionary Should Contain Key    ${json}    rules
    Dictionary Should Contain Key    ${json}    environments

02 - 获取规则列表
    [Documentation]    测试 GET /rules 返回列表
    [Tags]    api    rules
    创建 API 会话
    ${headers}=    获取请求头
    ${response}=    GET On Session    baafoo    /rules    headers=${headers}
    Should Be Equal As Strings    ${response.status_code}    200
    ${json}=    Set Variable    ${response.json()}
    Should Be True    isinstance(${json}, list)

03 - 创建规则
    [Documentation]    测试通过 API 创建新规则
    [Tags]    api    rules    create
    创建 API 会话
    ${headers}=    获取请求头
    &{condition}=    Create Dictionary
    ...    type=method
    ...    operator=equals
    ...    value=GET
    &{response_entry}=    Create Dictionary
    ...    name=默认响应
    ...    statusCode=200
    ...    body={"msg":"mock"}
    @{conditions}=    Create List    ${condition}
    @{responses}=    Create List    ${response_entry}
    &{rule}=    Create Dictionary
    ...    name=robot-测试规则
    ...    protocol=http
    ...    host=test.example.com
    ...    port=${8080}
    ...    conditions=@{conditions}
    ...    responses=@{responses}
    ${response}=    POST On Session    baafoo    /rules    json=${rule}    headers=${headers}
    Should Be Equal As Strings    ${response.status_code}    200
    ${json}=    Set Variable    ${response.json()}
    Dictionary Should Contain Key    ${json}    id
    Set Test Variable    ${CREATED_RULE_ID}    ${json["id"]}

04 - 获取单个规则
    [Documentation]    测试通过 ID 获取规则详情
    [Tags]    api    rules
    [Setup]    Run Keywords    创建 API 会话    AND    ${headers}=    获取请求头
    &{condition}=    Create Dictionary    type=method    operator=equals    value=GET
    @{conditions}=    Create List    ${condition}
    &{response_entry}=    Create Dictionary    name=默认    statusCode=200    body={}
    @{responses}=    Create List    ${response_entry}
    &{rule}=    Create Dictionary
    ...    name=robot-获取测试
    ...    protocol=http
    ...    host=get-test.example.com
    ...    conditions=@{conditions}
    ...    responses=@{responses}
    ${create_resp}=    POST On Session    baafoo    /rules    json=${rule}    headers=${headers}
    ${rule_json}=    Set Variable    ${create_resp.json()}
    ${rule_id}=    Set Variable    ${rule_json["id"]}
    ${response}=    GET On Session    baafoo    /rules/${rule_id}    headers=${headers}
    Should Be Equal As Strings    ${response.status_code}    200

05 - 获取环境列表
    [Documentation]    测试 GET /environments 返回列表
    [Tags]    api    environments
    创建 API 会话
    ${headers}=    获取请求头
    ${response}=    GET On Session    baafoo    /environments    headers=${headers}
    Should Be Equal As Strings    ${response.status_code}    200
    ${json}=    Set Variable    ${response.json()}
    Should Be True    isinstance(${json}, list)

06 - 创建环境
    [Documentation]    测试创建新环境
    [Tags]    api    environments    create
    创建 API 会话
    ${headers}=    获取请求头
    &{env}=    Create Dictionary
    ...    name=robot-test-env
    ...    mode=stub
    ...    description=Robot Framework 自动化测试环境
    ${response}=    POST On Session    baafoo    /environments    json=${env}    headers=${headers}
    Should Be Equal As Strings    ${response.status_code}    200
    ${json}=    Set Variable    ${response.json()}
    Dictionary Should Contain Key    ${json}    id
    Set Test Variable    ${CREATED_ENV_ID}    ${json["id"]}

07 - 获取场景集列表
    [Documentation]    测试 GET /scenes 返回列表
    [Tags]    api    scenes
    创建 API 会话
    ${headers}=    获取请求头
    ${response}=    GET On Session    baafoo    /scenes    headers=${headers}
    Should Be Equal As Strings    ${response.status_code}    200
    ${json}=    Set Variable    ${response.json()}
    Should Be True    isinstance(${json}, list)

08 - 获取 Agent 列表
    [Documentation]    测试 GET /agents 返回列表
    [Tags]    api    agents
    创建 API 会话
    ${headers}=    获取请求头
    ${response}=    GET On Session    baafoo    /agents    headers=${headers}
    Should Be Equal As Strings    ${response.status_code}    200
    ${json}=    Set Variable    ${response.json()}
    Should Be True    isinstance(${json}, list)

09 - 获取规则集列表
    [Documentation]    测试 GET /rulesets 返回列表
    [Tags]    api    rulesets
    创建 API 会话
    ${headers}=    获取请求头
    ${response}=    GET On Session    baafoo    /rulesets    headers=${headers}
    Should Be Equal As Strings    ${response.status_code}    200
    ${json}=    Set Variable    ${response.json()}
    Should Be True    isinstance(${json}, list)

10 - 获取录制列表
    [Documentation]    测试 GET /recordings 返回列表
    [Tags]    api    recordings
    创建 API 会话
    ${headers}=    获取请求头
    ${response}=    GET On Session    baafoo    /recordings    headers=${headers}
    Should Be Equal As Strings    ${response.status_code}    200
    ${json}=    Set Variable    ${response.json()}
    Should Be True    isinstance(${json}, list)

11 - 未知端点返回 404
    [Documentation]    测试访问不存在的 API 路径返回 404
    [Tags]    api    negative
    创建 API 会话
    ${headers}=    获取请求头
    ${response}=    GET On Session    baafoo    /unknown    headers=${headers}    expected_status=any
    Should Be Equal As Strings    ${response.status_code}    404

12 - OPTIONS 请求返回 200
    [Documentation]    测试 OPTIONS 预检请求
    [Tags]    api    cors
    创建 API 会话
    ${headers}=    获取请求头
    ${response}=    OPTIONS On Session    baafoo    /rules    headers=${headers}
    Should Be Equal As Strings    ${response.status_code}    200
