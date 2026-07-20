import { normalizeWorkspacePath } from './fileReferences';

export interface DiffFileHunk {
  filePath: string;
  oldPath?: string;
  newPath?: string;
  oldStart: number;
  oldLines: number;
  newStart: number;
  newLines: number;
  header: string;
  lines: string[];
  deleted?: boolean;
}

const DIFF_GIT_RE = /^diff --git a\/(.+) b\/(.+)$/;
const HUNK_RE = /^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@/;

export function parseDiffHunks(diff: string) {
  const hunks: DiffFileHunk[] = [];
  const lines = (diff ?? '').split('\n');
  let currentOldPath = '';
  let currentNewPath = '';
  let currentHunk: DiffFileHunk | null = null;

  function finishHunk() {
    if (currentHunk) {
      hunks.push(currentHunk);
      currentHunk = null;
    }
  }

  for (const line of lines) {
    const fileMatch = line.match(DIFF_GIT_RE);
    if (fileMatch) {
      finishHunk();
      currentOldPath = normalizeWorkspacePath(fileMatch[1]);
      currentNewPath = normalizeWorkspacePath(fileMatch[2]);
      continue;
    }
    if (line.startsWith('--- ')) {
      const oldPath = normalizeDiffPath(line.slice(4));
      if (oldPath) {
        currentOldPath = oldPath;
      }
      continue;
    }
    if (line.startsWith('+++ ')) {
      const newPath = normalizeDiffPath(line.slice(4));
      currentNewPath = newPath;
      continue;
    }
    const hunkMatch = line.match(HUNK_RE);
    if (hunkMatch) {
      finishHunk();
      const deleted = !currentNewPath;
      const oldStart = Number.parseInt(hunkMatch[1], 10);
      const oldLines = Number.parseInt(hunkMatch[2] ?? '1', 10);
      const newStart = Number.parseInt(hunkMatch[3], 10);
      const newLines = Number.parseInt(hunkMatch[4] ?? '1', 10);
      const filePath = currentNewPath || currentOldPath;
      if (!filePath) {
        continue;
      }
      currentHunk = {
        filePath,
        oldPath: currentOldPath || undefined,
        newPath: currentNewPath || undefined,
        oldStart,
        oldLines,
        newStart,
        newLines,
        header: line,
        lines: [],
        deleted,
      };
      continue;
    }
    if (currentHunk) {
      currentHunk.lines.push(line);
    }
  }
  finishHunk();
  return hunks;
}

function normalizeDiffPath(value: string) {
  const clean = value.trim();
  if (clean === '/dev/null') {
    return '';
  }
  return normalizeWorkspacePath(clean.replace(/^[ab]\//, ''));
}
