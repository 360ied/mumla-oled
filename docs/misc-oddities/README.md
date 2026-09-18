# Miscellaneous Codebase Oddities & Architectural Gaps

This directory catalogs defects, architectural inconsistencies, performance bottlenecks, and code hygiene issues uncovered during codebase exploration that are **not directly related to Push-to-Talk (PTT)**.

## Table of Contents

1. [Summary Matrix](#summary-matrix)
2. [Detailed Topics](#detailed-topics)
   - [ODD-01: Disconnected User Leak in ModelHandler](#odd-01-disconnected-user-leak-in-modelhandler)
   - [ODD-02: Incoming UDP Audio Processing Dispatched to Main UI Thread](#odd-02-incoming-udp-audio-processing-dispatched-to-main-ui-thread)
   - [ODD-03: Unbounded Outgoing UDP Send Queue](#odd-03-unbounded-outgoing-udp-send-queue)
   - [ODD-04: Deprecated Status/Navigation Bar Insets in Overlay HUD](#odd-04-deprecated-statusnavigation-bar-insets-in-overlay-hud)
   - [ODD-05: Hot Corner Overlay Ignores Screen Rotation](#odd-05-hot-corner-overlay-ignores-screen-rotation)
   - [ODD-06: First Run Certificate Dialog Re-spawns on Outside Touch](#odd-06-first-run-certificate-dialog-re-spawns-on-outside-touch)
   - [ODD-07: Keycode Reset Inconsistency (-1 vs 0)](#odd-07-keycode-reset-inconsistency--1-vs-0)
   - [ODD-08: Stale Commented-Out XML Preferences](#odd-08-stale-commented-out-xml-preferences)
3. [Remediation Roadmap](remediation-plan.md)

---

## Summary Matrix

| ID | Category | Severity | Summary | Location |
|---|---|---|---|---|
| **ODD-01** | **Memory / State** | **High** | **Disconnected User Memory Leak**: [`ModelHandler.java`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/java/se/lublin/humla/protocol/ModelHandler.java#L469-L490) never removes disconnected or kicked users from `mUsers`. Departed users accumulate indefinitely in memory, and `getUser(session)` returns orphaned users with `channel = null`. | [`ModelHandler.java:469`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/java/se/lublin/humla/protocol/ModelHandler.java#L469-L490) |
| **ODD-02** | **Threading / Perf** | **High** | **UDP Voice Packets Processed on Main UI Thread**: In [`HumlaUDP.java`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/java/se/lublin/humla/net/HumlaUDP.java#L123-L128), every incoming UDP datagram allocates a `Runnable` and posts to the main Looper. Protobuf parsing, byte copying, and JNI queueing run on the UI thread, causing UI jank and audio jitter during active chatter. | [`HumlaUDP.java:123`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/java/se/lublin/humla/net/HumlaUDP.java#L123-L128) |
| **ODD-03** | **Network / Memory** | **Medium** | **Unbounded Outgoing UDP Send Queue**: [`HumlaUDP.java`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/java/se/lublin/humla/net/HumlaUDP.java#L74) instantiates `mSendQueue` as an unbounded `LinkedBlockingQueue<DatagramPacket>`. Degraded or blocked cellular connections cause memory bloat and post-reconnect packet bursts. | [`HumlaUDP.java:74`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/java/se/lublin/humla/net/HumlaUDP.java#L74) |
| **ODD-04** | **UI / Compatibility** | **Medium** | **Deprecated `getIdentifier` Inset Query**: [`MumlaOverlay.java`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/main/java/se/lublin/mumla/service/MumlaOverlay.java#L265-L281) queries `"status_bar_height"` and `"navigation_bar_height"` via `Resources.getIdentifier()`, which fails on modern Android display cutouts, camera punch-holes, and gesture bars. | [`MumlaOverlay.java:265`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/main/java/se/lublin/mumla/service/MumlaOverlay.java#L265-L285) |
| **ODD-05** | **UI / Window** | **Low** | **Hot Corner Disregards Orientation Change**: [`MumlaService.onConfigurationChanged()`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/main/java/se/lublin/mumla/service/MumlaService.java#L541-L545) updates overlay HUD coordinates on rotation, but neglects `MumlaHotCorner`, failing to refresh gesture exclusion rects or layout bounds. | [`MumlaService.java:541`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/main/java/se/lublin/mumla/service/MumlaService.java#L541-L545) |
| **ODD-06** | **UI / Lifecycle** | **Low** | **First Run Certificate Dialog Re-spawns**: [`MumlaActivity.java`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/main/java/se/lublin/mumla/app/MumlaActivity.java#L478-L494) displays an uncancelable dialog without a negative/dismiss listener; tapping outside dismisses the dialog without setting `first_run = false`, causing it to reappear on every app launch. | [`MumlaActivity.java:479`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/main/java/se/lublin/mumla/app/MumlaActivity.java#L479-L494) |
| **ODD-07** | **Preferences** | **Low** | **Inconsistent Reset Key Default Value**: [`Settings.java`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/main/java/se/lublin/mumla/Settings.java#L59) defines `DEFAULT_PUSH_KEY = -1`, but [`KeySelectPreferenceDialogFragment.java`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/main/java/se/lublin/mumla/preference/KeySelectPreferenceDialogFragment.java#L35) sets `mCurrentValue = 0` (`KEYCODE_UNKNOWN`), producing divergent preference states. | [`KeySelectPreferenceDialogFragment.java:35`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/main/java/se/lublin/mumla/preference/KeySelectPreferenceDialogFragment.java#L35) |
| **ODD-08** | **Code Hygiene** | **Low** | **Dead Commented-Out Preferences**: Obsolete XML preferences (`channellistrowheight`, `colorizechannellist`) remain commented out in [`settings_appearance.xml`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/main/res/xml/settings_appearance.xml#L74-L85). | [`settings_appearance.xml:74`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/main/res/xml/settings_appearance.xml#L74-L85) |

---

## Detailed Topics

### ODD-01: Disconnected User Leak in ModelHandler

In [`ModelHandler.java:469-490`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/java/se/lublin/humla/protocol/ModelHandler.java#L469-L490):

```java
@Override
public void messageUserRemove(Mumble.UserRemove msg) {
    final User user = mUsers.get(msg.getSession());
    final User actor = mUsers.get(msg.getActor());
    final String reason = msg.getReason();

    // TODO? hackish fix of crash that was happening...
    final String userName = user != null ? user.getName() : "unknown";
    final String actorName = actor != null ? actor.getName() : "unknown";
    ...
    if (user != null) {
        user.setChannel(null);
    }
    mObserver.onUserRemoved(user, reason);
}
```

Notice that `mUsers.remove(msg.getSession())` is **never executed**.
- `user.setChannel(null)` detaches the user from the channel tree, but `mUsers` retains the `User` object reference permanently until disconnection.
- Any subsequent call to `ModelHandler.getUser(session)` returns a non-null `User` instance whose channel is `null`, triggering `NullPointerException`s in calling code expecting active users (e.g. [`MumlaOverlay.java:51`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/main/java/se/lublin/mumla/service/MumlaOverlay.java#L51)).
- On busy servers with high user turnover, memory usage grows monotonically throughout the session.

---

### ODD-02: Incoming UDP Audio Processing Dispatched to Main UI Thread

In [`HumlaUDP.java:121-128`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/java/se/lublin/humla/net/HumlaUDP.java#L121-L128):

```java
if (mListener != null) {
    if (buffer != null) {
        mCallbackHandler.post(new Runnable() {
            @Override
            public void run() {
                mListener.onUDPDataReceived(buffer);
            }
        });
    }
    ...
```

The Javadoc at line 216 explicitly states:
`/** onUDPDataReceived is always called on the UDP receive thread. */`

However, line 123 posts every packet to `mCallbackHandler` (the main UI thread).
In [`HumlaConnection.java:702-725`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/java/se/lublin/humla/net/HumlaConnection.java#L702-L725):
```java
MumbleUDP.Audio audioMsg = MumbleUDP.Audio.parseFrom(ByteString.copyFrom(data, 1, data.length - 1));
for (HumlaUDPMessageListener handler : mUDPHandlers) {
    handler.messageProtobufAudio(audioMsg);
}
```
This entire parsing, protobuf decoding, and JNI queueing pipeline runs on the **Android Main UI Thread**. At typical traffic rates (50–150 UDP packets/sec), this creates severe thread contention, frame drops in the UI, and jitter in playback packet queueing.

---

### ODD-03: Unbounded Outgoing UDP Send Queue

In [`HumlaUDP.java:60, 74`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/java/se/lublin/humla/net/HumlaUDP.java#L60):

```java
/** Unbounded queue of outgoing packets to be sent. */
private final BlockingQueue<DatagramPacket> mSendQueue;
...
mSendQueue = new LinkedBlockingQueue<>();
```

When transmitting audio over poor cellular connections (e.g. train tunnels, elevator shafts, or network handovers), the outgoing consumer thread may stall on network socket writes. Because the queue is unbounded:
- Packets continue to be appended at 50 packets/sec.
- No backpressure or drop policy is applied to voice datagrams.
- When network connectivity resumes, hundreds of obsolete voice packets are transmitted in a burst, flooding the server.

---

### ODD-04: Deprecated Status/Navigation Bar Insets in Overlay HUD

In [`MumlaOverlay.java:263-285`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/main/java/se/lublin/mumla/service/MumlaOverlay.java#L263-L285):

```java
private int getTopMargin(DisplayMetrics dm) {
    int statusBarHeight = 0;
    int resourceId = mService.getResources().getIdentifier("status_bar_height", "dimen", "android");
    if (resourceId > 0) {
        statusBarHeight = mService.getResources().getDimensionPixelSize(resourceId);
    }
    ...
```

1. Querying system dimensions by string identifier (`getIdentifier`) has been discouraged since Android 11.
2. Static resources do not account for camera punch holes, dynamic island notches, or variable gesture navigation bars.
3. The modern Android solution is `WindowInsets.getInsets(WindowInsets.Type.systemBars())` or `WindowManager.getCurrentWindowMetrics()`.
4. Line 258 uses `Gravity.LEFT` rather than `Gravity.START`, which prevents proper right-to-left (RTL) locale layout mirroring.

---

### ODD-05: Hot Corner Overlay Ignores Screen Rotation

In [`MumlaService.java:541-545`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/main/java/se/lublin/mumla/service/MumlaService.java#L541-L545):

```java
@Override
public void onConfigurationChanged(Configuration newConfig) {
    super.onConfigurationChanged(newConfig);
    if (mChannelOverlay != null && mChannelOverlay.isShown()) {
        mChannelOverlay.updatePosition();
    }
}
```

When the device rotates (e.g., portrait to landscape while gaming):
- `mChannelOverlay` updates its coordinate clamping.
- `mHotCorner` is completely ignored.
- The hot corner's Android 10+ gesture exclusion zones (`setSystemGestureExclusionRects`) are not refreshed, leaving the overlay vulnerable to system back gesture interception after rotation.

---

### ODD-06: First Run Certificate Dialog Re-spawns on Outside Touch

In [`MumlaActivity.java:472-494`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/main/java/se/lublin/mumla/app/MumlaActivity.java#L472-L494):

```java
new MaterialAlertDialogBuilder(this)
    .setTitle(R.string.first_run_generate_certificate_title)
    .setMessage(msg)
    .setPositiveButton(R.string.generate, (dialog, which) -> {
        ...
        mSettings.setFirstRun(false);
    })
    .show();
```

The dialog provides only a positive button. It lacks a negative button, cancel listener, or `setCancelable(false)`.
If a user taps outside the dialog or presses Back:
- The dialog dismisses.
- `mSettings.setFirstRun(false)` is **not executed**.
- The next time the user launches Mumla, the dialog prompts them again.

---

### ODD-07: Keycode Reset Inconsistency (-1 vs 0)

- In [`Settings.java:59`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/main/java/se/lublin/mumla/Settings.java#L59):
  `public static final Integer DEFAULT_PUSH_KEY = -1;`
- In [`KeySelectPreferenceDialogFragment.java:35`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/main/java/se/lublin/mumla/preference/KeySelectPreferenceDialogFragment.java#L35):
  ```java
  builder.setNeutralButton(R.string.reset_key, (dialog, which) -> {
      ...
      mCurrentValue = 0;
  });
  ```
  And in line 56:
  `mCurrentValue = preferences.getInt(preference.getKey(), 0);`

An unconfigured key defaults to `-1`, while a reset key is persisted as `0` (`KeyEvent.KEYCODE_UNKNOWN`), creating an inconsistent state model.

---

### ODD-08: Stale Commented-Out XML Preferences

In [`settings_appearance.xml:74-85`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/main/res/xml/settings_appearance.xml#L74-L85):

```xml
    <!--
    <ListPreference
        android:defaultValue="35"
        android:entries="@array/rowheightText"
        android:entryValues="@array/rowheightValues"
        android:key="channellistrowheight"
        android:summary="@string/rowheightSum"
        android:title="@string/rowheight" />
    <CheckBoxPreference
        android:defaultValue="false"
        android:key="colorizechannellist"
        android:summary="@string/colorizechannelsSum"
    -->
```

These legacy Plumble settings are commented out in XML and should be removed.

---

## Remediation Roadmap

For the prioritized engineering roadmap, implementation details, thread safety requirements, and test strategy, see:
- [Miscellaneous Oddities Remediation Roadmap](remediation-plan.md)

