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
   - [ODD-09: Bandwidth-Degraded framesPerPacket Fails to Scale UDP Send Queue](#odd-09-bandwidth-degraded-framesperpacket-fails-to-scale-udp-send-queue)
   - [ODD-10: DNS Rebinding TOCTOU in Image SSRF Check](#odd-10-dns-rebinding-toctou-in-image-ssrf-check)
   - [ODD-11: Incomplete IPv6 Transition-Mechanism Coverage in SSRF Policy](#odd-11-incomplete-ipv6-transition-mechanism-coverage-in-ssrf-policy)
   - [ODD-12: Thin SSRF Regression Test Layering](#odd-12-thin-ssrf-regression-test-layering)
   - [ODD-13: Unguarded getArguments() in Comment Dialog](#odd-13-unguarded-getarguments-in-comment-dialog)
   - [ODD-14: Deprecated onAttach Overload and Dropped Cause in Comment Dialog](#odd-14-deprecated-onattach-overload-and-dropped-cause-in-comment-dialog)
   - [ODD-15: Partial View Teardown in Comment Dialog](#odd-15-partial-view-teardown-in-comment-dialog)
   - [ODD-16: Untranslated Comment Chooser Title](#odd-16-untranslated-comment-chooser-title)
   - [ODD-17: Phase-4 Native and TLS Code Hygiene](#odd-17-phase-4-native-and-tls-code-hygiene)
   - [ODD-18: Phase-4 Certificate Import/Export Hygiene](#odd-18-phase-4-certificate-importexport-hygiene)
   - [ODD-19: Phase-4 Server Edit Dialog, Strings and TALK Receiver Polish](#odd-19-phase-4-server-edit-dialog-strings-and-talk-receiver-polish)
   - [ODD-20: Phase-4 Settings, Manifest and Build Script Hygiene](#odd-20-phase-4-settings-manifest-and-build-script-hygiene)

3. [Remediation Roadmap](remediation-plan.md)

---

## Summary Matrix

| ID | Category | Severity | Status | Summary | Location |
|---|---|---|---|---|---|
| **ODD-01** | **Memory / State** | **High** | **Resolved** | **Disconnected User Memory Leak**: [`ModelHandler.java`](../../libraries/humla/src/main/java/se/lublin/humla/protocol/ModelHandler.java#L469-L490) never removes disconnected or kicked users from `mUsers`. Departed users accumulate indefinitely in memory, and `getUser(session)` returns orphaned users with `channel = null`. | [`ModelHandler.java:469`](../../libraries/humla/src/main/java/se/lublin/humla/protocol/ModelHandler.java#L469-L490) |
| **ODD-02** | **Threading / Perf** | **High** | **Resolved** | **UDP Voice Packets Processed on Main UI Thread**: In [`HumlaUDP.java`](../../libraries/humla/src/main/java/se/lublin/humla/net/HumlaUDP.java#L123-L128), every incoming UDP datagram allocates a `Runnable` and posts to the main Looper. Protobuf parsing, byte copying, and JNI queueing run on the UI thread, causing UI jank and audio jitter during active chatter. | [`HumlaUDP.java:123`](../../libraries/humla/src/main/java/se/lublin/humla/net/HumlaUDP.java#L123-L128) |
| **ODD-03** | **Network / Memory** | **Medium** | **Resolved** | **Unbounded Outgoing UDP Send Queue**: [`HumlaUDP.java`](../../libraries/humla/src/main/java/se/lublin/humla/net/HumlaUDP.java#L74) instantiates `mSendQueue` as an unbounded `LinkedBlockingQueue<DatagramPacket>`. Degraded or blocked cellular connections cause memory bloat and post-reconnect packet bursts. | [`HumlaUDP.java:74`](../../libraries/humla/src/main/java/se/lublin/humla/net/HumlaUDP.java#L74) |
| **ODD-04** | **UI / Compatibility** | **Medium** | **Resolved** | **Modernized inset query**: the pinned overlay now prefers live `WindowInsets` (system bars plus display cutout, ignoring visibility) via [`MumlaOverlay.java`](../../app/src/main/java/se/lublin/mumla/service/MumlaOverlay.java#L266-L371), keeping the legacy `getIdentifier` lookup only as a fallback. Gravities intentionally stay physical `LEFT`/`RIGHT`. | [`MumlaOverlay.java:266`](../../app/src/main/java/se/lublin/mumla/service/MumlaOverlay.java#L266-L371) |
| **ODD-05** | **UI / Window** | **Low** | **Resolved** | **Hot corner exclusion refreshed on rotation**: [`MumlaService.onConfigurationChanged()`](../../app/src/main/java/se/lublin/mumla/service/MumlaService.java#L651-L662) now calls [`MumlaHotCorner.refreshGestureExclusionDeferred()`](../../app/src/main/java/se/lublin/mumla/service/MumlaHotCorner.java#L221-L234), reapplying gesture exclusion rects past the rotation relayout. | [`MumlaService.java:651`](../../app/src/main/java/se/lublin/mumla/service/MumlaService.java#L651-L662) |
| **ODD-06** | **UI / Lifecycle** | **Low** | **Resolved** | **First-run dialog dismissal hardened**: all dismissal paths in [`MumlaActivity.showFirstRunGuide()`](../../app/src/main/java/se/lublin/mumla/app/MumlaActivity.java#L624-L671) converge on a lifecycle-guarded dismiss listener, so `first_run` is always cleared and the startup action runs on a live instance. | [`MumlaActivity.java:624`](../../app/src/main/java/se/lublin/mumla/app/MumlaActivity.java#L624-L671) |
| **ODD-07** | **Preferences** | **Low** | **Resolved** | **PTT key sentinel unified**: the no-key sentinel is `Settings.DEFAULT_PUSH_KEY` (`-1`), enforced by [`Settings.isPttKeyBound()`](../../app/src/main/java/se/lublin/mumla/Settings.java#L262-L271) in the `MumlaActivity` key handlers and the key-select dialog, which normalizes the legacy `0` value on load. | [`Settings.java:60`](../../app/src/main/java/se/lublin/mumla/Settings.java#L60) |
| **ODD-08** | **Code Hygiene** | **Low** | **Resolved** | **Dead commented-out preferences pruned**: the obsolete `channellistrowheight`, `colorizechannellist`, `colorthresholdnumusers` block was removed from [`settings_appearance.xml`](../../app/src/main/res/xml/settings_appearance.xml). | [`settings_appearance.xml`](../../app/src/main/res/xml/settings_appearance.xml) |
| **ODD-09** | **Network / Latency** | **Medium** | **Open** | **Bandwidth-Degraded `framesPerPacket` Fails to Scale HumlaUDP Send Queue**: When low server bandwidth triggers `AudioHandler.setMaxBandwidth()` to increase `framesPerPacket` (e.g. from 2 to 4), `HumlaUDP` is not updated, keeping a 10-packet queue ($10 \times 40\text{ ms} = 400\text{ ms}$) and causing latency bloat. | [`AudioHandler.java:266`](../../libraries/humla/src/main/java/se/lublin/humla/protocol/AudioHandler.java#L266-L272) |
| **ODD-10** | **Security / SSRF** | **Medium** | **Open** | **DNS Rebinding TOCTOU in Image SSRF Check**: [`MumbleImageGetter.isHostBlocked()`](../../app/src/main/java/se/lublin/mumla/util/MumbleImageGetter.java#L598-L611) resolves via `getAllByName` but `HttpURLConnection` reconnects by hostname, so a rebind between check and `connect()` defeats the policy. | [`MumbleImageGetter.java:598`](../../app/src/main/java/se/lublin/mumla/util/MumbleImageGetter.java#L598-L611) |
| **ODD-11** | **Security / SSRF** | **Low** | **Open** | **Incomplete IPv6 Transition-Mechanism Coverage**: [`SsrfHostPolicy.isBlockedIPv6()`](../../app/src/main/java/se/lublin/mumla/util/SsrfHostPolicy.java#L239-L266) unwraps only the well-known NAT64 `/96`; RFC 6052 variable-length and operator NAT64 prefixes plus Teredo/ISATAP are unhandled. | [`SsrfHostPolicy.java:239`](../../app/src/main/java/se/lublin/mumla/util/SsrfHostPolicy.java#L239-L266) |
| **ODD-12** | **Testing** | **Low** | **Open** | **Thin SSRF Regression Test Layering**: `testSiteLocalBlocked` passes via either the `isSiteLocalAddress` gate or the explicit `fec0::/10` branch, and embedded `169.254`/`224` branches lack direct literal tests. | [`SsrfHostPolicyTest.java:113`](../../app/src/test/java/se/lublin/mumla/util/SsrfHostPolicyTest.java#L113-L124) |
| **ODD-13** | **UI / Lifecycle** | **Low** | **Open** | **Unguarded `getArguments()` in Comment Dialog**: [`AbstractCommentFragment.java`](../../app/src/main/java/se/lublin/mumla/channel/comment/AbstractCommentFragment.java#L58) dereferences `getArguments()` without a null check in `onCreate()` and `isEditing()`; a no-args instantiation crashes with NPE. | [`AbstractCommentFragment.java:58`](../../app/src/main/java/se/lublin/mumla/channel/comment/AbstractCommentFragment.java#L58) |
| **ODD-14** | **UI / Compatibility** | **Low** | **Open** | **Deprecated `onAttach(Activity)` and Dropped Cause**: [`AbstractCommentFragment.java`](../../app/src/main/java/se/lublin/mumla/channel/comment/AbstractCommentFragment.java#L62-L68) overrides the deprecated `onAttach(Activity)` overload and rethrows `ClassCastException` as `RuntimeException` without chaining the cause. | [`AbstractCommentFragment.java:62`](../../app/src/main/java/se/lublin/mumla/channel/comment/AbstractCommentFragment.java#L62-L68) |
| **ODD-15** | **Memory / Lifecycle** | **Low** | **Open** | **Partial View Teardown in Comment Dialog**: `onDestroyView()` nulls `mCommentView` but leaves `mTabHost`/`mCommentEdit` reachable, and the tab listener dereferences `mCommentView` unguarded. | [`AbstractCommentFragment.java:132`](../../app/src/main/java/se/lublin/mumla/channel/comment/AbstractCommentFragment.java#L132-L141) |
| **ODD-16** | **UI / i18n** | **Low** | **Open** | **Untranslated Comment Chooser Title**: `comment_open_link` exists only in `values/strings.xml`; `values-fr` and `values-zh-rCN` fall back to English. | [`strings.xml:56`](../../app/src/main/res/values/strings.xml#L56) |
| **ODD-17** | **Code Hygiene** | **Low** | **Open** | **Phase-4 Native and TLS Code Hygiene**: unnamed crypt-header constant and misplaced overflow guard in the JNI encrypt path, `filterTlsProtocols` split from its neighbours, and no test of the `createSocket` protocol-floor wiring. | [`NativeCryptStateJni.cpp`](../../libraries/humla/src/main/jni/crypto/NativeCryptStateJni.cpp) |
| **ODD-18** | **Code Hygiene** | **Low** | **Open** | **Phase-4 Certificate Import/Export Hygiene**: write-only `mPendingCertBytes`, split password-zeroing ownership, `printStackTrace` instead of `Log`, fuzzy `MAC_PATTERN`, empty-string trust-store password constant, and JUnit 3 test style. | [`CertificateImportActivity.java`](../../app/src/main/java/se/lublin/mumla/preference/CertificateImportActivity.java) |
| **ODD-19** | **UI / i18n** | **Low** | **Open** | **Phase-4 Server Edit Dialog, Strings and TALK Receiver Polish**: unstyled non-scrolling URL-password warning row, missing fr/zh translations, undocumented overload parameters, and `TalkBroadcastReceiver` edge cases. | [`dialog_server_edit.xml`](../../app/src/main/res/layout/dialog_server_edit.xml) |
| **ODD-20** | **Code Hygiene** | **Low** | **Open** | **Phase-4 Settings, Manifest and Build Script Hygiene**: boxed `Boolean` default, accessor naming, redundant `hasProperty`/`enabled` checks, duplicated signing condition, and string/preference naming inconsistencies. | [`Settings.java`](../../app/src/main/java/se/lublin/mumla/Settings.java) |

---

## Detailed Topics

### ODD-01: Disconnected User Leak in ModelHandler

In [`ModelHandler.java:469-490`](../../libraries/humla/src/main/java/se/lublin/humla/protocol/ModelHandler.java#L469-L490):

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
- Any subsequent call to `ModelHandler.getUser(session)` returns a non-null `User` instance whose channel is `null`, leaving stale user objects that necessitate defensive null checks (e.g. [`MumlaOverlay.java:51`](../../app/src/main/java/se/lublin/mumla/service/MumlaOverlay.java#L51)) and risk bugs or null pointer dereferences in downstream components expecting active connected users.
- On busy servers with high user turnover, memory usage grows monotonically throughout the session.

---

### ODD-02: Incoming UDP Audio Processing Dispatched to Main UI Thread

In [`HumlaUDP.java:121-128`](../../libraries/humla/src/main/java/se/lublin/humla/net/HumlaUDP.java#L121-L128):

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
In [`HumlaConnection.java:702-725`](../../libraries/humla/src/main/java/se/lublin/humla/net/HumlaConnection.java#L702-L725):
```java
MumbleUDP.Audio audioMsg = MumbleUDP.Audio.parseFrom(ByteString.copyFrom(data, 1, data.length - 1));
for (HumlaUDPMessageListener handler : mUDPHandlers) {
    handler.messageProtobufAudio(audioMsg);
}
```
This entire parsing, protobuf decoding, and JNI queueing pipeline runs on the **Android Main UI Thread**. At typical traffic rates (50–150 UDP packets/sec), this creates severe thread contention, frame drops in the UI, and jitter in playback packet queueing.

---

### ODD-03: Unbounded Outgoing UDP Send Queue

In [`HumlaUDP.java:60, 74`](../../libraries/humla/src/main/java/se/lublin/humla/net/HumlaUDP.java#L60):

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

> **Status: Resolved on `master`** (branch `bugfix/oddities-phase4-remediation`, merge commit `f4fa74d0`): the pinned overlay now prefers live `WindowInsets` with the legacy resource lookup kept as fallback, and gravities intentionally remain physical `LEFT`/`RIGHT` (stored corners are explicitly physical, so no RTL mirroring). The description below is the pre-fix record.

In [`MumlaOverlay.java:263-285`](../../app/src/main/java/se/lublin/mumla/service/MumlaOverlay.java#L263-L285):

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

> **Status: Resolved on `master`** (branch `bugfix/oddities-phase3-remediation`, merge commit `dd6becad`, released in `0.21.19`): `onConfigurationChanged()` now refreshes the hot corner via `refreshGestureExclusionDeferred()`, posted past the rotation layout. The description below is the pre-fix record.

In [`MumlaService.java:632-637`](../../app/src/main/java/se/lublin/mumla/service/MumlaService.java#L632-L637):

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

> **Status: Resolved on `master`** (branch `bugfix/oddities-phase3-remediation`, merge commit `dd6becad`, released in `0.21.19`): all dismissal paths converge on a lifecycle-guarded dismiss listener that always clears `first_run`. The description below is the pre-fix record.

In [`MumlaActivity.java:481-503`](../../app/src/main/java/se/lublin/mumla/app/MumlaActivity.java#L481-L503):

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

> **Status: Resolved on `master`** (branch `bugfix/oddities-phase3-remediation`, merge commit `dd6becad`, released in `0.21.19`): the sentinel is unified on `Settings.DEFAULT_PUSH_KEY` (`-1`) behind `Settings.isPttKeyBound`, with legacy-`0` normalization on dialog load. The description below is the pre-fix record.

- In [`Settings.java:59`](../../app/src/main/java/se/lublin/mumla/Settings.java#L59):
  `public static final Integer DEFAULT_PUSH_KEY = -1;`
- In [`KeySelectPreferenceDialogFragment.java:35`](../../app/src/main/java/se/lublin/mumla/preference/KeySelectPreferenceDialogFragment.java#L35):
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

> **Status: Resolved on `master`** (branch `bugfix/oddities-phase4-remediation`, commit `1b08c2c6`, merge commit `f4fa74d0`): the dead commented-out block was pruned from `settings_appearance.xml`. The XML quoted below is the pre-fix record.

In [`settings_appearance.xml:74-94`](../../app/src/main/res/xml/settings_appearance.xml#L74-L94):

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
        android:title="@string/colorizechannels" />

    <EditTextPreference
        android:defaultValue="5"
        android:inputType="number"
        android:key="colorthresholdnumusers"
        android:summary="@string/colorthresholdSum"
        android:title="@string/colorthreshold" />
    -->
```

These legacy Plumble settings are commented out in XML and should be removed.

---

### ODD-09: Bandwidth-Degraded framesPerPacket Fails to Scale UDP Send Queue

In [`AudioHandler.java:266-272`](../../libraries/humla/src/main/java/se/lublin/humla/protocol/AudioHandler.java#L266-L272):

```java
if (HumlaConnection.calculateAudioBandwidth(bitrate, framesPerPacket) > maxBandwidth) {
    if (framesPerPacket <= 4 && maxBandwidth <= 32000) {
        framesPerPacket = 4;
    } else if (framesPerPacket == 1 && maxBandwidth <= 64000) {
        framesPerPacket = 2;
    } else if (framesPerPacket == 2 && maxBandwidth <= 48000) {
        framesPerPacket = 4;
    }
    ...
}
```

When server bandwidth limits enforce auto-degradation, `AudioHandler` increases `mFramesPerPacket` (e.g. from 2 to 4), reconfiguring the native encoder to produce 40ms audio packets.
However, neither `HumlaConnection` nor `HumlaUDP` is notified of this adjusted packet size:
- `HumlaUDP.mSendQueueCapacity` remains at 10 packets (calculated for standard 20ms audio).
- With 40ms packets, a 10-packet queue buffers $10 \times 40\text{ ms} = 400\text{ ms}$ of audio—double the target latency ceiling of ~200ms.
- The send queue should dynamically scale down to 5 packets for 40ms audio to preserve real-time interactivity.

---

### ODD-10: DNS Rebinding TOCTOU in Image SSRF Check

In [`MumbleImageGetter.java:598-611`](../../app/src/main/java/se/lublin/mumla/util/MumbleImageGetter.java#L598-L611):

```java
try {
    return SsrfHostPolicy.isAnyAddressBlocked(
            InetAddress.getAllByName(SsrfHostPolicy.normalizeHost(host)));
} catch (UnknownHostException e) {
    return true;
}
```

The pre-connect DNS check in `isHostBlocked()` is best-effort: [`fetchOneUrl()`](../../app/src/main/java/se/lublin/mumla/util/MumbleImageGetter.java#L541-L549) validates the hostname, then `HttpURLConnection` reconnects by hostname in `getResponseCode()`, so a DNS rebind between the check and `connect()` (TOCTOU) defeats the policy. The manual redirect loop (no auto-follow, per-hop re-check, 5-hop cap) narrows but does not eliminate the window. Already disclosed as an accepted residual in [`SsrfHostPolicy.java:34-38`](../../app/src/main/java/se/lublin/mumla/util/SsrfHostPolicy.java#L34-L38) (phase-2 plan C3). Closing it requires connecting to the checked IP directly (pinned socket or custom `SocketFactory`) with manual TLS hostname verification — a larger change than the policy itself.

---

### ODD-11: Incomplete IPv6 Transition-Mechanism Coverage in SSRF Policy

In [`SsrfHostPolicy.java:239-266`](../../app/src/main/java/se/lublin/mumla/util/SsrfHostPolicy.java#L239-L266), `isBlockedIPv6()` unwraps IPv4-mapped, IPv4-compatible, 6to4, and the well-known NAT64 `64:ff9b::/96` into `isBlockedIPv4()`, with explicit `fec0::/10`, `fc00::/7`, and `2001:db8::/32` branches. Unhandled: RFC 6052 variable-length NAT64 prefixes (`/32`–`/64`, where the IPv4 bits sit at non-`/96` offsets and need prefix-length-dependent extraction), operator-specific NAT64 prefixes, Teredo `2001::/32`, and ISATAP. Consistent with the existing rigor boundary, but an open bypass class on exotic networks. Recorded as accepted residual in the phase2-image-pipeline round-2 review.

---

### ODD-12: Thin SSRF Regression Test Layering

In [`SsrfHostPolicyTest.java:113-124`](../../app/src/test/java/se/lublin/mumla/util/SsrfHostPolicyTest.java#L113-L124):

- `testSiteLocalBlocked` passes via either the `isSiteLocalAddress()` gate in `isBlockedAddress()` or the explicit `fec0::/10` branch in `isBlockedIPv6()` on desktop JVMs, so it pins the blocking outcome but not the defense-in-depth layer.
- Embedded `169.254`/`224` branches in `isBlockedIPv4()` are exercised only indirectly; `127` is covered via `64:ff9b::7f00:1`, but there are no direct `::ffff:169.254.x.x` / `::ffff:224.0.0.1` literal tests.

Coverage gap, not a code bug. Recorded in the phase2-image-pipeline round-2 review.

---

### ODD-13: Unguarded getArguments() in Comment Dialog

In [`AbstractCommentFragment.java:56-58`](../../app/src/main/java/se/lublin/mumla/channel/comment/AbstractCommentFragment.java#L56-L58) and [`AbstractCommentFragment.java:204-205`](../../app/src/main/java/se/lublin/mumla/channel/comment/AbstractCommentFragment.java#L204-L205):

```java
mComment = getArguments().getString("comment");
...
public boolean isEditing() {
    return getArguments().getBoolean("editing");
}
```

Both production callers ([`UserMenu.java:169-175`](../../app/src/main/java/se/lublin/mumla/channel/UserMenu.java#L169-L175), [`ChannelMenu.java:106-112`](../../app/src/main/java/se/lublin/mumla/channel/ChannelMenu.java#L106-L112)) always supply a `Bundle`, so this is latent, not live. A no-args instantiation (e.g. a future caller, process-restore edge, or test) crashes with NPE. Fix: switch both sites to `requireArguments()`, which fails fast with a clear `IllegalStateException` instead of a bare NPE.

---

### ODD-14: Deprecated onAttach Overload and Dropped Cause in Comment Dialog

In [`AbstractCommentFragment.java:61-70`](../../app/src/main/java/se/lublin/mumla/channel/comment/AbstractCommentFragment.java#L61-L70):

```java
@Override
public void onAttach(Activity activity) {
    super.onAttach(activity);
    try {
        mProvider = (HumlaServiceProvider) activity;
    } catch (ClassCastException e) {
        throw new RuntimeException(activity.getClass().getName() + " must implement HumlaServiceProvider!");
    }
}
```

Two hygiene issues, both non-behavioral today: `onAttach(Activity)` is deprecated (migrate to `onAttach(Context)`), and the rethrown `RuntimeException` drops the original `ClassCastException` cause, hiding the stack in crash reports. Fix: override `onAttach(Context)`, cast `context`, and chain the cause (`new RuntimeException(msg, e)`).

---

### ODD-15: Partial View Teardown in Comment Dialog

In [`AbstractCommentFragment.java:132-141`](../../app/src/main/java/se/lublin/mumla/channel/comment/AbstractCommentFragment.java#L132-L141), `onDestroyView()` (added by the phase2-comment-webview branch) destroys and nulls `mCommentView` but leaves `mTabHost` and `mCommentEdit` reachable, retaining a partial view-hierarchy reference after dismissal. Additionally, the tab listener in [`AbstractCommentFragment.java:102-113`](../../app/src/main/java/se/lublin/mumla/channel/comment/AbstractCommentFragment.java#L102-L113) dereferences `mCommentView` unguarded (`mCommentView.loadData(...)` at line 107), so a tab switch racing teardown after `onDestroyView` would NPE. Listener callbacks cannot fire after view destruction in practice (the `TabHost` is detached with the hierarchy), so both are residual hardening, not live bugs. Fix: null `mTabHost`/`mCommentEdit` alongside `mCommentView`, and null-guard the listener's `mCommentView` deref.

---

### ODD-16: Untranslated Comment Chooser Title

`comment_open_link` ("Open link", added by the phase2-comment-webview branch for the external-browser chooser title in [`AbstractCommentFragment.java`](../../app/src/main/java/se/lublin/mumla/channel/comment/AbstractCommentFragment.java)) exists only in [`values/strings.xml`](../../app/src/main/res/values/strings.xml). `values-fr` and `values-zh-rCN` (which both translate the neighboring `comment_*` strings) fall back to English at runtime. Cosmetic; no crash. Fix: add `comment_open_link` translations on the next strings pass.

---

### ODD-17: Phase-4 Native and TLS Code Hygiene

Residual pedantic items from the `phase4-integration` review, all non-behavioral:

- In [`NativeCryptStateJni.cpp`](../../libraries/humla/src/main/jni/crypto/NativeCryptStateJni.cpp), the encrypt-side overflow guard `length > INT_MAX - 4` is correct but uses the literal `4` (the crypt header size), which is repeated in several other places in the file. A named `constexpr jint kCryptHeaderBytes = 4` would document it, and `std::numeric_limits<jint>::max()` is more type-accurate than `INT_MAX`. The guard also sits after `GetArrayLength` instead of with the other argument checks, and is effectively unreachable because `length <= srcLen` already bounds it (no test exercises it).
- In the same file, the `nullptr` returns after a successful `NewByteArray` leave the local reference to be freed on return to Java. This is harmless but worth a comment.
- In [`HumlaSSLSocketFactory.java`](../../libraries/humla/src/main/java/se/lublin/humla/net/HumlaSSLSocketFactory.java), `filterTlsProtocols` is placed between the two `createSocket` overloads, separating related methods.
- [`HumlaSSLSocketFactoryTest.java`](../../libraries/humla/src/test/java/se/lublin/humla/net/HumlaSSLSocketFactoryTest.java) covers only the pure filter. Nothing tests that `createSocket` enforces the floor (the fail-closed path) or closes the plain socket on failure; that needs a socket seam or an injectable `SSLSocketFactory`.

---

### ODD-18: Phase-4 Certificate Import/Export Hygiene

Residual pedantic items in [`CertificateImportActivity.java`](../../app/src/main/java/se/lublin/mumla/preference/CertificateImportActivity.java), [`CertificateExportActivity.java`](../../app/src/main/java/se/lublin/mumla/preference/CertificateExportActivity.java), [`MumlaTrustStore.java`](../../app/src/main/java/se/lublin/mumla/util/MumlaTrustStore.java) and their tests:

- `mPendingCertBytes` in the import activity is effectively write-only: its only read is the argument to `showPasswordDialog` right after assignment, so it could be a local.
- The password `char[]` is zeroed by both the callee (`storeKeystore`) and the caller (`finally` in the submit action). Pick one owner.
- `storeKeystore` still uses `e.printStackTrace()` rather than `Log`, and `MAC_PATTERN` matching in `isPasswordFailure` is fuzzy (message-text heuristics).
- `MumlaTrustStore` exposes its empty-string store password through `getTrustStorePassword()` with no doc explaining that the BKS store is an integrity container for public pinned certificates, not a secret.
- The export filename sanitizer intentionally replaces any single trailing extension, so `Alice v1.2` becomes `Alice v1.p12`. This is a documented, lossy rewrite; revisit if certificate names containing dots matter.
- [`CertificateExportTest.java`](../../app/src/test/java/se/lublin/mumla/preference/CertificateExportTest.java) extends JUnit 3 `TestCase` and tests only a static helper; the name suggests activity behaviour. Consider JUnit 4 and a `FilenameSanitizer`-style extraction.
- The 5 MB certificate read runs on the main thread (including on every recreation while the password dialog is up). Not pedantic, but recorded here as a related pre-existing limitation: move it to a background executor.

---

### ODD-19: Phase-4 Server Edit Dialog, Strings and TALK Receiver Polish

- [`dialog_server_edit.xml`](../../app/src/main/res/layout/dialog_server_edit.xml): the URL-password warning `TextView` has no `textAppearance`/color emphasis, no bottom margin before the title row, and no `accessibilityLiveRegion` (it can now hide while the user edits). The dialog root is not scrollable (pre-existing), so the extra row makes landscape overflow more likely.
- `server_edit_url_password_warning` in `strings.xml` and the `pref_talk_broadcast_*` strings have no `values-fr`/`values-zh-rCN` translations (`MissingTranslation` lint is disabled, so nothing flags this).
- [`ServerEditFragment.java`](../../app/src/main/java/se/lublin/mumla/servers/ServerEditFragment.java): the four-argument `createServerEditDialog` overload forwards to the five-argument one, and neither Javadoc documents `@param context`. The `urlPassword` flag could be derived from `getServer().getPassword()` for `CONNECT_ACTION` + `ignoreTitle`, removing the extra parameter. `Fragment.instantiate` is deprecated (pre-existing).
- [`TalkBroadcastReceiver.java`](../../app/src/main/java/se/lublin/mumla/service/ipc/TalkBroadcastReceiver.java): throws `UnsupportedOperationException` for a non-TALK action instead of ignoring it, silently ignores unknown `status` values, and ignores the configured transmit mode (voice activity / continuous).
- [`MumlaService.java`](../../app/src/main/java/se/lublin/mumla/service/MumlaService.java): the `mTalkReceiverRegistered` field comment says "mid-connection toggles"; the receiver is registered once `isConnectionEstablished()` is true, which precedes server sync, so "registered" and "synchronized" are not the same state. Harmless because the receiver re-checks `isConnected()`.
- Default-on `TALK` broadcast with an exported receiver and no permission is a policy decision, not a defect: any installed app can key the microphone while connected. It needs an explicit owner sign-off; the setting only narrows exposure once the user opts out.

---

### ODD-20: Phase-4 Settings, Manifest and Build Script Hygiene

- [`Settings.java`](../../app/src/main/java/se/lublin/mumla/Settings.java): `DEFAULT_ALLOW_TALK_BROADCAST` is a boxed `Boolean` (copying `DEFAULT_CHAT_NOTIFY`); plain `boolean` avoids unboxing. The default is duplicated as a literal in `settings_general.xml`. The accessor `isTalkBroadcastAllowed` does not mirror the constant name `PREF_ALLOW_TALK_BROADCAST`.
- [`app/build.gradle`](../../app/build.gradle): `android.hasProperty("signingConfigs")` is always true for the Android extension, and the "has release signing" condition is duplicated between the `release {}` build type and the `taskGraph.whenReady` guard (extract a shared `def`). `whenReady` dereferences the `android` extension at execution time, which would not be configuration-cache compatible if the cache is ever enabled (only `org.gradle.caching` is on today).
- `strings.xml` / `preference.xml`: the new strings sit far from related groups, `pref_talk_broadcast_title`/`_summary` breaks the neighbouring `chatNotifications`/`chatNotificationsSum` naming, `server_edit_*` and `pref_*` strings live in different files, and the TALK title says "Tasker" while the summary says "automation apps" without stating that any app can send the broadcast.
- [`AndroidManifest.xml`](../../app/src/main/AndroidManifest.xml): `android:enabled="true"` is the default and redundant (pre-existing), and `READ_EXTERNAL_STORAGE` lost its explanatory comment when the legacy-storage comment was removed; note it is only needed on SDK ≤ 32 for the image picker.
- Pre-existing, out-of-scope but recorded: `android:allowBackup="true"` with server passwords in the database and client certificates plus the trust store under `filesDir`; consider `dataExtractionRules`/`fullBackupContent` exclusions, or `false`.

## Remediation Roadmap

For the prioritized engineering roadmap, implementation details, thread safety requirements, and test strategy, see:
- [Miscellaneous Oddities Remediation Roadmap](remediation-plan.md)

