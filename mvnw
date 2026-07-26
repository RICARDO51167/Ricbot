#!/bin/sh
# =============================================================================
# Maven Wrapper 启动脚本
# 该脚本用于在没有全局安装 Maven 的情况下，通过 Maven Wrapper 运行 Maven 命令
# =============================================================================

# 设置脚本在遇到错误时立即退出，并且在使用未设置的变量时报错
set -eu

# 获取脚本所在的绝对路径，作为项目的基础目录
BASE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
# 定义 Maven Wrapper JAR 文件的路径
WRAPPER_JAR="$BASE_DIR/.mvn/wrapper/maven-wrapper.jar"

# 检查 Maven Wrapper JAR 文件是否存在
if [ ! -f "$WRAPPER_JAR" ]; then
  # 如果不存在，输出错误信息到标准错误流并退出
  echo "mvnw: wrapper jar not found: $WRAPPER_JAR" >&2
  exit 1
fi

# 默认使用系统 PATH 中的 java 命令
JAVA_BIN="java"
# 如果设置了 JAVA_HOME 环境变量，并且该路径下的 java 可执行文件存在
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  # 则使用 JAVA_HOME 下的 java 命令
  JAVA_BIN="$JAVA_HOME/bin/java"
fi

# 使用 Java 执行 Maven Wrapper 主类，传递所有命令行参数
# ${MAVEN_OPTS:-} 允许传递额外的 JVM 选项
# -Dmaven.multiModuleProjectDirectory 指定多模块项目的根目录
exec "$JAVA_BIN" ${MAVEN_OPTS:-} -Dmaven.multiModuleProjectDirectory="$BASE_DIR" -classpath "$WRAPPER_JAR" org.apache.maven.wrapper.MavenWrapperMain "$@"
