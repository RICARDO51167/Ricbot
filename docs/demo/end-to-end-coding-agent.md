# End-to-End Coding Agent Demo

这个演示展示 Ricbot 从配置诊断、team worktree 执行、变更审阅、trace 复盘、经验沉淀到 Console 观测的完整闭环。

## 1. 准备

先构建：

```bash
sh ./mvnw -q -DskipTests package
```

准备配置：

```bash
cp config/ricbot.config.json config/ricbot.demo.json
```

如果要调用真实模型，确保 provider API key 已配置。固定 smoke eval 不需要真实模型。

## 2. Config Doctor

```bash
java -jar target/Ricbot-1.0-SNAPSHOT.jar config doctor \
  -c config/ricbot.demo.json
```

检查重点：

- workspace 是否正确
- model/provider/api_base 是否符合预期
- API key 是否已解析
- tools/web/exec/mcp 是否启用
- warnings 和 suggested fixes 是否需要处理

## 3. 启动交互模式

```bash
java -jar target/Ricbot-1.0-SNAPSHOT.jar agent \
  -c config/ricbot.demo.json
```

后续命令在交互模式中输入。

## 4. Team Worktree 执行

示例任务：

```text
/team run 给 README 增加一个很小的说明性修正 --worktree --verify
```

记录返回的 `taskId`。如果任务还在运行，可以查看：

```text
/team report <taskId>
```

## 5. Workspace Diff

查看受管 worktree 的变更：

```text
/workspace diff <taskId>
```

确认输出只包含预期文件和改动。

## 6. ChangeSet Review

把 worktree diff 收口为 ChangeSet：

```text
/change create <taskId>
```

再查看状态：

```text
/change status
/change diff
/change commit-message
```

本演示不执行 merge/commit。

## 7. Trace Viewer

查看任务 trace：

```text
/trace show <taskId>
```

关注：

- model request/response
- tool calls
- workspace/change events
- warnings 或 error

## 8. Experience 治理

查看候选经验：

```text
/experience list
```

人工验证：

```text
/experience verify <experienceId>
```

生成 skill：

```text
/experience promote-skill <experienceId>
```

生成内容会进入 `skills/generated/`，之后可被 SkillRouter 召回。

## 9. 打开 Console

另开终端启动服务：

```bash
java -jar target/Ricbot-1.0-SNAPSHOT.jar serve \
  -c config/ricbot.demo.json
```

打开：

```text
http://127.0.0.1:8000/console
```

在 Console 中查看：

- Header 右侧“中文 / English”可切换语言；默认中文，选择保存在 `localStorage` 的 `ricbot_console_lang`
- Demo Flow：用 10 步把 CLI 闭环映射到页面卡片，适合先给面试官或评审建立全景
- Config Doctor
- Latest Trace
- Team Reports
- Workspaces
- Experience Items
- Eval Runs
- Release Check
- Tools / MCP
- Recent Console Actions

Console 仍然是静态 HTML + CSS + 原生 JS，由单 jar 输出，不需要 Vue/React/npm 构建。

讲解建议：

- 先指顶部 Demo Flow：说明 Ricbot 不是单点聊天机器人，而是 `/team run -> report -> workspace diff -> change create -> trace -> experience -> eval -> release-check` 的工程闭环。
- 再看每个卡片右上角的数量和更新时间：有数据说明对应 CLI 能力已经产生 artifact，空状态则会提示下一步应该运行哪条命令。
- 遇到没有真实 key 的环境，解释 config doctor 的 warning/error 是诊断信号；fixed smoke eval、eval baseline 和 release-check 的 smoke 门禁仍然是 deterministic，不会访问真实模型或外网。
- Release Check 卡片只读展示 `target/release-check-report.md`，Console 不会从页面启动发布门禁。

## 10. 运行 Fixed Smoke Eval

CLI 方式：

```bash
java -jar target/Ricbot-1.0-SNAPSHOT.jar eval smoke \
  --scenarios evals/golden.jsonl \
  --workspace target/eval-smoke-workspace \
  --out workspace/.ricbot/evals
```

Console 方式：

```text
Eval Runs -> Run Smoke Eval
```

Console smoke 固定使用：

- `evals/golden.jsonl`
- `target/eval-console-smoke-workspace`
- deterministic smoke provider

它不会访问真实模型或外网。

## 11. 收口检查

建议最后运行：

```bash
sh scripts/smoke.sh
```

演示结束后不要自动 commit/merge；保留 ChangeSet、trace 和 Console action audit 供人工审阅。
