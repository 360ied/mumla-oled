# Prior Art: Jumble / Plumble SCO Support

The ancestor codebase (Jumble, by Andrew Comminos — also the upstream of this
repository's `humla` library) shipped Bluetooth SCO support that was later
dropped in the forks. Its shape is the best available template for
reintroduction. References below are to the Jumble master branch on GitHub
(`com.morlunk.jumble`), not to local paths.

## What existed

- `audio/BluetoothScoReceiver.java` — a `BroadcastReceiver` holding an
  `AudioManager`, registered in `JumbleService.onCreate` against
  `AudioManager.ACTION_SCO_AUDIO_STATE_CHANGED`. It tracked `mBluetoothScoOn`,
  forwarded connected/disconnected events to a `Listener`, and exposed
  `startBluetoothSco()` / `stopBluetoothSco()` / `isBluetoothScoOn()`.
- `JumbleService` implemented `BluetoothScoReceiver.Listener`:
  - `onBluetoothScoConnected()` → `mAudioBuilder.setBluetoothEnabled(true)` →
    recreate the `AudioHandler`.
  - `onBluetoothScoDisconnected()` → `setBluetoothEnabled(false)` → recreate.
  - `onConnectionDisconnected()` → `mBluetoothReceiver.stopBluetoothSco()` —
    no leaked link on call end.
  - Public session API: `usingBluetoothSco()`, `enableBluetoothSco()`,
    `disableBluetoothSco()` (exposed over `IJumbleService`/`IJumbleSession`,
    so UI layers could bind a toggle).
- `AudioHandler.Builder.setBluetoothEnabled(boolean)` fed a `mBluetoothOn`
  flag into construction; `initialize()` then started playback on
  `AudioManager.STREAM_VOICE_CALL` when Bluetooth was on, else the configured
  `mAudioStream`.

In short: **receiver owns link state → builder flag selects the voice-call
stream → handler recreate applies the route**. The current codebase recreates
`AudioHandler` on settings change already (`configureExtras` →
`createAudioHandler`), so this pattern slots in cleanly.

## Gaps in the old implementation (do not copy verbatim)

| Weakness | Why it matters now |
|---|---|
| Listened to deprecated `ACTION_SCO_AUDIO_STATE_CHANGED` | Misses failure delivery fixed by `ACTION_SCO_AUDIO_STATE_UPDATED`; use the latter |
| No connect timeout / retry | Silent `startBluetoothSco` failures strand the user; add bounded retry then fallback |
| No `setMode(MODE_IN_COMMUNICATION)` management visible in the service | Required pre-condition for reliable SCO bring-up on many OEM builds |
| No `BLUETOOTH_CONNECT` handling (predates API 31) | Runtime permission flow is now mandatory |
| No `setCommunicationDevice` backend (predates API 31/33) | Needed for BLE Audio headsets and deprecation compliance |
| Recreated the whole handler synchronously in the broadcast callback | Recreate is heavyweight (native engine + threads); move to the service handler path with debounce |
| `onReceive` called `stopBluetoothSco` on error/disconnect unconditionally | Can fight an in-progress reconnect; separate local-state reset from link teardown |

## What the forks dropped

Neither the current `HumlaService` nor the app layer retains any of the above:
no receiver, no builder flag, no session API, no manifest Bluetooth
permissions. The `handset_mode` → `STREAM_VOICE_CALL` mapping in
`ServerConnectTask` is the only surviving voice-call-route logic, and it is
static per connection rather than route-reactive.

## Reuse plan

Keep the three-part shape (receiver → service lifecycle → builder flag), but:

1. Put the timeout/retry/fallback state machine in the service, not the receiver.
2. Gate stream selection on *confirmed* SCO state, and recreate capture +
   playback together so source and stream stay consistent.
3. Add the API 31+ backend behind the same interface from day one.
