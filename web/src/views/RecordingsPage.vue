<template>
  <div class="recordings-page">
    <div class="page-header">
      <h2>录制管理</h2>
      <el-button @click="loadRecordings">刷新</el-button>
    </div>

    <el-card shadow="never" style="margin-top: 16px">
      <el-form :inline="true" size="small" @submit.prevent="onSearch">
        <el-form-item label="规则">
          <el-input v-model="searchParams.ruleId" placeholder="规则ID" clearable style="width: 120px" />
        </el-form-item>
        <el-form-item label="Agent ID">
          <el-input v-model="searchParams.agentId" placeholder="Agent ID" clearable style="width: 120px" />
        </el-form-item>
        <el-form-item label="Agent IP">
          <el-input v-model="searchParams.agentIp" placeholder="Agent IP" clearable style="width: 130px" />
        </el-form-item>
        <el-form-item label="协议">
          <el-select v-model="searchParams.protocol" placeholder="全部" clearable style="width: 90px">
            <el-option label="HTTP" value="http" />
            <el-option label="HTTPS" value="https" />
            <el-option label="TCP" value="tcp" />
            <el-option label="UDP" value="udp" />
            <el-option label="GRPC" value="grpc" />
            <el-option label="DUBBO" value="dubbo" />
          </el-select>
        </el-form-item>
        <el-form-item label="方法">
          <el-select v-model="searchParams.method" placeholder="全部" clearable style="width: 90px">
            <el-option label="GET" value="GET" />
            <el-option label="POST" value="POST" />
            <el-option label="PUT" value="PUT" />
            <el-option label="DELETE" value="DELETE" />
            <el-option label="PATCH" value="PATCH" />
            <el-option label="HEAD" value="HEAD" />
            <el-option label="OPTIONS" value="OPTIONS" />
          </el-select>
        </el-form-item>
        <el-form-item label="路径">
          <el-input v-model="searchParams.path" placeholder="路径" clearable style="width: 130px" />
        </el-form-item>
        <el-form-item label="状态码">
          <el-input v-model="searchParams.statusCode" placeholder="如 200" clearable style="width: 90px" />
        </el-form-item>
        <el-form-item label="详情">
          <el-input v-model="searchParams.keyword" placeholder="搜索详情内容" clearable style="width: 140px" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="onSearch">搜索</el-button>
          <el-button @click="onReset">重置</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card shadow="never" style="margin-top: 16px" v-loading="loading">
      <el-table :data="recordings" stripe size="small" max-height="500" empty-text="暂无录制数据">
        <el-table-column prop="id" label="ID" width="120" show-overflow-tooltip />
        <el-table-column prop="ruleName" label="规则" width="140" show-overflow-tooltip>
          <template #default="{ row }">{{ row.ruleName || (row.ruleId ? row.ruleId : '未匹配') }}</template>
        </el-table-column>
        <el-table-column prop="agentId" label="Agent ID" width="120" show-overflow-tooltip />
        <el-table-column prop="agentIp" label="Agent IP" width="130" show-overflow-tooltip />
        <el-table-column prop="protocol" label="协议" width="105">
          <template #default="{ row }"><el-tag size="small">{{ (row.protocol || '').toUpperCase() }}</el-tag></template>
        </el-table-column>
        <el-table-column prop="method" label="方法" width="80">
          <template #default="{ row }">
            <template v-if="isMqProtocol(row.protocol)">
              <el-tag v-if="row.direction" size="small" :type="directionType(row.direction)">{{ directionLabel(row.direction) }}</el-tag>
              <el-tag v-else size="small" type="info">{{ row.method }}</el-tag>
            </template>
            <el-tag v-else size="small" type="info">{{ row.method }}</el-tag>
          </template>
        </el-table-column>
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
        <div class="detail-meta">
          <span v-if="currentRecording.ruleName || currentRecording.ruleId">规则: {{ currentRecording.ruleName || currentRecording.ruleId }}</span>
          <span v-if="currentRecording.direction">方向: {{ directionLabel(currentRecording.direction) }}</span>
          <span v-if="currentRecording.agentId">Agent: {{ currentRecording.agentId }}</span>
          <span v-if="currentRecording.agentIp">Agent IP: {{ currentRecording.agentIp }}</span>
          <span v-if="currentRecording.host">Target: {{ currentRecording.host }}<span v-if="currentRecording.port">:{{ currentRecording.port }}</span></span>
        </div>
        <el-input :model-value="formatHeaders(currentRecording.requestHeaders)" type="textarea" :rows="3" readonly />
        <el-input :model-value="currentRecording.requestBody" type="textarea" :rows="6" readonly style="margin-top: 8px" />
        <h4 style="margin-top: 16px">响应 ({{ currentRecording.responseStatusCode }})</h4>
        <el-input :model-value="formatHeaders(currentRecording.responseHeaders)" type="textarea" :rows="3" readonly />
        <el-input :model-value="currentRecording.responseBody" type="textarea" :rows="10" readonly style="margin-top: 8px" />
      </div>
    </el-dialog>
  </div>
</template>

<script>
import { ref, reactive, onMounted } from 'vue'
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

    const searchParams = reactive({
      ruleId: '',
      agentId: '',
      agentIp: '',
      protocol: '',
      method: '',
      path: '',
      statusCode: '',
      keyword: ''
    })

    function getSearchParams() {
      const params = {}
      if (searchParams.ruleId) params.ruleId = searchParams.ruleId
      if (searchParams.agentId) params.agentId = searchParams.agentId
      if (searchParams.agentIp) params.agentIp = searchParams.agentIp
      if (searchParams.protocol) params.protocol = searchParams.protocol
      if (searchParams.method) params.method = searchParams.method
      if (searchParams.path) params.path = searchParams.path
      if (searchParams.statusCode) params.statusCode = searchParams.statusCode
      if (searchParams.keyword) params.keyword = searchParams.keyword
      return params
    }

    async function loadRecordings() {
      loading.value = true
      const res = await api.getRecordingsPaged(getSearchParams(), currentPage.value, pageSize.value)
      if (res.success && res.data) {
        recordings.value = res.data.items || []
        total.value = res.data.total || 0
      }
      loading.value = false
    }

    function onSearch() {
      currentPage.value = 1
      loadRecordings()
    }

    function onReset() {
      searchParams.ruleId = ''
      searchParams.agentId = ''
      searchParams.agentIp = ''
      searchParams.protocol = ''
      searchParams.method = ''
      searchParams.path = ''
      searchParams.statusCode = ''
      searchParams.keyword = ''
      currentPage.value = 1
      loadRecordings()
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

    onMounted(loadRecordings)
    return {
      recordings, loading, detailVisible, currentRecording,
      currentPage, pageSize, total, searchParams,
      loadRecordings, onSearch, onReset, onPageChange, onSizeChange,
      viewDetail, deleteItem, formatHeaders, formatTime, isMqProtocol, directionLabel, directionType, authStore
    }
  }
}
</script>

<style scoped>
.page-header { display: flex; justify-content: space-between; align-items: center; }
h4 { margin: 8px 0; color: var(--bf-text-secondary); }
.pagination-wrap { margin-top: 16px; display: flex; justify-content: flex-end; }
:deep(.el-pagination) { font-size: 12px; }
:deep(.el-pagination .el-pagination__total) { font-size: 12px; }
:deep(.el-pagination .el-pagination__sizes) { font-size: 12px; }
:deep(.el-pagination .el-pagination__sizes .el-input__wrapper) { font-size: 12px; height: 24px; }
:deep(.el-pagination .el-pagination__sizes .el-input__inner) { font-size: 12px; height: 24px; }
:deep(.el-pagination .btn-prev) { font-size: 12px; min-width: 24px; height: 24px; line-height: 24px; }
:deep(.el-pagination .btn-next) { font-size: 12px; min-width: 24px; height: 24px; line-height: 24px; }
:deep(.el-pagination .el-pager li) { font-size: 12px; min-width: 24px; height: 24px; line-height: 24px; }
.detail-meta { margin-bottom: 8px; font-size: 13px; color: var(--bf-text-muted); }
.detail-meta span { margin-right: 16px; }
:deep(.el-form--inline .el-form-item) { margin-right: 12px; margin-bottom: 8px; }
</style>
