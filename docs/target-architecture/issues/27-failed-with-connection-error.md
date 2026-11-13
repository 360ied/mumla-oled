# 27 — `Failed` can carry a `ClientError.Connection`

| | |
|---|---|
| Status | Decided 2026-11-12: add `ClientError.Internal`. `client.md` updated (error type, diagram, reconnect policy, start sequence). **Code change remains**: add the type, use it in `HumlaClient.runAttempt`'s unexpected-exception branch, update `HumlaClientTest.ioErrorReconnectsButBugDoesNot`, and check app code that switches on `ClientError`. |
| Category | Spec drift (deliberate in code, undocumented in spec) |
| Severity | Benign |
| Verified | Yes (code read) |
| Area | Client |
| Spec | [client.md § Reconnect policy](../client.md#reconnect-policy) ("Which errors reconnect"), [client.md § Start sequence](../client.md#start-sequence) step 2, [client.md § ClientState](../client.md#clientstate) |
| Code | `libraries/humla/src/main/java/se/lublin/humla/client/HumlaClient.kt` (`runAttempt`, the `catch (e: Exception)` branch after `transports.create`, around lines 263-277); test `HumlaClientTest.ioErrorReconnectsButBugDoesNot` |
| Gates | Not gating |

Line numbers below refer to `master` at `092d9dc8`.

## Summary

Unexpected exceptions during transport creation (anything other than
`TransportException`, `IOException`, or cancellation) publish
`ClientState.Failed(target, ClientError.Connection(...))`. The spec says
`Connection` errors always reconnect and that any pre-session error other
than trust is a `Connection` error that reconnects. So `Failed` now means
"terminal", while its error type says "reconnectable".

## What the spec says

`client.md` § Reconnect policy: "**Which errors reconnect:** only
`ClientError.Connection`". Start sequence step 2: "any other error before
the session exists (DNS, connect, TLS I/O) is `ClientError.Connection` and
reconnects". The diagram has `Connecting → Reconnecting` on Connection
error and `Connecting → Failed` only on Rejected, Removed, Trust.

## What the code does

```kotlin
} catch (e: Exception) {
    // Unexpected failures fail terminally without retry: deterministic
    // bugs must neither loop reconnects forever nor crash the process
    // via an uncaught coroutine exception. The error stays visible.
    if (epoch != connectEpoch) return
    teardownLiveSession()
    _state.value = ClientState.Failed(
        target,
        ClientError.Connection("Connect failed: ${e.message}", e),
    )
    return
}
```

Pinned by `HumlaClientTest.ioErrorReconnectsButBugDoesNot`.

## Impact

The behavior (do not reconnect-loop on a deterministic bug) is sensible.
But a consumer that decides "can retry?" from `Failed.error`'s type gets
the wrong answer, and the spec's error→transition table is incomplete.

## Fix directions

Add a distinct error, for example `ClientError.Internal(message, cause)`,
used for unexpected failures, with `Failed` as its transition; add it to
`client.md`'s `ClientError` list and diagram (on `master` first). Update
the test.

## Acceptance

`Failed` never carries `Connection`; spec lists the new error;
`./scripts/check.sh` passes.
