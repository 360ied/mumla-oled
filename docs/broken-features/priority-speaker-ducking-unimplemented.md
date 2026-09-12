# Broken Feature: Priority Speaker Audio Ducking Not Implemented

**Status:** confirmed unimplemented feature  
**Severity:** low-medium (feature parity with upstream Mumble)  
**Component:** `libraries/humla` Audio Engine / Mixer  
**Files Affected:**
- [`AudioOutput.java`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/java/se/lublin/humla/audio/AudioOutput.java)
- [`User.java`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/java/se/lublin/humla/model/User.java)

---

## 1. Problem Description

The Mumble protocol defines a **Priority Speaker** role (`Mumble.UserState.priority_speaker`). When a priority speaker transmits, other users talking simultaneously in the channel should have their audio volume automatically ducked (attenuated) so the priority speaker remains clearly intelligible.

In Mumla:
1. Priority speaker permissions and state are tracked on `User` objects.
2. The user context menu allows admins to toggle priority speaker status.
3. However, in `AudioOutput.java` (lines 164–170), the audio mixing implementation completely ignores priority speaker status:
   ```java
   /**
    * Fetches audio data from registered audio output users and mixes them into the given buffer.
    * TODO: add priority speaker support.
    * @param buffer The buffer to mix output data into.
    * @param bufferOffset The offset of the
    * @param bufferSize The size of the buffer.
    * @return true if the buffer contains audio data.
    */
   private boolean fetchAudio(short[] buffer, int bufferOffset, int bufferSize) {
   ```

All audio streams are passed to `mMixer.mix()` with equal gain. The priority speaker setting has no effect on audio output.

---

## 2. Technical Root Cause

`AudioOutput.fetchAudio()` collects all active `AudioOutputSpeech.Result` sources and feeds them into `AudioMixerShort` without checking if any of the active sources belong to a user with `user.isPrioritySpeaker() == true`.

---

## 3. Remediation Plan

1. In `AudioOutput.fetchAudio()`, inspect the active talking sources.
2. Determine if any currently active speech source is marked as a priority speaker.
3. If a priority speaker is active, apply a volume ducking multiplier (typically `0.25f` to `0.30f`, equivalent to -12 dB) to all non-priority audio streams before mixing.
