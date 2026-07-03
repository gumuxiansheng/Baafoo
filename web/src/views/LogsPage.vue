<template>
  <div class="logs-page">
    <h2>请求日志</h2>
    <el-card shadow="never" style="margin-top: 16px" v-loading="loading">
      <el-table :data="logs" stripe size="small" max-height="500" empty-text="暂无日志记录">
        <el-table-column prop="ruleName" label="规则" width="140" show-overflow-tooltip>
          <template #default="{ row }">{{ row.ruleName || (row.ruleId ? row.ruleId : '未匹配') }}</template>
        </el-table-column>
        <el-table-column prop="agentId" label="Agent ID" width="140" show-overflow-tooltip />
        <el-table-column prop="agentIp" label="Agent IP" width="140" show-overflow-tooltip />
        <el-table-column prop="protocol" label="协议" width="80">
          <template #default="{ row }"><el-tag size="small">{{ (row.protocol || '').toUpperCase() }}</el-tag></template>
        </el-table-column>
        <el-table-column prop="method" label="方法" width="80">
          <template #default="{ row }">
            <template v-if="isMqProtocol(row.protocol)">
              <el-tag v-if="row.direction" size="small" :type="directionType(row.direction)">{{ directionLabel(row.direction) }}</el-tag>
              <span v-else>{{ row.method }}</span>
            </template>
            <span v-else>{{ row.method }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="path" label="路径" min-width="200" show-overflow-tooltip />
        <el-table-column prop="responseStatusCode" label="状态码" width="80" align="center" />
        <el-table-column prop="responseTimeMs" label="耗时(ms)" width="90" align="center" />
        <el-table-column prop="recordedAt" label="时间" width="180">
          <template #default="{ row }">{{ formatTime(row.recordedAt) }}</template>
        </el-table-column>
      </el-table>
      <div class="pagination-wrap" v-if="total > 0">
        <el-pagination
          background
          layout="total, prev, pager, next, sizes"
          :total="total"
          :page-size="pageSize"
          :current-page="currentPage"
          :page-sizes="[10, 20, 50, 100]"
          @current-change="onPageChange"
          @size-change="onSizeChange"
        />
      </div>
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
    const loading = ref(false)
    const currentPage = ref(1)
    const pageSize = ref(20)
    const total = ref(0)

    async function loadLogs() {
      loading.value = true
      const res = await api.getRecordingsPaged('', currentPage.value, pageSize.value)
      if (res.success && res.data) {
        logs.value = res.data.items || []
        total.value = res.data.total || 0
      }
      loading.value = false
    }

    function onPageChange(page) {
      currentPage.value = page
      loadLogs()
    }

    function onSizeChange(size) {
      pageSize.value = size
      currentPage.value = 1
      loadLogs()
    }

    const formatTime = (ts) => ts ? new Date(ts).toLocaleString() : '-'

    const isMqProtocol = (protocol) => {
      const mqProtocols = ['kafka', 'pulsar', 'jms', 'tcp', 'udp', 'grpc', 'dubbo']
      return mqProtocols.includes((protocol || '').toLowerCase())
    }

    const directionLabel = (d) => {
      if (d === 'produce' || d === 'request') return '发送'
      if (d === 'consume' || d === 'response') return '接收'
      return d
    }
    const directionType = (d) => {
      if (d === 'produce' || d === 'request') return 'warning'
      if (d === 'consume' || d === 'response') return 'success'
      return 'info'
    }

    onMounted(loadLogs)
    return { logs, loading, currentPage, pageSize, total, onPageChange, onSizeChange, formatTime, isMqProtocol, directionLabel, directionType }
  }
}
</script>

<style scoped>
h2 { margin-bottom: 0; }
.pagination-wrap { margin-top: 16px; display: flex; justify-content: flex-end; }
:deep(.el-pagination) { font-size: 12px; }
:deep(.el-pagination .el-pagination__total) { font-size: 12px; }
:deep(.el-pagination .el-pagination__sizes) { font-size: 12px; }
:deep(.el-pagination .el-pagination__sizes .el-input__wrapper) { font-size: 12px; height: 24px; }
:deep(.el-pagination .el-pagination__sizes .el-input__inner) { font-size: 12px; height: 24px; }
:deep(.el-pagination .btn-prev) { font-size: 12px; min-width: 24px; height: 24px; line-height: 24px; }
:deep(.el-pagination .btn-next) { font-size: 12px; min-width: 24px; height: 24px; line-height: 24px; }
:deep(.el-pagination .el-pager li) { font-size: 12px; min-width: 24px; height: 24px; line-height: 24px; }
</style>
