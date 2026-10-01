# Miscellaneous Oddities Remediation Roadmap

This document outlines a prioritized, phased engineering roadmap for resolving all identified miscellaneous codebase defects, threading bottlenecks, memory leaks, lifecycle issues, and code hygiene gaps in Mumla OLED ([`README.md`](../../docs/misc-oddities/README.md)).

## Table of Contents

1. [Phase 1: Core Reliability & Threading Architecture (P0 / P1) — COMPLETED](#phase-1-core-reliability--threading-architecture-p0--p1--completed)
2. [Phase 2: Network Transport & Real-Time Buffer Parity (P1 / P2) — COMPLETED](#phase-2-network-transport--real-time-buffer-parity-p1--p2--completed)
3. [Phase 3: UI Lifecycle, Input State & Dialog Correctness (P2) — COMPLETED & RELEASED (0.21.19)](#phase-3-ui-lifecycle-input-state--dialog-correctness-p2--completed--released-02119)
4. [Phase 4: Modernization & Code Hygiene (P3) — COMPLETED](#phase-4-modernization--code-hygiene-p3--completed)
5. [Phase 5: Dynamic Bandwidth & Network Adaptation (P2) — COMPLETED](#phase-5-dynamic-bandwidth--network-adaptation-p2--completed)
6. [Phase 6: Comment Dialog Hardening Follow-Ups (P3) — COMPLETED](#phase-6-comment-dialog-hardening-follow-ups-p3--completed)
7. [Phase 7: Phase-4 Integration Review Residuals (P3) — COMPLETED](#phase-7-phase-4-integration-review-residuals-p3--completed)
8. [Phase 8: SSRF Residual Hardening (P1 / P2) — PLANNED](#phase-8-ssrf-residual-hardening-p1--p2--planned)
9. [Verification & Test Strategy](#verification--test-strategy)

---

## Phase 1: Core Reliability & Threading Architecture (P0 / P1) — COMPLETED

> [!NOTE]
> **Status: COMPLETED**
>
> All Phase 1 remediation items (ODD-01 and ODD-02) have been implemented, tested, and merged into `master` (branch `bugfix/oddities-phase1-remediation`, commits [`9be4ab1b`](../../libraries/humla/src/main/java/se/lublin/humla/protocol/ModelHandler.java#L469-L490) through [`9fbbc750`](../../libraries/humla/src/main/java/se/lublin/humla/net/HumlaConnection.java#L102), merge commit `8bd15530`): ODD-01 resolved by removing user sessions from `mUsers` in [`ModelHandler.messageUserRemove()`](../../libraries/humla/src/main/java/se/lublin/humla/protocol/ModelHandler.java#L469-L490) with automated verification in [`ModelHandlerUserRemoveTest.java`](../../libraries/humla/src/test/java/se/lublin/humla/protocol/ModelHandlerUserRemoveTest.java); ODD-02 resolved by offloading incoming UDP voice processing to the background receiver thread with cross-thread visibility hardening and automated verification in [`HumlaUDPReceiveThreadTest.java`](../../libraries/humla/src/test/java/se/lublin/humla/net/HumlaUDPReceiveThreadTest.java).

### 1.1 Fix Disconnected User Memory Leak in ModelHandler (ODD-01) — RESOLVED

**Status**: Resolved on `master` in commit [`9be4ab1b`](../../libraries/humla/src/main/java/se/lublin/humla/protocol/ModelHandler.java#L469-L490) (branch `bugfix/oddities-phase1-remediation`).

**Component**: [`ModelHandler.java`](../../libraries/humla/src/main/java/se/lublin/humla/protocol/ModelHandler.java#L469-L490)

**Problem**:
In [`ModelHandler.messageUserRemove()`](../../libraries/humla/src/main/java/se/lublin/humla/protocol/ModelHandler.java#L469), disconnected and kicked users are detached from their channel (`user.setChannel(null)`), but are **never removed from `mUsers`**.
1. **Memory Bloat**: Departed [`User`](../../libraries/humla/src/main/java/se/lublin/humla/model/User.java) objects accumulate monotonically in memory throughout the session, retaining certificates, names, comments, and textures.
2. **Ghost User References**: Calls to [`ModelHandler.getUser(session)`](../../libraries/humla/src/main/java/se/lublin/humla/protocol/ModelHandler.java#L78) return orphaned `User` instances whose `getChannel()` is `null`. Any downstream component assuming connected users have non-null channels (e.g., overlay adapters, search dialogs) encounters unexpected `NullPointerException`s.
3. **Upstream Protocol Parity**: Upstream Mumble explicitly removes disconnected users from its model in [`Messages.cpp:873`](https://github.com/mumble-voip/mumble/blob/master/src/mumble/Messages.cpp#L873) via `pmModel->removeUser(pDst)`.

**Solution**:
Remove the user session from `mUsers` in [`messageUserRemove()`](../../libraries/humla/src/main/java/se/lublin/humla/protocol/ModelHandler.java#L469) after logging and notifying observers:

```java
@Override
public void messageUserRemove(Mumble.UserRemove msg) {
    final User user = mUsers.get(msg.getSession());
    final User actor = mUsers.get(msg.getActor());
    final String reason = msg.getReason();

    final String userName = user != null ? user.getName() : "unknown";
    final String actorName = actor != null ? actor.getName() : "unknown";
    if (msg.getSession() == mSession) {
        mLogger.logWarning(mContext.getString(msg.getBan() ? R.string.chat_notify_kick_ban_self : R.string.chat_notify_kick_self, MessageFormatter.highlightString(actorName), reason));
    } else if (actor != null) {
        mLogger.logWarning(mContext.getString(msg.getBan() ? R.string.chat_notify_kick_ban : R.string.chat_notify_kick, MessageFormatter.highlightString(actorName), reason, MessageFormatter.highlightString(userName)));
    } else {
        mLogger.logInfo(mContext.getString(R.string.chat_notify_disconnected, MessageFormatter.highlightString(userName)));
    }

    if (user != null) {
        user.setChannel(null);
    }
    mObserver.onUserRemoved(user, reason);
    mUsers.remove(msg.getSession());
}
```

**Edge Cases & Impact**:
- If `user == null` (e.g. out-of-order or duplicate `UserRemove` packets from server), `mUsers.remove()` is safe on missing keys and `mObserver.onUserRemoved(null, reason)` handles null gracefully.
- Upstream Mumble guards user removal with `if (pDst != pSelf) pmModel->removeUser(pDst);` ([`Messages.cpp:872`](https://github.com/mumble-voip/mumble/blob/master/src/mumble/Messages.cpp#L872)). In Mumla, removing the local session upon self-kick/ban is also safe because a self-kick or ban terminates the connection and immediately triggers [`ModelHandler.clear()`](../../libraries/humla/src/main/java/se/lublin/humla/protocol/ModelHandler.java#L115).

---

### 1.2 Offload Incoming UDP Audio Processing from Main UI Thread (ODD-02) — RESOLVED

**Status**: Resolved on `master` in commits [`b8938c50`](../../libraries/humla/src/main/java/se/lublin/humla/net/HumlaUDP.java#L123-L128) and [`9fbbc750`](../../libraries/humla/src/main/java/se/lublin/humla/net/HumlaConnection.java#L102) (branch `bugfix/oddities-phase1-remediation`).

**Component**: [`HumlaUDP.java`](../../libraries/humla/src/main/java/se/lublin/humla/net/HumlaUDP.java#L123-L128), [`HumlaConnection.java`](../../libraries/humla/src/main/java/se/lublin/humla/net/HumlaConnection.java#L671), [`AudioHandler.java`](../../libraries/humla/src/main/java/se/lublin/humla/protocol/AudioHandler.java#L370-L374)

**Problem**:
1. In [`HumlaUDP.java:123-128`](../../libraries/humla/src/main/java/se/lublin/humla/net/HumlaUDP.java#L123-L128), incoming datagrams allocate a `new Runnable` and post to `mCallbackHandler` (`Looper.getMainLooper()`).
2. This violates [`UDPConnectionListener`](../../libraries/humla/src/main/java/se/lublin/humla/net/HumlaUDP.java#L216-L223)'s documented threading model:
   ```java
   /** onUDPDataReceived is always called on the UDP receive thread. */
   ```
3. In [`HumlaConnection.onUDPDataReceived()`](../../libraries/humla/src/main/java/se/lublin/humla/net/HumlaConnection.java#L699-L735), Protobuf parsing (`MumbleUDP.Audio.parseFrom`), byte copies, listener iterations, and [`AudioOutput.queueProtobufVoiceData()`](../../libraries/humla/src/main/java/se/lublin/humla/audio/AudioOutput.java#L426) (JNI queueing and `mInactiveLock.notify()`) run on the Android Main UI thread.
4. Active chatter at 50 packets/second per speaker inundates the main looper with hundreds of tasks per second, causing UI frame drops and introducing playback audio jitter whenever UI animations, drawer drags, or layout passes block the main looper.

**Solution**:
Execute [`onUDPDataReceived()`](../../libraries/humla/src/main/java/se/lublin/humla/net/HumlaConnection.java#L699) directly on the background UDP receiving thread (`mDatagramThread`):

```java
// HumlaUDP.java
try {
    final byte[] buffer = mCryptState.decrypt(data, length);

    if (mListener != null) {
        if (buffer != null) {
            // Direct callback on UDP receiver thread per UDPConnectionListener contract
            mListener.onUDPDataReceived(buffer);
        } else if (mCryptState.getLastGoodElapsed() > 5000000 &&
                mCryptState.getLastRequestElapsed() > 5000000) {
            mCryptState.resetLastRequestTime();
            mCallbackHandler.post(new Runnable() {
                @Override
                public void run() {
                    mListener.resyncCryptState();
                }
            });
            Log.d(TAG, "Packet failed to decrypt, discarding and requesting crypt state resync");
        } else {
            Log.d(TAG, "Packet failed to decrypt, discarding");
        }
    }
} catch (BadPaddingException | IllegalBlockSizeException | ShortBufferException e) {
    Log.d(TAG, "Discarding packet", e);
}
```

**Thread Safety & Concurrency Requirements**:
1. **`HumlaConnection` Concurrency**: In [`HumlaConnection.java:121`](../../libraries/humla/src/main/java/se/lublin/humla/net/HumlaConnection.java#L121), `mUDPHandlers` is already declared as a thread-safe `ConcurrentLinkedQueue<HumlaUDPMessageListener>`, so handler registration and iteration across threads is non-blocking and safe. However, shared connection fields mutated on the UDP thread must be hardened:
   - `mLastUDPPing` ([`HumlaConnection.java:102`](../../libraries/humla/src/main/java/se/lublin/humla/net/HumlaConnection.java#L102)): Plain `long` updated on the UDP thread (lines 261, 271, 281) and read on the main thread — must use `volatile` or `AtomicLong` to prevent 64-bit word tearing on 32-bit platforms.
   - `mServerVersionV2` (line 109) and `mMaxBandwidth` (line 113): Updated in `messageProtobufPing` on the UDP thread and read on TCP/UI threads — declare as `volatile`.
2. **`ModelHandler.getUser(session)` and `User.mLocalMuted`**: [`AudioOutput.queueProtobufVoiceData()`](../../libraries/humla/src/main/java/se/lublin/humla/audio/AudioOutput.java#L435) calls `mListener.getUser(session)` and `user.isLocalMuted()`.
   - Ensure `ModelHandler.mUsers` uses a `ConcurrentHashMap<Integer, User>` to allow safe concurrent lookups from the UDP receive thread while the TCP thread mutates user state.
   - Mark `User.mLocalMuted` ([`User.java:54`](../../libraries/humla/src/main/java/se/lublin/humla/model/User.java#L54)) as `volatile` to guarantee immediate cross-thread visibility when local mute state changes on the main thread.
3. **UI Observer Dispatch**: Any talking state events or icon animations triggered by incoming voice must be dispatched to the main UI looper via `Handler.post()`, isolating high-rate audio decoding from UI rendering (which [`AudioOutput.java:483-492`](../../libraries/humla/src/main/java/se/lublin/humla/audio/AudioOutput.java#L483-L492) already handles via `mMainHandler.post` in `onUserTalkStateChanged`).

---

## Phase 2: Network Transport & Real-Time Buffer Parity (P1 / P2) — COMPLETED

> [!NOTE]
> **Status: COMPLETED**
>
> Phase 2 remediation item ODD-03 has been implemented, hardened, and merged into `master` (branch `bugfix/oddities-phase2-remediation`, commits [`c98bff81`](../../libraries/humla/src/main/java/se/lublin/humla/net/HumlaUDP.java#L60-L75) and [`cc0f9260`](../../libraries/humla/src/main/java/se/lublin/humla/net/HumlaUDP.java), merge commit `2b0cfd5d`), with dynamic packet duration queue scaling added in branch `feature/dynamic-udp-send-queue` (commits [`3dc5f152`](../../libraries/humla/src/main/java/se/lublin/humla/net/HumlaUDP.java) and [`c7e2bab4`](../../libraries/humla/src/main/java/se/lublin/humla/net/HumlaConnection.java), merge commit `571f15a0`). Verified with comprehensive unit test coverage in [`HumlaUDPSendQueueTest.java`](../../libraries/humla/src/test/java/se/lublin/humla/net/HumlaUDPSendQueueTest.java).

### 2.1 Bound Outgoing UDP Send Queue & Enforce Drop Policy (ODD-03) — RESOLVED

**Status**: Resolved on `master` in commits [`c98bff81`](../../libraries/humla/src/main/java/se/lublin/humla/net/HumlaUDP.java#L60-L75) and [`cc0f9260`](../../libraries/humla/src/main/java/se/lublin/humla/net/HumlaUDP.java) (branch `bugfix/oddities-phase2-remediation`), dynamically scaled in commit [`3dc5f152`](../../libraries/humla/src/main/java/se/lublin/humla/net/HumlaUDP.java) (branch `feature/dynamic-udp-send-queue`).

**Component**: [`HumlaUDP.java`](../../libraries/humla/src/main/java/se/lublin/humla/net/HumlaUDP.java#L60-L75), [`HumlaUDP.java:186-202`](../../libraries/humla/src/main/java/se/lublin/humla/net/HumlaUDP.java#L186-L202)

**Problem**:
1. [`HumlaUDP.java:74`](../../libraries/humla/src/main/java/se/lublin/humla/net/HumlaUDP.java#L74) instantiates `mSendQueue` as an unbounded `new LinkedBlockingQueue<>()`.
2. Voice packets are appended at 50–100 packets/sec during transmission.
3. When cellular connectivity stalls (e.g. transit tunnels, cell tower handover), the queue accumulates unbounded packets.
4. Voice data is real-time and perishable. Upon network recovery, blasting hundreds of stale packets wastes cellular bandwidth, overflows server jitter buffers, and creates confusing voice playback bursts. Upstream Mumble transmits datagrams immediately via non-blocking socket writes ([`ServerHandler.cpp:349`](https://github.com/mumble-voip/mumble/blob/master/src/mumble/ServerHandler.cpp#L349)) without unbounded queueing.

**Solution**:
Introduce a bounded send queue whose capacity dynamically scales with the configured audio packet duration (`audio_per_packet`: 10ms, 20ms, 40ms, 60ms) to strictly maintain ~200ms target latency headroom:

```math
\text{capacity} = \max\left(2, \left\lceil \frac{200\text{ ms}}{\text{framesPerPacket} \times 10\text{ ms}} \right\rceil\right)
```

- **10ms** (1 frame): 20 packets (200ms)
- **20ms** (2 frames, default): 10 packets (200ms)
- **40ms** (4 frames): 5 packets (200ms)
- **60ms** (6 frames): 4 packets (240ms)

```java
// HumlaUDP.java
public static int calculateQueueCapacity(int framesPerPacket) {
    int fpp = sanitizeFramesPerPacket(framesPerPacket);
    int packetDurationMs = fpp * FRAME_DURATION_MS;
    return Math.max(2, (int) Math.ceil((double) TARGET_BUFFER_DURATION_MS / packetDurationMs));
}

public void sendMessage(@NotNull final byte[] data, final int length) {
    final InetAddress resolvedHost = mResolvedHost;
    if (!mCryptState.isValid() || !mConnected || resolvedHost == null) {
        return;
    }
    try {
        byte[] encryptedData = mCryptState.encrypt(data, length);
        final DatagramPacket packet = new DatagramPacket(encryptedData, encryptedData.length);
        packet.setAddress(resolvedHost);
        packet.setPort(mPort);

        // Atomic compound eviction/offer guarantees strict capacity bounding without thread races
        synchronized (mSendLock) {
            while (mSendQueue.size() >= mSendQueueCapacity) {
                mSendQueue.poll();
            }
            mSendQueue.offer(packet);
        }
    } catch (BadPaddingException | IllegalBlockSizeException | ShortBufferException e) {
        Log.w(TAG, "Failed to encrypt outgoing UDP packet", e);
    }
}
```

**Edge Cases & Impact**:
- **Dynamic Reconfiguration**: When the user switches `audio_per_packet` in Settings while connected, `setTargetFramesPerPacket()` adjusts capacity on the fly and immediately flushes excess stale packets if capacity decreased.
- **Atomic Head-Drop Eviction**: Guarding `size() >= mSendQueueCapacity`, `poll()`, and `offer()` with `mSendLock` prevents multi-producer queue inversions and race conditions while allowing non-blocking reads by `OutgoingConsumer`.
- **Ping & Terminator Packets**: Maintaining ~200ms latency ceiling across all packet durations ensures fresh pings and speech terminators proceed without multi-second delays.

---

## Phase 3: UI Lifecycle, Input State & Dialog Correctness (P2) — COMPLETED & RELEASED (0.21.19)

> [!NOTE]
> **Status: COMPLETED & RELEASED (0.21.19)**
>
> All Phase 3 items were implemented on branch `bugfix/oddities-phase3-remediation`, merged into `master` via commit `dd6becad`, and released in pre-release `0.21.19`. ODD-06: all first-run certificate dialog dismissal paths converge on a lifecycle-guarded dismiss listener in a dedicated dialog field, so `first_run` is always cleared and the startup action runs on a live instance. ODD-07: no-PTT-key sentinel unified on `Settings.DEFAULT_PUSH_KEY` (`-1`) behind `Settings.isPttKeyBound`, with key-capture rejection of unknown keys and legacy-`0` normalization on dialog load, covered by `SettingsPushKeyTest`. ODD-05: `MumlaHotCorner.refreshGestureExclusionDeferred()` invoked from `MumlaService.onConfigurationChanged()` (posted past the rotation layout) and after re-show.
>
> **Accepted trade-offs** (pedantic review of the branch, updated for the round-2 follow-ups):
>
> - **Backgrounded/rotating dismissal skips the startup action.** The dismiss listener still clears `first_run` on every path, but skips the startup action when the instance is finishing, destroyed, or rotating; a rotated instance therefore shows no news dialog (same as the pre-branch behavior, minus the crash and the re-prompt loop).
> - **Guard test covers the helper, not the call sites.** `SettingsPushKeyTest` now exercises the production `Settings.isPttKeyBound` helper rather than duplicating its expression, but nothing verifies that `MumlaActivity` keeps calling it (the project's JVM-only test setup lacks Robolectric).
> - **Plan snippets are a historical record.** The Solution snippets below reflect the proposals at planning time and drift slightly from the shipped implementation (e.g. the shipped refresh is the deferred post-layout variant; the shipped guide lives in a dedicated dialog field).

Phase 3 resolves UX annoyances, preference state divergence, and overlay rotation inconsistencies.

### 3.1 Fix First Run Certificate Dialog Outside Touch & Dismissal (ODD-06) — RESOLVED

**Status**: Resolved on `master` (branch `bugfix/oddities-phase3-remediation`, commit `2e999f0c`, follow-ups through `16d9b625`; merge commit `dd6becad`), released in `0.21.19`.

**Component**: [`MumlaActivity.java`](../../app/src/main/java/se/lublin/mumla/app/MumlaActivity.java#L610-L652)

**Problem**:
1. [`showFirstRunGuide()`](../../app/src/main/java/se/lublin/mumla/app/MumlaActivity.java#L610) creates an `AlertDialog` with only a positive button (`R.string.generate`).
2. The dialog is cancelable by default. If the user touches outside or presses Back:
   - The dialog dismisses silently.
   - `mSettings.setFirstRun(false)` is **never executed**.
   - The `else` branch running `StartupAction` (see [`MumlaActivity.java:462-470`](../../app/src/main/java/se/lublin/mumla/app/MumlaActivity.java#L462-L470), implemented in [`StartupAction.java`](../../app/src/foss/java/se/lublin/mumla/app/StartupAction.java)) is skipped because it is located in the `else` branch of `if (mSettings.isFirstRun())`.
   - On the next app launch, `isFirstRun()` remains `true`, re-spawning the dialog repeatedly.

**Solution**:
Provide an explicit negative button and cancellation listener that mark `first_run = false` and proceed with standard startup:

```java
private void showFirstRunGuide() {
    if (mSettings.isUsingCertificate()) {
        mSettings.setFirstRun(false);
        new StartupAction().execute(this);
        return;
    }
    String msg = getString(R.string.first_run_generate_certificate);
    new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.first_run_generate_certificate_title)
            .setMessage(msg)
            .setPositiveButton(R.string.generate, (DialogInterface dialog, int which) -> {
                MumlaCertificateGenerateTask generateTask = new MumlaCertificateGenerateTask(MumlaActivity.this) {
                    @Override
                    protected void onPostExecute(DatabaseCertificate result) {
                        super.onPostExecute(result);
                        if (result != null) mSettings.setDefaultCertificateId(result.getId());
                        new StartupAction().execute(MumlaActivity.this);
                    }
                };
                generateTask.execute();
                mSettings.setFirstRun(false);
            })
            .setNegativeButton(android.R.string.cancel, (dialog, which) -> {
                mSettings.setFirstRun(false);
                new StartupAction().execute(MumlaActivity.this);
            })
            .setOnCancelListener(dialog -> {
                mSettings.setFirstRun(false);
                new StartupAction().execute(MumlaActivity.this);
            })
            .show();
}
```

---

### 3.2 Harmonize PTT Keycode Reset Sentinel (-1 vs 0) (ODD-07) — RESOLVED

**Status**: Resolved on `master` (branch `bugfix/oddities-phase3-remediation`, commit `cb11911d`, follow-ups through `af2a5ad2` and `16d9b625`; merge commit `dd6becad`), released in `0.21.19`.

**Component**: [`Settings.java`](../../app/src/main/java/se/lublin/mumla/Settings.java#L59), [`KeySelectPreferenceDialogFragment.java`](../../app/src/main/java/se/lublin/mumla/preference/KeySelectPreferenceDialogFragment.java#L33-L58), [`MumlaActivity.java`](../../app/src/main/java/se/lublin/mumla/app/MumlaActivity.java#L577-L593)

**Problem**:
1. [`Settings.java:59`](../../app/src/main/java/se/lublin/mumla/Settings.java#L59) defines `DEFAULT_PUSH_KEY = -1`.
2. In [`KeySelectPreferenceDialogFragment.java:35`](../../app/src/main/java/se/lublin/mumla/preference/KeySelectPreferenceDialogFragment.java#L35), clicking "Reset Key" sets `mCurrentValue = 0` and writes `0` to preferences. Line 56 falls back to `0`.
3. In Android, `0` is `KeyEvent.KEYCODE_UNKNOWN`.
4. If a user resets their PTT key, the preference is stored as `0`. External hardware devices, gamepads, or input drivers emitting `KEYCODE_UNKNOWN` (`0`) will match `keyCode == mSettings.getPushToTalkKey()`, inadvertently triggering PTT transmission. On a clean install where the preference is unconfigured (`-1`), this false activation never occurs.

**Solution**:
Unify the "no key" sentinel value to `Settings.DEFAULT_PUSH_KEY` (`-1`):

1. **`KeySelectPreferenceDialogFragment.java`**:
   ```java
   // In onBindDialogView()
   KeySelectDialogPreference preference = (KeySelectDialogPreference) getPreference();
   mCurrentValue = requireNonNull(preference.getSharedPreferences())
           .getInt(preference.getKey(), Settings.DEFAULT_PUSH_KEY);
   updateValueView();

   // In onPrepareDialogBuilder()
   builder.setNeutralButton(R.string.reset_key, (dialog, which) -> {
       KeySelectDialogPreference pref = (KeySelectDialogPreference) getPreference();
       mCurrentValue = Settings.DEFAULT_PUSH_KEY;
       if (pref.callChangeListener(mCurrentValue)) {
           requireNonNull(pref.getSharedPreferences())
                   .edit().putInt(pref.getKey(), mCurrentValue).apply();
       }
   });

   // In updateValueView()
   private void updateValueView() {
       if (mCurrentValue <= 0 || mCurrentValue == Settings.DEFAULT_PUSH_KEY) {
           mValueView.setText(R.string.no_ptt_key);
       } else {
           ...
       }
   }
   ```
2. **`MumlaActivity.java`**:
   Add a defensive guard ensuring unconfigured keycodes cannot match in both `onKeyDown()` ([`MumlaActivity.java:577`](../../app/src/main/java/se/lublin/mumla/app/MumlaActivity.java#L577)) and `onKeyUp()` ([`MumlaActivity.java:587`](../../app/src/main/java/se/lublin/mumla/app/MumlaActivity.java#L587):
   ```java
   int pttKey = mSettings.getPushToTalkKey();
   if (mService != null && pttKey > 0 && keyCode == pttKey) {
       mService.onTalkKeyDown(); // or onTalkKeyUp() in onKeyUp()
       return true;
   }
   ```

---

### 3.3 Refresh Hot Corner Gesture Exclusion Rects on Configuration Change (ODD-05) — RESOLVED

**Status**: Resolved on `master` (branch `bugfix/oddities-phase3-remediation`, commit `bda21ffc`, follow-ups through `91b92e67` and `c42b7244`; merge commit `dd6becad`), released in `0.21.19`.

**Component**: [`MumlaService.java`](../../app/src/main/java/se/lublin/mumla/service/MumlaService.java#L652-L660), [`MumlaHotCorner.java`](../../app/src/main/java/se/lublin/mumla/service/MumlaHotCorner.java#L65-L82)

**Problem**:
1. In [`MumlaService.onConfigurationChanged()`](../../app/src/main/java/se/lublin/mumla/service/MumlaService.java#L652), `mChannelOverlay.updatePosition()` is invoked, but `mHotCorner` is completely ignored.
2. In [`MumlaHotCorner.addOnLayoutChangeListener()`](../../app/src/main/java/se/lublin/mumla/service/MumlaHotCorner.java#L65), `setSystemGestureExclusionRects()` is conditioned on `(width != mLastWidth || height != mLastHeight)`.
3. Because [`ptt_corner.xml`](../../app/src/main/res/layout/ptt_corner.xml) is fixed at 48dp × 48dp, rotating between portrait and landscape preserves width and height. The condition evaluates to `false`, skipping `setSystemGestureExclusionRects()`.
4. On Android 10+ (Q+), system gesture exclusion rects are cleared or invalidated upon display rotation. As a result, the hot corner loses its exclusion zone after rotation and becomes intercepted by Android's system back-gesture.

**Solution**:
1. Add an explicit `refreshGestureExclusion()` method on [`MumlaHotCorner`](../../app/src/main/java/se/lublin/mumla/service/MumlaHotCorner.java) that reapplies exclusion rects without checking dimensions:
   ```java
   public void refreshGestureExclusion() {
       if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && mView != null && mShown) {
           int width = mView.getWidth();
           int height = mView.getHeight();
           if (width > 0 && height > 0) {
               mView.setSystemGestureExclusionRects(Collections.singletonList(new Rect(0, 0, width, height)));
           }
       }
   }
   ```
2. In [`MumlaService.onConfigurationChanged()`](../../app/src/main/java/se/lublin/mumla/service/MumlaService.java#L652), refresh both overlays:
   ```java
   @Override
   public void onConfigurationChanged(Configuration newConfig) {
       super.onConfigurationChanged(newConfig);
       if (mChannelOverlay != null && mChannelOverlay.isShown()) {
           mChannelOverlay.updatePosition();
       }
       if (mHotCorner != null && mHotCorner.isShown()) {
           // Deferred past the rotation relayout so getWidth/getHeight reflect
           // the post-rotation size instead of silently no-opping (ODD-05).
           mHotCorner.refreshGestureExclusionDeferred();
       }
   }
   ```

---

## Phase 4: Modernization & Code Hygiene (P3) — COMPLETED

> [!NOTE]
> **Status: COMPLETED**
>
> All Phase 4 remediation items (ODD-04 and ODD-08) have been implemented and merged into `master` (branch `bugfix/oddities-phase4-remediation`, commits `1b08c2c6` through `23b892f0`, merge commit `f4fa74d0`): ODD-04 resolved by preferring live `WindowInsets` (`getInsetsIgnoringVisibility(systemBars | displayCutout)`) via a single per-layout `WindowMetrics` query shared by pure, unit-tested margin resolvers, with the legacy resource and hardcoded fallbacks preserved; ODD-08 resolved by pruning the dead commented-out preferences from `settings_appearance.xml`. Overlay gravities intentionally stay on physical `LEFT`/`RIGHT` (stored values and labels are explicitly physical, e.g. `topLeft` / `↖ Top left`), verified by [`MumlaOverlayMarginsTest.java`](../../app/src/test/java/se/lublin/mumla/service/MumlaOverlayMarginsTest.java).

Phase 4 updates legacy Android platform APIs and prunes dead code baggage.

### 4.1 Modernize Status & Navigation Bar Insets in Overlay HUD (ODD-04)

**Status**: Resolved on `master` (branch `bugfix/oddities-phase4-remediation`, commits `1b08c2c6` through `23b892f0`; merge commit `f4fa74d0`).

**Component**: [`MumlaOverlay.java`](../../app/src/main/java/se/lublin/mumla/service/MumlaOverlay.java#L263-L285), [`Settings.java`](../../app/src/main/java/se/lublin/mumla/Settings.java#L477-L489)

**Problem**:
1. [`MumlaOverlay.java:265, 277`](../../app/src/main/java/se/lublin/mumla/service/MumlaOverlay.java#L265) uses `Resources.getIdentifier()` to query system dimensions (`"status_bar_height"`, `"navigation_bar_height"`). This approach is deprecated, fragile across OEM skins, and ignorant of modern display cutouts, camera punch-holes, and gesture navigation bars.
2. In [`Settings.java:477-489`](../../app/src/main/java/se/lublin/mumla/Settings.java#L477-L489), pinned overlay gravities return `Gravity.LEFT` / `Gravity.RIGHT` instead of `Gravity.START` / `Gravity.END`, preventing proper right-to-left (RTL) locale layout mirroring.

**Solution**:
1. For Android 11+ (API 30+), query `WindowMetrics` and `WindowInsets` through a shared helper; keep the legacy `getIdentifier()` fallback for API < 30 unchanged (including the `40dp`/`56dp` defaults):
   ```java
   private android.graphics.Insets getSystemBarInsets() {
       if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
           WindowMetrics metrics = mWindowManager.getCurrentWindowMetrics();
           return metrics.getWindowInsets().getInsetsIgnoringVisibility(
                   WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
       }
       return null;
   }

   private int getTopMargin(DisplayMetrics dm) {
       android.graphics.Insets insets = getSystemBarInsets();
       if (insets != null) {
           return insets.top + (int) (8 * dm.density);
       }
       // Fallback for API < 30 (unchanged)
       int statusBarHeight = 0;
       int resourceId = mService.getResources().getIdentifier("status_bar_height", "dimen", "android");
       if (resourceId > 0) {
           statusBarHeight = mService.getResources().getDimensionPixelSize(resourceId);
       }
       return statusBarHeight > 0 ? statusBarHeight + (int) (8 * dm.density) : (int) (40 * dm.density);
   }

   private int getBottomMargin(DisplayMetrics dm) {
       android.graphics.Insets insets = getSystemBarInsets();
       if (insets != null) {
           return insets.bottom + (int) (8 * dm.density);
       }
       // Fallback for API < 30 (unchanged)
       int navBarHeight = 0;
       int resourceId = mService.getResources().getIdentifier("navigation_bar_height", "dimen", "android");
       if (resourceId > 0) {
           navBarHeight = mService.getResources().getDimensionPixelSize(resourceId);
       }
       return navBarHeight > 0 ? navBarHeight + (int) (8 * dm.density) : (int) (56 * dm.density);
   }
   ```
   - Use `getInsetsIgnoringVisibility()` (not `getInsets()`) so the pinned HUD keeps its offset when bars are transiently hidden; the cutout inset still applies. Include `displayCutout()` on both top and bottom queries.
   - Keep the `SDK_INT >= R` guard inline with the project's existing style; add the `WindowMetrics`/`WindowInsets` imports when implementing.
   - **Service-context caveat (acceptance criterion):** `mWindowManager` comes from a `Service` context, so `getCurrentWindowMetrics().getWindowInsets()` may return empty or display-wide insets. Verify non-zero system-bar insets on an API 30+ device. If the service-context query proves unreliable, read insets from the attached overlay view instead (`mOverlayView.getRootWindowInsets()` / `OnApplyWindowInsetsListener` after `addView()`) and keep `WindowMetrics` only as a secondary source.
2. Keep [`Settings.getOverlayGravity()`](../../app/src/main/java/se/lublin/mumla/Settings.java#L477) and [`Settings.getHotCornerGravity()`](../../app/src/main/java/se/lublin/mumla/Settings.java#L289) on physical `Gravity.LEFT` / `Gravity.RIGHT` (pedantic review of branch `bugfix/oddities-phase4-remediation`: stored values and labels are explicitly physical — `topLeft`, `↖ Top left` — so remapping to `START`/`END` would silently flip corners for existing RTL users and make the arrow labels lie, with unverified `WindowManager.LayoutParams` `x` polarity under `START` gravity). A hand-picked screen corner is not layout chrome; do not mirror it. The floating (unpinned) path in [`MumlaOverlay.java:262`](../../app/src/main/java/se/lublin/mumla/service/MumlaOverlay.java#L262) likewise stays absolute (`TOP | LEFT`) because dragged `x`/`y` positions are stored and restored verbatim.
3. Guard the modern path: if the relevant `WindowInsets` edge is empty (`<= 0`, e.g. service-context `getCurrentWindowMetrics()` returning zero insets on some OEM/API), fall through to the legacy resource lookup before the `40dp`/`56dp` defaults instead of returning `8dp` alone.

---

### 4.2 Remove Dead Commented-Out XML Preferences (ODD-08)

**Status**: Resolved on `master` (branch `bugfix/oddities-phase4-remediation`, commit `1b08c2c6`; merge commit `f4fa74d0`).

**Component**: [`settings_appearance.xml`](../../app/src/main/res/xml/settings_appearance.xml#L74-L94)

**Problem**:
Obsolete XML preferences (`channellistrowheight`, `colorizechannellist`, `colorthresholdnumusers`) remain commented out in [`settings_appearance.xml:74-94`](../../app/src/main/res/xml/settings_appearance.xml#L74-L94). The referenced resources (`@array/rowheightText`, `@string/rowheight`, etc.) do not exist in the project, causing confusion during codebase exploration.

**Solution**:
Prune lines 74–94 from [`settings_appearance.xml`](../../app/src/main/res/xml/settings_appearance.xml#L74-L94).

---

## Phase 5: Dynamic Bandwidth & Network Adaptation (P2) — COMPLETED

> [!NOTE]
> **Status: COMPLETED**
>
> All Phase 5 remediation items (ODD-09) have been implemented and merged into `master` (branch `feature/oddities-phase5-odd09`, commits `e5ef91c4` through `f8403a61`, merge commit `cc1efe0c`; follow-up `1504dc4a` logs queue rescales at debug): ODD-09 resolved by extracting the pure `AudioHandler.computeEffectiveConfig` degradation decision (covered by [`AudioBandwidthDegradationTest.java`](../../libraries/humla/src/test/java/se/lublin/humla/protocol/AudioBandwidthDegradationTest.java)) with a service-mediated `FramesPerPacketListener` that rescales the `HumlaUDP` send queue via `HumlaConnection.setTargetFramesPerPacket()` (40 ms audio now buffers 5 packets, preserving the ~200 ms latency ceiling), plus `fpp` sanitizing in the connection setter and `volatile` on the cross-thread audio fields. Queue-capacity and sanitize-agreement pins live in [`HumlaUDPSendQueueTest.java`](../../libraries/humla/src/test/java/se/lublin/humla/net/HumlaUDPSendQueueTest.java).

Phase 5 addresses secondary transport feedback loops and real-time buffer adaptation under server-enforced bandwidth constraints.

### 5.1 Scale HumlaUDP Send Queue on Bandwidth Throttling (ODD-09)

**Status**: Resolved on `master` (branch `feature/oddities-phase5-odd09`, commits `e5ef91c4` through `f8403a61`; merge commit `cc1efe0c`).

**Component**: [`AudioHandler.java`](../../libraries/humla/src/main/java/se/lublin/humla/protocol/AudioHandler.java), [`HumlaConnection.java`](../../libraries/humla/src/main/java/se/lublin/humla/net/HumlaConnection.java), [`HumlaService.java`](../../libraries/humla/src/main/java/se/lublin/humla/HumlaService.java) (`connect()`, `createAudioHandler()`, `configureExtras(EXTRAS_FRAMES_PER_PACKET)`)

**Implementation plan**: [`phase5-odd09-plan.md`](phase5-odd09-plan.md)

**Problem**:
1. When server maximum bandwidth constraints trigger auto-degradation in `AudioHandler.setMaxBandwidth()`, `framesPerPacket` may be increased from 2 to 4 (or 1 to 2/4) to reduce packet header overhead.
2. `AudioHandler` updates `mNativeEngine` so local Opus encoding outputs 40ms packets, but does not notify `HumlaConnection` or `HumlaUDP`.
3. Consequently, `HumlaUDP.mSendQueueCapacity` remains at 10 packets (configured for default 20ms audio).
4. With 40ms packets, a 10-packet queue allows up to 400ms ($10 \times 40\text{ ms}$) of buffered voice data during network stalls, doubling latency and causing bufferbloat.

**Solution**:
Wire a listener or feedback mechanism from `AudioHandler` to `HumlaConnection.setTargetFramesPerPacket()`:
1. When `setMaxBandwidth()` adjusts `framesPerPacket`, emit a callback or notification to `HumlaConnection`.
2. `HumlaConnection.setTargetFramesPerPacket()` dynamically scales `HumlaUDP` send queue capacity to 5 packets for 40ms audio (preserving the ~200ms target latency ceiling) and immediately flushes stale excess packets if downsized.

---

## Phase 6: Comment Dialog Hardening Follow-Ups (P3) — COMPLETED

> [!NOTE]
> **Status: COMPLETED**
>
> All Phase 6 remediation items (ODD-13 through ODD-16) have been implemented and merged into `master` (branch `feature/oddities-phase6-comment-dialog`, commits `cc393d98` through `772f725a`, merge commit `308a133d`): ODD-13 resolved by failing fast with `requireArguments()`, key validation via a `validateArguments` hook, and shared `ARG_*` constants across fragments, menus, and tests, covered by [`CommentFragmentArgumentsTest.java`](../../app/src/test/java/se/lublin/mumla/channel/comment/CommentFragmentArgumentsTest.java); ODD-14 resolved by migrating to `onAttach(Context)` with chained cause across the module; ODD-15 resolved by completing the teardown (detach-before-destroy, listener guard, observer release), covered by [`CommentFragmentTeardownTest.java`](../../app/src/test/java/se/lublin/mumla/channel/comment/CommentFragmentTeardownTest.java); ODD-16 resolved by translating `comment_open_link` in `values-fr` and `values-zh-rCN`. Two pedantic review rounds expanded the branch to adjacent incidentals (service/host lifecycle guards, call-site modernization, menu null guards) with no behavior change on reachable paths.

Residual low-severity items in `AbstractCommentFragment` surfaced by the phase2-comment-webview pedantic reviews. All latent or cosmetic; no live crash or leak.

**Implementation plan:** [`phase6-comment-dialog-plan.md`](phase6-comment-dialog-plan.md)

### 6.1 Guard Comment Dialog Arguments (ODD-13)

**Status**: Resolved on `master` (branch `feature/oddities-phase6-comment-dialog`, commits `8cfe2bca` through `772f725a`; merge commit `308a133d`).

**Component**: [`AbstractCommentFragment.java`](../../app/src/main/java/se/lublin/mumla/channel/comment/AbstractCommentFragment.java#L56-L58)

**Problem**:
`onCreate()` and `isEditing()` dereference `getArguments()` without a null check. Both production callers always supply a bundle, so a no-args instantiation is the only crash path.

**Solution**:
Switch both sites to `requireArguments()`.

---

### 6.2 Modernize Comment Dialog Attachment (ODD-14)

**Status**: Resolved on `master` (branch `feature/oddities-phase6-comment-dialog`, commits `8cfe2bca` through `772f725a`; merge commit `308a133d`).

**Component**: [`AbstractCommentFragment.java`](../../app/src/main/java/se/lublin/mumla/channel/comment/AbstractCommentFragment.java#L62-L68)

**Problem**:
Overrides the deprecated `onAttach(Activity)` overload, and the rethrown `RuntimeException` drops the `ClassCastException` cause.

**Solution**:
Override `onAttach(Context)` and chain the cause in the rethrow.

---

### 6.3 Complete Comment Dialog View Teardown (ODD-15)

**Status**: Resolved on `master` (branch `feature/oddities-phase6-comment-dialog`, commits `da86e5c9` through `772f725a`; merge commit `308a133d`).

**Component**: [`AbstractCommentFragment.java`](../../app/src/main/java/se/lublin/mumla/channel/comment/AbstractCommentFragment.java#L132-L141)

**Problem**:
`onDestroyView()` nulls `mCommentView` but leaves `mTabHost`/`mCommentEdit` reachable; the tab listener dereferences `mCommentView` unguarded.

**Solution**:
Null all three view fields in `onDestroyView()` and null-guard the listener's `mCommentView` deref.

---

### 6.4 Translate Comment Chooser Title (ODD-16)

**Status**: Resolved on `master` (branch `feature/oddities-phase6-comment-dialog`, commit `7e5c206c`; merge commit `308a133d`).

**Component**: [`strings.xml`](../../app/src/main/res/values/strings.xml#L56)

**Problem**:
`comment_open_link` has no `values-fr`/`values-zh-rCN` translations; those locales fall back to English.

**Solution**:
Add the two translations on the next strings pass.

---

## Phase 7: Phase-4 Integration Review Residuals (P3) — COMPLETED

> [!NOTE]
> **Status: COMPLETED**
>
> All Phase 7 remediation items (ODD-17 through ODD-20) have been implemented and merged into `master` (branch `bugfix/oddities-phase7-remediation`, commits `eda3a519` through `ecbc7591`, merge commit `ca6094fe`): ODD-17 resolved by naming the crypt-header constant and adding the injectable TLS socket seam with fail-closed tests; ODD-18 resolved by moving the certificate import read off the main thread with single-owner password zeroing and `Log` hygiene; ODD-19 resolved by hardening the TALK receiver (ignore-and-log unknown actions, transmit-mode gating) with fr/zh translations under the recorded keep-default-on decision; ODD-20 resolved by trimming the signing checks and restoring the manifest note, with the `allowBackup` posture tracked separately.

### 7.1 Native and TLS Hygiene (ODD-17)

**Status**: Resolved on `master` (branch `bugfix/oddities-phase7-remediation`, commits `eda3a519` through `ecbc7591`; merge commit `ca6094fe`).

**Component**: [`NativeCryptStateJni.cpp`](../../libraries/humla/src/main/jni/crypto/NativeCryptStateJni.cpp) (`nativeEncrypt`), [`HumlaSSLSocketFactory.java`](../../libraries/humla/src/main/java/se/lublin/humla/net/HumlaSSLSocketFactory.java), [`HumlaSSLSocketFactoryTest.java`](../../libraries/humla/src/test/java/se/lublin/humla/net/HumlaSSLSocketFactoryTest.java)

**Problem**:
1. The encrypt-side overflow guard uses the literal `4` (the crypt header size), repeated in several places in the file, and `INT_MAX` instead of the type-accurate limit.
2. The `nullptr` returns after a successful `NewByteArray` leave the local reference to be freed on return to Java. Harmless, but undocumented.
3. `filterTlsProtocols` sits between the two `createSocket` overloads, separating related methods.
4. Only the pure filter is tested. Nothing tests that `createSocket` enforces the TLS 1.2+ floor (the fail-closed path) or closes the plain socket on failure.

**Solution**:
1. Introduce `constexpr jint kCryptHeaderBytes = 4` and compare against `std::numeric_limits<jint>::max()`. Keep the guard where it is (after `GetArrayLength`, whose result it depends on); the earlier "move it with the other argument checks" ask is withdrawn as cosmetic.
2. Add a one-line comment on the post-`NewByteArray` `nullptr` returns noting the local reference is freed on return to Java.
3. Relocate `filterTlsProtocols` below both `createSocket` overloads.
4. Add an injectable socket seam (or factory) so a JVM test can assert the fail-closed path (no TLS 1.2+ match throws, plain socket closed). No dedicated native test for the length guard: `length <= srcLen` already bounds it to realistic sizes (only reachable near 2 GB arrays), so the constant plus comment is the fix.

**Edge Cases & Impact**:
- Pure renames/comments; no behavior change on reachable paths.
- The seam must not alter the production socket path (SNI hostname layering, `.onion` endpoint-identification exemption, fail-closed throw stay intact).

**Acceptance criteria**: constant used at every header-size site; `filterTlsProtocols` no longer splits the overloads; new JVM test pins fail-closed + plain-socket-close; existing TLS tests pass.

---

### 7.2 Certificate Import/Export Hygiene (ODD-18)

**Status**: Resolved on `master` (branch `bugfix/oddities-phase7-remediation`, commits `660176cb` through `8786a024`; merge commit `ca6094fe`).

**Component**: [`CertificateImportActivity.java`](../../app/src/main/java/se/lublin/mumla/preference/CertificateImportActivity.java), [`CertificateExportActivity.java`](../../app/src/main/java/se/lublin/mumla/preference/CertificateExportActivity.java), [`MumlaTrustStore.java`](../../app/src/main/java/se/lublin/mumla/util/MumlaTrustStore.java), [`CertificateExportTest.java`](../../app/src/test/java/se/lublin/mumla/preference/CertificateExportTest.java)

#### 7.2a Gated fix: certificate read blocks the main thread (not P3)

**Problem**: `readCertBytes()` (5 MB cap) runs on the main thread in `onActivityResult` and again on rotation restore. ANR risk, not pedantry.

**Solution**: Move the read to a background executor; the rotation path already restores from the `Uri` (never from persisted bytes), so re-read off-thread preserves behavior. Verify with a large-file import (no jank) plus rotate-mid-dialog.

#### 7.2b Style nits (drive-by only)

**Problem**:
1. `mPendingCertBytes` is effectively write-only: its only consumption is the `showPasswordDialog` argument it was just assigned from (rotation re-reads from the `Uri`).
2. The password `char[]` is zeroed twice: `storeKeystore` (callee) and the `submitAction` `finally` (caller) operate on the same array.
3. One remaining `e.printStackTrace()` in the `storeKeystore` failure branch; `MAC_PATTERN` message-text heuristics are fuzzy.
4. `MumlaTrustStore.getTrustStorePassword()` exposes the empty-string store password with no doc (the BKS store is an integrity container for public pinned certificates, not a secret).
5. The sanitizer intentionally strips any single trailing extension (`Alice v1.2` becomes `Alice v1.p12`).
6. `CertificateExportTest` extends JUnit 3 `TestCase` and tests only a static helper.

**Solution**:
1. Make the cert bytes a local passed through to the dialog callbacks; drop the field (keep the `Uri` + metadata restore path as-is).
2. Single owner is the callee: `storeKeystore` zeroes on all paths; the caller drops its redundant `finally` fill.
3. Switch `printStackTrace` to `Log.w(TAG, ...)`; keep `MAC_PATTERN` heuristics but note their fuzziness in a comment.
4. Document the empty-string password on `getTrustStorePassword()` (integrity-only, public pins).
5. Decision: keep the lossy single-extension strip (already documented in the `sanitizeExportFilename` Javadoc); change only if product decides dot-names matter. No code change by default.
6. JUnit 3→4 migration is optional and opportunistic — only with the next functional touch of that file.

**Edge Cases & Impact**:
- Field-to-local refactor must preserve the rotation contract: `Bundle` holds only the `Uri` + metadata, never bytes or passwords.
- Zeroing change is behavior-preserving (double-zero today); the risk is dropping the wrong fill, so keep the callee fill on every return path.

**Acceptance criteria**: no `printStackTrace` remains (grep); password zeroed exactly once per path (code inspection); trust-store password documented; sanitizer pins unchanged; rotation restore still re-reads from `Uri`.

---

### 7.3 Server Edit Dialog, Strings and TALK Receiver Polish (ODD-19)

**Status**: Resolved on `master` (branch `bugfix/oddities-phase7-remediation`, commits `b5c9a6ea` through `ecbc7591`; merge commit `ca6094fe`).

**Component**: [`dialog_server_edit.xml`](../../app/src/main/res/layout/dialog_server_edit.xml), [`ServerEditFragment.java`](../../app/src/main/java/se/lublin/mumla/servers/ServerEditFragment.java), [`TalkBroadcastReceiver.java`](../../app/src/main/java/se/lublin/mumla/service/ipc/TalkBroadcastReceiver.java), [`MumlaService.java`](../../app/src/main/java/se/lublin/mumla/service/MumlaService.java), `values/preference.xml`, `values/strings.xml`

#### 7.3a Gated decision: default-on exported TALK broadcast (not P3)

**Problem**: The TALK broadcast receiver is exported with no permission and enabled by default, so any installed app can key the microphone while connected. The setting only narrows exposure once the user opts out.

**Solution**: Explicit owner sign-off before any further polish: keep default-on, default-off, or permission-gate. Polish work below assumes the decision is recorded; do not ship receiver hardening as a substitute for the decision.

> Decision (owner, 2026-10-01): keep default-on. The receiver hardening and the user-facing "any installed app can send this broadcast" disclosure ship under that decision.

#### 7.3b Dialog, strings, and receiver polish

**Problem**:
1. The URL-password warning `TextView` has no `textAppearance`/color emphasis, no bottom margin before the title row, and no `accessibilityLiveRegion` (it can hide while the user edits). The dialog root is not scrollable (pre-existing), so the extra row makes landscape overflow more likely.
2. `server_edit_url_password_warning` and the `pref_talk_broadcast_*` strings have no `values-fr`/`values-zh-rCN` translations (`MissingTranslation` lint is disabled, so nothing flags this).
3. Neither `createServerEditDialog` overload documents `@param context`.
4. `TalkBroadcastReceiver` throws `UnsupportedOperationException` for a non-TALK action instead of ignoring it, silently ignores unknown `status` values, and ignores the configured transmit mode.
5. The `mTalkReceiverRegistered` field comment says "mid-connection toggles" but registration is gated on `isConnectionEstablished()` (which precedes server sync); harmless because the receiver re-checks `isConnected()`.
6. `Fragment.instantiate` is deprecated (pre-existing, out of scope).

**Solution**:
1. Style the warning row: body-small appearance with error coloring, 8 dp bottom margin, `accessibilityLiveRegion="polite"`. Keep the root non-scrollable (landscape overflow accepted as today) unless the decision in 7.3a reopens the dialog.
2. Add the fr/zh translations via a manual locale pass (not lint — see Verification).
3. Document `@param context` on both overloads. Keep the `urlPassword` flag: it carries provenance ("came from a deep link"), which cannot be derived from the password value without warning on manually typed passwords. The earlier "derive it" suggestion is withdrawn.
4. Ignore (and log) unknown actions instead of throwing; log unknown `status` values instead of silently dropping them; gate `setTalkingState` on push-to-talk transmit mode, matching the service's PTT-sound gating.
5. Correct the field comment to describe the actual gate (`isConnectionEstablished()` + preference toggle). Leave `Fragment.instantiate` alone.

**Edge Cases & Impact**:
- Unknown-action ignore must not mask registration bugs: log at warning level so a mis-wired filter is still visible.
- Transmit-mode gating changes behavior only for voice-activity/continuous users receiving TALK broadcasts (previously honored, now ignored); call this out in release notes.

**Acceptance criteria**: warning row styled with margin + live region (layout inspection, TalkBack pass); fr/zh strings present (manual locale check); both overloads document `@param context`; receiver unit test pins ignore-and-log + transmit-mode gate; `Fragment.instantiate` untouched.

---

### 7.4 Settings, Manifest and Build Script Hygiene (ODD-20)

**Status**: Resolved on `master` (branch `bugfix/oddities-phase7-remediation`, commit `d0002a0e`; merge commit `ca6094fe`).

**Component**: [`Settings.java`](../../app/src/main/java/se/lublin/mumla/Settings.java), [`app/build.gradle`](../../app/build.gradle), `values/preference.xml`, `values/strings.xml`, [`AndroidManifest.xml`](../../app/src/main/AndroidManifest.xml)

**Problem**:
1. `DEFAULT_ALLOW_TALK_BROADCAST` is a boxed `Boolean` (copying `DEFAULT_CHAT_NOTIFY`); the default is also duplicated as a literal in `settings_general.xml`. The accessor `isTalkBroadcastAllowed` does not mirror the constant name `PREF_ALLOW_TALK_BROADCAST`.
2. `android.hasProperty("signingConfigs")` is always true for the Android extension, so the real check is only the second half; the "has release signing" condition is duplicated between the `release {}` block (configuration time) and the `taskGraph.whenReady` guard (execution time).
3. New strings sit far from related groups; `pref_talk_broadcast_title`/`_summary` breaks the neighboring `chatNotifications` naming; the TALK title says "Tasker" while the summary says "automation apps" without stating any app can send the broadcast.
4. `android:enabled="true"` is the default and redundant (pre-existing); `READ_EXTERNAL_STORAGE` lost its explanatory comment (only needed on SDK ≤ 32 for the image picker).
5. Pre-existing, out of scope: `android:allowBackup="true"` with server passwords, client certificates, and the trust store under `filesDir`.

**Solution**:
1. Use primitive `boolean` for new defaults; leave existing boxed constants until the next functional touch (no standalone rename churn). Do not rename the accessor — the churn outweighs the benefit.
2. Simplify to `signingConfigs.hasProperty("release")` (drop the vacuous `android.hasProperty` half). Do not "extract a shared `def`" across the two sites: one runs at configuration time, the other at execution time, so sharing needs an `ext` property or helper method, not a plain `def`. The configuration-cache note (`whenReady` dereferencing `android` at execution time) is speculative while only `org.gradle.caching` is on — record, do not act.
3. String regrouping/renaming and the Tasker-vs-automation wording are opportunistic; the "any app can send this" disclosure belongs to the 7.3a decision, not to a rename.
4. Restore the `READ_EXTERNAL_STORAGE` comment (`maxSdkVersion=32`, image picker only). Leave `android:enabled="true"` alone.
5. `allowBackup` posture (exclusions vs `false`) is tracked separately as a security decision, not as Phase 7 polish. No change in this phase.

**Edge Cases & Impact**:
- Boxed-to-primitive changes are source-compatible for the `getBoolean(key, default)` call sites but touch every reader; batch with functional work to avoid churn.
- Build-script edits risk breaking release signing; verify both with-signing and without-signing configurations (see Verification).

**Acceptance criteria**: no new boxed defaults; manifest comment restored; release build with signing config succeeds and without it fails closed with the existing error; `allowBackup` decision recorded elsewhere.

---

## Phase 8: SSRF Residual Hardening (P1 / P2) — PLANNED

> [!NOTE]
> **Status: PLANNED**
>
> ODD-10 through ODD-12 remain open on `master`. The commit-ready design is locked in
> [`phase8-ssrf-residuals-plan.md`](phase8-ssrf-residuals-plan.md): ODD-11 closes the exactly-matchable
> transition gaps (Teredo, ISATAP, local-use NAT64 `64:ff9b:1::/48`; operator NAT64 and 6rd stay a documented
> residual), ODD-12 pins the shadowed policy branches with direct package-visible tests, and ODD-10 closes
> the DNS-rebinding TOCTOU with single-resolution pinned fetching (checked-IP connection with SNI and
> platform hostname verification against the original host). Severity posture is unchanged: ODD-10 stays
> Medium in the matrix with the low-practical-blast-radius note recorded in the plan. The implementing
> branch flips this section to COMPLETED and `README.md` ODD-10 – ODD-12 to Resolved per the Phase 1–7
> convention.

---

## Verification & Test Strategy

To ensure zero regressions across all phases, each change must be accompanied by targeted unit and integration tests:

| Phase | Item | Automated Verification | Manual / Device Check |
|---|---|---|---|
| **Phase 1** | **ODD-01** | [`ModelHandlerUserRemoveTest.java`](../../libraries/humla/src/test/java/se/lublin/humla/protocol/ModelHandlerUserRemoveTest.java) verifying `mUsers.get(session) == null` after `messageUserRemove`. | Connect to test server, have a remote user join and leave; inspect heap via Android Profiler. |
| **Phase 1** | **ODD-02** | [`HumlaUDPReceiveThreadTest.java`](../../libraries/humla/src/test/java/se/lublin/humla/net/HumlaUDPReceiveThreadTest.java) verifying `onUDPDataReceived` is invoked on the UDP receive thread, not `Looper.getMainLooper()`. | High-rate voice chatter benchmark (150 packets/sec); measure UI thread frame times (`gfxinfo`) ensuring zero dropped frames. |
| **Phase 2** | **ODD-03** | [`HumlaUDPSendQueueTest.java`](../../libraries/humla/src/test/java/se/lublin/humla/net/HumlaUDPSendQueueTest.java) verifying queue bounds to capacity, dynamic packet duration scaling, and dropping oldest packets on stall. | Throttle connection to 0 kbps for 5 seconds while holding PTT; unthrottle and observe server incoming packet rate. |
| **Phase 3** | **ODD-06** | Robolectric test in `MumlaActivityTest.java` simulating outside touch dismissal and verifying `isFirstRun() == false`. | Fresh install; tap outside first-run certificate dialog; force stop and relaunch to verify dialog does not reappear. |
| **Phase 3** | **ODD-07** | Unit test in `SettingsTest.java` verifying `getPushToTalkKey()` returns `-1` before and after reset; verify `KEYCODE_UNKNOWN` (`0`) does not trigger PTT. | Open PTT key preference, click "Reset Key", verify "None" is displayed and key events with `keyCode=0` are ignored. |
| **Phase 3** | **ODD-05** | Service unit test verifying `mHotCorner.refreshGestureExclusion()` is called in `onConfigurationChanged()`. | Enable hot corner on Android 10+ device; rotate screen; perform edge back gesture over hot corner to verify exclusion is active. |
| **Phase 4** | **ODD-04** | [`MumlaOverlayMarginsTest.java`](../../app/src/test/java/se/lublin/mumla/service/MumlaOverlayMarginsTest.java) verifying the pure margin resolvers (live insets win, legacy resource fallback, hardcoded defaults; edge gutter always added); `SettingsOverlayTest` keeps `LEFT`/`RIGHT` overlay expectations. JVM-only setup cannot exercise `WindowManager`/`WindowMetrics` (no Robolectric). | Test overlay positioning on punch-hole and notch devices in portrait and landscape; verify non-zero service-context insets on API 30+. |
| **Phase 4** | **ODD-08** | Gradle build and resource compilation check (`assembleFossDebug`). | Verify settings appearance screen loads and renders without XML inflation warnings. |
| **Phase 5** | **ODD-09** | [`AudioBandwidthDegradationTest.java`](../../libraries/humla/src/test/java/se/lublin/humla/protocol/AudioBandwidthDegradationTest.java) covering the pure `computeEffectiveConfig` degradation vectors; `HumlaUDPSendQueueTest.java` pins the degraded `fpp=4` capacity (5 packets) and the `setTargetFramesPerPacket` sanitize agreement. | Connect to bandwidth-limited server (32 kbps); verify send queue capacity shrinks dynamically from 10 to 5 (rescales now log at debug). |
| **Phase 6** | **ODD-13** | Unit test instantiating the comment fragment without arguments, verifying `IllegalStateException` instead of NPE. | Open user/channel comment dialogs; verify they render. |
| **Phase 6** | **ODD-14** | Lint check (`Deprecated` warning) confirming no `onAttach(Activity)` override remains. | Open comment dialogs; verify provider binding works. |
| **Phase 6** | **ODD-15** | [`CommentFragmentTeardownTest.java`](../../app/src/test/java/se/lublin/mumla/channel/comment/CommentFragmentTeardownTest.java) verifying all three view fields null after dismiss and post-teardown tab callbacks safe. | Open and dismiss comment dialogs repeatedly; inspect heap for retained view hierarchies. |
| **Phase 6** | **ODD-16** | Lint `MissingTranslation` check on `comment_open_link`. | Switch to French/Chinese locales; open a comment link chooser and verify the title is translated. |
| **Phase 7** | **ODD-17** | JVM test of `createSocket` fail-closed behavior via an injectable socket seam (fail-closed throw + plain-socket close). No native test for the length guard (`length <= srcLen` already bounds it; constant + comment is the fix). | None. |
| **Phase 7** | **ODD-18** | Existing import/export tests pass, plus: no `printStackTrace` remains (grep); sanitizer pins unchanged; rotation restore re-reads from `Uri` (no bytes/passwords in `Bundle`); background-executor read covered by manual large-file import below. | Import a password-protected PKCS#12 with a wrong then right password; rotate mid-dialog; export and re-import; import a large file and confirm no main-thread jank. |
| **Phase 7** | **ODD-19** | Receiver unit test for unknown action/status (ignore-and-log, no throw) and transmit-mode gating. Translations verified by manual locale pass, not lint (`MissingTranslation` is disabled in `app/build.gradle`). | Open a `mumble://` link with an embedded password in a right-to-left, large-font, landscape configuration; switch to French/Chinese locales and verify the warning + TALK strings. |
| **Phase 7** | **ODD-20** | Gradle configuration check (`assembleFossDebug`) and lint, verified both with and without the local signing config (without must fail closed with the existing error). `allowBackup` posture verified separately, not here. | None. |
| **Phase 8** | **ODD-10** | JVM tests for the pure pinning helpers (`buildPinnedUrl` / `hostHeaderValue` string and port vectors); no allow-all verifier (grep). TLS wiring covered by device checks. | Serve valid-HTTPS image (renders), wrong-host cert and self-signed cert (both refused), short-TTL rebind to private target (never connected), cross-host redirect chain (renders) plus redirect to private IP (refused); API 21–23 behavior recorded. |
| **Phase 8** | **ODD-11** | [`SsrfHostPolicyTest.java`](../../app/src/test/java/se/lublin/mumla/util/SsrfHostPolicyTest.java) vectors for Teredo (blocked client/server halves, allowed public), ISATAP (blocked, allowed, u/l-bit variant), and local-use NAT64 `/48` (blocked, allowed, nonzero-u-octet gate). | None (JVM suite covers it). |
| **Phase 8** | **ODD-12** | Direct package-visible `isBlockedIPv4`/`isBlockedIPv6` tests pinning every explicit branch with boundary vectors, plus public-API NAT64/6to4 embedded `169.254`/`224`/`10` tests. | None (JVM suite covers it). |
