# Bluetooth Toggle Unification Plan (Overflow Becomes a Settings Alias)

> **Status:** proposed.
> **Goal:** unify the two "Two-way Bluetooth" toggles so both exhibit the
> Settings > Audio behavior (requested-state toggle). No confirmed-link
> checkmark, no menu-only retry, no menu-only deferred-grant flow.

## Background

Mumla OLED currently ships two toggles bound to the same preference
(`bluetooth_headset` / `Settings.PREF_BLUETOOTH_HEADSET`):

| | Toggle S: Settings > Audio | Toggle M: channel overflow menu |
|---|---|---|
| Definition | `CheckBoxPreference` in [settings_audio.xml](../../app/src/main/res/xml/settings_audio.xml), beside `handset_mode` | Checkable item `menu_bluetooth_headset` in [channel_menu.xml](../../app/src/main/res/menu/channel_menu.xml) |
| Title/summary | `@string/bluetoothHeadset` / `@string/bluetoothHeadsetSum` in [preference.xml](../../app/src/main/res/values/preference.xml) | Same title string, no summary |
| Displayed state | **Requested** state: the persisted `Settings.isBluetoothHeadset()` value | **Confirmed** state: `IHumlaService.isBluetoothScoActive()` in [ChannelFragment.java](../../app/src/main/java/se/lublin/mumla/channel/ChannelFragment.java) (`onPrepareOptionsMenu`) |
| Write path | Preference framework writes `SharedPreferences` directly | `MumlaActivity.setBluetoothHeadset(boolean)` in [MumlaActivity.java](../../app/src/main/java/se/lublin/mumla/app/MumlaActivity.java): absolute assert with no-op early return |
| Permission (API 31+) | No pre-flight; [MumlaService.java](../../app/src/main/java/se/lublin/mumla/service/MumlaService.java) (`onSharedPreferenceChanged`) reverts to `false` + toast when `BLUETOOTH_CONNECT` is missing | Pre-flight: sets `mBluetoothMenuPendingPerm`, requests `BLUETOOTH_CONNECT`, persists only after grant; denial reverts with toast |
| Failed/connecting link | Stays checked (request persists); failure surfaces only via the `BluetoothScoManager` fallback toast | Shows unchecked while requested-but-not-up; tapping retries via `retryBluetoothSco()` or shows "connecting…" when bring-up is already running |
| Retry affordance | Flip off/on | One-tap explicit retry without flipping |

Both paths converge in the service: `MumlaService` forwards the preference as
`HumlaService.EXTRAS_BLUETOOTH_SCO`, and
[HumlaService.java](../../libraries/humla/src/main/java/se/lublin/humla/HumlaService.java)
(`configureExtras` → `mScoRequested` → `updateBluetoothScoRoute`) owns
bring-up/teardown. The connect-time bundle carries the same extra from
[ServerConnectTask.java](../../app/src/main/java/se/lublin/mumla/app/ServerConnectTask.java).
Volume keys, PTT cues, and the proximity sensor already follow **confirmed**
state (`useVoiceCallVolume`, `initSoundPool`, `shouldUseProximitySensor`,
`onBluetoothScoChanged`); that routing behavior is out of scope and does not
change here.

## Target behavior

"Settings behavior everywhere" means:

1. Both toggles display and flip the **requested** state
   (`Settings.isBluetoothHeadset()`). The overflow item becomes a thin alias
   of the Settings checkbox.
2. Both toggles share one write path: plain
   `Settings.setBluetoothHeadset(boolean)` (absolute assert, no-op early
   return). No menu-only deferred-grant, no menu-only retry branch.
3. Permission denial and missing-grant handling happen in one place — the
   existing service/activity revert-with-toast path — for both toggles.
4. Link failure is signaled only by the existing `BluetoothScoManager`
   failure/fallback toast, never by unchecking one toggle while the other
   stays checked. Retrying a failed link means flipping either toggle off/on
   (same as Settings today).
5. Route-dependent audio (volume stream, cues, proximity, pipeline reload on
   confirmed route) keeps following `isBluetoothScoActive()` / the
   `onBluetoothScoChanged` observer. Only the toggle checkmark changes meaning.

Explicit non-goals: no auto-routing on HFP connect (Option B in
[design-options.md](design-options.md)), no capture-source/AEC/VAD change
(OQ-2–OQ-4), no new status indicator or notification UI, no LE Audio work.

## Changes

### 1. `ChannelFragment.java` — requested-state checkmark, plain flip

- `onPrepareOptionsMenu`: `setChecked(...)` reads
  `Settings.getInstance(activity).isBluetoothHeadset()` instead of
  `getService().isBluetoothScoActive()`. Keep the existing visibility gate
  (visible only when the service is bound and connected).
- `onOptionsItemSelected` (`menu_bluetooth_headset`): replace the three-way
  branch (disable / `retryBluetoothSco()` / enable) with a single flip:
  `activity.setBluetoothHeadset(!Settings.getInstance(activity).isBluetoothHeadset())`.
  Delete the `retryBluetoothSco()` call site, the "connecting…" toast branch,
  and the now-unused `IHumlaService` retry import if applicable.
- `onSharedPreferenceChanged`: add `Settings.PREF_BLUETOOTH_HEADSET` to the
  observed keys and call `requireActivity().supportInvalidateOptionsMenu()`
  (guarded by `isAdded()`), so a Settings flip refreshes the overflow
  checkmark without waiting for an SCO callback.

### 2. `MumlaActivity.java` — delete the menu-only grant flow

- `setBluetoothHeadset(boolean)`: remove the `BLUETOOTH_CONNECT` pre-flight
  (`mBluetoothMenuPendingPerm` + `requestPermissions`) so it writes the
  preference unconditionally (after the existing no-op early return). Both
  toggles then share the Settings write semantics.
- Delete `mBluetoothMenuPendingPerm`, `STATE_BT_MENU_PENDING` save/restore,
  and the menu-pending branches in `onRequestPermissionsResult`
  (`PERMISSIONS_REQUEST_BLUETOOTH_CONNECT` keeps only the connect-flow arm)
  and in the dismissed-dialog path.
- Keep untouched: the connect-time grant check in `connectToServerWithPerm`
  (revert + toast + `mPermBluetoothAsked` first-ask logic) and the
  `onSharedPreferenceChanged` volume-stream switch on
  `PREF_BLUETOOTH_HEADSET`. Those are part of the retained Settings behavior.
- Keep `onBluetoothScoChanged` volume-stream switching. Its
  `supportInvalidateOptionsMenu()` call becomes visibility-only (checked
  state no longer depends on SCO); keep it rather than micro-optimizing, so
  connect/disconnect still gates menu visibility.

### 3. `MumlaService.java` — unchanged (single permission gate)

- The `PREF_BLUETOOTH_HEADSET` case (revert + toast when the grant is
  missing, else forward `EXTRAS_BLUETOOTH_SCO` and refresh proximity/cues)
  becomes the one permission gate for both toggles. No code change expected;
  cover it with the test below if untested.

### 4. Service-layer API — no change

- `HumlaService.retryBluetoothSco()` / `IHumlaService.retryBluetoothSco()`
  and the internal `BluetoothScoManager` retry budget
  (`shouldRetryBringUp`, pinned by `BluetoothScoRetryPolicyTest`) stay as-is.
  Only the manual one-tap UI retry goes away; automatic bring-up retries are
  unaffected. Optionally mark the UI-facing retry as retained-for-tests if a
  lint pass flags it as newly unused.

### 5. Strings, menus, preferences — near-zero change

- No menu XML or `settings_audio.xml` edits. Both toggles keep the
  existing "Two-way Bluetooth" title and the Settings summary. The
  now-unused `bluetooth_sco_connecting` string was deleted from
  `strings.xml` as follow-up cleanup after the retry-toast removal.

## Permission flow before/after (API 31+)

Before:

- Settings flip without grant: persisted, then service reverts + toast.
- Menu tap without grant: nothing persisted, permission requested, persisted
  only on grant; denial shows toast without ever flipping.

After (both toggles):

- Flip without grant: persisted, then `MumlaService` reverts to `false` +
  toast (and the overflow checkmark follows via the new preference listener).
- Connect with the toggle on and no grant: existing
  `connectToServerWithPerm` first-ask / revert path, unchanged.

## Edge cases

| Case | Expected handling |
|---|---|
| No headset paired / SCO bring-up fails | Request stays on in both toggles; fallback toast fires; user retries by flipping off/on |
| Bring-up in flight, user taps menu | Simple flip to off (cancels request via `updateBluetoothScoRoute` → `stop()`); no "connecting…" toast |
| Permission denied (Settings or menu) | Service reverts pref to `false` + `grant_perm_bluetooth` toast; both toggles show off |
| Rotation during grant dialog | No menu-pending state to save/restore; connect-flow `mServerPendingPerm` path unchanged |
| Disconnected (menu hidden) | Settings toggle still flips the persisted request; takes effect on next connect via `ServerConnectTask` extras |
| Rapid off/on | Each write is an absolute assert through `configureExtras`; manager `start()`/`stop()` are idempotent |
| Upgrade with toggle previously on | Unchanged connect-time revert path |

## Test plan

- Update/extend unit coverage for the new invariant: overflow checked-state
  equals `Settings.isBluetoothHeadset()` regardless of `isBluetoothScoActive()`
  (requested vs confirmed decoupling). Existing
  `BluetoothScoRetryPolicyTest` (automatic-retry budget) must keep passing
  untouched.
- Manual matrix on a WBS-capable and a narrowband-only headset: flip each
  toggle on/off mid-call and confirm the other mirrors it; deny
  `BLUETOOTH_CONNECT` from each toggle and confirm revert + toast; fail
  bring-up (headset off) and confirm both toggles stay on with fallback
  toast; disconnect/reconnect with the toggle on.
- `./scripts/check.sh` is exempt for this docs-only plan; the implementing
  worktree must pass it before review per the repository guidelines.

## Risks and trade-offs

- **Loses at-a-glance link confirmation.** The overflow checkmark no longer
  tells the user the SCO link is actually up; that signal moves entirely to
  toasts and audible routing. Accepted: two toggles disagreeing was worse
  than one honest requested-state toggle.
- **Loses one-tap retry.** A failed-but-requested link now needs off/on
  instead of a single tap. Accepted: matches Settings today and removes the
  only behavioral difference between the toggles.
- **Revert-toast UX is harsher than pre-flight.** Denial flips the checkbox
  then flips it back with a toast, instead of never flipping. Accepted as the
  cost of a single shared path; a future pass could add pre-flight to both
  toggles symmetrically without re-splitting them.

## Rollout

1. Implement in a dedicated worktree (`./scripts/worktree.py add <branch>`)
   touching `ChannelFragment.java` and `MumlaActivity.java` only (plus
   tests); no service, pipeline, string, or manifest changes expected.
2. Verify with `./scripts/check.sh` in the worktree and the manual matrix above.
3. Leave merge, push, and cleanup to the user on review.
