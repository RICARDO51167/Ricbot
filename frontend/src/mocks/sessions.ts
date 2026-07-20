import type { AgentSession, ApprovalRequest, ChangedFile, RiskAssessment, ToolCall } from '@/types/agent-console';

const safeRisk: RiskAssessment = {
  riskLevel: 'SAFE',
  reasons: ['workspace-scoped read/write', 'policy allows file edit in managed worktree'],
  command: '',
  toolName: 'EditFileTool',
  affectedPaths: ['README.md'],
  requiresApproval: false,
  blocked: false,
};

const highExecRisk: RiskAssessment = {
  riskLevel: 'HIGH',
  reasons: [
    'command writes executable script content',
    'affected path participates in release checks',
    'manual approval required by ToolPermissionPolicy',
  ],
  command: 'sh scripts/release-check.sh --publish',
  toolName: 'ExecTool',
  affectedPaths: ['scripts/release-check.sh', 'target/release-check-report.md'],
  requiresApproval: true,
  blocked: false,
};

const mcpRisk: RiskAssessment = {
  riskLevel: 'LOW',
  reasons: ['read-only MCP diagnostics request', 'no workspace mutation'],
  command: '',
  toolName: 'mcp.github.searchIssues',
  affectedPaths: [],
  requiresApproval: false,
  blocked: false,
};

const teamReadTool: ToolCall = {
  id: 'tool-read-readme',
  toolName: 'ReadFileTool',
  status: 'SUCCEEDED',
  durationMs: 178,
  arguments: {
    path: 'README.md',
    maxBytes: 12000,
  },
  result: {
    ok: true,
    bytes: 8812,
    preview: '# Ricbot\\n\\nRicbot is a Java 17 Agent Runtime...',
  },
  policy: {
    executionPolicy: 'workspace-readonly',
    permissionPolicy: 'allow',
    approvalRequired: false,
  },
  refs: {
    traceId: 'trace-team-20260603-01',
    contextId: 'ctx-readme-7b2',
  },
};

const teamEditTool: ToolCall = {
  id: 'tool-edit-readme',
  toolName: 'EditFileTool',
  status: 'SUCCEEDED',
  durationMs: 412,
  arguments: {
    path: 'README.md',
    replace: 'Ricbot 是一个 Java 17 Agent Runtime',
    with: 'Ricbot 是一个 Java 17 Agent Runtime，用于可治理的工程 Agent 闭环',
  },
  result: {
    ok: true,
    changed: true,
    changedFiles: ['README.md'],
    patchLines: 8,
  },
  policy: {
    executionPolicy: 'managed-worktree',
    permissionPolicy: 'allow',
    approvalRequired: false,
  },
  refs: {
    traceId: 'trace-team-20260603-01',
    changeSetId: 'cs-team-readme-001',
    workspaceId: 'ws-team-readme-001',
  },
};

const approvalTool: ToolCall = {
  id: 'tool-exec-release',
  toolName: 'ExecTool',
  status: 'WAITING_APPROVAL',
  durationMs: 0,
  arguments: {
    command: 'sh scripts/release-check.sh --publish',
    cwd: '/Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot',
  },
  result: {
    pending: true,
    approvalRequestId: 'apr-release-guard-001',
  },
  policy: {
    executionPolicy: 'shell-command',
    permissionPolicy: 'require_approval',
    approvalRequired: true,
  },
  refs: {
    traceId: 'trace-approval-20260603-02',
    approvalId: 'apr-release-guard-001',
  },
};

const mcpTool: ToolCall = {
  id: 'tool-mcp-search',
  toolName: 'mcp.github.searchIssues',
  status: 'SUCCEEDED',
  durationMs: 638,
  arguments: {
    query: 'repo:ricbot/ricbot label:agent-console approval trace',
    limit: 5,
  },
  result: {
    ok: true,
    items: [
      { number: 42, title: 'Expose approval event stream', state: 'open' },
      { number: 57, title: 'Trace viewer should link ChangeSet', state: 'closed' },
    ],
    schemaHash: 'mcp-8f71c2',
  },
  policy: {
    executionPolicy: 'mcp-readonly',
    permissionPolicy: 'allow',
    approvalRequired: false,
  },
  refs: {
    traceId: 'trace-mcp-20260603-03',
    mcpServer: 'github',
  },
};

const approvalRequest: ApprovalRequest = {
  requestId: 'apr-release-guard-001',
  riskAssessment: highExecRisk,
  createdAt: '2026-06-03T13:28:16Z',
  expiresAt: '2026-06-03T13:58:16Z',
  status: 'PENDING',
  pendingToolCall: {
    requestId: 'apr-release-guard-001',
    toolName: 'ExecTool',
    arguments: approvalTool.arguments,
    sessionId: 'approval-required',
    createdAt: '2026-06-03T13:28:16Z',
    riskAssessment: highExecRisk,
    consumed: false,
  },
  consumed: false,
};

const readmeDiff: ChangedFile = {
  path: 'README.md',
  changeType: 'modified',
  additions: 3,
  deletions: 1,
  diff: `diff --git a/README.md b/README.md
index 1d2c9ad..8a5f410 100644
--- a/README.md
+++ b/README.md
@@ -1,6 +1,8 @@
 # Ricbot
 
-Ricbot 是一个 Java 17 Agent Runtime。
+Ricbot 是一个 Java 17 Agent Runtime，用于可治理的工程 Agent 闭环。
+
+它把工具调用、审批、Trace 和 ChangeSet 收口为可审阅执行记录。
 
 ## Quick Start
 `,
};

const releaseDiff: ChangedFile = {
  path: 'scripts/release-check.sh',
  changeType: 'modified',
  additions: 2,
  deletions: 0,
  diff: `diff --git a/scripts/release-check.sh b/scripts/release-check.sh
index 60e03fa..a0c5c91 100755
--- a/scripts/release-check.sh
+++ b/scripts/release-check.sh
@@ -7,6 +7,8 @@ set -euo pipefail
 REPORT="target/release-check-report.md"
 mkdir -p target
 
+confirm_required=true
+
 run_step() {
   local name="$1"
   shift
`,
};

const mcpDiff: ChangedFile = {
  path: 'docs/mcp/mcp-diagnostics.md',
  changeType: 'modified',
  additions: 4,
  deletions: 1,
  diff: `diff --git a/docs/mcp/mcp-diagnostics.md b/docs/mcp/mcp-diagnostics.md
index 33e197a..426ab18 100644
--- a/docs/mcp/mcp-diagnostics.md
+++ b/docs/mcp/mcp-diagnostics.md
@@ -12,7 +12,10 @@ MCP diagnostics are read-only.
 
-The console lists configured servers.
+The console lists configured servers, connected tools, schema hashes,
+and trace references for recent MCP tool calls.
+
+Agent Console links MCP output back to timeline events.
`,
};

export const mockSessions: AgentSession[] = [
  {
    id: 'team-worktree-run',
    project: 'Ricbot',
    title: 'Team Worktree Run',
    task: '给 README 增加一个说明性修正并验证',
    status: 'PASS',
    updatedAt: '2026-06-03T13:24:42Z',
    workspace: 'managed-worktree/ws-team-readme-001',
    model: 'gpt-5-codex',
    traceId: 'trace-team-20260603-01',
    runId: 'run-team-001',
    tokenCount: 18421,
    toolCount: 5,
    approvalPendingCount: 0,
    changedFileCount: 1,
    riskLevel: 'SAFE',
    summary: 'Team worker 修改 README，verifier 通过，ChangeSet 已生成待审阅。',
    timeline: [
      {
        id: 'evt-team-user',
        kind: 'message',
        role: 'user',
        timestamp: '2026-06-03T13:18:02Z',
        title: 'User task',
        detail: 'Team run requested',
        severity: 'INFO',
        source: 'AGENT',
        refs: { sessionId: 'team-worktree-run' },
        content: '/team run 给 README 增加一个很小的说明性修正 --worktree --verify',
      },
      {
        id: 'evt-plan',
        kind: 'thought',
        timestamp: '2026-06-03T13:18:34Z',
        title: 'Planner',
        detail: '拆分为 inspect、edit、verify 三步',
        severity: 'INFO',
        source: 'TEAM',
        refs: { role: 'planner' },
        content: '先读取 README 定位介绍段，再在受管 worktree 中做最小文字修正，最后运行 targeted verification。',
      },
      {
        id: 'evt-read-tool',
        kind: 'tool',
        timestamp: '2026-06-03T13:19:11Z',
        title: 'ToolCall: ReadFileTool',
        detail: '读取 README.md',
        severity: 'INFO',
        source: 'TOOL',
        refs: teamReadTool.refs,
        toolCall: teamReadTool,
      },
      {
        id: 'evt-edit-tool',
        kind: 'tool',
        timestamp: '2026-06-03T13:20:09Z',
        title: 'ToolCall: EditFileTool',
        detail: '更新 README.md 简介',
        severity: 'INFO',
        source: 'TOOL',
        refs: teamEditTool.refs,
        toolCall: teamEditTool,
      },
      {
        id: 'evt-verify',
        kind: 'verification',
        timestamp: '2026-06-03T13:23:54Z',
        title: 'Verifier PASS',
        detail: 'targeted smoke passed; changedFiles=README.md',
        severity: 'INFO',
        source: 'TEAM',
        refs: { reportId: 'team-report-001', changeSetId: 'cs-team-readme-001' },
        payload: {
          workerStatus: 'APPLIED',
          verifierStatus: 'PASS',
          reportHealth: 'HEALTHY',
          executedTests: ['sh scripts/smoke.sh'],
        },
      },
      {
        id: 'evt-team-summary',
        kind: 'message',
        role: 'assistant',
        timestamp: '2026-06-03T13:24:42Z',
        title: 'Agent result',
        detail: 'ChangeSet ready',
        severity: 'INFO',
        source: 'AGENT',
        refs: { changeSetId: 'cs-team-readme-001' },
        content: '已在受管 worktree 中完成 README 说明性修正，验证通过，并生成 ChangeSet 等待人工审阅。',
      },
    ],
    changeSet: {
      id: 'cs-team-readme-001',
      sessionId: 'team-worktree-run',
      teamSessionId: 'team-session-001',
      taskId: 'task-readme-copy',
      baseCommit: '7a31f0e',
      status: 'VERIFIED',
      diffSummary: 'README.md introduction updated to describe Ricbot as an auditable engineering loop.',
      changedFiles: [readmeDiff],
      suggestedTests: ['sh scripts/smoke.sh'],
      executedTests: ['sh scripts/smoke.sh'],
      verifierStatus: 'PASS',
      verifierReasons: ['Only README.md changed', 'Smoke script returned exit code 0'],
      commitMessage: 'docs: clarify Ricbot agent loop',
      updatedAt: '2026-06-03T13:24:42Z',
    },
  },
  {
    id: 'approval-required',
    project: 'Ricbot',
    title: 'Approval Required',
    task: '准备执行 release check 发布命令',
    status: 'WAITING_APPROVAL',
    updatedAt: '2026-06-03T13:29:02Z',
    workspace: '/Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot',
    model: 'gpt-5-codex',
    traceId: 'trace-approval-20260603-02',
    runId: 'run-approval-002',
    tokenCount: 9264,
    toolCount: 2,
    approvalPendingCount: 1,
    changedFileCount: 1,
    riskLevel: 'HIGH',
    summary: 'ExecTool 命令命中高风险策略，等待人工批准或拒绝。',
    timeline: [
      {
        id: 'evt-approval-message',
        kind: 'message',
        role: 'user',
        timestamp: '2026-06-03T13:26:44Z',
        title: 'User task',
        detail: 'Release check requested',
        severity: 'INFO',
        source: 'AGENT',
        refs: { sessionId: 'approval-required' },
        content: '运行 release check，如果通过就准备发布。',
      },
      {
        id: 'evt-approval-thinking',
        kind: 'thought',
        timestamp: '2026-06-03T13:27:31Z',
        title: 'Policy evaluation',
        detail: 'ToolPermissionPolicy requires approval',
        severity: 'WARN',
        source: 'APPROVAL',
        refs: { policy: 'ToolPermissionPolicy' },
        content: '命令可能写入 release artifact，并触发发布路径。需要把风险和影响路径交给人工审批。',
      },
      {
        id: 'evt-exec-tool',
        kind: 'tool',
        timestamp: '2026-06-03T13:28:16Z',
        title: 'ToolCall: ExecTool',
        detail: '等待审批后执行 sh scripts/release-check.sh --publish',
        severity: 'WARN',
        source: 'TOOL',
        refs: approvalTool.refs,
        toolCall: approvalTool,
      },
      {
        id: 'evt-risk-approval',
        kind: 'approval',
        timestamp: '2026-06-03T13:28:16Z',
        title: 'Approval required',
        detail: 'HIGH risk command requires manual decision',
        severity: 'WARN',
        source: 'APPROVAL',
        refs: { approvalId: 'apr-release-guard-001', traceId: 'trace-approval-20260603-02' },
        approval: approvalRequest,
      },
    ],
    changeSet: {
      id: 'cs-release-guard-002',
      sessionId: 'approval-required',
      teamSessionId: '',
      taskId: 'task-release-guard',
      baseCommit: '7a31f0e',
      status: 'DRAFT',
      diffSummary: 'Release script receives an explicit confirmation guard before publish command execution.',
      changedFiles: [releaseDiff],
      suggestedTests: ['sh ./mvnw -q test', 'sh scripts/release-check.sh'],
      executedTests: [],
      verifierStatus: 'PENDING',
      verifierReasons: ['Approval pending before command execution'],
      commitMessage: 'chore: add release confirmation guard',
      updatedAt: '2026-06-03T13:29:02Z',
    },
  },
  {
    id: 'mcp-tool-trace',
    project: 'Ricbot',
    title: 'MCP Tool Trace',
    task: '检查 MCP 相关 issue 并链接到 Trace',
    status: 'RUNNING',
    updatedAt: '2026-06-03T13:35:20Z',
    workspace: '/Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot',
    model: 'gpt-5-codex',
    traceId: 'trace-mcp-20260603-03',
    runId: 'run-mcp-003',
    tokenCount: 11208,
    toolCount: 3,
    approvalPendingCount: 0,
    changedFileCount: 1,
    riskLevel: 'LOW',
    summary: 'MCP read-only 工具调用完成，Trace Inspector 展示 schema、refs 和结果 JSON。',
    timeline: [
      {
        id: 'evt-mcp-user',
        kind: 'message',
        role: 'user',
        timestamp: '2026-06-03T13:32:12Z',
        title: 'User task',
        detail: 'MCP diagnostics requested',
        severity: 'INFO',
        source: 'AGENT',
        refs: { sessionId: 'mcp-tool-trace' },
        content: '帮我查一下 MCP diagnostics 的近期问题，然后把结果关联到 trace。',
      },
      {
        id: 'evt-mcp-tool',
        kind: 'tool',
        timestamp: '2026-06-03T13:34:06Z',
        title: 'ToolCall: mcp.github.searchIssues',
        detail: '读取 GitHub MCP issue 列表',
        severity: 'INFO',
        source: 'TOOL',
        refs: mcpTool.refs,
        toolCall: mcpTool,
      },
      {
        id: 'evt-mcp-trace',
        kind: 'trace',
        timestamp: '2026-06-03T13:35:20Z',
        title: 'Trace linked',
        detail: 'MCP output linked to TraceRecorder event',
        severity: 'INFO',
        source: 'TRACE',
        refs: { traceId: 'trace-mcp-20260603-03', mcpServer: 'github' },
        payload: {
          traceId: 'trace-mcp-20260603-03',
          eventType: 'TOOL_RESULT',
          source: 'MCP',
          riskAssessment: mcpRisk,
          resultRefs: ['issue#42', 'issue#57'],
        },
      },
    ],
    changeSet: {
      id: 'cs-mcp-docs-003',
      sessionId: 'mcp-tool-trace',
      teamSessionId: '',
      taskId: 'task-mcp-trace',
      baseCommit: '7a31f0e',
      status: 'DRAFT',
      diffSummary: 'MCP diagnostics documentation mentions trace-linked MCP tool calls.',
      changedFiles: [mcpDiff],
      suggestedTests: ['sh ./mvnw -q test'],
      executedTests: [],
      verifierStatus: 'PENDING',
      verifierReasons: ['Documentation draft only'],
      commitMessage: 'docs: describe MCP trace links',
      updatedAt: '2026-06-03T13:35:20Z',
    },
  },
];
