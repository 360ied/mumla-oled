# Migration Slices (Concrete Work Plan for Mumla OLED)

Each slice is a sequence of atomic commits; every commit leaves the tree
working and passing `./scripts/check.sh`. Reviewers check conformance to
[porting-guide](porting-guide.md) (checklist: §5); review attribution goes in the commit body
for agent-reviewed slices. The binder surface
(`IMumlaService`/`IHumlaService`/`IHumlaSession`) stays frozen throughout.

## Slice 0 — Scaffold (no behavior change)

1. Create the worktree from the repo root (root stays on `master`):
   `./scripts/worktree.py add migration/full-modern`
2. Add `gradle/libs.versions.toml` with these pins (verified 2026-10-12,
   re-verify at scaffold; floors are binding, patches float):
   - `kotlin = "2.4.21"` (AGP 8.13.2 is inside its supported range),
     `ksp` = KSP2 build matching Kotlin 2.4.21 (resolve the exact artifact at scaffold),
     `coroutines` = the release recommended for Kotlin 2.4 (verify at scaffold; floor 1.10.x)
   - `compose-bom = "2026.09.00"` (pins `compose-material3 1.4.0`),
     no hand-pinned Compose artifacts
   - `hilt = "2.60.1"` (`com.google.dagger`), `androidx-hilt = "1.4.0"`
   - `room = "2.8.5"` (`androidx.room`; not the `room3` 3.0.x line)
   - `lifecycle`, `datastore-preferences` = latest stable at scaffold (record exact versions in the slice-0 commit)
   - `paparazzi = "2.0.0-alpha05.1"` — alpha accepted deliberately: the
     2.x line renders dialogs/popups as real windows, which the dialog
     parity gate needs. **Fork:** if the plugin requires Gradle 9+
     (wrapper here is 8.13), either upgrade the wrapper (recorded as its
     own commit) or fall back to the Paparazzi 1.3.x stable line, in which case dialogs are gated on-device only (overriding the README fallback line, which describes this fork branch).
3. Apply plugins: `org.jetbrains.kotlin.android` (both modules),
   `com.google.devtools.ksp`, `com.google.dagger.hilt.android`,
   `org.jetbrains.kotlin.plugin.parcelize`, `app.cash.paparazzi` (app module). minSdk stays 21.
4. Baseline: `./scripts/check.sh` green before any slice-1 edit.

## Slice 1 — minSdk 33 + legacy deletion (trial slice)

Config: `minSdkVersion 21 → 33` in `app/build.gradle` and
`libraries/humla/build.gradle`. Then the full site inventory below —
every row is a deletion, no conversions. (Line numbers are pre-slice;
`S` = 31, `S_V2` = 32.)

### App module (`:app`)

| Site | Gate | Disposition |
|---|---|---|
| `MumlaActivity.java:535` TIRAMISU `getParcelable` | typed-vs-deprecated | Keep typed branch only; delete `else` |
| `MumlaActivity.java:541-542` deprecated `getParcelable(String)` | deprecation | Switch both calls to typed `getParcelable(key, Server.class)` |
| `MumlaActivity.java:1011` S `BLUETOOTH_CONNECT` | `SDK_INT >= S &&` | Delete the version clause; permission exists at 33 |
| `MumlaActivity.java:1032` TIRAMISU notifications | `SDK_INT >= TIRAMISU &&` | Delete the version clause; keep the asked-flag + grant flow |
| `ChannelChatFragment.java:194` S_V2 storage | `SDK_INT <= S_V2` branch | Delete legacy branch; call `imagePicker.launch` directly; remove `readPermissionRequester`, `onReadPermissionGranted`, and the now-unused `RequestPermission` / `READ_EXTERNAL_STORAGE` / `checkSelfPermission` / `PERMISSION_GRANTED` / `SDK_INT` / `Build` imports (verify each has no other use first) |
| `MumlaMessageNotification.java:88` O channel | `SDK_INT >= O` | Delete gate; always create the channel |
| `MumlaMessageNotification.java:113` TIRAMISU notify | `SDK_INT < TIRAMISU \|\|` | Drop the version clause; keep the grant check |
| `MumlaHotCorner.java:79` Q layout | `SDK_INT >= Q` | Delete gate; keep body |
| `MumlaHotCorner.java:95` O overlay type | ternary | Always `TYPE_APPLICATION_OVERLAY`; delete `TYPE_SYSTEM_ALERT` |
| `MumlaHotCorner.java:163` M overlay perm | `SDK_INT >= M &&` | Delete the version clause |
| `MumlaHotCorner.java:210,227` Q exclusion | `SDK_INT < Q \|\|` | Delete the version clause; keep `!mShown` |
| `MumlaOverlay.java:255` O overlay type | ternary | Always `TYPE_APPLICATION_OVERLAY` |
| `MumlaOverlay.java:289-291` R insets | `@TargetApi(R)` + gate | Remove annotation + gate; keep `getCurrentWindowMetrics` body; delete `return null`, mark the method `@NonNull`, and drop now-dead `insets == null` branches at call sites |
| `MumlaConnectionNotification.java:339` O channel | `SDK_INT >= O &&` | Delete the version clause |
| `MumlaConnectionNotification.java:424` U foreground type | `UPSIDE_DOWN_CAKE` | **KEEP** — the sole surviving gate |
| `MumlaService.java:902` S grant helper | `SDK_INT < S` early-true | Delete early return; keep `checkSelfPermission` |
| `MumlaService.java:1062,1169` M overlay perm | `SDK_INT >= M &&` | Delete the version clause (both sites) |
| `PinnedTlsSocketFactory.java:62` N SNI | `SDK_INT >= N` | Delete gate; always set SNI |
| `ServerEditFragment.java:254` deprecated `getParcelable` | deprecation | Switch to `getArguments().getParcelable(ARGUMENT_SERVER, Server.class)` |

Untouched: `MumlaOverlay.java:306` (`legacy` is a resource comment, no
code), `MumlaImageGetter.java:393` (comment noting no gate),
`MumlaService.java:537` `setLegacyStreamType` (not a version gate),
`CertificateImportActivity.java:113` `BundleCompat` (compat already).

### Library (`:libraries:humla`)

| Site | Gate | Disposition |
|---|---|---|
| `AudioOutput.java:99` M builder | `SDK_INT >= M` + `else` | Delete `else` (legacy `AudioTrack` ctor); keep Builder path |
| `AudioOutput.java:119` O low-latency | `SDK_INT >= O` | Delete gate; always `setPerformanceMode` |
| `AudioOutput.java:251` M head query | ternary | Always `getBufferSizeInFrames()` |
| `AudioInput.java:96` M builder | `SDK_INT >= M` + `else` | Delete `else`; keep Builder path |
| `BluetoothScoManager.java:251` S dispatch | `>= S` / `else` | Always `bringUpModern`; delete `bringUpLegacy`, `isHeadsetConnected`, `isBluetoothScoOnLegacy`, `registerLegacyReceiver`, `mLegacyReceiver`, `onLegacyStateChanged`, legacy teardown block, legacy class-doc section. `REASON_NO_DEVICE` stays (modern path returns it) |
| `BluetoothScoManager.java:399,456` S queries | `>= S` / `else` | Keep modern branch only (both) |
| `BluetoothScoManager.java:660` S release | `>= S` / `else` | Always `clearCommunicationDevice()` |
| `BluetoothScoManager.java:676` S perm helper | `SDK_INT < S` early-true | Delete early return |
| `BluetoothScoManager.java:760` S listener removal | `!= null && >= S` | Drop the version clause |
| `HumlaService.java:497` M device callback | `SDK_INT >= M` | Delete gate; always register |
| `HumlaService.java:673` pre-S voice pre-hold | `SDK_INT < S && …` block (L671-680) | Delete the `acquiredHere` declaration, the `< S` block, and the `if (acquiredHere) releaseVoiceMode();` undo in the abort path below (modern path needs no pre-hold) |
| `HumlaService.java:907` N network callback | `>= N` / `else` | Always `registerDefaultNetworkCallback` |
| `HumlaService.java:1165` M media query | `SDK_INT < M \|\|` | Delete the version clause; keep null check |
| `HumlaService.java:1371,1388` S SCO clear/query | `>= S` / `else` | Keep modern branch only (both) |
| `HumlaService.java:1434` pre-S pre-hold | `SDK_INT < S && …` block | Delete the whole block |
| `HumlaService.java:1602` deprecated `getParcelable` | deprecation | Switch to typed `getParcelable(EXTRAS_SERVER, Server.class)` |

### Slice-1 verification

- `grep -rn "VERSION_CODES\.\(M\|N\|O\|Q\|R\|S\|S_V2\|TIRAMISU\)\b"` over
  main sources (`app/src/main`, `libraries/humla/src/main`) returns only the U site; Android lint clean (`nix develop --command ./gradlew lintFossDebug`; confirm `check.sh` coverage at trial).
- `./scripts/check.sh` green (JVM + native).
- Boot + connect + voice on the Pixel 4a (floor) and one modern device;
  SCO connect/disconnect, A2DP attach, overlay show/hide, notification
  post/dismiss.

## Slice 2 — Data layer (Java; Kotlin conversion comes in slices 3/5)

Room + DataStore + Hilt so the tree stays
green; only new files are Kotlin (plugin lands in slice 0) — no Java→Kotlin conversions of existing files here.

- **Entities (7 tables in `MumlaSQLiteDatabase`) →**
  `server`, `favourites`, `tokens`, `comments`, `local_mute`,
  `local_ignore`, `certificates`. `Server` becomes `@Entity @Parcelize`
  later (slice 5); in this slice keep the Java class and map columns.
- **DAOs mirror `MumlaDatabase` (24 data methods; 27 declared incl. `open`/`close` + default `addCertificate` overload)** one-for-one; keep method
  names so call sites diff minimally. In-memory Room replaces
  `MumlaSQLiteDatabase` behind the `MumlaDatabase` interface.
- **DataStore (Preferences):** the 48 `Settings` read accessors (get/is/should groups; excludes `getInstance`/statics and pairs setters with their getter's key)
  (`Settings.java`, getter/setter groups: input, PTT/hot-corner, certs,
  notifications/TTS, overlay, audio) become typed keys with defaults.
- **`ConfigDelta`:** replaces the pref→bundle→effect double dispatch
  (`MumlaService.onSharedPreferenceChanged` ~L774 +
  `HumlaService.configureExtras` ~L1598 collapse into `apply(ConfigDelta)`;
  the stringly `EXTRAS_*` bundle dies. Round-trip checklist — every key
  must survive (server, certificate+password, detection_threshold,
  amplitude_boost, transmit_mode, input_quality, force_tcp, client_name,
  access_tokens, audio_stream, frames_per_packet, trust_store+password+
  format, half_duplex, local_mute_history, local_ignore_history,
  adaptive_leveler, bluetooth_sco; deprecated input_rate/audio_source
  stay ignored as today; deprecated-unwritten `EXTRAS_AUTO_RECONNECT`,
  `EXTRAS_AUTO_RECONNECT_DELAY`, `EXTRAS_USE_OPUS` are dropped (never written by any sender).
- **Hilt modules (new Kotlin files — Kotlin plugin lands in slice 0, so
  new files are Kotlin from birth):** `DatabaseModule` (Room db + DAOs),
  `SettingsModule` (DataStore), `ServiceModule`. Provider interfaces
  (`HumlaServiceProvider`, `DatabaseProvider`) die later in slice 5 with
  the Kotlin conversion of their call sites.
- **Files touched:** `db/*` (4) + new entities/DAOs/modules,
  `Settings.java`, `MumlaService` pref switch, `HumlaService`
  `configureExtras`, `Server.java` column mapping, `FakeSharedPreferences`
  + `MumlaDatabaseTest`/`SettingsOverlayTest`.
- **Verification:** `./scripts/check.sh`; `MumlaDatabaseTest` ported to
  in-memory Room; every `ConfigDelta` key round-trips or the slice fails.

## Slice 3 — Kotlin conversion, `:libraries:humla` (syntax first)

62 files minus generated protobuf (`Mumble.java`, `MumbleUDP.java` —
never touched). Batches in dependency order, ~one package per commit:

1. `exception/*` (4) + `Constants.java`
2. `util/*` (10: `HumlaCallbacks`, `HumlaException`, observers…)
3. `model/*` (13 files + `Server` deferred: `User`, `Channel`, `Message`, whisper targets…; `Server` converts in slice 5)
4. `audio/inputmode/*` (4)
5. `audio/*` (6) — JNI facades (`NativeAudioInputEngine`,
   `NativeAudioOutputEngine`) convert
   **syntactically only**: `@Synchronized` preserved, native signatures
   byte-identical, no new allocations in encode/decode paths
6. `net/*` (14): `CryptState`, `PacketBuffer`, `FrameValidator` first;
   `HumlaTCP`/`HumlaUDP` next; `HumlaConnection` last
7. `protocol/*` (4: `ModelHandler`, `AudioHandler`, listeners)
8. `IHumlaService`, `IHumlaSession` (surface frozen, syntax only)
9. `HumlaService.java` last (the god, converted but not yet restructured)

Verification: existing humla JVM tests (`AudioRoutePolicyTest`,
`HumlaServiceVoiceTargetTest`, `ModelHandlerSelfMuteTest`, …) green;
`./scripts/check.sh`.

## Slice 4 — Coroutines service core (the real debt kill)

| Today | Becomes | Test carry-over |
|---|---|---|
| `mHandler` posts (talking-state, SCO notify) | `lifecycleScope.launch` hops | existing observer tests |
| Reconnect runnable + `removeCallbacks` disarm | `reconnectJob` + `cancel()`; backoff delays via `delay()` | **new:** backoff-sequence test |
| Route debounce/settle `postDelayed` | cancellable `debounceJob`/`settleJob` | `AudioRoutePolicyTest` |
| `HumlaNetworkThread` 3 executors (incl. dead receive executor) | deleted; one `ConnectionScope` (`SupervisorJob` + `Dispatchers.IO`), writes via `Mutex` | connection tests |
| Ping `ScheduledExecutorService` self-chain | `while (isActive) { sendKeepalivePing(); delay(interval) }` | ping-interval logic test |
| `HumlaUDP` queue + `mSendLock` head-drop | `Channel(capacity, DROP_OLDEST)`; consumer becomes an `IO` loop | UDP queue-capacity tests |
| SCO epoch + `Handler` timeout | cancellation *is* the epoch; `withTimeoutOrNull(8000)` | SCO retry/bring-up tests |
| `ModelHandler` `synchronized` writers | single-threaded model dispatcher; `ConcurrentHashMap`s stay (UDP-thread reads) | `ModelHandlerSelfMuteTest` |
| `HumlaCallbacks` set fan-out | interface kept; backed by set or `SharedFlow`, per-observer isolation kept | observer tests |
| `configureExtras` bundle | `apply(ConfigDelta)` from slice 2 | round-trip checklist |
| Capture/render audio threads | **stay raw threads** (blocking IO); only control plane moves to the scope | native suite |

Verification: `./scripts/check.sh` + on-device torture (reconnect
storm, SCO flap, A2DP stagger, route flip mid-call).

## Slice 5 — Kotlin conversion, `:app` (syntax first)

74 files, batches in dependency order (data-layer files already Kotlin
from slice 2; `Server` converts here to `@Entity @Parcelize` — deferred from slice 3's model batch):

1. `util/*` (11) 2. `db` leftovers (`MumlaSQLiteDatabase` deletion, `DatabaseProvider` removal with its call sites, `DatabaseCertificate` disposition) 3. `servers/*` (6)
4. `service/*` helpers (8+1: notifications, overlay, hot-corner —
   `MumlaService` itself last) 5. `channel/*` (15+3)
6. `preference/*` (17; still `PreferenceFragment`-based — Compose comes
   in slice 7) 7. `app/*` (7; `MumlaActivity` last)

`MumlaService`/`IMumlaService` binder surface converts syntactically
only (frozen per plan). Verification: all app JVM tests (incl.
Robolectric comment-dialog tests) green; `./scripts/check.sh`.

## Slice 6 — Compose foundation (theme + leaf + goldens)

- Port `themes.xml` (`Theme.Mumla`/`Theme.Mumla.Oled`, colors, type,
  dimens) into a Compose **Material3** `MaterialTheme` via tokens —
  including the OLED-black scheme as a third scheme alongside light/dark.
- Paparazzi goldens infrastructure (per slice 0 fork decision).
- First screen: `ServerInfoFragment` → `ServerInfo` route + ViewModel
  (its poll executor becomes `collectAsState`); first golden recorded.
- Verification: Paparazzi golden passes; old-vs-new screenshots compared
  by eye on the Pixel 4a; `./scripts/check.sh`.

## Slice 7 — Compose screens (leaf-to-root, dialogs last)

Order with per-screen gates: `FavouriteServerList` → `ServerEdit` →
`ChannelList` (**`ChannelFragmentDensityTest` must pass unmodified**) →
`Channel` → `ChannelChat` → comment fragments → `AccessToken` →
preference screens (Compose + DataStore directly; `res/xml/settings_*.xml` + `preference_headers.xml` retire
last) → `ChannelFragment` → `MumlaActivity` shell + `DialogHost` (all 7
fields, dismissal semantics intact) last → delete Fragment navigation
(`MumlaActivity.loadDrawerFragment`, `DrawerAdapter` routes, back-stack transactions → NavHost route map);
drawer becomes `ModalNavigationDrawer`. Final gate: full torture matrix
(rotate mid-dialog, switch mid-connect, deny mid-flow) on the Pixel 4a
plus one modern device, `./scripts/check.sh`, and a landing checklist
(merge requested explicitly — never autonomous).

## Verification matrix

| Slice | `check.sh` | Unit suites | Device/golden gates |
|---|---|---|---|
| 0 scaffold | green baseline | — | — |
| 1 minSdk | green | all existing | gate-grep clean, 2-device boot/voice |
| 2 data | green | Room in-memory DB tests, Settings tests | ConfigDelta round-trip |
| 3 Kotlin humla | green | humla JVM suite | — |
| 4 coroutines | green | route/reconnect/ping tests | torture: reconnect/SCO/A2DP |
| 5 Kotlin app | green | app JVM + Robolectric | — |
| 6 foundation | green | Paparazzi golden (leaf) | eye-compare on 4a |
| 7 screens | green | per-screen goldens, density test | full torture matrix, landing checklist |