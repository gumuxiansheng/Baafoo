<template>
  <div class="rule-editor-page">
    <div class="page-header">
      <el-button text @click="$router.back()">
        <el-icon><ArrowLeft /></el-icon> 返回
      </el-button>
      <h2>{{ isNew ? '新建规则' : '编辑规则' }}</h2>
    </div>

    <el-card shadow="never" style="margin-top: 16px" v-if="isNew || rule" v-loading="loading">
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
        <el-row :gutter="20">
          <el-col :span="8">
            <el-form-item label="Faker Seed">
              <el-input-number v-model="form.fakerSeed" :min="0" :step="1" placeholder="留空随机" />
              <div style="font-size: 12px; color: #909399; margin-top: 4px">设置后该规则所有 Faker 函数使用相同种子，可复现数据</div>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="计数器重置">
              <el-input-number v-model="form.requestCountReset" :min="0" :step="1" placeholder="不重置" />
              <div style="font-size: 12px; color: #909399; margin-top: 4px">达到该次数后计数器归零（循环模式），0 表示不重置</div>
            </el-form-item>
          </el-col>
          <el-col :span="8" v-if="!isNew && form.protocol === 'http'">
            <el-form-item label="状态重置">
              <el-button size="small" @click="resetRuleState" v-if="authStore.canWriteRule">重置请求计数器</el-button>
              <div style="font-size: 12px; color: #909399; margin-top: 4px">清空该规则的 requestCount 计数器</div>
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="标签">
          <el-input v-model="form.tagsStr" placeholder="逗号分隔" />
        </el-form-item>
        <el-form-item label="生效环境">
          <div v-if="inheritedEnvs.length > 0" style="margin-bottom: 6px">
            <el-tag v-for="env in inheritedEnvs" :key="env" size="small" type="warning" closable disable-transitions style="margin-right: 4px; margin-bottom: 2px">
              {{ env }} (场景集继承)
            </el-tag>
          </div>
          <el-select v-model="form.environments" multiple filterable allow-create default-first-option placeholder="选择或输入环境名" style="width: 100%">
            <el-option v-for="env in allEnvironments" :key="env.name" :label="env.name" :value="env.name" />
          </el-select>
          <div style="font-size: 12px; color: #909399; margin-top: 4px">
            未选择环境时规则不生效，需显式关联环境后才参与匹配
            <span v-if="inheritedEnvs.length > 0" style="color: #E6A23C">；橙色标签为场景集继承的环境，不可删除</span>
          </div>
        </el-form-item>

        <!-- Match Conditions -->
        <el-divider content-position="left">匹配条件
          <el-button size="small" @click="addCondition" v-if="authStore.canWriteRule">+ 添加条件</el-button>
          <el-button size="small" @click="addGraphqlHelper" v-if="authStore.canWriteRule && isGraphqlPath">+ GraphQL 快捷</el-button>
        </el-divider>
        <div v-if="isGraphqlPath" class="graphql-hint">
          <el-alert type="info" :closable="false" show-icon>
            <template #title>
              检测到 GraphQL 路径，可使用"GraphQL 快捷"按钮快速添加 operationName / operationType 匹配条件
            </template>
          </el-alert>
        </div>
        <div v-if="form.conditions.length === 0" style="color: #909399; font-size: 12px; margin-bottom: 8px">
          未添加条件时，该规则对所有请求生效（受主机/端口/环境约束）
        </div>
        <div v-for="(cond, idx) in form.conditions" :key="'cond-' + idx" style="margin-bottom: 8px">
          <el-row :gutter="10">
            <el-col :span="4">
              <el-select v-model="cond.type" size="small" @change="onConditionTypeChange(cond)">
                <el-option label="Method" value="method" />
                <el-option label="Path" value="path" />
                <el-option label="Header" value="header" />
                <el-option label="Query" value="query" />
                <el-option label="Body" value="body" />
                <el-option label="Body包含" value="bodyContains" />
                <el-option label="JSONPath" value="bodyJsonPath" />
                <el-option label="请求次数" value="requestCount" />
                <el-option label="GraphQL OpName" value="graphqlOperationName" />
                <el-option label="GraphQL OpType" value="graphqlOperationType" />
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
                <el-option label="大于" value="greaterThan" v-if="cond.type === 'requestCount'" />
                <el-option label="小于" value="lessThan" v-if="cond.type === 'requestCount'" />
                <el-option label="区间" value="range" v-if="cond.type === 'requestCount'" />
                <el-option label="取模" value="mod" v-if="cond.type === 'requestCount'" />
              </el-select>
            </el-col>
            <el-col :span="4" v-if="cond.type === 'header' || cond.type === 'query'">
              <el-input v-model="cond.key" size="small" placeholder="Key" />
            </el-col>
            <el-col :span="cond.type === 'header' || cond.type === 'query' ? 8 : 12" v-if="cond.operator !== 'exists'">
              <el-input v-model="cond.value" size="small" :placeholder="getValuePlaceholder(cond)" />
            </el-col>
            <el-col :span="4">
              <el-button size="small" type="danger" text @click="removeCondition(idx)" v-if="authStore.canWriteRule">删除</el-button>
            </el-col>
          </el-row>
        </div>

        <!-- Responses -->
        <el-divider content-position="left">响应分支
          <el-button size="small" @click="addResponse" v-if="authStore.canWriteRule">+ 添加响应分支</el-button>
        </el-divider>
        <div v-if="form.responses.length === 0" style="color: #909399; font-size: 12px; margin-bottom: 8px">
          请至少添加一个响应分支，第一个无条件的响应将作为默认响应
        </div>
        <div v-for="(resp, idx) in form.responses" :key="'resp-' + idx"
             class="response-card">
          <div class="response-card-header">
            <span class="response-card-title">响应 #{{ idx + 1 }}{{ resp.name ? ' - ' + resp.name : '' }}</span>
            <div>
              <el-tag v-if="!resp.condition" size="small" type="info">默认响应</el-tag>
              <el-tag v-else size="small" type="warning">条件响应</el-tag>
              <el-button size="small" type="danger" text @click="removeResponse(idx)" style="margin-left: 8px" v-if="authStore.canWriteRule">删除</el-button>
            </div>
          </div>

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
            <el-col :span="4">
              <el-form-item label="编码" size="small">
                <el-select v-model="resp.charset" size="small" clearable placeholder="UTF-8" style="width: 100%">
                  <el-option label="UTF-8" value="UTF-8" />
                  <el-option label="GBK" value="GBK" />
                  <el-option label="GB2312" value="GB2312" />
                  <el-option label="Big5" value="Big5" />
                  <el-option label="ISO-8859-1" value="ISO-8859-1" />
                  <el-option label="Shift_JIS" value="Shift_JIS" />
                  <el-option label="EUC-KR" value="EUC-KR" />
                  <el-option label="Windows-1252" value="Windows-1252" />
                </el-select>
              </el-form-item>
            </el-col>
          </el-row>

          <!-- Response Condition -->
          <div class="response-condition-section">
            <el-row :gutter="10" align="middle">
              <el-col :span="4">
                <span style="font-size: 12px; color: #606266; font-weight: 500">响应匹配条件</span>
              </el-col>
              <el-col :span="20">
                <el-button v-if="!resp.condition" size="small" @click="addResponseCondition(resp)">+ 添加匹配条件</el-button>
                <el-button v-else size="small" type="danger" text @click="resp.condition = null">移除条件</el-button>
                <span v-if="!resp.condition" style="font-size: 12px; color: #909399; margin-left: 8px">
                  无条件时作为默认响应，当其他响应分支均不匹配时使用
                </span>
              </el-col>
            </el-row>
            <div v-if="resp.condition" style="margin-top: 8px">
              <el-row :gutter="10">
                <el-col :span="4">
                  <el-select v-model="resp.condition.type" size="small" @change="onConditionTypeChange(resp.condition)">
                    <el-option label="Method" value="method" />
                    <el-option label="Path" value="path" />
                    <el-option label="Header" value="header" />
                    <el-option label="Query" value="query" />
                    <el-option label="Body" value="body" />
                    <el-option label="Body包含" value="bodyContains" />
                    <el-option label="JSONPath" value="bodyJsonPath" />
                    <el-option label="请求次数" value="requestCount" />
                    <el-option label="GraphQL OpName" value="graphqlOperationName" />
                    <el-option label="GraphQL OpType" value="graphqlOperationType" />
                  </el-select>
                </el-col>
                <el-col :span="3">
                  <el-select v-model="resp.condition.operator" size="small">
                    <el-option label="等于" value="equals" />
                    <el-option label="包含" value="contains" />
                    <el-option label="开头" value="startsWith" />
                    <el-option label="结尾" value="endsWith" />
                    <el-option label="正则" value="regex" />
                    <el-option label="存在" value="exists" />
                    <el-option label="大于" value="greaterThan" v-if="resp.condition.type === 'requestCount'" />
                    <el-option label="小于" value="lessThan" v-if="resp.condition.type === 'requestCount'" />
                    <el-option label="区间" value="range" v-if="resp.condition.type === 'requestCount'" />
                    <el-option label="取模" value="mod" v-if="resp.condition.type === 'requestCount'" />
                  </el-select>
                </el-col>
                <el-col :span="4" v-if="resp.condition.type === 'header' || resp.condition.type === 'query'">
                  <el-input v-model="resp.condition.key" size="small" placeholder="Key" />
                </el-col>
                <el-col :span="resp.condition.type === 'header' || resp.condition.type === 'query' ? 8 : 12" v-if="resp.condition.operator !== 'exists'">
                  <el-input v-model="resp.condition.value" size="small" :placeholder="getValuePlaceholder(resp.condition)" />
                </el-col>
              </el-row>
            </div>
          </div>

          <!-- Response Headers -->
          <div class="response-headers-section">
            <el-row :gutter="10" align="middle">
              <el-col :span="4">
                <span style="font-size: 12px; color: #606266; font-weight: 500">响应头</span>
              </el-col>
              <el-col :span="20">
                <el-button size="small" @click="addResponseHeader(resp)">+ 添加响应头</el-button>
              </el-col>
            </el-row>
            <div v-for="(h, hIdx) in getResponseHeaders(resp)" :key="'h-' + hIdx" style="margin-top: 6px">
              <el-row :gutter="10">
                <el-col :span="6">
                  <el-input v-model="h.key" size="small" placeholder="Header名称" />
                </el-col>
                <el-col :span="10">
                  <el-input v-model="h.value" size="small" placeholder="Header值 (支持模板变量)" />
                </el-col>
                <el-col :span="4">
                  <el-button size="small" type="danger" text @click="removeResponseHeader(resp, hIdx)">删除</el-button>
                </el-col>
              </el-row>
            </div>
          </div>

          <!-- Response Body -->
          <el-form-item label="响应体" size="small">
            <el-input v-model="resp.body" type="textarea" :rows="6" :placeholder="bodyPlaceholder" />
            <div style="font-size: 12px; color: #909399; margin-top: 4px" v-html="templateVarHint"></div>
            <!-- Faker Quick Insert -->
            <div v-if="showFakerRef" class="faker-ref-panel">
              <div class="faker-ref-title">动态数据函数 — 点击插入</div>
              <div class="faker-ref-group">
                <div class="faker-ref-label">个人信息</div>
                <el-tag v-for="fn in fakerGroups.personal" :key="fn" size="small" class="faker-tag" @click="insertFakerVar(resp, fn)" v-text="'{{' + fn + '}}'"></el-tag>
              </div>
              <div class="faker-ref-group">
                <div class="faker-ref-label">地址</div>
                <el-tag v-for="fn in fakerGroups.address" :key="fn" size="small" class="faker-tag" @click="insertFakerVar(resp, fn)" v-text="'{{' + fn + '}}'"></el-tag>
              </div>
              <div class="faker-ref-group">
                <div class="faker-ref-label">公司/网络</div>
                <el-tag v-for="fn in fakerGroups.network" :key="fn" size="small" class="faker-tag" @click="insertFakerVar(resp, fn)" v-text="'{{' + fn + '}}'"></el-tag>
              </div>
              <div class="faker-ref-group">
                <div class="faker-ref-label">数字/时间</div>
                <el-tag v-for="fn in fakerGroups.numeric" :key="fn" size="small" class="faker-tag" @click="insertFakerVar(resp, fn)" v-text="'{{' + fn + '}}'"></el-tag>
              </div>
              <div class="faker-ref-group">
                <div class="faker-ref-label">其他</div>
                <el-tag v-for="fn in fakerGroups.misc" :key="fn" size="small" class="faker-tag" @click="insertFakerVar(resp, fn)" v-text="'{{' + fn + '}}'"></el-tag>
              </div>
            </div>
          </el-form-item>
        </div>

        <!-- Fault Injection -->
        <el-divider content-position="left">故障注入
          <el-switch v-model="form.faultInjectionEnabled" size="small" v-if="authStore.canWriteRule" />
        </el-divider>
        <div v-if="form.faultInjectionEnabled" class="fault-injection-panel">
          <div style="font-size: 12px; color: #909399; margin-bottom: 8px">
            按 declaration 顺序评估，首个 probability 命中的 fault 生效；全部未命中则走正常响应
          </div>
          <div v-for="(fault, fIdx) in form.faults" :key="'fault-' + fIdx" class="fault-card">
            <el-row :gutter="10" align="middle">
              <el-col :span="5">
                <el-select v-model="fault.type" size="small" placeholder="故障类型">
                  <el-option label="HTTP 错误" value="HTTP_ERROR" />
                  <el-option label="延迟" value="DELAY" />
                  <el-option label="连接重置" value="CONNECTION_RESET" />
                  <el-option label="读超时" value="READ_TIMEOUT" />
                </el-select>
              </el-col>
              <el-col :span="4">
                <el-input-number v-model="fault.probability" :min="0" :max="1" :step="0.1" :precision="2" size="small" placeholder="概率" />
                <div style="font-size: 11px; color: #909399">概率 (0-1)</div>
              </el-col>
              <el-col :span="6" v-if="fault.type === 'HTTP_ERROR'">
                <el-input v-model="fault.statusCodesStr" size="small" placeholder="如: 503,504" />
                <div style="font-size: 11px; color: #909399">状态码列表（逗号分隔，等概率分配）</div>
              </el-col>
              <el-col :span="6" v-if="fault.type === 'DELAY'">
                <el-input-number v-model="fault.delayMs" :min="0" :max="60000" size="small" />
                <div style="font-size: 11px; color: #909399">延迟毫秒</div>
              </el-col>
              <el-col :span="6" v-if="fault.type === 'CONNECTION_RESET' || fault.type === 'READ_TIMEOUT'">
                <span style="font-size: 12px; color: #909399">
                  {{ fault.type === 'CONNECTION_RESET' ? '响应前关闭连接 (RST)' : '收到请求后不响应，等待客户端超时' }}
                </span>
              </el-col>
              <el-col :span="4" style="text-align: right">
                <el-button size="small" type="danger" text @click="removeFault(fIdx)" v-if="authStore.canWriteRule">删除</el-button>
              </el-col>
            </el-row>
          </div>
          <el-button size="small" @click="addFault" v-if="authStore.canWriteRule" style="margin-top: 8px">+ 添加故障</el-button>
        </div>

        <div style="margin-top: 24px; text-align: right">
          <el-button @click="$router.back()">取消</el-button>
          <el-button type="primary" @click="saveRule" :loading="saving" v-if="authStore.canWriteRule">保存规则</el-button>
          <span v-if="!authStore.canWriteRule" style="color: #909399; font-size: 14px; margin-left: 12px">当前角色无编辑权限</span>
        </div>
      </el-form>
    </el-card>
  </div>
</template>

<script>
import { ref, reactive, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useRulesStore, useAuthStore } from '@/store'
import { ElMessage } from 'element-plus'
import api from '@/api'

export default {
  name: 'RuleEditorPage',
  setup() {
    const route = useRoute()
    const router = useRouter()
    const rulesStore = useRulesStore()
    const authStore = useAuthStore()
    const rule = ref(null)
    const loading = ref(false)
    const saving = ref(false)
    const allEnvironments = ref([])
    const inheritedEnvs = ref([])
    const showFakerRef = ref(false)

    const fakerGroups = {
      personal: ['faker.name', 'faker.firstName', 'faker.lastName', 'faker.phone', 'faker.email', 'faker.idCard'],
      address: ['faker.address', 'faker.province', 'faker.city', 'faker.zipCode', 'faker.street'],
      network: ['faker.company', 'faker.url', 'faker.ip', 'faker.ipv6', 'faker.mac', 'faker.userAgent'],
      numeric: ['faker.int', 'faker.int.1.100', 'faker.float', 'faker.boolean', 'faker.uuid', 'faker.timestamp', 'faker.date', 'faker.dateTime'],
      misc: ['faker.hex', 'faker.hexColor', 'faker.alphaNumeric', 'faker.locale', 'faker.statusCode', 'faker.randomElement', 'faker.regexify']
    }

    const isNew = computed(() => route.params.id === 'new')

    const isGraphqlPath = computed(() => {
      const pathCond = form.conditions.find(c => c.type === 'path')
      return pathCond && pathCond.value && pathCond.value.includes('/graphql')
    })

    const envTagType = (val) => {
      return inheritedEnvs.value.includes(val) ? 'warning' : ''
    }

    const templateVarHint = '支持模板变量: <code>{{request.body.xxx}}</code> <code>{{request.header.xxx}}</code> <code>{{request.query.xxx}}</code> <code>{{request.path}}</code><br/>动态数据: <code>{{faker.phone}}</code> <code>{{faker.email}}</code> <code>{{faker.name}}</code> <code>{{faker.address}}</code> <code>{{faker.idCard}}</code> <code>{{faker.uuid}}</code> <code>{{faker.int.1.100}}</code> <a href="javascript:void(0)" onclick="document.dispatchEvent(new CustomEvent(\'toggle-faker-ref\'))" style="color:#409EFF">更多函数...</a>'

    // Listen for toggle-faker-ref event from v-html link
    if (typeof document !== 'undefined') {
      document.addEventListener('toggle-faker-ref', () => {
        showFakerRef.value = !showFakerRef.value
      })
    }

    const bodyPlaceholder = '{"code": 0, "data": {}}'

    const form = reactive({
      name: '', protocol: 'http', host: '', port: null,
      serviceName: '', priority: 100, enabled: true,
      tagsStr: '', environments: [], conditions: [], responses: [],
      fakerSeed: null, requestCountReset: null,
      faultInjectionEnabled: false, faults: []
    })

    onMounted(async () => {
      if (isNew.value) {
        form.responses.push({ name: '默认', statusCode: 200, delayMs: 0, body: '', headers: {}, condition: null })
        loading.value = false
        return
      }

      loading.value = true
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
        form.fakerSeed = rule.value.fakerSeed || null
        form.requestCountReset = rule.value.requestCountReset || null
        // Load fault injection
        const fi = rule.value.faultInjection
        if (fi && fi.faults && fi.faults.length > 0) {
          form.faultInjectionEnabled = true
          form.faults = fi.faults.map(f => ({
            type: f.type || 'HTTP_ERROR',
            probability: f.probability != null ? f.probability : 1.0,
            statusCodesStr: (f.statusCodes || []).join(','),
            delayMs: f.delayMs || 0
          }))
        } else {
          form.faultInjectionEnabled = false
          form.faults = []
        }
        if (form.responses.length === 0) {
          form.responses.push({ name: '默认', statusCode: 200, delayMs: 0, body: '', headers: {}, condition: null })
        }
        const res = await api.getInheritedEnvironments(id)
        if (res.success && res.data) {
          inheritedEnvs.value = res.data
          form.environments = (form.environments || []).filter(e => !inheritedEnvs.value.includes(e))
        }
      }
      loading.value = false
    })

    function onConditionTypeChange(cond) {
      if (cond.type === 'bodyJsonPath') {
        cond.operator = 'equals'
      } else if (cond.type === 'bodyContains') {
        cond.operator = 'contains'
      } else if (cond.type === 'graphqlOperationName' || cond.type === 'graphqlOperationType') {
        cond.operator = 'equals'
      } else if (cond.type === 'requestCount') {
        cond.operator = 'equals'
      }
    }

    function insertFakerVar(resp, variable) {
      const ta = document.querySelector('.response-card textarea')
      if (ta && resp) {
        const start = ta.selectionStart
        const end = ta.selectionEnd
        const before = (resp.body || '').substring(0, start)
        const after = (resp.body || '').substring(end)
        resp.body = before + '{{' + variable + '}}' + after
      } else if (resp) {
        resp.body = (resp.body || '') + '{{' + variable + '}}'
      }
    }

    function getValuePlaceholder(cond) {
      if (!cond) return '值'
      switch (cond.type) {
        case 'method': return '如: GET, POST'
        case 'path': return '如: /api/users'
        case 'header': return 'Header值'
        case 'query': return '参数值'
        case 'body': return '请求体内容'
        case 'bodyContains': return '包含的文本'
        case 'bodyJsonPath': return '如: $.user.id'
        case 'requestCount':
          if (cond.operator === 'range') return '如: 1,3 (第1到3次)'
          if (cond.operator === 'mod') return '如: 3,0 (每3次,余0触发)'
          return '如: 3'
        case 'graphqlOperationName': return '如: GetUser'
        case 'graphqlOperationType': return '如: query / mutation / subscription'
        default: return '值'
      }
    }

    function addCondition() {
      form.conditions.push({ type: 'method', operator: 'equals', key: '', value: '', caseSensitive: true })
    }

    function removeCondition(idx) {
      form.conditions.splice(idx, 1)
    }

    function addGraphqlHelper() {
      form.conditions.push({ type: 'graphqlOperationName', operator: 'equals', key: '', value: '', caseSensitive: true })
      form.conditions.push({ type: 'graphqlOperationType', operator: 'equals', key: '', value: 'query', caseSensitive: true })
    }

    function addFault() {
      form.faults.push({ type: 'HTTP_ERROR', probability: 0.5, statusCodesStr: '503', delayMs: 0 })
    }

    function removeFault(idx) {
      form.faults.splice(idx, 1)
    }

    async function resetRuleState() {
      try {
        const res = await api.resetRuleState(route.params.id)
        if (res.success) {
          ElMessage.success('请求计数器已重置')
        } else {
          ElMessage.error(res.message || '重置失败')
        }
      } catch (e) {
        ElMessage.error('重置失败: ' + (e.message || e))
      }
    }

    function addResponse() {
      form.responses.push({ name: '', statusCode: 200, delayMs: 0, body: '', headers: {}, condition: null })
    }

    function removeResponse(idx) {
      form.responses.splice(idx, 1)
    }

    function addResponseCondition(resp) {
      resp.condition = { type: 'body', operator: 'equals', key: '', value: '', caseSensitive: true }
    }

    function getResponseHeaders(resp) {
      if (!resp._headerPairs) {
        resp._headerPairs = []
        if (resp.headers && typeof resp.headers === 'object') {
          for (const [key, value] of Object.entries(resp.headers)) {
            resp._headerPairs.push({ key, value })
          }
        }
      }
      return resp._headerPairs
    }

    function addResponseHeader(resp) {
      if (!resp._headerPairs) {
        getResponseHeaders(resp)
      }
      resp._headerPairs.push({ key: '', value: '' })
    }

    function removeResponseHeader(resp, hIdx) {
      resp._headerPairs.splice(hIdx, 1)
    }

    function buildHeadersFromPairs(pairs) {
      const headers = {}
      for (const h of pairs) {
        if (h.key && h.key.trim()) {
          headers[h.key.trim()] = h.value || ''
        }
      }
      return Object.keys(headers).length > 0 ? headers : null
    }

    async function saveRule() {
      saving.value = true
      const responsesData = form.responses.map(r => {
        const entry = {
          name: r.name,
          statusCode: r.statusCode,
          delayMs: r.delayMs,
          body: r.body,
          headers: r._headerPairs ? buildHeadersFromPairs(r._headerPairs) : (r.headers || null),
          condition: r.condition || null
        }
        return entry
      })

      // Build fault injection config
      let faultInjection = null
      if (form.faultInjectionEnabled && form.faults.length > 0) {
        faultInjection = {
          faults: form.faults.map(f => {
            const fault = {
              type: f.type,
              probability: f.probability != null ? f.probability : 1.0
            }
            if (f.type === 'HTTP_ERROR') {
              fault.statusCodes = (f.statusCodesStr || '').split(',').map(s => parseInt(s.trim())).filter(n => !isNaN(n) && n > 0)
              if (fault.statusCodes.length === 0) fault.statusCodes = [500]
            } else if (f.type === 'DELAY') {
              fault.delayMs = f.delayMs || 0
            }
            return fault
          })
        }
      }

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
        responses: responsesData,
        fakerSeed: form.fakerSeed || null,
        requestCountReset: form.requestCountReset || null,
        faultInjection: faultInjection
      }

      if (isNew.value) {
        await rulesStore.createRule(data)
      } else {
        await rulesStore.updateRule(route.params.id, data)
      }
      saving.value = false
      router.back()
    }

    async function loadEnvironments() {
      const res = await api.getEnvironments()
      if (res.success) allEnvironments.value = res.data
    }

    loadEnvironments()

    return {
      isNew, rule, loading, saving, form, allEnvironments, inheritedEnvs, envTagType, templateVarHint, bodyPlaceholder,
      showFakerRef, fakerGroups, insertFakerVar,
      isGraphqlPath, addGraphqlHelper,
      addCondition, removeCondition,
      addResponse, removeResponse, addResponseCondition,
      getResponseHeaders, addResponseHeader, removeResponseHeader,
      addFault, removeFault, resetRuleState,
      onConditionTypeChange, getValuePlaceholder, saveRule, authStore
    }
  }
}
</script>

<style scoped>
.page-header { display: flex; align-items: center; gap: 8px; }
.page-header h2 { font-size: 20px; font-weight: 600; color: #303133; }
.response-card {
  border: 1px solid #e8e8e8;
  border-radius: 8px;
  padding: 16px;
  margin-bottom: 12px;
}
.response-card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
  padding-bottom: 8px;
  border-bottom: 1px dashed #ebeef5;
}
.response-card-title {
  font-size: 14px;
  font-weight: 600;
  color: #303133;
}
.response-condition-section {
  background: #fafafa;
  border-radius: 6px;
  padding: 10px 12px;
  margin-bottom: 12px;
}
.response-headers-section {
  margin-bottom: 12px;
}
.faker-ref-panel {
  margin-top: 8px;
  padding: 10px 12px;
  background: #f5f7fa;
  border-radius: 6px;
  border: 1px solid #e8e8e8;
}
.faker-ref-title {
  font-size: 13px;
  font-weight: 600;
  color: #303133;
  margin-bottom: 8px;
}
.faker-ref-group {
  margin-bottom: 6px;
}
.faker-ref-label {
  font-size: 12px;
  color: #606266;
  margin-bottom: 4px;
  font-weight: 500;
}
.faker-tag {
  margin-right: 4px;
  margin-bottom: 2px;
  cursor: pointer;
  transition: all 0.2s;
}
.faker-tag:hover {
  color: #409EFF;
  border-color: #409EFF;
}
.graphql-hint {
  margin-bottom: 12px;
}
.fault-injection-panel {
  background: #fafafa;
  border-radius: 6px;
  padding: 12px;
  border: 1px solid #ebeef5;
}
.fault-card {
  background: #fff;
  border: 1px solid #e8e8e8;
  border-radius: 6px;
  padding: 10px 12px;
  margin-bottom: 8px;
}
</style>
