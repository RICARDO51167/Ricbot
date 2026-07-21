---
name: memory
description: 结构化长期记忆与只追加会话历史；记忆候选必须经过显式审批。
always: true
---

# Memory

## 结构

- `SOUL.md` — 机器人性格与沟通风格的只读投影。
- `USER.md` — 已确认用户画像与偏好的只读投影。
- `memory/MEMORY.md` — 已审批长期事实的只读投影。
- `memory/memory_entries.jsonl` — 结构化记忆事实。
- `memory/candidates.jsonl` — 等待人工审批的候选记忆。
- `memory/history.jsonl` — 仅追加的 JSONL，不会被加载进上下文。搜索优先使用内置 `grep` 工具。

## 搜索历史事件

`memory/history.jsonl` 为 JSONL 格式——每行是一个 JSON 对象，包含 `cursor`、`timestamp`、`content`。

- 做大范围搜索时，先用 `grep(..., path="memory", glob="*.jsonl", output_mode="count")` 或默认的 `files_with_matches` 模式，再展开读取完整内容
- 需要精确命中行时，使用 `output_mode="content"` 并配合 `context_before` / `context_after`
- 匹配时间戳或 JSON 片段等字面量时使用 `fixed_strings=true`
- 使用 `head_limit` / `offset` 对长历史做分页
- 只有当内置搜索无法表达需求时，才把 `exec` 作为最后兜底

示例（将 `keyword` 替换为你的关键字）：
- `grep(pattern="keyword", path="memory/history.jsonl", case_insensitive=true)`
- `grep(pattern="2026-04-02 10:00", path="memory/history.jsonl", fixed_strings=true)`
- `grep(pattern="keyword", path="memory", glob="*.jsonl", output_mode="count", case_insensitive=true)`
- `grep(pattern="oauth|token", path="memory", glob="*.jsonl", output_mode="content", case_insensitive=true)`

## 重要

- **不要直接编辑 SOUL.md、USER.md 或 MEMORY.md。**它们由已审批结构化记忆生成。
- 模型推断和低置信度内容不得自动进入 Prompt。
- 候选记忆只有在用户通过 Memory API 明确批准后，才会进入长期记忆。
