# CLAUDE.md — dev loop & project notes

Guidance for working in **opensmalltalk-android**: run any OpenSmalltalk/Cuis
image (and the *Dialogo* app) on Android as a native APK — no Termux. Interpreted
**Stack VM** loaded via JNI (`app/src/main/cpp/squeak_jni.c`) + an embedded X11
server (fork of android-xserver-enhanced, in `library/`) that renders Smalltalk
into an Android View. Boot activity: `au.com.darkside.x11server.XServerActivity`.

This file documents the **low-intervention dev loop** in `scripts/loop/`: compile
→ deploy to an emulator → observe (screen + logs) → test, with no manual
USB/phone round-trips. You watch it live via scrcpy; the loop verifies itself via
screenshots, logcat, and Smalltalk text-tests.

---

## Quickstart

```bash
# Full APK cycle: build → boot emulator (headless) → deploy → mirror → observe
./scripts/loop/loop.sh

# Iterate ONLY the Smalltalk image (no APK rebuild): push a new image + tests
./scripts/loop/loop.sh --image <some-cuis-6.x>.image --changes <..>.changes \
    --st scripts/loop/dev-tests.st

# Just refresh the screenshot + logs of whatever is running
./scripts/loop/loop.sh --observe-only
```

Artifacts land in `.loop/` (gitignored): `screen.png`, `logcat.txt`, timestamped
history under `.loop/history/`, plus `emulator.log` / `scrcpy.log`.

---

## The scripts (`scripts/loop/`)

| Script | What it does |
|---|---|
| `env.sh` | Sourced by all others. Auto-detects SDK + the two JDKs; defines `PKG`, `ACTIVITY`, `AVD_NAME`, `FILES_DIR`, `$ADB`, helpers. Run it directly to print resolved config. Everything overridable via env vars. |
| `emulator.sh` | Create the arm64 AVD if missing, boot it **headless**, wait `sys.boot_completed`, `adb root`. Idempotent (reuses a running device). `--window` to see the emulator, `--wipe` for a cold boot. |
| `build.sh` | `./gradlew assembleDebug` with **JDK 11**. Gradle is incremental (~2s no-op). Needed only when Java / X11 / C sources or embedded assets change. |
| `deploy.sh` | Install the APK, relaunch, and defeat the asset-extraction race (see below). `--fresh` uninstalls first (clears filesDir → re-extracts assets). This path assumes the **embedded** image (`app/src/main/assets/Cuis.image`). |
| `observe.sh` | The two verification channels: `screencap` → `.loop/screen.png`, filtered `logcat` (Cuis/SQUEAK/SQUEAK_VM) → `.loop/logcat.txt`. Prints a health line (`vm_argv`, `image_open_fail`, `crash`) and surfaces any `DEVTEST` lines. `--stamp` keeps history, `--clear` resets the log buffer first. |
| `input.sh` | Real touch/keyboard via adb (exercises the X-server → Cuis path): `tap X Y`, `swipe …`, `longpress …`, `text "…"`, `key ENTER|BACK|ESCAPE|…`, `back`, `home`. A tap on the empty Cuis desktop opens the World menu — a handy smoke test. |
| `push-image.sh` | Iterate the **Smalltalk side without an APK rebuild**: `adb root` + push a `.image` (and `--changes`, `--st`) into filesDir, fixing owner + SELinux label so the app can read it, then relaunch. Warns if the image isn't 64-bit Spur. |
| `mirror.sh` | Keep a live **scrcpy** window open (idempotent). scrcpy mirrors the whole device, so it survives relaunches/reinstalls and auto-shows the latest build; it only drops if the emulator/adbd restart. `--restart`, `--status`. |
| `loop.sh` | Orchestrates the above. Modes: default (APK), `--no-build`, `--fresh`, `--image` (Smalltalk-only), `--observe-only`, `--window`. |
| `dev-tests.st` | Sample text tests (see below). |

---

## Two verification channels (how each change is checked)

1. **Screen** — `adb exec-out screencap -p` → PNG. Confirms the Cuis desktop/UI
   visually; diff before/after to detect UI effects.
2. **Logs** — `adb logcat -s Cuis SQUEAK SQUEAK_VM`. Tags: `Cuis` (Java side),
   `SQUEAK` (JNI), `SQUEAK_VM` (VM stdout/stderr). Shows the VM argv, image-open
   status, crashes, and **DEVTEST** results.

### Smalltalk text-tests → logcat (the machine-checkable channel)

`squeak_jni.c` appends `-s <filesDir>/dev-tests.st` to the VM argv **only when
that file exists** in filesDir. So:

- Put a `dev-tests.st` in filesDir (via `push-image.sh --st …` or `loop.sh --st …`).
- The image evaluates it at startup; output goes to stdout → logcat (`SQUEAK_VM`).
- Use `StdIOWriteStream stdout` for output (NOT `Transcript` / `FileStream stdout`
  — only `StdIOWriteStream` reaches the Android log). Prefix every line `DEVTEST`.
- `observe.sh` surfaces every `DEVTEST` line; the sample prints `PASS/FAIL/ERROR`
  and a `SUMMARY pass=N fail=M`.

**Requires a Cuis 6.x image.** Cuis 5.0/4507 predates `-s`/`-d` command-line
options, and creating classes this early in startup hangs — so tests are plain
assertions (no class definitions). Production images without a `dev-tests.st`
present are unaffected: the argv stays the original 6 elements.

---

## Toolchain (pinned — do NOT bump)

- **JDK 11** for the Gradle build (AGP 4.2.2 breaks on newer). `env.sh` finds it
  via `/usr/libexec/java_home -v 11` (override with `JAVA11_HOME`).
- **JDK 17+** for the Android cmdline-tools (`sdkmanager`/`avdmanager`, compiled
  class 61.0). `SKIP_JDK_VERSION_CHECK` is NOT enough — they need a real 17+ JVM.
  `env.sh` uses `java_home -v 17` (override `JAVA_CMDLINE_HOME`).
- AGP 4.2.2 / Gradle 7.4.2 (wrapper included), NDK 22.0.7026061, CMake 3.22.1,
  compileSdk android-29, build-tools 30.0.3.
- **arm64-v8a only**: `libsqueak.so` and friends live in `jniLibs/arm64-v8a`. The
  emulator/device MUST be arm64-v8a (the gradle `abiFilters` also lists x86_64 /
  armeabi-v7a, but only the JNI wrapper is built for those — no `libsqueak.so`).
- Emulator system image: `system-images;android-30;google_apis;arm64-v8a`
  (**google_apis**, not google_play, so `adb root` works for `push-image.sh`).
- AVD used: `cuis-arm64` (pixel_5, `hw.keyboard=yes`).

On this Mac the SDK is `~/Library/Android/sdk` (self-contained, incl.
`cmdline-tools;latest`). `local.properties` → `sdk.dir=…`. Scripts run under
macOS **bash 3.2**, so array expansions use the `${arr[@]+"${arr[@]}"}` idiom.

---

## Key facts / gotchas

- **64-bit Spur image required.** `libsqueak.so` is a *Production Spur 64-bit VM*,
  so the image must be 64-bit Spur (format magic **68021**); 32-bit Spur (6521)
  silently fails. `push-image.sh` checks this. The VM happily runs Cuis 5.0/6.0/6.x
  64-bit images (verified: 4507, 6053, CuisUniversity-6350).
- **Images are gitignored** (`*.image`/`*.changes`/`*.sources`, "download
  separately"). The app expects `app/src/main/assets/Cuis.image` (+ `.changes`);
  `extractAssets()` copies every top-level assets file into filesDir on first boot.
- **Asset-extraction race:** `extractAssets()` runs on a background thread while
  the VM launches ~500ms later. On a fresh install the ~22MB image+changes copy
  can lose the race → *"Could not open the Squeak image file"*. `deploy.sh` defeats
  it: wait for the extracted image to reach full size, then relaunch. `push-image.sh`
  sidesteps it (writes filesDir directly before launch).
- **Keep scrcpy on root, not toggling:** `emulator.sh` roots adbd once at boot so
  `push-image.sh` doesn't restart adbd each time (which would blink scrcpy).

---

## Backlog (UX — deferred until the setup is solid)

From the README "Known limitations" plus what the loop surfaced:

1. **Fullscreen only after a rotation** — the Cuis world renders in the top band
   (its saved size); the rest is the gray X root. Likely tied to #2.
2. **`XDisplayControlPlugin.so` fails to load** (`dlopen … (null)` in logcat) —
   probably the display resize/fullscreen control. Display itself works via
   `vm-display-X11.so`.
3. **Touch/menus hard to hit with a finger** — small hit targets; menu ergonomics.
4. **No runtime image picker** — the image is embedded in the APK; add in-app
   selection/import instead of rebuilding.
5. **File-write errors depending on storage permissions.**

Non-issues (benign, ignore): `pthread_setschedparam failed: Operation not
permitted` (VM can't get realtime prio; falls back to itimer) and
`Xlib: extension "RANDR" missing` (the embedded X server has no RANDR).

## Reproducibility

Third parties must be able to rebuild everything, **including the VM from the
original opensmalltalk-vm sources** (see `scripts/apply-fixes-stack.sh`). Document
anything you add. The dev-loop scripts auto-detect the toolchain and are overridable
by env var; the Smalltalk text-tests need any vanilla Cuis 6.x image (e.g.
`Cuis6.0-6053`) pushed with `--st scripts/loop/dev-tests.st`.
