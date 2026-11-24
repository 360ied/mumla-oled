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

Created in `MumlaApplication.onCreate` and reachable from any component as
`(application as MumlaApplication).container`. Constructs every
application-scoped object by hand, in this order:

1. `appScope` (`SupervisorJob` + `Dispatchers.Default`) and the main-thread
   poster (`ContextCompat.getMainExecutor`, so no `Handler`).
2. Storage: the settings `DataStore` (with today's
   `SharedPreferencesMigration`), `SettingsRepository`, and
   `MumlaRoomDatabase`, each built once here; their singleton accessors
   are gone. The repository's first snapshot is loaded with the app's one
   **blocking startup read** (`runBlocking { first() }`), because the theme
   and locale must be applied before the first activity draws. Today's
   first `Settings` read already blocks there, so startup costs the same.
   `AppContainer.kt` is a boundary file for this one line (see
   [enforcement](enforcement.md#concurrency-tripwire)).
3. Settings-derived flows: `VoiceSettings` and `RoutePreferences`, mapped
   from `SettingsRepository.snapshot` with `distinctUntilChanged`.
4. Platform signals: `configuration`, a `StateFlow<Configuration>` that
   `MumlaApplication.onConfigurationChanged` updates (the overlay and hot
   corner reposition on rotation, as `MumlaService.onConfigurationChanged`
   does today).
5. `LocalFactsStore` over the database and settings (local mute and
   ignore lists, persisted self-mute and self-deafen).
6. `TalkActivityPublisher`.
7. `RouteControl`, through `RouteControl.create`, with `AndroidAudioPlatform`
   and a factory that builds `BluetoothScoManager` on the application
   context (see [route](route.md#api)).
8. `VoiceEngine`, given the publisher, `RouteControl.state`, the voice
   settings and route preference flows, and a tunnel sink that reads the
   client's current session. The sink refers to the client lazily (it is
   built in the next steps) and runs only while a session exists.
9. `NetworkMonitor` over `ConnectivityManager`, registered once.
10. `HumlaClient`, with `DefaultTransportFactory`. Until step 7 it also
    takes the interim observer bridge, which the container owns because the
    client outlives every service instance: `HumlaCallbacks`, the
    main-thread poster, and the deny formatter from the chat-log strings.
11. The session-state mirror, a `StateFlow<SessionState>` that follows
    `client.currentSession` and is empty without one. The policy collector
    and, until step 8, the live handles read it.
12. The voice policy collector (see [voice](voice.md#voicepolicy)), with
    its pushes of `VoicePolicy` and `VoiceSettings` into `VoiceEngine`.
13. Features (below), each started with `appScope`.

**Lifetime.** Everything the container builds lives as long as the
process and is **never closed**. Services, screens, and features borrow
these objects; none of them may close one, because the next service or
screen in the same process gets the same instance. Idle, this costs an
idle route thread and voice control dispatcher, and two registered platform
callbacks (audio devices, default network). The native engine is created
on the first voice start and kept, as `VoiceEngine` already keeps it
across reconnects.

**Interim hand-off (step 6 only).** `HumlaService` still exists in step 6
and gets the library objects as one `HumlaRuntime` value from an abstract
`runtime()` that `MumlaService` implements from the container. `HumlaService`
keeps only service-lifetime work: the wake lock and Wi-Fi lock, posting the
connection notification, the binder delegations, and the connect half of
`ConfigDelta`. Step 7 deletes `HumlaRuntime` along with the service.

Screens get dependencies through one `ViewModelProvider.Factory` that reads
the container from `application`. There is no DI framework: Hilt is removed
from both modules in [step 6](migration.md#step-6--container-storage-and-features).
`:app` keeps kapt only for the Room compiler, until a stable KSP for the
project's Kotlin version replaces it.

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
  the container (a flow, the settings repository, the client). The
  [feature isolation check](enforcement.md#feature-isolation) enforces it.
- **Event collectors never block.** Features collect `client.events` and
  hand anything slow (TTS synthesis, database writes, notification
  posting) to their own scope, as [state](state.md#events) requires, so a
  slow feature cannot stall the session's message coroutine.
- **Features take flows and functions, not owners.** A feature's
  constructor takes the flows it collects and the intent functions it
  calls (`setPtt`, `setSelfMuteDeaf`, `disconnect`), never the container
  or `HumlaClient` itself, so its tests drive it with plain flows (see
  [testing](testing.md#app-layer-tests)).
- **Features own no Android component.** A feature uses the application
  context (for example for overlay windows, `TextToSpeech`, `SoundPool`,
  and toasts) and never holds a service or an activity. Anything that needs
  an activity, such as a dialog, belongs to a screen.

| Feature | Replaces (today) | Collects | Does |
|---|---|---|---|
| Connection notification and media session | `MumlaConnectionNotification` (including its `MediaSession`, custom actions, and headset-hook and claimed-key dispatch), `updateConnectedNotification`, `onMuteToggled`, `onDeafenToggled`, `onOverlayToggled`, `onDisconnect`, `onCancelReconnect`, `setSuppressNotifications` | `client.state`, session-state mirror (self, channel), `client.events` (`PermissionDenied`), `talkActivity`, overlay setting, notification style setting | Builds the foreground notification in its connecting, connected, and reconnecting variants and exposes it as a `StateFlow`; the service posts it with `startForeground` (`HumlaService` in step 6, `ConnectionService` from step 7). Owns the media session; actions call session and client intents. A permission denial re-shows the notification, as `MumlaService.onPermissionDenied` does today, except while the activity is in the foreground (today's `setSuppressNotifications`) |
| Message notifications | `MumlaMessageNotification`, `clearChatNotifications` | `client.events` (`TextMessageReceived`), `chatNotify` setting | Posts and clears message notifications. Never suppressed by chat visibility, as today |
| Chat log | `MumlaService` message log (`getMessageLog`, `clearMessageLog`, `addChatMessageToLog`, `updateActiveServerLog`, `getServerKey`) | `client.events`, session-state mirror (for filters), `voice.events` (`EncoderLimited`, `PipelineError`) | Keeps chat history per server as `StateFlow<List<IChatMessage>>` with today's limits: 500 messages per server (`MAX_CHAT_LOG_SIZE`), 10 servers in least-recently-used order (`MAX_CACHED_SERVERS`). Formats events into the text `ModelHandler` produces today, with the `chat_notify_*`, `deny_reason_*`, and `audio_max_bandwidth` strings, and applies today's filters: moves only when they enter or leave the self user's channel (with today's wording when the user moved themselves), recordings only in the same or a linked channel. `ConnectionWarning` and `PipelineError` are logged as warnings |
| TTS | `speakTts`, `initTts`, TTS parts of `handleUserTalkStateUpdated` | `client.events`, TTS settings, self deafen state | Speaks messages with today's rules (HTML stripped, links shortened, short form when configured, the 250-character threshold, nothing while deafened), plus today's connection announcements and engine-failure messages |
| PTT sounds | `initSoundPool`, `playPttSound`, `releaseSoundPool` | `talkActivity.selfTalking`, `route.state`, `pttSound` setting | Plays the PTT beeps on the stream today's code picks (voice call stream on the voice route, music otherwise); reloads on route change |
| Talk keys | `onTalkKeyDown`, `onTalkKeyUp`, `onTalkKeyCancel` | Key events from the activity, the media session, and the on-screen PTT button, PTT settings | Calls `session.setPtt`, with toggle mode as today. A cancel (today's `ChannelFragment.onPause` and `ACTION_CANCEL` paths call `onTalkKeyCancel`) always ends a press, so leaving or rotating the screen mid-press never leaves transmit on |
| Media key mute | `onMediaKeyMuteToggled` | Media session events, settings | Toggles self-mute; optional TTS announcement |
| Hot corner | `MumlaHotCorner`, `onHotCorner*`, `updateHotCornerVisibility` | `client.state`, `configuration`, hot corner settings | Shows the overlay window; calls `session.setPtt`; refreshes its gesture exclusion after rotation, deferred past the relayout as today |
| Overlay | `MumlaOverlay`, `setOverlayShown`, `isOverlayShown`, `updateOverlayVisibility`, `checkAndRequestOverlayPermission` | Session-state mirror, `talkActivity`, `configuration`, overlay settings | Shows who is talking in a system overlay; repositions on rotation and on a placement change, as today |
| Proximity | `shouldUseProximitySensor`, `setProximitySensorOn` | `route.state`, `client.state`, handset-mode setting | Holds `PROXIMITY_SCREEN_OFF_WAKE_LOCK` while connected in handset mode without SCO, as today's condition, with today's guards for devices without the sensor |
| Talk broadcast | `TalkBroadcastReceiver`, `registerTalkReceiver`, `unregisterTalkReceiver` | `client.state`, `allowTalkBroadcast` setting, input mode | Registers the receiver while connected; maps on, off, and toggle to `session.setPtt` with today's guards (connected, push-to-talk mode only, unknown values ignored) |
| Bluetooth permission guard | `MumlaService`'s `hasBluetoothConnectGrant` check when the Bluetooth headset preference changes | Bluetooth headset setting | When the preference is turned on without the `BLUETOOTH_CONNECT` grant, turns it back off and shows today's toast |
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
collects only the keys it uses, mapped from one current-value API on
`SettingsRepository`:

- **`snapshot: StateFlow<PrefSnapshot>`** holds every key. The container
  seeds it with the blocking startup read (see
  [`AppContainer`](#appcontainer)), so it always has a value, and reading
  `snapshot.value` never blocks. The `Settings` facade's getters and
  `DataStorePreferenceDataStore` (whose `PreferenceDataStore` contract is
  synchronous) read it.
- **`update(block)`** is not `suspend` and never blocks. It applies the
  change to `snapshot` at once (an atomic `update {}`), so every reader,
  including the caller's next line, sees it. It then queues the names of the
  keys the block touched to one writer coroutine in `appScope`, which
  writes those keys with their values in `snapshot` at the time it writes.
  Two racing updates may queue in either order, but the store still ends
  at the in-memory value. The repository is the store's only writer (one instance, built
  by the container), so the in-memory value is the truth and nothing reads
  the store back. A failed write is logged and the in-memory value stays,
  so the setting holds for the rest of the process. A change made in the
  last milliseconds before the process dies can be lost; today's blocking
  write cannot lose it, and that difference is accepted.
- Until step 7, the legacy listener bridge diffs consecutive snapshots for
  the screens that still register `OnSettingChangedListener`.

| Settings key (today's `Settings` property) | Consumer |
|---|---|
| Server, certificate (`defaultCertificate`, `isUsingCertificate`), trust store, `isTcpForced`, client name, access tokens | `ConnectTarget`, frozen at `connect`; the certificate and `isTcpForced` also feed the reconnect-required notice |
| `inputMethod`, `detectionThreshold`, `amplitudeBoostMultiplier`, `inputQuality`, `framesPerPacket`, `isHalfDuplex`, `isAdaptiveLevelerEnabled` | `VoiceSettings` (see [voice](voice.md#policy-and-settings-handoff)) |
| `isBluetoothHeadset`, `isHandsetMode` | `RoutePreferences`; `isHandsetMode` also feeds the proximity feature |
| `isMuted`, `isDeafened` | `LocalFactsStore` (self-mute restore) |
| Local mute and ignore lists (database) | `LocalFactsStore` |
| `pushToTalkKey`, `isPushToTalkToggle`, `isPushToTalkButtonShown`, `pttButtonHeight` | Talk keys feature; `ChannelFragment` |
| `hotCorner` | Hot corner feature |
| `isOverlayShown`, `overlayPlacement`, overlay position | Overlay feature |
| `isChatNotifyEnabled` | Message notifications feature |
| `notificationStyle` | Connection notification feature |
| `isMediaKeyMuteEnabled`, `isMediaKeyMuteTtsEnabled` | Media key mute feature |
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
