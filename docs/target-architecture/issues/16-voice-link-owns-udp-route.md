# 16 — The voice link, not the transport, decides the initial UDP/TCP route

| | |
|---|---|
| Status | Resolved 2026-11-14 (`aba7011b`): the transport pushes the initial route at start and the link only follows `SetVoiceRoute`. See [Resolution](#resolution). |
| Category | Undocumented interim |
| Severity | Moderate (two writers of a decision the spec gives to one owner; directly relevant to 5a.3) |
| Verified | Yes (code read) |
| Area | Transport / voice link |
| Spec | [transport.md § Keepalive and UDP health](../transport.md#keepalive-and-udp-health), [transport.md § Responsibilities](../transport.md#responsibilities) |
| Code | `libraries/humla/src/main/java/se/lublin/humla/net/LegacyVoiceLink.kt` (`usingUdp`, `startVoice`, `setForceTcp`, `submit`); `net/TransportImpl.kt` (`usingUdpRef`, `handlePing`, `switchToTunnel`, `startKeepalive`); `client/Session.kt` (`SessionStats.initial(usingUdp = !target.forceTcp)`) |
| Gates | Gated step 5a.3b; gate met |

Line numbers below refer to `master` at `092d9dc8`.

## Summary

The spec makes the transport the sole owner of the UDP-vs-tunnel decision:
it publishes it in `TransportStats.usingUdp` and pushes it to voice with
`VoiceCommand.SetVoiceRoute(udp)`, and voice only follows. In the code,
`LegacyVoiceLink` sets its own `usingUdp = true` on `startVoice()` and
`false` on `setForceTcp(true)`. The transport only pushes **changes**
(`SetVoiceRoute(false)` when switching to tunnel, `SetVoiceRoute(true)`
when switching back). For a normal session the transport never tells voice
"UDP"; voice is on UDP because of its own default. The session also seeds
its stats' `usingUdp` from `target.forceTcp`, a third writer.

## What the spec says

`transport.md` § Keepalive and UDP health:

> **The transport alone owns the UDP/TCP decision.** It publishes it in
> `TransportStats.usingUdp` and pushes it to the `VoiceEngine` with
> `VoiceCommand.SetVoiceRoute(udp: Boolean)`; the `VoiceEngine` only
> follows that command and does not report its own idea of the route.

`client.md` § Statistics: "`usingUdp` comes only from the transport, which
owns that decision".

## What the code does

`LegacyVoiceLink.kt`:

```kotlin
private val usingUdp = AtomicBoolean(true)                // line 76

override fun setForceTcp(force: Boolean) {                // line 97
    forceTcp.set(force)
    if (force) usingUdp.set(false)
}

override fun startVoice(host: String, port: Int) {        // line 122
    if (forceTcp.get()) return
    usingUdp.set(true)
    connectUdp(host, port)
}

is VoiceCommand.SetVoiceRoute -> usingUdp.set(command.udp && !forceTcp.get())   // line 228
```

`setForceTcp` is called directly by the client through `VoiceLifecycle`,
not via the transport.

`TransportImpl.kt`: `usingUdpRef` starts from its own initial value;
`switchToTunnel` (around line 560) does
`if (!usingUdpRef.getAndSet(false)) return; ... voice.submit(SetVoiceRoute(false))`;
the switch-back branch in `handlePing` (around line 546) does
`if (!usingUdpRef.compareAndSet(false, true)) ...; voice.submit(SetVoiceRoute(true))`.
Neither `startKeepalive()` nor session start pushes the initial route.

`Session.kt` line 171:
`MutableStateFlow(SessionStats.initial(usingUdp = !target.forceTcp))`.

## Impact

- The link's route and `TransportStats.usingUdp` can disagree (for example
  after a reconnect, if the transport's initial value and the link's reset
  in `startVoice` differ) with no ping evidence to reconcile them until a
  later ping round triggers a switch.
- The spec's invariant cannot be asserted in transport tests with a fake
  link, because the real link does not just follow.
- **For 5a.3:** the native `VoiceEngine` will be written against the spec's
  contract ("only follows `SetVoiceRoute`"). If the transport keeps relying
  on the link's default, a spec-faithful native engine will start in
  whatever its default is, and may never be told "UDP".

## Fix directions

1. In `TransportImpl`, push the initial route explicitly when voice starts
   (or at `ServerSync`/`startKeepalive`): `SetVoiceRoute(!forceTcp)`, and
   make `usingUdpRef`'s initial value consistent with it.
2. Make `LegacyVoiceLink` follow only `SetVoiceRoute` (remove the
   `usingUdp.set(true)` in `startVoice` and the `usingUdp.set(false)` in
   `setForceTcp`, or keep `forceTcp` as a hard override documented in the
   spec).
3. Seed `SessionStats.usingUdp` from `TransportStats`, not from the target
   (see [26](26-stats-two-collectors.md)).
4. If any of this is intended to stay (for example forced TCP as a
   voice-side override), write it into `transport.md` on `master` first.

## Acceptance

- A `TransportImpl` test with a fake `VoiceLink` shows the link receives
  `SetVoiceRoute(true)` at session start (non-forced) and
  `SetVoiceRoute(false)` (or nothing, if forced TCP is a documented
  override) when forced TCP.
- `LegacyVoiceLink`'s route changes only in response to `SetVoiceRoute`.
- `./scripts/check.sh` and murmur interop pass.

## Resolution

The state at `aba7011b`.

- **One writer.** `TransportImpl.start()` submits
  `SetVoiceRoute(!forceTcp)` before its reader runs, then only its two
  switches do. Forced TCP no longer re-pushes at `ServerSync`; it only
  sends the dummy `UDPTunnel` there. A closed transport submits no voice
  commands, since the link outlives it.
- **The link follows.** `LegacyVoiceLink` starts tunnelled; `startVoice`
  and `stopVoice` leave the route alone, and `setForceTcp` is gone from
  `VoiceLifecycle`. The UDP socket opens once voice is started *and* the
  route is UDP, in whichever order they arrive, and stays open across
  tunnel switches until `stopVoice` (as before).
- **Why push at start, not at `ServerSync`.** `HumlaClient.onSynced`
  (client coroutine) and `startKeepalive` (session coroutine) both follow
  `ServerSync` and are not ordered. Pushing at `ServerSync` would let a
  forced-TCP session open UDP on a route the previous session left on
  UDP. Pushed at start, the route is in place before any server message
  is read.
- **Stats seed** (direction 3): `Session` seeds `usingUdp` from
  `transport.stats.value`. The rest of [26](26-stats-two-collectors.md)
  (one `combine` collector) stays open.
- **Spec.** `VoiceCommand.Start` drops its `tunnel` field
  ([voice](../voice.md#kotlin-facade)): a route in `Start` would be a
  second writer, overwriting any switch submitted between the client
  reading the transport's decision and submitting `Start`. The
  `VoiceEngine` keeps the last route across `Stop` and `Start`.
  [transport](../transport.md#keepalive-and-udp-health) records the
  initial push and the closed-transport rule.

**Behavior change in one edge.** If sync takes more than 20 s, the first
TCP ping reply after `ServerSync` can switch to tunnel before voice
starts (the 20 s window is anchored at transport creation; see
[22](22-keepalive-anchored-at-construction.md)). The old reset in
`startVoice` then put the link back on UDP or not depending on thread
order; now the session deterministically stays tunnelled, which is what
the transport publishes.

**Pinned by** `TransportImplTest.initialRouteIsPushedAtStart` and
`forcedTcpTunnelsFromServerSyncAndNeverUsesUdp` (tunnel at start, no
repeat), and `LegacyVoiceLinkContractTest.routeFollowsOnlySetVoiceRoute`.
**Shown to fail** with the push removed, with `startVoice` resetting the
route to UDP, and with the link starting on UDP. `check.sh` and murmur
interop (14/14, including forced TCP and both UDP-blocked fallbacks)
pass.

## Related

- [17](17-udp-format-per-packet.md), [26](26-stats-two-collectors.md),
  [04](04-session-transport-virtual-time-gaps.md)
