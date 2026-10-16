# Porting Guide: Java → Modern Android (Mumla OLED Migration)

This is the mechanical mapping for the migration. It is reviewed like
code: when a class of defect recurs during conversion, fix this guide
first, then the offending file. Converters follow it literally; reviewers
check conformance to it.

Scope reminder: the native DSP in
[`audio_engine`](../../libraries/humla/src/main/jni/audio_engine/) is
fenced off. Its Java JNI facades convert syntactically only — method
signatures and threading semantics frozen. Same treatment for [`crypto`](../../libraries/humla/src/main/jni/crypto/) (OCB2 `CryptState`, JNI bridge).

## 0. Baseline: minSdk 33 (Pixel 4a floor, decided)

The migration targets minSdk 33: the oldest fleet device is a Pixel 4a,
which runs Android 13 (API 33) at most. This is a single-dev app with a
three-person userbase, so the floor is set by hardware on hand — revisit
only if the fleet changes.

> [!NOTE]
> **Post-landing change (v0.26.1):** the floor was lowered to minSdk 30 to
> restore Android 11–12 users. The deletion list below describes the
> migration as executed; API 30–32 compatibility (legacy SCO backend,
> `BundleCompat`/`IntentCompat`, permission guards) was reintroduced after
> landing behind version dispatch.

Delete, don't convert:

- All `M` (23) gates: pre-M `AudioRecord`/`AudioTrack` constructors in
  [`AudioInput`](../../libraries/humla/src/main/java/se/lublin/humla/audio/AudioInput.kt)
  and
  [`AudioOutput`](../../libraries/humla/src/main/java/se/lublin/humla/audio/AudioOutput.kt),
  `getBufferSizeInFrames` fallback, `MumlaService`/`HumlaService`
  M-gated branches, `AudioDeviceCallback` M registration gate.
- All `N` (24–25) gates (`PinnedTlsSocketFactory:62`, `HumlaService:907`; enumerate any remainder with `grep SDK_INT` — the rule is delete every gate below 33 except `UPSIDE_DOWN_CAKE`).
- All `O` (26) gates: notification-channel creation checks in
  `MumlaConnectionNotification`/`MumlaMessageNotification`,
  `MumlaHotCorner`/`MumlaOverlay` O branches.
- All `Q` (29) gates: gesture-exclusion and hot-corner Q branches (`MumlaHotCorner`; `MumlaOverlay` is O/R only).
- All `R` (30) gates: overlay R branches (`@TargetApi(R)` goes away).
- All `S` (31) gates, **including the `BluetoothScoManager`
  modern/legacy duality**: the pre-S broadcast-receiver bring-up
  (`bringUpLegacy` and the legacy broadcast receiver)
  is deleted outright — only the communication-device path survives.
  `REASON_NO_DEVICE` is shared with the modern path and stays.
  Same for the pre-S voice-mode pre-hold in `HumlaService`
  (`retryBluetoothSco`, `updateBluetoothScoRoute`).
- All `S_V2` (32) gates: the `ChannelChatFragment` storage branch (`SDK_INT <= S_V2`) — dead at 33, delete the legacy side.
- All `TIRAMISU` (33) gates: `POST_NOTIFICATIONS` permission flow goes
  unguarded.

Stays: `UPSIDE_DOWN_CAKE` (34) branches only (e.g. in
`MumlaConnectionNotification`). One gate class left in the whole tree.

Newly unguarded by the bump: `Context.getMainExecutor()` (28), notification
channels (26), `AudioRecord.Builder`/`AudioTrack.Builder` (23) — call all of them unconditionally.

## 1. Java → Kotlin (syntax layer)

Convert syntax first, restructure second. One file per commit.

- **Nullability:** platform types are the top source of conversion bugs.
  Every Java value crossing into Kotlin gets an explicit type — nullable
  unless the converter can prove otherwise. No `!!` in converted code;
  snapshot fields to locals, then use an explicit null check with early
  return, or throw `NullPointerException` with a message where the original
  dereferenced unconditionally (preserves the exact throw type; `requireNotNull`
  throws `IllegalArgumentException` and is reserved for new internal hardening
  on provably unreachable paths). Public API boundaries keep NPE-exact
  behavior (`Objects.requireNonNull`); private helpers may use `requireNotNull`.
- **Casts:** view lookups use nullable casts with the NPE-exact idiom
  (`as X? ?: throw NullPointerException("<name>")`). A non-null `as X`
  throws `TypeCastException`, not NPE — and crashes unconditionally on
  layout-variant-absent views (e.g. phone-only views on tablets) where Java's
  cast-then-null-check survived. Fields holding variant-absent views stay
  nullable (`as X?`); the use site keeps the null branch.
- **Openness:** plain Kotlin members are `final` by default but `override`
  members stay open — do NOT add redundant `open` to overrides. Add `open`
  to plain classes/members only where a subclass exists (Java subclasses
  re-override them: services, observers, tests). Otherwise converted classes
  stay `final` (documents intentional sealing; absence of subclasses verified
  by grep). Kotlin has no package visibility: widen test seams to public
  with a comment, never `internal` (name-mangled, not package-private).
- **Getters/setters → properties**, `static` → `companion object` or
  top-level functions, constants stay `const val`.
- **Models:** `User`/`Channel`/`Server`/`Message` become Kotlin classes
  with `volatile`-equivalent visibility preserved (see §2). `Server` is
  `Parcelable` — use `@Parcelize` (add `kotlin-parcelize` plugin), do not
  hand-write it.
- **State enums (`ConnectionState`, `HandshakeFailure`) and SCO `STATE_*` ints:**
  Enums become Kotlin `enum class`es (SCO states stay `const val` ints).
  Exhaustive `when` replaces `switch`; for enums/sealed types the compiler
  must prove exhaustiveness (no `else` branch that hides new states).
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
| `HumlaNetworkThread` 3-executors-per-connection (incl. the dead receive executor — do not port the bug) | One `ConnectionScope` (`SupervisorJob + Dispatchers.IO`): blocking TCP read loop in one child, writes serialized on a per-connection single-threaded dispatcher (`Dispatchers.IO.limitedParallelism(1)` for strict FIFO ordering; closed on teardown) |
| `HumlaUDP` `BlockingQueue` + `mSendLock` head-drop | `Channel(capacity, onBufferOverflow = DROP_OLDEST)` — identical ~200 ms bound semantics, no explicit lock |
| `volatile` route/connection flags read anywhere | `@Volatile var` where visibility across threads is needed |
| `synchronized` `ModelHandler.message*` writers + lock-free readers | Main-thread confinement via `HumlaTCP.executeOnMainThread` (`@Synchronized` + `ConcurrentHashMap`s retained so the UDP-thread `getUser` path stays lock-free) |
| `HumlaCallbacks` concurrent-set fan-out | Keep the `ConcurrentHashMap`-backed set and per-observer isolation as-is |
| `BluetoothScoManager` epoch + `Handler` timeout | Coroutine scope + generation epoch: `stop()` cancels the bring-up timeout child (`timeoutJob` with `delay(connectTimeoutMs)`); `destroy()` cancels the scope |
| `ConnectivityManager.NetworkCallback`, `AudioDeviceCallback`, `BroadcastReceiver`s | Direct `lifecycleScope.launch` hops (single-consumer fire-and-forget; `callbackFlow` adds no backpressure value here) |
| Talking-state hops (`mHandler.post` from capture thread, `mMainHandler.post` from native render callback) | `lifecycleScope.launch { ... }` in `HumlaService` (`AudioOutput` keeps its `mainHandler` hop since it is not a `Service`) |
| `AsyncTask` (`ServerConnectTask`, `ServerInfoTask`, `MumlaCertificateGenerateTask`) | Deferred with slice 7 (stay `AsyncTask` while XML/Fragment UI remains; `ServerConnectTask` slimmed in slice 2 to pass only the server ID) |
| Ad-hoc SRV single-thread executors | `Dispatchers.IO` call with `withTimeoutOrNull(800)` |
| Capture `AudioInput` thread + render (`AudioOutput`) thread (`URGENT_AUDIO`, blocking `read`/`write`/`wait`) | **Stay raw threads.** Blocking audio IO is not suspendible; only the control plane (start/stop/join bookkeeping) moves into the scope. Join/interrupt/notify pairing stays exactly as-is |

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
  the 46 read accessors over 41 keys (counting rule in slices.md) become typed keys with defaults in one place.
- **Pref→bundle→effect double dispatch → typed `ConfigDelta`.**
  `MumlaService.onSharedPreferenceChanged` and
  `HumlaService.configureExtras` collapse into `apply(ConfigDelta)`; the
  stringly `EXTRAS_*` bundle dies. Every key must survive the round trip
  or `check.sh` + behavior tests fail the slice. Shape (humla `util/ConfigDelta.kt`,
  one-shot command — constructed per change, never stored; nullable field =
  unchanged; `apply()` skips nulls in fixed order: identity/reconnect keys →
  audio pipeline params → live sends → side-effect appliers; returns whether
  a reconnect is needed):

  ```kotlin
  data class CertRef(val data: ByteArray, val password: String?)
  data class TrustStoreRef(val path: String?, val password: String?, val format: String?)
  data class ConfigDelta(
      val server: Server? = null,
      val certificate: CertRef? = null,
      val forceTcp: Boolean? = null,
      val trustStore: TrustStoreRef? = null,
      val transmitMode: Int? = null,
      val detectionThreshold: Float? = null,
      val amplitudeBoost: Float? = null,
      val inputQuality: Int? = null,
      val framesPerPacket: Int? = null,
      val halfDuplex: Boolean? = null,
      val bluetoothSco: Boolean? = null,
      val adaptiveLeveler: Boolean? = null,
      val accessTokens: List<String>? = null, // live-sent, no reload
      val localMuteHistory: List<Int>? = null, // connect-time only
      val localIgnoreHistory: List<Int>? = null, // connect-time only
      val clientName: String? = null, // connect-time only
      val audioStream: Int? = null, // handset-derived, funnel-only
      val theme: String? = null,             // applier: applyTheme
      val overlayShown: Boolean? = null,     // applier: updateOverlayVisibility
      val overlayPlacement: String? = null,  // applier: updateOverlayPosition
      // ttsEngine needs tri-state (unchanged vs system-default vs engine pkg):
      val ttsEngine: java.util.Optional<String>? = null, // applier: initTts
  )
  ```

  Notes (slice-2 amendments, reviewed with the code): `path` is nullable
  because an uninitialized trust store is null (system store); Java callers
  build deltas via the nested `Builder` (data-class `copy()` has no Java
  defaults); `hasCore()` gates the service call, `hasOtherAudioKeys()`
  preserves the old reload-suppression set (threshold excluded — applied
  directly, no reload needed).
- **Provider interfaces → Hilt:** `HumlaServiceProvider`,
  `DatabaseProvider` and manual singletons become `@Module`s;
  `@Singleton`/`@ServiceScoped` match today's lifetimes. Existing
  `FakeSharedPreferences`-style tests port to DataStore/Room in-memory
  fakes.

## 4. UI → Compose (DEFERRED 2026-10-13 with slices 6-7 — internals only, pixels frozen, if revived)

Visual/structural parity is a hard guardrail (see
[plan](plan.md#ui-parity-hard-guardrail)). Mapping:

- Fragment destination → navigation route composable; `loadDrawerFragment`
  becomes a route map, same order, same back stack.
- `RecyclerView` + adapter + `ViewHolder` → `LazyColumn` + `key()`;
  `onBindViewHolder` listener nests become plain lambdas on state.
- Each of the seven `MumlaActivity` dialog fields (six plus the first-run host; `mCertDialog` covers three TLS variants) → `Dialog` composable behind a
  single `DialogHost` holding today's dismissal semantics
  (pause-dismissal, dismiss-before-assign).
- All Compose UI uses `androidx.compose.material3` (Material3, per README/plan); no `androidx.compose.material` (M2).
- `DrawerLayout` → `ModalNavigationDrawer`, options menu → `TopAppBar`
  actions, toasts → `SnackbarHost` — all rendering today's copy and order.
- Custom views (`CircleDrawable`, overlay layout) → `Canvas`-based
  composables matched against screenshots, not redesigned.
- All `res/` strings, colors, dimens stay the single source of truth
  (`stringResource`/`colorResource`/`dimensionResource`).
- **Parity mapping rules (from the before/after diffs — reviewers
  enforce these literally):**
  - `stringResource` rejects null format args (`Any`, non-null) where
    `getString` printed them as `"null"`. Nullable slots render via
    explicit `.toString()` to preserve the old output exactly.
  - Bare `TextView` is 14sp with zero letter spacing; Compose `Text()`
    inherits 16sp body plus M3 ambient letter spacing. Pin both
    explicitly (`14.sp`, `letterSpacing = 0.sp`) unless the layout sets
    its own size.
  - State-list selector AND layer-list drawables are unloadable by
    `painterResource` on EVERY platform (throws, not just Paparazzi).
    Selectors: mount the state asset directly and let the ripple carry
    press feedback. Layer-lists (talk badges): redraw on `Canvas` from
    the same color tokens and glyph PNGs, transcribed drawable-by-drawable.
  - Every snapshot root paints the scheme background (`Surface`).
    Transparent content over Paparazzi's dark window once masqueraded as
    a broken light theme; the `Surface` also matches the real window.
  - Legacy underline fields are text plus a 1dp divider (~40dp rows),
    not M3 boxes: M3 `TextField` minimums to 56dp and clips when
    squeezed. Use `BasicTextField` plus a divider for form rhythm.
  - Behavior-ground-truth beats static reading: a control the code
    appears to disable but the device proves live ships enabled (chat
    image button: `enabled=false` + no enabler vs a v0.26.1 screenshot
    of the running flow). Related rendering trap: M3 dims disabled
    content while legacy views render full-bright, so a wrongly-disabled
    port also looks wrong.
  - M3 `Card` applies a primary tonal overlay at any elevation;
    `CardView` never tinted. Card parity is a `Surface` with the scheme
    container color, `tonalElevation = 0.dp`, and a real `shadowElevation`.
  - M3 light `surfaceContainer` is lavender-tinted; old light cards/menus
    are untinted white. Override the token, not each call site.
  - Goldens of new UI alone are not parity evidence. Every screen gets
    old-View before-shots (same data, same pinned device, matching theme)
    diffed against the Compose after-goldens before its wiring lands.
  - Native rows inside Compose need the same themed treatment as pure
    Compose: one Paparazzi class per scheme with the matching activity
    theme, or `?attr`/`?cardStyle` rows paint dark on every scheme.
    Inflated rows lose their `match_parent` without a `ListView` parent
    (self-message gravity collapses left) — pin `fillMaxWidth` explicitly.
  - Golden data must be clock-free: wall timestamps flake record-vs-verify
    by milliseconds. Freeze date text in golden lambdas; production keeps
    the live formatter.
  - `LazyColumn` keys need stability the old lists never had: key by
    index when items carry no id (colliding `receivedTime` keys crash).

## 5. What reviewers check (conformance list)

1. Guide followed literally; deviations call out the guide section.
2. No `!!`, no `GlobalScope`, no new `Thread`/`Handler` outside §2's
   allowlist, no per-packet allocations in audio paths.
3. `./scripts/check.sh` green; screenshot diffs empty for UI slices;
   torture paths (rotate mid-dialog, switch mid-connect, deny mid-flow)
   unchanged.
4. Commit message carries review attribution for agent-reviewed slices.
