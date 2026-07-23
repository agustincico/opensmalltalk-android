#!/usr/bin/env bash
#
# build.sh — assemble the debug APK with the pinned JDK 11.
# Gradle is already incremental (no-op ~2s when nothing changed), so this is
# safe to call every iteration. Only needed when Java / X11 / C (cpp) sources
# or the embedded assets change; pure Smalltalk iteration uses push-image.sh.
#
#   ./scripts/loop/build.sh            # assembleDebug
#   ./scripts/loop/build.sh --release  # assembleRelease
#   ./scripts/loop/build.sh clean assembleDebug   # pass raw gradle tasks
#
set -euo pipefail
source "$(dirname "$0")/env.sh"

[ -d "$JAVA11_HOME" ] || { loop_err "JDK 11 not found; set JAVA11_HOME"; exit 1; }

# local.properties (sdk.dir) — create if missing
if [ ! -f "$REPO_ROOT/local.properties" ]; then
  loop_log "writing local.properties (sdk.dir=$ANDROID_SDK_ROOT)"
  echo "sdk.dir=$ANDROID_SDK_ROOT" > "$REPO_ROOT/local.properties"
fi

TASKS=("assembleDebug")
case "${1:-}" in
  --release) TASKS=("assembleRelease") ;;
  "" ) : ;;
  * ) TASKS=("$@") ;;   # raw gradle tasks
esac

loop_log "gradle ${TASKS[*]} (JDK 11)"
cd "$REPO_ROOT"
JAVA_HOME="$JAVA11_HOME" ./gradlew "${TASKS[@]}"

APK="$REPO_ROOT/app/build/outputs/apk/debug/app-debug.apk"
[ -f "$APK" ] && loop_log "APK: $APK ($(du -h "$APK" | cut -f1))"
