# Bluetooth SCO Investigation

Deep investigation into adding Bluetooth SCO (HFP) headset support to Mumla OLED:
where the audio pipeline stands today, what the Android platform requires, what
prior art existed in the Jumble/Plumble ancestors, and which design to implement.

## Verdict

SCO support is **worth building**, as a manual opt-in toggle first. The 48 kHz
pipeline needs no resampling changes (the platform resamples the 8/16 kHz SCO
link), and the output `Pacer` + native jitter buffer are expected to accommodate
SCO-grade latency — pending on-device validation (see [Android platform requirements](android-platform.md)). The real work is all lifecycle: permissions, `AudioManager` mode
management, async link setup/teardown, and route-failure fallback. None of that
machinery exists in the tree today.

## Contents

1. [Current pipeline and gaps](current-pipeline.md) — how audio routing works
   today, and the exact integration points SCO needs.
2. [Android platform requirements](android-platform.md) — HFP/WBS audio reality,
   permissions, lifecycle APIs, and the `startBluetoothSco` →
   `setCommunicationDevice` migration.
3. [Prior art: Jumble/Plumble](prior-art.md) — the SCO implementation this
   codebase descended from, what it did, and why it was dropped.
4. [Design options and recommendation](design-options.md) — three scoped
   options, the recommended phased plan, risks, and test matrix.

## Key facts

| Fact | Implication |
|---|---|
| No `AudioManager` route-control exists anywhere in the tree | Greenfield lifecycle owner needed (proposed: `HumlaService`) |
| Capture and playback are fixed 48 kHz mono | No DSP changes required; platform resamples SCO |
| `targetSdk 36`, `minSdk 21` | `BLUETOOTH_CONNECT` runtime permission (API 31+) plus legacy flags; API-dependent routing code paths |
| Ancestor `BluetoothScoReceiver` + `setBluetoothEnabled` existed | Proven shape to reintroduce, but its teardown and error handling need hardening |
| `startBluetoothSco` is deprecated from API 33 | New code should branch: `setCommunicationDevice` on API 31+, legacy SCO path below 31 (usable through 32) |

## Component touch list (anticipated)

| Layer | File | Change |
|---|---|---|
| Manifest | `app/src/main/AndroidManifest.xml` | `BLUETOOTH_CONNECT`, legacy `BLUETOOTH`/`BLUETOOTH_ADMIN` (with `maxSdkVersion="30"`) |
| Settings | `app/src/main/res/xml/settings_audio.xml`, `Settings.java` | Bluetooth toggle preference |
| Connect path | `app/src/main/java/se/lublin/mumla/app/ServerConnectTask.java` | Pass Bluetooth preference into service extras |
| Service | `libraries/humla/src/main/java/se/lublin/humla/HumlaService.java` | SCO lifecycle owner, receiver registration |
| Audio | `libraries/humla/src/main/java/se/lublin/humla/protocol/AudioHandler.java` | Route-aware stream/source selection, recreate on route change |
| UI | `MumlaActivity.java`, service notification | Route indicator, manual toggle entry point |

## Open questions for implementation

- **OQ-1**: Manual toggle only, or auto-start SCO when an HFP device connects
  mid-call? (Recommendation: manual first; see [design options](design-options.md).)
- **OQ-2**: Should SCO force `VOICE_COMMUNICATION` capture source, or keep the
  existing `MIC`/`DEFAULT` selection? Needs on-device measurement.
- **OQ-3**: Is `AcousticEchoCanceler` needed for the headset speaker→mic path?
  Currently unused; SCO coupling may demand it.
- **OQ-4**: VAD threshold retuning for band-limited SCO mic input?
