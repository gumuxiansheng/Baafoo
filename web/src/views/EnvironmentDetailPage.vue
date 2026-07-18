<template>
  <div class="env-detail-page">
    <div class="page-header">
      <el-button text @click="$router.back()"><el-icon><ArrowLeft /></el-icon> {{ $t('environments.back') }}</el-button>
      <h2>{{ env ? env.name : $t('environments.loading') }}</h2>
    </div>

    <el-card shadow="never" style="margin-top: 16px" v-if="env" v-loading="loading">
      <el-descriptions :column="2" border size="small">
        <el-descriptions-item :label="$t('environments.envId')">{{ env.id }}</el-descriptions-item>
        <el-descriptions-item :label="$t('environments.name')">{{ env.name }}</el-descriptions-item>
        <el-descriptions-item :label="$t('environments.currentMode')">
          <el-tag :type="modeTagType(env.mode) || undefined" effect="dark">{{ modeDisplayName(env.mode) }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item :label="$t('environments.createdAt')">{{ formatTime(env.createdAt) }}</el-descriptions-item>
      </el-descriptions>

      <h3 style="margin-top: 24px">{{ $t('environments.modeSwitch') }}</h3>
      <el-radio-group v-model="selectedMode" @change="switchMode" style="margin-top: 12px" v-if="authStore.canWriteEnvironment">
        <el-radio-button value="stub">Stub</el-radio-button>
        <el-radio-button value="passthrough">Passthrough</el-radio-button>
        <el-radio-button value="record">Record</el-radio-button>
        <el-radio-button value="record-and-stub">Record+Stub</el-radio-button>
        <el-radio-button value="record-all">Record All</el-radio-button>
      </el-radio-group>
      <span v-else style="color: var(--bf-text-muted); font-size: 14px; margin-top: 12px; display: inline-block">{{ $t('environments.noSwitchPermission', { 0: env ? modeDisplayName(env.mode) : '' }) }}</span>

      <h3 style="margin-top: 24px">{{ $t('environments.associatedAgents') }} ({{ agentRows.length }})</h3>
      <el-table :data="agentRows" size="small" style="margin-top: 12px" :empty-text="$t('environments.noAgent')">
        <el-table-column prop="agentId" label="Agent ID" min-width="200" />
        <el-table-column prop="environment" :label="$t('environments.name')" width="150" />
        <el-table-column prop="hostname" label="Hostname" min-width="150" />
        <el-table-column :label="$t('environments.createdAt')" width="180">
          <template #default="{ row }">{{ formatTime(row.registeredAt) }}</template>
        </el-table-column>
      </el-table>

      <h3 style="margin-top: 24px">{{ $t('environments.envVariables') }}
        <el-button size="small" text @click="showEditVariables" v-if="authStore.canWriteEnvironment" style="margin-left: 8px">
          <el-icon><Edit /></el-icon> {{ $t('environments.edit') }}
        </el-button>
      </h3>
      <el-table :data="variableList" size="small" style="margin-top: 12px" :empty-text="$t('environments.noVariables')">
        <el-table-column prop="key" :label="$t('environments.variableName')" />
        <el-table-column prop="value" :label="$t('environments.value')" />
      </el-table>
    </el-card>

    <el-dialog v-model="editVariablesVisible" :title="$t('environments.editVariables')" width="600px">
      <el-table :data="editVariables" size="small" border>
        <el-table-column :label="$t('environments.variableName')" min-width="200">
          <template #default="{ row }">
            <el-input v-model="row.key" :placeholder="$t('environments.variableName')" />
          </template>
        </el-table-column>
        <el-table-column :label="$t('environments.value')" min-width="200">
          <template #default="{ row }">
            <el-input v-model="row.value" :placeholder="$t('environments.value')" />
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
        <el-icon><Plus /></el-icon> {{ $t('environments.addVariable') }}
      </el-button>
      <template #footer>
        <el-button @click="editVariablesVisible = false">{{ $t('environments.cancel') }}</el-button>
        <el-button type="primary" @click="saveVariables" :loading="savingVariables">{{ $t('environments.save') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script>
import { ref, computed, onMounted, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useAuthStore } from '@/store'
import api from '@/api'
import { ElMessage } from 'element-plus'

export default {
  name: 'EnvironmentDetailPage',
  setup() {
    const route = useRoute()
    const authStore = useAuthStore()
    const { t } = useI18n()
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

    const agents = ref([])
    const agentRows = computed(() => {
      const envName = env.value?.name
      if (!envName) return []
      const ids = new Set(env.value?.agentIds || [])
      return agents.value.filter(a => a.environment === envName || ids.has(a.agentId))
    })

    // M25: extract load logic for route param watch
    async function loadEnv() {
      loading.value = true
      const res = await api.getEnvironment(route.params.id)
      if (res.success) {
        env.value = res.data
        selectedMode.value = res.data.mode
      }
      loading.value = false
      // Load agent details for richer display
      try {
        const agentRes = await api.getAgents()
        if (agentRes.success) agents.value = agentRes.data || []
      } catch (e) { console.error('Failed to load agents:', e) }
    }

    onMounted(loadEnv)
    // M25: reload when route param changes (same component, different id)
    watch(() => route.params.id, (newId, oldId) => {
      if (newId && newId !== oldId) {
        loadEnv()
      }
    })

    async function switchMode(mode) {
      // H-2: 乐观更新 + 失败回滚，避免 UI 显示与服务端不一致
      const prevMode = env.value ? env.value.mode : null
      if (env.value) env.value.mode = mode
      try {
        await api.updateEnvironment(route.params.id, { mode })
      } catch (e) {
        // 回滚到切换前的模式
        if (env.value) env.value.mode = prevMode
        selectedMode.value = prevMode
        ElMessage.error(t('environments.modeSwitchFailed') + ': ' + (e?.message || t('common.unknownError')))
      }
    }

    function modeTagType(mode) {
      const map = { 'stub': '', 'passthrough': 'info', 'record': 'warning', 'record-and-stub': 'success', 'record-all': 'danger' }
      return map[mode] || ''
    }

    function modeDisplayName(mode) {
      const map = { 'stub': 'Stub', 'passthrough': 'Passthrough', 'record': 'Record', 'record-and-stub': 'Record+Stub', 'record-all': 'Record All' }
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
          ElMessage.success(t('environments.varSaveSuccess'))
        } else {
          ElMessage.error(res.message || t('environments.varSaveFailed'))
        }
      } catch (e) {
        ElMessage.error(t('environments.varSaveFailed') + ': ' + (e.message || t('common.unknownError')))
      } finally {
        savingVariables.value = false
      }
    }

    const formatTime = (ts) => ts ? new Date(ts).toLocaleString() : '-'
    return { env, loading, selectedMode, variableList, agentRows, switchMode, modeTagType, modeDisplayName, formatTime, authStore,
             editVariablesVisible, editVariables, savingVariables, showEditVariables, addVariable, removeVariable, saveVariables }
  }
}
</script>

<style scoped>
.page-header { display: flex; align-items: center; gap: 8px; }
h3 { font-size: 15px; font-weight: 700; color: var(--bf-text); }
</style>
