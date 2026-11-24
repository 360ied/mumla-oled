# Step 3 — Client and session

Part of the [migration](../migration.md). **Status: done.**
This is the step's plan as it was executed, kept as a record;
[`PROGRESS.md`](../PROGRESS.md) summarises what landed, and the living
specs hold the current design.

1. `Transport` extracted from `HumlaConnection` and `HumlaTCP`: framing,
   reader thread, writer coroutine, TLS, keepalive, UDP health. Until step
   5a it talks to voice through an interim `LegacyVoiceLink`, a
   `VoiceLink` implementation over today's Kotlin `HumlaUDP` and
   `CryptState` (keys, IVs, UDP pings, tunnelled packets, resync
   requests); step 5a replaces it with the `VoiceEngine`.
2. `Session`: scope, message coroutine, intents, statistics.
   `HumlaService` delegates `IHumlaSession` to the current session.
3. `HumlaClient`, `ClientState`, `ReconnectPolicy` (with
   `ReconnectBackoffTest` ported), `NetworkMonitor`. `HumlaService`
   delegates `IHumlaService` to the client.
4. Delete `connectionGeneration` and the connect, reconnect, and whisper
   locks. `ConnectionParams` is replaced by `ConnectTarget`; the
   `ConfigDelta` bridge (see the ground rules) keeps live settings working.

**Done when:** `HumlaConnection` is off the allowlist (deleted), and the
interop harness drives `HumlaClient` directly.
