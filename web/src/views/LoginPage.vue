<template>
  <div class="login-container">
    <div class="login-card">
      <div class="login-header">
        <BaafooLogo class="login-logo" variant="login" />
        <h1 class="logo">Baafoo</h1>
        <p class="subtitle">{{ $t('login.subtitle') }}</p>
      </div>
      <el-form :model="form" :rules="rules" ref="formRef" @submit.prevent="handleLogin" autocomplete="off">
        <el-form-item prop="username">
          <el-input
            v-model="form.username"
            :placeholder="$t('login.usernamePlaceholder')"
            prefix-icon="User"
            size="large"
            autocomplete="off"
          />
        </el-form-item>
        <el-form-item prop="password">
          <el-input
            v-model="form.password"
            type="password"
            :placeholder="$t('login.passwordPlaceholder')"
            prefix-icon="Lock"
            size="large"
            show-password
            autocomplete="new-password"
            @keyup.enter="handleLogin"
          />
        </el-form-item>
        <el-form-item>
          <el-button
            type="primary"
            size="large"
            :loading="loading"
            style="width: 100%"
            @click="handleLogin"
          >
            {{ $t('login.loginButton') }}
          </el-button>
        </el-form-item>
      </el-form>
      <div class="login-footer">
        <el-button type="info" text size="small" @click="enterAsGuest">
          {{ $t('login.guestBrowse') }}
        </el-button>
      </div>
    </div>
  </div>
</template>

<script>
import { ref, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useAuthStore } from '@/store'
import { ElMessage } from 'element-plus'
import BaafooLogo from '@/components/BaafooLogo.vue'

export default {
  name: 'LoginPage',
  components: { BaafooLogo },
  setup() {
    const router = useRouter()
    const authStore = useAuthStore()
    const { t } = useI18n()
    const formRef = ref(null)
    const loading = ref(false)
    // L-11: Brute-force throttle — after 5 consecutive failures the login button is locked for 30s.
    const MAX_ATTEMPTS = 5
    const LOCKOUT_MS = 30 * 1000
    const loginAttemptCount = ref(0)
    const loginLockedUntil = ref(null)

    const form = reactive({
      username: '',
      password: ''
    })

    const rules = {
      username: [{ required: true, message: t('login.validation.usernameRequired'), trigger: 'blur' }],
      password: [{ required: true, message: t('login.validation.passwordRequired'), trigger: 'blur' }]
    }

    const handleLogin = async () => {
      if (!formRef.value) return
      // L-11: Rate-limit login attempts to slow down brute-force guesses. After MAX_ATTEMPTS
      // failures the button is locked for LOCKOUT_MS before the user can try again.
      if (loginLockedUntil.value && Date.now() < loginLockedUntil.value) {
        const secs = Math.ceil((loginLockedUntil.value - Date.now()) / 1000)
        ElMessage.error(t('login.loginError', { 0: `too many attempts, retry in ${secs}s` }))
        return
      }
      await formRef.value.validate(async (valid) => {
        if (!valid) return
        loading.value = true
        try {
          const success = await authStore.login(form.username, form.password)
          if (success) {
            loginAttemptCount.value = 0
            loginLockedUntil.value = null
            ElMessage.success(t('login.loginSuccess'))
            router.push('/')
          } else {
            loginAttemptCount.value += 1
            if (loginAttemptCount.value >= MAX_ATTEMPTS) {
              loginLockedUntil.value = Date.now() + LOCKOUT_MS
              loginAttemptCount.value = 0
              ElMessage.error(t('login.loginError', { 0: `too many attempts, locked for ${LOCKOUT_MS / 1000}s` }))
            } else {
              ElMessage.error(t('login.loginFailed'))
            }
          }
        } catch (e) {
          ElMessage.error(t('login.loginError', { 0: e.message || t('common.unknownError') }))
        } finally {
          loading.value = false
        }
      })
    }

    const enterAsGuest = () => {
      authStore.logout()
      router.push('/')
    }

    return { form, rules, formRef, loading, handleLogin, enterAsGuest }
  }
}
</script>

<style scoped>
.login-container {
  height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background:
    radial-gradient(circle at 20% 20%, rgba(15, 118, 110, 0.06) 0%, transparent 35%),
    radial-gradient(circle at 80% 80%, rgba(41, 37, 36, 0.04) 0%, transparent 35%),
    var(--bf-bg);
}

.login-card {
  width: 420px;
  padding: 44px;
  background: var(--bf-surface);
  border-radius: var(--bf-radius-lg);
  border: 1px solid var(--bf-border);
  box-shadow: var(--bf-shadow-lg);
}

.login-header {
  text-align: center;
  margin-bottom: 36px;
}

.login-logo {
  width: 60px;
  height: 60px;
  margin: 0 auto 16px;
  display: block;
  color: var(--bf-accent);
}

.logo {
  font-size: 30px;
  font-weight: 800;
  letter-spacing: -0.03em;
  color: var(--bf-text);
}

.subtitle {
  margin-top: 8px;
  color: var(--bf-text-muted);
  font-size: 14px;
  font-weight: 500;
}

.login-footer {
  text-align: center;
  margin-top: 12px;
}
</style>
