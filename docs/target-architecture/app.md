# App layer

Part of the [target architecture](README.md). Specifies the composition
root, the foreground service, the features that replace `MumlaService`'s
responsibilities, the screens' ViewModels, and how settings reach each
component.

## Port rule

The app layer is mostly a move, not a rewrite. **Any behavior of today's
app that this file does not name moves unchanged with the class that owns
it**: a feature takes the logic from the `MumlaService` methods it
replaces, a ViewModel takes the connection-related logic from its screen,
and everything UI-only stays in the activity or fragment. That includes,
for example, the chat image picker and resize flow, outgoing link markup,
HTML sanitizing (`MumbleImageGetter`, `ChatLinkPolicy`), avatar fetching
and `AvatarCache`, the kick and ban reason dialogs, and menu actions. The
tables below name what changes owner or shape; anything they leave out
keeps its current owner and behavior.

## `AppContainer`

Created in `MumlaApplication.onCreate`, through an overridable
`createContainer()` that tests replace, and reachable from any component as
`(application as MumlaApplication).container`. Constructs every
application-scoped object by hand, in this order:

1. `appScope`: `SupervisorJob` + `Dispatchers.Default` + a
   `CoroutineExceptionHandler` that throws in debug builds and logs in
   release builds, the same split as `checkNotMainThread()`. The
   main-thread poster is `ContextCompat.getMainExecutor`, so no `Handler`.
2. Storage: the settings `DataStore` (with today's
   `SharedPreferencesMigration`), `SettingsRepository`, and
   `MumlaRoomDatabase`, each built once here; their singleton accessors
   are gone. The repository's first value is loaded with the app's one
   **blocking startup read** (`runBlocking { store.data.first() }`, which
   also runs the migration), because the theme and locale must be applied
   before the first activity draws. Today's first `Settings` read already
   blocks there, so startup costs the same. `AppContainer.kt` is a
   boundary file for this one call and its import (see
   [enforcement](enforcement.md#concurrency-tripwire)).
3. Settings-derived inputs for the runtime, each a `StateFlow` (`stateIn`
   on `appScope`, seeded from the snapshot read in step 2):
   - `VoiceSettings`, mapped with `VoiceSettingsMapper`. A snapshot the
     mapper rejects keeps the previous value and logs a warning.
   - `RoutePreferences`. Bluetooth SCO is on only when the setting is on
     and the `BLUETOOTH_CONNECT` grant is held. Android kills the process
     when a grant is revoked, so at runtime a grant only appears, and the
     permission guard has already turned the setting off by then.
4. Platform signals and hand-off flows:
   - `configuration`, a `StateFlow<Configuration>` that
     `MumlaApplication.onConfigurationChanged` updates (the overlay and hot
     corner reposition on rotation, as `MumlaService.onConfigurationChanged`
     does today).
   - The hand-off flows, which carry one feature's output to another
     without a call (see [features](#features)): `talkKeys` (`Down`, `Up`,
     and `Cancel` from the screens' talk keys and the hot corner, for the
     talk keys feature), `speech` (lines another feature wants spoken, for
     TTS), and `appLog` (info and warning lines from features, for the
     chat log). Each is a `MutableSharedFlow` with a buffer of 64, emitted
     with `tryEmit` on the main thread and collected on the main thread
     without slow work inline. A failed `tryEmit` follows the `appScope`
     rule: it throws in debug builds and logs in release builds.
5. `LocalFactsStore` over the database and settings (local mute and
   ignore lists, persisted self-mute and self-deafen).
6. The deny formatter, from the `deny_reason_*` strings.
7. `HumlaRuntime`, through `HumlaRuntime.create` (below), given the
   application context, `appScope`, the inputs from steps 3, 5, and 6,
   and the edges (production edges unless a test passes fakes).
8. Features (below), each started with `appScope`.

**Lifetime.** Everything the container builds lives as long as the
process and is **never closed**. Services, screens, and features borrow
these objects; none of them may close one, because the next service or
screen in the same process gets the same instance. Idle, this costs an
idle route thread and voice control dispatcher, and two registered platform
callbacks (audio devices, default network). Heavy resources wait for use:
the native engine is created on the first voice start and kept, as
`VoiceEngine` already keeps it across reconnects, and features create
theirs on the first connect (see [features](#features)).

**Interim hand-off (step 6 only).** `HumlaService` still exists in step 6
and gets the runtime from an abstract `runtime()` that `MumlaService`
implements from the container. `HumlaService` keeps only per-instance
work:

- The wake lock and Wi-Fi lock, with today's windows.
- The connection notification, from an abstract `foregroundNotification()`
  that `MumlaService` implements from the feature. `HumlaService` calls
  `startForeground` with its current value when it starts and posts each
  update. When `client.state` becomes `Idle` or `Failed`, it calls
  `stopForeground(STOP_FOREGROUND_REMOVE)`, as
  `MumlaConnectionNotification.hide` does today, because a bound service
  outlives the connection.
- The binder delegations, including registering observers on the runtime's
  `HumlaCallbacks` and unregistering them on destroy.
- The connect half of `ConfigDelta`.
- The hooks `MumlaService` overrides until the features that replace them
  land. Its `client.state` collector, which also drives the locks and the
  connection-state observer callbacks, calls `onConnectionSynchronized` and
  `onConnectionDisconnected`. A collector of the runtime's `routeState`
  calls `onAudioRouteChanged`, which rebuilds the PTT sound pool for the
  new stream and rechecks the proximity lock; the runtime's own route
  collector only forwards hints into `HumlaCallbacks` and cannot reach a
  service override. The later of the PTT-sound and proximity feature
  commits deletes this hook.
- The `StateEventLogger` chat-log adapter, which logs through the service
  into `HumlaCallbacks` until the chat log feature replaces it.

Screens get dependencies through one `ViewModelProvider.Factory` that reads
the container from `application`. There is no DI framework: Hilt is removed
from both modules in [step 6](migration.md#step-6--container-storage-and-features).
`:app` keeps kapt only for the Room compiler, until a stable KSP for the
project's Kotlin version replaces it.

### `HumlaRuntime`

The library's public assembly point. `RouteControl`, `AudioPlatform`,
`ScoLink`, and `RouteThread` stay `internal` to `:libraries:humla`, and
`PolicyCollector` becomes `internal` in step 6 (it is public today, with no
user outside the library). `HumlaRuntime.create` builds them, so `:app`
never names them:

```kotlin
class HumlaRuntime private constructor(/* … */) {
    data class Inputs(
        val voiceSettings: StateFlow<VoiceSettings>,
        val routePreferences: StateFlow<RoutePreferences>,
        val localFacts: LocalFactsStore,
        val denyFormatter: (SessionEvent.PermissionDenied) -> String,  // interim, until step 7
        val clock: Clock,
    )
    data class Edges(
        val engineFactory: (NativeVoiceEngine.Listener) -> EngineNative,
        val transports: TransportFactory,
    ) { companion object { fun production(): Edges } }

    companion object {
        fun create(context: Context, scope: CoroutineScope, inputs: Inputs, edges: Edges): HumlaRuntime
    }

    val client: HumlaClient
    val routeState: StateFlow<RouteState>
    val routeEvents: SharedFlow<RouteEvent>
    fun retrySco()
    val voiceEvents: SharedFlow<VoiceEvent>
    val talkActivity: StateFlow<TalkActivity>
    val sessionState: StateFlow<SessionState>   // the mirror; empty without a session
    val callbacks: HumlaCallbacks               // interim, until step 8
    val voiceSettings: StateFlow<VoiceSettings> // interim: the input, for getTransmitMode
}
```

`create` builds, in order: the `TalkActivityPublisher`; `RouteControl`
through `RouteControl.create` with `AndroidAudioPlatform` and a factory
that builds `BluetoothScoManager` on the application context (see
[route](route.md#api)), then calls its `onInit` and `start`; `VoiceEngine`
on its own single-thread control dispatcher, with a tunnel sink that reads
the client's current session lazily (the client is built after it, and the
sink runs only while a session exists); the `NetworkMonitor` over
`ConnectivityManager`, registered once; `HumlaCallbacks`; `HumlaClient`
with the interim observer bridge (the callbacks, the main-thread poster,
the deny formatter); the session-state mirror; the policy collector (see
[voice](voice.md#voicepolicy)) with its pushes of `VoicePolicy` and
`VoiceSettings` into `VoiceEngine`; and the observer-bridge collectors
that forward route, SCO, and talk hints into the callbacks. The runtime's
own collectors run in `scope` under the same exception rule as `appScope`.

`voiceSettings` echoes the input so `HumlaService.getTransmitMode` can
read it. It goes in step 6, with `getTransmitMode`, once the PTT-sound and
talk-broadcast features read the input method setting directly (see
[migration](migration.md#step-6--container-storage-and-features)). Step 7
removes the other interim members with `IHumlaObserver`. The runtime itself
stays.

## `ConnectionService`

A foreground `Service` of type `microphone` with no logic of its own.

- **Started by the UI path that calls `connect`**, before calling it, while
  the app is in the foreground. Android 14 and later generally refuse to
  start a microphone foreground service from the background.
- **Stays in the foreground** while `ClientState` is `Connecting`,
  `Connected`, or `Reconnecting`, including every reconnect cycle. It stops
  itself when the state becomes `Idle` or `Failed`. Today's `HumlaService`
  lingers after a kick or ban (`CONNECTION_LOST` without reconnect) so the
  bound UI can show the error; in the target the error lives in the
  application-scoped `ClientState.Failed`, so screens show it without a
  service, and the service can stop.
- **Background connects** (talk broadcast, notification actions) are
  accepted only while the service is already in the foreground; otherwise
  they are ignored and logged.
- Holds the partial wake lock and the Wi-Fi lock with today's windows,
  moved from `HumlaService` unchanged: a 15 s grace on connect
  (`WAKELOCK_GRACE_MS`), held while connected, and the backoff delay plus
  15 s while reconnecting. Both are
  [keep-alive shields](voice.md#keep-alive-shields); do not narrow these
  windows to save power.
- Posts the connection notification built by the connection notification
  feature.
- Nothing binds to it. `onBind` returns `null`.

## Features

Each feature is a small class in `se.lublin.mumla.feature`, one per file,
with a `start(scope)` method that collects the flows it needs. The
container builds and starts every feature once, at process start.

- **Features do not call each other.** What two features share comes from
  the container (a flow, the settings repository, the client). Where
  today's code has one responsibility call another, the sender emits into
  a [hand-off flow](#appcontainer) and the receiver collects it: the hot
  corner's gestures reach the talk keys feature through `talkKeys`, the
  media-key mute confirmation reaches TTS through `speech`, and TTS engine
  failures reach the chat log through `appLog`. The
  [feature isolation check](enforcement.md#feature-isolation) enforces it.
- **Event collectors never block.** Features collect `client.events` and
  hand anything slow (TTS synthesis, database writes, notification
  posting) to their own scope, as [state](state.md#events) requires, so a
  slow feature cannot stall the session's message coroutine.
- **Features take flows and functions, not owners.** A feature's
  constructor takes the flows it collects and the intent functions it
  calls (`setPtt`, `setSelfMuteDeaf`, `disconnect`), never the container
  or `HumlaClient` itself, so its tests drive it with plain flows (see
  [testing](testing.md#app-layer-tests)). The one exception is interim:
  until step 7 the chat log also takes the runtime's `HumlaCallbacks`,
  registering on it for received text and forwarding its lines into it
  for the open chat screen (see
  [migration](migration.md#step-6--container-storage-and-features)).
- **Features run on the main thread.** Each feature collects on
  `Dispatchers.Main.immediate` in its own child `SupervisorJob`, so one
  failing feature stops alone and the `appScope` handler reports it. That
  is where all of `MumlaService` runs today, and windows, toasts,
  `SoundPool`, and `MediaSession` need it.
- **Heavy resources wait for the first connect.** `TextToSpeech` and
  `SoundPool` are created the first time `client.state` leaves `Idle`,
  then kept for the process. The media session, overlay windows, and the
  proximity wake lock follow today's show and hide rules. A process that
  never connects creates none of them.
- **Features own no Android component.** A feature uses the application
  context (for example for overlay windows, `TextToSpeech`, `SoundPool`,
  and toasts) and never holds a service or an activity. Anything that needs
  an activity, such as a dialog, belongs to a screen. Overlay windows
  inflate from a `ContextThemeWrapper` over the application context,
  themed by `MumlaApplication.applyTheme`, as `MumlaService.onCreate`
  themes its own context for the overlay views today.

| Feature | Replaces (today) | Collects | Does |
|---|---|---|---|
| Connection notification and media session | `MumlaConnectionNotification` (including its `MediaSession`, custom actions, and headset-hook and claimed-key dispatch), `updateConnectedNotification`, `onMuteToggled`, `onMediaKeyMuteToggled`, `onDeafenToggled`, `onOverlayToggled`, `onDisconnect`, `onCancelReconnect`, `setSuppressNotifications` | `client.state`, session-state mirror (self, channel), `client.events` (`PermissionDenied`), `talkActivity`, overlay setting, notification style setting, media-key mute settings | Builds the foreground notification in its connecting, connected, and reconnecting variants and exposes it as a `StateFlow<Notification>` that always has a value (`Idle` maps to the connecting variant, since a service only asks while connecting or later). The service calls `startForeground` with the current value as soon as it starts, which meets Android's five-second rule however late `client.state` moves, and posts each update while the connection lasts. On `Idle` or `Failed` the notification goes, as `MumlaConnectionNotification.hide` does today: in step 6 `HumlaService` removes it with `stopForeground`, because the bound service outlives the connection, and from step 7 `ConnectionService` stops itself. Its actions are explicit `PendingIntent`s to a non-exported `NotificationActionReceiver` (in `se.lublin.mumla.service`, not a feature) that calls the feature through the container, so they work with or without a service. Owns the media session; actions call session and client intents. Media keys and play/pause toggle self-mute while media-key mute is on (today's `onMediaKeyMuteToggled`, whose only source is the media session), with today's claimed-key and auto-repeat rules, and emit today's spoken confirmation into `speech` when that option is on. A permission denial re-shows the notification, as `MumlaService.onPermissionDenied` does today, except while the activity is in the foreground (today's `setSuppressNotifications`) |
| Message notifications | `MumlaMessageNotification`, `clearChatNotifications` | `client.events` (`TextMessageReceived`), `chatNotify` setting | Posts and clears message notifications. Never suppressed by chat visibility, as today. Clears them each time `client.state` becomes `Idle`, `Reconnecting`, or `Failed`, as `MumlaService.onConnectionDisconnected` does today |
| Chat log | `MumlaService` message log (`getMessageLog`, `clearMessageLog`, `addChatMessageToLog`, `updateActiveServerLog`, `getServerKey`) | `client.events` (filters read each record's state), `client.state` (today's connecting, connected, disconnected, and reconnecting lines), the runtime's `voiceEvents` (`EncoderLimited`, `PipelineError`), `appLog` (other features' lines, today the TTS engine-failure warnings); until step 7 also `onMessageLogged` and `MumlaService`'s send delegations (see [migration](migration.md#step-6--container-storage-and-features)) | Keeps chat history per server as `StateFlow<List<IChatMessage>>` with today's limits: 500 messages per server (`MAX_CHAT_LOG_SIZE`), 10 servers in least-recently-used order (`MAX_CACHED_SERVERS`), for the life of the process (today the log goes when the service is destroyed). Until step 7 it forwards each line it appends to `HumlaCallbacks` for the open chat screen. Formats events into the text `ModelHandler` produces today, with the `chat_notify_*`, `deny_reason_*`, and `audio_max_bandwidth` strings, and applies today's filters: moves only when they enter or leave the self user's channel (with today's wording when the user moved themselves), recordings only in the same or a linked channel. `ConnectionWarning` and `PipelineError` are logged as warnings |
| TTS | `speakTts`, `initTts`, TTS parts of `handleUserTalkStateUpdated` | `client.events`, `client.state` (connection announcements), `speech`, TTS settings, self deafen state | Speaks messages with today's rules (HTML stripped, links shortened, short form when configured, the 250-character threshold, nothing while deafened), today's connection announcements, and each `speech` line, all under the TTS master switch. Engine-failure warnings go into `appLog`, so they still reach the chat log as today |
| PTT sounds | `initSoundPool`, `playPttSound`, `releaseSoundPool` | `talkActivity.selfTalking`, `route.state`, input method and `pttSound` settings | Plays the PTT beeps in push-to-talk mode on the stream today's code picks (voice call stream on the voice route, music otherwise); reloads on route change. The mode comes from the setting: on `master` the check reads `getTransmitMode`, which always says voice activity, so the beeps never play (see [migration](migration.md#step-6--container-storage-and-features)) |
| Talk keys | `onTalkKeyDown`, `onTalkKeyUp`, `onTalkKeyCancel` | `talkKeys` (the activity's key handling and the on-screen PTT button through `MumlaService`'s `onTalkKey*` delegations until step 7, and the hot corner), `client.state`, input method and PTT toggle settings | With today's guards (connected, push-to-talk mode), calls `session.setPtt`, or `session.togglePtt` on release in toggle mode, as today. The media session is not a source: it swallows a media key bound as the PTT key so the activity's key handling gets it. A cancel (today's `ChannelFragment.onPause` and `ACTION_CANCEL` paths call `onTalkKeyCancel`) always ends a press, so leaving or rotating the screen mid-press never leaves transmit on |
| Hot corner | `MumlaHotCorner`, `onHotCorner*`, `updateHotCornerVisibility` | `client.state`, `talkActivity.selfTalking`, `configuration`, hot corner and input method settings | Shows the overlay window while connected with the hot corner on in push-to-talk mode, as today; highlights it while the user talks (today's `updateTalkState`, seeded from the current level when it appears); emits its down, up, and cancel gestures into `talkKeys`, so toggle mode and the push-to-talk guards apply to it exactly as to any other talk key (today it calls `onTalkKey*`); refreshes its gesture exclusion after rotation, deferred past the relayout as today |
| Overlay | `MumlaOverlay`, `setOverlayShown`, `isOverlayShown`, `updateOverlayVisibility`, `checkAndRequestOverlayPermission` | Session-state mirror, `talkActivity`, `configuration`, overlay settings | Shows who is talking in a system overlay; repositions on rotation and on a placement change, as today |
| Proximity | `shouldUseProximitySensor`, `setProximitySensorOn` | `route.state`, `client.state`, handset-mode setting | Holds `PROXIMITY_SCREEN_OFF_WAKE_LOCK` while connected in handset mode without SCO, as today's condition, with today's guards for devices without the sensor |
| Talk broadcast | `TalkBroadcastReceiver`, `registerTalkReceiver`, `unregisterTalkReceiver` | `client.state`, `allowTalkBroadcast` and input method settings | Registers the receiver while connected; maps on and off to `session.setPtt` and toggle to `session.togglePtt`, with today's guards (connected, push-to-talk mode only, unknown values ignored). The mode comes from the setting, which also restores the receiver: on `master` its `getTransmitMode` guard rejects every request |
| Bluetooth permission guard | `MumlaService`'s `hasBluetoothConnectGrant` check when the Bluetooth headset preference changes | Bluetooth headset setting | When the preference is turned on without the `BLUETOOTH_CONNECT` grant, turns it back off and shows today's toast. The route never sees the ungranted value, because the container gates `RoutePreferences` on the grant |
| SCO failure message | `MumlaService.onBluetoothScoFailed` (Toast) | `route.events` | Shows the toast, with today's `scoFailureMessage` bucketing |
| Avatar requests | `MumlaService`'s `requestAvatar` calls in `onUserConnected` and `onUserStateUpdated` | Session-state mirror | For each user with a texture hash and no texture, calls `session.requestTexture` once per user and hash value; forgets a user when they leave and everything when the session ends. Today's code repeats the request on every state update until the texture arrives. The server answers each request over TCP, so one request per hash is enough |
| Reconnect-required notice | `MumlaService.onSettingChanged`'s `change_requires_reconnect` toast | `certificateId` and `forceTcp` settings, `client.state` | Shows today's toast when either setting changes while connected |

Persisting and restoring self-mute/deafen moves into the session and
`LocalFactsStore` (see [state](state.md#self-mute-and-deafen)); it is no
longer a `MumlaService` job.

Everything left in `MumlaService` after this table is service lifetime,
which `ConnectionService` covers, plus two pieces step 7 moves into
`MainViewModel`: the error-shown flag and the connect half of `ConfigDelta`.
The permission-denied dialog was never a service job: `MumlaActivity` shows
it today from its own observer, and `MainViewModel` takes it over in step 7
(see [screens](#screens)). `MumlaService`, `IMumlaService`, and the
library's `HumlaService` are deleted in step 7.

## Screens

Every screen keeps its XML layout. Each connected screen gets a ViewModel
that combines the flows it needs into one UI state, and calls client or
session methods for actions. Fragments and activities collect UI state
with `repeatOnLifecycle(STARTED)`.

| Screen | ViewModel | UI state from | Actions |
|---|---|---|---|
| `MumlaActivity` | `MainViewModel` | `client.state` (target name, reconnect countdown, failure, wrong-password reason), `client.events` (`PermissionDenied`), crash report availability | Connect, switch server (with today's confirmation when already connected), disconnect, cancel reconnect, trust decisions, wrong-password retry |
| `ChannelFragment` | `ChannelViewModel` | Self `UserInfo`, `talkActivity.selfTalking`, input mode setting | PTT button, self-mute and deafen |
| `ChannelListFragment` + `ChannelListAdapter` | `ChannelListViewModel` | `ServerState.tree`, `users`, `talkActivity` | Join, move, menus. Expanded and collapsed channels stay where today's adapter keeps them |
| `ChannelChatFragment` | `ChatViewModel` | Chat log feature, chat target, `ServerSettingsInfo` limits | Send text, clear log. The ViewModel holds the chat target as `StateFlow<ChatTarget?>` (channel or user ID), replacing the fragment-implemented `ChatTargetProvider` interface; the channel list sets it through the shared activity ViewModel. Length and image-size checks run in the ViewModel before `sendText`, with today's messages; `sendText` itself only fails when not connected, which the screen already shows |
| `ServerInfoFragment` | `ServerInfoViewModel` | `ServerState.info`, `session.stats` | none |
| `AccessTokenFragment` | `AccessTokenViewModel` | Tokens from the database | Edit tokens (saved to the database), `sendAccessTokens` to the live session after each change, as today |
| `UserCommentFragment`, `ChannelDescriptionFragment` | `CommentViewModel` | The user's `comment` or channel's `description` in state | Request blob, `setComment` |
| `UserMenu`, `ChannelMenu`, `PermissionsPopupMenu` | Host screen's ViewModel | Current `UserInfo`/`ChannelInfo` and permissions | Session intents |
| `SettingsActivity`, `CrashReportsFragment` | Small ViewModels | `client.state` where they show connection status today | none |

### `MumlaActivity` responsibilities

| Today | Target |
|---|---|
| Service binding, `onServiceConnected`, observer registration | `MainViewModel` collecting client state |
| Reconnect observers (`setReconnectObserver` and friends) | `ClientState.Reconnecting` rendered by `MainViewModel` |
| TLS trust dialogs (`showMismatchDialog`, `showFirstTrustDialog`, `showChangedDialog`, `pinAndReconnect`, `pinnedForHost`) | `ClientState.Failed(Trust)` rendered as the same three dialogs with today's choices: the first-trust and changed-certificate dialogs can pin (writing `MumlaTrustStore` and calling `connect`); the hostname-mismatch dialog cannot, as today |
| `connectToServer`, `connectToServerWithPerm`, permission results | Activity keeps the permission prompts; then `MainViewModel.connect`, which starts `ConnectionService` first |
| Crash report snackbar, drawer, favourites, server editing, `mumble://` URL handling, first-run certificate guide, options menu, back-press and disconnect confirmation, volume-control stream, stay-awake, the drawer-open PTT cancel | Stay in the activity (UI-only); key events forward to the talk keys feature |
| `onPermissionDenied` dialog (replacing any open one), and the service's error-shown flag that keeps the connection-lost dialog from showing twice | `MainViewModel` exposes the latest `PermissionDenied` reason, formatted with the `deny_reason_*` strings, for the same dialog, and remembers which connection error (in `Failed` or `Reconnecting`) the user already dismissed |
| Rotation and process recreation | ViewModels survive rotation; pending dialogs are rebuilt from `ClientState` instead of saved instance state. Process death ends the connection, as today |

## Settings

Settings stay in the existing DataStore file and key space. Each consumer
collects only the keys it uses, from one current-value API on
`SettingsRepository`:

- **`state: StateFlow<SettingsState>`**, where `SettingsState` holds the raw
  `Preferences` and the typed `PrefSnapshot` derived from it, always
  together. The container seeds it with the blocking startup read (see
  [`AppContainer`](#appcontainer)), so it always has a value, and reading
  it never blocks. The `Settings` facade's getters read the snapshot.
  `DataStorePreferenceDataStore`, whose `PreferenceDataStore` contract is
  synchronous and raw-keyed with the widget's default, reads the raw
  `Preferences`.
- **`update(block: (MutablePreferences) -> Unit)`** is not `suspend` and
  never blocks. Inside an atomic `update {}` it copies the current raw
  value, applies the block to the copy, and derives the new snapshot, so
  every reader, including the caller's next line, sees the change. It then
  signals one writer coroutine in `appScope` through a conflated channel.
  The writer replaces the store's contents with the latest in-memory raw
  value (`store.updateData { latest }`). Writing the whole value, not the
  changed keys, means racing updates and coalesced signals always leave
  the store equal to memory, with no per-key bookkeeping.
- The repository is the store's only writer (one instance, built by the
  container), so the in-memory value is the truth and nothing reads the
  store back after startup. A failed write is logged; the in-memory value
  stays, and the next signal writes it again. A change made in the last
  milliseconds before the process dies can be lost; today's blocking write
  cannot lose it, and that difference is accepted.
- Until step 7, the legacy listener bridge diffs consecutive snapshots for
  the screens that still register `OnSettingChangedListener`.

| Settings key (today's `Settings` property) | Consumer |
|---|---|
| Server, certificate (`defaultCertificate`, `isUsingCertificate`), trust store, `isTcpForced`, client name, access tokens | `ConnectTarget`, frozen at `connect`; the certificate and `isTcpForced` also feed the reconnect-required notice |
| `inputMethod`, `detectionThreshold`, `amplitudeBoostMultiplier`, `inputQuality`, `framesPerPacket`, `isHalfDuplex`, `isAdaptiveLevelerEnabled` | `VoiceSettings` (see [voice](voice.md#policy-and-settings-handoff)); `inputMethod` also feeds the talk keys, hot corner, PTT-sound, and talk broadcast features |
| `isBluetoothHeadset`, `isHandsetMode` | `RoutePreferences`; `isHandsetMode` also feeds the proximity feature |
| `isMuted`, `isDeafened` | `LocalFactsStore` (self-mute restore) |
| Local mute and ignore lists (database) | `LocalFactsStore` |
| `pushToTalkKey`, `isPushToTalkToggle`, `isPushToTalkButtonShown`, `pttButtonHeight` | Talk keys feature; `ChannelFragment`; `pushToTalkKey` also feeds the connection notification feature's claimed-key check |
| `hotCorner` | Hot corner feature |
| `isOverlayShown`, `overlayPlacement`, overlay position | Overlay feature |
| `isChatNotifyEnabled` | Message notifications feature |
| `notificationStyle` | Connection notification feature |
| `isMediaKeyMuteEnabled`, `isMediaKeyMuteTtsEnabled` | Connection notification feature (media keys) |
| `isTextToSpeechEnabled`, `isShortTextToSpeechMessagesEnabled`, `ttsEngine` | TTS feature |
| `isPttSoundEnabled` | PTT sounds feature |
| `isTalkBroadcastAllowed` | Talk broadcast feature |
| `shouldLoadExternalImages` | `ChannelChatFragment` (image loading), unchanged |
| `shouldShowUserCount`, `shouldStartUpInPinnedMode`, `shouldStayAwake`, `isFirstRun`, news versions | Screens that read them today, unchanged |
| `theme`, `language` | `MumlaApplication`, as today |

Step 6 removes the voice, route, and local-fact fields from `ConfigDelta`
and their branches from `HumlaService.apply`, because the container derives
`VoiceSettings` and `RoutePreferences` from `snapshot`. The rest of
`ConfigDelta`, its bridge, and `MumlaService`'s delta building are deleted
in step 7.
