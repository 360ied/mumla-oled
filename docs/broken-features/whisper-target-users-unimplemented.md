# Broken Feature: Whisper Target to Individual Users Throws UnsupportedOperationException

**Status:** confirmed unimplemented stub  
**Severity:** medium  
**Component:** `libraries/humla` Protocol / Model  
**Files Affected:**
- [`WhisperTargetUsers.java`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/java/se/lublin/humla/model/WhisperTargetUsers.java)
- [`AudioOutput.java`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/java/se/lublin/humla/audio/AudioOutput.java)
- [`ChannelListAdapter.java`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/main/java/se/lublin/mumla/channel/ChannelListAdapter.java)

---

## 1. Problem Description

Mumble supports whispering (private voice transmission) either to specific channels or to specific individual users.

While channel whispering (`WhisperTargetChannel`) is functional in Mumla, whispering to specific users (`WhisperTargetUsers`) is an empty stub where all methods unconditionally throw exceptions:

```java
public class WhisperTargetUsers implements WhisperTarget {
    @Override
    public Mumble.VoiceTarget.Target createTarget() {
        throw new UnsupportedOperationException(); // TODO
    }

    @Override
    public String getName() {
        throw new UnsupportedOperationException(); // TODO
    }
}
```

Any attempt to register or activate a user whisper target results in an immediate crash with `UnsupportedOperationException`.

---

## 2. Incomplete Whisper Audio & UI Pipeline

Beyond `WhisperTargetUsers`, the whisper pipeline contains multiple incomplete touchpoints:

1. **Incoming Whisper Audio Ignored:**
   In `AudioOutput.java` (line 217):
   ```java
   User user = mListener.getUser(session);
   if(user != null && !user.isLocalMuted()) {
       // TODO check for whispers here
       int seq = (int) pds.readLong();
   ```
   The target field in the UDP voice header (`data[0] & 0x1F`) is masked out in `queueVoiceData()`, and incoming whispers are routed to the standard speaker mix without notifying the UI or differentiating from regular channel voice packets.
2. **Missing Whisper / Shout Avatars:**
   In `ChannelListAdapter.java` (line 364):
   ```java
   } else if (user.getTalkState() == TalkState.TALKING
           || user.getTalkState() == TalkState.SHOUTING
           || user.getTalkState() == TalkState.WHISPERING) {
       // TODO whisper and shouting?
       return resources.getDrawable(R.drawable.outline_circle_talking_on);
   ```
   Users whispering or shouting are drawn with standard green talking circles rather than distinct indicators (e.g. blue or cyan in standard Mumble clients).

---

## 3. Remediation Plan

1. **Implement `WhisperTargetUsers`:**
   Add a user session list constructor and implement `createTarget()` to build a `Mumble.VoiceTarget.Target` with `addAllSessions(...)`. Implement `getName()` to return formatted names of targeted users.
2. **Handle Incoming Whispers in `AudioOutput`:**
   Inspect voice target flags (`msgFlags != 0`) and trigger dedicated notifications/audio indicators so the receiving user knows they are receiving a private whisper.
3. **Add Distinct Visual Indicators:**
   Provide distinct avatar halos or badges for `TalkState.WHISPERING` and `TalkState.SHOUTING` in `ChannelListAdapter`.
