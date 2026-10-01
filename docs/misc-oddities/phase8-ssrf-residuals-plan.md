# Phase 8 Implementation Plan: SSRF Residual Hardening (ODD-10 – ODD-12)

Concrete, commit-ready plan for [Phase 8](remediation-plan.md#phase-8-ssrf-residual-hardening-p2--p3--completed) of the Miscellaneous Oddities remediation
([detailed records](README.md#odd-10-dns-rebinding-toctou-in-image-ssrf-check), [plain-language explainer](ssrf-residuals-explainer.md)).
This file locks the design decisions left open by the SSRF review so the implementing branch has no deliberation left to do.

**Status:** Ready for implementation.
**Scope:** Three items — ODD-10 (P2 / Medium), ODD-11 (P3 / Low), ODD-12 (P3 / Low, tests only).
Its distinctive feature versus earlier phases is risk ordering: the pure-JVM, fully unit-testable work
(ODD-11 policy gaps, ODD-12 test layering) lands first; the delicate TLS-touching TOCTOU close (ODD-10)
lands last, isolated in its own commits with device-side negative tests.

---

## 1. Problem restatement

Chat images in Mumla OLED come from server-supplied URLs, so a hostile server aims the phone's HTTP
client at arbitrary hosts. The guard is [`SsrfHostPolicy`](../../app/src/main/java/se/lublin/mumla/util/SsrfHostPolicy.java)
(checked per hop by [`MumbleImageGetter.fetchOneUrl()`](../../app/src/main/java/se/lublin/mumla/util/MumbleImageGetter.java#L543-L565),
redirects followed manually with per-hop re-checks and a 5-hop cap). Three residuals remain:

- **ODD-10:** `isHostBlocked()` resolves via `getAllByName`, then `url.openConnection()` resolves the
  hostname **again** — a DNS rebind between check and `connect()` (TOCTOU) defeats the policy at every hop.
- **ODD-11:** `isBlockedIPv6()` ([`SsrfHostPolicy.java:281-337`](../../app/src/main/java/se/lublin/mumla/util/SsrfHostPolicy.java#L281-L337))
  unwraps IPv4-mapped, IPv4-compatible, 6to4, and only the well-known NAT64 `64:ff9b::/96`. Teredo
  (`2001::/32`, XOR-obfuscated), ISATAP (`…:0:5efe:…`), and the local-use NAT64 `64:ff9b:1::/48`
  ([RFC 8215](https://www.rfc-editor.org/rfc/rfc8215)) are unhandled.
- **ODD-12:** `testSiteLocalBlocked` passes via the generic `isSiteLocalAddress()` gate before the explicit
  `fec0::/10` branch ever runs, and the `127` / `169.254` / `224` branches of `isBlockedIPv4()` are pinned
  only indirectly. Outcome tested, layers not.

---

## 2. Locked design decisions

1. **Severity posture is unchanged.** ODD-10 stays **Medium** in the matrix; the explainer's "low in
   practice" (blind single request, no exfiltration channel) is recorded as a blast-radius note, not a
   downgrade. ODD-11/ODD-12 stay Low.
2. **ODD-11 splits into fixable vs. accepted-residual.** Teredo, ISATAP, and the IANA local-use
   `64:ff9b:1::/48` have exact, statically matchable layouts — fix them. Operator-specific NAT64 prefixes
   and 6rd stay an accepted residual: per [RFC 6052 §2.2](https://www.rfc-editor.org/rfc/rfc6052#section-2.2)
   the embedded-IPv4 offsets depend on a prefix length the app cannot know statically (it requires
   network discovery such as PREF64/DNS64). Record that rationale in the `SsrfHostPolicy` class Javadoc;
   do not attempt heuristic "check every 32-bit window" extraction — it overblocks genuine public IPv6
   addresses whose tail bytes happen to look like `10.x.x.x`.
3. **ODD-10 fix is single-resolution pinning, not a second check.** Resolve once, keep the checked
   `InetAddress[]`, and connect to *those addresses* (IP-literal URL, no hostname left to re-resolve).
   A "re-check after connect" (e.g. comparing `getInetAddress()`) races the same window and is explicitly
   not the fix.
4. **TLS verification is delegated, never reimplemented.** The pinned factory presents the original
   hostname via SNI and the hostname verifier calls the **platform default verifier** against the original
   hostname. There is no custom certificate logic, no fingerprint pinning, no `ALLOW_ALL`-anything in this
   branch — a verifier lambda returning `true` is a hard review failure.
5. **`SsrfHostPolicy` stays pure-JVM.** The two new pinning helpers (`buildPinnedUrl`, `hostHeaderValue`)
   take only `java.net` types so the existing JVM test setup covers them. All Android API usage
   (`Build.VERSION`, `HttpsURLConnection` wiring) lives in `MumbleImageGetter` / the new factory file.
6. **Existing fail-closed semantics are preserved, not revisited.** If *any* resolved address is blocked,
   the whole host is refused (mixed public+private DNS answers remain a refusal — that mixture is itself a
   rebinding signal). DNS failure, null/empty hosts, and userinfo URLs still fail closed, per hop.
7. **ODD-12 widening is package-visibility, not an API.** `isBlockedIPv4`/`isBlockedIPv6` drop `private` for
   default (package) visibility with a "visible for testing" comment. The test class is already in the same
   package (`se.lublin.mumla.util`), so no annotation, no reflection, no production API change.
8. **Order of work is risk-ascending.** Step 1 (ODD-11) + Step 2 (ODD-12) are pure-JVM and independently
   committable; Step 3 (ODD-10) touches live TLS and stays in its own commits on top. If Step 3 misbehaves
   on device, Steps 1–2 still merge alone.
9. **First address is not special.** When DNS returns several all-allowed addresses, attempt them in order
   and use the first that connects. Pinning to `addresses[0]` only would turn DNS round-robin into missing
   images whenever the first record is down.
10. **No new dependencies.** Pinning uses `HttpURLConnection`/`HttpsURLConnection` plus one small
    `SSLSocketFactory` subclass. OkHttp or any new fetch stack is out of scope.

---

## 3. Implementation steps

### Step 1 — ODD-11: close the exactly-matchable transition gaps

In `isBlockedIPv6()`, alongside the existing unwrap checks (those constrain disjoint prefixes, so their
early returns are order-independent — put the three new checks directly after the `isNat64` check).
Unlike the existing unwraps, the three new predicates can coincide on an attacker-crafted literal:
ISATAP constrains only the IID (bytes 8–11), so a Teredo or NAT64-local address can carry an
ISATAP-coincident IID with a public suffix while embedding a private target elsewhere (e.g.
`64:ff9b:1:a00:0:5efe:808:808` embeds `10.0.0.94` yet trails `8.8.8.8`). Early return on the first
match would let the public embedding mask the private one, so the new checks OR-combine — the
address is refused if *any* matched embedding is blocked:

```java
// OR-combined: several transition embeddings can coincide in one crafted
// literal, so every matched embedding is evaluated and any blocked one
// refuses the address. Do not early-return between these three checks.
boolean transitionBlocked = false;
if (isTeredo(addr)) {
    // RFC 4380: 2001::/32. Bytes 4-7 are the server IPv4; the last 32 bits
    // are the client IPv4 XOR 0xFFFFFFFF. Either can carry the target.
    transitionBlocked |= isBlockedIPv4(new byte[]{addr[4], addr[5], addr[6], addr[7]});
    transitionBlocked |= isBlockedIPv4(new byte[]{
            (byte) (addr[12] ^ 0xFF), (byte) (addr[13] ^ 0xFF),
            (byte) (addr[14] ^ 0xFF), (byte) (addr[15] ^ 0xFF)});
}
if (isIsatap(addr)) {
    // RFC 5214: interface identifier 00-00-5E-FE (u/l bit masked) + IPv4.
    transitionBlocked |= isBlockedIPv4(new byte[]{addr[12], addr[13], addr[14], addr[15]});
}
if (isNat64LocalUse(addr)) {
    // RFC 8215 + RFC 6052 section-2.2 /48 row: prefix 0064:ff9b:0001,
    // u octet (byte 8) zero; v4 = bytes 6,7,9,10. Suffix ignored per
    // RFC 6052 section-2.3 (translators ignore nonzero suffix).
    transitionBlocked |= isBlockedIPv4(new byte[]{addr[6], addr[7], addr[9], addr[10]});
}
if (transitionBlocked) {
    return true;
}
```

Predicates (exact byte matches — no tolerance, to avoid overblocking):

```java
private static boolean isTeredo(byte[] addr) {
    return addr[0] == 0x20 && addr[1] == 0x01 && addr[2] == 0x00 && addr[3] == 0x00;
}

private static boolean isIsatap(byte[] addr) {
    return (addr[8] & 0xFD) == 0x00 && addr[9] == 0x00
            && addr[10] == 0x5E && addr[11] == (byte) 0xFE;
}

private static boolean isNat64LocalUse(byte[] addr) {
    return addr[0] == 0x00 && addr[1] == 0x64
            && addr[2] == (byte) 0xFF && addr[3] == (byte) 0x9B
            && addr[4] == 0x00 && addr[5] == 0x01
            && addr[8] == 0x00; // u octet must be zero; suffix bytes 11-15 ignored
}
```

Notes:

- The `(addr[8] & 0xFD)` mask accepts both `00-00-5E-FE` and `02-00-5E-FE` (u/l bit), and nothing else.
- `isTeredo` checks the server IPv4 too: normally a public relay, but an attacker-crafted address can put
  anything there — blocking on either half is the safe direction and cannot overblock (a public server IP
  simply doesn't trigger it).
- Update the `SsrfHostPolicy` class Javadoc residual note: Teredo/ISATAP/local-use now handled; remaining
  residual is operator/NSP NAT64 and 6rd (prefix unknowable statically — cite RFC 6052 section-2.2).
- The explainer's ODD-11 table gains a "handled" row set; the "missed" rows shrink to operator/6rd.

### Step 2 — ODD-12: pin the shadowed branches directly

1. Widen the two helpers (same file, [`SsrfHostPolicy.java:227`](../../app/src/main/java/se/lublin/mumla/util/SsrfHostPolicy.java#L227) and [`SsrfHostPolicy.java:281`](../../app/src/main/java/se/lublin/mumla/util/SsrfHostPolicy.java#L281)):
   ```java
   // Package-visible for testing: pins the explicit branches shadowed by the
   // generic InetAddress predicates in isBlockedAddress() (see ODD-12).
   static boolean isBlockedIPv4(byte[] addr) { ... }
   static boolean isBlockedIPv6(byte[] addr) { ... }
   ```
2. Extend [`SsrfHostPolicyTest.java`](../../app/src/test/java/se/lublin/mumla/util/SsrfHostPolicyTest.java)
   (JUnit-3 `extends TestCase` style, unchanged) with:
   - One direct test per `isBlockedIPv4` explicit branch, called as `isBlockedIPv4(new byte[]{...})` so the
     generic gate is bypassed by construction: `0/8`, `127/8`, `169.254/16`, `224/4`, `10/8`, `172.16/12`,
     `192.168/16`, `100.64/10`, `240/4` (+ broadcast), TEST-NET-1/2/3, `192.88.99/24`, `198.18/15` — each
     with its boundary vectors (vectors in §5).
   - Direct `isBlockedIPv6` tests for `fec0::/10`, `fc00::/7` (plus the `fe00::` non-match boundary), and
     `2001:db8::/32`, pinning the explicit layer regardless of the JDK gate.
   - Public-API embedded regression tests proving the unwrap path reaches those branches:
     `64:ff9b::a9fe:a14` (169.254.10.20), `64:ff9b::e000:1` (224.0.0.1), `2002:a00:1::` (10.0.1.0 via 6to4).
3. Fix the explainer's overstatement (§ODD-12 "The wrinkle" paragraph): the explicit branches are
   *shadowed via the public API but reachable via the IPv6-unwrap path* (proven by the existing
   `64:ff9b::7f00:1` test) — the gap was missing pins, not unreachable code. One-paragraph edit, same file.

### Step 3 — ODD-10: resolve once, connect to the checked address

#### 3a — Pure helpers in `SsrfHostPolicy` (JVM-testable, no Android imports)

```java
/**
 * Builds the fetch URL for one already-checked address: same scheme, port,
 * and path as the original, host replaced by the IP literal (bracketed for
 * IPv6) so the connection performs no second DNS lookup. Call only with
 * addresses that already passed {@link #isAnyAddressBlocked}.
 */
static URL buildPinnedUrl(URL original, InetAddress address) throws MalformedURLException {
    String literal = address.getHostAddress();
    if (literal.indexOf(':') >= 0) {
        literal = "[" + literal + "]";
    }
    int port = original.getPort();
    String authority = (port == -1) ? literal : literal + ":" + port;
    return new URL(original.getProtocol() + "://" + authority + original.getFile());
}

/** Value for the Host header, preserving virtual-host routing through the pinned connection. */
static String hostHeaderValue(URL original) {
    String host = original.getHost();
    int port = original.getPort();
    if (port == -1 || port == original.getDefaultPort()) {
        return host;
    }
    return host + ":" + port;
}
```

Build the pinned URL by string form (not the `URL(protocol, host, port, file)` overload) to dodge the
IPv6-bracket ambiguity; §5 pins the exact string round-trip instead of `getHost()` quirks. Scoped
addresses need no handling: they are link-local and already refused upstream.

#### 3b — New file `PinnedTlsSocketFactory.java` (`app/src/main/java/se/lublin/mumla/util/`)

```java
/**
 * TLS factory for pinned image fetches: the TCP connection goes to a
 * pre-checked IP while SNI presents — and verification checks — the
 * original hostname. Verification is always the platform default
 * verifier; only the *name* it checks is overridden. Never returns true.
 */
public final class PinnedTlsSocketFactory extends SSLSocketFactory {
    private final SSLSocketFactory mDelegate =
            (SSLSocketFactory) SSLSocketFactory.getDefault();
    private final String mSniHostname;

    public PinnedTlsSocketFactory(String sniHostname) { mSniHostname = sniHostname; }

    /** Verifier that checks the session against the original hostname, not the pinned IP. */
    public static HostnameVerifier verifierFor(String originalHost) {
        HostnameVerifier platform = HttpsURLConnection.getDefaultHostnameVerifier();
        return (hostname, session) -> platform.verify(originalHost, session);
    }
    // ... createSocket(Socket, host, port, autoClose) override below, plus seven one-line delegates
    // (five SocketFactory overloads and two cipher-suite getters). The override count was
    // verified against the compiler: an early draft with five delegates failed to build.
}
```

The single interesting override:

```java
@Override
public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
    SSLSocket socket = (SSLSocket) mDelegate.createSocket(s, host, port, autoClose);
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        SSLParameters params = socket.getSSLParameters();
        params.setServerNames(java.util.Collections.singletonList(new SNIHostName(mSniHostname)));
        socket.setSSLParameters(params);
    }
    // Below N (minSdk 21): SNIHostName is unavailable, so no SNI override — the handshake
    // either succeeds on the server's default cert or fails closed. Never insecure.
    return socket;
}
```

GPL-3.0-or-later header with `Copyright (C) 2026 Brian Zhu`, copied from a recent file in the same
component. Confirm the `SNIHostName` API-24 gate at implementation time; if the gate must move, the
direction is fixed: newer devices get SNI, older devices fail closed, no device skips verification.

#### 3c — Rewire `MumbleImageGetter.fetchOneUrl()` to fetch through the pin

Replace the check-then-connect-by-hostname sequence
([`MumbleImageGetter.java:543-565`](../../app/src/main/java/se/lublin/mumla/util/MumbleImageGetter.java#L543-L565))
with resolve-once, try-each:

```java
private FetchResult fetchOneUrl(URL url) throws IOException {
    if (!isSchemeAllowed(url) || SsrfHostPolicy.hasUserinfo(url)) {
        Log.w(TAG, "Refusing to load image with disallowed URL");
        return new FetchResult(null);
    }
    InetAddress[] checked = resolveAndCheck(url); // null = blocked (fail closed), per-hop
    if (checked == null) {
        Log.w(TAG, "Refusing to load image from blocked host");
        return new FetchResult(null);
    }
    IOException lastFailure = null;
    for (InetAddress addr : checked) {
        try {
            return fetchPinned(url, addr);
        } catch (IOException e) {
            lastFailure = e; // try the next checked address
        }
    }
    throw lastFailure; // all checked addresses failed; loop treats as fetch failure
}
```

where `resolveAndCheck` is the existing `isHostBlocked` logic inverted (literals via `isLiteralBlocked`
return a single-element array; hostnames via `getAllByName` + `isAnyAddressBlocked`, null on block), and
`fetchPinned` opens `SsrfHostPolicy.buildPinnedUrl(url, addr)`, sets the `Host` header from
`hostHeaderValue(url)`, keeps `setInstanceFollowRedirects(false)` and the 15-second timeouts, and — for
`https` — installs `new PinnedTlsSocketFactory(originalHost)` plus `PinnedTlsSocketFactory.verifierFor`
before `connect()`. The §6.1 name-based-vhost positive test is the gate for the Step 3 commits: it
proves the `Host` override reaches the wire on Android's `HttpURLConnection` stack — if the stack
drops the override, pinned fetches land on the server's default vhost and Step 3 must not merge as-is.
Redirect handling in `fetchURLImage` is untouched (each hop re-resolves and re-pins).

Do not change: scheme/userinfo gating, per-hop re-check, 5-hop cap, 15 s timeouts, `disconnect()` in
`finally`, the `mFailedDownloads`/`mPendingDownloads` bookkeeping.

### Step 4 — Docs bookkeeping (same branch)

- `remediation-plan.md`: the Phase 8 section (added with this plan) flips PLANNED → COMPLETED with
  branch/commit recorded, per the Phase 1–7 convention.
- `README.md` matrix: ODD-10 – ODD-12 flip Open → Resolved.
- `ssrf-residuals-explainer.md`: ODD-10 section rewritten from "accepted residual" to the pinning design
  (keeping the TOCTOU explanation as the motivation); ODD-11 table updated per Step 1; ODD-12 wrinkle
  paragraph fixed per Step 2.3.

---

## 4. Edge cases

| Case | Expected handling |
|---|---|
| DNS returns mixed public + private addresses | Whole host refused (existing any-blocked fail-closed kept). Mixing is itself a rebinding signal. |
| DNS returns several all-public addresses, first down | Tried in order (§2.9); first successful connection wins. No behavior bias toward any record. |
| Pinned `https` on API 21–23 | No SNI override (gate in §3b); handshake succeeds on default-cert vhosts or fails closed. Verification never skipped. |
| Redirect `Location` is absolute with a new hostname | Loop re-resolves + re-pins the new host (per-hop check preserved). Redirect to a blocked host refused as today. |
| HTTPS→HTTP downgrade via redirect | Per-hop `isSchemeAllowed` still applied; downgrade itself is not blocked (unchanged policy — server chose the target). |
| Non-default port (`https://host:8443/…`) | Pinned URL keeps `:8443`; `Host` header carries `:8443`; SNI carries the bare hostname (no port — per TLS spec). Pinned by §5 vectors. |
| `Host` header on plain HTTP | Required for virtual-host routing once the URL host is an IP; set on every pinned fetch, both schemes. |
| IPv6 literal in pinned URL | Bracketed form (`http://[64:ff9b::808:808]/…`); JVM tests pin the exact string. |
| Connect failure to all checked addresses | `fetchOneUrl` throws the last `IOException`; caller treats as fetch failure (image skipped, URL cached in `mFailedDownloads`). No policy bypass. |
| Worst-case latency (N addresses × 15 s timeouts) | Bounded by address count (typically 1–4); fetches already run on the 2-thread background executor, never the UI thread. No timeout change. |
| Teredo server half public, client half private (or vice versa) | Blocked if *either* half is blocked — attacker controls the whole address, so both halves are untrusted. |
| NAT64 local-use with nonzero suffix / nonzero u octet | Nonzero u octet → not a valid §2.2 address → no extraction (falls through to the outer-IPv6 verdict). Nonzero suffix → extraction still applied (§2.3: suffix doesn't move the target). Pinned by §5 vectors. |
| ISATAP-looking IID on a mapped/compatible address | Impossible by construction (marker bytes are nonzero); checks are order-independent. |
| Teredo or NAT64-local address with an ISATAP-coincident IID (crafted literal: public suffix, private embedding) | Blocked: the three Step 1 checks OR-combine, so any private embedding refuses the address. Pinned by the §5 overlap vectors. |
| `fe00::…` (not unique-local, not link-local) | Direct `isBlockedIPv6` test pins `false` — guards the `fc00::/7` mask against over-widening. |

---

## 5. Automated tests

All in the existing JVM suites — no new infrastructure. `SsrfHostPolicyTest` keeps its
`extends TestCase` (JUnit 3) style; GPL headers already present.

**ODD-11 vectors** (public-API `isBlockedAddress`, via `InetAddress.getByName`):

| Literal | Expected | Pins |
|---|---|---|
| `2001:0:808:808:8000:fb2d:f5ff:fffa` (Teredo; server 8.8.8.8, client `F5FFFFFA ^ FF… = 0A000005` = 10.0.0.5) | blocked | Teredo client-half de-obfuscation |
| `2001:0:808:808:8000:fb2d:f7f7:f7f7` (Teredo; client `F7F7F7F7 ^ FF… = 08080808` = 8.8.8.8) | allowed | no overblock on public Teredo |
| `2001:0:a00:1:8000:fb2d:f5ff:fffa` (Teredo; server 10.0.0.1, client 10.0.0.5) | blocked | Teredo server-half check |
| `::0:5efe:a00:5` (ISATAP; 10.0.0.5) | blocked | ISATAP IID marker path |
| `::0:5efe:808:808` (ISATAP; 8.8.8.8) | allowed | no overblock on public ISATAP |
| `::200:5efe:a00:5` (ISATAP; u/l bit set) | blocked | IID mask, not exact match |
| `64:ff9b:1:a00:0:500:0:0` (§3 derivation: bytes 6,7 = `0A 00`, u = `00`, bytes 9,10 = `00 05` → 10.0.0.5) | blocked | /48 local-use extraction |
| `64:ff9b:1:808:0:808:0:0` (→ 8.8.8.8) | allowed | no overblock on public /48 |
| `64:ff9b:1:a00:100:500:0:0` (u octet `01` ≠ 0) | allowed | u-octet validity gate (outer is global, unlisted) |
| `64:ff9b:1:a00:0:5efe:808:808` (NAT64-local → 10.0.0.94, ISATAP-coincident IID trailing 8.8.8.8) | blocked | OR-combination: private embedding not masked by public tail |
| `2001:0:a00:1:0:5efe:808:808` (Teredo server 10.0.0.1, ISATAP-coincident IID trailing 8.8.8.8) | blocked | OR-combination across transition checks |

Derivations shown above are normative — recompute, don't trust: client-half `XOR FFFFFFFF` both ways,
`/48` bytes `[b6,b7,b9,b10]` per the RFC 6052 figure in §3.

**ODD-12 vectors:**

| Test | Pins |
|---|---|
| Direct `isBlockedIPv4(new byte[]{…})` for every explicit branch (`0/8`, `127/8`, `169.254/16`, `224/4`, `10/8`, `172.16/12`, `192.168/16`, `100.64/10`, `240/4`+broadcast, TEST-NET-1/2/3, `192.88.99/24`, `198.18/15`) with boundary pairs (e.g. `172.15.x`/`172.32.x` allowed; `100.63.x`/`100.128.x` allowed; `223.x`/`240.x` boundary; `198.17.x`/`198.20.x` allowed) | Each explicit layer, independent of JDK predicates |
| Direct `isBlockedIPv6(rawOf("fec0::1"))` → blocked; `rawOf("fc00::1")`, `rawOf("fd00::1")` → blocked; `rawOf("fe00::1")` → allowed | `fec0::/10` + `fc00::/7` layers + mask boundary (`rawOf` is a test-local helper over `InetAddress.getByName(...).getAddress()`) |
| `isBlockedAddress(64:ff9b::a9fe:a14)` → blocked; `64:ff9b::e000:1` → blocked; `2002:a00:1::` → blocked | Unwrap path reaches the shadowed `169.254`/`224`/`10` branches via public API |

**ODD-10 vectors** (pure helpers, JVM):

| Test | Pins |
|---|---|
| `buildPinnedUrl("http://example.com/a/b?q=1", 93.184.216.34)` → exactly `http://93.184.216.34/a/b?q=1` | string form, path+query preserved |
| Same with `https://example.com:8443/x` → `https://93.184.216.34:8443/x`; with default `:443` explicit in input → `Host` header without port | port handling both sides |
| IPv6: `buildPinnedUrl("http://example.com/", ::1)` → `"http://[" + addr.getHostAddress() + "]"` plus `getFile()` (empty path degrades to `/`-less form — assert whatever `getFile()` yields, document it; `getHostAddress()` returns the expanded `0:0:0:0:0:0:0:1` form, never `::1`) | bracketed literal form |
| `hostHeaderValue`: default ports stripped (`example.com`, not `example.com:443`); non-default kept (`example.com:8443`) | virtual-host header correctness |

No JVM test for the TLS factory or the fetch loop (needs Android + network) — covered by §6 device checks.
Run: `nix develop --command ./gradlew testFossDebugUnitTest` during development; full `./scripts/check.sh`
in the worktree before completion.

---

## 6. Manual / device verification

ODD-11/ODD-12 need no device work (JVM suite covers them). ODD-10 does — the TLS wiring must be seen to
verify, in both directions:

1. **Positive:** serve a valid image over HTTPS (public host, valid cert for that hostname); confirm chat
   renders it — pinning + SNI + platform verification accept the legitimate case.
2. **Negative — wrong host:** point a test hostname at the same server (cert valid for a *different* name);
   confirm the image is refused (verifier checks the original hostname, not the IP).
3. **Negative — self-signed:** serve an image with a self-signed cert; confirm refusal (no trust-all regression).
4. **Negative — rebinding simulation:** host resolving first to a public test server then (short TTL flip) to
   a private-range test server; confirm at most the checked address is ever connected to (logcat `Refusing
   to load image from blocked host` on the rebind leg, no request to the private target).
5. **Redirect chain:** HTTPS image → 302 → second HTTPS host (different name, valid cert) → image; confirm
   render (per-hop re-pin works) and confirm a redirect to `http://192.168.1.1/` is refused.
6. **API 21–23 spot check (emulator OK):** positive case either renders (default-cert vhost) or fails
   closed — never renders with a cert warning bypassed. Record which.

---

## 7. Acceptance criteria

- [ ] Teredo / ISATAP / NAT64-local-use literals embedding private IPv4 are blocked; public embeddings
      allowed (vectors in §5 green); operator/6rd residual documented in the class Javadoc.
- [ ] Every explicit `isBlockedIPv4` branch plus `fec0::/10` / `fc00::/7` pinned by direct package-visible
      tests; embedded `169.254`/`224`/`10` public-API tests added; full JVM suite green.
- [ ] Explainer ODD-12 wrinkle paragraph corrected (shadowed, not unreachable); ODD-10 severity posture
      recorded as Medium-with-low-blast-radius-note; ODD-11 table updated.
- [ ] Pinned fetch: resolve-once per hop; IP-literal URL (no second DNS); `Host` header on every fetch;
      HTTPS carries SNI + platform-verifier-against-original-host; no allow-all verifier anywhere (grep).
- [ ] Pure-helper JVM tests green (`buildPinnedUrl` / `hostHeaderValue` vectors).
- [ ] Device checks in §6 performed, including both TLS negatives; API 21–23 behavior recorded.
- [ ] `remediation-plan.md` Phase 8 flipped to COMPLETED and `README.md` ODD-10 – ODD-12 to Resolved with
      branch/commit recorded (same convention as Phases 1–7) once merged.
- [ ] `./scripts/check.sh` green in the worktree. No merge, push, or worktree deletion (per repo policy —
      leave the branch for review).

---

## 8. Work plan (repo mechanics)

- Worktree/branch: `./scripts/worktree.py add feature/oddities-phase8-ssrf` (root stays on `master`; this
  plan file itself lives on `master` as standalone documentation).
- Commits via `python3 scripts/commit.py -m "<scope>: <subject>"` with the three-section body
  (`Context & Motivation` / `Technical Approach` / `Edge Cases & Impact`); suggested split is
  (1) Step 1 policy + ODD-11 vectors, (2) Step 2 visibility + ODD-12 pins + explainer wording fix,
  (3) Step 3a pure helpers + JVM tests, (4) Step 3b–3c factory + fetch rewire, (5) docs flip
  (`remediation-plan.md`, `README.md`, explainer) on merge-readiness.
- New files (`PinnedTlsSocketFactory.java`) need the standard GPL-3.0-or-later header with
  `Copyright (C) 2026 Brian Zhu`.
- The Step 3 commits must name the §6 device checks in their messages so a later audit can find the
  TLS verification evidence from branch history.
