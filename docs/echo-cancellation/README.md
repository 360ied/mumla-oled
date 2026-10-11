# Echo Cancellation Plan (Platform AEC, Route-Dependent)

Status: design exploration, not yet implemented. No AEC exists anywhere
in the pipeline today; this is the agreed pareto improvement over status
quo before any software-AEC work is considered.

## Why echo happens here

Capture ([`AudioInput`](../../libraries/humla/src/main/java/se/lublin/humla/audio/AudioInput.java))
feeds 10 ms frames into the native
[`AudioInputEngine`](../../libraries/humla/src/main/jni/audio_engine/AudioInputEngine.cpp)
(copy, HPF, RNNoise, VAD transmit evaluation, leveler applied after
the VAD decision, Opus encode), while render
([`AudioOutput`](../../libraries/humla/src/main/java/se/lublin/humla/audio/AudioOutput.java))
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
- **On-BT A2DP / wired headset:** unchanged media path — connect-time
  source as today (`MIC`; handset-started sessions keep `DEFAULT`),
  `USAGE_MEDIA`, mode untouched (normally `NORMAL`), no AEC attempted.
  Echo is physically minimal with buds in ears, and A2DP stereo quality
  is preserved (SCO mono at 8/16 kHz is audibly worse and must not
  become the default). BLE/LE Audio headsets fall in this bucket too:
  they ride the media path like A2DP, never touch the SCO manager, and
  are inside the accepted gap below.

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

## What "music" does today (for the record)

The `STREAM_MUSIC` branch is routing, not DSP: default routing
(typically the loudspeaker, never forced), media volume slider for
volume keys (`MumlaActivity`) and PTT beeps
(`MumlaService.initSoundPool`), A2DP over Bluetooth, connect-time
source `MIC` — except handset-started sessions, which use `DEFAULT`
plus `STREAM_VOICE_CALL` (`ServerConnectTask`) — and mode untouched
(normally `NORMAL`; only `BluetoothScoManager` ever calls `setMode`).
There is also a live-toggle
asymmetry worth fixing in passing: toggling handset mode mid-call sends
`EXTRAS_AUDIO_STREAM` (recreating the pipeline) but never
`EXTRAS_AUDIO_SOURCE`, leaving capture stuck at its connect-time value.

## Touch points for the spike

1. `ServerConnectTask` — derive source/stream from route policy, not
   the handset boolean alone.
2. `AudioOutput` — usage mapping follows the policy; handset toggle
   becomes explicit speakerphone routing instead of a stack swap.
3. `AudioInput` — attach `AcousticEchoCanceler` on the VoIP route;
   fix the stale-source asymmetry above.
4. Route management — generalize past SCO-only: A2DP/wired
   connect/disconnect drives the existing
   `configureExtras`/`createAudioHandler` pipeline recreate, plus
   mode set/clear and volume/`SoundPool` route updates.
5. SCO opt-in interplay — `AudioHandler.initialize` already
   force-selects `VOICE_CALL` when SCO is up; keep that consistent
   with the policy.

## Mode ownership

One owner sets and clears `MODE_IN_COMMUNICATION`: the service-level
route policy (generalizing what `BluetoothScoManager` does for SCO
today), not the SCO manager and the policy independently. Set points:
connection established on the off-BT route, and route-enter off-BT
mid-call. Clear points: route-exit to BT/wired, and disconnect.
Disconnect cleanup must restore the saved (pre-connection) mode even
if a route change is in flight. SCO overlap rule: when SCO starts
while VoIP mode is already held, the SCO manager must treat the
current `IN_COMMUNICATION` as externally owned — snapshot it without
claiming ownership and skip the restore on stop/fail/drop, so it never
clears the policy's mode out from under it.

## Speakerphone routing (explicit API)

Today "handset mode" is implicit: `STREAM_VOICE_CALL` lands on the
earpiece by default, `STREAM_MUSIC` on the speaker, plus the proximity
wake lock for ear-held use. Under the VoIP policy both earpiece and
speakerphone share one triple (source/mode/usage) and differ only by
an explicit route call: legacy `AudioManager.setSpeakerphoneOn(bool)`
below API 31, `setCommunicationDevice()` with the matching
`AudioDeviceInfo` on API 31+ (minSdk 21, targetSdk 36 — both branches
stay, mirroring `BluetoothScoManager`). The handset toggle keeps
meaning "earpiece" vs "speakerphone" for UX and keeps driving the
proximity lock; AEC covers both. What changes is mechanism
(stream swap becomes route call), not meaning.

## Route-change detection

No A2DP/wired listener exists today (only the SCO manager observes
routes), and volume/`SoundPool` have no A2DP/wired trigger — all new.
Planned: `AudioDeviceCallback` (API 23+) for wired/A2DP attach/detach
plus `AudioManager.OnCommunicationDeviceChangedListener` on API 31+
for the communication-device view, funneled into one converge point in
the `applyScoRoute` style so BT, wired, and SCO transitions serialize
through a single route decision. Debounce rapid flaps (single short
quiesce, not per-event recreates). Ordering per transition: set
mode first, then `createAudioHandler` pipeline recreate, then
volume-stream/`SoundPool` refresh — so effects attach (at `AudioInput`
construction on the capture session) only after the HAL sees the
target mode.

## Transition ordering (session-bound effects)

HAL coupling requires mode-before-capture: `MODE_IN_COMMUNICATION`
must be set before `AudioRecord` creation, and `AudioInput`
constructs its effects (NS/AGC, plus AEC on the VoIP route) at
construction time. So every route change is atomic at the triple
level: set mode, then `createAudioHandler` (which fully tears down
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

Spike note on effects interplay: create AEC first inside
`enableAudioEffects()`, alongside the existing NS/AGC, and log
per-route availability plus enabled state for all three effects —
`VOICE_COMMUNICATION` invites HAL-baked preprocessing that can
double-process with RNNoise/HW-NS, and OEM `isAvailable()` varies, so
the spike must include a listen-check for double-suppression
(thinning, pumping, gated tails) on at least speakerphone and
earpiece routes.

The one thing left behind is a seam, not a stub: a comment at the top of
`AudioInputEngine::processFrame` recording the ordering contract that
any future canceller runs **first, before HPF/RNNoise/VAD**, with the
mic frame keeping a single wrappable entry point.

## Verification

- AEC `isAvailable()`/enabled logging per device and route.
- Speakerphone double-talk test (echo audibly reduced, no pumping).
- BT earbud regression check (quality and route unchanged).
- Route-flap test (BT connect/disconnect mid-call: no stuck mode,
  no dead pipeline, triple restored atomically).
- SCO opt-in interplay check (SCO start over held VoIP mode keeps the
  mode; SCO stop/fail never clears it).
