# Phase 1 implementation plan — remote MITM + pre-auth DoS (H1, H2, M2)

Companion to [remediation-plan.md](remediation-plan.md) Phase 1 and
[findings.md](findings.md) (H1, H2, M2). Incorporates a pre-implementation
review of the tree: three corrections to the remediation plan as written, four
prerequisites to close before coding, then the work breakdown (two atomic
commits). Implementation itself MUST happen in a dedicated worktree
(`./scripts/worktree.py add <branch>`); this file is docs-only on `master`.

## 0. User-visible changes (UX contract — read first)

The single "Untrusted Certificate" Allow/Cancel dialog
(`MumlaActivity.java:202-251`, strings `untrusted_certificate`, `allow`,
`certificate_info`) becomes three distinct states. Reason shown in every
state; failure reasons never share copy or buttons.

| State | Trigger | Title / body | Buttons |
|---|---|---|---|
| `HOSTNAME_MISMATCH` | CA-valid cert, wrong host | "Server identity mismatch": names the expected host and what the cert is valid for; states the connection was refused, not retried | Disconnect (primary) + Details. No Allow, no pinning — one-click trust of a wrong-host cert is the attack |
| `UNTRUSTED_ISSUER` | Self-signed/unknown CA, no pin for host | Current Allow flow, copy clarified: cert is not issued by a known authority; SHA-256 fingerprint prominent; Allow pins to this host only | Allow / Cancel (unchanged), then existing `connectToServer` reconnect |
| `PIN_CHANGED` | Pinned host presents different SPKI (new) | "Server certificate changed" warning: old-vs-new SHA-256 fingerprints side by side; explicit acknowledgment required before Allow replaces the pin | Replace pin (explicit) / Cancel |

Behavior changes with no new dialog:

- IP-literal connections to DNS-named certs now fail closed (previously
  silently accepted). Affected users reconnect using the server's DNS name;
  there is intentionally no pin bypass on the `HOSTNAME_MISMATCH` path.
- Existing self-signed pins keep working with no re-prompt (pin path skips
  SAN matching per C2; SPKI compare, same BKS store, no migration).
- H2 oversize/invalid frame aborts the connection via the existing
  connection-failed path — no new UI, only a distinct logged/disconnect
  reason so it is distinguishable from a network drop.
- New strings ship English-only initially (no translations in this change).

## 1. Corrections to remediation-plan.md (read first)

### C1 — H1: socket creation path must be unified before endpoint verification

`HumlaSSLSocketFactory.createSocket(host, port, timeoutMs)`
(`libraries/humla/src/main/java/se/lublin/humla/net/HumlaSSLSocketFactory.java:76-91`)
has two paths: the no-timeout path builds the TLS socket directly over
`InetAddress.getByName(host)`, discarding the hostname string. Any
`setEndpointIdentificationAlgorithm("HTTPS")` (or manual SAN check keyed off
the socket) on that path would verify against the IP literal and break every
DNS-name server. Fix: delete the `InetAddress` path, always build a plain
`Socket`, `connect()` with timeout, then layer TLS with
`createSocket(plainSocket, host, port, true)` so the hostname survives on both
paths. The `SCSF.setHostname()` SNI hack in
`HumlaTCP.java:150-153` becomes redundant after this; remove only after
verifying SNI still works on minSdk 21 without it.

Verification itself must be manual post-handshake leaf SAN/CN matching (pure
helper, unit-testable), with `setEndpointIdentificationAlgorithm("HTTPS")` as
defense-in-depth only — not the sole mechanism. Rationale: platform
enforcement on raw `SSLSocket` varies across the API 21–36 range and
`unitTests.returnDefaultValues = true` (`libraries/humla/build.gradle:84-86`)
makes `android.net.http.X509TrustManagerExtensions` untestable in JVM tests.
One pure implementation, enforced everywhere, deterministic on all API levels.

### C2 — M2: pinned entries must NOT require a SAN match (TOFU semantics)

"Re-verify hostname for pinned entries" as written would break legitimate
servers: private Mumla OLED servers overwhelmingly use self-signed certs with
no SAN, so a strict SAN check on the pin path re-prompts (or locks out) every
TOFU user. Correct semantics:

- CA-valid chain → require SAN/CN match against post-SRV `mHost` (H1). Fail =
  `HOSTNAME_MISMATCH`, no TOFU bypass.
- CA-invalid chain → accept **iff** the leaf SPKI (SHA-256 of
  `getPublicKey().getEncoded()`) equals the SPKI pinned for *that exact host*.
  No SAN requirement on this path — the pin *is* the identity. Fail =
  `UNTRUSTED_ISSUER` (no pin) or `PIN_CHANGED` (pin exists, SPKI differs).

The existing BKS store already keys `alias = hostname`
(`app/src/main/java/se/lublin/mumla/app/MumlaActivity.java:235-238`), so
per-host enforcement needs no migration: compare by SPKI, keep storing the
`X509Certificate` for compat. The actual bug is that the wrapper
(`HumlaSSLSocketFactory.java:129-137`) consults the custom manager as a
**global** anchor for any host — thread the expected host into the factory
(see P2) and scope the fallback to that host only.

Missing from the remediation plan: changed-cert warning. `setCertificateEntry`
silently overwrites, so an attacker presenting a *different* cert for a pinned
host gets one dialog click to persist. The Allow dialog MUST show old-vs-new
SHA-256 fingerprints whenever a pin for that host already exists.

### C3 — H2: reject means abort the connection, and the finite timeout is conditional

The current `continue` after `readFully` is only safe because the payload was
already consumed. An overlarge/negative length rejected *before* allocating
leaves the stream positioned mid-frame, so the only correct action is
`error()` + close — never `continue` (stream desync, attacker-controlled
re-parse). After validation-first ordering, `NegativeArraySizeException`/OOM
from `new byte[messageLength]` (`HumlaTCP.java:182`) becomes unreachable; do
not add catch-`Error` as a substitute.

The finite read timeout needs a prerequisite check (P4), not a blind `setSoTimeout`:
idle-but-healthy connections routinely see no traffic for minutes, and a 30 s
`SoTimeout` on the read loop would disconnect them unless server Ping cadence
provable covers it. If P4 does not close cleanly, land the cap without the
timeout change and record the residual slow-stall as accepted risk.

## 2. Prerequisites (close before coding, not during)

- **P1 — cap value from upstream, not from the audit.** Murmur's
  `iMaxImageMessageLength = 1048576` (`../mumble/src/murmur/Meta.cpp:105`) is
  *payload* size plus protobuf overhead, and ACL/BanList frames on large
  servers may exceed 1 MiB. Read upstream `Connection.cpp` frame handling for
  the exact limit/behavior and mirror it. No TCP frame cap was found in the
  first grep pass — absence of a cap upstream does not block us, but the
  constant must clear the largest legitimate frame with margin.
- **P2 — factory instance lifecycle.** Read the `HumlaConnection` connect flow
  around `createSocketFactory()` (`HumlaConnection.java:615-627`) to confirm
  whether the factory is per-connect or shared across reconnects/hosts. This
  decides the host-threading mechanism: constructor param (per-connect) vs
  setter called from `HumlaTCP.run()` before `startHandshake()` (shared).
- **P3 — confirm no reliance on platform endpoint-ID enforcement.** Already
  decided in C1 (manual verification is authoritative); P3 is just the
  negative test: wrong-host cert must be rejected on minSdk 21 with the
  defense-in-depth line removed/disabled.
- **P4 — Ping cadence vs read timeout.** `BOOTSTRAP_PING_INTERVAL_SECONDS = 5`,
  `STEADY_STATE = 10` (`HumlaConnection.java:85-86,299-310`); server replies to
  Ping keep the loop fed, but confirm a quiet server cannot starve the read
  loop past the proposed timeout before setting one.

## 3. Commit 1: H1 + M2 — connection identity (must land together)

H1 without M2 leaves cross-host pins exploitable; M2 without H1 leaves
any-CA MITM. One commit, `humla:` + `app:` scope split if size demands, but a
single review unit.

### `HumlaSSLSocketFactory.java`

- Unify `createSocket` on the layered path (C1); try-with-resources the
  `FileInputStream` at `:57` (drive-by, in scope).
- Factory/wrapper gains expected-host state (mechanism per P2) plus a
  `getLastHandshakeFailure()` reason accessor. Do NOT change the
  `TCPConnectionListener.onTLSHandshakeFailed(X509Certificate[])` signature —
  it is an exported listener contract; the reason travels via the accessor.
- `checkServerTrusted` logic: try default CA validation; on success return
  (SAN check happens post-handshake in `HumlaTCP`, C1). On failure, accept
  iff `spkiSha256(chain[0])` equals the SPKI stored under the expected host;
  record `UNTRUSTED_ISSUER` / `PIN_CHANGED` otherwise and throw.

### `HumlaTCP.java` (`run()`, `:148-177` region)

- After `startHandshake()`: manual `verifyHostname(postSrvMHost, leaf)`; on
  mismatch close the socket and dispatch `onTLSHandshakeFailed` with
  `HOSTNAME_MISMATCH` (chain available via `getServerChain()` — wrapper
  already stores it at `:130`). Verify against post-SRV-rewrite `mHost`
  (`:127-132`), consistent with SNI. `.onion` hosts (`:102`) skip SAN matching
  — no public CA can vouch for them; identity is pin-or-nothing.
- H1 failure must NOT look identical to an untrusted-issuer failure in the UI
  (today both land in the same Allow dialog — one click from pinning an
  attacker cert).

### `MumlaActivity.java` (`onTLSHandshakeFailed`, `:202-251`)

- Dialog per the §0 contract: reason-specific title/body/buttons
  (`HOSTNAME_MISMATCH` has no Allow; `PIN_CHANGED` shows old-vs-new SHA-256
  fingerprints and requires explicit Replace). Allow writes the pin
  (same-host overwrite only, now explicit); Cancel aborts. No auto-reconnect
  on Allow beyond the existing `connectToServer` call.

### New pure helpers (`se.lublin.humla.net`, zero `android.*` imports)

- `TlsHostnameVerifier.verifyHostname(host, leaf)`: SAN dNSName (type 2) /
  iPAddress (type 7) first, CN fallback for legacy certs, single-label
  wildcard only, trailing-dot strip, case-insensitive DNS, IP-literal hosts
  match iPAddress SANs only (never dNSName/CN).
- `PinStore.spkiSha256(cert)`, per-host compare against the BKS entry.
- `HandshakeFailure` enum: `HOSTNAME_MISMATCH`, `UNTRUSTED_ISSUER`,
  `PIN_CHANGED`.

## 4. Commit 2: H2 — frame validation

- New pure `FrameValidator.validateFrame(short type, int length)` (range-check
  type `0..HumlaTCPMessageType.values().length - 1` first — `readShort` is
  signed — then `0 <= length <= MAX_FRAME_BYTES`); wire into the read loop at
  `HumlaTCP.java:179-185` *before* `new byte[...]`. Any violation → `error()`
  + close (C3), with the reason logged.
- `MAX_FRAME_BYTES`: set from P1 with margin over the largest legitimate
  frame; disconnect-and-log on exceed (fail closed, no skip).
- Read timeout: apply iff P4 closes (candidate: 30 s ≈ 3 missed steady-state
  pings); `SocketTimeoutException` already funnels through the `IOException`
  catch into `error()`. Otherwise record the residual explicitly (see §6).

## 5. Test matrix (JVM unit tests; precedent `HumlaCertificateGeneratorTest`)

| Case | Expects |
|---|---|
| Valid-for-wrong-host CA cert | `HOSTNAME_MISMATCH`, rejected, no pin written |
| Self-signed + matching pin, same host | accepted (no SAN required, C2) |
| Pinned cert presented on *different* host | rejected (`UNTRUSTED_ISSUER`) |
| Pinned host, different SPKI | `PIN_CHANGED`, dialog shows old/new fingerprints |
| IP-literal host, matching iPAddress SAN | accepted; DNS SAN only → rejected |
| Trailing dot / case variants / SRV target | matched post-rewrite |
| `.onion` host | SAN check skipped, pin-or-nothing |
| Frame: negative / overlarge / bad type | connection aborted before allocation |
| Frame: 0-length valid type | accepted (no regression on empty messages) |
| Existing suite | `./scripts/check.sh` green from the worktree |

TOFU Allow-then-reconnect for a *legitimate* self-signed server is the
no-regression path: pin once, subsequent connects silent.

## 6. Residuals and non-goals (explicit, not overlooked)

- Slow-sender stall if the timeout change does not land (C3/P4) — accepted,
  documented here; cap alone still kills the OOM primitive.
- Secrets at rest (former C1, H6–H8, L1) out of scope per
  [secrets-at-rest-plan.md](secrets-at-rest-plan.md).
- `onTLSHandshakeFailed` signature unchanged; `MumlaTrustStore` storage format
  unchanged (no migration).
- `sendMessage` path untouched (lengths are locally generated).
