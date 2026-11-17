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

if [ -f "$APP_DIR/app.jar" ]; then
    APP_JAR="$APP_DIR/app.jar"
else
    APP_JAR=
    for candidate in "$APP_DIR"/build/libs/*.jar; do
        [ -f "$candidate" ] || continue
        case "$candidate" in *-plain.jar) continue ;; esac
        if [ -n "$APP_JAR" ]; then
            echo "실행할 JAR가 여러 개입니다. 배포할 파일을 $APP_DIR/app.jar로 배치해 주세요." >&2
            exit 1
        fi
        APP_JAR="$candidate"
    done
    if [ -z "$APP_JAR" ]; then
        echo "app.jar를 배치하거나 services/api에서 bash gradlew bootJar를 실행해 주세요." >&2
        exit 1
    fi
fi

JAVA=java
if [ -n "${JAVA_HOME:-}" ]; then JAVA="$JAVA_HOME/bin/java"; fi
if ! "$JAVA" -version >/dev/null 2>&1; then
    echo "Java 17 이상을 설치하고 JAVA_HOME 또는 PATH를 확인해 주세요." >&2
    exit 1
fi
LOG_FILE="$APP_DIR/logs/api-$(date +%Y%m%d-%H%M%S)-$$.log"
nohup "$JAVA" "-Dmychatbot.home=$APP_DIR" -jar "$APP_JAR" > "$LOG_FILE" 2>&1 < /dev/null &
pid=$!
printf '%s\n' "$pid" > "$PID_FILE"
sleep 2
if ! kill -0 "$pid" 2>/dev/null; then
    rm -f "$PID_FILE"
    echo "서버 실행에 실패했습니다. 로그를 확인해 주세요: $LOG_FILE" >&2
    exit 1
fi
echo "서버 실행을 요청했습니다. PID=$pid"
echo "기동 결과는 로그에서 확인해 주세요: $LOG_FILE"
