<template>
  <div class="env-detail-page">
    <div class="page-header">
      <el-button text @click="$router.back()"><el-icon><ArrowLeft /></el-icon> 返回</el-button>
      <h2>{{ env ? env.name : '加载中...' }}</h2>
    </div>

    <el-card shadow="never" style="margin-top: 16px" v-if="env" v-loading="loading">
      <el-descriptions :column="2" border size="small">
        <el-descriptions-item label="环境ID">{{ env.id }}</el-descriptions-item>
        <el-descriptions-item label="名称">{{ env.name }}</el-descriptions-item>
        <el-descriptions-item label="当前模式">
          <el-tag :type="modeTagType(env.mode)" effect="dark">{{ env.mode }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="创建时间">{{ formatTime(env.createdAt) }}</el-descriptions-item>
      </el-descriptions>

      <h3 style="margin-top: 24px">模式切换</h3>
      <el-radio-group v-model="selectedMode" @change="switchMode" style="margin-top: 12px">
        <el-radio-button value="STUB">Stub</el-radio-button>
        <el-radio-button value="PASSTHROUGH">Passthrough</el-radio-button>
        <el-radio-button value="RECORD">Record</el-radio-button>
        <el-radio-button value="RECORD_AND_STUB">Record+Stub</el-radio-button>
      </el-radio-group>

      <h3 style="margin-top: 24px">关联 Agents ({{ (env.agentIds || []).length }})</h3>
      <el-table :data="env.agentIds || []" size="small" style="margin-top: 12px" empty-text="暂无 Agent">
        <el-table-column label="Agent ID" min-width="200">
          <template #default="{ row }">{{ row }}</template>
        </el-table-column>
      </el-table>

      <h3 style="margin-top: 24px">环境变量</h3>
      <el-table :data="variableList" size="small" style="margin-top: 12px" empty-text="无变量">
        <el-table-column prop="key" label="变量名" />
        <el-table-column prop="value" label="值" />
      </el-table>
    </el-card>
  </div>
</template>

<script>
import { ref, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import api from '@/api'

export default {
  name: 'EnvironmentDetailPage',
  setup() {
    const route = useRoute()
    const env = ref(null)
    const loading = ref(true)
    const selectedMode = ref('')

    const variableList = computed(() => {
      if (!env.value || !env.value.variables) return []
      return Object.entries(env.value.variables).map(([key, value]) => ({ key, value }))
    })

    onMounted(async () => {
      const res = await api.getEnvironment(route.params.id)
      if (res.success) {
        env.value = res.data
        selectedMode.value = res.data.mode
      }
      loading.value = false
    })

    async function switchMode(mode) {
      await api.updateEnvironment(route.params.id, { mode })
      env.value.mode = mode
    }

    function modeTagType(mode) {
      const map = { STUB: '', PASSTHROUGH: 'info', RECORD: 'warning', RECORD_AND_STUB: 'success' }
      return map[mode] || ''
    }

    const formatTime = (ts) => ts ? new Date(ts).toLocaleString() : '-'
    return { env, loading, selectedMode, variableList, switchMode, modeTagType, formatTime }
  }
}
</script>

<style scoped>
.page-header { display: flex; align-items: center; gap: 8px; }
.page-header h2 { font-size: 20px; font-weight: 600; color: #303133; }
h3 { font-size: 16px; font-weight: 600; color: #303133; }
</style>
