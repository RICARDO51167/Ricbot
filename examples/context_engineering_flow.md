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
- 长期规则由人工维护的 Skill 提供，不从运行结果自动晋升。
- `/context --sources` 只展示实际进入上下文的 Memory、Note、RAG、Team、Workspace 和 Trace 来源。

候选经验和已拒绝经验会被刻意排除在上下文之外。
