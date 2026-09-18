# Protocol Parity & Service State Coordination

This document examines how Push-to-Talk interacts with the Mumble wire protocol, server-side policies, audio routing, and cross-layer state management in Mumla OLED.

## Table of Contents

1. [Upstream Protocol Parity Comparison](#upstream-protocol-parity-comparison)
2. [Defect Deep-Dive: Ignored Server PTT Policies (PTT-07)](#defect-deep-dive-ignored-server-ptt-policies-ptt-07)
3. [Defect Deep-Dive: Silent Failure on Suppression](#defect-deep-dive-silent-failure-on-suppression)
4. [Defect Deep-Dive: Broken Half-Duplex Runtime Preference (PTT-03)](#defect-deep-dive-broken-half-duplex-runtime-preference-ptt-03)
5. [Defect Deep-Dive: Dangerous Global OS Stream Muting (PTT-04)](#defect-deep-dive-dangerous-global-os-stream-muting-ptt-04)
6. [Decoupled Talk State & UI Round-Trip Latency](#decoupled-talk-state--ui-round-trip-latency)

---

## Upstream Protocol Parity Comparison

| Feature / Behavior | Upstream Mumble C++ Client (`../mumble`) | Mumla OLED Implementation | Parity Status |
|---|---|---|---|
| **Server PTT Suggestion** | Parses [`Mumble::Protocol::SuggestConfig`](file:///home/bualy/files/devel/mumla_dev/mumble/src/murmur/Messages.cpp#L611-L612), prompts user if server requires/suggests PTT | Netty parses [`MumbleProto.SuggestConfig`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/Mumble.proto#L614-L626), but callbacks in [`HumlaTCPMessageListener`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/java/se/lublin/humla/protocol/HumlaTCPMessageListener.java#L84) are empty stubs | **Broken / Stubbed** |
| **Talking While Muted Cue** | Detects `bTalkingWhenMuted`, suppresses audio output, plays `qsTxMuteCue` audio beep, emits `doMuteCue()` | Gated in `AudioInputEngine::processFrame`, but completely silent; no audio cue, no toast, no UI warning | **Missing Feedback** |
| **Whisper / Target State** | Sets `ClientUser::setTalking(Settings::Shouting)` when transmitting to whisper/shout targets | Hardcodes `currentUser.setTalkState(TALKING)`, discarding whisper/shout context | **Degraded** |
| **Stream Terminator** | Dispatches `isLastFrame = true` on speech offset under all conditions | Only flushes terminator when `m_accumulatedFrames > 0`; drops terminator on exact packet boundaries | **Critical Bug (PTT-01)** |
| **Half-Duplex Audio** | Attenuates / ducks playback inside audio output mixer | Invokes deprecated Android OS `AudioManager.setStreamMute()` on global system audio stream | **Hazardous (PTT-04)** |

---

## Defect Deep-Dive: Ignored Server PTT Policies (PTT-07)

The Mumble protocol specification defines `SuggestConfig` ([`Mumble.proto:614-626`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/Mumble.proto#L614-L626)):

```protobuf
message SuggestConfig {
    optional uint32 version_v1 = 1;
    optional uint64 version_v2 = 4;
    optional bool positional = 2;
    optional bool push_to_talk = 3;
}
```

When a server administrator configures `suggestpushtotalk = true` in `mumble-server.ini` (e.g. for competitive gaming or tactical comms where hot mics cause interference), the server broadcasts this message upon client synchronization.

### The Implementation Gap in Humla

In [`HumlaConnection.java:825-826, 920`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/java/se/lublin/humla/net/HumlaConnection.java#L825-L826), the message is parsed from the TCP stream and dispatched to the handler:

```java
case SuggestConfig:
    return Mumble.SuggestConfig.parseFrom(data);
...
case SuggestConfig:
    handler.messageSuggestConfig((Mumble.SuggestConfig) msg);
```

However, [`HumlaTCPMessageListener.java:84`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/java/se/lublin/humla/protocol/HumlaTCPMessageListener.java#L84) and [`HumlaNetworkListener.java:156`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/java/se/lublin/humla/util/HumlaNetworkListener.java#L156) define only empty default implementations:

```java
public void messageSuggestConfig(Mumble.SuggestConfig msg) {}
```

No class in `:app` or `:libraries:humla` implements this method. The server's suggested PTT policy is completely discarded. The user continues using continuous or VAD transmission despite the administrator's explicit policy.

---

## Defect Deep-Dive: Silent Failure on Suppression

When a user is muted or suppressed by an administrator, or enters a channel where their certificate lacks Speak permissions (`PermissionDenied`), the server transmits a `UserState` message containing `suppress = true`.

In [`AudioHandler.java:178, 351`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/java/se/lublin/humla/protocol/AudioHandler.java#L178):

```java
setServerMuted(msg.getMute() || msg.getSelfMute() || msg.getSuppress());
```

This sets `m_muted = true` in [`AudioInputEngine.cpp:99-101`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/jni/audio_engine/AudioInputEngine.cpp#L99-L101):

```cpp
if (m_muted) {
    shouldTransmit = false;
}
```

### The User Experience Failure

1. The user presses the PTT button or hot corner.
2. `AudioInputEngine::processFrame()` evaluates `shouldTransmit = false`.
3. Because `shouldTransmit == m_talking` (both are `false`), **no state transition occurs**.
4. No talking state callback is dispatched to Java.
5. The UI does not change color, no warning appears, and no sound plays.
6. The user is left completely in the dark, wondering why other participants cannot hear them.

In contrast, upstream Mumble detects `bTalkingWhenMuted = true`, blocks transmission, plays an alert chime (`qsTxMuteCue`), and displays an on-screen warning: *"You are talking while muted or suppressed"*.

---

## Defect Deep-Dive: Broken Half-Duplex Runtime Preference (PTT-03)

Mumla provides a preference `half_duplex` intended to mute incoming server audio while the user is transmitting via PTT (preventing speaker-to-mic feedback loops when not wearing headphones).

### The Preference Propagation Bug

In [`MumlaService.java:672-673`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/main/java/se/lublin/mumla/service/MumlaService.java#L672-L673), when the user changes settings while connected:

```java
case Settings.PREF_HALF_DUPLEX:
    changedExtras.putBoolean(EXTRAS_HALF_DUPLEX, mSettings.isHalfDuplex());
    break;
```

`MumlaService` forwards `changedExtras` to [`HumlaService.configureExtras()`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/java/se/lublin/humla/HumlaService.java#L698-L702):

```java
if (extras.containsKey(EXTRAS_HALF_DUPLEX)) {
    mAudioBuilder.setHalfDuplexEnabled(
            extras.getInt(EXTRAS_TRANSMIT_MODE) == Constants.TRANSMIT_PUSH_TO_TALK
                    && extras.getBoolean(EXTRAS_HALF_DUPLEX));
}
```

### Why it Fails

1. When `Settings.PREF_HALF_DUPLEX` is modified, `changedExtras` contains **only** `EXTRAS_HALF_DUPLEX`. It does **not** contain `EXTRAS_TRANSMIT_MODE`.
2. In `extras.getInt(EXTRAS_TRANSMIT_MODE)`, `Bundle.getInt()` returns the default integer `0` (`Constants.TRANSMIT_VOICE_ACTIVITY`).
3. The comparison `0 == Constants.TRANSMIT_PUSH_TO_TALK` evaluates to **`false`**.
4. As a result, `setHalfDuplexEnabled` is **always invoked with `false`** when modified at runtime.
5. Even if the user is in PTT mode, toggling the Half Duplex preference in settings disables it!
6. Furthermore, changing `EXTRAS_TRANSMIT_MODE` from VAD to PTT does not re-evaluate `EXTRAS_HALF_DUPLEX` because line 698 checks `extras.containsKey(EXTRAS_HALF_DUPLEX)`.

`HumlaService` maintains a member field `mTransmitMode` (line 640), but line 700 erroneously queries `extras.getInt(EXTRAS_TRANSMIT_MODE)` instead of reading `mTransmitMode`.

---

## Defect Deep-Dive: Dangerous Global OS Stream Muting (PTT-04)

When half-duplex is enabled, [`AudioHandler.java:450-452`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/java/se/lublin/humla/protocol/AudioHandler.java#L450-L452) executes:

```java
if (mHalfDuplex) {
    mAudioManager.setStreamMute(getAudioStream(), isTalking);
}
```

### Architectural Dangers

1. **Deprecated Android API**:
   `AudioManager.setStreamMute()` was deprecated in API 23 (Android 6.0). In modern Android versions (API 30+), mutating system stream volumes without holding `MODIFY_AUDIO_ROUTING` can be blocked or cause system log spam.
2. **Device-Wide Blast Radius**:
   `getAudioStream()` returns `AudioManager.STREAM_VOICE_CALL` or `STREAM_MUSIC`. Invoking `setStreamMute` mutes the **entire operating system stream**. If the user has a background navigation prompt, YouTube video, or secondary communication app running, those apps are silenced as well.
3. **Mute Leakage on Crash**:
   If Mumla terminates unexpectedly, crashes, or is killed by the Android LMK (Low Memory Killer) while `isTalking == true`, the device's voice call or media stream remains **permanently muted** at the OS level until the user manually adjusts their volume keys or reboots the device.
4. **Clean Alternative**:
   Mumla possesses a full in-tree native playback engine ([`AudioOutputEngine.cpp`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/jni/audio_engine/AudioOutputEngine.cpp)). Half-duplex should simply set an internal attenuation factor ($0.0$) in `AudioOutputEngine::renderMix` or pause the local `AudioTrack`, completely isolating the behavior from the Android OS volume subsystem.

---

## Decoupled Talk State & UI Round-Trip Latency

### The Decoupled `isTalking()` Method

In [`HumlaService.java:962-964`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/java/se/lublin/humla/HumlaService.java#L962-L964):

```java
@Override
public boolean isTalking() {
    return mToggleInputMode.isTalkingOn();
}
```

`mToggleInputMode.isTalkingOn()` only records the boolean flag set by [`setTalkingState(boolean)`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/java/se/lublin/humla/HumlaService.java#L967-L972).
If the client is muted (`m_muted == true`), `AudioInputEngine` transmits zero packets and remains silent. Yet `HumlaService.isTalking()` returns `true`.

### Round-Trip Latency of UI Feedback

When the user presses the PTT button:

```text
User Touches Button (t = 0ms)
   │
   ▼  Direct method call
MumlaService.onTalkKeyDown()
   │
   ▼  Direct method call
AudioInputEngine::setPttTalking(true) (t ~ 0.2ms)
   │
   ▼  Waits for next 10ms AudioRecord frame
AudioInputEngine::processFrame() (t = 0ms to 10ms)
   │
   ▼  Native talkingCb callback
NativeAudioInputEngineJni.cpp (t ~ 10.5ms)
   │
   ▼  AudioHandler.onTalkingStateChanged()
HumlaService.mAudioInputListener (t ~ 11ms)
   │
   ▼  mHandler.post() to Android Main Looper queue
Main Looper Schedule & Dispatch (t = 15ms to 60ms+)
   │
   ▼
onUserTalkStateUpdated() ──► mTalkButton.setPressed(true) + playSoundEffect()
```

Because the visual state update and sound effect are tied to the end of the round-trip through the audio record loop and main looper, the user perceives a 20–60ms delay between physically touching the button and receiving visual/audio confirmation.
