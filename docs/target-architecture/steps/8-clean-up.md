# Step 8 — Clean up

Part of the [migration](../migration.md). **Status: not started.**

Delete `IHumlaObserver`, `HumlaCallbacks`, the live handles, `IUser`,
`IChannel`, and any remaining compatibility shims.

**Done when:** the allowlist holds only the boundary files listed in
[enforcement](../enforcement.md#concurrency-tripwire).
