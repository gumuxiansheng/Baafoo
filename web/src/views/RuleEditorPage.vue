<template>
  <div class="rule-editor-page">
    <div class="page-header">
      <el-button text @click="$router.back()">
        <el-icon><ArrowLeft /></el-icon> {{ $t('rules.back') }}
      </el-button>
      <h2>{{ isNew ? $t('rules.newRuleTitle') : $t('rules.editRuleTitle') }}</h2>
    </div>

    <el-card shadow="never" style="margin-top: 16px" v-if="isNew || rule" v-loading="loading">
      <el-form :model="form" label-width="100px" size="small">
        <!-- Basic Info -->
        <el-divider content-position="left">{{ $t('rules.basicInfo') }}</el-divider>
        <el-row :gutter="20">
          <el-col :span="12">
            <el-form-item :label="$t('rules.ruleNameLabel')" required>
              <el-input v-model="form.name" />
            </el-form-item>
          </el-col>
          <el-col :span="6">
            <el-form-item :label="$t('rules.protocolLabel')" required>
              <el-select v-model="form.protocol" style="width: 100%">
                <el-option label="HTTP" value="http" />
                <el-option label="TCP" value="tcp" />
                <el-option label="gRPC" value="grpc" />
                <el-option label="Kafka" value="kafka" />
                <el-option label="Pulsar" value="pulsar" />
                <el-option label="JMS" value="jms" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="6">
            <el-form-item :label="$t('rules.priorityLabel')">
              <el-input-number v-model="form.priority" :min="1" :max="1000" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="20">
          <el-col :span="8">
            <el-form-item :label="$t('rules.targetHostLabel')">
              <el-input v-model="form.host" :placeholder="$t('rules.targetHostPlaceholder')" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item :label="$t('rules.targetPort')">
              <el-input-number v-model="form.port" :min="0" :max="65535" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item :label="$t('rules.serviceName')">
              <el-input v-model="form.serviceName" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item :label="$t('rules.enabled')">
          <el-switch v-model="form.enabled" />
        </el-form-item>
        <el-row :gutter="20">
          <el-col :span="8">
            <el-form-item :label="$t('rules.fakerSeed')">
              <el-input-number v-model="form.fakerSeed" :min="0" :step="1" :placeholder="$t('rules.fakerSeedPlaceholder')" />
              <div style="font-size: 12px; color: var(--bf-text-muted); margin-top: 4px">{{ $t('rules.fakerSeedHint') }}</div>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item :label="$t('rules.counterReset')">
              <el-input-number v-model="form.requestCountReset" :min="0" :step="1" :placeholder="$t('rules.counterResetPlaceholder')" />
              <div style="font-size: 12px; color: var(--bf-text-muted); margin-top: 4px">{{ $t('rules.counterResetHint') }}</div>
            </el-form-item>
          </el-col>
          <el-col :span="8" v-if="!isNew && form.protocol === 'http'">
            <el-form-item :label="$t('rules.stateReset')">
              <el-button size="small" @click="resetRuleState" v-if="authStore.canWriteRule">{{ $t('rules.stateResetAction') }}</el-button>
              <div style="font-size: 12px; color: var(--bf-text-muted); margin-top: 4px">{{ $t('rules.stateResetHint') }}</div>
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item :label="$t('rules.tags')">
          <el-input v-model="form.tagsStr" :placeholder="$t('rules.tagsPlaceholder')" />
        </el-form-item>
        <el-form-item :label="$t('rules.effectiveEnvLabel')">
          <div v-if="inheritedEnvs.length > 0" style="margin-bottom: 6px">
            <el-tag v-for="env in inheritedEnvs" :key="env" size="small" type="warning" closable disable-transitions style="margin-right: 4px; margin-bottom: 2px">
              {{ env }} {{ $t('rules.sceneInherited') }}
            </el-tag>
          </div>
          <el-select v-model="form.environments" multiple filterable allow-create default-first-option :placeholder="$t('rules.effectiveEnvPlaceholder')" style="width: 100%">
            <el-option v-for="env in allEnvironments" :key="env.name" :label="env.name" :value="env.name" />
          </el-select>
          <div style="font-size: 12px; color: var(--bf-text-muted); margin-top: 4px">
            {{ $t('rules.envNoEffect') }}
            <span v-if="inheritedEnvs.length > 0" style="color: var(--bf-warning)">；{{ $t('rules.envInherited') }}</span>
          </div>
        </el-form-item>

        <!-- Match Conditions -->
        <el-divider content-position="left">{{ $t('rules.matchConditions') }}
          <el-button size="small" @click="addCondition" v-if="authStore.canWriteRule">{{ $t('rules.addCondition') }}</el-button>
          <el-button size="small" @click="addGraphqlHelper" v-if="authStore.canWriteRule && isGraphqlPath">{{ $t('rules.graphqlHelper') }}</el-button>
        </el-divider>
        <div v-if="isGraphqlPath" class="graphql-hint">
          <el-alert type="info" :closable="false" show-icon>
            <template #title>
              {{ $t('rules.graphqlDetected') }}
            </template>
          </el-alert>
        </div>
        <div v-if="form.conditions.length === 0" style="color: var(--bf-text-muted); font-size: 12px; margin-bottom: 8px">
          {{ $t('rules.noConditionMatch') }}
        </div>
        <div v-for="(cond, idx) in form.conditions" :key="'cond-' + idx" style="margin-bottom: 8px">
          <el-row :gutter="10">
            <el-col :span="4">
              <el-select v-model="cond.type" size="small" @change="onConditionTypeChange(cond)">
                <template v-if="isMqProtocol">
                  <el-option :label="$t('rules.conditionFields.topic')" value="topic" v-if="form.protocol === 'kafka' || form.protocol === 'pulsar'" />
                  <el-option :label="$t('rules.conditionFields.key')" value="key" v-if="form.protocol === 'kafka'" />
                  <el-option :label="$t('rules.conditionFields.destination')" value="destination" v-if="form.protocol === 'jms'" />
                  <el-option :label="$t('rules.conditionFields.messageContent')" value="body" />
                  <el-option :label="$t('rules.conditionFields.messageContains')" value="bodyContains" />
                  <el-option :label="$t('rules.conditionFields.requestCount')" value="requestCount" />
                </template>
                <template v-else-if="isGrpcProtocol">
                  <el-option :label="$t('rules.conditionFields.grpcService')" value="grpcService" />
                  <el-option :label="$t('rules.conditionFields.grpcMethod')" value="grpcMethod" />
                  <el-option :label="$t('rules.conditionFields.path')" value="path" />
                  <el-option :label="$t('rules.conditionFields.header')" value="header" />
                  <el-option label="Body(hex)" value="body" />
                  <el-option :label="$t('rules.conditionFields.bodyContains')" value="bodyContains" />
                  <el-option :label="$t('rules.conditionFields.requestCount')" value="requestCount" />
                </template>
                <template v-else>
                  <el-option :label="$t('rules.conditionFields.method')" value="method" />
                  <el-option :label="$t('rules.conditionFields.path')" value="path" />
                  <el-option :label="$t('rules.conditionFields.header')" value="header" />
                  <el-option :label="$t('rules.conditionFields.query')" value="query" />
                  <el-option :label="$t('rules.conditionFields.body')" value="body" />
                  <el-option :label="$t('rules.conditionFields.bodyContains')" value="bodyContains" />
                  <el-option :label="$t('rules.conditionFields.bodyJsonPath')" value="bodyJsonPath" />
                  <el-option :label="$t('rules.conditionFields.requestCount')" value="requestCount" />
                  <el-option :label="$t('rules.conditionFields.graphqlOperationName')" value="graphqlOperationName" />
                  <el-option :label="$t('rules.conditionFields.graphqlOperationType')" value="graphqlOperationType" />
                </template>
              </el-select>
            </el-col>
            <el-col :span="3">
              <el-select v-model="cond.operator" size="small">
                <el-option :label="$t('rules.operators.equals')" value="equals" />
                <el-option :label="$t('rules.operators.contains')" value="contains" />
                <el-option :label="$t('rules.operators.startsWith')" value="startsWith" />
                <el-option :label="$t('rules.operators.endsWith')" value="endsWith" />
                <el-option :label="$t('rules.operators.regex')" value="regex" />
                <el-option :label="$t('rules.operators.exists')" value="exists" />
                <el-option :label="$t('rules.operators.greaterThan')" value="greaterThan" v-if="cond.type === 'requestCount'" />
                <el-option :label="$t('rules.operators.lessThan')" value="lessThan" v-if="cond.type === 'requestCount'" />
                <el-option :label="$t('rules.operators.range')" value="range" v-if="cond.type === 'requestCount'" />
                <el-option :label="$t('rules.operators.mod')" value="mod" v-if="cond.type === 'requestCount'" />
              </el-select>
            </el-col>
            <el-col :span="4" v-if="cond.type === 'header' || cond.type === 'query'">
              <el-input v-model="cond.key" size="small" placeholder="Key" />
            </el-col>
            <el-col :span="cond.type === 'header' || cond.type === 'query' ? 8 : 12" v-if="cond.operator !== 'exists'">
              <el-input v-model="cond.value" size="small" :placeholder="$t(getValuePlaceholder(cond))" />
            </el-col>
            <el-col :span="4">
              <el-button size="small" type="danger" text @click="removeCondition(idx)" v-if="authStore.canWriteRule">{{ $t('rules.deleteCondition') }}</el-button>
            </el-col>
          </el-row>
        </div>

        <!-- Responses -->
        <el-divider content-position="left">{{ $t('rules.responseBranches') }}
          <el-button size="small" @click="addResponse" v-if="authStore.canWriteRule">{{ $t('rules.addResponse') }}</el-button>
        </el-divider>
        <div v-if="form.responses.length === 0" style="color: var(--bf-text-muted); font-size: 12px; margin-bottom: 8px">
          {{ $t('rules.addAtLeastOne') }}
        </div>
        <div v-for="(resp, idx) in form.responses" :key="'resp-' + idx"
             class="response-card">
          <div class="response-card-header">
            <span class="response-card-title">{{ $t('rules.responseName') }} #{{ idx + 1 }}{{ resp.name ? ' - ' + resp.name : '' }}</span>
            <div>
              <el-tag v-if="!resp.condition" size="small" type="info">{{ $t('rules.responseDefault') }}</el-tag>
              <el-tag v-else size="small" type="warning">{{ $t('rules.responseConditional') }}</el-tag>
              <el-button size="small" type="danger" text @click="removeResponse(idx)" style="margin-left: 8px" v-if="authStore.canWriteRule">{{ $t('rules.deleteResponse') }}</el-button>
            </div>
          </div>

          <el-row :gutter="10">
            <el-col :span="8">
              <el-form-item :label="$t('rules.responseNameLabel')" size="small">
                <el-input v-model="resp.name" size="small" :placeholder="$t('rules.responseNamePlaceholder')" />
              </el-form-item>
            </el-col>
            <el-col :span="4">
              <el-form-item :label="$t('rules.statusCodeLabel')" size="small">
                <el-input-number v-model="resp.statusCode" :min="100" :max="599" size="small" />
              </el-form-item>
            </el-col>
            <el-col :span="4" v-if="isGrpcProtocol">
              <el-form-item :label="$t('rules.grpcStatusLabel')" size="small">
                <el-select v-model="resp.grpcStatus" size="small" style="width: 100%">
                  <el-option label="OK" :value="0" />
                  <el-option label="NOT_FOUND" :value="5" />
                  <el-option label="UNIMPLEMENTED" :value="12" />
                  <el-option label="UNAVAILABLE" :value="14" />
                  <el-option label="INVALID_ARGUMENT" :value="3" />
                  <el-option label="PERMISSION_DENIED" :value="7" />
                  <el-option label="INTERNAL" :value="13" />
                  <el-option label="UNAUTHENTICATED" :value="16" />
                </el-select>
              </el-form-item>
            </el-col>
            <el-col :span="4">
              <el-form-item :label="$t('rules.delayMs')" size="small">
                <el-input-number v-model="resp.delayMs" :min="0" :max="60000" size="small" />
              </el-form-item>
            </el-col>
            <el-col :span="4">
              <el-form-item :label="$t('rules.encoding')" size="small">
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
                <span style="font-size: 12px; color: var(--bf-text-secondary); font-weight: 500">{{ $t('rules.responseMatchCondition') }}</span>
              </el-col>
              <el-col :span="20">
                <el-button v-if="!resp.condition" size="small" @click="addResponseCondition(resp)">{{ $t('rules.addMatchCondition') }}</el-button>
                <el-button v-else size="small" type="danger" text @click="resp.condition = null">{{ $t('rules.removeCondition') }}</el-button>
                <span v-if="!resp.condition" style="font-size: 12px; color: var(--bf-text-muted); margin-left: 8px">
                  {{ $t('rules.defaultResponseHint') }}
                </span>
              </el-col>
            </el-row>
            <div v-if="resp.condition" style="margin-top: 8px">
              <el-row :gutter="10">
                <el-col :span="4">
                  <el-select v-model="resp.condition.type" size="small" @change="onConditionTypeChange(resp.condition)">
                    <template v-if="isMqProtocol">
                      <el-option :label="$t('rules.conditionFields.topic')" value="topic" v-if="form.protocol === 'kafka' || form.protocol === 'pulsar'" />
                      <el-option :label="$t('rules.conditionFields.key')" value="key" v-if="form.protocol === 'kafka'" />
                      <el-option :label="$t('rules.conditionFields.destination')" value="destination" v-if="form.protocol === 'jms'" />
                      <el-option :label="$t('rules.conditionFields.messageContent')" value="body" />
                      <el-option :label="$t('rules.conditionFields.messageContains')" value="bodyContains" />
                      <el-option :label="$t('rules.conditionFields.requestCount')" value="requestCount" />
                    </template>
                    <template v-else-if="isGrpcProtocol">
                      <el-option :label="$t('rules.conditionFields.grpcService')" value="grpcService" />
                      <el-option :label="$t('rules.conditionFields.grpcMethod')" value="grpcMethod" />
                      <el-option :label="$t('rules.conditionFields.path')" value="path" />
                      <el-option :label="$t('rules.conditionFields.header')" value="header" />
                      <el-option label="Body(hex)" value="body" />
                      <el-option :label="$t('rules.conditionFields.bodyContains')" value="bodyContains" />
                      <el-option :label="$t('rules.conditionFields.requestCount')" value="requestCount" />
                    </template>
                    <template v-else>
                      <el-option :label="$t('rules.conditionFields.method')" value="method" />
                      <el-option :label="$t('rules.conditionFields.path')" value="path" />
                      <el-option :label="$t('rules.conditionFields.header')" value="header" />
                      <el-option :label="$t('rules.conditionFields.query')" value="query" />
                      <el-option :label="$t('rules.conditionFields.body')" value="body" />
                      <el-option :label="$t('rules.conditionFields.bodyContains')" value="bodyContains" />
                      <el-option :label="$t('rules.conditionFields.bodyJsonPath')" value="bodyJsonPath" />
                      <el-option :label="$t('rules.conditionFields.requestCount')" value="requestCount" />
                      <el-option :label="$t('rules.conditionFields.graphqlOperationName')" value="graphqlOperationName" />
                      <el-option :label="$t('rules.conditionFields.graphqlOperationType')" value="graphqlOperationType" />
                    </template>
                  </el-select>
                </el-col>
                <el-col :span="3">
                  <el-select v-model="resp.condition.operator" size="small">
                    <el-option :label="$t('rules.operators.equals')" value="equals" />
                    <el-option :label="$t('rules.operators.contains')" value="contains" />
                    <el-option :label="$t('rules.operators.startsWith')" value="startsWith" />
                    <el-option :label="$t('rules.operators.endsWith')" value="endsWith" />
                    <el-option :label="$t('rules.operators.regex')" value="regex" />
                    <el-option :label="$t('rules.operators.exists')" value="exists" />
                    <el-option :label="$t('rules.operators.greaterThan')" value="greaterThan" v-if="resp.condition.type === 'requestCount'" />
                    <el-option :label="$t('rules.operators.lessThan')" value="lessThan" v-if="resp.condition.type === 'requestCount'" />
                    <el-option :label="$t('rules.operators.range')" value="range" v-if="resp.condition.type === 'requestCount'" />
                    <el-option :label="$t('rules.operators.mod')" value="mod" v-if="resp.condition.type === 'requestCount'" />
                  </el-select>
                </el-col>
                <el-col :span="4" v-if="resp.condition.type === 'header' || resp.condition.type === 'query'">
                  <el-input v-model="resp.condition.key" size="small" placeholder="Key" />
                </el-col>
                <el-col :span="resp.condition.type === 'header' || resp.condition.type === 'query' ? 8 : 12" v-if="resp.condition.operator !== 'exists'">
                  <el-input v-model="resp.condition.value" size="small" :placeholder="$t(getValuePlaceholder(resp.condition))" />
                </el-col>
              </el-row>
            </div>
          </div>

          <!-- Response Headers -->
          <div class="response-headers-section">
            <el-row :gutter="10" align="middle">
              <el-col :span="4">
                <span style="font-size: 12px; color: var(--bf-text-secondary); font-weight: 500">{{ $t('rules.responseHeaders') }}</span>
              </el-col>
              <el-col :span="20">
                <el-button size="small" @click="addResponseHeader(resp)">{{ $t('rules.addHeader') }}</el-button>
              </el-col>
            </el-row>
            <div v-for="(h, hIdx) in getResponseHeaders(resp)" :key="'h-' + hIdx" style="margin-top: 6px">
              <el-row :gutter="10">
                <el-col :span="6">
                  <el-input v-model="h.key" size="small" :placeholder="$t('rules.headerName')" />
                </el-col>
                <el-col :span="10">
                  <el-input v-model="h.value" size="small" :placeholder="$t('rules.headerValue')" />
                </el-col>
                <el-col :span="4">
                  <el-button size="small" type="danger" text @click="removeResponseHeader(resp, hIdx)">{{ $t('rules.deleteHeader') }}</el-button>
                </el-col>
              </el-row>
            </div>
          </div>

          <!-- Response Body -->
          <el-form-item :label="$t('rules.responseBody')" size="small">
            <el-input v-model="resp.body" type="textarea" :rows="6" :placeholder="bodyPlaceholder" />
            <div style="font-size: 12px; color: var(--bf-text-muted); margin-top: 4px" v-html="templateVarHint"></div>
            <!-- Faker Quick Insert -->
            <div v-if="showFakerRef" class="faker-ref-panel">
              <div class="faker-ref-title" v-html="templateVarHint"></div>
              <div class="faker-ref-group">
                <div class="faker-ref-label">{{ $t('rules.templates.personal') }}</div>
                <el-tag v-for="fn in fakerGroups.personal" :key="fn" size="small" class="faker-tag" @click="insertFakerVar(resp, fn)" v-text="'{{' + fn + '}}'"></el-tag>
              </div>
              <div class="faker-ref-group">
                <div class="faker-ref-label">{{ $t('rules.templates.address') }}</div>
                <el-tag v-for="fn in fakerGroups.address" :key="fn" size="small" class="faker-tag" @click="insertFakerVar(resp, fn)" v-text="'{{' + fn + '}}'"></el-tag>
              </div>
              <div class="faker-ref-group">
                <div class="faker-ref-label">{{ $t('rules.templates.company') }}</div>
                <el-tag v-for="fn in fakerGroups.network" :key="fn" size="small" class="faker-tag" @click="insertFakerVar(resp, fn)" v-text="'{{' + fn + '}}'"></el-tag>
              </div>
              <div class="faker-ref-group">
                <div class="faker-ref-label">{{ $t('rules.templates.number') }}</div>
                <el-tag v-for="fn in fakerGroups.numeric" :key="fn" size="small" class="faker-tag" @click="insertFakerVar(resp, fn)" v-text="'{{' + fn + '}}'"></el-tag>
              </div>
              <div class="faker-ref-group">
                <div class="faker-ref-label">{{ $t('rules.templates.other') }}</div>
                <el-tag v-for="fn in fakerGroups.misc" :key="fn" size="small" class="faker-tag" @click="insertFakerVar(resp, fn)" v-text="'{{' + fn + '}}'"></el-tag>
              </div>
            </div>
          </el-form-item>
        </div>

        <!-- Fault Injection -->
        <el-divider content-position="left">{{ $t('rules.faultInjection') }}
          <el-switch v-model="form.faultInjectionEnabled" size="small" v-if="authStore.canWriteRule" />
        </el-divider>
        <div v-if="form.faultInjectionEnabled" class="fault-injection-panel">
          <div style="font-size: 12px; color: var(--bf-text-muted); margin-bottom: 8px">
            {{ $t('rules.faultHint') }}
          </div>
          <div v-for="(fault, fIdx) in form.faults" :key="'fault-' + fIdx" class="fault-card">
            <el-row :gutter="10" align="middle">
              <el-col :span="5">
                <el-select v-model="fault.type" size="small" :placeholder="$t('rules.faultType')">
                  <el-option :label="$t('rules.faultTypes.HTTP_ERROR')" value="HTTP_ERROR" />
                  <el-option :label="$t('rules.faultTypes.DELAY')" value="DELAY" />
                  <el-option :label="$t('rules.faultTypes.CONNECTION_RESET')" value="CONNECTION_RESET" />
                  <el-option :label="$t('rules.faultTypes.READ_TIMEOUT')" value="READ_TIMEOUT" />
                </el-select>
              </el-col>
              <el-col :span="4">
                <el-input-number v-model="fault.probability" :min="0" :max="1" :step="0.1" :precision="2" size="small" :placeholder="$t('rules.probability')" />
                <div style="font-size: 11px; color: var(--bf-text-muted)">{{ $t('rules.probabilityHint') }}</div>
              </el-col>
              <el-col :span="6" v-if="fault.type === 'HTTP_ERROR'">
                <el-input v-model="fault.statusCodesStr" size="small" :placeholder="$t('rules.statusCodeList')" />
                <div style="font-size: 11px; color: var(--bf-text-muted)">{{ $t('rules.statusCodeList') }}</div>
              </el-col>
              <el-col :span="6" v-if="fault.type === 'DELAY'">
                <el-input-number v-model="fault.delayMs" :min="0" :max="60000" size="small" />
                <div style="font-size: 11px; color: var(--bf-text-muted)">{{ $t('rules.delayMsHint') }}</div>
              </el-col>
              <el-col :span="6" v-if="fault.type === 'CONNECTION_RESET' || fault.type === 'READ_TIMEOUT'">
                <span style="font-size: 12px; color: var(--bf-text-muted)">
                  {{ fault.type === 'CONNECTION_RESET' ? $t('rules.faultConnectionReset') : $t('rules.faultReadTimeout') }}
                </span>
              </el-col>
              <el-col :span="4" style="text-align: right">
                <el-button size="small" type="danger" text @click="removeFault(fIdx)" v-if="authStore.canWriteRule">{{ $t('rules.deleteFault') }}</el-button>
              </el-col>
            </el-row>
          </div>
          <el-button size="small" @click="addFault" v-if="authStore.canWriteRule" style="margin-top: 8px">{{ $t('rules.addFault') }}</el-button>
        </div>

        <div style="margin-top: 24px; text-align: right">
          <el-button @click="$router.back()">{{ $t('rules.cancel') }}</el-button>
          <el-button type="primary" @click="saveRule" :loading="saving" v-if="authStore.canWriteRule">{{ $t('rules.saveRule') }}</el-button>
          <span v-if="!authStore.canWriteRule" style="color: var(--bf-text-muted); font-size: 14px; margin-left: 12px">{{ $t('rules.noPermission') }}</span>
        </div>
      </el-form>
    </el-card>
  </div>
</template>

<script>
import { ref, reactive, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
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
    const { t, locale } = useI18n()
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

    const isMqProtocol = computed(() => {
      return ['kafka', 'pulsar', 'jms'].includes(form.protocol)
    })

    const isGrpcProtocol = computed(() => {
      return form.protocol === 'grpc'
    })

    const envTagType = (val) => {
      return inheritedEnvs.value.includes(val) ? 'warning' : ''
    }

    // NOTE: templateVarHint contains {{{{...}}}} which breaks vue-i18n interpolation.
    // Keep it as a raw string, NOT an i18n key.
    const templateVarHint = computed(() => {
      const isEn = locale.value === 'en'
      const prefix = isEn ? 'Template vars supported: ' : '支持模板变量: '
      const dynamicLabel = isEn ? 'Dynamic data: ' : '动态数据: '
      const moreFn = isEn ? 'More functions...' : '更多函数...'
      return `${prefix}<code>{{{{request.body.xxx}}}}</code> <code>{{{{request.header.xxx}}}}</code> <code>{{{{request.query.xxx}}}}</code> <code>{{{{request.path}}}}</code><br/>${dynamicLabel}<code>{{{{faker.phone}}}}</code> <code>{{{{faker.email}}}}</code> <code>{{{{faker.name}}}}</code> <code>{{{{faker.address}}}}</code> <code>{{{{faker.idCard}}}}</code> <code>{{{{faker.uuid}}}}</code> <code>{{{{faker.int.1.100}}}}</code> <a href="javascript:void(0)" onclick="document.dispatchEvent(new CustomEvent('toggle-faker-ref'))" style="color:var(--bf-accent)">${moreFn}</a>`
    })

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
        form.responses.push({ name: `${t('rules.responseDefault')}`, statusCode: 200, delayMs: 0, body: '', headers: {}, condition: null })
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
          form.responses.push({ name: `${t('rules.responseDefault')}`, statusCode: 200, delayMs: 0, body: '', headers: {}, condition: null })
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
      } else if (cond.type === 'grpcService' || cond.type === 'grpcMethod') {
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
      if (!cond) return 'rules.value'
      switch (cond.type) {
        case 'method': return 'rules.methodPlaceholder'
        case 'path': return 'rules.pathPlaceholder'
        case 'header': return 'rules.headerPlaceholder'
        case 'query': return 'rules.queryPlaceholder'
        case 'body': return 'rules.bodyPlaceholder'
        case 'bodyContains': return 'rules.bodyContainsPlaceholder'
        case 'bodyJsonPath': return 'rules.bodyJsonPathPlaceholder'
        case 'topic': return 'rules.topicPlaceholder'
        case 'key': return 'rules.keyPlaceholder'
        case 'destination': return 'rules.destinationPlaceholder'
        case 'grpcService': return 'rules.grpcServicePlaceholder'
        case 'grpcMethod': return 'rules.grpcMethodPlaceholder'
        case 'requestCount':
          if (cond.operator === 'range') return 'rules.requestCountRangeHint'
          if (cond.operator === 'mod') return 'rules.requestCountModHint'
          return 'rules.requestCountSingleHint'
        case 'graphqlOperationName': return 'rules.graphqlOpNamePlaceholder'
        case 'graphqlOperationType': return 'rules.graphqlOpTypePlaceholder'
        default: return 'rules.value'
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
          ElMessage.success(t('rules.stateResetSuccess'))
        } else {
          ElMessage.error(res.message || t('rules.stateResetFailed'))
        }
      } catch (e) {
        ElMessage.error(t('rules.stateResetFailed') + ': ' + (e.message || e))
      }
    }

    function addResponse() {
      form.responses.push({ name: '', statusCode: 200, delayMs: 0, body: '', headers: {}, condition: null, grpcStatus: 0, grpcStatusMessage: '' })
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
          condition: r.condition || null,
          grpcStatus: r.grpcStatus != null ? r.grpcStatus : 0,
          grpcStatusMessage: r.grpcStatusMessage || null
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
      isGraphqlPath, isMqProtocol, isGrpcProtocol, addGraphqlHelper,
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
.response-card {
  border: 1px solid var(--bf-border);
  border-radius: var(--bf-radius);
  padding: 16px;
  margin-bottom: 12px;
  background: var(--bf-surface);
}
.response-card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
  padding-bottom: 8px;
  border-bottom: 1px dashed var(--bf-border);
}
.response-card-title {
  font-size: 14px;
  font-weight: 700;
  color: var(--bf-text);
}
.response-condition-section {
  background: var(--bf-fill-color);
  border-radius: var(--bf-radius-sm);
  padding: 10px 12px;
  margin-bottom: 12px;
}
.response-headers-section {
  margin-bottom: 12px;
}
.faker-ref-panel {
  margin-top: 8px;
  padding: 10px 12px;
  background: var(--bf-fill-color);
  border-radius: var(--bf-radius-sm);
  border: 1px solid var(--bf-border);
}
.faker-ref-title {
  font-size: 13px;
  font-weight: 700;
  color: var(--bf-text);
  margin-bottom: 8px;
}
.faker-ref-group {
  margin-bottom: 6px;
}
.faker-ref-label {
  font-size: 12px;
  color: var(--bf-text-secondary);
  margin-bottom: 4px;
  font-weight: 600;
}
.faker-tag {
  margin-right: 4px;
  margin-bottom: 2px;
  cursor: pointer;
  transition: all 0.2s;
}
.faker-tag:hover {
  color: var(--bf-accent);
  border-color: var(--bf-accent);
}
.graphql-hint {
  margin-bottom: 12px;
}
.fault-injection-panel {
  background: var(--bf-fill-color);
  border-radius: var(--bf-radius-sm);
  padding: 12px;
  border: 1px solid var(--bf-border);
}
.fault-card {
  background: var(--bf-surface);
  border: 1px solid var(--bf-border);
  border-radius: var(--bf-radius-sm);
  padding: 10px 12px;
  margin-bottom: 8px;
}
</style>
