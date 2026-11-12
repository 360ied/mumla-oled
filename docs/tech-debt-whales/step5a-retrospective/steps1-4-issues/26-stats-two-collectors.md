# 26 — Session statistics come from two collectors, and `usingUdp` is seeded from the target

| | |
|---|---|
| Category | Spec drift |
| Severity | Benign |
| Verified | Yes (code read) |
| Area | Session statistics |
| Spec | [client.md § Statistics](../../target-architecture/client.md#statistics) |
| Code | `libraries/humla/src/main/java/se/lublin/humla/client/Session.kt` (line 171 and the two `scope.launch` collectors around lines 230-285) |
| Gates | Not gating |

Line numbers below refer to `master` at `092d9dc8`.

## Summary

The spec has the session combine `transport.stats` and `voice.status` with
one `combine` collector, and says `usingUdp` comes only from the
transport. The code runs two separate collectors that both
`_stats.update { ... }` (one on `transport.stats`, reading
`voiceLink.status.value` inside; one on `voiceLink.status`), and
initializes `_stats` with `usingUdp = !target.forceTcp`.

## What the spec says

> The session combines `transport.stats` and `voice.status` into `stats`
> with one `combine` collector in its scope.

> `usingUdp` comes only from the transport, which owns that decision.

## What the code does

`Session.kt` line 171:
`private val _stats = MutableStateFlow(SessionStats.initial(usingUdp = !target.forceTcp))`.

Around line 230: `scope.launch { transport.stats.collect { t -> _stats.update { ... usingUdp = t.usingUdp ... } } }`.
Around line 270: `scope.launch { voiceLink.status.collect { v -> _stats.update { ... } } }`.

## Impact

No wrong values observed (each writer reads the other source's current
value). Duplicate per-emission work, and the session briefly owns a
transport decision (`usingUdp`) before the first transport emission; the
initial value can disagree with the transport's if they are initialized
differently (see [16](16-voice-link-owns-udp-route.md)).

## Fix directions

Replace both collectors with
`combine(transport.stats, voiceLink.status) { t, v -> SessionStats.from(t, v) }`
collected into `_stats` (or `stateIn`), and seed from
`transport.stats.value` rather than the target.

## Acceptance

One collector; `usingUdp` initial value equals `transport.stats.value.usingUdp`;
`./scripts/check.sh` passes.

## Related

- [15](15-current-bandwidth-bypasses-stats.md),
  [16](16-voice-link-owns-udp-route.md)
