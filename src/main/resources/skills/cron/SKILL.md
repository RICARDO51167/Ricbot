---
name: cron
description: 安排提醒与周期性任务。
keywords: cron, 定时, 提醒, 周期任务, 定时任务, schedule, reminder
---

# Cron

使用 `cron` 工具来安排提醒或周期性任务。

## 三种模式

1. **提醒**：message 会直接发送给用户
2. **任务**：message 是任务描述，代理会执行并发送结果
3. **一次性**：在指定时间只运行一次，随后自动删除

## 示例

固定提醒：
```
cron(action="add", message="Time to take a break!", every_seconds=1200)
```

动态任务（每次触发都由代理执行）：
```
cron(action="add", message="Check HKUDS/ricbot GitHub stars and report", every_seconds=600)
```

一次性定时任务（从当前时间计算 ISO datetime）：
```
cron(action="add", message="Remind me about the meeting", at="<ISO datetime>")
```

带时区的 cron：
```
cron(action="add", message="Morning standup", cron_expr="0 9 * * 1-5", tz="America/Vancouver")
```

列出/删除：
```
cron(action="list")
cron(action="remove", job_id="abc123")
```

## 时间表达

| 用户说法 | 参数 |
|-----------|------------|
| 每 20 分钟 | every_seconds: 1200 |
| 每小时 | every_seconds: 3600 |
| 每天早上 8 点 | cron_expr: "0 8 * * *" |
| 工作日 5 点 | cron_expr: "0 17 * * 1-5" |
| 温哥华时间每天 9 点 | cron_expr: "0 9 * * *", tz: "America/Vancouver" |
| 在某个具体时间 | at: ISO datetime 字符串（从当前时间计算） |

## 时区

结合 `cron_expr` 使用 `tz`，可按指定 IANA 时区来调度。不提供 `tz` 时，使用服务器本地时区。
