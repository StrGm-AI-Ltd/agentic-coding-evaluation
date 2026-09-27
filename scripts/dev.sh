#!/usr/bin/env bash
# Runs ace-service and ace-ui-vaadin as independent launchd jobs (via `launchctl submit`)
# instead of `nohup ... & disown`. A backgrounded job launched from a non-interactive shell
# (job control off) inherits that shell's own process group rather than getting its own, so
# nohup+disown don't protect it from a signal sent to that whole group - launchd (PID 1) has
# no such dependency. See git history around 2026-09-27 for the incident this was written for.
#
# Usage:
#   scripts/dev.sh up             # start both (safe to re-run: restarts anything already running)
#   scripts/dev.sh down [service|ui]   # stop both, or just one
#   scripts/dev.sh status         # launchd's view of both jobs
#   scripts/dev.sh logs [service|ui]   # tail -f the log (default: service)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SERVICE_LABEL="com.ace.service"
UI_LABEL="com.ace.ui"

usage() {
    echo "Usage: $(basename "$0") <up|down|status|logs> [service|ui]" >&2
    exit 1
}

# launchctl's own -o/-e redirection into a file under ~/Documents fails silently (macOS's
# per-folder protection blocks launchd's file-open there, even though the job's OWN writes
# inside that folder work fine) - so logging is redirected inside the command itself instead.
submit() {
    local label="$1" logfile="$2" cmd="$3"
    launchctl remove "$label" >/dev/null 2>&1 || true
    : > "$logfile"
    launchctl submit -l "$label" -- /bin/bash -c "$cmd"
}

wait_for_port() {
    local port="$1" name="$2" logfile="$3"
    for _ in $(seq 1 30); do
        lsof -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1 && { echo "$name is up on :$port"; return 0; }
        sleep 2
    done
    echo "WARNING: $name did not come up on :$port within 60s - check $logfile" >&2
    return 1
}

up() {
    local key_env=""
    if [ -n "${OMLX_API_KEY:-}" ]; then
        key_env="OMLX_API_KEY=$OMLX_API_KEY"
    else
        echo "WARNING: OMLX_API_KEY is not set in this shell - ace-service will start without a" >&2
        echo "         model-server key (preflight/model calls will get a 401)." >&2
    fi

    echo "Starting $SERVICE_LABEL..."
    submit "$SERVICE_LABEL" "$ROOT/service.log" \
        "cd '$ROOT' && exec env $key_env ./gradlew :ace-service:bootRun >> '$ROOT/service.log' 2>&1"

    echo "Starting $UI_LABEL..."
    submit "$UI_LABEL" "$ROOT/ui.log" \
        "cd '$ROOT' && exec ./gradlew :ace-ui-vaadin:bootRun >> '$ROOT/ui.log' 2>&1"

    wait_for_port 8765 ace-service "$ROOT/service.log"
    wait_for_port 8800 ace-ui-vaadin "$ROOT/ui.log"
}

down() {
    local target="${1:-both}"
    case "$target" in
        both|service) echo "Stopping $SERVICE_LABEL..."; launchctl remove "$SERVICE_LABEL" 2>/dev/null || true ;;
    esac
    case "$target" in
        both|ui) echo "Stopping $UI_LABEL..."; launchctl remove "$UI_LABEL" 2>/dev/null || true ;;
    esac
}

status() { launchctl list | grep -E "com\.ace\.(service|ui)" || echo "Neither job is registered with launchd."; }

logs() {
    case "${1:-service}" in
        service) tail -f "$ROOT/service.log" ;;
        ui) tail -f "$ROOT/ui.log" ;;
        *) usage ;;
    esac
}

case "${1:-}" in
    up) up ;;
    down) down "${2:-both}" ;;
    status) status ;;
    logs) logs "${2:-service}" ;;
    *) usage ;;
esac
