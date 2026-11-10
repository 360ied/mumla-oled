# Transport

Part of the [target architecture](README.md). Specifies the TCP control
channel, keepalive and UDP-health logic, crypt setup, and tunnel mode. The
UDP socket itself belongs to the [`VoiceEngine`](voice.md); the transport
decides *how* voice travels and passes keys and tunnelled packets to it.

## Responsibilities

| Concern | Owner |
|---|---|
| DNS (SRV then A/AAAA), TLS socket, certificate verification | Transport |
| TCP framing, inbound parsing, outbound queue | Transport |
| `Ping` over TCP, TCP latency, remote crypt statistics | Transport |
| UDP vs TCP decision for voice | Transport (from voice's UDP ping results) |
| UDP socket, UDP ping send and receive, voice encryption | `VoiceEngine` |
| Crypt keys and resync | Transport receives `CryptSetup`, `VoiceEngine` holds the keys |

The transport lives in `se.lublin.humla.net` and is owned by one
`Session`. It talks to the voice side only through `VoiceLink`, an
interface the `VoiceEngine` implements (`setKeys`, `setDecryptIv`,
`encryptIv`, `submit(SetVoiceRoute)`, `submit(SendPing)`,
`queueTunnelled`, and the `UdpPing` and `CryptResyncRequested` events), so
transport tests use a fake. It reuses today's framing, SRV lookup
([`HumlaTCP`](../../../libraries/humla/src/main/java/se/lublin/humla/net/HumlaTCP.kt)),
frame validation
([`FrameValidator`](../../../libraries/humla/src/main/java/se/lublin/humla/net/FrameValidator.kt)),
and TLS
([`HumlaSSLSocketFactory`](../../../libraries/humla/src/main/java/se/lublin/humla/net/HumlaSSLSocketFactory.kt),
[`TlsHostnameVerifier`](../../../libraries/humla/src/main/java/se/lublin/humla/net/TlsHostnameVerifier.kt))
unchanged in behavior.

```kotlin
interface Transport {
    val inbound: ReceiveChannel<Inbound>      // parsed messages, server order
    val stats: StateFlow<TransportStats>
    fun send(message: MessageLite, type: HumlaTCPMessageType)   // never blocks
    val cryptKeys: StateFlow<CryptKeys?>      // latest full key set from CryptSetup
}

sealed interface Inbound {
    data class Message(val type: HumlaTCPMessageType, val message: MessageLite) : Inbound
    data class Tunnelled(val packet: ByteArray) : Inbound        // UDPTunnel payload
}
```

## Threads

- **Reader thread** (one per session, a plain `Thread`; on the allowlist):
  blocking reads of the 6-byte header (2-byte type, 4-byte length,
  big-endian), validation with `FrameValidator` (unknown type or length
  over `MAX_FRAME_BYTES` is a protocol violation that fails the session),
  payload read, protobuf parse, and `trySend` into `inbound`. `UDPTunnel`
  payloads are not parsed; they are delivered as `Inbound.Tunnelled`.
- **Writer coroutine** on the I/O dispatcher, draining a bounded outbound
  channel (capacity 256) and writing frames. `send` uses `trySend`; a full
  channel means the server has stopped reading for a long time, and fails
  the session with `ClientError.Connection`.
- **Ping coroutine** in the session scope (see below).

`inbound` has capacity 1024. The reader uses `trySendBlocking`, which
blocks the reader thread while the channel is full, so a backlog in the
session's message coroutine leaves bytes in the socket buffer and pushes
back on the server through TCP flow control. Nothing is dropped.

Cancelling the session scope closes the socket from a completion handler,
which unblocks the reader. The reader catches the resulting exception and
exits without reporting a failure (the session is already ending).

## TLS

- Client certificate: from `ConnectTarget.certificate` (PKCS#12 bytes and
  password), as today.
- Trust: the system trust store plus the app trust store
  (`MumlaTrustStore`, pinned certificates), as today.
- Verification failure: the transport captures the chain, the
  `HandshakeFailure` (`HOSTNAME_MISMATCH`, `UNTRUSTED_ISSUER`,
  `PIN_CHANGED`), and the host verification ran against, and fails the
  session with `ClientError.Trust`. It never reconnects; the app shows the
  existing trust dialogs from `ClientState.Failed` and, if the user pins,
  calls `connect` again.

## Keepalive and UDP health

Ported from
[`HumlaConnection`](../../../libraries/humla/src/main/java/se/lublin/humla/net/HumlaConnection.kt)
with the same constants:

- **Cadence:** a ping every 5 s for the first 30 s after `ServerSync`, and
  afterwards whenever UDP is in use but either side's good-packet count is
  3 or fewer; otherwise every 10 s.
- **Each ping round:** a TCP `Ping` (timestamp in microseconds since the
  session started, plus local crypt statistics from the `VoiceEngine`), and
  a request to the `VoiceEngine` to send a UDP ping (protobuf or legacy
  format, matching the voice format).
- **TCP `Ping` reply:** sets TCP latency and the remote crypt statistics.
- **UDP ping reply** (reported by the `VoiceEngine`): sets UDP latency;
  a protobuf ping may also update the server version and max bandwidth.
- **Switch to TCP:** when UDP is in use, more than 20 s have passed, and
  either side reports zero good packets. The transport sends the dummy
  `UDPTunnel` message (3 zero bytes) that tells the server to tunnel, and
  tells the `VoiceEngine` to use tunnel mode.
- **Switch back to UDP:** when both sides report more than 3 good packets.
- **Forced TCP:** with `ConnectTarget.forceTcp`, voice is tunnelled from
  `ServerSync` on, the dummy `UDPTunnel` is sent at `ServerSync`, and UDP is
  never used.

The current UDP/TCP decision is published in `TransportStats.usingUdp` and
pushed to the `VoiceEngine` with `VoiceCommand.SetVoiceRoute(udp: Boolean)`.

## UDP packet format

Chosen once per session from the server version (`Version` message), with
today's rule (`Constants.isProtobufUdpSupported`): protobuf UDP when the
server version is at least 1.5.0, legacy otherwise. Passed to the
`VoiceEngine` in `VoiceCommand.Start`. The 1.2.2 legacy codec workaround
(`applyLegacyCodecWorkaround`) moves into the native packet codec, keyed by
the same version.

## Crypt setup

`CryptSetup` handling is unchanged in protocol terms, but keys now go to
the `VoiceEngine` instead of a Kotlin `CryptState`:

| `CryptSetup` contents | Action |
|---|---|
| `key`, `client_nonce`, `server_nonce` (each 16 bytes) | Publish `CryptKeys`; if voice is running, `VoiceLink.setKeys` |
| `server_nonce` only (16 bytes) | Resync: `VoiceLink.setDecryptIv` |
| Empty | The server asks for our nonce: reply with `CryptSetup(client_nonce = VoiceLink.encryptIv())` |
| Any other size | Protocol violation: fail the session |

The client's `VoiceCommand.Start` includes the keys from `cryptKeys`, so a
`CryptSetup` that arrives before voice starts is never lost.

**Client-requested resync** is kept exactly as today's `HumlaUDP` does it
(and as desktop Mumble's `ServerHandler` does): when a packet fails to
decrypt, more than 5 s have passed since the last good packet, and more
than 5 s since the last request, the receive thread emits
`VoiceEvent.CryptResyncRequested`. The transport answers with an empty
`CryptSetup`, which asks the server to send a fresh `server_nonce`. The
receive thread keeps the last-request time, so requests are rate-limited at
the source.

## Tunnel mode

When voice is tunnelled:
- **Outbound:** the `VoiceEngine` hands framed, unencrypted voice packets to
  Kotlin through its notifier thread; the transport sends each as a
  `UDPTunnel` message.
- **Inbound:** `Inbound.Tunnelled` packets go straight from the session's
  message coroutine to `VoiceEngine.queueTunnelled(packet)`, which pushes
  them onto the voice I/O layer's tunnel inbound queue. They are never
  decrypted (TLS already protects them).

## Statistics

```kotlin
data class TransportStats(
    val tcpLatencyMicros: Long?,
    val udpLatencyMicros: Long?,
    val usingUdp: Boolean,
    val remoteCrypt: CryptCounts,             // from TCP Ping replies
    val maxBandwidth: Int?,
)
```

The session merges these with the `VoiceEngine`'s local crypt counts into
`SessionStats` (see [client](client.md#statistics)).
