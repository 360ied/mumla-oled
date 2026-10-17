# 05 — Servers, connection, identity, registration, admin

Factual parity audit of the server list, connection flow, certificates and
authentication, registration, and server-administration operations in Mumla OLED
versus upstream Mumble. No recommendations, no judgments.

## 1. Scope and files read

Server list, edit, ping (every line):

- `app/.../servers/FavouriteServerListFragment.kt`
- `app/.../servers/ServerAdapter.kt`
- `app/.../servers/FavouriteServerAdapter.kt`
- `app/.../servers/ServerEditFragment.kt`
- `app/.../servers/ServerInfoTask.kt`
- `app/.../servers/ServerInfoResponse.kt`

Database (every line):

- `app/.../db/Entities.kt`, `Daos.kt`, `MumlaDatabase.kt`,
  `MumlaRoomDatabase.kt`, `DatabaseCertificate.kt`, `DatabaseModule.kt`

Certificates and authentication settings (every line):

- `app/.../preference/AuthenticationSettingsFragment.kt`
- `app/.../preference/MumlaCertificateGenerateTask.kt`
- `app/.../preference/CertificateGenerateActivity.kt`
- `app/.../preference/CertificateImportActivity.kt`
- `app/.../preference/CertificateExportActivity.kt`
- `app/.../preference/CertificateSelectActivity.kt`
- `app/.../preference/ServerCertificateClearActivity.kt`
- `app/.../preference/KeySelectDialogPreference.kt`

Trust, URL, connect (every line):

- `app/.../util/MumlaTrustStore.kt`
- `app/.../util/PinnedTlsSocketFactory.kt` (image-fetch TLS only)
- `app/.../util/SsrfHostPolicy.kt` (image-fetch URL policy only, not server
  connect)
- `libraries/humla/.../util/MumbleURLParser.kt`
- `app/.../app/ServerConnectTask.kt`
- `libraries/humla/.../model/Server.kt`
- `libraries/humla/.../model/ServerSettings.kt`
- `libraries/humla/.../net/HumlaCertificateGenerator.kt`
- `libraries/humla/.../net/HumlaSSLSocketFactory.kt`
- `libraries/humla/.../net/HumlaConnection.kt`
- `libraries/humla/.../net/TlsHostnameVerifier.kt`
- `libraries/humla/.../net/HandshakeFailure.kt`
- `libraries/humla/.../Constants.kt`
- `libraries/humla/.../util/HumlaException.kt`

Connection lifecycle and UI integration (targeted sections plus
grep-verified): `app/.../service/MumlaService.kt` (connect delta, disconnect,
reconnecting notification/TTS), `libraries/humla/.../HumlaService.kt`
(connect, auth send, disconnect/reconnect/backoff, register/kick/tokens/move),
`app/.../app/MumlaActivity.kt` (view-intent handling, connect with permission,
TLS-handshake-failure dialogs plus pin-and-reconnect, update-connection-state
Reject handling, server-edited callback, first-run certificate prompt),
`app/.../channel/UserMenu.kt` (kick/ban/register menu),
`app/.../channel/ServerInfoFragment.kt`, `ModelHandler.kt` (welcome,
server-config, permission-denied), `app/.../Settings.kt`
(default-certificate/username/using-certificate keys).

Upstream reference (grep-sampled, not full reads):
`src/mumble/ConnectDialog.cpp` (favorite/public/LAN items, from-URL, public-list
fetch, filters), `src/mumble/ServerHandler.cpp` (Version/Authenticate,
tokens, digest, ping), `src/mumble/Cert.cpp`
(generate/import/export/validate/PKCS12), `src/mumble/Database.cpp/.h`
(servers table, ping cache, digest getters/setters, fuzzy match),
`src/mumble/MainWindow.cpp` (mumble-URL handling), `src/murmur/Server.cpp`/
`Server.h` (register/unregister, server config, query-users).

## 2. Server list — per-flow parity

| Flow | Mumla OLED — evidence | Upstream — evidence |
|---|---|---|
| Server add | `ServerEditFragment.kt` dialog; activity `ADD_ACTION → database.addServer(server)` then loads the favourites fragment | `ConnectDialog.cpp` from-URL plus add/edit dialogs; `Database.cpp` replace-into servers (name/hostname/port/username/password/url) |
| Server edit | `ServerEditFragment.kt` prefill from parcel; activity `EDIT_ACTION → database.updateServer(server)` | `ConnectDialog.cpp` username/password copy plus edit-save path; `Database.h` username field |
| Server delete | Favourites fragment confirm dialog → `database.removeServer` plus adapter remove; room transaction deletes server plus favourites plus tokens plus local-mute plus local-ignore in one transaction | `ConnectDialog.cpp` delete-favorite path; `Database.cpp` server-row delete |
| Server share | Favourites fragment builds a `mumble://[bracketed-host][:port]/` URL, password omitted, plain-text share intent | `ConnectDialog.cpp` server-item MIME data: `mumble://` URL, default port omitted, path set to channel, `?version=1.2.0` query item |
| Favorite versus public list | Favorites only: adapter from `database.getServers()`; no public-list fragment, adapter, fetch, or filter in `servers/` | Favorite plus public-internet top-level items; disable-public-list gate; web-fetch of the public list; search-text, location-combo, show-all/reachable/populated filters |
| Public-list fetch/filter/ping | Not observed in `servers/` (no web-fetch/public-list equivalent, no country/version/ping filter). Per-row liveness only (next row) | Cached public servers, fetch, resolver ping records, filter functions |
| Row ping (favorites) | `ServerAdapter.kt`: 50-thread pool; per-row UDP ping task; shows online plus version, users current/max, latency; dummy → offline | UI ping/users/max-users fields, ping sort, hide-public-until-search behavior |
| Ping packet | `ServerInfoTask.kt`: 12-byte request (type 0 + id), resolved host/port, 1000 ms timeout, 24-byte reply, source-address check, short/off-path → dummy | Upstream ping via the resolver plus ping-cache table (hostname/port/ping plus unique index; select/delete/replace) |
| Recent / history | No recent/history table, fragment, or menu. Entity tables: favourites (= pinned channels), tokens, comments, local-mute, local-ignore, certificates, server. No history table | `Database.cpp` orders servers by name plus fuzzy-match of past entries; no separate "recent" table observed either (history = server rows plus ping cache) |
| LAN / Zeroconf / Bonjour | Not observed in `servers/` or `Server.kt` | LAN type item in the connect dialog; murmur registers the `_mumble._tcp` service |
| Username rules at add/edit | Trim; empty → default-username hint; no character/length validation observed | Empty username → input dialog prompt, default global username |
| Port defaults | Empty → 0 (unconfigured sentinel); validate: non-empty must be 1–65535; `getSrvPort` returns port or `DEFAULT_PORT 64738` | URL port with the default Mumble port; schema default port |
| Password storage | Entity columns carry the password as-is; room plain insert/update, no encryption transform observed; service carries `Server.password` as-is | Database password text stored/replaced in the server row (plaintext) |
| Quick-connect | Favourites fragment quick-connect menu → create-edit-dialog with connect action and ignore-title, without database write; activity connects without writing | Quick-connect via the URL bar (server field) |

## 3. Connect and URL-parameter matrix

### 3.1 URL parsing (`MumbleURLParser.kt`)

| Parameter | Mumla OLED behavior — evidence | Upstream behavior — evidence |
|---|---|---|
| Scheme | `mumble://` full-match pattern; else malformed-URL exception | Non-`mumble` scheme → warning + return; connect dialog rewrites `http/https → mumble` when converting URLs |
| Username (userinfo) | Captured group, empty → null; bare `@` with empty userinfo → malformed-URL exception; retained on `Server.username`; activity flags URL-password for a dialog warning only | URL username; empty → prompt input dialog with the global default, then set |
| Password (userinfo) | Captured group, empty → null; retained on `Server.password` | URL password → fuzzy-match plus connect |
| Host | Captured group; IPv6 brackets stripped; blank → malformed-URL exception | URL host |
| Port | Absent → default port; non-numeric or out of range → malformed-URL exception | URL port with the default port |
| Path (channel) | Accepted by the trailing tail pattern and ignored — no `Server` field carries it | Desired channel = URL path; find-desired-channel when already connected to the same host/user |
| `?version=` | Accepted-and-ignored | Parse version, reject invalid, default to the running version, reject below 1.2.0 and above running with a warning |
| `?title=` | Accepted-and-ignored | Name = title query item; from-URL title → item name; empty → user@host |
| `?url=` | Accepted-and-ignored | From-URL: has-query-item url → item URL |
| `channelid://` / `clientid://` | Not observed in the parser (only `mumble://`) | Upstream handles both schemes |
| Deep-link entry | Activity `ACTION_VIEW → parseURL(dataString)` → edit-fragment connect dialog (connect action, ignore-title, URL password); `onNewIntent` re-handles (single-top); parse failure → URL-parse-failed toast | Upstream open-URL: version gate → same-server channel-find → fuzzy match → username prompt → connect |

### 3.2 SRV and DNS resolution

| Aspect | Mumla OLED — evidence | Upstream — evidence |
|---|---|---|
| Trigger | Only when port is 0: "if we have a port then don't bother with SRV" | Resolver records loop; connect-dialog resolver records |
| Query | `_mumble._tcp.<host>` via MiniDNS resolve-SRV, 800 ms future timeout, single-thread executor, sorted records, first record wins | `ServerResolver`/`ServerResolverRecord` (referenced; exact query string/timeout not read) |
| Skip rules | IP literals (IP-address pattern or contains `:`), `.onion` suffix | Not verified line by line |
| Fallback | Raw host + 64738 on empty/failed; main-thread call returns an uncached fallback with warning | Not verified |
| Caching | Volatile resolved host/port cache, invalidated in set-host/set-port; service sets resolved values after TCP established; ping task pings the resolved host/port | Not verified |

### 3.3 Connect flow (service)

| Step | Mumla OLED — evidence |
|---|---|
| Intent | `ServerConnectTask.kt`: `EXTRA_SERVER_ID` always; `EXTRA_SERVER` parcel only when the server is not saved; action connect; `onPostExecute` starts the service |
| Load | `MumlaService.kt` build-connect-delta: database server by id for saved, parcel for unsaved; access tokens by id; trust store (path/password/format); local mute/ignore history when saved; certificate reference when using a certificate |
| Apply/connect | `HumlaService.kt` connect: generation-scoped listener (stale-callback filter), force-TCP/keys/trust-store setters, model handler, connecting state, `connection.connect(host, port)`; `HumlaException` → disconnect path |
| Auth send | `HumlaService.kt`: `Version` via `Constants.createVersionMessage(clientName)` then `Authenticate{username, password, opus=true, tokens}` |
| Version content | 1.5.0 / protocol version plus v2; `createVersionMessage` sets release plus v1 plus v2, omits OS name/version fields (comment: privacy) |
| Tokens send | `HumlaConnection.kt` `sendAccessTokens` → `Authenticate{addAllTokens}`; token apply/send paths |
| ForceTCP | Settings force-TCP → connect delta → apply `mForceTcp` → `connection.setForceTCP`; dummy UDPTunnel on sync when forced; UDP/TCP auto-switch on ping stats |
| Reconnect/backoff | `getReconnectDelayForAttempts`: initial, 3000, 6000, 12000, else max; reconnect only when the reason is a connection error; `setReconnecting` schedules the delay plus a network callback; reconnecting notification/TTS |
| Reject handling | `HumlaConnection.kt` `messageReject → HumlaException(msg)` → disconnect path; `MumlaActivity.kt`: reject + wrong-user/wrong-server-password → password-retry dialog, writes `server.password`, updates the database if saved, serialized retry; reconnecting-plus-error → cancel-reconnect dialog; else generic message dialog. No per-type branches observed for wrong-version/username-in-use/server-full/no-certificate/authenticator-fail |
| Kick/ban disconnect | Self `UserRemove → HumlaException(userRemove)`; message `Kicked:/Banned: + reason`, reason user-remove |
| Server redirect | No server-sync-redirect handling observed in the connection/service/model handlers |
| Server config view | `ModelHandler.kt` stores `ServerSettings(msg)`; `ServerSettings` fields allow-html/message-length/image-length/max-bandwidth/max-users/welcome-text; server-info fragment displays release, OS name/version, TCP/UDP latency, SRV host/port, codec label, max/current bandwidth; max-users/allow-html/message-length not displayed there; welcome text → chat log |

Upstream connect reference (sampled): server-handler sends
`Version(release, version, os, os_version)` plus
`Authenticate(username, password, tokens[from database by digest], opus=true)`;
tokens keyed by certificate digest; digest compare; reject-last reset; TCP/UDP
ping loop.

## 4. Certificates and identity matrix

| Aspect | Mumla OLED — evidence | Upstream — evidence |
|---|---|---|
| Generate algorithm | `HumlaCertificateGenerator.kt`: RSA 2048 key-pair generator, hand-built X.509v3 DER, SHA256withRSA | `Cert.cpp` generate-new-certificate from a wizard page taking name + email (params not read) |
| Subject / validity | CN = `Humla Client`; 20 years, 10-minute backdate; UTCTime/GeneralizedTime split at 2050 | Wizard page takes name + email (blank allowed); exact subject/validity not read |
| Extensions | BasicConstraints CA:false critical; KeyUsage digitalSignature+keyEncipherment critical; EKU clientAuth non-critical; SKID = SHA-1 of the public-key bit string | Not read |
| PKCS12 packaging | Alias `Mumble Identity`, store password `""`; generate task writes bytes → add-certificate (no password) → sets the default certificate id | Upstream export: PKCS12 create with empty password and the Mumble-identity alias; import: DER-to-PKCS12 plus parse with null then typed password |
| Import formats | PKCS12 only: keystore `PKCS12`, key-entry recovery check; picker `*/*`; 5 MB cap; wrong-password heuristic plus retry dialog | Open dialog filter `PKCS12 (*.p12 *.pfx *.pkcs12)`; import plus validate, missing-password notice |
| Import persistence | Add-certificate with file name, bytes, password-or-null; password converted String↔char[] at the database boundary, char[] zeroed; success → default certificate id | Persists to the settings keypair (exact store not read) |
| Export | Certificate chooser; storage-access-framework create-document; export-filename sanitizing (traversal-strip, invalid-char replacement, leading-dot strip, extension normalize, 64-unit stem, Windows-reserved prefix, UUID fallback, `.p12`); raw-bytes write | Save dialog `PKCS12 (*.p12 *.pfx *.pkcs12)` |
| Select / active identity | Select activity: no-certificate plus database list, single-choice; activate → default certificate id / disable-certificate; `isUsingCertificate = id >= 0` | Single active keypair in settings plus per-server digest pinning |
| Multi-identity / per-server mapping | Certificates table holds N rows, but connect uses one global default certificate (service certificate reference when using a certificate); no per-server certificate column or mapping observed | Digest get/set per host/port plus password-per-host/port/username update; tokens by digest |
| Password storage (certificate) | Certificate-entity password field; migrations add `password TEXT`; round-trip getter | Not read |
| Untrusted-certificate flow | Trust factory wrapper: system trust first; pinned-store fallback by host alias, SPKI compare; handshake-failure set (untrusted-issuer/pin-changed/none); post-handshake `verifyServerIdentity` → `onTLSHandshakeFailed(chain, failure, verifiedHost)`; activity: hostname-mismatch → info-only dialog (claimed SANs/CN, no allow); pin-changed/existing-pin → replace dialog (old-vs-new SHA-256 fingerprints); otherwise first-trust allow dialog → pin-and-reconnect (set entry + save store + connect) | Chain handling plus digest-prompt flow in the main window (exact dialog not read) |
| Digest / pinning primitive | SPKI SHA-256 compare (constant-time loop); pin alias = canonicalized host (lowercase, trailing-dot strip, unbracket); dialogs fingerprint full-certificate SHA-1 plus SHA-256 | Digest SHA-1-hex compare; digest per host:port |
| Hostname verification | SAN dNSName/iPAddress first; CN fallback only if no dNSName SAN; unparseable-SAN → false; IP host → IP-SAN byte compare only; `.onion` → false; wildcard = one left label only; HTTPS endpoint identification except `.onion`; TLS floor 1.2+ | Not read |
| Trust store | Private `mumla-store.bks`, format BKS, password `""` (comment: integrity-only), atomic tmp+rename save, clear-trust delete, get-path null-if-absent; `ServerCertificateClearActivity` clears plus toast (wired as a clear-trust preference) | Qt system plus pinned store (exact type not read) |
| Expiry / renewal | Validity 20 years at generation; certificate details show not-before/not-after text in dialogs only; no expiry badge, renewal, or re-issue flow observed | Shows expiry date (red when expired), self-signed versus issuer label |
| Key size / curve options | RSA-2048 only; no UI or API for ECDSA/other sizes observed | Not read |
| Self-signed versus CA constraints | Generated leaf has CA:false; server-side chain validated by system trust first, pin fallback second; no client-side CA issuance observed | Validate-certificate: non-null key plus non-empty/non-null certificates |
| Server-password versus user-password versus certificate | One `Server.password` → `Authenticate.password`; wrong-user/wrong-server-password both retry into the same field; client certificate presented at TLS via set-keys; no separate registered-user password field observed | Username plus password plus tokens plus opus; murmur distinguishes server-password versus registered-auth server-side |
| E-mail / token registration flows | None observed: no e-mail field, no registration-token UI, no password-change UI | Wizard collects e-mail string for the certificate subject; murmur register-name/password/url config keys exist server-side |
| Self-registration | Menu register visible when user id unset, hash present, SelfRegister/Register/Write permission; action sends `UserState{session, userId=0}` | Register-user actions; murmur `registerUser`/`unregisterUser`/`isValidUserID` signals |

## 5. Server admin operations

| Operation | Mumla OLED — evidence | Upstream — evidence |
|---|---|---|
| Kick / ban (reason) | Menu: reason text field, kick/ban-user call when connected; service: `UserRemove{session, reason, ban}` | Same protocol `UserRemove` fields; murmur enforces server-side |
| Temporary versus permanent ban, duration | No duration/expiry field observed in the kick/ban-user call, menu, or builder usage | Ban editor exists in tree — contents not read |
| Ban export / import | Not observed (no ban-editor equivalent, no ban-list send path) | Upstream ban editor; murmur ban-list message |
| Ban list view/edit | Connection parses `BanList`; network-listener stub; no UI or request/update sender observed in the service layers | Server-handler/main-window ban-list flows (not read) |
| User registration management | `registerUser(session)` only; no unregister / user-list / query-users sender observed (grep shows no request-ban-list/request-user-list/query-users/unregister-user; model handles no observed user-list message — only listener stubs) | `Server.h` register/unregister/valid-id signals; murmur query-users handling; user-edit dialog upstream |
| Server config view | Server-info fragment fields (see §3.3); `ServerSettings` carries 6 fields, 1 displayed | Server-information dialog upstream (not read) |
| Allowed codecs | Server-info codec label from the session codec; Opus-preferring codec-version message; legacy CELT/Speex packets dropped ("parity with upstream Mumble 1.5+") | Upstream drops non-Opus audio packets with a warning |
| Permission-denied surfacing | Model maps channel-name/text-too-long/temporary-channel/missing-certificate/user-name/channel-full/nesting-limit/other → observer; service override | Same deny-type enum upstream (protocol) |
| Mute/deafen/priority/move/comment | Mute/deafen/priority/move/comment setters; menu wiring | Same user-state fields upstream (protocol) |
| Access tokens (user-facing) | Access-token fragment with the database token list; send-access-tokens path; tokens stored per server id | Tokens dialog; set-tokens → authenticate with added tokens; database tokens by certificate digest |
| ICE / RPC admin | Not observed; out of scope per task | Murmur ICE/Slice plus meta/database/register noted as out of scope; murmur register (public-server registration keys) recorded without review |

## 6. Unread and partially read

1. Key-select dialog fragment — not read (sibling of the key-select
   preference).
2. Access-token fragment — referenced but not line-read (token add/remove UI
   details).
3. `HumlaService.kt` full body — only cited sections read; reconnect
   constants, `apply()` trust-store path (sampled via grep), audio and
   voice-target sections unread.
4. `MumlaService.kt` — only cited sections read.
5. `HumlaTCP.kt` — only grep-sampled (identity verification, handshake,
   onion branches); full TLS/UDP-tunnel read not done.
6. `ModelHandler.kt` — only cited handlers read.
7. Upstream connect-dialog header/UI, server-handler header, resolver,
   password generator, version files — not read.
8. Upstream certificate header/UI, self-signed certificate (key type/size/
   validity/e-mail encoding) — not read.
9. Upstream database beyond cited greps (fuzzy-match body, digest-table
   schema, ping-cache write path) — not read.
10. Upstream main-window beyond the open-URL excerpt (reject-type dialogs,
    last-reject handling, find-desired-channel) — not read.
11. Upstream ban editor, user edit, ACL editor, server information, tokens
    dialogs — existence confirmed via listing, contents not read.
12. Murmur server (auth password branches, query-users, ban-list, user-list,
    server-config send), server-user, meta, database wrapper, register —
    grep-sampled only; ICE/RPC explicitly out of scope.
13. The protobuf reject-type enum values beyond wrong-user/wrong-server
    password (observed in the activity) — full enum not re-verified here.
14. Key-select preference layout and its binding — not traced to a live usage
    point.
