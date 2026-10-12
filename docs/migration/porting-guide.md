# Porting Guide: Java → Modern Android (Mumla OLED Migration)

This is the mechanical mapping for the migration. It is reviewed like
code: when a class of defect recurs during conversion, fix this guide
first, then the offending file. Converters follow it literally; reviewers
check conformance to it.

Scope reminder: the native DSP in
[`audio_engine`](../../../libraries/humla/src/main/jni/audio_engine/) is
fenced off. Its Java JNI facades convert syntactically only — method
signatures and threading semantics frozen.

## 0. Baseline: minSdk 33 (Pixel 4a floor, decided)

The migration targets minSdk 33: the oldest fleet device is a Pixel 4a,
which runs Android 13 (API 33) at most. This is a single-dev app with a
three-person userbase, so the floor is set by hardware on hand — revisit
only if the fleet changes. Delete, don't convert:

- All `M` (23) gates: pre-M `AudioRecord`/`AudioTrack` constructors in
  [`AudioInput`](../../../libraries/humla/src/main/java/se/lublin/humla/audio/AudioInput.java)
  and
  [`AudioOutput`](../../../libraries/humla/src/main/java/se/lublin/humla/audio/AudioOutput.java),
  `getBufferSizeInFrames` fallback, `MumlaService`/`HumlaService`
  M-gated branches, `AudioDeviceCallback` M registration gate.
- All `N` (24–25) gates: e.g. `PinnedTlsSocketFactory` behavior split.
- All `O` (26) gates: notification-channel creation checks in
  `MumlaConnectionNotification`/`MumlaMessageNotification`,
  `MumlaHotCorner`/`MumlaOverlay` O branches.
- All `Q` (29) gates: gesture-exclusion and overlay Q branches.
- All `R` (30) gates: overlay R branches (`@TargetApi(R)` goes away).
- All `S` (31) gates, **including the `BluetoothScoManager`
  modern/legacy duality**: the pre-S broadcast-receiver bring-up
  (`bringUpLegacy`, legacy receiver, `REASON_NO_DEVICE` legacy paths)
  is deleted outright — only the communication-device path survives.
  Same for the pre-S voice-mode pre-hold branches in `HumlaService`/
  `MumlaService`.
- All `TIRAMISU` (33) gates: `POST_NOTIFICATIONS` permission flow goes
  unguarded.

Stays: `UPSIDE_DOWN_CAKE` (34) branches only (e.g. in
`MumlaConnectionNotification`). One gate class left in the whole tree.

Newly unguarded at 28: `Context.getMainExecutor()`, notification
channels, `AudioRecord.Builder`/`AudioTrack.Builder` everywhere.

## 1. Java → Kotlin (syntax layer)

Convert syntax first, restructure second. One file per commit.

- **Nullability:** platform types are the top source of conversion bugs.
  Every Java value crossing into Kotlin gets an explicit type — nullable
  unless the converter can prove otherwise. No `!!` in converted code;
  use `requireNotNull` with a message or early return.
- **Getters/setters → properties**, `static` → `companion object` or
  top-level functions, constants stay `const val`.
- **Models:** `User`/`Channel`/`Server`/`Message` become Kotlin classes
  with `volatile`-equivalent visibility preserved (see §2). `Server` is
  `Parcelable` — use `@Parcelize` (add `kotlin-parcelize` plugin), do not
  hand-write it.
- **State enums → sealed interfaces/classes:** `ConnectionState`,
  `HandshakeFailure`, SCO `STATE_*` ints. Exhaustive `when` replaces
  `switch`; the compiler must prove exhaustiveness (no `else` branch
  that hides new states).
- **`switch` → `when`, `instanceof` chains → smart casts.**
- **JNI facades** (`NativeAudioInputEngine`,
  `NativeAudioOutputEngine`): convert syntax only. Keep every
  `synchronized` as `@Synchronized` (identical monitor semantics for
  the audio threads) and every `native` signature byte-identical.
- **Hot-path allocation discipline:** `AudioHandler.onAudioPacketEncoded`
  reuses `mProtobufPacketBuffer`/`mLegacyPacketBuffer` under
  `mPacketBufferLock`; `Pacer` is render-thread-confined with no locking.
  Convert these literally — no per-packet allocations, no new locks.

## 2. Threads/Handlers → coroutines (concurrency layer)

The rule: **network/audio threads parse; a confined scope mutates.**
`Handler.post` chains become structured concurrency:

| Today | Becomes |
|---|---|
| `mHandler.post / postDelayed / removeCallbacks` (reconnect runnable, route debounce, settle recheck, SCO notify) | Cancellable `Job`s in a service scope: `reconnectJob`, `debounceJob`, `settleJob`. Disarm = `job?.cancel()`, replacing `removeCallbacks` one-for-one |
| `ScheduledExecutorService` ping loop (`scheduleNextPing` self-chain) | `while (isActive) { sendKeepalivePing(); delay(interval) }` on `Dispatchers.IO`; `disconnect()` cancels the scope |
| `HumlaNetworkThread` 3-executors-per-connection (incl. the dead receive executor — do not port the bug) | One `ConnectionScope` (`SupervisorJob + Dispatchers.IO`): blocking TCP read loop in one child, writes serialized via a `Mutex` or actor coroutine |
| `HumlaUDP` `BlockingQueue` + `mSendLock` head-drop | `Channel(capacity, onBufferOverflow = DROP_OLDEST)` — identical ~200 ms bound semantics, no explicit lock |
| `volatile` route/connection flags read anywhere | `StateFlow` (route, connection state) for observed values; plain `volatile var` where only visibility is needed |
| `synchronized` `ModelHandler.message*` writers + lock-free readers | Confine all `message*` calls to a single-threaded model dispatcher; keep the `ConcurrentHashMap`s (the UDP-thread `getUser` path depends on them) |
| `HumlaCallbacks` concurrent-set fan-out | Keep the interface during migration; back it with a `SharedFlow` or keep the set — either way, per-observer isolation stays |
| `BluetoothScoManager` epoch + `Handler` timeout | Coroutine cancellation *is* the epoch: `stop()` cancels the bring-up children; timeout via `withTimeoutOrNull(8000)` |
| `ConnectivityManager.NetworkCallback`, `AudioDeviceCallback`, `BroadcastReceiver`s | `callbackFlow` adapters, collected in the service scope |
| Talking-state hops (`mHandler.post` from capture thread, `mMainHandler.post` from native render callback) | `serviceScope.launch { ... }` — still a hop to the confined scope, same ordering |
| `AsyncTask` (`ServerConnectTask`, `ServerInfoTask`, `MumlaCertificateGenerateTask`) | `suspend` functions + `viewModelScope`/`lifecycleScope`; `ServerConnectTask` keeps application-context semantics (it outlives rotation today) |
| Ad-hoc SRV single-thread executors | One shared `Dispatchers.IO` call with `withTimeout(800)` |
| Capture `MumlaAudioInput` thread + render thread (`URGENT_AUDIO`, blocking `read`/`write`/`wait`) | **Stay raw threads.** Blocking audio IO is not suspendible; only the control plane (start/stop/join bookkeeping) moves into the scope. Join/interrupt/notify pairing stays exactly as-is |

Services: extend `LifecycleService` (decided — no attachment to the
current `Service` base) and use `lifecycleScope` as the service scope.
Never use `GlobalScope`.

## 3. Data layer → Room + DataStore + Hilt

- **Hand SQL → Room:** entities for servers, certificates, access tokens,
  local mute/ignore history. DAOs replace `MumlaDatabase` methods
  one-for-one; keep method names where possible so call sites diff
  minimally. `Server` stays a single `@Entity @Parcelize` class (DB row,
  parcel, and UI model in one) — no separate layers.
- **`Settings` blob → DataStore (Preferences flavor):** typed preferences;
  the 65 getters become typed keys with defaults in one place.
- **Pref→bundle→effect double dispatch → typed `ConfigDelta`.**
  `MumlaService.onSharedPreferenceChanged` and
  `HumlaService.configureExtras` collapse into `apply(ConfigDelta)`; the
  stringly `EXTRAS_*` bundle dies. Every key must survive the round trip
  or `check.sh` + behavior tests fail the slice.
- **Provider interfaces → Hilt:** `HumlaServiceProvider`,
  `DatabaseProvider` and manual singletons become `@Module`s;
  `@Singleton`/`@ServiceScoped` match today's lifetimes. Existing
  `FakeSharedPreferences`-style tests port to DataStore/Room in-memory
  fakes.

## 4. UI → Compose (internals only, pixels frozen)

Visual/structural parity is a hard guardrail (see
[plan](plan.md#ui-parity-hard-guardrail)). Mapping:

- Fragment destination → navigation route composable; `loadDrawerFragment`
  becomes a route map, same order, same back stack.
- `RecyclerView` + adapter + `ViewHolder` → `LazyColumn` + `key()`;
  `onBindViewHolder` listener nests become plain lambdas on state.
- Each of the six `MumlaActivity` dialogs → `Dialog` composable behind a
  single `DialogHost` holding today's dismissal semantics
  (pause-dismissal, dismiss-before-assign).
- `DrawerLayout` → `ModalNavigationDrawer`, options menu → `TopAppBar`
  actions, toasts → `SnackbarHost` — all rendering today's copy and order.
- Custom views (`CircleDrawable`, overlay layout) → `Canvas`-based
  composables matched against screenshots, not redesigned.
- All `res/` strings, colors, dimens stay the single source of truth
  (`stringResource`/`colorResource`/`dimensionResource`).

## 5. What reviewers check (conformance list)

1. Guide followed literally; deviations call out the guide section.
2. No `!!`, no `GlobalScope`, no new `Thread`/`Handler` outside §2's
   allowlist, no per-packet allocations in audio paths.
3. `./scripts/check.sh` green; screenshot diffs empty for UI slices;
   torture paths (rotate mid-dialog, switch mid-connect, deny mid-flow)
   unchanged.
4. Commit message carries review attribution for agent-reviewed slices.
