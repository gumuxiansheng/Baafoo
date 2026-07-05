<template>
  <div class="rules-page">
    <div class="page-header">
      <h2>{{ $t('rules.title') }}</h2>
      <div>
        <el-button @click="showImportDialog = true" v-if="authStore.canWriteRule">
          <el-icon><Upload /></el-icon> {{ $t('rules.importOpenApi') }}
        </el-button>
        <el-button type="primary" @click="createRule" v-if="authStore.canWriteRule">
          <el-icon><Plus /></el-icon> {{ $t('rules.newRule') }}
        </el-button>
      </div>
    </div>

    <!-- Filters -->
    <el-card shadow="never" style="margin-top: 16px">
      <el-form :inline="true" size="small" @submit.prevent="doSearch">
        <el-form-item :label="$t('rules.protocol')">
          <el-select v-model="filter.protocol" :placeholder="$t('recordings.all')" clearable style="width: 120px" @change="doSearch">
            <el-option label="HTTP" value="http" />
            <el-option label="TCP" value="tcp" />
            <el-option label="Kafka" value="kafka" />
            <el-option label="Pulsar" value="pulsar" />
            <el-option label="JMS" value="jms" />
          </el-select>
        </el-form-item>
        <el-form-item :label="$t('rules.effectiveEnv')">
          <el-select v-model="filter.environment" :placeholder="$t('recordings.all')" clearable style="width: 140px" @change="doSearch">
            <el-option v-for="env in environments" :key="env.id" :label="env.name" :value="env.name" />
          </el-select>
        </el-form-item>
        <el-form-item :label="$t('rules.targetHost')">
          <el-input v-model="filter.host" :placeholder="$t('rules.targetHostPlaceholder')" clearable style="width: 160px" @clear="doSearch" @keyup.enter="doSearch" />
        </el-form-item>
        <el-form-item :label="$t('rules.search')">
          <el-input v-model="filter.keyword" :placeholder="$t('rules.search')" clearable style="width: 200px" @clear="doSearch" @keyup.enter="doSearch" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="doSearch">{{ $t('rules.query') }}</el-button>
          <el-button @click="resetFilter">{{ $t('rules.reset') }}</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <!-- Rule Table -->
    <el-card shadow="never" style="margin-top: 16px">
      <el-table :data="rulesStore.rules" stripe v-loading="rulesStore.loading" size="small">
        <el-table-column prop="name" :label="$t('rules.ruleName')" min-width="180">
          <template #default="{ row }">
            <el-link type="primary" @click="editRule(row)">{{ row.name }}</el-link>
          </template>
        </el-table-column>
        <el-table-column prop="id" label="ID" width="160" show-overflow-tooltip />
        <el-table-column prop="protocol" :label="$t('rules.protocol')" width="105">
          <template #default="{ row }">
            <el-tag size="small">{{ (row.protocol || '').toUpperCase() }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="$t('rules.target')" min-width="200">
          <template #default="{ row }">
            <span v-if="row.serviceName">{{ row.serviceName }} (Consul)</span>
            <span v-else>{{ row.host || '*' }}:{{ row.port || '*' }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="priority" :label="$t('rules.priority')" width="80" align="center" />
        <el-table-column :label="$t('rules.status')" width="80" align="center">
          <template #default="{ row }">
            <el-switch v-model="row.enabled" @change="toggleRule(row)" size="small" :disabled="!authStore.canWriteRule" />
          </template>
        </el-table-column>
        <el-table-column :label="$t('rules.effectiveEnvs')" min-width="120">
          <template #default="{ row }">
            <template v-if="row.environments && row.environments.length > 0">
              <el-tag v-for="env in row.environments" :key="env" size="small" type="info" style="margin-right: 4px">{{ env }}</el-tag>
            </template>
            <el-tag v-else size="small" type="warning">{{ $t('rules.unassociated') }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="version" :label="$t('rules.version')" width="60" align="center" />
        <el-table-column :label="$t('rules.actions')" width="240" fixed="right">
          <template #default="{ row }">
            <el-button size="small" text @click="editRule(row)" v-if="authStore.canWriteRule">{{ $t('rules.edit') }}</el-button>
            <el-button size="small" text @click="undoRuleItem(row)" :disabled="row.version <= 1" v-if="authStore.canWriteRule">{{ $t('rules.undo') }}</el-button>
            <el-popconfirm :title="$t('rules.confirmDelete')" @confirm="deleteRuleItem(row.id)" v-if="authStore.canWriteRule">
              <template #reference>
                <el-button size="small" text type="danger">{{ $t('rules.delete') }}</el-button>
              </template>
            </el-popconfirm>
          </template>
        </el-table-column>
      </el-table>
      <div class="pagination-wrap" v-if="rulesStore.pagination.total > 0">
        <el-pagination
          background
          layout="total, prev, pager, next, sizes"
          :total="rulesStore.pagination.total"
          :page-size="rulesStore.pagination.size"
          :current-page="rulesStore.pagination.page"
          :page-sizes="[10, 20, 50, 100]"
          @current-change="onPageChange"
          @size-change="onSizeChange"
        />
      </div>
    </el-card>

    <!-- OpenAPI Import Dialog -->
    <OpenApiImportDialog v-model="showImportDialog" @imported="onImported" />
  </div>
</template>

<script>
import { reactive, ref, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRulesStore, useAuthStore } from '@/store'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import api from '@/api'
import OpenApiImportDialog from '@/components/OpenApiImportDialog.vue'

export default {
  name: 'RulesPage',
  components: { OpenApiImportDialog },
  setup() {
    const router = useRouter()
    const rulesStore = useRulesStore()
    const authStore = useAuthStore()
    const { t } = useI18n()
    const filter = reactive({ protocol: '', keyword: '', environment: '', host: '' })
    const environments = ref([])
    const showImportDialog = ref(false)

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

    function doSearch() {
      rulesStore.setFilter({ protocol: filter.protocol, keyword: filter.keyword, environment: filter.environment, host: filter.host })
      rulesStore.fetchRulesPaged()
    }

    function resetFilter() {
      filter.protocol = ''
      filter.keyword = ''
      filter.environment = ''
      filter.host = ''
      rulesStore.setFilter({ protocol: '', keyword: '', environment: '', host: '' })
      rulesStore.fetchRulesPaged()
    }

    function onPageChange(page) {
      rulesStore.setPage(page)
      rulesStore.fetchRulesPaged()
    }

    function onSizeChange(size) {
      rulesStore.setPageSize(size)
      rulesStore.fetchRulesPaged()
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

    function onImported() {
      ElMessage.success(t('rules.importSuccess'))
      rulesStore.fetchRulesPaged()
    }

    onMounted(() => {
      loadEnvironments()
      rulesStore.fetchRulesPaged()
    })

    return {
      filter, environments, rulesStore, authStore, showImportDialog,
      doSearch, resetFilter, onPageChange, onSizeChange,
      createRule, editRule, toggleRule, deleteRuleItem, undoRuleItem, onImported
    }
  }
}
</script>

<style scoped>
.page-header { display: flex; justify-content: space-between; align-items: center; }
.pagination-wrap { margin-top: 16px; display: flex; justify-content: flex-end; }
:deep(.el-pagination) { font-size: 12px; }
:deep(.el-pagination .el-pagination__total) { font-size: 12px; }
:deep(.el-pagination .el-pagination__sizes) { font-size: 12px; }
:deep(.el-pagination .el-pagination__sizes .el-input__wrapper) { font-size: 12px; height: 24px; }
:deep(.el-pagination .el-pagination__sizes .el-input__inner) { font-size: 12px; height: 24px; }
:deep(.el-pagination .btn-prev) { font-size: 12px; min-width: 24px; height: 24px; line-height: 24px; }
:deep(.el-pagination .btn-next) { font-size: 12px; min-width: 24px; height: 24px; line-height: 24px; }
:deep(.el-pagination .el-pager li) { font-size: 12px; min-width: 24px; height: 24px; line-height: 24px; }
</style>


