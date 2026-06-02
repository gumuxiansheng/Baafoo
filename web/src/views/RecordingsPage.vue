<template>
  <div class="recordings-page">
    <div class="page-header">
      <h2>录制管理</h2>
      <el-button @click="loadRecordings">刷新</el-button>
    </div>

    <el-card shadow="never" style="margin-top: 16px" v-loading="loading">
      <el-table :data="recordings" stripe size="small" max-height="500" empty-text="暂无录制数据">
        <el-table-column prop="id" label="ID" width="120" show-overflow-tooltip />
        <el-table-column prop="ruleId" label="规则" width="120" show-overflow-tooltip />
        <el-table-column prop="agentId" label="Agent ID" width="120" show-overflow-tooltip />
        <el-table-column prop="host" label="Agent IP" width="130" show-overflow-tooltip />
        <el-table-column prop="protocol" label="协议" width="70">
          <template #default="{ row }"><el-tag size="small">{{ (row.protocol || '').toUpperCase() }}</el-tag></template>
        </el-table-column>
        <el-table-column prop="method" label="方法" width="70" />
        <el-table-column prop="path" label="路径" min-width="180" show-overflow-tooltip />
        <el-table-column prop="responseStatusCode" label="状态码" width="70" align="center" />
        <el-table-column prop="responseTimeMs" label="耗时(ms)" width="80" align="center" />
        <el-table-column prop="recordedAt" label="录制时间" width="170">
          <template #default="{ row }">{{ formatTime(row.recordedAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <el-button size="small" text @click="viewDetail(row)">详情</el-button>
            <el-popconfirm title="确定删除？" @confirm="deleteItem(row.id)" v-if="authStore.canWriteRecording">
              <template #reference>
                <el-button size="small" text type="danger">删除</el-button>
              </template>
            </el-popconfirm>
          </template>
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

    <!-- Detail Dialog -->
    <el-dialog v-model="detailVisible" title="录制详情" width="700px">
      <div v-if="currentRecording">
        <h4>请求</h4>
        <div class="detail-meta" v-if="currentRecording.agentId || currentRecording.host">
          <span v-if="currentRecording.agentId">Agent: {{ currentRecording.agentId }}</span>
          <span v-if="currentRecording.host">IP: {{ currentRecording.host }}<span v-if="currentRecording.port">:{{ currentRecording.port }}</span></span>
        </div>
        <el-input :model-value="formatHeaders(currentRecording.requestHeaders)" type="textarea" rows="3" readonly />
        <el-input :model-value="currentRecording.requestBody" type="textarea" rows="6" readonly style="margin-top: 8px" />
        <h4 style="margin-top: 16px">响应 ({{ currentRecording.responseStatusCode }})</h4>
        <el-input :model-value="formatHeaders(currentRecording.responseHeaders)" type="textarea" rows="3" readonly />
        <el-input :model-value="currentRecording.responseBody" type="textarea" rows="10" readonly style="margin-top: 8px" />
      </div>
    </el-dialog>
  </div>
</template>

<script>
import { ref, onMounted } from 'vue'
import { useAuthStore } from '@/store'
import api from '@/api'

export default {
  name: 'RecordingsPage',
  setup() {
    const authStore = useAuthStore()
    const recordings = ref([])
    const loading = ref(false)
    const detailVisible = ref(false)
    const currentRecording = ref(null)
    const currentPage = ref(1)
    const pageSize = ref(20)
    const total = ref(0)

    async function loadRecordings() {
      loading.value = true
      const res = await api.getRecordingsPaged('', currentPage.value, pageSize.value)
      if (res.success && res.data) {
        recordings.value = res.data.items || []
        total.value = res.data.total || 0
      }
      loading.value = false
    }

    function onPageChange(page) {
      currentPage.value = page
      loadRecordings()
    }

    function onSizeChange(size) {
      pageSize.value = size
      currentPage.value = 1
      loadRecordings()
    }

    function viewDetail(row) {
      currentRecording.value = row
      detailVisible.value = true
    }

    async function deleteItem(id) {
      await api.deleteRecording(id)
      await loadRecordings()
    }

    function formatHeaders(headers) {
      if (!headers) return ''
      return Object.entries(headers).map(([k, v]) => k + ': ' + v).join('\n')
    }

    const formatTime = (ts) => ts ? new Date(ts).toLocaleString() : '-'

    onMounted(loadRecordings)
    return {
      recordings, loading, detailVisible, currentRecording,
      currentPage, pageSize, total,
      loadRecordings, onPageChange, onSizeChange,
      viewDetail, deleteItem, formatHeaders, formatTime, authStore
    }
  }
}
</script>

<style scoped>
.page-header { display: flex; justify-content: space-between; align-items: center; }
.page-header h2 { font-size: 20px; font-weight: 600; color: #303133; }
h4 { margin: 8px 0; color: #606266; }
.pagination-wrap { margin-top: 16px; display: flex; justify-content: flex-end; }
:deep(.el-pagination) { font-size: 12px; }
:deep(.el-pagination .el-pagination__total) { font-size: 12px; }
:deep(.el-pagination .el-pagination__sizes) { font-size: 12px; }
:deep(.el-pagination .el-pagination__sizes .el-input__wrapper) { font-size: 12px; height: 24px; }
:deep(.el-pagination .el-pagination__sizes .el-input__inner) { font-size: 12px; height: 24px; }
:deep(.el-pagination .btn-prev) { font-size: 12px; min-width: 24px; height: 24px; line-height: 24px; }
:deep(.el-pagination .btn-next) { font-size: 12px; min-width: 24px; height: 24px; line-height: 24px; }
:deep(.el-pagination .el-pager li) { font-size: 12px; min-width: 24px; height: 24px; line-height: 24px; }
.detail-meta { margin-bottom: 8px; font-size: 13px; color: #909399; }
.detail-meta span { margin-right: 16px; }
</style>
