<template>
  <div class="login-container">
    <div class="login-card">
      <div class="login-header">
        <BaafooLogo class="login-logo" variant="login" />
        <h1 class="logo">Baafoo</h1>
        <p class="subtitle">挡板系统控制台</p>
      </div>
      <el-form :model="form" :rules="rules" ref="formRef" @submit.prevent="handleLogin" autocomplete="off">
        <el-form-item prop="username">
          <el-input
            v-model="form.username"
            placeholder="用户名"
            prefix-icon="User"
            size="large"
            autocomplete="off"
          />
        </el-form-item>
        <el-form-item prop="password">
          <el-input
            v-model="form.password"
            type="password"
            placeholder="密码"
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
            登 录
          </el-button>
        </el-form-item>
      </el-form>
      <div class="login-footer">
        <el-button type="info" text size="small" @click="enterAsGuest">
          以游客身份浏览
        </el-button>
      </div>
    </div>
  </div>
</template>

<script>
import { ref, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/store'
import { ElMessage } from 'element-plus'
import BaafooLogo from '@/components/BaafooLogo.vue'

export default {
  name: 'LoginPage',
  components: { BaafooLogo },
  setup() {
    const router = useRouter()
    const authStore = useAuthStore()
    const formRef = ref(null)
    const loading = ref(false)

    const form = reactive({
      username: '',
      password: ''
    })

    const rules = {
      username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
      password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
    }

    const handleLogin = async () => {
      if (!formRef.value) return
      await formRef.value.validate(async (valid) => {
        if (!valid) return
        loading.value = true
        try {
          const success = await authStore.login(form.username, form.password)
          if (success) {
            ElMessage.success({ message: '登录成功', duration: 3000 })
            router.push('/')
          } else {
            ElMessage.error('用户名或密码错误')
          }
        } catch (e) {
          ElMessage.error('登录失败: ' + (e.message || '未知错误'))
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
