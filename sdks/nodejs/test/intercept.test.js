'use strict';

const http = require('http');
const assert = require('assert');
const { Client, Options } = require('../index');
const { patch, unpatch } = require('../intercept');

// --- Mock Backend Server ---

let backendPort;
const backend = http.createServer((req, res) => {
  if (req.method === 'GET') {
    res.writeHead(200, { 'Content-Type': 'text/plain' });
    res.end('real response from backend');
  } else if (req.method === 'POST') {
    let body = '';
    req.on('data', (chunk) => { body += chunk; });
    req.on('end', () => {
      res.writeHead(201, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ created: true, received: body }));
    });
  }
});

// --- Tests ---

async function runTests() {
  // 启动后端服务器
  await new Promise((resolve) => backend.listen(0, resolve));
  backendPort = backend.address().port;

  let passed = 0;
  let failed = 0;

  async function test(name, fn) {
    try {
      await fn();
      console.log(`  PASS: ${name}`);
      passed++;
    } catch (e) {
      console.error(`  FAIL: ${name}`);
      console.error(`    ${e.message}`);
      console.error(`    ${e.stack}`);
      failed++;
    } finally {
      unpatch();
    }
  }

  function newClient(mode = 'passthrough', rules = []) {
    const c = new Client(new Options({
      serverUrl: 'http://127.0.0.1:1', // 不会真正连接
      heartbeatIntervalSec: 999,
      pollIntervalSec: 999,
    }));
    c._mode = mode;
    c._rules = rules;
    return c;
  }

  function httpGet(url) {
    return new Promise((resolve, reject) => {
      http.get(url, (res) => {
        let body = '';
        res.on('data', (chunk) => { body += chunk; });
        res.on('end', () => resolve({ statusCode: res.statusCode, body, headers: res.headers }));
      }).on('error', reject);
    });
  }

  function httpPost(url, data) {
    return new Promise((resolve, reject) => {
      const req = http.request(url, { method: 'POST' }, (res) => {
        let body = '';
        res.on('data', (chunk) => { body += chunk; });
        res.on('end', () => resolve({ statusCode: res.statusCode, body, headers: res.headers }));
      });
      req.on('error', reject);
      req.write(data);
      req.end();
    });
  }

  console.log('Running Node.js Full SDK interception tests...\n');

  // Test 1: patch/unpatch
  await test('patch replaces http.request', async () => {
    const c = newClient('passthrough');
    const original = http.request;
    patch(c);
    assert.notStrictEqual(http.request, original, 'http.request should be replaced');
    unpatch();
    assert.strictEqual(http.request, original, 'http.request should be restored');
  });

  // Test 2: stub mode returns mock
  await test('stub mode returns mock response', async () => {
    const c = newClient('stub', [
      {
        id: 'rule-001',
        enabled: true,
        conditions: [
          { field: 'method', operator: 'equals', value: 'GET' },
          { field: 'path', operator: 'prefix', value: '/api/' },
        ],
        responses: [
          {
            statusCode: 200,
            headers: { 'Content-Type': 'application/json' },
            body: '{"mocked":true}',
          },
        ],
      },
    ]);
    patch(c);

    const resp = await httpGet(`http://127.0.0.1:${backendPort}/api/test`);
    assert.strictEqual(resp.statusCode, 200);
    assert.ok(resp.body.includes('"mocked":true'), `unexpected body: ${resp.body}`);
  });

  // Test 3: passthrough forwards real request
  await test('passthrough mode forwards real request', async () => {
    const c = newClient('passthrough', []);
    patch(c);

    const resp = await httpGet(`http://127.0.0.1:${backendPort}/api/test`);
    assert.strictEqual(resp.statusCode, 200);
    assert.strictEqual(resp.body, 'real response from backend');
  });

  // Test 4: record mode captures request
  await test('record mode records request', async () => {
    const c = newClient('record', []);
    patch(c);

    const resp = await httpGet(`http://127.0.0.1:${backendPort}/api/test`);
    assert.strictEqual(resp.body, 'real response from backend');

    // 等待录制（end 事件是异步的）
    await new Promise((resolve) => setTimeout(resolve, 100));

    assert.ok(c._recordingBuffer.length >= 1, 'expected at least 1 recording');
    const entry = c._recordingBuffer[0];
    assert.strictEqual(entry.method, 'GET');
    assert.strictEqual(entry.path, '/api/test');
    assert.strictEqual(entry.responseStatusCode, 200);
  });

  // Test 5: record-and-stub mode
  await test('record-and-stub mode returns mock and records', async () => {
    const c = newClient('record-and-stub', [
      {
        id: 'rule-001',
        enabled: true,
        conditions: [
          { field: 'method', operator: 'equals', value: 'GET' },
          { field: 'path', operator: 'prefix', value: '/api/' },
        ],
        responses: [
          { statusCode: 200, body: '{"stub":true}' },
        ],
      },
    ]);
    patch(c);

    // 匹配规则 — 返回 mock
    const resp = await httpGet(`http://127.0.0.1:${backendPort}/api/orders`);
    assert.ok(resp.body.includes('"stub":true'), `unexpected body: ${resp.body}`);

    // 等待录制
    await new Promise((resolve) => setTimeout(resolve, 100));

    // 应该有录制
    assert.ok(c._recordingBuffer.length >= 1, 'expected recordings in record-and-stub mode');
  });

  // Test 6: delay injection
  await test('delay injection works', async () => {
    const c = newClient('stub', [
      {
        id: 'rule-001',
        enabled: true,
        conditions: [
          { field: 'method', operator: 'equals', value: 'GET' },
        ],
        responses: [
          { statusCode: 200, body: 'delayed', delayMs: 50 },
        ],
      },
    ]);
    patch(c);

    const start = Date.now();
    const resp = await httpGet(`http://127.0.0.1:${backendPort}/test`);
    const elapsed = Date.now() - start;
    assert.ok(elapsed >= 40, `expected delay >= 50ms, got ${elapsed}ms`);
    assert.strictEqual(resp.body, 'delayed');
  });

  // Test 7: POST request interception
  await test('POST request interception', async () => {
    const c = newClient('stub', [
      {
        id: 'rule-001',
        enabled: true,
        conditions: [
          { field: 'method', operator: 'equals', value: 'POST' },
        ],
        responses: [
          { statusCode: 201, body: '{"created":false}' },
        ],
      },
    ]);
    patch(c);

    const resp = await httpPost(`http://127.0.0.1:${backendPort}/api/create`, '{"name":"test"}');
    assert.strictEqual(resp.statusCode, 201);
    assert.ok(resp.body.includes('"created":false'), `unexpected body: ${resp.body}`);
  });

  // 关闭服务器
  backend.close();

  console.log(`\n${passed} passed, ${failed} failed`);
  if (failed > 0) {
    process.exit(1);
  }
}

runTests().catch((e) => {
  console.error(e);
  process.exit(1);
});
