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

- Config Doctor
- Latest Trace
- Team Reports
- Workspaces
- Experience Items
- Eval Runs
- Tools / MCP
- Recent Console Actions

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
