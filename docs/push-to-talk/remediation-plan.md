# Remediation & Architectural Roadmap

This document outlines a prioritized, phased engineering roadmap for resolving all identified Push-to-Talk defects, quality gaps, and protocol discrepancies in Mumla OLED.

## Table of Contents

1. [Phase 1: Critical Protocol & Audio Fixes (P0)](#phase-1-critical-protocol--audio-fixes-p0)
2. [Phase 2: DSP Quality & Acoustic Refinements (P1)](#phase-2-dsp-quality--acoustic-refinements-p1)
3. [Phase 3: UI/UX & Display Density Repairs (P2)](#phase-3-uiux--display-density-repairs-p2)
4. [Phase 4: Hardware, Peripheral & Background Support (P3)](#phase-4-hardware-peripheral--background-support-p3)

---

## Phase 1: Critical Protocol & Audio Fixes (P0)

### 1.1 Fix Terminator Packet Dropping (PTT-01)

**Component**: [`AudioInputEngine.cpp`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/jni/audio_engine/AudioInputEngine.cpp#L127-L133), [`AudioInputEngine.h`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/jni/audio_engine/AudioInputEngine.h#L119)

**Problem**: Releasing PTT when `m_accumulatedFrames == 0` skips terminator packet emission, inducing 100ms of PLC stutter across all remote clients.

**Solution**:
Modify `flushAccumulatorLocked` to accept an explicit `forceTerminator` or dispatch a 0-sample / comfort-noise terminator packet when `m_accumulatedFrames == 0`:

```cpp
// AudioInputEngine.cpp
} else if (m_talking && !shouldTransmit) {
    // Speech terminated: Always dispatch a terminator packet
    if (m_accumulatedFrames > 0) {
        flushAccumulatorLocked(true, packetsToDispatch);
    } else {
        // Emit zero-payload or comfort-noise terminator packet
        DispatchedPacket pkt{};
        pkt.size = 0;
        pkt.frames = 0;
        pkt.isTerminator = true;
        pkt.frameNumber = m_frameCounter;
        packetsToDispatch.push_back(pkt);
    }
    m_ringBuffer.clear();
}
```

In [`AudioHandler.java:398-420`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/java/se/lublin/humla/protocol/AudioHandler.java#L398-L420), ensure zero-length payloads with `isTerminator = true` are validly framed into Protobuf UDP and Legacy UDP messages.

---

### 1.2 Fix Stuck Microphone on Touch Cancellation (PTT-02)

**Component**: [`ChannelFragment.java`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/main/java/se/lublin/mumla/channel/ChannelFragment.java#L163-L177)

**Problem**: Edge gestures, status bar pull-downs, and scroll intercepts emit `ACTION_CANCEL`, which is unhandled, locking the microphone open.

**Solution**:
Add `MotionEvent.ACTION_CANCEL` to the touch listener:

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
                    Settings settings = Settings.getInstance(getActivity());
                    if (!settings.isPushToTalkToggle() && getService().isTalking()) {
                        getService().setTalkingState(false);
                    }
                }
                break;
        }
        return true;
    }
});
```

---

### 1.3 Fix Half-Duplex Preference Bug (PTT-03)

**Component**: [`HumlaService.java`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/java/se/lublin/humla/HumlaService.java#L698-L702)

**Problem**: Evaluates `extras.getInt(EXTRAS_TRANSMIT_MODE)` which is missing when only `half_duplex` changes, always disabling half-duplex.

**Solution**:
Read the internal `mTransmitMode` field, and re-evaluate half-duplex whenever transmit mode changes:

```java
// In configureAudio()
if (extras.containsKey(EXTRAS_TRANSMIT_MODE)) {
    mTransmitMode = extras.getInt(EXTRAS_TRANSMIT_MODE);
    ...
}
if (extras.containsKey(EXTRAS_HALF_DUPLEX) || extras.containsKey(EXTRAS_TRANSMIT_MODE)) {
    boolean halfDuplexRequested = extras.containsKey(EXTRAS_HALF_DUPLEX)
            ? extras.getBoolean(EXTRAS_HALF_DUPLEX)
            : mAudioBuilder.isHalfDuplexEnabled();
    mAudioBuilder.setHalfDuplexEnabled(
            mTransmitMode == Constants.TRANSMIT_PUSH_TO_TALK && halfDuplexRequested);
}
```

---

### 1.4 Replace Dangerous OS Stream Muting (PTT-04)

**Component**: [`AudioHandler.java`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/java/se/lublin/humla/protocol/AudioHandler.java#L450-L453)

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

## Phase 2: DSP Quality & Acoustic Refinements (P1)

### 2.1 Bypass Pre-Speech Lookahead Ring Buffer in PTT (PTT-05)

**Component**: [`AudioInputEngine.cpp`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/jni/audio_engine/AudioInputEngine.cpp#L116-L126)

**Problem**: Flushing 80ms of past audio leaks physical switch clicks and touchscreen tap transients.

**Solution**:
Only flush `m_ringBuffer` when running under `InputMode::VOICE_ACTIVITY`. When running under `InputMode::PUSH_TO_TALK`, immediately clear the ring buffer upon speech onset:

```cpp
if (!m_talking && shouldTransmit) {
    if (m_inputMode == InputMode::VOICE_ACTIVITY) {
        // Speech onset in VAD mode: Flush the 80ms lookahead ring buffer
        m_ringBuffer.flush([this, &packetsToDispatch](const int16_t* bufferedPcm, size_t len) {
            ...
        });
    } else {
        // PTT or Continuous: Discard pre-trigger audio to prevent click leakage
        m_ringBuffer.clear();
    }
}
```

---

### 2.2 Add Configurable PTT Release Hangover (PTT-06)

**Component**: [`AudioInputEngine.h`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/jni/audio_engine/AudioInputEngine.h), [`AudioInputEngine.cpp`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/jni/audio_engine/AudioInputEngine.cpp#L89-L92)

**Problem**: Releasing PTT cuts off audio with 0ms hangover, clipping trailing syllables.

**Solution**:
Introduce a release hold counter (e.g. 15 frames = 150ms default):

```cpp
case InputMode::PUSH_TO_TALK:
    if (m_pttTalking) {
        m_pttHoldFramesRemaining = m_pttHoldFrames; // e.g. 15 frames (150ms)
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

### 2.3 Implement Native C++ PTT Unit Tests (PTT-15)

**Component**: [`libraries/humla/src/test/cpp/test_audio_input_engine.cpp`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/test/cpp/)

**Solution**:
Construct a dedicated test suite verifying:
- PTT state transitions (`setPttTalking(true)` / `setPttTalking(false)`).
- Guaranteed emission of `isTerminator = true` across both odd and even packet boundary releases.
- Verification that pre-speech ring buffer is bypassed in PTT mode.
- Verification that mute gates audio immediately.

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

### 3.2 Display Disabled State Instead of Hiding PTT Button on Mute (PTT-09)

**Component**: [`ChannelFragment.java`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/main/java/se/lublin/mumla/channel/ChannelFragment.java#L323-L333)

**Solution**:
Keep `mTalkView` visible when PTT mode is enabled. Enable or disable `mTalkButton` based on mute status:

```java
boolean isPtt = settings.getInputMethod().equals(Settings.ARRAY_INPUT_METHOD_PTT);
boolean showPtt = settings.isPushToTalkButtonShown() && isPtt;
mTalkView.setVisibility(showPtt ? View.VISIBLE : View.GONE);

if (showPtt) {
    mTalkButton.setEnabled(!muted);
    mTalkButton.setAlpha(muted ? 0.4f : 1.0f);
    mTalkButton.setText(muted ? R.string.ptt_muted : R.string.ptt);
}
```

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

### 4.3 Support Server `SuggestConfig.push_to_talk` (PTT-07)

**Component**: [`ModelHandler.java`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/java/se/lublin/humla/protocol/ModelHandler.java#L554), [`MumlaService.java`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/main/java/se/lublin/mumla/service/MumlaService.java)

**Solution**:
Implement `messageSuggestConfig(Mumble.SuggestConfig msg)`:
- Store server suggestions in `ServerSettings`.
- If `msg.getPushToTalk() == true` and client is currently configured for continuous or VAD transmission, notify the user with a dismissible snackbar/toast: *"This server suggests using Push-to-Talk."*

---

### 4.4 Resolve README Hot Corner Soft-Keyboard Discrepancy (PTT-13)

**Component**: [`README.md:89`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/README.md#L89) or [`MumlaHotCorner.java`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/main/java/se/lublin/mumla/service/MumlaHotCorner.java)

**Solution**:
Either implement an insets / layout bounds listener on `MumlaHotCorner` that dims or hides the corner when display insets indicate IME keyboard expansion, or correct `README.md` to remove the unverified claim.
