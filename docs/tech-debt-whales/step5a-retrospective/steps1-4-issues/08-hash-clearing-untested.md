# 08 — Hash-driven clearing has no transition test

| | |
|---|---|
| Status | Open — not gating. |
| Category | Verification gap |
| Severity | Benign (behavior implemented; untested) |
| Verified | Yes (no test sets a hash on an incoming message) |
| Area | State transitions |
| Spec | [testing.md § State-transition tests](../../target-architecture/testing.md#state-transition-tests), [state.md § Server message transitions](../../target-architecture/state.md#server-message-transitions) |
| Code | `libraries/humla/src/main/java/se/lublin/humla/state/UserTransitions.kt` (`applyUserState`, around lines 174-185), `state/ChannelTransitions.kt` (`applyChannelState`, around lines 92-103); tests under `libraries/humla/src/test/java/se/lublin/humla/state/` |
| Gates | Not gating |

Line numbers below refer to `master` at `092d9dc8`.

## Summary

`testing.md` lists hash-driven clearing among the field-level transition
details that must be tested. It is implemented, but no test sends a
`UserState` or `ChannelState` carrying `comment_hash`, `texture_hash`, or
`description_hash`. Every other enumerated detail has a test.

## What the spec says

`testing.md` § State-transition tests requires tests for "every
field-level detail listed under server message transitions (...
hash-driven clearing, link replace/add/remove)".

## What the code does

`UserTransitions.kt` (`applyUserState`):

```kotlin
if (msg.hasTextureHash()) {
    u = u.copy(textureHash = msg.textureHash, texture = null)
}
if (msg.hasTexture()) {
    u = u.copy(texture = msg.texture, textureHash = null)
}
if (msg.hasCommentHash()) {
    u = u.copy(commentHash = msg.commentHash, comment = null)
}
if (msg.hasComment()) {
    u = u.copy(comment = msg.comment, commentHash = null)
}
```

`ChannelTransitions.kt` (`applyChannelState`) has the equivalent for
`description_hash` / `description`.

`grep -rn "setCommentHash\|setTextureHash\|setDescriptionHash"
libraries/humla/src/test` returns nothing. (Several test files mention the
hash *fields* when constructing `UserInfo`/`ChannelInfo` values, but none
feeds a hash through a message.)

Minor related gap reported by the audit: `testing.md` also asks for "every
combination" of self-echo self-mute/self-deafen; `testSelfEchoNeverEmits`
covers 3 of the 8 combinations.

## Impact

A regression that keeps a stale comment, avatar, or channel description
after the server announces a new hash (or wrongly drops fresh content)
would ship unnoticed until a user sees wrong text or images.

## Fix directions

Add transition tests in `UserTransitionsTest` and `ChannelTransitionsTest`:

- user with `comment` set; `UserState` with `comment_hash` → `comment` is
  null, `commentHash` set;
- user with `commentHash` set; `UserState` with `comment` → `comment` set,
  `commentHash` null;
- same pair for `texture`/`texture_hash`;
- same pair for channel `description`/`description_hash`;
- a message carrying both hash and content (order of the `if`s decides the
  result; pin it).

Optionally extend `testSelfEchoNeverEmits` to all 8 self-mute/self-deafen
echo combinations.

## Acceptance

Tests exist and fail if the clearing lines are removed; `./scripts/check.sh`
passes.
