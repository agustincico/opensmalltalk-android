# CLAUDE.md — dev loop & project notes

> **Planning a next step?** The consolidated backlog (open bugs, subtleties, UX,
> Google Play path) lives in [`docs/ROADMAP.md`](docs/ROADMAP.md). This file is the
> deep working notes.

Guidance for working in **opensmalltalk-android**: run any OpenSmalltalk/Cuis
image (and the *Dialogo* app) on Android as a native APK — no Termux. Interpreted
**Stack VM** loaded via JNI (`app/src/main/cpp/squeak_jni.c`) + an embedded X11
server (fork of android-xserver-enhanced, in `library/`) that renders Smalltalk
into an Android View. Application ID: **`ar.com.opensmalltalk`** (reverse-DNS of the
user's opensmalltalk.com.ar; changed 2026-08-10 — the old `au.com.darkside.x11server`
identity is a different app to Android, so it needed a fresh install). The Java
packages KEEP the fork's original `au.com.darkside.*` namespace (attribution + JNI
symbol stability); boot activity class: `au.com.darkside.x11server.XServerActivity`.

This file documents the **low-intervention dev loop** in `scripts/loop/`: compile
→ deploy to an emulator → observe (screen + logs) → test, with no manual
USB/phone round-trips. You watch it live via scrcpy; the loop verifies itself via
screenshots, logcat, and Smalltalk text-tests.

**Audience split.** These are *working notes* (rationale, root causes, backlog).
User- and contributor-facing docs live elsewhere and should be kept in sync:
`README.md` (install, use, build, caveats), `docs/DEV-LOOP.md` (this loop, for
outside contributors), `docs/BUILDING-VM.md` (native provenance + rebuild),
`THIRD-PARTY-NOTICES.md` (bundled binaries).

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
| `build.sh` | `./gradlew assembleDebug` with **JDK 17**. Gradle is incremental (~2s no-op). Needed only when Java / X11 / C sources or embedded assets change. |
| `deploy.sh` | Install the APK and relaunch. `--fresh` uninstalls first (clears filesDir). (It used to also defeat an asset-extraction race; that's gone — the image is no longer auto-extracted, the startup chooser copies it on demand.) |
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

## Toolchain (modernized 2026-08-12 for Google Play — see docs/ROADMAP.md)

> The old pin (JDK 11 / AGP 4.2.2 / compileSdk 29) is GONE. Play requires
> targetSdk 35, which requires AGP 8.x, which requires JDK 17.

- **JDK 17** for the Gradle build. AGP 8 rejects 11, and Gradle 8.9 rejects 25 —
  and macOS `java_home -v 17` means "17 **or newer**", so `env.sh` version-checks
  each candidate and only accepts a real 17/21 (override with `JAVA17_HOME`).
  This Mac has it unpacked at `~/.local/jdks/jdk-17*` (brew cask needs sudo).
- **JDK 17+** for the Android cmdline-tools (`sdkmanager`/`avdmanager`, compiled
  class 61.0). `SKIP_JDK_VERSION_CHECK` is NOT enough — they need a real 17+ JVM.
  `env.sh` uses `java_home -v 17` (override `JAVA_CMDLINE_HOME`).
- AGP 8.7.3 / Gradle 8.9 (wrapper included), NDK 26.2.11394342, CMake 3.22.1,
  compileSdk + targetSdk 35, build-tools 35.0.0.
- AGP 8 specifics applied: `namespace` in both modules (`au.com.darkside.x11server`
  / `au.com.darkside.xserver` — NOT the applicationId, the R class lives there),
  `package=` removed from both manifests, explicit `android:exported` on every
  component, `packagingOptions` → `packaging`, `jcenter()` → `mavenCentral()`.
- **arm64-v8a only** (`abiFilters`): `libsqueak.so` exists for arm64 alone, so the
  old armeabi-v7a/x86_64 variants could never run an image — they only let Play
  offer the app to devices where it cannot work. Dropping them also cut ~5 MB.
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
  separately") and since 2026-08-09 **no image is bundled at all** — the chooser
  is download-first (Squeak / Cuis 7.5 / Cuis University / From device…);
  `extractAssets()` still copies other top-level assets (plugins) into filesDir.
- **~~Asset-extraction race~~ — gone.** `extractAssets()` used to copy the ~22MB
  image on a background thread while the VM launched ~500ms later, so a fresh
  install could lose the race → *"Could not open the Squeak image file"*, and
  `deploy.sh` had to wait for the full size then relaunch. `extractAssets()` now
  **always skips** `Cuis.image`/`Cuis.changes` (the startup chooser copies an image
  on demand), so there is no race and `deploy.sh` just installs + launches.
- **Keep scrcpy on root, not toggling:** `emulator.sh` roots adbd once at boot so
  `push-image.sh` doesn't restart adbd each time (which would blink scrcpy).

---

## Backlog (UX — deferred until the setup is solid)

From the README "Known limitations" plus what the loop surfaced:

1. **~~Fullscreen only after a rotation~~ — DONE** (commit "Fullscreen from
   startup"). Root cause: at startup `ScreenView.onSizeChanged` fires
   `notifyClientsScreenResize` *before* the client maps a top-level window, so
   nothing resizes; no further resize until a rotation. Fix: `ScreenView` polls
   post-startup until a viewable top-level window exists, then applies the same
   resize a rotation does — once. Verified: world fills the screen from launch.
2. **~~`XDisplayControlPlugin.so` fails to load~~ — fixed 2026-08-12 by the NDK rebuild.**
   Cause was over-linking: `NEEDED` listed `libSM.so`, `libICE.so`,
   `libandroid-execinfo.so`, none shipped in `assets/plugins/`. Rebuilding the plugin from
   the pinned upstream tree (`scripts/build-vm-android.sh`) drops all three; its `NEEDED`
   is now entirely libraries the APK ships.
3. **Touch/menus hard to hit with a finger** — *improved:* a **Zoom** picker
   (ScreenView `_displayScale`) renders the X screen at `physical/scale` and scales
   it up (nearest-neighbour), so widgets are bigger and tappable; touch is mapped
   physical→logical so hits stay precise. The upscale means WHOLE-number zooms
   (2×, 3×) are pixel-crisp while fractional ones (1.75×, 2.25×) look soft — so the
   auto-default rounds to 0.5 (→2.0× on a 440dpi phone) and the ☰ *Zoom* item opens
   a picker (1.0–4.0×, whole numbers tagged "sharp"). A **"Smooth zoom"** toggle
   switches the upscale to bilinear (better for image-heavy content that looks
   blocky with nearest). Big+native-sharp would need image-side HiDPI (Cuis/Squeak
   UI scale). **Responsive images** (e.g. Dialogo) re-lay-out to the logical screen
   size, so zoom just lowers their render resolution (blocky) instead of enlarging —
   they're sharp at 1×; the upscale-zoom only enlarges FIXED-size worlds.
   Follow-ups: persist the choice
   (SharedPreferences); long-press-to-aim precision cursor (now the Precise-pointer
   toggle + Trackpad mode, see #5).
4. **~~No runtime image picker~~ — DONE**, extended into a **startup chooser**.
   With no image chosen yet (no `.custom_image` marker) the app shows a **Load
   image** dialog on launch instead of auto-booting anything: *Squeak (download)*,
   *Cuis 7.5 (download)*, *Cuis University (download)* (latest GitHub release of
   Cuis-University/Cuis-University, windows64 zip → image+changes+sources), *From
   device…* (SAF, no storage permission). The bundled-offline option was removed
   2026-08-09 (app renamed **OpenSmalltalk**). The same dialog is reachable any time
   from the ☰ menu → *Load image…*. Picking one copies the `.image` (+ its sibling
   `<name>.changes`, auto-assumed for device picks) into filesDir, sets the
   `.custom_image` marker (so `extractAssets()` never clobbers it), and restarts
   to boot it.
   **Restart (the "elijo Cuis y se cierra" bug) — the recipe changed.** The old
   `exit(0)`-races-auto-restart / go-HOME-+-AlarmManager approach is **blocked on
   Android 10+**: a backgrounded app can't start an activity (`Background activity
   start … isBgStartWhitelisted: false`), so the relaunch never fired. Fixed with
   the **ProcessPhoenix** technique — `RestartActivity` in its own `:restart`
   process (manifest `android:process=":restart"`), started while we're still
   foreground; it kills the old app process, waits ~500ms for the OS to free the X
   port (6000), then starts `XServerActivity` fresh (a foreground start, allowed)
   and finishes after a short delay (killing our own process immediately would
   cancel the pending launch). See `RestartActivity` + `XServerActivity.restartApp`.
   **Cuis download pinned to a stable version:** master HEAD (Cuis 7.9-8090)
   renders a **blank white world** on the embedded X server (confirmed on a real
   phone too; the image boots + responds to taps but never draws — an upstream
   Cuis compat issue). **DIAGNOSED 2026-08-10** (bisect with rolling snapshots +
   native backtraces + a 27-agent source sweep): NOT a pixel-format problem.
   Cuis7.7-7976 (tag `#BaseForCuis7.8`) works perfectly; every 7.9 rolling
   snapshot (7983, 8064, 8090=master) **never launches its UI process** on this
   VM — a SEGV-triggered VM stack dump shows only `ProcessorScheduler
   idleProcess` running; no world draw, no `-s` script processing, and 8064+
   additionally exception-storm (`primitiveFindHandlerContext` spinning at ~95%
   CPU). Cause: the mid-2026 **startup-sequence rework** (updates 8042/8056/8088
   area) interacting with this platform; upstream shipped startup FIXES
   8093/8094 days AFTER the 8090 snapshot, so current master is a known
   mid-rework image. NOT the culprit (all tested): missing .sources, image
   header/format (identical), our post-startup fullscreen resize (disabled →
   still blank), 24-bit alpha. So *"Cuis 7.7 (download)"* now fetches
   `?ref=%23BaseForCuis7.8` (Cuis7.7-7976 + Cuis7.6.sources). **Retest master
   when Cuis publishes a rolling image containing ≥8094.** Diagnostic tooling
   left in the tree: XPutImage logs (Drawable, budget-limited) and — trick worth
   remembering — `kill -SEGV <interpreter TID>` makes the VM print the Smalltalk
   stack to logcat (find the TID via `debuggerd -b`; plain SIGUSR1 is eaten by
   ART).
   **Bad image no longer bricks the app:** a 32-bit image made the 64-bit VM abort
   the process every launch (unusable until reinstall). Startup now rejects 32-bit
   images (format-magic 6521/6505/6504) up front and uses a `.boot_pending`
   crash-loop guard (written before `startVMNative`, cleared ~7s in once healthy);
   a launch that finds it still set drops the marker and returns to the chooser.
5. **Floating controls are collapsible, bottom-right.** The ☰ (options) / ⌨
   (keyboard) buttons sat over the world; now they collapse to a minimal `‹` handle
   in the bottom-right corner that slides them out on tap. Doubles as an **escape
   hatch** from a blank-rendering image (☰ → *Load image…* still works).
   The ☰ button opens a **curated, opaque** options dialog (`showOptionsDialog`),
   not the old translucent Android panel full of X-server legacy items: Load image,
   Zoom, Trackpad mode, Precise pointer, Mouse pointer, Shared clipboard, Long-press
   menu, Screen orientation. **Keyboard** no longer hides what you type — a
   global-layout listener pans the X view up to keep the caret above the IME.
   **Finger control** (both opt-in toggles, default off): *Trackpad mode* — the
   finger drives a relative cursor (slide=move+hover→opens submenus, tap=click at
   the cursor, press+pause+drag=drag, 2 fingers=right-click); *Precise pointer* —
   the pointer sits ~48dp above the finger so it doesn't occlude small targets
   (window close box). Follow-up: persist these toggles (they reset each restart)
   and tune trackpad with real-finger feedback. See `ScreenView.handleTrackpadTouch`.
   **Right-click made easy:** a **⊙** button in the pill arms the next tap as a
   right-click (button 3) for context menus; the two-finger-tap right-click was also
   fixed (release the first finger's button-1 before sending button-3). Window
   resize/drag works (title-drag moves a window); grabbing THIN resize edges / pane
   dividers is a precision problem — use Precise-pointer / Trackpad mode.
6. **Save Image works (to filesDir).** Tested on Cuis 7.5: World menu → *Save
   Image* writes `filesDir/Cuis.image` (size/mtime change, Transcript logs
   `----SNAPSHOT----`, no permission error) and the saved state boots on relaunch.
   *Save Image as…* pops a "New file name?" dialog defaulting to `Cuis.image`
   (also filesDir). So the old "file-write errors depending on storage
   permissions" does NOT reproduce here — the image lives in the app's private,
   writable filesDir. **2026-08-09 update — image library + fileout export:** the
   app now boots whatever image `.custom_image` NAMES (empty marker = legacy
   `Cuis.image`); every downloaded/picked image keeps its real filename, the Load
   dialog lists them all for one-tap OFFLINE reopening (+ *Delete an image…*), and
   *Save Image as…* under `<name>.image` shows up in that list. Fileouts
   (`.st`/`.pck.st`/`.cs` written anywhere under filesDir, one subdir level) are
   auto-copied to `Downloads/OpenSmalltalk/` via a FileObserver + MediaStore (no
   MIME type on insert — text/plain makes MediaStore rename `.st` → `.st.txt`).
   **Remaining gap:** the `.image` itself still can't leave the phone (a future
   "Export image" share-sheet / SAF copy).
   **File in (2026-08-09, v1.34 = instant drop):** ☰ → *File in code (.st)…* →
   SAF pick → copy into filesDir → `ScreenView.dropFile()` synthesizes the VM's
   **XdndSqueakLaunchDrop** ("leaves out the 8 step dance", sqUnixXdnd.c r3732):
   the absolute path goes in a property of that name ON OUR clientless clipboard
   server window (type XA_ATOM(!), format 8, trailing NUL) and ONE ClientMessage
   with data.l[0]=source-window-id announces it; the VM reads the property
   (observable via the XProperty GetProperty log), records the image DropFiles
   event itself, and acks. **Two gotchas, both learned the hard way:** (1) the
   image dispatches the drop AT THE VM's LAST MOUSE POSITION and
   `DropFilesEvent>>dispatchWith:` REJECTS positions outside the world — so
   dropFile warps the pointer to (w/2, h/3) first; (2) that warp must be TWO
   `updatePointerPosition` calls (first may emit only Enter/Leave on window
   change; only the second, same-window move emits the MotionNotify the VM
   tracks). Cuis pops "Select action for <file>" (browse code / open code
   changes / file in) at the pointer — tap "file in" and the code runs in the
   LIVE image (verified: FILEINPROBE done value=42, no restart). Fallback when
   no VM runs: `pending-filein.st` via the `-s` hook (priority over
   dev-tests.st; consumed by the boot-healthy timer; Cuis 6+ only).
   **Why not FileList:** it opens at `/` which the app can't enumerate, and the
   sandbox is unreachable from it. (Driving Cuis menus in tests: taps alone
   never open submenus; use `input motionevent` DOWN/MOVE/UP press-hold-drag.
   Modal PopUpMenus DO respond to plain taps.)

## Squeak file-in (drop into running image) — OPEN, hard

Cuis file-in-on-drop works (v1.34, XdndSqueakLaunchDrop → DropFilesAction menu).
**Squeak does NOT**: the launch-drop lands in `MorphicProject>>launchSystemFiles:
event:` (Squeak reads a drop with no prior in-app drag as a *singleton relaunch*
and shows "Cannot start a second instance…"). Patching that method is the fix,
BUT delivery to Squeak failed every way tried (2026-08-10):
- `-s <file>` is a **Cuis-only** option — Squeak's `DoItFirst` maps it to
  `#ignore` (verified in SqueakV60.sources). So android-setup.st never runs on
  Squeak.
- `--filein <file>` IS honoured by Squeak's DoItFirst, but (a) it does NOT
  evaluate plain doit chunks (a minimal `(FileStream forceNewFileNamed:…) …!`
  chunk never ran), and (b) a direct method-definition chunk
  (`!MorphicProject methodsFor:…!…! !`) **crashed the VM during boot** (app went
  to the launcher). Also `-ud <dir>` (Cuis opt) leaves a bare `<dir>` token that
  can derail Squeak's arg parser — pass `--filein` BEFORE `-ud` if retried.
- Non-ASCII (em-dash) in a filed-in file → "Unmatched comment quote" (chunk
  reader is byte-oriented). Keep any Squeak-filed file ASCII.
**The right fix is the FULL XDND drag** (send XdndEnter→Position→Drop + serve the
XdndSqueakSelection via Selection.java): that sets Squeak's `externalDropMorph`
so the drop routes to `ExternalDropHandler` (native `.st` file-in) — no image
patch, and Cuis's DropFilesAction still works. The launch-drop was a shortcut
that only Cuis tolerates. Reference: sqUnixXdnd.c dndInEnter/Position/Drop +
dndGetSelection (scratchpad had it). Not yet implemented; risk to the working
Cuis path, so do it behind a test.

## Open items from the 2026-08-09 repo audit

Fixed that day (see git log): the `last_error` strcat overflow that SIGABRT'd the
VM startup; the empty `dlopen`/`dlsym` error branches (NULL call → SIGSEGV);
`getLastError` never declared on the Java side; `push-image.sh` not writing
`.custom_image` (so the documented flow showed the chooser instead of booting);
`apply-fixes-stack.sh` resolving to the wrong directory; release keystore
passwords committed in `app/build.gradle`.

Still open, roughly by value:

1. **~~Native provenance~~ — closed 2026-08-12.** The VM *and* all 20 plugin/display/sound
   modules are now cross-compiled from **one** pinned upstream tree
   (`opensmalltalk-vm` `Cog` @ `a4d3da0`, `squeak.stack.spur`) with the Android NDK, by
   `scripts/build-vm-android.sh`, on a desktop — no phone, no Termux install, no
   line-addressed `sed`. Verified: aarch64 PIE exporting `main`, `NEEDED` identical to the
   old `libsqueak.so`, all 21 artifacts 16 KB-aligned, boots Cuis 7.7 with working touch.
   Only the ~60 X11/cairo/pango **support libraries** are still Termux prebuilts — but the
   build script now pulls its sysroot from Termux's package repo, so moving those over is a
   small next step. See `docs/BUILDING-VM.md`.
2. **Persist UI preferences** (zoom, smooth zoom, trackpad, precise pointer,
   pointer, clipboard, long-press) — they reset on every restart.
3. **Export image** — the `.image` itself still can't leave the device (fileouts
   now auto-export to Downloads/OpenSmalltalk, and *Save Image as…* names are
   booted via the image library, so only the share-sheet/SAF copy remains).
4. **`.boot_pending` guard is coarse** — *any* exit within 7 s (including a
   deliberate quit) is read as a failed boot and drops the chosen image.
5. **`abiFilters` ships `armeabi-v7a` + `x86_64`** variants that can never run the
   VM (arm64-only `libsqueak.so`). Consider dropping them.
6. **Legacy `onCreateOptionsMenu`** still exists and has diverged from the curated
   dialog; `launchChangesPicker` is dead code (its result is never handled).
7. **`Makefile`** is a stale second build path (hardcoded Homebrew SDK path) that
   contradicts the Gradle one — delete or fix.
8. **`jcenter()`** is still in the repository lists (deprecated/read-only).
9. **dev-tests channel is informational** — `observe.sh` prints DEVTEST lines but a
   failing test doesn't fail the loop.

Non-issues (benign, ignore): `pthread_setschedparam failed: Operation not
permitted` (VM can't get realtime prio; falls back to itimer) and
`Xlib: extension "RANDR" missing` (the embedded X server has no RANDR).

## Reproducibility

Third parties must be able to rebuild everything, **including the VM from the
original opensmalltalk-vm sources** (see `scripts/apply-fixes-stack.sh`). Document
anything you add. The dev-loop scripts auto-detect the toolchain and are overridable
by env var; the Smalltalk text-tests need any vanilla Cuis 6.x image (e.g.
`Cuis6.0-6053`) pushed with `--st scripts/loop/dev-tests.st`.
