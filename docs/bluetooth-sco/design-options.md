# Design Options and Recommendation

Three scoped options, ordered by increasing complexity. All assume the
two-backend routing interface (legacy SCO below 31 — usable through 32,
deprecated at 33 — and `setCommunicationDevice` ≥ 31) and the
`BLUETOOTH_CONNECT` runtime permission flow.

## Gap traceability

| Gap | Closed by |
|---|---|
| G-1 (route-control), G-2 (permissions), G-3 (state receiver) | Option A |
| G-6 (route UI) | Option A (minimal indicator); extended by B |
| G-4 (stream/source selection) | Option A (voice-call stream flag); source choice via OQ-2 measurement |
| G-5 (AEC), G-7 (VAD tuning) | Measurement follow-ups after A (OQ-3, OQ-4); not option-gated |
| Auto-routing beyond the manual toggle | Option B |

## Option A — Manual toggle (recommended first)

> **Shipped in 0.22.0** as "Two-way Bluetooth" (`bluetooth_headset`).
> As-built deltas vs this sketch: the SCO lifecycle lives in a dedicated
> `BluetoothScoManager` (epoch-guarded bring-up, reason-coded failures, owned
> mode restore) rather than inline service code; the voice-call stream is
> selected only on *confirmed* link state, never the raw toggle; volume keys,
> PTT cues, proximity, and the overflow checkmark all follow confirmed state
> via a new `onBluetoothScoChanged` observer event; the toggle additionally
> lives as a checkable channel-overflow item with a deferred-grant flow.

- New `bluetooth_headset` checkbox in `settings_audio.xml` beside
  `handset_mode`; plumbed through `Settings` → `ServerConnectTask` extras →
  `HumlaService`.
- `HumlaService` owns the SCO lifecycle: on connect with the toggle on,
  `setMode(MODE_IN_COMMUNICATION)` + `startBluetoothSco()` (or
  `setCommunicationDevice` on API 31+); wait for connected state with timeout
  and bounded retry; fall back to the previous route on failure.
- Confirmed SCO state sets the Jumble-style builder flag so `AudioOutput`
  uses the voice-call stream; capture source stays `MIC`/`DEFAULT` initially,
  with `VOICE_COMMUNICATION` as a measured follow-up (OQ-2).
- Teardown on disconnect: `stopBluetoothSco()` / `clearCommunicationDevice()`
  + `setMode(MODE_NORMAL)`.
- Minimal route UI: reflect active route in the connection notification or
  channel screen; log route transitions via `HumlaLogger`.

Effort: one worktree, no protocol or DSP changes. Testable on any HFP headset.

## Option B — Auto-route on HFP connect (follow-up)

Everything in A, plus:

- `BluetoothHeadset` profile proxy or `AudioDeviceCallback` detects HFP
  connection mid-call and auto-starts SCO; device removal tears down and
  falls back.
- User-facing route picker or sticky "prefer Bluetooth" behavior; needs
  product decisions about surprising the user mid-conversation.

Effort: a second worktree-sized state machine with genuinely tricky edge cases
(flapping links, competing A2DP routes, user override). Do after A proves the
lifecycle solid.

## Option C — Telecom / system-call integration (not recommended)

Routing via `ConnectionService`/`Connection` self-managed-call APIs. Full
system-call UX (in-call UI, BT button handling) at the cost of a large
permissions and lifecycle surface, plus OEM variance. Disproportionate for a
push-to-talk voice-chat client; revisit only if `KEYCODE_PTT`/headset-hook
work (PTT-12 in [push-to-talk](../push-to-talk/README.md)) demands it.

## Risks

| Risk | Mitigation |
|---|---|
| Silent SCO setup failure strands user on dead route | Timeout + retry + automatic fallback; surfaced toast/log |
| Leaked SCO link poisons later audio | Single lifecycle owner (`HumlaService.onConnectionDisconnected` path, mirroring Jumble) with tests on the teardown order |
| OEM HAL variance in source/AEC behavior | Measure OQ-2/OQ-3 per device; keep source selection a flag, not a constant |
| VAD misbehaving on band-limited mic input | Validate detection threshold defaults on SCO; retune if needed (OQ-4) |
| Battery cost of held SCO link | Tear down aggressively when idle/disconnected; note in setting summary |
| AOSP AMSCO drift (Android 17+) | Abstract routing behind an interface so the backend can follow the framework |

## Test matrix (for the implementation phase)

- Headsets: one WBS-capable, one narrowband-only, one cheap no-name (link
  behavior differs most at the low end).
- Transitions: toggle on/off mid-call, headset power off mid-call, incoming
  phone call preemption, disconnect with SCO active.
- Audio: first-syllable clipping rate, VAD false-talk rate on SCO mic,
  half-duplex + SCO combined, PTT cue audibility on the SCO earpiece.
- Permissions: grant/deny `BLUETOOTH_CONNECT` on API 31+; upgrade path with
  toggle previously on.
- Regression: `./scripts/check.sh` in the worktree; confirm A2DP media and
  handset paths unchanged with the toggle off.
