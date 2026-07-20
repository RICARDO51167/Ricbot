package ricbot.integration.api.console;

final class ConsolePage {
    private ConsolePage() {
    }

    static String html() {
        return """
                <!doctype html>
                <html lang="zh-CN">
                <head>
                  <meta charset="utf-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1">
                  <title>Ricbot 控制台</title>
                  <style>
                    :root {
                      color-scheme: light;
                      --bg: #f3f5f7;
                      --panel: #ffffff;
                      --ink: #17202a;
                      --muted: #667789;
                      --line: #dde5ec;
                      --soft-line: #edf1f5;
                      --accent: #0f6f66;
                      --accent-soft: #e8f5f3;
                      --warn: #9a6700;
                      --err: #b42318;
                      --ok: #16784c;
                      --shadow: 0 10px 28px rgba(24, 39, 57, 0.07);
                    }
                    * { box-sizing: border-box; }
                    body {
                      margin: 0;
                      background: var(--bg);
                      color: var(--ink);
                      font-family: ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
                      letter-spacing: 0;
                    }
                    header {
                      position: sticky;
                      top: 0;
                      z-index: 2;
                      background: rgba(255, 255, 255, 0.9);
                      border-bottom: 1px solid var(--line);
                      backdrop-filter: blur(14px);
                    }
                    .bar {
                      max-width: 1200px;
                      margin: 0 auto;
                      padding: 14px 20px;
                      display: flex;
                      align-items: center;
                      justify-content: space-between;
                      gap: 14px;
                    }
                    h1 {
                      margin: 0;
                      font-size: 20px;
                      line-height: 1.2;
                      font-weight: 700;
                    }
                    .meta {
                      color: var(--muted);
                      font-size: 13px;
                      display: flex;
                      align-items: center;
                      gap: 12px;
                      flex-wrap: wrap;
                      justify-content: flex-end;
                    }
                    main {
                      max-width: 1200px;
                      margin: 0 auto;
                      padding: 20px;
                      display: grid;
                      grid-template-columns: repeat(12, 1fr);
                      gap: 16px;
                    }
                    section {
                      background: var(--panel);
                      border: 1px solid var(--line);
                      border-radius: 10px;
                      padding: 16px;
                      min-width: 0;
                      box-shadow: var(--shadow);
                    }
                    .span-4 { grid-column: span 4; }
                    .span-6 { grid-column: span 6; }
                    .span-8 { grid-column: span 8; }
                    .span-12 { grid-column: span 12; }
                    h2 {
                      margin: 0 0 12px;
                      font-size: 14px;
                      line-height: 1.2;
                      text-transform: uppercase;
                      color: var(--muted);
                    }
                    .value {
                      font-size: 26px;
                      line-height: 1.15;
                      font-weight: 700;
                    }
                    .badge {
                      display: inline-flex;
                      align-items: center;
                      min-height: 22px;
                      padding: 2px 8px;
                      border-radius: 999px;
                      border: 1px solid var(--soft-line);
                      color: #506273;
                      background: #f7f9fb;
                      font-size: 12px;
                      font-weight: 650;
                    }
                    .sub {
                      color: var(--muted);
                      font-size: 13px;
                      margin-top: 6px;
                      overflow-wrap: anywhere;
                    }
                    .status {
                      display: inline-flex;
                      align-items: center;
                      min-height: 24px;
                      padding: 2px 9px;
                      border-radius: 999px;
                      border: 1px solid var(--line);
                      font-size: 12px;
                      font-weight: 700;
                    }
                    .ok { color: var(--ok); border-color: #8fd6b1; background: #edfdf4; }
                    .warning { color: var(--warn); border-color: #e7c66a; background: #fff8db; }
                    .error { color: var(--err); border-color: #f0a19b; background: #fff0ef; }
                    .list {
                      display: grid;
                      gap: 8px;
                    }
                    .item {
                      border-top: 1px solid var(--line);
                      padding-top: 8px;
                    }
                    .item:first-child {
                      border-top: 0;
                      padding-top: 0;
                    }
                    .title {
                      font-weight: 650;
                      overflow-wrap: anywhere;
                    }
                    .kv {
                      margin-top: 5px;
                      color: var(--muted);
                      font-size: 12px;
                      display: flex;
                      gap: 10px;
                      flex-wrap: wrap;
                    }
                    .empty, .errbox {
                      min-height: 58px;
                      display: grid;
                      place-items: center;
                      color: var(--muted);
                      border: 1px dashed var(--line);
                      border-radius: 8px;
                      padding: 12px;
                      text-align: center;
                    }
                    .errbox {
                      color: var(--err);
                      border-color: #f0a19b;
                      background: #fff0ef;
                    }
                    button {
                      min-height: 34px;
                      border: 1px solid var(--line);
                      background: var(--panel);
                      color: var(--ink);
                      border-radius: 8px;
                      padding: 0 12px;
                      cursor: pointer;
                      font-weight: 650;
                      transition: border-color 0.16s ease, color 0.16s ease, background 0.16s ease, box-shadow 0.16s ease;
                    }
                    button:hover {
                      border-color: var(--accent);
                      color: var(--accent);
                      background: var(--accent-soft);
                      box-shadow: 0 2px 8px rgba(15, 111, 102, 0.12);
                    }
                    button:disabled {
                      opacity: 0.62;
                      cursor: wait;
                      background: #f2f5f7;
                      box-shadow: none;
                    }
                    .lang-switch {
                      display: inline-flex;
                      align-items: center;
                      gap: 2px;
                      padding: 2px;
                      border: 1px solid var(--line);
                      border-radius: 9px;
                      background: #f7f9fb;
                    }
                    .lang-switch button {
                      min-height: 28px;
                      border: 0;
                      border-radius: 7px;
                      background: transparent;
                      padding: 0 9px;
                      color: var(--muted);
                    }
                    .lang-switch button.active {
                      background: var(--panel);
                      color: var(--accent);
                      box-shadow: 0 1px 4px rgba(24, 39, 57, 0.12);
                    }
                    .section-head {
                      display: flex;
                      align-items: center;
                      justify-content: space-between;
                      gap: 10px;
                      margin-bottom: 12px;
                    }
                    .section-head h2 { margin: 0; }
                    .tools {
                      display: flex;
                      align-items: center;
                      gap: 8px;
                      flex-wrap: wrap;
                      justify-content: flex-end;
                    }
                    .mini {
                      min-height: 28px;
                      padding: 0 9px;
                      font-size: 12px;
                    }
                    .demo-flow {
                      display: grid;
                      grid-template-columns: repeat(5, minmax(0, 1fr));
                      gap: 8px;
                    }
                    .step {
                      border: 1px solid var(--line);
                      border-radius: 8px;
                      padding: 10px;
                      min-height: 126px;
                      background: #fbfcfd;
                    }
                    .cmd {
                      margin-top: 8px;
                      color: var(--muted);
                      font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
                      font-size: 11px;
                      line-height: 1.35;
                      overflow-wrap: anywhere;
                    }
                    .pre {
                      margin-top: 10px;
                      max-height: 280px;
                      overflow: auto;
                      white-space: pre-wrap;
                      overflow-wrap: anywhere;
                      border: 1px solid var(--line);
                      border-radius: 8px;
                      padding: 10px;
                      background: #f8fafb;
                      color: var(--ink);
                      font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
                      font-size: 12px;
                      line-height: 1.45;
                    }
                    .row {
                      display: flex;
                      align-items: center;
                      justify-content: space-between;
                      gap: 10px;
                    }
                    @media (max-width: 860px) {
                      .span-4, .span-6, .span-8 { grid-column: span 12; }
                      .demo-flow { grid-template-columns: 1fr; }
                      .row { align-items: flex-start; flex-direction: column; }
                      .bar { align-items: flex-start; flex-direction: column; }
                      .meta { justify-content: flex-start; }
                      main { padding: 14px; }
                    }
                  </style>
                </head>
                <body>
                  <header>
                    <div class="bar">
                      <h1 data-i18n="appTitle">Ricbot 控制台</h1>
                      <div class="meta">
                        <span id="workspace">workspace</span>
                        <span id="model">model</span>
                        <div class="lang-switch" aria-label="Language">
                          <button type="button" data-lang="zh">中文</button>
                          <button type="button" data-lang="en">English</button>
                        </div>
                        <button id="refresh" type="button" data-i18n="refresh">刷新</button>
                      </div>
                    </div>
                  </header>
                  <main>
                    <section class="span-12" id="demo-flow-card"></section>
                    <section class="span-4" id="health-card"></section>
                    <section class="span-4" id="config-card"></section>
                    <section class="span-4" id="experience-card"></section>
                    <section class="span-8" id="trace-card"></section>
                    <section class="span-4" id="workspace-card"></section>
                    <section class="span-4" id="tools-card"></section>
                    <section class="span-4" id="mcp-card"></section>
                    <section class="span-6" id="team-card"></section>
                    <section class="span-6" id="experience-list-card"></section>
                    <section class="span-12" id="approval-card"></section>
                    <section class="span-12" id="action-card"></section>
                    <section class="span-12" id="release-check-card"></section>
                    <section class="span-12" id="eval-card"></section>
                  </main>
                  <script>
                    const endpoints = {
                      health: "/console/api/health",
                      config: "/console/api/config-doctor",
                      traces: "/console/api/traces",
                      teams: "/console/api/team-reports",
                      tools: "/console/api/tools",
                      mcp: "/console/api/mcp",
                      workspaces: "/console/api/workspaces",
                      experiences: "/console/api/experiences",
                      approvals: "/console/api/approvals",
                      actions: "/console/api/actions",
                      releaseCheck: "/console/api/release-check",
                      evals: "/console/api/evals"
                    };

                    const LANG_KEY = "ricbot_console_lang";
                    const I18N = {
                      zh: {
                        appTitle: "Ricbot 控制台",
                        refresh: "刷新",
                        refreshing: "刷新中...",
                        processing: "处理中...",
                        loading: "加载中",
                        loadingDemoFlow: "正在加载演示流程",
                        loadingDetail: "正在加载详情",
                        updated: "更新于",
                        demoFlow: "演示流程",
                        health: "健康状态",
                        configDoctor: "配置诊断",
                        experience: "经验治理",
                        latestTrace: "最新轨迹",
                        workspaces: "工作区",
                        tools: "工具",
                        mcp: "MCP 服务",
                        teamReports: "任务报告",
                        experienceItems: "经验记录",
                        approvals: "审批",
                        consoleActions: "控制台操作",
                        releaseCheck: "发布检查",
                        evalRuns: "评测记录",
                        createChangeSet: "创建变更集",
                        discard: "丢弃",
                        verify: "确认",
                        reject: "拒绝",
                        approve: "批准",
                        promoteSkill: "晋升技能",
                        runSmokeEval: "运行冒烟评测",
                        detail: "详情",
                        bind: "监听",
                        readonly: "只读",
                        apiKeyPresent: "API key 存在",
                        warnings: "警告",
                        errors: "错误",
                        total: "总数",
                        builtin: "内置",
                        generated: "生成",
                        noParameters: "无参数",
                        allTools: "全部工具",
                        loadedMcpTools: "个已加载 MCP 工具",
                        candidatesVerifiedLoaded: "候选 / 已确认已加载",
                        storeTotals: "存储总计",
                        baseline: "baseline",
                        compare: "compare",
                        completed: "完成",
                        runningSmokeEval: "正在运行冒烟评测",
                        noTrace: "暂无执行轨迹。运行一次 team 任务后可在这里查看时间线。",
                        noWorkspaces: "暂无工作区。运行 /team run --worktree 后会创建受管工作区。",
                        noEvalRuns: "暂无评测记录。可以运行固定 smoke eval 后查看结果。",
                        noExperience: "暂无经验记录。",
                        noTools: "暂无工具信息。",
                        noMcp: "暂无 MCP 服务配置。",
                        noTeamReports: "暂无任务报告。运行 /team run 后可在这里查看演示报告。",
                        noApprovals: "暂无待审批项。工具或变更审批会在这里等待人工确认。",
                        noActions: "暂无控制台操作。经验、审批、工作区和冒烟评测操作会在这里审计。",
                        noReleaseCheck: "暂无 release-check 报告。运行",
                        noFailingCases: "暂无失败用例",
                        manifest: "manifest",
                        workspaceActionError: "工作区操作",
                        experienceActionError: "经验操作",
                        approvalActionError: "审批操作",
                        evalDetailError: "评测详情",
                        runSmokeConfirm: "运行固定 golden smoke eval？",
                        discardConfirm: "丢弃受管工作区 {id}？",
                        changeCreateConfirm: "从工作区 {id} 创建变更集？",
                        experienceConfirm: "{label}经验 {id}？",
                        approvalConfirm: "{label}审批 {id}？",
                        flowConfigDoctor: "配置诊断",
                        flowTeamRun: "隔离执行任务",
                        flowTeamReport: "任务报告",
                        flowWorkspaceDiff: "工作区变更",
                        flowChangeCreate: "创建变更集",
                        flowTraceShow: "执行轨迹",
                        flowExperienceVerify: "经验确认",
                        flowPromoteSkill: "晋升技能",
                        flowFixedSmokeEval: "固定冒烟评测",
                        flowReleaseCheck: "发布检查",
                        status_OK: "正常",
                        status_ok: "正常",
                        status_WARNING: "警告",
                        status_ERROR: "错误",
                        status_PASS: "通过",
                        status_pass: "通过",
                        status_FAIL: "失败",
                        status_fail: "失败",
                        status_FAILED: "失败",
                        status_SKIPPED: "跳过",
                        status_skipped: "跳过",
                        status_UNKNOWN: "未知",
                        status_unknown: "未知",
                        status_empty: "空",
                        status_found: "已找到"
                      },
                      en: {
                        appTitle: "Ricbot Console",
                        refresh: "Refresh",
                        refreshing: "Refreshing...",
                        processing: "Processing...",
                        loading: "Loading",
                        loadingDemoFlow: "Loading demo flow",
                        loadingDetail: "Loading detail",
                        updated: "updated",
                        demoFlow: "Demo Flow",
                        health: "Health",
                        configDoctor: "Config Doctor",
                        experience: "Experience",
                        latestTrace: "Latest Trace",
                        workspaces: "Workspaces",
                        tools: "Tools",
                        mcp: "MCP",
                        teamReports: "Team Reports",
                        experienceItems: "Experience Items",
                        approvals: "Approvals",
                        consoleActions: "Console Actions",
                        releaseCheck: "Release Check",
                        evalRuns: "Eval Runs",
                        createChangeSet: "Create ChangeSet",
                        discard: "Discard",
                        verify: "Verify",
                        reject: "Reject",
                        approve: "Approve",
                        promoteSkill: "Promote Skill",
                        runSmokeEval: "Run Smoke Eval",
                        detail: "Detail",
                        bind: "bind",
                        readonly: "readonly",
                        apiKeyPresent: "api key present",
                        warnings: "warnings",
                        errors: "errors",
                        total: "total",
                        builtin: "builtin",
                        generated: "generated",
                        noParameters: "no parameters",
                        allTools: "all tools",
                        loadedMcpTools: "loaded MCP tools",
                        candidatesVerifiedLoaded: "candidates / verified loaded",
                        storeTotals: "store totals",
                        baseline: "baseline",
                        compare: "compare",
                        completed: "completed",
                        runningSmokeEval: "Running smoke eval",
                        noTrace: "No trace found. Run a team task, then come back here for the timeline.",
                        noWorkspaces: "No workspaces yet. Run /team run with --worktree to create a managed workspace.",
                        noEvalRuns: "No eval runs yet. Use Run Smoke Eval or sh scripts/release-check.sh to create deterministic artifacts.",
                        noExperience: "No experience items yet. Verified lessons and generated skills will appear here.",
                        noTools: "No tools loaded",
                        noMcp: "No MCP servers configured",
                        noTeamReports: "No team reports yet. After /team run, reports appear here for demo review.",
                        noApprovals: "No pending approvals. Tool or change approvals will wait here for human review.",
                        noActions: "No console actions yet. Experience, approval, workspace, and smoke eval actions are audited here.",
                        noReleaseCheck: "No release-check report yet. Run",
                        noFailingCases: "No failing cases",
                        manifest: "manifest",
                        workspaceActionError: "workspace action",
                        experienceActionError: "experience action",
                        approvalActionError: "approval action",
                        evalDetailError: "eval detail",
                        runSmokeConfirm: "Run fixed golden smoke eval?",
                        discardConfirm: "Discard managed workspace {id}?",
                        changeCreateConfirm: "Create ChangeSet from workspace {id}?",
                        experienceConfirm: "{label} experience {id}?",
                        approvalConfirm: "{label} approval {id}?",
                        flowConfigDoctor: "config doctor",
                        flowTeamRun: "team run --worktree --verify",
                        flowTeamReport: "team report",
                        flowWorkspaceDiff: "workspace diff",
                        flowChangeCreate: "change create",
                        flowTraceShow: "trace show",
                        flowExperienceVerify: "experience verify",
                        flowPromoteSkill: "promote skill",
                        flowFixedSmokeEval: "fixed smoke eval",
                        flowReleaseCheck: "release-check",
                        status_OK: "OK",
                        status_ok: "ok",
                        status_WARNING: "WARNING",
                        status_ERROR: "ERROR",
                        status_PASS: "PASS",
                        status_pass: "pass",
                        status_FAIL: "FAIL",
                        status_fail: "fail",
                        status_FAILED: "FAILED",
                        status_SKIPPED: "SKIPPED",
                        status_skipped: "skipped",
                        status_UNKNOWN: "UNKNOWN",
                        status_unknown: "unknown",
                        status_empty: "empty",
                        status_found: "found"
                      }
                    };

                    const appState = {};

                    const esc = (value) => String(value ?? "")
                      .replaceAll("&", "&amp;")
                      .replaceAll("<", "&lt;")
                      .replaceAll(">", "&gt;")
                      .replaceAll('"', "&quot;");

                    function getLang() {
                      try {
                        const stored = localStorage.getItem(LANG_KEY);
                        return stored === "en" ? "en" : "zh";
                      } catch (error) {
                        return "zh";
                      }
                    }

                    function setLang(lang) {
                      const next = lang === "en" ? "en" : "zh";
                      try {
                        localStorage.setItem(LANG_KEY, next);
                      } catch (error) {
                        // localStorage may be unavailable in hardened browser modes.
                      }
                      applyStaticI18n();
                      rerenderAll();
                    }

                    function t(key) {
                      const lang = getLang();
                      return I18N[lang]?.[key] ?? I18N.en[key] ?? key;
                    }

                    function applyStaticI18n() {
                      document.documentElement.lang = getLang() === "zh" ? "zh-CN" : "en";
                      document.title = t("appTitle");
                      document.querySelectorAll("[data-i18n]").forEach(node => {
                        node.textContent = t(node.getAttribute("data-i18n"));
                      });
                      document.querySelectorAll("[data-lang]").forEach(button => {
                        button.classList.toggle("active", button.getAttribute("data-lang") === getLang());
                      });
                    }

                    function formatStatus(status) {
                      const raw = String(status || "UNKNOWN");
                      return I18N[getLang()]?.[`status_${raw}`] ?? I18N.en[`status_${raw}`] ?? raw;
                    }

                    function formatCountLabel(type, count) {
                      const value = Number(count ?? 0);
                      if (getLang() === "zh") {
                        if (type === "total") return `总计 ${value}`;
                        const labels = {
                          events: "个事件",
                          reports: "份报告",
                          workspaces: "个工作区",
                          candidates: "个候选",
                          verified: "个已确认",
                          shown: "条显示",
                          pending: "个待处理",
                          recent: "条最近记录",
                          servers: "个服务",
                          steps: "步",
                          evalRuns: "次评测"
                        };
                        return `${value} ${labels[type] || type}`;
                      }
                      const labels = {
                        total: "total",
                        events: "events",
                        reports: "reports",
                        workspaces: "workspaces",
                        candidates: "candidates",
                        verified: "verified",
                        shown: "shown",
                        pending: "pending",
                        recent: "recent",
                        servers: "servers",
                        steps: "steps",
                        evalRuns: "eval runs"
                      };
                      return `${value} ${labels[type] || type}`;
                    }

                    async function getJson(name) {
                      const response = await fetch(endpoints[name], { headers: { "Accept": "application/json" } });
                      const json = await response.json();
                      if (!response.ok) {
                        throw new Error(json?.error?.message || response.statusText);
                      }
                      return json;
                    }

                    async function postJson(url, body) {
                      const options = { method: "POST", headers: { "Accept": "application/json" } };
                      if (body) {
                        options.headers["Content-Type"] = "application/json";
                        options.body = JSON.stringify(body);
                      }
                      const response = await fetch(url, options);
                      const json = await response.json();
                      if (!response.ok) {
                        throw new Error(json?.error?.message || response.statusText);
                      }
                      return json;
                    }

                    function pill(status) {
                      const raw = String(status || "UNKNOWN");
                      const upper = raw.toUpperCase();
                      const klass = upper === "OK" || upper === "COMPLETED" || upper === "HEALTHY" || upper === "PASS" || upper === "PASSED"
                        ? "ok"
                        : upper === "ERROR" || upper === "FAILED" || upper === "FAIL" || upper === "CRITICAL"
                          ? "error"
                          : "warning";
                      return `<span class="status ${klass}" title="${esc(raw)}">${esc(formatStatus(raw))}</span>`;
                    }

                    function sectionHead(title, count, refreshName) {
                      const badge = count === undefined || count === null ? "" : `<span class="badge">${esc(count)}</span>`;
                      const refresh = refreshName ? `<button class="mini" type="button" data-refresh-name="${esc(refreshName)}">${esc(t("refresh"))}</button>` : "";
                      const updated = `<span class="sub">${esc(t("updated"))} ${esc(new Date().toLocaleTimeString())}</span>`;
                      return `<div class="section-head"><h2>${esc(title)}</h2><div class="tools">${badge}${updated}${refresh}</div></div>`;
                    }

                    function empty(text) {
                      return `<div class="empty">${esc(text)}</div>`;
                    }

                    function loading(text = t("loading")) {
                      return `<div class="empty">${esc(text)}</div>`;
                    }

                    function errorCard(title, error) {
                      return `${sectionHead(title, formatStatus("ERROR"), null)}<div class="errbox">${esc(error.message || error)}</div>`;
                    }

                    function setBusy(button, busyText = t("processing")) {
                      if (!button) return () => {};
                      const oldText = button.textContent;
                      button.disabled = true;
                      button.textContent = busyText;
                      return () => {
                        button.disabled = false;
                        button.textContent = oldText;
                      };
                    }

                    function localCount(data, path, fallback = 0) {
                      return path.reduce((value, key) => value && value[key], data) ?? fallback;
                    }

                    function renderHealth(data) {
                      document.getElementById("workspace").textContent = data.workspace || "workspace";
                      document.getElementById("model").textContent = data.model || "model";
                      document.getElementById("health-card").innerHTML = `
                        ${sectionHead(t("health"), formatStatus(data.status || "ok"), "health")}
                        <div class="value">${pill(data.status)}</div>
                        <div class="sub">${esc(t("bind"))} ${esc(data.bindHost || "")} · ${esc(t("readonly"))} ${esc(data.readonly)}</div>
                        ${data.warning ? `<div class="sub">${esc(data.warning)}</div>` : ""}
                      `;
                    }

                    function renderConfig(data) {
                      document.getElementById("config-card").innerHTML = `
                        ${sectionHead(t("configDoctor"), formatStatus(data.status || "UNKNOWN"), "config")}
                        <div class="value">${pill(data.status)}</div>
                        <div class="sub">${esc(data.inferredProvider)} · ${esc(data.model)}</div>
                        <div class="sub">${esc(t("apiKeyPresent"))}: ${esc(data.apiKeyPresent)}</div>
                        <div class="sub">${esc((data.warnings || []).length)} ${esc(t("warnings"))} · ${esc((data.errors || []).length)} ${esc(t("errors"))}</div>
                      `;
                    }

                    function renderTrace(data) {
                      const latest = data.latest || {};
                      const events = latest.events || [];
                      document.getElementById("trace-card").innerHTML = `
                        ${sectionHead(t("latestTrace"), formatCountLabel("events", events.length), "traces")}
                        ${events.length === 0 ? empty(t("noTrace")) : `
                          <div class="title">${esc(latest.summary || latest.traceId || "Trace")}</div>
                          <div class="kv"><span>${pill(latest.status)}</span><span>${esc(formatCountLabel("events", events.length))}</span><span>${esc(latest.startedAt || "")}</span></div>
                          <div class="list">${events.slice(-6).reverse().map(event => `
                            <div class="item">
                              <div class="title">${esc(event.title || event.type)}</div>
                              <div class="sub">${esc(event.detail)}</div>
                              <div class="kv"><span>${esc(event.source)}</span><span>${esc(event.severity)}</span><span>${esc(event.timestamp)}</span></div>
                            </div>
                          `).join("")}</div>
                        `}
                      `;
                    }

                    function renderWorkspaces(data) {
                      const items = data.items || [];
                      document.getElementById("workspace-card").innerHTML = `
                        ${sectionHead(t("workspaces"), formatCountLabel("total", items.length), "workspaces")}
                        ${items.length === 0 ? empty(t("noWorkspaces")) : `
                          <div class="value">${esc(items.length)}</div>
                          <div class="list">${items.slice(0, 6).map(item => `
                            <div class="item">
                              <div class="row">
                                <div>
                                  <div class="title">${esc(item.id)}</div>
                                  <div class="kv"><span>${esc(item.type)}</span><span>${esc(item.status)}</span></div>
                                  <div class="sub">${esc(item.goal || item.branchName || item.workspacePath)}</div>
                                </div>
                                ${workspaceActionButtons(item)}
                              </div>
                            </div>
                          `).join("")}</div>
                        `}
                      `;
                      document.querySelectorAll("[data-workspace-action]").forEach(button => {
                        button.addEventListener("click", () => runWorkspaceAction(
                          button,
                          button.getAttribute("data-workspace-id"),
                          button.getAttribute("data-workspace-action")
                        ));
                      });
                    }

                    function workspaceActionButtons(item) {
                      const metadata = item.metadata || {};
                      const activeManagedWorktree = item.type === "GIT_WORKTREE" && item.status === "ACTIVE" && String(metadata.managedBy || "").toLowerCase() === "ricbot";
                      if (!activeManagedWorktree) return "";
                      return `
                        <div>
                          <button type="button" data-workspace-action="change-create" data-workspace-id="${esc(item.id)}">${esc(t("createChangeSet"))}</button>
                          <button type="button" data-workspace-action="discard" data-workspace-id="${esc(item.id)}">${esc(t("discard"))}</button>
                        </div>
                      `;
                    }

                    async function runWorkspaceAction(button, id, action) {
                      if (action === "discard" && !confirm(t("discardConfirm").replace("{id}", id))) return;
                      if (action === "change-create" && !confirm(t("changeCreateConfirm").replace("{id}", id))) return;
                      const restore = setBusy(button, t("processing"));
                      try {
                        const body = action === "discard" ? { confirm: true } : null;
                        await postJson(`/console/api/workspaces/${encodeURIComponent(id)}/${action}`, body);
                        appState.workspaces = await getJson("workspaces");
                        renderWorkspaces(appState.workspaces);
                        appState.actions = await getJson("actions");
                        renderActions(appState.actions);
                      } catch (error) {
                        document.getElementById("workspace-card").innerHTML = errorCard(t("workspaceActionError"), error);
                      } finally {
                        restore();
                      }
                    }

                    function renderTeams(data) {
                      const items = data.items || [];
                      document.getElementById("team-card").innerHTML = `
                        ${sectionHead(t("teamReports"), formatCountLabel("total", items.length), "teams")}
                        ${items.length === 0 ? empty(t("noTeamReports")) : `
                          <div class="list">${items.slice(0, 8).map(item => {
                            const report = item.report || {};
                            const task = item.task || {};
                            return `
                              <div class="item">
                                <div class="title">${esc(report.title || task.goal || task.id)}</div>
                                <div class="kv"><span>${pill(report.status || task.state)}</span><span>${esc(report.health || "")}</span><span>${esc(task.role || "")}</span></div>
                                <div class="sub">${esc(report.latestEvent || item.sessionGoal || "")}</div>
                              </div>
                            `;
                          }).join("")}</div>
                        `}
                      `;
                    }

                    function renderTools(data) {
                      const items = data.items || [];
                      document.getElementById("tools-card").innerHTML = `
                        ${sectionHead(t("tools"), formatCountLabel("total", data.total || 0), "tools")}
                        <div class="value">${esc(data.total || 0)}</div>
                        <div class="sub">${esc(data.builtinCount || 0)} ${esc(t("builtin"))} · ${esc(data.mcpCount || 0)} MCP · ${esc(data.generatedCount || 0)} ${esc(t("generated"))}</div>
                        ${items.length === 0 ? empty(t("noTools")) : `
                          <div class="list">${items.slice(0, 10).map(item => {
                            const params = item.parameters || {};
                            const names = (params.items || []).map(p => `${p.name}${p.required ? "*" : ""}`).join(", ");
                            return `
                              <div class="item">
                                <div class="title">${esc(item.name)}</div>
                                <div class="kv"><span>${esc(item.source)}</span><span>${esc(item.risk)}</span><span>${esc(item.enabled)}</span></div>
                                <div class="sub">${esc(item.description)}</div>
                                <div class="sub">${esc(names || t("noParameters"))}</div>
                              </div>
                            `;
                          }).join("")}</div>
                        `}
                      `;
                    }

                    function renderMcp(data) {
                      const servers = data.servers || [];
                      document.getElementById("mcp-card").innerHTML = `
                        ${sectionHead(t("mcp"), formatCountLabel("servers", servers.length), "mcp")}
                        <div class="value">${esc(data.configuredCount || 0)} / ${esc(data.connectedCount || 0)}</div>
                        <div class="sub">${esc(data.mcpToolCount || 0)} ${esc(t("loadedMcpTools"))}</div>
                        ${servers.length === 0 ? empty(t("noMcp")) : `
                          <div class="list">${servers.slice(0, 8).map(server => `
                            <div class="item">
                              <div class="title">${esc(server.name)}</div>
                              <div class="kv"><span>${pill(server.status)}</span><span>${esc(server.transportType)}</span><span>${esc(server.loadedToolCount || 0)} ${esc(t("tools"))}</span></div>
                              <div class="sub">${esc((server.enabledTools || []).join(", ") || t("allTools"))}</div>
                              ${server.lastError ? `<div class="sub">${esc(server.lastError)}</div>` : ""}
                            </div>
                          `).join("")}</div>
                        `}
                      `;
                    }

                    function renderExperiences(data) {
                      const stats = data.stats || {};
                      const candidates = data.candidates || [];
                      const verified = data.verified || [];
                      document.getElementById("experience-card").innerHTML = `
                        ${sectionHead(t("experience"), formatCountLabel("candidates", stats.candidates || 0), "experiences")}
                        <div class="value">${esc(candidates.length)} / ${esc(verified.length)}</div>
                        <div class="sub">${esc(t("candidatesVerifiedLoaded"))}</div>
                        <div class="sub">${esc(t("storeTotals"))}: ${esc(formatCountLabel("candidates", stats.candidates || 0))} · ${esc(formatCountLabel("verified", stats.verified || 0))}</div>
                      `;
                      const rows = [...candidates.map(x => ({...x, bucket: "candidate"})), ...verified.map(x => ({...x, bucket: "verified"}))];
                      document.getElementById("experience-list-card").innerHTML = `
                        ${sectionHead(t("experienceItems"), formatCountLabel("shown", rows.length), "experiences")}
                        ${rows.length === 0 ? empty(t("noExperience")) : `
                          <div class="list">${rows.slice(0, 10).map(item => `
                            <div class="item">
                              <div class="row">
                                <div>
                                  <div class="title">${esc(item.title || item.id)}</div>
                                  <div class="kv"><span>${esc(item.bucket)}</span><span>${esc(item.type)}</span><span>${esc(item.confidence)}</span></div>
                                </div>
                                <div>
                                  ${item.status === "CANDIDATE" || item.bucket === "candidate" ? `<button type="button" data-exp-action="verify" data-exp-id="${esc(item.id)}">${esc(t("verify"))}</button> <button type="button" data-exp-action="reject" data-exp-id="${esc(item.id)}">${esc(t("reject"))}</button>` : ""}
                                  ${item.status === "VERIFIED" || item.bucket === "verified" ? `<button type="button" data-exp-action="promote-skill" data-exp-id="${esc(item.id)}">${esc(t("promoteSkill"))}</button>` : ""}
                                </div>
                              </div>
                              <div class="sub">${esc(item.whenToApply || item.content)}</div>
                            </div>
                          `).join("")}</div>
                        `}
                      `;
                      document.querySelectorAll("[data-exp-action]").forEach(button => {
                        button.addEventListener("click", () => runExperienceAction(
                          button,
                          button.getAttribute("data-exp-id"),
                          button.getAttribute("data-exp-action")
                        ));
                      });
                    }

                    async function runExperienceAction(button, id, action) {
                      const label = action === "promote-skill" ? t("promoteSkill") : action === "verify" ? t("verify") : t("reject");
                      if (!confirm(t("experienceConfirm").replace("{label}", label).replace("{id}", id))) return;
                      const restore = setBusy(button, t("processing"));
                      try {
                        await postJson(`/console/api/experiences/${encodeURIComponent(id)}/${action}`);
                        appState.experiences = await getJson("experiences");
                        renderExperiences(appState.experiences);
                        appState.actions = await getJson("actions");
                        renderActions(appState.actions);
                      } catch (error) {
                        document.getElementById("experience-list-card").innerHTML = errorCard(t("experienceActionError"), error);
                      } finally {
                        restore();
                      }
                    }

                    function renderApprovals(data) {
                      const items = data.items || [];
                      document.getElementById("approval-card").innerHTML = `
                        ${sectionHead(t("approvals"), formatCountLabel("pending", items.length), "approvals")}
                        ${items.length === 0 ? empty(t("noApprovals")) : `
                          <div class="list">${items.slice(0, 12).map(item => {
                            const risk = item.riskAssessment || {};
                            const tool = item.pendingToolCall || {};
                            const change = item.pendingChangeAction || {};
                            return `
                              <div class="item">
                                <div class="row">
                                  <div>
                                    <div class="title">${esc(item.requestId)}</div>
                                    <div class="kv"><span>${pill(item.status)}</span><span>${esc(risk.riskLevel || "")}</span><span>${esc(risk.toolName || tool.toolName || change.actionType || "")}</span></div>
                                    <div class="sub">${esc((risk.reasons || []).join(" · ") || risk.command || change.changeSetId || "")}</div>
                                  </div>
                                  <div>
                                    <button type="button" data-approval-action="approve" data-approval-id="${esc(item.requestId)}">${esc(t("approve"))}</button>
                                    <button type="button" data-approval-action="reject" data-approval-id="${esc(item.requestId)}">${esc(t("reject"))}</button>
                                  </div>
                                </div>
                              </div>
                            `;
                          }).join("")}</div>
                        `}
                      `;
                      document.querySelectorAll("[data-approval-action]").forEach(button => {
                        button.addEventListener("click", () => runApprovalAction(
                          button,
                          button.getAttribute("data-approval-id"),
                          button.getAttribute("data-approval-action")
                        ));
                      });
                    }

                    async function runApprovalAction(button, id, action) {
                      const label = action === "approve" ? t("approve") : t("reject");
                      if (!confirm(t("approvalConfirm").replace("{label}", label).replace("{id}", id))) return;
                      const restore = setBusy(button, t("processing"));
                      try {
                        await postJson(`/console/api/approvals/${encodeURIComponent(id)}/${action}`);
                        appState.approvals = await getJson("approvals");
                        renderApprovals(appState.approvals);
                        appState.actions = await getJson("actions");
                        renderActions(appState.actions);
                      } catch (error) {
                        document.getElementById("approval-card").innerHTML = errorCard(t("approvalActionError"), error);
                      } finally {
                        restore();
                      }
                    }

                    function renderActions(data) {
                      const items = data.items || [];
                      document.getElementById("action-card").innerHTML = `
                        ${sectionHead(t("consoleActions"), formatCountLabel("recent", items.length), "actions")}
                        ${items.length === 0 ? empty(t("noActions")) : `
                          <div class="list">${items.slice(0, 10).map(item => `
                            <div class="item">
                              <div class="title">${esc(item.action)}</div>
                              <div class="kv"><span>${pill(item.result)}</span><span>${esc(item.targetType)}</span><span>${esc(item.targetId)}</span><span>${esc(item.timestamp)}</span></div>
                              <div class="sub">${esc(item.message)}</div>
                            </div>
                          `).join("")}</div>
                        `}
                      `;
                    }

                    function renderEvals(data) {
                      const items = data.items || [];
                      document.getElementById("eval-card").innerHTML = `
                        <div class="row">
                          <div>${sectionHead(t("evalRuns"), formatCountLabel("recent", items.length), "evals")}</div>
                          <button type="button" id="run-smoke-eval">${esc(t("runSmokeEval"))}</button>
                        </div>
                        <div id="eval-action-state"></div>
                        ${items.length === 0 ? empty(t("noEvalRuns")) : `
                          <div class="list">${items.slice(0, 12).map(run => {
                            const failures = run.failuresByKind || {};
                            const failureText = Object.entries(failures).map(([kind, count]) => `${kind}:${count}`).join(" · ");
                            return `
                              <div class="item" id="eval-${esc(run.runId)}">
                                <div class="row">
                                  <div>
                                    <div class="title">${esc(run.runId || "eval run")}</div>
                                    <div class="kv">
                                      <span>${esc(run.passed || 0)} ${esc(formatStatus("pass"))}</span>
                                      <span>${esc(run.failed || 0)} ${esc(formatStatus("fail"))}</span>
                                      <span>${esc(run.skipped || 0)} ${esc(formatStatus("skipped"))}</span>
                                      <span>${esc(run.providerMode || "provider")}</span>
                                      <span>${esc(run.model || "model")}</span>
                                    </div>
                                    <div class="sub">${esc(run.createdAt || run.startedAt || "")}${failureText ? " · " + esc(failureText) : ""}</div>
                                    ${(run.warnings || []).length ? `<div class="sub">${esc((run.warnings || []).join(" · "))}</div>` : ""}
                                  </div>
                                  <button type="button" data-run-id="${esc(run.runId)}">${esc(t("detail"))}</button>
                                </div>
                                <div class="eval-detail" id="eval-detail-${esc(run.runId)}"></div>
                              </div>
                            `;
                          }).join("")}</div>
                        `}
                      `;
                      document.querySelectorAll("[data-run-id]").forEach(button => {
                        button.addEventListener("click", () => loadEvalDetail(button.getAttribute("data-run-id")));
                      });
                      const runButton = document.getElementById("run-smoke-eval");
                      if (runButton) {
                        runButton.addEventListener("click", runSmokeEval);
                      }
                    }

                    async function runSmokeEval() {
                      if (!confirm(t("runSmokeConfirm"))) return;
                      const runButton = document.getElementById("run-smoke-eval");
                      const restore = setBusy(runButton, t("processing"));
                      const state = document.getElementById("eval-action-state");
                      if (state) state.innerHTML = empty(t("runningSmokeEval"));
                      try {
                        const result = await postJson("/console/api/evals/smoke");
                        if (state) state.innerHTML = `<div class="sub">${esc(t("completed"))}: ${esc(result?.data?.runId || result?.id || "")}</div>`;
                        appState.evals = await getJson("evals");
                        renderEvals(appState.evals);
                        appState.actions = await getJson("actions");
                        renderActions(appState.actions);
                      } catch (error) {
                        if (state) state.innerHTML = `<div class="errbox">${esc(error.message || error)}</div>`;
                      } finally {
                        restore();
                      }
                    }

                    function renderReleaseCheck(data) {
                      const exists = Boolean(data.exists);
                      document.getElementById("release-check-card").innerHTML = `
                        ${sectionHead(t("releaseCheck"), exists ? formatStatus(data.finalStatus || "found") : formatStatus("empty"), "releaseCheck")}
                        ${!exists ? empty(`${t("noReleaseCheck")} ${data.command || "sh scripts/release-check.sh"}`) : `
                          <div class="kv">
                            <span>${pill(data.finalStatus || "UNKNOWN")}</span>
                            <span>${esc(t("baseline"))} ${esc(data.baselineStatus || "unknown")}</span>
                            <span>${esc(t("compare"))} ${esc(data.evalCompareStatus || "unknown")}</span>
                            <span>${esc(data.generatedAt || "")}</span>
                          </div>
                          <div class="sub">${esc(data.reportPath)}</div>
                          <div class="pre">${esc(data.reportMarkdown || "")}</div>
                        `}
                      `;
                    }

                    function renderDemoFlow(state) {
                      const steps = [
                        [t("flowConfigDoctor"), "java -jar target/Ricbot-1.0-SNAPSHOT.jar config doctor -c config/ricbot.config.json", t("configDoctor"), formatStatus(state.config?.status || "unknown")],
                        [t("flowTeamRun"), "/team run <task> --worktree --verify", t("teamReports"), formatCountLabel("reports", localCount(state.teams, ["items", "length"]))],
                        [t("flowTeamReport"), "/team report <taskId>", t("teamReports"), formatCountLabel("reports", localCount(state.teams, ["items", "length"]))],
                        [t("flowWorkspaceDiff"), "/workspace diff <taskId>", t("workspaces"), formatCountLabel("workspaces", localCount(state.workspaces, ["items", "length"]))],
                        [t("flowChangeCreate"), "/change create <taskId>", t("workspaces"), formatCountLabel("workspaces", localCount(state.workspaces, ["items", "length"]))],
                        [t("flowTraceShow"), "/trace show <taskId>", t("latestTrace"), formatCountLabel("events", localCount(state.traces, ["latest", "events", "length"]))],
                        [t("flowExperienceVerify"), "/experience verify <id>", t("experienceItems"), formatCountLabel("candidates", localCount(state.experiences, ["candidates", "length"]))],
                        [t("flowPromoteSkill"), "/experience promote-skill <id>", t("experienceItems"), formatCountLabel("verified", localCount(state.experiences, ["verified", "length"]))],
                        [t("flowFixedSmokeEval"), "java -jar target/Ricbot-1.0-SNAPSHOT.jar eval smoke --scenarios evals/golden.jsonl", t("evalRuns"), formatCountLabel("evalRuns", localCount(state.evals, ["items", "length"]))],
                        [t("flowReleaseCheck"), "sh scripts/release-check.sh", t("releaseCheck"), formatStatus(state.releaseCheck?.finalStatus || (state.releaseCheck?.exists ? "found" : "empty"))]
                      ];
                      document.getElementById("demo-flow-card").innerHTML = `
                        ${sectionHead(t("demoFlow"), formatCountLabel("steps", 10), null)}
                        <div class="demo-flow">
                          ${steps.map((step, index) => `
                            <div class="step">
                              <div class="badge">${esc(index + 1)}</div>
                              <div class="title">${esc(step[0])}</div>
                              <div class="sub">${esc(step[2])} · ${esc(step[3])}</div>
                              <div class="cmd">${esc(step[1])}</div>
                            </div>
                          `).join("")}
                        </div>
                      `;
                    }

                    async function loadEvalDetail(runId) {
                      const target = document.getElementById(`eval-detail-${runId}`);
                      if (!target) return;
                      target.innerHTML = empty(t("loadingDetail"));
                      try {
                        const response = await fetch(`${endpoints.evals}/${encodeURIComponent(runId)}`, { headers: { "Accept": "application/json" } });
                        const json = await response.json();
                        if (!response.ok) {
                          throw new Error(json?.error?.message || response.statusText);
                        }
                        const cases = json.cases || [];
                        const failing = cases.filter(item => !["pass", "skipped", "xfail"].includes(String(item.status || "")));
                        const manifest = json.manifest || {};
                        target.innerHTML = `
                          <div class="list">
                            ${failing.length === 0 ? empty(t("noFailingCases")) : failing.slice(0, 12).map(item => `
                              <div class="item">
                                <div class="title">${esc(item.id)}</div>
                                <div class="kv"><span>${pill(item.status)}</span><span>${esc(item.failureKind || "unknown")}</span><span>${esc(item.durationMs)}ms</span><span>${esc((item.tools || []).join(", "))}</span></div>
                                <div class="sub">${esc(item.artifactPath)}</div>
                              </div>
                            `).join("")}
                          </div>
                          <div class="sub">${esc(t("manifest"))}: ${esc(manifest.provider_mode || "")} ${esc(manifest.model || "")}</div>
                          ${json.reportMarkdown ? `<div class="pre">${esc(json.reportMarkdown)}</div>` : ""}
                          ${(json.warnings || []).length ? `<div class="sub">${esc((json.warnings || []).join(" · "))}</div>` : ""}
                        `;
                      } catch (error) {
                        target.innerHTML = errorCard(t("evalDetailError"), error);
                      }
                    }

                    const cards = {
                      health: "health-card",
                      config: "config-card",
                      traces: "trace-card",
                      teams: "team-card",
                      tools: "tools-card",
                      mcp: "mcp-card",
                      workspaces: "workspace-card",
                      experiences: "experience-card",
                      approvals: "approval-card",
                      actions: "action-card",
                      releaseCheck: "release-check-card",
                      evals: "eval-card"
                    };

                    const renderers = {
                      health: renderHealth,
                      config: renderConfig,
                      traces: renderTrace,
                      teams: renderTeams,
                      tools: renderTools,
                      mcp: renderMcp,
                      workspaces: renderWorkspaces,
                      experiences: renderExperiences,
                      approvals: renderApprovals,
                      actions: renderActions,
                      releaseCheck: renderReleaseCheck,
                      evals: renderEvals
                    };

                    const jobs = [
                        ["health", renderHealth],
                        ["config", renderConfig],
                        ["traces", renderTrace],
                        ["workspaces", renderWorkspaces],
                        ["tools", renderTools],
                        ["mcp", renderMcp],
                        ["teams", renderTeams],
                        ["experiences", renderExperiences],
                        ["approvals", renderApprovals],
                        ["actions", renderActions],
                        ["releaseCheck", renderReleaseCheck],
                        ["evals", renderEvals]
                    ];

                    function rerenderAll() {
                      for (const [name, render] of jobs) {
                        if (appState[name]) {
                          render(appState[name]);
                        }
                      }
                      renderDemoFlow(appState);
                    }

                    async function load() {
                      Object.values(cards).forEach(id => document.getElementById(id).innerHTML = loading(t("loading")));
                      document.getElementById("demo-flow-card").innerHTML = loading(t("loadingDemoFlow"));
                      document.getElementById("experience-list-card").innerHTML = loading(t("loading"));
                      for (const [name, render] of jobs) {
                        try {
                          const data = await getJson(name);
                          appState[name] = data;
                          render(data);
                        } catch (error) {
                          document.getElementById(cards[name]).innerHTML = errorCard(t(name) || name, error);
                          if (name === "experiences") {
                            document.getElementById("experience-list-card").innerHTML = errorCard(t("experienceItems"), error);
                          }
                        }
                      }
                      renderDemoFlow(appState);
                    }

                    document.getElementById("refresh").addEventListener("click", async (event) => {
                      const restore = setBusy(event.currentTarget, t("refreshing"));
                      try {
                        await load();
                      } finally {
                        restore();
                        event.currentTarget.textContent = t("refresh");
                      }
                    });
                    document.querySelectorAll("[data-lang]").forEach(button => {
                      button.addEventListener("click", () => setLang(button.getAttribute("data-lang")));
                    });
                    document.addEventListener("click", async (event) => {
                      const button = event.target.closest("[data-refresh-name]");
                      if (!button) return;
                      const name = button.getAttribute("data-refresh-name");
                      if (!renderers[name]) return;
                      const restore = setBusy(button, t("refreshing"));
                      try {
                        appState[name] = await getJson(name);
                        renderers[name](appState[name]);
                        renderDemoFlow(appState);
                      } catch (error) {
                        const target = cards[name];
                        if (target) document.getElementById(target).innerHTML = errorCard(name, error);
                      } finally {
                        restore();
                      }
                    });
                    applyStaticI18n();
                    load();
                  </script>
                </body>
                </html>
                """;
    }
}
