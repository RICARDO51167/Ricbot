---
name: clawhub
description: 从公共技能注册表 ClawHub 搜索并安装代理技能。
keywords: clawhub, 技能搜索, 搜索技能, 安装技能, 可用技能, skill registry, agent skills
homepage: https://clawhub.ai
metadata: {"ricbot":{"emoji":"🦞"}}
---

# ClawHub

面向 AI 代理的公共技能注册表。支持用自然语言搜索（向量检索）。

## 何时使用

当用户提出以下任意需求时使用本技能：
- “帮我找一个用于……的技能”
- “搜索技能”
- “安装一个技能”
- “有哪些可用技能？”
- “更新我的技能”

## 搜索

```bash
npx --yes clawhub@latest search "web scraping" --limit 5
```

## 安装

```bash
npx --yes clawhub@latest install <slug> --workdir ~/.ricbot/workspace
```

将 `<slug>` 替换为搜索结果中的技能名称。该命令会把技能安装到 `~/.ricbot/workspace/skills/`（ricbot 从这里加载工作区技能）。务必带上 `--workdir`。

## 更新

```bash
npx --yes clawhub@latest update --all --workdir ~/.ricbot/workspace
```

## 列出已安装

```bash
npx --yes clawhub@latest list --workdir ~/.ricbot/workspace
```

## 注意事项

- 需要 Node.js（自带 `npx`）。
- 搜索与安装不需要 API key。
- 登录（`npx --yes clawhub@latest login`）仅在发布技能时需要。
- `--workdir ~/.ricbot/workspace` 非常关键——不带它会把技能装到当前目录，而不是 ricbot 的工作区。
- 安装后提醒用户开启新会话以加载技能。
