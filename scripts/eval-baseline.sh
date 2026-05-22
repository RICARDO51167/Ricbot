#!/usr/bin/env sh
set -eu

BASELINE_ROOT="${RICBOT_EVAL_BASELINE_ROOT:-target/eval-baseline}"
BASELINE_LATEST="$BASELINE_ROOT/latest"
BASELINE_RUNS="$BASELINE_ROOT/runs"

mkdir -p "$BASELINE_RUNS"

sh ./mvnw -q -DskipTests package

java -jar target/Ricbot-1.0-SNAPSHOT.jar eval smoke \
  --scenarios evals/golden.jsonl \
  --workspace target/eval-baseline-workspace \
  --out "$BASELINE_RUNS" \
  --fail-fast

latest_run="$(find "$BASELINE_RUNS" -mindepth 1 -maxdepth 1 -type d | sort | tail -n 1)"
if [ -z "$latest_run" ]; then
  echo "baseline generation failed: no run directory found" >&2
  exit 1
fi

rm -f "$BASELINE_LATEST"
rm -rf "$BASELINE_LATEST"
mkdir -p "$BASELINE_ROOT"
cp -R "$latest_run" "$BASELINE_LATEST"

echo "baseline updated: $BASELINE_LATEST"
