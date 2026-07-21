# persistence

共享 CAS 状态、不可变产物和文件持久化实现。

## 边界

本说明只描述当前保留的能力。生成产物、运行数据和已删除模块不属于本目录契约。

## 子目录

无直接子目录。

## 直接文件

- `EventClassificationCatalog.java`：Event Classification Catalog：事件、消息或审计事实模型。
- `FileRuntimeFactJournal.java`：File Runtime Fact Journal：驱动对应执行生命周期。
- `FileSharedStateStore.java`：File Shared State Store：负责状态持久化、读取或查询。
- `ImmutableArtifactStore.java`：Immutable Artifact Store：负责状态持久化、读取或查询。
- `RuntimeFactEvent.java`：Runtime Fact Event：驱动对应执行生命周期。
- `SharedStateStore.java`：Shared State Store：负责状态持久化、读取或查询。
- `SharedValue.java`：Shared Value：本包内的领域类型或协作组件。

