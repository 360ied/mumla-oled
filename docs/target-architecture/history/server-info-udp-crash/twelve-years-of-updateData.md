# Twelve Years of `updateData()`: A Brief History of the Poll Loop

Companion to the [server-info crash retrospective](README.md). All of this
is public git history — the point is that the crash was twelve years in
the making, and every era left a fingerprint on the murder weapon.

## 2014: the original sin

In January 2014, Andrew Comminos wrote a fragment that polls the Mumble
service once a second and posts the result to the main thread. It caught
exactly one exception:

```java
try {
    updateData();
} catch (RemoteException e) {
    e.printStackTrace();
}
```

At the time this was *correct and complete*. The getters returned plain
`long` fields defaulting to `0`; the only thing that could possibly go
wrong was IPC. The `// wow this is ugly` comment above the scheduler was
already there in the initial commit — the author knew, and shipped it
anyway. Correct call: the tab worked for a decade.

Three months later (`3e845a12`, "Prevented issue with information tab
updating after destroy") the lifecycle race was found and guarded with
`if(!isDetached())`. The teardown race is as old as the tab itself.

## 2015: "Safer polling for server info"

`ba9da8c1` did exactly what it says: stored the `ScheduledFuture`,
cancelled it on unbind, and switched the guard to `isVisible()`. The
catch clause stayed `RemoteException`-only, because nothing else could
throw. "Safer" held for ten years. Nobody re-examines a catch clause
that has never fired.

That same year the AIDL interfaces were removed in favor of plain
interfaces (`fb30b212`) — the first of two IPC migrations the fragment
would survive without changing shape. Same-process calls now, but the
`RemoteException` catch stayed, harmless and vestigial, like an appendix.

## 2017: "improving stability"

`3fbbef14` moved the fragment to the new session API — and quietly
*relaxed* the entry guard from `isSynchronized()` to `isConnected()`.
Read that twice: the one commit in the file's history whose message
claims stability is the one that widened the window in which the reads
execute. Under the old getters it didn't matter (zeros all the way
down). Under the 2026 getters, "connected but lightly synced" is exactly
the state that throws. The trap was set nine years before it snapped.

## 2020: "Rename the whole shebang"

Daniel Lublin renamed Jumble/MumbleClient into Mumla/Humla. Every
identifier in the file changed; not one behavior did. The `catch
(RemoteException)` crossed the rename untouched, a fossil in new
packaging.

## 2026, October: NPE-exact

The Kotlin migration (`18c066cd`) converted the file syntax-first with
an explicit policy: preserve every 2014 dereference *throw for throw*,
annotated `// NPE-exact`. This was the right migration discipline — and
it faithfully preserved a crash that didn't exist yet, like photocopying
a map of a minefield one month before someone buries the mines.

## 2026, November: the mines

`379a34b4` ("humla: wire client and delete `HumlaConnection`") replaced
the `long mLastUDPPing = 0` field with a nullable `Long?` that throws
`IllegalStateException` when unknown, and `HumlaService.getUDPLatency()`
passed it straight through. Twelve-year-old assumptions, all at once
wrong:

- that latency reads cannot throw (they now throw unchecked),
- that the catch clause enumerates failure (it enumerated 2014's
  failure),
- that "connected" implies "every value known" (UDP latency can stay
  unknown for the entire connection).

The poll loop did what it has done since January 2014: ran `updateData()`
on the main `Handler`, caught `RemoteException`, and let everything else
through. Everything else finally arrived.

## The moral

The bug was never *introduced* in the usual sense — no single commit is
wrong in isolation. The 2014 code was correct. The 2015 hardening was
correct. The 2017 relaxation was harmless. The 2020 rename was cosmetic.
The Kotlin conversion was disciplined. The transport refactor was good
state modeling. Each change was locally reasonable; the crash lived in
the *combination*, in a twelve-year-old catch clause no reviewer had
reason to look at. Contracts that outlive the era of their callers
need re-reading whenever the callee's era ends — which is what the
[retrospective's takeaways](README.md#takeaways-for-future-refactors)
are really about.
