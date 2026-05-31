<template>
  <div id="baafoo-app">
    <el-container style="height: 100vh">
      <!-- Header -->
      <el-header height="56px" class="baafoo-header">
        <div class="header-left">
          <span class="logo">⚡ Baafoo</span>
          <el-tag size="small" type="info" effect="plain">v1.0</el-tag>
        </div>
        <div class="header-right">
          <span class="header-badge" :class="statusConnected ? 'badge-success' : 'badge-danger'">
            {{ statusConnected ? '已连接' : '已断开' }}
          </span>
          <template v-if="authStore.isLoggedIn">
            <span class="header-badge badge-role">{{ roleLabel }}</span>
            <span class="user-name">{{ authStore.username }}</span>
            <el-button class="btn-header-logout" text size="small" @click="handleLogout">退出</el-button>
          </template>
          <template v-else>
            <span class="header-badge badge-guest">游客</span>
            <el-button class="btn-header-login" text size="small" @click="$router.push('/login')">登录</el-button>
          </template>
        </div>
      </el-header>

      <el-container>
        <!-- Sidebar -->
        <el-aside width="200px" class="baafoo-sidebar">
          <el-menu
            :default-active="activeMenu"
            router
            :collapse="false"
            class="sidebar-menu"
          >
            <el-menu-item index="/dashboard">
              <el-icon><DataBoard /></el-icon>
              <span>仪表盘</span>
            </el-menu-item>
            <el-menu-item index="/rules">
              <el-icon><List /></el-icon>
              <span>规则管理</span>
            </el-menu-item>
            <el-menu-item index="/scenes">
              <el-icon><Collection /></el-icon>
              <span>场景集</span>
            </el-menu-item>
            <el-menu-item index="/logs">
              <el-icon><Document /></el-icon>
              <span>请求日志</span>
            </el-menu-item>
            <el-menu-item index="/recordings">
              <el-icon><VideoCamera /></el-icon>
              <span>录制管理</span>
            </el-menu-item>
            <el-menu-item index="/environments">
              <el-icon><Setting /></el-icon>
              <span>环境管理</span>
            </el-menu-item>
            <el-menu-item v-if="authStore.canManageUsers" index="/users">
              <el-icon><User /></el-icon>
              <span>用户管理</span>
            </el-menu-item>
            <el-menu-item index="/status">
              <el-icon><Monitor /></el-icon>
              <span>系统状态</span>
            </el-menu-item>
          </el-menu>
        </el-aside>

        <!-- Main Content -->
        <el-main class="baafoo-main">
          <router-view />
        </el-main>
      </el-container>

      <!-- Status Bar -->
      <el-footer height="24px" class="baafoo-footer">
        <span>规则: {{ ruleCount }} | 环境: {{ envCount }} | Agents: {{ agentCount }}</span>
        <span class="footer-right">Baafoo v1.0.0</span>
      </el-footer>
    </el-container>
  </div>
</template>

<script>
import { computed, ref, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useStatusStore, useAuthStore } from '@/store'

export default {
  name: 'App',
  setup() {
    const route = useRoute()
    const router = useRouter()
    const statusStore = useStatusStore()
    const authStore = useAuthStore()
    const statusConnected = ref(true)

    const activeMenu = computed(() => route.path)

    const ruleCount = computed(() => statusStore.status?.rules ?? 0)
    const envCount = computed(() => statusStore.status?.environments ?? 0)
    const agentCount = computed(() => statusStore.status?.agents ?? 0)

    const roleLabel = computed(() => {
      const map = { admin: '管理员', developer: '开发', tester: '测试', guest: '游客' }
      return map[authStore.role] || authStore.role
    })

    const handleLogout = () => {
      authStore.logout()
      router.push('/login')
    }

    let timer = null

    onMounted(async () => {
      await authStore.fetchMe()
      statusStore.fetchStatus()
      timer = setInterval(() => statusStore.fetchStatus(), 30000)
    })

    onUnmounted(() => {
      if (timer) clearInterval(timer)
    })

    return {
      activeMenu, statusConnected, ruleCount, envCount, agentCount,
      authStore, roleLabel, handleLogout
    }
  }
}
</script>

<style>
* { margin: 0; padding: 0; box-sizing: border-box; }

body {
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif;
  background: #f5f7fa;
}

.baafoo-header {
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  color: white;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 20px;
  box-shadow: 0 2px 8px rgba(0,0,0,0.1);
}

.header-left { display: flex; align-items: center; gap: 10px; }
.logo { font-size: 20px; font-weight: 700; letter-spacing: 0.5px; }

.header-right { display: flex; align-items: center; gap: 10px; }
.header-badge {
  display: inline-flex;
  align-items: center;
  height: 22px;
  padding: 0 8px;
  border-radius: 4px;
  font-size: 12px;
  line-height: 1;
  white-space: nowrap;
}
.badge-success { background: rgba(255,255,255,0.2); color: #b7eb8f; }
.badge-danger { background: rgba(255,255,255,0.2); color: #ffa39e; }
.badge-role { background: rgba(255,255,255,0.2); color: #fff; border: 1px solid rgba(255,255,255,0.35); }
.badge-guest { background: rgba(255,255,255,0.15); color: rgba(255,255,255,0.75); border: 1px solid rgba(255,255,255,0.25); }
.user-name { font-size: 13px; color: rgba(255,255,255,0.9); }
.btn-header-logout { color: rgba(255,255,255,0.8) !important; }
.btn-header-logout:hover { color: #fff !important; }
.btn-header-login { color: rgba(255,255,255,0.9) !important; font-weight: 500; }
.btn-header-login:hover { color: #fff !important; }

.baafoo-sidebar {
  background: #fff;
  border-right: 1px solid #e8e8e8;
}

.sidebar-menu {
  border-right: none;
  height: calc(100vh - 80px);
}

.baafoo-main {
  background: #f5f7fa;
  padding: 20px;
  overflow-y: auto;
  height: calc(100vh - 80px);
}

.baafoo-footer {
  background: #fff;
  border-top: 1px solid #e8e8e8;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 20px;
  font-size: 12px;
  color: #909399;
}

.footer-right { color: #c0c4cc; }
</style>
