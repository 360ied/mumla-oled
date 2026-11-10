# App UI debt — `app/` of Mumla OLED

Scope: `app/src/main/java/se/lublin/mumla/{app,channel,servers,service,preference,util}`
plus `app/build.gradle` plus `app/src/main/res/layout/*`, read-only on
`master`. All P0/P1 claims below re-verified `VALID` except two `PARTIAL`
count/citation notes (see [06-validity-check.md](06-validity-check.md)).
`app/build.gradle` itself is healthy (`compileSdk`/`targetSdk 36`,
`minSdk 30`, lint `abortOnError=true`).

## P0

| Location | Issue | Effort | Remediation |
|---|---|---|---|
| [`ServerConnectTask.kt`](../../app/src/main/java/se/lublin/mumla/app/ServerConnectTask.kt) (`AsyncTask` import/class) | `AsyncTask` deprecated since API 30; confusing serial/parallel semantics; no cancellation on destroy. Mitigated only by `applicationContext`. | S | Replace with `lifecycleScope.launch(Dispatchers.IO)` + `startService`, or a suspend function. Delete the class. |
| [`MumlaCertificateGenerateTask.kt`](../../app/src/main/java/se/lublin/mumla/preference/MumlaCertificateGenerateTask.kt) (class + retained `mContext`) | Same deprecated API **plus retained `Activity` context** (raw `context`, not `applicationContext`) held across rotation; dialog shown from a dead instance. `onPreExecute`/`onPostExecute` guards do not erase retention. Admitted race comment in `CertificateGenerateActivity`. | S | Coroutine + `ViewModel`/retained worker; pass `applicationContext` for DB work; show dialogs only via the current activity. |
| [`ServerInfoTask.kt`](../../app/src/main/java/se/lublin/mumla/servers/ServerInfoTask.kt) + `ServerAdapter.kt` (`MAX_ACTIVE_PINGS = 50`, `executeOnExecutor`) | Deprecated API driving per-row UDP pings with a 50-thread pool per adapter instance. `FavouriteServerListFragment.updateServers()` rebuilds the adapter every `onResume`; old pool is shut down today but the pattern is fragile. | M | Coroutine `IO` dispatcher + bounded semaphore (4–8), `RecyclerView` + `DiffUtil`, cache responses in a `ViewModel`. |
| [`DataStorePreferenceDataStore.kt`](../../app/src/main/java/se/lublin/mumla/preference/DataStorePreferenceDataStore.kt) (`runBlocking(Dispatchers.IO)` read/write) | Synchronous DataStore on the UI thread: every `Preference` bind/get/put blocks main. Class KDoc documents this as an interim stance. ANR risk under disk pressure; defeats async DataStore design. | M | Make a settings repository the single source exposing `Flow`; collect with `lifecycleScope` or migrate to an async preference store. |

## P1

| Location | Issue | Effort | Remediation |
|---|---|---|---|
| [`ChannelFragment.kt`](../../app/src/main/java/se/lublin/mumla/channel/ChannelFragment.kt) (`FragmentPagerAdapter`, `onActivityCreated`, `setHasOptionsMenu`, plain `commit()`) | Three deprecated fragment APIs in one file. `FragmentPagerAdapter` keeps all pages resident; tablet branch commits without a state-loss guard. | M | `ViewPager2` + `FragmentStateAdapter`; move init to `onViewCreated`; use `MenuProvider` with `viewLifecycleOwner`. |
| `ChannelListFragment.kt`, `ChannelChatFragment.kt`, `FavouriteServerListFragment.kt` (`setHasOptionsMenu`, `registerForContextMenu`, `GridView`) | Deprecated options/context-menu plumbing across three fragments; `GridView` is itself legacy. | M | Per-fragment `MenuProvider`; `RecyclerView` + `PopupMenu` (already used in `ServerAdapter`). |
| [`MumlaPreferenceFragment.kt`](../../app/src/main/java/se/lublin/mumla/preference/MumlaPreferenceFragment.kt) (`setTargetFragment`) | Deprecated target-fragment plumbing; fragile lookup vs `FragmentResultListener` (which `SettingsActivity` already uses). | S | `setFragmentResultListener` / `parentFragmentManager.setFragmentResult`. |
| [`CertificateImportActivity.kt`](../../app/src/main/java/se/lublin/mumla/preference/CertificateImportActivity.kt) (`startActivityForResult`/`onActivityResult`) | Deprecated activity-result API. Replacement already in-tree (`ChannelChatFragment` `GetContent` launcher). | S | `registerForActivityResult(GetContent()/OpenDocument)` + launcher. |
| [`ServerInfoFragment.kt`](../../app/src/main/java/se/lublin/mumla/channel/ServerInfoFragment.kt) (1 s poll via `ScheduledExecutor` + `Handler`) | Wasteful wakeups bridging to potentially detached views; teardown split across three callbacks. File comment already proposes a service `StateFlow`. | S | `repeatOnLifecycle(STARTED)` collecting a flow from the service. |
| [`MumlaActivity.kt`](../../app/src/main/java/se/lublin/mumla/app/MumlaActivity.kt) (raw `Thread("crash-check")` + `Handler.postDelayed`) | Crash-prompt uses manual threads plus a generation counter to avoid post-rotation leaks. Correct but brittle. | S | `lifecycleScope.launch { delay(...); ... }` with `repeatOnLifecycle(RESUMED)`. |
| [`MumbleImageGetter.kt`](../../app/src/main/java/se/lublin/mumla/util/MumbleImageGetter.kt) (manual 2-thread pool + `Handler`) | Ad-hoc image loader; leak if `shutdown()` is ever missed by a new caller; ad-hoc coalescing. | S | Coil/Glide, or `CoroutineScope(IO)` with `viewLifecycleOwner` cancellation. |
| [`CrashReportsFragment.kt`](../../app/src/main/java/se/lublin/mumla/preference/CrashReportsFragment.kt) (15 s main-thread freeze test) | Deliberate ANR test gated only by connection state; no dev-build guard. Accidental tap freezes release UI. | S | Gate on `BuildConfig.DEBUG` or hide behind a dev menu. |
| `ChannelListAdapter.kt` vs `ChannelAdapter.kt` (`getTalkStateDrawable` duplication) | Same ~7-branch talk-state chain copied in both adapters plus an open whisper TODO. Drift risk (null-guard/avatar fallback differ). | S | Single `TalkStateIconMapper` in `channel/` plus unit test; both adapters delegate. |
| Tint snippet ×3 (`ChannelListFragment`, `ChannelChatFragment`, `DrawerAdapter`, with `TODO move this elsewhere`) | Identical theme-tint snippet copied across files. A fourth cite against `ChannelFragment` did not verify — 3 sites suffice for the finding. | S | One `MenuTintHelper` or `android:tint` / `ImageViewCompat` tint list. |
| Volume routing (`useVoiceCallVolume()` in `MumlaActivity`) | Same `STREAM_VOICE_CALL` vs `STREAM_MUSIC` branch in 3+ call sites. Count of 4 unverified; duplication of 3+ confirmed. | S | One `AudioRouteHelper.applyVolumeStream` call site. |
| `res/layout/*` (unlabeled icons) | Roughly a dozen `ImageView`/`ImageButton` without `contentDescription` (mute/deafen/more/join/send); verified pattern with positive control present elsewhere. TalkBack announces unlabeled controls. | S | Add `android:contentDescription` per interactive icon; enable lint `ContentDescription`. |

## P2/P3 (selected)

- Preview placeholders baked into `android:text` (`channel_row.xml`,
  `channel_user_row.xml`, `fragment_server_info.xml`, `fragment_channel.xml`)
  should be `tools:text`. Effort S.
- `ChannelListAdapter.updateChannels()` full-tree rebuild + `notifyDataSetChanged()`
  on every expand (`FIXME: very inefficient`); double-press `ActionMode` hack.
  Use `DiffUtil`/`ListAdapter` and selection tracking. Effort M.
- 10+ open TODO/FIXME markers with no tracker link. Triage into issues. S.
- `AccessTokenFragment` token adapter captures stale `position`; no stable IDs.
  Use `RecyclerView` + `bindingAdapterPosition`. S.
- Host-contract `onAttach` casts (`ClassCastException` with generic message);
  copy the `HumlaServiceFragment` fallback pattern. S.
- Overlay (`MumlaOverlay`, `MumlaHotCorner`, `OverlayLayout`): permission-revoke
  path and `ACTION_DOWN` consumption need drag-gating and re-checks. M.
- `DialogUtils.NEWS_ITEMS` frozen at `3.7.x`; add a test that the map head
  tracks the version tag. S.
- `DrawerAdapter` pre-`RecyclerView` idioms; `GeneralSettingsFragment` TTS query
  on main. M/S.
