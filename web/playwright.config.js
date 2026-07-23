const path = require('path')

module.exports = {
  testDir: path.join(__dirname, 'tests'),
  // 运行所有用例前自动登录 admin，生成 testing/7_Others/tmp/admin-storage.json。
  // 密码来源见 tests/global-setup.js 顶部注释。
  globalSetup: path.join(__dirname, 'tests/global-setup.js'),
  timeout: 60000,
  expect: {
    timeout: 10000,
  },
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  workers: 1,
  reporter: [
    ['list'],
    ['html', { outputFolder: path.join(__dirname, '../testing/7_Others/tmp/playwright-report') }],
  ],
  use: {
    baseURL: process.env.BAAFOO_BASE_URL || 'http://localhost:8084',
    browserName: 'chromium',
    channel: 'chrome',
    headless: true,
    storageState: path.join(__dirname, '../testing/7_Others/tmp/admin-storage.json'),
    screenshot: 'only-on-failure',
    trace: 'on-first-retry',
  },
}
