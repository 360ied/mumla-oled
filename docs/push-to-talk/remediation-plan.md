# Remediation & Architectural Roadmap

This document outlines a prioritized, phased engineering roadmap for resolving all identified Push-to-Talk defects, quality gaps, and protocol discrepancies in Mumla OLED.

## Table of Contents

1. [Phase 1: Critical Protocol & Audio Fixes (P0) — COMPLETED](#phase-1-critical-protocol--audio-fixes-p0--completed)
2. [Phase 2: DSP Quality & Acoustic Refinements (P1) — COMPLETED](#phase-2-dsp-quality--acoustic-refinements-p1--completed)
3. [Phase 3: UI/UX & Display Density Repairs (P2)](#phase-3-uiux--display-density-repairs-p2)
4. [Phase 4: Hardware, Peripheral & Background Support (P3)](#phase-4-hardware-peripheral--background-support-p3)

---

## Phase 1: Critical Protocol & Audio Fixes (P0) — COMPLETED

> [!NOTE]
> **Status: COMPLETED**
>
> All Phase 1 remediation items (PTT-01 through PTT-04) have been implemented, tested, and merged into `master` (branch `bugfix/ptt-phase1-remediation`, commits [`a372a9a4`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/jni/audio_engine/AudioInputEngine.cpp#L127-L141) through [`36a9c702`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/test/java/se/lublin/mumla/service/MumlaServiceTalkKeyTest.java), merge commit [`f04cb6fd`](file:///home/bualy/files/devel/mumla_dev/mumla-oled)).

### 1.1 Fix Terminator Packet Dropping (PTT-01) — RESOLVED

**Status**: Resolved on `master` in commit [`a372a9a4`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/jni/audio_engine/AudioInputEngine.cpp#L127-L141).

**Component**: [`AudioInputEngine.cpp`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/jni/audio_engine/AudioInputEngine.cpp#L127-L141), [`AudioInputEngine.h`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/jni/audio_engine/AudioInputEngine.h#L119)

**Problem**: Releasing PTT when `m_accumulatedFrames == 0` skips terminator packet emission, inducing 100ms of PLC stutter across all remote clients.

**Solution**:
In the upstream Mumble protocol specification ([`MumbleProtocol.cpp:909-912`](file:///home/bualy/files/devel/mumla_dev/mumble/src/MumbleProtocol.cpp#L909-L912)), packets with empty `opus_data` are rejected as invalid (`Audio packets without audio data are invalid`), and legacy UDP requires at least 1 byte of payload. Therefore, sending a 0-byte packet is not interoperable with upstream desktop clients.

Instead, when speech terminates on an exact packet boundary (`m_accumulatedFrames == 0`), `flushAccumulatorLocked` should pad a single packet of zeros (silence) and encode it through [`OpusVoiceEncoder`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/jni/audio_engine/OpusVoiceEncoder.h) with `isTerminator = true`, matching upstream Mumble's own encoder behavior ([`AudioInput.cpp:1110-1135`](file:///home/bualy/files/devel/mumla_dev/mumble/src/mumble/AudioInput.cpp#L1110-L1135)):

```cpp
// AudioInputEngine.cpp
} else if (m_talking && !shouldTransmit) {
    // Speech terminated: Always dispatch a terminator packet
    if (m_accumulatedFrames > 0) {
        flushAccumulatorLocked(true, packetsToDispatch);
    } else {
        // Packet boundary offset: encode 1 packet of zeroed PCM silence with isTerminator = true
        // Opus encodes this into a valid ~3-byte silence frame accepted by all upstream clients
        std::memset(m_accumulatedPcm.data(), 0,
                    static_cast<size_t>(m_framesPerPacket) * SAMPLES_PER_10MS * sizeof(int16_t));
        m_accumulatedFrames = m_framesPerPacket;
        flushAccumulatorLocked(true, packetsToDispatch);
    }
    m_ringBuffer.clear();
}
```

In [`NativeAudioInputEngineJni.cpp:106`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/jni/audio_engine/NativeAudioInputEngineJni.cpp#L106), ensure array copies check `if (size > 0 && data != nullptr)` defensively before calling `SetByteArrayRegion` to prevent JNI aborts. In [`AudioHandler.java:398-420`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/java/se/lublin/humla/protocol/AudioHandler.java#L398-L420), ensure packets with `isTerminator = true` continue to be framed correctly into Protobuf UDP and Legacy UDP messages.

---

### 1.2 Fix Stuck Microphone on Touch Cancellation (PTT-02) — RESOLVED

**Status**: Resolved on `master` in commits [`c6deeaa2`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/main/java/se/lublin/mumla/channel/ChannelFragment.java#L160-L183), [`6d81b96d`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/main/java/se/lublin/mumla/channel/ChannelFragment.java#L253-L258), [`968d0cc7`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/main/java/se/lublin/mumla/app/MumlaActivity.java#L464-L474), and [`36a9c702`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/test/java/se/lublin/mumla/service/MumlaServiceTalkKeyTest.java).

**Component**: [`ChannelFragment.java`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/main/java/se/lublin/mumla/channel/ChannelFragment.java#L160-L183)

**Problem**: Edge gestures, status bar pull-downs, and scroll intercepts emit `ACTION_CANCEL`, which is unhandled, locking the microphone open.

**Solution**:
Add `MotionEvent.ACTION_CANCEL` to the touch listener, calling a centralized `onTalkKeyCancel()` method on the service:

```java
mTalkButton.setOnTouchListener(new View.OnTouchListener() {
    @Override
    public boolean onTouch(View v, MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (getService() != null) {
                    getService().onTalkKeyDown();
                }
                break;
            case MotionEvent.ACTION_UP:
                if (getService() != null) {
                    getService().onTalkKeyUp();
                }
                break;
            case MotionEvent.ACTION_CANCEL:
                if (getService() != null) {
                    getService().onTalkKeyCancel();
                }
                break;
        }
        return true;
    }
});
```

Expose `void onTalkKeyCancel();` on [`IMumlaService`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/main/java/se/lublin/mumla/service/IMumlaService.java#L10-L31) and implement it in [`MumlaService.java`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/main/java/se/lublin/mumla/service/MumlaService.java) (reusing the existing [`onHotCornerCancel()`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/main/java/se/lublin/mumla/service/MumlaService.java#L213-L220) logic). Additionally, call `mService.onTalkKeyCancel()` in [`MumlaActivity.onPause()`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/main/java/se/lublin/mumla/app/MumlaActivity.java#L391-L406) so physical hardware PTT keys held down when the activity is backgrounded do not lock transmission open.

---

### 1.3 Fix Half-Duplex Preference Bug (PTT-03) — RESOLVED

**Status**: Resolved on `master` in commit [`c0daf3b9`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/java/se/lublin/humla/HumlaService.java#L699-L705).

**Component**: [`HumlaService.java`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/java/se/lublin/humla/HumlaService.java#L699-L705)

**Problem**: Evaluates `extras.getInt(EXTRAS_TRANSMIT_MODE)` which is missing when only `half_duplex` changes, always disabling half-duplex.

**Solution**:
Maintain a `private boolean mHalfDuplex;` field in [`HumlaService.java`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/java/se/lublin/humla/HumlaService.java) (parallel to `mTransmitMode` at line 135), and re-evaluate half-duplex whenever either setting changes in `configureExtras()`:

```java
// In configureExtras()
if (extras.containsKey(EXTRAS_TRANSMIT_MODE)) {
    mTransmitMode = extras.getInt(EXTRAS_TRANSMIT_MODE);
    ...
}
if (extras.containsKey(EXTRAS_HALF_DUPLEX)) {
    mHalfDuplex = extras.getBoolean(EXTRAS_HALF_DUPLEX);
}
if (extras.containsKey(EXTRAS_HALF_DUPLEX) || extras.containsKey(EXTRAS_TRANSMIT_MODE)) {
    mAudioBuilder.setHalfDuplexEnabled(
            mTransmitMode == Constants.TRANSMIT_PUSH_TO_TALK && mHalfDuplex);
}
```

---

### 1.4 Replace Dangerous OS Stream Muting (PTT-04) — RESOLVED

**Status**: Resolved on `master` in commits [`947643b9`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/java/se/lublin/humla/audio/AudioOutput.java#L208-L214) and [`a1e0a80d`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/java/se/lublin/humla/audio/AudioOutput.java#L78).

**Component**: [`AudioHandler.java`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/java/se/lublin/humla/protocol/AudioHandler.java#L449-L452)

**Problem**: Calls deprecated `AudioManager.setStreamMute()` on the global OS audio stream, impacting external apps and risking permanent device muting on crash.

**Solution**:
Isolate half-duplex muting to Mumla's internal playback path:
```java
// AudioHandler.java
if (mHalfDuplex && mOutput != null) {
    mOutput.setHalfDuplexMuted(isTalking);
}
```
Inside [`AudioOutput.java`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/java/se/lublin/humla/audio/AudioOutput.java), pass the flag to [`AudioOutputEngine::renderMix`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/jni/audio_engine/AudioOutputEngine.cpp) to zero the mixed PCM buffer before writing to `AudioTrack`.

---

## Phase 2: DSP Quality & Acoustic Refinements (P1) — COMPLETED

> [!NOTE]
> **Status: COMPLETED**
>
> All Phase 2 remediation items (PTT-05, PTT-06, and PTT-15) have been implemented, tested, and resolved (PTT-05 closed as won't fix/working as intended; PTT-06 resolved with 150ms release hangover; PTT-15 resolved with comprehensive native unit tests in [`test_audio_input_engine.cpp`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/test/cpp/test_audio_input_engine.cpp)).

### 2.1 Retain Pre-Speech Lookahead Ring Buffer in PTT as Latency Compensation (PTT-05) — CLOSED (WON'T FIX)

**Status**: Closed as Won't Fix (Working as Intended).

**Component**: [`AudioInputEngine.cpp`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/jni/audio_engine/AudioInputEngine.cpp#L116-L126)

**Evaluation & Decision**:
PTT-05 originally proposed clearing `m_ringBuffer` upon speech onset in PTT mode to avoid transmitting pre-trigger mechanical switch clicks or touchscreen tap transients.

Upon comprehensive review, this proposed change was rejected:
1. **Touch Latency Compensation**: Capacitive touchscreen input event dispatch on Android introduces 30–60ms of latency from physical touch to JNI dispatch. Flushing the 80ms buffer ensures that speech produced during this touch window is preserved rather than dropped.
2. **Prevention of Word-Onset Clipping**: Discarding the ring buffer clips initial unvoiced consonants (/p/, /t/, /k/, /s/) due to human coarticulation and speech anticipation.
3. **Existing Acoustic Filtering**: The 90Hz infrasonic high-pass filter and pre-buffering RNNoise neural denoising effectively suppress screen tap thumps and silence transients.
4. **No Privacy Impact**: 80ms is shorter than a single syllable and cannot leak intelligible private speech.

**Outcome**: No code modifications made to [`AudioInputEngine.cpp`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/jni/audio_engine/AudioInputEngine.cpp#L116-L126). The 80ms pre-speech lookahead flush is retained across all input modes.

---

### 2.2 Add PTT Release Hangover (PTT-06) — RESOLVED

**Status**: Resolved in branch `bugfix/ptt-phase2-remediation`.

**Component**: [`AudioInputEngine.h`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/jni/audio_engine/AudioInputEngine.h), [`AudioInputEngine.cpp`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/jni/audio_engine/AudioInputEngine.cpp#L89-L100)

**Problem**: Releasing PTT cuts off audio with 0ms hangover, clipping trailing syllables.

**Solution**:
Introduce a fixed release hangover counter of 15 frames (150ms), avoiding unnecessary configuration complexity:

```cpp
case InputMode::PUSH_TO_TALK:
    if (m_pttTalking) {
        m_pttHoldFramesRemaining = PTT_HOLD_FRAMES; // 15 frames (150ms)
        shouldTransmit = true;
    } else if (m_pttHoldFramesRemaining > 0) {
        m_pttHoldFramesRemaining--;
        shouldTransmit = true;
    } else {
        shouldTransmit = false;
    }
    m_vad.process(m_processedFrame.data(), SAMPLES_PER_10MS, speechProb);
    break;
```

---

### 2.3 Implement Native C++ PTT Unit Tests (PTT-15) — RESOLVED

**Status**: Resolved in branch `bugfix/ptt-phase2-remediation`.

**Component**: [`libraries/humla/src/test/cpp/test_audio_input_engine.cpp`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/test/cpp/test_audio_input_engine.cpp), [`scripts/test_native_audio.sh`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/scripts/test_native_audio.sh)

**Solution**:
Constructed a dedicated test suite verifying:
- PTT state transitions (`setPttTalking(true)` / `setPttTalking(false)`).
- Guaranteed emission of `isTerminator = true` across both odd and even packet boundary releases ($N=1, 2, 4, 6$).
- Verification that pre-speech ring buffer is flushed upon PTT speech onset.
- Verification that mute gates audio immediately.
- Verification that PTT release hangover sustains transmission for exactly 150ms (15 frames).
- Verification that re-asserting PTT during hangover avoids audio glitches or false terminator packets.
- Rapid micro-tap handling.
- Continuous and VAD input modes.
- Engine reset and state recovery.

---

## Phase 3: UI/UX & Display Density Repairs (P2)

### 3.1 Fix PTT Button Height Density Conversion (PTT-08)

**Component**: [`ChannelFragment.java`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/main/java/se/lublin/mumla/channel/ChannelFragment.java#L306-L312)

**Solution**:
Convert `settings.getPTTButtonHeight()` from dp to physical pixels using display metrics:

```java
private void configureInput() {
    Settings settings = Settings.getInstance(getActivity());
    int heightDp = settings.getPTTButtonHeight();
    int heightPx = (int) TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            heightDp,
            getResources().getDisplayMetrics());

    ViewGroup.LayoutParams params = mTalkButton.getLayoutParams();
    params.height = heightPx;
    mTalkButton.setLayoutParams(params);
    ...
}
```

---

### 3.2 Retain PTT Button Collapse on Mute (PTT-09) — CLOSED (WON'T FIX)

**Status**: Closed as Won't Fix (Working as Intended).

**Component**: [`ChannelFragment.java`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/main/java/se/lublin/mumla/channel/ChannelFragment.java#L323-L333)

**Evaluation & Decision**:
PTT-09 originally proposed keeping the PTT button visible in a disabled state (`setEnabled(false)`) when muted or suppressed, arguing that setting `View.GONE` caused jarring layout shifts.

Upon architectural and ergonomic review, this proposal was rejected:
1. **Screen Real Estate Reclamation**: Mumla allows users to configure the PTT button to be very large (occupying up to half the screen height) for comfortable blind targeting while gaming, walking, or driving. When muted, transmission is completely blocked; preserving an inactive button across half the screen wastes critical viewport space and blocks the channel hierarchy and chat.
2. **Intentional User Flow**: Muting is an explicit user action. Reclaiming the viewport space is an intentional feature, not an unexpected layout defect.
3. **No String Bloat**: Avoids introducing unnecessary disabled state strings (`R.string.ptt_muted`) or complex alpha animation states.

**Outcome**: No code modifications required. The existing `setTalkButtonHidden(true)` collapse behavior is retained as designed.

---

### 3.3 Harmonize Visual States (`setActivated`) (PTT-10)

**Component**: [`ChannelFragment.java`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/main/java/se/lublin/mumla/channel/ChannelFragment.java#L98-L107)

**Solution**:
Use `mTalkButton.setActivated(true)` to represent sustained talking state (especially in toggle mode), preventing conflicts with touch dispatch `setPressed()`.

---

### 3.4 Replace `playSoundEffect` with Low-Latency `SoundPool` (PTT-14)

**Component**: [`MumlaService.java`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/main/java/se/lublin/mumla/service/MumlaService.java#L458-L464)

**Solution**:
Pre-load two short audio clips (PTT On chirp, PTT Off chirp) into an Android `SoundPool` on `STREAM_MUSIC` / `STREAM_VOICE_CALL`:
- Plays reliably regardless of whether system touch sounds are disabled.
- Plays on both activation and deactivation.
- Provides tactile acoustic feedback matching radio standards.

---

## Phase 4: Hardware, Peripheral & Background Support (P3)

### 4.1 Native Support for Enterprise `KEYCODE_PTT` (PTT-12)

**Component**: [`MumlaActivity.java`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/main/java/se/lublin/mumla/app/MumlaActivity.java#L449-L464), [`Settings.java`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/main/java/se/lublin/mumla/Settings.java)

**Solution**:
Recognize `KeyEvent.KEYCODE_PTT` (286) automatically without requiring manual keycode binding in settings.

---

### 4.2 Headset Button & Media Session PTT (PTT-12)

**Component**: [`MumlaConnectionNotification.java`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/main/java/se/lublin/mumla/service/MumlaConnectionNotification.java#L202-L215)

**Solution**:
Implement `MediaSessionCompat.Callback.onMediaButtonEvent()`:
- Intercept `KEYCODE_HEADSETHOOK` and `KEYCODE_MEDIA_PLAY_PAUSE`.
- If PTT mode is active, toggle or hold transmission when pressed from wired or Bluetooth headphones while the device screen is off.

---

### 4.3 Reject Remote Server Transmission Constraints (PTT-07) — CLOSED (WON'T FIX)

**Status**: Closed as Won't Fix (User Autonomy Policy).

**Component**: [`HumlaTCPMessageListener.java`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/java/se/lublin/humla/protocol/HumlaTCPMessageListener.java#L84), [`HumlaNetworkListener.java`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/java/se/lublin/humla/util/HumlaNetworkListener.java#L156)

**Evaluation & Decision**:
PTT-07 originally proposed parsing `SuggestConfig.push_to_talk` to prompt or nudge the user toward PTT mode when requested by a server administrator.

Upon architectural review, this was rejected:
1. **User Agent Primacy**: A user agent represents the user, not the remote server. Remote servers must not be permitted to restrict, override, or harass the user regarding their chosen audio transmission mode.
2. **Accessibility and Hands-Free Use**: Mobile users often rely on Voice Activity Detection (VAD) or continuous transmission for hands-free scenarios (e.g., cycling, driving, accessibility). Server suggestions imposing PTT degrade usability and safety.
3. **Intentional No-Op**: Retaining the existing empty stub in [`HumlaTCPMessageListener.java`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/java/se/lublin/humla/protocol/HumlaTCPMessageListener.java#L84) safely satisfies the wire protocol while preserving full user autonomy.

**Outcome**: No code modifications required. The no-op handler is retained as an intentional architectural boundary.

---

### 4.4 Resolve README Hot Corner Soft-Keyboard Discrepancy (PTT-13)

**Component**: [`README.md:89`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/README.md#L89) or [`MumlaHotCorner.java`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/main/java/se/lublin/mumla/service/MumlaHotCorner.java)

**Solution**:
Either implement an insets / layout bounds listener on `MumlaHotCorner` that dims or hides the corner when display insets indicate IME keyboard expansion, or correct `README.md` to remove the unverified claim.
