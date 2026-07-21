# Ricbot Resume Bullets

## 简历 2 条版

- 设计并实现 Java 17 持久化 Agent Runtime，将 OpenAI-compatible API、工具调用、MCP、Team worktree、ChangeSet、Journal/Trace、Eval Gate 和本地 Console 串成可恢复、可观测、可治理的工程闭环。
- 构建 Agent 安全与发布门禁体系：Provider Capability 降级、workspace/SSRF/审批边界、Console auth/audit、deterministic smoke eval、baseline compare 和 `release-check` 报告。

## 简历 3 条版

- 从 0 到 1 实现 Java Agent Runtime，支持 CLI、OpenAI-compatible API、企业 IM webhook、本地 Console、AgentLoop/AgentRunner 和 ToolRegistry/MCP 扩展。
- 设计 Team/Workspace/ChangeSet 流程，将 Agent 编码任务隔离到受管 git worktree，并通过 verifier、diff、ChangeSet 和 trace 支持人工审阅。
- 建立 Agent 可观测与质量门禁：Config Doctor、Provider Capability Override、MCP Diagnostics、deterministic eval、baseline compare 和 release-check。

## 简历 5 条版

- 构建面向长任务与多智能体协作的 Java 17 持久化 Agent Runtime，覆盖 Journal/Checkpoint 精确恢复、副作用安全、Worker 协作、MCP、工作区隔离与 Eval 回归门禁。
- 实现 Team worktree 执行模式，将复杂任务拆分为计划、执行、验证，并用受管 git worktree 隔离修改。
- 设计 Workspace/ChangeSet 审阅链路，把 Agent 产生的 diff 收口成可审阅变更，降低自动化修改风险。
- 建立 Provider Capability 体系，支持静态/启发式推断和用户 override，对 tool calling、streaming、vision 等能力做运行时降级。
- 搭建 deterministic smoke eval、baseline compare、release-check 和 Console observability，用于发布前回归检查和现场演示。

## 中文版

- 独立设计并实现 Ricbot，一个 Java 17 Agent Runtime，支持模型调用、工具执行、MCP 扩展、团队式任务执行、工作区隔离、经验沉淀、评测门禁和本地 Console。
- 通过受管 git worktree、ChangeSet 审阅、trace 回放、Provider Capability 降级和 Console 安全边界，将 Agent 从 demo 形态推进到可诊断、可治理的工程系统。
- 构建 deterministic eval 与 release-check 流程，支持无真实模型环境下的 smoke test、baseline compare 和发布报告。

## English Version

- Built Ricbot, a Java 17 persistent Agent Runtime that integrates model providers, tool execution, MCP, team-based task execution, managed worktrees, recovery, eval gates, and a local observability Console.
- Designed governance boundaries for coding agents, including workspace isolation, ChangeSet review, trace replay, provider capability fallback, Console auth/audit, and deterministic release checks.
- Implemented MCP diagnostics and provider capability overrides to make tool exposure and model capability decisions explainable in OpenAI-compatible and private-model deployments.

## 后端方向版

- 设计 Java 17 后端 Agent Runtime，提供 CLI、HTTP API、Console、Webhook Gateway、配置加载、运行时诊断和发布门禁。
- 实现统一 ToolRegistry 和 MCP 适配层，将本地工具、MCP tools/resources/prompts 以一致 schema 暴露给 AgentRunner。
- 建立安全边界：API bearer、Console CSRF-lite、rate limit、audit、敏感字段脱敏、workspace 限制、SSRF 防护和高风险命令审批。

## Agent / AI Infra 方向版

- 构建面向 Coding Agent 的 AI Infra runtime，覆盖持久 Run、tool calling、context/memory、人工 Skill、provider capability fallback 和 deterministic eval。
- 设计 Team worktree + verifier + ChangeSet 的 Agent 编码闭环，让模型生成的变更可隔离、可审阅、可回放。
- 增强 MCP 和 OpenAI-compatible 生态可观测性：MCP diagnostics 解释工具暴露，Provider Capability Override 解决私有模型/中转网关能力不一致。
