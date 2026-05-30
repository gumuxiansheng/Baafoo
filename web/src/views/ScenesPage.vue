<template>
  <div class="scenes-page">
    <div class="page-header">
      <h2>场景集管理</h2>
      <el-button type="primary" @click="showCreateDialog">
        <el-icon><Plus /></el-icon> 新建场景集
      </el-button>
    </div>

    <el-card shadow="never" style="margin-top: 16px" v-loading="loading">
      <el-table :data="scenes" stripe size="small" empty-text="暂无场景集">
        <el-table-column prop="name" label="名称" min-width="160" />
        <el-table-column prop="description" label="描述" min-width="180" show-overflow-tooltip />
        <el-table-column prop="itemIds" label="关联规则数" width="110" align="center">
          <template #default="{ row }">{{ (row.itemIds || []).length }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90" align="center">
          <template #default="{ row }">
            <el-switch v-model="row.active" @change="toggleScene(row)" size="small" />
          </template>
        </el-table-column>
        <el-table-column label="创建时间" width="170">
          <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="160">
          <template #default="{ row }">
            <el-button size="small" text type="primary" @click="showEditDialog(row)">编辑</el-button>
            <el-popconfirm title="确定删除？" @confirm="deleteSceneItem(row.id)">
              <template #reference>
                <el-button size="small" text type="danger">删除</el-button>
              </template>
            </el-popconfirm>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="dialogVisible" :title="isEdit ? '编辑场景集' : '新建场景集'" width="600px">
      <el-form :model="form" label-width="80px">
        <el-form-item label="名称" required>
          <el-input v-model="form.name" placeholder="如: 用户模块测试场景" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" rows="2" />
        </el-form-item>
        <el-form-item label="关联规则">
          <el-select v-model="form.itemIds" multiple filterable placeholder="选择规则" style="width: 100%">
            <el-option v-for="r in allRules" :key="r.id" :label="r.name" :value="r.id" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="isEdit ? updateScene() : createScene()" :loading="saving">
          {{ isEdit ? '保存' : '创建' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script>
import { ref, reactive, onMounted } from 'vue'
import api from '@/api'

export default {
  name: 'ScenesPage',
  setup() {
    const scenes = ref([])
    const allRules = ref([])
    const loading = ref(false)
    const saving = ref(false)
    const dialogVisible = ref(false)
    const isEdit = ref(false)
    const editId = ref(null)
    const form = reactive({ name: '', description: '', itemIds: [], active: false })

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

    function showCreateDialog() {
      isEdit.value = false
      editId.value = null
      form.name = ''
      form.description = ''
      form.itemIds = []
      form.active = false
      dialogVisible.value = true
    }

    function showEditDialog(scene) {
      isEdit.value = true
      editId.value = scene.id
      form.name = scene.name || ''
      form.description = scene.description || ''
      form.itemIds = scene.itemIds ? [...scene.itemIds] : []
      form.active = scene.active !== false
      dialogVisible.value = true
    }

    async function createScene() {
      if (!form.name) return
      saving.value = true
      const res = await api.createScene({ name: form.name, description: form.description, active: false, itemIds: form.itemIds || [] })
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
        active: form.active
      })
      saving.value = false
      if (res.success) {
        dialogVisible.value = false
        await loadScenes()
      }
    }

    async function toggleScene(scene) {
      await api.updateScene(scene.id, { active: scene.active, name: scene.name, description: scene.description, itemIds: scene.itemIds || [] })
    }

    async function deleteSceneItem(id) {
      await api.deleteScene(id)
      await loadScenes()
    }

    const formatTime = (ts) => ts ? new Date(ts).toLocaleString() : '-'

    onMounted(() => { loadScenes(); loadRules() })
    return { scenes, allRules, loading, saving, dialogVisible, isEdit, form, showCreateDialog, showEditDialog, createScene, updateScene, toggleScene, deleteSceneItem, formatTime }
  }
}
</script>

<style scoped>
.page-header { display: flex; justify-content: space-between; align-items: center; }
.page-header h2 { font-size: 20px; font-weight: 600; color: #303133; }
</style>
