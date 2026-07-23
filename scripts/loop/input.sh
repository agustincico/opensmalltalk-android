#!/usr/bin/env bash
#
# input.sh — drive the *real* touch/keyboard path (adb -> Android -> embedded
# X server -> Cuis), so we exercise what a finger would, not a shortcut.
#
#   ./scripts/loop/input.sh tap   X Y
#   ./scripts/loop/input.sh swipe X1 Y1 X2 Y2 [DURATION_MS]
#   ./scripts/loop/input.sh text  "some text"      # types into focused morph
#   ./scripts/loop/input.sh key   KEYCODE|NAME      # e.g. 66 or ENTER, BACK, ESCAPE, TAB
#   ./scripts/loop/input.sh longpress X Y [MS]      # helps hit small menu items
#   ./scripts/loop/input.sh back|home               # nav keys
#
# Coordinates are device pixels (this AVD: 1080x2340). A single tap is a
# button-1 press+release; on the Cuis desktop background that opens the World
# menu, which is a handy smoke test.
#
set -euo pipefail
source "$(dirname "$0")/env.sh"

loop_device_online || { loop_err "no device online"; exit 1; }
cmd="${1:-}"; shift || true

case "$cmd" in
  tap)
    [ $# -ge 2 ] || { loop_err "usage: tap X Y"; exit 2; }
    "$ADB" shell input tap "$1" "$2" ;;
  swipe)
    [ $# -ge 4 ] || { loop_err "usage: swipe X1 Y1 X2 Y2 [MS]"; exit 2; }
    "$ADB" shell input swipe "$1" "$2" "$3" "$4" "${5:-200}" ;;
  longpress)
    [ $# -ge 2 ] || { loop_err "usage: longpress X Y [MS]"; exit 2; }
    d="${3:-600}"; "$ADB" shell input swipe "$1" "$2" "$1" "$2" "$d" ;;
  text)
    [ $# -ge 1 ] || { loop_err "usage: text \"...\""; exit 2; }
    # adb input text wants %s for spaces; escape common shell-special chars
    t="$*"; t="${t// /%s}"
    "$ADB" shell input text "$t" ;;
  key|keyevent)
    [ $# -ge 1 ] || { loop_err "usage: key KEYCODE|NAME"; exit 2; }
    case "$1" in
      ENTER) k=66 ;; BACK) k=4 ;; HOME) k=3 ;; ESCAPE|ESC) k=111 ;;
      TAB) k=61 ;; DEL|BACKSPACE) k=67 ;; MENU) k=82 ;; *) k="$1" ;;
    esac
    "$ADB" shell input keyevent "$k" ;;
  back) "$ADB" shell input keyevent 4 ;;
  home) "$ADB" shell input keyevent 3 ;;
  ""|-h|--help)
    grep '^#' "$0" | sed 's/^# \{0,1\}//' ;;
  *) loop_err "unknown subcommand: $cmd"; exit 2 ;;
esac
