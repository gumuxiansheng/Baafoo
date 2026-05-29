<template>
  <div class="logs-page">
    <h2>请求日志</h2>
    <el-card shadow="never" style="margin-top: 16px">
      <el-table :data="logs" stripe size="small" max-height="500" empty-text="暂无日志记录">
        <el-table-column prop="ruleId" label="规则ID" width="160" show-overflow-tooltip />
        <el-table-column prop="protocol" label="协议" width="80">
          <template #default="{ row }"><el-tag size="small">{{ (row.protocol || '').toUpperCase() }}</el-tag></template>
        </el-table-column>
        <el-table-column prop="method" label="方法" width="80" />
        <el-table-column prop="path" label="路径" min-width="200" show-overflow-tooltip />
        <el-table-column prop="responseStatusCode" label="状态码" width="80" align="center" />
        <el-table-column prop="responseTimeMs" label="耗时(ms)" width="80" align="center" />
        <el-table-column prop="recordedAt" label="时间" width="180">
          <template #default="{ row }">{{ formatTime(row.recordedAt) }}</template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script>
import { ref, onMounted } from 'vue'
import api from '@/api'

export default {
  name: 'LogsPage',
  setup() {
    const logs = ref([])

    onMounted(async () => {
      const res = await api.getRecordings('', 200)
      if (res.success) logs.value = res.data
    })

    const formatTime = (ts) => ts ? new Date(ts).toLocaleString() : '-'
    return { logs, formatTime }
  }
}
</script>

<style scoped>
h2 { font-size: 20px; font-weight: 600; color: #303133; margin-bottom: 0; }
</style>
