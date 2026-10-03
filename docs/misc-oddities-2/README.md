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
| **ODD-21** | **Preferences** | **Low** | **Open** | **Dead preprocessor toggle**: `isPreprocessorEnabled()` returns `true` unconditionally, so `EXTRAS_ENABLE_PREPROCESSOR` can never be `false` despite the pref key and default existing. | [`Settings.java:459`](../../app/src/main/java/se/lublin/mumla/Settings.java#L459-L461) |
| **ODD-22** | **Documentation** | **Low** | **Open** | **Stale render-lead docs**: the audio output reference says the bound is "1 quantum", but the code allows up to 2 quanta (~40 ms). | [`README.md:37`](../audio-output/README.md#L37) vs [`AudioOutput.java:574`](../../libraries/humla/src/main/java/se/lublin/humla/audio/AudioOutput.java#L574-L575) |
| **ODD-23** | **Testing** | **Low** | **Open** | **SCO state machine untestable on JVM**: timeout, retry budget, and `Handler` are hard-wired in `BluetoothScoManager`, so the bring-up matrix has no unit coverage. | [`BluetoothScoManager.java`](../../libraries/humla/src/main/java/se/lublin/humla/audio/BluetoothScoManager.java) (merged via `b3bfdc00`) |
| **ODD-24** | **Service Logic** | **Low** | **Resolved** | **Sticky `mForceTcp`**: `configureExtras` accumulated with `\|=`, so once forced, TCP stayed forced for the service lifetime even if the setting was later disabled. Fixed by plain assignment. | [`HumlaService.java:969`](../../libraries/humla/src/main/java/se/lublin/humla/HumlaService.java#L969) |
| **ODD-25** | **Service Logic** | **Low** | **Open** | **`setVoiceTargetId` NPE**: dereferences `mAudioHandler` without a null check; callable while disconnected. | [`HumlaService.java:1523`](../../libraries/humla/src/main/java/se/lublin/humla/HumlaService.java#L1523-L1530) |
| **ODD-26** | **UI / Lifecycle** | **Low** | **Open** | **Late listener unregister**: the preference listener is registered in `onActivityCreated` but unregistered in `onDestroy` rather than `onDestroyView`, surviving view recreations. | [`ChannelFragment.java:207`](../../app/src/main/java/se/lublin/mumla/channel/ChannelFragment.java#L207) vs [`ChannelFragment.java:307`](../../app/src/main/java/se/lublin/mumla/channel/ChannelFragment.java#L307-L310) |
| **ODD-27** | **Preferences** | **Low** | **Open** | **Unguarded input-rate parse**: `getInputSampleRate()` calls `Integer.parseInt` without the `try/catch` fallback its `getFramesPerPacket()` neighbor has; a corrupted pref string crashes connect. | [`Settings.java:245`](../../app/src/main/java/se/lublin/mumla/Settings.java#L245-L247) |

---

### ODD-21: Dead Preprocessor Preference

> Origin: pedantic review of the SCO dossier (incidental finding), verified on `master`.

In [`Settings.java:459-461`](../../app/src/main/java/se/lublin/mumla/Settings.java#L459-L461):

```java
public boolean isPreprocessorEnabled() {
    return true;
}
```

`PREF_PREPROCESSOR_ENABLED` and `DEFAULT_PREPROCESSOR_ENABLED` both exist, but
the accessor ignores them, so `EXTRAS_ENABLE_PREPROCESSOR` is always `true` and
the RNNoise preprocessor can never be disabled at runtime. Either wire the
accessor to the preference or remove the dead key and default. Low severity:
the preprocessor-on path is the tested, desirable default.

### ODD-22: Stale Render-Lead Bound in Audio Output Docs

> Origin: pedantic review of the SCO dossier (incidental finding), verified on `master`.

[`audio-output/README.md:37`](../audio-output/README.md#L37) says the render-lead
bound is "1 quantum, or the track minimum", but
[`AudioOutput.java:574-575`](../../libraries/humla/src/main/java/se/lublin/humla/audio/AudioOutput.java#L574-L575)
computes `max(renderSamples, min(renderSamples * 2, trackFrames))` — up to 2
quanta (~40 ms). One-line doc fix; the code is correct.

### ODD-23: No JVM Coverage for the SCO State Machine

> Origin: pedantic review of the `bt-sco-manual` worktree (merged to `master` via `b3bfdc00` after this entry was written).

`CONNECT_TIMEOUT_MS` ([`:74`](../../libraries/humla/src/main/java/se/lublin/humla/audio/BluetoothScoManager.java#L74)), `MAX_ATTEMPTS` ([`:76`](../../libraries/humla/src/main/java/se/lublin/humla/audio/BluetoothScoManager.java#L76)), and the main-thread `Handler` ([`:108`](../../libraries/humla/src/main/java/se/lublin/humla/audio/BluetoothScoManager.java#L108)) are
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

> Origin: pedantic review of the `bt-sco-manual` worktree (incidental, pre-existing).

In [`HumlaService.java:1523-1530`](../../libraries/humla/src/main/java/se/lublin/humla/HumlaService.java#L1523-L1530):

```java
public void setVoiceTargetId(byte targetId) {
    ...
    mVoiceTargetId = targetId;
    mAudioHandler.setVoiceTargetId(targetId);
    ...
}
```

`mAudioHandler` is dereferenced without a null check and is null while
disconnected. Fix: null-guard (defer or drop the call when no handler exists).
Latent: current callers only invoke it while connected.

### ODD-26: Channel Fragment Listener Unregistered Late

> Origin: pedantic review of the `bt-sco-manual` worktree (incidental, pre-existing).

[`ChannelFragment.java:207`](../../app/src/main/java/se/lublin/mumla/channel/ChannelFragment.java#L207)
registers the shared-preference listener in `onActivityCreated`, but
[`ChannelFragment.java:307`](../../app/src/main/java/se/lublin/mumla/channel/ChannelFragment.java#L307-L310)
unregisters in `onDestroy` rather than `onDestroyView`, so the retained
listener survives view recreations. Fix: move the unregister to
`onDestroyView` to mirror the view lifecycle. Latent: the callback only
touches view state through null-guarded paths today.

### ODD-27: Unguarded Input-Rate Parse Crashes Connect

> Origin: pedantic review of the `bt-sco-manual` worktree (incidental, pre-existing).

In [`Settings.java:245-247`](../../app/src/main/java/se/lublin/mumla/Settings.java#L245-L247):

```java
public int getInputSampleRate() {
    return Integer.parseInt(preferences.getString(Settings.PREF_INPUT_RATE, DEFAULT_RATE));
}
```

The adjacent [`getFramesPerPacket()`](../../app/src/main/java/se/lublin/mumla/Settings.java#L431-L437)
catches `NumberFormatException` and falls back to `Constants.DEFAULT_FRAMES_PER_PACKET`.
A hand-edited or backup-restored `PREF_INPUT_RATE` string kills the connect path on any
read of the corrupted value. Fix: mirror the `try/catch` fallback.

## Remediation Roadmap

For the prioritized engineering plan, fixes, and test strategy, see:
- [Miscellaneous Oddities Round-2 Remediation Plan](remediation-plan.md)
