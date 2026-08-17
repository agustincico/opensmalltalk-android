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
  Since **2026-08-12** these are built by `scripts/build-vm-android.sh` from the pinned
  upstream commit **`a4d3da0ac21d4b95dcc3eb77f7a3c1e24aab003c`** (branch `Cog`,
  `squeak.stack.spur`, interpreted Stack/Spur 64-bit), cross-compiled with **Android NDK
  26.2.11394342** clang against API 28. See [docs/BUILDING-VM.md](docs/BUILDING-VM.md) to
  reproduce them. (They previously came from a phone under Termux at commit `d621595`, with
  the plugins from a separate `squeak.cog.spur` checkout.)

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

## LGPL compliance and written offer

Several bundled libraries are under the **LGPL**, which requires that recipients can obtain
the corresponding source and can relink the application against a modified version.

**Relinking is satisfied by construction.** Every one of these libraries ships as a separate
**dynamic** `.so` that the VM `dlopen()`s at runtime — none is statically linked into the
VM or the application. Replacing any of them is a matter of substituting the file: unpack
the APK, swap the `.so` under `lib/arm64-v8a/` or `assets/plugins/`, repack and re-sign.
No part of this application has to be rebuilt to do that.

### Where the binaries came from

All of them were built by the **Termux** project for aarch64 and taken from a Termux
installation. Termux builds each package from unmodified upstream releases using a build
recipe published at
[termux/termux-packages](https://github.com/termux/termux-packages/tree/master/packages);
each recipe records the exact upstream version, source URL and SHA-256 it builds from, plus
any patches applied. For every library below, the corresponding source is therefore:

1. the upstream release named in the table, from that project's own distribution site, and
2. the matching recipe directory in `termux-packages`, which carries any Termux patches.

### The LGPL libraries in this APK

| Library files | Project | License | Version |
|---|---|---|---|
| `libglib-2.0.so.0`, `libgobject-2.0.so.0`, `libgmodule-2.0.so.0`, `libgio-2.0.so.0` | [GLib](https://gitlab.gnome.org/GNOME/glib) | LGPL-2.1-or-later | 2.86.1 |
| `libpango-1.0.so.0`, `libpangocairo-1.0.so.0`, `libpangoft2-1.0.so.0` | [Pango](https://gitlab.gnome.org/GNOME/pango) | LGPL-2.1-or-later | 1.57.0 |
| `libcairo.so.2` | [cairo](https://gitlab.freedesktop.org/cairo/cairo) | LGPL-2.1 **or** MPL-1.1 | 1.18.4 |
| `libfribidi.so` | [GNU FriBidi](https://github.com/fribidi/fribidi) | LGPL-2.1-or-later | see note |
| `libgraphite2.so` | [Graphite2](https://github.com/silnrsi/graphite) | LGPL-2.1-or-later (tri-licensed MPL/LGPL/GPL) | see note |
| `libiconv.so` | [GNU libiconv](https://www.gnu.org/software/libiconv/) | LGPL-2.1-or-later | see note |
| `libpulse.so`, `libpulse-simple.so`, `libpulsecommon-17.0.so` | [PulseAudio](https://gitlab.freedesktop.org/pulseaudio/pulseaudio) | LGPL-2.1-or-later | 17.0 |
| `libsndfile.so` | [libsndfile](https://github.com/libsndfile/libsndfile) | LGPL-2.1-or-later | 1.2.2 |
| `libmp3lame.so` | [LAME](https://lame.sourceforge.io/) | LGPL-2.0-or-later | see note |

Two further libraries are not LGPL but are **dual-licensed with a copyleft option**, so the
same offer is extended to them: `libfreetype.so` ([FreeType](https://freetype.org/), FTL or
GPL-2.0-or-later) and `libdbus-1.so` ([D-Bus](https://gitlab.freedesktop.org/dbus/dbus),
AFL-2.1 or GPL-2.0-or-later).

**Note on versions.** Where a version is given it was read from a banner inside the binary
itself, or from the source device's Termux `pkg list-installed` — see
[Recovered versions](#recovered-versions-read-from-the-shipped-binaries). The entries marked
*see note* are stripped binaries whose version could not be established that way, and are
deliberately **not** guessed here. If you need the exact source for one of those, use the
written offer below and it will be identified from the build that produced it.

### Written offer

For a period of three years from the distribution of any release of this application, the
author will provide, to anyone who asks, the complete corresponding source code for any
LGPL (or otherwise copyleft-licensed) library bundled in it — including the exact upstream
version and any patches applied — on a medium customarily used for software interchange, at
no charge beyond the cost of performing the distribution. Requests: open an issue at
<https://github.com/agustincico/opensmalltalk-android/issues>.

### The durable fix

This offer rests on binaries copied from a device, which is why several versions above are
not pinned. `scripts/build-vm-android.sh` already assembles its build sysroot by downloading
these same libraries from Termux's package repository, where every package is versioned and
checksummed by apt. Regenerating the *shipped* copies the same way would make every version
in this table exact and reproducible; see [docs/ROADMAP.md](docs/ROADMAP.md).

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

The LGPL side is covered by the [written offer](#written-offer) above. What is still worth
doing: pinning the four stripped libraries' versions (FriBidi, Graphite2, libiconv, LAME),
which the [durable fix](#the-durable-fix) would settle for all of them at once, and
collecting the per-version license texts. If you can contribute either — or you spot a
version that is wrong — please open a PR.

### Libraries that may not be needed at all

A dependency audit (2026-08-16) found ~5 MB of libraries with no reachable reference from
the VM, its plugins, or the JNI preload list: `libSDL2-2.0.so` and `libwm.so` (4.4 MB
between them), the Wayland trio, `libxkbcommon.so`, `libICE`/`libSM`, `libXcursor`,
`libXfixes`, `libXi`, `libXss` and `libdecor-0`. Dropping unused libraries is the cheapest
compliance win there is, since an obligation only exists for what is actually distributed.

**This is not as simple as deleting them.** A first attempt broke the boot, because the
analysis matched on file names while the loader resolves by `SONAME`, and because Android
only packages files from `jniLibs` whose name ends in exactly `.so` — `libz.so` carries
`SONAME libz.so.1` and is the copy that actually ships, while the `libz.so.1` sitting next
to it is never packaged at all. Any removal pass therefore has to resolve SONAMEs, model
that packaging rule, and be verified on a device one library at a time.
