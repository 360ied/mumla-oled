# 28 — A failed `LocalFacts` load is not logged

| | |
|---|---|
| Status | Resolved 2026-11-13 (`7459c0de`, merged in `5ac34784`): the fallback is kept and the failure logs a `WARNING`. |
| Category | Spec drift |
| Severity | Benign (diagnosability) |
| Verified | Yes (code read) |
| Area | Client |
| Spec | [client.md § Start sequence](../client.md#start-sequence) step 1 |
| Code | `libraries/humla/src/main/java/se/lublin/humla/client/HumlaClient.kt` (`runAttempt`, the `localFactsStore.load` try/catch, around lines 227-236) |
| Gates | Not gating; trivial |

Line numbers below refer to `master` at `092d9dc8`.

## Summary

The spec says that if loading `LocalFacts` fails, the session starts with
empty lists and the error is logged. The code does the fallback but does
not log.

## What the spec says

> If loading fails, the session starts with empty lists and the stored
> self-mute values treated as unset, and the error is logged; a database
> problem never blocks connecting.

## What the code does

```kotlin
val facts: LocalFacts = try {
    withContext(ioDispatcher) { localFactsStore.load(target.server.serverId) }
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    LocalFacts.empty()
}
```

## Impact

A failing store makes local mute/ignore lists silently look cleared to the
user, with no trace in logcat.

## Fix directions

Log at warning level in the `catch (e: Exception)` branch (the client
package is Android-free; use the same `java.util.logging.Logger` pattern
used elsewhere in `HumlaClient`).

## Acceptance

A warning is logged on load failure; `./scripts/check.sh` passes.

## Related

- [14](14-local-facts-self-mute-stub.md)
