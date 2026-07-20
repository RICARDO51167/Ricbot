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
  const loadingDirs = ref<Record<string, boolean>>({});
  const loadedDirs = ref(new Set<string>());
  const searchKeyword = ref('');
  const searchResults = ref<WorkspaceSearchResult[]>([]);
  const searchLoading = ref(false);
  const searchError = ref('');
  const previewCache = ref<Record<string, WorkspaceFileContent>>({});
  const previewErrorCache = ref<Record<string, string>>({});

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
        depth: options.depth ?? 1,
        includeHidden: options.includeHidden ?? false,
      });
      workspaceRoot.value = response.workspace;
      treeRoot.value = response.root;
      treeNodes.value = response.nodes ?? [];
      loadedDirs.value = collectLoadedDirectoryPaths(treeNodes.value, new Set([response.root || '']));
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
    loadedDirs.value = new Set();
    loadingDirs.value = {};
    await loadTree({
      root: treeRoot.value,
    });
  }

  async function toggleDirectory(path: string) {
    const next = new Set(expandedPaths.value);
    if (next.has(path)) {
      next.delete(path);
      expandedPaths.value = next;
    } else {
      next.add(path);
      expandedPaths.value = next;
      await loadDirectory(path);
    }
  }

  async function loadDirectory(path: string, options: { force?: boolean } = {}) {
    const cleanPath = normalizePath(path);
    if (!cleanPath && treeNodes.value.length > 0 && loadedDirs.value.has('') && !options.force) {
      return;
    }
    if (loadedDirs.value.has(cleanPath) && !options.force) {
      return;
    }
    loadingDirs.value = { ...loadingDirs.value, [cleanPath]: true };
    treeError.value = '';
    try {
      const response = await getWorkspaceTree({
        root: cleanPath,
        depth: 1,
        includeHidden: false,
      });
      workspaceRoot.value = response.workspace || workspaceRoot.value;
      mergeTreeChildren(cleanPath, response.nodes ?? []);
      const nextLoaded = new Set(loadedDirs.value);
      nextLoaded.add(cleanPath);
      for (const node of response.nodes ?? []) {
        if (node.type === 'directory' && node.loaded) {
          nextLoaded.add(node.path);
        }
      }
      loadedDirs.value = nextLoaded;
      updateTreeVisibilityHint();
    } catch (error) {
      const message = error instanceof Error ? error.message : 'Directory load failed';
      treeError.value = message;
      throw error;
    } finally {
      const nextLoading = { ...loadingDirs.value };
      delete nextLoading[cleanPath];
      loadingDirs.value = nextLoading;
    }
  }

  async function ensurePathLoaded(filePath: string) {
    const cleanPath = normalizePath(filePath);
    const parts = cleanPath.split('/').filter(Boolean);
    if (parts.length <= 1) {
      updateTreeVisibilityHint();
      return;
    }
    if (treeNodes.value.length === 0 && !loadedDirs.value.has('')) {
      await loadTree({ depth: 1 });
    }
    const nextExpanded = new Set(expandedPaths.value);
    for (let index = 1; index < parts.length; index += 1) {
      const parentPath = parts.slice(0, index).join('/');
      nextExpanded.add(parentPath);
      expandedPaths.value = new Set(nextExpanded);
      await loadDirectory(parentPath);
    }
    updateTreeVisibilityHint();
  }

  function mergeTreeChildren(parentPath: string, children: WorkspaceNode[]) {
    const normalizedParent = normalizePath(parentPath);
    const normalizedChildren = children.map(normalizeNode);
    if (!normalizedParent) {
      treeNodes.value = normalizedChildren;
      return;
    }
    treeNodes.value = mergeChildrenIntoNodes(treeNodes.value, normalizedParent, normalizedChildren);
  }

  async function openFile(path: string, line?: number | null) {
    const nextPath = normalizePath(path);
    const nextLine = normalizeLine(line);
    if (nextPath && nextPath === selectedPath.value && file.value && !fileLoading.value) {
      selectedLine.value = nextLine;
      return;
    }
    selectedPath.value = nextPath;
    selectedLine.value = nextLine;
    file.value = null;
    fileError.value = '';
    treeVisibilityHint.value = '';
    if (!selectedPath.value) {
      return;
    }
    expandParentPaths(selectedPath.value);
    try {
      await ensurePathLoaded(selectedPath.value);
    } catch (error) {
      if (isWorkspaceSecurityError(error)) {
        treeVisibilityHint.value = 'workspace.blockedBySecurityPolicy';
      }
    }
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

  function focusLine(line?: number | null) {
    selectedLine.value = normalizeLine(line);
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

  async function loadPreview(reference: { normalizedPath?: string; path: string }) {
    const path = normalizePath(reference.normalizedPath || reference.path);
    if (!path) {
      throw new Error('Preview unavailable');
    }
    if (previewCache.value[path]) {
      return previewCache.value[path];
    }
    if (previewErrorCache.value[path]) {
      throw new Error(previewErrorCache.value[path]);
    }
    try {
      const content = await getWorkspaceFileContent(path);
      previewCache.value = { ...previewCache.value, [path]: content };
      return content;
    } catch (error) {
      const message = error instanceof Error ? error.message : 'Preview unavailable';
      previewErrorCache.value = { ...previewErrorCache.value, [path]: message };
      throw error;
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
    if (treeVisibilityHint.value === 'workspace.blockedBySecurityPolicy') {
      return;
    }
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
    loadingDirs,
    loadedDirs,
    searchKeyword,
    searchResults,
    searchLoading,
    searchError,
    previewCache,
    previewErrorCache,
    changedPaths,
    selectedIsChanged,
    loadTree,
    refreshTree,
    openFile,
    searchFiles,
    clearSearch,
    focusLine,
    toggleDirectory,
    loadDirectory,
    ensurePathLoaded,
    mergeTreeChildren,
    loadPreview,
    clearSelection,
    expandParentPaths,
  };
});

function collectLoadedDirectoryPaths(nodes: WorkspaceNode[], existing: Set<string>) {
  const next = new Set(existing);
  for (const node of nodes) {
    if (node.type === 'directory') {
      if (node.loaded) {
        next.add(node.path);
      }
      collectLoadedDirectoryPaths(node.children ?? [], next).forEach((path) => next.add(path));
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

function normalizeNode(node: WorkspaceNode): WorkspaceNode {
  if (node.type !== 'directory') {
    return { ...node, children: node.children ?? [], loaded: true, hasChildren: false };
  }
  return {
    ...node,
    children: node.children ?? [],
    loaded: node.loaded ?? !(node.hasChildren ?? false),
    hasChildren: node.hasChildren ?? Boolean(node.children?.length),
  };
}

function mergeChildrenIntoNodes(nodes: WorkspaceNode[], parentPath: string, children: WorkspaceNode[]): WorkspaceNode[] {
  return nodes.map((node) => {
    if (node.path === parentPath) {
      return {
        ...normalizeNode(node),
        children,
        loaded: true,
        hasChildren: node.hasChildren ?? children.length > 0,
      };
    }
    if (node.children?.length) {
      return {
        ...node,
        children: mergeChildrenIntoNodes(node.children, parentPath, children),
      };
    }
    return node;
  });
}

function isWorkspaceSecurityError(error: unknown) {
  if (!(error instanceof Error)) {
    return false;
  }
  const message = error.message.toLowerCase();
  return message.includes('blocked')
    || message.includes('security policy')
    || message.includes('outside workspace');
}
