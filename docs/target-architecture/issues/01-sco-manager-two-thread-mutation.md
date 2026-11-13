# 01 — `BluetoothScoManager` is mutated from two threads

| | |
|---|---|
| Status | Open — regression unfixed; fix before step 5a.3. |
| Category | Confirmed regression (introduced by step 4) |
| Severity | Harmful (latent race; not observed in daily use) |
| Verified | Yes, by code reading against the current tree and the pre-step-4 tree |
| Area | Route / Bluetooth SCO |
| Spec | [route.md § Thread](../route.md#thread), [migration.md § Step 4](../migration.md#step-4--voice-control-and-route) item 1 |
| Code | `libraries/humla/src/main/java/se/lublin/humla/audio/BluetoothScoManager.kt`, `audio/ScoLink.kt`, `route/RouteControl.kt`, `HumlaService.kt` (construction) |
| Gates | Should be fixed before step 5a.3 (voice starts following `RouteState` natively) |

Line numbers below refer to `master` at `092d9dc8`.

## Summary

Step 4 moved `RouteControl` onto its own `route` `HandlerThread`, but did
not move `BluetoothScoManager` with it. The manager still runs its timers
and platform callbacks on the **main thread** (`Dispatchers.Main.immediate`
and `context.mainExecutor`), while `RouteControl` now calls its control
methods (`start`, `stop`, `isIdle`, `destroy`) from the **route thread**.
Its state machine is therefore read and written from two threads. Each
field is individually atomic (or not even that: two are plain `var`s), but
the operations that combine several fields are not atomic, despite comments
calling them "one atomic unit". Before step 4, everything ran on main, so
the machine was single-threaded; the race is new.

## What the spec says

`route.md` § Thread:

> - `BluetoothScoManager` uses the route thread's executor where it uses
>   `context.mainExecutor` today.
> - Blocking platform calls (`setCommunicationDevice`, `setMode`, SCO
>   bring-up) therefore run on the route thread, never on main.

`migration.md` § Step 4, item 1:

> `ScoLink` callbacks use the route executor.

The intent is **thread confinement**: one thread (route) owns all routing
state, including the SCO link, so no locking or cross-thread reasoning is
needed.

## What the code does

Construction (`HumlaService.kt:413`): `BluetoothScoManager(this, object :
BluetoothScoManager.Listener { ... })`. No executor or dispatcher is
passed. `ScoLink` (`audio/ScoLink.kt:28`) has no executor parameter either.

Inside the manager:

- `BluetoothScoManager.kt:146`:
  `private val scoScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)`.
  The comment at 144 says "Main.immediate is hardcoded (not injected like
  the budgets below): correct on device".
- `BluetoothScoManager.kt:841` (`registerModernListener`): the API 31+
  `OnCommunicationDeviceChangedListener` is registered with
  `context.mainExecutor`. The API 30 broadcast receiver is also delivered
  on main.
- The connect timeout is a coroutine launched in `scoScope` (around line
  787: `scoScope.launch { delay(connectTimeoutMs); onConnectTimeout(epoch) }`),
  so `onConnectTimeout` runs **on main**, and from there calls
  `teardownObservers()`, `releaseRouteIfHeld()`, and `attemptBringUp()`,
  which performs the bring-up IPC (`getAvailableCommunicationDevices`,
  `addOnCommunicationDeviceChangedListener`, `setCommunicationDevice` or
  `startBluetoothSco`) **on main**.
- Device-listener and broadcast callbacks call `setActive()`,
  `dropActiveLink()`, and `fail()` **on main**.
- `RouteControl` calls `start()` (`RouteControl.updateScoRoute`,
  `onRetrySco`) and `stop()` (`stopScoAndClearFlag`, `onSessionEnded`,
  `onDestroyed`) **on the route thread**. `start()` itself calls
  `attemptBringUp()` synchronously, so the first bring-up IPC runs on the
  route thread.

The class header (`BluetoothScoManager.kt:57-67`) documents this as a
deliberate design: "Threading (step 4, lock-free): the epoch/counter/flag
fields are atomics; bring-up re-validates by session epoch after each IPC
step ... Control calls (`start`/`stop`/`destroy`) arrive on the route
dispatcher; listener callbacks originate on main-thread platform
callbacks". So this is not an accidental omission; step 4 replaced the
spec's confinement with a lock-free two-thread scheme and did not record
the change in the spec.

### State touched from both threads

| Field | Type | Line |
|---|---|---|
| `state` | `AtomicReference<ScoState>` | 157 |
| `attempts` | `AtomicInteger` | 158 |
| `generation` | `AtomicInteger` | 159 |
| `routeHeld` | `AtomicBoolean` | 160 |
| `routeHeldEpoch` | `AtomicInteger` | 165 |
| `timeoutJob` | `AtomicReference<Job?>` | 150 |
| `deviceListener` | **plain `var Any?`** | 172 |
| `scoReceiver` | **plain `var BroadcastReceiver?`** | 178 |

`takeObserversLocked()` (line 890) is documented as "Nulls the observer
fields; route-dispatcher confined", but it is called from `fail()` and
`dropActiveLink()` (main) and from `teardownObservers()` inside
`onConnectTimeout` (main), as well as from `stop()` (route). The two plain
`var`s are written without any synchronization from both threads: a data
race in the Java memory model sense (no happens-before edge), not just a
logical race.

The `run { ... }` blocks in `start()`, `stop()`, `fail()`, `setActive()`,
`dropActiveLink()`, and `onConnectTimeout()` are commented as atomic units
("One atomic unit with the flip: timeout cancel and the generation bump
cannot interleave with a concurrent bring-up's register/arm"). `run {}` is
an ordinary Kotlin scope function; it provides no mutual exclusion. Each
block is a sequence of independent atomic operations that another thread
can interleave with.

### Concrete interleaving (by code reading)

Quick reconnect while an SCO bring-up is timing out:

1. Session A starts SCO. `start()` on route sets `state = STARTING`,
   `generation = g`, calls `attemptBringUp()`, which arms a timeout in
   `scoScope` (main).
2. The timeout fires on main: `onConnectTimeout(g)` passes its first check
   (`state == STARTING && epoch == generation`) and decides to retry.
3. Meanwhile, on route, session A ends and session B starts quickly:
   `stop()` sets `state = IDLE`, `generation = g+1`; then `start()` sets
   `state = STARTING`, `generation = g+2`, and begins B's bring-up.
4. Back on main, `onConnectTimeout` runs `teardownObservers()` (nulling the
   observer fields B may just have registered, without unregistering B's
   listener from the platform if the null-out races B's assignment),
   `releaseRouteIfHeld()`, then its second check. If B's `start()` landed
   between the checks, the check sees `STARTING` and its captured epoch
   `g != g+2`, so it returns. But if the second check runs **before** B's
   `start()` and B's `start()` lands between that check and
   `attemptBringUp()`, then `attemptBringUp()` reads a **fresh** epoch
   (`epoch = generation.get()` inside `attemptBringUp`, line ~289, not the
   captured one) and runs a **second, concurrent bring-up for session B**
   on main while B's own bring-up runs on route.
5. Outcomes include: two device listeners registered with only one stored
   in `deviceListener` (the other leaks until process death), `attempts`
   double-incremented (B's retry budget consumed early), and two
   `setCommunicationDevice` calls racing.

Other windows exist (for example `fail()` on main interleaving with
`start()` on route between `state.set(STARTING)` and the observer
registration), all of the same shape: multi-field invariants guarded only
by per-field atomics.

## Before the migration

At the step-4 base (`4459e745^`), `audio/AudioRouteController.kt` ran on
main and `BluetoothScoManager.kt` already used `Dispatchers.Main.immediate`
(line 150 there) and `context.mainExecutor` (line 850 there). Control calls
and callbacks were all on main: one thread, no race. Step 4 moved only the
controller.

## Impact

- **Correctness:** rare races during SCO bring-up, retry, timeout, or
  teardown, typically around reconnects or Bluetooth device changes.
  Symptoms would be a leaked platform listener, a stray SCO route request
  after the session moved on, a prematurely exhausted retry budget, or SCO
  state disagreeing with `RouteControl`'s view.
- **Main-thread IPC:** retry bring-up and observer teardown perform
  `AudioManager` binder calls on main, which the spec explicitly forbids
  ("never on main"); a jank/ANR risk on slow audio HALs.
- **Design:** the spec's confinement model is silently replaced by a
  lock-free scheme that is much harder to reason about, which is exactly
  the class of drift that sank the first step-5a attempt.
- Not observed in hours of daily use with Bluetooth, consistent with narrow
  timing windows.

## Fix directions

Preferred (matches the spec):

1. Give `ScoLink`/`BluetoothScoManager` an executor or
   `CoroutineDispatcher` parameter and pass the route thread's (from
   `RouteControl.create`: the route `Handler`, its `Executor`, and its
   dispatcher already exist there; `RouteControl` would need to expose
   them, or `HumlaService` would need to create the route thread first and
   hand it to both).
2. Replace `Dispatchers.Main.immediate` in `scoScope` with the route
   dispatcher, and `context.mainExecutor` in `registerModernListener` with
   the route executor. Register the API 30 broadcast receiver with the
   route `Handler` (`registerReceiver(receiver, filter, null, handler)`).
3. Once everything runs on the route thread, the atomics, epoch
   re-validation after each IPC step, and the `run {}` "atomic units" can
   be simplified to plain confined fields (optional; keep them for one
   commit if that eases review, but the plain `var`s must stop being
   written from two threads either way).
4. Listener callbacks into `RouteControl` (`onScoConnected` and so on)
   already re-post to the route thread; with the manager confined they
   arrive on it directly. Keep the stale-session checks
   (`shouldDropStaleEvent`) in `RouteControl`.

Alternative (not recommended): keep two threads but guard all multi-field
state with one lock. This contradicts the spec and keeps IPC on main.

Either way, update the class header comment, and if the chosen design
differs from `route.md`, change `route.md` on `master` first (see the
migration ground rules).

## Acceptance

- No `Dispatchers.Main`, `Main.immediate`, `mainExecutor`, or
  `Looper.getMainLooper()` in `BluetoothScoManager.kt`.
- All `BluetoothScoManager` methods and callbacks run on the route thread;
  add `checkRouteThread()`-style assertions (debug-throw, release-log, like
  `checkNotMainThread()`) at the entry points if cheap.
- `AudioRouteControllerTest`-style virtual-time tests (or new
  `BluetoothScoManager` tests with a fake `AudioManager` surface) cover:
  timeout-then-retry, timeout racing `stop()`, timeout racing
  `stop()`+`start()` (the interleaving above), and failure delivery after
  `stop()` (must be dropped).
- `./scripts/check.sh` passes; a Bluetooth headset session still connects,
  falls back, and retries on a device (developer's discretion).

## Related

- [09](09-route-settling-unobservable.md),
  [10](10-route-fallback-unreachable.md): other places where step 4's
  route behavior differs from `route.md`.
- [21](21-retry-sco-no-counter-check.md): `RetrySco` preconditions.
