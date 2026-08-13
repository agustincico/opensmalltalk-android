# Dev loop

A low-intervention cycle for working on this app: **compile → deploy to an ARM64 emulator →
observe (screenshot + logs) → drive real touch/keys**, with no USB/phone round-trips. You can
watch it live with scrcpy; the loop verifies itself with screenshots, logcat and Smalltalk
text-tests.

```bash
./scripts/loop/loop.sh                     # full cycle: build → emulator → deploy → mirror → observe
./scripts/loop/loop.sh --observe-only      # just refresh screenshot + logs
./scripts/loop/loop.sh --image Some.image --changes Some.changes   # iterate the image only
```

Artifacts land in `.loop/` (gitignored): `screen.png`, `logcat.txt`, `emulator.log`, `scrcpy.log`.

## One-time setup

```bash
# SDK packages (in addition to the build ones in the README)
sdkmanager "cmdline-tools;latest" "platform-tools" "emulator" \
           "system-images;android-30;google_apis;arm64-v8a"

brew install scrcpy     # optional, for the live mirror
```

- The emulator **must be arm64-v8a** — the VM is ARM64-only.
- Use the **`google_apis`** image, not `google_play`: `adb root` is needed by `push-image.sh`.
- `scripts/loop/emulator.sh` creates the AVD (`cuis-arm64`, pixel_5, `hw.keyboard=yes`) if missing.

Two JDKs are used and auto-detected: **JDK 17** for Gradle, **JDK 17+** for the Android
cmdline-tools (`sdkmanager`/`avdmanager` are compiled for class 61). Override with
`JAVA17_HOME` / `JAVA_CMDLINE_HOME`. Run `./scripts/loop/env.sh` to print the resolved config.

## The scripts

| Script | What it does |
|---|---|
| `env.sh` | Sourced by all others. Detects SDK + the two JDKs; defines `PKG`, `ACTIVITY`, `AVD_NAME`, `FILES_DIR`, `$ADB`, helpers. Everything overridable by env var. Defaults `ANDROID_SERIAL` to the emulator when a phone is also attached. |
| `emulator.sh` | Create the AVD if missing, boot it **headless**, wait for `sys.boot_completed`, `adb root`. Idempotent. `--window`, `--wipe`. |
| `build.sh` | `./gradlew assembleDebug` with JDK 17 (~2 s no-op). |
| `deploy.sh` | Install the APK and relaunch. `--fresh` uninstalls first (clears `filesDir`). |
| `observe.sh` | `screencap` → `.loop/screen.png`; filtered logcat (`Cuis`, `SQUEAK`, `SQUEAK_VM`) → `.loop/logcat.txt`; prints a health line (`vm_argv`, `image_open_fail`, `crash`) and any `DEVTEST` lines. `--stamp`, `--clear`. |
| `input.sh` | Real touch/keys via adb: `tap X Y`, `swipe`, `longpress`, `text "…"`, `key ENTER\|BACK\|…`, `back`, `home`. |
| `push-image.sh` | Iterate the Smalltalk side with **no APK rebuild**: push a `.image` (+ `--changes`, `--st`) into `filesDir` with the right owner/SELinux label, mark it as the chosen image, relaunch. Warns if the image isn't 64-bit Spur. |
| `mirror.sh` | Keep a live **scrcpy** window (idempotent). `--restart`, `--status`. |
| `loop.sh` | Orchestrates the above. `--no-build`, `--fresh`, `--image`, `--observe-only`, `--window`. |
| `dev-tests.st` | Sample Smalltalk text-tests (below). |

## Two verification channels

1. **Screen** — `adb exec-out screencap -p` → PNG. Confirms the world renders; diff
   before/after to see UI effects.
2. **Logs** — `adb logcat -s Cuis SQUEAK SQUEAK_VM`. `Cuis` = Java side, `SQUEAK` = JNI,
   `SQUEAK_VM` = the VM's stdout/stderr (piped to logcat by the JNI bridge).

Useful signals: `startVMNative() retornó: 0`, `boot healthy; cleared .boot_pending`,
`previous image failed to boot … back to the chooser`, `ScreenView: displayScale=…`.

## Smalltalk text-tests → logcat

`squeak_jni.c` appends `-s <filesDir>/dev-tests.st` to the VM argv **only when that file
exists**. So:

- Push one: `./scripts/loop/push-image.sh Foo.image --st scripts/loop/dev-tests.st`
- The image evaluates it at startup; output goes to stdout → logcat (`SQUEAK_VM`).
- Use `StdIOWriteStream stdout` (not `Transcript` / `FileStream stdout` — only that one reaches
  the Android log). Prefix every line with `DEVTEST`; `observe.sh` surfaces them.

Requires a **Cuis 6.x+** image (Cuis 5.0 predates `-s`). Production images without a
`dev-tests.st` are unaffected — the argv stays as it was.

> Caveat: this channel is informational — `observe.sh` prints `DEVTEST` lines and a summary,
> but a failing test does not make the loop exit non-zero.

## Notes

- `emulator.sh` roots adbd once at boot so `push-image.sh` doesn't restart adbd every time
  (which would blink scrcpy).
- **The emulator's Wi-Fi has no IPv4 route, and it wins the default-network election** —
  so every `connect()` from an app fails with *"Network is unreachable"* and the in-app
  image downloads do nothing. `emulator.sh` now detects and fixes this at boot, and prints
  `network ok (outbound TCP works)`.
  The trap, if you ever debug it by hand: `adb shell ip route add default … dev wlan0` does
  **not** work. Android routes per-network via `fwmark`, not through the main table, and
  `ip route show table wlan0` is *empty* — so that command fails with `RTNETLINK answers:
  Network is unreachable` and adding to the main table changes nothing. Meanwhile
  `dumpsys connectivity` cheerfully reports the Wi-Fi network `CONNECTED` and `VALIDATED`.
  The fix is to take Wi-Fi out of the running so Android falls back to the emulated
  cellular link (`eth0`), whose table *does* have a default route: `adb shell svc wifi
  disable`. Verify with a TCP test, not `ping` — ICMP fails under the emulator's user-mode
  networking even when TCP works.
- Scripts run under macOS **bash 3.2**, so array expansions use the
  `${arr[@]+"${arr[@]}"}` idiom.
