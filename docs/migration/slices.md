# Migration Slices (Concrete Work Plan)

Each slice is a sequence of atomic commits; every commit leaves the tree
working and passing `./scripts/check.sh`. Reviewers check conformance to
[porting-guide](porting-guide.md); review attribution goes in commit
messages for agent-reviewed slices. The binder surface
(`IMumlaService`/`IHumlaService`/`IHumlaSession`) stays frozen throughout.

## Slice 0 — Scaffold (no behavior change)

1. Create the worktree from the repo root (root stays on `master`):
   `./scripts/worktree.py add migration/full-modern`
2. Add `gradle/libs.versions.toml` with these pins (verified 2026-10-12,
   re-verify at scaffold; floors are binding, patches float):
   - `kotlin = "2.4.21"` (AGP 8.13.2 is inside its supported range),
     `ksp` = KSP2 line matching Kotlin 2.4.21,
     `coroutines` = release recommended for Kotlin 2.4 (floor 1.10.x)
   - `compose-bom = "2026.09.00"` (pins `compose-material3 1.4.0`),
     no hand-pinned Compose artifacts
   - `hilt = "2.60.1"` (`com.google.dagger`), `androidx-hilt = "1.4.0"`
   - `room = "2.8.5"` (`androidx.room`; not the `room3` 3.0.x line)
   - `lifecycle`, `datastore-preferences` = latest stable at scaffold
   - `paparazzi = "2.0.0-alpha05.1"` — alpha accepted deliberately: the
     2.x line renders dialogs/popups as real windows, which the dialog
     parity gate needs. **Fork:** if the plugin requires Gradle 9+
     (wrapper here is 8.13), either upgrade the wrapper (recorded as its
     own commit) or fall back to the Paparazzi 1.3.x stable line and gate
     dialogs on-device only.
3. Apply plugins: `org.jetbrains.kotlin.android` (both modules),
   `com.google.devtools.ksp`, `com.google.dagger.hilt.android`,
   `kotlin-parcelize`, Paparazzi (app module). minSdk stays 21.
4. Baseline: `./scripts/check.sh` green before any slice-1 edit.

## Slice 1 — minSdk 33 + legacy deletion (trial slice)

Config: `minSdkVersion 21 → 33` in `app/build.gradle` and
`libraries/humla/build.gradle`. Then the full site inventory below —
every row is a deletion, no conversions. (Line numbers are pre-slice;
`S` = 31, `S_V2` = 32.)

### App module (`:app`)

| Site | Gate | Disposition |
|---|---|---|
| `MumlaActivity.java:535` TIRAMISU `getParcelable` | typed-vs-deprecated | Keep typed branch only; delete `else` |
| `MumlaActivity.java:541-542` deprecated `getParcelable(String)` | deprecation | Switch both calls to typed `getParcelable(key, Server.class)` |
| `MumlaActivity.java:1011` S `BLUETOOTH_CONNECT` | `SDK_INT >= S &&` | Delete the version clause; permission exists at 33 |
| `MumlaActivity.java:1032` TIRAMISU notifications | `SDK_INT >= TIRAMISU &&` | Delete the version clause; keep the asked-flag + grant flow |
| `ChannelChatFragment.java:194` S_V2 storage | `SDK_INT <= S_V2` branch | Delete legacy branch; call `imagePicker.launch` directly; remove `readPermissionRequester`, `READ_EXTERNAL_STORAGE` import (dead after) |
| `MumlaMessageNotification.java:88` O channel | `SDK_INT >= O` | Delete gate; always create the channel |
| `MumlaMessageNotification.java:113` TIRAMISU notify | `SDK_INT < TIRAMISU \|\|` | Drop the version clause; keep the grant check |
| `MumlaHotCorner.java:79` Q layout | `SDK_INT >= Q` | Delete gate; keep body |
| `MumlaHotCorner.java:95` O overlay type | ternary | Always `TYPE_APPLICATION_OVERLAY`; delete `TYPE_SYSTEM_ALERT` |
| `MumlaHotCorner.java:163` M overlay perm | `SDK_INT >= M &&` | Delete the version clause |
| `MumlaHotCorner.java:210,227` Q exclusion | `SDK_INT < Q \|\|` | Delete the version clause; keep `!mShown` |
| `MumlaOverlay.java:255` O overlay type | ternary | Always `TYPE_APPLICATION_OVERLAY` |
| `MumlaOverlay.java:289-291` R insets | `@TargetApi(R)` + gate | Remove annotation + gate; keep `getCurrentWindowMetrics` body |
| `MumlaConnectionNotification.java:339` O channel | `SDK_INT >= O &&` | Delete the version clause |
| `MumlaConnectionNotification.java:424` U foreground type | `UPSIDE_DOWN_CAKE` | **KEEP** — the sole surviving gate |
| `MumlaService.java:902` S grant helper | `SDK_INT < S` early-true | Delete early return; keep `checkSelfPermission` |
| `MumlaService.java:1062,1169` M overlay perm | `SDK_INT >= M &&` | Delete the version clause (both sites) |
| `PinnedTlsSocketFactory.java:62` N SNI | `SDK_INT >= N` | Delete gate; always set SNI |
| `ServerEditFragment.java:254` deprecated `getParcelable` | deprecation | Switch to typed `getParcelable` |

Untouched: `MumlaOverlay.java:306` (`legacy` is a resource comment, no
code), `MumlaImageGetter.java:393` (comment noting no gate),
`MumlaService.java:537` `setLegacyStreamType` (not a version gate),
`CertificateImportActivity.java:113` `BundleCompat` (compat already).

### Library (`:libraries:humla`)

| Site | Gate | Disposition |
|---|---|---|
| `AudioOutput.java:99` M builder | `SDK_INT >= M` + `else` | Delete `else` (legacy `AudioTrack` ctor); keep Builder path |
| `AudioOutput.java:119` O low-latency | `SDK_INT >= O` | Delete gate; always `setPerformanceMode` |
| `AudioOutput.java:251` M head query | ternary | Always `getBufferSizeInFrames()` |
| `AudioInput.java:96` M builder | `SDK_INT >= M` + `else` | Delete `else`; keep Builder path |
| `BluetoothScoManager.java:251` S dispatch | `>= S` / `else` | Always `bringUpModern`; delete `bringUpLegacy`, `isHeadsetConnected`, `isBluetoothScoOnLegacy`, `registerLegacyReceiver`, `mLegacyReceiver`, `onLegacyStateChanged`, legacy teardown block, legacy class-doc section. `REASON_NO_DEVICE` stays (modern path returns it) |
| `BluetoothScoManager.java:399,456` S queries | `>= S` / `else` | Keep modern branch only (both) |
| `BluetoothScoManager.java:660` S release | `>= S` / `else` | Always `clearCommunicationDevice()` |
| `BluetoothScoManager.java:676` S perm helper | `SDK_INT < S` early-true | Delete early return |
| `BluetoothScoManager.java:760` S listener removal | `!= null && >= S` | Drop the version clause |
| `HumlaService.java:497` M device callback | `SDK_INT >= M` | Delete gate; always register |
| `HumlaService.java:673` pre-S voice pre-hold | `SDK_INT < S && …` block | Delete the whole block (modern path needs no pre-hold) |
| `HumlaService.java:907` N network callback | `>= N` / `else` | Always `registerDefaultNetworkCallback` |
| `HumlaService.java:1165` M media query | `SDK_INT < M \|\|` | Delete the version clause; keep null check |
| `HumlaService.java:1371,1388` S SCO clear/query | `>= S` / `else` | Keep modern branch only (both) |
| `HumlaService.java:1434` pre-S pre-hold | `SDK_INT < S && …` block | Delete the whole block |

### Slice-1 verification

- `grep -rn "VERSION_CODES\.\(M\|N\|O\|Q\|R\|S\|S_V2\|TIRAMISU\)\b"` over
  main sources returns only the U site; `NewApi` lint clean.
- `./scripts/check.sh` green (JVM + native).
- Boot + connect + voice on the Pixel 4a (floor) and one modern device;
  SCO connect/disconnect, A2DP attach, overlay show/hide, notification
  post/dismiss.

## Slice 2 — Data layer (Java; Kotlin conversion comes in slices 3/5)

Room + DataStore + Hilt using Java-compatible APIs so the tree stays
green; no Kotlin plugin output changes behavior here.

- **Entities (7 tables in `MumlaSQLiteDatabase`) →**
  `server`, `favourites`, `tokens`, `comments`, `local_mute`,
  `local_ignore`, `certificates`. `Server` becomes `@Entity @Parcelize`
  later (slice 5); in this slice keep the Java class and map columns.
- **DAOs mirror `MumlaDatabase` (24 methods)** one-for-one; keep method
  names so call sites diff minimally. In-memory Room replaces
  `MumlaSQLiteDatabase` behind the `MumlaDatabase` interface.
- **DataStore (Preferences):** the 48 `Settings` accessors
  (`Settings.java`, getter/setter groups: input, PTT/hot-corner, certs,
  notifications/TTS, overlay, audio) become typed keys with defaults.
- **`ConfigDelta`:** replaces the pref→bundle→effect double dispatch
  (`MumlaService.onSharedPreferenceChanged` ~L774 + 
...[truncated 3703 chars]