# 06 — The two `update {}` conventions are not enforced anywhere

| | |
|---|---|
| Status | Partly resolved on `master`: both rules added to the pedantic review skill. The `ServerSync` lambda violation and the rule-2 sweep remain. |
| Category | Verification gap (with one confirmed violation) |
| Severity | Harmful (no defense against read-then-act races in state code) |
| Verified | Yes |
| Area | Enforcement / state |
| Spec | [enforcement.md § Remaining conventions](../../target-architecture/enforcement.md#remaining-conventions) |
| Code | `.agents/skills/mumla-pedantic-code-review/SKILL.md`; violation in `libraries/humla/src/main/java/se/lublin/humla/client/Session.kt` (`ServerSync` branch of `handleMessage`) |
| Gates | Not gating; cheap to fix |

Line numbers below refer to `master` at `092d9dc8`.

## Summary

`enforcement.md` names two state-update rules that cannot be checked
mechanically and says the pedantic review skill's checklist gains both.
Neither rule appears in any skill or reviewer prompt. At least one place in
`Session` violates rule 1.

## What the spec says

`enforcement.md` § Remaining conventions:

> Two rules cannot be checked mechanically. Reviews check them, and the
> pedantic review skill's checklist gains both:
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

`.agents/skills/mumla-pedantic-code-review/SKILL.md` contains no mention of
either rule. A repository-wide search for "side effects inside", "Decide
inside", or `update {}` in skills finds nothing. The rules exist only in
`enforcement.md`.

## Confirmed violation of rule 1

`Session.kt`, `ServerSync` branch (around lines 551-566):

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

(The reason for the merge inside the lambda, preserving pre-sync PTT and
voice-target intents, is covered in
[19](19-pre-sync-local-intents-published.md).)

## Impact

State code in `Session`, `PolicyCollector`, and `RouteControl` uses atomics
and `StateFlow.update` extensively. With no reviewer instruction and no
tripwire, read-then-act races (rule 2) and non-idempotent effects inside
`update` (rule 1) can be introduced freely. These are precisely the bugs
the immutable-state design was meant to prevent.

## Fix directions

1. Add both rules, verbatim or close to it, to the pedantic code review
   skill's checklist (`.agents/skills/mumla-pedantic-code-review/SKILL.md`),
   with a short example of each violation and its fix. This is a
   documentation change and can go straight to `master`.
2. Fix the `ServerSync` lambda: compute the merged value inside `update`
   and assign `syncingState`/`synced` from the returned value afterwards
   (for example, `val published = _state.updateAndGet { ... }; syncingState
   = published; synced = true`), keeping the merge atomic.
3. Optionally, a cheap heuristic check: flag `update {` lambdas that
   contain an assignment to a non-local `var` (`=` on an identifier not
   declared in the lambda). This will have false positives; keep it
   advisory or skip it.
4. Sweep `Session.kt`, `PolicyCollector`, and `RouteControl` once for rule-2
   violations (`state.value` read followed by an action decided on it).
   This audit did not do that sweep.

## Acceptance

- Both rules appear in the pedantic review skill.
- The `ServerSync` publish lambda has no assignments to `Session` fields.
- `./scripts/check.sh` passes.

## Related

- [19](19-pre-sync-local-intents-published.md)
- [25](25-user-state-writes-local.md)
