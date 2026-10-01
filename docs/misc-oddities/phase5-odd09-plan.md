# Phase 5 Implementation Plan: ODD-09 — Scale HumlaUDP Send Queue on Bandwidth Throttling

Concrete, commit-ready plan for [Phase 5](remediation-plan.md#phase-5-dynamic-bandwidth--network-adaptation-p2) of the Miscellaneous Oddities remediation
([ODD-09 detailed record](README.md#odd-09-bandwidth-degraded-framesperpacket-fails-to-scale-udp-send-queue)).
This file locks the design decisions left open by the Phase 5 review so the implementing branch has no deliberation left to do.

**Status:** Ready for implementation.
**Scope:** One item — ODD-09 (P2 / Medium). No behavior change except the send-queue rescale described below.

---

## 1. Problem restatement

When server maximum bandwidth forces auto-degradation in `AudioHandler.setMaxBandwidth()`
(`libraries/humla/src/main/java/se/lublin/humla/protocol/AudioHandler.java`), `mFramesPerPacket`
grows (e.g. 2 → 4, i.e. 20 ms → 40 ms packets) and the native encoder is reconfigured, but
`HumlaConnection` / `HumlaUDP` are never told. `HumlaUDP.mSendQueueCapacity` stays at 10 packets
(the 20 ms value), so with 40 ms packets a stalled network buffers
$10 \times 40\text{ ms} = 400\text{ ms}$ of voice — double the ~200 ms target latency ceiling
established in Phase 2 (ODD-03). The fix is to propagate the degraded `framesPerPacket` to the
existing, already-flushing `HumlaConnection.setTargetFramesPerPacket()` path.

Key fact simplifying this plan: **no new queue logic is needed.** `HumlaUDP.setTargetFramesPerPacket()`
(`libraries/humla/src/main/java/se/lublin/humla/net/HumlaUDP.java`) already recalculates capacity
(`ceil(200/40) = 5` for `fpp=4`) and flushes excess stale packets under `mSendLock`. Only the
notification edge is missing.

---

## 2. Locked design decisions

1. **Service-mediated listener, not a direct `AudioHandler → HumlaConnection` reference.**
   `HumlaConnection` already holds the `AudioHandler` in its handler queues; a back-reference would
   create a dependency cycle and conflate the *user-configured* target (`HumlaConnection.mTargetFramesPerPacket`,
   owned by `HumlaService`) with the *bandwidth-degraded effective* value (owned by `AudioHandler`).
   Instead, `AudioHandler` exposes a single-method listener that `HumlaService` wires to the live
   connection. This also keeps `AudioHandler` unit-testable in shape (plain interface, no new instance
   back-reference; static `HumlaConnection` use remains).
2. **Listener is installed before `initialize()` runs.** Degradation is computed inside `initialize()`
   (via the `initialize(maxBandwidth)` path), so wiring it after construction-but-before-init is
   order-sensitive. The `Builder` carries the listener and sets it between `new AudioHandler(...)` and
   `handler.initialize(...)`.
3. **Notify only on `framesPerPacket` change.** Bitrate-only degradation (the `while (bitrate...)` loop)
   needs no queue update; a needless rescale is a wasted cross-thread call and lock acquisition
   (a same-capacity rescale is a no-op — eviction only runs while `size > capacity`). The log line
   still fires on either change, as today.
4. **UDP-Ping `MaxBandwidthPerUser` path is an accepted residual, not in scope.**
   `HumlaConnection.mUDPPingListener.messageProtobufPing()` updates `mMaxBandwidth` on the UDP thread
   and bypasses `AudioHandler` entirely — today neither the encoder nor the queue adapts to it, and this
   plan does not change that. (Closing it needs the reverse notification, `HumlaConnection → AudioHandler`,
   a larger change. Record here so a future reader does not mistake this plan for full coverage.)
5. **Upward recovery is intentionally absent.** `setMaxBandwidth()` only ever *increases* `framesPerPacket`;
   if bandwidth recovers the queue stays small, which is the safe (low-latency) direction. Do not add
   downshifting logic in this branch.
6. **Pure-function extraction for testability.** `AudioHandler` cannot be instantiated in JVM tests
   (needs `Context`, `AudioInput`/`AudioOutput`, `NativeAudioInputEngine`, and `mLogger`/`mContext` for
   logging). The degradation decision is therefore extracted into a static, Android-API-free function
   covered by a JVM test in the existing JUnit-3 (`extends TestCase`) style. The thin wiring
   (listener invocation, service lambda) is verified by code review plus the manual check in §6.

---

## 3. Implementation steps

### Step 1 — `AudioHandler.java`: pure helper + listener

Add a nested value type, a pure computation, a listener interface, and rewire `setMaxBandwidth()`
to use them. Sketch (names are normative; bodies adapt to surrounding style):

```java
/** Degraded effective audio config produced by bandwidth adaptation. Pure value type. */
public static final class EffectiveAudioConfig {
    public final int bitrate;
    public final int framesPerPacket;

    public EffectiveAudioConfig(int bitrate, int framesPerPacket) {
        this.bitrate = bitrate;
        this.framesPerPacket = framesPerPacket;
    }
}

/** Notified when bandwidth adaptation changes the effective frames-per-packet. */
public interface FramesPerPacketListener {
    void onEffectiveFramesPerPacketChanged(int framesPerPacket);
}

private volatile FramesPerPacketListener mFramesPerPacketListener;

public void setFramesPerPacketListener(FramesPerPacketListener listener) {
    mFramesPerPacketListener = listener;
}

/**
 * Pure bandwidth-degradation decision: Android-API-free so JVM tests can cover it.
 * Do not add logging, native calls, or context access here.
 */
static EffectiveAudioConfig computeEffectiveConfig(int bitrate, int framesPerPacket, int maxBandwidth) {
    if (maxBandwidth == -1) {
        return new EffectiveAudioConfig(bitrate, sanitizeFramesPerPacket(framesPerPacket));
    }
    int degradedBitrate = bitrate;
    int degradedFpp = framesPerPacket;
    if (HumlaConnection.calculateAudioBandwidth(degradedBitrate, degradedFpp) > maxBandwidth) {
        if (degradedFpp <= 4 && maxBandwidth <= 32000) {
            degradedFpp = 4;
        } else if (degradedFpp == 1 && maxBandwidth <= 64000) {
            degradedFpp = 2;
        } else if (degradedFpp == 2 && maxBandwidth <= 48000) {
            degradedFpp = 4;
        }
        while (HumlaConnection.calculateAudioBandwidth(degradedBitrate, degradedFpp) > maxBandwidth
                && degradedBitrate > 8000) {
            degradedBitrate -= 1000;
        }
    }
    degradedBitrate = Math.max(8000, degradedBitrate);
    degradedFpp = sanitizeFramesPerPacket(degradedFpp);
    return new EffectiveAudioConfig(degradedBitrate, degradedFpp);
}

private void setMaxBandwidth(int maxBandwidth) {
    EffectiveAudioConfig config = computeEffectiveConfig(mBitrate, mFramesPerPacket, maxBandwidth);
    if (config.bitrate == mBitrate && config.framesPerPacket == mFramesPerPacket) {
        return;
    }
    int oldFramesPerPacket = mFramesPerPacket;
    mBitrate = config.bitrate;
    mFramesPerPacket = config.framesPerPacket;
    if (mNativeEngine != null) {
        mNativeEngine.setBitrate(mBitrate);
        mNativeEngine.setFramesPerPacket(mFramesPerPacket);
    }
    mLogger.logInfo(mContext.getString(R.string.audio_max_bandwidth,
            maxBandwidth / 1000, mBitrate / 1000, mFramesPerPacket * Constants.FRAME_DURATION_MS));
    if (config.framesPerPacket != oldFramesPerPacket) {
        FramesPerPacketListener listener = mFramesPerPacketListener; // volatile read
        if (listener != null) {
            listener.onEffectiveFramesPerPacketChanged(config.framesPerPacket);
        }
    }
}
```

Notes:

- The branch table, bitrate loop, 8000 floor, `-1` early-out, and log-on-either-change semantics are
  behavior-preserving for all reachable inputs — this step is a pure refactor plus the guarded notify.
  (Strictly, the sketch also sanitizes on the `-1` path where the current code early-returns; this is
  unobservable because the constructor already sanitizes, so all reachable inputs are sanitized.)
- Invoking the listener while holding the `AudioHandler` monitor (the `initialize()` path is
  `synchronized`) is safe and intentional: the downstream chain
  (`HumlaConnection.setTargetFramesPerPacket` → `HumlaUDP.setTargetFramesPerPacket`) takes only the
  short `HumlaUDP.mSendLock`, and nothing in the codebase takes the locks in the reverse order, so no
  deadlock cycle exists. Do not restructure locking to "fix" this.
- `mFramesPerPacketListener` must be `volatile`: it is written on the service/main thread and read on
  the TCP thread (`messageServerSync` path).

### Step 2 — `AudioHandler.Builder`: carry the listener

Add a `FramesPerPacketListener` field plus setter to the `Builder` (mirroring `setEncodeListener`), and
with the corresponding Builder field:
```java
private AudioHandler.FramesPerPacketListener mFramesPerPacketListener;
```
Install it in `Builder.initialize()` strictly between construction and `handler.initialize(...)`:

```java
public Builder setFramesPerPacketListener(AudioHandler.FramesPerPacketListener listener) {
    mFramesPerPacketListener = listener;
    return this;
}

// in initialize(), before handler.initialize(...):
handler.setFramesPerPacketListener(mFramesPerPacketListener);
```

A null listener (tests, other callers) must remain legal — `setMaxBandwidth()` already null-guards.

### Step 3 — `HumlaService.java`: wire the listener once

In `onCreate()`, where `mAudioBuilder` is assembled, add a single permanent wiring whose connection is
resolved at call time with a disconnect-race null guard:

```java
mAudioBuilder = new AudioHandler.Builder()
        ...
        .setFramesPerPacketListener(fpp -> {
            HumlaConnection connection = mConnection;
            if (connection != null) {
                connection.setTargetFramesPerPacket(fpp);
            }
        });
```

Do **not** touch the existing `EXTRAS_FRAMES_PER_PACKET` path in `configureExtras()` (user-setting changes
already push to both builder and connection) and do **not** write the degraded value back into
`mAudioBuilder` — reconnects must start from the user setting, which `connect()` already applies via
`mConnection.setTargetFramesPerPacket(mAudioBuilder.getTargetFramesPerPacket())`.

### Step 4 — Adjacent hardening on the touched path (no side-backlog)

Two pre-existing hazards sit directly on the code this branch touches; fix them here rather than
filing follow-ups:

1. **Sanitize in `HumlaConnection.setTargetFramesPerPacket()`.** The setter stores the raw value while
   `HumlaUDP` sanitizes, so an invalid `fpp` (e.g. `3` via `configureExtras`) leaves the connection
   reporting `3` while UDP uses `2`. One-line fix (`Constants` is already imported):
   ```java
   public void setTargetFramesPerPacket(int targetFramesPerPacket) {
       mTargetFramesPerPacket = Constants.sanitizeFramesPerPacket(targetFramesPerPacket);
       final HumlaUDP udp = mUDP;
       if (udp != null) {
           udp.setTargetFramesPerPacket(mTargetFramesPerPacket);
       }
   }
   ```
   Note the delegation must forward the sanitized field, not the raw parameter, so both layers agree.
   `HumlaUDP.java` itself needs no production-code changes.
2. **`volatile` on `AudioHandler.mBitrate` / `AudioHandler.mFramesPerPacket`.** Both are written from
   the main thread (`initialize()`) and the TCP thread (`messageServerSync()`) and read via getters
   from other threads; the new `volatile` listener does not harden them. One word each, zero
   behavioral risk.

### Step 5 — Refresh stale line references in `remediation-plan.md`

Phase 5 cites `HumlaConnection.java#L441` (now ~L484) and `HumlaService.java#L685` (now `connect()` ~L340,
`createAudioHandler()` ~L610–628, `configureExtras(EXTRAS_FRAMES_PER_PACKET)` ~L739–744). Update the
`Component` line of §5.1 to the symbol links without stale pins as part of the branch (docs-only hunk in
an otherwise code branch is acceptable here since it documents the same item).

---

## 4. Edge cases

| Case | Expected handling |
|---|---|
| `maxBandwidth == -1` (server unset) | `computeEffectiveConfig` returns input (sanitized); `setMaxBandwidth` no-ops; no notify. Unchanged behavior. |
| Bitrate-only degradation, `fpp` unchanged | Fields/native/log update as today; listener NOT fired; queue untouched. |
| User `fpp=6` (60 ms) under constraint | Branch table leaves `fpp=6` (only bitrate loop applies); capacity stays `ceil(200/60)=4`. Correct per formula. |
| Invalid `fpp` input | `sanitizeFramesPerPacket` fallback preserved inside the helper (same as today). |
| Invalid `fpp` via `setTargetFramesPerPacket` (e.g. `3`) | Sanitized once in `HumlaConnection` (Step 4); connection and UDP agree on `2`, capacity 10. |
| `ForceTCP` mode | Rescale is harmless — queued-but-unsent packets simply never accumulate via UDP. No special-casing. |
| Disconnect race on late `messageServerSync` | Service lambda null-guards `mConnection`. `HumlaUDP.setTargetFramesPerPacket` on a disconnected instance only adjusts numbers under lock. |
| `Builder` without listener (other/future callers) | Null listener is legal; degradation still applies to the encoder, queue just isn't rescaled. Same as today's behavior. |

---

## 5. Automated tests

New file `libraries/humla/src/test/java/se/lublin/humla/protocol/AudioBandwidthDegradationTest.java`
(JUnit-3 `extends TestCase` style, matching the module's existing tests; GPL header copied from a recent
file, `Copyright (C) 2026 Brian Zhu`). It exercises **only** the pure `computeEffectiveConfig` — no Android
APIs involved. Vectors (all hand-verified against `calculateAudioBandwidth`: overhead
$(20+8+4+1+2+12+\mathrm{fpp}) \times (800/\mathrm{fpp}) + \mathrm{bitrate}$, integer division —
`800/6 = 133`):

| Input (bitrate, fpp, maxBW) | Expected | Rationale |
|---|---|---|
| `(40000, 2, -1)` | `(40000, 2)` | Unset server limit: no-op. |
| `(40000, 2, 72000)` | `(40000, 2)` | $59600 \le 72000$: no-op. |
| `(40000, 1, 64000)` | `(40000, 2)` | $78400 > 64000$, `fpp==1` branch; $59600 \le 64000$ stops the loop. |
| `(40000, 2, 48000)` | `(37000, 4)` | `fpp==2` branch → $50200 > 48000$ → bitrate loop to $47200 \le 48000$. |
| `(40000, 2, 32000)` | `(21000, 4)` | `fpp<=4` branch → $50200 > 32000$ → loop to $31200 \le 32000$. |
| `(9000, 2, 8000)` | `(8000, 4)` | Bitrate floor holds at 8000. |
| `(40000, 3, -1)` | `(40000, 2)` | Invalid `fpp` sanitized to default. |

Queue-level coverage: extend `HumlaUDPSendQueueTest.java` with methods asserting the exact degraded
configuration end to end — `new HumlaUDP(mClientCrypt, mDummyListener, mDummyHandler, 4)` yields
capacity `5` (the 40 ms row of the existing capacity table, now pinned as the degradation target),
and `setTargetFramesPerPacket(3)` leaves `getTargetFramesPerPacket() == 2` with capacity 10 (the Step 4
agreement pin). No new eviction test is needed; the shrink/flush path is already covered by
`testSendQueueDynamicResizeFlushesExcessPackets`.

Run: `nix develop --command ./gradlew testFossDebugUnitTest` during development; full `./scripts/check.sh`
in the worktree before completion.

---

## 6. Manual / device verification

1. Configure a Murmur test server with bandwidth throttled to 32 kbps (or otherwise advertise
   `maxBandwidth <= 32000`); connect Mumla OLED with default 20 ms audio settings.
2. Confirm `logcat` shows the existing `audio_max_bandwidth` line reporting 40 ms packets
   (`framesPerPacket * 10 ms`).
3. Confirm the live `HumlaUDP` send-queue capacity is 5, not 10 (debugger or transient logging).
4. Throttle the uplink to 0 kbps for ~5 s while holding PTT, then release the throttle: voice resumes
   within ~200 ms with no multi-second stale burst (same procedure as the ODD-03 check, now under
   degraded packet size).

---

## 7. Acceptance criteria

- [ ] Degraded `fpp` from both `initialize()` and late `messageServerSync()` reaches a live `HumlaUDP`
      via the service listener; capacity for 40 ms audio becomes 5 with excess stale packets flushed.
- [ ] Listener fires only on `fpp` change; bitrate-only adaptation leaves the queue alone.
- [ ] `HumlaConnection` sanitizes `fpp` on set (connection/UDP agreement pinned); `AudioHandler`
      `mBitrate`/`mFramesPerPacket` are `volatile`.
- [ ] `AudioBandwidthDegradationTest` passes with the vector table in §5; new-file GPL header present.
- [ ] Queue-capacity pin (`fpp=4 → 5`) plus sanitize-agreement pin (`3 → 2`) added to
      `HumlaUDPSendQueueTest`; full JVM suite green.
- [ ] Manual check in §6 performed against a bandwidth-limited server.
- [ ] `remediation-plan.md` §5.1 line references refreshed; ODD-09 status flipped to Resolved with the
      branch/commit recorded (same convention as Phases 1–4) once merged.
- [ ] `./scripts/check.sh` green in the worktree. No merge, push, or worktree deletion (per repo policy —
      leave the branch for review).

---

## 8. Work plan (repo mechanics)

- Worktree/branch: `./scripts/worktree.py add feature/oddities-phase5-odd09` (root stays on `master`).
- Commits via `python3 scripts/commit.py -m "<scope>: <subject>"` with the three-section body
  (`Context & Motivation` / `Technical Approach` / `Edge Cases & Impact`); suggested split is
  (1) pure-helper refactor + unit test, (2) listener + service wiring + adjacent hardening,
  (3) docs touch-up (`remediation-plan.md` pins, ODD-09 status on merge).
- New test file needs the standard GPL-3.0-or-later header with `Copyright (C) 2026 Brian Zhu`.
