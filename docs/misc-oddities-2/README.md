# Miscellaneous Oddities, Round 2

Residual findings from the Bluetooth SCO investigation and its pedantic
code reviews that are **not SCO behavior**: pre-existing defects, stale
documentation, and test debt worth tickets of their own. Continues the
numbering from [misc-oddities](../misc-oddities/README.md) (ODD-01–ODD-20).

## Table of Contents

1. [Summary Matrix](#summary-matrix)
2. [Detailed Topics](#detailed-topics)
   - [ODD-21: Dead Preprocessor Preference](#odd-21-dead-preprocessor-preference)
   - [ODD-22: Stale Render-Lead Bound in Audio Output Docs](#odd-22-stale-render-lead-bound-in-audio-output-docs)
   - [ODD-23: No JVM Coverage for the SCO State Machine](#odd-23-no-jvm-coverage-for-the-sco-state-machine)
   - [ODD-24: Sticky `mForceTcp` Never Clears](#odd-24-sticky-mforcetcp-never-clears)
   - [ODD-25: `setVoiceTargetId` NPE While Disconnected](#odd-25-setvoicetargetid-npe-while-disconnected)
   - [ODD-26: Channel Fragment Listener Unregistered Late](#odd-26-channel-fragment-listener-unregistered-late)
   - [ODD-27: Unguarded Input-Rate Parse Crashes Connect](#odd-27-unguarded-input-rate-parse-crashes-connect)

3. [Remediation Roadmap](remediation-plan.md)

---

## Summary Matrix

| ID | Category | Severity | Status | Summary | Location |
|---|---|---|---|---|---|
| **ODD-21** | **Preferences** | **Low** | **Resolved** | **Dead preprocessor toggle**: `isPreprocessorEnabled()` returned `true` unconditionally, so `EXTRAS_ENABLE_PREPROCESSOR` could never be `false`. Removed per owner decision; preprocessor is always on. | [remediation plan](remediation-plan.md#phase-2-preference--doc-hygiene-odd-21-odd-22) |
| **ODD-22** | **Documentation** | **Low** | **Resolved** | **Stale render-lead docs**: the audio output reference said the bound was "1 quantum", but the code allows up to 2 quanta (~40 ms). Fixed to the exact formula. | [`README.md:37`](../audio-output/README.md#L37) vs [`AudioOutput.java:574`](../../libraries/humla/src/main/java/se/lublin/humla/audio/AudioOutput.java#L574-L575) |
| **ODD-23** | **Testing** | **Low** | **Resolved** | **SCO state machine untestable on JVM**: timeout, retry budget, and `Handler` were hard-wired in `BluetoothScoManager`. Budgets injected; shared retry decision extracted as a pure predicate with truth-table tests. | [`BluetoothScoManager.java:139`](../../libraries/humla/src/main/java/se/lublin/humla/audio/BluetoothScoManager.java#L139) |
| **ODD-24** | **Service Logic** | **Low** | **Resolved** | **Sticky `mForceTcp`**: `configureExtras` accumulated with `\|=`, so once forced, TCP stayed forced for the service lifetime even if the setting was later disabled. Fixed by plain assignment. | [`HumlaService.java:969`](../../libraries/humla/src/main/java/se/lublin/humla/HumlaService.java#L969) |
| **ODD-25** | **Service Logic** | **Low** | **Resolved** | **`setVoiceTargetId` NPE**: dereferenced `mAudioHandler` without a null check; callable while disconnected. Null-guarded with an unsigned 5-bit check and regression tests. | [`HumlaService.java:1518`](../../libraries/humla/src/main/java/se/lublin/humla/HumlaService.java#L1518-L1530) |
| **ODD-26** | **UI / Lifecycle** | **Low** | **Resolved** | **Late listener unregister**: the preference listener was registered in `onActivityCreated` but unregistered in `onDestroy` rather than `onDestroyView`. Moved with view-field teardown and observer-window guards. | [`ChannelFragment.java:209`](../../app/src/main/java/se/lublin/mumla/channel/ChannelFragment.java#L209) vs [`ChannelFragment.java:307`](../../app/src/main/java/se/lublin/mumla/channel/ChannelFragment.java#L307) |
| **ODD-27** | **Preferences** | **Low** | **Resolved** | **Unguarded input-rate parse**: `getInputSampleRate()` called `Integer.parseInt` without the `try/catch` fallback its `getFramesPerPacket()` neighbor has. Both hardened with `NumberFormatException \| ClassCastException` fallback plus tests. | [`Settings.java:242`](../../app/src/main/java/se/lublin/mumla/Settings.java#L242-L248) |

---

## Detailed Topics

### ODD-21: Dead Preprocessor Preference

> **Status: Resolved on `master`** (branch `bugfix/oddities-round2-remediation`, merge commit `d7dbd548`): removed the dead key, default, accessor, extra, and builder setter, hardcoding preprocessor-on at the handoff. The description below is the pre-fix record.
>
> Origin: pedantic review of the SCO dossier (incidental finding), verified on `master`.

In `Settings.java` (pre-fix location `Settings.java:459-461`):

```java
public boolean isPreprocessorEnabled() {
    return true;
}
```

`PREF_PREPROCESSOR_ENABLED` and `DEFAULT_PREPROCESSOR_ENABLED` both exist, but
the accessor ignores them, so `EXTRAS_ENABLE_PREPROCESSOR` is always `true` and
the RNNoise preprocessor can never be disabled at runtime. Decision (owner):
remove the dead key, default, and extra plumbing — making the preprocessor
truly toggleable (settings UI, reconnect semantics, a supported off-config)
is a deep architectural lift, and always-on is the tested default. Applied on the remediation branch. Low severity:
the preprocessor-on path is the tested, desirable default.

### ODD-22: Stale Render-Lead Bound in Audio Output Docs

> **Status: Resolved on `master`** (branch `bugfix/oddities-round2-remediation`, merge commit `d7dbd548`): both audio-output docs now state the exact `max(1 quantum, min(2 quanta, trackFrames))` formula. The description below is the pre-fix record.
>
> Origin: pedantic review of the SCO dossier (incidental finding), verified on `master`.

[`audio-output/README.md:37`](../audio-output/README.md#L37) says the render-lead
bound is "1 quantum, or the track minimum", but
[`AudioOutput.java:574-575`](../../libraries/humla/src/main/java/se/lublin/humla/audio/AudioOutput.java#L574-L575)
computes `max(renderSamples, min(renderSamples * 2, trackFrames))` — up to 2
quanta (~40 ms). One-line doc fix; the code is correct. Applied on the
remediation branch (including the second stale bound in
`quality-improvements.md`).

### ODD-23: No JVM Coverage for the SCO State Machine

> **Status: Resolved on `master`** (branch `bugfix/oddities-round2-remediation`, merge commit `d7dbd548`): budgets injected via a package-visible overload (production defaults unchanged); the shared retry decision extracted as the pure `shouldRetryBringUp` predicate ([`BluetoothScoManager.java:139`](../../libraries/humla/src/main/java/se/lublin/humla/audio/BluetoothScoManager.java#L139)) with truth-table tests. The `Handler` stays hard-wired (unbuildable on JVM in this JUnit-only module). The description below is the pre-fix record.
>
> Origin: pedantic review of the `bt-sco-manual` worktree (merged to `master` via `b3bfdc00` after this entry was written).

`CONNECT_TIMEOUT_MS` ([`BluetoothScoManager.java:74`](../../libraries/humla/src/main/java/se/lublin/humla/audio/BluetoothScoManager.java#L74)), `MAX_ATTEMPTS` ([`BluetoothScoManager.java:76`](../../libraries/humla/src/main/java/se/lublin/humla/audio/BluetoothScoManager.java#L76)), and the main-thread `Handler` ([`BluetoothScoManager.java:108`](../../libraries/humla/src/main/java/se/lublin/humla/audio/BluetoothScoManager.java#L108)) are
hard-wired in `BluetoothScoManager`, so the timeout/retry/observer matrix
(bring-up success, timeout, retry, refusal, no-device, drop-after-connect)
cannot be exercised by JVM tests. Inject the timeout budget and handler (or
extract a pure transition function), and cover the
matrix in the existing JUnit style.

### ODD-24: Sticky `mForceTcp` Never Clears

> **Status: Resolved on `master`** (commit `e5c7565b`, merged via `b3bfdc00`): `configureExtras` now uses plain assignment. The description below is the pre-fix record.
>
> Origin: pedantic review of the `bt-sco-manual` worktree (incidental, pre-existing).

In [`HumlaService.java:969`](../../libraries/humla/src/main/java/se/lublin/humla/HumlaService.java#L969) (pre-fix):

```java
mForceTcp |= extras.getBoolean(EXTRAS_FORCE_TCP);
```

Once forced TCP is ever `true`, it stays `true` for the service lifetime even
if the setting is later disabled. Fix applied: plain assignment. This was
latent: the setting is
flagged as requiring reconnect, so the sticky value rarely surprises anyone —
but a mid-connection settings push hits exactly this line.

### ODD-25: `setVoiceTargetId` NPE While Disconnected

> **Status: Resolved on `master`** (branch `bugfix/oddities-round2-remediation`, merge commit `d7dbd548`): null-guarded with an API-1-safe unsigned 5-bit check (rejects negatives before state changes) plus regression tests. The description below is the pre-fix record.
>
> Origin: pedantic review of the `bt-sco-manual` worktree (incidental, pre-existing).

In [`HumlaService.java:1518-1530`](../../libraries/humla/src/main/java/se/lublin/humla/HumlaService.java#L1518-L1530) (pre-fix location `1523-1530`):

```java
public void setVoiceTargetId(byte targetId) {
    ...
    mVoiceTargetId = targetId;
    mAudioHandler.setVoiceTargetId(targetId);
    ...
}
```

`mAudioHandler` is dereferenced without a null check and is null while
disconnected. Fix applied: null-guard plus unsigned range check.
Latent: current callers only invoke it while connected.

### ODD-26: Channel Fragment Listener Unregistered Late

> **Status: Resolved on `master`** (branch `bugfix/oddities-round2-remediation`, merge commit `d7dbd548`): unregister moved to `onDestroyView` with all seven view fields nulled, plus `isAdded`/null guards on the observer-window paths (`configureTargetPanel`, `setTalkButtonHidden`); TOCTOU-hardened `HumlaSession()` reads. The description below is the pre-fix record.
>
> Origin: pedantic review of the `bt-sco-manual` worktree (incidental, pre-existing).

[`ChannelFragment.java:209`](../../app/src/main/java/se/lublin/mumla/channel/ChannelFragment.java#L209)
registers the shared-preference listener in `onActivityCreated`, but
[`ChannelFragment.java:307`](../../app/src/main/java/se/lublin/mumla/channel/ChannelFragment.java#L307)
(pre-fix: unregister in `onDestroy`) so the retained
listener survives view recreations. Fix applied: move the unregister to
`onDestroyView` to mirror the view lifecycle. Latent: the callback only
touches view state through null-guarded paths today.

### ODD-27: Unguarded Input-Rate Parse Crashes Connect

> **Status: Resolved on `master`** (branch `bugfix/oddities-round2-remediation`, merge commit `d7dbd548`): both accessors hardened with `NumberFormatException | ClassCastException` fallback plus six-vector regression tests. The description below is the pre-fix record.
>
> Origin: pedantic review of the `bt-sco-manual` worktree (incidental, pre-existing).

In [`Settings.java:242-248`](../../app/src/main/java/se/lublin/mumla/Settings.java#L242-L248) (pre-fix location `245-247`):

```java
public int getInputSampleRate() {
    return Integer.parseInt(preferences.getString(Settings.PREF_INPUT_RATE, DEFAULT_RATE));
}
```

The adjacent [`getFramesPerPacket()`](../../app/src/main/java/se/lublin/mumla/Settings.java#L432-L438)
(pre-fix location `431-437`)
catches `NumberFormatException` and falls back to `Constants.DEFAULT_FRAMES_PER_PACKET`.
A hand-edited or backup-restored `PREF_INPUT_RATE` string kills the connect path on any
read of the corrupted value. Fix applied: mirror the `try/catch` fallback (widened to `ClassCastException` on both accessors).

## Remediation Roadmap

For the prioritized engineering plan, fixes, and test strategy, see:
- [Miscellaneous Oddities Round-2 Remediation Plan](remediation-plan.md)
