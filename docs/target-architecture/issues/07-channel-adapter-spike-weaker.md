# 07 — The step-2 live-handle spike test is weaker than specified

| | |
|---|---|
| Status | Open — not gating. |
| Category | Verification gap |
| Severity | Benign-to-moderate (the spike's conclusion is backed by weaker evidence; daily use since agrees with it) |
| Verified | Yes (test file read; spec compared) |
| Area | State / compatibility handles / app adapter |
| Spec | [migration.md § Step 2](../migration.md#step-2--immutable-state) ("Spike first"), [migration.md § Kill criteria](../migration.md#kill-criteria) (step 2) |
| Code | `app/src/test/java/se/lublin/mumla/channel/ChannelAdapterTest.java`; `libraries/humla/src/test/java/se/lublin/humla/state/HandlesTest.kt` |
| Gates | Not gating |

## Summary

Step 2 was gated on a spike proving that `ChannelListAdapter` renders
correctly over live handles through a scripted sequence of real changes.
The test recorded as resolving the spike is a plain JUnit test with Mockito
mocks that checks identity plumbing, not rendered rows across a sequence.

## What the spec says

`migration.md` § Step 2:

> **Spike first:** live handles (see state) behind `ChannelListAdapter`. A
> Robolectric test drives the adapter over handles through a scripted
> sequence (users join, move, mute, deafen, start and stop talking, start
> and stop listening, and leave; channels are renamed, re-ordered, linked,
> expanded, collapsed, and removed) and asserts the rendered rows after
> each message. The spike passes when every assertion holds without changes
> to the adapter.

Kill criterion:

> **Step 2's live-handle spike** shows screen behavior changes the tests
> did not catch. Fix the harness before continuing.

## What exists

`ChannelAdapterTest.java`:

- `public class ChannelAdapterTest extends TestCase` (JUnit 3), no
  Robolectric runner.
- Uses `mock(IUser.class)`, `mock(IChannel.class)` (two mocked users, one
  mocked channel), not live handles over a `SessionState` flow.
- Asserts item IDs and counts, null-safety of `updateUserState` and
  `notifyUserChanged`, `getTalkStateDrawable` returning null, and the
  avatar cache.
- No scripted message sequence; no rendered-row assertions (bound view
  text, icons, ordering) after each change.

`HandlesTest.kt` (library) does test live handles against a real state
flow, but not through the adapter.

`PROGRESS.md` step 2 row and the resolved item in `open-items.md` record
the spike as resolved by `ChannelAdapterTest` and `HandlesTest`.

## Evidence that the gap mattered

The step-2 regression that did ship, stuck talk indicators (fixed in
`f6244c86`), is a screen-behavior change through live handles: a fresh
handle's default `talkState` defeated a dedup guard. That is the kind of
change the specified spike (start and stop talking, rendered rows asserted
after each message) was designed to catch, and the kill criterion says to
fix the harness when such a change slips through. The harness was not
changed; only the bug was fixed.

## Impact

Low today: daily use since step 2 has found no further screen regressions.
But the adapter is still driven by live handles until step 7, and any
future change to handles or to state transitions has no screen-level test.

## Fix directions

1. Write the specified Robolectric test: a real `MutableStateFlow<SessionState>`,
   real `UserHandle`/`ChannelHandle` instances, the real
   `ChannelListAdapter` bound to a RecyclerView in a Robolectric activity
   (or with `onBindViewHolder` called on created view holders), a scripted
   sequence of transitions (use the pure transition functions to produce
   states), and assertions on bound rows (names, mute/deafen/talk/listen
   icons, ordering, expansion) after each step.
2. Include start/stop talking with fresh handles, to pin the
   `f6244c86` regression at screen level.
3. Record the result in `PROGRESS.md` step 2 notes.

## Acceptance

- The test fails if the `f6244c86` fix is reverted (talk indicator stays
  on).
- It runs in `testFossDebugUnitTest` (or the app's unit test task) within
  `./scripts/check.sh`.

## Related

- [24](24-handles-drop-navigation-after-removal.md): another handle
  semantic the adapter depends on.
