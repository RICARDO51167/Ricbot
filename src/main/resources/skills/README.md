# ricbot 技能

本目录包含用于扩展 ricbot 能力的内置技能。

## 技能格式

每个技能都是一个目录，其中包含 `SKILL.md` 文件，内容包括：
- YAML frontmatter（name、description、metadata 等）
- 面向代理的 Markdown 操作指令

当技能需要引用较大的本地文档或日志时，优先使用 ricbot 内置的
`grep` / `glob` 工具先缩小搜索范围，再读取完整文件。
大范围搜索先用 `grep(output_mode="count")` / `files_with_matches`，
对大结果集用 `head_limit` / `offset` 分页，
当需要发现目录结构时使用 `glob(entry_type="dirs")`。

## 致谢

这些技能改编自 [OpenClaw](https://github.com/openclaw/openclaw) 的技能系统。
技能格式与元数据结构遵循 OpenClaw 的约定，以保持兼容性。

## 可用技能

| Skill | Description |
|-------|-------------|
| `github` | 使用 `gh` CLI 与 GitHub 交互 |
| `weather` | 通过 wttr.in 与 Open-Meteo 获取天气信息 |
| `summarize` | 总结 URL、文件与 YouTube 视频 |
| `tmux` | 远程控制 tmux 会话 |
| `clawhub` | 从 ClawHub 注册表搜索与安装技能 |
| `skill-creator` | 创建新技能 |
