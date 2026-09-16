# Audio Output Pipeline

Reference for the Mumla OLED playback path: transport parsing in Java,
all DSP in the native output engine, PCM sink via `AudioTrack`.

## Data flow

```text
UDP datagram / protobuf Audio
  → AudioOutput.queueVoiceData / queueProtobufVoiceData   (Java, transport parse)
  → NativeAudioOutputEngine.queuePacket                   (JNI)
  → AudioOutputEngine::queuePacket                        (per-user Speex jitter buffer)
  → AudioOutputEngine::renderMix                          (decode → FEC/PLC → xfade → fade → mix → saturate)
  → AudioTrack.write (mono 16-bit PCM 48 kHz)             (single render thread)
```

## Files

| Layer | File |
|---|---|
| Transport parse, `AudioTrack` lifecycle, render thread | `libraries/humla/src/main/java/se/lublin/humla/audio/AudioOutput.java` |
| JNI bridge | `libraries/humla/src/main/java/se/lublin/humla/audio/NativeAudioOutputEngine.java`, `libraries/humla/src/main/jni/audio_engine/NativeAudioOutputEngineJni.cpp` |
| Jitter, decode, mix, saturation | `libraries/humla/src/main/jni/audio_engine/AudioOutputEngine.{h,cpp}` |
| Opus decoder wrapper | `libraries/humla/src/main/jni/audio_engine/OpusVoiceDecoder.{h,cpp}` |
| Speex jitter buffer (in-tree) | `libraries/humla/src/main/jni/audio_engine/jitter/` |
| Constants (`SAMPLE_RATE`, `FRAME_SIZE`) | `libraries/humla/src/main/java/se/lublin/humla/protocol/AudioHandler.java` |

## Key parameters

| Symbol | Value | Meaning |
|---|---|---|
| `SAMPLE_RATE` / `FRAME_SIZE` | 48000 Hz / 480 samples | Mono fullband, 10 ms frames |
| `RENDER_SAMPLES` | 960 samples | 20 ms render quantum pulled per loop |
| Track buffer | `max(minBytes, 2 quanta)` | Floor of ~40 ms plus the hardware minimum |
| `m_jitterMarginFrames` | 4 frames | 40 ms jitter margin floor; Speex adapts upward on bad links |
| `STARTUP_QUIET_FRAMES` | 2 frames | ~20 ms pre-roll; buffered packets play with no gating |
| `DEAD_MISS_FRAMES` | 10 frames | Voice expiry after 100 ms of consecutive misses |
| `MAX_VOICES` | 32 | Evicts highest session id on join flood |
| `MAX_DECODE_SAMPLES` | 5760 samples | Caps 120 ms Opus bundles |
| Saturation knee | 0.5 (−6 dB) | Linear below, `1 − 0.5·exp(−2(|m| − 0.5))` above, asymptote 1.0, C1-smooth |
| `XFADE_SAMPLES` | 96 samples | 2 ms equal-power crossfade at real ↔ concealment joints |

## Threading and lifecycle

- One render thread (`THREAD_PRIORITY_URGENT_AUDIO`), 20 ms quanta. `renderMix`
  returns 0 when silent so the thread idles on a timed 20 ms wait instead of
  spinning zeros; the wait must stay timed because jitter startup/expiry timing
  advances per `renderMix` call.
- `queuePacket` runs on network threads; a single mutex guards all voice state.
  Decode runs inline in `renderMix` — cheap for a handful of mono streams, no
  cross-thread handoff latency.
- Talk-state callbacks are collected under lock, emitted after unlock.
- The track is never flushed on underrun, so gaps resume without clicks.
- Terminator packets drain buffered audio plus a 10 ms fade-out instead of
  cutting; decode failures recycle the decoder after 5 consecutive errors and
  conceal the slot so rendering still advances.

## Known quality gaps

Tracked in [quality-improvements.md](quality-improvements.md): dropped
per-source gain (`volume_adjustment`, listening volume), no output leveling,
int16/fixed-rate sink, mono-only output.
