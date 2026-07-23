#!/usr/bin/env bash
#
# push-image.sh — iterate the *Smalltalk* side without rebuilding the APK:
# push a new .image (and optionally .changes and/or a dev-tests .st) straight
# into the app's filesDir, fix owner + SELinux label so the app can read it,
# and relaunch.
#
#   ./scripts/loop/push-image.sh path/to/Some.image
#   ./scripts/loop/push-image.sh Foo.image --changes Foo.changes
#   ./scripts/loop/push-image.sh Foo.image --st scripts/loop/dev-tests.st
#   ./scripts/loop/push-image.sh Foo.image --no-relaunch
#
# The bundled VM is Spur *64-bit*, so the image MUST be 64-bit Spur (format
# magic 68021). A 32-bit image (6521) silently fails to boot — we warn.
#
set -euo pipefail
source "$(dirname "$0")/env.sh"

IMG=""; CHANGES=""; ST=""; RELAUNCH=1; DEST_IMG="$IMAGE_NAME"; DEST_ST="dev-tests.st"
while [ $# -gt 0 ]; do case "$1" in
  --changes) CHANGES="$2"; shift ;;
  --st)      ST="$2"; shift ;;
  --as)      DEST_IMG="$2"; shift ;;
  --no-relaunch) RELAUNCH=0 ;;
  --*) loop_err "unknown flag: $1"; exit 2 ;;
  *) IMG="$1" ;;
esac; shift; done

[ -n "$IMG" ] || { loop_err "usage: push-image.sh IMAGE [--changes F] [--st F] [--no-relaunch]"; exit 2; }
[ -f "$IMG" ] || { loop_err "image not found: $IMG"; exit 1; }
loop_device_online || { loop_err "no device online — run emulator.sh first"; exit 1; }

# --- sanity: 64-bit Spur? -------------------------------------------------
fmt="$(od -An -tu4 -N4 "$IMG" 2>/dev/null | tr -d ' ')"
case "$fmt" in
  68021) loop_log "image format 68021 (Spur 64-bit) ✓" ;;
  6521)  loop_err "image format 6521 = Spur 32-bit — the bundled VM is 64-bit; this will NOT boot" ;;
  *)     loop_log "image format $fmt (unrecognized; expected 68021 for Spur 64-bit)" ;;
esac

loop_adb_root
uidname="$("$ADB" shell stat -c %U "$FILES_DIR" 2>/dev/null | tr -d '\r' || true)"
[ -n "$uidname" ] || { loop_err "cannot stat $FILES_DIR — is the app installed?"; exit 1; }

push_one() {  # src destname
  local src="$1" dest="$2" tmp="/data/local/tmp/.loop_push_$$"
  loop_log "push $(basename "$src") -> $FILES_DIR/$dest"
  "$ADB" push "$src" "$tmp" >/dev/null
  "$ADB" shell "cp '$tmp' '$FILES_DIR/$dest' && chown $uidname:$uidname '$FILES_DIR/$dest' && chmod 600 '$FILES_DIR/$dest' && restorecon '$FILES_DIR/$dest' 2>/dev/null; rm -f '$tmp'"
}

"$ADB" shell am force-stop "$PKG" >/dev/null 2>&1 || true
push_one "$IMG" "$DEST_IMG"
[ -n "$CHANGES" ] && { [ -f "$CHANGES" ] || { loop_err "changes not found: $CHANGES"; exit 1; }; push_one "$CHANGES" "$CHANGES_NAME"; }
[ -n "$ST" ]      && { [ -f "$ST" ]      || { loop_err ".st not found: $ST"; exit 1; };      push_one "$ST" "$DEST_ST"; }

# verify sizes landed
loop_log "on-device: $("$ADB" shell run-as "$PKG" ls -l "files/$DEST_IMG" 2>/dev/null | tr -d '\r' || "$ADB" shell ls -l "$FILES_DIR/$DEST_IMG" | tr -d '\r')"

if [ "$RELAUNCH" = 1 ]; then
  loop_log "relaunching $PKG"
  "$ADB" shell am start -n "$PKG/$ACTIVITY" >/dev/null 2>&1
fi
