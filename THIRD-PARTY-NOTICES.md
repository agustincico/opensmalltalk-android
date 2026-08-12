# Third-party notices

The source code in this repository is MIT licensed (see [LICENSE](LICENSE)). **The APK also
redistributes prebuilt third-party native libraries, which are covered by their own licenses,
not by MIT.** This file discloses what is bundled so redistributors can comply.

> **Status: partial.** The binaries in `app/src/main/jniLibs/arm64-v8a/` and
> `app/src/main/assets/plugins/` were harvested from a **Termux** installation on an ARM64
> phone (see [docs/BUILDING-VM.md](docs/BUILDING-VM.md)); no `pkg list-installed` manifest was
> kept. However, the versions of many of the shipped libraries have since been **recovered by
> reading the version banners embedded in the binaries themselves** (see
> [Recovered versions](#recovered-versions-read-from-the-shipped-binaries) below) — that is the
> authoritative record of what is actually distributed. A handful are stripped and still need
> the device's package list. If you redistribute this APK, verify these yourself.

## The Smalltalk VM

- **OpenSmalltalk VM** (`libsqueak.so`, `vm-display-*.so`, `vm-sound-*.so`, and the
  `*Plugin.so` files) — https://github.com/OpenSmalltalk/opensmalltalk-vm — MIT.
  Built from upstream sources (Stack/Spur interpreter); see docs/BUILDING-VM.md.

## Embedded X11 server

- **android-xserver-enhanced** (`library/`, Java source, included in this repo) —
  https://github.com/ZhymabekRoman/android-xserver-enhanced, itself a fork of
  Matthew Kwan's android-xserver. See the headers in `library/` for its terms.

## Bundled native libraries

Grouped by upstream project. Licenses are the projects' usual terms — **verify per version**.

| Project (files) | Typical license |
|---|---|
| glib / gobject / gmodule / gio (`libglib-2.0`, `libgobject-2.0`, `libgmodule-2.0`, `libgio-2.0`) | LGPL-2.1+ |
| cairo (`libcairo`, `libpixman-1`) | LGPL-2.1 / MPL-1.1 |
| pango (`libpango-1.0`, `libpangocairo-1.0`, `libpangoft2-1.0`) | LGPL-2.1+ |
| HarfBuzz (`libharfbuzz`), FriBidi (`libfribidi`) | MIT / LGPL-2.1+ |
| FreeType (`libfreetype`), fontconfig (`libfontconfig`) | FTL or GPL-2 / MIT |
| graphite2 (`libgraphite2`) | LGPL-2.1+ |
| X11 client stack (`libX11`, `libXext`, `libXrender`, `libXrandr`, `libXi`, `libXfixes`, `libXcursor`, `libXau`, `libXdmcp`, `libXss`, `libICE`, `libSM`, `libxcb*`, `libxkbcommon`) | MIT/X11 |
| Mesa/GL loader (`libGL`, `libGLX`, `libGLdispatch`) | MIT |
| Wayland (`libwayland-*`, `libdecor-0`) | MIT |
| PulseAudio (`libpulse*`) | LGPL-2.1+ |
| libsndfile (`libsndfile`), FLAC (`libFLAC`), Ogg/Vorbis (`libogg`, `libvorbis*`), Opus (`libopus`), LAME (`libmp3lame`) | LGPL-2.1+ / BSD / LGPL-2 |
| SDL2 (`libSDL2-2.0`) | Zlib |
| D-Bus (`libdbus-1`) | AFL-2.1 or GPL-2+ |
| zlib (`libz`), bzip2 (`libbz2`), brotli (`libbrotli*`), expat (`libexpat`), libpng (`libpng16`), PCRE2 (`libpcre2-8`), libffi (`libffi`), libiconv (`libiconv`), util-linux uuid (`libuuid`) | zlib / BSD / MIT / LGPL-2.1+ |
| Termux support shims (`libandroid-shmem`, `libandroid-support`, `libandroid-execinfo`, `libandroid-posix-semaphore`) | Termux project terms |
| LLVM libc++ (`libc++_shared.so`) | Apache-2.0 with LLVM exception |

### LGPL note

Several of the above are **LGPL**. LGPL redistribution normally requires providing the
library's source (or a written offer) and allowing the user to relink. These libraries are
shipped as **dynamic** `.so` files (the VM `dlopen()`s them at runtime), which is the
relinkable arrangement LGPL contemplates — but the corresponding sources/versions are not
currently identified here. **Before publishing this APK (e.g. to an app store), pin the
versions and provide the source offer.**

## Bundled Smalltalk images

None. `*.image` / `*.changes` / `*.sources` are gitignored; images are downloaded by the user
at runtime (Squeak from files.squeak.org, Cuis from the Cuis-Smalltalk-Dev repository) and
remain under their own licenses (both MIT).

## Recovered versions (read from the shipped binaries)

These were extracted directly from the committed `.so` files (`strings <lib> | grep` for the
project's version banner), so they describe **exactly the binaries in this repo** — not a guess
from a package list. Reproduce with the recipe in
[docs/BUILDING-VM.md](docs/BUILDING-VM.md#verifying-what-you-have).

| Library | Version | Banner found in the binary |
|---|---|---|
| libpng (`libpng16.so`) | **1.6.50** | `libpng version 1.6.50` |
| HarfBuzz (`libharfbuzz.so`) | **12.2.0** | `12.2.0` |
| cairo (`libcairo.so.2`) | **1.18.4** | `1.18.4` |
| pango (`libpango-1.0.so.0`) | **1.57.0** | `1.57.0` |
| PCRE2 (`libpcre2-8.so`) | **10.46** | `10.46 2025-08-27` |
| expat (`libexpat.so.1`) | **2.7.1** | `expat_2.7.1` |
| D-Bus (`libdbus-1.so`) | **1.16.2** | `1.16.2` |
| FLAC (`libFLAC.so`) | **1.5.0** (2025-02-11) | `reference libFLAC 1.5.0 20250211` |
| Vorbis (`libvorbis.so`) | **1.3.7** | `Xiph.Org libVorbis I 20200704` |
| Opus (`libopus.so`) | **1.5.2** | `libopus 1.5.2` |
| libsndfile (`libsndfile.so`) | **1.2.2** | `libsndfile-1.2.2` |
| PulseAudio (`libpulse.so`, `libpulse-simple.so`, `libpulsecommon-17.0.so`) | **17.0-4** | replaced 2026-08-12 from Termux (16 KB-aligned build) |
| libXcursor (`libXcursor.so`) | **1.2.3-1** | replaced 2026-08-12 from Termux (16 KB-aligned build) |
| GLib (`libglib-2.0.so.0` etc.) | **2.86.1** | stripped — from the source device's Termux `pkg list-installed` (2026-08-09) |
| FreeType (`libfreetype.so`) | **2.14.1** | stripped — same package-list capture |
| fontconfig (`libfontconfig.so`) | **2.17.1-1** | stripped — same package-list capture |

The three stripped libraries' versions come from the source device itself: its Termux
`pkg list-installed` (captured 2026-08-09; the binaries were harvested 2026-07-22 from the
same installation, so a minor point-release drift is possible but unlikely). All native
libraries were cross-compiled with the **Android NDK clang 19.0.1** (LLD 19.0.1),
per the compiler banner in the binaries. The VM itself is `opensmalltalk-vm`
**7.0rc2-202511100848** (StackInterpreter VMMaker.oscog-eem.3682, upstream commit
`d621595`, built Nov 12 2025 under Termux), `squeak.stack.spur` flavour; the
display/sound plugins are from a `squeak.cog.spur` tree —
see [docs/BUILDING-VM.md](docs/BUILDING-VM.md#provenance-read-from-strings-in-the-binaries).

## How to help

All shipped library versions are now identified (table above). What's still missing is the
per-version **license texts** for redistribution-grade compliance — if you can contribute
those (or spot a version we got wrong), please open a PR.
