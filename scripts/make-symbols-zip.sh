#!/usr/bin/env bash
#
# make-symbols-zip.sh -- build the native debug symbols archive for Play.
#
# Play warns that a bundle with native code has no debug symbols, so a native
# crash arrives as raw addresses instead of function names. AGP is supposed to
# do this itself (`ndk { debugSymbolLevel 'FULL' }`), and for this project it
# does not: its extractor reports every library as "already stripped".
#
# That verdict is wrong for the VM. `llvm-readelf -S libsqueak.so` lists
# .debug_info, .debug_line and four more DWARF sections — the file has full debug
# information. What it also has is an ELF layout rewritten by patchelf (the build
# adds NEEDED entries after linking, see build-vm-android.sh), and AGP's own
# minimal ELF reader does not recognise the section table it produces.
#
# So do it directly. The result is uploaded by hand: Play Console → App bundle
# explorer → the version → Downloads → "Upload native debug symbols".
#
#   scripts/make-symbols-zip.sh            # → build/symbols-<version>.zip
set -euo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd)"
NDK="${NDK:-$HOME/Library/Android/sdk/ndk/26.2.11394342}"
OBJCOPY="$NDK/toolchains/llvm/prebuilt/$(uname -s | tr 'A-Z' 'a-z')-x86_64/bin/llvm-objcopy"
READELF="${OBJCOPY%objcopy}readelf"
LIBS="$HERE/app/src/main/jniLibs/arm64-v8a"
VER="$(sed -n 's/.*versionName "\([^"]*\)".*/\1/p' "$HERE/app/build.gradle" | head -1)"
OUT="${OUT:-$HERE/build/symbols-${VER:-unknown}.zip}"

[ -x "$OBJCOPY" ] || { echo "llvm-objcopy not found at $OBJCOPY" >&2; exit 1; }
STAGE="$(mktemp -d)"; trap 'rm -rf "$STAGE"' EXIT
mkdir -p "$STAGE/arm64-v8a" "$(dirname "$OUT")"

kept=0; skipped=0
for so in "$LIBS"/*.so; do
	name="$(basename "$so")"
	# Only the ones that actually carry DWARF. Most of the support libraries came
	# from Termux already stripped, and an empty .sym in the archive helps nobody.
	#
	# Read the sections into a variable rather than piping into `grep -q`: with
	# `set -o pipefail`, grep exiting early kills readelf with SIGPIPE and the
	# pipeline reports failure even though the pattern matched. That silently
	# produced an empty archive.
	sections="$("$READELF" -S "$so" 2>/dev/null || true)"
	case "$sections" in *.debug_info*) has_dwarf=1 ;; *) has_dwarf=0 ;; esac
	if [ "$has_dwarf" = 1 ]; then
		"$OBJCOPY" --only-keep-debug "$so" "$STAGE/arm64-v8a/$name.sym"
		kept=$((kept + 1))
	else
		skipped=$((skipped + 1))
	fi
done

[ "$kept" -gt 0 ] || { echo "no library carried debug information" >&2; exit 1; }
rm -f "$OUT"
( cd "$STAGE" && zip -qr "$OUT" arm64-v8a )
echo "$OUT"
echo "  $kept with debug info, $skipped already stripped by whoever built them"
ls -la "$OUT"
