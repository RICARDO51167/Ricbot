# workspace

Local/Worktree 工作区会话、差异和清理领域模型。

## 边界

本说明只描述当前保留的能力。生成产物、运行数据和已删除模块不属于本目录契约。

## 子目录

无直接子目录。

## 直接文件

- `GitWorktreeWorkspaceBackend.java`：Git Worktree Workspace Backend：本包内的领域类型或协作组件。
- `LocalWorkspaceBackend.java`：Local Workspace Backend：本包内的领域类型或协作组件。
- `RuntimeArtifactFilter.java`：Runtime Artifact Filter：驱动对应执行生命周期。
- `WorkspaceBackend.java`：Workspace Backend：本包内的领域类型或协作组件。
- `WorkspaceBackendType.java`：Workspace Backend Type：本包内的领域类型或协作组件。
- `WorkspaceLifecycleService.java`：Workspace Lifecycle Service：封装该领域用例或业务规则。
- `WorkspaceRenderer.java`：Workspace Renderer：本包内的领域类型或协作组件。
- `WorkspaceSession.java`：Workspace Session：本包内的领域类型或协作组件。
- `WorkspaceSessionStatus.java`：Workspace Session Status：执行结果、报告或状态值对象。
- `WorkspaceSessionStore.java`：Workspace Session Store：负责状态持久化、读取或查询。

