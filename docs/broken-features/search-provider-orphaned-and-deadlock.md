# Broken Feature: Channel Search Provider Blocking IPC and Unhandled Free-Text Search

**Status:** confirmed defects in a working feature (prior revisions wrongly described it as orphaned dead code — corrected 2026-10-01)  
**Severity:** medium (toolbar suggestion search works; Binder-thread stall, missing null-guards, and binding-leak defects, plus dead free-text submit)  
**Component:** `app` Android Integration / Search Provider  
**Files Affected:**
- [`AndroidManifest.xml`](../../app/src/main/AndroidManifest.xml) (SEARCH intent-filter, `android.app.searchable` meta)
- [`AndroidManifest.xml`](../../app/src/foss/AndroidManifest.xml) (flavor overlay: the actual `<provider>` registration)
- [`ChannelSearchProvider.java`](../../app/src/main/java/se/lublin/mumla/channel/ChannelSearchProvider.java)
- [`ChannelListFragment.java`](../../app/src/main/java/se/lublin/mumla/channel/ChannelListFragment.java) (working toolbar `SearchView` + suggestion-click handler)
- [`MumlaActivity.java`](../../app/src/main/java/se/lublin/mumla/app/MumlaActivity.java) (no `ACTION_SEARCH` handling)
- [`searchable.xml`](../../app/src/main/res/xml/searchable.xml) and [`searchable.xml`](../../app/src/foss/res/xml/searchable.xml) (flavor copy wins the resource merge in FOSS builds)
- [`fragment_channel_list.xml`](../../app/src/main/res/menu/fragment_channel_list.xml) (`menu_search` item)
- [`build.gradle`](../../app/build.gradle)

---

## 1. Problem Description

Mumla OLED includes a `ContentProvider` class named `ChannelSearchProvider` that provides channel/user search suggestions to a toolbar `SearchView`. Contrary to prior revisions of this report, the subsystem is registered and partially working — it is neither orphaned nor dead. It has real but narrower defects:

1. **Provider IS Registered (Prior Claim Was Wrong):**
   `res/xml/searchable.xml` specifies:
   ```xml
   android:searchSuggestAuthority="@string/search_suggest_authority"
   ```
   where `app/build.gradle` defines `@string/search_suggest_authority` as `${variant.applicationId}.channel.ChannelSearchProvider`.
   Prior revisions claimed there is no `<provider>` element because they only checked `app/src/main/AndroidManifest.xml`. The registration lives in the flavor overlay `app/src/foss/AndroidManifest.xml` (present since the foss/goog flavor rework, authority made dynamic per-`applicationId` later), which the manifest merger includes in every FOSS build — the only flavor Mumla OLED ships. Suggestion queries therefore resolve to the provider normally.
2. **Blocking IPC on a Binder Thread (Not a Main-Thread Deadlock):**
   In `ChannelSearchProvider.java` (`query()`, `bindService` at line 98):
   ```java
   if(mService == null) {
       Intent serviceIntent = new Intent(getContext(), MumlaService.class);
       getContext().bindService(serviceIntent, mConn, 0);

       synchronized (mServiceLock) {
           try {
               mServiceLock.wait(5000);
           } catch (InterruptedException e) {
               e.printStackTrace();
           }
           if(mService == null) {
               Log.v(TAG, "Failed to connect to service from search provider!");
               return null;
           }
       }
   }
   ```
   Prior revisions claimed this deadlocks the main thread for 5 seconds. That analysis is wrong: the framework dispatches `ContentProvider.query()` on a **Binder thread-pool thread**, never the main looper. `onServiceConnected()` posts to the main looper, which is free, so `notify()` wakes the waiting Binder thread normally — no ANR. The real defects in this block:
   - First query after provider creation stalls a Binder thread up to 5 seconds (Binder-pool pressure, not an ANR); subsequent queries use the cached `mService` with no wait.
   - `bindService(..., 0)` passes no `BIND_AUTO_CREATE`, so if `MumlaService` is not already running, `onServiceConnected()` never fires and every first query eats the full 5-second stall, then returns `null`.
   - The `ServiceConnection` is never unbound (leak).
   - `mService` is non-volatile yet written on the main thread and read on Binder threads (race).
   - `selectionArgs` is concatenated with no null-check (NPE on a null-args query).
   - Result rows reuse `_ID` values across the channel and user loops (duplicate row IDs).
3. **Free-Text Submit Goes Nowhere (But Suggestion Taps Work):**
   `AndroidManifest.xml` declares an intent-filter for `android.intent.action.SEARCH` on `MumlaActivity`, but `MumlaActivity.java` has no `ACTION_SEARCH` branch in `onCreate`/`onNewIntent` (`handleViewIntent` only handles `mumble://` VIEW intents) — pressing Enter on typed text does nothing. Prior revisions further claimed the UI has no search action or search view; that is wrong. `ChannelListFragment.onCreateOptionsMenu` inflates `menu_search` from `fragment_channel_list.xml`, binds it via `setSearchableInfo`, and handles suggestion taps through `OnSuggestionListener.onSuggestionClick` (join channel / scroll to channel / scroll to user). The suggestion-tap path bypasses `ACTION_SEARCH` entirely, so toolbar search visibly works despite the dead submit path.

---

## 2. Technical Root Cause

`ChannelSearchProvider` was ported from early Mumble/Plumble code for Android's legacy search interface. The synchronous IPC binding kludge in `query()` was never refactored, and free-text submit handling was never implemented — but flavor-overlay registration and the `ChannelListFragment` suggestion UI kept the core tap-to-navigate flow working.

---

## 3. Remediation Plan

There are two viable paths:

### Option A: Harden and Keep (Recommended)
The toolbar suggestion search works today, so removal would destroy a shipped user-facing feature. The fix is small (~30 lines, Java only, no JNI):
- Null-guard `selectionArgs` in `query()` and assign unique `_ID`s across channel/user rows.
- Pass `BIND_AUTO_CREATE` (or fail fast when the service is not running) instead of the unconditional 5-second wait; make `mService` volatile and `unbindService()` once connected.
- Handle `ACTION_SEARCH` in `MumlaActivity` (e.g. route free-text submit to the same join/scroll logic as suggestion taps) or drop the `SEARCH` intent-filter so Enter does not silently swallow input.

### Option B: Complete Removal (Destroys Working Search)
Only if product decides toolbar search is unwanted. Note this is far more invasive than previously described because the UI is live:
- Delete `ChannelSearchProvider.java`.
- Remove both `searchable.xml` copies (`main` and `foss`) and `search_suggest_authority` (`app/build.gradle:124`).
- Remove the `<provider>` from `app/src/foss/AndroidManifest.xml` plus the `android.intent.action.SEARCH` intent-filter and `android.app.searchable` meta-data from `app/src/main/AndroidManifest.xml`.
- Remove the `SearchView` wiring in `ChannelListFragment.onCreateOptionsMenu`/`onOptionsItemSelected`, the `menu_search` item, and the now-unused `search`/`searchHint`/`search_channel_users` resources (including `fr`/`zh-rCN` translations).

---

## 4. Correction (2026-10-01)

The 2026-10-01 re-verification below was inaccurate: it grepped only `app/src/main/AndroidManifest.xml` and missed the flavor overlay, and it never checked `ChannelListFragment` for search UI. Corrected findings against the current tree:

- `<provider android:name="se.lublin.mumla.channel.ChannelSearchProvider" ... android:exported="false" />` **is** registered in `app/src/foss/AndroidManifest.xml`; merged into every FOSS build. The `SEARCH` intent-filter and `android.app.searchable` meta on `MumlaActivity` therefore resolve.
- `ChannelListFragment:247` binds a toolbar `SearchView` (`menu_search`, `fragment_channel_list.xml:22`) via `setSearchableInfo`, and `:269-281` handles suggestion taps (join/scroll to channel, scroll to user). Only free-text `ACTION_SEARCH` submit is unhandled (`MumlaActivity` has no branch for it).
- `query()` runs on a Binder thread, not the main looper, so the claimed 5-second main-thread deadlock cannot occur; the costs are a Binder-thread stall on first query, an un-unbound `ServiceConnection`, a missing `selectionArgs` null-check, a racy `mService` field, and duplicate `_ID`s.
- Recommendation flipped: hardening the provider (Option A) is now recommended over removal, since removal deletes working toolbar search. An in-app `SearchView` filtering the channel/user tree directly remains a possible future replacement, but it is not needed to fix this issue.

### Prior (superseded) re-verification text

Still accurate that `ChannelSearchProvider.java:95-112` does `bindService()` + `mServiceLock.wait(5000)` (superseded analysis: Binder thread, not main thread — see §4 Correction). `MumlaActivity.onCreate`/`onNewIntent` only handle `mumble://` VIEW intents (`handleViewIntent`); there is no `ACTION_SEARCH` branch (superseded scope note: suggestion taps work via `ChannelListFragment`, so only free-text submit is dead — see §4 Correction).

### Option B (original): Fix and Integrate Search (now Option A above)
- ~~Add `<provider android:name=".channel.ChannelSearchProvider" android:authorities="@string/search_suggest_authority" android:exported="false" />` to `AndroidManifest.xml`.~~ Already registered via the foss overlay — no manifest addition needed.
- Remove synchronous blocking from `query()` (access existing singleton/bound service reference or use an asynchronous loader).
- Implement ~~a search action and~~ query handling in `MumlaActivity` ~~and `ChannelListFragment`~~ (free-text `ACTION_SEARCH` submit; the `ChannelListFragment` suggestion UI already exists).
