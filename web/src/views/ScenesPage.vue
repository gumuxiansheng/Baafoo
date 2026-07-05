<template>
  <div class="scenes-page">
    <div class="page-header">
      <h2>{{ $t('scenes.title') }}</h2>
      <el-button type="primary" @click="showCreateDialog" v-if="authStore.canWriteScene">
        <el-icon><Plus /></el-icon> {{ $t('scenes.newScene') }}
      </el-button>
    </div>

    <el-card shadow="never" style="margin-top: 16px" v-loading="loading">
      <el-table :data="scenes" stripe size="small" :empty-text="$t('scenes.noScenes')">
        <el-table-column prop="name" :label="$t('scenes.name')" min-width="160" />
        <el-table-column prop="description" :label="$t('scenes.description')" min-width="180" show-overflow-tooltip />
        <el-table-column prop="itemIds" :label="$t('scenes.ruleCount')" width="110" align="center">
          <template #default="{ row }">{{ (row.itemIds || []).length }}</template>
        </el-table-column>
        <el-table-column :label="$t('scenes.status')" width="90" align="center">
          <template #default="{ row }">
            <el-switch v-model="row.active" @change="toggleScene(row)" size="small" :disabled="!authStore.canWriteScene" />
          </template>
        </el-table-column>
        <el-table-column :label="$t('scenes.effectiveEnvs')" min-width="120">
          <template #default="{ row }">
            <template v-if="row.environments && row.environments.length > 0">
              <el-tag v-for="env in row.environments" :key="env" size="small" type="info" style="margin-right: 4px">{{ env }}</el-tag>
            </template>
            <el-tag v-else size="small" type="warning">{{ $t('scenes.unassociated') }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="$t('scenes.createdAt')" width="170">
          <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column :label="$t('scenes.actions')" width="160">
          <template #default="{ row }">
            <el-button size="small" text type="primary" @click="showEditDialog(row)" v-if="authStore.canWriteScene">{{ $t('scenes.edit') }}</el-button>
            <el-popconfirm :title="$t('scenes.confirmDelete')" @confirm="deleteSceneItem(row.id)" v-if="authStore.canWriteScene">
              <template #reference>
                <el-button size="small" text type="danger">{{ $t('scenes.delete') }}</el-button>
              </template>
            </el-popconfirm>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="dialogVisible" :title="isEdit ? $t('scenes.editScene') : $t('scenes.newScene')" width="600px">
      <el-form :model="form" label-width="80px">
        <el-form-item :label="$t('scenes.name')" required>
          <el-input v-model="form.name" :placeholder="$t('scenes.namePlaceholder')" />
        </el-form-item>
        <el-form-item :label="$t('scenes.description')">
          <el-input v-model="form.description" type="textarea" rows="2" />
        </el-form-item>
        <el-form-item :label="$t('scenes.associateRules')">
          <el-select v-model="form.itemIds" multiple filterable :placeholder="$t('scenes.selectRules')" style="width: 100%">
            <el-option v-for="r in allRules" :key="r.id" :label="r.name" :value="r.id" />
          </el-select>
        </el-form-item>
        <el-form-item :label="$t('scenes.effectiveEnv')">
          <el-select v-model="form.environments" multiple filterable allow-create default-first-option :placeholder="$t('scenes.selectEnvPlaceholder')" style="width: 100%">
            <el-option v-for="env in allEnvironments" :key="env.name" :label="env.name" :value="env.name" />
          </el-select>
          <div style="font-size: 12px; color: var(--bf-text-muted); margin-top: 4px">{{ $t('scenes.envNoEffect') }}</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">{{ $t('scenes.cancel') }}</el-button>
        <el-button type="primary" @click="isEdit ? updateScene() : createScene()" :loading="saving">
          {{ isEdit ? $t('scenes.save') : $t('scenes.create') }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script>
import { ref, reactive, onMounted } from 'vue'
import { useAuthStore } from '@/store'
import api from '@/api'

export default {
  name: 'ScenesPage',
  setup() {
    const authStore = useAuthStore()
    const scenes = ref([])
    const allRules = ref([])
    const allEnvironments = ref([])
    const loading = ref(false)
    const saving = ref(false)
    const dialogVisible = ref(false)
    const isEdit = ref(false)
    const editId = ref(null)
    const form = reactive({ name: '', description: '', itemIds: [], environments: [], active: false })

    async function loadScenes() {
      loading.value = true
      const res = await api.getScenes()
      if (res.success) scenes.value = res.data
      loading.value = false
    }

    async function loadRules() {
      const res = await api.getRules()
      if (res.success) allRules.value = res.data
    }

    async function loadEnvironments() {
      const res = await api.getEnvironments()
      if (res.success) allEnvironments.value = res.data
    }

    function showCreateDialog() {
      isEdit.value = false
      editId.value = null
      form.name = ''
      form.description = ''
      form.itemIds = []
      form.environments = []
      form.active = false
      dialogVisible.value = true
    }

    function showEditDialog(scene) {
      isEdit.value = true
      editId.value = scene.id
      form.name = scene.name || ''
      form.description = scene.description || ''
      form.itemIds = scene.itemIds ? [...scene.itemIds] : []
      form.environments = scene.environments ? [...scene.environments] : []
      form.active = scene.active !== false
      dialogVisible.value = true
    }

    async function createScene() {
      if (!form.name) return
      saving.value = true
      const res = await api.createScene({ name: form.name, description: form.description, active: false, itemIds: form.itemIds || [], environments: form.environments || [] })
      saving.value = false
      if (res.success) {
        dialogVisible.value = false
        await loadScenes()
      }
    }

    async function updateScene() {
      saving.value = true
      const res = await api.updateScene(editId.value, {
        name: form.name,
        description: form.description,
        itemIds: form.itemIds || [],
        environments: form.environments || [],
        active: form.active
      })
      saving.value = false
      if (res.success) {
        dialogVisible.value = false
        await loadScenes()
      }
    }

    async function toggleScene(scene) {
      await api.updateScene(scene.id, { active: scene.active, name: scene.name, description: scene.description, itemIds: scene.itemIds || [], environments: scene.environments || [] })
    }

    async function deleteSceneItem(id) {
      await api.deleteScene(id)
      await loadScenes()
    }

    const formatTime = (ts) => ts ? new Date(ts).toLocaleString() : '-'

    onMounted(() => { loadScenes(); loadRules(); loadEnvironments() })
    return { scenes, allRules, allEnvironments, loading, saving, dialogVisible, isEdit, form, showCreateDialog, showEditDialog, createScene, updateScene, toggleScene, deleteSceneItem, formatTime, authStore }
  }
}
</script>

<style scoped>
.page-header { display: flex; justify-content: space-between; align-items: center; }
</style>
