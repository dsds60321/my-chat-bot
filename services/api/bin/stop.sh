#!/bin/sh
set -eu
umask 077

APP_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
PID_FILE="$APP_DIR/.local/api.pid"
LOCK_DIR="$APP_DIR/.local/api-control.lock"
mkdir -p "$APP_DIR/.local"
if ! mkdir "$LOCK_DIR" 2>/dev/null; then
    echo "다른 시작 또는 종료 작업이 진행 중입니다." >&2
    exit 1
fi
trap 'rmdir "$LOCK_DIR"' EXIT
trap 'exit 1' HUP INT TERM

if [ ! -f "$PID_FILE" ]; then
    echo "이 스크립트로 실행한 서버의 PID 파일이 없습니다."
    exit 0
fi
read -r pid < "$PID_FILE"
case "$pid" in
    ''|*[!0-9]*) echo "PID 파일을 확인해 주세요: $PID_FILE" >&2; exit 1 ;;
esac
if ! kill -0 "$pid" 2>/dev/null; then
    rm -f "$PID_FILE"
    echo "서버가 이미 종료되어 PID 파일을 정리했습니다."
    exit 0
fi
command_line=$(ps -p "$pid" -o args=) || {
    echo "프로세스를 확인할 수 없습니다. PID 파일을 유지합니다." >&2
    exit 1
}
case "$command_line" in
    *"-Dmychatbot.home=$APP_DIR -jar "*) ;;
    *) echo "PID가 이 서버의 실행 프로세스와 일치하지 않습니다. PID=$pid" >&2; exit 1 ;;
esac
kill -TERM "$pid"
elapsed=0
while kill -0 "$pid" 2>/dev/null; do
    state=$(ps -p "$pid" -o stat= 2>/dev/null || true)
    case "$state" in ''|*Z*) break ;; esac
    if [ "$elapsed" -ge 30 ]; then
        echo "30초 안에 종료되지 않았습니다. 로그를 확인해 주세요. PID=$pid" >&2
        exit 1
    fi
    sleep 1
    elapsed=$((elapsed + 1))
done
rm -f "$PID_FILE"
echo "서버를 종료했습니다. PID=$pid"
