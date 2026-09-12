# Legacy Feature: CELT and Speex Codecs Dropped

**Status:** Closed (Deprecated / Dropped)  
**Severity:** Low (Obsolescence / Technical Debt)  
**Component:** `libraries/humla` Audio Engine / Codecs  
**Files Affected:**
- [`HumlaService.java`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/java/se/lublin/humla/HumlaService.java)
- [`AudioOutputSpeech.java`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/java/se/lublin/humla/audio/AudioOutputSpeech.java)
- [`libraries/humla/src/main/jni/Android.mk`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/jni/Android.mk)
- [`.gitmodules`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/.gitmodules)

---

## 1. Problem Description

Mumble historically used two distinct versions of the experimental CELT codec before Opus was standardized in 2012 (RFC 6716) and adopted in Mumble 1.2.4 (2013):
- CELT 0.7.0 (`CELTAlpha`, bitstream `0x8000000b`)
- CELT 0.11.0 (`CELTBeta`, bitstream `0x80000010`)
- Speex (`UDPVoiceSpeex`, narrowband / wideband legacy codec)

In earlier Mumla builds, CELT 0.11.0 decoding produced severe robotic audio distortion ("robot voices") and was commented out from client authentication. Furthermore, Mumla's native microphone engine only implements Opus encoding, meaning transmission on legacy CELT servers was non-functional.

Upstream Mumble dropped all legacy codecs (CELT and Speex) entirely in Mumble 1.5.0 (commit `4d05018c2`, PR #4538) because Opus has been universally supported across all Mumble servers and clients for over a decade.

---

## 2. Architectural Resolution

Rather than expending maintenance effort on obsolete pre-2013 codecs:
1. **Dropped CELT & Speex Codecs:** Completely removed `celt-0.7.0-src`, `celt-0.11.0-src`, and `speex` git submodules, along with `libjnicelt7.so`, `libjnicelt11.so`, and `libjnispeex.so`.
2. **In-Tree Adaptive Jitter Buffer:** Replaced the JavaCPP `Speex.JitterBuffer` with an in-tree native implementation compiled directly into `libhumlaaudio.so` (`audio_engine/jitter/`), decoupling jitter buffering from the Speex codebase while eliminating per-frame allocations in Java.
3. **Upstream Protocol Parity:** Authentication sends no CELT versions (`auth.addCeltVersions`), and incoming voice traffic is strictly Opus, matching upstream desktop Mumble 1.5+ behavior.
4. **Binary Footprint Reduction:** Stripped ~2.1 MB of dead native libraries from universal APK builds across all 4 target ABIs.

