# Broken Feature: Channel Search Provider Unregistered and Prone to Deadlock

**Status:** confirmed dead code / architecture bug  
**Severity:** medium-high (orphaned component, potential main thread deadlock)  
**Component:** `app` Android Integration / Search Provider  
**Files Affected:**
- [`AndroidManifest.xml`](../../app/src/main/AndroidManifest.xml)
- [`ChannelSearchProvider.java`](../../app/src/main/java/se/lublin/mumla/channel/ChannelSearchProvider.java)
- [`MumlaActivity.java`](../../app/src/main/java/se/lublin/mumla/app/MumlaActivity.java)
- [`searchable.xml`](../../app/src/main/res/xml/searchable.xml)
- [`build.gradle`](../../app/build.gradle)

---

## 1. Problem Description

Mumla includes an Android `SearchRecentSuggestionsProvider` / `ContentProvider` class named `ChannelSearchProvider` designed to provide search suggestions for channels and users. This entire subsystem is completely broken and disconnected:

1. **Missing Manifest Declaration:**
   `res/xml/searchable.xml` specifies:
   ```xml
   android:searchSuggestAuthority="@string/search_suggest_authority"
   ```
   where `app/build.gradle` defines `@string/search_suggest_authority` as `${variant.applicationId}.channel.ChannelSearchProvider`.
   However, **there is no `<provider>` element in `AndroidManifest.xml`**. When the Android system search tries to query suggestions, it fails to find any ContentProvider registered for that authority.
2. **Main-Thread Deadlock / 5-Second ANR Stall:**
   In `ChannelSearchProvider.java` (lines 95–112):
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
   If `query()` is invoked on the main looper thread, `mServiceLock.wait(5000)` freezes the thread. Because Android delivers `onServiceConnected()` via the main thread looper, the service connection callback cannot execute while the main thread is waiting. It unconditionally times out after 5 seconds with a log error and returns `null`.
3. **Unhandled Search Action in `MumlaActivity`:**
   `AndroidManifest.xml` declares an intent-filter for `android.intent.action.SEARCH` on `MumlaActivity`. However, `MumlaActivity.java` contains no code in `onCreate` or `onNewIntent` to handle `ACTION_SEARCH`, nor does the UI have a search action or search view.

---

## 2. Technical Root Cause

`ChannelSearchProvider` was ported from early Mumble/Plumble code for Android's legacy global search interface. Over years of refactoring:
- The `<provider>` entry was lost from `AndroidManifest.xml`.
- The synchronous IPC binding kludge in `query()` was never refactored.
- The UI search trigger was abandoned.

---

## 3. Remediation Plan

There are two viable paths:

### Option A: Complete Removal (Recommended)
Following Mumla's recent refactoring pattern (such as removing legacy Bluetooth SCO, Orbot, and Echo Cancellation):
- Delete `ChannelSearchProvider.java`.
- Remove `searchable.xml` and `search_suggest_authority` (`app/build.gradle:124`) from `build.gradle`.
- Remove the unused `android.intent.action.SEARCH` intent-filter and `android.app.searchable` meta-data from `AndroidManifest.xml`.

---

## 4. Re-verification (2026-10-01)

Still fully valid; §1 above is accurate against the current tree:

- No `<provider>` element in `AndroidManifest.xml` (grep confirms); the `SEARCH` intent-filter and `android.app.searchable` meta on `MumlaActivity` are still present but unresolvable.
- `ChannelSearchProvider.java:95-112` still does `bindService()` + `mServiceLock.wait(5000)`; `onServiceConnected()` arrives on the main looper, so a main-thread `query()` deadlocks itself for the full 5 seconds and returns `null`. `query()` also concatenates `selectionArgs` without a null-check.
- `MumlaActivity.onCreate`/`onNewIntent` only handle `mumble://` VIEW intents (`handleViewIntent`); there is no `ACTION_SEARCH` branch, no `SearchView`, and no menu entry anywhere.

Reviving this means designing search UX from scratch plus rewriting the IPC (singleton service handle or async loader, `exported=false` provider). An in-app `SearchView` filtering the existing channel/user tree is strictly better than a `ContentProvider` if search is ever wanted.

### Option B: Fix and Integrate Search
- Add `<provider android:name=".channel.ChannelSearchProvider" android:authorities="@string/search_suggest_authority" android:exported="false" />` to `AndroidManifest.xml`.
- Remove synchronous blocking from `query()` (access existing singleton/bound service reference or use an asynchronous loader).
- Implement a search action and query handling in `MumlaActivity` and `ChannelListFragment`.
