<template>
  <div class="dashboard-page">
    <h2>{{ $t('dashboard.title') }}</h2>
    <el-row :gutter="20" style="margin-top: 20px">
      <el-col :span="6">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-value">{{ stats.rules }}</div>
          <div class="stat-label">{{ $t('dashboard.totalRules') }}</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-value">{{ stats.environments }}</div>
          <div class="stat-label">{{ $t('dashboard.totalEnvs') }}</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-value">{{ stats.agents }}</div>
          <div class="stat-label">{{ $t('dashboard.onlineAgents') }}</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-value">{{ stats.scenes }}</div>
          <div class="stat-label">{{ $t('dashboard.totalScenes') }}</div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="20" style="margin-top: 20px">
      <el-col :span="12">
        <el-card shadow="never">
          <template #header><span>{{ $t('dashboard.ruleOverview') }}</span></template>
          <div ref="rulesChart" style="height: 300px"></div>
        </el-card>
      </el-col>
      <el-col :span="12">
        <el-card shadow="never">
          <template #header><span>{{ $t('dashboard.requestTrend') }}</span></template>
          <div ref="trendChart" style="height: 300px; width: 100%"></div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="20" style="margin-top: 20px">
      <el-col :span="24">
        <el-card shadow="never">
          <template #header><span>{{ $t('dashboard.recentRecordings') }}</span></template>
          <el-table :data="recentRecordings" stripe size="small" max-height="300">
            <el-table-column prop="ruleName" :label="$t('dashboard.rule')" width="180" show-overflow-tooltip>
              <template #default="{ row }">{{ row.ruleName || (row.ruleId ? row.ruleId : $t('dashboard.unmatched')) }}</template>
            </el-table-column>
            <el-table-column prop="protocol" :label="$t('dashboard.protocol')" width="105">
              <template #default="{ row }"><el-tag size="small">{{ (row.protocol || '').toUpperCase() }}</el-tag></template>
            </el-table-column>
            <el-table-column prop="method" :label="$t('dashboard.method')" width="80">
              <template #default="{ row }">
                <template v-if="isMqProtocol(row.protocol)">
                  <el-tag v-if="row.direction" size="small" :type="directionType(row.direction)">{{ $t(directionLabel(row.direction)) }}</el-tag>
                  <el-tag v-else size="small" type="info">{{ row.method }}</el-tag>
                </template>
                <el-tag v-else size="small" type="info">{{ row.method }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="path" :label="$t('dashboard.path')" min-width="200" />
            <el-table-column prop="responseStatusCode" :label="$t('dashboard.statusCode')" width="80" />
            <el-table-column prop="recordedAt" :label="$t('dashboard.time')" width="180">
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
import { useI18n } from 'vue-i18n'
import api from '@/api'
import * as echarts from 'echarts'

export default {
  name: 'DashboardPage',
  setup() {
    const { t } = useI18n()
    const stats = reactive({ rules: 0, environments: 0, agents: 0, scenes: 0 })
    const recentRecordings = ref([])
    const rulesChart = ref(null)
    const trendChart = ref(null)

    const formatTime = (ts) => ts ? new Date(ts).toLocaleString() : '-'

    const isMqProtocol = (protocol) => {
      const mqProtocols = ['kafka', 'pulsar', 'jms', 'tcp', 'udp', 'grpc', 'dubbo']
      return mqProtocols.includes((protocol || '').toLowerCase())
    }

    const directionLabel = (d) => {
      if (d === 'produce' || d === 'request') return 'dashboard.send'
      if (d === 'consume' || d === 'response') return 'dashboard.receive'
      return d
    }
    const directionType = (d) => {
      if (d === 'produce' || d === 'request') return 'warning'
      if (d === 'consume' || d === 'response') return 'success'
      return 'info'
    }

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
        renderTrendChart(statusRes.success && statusRes.data.requestTrend ? statusRes.data.requestTrend : [])
      })
    })

    function renderRulesChart(rules) {
      if (!rulesChart.value) return
      const chart = echarts.init(rulesChart.value)
      const counts = { http: 0, tcp: 0, grpc: 0, kafka: 0, pulsar: 0, jms: 0 }
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

    function renderTrendChart(trendData) {
      if (!trendChart.value) return
      const chart = echarts.init(trendChart.value)

      // Process trend data - fill in missing days with 0
      // Use YYYY-MM-DD string comparison to avoid UTC vs local timezone mismatch
      const days = []
      const counts = []
      const now = new Date()
      for (let i = 6; i >= 0; i--) {
        const date = new Date(now)
        date.setDate(date.getDate() - i)
        date.setHours(0, 0, 0, 0)
        const ymd = formatDateYMD(date)
        days.push(date.toLocaleDateString('zh-CN', { weekday: 'short' }))

        // Find count for this day (compare by YYYY-MM-DD in local timezone)
        const dayData = trendData.find(d => {
          const dDate = new Date(d.day)
          return formatDateYMD(dDate) === ymd
        })
        counts.push(dayData ? dayData.count : 0)
      }

      chart.setOption({
        tooltip: { trigger: 'axis' },
        grid: { left: 40, right: 20, top: 20, bottom: 30 },
        xAxis: { type: 'category', data: days },
        yAxis: { type: 'value', minInterval: 1 },
        series: [{
          data: counts,
          type: 'line',
          smooth: true,
          areaStyle: { opacity: 0.1 },
          itemStyle: { color: 'var(--bf-accent)' }
        }]
      })
    }

    function formatDateYMD(d) {
      const y = d.getFullYear()
      const m = String(d.getMonth() + 1).padStart(2, '0')
      const day = String(d.getDate()).padStart(2, '0')
      return `${y}-${m}-${day}`
    }

    return { stats, recentRecordings, rulesChart, trendChart, formatTime, isMqProtocol, directionLabel, directionType }
  }
}
</script>

<style scoped>
.dashboard-page h2 { font-size: 22px; font-weight: 800; letter-spacing: -0.03em; color: var(--bf-text); }
.stat-card { cursor: pointer; }
.stat-card .stat-value { color: var(--bf-accent); }
.stat-card .stat-label { color: var(--bf-text-muted); }
</style>
