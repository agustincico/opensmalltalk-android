# OpenSmalltalk Android

Run any OpenSmalltalk image (Cuis, Squeak) or custom project (like [Dialogo](https://dialog.ar)) on Android — as a native APK, no Termux required.

![Cuis University running on Samsung Galaxy A12](https://github.com/user-attachments/assets/78cb2c7f-c7a3-423a-a3c9-02b6d1e62064)

## Status

Working alpha. Tested on Samsung Galaxy A12 (ARM64, Android 10) with Cuis University image.

## How it works

The app packages two components into a single APK:

1. **OpenSmalltalk Stack VM** — compiled from source on Android/Termux for ARM64, loaded via JNI
2. **X11 server** — embedded in the app (forked from [android-xserver-enhanced](https://github.com/agustincico/android-xserver-enhanced)), adapted to connect with the VM

When launched, the app starts the X11 server, then launches the VM pointing to a Smalltalk image. The VM renders via X11 into an Android View.

### Architecture
```
Android App (Java)
├── XServerActivity            ← starts X11 server + launches VM
├── android-xserver-enhanced   ← X11 server running in-process (library/)
└── squeak_jni.c (JNI bridge)  ← connects Java layer to the VM
Native (ARM64)
├── libsqueak.so               ← OpenSmalltalk Stack VM
├── vm-display-X11.so          ← VM display plugin
└── ~50 dependency libs        ← resolved from Termux (glib, cairo, pango, X11, etc)
```
### Roadmap

- **v1 (current):** VM via JNI + embedded X11 server
- **v2 (planned):** Replace X11 stack with a native Android display plugin — eliminating ~50 dependencies and making the APK significantly smaller and more robust

## Requirements

- Android 5.1+ (API 22)
- ARM64 processor (ARMv8)

## Quick start

1. Download the APK from [Releases](https://github.com/agustincico/opensmalltalk-android/releases)
2. Enable "Install from unknown sources" on your device
3. Install and open the app — it comes pre-loaded with Cuis University image

To use a different image, replace `app/src/main/assets/Cuis.image` and `.changes` with your own before building.

## Building from source

### Prerequisites

- Android SDK (API 29)

### Build the APK
```bash
git clone https://github.com/agustincico/opensmalltalk-android
cd opensmalltalk-android
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties  # adjust path to your SDK
./gradlew assembleDebug
# APK will be at app/build/outputs/apk/debug/app-debug.apk
```

The repo is self-contained — both the launcher and the X11 server library are included. No submodules needed.

### Recompile the VM (optional)

The compiled VM (`libsqueak.so` and external plugins) is included in the repo. If you want to recompile it from source on an Android device:

1. Install Termux on your Android device
2. Clone [opensmalltalk-vm](https://github.com/OpenSmalltalk/opensmalltalk-vm)
3. Run the fixes script from this repo:
```bash
   bash scripts/apply-fixes-stack.sh
```
4. Follow the steps printed at the end of the script

The script documents 8 fixes required to compile the standard Linux VM on Android/Termux.

## X11 server fork

This project uses a modified version of [android-xserver-enhanced](https://github.com/ZhymabekRoman/android-xserver-enhanced).
Key changes for OpenSmalltalk compatibility:

- **TrueColor 32bpp visual** — Squeak/Cuis VM requires Visual class 4, depth 32; any other setting causes a blank screen
- **Public `processRequest()`** — exposed for external dispatch
- **Dynamic resize handling** — sends `ConfigureNotify` to clients when screen size changes at runtime

See: https://github.com/agustincico/android-xserver-enhanced

## Known limitations (v1)

- Image file is bundled in the APK — no runtime image picker yet
- File write errors may occur depending on Android storage permissions
- App icon is placeholder (still shows X server logo)
- Touch interaction is rough — menus are hard to tap with a finger
- Fullscreen only applies after rotating the screen once

These will be addressed in the next release.

## License

MIT

## Author

Agustin Martinez

## Acknowledgements

- [OpenSmalltalk VM](https://github.com/OpenSmalltalk/opensmalltalk-vm)
- [android-xserver-enhanced](https://github.com/ZhymabekRoman/android-xserver-enhanced) by ZhymabekRoman
- [Cuis University](https://sites.google.com/view/cuis-university/)
- Funded by [FAST](https://www.fast.org.ar) — Fundación Argentina de Smalltalk
