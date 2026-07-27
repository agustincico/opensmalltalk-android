#!/usr/bin/env bash
# shellcheck shell=bash
#
# Shared environment + config for the low-intervention dev loop.
# SOURCE this file from the other scripts:  source "$(dirname "$0")/env.sh"
#
# It auto-detects the Android SDK and the two JDKs this project needs, and
# exposes config (package, activity, AVD) + small helpers. Everything is
# overridable via environment variables so it stays reproducible on other
# machines / CI. Run it directly to print the resolved config:
#     ./scripts/loop/env.sh
#
# Toolchain (pinned by the repo — do NOT bump):
#   - JDK 11 for the Gradle build (AGP 4.2.2 breaks on newer)
#   - JDK 17+ for the Android cmdline-tools (sdkmanager/avdmanager; class 61.0)
#   - NDK 22.0.7026061, CMake 3.22.1, compileSdk 29, build-tools 30.0.3
#   - Emulator: arm64-v8a system image (libsqueak.so is arm64-only)

# --- repo root -------------------------------------------------------------
LOOP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" && pwd)"
REPO_ROOT="$(cd "$LOOP_DIR/../.." && pwd)"
export LOOP_DIR REPO_ROOT

# --- project config (override via env) ------------------------------------
export PKG="${PKG:-au.com.darkside.x11server}"
export ACTIVITY="${ACTIVITY:-.XServerActivity}"
export AVD_NAME="${AVD_NAME:-cuis-arm64}"
export AVD_DEVICE="${AVD_DEVICE:-pixel_5}"
export ABI="${ABI:-arm64-v8a}"
# API 30 google_apis arm64 is what we validated; google_apis (not google_play)
# so `adb root` works for push-image.
export SYS_IMAGE="${SYS_IMAGE:-system-images;android-30;google_apis;arm64-v8a}"
export API_TAG="${API_TAG:-google_apis}"
export API_LEVEL="${API_LEVEL:-30}"

# On-device paths
export FILES_DIR="/data/data/${PKG}/files"
export IMAGE_NAME="${IMAGE_NAME:-Cuis.image}"
export CHANGES_NAME="${CHANGES_NAME:-Cuis.changes}"

# Artifacts (screenshots / logs) — gitignored
export ARTIFACTS_DIR="${ARTIFACTS_DIR:-$REPO_ROOT/.loop}"
mkdir -p "$ARTIFACTS_DIR" 2>/dev/null || true

# --- Android SDK -----------------------------------------------------------
_first_existing_dir() { for d in "$@"; do [ -n "$d" ] && [ -d "$d" ] && { echo "$d"; return 0; }; done; return 1; }

if [ -z "${ANDROID_SDK_ROOT:-}" ]; then
  ANDROID_SDK_ROOT="$(_first_existing_dir \
      "${ANDROID_HOME:-}" \
      "$HOME/Library/Android/sdk" \
      "$HOME/Android/Sdk" \
      "/opt/homebrew/share/android-commandlinetools" || true)"
fi
export ANDROID_SDK_ROOT
export ANDROID_HOME="${ANDROID_HOME:-$ANDROID_SDK_ROOT}"

# --- JDKs ------------------------------------------------------------------
# JDK 11 for Gradle
if [ -z "${JAVA11_HOME:-}" ]; then
  if [ -x /usr/libexec/java_home ]; then JAVA11_HOME="$(/usr/libexec/java_home -v 11 2>/dev/null || true)"; fi
  [ -z "${JAVA11_HOME:-}" ] && JAVA11_HOME="$(_first_existing_dir \
      /Library/Java/JavaVirtualMachines/temurin-11.jdk/Contents/Home \
      /usr/lib/jvm/java-11-openjdk-amd64 \
      /usr/lib/jvm/java-11-openjdk || true)"
fi
export JAVA11_HOME

# JDK 17+ for cmdline-tools (sdkmanager / avdmanager)
if [ -z "${JAVA_CMDLINE_HOME:-}" ]; then
  if [ -x /usr/libexec/java_home ]; then
    JAVA_CMDLINE_HOME="$(/usr/libexec/java_home -v 17 2>/dev/null \
        || /usr/libexec/java_home -v 21 2>/dev/null \
        || /usr/libexec/java_home -v 25 2>/dev/null || true)"
  fi
  [ -z "${JAVA_CMDLINE_HOME:-}" ] && JAVA_CMDLINE_HOME="$JAVA11_HOME"  # last resort
fi
export JAVA_CMDLINE_HOME

# --- PATH ------------------------------------------------------------------
[ -n "$ANDROID_SDK_ROOT" ] && export PATH="\
$ANDROID_SDK_ROOT/platform-tools:\
$ANDROID_SDK_ROOT/emulator:\
$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:\
$PATH"

# Prefer the SDK's adb; fall back to whatever is on PATH.
ADB="$ANDROID_SDK_ROOT/platform-tools/adb"; [ -x "$ADB" ] || ADB="$(command -v adb || echo adb)"
EMULATOR="$ANDROID_SDK_ROOT/emulator/emulator"; [ -x "$EMULATOR" ] || EMULATOR="$(command -v emulator || echo emulator)"
export ADB EMULATOR

# If several devices are attached (e.g. Agustín's physical phone plus the
# emulator), default the whole loop to the emulator so it doesn't error with
# "more than one device". Override by exporting ANDROID_SERIAL yourself.
if [ -z "${ANDROID_SERIAL:-}" ]; then
  # `|| true`: with no emulator running the grep exits 1, which under a caller's
  # `set -euo pipefail` would abort the whole script before it can boot one.
  _emu="$("$ADB" devices 2>/dev/null | grep -E '^emulator-[0-9]+[[:space:]]+device' | head -1 | awk '{print $1}' || true)"
  [ -n "$_emu" ] && export ANDROID_SERIAL="$_emu"
fi

# --- helpers ---------------------------------------------------------------
# Run sdkmanager/avdmanager with the JDK they require. (Sourced into each
# script's shell, so no need to export it — and `export -f` misbehaves in zsh.)
sdk_java() { JAVA_HOME="$JAVA_CMDLINE_HOME" "$@"; }

# Is a device/emulator online?
loop_device_online() { "$ADB" get-state 2>/dev/null | grep -q '^device$'; }

# Ensure adbd runs as root (idempotent: no-op — and no scrcpy blip — if already root).
loop_adb_root() {
  local who; who="$("$ADB" shell id -u 2>/dev/null | tr -d '\r')"
  if [ "$who" = "0" ]; then return 0; fi
  "$ADB" root >/dev/null 2>&1 || true
  "$ADB" wait-for-device >/dev/null 2>&1 || true
}

loop_log() { printf '\033[1;36m[loop]\033[0m %s\n' "$*" >&2; }
loop_err() { printf '\033[1;31m[loop:err]\033[0m %s\n' "$*" >&2; }

# --- standalone: print config ---------------------------------------------
if [ "${BASH_SOURCE[0]:-}" = "${0:-}" ]; then
  cat <<EOF
REPO_ROOT         = $REPO_ROOT
ANDROID_SDK_ROOT  = $ANDROID_SDK_ROOT
JAVA11_HOME       = $JAVA11_HOME
JAVA_CMDLINE_HOME = $JAVA_CMDLINE_HOME
ADB               = $ADB
EMULATOR          = $EMULATOR
PKG / ACTIVITY    = $PKG / $ACTIVITY
AVD_NAME/DEVICE   = $AVD_NAME / $AVD_DEVICE
SYS_IMAGE         = $SYS_IMAGE
FILES_DIR         = $FILES_DIR
ARTIFACTS_DIR     = $ARTIFACTS_DIR
EOF
  # sanity
  [ -d "$ANDROID_SDK_ROOT" ] || loop_err "ANDROID_SDK_ROOT not found: $ANDROID_SDK_ROOT"
  [ -d "$JAVA11_HOME" ]      || loop_err "JDK 11 not found (set JAVA11_HOME)"
  [ -d "$JAVA_CMDLINE_HOME" ]|| loop_err "JDK 17+ not found (set JAVA_CMDLINE_HOME)"
fi
