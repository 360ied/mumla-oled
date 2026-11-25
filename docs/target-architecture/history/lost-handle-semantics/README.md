# What the Handles Were Doing

Snapshot: `step7-screens-and-service-cut-over` at `c4385173`, on `master`
at `4c27c0c8`. Commit hashes below refer to that branch's history.

The step-7 review found three regressions on the boundary where the live
compatibility handles were replaced by IDs plus `UserInfo`/`ChannelInfo`
values. Two came from the same cause, and so did a bug from step 2, when the
handles were introduced. Each time, an object model was replaced and the new
model kept the data while losing a property that callers relied on without
saying so. This note records what those properties were, where each one was
lost, and why the knowledge did not carry across.

## Three properties nobody listed

The pre-migration `User` and `Channel` model objects, and then the step-2
handles, gave callers three things beyond their fields:

| Property | Model objects (before step 2) | Handles (steps 2–6) | IDs plus values (step 7) |
|---|---|---|---|
| Equal by identity of the user or channel | Yes: one object per user | Yes: `equals` by session or channel ID | Only where a type says so |
| A read returns the current value | Yes: the object was mutated in place | Yes: every getter read the latest state | Only where the caller looks it up again |
| Per-object mutable state survives between uses | Yes | No: a fresh handle per lookup | No |

Code that held a reference got all three for free, so none of them showed up
at call sites. Each migration preserved what the call sites showed, which was
the fields.

## Timeline

- **2026-11-10** — `00230382` ("humla: switch model over to immutable
  `SessionState`") replaced the persistent model objects with live
  `UserHandle`/`ChannelHandle`, built fresh on each lookup. Equality and live
  reads were deliberate and written down. Carried state was not, and it was
  lost.
- **2026-11-11** — `ad164673` ("audio: fix stuck remote talking
  indicators"). `AudioOutput` deduplicated with
  `user.talkState != state`. On a persistent object that compared against
  the last state written; on a fresh handle it compared against the default
  `PASSIVE`, so the `PASSIVE` after `TALKING` was swallowed and remote
  indicators stuck on. This is the third property, lost in step 2. The
  [talking-indicator history](../talk-indicator-initial-sync/README.md)
  follows the later rounds, which came from handles built at sites that did
  not pass the talk flow.
- **Steps 2–6** — The handle KDoc in `Handles.kt` stated the other two
  properties and why they mattered:

  > Snapshots would compile and silently freeze screens, so these read the
  > current value on every access. […] Equal when IDs are equal.

  `state.md` § Compatibility handles said the same.
- **Step 7 plan** — [step 7](../../steps/7-screens-and-service-cut-over.md),
  item 6, specified that the adapter "keeps channel and user IDs, builds each
  row from the latest state", which covers live reads during render. For the
  menus it specified that `UserMenu`, `ChannelMenu`, and
  `PermissionsPopupMenu` "take the current `UserInfo` or `ChannelInfo`": a
  value captured when the menu opens. Nothing in the plan replaced the
  handles' equality.
- **2026-11-24** — `b098f615` ("ui: move the channel screen onto a
  ViewModel") introduced `ChatTarget`, which replaced the handle-based chat
  target with IDs. It was a plain class with a private constructor and
  factory functions, so `==` was identity.
- **2026-11-24** — `969ac733` ("ui: move the channel list onto a
  ViewModel") implemented the menus as the plan specified: they read their
  toggles from the captured value.
- **2026-11-24** — `ec0dac16` ("humla: delete the observer bridge and
  handles") deleted `Handles.kt`, and with it the only KDoc that named the
  two properties. `264414d1` ("docs: close step 7") replaced the
  Compatibility handles section with § Reading state, which kept "looks the
  value up in the latest state each render" and dropped equality.
- **2026-11-24** — `11cde7e4` ("app: close screen-state parity gaps from
  step 7"), a parity audit against the deleted screens, fixed five other
  state paths. It compared what each path displayed and kept, and these two
  bugs display correctly: the menu shows the right check marks when it opens,
  and the chat target is right after the first tap.
- **Review** — a read-only review of the whole branch flagged both: the
  `ChatTarget` comparison (raised as P0, ranked P1) and the menu toggles
  (P1).
- **Fix** — `0fb975af` ("app: fix three step-7 review regressions"), details
  below.

## The two step-7 bugs

**Equality.** `ChannelListFragment` finishes the chat action mode when the
tapped target equals the current one:

```kotlin
val target = ChatTarget.channel(channel.id)
if (channelViewModel.chatTarget.value == target && mActionMode != null) {
    mActionMode?.finish()
}
```

`target` is always a new instance, so the comparison was always false and a
second tap restarted the action mode instead of dismissing it. Before step 7
the same check compared handles, which were equal by ID.

**Live reads at action time.** `UserMenu` computed every toggle from the
value captured when the row was tapped:

```kotlin
viewModel.setMuteDeafState(user.session, !(user.muted || user.suppressed), user.deafened)
```

If another client muted or deafened the user while the menu was open, the
toggle inverted the old value and sent the state the user already had. The
handle-based menu read `mUser.isMuted` when the item was clicked, which was
the current value. `ChannelMenu` had the same shape for the channel's
description and links.

## Why it recurred

**The rules lived with the code they described.** The handle KDoc was the
clearest statement of both properties, and deleting the handles deleted it.
The living spec was rewritten in the same step and kept only the render half.
Nothing outside `Handles.kt` said that a stand-in for a user must be equal by
ID, or that an action must read again.

**The plan specified the bug.** "Take the current `UserInfo`" reads as
correct when written: the value is current when the menu opens. The gap is
the time between opening and clicking, which the plan's wording does not
show. The implementation followed the plan, and a parity audit that compared
behavior when each screen opens could not see the gap.

**The compiler accepts both forms.** `==` on a class without `equals`
compiles. So does a field read from a captured value. Neither fails a test
unless a test changes state between capture and use, and no menu test did.

**Each instance looks like a one-off.** Taken alone, each of the three bugs
reads as a local slip. The pattern only appears when they are listed against
the property each one lost.

## What changed

- `0fb975af` makes `ChatTarget` a `data class` and adds a test that two
  targets with the same ID are equal. Both menus now read the user or
  channel from the latest state on every access, through a new
  `ChannelListViewModel.user(session)` lookup, and use the tapped values only
  if the entry has left state. Tests change state after the menu is built
  and assert that each toggle inverts the new value.
- [state § Reading state](../../state.md#reading-state) now states both
  rules: look the value up again for each render and each action, and a type
  that stands in for a user or channel by ID is equal by that ID.
- A sweep of `app/src/main` found no other class holding a `UserInfo` or
  `ChannelInfo` across a user interaction.

Neither rule is in the architecture gate. Detecting `==` on a type without
value equality needs type information, and "read again at action time" is a
judgment about when a value is used. A regex for either would produce false
positives or miss real cases.

## For the next object-model change

Before replacing a type that callers hold references to, list what holding
the reference gives them, not only its fields: equality, freshness of reads,
and state that persists on the object. Then write each item into the spec
that outlives the old type, before the old type and its comments are deleted.
