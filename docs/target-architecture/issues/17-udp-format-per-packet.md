# 17 — UDP packet format is re-decided per packet, via an extra `VoiceLink` method

| | |
|---|---|
| Status | Resolved 2026-11-14 (`aba7011b`): the transport fixes the format at the first `Version` and voice receives it at start. See [Resolution](#resolution). |
| Category | Undocumented interim |
| Severity | Low today (unreachable in practice); relevant to the 5a.3 contract |
| Verified | Yes (code read) |
| Area | Transport / voice link |
| Spec | [transport.md § UDP packet format](../transport.md#udp-packet-format), [transport.md § Responsibilities](../transport.md#responsibilities) (`VoiceLink` interface) |
| Code | `libraries/humla/src/main/java/se/lublin/humla/net/TransportImpl.kt` (`handleVersion`, around lines 478-489); `net/Transport.kt` (`VoiceLink.setServerVersion`); `net/LegacyVoiceLink.kt` (`setServerVersion` line 104, `sendUdpPing` line 239, `isProtobufUdpSupported` line 258, `onUDPDataReceived` around lines 262-295) |
| Gates | Gated step 5a.3b; gate met |

Line numbers below refer to `master` at `092d9dc8`.

## Summary

The spec says the UDP packet format (legacy vs protobuf) is chosen once per
session from the server's `Version` and passed to voice at start. In the
code, the transport forwards raw version numbers through
`VoiceLink.setServerVersion(v2, v1)`, a method the spec's interface does
not list, and `LegacyVoiceLink` re-evaluates
`Constants.isProtobufUdpSupported(...)` on **every outgoing ping and every
received datagram**. It also overwrites its stored `serverVersionV2` from
the `server_version_v2` field of incoming protobuf UDP pings.

## What the spec says

`transport.md` § UDP packet format: chosen "once per session from the
server version (`Version` message) ... Passed to the `VoiceEngine` in
`VoiceCommand.Start`". The `VoiceLink` interface is enumerated as
`setKeys`, `setDecryptIv`, `encryptIv`, `submit(SetVoiceRoute|SendPing)`,
`queueTunnelled`, plus the events.

## What the code does

`TransportImpl.handleVersion` (around line 478) derives `v2`/`v1` and calls
`voice.setServerVersion(v2, v1)`.

`LegacyVoiceLink`:

```kotlin
override fun setServerVersion(v2: Long, v1: Int) {         // line 104
    serverVersionV2.set(v2); serverVersion.set(v1)
}

fun isProtobufUdpSupported(): Boolean =                    // line 258
    Constants.isProtobufUdpSupported(serverVersionV2.get(), serverVersion.get())

private fun sendUdpPing(...) {                             // line 239
    ...
    if (isProtobufUdpSupported()) { /* 0x01 + MumbleUDP.Ping */ } else { /* legacy */ }
}

override fun onUDPDataReceived(data: ByteArray) {          // around line 264
    if (isProtobufUdpSupported()) {
        ...
        } else if (msgType == 1) {   // protobuf ping
            ...
            if (v2 != null) serverVersionV2.set(v2)        // line ~289
```

## Impact

- In practice, servers send `Version` once and UDP pings report the same
  version, so the format does not flip. If it ever did (a misbehaving
  server, a version field disagreeing with the TCP `Version`), the framing
  and parsing format would flip under a live stream and most packets would
  be dropped silently.
- The `VoiceLink` interface has grown a method the spec does not describe,
  and the spec's "once per session, in `Start`" contract is not what 5a.3
  will replace.

## Fix directions

1. Decide the format once in the transport at `Version` time (or at voice
   start) and pass it to the link (interim: a `setUdpFormat(format)` or as
   a `startVoice` argument; target: `VoiceCommand.Start`).
2. Remove the per-packet re-evaluation and the overwrite from UDP pings in
   `LegacyVoiceLink`; UDP ping version fields can still be reported as
   statistics (`VoiceEvent.UdpPing` already carries them).
3. Update the `VoiceLink` interface in `transport.md` (on `master` first) if
   an interim method is kept.

## Acceptance

The format is fixed for a session after `Version`; a test shows a UDP ping
with a different `server_version_v2` does not change the format.
`./scripts/check.sh` passes.

## Resolution

The state at `aba7011b`.

- **Decided once, in the transport.** `TransportImpl.handleVersion`
  stores a `VoiceFormat(udp: UdpFormat, legacyServerVersion: Int)` from
  the first `Version` (compare-and-set; a repeat is logged and ignored)
  and exposes it as `Transport.voiceFormat`, `VoiceFormat.DEFAULT`
  (legacy, version 0, today's default) until then.
- **Passed at start.** `VoiceLink.setServerVersion` is gone.
  `VoiceLifecycle.startVoice(host, port, format)` is the interim
  `VoiceCommand.Start`, and `HumlaClient` reads the format from the
  transport. `LegacyVoiceLink` reads the format once per datagram and
  parses the whole datagram in it; the 1.2.2 workaround takes
  `legacyServerVersion` from the same value.
- **Pings report, never apply.** The protobuf UDP ping's
  `server_version_v2` still reaches `VoiceEvent.UdpPing` and
  `ServerState.info`, but no longer touches the format.
- **A second flip path, not in the summary above.** `VoiceControl` chose
  `AudioHandler`'s send framing from `ServerState.info.versionV2`, which
  UDP pings rewrite, so an `AudioHandler` rebuild after such a ping could
  flip the send format. It now reads `session.transport.voiceFormat`.
- **Spec.** [transport](../transport.md#udp-packet-format) names the
  transport as the decider and `voiceFormat` as the source for `Start`,
  and rules out `ServerState.info` as a source.
  [client](../client.md#start-sequence) step 7 says the same.

**Pinned by** `TransportImplTest.voiceFormatIsFixedByTheFirstVersion`
and `legacyServerVersionPicksLegacyFormat`,
`LegacyVoiceLinkContractTest.udpPingVersionDoesNotChangeTheFormat` (the
acceptance test: a 1.4.0 ping on a 1.5.0 session leaves the format
protobuf and protobuf voice still parses), and
`HumlaClientTest.connectSyncDisconnect` (the transport's format reaches
`startVoice`). **Shown to fail** with last-`Version`-wins, with the ping
rewriting the format, and with the client passing the default format.
**Not pinned:** the `VoiceControl` read, since `VoiceControlTest` never
starts a session ([03](03-voice-control-test-vacuous.md)).

## Related

- [16](16-voice-link-owns-udp-route.md)
