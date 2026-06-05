import { computed, ref } from 'vue';
import { defineStore } from 'pinia';

import { getChangeSetFileDiff, getRecentChangeSets } from '@/api/consoleApi';
import type { AgentSession, ChangedFile, ChangeSet, FileChangeType } from '@/types/agent-console';
import { useSessionStore } from './sessionStore';

export const useChangeSetStore = defineStore('changeSets', () => {
  const recentChangeSets = ref<ChangeSet[]>([]);
  const recentLoading = ref(false);
  const recentError = ref('');
  const selectedChangeSetId = ref('');
  const sessionIdFilter = ref('');
  const selectedFilePath = ref<string | null>(null);
  const loadedDiffs = ref<Record<string, string>>({});
  const diffLoading = ref(false);
  const diffError = ref('');
  const diffEmptyMessage = ref('');

  const selectedRecentChangeSet = computed(() => {
    if (recentChangeSets.value.length === 0) {
      return null;
    }
    return recentChangeSets.value.find((changeSet) => changeSet.id === selectedChangeSetId.value)
      ?? recentChangeSets.value[0]
      ?? null;
  });

  const currentChangeSet = computed(() => selectedRecentChangeSet.value ?? useSessionStore().currentSession?.changeSet ?? null);

  const selectedFile = computed(() => {
    const changeSet = currentChangeSet.value;
    if (!changeSet) {
      return null;
    }
    return changeSet.changedFiles.find((file) => file.path === selectedFilePath.value) ?? changeSet.changedFiles[0] ?? null;
  });

  const currentDiff = computed(() => {
    const changeSet = currentChangeSet.value;
    const file = selectedFile.value;
    if (!changeSet || !file) {
      return '';
    }
    return loadedDiffs.value[diffKey(changeSet.id, file.path)] ?? file.diff ?? '';
  });

  async function selectFile(path: string) {
    selectedFilePath.value = path;
    await loadSelectedDiff();
  }

  async function loadRecentChangeSets(options: { sessionId?: string; changeSetId?: string; file?: string } = {}) {
    sessionIdFilter.value = options.sessionId ?? sessionIdFilter.value;
    selectedChangeSetId.value = options.changeSetId ?? selectedChangeSetId.value;
    recentLoading.value = true;
    recentError.value = '';
    try {
      const response = await getRecentChangeSets();
      const mapped = (response.items ?? []).map(toChangeSet);
      recentChangeSets.value = sessionIdFilter.value
        ? mapped.filter((changeSet) => changeSet.sessionId === sessionIdFilter.value)
        : mapped;
      if (!selectedChangeSetId.value || !recentChangeSets.value.some((changeSet) => changeSet.id === selectedChangeSetId.value)) {
        selectedChangeSetId.value = recentChangeSets.value[0]?.id ?? '';
      }
      selectedFilePath.value = options.file
        ?? currentChangeSet.value?.changedFiles[0]?.path
        ?? null;
      loadedDiffs.value = {};
      await loadSelectedDiff();
    } catch (error) {
      recentChangeSets.value = [];
      recentError.value = error instanceof Error ? error.message : 'ChangeSets unavailable';
    } finally {
      recentLoading.value = false;
    }
  }

  async function selectChangeSet(changeSetId: string) {
    selectedChangeSetId.value = changeSetId;
    selectedFilePath.value = currentChangeSet.value?.changedFiles[0]?.path ?? null;
    diffError.value = '';
    diffEmptyMessage.value = '';
    await loadSelectedDiff();
  }

  function resetForSession(session: AgentSession) {
    selectedFilePath.value = session.changeSet.changedFiles[0]?.path ?? null;
    loadedDiffs.value = {};
    diffError.value = '';
    diffEmptyMessage.value = '';
    diffLoading.value = false;
  }

  async function loadSelectedDiff() {
    const sessionStore = useSessionStore();
    const changeSet = currentChangeSet.value;
    const file = selectedFile.value;
    if (!changeSet || !file || sessionStore.selectedSessionDataSource !== 'backend') {
      if (selectedRecentChangeSet.value && changeSet && file) {
        await loadBackendDiff(changeSet.id, file.path, file.diff);
      }
      return;
    }
    await loadBackendDiff(changeSet.id, file.path, file.diff);
  }

  async function loadBackendDiff(changeSetId: string, path: string, existingDiff: string) {
    const key = diffKey(changeSetId, path);
    if (loadedDiffs.value[key] || existingDiff) {
      return;
    }
    diffLoading.value = true;
    diffError.value = '';
    diffEmptyMessage.value = '';
    try {
      const response = await getChangeSetFileDiff(changeSetId, path);
      if (response.diff) {
        loadedDiffs.value = { ...loadedDiffs.value, [key]: response.diff };
      } else {
        diffEmptyMessage.value = response.message || '暂无真实 diff';
      }
    } catch (error) {
      diffError.value = error instanceof Error ? error.message : 'Diff unavailable';
    } finally {
      diffLoading.value = false;
    }
  }

  return {
    selectedFilePath,
    currentChangeSet,
    recentChangeSets,
    recentLoading,
    recentError,
    selectedChangeSetId,
    sessionIdFilter,
    selectedFile,
    currentDiff,
    diffLoading,
    diffError,
    diffEmptyMessage,
    selectFile,
    selectChangeSet,
    loadRecentChangeSets,
    loadSelectedDiff,
    resetForSession,
  };
});

function diffKey(changeSetId: string, path: string) {
  return `${changeSetId}:${path}`;
}

function toChangeSet(raw: unknown): ChangeSet {
  const item = asRecord(raw);
  const id = String(item.id ?? item.changeSetId ?? 'changeset');
  const sessionId = String(item.sessionId ?? '');
  const diffPatch = String(item.diffPatch ?? item.patch ?? '');
  const changedFiles = changedFilesFromRaw(item.changedFiles, diffPatch);
  return {
    id,
    sessionId,
    teamSessionId: String(item.teamSessionId ?? ''),
    taskId: String(item.taskId ?? ''),
    baseCommit: String(item.baseCommit ?? ''),
    status: normalizeChangeSetStatus(item.status),
    diffSummary: String(item.diffSummary ?? item.summary ?? 'Backend ChangeSet'),
    changedFiles,
    suggestedTests: stringArray(item.suggestedTests),
    executedTests: stringArray(item.executedTests),
    verifierStatus: normalizeVerifierStatus(item.verifierStatus),
    verifierReasons: stringArray(item.verifierReasons),
    commitMessage: String(item.commitMessage ?? ''),
    updatedAt: String(item.updatedAt ?? new Date().toISOString()),
  };
}

function changedFilesFromRaw(value: unknown, patch: string): ChangedFile[] {
  if (!Array.isArray(value)) {
    return [];
  }
  return value.map((entry) => {
    if (typeof entry === 'string') {
      return changedFile(entry, patch);
    }
    const item = asRecord(entry);
    const path = String(item.path ?? item.file ?? '');
    return {
      path,
      changeType: normalizeChangeType(item.changeType ?? item.type),
      additions: numberOrZero(item.additions),
      deletions: numberOrZero(item.deletions),
      diff: String(item.diff ?? filePatch(patch, path)),
    };
  }).filter((file) => file.path);
}

function changedFile(path: string, patch: string): ChangedFile {
  return {
    path,
    changeType: patch.includes(`deleted file mode`) ? 'deleted' : patch.includes(`new file mode`) ? 'added' : 'modified',
    additions: countForFile(patch, path, '+'),
    deletions: countForFile(patch, path, '-'),
    diff: filePatch(patch, path),
  };
}

function filePatch(patch: string, path: string) {
  if (!patch.trim()) {
    return '';
  }
  const marker = `diff --git a/${path} b/${path}`;
  const start = patch.indexOf(marker);
  if (start < 0) {
    return patch;
  }
  const next = patch.indexOf('\ndiff --git ', start + 1);
  return next < 0 ? patch.slice(start) : patch.slice(start, next);
}

function countForFile(patch: string, path: string, prefix: '+' | '-') {
  return filePatch(patch, path)
    .split('\n')
    .filter((line) => line.startsWith(prefix) && !line.startsWith(`${prefix}${prefix}${prefix}`))
    .length;
}

function normalizeChangeType(value: unknown): FileChangeType {
  const normalized = String(value ?? '').toLowerCase();
  return ['added', 'modified', 'deleted'].includes(normalized) ? normalized as FileChangeType : 'modified';
}

function normalizeChangeSetStatus(value: unknown): ChangeSet['status'] {
  const normalized = String(value ?? '').toUpperCase();
  return ['DRAFT', 'VERIFIED', 'APPROVED', 'COMMITTED'].includes(normalized)
    ? normalized as ChangeSet['status']
    : 'DRAFT';
}

function normalizeVerifierStatus(value: unknown): ChangeSet['verifierStatus'] {
  const normalized = String(value ?? '').toUpperCase();
  return ['PASS', 'FAIL', 'PENDING'].includes(normalized)
    ? normalized as ChangeSet['verifierStatus']
    : 'PENDING';
}

function numberOrZero(value: unknown) {
  return typeof value === 'number' && Number.isFinite(value) ? value : 0;
}

function stringArray(value: unknown): string[] {
  return Array.isArray(value) ? value.map(String) : [];
}

function asRecord(value: unknown): Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value) ? value as Record<string, unknown> : {};
}
