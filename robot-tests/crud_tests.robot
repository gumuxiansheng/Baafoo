*** Settings ***
Documentation     Baafoo 完整 CRUD 流程测试 - 规则/环境/场景集的创建→查询→更新→删除
Library           RequestsLibrary
Library           Collections
Library           BuiltIn

*** Variables ***
${BASE_URL}       http://localhost:8080/__baafoo__/api
${CONTENT_TYPE}   application/json

*** Keywords ***
创建 API 会话
    Create Session    baafoo    ${BASE_URL}    verify=True

获取请求头
    ${headers}=    Create Dictionary    Content-Type=${CONTENT_TYPE}
    RETURN    ${headers}

*** Test Cases ***
规则完整生命周期
    [Documentation]    规则 CRUD: 创建 → 查询 → 更新 → 删除
    [Tags]    crud    rules
    创建 API 会话
    ${headers}=    获取请求头

    # 1. 创建规则
    &{condition}=    Create Dictionary    type=method    operator=equals    value=GET
    &{resp}=    Create Dictionary    name=成功    statusCode=200    body={"code":0,"data":[]}
    @{conditions}=    Create List    ${condition}
    @{responses}=    Create List    ${resp}
    &{rule}=    Create Dictionary
    ...    name=robot-crud-rule
    ...    protocol=http
    ...    host=crud-test.example.com
    ...    port=${8080}
    ...    conditions=@{conditions}
    ...    responses=@{responses}
    ${create_resp}=    POST On Session    baafoo    /rules    json=${rule}    headers=${headers}
    Should Be Equal As Strings    ${create_resp.status_code}    200
    ${created}=    Set Variable    ${create_resp.json()}
    ${rule_id}=    Set Variable    ${created["id"]}
    Log    创建规则成功, ID: ${rule_id}

    # 2. 查询规则
    ${get_resp}=    GET On Session    baafoo    /rules/${rule_id}    headers=${headers}
    Should Be Equal As Strings    ${get_resp.status_code}    200
    ${fetched}=    Set Variable    ${get_resp.json()}
    Should Be Equal    ${fetched["name"]}    robot-crud-rule

    # 3. 更新规则
    &{updated_rule}=    Create Dictionary
    ...    name=robot-crud-rule-updated
    ...    protocol=http
    ...    host=crud-updated.example.com
    ...    conditions=@{conditions}
    ...    responses=@{responses}
    ${update_resp}=    PUT On Session    baafoo    /rules/${rule_id}    json=${updated_rule}    headers=${headers}
    Should Be Equal As Strings    ${update_resp.status_code}    200

    # 4. 验证更新
    ${verify_resp}=    GET On Session    baafoo    /rules/${rule_id}    headers=${headers}
    ${verified}=    Set Variable    ${verify_resp.json()}
    Should Be Equal    ${verified["name"]}    robot-crud-rule-updated

    # 5. 删除规则
    ${delete_resp}=    DELETE On Session    baafoo    /rules/${rule_id}    headers=${headers}
    Should Be Equal As Strings    ${delete_resp.status_code}    200

    # 6. 验证删除后查询返回失败
    ${after_del}=    GET On Session    baafoo    /rules/${rule_id}    headers=${headers}
    ${del_json}=    Set Variable    ${after_del.json()}
    Should Not Be True    ${del_json["success"]}

环境完整生命周期
    [Documentation]    环境 CRUD: 创建 → 查询 → 更新模式 → 删除
    [Tags]    crud    environments
    创建 API 会话
    ${headers}=    获取请求头

    # 1. 创建环境
    &{env}=    Create Dictionary
    ...    name=robot-crud-env
    ...    mode=stub
    ...    description=CRUD 测试环境
    ${create_resp}=    POST On Session    baafoo    /environments    json=${env}    headers=${headers}
    Should Be Equal As Strings    ${create_resp.status_code}    200
    ${created}=    Set Variable    ${create_resp.json()}
    ${env_id}=    Set Variable    ${created["id"]}

    # 2. 查询环境
    ${get_resp}=    GET On Session    baafoo    /environments/${env_id}    headers=${headers}
    Should Be Equal As Strings    ${get_resp.status_code}    200

    # 3. 更新模式为 passthrough
    &{updated_env}=    Create Dictionary
    ...    name=robot-crud-env
    ...    mode=passthrough
    ...    description=CRUD 测试环境-透传模式
    ${update_resp}=    PUT On Session    baafoo    /environments/${env_id}    json=${updated_env}    headers=${headers}
    Should Be Equal As Strings    ${update_resp.status_code}    200

    # 4. 删除环境
    ${delete_resp}=    DELETE On Session    baafoo    /environments/${env_id}    headers=${headers}
    Should Be Equal As Strings    ${delete_resp.status_code}    200

场景集完整生命周期
    [Documentation]    场景集 CRUD: 创建 → 查询 → 删除
    [Tags]    crud    scenes
    创建 API 会话
    ${headers}=    获取请求头

    # 1. 创建场景集
    &{scene}=    Create Dictionary
    ...    name=robot-crud-scene
    ...    description=CRUD 测试场景集
    ...    itemIds=@{EMPTY}
    ${create_resp}=    POST On Session    baafoo    /scenes    json=${scene}    headers=${headers}
    Should Be Equal As Strings    ${create_resp.status_code}    200
    ${created}=    Set Variable    ${create_resp.json()}
    ${scene_id}=    Set Variable    ${created["id"]}

    # 2. 查询场景集
    ${get_resp}=    GET On Session    baafoo    /scenes/${scene_id}    headers=${headers}
    Should Be Equal As Strings    ${get_resp.status_code}    200

    # 3. 删除场景集
    ${delete_resp}=    DELETE On Session    baafoo    /scenes/${scene_id}    headers=${headers}
    Should Be Equal As Strings    ${delete_resp.status_code}    200

规则撤销测试
    [Documentation]    创建规则后修改，再撤销回上一版本
    [Tags]    crud    rules    undo
    创建 API 会话
    ${headers}=    获取请求头

    # 1. 创建规则
    &{condition}=    Create Dictionary    type=method    operator=equals    value=POST
    &{resp}=    Create Dictionary    name=初始响应    statusCode=200    body={"v":1}
    @{conditions}=    Create List    ${condition}
    @{responses}=    Create List    ${resp}
    &{rule}=    Create Dictionary
    ...    name=robot-undo-rule
    ...    protocol=http
    ...    host=undo-test.example.com
    ...    conditions=@{conditions}
    ...    responses=@{responses}
    ${create_resp}=    POST On Session    baafoo    /rules    json=${rule}    headers=${headers}
    ${created}=    Set Variable    ${create_resp.json()}
    ${rule_id}=    Set Variable    ${created["id"]}

    # 2. 更新规则（制造一个版本）
    &{updated_rule}=    Create Dictionary
    ...    name=robot-undo-rule-v2
    ...    protocol=http
    ...    host=undo-v2.example.com
    ...    conditions=@{conditions}
    ...    responses=@{responses}
    PUT On Session    baafoo    /rules/${rule_id}    json=${updated_rule}    headers=${headers}

    # 3. 撤销
    ${undo_resp}=    POST On Session    baafoo    /rules/${rule_id}/undo    headers=${headers}
    Should Be Equal As Strings    ${undo_resp.status_code}    200

    # 4. 清理
    DELETE On Session    baafoo    /rules/${rule_id}    headers=${headers}
