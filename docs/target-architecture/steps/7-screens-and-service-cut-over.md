# Step 7 — Screens and service cut-over

Part of the [migration](../migration.md). **Status: not started.**

1. Screens on ViewModels, one per commit, in this order (each depends only
   on features from step 6): `ServerInfoFragment`, `AccessTokenFragment`,
   comment fragments, `ChannelChatFragment`, `ChannelFragment`,
   `ChannelListFragment` (with the menus), `MumlaActivity`. Each commit
   removes that screen's `IHumlaObserver` and `HumlaServiceFragment` use
   (see [app](../app.md#screens)). The `MumlaActivity` commit moves the
   permission-denied dialog and the error-shown flag into `MainViewModel`.
   The `ChannelChatFragment` commit also removes the chat log's interim
   edges: the log builds text entries from `TextMessageReceived` and
   `TextMessageSent` records, resolving names from each record's state;
   it stops observing `onMessageLogged`, stops forwarding lines to
   `HumlaCallbacks`, and `MumlaService`'s send delegations no longer
   append. Its entries hold values, not live handles, so step 8 can delete
   the handles.
2. Storage off the main thread. Screens without a ViewModel that use the
   database (`FavouriteServerListFragment`, `ServerEditFragment`, the
   certificate activities, `MumlaCertificateGenerateTask`) call its
   `suspend` methods from `lifecycleScope`. `BaseActivity`,
   `ChannelFragment`, and `ChannelListFragment` collect the settings flow.
   Then the database's `runBlocking` bridge and the settings listener
   bridge are deleted.
3. `ConnectionService` with the foreground rules; the UI starts it before
   connecting. It posts the connection notification feature's
   notification, as `HumlaService` does in step 6.
4. Delete `MumlaService`, `IMumlaService`, `HumlaService`,
   `HumlaServiceProvider`, `HumlaServiceFragment`, `ConfigDelta` and its
   bridge, and the service-binding code. `HumlaRuntime` stays as the
   library's public assembly point; it loses its interim pieces (the
   main-thread poster, the deny-formatter input, and the observer-bridge
   collectors) along with the client's `IHumlaObserver` parameter.
   `HumlaCallbacks` itself goes in step 8.
5. Update the migration README's native fence and binder-surface decisions
   (see [below](../migration.md#decisions-this-reverses)).

**Done when:**

- **Executable:** `AppContainerLifecycleTest` is rewritten for
  `ConnectionService`: a connect started from the UI path starts the
  service in the foreground, a fake-transport session reaches `Connected`
  with no bound service, and the service stops itself on `Idle` and on
  `Failed`.
- No screen implements an observer or extends `HumlaServiceFragment`, and
  nothing binds a service.
- `HumlaService.kt` and `MumlaService.kt` are deleted (step 6 already
  took them off the allowlist), and the allowlist no longer lists
  `MumlaActivity.kt`, `SettingsRepository.kt`, or `MumlaRoomDatabase.kt`.
