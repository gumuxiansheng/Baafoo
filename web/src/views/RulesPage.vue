<template>
  <div class="rules-page">
    <div class="page-header">
      <h2>规则管理</h2>
      <el-button type="primary" @click="showCreateDialog">
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
            <el-link type="primary" @click="editRule(row.id)">{{ row.name }}</el-link>
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
            <el-switch v-model="row.enabled" @change="toggleRule(row)" size="small" />
          </template>
        </el-table-column>
        <el-table-column prop="version" label="版本" width="60" align="center" />
        <el-table-column label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <el-button size="small" text @click="editRule(row.id)">编辑</el-button>
            <el-button size="small" text @click="undoRuleItem(row)" :disabled="row.version <= 1">撤销</el-button>
            <el-popconfirm title="确定删除此规则？" @confirm="deleteRuleItem(row.id)">
              <template #reference>
                <el-button size="small" text type="danger">删除</el-button>
              </template>
            </el-popconfirm>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- Create/Edit Dialog -->
    <el-dialog
      v-model="dialogVisible"
      :title="editingRuleId ? '编辑规则' : '新建规则'"
      width="700px"
      destroy-on-close
    >
      <el-form :model="form" label-width="100px" size="small">
        <el-form-item label="规则名称" required>
          <el-input v-model="form.name" placeholder="如: GET /api/users" />
        </el-form-item>
        <el-form-item label="协议" required>
          <el-select v-model="form.protocol" style="width: 100%">
            <el-option label="HTTP" value="http" />
            <el-option label="TCP" value="tcp" />
            <el-option label="Kafka" value="kafka" />
            <el-option label="Pulsar" value="pulsar" />
            <el-option label="JMS" value="jms" />
          </el-select>
        </el-form-item>
        <el-form-item label="目标主机">
          <el-input v-model="form.host" placeholder="如: api.example.com（留空匹配所有）" />
        </el-form-item>
        <el-form-item label="目标端口">
          <el-input-number v-model="form.port" :min="0" :max="65535" placeholder="0=所有" />
        </el-form-item>
        <el-form-item label="服务名(Consul)">
          <el-input v-model="form.serviceName" placeholder="如: user-service" />
        </el-form-item>
        <el-form-item label="优先级">
          <el-input-number v-model="form.priority" :min="1" :max="1000" />
        </el-form-item>
        <el-form-item label="状态码">
          <el-input-number v-model="form.statusCode" :min="100" :max="599" />
        </el-form-item>
        <el-form-item label="响应体">
          <el-input v-model="form.responseBody" type="textarea" :rows="6"
            placeholder='{"code": 0, "data": [], "message": "success"}' />
        </el-form-item>
        <el-form-item label="延迟(ms)">
          <el-input-number v-model="form.delayMs" :min="0" :max="60000" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="saveRule">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script>
import { ref, reactive, computed, onMounted } from 'vue'
import { useRulesStore } from '@/store'
import { useRouter } from 'vue-router'

export default {
  name: 'RulesPage',
  setup() {
    const router = useRouter()
    const rulesStore = useRulesStore()
    const loading = ref(false)
    const dialogVisible = ref(false)
    const editingRuleId = ref(null)
    const filter = reactive({ protocol: '', keyword: '' })

    const form = reactive({
      name: '', protocol: 'http', host: '', port: null,
      serviceName: '', priority: 100, statusCode: 200,
      responseBody: '', delayMs: 0
    })

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

    function showCreateDialog() {
      editingRuleId.value = null
      Object.assign(form, {
        name: '', protocol: 'http', host: '', port: null,
        serviceName: '', priority: 100, statusCode: 200,
        responseBody: '', delayMs: 0
      })
      dialogVisible.value = true
    }

    function editRule(id) {
      router.push(`/rules/${id}`)
    }

    async function saveRule() {
      const ruleData = {
        name: form.name,
        protocol: form.protocol,
        host: form.host || null,
        port: form.port || null,
        serviceName: form.serviceName || null,
        priority: form.priority,
        enabled: true,
        conditions: [],
        responses: [{
          name: '默认响应',
          statusCode: form.statusCode,
          body: form.responseBody,
          delayMs: form.delayMs
        }]
      }

      if (editingRuleId.value) {
        await rulesStore.updateRule(editingRuleId.value, ruleData)
      } else {
        await rulesStore.createRule(ruleData)
      }

      dialogVisible.value = false
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

    onMounted(loadRules)

    return {
      loading, dialogVisible, editingRuleId, filter, form,
      filteredRules, showCreateDialog, editRule, saveRule,
      toggleRule, deleteRuleItem, undoRuleItem, loadRules, resetFilter
    }
  }
}
</script>

<style scoped>
.page-header { display: flex; justify-content: space-between; align-items: center; }
.page-header h2 { font-size: 20px; font-weight: 600; color: #303133; }
</style>
