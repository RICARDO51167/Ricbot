# Ricbot Demo Script

这个脚本适合 5-8 分钟演示。目标是让听众理解 Ricbot 不是单点 chatbot，而是一个可治理的 Agent Runtime：从配置诊断、任务执行、隔离变更、trace、经验、评测到 Console 观测形成闭环。

## 0:00-0:45 Opening

一句话定位：

> Ricbot 是一个 Java 17 Agent Runtime，把 OpenAI-compatible 模型、多工具调用、团队式任务执行、受管 worktree、经验沉淀、评测门禁和本地 Console 串成一个工程化闭环。

强调三个关键词：

- 可演示：Console 和 CLI 都能展示完整路径。
- 可回放：trace、team report、eval artifact 都是可追溯数据。
- 可治理：工具权限、ChangeSet、release-check、capability fallback、MCP diagnostics 都是边界。

## 0:45-1:30 Config Doctor

命令：

```bash
java -jar target/Ricbot-1.0-SNAPSHOT.jar config doctor \
  -c config/ricbot.config.json
```

讲解点：

- 展示 model/provider/api_base 推断。
- 展示 API key 是否解析，不展示真实 key。
- 展示 tools、MCP、ports 和 provider capability。
- 没有真实 key 时，`ERROR` 是诊断信号，不影响 deterministic smoke eval。

过渡句：

> 先用 config doctor 把启动前风险讲清楚，避免 Agent 跑到一半才发现模型、工具或端口配置错了。

## 1:30-2:20 Release Check

命令：

```bash
sh scripts/release-check.sh
```

讲解点：

- 串联全量测试、package、config doctor、fixed smoke eval、baseline compare。
- 报告写入 `target/release-check-report.md`。
- deterministic smoke provider 不访问真实模型。
- baseline compare 关注 pass -> fail 回归。

如果输出是 `WARNING`：

> 这里 warning 来自本机缺 API key；核心门禁 test/package/smoke/compare 都通过。这个区分很重要，避免把环境缺失和代码回归混在一起。

## 2:20-3:10 Console Demo Flow

启动：

```bash
java -jar target/Ricbot-1.0-SNAPSHOT.jar serve \
  -c config/ricbot.config.json
```

打开：

```text
http://127.0.0.1:8000/console
```

讲解顺序：

- 顶部 Demo Flow：从 config 到 release gate 的 10 步路径。
- Config Doctor card：启动前诊断。
- Release Check card：发布门禁报告。
- Tools / MCP card：运行时工具面和 MCP 状态。

过渡句：

> Console 不替代 CLI，它把 CLI 产生的 artifact、trace 和诊断结果变成演示面和排查面。

## 3:10-4:30 Team Worktree Demo

交互模式：

```bash
java -jar target/Ricbot-1.0-SNAPSHOT.jar agent \
  -c config/ricbot.config.json
```

演示命令：

```text
/team run 给 README 增加一个很小的说明性修正 --worktree --verify
```

拿到 `taskId` 后：

```text
/team report <taskId>
/workspace diff <taskId>
/change create <taskId>
```

讲解点：

- team task 有 planner/worker/verifier 分工。
- `--worktree` 把修改隔离在受管 git worktree。
- `workspace diff` 让人先看变更。
- `change create` 把 diff 收口成可审阅 ChangeSet。

过渡句：

> Agent 可以写代码，但不能默认直接污染主工作区；worktree 和 ChangeSet 是人的审阅边界。

## 4:30-5:40 Trace / Eval / Experience

Trace：

```text
/trace show <taskId>
```

讲解点：

- model request/response；
- tool calls；
- warnings；
- workspace/change events。

Eval：

```bash
java -jar target/Ricbot-1.0-SNAPSHOT.jar eval smoke \
  --scenarios evals/golden.jsonl
```

讲解点：

- Agent 评测看最终行为，不只看单元函数。
- deterministic provider 让 smoke eval 在无 key 环境也稳定。
- baseline compare 检查回归。

Experience：

```text
/experience list
/experience verify <id>
/experience promote-skill <id>
```

讲解点：

- 经验先进入 candidate。
- 人工 verify 后才可 promote。
- generated skill 再进入后续上下文召回。

## 5:40-6:40 Tools / MCP Diagnostics

Console 中打开 Tools / MCP，或请求：

```text
GET /console/api/mcp/diagnostics
```

讲解点：

- 每个 MCP server 的 transport、status、loadedToolCount。
- 每个 MCP tool 是否注册、是否被 `enabled_tools` 允许、是否最终暴露给模型。
- schema summary 和 schema hash 用于确认工具面是否变化。
- command/env/url/lastError 脱敏。

过渡句：

> MCP 最大的问题不是接不上，而是接上后不知道哪些工具真的暴露给模型。diagnostics 把这个决策过程解释出来。

## 6:40-7:30 Webhook / Gateway

本地 smoke：

```bash
sh scripts/webhook-smoke.sh
```

讲解点：

- Feishu / DingTalk / WeCom 三类企业 IM 入站。
- token/sign 校验。
- 文本消息归一化到 MessageBus。
- 5 分钟内存去重。
- 加密回调和附件/图片/语音是当前限制。

## 7:30-8:00 Closing

总结：

> Ricbot 的重点不是做一个会聊天的 demo，而是把 Agent 放进工程边界里：配置可诊断，工具可治理，变更可审阅，行为可评测，运行态可观测，经验可沉淀。

最后强调适用场景：

- Coding Agent runtime 原型；
- AI Infra / Agent Platform 项目展示；
- 企业内部工具 agent 的安全边界设计；
- 面试中讲工程化 Agent 的完整样板。
