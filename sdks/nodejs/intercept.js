'use strict';

const http = require('http');
const https = require('https');
const { URL } = require('url');
const { EventEmitter } = require('events');

/**
 * Baafoo Full SDK - Node.js HTTP module interception
 *
 * Replaces http.request and https.request to intercept all outbound HTTP requests.
 * Supports stub, record, record-and-stub, and passthrough modes.
 */

let _originalHttpRequest = null;
let _originalHttpsRequest = null;
let _originalHttpGet = null;
let _originalHttpsGet = null;
let _patched = false;
let _sdkInstance = null;

/**
 * 启用 HTTP 拦截：monkey-patch http.request 和 https.request
 * @param {object} client - Baafoo Client 实例
 */
function patch(client) {
  if (_patched) return;
  _patched = true;
  _sdkInstance = client;

  _originalHttpRequest = http.request;
  _originalHttpsRequest = https.request;
  _originalHttpGet = http.get;
  _originalHttpsGet = https.get;

  http.request = _patchedRequest;
  https.request = _patchedRequest;
  // http.get 内部缓存了原始 request 引用，必须单独 patch
  http.get = function(...args) {
    const req = _patchedRequest(...args);
    req.end();
    return req;
  };
  https.get = function(...args) {
    const req = _patchedRequest(...args);
    req.end();
    return req;
  };
}

/**
 * 恢复原始的 http.request 和 https.request
 */
function unpatch() {
  if (!_patched) return;
  _patched = false;
  _sdkInstance = null;

  if (_originalHttpRequest) {
    http.request = _originalHttpRequest;
  }
  if (_originalHttpsRequest) {
    https.request = _originalHttpsRequest;
  }
  if (_originalHttpGet) {
    http.get = _originalHttpGet;
  }
  if (_originalHttpsGet) {
    https.get = _originalHttpsGet;
  }
  _originalHttpRequest = null;
  _originalHttpsRequest = null;
  _originalHttpGet = null;
  _originalHttpsGet = null;
}

/**
 * 拦截后的 request 实现
 */
function _patchedRequest(...args) {
  if (!_sdkInstance) {
    return _originalHttpRequest(...args);
  }

  // 解析参数
  let options, callback;
  if (typeof args[0] === 'string' || args[0] instanceof URL) {
    const parsed = new URL(args[0]);
    options = {
      method: 'GET',
      hostname: parsed.hostname,
      port: parsed.port || (parsed.protocol === 'https:' ? 443 : 80),
      path: parsed.pathname + parsed.search,
      headers: {},
    };
    if (args[1] && typeof args[1] === 'object') {
      Object.assign(options, args[1]);
    }
    callback = typeof args[args.length - 1] === 'function' ? args[args.length - 1] : null;
  } else if (typeof args[0] === 'object') {
    options = { ...args[0] };
    callback = typeof args[args.length - 1] === 'function' ? args[args.length - 1] : null;
  } else {
    return _originalHttpRequest(...args);
  }

  const method = (options.method || 'GET').toUpperCase();
  const path = options.path || '/';
  const host = options.hostname || options.host || 'localhost';
  let port = parseInt(options.port, 10) || 80;

  const mode = _sdkInstance.mode;
  const rules = _sdkInstance.rules;

  // 在 stub/record-and-stub 模式下尝试匹配规则
  let matchedRule = null;
  if (mode === 'stub' || mode === 'record-and-stub') {
    for (const rule of rules) {
      if (!rule.enabled) continue;
      if (_matchRule(rule, method, path)) {
        matchedRule = rule;
        break;
      }
    }
  }

  if (matchedRule) {
    // 返回 mock 响应
    return _buildMockResponse(matchedRule, options, callback, method, path, host, port);
  }

  // 未匹配规则：passthrough 或 record 模式
  const startTime = Date.now();

  // 包装 callback 以录制响应
  const wrappedCallback = (res) => {
    const durationMs = Date.now() - startTime;

    if (mode === 'record' || mode === 'record-and-stub') {
      _recordResponse(_sdkInstance, method, path, host, port, res, durationMs, options);
    }

    if (callback) {
      callback(res);
    }
  };

  // 使用原始的 request
  const newArgs = [...args];
  if (newArgs.length > 0 && typeof newArgs[newArgs.length - 1] === 'function') {
    newArgs[newArgs.length - 1] = wrappedCallback;
  } else {
    newArgs.push(wrappedCallback);
  }

  return _originalHttpRequest.apply(http, newArgs);
}

/**
 * 规则匹配
 */
function _matchRule(rule, method, path) {
  for (const cond of (rule.conditions || [])) {
    if (cond.field === 'method') {
      if (!_matchOperator(cond.operator, method, cond.value)) return false;
    } else if (cond.field === 'path') {
      if (!_matchOperator(cond.operator, path, cond.value)) return false;
    }
  }
  return true;
}

/**
 * 操作符匹配
 */
function _matchOperator(operator, actual, expected) {
  switch (operator) {
    case 'equals': case '=': return actual === expected;
    case 'contains': return actual.includes(expected);
    case 'prefix': return actual.startsWith(expected);
    case 'suffix': return actual.endsWith(expected);
    case 'exists': return actual !== '';
    default: return actual === expected;
  }
}

/**
 * 构建 mock 响应并返回一个模拟的 ClientRequest
 */
function _buildMockResponse(rule, options, callback, method, path, host, port) {
  const respConfig = (rule.responses && rule.responses[0]) || {};
  const statusCode = respConfig.statusCode || 200;
  const body = respConfig.body || '';
  const headers = respConfig.headers || {};
  const delayMs = respConfig.delayMs || 0;

  // 创建一个模拟的 IncomingMessage
  const res = new http.IncomingMessage(/* socket */ null);
  res.statusCode = statusCode;
  res.headers = {};
  for (const [k, v] of Object.entries(headers)) {
    res.headers[k] = v;
    res.headers[k.toLowerCase()] = v;
  }

  // 创建一个模拟的 ClientRequest（基于 EventEmitter，不发起真实连接）
  const req = new EventEmitter();
  req.finished = false;
  req.writableEnded = false;
  req.destroyed = false;
  req.write = function(_chunk, _enc, _cb) { return true; };
  req.end = function(_chunk, _enc, _cb) {
    this.writableEnded = true;
    this.finished = true;
    return this;
  };
  req.abort = function() { this.destroyed = true; this.emit('abort'); };
  req.destroy = function(_err) {
    this.destroyed = true;
    if (_err) this.emit('error', _err);
    return this;
  };
  req.setTimeout = function(_ms, _cb) { return this; };
  req.setNoDelay = function(_n) { return this; };
  req.setSocketKeepAlive = function(_e, _d) { return this; };
  req.flushHeaders = function() {};

  // 延迟发送响应
  const sendResponse = () => {
    // 如果是 record-and-stub 模式，录制请求
    if (_sdkInstance.mode === 'record-and-stub') {
      _sdkInstance.reportRecording({
        protocol: 'http',
        host: host,
        port: port,
        method: method,
        path: path,
        responseStatusCode: statusCode,
        responseHeaders: headers,
        responseBody: body,
        responseTimeMs: delayMs,
        direction: 'request',
      });
    }

    // 先发送响应头事件，再调用 callback（让调用方注册 res 的 data/end 监听器）
    req.emit('response', res);
    if (callback) {
      callback(res);
    }

    // 异步发送响应体（确保调用方已注册 data/end 监听器）
    const emitBody = () => {
      if (body) {
        const bodyBuf = Buffer.from(body);
        res.emit('data', bodyBuf);
      }
      res.emit('end');
    };
    // 使用 process.nextTick 确保监听器已注册
    process.nextTick(emitBody);
  };

  if (delayMs > 0) {
    setTimeout(sendResponse, delayMs);
  } else {
    process.nextTick(sendResponse);
  }

  return req;
}

/**
 * 录制响应
 */
function _recordResponse(sdk, method, path, host, port, res, durationMs, reqOptions) {
  const statusCode = res.statusCode || 200;
  const respHeaders = {};
  for (const [k, v] of Object.entries(res.headers || {})) {
    respHeaders[k] = v;
  }

  // 收集响应体
  const bodyChunks = [];
  res.on('data', (chunk) => {
    bodyChunks.push(chunk);
  });
  res.on('end', () => {
    const responseBody = Buffer.concat(bodyChunks).toString('utf-8');

    sdk.reportRecording({
      protocol: 'http',
      host: host,
      port: port,
      method: method,
      path: path,
      requestHeaders: reqOptions.headers || {},
      responseStatusCode: statusCode,
      responseHeaders: respHeaders,
      responseBody: responseBody,
      responseTimeMs: durationMs,
      direction: 'request',
    });
  });
}

module.exports = { patch, unpatch };
