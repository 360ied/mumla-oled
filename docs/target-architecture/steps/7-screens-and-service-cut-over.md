# Step 7 — Screens and service cut-over

Part of the [migration](../migration.md). **Status: not started.**

Step 6 left `MumlaService` with four jobs: service lifetime, the binder
delegations, the connect half of `ConfigDelta`, and the error-shown flag.
`HumlaService` is still its base class, and the interim observer bridge
still feeds today's screens. This step moves every screen onto a ViewModel
that collects the container's flows. It replaces both services with
`ConnectionService` and deletes the binder surface. Once nothing uses them,
it also deletes the observer bridge and the live handles. When it is done,
nothing binds a service, nothing registers an observer, and no code reads
state through `IUser` or `IChannel`.

**Where `HumlaService`'s remaining work goes.** Besides the binder
delegations, the service still does work that no screen calls directly.
Each piece has a destination before the class is deleted:

| Today (`HumlaService`, `MumlaService`) | Target | Commit |
|---|---|---|
| Wake lock and Wi-Fi lock, with their windows | `ConnectionService`, unchanged | 10 |
| Posting the connection notification and removing it on `Idle` or `Failed` | `ConnectionService`, which stops itself instead | 10 |
| `buildConnectDelta`, `apply`, and `connect`: saved-server lookup, certificate, access tokens, trust store with `isTrustStorePathAllowed`, client name | `ConnectTargets.build` in `:app` (`suspend`), called by the container's connect path | 10 |
| `HumlaService.init` (MiniDNS link properties and the 800 ms resolver timeout), called from `MumlaApplication.onCreate` | The first thing `HumlaRuntime.create` does | 10 |
| `lastServer.setResolved` on `Connected`, whose only reader is `ServerInfoFragment` (through `getSrvHost`) | `ServerInfoViewModel` reads `session.transport.remoteHost` and `remotePort`; `Server.setResolved` is deleted | 1 |
| `pendingVoiceTarget`: applied on `Connected`, reset to normal on every disconnect with an `onVoiceTargetChanged` callback | Deleted. The voice target lives in `SessionState.local`, and a new session starts at normal. Its only caller, `ChannelMenu`, sets a target on a synced session | 10 |
| `ClientError.toHumlaException`, `getConnectionState`, `getConnectionError`, `isReconnecting`, `getReconnectAttempts`, `getReconnectDelay`, `getTargetServer` | Screens read `ClientState` and `ClientError` directly (see [client](../client.md#intents)); the members go with their last caller | 1–10 |
| Connection-state fan-out into `HumlaCallbacks` (`onConnecting`, `onConnected`, `onDisconnected`, `onTLSHandshakeFailed`, `onVoiceTargetChanged`) | Nothing: by commit 10 no observer is left | 10 |
| `setSelfMuteDeafState`'s self `onUserStateUpdated` repaint hint | Nothing: screens collect the self `UserInfo` | 10 |
| The error-shown flag (`isErrorShown`, `markErrorShown`, reset when a connect starts) | `MainViewModel` | 9 |
| `MumlaService.onStartCommand`'s notification-action branch | Deleted; the actions already go to `NotificationActionReceiver` | 10 |

**Rules for commits 1–9.**

- **One factory.** `MumlaViewModelFactory`, added in commit 1, is the one
  `ViewModelProvider.Factory`, and it reads the container from
  `application` (see [app](../app.md#appcontainer)). Like features,
  ViewModels take the flows they collect and the functions they call,
  never the container or `HumlaClient` itself, so their tests drive them
  with plain flows under virtual time (see
  [testing](../testing.md#app-layer-tests)).
- **The session comes from `client.state`.** A ViewModel reaches the
  current session as `(client.state.value as? ClientState.Connected)?.session`
  and never keeps a `Session` across a state change. An action without a
  session does nothing, as `HumlaService`'s `currentSession()?.` calls do
  today. Server state comes from the runtime's session-state mirror, which
  is one flow across reconnects.
- **IDs, not values.** A screen keeps channel and user IDs across time and
  looks up `ChannelInfo` and `UserInfo` in the latest state each time it
  renders or acts (see
  [enforcement](../enforcement.md#remaining-conventions)). No screen code
  added in this step names `IUser`, `IChannel`, or a handle.
- **Collection.** Fragments and activities collect UI state with
  `repeatOnLifecycle(STARTED)`.
- **Each commit removes its screen's legacy wiring:** its
  `HumlaServiceFragment` base class, its observer, its
  `OnSettingChangedListener` (replaced by collecting `settings.state`
  mapped to the keys it reads), and its `getService()` and
  `HumlaSession()` calls.
- **Delete what the commit makes dead.** When a screen was the last caller
  of an `IMumlaService`, `IHumlaService`, or `IHumlaSession` member, the
  same commit deletes the member from the interface and from `MumlaService`,
  `HumlaService`, and `Session`, together with tests that only exercised
  it.

Commits, in order:

1. **ViewModel factory and `ServerInfoFragment`.** `ServerInfoViewModel`
   combines `ServerState.info` (release, OS, version, Opus, `maxBandwidth`)
   with the current session's `stats` (`flatMapLatest` over
   `client.state`) and the resolved host and port from `session.transport`.
   This replaces the fragment's polling executor and main-thread `Handler`,
   so `ServerInfoFragment.kt` (2) leaves the allowlist. With the fragment
   off `getTargetServer().getSrvHost()`, nothing reads what `HumlaService`
   writes with `setResolved` on `Connected`. The write goes, with
   `Server.setResolved` and its `@Synchronized` (`Server.kt` drops from 5
   to 4). `getSrvHost` and `getSrvPort` stay for `ServerInfoTask`'s
   server-list pings.
2. **`AccessTokenFragment`.** `AccessTokenViewModel` loads the server's
   tokens through `database.query` (commit 11 turns these into direct
   `suspend` calls). It saves each added or removed token, and after each
   change it sends the full list with `session.sendAccessTokens` when a
   session exists, as the fragment does today through `HumlaSession()`.
3. **Comment fragments.** `CommentViewModel` serves `UserCommentFragment`
   and `ChannelDescriptionFragment`. It reads the user's `comment` or the
   channel's `description` from state, and requests the blob
   (`requestComment` or `requestDescription`) when state has a hash but no
   text. The text then arrives through state, which replaces the one-shot
   observers the fragments register and unregister today. Saving calls
   `setComment`.
4. **`ChannelFragment`.** `ChannelViewModel` combines the self `UserInfo`
   (self-mute and self-deafen icons), `talkActivity.selfTalking`, the voice
   target and whisper target from `SessionState.local` (replacing
   `onVoiceTargetChanged` and `getWhisperTarget`), and the PTT button and
   input method settings. The on-screen PTT button and
   the `onPause` cancel emit into `talkKeys` directly, so `onTalkKeyDown`,
   `onTalkKeyUp`, and `onTalkKeyCancel` lose this caller. The Bluetooth
   menu item reads `client.state` for its visibility. `ChannelViewModel`
   also owns the chat target as `StateFlow<ChatTarget?>` (a channel or user
   ID). `ChannelFragment` hosts both the list and the chat, so the two
   children read the target through
   `viewModels(ownerProducer = { requireParentFragment() })`, and the
   `ChatTargetProvider` interface and its listener list are deleted.
5. **`ChannelChatFragment` and the chat log's interim edges.**
   `ChatViewModel` collects the chat log feature's `messages` and the chat
   target. It runs today's length and image-size checks before
   `session.sendText`, which emits `TextMessageSent`. The same commit
   removes the chat log's interim edges (see
   [step 6](6-container-storage-and-features.md#step-6--container-storage-and-features)):
   - The log builds text entries from the `TextMessageReceived` and
     `TextMessageSent` records, and resolves sender and target names from
     each record's state.
   - `ChatLogFeature` loses its `HumlaCallbacks` parameter,
     `receivedTextObserver`, the container's registration of it,
     `addSentText`, and the forwarding of its lines to `onLogInfo`,
     `onLogWarning`, and `onLogError`.
   - `MumlaService`'s `sendUserTextMessage` and `sendChannelTextMessage`
     overrides go. So do `getMessageLog` and `clearMessageLog`, because the
     screen now clears the log through the feature.
   - `IChatMessage.TextMessage` holds values (sender name, target names,
     body, time, self-authored), not an `IMessage`, so step 7's last
     commits can delete the handles behind it.

   An open chat screen updates live because it collects the log, not
   because lines are forwarded to it.
6. **`ChannelListFragment`, `ChannelListAdapter`, and the menus.**
   `ChannelListViewModel` combines `ServerState.tree`, `users`, and
   `talkActivity`. The adapter keeps channel and user IDs, builds each row
   from the latest state, and keeps expanded and collapsed channels where
   today's adapter keeps them. Pinned-only mode reads the pinned channels
   through `database.query`. `UserMenu`, `ChannelMenu`, and
   `PermissionsPopupMenu` take the current `UserInfo` or `ChannelInfo` and
   call session intents through the ViewModel:
   - Whisper targets go through `registerWhisperTarget` with a
     `state.WhisperTarget`, then `setVoiceTarget`, so `WhisperTargetChannel`
     loses its caller.
   - Channel pinning reads the server ID from `client.state`'s target.
   - `PermissionsPopupMenu` requests permissions and reads
     `ChannelInfo.permissions` from state, which replaces its own
     observer.
   - The handle's `textureCacheKey` derivation, which `AvatarCache` uses,
     moves as is into a function over `UserInfo`.
   - `shouldShowUserCount` comes from `settings.state`.

   This commit also adds the screen-level adapter test that
   [issue 07](../issues/07-channel-adapter-spike-weaker.md) asks for.
7. **Overlay feature off observers.** `MumlaOverlay` and its
   `ChannelAdapter` still repaint from observer callbacks through
   `OverlayHost.registerObserver`, a step-6 leftover against the feature
   table, which says the overlay collects the session-state mirror and
   `talkActivity` (see [app](../app.md#features)). This commit makes the
   overlay collect them, keyed by user ID like the channel list.
   `OverlayHost` loses `registerObserver`, `unregisterObserver`, and
   `sessionChannel(): IChannel`, and the container stops building a
   `ChannelHandle` for it. `MumlaOverlayMarginsTest` and the overlay
   feature tests keep passing.
8. **Settings screens.** `SettingsActivity` stops binding `MumlaService`,
   and `CrashReportsFragment` gets a small ViewModel over `client.state`.
   The crash-test buttons are enabled while the state is `Idle` or
   `Failed`. That replaces today's "bound and not connected" condition,
   whose "bound" half means only that a service instance happened to be
   running. The `crash_test_unbound` string, which has no translations, is
   deleted.
9. **`MumlaActivity` and `MainViewModel`.** `MainViewModel` takes over
   the activity rows of the
   [responsibilities table](../app.md#mumlaactivity-responsibilities):
   - **No binding.** The activity's `ServiceConnection`,
     `mServiceFragments`, the reconnect observers, and the pending-reconnect
     server are deleted. So are `HumlaServiceProvider` and
     `HumlaServiceFragment`, whose last users go in commits 1–8.
   - **Connection state.** The reconnect countdown comes from
     `Reconnecting.retryAtMillis`, and the wrong-password retry from
     `Failed(Rejected)`. The TLS trust dialogs come from `Failed(Trust)`,
     using its chain, failure, and verified host (falling back to the
     target's host, as `lastServer.host` does today). Pinning writes
     `MumlaTrustStore` and connects again.
   - **Permission denied.** `client.events` delivers `PermissionDenied`,
     formatted with the container's deny formatter, for the same dialog,
     replacing any open one.
   - **Error-shown flag.** `MainViewModel` remembers which `ClientError`
     instance the user dismissed and forgets it when the state becomes
     `Connecting`.
   - **Server switch.** The switch keeps today's sequence and today's
     pause rule. After the confirmation, `MainViewModel` calls
     `disconnect` and holds the new server as a pending switch. The connect
     runs once `client.state` is `Idle` while the activity is started: the
     activity's `repeatOnLifecycle(STARTED)` collector triggers it. So a
     switch confirmed just before leaving the app connects on return, as
     today's rebind does, and the microphone service is never started
     from the background.
   - **Suppressing notifications.** `setSuppressNotifications` reaches the
     connection notification feature through `MainViewModel` from the
     activity's resume and pause.
   - **Talk keys.** Key events and the drawer-open PTT cancel emit into
     `talkKeys`. With that, every `onTalkKey*` delegation has lost its last
     caller and is deleted. The volume-control stream reads
     `isMediaRouteActive` from the runtime.
   - **Crash reports.** Crash-report availability (the startup check and
     its delayed recheck) moves into `MainViewModel` as a coroutine on
     `Dispatchers.IO` that calls `CrashReportSeenStore.getLastSeen`
     directly. This removes all five tripwire matches in the activity (two
     `Thread`s, `runBlocking`, the crash `Handler`, and the `@Volatile`
     generation), and `MumlaActivity.kt` (5) leaves the allowlist.
   - **Connect.** Until commit 10, `MainViewModel.connect` builds today's
     `ACTION_CONNECT` intent and starts `MumlaService` with it, and
     `ServerConnectTask`, an `AsyncTask` that only built that intent, is
     deleted. Nothing binds `MumlaService` after this commit. The started
     service still posts the notification and connects, as on `master`.

   `MainViewModel` tests cover each row above.
10. **`ConnectionService` and the connect path.** This is one commit,
    because the swap is atomic: two services cannot both own the
    connection notification and the locks.
    - **`ConnectTargets.build`.** A `suspend` function in `:app` builds the
      `ConnectTarget` from a `Server`. It re-reads a saved server by ID,
      because the database stays the source of truth, and it moves
      `buildConnectDelta`, `PendingConnection`, and `HumlaService.connect`'s
      mapping unchanged: certificate, access tokens, trust store (with
      `isTrustStorePathAllowed` and its tests), `forceTcp`, and the client
      name.
    - **The connect path.** The container's `connect(target)`, which
      `MainViewModel` calls, does two things. It calls
      `client.connect(target)`. It also launches a waiter in `appScope` that
      starts `ConnectionService` with `startForegroundService` at the first
      non-terminal `client.state`. The waiter ends without starting
      anything at a `Failed` that is not the instance present before the
      call, and a later connect cancels an earlier waiter. The service is
      therefore never started while the state still shows `Idle` or a stale
      `Failed`, and an attempt that fails before `Connecting` is observed
      starts no service. If Android refuses the start because the app left
      the foreground in between, the refusal is logged and the connection
      continues without the service.
    - **`ConnectionService`.** It follows the rules in
      [app](../app.md#connectionservice). `onStartCommand` calls
      `startForeground` at once with the feature's current notification
      (type microphone on API 34 and later, as today), then collects
      `client.state`. It posts each update while the state is
      `Connecting`, `Connected`, or `Reconnecting`. On `Idle` or `Failed`
      it removes the notification and calls `stopSelf`. The locks move
      from `HumlaService` unchanged. `onDestroy` releases them and calls
      `client.disconnect()` if the state is not terminal, so a connection
      never outlives its foreground service. `onBind` returns `null`, and
      the service returns `START_NOT_STICKY`, as today.
    - **Manifests.** `ConnectionService` replaces `MumlaService` in the app
      manifest. The library manifest loses its `.HumlaService` entry, and
      the Wi-Fi lock permissions move to the app manifest with the lock's
      owner.
    - **Deleted:** `MumlaService`, `IMumlaService`, `HumlaService`,
      `IHumlaService`, `IHumlaSession`, `HumlaDisconnectedException`,
      `ConfigDelta` (with `CertRef` and its `TrustStoreRef` twin), and the
      `HumlaService` tests (`HumlaServiceApplyTest` and the rest). Their
      cases that still describe behavior become `ConnectTargets` and
      lifecycle tests. `HumlaService.init` moves into
      `HumlaRuntime.create`, so `MumlaApplication` no longer names the
      service.
    - **Lifecycle test.** `AppContainerLifecycleTest` is rewritten for
      `ConnectionService` in this commit, because this commit makes the
      service live (see the done-when list).
11. **Storage off the main thread.**
    - `MumlaDatabase`'s methods become `suspend`, each running on
      `Dispatchers.IO`. The `runBlocking` bridge (`io`) and `query` are
      deleted, along with `isCommentSeen` and `markCommentSeen`, which
      nothing calls.
    - The `database.query` calls from commits 2 and 6 become direct
      calls.
    - Screens without a ViewModel call the database from `lifecycleScope`:
      `FavouriteServerListFragment`, `ServerEditFragment`, the
      favourites code in `MumlaActivity`, and the certificate activities.
    - `CertificateImportActivity`'s single-thread executor becomes a
      `lifecycleScope` coroutine on `Dispatchers.IO`, which keeps today's
      cancel-on-destroy check, and the activity (1) leaves the allowlist.
    - `MumlaCertificateGenerateTask` becomes a `suspend` function that
      generates on `Dispatchers.Default`, and its `AsyncTask` goes.
    - `BaseActivity`, the last listener, collects the theme from
      `settings.state`. The listener bridge is then deleted: `Settings`'s
      `registerListener`, `unregisterListener`, and
      `OnSettingChangedListener`, and `SettingsRepository`'s listener code.
      `SettingsRepository.kt` (3) and `MumlaRoomDatabase.kt` (2) leave the
      allowlist.
12. **Observer bridge and compatibility types.** `HumlaClient` and
    `Session` lose the observer, the main-thread poster, and the deny
    formatter. `HumlaRuntime` loses `callbacks`, the observer-bridge
    collectors (route, SCO, and talk hints), and `Inputs.denyFormatter`,
    and keeps the session-state mirror, which features and ViewModels
    collect. The container keeps the deny formatter for `MainViewModel`
    and the chat log. With no observer and no handle reader left, the
    commit deletes:
    - `IHumlaObserver`, `HumlaObserver`, and `HumlaCallbacks`;
    - `Session`'s observer fan-out and its handle-returning getters
      (`getSessionUser`, `getSessionChannel`, `getUser`, `getChannel`,
      `getRootChannel`), along with the other legacy getters whose only
      caller was `HumlaService`;
    - `UserHandle`, `ChannelHandle`, `IUser`, `IChannel`, `IMessage`, and
      `Message`, once `MessageNotificationsFeature` and
      `MumlaMessageNotification` take commit 5's text-entry value instead
      of building a `Message`;
    - the `model` whisper-target classes (`WhisperTarget`,
      `WhisperTargetChannel`, `WhisperTargetUsers`, `WhisperTargetList`),
      replaced by `state.WhisperTarget` since commits 4 and 6;
    - `HandlesTest`, `ChannelAdapterTest`, and the other tests of deleted
      types.

    [Issue 07](../issues/07-channel-adapter-spike-weaker.md) is resolved
    by commit 6's test, since the handles it worries about are gone.
13. **Docs.**
    - The migration README's native-fence and binder-surface bullets each
      get a dated "superseded" note that links the replacing decision (see
      [below](../migration.md#decisions-this-reverses)); the original text
      stays.
    - The living specs drop the interim notes this step retires: the
      interim hand-off, the observer bridge, the chat log's step-6 inputs,
      the `HumlaRuntime` interim members, and the compatibility-handles
      section.
    - `PROGRESS.md` gets its row.

What step 7 leaves for [step 8](8-clean-up.md): the non-boundary allowlist
entries that no screen or service change touches (`CrashReportsFragment.kt`,
`ServerAdapter.kt`, `MumbleImageGetter.kt`, and `Server.kt`), and leftover
shims with no production caller.

**Done when:**

- **Executable:** `AppContainerLifecycleTest` (Robolectric, `:app`) is
  rewritten for `ConnectionService`. It builds the real container with fake
  edges, as in step 6, and asserts that:
  - a connect through the container's connect path starts
    `ConnectionService`, which calls `startForeground` with the connection
    notification;
  - the fake-transport session reaches `Connected` with no service bound;
  - the service stops itself and removes the notification on `Idle` (after
    `disconnect`) and on `Failed` (the fake transport rejects);
  - a connect that fails before `Connecting` is observed leaves no running
    service;
  - destroying the service while `Connected` disconnects the client;
  - a second connect after the service stopped gets the same `HumlaClient`
    and `HumlaRuntime`, and the route still answers `SessionStarted` and
    `SessionEnded`.
- **Executable:** ViewModel tests over fake flows for each screen in
  commits 1–9, including `MainViewModel`'s trust, wrong-password,
  reconnect-countdown, permission-denied, dismissed-error, and
  pending-switch cases. The chat test pins that a sent message appears in
  the log once.
- **Executable:** the screen-level channel-list test from commit 6, which
  binds the real `ChannelListAdapter` in a Robolectric activity, drives
  state through the pure transitions (users joining, moving, muting,
  leaving; talking starting and stopping), and asserts the bound rows after
  each step, including that the talk indicator turns off.
- Invariants and their detectors:
  - **Nothing binds a service:** a new architecture check fails on
    `bindService(` or `ServiceConnection` under `app/src/main`, and
    `HumlaServiceFragment`, `IMumlaService`, and `IHumlaService` no longer
    exist.
  - **No observers, no handles:** `IHumlaObserver`, `HumlaCallbacks`,
    `IUser`, `IChannel`, and the handles are deleted, so the compiler
    rejects any use.
  - **The service is in the foreground exactly while the connection
    lives:** the lifecycle assertions above.
  - **One input per chat direction:** the chat test above, and
    `ChatLogFeature` takes no `HumlaCallbacks`.
  - **No storage I/O blocks a screen after startup:** the concurrency
    tripwire. `MumlaRoomDatabase.kt` and `SettingsRepository.kt` leave the
    allowlist, so a returning `runBlocking` or `synchronized` fails the
    gate.
- **Allowlist:** these leave the list: `MumlaActivity.kt` (5),
  `ServerInfoFragment.kt` (2), `SettingsRepository.kt` (3),
  `MumlaRoomDatabase.kt` (2), and `CertificateImportActivity.kt` (1).
  `Server.kt` drops from 5 to 4. That takes the list from 15 / 44 to
  10 / 30, of which six files are boundary files.

## Kill criterion

**Step 7:** if a screen cannot move to a ViewModel without a second copy
of connection state that must be kept in sync with `client.state`, or
without reading through a live handle, the screens table is wrong. Revise
[app](../app.md#screens) on `master` before continuing. If the
`ConnectionService` start rule above cannot be made race-free from
`ClientState` alone, revise [client](../client.md#clientstate) and
[app](../app.md#connectionservice) on `master` first.
