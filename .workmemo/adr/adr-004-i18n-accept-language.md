# ADR-004: i18n — Accept-Language 头驱动

- **状态**: Accepted
- **日期**: 2026-07-05
- **关联**: `web/src/locales/`、`baafoo-core/.../i18n/I18n.java`、`ManagementApiHandler.java`

## 背景

Baafoo 前端所有界面文本和后端部分 API 错误消息为硬编码中文。开源化后需要支持英文。

## 选项

| # | 方案 | 优点 | 缺点 |
|---|------|------|------|
| A | 前端 i18n + 后端 Accept-Language 头 | 标准 HTTP 机制、前后端独立 | 后端需解析请求头 |
| B | 仅前端 i18n，后端返回 errorCode 前端翻译 | 后端无改动 | 前端需维护 error code 映射表 |
| C | 全后端驱动：后端返回完整翻译文本 | 前端无 i18n 逻辑 | 灵活性差、耦合高 |

## 决策

选择 **方案 A**：前端使用 `vue-i18n@9` + `LocaleSwitcher` 组件 + `zh-CN.json`/`en.json` 翻译资源；后端 `ManagementApiHandler` 解析 `Accept-Language` 头，通过 `I18n` 工具类从 `messages.properties`/`messages_en.properties` 读取翻译。

## 理由

- HTTP `Accept-Language` 是国际化标准机制，无需发明自定义协议
- 前后端独立翻译资源，互不阻塞
- `vue-i18n` 是 Vue 3 生态最成熟的 i18n 方案
- 后端只需 `I18n.java` 工具类 + properties 文件，无重量级依赖

## 后果

- 所有新 API handler 需通过 `ApiContext.getI18n()` 获取翻译，不能硬编码消息
- 前端新增组件必须使用 `$t()` 而非硬编码文本
- `api/index.js` 拦截器自动注入 `Accept-Language` 头
- 已知约束：含 `{{...}}` 的翻译值需避免 vue-i18n 嵌套插值（使用 `v-html` + computed 替代）
