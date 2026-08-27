# Mumla Audio Input Architecture & Technical Notes

This document synthesizes our technical analysis, design trade-offs, security considerations, and architectural evaluations for audio input processing in Mumla and Humla.

---

## Table of Contents
1. [Audio Input Architecture Overview](#1-audio-input-architecture-overview)
2. [Architectural Assessment (Modern vs. Archaic vs. Idiosyncratic)](#2-architectural-assessment-modern-vs-archaic-vs-idiosyncratic)
3. [Voice Activity Detection (VAD) Comparative Analysis](#3-voice-activity-detection-vad-comparative-analysis)
4. [Audio Quality Improvement Vectors](#4-audio-quality-improvement-vectors)
5. [CBR vs. VBR & Cryptographic Traffic Analysis Side-Channels](#5-cbr-vs-vbr--cryptographic-traffic-analysis-side-channels)
6. [Design Concept: Pre-Speech Lookahead Ring Buffer](#6-design-concept-pre-speech-lookahead-ring-buffer)

---

## 1. Audio Input Architecture Overview

Audio input in Mumla is split between the Android application/service layer (`se.lublin.mumla`) and the core audio/protocol engine in **Humla** (`se.lublin.humla.audio`).

```
[ Microphone (Hardware) ]
           │
           ▼
[ AudioInput (AudioRecord Thread) ] ── (10ms PCM short[] frames @ URGENT_AUDIO priority)
           │
           ▼
[ AudioHandler.onAudioInputReceived() ]
    ├── 1. Gating & Input Mode Decision (IInputMode: PTT / VAD / Continuous)
    ├── 2. Mute / Deafen / Server Suppression Checks
    ├── 3. Amplitude Boost Scaling & Clamping
    │
    ▼ (if transmitting)
[ IEncoder Pipeline (Decorator Pattern) ]
    ├── ResamplingEncoder (Speex resampler to 48kHz if input != 48kHz)
    ├── PreprocessingEncoder (Speex DSP: AGC, Denoise, Dereverb)
    └── OpusEncoder / CELT Encoder (Compresses PCM frames into codec packets)
           │
           ▼ (when buffered frames == framesPerPacket or speech terminated)
[ AudioHandler.sendEncodedAudio() ]
    ├── Packet Framing (Legacy UDP Voice Packet OR MumbleUDP.Audio Protobuf)
    └── Dispatches to HumlaConnection -> UDP/TCP Socket
```

### Key Components

* **`AudioInput.java`**:
  * Runs a dedicated recording loop on a background thread (`Process.THREAD_PRIORITY_URGENT_AUDIO`).
  * Probes supported sample rates (`48000, 44100, 16000, 8000` Hz) for `AudioRecord` initialization.
  * Delivers raw 16-bit PCM mono frames (10ms slices) to `AudioInputListener`.
  * Manages hardware Acoustic Echo Cancellation (`AcousticEchoCanceler` + `AudioManager.MODE_IN_COMMUNICATION`).
* **`IInputMode.java`** (Gating):
  * `ToggleInputMode`: Push-to-talk (PTT) mode using thread suspension via `Condition.await()` during silence.
  * `ActivityInputMode`: Voice activity detection (VAD) via RMS energy calculation and a 250ms hangover timer.
  * `ContinuousInputMode`: Continuous transmission.
* **`IEncoder.java` Pipeline**:
  * `ResamplingEncoder`: Wraps Speex resampler if hardware capture rate != 48 kHz.
  * `PreprocessingEncoder`: Applies Speex DSP (Automatic Gain Control, Denoise, Dereverberation).
  * `OpusEncoder`: Encodes 10ms/20ms PCM frames to Opus audio packets via JavaCPP native bindings.
  * `CELT7Encoder` / `CELT11Encoder`: Backward compatibility for legacy Mumble servers.
* **`AudioHandler.java`**:
  * Orchestrates transmission, server-enforced bandwidth throttling, half-duplex stream muting, and UDP voice framing (Legacy Bitmask headers vs. Protobuf UDP `MumbleUDP.Audio`).
* **`HumlaService.java` & `MumlaService.java`**:
  * Coordinates Android lifecycle, Bluetooth SCO audio routing (`BluetoothScoReceiver`), user preferences, and IPC broadcast intents (`se.lublin.mumla.action.TALK`).

---

## 2. Architectural Assessment (Modern vs. Archaic vs. Idiosyncratic)

| Dimension | Classification | Assessment |
| :--- | :--- | :--- |
| **Capture Layer** | **Archaic** | Uses Java-space `AudioRecord` with a sample-rate probe loop (`48000 -> 44100 -> 16000 -> 8000`). Modern Android real-time audio uses **Google Oboe / AAudio** with MMAP low-latency fast-paths. |
| **Native Bindings** | **Idiosyncratic** | Uses **JavaCPP** annotations (`@Platform`, `Loader`, `@Cast`) for C/C++ library binding rather than standard CMake NDK / JNI wrappers. |
| **DSP Preprocessing** | **Archaic** | Relies on **SpeexDSP 1.2** (AGC, Denoise, Resampling). Modern stacks use **WebRTC APM** or neural models (**RNNoise**). |
| **PTT Thread Management** | **Idiosyncratic** | Suspends the recording thread using Java `Condition.await()` in `waitForInput()` rather than pausing `AudioRecord` or discarding frames. |
| **Packet Protocol** | **Modern** | Fully supports modern **Protobuf UDP** (`MumbleUDP.Audio`), negotiating between Protobuf and legacy bitmask framing based on server capability. |
| **Encoder Architecture** | **Modern / Clean** | Uses clean **Decorator pattern** (`ResamplingEncoder -> PreprocessingEncoder -> OpusEncoder`) with strict bandwidth throttling parity matching desktop Mumble reference code. |

---

## 3. Voice Activity Detection (VAD) Comparative Analysis

### How Mumla's VAD Works (`ActivityInputMode.java`)
1. **RMS Energy Calculation**: Computes time-domain Root Mean Square energy of 16-bit PCM samples over 10ms:
   $$\text{micLevel} = \sqrt{\frac{1}{N} \sum_{i=0}^{N-1} x[i]^2}$$
2. **Logarithmic Normalization**:
   $$\text{peakSignal} = \frac{20 \cdot \log_{10}(\text{micLevel} / 32768.0)}{96.0}$$
3. **Threshold & Hangover**:
   $$\text{talking} = (\text{peakSignal} + 1 \ge T_{\text{VAD}}) \lor (\Delta t_{\text{last\_speech}} < 250\text{ ms})$$

### Generation Comparison Matrix

| Feature | Mumla (`ActivityInputMode`) | Statistical VAD (WebRTC VAD) | Codec VAD (Opus DTX) | Neural VAD (Silero / RNNoise) |
| :--- | :--- | :--- | :--- | :--- |
| **Domain** | Time-domain RMS | 6 Sub-band spectral energy | Bark-scale bands + Pitch LPC | Multi-band spectral + Recurrent GRU/LSTM |
| **Noise Adaptation** | **None** (Static threshold) | **Dynamic** (Continuous noise floor estimation) | **Dynamic** (Tracks SNR & psychoacoustics) | **Learned** (Distinguishes human phonetics from noise) |
| **Breathing / Typing** | **Poor** (Triggers on volume) | **Fair** (Filters stationary hums) | **Good** (Checks vocal periodicity) | **Exceptional** (Rejects keyboard, breath, clicks) |
| **Fricatives (*s*, *th*, *f*)** | **Poor** (Quiet consonants cut off) | **Good** (Detects unvoiced spectral shape) | **Good** | **Exceptional** (Maintains speech phonetics) |
| **Memory / CPU** | 0% CPU, 0 KB RAM | ~0.5% CPU, <20 KB RAM | Built into Opus encoder | ~1% CPU, ~1.5 MB RAM |

---

## 4. Audio Quality Improvement Vectors

1. **Pre-Speech Lookahead Ring Buffer**:
   * Eliminates word onset clipping by holding 60–80ms of audio during silence and flushing in FIFO order when VAD activates.
2. **Opus Encoder Configuration Tuning**:
   * `OPUS_SET_COMPLEXITY(10)`: Maximize psychoacoustic modeling quality.
   * `OPUS_SET_INBAND_FEC(1)` + `OPUS_SET_PACKET_LOSS_PERC(p)`: Forward error correction to reconstruct lost packets over cellular/Wi-Fi.
   * `OPUS_SET_SIGNAL(OPUS_SIGNAL_VOICE)` + `OPUS_SET_BANDWIDTH(OPUS_BANDWIDTH_FULLBAND)`: Full 20 kHz voice spectrum.
3. **Soft-Knee Limiter / Saturation for Amplitude Boost**:
   * Replace hard sample clamping (`Short.MAX_VALUE` / `Short.MIN_VALUE`) in `AudioHandler` with smooth polynomial or $\tanh$ soft-limiting to eliminate harsh square-wave digital distortion.
4. **DSP Modernization**:
   * Upgrade `SpeexResampler` quality setting from `3` to `8+`, or replace Speex Denoise with **RNNoise** / **WebRTC APM**.

---

## 5. CBR vs. VBR & Cryptographic Traffic Analysis Side-Channels

### The VBR Side-Channel Vulnerability
In encrypted VoIP (TLS, DTLS, OCB-AES-128):
* Encryption conceals packet payload contents, but **preserves packet lengths and transmission timing**.
* In VBR / Constrained VBR, phonemes compress to drastically different byte sizes (e.g. fricatives vs. vowels vs. silence).
* Research (*Wright et al.*, *White et al.*) demonstrated that statistical classifiers can **reconstruct spoken phrases, identify spoken languages (>80% accuracy), and perform speaker identification** purely by inspecting encrypted packet length sequences.

### Why Hard CBR is the Correct Trade-off for Mumble
* **Tor Support**: Mumla supports routing traffic over **Tor** (`PREF_USE_TOR`). Under a strong anonymity threat model, VBR packet-length leakage enables end-to-end traffic correlation and phrase fingerprinting.
* **Zero-Entropy Lengths**: Hard Constant Bitrate (`OPUS_SET_VBR(0)`) pads every frame to the exact same byte length, completely eliminating the packet-length side-channel.
* **Latency Neutrality**: Constrained VBR (CVBR) uses a backward-looking leaky bucket model with 0 ms lookahead delay, but still exposes variable packet sizes on the wire. Hence, Hard CBR is the superior security choice.

---

## 6. Design Concept: Pre-Speech Lookahead Ring Buffer

### Motivation
In Voice Activity mode, energy-based speech detection causes clipping of initial phonemes and opening consonants before the threshold is crossed.

### Reference Architecture
* **Circular Ring Buffer**: Pre-allocated 2D array (`short[capacity][maxFrameSize]`, e.g., 8 frames $\times$ 10ms = 80ms) guaranteeing zero heap allocations on the audio thread.
* **Silence Phase**: Frames are stored in FIFO order, overwriting oldest frames when capacity is reached.
* **Onset Transition (`!mTalking && talking`)**: Flush buffered frames in chronological order through the encoder pipeline prior to encoding the triggering speech frame.
* **Active Speech**: Encode frames directly and clear buffer.
