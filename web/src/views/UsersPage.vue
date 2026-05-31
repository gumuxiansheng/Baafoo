<template>
  <div class="users-page">
    <div class="page-header">
      <h2>用户管理</h2>
      <div class="header-actions">
        <el-button type="success" @click="showImportDialog = true">
          <el-icon><Upload /></el-icon> CSV导入
        </el-button>
        <el-button type="primary" @click="showAddDialog = true">
          <el-icon><Plus /></el-icon> 新增用户
        </el-button>
      </div>
    </div>

    <el-table :data="users" v-loading="loading" stripe>
      <el-table-column prop="username" label="用户名" width="140" />
      <el-table-column prop="displayName" label="显示名称" width="140" />
      <el-table-column prop="email" label="邮箱" width="200" />
      <el-table-column prop="role" label="角色" width="120">
        <template #default="{ row }">
          <el-tag :type="roleTagType(row.role)" size="small">{{ roleLabel(row.role) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="API Key" width="100">
        <template #default="{ row }">
          <el-tag v-if="row.apiKey" type="success" size="small">有</el-tag>
          <el-tag v-else type="info" size="small">无</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="最后登录" width="180">
        <template #default="{ row }">
          {{ row.lastLoginAt ? new Date(row.lastLoginAt).toLocaleString() : '-' }}
        </template>
      </el-table-column>
      <el-table-column label="操作" fixed="right" width="280">
        <template #default="{ row }">
          <el-dropdown trigger="click" @command="(cmd) => handleRoleChange(row, cmd)">
            <el-button size="small" type="warning" plain>
              角色 <el-icon><ArrowDown /></el-icon>
            </el-button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="admin">管理员</el-dropdown-item>
                <el-dropdown-item command="developer">开发</el-dropdown-item>
                <el-dropdown-item command="tester">测试</el-dropdown-item>
                <el-dropdown-item command="guest">游客</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
          <el-button
            v-if="!row.apiKey"
            size="small"
            type="success"
            plain
            @click="handleGenerateApiKey(row)"
          >
            生成Key
          </el-button>
          <el-button
            v-else
            size="small"
            type="info"
            plain
            @click="handleRevokeApiKey(row)"
          >
            吊销Key
          </el-button>
          <el-button
            size="small"
            type="danger"
            plain
            :disabled="row.username === currentUsername"
            @click="handleDelete(row)"
          >
            删除
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 新增用户 -->
    <el-dialog v-model="showAddDialog" title="新增用户" width="520px" destroy-on-close>
      <el-form :model="addForm" :rules="addRules" ref="addFormRef" label-width="80px" autocomplete="off">
        <el-form-item label="用户名" prop="username">
          <el-input v-model="addForm.username" placeholder="登录用户名" autocomplete="off" />
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input v-model="addForm.password" type="password" placeholder="8-64位，含大小写字母、数字、特殊字符" show-password autocomplete="new-password" />
        </el-form-item>
        <el-form-item label="显示名称" prop="displayName">
          <el-input v-model="addForm.displayName" placeholder="可选，默认同用户名" />
        </el-form-item>
        <el-form-item label="邮箱" prop="email">
          <el-input v-model="addForm.email" placeholder="可选" />
        </el-form-item>
        <el-form-item label="角色" prop="role">
          <el-select v-model="addForm.role" placeholder="请选择角色" style="width: 100%">
            <el-option label="管理员 (Admin)" value="admin" />
            <el-option label="开发 (Developer)" value="developer" />
            <el-option label="测试 (Tester)" value="tester" />
            <el-option label="游客 (Guest)" value="guest" />
          </el-select>
        </el-form-item>
      </el-form>
      <div v-if="addForm.role" class="permission-preview">
        <p class="preview-title">角色权限预览:</p>
        <el-descriptions :column="1" size="small" border>
          <el-descriptions-item label="规则查看">✅</el-descriptions-item>
          <el-descriptions-item label="规则新建/编辑">
            {{ canRole(addForm.role, 'rule') ? '✅' : '❌' }}
          </el-descriptions-item>
          <el-descriptions-item label="场景集新建/编辑">
            {{ canRole(addForm.role, 'scene') ? '✅' : '❌' }}
          </el-descriptions-item>
          <el-descriptions-item label="环境配置">
            {{ canRole(addForm.role, 'environment') ? '✅' : '❌' }}
          </el-descriptions-item>
          <el-descriptions-item label="用户管理">
            {{ addForm.role === 'admin' ? '✅' : '❌' }}
          </el-descriptions-item>
        </el-descriptions>
      </div>
      <template #footer>
        <el-button @click="showAddDialog = false">取消</el-button>
        <el-button type="primary" @click="handleAddUser" :loading="addLoading">确认</el-button>
      </template>
    </el-dialog>

    <!-- CSV导入 -->
    <el-dialog v-model="showImportDialog" title="CSV批量导入用户" width="640px" destroy-on-close>
      <el-alert
        type="info"
        :closable="false"
        style="margin-bottom: 16px"
      >
        <template #title>
          CSV格式要求：第一行为标题，必须包含"用户名"和"密码"列，可选"显示名称"、"邮箱"、"角色代码"
        </template>
      </el-alert>
      <div class="csv-template">
        <p class="preview-title">CSV模板:</p>
        <pre class="csv-sample">用户名,密码,显示名称,邮箱,角色代码
zhangsan,Zs@2026!,张三,zhangsan@example.com,developer
lisi,Ls#2026!,李四,lisi@example.com,tester</pre>
      </div>
      <el-upload
        ref="csvUploadRef"
        :auto-upload="false"
        :limit="1"
        accept=".csv"
        :on-change="handleCsvFileChange"
        :on-remove="() => csvFile = null"
      >
        <el-button type="primary" plain>选择CSV文件</el-button>
      </el-upload>
      <div v-if="importResult" class="import-result">
        <el-descriptions :column="3" size="small" border style="margin-top: 16px">
          <el-descriptions-item label="成功创建">
            <span style="color: #67c23a; font-weight: bold">{{ importResult.created }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="跳过(已存在)">
            <span style="color: #e6a23c; font-weight: bold">{{ importResult.skipped }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="失败">
            <span style="color: #f56c6c; font-weight: bold">{{ importResult.failed }}</span>
          </el-descriptions-item>
        </el-descriptions>
        <div v-if="importResult.errors && importResult.errors.length" style="margin-top: 8px">
          <p class="preview-title">错误详情:</p>
          <ul class="error-list">
            <li v-for="(err, idx) in importResult.errors" :key="idx">{{ err }}</li>
          </ul>
        </div>
      </div>
      <template #footer>
        <el-button @click="showImportDialog = false; importResult = null">关闭</el-button>
        <el-button type="primary" @click="handleCsvImport" :loading="importLoading" :disabled="!csvFile">
          导入
        </el-button>
      </template>
    </el-dialog>

    <!-- API Key 展示 -->
    <el-dialog v-model="showApiKeyDialog" title="API Key 已生成" width="480px">
      <el-alert
        type="warning"
        title="请立即复制并保存此 API Key，关闭后将无法再次查看明文"
        :closable="false"
        show-icon
        style="margin-bottom: 16px"
      />
      <el-input :model-value="generatedApiKey" readonly>
        <template #append>
          <el-button @click="copyApiKey">复制</el-button>
        </template>
      </el-input>
      <template #footer>
        <el-button type="primary" @click="showApiKeyDialog = false">我已保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script>
import { ref, reactive, onMounted, computed } from 'vue'
import { useAuthStore } from '@/store'
import api from '@/api'
import { ElMessage, ElMessageBox } from 'element-plus'

export default {
  name: 'UsersPage',
  setup() {
    const authStore = useAuthStore()
    const users = ref([])
    const loading = ref(false)
    const showAddDialog = ref(false)
    const addLoading = ref(false)
    const addFormRef = ref(null)
    const showApiKeyDialog = ref(false)
    const generatedApiKey = ref('')
    const showImportDialog = ref(false)
    const importLoading = ref(false)
    const importResult = ref(null)
    const csvFile = ref(null)
    const csvUploadRef = ref(null)

    const currentUsername = computed(() => authStore.username)

    const addForm = reactive({
      username: '',
      password: '',
      displayName: '',
      email: '',
      role: 'developer'
    })

    const validateStrongPassword = (rule, value, callback) => {
      if (!value) {
        callback(new Error('请输入密码'))
      } else if (value.length < 8) {
        callback(new Error('密码长度不能少于8位'))
      } else if (value.length > 64) {
        callback(new Error('密码长度不能超过64位'))
      } else if (!/[A-Z]/.test(value)) {
        callback(new Error('密码必须包含至少一个大写字母'))
      } else if (!/[a-z]/.test(value)) {
        callback(new Error('密码必须包含至少一个小写字母'))
      } else if (!/[0-9]/.test(value)) {
        callback(new Error('密码必须包含至少一个数字'))
      } else if (!/[^A-Za-z0-9]/.test(value)) {
        callback(new Error('密码必须包含至少一个特殊字符'))
      } else {
        callback()
      }
    }

    const addRules = {
      username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
      password: [{ required: true, validator: validateStrongPassword, trigger: 'blur' }],
      role: [{ required: true, message: '请选择角色', trigger: 'change' }]
    }

    const roleLabel = (role) => {
      const map = { admin: '管理员', developer: '开发', tester: '测试', guest: '游客' }
      return map[role] || role
    }

    const roleTagType = (role) => {
      const map = { admin: 'danger', developer: '', tester: 'warning', guest: 'info' }
      return map[role] || 'info'
    }

    const canRole = (role, resource) => {
      if (role === 'admin') return true
      if (resource === 'rule') return role === 'developer'
      if (resource === 'scene') return role === 'developer' || role === 'tester'
      if (resource === 'environment') return false
      return false
    }

    const fetchUsers = async () => {
      loading.value = true
      try {
        const res = await api.getUsers()
        if (res.success) users.value = res.data
      } finally {
        loading.value = false
      }
    }

    const handleAddUser = async () => {
      if (!addFormRef.value) return
      await addFormRef.value.validate(async (valid) => {
        if (!valid) return
        addLoading.value = true
        try {
          const res = await api.createUser(addForm)
          if (res.success) {
            ElMessage.success('用户创建成功')
            showAddDialog.value = false
            addForm.username = ''
            addForm.password = ''
            addForm.displayName = ''
            addForm.email = ''
            addForm.role = 'developer'
            await fetchUsers()
          } else {
            ElMessage.error(res.message || '创建失败')
          }
        } finally {
          addLoading.value = false
        }
      })
    }

    const handleRoleChange = async (row, newRole) => {
      if (row.role === newRole) return
      try {
        await ElMessageBox.confirm(
          `确认将用户 "${row.username}" 的角色从 ${roleLabel(row.role)} 修改为 ${roleLabel(newRole)}?`,
          '修改角色',
          { type: 'warning' }
        )
        const res = await api.updateUserRole(row.username, newRole)
        if (res.success) {
          ElMessage.success('角色修改成功')
          await fetchUsers()
        } else {
          ElMessage.error(res.message || '修改失败')
        }
      } catch (e) {
        // cancelled
      }
    }

    const handleGenerateApiKey = async (row) => {
      try {
        const res = await api.generateApiKey(row.username)
        if (res.success && res.data) {
          generatedApiKey.value = res.data.apiKey
          showApiKeyDialog.value = true
          await fetchUsers()
        }
      } catch (e) {
        ElMessage.error('生成 API Key 失败')
      }
    }

    const handleRevokeApiKey = async (row) => {
      try {
        await ElMessageBox.confirm(
          `确认吊销用户 "${row.username}" 的 API Key?`,
          '吊销 API Key',
          { type: 'warning' }
        )
        const res = await api.revokeApiKey(row.username)
        if (res.success) {
          ElMessage.success('API Key 已吊销')
          await fetchUsers()
        }
      } catch (e) {
        // cancelled
      }
    }

    const handleDelete = async (row) => {
      try {
        await ElMessageBox.confirm(
          `确认删除用户 "${row.username}"? 此操作不可恢复。`,
          '删除用户',
          { type: 'danger' }
        )
        const res = await api.deleteUser(row.username)
        if (res.success) {
          ElMessage.success('用户已删除')
          await fetchUsers()
        } else {
          ElMessage.error(res.message || '删除失败')
        }
      } catch (e) {
        // cancelled
      }
    }

    const copyApiKey = () => {
      navigator.clipboard.writeText(generatedApiKey.value).then(() => {
        ElMessage.success('已复制到剪贴板')
      }).catch(() => {
        ElMessage.warning('复制失败，请手动复制')
      })
    }

    const handleCsvFileChange = (file) => {
      csvFile.value = file.raw
    }

    const handleCsvImport = async () => {
      if (!csvFile.value) return
      importLoading.value = true
      importResult.value = null
      try {
        const text = await csvFile.value.text()
        const res = await api.importUsersCsv(text)
        if (res.success && res.data) {
          importResult.value = res.data
          if (res.data.created > 0) {
            await fetchUsers()
          }
          ElMessage.success(`导入完成: 成功${res.data.created}条, 跳过${res.data.skipped}条, 失败${res.data.failed}条`)
        } else {
          ElMessage.error(res.message || '导入失败')
        }
      } catch (e) {
        ElMessage.error('导入失败: ' + (e.message || '未知错误'))
      } finally {
        importLoading.value = false
      }
    }

    onMounted(fetchUsers)

    return {
      users, loading, showAddDialog, addLoading, addFormRef, addForm, addRules,
      showApiKeyDialog, generatedApiKey, currentUsername,
      showImportDialog, importLoading, importResult, csvFile, csvUploadRef,
      roleLabel, roleTagType, canRole,
      fetchUsers, handleAddUser, handleRoleChange,
      handleGenerateApiKey, handleRevokeApiKey, handleDelete, copyApiKey,
      handleCsvFileChange, handleCsvImport
    }
  }
}
</script>

<style scoped>
.users-page { padding: 0; }
.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
}
.page-header h2 { margin: 0; font-size: 20px; color: #303133; }
.header-actions { display: flex; gap: 8px; }
.permission-preview { margin-top: 16px; padding: 12px; background: #f5f7fa; border-radius: 6px; }
.preview-title { font-size: 13px; color: #606266; margin-bottom: 8px; font-weight: 500; }
.csv-template { margin-bottom: 16px; }
.csv-sample {
  background: #f5f7fa; padding: 10px; border-radius: 4px;
  font-size: 12px; line-height: 1.6; color: #606266;
  overflow-x: auto; white-space: pre;
}
.import-result { margin-top: 12px; }
.error-list { font-size: 12px; color: #f56c6c; padding-left: 16px; margin: 4px 0; }
.error-list li { margin-bottom: 2px; }
</style>
