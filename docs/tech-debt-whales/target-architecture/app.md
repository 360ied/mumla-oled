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
  15 s while reconnecting.
- Posts the connection notification built by the connection notification
  feature.
- Nothing binds to it. `onBind` returns `null`.

## Features

Each feature is a small class with a `start(scope)` method that collects
the flows it needs. Features do not call each other.

| Feature | Replaces (today) | Collects | Does |
|---|---|---|---|
| Connection notification and media session | `MumlaConnectionNotification` (including its `MediaSession`, custom actions, and headset-hook and claimed-key dispatch), `updateConnectedNotification`, `onMuteToggled`, `onDeafenToggled`, `onOverlayToggled`, `onDisconnect`, `onCancelReconnect`, `setSuppressNotifications` | `client.state`, `session.state` (self, channel), `talkActivity`, overlay setting, notification style setting | Builds the foreground notification in its connecting, connected, and reconnecting variants; owns the media session; actions call session and client intents. While the activity is in the foreground (today's `setSuppressNotifications`), a permission denial does not re-show the notification |
| Message notifications | `MumlaMessageNotification`, `clearChatNotifications` | `session.events` (`TextMessageReceived`), `chatNotify` setting | Posts and clears message notifications. Never suppressed by chat visibility, as today |
| Chat log | `MumlaService` message log (`getMessageLog`, `clearMessageLog`, `addChatMessageToLog`, `updateActiveServerLog`, `getServerKey`) | `session.events`, `session.state` (for filters), `voice.events` (`EncoderLimited`, `PipelineError`) | Keeps chat history per server as `StateFlow<List<IChatMessage>>` with today's limits: 500 messages per server (`MAX_CHAT_LOG_SIZE`), 10 servers in least-recently-used order (`MAX_CACHED_SERVERS`). Formats events into the text `ModelHandler` produces today, with the `chat_notify_*`, `deny_reason_*`, and `audio_max_bandwidth` strings, and applies today's filters: moves only when they enter or leave the self user's channel (with today's wording when the user moved themselves), recordings only in the same or a linked channel. `ConnectionWarning` and `PipelineError` are logged as warnings |
| TTS | `speakTts`, `initTts`, TTS parts of `handleUserTalkStateUpdated` | `session.events`, TTS settings, self deafen state | Speaks messages with today's rules (HTML stripped, links shortened, short form when configured, the 250-character threshold, nothing while deafened), plus today's connection announcements and engine-failure messages |
| PTT sounds | `initSoundPool`, `playPttSound`, `releaseSoundPool` | `talkActivity.selfTalking`, `route.state`, `pttSound` setting | Plays the PTT beeps on the stream today's code picks (voice call stream on the voice route, music otherwise); reloads on route change |
| Talk keys | `onTalkKeyDown`, `onTalkKeyUp`, `onTalkKeyCancel` | Key events from the activity, the media session, and the on-screen PTT button, PTT settings | Calls `session.setPtt`, with toggle mode as today. A cancel (today's `ChannelFragment.onPause` and `ACTION_CANCEL` paths call `onTalkKeyCancel`) always ends a press, so leaving or rotating the screen mid-press never leaves transmit on |
| Media key mute | `onMediaKeyMuteToggled` | Media session events, settings | Toggles self-mute; optional TTS announcement |
| Hot corner | `MumlaHotCorner`, `onHotCorner*`, `updateHotCornerVisibility` | `client.state`, hot corner settings | Shows the overlay window; calls `session.setPtt` |
| Overlay | `MumlaOverlay`, `setOverlayShown`, `isOverlayShown`, `updateOverlayVisibility`, `checkAndRequestOverlayPermission` | `session.state`, `talkActivity`, overlay settings | Shows who is talking in a system overlay |
| Proximity | `shouldUseProximitySensor`, `setProximitySensorOn` | `route.state`, `client.state`, handset-mode setting | Holds `PROXIMITY_SCREEN_OFF_WAKE_LOCK` while connected in handset mode without SCO, as today's condition, with today's guards for devices without the sensor |
| Permission denied | `onPermissionDenied` dialog, `isErrorShown`, `markErrorShown` | `session.events` (`PermissionDenied`) | Shows today's dialog once per error, as the error-shown flag does today |
| Talk broadcast | `TalkBroadcastReceiver`, `registerTalkReceiver`, `unregisterTalkReceiver` | `client.state`, `allowTalkBroadcast` setting, input mode | Registers the receiver while connected; maps on, off, and toggle to `session.setPtt` with today's guards (connected, push-to-talk mode only, unknown values ignored) |
| Bluetooth permission guard | `MumlaService`'s `hasBluetoothConnectGrant` check when the Bluetooth headset preference changes | Bluetooth headset setting | When the preference is turned on without the `BLUETOOTH_CONNECT` grant, turns it back off and shows today's toast |
| SCO failure message | `MumlaService.onBluetoothScoFailed` (Toast) | `route.events` | Shows the toast, with today's `scoFailureMessage` bucketing |

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
| `MumlaActivity` | `MainViewModel` | `client.state` (target name, reconnect countdown, failure, wrong-password reason), crash report availability | Connect, switch server (with today's confirmation when already connected), disconnect, cancel reconnect, trust decisions, wrong-password retry |
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
| Rotation and process recreation | ViewModels survive rotation; pending dialogs are rebuilt from `ClientState` instead of saved instance state. Process death ends the connection, as today |

## Settings

Settings stay in the existing DataStore repository; each consumer collects
only the keys it uses as a `Flow`. The repository's synchronous getters
(used today through `runBlocking`) are replaced by one
`StateFlow<SettingsSnapshot>` (today's `PrefSnapshot`) for consumers that
need the current value, and `suspend` setters.

| Settings key (today's `Settings` property) | Consumer |
|---|---|
| Server, certificate (`defaultCertificate`, `isUsingCertificate`), trust store, `isTcpForced`, client name, access tokens | `ConnectTarget`, frozen at `connect` |
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

`ConfigDelta`, its bridge, and the delta dispatch in `HumlaService.apply`
and `MumlaService` are deleted in step 7.
