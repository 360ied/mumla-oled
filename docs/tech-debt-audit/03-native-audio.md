# Native audio debt — JNI pipeline of Mumla OLED

Scope: `libraries/humla/src/main/jni/*` (`CMakeLists.txt`,
`audio_engine/*.cpp/h`, `crypto/*`), JNI bridge Kotlin
(`audio/NativeAudio*Engine.kt`), `scripts/test_native_audio.sh`. Read-only;
no builds run. Correct baselines confirmed: output JNI null/exception guards,
overflow-safe JNI window checks on both bridges, pinning-free crypto JNI, and
consistent `48000/480` sample-rate end to end.

## P0

| Location | Issue | Effort | Remediation |
|---|---|---|---|
| [`NativeAudioInputEngineJni.cpp`](../../libraries/humla/src/main/jni/audio_engine/NativeAudioInputEngineJni.cpp) (`nativeCreate`) | No JNI failure guards, unlike the hardened output bridge: throwing `new`, ignored `GetJavaVM` return, unchecked `NewGlobalRef`/`GetObjectClass`/2×`GetMethodID`/`NewByteArray`, no `ExceptionCheck`/`ExceptionClear`. A failed lookup leaves null methods; the packet lambda then silently drops all voice and a pending `NoSuchMethodError` poisons the next JNI call. The Kotlin input constructor also lacks the output-side zero-handle throw, making silence literal. | S | Mirror the output bridge: `nothrow` plus null checks plus `ExceptionClear` plus `delete ctx; return 0` on every failure; check `GetJavaVM == JNI_OK`; add the Kotlin zero-handle guard. |
| [`RnnoiseProcessor.cpp`](../../libraries/humla/src/main/jni/audio_engine/RnnoiseProcessor.cpp) (`RNNModel` mirror) + `.gitmodules` + [`CMakeLists.txt`](../../libraries/humla/src/main/jni/CMakeLists.txt) | `RNNModel` layout is hand-mirrored to work around an upstream `rnnoise_model_from_buffer()` gap (uninitialized `FILE*`). Any upstream field reorder/addition silently breaks model load/create/free. No `static_assert(sizeof/offsetof)`, no configure-time rev gate; submodules
gitlink-pinned but unenforced, no branch pin (the Opus pin comment lives in `CMakeLists.txt`, the RNNoise pin note in the processor header — neither machine-enforced). | M | Vendor a compile-time layout assertion, plus a model-version/git-rev check that fails configure on drift; pin or gate submodule revisions. |

## P1

| Location | Issue | Effort | Remediation |
|---|---|---|---|
| Both `nativeDestroy` paths + `NativeAudio*Engine.kt` join convention | Bare `delete ctx` with no drain/lock/generation. Input/output lambdas capture raw `ctx*` and dereference mutexes, refs, and buffers while `nativeDestroy` on another thread can delete them. Kotlin snapshots the handle outside the monitor precisely to avoid a mute-tap deadlock, so the handle is provably reachable after `destroy()` zeroes it. Convention-only, unenforced. | M | Reference-counted or generation-guarded handle (registry + mutex like `NativeCryptStateJni.cpp`, or `shared_ptr` retained by in-flight callbacks); make `destroy()` block until callbacks quiesce. |
| Host suite + `CMakeLists.txt` + `scripts/test_native_audio.sh` | Host `test_audio_engine` links Opus only and omits `RnnoiseProcessor.cpp`, either JNI bridge, and all RNNoise sources; host `humlaaudio` is crypto-only. Device-only code ships untested. Android targets also lack host `-Wall -Wextra -Werror`, so device warnings stay invisible. Line numbers drifted; substance verified. | M | Build host `humlarnnoise` plus `RnnoiseProcessor.cpp` into the host suite with a real-model-load test; compile JNI shims on host; add `-Wall -Wextra` to Android targets (promote to `-Werror` after cleanup). |
| Input callback lambdas | No `ExceptionCheck`/`ExceptionClear` after `SetByteArrayRegion`/`CallVoidMethod` (output side has them); non-daemon `AttachCurrentThread` vs output `AsDaemon`; `callbackMutex` held across the Java upcall, serializing capture against slow listeners. A throwing listener wedges capture. | S | Copy the output pattern: daemon attach, per-upcall exception check/log/clear, detach-only-if-attached, minimize work under lock. |
| [`OpusVoiceEncoder.cpp`](../../libraries/humla/src/main/jni/audio_engine/OpusVoiceEncoder.cpp) + `AudioInputEngine.cpp` + `OpusVoiceDecoder.cpp` | All `opus_encoder_ctl` returns discarded (9 calls); `setBitrate` accepts any `>0` with no upper clamp; decoder reset unchecked; `flushAccumulatorLocked` drops `encodedBytes <= 0` without counter/log, so `BAD_ARG`/`BUFFER_TOO_SMALL`/`INTERNAL_ERROR` vanish. Note: `getBitrate` returns cached `m_bitrate` when the encoder is null
and otherwise returns unchecked `ctl` output (`0` on failure); all `ctl`s stay
unchecked. | S | Check each `ctl`, log plus clamp on failure; count/log encode failures; assert validity after create; clamp `setBitrate` to the Opus range. |

## P2

| Location | Issue | Effort | Remediation |
|---|---|---|---|
| Large-model JNI pinning + per-packet global-buffer copy | Model load pins the Java heap array for the whole assign; per-packet path copies into a shared global array that Java must synchronously copy before the next packet overwrites it — an undocumented aliasing contract. Crypto bridge shows the preferred region-copy pattern. | S | Use length-plus-region copy into a native buffer; document `data[0,length)` copy-before-return or allocate per-packet arrays if listener diversity grows. |
| Fixed 48 kHz everywhere (`AudioInput.kt`, `AudioOutput.kt`, `BiquadFilter.h`) | Devices without a 48 kHz path fail closed on capture and risk init failure or silent resampling on playback; no native-rate query or software resample. | M | Query native rates, log actual vs requested, add a fallback or explicit unsupported-device error; parameterize the filter from the negotiated rate. |
| Partial `AudioRecord.read` zero-padded (`AudioInput.kt`, `AudioInputEngine.cpp`) | Short reads are padded with zeros, depressing RMS/VAD/leveler and injecting clicks. No fragment accumulation, no underrun/partial counters beyond an error log. | S | Accumulate fragments to whole 480-sample quanta; expose underrun/partial counters. |
| `MAX_OPUS_BUFFER_BYTES 1024` tight for 60 ms high-bitrate packets | Opus worst case exceeds the input buffer at high bitrate with multi-frame packets; constrained encode squeezes quality or errors silently. Output side correctly sizes `2048`. | XS | Size input to `2048` (or `1275*framesPerPacket`), clamp/log `setBitrate`, surface `BUFFER_TOO_SMALL`. |
| Android NDK warning hygiene + ABI/page notes | Android gets optimization/hardening flags but no `-Wall`; 16 KiB alignment on 32-bit `armeabi-v7a` and the `x86` omission lack in-tree rationale comments. | S | Add `-Wall -Wextra` to Android; comment page-size/ABI rationale in `CMakeLists.txt`. |
| Per-quantum `expf` saturation + move-emptied packet vector | `expf` per hot mixed sample plus steady-state reallocation on VAD speech bursts; unbounded scratch growth by Java length. | S | Fast saturation approximation; capacity-preserving clear with reserve; cap scratch. |

## P3

- RNNoise enabled-with-no-model is indistinguishable from active denoising
  (`initModel` no-ops, `process` returns `-1`, `isEnabled` still true). Expose
  `hasModel()`/`isActive()`, log once when modeless, surface to settings UI. XS.
- Static Opus tuning (complexity 6, FEC 10%, DTX off) never adapts to measured
  loss. Drive loss/bitrate from transport stats; document why DTX stays off
  for protocol parity. M.
