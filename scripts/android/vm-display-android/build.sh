#!/usr/bin/env bash
#
# build.sh -- build vm-display-android.so, the X11-free display driver.
#
# It is an ordinary Unix display module, so it needs no VM rebuild: it resolves
# aioPoll/primitiveFail/... from the libsqueak.so the APK already ships. That is
# why this compiles in a second instead of the ten minutes a VM build takes.
#
#   OSVM=<upstream checkout at the pinned commit> ./build.sh
#
# Output: vm-display-android.so, ready to drop into app/src/main/assets/plugins/.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
OSVM="${OSVM:-$HOME/opensmalltalk-vm}"
NDK="${NDK:-$HOME/Library/Android/sdk/ndk/26.2.11394342}"
API="${API:-28}"
TC="$NDK/toolchains/llvm/prebuilt/$(uname -s | tr 'A-Z' 'a-z')-x86_64/bin"
CC="$TC/aarch64-linux-android$API-clang"
OUT="${OUT:-$HERE/vm-display-android.so}"
B="$(mktemp -d)"; trap 'rm -rf "$B"' EXIT

# The module needs only these few of the ~450 defines configure produces; the
# rest of config.h describes libraries it does not use.
cat > "$B/config.h" <<'CFG'
#define SIZEOF_INT 4
#define SIZEOF_LONG 8
#define SIZEOF_LONG_LONG 8
#define SIZEOF_VOID_P 8
#define HAVE_UNISTD_H 1
#define HAVE_SYS_TIME_H 1
#define HAVE_TIME_H 1
CFG

"$CC" -c -o "$B/display.o" "$HERE/sqUnixAndroidDisplay.c" \
  -DLSB_FIRST=1 -O2 -fPIC -Wall \
  -I"$B" -I"$OSVM/src/spur64.cog" -I"$OSVM/platforms/Cross/vm" \
  -I"$OSVM/platforms/unix/vm" -I"$OSVM/platforms/Cross/plugins/B3DAcceleratorPlugin"

"$CC" -shared -o "$OUT" "$B/display.o" -landroid -llog \
  -Wl,--version-script="$HERE/exports.map" \
  -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384

# (No -fvisibility=hidden: a version script cannot re-export what the compiler
# already marked hidden, and the script alone does the sealing.)

# Plugins resolve VM symbols from the executable's dynamic table on a normal
# Unix; here the "executable" is itself dlopen()ed, so name it explicitly.
patchelf --add-needed libsqueak.so "$OUT"

echo "built $OUT"
"$TC/llvm-readelf" -d "$OUT" | grep NEEDED
