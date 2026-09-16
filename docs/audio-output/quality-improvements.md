# Audio Output: Quality Improvement Notes

Prioritized gaps in the playback path (`AudioOutputEngine.cpp`, `AudioOutput.java`).
Highest value first. Items 1–2 and 5 form the tight low-risk batch.

## 1. In-band FEC never used

`renderMix` always calls `decodeFloat(..., decodeFec=0)`; any non-`JITTER_BUFFER_OK`
result falls straight to `decodeConcealment`. On lossy/mobile networks a missing
frame is concealed even when the following packet carries Opus in-band FEC that
could reconstruct it.

- Fix: on `MISSING`/`LOST`, peek the next buffered packet and retry the missing
  frame with `decodeFec=1`.
- Risk: low. Purely additive on the loss path; the no-loss path is untouched.

## 2. Clicks at loss boundaries

Fade in/out (one `FRAME_SIZE`) applies only at voice start and at terminator/miss
expiry. Real ↔ concealed frame transitions mid-utterance have no crossfade, the
most audible artifact left in the pipeline.

- Fix: 2–5 ms equal-power crossfade around concealed frames.
- Risk: low. Local to the per-voice scratch assembly in `renderMix`.

## 3. Per-source gain dropped on the floor

- `queueProtobufVoiceData` ignores `MumbleUDP.Audio.volume_adjustment`.
- `Channel.listeningVolume` (stored by `ModelHandler`) is never read by the
  output path.
- No per-user local volume stage exists at all.

Quiet/loud speakers stay that way and server-side intent is silently ignored.

- Fix: plumb a gain per packet/voice (protobuf field → voice gain, listening
  volume → channel gain), apply pre-mix before `saturateSample` so the soft
  knee still guards the bus.
- Risk: medium. Touches the Java→JNI contract (`queuePacket` signature) and
  needs a gain-combining rule; verify against upstream 1.5+ semantics.

## 4. No output leveling

`saturateSample` prevents clipping but does not match levels across speakers; a
single quiet talker stays quiet while overlapping talkers get compressed.

- Fix: per-voice slow AGC (mirror of the input `AdaptiveLeveler`: slow attack,
  bounded range, no fast pumping).
- Risk: medium-high. Leveling taste varies and pumping artifacts are easy to
  add; do item 3 first, then evaluate whether AGC is still needed.

## 5. No output high-pass

Input runs a 90 Hz `BiquadFilter` HPF before denoise/VAD; output decodes
straight to the mix. DC offset and rumble waste headroom, mostly on phone
speakers.

- Fix: float HPF around 80 Hz on the mix bus (or per voice); `BiquadFilter`
  needs a float overload first.
- Risk: low. Linear, stateless across voices if placed on the bus.

## 6. First-utterance latency stack (~300 ms)

Two fixed delays add up before the Speex buffer's own delay:

| Source | Cost |
|---|---|
| `m_jitterMarginFrames = 10` (100 ms), no call site for `setJitterMarginFrames` in the Java/app tree | 100 ms every utterance |
| `STARTUP_QUIET_FRAMES = 20` (200 ms pre-roll before `started=true`) | 200 ms first utterance |

- Fix: start playout after 1–2 buffered frames instead of 20; make the margin
  adaptive (good WiFi ~40–60 ms) or at least a user setting.
- Risk: medium. Lower margins trade robustness for latency; needs real-network
  testing on jittery links, not just localhost.

## 7. Int16, fixed-48 kHz sink

The engine mixes float, quantizes to int16 via `lroundf`/clamp, and the
framework may resample again to the device native rate.

- Fix: `ENCODING_PCM_FLOAT` track (API 21+) and/or match the device native
  sample rate.
- Risk: low, but audibility is minor compared to items 1–6.

## 8. Mono only, no positional audio

`CHANNEL_OUT_MONO` plus a mono decoder cannot separate overlapping talkers
spatially, the biggest intelligibility win in crowded channels — and the
biggest change (stereo track, per-user pan, positional data parsing that does
not exist yet in the output path).

- Risk: high effort, protocol surface to verify against upstream. Only if
  crowded-channel use justifies it.
