# Step 6 — Container, storage, and features

Part of the [migration](../migration.md). **Status: done.**
This is the step's plan as it was executed, kept as a record;
[`PROGRESS.md`](../PROGRESS.md) summarises what landed, and the living
specs hold the current design.

`MumlaService` stays the bound service in this step, and `HumlaService`
stays its base class. The step moves construction into `AppContainer` and
the library's `HumlaRuntime`, gives storage a non-blocking current-value
API, and moves each `MumlaService` responsibility into a feature.
`MumlaService` delegates the matching `IMumlaService` calls to the feature
until step 7 deletes it. Binder calls stay synchronous: reads return the
feature's current `StateFlow` value (for example `getMessageLog` returns
the chat log's `value`), and writes launch into the feature's scope.

**Lifetime rule.** Everything the container builds lives as long as the
process and is never closed (see [app](../app.md#appcontainer)). Today
`HumlaService.onDestroy` closes `RouteControl`, `VoiceEngine`, and
`PolicyCollector` and unregisters the network callback. From commit 5 on
it does none of that. The service is destroyed and re-created within one
process (disconnect, leave the app, come back), and a second instance
handed closed objects has no voice. This is the same class of failure that
ended the first step-5a attempt. `onDestroy` keeps `client.disconnect()`,
which ends a connection exactly as today, releases the service's own wake
lock and Wi-Fi lock, and unregisters every observer that was registered
through its binder, so a dead service's screens never stay in the
container's `HumlaCallbacks`.

Commits, in order:

1. **Remove Hilt.** Nothing uses the Hilt graph: there is no
   `@AndroidEntryPoint`, and the only `@Inject` is `SettingsRepository`'s
   constructor. Delete `@HiltAndroidApp`, that `@Inject`, `DatabaseModule`,
   `SettingsModule`, the Hilt plugin in both modules and in the root
   `build.gradle`, the Hilt and `androidx.hilt` dependencies, their
   `gradle/libs.versions.toml` entries (including the unused
   `hilt-navigation-compose` version and library and the Hilt notes beside
   them), and kapt in `:libraries:humla`, where Hilt is its only
   processor. `:app` keeps kapt for the Room compiler until the KSP TODO
   in `gradle/libs.versions.toml` lands. Doing this first means the
   container never shares construction with a second provider.
2. **Transmit mode and talk state.** `InputModes` keeps its own transmit
   mode, and nothing feeds it (nothing calls `applyAudioDelta`). So on
   `master`, `getTransmitMode` returns voice activity whatever the
   setting. That silences the PTT sounds and makes the talk broadcast
   receiver reject every request. `HumlaService`'s `voiceSettingsFlow`
   already gets the real mode through `VoiceSettingsMapper` in `apply`,
   so this fix needs nothing from the container and lands on its own,
   where a regression in beeps or broadcasts bisects to it alone. The
   commit:
   - makes `getTransmitMode` map `voiceSettingsFlow.value.inputMode` back
     to its `Constants.TRANSMIT_*` value;
   - makes `SessionState.local.pttPressed` the only record of whether the
     user is talking. `isTalking` reads it (`false` without a session),
     `setTalkingState` calls `session.setPtt`, and a new
     `session.togglePtt()` intent flips it inside `update {}` for toggle
     mode (see [state](../state.md#user-intents)). A toggle-mode press no
     longer survives a reconnect; today the button stays lit while the
     new session's gate is closed;
   - deletes `InputModes`, `InputModesValidationTest`, and the
     `audio/inputmode` package. `VoiceSettingsMapper` already applies
     every rule `validateAudioDelta` applies, so `apply` maps the next
     `VoiceSettings` **before** it merges anything, and a rejected delta
     still commits nothing, as today. The bitrate and amplitude-boost
     bounds (`MIN_INPUT_BITRATE` and the others) move from `InputModes.kt`
     into `VoiceSettingsMapper.kt`, and its KDoc, which names `InputModes`
     and `ActivityInputMode`, is reworded. Any `InputModesValidationTest`
     case that `VoiceSettingsMapperTest` does not already cover moves
     there, and an `apply` test pins that a rejected delta leaves the
     pending connect target and `voiceSettingsFlow` unchanged.

   `InputModes.kt` (2) and `ToggleInputMode.kt` (1) leave the allowlist
   here.
3. **`AppContainer` and storage.** `MumlaApplication.onCreate` builds the
   container through an overridable `createContainer()` (the test seam) and
   exposes it as `container`. The container constructs the settings
   `DataStore`, `SettingsRepository`, and `MumlaRoomDatabase` once. The
   `AppDataStore.get` and `MumlaRoomDatabase.getInstance` singletons and
   the `Settings.getInstance` factory are deleted, and every call site
   reads the container.
   - Settings get the current-value API from [app](../app.md#settings): one
     in-memory `Preferences` with its typed `PrefSnapshot`, seeded by the
     one blocking startup read, changed by a non-blocking `update`, and
     written back whole by one writer coroutine. The `Settings` facade's
     getters read the snapshot and its setters call `update`.
     `DataStorePreferenceDataStore` reads the raw `Preferences` with the
     widget's default and writes through `update`. After startup, no
     settings read or write blocks.
   - `MumlaApplication` collects theme and language from the flow instead
     of registering a listener. The listener bridge stays, with its
     baseline taken from the in-memory value, for the screens that still
     register (`BaseActivity`, `ChannelFragment`, `ChannelListFragment`)
     until step 7.
   - Tests that wrote the store after building a repository write through
     `update` instead; writing the store first and then building the
     repository still works, because the startup read loads it.
   - The database keeps its synchronous `runBlocking` bridge for screens
     until step 7 and gains `suspend` methods, which the service, the
     features, and `LocalFactsStore` use from here on. Comments that name
     `runBlocking` without calling it are reworded, so the tripwire counts
     only real calls.
4. **Local facts.** `LocalFactsStore` gets its real implementation in
   `:app`, over the database (mute and ignore lists per server ID) and
   settings (self-mute and self-deafen). It skips writes, and loads empty
   lists, for a server that is not saved (ID `-1`), as the adapter's
   `Server.isSaved()` guard does today. Until commit 5, `HumlaService` gets it
   from a `localFactsStore()` hook that `MumlaService` implements from the
   container. The same commit removes the other writers, so there is never
   zero or two: `ChannelListAdapter` stops writing local mute to the
   database (and its writer `Thread` goes with it); `MumlaService` stops
   writing self-mute and deafen to settings on each self `UserState` and
   stops restoring them after sync (the session does both, see
   [state](../state.md#self-mute-and-deafen)); and
   `ConfigDelta.localMuteHistory`, `ConfigDelta.localIgnoreHistory`, and
   `HumlaService`'s pending-snapshot store are deleted.
5. **`HumlaRuntime`.** `RouteControl`, `AudioPlatform`, and `VoiceEngine`'s
   other collaborators are `internal` to `:libraries:humla`, and they stay
   that way. `PolicyCollector` is public today, with no user outside the
   library and its tests; this commit makes it `internal` too. A public
   `HumlaRuntime.create` in the library builds them, in the order and with
   the inputs [app](../app.md#humlaruntime) gives, plus the interim pieces
   they reference: `HumlaCallbacks`, the main-thread poster, the
   session-state mirror, and the observer-bridge collectors that forward
   route, SCO, and talk hints into `HumlaCallbacks`. The container calls
   it once and hands the result to `HumlaService` through an abstract
   `runtime()` that `MumlaService` implements. `HumlaService` constructs
   and closes none of it (lifetime rule), and the commit-4 hook folds into
   the runtime's inputs. `HumlaService` keeps the per-instance work that
   [app](../app.md#appcontainer) lists for the interim hand-off, including the
   hooks `MumlaService` still overrides: its `client.state` collector
   still calls `onConnectionSynchronized` and `onConnectionDisconnected`,
   and its own collector of the runtime's `routeState` calls
   `onAudioRouteChanged`, so the PTT sound pool and the proximity lock keep
   following route changes until their features land. The same commit:
   - creates `:libraries:humla-testing` (added to `settings.gradle` and to
     the module list in `AGENTS.md`), moves the shared fakes into it (see
     [testing](../testing.md#app-layer-tests)), and adds
     `AppContainerLifecycleTest` with the identity, route, observer, and
     connect assertions from the done-when below. It is the executable
     check of the lifetime rule, so it lands with the commit that starts
     the rule;
   - derives `VoiceSettings` and `RoutePreferences` in the container from
     the settings snapshot, so `HumlaService.apply` loses its voice and
     route branches (including the `setTargetBitrate` and
     `setTargetFramesPerPacket` calls) and `ConfigDelta` loses those
     fields. Until step 7, `apply` keeps only the connect-target half. A
     snapshot the mapper rejects (any field `validateAudioDelta` rejects
     today) keeps the previous `VoiceSettings` and logs a warning, as
     `apply` keeps the running configuration today. `RoutePreferences`
     carries Bluetooth SCO only when the setting is on **and** the
     `BLUETOOTH_CONNECT` grant is held, so a value the permission guard is
     about to revert never reaches `RouteControl`;
   - moves `voiceSettingsFlow` out of `HumlaService`, so `getTransmitMode`
     reads the runtime's interim `voiceSettings` member instead (see
     [app](../app.md#humlaruntime)). Its only callers are the PTT-sound
     check in `MumlaService` and `TalkBroadcastReceiver`. The features
     that take them over read the input method setting directly, and the
     later of those two feature commits deletes `getTransmitMode` from
     `IHumlaSession` and `HumlaService` and `voiceSettings` from the
     runtime.
6. **Client-level events.** `HumlaClient.events` replaces `Session.events`
   (see [state](../state.md#events)): one application-lifetime
   `SharedFlow<SessionEventRecord>`, where each record pairs the event with
   the emitting session's state right after the transition that produced
   it. Features subscribe once, when the container starts them, so a new
   session cannot emit before its collectors exist, and filters read the
   state the event came from instead of a mirror that may lag. The interim
   chat-log adapter switches to it.
7. **Features, one per commit** (see [app](../app.md#features)), chat log
   first.
   - **Chat-log goldens.** Before the chat-log commit deletes
     `StateEventLogger`, it records golden strings from it in `:app`
     (Robolectric, where the merged resources load) for every
     `SessionEvent` case: self, other, and actor variants, and both sides
     of the move and recording filters. The feature must reproduce them.
     The `chat_notify_*`, `deny_reason_*`, and `audio_max_bandwidth`
     strings (with their translations) move from `:libraries:humla` to
     `:app` in that commit, and the deny formatter that the client's
     interim observer bridge uses moves with them. The commit also adds the
     lifecycle test's feature-events assertion.
   - **Chat log interim edges.** Until step 7 the chat log keeps two
     inputs from today's paths: received text arrives through the
     observer bridge's `onMessageLogged` (the container registers the
     feature as an observer for that callback only), and sent text through
     `MumlaService`'s send delegations. Every info, warning, and error line
     it appends is also forwarded to `HumlaCallbacks.onLogInfo`,
     `onLogWarning`, or `onLogError`, so an open `ChannelChatFragment`
     keeps updating live as it does today.
   - **Tests move with their code.** `MumlaServiceChatLogTest`,
     `MumlaServiceTalkKeyTest`, `TalkBroadcastReceiverTest`,
     `MumlaOverlayMarginsTest`, and the `MumlaConnectionNotification`
     tests become tests of the features that take that code over, in the
     same commits. `HumlaService` tests that reach into its fields build a
     test runtime instead.
   - **Hand-off flows** (`talkKeys`, `speech`, `appLog`; see
     [app](../app.md#appcontainer)) join the container with the first feature
     that emits into or collects each one.
   - **Interim hooks go with their last user.** The later of the PTT-sound
     and proximity feature commits deletes `onAudioRouteChanged` and its
     route collector from `HumlaService`.
   - The connection notification feature exposes its notification.
     `HumlaService` posts it with `startForeground` and removes it on
     `Idle` or `Failed` (see [app](../app.md#appcontainer)) until step 7
     hands that to `ConnectionService`; the same commit adds the lifecycle
     test's notification assertion. The other features follow in any
     order.

What step 6 leaves in `MumlaService` for step 7: `onCreate`, `onDestroy`,
`onBind`, `onStartCommand`, the `IMumlaService` delegations, the connect
half of `ConfigDelta` (`buildConnectDelta`, read at connect time), and the
error-shown flag (`isErrorShown`, `markErrorShown`, reset when `client.state`
becomes `Connecting`), which step 7 moves into `MainViewModel`.

**Done when:**

- **Executable:** `AppContainerLifecycleTest` (Robolectric, `:app`) builds
  the real container through `createContainer()` with fakes only at the
  runtime's edges (the native engine factory and the transport factory,
  see [testing](../testing.md#app-layer-tests)). It drives `MumlaService`
  through create, destroy, create, and destroy, and asserts that:
  - the second instance holds the same `HumlaClient` and `HumlaRuntime`
    as the first;
  - after the first destroy, the route still answers `SessionStarted` and
    `SessionEnded` with state changes;
  - an observer registered through the first instance is gone from
    `HumlaCallbacks` after its destroy, and one registered through the
    second instance receives a callback;
  - a connect through the second instance reaches `Connected` on the fake
    transport, and the features see its events;
  - after that connection ends, the service is out of the foreground and
    the connection notification is gone, though the service stays bound.
- **Executable:** the chat-log goldens recorded from `StateEventLogger`
  pass against the chat log feature.
- Every row of the [feature table](../app.md#features) has a feature class
  in `se.lublin.mumla.feature`, and `MumlaService` holds only the residue
  listed above: no field of type `TextToSpeech`, `SoundPool`,
  `MumlaOverlay`, `MumlaHotCorner`, `MumlaConnectionNotification`,
  `MumlaMessageNotification`, `TalkBroadcastReceiver`, or `WakeLock`, and
  no chat log.
- Invariants and their detectors:
  - **One instance of each application-scoped object:** the identity
    assertions above, and the concurrency tripwire, which fails if a
    `synchronized` or `@Volatile` singleton returns to the storage files
    after they leave the allowlist.
  - **One writer for local facts:** a `LocalFactsStore` test (one database
    write per mute or ignore toggle, none for an unsaved server, one
    settings write per self-mute intent), and the
    [local-facts writer check](../enforcement.md#local-facts-writer).
  - **One owner of talk state:** a `Session` test that `togglePtt`
    flips `pttPressed` inside `update {}`, `InputModes` gone, and a talk
    keys feature test that hot-corner gestures arriving on `talkKeys`
    toggle in toggle mode, as today.
  - **Transmit mode follows the setting:** a `HumlaService` test that
    `getTransmitMode` returns push-to-talk after a push-to-talk delta
    (commit 2), then the PTT-sound and talk-broadcast feature tests in
    push-to-talk and voice-activity modes.
  - **Features do not call each other:** the
    [feature isolation check](../enforcement.md#feature-isolation).
  - **Event collectors never stall the session:** for each feature that
    collects `client.events`, a test emits more events than the buffer
    holds (300) while the feature's slow dependency (TTS engine,
    notification manager, database) is blocked, and asserts that the
    emitter never suspends.
- **Allowlist:** these leave the list: `AppDataStore.kt` (2),
  `DataStorePreferenceDataStore.kt` (3), `MumlaService.kt` (12),
  `MumlaConnectionNotification.kt` (1), `MumlaMessageNotification.kt` (1),
  `HumlaService.kt` (1), `ChannelListAdapter.kt` (1), `InputModes.kt` (2),
  and `ToggleInputMode.kt` (1). `SettingsRepository.kt` drops from 8 to 3
  (the listener bridge) and `MumlaRoomDatabase.kt` from 5 to 2 (the `io`
  bridge and its import). `AppContainer.kt` joins as a boundary file with 2
  (the startup read and its `runBlocking` import). That takes the list from
  23 / 74 to 15 / 44.

## Kill criterion

**Step 6:** if a feature cannot be built without calling another feature,
holding a lock, or blocking an event collector, the feature table is
wrong. Revise [app](../app.md#features) on `master` before continuing.
