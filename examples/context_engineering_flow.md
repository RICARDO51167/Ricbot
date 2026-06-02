# 上下文工程流程

用于展示上下文选择与来源可观测性的最小流程。

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

预期展示点：

- `task_state` 会跟踪当前目标和下一步动作。
- `tool_trace` 会汇总最近的工具调用。
- `memory_recall`、`project_notes` 和 `workspace_knowledge` 会分别占用上下文预算。
- `verified_experience` 只会在经验经过人工验证后出现。
- `/context --sources` 会展示类似 `experience/verified.jsonl:<id>` 的来源路径。

候选经验和已拒绝经验会被刻意排除在上下文之外。
