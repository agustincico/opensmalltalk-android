# Roadmap & open work

Durable backlog for **opensmalltalk-android** — what's shipped, what's broken, the
subtleties worth knowing, and the path to a Play Store release. Working notes with
root-causes live in [`CLAUDE.md`](../CLAUDE.md); this file is the high-level plan.

Last updated: 2026-08-12, release **v1.42** (Play-ready: targetSdk 35 + 16 KB pages).

---

## Where things are

- **App identity:** `ar.com.opensmalltalk` (reverse-DNS of the planned
  opensmalltalk.com.ar). Java packages keep the fork's `au.com.darkside.*` namespace
  (attribution + JNI symbol stability). Label on device: **OpenSmalltalk**.
- **Branches:** work on **`dev-loop`**; `main` is fast-forwarded on each release.
  Both currently point at the same commit.
- **Releases:** signed APKs on GitHub Releases (v1.31 … v1.42), all signed with the
  **same key** so they update in place (Obtainium-friendly). Not on any app store yet —
  but the `.aab` is now Play-ready (`./gradlew bundleRelease`).
- **Signing key (CRITICAL):** `app/keystore.jks` + `app/keystore.properties` — both
  gitignored, present **only on the maintainer's Mac**. Back them up. Losing them means
  no in-place updates ever again (users must uninstall + reinstall). The *old*
  `au.com.darkside.x11server` keystore leaked its passwords in git history — burned,
  never reuse.
- **Release flow (no `gh` CLI):** token from `git credential fill` (osxkeychain,
  scope `repo`) → `POST /releases` → upload the signed APK asset → verify sha256 of the
  downloaded asset against the local build → `git push dev-loop:main`.
- **What works:** startup image chooser (Squeak / Cuis 7.7 / Cuis University / device /
  library of previously-loaded images), download + offline reopen, **Cuis** file-in by
  drag-and-drop into the running image, automatic fileout export to
  `Downloads/OpenSmalltalk/`, silent Cuis fileOut (no path dialog), Save Image,
  crash-loop protection, finger input (trackpad/precise-pointer/right-click), native
  zoom + zoom picker, FAST funding credit in the Load dialog.

---

## Bugs & broken features (highest value first)

### 1. Squeak file-in into a running image — OPEN, hard
Filing code into a running **Squeak** (☰ → *File in code*) shows *"Cannot start a
second instance of Squeak … singleton application."* **Cuis works.**

- **Root cause:** the app delivers the drop via the VM's `XdndSqueakLaunchDrop`
  (a launch-drop with no preceding drag). Squeak's mouse-up handler sees
  `externalDropMorph == nil` and routes to `MorphicProject>>launchSystemFiles:event:`
  (the "singleton relaunch" path). Cuis instead runs `DropFilesAction` → file-in menu.
- **Delivery of an image patch to Squeak failed every way tried (see CLAUDE.md):**
  `-s` is Cuis-only (Squeak's `DoItFirst` → `#ignore`); `--filein` doesn't evaluate
  doit chunks and a method-def chunk **crashed Squeak's boot**.
- **The right fix:** send a **full synthesized XDND drag** (XdndEnter → XdndPosition →
  XdndDrop, and serve `XdndSqueakSelection` from `Selection.java`). That sets Squeak's
  `externalDropMorph` so the drop routes to `ExternalDropHandler` → native `.st`
  file-in, and Cuis's `DropFilesAction` still works — **no image patch**. The current
  launch-drop is a shortcut only Cuis tolerates. VM reference: `sqUnixXdnd.c`
  `dndInEnter/Position/Drop` + `dndGetSelection`. Do it behind a test; there's risk to
  the working Cuis drop.

### 2. "Save Image and Quit" closes the app to the launcher — OPEN, needs a watchdog
Quitting from the World menu (*Save Image and Quit* / *Quit without saving*) drops the
user to the Android launcher instead of back to the **Load image** chooser.

- **Root cause (measured 2026-08-10):** the VM's quit (`Smalltalk quitPrimitive` →
  `ioExit`) terminates the process **hard** — `atexit` handlers do **not** run and
  `g_squeak_main` does **not** return (verified with an atexit file-marker + a
  post-`main` log; neither fired, pid vanished). The Java X server runs in the **same
  process**, so it dies too. **There is no in-process hook to react to a quit.**
- **Why the existing restart doesn't apply:** ProcessPhoenix (`RestartActivity` in the
  `:restart` process) works for image-switch because it's started **while the app is
  foreground**. On quit there's no foreground moment to launch from, and Android 10+
  blocks background activity starts.
- **The fix is a separate watchdog process** that outlives the app: bind to the main
  process and `linkToDeath()` (or wait on a socket/pipe that closes on death), then
  relaunch `XServerActivity` with a "show chooser" flag. The relaunch must satisfy
  Android 12+'s background-activity-start rules — options: a foreground-service grace
  window, or `SYSTEM_ALERT_WINDOW`. Non-trivial; test on API 30+ (the dev AVD).
- **Cheaper partial win** (doesn't stop the close, only fixes the *next* launch): after a
  healthy boot, heartbeat a `.session_active` file; on launch, a *recent* heartbeat +
  set `.custom_image` ⇒ the session ended abruptly ⇒ show the chooser instead of
  re-booting. Heuristic (can't distinguish quit from crash from OOM), and it still shows
  the launcher first.

### 3. Latest Cuis (7.9 rolling) renders a blank world — WORKED AROUND
Every Cuis 7.9 rolling snapshot (7983 … 8090/master) boots but **never starts its UI**
on this VM (diagnosed: 2026 startup-sequence rework; upstream fixed it in updates
**8093/8094**, which landed *after* the current rolling image). **Action:** the in-app
Cuis download is pinned to **7.7-7976** (`#BaseForCuis7.8`); retest master and unpin
when Cuis publishes a rolling image containing ≥8094.

### 4. `XDisplayControlPlugin.so` fails to `dlopen` — LOW
Over-linked against libs not shipped (libSM/libICE/libuuid/libandroid-execinfo). Doesn't
block anything (fullscreen works without it). Rebuild the plugin without the X-session
libs, or ship those `.so`s, to actually load it.

### 5. Benign log noise (ignore)
`pthread_setschedparam failed` (VM can't get realtime prio — falls back to itimer) and
`Xlib: extension "RANDR" missing` (embedded server has no RANDR).

---

## Subtleties & gotchas (so the next person doesn't relearn them)

- **`-s` is a Cuis option, not a VM/Squeak option.** Squeak ignores it. Cuis 5.0 also
  predates it. The per-boot `assets/android-setup.st` (Cuis fileout + author-initials +
  script chain) is delivered via `-s` and only runs on Cuis 6+.
- **Chunk-format files must be ASCII and `!`-safe.** A non-ASCII char (em-dash) →
  "Unmatched comment quote"; a `!` inside a string chunk terminates the chunk early.
- **Two JDKs:** **JDK 17** for Gradle (AGP 8 rejects 11; Gradle 8.9 rejects 25 — and
  macOS `java_home -v 17` returns "17 **or newer**", so `env.sh` version-checks), JDK 17+
  for the Android cmdline-tools. NDK 26.2, CMake 3.22.1, compileSdk/targetSdk 35,
  arm64-v8a only (the only ABI with a real `libsqueak.so`).
- **Checking 16 KB alignment:** read `p_align` of the PT_LOAD segments of every `.so`
  (must be ≥ 0x4000). Do it on the tree, on the release APK **and** on the AAB. Current
  Termux packages are already 16 KB-aligned, so a misaligned prebuilt can usually be
  fixed by swapping in a fresh `.deb` from `packages.termux.dev` (check SONAME + NEEDED
  match first) instead of rebuilding from source.
- **Emulator must be `google_apis` arm64** (not `google_play`) so `adb root` works for
  `push-image.sh`. It sometimes comes up with no default route → in-app downloads fail
  instantly (`adb shell ip route`; add `default via 10.0.2.2`).
- **Driving Cuis menus in tests:** taps alone don't open submenus — use
  `adb shell input motionevent DOWN/MOVE/UP` (press-hold-drag). Modal PopUpMenus DO take
  plain taps. The SAF file picker rows also need keyboard (`keyevent 61/20/66`), and
  `adb push` to `/sdcard/Download` isn't MediaStore-indexed (won't show in the picker).
- **`kill -SEGV <interpreter TID>`** makes the VM print the Smalltalk stack to logcat
  (find the TID via `debuggerd -b <pid>`); plain SIGUSR1 is eaten by ART.
- **Image header magic:** 68021 (Cuis/Squeak 64-bit Spur), 68531/68533 (Squeak 6.0),
  rejected 6521/6505/6504 (32-bit/V3). The 64-bit-only VM aborts hard on a 32-bit image
  — hence the up-front rejection + `.boot_pending` crash-loop guard.

---

## UX / polish backlog

- **Auto-show the soft keyboard when a text field is focused in the image.** Today the
  user taps the ⌨ button after clicking into a text morph. **Not easy:** Morphic manages
  text focus *internally* and draws its own caret — the whole world is one X top-level
  window, so there's **no X-level "text field focused" event** for the server to react to
  (no XIM, no per-field input focus). Showing the keyboard on every tap would cover half
  the screen. Real options: (a) **image→app signal** — patch the Cuis editor's
  focus/unfocus (e.g. `Editor`/`PluggableTextModel` gaining keyboard focus) to set/clear
  a custom X property (or send a ClientMessage to the clipboard server window), which the
  Java X server watches → show/hide the IME. Doable for Cuis via the existing
  `android-setup.st` (`-s`) hook; **hard for Squeak** (same `-s`/`--filein` delivery
  problem as the Squeak file-in bug). (b) A gesture heuristic (double-tap-to-type) — less
  precise but image-agnostic. Prefer (a) for Cuis; wire it to the same server-property
  mechanism the shared clipboard already uses.
- **Persist UI preferences** (zoom, smooth zoom, trackpad, precise pointer, mouse
  pointer, shared clipboard, long-press, orientation) via SharedPreferences — they reset
  every restart.
- **Export the `.image` off the device** (share-sheet / SAF copy to Downloads). Today
  the image lives in private `filesDir`; fileouts already leave (auto-export), but the
  image itself can't. Also *Save Image as…* under a new name now stays in the in-app
  library, but isn't exportable.
- **Make the FAST logo tappable** → open fast.org.ar (funding + outreach link).
- **Cuis University "looks big"** — likely the image is saved with a high UI scale; low
  priority (may be intentional / image-side). Native zoom (v1.41) at least no longer
  enlarges it further.
- **`.boot_pending` guard is coarse** — any exit within ~7 s (incl. a deliberate quit)
  reads as a failed boot and drops the chosen image.
- **Trackpad tuning** with real-finger feedback (drag hold-delay, cursor gain, default).
- **Dialogo language-button menu** — an old modal `PopUpMenu` (Sensor-driven) that dies
  on touch; deferred.

---

## Reproducibility / provenance (mostly done)

- **Native binary versions** are all recorded ([`THIRD-PARTY-NOTICES.md`](../THIRD-PARTY-NOTICES.md)):
  read from the shipped `.so` banners + the source device's Termux `pkg list-installed`.
  VM = `opensmalltalk-vm` **7.0rc2-202511100848** (VMMaker.oscog-eem.3682, commit
  d621595, Nov 2025), `squeak.stack.spur`; display/sound plugins from a `squeak.cog.spur`
  tree. Remaining: per-version LGPL **license texts** for redistribution-grade
  compliance; a copy script; pinning the plugin checkout's exact commit.
- **VM rebuild from source:** [`docs/BUILDING-VM.md`](BUILDING-VM.md) +
  `scripts/apply-fixes-stack.sh` (5 of 8 patches are still line-addressed — convert to
  context diffs against a pinned commit).
- **Drop the dead ABIs:** `abiFilters` still ships `armeabi-v7a`/`x86_64` JNI wrappers
  that can never run the arm64-only VM.
- Stale second build path (`Makefile`, hardcoded Homebrew SDK) and `jcenter()` in the
  repo lists — delete/fix.

---

## Google Play — technical work DONE (2026-08-12), account work remains

**The two blockers this roadmap called "weeks of work" are done**, and the VM did NOT
need rebuilding. `./gradlew bundleRelease` now produces a Play-ready signed `.aab`.

### ✅ 16 KB page size — done, without recompiling the VM
The old estimate assumed all ~100 prebuilt Termux `.so` were 4 KB-aligned. Measured
reality: **98 of 102 were already 16 KB (or 64 KB) aligned** — Termux has been building
that way — **including `libsqueak.so` (the VM itself)**. Only 4 were 4 KB:
`libXcursor.so` and the three `libpulse*`. They were **replaced with current Termux
builds** (pulseaudio 17.0-4, libxcursor 1.2.3-1 — same SONAMEs, no new deps, so no ABI
risk) rather than deleted, keeping behaviour identical. The one library we compile
ourselves (`libsqueak_jni.so`) gets `-Wl,-z,max-page-size=16384` in
`app/src/main/cpp/CMakeLists.txt` (NDK 26 needs it explicitly; r27+ defaults to it).

Verification: 0 misaligned ELF64 in the tree, in the release APK **and in the AAB**, and
the app was run end-to-end on an **Android 15 emulator with `getconf PAGE_SIZE` = 16384**
(`system-images;android-35;google_apis_playstore_ps16k;arm64-v8a`, AVD `ost-16k`) —
Cuis 7.7 booted and rendered with 0 dlopen errors, in both debug and R8 release builds.

### ✅ targetSdk 35 — done
AGP 4.2.2 → **8.7.3**, Gradle 7.4.2 → **8.9**, JDK 11 → **17**, compileSdk/targetSdk 29 →
**35**, NDK 22 → **26.2**. Plus the AGP 8 migration (namespace, no `package=`, explicit
`android:exported`, `packaging {}`, `mavenCentral()`) and **arm64-v8a only** — the
armeabi-v7a/x86_64 variants could never run the VM, and shipping them would let Play
offer the app to devices where it cannot work. Release APK dropped 17.8 → 15.6 MB.

### What is left (account/store work, not code)
1. **Play Console account — US$25 one-time.** A *new personal* account must also run a
   **closed test with 12 testers for 14 continuous days** before production unlocks
   (~3–4 weeks of calendar time). An organisation account skips that but needs a D-U-N-S.
2. **Play App Signing**: upload the existing `app/keystore.jks` as the *upload key*
   (Google then holds the app signing key). Keep backing that file up.
3. **Store listing**: icon, screenshots (the emulator ones work), short/full description,
   category, contact email.
4. **Privacy policy URL** — the app collects nothing; a page in this repo (or on
   opensmalltalk.com.ar) is enough. Needed for the Data safety form, which is otherwise
   "no data collected".
5. **Content rating** questionnaire + target-audience declaration.
6. **Upload** `app/build/outputs/bundle/release/app-release.aab`.

Policy note (checked): downloading `.image` files at runtime is fine — Play forbids
downloading executable *code*, with an explicit exception for interpreted/VM content
(same basis as Pydroid and the emulator apps). We download no `.so`.

Still worth doing before/around publishing: complete the LGPL source offer in
THIRD-PARTY-NOTICES (see Reproducibility), since publishing widens redistribution.

## How to resume (for a future session)

1. `git checkout dev-loop && git pull` — HEAD should be the latest release commit.
2. Toolchain auto-detects; `./scripts/loop/loop.sh` boots the emulator + deploys, or
   `./scripts/loop/emulator.sh` then `deploy.sh`. See [`docs/DEV-LOOP.md`](DEV-LOOP.md).
3. Read `CLAUDE.md` for deep working notes and the Squeak-file-in analysis.
4. The maintainer grabs the debug APK locally from
   `app/build/outputs/apk/debug/app-debug.apk`; releases are cut from `main`.
