#!/usr/bin/env bash
#
# build-vm-android.sh -- cross-compile the OpenSmalltalk Stack/Spur 64-bit VM
# and its plugins for Android/arm64, from a pinned upstream commit, using the
# Android NDK on a desktop machine.
#
# This replaces the old scripts/apply-fixes-stack.sh, which patched by absolute
# line number and could only be run on the phone itself under Termux.
#
# What it produces (in $WORK/osvm/building/linux64ARMv8/squeak.stack.spur/build):
#   squeak          the VM -- install as app/src/main/jniLibs/arm64-v8a/libsqueak.so
#   */*.so          the plugins -- install into app/src/main/assets/plugins/
#
# Requirements: macOS or Linux, Android NDK 26, git, curl, bsdtar, pkg-config.
#
set -euo pipefail

# Resolve our own directory up front: the script cd's into the VM tree later, so
# anything relative to $0 must be pinned to an absolute path before that happens.
HERE="$(cd "$(dirname "$0")" && pwd)"

WORK="${WORK:-$HOME/osvm-android}"
NDK_VER="${NDK_VER:-26.2.11394342}"
SDK="${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}"
NDK="${NDK:-$SDK/ndk/$NDK_VER}"
# API 28: nl_langinfo needs 26 and glob() needs 28 in Bionic. Anything lower
# needs Termux's libandroid-support/libandroid-glob shims instead.
API="${API:-28}"
# Pinned upstream commit on branch Cog.
COMMIT="${COMMIT:-a4d3da0ac21d4b95dcc3eb77f7a3c1e24aab003c}"

SR="$WORK/sysroot"
O="$WORK/osvm"
B="$O/building/linux64ARMv8/squeak.stack.spur/build"
HOSTTAG="$(uname -s | tr 'A-Z' 'a-z')-x86_64"   # darwin-x86_64 also runs on Apple silicon
TC="$NDK/toolchains/llvm/prebuilt/$HOSTTAG/bin"
TERMUX=https://packages.termux.dev/apt/termux-main

[ -x "$TC/aarch64-linux-android$API-clang" ] || {
	echo "No NDK clang at $TC (set NDK=... or NDK_VER=...)" >&2; exit 1; }

mkdir -p "$WORK"

# ---------------------------------------------------------------- 1. sysroot
# Bionic ships no X11, zlib headers, iconv, uuid or desktop GL. Take them from
# Termux's aarch64 packages -- the same provenance as the libraries the APK
# already bundles, so headers and shipped .so files agree.
if [ ! -f "$SR/usr/include/X11/Xlib.h" ]; then
	echo "==> building Android sysroot from Termux packages"
	mkdir -p "$WORK/debs" "$SR"
	curl -sSL "$TERMUX/dists/stable/main/binary-aarch64/Packages" -o "$WORK/debs/Packages"
	for p in zlib libiconv libuuid libx11 xorgproto libxext libxrender libxrandr \
	         libxcb libxau libxdmcp libxft libxi libxcursor libxfixes \
	         libglvnd libglvnd-dev mesa-dev \
	         glib pango libcairo libpixman harfbuzz freetype fontconfig \
	         libpng pcre2 libexpat fribidi libgraphite brotli libffi; do
		fn=$(awk -v P="$p" '$1=="Package:"&&$2==P{f=1} f&&$1=="Filename:"{print $2; exit}' \
		     "$WORK/debs/Packages")
		[ -n "$fn" ] || { echo "missing Termux package: $p" >&2; exit 1; }
		curl -sSL "$TERMUX/$fn" -o "$WORK/debs/$(basename "$fn")"
	done
	tmp="$WORK/debs/unpack"; rm -rf "$tmp"; mkdir -p "$tmp"
	( cd "$tmp"
	  for d in ../*.deb; do
		ar x "$d" && tar xf data.tar.* && rm -f data.tar.* control.tar.* debian-binary
	  done )
	U="$tmp/data/data/com.termux/files/usr"
	mkdir -p "$SR/usr"
	cp -R "$U/include" "$U/lib" "$SR/usr/" 2>/dev/null || true
	# .pc files hardcode Termux's on-device prefix in prefix=, includedir= and
	# libdir= alike, so rewrite every occurrence -- a partial rewrite hands the
	# compiler paths that do not exist here and the plugin is silently dropped.
	perl -pi -e "s{/data/data/com\.termux/files/usr}{$SR/usr}g" "$SR/usr/lib/pkgconfig/"*.pc
fi

# ------------------------------------------------------------- 2. VM sources
if [ ! -d "$O/.git" ]; then
	echo "==> cloning opensmalltalk-vm at $COMMIT"
	git clone --branch Cog https://github.com/OpenSmalltalk/opensmalltalk-vm.git "$O"
	git -C "$O" checkout "$COMMIT"
fi

# Generated version headers. Upstream's own script does this correctly given a
# real git clone -- no hand-written sqSCCSVersion.h needed.
( cd "$O" && bash scripts/updateSCCSVersions )

# ------------------------------------------------------------- 3. VM patches
# Four Bionic portability fixes. Each is an extension of a guard upstream
# already has, so they are candidates to send upstream as-is.
cd "$O"
#  (a) strverscmp is a GNU extension; upstream already excludes musl.
perl -pi -e 's/^#if defined\(__linux__\)$/#if defined(__linux__) \&\& !defined(__ANDROID__)/' \
	platforms/unix/plugins/SqueakSSL/openssl_overlay.h
#  (b) valloc() does not exist in Bionic.
perl -0pi -e 's{\tif \(!\(mem = valloc\(pagesize\)\)\)\n\t\treturn 0;}
{#if defined(__ANDROID__)\n\tif (posix_memalign(&mem, pagesize, pagesize) != 0)\n\t\treturn 0;\n#else\n\tif (!(mem = valloc(pagesize)))\n\t\treturn 0;\n#endif}' \
	platforms/Cross/plugins/IA32ABI/arm64abicc.c
#  (c) Bionic's FILE is opaque, so `*stdout = *output` will not compile --
#      exactly the musl situation upstream already handles with no-op stubs.
perl -pi -e 's/^#ifndef MUSL$/#if !defined(MUSL) \&\& !defined(__ANDROID__)/' \
	platforms/Cross/vm/sqVirtualMachine.c
perl -pi -e 's/^#ifdef MUSL$/#if defined(MUSL) || defined(__ANDROID__)/' \
	platforms/unix/vm/sqUnixMain.c
#  (d) getdtablesize()/confstr() exist at no Bionic API level; login_tty lives
#      in <utmp.h>. Shipped as a force-included header (see the file).
cp "$HERE/android/sqAndroidCompat.h" platforms/unix/vm/sqAndroidCompat.h

# ------------------------------------------------- 4. configure cross-compile
# AC_REQUIRE_SIZEOF uses AC_TRY_RUN with no fourth (cross-compiling) argument, so
# autoconf emits a hard "cannot run test program while cross compiling" abort and
# configure dies at the first size check. Patch the macro AND the corresponding
# two blocks of the committed generated configure.
#
# Both edits are skipped when already applied, so this stays a no-op once the
# upstream pull request that carries the same fix is merged.
python3 - "$O" <<'PY'
import sys, pathlib

o = pathlib.Path(sys.argv[1])

# -- the macro itself ------------------------------------------------------
m4 = o / "platforms/unix/config/acinclude.m4"
s = m4.read_text(errors="surrogateescape")
old_m4 = '''  AC_TRY_RUN([#include <sys/types.h>
\t      int main(){return(sizeof($1) == $2)?0:1;}],'''
new_m4 = '''  AC_COMPILE_IFELSE([AC_LANG_SOURCE([[#include <sys/types.h>
\t      int sizeof_assertion[(sizeof($1) == $2) ? 1 : -1];]])],'''
if old_m4 in s:
    m4.write_text(s.replace(old_m4, new_m4), errors="surrogateescape")
    print("   acinclude.m4: AC_TRY_RUN -> AC_COMPILE_IFELSE")

# -- and the generated configure -------------------------------------------
# Replace each AC_REQUIRE_SIZEOF expansion wholesale: from the cross-compiling
# guard that precedes the test source down to the `fi` that closes it. A textual
# search-and-replace cannot be used here -- the surrounding lines appear many
# times over in a generated configure, and matching only the unique ones leaves
# the block half-converted.
cfg = o / "platforms/unix/config/configure"
L = cfg.read_text(errors="surrogateescape").splitlines(keepends=True)

GUARD   = '  if test "$cross_compiling" = yes; then :\n'
RUN_END = '  conftest.$ac_objext conftest.beam conftest.$ac_ext\n'

def convert(marker, assertion):
    """Swap one AC_REQUIRE_SIZEOF run-test for the compile-test autoconf would
       have generated. The replacement is spelled out rather than spliced from
       the original, because the pieces around it (`if ac_fn_c_try_run`, the
       cleanup line) recur dozens of times in a generated configure."""
    global L
    hits = [k for k, l in enumerate(L) if marker in l]
    if not hits:
        return False
    i = hits[0]
    start = next(k for k in range(i, -1, -1) if L[k] == GUARD)
    end   = next(k for k in range(i, len(L)) if L[k] == RUN_END) + 1
    assert L[end] == 'fi\n', "unexpected end of AC_REQUIRE_SIZEOF block"
    block = [
        '            cat confdefs.h - <<_ACEOF >conftest.$ac_ext\n',
        '/* end confdefs.h.  */\n',
        '#include <sys/types.h>\n',
        '\t      %s\n' % assertion,
        '_ACEOF\n',
        'if ac_fn_c_try_compile "$LINENO"; then :\n',
        '  { $as_echo "$as_me:${as_lineno-$LINENO}: result: \\"okay\\"" >&5\n',
        '$as_echo "\\"okay\\"" >&6; }\n',
        'else\n',
        '  { $as_echo "$as_me:${as_lineno-$LINENO}: result: \\"bad\\"" >&5\n',
        '$as_echo "\\"bad\\"" >&6; }\n',
        '    as_fn_error $? "\\"one or more basic data types has an incompatible '
        'size: giving up\\"" "$LINENO" 5\n',
        'fi\n',
        'rm -f core conftest.err conftest.$ac_objext conftest.$ac_ext\n',
    ]
    L = L[:start] + block + L[end + 1:]
    return True

n = 0
for marker, assertion in (
        ('int main(){return(sizeof(int) == 4)?0:1;}',
         'int sizeof_assertion[(sizeof(int) == 4) ? 1 : -1];'),
        ('int main(){return(sizeof(double) == 8)?0:1;}',
         'int sizeof_assertion[(sizeof(double) == 8) ? 1 : -1];')):
    if convert(marker, assertion):
        n += 1
if n:
    cfg.write_text(''.join(L), errors="surrogateescape")
    print("   configure: %d AC_REQUIRE_SIZEOF block(s) made cross-safe" % n)
PY

# --------------------------------------------------------------- 5. building
mkdir -p "$B"; cd "$B"
cp ../plugins.int ../plugins.ext . 2>/dev/null || true
# CameraPlugin is V4L2-only; Android apps cannot open /dev/video*.
perl -pi -e 's/^CameraPlugin \\\n$//' plugins.int plugins.ext
rm -f config.h config.cache

export PKG_CONFIG_LIBDIR="$SR/usr/lib/pkgconfig"
export PKG_CONFIG_PATH="$SR/usr/lib/pkgconfig"

# UnicodePlugin is guarded by PKG_CHECK_MODULES(glib-2.0 pangocairo), and under
# cross-compilation configure ends up with an empty $PKG_CONFIG and quietly
# disables the plugin -- passing PKG_CONFIG= on the command line is not enough.
# Hand it the resolved flags instead, which is the escape hatch the macro
# provides precisely for this.
UNICODE_CFLAGS="$(pkg-config --cflags 'glib-2.0 pangocairo' 2>/dev/null || true)"
UNICODE_LIBS="$(pkg-config --libs 'glib-2.0 pangocairo' 2>/dev/null || true)"

../../../../platforms/unix/config/configure \
	--host=aarch64-linux-android --build="$(uname -m)-apple-darwin" \
	--with-vmversion=5.0 --with-src=src/spur64.stack --disable-cogit \
	--without-npsqueak --with-scriptname=spur64 \
	--x-includes="$SR/usr/include" --x-libraries="$SR/usr/lib" \
	PKG_CONFIG="$(command -v pkg-config)" \
	UNICODE_PLUGIN_CFLAGS="$UNICODE_CFLAGS" UNICODE_PLUGIN_LIBS="$UNICODE_LIBS" \
	CC="$TC/aarch64-linux-android$API-clang" \
	AR="$TC/llvm-ar" RANLIB="$TC/llvm-ranlib" STRIP="$TC/llvm-strip" NM="$TC/llvm-nm" \
	CFLAGS="-g -O2 -DNDEBUG -DDEBUGVM=0 -D_GNU_SOURCE -D__ARM_ARCH_ISA_A64 -DARM64 \
-D__arm64__ -D__aarch64__ -DDUAL_MAPPED_CODE_ZONE=1 -DNOEXECINFO \
-include $O/platforms/unix/vm/sqAndroidCompat.h -I$SR/usr/include" \
	LDFLAGS="-L$SR/usr/lib -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384" \
	LIBS="-liconv"

# getversion is a *build* tool: the Makefile compiles it with $(CC), which here
# is the cross compiler, so the build host cannot run it. Build it for the host.
cc -DLSB_FIRST=1 -o getversion -I"$B" -I"$O/platforms/Cross/vm" \
	-I"$O/platforms/unix/vm" "$O/platforms/unix/config/getversion.c" 2>/dev/null \
	&& touch getversion || true

# The `squeak` rule runs the freshly linked binary to stamp a version; that
# cannot work from a cross build, so the final "Error 126" is expected and
# happens *after* the binary and every plugin are already written.
make -j"$(getconf _NPROCESSORS_ONLN)" || true

# ------------------------------------------------------- 6. Android fixups
# On a normal Unix the plugins resolve VM symbols (localeEncoding, interpret,
# ...) from the executable's dynamic symbol table. Here the "executable" is
# itself dlopen()ed as libsqueak.so, so each plugin must name it explicitly or
# its own dlopen fails with "cannot locate symbol". The shipped plugins have
# always had this; add it after linking, since libsqueak.so does not exist
# under that name until the VM is installed.
command -v patchelf >/dev/null || { echo "patchelf required (brew install patchelf)" >&2; exit 1; }
for so in $(find "$B" -name '*.so'); do
	patchelf --print-needed "$so" | grep -q '^libsqueak\.so$' \
		|| patchelf --add-needed libsqueak.so "$so"
done
# vm-display-X11 additionally uses SysV shared memory, which Bionic does not
# implement -- Termux's libandroid-shmem provides it (its <sys/shm.h> redirects
# shmget to libandroid_shmget) -- and XRandR. libtool leaves this one under
# <plugin>/.libs/, not next to the VM, so look it up rather than assume.
DISPLAY_SO="$(find "$B" -name 'vm-display-X11.so' | head -1)"
[ -n "$DISPLAY_SO" ] || { echo "vm-display-X11.so was not built" >&2; exit 1; }
for lib in libandroid-shmem.so libXrandr.so; do
	patchelf --print-needed "$DISPLAY_SO" | grep -q "^$lib$" \
		|| patchelf --add-needed "$lib" "$DISPLAY_SO"
done

echo
echo "==> VM:      $B/squeak"
echo "==> plugins: $(find "$B" -name '*.so' | wc -l | tr -d ' ') modules"
echo
echo "Install with:"
echo "  cp $B/squeak <repo>/app/src/main/jniLibs/arm64-v8a/libsqueak.so"
echo "  find $B -name '*.so' -exec cp {} <repo>/app/src/main/assets/plugins/ \\;"
