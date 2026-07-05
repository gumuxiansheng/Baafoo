<template>
  <div class="status-page">
    <h2>{{ $t('status.title') }}</h2>

    <el-row :gutter="20" style="margin-top: 20px">
      <el-col :span="6" v-for="item in statItems" :key="item.label">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-value">{{ item.value }}</div>
          <div class="stat-label">{{ item.label }}</div>
        </el-card>
      </el-col>
    </el-row>

    <el-card shadow="never" style="margin-top: 20px">
      <template #header>
        <span>{{ $t('status.agentList') }}</span>
        <el-button size="small" text style="float: right" @click="loadData">{{ $t('status.refresh') }}</el-button>
      </template>
      <el-table :data="agents" stripe size="small" :empty-text="$t('status.noAgent')">
        <el-table-column prop="agentId" label="Agent ID" width="200" show-overflow-tooltip />
        <el-table-column prop="environment" :label="$t('status.agentColumns.env')" width="120" />
        <el-table-column prop="hostname" :label="$t('status.agentColumns.host')" width="150" />
        <el-table-column prop="agentIp" :label="$t('status.agentColumns.ip')" width="140" />
        <el-table-column prop="version" :label="$t('status.agentColumns.version')" width="100" />
        <el-table-column :label="$t('status.agentColumns.registeredAt')" width="180">
          <template #default="{ row }">{{ formatTime(row.registeredAt) }}</template>
        </el-table-column>
        <el-table-column :label="$t('status.agentColumns.lastHeartbeat')" min-width="200">
          <template #default="{ row }">
            <div style="display: flex; align-items: center; gap: 8px">
              <span>{{ formatTime(row.lastHeartbeat) }}</span>
              <el-tag v-if="isOnline(row)" size="small" type="success" effect="plain">{{ $t('status.agentColumns.online') }}</el-tag>
              <el-tag v-else size="small" type="danger" effect="plain">{{ $t('status.agentColumns.offline') }}</el-tag>
            </div>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card shadow="never" style="margin-top: 20px">
      <template #header><span>{{ $t('status.serviceInfo') }}</span></template>
      <el-descriptions :column="2" border size="small">
        <el-descriptions-item :label="$t('status.version')">1.0.0-SNAPSHOT</el-descriptions-item>
        <el-descriptions-item :label="$t('status.httpPort')">8084</el-descriptions-item>
        <el-descriptions-item label="HTTP Stub">9000</el-descriptions-item>
        <el-descriptions-item label="TCP Stub">9001</el-descriptions-item>
        <el-descriptions-item :label="$t('status.startedAt')">{{ formatTime(status?.uptime) }}</el-descriptions-item>
        <el-descriptions-item :label="$t('status.dataDir')">./data</el-descriptions-item>
      </el-descriptions>
    </el-card>
  </div>
</template>

<script>
import { ref, computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import api from '@/api'

export default {
  name: 'StatusPage',
  setup() {
    const { t } = useI18n()
    const status = ref(null)
    const agents = ref([])

    const statItems = computed(() => [
      { label: t('status.statLabels.rules'), value: status.value?.rules ?? 0 },
      { label: t('status.statLabels.environments'), value: status.value?.environments ?? 0 },
      { label: t('status.statLabels.onlineAgents'), value: status.value?.onlineAgents ?? 0 },
      { label: t('status.statLabels.scenes'), value: status.value?.scenes ?? 0 }
    ])

    async function loadData() {
      const [statusRes, agentsRes] = await Promise.all([
        api.getStatus(),
        api.getAgents()
      ])

      if (statusRes.success) status.value = statusRes.data
      if (agentsRes.success) agents.value = agentsRes.data || []
    }

    function isOnline(agent) {
      if (!agent.lastHeartbeat) return false
      return (Date.now() - agent.lastHeartbeat) < 60000
    }

    const formatTime = (ts) => ts ? new Date(ts).toLocaleString() : '-'

    onMounted(loadData)
    return { status, agents, statItems, isOnline, formatTime, loadData }
  }
}
</script>

<style scoped>
h2 { font-size: 22px; font-weight: 800; letter-spacing: -0.03em; color: var(--bf-text); }
.stat-card .stat-value { color: var(--bf-accent); }
.stat-card .stat-label { color: var(--bf-text-muted); }
</style>
