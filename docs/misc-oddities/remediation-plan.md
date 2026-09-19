# Miscellaneous Oddities Remediation Roadmap

This document outlines a prioritized, phased engineering roadmap for resolving all identified miscellaneous codebase defects, threading bottlenecks, memory leaks, lifecycle issues, and code hygiene gaps in Mumla OLED ([`README.md`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/docs/misc-oddities/README.md)).

## Table of Contents

1. [Phase 1: Core Reliability & Threading Architecture (P0 / P1) — COMPLETED](#phase-1-core-reliability--threading-architecture-p0--p1--completed)
2. [Phase 2: Network Transport & Real-Time Buffer Parity (P1 / P2) — COMPLETED](#phase-2-network-transport--real-time-buffer-parity-p1--p2--completed)
3. [Phase 3: UI Lifecycle, Input State & Dialog Correctness (P2)](#phase-3-ui-lifecycle-input-state--dialog-correctness-p2)
4. [Phase 4: Modernization & Code Hygiene (P3)](#phase-4-modernization--code-hygiene-p3)
5. [Phase 5: Dynamic Bandwidth & Network Adaptation (P2)](#phase-5-dynamic-bandwidth--network-adaptation-p2)
6. [Verification & Test Strategy](#verification--test-strategy)

---

## Phase 1: Core Reliability & Threading Architecture (P0 / P1) — COMPLETED

> [!NOTE]
> **Status: COMPLETED**
>
> All Phase 1 remediation items (ODD-01 and ODD-02) have been implemented, tested, and merged into `master` (branch `bugfix/oddities-phase1-remediation`, commits [`9be4ab1b`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/java/se/lublin/humla/protocol/ModelHandler.java#L469-L490) through [`9fbbc750`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/java/se/lublin/humla/net/HumlaConnection.java#L102), merge commit [`8bd15530`](file:///home/bualy/files/devel/mumla_dev/mumla-oled)): ODD-01 resolved by removing user sessions from `mUsers` in [`ModelHandler.messageUserRemove()`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/java/se/lublin/humla/protocol/ModelHandler.java#L469-L490) with automated verification in [`ModelHandlerUserRemoveTest.java`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/test/java/se/lublin/humla/protocol/ModelHandlerUserRemoveTest.java); ODD-02 resolved by offloading incoming UDP voice processing to the background receiver thread with cross-thread visibility hardening and automated verification in [`HumlaUDPReceiveThreadTest.java`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/test/java/se/lublin/humla/net/HumlaUDPReceiveThreadTest.java).

### 1.1 Fix Disconnected User Memory Leak in ModelHandler (ODD-01) — RESOLVED

**Status**: Resolved on `master` in commit [`9be4ab1b`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/java/se/lublin/humla/protocol/ModelHandler.java#L469-L490) (branch `bugfix/oddities-phase1-remediation`).

**Component**: [`ModelHandler.java`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/java/se/lublin/humla/protocol/ModelHandler.java#L469-L490)

**Problem**:
In [`ModelHandler.messageUserRemove()`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/java/se/lublin/humla/protocol/ModelHandler.java#L469), disconnected and kicked users are detached from their channel (`user.setChannel(null)`), but are **never removed from `mUsers`**.
1. **Memory Bloat**: Departed [`User`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/java/se/lublin/humla/model/User.java) objects accumulate monotonically in memory throughout the session, retaining certificates, names, comments, and textures.
2. **Ghost User References**: Calls to [`ModelHandler.getUser(session)`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/java/se/lublin/humla/protocol/ModelHandler.java#L78) return orphaned `User` instances whose `getChannel()` is `null`. Any downstream component assuming connected users have non-null channels (e.g., overlay adapters, search dialogs) encounters unexpected `NullPointerException`s.
3. **Upstream Protocol Parity**: Upstream Mumble explicitly removes disconnected users from its model in [`Messages.cpp:873`](file:///home/bualy/files/devel/mumla_dev/mumble/src/mumble/Messages.cpp#L873) via `pmModel->removeUser(pDst)`.

**Solution**:
Remove the user session from `mUsers` in [`messageUserRemove()`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/java/se/lublin/humla/protocol/ModelHandler.java#L469) after logging and notifying observers:

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
- Upstream Mumble guards user removal with `if (pDst != pSelf) pmModel->removeUser(pDst);` ([`Messages.cpp:872`](file:///home/bualy/files/devel/mumla_dev/mumble/src/mumble/Messages.cpp#L872)). In Mumla, removing the local session upon self-kick/ban is also safe because a self-kick or ban terminates the connection and immediately triggers [`ModelHandler.clear()`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/java/se/lublin/humla/protocol/ModelHandler.java#L115).

---

### 1.2 Offload Incoming UDP Audio Processing from Main UI Thread (ODD-02) — RESOLVED

**Status**: Resolved on `master` in commits [`b8938c50`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/java/se/lublin/humla/net/HumlaUDP.java#L123-L128) and [`9fbbc750`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/java/se/lublin/humla/net/HumlaConnection.java#L102) (branch `bugfix/oddities-phase1-remediation`).

**Component**: [`HumlaUDP.java`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/java/se/lublin/humla/net/HumlaUDP.java#L123-L128), [`HumlaConnection.java`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/java/se/lublin/humla/net/HumlaConnection.java#L671), [`AudioHandler.java`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/java/se/lublin/humla/protocol/AudioHandler.java#L370-L374)

**Problem**:
1. In [`HumlaUDP.java:123-128`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/java/se/lublin/humla/net/HumlaUDP.java#L123-L128), incoming datagrams allocate a `new Runnable` and post to `mCallbackHandler` (`Looper.getMainLooper()`).
2. This violates [`UDPConnectionListener`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/java/se/lublin/humla/net/HumlaUDP.java#L216-L223)'s documented threading model:
   ```java
   /** onUDPDataReceived is always called on the UDP receive thread. */
   ```
3. In [`HumlaConnection.onUDPDataReceived()`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/java/se/lublin/humla/net/HumlaConnection.java#L699-L735), Protobuf parsing (`MumbleUDP.Audio.parseFrom`), byte copies, listener iterations, and [`AudioOutput.queueProtobufVoiceData()`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/java/se/lublin/humla/audio/AudioOutput.java#L426) (JNI queueing and `mInactiveLock.notify()`) run on the Android Main UI thread.
4. Active chatter at 50 packets/second per speaker inundates the main looper with hundreds of tasks per second, causing UI frame drops and introducing playback audio jitter whenever UI animations, drawer drags, or layout passes block the main looper.

**Solution**:
Execute [`onUDPDataReceived()`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/java/se/lublin/humla/net/HumlaConnection.java#L699) directly on the background UDP receiving thread (`mDatagramThread`):

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
1. **`HumlaConnection` Concurrency**: In [`HumlaConnection.java:121`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/java/se/lublin/humla/net/HumlaConnection.java#L121), `mUDPHandlers` is already declared as a thread-safe `ConcurrentLinkedQueue<HumlaUDPMessageListener>`, so handler registration and iteration across threads is non-blocking and safe. However, shared connection fields mutated on the UDP thread must be hardened:
   - `mLastUDPPing` ([`HumlaConnection.java:102`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/java/se/lublin/humla/net/HumlaConnection.java#L102)): Plain `long` updated on the UDP thread (lines 261, 271, 281) and read on the main thread — must use `volatile` or `AtomicLong` to prevent 64-bit word tearing on 32-bit platforms.
   - `mServerVersionV2` (line 109) and `mMaxBandwidth` (line 113): Updated in `messageProtobufPing` on the UDP thread and read on TCP/UI threads — declare as `volatile`.
2. **`ModelHandler.getUser(session)` and `User.mLocalMuted`**: [`AudioOutput.queueProtobufVoiceData()`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/java/se/lublin/humla/audio/AudioOutput.java#L435) calls `mListener.getUser(session)` and `user.isLocalMuted()`.
   - Ensure `ModelHandler.mUsers` uses a `ConcurrentHashMap<Integer, User>` to allow safe concurrent lookups from the UDP receive thread while the TCP thread mutates user state.
   - Mark `User.mLocalMuted` ([`User.java:54`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/java/se/lublin/humla/model/User.java#L54)) as `volatile` to guarantee immediate cross-thread visibility when local mute state changes on the main thread.
3. **UI Observer Dispatch**: Any talking state events or icon animations triggered by incoming voice must be dispatched to the main UI looper via `Handler.post()`, isolating high-rate audio decoding from UI rendering (which [`AudioOutput.java:483-492`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/java/se/lublin/humla/audio/AudioOutput.java#L483-L492) already handles via `mMainHandler.post` in `onUserTalkStateChanged`).

---

## Phase 2: Network Transport & Real-Time Buffer Parity (P1 / P2) — COMPLETED

> [!NOTE]
> **Status: COMPLETED**
>
> Phase 2 remediation item ODD-03 has been implemented, hardened, and merged into `master` (branch `bugfix/oddities-phase2-remediation`, commits [`c98bff81`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/java/se/lublin/humla/net/HumlaUDP.java#L60-L75) and [`cc0f9260`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/java/se/lublin/humla/net/HumlaUDP.java), merge commit [`2b0cfd5d`](file:///home/bualy/files/devel/mumla_dev/mumla-oled)), with dynamic packet duration queue scaling added in branch `feature/dynamic-udp-send-queue` (commits [`3dc5f152`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/java/se/lublin/humla/net/HumlaUDP.java) and [`c7e2bab4`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/java/se/lublin/humla/net/HumlaConnection.java), merge commit [`571f15a0`](file:///home/bualy/files/devel/mumla_dev/mumla-oled)). Verified with comprehensive unit test coverage in [`HumlaUDPSendQueueTest.java`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/test/java/se/lublin/humla/net/HumlaUDPSendQueueTest.java).

### 2.1 Bound Outgoing UDP Send Queue & Enforce Drop Policy (ODD-03) — RESOLVED

**Status**: Resolved on `master` in commits [`c98bff81`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/java/se/lublin/humla/net/HumlaUDP.java#L60-L75) and [`cc0f9260`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/java/se/lublin/humla/net/HumlaUDP.java) (branch `bugfix/oddities-phase2-remediation`), dynamically scaled in commit [`3dc5f152`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/java/se/lublin/humla/net/HumlaUDP.java) (branch `feature/dynamic-udp-send-queue`).

**Component**: [`HumlaUDP.java`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/java/se/lublin/humla/net/HumlaUDP.java#L60-L75), [`HumlaUDP.java:186-202`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/java/se/lublin/humla/net/HumlaUDP.java#L186-L202)

**Problem**:
1. [`HumlaUDP.java:74`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/java/se/lublin/humla/net/HumlaUDP.java#L74) instantiates `mSendQueue` as an unbounded `new LinkedBlockingQueue<>()`.
2. Voice packets are appended at 50–100 packets/sec during transmission.
3. When cellular connectivity stalls (e.g. transit tunnels, cell tower handover), the queue accumulates unbounded packets.
4. Voice data is real-time and perishable. Upon network recovery, blasting hundreds of stale packets wastes cellular bandwidth, overflows server jitter buffers, and creates confusing voice playback bursts. Upstream Mumble transmits datagrams immediately via non-blocking socket writes ([`ServerHandler.cpp:349`](file:///home/bualy/files/devel/mumla_dev/mumble/src/mumble/ServerHandler.cpp#L349)) without unbounded queueing.

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

## Phase 3: UI Lifecycle, Input State & Dialog Correctness (P2)

Phase 3 resolves UX annoyances, preference state divergence, and overlay rotation inconsistencies.

### 3.1 Fix First Run Certificate Dialog Outside Touch & Dismissal (ODD-06)

**Status**: Open

**Component**: [`MumlaActivity.java`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/main/java/se/lublin/mumla/app/MumlaActivity.java#L481-L503)

**Problem**:
1. [`showFirstRunGuide()`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/main/java/se/lublin/mumla/app/MumlaActivity.java#L481) creates an `AlertDialog` with only a positive button (`R.string.generate`).
2. The dialog is cancelable by default. If the user touches outside or presses Back:
   - The dialog dismisses silently.
   - `mSettings.setFirstRun(false)` is **never executed**.
   - [`StartupAction`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/main/java/se/lublin/mumla/app/MumlaActivity.java#L372) is skipped because it is located in the `else` branch of `if (mSettings.isFirstRun())`.
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

### 3.2 Harmonize PTT Keycode Reset Sentinel (-1 vs 0) (ODD-07)

**Status**: Open

**Component**: [`Settings.java`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/main/java/se/lublin/mumla/Settings.java#L59), [`KeySelectPreferenceDialogFragment.java`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/main/java/se/lublin/mumla/preference/KeySelectPreferenceDialogFragment.java#L33-L58), [`MumlaActivity.java`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/main/java/se/lublin/mumla/app/MumlaActivity.java#L451)

**Problem**:
1. [`Settings.java:59`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/main/java/se/lublin/mumla/Settings.java#L59) defines `DEFAULT_PUSH_KEY = -1`.
2. In [`KeySelectPreferenceDialogFragment.java:35`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/main/java/se/lublin/mumla/preference/KeySelectPreferenceDialogFragment.java#L35), clicking "Reset Key" sets `mCurrentValue = 0` and writes `0` to preferences. Line 56 falls back to `0`.
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
   Add a defensive guard ensuring unconfigured keycodes cannot match in both `onKeyDown()` ([`MumlaActivity.java:451`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/main/java/se/lublin/mumla/app/MumlaActivity.java#L451)) and `onKeyUp()` ([`MumlaActivity.java:460`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/main/java/se/lublin/mumla/app/MumlaActivity.java#L460)):
   ```java
   int pttKey = mSettings.getPushToTalkKey();
   if (mService != null && pttKey > 0 && keyCode == pttKey) {
       mService.onTalkKeyDown(); // or onTalkKeyUp() in onKeyUp()
       return true;
   }
   ```

---

### 3.3 Refresh Hot Corner Gesture Exclusion Rects on Configuration Change (ODD-05)

**Status**: Open

**Component**: [`MumlaService.java`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/main/java/se/lublin/mumla/service/MumlaService.java#L632-L637), [`MumlaHotCorner.java`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/main/java/se/lublin/mumla/service/MumlaHotCorner.java#L65-L82)

**Problem**:
1. In [`MumlaService.onConfigurationChanged()`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/main/java/se/lublin/mumla/service/MumlaService.java#L632), `mChannelOverlay.updatePosition()` is invoked, but `mHotCorner` is completely ignored.
2. In [`MumlaHotCorner.addOnLayoutChangeListener()`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/main/java/se/lublin/mumla/service/MumlaHotCorner.java#L65), `setSystemGestureExclusionRects()` is conditioned on `(width != mLastWidth || height != mLastHeight)`.
3. Because [`ptt_corner.xml`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/main/res/layout/ptt_corner.xml) is fixed at 48dp × 48dp, rotating between portrait and landscape preserves width and height. The condition evaluates to `false`, skipping `setSystemGestureExclusionRects()`.
4. On Android 10+ (Q+), system gesture exclusion rects are cleared or invalidated upon display rotation. As a result, the hot corner loses its exclusion zone after rotation and becomes intercepted by Android's system back-gesture.

**Solution**:
1. Add an explicit `refreshGestureExclusion()` method on [`MumlaHotCorner`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/main/java/se/lublin/mumla/service/MumlaHotCorner.java) that reapplies exclusion rects without checking dimensions:
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
2. In [`MumlaService.onConfigurationChanged()`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/main/java/se/lublin/mumla/service/MumlaService.java#L632), refresh both overlays:
   ```java
   @Override
   public void onConfigurationChanged(Configuration newConfig) {
       super.onConfigurationChanged(newConfig);
       if (mChannelOverlay != null && mChannelOverlay.isShown()) {
           mChannelOverlay.updatePosition();
       }
       if (mHotCorner != null && mHotCorner.isShown()) {
           mHotCorner.refreshGestureExclusion();
       }
   }
   ```

---

## Phase 4: Modernization & Code Hygiene (P3)

Phase 4 updates legacy Android platform APIs and prunes dead code baggage.

### 4.1 Modernize Status & Navigation Bar Insets in Overlay HUD (ODD-04)

**Status**: Open

**Component**: [`MumlaOverlay.java`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/main/java/se/lublin/mumla/service/MumlaOverlay.java#L263-L285), [`Settings.java`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/main/java/se/lublin/mumla/Settings.java#L477-L489)

**Problem**:
1. [`MumlaOverlay.java:265, 277`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/main/java/se/lublin/mumla/service/MumlaOverlay.java#L265) uses `Resources.getIdentifier()` to query system dimensions (`"status_bar_height"`, `"navigation_bar_height"`). This approach is deprecated, fragile across OEM skins, and ignorant of modern display cutouts, camera punch-holes, and gesture navigation bars.
2. In [`Settings.java:477-489`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/main/java/se/lublin/mumla/Settings.java#L477-L489), pinned overlay gravities return `Gravity.LEFT` / `Gravity.RIGHT` instead of `Gravity.START` / `Gravity.END`, preventing proper right-to-left (RTL) locale layout mirroring.

**Solution**:
1. For Android 11+ (API 30+), query `WindowMetrics` and `WindowInsets` for both top and bottom margins:
   ```java
   private int getTopMargin(DisplayMetrics dm) {
       if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
           WindowMetrics metrics = mWindowManager.getCurrentWindowMetrics();
           android.graphics.Insets insets = metrics.getWindowInsets().getInsetsIgnoringVisibility(
                   WindowInsets.Type.statusBars() | WindowInsets.Type.displayCutout());
           return insets.top + (int) (8 * dm.density);
       }
       // Fallback for API < 30
       int statusBarHeight = 0;
       int resourceId = mService.getResources().getIdentifier("status_bar_height", "dimen", "android");
       if (resourceId > 0) {
           statusBarHeight = mService.getResources().getDimensionPixelSize(resourceId);
       }
       return statusBarHeight > 0 ? statusBarHeight + (int) (8 * dm.density) : (int) (40 * dm.density);
   }

   private int getBottomMargin(DisplayMetrics dm) {
       if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
           WindowMetrics metrics = mWindowManager.getCurrentWindowMetrics();
           android.graphics.Insets insets = metrics.getWindowInsets().getInsetsIgnoringVisibility(
                   WindowInsets.Type.navigationBars());
           return insets.bottom + (int) (8 * dm.density);
       }
       // Fallback for API < 30
       int navBarHeight = 0;
       int resourceId = mService.getResources().getIdentifier("navigation_bar_height", "dimen", "android");
       if (resourceId > 0) {
           navBarHeight = mService.getResources().getDimensionPixelSize(resourceId);
       }
       return navBarHeight > 0 ? navBarHeight + (int) (8 * dm.density) : (int) (56 * dm.density);
   }
   ```
2. Update [`Settings.getOverlayGravity()`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/main/java/se/lublin/mumla/Settings.java#L477) to return `Gravity.START` and `Gravity.END`.

---

### 4.2 Remove Dead Commented-Out XML Preferences (ODD-08)

**Status**: Open

**Component**: [`settings_appearance.xml`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/main/res/xml/settings_appearance.xml#L74-L94)

**Problem**:
Obsolete XML preferences (`channellistrowheight`, `colorizechannellist`, `colorthresholdnumusers`) remain commented out in [`settings_appearance.xml:74-94`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/main/res/xml/settings_appearance.xml#L74-L94). The referenced resources (`@array/rowheightText`, `@string/rowheight`, etc.) do not exist in the project, causing confusion during codebase exploration.

**Solution**:
Prune lines 74–94 from [`settings_appearance.xml`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/main/res/xml/settings_appearance.xml#L74-L94).

---

## Phase 5: Dynamic Bandwidth & Network Adaptation (P2)

Phase 5 addresses secondary transport feedback loops and real-time buffer adaptation under server-enforced bandwidth constraints.

### 5.1 Scale HumlaUDP Send Queue on Bandwidth Throttling (ODD-09)

**Status**: Open

**Component**: [`AudioHandler.java`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/java/se/lublin/humla/protocol/AudioHandler.java#L266-L272), [`HumlaConnection.java`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/java/se/lublin/humla/net/HumlaConnection.java#L441), [`HumlaService.java`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/java/se/lublin/humla/HumlaService.java#L685)

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

## Verification & Test Strategy

To ensure zero regressions across all phases, each change must be accompanied by targeted unit and integration tests:

| Phase | Item | Automated Verification | Manual / Device Check |
|---|---|---|---|
| **Phase 1** | **ODD-01** | [`ModelHandlerUserRemoveTest.java`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/test/java/se/lublin/humla/protocol/ModelHandlerUserRemoveTest.java) verifying `mUsers.get(session) == null` after `messageUserRemove`. | Connect to test server, have a remote user join and leave; inspect heap via Android Profiler. |
| **Phase 1** | **ODD-02** | [`HumlaUDPReceiveThreadTest.java`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/test/java/se/lublin/humla/net/HumlaUDPReceiveThreadTest.java) verifying `onUDPDataReceived` is invoked on the UDP receive thread, not `Looper.getMainLooper()`. | High-rate voice chatter benchmark (150 packets/sec); measure UI thread frame times (`gfxinfo`) ensuring zero dropped frames. |
| **Phase 2** | **ODD-03** | [`HumlaUDPSendQueueTest.java`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/test/java/se/lublin/humla/net/HumlaUDPSendQueueTest.java) verifying queue bounds to capacity, dynamic packet duration scaling, and dropping oldest packets on stall. | Throttle connection to 0 kbps for 5 seconds while holding PTT; unthrottle and observe server incoming packet rate. |
| **Phase 3** | **ODD-06** | Robolectric test in `MumlaActivityTest.java` simulating outside touch dismissal and verifying `isFirstRun() == false`. | Fresh install; tap outside first-run certificate dialog; force stop and relaunch to verify dialog does not reappear. |
| **Phase 3** | **ODD-07** | Unit test in `SettingsTest.java` verifying `getPushToTalkKey()` returns `-1` before and after reset; verify `KEYCODE_UNKNOWN` (`0`) does not trigger PTT. | Open PTT key preference, click "Reset Key", verify "None" is displayed and key events with `keyCode=0` are ignored. |
| **Phase 3** | **ODD-05** | Service unit test verifying `mHotCorner.refreshGestureExclusion()` is called in `onConfigurationChanged()`. | Enable hot corner on Android 10+ device; rotate screen; perform edge back gesture over hot corner to verify exclusion is active. |
| **Phase 4** | **ODD-04** | Overlay insets unit test comparing modern `WindowMetrics` against legacy fallback. | Test overlay positioning on punch-hole and notch devices in portrait and landscape. |
| **Phase 4** | **ODD-08** | Gradle build and resource compilation check (`assembleFossDebug`). | Verify settings appearance screen loads and renders without XML inflation warnings. |
| **Phase 5** | **ODD-09** | Unit test verifying `setMaxBandwidth` invokes `setTargetFramesPerPacket` and shrinks `HumlaUDP` queue to 5 packets. | Connect to bandwidth-limited server (32 kbps); verify send queue capacity shrinks dynamically from 10 to 5. |
