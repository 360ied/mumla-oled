# Audio Output: Quality Improvement Notes

Prioritized gaps in the playback path (`AudioOutputEngine.cpp`, `AudioOutput.java`).
Highest value first.

## 1. In-band FEC never used — IMPLEMENTED

Status: shipped on master. `renderMix` now reserves
the missing frame as a silent FEC-debt slot and defers concealment one frame:
when the next packet starts exactly at the pointer it is decoded with
`decodeFec=1` into the slot; anything else (jitter jump, no LBRR) falls back
to concealment, and unrecovered debt is PLC-filled at quantum end so decoder
state advances exactly as before. Debt is quantum-local, never carried over.
A miss arriving with debt outstanding settles the old slot via concealment
first (its frame is beyond LBRR range) and starts a fresh debt, so burst
loss chains correctly instead of playing the wrong frame's audio in a stale
slot. Our encoder always sends LBRR (`OPUS_SET_INBAND_FEC(1)`), so
Mumla-to-Mumla streams recover single losses near-perfectly.

## 2. Clicks at loss boundaries — IMPLEMENTED

Status: shipped on master. Real ↔ concealment chunk
boundaries (FEC recovery counts as real) blend over a 96-sample (2 ms)
equal-power crossfade (`XFADE_SAMPLES`, precomputed `m_xfadeIn/m_xfadeOut`
tables): two-sided within a quantum, one-sided from the tail snapshot's last
value across quanta (keeps the joint C0). Measured joint step 215 counts vs
~13100 unblended.

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

## 5. No output high-pass — REJECTED

Status: evaluated and rejected, not implemented.
Decoded Opus speech is already AC-coupled (unlike mic input, which needs its
90 Hz HPF against wind/handling noise), upstream Mumble does not filter
output, and any HPF attenuates the constant-DC levels the native suite pins
(e.g. the overlap-compression peak assertion collapses from ~31000 to
~6000). Marginal benefit for pathological streams only; the saturation knee
already bounds headroom. Revisit only with an AC-signal test corpus.

## 6. First-utterance latency stack — CUT and shipped on master

Was ~300 ms of stacked fixed delays; now ~100–165 ms typical, plus ~50 ms
of intentional burst-start hold (the gate waits for margin+1 frames of
queued audio, ~50 ms at the default margin 4) that buys back far more in
concealment artifacts it prevents:

| Source | Before | After |
|---|---|---|
| `m_jitterMarginFrames` (every utterance) | 10 frames (100 ms) | 4 frames (40 ms); also sizes the startup gate; Speex still adapts upward on bad links |
| Startup gate (upstream parity) | none: buffered packets played immediately, the loop free-ran into concealment on burst start | holds until margin+1 frames queued, force-start at `GATE_TIMEOUT_FRAMES` = 20 frames (200 ms) |
| Render quantum / idle wait (batching) | 60 ms | 20 ms |
| Track buffer floor | ~120 ms | ~40 ms plus the hardware minimum |
| Render lead | unbounded (loop sprinted into track slack) | 1 quantum past the playback head (or the track minimum if larger) |

Remaining risk: the leaner margin trades robustness for latency on jittery
links. Per-voice queue-to-playout delay is now logged on device
(`AudioOutputEngine` verbose tag, `first audio … ms after queue`), so tune
against those readings plus bad-link listening — not localhost. A
user-facing margin setting (`setJitterMarginFrames` exists with no callers)
is the follow-up if one size does not fit all.

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
