# Battery-Mode Periodic Disconnects: Doze Suspend Reaping by Murmur Ping Timeout

Field investigation, 2026-10-02. Device stayed connected overnight on charger
with zero drops; after unplugging (~09:00), Mumla OLED began disconnecting
every ~5–6 minutes until at least 11:17.

Related tracks: [wakelock-remediation](wakelock-remediation.md),
[remediation-plan](remediation-plan.md).

## 1. Timeline (device clock, EDT)

| Time | Event | Source |
|---|---|---|
| overnight – 09:19 | Stable connection, zero drops (charger attached, Doze inhibited) | `logcat`, `batterystats` |
| 09:19:18 | `status=discharging … -plugged` — unplugged onto battery at 100% | `dumpsys batterystats` |
| 09:19:31 | Screen off | `batterystats` (`-screen`) |
| 09:21:27 | First `+wake_lock=u0a429:"Humla:HumlaService"` reconnect | `batterystats` |
| 09:22:12 | `device_idle=full` — Doze engages, ~3 min after unplug + screen-off | `batterystats` |
| 09:21 – 11:17 | Reconnect burst every ~5–6 min: 09:26, 09:31, 09:37, 09:42, 09:48, 09:53, 09:59, 10:08, 10:14:08, 10:19:53, 10:25:40, 10:31:11, 10:36:27, 10:41:43, 10:47:14, 10:52:15, 10:57:47, 11:03:33, 11:08:49, 11:16:06 | `batterystats` wake-lock lines + `logcat` `HumlaService: Error` / `e4.k: Connecting` pairs |

The ~5.3-minute cadence matches this device's Doze light-idle schedule
exactly (`light_idle_to=+5m0s0ms` from `dumpsys deviceidle`).

> Note: the `logcat` main buffer begins at `10:14:08 (beginning of main)` —
> drops before that rotated out of the main buffer, but `batterystats`
> preserves the identical wake-lock/reconnect signature back to 09:21.

## 2. Failure signature (11 captured drops, all identical)

Every drop presents as a server-side close observed on the TCP read loop:

```
W System.err: g4.c: An error occurred when communicating with the host
W System.err: Caused by: java.io.EOFException
    at java.io.DataInputStream.readFully / readShort
    at e4.k.run(...:467)
E se.lublin.humla.HumlaService: Error: ... (reason: CONNECTION_ERROR)
```

De-obfuscated via the release R8 mapping (`app/build/outputs/mapping/fossRelease/mapping.txt`):

| Obfuscated | Real class |
|---|---|
| `e4.k` | `se.lublin.humla.net.HumlaTCP` (TCP read/write thread) |
| `e4.f` | `se.lublin.humla.net.HumlaConnection` |
| `g4.c` | `se.lublin.humla.util.HumlaException` |

So `e4.k.run(:467)` is the blocking `readShort()`/`readFully()` frame loop in
[`HumlaTCP.java`](../../libraries/humla/src/main/java/se/lublin/humla/net/HumlaTCP.java),
and the message string is its generic `catch (IOException e)` handler.
`EOFException` (not `SocketTimeoutException`, despite the 30 s `SO_TIMEOUT`
on the socket) proves the peer sent FIN — **the server closed the
connection**; the client merely observed it. The trailing
`SocketException: Socket is closed` spam is the sender thread flushing a dead
socket during teardown — noise, not cause.

## 3. The 30–40 s keepalive stall before every drop

Steady-state keepalive is one TCP + one UDP ping every 10 s
(`STEADY_STATE_PING_INTERVAL_SECONDS` in
[`HumlaConnection.java`](../../libraries/humla/src/main/java/se/lublin/humla/net/HumlaConnection.java),
visible as `DatagramSocket p = …<mumble-server>` lines). Before every
drop the pings stall for over three intervals:

| Drop | Last ping | Stall |
|---|---|---|
| 10:19:53 | 10:19:21 | 33 s |
| 10:25:40 | 10:25:06 | 34 s |
| 10:31:11 | 10:30:31 | 40 s |
| 10:36:27 | 10:35:54 | 33 s |
| 10:47:14 | 10:46:33 | 40 s |
| 10:52:15 | 10:51:35 | 40 s |
| 10:57:47 | 10:57:06 | 40 s |
| 11:03:33 | 11:02:56 | 37 s |
| 11:08:49 | 11:08:14 | 35 s |

## 4. Verified causal chain

Upstream Murmur defaults (see
[`src/murmur/Meta.cpp`](https://github.com/mumble-voip/mumble/blob/master/src/murmur/Meta.cpp)
and
[`src/murmur/Server.cpp`](https://github.com/mumble-voip/mumble/blob/master/src/murmur/Server.cpp)):

- Default `timeout = 30` s (`iTimeout = 30` in `Meta.cpp`).
- `Server::checkTimeout()` runs every 15.5 s and calls
  `u->disconnectSocket(true)` on any client whose `activityTime()` exceeds
  `iTimeout`.

Chain of events per cycle:

1. Client process/radio frozen for 30+ s → no TCP or UDP ping transmitted
   (any ping resets the server's per-client activity timestamp).
2. Murmur's 15.5 s sweeper observes inactivity > 30 s and closes the TCP socket.
3. Client wakes at the next Doze maintenance window; the blocked read loop
   immediately sees FIN → `EOFException` → `CONNECTION_ERROR`.
4. Auto-reconnect fires within the same second (`Connecting to
   <mumble-server>:64738`), so each outage is brief but the voice
   session and channel state flap every ~5 minutes.

## 5. Root cause: suspend + WiFi sleep on battery, not the Doze network firewall

Three independent pieces of evidence rule out a simple app bug and the Doze
network restriction path:

1. **No `WifiLock` held — ever.** `dumpsys wifi` shows `Locks held:` empty and
   `Locks acquired: 0 full high perf`. The codebase holds a `PARTIAL_WAKE_LOCK`
   (`Humla:HumlaService`, acquired indefinitely in `onConnectionSynchronized()`)
   and runs as a microphone-type foreground service, but nothing acquires a
   `WifiManager.WifiLock`, and the manifest declares no `ACCESS_WIFI_STATE` /
   `CHANGE_WIFI_STATE` permission. With `mSuspendOptimizationsEnabled true`,
   the WiFi firmware power-saves on screen-off + battery
   (`-wifi_radio`, `wifi-data: inactive`, `wake_reason="-1 wifi wakeup"`
   churn at every reconnect).
2. **The device suspends despite a continuously held partial wake lock.**
   There is not a single `-wake_lock=u0a429` transition in the whole
   09:19–11:17 window — the lock is never released — yet `-running` (kernel
   suspend-to-RAM) recurs between every reconnect, with suspend abort/retry
   churn (`Abort: Pending Wakeup Sources: qcom_rx_wakelock`). On AOSP a held
   `PARTIAL_WAKE_LOCK` makes suspend impossible, so this Vivo/OriginOS device
   is force-suspending beneath the wake lock via OEM power management.
3. **Doze firewall is already bypassed.** The package is on the `deviceidle`
   user whitelist (`se.lublin.mumla.oled15` listed) and `RUN_IN_BACKGROUND`
   is allowed — whitelisted apps are exempt from Doze network restrictions.
   The stall mechanism is therefore process suspend plus radio sleep, which no
   whitelist covers.

In short: on charger, no Doze/suspend, pings flow, connection lives. On
battery + screen-off, Vivo suspends the process and parks the radio between
maintenance windows; pings stall past Murmur's 30 s timeout; the server
reaps the connection; the client EOFs and reconnects. The 10 s ping interval
is already aggressive — shortening it cannot help threads that are never
scheduled.

## 6. Power-draw implications

Each 5-minute cycle pays a full reconnect: WiFi wake + TLS handshake
(`ConscryptEngineSocket.startHandshake` on the fresh socket) + model sync +
audio pipeline restart, plus the cellular/WiFi radio tail each reconnect's
traffic triggers. Counterintuitively, the cheapest steady state here is also
the most reliable one: while connected, the existing indefinite partial wake
lock already blocks deep sleep (see [wakelock-remediation](wakelock-remediation.md)),
so adding a `WIFI_MODE_FULL_HIGH_PERF` lock scoped to the connection lifetime
adds only the delta of keeping the radio out of power-save — small compared
to the periodic TLS-handshake + sync storms, and it eliminates the flapping
that keeps the radio in high-power tails anyway.

## 7. Remediation options

1. **Acquire a `WIFI_MODE_FULL_HIGH_PERF` `WifiLock` for the connection
   lifetime** (acquire in `onConnectionSynchronized()`, release in
   `onConnectionDisconnected()`/`disconnect()`), plus `ACCESS_WIFI_STATE` /
   `CHANGE_WIFI_STATE` manifest permissions. Standard VoIP practice; directly
   addresses the radio-sleep half of the stall. Small incremental drain while
   connected; large saving versus reconnect storms.
2. **Do not shorten the ping interval.** The threads are suspended, not slow;
   more frequent pings only cost radio tails without surviving suspend.
3. **OEM background-power exemption (user-facing).** On Vivo/OriginOS, verify
   the app is allowed "high background power consumption" / background
   running — the OEM force-suspend observed here overrides wake locks and no
   API fully defeats it. Document in user-facing troubleshooting.
4. **Longer-term:** the architectural standby-suspend track in
   [wakelock-remediation](wakelock-remediation.md) (coordinated suspend with
   server-side awareness) remains the principled fix for idle-connected
   drain; this incident is evidence for why naive wake-lock removal without
   that track would convert these 5-minute flaps into permanent silence.

## Appendix: reproduction commands

```bash
adb logcat -b all -d -v threadtime > /tmp/logcat_full.txt
adb shell dumpsys batterystats | grep "10-02 09:"   # unplug, screen, device_idle, wake_lock lines
adb shell dumpsys deviceidle | head -n 60           # light_idle_to cadence, whitelist
adb shell dumpsys wifi | grep -i "locks held" -A 3  # missing WifiLock
```
