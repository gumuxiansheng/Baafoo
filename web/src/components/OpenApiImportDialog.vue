<template>
  <el-dialog
    v-model="visible"
    title="导入 OpenAPI 规范"
    width="80%"
    top="5vh"
    :close-on-click-modal="false"
    @closed="handleClosed"
  >
    <el-steps :active="activeStep" finish-status="success" align-center style="margin-bottom: 24px">
      <el-step title="上传规范" />
      <el-step title="预览规则" />
      <el-step title="选择环境" />
      <el-step title="导入结果" />
    </el-steps>

    <!-- Step 1: Upload -->
    <div v-if="activeStep === 0" class="step-content">
      <el-alert type="info" :closable="false" show-icon style="margin-bottom: 16px">
        <template #title>
          支持 OpenAPI 3.0 JSON 格式（Phase 1）。Swagger 2.0 和 YAML 将在后续版本支持。
        </template>
      </el-alert>

      <el-upload
        ref="uploadRef"
        :auto-upload="false"
        :limit="1"
        accept=".json"
        :on-change="handleFileChange"
        :on-exceed="handleExceed"
        drag
      >
        <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
        <div class="el-upload__text">拖拽文件到此处，或<em>点击上传</em></div>
        <template #tip>
          <div class="el-upload__tip">仅支持 .json 格式的 OpenAPI 3.0 规范文件</div>
        </template>
      </el-upload>

      <el-divider>或粘贴 JSON 内容</el-divider>

      <el-input
        v-model="jsonContent"
        type="textarea"
        :rows="10"
        placeholder='{"openapi": "3.0.0", "paths": {...}}'
        style="font-family: monospace"
      />

      <div v-if="parseError" style="margin-top: 12px">
        <el-alert type="error" :closable="false" show-icon>
          <template #title>{{ parseError }}</template>
        </el-alert>
      </div>
    </div>

    <!-- Step 2: Preview -->
    <div v-if="activeStep === 1" class="step-content" v-loading="loading">
      <el-alert v-if="importResult" type="success" :closable="false" show-icon style="margin-bottom: 12px">
        <template #title>
          解析成功：生成 {{ importResult.generatedCount }} 条规则，跳过 {{ importResult.skippedCount }} 条
        </template>
      </el-alert>

      <el-alert type="info" :closable="false" show-icon style="margin-bottom: 12px">
        <template #title>
          当前版本将导入全部 {{ previewRules.length }} 条解析规则（不支持选择性导入）
        </template>
      </el-alert>

      <el-table :data="previewRules" stripe size="small">
        <el-table-column type="index" label="#" width="50" />
        <el-table-column prop="name" label="规则名称" min-width="180" show-overflow-tooltip />
        <el-table-column label="方法" width="80">
          <template #default="{ row }">
            <el-tag size="small" :type="methodTagType(getRuleMethod(row))">{{ getRuleMethod(row) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="路径" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">
            {{ getRulePath(row) }}
          </template>
        </el-table-column>
        <el-table-column label="状态码" width="80" align="center">
          <template #default="{ row }">
            {{ getRuleStatusCode(row) }}
          </template>
        </el-table-column>
        <el-table-column label="Body 预览" min-width="250" show-overflow-tooltip>
          <template #default="{ row }">
            <span style="font-family: monospace; font-size: 12px; color: var(--bf-text-secondary)">{{ getRuleBodyPreview(row) }}</span>
          </template>
        </el-table-column>
      </el-table>

      <div v-if="importResult && importResult.warnings && importResult.warnings.length > 0" style="margin-top: 12px">
        <el-alert type="warning" :closable="false" show-icon>
          <template #title>解析警告 ({{ importResult.warnings.length }})</template>
          <div v-for="(w, i) in importResult.warnings" :key="i" style="font-size: 12px; margin-top: 4px">
            {{ w }}
          </div>
        </el-alert>
      </div>
    </div>

    <!-- Step 3: Environment -->
    <div v-if="activeStep === 2" class="step-content">
      <el-form label-width="120px">
        <el-form-item label="关联环境">
          <el-select v-model="selectedEnvironments" multiple filterable allow-create default-first-option placeholder="选择或输入环境名（可不选）" style="width: 100%">
            <el-option v-for="env in environments" :key="env.name" :label="env.name" :value="env.name" />
          </el-select>
          <div style="font-size: 12px; color: var(--bf-text-muted); margin-top: 4px">
            未选择环境时规则不生效，需显式关联环境后才参与匹配
          </div>
        </el-form-item>
        <el-form-item label="规则 ID 前缀">
          <el-input v-model="ruleIdPrefix" placeholder="openapi-" style="width: 200px" />
          <div style="font-size: 12px; color: var(--bf-text-muted); margin-top: 4px">自动生成规则 ID 的前缀</div>
        </el-form-item>
        <el-form-item label="导入数量">
          <span>共 {{ previewRules.length }} 条规则将被导入</span>
        </el-form-item>
      </el-form>
    </div>

    <!-- Step 4: Result -->
    <div v-if="activeStep === 3" class="step-content" v-loading="importing">
      <template v-if="importResult">
        <el-result
          :icon="importResult.failedCount > 0 ? 'warning' : 'success'"
          :title="`导入完成`"
          :sub-title="`成功 ${importResult.savedCount || importResult.generatedCount} 条，失败 ${importResult.failedCount || 0} 条，冲突 ${importResult.conflictCount || 0} 条`"
        />
        <div v-if="importResult.warnings && importResult.warnings.length > 0" style="margin-top: 16px">
          <el-alert type="warning" :closable="false" show-icon>
            <template #title>警告详情</template>
            <div v-for="(w, i) in importResult.warnings" :key="i" style="font-size: 12px; margin-top: 4px">
              {{ w }}
            </div>
          </el-alert>
        </div>
      </template>
    </div>

    <template #footer>
      <el-button @click="handleClose">取消</el-button>
      <el-button v-if="activeStep > 0 && activeStep < 3" @click="activeStep--">上一步</el-button>
      <el-button v-if="activeStep === 0" type="primary" @click="parseOpenApi" :loading="loading" :disabled="!jsonContent">解析</el-button>
      <el-button v-if="activeStep === 1" type="primary" @click="activeStep = 2" :disabled="previewRules.length === 0">下一步</el-button>
      <el-button v-if="activeStep === 2" type="primary" @click="doImport" :loading="importing">确认导入</el-button>
      <el-button v-if="activeStep === 3" type="primary" @click="handleClose">完成</el-button>
    </template>
  </el-dialog>
</template>

<script>
import { ref, reactive, computed } from 'vue'
import { ElMessage } from 'element-plus'
import { UploadFilled } from '@element-plus/icons-vue'
import api from '@/api'

export default {
  name: 'OpenApiImportDialog',
  components: { UploadFilled },
  props: {
    modelValue: { type: Boolean, default: false }
  },
  emits: ['update:modelValue', 'imported'],
  setup(props, { emit }) {
    const visible = computed({
      get: () => props.modelValue,
      set: (val) => emit('update:modelValue', val)
    })

    const activeStep = ref(0)
    const jsonContent = ref('')
    const parseError = ref('')
    const loading = ref(false)
    const importing = ref(false)
    const importResult = ref(null)
    const previewRules = ref([])
    const environments = ref([])
    const selectedEnvironments = ref([])
    const ruleIdPrefix = ref('openapi-')

    function handleFileChange(file) {
      const reader = new FileReader()
      reader.onload = (e) => {
        jsonContent.value = e.target.result
        parseError.value = ''
      }
      reader.onerror = () => {
        ElMessage.error('文件读取失败')
      }
      reader.readAsText(file.raw)
    }

    function handleExceed() {
      ElMessage.warning('只能上传一个文件，请先移除已选文件')
    }

    async function parseOpenApi() {
      if (!jsonContent.value || !jsonContent.value.trim()) {
        parseError.value = '请输入或上传 OpenAPI JSON 内容'
        return
      }

      // Validate JSON
      try {
        JSON.parse(jsonContent.value)
      } catch (e) {
        parseError.value = 'JSON 格式错误: ' + e.message
        return
      }

      loading.value = true
      parseError.value = ''
      try {
        const res = await api.importOpenApi(jsonContent.value, { save: false })
        if (res.success && res.data) {
          importResult.value = res.data
          previewRules.value = res.data.rules || []
          activeStep.value = 1
        } else {
          parseError.value = res.message || '解析失败'
        }
      } catch (e) {
        parseError.value = e.message || '解析失败'
      } finally {
        loading.value = false
      }
    }

    async function doImport() {
      importing.value = true
      try {
        // Filter to only selected rules by re-parsing with save=true
        // The backend doesn't support selective import yet, so we import all and rely on conflict detection
        const envStr = selectedEnvironments.value.length > 0 ? selectedEnvironments.value.join(',') : ''
        const res = await api.importOpenApi(jsonContent.value, {
          environment: envStr,
          save: true,
          prefix: ruleIdPrefix.value
        })
        if (res.success && res.data) {
          importResult.value = res.data
          activeStep.value = 3
          emit('imported')
        } else {
          ElMessage.error(res.message || '导入失败')
        }
      } catch (e) {
        ElMessage.error('导入失败: ' + (e.message || e))
      } finally {
        importing.value = false
      }
    }

    function handleClose() {
      visible.value = false
    }

    /**
     * Called by el-dialog after the close transition completes (S11 fix).
     * Replaces the previous setTimeout(300) approach which could race with
     * a rapid re-open and wipe the new dialog's state.
     */
    function handleClosed() {
      activeStep.value = 0
      jsonContent.value = ''
      parseError.value = ''
      importResult.value = null
      previewRules.value = []
      selectedEnvironments.value = []
    }

    // Helper functions to extract data from rule objects
    function getRuleMethod(rule) {
      const conds = rule.conditions || []
      const methodCond = conds.find(c => c.type === 'method')
      return methodCond ? (methodCond.value || '').toUpperCase() : '-'
    }

    function getRulePath(rule) {
      const conds = rule.conditions || []
      const pathCond = conds.find(c => c.type === 'path')
      return pathCond ? pathCond.value : '-'
    }

    function getRuleStatusCode(rule) {
      const responses = rule.responses || []
      if (responses.length > 0 && responses[0].statusCode) {
        return responses[0].statusCode
      }
      return '-'
    }

    function getRuleBodyPreview(rule) {
      const responses = rule.responses || []
      if (responses.length > 0 && responses[0].body) {
        const body = responses[0].body
        return body.length > 80 ? body.substring(0, 80) + '...' : body
      }
      return '(空)'
    }

    function methodTagType(method) {
      const map = { GET: 'success', POST: 'warning', PUT: 'primary', DELETE: 'danger', PATCH: 'info' }
      return map[method] || ''
    }

    async function loadEnvironments() {
      try {
        const res = await api.getEnvironments()
        if (res.success && res.data) {
          environments.value = res.data
        }
      } catch (e) {
        // ignore
      }
    }

    loadEnvironments()

    return {
      visible, activeStep, jsonContent, parseError, loading, importing,
      importResult, previewRules, environments, selectedEnvironments, ruleIdPrefix,
      handleFileChange, handleExceed, parseOpenApi, doImport, handleClose, handleClosed,
      getRuleMethod, getRulePath, getRuleStatusCode, getRuleBodyPreview, methodTagType
    }
  }
}
</script>

<style scoped>
.step-content {
  min-height: 300px;
}
</style>
