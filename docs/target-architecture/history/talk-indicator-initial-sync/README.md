# Talking Indicators, Three Times

Snapshot: `fix/talk-indicator-initial-sync` at `d75f0408`, on `master`
at `53ac5e6a`. Commit hashes below refer to `master` history.

Opening the channel list while the local user was already talking showed
the self row as passive until the next talk event. Remote rows looked
fine and the reporter could not reproduce a remote case. The fix makes
the live handle read self-aware and initial-syncs the PTT button and hot
corner. This note records why the same symptom kept coming back.

## Timeline

- **2026-11-10** — `00230382` ("humla: switch model over to immutable
  `SessionState`") replaced persistent `User`/`Channel` with live
  `UserHandle`/`ChannelHandle` (fresh instance per lookup, default
  `PASSIVE`).
- **2026-11-11** — `ad164673` ("audio: fix stuck remote talking
  indicators"). The step-2 handles silently broke `AudioOutput`'s
  `user.talkState != state` dedup: the `PASSIVE` following `TALKING`
  always compared equal on a fresh handle and was swallowed, so remote
  dots stuck on permanently. Fixed by dropping the Kotlin-side dedup and
  always stamping and fanning out; native already emits transitions only.
- **2026-11-14** — `2e114319` ("humla: rewire output to native render
  and levels", step 5a.3a). Talk becomes levels in a service-lifetime
  `TalkActivity` publisher (`users` plus `selfTalking`);
  `IUser.talkState` becomes a live read from the flow; the service fans a
  repaint hint for every session user on each change with no snapshot
  comparison.
- **2026-11-14** — `9c2c5198` ("humla: source self talk hint from
  `selfTalking`"). 5a.3a moved self talk into `TalkActivity.selfTalking`,
  but the hint collector built every hint from the remote `users` table,
  which never holds self. The local indicator stayed passive while
  remotes worked. Fixed with `talkStateOverride` on `UserHandle`: the
  service fires self's hint with the override sourced from the emitted
  `selfTalking` value.
- **2026-11-14** — `7fb6057d` ("humla: forward talk levels to
  `ChannelHandle` sites"). `Session` built every `ChannelHandle` without
  the flow, so `channel.users` rosters re-bound passive even while
  hint-updated visible rows lit. Fixed by passing `talkActivity` at all
  channel-handle sites; pinned by a `HumlaClientTest` driving sync
  through the live fake transport.
- **Report** — channel list rows created while already talking stay
  passive until stop/restart. Remotes could not be reproduced; self was
  deterministic.
- **Fix** — `d75f0408` in this branch (details below).

## Root cause of this round

Two facts combined:

1. The live read only consulted the remote table. `UserHandle.talkState`
   was `talkStateOverride ?: talkActivity.users[session] ?: PASSIVE`.
   Self never appears in that table, so any *normal* handle for the self
   session — exactly what `onBindViewHolder` reads via `channel.users` —
   was passive by construction. Only the *hint* handle carried the
   override, so only event-driven updates showed self talking.
2. The other self surfaces had no initial sync either.
   `ChannelFragment.configureInput()` set the PTT button from
   `session.isTalking()` (the toggle request flag, not the live level),
   and `MumlaService.updateHotCornerVisibility()` showed the corner
   without touching its talk state. Both waited for the next hint.

This is why creation was the trigger and stop/restart was the cure: the
UI missed the level that was already current and only the next transition
repaired it.

## The fix

- `UserHandle.talkState` is self-aware: when `talk.selfTalking` is true
  and the handle's session is the current self session, it reports
  `TALKING`. The override still wins, a null publisher or unsynced state
  still reads passive, and remote reads are untouched. Fresh binds
  (channel list rows, overlay rows, talk button) therefore initial-sync
  without waiting for a hint.
- `ChannelFragment.configureInput()` derives the button from the live
  self `talkState` (matching its own `onUserTalkStateUpdated` observer)
  and clears it on teardown races instead of keeping the old value.
- `MumlaService.updateHotCornerVisibility()` re-applies the cached
  `mSelfTalking` level after showing the corner.

No publisher, protocol, or contract changes; `StateEventLogger` handles
stay intentionally passive (chat log renders no talk dots).

## Why it kept slipping through

- Each fix covered the *event* path and left the *creation* path on the
  old assumption. `9c2c5198` proved the override wins for hints;
  `HandlesTest` even pinned that a plain handle stays passive — true at
  the time because `selfTalking` was false in that test, so the gap was
  invisible.
- The spec already said the right thing
  ([state](../../state.md#compatibility-handles-migration-only):
  "`IUser.talkState` from `VoiceEngine.talkActivity`, passive when
  absent"), but the implementation read only half of `TalkActivity`.
  Nothing tested a fresh self handle with `selfTalking` true.
- Remote and self share one `talkState` getter but have different
  sources, so "indicators work" in manual testing usually means remotes
  work. Self needs its own creation-time case: open the list mid-talk,
  rotate, or show the corner while holding PTT.

## Takeaways

- When a level splits into two sources (remote table plus self flag),
  every reader needs the combined view, not just the event fanout. Test
  the bind path, not only the hint path: construct a plain handle with
  `selfTalking` true and assert `TALKING`.
- UI created mid-state must initial-sync from the level, never rely on
  the next event. The existing "repaint hints keep no memory" rule cuts
  both ways: no stale snapshots, but also no catch-up for late
  subscribers — the bind is the catch-up.
