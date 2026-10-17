# 02 — Audio pipeline

Factual parity audit of audio capture, processing, coding, transport
packetization, playout, and platform routing in Mumla OLED versus upstream
Mumble. No recommendations, no judgments.

## 1. Scope and files read

Mumla OLED files read fully, every line:

- `libraries/humla/.../audio/AudioInput.kt` — capture thread, `AudioRecord`
  setup, hardware effects, read/recreate loop
- `libraries/humla/.../audio/AudioOutput.kt` — `AudioTrack` lifecycle, render
  loop, `Pacer`, UDP/protobuf parse, talk-state mapping
- `libraries/humla/.../audio/NativeAudioInputEngine.kt` — JNI surface,
  VAD/hold/squelch defaults, RNNoise model cache
- `libraries/humla/.../audio/NativeAudioOutputEngine.kt` — JNI surface, talk
  ordinals, jitter-margin setter
- `libraries/humla/.../audio/BluetoothScoManager.kt` — SCO state machine, dual
  backend, timeout/retry
- `libraries/humla/.../audio/inputmode/` — `ActivityInputMode.kt`,
  `ContinuousInputMode.kt`, `ToggleInputMode.kt`, `IInputMode.kt`
- `libraries/humla/.../audio/InvalidSampleRateException.kt`
- `libraries/humla/.../protocol/AudioHandler.kt` — pipeline builder, bandwidth
  adaptation, mute gate, packetize, PTT/VAD wiring
- `libraries/humla/.../util/VoiceTargetMode.kt`
- `libraries/humla/.../model/TalkState.kt`
- `libraries/humla/.../jni/audio_engine/` — `AudioInputEngine`,
  `AudioOutputEngine`, `OpusVoiceEncoder`, `OpusVoiceDecoder`,
  `HysteresisVad`, `RnnoiseProcessor`, `AdaptiveLeveler`,
  `PreSpeechRingBuffer`, `SoftLimiter`, `BiquadFilter`, both JNI bridges
  (headers plus implementations)
- `app/.../preference/AudioSettingsFragment.kt` (inflate-only, delegates to XML)
- `app/.../Settings.kt` (audio-key accessors and companion defaults)
- `app/.../res/xml/settings_audio.xml` (full preference list)
- `libraries/humla/.../Constants.kt` (transmit modes, frames-per-packet sanitize)
- `libraries/humla/.../util/ConfigDelta.kt` (audio delta fields)
- `libraries/humla/.../model/User.kt` (mute/deafen/priority/local-mute fields,
  via targeted grep)

Mumla OLED files partially read: `app/.../service/MumlaService.kt` (route
policy, `buildConnectDelta`, `onSettingChanged`, PTT key handlers, SoundPool,
proximity); `libraries/humla/.../HumlaService.kt` (route policy,
`MODE_IN_COMMUNICATION`, `BluetoothScoManager` wiring, media-device queries,
audio-source/stream setters, transmit/VAD/amplitude/bitrate/frames/half-duplex/SCO/leveler
appliers, priority speaker, voice-target builder — sampled via scoped grep).
Unread: `jitter/jitter.c` internals (only the margin define and call sites
observed), `opus`/`rnnoise` submodule internals (only consumed APIs observed at
call sites), `Android.mk`/`Application.mk`/crypto wiring, DataStore migration,
notification/overlay PTT consumers (grep hits only).

Upstream files sampled (grep plus narrow line-range reads, not every line):
`src/mumble/AudioInput.h` (echo-option vector, `canEcho`, preprocessor member,
frame-size comment, `adjustBandwidth`, `getNetworkBandwidth` signatures);
`src/mumble/AudioInput.cpp` (Opus application-mode switch, CBR, bandwidth
degrade, overhead, AGC init, RNNoise create/process, VAD decision,
PTT/continuous branches, mute gate, loopback target, position send);
`src/mumble/AudioOutput.h` (registrar priority, speaker-position vector,
`calcGain`, `playSample`, channel/sample-rate params);
`src/mumble/AudioOutputSpeech.cpp` (stereo decoder plus phase-inversion,
jitter init/margin, get/put/tick/update-delay call sites);
`src/mumble/AudioPreprocessor.h` (Speex echo-state, AGC getters/setters,
denoise toggle, VAD toggle signatures); `src/mumble/AudioStats.cpp` (bitrate/AGC/VAD-bar
sources); `src/mumble/Settings.h` (transmit mode, quality, jitter size, VAD
min/max, frames-per-packet, volumes, positional distances/bloom, loopback mode).
Backend presence (`ALSAAudio`, `JackAudio`, `WASAPI`, `CoreAudio`, `ASIOInput`,
`AudioOutputToken/Buffer/Cache/Sample`, `AudioConfigDialog`, `AudioWizard`,
`ManualPlugin`, `ProcessResolver`, `VolumeAdjustment`, listener sliders) was
confirmed by directory listing only; contents unread.

## 2. Input chain — capture device and sample rates

| Upstream behavior | Status | Mumla OLED evidence |
|---|---|---|
| Multiple backends with device selection (`ALSAAudio`, `JackAudio`, `WASAPI`, `CoreAudio`, `ASIOInput`, PulseAudio, PipeWire, OSS) | Missing | Single `AudioRecord` path only: `AudioInput.kt` `getMinBufferSize`, `setSampleRate(SAMPLE_RATE)`; `SAMPLE_RATE=48000`. No backend switch, no device enumeration UI in scope |
| Variable `iSampleRate` with Speex resamplers (`srsMic`/`srsEcho`, `iMicFreq`/`iEchoFreq` negotiation) | Missing | Fixed 48 kHz end to end: `AudioInput.kt`, `NativeAudioInputEngine.kt`, `AudioHandler.kt` companion `SAMPLE_RATE=48000`; `AudioHandler.kt` Builder `setInputSampleRate` deprecated ("accepted and ignored"). `InvalidSampleRateException.kt` retained with a comment noting no in-tree thrower/catcher remains |
| Sample-rate options 8/12/16/24/44.1/48 kHz in config UI | Missing | `Settings.kt` `PREF_INPUT_RATE="input_quality"`, `DEFAULT_RATE="48000"`; `inputSampleRate` parses the `inputQuality` string but `AudioHandler` ignores it. `settings_audio.xml` exposes no sample-rate list |
| Echo-capture coupling (`addMic`/`addEcho`, echo channels, multi-echo, filter-size from nominal lag) | Missing | No echo-reference capture. `AudioInput.kt` captures mono mic only; no `addEcho`, no multi-channel echo frame. The native input engine's `processFrame` takes no echo input |
| AAudio/Oboe low-latency path (WASAPI-exclusive / JACK-graph analogue) | Missing | `AudioInput.kt` uses `AudioRecord.Builder` and `AudioTrack.Builder` only; no AAudio stream or Oboe symbol in scope. `AudioOutput.kt` sets `PERFORMANCE_MODE_LOW_LATENCY` |

## 3. Input chain — input modes

| Upstream behavior | Status | Mumla OLED evidence |
|---|---|---|
| Continuous transmit forces speech | Supported | `ContinuousInputMode.kt` `shouldTransmit=true`; native `case InputMode::CONTINUOUS: shouldTransmit=true`. Mode id `TRANSMIT_CONTINUOUS=2` in `Constants.kt`; mapping in the `AudioHandler.kt` constructor |
| VAD amplitude/SNR-gated speech with hold | Partial | Dual-threshold hysteresis present but in a different signal domain with a fixed hold: `HysteresisVad` defaults 0.35/0.25/25 frames/-65 dB; score = neural speech probability gated by squelch. Upstream `fVADmax=0.98/fVADmin=0.80` are amplitude-domain versus 0.35/0.25 neural-probability domain; upstream `iVoiceHold` is user frames versus fixed 25 frames (`DEFAULT_HOLD_FRAMES`), settable only via JNI with no preference wiring |
| Push-to-talk gated on a talk-key counter plus double-push window | Partial | PTT gate present, double-push absent: `AudioInputEngine.h` `PTT_HOLD_FRAMES=15` (150 ms); `ToggleInputMode.kt` `isTalkingOn` flag; `AudioHandler.kt` `setPttTalking` fans out to both `ToggleInputMode.isTalkingOn` and native. No double-push timer symbol in scope; the service `onTalkKeyDown/Up` only toggles talking state when the input method is PTT and not toggle mode |
| Toggle-PTT latch (press toggles rather than holds) | Supported | `ToggleInputMode.kt` `toggleTalkingOn()` flips `isTalkingOn`; `Settings.kt` `isPushToTalkToggle` (`togglePtt`); service branches on it |
| PTT while muted, previous-PTT edge, PTT audio cues | Partial | Mute overrides PTT (muted forces `shouldTransmit=false` and clears the PTT hold); self-talk cue `playPttSound` in the service (`mPTTSoundEnabled`, PTT on/off sounds) gated on self talk state, not on a previous-PTT edge. No talking-while-muted metering export; the talking callback emits only `isTalking` transitions |
| Plugin `overwriteMicrophoneActivation` forcing speech | Missing | No such symbol in scope; the continuous branch is the only forced-transmit path |

## 4. Input chain — VAD tuning

| Upstream behavior | Status | Mumla OLED evidence |
|---|---|---|
| Separate `fVADmin` / `fVADmax` sliders | Partial | Single user threshold mapped to a pair: `Settings.kt` `PREF_THRESHOLD="vadThreshold"`, default 35; `detectionThreshold` = `vadThreshold/100`; `ActivityInputMode.kt` init `vadMax=clamp(threshold)`, `vadMin=vadMax*0.7`; `setThreshold` uses the same formula. No independent minimum slider |
| `iVoiceHold` hold frames | Partial | Hold exists but is not user-tunable: fixed 25 frames; the JNI passthrough `nativeSetVadHoldFrames` has no preference key in `settings_audio.xml` or `Settings.kt`. Fixed PTT hold of 15 frames likewise untunable |
| VAD mode `SignalToNoise` versus amplitude | Partial | Score selection is squelch-gated neural probability with energy fallback, not a user switch: score = neural probability when peak dB is above squelch, else zero, with an energy-gate fallback. No `vsVAD` preference |
| Attack/decay shaping | Missing | No attack/decay constants in the VAD or input engine; hold is a frame counter. Slew limiting exists only in the output-adjacent `AdaptiveLeveler` gain slew, not in the VAD envelope |
| Squelch/noise-floor handling | Supported | RMS dB clamped to `[-96,0]` dBFS; default squelch floor -65 dB (`HysteresisVad.h`, `NativeAudioInputEngine.kt`); JNI passthrough `setVadSquelchFloor`/`getVadSquelchFloor` |
| Pre-speech lookahead preventing onset clipping | Supported (different mechanism) | 80 ms ring buffer (`PreSpeechRingBuffer.h` `DEFAULT_CAPACITY_FRAMES=8`); constructed with 8 × 10 ms frames; silence pushed in VAD mode, flushed on onset, cleared on mode change/mute/termination. Upstream frame counters have no direct counterpart; Mumla OLED uses a frame counter plus accumulated frames |

## 5. Input chain — preprocessor

| Upstream behavior | Status | Mumla OLED evidence |
|---|---|---|
| Speex `AudioPreprocessor` denoise toggle plus Speex AGC (target, max gain, increment/decrement, gain getter) | Partial | Speex preprocessor absent; replacement is RNNoise plus `AdaptiveLeveler` plus hardware effects. `AdaptiveLeveler` targets -18 dBFS with gains in [0.25, 4.0], slow EMA, per-frame slew cap, and speech/energy gates; hardware `AutomaticGainControl` also enabled on the capture session. `AudioHandler.kt` Builder hardcodes `preprocessorEnabled=true` with a follow-up TODO noting the flag is now constant. No dB/sec increment/decrement or 1–32768 target-level API |
| RNNoise toggle plus Speex-vs-RNNoise selection, noise-suppress level | Partial | RNNoise present as boolean on/off with a bundled model, no strength/mode selection: `RnnoiseProcessor` frame size 480, model from bundled assets when the preprocessor is enabled; squelch-gated bypass feeds zeros to advance state. No suppress-level dB control |
| Speex acoustic echo cancellation (echo state per frame size, sample-rate arg) | Missing (replaced) | No Speex echo state in the input engine or JNI. Echo control is Android hardware `AcousticEchoCanceler` on the capture session (see `docs/echo-cancellation/`), held in `MODE_IN_COMMUNICATION` so hardware effects couple. No filter-length/resync-lag API |
| `EchoCancelOption` per-output-system options UI | Missing | No echo-option list in `settings_audio.xml`; no such option symbol in scope |
| Infrasonic high-pass before denoise/VAD | Supported (different filter) | Butterworth high-pass, 90 Hz cutoff, Q 0.7071, applied per frame before VAD. Upstream high-pass is inside the Speex preprocess path, not a standalone biquad (mechanism delta) |
| Amplitude boost with anti-clip | Supported | `AudioHandler.kt` `mAmplitudeBoost` from `Settings.amplitudeBoostMultiplier` (`inputVolume/100`, `settings_audio.xml` max 200 default 100); applied via the leveler when on, or via a tanh-knee soft limiter when off |

## 6. Codec parameters — Opus matrix

| Parameter | Upstream (cited) | Mumla OLED (cited) | Delta |
|---|---|---|---|
| Application mode | Switched by bitrate: at/above 64 kbps restricted-lowdelay, at/above 32 kbps audio, else voice | Fixed `OPUS_APPLICATION_VOIP` | Mumla OLED never selects audio/lowdelay at high bitrates |
| Channels | Encoder mono; decoder stereo-pretend with phase-inversion disabled | Encoder mono, decoder mono | Mumla OLED mono throughout; no stereo pretend |
| Sample rate | Variable plus resampling | 48000 fixed (input, encoder, decoder) | No rate negotiation |
| Bitrate default/range | Default 40000, degrade floor 8000 | Default 40000, UI 8–192 kbps, floor 8000 in `computeEffectiveConfig`, live `OPUS_SET_BITRATE` | Ranges match at default/floor; Mumla OLED ceiling 192 kbps from UI |
| VBR/CBR | CBR | Hard CBR with constraint pinned | Same CBR; Mumla OLED additionally pins the constraint |
| Complexity | libopus default (no explicit set in sampled lines) | Fixed 6 | Mumla OLED pins 6 |
| Signal type | Not sampled | Forced voice | Upstream side unverified beyond sampled lines |
| Bandwidth | Not sampled | Forced fullband | Upstream side unverified beyond sampled lines |
| In-band FEC | Decoder FEC path present; encoder flag not sampled | Encoder in-band FEC on; decoder FEC-recovery plus packet-loss concealment with deferred-debt concealment | Both FEC-capable; Mumla OLED encoder flag explicit |
| Packet-loss percent | Not sampled | Fixed 10, no preference | Upstream side unverified beyond sampled lines |
| DTX | Upstream DTX flag exists in full `Settings.h` (unread in this run) | Forced off | Mumla OLED DTX off |
| Frames per packet | Default 2; encode buffered frames, zero-pad missing | Same set {1,2,4,6} enforced in three places, default 2, same UI values; accumulator zero-pads underfilled packets and emits a silence terminator when empty | Same set/default |
| Maximum bundle | 120 ms (5760 samples at 48 kHz) | Same 120 ms cap; output queue rejects over-2048-byte packets, decode scratch at 5760 | Same bundle cap; buffer sizes differ by direction |
| Capture frame | `iSampleRate/100` | 480 samples (10 ms at 48 kHz) | Same at 48 kHz |
| Render quantum | System buffer size | Fixed 960 samples (20 ms); track floor two quanta over the minimum buffer size | Mumla OLED fixed 20 ms quantum |
| Jitter margin | Default 1 frame × frame size | 4 frames | Mumla OLED four times the upstream default margin |
| Gate timeout / miss expiry | 20-frame grant (per Mumla OLED parity comment; upstream lines unread) | Gate timeout 20 frames, dead-miss 10 frames | Same timeout number; miss-expiry upstream counterpart unread |
| Voices cap | Unread | 32 | Upstream side unverified |

Terminator handling: the end-of-speech marker (header bit 13) is preserved in
both transports; zero-length terminators are encoded as silence, never empty.
Bundled datagrams are parsed chained per 10 ms frame with sequence stepping;
parsing stops before a trailing 12-byte positional trailer, with a documented
intentional tolerance divergence (Mumla OLED plays the valid prefix where
upstream discards the whole packet). The positional trailer itself is dropped
on receive and never sent.

## 7. Output chain — jitter buffer, resync, decode, mix

| Upstream behavior | Status | Mumla OLED evidence |
|---|---|---|
| Speex jitter buffer init plus margin from the jitter-size setting | Supported | Same library, same verbs; default margin 4 frames; setter exists via JNI with no preference wiring (upstream default is 1 frame — differs) |
| Jitter get/put/tick/update-delay with quiet-gated delay update | Supported | Same verbs; delay updated on quiet (peak below gate), on every miss, and on conceal paths; buffered-count query for the drain check |
| Startup availability gate (fresh voice silent until buffered) | Supported | Gate holds until margin + 1 frames queued (counting the union of accepted intervals), force-starts after 20 frames; gated quanta emit zero PCM so `AudioTrack` backpressure paces |
| Resync nominal-lag enlarging the echo filter; output resync threshold | Missing | No resync/nominal-lag symbol in the output engine; margin is a floor with Speex adaptation only |
| Per-packet resampling, stereo interleave, mixer/buffer sizing | Missing | No resampler in the output engine; fixed 48 kHz mono `AudioTrack` |
| In-band FEC recovery plus concealment | Supported | Null-data decode path; reserves a silent FEC-debt slot, recovers via FEC decode when aligned, else concealment; decoder reset after 5 consecutive decode errors |
| Loss-boundary crossfade, per-voice fade in/out, bus soft saturation | Supported (superset mechanism) | 96-sample (2 ms) equal-power crossfades, head blending from a tail snapshot, carry overflow across quanta, onset fade-in / finish fade-out, soft-knee saturation at -6 dB asymptoting to full scale |
| Render pacing against the device clock | Supported (analogous) | `Pacer`: 20 ms quantum, lead capped at two quanta or track frames, 5 ms poll, ~200 ms stall break, wrap/reset handling, track floor ~40 ms over minimum |
| Maximum voices cap with eviction reporting passive | Supported (policy differs) | Cap 32; evicts the highest session id ("newest-voice approximation") with passive state. Upstream eviction policy unread; no parity claim on policy |
| Talk-state mapping normal/shout/whisper/listen/invalid | Supported | Flags 0 → talking, 1 → shouting, 2 → whispering, 3 → talking (listen shown talking), 0xFF → passive, default talking; ordinals match the Kotlin bridge; the Java layer maps onto `TalkState` on the main handler |
| Local mute drop before decode | Supported | Both legacy and protobuf queue paths return early when the user is null or locally muted, under the output lock |
| Per-user volume | Missing | `User.kt` shows `isLocalMuted` but no volume field; the mix is an unweighted sum with only global saturation. No per-user gain API surfaced (only listener-channel volume) |
| Priority-speaker ducking (attenuating others) | Missing | `User.kt` stores `isPrioritySpeaker` and the service persists the flag; no ducking/attenuation in the render path |
| Positional playback (speaker position, distance/bloom gain) | Missing | No distance/bloom gain in the output engine; the positional trailer is explicitly skipped; no plugin position feed |
| Sample playback (`playSample`, output token, buffer/cache/sample) | Missing | No such path in scope; PTT cues use Android `SoundPool`, not the voice mixer |
| Loopback playback (local/server loopback mode, attenuate) | Partial | `VoiceTargetMode` defines server loopback (id 31, unsigned-mask decode); the target-id byte is carried into packets (0 normal, 1–30 whisper, 31 loopback). No local-loopback render path, no local/server mode switch, no attenuate flag |

## 8. Session, routing, and platform integration

| Upstream behavior | Status | Mumla OLED evidence |
|---|---|---|
| Audio session selection (WASAPI exclusive, ALSA device, JACK ports, CoreAudio devices, ASIO channels) | Missing (Android route policy instead) | Service owns the audio manager: `MODE_IN_COMMUNICATION` hold/release, handset stream default music, `VOICE_COMMUNICATION` source on voice route versus mic on media, `VOICE_CALL` versus music stream, media-device presence queries; output starts on the voice-call stream when the voice route or SCO is active, else music, plus explicit speakerphone control |
| Bluetooth SCO/HFP link management | Supported (Android-only; no upstream counterpart) | `BluetoothScoManager.kt`: 8 s connect timeout, 2 attempts, idle/starting/active states, refusal/timeout/error reasons, retry policy, API-31 communication-device path versus legacy SCO start/stop plus state broadcasts, device-availability check, epoch-guarded route release. `AudioHandler.kt` confirmed-link SCO enable plus voice-route enable (recreate to apply) |
| A2DP / wired-headset media routing | Supported (Android-only) | Media-route-selected versus SCO-route-active policy; sound-pool init selects voice-cue versus media usage by route so cues follow voice output and media routes keep A2DP volume. No codec selection |
| Wired-headset / speakerphone / earpiece explicit control | Supported (Android-only) | Speakerphone setter; handset preference maps to voice-call versus music stream; proximity lock for handset when Bluetooth SCO is inactive |
| PTT keys / mouse buttons / shortcuts | Partial | Android keys only: `talkKey` default unbound, unbound/unknown never match; key-select preference, hot-corner list, hide/toggle/half-duplex checkboxes; talk broadcast receiver; media-key claim in the connection notification. No mouse-button/shortcut-editor counterpart |
| Half-duplex (mute output while transmitting) | Supported | Preference plus engine flag; output fills the mix with zeros when half-duplex-muted |
| Audio statistics UI (per-user ping/jitter/loss) | Missing | No statistics view in scope; engine sources (bitrate, current gain, peak energy) exist but have no UI wiring in scope files |
| Audio wizard / config dialog (device test, VAD calibration) | Missing | The audio settings fragment only loads the XML; no wizard, loopback test, or calibration tone in scope |
| Manual-plugin positional hooks | Missing | No plugin symbols in scope; no position query; voice-target mode covers only ids 0/1–30/31 |
| Voice targets / whisper (shout/whisper contexts, target message, loopback) | Partial | Target-id byte plumbed (set/clear, legacy flags plus protobuf target, unsigned-mask decode, normal/whisper/server-loopback mapping); shout versus whisper talk-state mapping present. Full whisper-target editor (channels/users/links) and target-id lifecycle beyond resets were outside the read scope |
| Per-user local mute persistence | Supported | Output honors `user.isLocalMuted`; connect delta passes local-mute/local-ignore histories; config delta carries both lists; service applies the mute history |
| Self-mute/deafen/suppress gate including optimistic mute plus deaf-implies-mute | Supported | Self/server-mute/deafen/suppress seeds, optimistic-self-mute echo handling, fail-closed deaf-implies-mute merge, mic stays open while muted (Doze-shield comment), shutdown emits talking-stopped. The local-loopback-mode analogue is missing per the loopback row |

## 9. Processing-chain diagram (text)

Input (capture → encode → packetize):

```text
AudioRecord(48k mono, 10ms/480)
  → hardware AEC/NS/AGC on the capture session
  → capture thread read/recreate loop
  → AudioHandler.onAudioInputReceived → native process frame
  → Butterworth high-pass at 90 Hz
  → RMS dB calculation + squelch gate (-65 dB)
  → RNNoise inference (or zero-feed bypass) → speech probability
  → hysteresis VAD (thresholds 0.35/0.25, hold 25)
     ├─ CONTINUOUS: always transmit
     ├─ PTT: talking flag + 15-frame hold
     └─ VAD: process frame; mute forces silence
  → onset: flush 80 ms ring (VAD only)
  → silence: push ring
  → adaptive leveler (-18 dBFS target) or soft limiter when off
  → accumulate 1/2/4/6 frames → Opus VOIP/CBR/fullband/complexity 6/FEC
  → flush accumulator (zero-pad; silence terminator if empty)
  → JNI packet/talking callbacks
  → legacy (flag + varint frame number + header bit 13) or protobuf
     Audio(target/frame number/opus/terminator)
  → connection send (legacy or protobuf UDP header)
```

Output (parse → jitter → decode → mix → render):

```text
UDP/protobuf receive
  → parse: flag/session/sequence, chained header+payload,
     stop before 12-byte positional trailer, drop non-Opus, drop locally muted
  → protobuf parse: session/opus/sequence/flags/terminator, same mute gate
  → native queue packet → jitter put + gate-union accounting
  → render mix (960 samples): gate (margin+1 or 20-frame timeout or
     terminator bypass)
  → per voice: serve carry → jitter get → FEC-recover/conceal
     → ticks/update-delay → append chunk with 96-sample equal-power crossfade
  → onset fade-in / finish fade-out (terminator drain or miss threshold)
  → sum mix → soft-knee saturate (-6 dB knee) → clamp to 16-bit
  → Pacer (lead cap, 5 ms poll, stall break)
  → AudioTrack (48 kHz mono, low-latency, voice usage on voice route else media)
  → talk events drained each render → main-thread TalkState update
```

Half-duplex mute (zero-fill) applies at the final write; local-mute drops at
parse time before queueing; self-mute drops at encode time via the muted flag.

## 10. Unread and partially verified items

- Full bodies of `HumlaService.kt` and `MumlaService.kt` beyond cited
  offsets/greps (route funnel, `apply()` ordering, voice-target editor,
  whisper-target persistence, reconnect logic).
- Upstream `AudioConfigDialog`, `AudioWizard`, `AudioStats` UI, input/output
  UI files, backend device enumeration, `VolumeAdjustment`, `ProcessResolver`,
  manual-plugin positional hooks, positional data, listener/user volume
  sliders, client-user volume/priority fields, server-handler ping stats —
  presence only.
- `jitter.c` internals, `opus`/`rnnoise` submodule internals, ABI wiring,
  RNNoise model provenance/training data.
- Settings repository/DataStore defaults parity for every audio key; exact
  audio-bandwidth formula and varint-buffer details.
- Upstream encoder signal/bandwidth/complexity/loss/DTX lines outside the
  sampled grep window; upstream output fade/mix/ducking specifics outside
  sampled lines.
