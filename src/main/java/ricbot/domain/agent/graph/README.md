# graph

统一 Runtime 的确定性 Agent Graph、持久化 Superstep 与内置节点。

## 边界

本说明只描述当前保留的能力。生成产物、运行数据和已删除模块不属于本目录契约。

## 子目录

无直接子目录。

## 执行语义

- 同一 Superstep 的 `activeNodes` 读取同一 Channel 快照并行执行。
- Node Write 先独立持久化，再按 `planOrder/nodeId/activationId` 排序归并。
- Channel 必须在 `GraphStateSchema` 声明；未知 Channel 和 Reducer 冲突直接失败。
- 每步提交完整 Checkpoint；恢复时复用 Pending Write，只重跑未完成 Activation。
- 静态边显式选择 `FIRST_MATCH` 或 `FAN_OUT`，动态分支使用稳定 `GraphSend`。

`BuiltinGraphExecutors` 提供 Model、Tool、Approval、Worker、Join、ApplyChangeSet 和 Verifier 适配器；`DefaultTeamGraph` 定义本地 Team 的默认执行闭环。
