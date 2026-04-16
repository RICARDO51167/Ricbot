根据下面的分析更新记忆文件。
- [FILE] 条目：将描述的内容添加到对应文件
- [FILE-REMOVE] 条目：从记忆文件中删除对应内容
- [SKILL] 条目：使用 write_file 在 skills/<name>/SKILL.md 下创建新技能

## 文件路径（相对于工作区根目录）
- SOUL.md
- USER.md
- memory/MEMORY.md
- skills/<name>/SKILL.md（仅用于 [SKILL] 条目）

不要猜测路径。

## 编辑规则
- 直接编辑——下方已提供文件内容，无需再 read_file
- old_text 必须使用完全一致的原文，并包含前后空行以确保唯一匹配
- 同一文件的多处修改合并到一次 edit_file 调用
- 删除时：将“章节标题 + 所有条目”作为 old_text，new_text 置空
- 只做外科手术式修改——不要重写整个文件
- 若无需更新，直接停止，不要调用工具

## 技能创建规则（用于 [SKILL] 条目）
- 使用 write_file 创建 skills/<name>/SKILL.md
- 写入前先 read_file `{{ skill_creator_path }}` 参考格式（frontmatter 结构、命名规范、质量标准）
- **去重检查**：读取下方列出的已有技能，确认新技能在功能上不重复。若已有技能覆盖同一工作流则跳过创建。
- 包含 YAML frontmatter，至少包含 name 与 description 字段
- SKILL.md 控制在 2000 词以内——简洁、可执行
- 必须包含：适用场景、步骤、输出格式、至少一个示例
- 不要覆盖已有技能——若技能目录已存在则跳过
- 引用代理可用的具体工具（read_file、write_file、exec、web_search 等）
- 技能是“操作指令集”，不是代码——不要包含实现代码

## 质量要求
- 每一行都应具备独立价值
- 清晰标题下使用简洁要点
- 需要精简（而不是删除）时：保留关键事实，去掉冗长细节
- 不确定是否该删时：先保留，但加上“（待核实是否仍有效）”
