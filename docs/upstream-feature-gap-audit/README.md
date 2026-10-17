# Upstream feature-gap audit — Mumla OLED vs Mumble

This folder is a systematic, line-by-line feature-gap audit of Mumla OLED
against upstream Mumble. It records factual deltas only. It does not propose
recommendations, does not rank work, and does not treat any gap as inherently
good or bad. Some gaps reflect deliberate Android-platform differences; others
reflect desktop-only subsystems with no mobile counterpart. The audit does not
distinguish motive — it only records what exists on each side and where.

## Scope

- **Mumla OLED side:** the `app/` Android application plus the in-tree
  `:libraries:humla` core library (protocol engine, background service support,
  JNI audio pipeline over `AudioRecord`/`AudioTrack` with native Opus/RNNoise,
  no Oboe/AAudio), as present on `master` at the time of the audit.
- **Upstream side:** the sibling upstream Mumble checkout, principally
  `src/Mumble.proto`, `src/MumbleUDP.proto`, `src/mumble/` (desktop client),
  and, where connection behavior requires it, `src/murmur/` (server). Upstream
  references below use canonical web URLs or plain-text `src/...` paths, never
  local filesystem paths.

## Method

Seven parallel read-only reviewer lanes each read their assigned Mumla OLED
files line by line and compared them against the corresponding upstream
sources, citing exact file and line evidence on both sides for every claim:

| Report | Lane | Covers |
|---|---|---|
| [01-protocol-transport.md](01-protocol-transport.md) | Protocol / transport | `Mumble.proto` / `MumbleUDP.proto` parity field by field; TCP framing; UDP voice/crypt; TLS; keepalive; reconnect; tokens |
| [02-audio-pipeline.md](02-audio-pipeline.md) | Audio pipeline | Capture/playout, VAD/PTT/continuous, preprocessor (RNNoise, leveler, HW effects), Opus parameter matrix, jitter buffer, mixing, routing |
| [03-channels-acl.md](03-channels-acl.md) | Channels / ACL | Hierarchy, temporary channels, links, listen, pin/hide, ACL permission matrix, tokens, bans, permission-denied taxonomy |
| [04-users-voice-text.md](04-users-voice-text.md) | Users / voice / text | Voice states, whisper/shout targets, text messaging, Markdown/HTML, images, links, notifications, TTS, friends/ignore, user info |
| [05-servers-identity-admin.md](05-servers-identity-admin.md) | Servers / identity / admin | Server list, public list, `mumble://` URL params, SRV, connect flow, certificates, trust store, registration, kick/ban |
| [06-settings-ui-platform.md](06-settings-ui-platform.md) | Settings / UI / platform | All preference screens and keys, drawer/activity UI, overlay/HotCorner, notifications, shortcuts, Intent API, platform integrations |
| [07-desktop-only-sweep.md](07-desktop-only-sweep.md) | Desktop-only sweep | Exhaustive per-file inventory of upstream `src/mumble/` so nothing lanes 1–6 missed goes unrecorded |

Status vocabulary used throughout:

- **Supported** — the behavior is present in Mumla OLED with evidence cited.
- **Partial** — the behavior is present but narrower than upstream
  (fewer fields, fewer modes, UI-unreachable send path, inbound-only, etc.).
- **Missing** — no counterpart was found in the searched Mumla OLED scope.
- **Absent** (sweep report) — zero matches for the named upstream file or
  symbol in `app/` plus `libraries/humla/`.

## How to read the parity tables

Every row cites evidence as `path:line` or `path` symbol references. Mumla OLED
paths are portable relative links into this repository (e.g.
[HumlaConnection](../../libraries/humla/src/main/java/se/lublin/humla/net/HumlaConnection.kt)).
Upstream paths are plain-text `src/...` references that resolve to
[`github.com/mumble-voip/mumble`](https://github.com/mumble-voip/mumble)
(e.g. `src/mumble/ServerHandler.cpp`), with
[`src/Mumble.proto`](https://github.com/mumble-voip/mumble/blob/master/src/Mumble.proto)
and
[`src/MumbleUDP.proto`](https://github.com/mumble-voip/mumble/blob/master/src/MumbleUDP.proto)
linked where the protobuf schema is the authority.

Listener-interface presence alone (a no-op override) is never counted as
handling. "No send path in scope" means no `newBuilder` construction call for
that message was found by repository-wide grep at audit time.

## Limitations

- Each lane lists its own unread files explicitly at the end of its report.
  Claims are scoped to what was read plus repository-wide grep for the relevant
  symbols; bodies that were only grep-sampled are noted as such.
- Upstream `murmur/` server internals were sampled only where client-visible
  behavior required it. Mumla OLED is a client only; the absence of any
  server-side subsystem is recorded once in the sweep rather than repeated.
- Line numbers refer to the tree state at audit time and will drift. File
  paths are the stable part of each citation.
- No tests were run for this audit. It is a code-reading comparison, not a
  behavioral test report.
