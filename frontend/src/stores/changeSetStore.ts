import { computed, ref } from 'vue';
import { defineStore } from 'pinia';

import { getChangeSetFileDiff } from '@/api/consoleApi';
import type { AgentSession } from '@/types/agent-console';
import { useSessionStore } from './sessionStore';

export const useChangeSetStore = defineStore('changeSets', () => {
  const selectedFilePath = ref<string | null>(null);
  const loadedDiffs = ref<Record<string, string>>({});
  const diffLoading = ref(false);
  const diffError = ref('');
  const diffEmptyMessage = ref('');

  const currentChangeSet = computed(() => useSessionStore().currentSession?.changeSet ?? null);

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
      return;
    }
    const key = diffKey(changeSet.id, file.path);
    if (loadedDiffs.value[key] || file.diff) {
      return;
    }
    diffLoading.value = true;
    diffError.value = '';
    diffEmptyMessage.value = '';
    try {
      const response = await getChangeSetFileDiff(changeSet.id, file.path);
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
    selectedFile,
    currentDiff,
    diffLoading,
    diffError,
    diffEmptyMessage,
    selectFile,
    loadSelectedDiff,
    resetForSession,
  };
});

function diffKey(changeSetId: string, path: string) {
  return `${changeSetId}:${path}`;
}
