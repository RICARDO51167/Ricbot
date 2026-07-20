# 经验学习流程

将一次已完成任务转化为受治理经验的最小流程。

```text
/summary
/summary --write-note
/experience extract
/experience list
/experience show exp_...
/experience verify exp_...
/context --detail --sources
/experience promote exp_...
```

预期行为：

- `/summary` 渲染当前任务摘要，但不写入 notes。
- `/summary --write-note` 写入 `notes/tasks/<timestamp>-<slug>.md` 并更新 `notes/index.json`。
- `/experience extract` 将候选条目写入 `experience/candidates.jsonl`。
- 候选条目不会进入 `/context`。
- `/experience verify <id>` 将审阅后的条目写入 `experience/verified.jsonl`。
- 已验证条目可以出现在 `/context` 的 `verified_experience` 下。
- `/experience promote <id>` 需要显式触发，并通过 `NoteService` 写入项目 playbook note。

有用的验证片段：

```text
experience extracted:
status: CANDIDATE

experience verified
status: VERIFIED

verified_experience
experience/verified.jsonl:exp_...
```
