# Push-to-Talk (PTT) Implementation & Architecture

This directory provides a comprehensive, rigorous examination of the Push-to-Talk (PTT) architecture, DSP pipeline, protocol integration, hardware peripheral support, and UI/UX implementation in Mumla OLED.

## Table of Contents

1. [Architectural Overview](#architectural-overview)
2. [Data Flow & Pipeline](#data-flow--pipeline)
3. [Component Inventory](#component-inventory)
4. [Summary of Features](#summary-of-features)
5. [Summary Matrix of Deficiencies & Bugs](#summary-matrix-of-deficiencies--bugs)
6. [Detailed Findings Modules](#detailed-findings-modules)

---

## Architectural Overview

Push-to-Talk in Mumla spans four distinct layers:

```
┌─────────────────────────────────────────────────────────────────────────┐
│                          User Interface Layer                           │
│  ChannelFragment (On-screen) │ MumlaHotCorner (Overlay) │ MumlaActivity │
└─────────────────────────────────────────────────────────────────────────┘
                                   │  Calls onTalkKeyDown() / onTalkKeyUp()
                                   ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                     Service & State Management Layer                    │
│      MumlaService (App Policy)  ◄──►  HumlaService (Session State)      │
│      TalkBroadcastReceiver (IPC) │ ToggleInputMode (Legacy state)       │
└─────────────────────────────────────────────────────────────────────────┘
                                   │  setPttTalking(boolean)
                                   ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                       Native Audio Input Pipeline                       │
│    NativeAudioInputEngine (JNI) ──► AudioInputEngine (C++ Core)         │
│    PreSpeechRingBuffer │ RNNoise │ HysteresisVAD │ OpusVoiceEncoder     │
└─────────────────────────────────────────────────────────────────────────┘
                                   │  Dispatches encoded Opus packets
                                   ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                      Network & Protocol Transport                       │
│      AudioHandler (Protobuf/Legacy framing) ──► HumlaConnection (UDP)   │
└─────────────────────────────────────────────────────────────────────────┘
```

The user engages PTT via one of four entry points:
- **On-Screen Button**: A full-width `Button` in [`ChannelFragment.java`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/main/java/se/lublin/mumla/channel/ChannelFragment.java#L160-L178).
- **Floating Hot Corner**: A system overlay `FrameLayout` in [`MumlaHotCorner.java`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/main/java/se/lublin/mumla/service/MumlaHotCorner.java#L42-L95).
- **Physical Key Binding**: Window key event routing in [`MumlaActivity.java`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/main/java/se/lublin/mumla/app/MumlaActivity.java#L448-L464).
- **External IPC Broadcast**: Dynamic receiver in [`TalkBroadcastReceiver.java`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/main/java/se/lublin/mumla/service/ipc/TalkBroadcastReceiver.java#L30-L62).

---

## Data Flow & Pipeline

```text
1. User Event (Touch / Key / IPC Intent)
   → ChannelFragment / MumlaHotCorner / MumlaActivity / TalkBroadcastReceiver
   → MumlaService.onTalkKeyDown() / onTalkKeyUp()
2. State Coordination
   → HumlaService.setTalkingState(boolean)
   → AudioHandler.setPttTalking(boolean)
   → NativeAudioInputEngine.setPttTalking(boolean) [JNI]
3. Native Ingestion & DSP (AudioRecord Thread, 10ms quantum = 480 samples @ 48kHz)
   → AudioInputEngine::processFrame()
   → High-Pass Filter (<90Hz) ──► RNNoise Denoiser ──► VAD Speech Probability
   → Mode Evaluation: shouldTransmit = m_pttTalking && !m_muted
   → State Transition Handling:
       * False -> True (Onset):  Flush 80ms lookahead ring buffer into accumulator
       * True -> False (Offset): Flush accumulator with isTerminator = true
   → Adaptive Voice Leveler & Soft Limiter
   → OpusVoiceEncoder::encode() (40 kbps CBR, 20ms / 2 frames per packet)
4. Packet Framing & Dispatch
   → DispatchedPacket (AudioPacketCallback)
   → NativeAudioInputEngineJni.cpp ──► AudioHandler.onAudioPacketEncoded()
   → Protobuf Audio / Legacy Header Construction (Terminator flag, Sequence number)
   → HumlaConnection.sendUDPMessage() ──► Mumble Server
5. Asynchronous Feedback Loop
   → AudioInputEngine::TalkingStateCallback
   → AudioHandler.onTalkingStateChanged()
   → HumlaService.mAudioInputListener (posts to Android Main Looper)
   → currentUser.setTalkState(TALKING / PASSIVE)
   → MumlaService.mObserver.onUserTalkStateUpdated()
   → ChannelFragment / MumlaHotCorner UI update + AudioManager keypress sound
```

---

## Component Inventory

| Layer | Component / File | Key Responsibilities |
|---|---|---|
| **UI** | [`ChannelFragment.java`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/main/java/se/lublin/mumla/channel/ChannelFragment.java) | On-screen PTT touch handling, dynamic sizing, hiding on mute |
| **UI / Overlay** | [`MumlaHotCorner.java`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/main/java/se/lublin/mumla/service/MumlaHotCorner.java) | System overlay PTT target, gesture exclusion rects, active feedback |
| **UI / Window** | [`MumlaActivity.java`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/main/java/se/lublin/mumla/app/MumlaActivity.java) | Foreground hardware key capture (`onKeyDown`, `onKeyUp`) |
| **Preferences** | [`KeySelectPreferenceDialogFragment.java`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/main/java/se/lublin/mumla/preference/KeySelectPreferenceDialogFragment.java) | Physical keycode capture dialog for custom PTT key |
| **IPC** | [`TalkBroadcastReceiver.java`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/main/java/se/lublin/mumla/service/ipc/TalkBroadcastReceiver.java) | External broadcast intent receiver (`se.lublin.mumla.action.TALK`) |
| **App Service** | [`MumlaService.java`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/main/java/se/lublin/mumla/service/MumlaService.java) | Talk key dispatching, sticky toggle logic, audio cue effects |
| **Core Service** | [`HumlaService.java`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/java/se/lublin/humla/HumlaService.java) | Audio configuration, session talk state propagation, half-duplex builder |
| **Audio Transport** | [`AudioHandler.java`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/java/se/lublin/humla/protocol/AudioHandler.java) | Packet encapsulation (Protobuf vs legacy UDP), OS stream muting |
| **Audio Capture** | [`AudioInput.java`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/java/se/lublin/humla/audio/AudioInput.java) | Android `AudioRecord` hardware capture loop (mono 48kHz, 10ms chunks) |
| **JNI Bridge** | [`NativeAudioInputEngine.java`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/java/se/lublin/humla/audio/NativeAudioInputEngine.java), [`NativeAudioInputEngineJni.cpp`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/jni/audio_engine/NativeAudioInputEngineJni.cpp) | Native handle lifecycle, parameter forwarding, packet & talk callbacks |
| **Native DSP** | [`AudioInputEngine.h`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/jni/audio_engine/AudioInputEngine.h), [`AudioInputEngine.cpp`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/jni/audio_engine/AudioInputEngine.cpp) | DSP chain, VAD/PTT gating, lookahead buffering, Opus encoding |

---

## Summary of Features

1. **On-Screen Push-to-Talk Button**:
   - Resides at the bottom of [`fragment_channel.xml`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/main/res/layout/fragment_channel.xml#L74-L87).
   - Configurable vertical height via `pttButtonHeight` setting.
   - Option to hide via `hidePtt` when using physical buttons or hot corner.
2. **Persistent PTT Hot Corner Overlay**:
   - Corner overlay (`48dp x 48dp`) anchored to screen corners via [`MumlaHotCorner.java`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/main/java/se/lublin/mumla/service/MumlaHotCorner.java).
   - Registers Android 10+ gesture exclusion zones (`setSystemGestureExclusionRects`) to prevent back gesture interference.
   - Handles `ACTION_CANCEL` gracefully to prevent stuck-mic states during system interrupts.
3. **Physical Hardware Key Binding**:
   - Captures any hardware key (e.g., volume buttons, external keypad) via [`KeySelectPreferenceDialogFragment.java`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/main/java/se/lublin/mumla/preference/KeySelectPreferenceDialogFragment.java).
4. **Sticky / Toggle Mode**:
   - `togglePtt` preference allows single-tap activation and deactivation without holding the button.
5. **Half-Duplex Mode**:
   - `half_duplex` preference mutes incoming audio while transmitting to avoid acoustic feedback when not using headphones.
6. **External Automation (IPC Broadcast)**:
   - `TalkBroadcastReceiver` enables Tasker, Automate, and external accessories to toggle or hold transmission via `se.lublin.mumla.action.TALK`.
7. **Audio Feedback**:
   - `ptt_sound` preference plays a click sound when PTT is engaged.

---

## Summary Matrix of Deficiencies & Bugs

| ID | Category | Severity | Status | Description | Reference |
|---|---|---|---|---|---|
| **PTT-01** | **Audio Transport** | **Critical** | **Resolved** | **Terminator packet omitted on packet boundaries**: When PTT is released and `m_accumulatedFrames == 0`, no terminator packet (`isTerminator = true`) is dispatched. Remote clients suffer 100ms of PLC artifacts / robotic stutter on 50%–75% of speech stops. | [`AudioInputEngine.cpp:128`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/jni/audio_engine/AudioInputEngine.cpp#L128-L133) |
| **PTT-02** | **UI / Touch** | **Critical** | **Resolved** | **Stuck microphone on touch cancellation**: [`ChannelFragment.java`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/main/java/se/lublin/mumla/channel/ChannelFragment.java#L163-L177) does not handle `MotionEvent.ACTION_CANCEL`. Gesture navigation, notification shade pull-downs, or scroll intercepts leave the mic transmitting indefinitely. | [`ChannelFragment.java:163`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/main/java/se/lublin/mumla/channel/ChannelFragment.java#L163-L177) |
| **PTT-03** | **Service Logic** | **High** | **Resolved** | **Half-duplex runtime preference ignored**: When `half_duplex` is toggled in Settings, `HumlaService.configureExtras()` evaluates `extras.getInt(EXTRAS_TRANSMIT_MODE)`, which defaults to 0 (VAD), always disabling half duplex at runtime. | [`HumlaService.java:700`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/java/se/lublin/humla/HumlaService.java#L698-L702) |
| **PTT-04** | **Audio Policy** | **High** | **Resolved** | **Global Android system audio muting**: Half-duplex calls deprecated `mAudioManager.setStreamMute()` on the entire device voice call stream, muting third-party apps and risking permanent device muting on app crash, rather than muting in the internal mix engine. | [`AudioHandler.java:451`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/java/se/lublin/humla/protocol/AudioHandler.java#L450-L453) |
| **PTT-05** | **Audio DSP** | **Medium** | **Closed (Won't Fix)** | **Pre-speech lookahead ring buffer behavior in PTT**: Retained intentionally as input latency compensation. Discarding the 80ms buffer induces severe speech onset clipping due to Android capacitive touch latency (30–60ms) and coarticulation. Mechanical thumps are filtered by the 90Hz HPF and RNNoise. | [`AudioInputEngine.cpp:118`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/jni/audio_engine/AudioInputEngine.cpp#L116-L126) |
| **PTT-06** | **Audio DSP** | **Medium** | **Resolved** | **PTT release hold delay / hangover added**: Audio transmission continues for 150ms (15 frames) upon PTT release, preventing trailing syllable clipping while keeping implementation lightweight and deterministic. | [`AudioInputEngine.cpp:89`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/jni/audio_engine/AudioInputEngine.cpp#L89-L100) |
| **PTT-07** | **Protocol Parity** | **Medium** | **Closed (Won't Fix)** | **Server PTT policy suggestion (`SuggestConfig.push_to_talk`) ignored**: Closed as Won't Fix. Ideologically, user agents prioritize user autonomy and should never restrict or override user-configured transmission functionality (e.g. VAD or continuous) at the request of remote servers. | [`HumlaTCPMessageListener.java:84`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/java/se/lublin/humla/protocol/HumlaTCPMessageListener.java#L84) |
| **PTT-08** | **UI / Layout** | **Medium** | **Resolved** | **PTT button height raw pixel vs dp scaling bug**: Resolved by converting configured dp value to physical pixels using `TypedValue.applyDimension` and display metrics before assigning to layout params. | [`ChannelFragment.java:321`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/main/java/se/lublin/mumla/channel/ChannelFragment.java#L321-L342) |
| **PTT-09** | **UI / UX** | **Medium** | **Closed (Won't Fix)** | **PTT button visibility when muted**: Closed as Won't Fix. Collapsing the PTT button (`View.GONE`) when muted or suppressed is an intentional feature that reclaims critical on-screen real estate for the channel list and chat, especially since users frequently configure the PTT button to occupy up to half the screen. Predicted layout shift concerns are overwrought for deliberate mute toggles. | [`ChannelFragment.java:323`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/main/java/se/lublin/mumla/channel/ChannelFragment.java#L323-L328) |
| **PTT-10** | **UI / State** | **Low** | **Resolved** | **Visual state confusion (`setPressed` vs `setActivated`)**: Harmonized touch dispatch (`setPressed`) and sustained talk state notification (`setActivated`), using `ptt_button_tint.xml` selector rules to eliminate button flicker in toggle mode. | [`ChannelFragment.java:95`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/main/java/se/lublin/mumla/channel/ChannelFragment.java#L95-L107) |
| **PTT-11** | **Hardware / Background** | **Medium** | Open | **Foreground-only physical key capture**: Hardware keys are captured exclusively in [`MumlaActivity.onKeyDown()`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/main/java/se/lublin/mumla/app/MumlaActivity.java#L449-L455). Physical PTT does not function when the screen is locked, phone is in pocket, or another app is open. | [`MumlaActivity.java:449`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/main/java/se/lublin/mumla/app/MumlaActivity.java#L449-L464) |
| **PTT-12** | **Hardware / Peripherals** | **Medium** | Open | **No support for `KEYCODE_PTT` or Headset Hook**: Android 10+ standard `KeyEvent.KEYCODE_PTT` (286) for rugged enterprise devices and inline headset buttons (`KEYCODE_HEADSETHOOK`) are unhandled by `MediaSessionCompat`. | [`MumlaConnectionNotification.java:202`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/main/java/se/lublin/mumla/service/MumlaConnectionNotification.java#L202-L215) |
| **PTT-13** | **Documentation** | **Low** | Open | **Phantom "Soft-Keyboard Awareness" claim**: `README.md` claims the PTT hot corner automatically hides when typing. No IME detection exists; the hot corner remains visible and obscures on-screen keyboards. | [`README.md:89`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/README.md#L89) |
| **PTT-14** | **Audio Feedback** | **Low** | **Resolved** | **Muted PTT sound on disabled system clicks & missing release cue**: Replaced global OS keypress sound with dedicated `SoundPool` on `STREAM_MUSIC`/`STREAM_VOICE_CALL`, loading upstream Mumble radio chirps (`ptt_on.ogg`, `ptt_off.ogg`) on both activation and deactivation. | [`MumlaService.java:465`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/main/java/se/lublin/mumla/service/MumlaService.java#L465-L530) |
| **PTT-15** | **Testing** | **Medium** | **Resolved** | **Native C++ unit test coverage for PTT**: Added 12 comprehensive unit tests in `test_audio_input_engine.cpp` verifying PTT state transitions, hangover timing, terminator packet generation, and ring buffer lookahead. | [`test_audio_input_engine.cpp`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/test/cpp/test_audio_input_engine.cpp) |

---

## Detailed Findings Modules

For full technical deep-dives, code traces, and protocol analyses, see:

1. [Native Audio DSP & Transport Layer](audio-engine-and-dsp.md)
   - Pre-speech lookahead ring buffer behavior in PTT.
   - Terminator packet emission and packet boundary analysis.
   - PTT hold/release hangover time and VAD co-execution.
   - Native test harness status.
2. [Protocol Parity & Service State Coordination](protocol-and-service-parity.md)
   - Upstream Mumble parity (`SuggestConfig`, mute cues, voice targets).
   - Half-duplex runtime preference propagation bug.
   - Deprecated OS stream muting vs internal playback ducking.
   - Decoupled `isTalking()` state and feedback loop latency.
3. [UI Interaction, Hardware & Peripheral Integration](ui-and-hardware-integration.md)
   - Critical stuck-mic `ACTION_CANCEL` bug in `ChannelFragment`.
   - Layout parameter and display density scaling errors.
   - Dynamic button hiding vs disabled states.
   - Sticky toggle timing mechanics.
   - Hot corner overlay and the phantom soft-keyboard awareness claim.
   - Physical key, headset button, and Android 10+ `KEYCODE_PTT` integration.
   - Audio feedback sound generation.
4. [Remediation & Architecture Roadmap](remediation-plan.md)
   - Prioritized phased implementation plan for resolving all findings.
