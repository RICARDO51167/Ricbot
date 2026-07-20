<template>
  <section class="console-page-card">
    <div class="page-head">
      <div>
        <h2>{{ t('nav.approvals') }}</h2>
        <p>{{ t('page.approvals.description') }}</p>
      </div>
      <el-tag>{{ approvalStore.statusFilter }}</el-tag>
    </div>
    <div class="page-filter-row">
      <el-select v-model="approvalStore.statusFilter" size="small">
        <el-option :label="t('page.approvals.pending')" value="pending" />
        <el-option :label="t('page.approvals.approved')" value="approved" />
        <el-option :label="t('page.approvals.rejected')" value="rejected" />
        <el-option :label="t('page.approvals.all')" value="all" />
      </el-select>
      <el-input v-model="approvalStore.sessionIdFilter" size="small" :placeholder="t('common.sessionId')" clearable />
      <el-input v-model="approvalStore.keywordFilter" size="small" :placeholder="t('common.keyword')" clearable />
      <el-button size="small" :loading="approvalStore.loading" @click="reload">{{ t('common.refresh') }}</el-button>
    </div>
    <el-alert v-if="approvalStore.error" type="error" :title="approvalStore.error" :closable="false" />
    <el-alert v-else-if="approvalStore.unsupportedStatusHint" type="info" :title="approvalStore.unsupportedStatusHint" :closable="false" />
    <div v-if="approvalStore.filteredItems.length > 0" class="approval-list">
      <article v-for="item in approvalStore.filteredItems" :key="item.approval.requestId" class="approval-list-row">
        <div>
          <strong>{{ item.approval.requestId }}</strong>
          <button type="button" class="inline-link" @click="openSession(item.sessionId)">{{ item.sessionId || t('page.approvals.sessionUnavailable') }}</button>
          <p>{{ item.command || item.approval.riskAssessment.toolName || t('page.approvals.request') }}</p>
        </div>
        <div class="approval-list-actions">
          <el-tag type="warning">{{ item.approval.riskAssessment.riskLevel }}</el-tag>
          <el-button size="small" type="success" :loading="approvalStore.actionLoadingId === item.approval.requestId" @click="runAction(item.approval.requestId, 'approve-only')">
            {{ t('approval.approve') }}
          </el-button>
          <el-button size="small" type="primary" :loading="approvalStore.actionLoadingId === item.approval.requestId" @click="runAction(item.approval.requestId, 'approve-execute')">
            {{ t('approval.approveExecute') }}
          </el-button>
          <el-button size="small" type="danger" :loading="approvalStore.actionLoadingId === item.approval.requestId" @click="runAction(item.approval.requestId, 'reject')">
            {{ t('approval.reject') }}
          </el-button>
        </div>
      </article>
    </div>
    <ApprovalPanel v-else-if="selectedApproval" />
    <div v-else-if="!approvalStore.loading && !approvalStore.unsupportedStatusHint" class="history-empty">{{ t('page.approvals.empty') }}</div>
  </section>
</template>

<script setup lang="ts">
import { watch } from 'vue';
import { storeToRefs } from 'pinia';

import ApprovalPanel from '@/components/inspector/ApprovalPanel.vue';
import { currentRoute, navigate, replace } from '@/router';
import { useApprovalStore } from '@/stores/approvalStore';
import { useInspectorStore } from '@/stores/inspectorStore';
import { useLocaleStore } from '@/stores/localeStore';

const { selectedApproval } = storeToRefs(useInspectorStore());
const approvalStore = useApprovalStore();
const { t } = useLocaleStore();
let applyingQuery = false;

watch(
  () => queryKey(currentRoute.value.query),
  () => {
    void applyApprovalsQuery(currentRoute.value.query);
  },
  { immediate: true },
);

watch(
  () => [
    approvalStore.statusFilter,
    approvalStore.sessionIdFilter,
    approvalStore.keywordFilter,
  ],
  () => {
    syncApprovalsQuery();
  },
);

async function applyApprovalsQuery(query: Record<string, string>) {
  if (currentRoute.value.path !== '/console/approvals') {
    return;
  }
  applyingQuery = true;
  try {
    await approvalStore.loadApprovals({
      status: normalizeStatus(query.status),
      sessionId: query.sessionId ?? '',
      keyword: query.keyword ?? '',
    });
  } finally {
    applyingQuery = false;
    syncApprovalsQuery();
  }
}

function reload() {
  void approvalStore.loadApprovals();
}

async function runAction(requestId: string, action: 'approve-only' | 'approve-execute' | 'reject') {
  await approvalStore.runApprovalAction(requestId, action);
}

function openSession(sessionId: string) {
  if (sessionId) {
    navigate('/console/workbench', { sessionId });
  }
}

function syncApprovalsQuery() {
  if (applyingQuery || currentRoute.value.path !== '/console/approvals') {
    return;
  }
  const next = {
    status: approvalStore.statusFilter === 'pending' ? undefined : approvalStore.statusFilter,
    sessionId: approvalStore.sessionIdFilter || undefined,
    keyword: approvalStore.keywordFilter || undefined,
  };
  if (queryKey(next) !== queryKey(currentRoute.value.query)) {
    replace('/console/approvals', next);
  }
}

function normalizeStatus(value: string | undefined) {
  return ['pending', 'approved', 'rejected', 'all'].includes(value ?? '')
    ? value as 'pending' | 'approved' | 'rejected' | 'all'
    : 'pending';
}

function queryKey(query: Record<string, string | undefined>) {
  return JSON.stringify(Object.entries(query)
    .filter(([, value]) => value !== undefined && value !== '')
    .sort(([left], [right]) => left.localeCompare(right)));
}
</script>
