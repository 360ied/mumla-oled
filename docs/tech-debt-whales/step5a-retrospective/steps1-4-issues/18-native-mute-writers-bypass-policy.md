# 18 — Native mute/PTT gates have writers outside the interim policy path

| | |
|---|---|
| Category | Undocumented interim (documented in code, not in the spec) |
| Severity | Moderate (two owners of the transmit gate; possible latched divergence) |
| Verified | Code paths verified; a concrete latching sequence was not constructed |
| Area | Step-4 voice control / `AudioHandler` |
| Spec | [voice.md § Interim mute (step 4, before 5a)](../../target-architecture/voice.md#interim-mute-step-4-before-5a), [migration.md § Step 4](../../target-architecture/migration.md#step-4--voice-control-and-route) item 3 |
| Code | `libraries/humla/src/main/java/se/lublin/humla/protocol/AudioHandler.kt` (`updateMuteState` line 394, `setSelfMutedOptimistic` line 440, `applyInterimPolicy` line 752, `setPttTalking` line 896, `messageUserState` around line 700); `HumlaService.kt` (`setTalkingState` line 1351, `setSelfMuteDeafState` around line 1441); `audio/NativeAudioInputEngine.kt` (`@Synchronized` setters) |
| Gates | Ends with 5a.4 (policy pushed natively); worth documenting now |

Line numbers below refer to `master` at `092d9dc8`.

## Summary

The spec's step-4 interim says the Kotlin capture thread is the only writer
of the native engine's mute and PTT gates: it reads `VoicePolicy` each
frame and calls `setMuted`/`setPttTalking` only on change, "the same thread
that drives the engine, so there is no cross-thread contention". In the
code, `AudioHandler.updateMuteState()` also writes `setMuted(...)` from
whatever thread calls it (initialization, the session dispatcher via
`messageUserState`, the binder/main thread via `setSelfMutedOptimistic`),
and `HumlaService.setTalkingState` calls `setPttTalking(...)` directly from
the caller's thread. Because `applyInterimPolicy` memoizes the last policy
values and only writes on change, an out-of-band write is not corrected
until the policy itself changes.

## What the spec says

`voice.md` § Interim mute:

> The Kotlin capture thread reads its value each frame and calls the
> current engine's `setMuted` and `setPttTalking` only when
> `transmitAllowed` or `pttPressed` changes. It is the same thread that
> drives the engine, so there is no cross-thread contention.

## What the code does

Writers of `mNativeEngine.setMuted(...)`:

| Writer | Thread | Input |
|---|---|---|
| `applyInterimPolicy()` (line 752), called per frame | Capture thread | `!policy.transmitAllowed`, only if different from `lastPolicyTransmit` |
| `updateMuteState()` (line 394) | Caller's: `initialize` (line 373, control thread), `messageUserState` (line ~709, session dispatcher), `setSelfMutedOptimistic` (line 446, binder/main via `HumlaService.setSelfMuteDeafState` line ~1441) | `mServerMuted || mSelfMuted || mServerDeafened || mSuppressed` |

Writers of `mNativeEngine.setPttTalking(...)`:

| Writer | Thread | Input |
|---|---|---|
| `applyInterimPolicy()` | Capture thread | `policy.pttPressed`, only on change |
| `AudioHandler.setPttTalking()` (line 896) via `HumlaService.setTalkingState` (line 1351) | Caller's (binder/main) | the argument |

`NativeAudioInputEngine`'s setters are `@Synchronized` and contend with the
capture thread's frame processing.

`updateMuteState`'s own comment documents the dual ownership: "Ownership
(step 4 interim): this synchronous gate seeds init and answers
server/model transitions; the per-frame `applyInterimPolicy` below is the
live owner once a policy flow is attached, converging on the same
composite. Interim staleness is bounded by one collector pass".

## Why "converging" is not guaranteed

The two writers compute from different inputs: `updateMuteState` uses
`AudioHandler`'s own fields (including the optimistic `mSelfMuted`);
`applyInterimPolicy` uses `VoicePolicy.transmitAllowed`, which also depends
on sync phase, presence of self, `effectiveSelfMute` (desired plus echo),
and local suppression. Whenever `updateMuteState` writes a value that
differs from what the policy says, and the policy does not subsequently
change, `applyInterimPolicy` sees `lastPolicyTransmit == policy.transmitAllowed`
and does not rewrite the gate. The engine then stays in `updateMuteState`'s
state against the policy until the next policy change. This audit did not
construct a specific user-visible sequence; the PTT path is consistent in
practice because `setTalkingState` also updates the session's `pttPressed`.

## Impact

- The spec's single-writer, no-contention claim does not hold.
- Possible transient (or, in an unlucky sequence, latched until the next
  policy change) disagreement between the mic gate and the policy:
  transmitting when the policy says no, or muted when it says yes.
- Mute is safety-relevant (an open mic when the user believes they are
  muted), so even a rare divergence matters more than its frequency.

## Fix directions

1. Simplest interim fix: when a policy flow is attached, make
   `updateMuteState` not write the native gate (only the policy path
   writes), or have it reset `lastPolicyTransmit = null` so the next frame
   re-applies the policy unconditionally.
2. Route `setTalkingState` through the session only (`setPtt`), and let the
   policy carry it to the engine; drop the direct `setPttTalking` call, or
   likewise reset `lastPolicyPtt`.
3. Keep the optimistic self-mute (desktop parity) by making sure it is
   reflected in `VoicePolicy` (`effectiveSelfMute` already uses the desired
   state), rather than by a second writer.
4. Document whatever remains in `voice.md`'s interim section (on `master`
   first).

## Acceptance

- Exactly one writer per gate while a policy flow is attached, or a
  documented and tested reconciliation (for example, a test that an
  out-of-band write is overwritten on the next frame).
- `./scripts/check.sh` passes; mute/unmute/PTT behave as before on a device
  (developer's discretion).

## Related

- [19](19-pre-sync-local-intents-published.md),
  [14](14-local-facts-self-mute-stub.md)
