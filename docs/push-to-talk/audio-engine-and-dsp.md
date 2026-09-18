# Native Audio DSP & Transport Layer

This document details the low-level digital signal processing (DSP), buffering, encoding, and transport behavior of Push-to-Talk in Mumla OLED.

## Table of Contents

1. [Native Ingestion Pipeline](#native-ingestion-pipeline)
2. [Defect Deep-Dive: Terminator Packet Dropping (PTT-01)](#defect-deep-dive-terminator-packet-dropping-ptt-01)
3. [Architectural Evaluation: Pre-Speech Ring Buffer in PTT (PTT-05)](#architectural-evaluation-pre-speech-ring-buffer-in-ptt-ptt-05)
4. [Defect Deep-Dive: Abrupt Stream Cutoff & Lack of PTT Hangover (PTT-06)](#defect-deep-dive-abrupt-stream-cutoff--lack-of-ptt-hangover-ptt-06)
5. [VAD Co-Execution & Metering Gaps](#vad-co-execution--metering-gaps)
6. [Native Test Coverage Assessment (PTT-15)](#native-test-coverage-assessment-ptt-15)

---

## Native Ingestion Pipeline

Audio ingestion runs on a dedicated high-priority thread managed by [`AudioInput.java`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/java/se/lublin/humla/audio/AudioInput.java#L189-L228) (`Process.THREAD_PRIORITY_URGENT_AUDIO`).

```text
AudioRecord (Mono 16-bit PCM @ 48 kHz)
   │  pulls 480 samples (10ms) per read()
   ▼
AudioInput.AudioInputListener.onAudioInputReceived()
   ▼
NativeAudioInputEngine.processFrame() [JNI]
   ▼
AudioInputEngine::processFrame(const int16_t* pcm, size_t sampleCount)
```

Inside [`AudioInputEngine::processFrame`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/jni/audio_engine/AudioInputEngine.cpp#L52-L166), each 10ms frame passes through the following stages:

```
[1. Copy & Zero-Pad] ──► [2. Biquad HPF (<90Hz)] ──► [3. RNNoise Denoiser]
                                                            │
                                                            ▼
[6. Accumulate / Encode] ◄── [5. Adaptive Leveler] ◄── [4. InputMode Gate]
```

1. **Biquad High-Pass Filter**: 2nd-order Butterworth filter attenuating infrasonic rumble below 90 Hz.
2. **Neural Denoising (RNNoise)**: Recurrent neural network producing denoised audio and a voice probability metric ($p_{\text{speech}} \in [0.0, 1.0]$).
3. **InputMode Gate**: Evaluates whether the frame should be transmitted:
   ```cpp
   switch (m_inputMode) {
       case InputMode::CONTINUOUS:
           shouldTransmit = true;
           m_vad.process(m_processedFrame.data(), SAMPLES_PER_10MS, speechProb);
           break;
       case InputMode::PUSH_TO_TALK:
           shouldTransmit = m_pttTalking;
           m_vad.process(m_processedFrame.data(), SAMPLES_PER_10MS, speechProb);
           break;
       case InputMode::VOICE_ACTIVITY:
       default:
           shouldTransmit = m_vad.process(m_processedFrame.data(), SAMPLES_PER_10MS, speechProb);
           break;
   }
   if (m_muted) {
       shouldTransmit = false;
   }
   ```
4. **Adaptive Leveler & Soft Limiter**: RMS leveling targeting speech segments, followed by smooth hyperbolic soft-saturation.
5. **Frame Accumulator**: Buffers 10ms frames into packets sized by `m_framesPerPacket` (default 2 frames = 20ms = 960 samples).
6. **Opus Voice Encoder**: Constant Bitrate (CBR) Opus encoding (default 40,000 bps).

---

## Defect Deep-Dive: Terminator Packet Dropping (PTT-01)

### The Mechanism

In the Mumble protocol ([`MumbleUDP.proto:53`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/MumbleUDP.proto#L53)), the final packet of any voice transmission must carry the flag `is_terminator = true` (or bit 13 in the legacy UDP voice header).

The receiving client's jitter buffer ([`AudioOutputEngine.cpp`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/jni/audio_engine/AudioOutputEngine.cpp)) relies on this flag to perform a graceful 10ms cosine fade-out and cleanly tear down the playback voice slot. If no terminator flag is received, the remote jitter buffer assumes the packet was lost in transit and triggers Packet Loss Concealment (PLC). It continues synthesizing extrapolated audio for up to `DEAD_MISS_FRAMES` (10 frames = 100ms) until declaring the stream dead, generating distinct robotic buzzing or stuttering at the end of every utterance.

### The Code Flaw

In [`AudioInputEngine.cpp:127-133`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/jni/audio_engine/AudioInputEngine.cpp#L127-L133):

```cpp
} else if (m_talking && !shouldTransmit) {
    // Speech terminated: Flush any remaining audio in accumulator with isTerminator = true
    if (m_accumulatedFrames > 0) {
        flushAccumulatorLocked(true, packetsToDispatch);
    }
    m_ringBuffer.clear();
}
```

Now inspect [`AudioInputEngine::flushAccumulatorLocked`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/jni/audio_engine/AudioInputEngine.cpp#L168-L173):

```cpp
void AudioInputEngine::flushAccumulatorLocked(bool isTerminator, std::vector<DispatchedPacket>& packetsOut) {
    if (m_accumulatedFrames == 0) {
        return;
    }
    ...
```

### Mathematical Analysis of Packet Boundary Drop Probability

Let $N$ be the number of frames per packet (`m_framesPerPacket`). With standard 20ms Opus packets, $N = 2$.
Each 10ms frame increases `m_accumulatedFrames`. When `m_accumulatedFrames == N`, a standard packet (`isTerminator = false`) is dispatched and `m_accumulatedFrames` is reset to 0:

```text
Frame 1 (10ms): accumulated = 1
Frame 2 (20ms): accumulated = 2 ──► flushAccumulatorLocked(false) ──► accumulated = 0
Frame 3 (30ms): accumulated = 1
Frame 4 (40ms): accumulated = 2 ──► flushAccumulatorLocked(false) ──► accumulated = 0
...
Frame 2k (20k ms): accumulated = 2 ──► flushAccumulatorLocked(false) ──► accumulated = 0
```

When the user releases the PTT button, the key release event arrives asynchronously relative to the audio clock. The release falls on frame index $t \pmod N$.

The probability that the release occurs precisely after an even number of frames (when `m_accumulatedFrames == 0`) is:

```math
P(\text{no terminator}) = \frac{1}{N}
```

- For $N = 2$ (20ms packets, default): **50% of all PTT releases fail to send a terminator packet**.
- For $N = 4$ (40ms packets): **25% of releases fail to send a terminator packet**.
- For $N = 1$ (10ms packets): **100% of releases fail to send a terminator packet** because `m_accumulatedFrames` is always reset to 0 after every frame!

### Impact & Symptoms

When `m_accumulatedFrames == 0`:
1. `flushAccumulatorLocked(true, ...)` is skipped entirely.
2. Transmission halts abruptly.
3. Every remote participant on the server experiences 100ms of PLC error concealment and robotic stutter.

---

## Architectural Evaluation: Pre-Speech Ring Buffer in PTT (PTT-05)

### The Mechanism

[`PreSpeechRingBuffer`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/jni/audio_engine/PreSpeechRingBuffer.h) maintains an 8-frame (80ms) circular buffer of past PCM samples.

In Voice Activity Detection (VAD) mode, this lookahead buffer is essential: neural networks and energy detectors require 20–40ms of speech energy to exceed onset thresholds. Flushing the lookahead buffer ensures that leading unvoiced consonants (/p/, /t/, /k/, /s/) are not clipped.

### The Original Finding

In [`AudioInputEngine.cpp:116-126`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/jni/audio_engine/AudioInputEngine.cpp#L116-L126):

```cpp
if (!m_talking && shouldTransmit) {
    // Speech onset: Flush the 80ms lookahead ring buffer through the encoder
    m_ringBuffer.flush([this, &packetsToDispatch](const int16_t* bufferedPcm, size_t len) {
        std::memcpy(&m_accumulatedPcm[m_accumulatedFrames * SAMPLES_PER_10MS],
                    bufferedPcm, len * sizeof(int16_t));
        m_accumulatedFrames++;
        m_frameCounter++;
        if (m_accumulatedFrames >= static_cast<size_t>(m_framesPerPacket)) {
            flushAccumulatorLocked(false, packetsToDispatch);
        }
    });
}
```

Crucially, [`m_ringBuffer.push`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/jni/audio_engine/AudioInputEngine.cpp#L154-L157) is continuously fed whenever the client is unmuted:

```cpp
} else if (!m_muted) {
    // Silence: store into lookahead ring buffer (only when not muted)
    m_ringBuffer.push(m_processedFrame.data(), SAMPLES_PER_10MS);
}
```

PTT-05 originally noted that in Push-to-Talk mode, flushing this 80ms buffer could transmit the acoustic "thump" of a finger striking the touchscreen, a mechanical switch click, or a sharp pre-speech breath.

### Resolution: Closed as Won't Fix (Working as Intended)

Following thorough review, this behavior was reclassified as **Working as Intended** and marked **Closed (Won't Fix)** for the following architectural and psychoacoustic reasons:

1. **Android Capacitive Touch Latency ($\approx 30\text{--}60\text{ms}$)**:
   Physical touch contact on Android is not instantaneous. Between hardware touch digitizer scanning/debounce, Linux `evdev`, Android `InputDispatcher`, UI Looper/Choreographer dispatch, and JNI bridging into [`AudioInputEngine::setPttTalking`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/jni/audio_engine/AudioInputEngine.cpp#L240), an unavoidable delay of 30–60ms elapses.
   Because the low-latency Oboe/AAudio recording stream is active continuously, clearing the ring buffer on PTT onset discards all speech captured during this physical touch latency window.

2. **Human Coarticulation & Speech Anticipation**:
   Speakers routinely begin vocalizing simultaneously with or slightly before their finger makes contact with the screen. Discarding the pre-speech buffer in PTT mode guarantees clipping of leading plosives and unvoiced consonants (/p/, /t/, /k/, /s/), causing severe conversational degradation ("...opy that" instead of "Copy that").

3. **Acoustic Mitigation via High-Pass Filtering and Neural Denoising**:
   - The infrasonic high-pass filter (<90Hz) in [`AudioInputEngine::processPcm`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/jni/audio_engine/AudioInputEngine.cpp#L77) strips out the sub-bass mechanical chassis thump from touchscreen taps.
   - [`RNNoise`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/jni/audio_engine/AudioInputEngine.cpp#L80) processes frames *prior* to [`m_ringBuffer.push`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/jni/audio_engine/AudioInputEngine.cpp#L154-L157), suppressing non-speech transients during silence.

4. **Privacy & Duration**:
   80ms ($0.08\text{s}$) is less than the duration of an average phoneme or syllable; it is physically impossible to leak intelligible private speech.

5. **Symmetry with PTT-06 (PTT Release Hangover)**:
   The pre-speech ring buffer protects the **head** (speech onset) against touch latency, while PTT release hangover ([PTT-06](#defect-deep-dive-abrupt-stream-cutoff--lack-of-ptt-hangover-ptt-06)) protects the **tail** (speech termination) against premature button release. Retaining the 80ms lookahead ensures natural, unclipped voice transmission.

---

## Defect Deep-Dive: Abrupt Stream Cutoff & Lack of PTT Hangover (PTT-06)

In natural conversation, speakers frequently release a PTT button slightly before completing the final phoneme of their utterance.

### Comparison: VAD vs PTT

In VAD mode, [`HysteresisVad`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/jni/audio_engine/HysteresisVad.h) enforces hangover frames:

```cpp
void AudioInputEngine::setVadHoldFrames(uint32_t holdFrames) {
    std::lock_guard<std::mutex> lock(m_mutex);
    m_vad.setHoldFrames(holdFrames);
}
```

The VAD continues transmitting for `m_holdFrames` (typically 20–40 frames = 200–400ms) after speech probability drops below threshold, preventing word clipping.

### PTT Reality

In PTT mode, line 90 binds transmission strictly to the instantaneous button state:

```cpp
case InputMode::PUSH_TO_TALK:
    shouldTransmit = m_pttTalking;
```

The instant `m_pttTalking` becomes false, `shouldTransmit` becomes false. There is **zero hangover time**. The final syllable or consonant of a sentence is clipped unless the user deliberately holds the button for a quarter-second after finishing their sentence.

---

## VAD Co-Execution & Metering Gaps

In [`AudioInputEngine.cpp:91`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/jni/audio_engine/AudioInputEngine.cpp#L91):

```cpp
case InputMode::PUSH_TO_TALK:
    shouldTransmit = m_pttTalking;
    m_vad.process(m_processedFrame.data(), SAMPLES_PER_10MS, speechProb);
    break;
```

The VAD is executed on every frame even when in PTT mode. This updates `m_vad.m_peakEnergy`.

However, look at how `talkingCb` is invoked in lines 111-114:

```cpp
if (shouldTransmit != m_talking) {
    notifyTalking = true;
    talkingState = shouldTransmit;
    peakEnergy = m_vad.getPeakEnergy();
    ...
```

`talkingCb` is **only invoked on state transitions** (when PTT is first pressed or released). Throughout the duration of the transmission, no energy updates are emitted to Java. The user interface has no real-time mic volume level meter while talking in PTT mode.

---

## Native Test Coverage Assessment (PTT-15)

The native test harness in [`libraries/humla/src/test/cpp/`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/test/cpp/) verifies several isolated DSP primitives:

| Test File | Component Tested | Coverage |
|---|---|---|
| [`test_adaptive_leveler.cpp`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/test/cpp/test_adaptive_leveler.cpp) | `AdaptiveLeveler` | Attack/decay dynamics, gain clamps |
| [`test_biquad_filter.cpp`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/test/cpp/test_biquad_filter.cpp) | `BiquadFilter` | Frequency attenuation |
| [`test_hysteresis_vad.cpp`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/test/cpp/test_hysteresis_vad.cpp) | `HysteresisVad` | Squelch, hold frames, thresholds |
| [`test_pre_speech_ring_buffer.cpp`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/test/cpp/test_pre_speech_ring_buffer.cpp) | `PreSpeechRingBuffer` | Push, pop, overflow clearing |
| [`test_soft_limiter.cpp`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/test/cpp/test_soft_limiter.cpp) | `SoftLimiter` | Saturation curves |
| [`test_audio_output_engine.cpp`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/test/cpp/test_audio_output_engine.cpp) | `AudioOutputEngine` | Jitter, FEC, PLC, mixing |

### Missing Coverage

There are **zero unit tests** for:
1. `AudioInputEngine` class lifecycle and frame processing.
2. `InputMode::PUSH_TO_TALK` state transitions.
3. Terminator packet emission on release across varied frame boundaries ($t \equiv 0 \pmod N$ vs $t \not\equiv 0 \pmod N$).
4. Lookahead ring buffer bypassing during PTT onset.
5. Mute gating interactions with PTT.
