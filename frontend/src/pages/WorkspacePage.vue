<template>
  <section class="workspace-page console-page-card">
    <WorkspaceToolbar
      :workspace-root="store.workspaceRoot"
      :loading="store.treeLoading"
      @refresh="refreshTree"
    />

    <div v-if="store.treeError" class="workspace-error">{{ store.treeError }}</div>

    <div class="workspace-layout">
      <aside class="workspace-tree-pane">
        <WorkspaceTree
          :nodes="store.treeNodes"
          :selected-path="store.selectedPath"
          :changed-paths="store.changedPaths"
          @select-file="selectFile"
        />
      </aside>
      <FileViewer
        :file="store.file"
        :selected-path="store.selectedPath"
        :loading="store.fileLoading"
        :error="store.fileError"
        :changed="store.selectedIsChanged"
      />
    </div>
  </section>
</template>

<script setup lang="ts">
import { onMounted, watch } from 'vue';

import FileViewer from '@/components/workspace/FileViewer.vue';
import WorkspaceToolbar from '@/components/workspace/WorkspaceToolbar.vue';
import WorkspaceTree from '@/components/workspace/WorkspaceTree.vue';
import { currentRoute, replace } from '@/router';
import { useWorkspaceStore } from '@/stores/workspaceStore';

const store = useWorkspaceStore();
let applyingQuery = false;

onMounted(async () => {
  await store.loadTree();
  await openQueryFile();
});

watch(
  () => currentRoute.value.query.file,
  () => {
    if (currentRoute.value.path === '/console/workspace') {
      void openQueryFile();
    }
  },
);

async function refreshTree() {
  await store.loadTree();
  await openQueryFile();
}

async function selectFile(path: string) {
  applyingQuery = true;
  replace('/console/workspace', { file: path });
  applyingQuery = false;
  await store.openFile(path);
}

async function openQueryFile() {
  if (applyingQuery) {
    return;
  }
  const file = currentRoute.value.query.file ?? '';
  if (file) {
    await store.openFile(file);
  } else {
    store.clearSelection();
  }
}
</script>
