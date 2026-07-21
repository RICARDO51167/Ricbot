#!/usr/bin/env sh
set -eu

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
PROJECT_ROOT="$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)"
cd "$PROJECT_ROOT"

echo "== Ricbot smoke: targeted tests =="
sh ./mvnw -q -Dtest='ricbot.domain.eval.*Test,ricbot.domain.config.*Test' test

echo "== Ricbot smoke: package =="
sh ./mvnw -q -DskipTests package

echo "== Ricbot smoke: config doctor =="
java -jar target/Ricbot-1.0-SNAPSHOT.jar config doctor -c config/ricbot.config.json

echo "== Ricbot smoke: fixed eval smoke =="
java -jar target/Ricbot-1.0-SNAPSHOT.jar eval smoke \
  --scenarios evals/golden.jsonl \
  --workspace target/eval-smoke-workspace \
  --out target/eval-smoke-artifacts \
  --fail-fast

echo "== Ricbot smoke complete =="
