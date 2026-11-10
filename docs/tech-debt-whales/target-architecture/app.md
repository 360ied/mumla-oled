# App layer

Part of the [target architecture](README.md). Specifies the composition
root, the foreground service, the features that replace `MumlaService`'s
responsibilities, the screens' ViewModels, and how settings reach each
component.

## `AppContainer`

Created in `MumlaApplication.onCreate`. Constructs every
application-scoped object by hand, in this order, and holds them for the
process lifetime:

1. `appScope` (`SupervisorJob` + `Dispatchers.Default`).
2. Storage: `MumlaRoomDatabase`, `AppDataStore`, `SettingsRepository`
   (existing classes, with `suspend`/`Flow` APIs; the `runBlocking`
   bridges go).
3. `LocalFactsStore` over the database and settings (local mute/ignore
   lists, persisted self-mute/deafen).
4. `RouteControl` with `AndroidAudioPlatform` and `BluetoothScoManager`.
5. `VoiceEngine`, given the voice settings flow and `RouteControl.state`.
6. `NetworkMonitor` over `ConnectivityManager`.
7. `HumlaClient`.
8. The voice policy collector (see [voice](voice.md#voicepolicy)).
9. Features (below), each started with `appScope`.

Screens get dependencies through one `ViewModelProvider.Factory` that reads
the container from `application`. There is no DI framework; Hilt and kapt
are removed from both modules (see [migration](migration.md) step 7).

## `ConnectionService`

A foreground `Service` of type `microphone` with no logic of its own.

- **Started by the UI path that calls `connect`**, before calling it, while
  the app is in the foreground. Android 14 and later generally refuse to
  start a microphone foreground service from the background.
- **Stays in the foreground** while `ClientState` is `Connecting`,
  `Connected`, or `Reconnecting`, including every reconnect cycle. It stops
  itself when the state becomes `Idle` or `Failed`.
- **Background connects** (talk broadcast, notification actions) are
  accepted only while the service is already in the foreground; otherwise
  they are ignored and logged.
- Holds the partial wake lock and the Wi-Fi lock for its foreground
  lifetime, as `HumlaService` does today.
- Posts the connection notification built by the connection notification
  feature.
- Nothing binds to it. `onBind` returns `null`.

## Features

Each feature is a small class with a `start(scope)` method that collects
the flows it needs. Features do not call each other.

| Feature | Replaces (today) | Collects | Does |
|---|---|---|---|
| Connection notification | `MumlaConnectionNotification`, `MumlaService.updateConnectedNotification`, `onMuteToggled`, `onDeafenToggled`, `onDisconnect`, `onCancelReconnect` | `client.state`, `session.state` (self), `talkActivity` | Builds the foreground notification; its actions call session and client intents |
| Message notifications | `MumlaMessageNotification`, `clearChatNotifications`, `setSuppressNotifications` | `session.events` (`TextMessageReceived`), chat-visible flag from the chat screen | Posts and clears message notifications |
| Chat log | `MumlaService` message log (`getMessageLog`, `clearMessageLog`, `addChatMessageToLog`, `updateActiveServerLog`, `getServerKey`, the per-server LRU) | `session.events` | Keeps the per-server chat history as `StateFlow<List<IChatMessage>>`; formats informational events with the `chat_notify_*` and `deny_reason_*` strings now formatted in `ModelHandler` |
| TTS | `speakTts`, `initTts` | `session.events`, TTS settings | Speaks messages (short form when configured) |
| PTT sounds | `initSoundPool`, `playPttSound`, `releaseSoundPool` | `talkActivity.selfTalking`, `route.events` | Plays the PTT beeps; reloads on route change |
| Talk keys | `onTalkKeyDown`, `onTalkKeyUp`, `onTalkKeyCancel` | Key events from the activity and media session, PTT settings | Calls `session.setPtt`, with toggle mode as today |
| Media key mute | `onMediaKeyMuteToggled` | Media session events, settings | Toggles self-mute; optional TTS announcement |
| Hot corner | `MumlaHotCorner`, `onHotCorner*`, `updateHotCornerVisibility` | `client.state`, hot corner settings | Shows the overlay window; calls `session.setPtt` |
| Overlay | `MumlaOverlay`, `setOverlayShown`, `isOverlayShown`, `updateOverlayVisibility`, `checkAndRequestOverlayPermission` | `session.state`, `talkActivity`, overlay settings | Shows who is talking in a system overlay |
| Proximity | `shouldUseProximitySensor`, `setProximitySensorOn` | `route.state`, `client.state` | Holds the proximity wake lock on the handset route |
| Talk broadcast | `TalkBroadcastReceiver`, `registerTalkReceiver`, `unregisterTalkReceiver` | `client.state`, `allowTalkBroadcast` setting | Registers the receiver while connected; calls `session.setPtt` |
| SCO failure message | `MumlaService.onBluetoothScoFailed` (Toast) | `route.events` | Shows the toast, with today's `ScoFailureMessage` bucketing |

Persisting and restoring self-mute/deafen moves into the session and
`LocalFactsStore` (see [state](state.md#self-mute-and-deafen)); it is no
longer a `MumlaService` job.

Everything left in `MumlaService` after this table is service lifetime,
which `ConnectionService` covers. `MumlaService`, `IMumlaService`, and the
library's `HumlaService` are deleted in step 7.

## Screens

Every screen keeps its XML layout. Each connected screen gets a ViewModel
that combines the flows it needs into one UI state, and calls client or
session methods for actions. Fragments and activities collect UI state
with `repeatOnLifecycle(STARTED)`.

| Screen | ViewModel | UI state from | Actions |
|---|---|---|---|
| `MumlaActivity` | `MainViewModel` | `client.state` (target name, reconnect countdown, failure), crash report availability | Connect, disconnect, cancel reconnect, trust decisions |
| `ChannelFragment` | `ChannelViewModel` | Self `UserInfo`, `talkActivity.selfTalking`, input mode setting | PTT button, self-mute and deafen |
| `ChannelListFragment` + `ChannelListAdapter` | `ChannelListViewModel` | `ServerState.tree`, `users`, `talkActivity`, expanded channels | Join, move, menus |
| `ChannelChatFragment` | `ChatViewModel` | Chat log feature, chat target (`ChatTargetProvider`), `ServerSettingsInfo` limits | Send text, clear log, report chat visibility |
| `ServerInfoFragment` | `ServerInfoViewModel` | `ServerState.info`, `session.stats` | none |
| `AccessTokenFragment` | `AccessTokenViewModel` | Tokens from the database | Edit tokens, `sendAccessTokens` |
| `UserCommentFragment`, `ChannelDescriptionFragment` | `CommentViewModel` | The user's `comment` or channel's `description` in state | Request blob, `setComment` |
| `UserMenu`, `ChannelMenu`, `PermissionsPopupMenu` | Host screen's ViewModel | Current `UserInfo`/`ChannelInfo` and permissions | Session intents |
| `SettingsActivity`, `CrashReportsFragment` | Small ViewModels | `client.state` where they show connection status today | none |

### `MumlaActivity` responsibilities

| Today | Target |
|---|---|
| Service binding, `onServiceConnected`, observer registration | `MainViewModel` collecting client state |
| Reconnect observers (`setReconnectObserver` and friends) | `ClientState.Reconnecting` rendered by `MainViewModel` |
| TLS trust dialogs (`showMismatchDialog`, `showFirstTrustDialog`, `showChangedDialog`, `pinAndReconnect`, `pinnedForHost`) | `ClientState.Failed(Trust)` rendered as the same dialogs; pinning writes `MumlaTrustStore` and calls `connect` |
| `connectToServer`, `connectToServerWithPerm`, permission results | Activity keeps the permission prompts; then `MainViewModel.connect`, which starts `ConnectionService` first |
| Crash report snackbar, drawer, first-run guide, options menu, key events, stay-awake | Stay in the activity (UI-only); key events forward to the talk keys feature |

## Settings

Settings stay in the existing DataStore repository. Each consumer collects
only the keys it uses. Today's `ConfigDelta` fields map as follows:

| `ConfigDelta` field | Consumer |
|---|---|
| `server`, `certificate`, `trustStore`, `forceTcp`, `clientName`, `accessTokens` | `ConnectTarget`, frozen at `connect` |
| `transmitMode`, `detectionThreshold`, `amplitudeBoost`, `inputQuality`, `framesPerPacket`, `halfDuplex`, `adaptiveLeveler` | `VoiceSettings` (see [voice](voice.md#policy-and-settings-handoff)) |
| `bluetoothSco`, `audioStream` | `RoutePreferences` |
| `localMuteHistory`, `localIgnoreHistory` | `LocalFactsStore` |
| `theme` | `MumlaApplication` (as today) |
| `overlayShown`, `overlayPlacement` | Overlay feature |
| `ttsEngine` | TTS feature |

`ConfigDelta` and the delta dispatch in `HumlaService.apply` and
`MumlaService` are deleted in step 7.
