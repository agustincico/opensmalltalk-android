#!/bin/bash
# Android/Termux compilation fixes for OpenSmalltalk VM Stack
# Platform: Termux, Android ARMv8 (aarch64)
# Date: November 2025

set -e

echo "=== Applying Android/Termux fixes for OpenSmalltalk VM Stack ==="

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT"

# Fix 1: sqPluginsSCCSVersion.h
echo "Fix 1: Creating sqPluginsSCCSVersion.h..."
cat > platforms/Cross/plugins/sqPluginsSCCSVersion.h << 'EOF'
static char GitRawPluginsRevisionString[] = "$Rev: 202511130000 $";
static char GitRawPluginsRepositoryURL[] = "$URL: https://github.com/OpenSmalltalk/opensmalltalk-vm $";
static char *pluginsRevisionAsString() { return GitRawPluginsRevisionString + 6; }
static char *pluginsRepositoryURL() { return GitRawPluginsRepositoryURL + 6; }
EOF

# Fix 2: sqSCCSVersion.h
echo "Fix 2: Creating sqSCCSVersion.h..."
cat > platforms/Cross/vm/sqSCCSVersion.h << 'EOF'
static char *sourceVersion = 0;
static char GitRawRevisionString[] = "$Rev: 202511130000 $";
static char GitRawRepositoryURL[] = "$URL: https://github.com/OpenSmalltalk/opensmalltalk-vm $";
static char GitRawRevisionDate[] = "$Date: 2025-11-13 00:00:00 -0300 $";
static char GitRawShortHash[] = "abcd123";

static char *revisionAsString() { return GitRawRevisionString + 6; }
static char *repositoryURL() { return GitRawRepositoryURL + 6; }
static char *revisionDateAsString() { return GitRawRevisionDate + 7; }
static char *revisionShortHash() { return GitRawShortHash; }
static char *sourceVersionString(char separator) { return "VM: 202511130000"; }

#if VERSION_PROGRAM
#include <string.h>
int main(int argc, char **argv) {
    if (argc == 2) {
        if (strcmp(argv[1], "VERSION_TAG") == 0) {
            printf("5.0-202511130000-64bit\n");
        } else if (strcmp(argv[1], "VM_VERSION") == 0) {
            printf("5.0\n");
        } else {
            printf("%s\n", revisionAsString());
        }
    }
    return 0;
}
#endif
EOF

# Fix 3: arm64abicc.c
echo "Fix 3: Fixing valloc in arm64abicc.c..."
sed -i 's/if (!(mem = valloc(pagesize)))/if (posix_memalign(\&mem, pagesize, pagesize) != 0)/' \
  platforms/Cross/plugins/IA32ABI/arm64abicc.c

# Fix 4: openssl_overlay.h
echo "Fix 4: Fixing strverscmp in openssl_overlay.h..."
sed -i '549s/strverscmp/strcmp/' \
  platforms/unix/plugins/SqueakSSL/openssl_overlay.h

# Fix 5: UnixOSProcessPlugin.c
echo "Fix 5: Fixing POSIX functions in UnixOSProcessPlugin.c..."
sed -i '764s/getdtablesize()/sysconf(_SC_OPEN_MAX)/' \
  src/plugins/UnixOSProcessPlugin/UnixOSProcessPlugin.c
sed -i '1437s/bufferSize = confstr(optionIndex, 0, 0);/bufferSize = 0;/' \
  src/plugins/UnixOSProcessPlugin/UnixOSProcessPlugin.c
sed -i '1443s/result = confstr(optionIndex, buffer, bufferSize);/result = 0;/' \
  src/plugins/UnixOSProcessPlugin/UnixOSProcessPlugin.c

# Fix 6: sqUnixUUID.c
echo "Fix 6: Fixing UUID header in sqUnixUUID.c..."
sed -i '10i #elif defined(__ANDROID__) || defined(__TERMUX__)\n# include <uuid/uuid.h>' \
  platforms/unix/plugins/UUIDPlugin/sqUnixUUID.c

# Fix 7: sqUnixMain.c
echo "Fix 7: Fixing include path in sqUnixMain.c..."
sed -i '8s|#include "sqSCCSVersion.h"|#include "../../Cross/vm/sqSCCSVersion.h"|' \
  platforms/unix/vm/sqUnixMain.c

# Fix 8: BitBltArm64.c
echo "Fix 8: Disabling BitBltArm64.c (assembly issues with Termux)..."
if [ -f platforms/Cross/plugins/BitBltPlugin/BitBltArm64.c ]; then
  mv platforms/Cross/plugins/BitBltPlugin/BitBltArm64.c \
     platforms/Cross/plugins/BitBltPlugin/BitBltArm64.c.disabled
fi

echo ""
echo "=== All Stack VM fixes applied successfully! ==="
echo ""
echo "Next steps:"
echo "1. cd building/linux64ARMv8/squeak.stack.spur/build"
echo "2. Copy plugins: cp ../plugins.int . && cp ../plugins.ext ."
echo "3. bash ../../../../platforms/unix/config/configure --with-src=src/spur64.stack --disable-cogit CC=clang"
echo "4. sed -i 's/-luuid -lz -lpthread -lm/-luuid -lz -lpthread -lm -liconv/g' Makefile"
echo "5. make"
