# 03 — Channels, hierarchy, ACL, tokens, links, descriptions

Factual parity audit of channels, channel hierarchy, permissions/ACL, access
tokens, channel links, descriptions, and server-info display in Mumla OLED
versus upstream Mumble. No recommendations, no judgments.

## 1. Scope and files read

Mumla OLED, full reads:

- `libraries/humla/.../model/Channel.kt`, `IChannel.kt`
- `libraries/humla/.../model/ServerSettings.kt`, `IServerSettings.kt`
- `libraries/humla/.../net/Permissions.kt`
- `libraries/humla/.../protocol/ModelHandler.kt`
- `libraries/humla/.../IHumlaSession.kt` (grep: lines 133–239)
- `libraries/humla/.../net/HumlaConnection.kt` (partial: lines 110–132,
  820–880, 1004–1032)
- `libraries/humla/.../protocol/HumlaTCPMessageListener.kt` (grep)
- `app/.../channel/ChannelFragment.kt`
- `app/.../channel/ChannelListFragment.kt`
- `app/.../channel/ChannelAdapter.kt`
- `app/.../channel/ChannelListAdapter.kt`
- `app/.../channel/ChannelMenu.kt`
- `app/.../channel/AccessTokenFragment.kt`
- `app/.../channel/PermissionsPopupMenu.kt`
- `app/.../channel/UserMenu.kt`
- `app/.../channel/ServerInfoFragment.kt`
- `app/.../channel/ChatTargetActionModeCallback.kt`
- `app/.../channel/comment/AbstractCommentFragment.kt`
- `app/.../channel/comment/ChannelDescriptionFragment.kt`
- `app/.../app/DrawerAdapter.kt`
- `app/.../service/MumlaService.kt` (grep plus partial lines 430–480)
- `app/.../app/MumlaActivity.kt` (partial lines 505–524)
- `app/.../util/ModelUtils.kt`
- `app/.../Settings.kt` (grep: show-user-count, pinned-mode keys)
- `app/.../res/menu/context_channel.xml`, `context_user.xml`
- `app/.../res/values/strings.xml` (grep)

`libraries/humla/.../HumlaService.kt` offsets 2040–2400 (channel-op region),
partial.

Upstream, partial reads plus grep: `src/Channel.h` (lines 1–140),
`src/Channel.cpp` (grep: hierarchy, sort, temp suffix), `src/Group.h` (full),
`src/Ban.h` (full), `src/ChannelListenerManager.h` (full),
`src/mumble/ChannelFilterMode.h` (full), `src/Mumble.proto` channel/ACL/ban
sections, `src/mumble/ServerHandler.cpp` (channel create/remove/link,
join-with-tokens, request paths), `src/mumble/MainWindow.cpp` (channel
actions, menus, send-message, copy-URL, hide/pin), `src/mumble/ACLEditor.h`
(grep) / `ACLEditor.cpp` (partial plus grep), `src/mumble/BanEditor.h/.cpp`
(grep), `widgets/BanDialog.cpp` (grep), `src/mumble/ServerInformation.cpp`
(grep), `src/HTMLFilter.cpp` (grep), `src/murmur/Server.h`/`Server.cpp`/
`Messages.cpp`/`Meta.h` (grep only).

Unread line by line: `src/ChannelListenerManager.cpp`, `src/ACL.cpp`,
`src/Group.cpp`, `src/Ban.cpp`, `ACLEditor.cpp` beyond lines 100–200,
`BanEditor` bodies, `ServerInformation` bodies, channel actions in
`MainWindow.h`/`.ui`, murmur channel-create validation; Mumla OLED
`HumlaService.kt` outside the channel-op offsets, `MumlaService.kt` outside
cited lines, `ChannelChatFragment.kt`, `ServerInfoTask.kt`,
`ServerInfoResponse.kt`, `ServerEditFragment.kt`, full `Settings.kt` beyond
grep hits, and the ACL/ban/server-information `.ui` files.

## 2. Per-operation parity

Notation: **Supported** = send/receive/handler path present; **Partial** =
inbound-only or a subset of fields/options; **Missing** = no send/edit/UI path
found in scope.

| # | Upstream operation | Mumla OLED evidence | Parity |
|---|---|---|---|
| C1 | Channel create via ACL-editor add dialog; send `ChannelState{parent,name,description,position,temporary,max_users}` | No `createChannel`/`ChannelState` create-send in the channel-op region of `HumlaService.kt` (only join, move, tokens, permissions-query, description-request, kick/ban, link/unlink, listen, listening-volume); `context_channel.xml` contains no add/edit/remove items; `ChannelMenu.kt` handles only join/view-description/pin/link/listen/unlink-all/shout | Missing |
| C2 | Channel remove with confirm plus `removeChannel` send | Inbound `ChannelRemove` handled: `ModelHandler.kt` `messageChannelRemove` removes links, map entry, parent subchannel, notifies; no `ChannelRemove` send in the channel-op region; no remove item in `context_channel.xml` | Partial (inbound only) |
| C3 | Channel edit (name/description/position/max-users/temporary; ACL password rows) | `ChannelDescriptionFragment.kt` throws `UnsupportedOperationException("Channel description editing is not supported")` and rejects `ARG_EDITING=true`; no property-edit send in the channel-op region | Missing |
| C4 | Channel hierarchy add/remove | `Channel.kt` `addSubchannel`/`removeSubchannel` with sorted insert via `compareTo`; `ModelHandler.kt` reparent (remove + parent add) and removal from parent on `ChannelRemove` | Supported |
| C5 | Channel sort `lessThan`; depth/path | `Channel.kt` `compareTo` (position, then name, then id); `ChannelListAdapter.kt` `constructNodes(parent,channel,depth,nodes)` recursion; `ModelUtils.kt` `getChannelList` flatten. `getPath` string form has no counterpart | Supported for sort + depth; partial on path |
| C6 | Nesting-limit deny | Deny surfacing maps `NestingLimit` to a strings reason in `ModelHandler.kt`; no client-side depth pre-check in the list adapter or service ops | Partial (server-deny display only) |
| C7 | `ChannelState.temporary`, `position`, `max_users`, `is_enter_restricted`, `can_enter` | Stored (`Channel.kt` position, temporary flag, max users, enter-restricted, can-enter) and parsed (`ModelHandler.kt` all five); none of the temporary badge / max-users / enter-restricted badge displayed in `ChannelListAdapter.kt` | Partial (model + parse, no display/edit) |
| C8 | `ChannelState.name`, `description`, `description_hash` | Parsed (`ModelHandler.kt` name, descriptionHash clearing description, description clearing hash) and stored; rendered read-only via `ChannelMenu.kt` → `ChannelDescriptionFragment.kt` (`requestChannelDescription` + `RequestBlob`) | Supported (read-only) |
| C9 | Description fetch-or-cache (blob-cache check before ACL/blob request) | `ChannelDescriptionFragment.kt` registers an observer then `HumlaService` `requestChannelDescription` → `RequestBlob.addChannelDescription`; initial inline `description` shown if present, else a loading placeholder (`AbstractCommentFragment.kt`) | Supported |
| C10 | Description HTML filtering | Rendered in a `WebView` with JavaScript/file access off and network-image loads gated by setting (`AbstractCommentFragment.kt` `hardenCommentWebView`); outbound edit path loads raw text but channel edit throws (C3). No upstream `HTMLFilter` allow-list mirror | Partial (render-hardening present; upstream allow-list not mirrored; channel write absent) |
| C11 | Channel password via ACL deny/allow rows | No password ACL UI/send in scope; `Permissions.kt` defines bits but no password composer; `ChannelMenu.kt` has no password item; no ACL send in the channel-op region | Missing |
| C12 | Join channel; join-with-tokens overload (`temporary_access_tokens`) | `HumlaService.kt` `joinChannel` → `moveUserToChannel(getSessionId(),...)` sending `UserState{session,channel_id}` with no `temporary_access_tokens`; UI: `ChannelMenu.kt` join, list-adapter row join button | Partial (join without per-join tokens) |
| C13 | Move user (server reads tokens on move) | `HumlaService.kt` `moveUserToChannel`; UI dialog listing channels via `ModelUtils.getChannelList`; gating on the Move permission (`UserMenu.kt`) | Supported |
| C14 | Link / unlink / unlink-all | Sends: `HumlaService.kt` `linkChannels` (linksAdd), `unlinkChannels` (linksRemove), `unlinkAllChannels` (iterates local links); inbound full/incremental handled (`ModelHandler.kt`); model bidirectional mirror; UI toggle in `ChannelMenu.kt`; link styling italic in the list adapter | Supported |
| C15 | Transitive links (`allLinks`); `isLinked` | `Channel.kt` `getAllLinks()` breadth-first traversal with snapshot; used for the recording notice (`ModelHandler.kt` self-channel links contain the user channel) | Supported |
| C16 | Listen (channel listener): add/remove; UI checked state; upstream 1.4 version gate | State: `Channel.kt` listening flag plus volume; inbound add/remove/volume (`ModelHandler.kt`); sends: `HumlaService.kt` `setListeningChannel`/`setListeningVolume` (synchronized-state check only, no version gate); UI toggle in `ChannelMenu.kt` with checked/title swap; row icon in `ChannelListAdapter.kt` | Partial (behaviors present; the version gate has no counterpart) |
| C17 | Listener volume adjust | Inbound stored and fanned out (`ModelHandler.kt` listening volume plus self fan-out); send exists; no volume-slider UI in `ChannelMenu.kt` or `context_channel.xml` | Partial (protocol + model, no UI) |
| C18 | Hide/Pin filter modes; toggles; checked state | Local pin (drawer filter): `ChannelMenu.kt` add/remove-pinned plus `isChannelPinned`; pinned-only root set; database-backed pinned channels; startup-in-pinned-mode setting. No hide mode: node construction only collapses empty channels | Partial (pin only; hide absent) |
| C19 | Filter empty channels by default | Node construction collapses when the subchannel user count is zero unless user-expanded; expand toggle dimmed when empty. No text-search filter in the channel list fragment or adapter | Supported (collapse-empty; search absent — see §5 of the settings report) |
| C20 | Copy channel URL (walk parent chain) | No URL builder/copy in `ChannelMenu.kt` or `context_channel.xml` | Missing |
| C21 | Send message to channel/tree (dialog with tree flag) | `HumlaService` channel-text-message send supports the tree flag; chat-target action mode exists; no per-channel "send message" item in `context_channel.xml` | Partial (protocol present; channel-menu entry absent) |
| C22 | Default channel on connect | No default-channel preference/routing in `Settings.kt` grep (only show-user-count and pinned-mode keys); list adapter defaults the root to id 0 | Missing |
| C23 | Drag/sort channels (position weight) | Sort honored (`Channel.kt`); no drag-reorder UI; no position-write in the channel-op region | Partial (read/sort only) |
| C24 | Avatar display (`texture`/`texture_hash`; `RequestBlob` texture) | Fetch: `HumlaService` `requestAvatar` → `RequestBlob.addSessionTexture`; cache: `AvatarCache.kt` (via `ChannelAdapter.kt`, `ChannelListAdapter.kt`); passive fallback (`CircleDrawable` if bitmap present, else outline icon) | Supported |
| C25 | User-count badges | Toggle: `Settings.kt` `shouldShowUserCount`; render: `ChannelListAdapter.kt` subchannel-user-count formatting; listener for the setting change | Supported |
| C26 | Talking-state icons | Mapping: self-deafened/deafened/self-muted/muted/suppressed → outline icons; talking/shouting/whispering → talking-on icon; else avatar or talking-off; overlay mirror in `ChannelAdapter.kt`; live patch paths in both adapters | Supported |
| C27 | Kick (reason dialog → `UserRemove{ban=false}`) | Reason dialog (`UserMenu.kt` hint field → `kickBanUser(session,reason,false)`); send: `HumlaService.kt` `UserRemove{session,reason,ban}`; gating on Kick/Ban/Write | Supported |
| C28 | Ban with cert/IP scope plus duration editor; list entry (address/mask/name/hash/reason/start/duration); ban editor dialog | `UserMenu.kt` collects reason only, calls `kickBanUser(session,reason,true)`; `HumlaService.kt` sets session/reason/ban with no `ban_certificate`/`ban_ip`; `HumlaConnection.kt` parses/dispatches `BanList` but `HumlaTCPMessageListener` defaults it and `ModelHandler.kt` has no override; no ban editor/dialog counterpart in scope | Partial (kick-ban reason only; cert/IP scope, durations, list editor absent) |
| C29 | Ban list query/replace (`BanList{query=true}`) | No `requestBanList`/ban-replace send in the session interface or service ops; inbound `BanList` unhandled beyond the default no-op | Missing |
| C30 | Permission-denied taxonomy (14 deny types) | Mapped: ChannelName, TextTooLong, TemporaryChannel, MissingCertificate, UserName, ChannelFull, NestingLimit; Text/Permission/SuperUser/H9K/ChannelCountLimit/ChannelListenerLimit/UserListenerLimit fall to the generic deny/other branch | Partial (7 of 14 typed; remainder generic) |
| C31 | Denied surfacing (log plus status bar) | `ModelHandler.kt` `onPermissionDenied(reason)`; service notification ping; activity dialog with the denied title plus reason | Supported |
| C32 | ACL request (`ACL{channel_id,query=true}`; schema inherit_acls/groups/acls) | `PermissionsPopupMenu.kt` re-queries permissions (via `PermissionQuery`) when cached permissions are zero; `HumlaService.kt` sends `PermissionQuery{channelId}` — note: `PermissionQuery`, not `ACL` query. No `ACL{query}` send; inbound `ACL` defaults to no-op | Partial (`PermissionQuery` only; ACL fetch/edit absent) |
| C33 | Group membership editor (add/remove/inherit/inheritable, inherited members) | No group UI/model in scope; `Permissions.kt` is bit constants only; `PermissionsPopupMenu.kt` uses cached channel permissions / root permissions | Missing |
| C34 | Token auth (persistent `Authenticate.tokens` plus per-join `temporary_access_tokens`) | Persistent send: `HumlaConnection.kt` `sendAccessTokens` (`Authenticate.addAllTokens`); trigger: `HumlaService.kt`; UI add/remove plus persist plus resend: `AccessTokenFragment.kt` (append trimmed non-empty text, persist, resend; delete by position, persist removal, resend); initial list from the `access_tokens` argument; drawer entry. Per-join temporary tokens absent (see C12) | Supported (persistent); per-join temporary tokens absent |
| C35 | Server info dialog (host/users/protocol/release/OS/bandwidth/codec/latency/cert/UDP stats) | Shown: release-or-version, OS, TCP/UDP latency, host, codec label, max/current bandwidth; polled at 1 s. Not shown: user counts, UDP loss stats, certificate viewer | Partial (transport + version; users/certs/loss absent) |
| C36 | Enter-restricted / can-enter gating | Stored/parsed (C7); join button does not pre-gate on `canEnter`; permissions popup gates on cached perms only | Partial (state only) |

## 3. ACL permission matrix coverage

Upstream bit definitions: `src/ACL.h`. Mumla OLED bit definitions:
`libraries/humla/.../net/Permissions.kt` (identical values including Listen,
Kick, Ban, Register, SelfRegister, ResetUserContent, Cached, All).

Upstream editor rows: all named permissions via bit loop, root 30 rows versus
non-root 16, version-gated Listen/ResetUserContent pre-1.4
(`src/mumble/ACLEditor.cpp`). Default allow:
Traverse/Enter/Speak/Whisper/TextMessage/Listen
(`src/mumble/ACLEditor.cpp`).

| Permission (upstream `src/ACL.h`) | In `Permissions.kt` | Mumla OLED enforcement / UI use |
|---|---|---|
| Write | Yes | `UserMenu.kt` kick/ban/mute/deafen/register gating; no menu-gating counterpart in `ChannelMenu.kt` (only description-visibility, pin-checked, link-checked, listen-checked) |
| Traverse | Yes | Defined; no use in `ChannelMenu.kt` or `UserMenu.kt` |
| Enter | Yes | Stored as enter-restricted/can-enter; no Enter pre-check before join |
| Speak | Yes | Defined; talk-button mute gating uses muted/suppressed/self-muted flags, not the Speak bit |
| MuteDeafen | Yes | `UserMenu.kt` (Write/MuteDeafen gates mute/deafen/priority) |
| Move | Yes | `UserMenu.kt` (move visibility) |
| MakeChannel | Yes | Defined; no create path (C1) |
| LinkChannel | Yes | No LinkChannel check before linking (toggle on checked-state only) |
| Whisper | Yes | Shout path does not check Whisper |
| TextMessage | Yes | No TextMessage check in channel-menu shout/send paths |
| MakeTempChannel | Yes | Defined; no create path (C1) |
| Listen | Yes | No Listen check before setting the listening channel |
| Kick | Yes | `UserMenu.kt` (Kick/Ban/Write shows kick) |
| Ban | Yes | `UserMenu.kt` (kick row includes Ban; ban row Ban/Write) |
| Register | Yes | `UserMenu.kt` (Register/SelfRegister + Write) |
| SelfRegister | Yes | `UserMenu.kt` (self branch) |
| ResetUserContent | Yes | `UserMenu.kt` (ResetUserContent/Move/Write shows reset-comment) |
| Cached | Yes | Defined; popup refetches when zero but does not test Cached |
| All | Yes (as `0x1F0FFF`) | Defined; no matrix UI in scope |

Group/inheritance rows (`applyHere`/`applySubs`/`inherited`/`userId`/`group`/
`grant`/`deny`; `inherit_acls`/`groups`/`acls`/`query` in
[`src/Mumble.proto`](https://github.com/mumble-voip/mumble/blob/master/src/Mumble.proto)).
Mumla OLED has no `ChanACL`/`ChanGroup` model, no allow/deny matrix, and no
`inherit_acls`/`apply_here`/`apply_subs` handling in `ModelHandler.kt` or the
channel-op region.

## 4. Token, link, and temporary-channel behavior notes

- **Persistent tokens:** `HumlaConnection.kt` replaces Authenticate tokens on
  each `sendAccessTokens`; `AccessTokenFragment.kt` appends trimmed non-empty
  text (empty guarded), persists via the database, and resends the full list;
  delete path removes by position, persists removal, resends. Initial list
  from the `access_tokens` argument. Token case-sensitivity (upstream
  `Group.h` access-token case sensitivity) has no Mumla OLED counterpart in
  scope.
- **Links:** full-list replace plus incremental add/remove, bidirectional
  mirror, removal cleanup on `ChannelRemove`. Sends are single-edge deltas.
  `unlinkAllChannels` fans out from the local link set, whereas upstream fans
  out from the permitted-links set.
- **Temporary channels:** `temporary` parsed only at creation; stub channels
  default false; no update path for later `temporary` flips; no temporary-only
  badge (upstream appends a marker); no force-temporary when lacking
  Write/MakeChannel (upstream disables the checkbox); `TemporaryChannel` deny
  mapped to a strings reason.
- **Passwords:** upstream password = an ACL pair (ACL editor password rows);
  Mumla OLED has no password prompt/composer; join sends no password/token.
  Server-password `Authenticate.password` handling (murmur auth branches) is
  outside this lane and was not traced here.
- **Listener caps** (`ChannelListenerLimit`/`UserListenerLimit`) fall through
  to the generic deny branch.

## 5. Unread and partially read

- `HumlaService.kt` outside the channel-op offsets (e.g. comment setters,
  registration, text-message sends, mute/deafen state bodies) — not line-read.
- `MumlaService.kt` outside cited lines — channel ops inherited from
  `HumlaService`, not separately traced.
- `ChannelChatFragment.kt`, `ServerInfoTask.kt`, `ServerInfoResponse.kt`,
  `ServerEditFragment.kt` — not read.
- `Settings.kt` beyond grep hits — channel keys other than show-user-count and
  startup-in-pinned-mode not enumerated.
- `src/ACL.cpp`, `src/Group.cpp`, `src/Ban.cpp`,
  `src/ChannelListenerManager.cpp`, `ACLEditor.cpp` beyond lines 100–200,
  `BanEditor`/`ServerInformation` beyond grep, `MainWindow.h`/`.ui` channel
  actions, murmur channel-create validation — not line-read.
- The ACL editor, ban editor, and server-information `.ui` files — not read.
