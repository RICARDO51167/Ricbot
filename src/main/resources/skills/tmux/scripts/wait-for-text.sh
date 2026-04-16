#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
用法：wait-for-text.sh -t target -p pattern [options]

轮询某个 tmux 窗格的输出，找到匹配文本后退出。

选项：
  -t, --target    tmux 目标（session:window.pane），必填
  -p, --pattern   要匹配的正则表达式，必填
  -F, --fixed     将 pattern 视为固定字符串（grep -F）
  -T, --timeout   等待秒数（整数，默认：15）
  -i, --interval  轮询间隔秒数（默认：0.5）
  -l, --lines     检查的历史行数（整数，默认：1000）
  -h, --help      显示本帮助
USAGE
}

target=""
pattern=""
grep_flag="-E"
timeout=15
interval=0.5
lines=1000

while [[ $# -gt 0 ]]; do
  case "$1" in
    -t|--target)   target="${2-}"; shift 2 ;;
    -p|--pattern)  pattern="${2-}"; shift 2 ;;
    -F|--fixed)    grep_flag="-F"; shift ;;
    -T|--timeout)  timeout="${2-}"; shift 2 ;;
    -i|--interval) interval="${2-}"; shift 2 ;;
    -l|--lines)    lines="${2-}"; shift 2 ;;
    -h|--help)     usage; exit 0 ;;
    *) echo "未知选项：$1" >&2; usage; exit 1 ;;
  esac
done

if [[ -z "$target" || -z "$pattern" ]]; then
  echo "必须提供 target 与 pattern" >&2
  usage
  exit 1
fi

if ! [[ "$timeout" =~ ^[0-9]+$ ]]; then
  echo "timeout 必须是整数秒数" >&2
  exit 1
fi

if ! [[ "$lines" =~ ^[0-9]+$ ]]; then
  echo "lines 必须是整数" >&2
  exit 1
fi

if ! command -v tmux >/dev/null 2>&1; then
  echo "PATH 中未找到 tmux" >&2
  exit 1
fi

# End time in epoch seconds (integer, good enough for polling)
start_epoch=$(date +%s)
deadline=$((start_epoch + timeout))

while true; do
  # -J joins wrapped lines, -S uses negative index to read last N lines
  pane_text="$(tmux capture-pane -p -J -t "$target" -S "-${lines}" 2>/dev/null || true)"

  if printf '%s\n' "$pane_text" | grep $grep_flag -- "$pattern" >/dev/null 2>&1; then
    exit 0
  fi

  now=$(date +%s)
  if (( now >= deadline )); then
    echo "等待 pattern 超时（${timeout} 秒）：$pattern" >&2
    echo "$target 的最后 ${lines} 行：" >&2
    printf '%s\n' "$pane_text" >&2
    exit 1
  fi

  sleep "$interval"
done
