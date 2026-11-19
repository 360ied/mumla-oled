# 29 — `VoiceSettings.halfDuplex` carries the raw preference, not the effective value

| | |
|---|---|
| Status | Resolved (spec): `VoiceSettings.halfDuplex` is the raw preference by design; the effective value is derived at the native push. |
| Category | Spec drift |
| Severity | Benign (spec wording only; behavior correct today via `VoiceControl` gating, and in 5a.4 via the native push with no pipeline rebuild) |
| Verified | Yes (code read) |
| Area | Step-4 voice settings |
| Spec | [voice.md § Policy and settings handoff](../voice.md#policy-and-settings-handoff) (`VoiceSettings` data class) |
| Code | `libraries/humla/src/main/java/se/lublin/humla/voice/VoiceSettingsMapper.kt` (raw store; `halfDuplexFor` helper documents the effective predicate); today `voice/VoiceControl.kt` gates at build time, from 5a.4 `voice/VoiceEngine.kt` (`setSettings` / `pushSettings`) gates before the native seqlock |
| Gates | Not gating |

Line numbers below refer to `master` at `092d9dc8`.

## Summary

The spec defined `VoiceSettings.halfDuplex` as the effective value
`Settings.isHalfDuplex && inputMode == PUSH_TO_TALK`. The mapper stores the
raw preference instead; today `VoiceControl` derives the effective value when
building the pipeline, and from 5a.4 `VoiceEngine` derives it at each native
push, so behavior is correct and a transmit-mode round trip preserves the
preference. The spec now documents raw storage with push-time gating.
`VoiceControl` is deleted in 5a.4, so the cited rebuild cost goes away there:
the native push is a seqlock write, not a microphone reacquire.

## What the spec says

```kotlin
val halfDuplex: Boolean,   // Settings.isHalfDuplex && inputMode == PUSH_TO_TALK
```

## What the code does

`VoiceSettingsMapper.kt`:

```kotlin
fun halfDuplexFor(transmitMode: Int, halfDuplex: Boolean): Boolean {
    return halfDuplex && transmitMode == Constants.TRANSMIT_PUSH_TO_TALK
}
...
if (halfDuplex != null) {
    next = next.copy(halfDuplex = halfDuplex)    // raw preference
}
```

`VoiceControl.kt` line 339 (building the handler):
`currentSettings.inputMode == InputMode.PUSH_TO_TALK && currentSettings.halfDuplex`.
Line 191 (`maybeReconfigure`'s "same settings" comparison) compares
`built.settings.halfDuplex == current.halfDuplex`, i.e. the raw values.

## Impact

Toggling the half-duplex preference while in voice-activity or continuous
mode changes the raw value, fails the comparison at line 191, and triggers
a full pipeline rebuild (microphone re-acquired), although the effective
value did not change. Any consumer of `VoiceSettings` that trusts the
spec's meaning would see the raw preference.

## Fix directions

Fix the spec, not the code: document raw storage with push-time gating in
[voice.md § Policy and settings handoff](../voice.md#policy-and-settings-handoff).
Storing the effective value in the mapper would collapse a `PUSH_TO_TALK` with
half duplex on, away to another mode, and back to off. Optionally call
`halfDuplexFor` at the `VoiceEngine` push sites or delete the now-duplicated
predicate.

## Acceptance

`voice.md` states Kotlin `halfDuplex` is raw with effective derived at the
push; the half-duplex round-trip mapper test passes;
`./scripts/check.sh` passes.
