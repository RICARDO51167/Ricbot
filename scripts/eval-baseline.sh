#!/usr/bin/env sh
set -eu

BASELINE_ROOT=".ricbot/eval-baselines"
BASELINE_NAME="golden"
BASELINE_DIR="$BASELINE_ROOT/$BASELINE_NAME"
RUNS_DIR="target/eval-baseline-runs"
WORKSPACE_DIR="target/eval-baseline-workspace"
JAR="target/Ricbot-1.0-SNAPSHOT.jar"

usage() {
  echo "Usage: sh scripts/eval-baseline.sh create [--force]"
  echo "       sh scripts/eval-baseline.sh show"
}

is_baseline_ready() {
  [ -f "$BASELINE_DIR/summary.json" ] && [ -f "$BASELINE_DIR/cases.jsonl" ]
}

show_baseline() {
  echo "baseline_name: $BASELINE_NAME"
  echo "baseline_path: $BASELINE_DIR"
  if is_baseline_ready; then
    echo "status: FOUND"
    if [ -f "$BASELINE_DIR/manifest.json" ]; then
      echo "manifest: $BASELINE_DIR/manifest.json"
    fi
    echo "summary: $BASELINE_DIR/summary.json"
    echo "cases: $BASELINE_DIR/cases.jsonl"
    if [ -f "$BASELINE_DIR/report.md" ]; then
      echo "report: $BASELINE_DIR/report.md"
    fi
  else
    echo "status: MISSING"
  fi
}

create_baseline() {
  force="false"
  while [ "$#" -gt 0 ]; do
    case "$1" in
      --force)
        force="true"
        ;;
      *)
        usage
        exit 2
        ;;
    esac
    shift
  done

  if is_baseline_ready && [ "$force" != "true" ]; then
    echo "baseline already exists: $BASELINE_DIR"
    echo "pass --force to replace it"
    exit 2
  fi

  mkdir -p "$RUNS_DIR" "$BASELINE_ROOT"
  sh ./mvnw -q -DskipTests package

  java -jar "$JAR" eval smoke \
    --scenarios evals/golden.jsonl \
    --workspace "$WORKSPACE_DIR" \
    --out "$RUNS_DIR" \
    --fail-fast

  latest_run="$(find "$RUNS_DIR" -mindepth 1 -maxdepth 1 -type d | sort | tail -n 1)"
  if [ -z "$latest_run" ]; then
    echo "baseline generation failed: no run directory found" >&2
    exit 1
  fi

  if [ "$force" = "true" ]; then
    rm -rf "$BASELINE_DIR"
  fi
  mkdir -p "$BASELINE_DIR"
  cp -R "$latest_run/." "$BASELINE_DIR/"

  echo "baseline created: $BASELINE_DIR"
  show_baseline
}

command="${1:-show}"
case "$command" in
  create)
    shift
    create_baseline "$@"
    ;;
  show)
    shift
    if [ "$#" -ne 0 ]; then
      usage
      exit 2
    fi
    show_baseline
    ;;
  -h|--help|help)
    usage
    ;;
  *)
    usage
    exit 2
    ;;
esac
