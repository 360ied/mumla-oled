# Bug: the in-app "What's New" dialog never shows

**Status:** open, unfixed
**Severity:** low (cosmetic/UX; no data loss, no crash)
**Found while:** investigating the static `versionCode` in
[`app/build.gradle`](../../app/build.gradle) (unrelated — `versionCode` is
intentionally out of scope here)
**Recorded against:** `455d81f8`

## Summary

`DialogUtils.maybeShowNewsDialog()` is invoked on every startup, but it always
returns early with an empty list of news items. The startup "What's New" dialog
therefore never appears, for any version, ever.

Root cause: `NEWS_ITEMS` is still keyed by legacy upstream version numbers
(`3.7.0`–`3.7.3`), while `BuildConfig.VERSIONTAG` is now a fork version
(`0.27.x`). The gate loop in `RELEVANT_VERSIONS` breaks out on the *first* key
because `3.7.0 > 0.27.3`, producing an empty list every time.

## The code

[`app/src/main/java/se/lublin/mumla/app/DialogUtils.kt`](../../app/src/main/java/se/lublin/mumla/app/DialogUtils.kt):

```kotlin
private val NEWS_ITEMS: Map<String, Int> = linkedMapOf(
    // These *must* be added in ascending semver order (1.2.3, 1.10.1, ...)
    "3.7.0" to R.string.app_news_items_v3_7_0,
    "3.7.1" to R.string.app_news_items_v3_7_1,
    "3.7.2" to R.string.app_news_items_v3_7_2,
    "3.7.3" to R.string.app_news_items_v3_7_3
)
private val RELEVANT_VERSIONS: List<String> = buildList {
    for (candidate in NEWS_ITEMS.keys) {
        // Relevant as long as candidate-version <= build-version; otherwise break
        if (Version(candidate).compareTo(Version(BuildConfig.VERSIONTAG)) > 0) {
            break
        }
        add(candidate)
    }
}
```

`BuildConfig.VERSIONTAG` comes from
[`app/build.gradle`](../../app/build.gradle):

```groovy
buildConfigField "String", "VERSIONTAG", "\"" + gitDescribe.split("-")[0] + "\""
```

so for the current release line it is `"0.27.3"` (leading `v` stripped,
`-NN-gHASH` suffix discarded).

`Version` is a private nested class in the same file that parses
`major.minor.patch` and compares numerically. `Version("3.7.0").compareTo(
Version("0.27.3")) > 0` is `true`, so the `for` loop breaks before adding
anything.

## Trace

| Step | Value |
| --- | --- |
| `NEWS_ITEMS` first key | `"3.7.0"` |
| `BuildConfig.VERSIONTAG` (latest tag `v0.27.3`) | `"0.27.3"` |
| `Version("3.7.0") > Version("0.27.3")`? | yes → `break` |
| `RELEVANT_VERSIONS` | `[]` |
| `maybeShowNewsDialog()` | `toShow` empty → `return false`, no dialog |

`maybeShowNewsDialog()` is called from
[`app/src/foss/java/se/lublin/mumla/app/StartupAction.kt`](../../app/src/foss/java/se/lublin/mumla/app/StartupAction.kt)
on app start, so nothing happens there. `Settings.newsShownVersions` is
consequently never populated by the startup path (it is only ever *reset* by the
About screen, see below).

## Why this is not visible as a regression

Both halves of the feature arrived from upstream in one commit
(`062f5cb1`, "Add app news function; refactor and clean-up", Dec 2025) with
`3.7.x` keys that matched upstream's `3.7.x` tags. This fork has *never* had a
`3.x` tag — the earliest tags are `0.0.1`/`0.1.x`, and at the commit that
introduced the feature `git describe --tags` resolves to a bare hash (i.e.
`VERSIONTAG` parses to `0.0.0`, which also breaks out on the first key). So the
dialog has most likely never fired in this repository at all; nobody noticed
because it silently shows nothing rather than erroring.

Note that `Version("3.7.0")` vs `Version("0.27.3")` is not a naming-scheme
quibble that a rename of the strings would fix in place: the *ordering*
semantics changed too. Under the legacy scheme, higher major = newer build;
under the fork's `0.X.Y` scheme, `0.27.3` is newer than `0.21.0` but numerically
smaller than `3.7.3`.

## Adjacent dead code (found while tracing, same feature)

1. **`showAllNewsDialog()` has no callers.** It is the "show all news items
   unconditionally" entry point (`markShown = false`) and nothing in the app
   references it.

2. **The About screen's version preference clears the news history instead of
   showing the news.** In
   [`app/src/main/java/se/lublin/mumla/preference/AboutSettingsFragment.kt`](../../app/src/main/java/se/lublin/mumla/preference/AboutSettingsFragment.kt)
   the `version` preference's click listener calls
   `Settings.resetNewsShownVersion()` and nothing else. Upstream paired that
   reset with a toast ("Latest update news will be shown again on app startup"),
   and upstream additionally had a separate `showNews` preference wired to
   `showNewsDialog()`. The fork's commit `9e3e73f8` ("ui: update About screen
   attribution and repo links") removed the `showNews` preference from
   [`app/src/main/res/xml/settings_about.xml`](../../app/src/main/res/xml/settings_about.xml)
   while leaving the reset-on-click listener behind. Net effect today: tapping
   the version row silently clears a preference set that the startup gate
   ignores anyway.

3. **The in-app news list is not what the About screen links to.** The "What's
   New" row in `settings_about.xml` (`@string/app_news_summary`, "View the
   latest updates") opens `@string/releases_link` in a browser, so GitHub
   release notes are the *de facto* news channel; the in-app version is orphaned
   duplication.

4. **Only two locales have news strings.** `app_news_items_v3_7_0`–`_3_7_3` and
   `app_news_footer_on_startup` exist only in
   [`app/src/main/res/values/strings.xml`](../../app/src/main/res/values/strings.xml)
   and `values-zh-rCN/strings.xml`. `resourceConfigurations` is limited to
   `en`, `fr`, `zh-rCN` in
   [`app/build.gradle`](../../app/build.gradle), so `fr` has no news text.

## Fix options

Listed cheapest-first. None of these is urgent.

### A. Delete the feature

Remove `DialogUtils`'s news half, the `NEWS_ITEMS`/`RELEVANT_VERSIONS`/`Version`
machinery, `Settings.newsShownVersions` + `resetNewsShownVersion()`, the
`StartupAction` call, the `app_news_items_v3_7_*` strings, and the click listener
in `AboutSettingsFragment`. Keeps GitHub releases as the single news channel.
Cheapest, and honest about what actually exists — but loses the feature
entirely, including the `newsShownVersions` pref key (harmless: it is stored
data, not a schema; a stale key in `SharedPreferences`/DataStore just becomes
inert).

### B. Re-key `NEWS_ITEMS` to fork versions and keep the gate

Add entries with `0.x.y` keys (e.g. `"0.27.3"`) and drop the `3.7.x` keys, then
keep appending a key + `app_news_items_vX_Y_Z` string per release. This is what
the surrounding comment ("These *must* be added in ascending semver order")
already presumes. Requires a per-release manual step, which is exactly the kind
of step that rots — worth pairing with a check.

Guard worth adding with either A-adjacent or B: assert the gate is non-empty for
tagged builds, or make the gate a pure function so it is unit-testable
(`Version` and `RELEVANT_VERSIONS` are currently private, so no existing test can
cover them; `app/src/test` has no `DialogUtils` test).

### C. Derive the gate from `BuildConfig.VERSION_CODE`/a monotonic counter

Not recommended: `versionCode` is frozen at `3070300` for all fork releases
(see the `versionCode` discussion — deliberately not being changed), so it is
useless as an ordering signal today.

### D. Keep the dialog but bound it by "commits since last seen"

Overkill for the value; the news text would need to be authored per commit.

## Verification notes for whoever fixes it

- The gate is order-dependent (`break`, not `filter`): re-keying with mixed
  `3.7.x` and `0.x` keys will silently truncate the list at the first
  out-of-order key. Sort or assert ascending order in a unit test.
- `Version` swallows `NumberFormatException` and falls back to `0.0.0`, so a
  malformed `VERSIONTAG` fails *closed* (empty list). Do not "fix" this by
  removing the try/catch.
- `Settings.addNewsShownVersions()` takes `List<String?>` and copies before
  mutating; `newsShownVersions` is a `Set<String>` snapshot — a fix must not
  mutate the stored set in place (see
  [`app/src/main/java/se/lublin/mumla/Settings.kt`](../../app/src/main/java/se/lublin/mumla/Settings.kt)).
- Reproduction is by inspection: the dialog is unreachable, so there is no
  runtime state to observe. To confirm the mechanism, temporarily log
  `RELEVANT_VERSIONS.size` — it is `0`.
