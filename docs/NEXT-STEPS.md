# Next steps

What is worth doing next, why, and what each one would actually cost. The
consolidated bug/UX backlog stays in [`ROADMAP.md`](ROADMAP.md); this file is the
shorter question of *where to spend the next session*, written down so the
reasoning survives between them.

Ordered by value, not by effort.

---

## 1. Rebuild the support libraries from source

**Status: open. The biggest single item in the repo.**

The ~60 X11/cairo/pango/glib libraries the APK ships are **Termux prebuilts copied off a
phone**. That is the last provenance gap (audit item 1 in `CLAUDE.md` — the VM and all 20
plugin/display/sound modules have been cross-compiled from pinned upstream sources since
2026-08-12; only these remain).

It is worth doing because it closes **two** problems at once:

- **Reproducibility.** A third party can rebuild everything else from source today. These
  they must take on trust.
- **It is what blocks a genuinely X-free build.** `libcairo.so.2` links `libX11` and
  `libxcb`, and cairo is what `UnicodePlugin` needs — so even the `-PnativeOnly` APK, which
  has no X server and no X display driver, still carries the X client libraries. Built
  without X, the native path would need not one X library, and the APK would shrink well
  past the 13 MB already saved.

The scaffolding exists: `scripts/build-vm-android.sh` already assembles its sysroot by
downloading Termux's own packages, so the sources and versions are known
(`THIRD-PARTY-NOTICES.md` records every version). The work is turning that into
*compiling* them with the NDK — cairo with `--disable-xlib --disable-xcb`, pango with the
FreeType backend only, and whatever that cascade drags in.

**Expected outcome:** every native artifact built from source, and an APK with zero X11.

---

## 2. Offer `vm-display-android` upstream

**Status: the module works; the packaging for upstream is not written.**

Upstream OpenSmalltalk has **no Android display module at all**. Ours is an ordinary Unix
display module (`SqDisplayDefine` + `SqModuleDefine`) with no Android-only hacks in VM
code, which is exactly the shape a contribution should take — see
[`NATIVE-DISPLAY.md`](NATIVE-DISPLAY.md).

What it needs: a `Makefile.inc` and `acinclude.m4` next to the source, and an entry in
`sqUnixMain.c`'s `moduleDescriptions` (only so it can be a *default* — an explicit
`-vm-display-android` already works without it).

### Three things we owe upstream and have not sent

1. **The 16 KB page-size code-zone bug.** Upstream's prescribed plain-RWX path for the Cog
   JIT works on 4 KB pages and **faults on 16 KB** — which matters now that Play requires
   16 KB support. Diagnosed while porting the JIT (see the JIT section of `ROADMAP.md`).
   Owed as an issue.
2. **The two memfd patches** that make the dual-mapped code zone work on Bionic
   (`scripts/android/cog-jit-android.patch`). Offered in PR #781's discussion, never sent
   as a PR of their own.
3. **`BitBltArm64.c` does not compile with NDK clang** — about five inline-asm errors
   (`invalid operand for instruction`, `constraint 'I' expects an integer constant
   expression`, `Immediate too large for register`). This is a real clang incompatibility
   in the optimised blitter, not the Termux assembler quirk we first assumed. Owed as a bug
   report with the error list.

Existing work: PR [#781](https://github.com/OpenSmalltalk/opensmalltalk-vm/pull/781) and
issue [#780](https://github.com/OpenSmalltalk/opensmalltalk-vm/issues/780). See
[`UPSTREAMING.md`](UPSTREAMING.md).

---

## 3. Unpin the Cuis download

**Status: open, and cheap. Best return per hour on this list.**

The in-app *Cuis (download)* option is pinned to **7.7-7976** because every 7.9 rolling
snapshot tested (7983, 8064, 8090) never launched its UI process on this VM — the
mid-2026 startup-sequence rework, window **8043–8092**. Diagnosis in `CLAUDE.md` backlog
item 4, including the two best candidate updates and the fact that CuisUniversity-8134
(the same 8090 file plus updates 8091–8134) renders perfectly — so it is fixed somewhere
in that range.

Time has passed. Re-test current Cuis; if it starts, drop the `?ref=%23BaseForCuis7.8` pin
and users get a current Cuis again. If it still fails, bisect 8043–8092 by commit SHA —
the method is written down.

---

## 4. Product bugs still open

| | |
|---|---|
| **Squeak file-in into a running image** | Hard. Cuis works (XdndSqueakLaunchDrop); Squeak reads that as a singleton relaunch and refuses. The right fix is the **full XDND handshake** (Enter→Position→Drop + serving XdndSqueakSelection), which also keeps the Cuis path working. Do it behind a test — it risks the one thing that does work. `CLAUDE.md` has the analysis and the reference implementation. |
| **"Save Image and Quit" closes the app to the launcher** | Needs a watchdog: the VM exits and nothing brings the activity back. |
| **Export the `.image` off the device** | Fileouts already auto-export to `Downloads/OpenSmalltalk/`; the image itself still cannot leave. A share-sheet / SAF copy. |
| **`.boot_pending` guard is still coarse** | Improved 2026-08-27 (`onPause` clears it when the VM is running, so backgrounding no longer counts as a failed boot), but a deliberate quit within 7 s still does. |

---

## 5. Make the native display the only display

**Status: partially shipped — v1.46 ships the `-PnativeOnly` build.**

Shipping it is not the same as removing the alternative. The X server, its library module
(`library/`, ~2500 lines) and `X11Display` are all still in the tree, and the default build
still produces both engines. Removing them is only worth doing once (1) has landed and the
native path has been used on a range of real devices for a while.

Until then, keep both: `SmalltalkDisplay` + the `X11Display` adapter exist precisely so the
two can coexist without the library module changing.

---

## 6. Google Play

**Status: all technical work done (16 KB alignment, targetSdk 35, arm64-only). What is
left is account work**, listed in [`ROADMAP.md`](ROADMAP.md#google-play--technical-work-done-2026-08-12-account-work-remains):
the US$25 one-time fee, Play App Signing, the store listing, a privacy-policy URL, the
content rating questionnaire, and the closed test — **12 testers for 14 continuous days**
before production unlocks for a new personal account.

That last one is calendar time, not work: the sooner it starts, the sooner it ends.
