<template>
  <section class="workspace-page console-page-card">
    <WorkspaceToolbar
      :workspace-root="store.workspaceRoot"
      :loading="store.treeLoading"
      :search-keyword="store.searchKeyword"
      :search-loading="store.searchLoading"
      @refresh="refreshTree"
      @search="searchFiles"
      @clear-search="store.clearSearch"
    />

    <div v-if="store.treeError" class="workspace-error">{{ store.treeError }}</div>

    <div class="workspace-layout">
      <aside class="workspace-tree-pane">
        <div v-if="store.searchError" class="workspace-search-error">{{ store.searchError }}</div>
        <div v-if="store.searchKeyword" class="workspace-search-results">
          <h3>{{ t('workspace.searchResults') }}</h3>
          <div v-if="store.searchLoading" class="workspace-empty compact">{{ t('common.loading') }}</div>
          <div v-else-if="store.searchResults.length === 0" class="workspace-empty compact">
            {{ t('workspace.noMatchingFiles') }}
          </div>
          <template v-else>
            <button
              v-for="result in store.searchResults"
              :key="result.path"
              type="button"
              class="workspace-search-result"
              :class="{ selected: result.path === store.selectedPath }"
              data-test="workspace-search-result"
              @click="openWorkspaceFile(result.path)"
            >
              <span>{{ result.name }}</span>
              <small>{{ result.path }}</small>
            </button>
          </template>
        </div>
        <div v-if="treeVisibilityHintText" class="workspace-tree-hint">
          {{ treeVisibilityHintText }}
        </div>
        <WorkspaceTree
          :nodes="store.treeNodes"
          :selected-path="store.selectedPath"
          :changed-paths="store.changedPaths"
          :expanded-paths="store.expandedPaths"
          :loading-dirs="store.loadingDirs"
          @toggle-directory="toggleDirectory"
          @select-file="selectFile"
        />
      </aside>
      <FileViewer
        :file="store.file"
        :selected-path="store.selectedPath"
        :loading="store.fileLoading"
        :error="store.fileError"
        :changed="store.selectedIsChanged"
        :selected-line="store.selectedLine"
        :source-label="sourceLabel"
      />
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, watch } from 'vue';

import FileViewer from '@/components/workspace/FileViewer.vue';
import WorkspaceToolbar from '@/components/workspace/WorkspaceToolbar.vue';
import WorkspaceTree from '@/components/workspace/WorkspaceTree.vue';
import { currentRoute, replace } from '@/router';
import { useLocaleStore, type MessageKey } from '@/stores/localeStore';
import { useWorkspaceStore } from '@/stores/workspaceStore';

const store = useWorkspaceStore();
const { t } = useLocaleStore();
const treeVisibilityHintText = computed(() => (
  store.treeVisibilityHint ? t(store.treeVisibilityHint as MessageKey) : ''
));
const sourceLabel = computed(() => currentRoute.value.query.source === 'diff' ? 'diff' : '');
let applyingQuery = false;

onMounted(async () => {
  await store.loadTree();
  await openQueryFile();
});

watch(
  () => [currentRoute.value.query.file, currentRoute.value.query.line],
  () => {
    if (currentRoute.value.path === '/console/workspace') {
      void openQueryFile();
    }
  },
);

async function refreshTree() {
  await store.refreshTree();
  await openQueryFile();
}

async function selectFile(path: string) {
  await openWorkspaceFile(path);
}

async function toggleDirectory(path: string) {
  await store.toggleDirectory(path);
}

async function openWorkspaceFile(path: string, line?: number | null) {
  applyingQuery = true;
  replace('/console/workspace', line ? { file: path, line: String(line) } : { file: path });
  applyingQuery = false;
  await store.openFile(path, line ?? null);
}

async function searchFiles(keyword: string) {
  await store.searchFiles(keyword);
}

async function openQueryFile() {
  if (applyingQuery) {
    return;
  }
  const file = currentRoute.value.query.file ?? '';
  if (file) {
    const line = parseLine(currentRoute.value.query.line);
    if (store.selectedPath === file && store.file) {
      store.focusLine(line);
    } else {
      await store.openFile(file, line);
    }
  } else {
    store.clearSelection();
  }
}

function parseLine(value: string | undefined) {
  const parsed = Number.parseInt(value ?? '', 10);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : null;
}
</script>
