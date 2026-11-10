# Client and session

Part of the [target architecture](README.md). Specifies `HumlaClient` (the
connection lifecycle and reconnect policy) and `Session` (one connection,
its scope, its intents, and its teardown).

## `HumlaClient`

Application-scoped. Lives in `se.lublin.humla.client`, which imports no
`android.*`: everything platform-backed reaches it through small
interfaces, so the client runs in plain JVM tests with fakes:
[`NetworkMonitor`](#networkmonitor), `VoiceLifecycle` (implemented by the
`VoiceEngine`: `submit(Start)` and `submit(Stop)`), and `RouteLifecycle`
(implemented by `RouteControl`: `SessionStarted` and `SessionEnded`).

```kotlin
class HumlaClient(
    scope: CoroutineScope,                    // application scope, SupervisorJob
    private val transports: TransportFactory,
    private val voice: VoiceLifecycle,
    private val route: RouteLifecycle,
    private val network: NetworkMonitor,
    private val localFacts: LocalFactsStore,  // local mute/ignore, self mute/deaf (database, settings)
    private val clock: Clock,
) {
    val state: StateFlow<ClientState>

    fun connect(target: ConnectTarget)        // non-blocking
    fun disconnect()                          // non-blocking
    fun cancelReconnect()                     // non-blocking
}
```

All lifecycle logic runs on one **client dispatcher**
(`Dispatchers.Default.limitedParallelism(1)`). The public methods only
launch onto it and return. This dispatcher handles lifecycle transitions
only (a handful per connection), never server messages or voice, so it
cannot become a bottleneck.

### `ConnectTarget`

Everything a connection needs, frozen when `connect` is called, as today's
`ConnectionParams` snapshot is:

```kotlin
data class ConnectTarget(
    val server: Server,                       // Room entity: host, port, username, password
    val certificate: CertRef?,
    val trustStore: TrustStoreRef?,
    val forceTcp: Boolean,
    val clientName: String,
    val accessTokens: List<String>,
)
```

Local mute/ignore history and the persisted self-mute state are not in the
target; the session loads them from `LocalFactsStore` when it starts, so a
reconnect always uses current values.

### `ClientState`

```kotlin
sealed interface ClientState {
    data object Idle : ClientState
    data class Connecting(val target: ConnectTarget, val attempt: Int) : ClientState
    data class Connected(val target: ConnectTarget, val session: Session) : ClientState
    data class Reconnecting(val target: ConnectTarget, val attempt: Int,
        val retryAtMillis: Long, val lastError: ClientError,
        val lastState: ServerState?) : ClientState
    data class Failed(val target: ConnectTarget, val error: ClientError) : ClientState
}

sealed interface ClientError {
    data class Connection(val message: String, val cause: Throwable?) : ClientError  // reconnects
    data class Rejected(val type: Int, val reason: String?) : ClientError           // Reject
    data class Removed(val actor: Int?, val reason: String?, val ban: Boolean) : ClientError
    data class Trust(val chain: List<X509Certificate>, val failure: HandshakeFailure,
        val verifiedHost: String?) : ClientError                                    // TLS verification
}
```

`Connecting` also exposes the in-progress `Session` (as a nullable field
on the client, `currentSession: StateFlow<Session?>`), so a screen can show
the channel tree while it syncs if it wants to.

```mermaid
stateDiagram-v2
    [*] --> Idle
    Idle --> Connecting: connect
    Connecting --> Connected: ServerSync
    Connecting --> Reconnecting: Connection error
    Connected --> Reconnecting: Connection error
    Reconnecting --> Connecting: backoff elapsed or network available
    Connecting --> Failed: Rejected, Removed, Trust
    Connected --> Failed: Rejected, Removed
    Failed --> Connecting: connect
    Connecting --> Idle: disconnect
    Connected --> Idle: disconnect
    Reconnecting --> Idle: disconnect or cancelReconnect
    Failed --> Idle: disconnect
    Connected --> Connecting: connect (other target)
```

### Reconnect policy

Unchanged from today's `HumlaService`, now in a pure `ReconnectPolicy`
object tested by the existing `ReconnectBackoffTest` truth table:

- **Which errors reconnect:** only `ClientError.Connection` (today's
  `CONNECTION_ERROR`). `Rejected`, `Removed`, and `Trust` go to `Failed`,
  because the user has to act (a trust prompt, a changed password, a ban).
- **Delays** by attempt number: 1 s, 3 s, 6 s, 12 s, then 25 s for every
  later attempt.
- **Attempt counter:** incremented by each automatic attempt; reset when a
  session reaches `Connected` and when the user disconnects. A manual
  `connect` does *not* reset it: if a manual retry fails, the server is
  still down, so the next automatic attempt keeps the grown delay.
- **Network available:** while `Reconnecting`, a `NetworkMonitor`
  availability event cancels the timer and starts the next attempt
  immediately (incrementing the counter), as today's network callback does.
  Repeated events while `Connecting` are ignored.

### `NetworkMonitor`

```kotlin
interface NetworkMonitor {
    val available: Flow<Unit>                 // emits on each default-network availability
}
```

The app implements it over `ConnectivityManager.registerDefaultNetworkCallback`.
The client collects it only while `Reconnecting`.

## `Session`

One connection. Created by the client for each attempt and never reused.

```kotlin
class Session internal constructor(
    val id: Long,                             // unique per process, for logs and tests
    val target: ConnectTarget,
    parentScope: CoroutineScope,
    transport: Transport,
    localFacts: LocalFacts,                   // loaded before construction
) {
    val state: StateFlow<SessionState>
    val events: SharedFlow<SessionEvent>
    val stats: StateFlow<SessionStats>
    val failure: Deferred<ClientError>        // completes once, when the session fails

    // intents: see below
}
```

- **Scope:** `parentScope` plus a fresh `SupervisorJob`. The message
  coroutine, keepalive timers, and transport writer all run in it.
- **Fields:** all non-null and set at construction.
- **Failure:** any fatal condition (transport I/O error, `Reject`, removal
  of self, protocol violation) completes `failure` exactly once. The client
  awaits `failure` and ends the session.

### Start sequence

1. The client loads `LocalFacts` (local mute/ignore lists for this server,
   persisted self-mute/deafen) from `LocalFactsStore` on the I/O
   dispatcher.
2. The client creates the `Transport`, which resolves the host (SRV first,
   as today), opens the TLS socket, and verifies the certificate. A
   verification failure completes `failure` with `ClientError.Trust`.
3. The client creates the `Session` with phase `HANDSHAKING` and publishes
   `Connecting`.
4. The session sends `Version` and `Authenticate` (username, password,
   access tokens, Opus support) exactly as today's connection-established
   path does.
5. Server messages flow through the session's message coroutine (see
   [state](state.md#sync-phases-and-publication)). `CryptSetup` arrives
   during sync; the transport keeps the keys for the voice start.
6. On `ServerSync`: phase `SYNCED`, state published, the transport starts
   its ping loop, and the session sends the restored self-mute `UserState`
   if one is set.
7. The client publishes `Connected(session)`, sends `SessionStarted` to
   `RouteControl`, and submits `VoiceCommand.Start` with the server's UDP
   address, the crypt keys, the UDP packet format, whether voice starts
   tunnelled, and the tunnel sink (see [voice](voice.md#kotlin-facade)).
   Codec and input settings reach the `VoiceEngine` from the settings flow,
   not through the client.

### Intents

`Session` exposes the user actions that `IHumlaSession` exposes today. Each
is non-blocking: one atomic state update and/or one enqueued TCP message
(see [state](state.md#user-intents) for the state side).

| Today (`IHumlaSession`) | Target |
|---|---|
| `getSessionId`, `getSessionUser`, `getSessionChannel`, `getUser`, `getChannel`, `getRootChannel`, `getPermissions`, `getServerSettings`, `getServerVersion*`, `getServerRelease`, `getServerOS*` | Read `state.value` (`server.self`, `users`, `channels`, `rootPermissions`, `info`) |
| `getTCPLatency`, `getUDPLatency`, `getMaxBandwidth`, `getCurrentBandwidth` | Read `stats.value` |
| `getTransmitMode`, `getCodec` | Settings (transmit mode) and `state.value.server.info.opus` |
| `isTalking`, `setTalkingState` | `VoiceEngine` talk activity; `setPtt(pressed)` |
| `joinChannel`, `moveUserToChannel` | `joinChannel(id)`, `moveUser(session, channel)` |
| `sendAccessTokens` | `sendAccessTokens(tokens)` |
| `requestPermissions`, `requestComment`, `requestAvatar`, `requestChannelDescription` | `requestPermissions(channel)`, `requestComment(session)`, `requestTexture(session)`, `requestDescription(channel)` |
| `registerUser`, `kickBanUser`, `setPrioritySpeaker`, `setMuteDeafState` | Same names |
| `sendUserTextMessage`, `sendChannelTextMessage` | `sendText(target, message)`; returns nothing: the sent message is emitted as an event so the chat log has one input |
| `setUserComment` | `setComment(session, comment)` |
| `setSelfMuteDeafState` | `setSelfMuteDeaf(mute, deaf)` (see [state](state.md#self-mute-and-deafen)) |
| `linkChannels`, `unlinkChannels`, `unlinkAllChannels` | Same names, taking channel IDs |
| `registerWhisperTarget`, `unregisterWhisperTarget`, `setVoiceTargetId`, `getVoiceTargetId`, `getVoiceTargetMode`, `getWhisperTarget` | `registerWhisperTarget`, `unregisterWhisperTarget`, `setVoiceTarget`; reads from `state.value.local` |
| `setListeningChannel`, `setListeningVolume`, `isListeningToChannel` | `setListening`, `setListeningVolume`; reads from state |

`sendText` adds one event type to [state](state.md#events):
`TextMessageSent(target, message, sentAtMillis)`, so the chat log feature
records both directions from the event stream.

| Today (`IHumlaService`) | Target |
|---|---|
| `registerObserver`, `unregisterObserver` | Collect `client.state`, `session.state`, `session.events` |
| `isConnected`, `getConnectionState`, `getConnectionError`, `getTargetServer` | `client.state` |
| `isReconnecting`, `getReconnectAttempts`, `getReconnectDelay`, `cancelReconnect` | `ClientState.Reconnecting`; `client.cancelReconnect()` |
| `disconnect` | `client.disconnect()` |
| `isBluetoothScoActive`, `isMediaRouteActive`, `retryBluetoothSco` | `route.state`; `route.submit(RouteCommand.RetrySco)` |
| `HumlaSession()` | `(client.state.value as? Connected)?.session` |

### Ending a session

The client ends a session on failure, on `disconnect`, and before
connecting to another target. The order is fixed:

1. `voice.submit(VoiceCommand.Stop)`. The voice control thread processes
   commands in order, so the next session's `Start` can only run after this
   stop has closed the UDP socket and discarded the crypt keys.
2. `route.submit(RouteCommand.SessionEnded)`, so nothing captures from a
   route being torn down.
3. Cancel the session's scope. Its completion handler closes the TCP
   socket, which unblocks the transport's reader thread; the reader's last
   send into the closed inbound channel fails harmlessly.
4. Publish the next `ClientState`.

After step 3, nothing can reach the old session's state: its coroutines are
cancelled, its channels are closed, and native code and platform callbacks
never held a reference to it.

### Statistics

```kotlin
data class SessionStats(
    val tcpLatencyMicros: Long?,
    val udpLatencyMicros: Long?,
    val usingUdp: Boolean,
    val maxBandwidth: Int?,
    val currentBandwidth: Int?,
    val crypt: CryptStats,                    // local and remote good/late/lost/resync
)
```

Updated by the transport (see
[transport](transport.md#keepalive-and-udp-health)) and the `VoiceEngine`
status, merged by the session. Kept out of `SessionState` because latency
changes every ping.
