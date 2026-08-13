# Rebuilding the native side (VM, plugins, support libraries)

This documents **what the committed native binaries actually are, where they came from, and
which parts are reproducible today**. It is deliberately honest about the gaps: the goal is
that a third party knows exactly what they're trusting and what would have to be redone.

## What ships in the APK

| Where | Count / size | What |
|---|---|---|
| `app/src/main/jniLibs/arm64-v8a/` | 61 files, ~26 MB | `libsqueak.so` (the VM), `libsqueak_jni.so` (built from source here), and the X11/graphics support libraries |
| `app/src/main/assets/plugins/` | 41 files, ~12 MB | VM plugins (`vm-display-X11.so`, `UUIDPlugin.so`, …) plus copies of support libs, extracted to `filesDir/plugins` at first run and preloaded by `squeak_jni.c` |

Only **`libsqueak_jni.so` is built by this repo's build system** (from
`app/src/main/cpp/squeak_jni.c`, via CMake/NDK). Everything else is a committed prebuilt.

## Provenance

**Since 2026-08-12 the VM and all 20 plugin/display/sound modules are built by
`scripts/build-vm-android.sh`** from upstream commit `a4d3da0` (branch `Cog`) with the
Android NDK — see [the recipe below](#reproducible-today-cross-compiling-the-vm-with-the-android-ndk).
The support libraries (libX11, libcairo, libpango, libglib, …) are still Termux prebuilts.

### The previous binaries, for the record (read from strings in them)

- **`libsqueak.so`** — built from an `opensmalltalk-vm` checkout at
  `/data/data/com.termux/files/home/opensmalltalk-vm`, **Stack/Spur** flavour
  (`squeak.stack.spur`). The VM's own crash banner (obtained live 2026-08-10) pins it
  exactly: **`Squeak VM version: 7.0rc2-202511100848`**, built **Nov 12 2025** with clang,
  `StackInterpreter VMMaker.oscog-eem.3682`, upstream **commit `d621595`**
  (Mon Nov 10 2025), `Plugins: 202511100848`, build host Termux/Android aarch64.
  (The `r3732` string found earlier via `strings` is just release-notes URL text baked
  into a help banner — the real revision is the above.)
  It is the VM's `squeak` **executable, renamed**; `squeak_jni.c` `dlopen()`s it and enters
  through `dlsym(handle, "main")`.
- **The plugins** — built from a *different* checkout,
  `/data/data/com.termux/files/home/opensmalltalk-vm-cog-clean`, under
  `building/linux64ARMv8/`**`squeak.cog.spur`**`/build/<PluginName>/`.
- **The support libraries** (libX11, libcairo, libpango, libglib, …) were taken from a
  **Termux** installation on the same ARM64 phone (`/data/data/com.termux/files/usr/...`).

So: everything native was produced **on an Android phone under Termux**, and the VM and the
plugins came from two different source trees.

## Reproducible today: cross-compiling the VM with the Android NDK

`scripts/build-vm-android.sh` builds the VM **and all its plugins** from a pinned upstream
commit, on a desktop machine, with the Android NDK. No phone and no Termux install are
needed. It replaces the old `apply-fixes-stack.sh`, which patched by absolute line number
and only ran on the device.

```bash
brew install patchelf pkg-config          # or your platform's equivalents
./scripts/build-vm-android.sh             # ~10 min; override WORK=, NDK=, API=, COMMIT=
```

Pinned inputs: upstream `opensmalltalk-vm` branch **`Cog`** at
**`a4d3da0ac21d4b95dcc3eb77f7a3c1e24aab003c`**, **NDK 26.2.11394342**, Android **API 28**,
and an Android sysroot assembled from ~30 **Termux** aarch64 packages (X11, GL, glib/pango/
cairo, zlib, iconv, uuid) — the same provenance as the support libraries the APK bundles,
so headers and shipped `.so` files agree.

Result, verified 2026-08-12: `squeak` (5.5 MB, ELF aarch64 PIE, `/system/bin/linker64`),
exporting `main` — the entry point `squeak_jni.c` reaches via `dlsym` — with a `NEEDED` set
identical to the previously shipped `libsqueak.so`, plus **20 plugin/display/sound modules**.
Every artifact is **16 KB-page aligned**. It boots Cuis 7.7 on the emulator and the World
menu responds to touch.

### The four source patches

Each one extends a guard upstream already has, which is why they are small enough to send
upstream (see [UPSTREAMING.md](UPSTREAMING.md)):

| File | Fix |
|---|---|
| `platforms/unix/plugins/SqueakSSL/openssl_overlay.h` | `strverscmp` is a GNU extension; upstream already excludes musl, exclude Bionic too |
| `platforms/Cross/plugins/IA32ABI/arm64abicc.c` | `valloc()` does not exist in Bionic → `posix_memalign` |
| `platforms/Cross/vm/sqVirtualMachine.c` + `platforms/unix/vm/sqUnixMain.c` | Bionic's `FILE` is opaque, so `*stdout = *output` will not compile — exactly the musl case upstream already handles with no-op `pushOutputFile`/`popOutputFile` stubs |
| `scripts/android/sqAndroidCompat.h` (force-included) | `getdtablesize()` and `confstr()` exist at **no** Bionic API level; `login_tty` lives in `<utmp.h>` |

Two more are build-environment issues, not VM bugs: `platforms/unix/config/configure`'s
`AC_REQUIRE_SIZEOF` uses `AC_TRY_RUN`, which can never work when cross-compiling (replaced
with a compile-time static assertion), and `CameraPlugin` is dropped because it is V4L2-only
and Android apps cannot open `/dev/video*`.

### Things that only bite when cross-compiling

- **`getversion` is a build tool.** The Makefile compiles it with `$(CC)` — here the *cross*
  compiler — so the build host cannot run it. Build it for the host instead.
- **The `squeak` target runs the binary it just linked** to stamp a version. From a cross
  build that fails with `cannot execute binary file`, so `make` ends in **Error 126**. This
  is expected and happens *after* the VM and every plugin are already written.
- **Bionic's API floor is real.** `nl_langinfo` needs API 26, `glob`/`globfree` need 28, and
  `backtrace` needs 33 (handled with upstream's own `NOEXECINFO`). Building at API 24, as
  the previously shipped binaries did, requires Termux's `libandroid-support`/`-glob` shims.
- **`AC_PATH_X` and `PKG_CHECK_MODULES` both fail silently when cross-compiling**, quietly
  disabling `ClipboardExtendedPlugin` and `UnicodePlugin`. Pass `--x-includes`/`--x-libraries`
  and `UNICODE_PLUGIN_CFLAGS`/`_LIBS` explicitly.
- **Plugins must name `libsqueak.so`.** On a normal Unix they resolve VM symbols from the
  executable's dynamic symbol table; here the "executable" is itself `dlopen()`ed, so a
  plugin that does not list it fails with `cannot locate symbol "localeEncoding"`. The
  script adds it with `patchelf` after linking.

### What this fixed

The rebuilt `XDisplayControlPlugin.so` no longer drags in `libSM.so`, `libICE.so` and
`libandroid-execinfo.so` — the over-linking that made it fail to `dlopen` (a long-standing
item in `CLAUDE.md`'s backlog). Its `NEEDED` set is now entirely libraries the APK ships.

### Installing the result

| Build artifact | Goes to | Note |
|---|---|---|
| `build/vm/squeak` (the executable) | `app/src/main/jniLibs/arm64-v8a/libsqueak.so` | **must be renamed**: Android only extracts/loads files named `lib*.so` |
| built `*.so` plugins | `app/src/main/assets/plugins/` | preloaded by name in `squeak_jni.c` |

If you add or rename a plugin, update the `deps_to_load[]` list in
`app/src/main/cpp/squeak_jni.c` — it preloads them explicitly, in dependency order.

> The VM must stay an **interpreted Stack** build (`--disable-cogit`). A JIT (Cog) build
> needs W^X memory that Android does not grant.

## Not reproducible today (the honest gaps)

1. **~~No pinned upstream commit~~ — closed 2026-08-12.** `scripts/build-vm-android.sh` pins
   commit `a4d3da0` on branch `Cog` and expresses every fix as an `#ifdef`-guarded edit or a
   force-included header, not a `sed` line address.
2. **~~The plugins have no build procedure~~ — closed 2026-08-12.** All 20 modules are built
   from the same pinned `squeak.stack.spur` tree as the VM, and the over-linked
   `XDisplayControlPlugin.so` is fixed as a side effect.
3. **~~The support libraries have no full manifest~~ — closed.** All shipped library
   versions are now recorded in
   [THIRD-PARTY-NOTICES.md](../THIRD-PARTY-NOTICES.md#recovered-versions-read-from-the-shipped-binaries):
   most read from the binaries' own embedded version banners (libpng 1.6.50, cairo 1.18.4,
   pango 1.57.0, HarfBuzz 12.2.0, PCRE2 10.46, expat 2.7.1, D-Bus 1.16.2, FLAC 1.5.0,
   Vorbis 1.3.7, Opus 1.5.2, libsndfile 1.2.2; all built with Android NDK clang 19.0.1),
   and the three stripped ones (GLib 2.86.1, FreeType 2.14.1, fontconfig 2.17.1) from the
   source device's Termux `pkg list-installed` (captured 2026-08-09). Remaining nice-to-have:
   a copy script / per-version license texts, or switching to libraries built from source
   with the NDK.
4. **The ~60 support libraries are still Termux prebuilts**, copied from a phone rather than
   built here. The sysroot the build script assembles now downloads them from Termux's
   package repository (versioned, checksummed by apt) instead, so switching the *shipped*
   libraries to that same source is a small, well-defined next step.
5. **The build needs a network** the first time (Termux package downloads + the git clone),
   and pins no checksums for the `.deb`s beyond what apt's index provides.

The old `scripts/apply-fixes-stack.sh` (GNU-`sed`-only, Termux-only, line-addressed) is
superseded by `scripts/build-vm-android.sh` and kept only for reference.

## Verifying what you have

The committed binaries can at least be fingerprinted:

```bash
# hashes of everything native that ships
find app/src/main/jniLibs app/src/main/assets/plugins -name '*.so*' -print0 \
  | sort -z | xargs -0 shasum -a 256 > /tmp/native-manifest.txt

# provenance strings (build tree / upstream tag) of any binary
strings app/src/main/jniLibs/arm64-v8a/libsqueak.so | grep -E 'opensmalltalk-vm|r[0-9]{4}'
```
