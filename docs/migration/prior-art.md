# Prior Art: Views → Compose Migrations

Why the 2026-10-15 slices 6-7 attempt failed, read against how others
migrated View-based apps to Compose. The consensus is incremental
interop with navigation last; the killed attempt did the inverse
(big-bang chain, pixel freeze, shell last). See the structural lesson
in [status](status.md).

## 1. Official Google strategy (incremental, interop indefinitely)

Google recommends migrating with Compose and Views co-existing until
the app is fully in Compose — there is no need to reach Compose-only
to reap the benefits:

1. Build new screens with Compose (`ComposeView` in a Fragment).
2. Build a library of common UI components.
3. Replace existing features one screen at a time — simple/static
   screens first, mixed screens bottom-up (leaves toward root).
4. Remove Fragments and adopt Navigation Compose **last**: every
   destination must be a composable first, and there is no incremental
   path ([issue 265480755](https://issuetracker.google.com/issues/265480755)).

Sources:

- [Migration strategy](https://developer.android.com/develop/ui/compose/migrate/strategy)
- [Migrate XML Views to Compose](https://developer.android.com/develop/ui/compose/migrate/migrate-xml-views-to-jetpack-compose)
- [Other considerations](https://developer.android.com/develop/ui/compose/migrate/other-considerations)

Takeaway for Mumla OLED: the killed plan treated interop shims as rot
to be deleted ("big-bang, not strangler") and required 100% plus the
shell before anything was shippable. Google's model ships value at
every step and tolerates a mixed tree indefinitely.

## 2. Sunflower sample (worked example, 5 screens)

[Chris Arriola, "Migrating Sunflower to Jetpack Compose"](https://medium.com/androiddevelopers/migrating-sunflower-to-jetpack-compose-f840fa3b9985)
applies the strategy above: screen-level composables bottom-up,
component reuse across screens, each Fragment returning a `ComposeView`
wrapped in the existing XML theme (reused via adapter, not a ground-up
token port), one preview plus one UI test per screen. Containers migrate
last (`HomeViewPagerFragment` after its pages, avoiding Fragments
inside Compose and the later refactor).

Two obstacles match the killed attempt exactly:

- `StateListDrawable` is incompatible with Compose (state drives the UI).
  Sunflower swapped in a single `VectorDrawable` plus tint; the Mumla
  OLED attempt hit selectors and layer-lists the same way and had to
  redraw badges on `Canvas`.
- Collapsing toolbars in M2 need `nestedScroll` interop; M3 supports
  them natively.

Structural difference: Sunflower split the Material 2 → 3 migration out
([issue #829](https://github.com/android/sunflower/issues/829)). The
Mumla OLED attempt bundled toolkit, Material 2 → 3, and OLED/dark/light
schemes into one parity gate, so every diff had three possible causes
at once (tint, elevation, spacing).

## 3. Reply sample ("common UI first")

[Simona Milanović, "Migrating to Jetpack Compose — an interop love story, part 1"](https://medium.com/androiddevelopers/migrating-to-jetpack-compose-an-interop-love-story-part-1-3693ca3ae981)
migrates to unblock parallel work: smallest shared components, then
complex components, then low-risk screens, then complex screens — while
keeping the original navigation (Navigation Compose is not a requisite)
and proving interop in both directions (Compose `Chip` inside a View
`ChipGroup`, Compose rows inside a `RecyclerView`).

Two sentences that condemn the killed attempt as executed:

- "Views to Compose don't always need a 1:1 mapping" — a View
  `ConstraintLayout` may become Compose `Row`/`Column`. The attempt
  forbade this (pixels frozen), forcing `BasicTextField` plus a divider
  to fake the legacy underline rhythm and tint-free `Surface` to fake
  `CardView` — fighting the framework.
- "Redesign? Perfect time to migrate." Mumla OLED is feature-complete
  with no redesign and no new screens, so every converted screen was
  pure risk with zero user value.

Screenshot testing is the recommended safety net (see
[Now in Android #876](https://github.com/android/nowinandroid/pull/876),
now Roborazzi / Compose Preview Screenshot Testing). The Mumla OLED
before/after Paparazzi gate (old-View goldens versus new-Compose
goldens, same data, pinned device) is the honest version of this:
new-UI-only goldens bless whatever is rendered.

## 4. Merpay (hundreds of screens, shipped throughout)

[Merpay migration to SwiftUI/Jetpack Compose](https://engineering.mercari.com/en/blog/entry/20241221-leading-a-project-to-migrate-hundreds-of-screens-to-swiftui-jetpack-compose-from-uikit-androidview-in-merpay/):
about two years, roughly 65% of Android screens done and released as of
December 2024. Process weight matches scope: a per-screen spreadsheet
with IDs and owners, states from TODO through QA to Done, CTO/VPoE
reporting, milestones shared company-wide, migration timed with product
initiatives (with explicit acknowledgment that low-churn screens never
migrate on pull alone). The value prop was the new DesignSystem itself
(unified UX, dark mode, accessibility) — visible improvement justifies
drift.

Contrast: the killed attempt ran a hundreds-screen program (slice table,
per-screen goldens, stacked branches) with one developer, no design
partner, no product pull, and drift defined as pure defect. Shipped at
65% beats killed at 100%.

## 5. Strangler fig on mobile

[Thoughtworks, "Using the Strangler Fig with Mobile Apps"](https://martinfowler.com/articles/strangler-fig-mobile-apps.html):
displace incrementally at navigation entry points ("points of
interception"), behind feature flags with instant backout and contract
tests at the bridge boundaries. First value in about a month against a
two-year rewrite quote, with roughly halved cycle time. This is the
industry default for the problem class — the pattern the migration plan
explicitly rejected (correctly for Kotlin in slices 0-5, fatally for UI
in slices 6-7).

## Revival checklist (if slices 6-7 are ever reopened)

- Follow Google's order: common UI, then simple screens, then complex
  screens, navigation last. Keep Fragment navigation throughout.
- Split theming from toolkit: port tokens first, convert screens later.
- Permit simplification: no 1:1 mapping fetish.
- Keep the before/after Paparazzi gate; add the device loop (torture
  matrix) from the first screen, not the last.
- Ship mixed indefinitely; time conversion with product or redesign
  pull, or accept that a feature-complete app may never justify it.
