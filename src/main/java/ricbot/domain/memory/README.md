# memory

结构化长期记忆、审批状态、租户隔离、召回和持久化。

## 边界

本说明只描述当前保留的能力。生成产物、运行数据和已删除模块不属于本目录契约。

## 子目录

无直接子目录。

## 直接文件

- `MemoryEntry.java`：Memory Entry：本包内的领域类型或协作组件。
- `MemoryRetriever.java`：Memory Retriever：本包内的领域类型或协作组件。
- `MemoryStore.java`：Memory Store：负责状态持久化、读取或查询。
- `MemoryType.java`：Memory Type：本包内的领域类型或协作组件。
- `MemoryWritePolicy.java`：Memory Write Policy：本包内的领域类型或协作组件。
- `TenantMemoryService.java`：Tenant Memory Service：封装该领域用例或业务规则。
