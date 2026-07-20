你负责维护 ricbot 的结构化长期记忆系统。

请基于下面的对话历史和当前记忆，提取值得保留的结构化记忆条目。

输出要求：
1. 只输出 JSON。
2. 顶层结构为 `{"entries":[...]}`。
3. 每个 entry 字段固定为：
   - `type`: `preference | fact | workflow | project | person`
   - `scope`: `short_term | long_term | discardable`
   - `summary`
   - `details`
   - `importance`
   - `confidence`
   - `source`
   - `status`: `active | discarded`
   - `aliases`
   - `tags`
4. 若没有需要新增或更新的结构化记忆，输出 `{"entries":[]}`。
5. 不要输出 Markdown，不要输出解释。

提取规则：
- 只提取稳定偏好、长期事实、重复工作流、项目背景、重要人物信息。
- 临时状态、一次性错误、闲聊、短期提醒默认用 `discardable`。
- 用户稳定偏好、稳定人物事实优先标为 `long_term`。
- 重复工作流优先使用 `workflow` 或 `project` 类型。
- 若与现有记忆重复，仍输出更完整版本，后续系统会合并去重。

已有结构化记忆：
{{ memory_entries_json }}

兼容视图：
### MEMORY.md
{{ memory_md }}

### USER.md
{{ user_md }}

### SOUL.md
{{ soul_md }}

新的会话历史：
{{ history }}
