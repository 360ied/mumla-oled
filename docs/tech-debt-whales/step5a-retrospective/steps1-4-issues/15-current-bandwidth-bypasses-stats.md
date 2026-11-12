# 15 — `getCurrentBandwidth` reads `AudioHandler`, not session statistics

| | |
|---|---|
| Status | Documented as an interim in `migration.md` (ground rules, "Other recorded interims"). Choosing one source remains. |
| Category | Undocumented interim |
| Severity | Low (a display value from a second source) |
| Verified | Yes |
| Area | Client statistics / service facade |
| Spec | [client.md § Intents](../../target-architecture/client.md#intents) (`IHumlaSession` table), [client.md § Statistics](../../target-architecture/client.md#statistics) |
| Code | `libraries/humla/src/main/java/se/lublin/humla/HumlaService.kt` (`getCurrentBandwidth`, around line 1255); `client/Session.kt` (stats collectors, around lines 225-285); `client/SessionStats.kt` (`currentBandwidth`); `net/LegacyVoiceLink.kt` (interim encoder target, around line 89); consumer `app/src/main/java/se/lublin/mumla/channel/ServerInfoFragment.kt` (around line 140) |
| Gates | Not gating; disappears or must be resolved when 5a provides `VoiceStatus` |

Line numbers below refer to `master` at `092d9dc8`.

## Summary

The spec says `getCurrentBandwidth` reads `session.stats.value`, and that
`SessionStats.currentBandwidth` is computed from the voice status. The
session does maintain `currentBandwidth`, but nothing reads it.
`HumlaService.getCurrentBandwidth()` instead asks the current
`AudioHandler`, and returns `-1` when there is no handler.

## What the spec says

`client.md` § Intents, `IHumlaSession` table:

> | `getTCPLatency`, `getUDPLatency`, `getMaxBandwidth`, `getCurrentBandwidth` | Read `stats.value` |

`client.md` § Statistics: "`currentBandwidth`:
`calculateAudioBandwidth(VoiceStatus.bitrate, framesPerPacket)`".

## What the code does

`HumlaService.getCurrentBandwidth()` (around line 1255):

```kotlin
if (!isSynchronized()) throw IllegalStateException("Not synchronized")
try {
    val handler = if (::voiceControl.isInitialized) voiceControl.currentHandler() else null
    if (handler != null) return handler.getCurrentBandwidth()
    ...  // falls back to -1
```

`AudioHandler.getCurrentBandwidth()` computes from its own `mBitrate` and
`mFramesPerPacket`.

Meanwhile `Session.kt` (around lines 247 and 274) computes
`SessionStats.currentBandwidth` from `LegacyVoiceLink`'s interim encoder
target tuple (set by `HumlaService` around line 1132, "Interim encoder
target for SessionStats.currentBandwidth until the step-5a ..."). A
repository search finds no reader of `SessionStats.currentBandwidth`
outside `Session.kt`.

`ServerInfoFragment.kt` (around line 140) displays
`session.getCurrentBandwidth()`.

## Impact

- The server info screen shows `-1` (rendered as `-0.001` kbit/s after
  the `/ 1000f`) whenever no handler exists, and a value that can diverge
  from `SessionStats` once encoder degradation changes `AudioHandler`'s
  bitrate.
- `SessionStats.currentBandwidth` is dead code in production, so its
  interim plumbing through `LegacyVoiceLink` is maintained for nothing.

## Fix directions

Either:

1. Make `HumlaService.getCurrentBandwidth()` return
   `currentSession()?.stats?.value?.currentBandwidth ?: -1` (spec), and
   make sure the interim target tuple pushed into `LegacyVoiceLink` tracks
   the encoder's effective bitrate (including degradation); or
2. Record the `AudioHandler` path as an interim until 5a, and delete the
   unused `SessionStats.currentBandwidth` plumbing until `VoiceStatus`
   exists.

Option 1 matches the spec; option 2 is less code. Pick one; do not keep
both sources.

## Acceptance

One source for current bandwidth; `ServerInfoFragment` shows the same value
`SessionStats` would. `./scripts/check.sh` passes.

## Related

- [26](26-stats-two-collectors.md)
