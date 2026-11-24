# Step 8 — Clean up

Part of the [migration](../migration.md). **Status: not started.**

Step 7 deletes `IHumlaObserver`, `HumlaCallbacks`, the live handles,
`IUser`, and `IChannel` in the commit that leaves them unused (see
[step 7](7-screens-and-service-cut-over.md#step-7--screens-and-service-cut-over)).
This step takes the non-boundary allowlist entries that no screen or
service change touched (`CrashReportsFragment.kt`, `ServerAdapter.kt`,
`MumbleImageGetter.kt`, and `Server.kt`) and deletes any remaining
compatibility shims without a production caller.

**Done when:** the allowlist holds only the boundary files listed in
[enforcement](../enforcement.md#concurrency-tripwire).
