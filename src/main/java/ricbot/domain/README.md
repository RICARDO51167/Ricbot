# domain

核心领域模型；不依赖 CLI、HTTP Server 或界面实现。

## 边界

本说明只描述当前保留的能力。生成产物、运行数据和已删除模块不属于本目录契约。

## 子目录

- `agent/`：Agent Graph、Run Journal、Checkpoint、恢复、上下文和单次执行核心。
- `change/`：Git ChangeSet 的审阅、批准、提交与回滚边界。
- `config/`：配置诊断、有效配置报告和迁移提示模型。
- `eval/`：确定性评测、矩阵、回放、对比、快照和报告模型。
- `hook/`：Agent 生命周期钩子协议及其注册机制。
- `memory/`：结构化长期记忆、审批状态、租户隔离、召回和持久化。
- `message/`：CLI 入站/出站消息与进程内 MessageBus；这是内部事件机制，不是网络 Channel。
- `policy/`：角色、工具和命令策略，决定允许、拒绝或要求审批。
- `security/`：副作用审批、幂等预留、补偿和风险证据。
- `session/`：对话 Session 模型、管理与持久化契约。
- `team/`：Team 任务、计划步骤、验证、产物和 Worker 协作领域规则。
- `trace/`：只读 Trace 投影、检索和导出；不作为运行事实源。
- `workspace/`：Local/Worktree 工作区会话、差异和清理领域模型。

## 直接文件

除本说明外无直接文件；实现位于上列子目录。
