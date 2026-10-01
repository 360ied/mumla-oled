# Phase 6 Implementation Plan: Comment Dialog Hardening + Robolectric Pilot (ODD-13 – ODD-16)

Concrete, commit-ready plan for [Phase 6](remediation-plan.md#phase-6-comment-dialog-hardening-follow-ups-p3) of the Miscellaneous Oddities remediation
([detailed records](README.md#odd-13-unguarded-getarguments-in-comment-dialog)).
This file locks the design decisions left open by the Phase 6 review so the implementing branch has no deliberation left to do.
Its distinctive feature versus earlier phases is a **one-time Robolectric pilot in `:app`**: the ODD-13/15 fixes are fragment-lifecycle
changes that the repo's JVM-only setup cannot exercise, so this branch introduces the minimal test infrastructure to cover them —
with an explicit revert rule if the pilot misbehaves.

**Status:** Implemented on branch `feature/oddities-phase6-comment-dialog` (commits `cc393d98` through `772f725a`); pending review and merge. Two pedantic review rounds expanded scope beyond the locked recipe to every adjacent incidental (see §2.5–§2.6 and §5 deltas).
**Scope:** Four items — ODD-13 through ODD-16 (all P3 / Low, latent or cosmetic; no live crash or leak) — plus shared `:app` Robolectric infrastructure. No behavior change except the guards described below.

---

## 1. Problem restatement

`AbstractCommentFragment` (`app/src/main/java/se/lublin/mumla/channel/comment/AbstractCommentFragment.java`) carries four residual
low-severity items from the phase2-comment-webview pedantic reviews:

- **ODD-13:** `onCreate()` and `isEditing()` dereference `getArguments()` unchecked. Both production callers (`UserMenu`, `ChannelMenu`)
  always supply a bundle, so only a no-args instantiation crashes (NPE). The subclasses repeat the pattern:
  `UserCommentFragment.getSession()` and `ChannelDescriptionFragment.getChannelId()` call `getArguments().getInt(...)` unchecked.
- **ODD-14:** overrides the deprecated `onAttach(Activity)` and rethrows `ClassCastException` as `RuntimeException` without chaining the cause.
- **ODD-15:** `onDestroyView()` destroys and nulls `mCommentView` but leaves `mTabHost`/`mCommentEdit` reachable; the tab listener
  dereferences `mCommentView` (and `mCommentEdit`) unguarded. The `TabHost` is detached with the hierarchy so the listener cannot fire
  post-teardown in practice — residual hardening, not a live bug.
- **ODD-16:** `comment_open_link` ("Open link") exists only in `values/strings.xml`; `values-fr` and `values-zh-rCN` fall back to English.

Key fact shaping this plan: **the repo has no fragment-lifecycle test capability.** `:app` tests are JVM-only
(`junit:junit:4.13.2`, `testOptions { unitTests.returnDefaultValues = true }`), which is why Phases 3–4 accepted downgraded
verification (helper tested, call sites untested; margin resolvers tested, `WindowManager` untested). ODD-13's natural regression test —
instantiate the fragment with no args — is unexpressible without a fragment runtime. This branch therefore pilots Robolectric,
scoped as narrowly as possible (see §2).

---

## 2. Locked design decisions

1. **Robolectric is scoped to `:app`, pinned, and SDK-pinned.** Add exactly one dependency to `app/build.gradle`:
   `testImplementation 'org.robolectric:robolectric:4.15.1'`, plus `testOptions { unitTests.includeAndroidResources = true }`
   (required for resource-backed fragment inflation; keep the existing `returnDefaultValues = true` line untouched).
   All Robolectric tests carry `@Config(sdk = 34)`: running at API 34 avoids newest-shadow gaps around `compileSdk = 36`,
   keeps the one-time `android-all` download small, and is representative — none of the touched code paths are API-level-sensitive.
   Do not add Robolectric to `:libraries:humla`; do not apply it to existing tests. `4.15.1` was confirmed resolvable
   from Maven Central during implementation, so no version substitution was needed.
2. **New Robolectric tests use JUnit 4 style, exempt from the module's `TestCase` convention.** Robolectric requires the
   `RobolectricTestRunner` (`@RunWith`), which is incompatible with `junit.framework.TestCase`. Existing tests stay as-is;
   the two new test classes use `@Test` + `@RunWith(RobolectricTestRunner.class)` + `@Config(sdk = 34)`. Record this exemption
   here so a future pedantic pass does not "normalize" them back into `TestCase` and silently break the runner.
3. **Pilot-then-decide with a locked revert rule.** The branch adds exactly two Robolectric tests (ODD-13 no-args, ODD-15 teardown;
   see §5). If either test requires production-code contortions (custom test themes, shadow configuration beyond `@Config`,
   real `WebView` emulation) or makes `./scripts/check.sh` materially slower/flakier, **drop that test and fall back to the
   documented manual verification** — do not grow the infrastructure to save the test (anything beyond the locked §5 recipe —
   additional themes, shadow configuration beyond `@Config`, real `WebView` emulation, production-code changes — triggers
   the drop, no exceptions). One dropped test does not fail the branch;
   dropping both means also reverting the `app/build.gradle` hunks so the branch is infrastructure-free.
4. **No assertions on WebView rendering.** Treat Robolectric's `ShadowWebView` as unemulated for this branch — `loadData`,
   `WebSettings`, and navigation callbacks are outside what the tests may rely on. Tests may drive the fragment lifecycle and assert field state / exception type, but must not assert
   anything about rendered comment content. (This is also why ODD-15's test asserts view-field nulling and listener safety,
   not preview behavior.)
5. **ODD-13 scope includes the two subclass sites, plus key validation.** `UserCommentFragment.getSession()` and
   `ChannelDescriptionFragment.getChannelId()` switch to a shared `requireIntArgument()` helper (missing key fails fast
   instead of silently yielding `0`), validated up front via a `validateArguments()` hook called from `onCreate()` so
   partial bundles fail at creation. The base hook additionally requires `ARG_EDITING`; the channel override rejects
   `editing=true` (channel editing is unsupported — previously a Save-time `UnsupportedOperationException`). Observer
   tracking caches the registering service so `onDestroy` unregisters against it even after unbind, releasing any
   replaced observer. Unbound save/fetch degrades to a logged no-op with the dialog left open. The five remaining
   `onAttach(Activity)` subclasses across the module migrate in the same shape, and `ChannelMenu`/`ChannelListAdapter`
   gain null-target/null-service guards.
   (which also drop deprecated `Fragment.instantiate`), and tests. Observer tracking (`trackCommentObserver`, released
   in `onDestroy`) closes the dismiss-before-reply leak; `getService()` is null-guarded at both use sites; `mProvider`
   is cleared in `onDetach`; the WebView is detached from its parent before `destroy()`.
6. **ODD-14 keeps the `RuntimeException` type, adds the cause.** `throw new RuntimeException(msg, e)` — minimal diff;
   switching to `IllegalStateException` would be more idiomatic but changes the observable exception type for no functional gain.
   New signature: `onAttach(@NonNull Context context)` (add `import android.content.Context;` and
   `import androidx.annotation.NonNull;`; drop the `android.app.Activity` import if it becomes unused), cast `context`,
   `super.onAttach(context)`. A `ContextWrapper` fallback re-resolves via `getActivity()` before throwing, preserving
   the exact type/message/cause contract.
7. **ODD-15 guards both fields, not just `mCommentView`.** The listener touches `mCommentEdit.getText()/setText(...)` as well as
   `mCommentView.loadData(...)`, so a `mCommentView`-only guard still NPEs on `mCommentEdit` once that field is nulled.
   Locked form: early return `if (mCommentView == null || mCommentEdit == null) return;` at the top of `onTabChanged`.
   (This mirrors the existing `loadComment()` null-guard precedent.)
8. **ODD-16 adds only `comment_open_link` in this branch.** Values: fr `Ouvrir le lien` (infinitive, like the neighboring
   `Éditer la source` / `Voir la source`; consistent with the noun `Affichage`), zh-rCN `打开链接` (matching `查看` / `编辑源代码` / `查看源代码`).
   ODD-19's missing fr/zh strings (`server_edit_url_password_warning`, `pref_talk_broadcast_*`) stay Phase 7 scope — note the
   single-strings-pass opportunity in the commit message, but do not expand this diff.
9. **First Robolectric run needs network.** The `android-all` runtime jar downloads from Maven Central on first execution
   (hundreds of MB, cached thereafter). In the Nix dev shell this is fine; do not attempt the maiden run in a network-sandboxed
   context and mistake download failure for test failure.

---

## 3. Implementation steps

### Step 0 — `:app` Robolectric infrastructure (revertible hunk)

In `app/build.gradle`, in the existing `testOptions` block and `dependencies` block:

```groovy
testOptions {
    unitTests.returnDefaultValues = true
    unitTests.includeAndroidResources = true
}
```

```groovy
testImplementation 'junit:junit:4.13.2'
testImplementation 'org.robolectric:robolectric:4.15.1'
```

No other build-file changes: no new source sets, no `testOptions.unitTests.all` JVM-arg tweaks, no manifest or resource changes for tests.
Verify the hunk in isolation before writing production fixes: run one trivial Robolectric smoke test
(e.g. assert `ApplicationProvider.getApplicationContext() != null` at `sdk = 34`) and confirm it passes with network available.
(`ApplicationProvider` lives in `androidx.test:core`, which implementation confirmed is **not** transitively on the test
classpath — the smoke test failed to compile against it. Per this recipe the branch therefore uses the deprecated-but-present
`RuntimeEnvironment.getApplication()`; no other substitution is permitted.)
If the smoke test cannot pass cleanly, stop — revert this step and implement Phase 6 with manual verification only.

### Step 1 — ODD-13 + ODD-14: argument guards and attach modernization

`AbstractCommentFragment.java`:

```java
mComment = requireArguments().getString("comment");
...
public boolean isEditing() {
    return requireArguments().getBoolean("editing");
}
```

```java
@Override
public void onAttach(@NonNull Context context) {
    super.onAttach(context);
    try {
        mProvider = (HumlaServiceProvider) context;
    } catch (ClassCastException e) {
        throw new RuntimeException(context.getClass().getName() + " must implement HumlaServiceProvider!", e);
    }
}
```

(Add `import androidx.annotation.NonNull;` alongside `import android.content.Context;`; drop the `android.app.Activity` import
if it becomes unused.)
Same commit, `UserCommentFragment.java` / `ChannelDescriptionFragment.java`:

```java
return requireArguments().getInt("session");   // / ("channel")
```

### Step 2 — ODD-15: complete teardown + listener guard

```java
@Override
public void onDestroyView() {
    // Release the WebView's native peer; otherwise the renderer and its
    // host Activity stay reachable via mCommentView after dismissal.
    if (mCommentView != null) {
        mCommentView.destroy();
        mCommentView = null;
    }
    mTabHost = null;
    mCommentEdit = null;
    super.onDestroyView();
}
```

Listener head in `onCreateDialog()`:

```java
mTabHost.setOnTabChangedListener(new TabHost.OnTabChangeListener() {
    @Override
    public void onTabChanged(String tabId) {
        // View hierarchy may be torn down (ODD-15); never touch nulled fields.
        if (mCommentView == null || mCommentEdit == null) return;
        ...
```

### Step 3 — ODD-16: translations

Add to `app/src/main/res/values-fr/strings.xml` (next to the other `comment_*` strings):

```xml
<string name="comment_open_link">Ouvrir le lien</string>
```

Add to `app/src/main/res/values-zh-rCN/strings.xml`:

```xml
<string name="comment_open_link">打开链接</string>
```

### Step 4 — Verify the `remediation-plan.md` link (already added)

The Phase 6 section intro already links this plan (added together with the plan itself, same convention as the Phase 5 link) —
verify the link exists and do not duplicate it. On merge, flip ODD-13 – ODD-16 to Resolved with branch/commit recorded.

---

## 4. Edge cases

| Case | Expected handling |
|---|---|
| Bundle present but `"comment"` key missing | `requireArguments().getString()` returns null → existing `mComment == null` → `requestComment()` path. Unchanged. |
| Bundle present but `"editing"` key missing | `validateArguments` throws `IllegalStateException` (fail-fast; both production callers always put it) |
| No-args instantiation (future caller, restore edge, test) | `IllegalStateException` with a clear message instead of a bare NPE. Intended behavior change. |
| Host activity not implementing `HumlaServiceProvider` | Same `RuntimeException` type/message as today, now with the `ClassCastException` cause chained for crash-report readability. |
| Tab switch racing teardown | Early return; no NPE on either nulled field. Unobservable in practice (detached `TabHost` cannot fire), defense-in-depth only. |
| `onDestroyView()` without `onCreateDialog()` (never shown) | All three fields already null; method is a no-op besides `super`. Safe. |
| fr/zh locales for the chooser title | Translated title; all other locales keep the English fallback. Cosmetic only. |

---

## 5. Automated tests

Two new files, both JUnit 4 + Robolectric (`@RunWith(RobolectricTestRunner.class)`, `@Config(sdk = 34)`),
GPL header copied from a recent file with `Copyright (C) 2026 Brian Zhu`.
Neither test asserts WebView rendering (per §2.4).

Shared harness (locked). Both test classes live in `app/src/test/java/se/lublin/mumla/channel/comment/` and share
`CommentDialogStubHost` (own file in the same package): a `FragmentActivity` implementing `HumlaServiceProvider` (`getService()` returns null,
`add/removeServiceFragment` are no-ops). The stub host is mandatory, not optional — `onAttach` casts the host to
`HumlaServiceProvider` and rethrows `RuntimeException` on mismatch, so every lifecycle-driven test fails before reaching
the code under test without it. Returning null from `getService()` is safe because every pinned bundle below carries a
non-null `"comment"`, so `requestComment(mProvider.getService())` is never entered and the service is never dereferenced.
Teardown tests additionally call `setTheme(R.style.Theme_Mumla)` on the host before `setup()` (`Theme.Mumla` extends
`Theme.Material3.DayNight.NoActionBar`, satisfying `MaterialAlertDialogBuilder`); anything beyond this recipe triggers
the §2.3 fallback, no exceptions.

Driver APIs (locked, normative):

- Arguments tests: `isEditing()` is driven host-free on a bare instance — `new UserCommentFragment()` followed by
  `fragment.isEditing()`, where `requireArguments()` throws before any host interaction. `onCreate()`, however, cannot run
  host-free: `super.onCreate()` walks the child `FragmentManager`, which needs an attached host under androidx.fragment 1.8.9
  (a bare `fragment.onCreate(null)` NPEs inside the framework before reaching the guard). The `onCreate` rows therefore attach
  via `commitNow()` to the shared stub host — no dialog is created, so no theme, service, or WebView is involved.
  Do not use `FragmentScenario` here — its fixed internal host cannot satisfy the provider cast.
- Teardown tests drive full dialog creation via `Robolectric.buildActivity(StubHost.class)` + `setTheme` + `setup()`,
  then `fragment.show(host.getSupportFragmentManager(), "tag")` + `executePendingTransactions()`. Teardown is driven by
  `fragment.dismissAllowingStateLoss()` + `executePendingTransactions()` (which routes through `onDestroyView`); direct
  `onDestroyView()` calls are not permitted (they bypass `DialogFragment` dismissal bookkeeping).

Pinned bundles (locked): user-fragment bundles are `{session: 1, comment: "<p>x</p>", editing: <bool>}`; channel-fragment
bundles are `{channel: 1, comment: "<p>x</p>", editing: false}`. A null or missing `"comment"` is never used in lifecycle
tests — it would enter the provider-dependent `requestComment` path and conflate setup failure with the behavior under test.

New file `app/src/test/java/se/lublin/mumla/channel/comment/CommentFragmentArgumentsTest.java`:

| Test | Procedure | Expectation |
|---|---|---|
| `userOnCreateWithoutArgumentsThrows` | `new UserCommentFragment()` with no arguments; attach via themed stub host | `IllegalStateException` (message + key-pinned), not `NullPointerException` |
| `userIsEditingWithoutArgumentsThrows` | Same fragment; `fragment.isEditing()` directly (host-free) | `IllegalStateException` (message-pinned) |
| `userOnCreateWithBundlePreservesEditing` | Pinned user bundle with `editing=true`; attach; `isEditing()` | No throw; returns true |
| `userOnCreateWithViewBundleClearsEditing` | Pinned user bundle with `editing=false`; attach; `isEditing()` | No throw; returns false |
| `userOnCreateWithBundleMissingSessionThrows` | User bundle without `"session"`; attach | `IllegalStateException` (message + key-pinned) |
| `userOnCreateWithBundleMissingEditingThrows` | User bundle without `"editing"`; attach | `IllegalStateException` (message + key-pinned) |
| `channelOnCreateWithoutArgumentsThrows` | `new ChannelDescriptionFragment()` with no arguments; attach via themed stub host | `IllegalStateException` (message-pinned) |
| `channelOnCreateWithBundleSucceeds` | Pinned channel bundle; attach; `isEditing()` | No throw; returns false |
| `channelOnCreateWithBundleMissingChannelThrows` | Channel bundle without `"channel"`; attach | `IllegalStateException` (message + key-pinned) |
| `channelOnCreateWithEditingRejects` | Channel bundle with `editing=true`; attach | `IllegalStateException` naming editing |
| `userAttachToNonProviderHostThrows` | Pinned bundle on a plain `FragmentActivity`; attach | Exact-type `RuntimeException` naming `HumlaServiceProvider` with `ClassCastException` cause |

The private `ChannelDescriptionFragment.getChannelId()` (key-validated like its sibling, plus the editing rejection)
is pinned by the channel rows above, so no reflection into the private getter is required.

New file `app/src/test/java/se/lublin/mumla/channel/comment/CommentFragmentTeardownTest.java` (user fragment, pinned bundle
with `editing=false`):

| Test | Procedure | Expectation |
|---|---|---|
| `destroyViewNullsAllViewFields` | `show()` per the locked recipe; assert all three view fields non-null via reflection before dismissal (`AbstractCommentFragment.class.getDeclaredField(...)` + `setAccessible(true)` — the declaring class, not the subclass); `dismiss()`; read the fields the same way | All three null |
| `tabCallbackAfterTeardownIsSafe` | After teardown, drive the captured `TabHost` with `setCurrentTab(1)` then `setCurrentTab(0)` (fires the registered `OnTabChangeListener` for `"Edit"` and `"View"`) | Returns without throwing |

Fallback rule (locked, from §2.3): if the teardown tests need theme/shadow scaffolding beyond `@Config` (e.g. a Material
test theme to satisfy `MaterialAlertDialogBuilder`), drop `CommentFragmentTeardownTest.java`, verify ODD-15 manually per §6,
and keep only the arguments test. If the arguments test also misbehaves, revert Step 0 as well.

Run: `nix develop --command ./gradlew :app:testFossDebugUnitTest` during development (first run needs network for the
`android-all` download); full `./scripts/check.sh` in the worktree before completion.

---

## 6. Manual / device verification

1. Open a user comment dialog (view and edit) and a channel description dialog; verify each renders as before.
2. Open and dismiss each dialog repeatedly, including via Back and outside-touch; verify no crash and no retained view
   hierarchy in the Android Profiler heap dump (ODD-15 check — the WebView peer release was already verified in the
   phase2-comment-webview branch; confirm no regression).
3. Trigger a comment containing an `http(s)` link; verify the system chooser appears with the translated title in French
   and Chinese (PRC) locales (ODD-16 check).
4. If either Robolectric test was dropped per the fallback rule, explicitly exercise its path here instead:
   no-args instantiation is not reachable from production UI, so record the dropped test as accepted-manual in the commit message.

---

## 7. Acceptance criteria

- [ ] `requireArguments()` at all four sites (fragment `onCreate`/`isEditing` plus both subclass getters); no-args construction
      fails fast with `IllegalStateException`. Channel private getter covered by identical-change review + channel rows in
      `CommentFragmentArgumentsTest` (no reflection into the private getter).
- [ ] `onAttach(Context)` override with chained cause; no `onAttach(Activity)` override remains (lint `Deprecated` clean).
- [ ] `onDestroyView()` nulls all three view fields; tab listener null-guards both `mCommentView` and `mCommentEdit`.
- [ ] `comment_open_link` translated in `values-fr` and `values-zh-rCN`, placed adjacent to the other `comment_*` strings.
- [ ] `CommentFragmentArgumentsTest` green under Robolectric (`sdk = 34`); teardown test green, or explicitly dropped with
      manual coverage recorded. No WebView-rendering assertions anywhere.
- [ ] `app/build.gradle` Robolectric hunks present if and only if at least one Robolectric test ships (no orphan infrastructure).
- [ ] Manual checks in §6 performed on device; French/Chinese chooser titles visually confirmed.
- [ ] `remediation-plan.md` Phase 6 link verified present (not duplicated); ODD-13 – ODD-16 flipped to Resolved with branch/commit
      recorded (same convention as Phases 1–5) once merged.
- [ ] `./scripts/check.sh` green in the worktree. No merge, push, or worktree deletion (per repo policy —
      leave the branch for review).

---

## 8. Work plan (repo mechanics)

- Worktree/branch: `./scripts/worktree.py add feature/oddities-phase6-comment-dialog` (root stays on `master`; this plan file
  itself lives on `master` as standalone documentation).
- Commits via `python3 scripts/commit.py -m "<scope>: <subject>"` with the three-section body
  (`Context & Motivation` / `Technical Approach` / `Edge Cases & Impact`); suggested split is
  (1) Step 0 infrastructure + smoke test, (2) ODD-13 + ODD-14 with arguments test, (3) ODD-15 with teardown test
  (or manual-verification note if dropped), (4) ODD-16 strings + `remediation-plan.md` link verification.
- New test files need the standard GPL-3.0-or-later header with `Copyright (C) 2026 Brian Zhu`.
- This plan is the first Robolectric consumer in the repo: keep the pilot visible in commit messages so a later
  infrastructure review can find every Robolectric-dependent test from the branch history.
