// @ts-check
/**
 * Playwright globalSetup — 在所有用例运行前自动登录 admin 账号，把 JWT token
 * 写入 localStorage 并保存为 storageState（admin-storage.json），供每个测试用例复用。
 *
 * 这样开发者无需手工准备 admin-storage.json，运行脚本只需提供 admin 密码即可。
 *
 * 密码来源（按优先级）：
 *   1. 环境变量 BAAFOO_ADMIN_PASSWORD
 *   2. 环境变量 BAAFOO_ADMIN_PASSWORD_FILE 指向的文件（默认 testing/7_Others/tmp/.admin-password）
 *
 * 运行脚本 run-ui-tests.ps1 / run-ui-tests.sh 会从容器内的 .admin-credentials
 * 抽取密码并写入上述文件 / 环境变量。
 */
const { request, chromium } = require('@playwright/test')
const path = require('path')
const fs = require('fs')

const BASE_URL = process.env.BAAFOO_BASE_URL || 'http://localhost:8084'
const ADMIN_USER = process.env.BAAFOO_ADMIN_USER || 'admin'

const STORAGE_STATE_PATH = path.join(
  __dirname,
  '../../testing/7_Others/tmp/admin-storage.json'
)

function readAdminPassword() {
  if (process.env.BAAFOO_ADMIN_PASSWORD) {
    return process.env.BAAFOO_ADMIN_PASSWORD
  }
  const passwordFile =
    process.env.BAAFOO_ADMIN_PASSWORD_FILE ||
    path.join(__dirname, '../../testing/7_Others/tmp/.admin-password')
  if (fs.existsSync(passwordFile)) {
    const raw = fs.readFileSync(passwordFile, 'utf8').trim()
    if (raw) return raw
  }
  throw new Error(
    '[globalSetup] 未找到 admin 密码。请通过环境变量 BAAFOO_ADMIN_PASSWORD 提供，' +
      '或运行 testing/6_UITest/run-ui-tests.ps1|.sh（会自动从容器抽取密码写入 ' +
      'testing/7_Others/tmp/.admin-password）。'
  )
}

module.exports = async () => {
  const adminPassword = readAdminPassword()

  // 确保 tmp 目录存在
  fs.mkdirSync(path.dirname(STORAGE_STATE_PATH), { recursive: true })

  // 1. 调用登录 API 拿 JWT token
  const ctx = await request.newContext({ baseURL: BASE_URL })
  const res = await ctx.post('/__baafoo__/api/auth/login', {
    headers: { 'Content-Type': 'application/json' },
    data: { username: ADMIN_USER, password: adminPassword },
  })
  const body = await res.json().catch(() => null)
  await ctx.dispose()

  if (!body || !body.success || !body.data || !body.data.token) {
    throw new Error(
      `[globalSetup] admin 登录失败：${body ? body.message || JSON.stringify(body) : `HTTP ${res.status()}`
      }`
    )
  }
  const token = body.data.token

  // 2. 用一个真实浏览器上下文把 token 写入 localStorage，再保存 storageState。
  //    localStorage 必须有同源页面才能写入，所以先 goto(BASE_URL) 再 evaluate。
  const browser = await chromium.launch({ headless: true, channel: 'chrome' })
  const context = await browser.newContext()
  const page = await context.newPage()
  await page.goto(BASE_URL, { waitUntil: 'domcontentloaded' })
  await page.evaluate(
    ({ token, username }) => {
      window.localStorage.setItem('baafoo_token', token)
      window.localStorage.setItem('baafoo_username', username)
    },
    { token, username: ADMIN_USER }
  )
  await context.storageState({ path: STORAGE_STATE_PATH })
  await browser.close()

  console.log(`[globalSetup] admin 登录成功，storageState 已写入 ${STORAGE_STATE_PATH}`)
}
