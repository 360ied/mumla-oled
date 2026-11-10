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

enum class SyncPhase { HANDSHAKING, SYNCING, SYNCED }

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
    val listeningVolumes: PersistentMap<Int, Float>,         // per channel, as last sent
)

data class WhisperTarget(                     // today's WhisperTargetChannel
    val channelId: Int,
    val includeLinked: Boolean,
    val includeSubchannels: Boolean,
    val group: String?,
)
```

Only channel whisper targets exist: today's `WhisperTargetUsers` throws
`UnsupportedOperationException` from both members, so user whispers are
not supported today and stay out of scope. A target's display name is the
channel's name in state.

`voiceTargetId` follows today's mode mapping (`VoiceTargetMode`): 0 is normal
talking, 1–30 are registered whisper targets, and 31 is server loopback.
`VoiceTargetMode.fromId` moves into the state package unchanged, so screens
derive the mode (and the active `WhisperTarget`) from `local` instead of
today's `getVoiceTargetMode` and `getWhisperTarget`; a change is a state
change, which replaces today's `onVoiceTargetChanged` callback.

`UserInfo` deliberately carries server-decided, user-decided, and
device-local facts together: every consumer needs the joined value, and
atomic updates make several writers safe.

**`ChannelTree`** is derived, not stored:

```kotlin
class ChannelTree private constructor(...) {
    fun children(channelId: Int): List<ChannelInfo>      // sorted
    fun users(channelId: Int): List<UserInfo>            // sorted
    fun userCountInSubtree(channelId: Int): Int          // today's subchannelUserCount
    fun allLinks(channelId: Int): Set<Int>               // today's getAllLinks: transitive links
    companion object {
        fun build(channels: Map<Int, ChannelInfo>, users: Map<Int, UserInfo>): ChannelTree
    }
}
```

Sorting matches today exactly:
- **Channels** (`Channel.compareTo`): by `position`; then by name,
  case-sensitive, with a null name first; then by ID.
- **Users** (`User.compareTo`): by name, case-insensitive, with a null name
  first; then by session.

Channels whose parent is missing (possible transiently between messages)
are left out of the tree until the parent arrives. `tree` is a
`lazy(LazyThreadSafetyMode.PUBLICATION)` property declared in the class
body, so it is not part of the data class's value (and not walked by the
[immutability test](enforcement.md#state-immutability-test)), so whichever thread reads
it first computes it, concurrent first reads are harmless, and values that
no screen reads never pay for it.

**Derived reads** that today live on the model objects come from state:
`IChannel.isListening` is "the self user's `listeningChannels` contains this
channel"; `listeningVolume` is `local.listeningVolumes`; `hasTexture` is
`texture != null && !texture.isEmpty`, as today; `UserInfo.userId` is
`null` where today's `User.userId` is `-1`.

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

After `SYNCED`, the message coroutine takes whatever is already queued in
the transport's `inbound` channel, up to 64 messages, applies them in
order, and publishes once; anything still queued is handled in the next
batch, so nothing is dropped and no batch delays publication by more than
64 messages. A burst
therefore costs one publication, while a lone message is published
immediately.

## Server message transitions

Every inbound TCP message type and what handles it. "State" rows are pure
transitions in the state package; the others are handled where noted.

| Message | Handled by | Effect |
|---|---|---|
| `Version` | State, [transport](transport.md) | `info.versionV2/release/os/osVersion`; the transport derives the UDP format from the version |
| `CodecVersion` | State | `info.opus`. Today's code also records a CELT fallback, but nothing reads it: voice is Opus-only today and stays so |
| `ChannelState` | State | Create or update `ChannelInfo`. Links are applied in today's order, each step only when its field is present: replace with `links`, then remove `links_remove`, then add `links_add` |
| `ChannelRemove` | State | Remove the channel and its whole subtree (never the root); drop them from other channels' links; users inside lose their `channelId` until the server moves them, as `ModelHandler` does today. Also drop every removed ID from every user's `listeningChannels`: **an intentional fix**, since today's code only drops the top removed channel and leaks listeners on its subchannels |
| `UserState` | State, [events](#events) | Create or update `UserInfo` (see [self-mute](#self-mute-and-deafen)); emit join, move, recording, and other users' self-mute events |
| `UserRemove` | State, client | Other user: remove and emit `UserLeft`. Self: the session fails with reason `USER_REMOVE` (no reconnect) |
| `ServerSync` | State, transport, client | `selfSession`, `rootPermissions`, `maxBandwidth`, `welcomeText`; phase `SYNCED`; emit `WelcomeText` when present; transport starts pings; client publishes `Connected` |
| `ServerConfig` | State | `info.settings`, `welcomeText`; `max_bandwidth` updates `info.maxBandwidth`, which lowers the encoder settings (see [voice](voice.md#voicepolicy)) |
| `PermissionQuery` | State | Set `permissions` on the channel; when `flush` is set, reset every other channel's `permissions` to 0 first |
| `PermissionDenied` | [Events](#events) | `PermissionDenied` event; no state change |
| `TextMessage` | [Events](#events) | `TextMessageReceived` event, except from a locally ignored sender, which is dropped as today; chat history is an app feature, not state |
| `Reject` | Client | The session fails with reason `REJECT` (no reconnect) |
| `CryptSetup` | [Transport](transport.md#crypt-setup) | Key setup or resync, forwarded to the `VoiceEngine` |
| `Ping` | [Transport](transport.md#keepalive-and-udp-health) | TCP latency, remote crypt statistics, UDP/TCP mode decision |
| `UDPTunnel` | [Transport](transport.md#tunnel-mode) | Tunnelled voice into the `VoiceEngine` |
| `ACL`, `BanList`, `QueryUsers`, `UserList`, `UserStats`, `ContextAction`, `ContextActionModify`, `SuggestConfig`, `PluginDataTransmission` | Ignored | Not handled today; unchanged |
| `Authenticate`, `VoiceTarget`, `RequestBlob` | Client-sent only | Never arrive from the server; sent by the session (see [intents](#user-intents)) |

`ModelHandler`'s current handlers are the reference for every field-level
detail: partial-update semantics, temporary channels, `can_enter` (copied
from the message), cycle and self-parent guards, placeholder channels for
unknown parents, re-sorting on position or name changes, falling back to
the root for users in unknown channels, and clearing comment and texture
when their hashes change. Each one is ported as a transition with its existing
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
| `setVoiceTarget(id)` | `local.voiceTargetId` (0–31) | none |
| `registerWhisperTarget(target)` | Takes the lowest free ID in 1–30 inside the `update`; returns it, or `-1` when all 30 are taken, as today | `VoiceTarget` |
| `unregisterWhisperTarget(id)` | Frees the ID (IDs outside 1–30 are ignored) | none, as today |
| `setListening(channel, on)` | none until echo | `UserState(listening_channel_add/remove)` |
| `setListeningVolume(channel, v)` | `local.listeningVolumes` | `UserState(listening_volume_adjustment)`, as today |
| All others (join, move, text, comment, kick, link, register, requests, admin mute/deafen) | none until echo | one TCP message each, with today's `HumlaService` coercions (deaf implies mute, unmute implies undeafen, admin unmute clears `suppress`) |

Intents that need a connection fail as today when the session is not yet
`SYNCED`: they are no-ops returning nothing (or `-1` for whisper
registration), instead of today's mix of `IllegalArgumentException` and
`IllegalStateException`.

Decisions that depend on current state happen inside the `update` lambda,
never after reading `state.value` (see
[remaining conventions](enforcement.md#remaining-conventions)).

## Events

`Session.events` is a `SharedFlow<SessionEvent>` with a replay of 0 and a
buffer of 256. It never drops: a full buffer suspends the message
coroutine, which slows the server stream rather than losing events. To
keep that from stalling the connection, **collectors never do slow work
inline**: features hand anything slow (TTS synthesis, database writes,
notifications) to their own scope and return at once. A collector that
stops (a closed screen, a crashed feature) unsubscribes and no longer
holds the buffer. Events
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
    data class UserSelfMuteChanged(val session: Int, val name: String?,
        val muted: Boolean, val deafened: Boolean) : SessionEvent
    data class WelcomeText(val text: String) : SessionEvent
    data class ConnectionWarning(val message: String) : SessionEvent
    data class TextMessageSent(val channels: List<Int>, val trees: List<Int>,
        val users: List<Int>, val message: String, val sentAtMillis: Long) : SessionEvent
    data class PermissionDenied(val type: Int, val permission: Int?,
        val channel: Int?, val session: Int?, val reason: String?,
        val name: String?) : SessionEvent
}
```

`UserMoved` and `RecordingChanged` are emitted for every user. Today's
filters for which of them reach the chat log (moves into or out of the
self user's channel, with actor-specific wording; recordings in the same
or a linked channel, via `ChannelTree.allLinks`) move to the chat log
feature (see [app](app.md#features)).

`ConnectionWarning(message)` carries what today's `onConnectionWarning`
logs as a chat warning.

`SelfMuteStateChanged` is emitted by the `setSelfMuteDeaf` intent (today
the toggle site logs the self line, never the echo).
`UserSelfMuteChanged` is emitted for *other* users whose self-mute or
self-deafen changes while they are in the self user's channel, as today.
`WelcomeText` carries `ServerSync.welcome_text`, which today is logged to
chat.

Informational events (`UserJoinedServer`, `UserMoved`, `RecordingChanged`,
`SelfMuteStateChanged`, `UserSelfMuteChanged`, `WelcomeText`, and
`UserLeft` without an actor) are emitted only after `SYNCED`, as `HumlaService.logInfo` suppresses them today, so the
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
- Navigation properties return handles, not snapshots. Today they are
  typed as the concrete classes (`IUser.channel: Channel?`,
  `IChannel.parent: Channel?`, `users: List<User>`,
  `subchannels: List<Channel>`, `links: List<Channel>`), so step 2 narrows
  them to `IChannel?`, `List<IUser>`, and `List<IChannel>`; the compiler
  then points at every caller that needs the interface type.
- Handles are equal when their IDs are equal, matching today's `equals`.
- Handles are read-only. `IUser` and `IChannel` lose their setters
  (`var` properties and `set…` methods) in step 2; each app call site that
  writes through them becomes a `Session` intent, and the compiler finds
  them all.

Handle member sources:

| Member | Source |
|---|---|
| `IUser.hash` | `UserInfo.certHash` |
| `IUser.userId` | `UserInfo.userId ?: -1` |
| `IUser.talkState` | `VoiceEngine.talkActivity` (passive when absent) |
| `IUser.textureCacheKey` | Today's derivation from the texture bytes, moved as is |
| `IUser.listeningChannels`, `isListeningTo` | `UserInfo.listeningChannels` |
| `IChannel.subchannelUserCount`, `links` | `ChannelTree.userCountInSubtree`, `ChannelInfo.links` as handles |
| `IChannel.isListening`, `listeningVolume` | Derived reads above |
| Everything else | The same-named `UserInfo` or `ChannelInfo` field |

Reads of two properties are not mutually consistent (each reads the latest
value), which matches today's per-field `@Volatile` behavior. New code uses
`UserInfo` and `ChannelInfo` directly. The handles, `IUser`, and `IChannel`
are deleted in step 8.
