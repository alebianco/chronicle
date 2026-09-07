---
name: chronicle-auto-emulator
description: "How the Android Automotive emulator was set up headlessly for Chronicle, and why Gradle Managed Devices cannot do it"
metadata: 
  node_type: memory
  type: project
  originSessionId: eb8384f7-4315-4572-b92d-890d4987c009
  modified: 2026-09-05T13:54:14.490Z
---

**Gradle Managed Devices cannot run Auto or TV.** AGP refuses outright, verified 2026-09-05:
`"TV and Auto devices are presently not supported with Gradle Managed Devices."` Do not try to add
an `android-automotive` `systemImageSource` to `managedDevices` in `app/build.gradle.kts` — the
config *resolves* and the task is generated, but `<device>Setup` fails with that message.

**The working route is a manual AVD**, all headless, no Android Studio UI needed:

1. `cmdline-tools` was missing from `~/Library/Android/sdk`. Downloaded from
   `https://dl.google.com/android/repository/commandlinetools-mac-11076708_latest.zip` and unpacked
   to `$SDK/cmdline-tools/latest/` (the `latest/` subdir matters — `sdkmanager` expects it).
2. `yes | sdkmanager --licenses`
3. `sdkmanager "system-images;android-33;android-automotive;arm64-v8a"` — the **non-Play**
   "Google APIs" variant, ~2GB. Chronicle needs no Play Services, and the household devices are
   de-Googled GSIs.
4. `avdmanager create avd -n chronicle_auto -k "<that image>" -d "automotive_1024p_landscape"`
5. `emulator -avd chronicle_auto -no-snapshot-load -no-boot-anim -gpu swiftshader_indirect`

Boots in ~10s as `emulator-5554`; `getprop ro.build.characteristics` reports `automotive`, which is
the check that it is a real Auto image and not a phone.

**Android Studio *is* installed**, at `~/Applications/Android Studio.app` — not `/Applications`,
so a check of the latter alone reports it missing. It bundles `AvdManagerCli` but **not**
`SdkManagerCli`, and invoking `AvdManagerCli` standalone fails through Kotlin → Guava → JAXB, the
last being unavailable post-JDK-8. Not worth chasing; download `cmdline-tools` instead.

`sdkmanager` 12.0 prints harmless `SDK XML versions up to 3 but ... version 4` and `unexpected
element abis` warnings against a Studio-managed SDK. They do not stop it working.

Related: [[chronicle-tablet-session]].
