# `HumlaService`: Intrinsic vs. Accidental Complexity

`HumlaService` is the largest hand-written class in the tree and the single
hottest file in the repository. The standing question is whether its size is
*earned* — a service that genuinely is the session — or whether it is a
container for work that belongs elsewhere. This document answers that
question with a per-member decomposition rather than a line-count opinion, and
records the intended decomposition direction so a future reader can tell
intention from oversight.

Snapshot: commit `1d6d509c`, 2026-10-31. All figures below are measurements at
that commit; the appendix reproduces them.

Subject: [`HumlaService.kt`](../../libraries/humla/src/main/java/se/lublin/humla/HumlaService.kt).

## Verdict

The god-class verdict is earned, but not by the total line count. At the
member level the class is roughly:

- **~3% genuinely unnecessary behavior** — code that should not exist at all.
- **~38% necessary behavior attached to the wrong owner** — real work that a
  different class should own, extractable without semantic loss.
- **~28% intrinsically this class's** — the connection state machine.
- **~17% façade surface** — delegation with no logic, which is the cost of a
  wide API, not bloat.
- **~11% platform shell** — work that must live in *some* service, though not
  necessarily in this one.

The sharper figure is **state**, not lines: of 52 instance fields, **26 — half
the mutable state — belongs to audio routing and configuration application**,
neither of which is the Mumble protocol. That shared-state overlap is what
makes the class hard to change, and it is why the recommended target is
*route-state eviction* rather than a rewrite.

## 1. Why the naive metrics lie

Two obvious measurements invert or inflate the answer. Both are recorded
because the same traps will catch the next reader.

### Churn: renamed files hide history

```bash
git log --oneline --follow -- libraries/humla/src/main/java/se/lublin/humla/HumlaService.kt
```

reports 15 commits, which reads as "rarely touched, low risk." It is an
artifact twice over:

- The `.kt` file was created on 2026-10-12 by `humla: convert HumlaService to
  Kotlin`. It is three weeks old at the snapshot commit, so *every* commit it
  has falls inside any "last six months" window — the filter compared nothing.
- `--follow` does not bridge the `.java` → `.kt` transition, because the
  conversion was a full rewrite rather than a tracked rename.

The honest count, unioning both paths with `--diff-filter=AMRC`, is 75 commits
on the `.java` plus 15 on the `.kt`, and the recent rate is extreme:

| month | commits touching `HumlaService.*` | repo commits | share |
|---|---:|---:|---:|
| 2026-08 | 17 | 191 | 8% |
| 2026-09 | 30 | 462 | 6% |
| 2026-10 | 53 | 296 | 17% |

In October 2026 roughly one in six commits in the entire repository touched
this one file. The top five hottest files over the last 90 days are
`HumlaService`, `MumlaService`, `AudioHandler`, `MumlaActivity`, and
`AudioOutput` — precisely the classes that would be named god classes. That is
the mechanism, not a coincidence: complexity concentrates where
responsibilities concentrate, so churn concentrates there, so the file grows,
which attracts more churn.

### Size: locals are not state

```bash
grep -cE '^\s+(private\s+)?(val|var) ' libraries/humla/src/main/java/se/lublin/humla/HumlaService.kt
```

reports 207, which reads as "207 fields, unmanageable." It counts every local
`val` inside every function. The class has **52** instance fields. State, not
line count, is what determines whether unrelated changes collide, so this
matters: the earlier figure overstated the problem by 4×, and the corrected
figure still shows the same structural issue.

## 2. The class as measured

| metric | value |
|---|---:|
| total lines | 2,442 |
| blank lines | 201 |
| comment-only lines | 546 (22%) |
| code lines | 1,695 |
| instance fields | 52 |
| top-level members | 167 |
| interfaces implemented | 6 |
| `override fun` | 90 |
| `IHumlaSession` + `IHumlaService` API members | 63 |

Six interfaces from six unrelated concerns: `LifecycleService` (Android
lifecycle), `IHumlaService` (service control), `IHumlaSession` (the session
API), `HumlaConnection.HumlaConnectionListener` (transport callbacks),
`HumlaLogger` (logging), and `AudioHandler`/`AudioOutput` listener roles
(audio callbacks).

## 3. Cluster decomposition

Every top-level member bucketed by the concern it serves. Covered lines are
2,357 of 2,442; the remainder is inter-member spacing and comments.

| cluster | members | lines | fields | ln/member |
|---|---:|---:|---:|---:|
| audio route / SCO / voice-mode policy | 39 | 690 | 17 | 17.7 |
| connect / reconnect / network state | 24 | 436 | 8 | 18.2 |
| session façade (delegation) | 48 | 408 | 0 | 8.5 |
| platform shell (lifecycle, wakelocks) | 13 | 257 | 5 | 19.8 |
| config-delta application | 12 | 208 | 9 | 17.3 |
| handler wiring | 8 | 122 | 4 | 15.2 |
| mute/deaf reconciliation | 6 | 105 | 2 | 17.5 |
| whisper / text messages | 9 | 105 | 2 | 11.7 |
| input-mode holders | 5 | 9 | 5 | 1.8 |
| logging | 3 | 17 | 0 | 5.7 |

The façade bucket (48 members) corresponds almost exactly to the 48 members of
[`IHumlaSession.kt`](../../libraries/humla/src/main/java/se/lublin/humla/IHumlaSession.kt):
each member is 3–4 lines of KDoc and a body of the form
`requireModelHandler().getX()`. That is 17% of the file performing no work. It
cannot be deleted — it is the published API — but it should not be counted as
complexity when judging the class.

## 4. Taxonomy

### Unnecessary — should not exist (~6–20 lines, <1%)

`onScoFailed` shows a `Toast.makeText(..., R.string.bluetooth_sco_failed, ...)`.
A protocol library surfacing Android UI strings with app resources is the one
place where bundling created code with no defensible owner at all. Everything
else in the class is doing real work.

### Necessary but wrongly owned — accidental (~898 lines, 38%; 26 of 52 fields)

**Audio route policy — 690 lines, 17 fields.** The largest single inbound
concern, and the one that earns the god-class label. The library already
decomposes audio:

- [`BluetoothScoManager.kt`](../../libraries/humla/src/main/java/se/lublin/humla/audio/BluetoothScoManager.kt) — 844 lines, owns SCO lifecycle
- [`AudioOutput.kt`](../../libraries/humla/src/main/java/se/lublin/humla/audio/AudioOutput.kt) — 694 lines, owns the output pipeline
- [`AudioHandler.kt`](../../libraries/humla/src/main/java/se/lublin/humla/protocol/AudioHandler.kt) — 1,055 lines, owns the audio protocol

Route *selection* is a fourth concern living in the service, negotiating with a
manager it also constructs. Its 17 fields:

`mHalfDuplex`, `mScoRequested`, `mScoManager`, `mScoRouteActive`,
`mAudioManager`, `mSavedAudioMode`, `mVoiceModeHeld`, `mVoiceModeClaimed`,
`mMediaRouteSelected`, `mAppliedVoiceRoute`, `mHandsetAudioStream`,
`mDeviceCallback`, `routeDebounceJob`, `routeSettleJob`, `mSettleAttempts`,
`mSettleFired`, `mSettlePending`.

Nine of those (`mScoRouteActive`, `mVoiceModeHeld`, `mVoiceModeClaimed`,
`mMediaRouteSelected`, `mAppliedVoiceRoute`, `mSettleFired`, `mSettlePending`,
`mSettleAttempts`, plus `mScoRequested`) encode a hand-rolled state machine as
interacting booleans and a counter — perhaps three states in disguise. That
shape is the definition of accidental complexity: the underlying problem is
genuinely hard, but the complexity took the form of loose flags readable and
writable from protocol code, rather than an encapsulated state type.

**Configuration application — 208 lines, 9 fields.** `apply(ConfigDelta)` alone
is 168 lines fanning a settings bag out to four sub-objects
(`AudioHandler.Builder`, input modes, connection, trust store) and deciding
which changes force a reconnect. A mapper for a settings object, inlined into
the session aggregate.

### Intrinsic — belongs here (~663 lines, 28%; 12 fields)

The connect/synchronize/reconnect machine (`onConnectionEstablished`,
`onConnectionSynchronized`, `onConnectionDisconnected`, `setReconnecting`,
`cancelReconnect`, the network callback pair) plus handler wiring. This is one
state machine with real ordering constraints; splitting it from its state would
create the distributed-god-object failure mode rather than reduce complexity.
`mNetworkCallback` and the reconnect `Job` must be co-located with
`mConnectionState` and `connectionGeneration`.

### Platform shell (~257 lines, 11%; 5 fields)

`onCreate`/`onStartCommand`/`onDestroy`/`onBind`, wake lock and Wi-Fi lock
acquisition, `stopSelfIfIdle`. Intrinsic to being a foreground service; not
intrinsic to being the session. Movable, not necessary to move.

## 5. Comment density as a diagnostic

Comment-only share, which tracks where complexity was *absorbed with
explanation* instead of *moved out*:

| region | comment share |
|---|---:|
| route policy (`L1128`–`L1610`) | 33% |
| `apply(ConfigDelta)` | 17% |
| platform shell (`onCreate`) | 14% |
| whole file | 22% |

The route region carries half again the file's baseline. Those comments are not
documentation of intent — they are scars from debugging sessions: Doze letting
the Wi-Fi radio sleep between maintenance windows, Bluetooth profiles attaching
staggered (HFP/ACL first, A2DP sink seconds later), system-initiated SCO
requests racing the service's own. Each is a genuine platform pathology the
code overcame in place. Complexity that survives as explained booleans leaves
this signature.

## 6. What this implies

The intrinsic kernel — connection machine, handler wiring, lifecycle, façade —
is roughly 1,400 lines over 14 fields. That is a normal, defensible Android
service. The class is not intrinsically 2,442 lines; it is intrinsically about
40% of that.

The refactoring target is therefore not "shrink the file." It is **stop owning
route state**:

1. Move the route state machine out as a unit — all 17 fields plus the
   debounce/settle jobs and device callback — behind a narrow interface the
   service delegates to. Collapsing the nine flags into a sealed state type is
   part of that move, not a separate cleanup.
2. Move `apply(ConfigDelta)` mapping alongside the builder it configures.
3. Delete the `Toast`.
4. Leave the connection machine, the façade, and the lifecycle alone.

Sequencing matters: the file absorbs ~1.7 edits/day, so a large restructuring
begun while it is hot will collide with every other change. Extraction should
be done as state-first, behavior-preserving slices with `./scripts/check.sh`
green between them, not as a rewrite.

## 7. What would change this conclusion

- **Multi-author coordination.** The repository currently has one author on
  this file, so merge-conflict cost is zero and shared mutable state is
  survivable via a single mental model. With three contributors, the same
  numbers become a much stronger argument for extraction.
- **Stabilizing churn.** Badness is churn-weighted. If the file stopped
  changing, the 38% misplaced block would cost nothing, and the case for
  touching it would weaken accordingly.
- **A routing fix that removes the platform pathologies.** If Android's
  staggered-profile and Doze behavior could be handled upstream of the service
  (a single owner for "which sink is live"), the route cluster would shrink on
  its own rather than merely relocating.

## Appendix A: reproduction

The decomposition script parses top-level members at four-space indent, assigns
each to a concern by name, and tallies lines and fields. Run from the
repository root:

```bash
python3 - <<'PY'
import re, subprocess
P = "libraries/humla/src/main/java/se/lublin/humla/HumlaService.kt"
lines = open(P).read().split("\n")
decl = re.compile(r'^    (?:@\w+ )*(?:(?:private|internal|public|protected|override|open|final'
                  r'|lateinit|const|suspend|abstract|inline|operator)\s+)*'
                  r'(fun|val|var|class|object|init|companion)\b')
starts = [i for i, l in enumerate(lines) if decl.match(l) or re.match(r'^    companion object', l)]
members = []
for n, s in enumerate(starts):
    e = starts[n + 1] if n + 1 < len(starts) else len(lines)
    head = lines[s].strip()
    ident = re.search(r'\b(fun|val|var|class|object)\s+([A-Za-z_]\w*)', head)
    # anchored: constructor params like `class Binder internal constructor(private val x)`
    # are not class state and must not be counted as fields
    is_field = bool(re.match(r'(?:private |internal |public |protected )?'
                             r'(?:lateinit )?(?:val|var) ', head))
    members.append((e - s, ident.group(2) if ident else head, is_field))

BUCKETS = {
    "AUDIO_ROUTE": """applyAudioRoute maybeStartRequestedSco counterUnrequestedSystemSco
        queryMediaDevicesPresent stopScoWithNotify ensureVoiceMode retryBluetoothSco releaseVoiceMode
        updateBluetoothScoRoute applyScoRoute onRouteDevicesChanged logDeviceTypes updateRouteBuilderLocked
        updateRouteBuilder armRouteSettle cancelRouteSettle cancelRouteDebounce applyVoiceMode isSystemScoOn
        isBluetoothScoActive isMediaRouteActive onAudioRouteChanged mDeviceCallback mHalfDuplex mScoRequested
        mScoManager mScoRouteActive mAudioManager mSavedAudioMode mVoiceModeHeld mVoiceModeClaimed
        mMediaRouteSelected mAppliedVoiceRoute mHandsetAudioStream routeDebounceJob routeSettleJob
        mSettleAttempts mSettleFired mSettlePending""".split(),
    "CONFIG_APPLY": """apply buildConnectDelta isTrustStorePathAllowed mServer mCertificate
        mCertificatePassword mForceTcp mClientName mAccessTokens mTrustStore mTrustStorePassword
        mTrustStoreFormat""".split(),
    "CONNECT_MACHINE": """connect disconnect onConnectionEstablished onConnectionSynchronized
        onConnectionDisconnected onConnectionHandshakeFailed onConnectionWarning setReconnecting
        cancelReconnect getReconnectDelay registerNetworkCallback unregisterNetworkCallback getConnection
        isConnectionEstablished isSynchronized requireConnection mConnection mConnectionState mReconnecting
        mReconnectAttempts mNetworkCallback mNetworkCallbackRegistered connectionGeneration
        reconnectJob""".split(),
    "PLATFORM_SHELL": """onCreate onStartCommand onDestroy onBind HumlaBinder stopSelfIfIdle companion
        acquireWifiLock releaseWifiLock mWakeLock mWifiLock mHandler mCallbacks mDestroyed""".split(),
    "HANDLER_WIRING": """getModelHandler getAudioHandler createAudioHandler mAudioInputListener
        mAudioOutputListener mModelHandler mAudioHandler requireModelHandler""".split(),
    "INPUT_MODE": "mTransmitMode mActivityInputMode mToggleInputMode mContinuousInputMode mAudioBuilder".split(),
    "MUTE_STATE": """applyOptimisticSelfMuteDeaf setSelfMuteDeafState setMuteDeafState setTalkingState
        mLocalMuteHistory mLocalIgnoreHistory""".split(),
    "WHISPER_TEXT": """registerWhisperTarget setVoiceTargetId setListeningChannel setListeningVolume
        getWhisperTarget sendChannelTextMessage sendUserTextMessage mVoiceTargetId
        mWhisperTargetList""".split(),
    "LOG": "logInfo logError logWarning".split(),
}

agg = {k: [0, 0, 0] for k in list(BUCKETS) + ["DELEGATION"]}
for ln, ident, is_field in members:
    k = next((k for k in BUCKETS if ident in BUCKETS[k]), "DELEGATION")
    agg[k][0] += 1
    agg[k][1] += ln
    agg[k][2] += is_field

print(f"{'cluster':<16}{'mem':>5}{'lines':>7}{'fields':>7}")
for k, (m, l, f) in agg.items():
    print(f"{k:<16}{m:>5}{l:>7}{f:>7}")
print(f"{'TOTAL':<16}{sum(v[0] for v in agg.values()):>5}"
      f"{sum(v[1] for v in agg.values()):>7}{sum(v[2] for v in agg.values()):>7}")
PY
```

Churn, accounting for the Kotlin conversion and the `.java` predecessor:

```bash
git log --format='%ad' --date=format:'%Y-%m' --diff-filter=AMRC \
  -- '*HumlaService.java' '*HumlaService.kt' | sort | uniq -c
git log --since='90 days ago' --name-only --format='' --diff-filter=AMRC \
  | grep -E '\.(kt|java)$' | sort | uniq -c | sort -rn | head -5
```

Comment density, for the absorption diagnostic:

```bash
sed -n '1128,1610p' libraries/humla/src/main/java/se/lublin/humla/HumlaService.kt \
  | grep -cE '^\s*(\*|/\*|//)'
```

Counts are line-based approximations: a member's line count includes its KDoc,
and bucketing by name assigns multi-concern members to a single bucket. The
figures are stable to a few percent and the conclusions do not depend on the
margins.
