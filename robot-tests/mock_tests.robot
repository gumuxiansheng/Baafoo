*** Settings ***
Documentation     Baafoo HTTP 挡板功能测试 - 验证 HTTP Mock 请求匹配和响应返回
Library           RequestsLibrary
Library           Collections
Library           BuiltIn

*** Variables ***
${MANAGE_URL}     http://localhost:8080/__baafoo__/api
${MOCK_URL}       http://localhost:9000
${CONTENT_TYPE}   application/json

*** Keywords ***
创建管理 API 会话
    Create Session    manage    ${MANAGE_URL}    verify=True

创建 Mock 会话
    Create Session    mock    ${MOCK_URL}    verify=True

获取请求头
    ${headers}=    Create Dictionary    Content-Type=${CONTENT_TYPE}
    RETURN    ${headers}

清理测试规则
    [Arguments]    ${rule_id}
    ${headers}=    获取请求头
    DELETE On Session    manage    /rules/${rule_id}    headers=${headers}    expected_status=any

*** Test Cases ***
无匹配规则返回 404
    [Documentation]    未匹配任何规则时，Server 返回 404
    [Tags]    mock    http    negative
    创建 Mock 会话
    ${response}=    GET On Session    mock    /nonexistent-path    headers=&{EMPTY}    expected_status=any
    Should Be Equal As Strings    ${response.status_code}    404

匹配规则返回 Mock 响应
    [Documentation]    创建规则后，Mock 端口返回对应的 Stub 响应
    [Tags]    mock    http    positive
    创建管理 API 会话
    ${headers}=    获取请求头

    # 1. 创建规则
    &{condition}=    Create Dictionary    type=method    operator=equals    value=GET
    &{resp}=    Create Dictionary    name=成功    statusCode=200    body={"code":0,"data":"mocked"}
    @{conditions}=    Create List    ${condition}
    @{responses}=    Create List    ${resp}
    &{rule}=    Create Dictionary
    ...    name=robot-mock-test
    ...    protocol=http
    ...    host=mock-test.example.com
    ...    conditions=@{conditions}
    ...    responses=@{responses}
    ${create_resp}=    POST On Session    manage    /rules    json=${rule}    headers=${headers}
    ${created}=    Set Variable    ${create_resp.json()}
    ${rule_id}=    Set Variable    ${created["id"]}

    # 2. 发送 Mock 请求
    创建 Mock 会话
    &{mock_headers}=    Create Dictionary    Host=mock-test.example.com
    ${mock_resp}=    GET On Session    mock    /    headers=${mock_headers}    expected_status=any
    
    # 3. 清理
    清理测试规则    ${rule_id}
