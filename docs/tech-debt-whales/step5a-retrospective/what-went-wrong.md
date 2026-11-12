# What Went Wrong in Step 5a

Part of the [step 5a retrospective](README.md). Commit hashes refer to the
discarded attempt (see [where the attempt lives](README.md#where-the-attempt-lives)).

## Summary

The attempt was not one bug. It was a large, all-at-once cut-over whose
done-when never required voice to work, verified by repeated code-review
rounds instead of execution, while the design drifted and the spec was
edited to match. The final runtime failure (below) was simply the last of
several "production does nothing" defects, and the only one that shipped.

## The runtime failure

After the cut-over, nothing in production code calls
`System.loadLibrary("humlaaudio")`. Before 5a, four classes did:
`NativeAudioInputEngine`, `NativeAudioOutputEngine`, `CryptState`, and
`NativeCrashTest`. The first three were deleted; the new `VoiceEngine`
declares 18 `external fun`s but no loader. The built `libhumlaaudio.so`
exports all of its JNI symbols; it is simply never loaded.

The failure was silent because of the `VoiceEngine` constructor:

```kotlin
handle.set(
    try { nativeCreate(this) }
    catch (_: UnsatisfiedLinkError) { 0L }   // "Unit tests run without the native library"
    catch (_: Exception) { 0L },
)
```

ART throws `UnsatisfiedLinkError` ("No implementation found for long
se.lublin.humla.voice.VoiceEngine.nativeCreate(...)"), the catch turns it
into a zero handle, and every call site no-ops on a zero handle. The app
runs; voice is dead. The fallback that let JVM unit tests run without the
native library was exactly the production failure mode, made silent.

A side effect: `NativeCrashTest` still loads the library from its static
initializer, which the Crash Reports settings screen touches. Opening that
screen before connecting would likely have made voice work, so the bug
could look intermittent.

## Why every automated layer stayed green

- **JVM unit tests** expect the library to be absent
  (`startVoiceDegradedWithoutNative`), so "native is dead" is a passing
  state.
- **The native host suite** calls C++ directly, never through the Kotlin
  JNI binding on ART.
- **The release build** checks compilation and linking, not load order.
- **Murmur interop** never ran the Android voice path:
  `InteropVoiceLink` is a TCP-only stub that moves no audio. `testing.md`
  lists "from step 5a: voice between two clients over UDP and over the
  tunnel", but that scenario was never added, and 5a's done-when did not
  require it.

## Root causes

### 1. The done-when never said "voice works"

5a's done-when lists golden, allocation, jitter-pool, codec, loopback,
queue, and sequence-lock tests, a tripwire, and deletions. All of them can
hold while production is silent, and they did.

### 2. A big-bang cut-over with no live consumer

Items 1–3 of the step (fixtures, engine refactor, codec) are pure and
verifiable offline. Item 4 (`voice_io`: threads, queues, socket, crypt,
JNI, facade) had **no production consumer** until item 5 switched
everything over at once and deleted the Kotlin path in the same step. When
voice was dead, a dozen new mechanisms were equally suspect, and there was
no old path to fall back to.

Steps 1–4 succeeded with the opposite pattern: small slices, interim
adapters (`LegacyVoiceLink`, `VoiceControl`) so old and new coexisted, and
each slice field-tested in daily use before the next.

### 3. Review rounds substituted for execution

The branch went through eight review-fix rounds, each changing 600–1600
lines. Three of them found that the production path did nothing:

- Round 2 (`9d7c3372`): "wire real Opus/RNNoise backends into VoiceIo
  **(production was silent)**".
- Round 3 (`ad9d58ac`): "JNI **never built by the gate**".
- Round 7 (`ee109e48`): "render path **returned 0 for every 960-sample
  sink call**", a regression introduced by round 6's own fix.

Each time a reviewer reading code caught it; nothing that executed the
path did. The library-load failure survived all eight rounds. A loop that
keeps finding branch-killing defects is not converging; it is a signal that
the slice is wrong.

### 4. The design drifted, and the spec was edited to match

The original `voice.md` I/O layer had **no mutexes** apart from OCB2's
encrypt and decrypt locks. Its realtime rule 1 read: "The capture and
render threads **never wait on a lock**, allocate, or call into Kotlin."

The attempt ended with 10 mutexes outside the spec (9 in `VoiceIo`: policy,
settings, pending, input, output, cmd, send, socket, resync; plus a static
JNI handle-registry mutex), three native threads, and marshalling machinery
for cross-thread engine operations. Mapped against the original design:

| Mutex | Original design |
|---|---|
| policy, settings | Sequence locks; readers never block. Realtime threads now lock every frame |
| input, output | Unneeded: each engine is driven by exactly one thread, and start resets engines before capture/render begin. Their existence implies other threads touch the engines, which also required the `pending` marshalling path |
| cmd | Defends against a race the spec rules out: "the control thread is the only caller of start and stop" |
| send, socket, resync, JNI registry | Lifetime and ordering machinery not in the spec, added piecemeal across review rounds |

Rather than flagging this as divergence, the branch rewrote `voice.md`:
rule 1 became "take only short, bounded locks… engine touches run under
the input/output engine mutexes", and the sequence lock became a
"mutex-guarded snapshot… replaced during 5a review". Because every change
arrived with a matching spec edit, the drift was invisible as drift.

The migration's general kill criterion covers exactly this case: "if a file
cannot leave the allowlist without new coordination machinery, treat it as
a design problem and revisit the spec, not the code." The spec was
revisited, but only to record what the code already did.

### 5. Behavior changes inside a behavior-pinned step

5a's engine and codec work was meant to be pinned by golden outputs, yet
the codec also changed behavior: `volume_adjustment` went from "parsed and
ignored, as today" to honored; the 1.2.2 CELT workaround was deleted; an
empty `opus_data` terminator went from delivered to dropped. Each is
defensible as upstream parity, but together they added "is it the refactor
or the new behavior?" to every diagnosis.

### 6. Deferring a failed criterion had already been normalized

This pattern predates 5a. Step 1's done-when (the harness must reproduce
the `2e2a067d` race) failed and was deferred to step 3 with a rationale;
step 1's kill criterion says that case means the harness cannot vouch for
later steps. Step 2's `PROGRESS.md` row waved through "net-package
failures are the pre-existing native-loader set". See
[what's still fine](whats-still-fine.md) for the current status.

## Lessons carried into the spec

Proposed in [proposed-spec-edits.md](proposed-spec-edits.md):

- Every step and sub-step done-when includes an executable end-to-end
  check of what it makes live.
- A failed done-when or kill criterion is never deferred with a rationale;
  the step stops, or the spec is changed on `master` first.
- Spec edits to a step's own section land on `master` before code depends
  on them; a step branch does not edit the design it implements.
- Review rounds are capped; a third round with branch-killing findings
  means re-slice, not round four.
- Native-load failure is loud on Android; the JVM-test fallback is
  injected, not caught.
- 5a is split into four sub-steps, each shipped and field-tested before the
  next.
