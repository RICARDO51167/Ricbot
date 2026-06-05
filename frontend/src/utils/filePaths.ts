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

const PATH_KEY_SET = new Set(WORKSPACE_PATH_KEYS);
const LINE_KEYS = ['line', 'startLine'];

export interface WorkspaceFileRef {
  path: string;
  line?: number;
}

export function extractFilePathsFromPayload(payload: unknown): string[] {
  return extractWorkspaceFileRefsFromPayload(payload).map((ref) => ref.path);
}

export function extractWorkspaceFileRefsFromPayload(payload: unknown): WorkspaceFileRef[] {
  const refs: WorkspaceFileRef[] = [];
  const seenPaths = new Set<string>();
  const seenObjects = new Set<unknown>();
  const queue: unknown[] = [payload];

  while (queue.length > 0) {
    const value = queue.shift();
    if (!value || typeof value !== 'object' || seenObjects.has(value)) {
      continue;
    }
    seenObjects.add(value);

    if (Array.isArray(value)) {
      queue.push(...value);
      continue;
    }

    const record = value as Record<string, unknown>;
    for (const [key, raw] of Object.entries(record)) {
      if (PATH_KEY_SET.has(key)) {
        collectPathValue(raw, refs, seenPaths, lineFromRecord(record));
      }
      if (raw && typeof raw === 'object') {
        queue.push(raw);
      }
    }
  }

  return refs;
}

function collectPathValue(value: unknown, refs: WorkspaceFileRef[], seenPaths: Set<string>, line?: number) {
  if (typeof value === 'string') {
    addPath(value, refs, seenPaths, line);
    return;
  }
  if (Array.isArray(value)) {
    for (const item of value) {
      collectPathValue(item, refs, seenPaths, line);
    }
    return;
  }
  if (value && typeof value === 'object') {
    const record = value as Record<string, unknown>;
    const nestedLine = lineFromRecord(record) ?? line;
    for (const key of WORKSPACE_PATH_KEYS) {
      collectPathValue(record[key], refs, seenPaths, nestedLine);
    }
  }
}

function addPath(value: string, refs: WorkspaceFileRef[], seenPaths: Set<string>, line?: number) {
  const path = normalizeWorkspacePath(value);
  if (!path || seenPaths.has(path)) {
    return;
  }
  seenPaths.add(path);
  refs.push(line ? { path, line } : { path });
}

export function normalizeWorkspacePath(value: string) {
  const path = value.trim().replace(/\\/g, '/').replace(/^\.\/+/, '');
  if (!path || path.startsWith('/') || path.includes('://') || path.split('/').includes('..')) {
    return '';
  }
  return path;
}

function lineFromRecord(record: Record<string, unknown>) {
  for (const key of LINE_KEYS) {
    const value = record[key];
    if (typeof value === 'number' && Number.isFinite(value) && value > 0) {
      return Math.floor(value);
    }
    if (typeof value === 'string') {
      const parsed = Number.parseInt(value, 10);
      if (Number.isFinite(parsed) && parsed > 0) {
        return parsed;
      }
    }
  }
  return undefined;
}
