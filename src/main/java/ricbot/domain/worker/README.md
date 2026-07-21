# worker

持久 Worker 状态、Mailbox、Ack、Join、Handoff、Cancel 与 Recover。

## 边界

本说明只描述当前保留的能力。生成产物、运行数据和已删除模块不属于本目录契约。

## 子目录

无直接子目录。

## 直接文件

- `WorkerRuntime.java`：Worker Runtime：驱动对应执行生命周期。
- `WorkerSpec.java`：Worker Spec：声明配置、选项或不可变执行规格。
- `WorkerState.java`：Worker State：执行结果、报告或状态值对象。
- `WorkerStore.java`：Worker Store：负责状态持久化、读取或查询。

