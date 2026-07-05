<template>
  <div class="users-page">
    <div class="page-header">
      <h2>{{ $t('users.title') }}</h2>
      <div class="header-actions">
        <el-button type="success" @click="showImportDialog = true">
          <el-icon><Upload /></el-icon> {{ $t('users.csvImport') }}
        </el-button>
        <el-button type="primary" @click="showAddDialog = true">
          <el-icon><Plus /></el-icon> {{ $t('users.addUser') }}
        </el-button>
      </div>
    </div>

    <el-table :data="users" v-loading="loading" stripe>
      <el-table-column prop="username" :label="$t('users.columns.username')" width="140" />
      <el-table-column prop="displayName" :label="$t('users.columns.displayName')" width="140" />
      <el-table-column prop="email" :label="$t('users.columns.email')" width="200" />
      <el-table-column prop="role" :label="$t('users.columns.role')" width="120">
        <template #default="{ row }">
          <el-tag :type="roleTagType(row.role)" size="small">{{ $t(roleLabel(row.role)) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column :label="$t('users.columns.apiKey')" width="100">
        <template #default="{ row }">
          <el-tag v-if="row.apiKey" type="success" size="small">{{ $t('users.hasKey') }}</el-tag>
          <el-tag v-else type="info" size="small">{{ $t('users.noKey') }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column :label="$t('users.columns.lastLogin')" width="180">
        <template #default="{ row }">
          {{ row.lastLoginAt ? new Date(row.lastLoginAt).toLocaleString() : '-' }}
        </template>
      </el-table-column>
      <el-table-column :label="$t('users.columns.actions')" fixed="right" width="280">
        <template #default="{ row }">
          <el-dropdown trigger="click" @command="(cmd) => handleRoleChange(row, cmd)">
            <el-button size="small" type="warning" plain>
              {{ $t('users.changeRole') }} <el-icon><ArrowDown /></el-icon>
            </el-button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="admin">{{ $t('users.roles.admin') }}</el-dropdown-item>
                <el-dropdown-item command="developer">{{ $t('users.roles.developer') }}</el-dropdown-item>
                <el-dropdown-item command="tester">{{ $t('users.roles.tester') }}</el-dropdown-item>
                <el-dropdown-item command="guest">{{ $t('users.roles.guest') }}</el-dropdown-item>
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
            {{ $t('users.generateKey') }}
          </el-button>
          <el-button
            v-else
            size="small"
            type="info"
            plain
            @click="handleRevokeApiKey(row)"
          >
            {{ $t('users.revokeKey') }}
          </el-button>
          <el-button
            size="small"
            type="danger"
            plain
            :disabled="row.username === currentUsername"
            @click="handleDelete(row)"
          >
            {{ $t('users.delete') }}
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- Add User -->
    <el-dialog v-model="showAddDialog" :title="$t('users.addDialogTitle')" width="520px" destroy-on-close>
      <el-form :model="addForm" :rules="addRules" ref="addFormRef" label-width="80px" autocomplete="off">
        <el-form-item :label="$t('users.columns.username')" prop="username">
          <el-input v-model="addForm.username" :placeholder="$t('users.usernamePlaceholder')" autocomplete="off" />
        </el-form-item>
        <el-form-item :label="$t('login.passwordLabel')" prop="password">
          <el-input v-model="addForm.password" type="password" :placeholder="$t('users.passwordPlaceholder')" show-password autocomplete="new-password" />
        </el-form-item>
        <el-form-item :label="$t('users.columns.displayName')" prop="displayName">
          <el-input v-model="addForm.displayName" :placeholder="$t('users.displayNamePlaceholder')" />
        </el-form-item>
        <el-form-item :label="$t('users.columns.email')" prop="email">
          <el-input v-model="addForm.email" :placeholder="$t('users.emailPlaceholder')" />
        </el-form-item>
        <el-form-item :label="$t('users.columns.role')" prop="role">
          <el-select v-model="addForm.role" :placeholder="$t('users.rolePlaceholder')" style="width: 100%">
            <el-option :label="$t('users.roles.admin') + ' (Admin)'" value="admin" />
            <el-option :label="$t('users.roles.developer') + ' (Developer)'" value="developer" />
            <el-option :label="$t('users.roles.tester') + ' (Tester)'" value="tester" />
            <el-option :label="$t('users.roles.guest') + ' (Guest)'" value="guest" />
          </el-select>
        </el-form-item>
      </el-form>
      <div v-if="addForm.role" class="permission-preview">
        <p class="preview-title">{{ $t('users.rolePreview') }}</p>
        <el-descriptions :column="1" size="small" border>
          <el-descriptions-item :label="$t('users.ruleView')">✅</el-descriptions-item>
          <el-descriptions-item :label="$t('users.ruleWrite')">
            {{ canRole(addForm.role, 'rule') ? '✅' : '❌' }}
          </el-descriptions-item>
          <el-descriptions-item :label="$t('users.sceneWrite')">
            {{ canRole(addForm.role, 'scene') ? '✅' : '❌' }}
          </el-descriptions-item>
          <el-descriptions-item :label="$t('users.envConfig')">
            {{ canRole(addForm.role, 'environment') ? '✅' : '❌' }}
          </el-descriptions-item>
          <el-descriptions-item :label="$t('users.userMgmt')">
            {{ addForm.role === 'admin' ? '✅' : '❌' }}
          </el-descriptions-item>
        </el-descriptions>
      </div>
      <template #footer>
        <el-button @click="showAddDialog = false">{{ $t('users.cancel') }}</el-button>
        <el-button type="primary" @click="handleAddUser" :loading="addLoading">{{ $t('users.confirm') }}</el-button>
      </template>
    </el-dialog>

    <!-- CSV Import -->
    <el-dialog v-model="showImportDialog" :title="$t('users.importTitle')" width="640px" destroy-on-close>
      <el-alert
        type="info"
        :closable="false"
        style="margin-bottom: 16px"
      >
        <template #title>
          {{ $t('users.csvFormatHint') }}
        </template>
      </el-alert>
      <div class="csv-template">
        <p class="preview-title">{{ $t('users.csvTemplate') }}</p>
        <pre class="csv-sample">{{ $t('users.csvTemplateContent') }}</pre>
      </div>
      <el-upload
        ref="csvUploadRef"
        :auto-upload="false"
        :limit="1"
        accept=".csv"
        :on-change="handleCsvFileChange"
        :on-remove="() => csvFile = null"
      >
        <el-button type="primary" plain>{{ $t('users.selectFile') }}</el-button>
      </el-upload>
      <div v-if="importResult" class="import-result">
        <el-descriptions :column="3" size="small" border style="margin-top: 16px">
          <el-descriptions-item :label="$t('users.created')">
            <span style="color: var(--bf-success); font-weight: bold">{{ importResult.created }}</span>
          </el-descriptions-item>
          <el-descriptions-item :label="$t('users.skipped')">
            <span style="color: var(--bf-warning); font-weight: bold">{{ importResult.skipped }}</span>
          </el-descriptions-item>
          <el-descriptions-item :label="$t('users.failed')">
            <span style="color: var(--bf-danger); font-weight: bold">{{ importResult.failed }}</span>
          </el-descriptions-item>
        </el-descriptions>
        <div v-if="importResult.errors && importResult.errors.length" style="margin-top: 8px">
          <p class="preview-title">{{ $t('users.errorDetails') }}</p>
          <ul class="error-list">
            <li v-for="(err, idx) in importResult.errors" :key="idx">{{ err }}</li>
          </ul>
        </div>
      </div>
      <template #footer>
        <el-button @click="showImportDialog = false; importResult = null">{{ $t('users.close') }}</el-button>
        <el-button type="primary" @click="handleCsvImport" :loading="importLoading" :disabled="!csvFile">
          {{ $t('users.import') }}
        </el-button>
      </template>
    </el-dialog>

    <!-- API Key Display -->
    <el-dialog v-model="showApiKeyDialog" :title="$t('users.apiKeyTitle')" width="480px">
      <el-alert
        type="warning"
        :title="$t('users.apiKeyHint')"
        :closable="false"
        show-icon
        style="margin-bottom: 16px"
      />
      <el-input :model-value="generatedApiKey" readonly>
        <template #append>
          <el-button @click="copyApiKey">{{ $t('users.copy') }}</el-button>
        </template>
      </el-input>
      <template #footer>
        <el-button type="primary" @click="showApiKeyDialog = false">{{ $t('users.saved') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script>
import { ref, reactive, onMounted, computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useAuthStore } from '@/store'
import api from '@/api'
import { ElMessage, ElMessageBox } from 'element-plus'

export default {
  name: 'UsersPage',
  setup() {
    const authStore = useAuthStore()
    const { t } = useI18n()
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
        callback(new Error(t('users.validation.passwordRequired')))
      } else if (value.length < 8) {
        callback(new Error(t('users.validation.passwordMinLength')))
      } else if (value.length > 64) {
        callback(new Error(t('users.validation.passwordMaxLength')))
      } else if (!/[A-Z]/.test(value)) {
        callback(new Error(t('users.validation.passwordUppercase')))
      } else if (!/[a-z]/.test(value)) {
        callback(new Error(t('users.validation.passwordLowercase')))
      } else if (!/[0-9]/.test(value)) {
        callback(new Error(t('users.validation.passwordDigit')))
      } else if (!/[^A-Za-z0-9]/.test(value)) {
        callback(new Error(t('users.validation.passwordSpecial')))
      } else {
        callback()
      }
    }

    const addRules = {
      username: [{ required: true, message: t('users.validation.usernameRequired'), trigger: 'blur' }],
      password: [{ required: true, validator: validateStrongPassword, trigger: 'blur' }],
      role: [{ required: true, message: t('users.validation.roleRequired'), trigger: 'change' }]
    }

    const roleLabel = (role) => {
      const map = { admin: 'users.roles.admin', developer: 'users.roles.developer', tester: 'users.roles.tester', guest: 'users.roles.guest' }
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
            ElMessage.success(t('users.userCreateSuccess'))
            showAddDialog.value = false
            addForm.username = ''
            addForm.password = ''
            addForm.displayName = ''
            addForm.email = ''
            addForm.role = 'developer'
            await fetchUsers()
          } else {
            ElMessage.error(res.message || t('users.failed'))
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
          t('users.roleChangeConfirm', { 0: row.username, 1: t(roleLabel(row.role)), 2: t(roleLabel(newRole)) }),
          t('users.roleChangeTitle'),
          { type: 'warning' }
        )
        const res = await api.updateUserRole(row.username, newRole)
        if (res.success) {
          ElMessage.success(t('users.roleChangeSuccess'))
          await fetchUsers()
        } else {
          ElMessage.error(res.message || t('common.unknownError'))
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
        ElMessage.error(t('users.apiKeyGenFailed'))
      }
    }

    const handleRevokeApiKey = async (row) => {
      try {
        await ElMessageBox.confirm(
          t('users.revokeKeyConfirm', { 0: row.username }),
          t('users.revokeKeyTitle'),
          { type: 'warning' }
        )
        const res = await api.revokeApiKey(row.username)
        if (res.success) {
          ElMessage.success(t('users.revokeKeySuccess'))
          await fetchUsers()
        }
      } catch (e) {
        // cancelled
      }
    }

    const handleDelete = async (row) => {
      try {
        await ElMessageBox.confirm(
          t('users.deleteConfirm', { 0: row.username }),
          t('users.deleteTitle'),
          { type: 'danger' }
        )
        const res = await api.deleteUser(row.username)
        if (res.success) {
          ElMessage.success(t('users.deleteSuccess'))
          await fetchUsers()
        } else {
          ElMessage.error(res.message || t('common.unknownError'))
        }
      } catch (e) {
        // cancelled
      }
    }

    const copyApiKey = () => {
      navigator.clipboard.writeText(generatedApiKey.value).then(() => {
        ElMessage.success(t('users.copied'))
      }).catch(() => {
        ElMessage.warning(t('users.copyFailed'))
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
          ElMessage.success(t('users.importSuccess', { 0: res.data.created, 1: res.data.skipped, 2: res.data.failed }))
        } else {
          ElMessage.error(res.message || t('users.importFailed'))
        }
      } catch (e) {
        ElMessage.error(t('users.importFailed') + ': ' + (e.message || t('common.unknownError')))
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
.page-header h2 { margin: 0; }
.header-actions { display: flex; gap: 8px; }
.permission-preview { margin-top: 16px; padding: 12px; background: var(--bf-fill-color); border-radius: var(--bf-radius-sm); }
.preview-title { font-size: 13px; color: var(--bf-text-secondary); margin-bottom: 8px; font-weight: 600; }
.csv-template { margin-bottom: 16px; }
.csv-sample {
  background: var(--bf-fill-color); padding: 10px; border-radius: var(--bf-radius-sm);
  font-size: 12px; line-height: 1.6; color: var(--bf-text-secondary);
  overflow-x: auto; white-space: pre;
}
.import-result { margin-top: 12px; }
.error-list { font-size: 12px; color: var(--bf-danger); padding-left: 16px; margin: 4px 0; }
.error-list li { margin-bottom: 2px; }
</style>


