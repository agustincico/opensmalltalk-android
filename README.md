# OpenSmalltalk Android

[![Latest release](https://img.shields.io/github/v/release/agustincico/opensmalltalk-android?label=release&color=blue)](https://github.com/agustincico/opensmalltalk-android/releases/latest)

Run any OpenSmalltalk image (Cuis, Squeak) or custom project (like [Dialogo](https://dialog.ar)) on Android — as a native APK. No Termux at runtime.

**📦 [Download the signed APK from the latest release](https://github.com/agustincico/opensmalltalk-android/releases/latest)** — install it, pick an image (Squeak, Cuis, Cuis University or your own), done.

![Cuis University running on Samsung Galaxy A12](https://github.com/user-attachments/assets/78cb2c7f-c7a3-423a-a3c9-02b6d1e62064)

## Status

Working alpha, actively developed. The app is called **OpenSmalltalk** on the device.
Current release: **[v1.31](https://github.com/agustincico/opensmalltalk-android/releases/tag/v1.31)**
(first signed APK — 2026-08-09). Recent progress, roughly newest first:

- **Signed installable releases** (no more debug-mode sideloading); same key every release,
  so they update in place — [Obtainium](https://github.com/ImranR98/Obtainium)-friendly.
- **Download-first startup chooser**: Squeak 6.0, Cuis 7.7, **Cuis University**, or an
  image from the device — nothing bundled in the APK.
- **Full provenance of the shipped native binaries** recovered and documented
  ([THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md), [docs/BUILDING-VM.md](docs/BUILDING-VM.md)).
- Crash-on-launch fixed (native error-buffer overflow); bad 32-bit images rejected up front
  instead of crash-looping the app.
- Finger-friendly input (trackpad mode, precise pointer, one-tap right-click ⊙), zoom picker
  with pixel-crisp integer scaling, curated ☰ options menu, collapsible floating controls.
- Save Image verified working; ProcessPhoenix-based restart for clean image switching.

Verified on a Samsung Galaxy A12 (ARM64, Android 10) and on an ARM64 emulator
(`system-images;android-30;google_apis;arm64-v8a`).

| Image | Result |
|---|---|
| Cuis 5.0-4507, Cuis 6.0-6053, CuisUniversity-6350 | boots + runs |
| **Cuis 7.7-7976** (what the in-app download fetches) | boots + runs |
| **Squeak 6.0-22156 (64-bit)** | boots + runs |
| Dialogo (custom app image) | boots + runs |
| Cuis 7.9 rolling snapshots (7983–8090 incl. master) | boot but **never start their UI** on this VM (blank world; the mid-2026 startup rework — upstream fixed it in updates 8093/8094 AFTER the current snapshot; retest when a newer rolling image lands) |

## Requirements

- **Android 5.1+** (API 22)
- **ARM64 (arm64-v8a) device.** The VM is shipped for ARM64 only — the APK's
  `armeabi-v7a`/`x86_64` variants contain the JNI wrapper but no VM and cannot run an image.
- **64-bit Spur images only** (format magic `68021`, `68531` or `68533`). 32-bit / V3 images
  (`6521`, `6505`, `6504`) are rejected up front — see [Bad images](#bad-images-cant-brick-the-app).

## Quick start (phone)

1. Download the signed APK from
   [**Releases**](https://github.com/agustincico/opensmalltalk-android/releases)
   (or build it yourself — see [Building from source](#building-from-source)).
2. Open the downloaded file and allow **"Install from unknown sources"** when Android asks.
3. Open **OpenSmalltalk**. On first launch it shows a **Load image** dialog — it does
   *not* auto-boot anything:
   - **Squeak (download)** — newest `Squeak6.0-<build>-64bit` from files.squeak.org
   - **Cuis 7.7 (download)** — the newest Cuis base that runs here (see [Loading images](#loading-images))
   - **Cuis University (download)** — the latest [Cuis University](https://sites.google.com/view/cuis-university)
     release (image + changes + sources from its platform bundle)
   - **From device…** — pick a `.image` you already have (no storage permission needed)
4. Change image any time: **☰ → Load image…**

Downloads need internet (the app requests `INTERNET`; the only other permission is `WAKE_LOCK`).

> **Auto-updates:** point [Obtainium](https://github.com/ImranR98/Obtainium) at this repo's
> URL and it will offer each new GitHub release as an in-place update (all releases are
> signed with the same key).

## Using it with a finger

Smalltalk expects a mouse; a finger has no hover and is imprecise. The app adds:

**The floating pill** (bottom-right). Collapsed to a small `‹` handle so it doesn't cover the
world; tap it to slide out:

| Button | What it does |
|---|---|
| **☰** | Options dialog (below) |
| **⌨** | Show/hide the keyboard |
| **⊙** | Arm the **next tap as a right-click** (context menus) |
| **›** | Collapse again |

The pill is also the **escape hatch**: if an image renders blank, ☰ → *Load image…* still works.

**Mouse buttons from touch**

| Action | Result |
|---|---|
| Tap | Left click |
| **Two-finger tap** | Right click |
| **⊙ then tap** | Right click |
| **Volume Down** | Right click (hardware) |
| **Volume Up** | Left click (hardware) |

**Options dialog (☰)**

| Item | Notes |
|---|---|
| **Load image…** | The chooser above |
| **Zoom** | 1.0–4.0×. Whole numbers are marked *(sharp)* — see [Zoom](#zoom-and-sharpness) |
| **Smooth zoom** | Bilinear upscale — better for image-heavy worlds, softer text |
| **Trackpad mode** | Finger drives a *relative* cursor: slide = move (hover opens submenus), tap = click at the cursor, press-pause-drag = drag, two fingers = right click |
| **Precise pointer** | Pointer sits ~48 dp above your finger so it doesn't cover small targets |
| **Mouse pointer** | Always-visible arrow at the pointer position (on by default) |
| **Shared clipboard** | Android ↔ Smalltalk clipboard (on by default) |
| **Long-press menu** | The legacy CTRL+C/V/X/ESC popup (off by default — it fights Smalltalk's own press-and-hold) |
| **Screen orientation** | Lock portrait/landscape |

**Tips.** Thin targets (window resize edges, pane dividers) are still fiddly — turn on
*Precise pointer* or *Trackpad mode*. The soft keyboard pans the world up so the caret stays
visible while you type.

### Zoom and sharpness

Zoom renders the X screen at `physical / zoom` and scales it up with nearest-neighbour, so:

- **Whole-number zooms (2×, 3×, 4×) are pixel-crisp**; fractional ones (1.75×, 2.25×) look soft.
  The auto-default rounds to 0.5 (a 440 dpi phone starts at 2.0×).
- **Responsive images** (worlds that re-lay-out to the screen, e.g. Dialogo) only get *lower
  resolution* from zoom — they are sharpest at **1×**. Zoom enlarges fixed-size worlds.
- **Smooth zoom** helps image-heavy content that looks blocky.

## Loading images

- **Downloads.** Squeak scrapes `files.squeak.org/6.0/` for the newest 64-bit build.
  Cuis is **pinned to the newest base tag that works** — `#BaseForCuis7.8` (Cuis 7.7-7976 +
  `Cuis7.6.sources`) — because every 7.9 rolling snapshot so far (7983–8090) never starts
  its UI on this VM (see Known limitations).
  Cuis University resolves the latest release of
  [Cuis-University/Cuis-University](https://github.com/Cuis-University/Cuis-University) and
  extracts image + changes + sources from its platform zip.
- **From device…** uses the Storage Access Framework (no storage permission). The sibling
  `<name>.changes` next to the picked `.image` is copied automatically.
- **Every image you load stays in the library.** Downloads and device picks keep their real
  file names, so the *Load image* dialog lists them all — most recently used first — and any
  of them reopens **offline** with one tap. *Delete an image…* (in the same dialog) reclaims
  the space. An image saved inside Smalltalk under a new name (*Save Image as…* `Foo.image`)
  shows up in the library too.
- **Fileouts land in `Downloads/OpenSmalltalk/`.** Anything the image files out (`.st`,
  `.pck.st`, `.cs`) is copied there the moment it is written (Android 10+: via MediaStore, no
  permission needed; re-fileouts overwrite the copy), so your code leaves the app's private
  storage automatically.
- **File in code: ☰ → *File in code (.st)…*** picks a `.st`/`.cs` from the device, copies it
  into the image folder, and **drops it into the RUNNING image like a desktop drag-and-drop**
  (the embedded X server synthesizes the VM's XDND launch-drop). The image itself decides
  what to do — Cuis pops its *"Select action for …"* menu (browse code / open code changes /
  **file in**) right at the centred pointer. No restart, no interruption. If no image is
  running yet, the pick is queued instead and files in on the next image start
  (`FILEIN OK/ERROR` in logcat). The in-image FileList can't browse outside the app sandbox,
  so this is the way to bring code in.
- Images live in the app's **private** storage (`filesDir`). **Save Image works** (verified on
  Cuis 7.5: the file is rewritten and the saved state boots again). Exporting the `.image`
  itself is still pending — see [Known limitations](#known-limitations).

### Bad images can't brick the app

A 32-bit image makes the 64-bit VM abort the process; because the choice persisted, the app
used to die on every launch until reinstalled. Now:

- 32-bit/V3 formats are rejected when picked **and** at boot;
- a `.boot_pending` marker is written before the VM starts and cleared ~7 s later, so a boot
  that dies early is detected and the app returns to the chooser instead of crash-looping.

## How it works

Two components in one APK:

1. **OpenSmalltalk Stack VM** (interpreted, Spur 64-bit) — `libsqueak.so`, loaded via JNI.
2. **X11 server** embedded in the app (fork of
   [android-xserver-enhanced](https://github.com/agustincico/android-xserver-enhanced)),
   rendering into an Android `View`.

Boot sequence (`XServerActivity`):

```
X server starts (TCP 127.0.0.1:6000)
  → 500 ms → is there a .custom_image marker?
      no  → show the "Load image" chooser (the VM is never started)
      yes → crash-loop guard (.boot_pending) + 32-bit format check
          → prune a dangling ----STARTUP---- from the .changes tail
          → write .boot_pending → startVMNative() → clear it after 7 s
```

The VM is launched as:

```
squeak -plugins <filesDir>/plugins -display 127.0.0.1:0 <filesDir>/Cuis.image -ud <filesDir>
```

`-ud` points Cuis's *user base directory* at the writable `filesDir`; without it Cuis 6.x/7.x
die on `UserBaseDirectory assureExistence` at startup (Squeak and Cuis 5.0 ignore the flag).
`cwd`, `HOME` and `TMPDIR` are also set to `filesDir`, which is why *Save Image* works.
If `<filesDir>/dev-tests.st` exists, `-s <that file>` is appended (see
[docs/DEV-LOOP.md](docs/DEV-LOOP.md)).

### Architecture

```
Android App (Java)
├── XServerActivity            ← X11 server + image chooser + VM launch + options UI
├── RestartActivity            ← ":restart" process: kills and relaunches the app
│                                (the native VM cannot re-initialise in a live process)
├── XServerService             ← keeps the app alive with a notification
├── android-xserver-enhanced   ← X11 server in-process (library/) — ScreenView does
│                                rendering, zoom and touch→pointer mapping
└── squeak_jni.c (JNI bridge)  ← preloads the plugins, dlopen()s the VM, calls its main()
Native (ARM64)
├── libsqueak.so               ← the OpenSmalltalk Stack VM (see note below)
├── vm-display-X11.so          ← VM display plugin
├── *.so                       ← other VM plugins
└── ~50 dependency libs        ← X11/cairo/pango/glib etc. (see THIRD-PARTY-NOTICES.md)
```

> `libsqueak.so` is the VM's `squeak` executable **renamed**; the JNI bridge `dlopen()`s it and
> enters through `dlsym(handle, "main")`.

**Switching images restarts the app.** The native VM can't be re-initialised in a running
process, so `RestartActivity` (in its own `:restart` process) kills the old process, waits for
port 6000 to free, and launches a fresh one. Android 10+ blocks the older
background-relaunch approaches.

## Building from source

### Prerequisites

- **JDK 11** — what `scripts/loop/build.sh` uses and the validated configuration.
  JDK 8 will not work (the Gradle daemon needs `--add-opens`).
- **Android SDK** with these packages:

```bash
sdkmanager "platforms;android-29" "build-tools;30.0.3" \
           "ndk;22.0.7026061" "cmake;3.22.1" "platform-tools"
```

The NDK/CMake versions are pinned in `app/build.gradle`; AGP 4.2.2 / Gradle 7.4.2 are pinned
in the wrapper. On Apple Silicon, NDK 22 ships x86_64 binaries — install Rosetta 2
(`softwareupdate --install-rosetta`).

### Build the APK

```bash
git clone https://github.com/agustincico/opensmalltalk-android
cd opensmalltalk-android
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties   # adjust to your SDK
JAVA_HOME=$(/usr/libexec/java_home -v 11) ./gradlew assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

The repo is self-contained: launcher, X11 server library and the prebuilt native VM are all
included. No submodules.

### No Smalltalk image ships in the APK

`*.image` / `*.changes` / `*.sources` are gitignored (they are large binaries) and the app
deliberately bundles **no** offline image — every image is downloaded (or picked from the
device) at first launch via the **Load image** chooser. So a fresh clone builds the exact
same APK as the official release, and the download options (Squeak / Cuis 7.7 /
Cuis University) are the supported way to get an image onto the device.

### Release builds

`assembleRelease` works out of the box and produces an **unsigned** APK. To sign it, copy
`app/keystore.properties.example` to `app/keystore.properties` and point it at your own
keystore (both that file and `*.jks` are gitignored). The APKs on the
[Releases](https://github.com/agustincico/opensmalltalk-android/releases) page are signed
with the maintainer's key, so they can update each other in place; a locally-signed build
must be installed after uninstalling the official one (different signature).

## Development

There is a low-intervention dev loop (build → headless ARM64 emulator → deploy → screenshot +
logcat → drive real touch/keys), plus a Smalltalk text-test hook whose results land in logcat.

```bash
./scripts/loop/loop.sh              # full cycle
./scripts/loop/loop.sh --observe-only
```

See **[docs/DEV-LOOP.md](docs/DEV-LOOP.md)**. Working notes, design rationale and the detailed
backlog live in [CLAUDE.md](CLAUDE.md).

## Rebuilding the VM

The prebuilt VM and plugins are committed. Rebuilding them is **partially reproducible** —
read **[docs/BUILDING-VM.md](docs/BUILDING-VM.md)**, which documents what the shipped binaries
actually are, how they were produced (Termux on an ARM64 phone), and precisely which parts are
not yet reproducible.

## X11 server fork

Modified from [android-xserver-enhanced](https://github.com/ZhymabekRoman/android-xserver-enhanced).
Key changes for OpenSmalltalk compatibility:

- **TrueColor 32bpp visual** — the Squeak/Cuis VM requires Visual class 4, depth 32; anything
  else gives a blank screen
- **Public `processRequest()`** — exposed for external dispatch
- **Dynamic resize handling** — sends `ConfigureNotify` when the screen size changes, and
  applies the initial resize once a client maps a window (fullscreen from the first launch)
- **Touch/zoom layer** — display scaling, touch→pointer mapping, trackpad mode, an
  always-visible pointer, and IME-aware panning

See: https://github.com/agustincico/android-xserver-enhanced

## Known limitations

- **ARM64 only**; images must be 64-bit Spur.
- **No `.image` export.** The image itself lives in private app storage, so a saved image
  can't be copied off the device yet. (Fileouts DO leave the device — they are auto-copied to
  `Downloads/OpenSmalltalk/` — and *Save Image as…* under a new name now appears in the
  in-app library.)
- **UI preferences reset on restart** (zoom, trackpad, precise pointer, …).
- **Cuis 7.9 rolling snapshots (7983–8090) never start their UI here** (blank world, only the
  idle process runs — the mid-2026 startup-sequence rework; upstream already fixed it in
  updates 8093/8094, newer than the current master snapshot). The in-app download is pinned
  to 7.7-7976, the newest base that works; retest master when a new rolling image lands.
- **Fine targets** (window resize edges, pane dividers) remain fiddly with a finger.
- **The in-image FileList is of limited use**: it opens at `/`, which an Android app cannot
  enumerate, and it can never browse outside the app sandbox. Use **☰ → File in code (.st)…**
  to bring code in, and the automatic fileout export to get code out.
- `XDisplayControlPlugin.so` fails to `dlopen` (it is over-linked against libs that aren't
  shipped). Harmless — nothing depends on it.
- Benign log noise: `pthread_setschedparam failed` (the VM can't get realtime priority) and
  `Xlib: extension "RANDR" missing` (the embedded server has no RANDR).

## Reproducibility caveats

Honest disclosure of what is *not* reproducible from this repo today:

- **The ~100 bundled native libraries** (X11, cairo, pango, glib, …) were harvested from a
  **Termux** installation on an ARM64 phone. There is no package list, version manifest or
  harvesting script, so they cannot be rebuilt or audited from source as-is.
- **The VM and the plugins come from two different checkouts**: `libsqueak.so` was built from
  an `opensmalltalk-vm` tree (Stack/Spur, upstream tag `r3732`), while every plugin was built
  from a separate `opensmalltalk-vm-cog-clean` tree (`squeak.cog.spur`). Only the VM side has
  a documented patch script.
- **No pinned upstream commit.** `scripts/apply-fixes-stack.sh` patches some files by absolute
  line number, so it is only valid for the revision it was written against, which isn't recorded.

See [docs/BUILDING-VM.md](docs/BUILDING-VM.md) for the full picture and what would close these gaps.

## License

MIT — see [LICENSE](LICENSE).

The APK also redistributes third-party native libraries under their own licenses (LGPL and
others). See **[THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)**.

## Author

Agustin Martinez

## Acknowledgements

- [OpenSmalltalk VM](https://github.com/OpenSmalltalk/opensmalltalk-vm)
- [android-xserver-enhanced](https://github.com/ZhymabekRoman/android-xserver-enhanced) by ZhymabekRoman
- [Cuis University](https://sites.google.com/view/cuis-university/)
- Funded by [FAST](https://www.fast.org.ar) — Fundación Argentina de Smalltalk
