# Broken Feature: CELT 0.11.0 Codec Disabled Due to "Robot Voices"

**Status:** confirmed disabled codec / bug in native decoding  
**Severity:** medium (codec incompatibility with older CELT 11 servers)  
**Component:** `libraries/humla` Audio Engine / Codecs  
**Files Affected:**
- [`HumlaService.java`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/java/se/lublin/humla/HumlaService.java)
- [`AudioOutputSpeech.java`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/java/se/lublin/humla/audio/AudioOutputSpeech.java)
- [`libraries/humla/src/main/jni/celt-0.11.0-src/`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/jni/celt-0.11.0-src/)

---

## 1. Problem Description

Mumble historically used two distinct versions of the experimental CELT codec before Opus was adopted:
- CELT 0.7.0 (`CELTAlpha`, bitstream `0x8000000b`)
- CELT 0.11.0 (`CELTBeta`, bitstream `0x80000010`)

The Mumla repository includes the full native source submodule for CELT 0.11.0 under `libraries/humla/src/main/jni/celt-0.11.0-src`, builds it as part of `libjnicelt11.so`, and contains decoding plumbing in `CELT11.java` and `AudioOutputSpeech.java`.

However, in `HumlaService.java` (lines 360–371), CELT 0.11.0 is explicitly disabled during server authentication:

```java
final Mumble.Authenticate.Builder auth = Mumble.Authenticate.newBuilder();
auth.setUsername(mServer.getUsername());
auth.setPassword(mServer.getPassword());
auth.addCeltVersions(CELT7.getBitstreamVersion());
// FIXME: resolve issues with CELT 11 robot voices.
// auth.addCeltVersions(Constants.CELT_11_VERSION);
auth.setOpus(mUseOpus);
```

Because `auth.addCeltVersions(Constants.CELT_11_VERSION)` is commented out, Mumble servers will never negotiate CELT 0.11.0 with Mumla.

---

## 2. Technical Root Cause

During earlier development, decoding CELT 0.11.0 streams on Android produced severe audio degradation described in the codebase as "robot voices". Rather than resolving the sample rate, frame sizing (e.g. 480 vs 960 samples), or JNI buffer alignment issues in `AudioOutputSpeech`, the codec was commented out from client authentication.

---

## 3. Remediation Plan

1. **Investigate Frame Size & Buffer Alignment in `AudioOutputSpeech`:**
   Inspect how `AudioOutputSpeech.java` handles `UDPVoiceCELTBeta`. Ensure that the frame size passed to `CELT11.celt_decode_float()` matches the server's negotiated frame size and sample rate (48kHz).
2. **Test With Reference C++ Implementation:**
   Compare the decoding pipeline with upstream `../mumble/src/mumble/AudioOutputSpeech.cpp` to verify exact bitstream version negotiation and packet loss concealment behavior.
3. **Re-enable CELT 11 Authentication:**
   Uncomment `auth.addCeltVersions(Constants.CELT_11_VERSION)` once audio rendering is validated without distortion.
