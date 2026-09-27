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
6. [Actionable Remediation Paths](#6-actionable-remediation-paths)
   - [Option 1: Retain Continuous Network Wakelock, Preserve Audio Power-Gating (Recommended)](#option-1-retain-continuous-network-wakelock-preserve-audio-power-gating-recommended)
   - [Option 2: Complete Reversion to 0.21.9 Baseline](#option-2-complete-reversion-to-0219-baseline)

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

## 6. Actionable Remediation Paths

### Option 1: Retain Continuous Network Wakelock, Preserve Audio Power-Gating (Recommended)

This approach eliminates the regression immediately while preserving all audio hardware power savings from Phase 2:

1. **Re-engage Permanent `mWakeLock`**: In [`HumlaService.java`](../../libraries/humla/src/main/java/se/lublin/humla/HumlaService.java), hold `PARTIAL_WAKE_LOCK` continuously while connected.
2. **Restore In-Memory Keepalive Loop**: Do not put [`HumlaConnection.java`](../../libraries/humla/src/main/java/se/lublin/humla/net/HumlaConnection.java) into suspended standby mode. Let `ScheduledExecutorService` tick natively every 10–30 seconds.
3. **Preserve Audio Hardware Power-Gating**: Keep [`AudioOutput.setStandbyPauseEnabled(true)`](../../libraries/humla/src/main/java/se/lublin/humla/audio/AudioOutput.java#L168-L180). Pausing `AudioTrack` during silence cuts $15\text{ to }30\text{ mW}$ of audio DSP/DAC power without touching the network layer.
4. **Fix SSL Stream Flushing**: Add `mDataOutput.flush()` to [`HumlaTCP.java`](../../libraries/humla/src/main/java/se/lublin/humla/net/HumlaTCP.java#L252) and enable `setTcpNoDelay(true)`.

### Option 2: Complete Reversion to 0.21.9 Baseline

Revert the merge commit `cdc3c5f2` and restore the proven, stable 0.21.9 connection lifecycle.
