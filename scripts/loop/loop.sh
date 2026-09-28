#!/usr/bin/env bash
#
# loop.sh — one command for the whole low-intervention cycle.
#
#   ./scripts/loop/loop.sh                 # build → emulator → deploy → mirror → observe
#   ./scripts/loop/loop.sh --no-build      # reuse existing APK
#   ./scripts/loop/loop.sh --fresh         # clean install (re-extract assets)
#   ./scripts/loop/loop.sh --image F.image # Smalltalk-only: push image, no APK rebuild
#         [--changes F.changes] [--st scripts/loop/dev-tests.st]
#   ./scripts/loop/loop.sh --observe-only  # just refresh screen.png + logcat.txt
#   ./scripts/loop/loop.sh --window        # first emulator boot shows a window
#
# Artifacts land in .loop/ (screen.png, logcat.txt). scrcpy stays open as the
# live monitor and auto-shows whatever was just deployed.
#
set -euo pipefail
HERE="$(dirname "$0")"
source "$HERE/env.sh"

MODE="apk"; NO_BUILD=0; FRESH=0; WINDOW=0; IMG=""; CHANGES=""; SOURCES=""; ST=""
while [ $# -gt 0 ]; do case "$1" in
  --no-build)     NO_BUILD=1 ;;
  --fresh)        FRESH=1 ;;
  --window)       WINDOW=1 ;;
  --observe-only) MODE="observe" ;;
  --image)        MODE="image"; IMG="$2"; shift ;;
  --changes)      CHANGES="$2"; shift ;;
  --sources)      SOURCES="$2"; shift ;;
  --st)           ST="$2"; shift ;;
  *) loop_err "unknown arg: $1"; exit 2 ;;
esac; shift; done

# 1) emulator (idempotent) — unless we're only observing an already-up device
if [ "$MODE" = "observe" ] && loop_device_online; then :; else
  args=(); [ "$WINDOW" = 1 ] && args+=(--window)
  bash "$HERE/emulator.sh" ${args[@]+"${args[@]}"}
fi

# 2) build / deploy / push depending on mode
case "$MODE" in
  apk)
    [ "$NO_BUILD" = 1 ] || bash "$HERE/build.sh"
    dargs=(); [ "$FRESH" = 1 ] && dargs+=(--fresh)
    bash "$HERE/deploy.sh" ${dargs[@]+"${dargs[@]}"}
    ;;
  image)
    pargs=("$IMG"); [ -n "$CHANGES" ] && pargs+=(--changes "$CHANGES")
    [ -n "$SOURCES" ] && pargs+=(--sources "$SOURCES"); [ -n "$ST" ] && pargs+=(--st "$ST")
    bash "$HERE/push-image.sh" "${pargs[@]}"
    ;;
  observe) : ;;
esac

# 3) live mirror (idempotent) + a short settle for the world to paint
bash "$HERE/mirror.sh" || true
sleep 3

# 4) observe — capture the two channels + print health
bash "$HERE/observe.sh" --stamp

loop_log "done. screen: $ARTIFACTS_DIR/screen.png  logs: $ARTIFACTS_DIR/logcat.txt"
