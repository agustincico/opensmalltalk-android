# The X11-free display path (`vm-display-android`)

Status: **working prototype, measured, not yet the shipping path.** The app still boots the
embedded X server by default; the native path is a second activity you can launch side by
side, so both can be compared on the same device with the same image.

## Why

Everything the image draws currently travels: image → `XPutImage` → **TCP socket to
localhost:6000** → a **Java X-protocol parser** → an Android `Bitmap` → `View.onDraw`. That
is three copies and a protocol round trip per frame, and it is why the Cog JIT — 4.3× the
bytecodes, 6.7× the sends — did not feel much faster: the JIT speeds up everything *except*
painting.

`vm-display-android.so` deletes that entire chain. The VM writes the Display straight into
the buffer behind a `SurfaceView`.

## What it is

An **ordinary OpenSmalltalk Unix display module** — the same kind as `vm-display-X11` and
`vm-display-fbdev`: `SqDisplayDefine` + `SqModuleDefine`, built as `vm-display-android.so`,
selected by putting `-vm-display-android` on the VM's command line.

That matters more than it sounds: **the VM needs no change at all.** `requireModuleNamed`
turns `-vm-display-<name>` into a `dlopen` of `vm-display-<name>.so` in the plugins
directory, so the `libsqueak.so` already shipped in v1.45 loads this driver as-is. Verified
by checking every symbol the module imports (`aioPoll`, `primitiveFail`, `signalSemaphoreWithIndex`,
`uxDropFileNames`, …) against the shipped VM's dynamic symbol table.

Its `NEEDED` set is `libsqueak.so`, `libandroid.so`, `liblog.so`, `libdl.so`, `libc.so` —
all platform libraries. **No X11, no cairo, no pango, no SDL.** 67 KB.

### Not the Pharo approach, and why

Pharo's VM is *headless*: it has no display code, and the image draws through UFFI → libffi
→ libSDL2. Squeak and Cuis do the opposite — the VM owns the display and the image calls
`ioShowDisplay`. So "an SDL2 plugin like Pharo's" would mean changing the image, whereas a
display module changes nothing above the VM. SDL2 would also have cost ~1.5 MB of library
and forced the launcher to become an `SDLActivity` (SDL's Android backend owns the main
thread), for the same pixels.

## Measured

Emulator, API 30 arm64, Cuis 7.5-7681, Cog JIT, same image and same script both ways —
counting full-screen `Display primShowRectLeft:right:top:bottom:` calls completed in 6
seconds, with the world already up:

| | embedded X server | `vm-display-android` |
|---|---|---|
| world size | 1080×2063 | 1080×2195 (6% more pixels) |
| full-screen updates in 6 s | **42** (7/s) | **230** (38/s) |
| frames SurfaceFlinger actually presented | 1.8 fps | 38.9 fps |

**~5.5× more repaints reaching the screen**, and the native figure is at the emulator's
presentation ceiling (~22 ms per posted buffer on a software GPU), not at the driver's.

> Measure with the world already up. Under X11, `display_ioShowDisplay` returns immediately
> while `stWindow == 0`, so a benchmark run from the `-s` startup script measures an empty
> loop — it reported 260 *million* "frames" in 6 seconds before this was noticed.

## Two things learned the hard way

1. **One post per damage rectangle throttles the image to one rectangle per refresh.**
   Posting a buffer presents a frame and blocks until the compositor frees one. Damage is
   now unioned and posted once per turn of the VM's event loop (and at the latest 16 ms
   after the first damage, so a long draw cannot hold the screen).
2. **A small damage rectangle costs MORE than a full-screen one** — 28 ms vs 21 ms.
   `ANativeWindow_lock` preserves everything *outside* the dirty rectangle by copying it
   from the previously posted buffer, so the smaller the damage, the bigger that copy. The
   driver therefore claims the whole surface (nothing to preserve, no copy-back) and
   repaints it itself from the Display bits it already has. Kept as the `fullRepaint`
   switch, since the trade-off is device-dependent.

Also worth knowing, from the same family of problems the Pharo port hit: Android resolves
symbols in **one flat process-wide namespace**, so a module can interpose on libc or on the
other display driver. `exports.map` publishes exactly `display_android` and the `sqAndroid*`
entry points and hides the event-buffer globals the module inherits from `sqUnixEvent.c`.

## Building it

```bash
OSVM=~/opensmalltalk-vm scripts/android/vm-display-android/build.sh
cp scripts/android/vm-display-android/vm-display-android.so app/src/main/assets/plugins/
```

Seconds, not the ten minutes a VM build takes: a display module resolves the VM's symbols at
load time, so it compiles standalone against the headers with a seven-line `config.h`. Use
the same pinned upstream commit the VM was built from (`a4d3da0`, branch `Cog`).

## Trying it

```bash
adb shell am start -n ar.com.opensmalltalk/au.com.darkside.x11server.NativeDisplayActivity
```

It boots whatever image the normal launcher last chose (`.custom_image`) and extracts the
driver itself if the installed APK predates it.

## Shape of the code

| Piece | What it does |
|---|---|
| `scripts/android/vm-display-android/sqUnixAndroidDisplay.c` | the driver: `ANativeWindow` rendering, the event queue, clipboard, drop files |
| `scripts/android/vm-display-android/exports.map` | seals the symbol namespace |
| `app/src/main/cpp/squeak_jni.c` | `NativeDisplay_*` JNI entry points; caches the surface until the driver is loaded |
| `NativeDisplay.java` | the Java side of that bridge |
| `SqueakSurfaceView.java` | the surface, and the touch/key translation the X server used to do |
| `NativeDisplayActivity.java` | a second entry point, so the shipping X11 path stays untouched |

Threading: everything `display_*` runs on the VM thread, everything `sqAndroid*` on the
Android UI thread. They meet in two places, each with its own mutex — the window pointer
(so `surfaceDestroyed` cannot pull the buffer out from under a blit) and a raw event ring
that the VM thread drains in `ioProcessEvents`, which keeps `sqUnixEvent.c`'s buffer
single-threaded as it was designed to be. A self-pipe registered with `aio` lets a touch cut
the VM's idle sleep short.

## Using it

☰ → **Display engine** switches between the two and restarts (the VM binds to a display
driver through its argv, so it cannot change in a running image). The choice is a marker
file in filesDir, so it survives the restart; `NativeDisplayActivity` forces the native one
for a shell launch:

```bash
adb shell am start -n ar.com.opensmalltalk/au.com.darkside.x11server.NativeDisplayActivity
```

Both backends are two modes of the same activity, so the pill, the image chooser, the
crash-loop guard, the fileout watcher and the file-in picker are shared rather than copied.
What differs is declared, not assumed: `SmalltalkDisplay.supports()` decides which rows the
options dialog shows.

| Menu item | Native path |
|---|---|
| Load image…, File in code, Screen orientation, Zoom | work |
| Precise pointer, Shared clipboard | work |
| Trackpad mode, Mouse pointer, Long-press menu | work |
| Smooth zoom | **hidden** — nothing to toggle: the X path filters a `Canvas` blit, here the scaling is SurfaceFlinger's and the image re-lays-out instead of being interpolated |

Verified on the emulator with Cuis 7.5: the world draws and responds to touch, ⊙ opens the
World menu, ✦ raises a morph's halos, ⌨ brings up the keyboard and typing reaches the image,
and Zoom 2× genuinely re-lays-out the world (the driver logs `ioScreenSize -> 540x1032` and
then `image Display is 540x1032`, which is how you tell a real resize from the compositor
merely magnifying a stale Display).

### The button numbers, since the names mislead

The Unix VM maps X buttons 1/2/3 onto Squeak's red/yellow/blue bits in that order (`rybMap`,
sqUnixX11.c), and the X path sends **button 3 for the context menu** and **button 2 for
halos**. So on this backend menu = the *blue* bit (1) and halos = the *yellow* bit (2) —
the opposite of what the colour names suggest. Reproducing those exact bits is what makes
the two backends behave identically.

## The four input features, and what each had to get right

Ported after the menu, all verified on the emulator:

- **Trackpad mode** — `ScreenView.handleTrackpadTouch` is pure MotionEvent arithmetic; only
  its two sinks change. The rule that makes it work is that a hover must carry **no buttons**
  (that is what opens Cuis submenus), so nothing is emitted on ACTION_DOWN. The ⊙/✦ arm is
  consumed at UP here, not at DOWN as in direct touch: a slide produces no click and would
  otherwise swallow it.
- **The pointer arrow** — a sibling overlay view (`PointerOverlayView`) added above the
  surface and below the pill. It could not be drawn in `onDraw`: a `SurfaceView`'s content is
  the VM's buffer and the view's own drawing goes to the window layer. It is also the *only*
  pointer that will ever exist here, since the driver stubs the cursor primitives. It takes
  touch from nobody (`dispatchTouchEvent` returns false) and scales with density, **not** with
  the zoom — zooming shrinks the logical screen, so a zoom-scaled arrow would shrink as
  everything else grew.
- **Long-press menu** — a floating `ActionMode` with the same items. Ctrl chords are sent as
  Squeak expects them: the CONTROL CHARACTER carrying CtrlKeyBit, with the plain letter as the
  Unicode value, which is exactly what XLookupString handed the VM. Each chord is followed by a
  zero-modifier mouse event, because the driver keeps the modifier word globally and the next
  tap would otherwise be a ctrl-click.
- **IME panning** — `SOFT_INPUT_ADJUST_NOTHING` is required here (resizing the surface would
  leave the logical size behind and the compositor would squash the world), and it also means
  `getWindowVisibleDisplayFrame` reports no keyboard at all. The height comes from
  `WindowInsets.Type.ime()` instead, with the old measurement as the fallback below API 30.
  The pan moves the surface and the overlay together.

**Stuck buttons are the standing hazard.** Every path that presses releases — the 350 ms
hold-to-drag, a mode toggle mid-drag, surface teardown — because there is no server in between
to notice an unmatched press: the image would simply stay in a drag forever.

## What is still missing

- Depths other than 32 bpp are refused (`ioHasDisplayDepth`), which every modern image is
  fine with, and rotation is lightly tested.
- The `.boot_pending` crash-loop guard is as coarse here as on the X path: any death within
  7 s — an emulator's slow first start after an install, say — is read as a failed boot and
  drops the chosen image.

## Upstreaming

The driver is written to be contributable: no Android-only hacks in VM code, everything in
its own `platforms/unix/vm-display-android/` shape. To offer it upstream it needs a
`Makefile.inc` and `acinclude.m4` next to the source and an entry in `sqUnixMain.c`'s
`moduleDescriptions` (only so it can be a *default*; explicit `-vm-display-android` already
works without it). Upstream has no Android display module at all today.
