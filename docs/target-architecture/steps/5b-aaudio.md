# Step 5b — AAudio

Part of the [migration](../migration.md). **Status: done.**
This is the step's plan as it was executed, kept as a record;
[`PROGRESS.md`](../PROGRESS.md) summarises what landed, and the living
specs hold the current design.

AAudio directly (not Oboe), in callback mode, for both streams (decided
2026-11-23; see [open items](../open-items.md#resolved)). No spikes gate the
step: the choices are made, and on a single device the on-device check and
daily use below are the evidence that counts. Android 11–12 is not tested.

Three items, each a checkpoint that keeps voice working. Item 1 adds AAudio
code that nothing uses yet and leaves the live 5a path untouched, item 2
switches voice over to it in one commit, and item 3 deletes the old path
and points the JNI render and capture entry points at the new code for
tests.

**Files.** A new header, `voice_io/VoiceStreamContext.h`, defines the state
the callbacks share with the rest of `voice_io`. The callback bodies go in
a new host-compiled file, `voice_io/VoiceStreamCallbacks.cpp`, added to the
explicit source lists of the Android `humlaaudio` target, the host
`humlaaudio` target, and `test_voice_io`. Everything that calls AAudio,
including the stream JNI functions, goes in an Android-only file,
`voice_io/AAudioStreams.cpp`, and only the Android target links `aaudio`.
The host library never sees AAudio, so it still links; host tests never
open streams. `nativeDestroy` also stops and closes any streams
defensively before deleting the context, so a missed Kotlin teardown
cannot leave callback user data dangling.

1. **Native stream layer in `voice_io`, not yet used.**
   - **Shared state.** `VoiceStreamContext` holds pointers to the `VoiceIo`
     and `VoiceSend` instances, the `renderActive` and `captureActive`
     flags with `RenderGuard` and `CaptureGuard`, the status counters
     (`lastDroppedInbound`, `lastDroppedTunnel`, `lastDroppedJitter`,
     `lastDroppedOutbound`, `lastHasActiveVoices`), the render carry
     buffer, the stream generation counter, and the disconnect atomic.
     These move out of the JNI-private `VoiceEngineContext`, which now owns
     one `VoiceStreamContext`, created with it in `nativeCreate`. The
     header declares `streamContextFromHandle(jlong)`, defined in
     `NativeVoiceEngineJni.cpp`, so `AAudioStreams.cpp` can reach it. The
     existing JNI entry points and lifecycle asserts read and write the
     same fields, so 5a behavior is unchanged.
   - **Stream calls.** `openStreams`, `startStreams`, `stopStreams`, and
     `closeStreams` for both streams, as JNI functions in
     `AAudioStreams.cpp` and methods on `EngineNative`. Open takes the
     route's [stream parameters](../route.md#stream-parameters), clears the
     carry buffer and disconnect slot, bumps the generation before building
     the streams (it starts at 0, so the first open returns 1) and never
     rolls it back: a failed attempt consumes its generation, so a late
     disconnect can never match a later stream pair. It returns a
     `LongArray` of the input stream's session ID
     (allocated with `AAUDIO_SESSION_ID_ALLOCATE`) and the new generation,
     or `null` on failure. Start returns `false` if either stream fails to
     start. Start and stop are separate from open and close so Kotlin can
     attach the capture effects before the streams start and release them
     before they close. Stop also reads each stream's
     `AAudioStream_getXRunCount` and logs it on the control thread; nothing
     logs from a callback.
   - **Stop and the engine handoff.** Stop requests stop on both streams
     and waits, with a 500 ms timeout, for `AAUDIO_STREAM_STATE_STOPPED`,
     treating `AAUDIO_STREAM_STATE_DISCONNECTED` as final too, since a
     disconnected stream may never report stopped. Close calls
     `AAudioStream_close`, after which no callback runs; the control thread
     touches an engine only after close (see [engine
     lifecycle](../voice.md#start-stop-reconfigure)).
   - **Render callback body.** Each render call must cover whole 10 ms
     frames, and FEC needs at least two per call: the output engine's gate
     timeout counts `samples / FRAME_SIZE`, so sub-frame requests would
     never time the gate out, and a missing frame is recovered from its
     successor's in-band FEC only when both fall in the same render call
     (an unrecovered slot is concealed at the end of the call). The render
     body therefore calls `VoiceIo::render` only in 960-sample (20 ms)
     chunks, 5a's render quantum, into a two-frame carry buffer, and copies
     out exactly the frames requested, keeping the remainder for the next
     call. A loss in a chunk's first frame is recovered from FEC and a loss
     in its second is concealed, as in 5a; one-frame chunks would conceal
     every loss. This adds up to 20 ms of output latency. It reports
     whether everything it wrote was silence and stores the status
     counters. The AAudio callback ignores the silence report and always
     returns the full buffer.
   - **Capture callback body.** Calls `VoiceSend::captureFrame` with
     whatever burst arrives; `VoiceSend` already carries the remainder in
     10 ms frames. It stores `lastDroppedOutbound`.
   - **Both callbacks** are C++ functions, never JNI (callbacks never touch
     the JVM), and set `renderActive` or `captureActive` through their
     guard for the whole call.
   - **Disconnects.** The error callback stores its stream's generation in
     the disconnect atomic and its `aaudio_result_t` in a second atomic
     (both initially 0; open and `nativeReset` clear them); AAudio's
     documentation says not to stop, close, or reopen a stream from that
     callback, and it does not log. The control thread logs the stored
     error when it tears the streams down.
     `VoiceNotifier` gains two constructor parameters, a pointer to the
     atomic and a second sink, both defaulting to `nullptr`/empty so the
     existing three-argument constructions in `test_voice_io.cpp` still
     compile. On every tick, before the talk-cell read that can return
     early, the notifier compares the atomic with the last value delivered
     and, when it changed, calls the sink, which calls a new listener
     method, `NativeVoiceEngine.Listener.onStreamDisconnect(generation:
     Long)`, separate from `onTalkSnapshot`. `nativeCreate` looks the
     method up as `(J)V` and fails creation if it is missing, as for the
     other listener methods. A disconnect during silence is therefore still
     delivered.
   - **Kotlin stubs.** So item 1 compiles and the interop tests that create
     a real `NativeVoiceEngine` still pass, `VoiceEngine` gets an empty
     `onStreamDisconnect` and `VoiceEngineTest`'s `FakeEngineNative` gets
     stub stream methods; item 2 fills them in.
   - **Host tests** go in `test_voice_send.cpp`, which already installs the
     counting allocator (`--wrap` link options and `-fno-builtin` on that
     file), and drive the callback bodies directly: render with burst sizes
     such as 96, 192, 441, and 960 frames writes exactly that many frames
     and allocates nothing; under 96-frame bursts a trickling voice's
     startup gate still times out, a loss in a chunk's first frame is
     recovered from FEC (the decoder sees an FEC decode), and a loss in a
     chunk's second frame is concealed; `notifyNow` delivers a disconnect
     with no talk change.

2. **Switch voice to AAudio.** In one commit, `VoiceEngine` opens the
   AAudio streams through `EngineNative` wherever it builds `AudioInput`
   and `AudioOutput` today (the pipeline rebuild on start, route change,
   and handset-stream preference change). The `inputFactory` and
   `outputFactory` seams go. `VoiceEngineTest` fakes the stream calls in
   its existing `FakeEngineNative`; `VoiceEnginePipelineTest`, which uses a
   zero-handle `NativeVoiceEngine` today, gets a fake `EngineNative` that
   records opens, starts, stops, and closes. `VoiceEngine` calls
   `setSpeakerphone` before `openStreams`, so the route is final when the
   streams open and the speakerphone change cannot disconnect them. It
   attaches `AcousticEchoCanceler`, `NoiseSuppressor`, and
   `AutomaticGainControl` to the returned session ID before the streams
   start (skipping them if the ID is `AAUDIO_SESSION_ID_NONE`), and
   releases them after the streams stop and before they close, as
   `AudioInput` does today, on every route (the session ID is allocated
   either way). `BuiltPipeline` drops its `InputPipeline` and
   `OutputPipeline` fields and records the route, profile, input preset and
   usage, the attached effects, and the generation the last open returned;
   `pipelineForTests()` returns that record.

   - **On `onStreamDisconnect(generation)`** it posts one reopen of both
     streams to the voice control thread. The control thread skips it if
     voice is not running, no streams are open, or the generation does not
     exactly equal the live pair's generation. Only a disconnect of the
     exact generation built by the last reopen counts as a failed reopen
     within 5 s; route and preference rebuilds clear that history.
   - **A failed open, start, or reopen** stops and closes whatever streams
     are open, releases the capture effects, and leaves voice without
     streams. It follows the [voice failure table](../voice.md#failures):
     `PipelineError`, and the stored pipeline record is cleared so the next
     route or route-preference change rebuilds (today's `tryEnsurePipeline`
     returns early when nothing changed).
   - A facade JVM test pins the skips and the failure paths. From this
     commit `AudioInput` and `AudioOutput` are unused at runtime;
     `VoiceEngine` still implements `AudioInput.AudioInputListener` and
     `RenderDriver` until item 3.

3. **Delete the old path.**
   - Kotlin: `AudioInput.kt` and `AudioOutput.kt` (with their allowlist
     entries), the `Pacer` and `AudioOutputPacerTest`, `Pipeline.kt`,
     `RenderDriver.kt`, and `VoiceEngine`'s `AudioInput.AudioInputListener`
     and `RenderDriver` supertypes: `onAudioInputReceived` goes, and
     `render` stays without `override` for the tests. In `VoiceEngine`,
     `NativeVoiceEngine`, and `VoiceEngineTest`'s fake, the members only
     the old path used go: `awaitWork`, `wakeForStop`, and
     `lastHasActiveVoices`.
   - Native: `nativeAwaitWork`, `nativeWakeForStop`, `lastHasActiveVoices`
     and the `hasActiveVoices` field, and the [render
     wake](../voice.md#render-idle-and-wake) (semaphore, `idle` flag,
     `awaitWork`, the producer posts). Tests: delete the render-wake tests
     in `test_voice_io.cpp`, and edit out the `hasActiveVoices` assertions
     in its ordinary render tests and the one use in `test_voice_send.cpp`.
   - Test entry points: `nativeRender` and `nativeCaptureFrame` stay,
     rewired to call the callback bodies, so the host tests that drive
     voice through `VoiceEngine.render` and `captureFrame`
     (`VoiceEngineTest`, `MurmurVoiceGateInteropTest`, `MurmurConnectTest`)
     exercise the same code AAudio runs. `nativeRender` keeps today's
     return value, 0 when the render body reports silence. `RenderGuard` is
     now used only in the callback bodies, so `check_architecture.py`'s
     voice lifecycle check splits in the same commit: the `RenderGuard`-use
     rule targets `VoiceStreamCallbacks.cpp`, the `renderActive` and
     `captureActive` assert rules keep targeting
     `NativeVoiceEngineJni.cpp`, and the fixtures in
     `scripts/test_check_architecture.py` follow.
   - Docs: update the audio pipeline description in `AGENTS.md` and the
     [decisions table](../migration.md#decisions-this-reverses).

AAudio xrun counts (read at stop, see item 1) and stream errors are logged
on the control thread, for diagnosis only; they gate nothing. Allocating a
session ID can give the input stream a higher-latency path (AAudio's
documentation warns of this), so the input may never get very short
callbacks; if capture xruns show up anyway, the input stream may drop to
`AAUDIO_PERFORMANCE_MODE_NONE`.

**Done when:** `AudioInput` and `AudioOutput` are deleted; the host tests
above pass; on-device debug and release pass (connect, voice both ways,
mute, deafen, push-to-talk, Bluetooth SCO and A2DP, handset with both
handset-stream settings, speakerphone echo); and the AAudio path is in
daily use without regressions the developer notices against 5a.

## Kill criterion

**Step 5b** regresses latency, glitches, echo cancellation, routing, or
connection survival in daily use and cannot be fixed. Revert the 5b
commits with `git revert` and stay on 5a's `AudioRecord` and
`AudioTrack`, which still leaves all transmit state on the native side.
