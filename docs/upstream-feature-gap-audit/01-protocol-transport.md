# 01 — Protocol and transport

Factual parity audit of the Mumble protocol, transport, and connection-security
layers in Mumla OLED versus upstream Mumble. No recommendations, no judgments.

## 1. Scope and files read

### Mumla OLED — read every line unless noted

- [HumlaConnection.kt](../../libraries/humla/src/main/java/se/lublin/humla/net/HumlaConnection.kt) —
  connection state, `getProtobufMessage`, `mConnectionMessageHandler`,
  `mUDPPingListener`, `scheduleNextPing`/`sendKeepalivePing`,
  `sendUDPMessage`/`enableForceTCP`, `broadcastTCPMessage`/`broadcastUDPMessage`,
  `onUDPDataReceived`, `applyLegacyCodecWorkaround`
- [HumlaTCP.kt](../../libraries/humla/src/main/java/se/lublin/humla/net/HumlaTCP.kt) —
  connect, SRV lookup, TLS handshake, `verifyServerIdentity`,
  `classifyHandshakeFailure`, read loop + `FrameValidator`, `sendMessage`
  overloads, `disconnect`, timeouts
- [HumlaUDP.kt](../../libraries/humla/src/main/java/se/lublin/humla/net/HumlaUDP.kt) —
  socket, send channel, receive/decrypt/resync, queue rescale
- [HumlaTCPMessageType.kt](../../libraries/humla/src/main/java/se/lublin/humla/net/HumlaTCPMessageType.kt) —
  full read (27 entries)
- [HumlaUDPMessageType.kt](../../libraries/humla/src/main/java/se/lublin/humla/net/HumlaUDPMessageType.kt) —
  full read (5 entries, 3 deprecated legacy)
- [CryptState.kt](../../libraries/humla/src/main/java/se/lublin/humla/net/CryptState.kt) —
  native-backed OCB2-AES128 wrapper
- [PacketBuffer.kt](../../libraries/humla/src/main/java/se/lublin/humla/net/PacketBuffer.kt) —
  varint/codec buffer
- [FrameValidator.kt](../../libraries/humla/src/main/java/se/lublin/humla/net/FrameValidator.kt) —
  `MAX_FRAME_BYTES = 0x7fffff`
- [HumlaSSLSocketFactory.kt](../../libraries/humla/src/main/java/se/lublin/humla/net/HumlaSSLSocketFactory.kt) —
  TLS 1.2/1.3 floor, pin-scoped trust wrapper
- [TlsHostnameVerifier.kt](../../libraries/humla/src/main/java/se/lublin/humla/net/TlsHostnameVerifier.kt) —
  RFC 2815/6125 matcher
- [HandshakeFailure.kt](../../libraries/humla/src/main/java/se/lublin/humla/net/HandshakeFailure.kt)
- [Permissions.kt](../../libraries/humla/src/main/java/se/lublin/humla/net/Permissions.kt) —
  bitmask constants
- [HumlaCertificateGenerator.kt](../../libraries/humla/src/main/java/se/lublin/humla/net/HumlaCertificateGenerator.kt) —
  self-signed RSA-2048 + PKCS#12
- [HumlaTCPMessageListener.kt](../../libraries/humla/src/main/java/se/lublin/humla/protocol/HumlaTCPMessageListener.kt) —
  27 methods + Stub
- [HumlaUDPMessageListener.kt](../../libraries/humla/src/main/java/se/lublin/humla/protocol/HumlaUDPMessageListener.kt) —
  4 methods + Stub
- [ModelHandler.kt](../../libraries/humla/src/main/java/se/lublin/humla/protocol/ModelHandler.kt) —
  message-handling parts: ChannelState/Remove, PermissionQuery, UserState/Remove,
  PermissionDenied, TextMessage, ServerSync, ServerConfig
- [AudioHandler.kt](../../libraries/humla/src/main/java/se/lublin/humla/protocol/AudioHandler.kt) —
  UDP voice parts: `onAudioPacketEncoded` legacy+protobuf send,
  `messageVoiceData`/`messageProtobufAudio`/`messageProtobufPing`,
  `messageCodecVersion`, `messageServerSync`/`messageServerConfig`,
  `messageUserState` gate
- [MumbleURLParser.kt](../../libraries/humla/src/main/java/se/lublin/humla/util/MumbleURLParser.kt)
- [HumlaNetworkListener.kt](../../libraries/humla/src/main/java/se/lublin/humla/util/HumlaNetworkListener.kt)
- [Constants.kt](../../libraries/humla/src/main/java/se/lublin/humla/Constants.kt) —
  protocol 1.5.0, version packing, `createVersionMessage`,
  `isProtobufUdpSupported`
- [HumlaService.kt](../../libraries/humla/src/main/java/se/lublin/humla/HumlaService.kt) —
  partial: version/authenticate send, reconnect constants
  (`RECONNECT_DELAY_INITIAL`, `RECONNECT_DELAY_MAX`), `getReconnectDelayForAttempts`,
  disconnect/reconnect paths
- [AudioOutput.kt](../../libraries/humla/src/main/java/se/lublin/humla/audio/AudioOutput.kt) —
  partial: legacy voice decode loop, `queueProtobufVoiceData`
- [ServerConnectTask.kt](../../app/src/main/java/se/lublin/mumla/app/ServerConnectTask.kt)
- [PinnedTlsSocketFactory.kt](../../app/src/main/java/se/lublin/mumla/util/PinnedTlsSocketFactory.kt)
- [MumlaTrustStore.kt](../../app/src/main/java/se/lublin/mumla/util/MumlaTrustStore.kt)
- [SsrfHostPolicy.kt](../../app/src/main/java/se/lublin/mumla/util/SsrfHostPolicy.kt)
- Net tests: `PingIntervalTest.java`, `AdaptiveKeepaliveTest.java` (full);
  `Mumble15ProtocolTest.java` (lines 1–60, partial)

### Upstream — read

- `src/Mumble.proto` (639 lines) — full read
- `src/MumbleUDP.proto` — full read
- `src/Connection.h` — full read; `src/Connection.cpp` — lines 100–220
  (socketRead, messageToNetwork, sendMessage, disconnectSocket)
- `src/MumbleProtocol.h` — full read (TCP/UDP enums,
  `MAX_UDP_PACKET_SIZE = 1024`, `AudioData`/`PingData`, encoder/decoder roles,
  `PROTOBUF_INTRODUCTION_VERSION = 1.5.0`, `protocolVersionsAreCompatible`)
- `src/PacketDataStream.h` — full read
- `src/Timer.h`, `src/Version.h`, `src/Version.cpp` — full read
- `src/SSL.h` — full read; `src/SSL.cpp` — lines 1–80
  (cipher string, TLS 1.3 suite split)
- `src/crypto/CryptState.h` — full read
- `src/ServerResolver.h` — full read
- `src/mumble/ServerHandler.cpp` — segments: init, udpReady, sendMessage
  UDP/TCP-tunnel, connection setup (TLS protocol, ping ticker),
  sendPing/sendPingInternal, message Ping handling, Version + Authenticate send,
  username/password/tokens, setTokens
- `src/mumble/Settings.h` — `iPingIntervalMsec`, `iConnectionTimeoutDurationMsec`
  via grep
- `src/murmur/` server files — grep-level only
  (`Messages.cpp`, `Server.cpp`, `Meta.cpp` bandwidth/version-gate references)

Not read line by line: `src/Net.cpp`/`src/Net.h`, `src/SSL.cpp` lines 81+,
`src/MumbleProtocol.cpp`, `src/Timer.cpp`, `src/ServerResolver.cpp`,
`src/ServerResolverRecord.*`, `src/crypto/*` implementations,
`src/murmur/Messages.cpp` + `src/murmur/Server.cpp` bodies,
`src/UnresolvedServerAddress.*`, `src/HostAddress.*`, `src/ServerAddress.*`,
and most `libraries/humla`/`app` unit tests (see lane read-state notes in
§4). No claims are made about those bodies beyond cited lines.

## 2. Per-message and per-field parity

Notation: **Supported** = Mumla OLED sends/receives and acts on the field;
**Partial** = parsed/dispatched but some fields unused, or send omits fields
upstream sends; **Missing** = no handler acts and no send path. Dispatch into a
no-op listener override is recorded, never counted as handling.

Upstream field references below are to
[`src/Mumble.proto`](https://github.com/mumble-voip/mumble/blob/master/src/Mumble.proto)
and
[`src/MumbleUDP.proto`](https://github.com/mumble-voip/mumble/blob/master/src/MumbleUDP.proto).

| Upstream message / field | Mumla OLED status | Evidence |
|---|---|---|
| `Version`: `version_v1`, `version_v2`, `release`, `os`, `os_version` | Partial (receive all; send omits `os`, `os_version`) | Receive: `HumlaConnection.kt` `messageVersion` reads `versionV2`/`versionV1`, `release`, `os`, `osVersion`. Send: `HumlaService.kt` builds via `Constants.kt` `createVersionMessage`, which sets `release` + `versionV1`/`versionV2` and documents `os`/`os_version` as omitted. Upstream sends `os`/`os_version` unless hidden (`src/mumble/ServerHandler.cpp` Version + Authenticate send segment). |
| `UDPTunnel.packet` | Supported (receive; send: 3-byte dummy + voice tunnel) | Receive: `HumlaConnection.kt` `onTCPMessageReceived` routes `UDPTunnel` to `onUDPDataReceived`. Send voice: `HumlaConnection.kt` `sendUDPMessage` tunnels when forceTCP/`!mUsingUDP`. Dummy: `enableForceTCP` sends 3-byte `UDPTunnel`. Upstream decodes the TCP tunnel via a dedicated decoder (`ServerHandler.cpp` message UDPTunnel branch). |
| `Authenticate`: `username`, `password`, `tokens`, `celt_versions`, `opus`, `client_type` | Partial (sends username/password/tokens/opus; never sends `celt_versions` or `client_type`; receives into a no-op listener) | Send: `HumlaService.kt` sets username, password, `opus=true`, `addAllTokens`; no `celt_versions`/`client_type` assignment anywhere (repo-wide grep finds no reference outside protobuf stubs). Receive: dispatched to `HumlaTCPMessageListener.messageAuthenticate`, whose only in-tree override is the no-op in `HumlaNetworkListener.kt`. |
| `Ping`: `timestamp`, `good`, `late`, `lost`, `resync`, `udp_packets`, `tcp_packets`, `udp_ping_avg`, `udp_ping_var`, `tcp_ping_avg`, `tcp_ping_var` | Partial (sends fields 1–5 only; receives good/late/lost/resync + timestamp, ignores the six statistics fields) | Send: `HumlaConnection.kt` `sendKeepalivePing` sets timestamp/good/late/lost/resync; no `udp_packets`/`tcp_packets`/avg/var setters. Upstream sends all eleven (`ServerHandler.cpp` sendPingInternal segment). Receive: `HumlaConnection.kt` `messagePing` reads `good`/`late`/`lost`/`resync` + timestamp; the statistics fields are never read (grep). |
| `Reject`: `type` (9 enum values), `reason` | Partial (type+reason surfaced as an exception; no per-`RejectType` branching) | `HumlaConnection.kt` `messageReject` wraps the whole message in `HumlaException(msg)`; no `getType()` switch exists in `libraries/humla` or `app` (grep for `RejectType` finds only the protobuf stub plus the exception constructor). Upstream defines `RejectType` None/WrongVersion/InvalidUsername/WrongUserPW/WrongServerPW/UsernameInUse/ServerFull/NoCertificate/AuthenticatorFail/NoNewConnections. |
| `ServerSync`: `session`, `max_bandwidth`, `welcome_text`, `permissions` | Supported (all four consumed) | `HumlaConnection.kt` `messageServerSync` reads session/maxBandwidth and sets synchronized; `ModelHandler.kt` `messageServerSync` reads session/permissions/welcome_text; `AudioHandler.kt` `messageServerSync` re-applies maxBandwidth. |
| `ChannelRemove.channel_id` | Supported | `ModelHandler.kt` `messageChannelRemove` removes the channel, unlinks it, and notifies. The root channel (id 0) is guarded, not removed. |
| `ChannelState` (all 13 fields: `channel_id`, `parent`, `name`, `links`, `description`, `links_add`, `links_remove`, `temporary`, `position`, `description_hash`, `max_users`, `is_enter_restricted`, `can_enter`) | Supported (all 13 fields read) | `ModelHandler.kt` `messageChannelState` handles every field including links/links_add/links_remove, description versus description_hash, max_users, and enter-restricted/can_enter. |
| `UserRemove`: `session`, `actor`, `reason`, `ban`, plus `ban_certificate`, `ban_ip` | Partial (fields 1–4 consumed; `ban_certificate`, `ban_ip` never read) | `ModelHandler.kt` `messageUserRemove` reads session/actor/reason/ban; no reference to `banCertificate`/`banIp` in `libraries/humla` (grep). |
| `UserState` (session through `listening_volume_adjustment`) | Partial (`plugin_context`, `plugin_identity`, `temporary_access_tokens` parsed but unused for behavior; all others consumed) | `ModelHandler.kt` `messageUserState` consumes fields 1–11, 14–19, 21–23 (including listening volume fan-out). `temporary_access_tokens` is never referenced in `libraries/humla` or `app` (grep). `plugin_context`/`plugin_identity` are never referenced outside protobuf stubs (grep); upstream documents them as not transmitted to clients. `AudioHandler.kt` `messageUserState` consumes session/mute/self_mute/suppress/deaf/self_deaf for the capture gate. |
| `BanList` (`bans`, `query`; `BanEntry` address/mask/name/hash/reason/start/duration) | Missing (dispatched, no handler acts; no send path) | Dispatch exists in `HumlaConnection.kt`; interface method exists; the only override is the no-op `HumlaNetworkListener.kt`. No `BanList.newBuilder` send call in `libraries/humla` or `app` (grep). |
| `TextMessage` (`actor`, `session`, `channel_id`, `tree_id`, `message`) | Supported (receive); no send path in this lane's scope | `ModelHandler.kt` `messageTextMessage` resolves actor/channels/trees/users, drops messages from locally-ignored senders, logs `Message`. No `TextMessage.newBuilder` send in the message paths covered by this lane (send lives in UI/service layers covered by the users/voice/text lane). |
| `PermissionDenied` (`permission`, `channel_id`, `session`, `reason`, `type` with 13 enum values, `name`) | Partial (maps 8 denial types plus a generic fallback; the `permission`/`channel_id`/`session`/`name` fields are not surfaced) | `ModelHandler.kt` `messagePermissionDenied` switches on type for ChannelName/TextTooLong/TemporaryChannel/MissingCertificate/UserName/ChannelFull/NestingLimit, else `reason`; never reads `msg.permission`/`channelId`/`session`/`name`. Upstream `DenyType` values Text/Permission/SuperUser/H9K/ChannelCountLimit/ChannelListenerLimit/UserListenerLimit fall into the generic branch. The `Permissions.kt` bitmask constants exist but are not wired to this message. |
| `ACL` (`channel_id`, `inherit_acls`, `groups`, `acls`, `query`) | Missing (dispatched, no handler acts; no send path) | Same pattern as BanList: dispatch exists, only no-op override. No `ACL.newBuilder` send in scope (grep). |
| `QueryUsers` (`ids`, `names`) | Missing (dispatched, no handler acts; no send path) | Dispatch exists, only no-op override. No `QueryUsers.newBuilder` send in scope (grep). |
| `CryptSetup` (`key`, `client_nonce`, `server_nonce`) | Supported | `HumlaConnection.kt` `messageCryptSetup`: full triple → `setKeys` (16-byte guard); server-nonce-only → `setDecryptIV`; empty → replies with client nonce. Matches the upstream resync semantic (either side may send with only its nonce filled). |
| `ContextActionModify` (`action`, `text`, `context`, `operation` Add/Remove) | Partial (Add and Remove dispatched to separate callbacks; no UI registry in scope) | `HumlaConnection.kt` `broadcastTCPMessage` splits Add → `messageContextActionModify`, Remove → `messageRemoveContextAction`. No further in-scope consumer (grep finds no override outside the no-ops in `HumlaNetworkListener.kt`). |
| `ContextAction` (`session`, `channel_id`, `action`) | Missing receive-side handling; no send path in scope | Dispatched; only no-op override. No `ContextAction.newBuilder` send in scope (grep). |
| `UserList` (`users`: `user_id`, `name`, `last_seen`, `last_channel`) | Missing | Dispatched; only no-op override. No send/consume in scope. |
| `VoiceTarget` (`id`, `targets`: session/channel_id/group/links/children) | Partial (client sends only the target-id byte in voice packets; the `VoiceTarget` TCP message is never sent; receive dispatched but unhandled) | Voice targeting uses the raw `mTargetId` byte in `AudioHandler.kt` `onAudioPacketEncoded`; no `VoiceTarget.newBuilder` construction in scope (grep). Receive: only no-op override. Upstream `Target` subfields have no Mumla OLED-side builder. |
| `PermissionQuery` (`channel_id`, `permissions`, `flush`) | Supported (receive) | `ModelHandler.kt` `messagePermissionQuery` handles flush plus per-channel permissions and root-permissions promotion. No query send path in scope. |
| `CodecVersion` (`alpha`, `beta`, `prefer_alpha`, `opus`) | Partial (only the `opus` flag is consumed; alpha/beta/prefer_alpha read nowhere) | `HumlaConnection.kt` `messageCodecVersion` branches on `opus` only; `AudioHandler.kt` `messageCodecVersion` unconditionally selects Opus. The alpha/beta/prefer_alpha fields are never read in `libraries/humla` (grep). |
| `UserStats` (session, stats_only, certificates, from_client/server, packet counters, ping avg/var, version, celt_versions, address, bandwidth, onlinesecs, idlesecs, strong_certificate, opus, rolling_stats) | Missing | Dispatched; only no-op override. No field reads in scope (grep for `UserStats` finds listener plus stub only). |
| `RequestBlob` (`session_texture`, `session_comment`, `channel_description`) | Missing (dispatched, no handler; Mumla OLED never requests blobs — hashes cached without fetch is handled in the model layer) | Dispatched; only no-op override. `ModelHandler.kt` stores `textureHash`/`commentHash`/`descriptionHash` and clears cached values but contains no `RequestBlob` send (grep finds no `RequestBlob.newBuilder` in repo scope). |
| `ServerConfig` (`max_bandwidth`, `welcome_text`, `allow_html`, `message_length`, `image_message_length`, `max_users`, `recording_allowed`) | Partial (fields 1–2 consumed; 3–7 parsed but unused) | `ModelHandler.kt` `messageServerConfig` wraps the whole message in `ServerSettings`; `AudioHandler.kt` `messageServerConfig` reads `maxBandwidth` only. `allow_html`/`message_length`/`image_message_length`/`max_users`/`recording_allowed` are never read in `libraries/humla` (grep). |
| `SuggestConfig` (`version_v1`, `version_v2`, `positional`, `push_to_talk`) | Missing | Dispatched; only no-op override. No reads in scope. |
| `PluginDataTransmission` (`senderSession`, `receiverSessions`, `data`, `dataID`) | Missing | Dispatched; only no-op override. No reads/sends in scope. |
| `MumbleUDP.Audio` (oneof `target`/`context`, `sender_session`, `frame_number`, `opus_data`, `positional_data`, `volume_adjustment`, `is_terminator`) | Partial (send: target/frame_number/opus_data/is_terminator; receive: sender_session/frame_number/opus_data/context/is_terminator; positional_data and volume_adjustment absent both directions) | Send: `AudioHandler.kt` `onAudioPacketEncoded` protobuf branch sets target (if nonzero), frameNumber, opusData, isTerminator; never sets `positional_data`/`volume_adjustment`/`sender_session`. Receive: `AudioOutput.kt` `queueProtobufVoiceData` reads senderSession/opusData/frameNumber/context/isTerminator; ignores `positional_data`/`volume_adjustment`. Upstream `AudioData` carries position plus volumeAdjustment. Legacy positional trailer (3 floats) is detected and skipped on legacy receive. |
| `MumbleUDP.Ping` (`timestamp`, `request_extended_information`, `server_version_v2`, `user_count`, `max_user_count`, `max_bandwidth_per_user`) | Partial (send: timestamp only; receive: timestamp + server_version_v2 + max_bandwidth_per_user; the request/extended fields unused) | Send: `HumlaConnection.kt` `sendKeepalivePing` protobuf branch sets only `timestamp`. Upstream client sends `requestAdditionalInformation=false`. Receive: `HumlaConnection.kt` `messageProtobufPing` reads timestamp/`serverVersionV2`/`maxBandwidthPerUser`; `user_count`/`max_user_count` never read. Legacy `UDPPing` send/receive of the bare timestamp is retained. |

## 3. Transport behaviors present versus missing

| Behavior | Mumla OLED | Upstream reference |
|---|---|---|
| TCP framing: 2-byte type + 4-byte length, big-endian | Present: `HumlaTCP.kt` read loop `readShort`/`readInt`, `FrameValidator` before allocation | `src/Connection.cpp` socketRead: 6-byte header, `iPacketLength > 0x7fffff` → disconnect; `FrameValidator.kt:MAX_FRAME_BYTES = 0x7fffff` mirrors it |
| TCP frame validation: bad type / negative length / overlarge | Present (all three): `FrameValidator.kt` `BAD_TYPE`/`NEGATIVE_LENGTH`/`OVERLARGE_LENGTH`; `HumlaTCP.kt` read loop aborts on violation | `src/Connection.cpp` disconnects on overlarge; type universe bounded by the TCP enum (0–26). Mumla OLED additionally rejects negative lengths explicitly |
| Send-side overlarge guard | Absent: `HumlaTCP.kt` `sendMessage` (both overloads) writes `serializedSize`/`length` unchecked | `src/Connection.cpp` messageToNetwork returns false when `len > 0x7fffff` |
| TCP send ordering / serialization | Present: per-connection `sendDispatcher` (`limitedParallelism(1)`) + `writeMutex` in `HumlaTCP.kt` | Upstream writes to `QSslSocket`; Qt serializes via the socket buffer |
| UDP socket: connected datagram socket, encrypt-then-send, decrypt-then-dispatch | Present: `HumlaUDP.kt` runUdp (`connect`, encrypt, sender channel; `receive`, decrypt, length `< 5` discard) | `src/mumble/ServerHandler.cpp` udpReady: sender check, `buflen < 5` discard, decrypt, decode |
| UDP oversize-discard | Present on the receive path only via a fixed 2048-byte datagram buffer; no explicit `> MAX` discard branch | `src/mumble/ServerHandler.cpp`: datagrams `> MAX_UDP_PACKET_SIZE` (1024, `src/MumbleProtocol.h`) are discarded. Mumla OLED `BUFFER_SIZE = 2048` (`HumlaUDP.kt`) is twice the upstream maximum |
| UDP sender source/port validation | Absent: Mumla OLED is the client and accepts any decrypted datagram | Upstream server drops datagrams whose sender is not the connected peer |
| UDP → TCP fallback on poor crypt health | Present: `HumlaConnection.kt` `messagePing` switches `mUsingUDP=false` + `enableForceTCP()` when local or remote `good==0` after 20 s; switches back when both `> 3` | `src/mumble/ServerHandler.cpp`: `bUdp=false` when local or remote `good==0` after 20 s (no switch-back branch in the read segment) |
| Dummy UDPTunnel to signal force-TCP | Present: `HumlaConnection.kt` `enableForceTCP` (3-byte tunnel) on `ServerSync` when `shouldForceTCP`, on UDP error, on fallback | Upstream `forcedTcp` gate skips UDP ping so the server keeps voice on TCP |
| UDP hole-punch / initial ping burst | Absent: no dedicated pre-sync UDP knock; the first UDP datagram is the keepalive ping started from `messageServerSync` → `scheduleNextPing(0)` | Upstream client opens a bound UDP socket right after authenticate and begins pinging on the 5 s ticker |
| Crypt resync request (empty CryptSetup on decrypt failure) | Present: `HumlaUDP.kt` receive loop requests resync when `getLastGoodElapsed() > 5000000 && getLastRequestElapsed() > 5000000`; `HumlaConnection.kt` `resyncCryptState` sends empty `CryptSetup` | `src/mumble/ServerHandler.cpp`: identical 5 s / 5 s gate before sending empty `CryptSetup` |
| CryptSetup receive (key + nonces / server-nonce-only / empty) | Present: three-way branch in `HumlaConnection.kt` `messageCryptSetup` with 16-byte guards | Proto semantic; Mumla OLED enforces `AES_BLOCK_SIZE` (16) on each present field |
| OCB2-AES128 encrypt/decrypt, replay/loss stats | Present via the native engine: `CryptState.kt` delegates to `libhumlaaudio.so`; `getGood/getLate/getLost/getResync` synced from native | `src/crypto/CryptState.h`: `PacketStats` good/late/lost/resync plus rolling stats; Mumla OLED mirrors upstream remote stats |
| Keepalive: dual TCP+UDP ping, 5 s bootstrap → 10 s steady | Present: `HumlaConnection.kt` `BOOTSTRAP_PING_INTERVAL_SECONDS=5`, `STEADY_STATE_PING_INTERVAL_SECONDS=10`, `BOOTSTRAP_DURATION_MICROS=30_000_000`; `sendKeepalivePing` sends UDP (protobuf if `isProtobufUdpSupported`, else legacy) + TCP; `scheduleNextPing` self-rescheduling coroutine | Upstream `iPingIntervalMsec = 5000` fixed (`src/mumble/Settings.h`); `sendPingInternal` sends UDP (unless forced-TCP) + TCP. The Mumla OLED 30 s bootstrap plus health-gated 5 s extension has no upstream counterpart in the read segments |
| Keepalive stats accumulation (avg/var, packet counters) | Absent: TCP Ping carries only timestamp/good/late/lost/resync | Upstream `sendPingInternal` accumulates per-direction avg/var plus counters |
| In-flight TCP ping tracking / dead-server disconnect | Absent: no in-flight counter; liveness relies on the 30 s socket read timeout | Upstream disconnects when in-flight TCP pings exceed the maximum, reset on Ping reply |
| TCP connect timeout | Present: `HumlaTCP.kt` `CONNECT_TIMEOUT = 10000` (also used as pre-handshake `soTimeout`) | Upstream `iConnectionTimeoutDurationMsec = 30000` via the connection-timeout timer; Mumla OLED's 10 s is shorter |
| TCP read timeout | Present: `HumlaTCP.kt` `READ_TIMEOUT_MS = 30000` | No direct counterpart found in the read upstream segments (QSslSocket blocking behavior; not verified) |
| Reconnect with backoff | Present: `HumlaService.kt` delays 1000/3000/6000/12000/25000 ms; auto-reconnect only for `CONNECTION_ERROR`; network-available trigger | Upstream address-rotation loop over resolved addresses; backoff policy not in the read segments |
| SRV resolution (`_mumble._tcp`) | Present: `HumlaTCP.kt` runLoop resolves `_mumble._tcp.<host>` via MiniDNS with 800 ms timeout, first sorted record wins, falls back to `DEFAULT_PORT 64738` | Upstream `ServerResolver`/`ServerResolverRecord` not read beyond the header; behavior delta not verified — recorded as unread |
| Protocol version gating (protobuf UDP ≥ 1.5.0) | Present: `Constants.kt` `isProtobufUdpSupported` (v2 ≥ 1.5.0, else legacy v1 ≥ 1.5); `HumlaConnection.kt` branches UDP ping/audio encode + decode on it | `src/MumbleProtocol.h` `PROTOBUF_INTRODUCTION_VERSION = 1.5.0`; encoder/decoder classes version-switch legacy versus protobuf |
| Legacy codec handling (CELT Alpha/Speex/Beta) | Send: Opus only. Receive: explicitly dropped (`HumlaConnection.kt` `broadcastUDPMessage` Unit arm for Alpha/Speex/Beta). 1.2.2 ordinal swap workaround retained (`applyLegacyCodecWorkaround`) | Upstream 1.5 client drops non-Opus voice with a warning; legacy types retained in the protocol header as legacy enums |
| Positional audio (legacy trailer + protobuf field) | Send: absent both encodings. Receive legacy: 12-byte trailer detected and skipped; receive protobuf: `positional_data` ignored | Upstream `AudioData.containsPositionalData` plus position array; legacy encoder appends 3 floats |
| Volume adjustment | Absent both directions (send never sets; `queueProtobufVoiceData` ignores `volume_adjustment`) | Upstream `AudioData.volumeAdjustment`, pre-encoded DB adjustment table |
| Whisper/shout targets, loopback, listening channels | Partial: raw target-id byte sent (`AudioHandler.kt` `mTargetId`); `VoiceTarget` TCP message never built; listen add/remove/volume applied to the model | Upstream reserved target ids (regular speech 0, server loopback 31) and audio contexts (normal/shout/whisper/listen) |
| Channel links | Present in the model (`ModelHandler.kt` links/add/remove) | Upstream `ChannelState.links/links_add/links_remove` |
| Access tokens (post-connect update) | Present: `HumlaConnection.kt` `sendAccessTokens` sends `Authenticate` with tokens only | Upstream `setTokens` sends token-only `Authenticate` |
| mDNS / multi-address rotation | Not observed in scope files | Upstream iterates resolved addresses with a try-next-target-server loop; not verified for Mumla OLED beyond single-host connect |
| QoS / ToS socket options | Absent in scope files | Upstream sets `IP_TOS`/`SO_PRIORITY` on TCP+UDP |
| Image/URL fetching (SSRF policy) | Present: [SsrfHostPolicy.kt](../../app/src/main/java/se/lublin/mumla/util/SsrfHostPolicy.kt) (literal + resolved-address blocklists, redirect handling, single-address pin, redirect cap); [PinnedTlsSocketFactory.kt](../../app/src/main/java/se/lublin/mumla/util/PinnedTlsSocketFactory.kt) (SNI + platform verifier against the original host) | No upstream counterpart in the read segments (client UI concern; upstream not examined) |
| Mumble URL parsing | Present: [MumbleURLParser.kt](../../libraries/humla/src/main/java/se/lublin/humla/util/MumbleURLParser.kt) (scheme, userinfo, bracketed IPv6, port range, path/query ignored) | Upstream URL handling not in the read segments |

## 4. Security and authentication deltas

| Area | Mumla OLED | Upstream reference |
|---|---|---|
| TLS floor | TLS 1.2–1.3 allow-list, fail-closed when the runtime offers neither (`HumlaSSLSocketFactory.kt` `TLS_PROTOCOLS_ALLOWED`, `filterTlsProtocols`, `SSLHandshakeException` on empty) | Client `TlsV1_2OrLater` on newer Qt, older floor otherwise; server `TlsV1_2OrLater` similarly |
| Cipher-suite policy | No cipher-suite configuration; negotiates runtime defaults within the 1.2+ floor | `src/SSL.cpp` pins TLS 1.3 suites plus an `EECDH+AESGCM`-family cipher string, applied via the socket config |
| Server identity: hostname verification | Always verified post-handshake via `TlsHostnameVerifier` (SAN dNSName/iPAddress, CN fallback only when no dNSName SAN, single-label wildcard, IP-literal SAN-byte compare); enforced in `HumlaTCP.kt` `verifyServerIdentity` plus HTTPS endpoint identification on non-onion sockets | Qt `QSslSocket` default verification plus error list delivered to a user prompt; exact matcher not in the read segments |
| Server identity: TOFU pin bypass | Pin match for the expected host skips the SAN check; pins scoped per host alias, compared by leaf SPKI (`HumlaSSLSocketFactory.kt` trust wrapper; `HandshakeFailure.sameSpki`) | No counterpart observed in the read upstream segments |
| Server identity: `.onion` | SAN/endpoint-ID checks skipped; pin-or-nothing (`HumlaTCP.kt` `verifyServerIdentity` returns true for onion before pin/CA checks; `TlsHostnameVerifier.isOnionHost`) | No counterpart observed in the read upstream segments |
| Failure taxonomy | Three distinct reasons (`HandshakeFailure`: `HOSTNAME_MISMATCH`, `UNTRUSTED_ISSUER`, `PIN_CHANGED`, `NONE`) recorded via `setLastHandshakeFailure` and delivered to the UI with chain plus verified host | `QList<QSslError>` delivered to the SSL-error handler; taxonomy not in the read segments |
| Client certificate authentication | PKCS#12 client key loaded into a `KeyManagerFactory("X509")` when configured (constructor; `HumlaConnection.kt` `createSocketFactory`); empty-password default tolerated | Upstream client cert path not in the read segments |
| Self-signed client cert generation | RSA-2048, `CN=Humla Client`, 20-year validity, BasicConstraints CA:false (critical), KeyUsage digitalSignature+keyEncipherment (critical), EKU clientAuth, SKID SHA-1 (`HumlaCertificateGenerator.kt`) | Upstream `SelfSignedCertificate.*` not read — recorded as unread |
| Trust store | Private BKS `mumla-store.bks`, empty integrity password, atomic tmp-file replace (`MumlaTrustStore.kt`); passed as path/password/format into the factory and consulted only for the expected host's alias | Upstream `MumbleSSL::addSystemCA` exists; behavior not read |
| Spoofing guards: frame allocation | `FrameValidator` rejects bad type / negative / `> 0x7fffff` before `ByteArray` allocation (`HumlaTCP.kt` read loop) | Upstream rejects overlarge after reading the header; the negative-length arm has no upstream counterpart (Qt `int` read; the Mumla OLED arm is additional) |
| Spoofing guards: UDP decrypt | Length `< 5` discarded; `CryptState.isValid` gate; decrypt failure counted with 5 s / 5 s resync throttle (`HumlaUDP.kt`); oversize datagrams beyond 2048 bytes truncated by the buffer (no explicit-discard branch) | Upstream: `buflen < 5` discard plus sender-address check plus 5 s / 5 s resync gate; `> 1024` explicit discard |
| Privileged `Reject` handling | `UserRemove` for the own session and any `Reject` terminate via `handleFatalException` (`HumlaConnection.kt`) | `Reject_RejectType` enumeration; per-type UI mapping not mirrored in scope |
| Permission enforcement point | `Permissions.kt` defines bits including `All`; consumed by model/service layers outside this lane's scope | Upstream ACL grant/deny evaluation is server-side; the client only displays |
