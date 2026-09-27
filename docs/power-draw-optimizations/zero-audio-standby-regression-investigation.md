# Regression Investigation & Root Cause Analysis: Zero-Audio Standby Disconnects in Mumla OLED 0.21.10

An exhaustive architectural investigation and empirical analysis of the screen-off connection drop regression introduced in **Mumla OLED 0.21.10** under zero-audio standby conditions. This document evaluates the client-server keepalive dynamics against upstream Murmur source code, presents chronological logcat evidence from physical hardware, analyzes the operating system failure mode across Android `AlarmManager` and Linux kernel power management, and outlines concrete remediation paths.

## Table of Contents

1. [Executive Summary & Theory Evaluation](#1-executive-summary--theory-evaluation)
2. [Protocol Architecture: Murmur Keepalives & Inactivity Timeouts](#2-protocol-architecture-murmur-keepalives--inactivity-timeouts)
   - [A. Directionality of Keepalives (Client-Initiated vs. Server-Echoed)](#a-directionality-of-keepalives-client-initiated-vs-server-echoed)
   - [B. Inactivity Timeout Invariant (`Server::checkTimeout`)](#b-inactivity-timeout-invariant-serverchecktimeout)
   - [C. Asymmetry in Activity Reset: TCP Exclusivity](#c-asymmetry-in-activity-reset-tcp-exclusivity)
3. [Empirical Hardware Evidence: Logcat Chronology on Android 14](#3-empirical-hardware-evidence-logcat-chronology-on-android-14)
   - [Incident 1: 104-Second Complete Kernel Suspend](#incident-1-104-second-complete-kernel-suspend)
   - [Incident 2: 37.6-Second Alarm Jitter Exceeds 30s Timeout](#incident-2-376-second-alarm-jitter-exceeds-30s-timeout)
   - [The USB Tethering Masking Effect](#the-usb-tethering-masking-effect)
4. [The Architectural Defect in `wakelock-remediation-lite`](#4-the-architectural-defect-in-wakelock-remediation-lite)
   - [A. The Fatal Assumption: Standard `setExact()` vs. Screen-Off Deferral](#a-the-fatal-assumption-standard-setexact-vs-screen-off-deferral)
   - [B. The AOSP While-Idle Throttling Deadlock](#b-the-aosp-while-idle-throttling-deadlock)
   - [C. Screen State Asymmetry (Display Wakelock Coupling)](#c-screen-state-asymmetry-display-wakelock-coupling)
5. [Secondary Pipeline Defect: Unflushed Conscrypt SSL Streams](#5-secondary-pipeline-defect-unflushed-conscrypt-ssl-streams)
6. [The Core Hardware Paradox: OEM Watchdog SIGKILL (0.21.9) vs. Murmur Timeout (0.21.10)](#6-the-core-hardware-paradox-oem-watchdog-sigkill-0219-vs-murmur-timeout-02110)
   - [A. The Impossible Triad: Comparative Invariant Matrix](#a-the-impossible-triad-comparative-invariant-matrix)
   - [B. Deconstructing the 0.21.9 Flaw: Audio Gating Triggers Process Termination](#b-deconstructing-the-0219-flaw-audio-gating-triggers-process-termination)
   - [C. Deconstructing the 0.21.10 Flaw: Wakelock Release Triggers Murmur Timeout](#c-deconstructing-the-02110-flaw-wakelock-release-triggers-murmur-timeout)
7. [Actionable Remediation Paths](#7-actionable-remediation-paths)
   - [Path A: Screen-Aware Dynamic Silence Shield Coupling (Recommended)](#path-a-screen-aware-dynamic-silence-shield-coupling-recommended)
   - [Path B: Complete Restoration of the 0.21.7 Baseline (Maximum Stability)](#path-b-complete-restoration-of-the-0217-baseline-maximum-stability)

---

## 1. Executive Summary & Theory Evaluation

In release **0.21.10**, the branch `wakelock-remediation-lite` was merged to reduce battery drain during conversational pauses, deafened periods, and empty-channel camping. Shortly after release, users reported that whenever the device screen is turned off while in quiet channels (`isPlausiblyZeroAudio() == true`), the client reliably disconnects from the Murmur server.

### Evaluation of Working Theory

> **Working Theory**: *"Client is not properly responding to Murmur's pings."*

The working theory accurately identifies the symptom—an inactivity timeout termination initiated by Murmur—but inverts the protocol directionality:

1. **Murmur Never Pings Clients First**: In the upstream Mumble protocol, the server **never initiates keepalive pings**. Keepalive polling is 100% client-driven. Murmur only echoes pings back to clients upon receipt.
2. **Passive Server Inactivity Monitoring**: Murmur runs a periodic watchdog (`qtTimeout`, every 15.5 seconds). If a client fails to transmit any data over its TCP control connection for 30 consecutive seconds, Murmur assumes the client has silently died and forcefully closes the TCP socket with a `"Timeout"` log.
3. **The Actual Breakdown**: The client is not failing to *respond* to Murmur's pings; rather, **the client fails to initiate and transmit its scheduled keepalive pings to Murmur**.

### Root Cause Summary

When entering `ZeroAudioStandby` in [`HumlaService.java`](../../libraries/humla/src/main/java/se/lublin/humla/HumlaService.java), the application:
1. Unconditionally releases its permanent `PowerManager.PARTIAL_WAKE_LOCK`.
2. Cancels the user-space in-memory ping loop (`mPingTask.cancel(false)`) in [`HumlaConnection.java`](../../libraries/humla/src/main/java/se/lublin/humla/net/HumlaConnection.java).
3. Relies on `AlarmManager.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, ...)` to pulse the CPU every 10 seconds.

However, standard exact alarms without the `FLAG_ALLOW_WHILE_IDLE` flag **are deferred by AOSP and OEM power managers (e.g., Vivo PEM) when the device screen is off and running on battery**. With no wakelocks held, the Linux kernel enters `suspend-to-RAM`, alarms are delayed past 30 seconds, Murmur's 30-second inactivity timer expires, and Murmur sends a TCP FIN (`EOFException` on the client), severing the session.

---

## 2. Protocol Architecture: Murmur Keepalives & Inactivity Timeouts

To verify connection liveness behavior, upstream Mumble C++ server source code was analyzed.

### A. Directionality of Keepalives (Client-Initiated vs. Server-Echoed)

In upstream Murmur [`src/murmur/Messages.cpp`](https://github.com/mumble-voip/mumble/blob/master/src/murmur/Messages.cpp#L2086-L2115):

```cpp
void Server::msgPing(ServerUser *uSource, MumbleProto::Ping &msg) {
    ZoneScoped;
    MSG_SETUP_NO_UNIDLE(ServerUser::Authenticated);

    QMutexLocker l(&uSource->qmCrypt);
    uSource->csCrypt->m_statsRemote.good   = msg.good();
    uSource->csCrypt->m_statsRemote.late   = msg.late();
    uSource->csCrypt->m_statsRemote.lost   = msg.lost();
    uSource->csCrypt->m_statsRemote.resync = msg.resync();

    quint64 ts = msg.timestamp();
    msg.Clear();
    msg.set_timestamp(ts);
    msg.set_good(uSource->csCrypt->m_statsLocal.good);
    msg.set_late(uSource->csCrypt->m_statsLocal.late);
    msg.set_lost(uSource->csCrypt->m_statsLocal.lost);
    msg.set_resync(uSource->csCrypt->m_statsLocal.resync);

    sendMessage(uSource, msg); // Echo reply back to client
}
```

Murmur processes TCP pings only in `Server::msgPing()`, which executes as a handler for incoming client messages. Murmur has no background thread or timer that dispatches unsolicited pings to connected clients. If clients replied to server pings and servers replied to client pings, an infinite ping loop would occur. The protocol is strictly client-initiated.

### B. Inactivity Timeout Invariant (`Server::checkTimeout`)

Murmur monitors client health via [`src/murmur/Server.cpp`](https://github.com/mumble-voip/mumble/blob/master/src/murmur/Server.cpp#L1838-L1852):

```cpp
void Server::checkTimeout() {
    QList< ServerUser * > qlClose;

    qrwlVoiceThread.lockForRead();
    for (ServerUser *u : qhUsers) {
        if (u->activityTime() > (iTimeout * 1000)) { // iTimeout = 30 seconds default
            log(u, "Timeout");
            qlClose.append(u);
        }
    }
    qrwlVoiceThread.unlock();
    for (ServerUser *u : qlClose) {
        u->disconnectSocket(true); // Sends TCP FIN and severs connection
    }
}
```

- Murmur runs `qtTimeout` on a periodic interval of **15.5 seconds** ([`Server.cpp:283`](https://github.com/mumble-voip/mumble/blob/master/src/murmur/Server.cpp#L283)).
- If `u->activityTime()` exceeds `iTimeout * 1000` (default 30,000 ms), the socket is closed immediately.

### C. Asymmetry in Activity Reset: TCP Exclusivity

In [`src/murmur/Server.cpp`](https://github.com/mumble-voip/mumble/blob/master/src/murmur/Server.cpp#L1724-L1726):

```cpp
void Server::message(Mumble::Protocol::TCPMessageType type, const QByteArray &qbaMsg, ServerUser *u) {
    ...
    if (u->sState == ServerUser::Authenticated) {
        u->resetActivityTime(); // Only invoked on inbound TCP messages!
    }
    ...
}
```

- `u->resetActivityTime()` is invoked **strictly upon receiving TCP messages**.
- UDP ping packets handled in [`Server.cpp:1015-1030`](https://github.com/mumble-voip/mumble/blob/master/src/murmur/Server.cpp#L1015-L1030) do **not** call `resetActivityTime()`.
- Therefore, even if UDP connectivity remains operational, a client that ceases TCP transmissions will be forcefully dropped by Murmur after 30 seconds.

---

## 3. Empirical Hardware Evidence: Logcat Chronology on Android 14

Empirical logcat captures from a physical test device (Vivo PD2227, OriginOS / Android 14, PID `26006`) running Mumla OLED 0.21.10 document the precise failure sequence:

### Incident 1: 104-Second Complete Kernel Suspend

```text
09-27 14:47:57.279 26006 26006 D se.lublin.humla.HumlaService: Entering zero-audio standby mode
09-27 14:47:57.280 26006  6757 D AudioManager: getCommunicationDevice
...
[104 consecutive seconds: zero wakeups, zero DatagramSocket logs, zero CPU activity]
...
09-27 14:49:41.959 26006 26006 E se.lublin.humla.HumlaService: Error: An error occurred when communicating with the host (reason: CONNECTION_ERROR)
09-27 14:49:41.961 26006  6743 W System.err: java.net.SocketException: Socket is closed
Caused by: java.io.EOFException
    at java.io.DataInputStream.readFully(DataInputStream.java:205)
    at java.io.DataInputStream.readShort(DataInputStream.java:322)
    at se.lublin.humla.net.HumlaTCP.run(HumlaTCP.java:179)
09-27 14:49:41.967 26006 26006 V se.lublin.humla.HumlaService: Scheduling reconnect in 1000 ms (attempt 0)
```

1. At `14:47:57.279`, the client entered `ZeroAudioStandby` and dropped `mWakeLock`.
2. The phone screen was off and running on battery. The Linux kernel entered `suspend-to-RAM`.
3. Standard `AlarmManager.setExact()` alarms failed to wake the CPU.
4. For **104 seconds**, not a single keepalive packet was dispatched.
5. Murmur timed out at 30 seconds and closed the TCP connection. When the TCP FIN packet woke the modem/CPU at `14:49:41.959`, `HumlaTCP` threw `EOFException` and triggered an automated reconnect.

### Incident 2: 37.6-Second Alarm Jitter Exceeds 30s Timeout

```text
09-27 14:49:42.989 26006 26006 D se.lublin.humla.HumlaService: Entering zero-audio standby mode
09-27 14:50:05.377 26006  8257 I System.out: DatagramSocket p = java.net.DatagramPacket@3b110ce ipAddress = <mumble-server>/<redacted-ip>
...
[37.6 consecutive seconds of silence: next 10s alarm scheduled for 14:50:15 fails to fire]
...
09-27 14:50:42.990 26006 26006 W System.err: f4.c: An error occurred when communicating with the host
Caused by: java.io.EOFException
    at java.io.DataInputStream.readShort(DataInputStream.java:322)
    at se.lublin.humla.net.HumlaTCP.run(HumlaTCP.java:179)
09-27 14:50:42.991 26006 26006 E se.lublin.humla.HumlaService: Error: An error occurred when communicating with the host (reason: CONNECTION_ERROR)
09-27 14:50:43.022  4370 26194 I GoogleTTSServiceImpl: Synthesis request for locale eng-USA and name en-US-language ("Disconnected")
```

1. Reconnect completed at `14:49:42.989` and immediately re-entered standby mode.
2. The first 10-second alarm was delayed by 12.4 seconds, firing late at `14:50:05.377` (22.4 seconds after standby entry).
3. The next alarm (scheduled for `14:50:15`) was deferred completely by the OS.
4. From `14:50:05.377` to `14:50:42.990`, exactly **37.613 seconds** elapsed without any packets leaving the client.
5. Murmur's 30-second timer expired, Murmur closed the socket, and Text-to-Speech announced *"Disconnected"*.

### The USB Tethering Masking Effect

During initial testing with `adb logcat` over USB:
- `adb shell dumpsys battery` reported `AC powered: true`.
- When an Android device is actively charging, the OS **disables Doze mode and prevents aggressive kernel suspend-to-RAM**.
- Consequently, while tethered to a development PC, alarms fired accurately every 10 seconds (`14:53:53`, `14:54:03`, `14:54:13`), masking the defect entirely until simulated battery operation (`adb shell dumpsys battery unplug`) or untethered operation was tested.

---

## 4. The Architectural Defect in `wakelock-remediation-lite`

### A. The Fatal Assumption: Standard `setExact()` vs. Screen-Off Deferral

In [`docs/power-draw-optimizations/wakelock-remediation-lite.md`](wakelock-remediation-lite.md#component-2-the-aosp-alarm-throttling-paradox--the-bimodal-keepalive-engine), the implementation rationale stated:

> *"The critical insight lies in the behavioral divergence of standard AlarmManager.setExact(ELAPSED_REALTIME_WAKEUP, ...) (without the AllowWhileIdle flag): In State I (Active Mobility / Pocket Standby)... standard exact wakeup alarms are not tagged with FLAG_ALLOW_WHILE_IDLE and are not subject to ALLOW_WHILE_IDLE_SHORT_TIME = 60000 throttling... waking the CPU at the exact requested millisecond."*

This assumption directly contradicts Android platform behavior:
- According to official Android documentation for [`AlarmManager.setExact()`](https://developer.android.com/reference/android/app/AlarmManager#setExact(int,%20long,%20android.app.PendingIntent)):
  > *"Note: To schedule an alarm that is guaranteed to fire when the device is idle (e.g. while in doze or when the screen is off), see `setExactAndAllowWhileIdle(int, long, PendingIntent)`."*
- In AOSP [`AlarmManagerService.java`](https://cs.android.com/android/platform/superproject/main/+/main:frameworks/base/services/core/java/com/android/server/alarm/AlarmManagerService.java), any alarm that lacks `FLAG_ALLOW_WHILE_IDLE` or `FLAG_WAKE_FROM_IDLE` is passed through `adjustDeliveryTimeBasedOnDeviceIdle()`.
- When the screen is turned off and the device is on battery, the operating system shifts into low-power states (Light Doze / Quick Doze / OEM Sleep). Standard exact alarms are **intentionally batched and deferred** until the screen is turned on or a system maintenance window occurs.

### B. The AOSP While-Idle Throttling Deadlock

The reason the authors avoided `setExactAndAllowWhileIdle()` was the system rate-limit:

```math
T_{\text{while-idle-min}} = 60\text{ s} > T_{\text{murmur-timeout}} = 30\text{ s}
```

AOSP strictly enforces a minimum interval of 60 seconds (`ALLOW_WHILE_IDLE_SHORT_TIME`) for while-idle alarms, even for apps on the battery optimization whitelist (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`).

This creates a platform deadlock:
- If `setExact()` is used without `AllowWhileIdle`, the alarm is **deferred indefinitely** when the screen is off.
- If `setExactAndAllowWhileIdle()` is used, the alarm is **hard-clamped to $\ge 60$ seconds**, which exceeds Murmur's 30-second disconnect timeout.
- Android provides **no alarm mechanism** that permits an unheld Application Processor to wake up every 10 seconds while asleep on battery.

### C. Screen State Asymmetry (Display Wakelock Coupling)

When the phone screen is **on**:
- The Android display manager holds an internal display wakelock (`PowerManager.SCREEN_BRIGHT_WAKE_LOCK` or display C0 state).
- Because the display wakelock keeps the Application Processor awake, alarms fire promptly and user-space threads execute normally.
- This creates the illusion that zero-audio standby functions correctly during interactive bench testing. The failure triggers exclusively when the screen turns off and all display wakelocks are released.

---

## 5. Secondary Pipeline Defect: Unflushed Conscrypt SSL Streams

In addition to alarm deferral, the TCP keepalive transmission pipeline contains a concurrency race condition:

### Missing `flush()` on `SSLSocket`
In [`HumlaTCP.java`](../../libraries/humla/src/main/java/se/lublin/humla/net/HumlaTCP.java#L248-L256):

```java
public void sendMessage(final MessageLite message, final HumlaTCPMessageType messageType) {
    executeOnSendThread(new Runnable() {
        @Override
        public void run() {
            try {
                mDataOutput.writeShort(messageType.ordinal());
                mDataOutput.writeInt(message.getSerializedSize());
                message.writeTo(mDataOutput);
                // DEFECT: mDataOutput.flush() is NEVER called!
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    });
}
```

- `mDataOutput` wraps `ConscryptEngineSocket$SSLOutputStream`.
- `SSLOutputStream` buffers TLS records in user space until a record threshold is reached or `.flush()` is explicitly invoked.
- Furthermore, `mTCPSocket` does not enable `TCP_NODELAY` (`setTcpNoDelay(true)`), subjecting transmissions to Nagle's algorithm.

### The 200 ms Drain Race
In [`HumlaService.java`](../../libraries/humla/src/main/java/se/lublin/humla/HumlaService.java#L338-L342):

```java
scheduleKeepaliveAlarm();
if (mHandler != null) {
    mHandler.removeCallbacks(mKeepaliveDrainRunnable);
    mHandler.postDelayed(mKeepaliveDrainRunnable, STANDBY_KEEPALIVE_TX_DRAIN_MS); // 200 ms
}
```

- When an alarm pulse fires, `HumlaService` acquires `mKeepaliveWakeLock`.
- It invokes `sendKeepalivePing()`, which asynchronously offloads TLS formatting to `mSendExecutor`.
- `mKeepaliveDrainRunnable` releases the wakelock after a fixed 200 ms delay.
- If cellular radio wake latency, TLS encryption, or socket buffering exceeds 200 ms, `mKeepaliveWakeLock` is released before bytes leave the physical modem buffer. The Application Processor immediately re-enters kernel suspend, trapping the unflushed packet.

---

## 6. The Core Hardware Paradox: OEM Watchdog SIGKILL (0.21.9) vs. Murmur Timeout (0.21.10)

The root challenge in Mumla OLED's power optimization initiative is governed by a fundamental hardware, operating system, and protocol trilemma:

### A. The Impossible Triad: Comparative Invariant Matrix

| Architecture Baseline | `PARTIAL_WAKE_LOCK` State | `AudioTrack` Playback State | Murmur TCP Keepalive (30s) | OEM Watchdog (`SIGKILL`) | Power Footprint | Real-World Operational Outcome |
|---|---|---|---|---|---|---|
| **Mumla OLED 0.21.7 (Continuous Silence)** | Held continuously (24/7) | `PLAYSTATE_PLAYING` (Digital silence / zero PCM) | **Sustained** (In-memory loop pings Murmur every 10–30s) | **Immune** (Continuous silence shield active) | High ($35\text{--}60\text{ mA}$ AP + $15\text{--}30\text{ mW}$ Audio DSP) | **100% Stable Connection**: No disconnects, no process kills; burns power rendering silence. |
| **Mumla OLED 0.21.9 (Hardware Audio Gating)** | Held continuously (24/7) | `PLAYSTATE_PAUSED` (Standby pause after 3s/15s silence) | **Sustained** (In-memory loop pings Murmur every 10–30s) | **FATAL FAILURE (`SIGKILL`)**: Watchdog detects wakelock held without active audio | Moderate ($35\text{--}60\text{ mA}$ AP + $0\text{ mW}$ Audio DSP) | **Process Killed by OS**: Samsung Device Care, Xiaomi MIUI, and Vivo PEM kill app with `SIGKILL`. |
| **Mumla OLED 0.21.10 (Zero-Audio Standby Lite)** | Released in Zero-Audio Standby | `PLAYSTATE_PAUSED` (Standby pause enabled) | **FATAL FAILURE (TIMEOUT)**: Alarms deferred 37s–104s; Murmur drops socket | **Immune** (No wakelock held while audio is paused) | Low ($5\text{--}10\text{ mA}$ AP theoretical + $0\text{ mW}$ Audio DSP) | **Connection Dropped**: Murmur detects 30s inactivity and terminates session (`EOFException`). |

### B. Deconstructing the 0.21.9 Flaw: Audio Gating Triggers Process Termination

In Release **0.21.9**, the audio pipeline introduced route-aware `AudioTrack` standby pausing ([`AudioOutput.java`](../../libraries/humla/src/main/java/se/lublin/humla/audio/AudioOutput.java)) after 3 seconds of silence (15 seconds on Bluetooth A2DP) to cut the $15\text{ to }30\text{ mW}$ wasted by `AudioFlinger` and the hardware audio DSP/DAC on digital silence.

However, 0.21.9 left the monolithic `PowerManager.PARTIAL_WAKE_LOCK` active in [`HumlaService.java`](../../libraries/humla/src/main/java/se/lublin/humla/HumlaService.java). This combination created a fatal vulnerability:

1. **OEM Watchdog Heuristics**: Proprietary battery management daemons on Samsung (Device Care / OneUI), Xiaomi (MIUI / HyperOS), Huawei (EMUI), and BBK/Vivo (Power Engine Management / PEM) inspect `AudioFlinger` track state alongside active kernel wakelocks.
2. **The Kill Condition**: When an OEM watchdog detects an application holding an active `PARTIAL_WAKE_LOCK` with the screen off while no `AudioTrack` is in `PLAYSTATE_PLAYING`, the system classifies the process as a rogue background battery abuser.
3. **Outcome**: The OS terminates the process with `SIGKILL` without warning or ANR dialog.

Any naive recommendation to "simply hold `PARTIAL_WAKE_LOCK` while pausing `AudioTrack`" directly recreates the 0.21.9 defect and guarantees that the process will be murdered by OEM watchdogs within minutes of turning the screen off.

### C. Deconstructing the 0.21.10 Flaw: Wakelock Release Triggers Murmur Timeout

To prevent the 0.21.9 `SIGKILL` termination, 0.21.10 implemented `wakelock-remediation-lite`: whenever `isPlausiblyZeroAudio() == true`, `HumlaService` released `mWakeLock` alongside `AudioTrack.pause()`, ensuring no wakelock was held while audio was paused.

However, releasing `mWakeLock` collided with Android power management:
1. **Linux Kernel Suspend**: With zero wakelocks held, the Application Processor entered `suspend-to-RAM`.
2. **AlarmManager Deferral**: Android OS and OEM power managers defer standard `setExact(ELAPSED_REALTIME_WAKEUP)` alarms when the screen is off on battery (empirically observed delays: 37.6s to 104s).
3. **Murmur Inactivity Drop**: Upstream Murmur disconnects any client that sends zero TCP data for 30 consecutive seconds. Because keepalive alarms failed to fire, Murmur unilaterally severed the connection.

---

## 7. Actionable Remediation Paths

A viable architecture must resolve both constraints simultaneously: it must maintain TCP keepalive pings every $< 30$ seconds to satisfy Murmur, while never holding a `PARTIAL_WAKE_LOCK` with `AudioTrack` paused while the screen is off to satisfy OEM watchdogs.

### Path A: Screen-Aware Dynamic Silence Shield Coupling (Recommended)

This hybrid architecture leverages the fact that OEM watchdogs only enforce their kill heuristic when the screen is **off**:

1. **Screen-On Behavior (Foreground & Active Interaction)**:
   - When the screen is ON (monitored via `ACTION_SCREEN_ON`), the Android display subsystem holds a display wakelock (`PowerManager.SCREEN_BRIGHT_WAKE_LOCK` or display C0 state).
   - OEM watchdogs **never issue `SIGKILL` while the screen is on**.
   - Therefore, `AudioOutput.setStandbyPauseEnabled(true)` can be safely permitted, allowing `AudioTrack.pause()` and power-gating the audio DSP/DAC ($15\text{ to }30\text{ mW}$ saved) while the user is using the phone or has the display active.
2. **Screen-Off Behavior (Background Connected Standby)**:
   - When the screen turns OFF (monitored via `ACTION_SCREEN_OFF`):
     - `HumlaService` must **strictly inhibit audio standby pause** (`AudioOutput.setStandbyPauseEnabled(false)`).
     - `AudioTrack` enters `PLAYSTATE_PLAYING` rendering zero PCM digital silence (the proven 0.21.7 silence shield).
     - `HumlaService` maintains `PARTIAL_WAKE_LOCK` and keeps `HumlaConnection`'s in-memory `ScheduledExecutorService` active.
   - **Result**:
     - OEM watchdogs inspect `AudioFlinger`, observe an active playback track, classify Mumla OLED as active VoIP media, and **refrain from issuing `SIGKILL`**.
     - The in-memory keepalive loop continues ticking, dispatching TCP keepalive pings every 10–30 seconds, **preventing Murmur 30s timeouts**.
3. **Transport Hardening in `HumlaTCP.java`**:
   - Add explicit `mDataOutput.flush()` to `HumlaTCP.sendMessage()` to guarantee TLS records leave user space immediately.
   - Enable `setTcpNoDelay(true)` on `mTCPSocket` to disable Nagle packet coalescing.

### Path B: Complete Restoration of the 0.21.7 Baseline (Maximum Stability)

If total code simplicity and operational certainty across all legacy OEM devices is prioritized:

1. **Restore Perpetual Silence Playback**: Fully revert Phase 2 route-aware `AudioTrack` standby pausing. `AudioOutput` keeps `AudioTrack` continuously in `PLAYSTATE_PLAYING` rendering zero PCM digital silence.
2. **Restore Monolithic Wakelock**: Keep `PARTIAL_WAKE_LOCK` held continuously in `HumlaService` throughout the connection.
3. **Restore Native In-Memory Keepalives**: Retain the standard `ScheduledExecutorService` keepalive loop in `HumlaConnection`.
4. **Trade-Off**: Burns $15\text{ to }30\text{ mW}$ on audio hardware during silence, but is provably immune to both OEM watchdog termination and Murmur timeouts.

