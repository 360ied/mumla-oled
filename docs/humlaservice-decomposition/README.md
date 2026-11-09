# `HumlaService` Decomposition: Findings and Refactoring Plan

`HumlaService` is the largest hand-written class in the tree and the single
hottest file in the repository. This document answers two questions: which
parts of its size are *earned* (the service genuinely is the session) and
which are work that belongs elsewhere; and, for the latter, how to move it
out without destabilizing a file that changes more than once a day.

Snapshot: commit `1d6d509c` (2026-10-31). `HumlaService.kt` is byte-identical
through `535088d3` (2026-11-09), so every line reference below is current at
that commit. The appendix reproduces the measurements.

Subject: [`HumlaService.kt`](../../libraries/humla/src/main/java/se/lublin/humla/HumlaService.kt).

## Summary

- **One concern earns the god-class label: audio route policy.** It is about
  855 of 2,357 member lines (~36%) and 16 of 52 fields. It has no unit
  tests beyond a one-line truth table. Roughly one in five commits touching
  the file since August were route work, more than half of them fixes or
  labeled rounds of review findings. The route logic is being verified by
  reading it, because it cannot be unit-tested on the JVM where it lives.
- **The route state is smaller than it looks.** Of its 16 fields, one is
  write-only (`mSettlePending`), one always equals another
  (`mVoiceModeClaimed` ≡ `mVoiceModeHeld`), one is a function argument passed
  through a field (`mSettleFired`), and two are cached copies of the same
  decision (`mMediaRouteSelected`, `mAppliedVoiceRoute`). The real state is
  three small orthogonal machines plus two inputs.
- **The route choreography is written four times.** Sync, the funnel, the
  disconnect callback, and `onDestroy` each re-implement part of "apply the
  route", and the sync copy has drifted from the funnel. A traced (not yet
  device-reproduced) consequence: CONNECT while CONNECTED skips the old
  session's teardown entirely, route and audio pipeline alike (§3.4). The
  in-app UI does not take that path, so it is a latent hazard rather than
  a field bug.
- **The configuration fields are the session's, not misplaced.** The 11
  connection parameters are read by `connect()` and
  `onConnectionEstablished()`. The fix is one value object, not eviction.
- **The `Toast` is misowned, not unnecessary.** It is the user's only signal
  that Bluetooth permission was denied. Turn it into an observer event.
- **Recommended plan:** shrink the route state in place (§5, slice 1),
  put the platform behind ports while the code still lives in the service
  (slice 3), extract it whole into an `AudioRouteController` (slices 4–5),
  write the JVM tests the ports make possible (slice 6), then unify the
  choreography (slice 7). Configuration and power-lock tidying are a
  separate, lower-priority track. Leave the connection machine, the façade,
  and the subclass hooks alone.

Expected end state: `HumlaService` drops from 52 fields to about 23 and from
2,442 lines to roughly 1,500–1,650, and the route policy gains a test suite.
The line count is a side effect, not the goal.

## 1. The class as measured

| metric | value |
|---|---:|
| total lines | 2,442 |
| blank lines | 201 |
| comment-only lines | 546 (22%) |
| instance fields | 52 (50 `var`, 2 `val` listeners) |
| top-level members | 167 |
| supertypes | `LifecycleService` + 4 interfaces |
| `override fun` | 90 |
| `IHumlaSession` + `IHumlaService` API members | 48 + 15 |

Class header (L84–85):
`LifecycleService(), IHumlaService, IHumlaSession,
HumlaConnection.HumlaConnectionListener, HumlaLogger`. The `AudioHandler` and
`AudioOutput` listener roles are anonymous objects held in fields
(`mAudioInputListener`, `mAudioOutputListener`), not supertypes.

The class is also a **base class**: `MumlaService` in the app module extends
it. `MumlaService` overrides `onAudioRouteChanged()` (a `protected open` hook
inside the route code), `onConnectionSynchronized()`,
`onConnectionDisconnected()`, `buildConnectDelta()`, `onStartCommand()`,
`cancelReconnect()`, and the text-message senders, and it calls `apply()`,
`isMediaRouteActive()`, and `isBluetoothScoActive()`. Every extraction below
has to keep that inheritance contract intact.

## 2. Where the lines and state actually sit

### 2.1 Bucketed by member name

The appendix script assigns each top-level member to a concern by name.
Percentages are of the 2,357 lines covered by members; the remaining 85 are
inter-member spacing.

| cluster (script bucket) | members | lines | share | fields |
|---|---:|---:|---:|---:|
| audio route / SCO / voice mode | 39 | 690 | 29% | 17 |
| connect / reconnect / network | 24 | 436 | 18% | 8 |
| residual (`DELEGATION`) | 48 | 408 | 17% | 0 |
| platform shell | 13 | 257 | 11% | 5 |
| config-delta application | 12 | 208 | 9% | 9 |
| handler wiring | 8 | 122 | 5% | 4 |
| mute/deaf reconciliation | 6 | 105 | 4% | 2 |
| whisper / text messages | 9 | 105 | 4% | 2 |
| input-mode holders | 5 | 9 | <1% | 5 |
| logging | 3 | 17 | 1% | 0 |

### 2.2 Corrections the name-based table cannot see

Name bucketing assigns each member wholesale. Three corrections matter for
the plan:

1. **About 165 lines of route code live inside members bucketed elsewhere:**
   - `onCreate` L527–575: AudioManager lookup, `BluetoothScoManager`
     listener, and device-callback registration.
   - `onDestroy` L594–622: route teardown.
   - `onConnectionSynchronized` L867–892: a second copy of the route
     application.
   - `onConnectionDisconnected` L931–947: route teardown.
   - `disconnect` L748–750.
   - `apply` L1743–1751, L1789–1808, L1822–1833: route inputs and the
     reload.

   Route policy is therefore about **855 lines (~36%)**, not 690.
2. **The residual bucket is not all façade.** It is the script's default for
   unmatched names, not a positive match. It contains the 72-line companion
   object and about 33 lines of the trailing `ConnectionState` enum (the
   regex doesn't recognize `enum class`, so it gets folded into
   `isListeningToChannel`). It also holds `IHumlaService` members such as
   `HumlaSession()`, `registerObserver()`, and `getConnectionState()`.
   Only 38 of its 48 members are `IHumlaSession` members, and 10
   `IHumlaSession` members landed in the whisper and mute buckets. True
   delegation is about **300 lines (~13%)**: one-line forwards to
   `requireModelHandler()` or `requireConnection()`, often wrapped in a
   `NotConnectedException` → `IllegalStateException` try/catch.
3. **Field ownership needs reclassifying:**
   - `mHalfDuplex` is input-mode configuration. It is read only in `apply()`
     alongside `mTransmitMode`, not by route code. Route owns **16** fields.
   - `mLocalMuteHistory` and `mLocalIgnoreHistory` are connect-time
     `ModelHandler` parameters that force a reconnect. They are connection
     parameters, not mute state.
   - The 9 "config" fields are also connection parameters: `connect()`
     (L633–714) and `onConnectionEstablished()` (L817–832) read them.
     Together with the two histories that makes **11 connection
     parameters**, which belong to the session.

### 2.3 Taxonomy

| category | lines (approx.) | fields | disposition |
|---|---:|---:|---|
| route policy | ~855 | 16 | **extract** (§4–§5) |
| connection machine + handler wiring | ~510 | 12 | keep |
| façade delegation | ~300 | 0 | keep |
| connection parameters + `apply()` mapping | ~170 | 11 | **consolidate** into one value object |
| platform shell (lifecycle, wake/Wi-Fi locks) | ~180 | 5 | keep; optional power-hold tidy |
| mute, whisper, text, logging | ~230 | 2 | keep |
| input-mode holders | ~10 | 6 | fold into config track |
| companion object, `ConnectionState` enum | ~105 | 0 | keep (route constants move with route) |

Nothing in the class is unnecessary behavior. The one item previously
filed as "should not exist", the `Toast` in `onScoFailed`, has a
permission-denied branch with no other UI path (§3.5).

## 3. Anatomy of the route policy

### 3.1 What the 16 fields really are

| role | fields | target |
|---|---|---|
| inputs (from `apply`) | `mScoRequested`, `mHandsetAudioStream` | 2 fields |
| platform references | `mAudioManager`, `mScoManager`, `mDeviceCallback` | 3 fields (become ports) |
| observation | `mScoRouteActive` (confirmed SCO link) | 1 field |
| applied decision | `mMediaRouteSelected`, `mAppliedVoiceRoute` | 1 enum: `VOICE_SCO` / `VOICE_HANDSET` / `MEDIA` |
| voice-mode ownership | `mVoiceModeHeld`, `mVoiceModeClaimed`, `mSavedAudioMode` | 1 sealed type: `Released` / `Claimed(savedMode)` |
| settle recheck | `mSettleAttempts`, `mSettleFired`, `mSettlePending`, `routeSettleJob` | attempts + job |
| debounce | `routeDebounceJob` | 1 field |

Concrete redundancy, verifiable by tracing every read and write:

- **`mSettlePending` is write-only.** It is assigned at L266, L271, and L284
  and never read. Its comment ("the flag can never claim protection no
  callback provides") describes a guard that no code consults.
- **`mVoiceModeClaimed` always equals `mVoiceModeHeld`.** Both are set true
  together (L1165–1166) and false together (L1186–1187). The "held but
  unclaimed" state that the field comment (L195–199) and the
  `ensureVoiceMode` KDoc (L1135–1137) describe is unreachable:
  `ensureVoiceMode` now returns *without* setting either flag when the mode
  is externally held (L1148–1155). The `if (!claimed) return` branch in
  `releaseVoiceMode` is dead.
- **`mSettleFired` passes an argument through a field.** The settle job sets
  it (L272) immediately before calling `applyAudioRoute`, which reads and
  clears it on entry (L1364–1365). All funnel callers are main-confined, so
  this is exactly a `trigger = SETTLE` parameter.
- **`mMediaRouteSelected` means two things.** Inside the funnel it holds the
  gated decision (`!sco && mediaPresent`, L1321–1322). In `onCreate` (L575)
  and `onConnectionDisconnected` (L938) it is assigned the raw device query
  with no SCO gate. `mAppliedVoiceRoute` is
  `computeVoiceRoute(sco, media)` cached for change detection.

So the earlier "nine interacting booleans" are three small machines:
- the applied route (3 states);
- mode ownership (2 states plus a saved int);
- a settle budget (0–2 plus a job handle).

Around them sit two inputs and one observation.

### 3.2 The choreography is implemented four times

"Apply the current route" happens in four places, each a partial copy:

| site | decision | voice mode | foreign-SCO counter | pipeline | notify | settle |
|---|---|---|---|---|---|---|
| `applyAudioRoute` (funnel) | ✓ | ✓ | **before** pipeline | if changed | if changed | arm / cancel / budget |
| `onConnectionSynchronized` | ✓ | ✓ | **after** pipeline | always | always | arm only, no budget |
| `onConnectionDisconnected` | raw query | release | — | shutdown | — | cancel |
| `onDestroy` | reset | release | — | — | — (deliberate) | cancel |

The funnel's comment (L1374–1376) states the ordering rule: clear foreign
SCO *before* creating a media pipeline. The sync copy creates the pipeline
first. In the normal flow this is unobservable, because the voice mode is
always released before sync so the counter is a no-op. It becomes reachable
on the path in §3.4. This drift is what duplication predicts. A single
entry point with an explicit trigger removes the class of bug.

### 3.3 Threading is split across two thread origins and a shared monitor

- **Origins.** Device callbacks, SCO callbacks, settings, and the sync
  callback run on main; sync is posted by `HumlaConnection` (`HumlaConnection.kt` L248).
  `onConnectionDisconnected` runs **directly on connection threads**: via
  `handleFatalException` and `onTCPConnectionDisconnect`, which are not
  posted. `disconnect()` and `retryBluetoothSco()` are binder-reachable.
  The comment at L239–241 ("the filtered callbacks arrive on Main via Handler
  posts") is true only of sync.
- **Monitor.** Route fields are guarded by `synchronized(this)` and
  `@Synchronized` on the service instance. Because `MumlaService` *is* that
  instance, its unrelated chat-log methods (`getMessageLog`,
  `clearMessageLog`, `updateActiveServerLog`, `addChatMessageToLog`, the
  block at `onDestroy`) contend on the same lock. `retryBluetoothSco` also
  snapshots `mConnection` and `mConnectionState` under it. Those fields are
  never written under the monitor, so that snapshot is not atomic (its own
  comment admits this).

Extracting the route machine gives it a private lock. This decouples the
chat log for free and makes the route's lock discipline something a reviewer
can check inside one class.

### 3.4 Session end is inferred from a callback that can be dropped

`connect()` tears down a prior connection with `mConnection?.disconnect()`,
then bumps `connectionGeneration` (L657–665). `HumlaConnection.disconnect()`
is asynchronous: the formal `onConnectionDisconnected` arrives later from the
TCP read loop's `finally`. By then the generation has moved, so the
generation filter drops it as stale. That is deliberate: it protects the new
attempt. The side effect is that **none of the old session's route teardown
runs** on CONNECT while CONNECTED, which `onStartCommand` permits; it guards
only CONNECTING:

- the claimed `MODE_IN_COMMUNICATION` stays held through the new handshake;
- the SCO link and `mScoRouteActive` carry over;
- an armed settle job survives;
- the old `AudioHandler` keeps capturing until the new sync's
  `createAudioHandler()` replaces it.

The last item is not route state. Whatever fixes this has to end the whole
old session, pipeline included, not only the route.

**Reachability.** The in-app UI never takes this path.
`MumlaActivity.connectToServerWithPerm()` turns a tap on another server
while connected into a switch dialog. The dialog calls `disconnect()` and
reconnects only from the resulting `onDisconnected` callback, so the old
teardown runs before the new attempt starts. `MumlaService` is not
exported. The network-callback reconnect runs only while reconnecting,
which means after the old session's disconnect callback has already torn
down. What remains is an `ACTION_CONNECT` that lands while the activity has
no bound service, or a future caller that skips the dialog. This is a
latent hazard, not an observed field bug.

This is traced from code and not yet reproduced on a device. Either way it
shows the structural point: session end should be an explicit event the
connection machine sends to the route owner, not something the route code
infers from a callback the connection machine may legitimately discard.

### 3.5 The `Toast` is a missing observer event

`onScoFailed` (L542–554, inside `onCreate`) shows
`R.string.bluetooth_sco_permission` when the reason is
`BluetoothScoManager.REASON_PERMISSION`, and `R.string.bluetooth_sco_failed`
otherwise. The permission message is the only place the user learns that
headset audio needs a Bluetooth grant. Deleting it would be a regression.
What is wrong is the layer: a protocol library rendering UI with its own
string resources. Both strings exist only in the library's default locale,
so moving them to the app costs nothing.

### 3.6 No test coverage, and the churn shows it

The route policy's only test is `AudioRoutePolicyTest`, which pins the
one-line `computeVoiceRoute(sco, media)` truth table. The settle budget,
debounce coalescing, mode-hold restore guard, foreign-SCO counter gating, and
teardown races are untested. They can't be unit-tested on the plain JVM in
place:
- they need a constructed `Service`;
- they call `AudioManager`;
- they schedule on `lifecycleScope`.

The existing `HumlaServiceVoiceTargetTest` gets an instance only through
reflection. Robolectric would lift the first and third limits.
`:app` already has it as a test dependency; `:libraries:humla` does not.
But the route code also drives `BluetoothScoManager` and API-level
`AudioManager` branches, and those would need shadows. That is enough work
that putting ports in front of the platform (§5, slice 3) is the cheaper
way to get tests.

The history shows the cost. Of 93 commits touching `HumlaService` since
2026-08-01, 18 name route, SCO, or settle work in the subject, and 10 of
those are fixes, hardening, or labeled review rounds. Examples:
- `humla: fix round-7 settle, counter and pre-hold`
- `humla: unwedge settle flags, spare carrier mode`
- `humla: fix fourth review findings on SCO route`
- `humla: fix fifth review findings on SCO teardown`

The comment density tells the same story. The route region (L1128–1610) is
33% comment-only lines against the file's 22%, and the comments record field
pathologies such as staggered BT profile attach, system-raised SCO, and
carrier calls, overcome in place.

## 4. Target design: `AudioRouteController`

One class in `se.lublin.humla.audio` owns all route state and all route
choreography. `HumlaService` owns an instance, feeds it events, and forwards
three API members to it.

```kotlin
internal class AudioRouteController(
    private val platform: AudioPlatform, // mode get/set, output-device query,
                                         // system-SCO query/clear, device callback
    private val sco: ScoLink,            // start/stop/isIdle/isActive/isScoDeviceAvailable
    private val scope: CoroutineScope,   // debounce + settle timers (TestScope in tests)
    private val host: Host,
) {
    interface Host {
        val isLive: Boolean              // connection state == CONNECTED
        val sessionToken: Int            // connectionGeneration; replaces connection
                                         // identity checks in retry/attach re-validation
        fun hasLivePipeline(): Boolean
        fun recreatePipeline()           // createAudioHandler(); may throw AudioException
        fun onRouteChanged()             // subclass hook, then mCallbacks fan-out
        fun onScoChanged(active: Boolean)
        fun onScoFailed(reason: String?) // replaces the Toast (§3.5)
    }

    fun setScoRequested(requested: Boolean)   // inputs from apply()
    fun setHandsetStream(stream: Int)
    fun onEvent(event: RouteEvent)            // the single funnel
    fun decision(): RouteDecision             // pushed into AudioHandler.Builder
    val isMediaRouteActive: Boolean
    val isScoActive: Boolean
    fun retrySco(): Boolean
}

sealed interface RouteEvent {
    data object SessionStarted : RouteEvent
    data object SessionEnded : RouteEvent       // disconnect callback AND superseding connect()
    data class DevicesChanged(val added: Array<AudioDeviceInfo>?) : RouteEvent
    data class ScoLinkChanged(val active: Boolean) : RouteEvent
    data class SettingsChanged(val reload: Boolean) : RouteEvent // decided by apply()
    data object SettleRecheck : RouteEvent
    data object Destroyed : RouteEvent          // bare teardown, no notify
}

enum class AppliedRoute { VOICE_SCO, VOICE_HANDSET, MEDIA }
sealed interface ModeHold {
    data object Released : ModeHold
    data class Claimed(val savedMode: Int) : ModeHold
}
```

Design constraints:

- **Preserve the subclass hook and its ordering.** `Host.onRouteChanged()`
  is implemented in `HumlaService` as `onAudioRouteChanged()` followed by
  `mCallbacks.onAudioRouteChanged()`. `MumlaService` relies on the hook
  running before observers. The hook stays `protected open`.
- **Keep the API members on the service.** `isBluetoothScoActive()`,
  `isMediaRouteActive()`, and `retryBluetoothSco()` become one-line
  forwards.
- **Ports, not Android types, at the seam.** `AudioPlatform` wraps the
  eight `AudioManager` methods the route code uses, including the
  `Build.VERSION` S+ split for the communication device. `ScoLink` is an
  interface that `BluetoothScoManager` implements. Both get trivial fakes,
  so the controller is testable on the JVM with `kotlinx-coroutines-test`.
  That library is already a `:libraries:humla` test dependency.
- **Route decides; the builder carries it.** Today route writes four
  `AudioHandler.Builder` fields under the service monitor. In the target,
  `createAudioHandler()` pushes `route.decision()` into the builder
  immediately before `initialize()`, which is the only point those fields
  are consumed. Builder knobs (bitrate, boost, input mode, frames per
  packet) remain configuration's.
- **Route owns when the pipeline is recreated, for every reason.** The
  voice mode must be held before capture starts, so every live recreate
  goes through the funnel. That includes a settings reload with no route
  change, such as a bitrate change: `apply()` already reloads through
  `applyAudioRoute(..., forceReload = true)`. The controller therefore owns
  the timing of every live recreate (`Host.recreatePipeline()`), while
  configuration decides only *whether* a reload is due. State this in the
  slice 4 commit; it is the seam's main non-obvious consequence.
- **Constants move with their owner.** `ROUTE_SETTLE_RECHECK_MS`,
  `ROUTE_SETTLE_MAX_RECHECKS`, `ROUTE_RECOMPUTE_DEBOUNCE_MS`, and
  `computeVoiceRoute` leave the companion object.

What stays in `HumlaService` for route: one field (the controller), its
construction in `onCreate`, the `Host` implementation, three API forwards,
and one `onEvent` call at each lifecycle edge.

## 5. Migration plan

Each slice is a separate, behavior-preserving commit (or short series) with
`./scripts/check.sh` green. The file absorbs about 1.7 edits a day, so slices
should be small enough to land between other work, and the big move
(slice 4) should land in one sitting.

**Slice 1: shrink route state in place.** No moves.
- Delete `mSettlePending`.
- Collapse `mVoiceModeHeld`/`mVoiceModeClaimed`/`mSavedAudioMode` into
  `ModeHold` and delete the dead `!claimed` branch.
- Replace `mSettleFired` with an explicit trigger parameter on
  `applyAudioRoute`.
- Fix the stale comments: L195–199, L1135–1137, L239–241, L257–260.

Net effect: −4 fields (one deleted, three collapsed into one, one turned
into a parameter), fewer things to carry across the move. Pure refactor,
reviewable line by line.

**Slice 2: Toast → observer event.**
- Add `onBluetoothScoFailed(reason: String?)` to `IHumlaObserver`, with a
  no-op in the `HumlaObserver` adapter. Every in-tree observer extends the
  adapter.
- Fan it out through `HumlaCallbacks` from the same point the `Toast` fires
  today.
- `MumlaService`'s existing `mObserver` shows the same `Toast` with the same
  two strings, moved to app resources.
- Remove `android.widget.Toast` and the `R` string usage from
  `HumlaService`.

Behavior is identical, including the accepted re-toast on each failing
device edge.

**Slice 3: ports in place.** No moves.
- Introduce `AudioPlatform` (the eight `AudioManager` methods route code
  uses, including the `Build.VERSION` S+ split) and `ScoLink` (implemented
  by `BluetoothScoManager`).
- Construct them in `onCreate` and switch every route call site in
  `HumlaService` over, one for one.

This is a mechanical substitution, reviewable without understanding the
route policy. It shrinks the move to come, which is otherwise untested:
slice 4 then relocates code that already talks only to ports. If cheap
characterization tests are wanted before the move, write them against the
ports here, using Robolectric only for the `Service` shell (§3.6).

**Slice 4: extract `AudioRouteController` as one unit.**
- Move all remaining route fields, every route member, and the 165
  embedded lines (§2.2) behind the §4 interface, with the existing volatile
  and lock discipline but on a **private lock object**.
- Keep the four choreography copies as four controller methods, still
  divergent, so the move is mechanical and diffable.

Hard rule: the route monitor group moves atomically. A half-moved state in
which some route fields are guarded by the service monitor and others by
the controller's lock is a data race, not an intermediate step.

Lock-identity change: route no longer shares a monitor with `MumlaService`'s
chat log or with `retryBluetoothSco`'s connection snapshot. Neither sharing
provided a guarantee (§3.3), so this is safe, but state it in the commit
body.

**Slice 5: route inputs and pipeline handoff.**
- `apply()` calls `route.setScoRequested()` and `route.setHandsetStream()`.
- `apply()` keeps computing whether to reload, including the SCO-only
  `suppressReload` rule, and passes the result as `SettingsChanged(reload)`.
  The rule reads `delta.hasOtherAudioKeys()`, a fact about the config
  delta that the controller should not need to see.
- `createAudioHandler()` pushes `route.decision()` into the builder.
- After this slice, no route field or builder route flag is touched outside
  the controller.

**Slice 6: tests.** This is the payoff that justifies the extraction, so
schedule it immediately after the move and not "later". Use fakes for
`AudioPlatform` and `ScoLink` and a `TestScope` for virtual time. Minimum
suite, each case corresponding to a past fix commit:
- the settle budget: at most two rechecks per episode, a fresh edge resets
  it, and settle runs consume it;
- debounce: a burst of device edges produces exactly one recompute;
- mode hold: never claims over `MODE_IN_COMMUNICATION` or `MODE_IN_CALL`,
  and restores the saved mode only while ours is still current;
- the foreign-SCO counter: fires only after our claimed hold, with SCO
  unrequested and confirmed SCO down, never during `MODE_IN_CALL`, and
  clears before the media pipeline is created;
- the teardown race: a queued SCO-false notify is suppressed once a newer
  link confirms;
- `retrySco` re-validation across a session-token change.

**Slice 7: unify the choreography.** These are the only intentional behavior
changes in the route plan. Each gets its own commit and is justified by a
slice-6 test.
- Collapse the four copies into `onEvent`:
  - sync becomes `SessionStarted` and goes through the funnel's ordering,
    which fixes the counter-after-pipeline drift (§3.2);
  - disconnect becomes `SessionEnded`;
  - destroy becomes `Destroyed`.
- Replace `mMediaRouteSelected`/`mAppliedVoiceRoute` with `AppliedRoute`.
- Close §3.4 by having `connect()` end the old session before it overwrites
  `mConnection`:
  - shut down and unregister the old `AudioHandler`;
  - send `route.onEvent(SessionEnded)`;
  - decide explicitly whether observers see `onDisconnected` (today they go
    straight to `onConnecting`).

  This is a few lines inside `connect()`, not a split of the connection
  machine. The route event alone would leave the old pipeline capturing.
  Because the path is unreachable from the in-app UI and the service is not
  exported, a device reproduction needs an in-process trigger, such as a
  debug-only hook or an instrumentation test. Record the before/after.
  Given the reachability (§3.4), this is the lowest-priority item in the
  slice.

**Slice 8 (optional): main-thread confinement.** With every entry point
going through `onEvent`, the controller could post off-main events
(`SessionEnded` from connection threads, `retrySco` from binder) to main and
drop every `@Volatile` and lock. The ordering argument is that `connect()`
is main-confined, so a teardown posted before a reconnect is initiated is
FIFO-ordered ahead of it. Explicit `disconnect()` currently tears SCO down
synchronously on purpose. Do this only with slice-6 tests in place and a
dedicated review of that ordering claim.

### Configuration track (after slice 5)

Slice 5 has already moved the route inputs out of `apply()`. Two things
remain:

- **`ConnectionParams`.** Replace the 11 connection-parameter fields with
  one value held in a single `@Volatile` field. `connect()` and
  `onConnectionEstablished()` read one snapshot. Keep `reconnectNeeded`
  presence-based in this step: today any delta that *contains* a
  reconnect-relevant key returns true, even for an unchanged value. Note
  that `Server` is mutable (`setResolved` in `onConnectionEstablished`), so
  value equality on it is not a safe basis for a diff.
- **Audio settings.** Move the builder-knob and input-mode mapping
  (threshold, boost, transmit mode, quality, frames per packet, half duplex,
  leveler) into an applier beside `AudioHandler.Builder`, with an
  `InputModes` holder for the three mode instances, `mTransmitMode`, and
  `mHalfDuplex`.

`apply()` becomes a roughly 25-line dispatcher:
1. merge params;
2. push access tokens live;
3. apply audio settings;
4. send `route.onEvent(SettingsChanged(reload))`, with `reload` computed
   here (§5, slice 5);
5. return `reconnectNeeded`.

### Optional: power-hold policy

Wake-lock and Wi-Fi-lock transitions are spread over seven sites with four
different durations:
- 15 s on attempt;
- indefinite when live;
- delay + 15 s while a reconnect is scheduled;
- released when idle.

A cross-cutting invariant also sits in `cancelReconnect` ("never drop the
hold while CONNECTED or CONNECTING"). A small `ConnectionPowerHold` with one
method per transition would put those rules in one table. Low risk, modest
value; do it only when these sites next need a change.

### Explicitly out of scope

- **Splitting the connection machine.** `connect`, the five callbacks,
  `setReconnecting`, the network callback, and `connectionGeneration` form
  one state machine with real ordering constraints. Splitting it from its
  state would create a distributed god object. The §3.4 fix in slice 7 is
  a few lines inside `connect()`, not a split.
- **Restructuring the façade.** About 300 lines of one-line forwards are the
  cost of a published API. Kotlin `by` delegation does not fit, because the
  delegate (the model handler) changes per connection.
- **Replacing the `open` hooks with observers.** `MumlaService` depends on
  hook ordering and on `super` calls. Changing that is an app-architecture
  decision, not a decomposition step.
- **A rewrite.** Every slice above is incremental by design.

### Expected end state (estimates)

| | before | after route + config tracks |
|---|---:|---:|
| `HumlaService` fields | 52 | ~23 |
| `HumlaService` lines | 2,442 | ~1,500–1,650 |
| route fields | 16 (in service) | ~11 (in controller) |
| route unit tests | 1 truth table | settle, debounce, mode, counter, races |

Field arithmetic: the route track takes 16 fields out and adds one
controller field (52 → 37). `ConnectionParams` replaces 11 fields with one
(→ 27). The `InputModes` holder replaces five (the three mode instances,
`mTransmitMode`, `mHalfDuplex`) with one (→ 23); `mAudioBuilder` stays.
Inside the controller, slice 1 removes four of the 16 route fields and
slice 7's `AppliedRoute` one more, leaving 11.

## 6. What would change this plan

- **Churn dropping.** Badness is churn-weighted. If route work stopped, the
  extraction's main payoff (testable route changes) would shrink. Slices 1
  and 2 are worth doing regardless.
- **More contributors.** The file has a single author, so shared-monitor and
  shared-field hazards are managed by one mental model. A second
  contributor makes slices 3–6 urgent rather than advisable.
- **Upstream route ownership.** If a future Android release or a platform
  helper could own "which sink is live", the settle and foreign-SCO
  machinery would shrink instead of relocating. The controller boundary is
  where such a change would plug in.
- **§3.4 not reproducing.** The in-app UI disconnects before switching
  servers, so a reproduction attempt that goes through the UI will not hit
  the path at all; it needs an in-process trigger. If CONNECT while
  CONNECTED still proves to tear down correctly through a path not traced
  here, slice 7 loses one motivating bug. The duplication argument stands
  on its own.

## Appendix A: reproduction

All commands run from the repository root and are pinned to the snapshot.

Member decomposition (§2.1). The script reproduces the table exactly; its
known limitations are listed in §2.2.

```bash
SNAP=1d6d509c
P=libraries/humla/src/main/java/se/lublin/humla/HumlaService.kt
python3 - "$SNAP:$P" <<'PY'
import re, subprocess, sys
lines = subprocess.run(["git", "show", sys.argv[1]], capture_output=True,
                       text=True, check=True).stdout.split("\n")
decl = re.compile(r'^    (?:@\w+ )*(?:(?:private|internal|public|protected|override|open|final'
                  r'|lateinit|const|suspend|abstract|inline|operator)\s+)*'
                  r'(fun|val|var|class|object|init|companion)\b')
starts = [i for i, l in enumerate(lines) if decl.match(l) or re.match(r'^    companion object', l)]
members = []
for n, s in enumerate(starts):
    e = starts[n + 1] if n + 1 < len(starts) else len(lines)
    head = lines[s].strip()
    ident = re.search(r'\b(fun|val|var|class|object)\s+([A-Za-z_]\w*)', head)
    is_field = bool(re.match(r'(?:private |internal |public |protected )?'
                             r'(?:lateinit )?(?:val|var) ', head))
    members.append((e - s, ident.group(2) if ident else head, is_field))

BUCKETS = {
    "AUDIO_ROUTE": """applyAudioRoute maybeStartRequestedSco counterUnrequestedSystemSco
        queryMediaDevicesPresent stopScoWithNotify ensureVoiceMode retryBluetoothSco releaseVoiceMode
        updateBluetoothScoRoute applyScoRoute onRouteDevicesChanged logDeviceTypes updateRouteBuilderLocked
        updateRouteBuilder armRouteSettle cancelRouteSettle cancelRouteDebounce applyVoiceMode isSystemScoOn
        isBluetoothScoActive isMediaRouteActive onAudioRouteChanged mDeviceCallback mHalfDuplex mScoRequested
        mScoManager mScoRouteActive mAudioManager mSavedAudioMode mVoiceModeHeld mVoiceModeClaimed
        mMediaRouteSelected mAppliedVoiceRoute mHandsetAudioStream routeDebounceJob routeSettleJob
        mSettleAttempts mSettleFired mSettlePending""".split(),
    "CONFIG_APPLY": """apply buildConnectDelta isTrustStorePathAllowed mServer mCertificate
        mCertificatePassword mForceTcp mClientName mAccessTokens mTrustStore mTrustStorePassword
        mTrustStoreFormat""".split(),
    "CONNECT_MACHINE": """connect disconnect onConnectionEstablished onConnectionSynchronized
        onConnectionDisconnected onConnectionHandshakeFailed onConnectionWarning setReconnecting
        cancelReconnect getReconnectDelay registerNetworkCallback unregisterNetworkCallback getConnection
        isConnectionEstablished isSynchronized requireConnection mConnection mConnectionState mReconnecting
        mReconnectAttempts mNetworkCallback mNetworkCallbackRegistered connectionGeneration
        reconnectJob""".split(),
    "PLATFORM_SHELL": """onCreate onStartCommand onDestroy onBind HumlaBinder stopSelfIfIdle
        acquireWifiLock releaseWifiLock mWakeLock mWifiLock mHandler mCallbacks mDestroyed""".split(),
    "HANDLER_WIRING": """getModelHandler getAudioHandler createAudioHandler mAudioInputListener
        mAudioOutputListener mModelHandler mAudioHandler requireModelHandler""".split(),
    "INPUT_MODE": "mTransmitMode mActivityInputMode mToggleInputMode mContinuousInputMode mAudioBuilder".split(),
    "MUTE_STATE": """applyOptimisticSelfMuteDeaf setSelfMuteDeafState setMuteDeafState setTalkingState
        mLocalMuteHistory mLocalIgnoreHistory""".split(),
    "WHISPER_TEXT": """registerWhisperTarget setVoiceTargetId setListeningChannel setListeningVolume
        getWhisperTarget sendChannelTextMessage sendUserTextMessage mVoiceTargetId
        mWhisperTargetList""".split(),
    "LOG": "logInfo logError logWarning".split(),
}

agg = {k: [0, 0, 0] for k in list(BUCKETS) + ["DELEGATION"]}
for ln, ident, is_field in members:
    k = next((k for k in BUCKETS if ident in BUCKETS[k]), "DELEGATION")
    agg[k][0] += 1
    agg[k][1] += ln
    agg[k][2] += is_field

print(f"{'cluster':<16}{'mem':>5}{'lines':>7}{'fields':>7}")
for k, (m, l, f) in agg.items():
    print(f"{k:<16}{m:>5}{l:>7}{f:>7}")
print(f"{'TOTAL':<16}{sum(v[0] for v in agg.values()):>5}"
      f"{sum(v[1] for v in agg.values()):>7}{sum(v[2] for v in agg.values()):>7}")
PY
```

Basic counts (§1):

```bash
git show "$SNAP:$P" | grep -cE '^\s*$'                    # blank: 201
git show "$SNAP:$P" | grep -cE '^\s*(\*|/\*|//)'          # comment-only: 546
git show "$SNAP:$P" | grep -c 'override fun'              # 90
git show "$SNAP:$P" | sed -n '1128,1610p' | grep -cE '^\s*(\*|/\*|//)'  # route region: 162 of 483
```

Churn (§3.6). Use exact paths: a `*HumlaService.*` glob also matches
`IHumlaService` and a pre-monorepo `src/main/...` path. `--follow` does not
bridge the `.java` → `.kt` conversion (`9b7472d9`, 2026-10-12), which
created the `.kt` as a new 2,285-line file rather than a tracked rename.

```bash
J=libraries/humla/src/main/java/se/lublin/humla/HumlaService.java
git log "$SNAP" --format='%ad' --date=format:'%Y-%m' -- "$J" "$P" | sort | uniq -c
#   10 2026-08    31 2026-09    52 2026-10
git log "$SNAP" --format='%ad' --date=format:'%Y-%m' | sort | uniq -c | tail -3
#  191 2026-08   462 2026-09   348 2026-10   (shares: 5%, 7%, 15%)
git log "$SNAP" --since=2026-08-01 --format='%s' -- "$J" "$P" \
  | grep -ciE '\b(route|routing|sco|a2dp|bluetooth|settle|voice.?mode|media|handset)\b'
#   18 of 93
```

Counts are line-based approximations. A member's line count includes its
leading comments, and the §2.2 corrections are manual line-range estimates.
The plan depends on which state and choreography belong together, not on
the margins.

## Appendix B: revision note

The first version of this document (`358dfb8b`) is superseded. Corrections:
- The verdict percentages were internally inconsistent: "~3% unnecessary"
  vs "<1%", the intrinsic field count, and a 1,400-line "40%" kernel.
- It counted 6 supertypes instead of `LifecycleService` + 4 interfaces.
- It treated the residual bucket as pure façade.
- It classified the connection parameters as misplaced ("half the state").
- It recommended deleting the permission-denied `Toast`.
- Its monthly churn table used a glob that also counted `IHumlaService`,
  and its October repository total could not be reproduced.
- Its reproduction commands were not pinned to the snapshot.

The `MumlaService` inheritance contract, the redundant route flags, the
duplicated choreography, the threading facts, and the CONNECT-while-CONNECTED
teardown gap are new in this version.

A review of the second version (`85ea33d2`) confirmed its factual findings
(the script output, the redundant flags, the §3.2 ordering drift, the
threading facts) and corrected the plan:
- **Ports first.** The ports now get their own in-place slice (slice 3)
  before the move, so the move itself carries less untested change. Every
  later slice number shifted up by one.
- **Testability.** §3.6 overstated "can't be tested in place": Robolectric
  is already an `:app` test dependency. Ports remain the cheaper route to
  tests.
- **§3.4.** The gap is not route-only (the old pipeline keeps capturing),
  so the fix ends the whole old session inside `connect()`. The path is
  also unreachable from the in-app UI, which disconnects before switching
  servers, so it is now framed as a latent hazard and given the lowest
  priority.
- **Pipeline ownership.** §4 now states that the controller owns the
  timing of every live recreate, including non-route settings reloads.
- **`suppressReload`.** The rule stays in `apply()`, because it reads the
  config delta, and travels in `SettingsChanged(reload)`.
- **Arithmetic.** Slice 1 removes four fields, not three. The end state is
  about 23 service fields, not 27, once the `InputModes` holder is
  counted.
