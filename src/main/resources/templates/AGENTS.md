# 代理说明

## 定时提醒

在安排提醒之前，先检查可用技能并优先遵循技能指引。
使用内置 `cron` 工具创建/列出/删除任务（不要通过 `exec` 调用 `ricbot cron`）。
从当前 session 获取 USER_ID 与 CHANNEL（例如从 `telegram:8281248569` 推出 `8281248569` 与 `telegram`）。

不要只把提醒写进 MEMORY.md——那不会触发实际通知。

## 心跳任务

系统会按配置的心跳间隔检查 `HEARTBEAT.md`。使用文件工具来管理周期性任务：

- **新增**：用 `edit_file` 追加新任务
- **移除**：用 `edit_file` 删除已完成任务
- **重写**：用 `write_file` 替换全部任务

当用户提出循环/周期性任务时，应更新 `HEARTBEAT.md`，而不是创建一次性的 cron 提醒。
