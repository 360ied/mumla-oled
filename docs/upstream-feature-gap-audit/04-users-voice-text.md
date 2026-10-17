# 04 — Users, voice states, whisper/shout, text messaging

Factual parity audit of the user model, voice states, whisper/shout targets,
text messaging and chat, rendering and filtering, images, links, notifications,
and TTS in Mumla OLED versus upstream Mumble. No recommendations, no judgments.

## 1. Scope and files read

Repo-local files, all read:

- `libraries/humla/.../model/User.kt` (full)
- `libraries/humla/.../model/IUser.kt` (full)
- `libraries/humla/.../model/TalkState.kt` (full: talking/shouting/passive/
  whispering plus Parcel clamp)
- `libraries/humla/.../model/Message.kt`, `IMessage.kt` (full)
- `libraries/humla/.../model/WhisperTarget.kt`, `WhisperTargetList.kt`
  (target ids 1–30, bit-vector), `WhisperTargetChannel.kt`,
  `WhisperTargetUsers.kt` (stub)
- `libraries/humla/.../util/VoiceTargetMode.kt` (normal 0, whisper 1–30,
  server loopback 31)
- `libraries/humla/.../protocol/ModelHandler.kt` (`messageUserState`,
  `messageUserRemove`, `messageTextMessage`, `messageServerSync/Config`,
  `messageChannelRemove`, listening add/remove/volume-adjustment blocks)
- `libraries/humla/.../protocol/AudioHandler.kt` (init/mute-gate,
  `messageUserState`, voice-target setters, optimistic self-mute, Builder,
  `onAudioPacketEncoded` target encoding)
- `libraries/humla/.../HumlaService.kt` (`registerWhisperTarget`/
  `unregisterWhisperTarget`, voice-target getters, listening setters, text
  senders, comment/avatar/registration/kick/move/mute setters, link/unlink)
- `app/.../channel/UserMenu.kt` (prepare + click dispatch, full)
- `app/.../channel/ChannelMenu.kt` (listen + shout blocks)
- `app/.../channel/ChannelFragment.kt` (whisper target panel +
  `onVoiceTargetChanged`)
- `app/.../channel/ChannelChatFragment.kt` (full including adapter view,
  link sanitizing, outgoing markup, image send path, send routing)
- `app/.../channel/ChatTargetProvider.kt` (single user-or-channel target),
  `ChatTargetActionModeCallback.kt` (full)
- `app/.../channel/AvatarCache.kt` (4 MB LRU, 512 px bound)
- `app/.../channel/comment/UserCommentFragment.kt` (full)
- `libraries/humla/.../util/MessageFormatter.kt` (highlight only), `HtmlUtils.kt`
  (hostname extraction), `MumbleImageGetter.kt` (data-URI sync plus
  http/https async, SSRF pinning, bounds), `ChatLinkPolicy.kt` (http/https
  allowlist), `NotificationSanitizer.kt` (strip + actor length cap),
  `BitmapUtils.kt` (full)
- `app/.../service/MumlaMessageNotification.kt` (InboxStyle,
  POST_NOTIFICATIONS gate), `app/.../service/MumlaService.kt` (observer
  message-logged TTS + notify + log, talk-state handling, TTS init/speak,
  500-message/10-server log caps, send overrides, talk-key handling)
- `app/.../service/IChatMessage.kt` (TextMessage plus info severities),
  `service/ipc/TalkBroadcastReceiver.kt` (PTT-only talk on/off/toggle)

Upstream spot-reads: `src/User.h`, `src/ClientUser.h/.cpp`,
`src/mumble/Messages.cpp` (UserState/recording/priority/mute-deaf/text/user-stats/
suggest/plugin), `src/mumble/Markdown.h/.cpp`, `src/HTMLFilter.h/.cpp`,
`src/mumble/Log.h/.cpp` (allowed schemes, valid HTML, image rendering, user and
channel formatting), `src/mumble/UserInformation.h/.cpp`,
`src/mumble/MainWindow.cpp` (user/channel menu actions, chat-bar send paths),
`src/mumble/ServerHandler.cpp` (send/listening/register/kick/ban paths),
`src/mumble/UserLocalVolumeSlider.h`, global-shortcut target UI strings.
`src/Mumble.proto` / `src/MumbleUDP.proto` not re-read in this lane; field
names cited from call sites.

## 2. User flags

| Capability | Mumla OLED | Upstream | Delta |
|---|---|---|---|
| Self mute/deaf fields | `User.kt` `isSelfMuted`/`isSelfDeafened` with coercing setters (unmute clears deafen; deafen sets mute). `HumlaService.setSelfMuteDeafState` coerces centrally then applies optimistic self-mute/deafen | `User.h` self-mute/self-deaf; `ClientUser.cpp` same coercions; `ServerHandler.cpp` sends both | Parity on coercion semantics; Mumla OLED adds an optimistic gate plus a model mirror before echo |
| Server mute/deaf/suppress fields | `User.kt` `isMuted` (unmute clears deafen), `isDeafened` (deaf sets mute), `isSuppressed`; `ModelHandler.messageUserState` applies mute-then-deaf order | `User.h` mute/deaf/suppress; `ClientUser.cpp` same order; murmur messages layer same | Same order and coercion |
| Priority speaker | `User.isPrioritySpeaker`; service sends `priority_speaker`; model applies on `hasPrioritySpeaker`; menu toggle gated on Write/MuteDeafen | `User.h` priority flag; 6 actor-combination log lines (self/other × give/revoke/self-assume); self plus other menu actions | Mumla OLED transports and toggles the flag but has no priority-speaker log lines (the branch only sets the field) |
| Recording flag | `User.isRecording`; model logs self start/stop plus linked-channel other start/stop | Upstream logs self start/stop plus linked-channel other start/stop under the recording log type | Parity on condition shape (self versus linked-channel other) |
| Talk-state enum | `TalkState.kt` talking/shouting/passive/whispering plus a Parcel-corruption clamp to passive | Upstream talk states passive/talking/whispering/shouting; whisper mapped from the whisper flag, loopback/shout mapped to shouting | Same 4 states; Mumla OLED adds a parcel-corruption clamp (transport differs) |
| Self talk-state → UI/cue | Service maps talking/shouting/whispering to talking; drives the hot corner plus PTT cue when enabled | Talking list plus overlay sort plus attenuation plus plugin notification on talk-state change | Mumla OLED covers the local cue/hot-corner only; no talking-list model, overlay sort, or per-user attenuation in scope |
| Local mute (audio) | `User.isLocalMuted`; menu toggles the flag plus notifies; the change observer in `ChannelListAdapter.kt` persists it to the database (add/remove local-muted user) and the model restores from the user-id-keyed local-mute history seeded from the database at connect | Upstream local mute persisted via cert-hash-keyed database, restored by hash plus friend/volume/nickname | Key differs: Mumla OLED user-id history list versus upstream cert-hash database. The `UserMenu.kt` method body itself is memory-only; persistence lives in the list-adapter observer |
| Local ignore (text) | `User.isLocalIgnored`; `ModelHandler.messageTextMessage` drops when the sender is locally ignored; menu ignore-messages toggle; persisted and restored via the same database-backed history path as local mute | Upstream drops text from locally ignored senders; persists via hash-keyed database with a "not permanent when hash empty" warning | Same drop semantics; Mumla OLED database-backed user-id history versus hash-keyed database |
| Local ignore TTS | Absent as a field. The service observer has no per-sender TTS-ignore check; only global TTS enabled, length cap, and self-deafened gates | Upstream local-ignore-TTS flag plus database persistence; passed as ignore-TTS into the log call; separate menu action | A sender ignored for TTS upstream still speaks in Mumla OLED unless globally gated |
| Local volume per user | Absent. No local-volume field on user/session interfaces; no slider/menu item; only listener-channel volume exists | Upstream per-user local volume plus slider widget, hash-keyed persistence, per-user volume submenu | No per-user local volume; listener-channel volume only (see §3) |
| Local nickname | Absent. No nickname field/menu | Upstream local nickname plus setter, persistence, dialog | Absent in Mumla OLED |
| Friend list | Absent. No friend field, menu, or database table in scope; no flags-string equivalent | Upstream friend name, add/remove/get, menu add/update/remove, model friend name, flags string, "only accept whispers from friends" setting | Absent in Mumla OLED |

## 3. Listening (deafened-channel listeners)

| Capability | Mumla OLED | Upstream | Delta |
|---|---|---|---|
| Listener channels | `User.listeningChannelSet` plus `isListeningTo/listeningChannels`; model handles listen add/remove (plus self channel-mirror and observer fan-out) and listening volume adjustment (self-only fan-out); service listen setters; menu toggle | Upstream listener manager (`isListening`), server-handler start/stop, log lines for add/remove, menu listen action | Transport parity; Mumla OLED adds a listening-volume-adjustment receive path (upstream volume path lives in volume-adjustment/audio-output, not verified here). Mumla OLED logs nothing for listen add/remove; upstream logs both |

## 4. Voice targeting — whisper versus shout

| Capability | Mumla OLED | Upstream | Delta |
|---|---|---|---|
| Target id space | `VoiceTargetMode.fromId`: 0 normal, 1–30 whisper, 31 server loopback; `WhisperTargetList` min 1 / max 30, bit-vector taken ids | Voice-target ids 0–31 (5-bit; 0 normal, 31 loopback by convention); input target plus previous target, global-shortcut whisper slots | Same id space |
| Shout to channel (linked/subtree/group) | `WhisperTargetChannel(channel, includeLinked, includeSubchannels, groupRestriction)` builds the target with links/children/group/channel fields; menu shout dialog (subchannel + linked checkboxes, group always null) → register target → set voice-target id; fragment target panel shows the name plus cancel restores id 0 and unregisters. `AudioHandler.onAudioPacketEncoded` stamps the target id (legacy low-5-bit / protobuf target); terminator-for-whisper comment preserved | Global-shortcut target editor: channel target plus shout-links/shout-subchannels checkboxes plus group restriction ("only members of this group receive the whisper") plus positional-disable flag; shortcuts persist; whisper/shout framing plus terminator with previous target | Group restriction hard-wired null at the only construction site; no positional-disable flag; no multi-shortcut persistence (single live slot, unregistered on cancel) |
| Whisper to user (per-user target) | `WhisperTargetUsers.createTarget/name` both throw `UnsupportedOperationException` (explicit TODO). No construction site; no menu | Per-user whisper targets via the shortcut editor (user list targets); whisper-only audibility rules in audio output | Per-user voice targets unimplemented (explicit stub) |
| Recursive/subtree voice | Via `includeSubchannels` (children flag) | Same children semantic checkbox | Parity at protocol level |
| Persistent versus one-shot | Persistent until the user cancels (set id 0 + unregister) or replaces (existing whisper slot unregistered first). No one-shot provided | Shortcut-held whisper (one-shot while held) plus persistent toggle variants via press/release | Persistent-only; no hold-to-whisper one-shot |
| Incoming whisper/shout audibility | Not in scope files; whispering/shouting states exist; receive-side listener rules unaudited in this lane | Linked-channel audio plus whisper/shout states for audibility; "only accept whispers from friends" setting | Receive-side whisper filtering unverified in this lane; no friends-only-whisper setting found |
| Loopback (id 31) | `VoiceTargetMode.SERVER_LOOPBACK` parsed; `setVoiceTargetId` allows up to `0x1F` (31); no UI sets 31 | Server/local loopback and test paths | Decoded but not surfaced in Mumla OLED UI |

### Whisper-target matrix

| Target kind | Create in Mumla OLED | Protocol fields set | Tear-down | Upstream equivalent |
|---|---|---|---|---|
| Channel, no flags | Shout dialog with both boxes unchecked → channel target, no flags → id 1–30 | channel only | Cancel → id 0 + unregister; replace unregisters first | Same `VoiceTarget.Target{channel_id}` |
| Channel + linked | Linked box → includeLinked → `setLinks(true)` | links | Same | Shout-links checkbox |
| Channel + subchannels | Subchannel box → includeSubchannels → `setChildren(true)` | children | Same | Shout-subchannels checkbox |
| Channel + group | Never (always null) | group never set | n/a | Group-restriction line edit |
| Channel + no-positional | No such parameter | n/a | n/a | "Do not send positional audio" checkbox |
| Users (one or more) | Impossible (both methods throw) | n/a | n/a | User-list whisper target |
| Loopback 31 | Impossible via UI | n/a | n/a | Test/loopback paths |
| Slot exhaustion | `registerWhisperTarget` returns -1 → shout-failed toast | n/a | `free(slot)` nulls slot plus clears bit | Fixed slot list in the shortcut config |

## 5. Text messaging

| Capability | Mumla OLED | Upstream | Delta |
|---|---|---|---|
| Send to user | `HumlaService.sendUserTextMessage(session,msg)` → `TextMessage{session[],message}`; chat fragment routes a user target to it; service override appends the self copy to the log. Target provider holds one user; set via long-press action mode | `ServerHandler.cpp` user-text-message send; per-user text dialog; chat-bar selection mode routes to user versus channel | Parity on transport; Mumla OLED target selection is list-driven action mode, upstream dialog plus chat-bar selection |
| Send to channel | `sendChannelTextMessage(channel,msg,false)` from the chat fragment (default and explicit channel targets both pass false) | Channel send with a tree flag; chat-bar send passes false for the channel case | Same default; the tree flag exists in the Mumla OLED API but no chat send site passes true |
| Send to tree/subtree | API supports `tree=true` (maps to the tree-id list); zero UI reachability (both chat send sites pass false; the target provider has no tree target). Receive side renders the first tree target | Chat bar sends channel-only; tree sends originate elsewhere (ACL/channel flows, not the chat bar). The log prefixes "(Tree)" | Mumla OLED can receive and display tree messages but cannot author tree messages from UI |
| Send to multiple/recursive listeners | No multi-target compose; one user XOR one channel per target | Chat bar single-target as well; the protocol supports channel/tree/session lists | Parity (single-target compose) |
| Receive user/channel/tree | Model resolves channel/tree/session ids to objects, drops unknown ids silently, drops all when the sender is locally ignored; service observer → TTS + notification + log append | Resolves; drops when locally ignored; prefixes channel/tree/private; logs private versus channel text with separate per-type toggles | Mumla OLED drops unresolvable ids silently (upstream keeps raw ids for display — display uses resolved objects in both; beyond-prefix behavior unverified). No private-versus-channel log-type split |
| Offline messages | No offline queue/store found in scope; the message log is memory-only per server key; clearing nulls the log | No offline text spool either (server does not spool text; the log is session-local) | Parity (none) |
| History size/persistence | 500-message cap (evict head), 10-server LRU keyed by server key (password-excluded), unmodifiable-copy getter, clear clears plus removes the server key; fragment menu clears adapter plus service log | Session-memory log with per-type message limiting and per-type ignore/TTS/sound config | Different scheme: bounded body cache per server versus unbounded session view with per-type rate limiting |
| Kick/ban/move effects on chat | Model logs kick/ban/self variants; channel-move logs join/leave; no chat-target invalidation (action-mode target may dangle — the target provider holds raw user/channel references; no listener clears it on remove) | User-remove logs kicked variants with actor; user pointers removed from the model; dialogs re-resolve by session and abort if gone | Mumla OLED send path dereferences the target at send time with throwing getters; no explicit stale-target guard |

## 6. HTML/Markdown rendering and filtering

| Capability | Mumla OLED | Upstream | Delta |
|---|---|---|---|
| Outgoing markup | Bare-`https?://` autolink to `<a href>`, newline to `<br>`. No Markdown, no HTML escaping of the typed body (typed `<` passes through into HTML) | `sendChatbarText(text, plainText)`: plain mode escapes HTML plus breaks plus non-breaking spaces; rich mode runs a Markdown pipeline (headers, links, bold, italic, strikethrough, blockquote, inline code, code blocks, plain-link autolink including `www.` → https, escaping) | Mumla OLED autolinks http(s) only, no Markdown constructs, no escaping; upstream full Markdown pipeline with escaping |
| Incoming HTML render | `HtmlCompat` legacy mode plus image getter plus link sanitizing (URLSpan → ClickableSpan http(s)-only, inert otherwise) | Upstream valid-HTML path: document render plus anchor-scheme strip (non-allowlist anchors de-formatted), layout-size sanity (2048×2048, invalid-size guards) | Both filter links; Mumla OLED allowlist is narrower (http/https only versus a 16-scheme list including `mumble:`, `channelid:`, `clientid:`, `gemini:`/`ftp:`/`magnet:` and others); Mumla OLED has no rendered-size bomb guard (has decode bounds instead) |
| HTML-disallowed servers | `allowHtml` is parsed into `ServerSettings.allowHtml` but no render path branches on it (grep finds no consumer outside the protobuf/model layers); `imageMessageLength` is consumed as the send cap | Message-config/server-config honor `allow_html=false` via an HTML filter (plain-text conversion, strict parse, breaks/paragraphs to newline) | Mumla OLED always renders HTML; no plain-text fallback path |
| Mentions/keywords/highlight | No keyword/mention/highlight engine in scope (`MessageFormatter.highlightString` is a static font-color wrapper for actor/channel names in log lines only) | Log highlight column plus highlight-spawned signals, per-type highlight toggles | Absent in Mumla OLED |
| Emoji | No emoji picker/shortcode path in scope; emoji render as system font via HTML compat (no custom emote set) | No built-in emoji set either (system/Unicode) | Parity (none custom) |

## 7. Images and attachments

| Capability | Mumla OLED | Upstream | Delta |
|---|---|---|---|
| Send image | Image-pick → EXIF orient → resize-keeping-aspect 1600×1600 → JPEG quality loop 97→12 step −15 → downscale by 2/3 while above 320 px → `<img src="data:image/jpeg;base64,..."/>` capped by the server `imageMessageLength` (0 = unlimited); failure → image-too-large toast. Max send bound is the server limit | Upstream `imageToImg(image, maxSize)`: scale to 1600×1000, JPEG quality 100→10 loop, returns empty when over max size (from server-config `image_message_length`); paste/drag image in the chat bar | Same 1600-box plus quality-loop shape; Mumla OLED max box 1600×1600 versus 1600×1000; Mumla OLED adds EXIF plus iterative downscale-to-320; both gate on the server limit |
| Receive/inline display | Image getter: data-URI sync decode (SHA-256 cache key, bounded 4096 px / ~16 MP decode, absolute reject 32768 px / 64 MP, 10 MB cap, LRU ~1/8 heap ≥ 1 MB) plus http/https async fetch (2 daemon threads, pending/failed sets, 15 s timeout, redirect re-check per hop, pinned-IP SSRF, Host-header pin, SNI pin for https, content-length plus stream cap, bounds fitting: photos fill width ≤ 65% height, icons ≤ 96 dp scaled by density) | Log document resource loading plus layout load; producer-side image-to-image bound; no fetch policy beyond Qt resource loading | Receive path strictly more bounded (caps, SSRF pinning, async, negative cache) |
| Click-to-view | Images are inline bounded drawables only; no lightbox/activity; links open a chooser | Inline in the log; links open via anchor handling (allowlist schemes including in-app `clientid:`/`channelid:`) | No full-size viewer; no in-app user/channel link actions (see §8) |
| GIF/animated | No animated-decode path (first frame only) | Static frame in the log | Parity (static) |

## 8. Links — click handling, allowlist, SSRF

| Capability | Mumla OLED | Upstream | Delta |
|---|---|---|---|
| Click handling | Sanitizing keeps the href span (never display text), drops non-http(s), wraps in a clickable span → view-chooser. Outgoing autolink uses the match for href and text identically | Valid-HTML strips non-allowlist anchors in place; click handling routes `clientid:`/`channelid:` in-app, external via desktop services | Phishing-safer (href-only) but external-only; richer upstream scheme set with in-app navigation |
| Allowlist | Trimmed http/https only, fail-closed on null/empty/malformed | 16-scheme list: mumble/http/https/gemini/ftp/clientid/channelid/spotify/steam/irc/gg/mailto/xmpp/skype/rtmp/magnet | Narrower by 14 schemes; `mumble:`/`clientid:`/`channelid:` links inert in Mumla OLED |
| SSRF guard (remote images) | Scheme allowlist plus userinfo reject plus literal-IP classify / hostname resolve with refuse-if-any-address-blocked per hop plus redirect cap plus pinned-address fetch plus Host-header/SNI pinning | No equivalent remote-image fetch guard (no async remote fetch) | Mumla OLED-only hardening; no upstream counterpart |
| TTS link shortening | When short-TTS-messages is enabled: bare-URL anchors (href equal to text) spoken as the hostname; remaining HTML stripped by the sanitizer | No-scope/no-author TTS variants plus per-message overrides; hostname shortening absent | Different knobs; same intent (shorten spoken links) |

## 9. Notifications, TTS, log granularity

| Capability | Mumla OLED | Upstream | Delta |
|---|---|---|---|
| Chat notification | InboxStyle multi-line, sanitized actor/body line, vibrate 0/100, high priority, message channel, count badge; suppressed when POST_NOTIFICATIONS denied (also skips backlog append); explicit dismiss on clear/destroy; documented as NOT suppressed by the foreground suppression flag | Per-type notification toggles plus tray plus sound; per-type notification-mute blockers | Single on/off (`isChatNotifyEnabled`); no per-event-type notification/highlight/sound matrix |
| TTS | Global enabled plus engine preference plus short-links preference; speaks the sanitized line when length ≤ 250 and not self-deafened; queue-add; disconnect-reason speech; per-sender ignore absent; per-type TTS absent | Per-type TTS column, volume, threshold, read-back-own, no-scope/no-author, enable flag; per-message ignore-TTS (local-ignore-TTS) | Global-only with 250-char cap plus deaf gate; no per-type matrix, threshold, read-back, or scope variants |
| Log levels in chat | Info-message severities info/warning/error plus text message; fragment observer maps info/warning/error | 30+ log message types (debug/info through channel-listening add/remove, private versus channel text, self-mute/other-self-mute/mute matrices, permission-denied, plugin message) | Three info severities, no per-type routing |
| Keyword/mention notify | Absent in scope | Highlight plus highlight-spawned per type (no content keywords in core; plugins) | Absent both (no content-keyword engine either side) |

## 10. User info dialog, registration, kick/ban/move, listener sliders

| Capability | Mumla OLED | Upstream | Delta |
|---|---|---|---|
| User info dialog | No user-stats request/display path found in scope (no `requestUserStats`/user-stats-message/UserInformation; the session interface grep shows no such call). The menu has no information/stats item. Avatar via the avatar cache (texture/textureHash); comment via the user-comment fragment | User action → request stats → stats message → user-information dialog: certificate chain plus strong-cert bold plus certificate view, address, version/release, OS, opus support, TCP/UDP packets plus ping avg/var, total plus rolling loss/late/resync, online/idle seconds, bandwidth; 6 s refresh tick | No user-stats dialog at all: no certificate hash view, OS/release, idle time, UDP/TCP ping stats, address, bandwidth, opus flag |
| Registration | Menu register visible when user id is unset, hash non-empty, and SelfRegister/Register/Write permission; action sends `UserState{session, user_id=0}`; no confirm dialog (self-register permission branch included, direct send) | Self/other register actions with yes/no confirms (self: irreversible plus name-locked warning; other: certificate-binding warning) | Transport parity; no confirm dialog. No password-change UI either side in scope (certificate-identity model; password is a connection credential) |
| Password change / certificate identity | Certificate generate/select/import/export in settings; connection task loads certificates; no in-chat password change | Certificate config plus wizard plus self-signed certificate; password change is server-side (password in the connection dialog), not a user-menu action | Parity (certificate-based identity; no password-change user action) |
| Kick/ban | Menu kick/ban with a reason text field → `kickBanUser(session,reason,isBan)` → `UserRemove{session,reason,ban}`; visibility: kick on Kick/Ban/Write, ban on Ban/Write; model logs self/other × ban/kick plus disconnect fallback; `ban` bit only (no certificate/IP scope flags) | Kick action (reason input) → kick-user; ban action → ban dialog (user plus certificate/IP scope options) → ban-user with scope flags; ban-list editor (request list, editor); remove-message logs with actor plus reason | Ban is a boolean; no scope dialog and no ban-list UI. No ban-list query/editor |
| Move | Menu move (Move permission, non-self) → channel-picker dialog → move-user-to-channel; model logs join/leave with actor attribution (self/by-actor) | Move-to-channel action; join/leave logs with actor | Parity on transport plus log shape |
| Listener volume slider | No slider UI in scope; `setListeningVolume` exists in the service/session interfaces but no menu/dialog calls it (only the listen toggle). No per-user volume | Per-user local volume slider persisted per hash plus volume-adjustment listener-volume flows plus channel listen toggle | Listener-volume send path exists but is UI-unreachable; per-user volume absent |
| View/reset comment | View (read) plus reset-other (`setUserComment(session,"")`, ResetUserContent/Move/Write permission) plus self edit; request-on-demand | View/reset comment actions, self comment, lazy blob fetch plus seen-comment state | Parity |
| Avatar full-size view | Cache (4 MB LRU plus bounded 512 px decode) shown as the row icon; no full-size dialog found | Avatar in the user tree plus user-information dialog (texture display); full-size view via comment/avatar dialogs | Icon-only |
| Mute/deafen menu gating | Menu prepare: mute visible when Write/MuteDeafen and (non-self or self-muted/suppressed); deafen visible when Write/MuteDeafen and (non-self or self-deafened); checked states mirror flags; self-mute/deafen of others uses the mute/deafen setter; popup requires a non-null channel | Context menus gated on per-channel ACL perms with separate self/other actions (self mute, user mute/deaf) | Same permission family; single combined items with self/other visibility nuance |

## 11. Flat delta list

1. Per-user whisper voice targets unimplemented (both methods throw).
2. Shout group restriction always null; no positional-disable option.
3. No hold-to-whisper one-shot; single persistent slot only.
4. No per-user voice-target UI; loopback UI-unreachable.
5. No local-ignore-TTS field/check.
6. No per-user local volume; `setListeningVolume` UI-unreachable.
7. No local nickname; no friend list (add/update/remove, persistence, flags,
   friends-only-whisper gate).
8. Priority-speaker and suppress transitions produce no log lines; listen
   add/remove produce no log lines.
9. No user-stats/user-info dialog (certificate, address, version/release, OS,
   opus, ping/packet/loss stats, online/idle, bandwidth).
10. No ban-list query/editor; ban is boolean (no certificate/IP scope).
11. Registration has no confirm dialog.
12. Tree text messages receivable/displayable but not authorable (send sites
    hard-code the tree flag false; no tree target type).
13. Outgoing chat has no Markdown and no HTML escaping.
14. Incoming link allowlist http/https only (upstream 16 schemes;
    `mumble:`/`clientid:`/`channelid:` inert).
15. No `allow_html=false` plain-text fallback.
16. No per-type notification/TTS/highlight/ignore matrix; no per-type message
    limiting; TTS global-only (250 chars, deaf gate).
17. Chat history is 500 messages per server key, 10 servers LRU, in-memory; no
    persistence, no offline spool.
18. Chat target may dangle across kick/ban/disconnect (no invalidation
    listener).
19. Avatar icon-only; no full-size viewer.
20. No keyword/mention engine (both sides lack content keywords; upstream has
    per-type highlight signals).

## 12. Unread and partially read

- Connected-channel notification (mute/deaf display) — not read; lane stopped
  at user/whisper/chat per task.
- Comment fragments and channel description flows — not read.
- Settings TTS/chat-notify preference definitions and the database (local
  mute/ignore history stores) — only referenced via call sites.
- Output-side receive whisper/shout audibility plus positional audio — out of
  lane.
- Upstream protobuf schemas, channel-listener manager, volume adjustment,
  database (friend/mute/ignore/volume schema), JSON serialization,
  global-shortcut persistence — not re-read; cited via consumers.
- Upstream log TTS/volume/threshold runtime past the config surface —
  partially read (config verified, playback internals not).
- Upstream mute/deaf log matrix tail — partially read (head patterns
  verified).
