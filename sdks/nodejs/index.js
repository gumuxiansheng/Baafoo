'use strict';

const http = require('http');
const https = require('https');
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');
const os = require('os');
const { URL } = require('url');

/**
 * Baafoo Thin SDK - Node.js implementation
 */

class Options {
  constructor(opts = {}) {
    this.serverUrl = (opts.serverUrl || 'http://127.0.0.1:8084').replace(/\/+$/, '');
    this.environment = opts.environment || 'default';
    this.appName = opts.appName || '';
    this.apiKey = opts.apiKey || '';
    this.token = opts.token || '';
    this.heartbeatIntervalSec = opts.heartbeatIntervalSec || 15;
    this.pollIntervalSec = opts.pollIntervalSec || 10;
    this.httpTimeout = opts.httpTimeout || 30000;
  }
}

class Client {
  constructor(options) {
    this.opts = options instanceof Options ? options : new Options(options);
    this._agentId = '';
    this._rules = [];
    this._mode = 'record-and-stub';
    this._recordingBuffer = [];
    this._timers = [];
    this._closed = false;
  }

  // --- 生命周期 ---

  async start() {
    this._agentId = this._loadOrGenerateAgentId();
    await this._register();
    await this._pollRules();

    // 启动定时任务
    this._timers.push(setInterval(() => this._heartbeat().catch(() => {}), this.opts.heartbeatIntervalSec * 1000));
    this._timers.push(setInterval(() => this._pollRules().catch(() => {}), this.opts.pollIntervalSec * 1000));
    this._timers.push(setInterval(() => this._flushRecordings().catch(() => {}), 5000));
  }

  async close() {
    this._closed = true;
    for (const timer of this._timers) {
      clearInterval(timer);
    }
    this._timers = [];
    await this._flushRecordings();
  }

  // --- 公共 API ---

  get agentId() {
    return this._agentId;
  }

  get rules() {
    return [...this._rules];
  }

  get mode() {
    return this._mode;
  }

  reportRecording(entry) {
    if (!entry.id) {
      entry.id = crypto.randomUUID();
    }
    if (!entry.recordedAt) {
      entry.recordedAt = Date.now();
    }
    if (!entry.agentId) {
      entry.agentId = this._agentId;
    }

    this._recordingBuffer.push(entry);
    if (this._recordingBuffer.length >= 50) {
      this._flushRecordings().catch(() => {});
    }
  }

  matchRequest(method, reqPath) {
    for (const rule of this._rules) {
      if (!rule.enabled) continue;
      if (this._matchRule(rule, method, reqPath)) {
        return rule;
      }
    }
    return null;
  }

  // --- 内部方法 ---

  _matchRule(rule, method, reqPath) {
    for (const cond of (rule.conditions || [])) {
      if (cond.field === 'method') {
        if (!this._matchOperator(cond.operator, method, cond.value)) return false;
      } else if (cond.field === 'path') {
        if (!this._matchOperator(cond.operator, reqPath, cond.value)) return false;
      }
    }
    return true;
  }

  _matchOperator(operator, actual, expected) {
    switch (operator) {
      case 'equals': case '=': return actual === expected;
      case 'contains': return actual.includes(expected);
      case 'prefix': return actual.startsWith(expected);
      case 'suffix': return actual.endsWith(expected);
      case 'exists': return actual !== '';
      default: return actual === expected;
    }
  }

  async _register() {
    const hostname = os.hostname() || 'unknown';
    const body = {
      agentId: this._agentId,
      environment: this.opts.environment,
      hostname: hostname,
      version: '1.0.0',
      protocols: ['http'],
    };
    const data = await this._doPost('/agent/register', body);
    if (data) {
      this._agentId = data.agentId || this._agentId;
      if (data.mode) this._mode = data.mode;
      if (data.pollIntervalSec > 0) this.opts.pollIntervalSec = data.pollIntervalSec;
    }
  }

  async _heartbeat() {
    const body = {
      agentId: this._agentId,
      timestamp: Date.now(),
      pluginStatuses: {},
    };
    try {
      await this._doPost('/agent/heartbeat', body);
    } catch (e) {
      // 心跳失败静默
    }
  }

  async _pollRules() {
    const params = new URLSearchParams({
      agentId: this._agentId,
      environment: this.opts.environment,
    });
    const data = await this._doGet(`/agent/poll?${params.toString()}`);
    if (data) {
      this._rules = data.rules || [];
      if (data.mode) this._mode = data.mode;
    }
  }

  async _flushRecordings() {
    if (this._recordingBuffer.length === 0) return;

    const batch = this._recordingBuffer;
    this._recordingBuffer = [];

    const params = new URLSearchParams({
      agentId: this._agentId,
      environment: this.opts.environment,
    });
    try {
      await this._doPost(`/agent/recordings?${params.toString()}`, batch);
    } catch (e) {
      // 上传失败，放回缓冲区
      this._recordingBuffer = [...batch, ...this._recordingBuffer];
    }
  }

  // --- HTTP 工具方法 ---

  _doPost(path, body) {
    return this._doRequest('POST', path, body);
  }

  _doGet(path) {
    return this._doRequest('GET', path, null);
  }

  _doRequest(method, apiPath, body) {
    return new Promise((resolve, reject) => {
      const fullUrl = new URL(this.opts.serverUrl + '/__baafoo__/api' + apiPath);
      const isHttps = fullUrl.protocol === 'https:';
      const lib = isHttps ? https : http;

      const options = {
        method: method,
        hostname: fullUrl.hostname,
        port: fullUrl.port || (isHttps ? 443 : 80),
        path: fullUrl.pathname + fullUrl.search,
        headers: {},
        timeout: this.opts.httpTimeout,
      };

      if (this.opts.token) {
        options.headers['Authorization'] = 'Bearer ' + this.opts.token;
      } else if (this.opts.apiKey) {
        options.headers['X-Api-Key'] = this.opts.apiKey;
      }

      const bodyStr = body ? JSON.stringify(body) : null;
      if (bodyStr) {
        options.headers['Content-Type'] = 'application/json';
        options.headers['Content-Length'] = Buffer.byteLength(bodyStr);
      }

      const req = lib.request(options, (res) => {
        let data = '';
        res.on('data', (chunk) => { data += chunk; });
        res.on('end', () => {
          if (res.statusCode === 204) {
            resolve(null);
            return;
          }
          try {
            const apiResp = JSON.parse(data);
            if (!apiResp.success) {
              reject(new Error(`api error: ${apiResp.message} (code ${apiResp.code})`));
              return;
            }
            resolve(apiResp.data);
          } catch (e) {
            reject(new Error(`unmarshal response: ${e.message}, body: ${data}`));
          }
        });
      });

      req.on('error', reject);
      req.on('timeout', () => {
        req.destroy(new Error('request timeout'));
      });

      if (bodyStr) {
        req.write(bodyStr);
      }
      req.end();
    });
  }

  // --- AgentId 持久化 ---

  _loadOrGenerateAgentId() {
    const home = os.homedir();
    const dir = path.join(home, '.baafoo');
    const namePart = this.opts.appName || 'agent';
    const idFile = path.join(dir, `baafoo-${namePart}.id`);

    try {
      const id = fs.readFileSync(idFile, 'utf-8').trim();
      if (id) return id;
    } catch (e) {
      // 文件不存在
    }

    const agentId = crypto.randomUUID();
    try {
      fs.mkdirSync(dir, { recursive: true });
      fs.writeFileSync(idFile, agentId, 'utf-8');
    } catch (e) {
      // 写入失败忽略
    }

    return agentId;
  }
}

module.exports = { Client, Options };
