# Echo Cancellation (Platform AEC, Route-Dependent)

Status: shipped on `master` (`feature/platform-aec`, merged as `66da8618`).
Route-dependent platform `AcousticEchoCanceler` and the service-owned VoIP
route policy are live; software AEC remains a deliberate non-goal.

## Why echo happens here

Capture ([`AudioInput`](../../libraries/humla/src/main/java/se/lublin/humla/audio/AudioInput.kt))
feeds 10 ms frames into the native
[`AudioInputEngine`](../../libraries/humla/src/main/jni/audio_engine/AudioInputEngine.cpp)
(copy, HPF, RNNoise, VAD transmit evaluation, leveler applied after
the VAD decision, Opus encode), while render
([`AudioOutput`](../../libraries/humla/src/main/java/se/lublin/humla/audio/AudioOutput.kt))
pulls mixed PCM out of
[`AudioOutputEngine`](../../libraries/humla/src/main/jni/audio_engine/AudioOutputEngine.cpp).
The two sides never share a signal: RNNoise, the hardware
`NoiseSuppressor`/`AutomaticGainControl`, and the leveler are all
single-ended (mic only). None of them has ever seen what the speaker
played, so speaker leakage is transmitted as speech. Cancellation
requires a far-end (speaker) reference, which only exists on a coupled
capture/render path.

## Decision: platform AEC, routed — not software AEC

Use Android's `AcousticEchoCanceler` (attached to the capture session
like the existing NS/AGC) on routes where the HAL can couple the loop,
and leave the media path untouched where it cannot. Concretely:

- **Off-BT (phone speaker / earpiece):** full VoIP path —
  `VOICE_COMMUNICATION` capture source,
  `USAGE_VOICE_COMMUNICATION` playback,
  `MODE_IN_COMMUNICATION` held for the connection, AEC attached with
  availability/enabled logging. This is where echo is worst and where
  OEM AEC tuning is aimed (speakerphone echo is the primary test case).
- **On-BT A2DP / wired headset:** media path — capture source `MIC`,
  `USAGE_MEDIA` (`STREAM_MUSIC`), mode untouched (released back to the
  pre-connection mode, normally `NORMAL`), and the HAL declines AEC on the
  uncoupled media session.
  Echo is physically minimal with buds in ears, and A2DP stereo quality
  is preserved (SCO mono at 8/16 kHz is audibly worse and must not
  become the default). BLE/LE Audio headsets and hearing aids fall in
  this bucket too: they ride the media path like A2DP, never touch the
  SCO manager, and are inside the accepted gap below.

The VoIP stack is all-or-nothing per route: source, mode, and usage
must agree. Keeping `STREAM_MUSIC`/`USAGE_MEDIA` playback while hoping
for platform AEC does not work — the HAL declines the coupled loop
there.

## Accepted gap

Bluetooth loudspeaker (A2DP party speaker leaking into the mic) and
BLE/LE Audio headsets routed as media stay without AEC, exactly as
today. Neutral, not a regression: no route gets worse, phone
speaker/earpiece get strictly better. Software AEC
(SpeexDSP `mdf` or WebRTC AEC3 with a far-end reference tap from the
output mix into the input engine) is the documented future fix for that
hole only, and is deliberately not built now.

## Pre-AEC baseline (for the record)

Before `feature/platform-aec`, the `STREAM_MUSIC` branch was routing, not
DSP: default routing (typically the loudspeaker, never forced), media
volume slider for volume keys (`MumlaActivity`) and PTT beeps
(`MumlaService.initSoundPool`), A2DP over Bluetooth, connect-time
source `MIC` — except handset-started sessions, which used `DEFAULT`
plus `STREAM_VOICE_CALL` — and mode untouched (normally `NORMAL`; only
`BluetoothScoManager` ever called `setMode`). Toggling handset mode
mid-call also had a live-toggle asymmetry: it updated the audio stream
(recreating the pipeline) without updating the capture audio source.

## Landed touch points

1. [`HumlaService.updateRouteBuilderLocked`](../../libraries/humla/src/main/java/se/lublin/humla/HumlaService.kt) —
   derives both `audioSource` (`VOICE_COMMUNICATION` vs `MIC`) and
   `audioStream` (`STREAM_VOICE_CALL` / `mHandsetAudioStream` vs
   `STREAM_MUSIC`) from `computeVoiceRoute(sco, media)` on every route and
   `ConfigDelta` application (`ServerConnectTask` was slimmed in migration
   slice 2 to pass only the server ID; `AudioSource.DEFAULT` is retired).
2. [`AudioOutput`](../../libraries/humla/src/main/java/se/lublin/humla/audio/AudioOutput.kt)
   and [`AudioHandler`](../../libraries/humla/src/main/java/se/lublin/humla/protocol/AudioHandler.kt) —
   `AudioHandler.initialize` starts playback on `STREAM_VOICE_CALL`
   (`USAGE_VOICE_COMMUNICATION`) whenever `mVoiceRoute || mScoActive` and
   `STREAM_MUSIC` (`USAGE_MEDIA`) otherwise; handset toggle drives explicit
   speakerphone routing (`setSpeakerphone`) instead of a stream swap.
3. [`AudioInput`](../../libraries/humla/src/main/java/se/lublin/humla/audio/AudioInput.kt) —
   attaches `AcousticEchoCanceler` first inside `enableAudioEffects()`
   (before `NoiseSuppressor` and `AutomaticGainControl`) and logs
   availability, creation, and enabled state alongside `audioSource`.
4. Route management in [`HumlaService`](../../libraries/humla/src/main/java/se/lublin/humla/HumlaService.kt) —
   `applyAudioRoute` generalizes past SCO-only: A2DP/BLE/wired/USB
   attach/detach and `ConfigDelta` updates converge through one funnel that
   holds/releases `MODE_IN_COMMUNICATION`, recreates the pipeline, and
   notifies `onAudioRouteChanged` (`MumlaService` refreshes `SoundPool` and
   proximity lock; `MumlaActivity` updates `volumeControlStream`).
5. SCO opt-in interplay — `computeVoiceRoute(scoActive, mediaDevicesPresent)`
   force-selects the voice triple whenever confirmed SCO is active, winning
   over attached media sinks (pinned by `AudioRoutePolicyTest`).

## Mode ownership

One owner sets and clears `MODE_IN_COMMUNICATION`: the service-level
route policy in `HumlaService` (`ensureVoiceMode` / `releaseVoiceMode`).
Field logs during implementation (`2d5c6015`) proved that having
[`BluetoothScoManager`](../../libraries/humla/src/main/java/se/lublin/humla/audio/BluetoothScoManager.kt)
also call `setMode(MODE_IN_COMMUNICATION)` caused the service to classify
its own hold as externally owned and surrender its claim, stranding the
mode on later media switches. Mode ownership was therefore removed from
`BluetoothScoManager` entirely (the service holds the mode for the whole
voice-route duration, plus a pre-S voice-mode pre-hold in `HumlaService`
before legacy API 30 `startBluetoothSco()`). If `MODE_IN_COMMUNICATION` or
`MODE_IN_CALL` is already held externally (e.g. a phone call),
`ensureVoiceMode` claims nothing and leaves `mVoiceModeHeld` unset so the
next voice pass retries after the external holder releases.

## Speakerphone routing (explicit API)

Previously "handset mode" was implicit: `STREAM_VOICE_CALL` landed on the
earpiece by default and `STREAM_MUSIC` on the speaker, plus the proximity
wake lock for ear-held use. Under the VoIP policy both earpiece and
speakerphone share one voice triple (source/mode/usage) and differ only by
an explicit `AudioManager.setSpeakerphoneOn(bool)` call in
`AudioHandler.setSpeakerphone` (`!mScoActive && mAudioStream != STREAM_VOICE_CALL`
on the voice route; cleared on media routes for hygiene). (Bluetooth SCO
routing in `BluetoothScoManager` separately uses `setCommunicationDevice()`
on API 31+ with a legacy broadcast-receiver SCO backend on API 30 under
`minSdk 30`.) The handset toggle keeps meaning "earpiece" vs "speakerphone"
for UX and keeps driving the proximity lock; AEC covers both.

## Route-change detection

`HumlaService` registers an `AudioDeviceCallback` in `onCreate` for
wired/USB/A2DP/BLE attach and detach, debounced via `routeDebounceJob`
(`lifecycleScope`, 300 ms) into `applyAudioRoute`. Staggered Bluetooth
attaches (where HFP appears before A2DP) are covered by a follow-up
`routeSettleJob` recheck when voice was selected solely by media-sink
absence, and unrequested system HFP/SCO activations on media routes are
countered via `counterUnrequestedSystemSco` before pipeline creation.
Ordering per transition: set/release mode first, then pipeline recreate,
then `onAudioRouteChanged` (`SoundPool`, proximity sensor, and volume
stream refresh) — so capture effects attach only after the HAL sees the
target mode.

## Transition ordering (session-bound effects)

HAL coupling requires mode-before-capture: `MODE_IN_COMMUNICATION`
must be set before `AudioRecord` creation, and `AudioInput`
constructs its effects (NS/AGC, plus AEC on the VoIP route) at
construction time. So every route change is atomic at the triple
level: set mode, then pipeline recreate (which fully tears down
and rebuilds both engines), then cue/volume refresh. Failure and
fallback restore the previous route's triple atomically — a failed
SCO bring-up or a BT disconnect mid-recreate must never strand
`IN_COMMUNICATION` with a media pipeline or vice versa. The
route-flap verification below exists to prove this ordering.

## Deliberate non-goal: no software-AEC stub

No `IEchoCanceller` interface, JNI surface, or settings flag is added
with this change. An unused per-frame hook would not prototype any of
the hard part (cross-engine reference tap, delay alignment, thread
discipline) and would rot under the verification gate.

Effects interplay: `enableAudioEffects()` creates AEC first alongside the
existing NS/AGC and logs per-route availability plus enabled state for all
three effects (`VOICE_COMMUNICATION` invites HAL-baked preprocessing that
can interact with RNNoise/HW-NS, and OEM `isAvailable()` varies). Any
future software canceller in `AudioInputEngine::processFrame` must run
**first, before HPF/RNNoise/VAD**, with the mic frame keeping a single
wrappable entry point.

## Verification

- AEC `isAvailable()`/enabled logging per device and route.
- Speakerphone double-talk test (echo audibly reduced, no pumping).
- BT earbud regression check (quality and route unchanged).
- Route-flap test (BT connect/disconnect mid-call: no stuck mode,
  no dead pipeline, triple restored atomically).
- SCO opt-in interplay check (SCO start over held VoIP mode keeps the
  mode; SCO stop/fail never clears it).
