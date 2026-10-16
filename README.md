# Mumla OLED

```text
       ⠀⠀⠀⠀⠀⠀⣀⣤⣤⣶⣶⣤⣤⣀⠀⠀⠀⠀⠀⠀
       ⠀⠀⠀⠀⣴⣿⣿⠿⢿⣿⣿⣿⣿⣿⣿⣦
       ⠀⠀⠀⠀⠟⣩⣶⣶⣶⣄⣸⣿⣿⣿⣿⣿⠀⠀⠀⠀
       ⠀⠀⣴⣿⠼⠿⠿⠿⠿⠿⠿⠿⠿⠿⠿⠿⣿⣦⠀⠀
       ⠀⠀⢻⣿⢺⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⡷⣿⡟
       ⠀⠀⠀⠉⠉⢿⣿⣩⣿⣿⣿⣿⣍⣿⡿⠉⠁⠀⠀⠀
       ⠀⠀⠀⠀⠀⠀⢹⡿⠿⢿⡿⠿⢿⡏
       ⠀⠀⠀⠀⠀⠀⠛⠁⠀⠀⠀⠀⠈⠛
```

[![License: GPL-3.0-or-later](https://img.shields.io/badge/License-GPL--3.0--or--later-blue.svg)](LICENSE)
[![Platform: Android 11+](https://img.shields.io/badge/Platform-Android%2011%2B%20(API%2030%2B)-green.svg)](https://developer.android.com)
[![Target SDK: 36](https://img.shields.io/badge/Target%20SDK-36%20(Android%2016)-orange.svg)](https://developer.android.com)

**Mumla OLED** is Mumble for modern Android.

I forked [Mumla](https://gitlab.com/quite/mumla) to make the background `#000000` black. Then I started pulling on loose threads. Ten years of Android tech debt, a Java audio loop from the KitKat era, broken echo cancellation, and mic settings that required a degree in broadcast engineering just to stop breathing into the channel—one thing led to another, and now basically the entire engine has been rebuilt. The name stuck.

The thesis is simple: **Mumble should just work.**

You shouldn't have to stare at a VU meter and drag threshold sliders around for fifteen minutes before you can talk to your friends. In Mumla OLED, you connect to a server and talk. Inbound voice comes out of your speakers without echoing back into the channel. You don't configure your mic—the defaults are already right. RNNoise eats your background noise, an adaptive leveler evens out your volume, and a pre-speech ring buffer makes sure your first syllable never gets clipped.

Grab the latest signed APK from [**GitHub Releases**](https://github.com/360ied/mumla-oled/releases).

---

## What Changed (Almost Everything)

### Audio that doesn't fight you

Android audio routing is a minefield, and legacy Mumble clients dump that problem onto the user. Mumla OLED rips out the old Java audio loop and Speex preprocessor in favor of a 48 kHz native C++ DSP pipeline (`:libraries:humla/src/main/jni/audio_engine`) with opinionated, automatic defaults:

- **Speakerphone doesn't echo. Headphones don't sound like a 1998 landline.** When you're on the phone speaker or earpiece, Mumla OLED locks into Android's coupled VoIP path (`VOICE_COMMUNICATION` + hardware `AcousticEchoCanceler`) so speaker leakage gets killed at the HAL. Plug in wired headphones or connect Bluetooth A2DP buds, and it drops back to an uncoupled stereo media path (`MIC` + `USAGE_MEDIA`) instead of forcing your headphones into narrowband Bluetooth SCO potato mode unless you actually ask for the BT mic.
- **RNNoise is always on the job.** An embedded recurrent neural network (running off memory-mapped quantized weights straight from APK assets) strips fan noise, keyboard clatter, and room hiss in real time.
- **Volume leveling that doesn't trip the mic gate.** The native adaptive leveler normalizes quiet and loud speech, but applies its gain *after* the VAD transmit decision. Quiet talkers get boosted; silent rooms don't get amplified into false transmissions.
- **Zero clipped words.** A 2nd-order biquad high-pass filter cuts out desk thumps and rumble, feeding a dual-threshold hysteresis VAD (250 ms hold) backed by an 80 ms pre-speech lookahead buffer. By the time the VAD trips, the syllable you started 80 ms ago is already in the ring buffer waiting to be encoded.
- **Opus only, up to 192 kbps.** With a soft limiter on the output mixer. CELT and Speex are dead and gone.

### Built for OLED screens and actual phone use

- **True `#000000` black by default.** Looks sharp on modern OLED panels, saves battery, and doesn't blind you at 2 AM.
- **A floating HUD that stays out of the way.** Rebuilt as a compact speaker-pill overlay that snaps to any screen corner, passes touches straight through to whatever game or app is underneath, and toggles from the notification shade.
- **Push-to-Talk when you want it, Voice Activity when you don't.** VAD is the default because it finally works. If you prefer PTT, you get the on-screen bar, hardware volume keys, or a floating hot-corner target that knows to hide itself when the keyboard pops up.
- **Chat that survives a subway tunnel.** Reconnecting to a server no longer wipes your chat scrollback. Inline images load asynchronously with display-width scaling, LRU caching, and SSRF guards.
- **Mumble 1.5 channel listening.** Eavesdrop on multiple channels at the same time without leaving your own, with per-channel volume sliders and ear indicators.

### Purging the 2014 time capsule

Half of modernizing an old Android codebase is deleting things that have no business existing in 2026:

- **Goodbye BouncyCastle.** Pulling in 3,000 DEX classes and over a megabyte of bytecode just to generate a self-signed X.509 client cert was insane. Replaced with standard platform Conscrypt / Java SE and a tiny in-tree ASN.1 DER encoder.
- **No OS fingerprinting.** The client no longer tattles your Android version and device build string to every Mumble server on handshake.
- **Cut the deadweight.** Deleted the broken Tor/Orbot SOCKS proxy plumbing, deleted the dead public server browser, and replaced the old Java OCB2 implementation with native C++ OCB2-AES128 over JNI.
- **Modern Android under the hood.** Floor raised to **Android 11 (API 30)**, targeting **Android 16 (API 36)**. The old `AsyncTask`, raw SQLite, `SharedPreferences` blob, and `Handler`/`synchronized` soup in the service layer have been replaced with a `LifecycleService` running Kotlin coroutines and `Flow`, Room (`MumlaDatabase`), Preferences DataStore, Hilt, and an exponential-backoff `NetworkCallback` reconnect engine that survives Wi-Fi-to-cellular handoffs without leaking wake locks.

---

## Repository Layout & Deep Dives

```text
mumla-oled/
├── app/                              # :app — UI, activities/fragments, overlay HUD, Room, DataStore, Hilt
├── libraries/humla/                  # :libraries:humla — Mumble protocol, service, and JNI engine
│   ├── src/main/java/se/lublin/humla/ # Protocol state machine, TLS control channel, UDP transport
│   ├── src/main/jni/
│   │   ├── audio_engine/             # C++ DSP: RNNoise, AdaptiveLeveler, BiquadFilter, HysteresisVad, JitterBuffer
│   │   ├── crypto/                   # C++ OCB2-AES128 UDP crypto & JNI bridge
│   │   ├── opus/                     # Submodule: libopus
│   │   └── rnnoise/                  # Submodule: librnnoise
│   └── src/test/cpp/                 # Host C++ unit tests for audio DSP and crypto
├── docs/                             # Engineering write-ups, architecture notes, and postmortems
├── scripts/                          # worktree.py, commit.py, check.sh, test_native_audio.sh
└── flake.nix                         # Hermetic Nix dev shell (JDK 21, Android SDK/NDK)
```

If you want the gory details on why specific things are built the way they are, read [`docs/`](docs/):

- [**Echo Cancellation (`docs/echo-cancellation/`)**](docs/echo-cancellation/README.md) — Why single-ended mic DSP can never cancel speaker leakage, and how the route-dependent platform `AcousticEchoCanceler` and Bluetooth SCO/A2DP policies work.
- [**Optimistic Self-Mute (`docs/optimistic-self-mute/`)**](docs/optimistic-self-mute/README.md) — Eliminating round-trip server lag when tapping mute/deafen without desyncing state.
- [**Modernization Migration (`docs/migration/`)**](docs/migration/README.md) — The full log of migrating the service and data layers to Kotlin, Coroutines, Room, DataStore, and AGP 9 (plus why we killed the Compose UI rewrite and kept XML Views).
- [**APK Size Analysis (`docs/apk-size-analysis/`)**](docs/apk-size-analysis/README.md) — Byte-by-byte breakdowns of DEX, native `.so` packaging, and R8 shrinking across releases.

---

## Building from Source

100% FOSS. Zero Google Play Services. Toolchain versions are pinned in [`flake.nix`](flake.nix) and [`gradle/libs.versions.toml`](gradle/libs.versions.toml).

### With Nix (Recommended)

```bash
git clone --recursive https://github.com/360ied/mumla-oled.git
cd mumla-oled

# Build the debug APK
nix develop --command ./gradlew assembleFossDebug

# Run the gate (Python tooling tests, host-compiled C++ DSP/crypto tests, JVM unit tests)
./scripts/check.sh
```

### Without Nix

Initialize submodules (`git submodule update --init --recursive`), point `JAVA_HOME` (JDK 21) and `ANDROID_SDK_ROOT` at your local toolchain, and run `./gradlew assembleFossDebug`.

*(Note: release tasks like `assembleFossRelease` fail closed unless you have a local uncommitted `app/signing.gradle` defining `signingConfigs.release`. Debug builds work out of the box.)*

### Install via ADB

```bash
nix develop --command ./gradlew :app:installFossDebug
nix develop --command adb shell am start -n se.lublin.mumla.oled15/se.lublin.mumla.app.MumlaActivity
```

---

## Lineage & License

- **[Plumble](https://github.com/acomminos/Plumble) / [Jumble](https://github.com/acomminos/Jumble)** — Andrew Comminos (2013–2015)
- **[Mumla](https://gitlab.com/quite/mumla) / [Humla](https://gitlab.com/quite/humla)** — Daniel Lublin (2020–2026)
- **Mumla OLED** — 360ied & contributors (2026–present)

Push-to-talk audio cues (`ptt_on.ogg`, `ptt_off.ogg`) are from the [Mumble](https://www.mumble.info) project (3-Clause BSD). Audio codecs and DSP use [Opus](https://opus-codec.org/), Speex DSP, and [RNNoise](https://jmvalin.ca/demo/rnnoise/) by Jean-Marc Valin / Xiph.Org / Mozilla.

Licensed under the **GNU General Public License v3.0 or later** ([GPL-3.0-or-later](LICENSE)).
