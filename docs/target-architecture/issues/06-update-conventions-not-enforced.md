# 06 — The two `update {}` conventions are not enforced anywhere

| | |
|---|---|
| Status | Split. The confirmed rule-1 violation is fixed on branch `session-serversync-update-hygiene` (`89dd9b57`); enforcement now has **no** artifact, since the pedantic review skills that carried the rules were deleted (`8e88589f`); the rule-2 sweep remains undone. |
| Category | Verification gap (with one confirmed violation) |
| Severity | Harmful (no defense against read-then-act races in state code) |
| Verified | Yes |
| Area | Enforcement / state |
| Spec | [enforcement.md § Remaining conventions](../enforcement.md#remaining-conventions) |
| Code | Violation, now fixed: `libraries/humla/src/main/java/se/lublin/humla/client/Session.kt` (`ServerSync` branch of `handleMessage`); pinning test in `HumlaClientTest.serverSyncPublishesDumpAndPreSyncIntents` |
| Gates | Not gating; cheap to fix |

Line numbers below refer to `master` at `092d9dc8`.

## Summary

`enforcement.md` names two state-update rules that cannot be checked
mechanically and assigns them to review. The review skill that was supposed
to carry them never mentioned either rule, and has since been deleted, so
the rules exist only as prose. At least one place in `Session` violated
rule 1; that violation is fixed, but nothing prevents the next one.

## What the spec says

`enforcement.md` § Remaining conventions:

> Two rules cannot be checked mechanically. Reviews check them, and no
> tripwire covers them yet.
>
> 1. **No side effects inside `update {}`.** An atomic swap may run the
>    lambda more than once. The lambda only computes the new value.
> 2. **Decide inside `update {}`, not after reading.** Code that reads
>    `state.value` and then acts on what it read (for example, "if muted,
>    unmute") can act on a stale value. Decisions that depend on current
>    state are made inside the `update` lambda, and any effect follows from
>    its result. Code holds IDs across time, never `UserInfo` or
>    `ChannelInfo` values.

## What exists

No artifact carries these rules. They were assigned to the pedantic review
skill, which never mentioned either one; both skills were then deleted
(`8e88589f`) on the grounds that a delegated reviewer cannot verify anything
it can only read. The rules now live in `enforcement.md` prose, and
`scripts/check_architecture.py` does not scan for either of them.

## Confirmed violation of rule 1 (fixed)

`Session.kt`, `ServerSync` branch, as it stood before
`session-serversync-update-hygiene` (`89dd9b57`):

```kotlin
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

The lambda assigns two `Session` member fields (`syncingState`, `synced`).
`MutableStateFlow.update` retries the lambda on contention, so the
assignments may run more than once. In this specific case the effects are
idempotent (each retry recomputes and reassigns from the latest `current`,
and `synced = true` is stable), so there is no observable bug today; but it
is exactly the pattern rule 1 forbids, and it was written and reviewed
without anyone flagging it, which shows the rule is not being applied.

Fixed on `session-serversync-update-hygiene`: the merge moved to
`updateAndGet`, and `syncingState`/`synced` are assigned from the committed
value, outside the lambda. `serverSyncPublishesDumpAndPreSyncIntents` pins the
carry-over that the merge exists for; deleting the merge fails that test and
no other.

(The reason for the merge inside the lambda, preserving pre-sync PTT and
voice-target intents, is covered in
[19](19-pre-sync-local-intents-published.md).)

## Impact

State code in `Session`, `PolicyCollector`, and `RouteControl` uses atomics
and `StateFlow.update` extensively. With no artifact enforcing either rule,
read-then-act races (rule 2) and non-idempotent effects inside `update`
(rule 1) can be reintroduced freely. These are precisely the bugs
the immutable-state design was meant to prevent. The one known instance is
fixed; the gap is the absent check, not the code.

## Fix directions

1. ~~Add both rules to the pedantic code review skill's checklist.~~
   Withdrawn: that skill is deleted, and reinstating a subagent reviewer to
   hold the rules is the pattern that was judged harmful. A rule that only a
   reading can check is not enforced.
2. ~~Fix the `ServerSync` lambda.~~ Done on branch
   `session-serversync-update-hygiene` (`89dd9b57`): the merge moved to
   `updateAndGet` and the `syncingState`/`synced` assignments follow the
   commit.
3. A cheap static check: flag `update {` lambdas that assign an identifier
   not declared in the lambda. False positives are known
   (`setSelfMuteDeaf`, `registerWhisperTarget` capture transition payloads,
   which `updateAndGet` cannot carry), so it needs an allowlist. This is the
   surviving mechanical option, and it belongs in
   [check_architecture.py](../../../scripts/check_architecture.py) with the
   usual proof: inject a violation, watch it go red.
4. Sweep `Session.kt`, `PolicyCollector`, and `RouteControl` once for rule-2
   violations (`state.value` read followed by an action decided on it).
   This audit did not do that sweep, and it is still undone.

## Acceptance

- ~~Both rules appear in the pedantic review skill.~~ Void: the skill is
  deleted. Open replacement: a tripwire (direction 3) or an accepted
  decision that rule 1 is review-only prose.
- The `ServerSync` publish lambda has no assignments to `Session` fields
  (met on `session-serversync-update-hygiene`).
- `./scripts/check.sh` passes.

## Related

- [19](19-pre-sync-local-intents-published.md)
- [25](25-user-state-writes-local.md)
