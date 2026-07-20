<template>
  <div v-if="approvalEvent" class="approval-panel">
    <div class="inspector-title">
      <ShieldAlert :size="18" />
      <strong>{{ approvalEvent.approval.requestId }}</strong>
      <el-tag :type="statusType">{{ status }}</el-tag>
    </div>

    <div class="risk-banner" :class="`risk-${approvalEvent.approval.riskAssessment.riskLevel.toLowerCase()}`">
      <span>{{ t('approval.risk') }}</span>
      <strong>{{ approvalEvent.approval.riskAssessment.riskLevel }}</strong>
    </div>

    <div class="approval-actions">
      <el-button
        data-test="approval-approve-only"
        type="success"
        :icon="CheckCircle2"
        :loading="actionLoading === 'approve-only'"
        :disabled="actionDisabled"
        @click="runApprovalAction('approve-only')"
      >
        {{ t('approval.approveOnly') }}
      </el-button>
      <el-button
        data-test="approval-approve-execute"
        type="primary"
        :icon="CheckCircle2"
        :loading="actionLoading === 'approve-execute'"
        :disabled="actionDisabled"
        @click="runApprovalAction('approve-execute')"
      >
        {{ t('approval.approveExecute') }}
      </el-button>
      <el-button
        data-test="approval-reject"
        type="danger"
        :icon="XCircle"
        :loading="actionLoading === 'reject'"
        :disabled="actionDisabled"
        @click="runApprovalAction('reject')"
      >
        {{ t('approval.reject') }}
      </el-button>
    </div>
    <el-tag v-if="isMockMode" type="info">{{ t('approval.mockPreview') }}</el-tag>
    <el-alert
      v-if="actionError"
      type="error"
      :title="actionError"
      :closable="false"
      show-icon
    />

    <section class="approval-section">
      <h3>{{ t('approval.reasons') }}</h3>
      <ul>
        <li v-for="reason in approvalEvent.approval.riskAssessment.reasons" :key="reason">{{ reason }}</li>
      </ul>
    </section>

    <section class="approval-section">
      <h3>{{ t('approval.affectedPaths') }}</h3>
      <div class="path-list">
        <span v-for="path in approvalEvent.approval.riskAssessment.affectedPaths" :key="path">{{ path }}</span>
      </div>
    </section>

    <section class="approval-section">
      <h3>{{ t('approval.command') }}</h3>
      <code>{{ approvalEvent.approval.riskAssessment.command || 'n/a' }}</code>
    </section>

    <section class="json-section">
      <h3>{{ t('approval.request') }}</h3>
      <JsonViewer :value="approvalEvent.approval" />
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';
import { storeToRefs } from 'pinia';
import { CheckCircle2, ShieldAlert, XCircle } from 'lucide-vue-next';

import { approveExecuteApproval, approveOnlyApproval, rejectApproval } from '@/api/consoleApi';
import { useInspectorStore } from '@/stores/inspectorStore';
import { useLocaleStore } from '@/stores/localeStore';
import { useSessionStore } from '@/stores/sessionStore';
import JsonViewer from './JsonViewer.vue';

const inspectorStore = useInspectorStore();
const sessionStore = useSessionStore();
const { selectedApproval: approvalEvent, selectedApprovalStatus: status } = storeToRefs(inspectorStore);
const { t } = useLocaleStore();
const actionLoading = ref<'approve-only' | 'approve-execute' | 'reject' | ''>('');
const actionError = ref('');

const isMockMode = computed(() => sessionStore.selectedSessionDataSource !== 'backend');
const actionDisabled = computed(() => status.value !== 'PENDING' || Boolean(actionLoading.value));

const statusType = computed(() => {
  if (status.value === 'APPROVED') {
    return 'success';
  }
  if (status.value === 'REJECTED') {
    return 'danger';
  }
  return 'warning';
});

async function runApprovalAction(action: 'approve-only' | 'approve-execute' | 'reject') {
  const requestId = approvalEvent.value?.approval.requestId;
  if (!requestId || actionDisabled.value) {
    return;
  }
  actionError.value = '';
  actionLoading.value = action;
  try {
    if (isMockMode.value) {
      inspectorStore.setApprovalStatus(requestId, action === 'reject' ? 'REJECTED' : 'APPROVED');
      return;
    }
    const response = action === 'approve-only'
      ? await approveOnlyApproval(requestId)
      : action === 'approve-execute'
        ? await approveExecuteApproval(requestId)
        : await rejectApproval(requestId);
    if (response.status === 'failed') {
      actionError.value = response.message || 'Approval action failed';
      return;
    }
    inspectorStore.setApprovalStatus(requestId, action === 'reject' ? 'REJECTED' : 'APPROVED');
    await sessionStore.refreshEventsOnce();
  } catch (error) {
    actionError.value = error instanceof Error ? error.message : 'Approval action failed';
  } finally {
    actionLoading.value = '';
  }
}
</script>
