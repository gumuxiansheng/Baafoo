<template>
  <div id="baafoo-app">
    <el-container style="height: 100vh">
      <!-- Header -->
      <el-header height="56px" class="baafoo-header">
        <div class="header-left">
          <BaafooLogo class="logo-icon" />
          <span class="logo">Baafoo</span>
          <el-tag size="small" type="info" effect="plain">{{ $t('app.version') }}</el-tag>
        </div>
        <div class="header-right">
          <span class="header-badge">
            <span class="connection-dot" :class="statusConnected ? 'success' : 'danger'"></span>
            {{ statusConnected ? $t('app.connected') : $t('app.disconnected') }}
          </span>
          <LocaleSwitcher />
          <template v-if="authStore.isLoggedIn">
            <span class="header-badge badge-role">{{ roleLabel }}</span>
            <el-dropdown @command="handleUserCommand">
              <span class="user-name" style="cursor: pointer; display: inline-flex; align-items: center; gap: 4px;">
                {{ authStore.username }}
                <el-icon style="font-size: 12px"><ArrowDown /></el-icon>
              </span>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="profile">{{ $t('nav.profile') }}</el-dropdown-item>
                  <el-dropdown-item command="logout" divided>{{ $t('app.logout') }}</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </template>
          <template v-else>
            <span class="header-badge badge-guest">{{ $t('app.guest') }}</span>
            <el-button class="btn-header-login" text size="small" @click="$router.push('/login')">{{ $t('app.login') }}</el-button>
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
              <span>{{ $t('nav.dashboard') }}</span>
            </el-menu-item>
            <el-menu-item index="/rules">
              <el-icon><List /></el-icon>
              <span>{{ $t('nav.rules') }}</span>
            </el-menu-item>
            <el-menu-item index="/scenes">
              <el-icon><Collection /></el-icon>
              <span>{{ $t('nav.scenes') }}</span>
            </el-menu-item>
            <el-menu-item index="/logs">
              <el-icon><Document /></el-icon>
              <span>{{ $t('nav.logs') }}</span>
            </el-menu-item>
            <el-menu-item index="/recordings">
              <el-icon><VideoCamera /></el-icon>
              <span>{{ $t('nav.recordings') }}</span>
            </el-menu-item>
            <el-menu-item index="/environments">
              <el-icon><Setting /></el-icon>
              <span>{{ $t('nav.environments') }}</span>
            </el-menu-item>
            <el-menu-item v-if="authStore.canManageUsers" index="/users">
              <el-icon><User /></el-icon>
              <span>{{ $t('nav.users') }}</span>
            </el-menu-item>
            <el-menu-item index="/status">
              <el-icon><Monitor /></el-icon>
              <span>{{ $t('nav.status') }}</span>
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
        <span>{{ $t('footer.rules') }}: {{ ruleCount }} | {{ $t('footer.environments') }}: {{ envCount }} | {{ $t('footer.agents') }}: {{ agentCount }}</span>
        <span class="footer-right">Baafoo v1.0.0</span>
      </el-footer>
    </el-container>
  </div>
</template>

<script>
import { computed, ref, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useStatusStore, useAuthStore } from '@/store'
import { useI18n } from 'vue-i18n'
import { ArrowDown } from '@element-plus/icons-vue'
import BaafooLogo from '@/components/BaafooLogo.vue'
import LocaleSwitcher from '@/components/LocaleSwitcher.vue'

export default {
  name: 'App',
  components: { BaafooLogo, LocaleSwitcher, ArrowDown },
  setup() {
    const route = useRoute()
    const router = useRouter()
    const { t } = useI18n()
    const statusStore = useStatusStore()
    const authStore = useAuthStore()
    const statusConnected = ref(true)

    const activeMenu = computed(() => route.path)

    const ruleCount = computed(() => statusStore.status?.rules ?? 0)
    const envCount = computed(() => statusStore.status?.environments ?? 0)
    const agentCount = computed(() => statusStore.status?.agents ?? 0)

    const roleLabel = computed(() => {
      const map = { admin: t('roles.admin'), developer: t('roles.developer'), tester: t('roles.tester'), guest: t('roles.guest') }
      return map[authStore.role] || authStore.role
    })

    const handleLogout = () => {
      authStore.logout()
      router.push('/login')
    }

    const handleUserCommand = (command) => {
      if (command === 'logout') {
        handleLogout()
      } else if (command === 'profile') {
        router.push('/profile')
      }
    }

    let timer = null
    let visibilityTimer = null
    // H-6: 指数退避参数（失败时拉长轮询间隔，避免后端不可用时频繁重试）
    const BASE_INTERVAL = 30000
    const MAX_INTERVAL = 5 * 60 * 1000
    let currentInterval = BASE_INTERVAL

    async function pollOnce() {
      // H-6: fetchStatus 返回 boolean 表示成功/失败；失败时拉长间隔（指数退避）
      try {
        const ok = await statusStore.fetchStatus()
        statusConnected.value = ok
        if (ok) {
          currentInterval = BASE_INTERVAL
        } else {
          currentInterval = Math.min(currentInterval * 2, MAX_INTERVAL)
        }
      } finally {
        scheduleNext()
      }
    }

    function scheduleNext() {
      if (timer) {
        clearTimeout(timer)
        timer = null
      }
      timer = setTimeout(() => {
        pollOnce()
      }, currentInterval)
    }

    function startPolling() {
      if (timer) {
        clearTimeout(timer)
        timer = null
      }
      currentInterval = BASE_INTERVAL
      pollOnce()
    }

    function stopPolling() {
      if (timer) {
        clearTimeout(timer)
        timer = null
      }
    }

    function onVisibilityChange() {
      clearTimeout(visibilityTimer)
      visibilityTimer = setTimeout(() => {
        if (document.hidden) {
          stopPolling()
        } else {
          startPolling()
        }
      }, 300)
    }

    onMounted(async () => {
      await authStore.fetchMe()
      startPolling()
      document.addEventListener('visibilitychange', onVisibilityChange)
    })

    onUnmounted(() => {
      stopPolling()
      clearTimeout(visibilityTimer)
      document.removeEventListener('visibilitychange', onVisibilityChange)
    })

    return {
      activeMenu, statusConnected, ruleCount, envCount, agentCount,
      authStore, roleLabel, handleLogout, handleUserCommand, t
    }
  }
}
</script>

<style>
.baafoo-header {
  background: var(--bf-surface);
  color: var(--bf-text);
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 22px;
  border-bottom: 1px solid var(--bf-border);
  box-shadow: var(--bf-shadow-sm);
  z-index: 10;
}

.header-left { display: flex; align-items: center; gap: 10px; }
.logo-icon {
  width: 30px;
  height: 30px;
  flex-shrink: 0;
  color: var(--bf-accent);
}
.logo {
  font-size: 20px;
  font-weight: 800;
  letter-spacing: -0.03em;
  color: var(--bf-text);
}

.header-right { display: flex; align-items: center; gap: 12px; }
.connection-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  display: inline-block;
  margin-right: 6px;
}
.connection-dot.success { background: var(--bf-success); box-shadow: 0 0 0 3px rgba(21, 128, 61, 0.12); }
.connection-dot.danger { background: var(--bf-danger); box-shadow: 0 0 0 3px rgba(190, 18, 60, 0.12); }

.header-badge {
  display: inline-flex;
  align-items: center;
  height: 26px;
  padding: 0 10px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 600;
  line-height: 1;
  white-space: nowrap;
  background: var(--bf-fill-color);
  color: var(--bf-text-secondary);
  border: 1px solid var(--bf-border);
}
.badge-role {
  background: var(--bf-accent-light);
  color: var(--bf-accent);
  border-color: #ccfbf1;
}
.badge-guest {
  background: var(--bf-fill-color);
  color: var(--bf-text-muted);
}
.user-name { font-size: 13px; font-weight: 600; color: var(--bf-text-secondary); }
.btn-header-logout { color: var(--bf-text-secondary) !important; }
.btn-header-logout:hover { color: var(--bf-danger) !important; background: var(--bf-danger-bg) !important; }
.btn-header-login { color: var(--bf-accent) !important; font-weight: 700; }
.btn-header-login:hover { color: var(--bf-accent-hover) !important; background: var(--bf-accent-light) !important; }

.baafoo-sidebar {
  background: var(--bf-surface);
  border-right: 1px solid var(--bf-border);
}

.sidebar-menu {
  border-right: none;
  height: calc(100vh - 80px);
  padding-top: 8px;
  background: transparent;
}

.baafoo-main {
  background: var(--bf-bg);
  padding: 24px;
  overflow-y: auto;
  height: calc(100vh - 80px);
}

.baafoo-footer {
  background: var(--bf-surface);
  border-top: 1px solid var(--bf-border);
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 22px;
  font-size: 12px;
  font-weight: 600;
  color: var(--bf-text-muted);
}

.footer-right { color: var(--bf-text-muted); }
</style>
