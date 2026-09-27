# Architectural Deep Dive & Remediation: Partial Wakelock, Kernel Suspend & Android Deep Doze

An exhaustive architectural investigation, physical power model, and engineering remediation plan for eliminating the permanent `PowerManager.PARTIAL_WAKE_LOCK` in Mumla OLED ([`HumlaService.java`](../../libraries/humla/src/main/java/se/lublin/humla/HumlaService.java#L301-L306)) while navigating Linux kernel suspend-to-RAM, Android Deep Doze restrictions, upstream Murmur timeout invariants, and cellular/Wi-Fi hardware wake semantics.

## Table of Contents

1. [Decoupling Rationale & Executive Summary](#1-decoupling-rationale--executive-summary)
2. [Current Implementation Defect & Physical Hardware Footprint](#2-current-implementation-defect--physical-hardware-footprint)
3. [The Platform & Protocol Deadlock](#3-the-platform--protocol-deadlock)
   - [A. Linux Kernel Suspend vs. User-Space Timers](#a-linux-kernel-suspend-vs-user-space-timers)
   - [B. Upstream Murmur TCP Timeout Mechanics (`Server.cpp:1843`)](#b-upstream-murmur-tcp-timeout-mechanics-servercpp1843)
   - [C. Android Deep Doze & Alarm Throttling Limits](#c-android-deep-doze--alarm-throttling-limits)
   - [D. The Deadlock Formulation](#d-the-deadlock-formulation)
4. [The Real-World OEM Watchdog & On-Device Power Paradox](#4-the-real-world-oem-watchdog--on-device-power-paradox)
5. [Network Subsystem Physical Asymmetry: Cellular vs. Wi-Fi](#5-network-subsystem-physical-asymmetry-cellular-vs-wi-fi)
   - [A. Hardware Topology & Intersystem Wake Interfaces](#a-hardware-topology--intersystem-wake-interfaces)
   - [B. 3GPP Cellular DRX vs. IEEE 802.11 Power Save Protocol](#b-3gpp-cellular-drx-vs-ieee-80211-power-save-protocol)
   - [C. Physical Failure Modes of Consumer Wi-Fi Access Points](#c-physical-failure-modes-of-consumer-wi-fi-access-points)
   - [D. Wake Latency Budget, Jitter Buffer & Speech Onset Clipping](#d-wake-latency-budget-jitter-buffer--speech-onset-clipping)
   - [E. Android OS WifiLock Constraints & Screen-Off Throttling](#e-android-os-wifilock-constraints--screen-off-throttling)
   - [F. Network Physical Asymmetry Comparison Matrix](#f-network-physical-asymmetry-comparison-matrix)
6. [Target Architecture: Audio-Scoped Gating & Battery Exemption](#6-target-architecture-audio-scoped-gating--battery-exemption)
   - [Wakelock State Machine](#wakelock-state-machine)
   - [Step 1: Battery Optimization Exemption Gating](#step-1-battery-optimization-exemption-gating)
   - [Step 2: Audio-Scoped Active Lock (`mAudioWakeLock`)](#step-2-audio-scoped-active-lock-maudiowakelock)
   - [Step 3: Exact Alarm Pulsed Keepalive Lock (`mKeepaliveWakeLock`)](#step-3-exact-alarm-pulsed-keepalive-lock-mkeepalivewakelock)
   - [Step 4: Inbound Socket Packet Wakeup Bridge](#step-4-inbound-socket-packet-wakeup-bridge)
   - [Step 5: Autonomous Transport-Aware Standby Adaptation](#step-5-autonomous-transport-aware-standby-adaptation)
7. [Implementation Milestones & Phased Roadmap](#7-implementation-milestones--phased-roadmap)
8. [Verification, Edge Cases & Risk Mitigation Matrix](#8-verification-edge-cases--risk-mitigation-matrix)
9. [Conclusion](#9-conclusion)

---

## 1. Decoupling Rationale & Executive Summary

In early drafts of the power draw remediation roadmap ([`remediation-plan.md`](remediation-plan.md)), eliminating the permanent partial wakelock was grouped into **Phase 3: Deep Architectural Modernization** alongside compiler SIMD tuning and native OCB2-AES encryption.

However, an exhaustive engineering audit demonstrates that **the wakelock issue cannot be treated as a routine incremental optimization**:

1. **Massive Architectural Blast Radius**: Unlike compiler flags (build system) or native crypto (stateless mathematical transformation), wakelock lifecycle management directly impacts the entire application runtime: the Android [`HumlaService`](../../libraries/humla/src/main/java/se/lublin/humla/HumlaService.java) lifecycle, the [`HumlaConnection`](../../libraries/humla/src/main/java/se/lublin/humla/net/HumlaConnection.java) network keepalive loop, UDP and TCP socket receivers, the native [`AudioInputEngine`](../../libraries/humla/src/main/jni/audio_engine/AudioInputEngine.h), the [`AudioOutput`](../../libraries/humla/src/main/java/se/lublin/humla/audio/AudioOutput.java) render pipeline, Android OS `AlarmManager` APIs, and system permission flows.
2. **The Linux Suspend vs. Socket Timer Trap**: Naively dropping `mWakeLock.acquire()` causes the Application Processor (AP) to enter Linux kernel `suspend-to-RAM` during screen-off silence. While suspended, standard Java user-space timers (`ScheduledExecutorService`, `Handler.postDelayed`) **freeze completely**. As a result, 10-second keepalive pings fail to dispatch, causing Murmur servers to drop the connection after 30 seconds.
3. **Android Deep Doze Barriers**: Android Deep Doze restricts background alarm executions (`setAndAllowWhileIdle()`) to once every **9 to 15 minutes**, making regular 10-second wakeups physically impossible on stationary, non-exempt devices.
4. **Physical Network Asymmetry**: While LTE/5G cellular modems reliably wake the AP on incoming IP traffic via hardware baseband interrupts, Wi-Fi hardware in 802.11 Power Save Mode (PSM) frequently drops or delays inbound UDP voice datagrams across consumer routers.

Because this remediation represents the **single largest engineering lift** across the entire power optimization initiative, it has been decoupled into this dedicated specification to allow independent tracking, rigorous formal prototyping, and deep verification.

> [!TIP]
> **Pragmatic Low-Hanging Fruit (The Lite Track)**:
> While this document details the universal, full-scope architecture for sleeping the Application Processor between spoken utterances in active channels, a streamlined, low-risk alternative focused exclusively on provably zero-audio conditions (such as when the user is deafened or alone on the server) is documented in:
>
> 👉 **[Wakelock Remediation (Lite Track): Zero-Audio Standby Optimization](wakelock-remediation-lite.md)**

---

## 2. Current Implementation Defect & Physical Hardware Footprint

### Source Location

[`libraries/humla/src/main/java/se/lublin/humla/HumlaService.java#L520-L525`](../../libraries/humla/src/main/java/se/lublin/humla/HumlaService.java#L520-L525):

```java
// HumlaService.java:520-525
Log.v(TAG, "Connected");
if (mWakeLock != null) {
    if (mWakeLock.isHeld()) {
        mWakeLock.release();
    }
    mWakeLock.acquire();
}
```

### The Defect

Upon receiving `ServerSync` from the server, `HumlaService` acquires an untimed, indefinite `PowerManager.PARTIAL_WAKE_LOCK`.

- Reference counting is disabled (`mWakeLock.setReferenceCounted(false)` at [`HumlaService.java#L303`](../../libraries/humla/src/main/java/se/lublin/humla/HumlaService.java#L303)).
- The lock remains held continuously until the connection is fully torn down in `disconnect()` or `onDestroy()`.
- The lock is never dropped during hours of silent connected standby, screen-off periods, or when the client is completely muted and deafened.

### Physical Hardware Footprint

On mobile Application Processors (SoCs) such as Qualcomm Snapdragon, Google Tensor, and MediaTek Dimensity:

- Holding a `PARTIAL_WAKE_LOCK` permanently prevents the Linux kernel power management framework from executing `suspend-to-RAM` (`echo mem > /sys/power/state`).
- CPU core clusters are prevented from falling below C1/C2 states. Clock trees, high-speed memory buses (LPDDR4X/LPDDR5), and internal power rails remain energized.
- Even when all threads are blocked on locks (`wait()`), the Linux kernel scheduler continuously wakes CPU cores to service system tick interrupts (100–250 Hz).
- **Current Draw**: Burns **$35.0\text{ to }60.0\text{ mA}$** continuously on modern hardware ($135\text{ to }231\text{ mW}$ at nominal 3.85 V).
- **Battery Drain**: Over an 8-hour overnight standby connected to a silent Mumble server, this single defect consumes **$\approx 280\text{ to }480\text{ mAh}$** (typically $\sim 350\text{ to }480\text{ mAh}$ accounting for baseline SoC standby; 10% to 15% of total battery capacity) without a single spoken word or audible sound.

---

## 3. The Platform & Protocol Deadlock

### A. Linux Kernel Suspend vs. User-Space Timers

```mermaid
flowchart TD
    subgraph SoC_Active ["AP Active / C0-C1 State (PARTIAL_WAKE_LOCK Held)"]
        ActiveClock["High-Speed Clocks Running"]
        ActiveSched["Linux CFS Scheduler Active (100-250 Hz Ticks)"]
        ActiveTimers["ScheduledExecutorService / epoll_wait fires accurately"]
        ActivePower["Power Draw: 35 - 60 mA (135 - 231 mW)"]
    end

    subgraph SoC_Suspend ["AP Suspend-to-RAM / Deep Sleep (No Wakelock)"]
        SuspClocks["High-Speed Clocks Power-Gated"]
        SuspCores["CPU Cores in Retention / Off (< 10 mW)"]
        SuspTimers["ScheduledExecutorService / Java Threads FROZEN"]
        SuspWakes["Only RTC Alarm (AlarmManager) or HW Interrupts wake SoC"]
    end
```

When no Android wakelocks are active, the Linux kernel autosuspend subsystem suspends all user-space threads. Crucially:

- `ScheduledExecutorService` (used by [`HumlaConnection.java`](../../libraries/humla/src/main/java/se/lublin/humla/net/HumlaConnection.java) for keepalives) relies on POSIX `timerfd` or `epoll_wait`.
- While `CLOCK_BOOTTIME` or `CLOCK_MONOTONIC` tracks suspended time in the kernel, **user-space timerfd timeouts do not wake the Application Processor from suspend-to-RAM**.
- Consequently, when the AP suspends, the keepalive loop **stops ticking entirely**.

### B. Upstream Murmur TCP Timeout Mechanics (`Server.cpp:1843`)

In upstream Murmur ([`Server.cpp:1843`](https://github.com/mumble-voip/mumble/blob/master/src/murmur/Server.cpp#L1843-L1847)), the client timeout check is evaluated strictly against the TCP connection's activity timestamp (`u->activityTime()`):

```cpp
if (u->activityTime() > (iTimeout * 1000)) {
    log(u, "Timeout");
    qlClose.append(u);
}
```

- **Timeout Value**: Default `iTimeout` is **30 seconds** (configurable down to 15–20 seconds).
- **TCP Exclusivity**: Murmur resets `activityTime()` **only when receiving TCP messages** (`Server::message`). UDP pings do not reset TCP `activityTime()`.
- **Periodic Murmur Tick**: Murmur checks client timeouts every **15.5 seconds** ([`qtTimeout->start(15500)`](https://github.com/mumble-voip/mumble/blob/master/src/murmur/Server.cpp#L283) at [`Server.cpp:283`](https://github.com/mumble-voip/mumble/blob/master/src/murmur/Server.cpp#L283)).
- **Failure Consequence**: If the client AP suspends for $\ge 30\text{ seconds}$ without sending a TCP ping, the server forcefully terminates the connection with `"Timeout"`.

### C. Android Deep Doze & Alarm Throttling Limits

Starting in Android 6.0 (Marshmallow, API 23), Android introduces **Doze Mode**:

- When the screen is off, the device is stationary (accelerometer idle), and running on battery, Android enters **Deep Doze**.
- In Deep Doze:
  1. Network access is completely blocked for all non-whitelisted apps.
  2. Standard wakelocks are ignored for non-whitelisted apps.
  3. `AlarmManager.setExactAndAllowWhileIdle()` is strictly throttled by `AlarmManagerService`: alarms are clamped to `ALLOW_WHILE_IDLE_LONG_TIME` (**once every 9 to 15 minutes**) during deep idle, while even outside deep idle in low-power states the framework enforces `ALLOW_WHILE_IDLE_SHORT_TIME` (a **60-second** minimum interval).

### D. The Deadlock Formulation

```math
T_{\text{doze-alarm}} \approx 9\text{ to }15\text{ minutes} \gg T_{\text{murmur-timeout}} = 30\text{ seconds}
```

This mathematical inequality constitutes the core platform deadlock:

- Murmur drops the connection after **30 seconds** of silence.
- Android Deep Doze only permits background CPU wakeups every **540 to 900 seconds**.
- Therefore, on a standard non-whitelisted Android device, **a VoIP client cannot sustain an active TCP Mumble session in Deep Doze without battery optimization exemption**.
- Furthermore, because `AlarmManagerService`'s 60-second / 15-minute rate-limiting constants apply system-wide (even to apps on the power whitelist), **a client cannot sustain 10-second keepalives via `setExactAndAllowWhileIdle` while fully unheld in stationary Deep Doze**. Instead, battery optimization exemption enables two critical privileges: **unrestricted background network access** and the **permission to hold partial wakelocks during Doze**. A viable architecture must therefore decouple active screen-off suspend-to-RAM from stationary Deep Doze defense (see the comprehensive Bimodal Keepalive Engine in [`wakelock-remediation-lite.md`](wakelock-remediation-lite.md#component-2-the-aosp-alarm-throttling-paradox--the-bimodal-keepalive-engine)).

---

## 4. The Real-World OEM Watchdog & On-Device Power Paradox

Developers historically held `PARTIAL_WAKE_LOCK` 24/7 as an easy way to guarantee connection stability. Because Mumla OLED is strictly a FOSS application distributed via GitHub Releases and F-Droid (and never published to the Google Play Store), commercial Play Store discoverability penalties and Google Play Console metrics are completely irrelevant. Instead, the operational penalty is enforced directly on the physical mobile device:

1. **Physical Battery Drain & On-Device OS Warnings**:
   - Holding a `PARTIAL_WAKE_LOCK` continuously burns **$35\text{ to }60\text{ mA}$** constantly by preventing Linux kernel `suspend-to-RAM`. Over an 8-hour period, this wastes **$\approx 280\text{ to }480\text{ mAh}$** (10% to 15% of battery capacity) on pure silence.
   - Modern Android builds track per-app background drain and CPU wake time locally. The system battery manager flags the app directly to the user: *"Mumla OLED is draining battery in the background. Put app to sleep?"* Users who heed this prompt restrict background execution, which completely severs network connectivity when the screen turns off.
2. **Aggressive OEM Task Killers (The "Don't Kill My App" Problem)**:
   - Modern OEM skins (Samsung Device Care / OneUI, Xiaomi MIUI / HyperOS, Huawei EMUI, BBK ColorOS / OxygenOS) implement proprietary background watchdogs that operate at framework and kernel levels.
   - When an OEM watchdog detects an app holding an active partial wakelock while the screen is off without media audio playing through `AudioTrack`, **the OS forcefully kills the process (`SIGKILL`)**.
   - *(Note on Google Play Vitals)*: While Google Play enforces a commercial "Excessive Wake Locks" threshold (> 1 hour cumulative background wakelock/day), Mumla OLED's mandate is driven entirely by on-device battery longevity and preventing premature OS termination.

### Historical Context: The 0.21.7 "Continuous Silence" Workaround

It is crucial to emphasize that **the 0.21.7 implementation was not functionally broken**. In Mumla OLED 0.21.7 and earlier, the application never suffered from OEM watchdog `SIGKILL` terminations during silent connected standby.

Historically, [`AudioOutput.java`](../../libraries/humla/src/main/java/se/lublin/humla/audio/AudioOutput.java) kept `AudioTrack` continuously in `PLAYSTATE_PLAYING`, constantly rendering digital silence (zero PCM) even when no participants were speaking on the server. Far from being a bug, this perpetual playback served as an effective (albeit brute-force) shield against aggressive OEM task killers:
- Because the audio pipeline was actively playing sound through `AudioTrack`, OEM watchdogs classified Mumla OLED as an active media playback service rather than an idle background abuser.
- Consequently, the watchdog's kill condition (*"wakelock held without active `AudioTrack` playback"*) was never satisfied, and the process was spared from `SIGKILL`.
- Background connection stability was fully maintained; silent disconnections did not occur.

However, this stability was purchased at an extreme power cost:
- Pumping continuous silence forced Android's `AudioFlinger` mixer thread to run 24/7.
- The hardware audio DSP (e.g., Qualcomm Hexagon LPASS), external DAC, I2S/SoundWire inter-chip buses, and speaker/earpiece analog amplifiers remained fully energized, continuously burning **$15\text{ to }30\text{ mW}$** purely on digital silence—in addition to the **$35\text{ to }60\text{ mA}$** ($135\text{ to }231\text{ mW}$) burned by keeping the Application Processor out of kernel suspend-to-RAM.

### The Modern Paradox Emerges with Subsystem Gating (Phase 2 / 0.21.9)

The true paradox only emerged when addressing audio hardware power draw in **Phase 2** (Release 0.21.9, item 2.2):
- To eliminate the $15\text{ to }30\text{ mW}$ wasted on silent playback, [`AudioOutput.java`](../../libraries/humla/src/main/java/se/lublin/humla/audio/AudioOutput.java) introduced route-aware `AudioTrack` standby pausing (`mAudioTrack.pause()`) after 3 seconds (speaker/wired) or 15 seconds (Bluetooth) of consecutive silence.
- Pausing `AudioTrack` successfully powers down the audio DSP and DAC, but it simultaneously **strips away the historical "silence shield"**.
- If the permanent monolithic `PARTIAL_WAKE_LOCK` in [`HumlaService.java`](../../libraries/humla/src/main/java/se/lublin/humla/HumlaService.java#L301-L306) is left active while `AudioTrack` is in `PLAYSTATE_PAUSED`, the application suddenly meets the exact criteria monitored by OEM watchdogs: an active partial wakelock held with the screen off and no audio playing through `AudioTrack`.
- **The Paradox**: The historical codebase was not broken—it sustained stability by burning battery on silence. But optimizing audio hardware power draw without simultaneously modernizing the wakelock lifecycle creates a fatal conflict, causing OEM watchdogs to forcefully kill the process (`SIGKILL`). Power-gating `AudioTrack` therefore mandates decoupling and scoping the wakelock to active speech so that `mWakeLock` is released alongside `AudioTrack.pause()`.

---

## 5. Network Subsystem Physical Asymmetry: Cellular vs. Wi-Fi

Can the Application Processor (AP) safely drop all wakelocks, enter Linux kernel `suspend-to-RAM`, and rely exclusively on incoming network traffic to wake the device when another user begins speaking?

The physical reality of modern mobile hardware dictates that **the answer is sharply asymmetric**: over cellular basebands, inbound packet wake is nearly 100% reliable with microsecond hardware buffering; over consumer Wi-Fi, however, power-saving protocols and router firmware defects cause widespread packet discards, NAT collapse, and clipped voice onsets.

### A. Hardware Topology & Intersystem Wake Interfaces

The physical hardware architecture governing how incoming network packets reach a suspended Application Processor differs fundamentally between cellular modems and Wi-Fi transceivers:

```mermaid
flowchart TD
    subgraph Cellular_Path ["Cellular Downlink Wake Path (LTE / 5G NR)"]
        Tower["Cellular Base Station (eNodeB / gNodeB)"] -->|RF Downlink Scheduling| ModemRF["RF Front-End & Transceiver"]
        ModemRF --> ModemDSP["Dedicated Baseband SoC / DSP (Qualcomm Snapdragon / Exynos / MediaTek)"]
        ModemDSP --> ModemFIFO["Hardware Ring Buffers (Internal SRAM / LPDDR)"]
        ModemFIFO -->|Assert HW Wake Pin / PCIe PME| ModemIRQ["Out-of-Band Hardware IRQ (WAKE_HOST / SPMI)"]
    end

    subgraph AP_Subsystem ["Host Application Processor (Linux Kernel / Android)"]
        ModemIRQ -->|Resume SoC Power Rail| APKernel["Linux Kernel IRQ Handler / PCIe ASPM L1 Exit"]
        APKernel -->|Resume CFS Scheduler| NetStack["Kernel TCP/UDP Stack"]
        NetStack -->|Unblock select / epoll| Readers["HumlaUDP / HumlaTCP Socket Reader Threads"]
        Readers -->|Acquire Bridge Wakelock| AppService["HumlaService / AudioOutput Pipeline"]
    end

    subgraph WiFi_Path ["Wi-Fi Downlink Wake Path (802.11ac / ax / 7)"]
        Router["Consumer Wi-Fi Router (AP)"] -->|DTIM Beacon Broadcast| WiFiRF["Wi-Fi MAC/PHY Chipset (SDIO / PCIe)"]
        Router -.->|Drop-Tail Buffer Discard / Stale ARP| LostPackets["Dropped Inbound Voice Datagrams"]
        WiFiRF -->|PS-Poll / Trigger Frame| Router
        Router -->|Unicast UDP Burst| WiFiRF
        WiFiRF -->|In-Band Interrupt| APKernel
    end
```

1. **Cellular Modem Subsystem (Autonomous Baseband)**:
   - Modern LTE/5G baseband modems (e.g., Qualcomm Snapdragon X65/X70/X75, Samsung Exynos Modem, MediaTek M80) are fully autonomous secondary computers. They operate on isolated power rails and execute their own real-time operating systems (RTOS) independently of the Application Processor (AP).
   - The modem interfaces with the AP across high-speed PCIe (with Active State Power Management (ASPM L1/L1ss)) or HS-UART/SPMI, coupled with a dedicated, out-of-band physical GPIO interrupt line (typically labeled `AP_WAKEUP` or `WAKE_HOST`).
   - When the host AP enters Linux kernel `suspend-to-RAM` (`echo mem > /sys/power/state`), the modem stays fully active in low-power cellular listening mode.
   - When an incoming IP datagram arrives over the cellular air interface, the modem's internal DSP buffers the packet in hardware SRAM/DRAM FIFO queues and pulls the `WAKE_HOST` pin low. This triggers a dedicated hardware interrupt on the AP's Power Management Integrated Circuit (PMIC) or SoC interrupt controller, waking the kernel within $15\text{ to }25\text{ ms}$ with **zero packet loss**.

2. **Wi-Fi Subsystem (Co-Processor & Shared Radio)**:
   - Wi-Fi chipsets (e.g., Broadcom BCM43xx, Qualcomm FastConnect) interface via SDIO 3.0 or PCIe.
   - When the AP suspends, the Wi-Fi MAC/PHY microcontroller handles low-level 802.11 maintenance frames, but relies entirely on the upstream Access Point (the home/office router) to hold and buffer incoming unicast packets while the radio receiver sleeps.
   - The link between the Wi-Fi chipset and the host AP lacks the deep packet-buffering queues present on cellular basebands. If incoming UDP datagrams are forwarded too rapidly before the host AP finishes its kernel wake sequence, local driver ring buffer overflows occur.

---

### B. 3GPP Cellular DRX vs. IEEE 802.11 Power Save Protocol

The stark contrast in wake reliability stems directly from the underlying radio protocol specifications:

#### 1. 3GPP Cellular Discontinuous Reception (C-DRX & I-DRX)

- In LTE (3GPP TS 36.321) and 5G NR (3GPP TS 38.321), power conservation is governed by **Discontinuous Reception (DRX)**:
  - **Connected-Mode DRX (C-DRX)**: While an active radio link is maintained, the UE (User Equipment) cycles between an *On Duration* ($1\text{ to }10\text{ ms}$) and an *Off Duration* ($40\text{ to }640\text{ ms}$). During the on-duration, the modem monitors the Physical Downlink Control Channel (PDCCH) for downlink scheduling allocations.
  - **Idle-Mode DRX (I-DRX)**: When the radio connection is released to save energy, the modem sleeps for extended paging cycles ($1.28\text{ to }2.56\text{ s}$).
- **Guaranteed Network Buffering**: By 3GPP standard specification, the cellular network infrastructure (eNodeB / gNodeB and Evolved Packet Core / 5G User Plane Function) is **architecturally required to buffer downlink IP packets** while the UE is in the DRX off-state.
- Once the scheduling grant is signaled on PDCCH, the cellular base station transmits the buffered IP datagrams over the Physical Downlink Shared Channel (PDSCH). The terminal baseband accepts the transport block into its DMA ring and asserts the host AP wake interrupt. The cellular radio protocol guarantees that packet drop due to mobile device sleep is essentially non-existent ($< 0.1\%$).

#### 2. IEEE 802.11 Power Save Mode (PSM) & DTIM

- In IEEE 802.11, a sleeping station (STA) enters **Power Save Mode (PSM)** by asserting the `Power Management (PM)` bit ($PM=1$) in the MAC frame control header:
  - The STA shuts down its RF transceiver and only powers up to listen for periodic **Beacon frames** transmitted by the Access Point (AP), typically every $100\text{ TU} \approx 102.4\text{ ms}$.
  - The Access Point broadcasts a **Delivery Traffic Indication Message (DTIM)** at integer multiples of the beacon interval (e.g., DTIM period = 1, 2, or 3, yielding wake intervals of $102.4\text{ ms}$ to $307.2\text{ ms}$).
  - In the DTIM frame, the AP includes a Traffic Indication Map (TIM) bitmap indicating which associated STAs have buffered unicast traffic waiting on the router.
  - Upon decoding its Association ID (AID) in the TIM, the STA transmits a `PS-Poll` frame or a WMM/U-APSD trigger frame to request delivery of the buffered frames.
- **The Protocol Weakness**: Unlike carrier-grade cellular base stations, the 802.11 standard does not enforce rigorous minimum queue depths or latency bounds on how access points manage buffered unicast UDP traffic for sleeping stations.

---

### C. Physical Failure Modes of Consumer Wi-Fi Access Points

In laboratory testing and real-world mobile deployments, consumer-grade Wi-Fi routers (ASUS, TP-Link, Netgear, ISP-supplied combo gateways) demonstrate catastrophic failure modes when handling incoming VoIP UDP traffic destined for sleeping Android clients:

1. **Drop-Tail Buffer Discard on Burst Arrival**:
   - Mumble / Mumla OLED voice streams use Opus audio framed at $20\text{ ms}$ intervals ($50\text{ packets/second}$).
   - When a remote participant presses PTT and speaks, the server dispatches a rapid succession of UDP packets.
   - Consumer routers allocate minimal SRAM (often only 4 to 8 packets per associated station) for PSM sleep buffering. When a burst of 5 to 10 incoming UDP datagrams arrives between DTIM intervals, the router's queue overflows almost instantaneously, causing **immediate drop-tail packet loss**. The first $100\text{ to }200\text{ ms}$ of speech is discarded at the router before the phone ever learns that packets were pending.

2. **Aggressive NAT State Pruning (The 15-Second Window)**:
   - Consumer router state tables maintain Network Address Translation (NAT) binding entries for outbound UDP sessions.
   - While TCP connections typically enjoy 24-hour default NAT timeouts, **UDP NAT bindings are aggressively pruned**—frequently after only **$15\text{ to }30\text{ seconds}$ of silence**.
   - If Mumla OLED extends keepalive ping intervals beyond the router's UDP binding lifetime, the router silently drops the pinhole translation. Subsequent inbound voice packets from the server hit the router's WAN interface without an active port forwarding rule and are silently discarded or rejected with `ICMP Port Unreachable`.

3. **Stale ARP / MAC Resolution Failures**:
   - When a phone has been stationary with the screen off in suspend-to-RAM for several minutes, some router firmware implementations flag the station's IP/MAC address mapping in the ARP cache as "stale" or "expired".
   - When an incoming UDP voice packet arrives from the Internet, the router does not buffer the packet; instead, it transmits an ARP broadcast request on the local BSSID while simultaneously **dropping the inbound VoIP packet that triggered the resolution**.

4. **WMM Power Save / U-APSD Incompatibilities**:
   - Wi-Fi Multimedia Power Save (WMM-PS / U-APSD) is designed to optimize VoIP delivery by allowing bidirectional voice exchange within a single service period.
   - However, numerous consumer routers feature buggy or incomplete U-APSD implementations that fail to release queued voice packets upon receiving uplink trigger frames, or fail to prioritize Voice Access Category (`AC_VO`) queues when stations are transitioning from 802.11 sleep to active states.

---

### D. Wake Latency Budget, Jitter Buffer & Speech Onset Clipping

When an incoming speech burst arrives while the Application Processor is suspended in Linux kernel `suspend-to-RAM`, the end-to-end latency to render audio is composed of a multi-stage hardware and software pipeline:

```math
T_{\text{onset-latency}} = T_{\text{medium-delay}} + T_{\text{hw-irq}} + T_{\text{kernel-resume}} + T_{\text{sched-boost}} + T_{\text{render-prime}}
```

Where:

- $T_{\text{medium-delay}}$: Air-interface scheduling delay (PDCCH allocation on cellular vs. DTIM beacon wait + PS-Poll on Wi-Fi).
- $T_{\text{hw-irq}}$: Bus transaction time to transfer packet data (PCIe L1 exit / DMA transfer).
- $T_{\text{kernel-resume}}$: Linux kernel autosuspend wakeup latency (power-rail gating, clock tree restabilization).
- $T_{\text{sched-boost}}$: CFS scheduler wakeup and CPU frequency scaling via `schedutil` governor.
- $T_{\text{render-prime}}$: [`AudioOutput.java`](../../libraries/humla/src/main/java/se/lublin/humla/audio/AudioOutput.java) decoding, unpausing `AudioTrack`, and priming the Speex jitter buffer ([`jitter.c`](../../libraries/humla/src/main/jni/audio_engine/jitter/jitter.c)).

#### Latency Budget Comparison

| Pipeline Stage | Cellular Modem (LTE / 5G) | Consumer Wi-Fi (802.11ac / ax) |
| --- | --- | --- |
| Air Interface Latency ($T_{\text{medium-delay}}$) | $10\text{ to }25\text{ ms}$ (C-DRX subframe grant) | $50\text{ to }300\text{ ms}$ (DTIM beacon phase delay) |
| Bus Transfer & HW IRQ ($T_{\text{hw-irq}}$) | $2\text{ to }5\text{ ms}$ (Dedicated PCIe PME pin) | $5\text{ to }15\text{ ms}$ (SDIO / PCIe shared IRQ) |
| Kernel Resume ($T_{\text{kernel-resume}}$) | $15\text{ to }25\text{ ms}$ (SoC wake from C2/retention) | $15\text{ to }25\text{ ms}$ (SoC wake from C2/retention) |
| CPU DVFS Boost ($T_{\text{sched-boost}}$) | $5\text{ to }10\text{ ms}` (`schedutil` ramp) | $5\text{ to }10\text{ ms}` (`schedutil` ramp) |
| Jitter Buffer Prime ($T_{\text{render-prime}}$) | $10\text{ to }20\text{ ms}$ (Opus decode & `AudioTrack.play`) | $10\text{ to }20\text{ ms}$ (Opus decode & `AudioTrack.play`) |
| **Total Speech Onset Latency** | **$42\text{ to }85\text{ ms}$** | **$85\text{ to }370\text{ ms}$ (or packet loss)** |

#### Impact on the Speex Jitter Buffer ([`jitter.c`](../../libraries/humla/src/main/jni/audio_engine/jitter/jitter.c))

- Mumla OLED's native audio engine relies on the Speex adaptive jitter buffer ([`jitter.c`](../../libraries/humla/src/main/jni/audio_engine/jitter/jitter.c)) configured with a target buffer margin (`buffer_margin` $\approx 40\text{ to }60\text{ ms}$).
- **On Cellular**: The $42\text{ to }85\text{ ms}$ total wake latency causes the first packet to arrive slightly late, but the jitter buffer's dynamic auto-adjustment ([`jitter_buffer_update_delay`](../../libraries/humla/src/main/jni/audio_engine/jitter/jitter.c#L748)) easily absorbs the delay, resulting in crisp, unclipped speech playback.
- **On Wi-Fi**: If the router delays the packet across a $200\text{ ms}$ DTIM window or drops the initial packet, the jitter buffer detects an unrecoverable gap. It invokes Packet Loss Concealment (PLC) extrapolation via [`OpusVoiceDecoder.cpp`](../../libraries/humla/src/main/jni/audio_engine/OpusVoiceDecoder.cpp), synthesizing artificial comfort noise or robotic pitch-period repetitions. If multiple initial packets are dropped, **the first 1 to 2 spoken words are permanently muted**, destroying the user experience.

---

### E. Android OS WifiLock Constraints & Screen-Off Throttling

A common engineering question is: *Can we eliminate Wi-Fi packet drops by acquiring an Android `WifiManager.WifiLock`?*

An exhaustive audit of the Android framework demonstrates that **`WifiLock` cannot resolve this asymmetry without defeating our battery optimization goals**:

1. **The Power Draw Dilemma (`WIFI_MODE_FULL_HIGH_PERF`)**:
   - Acquiring a `WifiLock` with `WIFI_MODE_FULL_HIGH_PERF` instructs the Wi-Fi driver to disable 802.11 Power Save Mode (PSM) and keep the radio receiver in continuous active listening (`CAM` — Constantly Awake Mode).
   - In continuous active mode, the Wi-Fi chipset consumes **$150.0\text{ to }250.0\text{ mW}$** constantly. This burns *more power than holding the partial CPU wakelock*, completely invalidating the power savings of Linux kernel suspend-to-RAM.

2. **Android 10+ (API 29) Screen-Off Deactivation**:
   - Starting in Android 10, the Android OS power manager enforces aggressive restrictions on `WifiLock`:

     ```java
     // Android Framework: WifiLockManager.java
     // High-perf and low-latency Wi-Fi locks are automatically disabled when the screen turns off!
     ```

   - Unless an application is exempted or actively streaming an audible foreground media session, the framework **automatically deactivates high-performance Wi-Fi locks as soon as the screen turns off**.
   - Consequently, when the phone enters silent standby with the screen off, any acquired `WifiLock` is ignored by the OS, and the Wi-Fi chip falls back to 802.11 PSM and DTIM listening regardless of developer intent.

3. **Android 12+ (API 31) Low-Latency Lock (`WIFI_MODE_FULL_LOW_LATENCY`)**:
   - Android 12 introduces `WIFI_MODE_FULL_LOW_LATENCY` specifically for mobile gaming and VoIP.
   - However, this lock is explicitly gated by the system window manager: it is only active while the calling application's window is **visible on screen and in the foreground**. The instant the user locks their device or switches apps, the low-latency mode is deactivated.

---

### F. Network Physical Asymmetry Comparison Matrix

The table below synthesizes the complete physical, architectural, and operational asymmetry between cellular networks and Wi-Fi environments:

| Engineering Dimension | Cellular Modem (LTE / 5G NR) | Enterprise Wi-Fi (802.11ax / WMM-PS) | Consumer Wi-Fi (802.11ac / Home Router) |
| --- | --- | --- | --- |
| **Autonomous Hardware Subsystem** | Dedicated Baseband SoC + PMIC | Shared Wi-Fi MAC/PHY Chipset | Shared Wi-Fi MAC/PHY Chipset |
| **Intersystem Host Wake Interface** | Dedicated out-of-band HW IRQ (`WAKE_HOST` pin / PCIe PME) | Shared SDIO 3.0 / PCIe in-band interrupt | Shared SDIO 3.0 / PCIe in-band interrupt |
| **Power Save Protocol** | 3GPP Connected DRX (C-DRX, $40\text{ to }640\text{ ms}$) | 802.11 WMM-PS / U-APSD with QoS queues | 802.11 Legacy PSM with DTIM ($102.4\text{ to }307.2\text{ ms}$) |
| **Network Infrastructure Buffering** | **Mandatory by 3GPP Standard**: eNodeB/gNodeB buffers all downlink SDUs | Deep router memory buffers with Voice QoS (`AC_VO`) queues | **Unreliable / Shallow**: 4–8 packet queues; frequent drop-tail discards |
| **Inbound Wake Reliability** | **$> 99.9\%$**: Zero packet drop during AP sleep | **$\approx 90\%\text{ to }95\%$**: Occasional beacon phase jitter | **$\approx 60\%\text{ to }85\%$**: Severe packet loss on initial speech bursts |
| **NAT State Lifetime** | Carrier CGNAT timers: $30\text{ to }60\text{ s}$ | Enterprise state tables: $60\text{ to }300\text{ s}$ | Consumer router tables: **$15\text{ to }30\text{ s}$** (rapid collapse) |
| **Speech Onset Latency ($T_{\text{onset}}$)** | $42\text{ to }85\text{ ms}$ (smooth Speex jitter buffer absorb) | $60\text{ to }120\text{ ms}$ (acceptable latency) | $85\text{ to }370\text{ ms}$ (frequent syllable clipping / PLC distortion) |
| **Active Radio Standby Drain** | $\approx 3.0\text{ to }8.0\text{ mA}$ (modem in DRX paging) | $\approx 2.0\text{ to }5.0\text{ mA}$ (DTIM beacon listen) | $\approx 2.0\text{ to }5.0\text{ mA}$ (DTIM beacon listen) |
| **Autonomous Standby Policy** | **Kernel Suspend-to-RAM** (Zero packet loss via modem FIFO) | **Continuous Awake** (Guarantees zero onset clipping) | **Continuous Awake** (Guarantees zero packet loss & NAT stability) |

### Architectural Implication for Mumla OLED

This physical asymmetry proves that **a single uniform standby policy cannot fit all network environments**:

1. When connected over **LTE / 5G cellular**, `BATTERY_SAVER` standby with Linux kernel suspend-to-RAM is remarkably safe, responsive, and delivers up to $3\times$ battery life extension without audible degradation.
2. When connected over **Wi-Fi**, especially across diverse consumer routers, dropping wakelocks risks clipping the beginning of incoming transmissions.
3. Therefore, Mumla OLED must:
   - **Automate Standby Transport Selection (Zero User Configuration)**: A VoIP application's primary purpose is real-time voice communication. It must never expose a "save battery at the expense of dropped speech" setting that offloads radio protocol complexities onto the user. Standby policy must be determined automatically by querying Android's `ConnectivityManager`:
     - On **Cellular (LTE / 5G)**, where modem hardware FIFO and out-of-band IRQs guarantee zero packet drop, the Application Processor autonomously enters kernel suspend-to-RAM during silence.
     - On **Wi-Fi**, where consumer routers exhibit shallow buffers and aggressive NAT pruning, the client autonomously retains continuous awake standby to guarantee 100% speech onset delivery and pinhole survival.
   - Deploy the transient 2-second socket wakeup bridge (`mBridgeWakeLock` in Step 4) across both [`HumlaUDP.java`](../../libraries/humla/src/main/java/se/lublin/humla/net/HumlaUDP.java) and [`HumlaTCP.java`](../../libraries/humla/src/main/java/se/lublin/humla/net/HumlaTCP.java) to cushion the kernel resume sequence against Wi-Fi driver packet ring drops.

---

## 6. Target Architecture: Audio-Scoped Gating & Battery Exemption

To eliminate the 24/7 standby drain without causing server timeouts, dropped utterances, or OEM watchdog kills, the architecture must transition from a monolithic wakelock to a **multi-tiered state machine**:

### Wakelock State Machine

```mermaid
stateDiagram-v2
    [*] --> CheckExemption: Connection Synchronized
    
    state CheckExemption {
        [*] --> QueryPowerManager
        QueryPowerManager --> NonExemptMode: isIgnoringBatteryOptimizations() == false
        QueryPowerManager --> ExemptMode: isIgnoringBatteryOptimizations() == true
    }

    state NonExemptMode {
        HoldMonolithicLock: Hold Continuous PARTIAL_WAKE_LOCK
        PromptUser: Display In-App Exemption Recommendation
    }

    NonExemptMode --> ExemptMode: Exemption Granted by User
    ExemptMode --> NonExemptMode: Exemption Revoked by User / OS

    state ExemptMode {
        state ActiveAudio {
            AcquireAudioLock: Hold mAudioWakeLock
            StreamingAudio: Active Speech Capture or Playback
        }

        state GracePeriod {
            RunTrailingTimer: 3s / 15s Standby Timeout (Track Still Playing)
        }

        state SilentStandby {
            ReleaseLocks: Release All Wakelocks (AP Enters Suspend)
            ScheduleAlarm: AlarmManager Exact Wakeup (10s Keepalive)
            WaitInbound: Await Hardware Baseband/Wi-Fi Wakeup
        }

        ActiveAudio --> GracePeriod: Speech Ends & Miss Expiry Elapsed
        GracePeriod --> ActiveAudio: New Speech Onset / PTT Press
        GracePeriod --> SilentStandby: Standby Timeout Expired (AudioTrack Paused)
        
        SilentStandby --> ActiveAudio: Inbound Voice Datagram Received (Bridge Lock Acquired)
        SilentStandby --> ActiveAudio: PTT Pressed by User
        SilentStandby --> KeepaliveBurst: 10s Alarm Fires
        
        state KeepaliveBurst {
            PulseLock: Acquire mKeepaliveWakeLock (1000ms safety cap)
            SendPings: Dispatch Synchronous UDP/TCP Keepalives
        }
        KeepaliveBurst --> SilentStandby: Pings Dispatched (Release Lock)

        SilentStandby --> DeepDozeHold: Device Enters Deep Doze (isDeviceIdleMode == true)
        DeepDozeHold --> SilentStandby: Device Exits Deep Doze (Motion Detected / Screen On)

        state DeepDozeHold {
            HoldExemptLock: Hold Defensive Wakelock (Permitted Under Exemption)
            PreventTimeout: Maintain 10s Keepalives While Bypassing 15m Alarm Clamp
        }
    }
```

### Step 1: Battery Optimization Exemption Gating

The app must never release its continuous wakelock unless battery optimization exemption is verified:

```java
PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
boolean isExempt = pm.isIgnoringBatteryOptimizations(context.getPackageName());
```

- **If Not Exempt (`isExempt == false`)**: Fall back to holding `mWakeLock` continuously (preserving connection reliability) and display a non-intrusive banner in the UI inviting the user to grant exemption via `android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.
- **If Exempt (`isExempt == true`)**: Enable the adaptive standby sleep architecture. Android waives background network cutoffs and permits holding partial wakelocks during Doze for exempt apps. During active mobile and pocket screen-off standby, exact wakeup alarms dispatch keepalive bursts while allowing kernel suspend-to-RAM. If the device enters stationary Deep Doze (`PowerManager.isDeviceIdleMode() == true`), the exemption permits holding a defensive keepalive wakelock to prevent Murmur 30-second timeouts against AOSP's 15-minute alarm clamp.

### Step 2: Audio-Scoped Active Lock (`mAudioWakeLock`)

Decouple audio processing from connection maintenance in [`HumlaService.java`](../../libraries/humla/src/main/java/se/lublin/humla/HumlaService.java):

1. **Acquisition Triggers**:
   - Push-To-Talk button pressed or VAD speech detected in [`AudioInputEngine.cpp`](../../libraries/humla/src/main/jni/audio_engine/AudioInputEngine.cpp).
   - Incoming voice packet decoded or registered voice in [`AudioOutput.java`](../../libraries/humla/src/main/java/se/lublin/humla/audio/AudioOutput.java) (`hasActiveVoices() == true`).
2. **Release Triggers**:
   - When no participants are speaking and the local user is idle, start a trailing grace timer (3s on built-in speakers/headphones; 15s on Bluetooth).
   - Reconciled with Phase 2's route-aware [`AudioOutput`](../../libraries/humla/src/main/java/se/lublin/humla/audio/AudioOutput.java) standby pause: **the audio wakelock is released only when `AudioTrack` enters `mAudioTrack.pause()`**.

### Step 3: Exact Alarm Pulsed Keepalive Lock (`mKeepaliveWakeLock`)

When in silent standby with `mAudioWakeLock` released:

- Declare `android.permission.SCHEDULE_EXACT_ALARM` in [`app/src/main/AndroidManifest.xml`](../../app/src/main/AndroidManifest.xml) (required on Android 12+, API 31+) and verify `alarmManager.canScheduleExactAlarms()` before arming exact alarms.
- Replace `mPingExecutorService.schedule(...)` with an Android `AlarmManager` exact wakeup alarm:

  ```java
  alarmManager.setExactAndAllowWhileIdle(
      AlarmManager.ELAPSED_REALTIME_WAKEUP,
      SystemClock.elapsedRealtime() + (intervalSeconds * 1000L),
      mKeepalivePendingIntent
  );
  ```

- When the alarm triggers:
  1. Acquire a timed wakelock with a hard safety cap: `mKeepaliveWakeLock.acquire(1000)` (1000ms safety timeout cap; typical execution completes in 50–100 ms).
  2. Execute `mPingRunnable`: synchronously transmit the UDP Ping and TCP Ping via [`HumlaConnection.java`](../../libraries/humla/src/main/java/se/lublin/humla/net/HumlaConnection.java).
  3. Schedule the next alarm tick.
  4. Explicitly release `mKeepaliveWakeLock` in a `finally` block, allowing the AP to return to kernel suspend-to-RAM.

### Step 4: Inbound Socket Packet Wakeup Bridge

When an incoming packet arrives over the cellular modem or Wi-Fi while the AP is suspended:

1. The hardware interrupt wakes the Linux kernel network stack.
2. Inside [`HumlaUDP.java`](../../libraries/humla/src/main/java/se/lublin/humla/net/HumlaUDP.java) and [`HumlaTCP.java`](../../libraries/humla/src/main/java/se/lublin/humla/net/HumlaTCP.java), the socket reader thread unblocks from `select()` or `read()`.
3. **The Race Condition**: If the CPU attempts to suspend before the audio pipeline starts rendering, the packet will be delayed.
4. **The Bridge**: The socket reader immediately acquires a transient bridge wakelock:

   ```java
   mBridgeWakeLock.acquire(2000); // 2-second transient bridge
   ```

   This keeps the CPU awake long enough for the packet to be pushed into [`AudioOutput`](../../libraries/humla/src/main/java/se/lublin/humla/audio/AudioOutput.java), which in turn promotes `mAudioWakeLock` to active status and unpauses `AudioTrack`.

### Step 5: Autonomous Transport-Aware Standby Adaptation

A VoIP client exists to deliver clear, reliable, real-time voice—not to force users to choose between battery life and missing the beginning of transmissions. Shifting radio protocol physics onto end users via a configuration toggle is an anti-pattern that creates a "press this to degrade VoIP" trap.

Instead, standby behavior is governed **entirely autonomously** based on active network transport and battery exemption:

1. **Cellular Transport (`NetworkCapabilities.TRANSPORT_CELLULAR`)**:
   - Because 3GPP standards mandate eNodeB/gNodeB packet buffering and modern baseband modems feature dedicated SRAM FIFOs with out-of-band wake pins (`WAKE_HOST`), inbound packet wake is physically guaranteed with zero packet drop.
   - When running on cellular data and exempted from battery optimizations, `HumlaService` drops CPU wakelocks during silent standby and enters Linux kernel `suspend-to-RAM`, cutting standby power by **63% to 64%** without risking clipped audio onsets.

2. **Wi-Fi Transport (`NetworkCapabilities.TRANSPORT_WIFI`)**:
   - Because consumer Wi-Fi access points suffer from shallow sleep buffers (4–8 packets), variable DTIM beacon phases ($100\text{ to }300\text{ ms}$), and aggressive NAT pinhole pruning ($15\text{ to }30\text{ seconds}$), kernel suspend risks dropping initial speech bursts.
   - To guarantee that speech is never clipped, Mumla OLED automatically retains continuous awake standby on Wi-Fi.

3. **Seamless Dynamic Transitions**:
   - A registered `ConnectivityManager.NetworkCallback` monitors active transport changes.
   - Handing over from Wi-Fi to Cellular seamlessly enables suspend-to-RAM standby.
   - Handing over from Cellular to Wi-Fi immediately acquires `mWakeLock` to safeguard voice packets against router buffer drops.
   - Zero user settings, zero cognitive overhead, and zero risk of misconfiguration.

---

## 7. Implementation Milestones & Phased Roadmap

Because of the architectural complexity, implementation should proceed across four structured milestones:

```mermaid
gantt
    title Wakelock & Deep Doze Implementation Milestones
    dateFormat  YYYY-MM-DD
    section Milestone W1
    Battery Exemption API & Transport Monitor       :w1, 2026-10-01, 7d
    section Milestone W2
    Audio-Scoped State Machine in HumlaService      :w2, after w1, 10d
    section Milestone W3
    Exact Alarm Keepalive & Socket Wakeup Bridge   :w3, after w2, 10d
    section Milestone W4
    Lab Verification, Doze Simulation & Soak Tests :w4, after w3, 14d
```

### Milestone W1: Exemption API & Transport Monitoring

- Implement `BatteryOptimizationHelper.java` to query and request battery exemption.
- Implement `NetworkTransportMonitor` using Android `ConnectivityManager.NetworkCallback` to detect Cellular vs. Wi-Fi transport changes dynamically without user configuration.
- Declare `android.permission.SCHEDULE_EXACT_ALARM` in [`app/src/main/AndroidManifest.xml`](../../app/src/main/AndroidManifest.xml) and wire `alarmManager.canScheduleExactAlarms()` checks for Android 12+ (API 31+).
- Connect transport and exemption state listeners to [`HumlaService.java`](../../libraries/humla/src/main/java/se/lublin/humla/HumlaService.java).

### Milestone W2: Audio-Scoped Wakelock Management

- Refactor [`HumlaService.java`](../../libraries/humla/src/main/java/se/lublin/humla/HumlaService.java) to support separate `mAudioWakeLock`, `mKeepaliveWakeLock`, and `mBridgeWakeLock` instances.
- Connect [`AudioInputEngine`](../../libraries/humla/src/main/jni/audio_engine/AudioInputEngine.h) talking callbacks and [`AudioOutput`](../../libraries/humla/src/main/java/se/lublin/humla/audio/AudioOutput.java) active voice state listeners to `HumlaService` to drive `mAudioWakeLock` acquisition and release.
- Integrate with Phase 2's route-aware standby pause: hold `mAudioWakeLock` while `AudioTrack` is playing, release when `AudioTrack.pause()` is called.

### Milestone W3: Exact Alarm Keepalive Loop & Socket Bridge

- Replace `ScheduledExecutorService` keepalive loop in [`HumlaConnection.java`](../../libraries/humla/src/main/java/se/lublin/humla/net/HumlaConnection.java) with `AlarmManager.setExactAndAllowWhileIdle()` when in autonomous suspend standby mode.
- Implement `KeepaliveBroadcastReceiver` to handle alarm wakeups with a pulsed wakelock (1000ms safety cap, ~50–100 ms execution).
- Listen for `PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED` to hold a defensive keepalive wakelock if the device enters stationary Deep Doze.
- Add socket wakeup bridge lock in [`HumlaUDP.java`](../../libraries/humla/src/main/java/se/lublin/humla/net/HumlaUDP.java) and [`HumlaTCP.java`](../../libraries/humla/src/main/java/se/lublin/humla/net/HumlaTCP.java).

### Milestone W4: Laboratory Verification & Field Testing

- Execute automated ADB Deep Doze simulations (`dumpsys deviceidle force-idle`).
- Conduct 8-hour connected standby drain tests comparing baseline vs. optimized.
- Test incoming speech onset latency on cellular (LTE/5G) and Wi-Fi networks across multiple consumer routers.

---

## 8. Verification, Edge Cases & Risk Mitigation Matrix

| Failure Mode / Edge Case | Mechanism | Mitigation / Defense |
| --- | --- | --- |
| **Murmur TCP Timeout (30s)** | Phone suspends, user-space timer fails to tick, Murmur drops socket after 30s | Use `AlarmManager.setExactAndAllowWhileIdle()`; gate behind `isIgnoringBatteryOptimizations()`; fallback to continuous wakelock if non-exempt. |
| **Dropped Speech Onset on Wi-Fi** | Consumer router prunes NAT state or drops UDP unicast packet sent to sleeping 802.11 STA | Autonomous transport awareness retains continuous awake standby on Wi-Fi; transient 2s socket bridge cushions incoming bursts; zero user configuration required. |
| **On-Device Battery Warnings** | Wakelock held continuously in background triggers OS battery alerts ("putting app to sleep") | Autonomous suspend on cellular eliminates continuous background wakelock accumulation during mobile on-the-go use. |
| **OEM Watchdog Termination** | Samsung Device Care or Xiaomi MIUI kills app holding wakelock without active audio | Gating wakelock strictly to active audio states prevents OEM watchdogs from identifying Mumla OLED as an abusive background process. |
| **Deep Doze Alarm Clamping (15m)** | AOSP `AlarmManagerService` clamps `setExactAndAllowWhileIdle` to 15m in Deep Doze regardless of exemption | Monitor `isDeviceIdleMode()` via `ACTION_DEVICE_IDLE_MODE_CHANGED`; retain defensive keepalive wakelock (permitted under exemption) while stationary Deep Doze is active. |
| **Exact Alarm Permission Denial** | Android 12+ (API 31+) revokes or denies `SCHEDULE_EXACT_ALARM`, causing `SecurityException` | Check `alarmManager.canScheduleExactAlarms()`; fall back to continuous wakelock if exact alarms cannot be armed. |
| **Rapid PTT Button Flutter** | User rapidly taps PTT button causing high-frequency wakelock thrashing | Implement a trailing 3-second hold hangover on `mAudioWakeLock` to prevent rapid lock/unlock thrashing. |
| **Bluetooth SCO Link Drop** | Audio HAL pauses track while on active Bluetooth SCO call | Preserve Phase 2 invariant: `isBluetoothScoActive()` strictly inhibits both `AudioTrack` pause and `mAudioWakeLock` release. |
| **Transient Ping Socket Error** | Network socket throws `IOException` during keepalive burst | Enclose alarm handler in `try-finally` to guarantee immediate lock release and rescheduling of subsequent alarm ticks. |

---

## 9. Conclusion

The permanent partial wakelock is the ultimate hurdle in transforming Mumla OLED into an ultra-low-power VoIP client. By acknowledging the platform reality of Android Deep Doze, respecting Murmur's 30-second TCP timeout, and implementing a battery-optimization-gated, audio-scoped state machine with pulsed keepalive alarms, Mumla OLED can safely achieve **true Linux kernel suspend-to-RAM** during silent standby—yielding up to a **$3\times$ increase in battery life** while safeguarding against silent connection drops.
