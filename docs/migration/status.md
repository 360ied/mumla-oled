# Migration Status Log

Progress record for the big-bang modernization on branch `migration/full-modern`
(worktree `.worktrees/migration/full-modern`; root repository stays on `master`).
Detail (inventories, pins, verification) lives in [slices](slices.md) and the
[porting guide](porting-guide.md); this file tracks what landed, what was
decided, and what is still owed.

## Slice 0 — Scaffold (DONE 2026-10-12, `check.sh` green)

- `4071b2db` — version catalog + plugin wiring: `gradle/libs.versions.toml`
  (single source of truth), both modules migrated from `buildscript` to catalog
  `plugins {}` aliases, Kotlin/kapt/parcelize + Hilt applied with scaffold-only
  Hilt deps (no consumers yet), minSdk stays 21.
- `db6764e8` — AGP 9 toolchain adoption (cherry-picked from `spike/agp9`,
  reworded): wrapper 9.1.0, AGP 9.0.1, Hilt 2.60.1, `resValues = true` for the
  AGP 9 default flip, `flake.nix` Build-Tools 35.0.0 → 36.0.0 (AGP 9 minimum),
  AGP 9 opt-outs (`newDsl=false`, `builtInKotlin=false`). Verified by `check.sh`
  green on the migration branch.

Final slice-0 pins: AGP `9.0.1`, Gradle `9.1.0`, Kotlin `2.4.21` (kapt + TODO —
no stable KSP 2.4 on Central), Hilt `2.60.1`, coroutines `1.11.0`, Compose BOM
`2026.09.00`, navigation-compose `2.10.2`, activity-compose `1.13.0`,
hilt-navigation-compose `1.4.0`, lifecycle `2.11.0`, androidx-hilt `1.4.0`,
Room `2.8.5`, datastore-preferences `1.2.1`, Paparazzi `2.0.0-alpha05.1`
(pinned, NOT applied — deferred, see below).

## Slice 1 — minSdk 33 + legacy deletion (DONE 2026-10-12, `check.sh` green)

- `7f876cfc` (+143/−470) — minSdk 21 → 33 both modules; deleted every gate
  below 33 except `UPSIDE_DOWN_CAKE`: the SCO legacy duality (`bringUpLegacy`,
  broadcast receiver, `isHeadsetConnected`, both pre-S voice pre-holds), pre-M
  audio constructors, the `ChannelChatFragment` storage branch + permission
  machinery, deprecated `getParcelable` overloads, always-true branches, and
  orphaned imports. Gate-grep returns only the U site; assemble +
  `lintFossDebug` + `check.sh` green.
- `8abec58b` — on-device result: fossDebug installed on hardware, basic
  functionality (boot/connect/voice) confirmed by the user. Deeper torture
  (SCO flap, A2DP stagger, overlay drag/rotate, notification paths) still open.

## Fork outcomes (all bisected 2026-10-12)

- **Kotlin 2.4.21 stands.** A `MergeResources` failure initially suspected KGP
  but bisected cleanly to Paparazzi; 2.4.21 + kapt proven green.
- **Hilt capped by AGP, then uncapped.** Hilt ≥ 2.59 requires AGP 9, so the
  scaffold first pinned 2.58; the toolchain adoption restored 2.60.1.
- **Paparazzi 2.0.0-alpha05.1 disqualified on BOTH toolchains, deferred to
  slice 6.** On AGP 8 it breaks resource merging build-wide
  (`MergedResourceWriterRequest` NoSuchMethodError when applied anywhere); on
  AGP 9 it configures fine but ships `android-all` classes that shadow the
  mockable `android.jar`, failing 7 stub-dependent tests (including the
  must-keep-unmodified `ChannelFragmentDensityTest`; 301/301 green with it
  off). The 1.3.x fallback is dead (layoutlib caps at compileSdk 35; project
  is on 36). Re-evaluate at slice 6 (stable 2.0.0 may behave; otherwise scope
  its classpath or gate dialogs on-device).
- **AGP 9 needs Build-Tools 36.0.0** (flake.nix updated in-branch; `master`
  still 35.0.0) and `resValues = true` (AGP 9 default flip; the app uses
  `resValue`).

## Spike record

- Branch `spike/agp9` (worktree `.worktrees/spike/agp9`), commit `ade4446e`:
  AGP 9.0.1 + Gradle 9.1.0 viability probe — `check.sh` green (301 app tests,
  24 humla suites, native suite) + `assembleFossDebug` APK. Kept as evidence;
  cleanup only on explicit request (never autonomous).

## Outstanding debt (do not forget)

- Full new-DSL / built-in-Kotlin migration (opt-outs are deprecated warnings).
- Release + R8 verification deferred to pre-landing (explicitly accepted).
- Paparazzi re-evaluation at slice 6 (above).
- Re-verify androidx-hilt 1.4.0 against Hilt 2.60 when adding
  `hilt-navigation-compose` in slice 7.
- kapt → KSP when a stable KSP for Kotlin 2.4 lands.

## Review

- Pedantic review fan-out (6 slices, `reviewer` agents) completed; synthesis
  concurred on two blockers (dual-bus split-brain, duplicate-listener leak)
  plus smaller defects (manifest minSdk, jvmTarget, `<9` migration path,
  `close()` scope, P1 overlay guard, stale docs/imports).
- `295754b6` fixes all concurred items: DataStore-backed `PreferenceDataStore`
  in the fragment base, both custom dialogs and all four listeners ported,
  `Migration(8, 10)`, listener-bridge unit test, toolchain/hygiene batch.
  Contested/downgraded (runBlocking interim, pre-existing nits) recorded as
  debt, not fixed. `check.sh` + assemble + lint green.

## Slice 2 — Data layer (DONE 2026-10-12, `check.sh` green)

- `0b44fc2a` — Room v10 (`MumlaRoomDatabase` behind `MumlaDatabase`, 7
  entities/DAOs, `MIGRATION_9_10` recreating all tables for validator-exact
  shapes), `MumlaSQLiteDatabase` deleted; `AppDataStore` + `SettingsRepository`
  (41-key snapshot, `SharedPreferencesMigration`, key-diff listener bridge);
  Hilt `@HiltAndroidApp` + `DatabaseModule` + `SettingsModule`.
- `2c464334` — `Settings` rewritten over the repository (surface kept, theme
  and language added); `ConfigDelta` replaces the pref→bundle→effect
  dispatch (`HumlaService.apply`, `MumlaService` delta dispatch + appliers,
  slim `ServerConnectTask` carrying only the server id); `MumlaApplication`
  reads theme and language from `Settings`. Tests ported (in-memory Room +
  v9→v10 migration test, DataStore doubles, fake deleted).
- All 17 `ConfigDelta` keys round-trip audited; comment seen-tracking now
  compares text (was blob-vs-text, never matched). Lint + assemble green.
- Owed: on-device connect/voice/reconnect-toast pass; R8/release still
  deferred to pre-landing.

## Next

Slice 3 — Kotlin conversion of `:libraries:humla`, per [slices](slices.md).
