<template>
  <div class="logs-page">
    <h2>{{ $t('logs.title') }}</h2>
    <el-card shadow="never" style="margin-top: 16px" v-loading="loading">
      <el-table :data="tableRows" row-key="id" :row-class-name="rowClassName" stripe size="small" max-height="500" :empty-text="$t('logs.noData')">
        <el-table-column type="expand" width="40">
          <template #default="{ row }">
            <template v-if="row.__isGroup">
              <div class="pair-expanded">
                <el-table :data="row.children" size="small" :show-header="false" border>
                  <el-table-column width="80">
                    <template #default="{ row: child }">
                      <el-tag size="small" :type="directionType(child.direction)">{{ $t(directionLabel(child.direction)) }}</el-tag>
                    </template>
                  </el-table-column>
                  <el-table-column>
                    <template #default="{ row: child }">
                      <span class="pair-data-preview">{{ getDataPreview(child) }}</span>
                    </template>
                  </el-table-column>
                  <el-table-column width="180">
                    <template #default="{ row: child }">{{ formatTime(child.recordedAt) }}</template>
                  </el-table-column>
                </el-table>
              </div>
            </template>
          </template>
        </el-table-column>
        <el-table-column prop="ruleName" :label="$t('logs.rule')" width="140" show-overflow-tooltip>
          <template #default="{ row }">{{ row.ruleName || (row.ruleId ? row.ruleId : $t('logs.unmatched')) }}</template>
        </el-table-column>
        <el-table-column prop="agentId" label="Agent ID" width="140" show-overflow-tooltip />
        <el-table-column prop="agentIp" label="Agent IP" width="140" show-overflow-tooltip />
        <el-table-column prop="protocol" :label="$t('logs.protocol')" width="105">
          <template #default="{ row }"><el-tag size="small">{{ (row.protocol || '').toUpperCase() }}</el-tag></template>
        </el-table-column>
        <el-table-column prop="method" :label="$t('logs.method')" width="100">
          <template #default="{ row }">
            <template v-if="row.__isGroup">
              <el-tag v-for="d in uniqueDirections(row.children)" :key="d" size="small" :type="directionType(d)" class="dir-tag">{{ $t(directionLabel(d)) }}</el-tag>
            </template>
            <template v-else-if="isNonHttpProtocol(row.protocol)">
              <el-tag v-if="row.direction" size="small" :type="directionType(row.direction)">{{ $t(directionLabel(row.direction)) }}</el-tag>
              <el-tag v-else size="small" type="info">{{ row.method }}</el-tag>
            </template>
            <el-tag v-else size="small" type="info">{{ row.method }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="path" :label="$t('logs.path')" min-width="200" show-overflow-tooltip />
        <el-table-column prop="responseStatusCode" :label="$t('logs.statusCode')" width="80" align="center" />
        <el-table-column prop="responseTimeMs" :label="$t('logs.latencyMs')" width="90" align="center" />
        <el-table-column prop="recordedAt" :label="$t('logs.time')" width="200">
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
import { ref, computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import api from '@/api'

export default {
  name: 'LogsPage',
  setup() {
    const { t } = useI18n()
    const logs = ref([])
    const loading = ref(false)
    const currentPage = ref(1)
    const pageSize = ref(20)
    const total = ref(0)

    async function loadLogs() {
      loading.value = true
      // L-9: First arg is the empty ruleId filter — LogsPage intentionally shows recordings
      // across all rules (and unmatched), so no rule filter is applied.
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

    const isNonHttpProtocol = (protocol) => {
      const mqProtocols = ['kafka', 'pulsar', 'jms', 'tcp', 'udp', 'grpc', 'dubbo']
      return mqProtocols.includes((protocol || '').toLowerCase())
    }

    const directionLabel = (d) => {
      if (d === 'produce' || d === 'request') return 'logs.send'
      if (d === 'consume' || d === 'response') return 'logs.receive'
      return d
    }
    const directionType = (d) => {
      if (d === 'produce' || d === 'request') return 'warning'
      if (d === 'consume' || d === 'response') return 'success'
      return 'info'
    }

    // Group TCP/UDP passthrough recordings by sessionId for display.
    // Only sessionIds with 2+ records on the current page form a group;
    // single records (HTTP, server-side TCP stub, orphan TCP entries) stay as-is.
    const tableRows = computed(() => {
      const rows = logs.value
      const sessionCounts = new Map()
      for (const row of rows) {
        const sid = row.sessionId
        if (sid && sid.trim()) {
          sessionCounts.set(sid, (sessionCounts.get(sid) || 0) + 1)
        }
      }
      const groups = new Map()
      const result = []
      for (const row of rows) {
        const sid = row.sessionId && row.sessionId.trim() ? row.sessionId : null
        if (sid && sessionCounts.get(sid) >= 2) {
          let group = groups.get(sid)
          if (!group) {
            group = {
              id: 'group-' + sid,
              sessionId: sid,
              children: [],
              __isGroup: true
            }
            groups.set(sid, group)
            result.push(group)
          }
          group.children.push(row)
          if (!group.recordedAt || row.recordedAt < group.recordedAt) {
            group.protocol = row.protocol
            group.agentId = row.agentId
            group.agentIp = row.agentIp
            group.host = row.host
            group.port = row.port
            group.path = row.path
            group.ruleName = row.ruleName
            group.ruleId = row.ruleId
            group.recordedAt = row.recordedAt
          }
        } else {
          result.push(row)
        }
      }
      return result
    })

    const rowClassName = ({ row }) => row.__isGroup ? 'group-row' : 'single-row'

    const uniqueDirections = (children) => {
      const seen = new Set()
      const result = []
      for (const c of children || []) {
        if (c.direction && !seen.has(c.direction)) {
          seen.add(c.direction)
          result.push(c.direction)
        }
      }
      return result
    }

    const getDataPreview = (rec) => {
      const text = rec.dataHex || rec.requestBody || ''
      const max = 80
      return text.length > max ? text.substring(0, max) + '…' : (text || '-')
    }

    onMounted(loadLogs)
    return {
      logs, loading, currentPage, pageSize, total,
      tableRows, rowClassName, uniqueDirections, getDataPreview,
      onPageChange, onSizeChange, formatTime,
      isNonHttpProtocol, directionLabel, directionType
    }
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
:deep(.el-table__row.single-row .el-table__expand-icon) { visibility: hidden; pointer-events: none; }
:deep(.el-table__row.group-row > td) { background-color: var(--bf-bg-secondary); }
.pair-expanded { padding: 4px 12px 4px 48px; }
.pair-expanded :deep(.el-table) { border-radius: var(--bf-radius); }
.pair-expanded :deep(.el-table td) { padding: 6px 8px; }
.pair-data-preview { font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; font-size: 12px; color: var(--bf-text-secondary); word-break: break-all; }
.dir-tag { margin-right: 4px; }
.dir-tag:last-child { margin-right: 0; }
</style>
