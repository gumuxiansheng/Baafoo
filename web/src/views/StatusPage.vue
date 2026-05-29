<template>
  <div class="status-page">
    <h2>系统状态</h2>

    <el-row :gutter="20" style="margin-top: 20px">
      <el-col :span="6" v-for="item in statItems" :key="item.label">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-value">{{ item.value }}</div>
          <div class="stat-label">{{ item.label }}</div>
        </el-card>
      </el-col>
    </el-row>

    <el-card shadow="never" style="margin-top: 20px">
      <template #header><span>Agent 列表</span></template>
      <el-table :data="agents" stripe size="small" empty-text="暂无 Agent 连接">
        <el-table-column prop="agentId" label="Agent ID" width="200" show-overflow-tooltip />
        <el-table-column prop="environment" label="环境" width="120" />
        <el-table-column prop="hostname" label="主机" width="150" />
        <el-table-column prop="version" label="版本" width="100" />
        <el-table-column label="最后心跳" width="180">
          <template #default="{ row }">
            {{ formatTime(row.lastHeartbeat) }}
            <el-tag v-if="isOnline(row)" size="small" type="success" effect="plain" style="margin-left: 8px">在线</el-tag>
            <el-tag v-else size="small" type="danger" effect="plain" style="margin-left: 8px">离线</el-tag>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card shadow="never" style="margin-top: 20px">
      <template #header><span>服务信息</span></template>
      <el-descriptions :column="2" border size="small">
        <el-descriptions-item label="版本">1.0.0-SNAPSHOT</el-descriptions-item>
        <el-descriptions-item label="HTTP 端口">8080</el-descriptions-item>
        <el-descriptions-item label="HTTP Stub">9000</el-descriptions-item>
        <el-descriptions-item label="TCP Stub">9001</el-descriptions-item>
        <el-descriptions-item label="启动时间">{{ formatTime(status?.uptime) }}</el-descriptions-item>
        <el-descriptions-item label="数据目录">./data</el-descriptions-item>
      </el-descriptions>
    </el-card>
  </div>
</template>

<script>
import { ref, reactive, computed, onMounted } from 'vue'
import api from '@/api'

export default {
  name: 'StatusPage',
  setup() {
    const status = ref(null)
    const agents = ref([])
    const envs = ref([])
    const scenes = ref([])

    const statItems = computed(() => [
      { label: '规则总数', value: status.value?.rules ?? 0 },
      { label: '环境数', value: envs.value.length },
      { label: '在线 Agents', value: agents.value.length },
      { label: '场景集', value: scenes.value.length }
    ])

    onMounted(async () => {
      const [statusRes, envsRes, scenesRes] = await Promise.all([
        api.getStatus(),
        api.getEnvironments(),
        api.getScenes()
      ])

      if (statusRes.success) status.value = statusRes.data
      if (envsRes.success) envs.value = envsRes.data
      if (scenesRes.success) scenes.value = scenesRes.data

      // Agents are part of the status response
      agents.value = status.value?.agents || []
    })

    function isOnline(agent) {
      if (!agent.lastHeartbeat) return false
      return (Date.now() - agent.lastHeartbeat) < 60000
    }

    const formatTime = (ts) => ts ? new Date(ts).toLocaleString() : '-'
    return { status, agents, statItems, isOnline, formatTime }
  }
}
</script>

<style scoped>
h2 { font-size: 20px; font-weight: 600; color: #303133; }
.stat-card { text-align: center; }
.stat-value { font-size: 32px; font-weight: 700; color: #667eea; }
.stat-label { font-size: 14px; color: #909399; margin-top: 8px; }
</style>
