#!/usr/bin/env sh
set -u

REPORT_DIR="target"
REPORT="$REPORT_DIR/release-check-report.md"
SMOKE_OUT="target/release-check-eval-smoke"
COMPARE_OUT="target/release-check-eval-compare"
BASELINE_DIR="${RICBOT_EVAL_BASELINE:-target/eval-baseline/latest}"
CONFIG_PATH="${RICBOT_CONFIG:-config/ricbot.config.json}"
JAR="target/Ricbot-1.0-SNAPSHOT.jar"

WARNINGS=""
FINAL_STATUS="PASS"
TEST_STATUS="NOT_RUN"
PACKAGE_STATUS="NOT_RUN"
CONFIG_STATUS="NOT_RUN"
SMOKE_STATUS="NOT_RUN"
COMPARE_STATUS="SKIPPED"
CONFIG_OUTPUT=""
SMOKE_OUTPUT=""
COMPARE_OUTPUT=""
SMOKE_RUN_ID=""
SMOKE_ARTIFACTS=""

mkdir -p "$REPORT_DIR" "$SMOKE_OUT" "$COMPARE_OUT"

now_utc() {
  date -u "+%Y-%m-%dT%H:%M:%SZ"
}

append_warning() {
  WARNINGS="${WARNINGS}- $1
"
  if [ "$FINAL_STATUS" = "PASS" ]; then
    FINAL_STATUS="WARNING"
  fi
}

mark_fail() {
  FINAL_STATUS="FAIL"
}

run_capture() {
  label="$1"
  outfile="$2"
  shift 2
  echo "== $label =="
  "$@" >"$outfile" 2>&1
}

write_report() {
  branch="$(git branch --show-current 2>/dev/null || echo unknown)"
  commit="$(git rev-parse --short HEAD 2>/dev/null || echo unknown)"
  dirty="$(git status --short 2>/dev/null | wc -l | tr -d ' ')"
  {
    echo "# Ricbot Release Check Report"
    echo
    echo "- generated_at: $(now_utc)"
    echo "- git_branch: $branch"
    echo "- git_commit: $commit"
    echo "- git_dirty_files: $dirty"
    echo "- final_status: $FINAL_STATUS"
    echo
    echo "## Steps"
    echo
    echo "| Step | Result |"
    echo "| --- | --- |"
    echo "| mvn test | $TEST_STATUS |"
    echo "| package | $PACKAGE_STATUS |"
    echo "| config doctor | $CONFIG_STATUS |"
    echo "| eval smoke | $SMOKE_STATUS |"
    echo "| eval compare | $COMPARE_STATUS |"
    echo
    echo "## Config Doctor"
    echo
    echo "- config: $CONFIG_PATH"
    echo "- status: $CONFIG_STATUS"
    echo
    echo '```text'
    printf "%s\n" "$CONFIG_OUTPUT"
    echo '```'
    echo
    echo "## Eval Smoke"
    echo
    echo "- status: $SMOKE_STATUS"
    echo "- run_id: ${SMOKE_RUN_ID:-n/a}"
    echo "- artifacts: ${SMOKE_ARTIFACTS:-n/a}"
    echo
    echo '```text'
    printf "%s\n" "$SMOKE_OUTPUT"
    echo '```'
    echo
    echo "## Eval Compare"
    echo
    echo "- status: $COMPARE_STATUS"
    echo "- baseline: $BASELINE_DIR"
    echo "- candidate: ${SMOKE_ARTIFACTS:-n/a}"
    echo
    echo '```text'
    printf "%s\n" "$COMPARE_OUTPUT"
    echo '```'
    echo
    echo "## Warnings"
    echo
    if [ -n "$WARNINGS" ]; then
      printf "%s" "$WARNINGS"
    else
      echo "- none"
    fi
  } >"$REPORT"
}

TEST_LOG="$REPORT_DIR/release-check-mvn-test.log"
if run_capture "mvn test" "$TEST_LOG" sh ./mvnw -q test; then
  TEST_STATUS="PASS"
else
  TEST_STATUS="FAIL"
  mark_fail
  CONFIG_OUTPUT="mvn test failed; see $TEST_LOG"
  write_report
  echo "release-check failed: mvn test"
  echo "report: $REPORT"
  exit 1
fi

PACKAGE_LOG="$REPORT_DIR/release-check-package.log"
if run_capture "package" "$PACKAGE_LOG" sh ./mvnw -q -DskipTests package; then
  PACKAGE_STATUS="PASS"
else
  PACKAGE_STATUS="FAIL"
  mark_fail
  CONFIG_OUTPUT="package failed; see $PACKAGE_LOG"
  write_report
  echo "release-check failed: package"
  echo "report: $REPORT"
  exit 1
fi

CONFIG_LOG="$REPORT_DIR/release-check-config-doctor.log"
if run_capture "config doctor" "$CONFIG_LOG" java -jar "$JAR" config doctor -c "$CONFIG_PATH"; then
  :
else
  append_warning "config doctor command returned non-zero; treated as diagnostic warning"
fi
CONFIG_OUTPUT="$(cat "$CONFIG_LOG")"
CONFIG_STATUS="$(printf "%s\n" "$CONFIG_OUTPUT" | awk -F': ' '/^status:/ {print $2; exit}')"
if [ -z "$CONFIG_STATUS" ]; then
  CONFIG_STATUS="UNKNOWN"
  append_warning "config doctor status was not found in output"
elif [ "$CONFIG_STATUS" != "OK" ]; then
  append_warning "config doctor reported $CONFIG_STATUS; missing local API keys do not fail release-check"
fi

SMOKE_LOG="$REPORT_DIR/release-check-eval-smoke.log"
if run_capture "fixed eval smoke" "$SMOKE_LOG" java -jar "$JAR" eval smoke \
  --scenarios evals/golden.jsonl \
  --workspace target/eval-release-check-workspace \
  --out "$SMOKE_OUT" \
  --fail-fast; then
  SMOKE_STATUS="PASS"
else
  SMOKE_STATUS="FAIL"
  mark_fail
fi
SMOKE_OUTPUT="$(cat "$SMOKE_LOG")"
SMOKE_RUN_ID="$(printf "%s\n" "$SMOKE_OUTPUT" | awk -F': ' '/^run_id:/ {print $2; exit}')"
SMOKE_ARTIFACTS="$(printf "%s\n" "$SMOKE_OUTPUT" | awk -F': ' '/^artifacts:/ {print $2; exit}')"
if [ "$SMOKE_STATUS" = "FAIL" ]; then
  write_report
  echo "release-check failed: eval smoke"
  echo "report: $REPORT"
  exit 1
fi
if [ -z "$SMOKE_ARTIFACTS" ]; then
  append_warning "eval smoke artifacts path was not found in output"
fi

if [ -d "$BASELINE_DIR" ] && [ -f "$BASELINE_DIR/summary.json" ]; then
  COMPARE_LOG="$REPORT_DIR/release-check-eval-compare.log"
  if run_capture "eval compare" "$COMPARE_LOG" java -jar "$JAR" eval compare \
    --baseline "$BASELINE_DIR" \
    --candidate "$SMOKE_ARTIFACTS" \
    --out "$COMPARE_OUT"; then
    COMPARE_STATUS="PASS"
  else
    COMPARE_STATUS="FAIL"
    mark_fail
  fi
  COMPARE_OUTPUT="$(cat "$COMPARE_LOG")"
else
  COMPARE_STATUS="SKIPPED"
  COMPARE_OUTPUT="baseline not found: $BASELINE_DIR"
  append_warning "eval compare skipped because baseline was not found: $BASELINE_DIR"
fi

write_report
echo "release-check final_status: $FINAL_STATUS"
echo "report: $REPORT"

if [ "$FINAL_STATUS" = "FAIL" ]; then
  exit 1
fi
exit 0
