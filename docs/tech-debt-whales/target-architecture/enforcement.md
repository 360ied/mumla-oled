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

**Forbidden patterns** (regular expressions, word-bounded):
`@Volatile`, `@Synchronized`, `\bsynchronized\(`, `\bThread\(`,
`\bHandler\(`, `runBlocking`, `ReentrantLock`, `ReentrantReadWriteLock`,
`Executors\.`, `CountDownLatch`, `java\.util\.concurrent\.locks`.

**Allowlist:** `scripts/architecture-allowlist.txt`, one entry per line:

```text
# path                                                          count
libraries/humla/src/main/java/se/lublin/humla/net/HumlaTCP.kt   4
```

- `count` is the number of forbidden matches the file may contain.
- The check fails if a file not on the list has any match, if a listed
  file has **more** matches than its count, or if a listed file has
  **fewer** (the count must be lowered in the same commit, so the list only
  shrinks). A file with zero matches must be removed from the list.
- Step 1 seeds the list with today's 31 files and their counts. Every
  migration step's "done" includes lowering it.

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
`VoiceStatus`, `TalkActivity`, `RouteState`, and `SessionStats` by Kotlin
reflection and fails if any:

- property is a `var`;
- property's declared type is a `java.util` collection, a Kotlin
  `MutableList`/`MutableSet`/`MutableMap`, an array, or a
  `kotlinx.collections.immutable` *non-persistent* interface
  (`ImmutableList` and friends permit mutable implementations);
- class is not a `data class`, `data object`, enum, sealed interface, or one
  of an explicit list of immutable leaf types (`String`, primitives,
  `ByteString`, `InetAddress`, `X509Certificate`, `Server`).

Adding a new state type adds it to the walk automatically, because the
walk follows property types.

## Android-free packages

The check fails if any file in these packages imports `android.*`,
`androidx.*`, or an `R` class:

- `se.lublin.humla.state`
- `se.lublin.humla.client`
- the non-JNI parts of `se.lublin.humla.net` (everything except the TLS
  socket factory, which uses Android's trust store APIs)

This keeps state, transitions, the client state machine, and the reconnect
policy runnable in plain JVM tests with virtual time.

## Native tripwire

**Scope:** `libraries/humla/src/main/jni/audio_engine/`,
`libraries/humla/src/main/jni/voice_codec/`, and
`libraries/humla/src/main/jni/crypto/`.

**Forbidden includes** (all three directories): `<sys/socket.h>`,
`<netinet/in.h>`, `<arpa/inet.h>`, `<aaudio/AAudio.h>`, any `<oboe/…>`,
`<thread>`, `<pthread.h>`, `<semaphore.h>`, `<fcntl.h>`, `<unistd.h>`,
`<jni.h>`.

**Additionally forbidden in `audio_engine/` and `voice_codec/`** after
step 5a: `<mutex>`, `<shared_mutex>`, `<condition_variable>`, `<atomic>`.
OCB2 in `crypto/` keeps its locks, since two network threads share it.

**Existing exceptions** (allowlisted by file, removed when fixed):
- `crypto/CryptStateOCB2.cpp`: `<fcntl.h>` and `<unistd.h>` for key
  generation from `/dev/urandom` (client keys come from the server, so this
  path is unused in practice).
- `audio_engine/AudioOutputEngine.cpp`: `<android/log.h>` for logging.
- The JNI bridges (`NativeAudioInputEngineJni.cpp`,
  `NativeAudioOutputEngineJni.cpp`, `NativeCrashTestJni.cpp`,
  `crypto/NativeCryptStateJni.cpp`) until they are deleted or moved to
  `voice_io/` in step 5.

`voice_io/` is the only native directory allowed to include socket,
AAudio, thread, and JNI headers.

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
