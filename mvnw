#!/bin/sh
set -eu

BASE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
WRAPPER_JAR="$BASE_DIR/.mvn/wrapper/maven-wrapper.jar"

if [ ! -f "$WRAPPER_JAR" ]; then
  echo "mvnw: wrapper jar not found: $WRAPPER_JAR" >&2
  exit 1
fi

JAVA_BIN="java"
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  JAVA_BIN="$JAVA_HOME/bin/java"
fi

exec "$JAVA_BIN" ${MAVEN_OPTS:-} -Dmaven.multiModuleProjectDirectory="$BASE_DIR" -classpath "$WRAPPER_JAR" org.apache.maven.wrapper.MavenWrapperMain "$@"
