# Roadmap & open work

Durable backlog for **opensmalltalk-android** — what's shipped, what's broken, the
subtleties worth knowing, and the path to a Play Store release. Working notes with
root-causes live in [`CLAUDE.md`](../CLAUDE.md); this file is the high-level plan.

Last updated: 2026-08-25, **v1.44**. Latest finding: the Cog JIT **does** run on Android —
see the JIT section at the end. Shipped VM is still the interpreted Stack build.

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

### 3. Latest Cuis (7.9 rolling) renders a blank world — WORKED AROUND, likely fixable now
Every Cuis 7.9 rolling snapshot (7983 … 8090/master) boots but **never starts its UI**
on this VM (diagnosed: 2026 startup-sequence rework; upstream fixed it in updates
**8093/8094**, which landed *after* the current rolling image). The in-app Cuis download
is pinned to **7.7-7976** (`#BaseForCuis7.8`).

**Measured 2026-08-13 against the freshly cross-compiled VM:**

- `Cuis7.9-8090` (still what `master/CuisImage` ships today) **fails identically** on a
  VM built independently from pinned upstream sources: blank world, the `-s` startup
  script never runs, and the process burns **92.5% CPU** — the same exception-storm
  signature recorded in July. A different VM binary changes nothing, which rules out
  our VM binary as the cause.
- `CuisUniversity-8134` downloads and **renders perfectly** on that same VM. Its
  `.changes` provenance chain proves it is **literally `Cuis7.9-8090.image` + core
  updates 8091–8134 + packages** — the same image file, updated, not a fork. So the
  8090 image is not structurally incompatible with this VM; something in the
  8091–8134 delta fixes or masks the failure.

**Correction (same day): the "8093/8094 are the fix" premise this file used to carry is
wrong.** A diff of those two updates shows 8093 *introduced* a `processStartUpList:`
ordering bug and 8094 undid it 33 minutes later; at 8090 the order was **already** what
8094 restored. No released image ever shipped 8093 without 8094. So "CuisUniversity has
8093/8094, therefore the startup bug is fixed" is unsound reasoning even though the
observation it rests on is real.

**Two better-supported candidates, neither yet tested on the device:**

1. **Update 8043** (`EarlierReadPreferencesAndCommandLineOptions`, 2026-07-02) moved
   `processCommandLineArguments: true` to *before* `readCommandLineArguments`. That makes
   the broken window exactly **8043–8092** — which fits every data point we have (our pin
   7976 < 8043 works; 8090 is inside and fails; 8134 ≥ 8094 works). It also explains the
   symptom mechanically: **`-ud` is an INITIAL command-line option and `-s` is a FINAL
   one** (`SystemDictionary>>processInitialCommandLineOption:` vs
   `processFinalCommandLineOption:`). In that window our `-ud <filesDir>` is silently
   ignored, so Cuis resolves its user base from a fallback path — and "`-s` never runs"
   is not an independent symptom at all, because final options only run once the UI
   process exists.
2. **Updates 8119/8120** fix a nil `strokeWidth` DNU in `VectorEngineDrawer`'s
   world-draw path (8120 sets it in `pvtSetForm:`, which runs at Display setup and on
   screen resize). A DNU raised repeatedly *inside the draw loop* matches both halves of
   the symptom — never-drawn world plus a `primitiveFindHandlerContext` storm at ~95%
   CPU — better than any ordering change. Caveat: `pvtSetForm:` is touched only once in
   the whole 7978→8134 stream, so 7976 left `strokeWidth` uninitialised too; the trigger
   must be something else that changed.

**Action.** The pin stays — but the honest reason is that *no published plain Cuis image
sits outside the 8043–8092 window*, not that we know which update matters. Cheapest ways
to settle it, in order: (a) upstream ships a built-in startup tracer whose output would
land straight in logcat; (b) historical rolling images are fetchable by commit SHA, so a
bisect across 8043–8092 is cheap; (c) test whether `-ud` is being honoured at all on the
pinned image. Also worth knowing: `Cuis-Smalltalk/Cuis7-8` (a *stable* repo) carries
`Cuis7.8.image` at update **7977**, one past our pin — switching would need a regex
change, since stable images carry no build-number suffix. And the next base tag will be
**`#BaseForCuis8.0`**, not 7.9: tags are even minors pointing at the last odd-minor dev
build, so there will never be a "7.9 stable".

### 4. `XDisplayControlPlugin.so` fails to `dlopen` — FIXED 2026-08-12
Was over-linked against libs not shipped (libSM/libICE/libandroid-execinfo). The NDK
rebuild (`scripts/build-vm-android.sh`) drops all three; its `NEEDED` is now entirely
libraries the APK ships. It never blocked anything (fullscreen works without it), but the
root cause — a plugin linked against X-session libraries the APK does not carry — is worth
remembering as the failure mode to check whenever a plugin refuses to `dlopen`.

### 5. Image downloads: hardened 2026-08-13, two follow-ups left
The three pre-set downloads work (each verified downloading and booting on the rebuilt
VM). What was wrong was everything *around* the happy path — an unchecked download became
the boot image sight-unseen, and failures were near-invisible. See the commit "Make a
failed image download say why…". Still open:
- **The three sources are pinned by string matching that will rot silently** — a
  `files.squeak.org` listing regex, a GitHub tag, and a `windows64.zip` asset name. Worth
  a scripted check in the dev loop that resolves all three and asserts a plausible image.
- **Cuis University cannot be cancelled** and its progress bar sits at 100% during the
  ~50 s unzip. Also the restart-after-download path has only ever been exercised on
  API 30 (the AVD), while the app now targets 35.

### 6. Benign log noise (ignore)
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

- **✅ The VM and every plugin are now built from source (2026-08-12).**
  `scripts/build-vm-android.sh` cross-compiles `opensmalltalk-vm` `Cog` @ **`a4d3da0`**
  (`squeak.stack.spur`) and all **20** plugin/display/sound modules with the Android NDK 26
  against API 28, on a desktop. This retires the phone-only, line-addressed
  `apply-fixes-stack.sh` and the split VM/plugin provenance (plugins used to come from a
  *different* `squeak.cog.spur` checkout). See [`docs/BUILDING-VM.md`](BUILDING-VM.md).
  Verified: boots Cuis 7.7, touch works, all artifacts 16 KB-aligned.
- **Support libraries are the remaining prebuilts.** ~60 X11/cairo/pango/glib `.so` still
  come from a Termux install; versions are recorded in
  [`THIRD-PARTY-NOTICES.md`](../THIRD-PARTY-NOTICES.md). The build script already downloads
  its *sysroot* from Termux's package repo, so building the shipped copies the same way is
  a small, well-scoped next step. Also still open: per-version LGPL **license texts**.
- **Drop the dead ABIs:** `abiFilters` still ships `armeabi-v7a`/`x86_64` JNI wrappers
  that can never run the arm64-only VM.
- Stale second build path (`Makefile`, hardcoded Homebrew SDK) and `jcenter()` in the
  repo lists — delete/fix.

---

## Google Play — technical work DONE (2026-08-12), account work remains

**The two blockers this roadmap called "weeks of work" are done.** `./gradlew bundleRelease`
produces a Play-ready signed `.aab`. The VM was **not** required to be rebuilt for Play —
the shipped binaries already passed. It was rebuilt anyway (2026-08-12) for provenance and
to enable the upstream contribution; the rebuilt artifacts are 16 KB-aligned by explicit
`-Wl,-z,max-page-size=16384`, since NDK 26 does not default to it.

**One consequence to be aware of:** the rebuilt VM targets **API 28**, so `minSdkVersion`
moved 22 → 28. That floor was previously fiction anyway — every shipped `.so` already
declared API 24.

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
1. **Play Console account — US$25 one-time, and it covers UNLIMITED apps** (the fee is
   per developer account, paid once — not per app; the same account can later publish
   Dialogo or anything else). A *new personal* account must also run a **closed test
   with 12 testers for 14 continuous days** before production unlocks (~3–4 weeks of
   calendar time). An organisation account skips that but needs a D-U-N-S number.
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

---

## JIT (Cog) on Android — it works; not shipped yet

**The long-standing claim in this repo — "a JIT needs W^X memory that Android does not
grant" — is wrong**, and upstream already knew: `platforms/unix/vm/codeZoneControlARM64.h`
reads *"Android allows the use of PROT_READ | PROT_WRITE | PROT_EXEC so nothing need be
done"*. AOSP's SELinux policy grants every app domain `execmem`, commented *"WebView and
other application-specific JIT compilers"*, unchanged from API 28 through Android 16.
Measured inside the app's own uid and SELinux domain: anonymous RWX `mmap`,
write-then-`mprotect`, and the `memfd` dual mapping all work. (`execstack` and `execheap`
are neverallowed for every domain, so a code zone must be anonymous `mmap` — never the
thread stack or the brk heap. Cog already does the allowed thing.)

Prior art exists: upstream issue #761 is a Cog VM running on a Meta Quest 3 (ARMv8).
Eliot's historical "no JIT on mobile" remarks were about **iOS**, with Android explicitly
contrasted as not having that problem.

### The interesting part: the two code-zone schemes do not behave the same

| Code zone | 4 KB pages (Android 11) | 16 KB pages (Android 15) |
|---|---|---|
| `DUAL_MAPPED_CODE_ZONE=0` — plain RWX, what upstream prescribes for Android | boots, renders | **crashes in JIT-compiled code** |
| `DUAL_MAPPED_CODE_ZONE=1` + our memfd patch | not tested | **boots, renders** |

The RWX crash is a SIGSEGV inside machine-code frames (`M` in the VM's own stack dump),
in `SystemDictionary>>scanFor:` during startup — i.e. the JIT generated code and executing
it faulted. Same binary, same image, same emulator family; only the page size differs.

So **`codeZoneControlARM64.h`'s Android branch appears to be 4 KB-only**. That matters
beyond this project: Google now requires 16 KB-page support, so the path upstream
documents for Android is the one that breaks on the devices Play is pushing everyone
towards. Worth reporting upstream as its own issue, with the dual-mapped route as the fix.

### Our patches

`scripts/android/cog-jit-android.patch` makes the dual-mapped scheme work on Bionic —
neither fix is about execute permissions:

1. `memory_alias_map()` creates its aliased file with `shm_open`, and **Bionic implements
   no POSIX shared memory at any API level**. `memfd_create` is the drop-in replacement
   (via `syscall()`; Bionic only declares it from API 30, the kernel has had it since 3.17).
2. Android **refuses to add `PROT_EXEC` to an existing file-backed shared mapping**
   (`mprotect` → `EACCES`) but will create one executable from the outset. The executable
   alias now asks for its permissions at `mmap` time and the `mprotect` is skipped.

Proof it works: `/proc/<pid>/maps` shows both aliases of one memfd, `r-xs` and `rw-s`.

Build with `scripts/android/build-cog-android.sh` — it differs from the Stack build only in
`--with-src=src/spur64.cog`, dropping `--disable-cogit`, and `-DCOGMTVM=0`.

### Measured on a physical phone (2026-08-25)

`1 tinyBenchmarks` on a real Samsung device, same Cuis 7.7 image, the two builds installed
side by side:

| | Stack (shipped) | Cog JIT | |
|---|---|---|---|
| megaBytecodes/second | 105.79 | **457.96** | **4.33x** |
| megaSends/second | 5.92 | **39.57** | **6.68x** |

Sends are where it counts in Smalltalk, and they are nearly 7x. Note this also settles the
smoke test I could not finish locally: the packaged `app-jit.apk` runs on real hardware, so
the hang I saw was the worn-out emulator, not the build.

Worth knowing: the research turned up **no published Cog-vs-Stack figures for ARM64 at
all** — the widely-quoted numbers predate the ARM64 backend. So this measurement is likely
the first of its kind, and worth passing upstream.

**Why it is still not shipped.** tinyBenchmarks measures bytecode dispatch and message
sends — not GC, not the plugins, and not the X-server-over-Java rendering path that
actually governs how the UI feels. Before it replaces the Stack VM: check what the code
zone costs in RAM, run a long session for stability, exercise the plugins, and confirm the
UI is perceptibly better and not just arithmetically faster. Before it replaces the Stack VM: measure it against Stack on real work, exercise the
plugins, and check what the code zone costs in memory. There is also an unexamined risk —
upstream's `linux64ARMv8` Cog build uses **gcc** with a comment that the fast blitter
"compiles-and-segfaults with clang", and clang is all we have.
