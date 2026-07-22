<template>
  <div class="profile-container">
    <el-row :gutter="20">
      <!-- 个人信息卡片 -->
      <el-col :xs="24" :sm="24" :md="12" :lg="12">
        <el-card class="profile-card">
          <template #header>
            <div class="card-header">
              <el-icon><User /></el-icon>
              <span>{{ $t('profile.title') }}</span>
            </div>
          </template>

          <el-alert
            v-if="ssoBlocked"
            :title="ssoMessage"
            type="warning"
            :closable="false"
            show-icon
            style="margin-bottom: 20px"
          >
            <template #default>
              <p>{{ ssoMessage }}</p>
              <el-button type="primary" size="small" @click="goEhre">{{ $t('profile.goEhre') }}</el-button>
            </template>
          </el-alert>

          <el-form :model="profileForm" label-width="100px">
            <el-form-item :label="$t('profile.username')">
              <el-input :value="profileForm.username" disabled />
            </el-form-item>
            <el-form-item :label="$t('profile.displayName')">
              <el-input v-model="profileForm.displayName" :disabled="ssoBlocked" :placeholder="$t('profile.displayNamePlaceholder')" />
            </el-form-item>
            <el-form-item :label="$t('profile.email')">
              <el-input v-model="profileForm.email" :disabled="ssoBlocked" placeholder="email@example.com" />
            </el-form-item>
            <el-form-item :label="$t('profile.phone')">
              <el-input v-model="profileForm.phone" :disabled="ssoBlocked" :placeholder="$t('profile.phonePlaceholder')" />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" @click="saveProfile" :disabled="ssoBlocked" :loading="saving">{{ $t('profile.save') }}</el-button>
            </el-form-item>
          </el-form>
        </el-card>
      </el-col>

      <!-- 修改密码卡片 -->
      <el-col :xs="24" :sm="24" :md="12" :lg="12">
        <el-card class="password-card">
          <template #header>
            <div class="card-header">
              <el-icon><Lock /></el-icon>
              <span>{{ $t('profile.changePassword') }}</span>
            </div>
          </template>

          <el-alert
            v-if="ssoBlocked"
            :title="ssoMessage"
            type="warning"
            :closable="false"
            show-icon
            style="margin-bottom: 20px"
          />

          <el-form :model="passwordForm" label-width="100px">
            <el-form-item :label="$t('profile.oldPassword')">
              <el-input v-model="passwordForm.oldPassword" type="password" :disabled="ssoBlocked" show-password :placeholder="$t('profile.oldPasswordPlaceholder')" />
            </el-form-item>
            <el-form-item :label="$t('profile.newPassword')">
              <el-input v-model="passwordForm.newPassword" type="password" :disabled="ssoBlocked" show-password :placeholder="$t('profile.newPasswordPlaceholder')" />
            </el-form-item>
            <el-form-item :label="$t('profile.confirmPassword')">
              <el-input v-model="passwordForm.confirmPassword" type="password" :disabled="ssoBlocked" show-password :placeholder="$t('profile.confirmPasswordPlaceholder')" />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" @click="savePassword" :disabled="ssoBlocked" :loading="changingPassword">{{ $t('profile.changePasswordBtn') }}</el-button>
            </el-form-item>
          </el-form>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script>
import { ElMessage } from 'element-plus'
import { User, Lock } from '@element-plus/icons-vue'
import api from '@/api'
import { useAuthStore } from '@/store'

export default {
  name: 'UserProfile',
  components: { User, Lock },
  data() {
    return {
      profileForm: {
        username: '',
        displayName: '',
        email: '',
        phone: ''
      },
      passwordForm: {
        oldPassword: '',
        newPassword: '',
        confirmPassword: ''
      },
      saving: false,
      changingPassword: false,
      ssoBlocked: false,
      ssoMessage: ''
    }
  },
  async created() {
    const authStore = useAuthStore()
    this.profileForm.username = authStore.username || ''
    try {
      const res = await api.getAuthMe()
      if (res && res.success && res.data) {
        this.profileForm.username = res.data.username || this.profileForm.username
      }
    } catch (e) {
      // ignore
    }
  },
  methods: {
    saveProfile() {
      this.saving = true
      api.updateProfile({
        displayName: this.profileForm.displayName,
        email: this.profileForm.email,
        phone: this.profileForm.phone
      }).then((res) => {
        if (res && res.success) {
          ElMessage.success(this.$t('profile.saveSuccess'))
        } else if (res && res.code === 403) {
          this.ssoBlocked = true
          this.ssoMessage = res.message || this.$t('profile.ssoBlocked')
        } else {
          ElMessage.error((res && res.message) || this.$t('profile.saveFail'))
        }
      }).catch(() => {
        ElMessage.error(this.$t('profile.saveFail'))
      }).finally(() => {
        this.saving = false
      })
    },
    savePassword() {
      if (!this.passwordForm.oldPassword) {
        ElMessage.warning(this.$t('profile.oldPasswordRequired'))
        return
      }
      if (!this.passwordForm.newPassword) {
        ElMessage.warning(this.$t('profile.newPasswordRequired'))
        return
      }
      if (this.passwordForm.newPassword !== this.passwordForm.confirmPassword) {
        ElMessage.warning(this.$t('profile.passwordMismatch'))
        return
      }
      this.changingPassword = true
      api.changePassword(this.passwordForm.oldPassword, this.passwordForm.newPassword).then((res) => {
        if (res && res.success) {
          ElMessage.success(this.$t('profile.passwordChanged'))
          this.passwordForm.oldPassword = ''
          this.passwordForm.newPassword = ''
          this.passwordForm.confirmPassword = ''
        } else if (res && res.code === 403) {
          this.ssoBlocked = true
          this.ssoMessage = res.message || this.$t('profile.ssoBlocked')
        } else {
          ElMessage.error((res && res.message) || this.$t('profile.passwordChangeFail'))
        }
      }).catch(() => {
        ElMessage.error(this.$t('profile.passwordChangeFail'))
      }).finally(() => {
        this.changingPassword = false
      })
    },
    goEhre() {
      window.open('http://localhost:8085', '_blank')
    }
  }
}
</script>

<style scoped>
.profile-container {
  padding: 20px;
}
.profile-card,
.password-card {
  margin-bottom: 20px;
}
.card-header {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 18px;
  font-weight: 500;
}
.card-header .el-icon {
  font-size: 20px;
  color: var(--el-color-primary);
}
</style>
