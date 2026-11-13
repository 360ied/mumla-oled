# 25 — `applyUserState` is a second writer of `local`

| | |
|---|---|
| Status | Resolved on `master`: `state.md` names the self-echo exception in the intents rule, the `listeningVolumes` comment, and the `UserState` row. Optional test remains. |
| Category | Spec inconsistency (code follows upstream; two spec statements conflict) |
| Severity | Benign |
| Verified | Yes |
| Area | State transitions |
| Spec | [state.md § User intents](../state.md#user-intents), [state.md § Types](../state.md#types) (`listeningVolumes`), [state.md § Server message transitions](../state.md#server-message-transitions) |
| Code | `libraries/humla/src/main/java/se/lublin/humla/state/UserTransitions.kt` (`applyUserState`, around lines 204-217) |
| Gates | Not gating |

Line numbers below refer to `master` at `092d9dc8`.

## Summary

The spec says intent methods are the only writers of `local`. When a
`UserState` for the local user carries `listening_volume_adjustment`,
`applyUserState` writes `local.listeningVolumes`. This matches upstream
Mumble's client (`MainWindow::msgUserState`), which the spec also names as
a reference, so the spec contradicts itself and the code picked upstream.

## What the spec says

`state.md` § User intents: "Intent methods on `Session` ... are the only
writers of `local` and of device-local `UserInfo` fields."

`state.md` § Types: `listeningVolumes // per channel, as last sent`.

The `UserState` transition row says only "Create or update `UserInfo`".

## What the code does

`UserTransitions.kt` (around line 204):

```kotlin
if (u.session == server.selfSession) {
    var volumes = local.listeningVolumes
    ... // apply listening_volume_adjustment entries
    local = local.copy(listeningVolumes = volumes)
}
```

## Impact

None today: the server echo carries the value just sent. But the
"intents are the only writers of `local`" invariant is false, which
matters for [19](19-pre-sync-local-intents-published.md)'s option 2 and
for any reviewer relying on the invariant.

## Fix directions

Correct the spec: `listeningVolumes` is "as last sent or echoed by the
server", and the `UserState` row lists the self-echo update of
`local.listeningVolumes`; amend the "only writers" sentence to name this
exception. Add a transition test for the echo if missing.

## Acceptance

Spec and code agree; `./scripts/check.sh` passes.
