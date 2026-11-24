# Step 2 — Immutable state

Part of the [migration](../migration.md). **Status: done.**
This is the step's plan as it was executed, kept as a record;
[`PROGRESS.md`](../PROGRESS.md) summarises what landed, and the living
specs hold the current design.

**Spike first:** live handles (see [state](../state.md#compatibility-handles-migration-only))
behind `ChannelListAdapter`. A Robolectric test drives the adapter over
handles through a scripted sequence (users join, move, mute, deafen,
start and stop talking, start and stop listening, and leave; channels are
renamed, re-ordered, linked, expanded, collapsed, and removed) and asserts
the rendered rows after each message. The spike passes when every assertion holds without changes
to the adapter.

1. `kotlinx-collections-immutable` in the version catalog. The state types,
   `ChannelTree`, and their tests.
2. Transitions ported from `ModelHandler`, one message family per commit
   (`ChannelState`/`ChannelRemove`/links; `UserState`; `UserRemove`;
   `PermissionQuery`; `ServerSync`, `ServerConfig`, `Version`,
   `CodecVersion`), each with its existing tests ported.
3. `SessionEvent` emission. A temporary adapter in `:libraries:humla`
   turns events into today's `HumlaLogger` strings, so the chat log keeps
   working; it moves to the app in step 6.
4. One commit switches the model over, since the halves cannot work
   apart: `HumlaService` holds a `MutableStateFlow<SessionState>` and
   applies the transitions; live handles replace `User` and `Channel`
   behind `IUser` and `IChannel`, whose navigation types narrow to the
   interfaces and whose setters are removed (each writer becomes a session
   intent); `IHumlaObserver` callbacks pass handles; `ModelHandler`,
   `User`, and `Channel` are deleted.
5. `AudioHandler`'s reads of self and local-mute state come from the state
   flow.

**Done when:** `Channel`, `User`, and `ModelHandler` are off the
allowlist (deleted); `StateImmutabilityTest` walks `SessionState`.

## Kill criterion

**Step 2's live-handle spike** shows screen behavior changes the tests
did not catch. Fix the harness before continuing.
