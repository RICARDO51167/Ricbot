# workspace

对应生产包 `workspace` 的自动化测试，验证正常路径、失败边界和持久化不变量。

## 边界

本说明只描述当前保留的能力。生成产物、运行数据和已删除模块不属于本目录契约。

## 子目录

无直接子目录。

## 直接文件

- `GitWorktreeWorkspaceBackendTest.java`：验证 Git Worktree Workspace Backend Test 的行为与边界。
- `LocalWorkspaceBackendTest.java`：验证 Local Workspace Backend Test 的行为与边界。
- `WorkspaceLifecycleServiceTest.java`：验证 Workspace Lifecycle Service Test 的行为与边界。
- `WorkspaceSessionStoreTest.java`：验证 Workspace Session Store Test 的行为与边界。

