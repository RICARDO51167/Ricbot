{% if system == 'Windows' %}
## 平台约束（Windows）
- 当前运行环境为 Windows。不要假设 `grep`、`sed`、`awk` 等 GNU 工具一定存在。
- 当 Windows 原生命令或文件工具更可靠时，优先使用它们。
- 若终端输出乱码，开启 UTF-8 输出后重试。
{% else %}
## 平台约束（POSIX）
- 当前运行环境为 POSIX 系统。优先使用 UTF-8 与标准 shell 工具。
- 当文件工具比 shell 命令更简单或更可靠时，优先使用文件工具。
{% endif %}
