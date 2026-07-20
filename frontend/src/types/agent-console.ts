export type RunStatus = 'RUNNING' | 'WAITING_APPROVAL' | 'PASS' | 'FAILED' | 'IDLE';
export type TimelineEventKind = 'message' | 'thought' | 'tool' | 'approval' | 'trace' | 'verification' | 'run';
export type MessageRole = 'user' | 'assistant' | 'system';
export type ToolStatus = 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'WAITING_APPROVAL';
export type ApprovalStatus = 'PENDING' | 'APPROVED' | 'REJECTED';
export type InspectorMode = 'trace' | 'tool' | 'approval' | 'message';
export type RiskLevel = 'SAFE' | 'LOW' | 'MEDIUM' | 'HIGH' | 'BLOCKED';
export type FileChangeType = 'added' | 'modified' | 'deleted';
export type DataSource = 'backend' | 'mock';
export type EventCategory = 'all' | 'run' | 'tool' | 'approval' | 'changeset' | 'error' | 'system';

export interface RuntimeInfo {
  appName: string;
  mode: DataSource;
  modelConfigured: boolean;
  provider: string | null;
  model: string | null;
  workspace: string;
  version: string;
  readonly: boolean;
}

export interface RiskAssessment {
  riskLevel: RiskLevel;
  reasons: string[];
  command: string;
  toolName: string;
  affectedPaths: string[];
  requiresApproval: boolean;
  blocked: boolean;
}

export interface ApprovalRequest {
  requestId: string;
  riskAssessment: RiskAssessment;
  createdAt: string;
  expiresAt: string;
  status: ApprovalStatus;
  pendingToolCall?: {
    requestId: string;
    toolName: string;
    arguments: Record<string, unknown>;
    sessionId: string;
    createdAt: string;
    riskAssessment: RiskAssessment;
    consumed: boolean;
  };
  pendingChangeAction?: {
    requestId: string;
    actionType: 'COMMIT' | 'ROLLBACK';
    changeSetId: string;
    commands: string[];
    commitMessage: string;
    riskAssessment: RiskAssessment;
    createdAt: string;
    consumed: boolean;
  };
  consumed: boolean;
}

export interface ToolCall {
  id: string;
  toolName: string;
  status: ToolStatus;
  durationMs: number;
  arguments: Record<string, unknown>;
  result: Record<string, unknown>;
  policy: {
    executionPolicy: string;
    permissionPolicy: string;
    approvalRequired: boolean;
  };
  refs: Record<string, string>;
}

interface TimelineEventBase {
  id: string;
  kind: TimelineEventKind;
  timestamp: string;
  title: string;
  detail: string;
  severity: 'INFO' | 'WARN' | 'ERROR';
  source: 'AGENT' | 'TRACE' | 'TOOL' | 'APPROVAL' | 'TEAM';
  refs: Record<string, string>;
  runId?: string;
  eventType?: string;
  name?: string;
  category?: Exclude<EventCategory, 'all'>;
  status?: string;
  actor?: string;
  eventSource?: string;
}

export interface MessageEvent extends TimelineEventBase {
  kind: 'message';
  role: MessageRole;
  content: string;
}

export interface ThoughtEvent extends TimelineEventBase {
  kind: 'thought';
  content: string;
}

export interface ToolCallEvent extends TimelineEventBase {
  kind: 'tool';
  toolCall: ToolCall;
}

export interface ApprovalEvent extends TimelineEventBase {
  kind: 'approval';
  approval: ApprovalRequest;
}

export interface TraceEvent extends TimelineEventBase {
  kind: 'trace' | 'verification';
  payload: Record<string, unknown>;
}

export interface RunEvent extends TimelineEventBase {
  kind: 'run';
  payload: Record<string, unknown>;
}

export type TimelineEvent = MessageEvent | ThoughtEvent | ToolCallEvent | ApprovalEvent | TraceEvent | RunEvent;

export interface ChangedFile {
  path: string;
  changeType: FileChangeType;
  additions: number;
  deletions: number;
  diff: string;
}

export interface ChangeSet {
  id: string;
  sessionId: string;
  teamSessionId: string;
  taskId: string;
  baseCommit: string;
  status: 'DRAFT' | 'VERIFIED' | 'APPROVED' | 'COMMITTED';
  diffSummary: string;
  changedFiles: ChangedFile[];
  suggestedTests: string[];
  executedTests: string[];
  verifierStatus: 'PASS' | 'FAIL' | 'PENDING';
  verifierReasons: string[];
  commitMessage: string;
  updatedAt: string;
}

export interface AgentSession {
  id: string;
  project: string;
  title: string;
  task: string;
  status: RunStatus;
  updatedAt: string;
  workspace: string;
  model: string;
  traceId: string;
  runId: string;
  tokenCount: number;
  toolCount: number;
  approvalPendingCount: number;
  changedFileCount: number;
  riskLevel: RiskLevel;
  summary: string;
  timeline: TimelineEvent[];
  changeSet: ChangeSet;
}
