# Third-party notices

The source code in this repository is MIT licensed (see [LICENSE](LICENSE)). **The APK also
redistributes prebuilt third-party native libraries, which are covered by their own licenses,
not by MIT.** This file discloses what is bundled so redistributors can comply.

> **Status: incomplete.** The binaries in `app/src/main/jniLibs/arm64-v8a/` and
> `app/src/main/assets/plugins/` were harvested from a **Termux** installation on an ARM64
> phone (see [docs/BUILDING-VM.md](docs/BUILDING-VM.md)), and **no package/version manifest was
> kept**. The list below is derived from the file names; exact upstream versions and license
> texts still have to be recovered. If you redistribute this APK, verify these yourself.

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

## How to help

If you can identify the exact upstream versions (e.g. from the Termux device that produced
these binaries via `pkg list-installed`), please open a PR replacing this file's estimates with
the real versions and license texts.
