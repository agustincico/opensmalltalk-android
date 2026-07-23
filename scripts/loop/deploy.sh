#!/usr/bin/env bash
#
# deploy.sh — install the debug APK, (re)launch the activity, and wait until
# the Smalltalk VM has actually opened the image (not just the process start).
#
#   ./scripts/loop/deploy.sh              # install -r + relaunch + wait
#   ./scripts/loop/deploy.sh --fresh      # uninstall first (clears filesDir → re-extracts assets)
#   ./scripts/loop/deploy.sh --no-install # just relaunch what's installed
#
# Note: on a fresh install the ~22MB image+changes are extracted on a background
# thread while the VM launches ~500ms later — a race that can miss the image.
# We defeat it deterministically: wait for the extracted image to reach full
# size, then relaunch once so the VM boots against a complete file.
#
set -euo pipefail
source "$(dirname "$0")/env.sh"

FRESH=0; INSTALL=1
for a in "$@"; do case "$a" in
  --fresh) FRESH=1 ;;
  --no-install) INSTALL=0 ;;
  *) loop_err "unknown arg: $a"; exit 2 ;;
esac; done

loop_device_online || { loop_err "no device online — run emulator.sh first"; exit 1; }
APK="$REPO_ROOT/app/build/outputs/apk/debug/app-debug.apk"

if [ "$INSTALL" = 1 ]; then
  [ -f "$APK" ] || { loop_err "APK not found ($APK) — run build.sh first"; exit 1; }
  if [ "$FRESH" = 1 ]; then loop_log "uninstalling (fresh)"; "$ADB" uninstall "$PKG" >/dev/null 2>&1 || true; fi
  loop_log "installing APK"
  "$ADB" install -r "$APK" >/dev/null
fi

launch() { "$ADB" shell am start -n "$PKG/$ACTIVITY" >/dev/null 2>&1; }
stop()   { "$ADB" shell am force-stop "$PKG" >/dev/null 2>&1 || true; }

# Expected asset sizes (0 = unknown → just check the file is non-empty & stable)
img_asset="$REPO_ROOT/app/src/main/assets/$IMAGE_NAME"
want_img=0; [ -f "$img_asset" ] && want_img="$(wc -c < "$img_asset" | tr -d ' ')"

# Never let a missing file / failing stat abort the script (set -e + pipefail):
dev_size() { "$ADB" shell run-as "$PKG" stat -c %s "files/$1" 2>/dev/null | tr -d '\r' || true; }

loop_log "launch #1 (triggers asset extraction on fresh installs)"
stop; launch

loop_log "waiting for $IMAGE_NAME in filesDir to be complete…"
ok=0
for _ in $(seq 1 90); do
  s="$(dev_size "$IMAGE_NAME")"; s="${s:-0}"
  if [ "$want_img" != 0 ]; then
    [ "$s" = "$want_img" ] && { ok=1; break; }
  else
    # size unknown (image pushed, not embedded): accept once it's stable & >1MB
    sleep 1; s2="$(dev_size "$IMAGE_NAME")"; s2="${s2:-0}"
    [ "$s" -gt 1000000 ] && [ "$s" = "$s2" ] && { ok=1; break; }
  fi
  sleep 1
done
[ "$ok" = 1 ] || loop_err "image not present/complete in filesDir (size=$(dev_size "$IMAGE_NAME"))"

loop_log "relaunch #2 (VM boots against the complete image)"
stop; launch

# wait for the VM thread to report the image opened (or fail)
loop_log "waiting for VM to open the image…"
opened=0
for _ in $(seq 1 30); do
  if "$ADB" logcat -d -s SQUEAK_VM 2>/dev/null | grep -qiE 'could not open the squeak image'; then
    loop_err "VM could not open the image — check logcat"; break
  fi
  if "$ADB" logcat -d -s SQUEAK 2>/dev/null | grep -q 'g_squeak_main'; then opened=1; fi
  pid="$("$ADB" shell pidof "$PKG" 2>/dev/null | tr -d '\r' || true)"
  [ "$opened" = 1 ] && [ -n "$pid" ] && break
  sleep 1
done
pid="$("$ADB" shell pidof "$PKG" 2>/dev/null | tr -d '\r')"
if [ -n "$pid" ]; then loop_log "app running (pid $pid)"; else loop_err "app not running after launch"; fi
