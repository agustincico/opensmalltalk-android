# OpenSmalltalk Android

Run [Cuis](https://cuis.st) and [Squeak](https://squeak.org) Smalltalk — or a custom image
like [Dialogo](https://dialog.ar) — natively on an Android phone. No Termux, no desktop:
one APK that boots a real OpenSmalltalk VM and shows the live Smalltalk world on screen.

![Cuis University running on Samsung Galaxy A12](https://github.com/user-attachments/assets/78cb2c7f-c7a3-423a-a3c9-02b6d1e62064)

## Install

Download the signed APK from
[**Releases**](https://github.com/agustincico/opensmalltalk-android/releases/latest), open
it, and allow "install from unknown sources". Releases update each other in place (same
signing key) — point [Obtainium](https://github.com/ImranR98/Obtainium) at this repo for
automatic updates.

Requirements: **ARM64 phone** (arm64-v8a), **Android 9+** (API 28 — the level the VM's C
library calls need). Images must be **64-bit Spur** (32-bit images are rejected with a clear
message instead of crashing).

## What you can do

- **Pick an image on first launch** — download Squeak, a recent stable Cuis, or
  [Cuis University](https://sites.google.com/view/cuis-university), or open a `.image`
  already on your device. Nothing is bundled; every image you load stays in an on-device
  library and reopens offline with one tap (☰ → *Load image…*).
- **Bring code in**: ☰ → *File in code (.st)…* picks a fileout from your device and drops
  it into the **running** image, like desktop drag-and-drop — the image shows its own
  "Select action" menu (browse / file in).
- **Get code out**: *fileOut* in the image just works — no path dialogs — and the file
  appears in **`Downloads/OpenSmalltalk/`** automatically. Same for `.pck.st` / `.cs`.
- **Save Image** persists your session (app-private storage; it reboots into your saved
  state). Saved-as images show up in the library too.
- **Work with fingers**: two-finger tap or the ⊙ button = right click, an optional
  trackpad mode (relative cursor with hover), a precise-pointer offset for small targets,
  and a zoom picker with pixel-crisp integer scales. A soft-keyboard-aware view keeps the
  caret visible while typing.

Everything lives behind a small collapsible pill (bottom-right): **☰** options,
**⌨** keyboard, **⊙** right-click, **›** collapse. If an image ever renders blank, the
pill is the escape hatch — ☰ → *Load image…* always works.

## How it works

- The **OpenSmalltalk Stack VM** (interpreted Spur 64-bit, prebuilt for ARM64) is loaded
  in-process via JNI and launched against the chosen image.
- The VM renders through X11 into an **embedded X server written in Java** (a fork of
  [android-xserver](https://github.com/ZhymabekRoman/android-xserver-enhanced)) that
  paints into an Android view and translates touch into X input events.
- The app adds the phone conveniences on top: the image library, the drag-and-drop
  file-in (synthesized XDND), the automatic fileout export, crash-loop protection
  against bad images, and a per-boot setup script that adapts Cuis to the phone.

Known limitations: ARM64 only; the very latest Cuis *rolling* snapshots don't start their
UI on this VM yet (a recent upstream startup rework, already fixed upstream — the in-app
download is pinned to the newest Cuis base that works); thin targets like window-resize
edges remain fiddly with a finger (use Precise pointer / Trackpad mode).

## Building from source

```bash
git clone https://github.com/agustincico/opensmalltalk-android
cd opensmalltalk-android
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties   # adjust to your SDK
JAVA_HOME=/path/to/jdk-17 ./gradlew assembleDebug
```

The repo is self-contained (launcher, X server library, prebuilt native VM — no
submodules). Toolchain: **JDK 17**, AGP 8.7.3, Gradle 8.9 (wrapper included), NDK 26,
compileSdk/targetSdk 35, arm64-v8a only. A clone builds the same APK as the official
release; `./gradlew bundleRelease` produces the Play Store `.aab`.

The native VM is committed as a prebuilt, but it is **not** a mystery binary: it is built
from a pinned upstream commit by `scripts/build-vm-android.sh`, which cross-compiles the
OpenSmalltalk VM and all 20 of its plugins for Android with the NDK, on your desktop —
no phone or Termux install needed.

- [docs/DEV-LOOP.md](docs/DEV-LOOP.md) — the emulator dev loop (build → deploy →
  observe → drive input), including Smalltalk text-tests wired into logcat.
- [docs/BUILDING-VM.md](docs/BUILDING-VM.md) — the NDK cross-compile recipe, the four
  Bionic portability patches, and what still comes from Termux.
- [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md) — bundled binaries and licenses.
- [docs/ROADMAP.md](docs/ROADMAP.md) — open bugs, subtleties, UX backlog, and the
  Google Play path (cost + what modernization it needs).
- [docs/UPSTREAMING.md](docs/UPSTREAMING.md) — plan for contributing the Android build
  fixes back to the upstream OpenSmalltalk VM.
- `CLAUDE.md` — working notes: root causes, gotchas, backlog.

## License & credits

MIT for the code in this repo (see [LICENSE](LICENSE)); the bundled third-party binaries
keep their own licenses — see the notices file. Built on
[OpenSmalltalk](https://github.com/OpenSmalltalk/opensmalltalk-vm),
[Cuis Smalltalk](https://github.com/Cuis-Smalltalk/Cuis-Smalltalk-Dev), and Matthew
Kwan's android-xserver. Author: [@agustincico](https://github.com/agustincico).
