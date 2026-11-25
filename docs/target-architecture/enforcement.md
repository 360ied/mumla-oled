# Enforcement

Part of the [target architecture](README.md). Specifies the checks that
keep the architecture from eroding: the concurrency tripwire, the native
tripwire, the Android-free package check, the state immutability test, the
feature, service-binding, local-facts, R8, and unreferenced-type checks,
and the two remaining conventions.

All checks run in `./scripts/check.sh` as a new step before the Gradle and
native suites, implemented as one Python script,
`scripts/check_architecture.py`, with its own unit tests in
`scripts/test_check_architecture.py` (matching the repository's existing
`commit.py` / `worktree.py` pattern). The script is added in migration
step 1 and only ever gets stricter.

## Concurrency tripwire

**Scope:** `*.kt` and `*.java` under `app/src/main` and
`libraries/humla/src/main/java`, excluding the generated
`se/lublin/humla/protobuf/`.

**Forbidden patterns** (regular expressions):
`@Volatile`, `@Synchronized`, `\bsynchronized\s*\(`, `\bThread\s*[({]`,
`\bHandlerThread\s*\(`, `\bHandler\s*[\(.]`, `\brunBlocking\b`,
`\bReentrantLock\b`, `\bReentrantReadWriteLock\b`, `\bExecutors\.`,
`\bCountDownLatch\b`, `\bjava\.util\.concurrent\.locks\b`,
`\bThreadPoolExecutor\b`, `\bScheduledThreadPoolExecutor\b`,
`\bForkJoinPool\b`, `\bScheduledExecutorService\b`,
`\bnewSingleThreadContext\b`,
`\bGlobalScope\b`, `\basExecutor\b`, `\bTimer\s*\(`, `\bHandlerCompat\b`,
`\bAlarmManager\b`, `\bWorkManager\b`,
`\bkotlin\.concurrent\.thread\b`.

`\bThread\s*[({]` catches both `Thread(...)` and Kotlin's `Thread { … }`.
The word boundaries keep `currentThread(` from matching the `Thread`
pattern and `createAudioHandler(` from matching the `Handler` pattern;
`Thread.sleep`, `Thread.currentThread`, and friends are deliberately not
matched (they are not thread creation). The `Handler` pattern also matches
`Handler.` so the `Handler.createAsync` factory cannot evade the tripwire
(the boundary still keeps `ModelHandler.*` and `mainHandler.post` from
matching). Java-style `synchronized (lock)`
with a space matches, as does `Handler (`; only the import-qualified
`kotlin.concurrent.thread` is matched, because bare prose (`render thread (`
in comments) is too noisy. The constructor-form pools are listed
separately: `\bThreadPoolExecutor\b` does not match
`ScheduledThreadPoolExecutor`, because the preceding `d` is a word
character. Comments are not stripped: a pattern named in a comment
counts, which keeps the script simple and the comments honest.

**Allowlist:** `scripts/architecture-allowlist.txt`, one entry per line:

```text
# path                                                          count
libraries/humla/src/main/java/se/lublin/humla/net/HumlaTCP.kt   4
```

- `count` is the number of **lines** in the file that match at least one
  pattern (what `grep -cE` reports), so a line with two patterns counts
  once.
- The check fails if a file not on the list has any matching line, if a
  listed file has **more** matching lines than its count, or if it has
  **fewer** (the count must be lowered in the same commit). A file with
  zero matching lines must be removed from the list.
- Step 1 seeds the list with today's numbers. On `master` at `c988bbfe`,
  the pre-review patterns match 335 lines in 34 files; the whitespace
  variants closed in review add one honestly counted comment line, so the
  landed list holds 336 lines in 34 files. Step 1 recomputes the exact
  counts from the script itself when it lands.
- **Adding a boundary file.** New code that genuinely needs a thread or a
  handler (the transport reader, `RouteControl`, the `VoiceEngine`
  facade) may add itself to the list, but only if its path is in the
  script's `BOUNDARY_FILES` constant. Adding a path to `BOUNDARY_FILES` is
  a deliberate, reviewed change with a comment saying why. The list starts
  with these planned paths (the new ones are created by the steps named):

  ```text
  libraries/humla/src/main/java/se/lublin/humla/net/HumlaTCP.kt            # TCP reader thread (step 3 keeps the file)
  libraries/humla/src/main/java/se/lublin/humla/net/TransportImpl.kt      # Transport reader thread (step 3 extracts the Transport)
  libraries/humla/src/main/java/se/lublin/humla/net/HumlaSSLSocketFactory.kt
  libraries/humla/src/main/java/se/lublin/humla/route/RouteControl.kt      # step 4
  libraries/humla/src/main/java/se/lublin/humla/voice/VoiceEngine.kt       # step 5a (step 4 uses voice/VoiceControl.kt, removed in 5a)
  app/src/main/java/se/lublin/mumla/util/CrashReporter.kt
  app/src/main/java/se/lublin/mumla/AppContainer.kt                       # step 6: the one blocking settings read at process start
  ```

  `AppContainer.kt` joins in step 6 with exactly two matching lines, the
  startup read and its `runBlocking` import: the first settings snapshot
  must exist before the first activity applies its theme, and `DataStore`
  has no synchronous read (see [app](app.md#appcontainer)). Any third
  match in it is a violation.

  Every other change can only lower counts or remove entries.
- Every migration step's "done" includes lowering the totals.

It is a tripwire, not a proof: text search cannot see atomics used as
locks or mutability hidden in state. The
[state immutability test](#state-immutability-test) and the
[Android-free package check](#android-free-packages) cover the most likely
gaps.

### End state

Step 8 reduces the allowlist and `BOUNDARY_FILES` to the same five files,
all at real boundaries:

```text
libraries/humla/src/main/java/se/lublin/humla/net/TransportImpl.kt
libraries/humla/src/main/java/se/lublin/humla/net/HumlaSSLSocketFactory.kt
libraries/humla/src/main/java/se/lublin/humla/route/RouteControl.kt
app/src/main/java/se/lublin/mumla/util/CrashReporter.kt
app/src/main/java/se/lublin/mumla/AppContainer.kt
```

- `TransportImpl`: the reader thread's blocking socket read.
- `HumlaSSLSocketFactory`: TLS callbacks on the socket thread.
- `RouteControl`: owns its `HandlerThread` and the handler for platform
  callbacks.
- `CrashReporter`: the uncaught-exception handler.
- `AppContainer`: the startup settings read.

The planned list above also named `HumlaTCP.kt` and `VoiceEngine.kt`.
Step 3 moved the TCP reader into `TransportImpl` and left `HumlaTCP`
without a caller, so step 8 deletes it. Since 5a.4 the `VoiceEngine`
control thread has been `Dispatchers.IO.limitedParallelism(1)`, which
matches no pattern.

From step 8, `check_architecture.py` holds this state:

- **Boundary only.** The check fails if an allowlist entry is not in
  `BOUNDARY_FILES`, or if a `BOUNDARY_FILES` path is not on the allowlist.
  A boundary file whose last match goes must leave both lists in the same
  commit, so no unused permission stays behind. A new boundary file is
  added to the block above and to `BOUNDARY_FILES` in one reviewed change.
- **The block above is the gate's list.** A test in
  `test_check_architecture.py` reads the fenced block under this heading
  and fails unless it equals `BOUNDARY_FILES`.
- **More patterns.** `\bAsyncTask\b`, `\bExecutorService\b`, and
  `\bThreadFactory\b` join the forbidden patterns. They are hidden thread
  pools that the step-1 list missed, and nothing outside the deleted
  files matched them.

## State immutability test

A JVM unit test in `:libraries:humla`, `StateImmutabilityTest`, walks every
class reachable from `SessionState`, `ClientState`, `VoicePolicy`,
`VoiceSettings`, `VoiceStatus`, `TalkActivity`, `RouteState`, and
`SessionStats` by Kotlin reflection and fails if any:

- property is a `var`;
- property's declared type is a `java.util` collection, a Kotlin
  `MutableList`/`MutableSet`/`MutableMap`, an array, or a
  `kotlinx.collections.immutable` *non-persistent* interface
  (`ImmutableList` and friends permit mutable implementations);
- class is not a `data class`, `data object`, enum, sealed interface, or one
  of an explicit list of immutable leaf types (`String`, primitives,
  `ByteString`, `InetAddress`, `X509Certificate`, `HandshakeFailure`,
  `Throwable`).

**What is walked.** The test follows only primary-constructor properties,
so derived members declared in a class body (such as `ServerState.tree`)
are not walked. It never descends into types on an explicit stop list,
currently just `Session` (reached through `ClientState.Connected`), which
is an object with a lifetime, not a value; its own state is a separate
root. Enums (`SyncPhase`, `InputMode`, `AppliedRoute`, `StreamProfile`,
`TalkState`) are leaves. `ClientState` holds `ServerEndpoint` and
`ClientCertificate` (a `ByteString`), not the Room `Server` entity or a
`ByteArray`, so nothing mutable is reachable. `ByteArray` appears only in
transport messages (`Inbound.Tunnelled`) and commands, which are not state
roots.

Adding a new state type adds it to the walk automatically, because the
walk follows property types. Step 1 adds the test as a scaffold that walks
nothing (marked `TODO(step2)`); each later step adds its roots when the
types exist.

## Android-free packages

The check fails if any file in these packages imports `android.*`,
`androidx.*`, or an `R` class, or uses a fully-qualified `android.*` /
`androidx.*` reference, reflection (`Class.forName`, `getIdentifier`), or
an `"android.` string literal without an import:

- `se.lublin.humla.state`
- `se.lublin.humla.client`
- the non-JNI parts of `se.lublin.humla.net` (everything except the TLS
  socket factory, which uses Android's trust store APIs)

The fully-qualified pattern is `\bandroid\s*\.`: every `android.*`
subpackage is matched, so `android.graphics.*`, `android.bluetooth.*`,
`android.hardware.*`, and `android.location.*` cannot be used without an
import. `\bandroidx\s*\.` matches all `androidx.` references.

This keeps state, transitions, the client state machine, and the reconnect
policy runnable in plain JVM tests with virtual time.

**Step-1 staging:** today's `HumlaConnection.kt`, `HumlaTCP.kt`, and
`HumlaUDP.kt` still use `Handler`/`Looper`/`Log`, so the script grandfathers
those three files until step 3 extracts the `Transport`. Only the TLS
socket factory exclusion above is permanent; the grandfathering is removed
in step 3, which is a strictness increase, not a relaxation.

## Feature isolation

From step 6, `check_architecture.py` lists the feature classes in
`app/src/main/java/se/lublin/mumla/feature/` (the classes that declare
`fun start(scope: CoroutineScope)`) and fails if **any file in that
package** names another file's feature class. The set is closed: any
`fun start` with a different signature (including `suspend fun start`)
fails instead of silently dropping out of the feature set. Shared
helpers in that package that are not features, such as the
connection-line formatter that the chat log and TTS both use, may be
referenced freely, but they are scanned as referrers too: moving a
cross-feature call into a start-less helper does not launder it.
Features get everything they share
from the container (see [app](app.md#features)); a feature that needs
another feature's output means that output belongs in the container as a
flow, such as the `talkKeys`, `speech`, and `appLog` hand-off flows.
Same-package references need no import, so the check matches class names,
not imports.

## No service binding

From step 7, `check_architecture.py` fails if a file under `app/src/main`
contains a `bind…Service` identifier (`bindService`, `bindIsolatedService`,
`bindServiceAsUser`) or `ServiceConnection`. It matches identifiers, not
call shapes, so a call split across lines is caught, and it counts comments
and strings like the render-thread check does: reword a mention rather than
allowlist it. Screens and features
collect the container's flows; `ConnectionService` holds only the
foreground lifetime, and its `onBind` returns `null` (see
[app](app.md#connectionservice)).

## Local-facts writer

From step 6, `check_architecture.py` fails if a file under `app/src/main`
other than the `LocalFactsStore` implementation and the `db` package (which
declares these methods) contains a call to `.addLocalMutedUser(`,
`.removeLocalMutedUser(`, `.addLocalIgnoredUser(`, `.removeLocalIgnoredUser(`,
`localMuteDao()`, `localIgnoreDao()`, `.setMutedAndDeafened(`, or the DAO-level
wrappers `insertLocalMutedUser(`, `deleteLocalMutedUser(`,
`insertLocalIgnoredUser(`, `deleteLocalIgnoredUser(`. The database/DAO
suspend writer is anchored on its receiver — `(database|db|dao).setLocalMute(`
and `.setLocalIgnore(`, plus the read wrappers `localMutedUsers(` and
`localIgnoredUsers(` — because the room database exposes the same rows
through a public API. The sanctioned service funnel
(`mService.setLocalMute` in `UserMenu.kt`) deliberately does not match:
it goes through the store. The patterns are call sites, so declarations
and tests never match, and the store's `suspend` path through the DAOs
is covered by the DAO patterns.
The session decides local mute, ignore, and self-mute, and its store is
their only writer (see [state](state.md#self-mute-and-deafen)); before
step 6, the adapter, the service, and the store all wrote them.

## R8 keep rules

From step 8, `check_architecture.py` fails if `app/proguard-rules.pro`
names a `se.lublin.` class that has no production source file. For a
nested name, only the part before the first `$` is checked. When the JNI
seams were deleted in step 5a, their keep rules stayed behind for
step 8 to find. A rule that matches nothing changes nothing in the build,
but it reads as a live JNI contract.

## Unreferenced types

From step 8, `check_architecture.py` fails on a top-level class,
interface, or object that is never named again in production code. A
type fails when all of these hold:

- it is declared under `app/src/main`, `app/src/foss`, or
  `libraries/humla/src/main/java`, outside the generated `protobuf`
  package;
- its simple name appears only once in its own file (the declaration);
- the name appears in no other production file.

Production files are Kotlin, Java, XML resources and manifests,
`proguard-rules.pro`, and C++ under `libraries/humla/src/main/jni` outside
the `opus`, `rnnoise`, and `nanopb` submodules, because JNI looks classes
up by name. Tests do not count, so a test-only type in `main` fails and
belongs in `libraries/humla-testing` or a test source set.

The check is deliberately narrow. It catches the usual leftover of a
migration, a type whose last caller was deleted. It cannot see dead
members, or dead types that are still named somewhere, such as one that is
only constructed by another dead type. It has no exception list. If it
ever needs one, narrow the check instead.

## Native tripwire

**Scope:** `libraries/humla/src/main/jni/audio_engine/`,
`libraries/humla/src/main/jni/voice_codec/`, and
`libraries/humla/src/main/jni/crypto/`.

**Forbidden includes** (all three directories): `<sys/socket.h>`,
`<netinet/in.h>`, `<arpa/inet.h>`, `<aaudio/AAudio.h>`, any `<oboe/…>`,
`<thread>`, `<pthread.h>`, `<semaphore.h>`, `<fcntl.h>`, `<unistd.h>`,
`<jni.h>`.

**Additionally forbidden in `audio_engine/` and `voice_codec/`** once the
engine refactor lands: `<mutex>`, `<shared_mutex>`, `<condition_variable>`,
`<atomic>`. The script holds this rule behind a constant,
`SINGLE_THREADED_ENGINE`, which the last engine-refactor commit of step 5a
flips to `True`. OCB2 in `crypto/` keeps its locks, since two network
threads share it.

**Exceptions** live in the same allowlist file, in a `[native]` section of
`path: header` lines, and follow the same ratchet: entries may only be
removed. The two `<mutex>` entries for the input and output engine bridges
are the one addition a step makes in place: the engine refactor moved the
lock out of the engine and into the interim JNI seam, so each entry
disappears with its bridge file (the output engine's in 5a.3a, the input
engine's in 5a.4), and the check fails if an entry
outlives the include it excuses. 5a.2 adds one more the same way:
`voice_codec/NativeVoiceCodecJni.cpp` takes `<jni.h>` for the interim
codec bridge Kotlin calls, and the entry leaves with the file in 5a.4.
The codec core itself stays free of JNI, so nothing else in `voice_codec/`
needs an exception.

```text
[native]
libraries/humla/src/main/jni/crypto/CryptStateOCB2.cpp: <fcntl.h>
libraries/humla/src/main/jni/crypto/CryptStateOCB2.cpp: <unistd.h>
libraries/humla/src/main/jni/audio_engine/NativeAudioInputEngineJni.cpp: <jni.h>
libraries/humla/src/main/jni/audio_engine/NativeAudioOutputEngineJni.cpp: <jni.h>
libraries/humla/src/main/jni/audio_engine/NativeAudioInputEngineJni.cpp: <mutex>
libraries/humla/src/main/jni/audio_engine/NativeAudioOutputEngineJni.cpp: <mutex>
libraries/humla/src/main/jni/audio_engine/NativeCrashTestJni.cpp: <jni.h>
libraries/humla/src/main/jni/crypto/NativeCryptStateJni.cpp: <jni.h>
```

- `CryptStateOCB2.cpp` reads `/dev/urandom` for key generation (client
  keys come from the server, so this path is unused in practice).
- The two engine bridges take `<mutex>` for the interim Kotlin path only:
  the engines are single-threaded by contract, but Kotlin still calls them
  from several threads (network threads queue voice, the control thread
  sets input configuration, a render thread mixes). The bridge, not the
  engine, serializes and holds the lock, and it is the only reason these
  files keep the header. Both exceptions disappear with the bridge files.
- The JNI bridges were deleted in step 5a (the last in 5a.4), and
  `NativeCrashTestJni.cpp` moved to `jni/diagnostics/`. The block above is
  the step-1 seed; the live list is the `[native]` section of
  `scripts/architecture-allowlist.txt`.
- Logging through `<android/log.h>` is not forbidden. Other `<sys/>` /
  `<asm/>` / SIMD-intrinsic headers for CPU detection (such as
  `<sys/auxv.h>`) remain allowed; only the listed I/O, thread, and JNI
  headers are banned.

Body-level indirection is also banned in the same three directories:
`dlopen`, `dlsym`, `AAudio_`, `Oboe`, `oboe/`, `syscall`, `mmap`,
`pthread_create`, `std::thread`, `__has_include`. All absent today; the
tripwire fails on any occurrence.

`voice_io/` is the only native directory allowed to include socket,
AAudio, thread, and JNI headers. Third-party submodules (`opus`,
`rnnoise`, `nanopb`) are outside the check's scope; the generated nanopb
files under `voice_codec/generated/` are inside it.

## Remaining conventions

Two rules cannot be checked mechanically. Reviews check them, and no
tripwire covers them yet; the enforcement state is tracked in
[issue 06](issues/06-update-conventions-not-enforced.md).

1. **No side effects inside `update {}`.** An atomic swap may run the
   lambda more than once. The lambda only computes the new value.
2. **Decide inside `update {}`, not after reading.** Code that reads
   `state.value` and then acts on what it read (for example, "if muted,
   unmute") can act on a stale value. Decisions that depend on current
   state are made inside the `update` lambda, and any effect follows from
   its result. Code holds IDs across time, never `UserInfo` or
   `ChannelInfo` values.
