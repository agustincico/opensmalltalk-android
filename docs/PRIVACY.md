# Privacy Policy — OpenSmalltalk for Android

**Last updated: 17 August 2026**

OpenSmalltalk for Android (`ar.com.opensmalltalk`) is a free, open-source application that
runs the OpenSmalltalk virtual machine on an Android device, so you can use Smalltalk
environments such as Squeak and Cuis on a phone or tablet.

## The short version

**This app collects nothing.** It has no analytics, no advertising, no crash reporting and
no accounts. Nothing you do in it is sent anywhere, and the developer cannot see any of it.

## What the app stores, and where

Everything the app writes stays in Android's private storage for this application, on your
own device:

- **Smalltalk images** you download or open (`.image`, `.changes`, `.sources`), and any
  images you save from inside the environment.
- **Your work**, which in Smalltalk lives inside the image itself.
- A few small marker files the app uses to remember which image to open.

None of it leaves the device. Uninstalling the app deletes all of it, so keep a copy of
anything you want to survive an uninstall.

One exception, and it is deliberate: when you *file out* code from inside the Smalltalk
environment, the resulting `.st` file is copied to your device's **Downloads/OpenSmalltalk/**
folder, so that you can find and share it yourself. That copy is made on your device; it is
not uploaded anywhere.

## Network use

The app uses the internet for exactly one purpose: **downloading the Smalltalk image you
choose**, when you choose it. Those downloads go to the projects' own public servers:

- `files.squeak.org` — Squeak images
- `api.github.com` and `raw.githubusercontent.com` — Cuis and Cuis University images

These are ordinary anonymous downloads. No account, no identifier, and nothing about you is
transmitted. Those servers will, like any web server, see the request and your IP address;
their own operators' policies apply to that, not this one.

The app makes no other network connections. It does not phone home.

## Permissions, and why each exists

| Permission | Why |
|---|---|
| `INTERNET` | to download the image you pick from the *Load image* menu |
| `WAKE_LOCK` | to keep the screen awake while the Smalltalk environment is running, so it does not sleep mid-session |
| `WRITE_EXTERNAL_STORAGE` | only to place filed-out code into `Downloads/OpenSmalltalk/` on older Android versions |

The app requests no access to your contacts, location, camera, microphone, photos, call
logs, or any other personal data, and it holds no such permission.

## Children

The app is a programming environment. It is not directed at children, and since it collects
no data it collects none from anyone, of any age.

## Third-party code

The app bundles the OpenSmalltalk virtual machine and a number of open-source support
libraries; the full list is in
[THIRD-PARTY-NOTICES.md](../THIRD-PARTY-NOTICES.md). None of them performs analytics or
telemetry in this application.

The Smalltalk images you download are themselves complete programming environments, and once
running they can do whatever you program them to do — including opening network connections.
That is under your control, not the app's.

## Changes

If this policy ever changes, the updated version will be published at this address and the
date above will change with it. The history of this file is public in the repository.

## Contact

Agustín Martínez — <agurafamartinez@gmail.com>
Issues: <https://github.com/agustincico/opensmalltalk-android/issues>
