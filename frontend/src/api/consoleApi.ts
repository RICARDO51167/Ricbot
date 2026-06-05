import { getJson, postJson } from './client';

export interface ConsoleRuntimeResponse {
  appName: string;
  mode: 'backend';
  modelConfigured: boolean;
  provider: string | null;
  model: string | null;
  workspace: string;
  version: string;
  readonly?: boolean;
  configPath?: string;
}

export interface ConsoleSessionSummary {
  id?: string;
  key?: string;
  project?: string;
  title?: string;
  task?: string;
  status?: string;
  updatedAt?: string;
  messageCount?: number;
  toolCount?: number;
  approvalPendingCount?: number;
  changedFileCount?: number;
  riskLevel?: string;
  summary?: string;
}

export interface ConsoleSessionsResponse {
  mode: 'backend';
  items: ConsoleSessionSummary[];
}

export interface ConsoleApprovalsResponse {
  items: unknown[];
}

export interface ConsoleChangeSetsResponse {
  mode: 'backend';
  items: unknown[];
}

export interface ConsoleSessionDetail {
  sessionId: string;
  title?: string;
  workspace?: string;
  status?: string;
  model?: string | null;
  provider?: string | null;
  messages?: Record<string, unknown>[];
  runEvents?: Record<string, unknown>[];
  toolCalls?: Record<string, unknown>[];
  traceEvents?: Record<string, unknown>[];
  approvalEvents?: Record<string, unknown>[];
  changeSets?: Record<string, unknown>[];
  metadata?: Record<string, unknown>;
}

export interface ConsoleTimelineEvent {
  id?: string;
  type?: string;
  name?: string;
  category?: string;
  actor?: string;
  source?: string;
  runId?: string;
  sessionId?: string;
  time?: string;
  title?: string;
  summary?: string;
  status?: string;
  payload?: Record<string, unknown>;
}

export interface ConsoleSessionEventsResponse {
  sessionId: string;
  events: ConsoleTimelineEvent[];
  nextCursor: string;
}

export interface ConsoleStartRunResponse {
  sessionId?: string;
  runId?: string;
  status: ConsoleRunStatus | 'failed';
  code?: string;
  message?: string;
}

export type ConsoleRunStatus = 'queued' | 'running' | 'finished' | 'failed' | 'cancelled';

export interface ConsoleRunStatusResponse {
  runId: string;
  sessionId: string;
  status: ConsoleRunStatus;
  createdAt: string;
  startedAt?: string;
  finishedAt?: string;
  error?: string | null;
  inputPreview?: string;
}

export interface ConsoleCancelRunResponse {
  runId?: string;
  status: ConsoleRunStatus | 'failed';
  code?: string;
  message?: string;
}

export interface ConsoleApprovalActionResponse {
  requestId?: string;
  status?: 'PENDING' | 'APPROVED' | 'REJECTED' | 'failed';
  executed?: boolean;
  code?: string;
  message?: string;
}

export interface ConsoleChangeSetFileDiffResponse {
  changeSetId: string;
  path: string;
  diff: string;
  truncated?: boolean;
  message?: string;
}

export interface ConsoleWorkspaceTreeNode {
  name: string;
  path: string;
  type: 'directory' | 'file';
  size?: number;
  modifiedAt?: string;
  children?: ConsoleWorkspaceTreeNode[];
}

export interface ConsoleWorkspaceTreeResponse {
  workspace: string;
  root: string;
  nodes: ConsoleWorkspaceTreeNode[];
}

export interface ConsoleWorkspaceFileContentResponse {
  path: string;
  language: string;
  size: number;
  modifiedAt: string;
  binary: boolean;
  truncated: boolean;
  content: string;
}

export interface ConsoleWorkspaceSearchResult {
  name: string;
  path: string;
  type: 'file';
  size: number;
  modifiedAt: string;
  score: number;
}

export interface ConsoleWorkspaceSearchResponse {
  workspace: string;
  keyword: string;
  results: ConsoleWorkspaceSearchResult[];
}

export interface ConsoleRunHistoryItem {
  runId: string;
  sessionId: string;
  status: ConsoleRunStatus;
  inputPreview: string;
  createdAt: string;
  startedAt?: string;
  finishedAt?: string;
  durationMs: number;
  model?: string;
  toolCallCount: number;
  approvalCount: number;
  changeSetCount: number;
  errorCount: number;
  lastEventName: string;
  lastEventSummary: string;
}

export interface ConsoleRunHistoryResponse {
  sessionId: string;
  runs: ConsoleRunHistoryItem[];
}

export interface ConsoleEventSearchQuery {
  sessionId?: string;
  runId?: string;
  category?: string;
  status?: string;
  keyword?: string;
  since?: string;
  until?: string;
  limit?: number;
  after?: string;
}

export interface ConsoleEventSearchResponse {
  events: ConsoleTimelineEvent[];
  nextCursor: string;
}

export interface ConsoleMetricsSummary {
  scope: {
    sessionId: string;
    since: string;
    until: string;
  };
  runs: {
    total: number;
    queued: number;
    running: number;
    finished: number;
    failed: number;
    cancelled: number;
    successRate: number;
    avgDurationMs: number;
  };
  events: {
    total: number;
    run: number;
    tool: number;
    approval: number;
    changeset: number;
    error: number;
    system: number;
  };
  tools: {
    totalCalls: number;
    errorCount: number;
    topTools: Array<{ name: string; count: number; errorCount: number }>;
  };
  approvals: {
    total: number;
    approveOnly: number;
    approveExecute: number;
    reject: number;
  };
  changesets: {
    total: number;
    diffViews: number;
    fileDiffViews: number;
  };
  recentErrors: Array<{
    id: string;
    sessionId: string;
    runId: string;
    time: string;
    name: string;
    summary: string;
  }>;
  activeSessions: Array<{
    sessionId: string;
    eventCount: number;
    runCount: number;
    lastEventAt: string;
  }>;
}

export function getRuntime() {
  return getJson<ConsoleRuntimeResponse>('/api/console/runtime');
}

export function getSessions() {
  return getJson<ConsoleSessionsResponse>('/api/console/sessions');
}

export function getSessionDetail(sessionId: string) {
  return getJson<ConsoleSessionDetail>(`/api/console/sessions/${encodeURIComponent(sessionId)}`);
}

export function getSessionTimeline(sessionId: string, filters: { category?: string; runId?: string } = {}) {
  const query = queryString(filters);
  return getJson<ConsoleTimelineEvent[]>(`/api/console/sessions/${encodeURIComponent(sessionId)}/timeline${query}`);
}

export function getSessionEvents(sessionId: string, after?: string, filters: { category?: string; runId?: string } = {}) {
  const query = queryString({ after, ...filters });
  return getJson<ConsoleSessionEventsResponse>(`/api/console/sessions/${encodeURIComponent(sessionId)}/events${query}`);
}

export function getSessionEventStreamUrl(sessionId: string, after?: string) {
  const query = after ? `?after=${encodeURIComponent(after)}` : '';
  return `/api/console/sessions/${encodeURIComponent(sessionId)}/events/stream${query}`;
}

export function getRunHistory(sessionId: string, filters: { status?: string; keyword?: string; limit?: number } = {}) {
  const query = queryString(filters);
  return getJson<ConsoleRunHistoryResponse>(`/api/console/sessions/${encodeURIComponent(sessionId)}/runs/history${query}`);
}

export function searchEvents(query: ConsoleEventSearchQuery = {}) {
  return getJson<ConsoleEventSearchResponse>(`/api/console/events/search${queryString(query)}`);
}

export function getMetricsSummary(query: { sessionId?: string; since?: string; until?: string } = {}) {
  return getJson<ConsoleMetricsSummary>(`/api/console/metrics/summary${queryString(query)}`);
}

export function startSessionRun(sessionId: string, input: string, options: { workspace?: string; model?: string; mode?: string } = {}) {
  return postJson<ConsoleStartRunResponse>(`/api/console/sessions/${encodeURIComponent(sessionId)}/runs`, {
    input,
    workspace: options.workspace,
    model: options.model,
    mode: options.mode ?? 'interactive',
  });
}

export function getRunStatus(runId: string) {
  return getJson<ConsoleRunStatusResponse>(`/api/console/runs/${encodeURIComponent(runId)}`);
}

export function cancelRun(runId: string) {
  return postJson<ConsoleCancelRunResponse>(`/api/console/runs/${encodeURIComponent(runId)}/cancel`, {});
}

export function approveOnlyApproval(approvalId: string) {
  return postJson<ConsoleApprovalActionResponse>(`/api/console/approvals/${encodeURIComponent(approvalId)}/approve-only`, {});
}

export function approveExecuteApproval(approvalId: string) {
  return postJson<ConsoleApprovalActionResponse>(`/api/console/approvals/${encodeURIComponent(approvalId)}/approve-execute`, {});
}

export function rejectApproval(approvalId: string) {
  return postJson<ConsoleApprovalActionResponse>(`/api/console/approvals/${encodeURIComponent(approvalId)}/reject`, {});
}

export function getPendingApprovals() {
  return getJson<ConsoleApprovalsResponse>('/api/console/approvals/pending');
}

export function getRecentChangeSets() {
  return getJson<ConsoleChangeSetsResponse>('/api/console/changesets/recent');
}

export function getChangeSetFileDiff(changeSetId: string, path: string) {
  return getJson<ConsoleChangeSetFileDiffResponse>(
    `/api/console/changesets/${encodeURIComponent(changeSetId)}/files/${encodeURIComponent(path)}/diff`,
  );
}

export function getWorkspaceTree(options: { root?: string; depth?: number; includeHidden?: boolean } = {}) {
  return getJson<ConsoleWorkspaceTreeResponse>(`/api/console/workspace/tree${queryString(options)}`);
}

export function getWorkspaceFileContent(path: string) {
  return getJson<ConsoleWorkspaceFileContentResponse>(
    `/api/console/workspace/files/content${queryString({ path })}`,
  );
}

export function searchWorkspaceFiles(options: { keyword?: string; limit?: number; includeHidden?: boolean } = {}) {
  return getJson<ConsoleWorkspaceSearchResponse>(`/api/console/workspace/search${queryString(options)}`);
}

function queryString(values: object) {
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(values)) {
    if ((typeof value === 'string' || typeof value === 'number') && String(value).trim()) {
      params.set(key, String(value));
    } else if (typeof value === 'boolean') {
      params.set(key, String(value));
    }
  }
  const text = params.toString();
  return text ? `?${text}` : '';
}
