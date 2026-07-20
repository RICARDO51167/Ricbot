# Eval 学习流程

将 eval 失败转换为候选经验的最小流程。

运行 deterministic smoke eval：

```bash
java -jar target/Ricbot-1.0-SNAPSHOT.jar eval smoke \
  --scenarios evals/golden.jsonl \
  --workspace target/eval-smoke-workspace \
  --out target/eval-smoke-artifacts
```

从运行 artifact 中学习：

```bash
java -jar target/Ricbot-1.0-SNAPSHOT.jar eval learn \
  --run target/eval-smoke-artifacts/<run-id> \
  --workspace target/demo-workspace \
  --include-xfail
```

预期输出：

```text
ricbot eval learn
candidates_generated:
added:
skipped_duplicate:
- exp_... [TEST_POLICY] ... sourceRef=eval:<runId>:<caseId>
candidates_file: .../experience/candidates.jsonl
```

审阅流程：

```text
/experience list
/experience show exp_...
/experience verify exp_...
/experience promote exp_...
```

安全规则：

- `eval learn` 只写入候选经验。
- 它不会验证、promote，也不会把条目加入 prompt context。
- 对相同 `sourceRef + type + title` 重复运行时会跳过重复项。
