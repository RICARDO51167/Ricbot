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
                  <title>Ricbot Console</title>
                  <style>
                    :root {
                      color-scheme: light;
                      --bg: #f5f7f9;
                      --panel: #ffffff;
                      --ink: #17202a;
                      --muted: #5f6f7d;
                      --line: #d8e0e6;
                      --accent: #0f766e;
                      --warn: #9a6700;
                      --err: #b42318;
                      --ok: #16784c;
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
                      background: rgba(245, 247, 249, 0.96);
                      border-bottom: 1px solid var(--line);
                    }
                    .bar {
                      max-width: 1180px;
                      margin: 0 auto;
                      padding: 14px 18px;
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
                      gap: 12px;
                      flex-wrap: wrap;
                      justify-content: flex-end;
                    }
                    main {
                      max-width: 1180px;
                      margin: 0 auto;
                      padding: 18px;
                      display: grid;
                      grid-template-columns: repeat(12, 1fr);
                      gap: 14px;
                    }
                    section {
                      background: var(--panel);
                      border: 1px solid var(--line);
                      border-radius: 8px;
                      padding: 14px;
                      min-width: 0;
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
                      padding: 1px 7px;
                      border-radius: 999px;
                      border: 1px solid var(--line);
                      color: var(--muted);
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
                      padding: 2px 8px;
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
                    }
                    button:hover { border-color: var(--accent); color: var(--accent); }
                    button:disabled { opacity: 0.55; cursor: wait; }
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
                    }
                  </style>
                </head>
                <body>
                  <header>
                    <div class="bar">
                      <h1>Ricbot Console</h1>
                      <div class="meta">
                        <span id="workspace">workspace</span>
                        <span id="model">model</span>
                        <button id="refresh" type="button">Refresh</button>
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

                    const esc = (value) => String(value ?? "")
                      .replaceAll("&", "&amp;")
                      .replaceAll("<", "&lt;")
                      .replaceAll(">", "&gt;")
                      .replaceAll('"', "&quot;");

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
                      const klass = raw === "OK" || raw === "ok" || raw === "COMPLETED" || raw === "HEALTHY"
                        ? "ok"
                        : raw === "ERROR" || raw === "FAILED" || raw === "CRITICAL"
                          ? "error"
                          : "warning";
                      return `<span class="status ${klass}">${esc(raw)}</span>`;
                    }

                    function sectionHead(title, count, refreshName) {
                      const badge = count === undefined || count === null ? "" : `<span class="badge">${esc(count)}</span>`;
                      const refresh = refreshName ? `<button class="mini" type="button" data-refresh-name="${esc(refreshName)}">Refresh</button>` : "";
                      const updated = `<span class="sub">updated ${esc(new Date().toLocaleTimeString())}</span>`;
                      return `<div class="section-head"><h2>${esc(title)}</h2><div class="tools">${badge}${updated}${refresh}</div></div>`;
                    }

                    function empty(text) {
                      return `<div class="empty">${esc(text)}</div>`;
                    }

                    function loading(text = "Loading") {
                      return `<div class="empty">${esc(text)}</div>`;
                    }

                    function errorCard(title, error) {
                      return `${sectionHead(title, "error", null)}<div class="errbox">${esc(error.message || error)}</div>`;
                    }

                    function setBusy(button, busyText = "Working") {
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
                        ${sectionHead("Health", data.status || "ok", "health")}
                        <div class="value">${pill(data.status)}</div>
                        <div class="sub">bind ${esc(data.bindHost || "")} · readonly ${esc(data.readonly)}</div>
                        ${data.warning ? `<div class="sub">${esc(data.warning)}</div>` : ""}
                      `;
                    }

                    function renderConfig(data) {
                      document.getElementById("config-card").innerHTML = `
                        ${sectionHead("Config Doctor", data.status || "UNKNOWN", "config")}
                        <div class="value">${pill(data.status)}</div>
                        <div class="sub">${esc(data.inferredProvider)} · ${esc(data.model)}</div>
                        <div class="sub">api key present: ${esc(data.apiKeyPresent)}</div>
                        <div class="sub">${esc((data.warnings || []).length)} warnings · ${esc((data.errors || []).length)} errors</div>
                      `;
                    }

                    function renderTrace(data) {
                      const latest = data.latest || {};
                      const events = latest.events || [];
                      document.getElementById("trace-card").innerHTML = `
                        ${sectionHead("Latest Trace", `${events.length} events`, "traces")}
                        ${events.length === 0 ? empty("No trace found. Run a team task, then come back here for the timeline.") : `
                          <div class="title">${esc(latest.summary || latest.traceId || "Trace")}</div>
                          <div class="kv"><span>${pill(latest.status)}</span><span>${esc(events.length)} events</span><span>${esc(latest.startedAt || "")}</span></div>
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
                        ${sectionHead("Workspaces", `${items.length} total`, "workspaces")}
                        ${items.length === 0 ? empty("No workspaces yet. Run /team run with --worktree to create a managed workspace.") : `
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
                          <button type="button" data-workspace-action="change-create" data-workspace-id="${esc(item.id)}">Create ChangeSet</button>
                          <button type="button" data-workspace-action="discard" data-workspace-id="${esc(item.id)}">Discard</button>
                        </div>
                      `;
                    }

                    async function runWorkspaceAction(button, id, action) {
                      if (action === "discard" && !confirm(`Discard managed workspace ${id}?`)) return;
                      if (action === "change-create" && !confirm(`Create ChangeSet from workspace ${id}?`)) return;
                      const restore = setBusy(button, "Working");
                      try {
                        const body = action === "discard" ? { confirm: true } : null;
                        await postJson(`/console/api/workspaces/${encodeURIComponent(id)}/${action}`, body);
                        renderWorkspaces(await getJson("workspaces"));
                        renderActions(await getJson("actions"));
                      } catch (error) {
                        document.getElementById("workspace-card").innerHTML = errorCard("workspace action", error);
                      } finally {
                        restore();
                      }
                    }

                    function renderTeams(data) {
                      const items = data.items || [];
                      document.getElementById("team-card").innerHTML = `
                        ${sectionHead("Team Reports", `${items.length} total`, "teams")}
                        ${items.length === 0 ? empty("No team reports yet. After /team run, reports appear here for demo review.") : `
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
                        ${sectionHead("Tools", `${data.total || 0} total`, "tools")}
                        <div class="value">${esc(data.total || 0)}</div>
                        <div class="sub">${esc(data.builtinCount || 0)} builtin · ${esc(data.mcpCount || 0)} MCP · ${esc(data.generatedCount || 0)} generated</div>
                        ${items.length === 0 ? empty("No tools loaded") : `
                          <div class="list">${items.slice(0, 10).map(item => {
                            const params = item.parameters || {};
                            const names = (params.items || []).map(p => `${p.name}${p.required ? "*" : ""}`).join(", ");
                            return `
                              <div class="item">
                                <div class="title">${esc(item.name)}</div>
                                <div class="kv"><span>${esc(item.source)}</span><span>${esc(item.risk)}</span><span>${esc(item.enabled)}</span></div>
                                <div class="sub">${esc(item.description)}</div>
                                <div class="sub">${esc(names || "no parameters")}</div>
                              </div>
                            `;
                          }).join("")}</div>
                        `}
                      `;
                    }

                    function renderMcp(data) {
                      const servers = data.servers || [];
                      document.getElementById("mcp-card").innerHTML = `
                        ${sectionHead("MCP", `${servers.length} servers`, "mcp")}
                        <div class="value">${esc(data.configuredCount || 0)} / ${esc(data.connectedCount || 0)}</div>
                        <div class="sub">${esc(data.mcpToolCount || 0)} loaded MCP tools</div>
                        ${servers.length === 0 ? empty("No MCP servers configured") : `
                          <div class="list">${servers.slice(0, 8).map(server => `
                            <div class="item">
                              <div class="title">${esc(server.name)}</div>
                              <div class="kv"><span>${pill(server.status)}</span><span>${esc(server.transportType)}</span><span>${esc(server.loadedToolCount || 0)} tools</span></div>
                              <div class="sub">${esc((server.enabledTools || []).join(", ") || "all tools")}</div>
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
                        ${sectionHead("Experience", `${stats.candidates || 0} candidates`, "experiences")}
                        <div class="value">${esc(candidates.length)} / ${esc(verified.length)}</div>
                        <div class="sub">candidates / verified loaded</div>
                        <div class="sub">store totals: ${esc(stats.candidates || 0)} candidates · ${esc(stats.verified || 0)} verified</div>
                      `;
                      const rows = [...candidates.map(x => ({...x, bucket: "candidate"})), ...verified.map(x => ({...x, bucket: "verified"}))];
                      document.getElementById("experience-list-card").innerHTML = `
                        ${sectionHead("Experience Items", `${rows.length} shown`, "experiences")}
                        ${rows.length === 0 ? empty("No experience items yet. Verified lessons and generated skills will appear here.") : `
                          <div class="list">${rows.slice(0, 10).map(item => `
                            <div class="item">
                              <div class="row">
                                <div>
                                  <div class="title">${esc(item.title || item.id)}</div>
                                  <div class="kv"><span>${esc(item.bucket)}</span><span>${esc(item.type)}</span><span>${esc(item.confidence)}</span></div>
                                </div>
                                <div>
                                  ${item.status === "CANDIDATE" || item.bucket === "candidate" ? `<button type="button" data-exp-action="verify" data-exp-id="${esc(item.id)}">Verify</button> <button type="button" data-exp-action="reject" data-exp-id="${esc(item.id)}">Reject</button>` : ""}
                                  ${item.status === "VERIFIED" || item.bucket === "verified" ? `<button type="button" data-exp-action="promote-skill" data-exp-id="${esc(item.id)}">Promote Skill</button>` : ""}
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
                      const label = action === "promote-skill" ? "Promote Skill" : action === "verify" ? "Verify" : "Reject";
                      if (!confirm(`${label} experience ${id}?`)) return;
                      const restore = setBusy(button, label);
                      try {
                        await postJson(`/console/api/experiences/${encodeURIComponent(id)}/${action}`);
                        const experiences = await getJson("experiences");
                        renderExperiences(experiences);
                        renderActions(await getJson("actions"));
                      } catch (error) {
                        document.getElementById("experience-list-card").innerHTML = errorCard("experience action", error);
                      } finally {
                        restore();
                      }
                    }

                    function renderApprovals(data) {
                      const items = data.items || [];
                      document.getElementById("approval-card").innerHTML = `
                        ${sectionHead("Approvals", `${items.length} pending`, "approvals")}
                        ${items.length === 0 ? empty("No pending approvals. Tool or change approvals will wait here for human review.") : `
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
                                    <button type="button" data-approval-action="approve" data-approval-id="${esc(item.requestId)}">Approve</button>
                                    <button type="button" data-approval-action="reject" data-approval-id="${esc(item.requestId)}">Reject</button>
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
                      const label = action === "approve" ? "Approve" : "Reject";
                      if (!confirm(`${label} approval ${id}?`)) return;
                      const restore = setBusy(button, label);
                      try {
                        await postJson(`/console/api/approvals/${encodeURIComponent(id)}/${action}`);
                        renderApprovals(await getJson("approvals"));
                        renderActions(await getJson("actions"));
                      } catch (error) {
                        document.getElementById("approval-card").innerHTML = errorCard("approval action", error);
                      } finally {
                        restore();
                      }
                    }

                    function renderActions(data) {
                      const items = data.items || [];
                      document.getElementById("action-card").innerHTML = `
                        ${sectionHead("Console Actions", `${items.length} recent`, "actions")}
                        ${items.length === 0 ? empty("No console actions yet. Experience, approval, workspace, and smoke eval actions are audited here.") : `
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
                          <div>${sectionHead("Eval Runs", `${items.length} recent`, "evals")}</div>
                          <button type="button" id="run-smoke-eval">Run Smoke Eval</button>
                        </div>
                        <div id="eval-action-state"></div>
                        ${items.length === 0 ? empty("No eval runs yet. Use Run Smoke Eval or sh scripts/release-check.sh to create deterministic artifacts.") : `
                          <div class="list">${items.slice(0, 12).map(run => {
                            const failures = run.failuresByKind || {};
                            const failureText = Object.entries(failures).map(([kind, count]) => `${kind}:${count}`).join(" · ");
                            return `
                              <div class="item" id="eval-${esc(run.runId)}">
                                <div class="row">
                                  <div>
                                    <div class="title">${esc(run.runId || "eval run")}</div>
                                    <div class="kv">
                                      <span>${esc(run.passed || 0)} passed</span>
                                      <span>${esc(run.failed || 0)} failed</span>
                                      <span>${esc(run.skipped || 0)} skipped</span>
                                      <span>${esc(run.providerMode || "provider")}</span>
                                      <span>${esc(run.model || "model")}</span>
                                    </div>
                                    <div class="sub">${esc(run.createdAt || run.startedAt || "")}${failureText ? " · " + esc(failureText) : ""}</div>
                                    ${(run.warnings || []).length ? `<div class="sub">${esc((run.warnings || []).join(" · "))}</div>` : ""}
                                  </div>
                                  <button type="button" data-run-id="${esc(run.runId)}">Detail</button>
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
                      if (!confirm("Run fixed golden smoke eval?")) return;
                      const runButton = document.getElementById("run-smoke-eval");
                      const restore = setBusy(runButton, "Running");
                      const state = document.getElementById("eval-action-state");
                      if (state) state.innerHTML = empty("Running smoke eval");
                      try {
                        const result = await postJson("/console/api/evals/smoke");
                        if (state) state.innerHTML = `<div class="sub">completed: ${esc(result?.data?.runId || result?.id || "")}</div>`;
                        renderEvals(await getJson("evals"));
                        renderActions(await getJson("actions"));
                      } catch (error) {
                        if (state) state.innerHTML = `<div class="errbox">${esc(error.message || error)}</div>`;
                      } finally {
                        restore();
                      }
                    }

                    function renderReleaseCheck(data) {
                      const exists = Boolean(data.exists);
                      document.getElementById("release-check-card").innerHTML = `
                        ${sectionHead("Release Check", exists ? (data.finalStatus || "FOUND") : "empty", "releaseCheck")}
                        ${!exists ? empty(`No release-check report yet. Run ${data.command || "sh scripts/release-check.sh"}`) : `
                          <div class="kv">
                            <span>${pill(data.finalStatus || "UNKNOWN")}</span>
                            <span>baseline ${esc(data.baselineStatus || "unknown")}</span>
                            <span>compare ${esc(data.evalCompareStatus || "unknown")}</span>
                            <span>${esc(data.generatedAt || "")}</span>
                          </div>
                          <div class="sub">${esc(data.reportPath)}</div>
                          <div class="pre">${esc(data.reportMarkdown || "")}</div>
                        `}
                      `;
                    }

                    function renderDemoFlow(state) {
                      const steps = [
                        ["config doctor", "java -jar target/Ricbot-1.0-SNAPSHOT.jar config doctor -c config/ricbot.config.json", "Config Doctor", state.config?.status || "unknown"],
                        ["team run --worktree --verify", "/team run <task> --worktree --verify", "Team Reports", `${localCount(state.teams, ["items", "length"])} reports`],
                        ["team report", "/team report <taskId>", "Team Reports", `${localCount(state.teams, ["items", "length"])} reports`],
                        ["workspace diff", "/workspace diff <taskId>", "Workspaces", `${localCount(state.workspaces, ["items", "length"])} workspaces`],
                        ["change create", "/change create <taskId>", "Workspaces", `${localCount(state.workspaces, ["items", "length"])} workspaces`],
                        ["trace show", "/trace show <taskId>", "Latest Trace", `${localCount(state.traces, ["latest", "events", "length"])} events`],
                        ["experience verify", "/experience verify <id>", "Experience Items", `${localCount(state.experiences, ["candidates", "length"])} candidates`],
                        ["promote skill", "/experience promote-skill <id>", "Experience Items", `${localCount(state.experiences, ["verified", "length"])} verified`],
                        ["fixed smoke eval", "java -jar target/Ricbot-1.0-SNAPSHOT.jar eval smoke --scenarios evals/golden.jsonl", "Eval Runs", `${localCount(state.evals, ["items", "length"])} eval runs`],
                        ["release-check", "sh scripts/release-check.sh", "Release Check", state.releaseCheck?.finalStatus || (state.releaseCheck?.exists ? "found" : "empty")]
                      ];
                      document.getElementById("demo-flow-card").innerHTML = `
                        ${sectionHead("Demo Flow", "10 steps", null)}
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
                      target.innerHTML = empty("Loading detail");
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
                            ${failing.length === 0 ? empty("No failing cases") : failing.slice(0, 12).map(item => `
                              <div class="item">
                                <div class="title">${esc(item.id)}</div>
                                <div class="kv"><span>${pill(item.status)}</span><span>${esc(item.failureKind || "unknown")}</span><span>${esc(item.durationMs)}ms</span><span>${esc((item.tools || []).join(", "))}</span></div>
                                <div class="sub">${esc(item.artifactPath)}</div>
                              </div>
                            `).join("")}
                          </div>
                          <div class="sub">manifest: ${esc(manifest.provider_mode || "")} ${esc(manifest.model || "")}</div>
                          ${json.reportMarkdown ? `<div class="pre">${esc(json.reportMarkdown)}</div>` : ""}
                          ${(json.warnings || []).length ? `<div class="sub">${esc((json.warnings || []).join(" · "))}</div>` : ""}
                        `;
                      } catch (error) {
                        target.innerHTML = errorCard("eval detail", error);
                      }
                    }

                    async function load() {
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
                      Object.values(cards).forEach(id => document.getElementById(id).innerHTML = loading("Loading"));
                      document.getElementById("demo-flow-card").innerHTML = loading("Loading demo flow");
                      document.getElementById("experience-list-card").innerHTML = loading("Loading");
                      const loaded = {};
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
                      for (const [name, render] of jobs) {
                        try {
                          const data = await getJson(name);
                          loaded[name] = data;
                          render(data);
                        } catch (error) {
                          document.getElementById(cards[name]).innerHTML = errorCard(name, error);
                          if (name === "experiences") {
                            document.getElementById("experience-list-card").innerHTML = errorCard("experience items", error);
                          }
                        }
                      }
                      renderDemoFlow(loaded);
                    }

                    document.getElementById("refresh").addEventListener("click", load);
                    document.addEventListener("click", async (event) => {
                      const button = event.target.closest("[data-refresh-name]");
                      if (!button) return;
                      const name = button.getAttribute("data-refresh-name");
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
                      if (!renderers[name]) return;
                      const restore = setBusy(button, "Refresh");
                      try {
                        renderers[name](await getJson(name));
                      } catch (error) {
                        const target = {
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
                        }[name];
                        if (target) document.getElementById(target).innerHTML = errorCard(name, error);
                      } finally {
                        restore();
                      }
                    });
                    load();
                  </script>
                </body>
                </html>
                """;
    }
}
