# 19 — Local intents are published before `SYNCED`, then merged back at `ServerSync`

| | |
|---|---|
| Status | Decided 2026-11-12: allow and document (option 2). `state.md` describes the pre-sync carry-over of `pttPressed`/`voiceTargetId`. **Code change remains**: move the `syncingState`/`synced` assignments out of the `update` lambda (also closes the issue-06 violation), and add the PTT-during-sync test. |
| Category | Undocumented interim |
| Severity | Low-to-moderate (works today; fragile, and the merge lambda breaks a convention) |
| Verified | Yes (code read) |
| Area | Session / state publication |
| Spec | [state.md § Sync phases and publication](../../target-architecture/state.md#sync-phases-and-publication), [state.md § Rules](../../target-architecture/state.md#rules), [state.md § User intents](../../target-architecture/state.md#user-intents) |
| Code | `libraries/humla/src/main/java/se/lublin/humla/client/Session.kt` (`setPtt` around line 767, `setVoiceTarget` around line 816, `ServerSync` branch of `handleMessage` around lines 551-566); `state/IntentTransitions.kt` |
| Gates | Not gating |

Line numbers below refer to `master` at `092d9dc8`.

## Summary

The spec says nothing is published before `SYNCED`: the message coroutine
applies transitions to a private value and publishes once at
`ServerSync`. In the code, `setPtt` and `setVoiceTarget` update the
**published** `_state` at any phase. At `ServerSync`, the private synced
value would overwrite those updates, so the `ServerSync` publish lambda
copies `pttPressed` and `voiceTargetId` from the current published value
into the synced value. That merge is not in the spec, and the lambda also
assigns `Session` fields (see [06](06-update-conventions-not-enforced.md)).

## What the spec says

`state.md` § Sync phases and publication: `HANDSHAKING` and `SYNCING` are
"Not published: the message coroutine applies transitions to a private
value"; `SYNCED` publishes once.

`state.md` § User intents: intents are no-ops before `SYNCED` (the code
comment in `Session` says "No-ops returning nothing (or -1) when not
SYNCED, never throwing. setPtt/setVoiceTarget are local-only and valid
pre-SYNCED").

`state.md` § Rules: "Every change is a function `(SessionState, input) ->
SessionState`, with no side effects".

## What the code does

`Session.setPtt` (around line 767): `_state.update { current ->
applyPttIntent(current, pressed).state }`, with no phase check;
`IntentTransitions` has no phase gate for PTT or voice target.
`setVoiceTarget` (around line 816) likewise.

`ServerSync` branch (around line 551):

```kotlin
val result = applyServerSync(syncingState, msg)
// Preserve local-only intents that landed pre-sync (PTT, voice target)
// atomically: read-then-publish would drop a press racing this block,
// so merge inside update.
_state.update { current ->
    syncingState = result.state.copy(
        local = result.state.local.copy(
            pttPressed = current.local.pttPressed,
            voiceTargetId = current.local.voiceTargetId,
        ),
    )
    synced = true
    syncingState
}
```

## Impact

- Consumers of the published flow (handles, `PolicyCollector` →
  `VoicePolicy`) can observe a pre-sync value that differs from
  `emptySessionState()` in two local fields. `VoicePolicy` requires
  `SYNCED` to allow transmit, so this does not open the mic.
- Correctness depends on the merge listing every pre-sync-writable local
  field. A future intent made valid pre-sync, or an edit that drops one of
  the two fields, silently loses a PTT press or pending voice target at
  sync.
- The lambda assigns `syncingState` and `synced` (rule-1 violation, see
  [06](06-update-conventions-not-enforced.md)).

## Fix directions

Pick one model and write it into `state.md` (on `master` first):

1. **Private pre-sync local state:** apply pre-sync `setPtt`/`setVoiceTarget`
   to the private `syncingState` (on the message coroutine, or under the
   same confinement), publish nothing before `SYNCED`, and drop the merge.
2. **Published local state is allowed pre-sync:** document that `local`
   may be published before `SYNCED`, and replace the field-by-field merge
   with a structural rule (for example, `ServerSync` publishes
   `result.state.copy(local = current.local)` with `local` owned entirely
   by intents, after checking no server transition writes `local`; note
   [25](25-user-state-writes-local.md) says one does).

In either case, move the `syncingState`/`synced` assignments out of the
`update` lambda.

## Acceptance

`state.md` describes what is published before `SYNCED`; a test presses PTT
during `SYNCING` and asserts it survives `ServerSync`; `./scripts/check.sh`
passes.

## Related

- [06](06-update-conventions-not-enforced.md),
  [25](25-user-state-writes-local.md)
