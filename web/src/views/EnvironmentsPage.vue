<template>
  <div class="environments-page">
    <div class="page-header">
      <h2>{{ $t('environments.title') }}</h2>
      <el-button type="primary" @click="showCreateDialog" v-if="authStore.canWriteEnvironment">
        <el-icon><Plus /></el-icon> {{ $t('environments.newEnv') }}
      </el-button>
    </div>

    <el-card shadow="never" style="margin-top: 16px" v-loading="loading">
      <el-table :data="environments" stripe size="small" :empty-text="$t('environments.noEnvs')">
        <el-table-column prop="name" :label="$t('environments.name')" min-width="150" />
        <el-table-column :label="$t('environments.mode')" width="160" align="center">
          <template #default="{ row }">
            <el-tag :type="modeTagType(row.mode) || undefined" effect="dark">
              {{ modeLabel(row) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="Agents" width="80" align="center">
          <template #default="{ row }">{{ (row.agentIds || []).length }}</template>
        </el-table-column>
        <el-table-column :label="$t('environments.associatedRules')" width="100" align="center">
          <template #default="{ row }">
            <el-link type="primary" @click="showAssociateDialog(row)">{{ getRuleCountForEnv(row.name) }}</el-link>
          </template>
        </el-table-column>
        <el-table-column :label="$t('environments.createdAt')" width="180">
          <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column :label="$t('environments.actions')" width="240" fixed="right">
          <template #default="{ row }">
            <el-button size="small" text @click="viewDetail(row.id)">{{ $t('environments.detail') }}</el-button>
            <el-dropdown @command="(cmd) => changeMode(row, cmd)" style="margin-left: 8px" v-if="authStore.canWriteEnvironment">
              <el-button size="small" text>{{ $t('environments.switchMode') }} <el-icon><ArrowDown /></el-icon></el-button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="stub" :disabled="row.mode === 'stub'">{{ $t('environments.modes.stub') }}</el-dropdown-item>
                  <el-dropdown-item command="passthrough" :disabled="row.mode === 'passthrough'">{{ $t('environments.modes.passthrough') }}</el-dropdown-item>
                  <el-dropdown-item command="record" :disabled="row.mode === 'record'">{{ $t('environments.modes.record') }}</el-dropdown-item>
                  <el-dropdown-item command="record-and-stub" :disabled="row.mode === 'record-and-stub'">Record+Stub</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
            <el-popconfirm :title="$t('environments.confirmDelete')" @confirm="deleteEnv(row.id)" v-if="authStore.canWriteEnvironment">
              <template #reference>
                <el-button size="small" text type="danger">{{ $t('environments.delete') }}</el-button>
              </template>
            </el-popconfirm>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="dialogVisible" :title="$t('environments.newEnv')" width="500px">
      <el-form :model="form" label-width="80px">
        <el-form-item :label="$t('environments.name')" required>
          <el-input v-model="form.name" :placeholder="$t('environments.namePlaceholder')" />
        </el-form-item>
        <el-form-item :label="$t('environments.defaultMode')">
          <el-select v-model="form.mode" style="width: 100%">
            <el-option :label="$t('environments.modes.stub')" value="stub" />
            <el-option :label="$t('environments.modes.passthrough')" value="passthrough" />
            <el-option :label="$t('environments.modes.record')" value="record" />
            <el-option label="Record+Stub" value="record-and-stub" />
          </el-select>
        </el-form-item>
        <el-form-item :label="$t('environments.envVariables')">
          <div style="width: 100%">
            <div v-for="(v, idx) in form.variables" :key="idx" style="display: flex; gap: 8px; margin-bottom: 8px">
              <el-input v-model="v.key" :placeholder="$t('environments.variableName')" style="flex: 1" />
              <el-input v-model="v.value" :placeholder="$t('environments.value')" style="flex: 1" />
              <el-button text type="danger" @click="removeCreateVariable(idx)">
                <el-icon><Delete /></el-icon>
              </el-button>
            </div>
            <el-button size="small" type="primary" plain @click="addCreateVariable">
              <el-icon><Plus /></el-icon> {{ $t('environments.addVariable') }}
            </el-button>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">{{ $t('environments.cancel') }}</el-button>
        <el-button type="primary" @click="createEnv">{{ $t('environments.create') }}</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="associateVisible" :title="`${$t('environments.associateRules')} - ${currentEnv.name || ''}`" width="600px">
      <el-form label-width="80px">
        <el-form-item :label="$t('environments.currentEnv')">
          <el-tag>{{ currentEnv.name }}</el-tag>
        </el-form-item>
        <el-form-item :label="$t('environments.associateRules')">
          <el-select v-model="selectedRuleIds" multiple filterable :placeholder="$t('environments.selectRulesPlaceholder')" style="width: 100%">
            <el-option v-for="r in allRules" :key="r.id" :label="r.name" :value="r.id" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="associateVisible = false">{{ $t('environments.cancel') }}</el-button>
        <el-button type="primary" @click="saveAssociation" :loading="saving" v-if="authStore.canWriteEnvironment">{{ $t('environments.saveAssociation') }}</el-button>
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
    const form = reactive({ name: '', mode: 'record-and-stub', variables: [] })

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

    function addCreateVariable() {
      form.variables.push({ key: '', value: '' })
    }

    function removeCreateVariable(index) {
      form.variables.splice(index, 1)
    }

    function showCreateDialog() {
      form.name = ''
      form.mode = 'record-and-stub'
      form.variables = []
      dialogVisible.value = true
    }

    async function createEnv() {
      const variables = {}
      for (const item of form.variables) {
        if (item.key.trim()) {
          variables[item.key.trim()] = item.value
        }
      }
      const data = { name: form.name, mode: form.mode }
      if (Object.keys(variables).length > 0) data.variables = variables
      const res = await api.createEnvironment(data)
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
    return { environments, allRules, loading, saving, dialogVisible, associateVisible, currentEnv, selectedRuleIds, form, showCreateDialog, createEnv, addCreateVariable, removeCreateVariable, changeMode, viewDetail, deleteEnv, modeTagType, modeLabel, formatTime, getRuleCountForEnv, showAssociateDialog, saveAssociation, authStore }
  }
}
</script>

<style scoped>
.page-header { display: flex; justify-content: space-between; align-items: center; }
</style>
