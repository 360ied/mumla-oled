# Miscellaneous Oddities Round-2 Remediation Plan

Prioritized engineering plan for the residual findings cataloged in [`README.md`](README.md) (ODD-21–ODD-27) for Mumla OLED. All seven items are **Low** severity — latent defects, stale docs, and test debt — so the plan is ordered by blast radius (crash paths first, testability last) rather than by severity. ODD-24 is already resolved on `master` and recorded here for completeness. Owner decisions are recorded inline (ODD-21 removal, single-branch scheduling); implement in one `bugfix/oddities-round2-remediation` branch covering Phases 1–3.

> [!NOTE]
> **Status: COMPLETED** — all three phases implemented on branch `bugfix/oddities-round2-remediation` (six commits: the remediation batch plus four pedantic-review follow-ups, each review round returning OK with notes) and merged via `d7dbd548`. Per-item records live in [`README.md`](README.md); the solutions below are the as-built record.

## Table of Contents

1. [Phase 1: Crash-Path Hardening (ODD-25, ODD-27) — COMPLETED](#phase-1-crash-path-hardening-odd-25-odd-27)
2. [Phase 2: Preference & Doc Hygiene (ODD-21, ODD-22) — COMPLETED](#phase-2-preference--doc-hygiene-odd-21-odd-22)
3. [Phase 3: Lifecycle & Testability (ODD-26, ODD-23) — COMPLETED](#phase-3-lifecycle--testability-odd-26-odd-23)
4. [Resolved: Sticky `mForceTcp` (ODD-24)](#resolved-sticky-mforcetcp-odd-24)
5. [Verification & Test Strategy](#verification--test-strategy)

---

## Phase 1: Crash-Path Hardening (ODD-25, ODD-27) — COMPLETED

Both items are unguarded dereferences/parses on paths reachable with corrupted state or off-nominal call timing. One-line fixes each; do them together.

### 1.1 Null-guard `setVoiceTargetId` (ODD-25)

**Component**: [`HumlaService.java:1518-1527`](../../libraries/humla/src/main/java/se/lublin/humla/HumlaService.java#L1518-L1527)

**Problem**: `mAudioHandler.setVoiceTargetId(targetId)` dereferences `mAudioHandler` without a null check. The handler is null while disconnected, so any future disconnected caller gets an NPE. Neighboring `setTalkingState` already null-guards the same field.

**Solution**: Null-guard and drop the live call when no handler exists:

```java
mVoiceTargetId = targetId;
if (mAudioHandler != null) {
    mAudioHandler.setVoiceTargetId(targetId);
}
```

Dropping (rather than deferring) the live call matches current per-connection semantics: the stored `mVoiceTargetId` is re-applied to the fresh pipeline at init ([`HumlaService.java:870-873`](../../libraries/humla/src/main/java/se/lublin/humla/HumlaService.java#L870-L873)), so a mid-connection set survives pipeline rebuilds — but `connect()` ([`:435`](../../libraries/humla/src/main/java/se/lublin/humla/HumlaService.java#L435)) and disconnect ([`:595`](../../libraries/humla/src/main/java/se/lublin/humla/HumlaService.java#L595)) reset the target to `0`, so a pre-connect set is intentionally not preserved.

**Acceptance criteria**: `setVoiceTargetId` callable with `mAudioHandler == null` (unit test with a null handler seam or a disconnected service harness); existing voice-target tests pass.

### 1.2 Guard the input-rate parse (ODD-27)

**Component**: [`Settings.java:242-247`](../../app/src/main/java/se/lublin/mumla/Settings.java#L242-L247)

**Problem**: `getInputSampleRate()` calls `Integer.parseInt` on a raw preference string with no fallback, while the adjacent `getFramesPerPacket()` catches `NumberFormatException`. A hand-edited or backup-restored `PREF_INPUT_RATE` value crashes the connect path on any read of the corrupted value.

**Solution**: Mirror the neighbor's `try/catch` fallback. Fall back to the numeric default rate on `NumberFormatException`:

```java
public int getInputSampleRate() {
    try {
        return Integer.parseInt(preferences.getString(Settings.PREF_INPUT_RATE, DEFAULT_RATE));
    } catch (NumberFormatException | ClassCastException e) {
        return Integer.parseInt(DEFAULT_RATE);
    }
}
```

The same widening applies to the neighboring `getFramesPerPacket()`, which
shared the narrow catch. A wrong-typed stored value cannot be pinned through
`FakeSharedPreferences` (it returns the default on type mismatch instead of
throwing like the framework), so the `ClassCastException` arm is
inspection-verified.

**Acceptance criteria**: JVM test pins valid-string passthrough and garbage-string fallback; connect path no longer throws on corrupted prefs.

---

## Phase 2: Preference & Doc Hygiene (ODD-21, ODD-22) — COMPLETED

Dead state and a stale bound description. No behavior change in either fix.

### 2.1 Resolve the dead preprocessor toggle (ODD-21)

**Component** (removed by this change; pre-fix locations): `Settings.PREF_PREPROCESSOR_ENABLED` / `isPreprocessorEnabled()` (was `Settings.java:459-461`), `HumlaService.EXTRAS_ENABLE_PREPROCESSOR` and its `configureExtras` block, `AudioHandler.Builder.setPreprocessorEnabled`, and the `ServerConnectTask` put (was line 87).

**Problem**: `isPreprocessorEnabled()` returns `true` unconditionally while `PREF_PREPROCESSOR_ENABLED` and `DEFAULT_PREPROCESSOR_ENABLED` exist but are never read, so `EXTRAS_ENABLE_PREPROCESSOR` can never be `false` and the RNNoise preprocessor cannot be disabled at runtime. The key has no settings-screen entry (no XML references it) and no other readers, so both directions are safe.

**Solution** (decision, owner): **remove**. Making the preprocessor truly toggleable — settings-screen UI, reconnect-vs-live semantics (`EXTRAS_ENABLE_PREPROCESSOR` participates in `bundleHasOtherAudioKeys`), and a supported/tested preprocessor-off audio configuration — is a deep architectural lift, and always-on is the tested default. Delete:
1. `PREF_PREPROCESSOR_ENABLED` / `DEFAULT_PREPROCESSOR_ENABLED` and `isPreprocessorEnabled()` in [`Settings.java`](../../app/src/main/java/se/lublin/mumla/Settings.java).
2. `EXTRAS_ENABLE_PREPROCESSOR`, its `bundleHasOtherAudioKeys` entry, and its `configureExtras` block in [`HumlaService.java`](../../libraries/humla/src/main/java/se/lublin/humla/HumlaService.java).
3. The `putExtra` in [`ServerConnectTask.java:87`](../../app/src/main/java/se/lublin/mumla/app/ServerConnectTask.java#L87).
4. `Builder.setPreprocessorEnabled` and its field in [`AudioHandler.java`](../../libraries/humla/src/main/java/se/lublin/humla/protocol/AudioHandler.java) — hardcode `true` at the builder→handler handoff, since the extra is unconditionally `true` today and the default must not silently flip to off. The now-constant internal flag is left for a future cleanup.

**Acceptance criteria**: no `PREPROCESSOR_ENABLED` / `ENABLE_PREPROCESSOR` references remain in `app/src` or `libraries/humla/src` (the docs intentionally record the removed names); `assembleFossDebug` passes; connect succeeds with preprocessing audibly active.

### 2.2 Fix the render-lead bound doc (ODD-22)

**Component**: [`audio-output/README.md:37`](../audio-output/README.md#L37)

**Problem**: The reference says the render-lead bound is "1 quantum, or the track minimum", but [`AudioOutput.java:574-575`](../../libraries/humla/src/main/java/se/lublin/humla/audio/AudioOutput.java#L574-L575) computes `max(renderSamples, min(renderSamples * 2, trackFrames))` — up to 2 quanta (~40 ms), intentionally matching the native jitter margin. The code and its comment are correct; only the doc is stale.

**Solution**: One-line doc fix — "up to 2 quanta (~40 ms), or the track minimum".

**Acceptance criteria**: doc bound matches the `Pacer` computation; no code change.

---

## Phase 3: Lifecycle & Testability (ODD-26, ODD-23) — COMPLETED

A fragment lifecycle mirror fix and the only test-debt item in the batch.

### 3.1 Move the listener unregister to `onDestroyView` (ODD-26)

**Component**: [`ChannelFragment.java:207`](../../app/src/main/java/se/lublin/mumla/channel/ChannelFragment.java#L207) (register), [`ChannelFragment.java:304-305`](../../app/src/main/java/se/lublin/mumla/channel/ChannelFragment.java#L304-L305) (unregister in `onDestroyView`)

**Problem**: The shared-preference listener is registered in `onActivityCreated` but unregistered in `onDestroy` rather than `onDestroyView`, so the retained listener survives view recreations. Latent today (callbacks only touch null-guarded view state), but the asymmetry leaks the fragment as a listener across every rotation.

**Solution**: Move the `unregisterOnSharedPreferenceChangeListener` call to `onDestroyView`, mirroring the view lifecycle. Safe: the fragment is still attached to its activity in `onDestroyView`, so `getActivity()` remains valid there. Null all seven view fields alongside (per the ODD-15 precedent) and guard `configureTargetPanel()` with the same `!isAdded()` / null checks as `configureInput()`, since the service observer outlives the view until `onDestroy` and `onVoiceTargetChanged` would otherwise touch the nulled panel.

**Acceptance criteria**: register/unregister live in symmetric lifecycle callbacks (inspection); rotate-with-view-recreation leaves no stale listener (manual or Robolectric check if harnessed).

### 3.2 Make the SCO state machine JVM-testable (ODD-23)

**Component**: [`BluetoothScoManager.java`](../../libraries/humla/src/main/java/se/lublin/humla/audio/BluetoothScoManager.java) (`CONNECT_TIMEOUT_MS` at [`BluetoothScoManager.java:74`](../../libraries/humla/src/main/java/se/lublin/humla/audio/BluetoothScoManager.java#L74), `MAX_ATTEMPTS` at [`BluetoothScoManager.java:76`](../../libraries/humla/src/main/java/se/lublin/humla/audio/BluetoothScoManager.java#L76), main-thread `Handler` at [`BluetoothScoManager.java:108`](../../libraries/humla/src/main/java/se/lublin/humla/audio/BluetoothScoManager.java#L108))

**Problem**: Timeout budget, retry budget, and the `Handler` are hard-wired, so the bring-up matrix (success, timeout, retry, refusal, no-device, drop-after-connect) cannot be exercised by JVM tests.

**Solution**: constructor injection of the timeout and retry budgets via a package-visible overload, with the production defaults (`8000 ms`, `2` attempts) passed at the existing `HumlaService` call site — no manager API redesign, behavior byte-identical. The `Handler` stays hard-wired: `android.os.Handler` cannot be constructed on the JVM and this module is JUnit-only (no Robolectric/Mockito), so an injected handler would gain no test. The bring-up matrix is instead pinned through the extracted pure predicate `shouldRetryBringUp(attempts, maxAttempts)`, shared by the timeout and stack-error paths; refusal and no-device fail unconditionally in their callers.

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
| **Phase 2** | **ODD-21** | Grep: no `PREPROCESSOR_ENABLED` / `ENABLE_PREPROCESSOR` references remain in `app/src` or `libraries/humla/src`; `assembleFossDebug` passes. | Connect and verify preprocessing audibly active (behavior unchanged, always on). |
| **Phase 2** | **ODD-22** | Doc/code consistency inspection (no test). | None. |
| **Phase 3** | **ODD-26** | Lifecycle symmetry inspection; Robolectric rotation test if harnessed. | Rotate with the channel view open; confirm no stale callbacks. |
| **Phase 3** | **ODD-23** | [`BluetoothScoRetryPolicyTest.java`](../../libraries/humla/src/test/java/se/lublin/humla/audio/BluetoothScoRetryPolicyTest.java) truth-tabling the shared retry predicate (fresh/first-timeout retry, exhaustion, single/zero budgets); refusal and no-device are unconditional fail paths. | None (JVM suite covers it). |
