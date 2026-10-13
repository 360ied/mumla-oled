# Migration Status Log

Progress record for the big-bang modernization on branch `migration/full-modern`
(worktree `.worktrees/migration/full-modern`; root repository stays on `master`).
Detail (inventories, pins, verification) lives in [slices](slices.md) and the
[porting guide](porting-guide.md); this file tracks what landed, what was
decided, and what is still owed.

## Slice 0 — Scaffold (DONE 2026-10-12, `check.sh` green)

- `4071b2db` — version catalog + plugin wiring: `gradle/libs.versions.toml`
  (single source of truth), both modules migrated from `buildscript` to catalog
  `plugins {}` aliases, Kotlin/kapt/parcelize + Hilt applied with scaffold-only
  Hilt deps (no consumers yet), minSdk stays 21.
- `db6764e8` — AGP 9 toolchain adoption (cherry-picked from `spike/agp9`,
  reworded): wrapper 9.1.0, AGP 9.0.1, Hilt 2.60.1, `resValues = true` for the
  AGP 9 default flip, `flake.nix` Build-Tools 35.0.0 → 36.0.0 (AGP 9 minimum),
  AGP 9 opt-outs (`newDsl=false`, `builtInKotlin=false`). Verified by `check.sh`
  green on the migration branch.

Final slice-0 pins: AGP `9.0.1`, Gradle `9.1.0`, Kotlin `2.4.21` (kapt + TODO —
no stable KSP 2.4 on Central), Hilt `2.60.1`, coroutines `1.11.0`, Compose BOM
`2026.09.00`, navigation-compose `2.10.2`, activity-compose `1.13.0`,
hilt-navigation-compose `1.4.0`, lifecycle `2.11.0`, androidx-hilt `1.4.0`,
Room `2.8.5`, datastore-preferences `1.2.1`, Paparazzi `2.0.0-alpha05.1`
(pinned, NOT applied — deferred, see below).

## Slice 1 — minSdk 33 + legacy deletion (DONE 2026-10-12, `check.sh` green)

- `7f876cfc` (+143/−470) — minSdk 21 → 33 both modules; deleted every gate
  below 33 except `UPSIDE_DOWN_CAKE`: the SCO legacy duality (`bringUpLegacy`,
  broadcast receiver, `isHeadsetConnected`, both pre-S voice pre-holds), pre-M
  audio constructors, the `ChannelChatFragment` storage branch + permission
  machinery, deprecated `getParcelable` overloads, always-true branches, and
  orphaned imports. Gate-grep returns only the U site; assemble +
  `lintFossDebug` + `check.sh` green.
- `8abec58b` — on-device result: fossDebug installed on hardware, basic
  functionality (boot/connect/voice) confirmed by the user. Deeper torture
  (SCO flap, A2DP stagger, overlay drag/rotate, notification paths) still open.

## Fork outcomes (all bisected 2026-10-12)

- **Kotlin 2.4.21 stands.** A `MergeResources` failure initially suspected KGP
  but bisected cleanly to Paparazzi; 2.4.21 + kapt proven green.
- **Hilt capped by AGP, then uncapped.** Hilt ≥ 2.59 requires AGP 9, so the
  scaffold first pinned 2.58; the toolchain adoption restored 2.60.1.
- **Paparazzi 2.0.0-alpha05.1 disqualified on BOTH toolchains, deferred to
  slice 6.** On AGP 8 it breaks resource merging build-wide
  (`MergedResourceWriterRequest` NoSuchMethodError when applied anywhere); on
  AGP 9 it configures fine but ships `android-all` classes that shadow the
  mockable `android.jar`, failing 7 stub-dependent tests (including the
  must-keep-unmodified `ChannelFragmentDensityTest`; 301/301 green with it
  off). The 1.3.x fallback is dead (layoutlib caps at compileSdk 35; project
  is on 36). Re-evaluate at slice 6 (stable 2.0.0 may behave; otherwise scope
  its classpath or gate dialogs on-device).
- **AGP 9 needs Build-Tools 36.0.0** (flake.nix updated in-branch; `master`
  still 35.0.0) and `resValues = true` (AGP 9 default flip; the app uses
  `resValue`).

## Spike record

- Branch `spike/agp9` (worktree `.worktrees/spike/agp9`), commit `ade4446e`:
  AGP 9.0.1 + Gradle 9.1.0 viability probe — `check.sh` green (301 app tests,
  24 humla suites, native suite) + `assembleFossDebug` APK. Kept as evidence;
  cleanup only on explicit request (never autonomous).

## Outstanding debt (do not forget)

- Full new-DSL / built-in-Kotlin migration (opt-outs are deprecated warnings).
- Release + R8 verification deferred to pre-landing (explicitly accepted).
- Paparazzi re-evaluation at slice 6 (above).
- Re-verify androidx-hilt 1.4.0 against Hilt 2.60 when adding
  `hilt-navigation-compose` in slice 7.
- kapt → KSP when a stable KSP for Kotlin 2.4 lands.

## Review

- Pedantic review fan-out (6 slices, `reviewer` agents) completed; synthesis
  concurred on two blockers (dual-bus split-brain, duplicate-listener leak)
  plus smaller defects (manifest minSdk, jvmTarget, `<9` migration path,
  `close()` scope, P1 overlay guard, stale docs/imports).
- `295754b6` fixes all concurred items: DataStore-backed `PreferenceDataStore`
  in the fragment base, both custom dialogs and all four listeners ported,
  `Migration(8, 10)`, listener-bridge unit test, toolchain/hygiene batch.
  Contested/downgraded (runBlocking interim, pre-existing nits) recorded as
  debt, not fixed. `check.sh` + assemble + lint green.

## Slice 2 — Data layer (DONE 2026-10-12, `check.sh` green)

- `0b44fc2a` — Room v10 (`MumlaRoomDatabase` behind `MumlaDatabase`, 7
  entities/DAOs, `MIGRATION_9_10` recreating all tables for validator-exact
  shapes), `MumlaSQLiteDatabase` deleted; `AppDataStore` + `SettingsRepository`
  (41-key snapshot, `SharedPreferencesMigration`, key-diff listener bridge);
  Hilt `@HiltAndroidApp` + `DatabaseModule` + `SettingsModule`.
- `2c464334` — `Settings` rewritten over the repository (surface kept, theme
  and language added); `ConfigDelta` replaces the pref→bundle→effect
  dispatch (`HumlaService.apply`, `MumlaService` delta dispatch + appliers,
  slim `ServerConnectTask` carrying only the server id); `MumlaApplication`
  reads theme and language from `Settings`. Tests ported (in-memory Room +
  v9→v10 migration test, DataStore doubles, fake deleted).
- All 17 `ConfigDelta` keys round-trip audited; comment seen-tracking now
  compares text (was blob-vs-text, never matched). Lint + assemble green.
- Owed: on-device connect/voice/reconnect-toast pass; R8/release still
  deferred to pre-landing.

## Slice 3 — Kotlin conversion, `:libraries:humla` (DONE 2026-10-13, `check.sh` green)

- `99bb3499` — batch 1 (`exception/*` + `Constants`): identical exception
  overloads with explicit nullable types (`AudioException` open); `Constants`
  becomes an `object` with `const val` literals (Java switch labels keep
  compiling) + `@JvmField`/`@JvmStatic` for derived values and functions.
- `3b9d7144` — batch 2 (`util/*`, 10 files): nullable-parameter observer
  trio (`HumlaObserver`/`HumlaNetworkListener` stay `open`); `HumlaException`
  parcel layout + static `CREATOR` intact; `parseURL` keeps `@Throws` for Java
  catch sites; `VoiceTargetMode` range mapping literal.
- `6ddd55d3` — batch 3 (`model/*`, 13 files; `Server` deferred to slice 5):
  accessors become properties (JVM-identical signatures); asymmetric
  `ByteString` accessors stay explicit; self-mute coercion moves into
  property setters; `canEnter()` stays a method pair; `IChannel.getParent`
  narrows to `Channel` (all readers source-compatible).
- Converter lessons (first compile caught all five): no smart-cast on
  mutable properties (copy to locals), stdlib `compareTo(..., ignoreCase)`
  over Java `compareToIgnoreCase`, `Byte` ranges iterate as `Int` (no
  `Byte.rangeTo`).
- `da0d3003` — batch 4 (`audio/inputmode/*`, 4 files): `IInputMode` with
  `ShortArray`; `ActivityInputMode` clamped dual-threshold init with private
  setters (VAD defaults stay `const val` from the native facade);
  `ToggleInputMode` volatile flag as an `isTalkingOn` property.
- `e5c0339d` — batch 5 (`audio/*`, 6 files): capture/render threads with
  shutdown-race guards literal; JNI facades syntactic only (`@Synchronized`
  preserved, static natives verified byte-identical via `javap`); `Pacer`
  test-visible members `@JvmField`; package test seams widened to public
  (Kotlin has no package visibility); `finalize` drops its super call.
- `3e9fe745` — batch 6 (`net/*`, 14 files): `CryptState` syntactic only
  (`@Synchronized` preserved, native signatures verified byte-identical via
  `javap`, static `nativeIsSupported` kept on the outer class via `@JvmStatic`);
  `HumlaNetworkThread` keeps its send-executor check bug literally for slice 4
  to delete; `HumlaTCP`/`HumlaUDP`/`HumlaConnection` preserve threading, SRV
  lookup, frame validation, and ping scheduling exactly; package test seams
  widened to public (`filterTlsProtocols`, UDP queue seams, connection ping
  internals; `HumlaSSLSocketFactoryTest` overrides widened to match);
  `finalize` drops its super call.
- `ca55913c` — batch 7 (`protocol/*`, 4 files): listener `Stub`s become open
  classes with open overrides; `ModelHandler` accessors become properties with
  `synchronized` preserved exactly; `AudioHandler` keeps zero-alloc buffers,
  bandwidth-lock pairing, and `Builder` literally (`EffectiveAudioConfig`
  fields stay `@JvmField`). Follow-up fixes this batch surfaced: null-clearable
  `ByteString` setters go nullable across `IUser`/`User`, and
  `HumlaNetworkListener` methods go open (batch 2 left them final).
- Converter lessons (later batches): Java-subclass parity needs `open` on every
  overridable (lifecycle, interface impls, plain publics — the app and its tests
  re-override several); `fun getX()` stays a method call (no property syntax);
  `Byte`/`Long` literals need explicit `.toByte()`/`L` suffixes; test seams that
  take null (e.g. `HumlaConnection(null)`) stay nullable; always run
  `:libraries:humla:testDebugUnitTest` explicitly (root `testFossDebugUnitTest`
  skips the flavorless humla suite).
- `dcbfc4cd` — batch 8 (`IHumlaService`, `IHumlaSession`): syntax only, surface
  frozen (factory name, contracts, `Throws` kept); object returns go nullable
  where the docs promise null so the still-Java `HumlaService` implements freely.
- `9b7472d9` (+ `5e4dd138` deletion bookkeeping) — batch 9 (`HumlaService`,
  the god, converted but not restructured): switches become exhaustive `when`;
  ConfigDelta/model accessors go property syntax, protobuf builders keep method
  syntax; connection/model access funnels through `requireNotNull` helpers;
  text-message target lists skip missing entries; audio/model getters fail fast.
- Verification: full `testFossDebugUnitTest` green per batch (`ModelTest`,
  `ChannelLinksTest`, `WhisperTargetListTest`, `URLParserTest`,
  `AudioOutputPacerTest`, `NativeAudioOutputEngineTest`, observer/
  voice-target suites); full `check.sh` green at the slice boundary (JVM +
  native suites). Slice 3 complete: no `.java` remains under `:libraries:humla`
  except `Server` (slice 5) and the generated protobuf pair (never touched).

## Review (slice 3)

- Pedantic review fan-out (7 slices, `reviewer` agents) over the whole of
  slice 3 completed; synthesis concurred on three contained P1s (dropped
  `volatile` on `User.isSuppressed`, `PacketBuffer` int/long widening and
  truncation slips, `spkiSha256`/Builder NPE→IAE swaps) plus notes (TAG
  restoration, trust-manager null guards, `Permissions.All` constness,
  `isIpLiteral` exactness, small hygiene).
- `02e23c26` fixes all concurred items and eliminates every `!!` in humla
  main (92 sites → locals with explicit null handling, NPE-exact) with
  `check.sh` re-gated green.
- Contested with evidence (no action): `HumlaObserver` openness (Kotlin
  `override` is open by default — `javap` + green build disprove the BLOCK),
  `const`-in-companion Java visibility (`javap` shows outer statics),
  inner-`isPinnedLeaf` widening (wrapper class is already private).
  Accepted as intentional: widened test seams, internal `requireNotNull`
  hardening, null-filtering over null-carrying, fail-fast talking listener.
  Guide §1 amended (openness, NPE-exact boundaries, seam widening).

## Next

Slice 4 — coroutines service core, per [slices](slices.md).

## Slice 4 — Coroutines service core (IN PROGRESS)

- `940e3e38` — LifecycleService base (no behavior change) + static
  `getReconnectDelayForAttempts` + `ReconnectBackoffTest`/`PingIntervalTest` pins.
- `6970ddb8` — reconnect runnable → `reconnectJob`, route debounce/settle
  → `routeDebounceJob`/`routeSettleJob` with `delay()`, talking-state/SCO
  notify/network-available hops → `lifecycleScope.launch`.
- `2e05feb9` — ping `ScheduledExecutorService` self-chain →
  `SupervisorJob + IO` loop with `delay(getNextPingIntervalSeconds)`.
- `f946dfdc` — `HumlaNetworkThread` deleted; `HumlaTCP` owns a
  `ConnectionScope` (SupervisorJob + IO) with Mutex-serialized writes and
  `withTimeoutOrNull(800)` SRV lookup.
- `0f336bdd` — `HumlaUDP` BlockingQueue + lock →
  `Channel(capacity, DROP_OLDEST)` with IO consumer loop; tests moved to
  `getQueuedCount`/`pollQueuedPacket`/`isQueueEmpty`.
- `ea439920` — SCO Handler timeout → scope `timeoutJob` with `delay()`;
  `stop()` cancels the child, `destroy()` cancels the scope.
- ModelHandler: main-thread confinement documented (Main.immediate
  equivalent via HumlaTCP main-Looper fan-out); `ConcurrentHashMap`s and
  `@Synchronized` retained, callbacks fan-out unchanged (no SharedFlow).
  Network/AudioDevice callbacks hop directly into `lifecycleScope` (single
  consumer fire-and-forget — callbackFlow adds no backpressure value here).
- Owed to close slice 4: full `./scripts/check.sh` (JVM + native) green at
  the boundary (done, `b7326ab2`) + on-device torture (reconnect storm,
  SCO flap, A2DP stagger, route flip mid-call).
- On-device result: fossDebug installed, basic functionality
  (boot/connect/voice) confirmed by the user. Deeper torture (reconnect
  storm, SCO flap, A2DP stagger, route flip mid-call) still open.
