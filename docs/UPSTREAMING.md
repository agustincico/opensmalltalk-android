# Contributing this work back to OpenSmalltalk

Plan for proposing the Android port to the upstream project, based on what the
OpenSmalltalk org actually looks like (surveyed 2026-08-12).

## What upstream looks like

- The org has **two** repos: `opensmalltalk-vm` (the VM) and
  `opensmalltalk.github.io` (a single-page site). There is no "apps" or "ports" repo.
- `opensmalltalk-vm` is **active** — commits in the last days (mostly Eliot Miranda),
  PRs merged regularly — and its default branch is **`Cog`**, not `main`.
- `platforms/` holds `Cross, unix, win32, iOS, minheadless, Plan9, RiscOS`;
  `building/` holds one directory per target (`linux64ARMv8`, `macos64ARMv8`, …).
  **There is no Android support of any kind** (the single `android` hit in the tree
  is an unrelated gdb config).
- Useful precedent: **PR #753 "Support for OpenBSD" was merged and touched 3 files** —
  two `building/*/mvm` scripts and one `platforms/unix/vm/*.c`. That is the shape of
  contribution this project accepts: small, focused, ifdef-guarded.

## The split: what goes upstream, what does not

This repo is two very different things glued together.

**Belongs upstream (small, in scope):** the changes that make the *Unix VM compile and
run on Android/Bionic*. Verified still needed against the current `Cog` branch:

All of these are now **verified against a real NDK cross-build** of commit `a4d3da0` that
boots on a device (see [BUILDING-VM.md](BUILDING-VM.md)) — not inferred from the old
line-addressed `sed` script, which turned out to be partly unnecessary and partly wrong.

| Fix | Shape | Ready to send? |
|---|---|---|
| `strverscmp` in `platforms/unix/plugins/SqueakSSL/openssl_overlay.h` | GNU-only, absent in Bionic **and** musl. Upstream already has the fallback; extend `#if defined(__linux__)` with `&& !defined(__ANDROID__)` | **yes** — 1 line |
| `valloc()` → `posix_memalign()` in `platforms/Cross/plugins/IA32ABI/arm64abicc.c` | `valloc` does not exist in Bionic | **yes** — 6 lines, `__ANDROID__`-guarded |
| opaque `FILE` in `platforms/Cross/vm/sqVirtualMachine.c` + `platforms/unix/vm/sqUnixMain.c` | `static FILE stdoutStack[]` and `*stdout = *output` need glibc's complete `FILE`. This is **exactly** the musl case upstream already handles with no-op `pushOutputFile`/`popOutputFile`; widen the two guards | **yes** — 3 lines, and it fixes musl-adjacent libcs generally |
| `getdtablesize()` / `confstr()` / `login_tty` | exist at **no** Bionic API level. Our `sqAndroidCompat.h` implements them (`sysconf(_SC_OPEN_MAX)`; a `_CS_PATH`-only `confstr`) and is force-included | **as a `platforms/unix/vm` header** — never patch `src/`, it is generated from VMMaker |
| `AC_REQUIRE_SIZEOF` uses `AC_TRY_RUN` in `platforms/unix/config/acinclude.m4` | aborts *any* cross build with "cannot run test program while cross compiling"; a compile-time static assertion works for native and cross alike | **yes, and valuable beyond Android** — this blocks every cross-compile target |
| ~~uuid header in `sqUnixUUID.c`~~ | not needed — `configure` sets `HAVE_UUID_UUID_H` correctly once the sysroot has the header | drop |
| ~~create `sqSCCSVersion.h` / `sqPluginsSCCSVersion.h`~~ | not needed — upstream's own `scripts/updateSCCSVersions` does it given a real git clone | drop |
| ~~include-path edit in `sqUnixMain.c`~~ | consequence of the above | drop |
| ~~disable `BitBltArm64.c`~~ | not needed *for us*: `--enable-fast-bitblt` is **off by default** on aarch64 upstream, so nothing disables it. But it does **not** compile with NDK clang 17 either — ~5 inline-asm errors (`invalid operand for instruction`, `constraint 'I' expects an integer constant expression`, `Immediate too large for register`). So this is a **real clang incompatibility in the optimized blitter**, not the Termux assembler quirk we assumed | drop from our patch set; **report as a separate upstream issue** with the error list |

So the old "8 patches" are really **4 source fixes plus 1 build-system fix**, and two of the
five (the `FILE` guard and the `AC_TRY_RUN` removal) fix problems that are not Android-specific
at all — good openers for the issue. The BitBlt finding is a bug report we owe upstream,
independent of the port.

Plus a `building/android64ARMv8/` config mirroring `linux64ARMv8`, so the port is
reproducible and CI-able instead of living in a shell script here.

**Does NOT belong upstream:** the Android application itself — the launcher, image
chooser/library, file-in/fileout plumbing, touch/zoom handling, and especially the
**forked Java X server** (`library/`, a fork of android-xserver) and the **prebuilt
`.so` blobs**. A VM repo does not host a phone app, and blobs harvested from a device
are exactly what upstream would (rightly) reject.

## Status: submitted 2026-08-16

- **PR [OpenSmalltalk/opensmalltalk-vm#781](https://github.com/OpenSmalltalk/opensmalltalk-vm/pull/781)**
  — "Android/arm64: cross-build the Stack VM with the NDK". Six commits, 11 files, against
  `Cog` at `c687569`. Branch: `agustincico:android-arm64-cross-build`.
- **Issue [#780](https://github.com/OpenSmalltalk/opensmalltalk-vm/issues/780)** —
  `BitBltArm64.c` does not assemble with clang 17 on aarch64 (18 errors, all in the inline
  assembly). Filed separately: it is upstream's bug, not part of the port.

Eliot's guidance (email, 2026-08-14) splits contributions in two: Monticello on
source.squeak.org for Smalltalk-level VMMaker work, git for everything else. **This
contribution is entirely git-side** — every change is in `platforms/` or `building/`, and
nothing touches the VMMaker-generated `src/`, so no VMMaker image was needed. The one
piece that could migrate is the OSProcess shim; the PR says so and offers to move it.

The Android application itself stays in this repository. It bundles a forked Java X server
and ~60 LGPL third-party libraries whose source offer is not yet pinned, so it does not
belong in upstream's release stream. Once `building/android64ARMv8/` is merged, upstream
can produce an Android VM artifact through its own process — that is the path to an
"official" Android VM, rather than shipping this APK there.

## Prerequisite before opening the PR — **done 2026-08-12**

The rebuild that had to happen first is finished, so the PR now rests on evidence rather
than on a script nobody could re-run:

1. ✅ Cloned `opensmalltalk-vm` at pinned commit `a4d3da0` of `Cog`.
2. ✅ Re-expressed every fix as an `#ifdef`-guarded edit or a force-included header.
3. ✅ **Cross-compiled with the Android NDK** on a desktop — `scripts/build-vm-android.sh`,
   reproducible by anyone, no phone and no Termux install needed.
4. ✅ Installed the resulting VM + 20 plugins into the app and **verified Cuis 7.7 boots**
   on the emulator and the World menu responds to touch.
5. ⬜ Open the issue, then the PR, against `Cog`.

Two claims from the earlier plan did **not** survive contact with the build, and the PR
should not repeat them: three of the "8 patches" turned out to be unnecessary, and the
BitBlt one was misdiagnosed (see the table above).

## Where to propose it

1. **Open an issue on `opensmalltalk-vm` first**, describing the port and asking whether
   they want (a) only the Bionic build fixes, or (b) also a `platforms/android/` host
   glue (the JNI bridge). Ask before dropping a large PR — the OpenBSD precedent shows
   they merge focused work.
2. **PR the build fixes** against `Cog` (small, ifdef-guarded, with the build config).
3. **Keep the app in its own repo** (this one). Ask, in the same issue, whether they'd
   like it adopted into the org later; otherwise offer it as a linked community port.
4. **Link it from the website**: `opensmalltalk.github.io` is a single `index.html` —
   a small PR adding an Android entry is the low-friction way to make the port visible.

## Authorship

All commits and PRs are authored by **Agustín (@agustincico)** alone. Do not add
co-author trailers of any kind.
