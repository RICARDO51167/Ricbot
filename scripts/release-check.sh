#!/usr/bin/env sh
set -u

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
PROJECT_ROOT="$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)"
cd "$PROJECT_ROOT"

REPORT_DIR="target"
REPORT="$REPORT_DIR/release-check-report.md"
LOG_DIR="$REPORT_DIR/release-check-logs"
SMOKE_OUT="target/release-check-eval-smoke"
REPLAY_OUT="target/release-check-eval-replay"
COMPARE_OUT="target/release-check-eval-compare"
BASELINE_DIR=".ricbot/eval-baselines/golden"
CONFIG_PATH="${RICBOT_CONFIG:-config/ricbot.config.json}"
JAR="target/Ricbot-1.0-SNAPSHOT.jar"

WARNINGS=""
WARNING_REASONS=""
FINAL_REASONS=""
FINAL_STATUS="PASS"
TEST_STATUS="NOT_RUN"
PACKAGE_STATUS="NOT_RUN"
CONFIG_STATUS="NOT_RUN"
SMOKE_STATUS="NOT_RUN"
COMPARE_STATUS="SKIPPED"
REPLAY_STATUS="NOT_RUN"
SCHEMA_STATUS="NOT_RUN"
BASELINE_STATUS="MISSING"
ARCHITECTURE_STATUS="NOT_RUN"
TEAM_RUNTIME_STATUS="NOT_RUN"
CONFIG_OUTPUT=""
SMOKE_OUTPUT=""
COMPARE_OUTPUT=""
REPLAY_OUTPUT=""
COMPARE_WARNINGS=""
COMPARE_REGRESSIONS="n/a"
COMPARE_IMPROVEMENTS="n/a"
COMPARE_UNCHANGED="n/a"
SMOKE_RUN_ID=""
SMOKE_ARTIFACTS=""

mkdir -p "$REPORT_DIR" "$LOG_DIR" "$SMOKE_OUT" "$REPLAY_OUT" "$COMPARE_OUT"

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

append_warning_reason() {
  WARNING_REASONS="${WARNING_REASONS}- $1
"
}

append_compare_warning() {
  COMPARE_WARNINGS="${COMPARE_WARNINGS}- $1
"
  append_warning "$1"
}

append_final_reason() {
  FINAL_REASONS="${FINAL_REASONS}- $1
"
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
    echo "- warning_reasons:"
    if [ -n "$WARNING_REASONS" ]; then
      printf "%s" "$WARNING_REASONS"
    elif [ "$FINAL_STATUS" = "WARNING" ]; then
      echo "- see warnings below"
    else
      echo "- none"
    fi
    echo
    echo "## Steps"
    echo
    echo "| Step | Result |"
    echo "| --- | --- |"
    echo "| mvn test | $TEST_STATUS |"
    echo "| package | $PACKAGE_STATUS |"
    echo "| config doctor | $CONFIG_STATUS |"
    echo "| eval smoke | $SMOKE_STATUS |"
    echo "| eval replay | $REPLAY_STATUS |"
    echo "| eval compare | $COMPARE_STATUS |"
    echo "| architecture hard-cut | $ARCHITECTURE_STATUS |"
    echo "| SQLite schema v2 | $SCHEMA_STATUS |"
    echo "| team runtime golden | $TEAM_RUNTIME_STATUS |"
    echo
    echo "## Baseline"
    echo
    echo "- baseline_status: $BASELINE_STATUS"
    echo "- baseline_path: $BASELINE_DIR"
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
    echo "## Eval Replay"
    echo
    echo "- status: $REPLAY_STATUS"
    echo
    echo '```text'
    printf "%s\n" "$REPLAY_OUTPUT"
    echo '```'
    echo
    echo "## Eval Compare"
    echo
    echo "- status: $COMPARE_STATUS"
    echo "- baseline: $BASELINE_DIR"
    echo "- candidate: ${SMOKE_ARTIFACTS:-n/a}"
    echo "- regressions: $COMPARE_REGRESSIONS"
    echo "- improvements: $COMPARE_IMPROVEMENTS"
    echo "- unchanged: $COMPARE_UNCHANGED"
    echo "- warnings:"
    if [ -n "$COMPARE_WARNINGS" ]; then
      printf "%s" "$COMPARE_WARNINGS"
    else
      echo "  - none"
    fi
    echo
    echo '```text'
    printf "%s\n" "$COMPARE_OUTPUT"
    echo '```'
    echo
    echo "## Final Decision"
    echo
    echo "- status: $FINAL_STATUS"
    echo "- reasons:"
    if [ -n "$FINAL_REASONS" ]; then
      printf "%s" "$FINAL_REASONS"
    elif [ "$FINAL_STATUS" = "PASS" ]; then
      echo "- all release gates passed"
    else
      echo "- see warnings and failed steps above"
    fi
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

TEST_TEMP_LOG="${TMPDIR:-/tmp}/ricbot-release-check-mvn-test-$$.log"
TEST_LOG="$LOG_DIR/mvn-clean-test.log"
if run_capture "mvn clean test" "$TEST_TEMP_LOG" sh ./mvnw -q clean test; then
  mkdir -p "$LOG_DIR" "$SMOKE_OUT" "$REPLAY_OUT" "$COMPARE_OUT"
  cp "$TEST_TEMP_LOG" "$TEST_LOG"
  rm -f "$TEST_TEMP_LOG"
  TEST_STATUS="PASS"
else
  mkdir -p "$LOG_DIR"
  cp "$TEST_TEMP_LOG" "$TEST_LOG"
  rm -f "$TEST_TEMP_LOG"
  TEST_STATUS="FAIL"
  mark_fail
  append_final_reason "mvn test failed"
  CONFIG_OUTPUT="mvn test failed; see $TEST_LOG"
  write_report
  echo "release-check failed: mvn test"
  echo "report: $REPORT"
  exit 1
fi

ARCHITECTURE_LOG="$LOG_DIR/architecture.log"
FORBIDDEN_TYPES="AgentRunner.java SpawnWorkerService.java SpawnTool.java AutoCompact.java Consolidator.java RuntimeV2QueryService.java RunCheckpoint.java RunCheckpointStore.java RunJournalStore.java RunEventSink.java RunResumeService.java RunRecoveryCoordinator.java RunEventReplayService.java"
: >"$ARCHITECTURE_LOG"
for forbidden_type in $FORBIDDEN_TYPES; do
  found="$(find src/main/java -name "$forbidden_type" -print)"
  if [ -n "$found" ]; then
    printf '%s\n' "$found" >>"$ARCHITECTURE_LOG"
  fi
done
for removed_dir in src/main/java/ricbot/application/team src/main/java/ricbot/domain/team src/main/java/ricbot/domain/worker; do
  if [ -d "$removed_dir" ] && find "$removed_dir" -type f -print -quit | grep -q .; then
    echo "$removed_dir" >>"$ARCHITECTURE_LOG"
  fi
done
if grep -R -n '/team' src/main/java --include='*.java' >>"$ARCHITECTURE_LOG" 2>/dev/null; then
  :
fi
if grep -R -n -E 'isReadOnly\(|isExclusive\(|supportsCompensation\(|preflight\(' \
    src/main/java --include='*.java' >>"$ARCHITECTURE_LOG" 2>/dev/null; then
  :
fi
if grep -R -n 'runtime-v2' src/main/java README.md --include='*.java' --include='*.md' \
    >>"$ARCHITECTURE_LOG" 2>/dev/null; then
  :
fi
if grep -R -n 'GraphRunService\|AgentApprovalGraphResumeService\|runTeamGraph' \
    src/main/java --include='*.java' >>"$ARCHITECTURE_LOG" 2>/dev/null; then
  :
fi
if grep -R -n 'new SqliteRuntimeStore' src/main/java --include='*.java' \
    | grep -v 'RuntimeStoreRegistry.java' >>"$ARCHITECTURE_LOG" 2>/dev/null; then
  :
fi
if grep -R -n 'Thread\.sleep' src/main/java/ricbot/application/runtime \
    src/main/java/ricbot/domain/agent/graph src/main/java/ricbot/domain/agent/AgentGraphFactory.java \
    >>"$ARCHITECTURE_LOG" 2>/dev/null; then
  :
fi
if ! grep -q 'AgentRuntimeFactory.create' src/main/java/ricbot/domain/agent/AgentRuntimeCoreFactory.java; then
  echo 'missing AgentRuntime production wiring' >>"$ARCHITECTURE_LOG"
fi
if ! grep -q 'RuntimeEventOpenTelemetrySubscriber' src/main/java/ricbot/application/runtime/AgentRuntimeFactory.java; then
  echo 'missing RuntimeEventOpenTelemetrySubscriber production wiring' >>"$ARCHITECTURE_LOG"
fi
if [ -s "$ARCHITECTURE_LOG" ]; then
  ARCHITECTURE_STATUS="FAIL"
  mark_fail
  append_final_reason "architecture hard-cut check found forbidden legacy runtime types"
  write_report
  echo "release-check failed: architecture hard-cut"
  echo "report: $REPORT"
  exit 1
fi
ARCHITECTURE_STATUS="PASS"

SCHEMA_LOG="$LOG_DIR/sqlite-schema-v2.log"
if run_capture "SQLite schema v2" "$SCHEMA_LOG" sh ./mvnw -q \
  -Dtest=SqliteRuntimeStoreTest test; then
  SCHEMA_STATUS="PASS"
else
  SCHEMA_STATUS="FAIL"
  mark_fail
  append_final_reason "SQLite schema v2 tests failed"
  write_report
  echo "release-check failed: SQLite schema v2"
  echo "report: $REPORT"
  exit 1
fi

TEAM_LOG="$LOG_DIR/team-runtime.log"
if run_capture "team runtime golden" "$TEAM_LOG" sh ./mvnw -q \
  -Dtest=TeamAgentRuntimeTest,LocalTaskSchedulerTest,PatchLedgerServiceTest,SqliteTaskClaimConcurrencyTest test; then
  TEAM_RUNTIME_STATUS="PASS"
else
  TEAM_RUNTIME_STATUS="FAIL"
  mark_fail
  append_final_reason "team runtime golden scenarios failed"
  write_report
  echo "release-check failed: team runtime golden"
  echo "report: $REPORT"
  exit 1
fi

PACKAGE_LOG="$LOG_DIR/package.log"
if run_capture "package" "$PACKAGE_LOG" sh ./mvnw -q -DskipTests package; then
  PACKAGE_STATUS="PASS"
else
  PACKAGE_STATUS="FAIL"
  mark_fail
  append_final_reason "package failed"
  CONFIG_OUTPUT="package failed; see $PACKAGE_LOG"
  write_report
  echo "release-check failed: package"
  echo "report: $REPORT"
  exit 1
fi

CONFIG_LOG="$LOG_DIR/config-doctor.log"
if run_capture "config doctor" "$CONFIG_LOG" java -jar "$JAR" config doctor -c "$CONFIG_PATH"; then
  :
else
  :
fi
CONFIG_OUTPUT="$(cat "$CONFIG_LOG")"
CONFIG_STATUS="$(printf "%s\n" "$CONFIG_OUTPUT" | awk -F': ' '/^status:/ {print $2; exit}')"
if [ -z "$CONFIG_STATUS" ]; then
  CONFIG_STATUS="UNKNOWN"
  append_warning "config doctor status was not found in output"
elif [ "$CONFIG_STATUS" != "OK" ]; then
  CONFIG_ERROR_CODES="$(printf "%s\n" "$CONFIG_OUTPUT" | awk -F': ' '/^errorCodes:/ {print $2; exit}')"
  if [ "$CONFIG_STATUS" = "ERROR" ] && [ -n "$CONFIG_ERROR_CODES" ] \
      && ! printf "%s\n" "$CONFIG_ERROR_CODES" | tr ',' '\n' | grep -qv '^MISSING_OPTIONAL_CREDENTIAL$'; then
    CONFIG_STATUS="OPTIONAL_CREDENTIALS_MISSING"
    WARNINGS="${WARNINGS}- optional local API credentials are not configured; fixed smoke uses no real key
"
  elif [ "$CONFIG_STATUS" = "WARNING" ]; then
    append_warning "config doctor reported warnings"
  else
    CONFIG_STATUS="FAIL"
    mark_fail
    append_final_reason "config doctor reported non-optional errors: ${CONFIG_ERROR_CODES:-NO_ERROR_CODE}"
    write_report
    echo "release-check failed: config doctor"
    echo "report: $REPORT"
    exit 1
  fi
fi

SMOKE_LOG="$LOG_DIR/eval-smoke.log"
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
  append_final_reason "fixed eval smoke failed"
  write_report
  echo "release-check failed: eval smoke"
  echo "report: $REPORT"
  exit 1
fi
if [ -z "$SMOKE_ARTIFACTS" ]; then
  append_warning "eval smoke artifacts path was not found in output"
fi

REPLAY_LOG="$LOG_DIR/eval-replay.log"
if run_capture "eval replay" "$REPLAY_LOG" java -jar "$JAR" eval replay \
  --run "$SMOKE_ARTIFACTS" \
  --workspace target/eval-release-check-replay-workspace \
  --out "$REPLAY_OUT" \
  --fail-fast; then
  REPLAY_STATUS="PASS"
else
  REPLAY_STATUS="FAIL"
  mark_fail
fi
REPLAY_OUTPUT="$(cat "$REPLAY_LOG")"
if [ "$REPLAY_STATUS" = "FAIL" ]; then
  append_final_reason "eval replay failed"
  write_report
  echo "release-check failed: eval replay"
  echo "report: $REPORT"
  exit 1
fi

if [ -d "$BASELINE_DIR" ] && [ -f "$BASELINE_DIR/summary.json" ] && [ -f "$BASELINE_DIR/cases.jsonl" ]; then
  BASELINE_STATUS="FOUND"
  COMPARE_LOG="$LOG_DIR/eval-compare.log"
  if run_capture "eval compare" "$COMPARE_LOG" java -jar "$JAR" eval compare \
    --baseline "$BASELINE_DIR" \
    --candidate "$SMOKE_ARTIFACTS" \
    --out "$COMPARE_OUT"; then
    COMPARE_STATUS="PASS"
  else
    COMPARE_STATUS="FAIL"
    mark_fail
    append_final_reason "eval compare found regressions"
  fi
  COMPARE_OUTPUT="$(cat "$COMPARE_LOG")"
  COMPARE_REGRESSIONS="$(printf "%s\n" "$COMPARE_OUTPUT" | awk -F': ' '/^regressions:/ {print $2; exit}')"
  COMPARE_IMPROVEMENTS="$(printf "%s\n" "$COMPARE_OUTPUT" | awk -F': ' '/^improvements:/ {print $2; exit}')"
  missing_cases="$(printf "%s\n" "$COMPARE_OUTPUT" | awk -F': ' '/^missing_cases:/ {print $2; exit}')"
  new_cases="$(printf "%s\n" "$COMPARE_OUTPUT" | awk -F': ' '/^new_cases:/ {print $2; exit}')"
  baseline_total="$(printf "%s\n" "$COMPARE_OUTPUT" | awk -F': ' '/^baseline_total:/ {print $2; exit}')"
  candidate_total="$(printf "%s\n" "$COMPARE_OUTPUT" | awk -F': ' '/^candidate_total:/ {print $2; exit}')"
  if [ -n "$baseline_total" ] && [ -n "$COMPARE_REGRESSIONS" ] && [ -n "$COMPARE_IMPROVEMENTS" ]; then
    COMPARE_UNCHANGED=$((baseline_total - COMPARE_REGRESSIONS - COMPARE_IMPROVEMENTS))
    if [ "$COMPARE_UNCHANGED" -lt 0 ]; then
      COMPARE_UNCHANGED=0
    fi
  fi
  if [ -z "$COMPARE_REGRESSIONS" ]; then
    COMPARE_REGRESSIONS="unknown"
    append_compare_warning "eval compare regressions count was not found"
  fi
  if [ -z "$COMPARE_IMPROVEMENTS" ]; then
    COMPARE_IMPROVEMENTS="unknown"
    append_compare_warning "eval compare improvements count was not found"
  elif [ "$COMPARE_IMPROVEMENTS" != "0" ]; then
    append_final_reason "eval compare found $COMPARE_IMPROVEMENTS improvement(s)"
  fi
  if [ -n "$missing_cases" ] && [ "$missing_cases" != "0" ]; then
    append_compare_warning "eval compare reported $missing_cases missing case(s)"
  fi
  if [ -n "$new_cases" ] && [ "$new_cases" != "0" ]; then
    append_compare_warning "eval compare reported $new_cases new case(s)"
    append_warning_reason "eval compare has new cases: $new_cases"
  fi
  if [ -n "$baseline_total" ] && [ -n "$candidate_total" ] && [ "$baseline_total" != "$candidate_total" ]; then
    append_compare_warning "eval compare case count changed: baseline=$baseline_total candidate=$candidate_total"
  fi
else
  COMPARE_STATUS="FAIL"
  COMPARE_OUTPUT="baseline not found: $BASELINE_DIR"
  mark_fail
  append_warning_reason "baseline missing"
  append_final_reason "fixed golden baseline is missing"
fi

write_report
echo "release-check final_status: $FINAL_STATUS"
echo "report: $REPORT"

if [ "$FINAL_STATUS" = "FAIL" ]; then
  exit 1
fi
exit 0
