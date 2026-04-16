# 工具使用说明

工具签名会通过函数调用机制自动提供。
本文件记录一些不明显的限制与使用习惯。

## exec — 安全限制

- 命令有可配置超时（默认 60 秒）
- 危险命令会被拦截（rm -rf、format、dd、shutdown 等）
- 输出最多保留 10,000 字符，超出会截断
- 配置项 `restrictToWorkspace` 可将文件访问限制在工作区内

## glob — 文件发现

- 优先用 `glob` 按模式查找文件，再考虑退回到 shell 命令
- `*.py` 这类简单模式会按文件名递归匹配
- 需要匹配目录而不是文件时使用 `entry_type="dirs"`
- 使用 `head_limit` 与 `offset` 对大结果集做分页
- 只需要文件路径时，优先用它而不是 `exec`

## grep — 内容搜索

- 使用 `grep` 在工作区内搜索文件内容
- 默认只返回命中文件路径（`output_mode="files_with_matches"`）
- 支持可选的 `glob` 过滤以及 `context_before` / `context_after`
- 支持 `type="py"`、`type="ts"`、`type="md"` 等简写类型过滤
- 关键字包含正则字符且希望按字面匹配时使用 `fixed_strings=true`
- 只需要命中文件路径时使用 `output_mode="files_with_matches"`
- 读取全文前先估算命中规模时使用 `output_mode="count"`
- 使用 `head_limit` 与 `offset` 对结果分页
- 代码与历史搜索优先用它而不是 `exec`
- 为保证可读性，二进制或过大的文件可能会被跳过

## cron — 定时提醒

- 用法请参考 cron 技能。
