<template>
  <div class="environments-page">
    <div class="page-header">
      <h2>环境管理</h2>
      <el-button type="primary" @click="showCreateDialog" v-if="authStore.canWriteEnvironment">
        <el-icon><Plus /></el-icon> 新建环境
      </el-button>
    </div>

    <el-card shadow="never" style="margin-top: 16px" v-loading="loading">
      <el-table :data="environments" stripe size="small" empty-text="暂无环境">
        <el-table-column prop="name" label="名称" min-width="150" />
        <el-table-column label="模式" width="160" align="center">
          <template #default="{ row }">
            <el-tag :type="modeTagType(row.mode)" effect="dark">
              {{ modeLabel(row) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="Agents" width="80" align="center">
          <template #default="{ row }">{{ (row.agentIds || []).length }}</template>
        </el-table-column>
        <el-table-column label="关联规则" width="100" align="center">
          <template #default="{ row }">
            <el-link type="primary" @click="showAssociateDialog(row)">{{ getRuleCountForEnv(row.name) }}</el-link>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" width="180">
          <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="240" fixed="right">
          <template #default="{ row }">
            <el-button size="small" text @click="viewDetail(row.id)">详情</el-button>
            <el-dropdown @command="(cmd) => changeMode(row, cmd)" style="margin-left: 8px" v-if="authStore.canWriteEnvironment">
              <el-button size="small" text>切换模式 <el-icon><ArrowDown /></el-icon></el-button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="stub" :disabled="row.mode === 'stub'">Stub 模式</el-dropdown-item>
                  <el-dropdown-item command="passthrough" :disabled="row.mode === 'passthrough'">Passthrough</el-dropdown-item>
                  <el-dropdown-item command="record" :disabled="row.mode === 'record'">Record</el-dropdown-item>
                  <el-dropdown-item command="record-and-stub" :disabled="row.mode === 'record-and-stub'">Record+Stub</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
            <el-popconfirm title="确定删除？" @confirm="deleteEnv(row.id)" v-if="authStore.canWriteEnvironment">
              <template #reference>
                <el-button size="small" text type="danger">删除</el-button>
              </template>
            </el-popconfirm>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="dialogVisible" title="新建环境" width="500px">
      <el-form :model="form" label-width="80px">
        <el-form-item label="名称" required>
          <el-input v-model="form.name" placeholder="如: dev, staging" />
        </el-form-item>
        <el-form-item label="默认模式">
          <el-select v-model="form.mode" style="width: 100%">
            <el-option label="Stub（挡板）" value="stub" />
            <el-option label="Passthrough（透传）" value="passthrough" />
            <el-option label="Record（录制）" value="record" />
            <el-option label="Record+Stub" value="record-and-stub" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="createEnv">创建</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="associateVisible" :title="`关联规则 - ${currentEnv.name || ''}`" width="600px">
      <el-form label-width="80px">
        <el-form-item label="当前环境">
          <el-tag>{{ currentEnv.name }}</el-tag>
        </el-form-item>
        <el-form-item label="关联规则">
          <el-select v-model="selectedRuleIds" multiple filterable placeholder="选择要关联的规则" style="width: 100%">
            <el-option v-for="r in allRules" :key="r.id" :label="r.name" :value="r.id" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="associateVisible = false">取消</el-button>
        <el-button type="primary" @click="saveAssociation" :loading="saving" v-if="authStore.canWriteEnvironment">保存关联</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script>
import { ref, reactive, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/store'
import api from '@/api'

export default {
  name: 'EnvironmentsPage',
  setup() {
    const router = useRouter()
    const authStore = useAuthStore()
    const environments = ref([])
    const allRules = ref([])
    const loading = ref(false)
    const saving = ref(false)
    const dialogVisible = ref(false)
    const associateVisible = ref(false)
    const currentEnv = reactive({ id: '', name: '' })
    const selectedRuleIds = ref([])
    const form = reactive({ name: '', mode: 'record-and-stub' })

    async function loadEnvs() {
      loading.value = true
      const res = await api.getEnvironments()
      if (res.success) environments.value = res.data
      loading.value = false
    }

    async function loadRules() {
      const res = await api.getRules()
      if (res.success) allRules.value = res.data
    }

    function getRuleCountForEnv(envName) {
      return allRules.value.filter(r => r.environments && r.environments.includes(envName)).length
    }

    function showAssociateDialog(env) {
      currentEnv.id = env.id
      currentEnv.name = env.name
      selectedRuleIds.value = allRules.value
        .filter(r => r.environments && r.environments.includes(env.name))
        .map(r => r.id)
      associateVisible.value = true
    }

    async function saveAssociation() {
      saving.value = true
      const envName = currentEnv.name
      const currentAssociated = allRules.value
        .filter(r => r.environments && r.environments.includes(envName))
        .map(r => r.id)
      const toAdd = selectedRuleIds.value.filter(id => !currentAssociated.includes(id))
      const toRemove = currentAssociated.filter(id => !selectedRuleIds.value.includes(id))
      if (toAdd.length > 0) {
        await api.associateRulesToEnv(currentEnv.id, toAdd)
      }
      if (toRemove.length > 0) {
        await api.dissociateRulesFromEnv(currentEnv.id, toRemove)
      }
      saving.value = false
      associateVisible.value = false
      await loadRules()
    }

    function modeTagType(mode) {
      const map = { 'stub': '', 'passthrough': 'info', 'record': 'warning', 'record-and-stub': 'success' }
      return map[mode] || ''
    }

    function modeLabel(row) {
      const map = { 'stub': 'Stub', 'passthrough': 'Passthrough', 'record': 'Record', 'record-and-stub': 'Record+Stub' }
      return map[row.mode] || row.mode
    }

    function showCreateDialog() {
      form.name = ''
      form.mode = 'record-and-stub'
      dialogVisible.value = true
    }

    async function createEnv() {
      const res = await api.createEnvironment({ name: form.name, mode: form.mode })
      if (res.success) {
        dialogVisible.value = false
        await loadEnvs()
      }
    }

    async function changeMode(env, cmd) {
      await api.updateEnvironment(env.id, { mode: cmd })
      await loadEnvs()
    }

    function viewDetail(id) {
      router.push(`/environments/${id}`)
    }

    async function deleteEnv(id) {
      await api.deleteEnvironment(id)
      await loadEnvs()
    }

    const formatTime = (ts) => ts ? new Date(ts).toLocaleString() : '-'

    onMounted(() => { loadEnvs(); loadRules() })
    return { environments, allRules, loading, saving, dialogVisible, associateVisible, currentEnv, selectedRuleIds, form, showCreateDialog, createEnv, changeMode, viewDetail, deleteEnv, modeTagType, modeLabel, formatTime, getRuleCountForEnv, showAssociateDialog, saveAssociation, authStore }
  }
}
</script>

<style scoped>
.page-header { display: flex; justify-content: space-between; align-items: center; }
.page-header h2 { font-size: 20px; font-weight: 600; color: #303133; }
</style>
