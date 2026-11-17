#!/bin/sh
set -eu
umask 077

APP_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
cd "$APP_DIR"
PID_FILE="$APP_DIR/.local/api.pid"
LOCK_DIR="$APP_DIR/.local/api-control.lock"
mkdir -p "$APP_DIR/.local" "$APP_DIR/logs"
if ! mkdir "$LOCK_DIR" 2>/dev/null; then
    echo "다른 시작 또는 종료 작업이 진행 중입니다." >&2
    exit 1
fi
trap 'rmdir "$LOCK_DIR"' EXIT
trap 'exit 1' HUP INT TERM

if [ -f "$PID_FILE" ]; then
    read -r pid < "$PID_FILE"
    case "$pid" in
        ''|*[!0-9]*) echo "PID 파일을 확인해 주세요: $PID_FILE" >&2; exit 1 ;;
    esac
    if kill -0 "$pid" 2>/dev/null; then
        echo "기존 PID가 실행 중입니다. 먼저 bin/stop.sh를 실행해 주세요. PID=$pid" >&2
        exit 1
    fi
    rm -f "$PID_FILE"
fi

APP_JAR="$APP_DIR/e2u-chat-bot.jar"
if [ ! -f "$APP_JAR" ]; then
    echo "실행할 JAR가 없습니다: $APP_JAR" >&2
    exit 1
fi

JAVA=java
if [ -n "${JAVA_HOME:-}" ]; then JAVA="$JAVA_HOME/bin/java"; fi
if ! "$JAVA" -version >/dev/null 2>&1; then
    echo "Java 17 이상을 설치하고 JAVA_HOME 또는 PATH를 확인해 주세요." >&2
    exit 1
fi
LOG_CONFIG="$APP_DIR/bin/logback-spring.xml"
if [ ! -f "$LOG_CONFIG" ]; then
    echo "로그 설정 파일이 없습니다: $LOG_CONFIG" >&2
    exit 1
fi
LOG_FILE="$APP_DIR/logs/root.txt"
(
    trap '' HUP
    exec "$JAVA" "-Dlogging.config=$LOG_CONFIG" "-Dmychatbot.home=$APP_DIR" -jar "$APP_JAR"
) < /dev/null &
pid=$!
printf '%s\n' "$pid" > "$PID_FILE"
sleep 2
if ! kill -0 "$pid" 2>/dev/null; then
    rm -f "$PID_FILE"
    echo "서버 실행에 실패했습니다. 터미널 출력과 로그를 확인해 주세요: $LOG_FILE" >&2
    exit 1
fi
echo "서버 실행을 요청했습니다. PID=$pid"
echo "기동 결과는 로그에서 확인해 주세요: $LOG_FILE"
