# Miscellaneous Oddities Round-2 Remediation Plan

Prioritized engineering plan for the residual findings cataloged in ([`README.md`](README.md)) (ODD-21–ODD-27). All seven items are **Low** severity — latent defects, stale docs, and test debt — so the plan is ordered by blast radius (crash paths first, testability last) rather than by severity. ODD-24 is already resolved on `master` and recorded here for completeness.

## Table of Contents

1. [Phase 1: Crash-Path Hardening (ODD-25, ODD-27)](#phase-1-crash-path-hardening-odd-25-odd-27)
2. [Phase 2: Preference & Doc Hygiene (ODD-21, ODD-22)](#phase-2-preference--doc-hygiene-odd-21-odd-22)
3. [Phase 3: Lifecycle & Testability (ODD-26, ODD-23)](#phase-3-lifecycle--testability-odd-26-odd-23)
4. [Resolved: Sticky `mForceTcp` (ODD-24)](#resolved-sticky-mforcetcp-odd-24)
5. [Verification & Test Strategy](#verification--test-strategy)

---

## Phase 1: Crash-Path Hardening (ODD-25, ODD-27)

Both items are unguarded dereferences/parses on paths reachable with corrupted state or off-nominal call timing. One-line fixes each; do them together.

### 1.1 Null-guard `setVoiceTargetId` (ODD-25)

**Component**: [`HumlaService.java:1523-1530`](../../libraries/humla/src/main/java/se/lublin/humla/HumlaService.java#L1523-L1530)

**Problem**: `mAudioHandler.setVoiceTargetId(targetId)` dereferences `mAudioHandler` without a null check. The handler is null while disconnected, so any future disconnected caller gets an NPE. Neighboring `setTalkingState` already null-guards the same field.

**Solution**: Null-guard and drop the live call when no handler exists:

```java
mVoiceTargetId = targetId;
if (mAudioHandler != null) {
    mAudioHandler.setVoiceTargetId(targetId);
}
```

Dropping (rather than deferring) is safe: the stored `mVoiceTargetId` is re-applied to the fresh pipeline at init ([`HumlaService.java:873`](../../libraries/humla/src/main/java/se/lublin/humla/HumlaService.java#L873)), so no target update is lost across a disconnect/reconnect.

**Acceptance criteria**: `setVoiceTargetId` callable with `mAudioHandler == null` (unit test with a null handler seam or a disconnected service harness); existing voice-target tests pass.

### 1.2 Guard the input-rate parse (ODD-27)

**Component**: [`Settings.java:245-247`](../../app/src/main/java/se/lublin/mumla/Settings.java#L245-L247)

**Problem**: `getInputSampleRate()` calls `Integer.parseInt` on a raw preference string with no fallback, while the adjacent `getFramesPerPacket()` catches `NumberFormatException`. A hand-edited or backup-restored `PREF_INPUT_RATE` value crashes the connect path on any read of the corrupted value.

**Solution**: Mirror the neighbor's `try/catch` fallback. Fall back to the numeric default rate on `NumberFormatException`:

```java
public int getInputSampleRate() {
    try {
        return Integer.parseInt(preferences.getString(Settings.PREF_INPUT_RATE, DEFAULT_RATE));
    } catch (NumberFormatException e) {
        return Integer.parseInt(DEFAULT_RATE);
    }
}
```

**Acceptance criteria**: JVM test pins valid-string passthrough and garbage-string fallback; connect path no longer throws on corrupted prefs.

---

## Phase 2: Preference & Doc Hygiene (ODD-21, ODD-22)

Dead state and a stale bound description. No behavior change in either fix.

### 2.1 Resolve the dead preprocessor toggle (ODD-21)

**Component**: [`Settings.java:459-461`](../../app/src/main/java/se/lublin/mumla/Settings.java#L459-L461), [`ServerConnectTask.java:87`](../../app/src/main/java/se/lublin/mumla/app/ServerConnectTask.java#L87)

**Problem**: `isPreprocessorEnabled()` returns `true` unconditionally while `PREF_PREPROCESSOR_ENABLED` and `DEFAULT_PREPROCESSOR_ENABLED` exist but are never read, so `EXTRAS_ENABLE_PREPROCESSOR` can never be `false` and the RNNoise preprocessor cannot be disabled at runtime. The key has no settings-screen entry (no XML references it) and no other readers, so both directions are safe.

**Solution** (owner picks one; recommended first):
1. **Wire it (recommended)**: return `preferences.getBoolean(PREF_PREPROCESSOR_ENABLED, DEFAULT_PREPROCESSOR_ENABLED)` and add a settings-screen toggle, so the dead key becomes a real user control.
2. **Remove it**: delete the key, default, and the `EXTRAS_ENABLE_PREPROCESSOR` plumbing if the preprocessor-on default is a deliberate product decision.

**Acceptance criteria**: no dead key remains — either the accessor reads the preference behind a visible toggle, or the key/default/extra are gone (grep).

### 2.2 Fix the render-lead bound doc (ODD-22)

**Component**: [`audio-output/README.md:37`](../audio-output/README.md#L37)

**Problem**: The reference says the render-lead bound is "1 quantum, or the track minimum", but [`AudioOutput.java:574-575`](../../libraries/humla/src/main/java/se/lublin/humla/audio/AudioOutput.java#L574-L575) computes `max(renderSamples, min(renderSamples * 2, trackFrames))` — up to 2 quanta (~40 ms), intentionally matching the native jitter margin. The code and its comment are correct; only the doc is stale.

**Solution**: One-line doc fix — "up to 2 quanta (~40 ms), or the track minimum".

**Acceptance criteria**: doc bound matches the `Pacer` computation; no code change.

---

## Phase 3: Lifecycle & Testability (ODD-26, ODD-23)

A fragment lifecycle mirror fix and the only test-debt item in the batch.

### 3.1 Move the listener unregister to `onDestroyView` (ODD-26)

**Component**: [`ChannelFragment.java:207`](../../app/src/main/java/se/lublin/mumla/channel/ChannelFragment.java#L207), [`ChannelFragment.java:307-310`](../../app/src/main/java/se/lublin/mumla/channel/ChannelFragment.java#L307-L310)

**Problem**: The shared-preference listener is registered in `onActivityCreated` but unregistered in `onDestroy` rather than `onDestroyView`, so the retained listener survives view recreations. Latent today (callbacks only touch null-guarded view state), but the asymmetry leaks the fragment as a listener across every rotation.

**Solution**: Move the `unregisterOnSharedPreferenceChangeListener` call to `onDestroyView`, mirroring the view lifecycle. Safe: the fragment is still attached to its activity in `onDestroyView`, so `getActivity()` remains valid there.

**Acceptance criteria**: register/unregister live in symmetric lifecycle callbacks (inspection); rotate-with-view-recreation leaves no stale listener (manual or Robolectric check if harnessed).

### 3.2 Make the SCO state machine JVM-testable (ODD-23)

**Component**: [`BluetoothScoManager.java`](../../libraries/humla/src/main/java/se/lublin/humla/audio/BluetoothScoManager.java) (`CONNECT_TIMEOUT_MS` at `:74`, `MAX_ATTEMPTS` at `:76`, main-thread `Handler` at `:108`)

**Problem**: Timeout budget, retry budget, and the `Handler` are hard-wired, so the bring-up matrix (success, timeout, retry, refusal, no-device, drop-after-connect) cannot be exercised by JVM tests.

**Solution**: Inject the timeout/retry budget and the handler (constructor or setter seam), or extract a pure transition function the manager drives; then cover the matrix in the existing JUnit style. Keep the production defaults (`8000 ms`, `2` attempts, main-looper handler) unchanged.

**Acceptance criteria**: JVM tests pin at least timeout, retry-exhaustion, and refusal transitions without Robolectric; production defaults byte-identical.

---

## Resolved: Sticky `mForceTcp` (ODD-24)

**Status**: Resolved on `master` (commit `e5c7565b`, merged via `b3bfdc00`). `configureExtras` now assigns `mForceTcp` instead of accumulating with `|=`, so disabling the force-TCP setting takes effect on the next extras push. No further action.

---

## Verification & Test Strategy

Each fix ships with a targeted regression pin; all items are JVM-testable except the fragment lifecycle mirror (inspection + manual rotation):

| Phase | Item | Automated Verification | Manual / Device Check |
|---|---|---|---|
| **Phase 1** | **ODD-25** | Unit test calling `setVoiceTargetId` with a null `mAudioHandler`; existing voice-target tests pass. | None (latent path). |
| **Phase 1** | **ODD-27** | JVM test: valid rate string passes through, garbage string falls back to the default rate. | Corrupt `PREF_INPUT_RATE` via backup restore; connect succeeds. |
| **Phase 2** | **ODD-21** | Grep: no unread `PREF_PREPROCESSOR_ENABLED` remains (wired behind a toggle, or key/default/extra removed). | Toggle the preprocessor off (if wired); verify audio path still connects. |
| **Phase 2** | **ODD-22** | Doc/code consistency inspection (no test). | None. |
| **Phase 3** | **ODD-26** | Lifecycle symmetry inspection; Robolectric rotation test if harnessed. | Rotate with the channel view open; confirm no stale callbacks. |
| **Phase 3** | **ODD-23** | JUnit coverage of the timeout / retry-exhaustion / refusal transitions via the injected seam. | None (JVM suite covers it). |
