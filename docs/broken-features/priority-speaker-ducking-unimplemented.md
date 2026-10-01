# Broken Feature: Priority Speaker Audio Ducking Not Implemented

**Status:** confirmed unimplemented feature  
**Severity:** low-medium (feature parity with upstream Mumble)  
**Component:** `libraries/humla` Audio Engine / Mixer  
**Files Affected:**
- [`AudioOutputEngine.cpp`](../../libraries/humla/src/main/jni/audio_engine/AudioOutputEngine.cpp) (`renderMix`, ~line 467) / [`AudioOutputEngine.h`](../../libraries/humla/src/main/jni/audio_engine/AudioOutputEngine.h) — native mixer, no priority term
- [`User.java`](../../libraries/humla/src/main/java/se/lublin/humla/model/User.java) (`isPrioritySpeaker`) / [`ModelHandler.java`](../../libraries/humla/src/main/java/se/lublin/humla/protocol/ModelHandler.java) (tracks `UserState.priority_speaker`) / [`HumlaService.java`](../../libraries/humla/src/main/java/se/lublin/humla/HumlaService.java) (`setPrioritySpeaker`) / [`UserMenu.java`](../../app/src/main/java/se/lublin/mumla/channel/UserMenu.java) (admin toggle)
- Upstream reference: `../mumble/src/mumble/AudioOutput.cpp:477,486-501,665-669`

---

## 1. Problem Description

The Mumble protocol defines a **Priority Speaker** role (`Mumble.UserState.priority_speaker`). When a priority speaker transmits, other users talking simultaneously in the channel should have their audio volume automatically ducked (attenuated) so the priority speaker remains clearly intelligible.

In Mumla:
1. Priority speaker permissions and state are tracked on `User` objects.
2. The user context menu allows admins to toggle priority speaker status.
3. However, the native mixer completely ignores priority speaker status: `AudioOutputEngine::renderMix()` sums per-voice scratch buffers with equal gain (`m_mix[i] += m_voiceScratch[i]`); grep for `priority/duck/attenuat` in `audio_engine/` finds nothing. (The `AudioOutput.fetchAudio()` / `AudioMixerShort` path cited in earlier revisions no longer exists — mixing moved to native.) The priority speaker setting therefore has no effect on audio output.

---

## 2. Technical Root Cause

`renderMix()` never consults `user.isPrioritySpeaker()`. The engine knows sessions and talk flags but has no priority-session set bridged over JNI, so it cannot distinguish priority voices at mix time. State plumbing around the engine is complete (`ModelHandler` tracks `priority_speaker`, `UserMenu` toggles it, `HumlaService.setPrioritySpeaker()` sends it); only the audio path is missing.

---

## 3. Remediation Plan

1. Bridge priority state into the engine (e.g. `setPrioritySpeaker(session, bool)` / priority-session set over JNI, updated on `UserState` changes) so `renderMix()` can identify priority voices.
2. In `renderMix()`, follow upstream's two-pass mix (`AudioOutput.cpp:486-501,665-669`): first detect any active non-muted priority speaker, then multiply every non-priority, non-whisper voice by `adjustFactor = 10^(-18/20) ≈ 0.126` (-18 dB). Upstream exempts the whispering listener themselves (`tsState != Whispering`) and honors a `prioritySpeakerActiveOverride` — decide whether to mirror both.
3. Keep the constant with upstream (-18 dB, not the -12 dB previously suggested here) unless a user-facing volume setting is added.

---

## 4. Re-verification (2026-10-01)

Functionally still valid: `isPrioritySpeaker()` is set and toggleable but never read on any audio path. This report previously pointed at the deleted Java `fetchAudio()`; the target is now `AudioOutputEngine::renderMix()`. Talk-state/whisper work (whisper-target report) touches the same `renderMix`/flags path — implement together to avoid two native-interface revisions.
