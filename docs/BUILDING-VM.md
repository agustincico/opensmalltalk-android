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

## Provenance (read from strings in the binaries)

- **`libsqueak.so`** — built from an `opensmalltalk-vm` checkout at
  `/data/data/com.termux/files/home/opensmalltalk-vm`, **Stack/Spur** flavour
  (`squeak.stack.spur`), referencing upstream release tag **`r3732`**.
  It is the VM's `squeak` **executable, renamed**; `squeak_jni.c` `dlopen()`s it and enters
  through `dlsym(handle, "main")`.
- **The plugins** — built from a *different* checkout,
  `/data/data/com.termux/files/home/opensmalltalk-vm-cog-clean`, under
  `building/linux64ARMv8/`**`squeak.cog.spur`**`/build/<PluginName>/`.
- **The support libraries** (libX11, libcairo, libpango, libglib, …) were taken from a
  **Termux** installation on the same ARM64 phone (`/data/data/com.termux/files/usr/...`).

So: everything native was produced **on an Android phone under Termux**, and the VM and the
plugins came from two different source trees.

## Reproducible today: the VM (Stack/Spur)

`scripts/apply-fixes-stack.sh` applies the 8 source patches needed to compile the standard
Linux VM under Termux/Android:

| # | Patch |
|---|---|
| 1 | create `platforms/Cross/plugins/sqPluginsSCCSVersion.h` |
| 2 | create `platforms/Cross/vm/sqSCCSVersion.h` |
| 3 | `arm64abicc.c` — `valloc` |
| 4 | `openssl_overlay.h` — `strverscmp` |
| 5 | `UnixOSProcessPlugin.c` — missing POSIX functions |
| 6 | `sqUnixUUID.c` — UUID header for Android/Termux |
| 7 | `sqUnixMain.c` — `sqSCCSVersion.h` include path |
| 8 | disable `BitBltArm64.c` (Termux assembler issues) |

### Procedure

Requires an **aarch64 Termux** environment (this is how it was done; a generic aarch64 Linux
toolchain has not been tried). Install at least: `git`, `clang`, `make`, `autoconf`,
`automake`, `libtool`, `pkg-config`, plus the X11/cairo/pango/glib/uuid/iconv/zlib dev
packages.

```bash
git clone https://github.com/OpenSmalltalk/opensmalltalk-vm
# Patch the VM checkout (pass its path; the script refuses anything else):
bash /path/to/opensmalltalk-android/scripts/apply-fixes-stack.sh ~/opensmalltalk-vm

cd ~/opensmalltalk-vm/building/linux64ARMv8/squeak.stack.spur/build
cp ../plugins.int . && cp ../plugins.ext .
bash ../../../../platforms/unix/config/configure \
     --with-src=src/spur64.stack --disable-cogit CC=clang
sed -i 's/-luuid -lz -lpthread -lm/-luuid -lz -lpthread -lm -liconv/g' Makefile
make
```

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

1. **No pinned upstream commit.** `apply-fixes-stack.sh` applies 5 of its 8 patches with
   `sed` line addresses (e.g. `10i`, `8s`, and absolute line numbers). On any other revision
   those silently patch nothing, or the wrong line. The revision it was written against is
   not recorded. **Fix:** pin a commit SHA and convert the line-addressed patches to context
   diffs (`git apply`).
2. **The plugins have no build procedure.** They came from a `squeak.cog.spur` tree in a
   *different* checkout (`opensmalltalk-vm-cog-clean`) with unknown patches; the script above
   only covers `squeak.stack.spur`. **Fix:** rebuild the plugins in the stack tree
   (`plugins.int` / `plugins.ext`) and confirm they load, or document the cog tree's patches.
   This also blocks fixing the known over-linked `XDisplayControlPlugin.so`.
3. **The ~100 support libraries have no manifest.** Harvested from a Termux install with no
   package list, versions, or copy script — they can't be rebuilt or security-audited as-is.
   **Fix:** record `pkg list-installed` from that device and a copy script, or switch to
   libraries built from source with the NDK.
4. **`apply-fixes-stack.sh` is GNU-`sed`-only** (BSD/macOS `sed -i` differs), and assumes
   Termux paths/toolchain.

**These are recoverable only from the phone that produced the binaries** — while it still
exists, capture `pkg list-installed`, the two VM checkouts (their `git log -1`), and
`plugins.int`/`plugins.ext`. That single capture would close gaps 1–3.

## Verifying what you have

The committed binaries can at least be fingerprinted:

```bash
# hashes of everything native that ships
find app/src/main/jniLibs app/src/main/assets/plugins -name '*.so*' -print0 \
  | sort -z | xargs -0 shasum -a 256 > /tmp/native-manifest.txt

# provenance strings (build tree / upstream tag) of any binary
strings app/src/main/jniLibs/arm64-v8a/libsqueak.so | grep -E 'opensmalltalk-vm|r[0-9]{4}'
```
