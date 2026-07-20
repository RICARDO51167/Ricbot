---
name: summarize
description: 总结或从 URL、播客与本地文件中提取文本/字幕（是“转录这个 YouTube/视频”的优质兜底方案）。
keywords: summarize, 总结, 摘要, 转录, 字幕, youtube, 视频, 播客, url, 文章
homepage: https://summarize.sh
metadata: {"ricbot":{"emoji":"🧾","requires":{"bins":["summarize"]},"install":[{"id":"brew","kind":"brew","formula":"steipete/tap/summarize","bins":["summarize"],"label":"安装 summarize（brew）"}]}}
---

# Summarize

一个快速的 CLI，用于总结 URL、本地文件与 YouTube 链接。

## 何时使用（触发语句）

当用户提出以下任意需求时，立即使用本技能：
- “use summarize.sh”
- “这个链接/视频讲什么？”
- “总结这个 URL/文章”
- “转录这个 YouTube/视频”（尽力提取字幕；无需 `yt-dlp`）

## 快速开始

```bash
summarize "https://example.com" --model google/gemini-3-flash-preview
summarize "/path/to/file.pdf" --model google/gemini-3-flash-preview
summarize "https://youtu.be/dQw4w9WgXcQ" --youtube auto
```

## YouTube：总结 vs 字幕

尽力提取字幕（仅 URL）：

```bash
summarize "https://youtu.be/dQw4w9WgXcQ" --youtube auto --extract-only
```

如果用户要完整字幕但内容过大，先返回精炼摘要，然后询问希望展开的章节/时间范围。

## 模型与密钥

为你选择的 provider 设置对应的 API Key：
- OpenAI: `OPENAI_API_KEY`
- Anthropic: `ANTHROPIC_API_KEY`
- xAI: `XAI_API_KEY`
- Google: `GEMINI_API_KEY` (aliases: `GOOGLE_GENERATIVE_AI_API_KEY`, `GOOGLE_API_KEY`)

如果未设置模型，默认使用 `google/gemini-3-flash-preview`。

## 常用参数

- `--length short|medium|long|xl|xxl|<chars>`
- `--max-output-tokens <count>`
- `--extract-only`（仅 URL）
- `--json`（便于机器解析）
- `--firecrawl auto|off|always`（兜底提取）
- `--youtube auto`（若设置了 `APIFY_API_TOKEN` 则可用 Apify 兜底）

## 配置

可选配置文件：`~/.summarize/config.json`

```json
{ "model": "openai/gpt-5.2" }
```

可选服务：
- `FIRECRAWL_API_KEY`：用于被拦截的网站
- `APIFY_API_TOKEN`：用于 YouTube 兜底
