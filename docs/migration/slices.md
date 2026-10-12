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
     `ksp` = KSP2 build matching Kotlin 2.4.21 (resolve the exact artifact at scaffold — the only unpinned entry),
     `coroutines = "1.11.0"`
   - `compose-bom = "2026.09.00"` (pins `compose-material3 1.4.0`),
     no hand-pinned Compose artifacts
   - `hilt = "2.60.1"` (`com.google.dagger`), `androidx-hilt = "1.4.0"`
   - `room = "2.8.5"` (`androidx.room`; not the `room3` 3.0.x line)
   - `lifecycle = "2.11.0"` (`lifecycle-service` for the `LifecycleService` switch, `viewmodel-ktx`, `viewmodel-compose`, `runtime-ktx`), `datastore-preferences = "1.2.1"`
   - `paparazzi = "2.0.0-alpha05.1"` — alpha accepted deliberately: the
     2.x line renders dialogs/popups as real windows, which the dialog
     parity gate needs. **Gradle fork (resolved, not open):** upstream evidence (Gradle-9 compat tests targeting 9.1.0; 9.0 incompatible with Paparazzi master — per upstream notes, re-verify at scaffold) says 2.x needs Gradle 9.x, and this repo's wrapper is 8.13 — so the primary path is upgrading the wrapper to Gradle 9.x as its own slice-0 commit, then applying the plugin. The 1.3.x fallback is likely dead (its layoutlib caps at compileSdk 35; the project is on 36). Spike order: try 2.0.0-alpha05.1 on 8.13 first (cheap disproof); on failure, upgrade wrapper, re-try; record the outcome here before slice 1.
3. Apply plugins: `org.jetbrains.kotlin.android` (both modules),
   `com.google.devtools.ksp`, `com.google.dagger.hilt.android`,
   `org.jetbrains.kotlin.plugin.parcelize`, `app.cash.paparazzi` (app module). minSdk stays 21 (scaffold stays behavior-neutral; slice 1 performs the bump + deletion together).
4. Baseline: `./scripts/check.sh` green before any slice-1 edit.

## Slice 1 — minSdk 33 + legacy deletion (trial slice)

Config: `minSdkVersion 21 → 33` in `app/build.gradle` and
`libraries/humla/build.gradle`. Then the full site inventory below —
every row is a deletion, no conversions. (Line numbers are pre-slice;
`S` = 31, `S_V2` = 32. Inventory verified against master `9fef92e1`;
re-run the gate-grep at slice-1 start and correct drift before deleting.)

### App module (`:app`)

| Site | Gate | Disposition |
|---|---|---|
| `MumlaActivity.java:535-542` TIRAMISU `getParcelable` | typed-vs-deprecated | Keep typed branch only; delete `else` |
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

- `grep -rEn "VERSION_CODES\.(M|N|O|Q|R|S|S_V2|TIRAMISU)\b"` over
  main sources (`app/src/main`, `libraries/humla/src/main`) returns only the U site; Android lint clean via explicit `nix develop --command ./gradlew lintFossDebug` (outside `check.sh`, which covers tests+native only).
- `./scripts/check.sh` green (JVM + native).
- Boot + connect + voice on the Pixel 4a (floor) and one modern device;
  SCO connect/disconnect, A2DP attach, overlay show/hide, notification
  post/dismiss.

## Slice 2 — Data layer (Java; Kotlin conversion comes in slices 3/5)

Room + DataStore + Hilt so the tree stays
green; only new files are Kotlin (plugin lands in slice 0) — no Java→Kotlin conversions of existing files here.

- **Entities (7 tables, same file, same schema — Room opens the existing
  `mumble.db` at version 9; `fallbackToDestructiveMigration` is forbidden,
  the fleet has real data):**
  `server(_id INTEGER PRIMARY KEY AUTOINCREMENT, name/host/username TEXT NOT NULL, port INTEGER, password TEXT)` |
  `favourites(_id PK AUTOINCREMENT, channel TEXT NOT NULL, server INTEGER NOT NULL)` |
  `tokens(_id PK AUTOINCREMENT, value TEXT NOT NULL, server INTEGER NOT NULL)` |
  `comments(who TEXT NOT NULL, comment TEXT NOT NULL, seen TEXT NOT NULL — no PK; stores the literal string `datetime('now')` (pre-existing app bug, file separately — column value never read); `MIGRATION_9_10` recreates this table with TEXT affinity via data-preserving INSERT…SELECT, every other table schema-identical)` |
  `local_mute(server INTEGER NOT NULL, user INTEGER NOT NULL, UNIQUE(server,user))` |
  `local_ignore(server INTEGER NOT NULL, user INTEGER NOT NULL, UNIQUE(server,user))` |
  `certificates(_id PK AUTOINCREMENT, data BLOB NOT NULL, name TEXT NOT NULL, password TEXT)`. `Server` becomes
  `@Entity @Parcelize` later (slice 5); in this slice keep the Java class
  and map columns.
- **DAOs mirror `MumlaDatabase` (24 data methods; 27 declared incl. `open`/`close` + default `addCertificate` overload)** one-for-one; keep method
  names so call sites diff minimally (servers 4 + comments 2 + pinned 4 +
  tokens 3 + localMute 3 + localIgnore 3 + certificates 5 = 24; the default overload delegates and needs no DAO). Room opens the existing `mumble.db` at version 9 and migrates v9 → v10 via an explicit `Migration` (or `autoMigration`) — destructive fallback is forbidden, the fleet has real data. In-memory Room replaces
  `MumlaSQLiteDatabase` behind the `MumlaDatabase` interface.
- **DataStore (Preferences):** the 46 `Settings` instance read accessors (get/is/should; excludes `getInstance`, static `isPttKeyBound`, and setters, which pair with their getter's key — 46 accessors over 41 keys, derived accessors share)
  (`Settings.java`, getter/setter groups: input, PTT/hot-corner, certs,
  notifications/TTS, overlay, audio) become typed keys with defaults. Fleet settings survive via `SharedPreferencesMigration` on the default prefs file (`<package>_preferences`, all keys) + the per-key defaults table below (defaults = today's `DEFAULT_*` constants and `getString/getInt/getBoolean` fallbacks); no key may silently reset.

  | Key | Default | Notes |
  |---|---|---|
  | `audioInputMethod` | `"voiceActivity"` | drives `getHumlaInputMethod` |
  | `vadThreshold` | `35` (int, read as /100) | |
  | `talkKey` | `-1` (unset) | |
  | `hotCorner` | `"none"` | + derived gravity |
  | `hidePtt` | `false` (shown = !hide) | |
  | `togglePtt` | `false` | |
  | `input_quality` | `"48000"` (string, parsed) | misnamed: sample rate |
  | `input_bitrate` | `40000` | |
  | `inputVolume` | `100` (read as /100) | |
  | `chatNotify`, `allowTalkBroadcast` | `true` | |
  | `notification_style` | `"bigtext"` | |
  | `mediaKeyMute`, `mediaKeyMuteTts` | `true` | |
  | `useTts` | `true` | |
  | `ttsEngine` | `null` (= system default) | read with null fallback |
  | `shortTtsMessages` | `true` | |
  | `theme` | `"forceBlack"` | read by `MumlaApplication`; drives OLED default |
  | `language` | `"system"` | read by `MumlaApplication`; no `Settings` accessor |
  | `pttButtonHeight` | `50` (clamp 40–400) | |
  | `certificateId` | `-1` (none) | |
  | `defaultUsername` | `"Mumla_User"` | |
  | `forceTcp`, `muted`, `deafened` | `false` | |
  | `firstRun` | `true` | |
  | `load_images` | `false` | |
  | `audio_per_packet` | `"2"` (string, parsed) | |
  | `half_duplex`, `handset_mode`, `bluetooth_headset`, `ptt_sound` | `false` | |
  | `adaptive_leveler`, `stay_awake` | `true` | |
  | `show_user_count`, `startUpInPinnedMode`, `overlay_shown` | `false` | |
  | `overlay_placement` | `"floating"` | |
  | `overlay_hud_pos_x` / `_y` | caller-provided default | no fixed default |
  | `newsShownVersions` | `{}` (empty set) | |

  Scale/parse rules: `vadThreshold`/`inputVolume` /100 on read; `input_bitrate` XML default 40 ×1000; `pttButtonHeight` XML default 5 ×10 (clamp 40–400); `input_quality`/`audio_per_packet` string-parsed ints.
- **`ConfigDelta`:** replaces the pref→bundle→effect double dispatch
  (`MumlaService.onSharedPreferenceChanged` :774 +
  `HumlaService.configureExtras` :1598 collapse into `apply(ConfigDelta)`;
  the stringly `EXTRAS_*` bundle dies. Round-trip checklist — every key
  must survive (server, certificate+password, detection_threshold,
  amplitude_boost, transmit_mode, input_quality, force_tcp, client_name,
  access_tokens, audio_stream, frames_per_packet, trust_store+password+
  format, half_duplex, local_mute_history, local_ignore_history,
  adaptive_leveler, bluetooth_sco; deprecated input_rate/audio_source
  stay ignored as today. Side-effect-only prefs that never touch the bundle get delta keys with appliers too: theme (`applyTheme`), overlay shown/placement, TTS engine/short-messages (`initTts`), PTT sound flag, talk-broadcast register/unregister, hot-corner gravity, handset proximity sensor; deprecated-unwritten `EXTRAS_AUTO_RECONNECT`,
  `EXTRAS_AUTO_RECONNECT_DELAY`, `EXTRAS_USE_OPUS` are dropped (never written by any sender).
- **Hilt modules (new Kotlin files — Kotlin plugin lands in slice 0, so
  new files are Kotlin from birth):** `DatabaseModule` (provides
  `Room.databaseBuilder(@ApplicationContext context, MumlaRoomDatabase::class.java, "mumble.db").build()`, all 7 DAOs,
  and the `MumlaDatabase` binding), `SettingsModule` (provides
  `DataStore<Preferences>` (file `"settings"`) + `SettingsRepository`; `@ApplicationContext`
  needs no module). No `ServiceModule` yet — nothing new to provide.
  Entry points: `@HiltAndroidApp` on `MumlaApplication` in this slice;
  `@AndroidEntryPoint` lands on converted activities/fragments/services as their slices convert (services with the `LifecycleService` switch in slice 4).
  Provider interfaces (`HumlaServiceProvider`, `DatabaseProvider`) die
  later in slice 5 with the Kotlin conversion of their call sites.
`SettingsRepository` is `@Singleton class SettingsRepository @Inject constructor(private val store: DataStore<Preferences>)` with `val snapshot: Flow<PrefSnapshot>` (`PrefSnapshot` data class, one field per key — 46 accessors over 41 keys, derived accessors share their getter's key) + `suspend fun update(transform: MutablePrefs.() -> Unit)` with explicit writeback per key (scaled ints, string-parsed ints, inverted `hidePtt`, null `ttsEngine`, caller-default overlay pos, `newsShownVersions` set ops); no key may silently reset. DAOs/entities live in `se.lublin.mumla.db` (same package as today). `MIGRATION_9_10` lives in `MumlaRoomDatabase.kt`: empty `migrate()` for the six identical tables + comments-table recreate with TEXT affinity (data-preserving INSERT…SELECT, see entity row).
- **Files touched:** `db/*` (4) + 17 new app files (`ServerEntity`,
  `FavouriteEntity`, `TokenEntity`, `CommentEntity`, `LocalMuteEntity`,
  `LocalIgnoreEntity`, `CertificateEntity`, `MumlaRoomDatabase`,
  `ServerDao`, `PinnedDao`, `TokenDao`, `CommentDao`, `LocalMuteDao`,
  `LocalIgnoreDao`, `CertificateDao`, `DatabaseModule`,
  `SettingsRepository`; `ConfigDelta.kt` goes in humla `util/`),
  `Settings.java` (internals rewritten, surface kept), `MumlaService`
  pref switch, `HumlaService` `configureExtras`, `Server.java` column
  mapping (`libraries/humla/.../model/Server.java`; `@Ignore` transient `mResolvedHost`/`mResolvedPort` + SRV logic), `FakeSharedPreferences` + `MumlaDatabaseTest`/`SettingsOverlayTest`.
- **Verification:** `./scripts/check.sh`; `MumlaDatabaseTest` ported to
  in-memory Room; `FakeSharedPreferences` replaced by a DataStore test
  double; all six `Settings*Test` green against the DataStore-backed
  `Settings` (`SettingsBluetoothHeadsetTest`, `SettingsCertificateTest`,
  `SettingsInputRateTest`, `SettingsNotificationStyleTest`,
  `SettingsOverlayTest`, `SettingsPushKeyTest`); every `ConfigDelta`
  key round-trips or the slice fails.

## Slice 3 — Kotlin conversion, `:libraries:humla` (syntax first)

62 files total, 60 to convert (minus generated protobuf (`Mumble.java`, `MumbleUDP.java` —
never touched)). Batches in dependency order, ~one package per commit:

1. `exception/*` (4: `AudioException`, `AudioInitializationException`, `NotConnectedException`, `NotSynchronizedException`) + `Constants.java`
2. `util/*` (10: `HumlaCallbacks`, `HumlaDisconnectedException`, `HumlaException`, `HumlaLogger`, `HumlaNetworkListener`, `HumlaObserver`, `IHumlaObserver`, `MessageFormatter`, `MumbleURLParser`, `VoiceTargetMode`)
3. `model/*` (13 files + `Server` deferred: `Channel`, `IChannel`, `IMessage`, `IServerSettings`, `IUser`, `Message`, `ServerSettings`, `TalkState`, `User`, `WhisperTarget`, `WhisperTargetChannel`, `WhisperTargetList`, `WhisperTargetUsers`; `Server` converts in slice 5)
4. `audio/inputmode/*` (4: `ActivityInputMode`, `ContinuousInputMode`, `IInputMode`, `ToggleInputMode`)
5. `audio/*` (6: `AudioInput`, `AudioOutput`, `BluetoothScoManager` convert normally;
   `NativeAudioInputEngine`, `NativeAudioOutputEngine` convert
   **syntactically only**: `@Synchronized` preserved, native signatures
   byte-identical, no new allocations in encode/decode paths;
   `InvalidSampleRateException` converts as a plain exception — note it lives in `audio/`, not `exception/`)
6. `net/*` (14: `CryptState` (syntactically only, like the Native* engines — crypto JNI bridge frozen), `FrameValidator`, `PacketBuffer` first; `HumlaTCP`/`HumlaUDP` next; `HumlaConnection` last; remainder — `HandshakeFailure`, `HumlaCertificateGenerator`, `HumlaSSLSocketFactory`, `HumlaTCPMessageType`, `HumlaUDPMessageType`, `Permissions`, `TlsHostnameVerifier` — with their users; `HumlaNetworkThread`: convert surface only, deleted in slice 4)
7. `protocol/*` (4: `AudioHandler`, `HumlaTCPMessageListener`, `HumlaUDPMessageListener`, `ModelHandler`)
8. `IHumlaService`, `IHumlaSession` (surface frozen, syntax only)
9. `HumlaService.java` last (the god, converted but not yet restructured)

Verification: existing humla JVM tests (`AudioRoutePolicyTest`,
`HumlaServiceVoiceTargetTest`, `ModelHandlerSelfMuteTest`, …) green;
`./scripts/check.sh`.

## Slice 4 — Coroutines service core (the real debt kill)

| Today | Becomes | Test carry-over |
|---|---|---|
| `mHandler` creation + talking-state/SCO posts (`HumlaService:451` creation; `:283` talking hop; `:997` SCO-notify post) | `lifecycleScope.launch` hops | existing observer tests |
| Reconnect runnable + disarm (`:890-969`: `mReconnectRunnable`, `removeCallbacks` at `:896,940,958`, `postDelayed` at `:969`; settle/recompute disarm at `:568-569,768,834`) | `reconnectJob` + `cancel()`; backoff delays via `delay()` | **new:** `ReconnectBackoffTest` (attempts→delay: 0→1000, 1→3000, 2→6000, 3→12000, ≥4→25000 — asserts the exact `getReconnectDelay` switch) |
| Route debounce/settle `postDelayed` (`:1152-1154` debounce 300 ms; `:768-770,1322-1326` settle rechecks) | cancellable `debounceJob`/`settleJob` | `AudioRoutePolicyTest` |
| `HumlaNetworkThread` 3 executors (`HumlaNetworkThread:50-52`, incl. dead receive executor; send path `:73-83`, main hop `:87`) | deleted; one `ConnectionScope` (`SupervisorJob` + `Dispatchers.IO`), writes serialized via `Mutex` (decided over actor: single-writer FIFO is all the TCP path needs) | `Mumble15ProtocolTest`, `HumlaSSLSocketFactoryTest` kept green |
| Ping `ScheduledExecutorService` self-chain (`HumlaConnection:90,313-320,359-368,406,585`) | `while (isActive) { sendKeepalivePing(); delay(interval) }` | **new:** `PingIntervalTest` (elapsed<30 s → 5 s; else if (!forceTCP && usingUDP && (uiRemoteGood≤3 || good≤3)) → 5 s; else 10 s — asserts `getNextPingIntervalSeconds`); `AdaptiveKeepaliveTest`, `HumlaUDPPingTest` kept green |
| `HumlaUDP` queue + `mSendLock` head-drop | `Channel` with explicit capacity mirroring `calculateQueueCapacity(FPP)` (~200 ms) + recalculation on `setTargetFramesPerPacket` (same policy, incl. the `setSendQueueCapacity` override path); consumer becomes an `IO` loop | `HumlaUDPSendQueueTest`, `HumlaUDPReceiveThreadTest` kept green |
| SCO epoch + `Handler` timeout (`BluetoothScoManager`, 8 s timeout) | cancellation *is* the epoch; `withTimeoutOrNull(8000)` | `BluetoothScoRetryPolicyTest` kept green |
| `ModelHandler` `synchronized` writers | single-threaded model dispatcher; `ConcurrentHashMap`s stay (UDP-thread reads) | `ModelHandlerSelfMuteTest`, `ModelHandlerTopologyTest`, `ModelHandlerUserRemoveTest` kept green |
| `HumlaCallbacks` set fan-out | interface kept AS-IS (`ConcurrentHashMap` set + per-observer isolation already correct — decided: no `SharedFlow` migration) | observer tests |
| `configureExtras` bundle | `apply(ConfigDelta)` from slice 2 | round-trip checklist |
| Capture/render audio threads | **stay raw threads** (blocking IO); only control plane moves to the scope | native suite (`AudioOutputPacerTest`, `AudioOutputTest` kept green) |
| Service base class (`extends Service` in `HumlaService`, `MumlaService`) | `extends LifecycleService`; `lifecycleScope` is the service scope (decided) | service bind/disconnect tests |

Verification: `./scripts/check.sh` + on-device torture (reconnect
storm, SCO flap, A2DP stagger, route flip mid-call).

## Slice 5 — Kotlin conversion, `:app` (syntax first)

74 files, batches in dependency order (data-layer files already Kotlin
from slice 2; `Server` converts here to `@Entity @Parcelize` — deferred from slice 3's model batch):

1. `util/*` (11: `BitmapUtils`, `ChatLinkPolicy`, `HtmlUtils`, `HumlaServiceFragment`, `HumlaServiceProvider`, `ModelUtils`, `MumbleImageGetter`, `MumlaTrustStore`, `NotificationSanitizer`, `PinnedTlsSocketFactory`, `SsrfHostPolicy`)
2. `db` leftovers (`MumlaSQLiteDatabase` deletion, `DatabaseProvider` removal with its call sites, `DatabaseCertificate` → data class, `MumlaDatabase` interface → Kotlin, facade kept — closes the 74-file loop: 11+4+6+9+18+17+7+1+1)
3. `servers/*` (6: `FavouriteServerAdapter`, `FavouriteServerListFragment`, `ServerAdapter`, `ServerEditFragment`, `ServerInfoResponse`, `ServerInfoTask`)
4. `service/*` + `service/ipc` (9: `IChatMessage`, `IMumlaService`, `MumlaConnectionNotification`, `MumlaHotCorner`, `MumlaMessageNotification`, `MumlaOverlay`, `OverlayLayout`, `TalkBroadcastReceiver` —
   `MumlaService` itself last)
5. `channel/*` (15: `AccessTokenFragment`, `AvatarCache`, `ChannelAdapter`, `ChannelChatFragment`, `ChannelFragment`, `ChannelListAdapter`, `ChannelListFragment`, `ChannelMenu`, `ChatTargetActionModeCallback`, `ChatTargetProvider`, `OnChannelClickListener`, `OnUserClickListener`, `PermissionsPopupMenu`, `ServerInfoFragment`, `UserMenu`)
   + `channel/comment/*` (3: `AbstractCommentFragment`, `ChannelDescriptionFragment`, `UserCommentFragment`)
6. `preference/*` (17: `AboutSettingsFragment`, `AppearanceSettingsFragment`, `AudioSettingsFragment`, `AuthenticationSettingsFragment`, `CertificateExportActivity`, `CertificateGenerateActivity`, `CertificateImportActivity`, `CertificateSelectActivity`, `GeneralSettingsFragment`, `KeySelectDialogPreference`, `KeySelectPreferenceDialogFragment`, `MumlaCertificateGenerateTask`, `MumlaPreferenceFragment`, `SeekBarDialogPreference`, `SeekBarPreferenceDialogFragment`, `ServerCertificateClearActivity`, `SettingsActivity`; still `PreferenceFragment`-based — Compose comes
   in slice 7)
7. `app/*` (7: `BaseActivity`, `DialogUtils`, `DrawerAdapter`, `IStartupAction`, `MumlaApplication`, `ServerConnectTask` — `MumlaActivity` itself last)
   + root `Settings.java` (replaced by `SettingsRepository`; call sites migrate) + `drawable/CircleDrawable.java`

`MumlaService`/`IMumlaService` binder surface converts syntactically
only (frozen per plan). Procedure per file: IDE auto-converter, then reviewer pass per porting-guide §1 (no `!!`, platform types explicit, nullability reviewed). Verification: all app JVM tests (incl.
Robolectric comment-dialog tests) green; `./scripts/check.sh`.

## Slice 6 — Compose foundation (theme + leaf + goldens)

- Port `themes.xml` (`Theme.Mumla`/`Theme.Mumla.Oled`, colors, type,
  dimens) into a Compose **Material3** `MaterialTheme` via tokens —
  three schemes: light (`md_theme_light_primary #3949ab`, `md_theme_light_onPrimary #ffffff`,
  `md_theme_light_primary_darker #212b64` for pttPressed), dark (`md_theme_dark_primary #001b2b`,
  `md_theme_dark_onPrimary #1795c2`, `md_theme_dark_primary_lighter #002b45` for pttPressed,
  `md_theme_dark_primary_lighter_more #0093eb` for high-contrast buttons), OLED (`black #000000` surfaces + `overlay_pill_background #d9000000`).
  Talk-state colors (`ripple_talk_state_on`, `_disabled`, `_suppressed`, `_server_muted`, `_server_deafened`) become state colors, not ripples.
- Night theme: `values-night/themes.xml` redefines `Theme.Mumla` (11 items) — map every item into the dark scheme: `colorPrimary`, `colorOnPrimary`, `pttPressed`, `textColorPrimaryInverse`/`textColorSecondaryInverse #dddddd`, `textColorLink` override, `TweakedAlertDialog`/`TweakedMaterialAlertDialog` high-contrast primary, `dialogPreferenceStyle`, darker `cardStyle`, dark `popupOverlay`.
- Scheme selection follows `PREF_THEME` (default `"forceBlack"` → OLED theme + `MODE_NIGHT_YES`; `forceDark` → dark non-OLED + YES; `forceLight` → NO; `system` → FOLLOW_SYSTEM; unknown → rewritten to `forceBlack`), applied per-Activity in the `BaseActivity` replacement before `super.onCreate` (manifest stays `@style/Theme.Mumla`); the DataStore `theme` key feeds it; goldens prioritize OLED (the default).
- Paparazzi goldens infrastructure (per slice 0 fork decision).
- First screen: `ServerInfoFragment` → `ServerInfo` route + `ServerInfoViewModel`
  (its poll executor becomes a `StateFlow` + `collectAsState`); first goldens recorded (route `"server_info"`; `@HiltViewModel class ServerInfoViewModel @Inject constructor(...) : ViewModel() { val uiState: StateFlow<ServerInfoUiState> }`, sealed `Loading/Content/Error`):
  9 subjects (loading / content / error × light / dark / OLED). New files:
  `theme/Theme.kt`, `theme/Tokens.kt`, `ServerInfoViewModel.kt`, `ServerInfoScreen.kt` + route entry.
- Verification: all 9 goldens pass; old-vs-new screenshots compared
  by eye on the Pixel 4a; `./scripts/check.sh`. Paparazzi runs with fixed device/theme/locale config; golden churn is triaged in the slice commit.

## Slice 7 — Compose screens (leaf-to-root, dialogs last)

Per-screen table (source → route + ViewModel → golden subjects → gate):

| Screen | Sources | ViewModel | Goldens | Gate |
|---|---|---|---|---|
| Favourites | `FavouriteServerListFragment` + `FavouriteServerAdapter` | `FavouritesViewModel` | list / empty × 3 schemes | — |
| ServerEdit | `ServerEditFragment` + `ServerAdapter` ping pool → coroutine | `ServerEditViewModel` | form / pinging × 3 | — |
| ChannelTree | `ChannelFragment` + `ChannelListFragment` + `ChannelListAdapter` + `ChannelAdapter` + `AvatarCache` + click listeners | `ChannelTreeViewModel` | tree (collapsed/expanded/talking) × 3 | **`ChannelFragmentDensityTest` unmodified** |
| ChannelChat | `ChannelChatFragment` + `ChatTarget*` (+ `MumbleImageGetter` pipeline stays) | `ChannelChatViewModel` | chat / image-send × 3 | — |
| Comments | `Abstract`/`User`/`ChannelDescription` fragments | `CommentDialogs` | each dialog × 3 | teardown tests green |
| AccessToken | `AccessTokenFragment` | `AccessTokenViewModel` | dialog × 3 | — |
| Settings | 5 header fragments + `MumlaPreferenceFragment` + `SettingsActivity` + `KeySelect`/`SeekBar` dialogs + cert management (`CertificateExport/Generate/Import/SelectActivity`, `ServerCertificateClearActivity`, `MumlaCertificateGenerateTask` — owned by the Authentication route) | DataStore-backed directly | each screen × 3 | — |
| Drawer | `DrawerAdapter` (items server/pinned/info/tokens/favourites/settings) | route map | open / closed × 3 | — |
| Shell | `MumlaActivity` + `BaseActivity` + news dialog (`DialogUtils`) | `ConnectFlow`, `PermissionFlow` state holders | — | full torture matrix |
| DialogHost | all 7 fields (connecting/error/switch/permission/back-press/cert[3 TLS variants]/first-run) | dialog state | each dialog × 3 | dismissal semantics intact |

`ChannelMenu`/`UserMenu`/`PermissionsPopupMenu` → `DropdownMenu`;
`ChatTargetActionModeCallback` → contextual `TopAppBar`. `ServerConnectTask`/
`ServerInfoTask`/`MumlaCertificateGenerateTask` → coroutines (slice-4 pattern).

**Stay Views (explicit non-goals):** `MumlaOverlay`, `MumlaHotCorner`,
`OverlayLayout` (WindowManager system windows, not activity content),
`MumlaConnectionNotification`, `MumlaMessageNotification`
(notification/RemoteViews APIs), `TalkBroadcastReceiver` (system broadcast;
wired to a flow). `CircleDrawable` → Canvas equivalent in Compose.

`MumlaActivity` shell + `DialogHost` convert last → delete Fragment navigation
(`MumlaActivity.loadDrawerFragment`, `DrawerAdapter` routes, back-stack transactions → NavHost route map);
drawer becomes `ModalNavigationDrawer`. Interop during slices 6–7: converted screens mount as NavHost routes; not-yet-converted screens stay mounted as Fragment-wrapped routes — the only sanctioned shim, and it dies with Fragment navigation (converted code itself carries no shims). Route map (`NavHost` in content_frame): `favourites`, `server_edit/{id}`, `server_info`, `channel_tree`, `channel_chat/{id}`, `comments/{session}`, `access_tokens`, `settings/general|audio|appearance|auth|about`, drawer + `DialogHost` at shell. Torture checklist (run per UI slice, full pass at slice 7): rotate mid-each-dialog (all 7), switch server mid-connect, deny each runtime permission (mic/BT/notifications), back-press while connected and while connecting, process-kill mid-call then reconnect, A2DP attach mid-call, overlay drag + rotate. Final gate: full torture matrix
(rotate mid-dialog, switch mid-connect, deny mid-flow) on the Pixel 4a
plus one modern device, `./scripts/check.sh`, and a landing checklist
(merge requested explicitly — never autonomous).

## Verification matrix

| Slice | `check.sh` | Unit suites | Device/golden gates |
|---|---|---|---|
| 0 scaffold | green baseline | — | — |
| 1 minSdk | green | all existing | gate-grep clean, 2-device boot/voice |
| 2 data | green | Room in-memory DB tests, 6 Settings*Test, key-defaults table | ConfigDelta round-trip (17 keys + side-effects) |
| 3 Kotlin humla | green | humla JVM suite | — |
| 4 coroutines | green | route/reconnect/ping tests | torture: reconnect/SCO/A2DP |
| 5 Kotlin app | green | app JVM + Robolectric | — |
| 6 foundation | green | 9 goldens (3 states × 3 schemes) | eye-compare on 4a, OLED-first |
| 7 screens | green | per-screen goldens, density test | full torture matrix, landing checklist |