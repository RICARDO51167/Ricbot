# Context Engineering Flow

Minimal sequence for showing context selection and source observability.

```bash
java -jar target/Ricbot-1.0-SNAPSHOT.jar agent \
  --config config/ricbot.config.json \
  --workspace target/demo-workspace \
  --session demo:context
```

```text
/status
/context
/context --detail
/context --sources
```

Expected points to show:

- `task_state` tracks the current goal and next action.
- `tool_trace` summarizes recent tool calls.
- `memory_recall`, `project_notes`, and `workspace_knowledge` are budgeted separately.
- `verified_experience` appears only after an experience has been manually verified.
- `/context --sources` shows source paths such as `experience/verified.jsonl:<id>`.

Candidate and rejected experience are intentionally excluded from context.
