'use strict';

const http = require('http');
const assert = require('assert');
const { Client, Options } = require('../index');

// --- Mock Server ---

let registerCount = 0;
let heartbeatCount = 0;
let pollCount = 0;
let recordingCount = 0;
let lastRecordings = [];

function resetCounts() {
  registerCount = 0;
  heartbeatCount = 0;
  pollCount = 0;
  recordingCount = 0;
  lastRecordings = [];
}

const server = http.createServer((req, res) => {
  const url = new URL(req.url, `http://localhost`);
  const path = url.pathname;

  if (req.method === 'POST' && path === '/__baafoo__/api/agent/register') {
    registerCount++;
    sendJson(res, {
      success: true,
      code: 200,
      message: 'OK',
      data: {
        agentId: 'test-agent-001',
        mode: 'record-and-stub',
        pollIntervalSec: 5,
      },
      timestamp: Date.now(),
    });
  } else if (req.method === 'POST' && path === '/__baafoo__/api/agent/heartbeat') {
    heartbeatCount++;
    sendJson(res, {
      success: true,
      code: 200,
      message: 'OK',
      data: null,
      timestamp: Date.now(),
    });
  } else if (req.method === 'GET' && path === '/__baafoo__/api/agent/poll') {
    pollCount++;
    sendJson(res, {
      success: true,
      code: 200,
      message: 'OK',
      data: {
        rules: [
          {
            id: 'rule-001',
            name: 'test-rule',
            protocol: 'http',
            enabled: true,
            priority: 100,
            conditions: [
              { field: 'method', operator: 'equals', value: 'GET' },
              { field: 'path', operator: 'prefix', value: '/api/' },
            ],
            responses: [
              { statusCode: 200, body: '{"ok":true}' },
            ],
          },
        ],
        mode: 'stub',
        version: Date.now(),
      },
      timestamp: Date.now(),
    });
  } else if (req.method === 'POST' && path === '/__baafoo__/api/agent/recordings') {
    let body = '';
    req.on('data', (chunk) => { body += chunk; });
    req.on('end', () => {
      const entries = body ? JSON.parse(body) : [];
      recordingCount += entries.length;
      lastRecordings = entries;
      sendJson(res, {
        success: true,
        code: 200,
        message: `Recorded ${entries.length}`,
        data: null,
        timestamp: Date.now(),
      });
    });
  } else {
    sendJson(res, { success: false, code: 404, message: 'Not Found' }, 404);
  }
});

function sendJson(res, data, status = 200) {
  const body = JSON.stringify(data);
  res.writeHead(status, {
    'Content-Type': 'application/json',
    'Content-Length': Buffer.byteLength(body),
  });
  res.end(body);
}

// --- Tests ---

async function runTests() {
  // 启动 mock server
  await new Promise((resolve) => server.listen(0, resolve));
  const port = server.address().port;
  const baseUrl = `http://127.0.0.1:${port}`;

  function newClient(opts = {}) {
    return new Client(new Options({
      serverUrl: baseUrl,
      heartbeatIntervalSec: 100,
      pollIntervalSec: 100,
      ...opts,
    }));
  }

  let passed = 0;
  let failed = 0;

  async function test(name, fn) {
    resetCounts();
    try {
      await fn();
      console.log(`  PASS: ${name}`);
      passed++;
    } catch (e) {
      console.error(`  FAIL: ${name}`);
      console.error(`    ${e.message}`);
      failed++;
    }
  }

  console.log('Running Node.js SDK tests...\n');

  // Test 1: new client
  await test('new client has defaults', async () => {
    const c = newClient();
    assert.strictEqual(c.mode, 'record-and-stub');
    assert.strictEqual(c.rules.length, 0);
  });

  // Test 2: register
  await test('register gets agentId and mode', async () => {
    const c = newClient();
    await c.start();
    try {
      assert.strictEqual(c.agentId, 'test-agent-001');
      assert.ok(['record-and-stub', 'stub'].includes(c.mode), `unexpected mode: ${c.mode}`);
      assert.ok(registerCount >= 1, `expected registerCount >= 1, got ${registerCount}`);
    } finally {
      await c.close();
    }
  });

  // Test 3: poll rules
  await test('poll rules fetches and stores rules', async () => {
    const c = newClient({ pollIntervalSec: 1 });
    await c.start();
    try {
      await new Promise((resolve) => setTimeout(resolve, 2000));
      assert.strictEqual(c.rules.length, 1);
      assert.strictEqual(c.rules[0].id, 'rule-001');
      assert.strictEqual(c.mode, 'stub');
    } finally {
      await c.close();
    }
  });

  // Test 4: report recording
  await test('report recording uploads to server', async () => {
    const c = newClient();
    await c.start();
    try {
      c.reportRecording({
        protocol: 'http',
        host: 'example.com',
        port: 8080,
        method: 'GET',
        path: '/api/test',
        responseStatusCode: 200,
        responseBody: '{"ok":true}',
      });
    } finally {
      await c.close();
    }
    assert.strictEqual(recordingCount, 1, `expected 1 recording, got ${recordingCount}`);
    assert.strictEqual(lastRecordings.length, 1);
    assert.strictEqual(lastRecordings[0].method, 'GET');
  });

  // Test 5: match request
  await test('match request finds matching rule', async () => {
    const c = newClient();
    c._rules = [
      {
        id: 'rule-001',
        enabled: true,
        conditions: [
          { field: 'method', operator: 'equals', value: 'GET' },
          { field: 'path', operator: 'prefix', value: '/api/' },
        ],
      },
      {
        id: 'rule-002',
        enabled: true,
        conditions: [
          { field: 'method', operator: 'equals', value: 'POST' },
          { field: 'path', operator: 'prefix', value: '/api/' },
        ],
      },
    ];

    let rule = c.matchRequest('GET', '/api/orders');
    assert.ok(rule, 'expected match for GET /api/orders');
    assert.strictEqual(rule.id, 'rule-001');

    rule = c.matchRequest('POST', '/api/orders');
    assert.ok(rule, 'expected match for POST /api/orders');
    assert.strictEqual(rule.id, 'rule-002');

    rule = c.matchRequest('DELETE', '/api/orders');
    assert.strictEqual(rule, null, 'expected no match for DELETE /api/orders');
  });

  // Test 6: auth headers
  await test('auth headers do not break start', async () => {
    const c = newClient({ apiKey: 'test-api-key' });
    await c.start();
    try {
      assert.strictEqual(c.agentId, 'test-agent-001');
    } finally {
      await c.close();
    }
  });

  // 关闭 server
  server.close();

  console.log(`\n${passed} passed, ${failed} failed`);
  if (failed > 0) {
    process.exit(1);
  }
}

runTests().catch((e) => {
  console.error(e);
  process.exit(1);
});
