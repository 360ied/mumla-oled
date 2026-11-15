# Server-Info Tab Crash Retrospective

Snapshot: 2026-11-15, `master` at `a7753709` (fix commit), after the
Information-tab crash was confirmed via logcat and fixed in
`bugfix/server-info-udp-latency-crash`. The deep history of how a 2014
poll loop survived to meet a 2026 refactor is told separately in
[twelve years of `updateData()`](twelve-years-of-updateData.md).

Opening the drawer's Information tab while connected killed the app with
`IllegalStateException: No UDP latency yet` on the main thread. The
fragment polls the service every second, so the crash was deterministic
on any connection where no UDP ping round-trip had completed — TCP-only
or UDP-blocked networks in particular, where the value never arrives at
all.

## Timeline

- **2026-11-10** — `379a34b4` ("humla: wire client and delete
  `HumlaConnection`") replaced the old connection object, whose UDP
  latency was a plain field defaulting to `0`, with the new
  `Client`/`Session`/`Transport` stack, where latency is a nullable
  `Long?` that throws when unknown. First shipped in `v0.27.2`.
- **Report** — consistent crash on tapping Information when connected.
- **Confirmation** — logcat showed `FATAL EXCEPTION: main`,
  `IllegalStateException: No UDP latency yet`, via the main-thread
  `Handler` → poll runnable → `updateData()` → `getUDPLatency()`. The
  R8 mapping confirmed the obfuscated frames.
- **Fix** — `a7753709` made the fragment's session reads best-effort
  (details below).

## Root cause

Three facts combined:

1. `Session.getUDPLatency()` throws `IllegalStateException` when
   `udpLatencyMicros` is `null`, and that field is populated only by a
   completed UDP ping round-trip. UDP pings are not even sent in
   forced-TCP mode, and on UDP-less networks the reply never comes back,
   so the value can stay `null` for the entire connection.
2. `HumlaService.getUDPLatency()` passes the throw straight through to
   the caller (unlike `getMaxBandwidth()`, which was given a
   try/catch → `-1` fallback in the same rewire).
3. `ServerInfoFragment.updateData()` — essentially unchanged since 2014
   — guarded only the `HumlaSession()` call and caught only
   `RemoteException` in its poll runnable. The new *unchecked* throw
   therefore reached the main-thread `Handler` uncaught: instant process
   death. The service runs in-process (direct binder reference, no
   `android:process`), so there was no IPC layer to translate or absorb
   it.

The same shape existed for TCP latency (transient: the first TCP ping
reply arrives within seconds) and for the pre-sync version path, but only
the UDP case is persistent, which is why the crash was consistent rather
than a startup flake.

## The fix

`updateData()` treats every session read as best-effort now:

- Version/latency/bandwidth/codec reads that throw
  `IllegalStateException` skip the update; the 1 Hz poll retries.
- Unknown TCP/UDP latency renders a new `server_info_latency_unknown`
  placeholder instead of the old misleading `0.00 ms`.
- Nulled views (poll posted before `onDestroyView`) and a null target
  server return early instead of throwing `NullPointerException`.
- The poll runnable catches `RuntimeException` as a backstop.

App-only diff, no service-contract changes; device-tested on a live
connection plus the full `check.sh` gate.

## Why it slipped through

- The throwing-getter contract was introduced with unit tests for the
  session/transport layers, but no test exercised the fragment against a
  session with unknown latency — the one consumer that couldn't tolerate
  the throw.
- Fail-fast getters are reasonable inside the protocol stack, but the
  unchecked throw crossed a layer boundary into UI code written a decade
  earlier under the old "returns 0, throws checked-only-when-offline"
  contract. Nothing flagged the contract change at the call site; the
  Kotlin migration even annotated the surrounding null-dereferences as
  intentionally NPE-preserving.
- The crash needs a live connection plus a specific network condition
  (no UDP), so it never appeared in unit tests, interop, or release
  builds — only in daily use.

## Takeaways for future refactors

- When a service-layer getter gains a new unchecked throw, every UI call
  site must be audited, not just the protocol-stack callers. UI reads of
  live session state should be best-effort by default.
- Same-process binder calls propagate unchecked exceptions directly onto
  the caller's thread — there is no AIDL-style translation safety net.
  The "Binder contract" comment on `requireSession()` documents the
  throw; consumers like the throttled pollers need to be written against
  that documentation.
- A diagnostics screen that polls every second turns any newly-throwing
  read into a deterministic crash. Screens with hot poll loops deserve a
  test with each backing value unknown.
