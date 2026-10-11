# Echo Cancellation Plan (Platform AEC, Route-Dependent)

Status: design exploration, not yet implemented. No AEC exists anywhere
in the pipeline today; this is the agreed pareto improvement over status
quo before any software-AEC work is considered.

## Why echo happens here

Capture ([`AudioInput`](../../libraries/humla/src/main/java/se/lublin/humla/audio/AudioInput.java))
feeds 10 ms frames into the native
[`AudioInputEngine`](../../libraries/humla/src/main/jni/audio_engine/AudioInputEngine.cpp)
(HPF, RNNoise, VAD, leveler, Opus), while render
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
- **On-BT A2DP / wired headset:** unchanged media path — `MIC` source,
  `USAGE_MEDIA`, mode `NORMAL`, no AEC attempted. Echo is physically
  minimal with buds in ears, and A2DP stereo quality is preserved
  (SCO mono at 8/16 kHz is audibly worse and must not become the
  default).

The VoIP stack is all-or-nothing per route: source, mode, and usage
must agree. Keeping `STREAM_MUSIC`/`USAGE_MEDIA` playback while hoping
for platform AEC does not work — the HAL declines the coupled loop
there.

## Accepted gap

Bluetooth loudspeaker (A2DP party speaker leaking into the mic) stays
without AEC, exactly as today. Neutral, not a regression: no route gets
worse, phone speaker/earpiece get strictly better. Software AEC
(SpeexDSP `mdf` or WebRTC AEC3 with a far-end reference tap from the
output mix into the input engine) is the documented future fix for that
hole only, and is deliberately not built now.

## What "music" does today (for the record)

The `STREAM_MUSIC` branch is routing, not DSP: loudspeaker-by-default,
media volume slider for volume keys (`MumlaActivity`) and PTT beeps
(`MumlaService.initSoundPool`), A2DP over Bluetooth, `MIC` capture
source at connect time, mode `NORMAL` (only `BluetoothScoManager`
touches `MODE_IN_COMMUNICATION`). There is also a live-toggle
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

## Deliberate non-goal: no software-AEC stub

No `IEchoCanceller` interface, JNI surface, or settings flag is added
with this change. An unused per-frame hook would not prototype any of
the hard part (cross-engine reference tap, delay alignment, thread
discipline) and would rot under the verification gate. The one thing
left behind is a seam, not a stub: a comment at the top of
`AudioInputEngine::processFrame` recording the ordering contract that
any future canceller runs **first, before HPF/RNNoise/VAD**, with the
mic frame keeping a single wrappable entry point.

## Verification

- AEC `isAvailable()`/enabled logging per device and route.
- Speakerphone double-talk test (echo audibly reduced, no pumping).
- BT earbud regression check (quality and route unchanged).
- Route-flap test (BT connect/disconnect mid-call: no stuck mode,
  no dead pipeline).
- SCO opt-in interplay check.
