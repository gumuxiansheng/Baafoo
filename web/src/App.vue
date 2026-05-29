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
          <el-tag :type="statusConnected ? 'success' : 'danger'" size="small" effect="dark">
            {{ statusConnected ? '已连接' : '已断开' }}
          </el-tag>
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
import { useRoute } from 'vue-router'
import { useStatusStore } from '@/store/status'

export default {
  name: 'App',
  setup() {
    const route = useRoute()
    const statusStore = useStatusStore()
    const statusConnected = ref(true)

    const activeMenu = computed(() => route.path)

    const ruleCount = computed(() => statusStore.status?.rules ?? 0)
    const envCount = computed(() => statusStore.status?.environments ?? 0)
    const agentCount = computed(() => statusStore.status?.agents ?? 0)

    let timer = null

    onMounted(() => {
      statusStore.fetchStatus()
      timer = setInterval(() => statusStore.fetchStatus(), 30000)
    })

    onUnmounted(() => {
      if (timer) clearInterval(timer)
    })

    return { activeMenu, statusConnected, ruleCount, envCount, agentCount }
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
