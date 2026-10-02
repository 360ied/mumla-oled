# Android Platform Requirements for SCO

What the OS demands of any app routing VoIP audio over a Bluetooth HFP link.
Sources are the Android developer guides (AudioManager self-managed call guide,
BLE Audio overview), AOSP SCO documentation, and AOSP Bluetooth internals.

## What SCO is

- **HFP SCO link**: a synchronous, circuit-like channel between phone and
  headset carrying mic uplink + earpiece downlink. Classic (narrowband) mode
  uses CVSD at **8 kHz**; wideband speech (WBS, mSBC codec) runs at **16 kHz**
  only if *both* ends negotiate it (`bt_wbs` / `g_sco_samplerate` in the BT
  stack; codec selection in `bta_ag_sco`).
- The app keeps capturing/rendering at 48 kHz; the platform resamples across
  the link. Expect telephone-grade input with no energy above 4 kHz (NB) or
  8 kHz (WB). Opus fullband encoding of that signal is fine — no encoder
  reconfiguration needed.
- Latency is poor and variable (~50–150 ms+ plus air-interface jitter).
  Budget jitter margin accordingly; the existing 40 ms floor plus Speex
  adaptation should be validated, not assumed.

## Lifecycle (legacy API, minSdk 21 path)

1. `AudioManager.setMode(MODE_IN_COMMUNICATION)` — required for the framework
   to patch streams to the SCO device.
2. `AudioManager.startBluetoothSco()` — **returns immediately; the link is not
   up**. Establishment takes 1–3 s and can fail silently.
3. Register for `AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED` and wait for
   `SCO_AUDIO_STATE_CONNECTED` before treating the route as live. The older
   `ACTION_SCO_AUDIO_STATE_CHANGED` is deprecated; the `_UPDATED` intent fixed
   missing failure delivery.
4. On failure (`SCO_AUDIO_STATE_ERROR`) or timeout: retry with backoff, then
   fall back to the previous route. Never leave the user on a dead route.
5. `stopBluetoothSco()` + restore `setMode(MODE_NORMAL)` on call end /
   disconnect. A leaked SCO link degrades all subsequent audio behavior.

Known flakiness to design around:

- `startBluetoothSco` without a prior `setMode(MODE_IN_COMMUNICATION)` may
  never connect on some OEM builds.
- Some devices deliver `DISCONNECTED` spuriously; debounce before tearing
  down the audio pipeline.
- The SCO link suspends A2DP concurrently; music-style routing and SCO
  routing are mutually exclusive by framework design.

## Modern API (API 31+/33+ path)

- From API 31, `getAvailableCommunicationDevices()` /
  `getCommunicationDevice()` expose routable endpoints (`TYPE_BLUETOOTH_SCO`,
  `TYPE_BLE_HEADSET`, …).
- From API 33, `startBluetoothSco()` / `stopBluetoothSco()` /
  `setSpeakerphoneOn()` are **deprecated** in favor of
  `setCommunicationDevice()` + `clearCommunicationDevice()` plus
  `OnCommunicationDeviceChangedListener`. Google's stated reason is BLE Audio
  (LE Audio / LC3 at 32 kHz) headset support, which the legacy SCO APIs cannot
  address.
- AOSP 17+ moves further to Audio-Managed SCO (AMSCO): the audio framework
  itself owns SCO bring-up as a consequence of streaming activity, and the BT
  stack no longer drives connection state. Behavior of the legacy APIs on
  future releases should be treated as compatibility shims.

Implementation consequence: new code needs **two routing backends** behind one
interface — `setCommunicationDevice` on API 31+, legacy `startBluetoothSco`
below — with `minSdk 21` preserved.

## Permissions and manifest

| API level | Requirement |
|---|---|
| ≤ 30 | `BLUETOOTH` + `BLUETOOTH_ADMIN` (install-time) |
| ≥ 31 | `BLUETOOTH_CONNECT` (**runtime** permission; user-grant flow needed) |
| All | `MODIFY_AUDIO_SETTINGS` (already declared) |

Additional integration notes:

- Dynamic receivers for SCO state are fine with `Context.registerReceiver`;
  on API 33+ specify `RECEIVER_NOT_EXPORTED` for the in-process state receiver.
- Querying paired HFP devices via `BluetoothAdapter` / `BluetoothHeadset`
  profile proxy also requires `BLUETOOTH_CONNECT` on API 31+.
- `FOREGROUND_SERVICE_MICROPHONE` and the mic-open Doze shield are unaffected,
  but note SCO keeps the BT radio active: measurable battery cost to mention
  in the setting summary.

## Capture-source consideration

- `VOICE_COMMUNICATION` is the source pre-tuned (by OEM HALs) for
  echo-managed, SCO-routed voice; `MIC`/`DEFAULT` bypass some of that tuning.
  Whether to switch the `AudioRecord` source when SCO is active (OQ-2) should
  be decided by on-device A/B measurement, not by documentation: HAL behavior
  varies widely.
- If AEC is needed (OQ-3), `AcousticEchoCanceler` attaches to the same session
  id pattern already used for NS/AGC in `AudioInput.enableAudioEffects()`.
