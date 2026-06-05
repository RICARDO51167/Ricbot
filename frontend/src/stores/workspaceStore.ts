import { computed, ref } from 'vue';
import { defineStore } from 'pinia';

import { getWorkspaceFileContent, getWorkspaceTree, searchWorkspaceFiles } from '@/api/consoleApi';
import type {
  ConsoleWorkspaceFileContentResponse,
  ConsoleWorkspaceSearchResult,
  ConsoleWorkspaceTreeNode,
} from '@/api/consoleApi';
import { useChangeSetStore } from './changeSetStore';

export type WorkspaceNode = ConsoleWorkspaceTreeNode;
export type WorkspaceFileContent = ConsoleWorkspaceFileContentResponse;
export type WorkspaceSearchResult = ConsoleWorkspaceSearchResult;

export const useWorkspaceStore = defineStore('workspace', () => {
  const workspaceRoot = ref('');
  const treeRoot = ref('');
  const treeNodes = ref<WorkspaceNode[]>([]);
  const selectedPath = ref('');
  const selectedLine = ref<number | null>(null);
  const file = ref<WorkspaceFileContent | null>(null);
  const treeLoading = ref(false);
  const fileLoading = ref(false);
  const treeError = ref('');
  const fileError = ref('');
  const treeVisibilityHint = ref('');
  const expandedPaths = ref(new Set<string>());
  const searchKeyword = ref('');
  const searchResults = ref<WorkspaceSearchResult[]>([]);
  const searchLoading = ref(false);
  const searchError = ref('');

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
      updateTreeVisibilityHint();
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

  async function openFile(path: string, line?: number | null) {
    selectedPath.value = normalizePath(path);
    selectedLine.value = normalizeLine(line);
    file.value = null;
    fileError.value = '';
    treeVisibilityHint.value = '';
    if (!selectedPath.value) {
      return;
    }
    expandParentPaths(selectedPath.value);
    updateTreeVisibilityHint();
    syncChangeSetFile(selectedPath.value);
    fileLoading.value = true;
    try {
      file.value = await getWorkspaceFileContent(selectedPath.value);
    } catch (error) {
      fileError.value = error instanceof Error ? error.message : 'File unavailable';
    } finally {
      fileLoading.value = false;
    }
  }

  function clearSelection() {
    selectedPath.value = '';
    selectedLine.value = null;
    file.value = null;
    fileError.value = '';
    fileLoading.value = false;
    treeVisibilityHint.value = '';
  }

  async function searchFiles(keyword: string) {
    const cleanKeyword = keyword.trim();
    searchKeyword.value = cleanKeyword;
    searchError.value = '';
    if (!cleanKeyword) {
      clearSearch();
      return;
    }
    searchLoading.value = true;
    try {
      const response = await searchWorkspaceFiles({ keyword: cleanKeyword, limit: 50 });
      workspaceRoot.value = response.workspace || workspaceRoot.value;
      searchKeyword.value = response.keyword || cleanKeyword;
      searchResults.value = response.results ?? [];
    } catch (error) {
      searchResults.value = [];
      searchError.value = error instanceof Error ? error.message : 'Search failed';
    } finally {
      searchLoading.value = false;
    }
  }

  function clearSearch() {
    searchKeyword.value = '';
    searchResults.value = [];
    searchError.value = '';
    searchLoading.value = false;
  }

  function syncChangeSetFile(path: string) {
    const changeSet = useChangeSetStore();
    if (changeSet.currentChangeSet?.changedFiles.some((item) => item.path === path)) {
      void changeSet.selectFile(path);
    }
  }

  function expandParentPaths(path: string) {
    const parts = normalizePath(path).split('/').filter(Boolean);
    if (parts.length <= 1) {
      return;
    }
    const next = new Set(expandedPaths.value);
    for (let index = 1; index < parts.length; index += 1) {
      next.add(parts.slice(0, index).join('/'));
    }
    expandedPaths.value = next;
  }

  function updateTreeVisibilityHint() {
    if (!selectedPath.value || treeNodes.value.length === 0) {
      treeVisibilityHint.value = '';
      return;
    }
    treeVisibilityHint.value = pathExistsInTree(treeNodes.value, selectedPath.value)
      ? ''
      : 'workspace.fileOpenedNotVisible';
  }

  return {
    workspaceRoot,
    treeRoot,
    treeNodes,
    selectedPath,
    selectedLine,
    file,
    treeLoading,
    fileLoading,
    treeError,
    fileError,
    treeVisibilityHint,
    expandedPaths,
    searchKeyword,
    searchResults,
    searchLoading,
    searchError,
    changedPaths,
    selectedIsChanged,
    loadTree,
    refreshTree,
    openFile,
    searchFiles,
    clearSearch,
    toggleDirectory,
    clearSelection,
    expandParentPaths,
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

function normalizePath(path: string) {
  return path.trim().replace(/\\/g, '/').replace(/^\.\/+/, '');
}

function normalizeLine(line?: number | null) {
  if (!Number.isFinite(line) || !line || line < 1) {
    return null;
  }
  return Math.floor(line);
}

function pathExistsInTree(nodes: WorkspaceNode[], path: string): boolean {
  for (const node of nodes) {
    if (node.path === path) {
      return true;
    }
    if (node.children?.length && pathExistsInTree(node.children, path)) {
      return true;
    }
  }
  return false;
}
