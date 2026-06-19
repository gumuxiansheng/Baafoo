const path = require('path')

module.exports = {
  testDir: path.join(__dirname, 'tests'),
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
    ['html', { outputFolder: path.join(__dirname, '../testing/tmp/playwright-report') }],
  ],
  use: {
    baseURL: 'http://localhost:8084',
    browserName: 'chromium',
    channel: 'chrome',
    headless: true,
    storageState: path.join(__dirname, '../testing/tmp/admin-storage.json'),
    screenshot: 'only-on-failure',
    trace: 'on-first-retry',
  },
}
