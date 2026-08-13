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

| Our fix (`scripts/apply-fixes-stack.sh`) | Upstream status | How to contribute it |
|---|---|---|
| `valloc` → `posix_memalign` in `platforms/Cross/plugins/IA32ABI/arm64abicc.c` | still `valloc` (line ~316); absent in Bionic | tiny portable fix, no ifdef needed |
| `strverscmp` in `platforms/unix/plugins/SqueakSSL/openssl_overlay.h` | still there (~548); GNU-only, absent in Bionic **and musl** | upstream *already* has a fallback branch — just extend its condition |
| `getdtablesize()` / `confstr()` in `UnixOSProcessPlugin.c` | still there (764, 1437, 1443) | ⚠️ `src/` is **generated** from VMMaker — do **not** patch it. Put Bionic shims in a `platforms/unix` header instead (or take it to the OSProcess/VMMaker maintainers) |
| uuid header in `platforms/unix/plugins/UUIDPlugin/sqUnixUUID.c` | uses `HAVE_UUID_UUID_H` from configure | right fix is autoconf detection under Android, not a hardcoded branch |
| `sqSCCSVersion.h` / `sqPluginsSCCSVersion.h` creation | generated upstream from git | **not** a portability fix — a build-environment workaround; leave out |
| include-path edit in `sqUnixMain.c` | — | consequence of the above; leave out |
| disabling `BitBltArm64.c` | — | ⚠️ that is the **optimized blitter**; disabling it costs graphics performance. Needs real diagnosis of the Termux/clang assembler failure before it can be proposed (or fixed for ourselves) |

Plus a `building/android64ARMv8/` config mirroring `linux64ARMv8`, so the port is
reproducible and CI-able instead of living in a shell script here.

**Does NOT belong upstream:** the Android application itself — the launcher, image
chooser/library, file-in/fileout plumbing, touch/zoom handling, and especially the
**forked Java X server** (`library/`, a fork of android-xserver) and the **prebuilt
`.so` blobs**. A VM repo does not host a phone app, and blobs harvested from a device
are exactly what upstream would (rightly) reject.

## Prerequisite before opening the PR

**Build the VM from a pinned upstream commit and prove it runs.** Today's binaries came
from a phone at an unknown revision, and the fixes are applied by `sed` line numbers.
To upstream them honestly:

1. Clone `opensmalltalk-vm` at a pinned commit of `Cog`.
2. Re-express the fixes as real diffs (ifdef-guarded), not line-addressed `sed`.
3. Build for `aarch64` Android — either on-device under Termux (how it was done) or,
   better, **cross-compiled with the Android NDK** so anyone can reproduce it.
4. Drop the resulting `libsqueak.so` + plugins into this app and verify an image boots.
5. Then open the PR against `Cog`.

(Note: this rebuild is *not* needed for Google Play — the shipped binaries are already
16 KB-page aligned. It is needed for **provenance and for contributing back**.)

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
