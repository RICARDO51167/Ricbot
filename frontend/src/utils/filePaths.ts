import { extractFileReferencesFromPayload, normalizeWorkspacePath } from './fileReferences';

export const WORKSPACE_PATH_KEYS = [
  'file',
  'path',
  'filePath',
  'targetFile',
  'changedFile',
  'relativePath',
  'files',
  'changedFiles',
];

export interface WorkspaceFileRef {
  path: string;
  line?: number;
}

export function extractFilePathsFromPayload(payload: unknown): string[] {
  return extractFileReferencesFromPayload(payload).map((reference) => reference.normalizedPath);
}

export function extractWorkspaceFileRefsFromPayload(payload: unknown): WorkspaceFileRef[] {
  return extractFileReferencesFromPayload(payload).map((reference) => ({
    path: reference.normalizedPath,
    line: reference.line ?? reference.startLine,
  }));
}

export { normalizeWorkspacePath };
