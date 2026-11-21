# 35 — the legacy server version is plumbed to the voice layer but unread

| | |
|---|---|
| Status | Open; deletion only |
| Category | Benign drift |
| Severity | Benign (dead parameter chain; one misleading spec sentence) |
| Verified | Yes, by search of `libraries/humla/src/main` for `legacyServerVersion` after the codec tail commits |
| Area | Transport, voice |
| Spec | [voice.md § Kotlin facade](../voice.md#kotlin-facade), [transport.md § UDP packet format](../transport.md#udp-packet-format) |
| Code | `net/Transport.kt` (`VoiceFormat.legacyServerVersion`), `client/HumlaClient.kt` (`transportVoiceFormat`), `voice/VoiceEngine.kt`, `voice/NativeVoiceEngine.kt` (`setFormat`), `jni/voice_io/NativeVoiceEngineJni.cpp` (`nativeSetFormat`), `jni/voice_io/VoiceReceive.{h,cpp}`, `jni/voice_io/VoiceSend.{h,cpp}` |
| Gates | Not gating |

## Summary

`VoiceFormat` was introduced with two fields: the UDP format and the
server's legacy version, which keyed the 1.2.2 CELT ordinal swap. The swap
was dead (the legacy format only ever yields `VoiceOpus`) and was deleted
with the [codec tail](../open-items.md#resolved), so nothing reads the
version any more. What remains is a parameter chain with no reader:
`VoiceFormat.legacyServerVersion` → `VoiceCommand.Start` →
`NativeVoiceEngine.setFormat` → `nativeSetFormat` →
`VoiceReceive::m_legacyVersion` (stored, never consulted; `VoiceSend`
already documents its copy as retained only so one `setFormat` covers both
halves).

The transport still *decides* the format from the same `Version` message,
so the decision itself stays; only the version carried beside it is
unused.

## Fix directions

Delete the field and the parameters it feeds, which leaves `VoiceFormat` a
one-field wrapper — either fold it into `UdpFormat` at `VoiceCommand.Start`
and `Transport.voiceFormat`, or keep the type as the session's fixed
format decision. Either way `transport.md`'s `VoiceFormat` sketch and the
`voice.md` facade sketch lose the field.

The alternative, if a future step needs the version again (the pre-1.5
branch of upstream's listener-volume handling is version-keyed), is to
leave the plumbing and give it a reader then. Recording it here keeps the
choice explicit instead of leaving the parameter chain to rot.

## Acceptance criteria

No `legacyServerVersion` in `libraries/humla/src/main`, or a reader for it
in the codec or an explicitly documented deferred home; `transport.md` and
`voice.md` sketches match the code; `./scripts/check.sh` green.
