# evals

确定性评测场景和基线输入；产物写到 target，不回写本目录。

## 边界

本说明只描述当前保留的能力。生成产物、运行数据和已删除模块不属于本目录契约。

## 子目录

- `baselines/golden/`：受版本管理、脱敏且固定的发布比较基线；只能通过显式 promote 更新。

运行 `sh scripts/eval-baseline.sh create` 会把候选写入 `target/eval-baseline-candidate/`，不会修改本目录。审查候选 diff 后，使用 `sh scripts/eval-baseline.sh promote --from target/eval-baseline-candidate --force` 显式更新基线。

## 直接文件

- `golden.jsonl`：golden.jsonl：逐行记录的场景或基线数据。
- `long_trajectory.jsonl`：long_trajectory.jsonl：逐行记录的场景或基线数据。
