# Bluetooth LE Audio Implementation Plan

> **Status:** proposed.
> **Goal:** recognize Bluetooth LE Audio headsets in the existing voice-route
> manager so the "Two-way Bluetooth" toggle drives LE transports as well as
> classic SCO. Small code delta; verification-gated on real hardware.

## Background

Mumla OLED 0.22.0 shipped classic Bluetooth (HFP/SCO) as a manual opt-in
toggle ("Two-way Bluetooth"), owned by `BluetoothScoManager` and described in
this dossier's [README](README.md). LE Audio is a separate transport —
bidirectional LC3 instead of 8/16 kHz CVSD/mSBC — exposed by Android as a
separate profile with separate device types. The current manager filters for
`TYPE_BLUETOOTH_SCO` only, so an LE headset is invisible to it: `findScoDevice`
returns null (`REASON_NO_DEVICE`) and the user stays on phone mic/speaker or
output-only A2DP. For earbud users this is the worst-sounding configuration
the app can produce, which makes LE recognition a quality fix rather than a
novel feature. This plan is the follow-up the [toggle unification
plan](toggle-unification-plan.md) explicitly defers ("no LE Audio work").

## Platform requirements

Sources: the [AudioManager self-managed call
guide](https://developer.android.com/develop/connectivity/bluetooth/ble-audio/audio-manager),
the [BLE Audio
overview](https://developer.android.com/develop/connectivity/bluetooth/ble-audio/overview),
and [audio routing API updates in Android
14](https://developer.android.com/develop/connectivity/telecom/voip-app/api-updates).
The [platform notes](android-platform.md) already record the SCO half; this
is the LE half.

- LE Audio exists only on **API 33+**: `BluetoothProfile.LE_AUDIO` (value
  22), `AudioDeviceInfo.TYPE_BLE_HEADSET` / `TYPE_BLE_SPEAKER` /
  `TYPE_BLE_BROADCAST`, and the `BluetoothLeAudio` proxy class.
- Google's mandate: starting in Android 13, apps must migrate from
  `startBluetoothSco()` to `setCommunicationDevice()` to support BLE
  headsets; the new API is backward-compatible with HFP. Our API 31+ backend
  already uses `setCommunicationDevice()`, so no architectural migration is
  needed — only device recognition.
- The documented LE flow is exactly our state-machine shape: list
  `getAvailableCommunicationDevices()`, filter for `TYPE_BLE_HEADSET`,
  `setCommunicationDevice()`, await confirmation via
  `getCommunicationDevice()` / `OnCommunicationDeviceChangedListener` with a
  timeout, `clearCommunicationDevice()` at session end.
- Android 14 enables LEA by default on Pixel/AOSP and deprecates
  `isBluetoothScoOn()` / `start-stopBluetoothSco()` in favor of the
  communication-device APIs — confirming the modern path is the foundation.
- The legacy `startBluetoothSco()` backend (runs below API 31) can **never**
  serve BLE, and BLE-capable devices require API 33+, so the legacy path is
  provably untouched by this work.
- No manifest or permission changes: `BLUETOOTH_CONNECT` is already declared
  without `maxSdkVersion`, and it is the only runtime grant the BLE path
  needs. The existing `MumlaActivity` / `MumlaService` grant flow covers it.
- Compile-time safety: `compileSdk = 36` provides the constants, and
  `static final int` constants are inlined by javac, so referencing
  `TYPE_BLE_HEADSET` cannot throw `NoSuchFieldError` on old devices. New
  *calls* (`getProfileConnectionState(LE_AUDIO)`) still get `SDK_INT >=
  Build.VERSION_CODES.TIRAMISU` guards for lint and clarity.

## Gap analysis

All recognition logic lives in one file (paths relative to the repo root):

[`BluetoothScoManager.java`](../../libraries/humla/src/main/java/se/lublin/humla/audio/BluetoothScoManager.java)

| # | Site | Today | Required change |
|---|---|---|---|
| 1 | `findScoDevice()` | Accepts only `TYPE_BLUETOOTH_SCO` | Also accept `TYPE_BLE_HEADSET` on API 33+; **prefer BLE over SCO** when a dual-mode headset exposes both |
| 2 | `isScoActiveNow()` | Checks only `TYPE_BLUETOOTH_SCO` | Same widening, or the already-routed fast path misfires on BLE |
| 3 | `onModernDeviceChanged()` | `scoNow` is SCO-only | Widen atomically with (1): otherwise selecting a BLE headset fires `dropActiveLink()` on the callback that should confirm the connection |
| 4 | `isHeadsetConnected()` | Checks only the `HEADSET` profile | **No change.** Legacy-path pre-flight, unreachable on API 33+; add a comment saying why so nobody "fixes" it later |

Rows 1–3 must land in a single commit: any subset that widens selection
without widening the listener (or vice versa) introduces a connect-then-drop
or never-connect regression on one transport.

Deliberately **excluded** from the predicate: `TYPE_BLE_SPEAKER`
(output-only, A2DP-class) and `TYPE_BLE_BROADCAST` (Auracast sink,
receive-only) — neither belongs on a two-way communication device — and
`TYPE_HEARING_AID`, which became explicitly selectable in Android 14 but has
unknown mic characteristics for a talk app. Hearing aids are future work,
not opportunistic scope.

## Changes

### 1. `BluetoothScoManager.java` — predicate plus three call sites

- Add a package-visible pure static predicate, e.g.
  `isVoiceRouteDevice(int type)`, returning true for `TYPE_BLUETOOTH_SCO`
  and (guarded) `TYPE_BLE_HEADSET`. Pure like `shouldRetryBringUp`, so the
  existing JVM truth-table test style applies.
- `findScoDevice()`: iterate the communication-device list collecting both
  accepted types, return BLE first. Single-device fast path unchanged.
- `isScoActiveNow()` and `onModernDeviceChanged()`: replace the inline
  `type == TYPE_BLUETOOTH_SCO` comparisons with the predicate.
- `isHeadsetConnected()`: comment-only touch recording that LE Audio needs
  no legacy pre-flight (BLE implies API 33+, legacy runs below 31).
- Naming: keep `BluetoothScoManager`, `EXTRAS_BLUETOOTH_SCO`,
  `isBluetoothScoActive()`, and `PREF_BLUETOOTH_HEADSET` stable — the pref
  key is persisted (renaming silently resets the user's toggle) and the
  rest are service/UI API surface. The SCO-named manager owning an LE route
  is accepted naming debt, recorded here; a rename is a separate follow-up.

### 2. Tests — JVM predicate coverage, no harness changes

- New truth-table test beside
  [`BluetoothScoRetryPolicyTest.java`](../../libraries/humla/src/test/java/se/lublin/humla/audio/BluetoothScoRetryPolicyTest.java):
  SCO accepted, BLE headset accepted on 33+ guard semantics, A2DP /
  speaker / broadcast / hearing-aid / unknown rejected, BLE-before-SCO
  ordering for dual-mode lists. The predicate is pure Java and needs no
  Android runtime; `AudioManager`-touching paths stay on the manual matrix
  below.
- Must keep passing untouched: `BluetoothScoRetryPolicyTest`,
  `SettingsBluetoothHeadsetTest`.

### 3. Docs — this plan plus index entry

- This file; `README.md` contents list gains item 6. No other dossier edits:
  OQ-2–OQ-4 stay open measurement questions, and the new LE-specific open
  questions live below.

### 4. Explicit non-changes

- Manifest, permission flows, both toggle UIs, `ServerConnectTask` extras.
- `AudioHandler` stream selection (`STREAM_VOICE_CALL` /
  `USAGE_VOICE_COMMUNICATION` on confirmed route), `MumlaService`
  volume/cues/proximity gating — all transport-agnostic and already follow
  confirmed state.
- Capture source (`MIC`/`DEFAULT`) and AEC/VAD tuning — OQ-2–OQ-4 cover
  SCO; OQ-6 below re-opens them for LE measured on device, not decided here.
- No user-visible device picker when several BT devices exist (Google
  suggests one; single toggle plus prefer-BLE is the v1; picker is future
  work). No auto-routing on connect (Option B in
  [design-options.md](design-options.md)).

## Open questions

- **OQ-5**: Is the 8 s + 1 retry bring-up budget enough for LE? Google's BLE
  sample waits up to 30 s for the device to become active (LE connection
  plus ASE configuration can outlast SCO bring-up). Keep 8 s initially;
  make the budget device-type-aware only if `REASON_TIMEOUT` rates on BLE
  hardware say so.
- **OQ-6**: Do OQ-2–OQ-4 answers (`VOICE_COMMUNICATION` source, AEC need,
  VAD retuning) differ under an LC3 wideband mic versus a band-limited SCO
  mic? Re-measure on LE hardware; do not carry SCO answers over by default.

## Edge cases

| Case | Expected handling |
|---|---|
| Dual-mode headset exposes SCO + BLE | Prefer BLE (LC3 bidirectional is the quality win); SCO remains the fallback entry |
| LE headset connected, toggle off | No route change; manual-toggle semantics preserved |
| OS moves the route mid-call (e.g. user picks speaker in system UI) | Widened `onModernDeviceChanged` drops a genuinely lost route, ignores transient flaps — same policy as SCO today |
| Pre-33 device | Byte-identical behavior; new branches are version-guarded |
| No BLE device present | `REASON_NO_DEVICE`, same fallback toast as SCO |
| `BLUETOOTH_CONNECT` revoked mid-bring-up | `REASON_PERMISSION` path unchanged |
| Hearing-aid-only or Auracast-only environment | Not selected; behavior identical to today (phone routes) |
| LE bring-up slower than the timeout | Retry then fallback toast; data for OQ-5, not a silent dead route |

## Test plan

- Unit: predicate truth table and BLE-first ordering (new test, §2); full
  `./scripts/check.sh` gate in the worktree.
- Manual matrix (requires an Android 13+ phone **and** an LE Audio headset —
  neither emulators nor CI cover this):
  1. Toggle on mid-call: `adb shell dumpsys audio` shows the
     `TYPE_BLE_HEADSET` device as the communication device; peer hears
     wideband; logcat `BluetoothScoManager` shows connected, no timeout.
  2. Toggle off mid-call: route and pipeline restore to prior audio.
  3. Bluetooth off / headset out of range with toggle on: fallback toast,
     phone audio, no dead route; re-entry retries per the existing budget.
  4. Deny `BLUETOOTH_CONNECT`: existing revert-plus-toast path from either
     toggle.
  5. Dual-mode headset: dumpsys confirms the BLE endpoint won over SCO.
  6. Repeat (1) on OS 33 vs 34+ and note the version (hearing-aid
     visibility and LEA default-on changed at 14).
- Useful commands: `adb shell dumpsys audio | grep -i -A2 communication`,
  `adb shell dumpsys bluetooth_manager | grep -i -A5 le_audio`,
  `adb logcat -s BluetoothScoManager`.

## Risks and trade-offs

- **Shipping blind is the top risk.** Without the hardware in the matrix
  above, this changes the voice path on evidence-free routing. Treat
  missing hardware as a merge-blocker, not a review nicety.
- **Timeout miscalibration (OQ-5).** Too short surfaces as fallback toasts
  on healthy LE links; the failure reason code distinguishes this from
  real breakage, so it is diagnosable post-ship.
- **OS-version fragmentation.** LE routing behavior moved between 13 and
  14 (default-on, hearing-aid visibility); a single-device test proves one
  version only.
- **Naming debt.** A `Sco`-named manager selecting BLE endpoints will
  confuse the next reader; accepted and documented in §1 rather than fixed
  by a cross-tree rename in the same commit.

## Rollout

1. Implement in a dedicated worktree (`./scripts/worktree.py add
   <branch>`) touching `BluetoothScoManager.java` plus the new JVM test;
   comment-only where noted. No manifest, UI, pipeline, or permission-flow
   changes expected.
2. Verify with `./scripts/check.sh` in the worktree plus the manual matrix
   above, and report the OS version and headset model tested.
3. Leave merge, push, and worktree cleanup to the user on review.
