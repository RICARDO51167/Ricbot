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
                    <section class="span-4" id="health-card"></section>
                    <section class="span-4" id="config-card"></section>
                    <section class="span-4" id="experience-card"></section>
                    <section class="span-8" id="trace-card"></section>
                    <section class="span-4" id="workspace-card"></section>
                    <section class="span-6" id="team-card"></section>
                    <section class="span-6" id="experience-list-card"></section>
                    <section class="span-12" id="approval-card"></section>
                    <section class="span-12" id="action-card"></section>
                    <section class="span-12" id="eval-card"></section>
                  </main>
                  <script>
                    const endpoints = {
                      health: "/console/api/health",
                      config: "/console/api/config-doctor",
                      traces: "/console/api/traces",
                      teams: "/console/api/team-reports",
                      workspaces: "/console/api/workspaces",
                      experiences: "/console/api/experiences",
                      approvals: "/console/api/approvals",
                      actions: "/console/api/actions",
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

                    async function postJson(url) {
                      const response = await fetch(url, { method: "POST", headers: { "Accept": "application/json" } });
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

                    function empty(text) {
                      return `<div class="empty">${esc(text)}</div>`;
                    }

                    function errorCard(title, error) {
                      return `<h2>${esc(title)}</h2><div class="errbox">${esc(error.message || error)}</div>`;
                    }

                    function renderHealth(data) {
                      document.getElementById("workspace").textContent = data.workspace || "workspace";
                      document.getElementById("model").textContent = data.model || "model";
                      document.getElementById("health-card").innerHTML = `
                        <h2>Health</h2>
                        <div class="value">${pill(data.status)}</div>
                        <div class="sub">bind ${esc(data.bindHost || "")} · readonly ${esc(data.readonly)}</div>
                        ${data.warning ? `<div class="sub">${esc(data.warning)}</div>` : ""}
                      `;
                    }

                    function renderConfig(data) {
                      document.getElementById("config-card").innerHTML = `
                        <h2>Config Doctor</h2>
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
                        <h2>Latest Trace</h2>
                        ${events.length === 0 ? empty("No trace found") : `
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
                        <h2>Workspaces</h2>
                        ${items.length === 0 ? empty("No workspaces") : `
                          <div class="value">${esc(items.length)}</div>
                          <div class="list">${items.slice(0, 6).map(item => `
                            <div class="item">
                              <div class="title">${esc(item.id)}</div>
                              <div class="kv"><span>${esc(item.type)}</span><span>${esc(item.status)}</span></div>
                              <div class="sub">${esc(item.goal || item.branchName || item.workspacePath)}</div>
                            </div>
                          `).join("")}</div>
                        `}
                      `;
                    }

                    function renderTeams(data) {
                      const items = data.items || [];
                      document.getElementById("team-card").innerHTML = `
                        <h2>Team Reports</h2>
                        ${items.length === 0 ? empty("No team reports") : `
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

                    function renderExperiences(data) {
                      const stats = data.stats || {};
                      const candidates = data.candidates || [];
                      const verified = data.verified || [];
                      document.getElementById("experience-card").innerHTML = `
                        <h2>Experience</h2>
                        <div class="value">${esc(candidates.length)} / ${esc(verified.length)}</div>
                        <div class="sub">candidates / verified loaded</div>
                        <div class="sub">store totals: ${esc(stats.candidates || 0)} candidates · ${esc(stats.verified || 0)} verified</div>
                      `;
                      const rows = [...candidates.map(x => ({...x, bucket: "candidate"})), ...verified.map(x => ({...x, bucket: "verified"}))];
                      document.getElementById("experience-list-card").innerHTML = `
                        <h2>Experience Items</h2>
                        ${rows.length === 0 ? empty("No experience items") : `
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
                          button.getAttribute("data-exp-id"),
                          button.getAttribute("data-exp-action")
                        ));
                      });
                    }

                    async function runExperienceAction(id, action) {
                      const label = action === "promote-skill" ? "Promote Skill" : action === "verify" ? "Verify" : "Reject";
                      if (!confirm(`${label} experience ${id}?`)) return;
                      try {
                        await postJson(`/console/api/experiences/${encodeURIComponent(id)}/${action}`);
                        const experiences = await getJson("experiences");
                        renderExperiences(experiences);
                      } catch (error) {
                        document.getElementById("experience-list-card").innerHTML = errorCard("experience action", error);
                      }
                    }

                    function renderApprovals(data) {
                      const items = data.items || [];
                      document.getElementById("approval-card").innerHTML = `
                        <h2>Approvals</h2>
                        ${items.length === 0 ? empty("No pending approvals") : `
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
                          button.getAttribute("data-approval-id"),
                          button.getAttribute("data-approval-action")
                        ));
                      });
                    }

                    async function runApprovalAction(id, action) {
                      const label = action === "approve" ? "Approve" : "Reject";
                      if (!confirm(`${label} approval ${id}?`)) return;
                      try {
                        await postJson(`/console/api/approvals/${encodeURIComponent(id)}/${action}`);
                        renderApprovals(await getJson("approvals"));
                      } catch (error) {
                        document.getElementById("approval-card").innerHTML = errorCard("approval action", error);
                      }
                    }

                    function renderActions(data) {
                      const items = data.items || [];
                      document.getElementById("action-card").innerHTML = `
                        <h2>Console Actions</h2>
                        ${items.length === 0 ? empty("No console actions") : `
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
                        <h2>Eval Runs</h2>
                        ${items.length === 0 ? empty("No eval runs") : `
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
                        workspaces: "workspace-card",
                        experiences: "experience-card",
                        approvals: "approval-card",
                        actions: "action-card",
                        evals: "eval-card"
                      };
                      Object.values(cards).forEach(id => document.getElementById(id).innerHTML = empty("Loading"));
                      document.getElementById("experience-list-card").innerHTML = empty("Loading");
                      const jobs = [
                        ["health", renderHealth],
                        ["config", renderConfig],
                        ["traces", renderTrace],
                        ["workspaces", renderWorkspaces],
                        ["teams", renderTeams],
                        ["experiences", renderExperiences],
                        ["approvals", renderApprovals],
                        ["actions", renderActions],
                        ["evals", renderEvals]
                      ];
                      for (const [name, render] of jobs) {
                        try {
                          render(await getJson(name));
                        } catch (error) {
                          document.getElementById(cards[name]).innerHTML = errorCard(name, error);
                          if (name === "experiences") {
                            document.getElementById("experience-list-card").innerHTML = errorCard("experience items", error);
                          }
                        }
                      }
                    }

                    document.getElementById("refresh").addEventListener("click", load);
                    load();
                  </script>
                </body>
                </html>
                """;
    }
}
