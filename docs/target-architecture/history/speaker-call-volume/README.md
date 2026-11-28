# Speaker Voice on the Media Volume Retrospective

Snapshot: 2026-11-27, branch `handset-voice-usage` at `4735e523` (fix
commit), after the fix was confirmed on device.

On the built-in speaker, Mumla OLED switched the system into call mode
(`MODE_IN_COMMUNICATION`), but inbound voice followed the **media**
volume rather than the call volume. The volume keys and the system
volume panel adjusted call volume, so they did nothing to voice
playback. In the background or on the lock screen, where the activity's
volume-key stream does not apply, there was no key-driven way to change
the voice level at all. The bug reproduced on any session with Handset
mode off (the default) and no headset or Bluetooth device attached.

## Timeline

- **2026-10-11**: `1107b197` and `f686afc5` landed the
  [echo-cancellation](../../../echo-cancellation/README.md) design: on
  the phone speaker and earpiece, the capture source, mode, and
  playback usage are all voice. `AudioHandler` hardcoded playback to
  `STREAM_VOICE_CALL` on every voice route, and the Handset preference's
  stream value was only used to decide speakerphone
  (`mAudioStream != STREAM_VOICE_CALL`). `0a49bc4e` (media sinks) and
  the Kotlin conversion `ca55913c` kept this.
- **2026-11-18**: `86f656b1` ("voice: cut over to native send path")
  deleted `AudioHandler` and moved stream selection into `VoiceEngine`.
  `VOICE_HANDSET` became `VOICE_COMMUNICATION to handset`, and the
  pair's stream went straight to `output.startPlaying(audioStream)`. The
  Handset value now chose the playback stream itself, so the default
  played on `STREAM_MUSIC`. First shipped in `v0.27.11`; `v0.27.10` was
  the last good release.
- **2026-11-23**: `d609fe69` wrote the
  [route.md](../../route.md#stream-parameters) stream-parameters table
  from that code ("Rows match today's `VoiceEngine` pipeline rebuild"),
  which made the regression part of the spec. `095f709d` ported it to
  AAudio as `AAUDIO_USAGE_MEDIA` and added a pipeline test,
  "handset stream preference changes the output usage", that pinned it.
- **2026-11-27**: Reported and reproduced at `master` (`6cafc54c`).
  Fixed in `4735e523` and confirmed on device.

## Root cause

Android picks a stream's volume from its usage (its legacy stream type),
not from the audio mode. `AAUDIO_USAGE_MEDIA` maps to `STREAM_MUSIC` and
follows the media volume even while `MODE_IN_COMMUNICATION` is held.
On `VOICE_HANDSET` the pieces disagreed:

| Piece | Stream on `VOICE_HANDSET`, Handset off |
|---|---|
| Mode (`RouteControl.ensureVoiceMode`) | `MODE_IN_COMMUNICATION` |
| Volume keys (`MumlaActivity.applyVolumeControlStream`) | `STREAM_VOICE_CALL` |
| Cues (`AndroidFeaturePlatform`'s `SoundPool`) | `USAGE_VOICE_COMMUNICATION` |
| Voice playback (`VoiceEngine.tryEnsurePipeline`) | `USAGE_MEDIA` |

The volume keys and cues followed the route alone. Voice playback also
read the Handset preference, and that preference had been carried
through the migration as a stream type (`handsetStream: Int`) even though
its only job since `f686afc5` was to choose earpiece or speaker.

The capture side compounded it: the echo-cancellation design notes that
the voice stack is all-or-nothing, so source, mode, and usage must
agree. Media-usage playback under a voice-communication capture session
is the combination it warns weakens platform echo cancellation, on the
loudspeaker, where echo is worst.

## The fix

`4735e523` restores the echo-cancellation design:

- `VOICE_HANDSET` always opens playback with
  `USAGE_VOICE_COMMUNICATION`, like `VOICE_SCO`. `MEDIA` is unchanged.
- `RoutePreferences.handsetStream: Int` became `earpiece: Boolean`.
  It only sets speakerphone: on for `VOICE_HANDSET` unless the earpiece
  is chosen, off on the other routes.
- `BuiltPipeline` records the speakerphone state, and a change rebuilds
  the streams. Without this, toggling Handset would no longer rebuild
  once the usage stopped changing, and speakerphone cannot safely change
  under open streams.
- The `route.md` stream-parameters table and the `StreamProfile` notes
  now describe voice playback on every voice route.
- The pinning test became "earpiece preference moves the output but
  keeps the voice usage", plus a test that the preference leaves SCO
  playback alone.

What users see: speaker-mode voice follows the call volume, a separate
level from media that usually cannot reach zero, and the volume keys
change voice playback whether the app is in front or not.

## Why it slipped through

- **A misleading name crossed a deletion.** The step-4 `VoiceEngine`
  passed the preference as `builder.setAudioStream(handset)`, and its
  comment said the preference "selects the stream". But the handler
  never played on that value for voice routes; it only derived
  speakerphone from it. When `86f656b1` deleted the handler, it took
  the name literally, and the hardcoded voice stream that had been the
  real behavior went with the deleted file.
- **The spec was written from the code, not the design.** `route.md`
  said "the handset stream and speakerphone routing travel with the
  profile as they do today", which did not say which stream played.
  `d609fe69` then filled the gap from the already-regressed
  `VoiceEngine`, and the echo-cancellation README, which had the right
  answer, was not consulted.
- **A test pinned the bug.** The 5b pipeline test asserted the media
  usage as intended behavior, so the regression was protected rather
  than caught.
- **Nothing checks the pieces agree.** Mode, volume-key stream, cue
  usage, and playback usage are set in four files in two modules, with
  no test that compares them. Each piece was individually consistent
  with its own reading of the spec.
- **The symptom is quiet.** Audio still played, from the right speaker;
  only the volume control felt wrong. It surfaced in daily use, not in
  unit tests, interop, or release checks.

## Takeaways for future refactors

- When deleting a class, list what it hardcoded, not only what it
  parameterized. Constants that override a passed-in value are behavior
  too, and a parameter's name may describe what callers believed rather
  than what it did.
- A spec row derived from "today's code" during a migration needs a
  check against the design record it descends from. Here that was
  [docs/echo-cancellation](../../../echo-cancellation/README.md).
- A setting should be carried as what it means (`earpiece: Boolean`),
  not as a platform value it was once translated to
  (`STREAM_VOICE_CALL`). The translated form invites new uses of the
  platform value.
- Where several components must agree on one platform decision (the
  stream for a route), a test should compare them, or one function
  should decide for all of them.
