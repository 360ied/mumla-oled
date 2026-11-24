# Step 4 — Voice control and route

Part of the [migration](../migration.md). **Status: done.**
This is the step's plan as it was executed, kept as a record;
[`PROGRESS.md`](../PROGRESS.md) summarises what landed, and the living
specs hold the current design.

1. `RouteControl` (`route/RouteControl.kt`): the controller moves to the
   route `HandlerThread`, publishes `RouteState` and events, and gains its
   private route-session counter in the same commit that removes `Host`,
   so `shouldDropStaleEvent` keeps working throughout (see
   [route](../route.md#thread)). `ScoLink` callbacks use the route executor.
2. `VoiceControl` (`voice/VoiceControl.kt`), an interim class that owns
   the voice control thread and today's `AudioHandler` lifecycle (create,
   destroy, recreate), following `RouteState` (see
   [route](../route.md#migration-notes)). Step 5a replaces it with the
   `VoiceEngine`.
3. The `VoiceSettings` mapper and the policy collector, which publishes
   `StateFlow<VoicePolicy>`. Interim consumer: the Kotlin capture thread
   reads it each frame and calls the engine's `setMuted`/`setPttTalking`
   (and bitrate and frames per packet) on change (see
   [voice](../voice.md#interim-mute-step-4-before-5a)).
4. Delete `mMuteLock` and the documented `AudioHandler` monitor order.

**Done when:** no `Thread.join` or other blocking wait is reachable from
the main thread (by review, and `checkNotMainThread()` guards every
blocking method in `VoiceControl` and `RouteControl`); `AudioHandler`,
`AudioRouteController`, and `BluetoothScoManager` are off the allowlist.

## Kill criterion

**Step 4's route sequence** misbehaves with the Bluetooth headset in
daily use and cannot be fixed within the readiness model. Rethink the
sequence before step 5.
