export type FileReferenceSource =
  | 'timeline'
  | 'tool_call'
  | 'trace'
  | 'changeset'
  | 'diff'
  | 'approval'
  | 'workspace';

export type FileReferenceConfidence = 'high' | 'medium' | 'low';

export type FileReference = {
  path: string;
  normalizedPath: string;
  line?: number;
  startLine?: number;
  endLine?: number;
  column?: number;
  source: FileReferenceSource;
  label?: string;
  eventId?: string;
  runId?: string;
  changeSetId?: string;
  toolName?: string;
  confidence: FileReferenceConfidence;
  payload?: unknown;
};
