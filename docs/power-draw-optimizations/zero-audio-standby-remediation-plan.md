# Remediation Plan: Zero-Audio Standby Regression & Silence Shield Restoration

An engineering implementation plan to eliminate the screen-off connection drop regression in **Mumla OLED 0.21.10** by fully restoring the proven **0.21.7 continuous silence shield baseline** alongside targeted transport hardening. This document builds upon the empirical evidence and architectural analysis in [`zero-audio-standby-regression-investigation.md`](zero-audio-standby-regression-investigation.md) and defines a surgical, phased remediation roadmap vetted against Android power management and upstream Mumble protocol invariants.

---

## Table of Contents

1. [Executive Summary & Architectural Invariants](#1-executive-summary--architectural-invariants)
   - [A. The Impossible Triad: Failure Mode Retrospective](#a-the-impossible-triad-failure-mode-retrospective)
   - [B. Remediation Strategy & Scope Boundary](#b-remediation-strategy--scope-boundary)
2. [Surgical Demolition vs. Optimization Preservation](#2-surgical-demolition-vs-optimization-preservation)
   - [A. Components Slated for Removal / Reversion](#a-components-slated-for-removal--reversion)
   - [B. Non-Regressive Optimizations to Retain](#b-non-regressive-optimizations-to-retain)
3. [Step-by-Step Implementation Blueprint](#3-step-by-step-implementation-blueprint)
   - [Step 1: Network Transport Hardening (`HumlaTCP.java`)](#step-1-network-transport-hardening-humlatcpjava)
   - [Step 2: Audio Pipeline Silence Restoration (`AudioOutput.java`)](#step-2-audio-pipeline-silence-restoration-audiooutputjava)
   - [Step 3: Service Wakelock & Keepalive Restoration (`HumlaService.java` & `HumlaConnection.java`)](#step-3-service-wakelock--keepalive-restoration-humlaservicejava--humlaconnectionjava)
   - [Step 4: Model Simplification & Manifest Cleanup](#step-4-model-simplification--manifest-cleanup)
   - [Step 5: Test Suite Retirement & Maintenance](#step-5-test-suite-retirement--maintenance)
4. [Atomic Commit Sequence & Worktree Roadmap](#4-atomic-commit-sequence--worktree-roadmap)
5. [Verification & Hardware Validation Protocol](#5-verification--hardware-validation-protocol)

---

## 1. Executive Summary & Architectural Invariants

### A. The Impossible Triad: Failure Mode Retrospective

The power optimization initiative in Mumla OLED surfaced an inescapable architectural trilemma governing background operation on modern mobile hardware:

| Architecture Baseline | `PARTIAL_WAKE_LOCK` State | `AudioTrack` Playback State | Murmur TCP Keepalive (30s) | OEM Watchdog (`SIGKILL`) | Power Footprint | Real-World Operational Outcome |
|---|---|---|---|---|---|---|
| **Mumla OLED 0.21.7 (Continuous Silence)** | Held continuously (24/7) | `PLAYSTATE_PLAYING` (Digital silence / zero PCM) | **Sustained** (In-memory loop pings Murmur every 5s) | **Immune** (Continuous silence shield active) | High ($35\text{--}60\text{ mA}$ AP + $15\text{--}30\text{ mW}$ Audio DSP) | **100% Stable Connection**: No disconnects, no process kills; burns power rendering silence. |
| **Mumla OLED 0.21.9 (Hardware Audio Gating)** | Held continuously (24/7) | `PLAYSTATE_PAUSED` (Standby pause after 3s/15s silence) | **Sustained** (In-memory loop pings Murmur every 5–10s) | **FATAL FAILURE (`SIGKILL`)**: Watchdog detects wakelock held without active audio | Moderate ($35\text{--}60\text{ mA}$ AP + $0\text{ mW}$ Audio DSP) | **Process Killed by OS**: Samsung Device Care, Xiaomi MIUI/HyperOS, and Vivo PEM kill app with `SIGKILL`. |
| **Mumla OLED 0.21.10 (Zero-Audio Standby Lite)** | Released in Zero-Audio Standby | `PLAYSTATE_PAUSED` (Standby pause enabled) | **FATAL FAILURE (TIMEOUT)**: Alarms deferred 37s–104s; Murmur drops socket | **Immune** (No wakelock held while audio is paused) | Low ($5\text{--}10\text{ mA}$ AP theoretical + $0\text{ mW}$ Audio DSP) | **Connection Dropped**: Murmur detects 30s inactivity and terminates session (`EOFException`). |

* **The 0.21.9 Failure**: When `AudioTrack` is paused while `PARTIAL_WAKE_LOCK` remains active, proprietary battery watchdogs (Samsung Device Care, Xiaomi MIUI/HyperOS, Vivo PEM, Huawei EMUI) classify the app as an audio-less wakelock abuser and terminate the process via `SIGKILL`.
* **The 0.21.10 Failure**: When `PARTIAL_WAKE_LOCK` is released to avoid `SIGKILL`, the Linux kernel enters `suspend-to-RAM`. Standard `AlarmManager.setExact(ELAPSED_REALTIME_WAKEUP)` alarms are deferred past 30 seconds by AOSP and OEM power managers, while `setExactAndAllowWhileIdle()` is clamped by AOSP to $T_{\text{while-idle-min}} = 60\text{ s} > 30\text{ s}$. Keepalive pings cease, Murmur's 30-second inactivity watchdog triggers, and Murmur drops the socket (`EOFException`).
* **The Inescapable Conclusion**: Modern Android provides no platform mechanism for an unheld Application Processor to wake up every 10 seconds while asleep on battery. The 0.21.7 "silence shield" masquerade (playing digital silence through `AudioTrack` while holding a continuous `PARTIAL_WAKE_LOCK`) is the only state that simultaneously satisfies Murmur's 30-second keepalive invariant and OEM watchdogs' heuristics.

### B. Remediation Strategy & Scope Boundary

The remediation plan executes a complete restoration of the **0.21.7 silence shield baseline**, complemented by targeted **transport layer hardening**:
1. **Restore Perpetual AudioTrack Silence**: Keep `AudioTrack` continuously in `PLAYSTATE_PLAYING`. When no remote voice packets are being decoded, the audio rendering thread sleeps on an internal monitor while `AudioFlinger` writes zero-PCM frames, maintaining the silence shield.
2. **Restore Monolithic Service Wakelock**: Keep `PowerManager.PARTIAL_WAKE_LOCK` acquired continuously throughout the connection in `HumlaService`.
3. **Restore Native In-Memory Keepalives**: Retain the adaptive synchronized keepalive loop in `HumlaConnection` driven entirely by `ScheduledExecutorService`.
4. **Transport Layer Hardening**: Add explicit `mDataOutput.flush()` calls to `HumlaTCP.sendMessage()` to guarantee TLS records leave user-space buffers immediately, and enable `setTcpNoDelay(true)` on `mTCPSocket`.
5. **Dismantle Standby Infrastructure**: Fully remove the `AlarmManager` pulsed keepalive engine, the zero-audio topology evaluator in `ModelHandler`, and the high-risk `SCHEDULE_EXACT_ALARM` Android permission.

---

## 2. Surgical Demolition vs. Optimization Preservation

A critical directive of this remediation is **selective demolition**. Blanket reversion to 0.21.7 would destroy dozens of valid, battle-tested optimizations and bug fixes. The boundary between what must be purged and what must be preserved is strictly defined:

### A. Components Slated for Removal / Reversion

1. **`AudioOutput.java` Standby Pause**:
   - Revert route-aware standby pause timing (`STANDBY_TIMEOUT_DEFAULT_MS = 3000L`, `STANDBY_TIMEOUT_A2DP_MS = 15000L`, `getStandbyTimeoutMs()`).
   - Remove `mStandbyPauseEnabled`, `isStandbyPauseEnabled()`, and `setStandbyPauseEnabled()`.
   - Remove calls to `mAudioTrack.pause()` in the idle rendering loop.
2. **`HumlaService.java` Alarm & Standby Engine**:
   - Remove `mZeroAudioStandby` flag and `isZeroAudioStandby()`.
   - Remove `ACTION_KEEPALIVE_ALARM`, `mAlarmManager`, `mKeepalivePendingIntent`, `mKeepaliveReceiver`, `mKeepaliveWakeLock`, `mKeepaliveDrainRunnable`, and `mIdleModeReceiver`.
   - Remove `enterZeroAudioStandby()`, `exitZeroAudioStandby()`, `updateStandbyState()`, `isPlausiblyZeroAudio()`, `scheduleKeepaliveAlarm()`, and `cancelKeepaliveAlarm()`.
   - Remove `isDeviceIdleMode()`, `isIgnoringBatteryOptimizations()`, `canScheduleExactAlarms()`, `isTransmitModeStandbyEligible()`, and `shouldExitStandbyOnStateChange()`.
   - Remove `onIncomingAudioPacket()` callback implementation and unhook `mModelHandler.setOnPlausibleZeroAudioListener()`.
3. **`HumlaConnection.java` Standby Suspension**:
   - Remove `setSuspendedStandbyMode(boolean)` and `mSuspendedStandbyMode`.
   - Remove `onIncomingAudioPacket()` callback definition from `HumlaConnectionListener` and invocation in UDP packet routing.
4. **`ModelHandler.java` Standby Evaluator**:
   - Remove `isPlausiblyZeroAudio()`, `OnPlausibleZeroAudioListener`, `mZeroAudioListener`, and `checkZeroAudioState()`.
5. **Permissions & Manifests**:
   - Remove `<uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" />` from both `app/src/main/AndroidManifest.xml` and `libraries/humla/src/main/AndroidManifest.xml`.
6. **Obsolete Unit Tests**:
   - Remove `HumlaStandbyTest.java` and `ModelHandlerZeroAudioTest.java`.
   - Update `AudioOutputStandbyTest.java` to purge standby pause tests while keeping voice engine checks.

### B. Non-Regressive Optimizations to Retain

All optimizations that reduce power consumption or improve correctness without compromising the silence shield or triggering watchdog kills must be preserved:

1. **Microphone Capture Gating on Mute / PTT Idle (`AudioInput.java`, `AudioInputEngine.cpp`)**:
   - Stopping `AudioRecord` and powering down the ADC/DSP during mute or PTT release saves $58\text{--}96\text{ mW}$.
   - **Watchdog Immunity**: Proprietary OEM watchdogs only inspect `AudioTrack` playback state when auditing wakelocks. Halting `AudioRecord` capture has zero impact on watchdog heuristics and avoids unwanted Android privacy indicator indicators.
2. **Phase 1 Audio Engine Optimizations**:
   - Opus encoder complexity set to 6 (reduced from 10, saving $\sim 47\text{ mW}$).
   - Pre-RNNoise squelch gating (bypassing GRU inference on dead silence, saving $\sim 90\text{ mW}$).
   - VAD hysteresis tuning and lookahead buffer gating.
   - 50 Hz render spin elimination: Indefinite `mInactiveLock.wait()` in `AudioOutput` when zero voices exist in native engine (keeps AP cores in C2/C3 without pausing `AudioTrack`).
3. **Adaptive Synchronized Keepalives (`HumlaConnection.java`, `AdaptiveKeepaliveTest.java`)**:
   - Synchronized UDP and TCP keepalive dispatch in a single scheduled tick.
   - 5s bootstrap window (elapsed $< 30\text{ s}$ or $\le 3$ crypt packets) transitioning to 10s steady state.
   - Saves modem wakeups and allows baseband RRC timers to expire while preserving a 3x retry margin against Murmur's 30s timeout.
4. **Model Hardening & Protocol Correctness (`Channel.java`, `User.java`, `ChannelLinksTest.java`)**:
   - `Channel.getAllLinks()` depth-first transitive linked channel resolution.
   - Forward stub hierarchy resolution for out-of-order `ChannelState` protobuf messages.
   - `User.java` contract fixes: `hashCode` aligned with session `equals`, null-safe `compareTo`, and `CopyOnWriteArraySet` for listening channels.
5. **UI & Memory Optimizations**:
   - Avatar LRU cache and circular drawable caching in channel list views.
   - Native OCB2-AES optimizations and heap allocation reduction.

---

## 3. Step-by-Step Implementation Blueprint

### Step 1: Network Transport Hardening (`HumlaTCP.java`)

In [`libraries/humla/src/main/java/se/lublin/humla/net/HumlaTCP.java`](../../libraries/humla/src/main/java/se/lublin/humla/net/HumlaTCP.java):

1. **Flush Conscrypt SSL Output Stream**:
   In both `sendMessage(MessageLite, HumlaTCPMessageType)` and `sendMessage(byte[], int, HumlaTCPMessageType)`, call `mDataOutput.flush()` immediately after writing:
   ```java
   mDataOutput.writeShort(messageType.ordinal());
   mDataOutput.writeInt(message.getSerializedSize());
   message.writeTo(mDataOutput);
   mDataOutput.flush(); // Guarantee TLS records leave user-space buffers
   ```
2. **Enable TCP NoDelay**:
   In `connect()` on the background connection thread, configure `mTCPSocket.setTcpNoDelay(true)`:
   ```java
   mTCPSocket.setKeepAlive(true);
   mTCPSocket.setTcpNoDelay(true); // Disable Nagle's algorithm for control pings
   mTCPSocket.setSoTimeout(CONNECT_TIMEOUT);
   mTCPSocket.startHandshake();
   ```

### Step 2: Audio Pipeline Silence Restoration (`AudioOutput.java`)

In [`libraries/humla/src/main/java/se/lublin/humla/audio/AudioOutput.java`](../../libraries/humla/src/main/java/se/lublin/humla/audio/AudioOutput.java):

1. **Remove Standby Pause State**:
   Remove fields `STANDBY_TIMEOUT_DEFAULT_MS`, `STANDBY_TIMEOUT_A2DP_MS`, `mStandbyPauseEnabled`, and methods `getStandbyTimeoutMs()`, `isStandbyPauseEnabled()`, `setStandbyPauseEnabled()`.
2. **Restore Indefinite Idle Wait**:
   Replace the two-tier standby pause block in `run()` with the Phase 1 stateful render wait (from commit `0470963c`):
   ```java
   synchronized (mInactiveLock) {
       if (!mHasIncomingAudio) {
           engine = mEngine;
           boolean hasVoices = (engine != null && engine.hasActiveVoices());
           if (!hasVoices) {
               // When nobody is speaking and zero voices exist in native engine,
               // sleep indefinitely. AudioTrack remains in PLAYSTATE_PLAYING,
               // rendering zero-PCM digital silence to maintain the silence shield.
               while (mRunning && !mHasIncomingAudio) {
                   try {
                       mInactiveLock.wait();
                   } catch (InterruptedException e) {
                       Thread.currentThread().interrupt();
                       break renderLoop;
                   }
                   engine = mEngine;
                   if (engine != null && engine.hasActiveVoices()) {
                       break;
                   }
               }
           } else {
               try {
                   mInactiveLock.wait(20);
               } catch (InterruptedException e) {
                   Thread.currentThread().interrupt();
                   break renderLoop;
               }
           }
       }
       mHasIncomingAudio = false;
   }
   ```
3. **Invariant**: `AudioTrack` transitions to `PLAYSTATE_PLAYING` once upon start and is never paused during an active connection.

### Step 3: Service Wakelock & Keepalive Restoration (`HumlaService.java` & `HumlaConnection.java`)

#### In `HumlaService.java`:
1. **Acquire & Hold Monolithic Wakelock**:
   In `onConnectionSynchronized()`, acquire `mWakeLock` indefinitely. The lock is released only in `disconnect()`, `onDestroy()`, or on unrecoverable disconnect in `onConnectionDisconnected()`.
2. **Purge Keepalive Alarm Engine**:
   - Delete `mAlarmManager`, `mKeepalivePendingIntent`, `mKeepaliveReceiver`, `mKeepaliveWakeLock`, `mKeepaliveDrainRunnable`, `mIdleModeReceiver`, `ACTION_KEEPALIVE_ALARM`.
   - In `onCreate()`, remove alarm initialization, receiver registration, and idle mode receivers.
   - In `onDestroy()`, remove alarm cancellation and unregistration blocks.
   - Delete `scheduleKeepaliveAlarm()`, `cancelKeepaliveAlarm()`, `enterZeroAudioStandby()`, `exitZeroAudioStandby()`, `updateStandbyState()`, `isPlausiblyZeroAudio()`, `isDeviceIdleMode()`, `isIgnoringBatteryOptimizations()`, `canScheduleExactAlarms()`, `isTransmitModeStandbyEligible()`, and `shouldExitStandbyOnStateChange()`.
   - Remove `onIncomingAudioPacket()` from `HumlaService`.
   - Remove `mModelHandler.setOnPlausibleZeroAudioListener()` wiring.

#### In `HumlaConnection.java`:
1. **Restore Continuous Keepalive Loop**:
   - Remove `setSuspendedStandbyMode(boolean)` and `isSuspendedStandbyMode()`.
   - In `scheduleNextPing(int delaySeconds)`, remove `mSuspendedStandbyMode` checks.
   - In `mPingRunnable`, remove `mSuspendedStandbyMode` guards.
2. **Remove Audio Wakeup Callbacks**:
   - In `HumlaConnectionListener`, delete `default void onIncomingAudioPacket() {}`.
   - In `onUDPDataReceived`, remove calls to `mListener.onIncomingAudioPacket()`.

### Step 4: Model Simplification & Manifest Cleanup

1. **`ModelHandler.java`**:
   - Delete `OnPlausibleZeroAudioListener` interface, `mZeroAudioListener`, `setOnPlausibleZeroAudioListener()`, `checkZeroAudioState()`, and `isPlausiblyZeroAudio()`.
   - Remove calls to `checkZeroAudioState()` from `clear()`, `messageChannelState()`, `messageChannelRemove()`, `messageUserState()`, `messageUserRemove()`, and `messageServerSync()`.
2. **`AndroidManifest.xml`**:
   - Remove `<uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" />` from:
     - `app/src/main/AndroidManifest.xml`
     - `libraries/humla/src/main/AndroidManifest.xml`

### Step 5: Test Suite Retirement & Maintenance

1. **Delete Obsolete Standby Tests**:
   - Delete `libraries/humla/src/test/java/se/lublin/humla/HumlaStandbyTest.java`.
   - Delete `libraries/humla/src/test/java/se/lublin/humla/protocol/ModelHandlerZeroAudioTest.java`.
2. **Update `AudioOutputStandbyTest.java`**:
   - Rename class to `AudioOutputVoiceTest.java` (or strip deleted standby methods).
   - Remove tests for `STANDBY_TIMEOUT_DEFAULT_MS`, `STANDBY_TIMEOUT_A2DP_MS`, and `isStandbyPauseEnabled()`.
   - Retain `testHasActiveVoicesDefault()`.
3. **Preserve Valid Test Coverage**:
   - Retain `libraries/humla/src/test/java/se/lublin/humla/model/ChannelLinksTest.java`.
   - Retain `libraries/humla/src/test/java/se/lublin/humla/net/AdaptiveKeepaliveTest.java`.
   - Retain all C++ and JNI unit tests (`test_audio_input_engine.cpp`).

---

## 4. Atomic Commit Sequence & Worktree Roadmap

All development must be conducted in a dedicated Git worktree created via `./scripts/worktree.py add bugfix/restore-silence-shield master`. Code changes must be organized into single logical commits using `python3 scripts/commit.py -m "<subject>"`:

### Commit 1: `net: harden HumlaTCP socket and flush Conscrypt streams`
* **Scope**: `libraries/humla/src/main/java/se/lublin/humla/net/HumlaTCP.java`
* **Changes**: Add `mDataOutput.flush()` to both `sendMessage()` overloads; add `mTCPSocket.setTcpNoDelay(true)`.
* **Description**: Ensure TLS records leave user space immediately and disable Nagle's algorithm for control messages.

### Commit 2: `audio: restore continuous AudioTrack silence playback`
* **Scope**: `libraries/humla/src/main/java/se/lublin/humla/audio/AudioOutput.java`
* **Changes**: Remove standby pause timing, route detection, and `AudioTrack.pause()`; restore Phase 1 indefinite wait on zero voices while keeping `AudioTrack` playing.
* **Description**: Re-establish the continuous silence shield masquerade to protect the process from OEM watchdog `SIGKILL` termination.

### Commit 3: `humla: restore monolithic wakelock and in-memory keepalives`
* **Scope**:
  - `libraries/humla/src/main/java/se/lublin/humla/HumlaService.java`
  - `libraries/humla/src/main/java/se/lublin/humla/net/HumlaConnection.java`
* **Changes**: Acquire `PARTIAL_WAKE_LOCK` continuously upon synchronization; purge `AlarmManager` pulsed keepalive infrastructure; remove `setSuspendedStandbyMode` and `onIncomingAudioPacket`.
* **Description**: Eliminate screen-off alarm deferrals by maintaining uninterrupted in-memory keepalive execution while connected.

### Commit 4: `humla: purge zero-audio model evaluator and exact alarm permissions`
* **Scope**:
  - `libraries/humla/src/main/java/se/lublin/humla/protocol/ModelHandler.java`
  - `libraries/humla/src/main/java/se/lublin/humla/protocol/AudioHandler.java`
  - `app/src/main/AndroidManifest.xml`
  - `libraries/humla/src/main/AndroidManifest.xml`
  - Test files (`HumlaStandbyTest.java`, `ModelHandlerZeroAudioTest.java`, `AudioOutputStandbyTest.java`)
* **Changes**: Remove `isPlausiblyZeroAudio` evaluator and listeners; remove `SCHEDULE_EXACT_ALARM` permissions; retire obsolete unit tests.
* **Description**: Clean up dead standby evaluation code, reduce manifest permission footprint, and align unit test suite.

---

## 5. Verification & Hardware Validation Protocol

### A. Pre-Completion Verification Gate
Inside `.worktrees/bugfix/restore-silence-shield`:
1. **Fast Unit Tests**:
   ```bash
   nix develop --command ./gradlew testFossDebugUnitTest
   ```
2. **Full Verification Script**:
   ```bash
   ./scripts/check.sh
   ```
   Ensures Spotless formatting, Checkstyle, Detekt, C++ tests, and unit tests pass with zero warnings or errors.

### B. Hardware & Physical Device Validation
1. **Battery Unplug Test (Defeating AC Mode Masking)**:
   Connect device to ADB, unplug USB cable or simulate battery power:
   ```bash
   adb shell dumpsys battery unplug
   ```
2. **Screen-Off Standby Validation (OriginOS / Android 14 / PD2227)**:
   - Connect Mumla OLED to a Murmur server in a quiet channel (`isPlausiblyZeroAudio() == true`).
   - Turn off device screen (`Power` button) and let sit for 10 minutes.
   - Inspect logcat over Wi-Fi (`adb connect <device-ip>:5555; adb logcat -s HumlaService HumlaTCP HumlaConnection`):
     - Verify TCP/UDP keepalives fire reliably every 10 seconds.
     - Verify zero `EOFException` socket drops.
     - Verify zero OEM watchdog kills (`am_kill`, `killProcess`, or `SIGKILL`).
3. **Microphone Mute Verification**:
   - Toggle mute while connected; verify in logcat that `AudioRecord` stops cleanly and audio input engine skips processing.
   - Toggle unmute; verify `AudioRecord` restarts without latency or distortion.
4. **Restore Device Battery State**:
   ```bash
   adb shell dumpsys battery reset
   ```
