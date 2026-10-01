# SSRF Residuals Explainer (ODD-10 – ODD-12)

Plain-language companion to the three SSRF items in the [misc-oddities
catalog](README.md). Status and remediation tracking live in the
[remediation plan](remediation-plan.md); this file explains what the items
mean and why they were left open.

## Background: why a chat app needs SSRF protection

Mumla OLED renders images inline in chat — and those image URLs come from
the **server** (channel/user avatars, comment bodies with `<img>` tags).
A malicious or compromised Mumble server can therefore make *your phone*
fetch *any URL it chooses*. That is the setup for **SSRF** (Server-Side
Request Forgery — here the phone is the confused deputy).

A hostile URL might point at `http://169.254.169.254/latest/meta-data/`
(cloud metadata endpoint) or `http://192.168.1.1/admin` (the local
router). The fetched bytes are decoded into a bitmap, so there is no
direct "send the data back to the attacker" channel — but forcing the
device to request internal URLs is still an attack primitive (router
probing, network mapping via timing, and similar).

The `phase2-image-pipeline` work built the guard: before fetching any
image,
[`MumbleImageGetter.isHostBlocked()`](../../app/src/main/java/se/lublin/mumla/util/MumbleImageGetter.java)
checks the URL's host against
[`SsrfHostPolicy`](../../app/src/main/java/se/lublin/mumla/util/SsrfHostPolicy.java),
which blocks loopback, private LAN ranges, link-local, multicast, CGNAT,
reserved ranges, and their IPv6 equivalents — resolving hostnames via DNS
and checking **every** returned address (fail-closed: DNS failure means
blocked). Redirects are followed manually, re-checked per hop, and capped
at 5. Phase 8 closed the three items below: fetches now resolve once per hop and
connect to the checked address (ODD-10), the transition gaps are unwrapped (ODD-11),
and the policy layers are pinned by direct tests (ODD-12).

## ODD-10: the DNS rebinding hole (TOCTOU)

The check and the fetch are two separate DNS lookups:

1. `isHostBlocked("http://attacker.com/pic.png")` resolves `attacker.com`,
   gets a public IP (allowed) — fetch approved.
2. `url.openConnection()` resolves `attacker.com` **again** — this time the
   attacker's DNS server answers `127.0.0.1` or `192.168.1.1`, and the HTTP
   request goes to the internal target.

That is the **TOCTOU** (time-of-check vs. time-of-use): the answer changes
between the check and the use. Attackers do this with short-TTL DNS
records that flip between a public IP and the internal target. The 5-hop
redirect cap and per-hop re-checking shrink the window but cannot close
it — every hop has the same check-then-fetch gap.

The fix (shipped in Phase 8) resolves once per hop and connects to *that specific IP*:
`fetchOneUrl` checks the resolved addresses, then fetches through an IP-literal URL — no
second DNS lookup — while the `Host` header and, for HTTPS, SNI plus the platform hostname
verifier keep presenting the original hostname. Crucially, no certificate logic was
reimplemented: verification is always the platform default verifier, only the *name* it
checks is overridden, so there is no hand-rolled TLS verification to get wrong. Several
all-allowed addresses are tried in order; a mixed public-plus-private answer still refuses
the whole host.

Blast-radius note (not a downgrade — the matrix keeps ODD-10 at Medium): even unpatched,
the attacker got one blind request from the phone to an internal address, and only learned
whether the response happened to decode as an image. No data came back to them.

## ODD-11: the IPv6 disguises the policy doesn't see through

An IPv4 address like `10.0.0.5` can wear several different IPv6 costumes,
and the policy now sees through every exactly-matchable one — only provider-chosen
prefixes stay uncovered. IPv6 has a family of
**transition mechanisms** — ways to carry an IPv4 address inside a 128-bit
IPv6 address, relics of the long IPv4-to-IPv6 migration:

| Mechanism | How `10.0.0.5` hides | Policy sees it? |
|---|---|---|
| IPv4-mapped (`::ffff:10.0.0.5`) | last 32 bits | Yes — unwrapped and blocked |
| 6to4 (`2002:0a00:0005::`) | bytes 2–5 | Yes — unwrapped and blocked |
| Well-known NAT64 (`64:ff9b::10.0.0.5`) | last 32 bits | Yes — unwrapped and blocked |
| Teredo (`2001::…`) | last 32 bits, XOR-obfuscated | Yes — unwrapped, blocked if server *or* client half embeds private |
| ISATAP (`…::0:5efe:10.0.0.5`) | last 32 bits | Yes — unwrapped (u/l-bit mask) |
| Local-use NAT64 (`64:ff9b:1::/48`) | bytes 6,7,9,10 per RFC 6052 §2.2 | Yes — unwrapped, u-octet gated |
| Operator / RFC 6052 NAT64 (custom prefix) | IPv4 bits at varying offsets | **No — accepted residual (prefix unknowable statically)** |
| 6rd (provider prefix + IPv4 suffix) | IPv4 bits after a provider prefix | **No — accepted residual (prefix unknowable statically)** |

Attacker DNS returns, say, a Teredo address embedding `127.0.0.1`; the
policy inspects the IPv6 wrapper, sees nothing on its blocklists, and
waves it through.

Why the remainder is still Low: the surviving bypass class needs the victim's network to
actually run operator NAT64 or 6rd toward the target. On a typical phone (carrier NAT or
home Wi-Fi), those translations don't exist — a real bypass class with almost no
road to drive on.

## ODD-12: tests that pass for the wrong reason

This one is not a hole in the product — it is a hole in the *proof* the
product is safe.

`testSiteLocalBlocked` asserts that `fec0::1` (deprecated IPv6 site-local
range) is blocked, and it passes. But it passes because of a **generic
JDK check** (`address.isSiteLocalAddress()`) that fires *before* the
policy's own explicit `fec0::/10` branch ever runs. Delete the explicit
branch tomorrow and the test still goes green. The test pins the
*outcome* but not the *layer* — it cannot tell you the defense-in-depth
you think you have is actually there. The same applies to the
`169.254.x.x` and `224.0.0.1` branches in the IPv4 checker, which are
covered only indirectly via generic link-local/multicast predicates.

The wrinkle, stated precisely: through the public `isBlockedAddress` API with plain IPv4 or IPv6
literals, the generic JDK predicates always win the race, so the explicit branches *look* unreachable —
but they are *shadowed*, not dead. The IPv6-unwrap path reaches them (the `64:ff9b::7f00:1` test proves
it: the `127/8` branch fires via NAT64 unwrapping), and the Phase 8 pins added direct package-visible
tests plus embedded `169.254`/`224`/`10` vectors to prove it. Coverage gap, not a code bug —
and the fix is process judgment, not engineering.

## The one-paragraph version

The image pipeline lets hostile servers aim the phone's HTTP client at
arbitrary URLs; the SSRF policy is the fence. **ODD-10** was a gate that
could be sprinted through between check and fetch (now resolve-once pinned; the meager
prize is recorded as a blast-radius note). **ODD-11** was three unguarded disguises for
smuggling private IPv4 addresses past the fence (now unwrapped; only provider-chosen
prefixes remain an accepted residual). **ODD-12** was the observation that two of the
fence's rails had never actually been load-tested, because another rail
always took the weight first (now pinned directly).
