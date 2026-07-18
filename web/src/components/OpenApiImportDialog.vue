<template>
  <el-dialog
    v-model="visible"
    :title="$t('openapi.importTitle')"
    width="80%"
    top="5vh"
    :close-on-click-modal="false"
    @closed="handleClosed"
  >
    <el-steps :active="activeStep" finish-status="success" align-center style="margin-bottom: 24px">
      <el-step :title="$t('openapi.uploadSpec')" />
      <el-step :title="$t('openapi.previewRules')" />
      <el-step :title="$t('openapi.selectEnv')" />
      <el-step :title="$t('openapi.importResult')" />
    </el-steps>

    <!-- Step 1: Upload -->
    <div v-if="activeStep === 0" class="step-content">
      <el-alert type="info" :closable="false" show-icon style="margin-bottom: 16px">
        <template #title>
          {{ $t('openapi.formatHint') }}
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
        <div class="el-upload__text">
          <span v-html="$t('openapi.dragHint')"></span>
        </div>
        <template #tip>
          <div class="el-upload__tip">{{ $t('openapi.fileHint') }}</div>
        </template>
      </el-upload>

      <el-divider>{{ $t('openapi.pasteJson') }}</el-divider>

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
          {{ $t('openapi.parseSuccess', { 0: importResult.generatedCount, 1: importResult.skippedCount }) }}
        </template>
      </el-alert>

      <el-alert type="info" :closable="false" show-icon style="margin-bottom: 12px">
        <template #title>
          {{ $t('openapi.importAllHint', { 0: previewRules.length }) }}
        </template>
      </el-alert>

      <el-table :data="previewRules" stripe size="small">
        <el-table-column type="index" label="#" width="50" />
        <el-table-column prop="name" :label="$t('rules.ruleName')" min-width="180" show-overflow-tooltip />
        <el-table-column :label="$t('recordings.method')" width="80">
          <template #default="{ row }">
            <el-tag size="small" :type="methodTagType(getRuleMethod(row))">{{ getRuleMethod(row) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="$t('recordings.path')" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">
            {{ getRulePath(row) }}
          </template>
        </el-table-column>
        <el-table-column :label="$t('recordings.statusCode')" width="80" align="center">
          <template #default="{ row }">
            {{ getRuleStatusCode(row) }}
          </template>
        </el-table-column>
        <el-table-column :label="$t('rules.templates.hint')" min-width="250" show-overflow-tooltip>
          <template #default="{ row }">
            <span style="font-family: monospace; font-size: 12px; color: var(--bf-text-secondary)">{{ getRuleBodyPreview(row) }}</span>
          </template>
        </el-table-column>
      </el-table>

      <div v-if="importResult && importResult.warnings && importResult.warnings.length > 0" style="margin-top: 12px">
        <el-alert type="warning" :closable="false" show-icon>
          <template #title>{{ $t('openapi.parseWarnings', { 0: importResult.warnings.length }) }}</template>
          <div v-for="(w, i) in importResult.warnings" :key="i" style="font-size: 12px; margin-top: 4px">
            {{ w }}
          </div>
        </el-alert>
      </div>
    </div>

    <!-- Step 3: Environment -->
    <div v-if="activeStep === 2" class="step-content">
      <el-form label-width="120px">
        <el-form-item :label="$t('openapi.envLabel')">
          <el-select v-model="selectedEnvironments" multiple filterable allow-create default-first-option :placeholder="$t('openapi.envPlaceholder')" style="width: 100%">
            <el-option v-for="env in environments" :key="env.name" :label="env.name" :value="env.name" />
          </el-select>
          <div style="font-size: 12px; color: var(--bf-text-muted); margin-top: 4px">
            {{ $t('openapi.envNoEffect') }}
          </div>
        </el-form-item>
        <el-form-item :label="$t('openapi.prefixLabel')">
          <el-input v-model="ruleIdPrefix" placeholder="openapi-" style="width: 200px" />
          <div style="font-size: 12px; color: var(--bf-text-muted); margin-top: 4px">{{ $t('openapi.prefixHint') }}</div>
        </el-form-item>
        <el-form-item :label="$t('openapi.import')">
          <span>{{ $t('openapi.importCount', { 0: previewRules.length }) }}</span>
        </el-form-item>
      </el-form>
    </div>

    <!-- Step 4: Result -->
    <div v-if="activeStep === 3" class="step-content" v-loading="importing">
      <template v-if="importResult">
        <el-result
          :icon="importResult.failedCount > 0 ? 'warning' : 'success'"
          :title="$t('openapi.importDone')"
          :sub-title="$t('openapi.importDoneDetail', { 0: (importResult.savedCount || importResult.generatedCount), 1: (importResult.failedCount || 0), 2: (importResult.conflictCount || 0) })"
        />
        <div v-if="importResult.warnings && importResult.warnings.length > 0" style="margin-top: 16px">
          <el-alert type="warning" :closable="false" show-icon>
            <template #title>{{ $t('openapi.warningsTitle') }}</template>
            <div v-for="(w, i) in importResult.warnings" :key="i" style="font-size: 12px; margin-top: 4px">
              {{ w }}
            </div>
          </el-alert>
        </div>
      </template>
    </div>

    <template #footer>
      <el-button @click="handleClose">{{ $t('openapi.cancel') }}</el-button>
      <el-button v-if="activeStep > 0 && activeStep < 3" @click="activeStep--">{{ $t('openapi.prevStep') }}</el-button>
      <el-button v-if="activeStep === 0" type="primary" @click="parseOpenApi" :loading="loading" :disabled="!jsonContent">{{ $t('openapi.parse') }}</el-button>
      <el-button v-if="activeStep === 1" type="primary" @click="activeStep = 2" :disabled="previewRules.length === 0">{{ $t('openapi.nextStep') }}</el-button>
      <el-button v-if="activeStep === 2" type="primary" @click="doImport" :loading="importing">{{ $t('openapi.confirmImport') }}</el-button>
      <el-button v-if="activeStep === 3" type="primary" @click="handleClose">{{ $t('openapi.finish') }}</el-button>
    </template>
  </el-dialog>
</template>

<script>
import { ref, reactive, computed } from 'vue'
import { useI18n } from 'vue-i18n'
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
    const { t } = useI18n()
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
      // L-10: Guard against missing file.raw — Element Plus can emit change events for
      // empty/aborted uploads where file.raw is undefined, which would throw on readAsText.
      if (!file || !file.raw) {
        ElMessage.error(t('openapi.fileReadFailed'))
        return
      }
      const reader = new FileReader()
      reader.onload = (e) => {
        jsonContent.value = e.target.result
        parseError.value = ''
      }
      reader.onerror = () => {
        ElMessage.error(t('openapi.fileReadFailed'))
      }
      reader.readAsText(file.raw)
    }

    function handleExceed() {
      ElMessage.warning(t('openapi.uploadLimit'))
    }

    async function parseOpenApi() {
      if (!jsonContent.value || !jsonContent.value.trim()) {
        parseError.value = t('openapi.jsonRequired')
        return
      }

      try {
        JSON.parse(jsonContent.value)
      } catch (e) {
        parseError.value = t('openapi.jsonParseError', { 0: e.message })
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
          parseError.value = res.message || t('openapi.parseFailed')
        }
      } catch (e) {
        parseError.value = e.message || t('openapi.parseFailed')
      } finally {
        loading.value = false
      }
    }

    async function doImport() {
      importing.value = true
      try {
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
          ElMessage.error(res.message || t('openapi.importFailed'))
        }
      } catch (e) {
        ElMessage.error(t('openapi.importFailed') + ': ' + (e.message || e))
      } finally {
        importing.value = false
      }
    }

    function handleClose() {
      visible.value = false
    }

    function handleClosed() {
      activeStep.value = 0
      jsonContent.value = ''
      parseError.value = ''
      importResult.value = null
      previewRules.value = []
      selectedEnvironments.value = []
    }

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
      return t('openapi.empty')
    }

    function methodTagType(method) {
      const map = { GET: 'success', POST: 'warning', PUT: 'primary', DELETE: 'danger', PATCH: 'info' }
      return map[method] || ''
    }

    async function loadEnvironments() {
      // H-5: 显式 .catch 防止未处理的 Promise 拒绝
      try {
        const res = await api.getEnvironments()
        if (res.success && res.data) {
          environments.value = res.data
        }
      } catch (e) {
        ElMessage.error(t('openapi.envLoadFailed') + ': ' + (e?.message || e))
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
