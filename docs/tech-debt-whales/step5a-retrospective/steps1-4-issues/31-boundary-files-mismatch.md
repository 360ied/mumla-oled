# 31 — `BOUNDARY_FILES` in the gate has an entry the spec does not list

| | |
|---|---|
| Category | Spec drift (enforcement record) |
| Severity | Benign |
| Verified | Yes |
| Area | Enforcement |
| Spec | [enforcement.md § Concurrency tripwire](../../target-architecture/enforcement.md#concurrency-tripwire) |
| Code | `scripts/check_architecture.py` (`BOUNDARY_FILES`, lines 44-56) |
| Gates | Not gating; a docs fix |

## Summary

`enforcement.md` lists five planned boundary files (files allowed to hold
threads/handlers permanently, and the only ones that may join the
allowlist). `check_architecture.py` has six: it adds
`libraries/humla/src/main/java/se/lublin/humla/net/TransportImpl.kt`
("Transport reader thread (step 3 extracts the Transport)"). The reader
thread does live there (`TransportImpl.kt` around line 412,
`Thread(..., "transport-reader")`), while `HumlaTCP.kt`, listed in the spec
as the "TCP reader thread" file, now holds only a few `@Volatile` flags.

## What the spec says

`enforcement.md`:

```text
libraries/humla/src/main/java/se/lublin/humla/net/HumlaTCP.kt            # TCP reader thread (step 3 keeps the file)
libraries/humla/src/main/java/se/lublin/humla/net/HumlaSSLSocketFactory.kt
libraries/humla/src/main/java/se/lublin/humla/route/RouteControl.kt      # step 4
libraries/humla/src/main/java/se/lublin/humla/voice/VoiceEngine.kt       # step 5a (step 4 uses voice/VoiceControl.kt, removed in 5a)
app/src/main/java/se/lublin/mumla/util/CrashReporter.kt
```

> Every other change can only lower counts or remove entries.

## What the code does

`check_architecture.py` `BOUNDARY_FILES` includes all of the above plus
`net/TransportImpl.kt`, with a comment justifying it. (The first step-5a
attempt added the matching line to `enforcement.md`; that edit was reset
away with the rest of 5a, which is why the mismatch exists at HEAD.)

## Impact

The record of which files are deliberate boundaries lives only in code;
step 8's done-when ("the allowlist holds only the boundary files listed in
enforcement.md") cannot be checked against the gate as written.

## Fix directions

Add `TransportImpl.kt` (reader thread) to `enforcement.md`'s list on
`master`, and decide whether `HumlaTCP.kt` stays a boundary file (if it no
longer creates a thread, consider removing it from both lists after it is
off the allowlist).

## Acceptance

`enforcement.md`'s list equals `BOUNDARY_FILES`.
