# Bluetooth SCO Investigation

Investigation record for Bluetooth SCO (HFP) headset support in Mumla OLED:
the pre-0.22.0 audio pipeline, what the Android platform requires, what
prior art existed in the Jumble/Plumble ancestors, and which design shipped.

> **Status (0.22.0)**: Option A (manual toggle) is implemented and released.
> This dossier now records the as-built design; OQ-1 is answered, OQ-2–OQ-4
> remain open measurement questions, and Options B/C are future work.

## Verdict

SCO support was **worth building** as a manual opt-in toggle first — shipped
in 0.22.0 as "Two-way Bluetooth". The investigation found the 48 kHz
pipeline needs no resampling changes (the platform resamples the 8/16 kHz SCO
link), and the output `Pacer` + native jitter buffer are expected to accommodate
SCO-grade latency — still pending on-device validation (see [Android platform requirements](android-platform.md)). The bulk of the work was lifecycle, not DSP: permissions, `AudioManager` mode
management, async link setup/teardown, and route-failure fallback. None of that
machinery existed in the tree before; it now lives in `BluetoothScoManager`,
owned by `HumlaService`.

## Contents

1. [Current pipeline and gaps](current-pipeline.md) — pre-0.22.0 audio routing,
   the integration points SCO needed, and each gap's resolution status.
2. [Android platform requirements](android-platform.md) — HFP/WBS audio reality,
   permissions, lifecycle APIs, and the `startBluetoothSco` →
   `setCommunicationDevice` migration.
3. [Prior art: Jumble/Plumble](prior-art.md) — the SCO implementation this
   codebase descended from, what it did, and why it was dropped.
4. [Design options and recommendation](design-options.md) — the three scoped
   options considered, the phased plan adopted, and the as-built deltas.
5. [Toggle unification plan](toggle-unification-plan.md) — proposal to unify
   the Settings checkbox and the channel-overflow item into a single
   requested-state toggle with Settings semantics.

## Key facts (starting position, pre-0.22.0)

| Fact | What happened |
|---|---|
| No `AudioManager` route-control existed anywhere in the tree | Now owned by `HumlaService` via the new `BluetoothScoManager` |
| Capture and playback are fixed 48 kHz mono | No DSP changes required; platform resamples SCO |
| `targetSdk 36`, `minSdk 21` | `BLUETOOTH_CONNECT` runtime permission (API 31+) plus legacy flags; API-dependent routing code paths |
| Ancestor `BluetoothScoReceiver` + `setBluetoothEnabled` existed | Reintroduced in hardened form (timeouts, reason-coded failures, owned mode restore) |
| `startBluetoothSco` is deprecated from API 33 | Branched as proposed: `setCommunicationDevice` on API 31+, legacy SCO path below 31 |

## Component touch list (as built in 0.22.0)

| Layer | File | Change |
|---|---|---|
| Manifest | `app/src/main/AndroidManifest.xml` | `BLUETOOTH_CONNECT`, legacy `BLUETOOTH`/`BLUETOOTH_ADMIN` (with `maxSdkVersion="30"`) |
| Settings | `app/src/main/res/xml/settings_audio.xml`, `Settings.java` | Bluetooth toggle preference |
| Connect path | `app/src/main/java/se/lublin/mumla/app/ServerConnectTask.java` | Pass Bluetooth preference into service extras |
| Service | `libraries/humla/src/main/java/se/lublin/humla/HumlaService.java` | SCO lifecycle owner; drives `BluetoothScoManager`, recreates pipeline on confirmed route change |
| Audio | `libraries/humla/src/main/java/se/lublin/humla/protocol/AudioHandler.java` | Voice-call stream on confirmed SCO only; capture source unchanged (OQ-2 open) |
| UI | `MumlaActivity.java`, `ChannelFragment.java` | Checkable channel-overflow item with confirmed-state checkmark; failure/fallback toasts; volume, cues, and proximity follow confirmed state |

## Open questions for implementation

- **OQ-1**: Manual toggle only, or auto-start SCO when an HFP device connects
  mid-call? **Answered: manual toggle shipped** (Settings checkbox plus channel
overflow item); auto-routing deferred to Option B.
- **OQ-2**: Should SCO force `VOICE_COMMUNICATION` capture source, or keep the
  existing `MIC`/`DEFAULT` selection? Needs on-device measurement.
- **OQ-3**: Is `AcousticEchoCanceler` needed for the headset speaker→mic path?
  Currently unused; SCO coupling may demand it.
- **OQ-4**: VAD threshold retuning for band-limited SCO mic input?
