# Enforcement

Part of the [target architecture](README.md). Specifies the checks that
keep the architecture from eroding: the concurrency tripwire, the native
tripwire, the Android-free package check, the state immutability test, and
the two remaining conventions.

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
`\bHandlerThread\s*\(`, `\bHandler\s*\(`, `\brunBlocking\b`,
`\bReentrantLock\b`, `\bReentrantReadWriteLock\b`, `\bExecutors\.`,
`\bCountDownLatch\b`, `\bjava\.util\.concurrent\.locks\b`,
`\bkotlin\.concurrent\.thread\b`.

`\bThread\s*[({]` catches both `Thread(...)` and Kotlin's `Thread { … }`.
The word boundaries keep `currentThread(` from matching the `Thread`
pattern and `createAudioHandler(` from matching the `Handler` pattern;
`Thread.sleep`, `Thread.currentThread`, and friends are deliberately not
matched (they are not thread creation). Java-style `synchronized (lock)`
with a space matches, as does `Handler (`; only the import-qualified
`kotlin.concurrent.thread` is matched, because bare prose (`render thread (`
in comments) is too noisy. Comments are not stripped: a pattern named in
a comment counts, which keeps the script simple and the comments honest.

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
  ```

  Every other change can only lower counts or remove entries.
- Every migration step's "done" includes lowering the totals.

**Expected end state:** about five files, all at real boundaries: the
`VoiceEngine` facade (owns the voice control thread and the JNI handle),
the TCP reader in `HumlaTCP`, `HumlaSSLSocketFactory` (TLS callbacks on the
socket thread), `CrashReporter` (the uncaught-exception handler), and
`RouteControl` (owns its `HandlerThread` and the handler for platform
callbacks).

It is a tripwire, not a proof: text search cannot see atomics used as
locks or mutability hidden in state. The next two checks cover the most
likely gaps.

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
`androidx.*`, or an `R` class:

- `se.lublin.humla.state`
- `se.lublin.humla.client`
- the non-JNI parts of `se.lublin.humla.net` (everything except the TLS
  socket factory, which uses Android's trust store APIs)

This keeps state, transitions, the client state machine, and the reconnect
policy runnable in plain JVM tests with virtual time.

**Step-1 staging:** today's `HumlaConnection.kt`, `HumlaTCP.kt`, and
`HumlaUDP.kt` still use `Handler`/`Looper`/`Log`, so the script grandfathers
those three files until step 3 extracts the `Transport`. Only the TLS
socket factory exclusion above is permanent; the grandfathering is removed
in step 3, which is a strictness increase, not a relaxation.

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
lock out of the engine and into the interim JNI seam, so both entries
disappear with the bridge files in 5a.4, and the check fails if an entry
outlives the include it excuses.

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
- The JNI bridges are deleted in step 5a (5b for anything `AudioInput` and
  `AudioOutput` still need), and `NativeCrashTestJni.cpp` moves to
  `jni/diagnostics/`.
- Logging through `<android/log.h>` is not forbidden.

`voice_io/` is the only native directory allowed to include socket,
AAudio, thread, and JNI headers. Third-party submodules (`opus`,
`rnnoise`, `nanopb`) are outside the check's scope; the generated nanopb
files under `voice_codec/generated/` are inside it.

## Remaining conventions

Two rules cannot be checked mechanically. Reviews check them, and the
pedantic review skill's checklist gains both:

1. **No side effects inside `update {}`.** An atomic swap may run the
   lambda more than once. The lambda only computes the new value.
2. **Decide inside `update {}`, not after reading.** Code that reads
   `state.value` and then acts on what it read (for example, "if muted,
   unmute") can act on a stale value. Decisions that depend on current
   state are made inside the `update` lambda, and any effect follows from
   its result. Code holds IDs across time, never `UserInfo` or
   `ChannelInfo` values.
