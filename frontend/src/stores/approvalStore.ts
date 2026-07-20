import { computed, ref } from 'vue';
import { defineStore } from 'pinia';

import {
  approveExecuteApproval,
  approveOnlyApproval,
  getPendingApprovals,
  rejectApproval,
} from '@/api/consoleApi';
import type { ApprovalRequest, ApprovalStatus, RiskLevel } from '@/types/agent-console';

type ApprovalFilterStatus = 'pending' | 'approved' | 'rejected' | 'all';
type ApprovalAction = 'approve-only' | 'approve-execute' | 'reject';

export interface ApprovalListItem {
  approval: ApprovalRequest;
  sessionId: string;
  command: string;
}

export const useApprovalStore = defineStore('approvals', () => {
  const items = ref<ApprovalListItem[]>([]);
  const loading = ref(false);
  const error = ref('');
  const actionLoadingId = ref('');
  const statusFilter = ref<ApprovalFilterStatus>('pending');
  const sessionIdFilter = ref('');
  const keywordFilter = ref('');
  const unsupportedStatusHint = ref('');

  const filteredItems = computed(() => {
    const keyword = keywordFilter.value.trim().toLowerCase();
    return items.value.filter((item) => {
      if (sessionIdFilter.value && item.sessionId !== sessionIdFilter.value) {
        return false;
      }
      if (!keyword) {
        return true;
      }
      const text = `${item.approval.requestId} ${item.sessionId} ${item.command} ${item.approval.riskAssessment.toolName}`.toLowerCase();
      return text.includes(keyword);
    });
  });

  async function loadApprovals(options: { status?: ApprovalFilterStatus; sessionId?: string; keyword?: string } = {}) {
    statusFilter.value = options.status ?? statusFilter.value;
    sessionIdFilter.value = options.sessionId ?? sessionIdFilter.value;
    keywordFilter.value = options.keyword ?? keywordFilter.value;
    unsupportedStatusHint.value = '';
    error.value = '';

    if (statusFilter.value !== 'pending') {
      items.value = [];
      unsupportedStatusHint.value = '当前后端仅支持 pending approvals，其他状态后续支持。';
      return;
    }

    loading.value = true;
    try {
      const response = await getPendingApprovals();
      items.value = (response.items ?? []).map(toApprovalListItem);
    } catch (err) {
      items.value = [];
      error.value = err instanceof Error ? err.message : 'Approvals unavailable';
    } finally {
      loading.value = false;
    }
  }

  async function runApprovalAction(requestId: string, action: ApprovalAction) {
    if (!requestId || actionLoadingId.value) {
      return;
    }
    actionLoadingId.value = requestId;
    error.value = '';
    try {
      const response = action === 'approve-only'
        ? await approveOnlyApproval(requestId)
        : action === 'approve-execute'
          ? await approveExecuteApproval(requestId)
          : await rejectApproval(requestId);
      if (response.status === 'failed') {
        error.value = response.message || 'Approval action failed';
        return;
      }
      await loadApprovals();
    } catch (err) {
      error.value = err instanceof Error ? err.message : 'Approval action failed';
    } finally {
      actionLoadingId.value = '';
    }
  }

  return {
    items,
    filteredItems,
    loading,
    error,
    actionLoadingId,
    statusFilter,
    sessionIdFilter,
    keywordFilter,
    unsupportedStatusHint,
    loadApprovals,
    runApprovalAction,
  };
});

function toApprovalListItem(raw: unknown): ApprovalListItem {
  const item = asRecord(raw);
  const risk = asRecord(item.riskAssessment);
  const pendingToolCall = asRecord(item.pendingToolCall);
  const requestId = String(item.requestId ?? item.id ?? pendingToolCall.requestId ?? 'approval');
  const sessionId = String(item.sessionId ?? pendingToolCall.sessionId ?? '');
  const command = String(risk.command ?? item.command ?? '');
  return {
    sessionId,
    command,
    approval: {
      requestId,
      createdAt: String(item.createdAt ?? new Date().toISOString()),
      expiresAt: String(item.expiresAt ?? ''),
      status: normalizeApprovalStatus(item.status),
      consumed: Boolean(item.consumed),
      riskAssessment: {
        riskLevel: normalizeRiskLevel(risk.riskLevel ?? risk.level),
        reasons: stringArray(risk.reasons),
        command,
        toolName: String(risk.toolName ?? pendingToolCall.toolName ?? ''),
        affectedPaths: stringArray(risk.affectedPaths),
        requiresApproval: Boolean(risk.requiresApproval ?? true),
        blocked: Boolean(risk.blocked),
      },
      pendingToolCall: Object.keys(pendingToolCall).length > 0 ? {
        requestId,
        toolName: String(pendingToolCall.toolName ?? risk.toolName ?? ''),
        arguments: asRecord(pendingToolCall.arguments),
        sessionId,
        createdAt: String(pendingToolCall.createdAt ?? item.createdAt ?? ''),
        riskAssessment: {
          riskLevel: normalizeRiskLevel(risk.riskLevel ?? risk.level),
          reasons: stringArray(risk.reasons),
          command,
          toolName: String(risk.toolName ?? pendingToolCall.toolName ?? ''),
          affectedPaths: stringArray(risk.affectedPaths),
          requiresApproval: Boolean(risk.requiresApproval ?? true),
          blocked: Boolean(risk.blocked),
        },
        consumed: Boolean(pendingToolCall.consumed),
      } : undefined,
    },
  };
}

function normalizeApprovalStatus(value: unknown): ApprovalStatus {
  const normalized = String(value ?? '').toUpperCase();
  return ['PENDING', 'APPROVED', 'REJECTED'].includes(normalized) ? normalized as ApprovalStatus : 'PENDING';
}

function normalizeRiskLevel(value: unknown): RiskLevel {
  const normalized = String(value ?? '').toUpperCase();
  return ['SAFE', 'LOW', 'MEDIUM', 'HIGH', 'BLOCKED'].includes(normalized) ? normalized as RiskLevel : 'MEDIUM';
}

function stringArray(value: unknown): string[] {
  return Array.isArray(value) ? value.map(String) : [];
}

function asRecord(value: unknown): Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value) ? value as Record<string, unknown> : {};
}
