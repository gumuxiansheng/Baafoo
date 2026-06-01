<template>
  <div class="rules-page">
    <div class="page-header">
      <h2>规则管理</h2>
      <el-button type="primary" @click="createRule" v-if="authStore.canWriteRule">
        <el-icon><Plus /></el-icon> 新建规则
      </el-button>
    </div>

    <!-- Filters -->
    <el-card shadow="never" style="margin-top: 16px">
      <el-form :inline="true" size="small">
        <el-form-item label="协议">
          <el-select v-model="filter.protocol" placeholder="全部" clearable style="width: 120px">
            <el-option label="HTTP" value="http" />
            <el-option label="TCP" value="tcp" />
            <el-option label="Kafka" value="kafka" />
            <el-option label="Pulsar" value="pulsar" />
            <el-option label="JMS" value="jms" />
          </el-select>
        </el-form-item>
        <el-form-item label="搜索">
          <el-input v-model="filter.keyword" placeholder="规则名称/ID..." clearable style="width: 200px" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="loadRules">查询</el-button>
          <el-button @click="resetFilter">重置</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <!-- Rule Table -->
    <el-card shadow="never" style="margin-top: 16px">
      <el-table :data="filteredRules" stripe v-loading="loading" size="small">
        <el-table-column prop="name" label="规则名称" min-width="180">
          <template #default="{ row }">
            <el-link type="primary" @click="editRule(row)">{{ row.name }}</el-link>
          </template>
        </el-table-column>
        <el-table-column prop="id" label="ID" width="160" show-overflow-tooltip />
        <el-table-column prop="protocol" label="协议" width="80">
          <template #default="{ row }">
            <el-tag size="small">{{ (row.protocol || '').toUpperCase() }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="目标" min-width="200">
          <template #default="{ row }">
            <span v-if="row.serviceName">{{ row.serviceName }} (Consul)</span>
            <span v-else>{{ row.host || '*' }}:{{ row.port || '*' }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="priority" label="优先级" width="80" align="center" />
        <el-table-column label="状态" width="80" align="center">
          <template #default="{ row }">
            <el-switch v-model="row.enabled" @change="toggleRule(row)" size="small" :disabled="!authStore.canWriteRule" />
          </template>
        </el-table-column>
        <el-table-column label="生效环境" min-width="120">
          <template #default="{ row }">
            <template v-if="row.environments && row.environments.length > 0">
              <el-tag v-for="env in row.environments" :key="env" size="small" type="info" style="margin-right: 4px">{{ env }}</el-tag>
            </template>
            <el-tag v-else size="small" type="warning">未关联</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="version" label="版本" width="60" align="center" />
        <el-table-column label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <el-button size="small" text @click="editRule(row)" v-if="authStore.canWriteRule">编辑</el-button>
            <el-button size="small" text @click="undoRuleItem(row)" :disabled="row.version <= 1" v-if="authStore.canWriteRule">撤销</el-button>
            <el-popconfirm title="确定删除此规则？" @confirm="deleteRuleItem(row.id)" v-if="authStore.canWriteRule">
              <template #reference>
                <el-button size="small" text type="danger">删除</el-button>
              </template>
            </el-popconfirm>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script>
import { ref, reactive, computed, onMounted } from 'vue'
import { useRulesStore, useAuthStore } from '@/store'
import { useRouter } from 'vue-router'

export default {
  name: 'RulesPage',
  setup() {
    const router = useRouter()
    const rulesStore = useRulesStore()
    const authStore = useAuthStore()
    const loading = ref(false)
    const filter = reactive({ protocol: '', keyword: '' })

    const filteredRules = computed(() => {
      let list = rulesStore.rules
      if (filter.protocol) list = list.filter(r => r.protocol === filter.protocol)
      if (filter.keyword) {
        const kw = filter.keyword.toLowerCase()
        list = list.filter(r => r.name.toLowerCase().includes(kw) || r.id.toLowerCase().includes(kw))
      }
      return list
    })

    async function loadRules() {
      loading.value = true
      await rulesStore.fetchRules()
      loading.value = false
    }

    function resetFilter() {
      filter.protocol = ''
      filter.keyword = ''
    }

    function createRule() {
      router.push({ name: 'RuleEditor', params: { id: 'new' } })
    }

    function editRule(rule) {
      router.push({ name: 'RuleEditor', params: { id: rule.id } })
    }

    async function toggleRule(rule) {
      await rulesStore.updateRule(rule.id, { enabled: rule.enabled })
    }

    async function deleteRuleItem(id) {
      await rulesStore.deleteRule(id)
    }

    async function undoRuleItem(rule) {
      await rulesStore.undoRule(rule.id)
    }

    onMounted(() => { loadRules() })

    return {
      loading, filter, filteredRules,
      createRule, editRule, loadRules, resetFilter,
      toggleRule, deleteRuleItem, undoRuleItem, authStore
    }
  }
}
</script>

<style scoped>
.page-header { display: flex; justify-content: space-between; align-items: center; }
.page-header h2 { font-size: 20px; font-weight: 600; color: #303133; }
</style>
