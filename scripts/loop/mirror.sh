#!/usr/bin/env bash
#
# mirror.sh — keep a live scrcpy window open on the Mac, always mirroring the
# latest deployed build. scrcpy mirrors the whole device screen, so it survives
# app relaunches and reinstalls; it only drops if the emulator or adbd restart.
# This script is idempotent: it starts scrcpy only if it isn't already up.
#
#   ./scripts/loop/mirror.sh            # ensure it's running (start if needed)
#   ./scripts/loop/mirror.sh --restart  # kill & relaunch
#   ./scripts/loop/mirror.sh --status   # report only
#
set -euo pipefail
source "$(dirname "$0")/env.sh"

SERIAL="$("$ADB" get-serialno 2>/dev/null | tr -d '\r' || true)"
TITLE="Cuis on Android (${AVD_NAME})"
running() { pgrep -f "scrcpy .*--window-title $AVD_NAME" >/dev/null 2>&1 || pgrep -f "scrcpy" >/dev/null 2>&1; }

case "${1:-}" in
  --status)
    if running; then loop_log "scrcpy: running (pid $(pgrep -f scrcpy | head -1))"; else loop_log "scrcpy: not running"; fi
    exit 0 ;;
  --restart) pkill -f scrcpy 2>/dev/null || true; sleep 1 ;;
  "" ) : ;;
  *) loop_err "unknown arg: $1"; exit 2 ;;
esac

if ! command -v scrcpy >/dev/null 2>&1; then
  loop_err "scrcpy not installed (brew install scrcpy). Skipping live mirror."
  exit 0
fi

if running; then
  loop_log "scrcpy already running — leaving it (mirrors latest build automatically)"
  exit 0
fi

loop_device_online || { loop_err "no device online — run emulator.sh first"; exit 1; }
loop_log "starting scrcpy live mirror: $TITLE"
nohup scrcpy ${SERIAL:+-s "$SERIAL"} \
  --window-title "$AVD_NAME" \
  --render-driver=software --disable-screensaver --stay-awake \
  >"$ARTIFACTS_DIR/scrcpy.log" 2>&1 &
sleep 2
if running; then loop_log "scrcpy up (pid $(pgrep -f scrcpy | head -1)); log: $ARTIFACTS_DIR/scrcpy.log"
else loop_err "scrcpy failed to start — see $ARTIFACTS_DIR/scrcpy.log"; fi
