<template>
  <div class="recordings-page">
    <div class="page-header">
      <h2>{{ $t('recordings.title') }}</h2>
      <el-button @click="loadRecordings">{{ $t('recordings.refresh') }}</el-button>
    </div>

    <el-card shadow="never" style="margin-top: 16px">
      <el-form :inline="true" size="small" @submit.prevent="onSearch">
        <el-form-item :label="$t('recordings.rule')">
          <el-input v-model="searchParams.ruleId" :placeholder="$t('recordings.rulePlaceholder')" clearable style="width: 120px" />
        </el-form-item>
        <el-form-item label="Agent ID">
          <el-input v-model="searchParams.agentId" :placeholder="$t('recordings.agentPlaceholder')" clearable style="width: 120px" />
        </el-form-item>
        <el-form-item label="Agent IP">
          <el-input v-model="searchParams.agentIp" :placeholder="$t('recordings.agentIpPlaceholder')" clearable style="width: 130px" />
        </el-form-item>
        <el-form-item :label="$t('recordings.protocol')">
          <el-select v-model="searchParams.protocol" :placeholder="$t('recordings.all')" clearable style="width: 90px">
            <el-option label="HTTP" value="http" />
            <el-option label="HTTPS" value="https" />
            <el-option label="TCP" value="tcp" />
            <el-option label="UDP" value="udp" />
            <el-option label="GRPC" value="grpc" />
            <el-option label="DUBBO" value="dubbo" />
          </el-select>
        </el-form-item>
        <el-form-item :label="$t('recordings.method')">
          <el-select v-model="searchParams.method" :placeholder="$t('recordings.all')" clearable style="width: 90px">
            <el-option label="GET" value="GET" />
            <el-option label="POST" value="POST" />
            <el-option label="PUT" value="PUT" />
            <el-option label="DELETE" value="DELETE" />
            <el-option label="PATCH" value="PATCH" />
            <el-option label="HEAD" value="HEAD" />
            <el-option label="OPTIONS" value="OPTIONS" />
          </el-select>
        </el-form-item>
        <el-form-item :label="$t('recordings.path')">
          <el-input v-model="searchParams.path" :placeholder="$t('recordings.path')" clearable style="width: 130px" />
        </el-form-item>
        <el-form-item :label="$t('recordings.statusCode')">
          <el-input v-model="searchParams.statusCode" :placeholder="$t('recordings.statusCodePlaceholder')" clearable style="width: 90px" />
        </el-form-item>
        <el-form-item :label="$t('recordings.keyword')">
          <el-input v-model="searchParams.keyword" :placeholder="$t('recordings.keywordPlaceholder')" clearable style="width: 140px" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="onSearch">{{ $t('recordings.search') }}</el-button>
          <el-button @click="onReset">{{ $t('recordings.reset') }}</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card shadow="never" style="margin-top: 16px" v-loading="loading">
      <el-table :data="recordings" stripe size="small" max-height="500" :empty-text="$t('recordings.noData')">
        <el-table-column prop="id" label="ID" width="120" show-overflow-tooltip />
        <el-table-column prop="ruleName" :label="$t('recordings.rule')" width="140" show-overflow-tooltip>
          <template #default="{ row }">{{ row.ruleName || (row.ruleId ? row.ruleId : $t('recordings.unmatched')) }}</template>
        </el-table-column>
        <el-table-column prop="agentId" label="Agent ID" width="120" show-overflow-tooltip />
        <el-table-column prop="agentIp" label="Agent IP" width="130" show-overflow-tooltip />
        <el-table-column prop="protocol" :label="$t('recordings.protocol')" width="105">
          <template #default="{ row }"><el-tag size="small">{{ (row.protocol || '').toUpperCase() }}</el-tag></template>
        </el-table-column>
        <el-table-column prop="method" :label="$t('recordings.method')" width="80">
          <template #default="{ row }">
            <template v-if="isNonHttpProtocol(row.protocol)">
              <el-tag v-if="row.direction" size="small" :type="directionType(row.direction)">{{ $t(directionLabel(row.direction)) }}</el-tag>
              <el-tag v-else size="small" type="info">{{ row.method }}</el-tag>
            </template>
            <el-tag v-else size="small" type="info">{{ row.method }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="path" :label="$t('recordings.path')" min-width="180" show-overflow-tooltip />
        <el-table-column prop="responseStatusCode" :label="$t('recordings.statusCode')" width="70" align="center" />
        <el-table-column prop="responseTimeMs" :label="$t('logs.latencyMs')" width="80" align="center" />
        <el-table-column prop="recordedAt" :label="$t('recordings.recordedAt')" width="170">
          <template #default="{ row }">{{ formatTime(row.recordedAt) }}</template>
        </el-table-column>
        <el-table-column :label="$t('recordings.actions')" min-width="180" fixed="right">
          <template #default="{ row }">
            <el-button size="small" text @click="viewDetail(row)">{{ $t('recordings.detail') }}</el-button>
            <el-popconfirm :title="$t('recordings.confirmDelete')" @confirm="deleteItem(row.id)" v-if="authStore.canWriteRecording">
              <template #reference>
                <el-button size="small" text type="danger">{{ $t('recordings.delete') }}</el-button>
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
    <el-dialog v-model="detailVisible" :title="$t('recordings.detailTitle')" width="760px" class="recording-detail-dialog">
      <div v-if="currentRecording" class="detail-body">
        <div class="detail-meta">
          <el-tag size="large" effect="dark" class="protocol-tag" :type="protocolTagType">{{ protocolLabel }}</el-tag>
          <el-tag v-if="responseSourceLabel" size="small" effect="plain" :type="responseSourceTagType" class="source-tag">{{ $t(responseSourceLabel) }}</el-tag>
          <span class="meta-id">ID: {{ currentRecording.id }}</span>
          <span class="meta-time">{{ formatTime(currentRecording.recordedAt) }}</span>
        </div>

        <el-descriptions :column="2" size="small" border class="meta-desc">
          <el-descriptions-item v-if="currentRecording.ruleName || currentRecording.ruleId" :label="$t('recordings.rule')">
            {{ currentRecording.ruleName || currentRecording.ruleId }}
          </el-descriptions-item>
          <el-descriptions-item v-if="currentRecording.agentId" label="Agent ID">{{ currentRecording.agentId }}</el-descriptions-item>
          <el-descriptions-item v-if="currentRecording.agentIp" label="Agent IP">{{ currentRecording.agentIp }}</el-descriptions-item>
          <el-descriptions-item v-if="currentRecording.host" :label="$t('recordings.host')">
            {{ currentRecording.host }}<span v-if="currentRecording.port">:{{ currentRecording.port }}</span>
          </el-descriptions-item>
          <el-descriptions-item v-if="currentRecording.serviceName" :label="$t('rules.serviceName')">{{ currentRecording.serviceName }}</el-descriptions-item>
        </el-descriptions>

        <!-- HTTP/HTTPS -->
        <template v-if="isHttpProtocol(currentRecording.protocol)">
          <div class="detail-section">
            <div class="section-header">
              <span class="section-title">{{ $t('recordings.detailRequest') }}</span>
              <el-tag size="small" type="info">{{ currentRecording.method }}</el-tag>
              <span v-if="currentRecording.path" class="section-sub">{{ currentRecording.path }}</span>
            </div>
            <div v-if="hasRequestHeaders" class="readonly-field">
              <div class="field-label">{{ $t('recordings.requestHeaders') }}</div>
              <pre class="readonly-pre">{{ formatHeaders(currentRecording.requestHeaders) }}</pre>
            </div>
            <div v-else class="empty-field">{{ $t('recordings.noHeaders') }}</div>
            <div v-if="currentRecording.requestBody" class="readonly-field">
              <div class="field-label">{{ $t('recordings.requestBody') }}</div>
              <pre class="readonly-pre">{{ currentRecording.requestBody }}</pre>
            </div>
            <div v-else class="empty-field">{{ $t('recordings.noBody') }}</div>
          </div>
          <div class="detail-section">
            <div class="section-header">
              <span class="section-title">{{ $t('recordings.detailResponse') }}</span>
              <el-tag size="small" :type="statusTagType">{{ currentRecording.responseStatusCode }}</el-tag>
            </div>
            <div v-if="hasResponseHeaders" class="readonly-field">
              <div class="field-label">{{ $t('recordings.responseHeaders') }}</div>
              <pre class="readonly-pre">{{ formatHeaders(currentRecording.responseHeaders) }}</pre>
            </div>
            <div v-else class="empty-field">{{ $t('recordings.noHeaders') }}</div>
            <div v-if="currentRecording.responseBody" class="readonly-field">
              <div class="field-label">{{ $t('recordings.responseBody') }}</div>
              <pre class="readonly-pre">{{ currentRecording.responseBody }}</pre>
            </div>
            <div v-else class="empty-field">{{ $t('recordings.noBody') }}</div>
          </div>
        </template>

        <!-- gRPC -->
        <template v-else-if="isGrpcProtocol(currentRecording.protocol)">
          <div class="detail-section">
            <div class="section-header">
              <span class="section-title">{{ $t('recordings.detailRequest') }}</span>
              <span v-if="currentRecording.path" class="section-sub">{{ currentRecording.path }}</span>
            </div>
            <el-descriptions :column="2" size="small" border class="meta-desc">
              <el-descriptions-item v-if="currentRecording.grpcService" :label="$t('recordings.grpcService')">{{ currentRecording.grpcService }}</el-descriptions-item>
              <el-descriptions-item v-if="currentRecording.grpcMethod" :label="$t('recordings.grpcMethod')">{{ currentRecording.grpcMethod }}</el-descriptions-item>
              <el-descriptions-item v-if="currentRecording.grpcContentType" :label="$t('recordings.grpcContentType')">{{ currentRecording.grpcContentType }}</el-descriptions-item>
            </el-descriptions>
            <div v-if="currentRecording.requestBody" class="readonly-field">
              <div class="field-label">{{ $t('recordings.requestBody') }}</div>
              <pre class="readonly-pre">{{ currentRecording.requestBody }}</pre>
            </div>
            <div v-else class="empty-field">{{ $t('recordings.noBody') }}</div>
          </div>
          <div class="detail-section">
            <div class="section-header">
              <span class="section-title">{{ $t('recordings.detailResponse') }}</span>
              <el-tag size="small" :type="statusTagType">{{ $t('recordings.grpcStatus') }} {{ currentRecording.grpcStatus }}</el-tag>
            </div>
            <div v-if="currentRecording.responseBody" class="readonly-field">
              <div class="field-label">{{ $t('recordings.responseBody') }}</div>
              <pre class="readonly-pre">{{ currentRecording.responseBody }}</pre>
            </div>
            <div v-else class="empty-field">{{ $t('recordings.noBody') }}</div>
          </div>
        </template>

        <!-- TCP / UDP -->
        <template v-else-if="isTcpUdpProtocol(currentRecording.protocol)">
          <div class="detail-section">
            <div class="section-header">
              <span class="section-title">{{ directionLabel(currentRecording.direction) }}</span>
              <el-tag size="small" :type="directionType(currentRecording.direction)">{{ $t(directionLabel(currentRecording.direction)) }}</el-tag>
            </div>
            <div v-if="currentRecording.host" class="readonly-field">
              <div class="field-label">{{ $t('recordings.host') }}</div>
              <pre class="readonly-pre">{{ currentRecording.host }}<span v-if="currentRecording.port">:{{ currentRecording.port }}</span></pre>
            </div>
            <div v-if="currentRecording.dataHex" class="readonly-field">
              <div class="field-label">{{ $t('recordings.rawData') }}</div>
              <pre class="readonly-pre raw-hex">{{ currentRecording.dataHex }}</pre>
            </div>
            <div v-else-if="currentRecording.requestBody" class="readonly-field">
              <div class="field-label">{{ $t('recordings.requestBody') }}</div>
              <pre class="readonly-pre">{{ currentRecording.requestBody }}</pre>
            </div>
            <div v-else class="empty-field">{{ $t('recordings.noBody') }}</div>
          </div>
        </template>

        <!-- MQ: Kafka / Pulsar / JMS -->
        <template v-else-if="isNonHttpProtocol(currentRecording.protocol)">
          <div class="detail-section">
            <div class="section-header">
              <span class="section-title">{{ $t('recordings.direction') }}</span>
              <el-tag size="small" :type="directionType(currentRecording.direction)">{{ $t(directionLabel(currentRecording.direction)) }}</el-tag>
            </div>
            <div class="readonly-field">
              <div class="field-label">{{ isJmsProtocol(currentRecording.protocol) ? $t('recordings.destination') : $t('recordings.topic') }}</div>
              <pre class="readonly-pre">{{ currentRecording.path || '-' }}</pre>
            </div>
            <div v-if="currentRecording.requestBody" class="readonly-field">
              <div class="field-label">{{ isJmsProtocol(currentRecording.protocol) ? $t('recordings.requestBody') : $t('recordings.produce') + ' Body' }}</div>
              <pre class="readonly-pre">{{ currentRecording.requestBody }}</pre>
            </div>
            <div v-if="currentRecording.responseBody" class="readonly-field">
              <div class="field-label">{{ isJmsProtocol(currentRecording.protocol) ? $t('recordings.responseBody') : $t('recordings.consume') + ' Body' }}</div>
              <pre class="readonly-pre">{{ currentRecording.responseBody }}</pre>
            </div>
            <div v-if="!currentRecording.requestBody && !currentRecording.responseBody" class="empty-field">{{ $t('recordings.noBody') }}</div>
          </div>
        </template>

        <!-- Unknown fallback -->
        <template v-else>
          <div class="detail-section">
            <div v-if="currentRecording.requestBody" class="readonly-field">
              <div class="field-label">{{ $t('recordings.requestBody') }}</div>
              <pre class="readonly-pre">{{ currentRecording.requestBody }}</pre>
            </div>
            <div v-if="currentRecording.responseBody" class="readonly-field">
              <div class="field-label">{{ $t('recordings.responseBody') }}</div>
              <pre class="readonly-pre">{{ currentRecording.responseBody }}</pre>
            </div>
            <div v-if="!currentRecording.requestBody && !currentRecording.responseBody" class="empty-field">{{ $t('recordings.noBody') }}</div>
          </div>
        </template>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import { ref, reactive, onMounted, computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useAuthStore } from '@/store'
import api from '@/api'

export default {
  name: 'RecordingsPage',
  setup() {
    const authStore = useAuthStore()
    const { t } = useI18n()
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
      // M-8: If we deleted the last row on the current page (and we're not on page 1),
      // step back one page so the user doesn't see an empty table with no pagination cue.
      if (recordings.value.length === 1 && currentPage.value > 1) {
        currentPage.value -= 1
      }
      await loadRecordings()
    }

    function formatHeaders(headers) {
      if (!headers) return ''
      return Object.entries(headers).map(([k, v]) => k + ': ' + v).join('\n')
    }

    const formatTime = (ts) => ts ? new Date(ts).toLocaleString() : '-'

    const isHttpProtocol = (protocol) => {
      const p = (protocol || '').toLowerCase()
      return p === 'http' || p === 'https'
    }

    const isGrpcProtocol = (protocol) => {
      return (protocol || '').toLowerCase() === 'grpc'
    }

    const isTcpUdpProtocol = (protocol) => {
      const p = (protocol || '').toLowerCase()
      return p === 'tcp' || p === 'udp'
    }

    const isJmsProtocol = (protocol) => {
      return (protocol || '').toLowerCase() === 'jms'
    }

    const isNonHttpProtocol = (protocol) => {
      const mqProtocols = ['kafka', 'pulsar', 'jms', 'tcp', 'udp', 'grpc', 'dubbo']
      return mqProtocols.includes((protocol || '').toLowerCase())
    }

    const directionLabel = (d) => {
      if (d === 'produce' || d === 'request') return 'recordings.send'
      if (d === 'consume' || d === 'response') return 'recordings.receive'
      return d
    }
    const directionType = (d) => {
      if (d === 'produce' || d === 'request') return 'warning'
      if (d === 'consume' || d === 'response') return 'success'
      return 'info'
    }

    const protocolLabel = computed(() => {
      const p = (currentRecording.value?.protocol || '').toUpperCase()
      return p || 'UNKNOWN'
    })

    const protocolTagType = computed(() => {
      const p = (currentRecording.value?.protocol || '').toLowerCase()
      if (p === 'http' || p === 'https') return 'success'
      if (p === 'grpc') return 'warning'
      if (p === 'kafka' || p === 'pulsar' || p === 'jms') return 'danger'
      if (p === 'tcp' || p === 'udp') return 'info'
      return ''
    })

    const statusTagType = computed(() => {
      const code = currentRecording.value?.responseStatusCode || 0
      const status = currentRecording.value?.grpcStatus
      const effective = code || status || 0
      if (effective >= 200 && effective < 300) return 'success'
      if (effective >= 300 && effective < 400) return 'warning'
      if (effective >= 400) return 'danger'
      return 'info'
    })

    const hasRequestHeaders = computed(() => {
      const h = currentRecording.value?.requestHeaders
      return h && Object.keys(h).length > 0
    })

    const hasResponseHeaders = computed(() => {
      const h = currentRecording.value?.responseHeaders
      return h && Object.keys(h).length > 0
    })

    const responseSourceLabel = computed(() => {
      const s = currentRecording.value?.responseSource
      if (!s) return ''
      const map = { STUB: 'recordings.sourceStub', PASSTHROUGH: 'recordings.sourcePassthrough', ERROR: 'recordings.sourceError' }
      return map[s] || ''
    })

    const responseSourceTagType = computed(() => {
      const s = currentRecording.value?.responseSource
      if (s === 'STUB') return 'warning'
      if (s === 'PASSTHROUGH') return 'success'
      if (s === 'ERROR') return 'danger'
      return 'info'
    })

    onMounted(loadRecordings)
    return {
      recordings, loading, detailVisible, currentRecording,
      currentPage, pageSize, total, searchParams,
      loadRecordings, onSearch, onReset, onPageChange, onSizeChange,
      viewDetail, deleteItem, formatHeaders, formatTime,
      isHttpProtocol, isGrpcProtocol, isTcpUdpProtocol, isNonHttpProtocol, isJmsProtocol,
      directionLabel, directionType,
      protocolLabel, protocolTagType, statusTagType,
      hasRequestHeaders, hasResponseHeaders,
      responseSourceLabel, responseSourceTagType,
      authStore
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
.detail-meta { margin-bottom: 12px; font-size: 13px; color: var(--bf-text-muted); display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.detail-meta .protocol-tag { font-weight: 600; text-transform: uppercase; }
.detail-meta .source-tag { font-weight: 600; }
.detail-meta .meta-id { font-family: monospace; }
.detail-meta .meta-time { margin-left: auto; }
.meta-desc { margin-bottom: 16px; }
.detail-section { background: var(--bf-bg-secondary); border: 1px solid var(--bf-border); border-radius: var(--bf-radius); padding: 12px; margin-bottom: 12px; }
.detail-section:last-child { margin-bottom: 0; }
.section-header { display: flex; align-items: center; gap: 8px; margin-bottom: 12px; font-weight: 600; color: var(--bf-text-primary); }
.section-title { font-size: 15px; }
.section-sub { color: var(--bf-text-secondary); font-weight: 400; font-size: 13px; }
.readonly-field { margin-bottom: 12px; }
.readonly-field:last-child { margin-bottom: 0; }
.field-label { font-size: 12px; color: var(--bf-text-secondary); margin-bottom: 4px; }
.readonly-pre { margin: 0; padding: 8px 12px; background: var(--bf-bg); border: 1px solid var(--bf-border); border-radius: var(--bf-radius); font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; font-size: 12px; line-height: 1.5; color: var(--bf-text-primary); white-space: pre-wrap; word-break: break-word; max-height: 320px; overflow: auto; }
.readonly-pre.raw-hex { word-break: break-all; }
.empty-field { padding: 8px 12px; background: var(--bf-bg); border: 1px dashed var(--bf-border); border-radius: var(--bf-radius); color: var(--bf-text-muted); font-size: 12px; text-align: center; margin-bottom: 12px; }
.empty-field:last-child { margin-bottom: 0; }
:deep(.recording-detail-dialog .el-dialog__body) { padding-top: 8px; }
:deep(.el-descriptions__label) { width: 110px; }
:deep(.el-form--inline .el-form-item) { margin-right: 12px; margin-bottom: 8px; }
</style>
