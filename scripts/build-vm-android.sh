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
	         libpng pcre2 libexpat fribidi libgraphite brotli; do
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
	# .pc files hardcode Termux's on-device prefix; point them at our sysroot.
	perl -pi -e "s{^prefix=.*}{prefix=$SR/usr}" "$SR/usr/lib/pkgconfig/"*.pc
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
cp "$(dirname "$0")/android/sqAndroidCompat.h" platforms/unix/vm/sqAndroidCompat.h

# ------------------------------------------------- 4. configure cross-compile
# AC_REQUIRE_SIZEOF uses AC_TRY_RUN, which cannot work when cross-compiling and
# aborts configure. Replace the run test with a compile-time static assertion.
python3 - "$O" <<'PY'
import re, sys, pathlib
o = pathlib.Path(sys.argv[1])
cfg = o / "platforms/unix/config/configure"
s = cfg.read_text(errors="surrogateescape")
s = re.sub(r'as_fn_error \$\? "cannot run test program while cross compiling"[^\n]*\n',
           'printf "%s\\n" "okay (compile-time, cross)" >&6\n', s)
cfg.write_text(s, errors="surrogateescape")
PY

# --------------------------------------------------------------- 5. building
mkdir -p "$B"; cd "$B"
cp ../plugins.int ../plugins.ext . 2>/dev/null || true
# CameraPlugin is V4L2-only; Android apps cannot open /dev/video*.
perl -pi -e 's/^CameraPlugin \\\n$//' plugins.int plugins.ext
rm -f config.h config.cache

export PKG_CONFIG_LIBDIR="$SR/usr/lib/pkgconfig"
export PKG_CONFIG_PATH="$SR/usr/lib/pkgconfig"

../../../../platforms/unix/config/configure \
	--host=aarch64-linux-android --build="$(uname -m)-apple-darwin" \
	--with-vmversion=5.0 --with-src=src/spur64.stack --disable-cogit \
	--without-npsqueak --with-scriptname=spur64 \
	--x-includes="$SR/usr/include" --x-libraries="$SR/usr/lib" \
	PKG_CONFIG="$(command -v pkg-config)" \
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
# shmget to libandroid_shmget) -- and XRandR.
for lib in libandroid-shmem.so libXrandr.so; do
	patchelf --print-needed "$B/vm-display-X11.so" | grep -q "^$lib$" \
		|| patchelf --add-needed "$lib" "$B/vm-display-X11.so"
done

echo
echo "==> VM:      $B/squeak"
echo "==> plugins: $(find "$B" -name '*.so' | wc -l | tr -d ' ') modules"
echo
echo "Install with:"
echo "  cp $B/squeak <repo>/app/src/main/jniLibs/arm64-v8a/libsqueak.so"
echo "  find $B -name '*.so' -exec cp {} <repo>/app/src/main/assets/plugins/ \\;"
