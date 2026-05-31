<template>
  <div class="rule-editor-page">
    <div class="page-header">
      <el-button text @click="$router.back()">
        <el-icon><ArrowLeft /></el-icon> 返回
      </el-button>
      <h2>{{ rule ? '编辑规则' : '加载中...' }}</h2>
    </div>

    <el-card shadow="never" style="margin-top: 16px" v-if="rule" v-loading="loading">
      <el-form :model="form" label-width="100px" size="small">
        <!-- Basic Info -->
        <el-divider content-position="left">基本信息</el-divider>
        <el-row :gutter="20">
          <el-col :span="12">
            <el-form-item label="规则名称" required>
              <el-input v-model="form.name" />
            </el-form-item>
          </el-col>
          <el-col :span="6">
            <el-form-item label="协议" required>
              <el-select v-model="form.protocol" style="width: 100%">
                <el-option label="HTTP" value="http" />
                <el-option label="TCP" value="tcp" />
                <el-option label="Kafka" value="kafka" />
                <el-option label="Pulsar" value="pulsar" />
                <el-option label="JMS" value="jms" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="6">
            <el-form-item label="优先级">
              <el-input-number v-model="form.priority" :min="1" :max="1000" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="20">
          <el-col :span="8">
            <el-form-item label="目标主机">
              <el-input v-model="form.host" placeholder="留空匹配所有" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="目标端口">
              <el-input-number v-model="form.port" :min="0" :max="65535" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="服务名(Consul)">
              <el-input v-model="form.serviceName" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="启用">
          <el-switch v-model="form.enabled" />
        </el-form-item>
        <el-form-item label="标签">
          <el-input v-model="form.tagsStr" placeholder="逗号分隔" />
        </el-form-item>
        <el-form-item label="生效环境">
          <el-select v-model="form.environments" multiple filterable allow-create default-first-option placeholder="选择或输入环境名" style="width: 100%">
            <el-option v-for="env in allEnvironments" :key="env.name" :label="env.name" :value="env.name" />
          </el-select>
          <div style="font-size: 12px; color: #909399; margin-top: 4px">未选择环境时规则不生效，需显式关联环境后才参与匹配</div>
        </el-form-item>

        <!-- Match Conditions -->
        <el-divider content-position="left">匹配条件
          <el-button size="small" @click="addCondition">+ 添加条件</el-button>
        </el-divider>
        <div v-for="(cond, idx) in form.conditions" :key="idx" style="margin-bottom: 8px">
          <el-row :gutter="10">
            <el-col :span="4">
              <el-select v-model="cond.type" size="small">
                <el-option label="Method" value="method" />
                <el-option label="Path" value="path" />
                <el-option label="Header" value="header" />
                <el-option label="Query" value="query" />
                <el-option label="Body" value="body" />
              </el-select>
            </el-col>
            <el-col :span="3">
              <el-select v-model="cond.operator" size="small">
                <el-option label="等于" value="equals" />
                <el-option label="包含" value="contains" />
                <el-option label="开头" value="startsWith" />
                <el-option label="结尾" value="endsWith" />
                <el-option label="正则" value="regex" />
                <el-option label="存在" value="exists" />
              </el-select>
            </el-col>
            <el-col :span="4" v-if="cond.type === 'header' || cond.type === 'query'">
              <el-input v-model="cond.key" size="small" placeholder="Key" />
            </el-col>
            <el-col :span="cond.type === 'header' || cond.type === 'query' ? 8 : 12">
              <el-input v-model="cond.value" size="small" placeholder="值" />
            </el-col>
            <el-col :span="4">
              <el-button size="small" type="danger" text @click="removeCondition(idx)">删除</el-button>
            </el-col>
          </el-row>
        </div>

        <!-- Responses -->
        <el-divider content-position="left">响应配置
          <el-button size="small" @click="addResponse">+ 添加响应</el-button>
        </el-divider>
        <div v-for="(resp, idx) in form.responses" :key="idx"
             style="border: 1px solid #e8e8e8; border-radius: 8px; padding: 16px; margin-bottom: 12px">
          <el-row :gutter="10">
            <el-col :span="8">
              <el-form-item label="响应名称" size="small">
                <el-input v-model="resp.name" size="small" placeholder="如: 成功返回" />
              </el-form-item>
            </el-col>
            <el-col :span="4">
              <el-form-item label="状态码" size="small">
                <el-input-number v-model="resp.statusCode" :min="100" :max="599" size="small" />
              </el-form-item>
            </el-col>
            <el-col :span="4">
              <el-form-item label="延迟ms" size="small">
                <el-input-number v-model="resp.delayMs" :min="0" :max="60000" size="small" />
              </el-form-item>
            </el-col>
            <el-col :span="8">
              <el-button size="small" type="danger" text @click="removeResponse(idx)" style="margin-top: 28px">删除此响应</el-button>
            </el-col>
          </el-row>
          <el-form-item label="响应体" size="small">
            <el-input v-model="resp.body" type="textarea" :rows="6" placeholder='{"code": 0, "data": {}}' />
          </el-form-item>
        </div>

        <div style="margin-top: 24px; text-align: right">
          <el-button @click="$router.back()">取消</el-button>
          <el-button type="primary" @click="saveRule" :loading="saving">保存规则</el-button>
        </div>
      </el-form>
    </el-card>
  </div>
</template>

<script>
import { ref, reactive, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useRulesStore } from '@/store'
import api from '@/api'

export default {
  name: 'RuleEditorPage',
  setup() {
    const route = useRoute()
    const router = useRouter()
    const rulesStore = useRulesStore()
    const rule = ref(null)
    const loading = ref(true)
    const saving = ref(false)
    const allEnvironments = ref([])

    const form = reactive({
      name: '', protocol: 'http', host: '', port: null,
      serviceName: '', priority: 100, enabled: true,
      tagsStr: '', environments: [], conditions: [], responses: []
    })

    onMounted(async () => {
      const id = route.params.id
      await rulesStore.fetchRule(id)
      rule.value = rulesStore.currentRule
      if (rule.value) {
        form.name = rule.value.name || ''
        form.protocol = rule.value.protocol || 'http'
        form.host = rule.value.host || ''
        form.port = rule.value.port
        form.serviceName = rule.value.serviceName || ''
        form.priority = rule.value.priority || 100
        form.enabled = rule.value.enabled !== false
        form.tagsStr = (rule.value.tags || []).join(',')
        form.environments = rule.value.environments || []
        form.conditions = JSON.parse(JSON.stringify(rule.value.conditions || []))
        form.responses = JSON.parse(JSON.stringify(rule.value.responses || []))
        if (form.responses.length === 0) {
          form.responses.push({ name: '默认', statusCode: 200, delayMs: 0, body: '' })
        }
      }
      loading.value = false
    })

    function addCondition() {
      form.conditions.push({ type: 'method', operator: 'equals', key: '', value: '', caseSensitive: true })
    }

    function removeCondition(idx) {
      form.conditions.splice(idx, 1)
    }

    function addResponse() {
      form.responses.push({ name: '', statusCode: 200, delayMs: 0, body: '' })
    }

    function removeResponse(idx) {
      form.responses.splice(idx, 1)
    }

    async function saveRule() {
      saving.value = true
      const data = {
        name: form.name,
        protocol: form.protocol,
        host: form.host || null,
        port: form.port,
        serviceName: form.serviceName || null,
        priority: form.priority,
        enabled: form.enabled,
        tags: form.tagsStr ? form.tagsStr.split(',').map(s => s.trim()).filter(Boolean) : [],
        environments: form.environments || [],
        conditions: form.conditions,
        responses: form.responses.filter(r => r.name || r.body)
      }

      await rulesStore.updateRule(route.params.id, data)
      saving.value = false
      router.back()
    }

    async function loadEnvironments() {
      const res = await api.getEnvironments()
      if (res.success) allEnvironments.value = res.data
    }

    loadEnvironments()

    return { rule, loading, saving, form, allEnvironments, addCondition, removeCondition, addResponse, removeResponse, saveRule }
  }
}
</script>

<style scoped>
.page-header { display: flex; align-items: center; gap: 8px; }
.page-header h2 { font-size: 20px; font-weight: 600; color: #303133; }
</style>
