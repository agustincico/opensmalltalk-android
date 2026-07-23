#!/usr/bin/env bash
#
# observe.sh — capture the two channels I read to verify each iteration:
#   1. screen  -> PNG   (adb screencap)
#   2. logs    -> text  (logcat filtered to Cuis / SQUEAK / SQUEAK_VM)
#
#   ./scripts/loop/observe.sh                 # writes .loop/screen.png + .loop/logcat.txt (latest)
#   ./scripts/loop/observe.sh --stamp         # also keep a timestamped copy under .loop/history/
#   ./scripts/loop/observe.sh --lines 400     # how many recent logcat lines (default 300)
#   ./scripts/loop/observe.sh --clear         # clear the log buffer first (fresh capture)
#   ./scripts/loop/observe.sh --tag FOO       # extra logcat tag to include (repeatable)
#
# Prints a one-line health summary (VM argv seen? image opened? crash/errors?).
#
set -euo pipefail
source "$(dirname "$0")/env.sh"

STAMP=0; LINES=300; CLEAR=0; TAGS=(Cuis SQUEAK SQUEAK_VM)
while [ $# -gt 0 ]; do case "$1" in
  --stamp) STAMP=1 ;;
  --clear) CLEAR=1 ;;
  --lines) LINES="$2"; shift ;;
  --tag)   TAGS+=("$2"); shift ;;
  *) loop_err "unknown arg: $1"; exit 2 ;;
esac; shift; done

loop_device_online || { loop_err "no device online"; exit 1; }

PNG="$ARTIFACTS_DIR/screen.png"
LOG="$ARTIFACTS_DIR/logcat.txt"

[ "$CLEAR" = 1 ] && "$ADB" logcat -c 2>/dev/null || true

# 1) screen
"$ADB" exec-out screencap -p > "$PNG"

# 2) logs (filtered). -d = dump & exit. Build "-s TAG ..." spec.
spec=(); for t in "${TAGS[@]}"; do spec+=("$t"); done
"$ADB" logcat -d -t "$LINES" -s "${spec[@]}" > "$LOG" 2>&1 || true

if [ "$STAMP" = 1 ]; then
  mkdir -p "$ARTIFACTS_DIR/history"
  ts="$(date +%Y%m%d-%H%M%S)"
  cp "$PNG" "$ARTIFACTS_DIR/history/screen-$ts.png"
  cp "$LOG" "$ARTIFACTS_DIR/history/logcat-$ts.txt"
fi

# health summary
argv_ok=$(grep -qE 'argv\[5\]:.*\.image' "$LOG" && echo yes || echo no)
img_fail=$(grep -qiE 'could not open the squeak image' "$LOG" && echo YES || echo no)
crash=$(grep -qiE 'beginning of crash|FATAL EXCEPTION|SIGSEGV|signal 11' "$LOG" && echo YES || echo no)
pid="$("$ADB" shell pidof "$PKG" 2>/dev/null | tr -d '\r' || true)"
sz=$(wc -c < "$PNG" | tr -d ' ')

loop_log "screen -> $PNG (${sz} bytes)   logs -> $LOG (${LINES} lines)"
loop_log "health: app_pid=${pid:-none} vm_argv=$argv_ok image_open_fail=$img_fail crash=$crash"
# dev-tests hook results, if any (see push-image.sh / dev image)
if grep -q 'DEVTEST' "$LOG"; then
  loop_log "dev-tests:"; grep 'DEVTEST' "$LOG" | sed 's/.*DEVTEST/  DEVTEST/'
fi
