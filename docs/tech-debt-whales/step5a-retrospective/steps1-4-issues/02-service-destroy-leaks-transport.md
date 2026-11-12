# 02 — Service destroy can leak the TLS connection

| | |
|---|---|
| Category | Confirmed regression (introduced by step 3) |
| Severity | Harmful (resource leak and ghost user on a rare path) |
| Verified | Yes, by code reading against the current tree and the pre-step-3 tree |
| Area | Client / transport lifecycle |
| Spec | [transport.md § Threads](../../target-architecture/transport.md#threads), [client.md § Ending a session](../../target-architecture/client.md#ending-a-session), [client.md § Session](../../target-architecture/client.md#session) |
| Code | `HumlaService.kt` (`onDestroy`), `client/HumlaClient.kt` (`disconnect`, `doDisconnect`, `close`, `runAttempt`), `client/Session.kt` (`close`), `net/TransportImpl.kt` (`scope`, `close`, `startReaderWriter`, `readerLoop`) |
| Gates | Not gating; independent bugfix |

Line numbers below refer to `master` at `092d9dc8`.

## Summary

When `HumlaService` is destroyed while connected, the teardown of the live
session may never run, and nothing else closes the TLS socket. The reader
thread keeps the socket open until the server times the client out (or, on
a busy server, parks forever in a full channel). Two spec rules are
missing: the transport is not closed by session-scope cancellation, and the
service's destroy path relies on an asynchronous launch that its own scope
cancellation can cancel before it starts.

## What the spec says

`transport.md` § Threads:

> Cancelling the session scope closes the socket from a completion handler,
> which unblocks the reader. The reader catches the resulting exception and
> exits without reporting a failure (the session is already ending).

`client.md` § Session:

> **Scope:** `parentScope` plus a fresh `SupervisorJob`. The message
> coroutine, keepalive timers, and transport writer all run in it.

`client.md` § Ending a session, step 3:

> Cancel the session's scope. Its completion handler closes the TCP socket,
> which unblocks the transport's reader thread; the reader's last send into
> the closed inbound channel fails harmlessly.

So the design guarantees that **cancelling the scope is sufficient** to
release the connection, whoever cancels it.

## What the code does

### No completion handler

`TransportImpl.kt:115`:

```kotlin
private val scope = CoroutineScope(
    parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]) + Dispatchers.IO,
)
```

There is no `invokeOnCompletion` (or equivalent) anywhere in
`TransportImpl.kt` or `Session.kt` (`grep invokeOnCompletion` finds none).
The socket and reader thread are released only by an explicit
`TransportImpl.close()` (line 664), which closes the inbound channel, the
socket, the outbound channel, cancels the scope, and joins the reader for
up to 1 s.

Also, the transport is created with `parentScope = attemptScope`
(`HumlaClient.runAttempt`, `transports.create(parentScope = attemptScope, ...)`),
so the writer coroutine and keepalive ping loop are children of the
**attempt** scope, not of the session's scope as the spec says.

### `close()` is called only from the client's teardown

`Session.close()` (`Session.kt:721`):

```kotlin
fun close() {
    checkNotMainThread()
    try { transport.close() } catch (ignored: Exception) {}
    scope.cancel()
}
```

It is called only from `HumlaClient.teardownLiveSession()`, which runs on
the client dispatcher from `doDisconnect`, `doConnect`, failure handling,
and `close()`. Note the order is inverted relative to the spec (transport
first, then scope), which is harmless on this path but shows the scope is
not what owns the connection.

### The destroy path

`HumlaService.onDestroy()` (around line 811):

```kotlin
// Order matters: disconnect first (async teardown onto the client
// dispatcher), owned teardowns next (each close launches a
// NonCancellable child so parent-scope cancellation in
// super.onDestroy() cannot truncate SCO/mode/mic release), then
// synchronous releases, super.onDestroy() last.
try {
    if (::client.isInitialized) client.disconnect()
} catch (ignored: Exception) {}
...
```

`HumlaClient.disconnect()` (line 108):

```kotlin
fun disconnect() {
    scope.launch(clientDispatcher) { doDisconnect() }
}
```

The client's scope is a child of `lifecycleScope` (`HumlaService.kt:595`,
`parentScope = lifecycleScope`). `HumlaService` is a `LifecycleService`;
`super.onDestroy()` at the end of `onDestroy` dispatches `ON_DESTROY`,
which cancels `lifecycleScope` and therefore the client scope.

`disconnect()` launches with the default `CoroutineStart.DEFAULT` onto
`Dispatchers.Default.limitedParallelism(1)`. If cancellation arrives before
the dispatcher starts the coroutine, **the coroutine never runs** (a
coroutine cancelled before it starts completes without executing its
body). `onDestroy` reaches `super.onDestroy()` within microseconds on main,
so this is a genuine race, not a theoretical one. The comment claims each
teardown "launches a NonCancellable child"; that is true of
`RouteControl.close()`, `VoiceControl.close()`, and `PolicyCollector.close()`,
but **not** of `client.disconnect()`.

### What happens to the connection then

If `doDisconnect` does not run:

- `teardownLiveSession()` never runs, so `Session.close()` and
  `TransportImpl.close()` never run.
- Cancelling the client scope cancels the attempt scope, the session scope,
  and the transport scope (all descendants), so the writer coroutine,
  keepalive loop, and message coroutine stop. But the **socket stays
  open** and the **reader thread** (`TransportImpl.kt:412`, a plain daemon
  `Thread` named `transport-reader`) keeps running `readerLoop`.
- `readerLoop` (`TransportImpl.kt:420`) blocks in `readShort()` with
  `soTimeout = READ_TIMEOUT_MS = 30000`. On a quiet server, after the
  server stops hearing pings it drops the client (murmur's timeout is about
  30 s), or the 30 s read timeout fires; either way the reader calls
  `failOnce(...)` (nobody is listening any more) and exits.
- On a busy server, reads keep succeeding and the reader forwards messages
  with `inboundChannel.trySendBlocking(...)`. The consumer (the session's
  message coroutine) is cancelled, and the channel is closed only by
  `TransportImpl.close()`, which never runs. After 1024 queued messages
  (`INBOUND_CAPACITY`) the reader **blocks forever** in `trySendBlocking`,
  holding the socket open until the server times the client out at the TCP
  level; the thread itself is leaked until process death.
- Voice and route are still released, because `VoiceControl.close()` and
  `RouteControl.close()` are separate `NonCancellable` teardowns in
  `onDestroy`; but the spec's order (voice stop, then route end, then
  session close) is not followed on this path.

## Before the migration

At the pre-step-3 tree (`5ff29253^`), `HumlaService.onDestroy` tore the
connection down **synchronously**, with a comment stating why:

```kotlin
// Socket + mic teardown: disconnect() alone only drops the socket
// (pipeline shutdown rides the async disconnect callback, which may
// never run after destroy). Shut down synchronously so a destroyed
// service never leaks the connection or capture while the process
// lives on.
...
mConnection?.disconnect()
mConnection = null
```

Step 3 replaced this with the asynchronous `client.disconnect()` and lost
the guarantee.

## When it happens

Only when the service is destroyed **while a session is live or
connecting**: for example, the app tearing the service down on exit without
disconnecting first, or the system destroying the service while the
process survives. If the process is killed outright, the socket dies with
it and nothing leaks. How often the app's own flows destroy a connected
service has not been checked; it is plausibly rare, which matches the bug
not being noticed.

## Impact

- The server keeps a ghost user for up to about 30 s (longer on a busy
  server, until TCP-level timeout).
- A leaked daemon reader thread and socket while the process lives on.
- The spec's core lifecycle guarantee ("cancelling the scope releases
  everything") does not hold, so any future code that relies on scope
  cancellation for teardown (step 6's `AppContainer`, step 7's
  `ConnectionService`) inherits the leak.

## Fix directions

Two independent fixes; both are recommended because each closes the leak
on its own and together they restore the spec's model.

1. **Make the transport close itself on scope cancellation** (the spec's
   model). In `TransportImpl`, register a completion handler on its scope's
   `Job` that performs the non-blocking part of `close()`: set `closed`,
   close the inbound channel, close the socket, close the outbound channel.
   Do not `join()` the reader from a completion handler (handlers must be
   fast and non-blocking; closing the socket is enough to unblock the
   reader, which then sees `closed == true` and exits quietly). Consider
   creating the transport in the **session's** scope rather than the
   attempt scope, per `client.md § Session`, or ensure the attempt scope's
   cancellation is equivalent. Keep `close()` idempotent (`closed`
   compare-and-set already exists).
2. **Make the service's destroy teardown non-cancellable.** Either have
   `HumlaClient.disconnect()` (or a dedicated `closeForDestroy()`) launch
   with `NonCancellable` and/or `CoroutineStart.ATOMIC`, matching the
   other `close()` methods, or make `HumlaClient.close()` do so and call
   that from `onDestroy` instead of `disconnect()`. Note
   `HumlaClient.close()` today also uses a plain `scope.launch`, so it has
   the same race.

Also align `Session.close()` with the spec order (cancel the scope, let the
completion handler close the transport), or document why explicit close
first is kept.

## Acceptance

- A unit test (virtual time, fake `Transport` or a test double that records
  `close`) shows that cancelling the client's parent scope immediately
  after `disconnect()` still closes the transport.
- A unit test shows that cancelling a session scope (without calling
  `close()`) closes the transport's socket and lets the reader exit.
- `grep -n invokeOnCompletion net/TransportImpl.kt` (or equivalent) shows
  the handler.
- `./scripts/check.sh` passes; murmur interop (`scripts/test_interop.sh`)
  still passes.

## Related

- [04](04-session-transport-virtual-time-gaps.md): there are no
  `Session`/`TransportImpl` tests that would have caught this.
- [30](30-connected-races-sync-tail.md): another place where the session
  scope does not own what the spec says it owns.
