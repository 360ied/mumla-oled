# State

Part of the [target architecture](README.md). Specifies `SessionState`,
how server messages and user intents change it, the event stream, and the
compatibility handles used during migration.

## Rules

- **One immutable value per session**, held in a `MutableStateFlow` owned by
  the `Session`. Every change is `state.update { … }`.
- **Persistent collections.** Every collection in state is a
  `kotlinx.collections.immutable` type (`PersistentMap`, `PersistentSet`,
  `PersistentList`). The compiler then rejects mutation, and an update
  copies only the changed path instead of the whole map. Pin the current
  release when step 2 starts; the library is pre-1.0, so any upgrade is
  re-verified against the state tests.
- **Protocol blobs** (hashes, textures, comments) are protobuf `ByteString`,
  which is immutable.
- **Android-free.** The state package (`se.lublin.humla.state`) imports no
  `android.*` and no Android resources. User-visible strings are formatted
  by the app from [events](#events), never inside state code.
- **Pure transitions.** Every change is a function
  `(SessionState, input) -> SessionState`, with no side effects, so it is
  safe inside `update {}` and testable as a plain function.

## Types

```kotlin
data class SessionState(
    val server: ServerState,
    val local: LocalState,
)

data class ServerState(
    val phase: SyncPhase,                     // HANDSHAKING, SYNCING, SYNCED
    val info: ServerInfo,
    val selfSession: Int?,                    // from ServerSync; null before
    val rootPermissions: Int,                 // from ServerSync.permissions
    val channels: PersistentMap<Int, ChannelInfo>,
    val users: PersistentMap<Int, UserInfo>,
) {
    val tree: ChannelTree by lazy { ChannelTree.build(channels, users) }
    val self: UserInfo? get() = selfSession?.let(users::get)
}

data class ServerInfo(
    val versionV2: Long?,                     // Version (v2, or v1 converted)
    val release: String?,
    val os: String?,
    val osVersion: String?,
    val opus: Boolean,                        // CodecVersion.opus
    val maxBandwidth: Int?,                   // ServerSync, then protobuf ping
    val welcomeText: String?,                 // ServerSync / ServerConfig
    val settings: ServerSettingsInfo?,        // ServerConfig
)

data class ServerSettingsInfo(
    val allowHtml: Boolean,
    val messageLength: Int,
    val imageMessageLength: Int,
    val maxUsers: Int,
)

data class ChannelInfo(
    val id: Int,
    val parentId: Int?,                       // null only for the root (id 0)
    val name: String?,
    val position: Int,
    val temporary: Boolean,
    val description: String?,                 // null until fetched
    val descriptionHash: ByteString?,
    val links: PersistentSet<Int>,
    val maxUsers: Int,
    val enterRestricted: Boolean,
    val canEnter: Boolean,
    val permissions: Int,                     // 0 = not yet queried
)

data class UserInfo(
    val session: Int,
    val userId: Int?,                         // registered ID; null if unregistered
    val name: String?,
    val channelId: Int?,                      // null only after its channel is removed, until the server moves the user
    val certHash: String?,
    val comment: String?,                     // null until fetched
    val commentHash: ByteString?,
    val texture: ByteString?,                 // null until fetched
    val textureHash: ByteString?,
    val muted: Boolean,                       // server-decided
    val deafened: Boolean,                    // server-decided
    val suppressed: Boolean,                  // server-decided
    val selfMuted: Boolean,                   // server echo (for self: optimistic first)
    val selfDeafened: Boolean,                // server echo (for self: optimistic first)
    val prioritySpeaker: Boolean,
    val recording: Boolean,
    val listeningChannels: PersistentSet<Int>,
    val localMuted: Boolean,                  // device-local
    val localIgnored: Boolean,                // device-local
)

data class LocalState(
    val selfMuteDesired: Boolean?,            // null until the user (or restore) sets it
    val selfDeafDesired: Boolean?,
    val pttPressed: Boolean,
    val voiceTargetId: Int,                   // 0 = normal talking
    val whisperTargets: PersistentMap<Int, WhisperTarget>,   // registered VoiceTarget IDs 1..30
    val listeningVolumes: PersistentMap<Int, Float>,         // per channel
)
```

`UserInfo` deliberately carries server-decided, user-decided, and
device-local facts together: every consumer needs the joined value, and
atomic updates make several writers safe.

**`ChannelTree`** is derived, not stored. It holds the children of each
channel and the users in each channel, sorted exactly as today's
`Channel.compareTo` (position, then name, then ID) and `User.compareTo`
(name, then session) sort them. It is computed lazily the first time a
state value's `tree` is read, so values that no screen reads never pay for
it.

**Not in `SessionState`**, because they change too often or belong
elsewhere:
- Per-user talk activity: a separate flow from the
  [`VoiceEngine`](voice.md#outputs).
- Connection statistics (TCP and UDP latency, crypt good/late/lost/resync,
  bandwidth): `Session.stats`, a separate `StateFlow` updated by the
  [transport](transport.md).
- Connection lifecycle: `ClientState` in the [client](client.md).

## Sync phases and publication

| Phase | Entered when | Publication |
|---|---|---|
| `HANDSHAKING` | Session created, TLS connected, `Version` and `Authenticate` sent | Published once (empty channels and users) |
| `SYNCING` | First `ChannelState` or `UserState` arrives | Not published: the message coroutine applies transitions to a private value |
| `SYNCED` | `ServerSync` arrives | The private value is published in one `update`; every change after this publishes |

After `SYNCED`, the message coroutine drains whatever is already queued
(up to 64 messages), applies them in order, and publishes once. A burst
therefore costs one publication, while a lone message is published
immediately.

## Server message transitions

Every inbound TCP message type and what handles it. "State" rows are pure
transitions in the state package; the others are handled where noted.

| Message | Handled by | Effect |
|---|---|---|
| `Version` | State, [transport](transport.md) | `info.versionV2/release/os/osVersion`; the transport derives the UDP format from the version |
| `CodecVersion` | State | `info.opus` |
| `ChannelState` | State | Create or update `ChannelInfo`; replace `links` when `links` is present, apply `links_add`/`links_remove` otherwise |
| `ChannelRemove` | State | Remove the channel and its whole subtree (never the root); drop them from other channels' links and every user's `listeningChannels`; users inside lose their `channelId` until the server moves them, as `ModelHandler` does today |
| `UserState` | State, [events](#events) | Create or update `UserInfo` (see [self-mute](#self-mute-and-deafen)); emit join, move, recording, and self-mute events |
| `UserRemove` | State, client | Other user: remove and emit `UserLeft`. Self: the session fails with reason `USER_REMOVE` (no reconnect) |
| `ServerSync` | State, transport, client | `selfSession`, `rootPermissions`, `maxBandwidth`, `welcomeText`; phase `SYNCED`; transport starts pings; client publishes `Connected` |
| `ServerConfig` | State | `info.settings`, `welcomeText` |
| `PermissionQuery` | State | Set `permissions` on the channel; when `flush` is set, reset every other channel's `permissions` to 0 first |
| `PermissionDenied` | [Events](#events) | `PermissionDenied` event; no state change |
| `TextMessage` | [Events](#events) | `TextMessageReceived` event; chat history is an app feature, not state |
| `Reject` | Client | The session fails with reason `REJECT` (no reconnect) |
| `CryptSetup` | [Transport](transport.md#crypt-setup) | Key setup or resync, forwarded to the `VoiceEngine` |
| `Ping` | [Transport](transport.md#keepalive-and-udp-health) | TCP latency, remote crypt statistics, UDP/TCP mode decision |
| `UDPTunnel` | [Transport](transport.md#tunnel-mode) | Tunnelled voice into the `VoiceEngine` |
| `ACL`, `BanList`, `QueryUsers`, `UserList`, `UserStats`, `ContextAction`, `ContextActionModify`, `SuggestConfig`, `PluginDataTransmission` | Ignored | Not handled today; unchanged |

`ModelHandler`'s current handlers are the reference for every field-level
detail (partial-update semantics, temporary channels, `canEnter`
recomputation). Each one is ported as a transition with its existing
behavior pinned by state-transition tests (see [testing](testing.md)).

## Self-mute and deafen

Today's behavior, kept exactly:

- **The user's choice is the truth for the mic gate.** When the user sets
  self-mute or self-deafen, the session writes `local.selfMuteDesired` /
  `local.selfDeafDesired`, writes the same values optimistically into the
  self `UserInfo` (so screens update at once), and sends `UserState`. This
  is what today's `AudioHandler.setSelfMutedOptimistic` latch does: once a
  desired value exists, the server's echo of `self_mute` no longer moves the
  gate.
- **Coercion before sending**, as `setSelfMuteDeafState` does today: deafen
  implies mute, and unmute implies undeafen.
- **The echo updates the model.** The server's `UserState` for self
  overwrites the self `UserInfo` (the model confirm), but not `local`.
- **Fail-closed exception.** An echo with `self_deaf = true` closes the gate
  even if the desired value says unmuted (murmur can send deaf-only
  updates; deaf implies mute). This only ever closes the mic.
- **Restore on connect.** Self-mute and self-deafen are persisted settings,
  as today. A new session starts with `selfMuteDesired`/`selfDeafDesired`
  from settings when either is set, and sends the `UserState` right after
  `ServerSync`, as `MumlaService.onConnectionSynchronized` does today. Every
  change is written back to settings off the main thread.

The voice gate is computed from these by
[`VoicePolicy`](voice.md#voicepolicy):

```kotlin
fun effectiveSelfMute(s: SessionState): Boolean {
    val self = s.server.self ?: return true
    val desired = s.local.selfMuteDesired ?: self.selfMuted
    val deafDesired = s.local.selfDeafDesired ?: self.selfDeafened
    return desired || deafDesired || self.selfDeafened
}
```

## User intents

Intent methods on `Session` (see [client](client.md#intents)) are the only
writers of `local` and of device-local `UserInfo` fields. Each is one
`update` plus, where the protocol needs it, one outbound message:

| Intent | State change | Outbound |
|---|---|---|
| `setSelfMuteDeaf(mute, deaf)` | `local.selfMuteDesired/selfDeafDesired`; optimistic self `UserInfo` | `UserState(self_mute, self_deaf)` |
| `setPtt(pressed)` | `local.pttPressed` | none |
| `setLocalMute(session, muted)` / `setLocalIgnore` | `UserInfo.localMuted/localIgnored` | none; persisted by user ID for registered users |
| `setVoiceTarget(id)` | `local.voiceTargetId` | none |
| `registerWhisperTarget(target)` | `local.whisperTargets` | `VoiceTarget` |
| `setListening(channel, on)` | none until echo | `UserState(listening_channel_add/remove)` |
| `setListeningVolume(channel, v)` | `local.listeningVolumes` | none |
| All others (join, move, text, comment, kick, link, register, requests) | none until echo | one TCP message each, as today's `HumlaService` sends them |

Decisions that depend on current state happen inside the `update` lambda,
never after reading `state.value` (see
[remaining conventions](enforcement.md#remaining-conventions)).

## Events

`Session.events` is a `SharedFlow<SessionEvent>` with a replay of 0 and a
buffer of 256. It never drops: a full buffer suspends the message
coroutine, which slows the server stream rather than losing events. Events
are semantic; the app turns them into text (replacing the
`chat_notify_*` and `deny_reason_*` strings `ModelHandler` formats today).

```kotlin
sealed interface SessionEvent {
    data class TextMessageReceived(val actor: Int?, val actorName: String?,
        val channels: List<Int>, val trees: List<Int>, val users: List<Int>,
        val message: String, val receivedAtMillis: Long) : SessionEvent
    data class UserJoinedServer(val session: Int, val name: String?) : SessionEvent
    data class UserLeft(val session: Int, val name: String?, val actor: Int?,
        val actorName: String?, val reason: String?, val ban: Boolean) : SessionEvent
    data class UserMoved(val session: Int, val from: Int?, val to: Int,
        val actor: Int?) : SessionEvent
    data class RecordingChanged(val session: Int, val recording: Boolean) : SessionEvent
    data class SelfMuteStateChanged(val muted: Boolean, val deafened: Boolean) : SessionEvent
    data class TextMessageSent(val channels: List<Int>, val trees: List<Int>,
        val users: List<Int>, val message: String, val sentAtMillis: Long) : SessionEvent
    data class PermissionDenied(val type: Int, val permission: Int?,
        val channel: Int?, val session: Int?, val reason: String?,
        val name: String?) : SessionEvent
}
```

Informational events (`UserJoinedServer`, `UserMoved`, `RecordingChanged`,
`SelfMuteStateChanged`, and `UserLeft` without an actor) are emitted only
after `SYNCED`, as `HumlaService.logInfo` suppresses them today, so the
initial user list produces no join notifications. Kicks and bans
(`UserLeft` with an actor), `PermissionDenied`, and `TextMessageReceived`
are emitted in every phase, as today's warnings are.
`TextMessageSent` is emitted by the `sendText` intent, so the chat log
records both directions from one stream.

## Compatibility handles (migration only)

During steps 2–7 of the [migration](migration.md), existing app code keeps
using `IUser` and `IChannel` and relies on them being *live*: adapters
keep references and re-read them after observer callbacks. Snapshot types
behind those interfaces would compile and silently freeze screens, so step
2 provides **live handles** instead:

- `UserHandle(session, state: StateFlow<SessionState>) : IUser` and
  `ChannelHandle(id, state) : IChannel`.
- Every property read looks up the current value in `state.value`.
- Each handle caches the last value it saw. When the user or channel is
  gone from state, reads return that cached value, as a departed `User`
  object does today.
- Navigation properties (`channel`, `parent`, `subchannels`, `users`)
  return handles, not snapshots.
- Handles are equal when their IDs are equal, matching today's `equals`.
- Handles are read-only. Mutation goes through `Session` intents.

Reads of two properties are not mutually consistent (each reads the latest
value), which matches today's per-field `@Volatile` behavior. New code uses
`UserInfo` and `ChannelInfo` directly. The handles, `IUser`, and `IChannel`
are deleted in step 8.
