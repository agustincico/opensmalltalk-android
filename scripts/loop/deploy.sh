#!/usr/bin/env bash
#
# deploy.sh — install the debug APK and (re)launch the activity.
#
#   ./scripts/loop/deploy.sh              # install -r + relaunch
#   ./scripts/loop/deploy.sh --fresh      # uninstall first (clears filesDir)
#   ./scripts/loop/deploy.sh --no-install # just relaunch what's installed
#
# The image/changes are no longer auto-extracted from the APK: on first boot the
# app shows the "Load image" chooser (download / pick from device / bundled Cuis),
# and only then copies an image into filesDir and restarts to boot it. So there
# is nothing to wait for here — we just install, launch, and confirm it's up.
# (The old asset-extraction race is gone with the auto-extract.)
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

loop_log "launch"
stop; launch

# Confirm the process is up. If an image is already present (marker set), the VM
# boots it; on a fresh install the chooser is shown instead — either way the
# process should be running within a few seconds.
loop_log "waiting for the app process…"
pid=""
for _ in $(seq 1 20); do
  pid="$("$ADB" shell pidof "$PKG" 2>/dev/null | tr -d '\r' || true)"
  [ -n "$pid" ] && break
  sleep 1
done
if [ -n "$pid" ]; then loop_log "app running (pid $pid)"; else loop_err "app not running after launch"; fi
