#!/usr/bin/env bash
#
# emulator.sh — ensure the arm64 AVD exists and is booted (headless), rooted,
# and ready. Idempotent: if it's already up, it just waits for boot + roots.
#
#   ./scripts/loop/emulator.sh            # headless boot (default)
#   ./scripts/loop/emulator.sh --window   # boot with the emulator window
#   ./scripts/loop/emulator.sh --wipe     # cold boot, wipe user data
#
set -euo pipefail
source "$(dirname "$0")/env.sh"

WINDOW_FLAG="-no-window"
EXTRA=()
for a in "$@"; do
  case "$a" in
    --window) WINDOW_FLAG="" ;;
    --wipe)   EXTRA+=("-wipe-data") ;;
    *) loop_err "unknown arg: $a"; exit 2 ;;
  esac
done

# 1) Create the AVD if missing --------------------------------------------
if ! sdk_java "$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/avdmanager" list avd 2>/dev/null \
      | grep -q "Name: ${AVD_NAME}$"; then
  loop_log "creating AVD '$AVD_NAME' ($AVD_DEVICE, $SYS_IMAGE)"
  # ensure the system image is installed
  if ! sdk_java "$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" \
        --sdk_root="$ANDROID_SDK_ROOT" --list_installed 2>/dev/null | grep -q "${SYS_IMAGE}"; then
    loop_log "installing system image $SYS_IMAGE"
    yes | sdk_java "$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" \
      --sdk_root="$ANDROID_SDK_ROOT" "$SYS_IMAGE" >/dev/null
  fi
  echo "no" | sdk_java "$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/avdmanager" \
    create avd -n "$AVD_NAME" -k "$SYS_IMAGE" -d "$AVD_DEVICE" --force >/dev/null
  # a hardware keyboard makes `input text` / typing into Smalltalk behave
  cfg="$HOME/.android/avd/${AVD_NAME}.avd/config.ini"
  if [ -f "$cfg" ] && grep -q '^hw.keyboard=no' "$cfg"; then
    tmp="$(mktemp)"; sed 's/^hw.keyboard=no/hw.keyboard=yes/' "$cfg" >"$tmp" && mv "$tmp" "$cfg"
  fi
fi

# 2) Boot it if no device is online ---------------------------------------
if ! loop_device_online; then
  loop_log "booting emulator '$AVD_NAME' (${WINDOW_FLAG:-windowed})"
  nohup "$EMULATOR" -avd "$AVD_NAME" $WINDOW_FLAG -no-audio -no-boot-anim \
      -no-snapshot -gpu swiftshader_indirect -netdelay none -netspeed full \
      ${EXTRA[@]+"${EXTRA[@]}"} >"$ARTIFACTS_DIR/emulator.log" 2>&1 &
  loop_log "emulator pid $! (log: $ARTIFACTS_DIR/emulator.log)"
else
  loop_log "a device is already online; reusing it"
fi

# 3) Wait for boot --------------------------------------------------------
loop_log "waiting for device…"
"$ADB" wait-for-device
loop_log "waiting for sys.boot_completed…"
for _ in $(seq 1 180); do
  [ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && break
  sleep 2
done
if [ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" != "1" ]; then
  loop_err "boot did not complete in time"; exit 1
fi

# 4) Root (persistent, so push-image won't restart adbd / blink scrcpy) ----
loop_adb_root

abi="$("$ADB" shell getprop ro.product.cpu.abi | tr -d '\r')"
sdk="$("$ADB" shell getprop ro.build.version.sdk | tr -d '\r')"
loop_log "ready: $("$ADB" get-serialno) abi=$abi sdk=$sdk root=$([ "$("$ADB" shell id -u|tr -d '\r')" = 0 ] && echo yes || echo no)"
[ "$abi" = "arm64-v8a" ] || loop_err "WARNING: device abi is '$abi', but libsqueak.so is arm64-only"
