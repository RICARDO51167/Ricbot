import type { FileReference, FileReferenceSource } from '@/types/file-reference';

export const FILE_REFERENCE_PATH_KEYS = [
  'file',
  'path',
  'filePath',
  'targetFile',
  'changedFile',
  'relativePath',
  'files',
  'changedFiles',
] as const;

const PATH_KEYS = new Set<string>(FILE_REFERENCE_PATH_KEYS);
const LINE_KEYS = ['line', 'startLine', 'lineNumber'];
const MAX_SCAN_DEPTH = 5;

export interface FileReferenceContext {
  source?: FileReferenceSource;
  eventId?: string;
  runId?: string;
  changeSetId?: string;
  toolName?: string;
  label?: string;
}

export function extractFileReferencesFromPayload(payload: unknown, context: FileReferenceContext = {}) {
  const references: FileReference[] = [];
  const seenObjects = new Set<unknown>();
  scanPayload(payload, context, references, seenObjects, 0);
  return dedupeFileReferences(references);
}

export function dedupeFileReferences(references: FileReference[]) {
  const byKey = new Map<string, FileReference>();
  for (const reference of references) {
    const normalizedPath = normalizeWorkspacePath(reference.normalizedPath || reference.path);
    if (!normalizedPath) {
      continue;
    }
    const line = reference.line ?? reference.startLine ?? 0;
    const key = `${normalizedPath}:${line}`;
    if (byKey.has(key)) {
      continue;
    }
    byKey.set(key, {
      ...reference,
      path: normalizedPath,
      normalizedPath,
      line: normalizeLine(reference.line),
      startLine: normalizeLine(reference.startLine),
      endLine: normalizeLine(reference.endLine),
      column: normalizeLine(reference.column),
    });
  }
  return [...byKey.values()];
}

export function normalizeWorkspacePath(value: string) {
  const path = value.trim().replace(/\\/g, '/').replace(/^\.\/+/, '');
  if (!path || path.startsWith('/') || path.includes('://') || path.split('/').includes('..')) {
    return '';
  }
  return path;
}

function scanPayload(
  payload: unknown,
  context: FileReferenceContext,
  references: FileReference[],
  seenObjects: Set<unknown>,
  depth: number,
) {
  if (!payload || typeof payload !== 'object' || depth > MAX_SCAN_DEPTH || seenObjects.has(payload)) {
    return;
  }
  seenObjects.add(payload);

  if (Array.isArray(payload)) {
    for (const item of payload) {
      scanPayload(item, context, references, seenObjects, depth + 1);
    }
    return;
  }

  const record = payload as Record<string, unknown>;
  for (const [key, value] of Object.entries(record)) {
    if (PATH_KEYS.has(key)) {
      collectPathValue(value, context, references, record);
    }
    if (value && typeof value === 'object') {
      scanPayload(value, context, references, seenObjects, depth + 1);
    }
  }
}

function collectPathValue(
  value: unknown,
  context: FileReferenceContext,
  references: FileReference[],
  owner: Record<string, unknown>,
) {
  if (typeof value === 'string') {
    addReference(value, context, references, owner, 'high');
    return;
  }
  if (Array.isArray(value)) {
    for (const item of value) {
      collectPathValue(item, context, references, owner);
    }
    return;
  }
  if (value && typeof value === 'object') {
    const record = value as Record<string, unknown>;
    for (const key of FILE_REFERENCE_PATH_KEYS) {
      collectPathValue(record[key], context, references, record);
    }
  }
}

function addReference(
  pathValue: string,
  context: FileReferenceContext,
  references: FileReference[],
  owner: Record<string, unknown>,
  confidence: FileReference['confidence'],
) {
  const normalizedPath = normalizeWorkspacePath(pathValue);
  if (!normalizedPath) {
    return;
  }
  const line = firstLine(owner);
  references.push({
    path: normalizedPath,
    normalizedPath,
    line,
    startLine: firstNumber(owner.startLine) ?? firstNumber((owner.range as Record<string, unknown> | undefined)?.startLine) ?? line,
    endLine: firstNumber(owner.endLine) ?? firstNumber((owner.range as Record<string, unknown> | undefined)?.endLine),
    column: firstNumber(owner.column),
    source: context.source ?? 'timeline',
    label: context.label,
    eventId: context.eventId,
    runId: context.runId,
    changeSetId: context.changeSetId,
    toolName: context.toolName,
    confidence,
    payload: owner,
  });
}

function firstLine(record: Record<string, unknown>) {
  for (const key of LINE_KEYS) {
    const value = firstNumber(record[key]);
    if (value) {
      return value;
    }
  }
  const range = record.range as Record<string, unknown> | undefined;
  return firstNumber(range?.startLine) ?? firstNumber(range?.line);
}

function firstNumber(value: unknown) {
  if (typeof value === 'number' && Number.isFinite(value) && value > 0) {
    return Math.floor(value);
  }
  if (typeof value === 'string') {
    const parsed = Number.parseInt(value, 10);
    if (Number.isFinite(parsed) && parsed > 0) {
      return parsed;
    }
  }
  return undefined;
}

function normalizeLine(value: number | undefined) {
  return Number.isFinite(value) && value && value > 0 ? Math.floor(value) : undefined;
}
