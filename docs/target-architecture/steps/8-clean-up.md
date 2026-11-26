# Step 8 — Clean up

Part of the [migration](../migration.md). **Status: done.**
This is the step's plan as it was executed, kept as a record;
[`PROGRESS.md`](../PROGRESS.md) summarises what landed, and the living
specs hold the current design.

Step 7 left the concurrency allowlist at 10 files and 30 matching lines.
Six of the files are boundary files (`AppContainer.kt`, `CrashReporter.kt`,
`HumlaSSLSocketFactory.kt`, `HumlaTCP.kt`, `TransportImpl.kt`, and
`RouteControl.kt`). Four are not: `CrashReportsFragment.kt`,
`ServerAdapter.kt`, `MumbleImageGetter.kt`, and `Server.kt`. Step 7 also
left code that no production path calls, some of it from earlier steps.
This step removes the four non-boundary files' matches. It deletes the dead
code, including `HumlaTCP`, whose boundary status was a leftover. It then
makes the gate hold the end state, so the allowlist cannot grow again
without a reviewed change to the boundary list.

Nothing here adds a feature. The rows below that change behavior say so.

**What is left on the allowlist.** Each match has a destination:

| File (matches) | What the matches are | Target | Commit |
|---|---|---|---|
| `HumlaTCP.kt` (3, boundary) | Three `@Volatile` fields in a class nothing constructs. Step 3 moved the reader thread into `TransportImpl`; the only call left is `TransportImpl`'s use of `HumlaTCP.isIpLiteral`, which forwards to `TlsHostnameVerifier.isIpLiteral` | Deleted. The file leaves the allowlist and `BOUNDARY_FILES` | 1, 2 |
| `Server.kt` (4) | The `@Volatile` `resolvedHost` and `resolvedPort`, the `@Synchronized` `srvResolve`, and a per-lookup `Executors.newSingleThreadExecutor` that bounds the lookup at 800 ms. The only reader is `ServerInfoTask` (server-list pings), through `getSrvHost` and `getSrvPort` | One `SrvResolver` in `se.lublin.humla.net`, shared by the transport and the pings. `Server` becomes a plain Room row and parcel | 1, 3 |
| `ServerAdapter.kt` (1) | A 50-thread ping pool per adapter, running the `ServerInfoTask` `AsyncTask` | Pings are `suspend` calls on `Dispatchers.IO.limitedParallelism(50)` in a scope the adapter owns; `shutdown()` cancels it | 3 |
| `MumbleImageGetter.kt` (4) | A main-thread `Handler` and a two-thread pool with its `ThreadFactory` for external chat images | A scope the getter owns, with downloads on `Dispatchers.IO.limitedParallelism(2)`; `shutdown()` cancels it | 4 |
| `CrashReportsFragment.kt` (2) | A `Thread` that warms the native loader, and a main-thread `Handler` that posts the ANR test's freeze | `CrashReportsViewModel` probes native support on `Dispatchers.IO`; the freeze is posted with `View.post` | 5 |

That leaves five files with 16 matching lines, all boundary files.
`VoiceEngine.kt`, which is in `BOUNDARY_FILES` but has had no matching
line since 5a.4 (its control thread is
`Dispatchers.IO.limitedParallelism(1)`), leaves `BOUNDARY_FILES` too.

**Dead code.** None of the following has a production caller:

| Item | Why it is dead | Commit |
|---|---|---|
| `RouteControl`'s `setScoRequested`, `setHandsetStream`, `isScoRequested`, `decision()`, and `handsetStream()` ([issue 33](../issues/33-route-control-unused-bridge-api.md)) | The step-4 cutover moved route preferences to the `prefs` flow | 6 |
| `VoiceFormat.legacyServerVersion` and the parameter chain down to `VoiceReceive::setFormat` and `VoiceSend::setFormat` ([issue 35](../issues/35-legacy-server-version-unread.md)) | The 1.2.2 workaround that read it was deleted in 5a. Decided 2026-11-25: delete the chain. A future version-keyed branch would add its own reader | 7 |
| `InvalidSampleRateException`, `AudioException`, `AudioInitializationException`, `NotConnectedException`, `NotSynchronizedException`, `HumlaLogger`, `HumlaNetworkListener`, and `HumlaUDPMessageListener` | Upstream Humla API whose throwers and callers went in steps 2–7. `InvalidSampleRateException`'s KDoc keeps it "for API compatibility" with external callers, but the library has none | 8 |
| `NoopVoiceLifecycle`, `NoopRouteLifecycle`, and `InMemoryLocalFactsStore` in `main` | Only tests use them | 9 |
| `PacketBuffer` and `HumlaUDPMessageType` in `main` | Only `VoiceCodecFixtureGeneratorTest` uses them, as the Kotlin reference for the codec goldens | 9 |
| R8 keep rules in `app/proguard-rules.pro` for `NativeAudioInputEngine`, `NativeVoiceIo`, `NativeVoiceCodec`, `DecodedVoiceFrame`, and `DecodedPing` | The classes were deleted in 5a.3a–5a.4 | 10 |
| "Interim" comments on `PolicyCollector`, `VoiceStatus`, `ChatEventFormatter.denyReason`, `AppContainer.chatFormatter`, and `VoiceEngine.sendUdpPing` | Each describes a seam a finished step removed | 11 |

**Rules for every commit.**

- **Delete what the commit makes dead,** including tests that only
  exercised it, as in step 7.
- **No behavior change except where a commit says so.** Three changes are
  intended. The transport's 800 ms SRV bound starts to work (commit 1). The
  server-list pings use the transport's rule for host literals (commit 3).
  The crash-reports screen stops calling the native loader on the main
  thread (commit 5).
- **Allowlist counts fall in the commit that removes the lines.** The gate
  already enforces this.

Commits, in order:

1. **One SRV resolver.** `SrvResolver` in `se.lublin.humla.net`, which stays
   Android-free, has one `suspend fun resolve(host, port)` that returns the
   host and port to connect to. It takes the blocking lookup (default:
   MiniDNS `ResolverApi.INSTANCE.resolveSrv`) and the timeout (default
   800 ms) as constructor parameters, so tests can drive it. The rule is
   `TransportImpl.resolveIfNeeded`'s:
   - a non-zero port returns the host and port without a lookup;
   - an IP literal (`TlsHostnameVerifier.isIpLiteral`, or any `:`) or an
     onion host (`TlsHostnameVerifier.isOnionHost`) returns the host with
     the default port;
   - otherwise `_mumble._tcp.<host>` is looked up, and the first record
     after `SrvUtil.sortSrvRecords`, minus its trailing dot, wins;
   - no record, a failure, or a timeout returns the host with the default
     port, and `CancellationException` propagates.

   The lookup runs as a `Deferred` in a scope the resolver owns, inside
   `runInterruptible(Dispatchers.IO)`. The caller awaits it under
   `withTimeoutOrNull`. When the timeout fires, the caller cancels the
   `Deferred`, which interrupts the lookup the way today's
   `future.cancel(true)` does, and returns without waiting for it.
   `TransportImpl.resolveIfNeeded` calls the resolver, and the
   transport's own copy of the rule and its `HumlaTCP.isIpLiteral` call
   are deleted.

   **Behavior change:** today's `withTimeoutOrNull(800)` wraps a blocking
   call it cannot interrupt, so the transport's lookup was bounded only by
   MiniDNS's per-query timeout. From this commit the 800 ms bound in the
   code holds, as it already does for pings.

   `SrvResolverTest` pins each rule with a fake lookup. It also pins that a
   lookup that ignores interrupts still returns the default port when a
   short injected timeout expires, and that cancelling the caller
   propagates. The connect path changes, so `./scripts/test_interop.sh`
   runs on this commit and must stay green.

   **What no automated check runs.** The interop server is reached at an
   explicit port, so interop exercises the resolver's short-circuit, never
   a lookup. `SrvResolverTest` uses a fake lookup. The default lookup is
   therefore kept to one line that calls
   `ResolverApi.INSTANCE.resolveSrv(lookup)` and returns its sorted
   records, the same call both paths make today. A real lookup runs only
   in the on-device SRV check below. Adding a DNS server to the interop
   harness is out of scope.
2. **Delete `HumlaTCP`.** It has no caller after commit 1. Deleted with it:
   its `TCPConnectionListener`, its allowlist entry (3), and its
   `BOUNDARY_FILES` entry. `HumlaSSLSocketFactory`'s two comments that
   name it are reworded to name `TransportImpl`. `check_architecture.py`'s
   step-3 comment and `test_net_excludes_tls_factory_only`'s file names
   are updated to name files that exist. The allowlist goes from 10 / 30
   to 9 / 27.
3. **Server-list pings from coroutines.**
   - `ServerInfoTask` (an `AsyncTask`) becomes
     `suspend fun pingServer(server: Server, resolver: SrvResolver)`. It
     resolves through `SrvResolver`, then sends today's UDP ping with the
     same 12-byte request, 1 s timeout, length check, and source-address
     check.
   - `ServerAdapter` owns a scope
     (`SupervisorJob() + Dispatchers.Main`) and runs each ping on
     `Dispatchers.IO.limitedParallelism(MAX_ACTIVE_PINGS)`. The result is
     stored and `notifyDataSetChanged` is called on the main thread, so
     `mInfoResponses` becomes a plain map. `shutdown()` cancels the scope.
     `FavouriteServerListFragment`'s two `shutdown()` call sites (adapter
     replaced, view destroyed) stay as they are.
   - The ping function, the main dispatcher (default `Dispatchers.Main`),
     and the ping dispatcher (default
     `Dispatchers.IO.limitedParallelism(MAX_ACTIVE_PINGS)`) are constructor
     parameters, and `FavouriteServerAdapter` passes them through. The
     test passes one `StandardTestDispatcher` for both, so pings and their
     results run in an order the test controls.
   - `Server` loses `resolvedHost`, `resolvedPort`, `getSrvHost`,
     `getSrvPort`, and `srvResolve`, along with their `Looper`, `Log`,
     `Patterns`, and executor imports. The fields were `@Ignore` and
     `@IgnoredOnParcel`, so the Room schema and the parcel bytes do not
     change. Nothing is lost by dropping the cache:
     `updateServers` builds new `Server` objects from the database on
     every resume.

   **Behavior change:** the pings classify host literals with the
   transport's rule. Today's `Patterns.IP_ADDRESS` (IPv4) or `:` check
   differs only for bracketed and trailing-dot forms.

   Two files leave the allowlist: `Server.kt` (4) and `ServerAdapter.kt`
   (1), which takes it to 7 / 22. A Robolectric `ServerAdapterTest` binds
   the adapter with a fake ping on the test dispatcher. It asserts the
   progress row before the result, the version, users, and latency after
   it, the offline row for a failed ping, and no callback after
   `shutdown()`. The 50-ping cap is today's constant carried over, not a
   tested invariant.
4. **Chat images from coroutines.** `MumbleImageGetter` owns a scope
   (`SupervisorJob() + Dispatchers.Main`), and downloads run on
   `Dispatchers.IO.limitedParallelism(2)`. After a download, the cache
   write, the failed-set or pending-set update, and the listener
   notification all happen on the main thread. That is where
   `Html.fromHtml` calls `getDrawable`, so the two
   `Collections.synchronizedSet`s become plain sets.

   Notifications are coalesced with a pending flag. The posted
   notification is launched on `Dispatchers.Main`, not `Main.immediate`,
   so every download that finishes in one looper turn produces one
   callback, as today's `removeCallbacks` and `post` pair does.
   `shutdown()` cancels the scope and clears both sets. A `getDrawable`
   after `shutdown()` starts nothing, which replaces the
   `RejectedExecutionException` guard. The SSRF-checked fetch stays as it
   is. The getter takes three constructor parameters, so the test can
   fake each: the fetch (defaulting to the real one), the main dispatcher
   (default `Dispatchers.Main`), and the fetch dispatcher (default
   `Dispatchers.IO.limitedParallelism(2)`).
   `ChannelChatAdapter` keeps the defaults.

   `MumbleImageGetter.kt` (4) leaves the allowlist, which takes it to
   6 / 18. A new Robolectric test passes one `StandardTestDispatcher` for
   both dispatchers. That makes the coalescing deterministic: two
   completions queue before the posted notification runs. It asserts:
   - two downloads that finish in one turn produce one callback;
   - a failed URL is not fetched again;
   - nothing is called back after `shutdown()`.

   The two-fetch cap is today's pool size carried over, not a tested
   invariant: a single-threaded test dispatcher cannot observe it, and a
   real-thread test could only check it with timing. The pure-function
   tests in `MumbleImageGetterTest` do not change.
5. **Crash reports without threads.**
   - `CrashReportsViewModel` gains `nativeCrashAvailable: StateFlow<Boolean>`.
     It starts `false` and is set once by a probe that runs in
     `viewModelScope` on an injected dispatcher (default `Dispatchers.IO`).
     The probe defaults to `NativeCrashTest::isAvailable`, and a probe that
     throws reports `false`.
   - The fragment's existing `STARTED` collector also collects the probe's
     flow. `refreshCrashTestState` and the click-time re-checks read
     `nativeCrashAvailable.value`, so the main thread never calls
     `isAvailable`. The warm-up `Thread` is deleted.
   - The ANR test posts its freeze with `requireView().post`, still after
     the click returns, and `anrHandler` is deleted.

   **Behavior change:** until the probe returns (a few milliseconds after
   the screen opens), the native button shows its "unavailable" summary.
   Today the first refresh blocks the main thread on the native load if
   the warm-up thread has not finished.

   `CrashReportsFragment.kt` (2) leaves the allowlist, which takes it to
   5 / 16. `CrashReportsViewModelTest` adds the probe cases (initially
   `false`, `true` after the probe, `false` when it throws).
6. **`RouteControl`'s unused bridge API** ([issue 33](../issues/33-route-control-unused-bridge-api.md)).
   The five members go. `scoRequested` and `handsetStream` become plain
   fields confined to the route thread, because the setters were their
   only off-thread readers. `RouteThreadConfinementTest` drops its
   `setScoRequested` call. `appliedRoute` stays atomic for
   `isMediaRouteActive`, which the activity reads.
7. **The legacy server version** ([issue 35](../issues/35-legacy-server-version-unread.md)).
   `VoiceFormat` is deleted, and `UdpFormat` takes its place in
   `Transport.voiceFormat` (renamed `udpFormat`), `VoiceCommand.Start`,
   `VoiceLifecycle.startVoice`, `NativeVoiceEngine.setFormat`,
   `nativeSetFormat`, and both native `setFormat`s.
   `fromServerVersion` and the default move to `UdpFormat`. The format
   decision at the first `Version` does not change. Native code changes,
   so `./scripts/test_native_audio.sh` runs through `check.sh`. The format
   reaches `Start`, so `./scripts/test_interop.sh` runs on this commit and
   must stay green.
8. **Unused upstream types.** The eight types in the dead-code table are
   deleted, and with them the `se.lublin.humla.exception` package, which
   then is empty.
9. **Test-only types out of `main`.** `NoopVoiceLifecycle`,
   `NoopRouteLifecycle`, and `InMemoryLocalFactsStore` move to
   `libraries/humla-testing`, which holds the shared fakes. `PacketBuffer`
   and `HumlaUDPMessageType` move to `libraries/humla/src/test` in the same
   package, beside their only user. The comments in `VoiceCodec.cpp` that
   cite `PacketBuffer` as the reference still hold.
10. **Stale R8 rules.** The keep rules for the five deleted classes, and
    their comments, are deleted. Rules that match nothing do not change
    what R8 keeps.
11. **Retired interim notes.** The comments in the dead-code table are
    rewritten to describe the code as it is. For example, `PolicyCollector`
    is the policy collector whose output `VoiceEngine.setPolicy`
    consumes, not an interim before 5a, and `denyReason` formats the
    permission-denied dialog for `MainViewModel`, not an observer bridge.
12. **The gate holds the end state.** In `check_architecture.py`:
    - `BOUNDARY_FILES` is reduced to the five files in
      [enforcement](../enforcement.md#end-state), dropping `VoiceEngine.kt`.
    - **Boundary only.** The check fails if an allowlist entry is not in
      `BOUNDARY_FILES`, or if a `BOUNDARY_FILES` path is not on the
      allowlist. A boundary file whose last match goes must leave both
      lists in the same commit, so no unused permission stays behind.
    - **New patterns:** `\bAsyncTask\b`, `\bExecutorService\b`, and
      `\bThreadFactory\b`. After commits 3–5 they match nothing, so no
      count changes.
    - **R8 keep rules.** Every `se.lublin.` name in
      `app/proguard-rules.pro` must match production code, including
      names after `implements` or `extends`. Production code includes the
      generated `protobuf` package, which the tripwire skips. A plain name
      is checked up to its first `$` and must have a source file. A name
      with a wildcard (`*` or `?`) is a pattern: the text before the first
      wildcard must begin at least one production class's fully qualified
      name. Today's only pattern, `se.lublin.humla.protobuf.Mumble**`,
      matches `Mumble` and `MumbleUDP`.
    - A test in `test_check_architecture.py` reads the fenced block under
      enforcement's [end state](../enforcement.md#end-state) heading and
      asserts that it equals `BOUNDARY_FILES`. That is the acceptance test
      of [issue 31](../issues/31-boundary-files-mismatch.md).

    Each rule gets a failing and a passing case in
    `test_check_architecture.py`.
13. **Unreferenced types.** `check_architecture.py` fails on a top-level
    class, interface, or object that is never named again in production
    code. A type fails when it is declared under `app/src/main`,
    `app/src/foss`, or `libraries/humla/src/main/java` (not the generated
    `protobuf` package), its simple name appears only once in its own
    file, and it appears in no other production file. Production files
    are Kotlin, Java, XML resources and manifests, `proguard-rules.pro`,
    and C++ under `libraries/humla/src/main/jni` outside the three
    submodules (JNI looks classes up by name).

    The check is deliberately narrow. It catches a class whose last
    caller was deleted, and test-only types left in `main` (commit 9
    moves today's). It does not catch dead members, or dead types that are
    still named somewhere. It needs no exception list today. Tests cover a
    dead type, a type used only in its own file, one named only from a
    manifest, one named only from C++, and one named only from a test
    (which fails).
14. **Docs.**
    - The living specs (`README.md`, `app.md`, `client.md`, `route.md`,
      `state.md`, `transport.md`, `voice.md`, and `testing.md`) name
      classes that steps 2–8 deleted only as history ("ported from",
      "the former"). They never name one as today's owner or writer. This
      covers:
      - `voice.md`'s "Interim mute" section and its `AudioHandler` and
        `AudioOutput` references;
      - `app.md`'s step-7 listener-bridge bullet;
      - `transport.md`'s `HumlaTCP` link;
      - `route.md`'s and `client.md`'s "today's `ConfigDelta`" comments;
      - `client.md`'s note that SRV resolution writes into `Server`;
      - the "today's `X`" phrasings for `HumlaService`, `MumlaService`,
        `ModelHandler`, and `StateEventLogger`.

      `enforcement.md`'s step-1 seed blocks stay as records.
    - The [issue index](../issues/README.md) is checked against the code:
      - 31, 33, and 35 are resolved by this step;
      - 19 was merged (`89dd9b57` is on `master`);
      - 14 is checked against `AppLocalFactsStore`;
      - 02 is checked against `ConnectionService.onDestroy`;
      - 07 and the other resolved rows stay as they are.
    - [Open items](../open-items.md): the "TCP reader as a coroutine" row,
      decided before the step, moves to the resolved table now that
      `HumlaTCP` is gone.
    - [`AGENTS.md`](../../../AGENTS.md)'s list of what
      `check_architecture.py` enforces gains the boundary-only, R8, and
      unreferenced-type checks.
    - [Testing](../testing.md#on-device-testing) gains step 8's
      device-only risks, and `PROGRESS.md` and the
      [migration](../migration.md) table get their rows.

**Not in this step.** These items stay tracked where they are:

- the behavior fixes in the issue backlog (02 if it still applies, 10,
  11, 20, 21, 22, 26, 27, 30, and 06's rule-2 sweep);
- the `connectEpoch` and `HumlaNative` open items;
- unused Android resources;
- `CrashReporter`'s comment line that names `runBlocking` (a boundary
  file may keep its matches).

**Done when:**

- **Executable:** `SrvResolverTest`, `ServerAdapterTest`, the new
  `MumbleImageGetter` Robolectric test, and the `CrashReportsViewModelTest`
  probe cases pass. They drive the code that commits 1, 3, 4, and 5 make
  live, as listed in those commits, except the real MiniDNS lookup (see
  commit 1).
- **Executable:** `./scripts/test_interop.sh` is green on commits 1 and 7
  and on the branch head. It covers the transport's explicit-port
  short-circuit and, for commit 7, the format at `Start`, not an SRV
  lookup. `./scripts/check.sh` and `assembleFossDebug` are green on the
  head.
- **Executable:** `test_check_architecture.py` has a failing and a passing
  case for every rule commits 12 and 13 add, and `check_architecture.py`
  passes on the head.
- Invariants and their detectors:
  - **Only boundary files hold threads, locks, or handlers:** the
    boundary-only check. The allowlist and `BOUNDARY_FILES` list the same
    five paths.
  - **No thread pool or `AsyncTask` outside a boundary file:** the
    concurrency tripwire with commit 12's patterns.
  - **The spec's boundary list is the gate's:** the `enforcement.md` test
    from commit 12.
  - **R8 keeps only classes that exist:** commit 12's keep-rule check.
  - **No production type is declared and never named:** commit 13's
    check, narrowed as written there.
- **Allowlist:** `HumlaTCP.kt` (3), `Server.kt` (4), `ServerAdapter.kt`
  (1), `MumbleImageGetter.kt` (4), and `CrashReportsFragment.kt` (2) leave
  the list. That takes it from 10 / 30 to 5 / 16, and every remaining
  entry is a boundary file: `AppContainer.kt` (2), `CrashReporter.kt` (6),
  `HumlaSSLSocketFactory.kt` (5), `TransportImpl.kt` (1), and
  `RouteControl.kt` (2).

**On-device checks** (at the developer's discretion; see
[testing](../testing.md#on-device-testing)):

- the server list fills in pings, including for a host that only
  resolves through SRV (saved with port 0);
- connecting to that host works. This and the ping above are the only
  checks that run a real SRV lookup, which matters for commit 1's
  behavior change;
- external and base64 chat images load, and leaving the chat while they
  load does not crash;
- the crash-test buttons enable only while disconnected, and the ANR test
  still freezes.

## Kill criterion

**Step 8:** if a non-boundary file can only leave the allowlist by giving
some object a long-lived thread of its own, that object is a boundary.
Add it to [enforcement](../enforcement.md#end-state)'s list on `master`
first, as a reviewed design change, rather than to `BOUNDARY_FILES` in the
branch. If the unreferenced-type check cannot pass without an exception
list, narrow or drop the check on `master` rather than exempting names in
the branch.
