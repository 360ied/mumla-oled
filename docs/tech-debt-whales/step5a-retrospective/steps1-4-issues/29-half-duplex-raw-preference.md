# 29 — `VoiceSettings.halfDuplex` carries the raw preference, not the effective value

| | |
|---|---|
| Category | Spec drift |
| Severity | Benign (effective behavior correct; causes unnecessary pipeline rebuilds) |
| Verified | Yes (code read) |
| Area | Step-4 voice settings |
| Spec | [voice.md § Policy and settings handoff](../../target-architecture/voice.md#policy-and-settings-handoff) (`VoiceSettings` data class) |
| Code | `libraries/humla/src/main/java/se/lublin/humla/voice/VoiceSettingsMapper.kt` (`halfDuplexFor` lines 52-53, unused; `initialFromDefaults` line ~81; `update` lines ~123-124); `voice/VoiceControl.kt` (line 191 in `maybeReconfigure`, line 339 in `buildHandler`) |
| Gates | Not gating |

Line numbers below refer to `master` at `092d9dc8`.

## Summary

The spec defines `VoiceSettings.halfDuplex` as the effective value
`Settings.isHalfDuplex && inputMode == PUSH_TO_TALK`. The mapper has a
helper for exactly that (`halfDuplexFor`) but does not use it; it stores
the raw preference. `VoiceControl` applies the PTT condition itself when
building the pipeline, so behavior is correct, but the published settings
differ from the spec's meaning.

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

Store `halfDuplexFor(transmitMode, halfDuplex)` in the mapper (recompute
when either input changes), and drop the extra PTT condition at
`VoiceControl` line 339. Add a mapper test.

## Acceptance

`VoiceSettings.halfDuplex` is false whenever input mode is not PTT; toggling
the preference outside PTT does not rebuild the pipeline;
`./scripts/check.sh` passes.
