---
name: memory
description: 双层记忆系统，由 Dream 自动管理知识文件。
always: true
---

# Memory

## 结构

- `SOUL.md` — 机器人性格与沟通风格。**由 Dream 管理。**请勿编辑。
- `USER.md` — 用户画像与偏好。**由 Dream 管理。**请勿编辑。
- `memory/MEMORY.md` — 长期事实（项目上下文、重要事件）。**由 Dream 管理。**请勿编辑。
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

- **不要编辑 SOUL.md、USER.md 或 MEMORY.md。**它们由 Dream 自动管理。
- 如果发现信息过时，Dream 下次运行时会进行修正。
- 用户可通过 `/dream-log` 查看 Dream 的活动记录。
