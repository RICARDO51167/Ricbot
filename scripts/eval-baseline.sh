#!/usr/bin/env sh
set -eu

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
PROJECT_ROOT="$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)"
cd "$PROJECT_ROOT"

BASELINE_ROOT="evals/baselines"
BASELINE_NAME="golden"
BASELINE_DIR="$BASELINE_ROOT/$BASELINE_NAME"
RUNS_DIR="target/eval-baseline-runs"
PROMOTION_DIR="target/eval-baseline-candidate"
WORKSPACE_DIR="target/eval-baseline-workspace"
JAR="target/Ricbot-1.0-SNAPSHOT.jar"

usage() {
  echo "Usage: sh scripts/eval-baseline.sh create"
  echo "       sh scripts/eval-baseline.sh promote --from RUN_DIR --force"
  echo "       sh scripts/eval-baseline.sh show"
}

is_baseline_ready() {
  [ -f "$BASELINE_DIR/summary.json" ] && [ -f "$BASELINE_DIR/cases.jsonl" ]
}

redact_file() {
  file="$1"
  escaped_root="$(printf '%s' "$PROJECT_ROOT" | sed 's/[|\\&]/\\&/g')"
  sed "s|$escaped_root|<PROJECT_ROOT>|g" "$file" >"$file.redacted"
  mv "$file.redacted" "$file"
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
  if [ "$#" -ne 0 ]; then usage; exit 2; fi

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

  rm -rf "$PROMOTION_DIR"
  mkdir -p "$PROMOTION_DIR"
  cp "$latest_run/manifest.json" "$latest_run/summary.json" "$latest_run/cases.jsonl" "$PROMOTION_DIR/"
  for file in manifest.json summary.json cases.jsonl; do
    redact_file "$PROMOTION_DIR/$file"
  done
  echo "baseline candidate created: $PROMOTION_DIR"
  echo "review it, then run: sh scripts/eval-baseline.sh promote --from $PROMOTION_DIR --force"
}

promote_baseline() {
  source_dir=""
  force="false"
  while [ "$#" -gt 0 ]; do
    case "$1" in
      --from) shift; source_dir="${1:-}" ;;
      --force) force="true" ;;
      *) usage; exit 2 ;;
    esac
    shift
  done
  if [ "$force" != "true" ] || [ -z "$source_dir" ]; then usage; exit 2; fi
  for file in manifest.json summary.json cases.jsonl; do
    [ -f "$source_dir/$file" ] || { echo "missing candidate file: $source_dir/$file" >&2; exit 1; }
  done
  mkdir -p "$BASELINE_DIR"
  cp "$source_dir/manifest.json" "$source_dir/summary.json" "$source_dir/cases.jsonl" "$BASELINE_DIR/"
  for file in manifest.json summary.json cases.jsonl; do
    redact_file "$BASELINE_DIR/$file"
  done
  echo "baseline promoted for review: $BASELINE_DIR"
  show_baseline
}

command="${1:-show}"
case "$command" in
  create)
    shift
    create_baseline "$@"
    ;;
  promote)
    shift
    promote_baseline "$@"
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
