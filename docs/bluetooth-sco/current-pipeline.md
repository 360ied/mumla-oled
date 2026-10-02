# Current Pipeline and SCO Gaps

How audio routing works in Mumla OLED today, and exactly what is missing for
Bluetooth SCO. All paths are relative to the repository root.

## Capture path

```text
Settings.handset_mode
  → ServerConnectTask: MIC vs DEFAULT source
  → HumlaService.EXTRAS_AUDIO_SOURCE
  → AudioHandler.Builder.setAudioSource
  → AudioInput(mAudioSource): fixed 48 kHz mono AudioRecord, 10 ms frames
  → NativeAudioInputEngine.processFrame (RNNoise, VAD, Opus CBR)
```

Findings:

- [`AudioInput.java`](../../libraries/humla/src/main/java/se/lublin/humla/audio/AudioInput.java)
  hardcodes `SAMPLE_RATE = 48000`, `FRAME_SIZE = 480`. Hardware
  `NoiseSuppressor` + `AutomaticGainControl` are enabled on the session; no
  `AcousticEchoCanceler` is ever created.
- The only source selection in the app is
  [`ServerConnectTask.java`](../../app/src/main/java/se/lublin/mumla/app/ServerConnectTask.java#L59-L62):
  `handset_mode` chooses `MediaRecorder.AudioSource.DEFAULT`, otherwise `MIC`.
  `VOICE_COMMUNICATION` (the source tuned for SCO/echo-managed paths) is never used.
- `AudioRecord` is created once per `AudioHandler` lifetime. There is
  recreate-on-failure logic for `IllegalStateException`, but nothing re-creates
  capture on *route* change.

## Playback path

```text
Settings.handset_mode
  → ServerConnectTask: STREAM_VOICE_CALL vs STREAM_MUSIC
  → HumlaService.EXTRAS_AUDIO_STREAM
  → AudioHandler.initialize → AudioOutput.startPlaying(audioStream)
  → AudioTrack 48 kHz mono; USAGE_VOICE_COMMUNICATION iff VOICE_CALL stream
  → Pacer + native jitter buffer + render thread
```

Findings:

- [`AudioOutput.java`](../../libraries/humla/src/main/java/se/lublin/humla/audio/AudioOutput.java)
  maps `STREAM_VOICE_CALL` → `USAGE_VOICE_COMMUNICATION`, everything else →
  `USAGE_MEDIA`, on API 23+. The `Pacer` bounds render lead to ~40 ms and
  already names A2DP-scale buffers (4800–11532 frames) as a design input, so
  SCO's latency profile is expected to fit inside existing margins — to be
  validated on-device (see [Android platform requirements](android-platform.md));
  no DSP change is anticipated.
- Reference: [audio output pipeline](../audio-output/README.md).

## Service and routing ownership

- [`HumlaService.java`](../../libraries/humla/src/main/java/se/lublin/humla/HumlaService.java)
  builds the `AudioHandler` from intent extras (`configureExtras`) and recreates
  it on settings change (`createAudioHandler`). It holds a `WifiLock` and
  partial `WakeLock` for radio/CPU retention but **performs no `AudioManager`
  route-control**:
  no `setMode`, no `setSpeakerphoneOn`, no `startBluetoothSco`, no audio-focus
  request, no `AudioDeviceCallback`. Grep for `Bluetooth`/`Sco` across
  `libraries/humla` and `app` returns zero route-control hits (only A2DP
  buffer-size comments in `AudioOutput` remain).
- [`MumlaActivity.java`](../../app/src/main/java/se/lublin/mumla/app/MumlaActivity.java)
  only calls `setVolumeControlStream` based on `handset_mode`. No route UI exists.
- [`MumlaService.java`](../../app/src/main/java/se/lublin/mumla/service/MumlaService.java)
  (app layer) plays PTT cue sounds via `SoundPool` on `STREAM_MUSIC` /
  `STREAM_VOICE_CALL` but performs no routing.

## Preferences

- [`settings_audio.xml`](../../app/src/main/res/xml/settings_audio.xml) exposes,
  among others,
  `handset_mode`, `half_duplex`, input method, VAD threshold, bitrate,
  frames-per-packet, and the adaptive-leveler toggle. There is **no Bluetooth preference**,
  and `Settings.java` has no Bluetooth accessor.
- Natural home for the toggle: `settings_audio.xml` next to `handset_mode`,
  since SCO (like handset mode) implies the `VOICE_CALL` stream family.

## Gap summary

| # | Gap | Severity |
|---|---|---|
| G-1 | No `AudioManager` mode/routing management anywhere | Blocking |
| G-2 | No Bluetooth permissions in `app/src/main/AndroidManifest.xml` | Blocking |
| G-3 | No SCO state receiver (`ACTION_SCO_AUDIO_STATE_UPDATED`, `AudioDeviceCallback`, or comm-device listener) | Blocking |
| G-4 | Capture source never `VOICE_COMMUNICATION`; no route-change recreate | Needed for quality |
| G-5 | No `AcousticEchoCanceler` on the capture session | Quality risk, measure first (OQ-3) |
| G-6 | No route indicator or toggle UI | UX, needed for manual phase |
| G-7 | VAD/squelch thresholds tuned for phone mics, unvalidated on SCO mics | Tuning risk (OQ-4) |
