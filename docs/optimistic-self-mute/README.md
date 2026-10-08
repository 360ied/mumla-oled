# Optimistic Self-Mute/Deafen: Deliberately Deferred Items

Context: the `feature/optimistic-self-mute` branch (merged as `e03797e`)
made client-initiated self-mute/deafen optimistic with desktop-Mumble
parity: [`HumlaService`](../../libraries/humla/src/main/java/se/lublin/humla/HumlaService.java)
coerces and sends the packet, then mirrors state into the `User` model,
pushes the [`AudioHandler`](../../libraries/humla/src/main/java/se/lublin/humla/protocol/AudioHandler.java)
mic gate, logs once, and fires `onUserStateUpdated`, with the server echo
as confirm. Four adjudicated pedantic review rounds (three primed, one
clean-eyes) surfaced the items below. Each was evaluated against one
rule — **fix now if fail-open, reachable, or cheap with evidence; defer
if fail-closed and rare, churn without behavioral gain, or out of scope**
— and deferred with the rationale, fix shape, and revisit trigger
recorded here so a future reader can tell intention from oversight.

## 1. Marshal observer callbacks onto the main thread

`onUserStateUpdated` fires on the caller's thread: main for menu and
notification toggles, Binder pool threads for IPC-driven `IHumlaSession`
callers, main for TCP echoes (`HumlaTCP` posts receipt via
`executeOnMainThread`). Downstream observers touch UI-thread-confined
state (adapter updates, `supportInvalidateOptionsMenu`, notification
rebuilds). It works because every current caller happens to be main, but
nothing enforces it.

Deferred because the fix is cross-cutting, not local: posting must move
to the `HumlaCallbacks` fan-out or to both fire sites (optimistic site
and [`ModelHandler`](../../libraries/humla/src/main/java/se/lublin/humla/protocol/ModelHandler.java)
echo path) together, or the two confirms get different threading and
ordering behavior. That deserves its own branch and its own review, not a
tacked-on hunk.

Fix shape: post through a main `Handler` at the fan-out; main-looper FIFO
preserves optimistic-before-echo ordering naturally.

Revisit if an IPC or broadcast-driven toggle ever fires off-main (crash or
`CalledFromWrongThreadException` will announce it), or when touching
observer threading for any other reason.

## 2. Simplify the redundant caller-side pre-coercion

`ChannelListFragment` (`deafened &= muted`), `MumlaService.toggleSelfMute`
(`deafened && muted`), and `onDeafenToggled` (`(deafened, deafened)`) all
hand-coerce pairs that `setSelfMuteDeafState` now coerces centrally.

Deferred as intentional defense-in-depth: a future direct caller of the
session API still gets sane behavior either way.

Fix shape: delete the local computations and pass raw intent (~6 lines
across 2 files, zero expected behavior change).

Revisit when already editing those call sites for another reason; not
worth a commit on its own.

## 3. Desktop `unmuteOnUndeaf` setting

Our deafen toggle sends `(false, false)` on undeafen (always unmutes).
Desktop keeps `mute=true` when its `unmuteOnUndeaf` setting is false.
Our behavior matches the desktop *default*; the alternative is an opt-in
setting.

Deferred as a feature request, not a defect. No fix shape until someone
wants the setting; then it is a `Settings` boolean plus a branch in the
two deafen-tap handlers.

## 4. Robolectric coverage for the `AudioHandler` gate

The latch (`mOptimisticSelfMuteActive`), echo-ignore, and fail-closed
deaf branches have no direct unit test: `AudioHandler`'s constructor
builds a `NativeAudioInputEngine` (loads native `.so`s, reads the RNNoise
model) and an `AudioInput` (opens capture), so instantiating the class
under test fails before the logic under Robolectric native-library
stubs. Covering it would mean shadowing `System.loadLibrary`, the native
model cache, and the audio source — a shadow harness around the whole
pipeline to exercise boolean logic.

Deferred because the test would mostly assert that the shadows work: the
coercion pairs, echo dedup, and state application the latch depends on
are pure Java already pinned by fast JVM tests, and the remaining wiring
fails loud, not subtle.

Fix shape (if ever): extract the composite computation (server/self/deaf
flags plus latch precedence) into a pure-JVM policy object and test
*that*, leaving `AudioHandler` a thin caller — a real refactor, not a
shadow.

Revisit if the gate logic ever grows a third input or a second writer;
re-evaluate the cost then.

## 5. Shared test-context helper

`ModelHandlerSelfMuteTest`, `ModelHandlerTopologyTest`, and
`ModelHandlerUserRemoveTest` each carry a verbatim ~15-line
`createTestContext()` (`Resources` mock + `ContextWrapper(null)`).

Deferred for test independence: each file reads standalone, and coupling
three suites to one helper trades fifteen duplicated lines for
cross-file edit blast radius. Consistent with the existing suite.

Revisit if a fourth copy appears or the mock needs behavior (not just
strings); three consistent copies are the precedent holding the line.

## 6. Accepted transients (documented in code, recorded here)

Two behaviors are intentional and convergent, not bugs:

- **Gate ahead of model.** The capture gate pushes before the model
  write, so when the session user is not yet visible (restore racing the
  initial dump) the mic follows desired state up to a round trip ahead
  of the UI. The in-order echo converges the model.
- **Best-effort chat line.** Logging re-gates on synchronization while
  the state change does not, so a disconnect interleaving drops the line
  but keeps the change (and the later echo reads as unchanged). A line
  for a dead session is not worth a bypass around the gate.

## 7. Intentional divergences from desktop (blessed, do not "fix")

- **Other-user duplicate suppression.** Desktop re-logs `OtherSelfMute`
  on redundant packets; we log transitions only. Strictly better
  (a duplicate "is now muted" line is misinformation); the code comment
  says so.
- **Echo never logs self.** Desktop's toggle site owns the self line;
  ours does too. Stale echoes on rapid toggles apply state silently.

## 8. Pre-existing items observed but untouched

No new writers were added for any of these, so the diff leaves their
posture unchanged: non-self `User` fields' lock-free reads,
`User.equals`/`compareTo` contract tension, `mPermissions` plain-int
reads, `ModelHandler` early-return dropping co-packaged updates,
`sendTCPMessage` silent drop, `HumlaConnection` disconnect-path
nullability. Fixing any of them is a separate branch.
