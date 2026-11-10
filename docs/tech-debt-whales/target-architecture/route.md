# Route

Part of the [target architecture](README.md). Specifies `RouteControl`
(audio routing, Bluetooth SCO, mode hold) and the session start sequence
between the client, routing, and voice.

## Starting point

Routing is the hardest real-world part of the app and the most churned:
[`BluetoothScoManager`](../../../libraries/humla/src/main/java/se/lublin/humla/audio/BluetoothScoManager.kt)
and
[`AudioRouteController`](../../../libraries/humla/src/main/java/se/lublin/humla/audio/AudioRouteController.kt)
have 27 commits between them since September. The controller is already
close to the target: one event funnel (`RouteEvent`), main-thread
confinement, ports for the platform (`AudioPlatform`) and SCO (`ScoLink`),
a pure decision (`AppliedRoute`), and about 67 virtual-time tests. The
target keeps its logic and tests, and changes three things:

1. It runs on its own thread instead of main.
2. It publishes state instead of calling a `Host` that recreates the audio
   pipeline.
3. It never calls into voice; voice follows its state.

## Thread

`RouteControl` owns a `HandlerThread` named `route`. Its coroutine
dispatcher is that thread's looper (`Handler(looper).asCoroutineDispatcher()`),
so the controller's existing single-thread confinement holds unchanged,
just on a different thread.

- `AudioPlatform.registerDeviceCallback` receives the route handler instead
  of the main handler.
- `BluetoothScoManager` uses the route thread's executor where it uses
  `context.mainExecutor` today.
- Blocking platform calls (`setCommunicationDevice`, `setMode`, SCO
  bring-up) therefore run on the route thread, never on main.
- `AudioRouteController.isMainThread` becomes `isRouteThread`; the off-thread
  re-posting and the session-token stale check become unnecessary for
  session identity (see [client](client.md#ending-a-session)), but the
  controller keeps its own `SessionStarted`/`SessionEnded` ordering.

## API

```kotlin
class RouteControl(platform: AudioPlatform, sco: ScoLink?, prefs: Flow<RoutePreferences>) {
    fun submit(command: RouteCommand)         // non-blocking; posts to the route thread
    val state: StateFlow<RouteState>
    val events: SharedFlow<RouteEvent>
}

sealed interface RouteCommand {
    data object SessionStarted : RouteCommand
    data object SessionEnded : RouteCommand
    data object RetrySco : RouteCommand       // today's retryBluetoothSco
}

data class RoutePreferences(
    val bluetoothSco: Boolean,                // today's ConfigDelta.bluetoothSco
    val handsetStream: Int,                   // today's ConfigDelta.audioStream
)
```

`RouteControl` implements the client's `RouteLifecycle` interface
(`SessionStarted`, `SessionEnded`), so the client depends only on that.

Preference changes are collected on the route thread and become the
controller's existing `SettingsChanged` event. Device changes, SCO link
changes, and settle rechecks stay internal events, as today.

## `RouteState`

```kotlin
sealed interface RouteState {
    data object Idle : RouteState                                 // no session
    data class Settling(val target: AppliedRoute) : RouteState
    data class Ready(val route: AppliedRoute, val profile: StreamProfile) : RouteState
    data class Fallback(val route: AppliedRoute, val profile: StreamProfile,
        val reason: String) : RouteState
}

enum class StreamProfile {
    VOICE,   // capture VOICE_COMMUNICATION; playback USAGE_VOICE_COMMUNICATION; echo cancellation on; mode IN_COMMUNICATION held
    MEDIA,   // capture MIC; playback USAGE_MEDIA (STREAM_MUSIC); mode untouched
}
```

`AppliedRoute` is today's enum (`VOICE_SCO`, `VOICE_HANDSET`, `MEDIA`).
`VOICE_SCO` and `VOICE_HANDSET` both use the `VOICE` profile; `MEDIA` uses
`MEDIA`. For `VOICE_HANDSET`, the handset stream and speakerphone routing
travel with the profile as they do today (see
[echo cancellation](../../echo-cancellation/README.md)).

| Transition | When |
|---|---|
| `Idle` → `Settling` | `SessionStarted` |
| `Settling` → `Ready` | The decided route is confirmed (SCO link up, or no SCO wanted) |
| `Settling` → `Fallback` | SCO fails, or the settle budget runs out (today's 3 s recheck, at most 2 rechecks) |
| `Ready`/`Fallback` → `Settling` | A device change or preference change produces a different decision (after today's 300 ms debounce) |
| `Fallback` → `Settling` | `RetrySco` |
| any → `Idle` | `SessionEnded` (SCO released, mode restored) |

**Events:** `ScoFailed(reason)` when entering `Fallback` because of SCO,
and `RouteChanged` on every entry to `Ready` or `Fallback`. A feature shows
the SCO failure message (today's `ScoFailureMessage` bucketing); screens
and features read the current route from `state`.

## Session start

```mermaid
sequenceDiagram
    participant C as HumlaClient
    participant R as RouteControl (route thread)
    participant V as VoiceEngine (control thread)

    C->>R: SessionStarted
    C->>V: Start(address, keys, format)
    Note over V: sockets and network threads start;<br/>capture and render wait
    R-->>V: RouteState = Settling(VOICE_SCO)
    Note over R: SCO bring-up (up to the settle budget)
    R-->>V: RouteState = Ready(VOICE_SCO, VOICE)
    Note over V: open capture and render with the VOICE profile
    C->>V: Stop (on teardown)
    C->>R: SessionEnded
    R-->>V: RouteState = Idle
```

Rules:

- **The client sequences lifetimes only.** Start: `SessionStarted` to route,
  then `Start` to voice. Teardown: `Stop` to voice, then `SessionEnded` to
  route.
- **Voice follows route state.** The `VoiceEngine` control thread collects
  `RouteState`. It opens capture and render only in `Ready` or `Fallback`,
  using that state's profile. While `Settling`, it keeps whatever streams
  are open (none at session start). On a new `Ready`/`Fallback` with a
  different route or profile, it restarts the streams. On `Idle`, it stops
  them.
- **Route never waits for voice, and voice never calls route.** The
  dependency is one-way, so they cannot deadlock or loop.
- **Network voice starts immediately.** Sockets, crypt, and the network
  threads start on `Start`, so inbound voice is buffered (and pings flow)
  while the route settles; only the audio device waits.

The cost is a short silence at connect while SCO settles, bounded by the
existing settle budget. This matches today, where pipeline creation also
waits for the route decision.

## Migration notes

- Step 4 moves the controller to the route thread and replaces `Host` with
  published state. The `VoiceEngine` does not exist yet, so in step 4 the
  Kotlin audio control (today's `AudioHandler` lifecycle, moved to the voice
  control thread) collects `RouteState` and recreates the Kotlin pipeline
  where `Host.recreatePipeline` did.
- The controller's existing tests keep running against the same virtual
  scheduler; only the dispatcher they replace changes.
- This is the step most likely to show problems only on a device (see
  [testing](testing.md#on-device-testing)).
