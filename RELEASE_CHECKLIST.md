# Ricbot Release Checklist

## Required Gates

Run the release gate:

```bash
sh scripts/release-check.sh
```

Expected:

- `mvn test`: PASS
- `package`: PASS
- `eval smoke`: PASS
- `eval compare`: PASS when baseline exists
- `config doctor`: OK/WARNING preferred; ERROR is acceptable only when clearly caused by missing local API keys in a demo environment

Show current baseline:

```bash
sh scripts/eval-baseline.sh show
```

Run config doctor explicitly:

```bash
java -jar target/Ricbot-1.0-SNAPSHOT.jar config doctor \
  -c config/ricbot.config.json
```

## Console Smoke

Start API and Console:

```bash
java -jar target/Ricbot-1.0-SNAPSHOT.jar serve \
  -c config/ricbot.config.json
```

Open:

```text
http://127.0.0.1:8000/console
```

Check:

- Demo Flow visible
- Config Doctor card loads
- Release Check card reads `target/release-check-report.md`
- Trace / Team / Workspace cards show data or useful empty states
- Eval Runs card loads
- Tools / MCP card loads
- MCP diagnostics endpoint responds:

```text
GET /console/api/mcp/diagnostics
```

## Webhook Smoke

With `serve` already running:

```bash
sh scripts/webhook-smoke.sh
```

Expected:

- Feishu challenge returns 200
- Feishu/DingTalk/WeCom text requests return structured JSON
- Duplicate requests return `duplicate: true`
- No real platform or external network is used

## Current Limits To Confirm

- Console is local-first; do not expose publicly without `api.bearer_token`.
- Console MCP Hub is read-only; no reload/reconnect/start/stop and no tool invocation.
- Console fixed smoke eval is the only eval action exposed in UI.
- Team worktree changes still require human diff/ChangeSet review.
- Feishu/WeCom encrypted webhook callback decryption is not implemented.
- Attachments/images/voice webhook payloads are not normalized as text.
- Provider capability override is user-declared, not an online probe.
- Missing local API key may make config doctor report `ERROR`; deterministic smoke eval and release-check can still pass.

## Tag Naming

Recommended tag names:

```text
v5.6-demo-release
v5.6.0-demo
ricbot-v5.6-demo
```

For a public GitHub release, prefer:

```text
v5.6.0
```

## Final Manual Review

- README links resolve.
- `docs/architecture/ricbot-architecture.md` reflects the current module boundaries.
- `docs/demo/demo-script.md` can be followed in 5-8 minutes.
- `docs/interview/project-pitch.md` has concise versions and follow-up answers.
- `docs/resume/ricbot-bullets.md` has Chinese and English bullets.
- `target/release-check-report.md` contains no secrets.
- `git status --short` only shows intended release changes.
