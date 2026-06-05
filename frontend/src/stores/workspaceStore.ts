import { computed, ref } from 'vue';
import { defineStore } from 'pinia';

import { getWorkspaceFileContent, getWorkspaceTree } from '@/api/consoleApi';
import type { ConsoleWorkspaceFileContentResponse, ConsoleWorkspaceTreeNode } from '@/api/consoleApi';
import { useChangeSetStore } from './changeSetStore';

export type WorkspaceNode = ConsoleWorkspaceTreeNode;
export type WorkspaceFileContent = ConsoleWorkspaceFileContentResponse;

export const useWorkspaceStore = defineStore('workspace', () => {
  const workspaceRoot = ref('');
  const treeRoot = ref('');
  const treeNodes = ref<WorkspaceNode[]>([]);
  const selectedPath = ref('');
  const file = ref<WorkspaceFileContent | null>(null);
  const treeLoading = ref(false);
  const fileLoading = ref(false);
  const treeError = ref('');
  const fileError = ref('');
  const expandedPaths = ref(new Set<string>());

  const changedPaths = computed(() => new Set(
    useChangeSetStore().currentChangeSet?.changedFiles.map((item) => item.path) ?? [],
  ));

  const selectedIsChanged = computed(() => selectedPath.value ? changedPaths.value.has(selectedPath.value) : false);

  async function loadTree(options: { root?: string; depth?: number; includeHidden?: boolean } = {}) {
    treeLoading.value = true;
    treeError.value = '';
    try {
      const response = await getWorkspaceTree({
        root: options.root ?? '',
        depth: options.depth ?? 3,
        includeHidden: options.includeHidden ?? false,
      });
      workspaceRoot.value = response.workspace;
      treeRoot.value = response.root;
      treeNodes.value = response.nodes ?? [];
      expandedPaths.value = collectDirectoryPaths(treeNodes.value, expandedPaths.value);
    } catch (error) {
      workspaceRoot.value = '';
      treeRoot.value = '';
      treeNodes.value = [];
      treeError.value = error instanceof Error ? error.message : 'Workspace unavailable';
    } finally {
      treeLoading.value = false;
    }
  }

  async function refreshTree() {
    await loadTree({
      root: treeRoot.value,
    });
  }

  function toggleDirectory(path: string) {
    const next = new Set(expandedPaths.value);
    if (next.has(path)) {
      next.delete(path);
    } else {
      next.add(path);
    }
    expandedPaths.value = next;
  }

  async function openFile(path: string) {
    selectedPath.value = path;
    file.value = null;
    fileError.value = '';
    if (!path.trim()) {
      return;
    }
    syncChangeSetFile(path);
    fileLoading.value = true;
    try {
      file.value = await getWorkspaceFileContent(path);
    } catch (error) {
      fileError.value = error instanceof Error ? error.message : 'File unavailable';
    } finally {
      fileLoading.value = false;
    }
  }

  function clearSelection() {
    selectedPath.value = '';
    file.value = null;
    fileError.value = '';
    fileLoading.value = false;
  }

  function syncChangeSetFile(path: string) {
    const changeSet = useChangeSetStore();
    if (changeSet.currentChangeSet?.changedFiles.some((item) => item.path === path)) {
      void changeSet.selectFile(path);
    }
  }

  return {
    workspaceRoot,
    treeRoot,
    treeNodes,
    selectedPath,
    file,
    treeLoading,
    fileLoading,
    treeError,
    fileError,
    expandedPaths,
    changedPaths,
    selectedIsChanged,
    loadTree,
    refreshTree,
    openFile,
    toggleDirectory,
    clearSelection,
  };
});

function collectDirectoryPaths(nodes: WorkspaceNode[], existing: Set<string>) {
  const next = new Set(existing);
  for (const node of nodes) {
    if (node.type === 'directory') {
      next.add(node.path);
      collectDirectoryPaths(node.children ?? [], next).forEach((path) => next.add(path));
    }
  }
  return next;
}
