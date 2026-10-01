# Broken Feature: Whisper Target to Individual Users Throws UnsupportedOperationException

**Status:** confirmed unimplemented stub  
**Severity:** medium  
**Component:** `libraries/humla` Protocol / Model  
**Files Affected:**
- [`WhisperTargetUsers.java`](../../libraries/humla/src/main/java/se/lublin/humla/model/WhisperTargetUsers.java)
- [`AudioOutput.java`](../../libraries/humla/src/main/java/se/lublin/humla/audio/AudioOutput.java) (flags forwarding) / [`AudioOutputEngine.cpp`](../../libraries/humla/src/main/jni/audio_engine/AudioOutputEngine.cpp) (`talkStateForFlags`, native mix)
- [`ChannelListAdapter.java`](../../app/src/main/java/se/lublin/mumla/channel/ChannelListAdapter.java)
- [`ChannelMenu.java`](../../app/src/main/java/se/lublin/mumla/channel/ChannelMenu.java) (only whisper-creation UX: channel shout) / [`ChannelFragment.java`](../../app/src/main/java/se/lublin/mumla/channel/ChannelFragment.java) (generic whisper `target_panel` cancel)

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

1. **Incoming Whisper Detection Now Works; UI Differentiation Still Missing:**
   The old Java `AudioOutput` masking described here is gone. `AudioOutput.queueVoiceData()` now forwards the UDP target byte (`data[0] & 0x1F`) as `msgFlags` into `engine.queuePacket()`, and native `talkStateForFlags()` (`AudioOutputEngine.cpp:163-180`) maps `0 → TALKING, 1 → SHOUTING, 2 → WHISPERING`, propagated via `onTalkStateChanged` into `TalkState`. Incoming whispers/shouts are therefore detected, but still mixed at equal gain with no dedicated notification sound and no distinct visual treatment (see item 2).
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
   Add a user/session-list constructor and implement `createTarget()` to build a `Mumble.VoiceTarget.Target` with `addAllSessions(...)` (`Mumble.proto:464-477`: `repeated uint32 session = 1`). Implement `getName()` to return formatted names of targeted users. Any call through `HumlaService.registerWhisperTarget()` (`HumlaService.java:1246` calls `target.createTarget()`) crashes until this lands.
2. **Add Outgoing User-Whisper UX:**
   The only whisper-creation UI is channel shout (`ChannelMenu.java:133-160`). There is no user-list multi-select / whisper-to-user dialog, so the model class alone leaves the feature unreachable. Add a `UserMenu` "whisper to…" entry that registers the target and calls `setVoiceTargetId()`; the existing `target_panel` cancel path in `ChannelFragment.java:183-197` already generalizes to any `WHISPER` mode.
3. **Add Distinct Visual/Audio Indicators:**
   Provide distinct avatar halos or badges for `TalkState.WHISPERING` and `TalkState.SHOUTING` in `ChannelListAdapter` (only `outline_circle_talking_{on,off}.xml` exist today) and consider a dedicated incoming-whisper notification sound.

---

## 4. Re-verification (2026-10-01)

Core bug still valid: `WhisperTargetUsers.java:27-33` throws on both methods and no caller constructs it. The old "Incoming Whisper Audio Ignored" claim above is superseded by the detection update — detection works, differentiation does not.
