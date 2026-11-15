# 34 — The published `VoicePolicy` is not checked as an invariant

| | |
|---|---|
| Status | Open — should be closed before step 5a.3b. |
| Category | Verification gap |
| Severity | Harmful (the policy that gates transmit and the sender filter is checked only as a pure function, never as what the collector publishes across a scenario) |
| Verified | Yes (code read of the checker and the collector tests) |
| Area | Client tests, voice policy |
| Spec | [testing.md § Virtual-time tests](../testing.md#virtual-time-tests) (invariants), [voice.md § `VoicePolicy`](../voice.md#voicepolicy) (`PolicyCollector`) |
| Code | `libraries/humla/src/test/java/se/lublin/humla/client/ClientTestSupport.kt` (`InvariantChecker.checkState`); `libraries/humla/src/test/java/se/lublin/humla/voice/PolicyCollectorTest.kt`; production `voice/PolicyCollector.kt`, its wiring in `HumlaService` |
| Gates | Should gate step 5a.3b (the sender filter reads the collector's output natively); the transmit fields move native in 5a.4 |

Found while reviewing the resolution of
[05](05-client-edge-invariants-weak.md), on branch
`feature/session-transport-tests` at `7844ef61`.

## Summary

testing.md requires, after every step of every scenario, that "no
`VoicePolicy` allows transmit while `effectiveSelfMute` is true or the user
is server-muted, deafened, or suppressed". The invariant checker from 05
evaluates this by calling the production `voicePolicy()` on each published
`SessionState` and comparing `transmitAllowed` against the same flags
`voicePolicy()` itself reads. That catches a mutation inside
`voicePolicy()` and nothing else, which `VoicePolicyTest` already covers.
The policy that reaches the voice layer, `PolicyCollector.policy`, is never
observed in a scenario.

## What is not covered

`PolicyCollector` combines the current session's state with
`VoiceSettings`, skips unchanged values, and publishes. Its own tests
(`publishesMutedFallbackWhenDisconnected`, `updatesWhenSessionConnects`,
`publishesTransmittingPolicyWhenSynced`) cover three static cases. None
cover the transitions where a stale policy is dangerous:

- a session switch (connect to another target while connected), where the
  old session's transmitting policy must not outlive it;
- a return to no session after `SYNCED` (disconnect, connection error,
  `Failed`), which must publish the muted fallback;
- a self-mute, server mute, deafen, or suppress arriving while `SYNCED`,
  which must close the gate in the next published value;
- the locally-muted list (and, once added, the known sessions) following
  `localMuted` changes and user removal.

In production the collector's input is not the client's session directly
but `HumlaService._sessionState`, a mirror the service resets to
`emptySessionState()` on its own disconnect paths. A collector wired to
`client.currentSession` in a test is therefore close to, not identical to,
production.

## Why it matters

- A transmitting policy while muted sends the microphone to the server
  when the user believes it is off: the most user-visible failure the
  voice path has.
- Step 5a.3b's sender filter reads `locallyMuted` and the known sessions
  from the collector's output through the policy sequence lock; step 5a.4
  pushes the whole policy to native. From 5a.3b on, the collector's output
  is the contract, and it is the one piece of the policy path the
  scenario invariants do not observe.

## Fix directions

1. Build a real `PolicyCollector` in `ClientHarness`, on the test
   dispatcher, fed by a source that mirrors `HumlaService`'s (the current
   session's state, or `emptySessionState()` when there is none). Record
   every published value with an unconfined collector, as the checker
   already does for `ClientState`.
2. Check after every step that the latest published policy does not allow
   transmit unless the current session is `SYNCED` and its self is not
   effectively self-muted, server-muted, deafened, or suppressed; and that
   with no current session the policy is the muted fallback. Check
   `locallyMuted` against the current state's `localMuted` users.
3. Drop the `voicePolicy()` recomputation from `checkState`, or keep it
   only as a cheap cross-check.
4. Optionally move the collector's source out of `HumlaService` (for
   example, to a flow `HumlaClient` exposes) so test and production share
   it; otherwise document the mirror as an accepted difference.

## Acceptance

- Every `HumlaClientTest` scenario checks the published policy after each
  step through the harness.
- A checker self-test shows the detector firing on a transmitting policy
  with a muted self.
- Breaking the collector on purpose is caught: for example, not resetting
  to the fallback when the session goes away, or holding the previous
  value across a session switch.
- `./scripts/check.sh` passes.

## Related

- [05](05-client-edge-invariants-weak.md): the invariant harness this
  extends.
- [18](18-native-mute-writers-bypass-policy.md): the native mute writers
  outside the policy path, until 5a.4.
