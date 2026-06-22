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
          <el-tag :type="modeTagType(env.mode) || undefined" effect="dark">{{ modeDisplayName(env.mode) }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="创建时间">{{ formatTime(env.createdAt) }}</el-descriptions-item>
      </el-descriptions>

      <h3 style="margin-top: 24px">模式切换</h3>
      <el-radio-group v-model="selectedMode" @change="switchMode" style="margin-top: 12px" v-if="authStore.canWriteEnvironment">
        <el-radio-button value="STUB">Stub</el-radio-button>
        <el-radio-button value="PASSTHROUGH">Passthrough</el-radio-button>
        <el-radio-button value="RECORD">Record</el-radio-button>
        <el-radio-button value="RECORD_AND_STUB">Record+Stub</el-radio-button>
      </el-radio-group>
      <span v-else style="color: #909399; font-size: 14px; margin-top: 12px; display: inline-block">当前模式: {{ env ? modeDisplayName(env.mode) : '' }}（无切换权限）</span>

      <h3 style="margin-top: 24px">关联 Agents ({{ (env.agentIds || []).length }})</h3>
      <el-table :data="env.agentIds || []" size="small" style="margin-top: 12px" empty-text="暂无 Agent">
        <el-table-column label="Agent ID" min-width="200">
          <template #default="{ row }">{{ row }}</template>
        </el-table-column>
      </el-table>

      <h3 style="margin-top: 24px">环境变量
        <el-button size="small" text @click="showEditVariables" v-if="authStore.canWriteEnvironment" style="margin-left: 8px">
          <el-icon><Edit /></el-icon> 编辑
        </el-button>
      </h3>
      <el-table :data="variableList" size="small" style="margin-top: 12px" empty-text="无变量">
        <el-table-column prop="key" label="变量名" />
        <el-table-column prop="value" label="值" />
      </el-table>
    </el-card>

    <el-dialog v-model="editVariablesVisible" title="编辑环境变量" width="600px">
      <el-table :data="editVariables" size="small" border>
        <el-table-column label="变量名" min-width="200">
          <template #default="{ row }">
            <el-input v-model="row.key" placeholder="变量名" />
          </template>
        </el-table-column>
        <el-table-column label="值" min-width="200">
          <template #default="{ row }">
            <el-input v-model="row.value" placeholder="值" />
          </template>
        </el-table-column>
        <el-table-column width="60" align="center">
          <template #default="{ $index }">
            <el-button text type="danger" @click="removeVariable($index)">
              <el-icon><Delete /></el-icon>
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-button size="small" type="primary" plain @click="addVariable" style="margin-top: 8px">
        <el-icon><Plus /></el-icon> 新增变量
      </el-button>
      <template #footer>
        <el-button @click="editVariablesVisible = false">取消</el-button>
        <el-button type="primary" @click="saveVariables" :loading="savingVariables">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script>
import { ref, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { useAuthStore } from '@/store'
import api from '@/api'
import { ElMessage } from 'element-plus'

export default {
  name: 'EnvironmentDetailPage',
  setup() {
    const route = useRoute()
    const authStore = useAuthStore()
    const env = ref(null)
    const loading = ref(true)
    const selectedMode = ref('')
    const editVariablesVisible = ref(false)
    const editVariables = ref([])
    const savingVariables = ref(false)

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
      const map = { stub: '', passthrough: 'info', record: 'warning', 'record-and-stub': 'success' }
      return map[mode] || ''
    }

    function modeDisplayName(mode) {
      const map = { stub: 'Stub', passthrough: 'Passthrough', record: 'Record', 'record-and-stub': 'Record+Stub' }
      return map[mode] || mode
    }

    function showEditVariables() {
      if (env.value && env.value.variables) {
        editVariables.value = Object.entries(env.value.variables).map(([key, value]) => ({ key, value }))
      } else {
        editVariables.value = []
      }
      editVariablesVisible.value = true
    }

    function addVariable() {
      editVariables.value.push({ key: '', value: '' })
    }

    function removeVariable(index) {
      editVariables.value.splice(index, 1)
    }

    async function saveVariables() {
      const variables = {}
      for (const item of editVariables.value) {
        if (item.key.trim()) {
          variables[item.key.trim()] = item.value
        }
      }
      savingVariables.value = true
      try {
        const res = await api.updateEnvironment(route.params.id, { variables })
        if (res.success) {
          env.value.variables = variables
          editVariablesVisible.value = false
          ElMessage.success('环境变量保存成功')
        } else {
          ElMessage.error(res.message || '保存失败')
        }
      } catch (e) {
        ElMessage.error('保存失败: ' + (e.message || '未知错误'))
      } finally {
        savingVariables.value = false
      }
    }

    const formatTime = (ts) => ts ? new Date(ts).toLocaleString() : '-'
    return { env, loading, selectedMode, variableList, switchMode, modeTagType, modeDisplayName, formatTime, authStore,
             editVariablesVisible, editVariables, savingVariables, showEditVariables, addVariable, removeVariable, saveVariables }
  }
}
</script>

<style scoped>
.page-header { display: flex; align-items: center; gap: 8px; }
.page-header h2 { font-size: 20px; font-weight: 600; color: #303133; }
h3 { font-size: 16px; font-weight: 600; color: #303133; }
</style>
