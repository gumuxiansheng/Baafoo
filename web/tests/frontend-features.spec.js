// @ts-check
const { test, expect } = require('@playwright/test')

const BASE_URL = 'http://localhost:8084'
const API_KEY = 'staging-admin-key'

/**
 * 确保 staging-a 处于 record_and_stub 模式，并通过 Agent 触发一条 HTTP 录制。
 */
test.beforeAll(async ({ request }) => {
  // 获取环境列表
  const envsRes = await request.get(`${BASE_URL}/__baafoo__/api/environments`, {
    headers: { 'X-Api-Key': API_KEY },
  })
  const envsBody = await envsRes.json()
  const env = envsBody.data?.find((e) => e.name === 'staging-a')
  if (env) {
    // 切换到 record_and_stub 模式
    await request.put(`${BASE_URL}/__baafoo__/api/environments/${env.id}`, {
      headers: { 'Content-Type': 'application/json', 'X-Api-Key': API_KEY },
      data: { name: 'staging-a', mode: 'record_and_stub' },
    })
  }

  // 触发一次被 Agent 拦截的 HTTP 请求，生成录制记录
  await request.get(`${BASE_URL.replace(':8084', ':9090')}/api/http/get?url=http://httpbin.org/get`)

  // 等待数据落库
  await new Promise((resolve) => setTimeout(resolve, 1500))
})

test.describe('Baafoo Web 控制台新功能测试', () => {
  test('仪表盘展示统计卡片与图表', async ({ page }) => {
    await page.goto('/#/dashboard')
    await page.waitForLoadState('networkidle')

    // 标题
    await expect(page.locator('h2:has-text("仪表盘")')).toBeVisible()

    // 四个统计卡片
    await expect(page.locator('.stat-card:has-text("规则总数")')).toBeVisible()
    await expect(page.locator('.stat-card:has-text("环境数")')).toBeVisible()
    await expect(page.locator('.stat-card:has-text("在线 Agents")')).toBeVisible()
    await expect(page.locator('.stat-card:has-text("场景集")')).toBeVisible()

    // 数字非空（允许 0）
    const ruleValue = await page.locator('.stat-card:has-text("规则总数") .stat-value').textContent()
    expect(ruleValue?.trim()).toMatch(/^\d+$/)

    // 规则概览饼图与请求趋势折线图
    await expect(page.locator('text=规则概览')).toBeVisible()
    await expect(page.locator('text=请求趋势')).toBeVisible()

    // 最近录制的响应表格
    await expect(page.locator('text=最近录制的响应')).toBeVisible()
  })

  test('场景集 - 新建、列表展示与删除', async ({ page }) => {
    await page.goto('/#/scenes')
    await page.waitForLoadState('networkidle')

    await expect(page.locator('h2:has-text("场景集管理")')).toBeVisible()

    // 打开新建对话框
    await page.locator('button:has-text("新建场景集")').click()
    await expect(page.locator('.el-dialog__title:has-text("新建场景集")')).toBeVisible()

    // 填写表单
    const sceneName = `ui-test-scene-${Date.now()}`
    await page.locator('.el-dialog input[placeholder*="测试场景"]').fill(sceneName)
    await page.locator('.el-dialog textarea').first().fill('由 Playwright 前端测试创建')

    // 关联第一条规则
    await page.locator('.el-dialog .el-select').first().click()
    await page.locator('.el-select-dropdown__item').first().click()
    await page.keyboard.press('Escape')

    // 选择生效环境
    await page.locator('.el-dialog .el-select').nth(1).click()
    await page.getByRole('option', { name: 'staging-a', exact: true }).click()
    await page.keyboard.press('Escape')

    // 提交
    await page.locator('.el-dialog footer button:has-text("创建")').click()

    // 列表中出现
    await expect(page.locator(`text=${sceneName}`)).toBeVisible()

    // 删除
    const row = page.locator('.el-table__row:has-text(' + JSON.stringify(sceneName) + ')')
    await row.locator('button:has-text("删除")').click()
    await page.locator('.el-popconfirm__action button:has-text("确定")').click()
    await expect(page.locator(`text=${sceneName}`)).toHaveCount(0)
  })

  test('录制管理 - 列表、搜索与详情', async ({ page }) => {
    await page.goto('/#/recordings')
    await page.waitForLoadState('networkidle')

    await expect(page.locator('h2:has-text("录制管理")')).toBeVisible()

    // 等待表格至少有一行数据（由 beforeAll 生成）
    await expect(page.locator('.el-table__row').first()).toBeVisible({ timeout: 10000 })

    // 搜索规则 ID
    await page.locator('input[placeholder="规则ID"]').fill('staging-a-http-get')
    await page.locator('button:has-text("搜索")').click()
    const firstRow = page.locator('.el-table__row').first()
    await expect(firstRow).toBeVisible()

    // 详情弹窗
    await firstRow.locator('button:has-text("详情")').click()
    await expect(page.locator('.el-dialog__title:has-text("录制详情")')).toBeVisible()
    const detailDialog = page.locator('.el-dialog:has(.el-dialog__title:has-text("录制详情"))')
    // 请求/响应区段标题渲染为 <span class="section-title">（RecordingsPage.vue）
    await expect(detailDialog.locator('.section-title:has-text("请求")')).toBeVisible()
    await expect(detailDialog.locator('.section-title:has-text("响应")')).toBeVisible()
    await page.locator('.el-dialog__headerbtn').click()

    // 重置搜索
    await page.locator('button:has-text("重置")').click()
    await expect(page.locator('.el-table__row').first()).toBeVisible()
  })

  test('请求日志 - 展示录制记录', async ({ page }) => {
    await page.goto('/#/logs')
    await page.waitForLoadState('networkidle')

    await expect(page.locator('h2:has-text("请求日志")')).toBeVisible()

    // 表格应至少有一行（与录制同数据源）
    await expect(page.locator('.el-table__row').first()).toBeVisible({ timeout: 10000 })

    // 存在协议、方法、路径、状态码列
    await expect(page.locator('th:has-text("协议")')).toBeVisible()
    await expect(page.locator('th:has-text("方法")')).toBeVisible()
    await expect(page.locator('th:has-text("路径")')).toBeVisible()
    await expect(page.locator('th:has-text("状态码")')).toBeVisible()
  })

  test('系统状态 - 展示 Agent 列表与服务信息', async ({ page }) => {
    await page.goto('/#/status')
    await page.waitForLoadState('networkidle')

    await expect(page.locator('h2:has-text("系统状态")')).toBeVisible()

    // 统计卡片
    await expect(page.locator('.stat-card:has-text("规则总数")')).toBeVisible()
    await expect(page.locator('.stat-card:has-text("环境数")')).toBeVisible()
    await expect(page.locator('.stat-card:has-text("在线 Agents")')).toBeVisible()
    await expect(page.locator('.stat-card:has-text("场景集")')).toBeVisible()

    // Agent 列表至少 2 行
    await expect(page.locator('text=Agent 列表')).toBeVisible()
    await expect(page.locator('.el-table__row')).toHaveCount(2)

    // 服务信息
    await expect(page.locator('text=服务信息')).toBeVisible()
    await expect(page.locator('text=HTTP Stub')).toBeVisible()
  })
})
