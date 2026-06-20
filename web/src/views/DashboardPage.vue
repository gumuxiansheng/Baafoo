<template>
  <div class="dashboard-page">
    <h2>仪表盘</h2>
    <el-row :gutter="20" style="margin-top: 20px">
      <el-col :span="6">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-value">{{ stats.rules }}</div>
          <div class="stat-label">规则总数</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-value">{{ stats.environments }}</div>
          <div class="stat-label">环境数</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-value">{{ stats.agents }}</div>
          <div class="stat-label">在线 Agents</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-value">{{ stats.scenes }}</div>
          <div class="stat-label">场景集</div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="20" style="margin-top: 20px">
      <el-col :span="12">
        <el-card shadow="never">
          <template #header><span>规则概览</span></template>
          <div ref="rulesChart" style="height: 300px"></div>
        </el-card>
      </el-col>
      <el-col :span="12">
        <el-card shadow="never">
          <template #header><span>请求趋势</span></template>
          <div ref="trendChart" style="height: 300px"></div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="20" style="margin-top: 20px">
      <el-col :span="24">
        <el-card shadow="never">
          <template #header><span>最近录制的响应</span></template>
          <el-table :data="recentRecordings" stripe size="small" max-height="300">
            <el-table-column prop="ruleName" label="规则" width="180" show-overflow-tooltip>
              <template #default="{ row }">{{ row.ruleName || (row.ruleId ? row.ruleId : '未匹配') }}</template>
            </el-table-column>
            <el-table-column prop="protocol" label="协议" width="80">
              <template #default="{ row }"><el-tag size="small">{{ (row.protocol || '').toUpperCase() }}</el-tag></template>
            </el-table-column>
            <el-table-column prop="method" label="方法" width="80">
              <template #default="{ row }">
                <el-tag v-if="row.direction" size="small" :type="row.direction === 'produce' || row.direction === 'request' ? 'warning' : 'success'">{{ row.direction === 'produce' || row.direction === 'request' ? '发送' : '接收' }}</el-tag>
                <span v-else>{{ row.method }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="path" label="路径" min-width="200" />
            <el-table-column prop="responseStatusCode" label="状态码" width="80" />
            <el-table-column prop="recordedAt" label="时间" width="180">
              <template #default="{ row }">
                {{ formatTime(row.recordedAt) }}
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script>
import { ref, reactive, onMounted, nextTick } from 'vue'
import api from '@/api'
import * as echarts from 'echarts'

export default {
  name: 'DashboardPage',
  setup() {
    const stats = reactive({ rules: 0, environments: 0, agents: 0, scenes: 0 })
    const recentRecordings = ref([])
    const rulesChart = ref(null)
    const trendChart = ref(null)

    const formatTime = (ts) => ts ? new Date(ts).toLocaleString() : '-'

    onMounted(async () => {
      const [statusRes, rulesRes, recordingsRes, scenesRes, envsRes] = await Promise.all([
        api.getStatus(),
        api.getRules(),
        api.getRecordings('', 10),
        api.getScenes(),
        api.getEnvironments()
      ])

      if (statusRes.success) {
        Object.assign(stats, statusRes.data)
        stats.agents = statusRes.data.onlineAgents ?? statusRes.data.agents ?? 0
        stats.scenes = scenesRes.success ? scenesRes.data.length : 0
      }

      if (recordingsRes.success) {
        recentRecordings.value = recordingsRes.data
      }

      nextTick(() => {
        renderRulesChart(rulesRes.success ? rulesRes.data : [])
        renderTrendChart()
      })
    })

    function renderRulesChart(rules) {
      if (!rulesChart.value) return
      const chart = echarts.init(rulesChart.value)
      const counts = { http: 0, tcp: 0, kafka: 0, pulsar: 0, jms: 0 }
      rules.forEach(r => { if (counts[r.protocol] !== undefined) counts[r.protocol]++ })
      chart.setOption({
        tooltip: { trigger: 'item' },
        legend: { bottom: 0 },
        series: [{
          type: 'pie',
          radius: ['40%', '70%'],
          data: Object.entries(counts).filter(([,v]) => v > 0).map(([k, v]) => ({ name: k.toUpperCase(), value: v }))
        }]
      })
    }

    function renderTrendChart() {
      if (!trendChart.value) return
      const chart = echarts.init(trendChart.value)
      chart.setOption({
        tooltip: { trigger: 'axis' },
        grid: { left: 40, right: 20, top: 20, bottom: 30 },
        xAxis: { type: 'category', data: ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'] },
        yAxis: { type: 'value' },
        series: [{
          data: [0, 0, 0, 0, 0, 0, 0],
          type: 'line',
          smooth: true,
          areaStyle: { opacity: 0.1 }
        }]
      })
    }

    return { stats, recentRecordings, rulesChart, trendChart, formatTime }
  }
}
</script>

<style scoped>
.dashboard-page h2 { font-size: 20px; font-weight: 600; color: #303133; }
.stat-card { text-align: center; cursor: pointer; }
.stat-value { font-size: 36px; font-weight: 700; color: #667eea; }
.stat-label { font-size: 14px; color: #909399; margin-top: 8px; }
</style>
