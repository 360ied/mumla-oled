# Target-architecture progress

One row per migration step, updated in the step's final commit.
See [testing](testing.md#progressmd) and [migration](migration.md).

| Step | Status | Commits | Allowlist (files / matches) | Notes |
|---|---|---|---|---|
| 1 Harness and gate | done | fafa6126..51f67972 plus round-3 fixes (see log) | 34 / 336 | Gate + interop land; `check.sh` green. Interop 5/5 scenarios pass. Revert check: buggy `HumlaService` generation order still passes interop (harness drives `HumlaConnection`, insensitive by construction); deterministic lifecycle-race cover arrives with step-3 virtual-time client tests. Includes `KeyManagerFactory` default-algorithm compat fix and two pedantic-review rounds (gate strictness, interop robustness, fail-loud wiring, doc accuracy). |
| 2 Immutable state | done | ac38e205..HEAD (see log) | 31 / 286 | Pure transitions (channel/user/sync/intent) with ported tests; live `UserHandle`/`ChannelHandle` behind `IUser`/`IChannel` (narrowed, read-only except voice-owned `talkState`); temporary `StateEventLogger` keeps chat log; `HumlaService` holds `StateFlow<SessionState>`; `Channel`/`User`/`ModelHandler` + ported-from tests deleted; `AudioHandler.initialize` takes `UserInfo`. Gating spikes resolved: adapter-over-interfaces (`ChannelAdapterTest`) + live handles (`HandlesTest`); collections-immutable pinned at 0.5.2. `check.sh` green (net-package failures are the pre-existing native-loader set, absent under the gate's native build). |
| 3 Client and session | done | 5ff29253..HEAD (see log) | 31 / 239 | Transport (reader thread, writer coroutine, TLS, keepalive, UDP health) via LegacyVoiceLink over HumlaUDP/CryptState; Session (scope, message coroutine, intents, stats) delegating IHumlaSession; HumlaClient (ClientState, ReconnectPolicy, NetworkMonitor) delegating IHumlaService; ConnectionParams replaced by ConnectTarget with ConfigDelta bridge; connectionGeneration and connect/reconnect/whisper locks deleted; HumlaConnection/StateMessageHandler deleted; net android-free (grandfathering removed); virtual-time client tests (lifecycle races, backoff, network-available, mute spam); interop 5/5 driving HumlaClient directly. `check.sh` green. |
| 4 Voice control and route | done | 4459e745..HEAD (see log) | 29 / 172 | RouteControl on route HandlerThread publishing RouteState and events with private session counter; VoiceControl owning voice control thread and AudioHandler lifecycle following RouteState; VoiceSettings mapper and PolicyCollector publishing StateFlow<VoicePolicy> with interim Kotlin capture-thread mute; mMuteLock and monitor order deleted; AudioHandler, AudioRouteController, BluetoothScoManager off allowlist; `check.sh` green. |
| 5a Engine refactor, codec, network threads | not started | | | pool size settled by stress test |
| 5b AAudio | not started | | | gated by AAudio/Oboe + callback + echo-cancel spikes |
| 6 Container, storage, and features | not started | | | |
| 7 Screens and service cut-over | not started | | | |
| 8 Clean up | not started | | | end state: allowlist holds only boundary files |
