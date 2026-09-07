---
name: mumla-release
description: >-
  End-to-end release process for the Mumla OLED Android client: bump the
  semver version via git tag, write user-centric GitHub release notes,
  build the signed FOSS release APK, and publish the release with the
  APK attached. Use when the user asks to cut, create, or publish a release.
---

# Mumla OLED Release Process

Prerequisites: `gh` CLI authenticated (`gh auth status`), Nix dev shell for
Gradle builds, clean working tree on `master` with the changes to release
already merged.

## 1. Determine the version

- Latest tag: `git tag -l | sort -V | tail -1`. Versions are `0.X.X` semver.
- Scope of changes: `git log <last-tag>..master --oneline`.
- **Bump type (major/minor/patch) must be explicitly stated by the user.**
  Never infer it from the commit log. If the user did not specify one, ask
  before proceeding. You may quote a recommendation (e.g. "the log shows
  features, so minor by convention") but the user makes the call.
- Flag anything in the log beyond the user's described core/secondary
  changes before tagging.

## 2. Review and categorize changes

- Read full commit messages: `git log <last-tag>..master --format='--- %h %s%n%b'`.
- Categorize changes into:
  - **User-Facing**: New UI features, preference additions, bug fixes,
    visual polish, audible sound/mic improvements, and connection stability.
  - **Developer/Internal**: Test harnesses, unit test migrations, CI/build
    tweaks, lint fixes, dev scripts, and pure internal code refactorings.
- Developer and internal changes must **not** be included in release highlights
  or the user-facing commits list.

## 3. Tag and push

```bash
git tag -a <version> -m "Release <version>"
git push origin master <version>
```

## 4. Write release notes

Write notes to `plans/release-<version>.md` (the `plans/` directory is
gitignored).

### Core Principle: Non-Technical End-User Focus

The release notes are read by non-technical end users downloading the APK to
their Android device. Notes must explain **what the user experiences** (what
they see, hear, or can do differently), NOT how it was implemented in code.

- **Lead with user benefits**: Describe the problem solved or feature added
  from the user's viewpoint.
- **No implementation jargon**: Never include math equations, filter topologies,
  class/variable names, Android resource IDs (e.g. `ic_action_*`), internal
  thread/buffer structures, or architectural patterns.
- **Translate technical mechanisms into user benefits**:
  - *Don't*: "Implemented 90 Hz 2nd-order Butterworth biquad high-pass filter."
  - *Do*: "Rumble & Wind Noise Filter: Added filtering to cut low-frequency
    microphone rumble, wind buffeting, and handling noise before transmission."
  - *Don't*: "Decoupled VAD from AdaptiveLeveler AGC and SoftLimiter saturation."
  - *Do*: "Speech Detection Consistency: Voice activation threshold no longer
    drifts or gets stuck when microphone volume boosts."
  - *Don't*: "Mute lookahead FIFO ring buffer zeroed on mute transition."
  - *Do*: "Strict Instant Mute: Muting now cuts transmission immediately,
    preventing any cut-off words from leaking out."
  - *Don't*: "Prevented concurrent modification exceptions in message snapshot list."
  - *Do*: "Chat Stability: Fixed crashes that could occur when receiving rapid
    chat messages."

### What to Include vs. Exclude in Highlights

| Category | Include in `## Highlights`? | Guidance |
| --- | --- | --- |
| **New Features & UI** | **Yes** | Describe new buttons, screens, settings, and behaviors. |
| **User-Observable Bug Fixes** | **Yes** | Describe the resolved crash, UI glitch, or broken behavior. |
| **Audio / Connection Improvements** | **Yes** | Describe the audible result or connection stability improvement. |
| **Test Suites & Harnesses** | **No** | Completely omit (e.g. C++ test migrations, test runner speedups). |
| **Developer Tooling & Scripts** | **No** | Completely omit (`check.sh`, `commit.py`, Nix expressions). |
| **Pure Code Refactoring** | **No** | Omit if there is no observable behavior change for the user. |
| **Internal Identifiers** | **No** | No file paths, class names, method names, or resource IDs. |

### Release Notes Structure

- **Title**: `Mumla OLED <version>` (matches existing release list; do NOT use
  bare version).
- **`## Highlights`**:
  - Group by feature or theme using `### Plain-English Feature Title`.
  - Under each heading, use bullet points formatted as:
    `* **Feature/Benefit Summary**: 1–2 plain-language sentences explaining
    what changed and why it helps the user.`
  - Order by importance (primary feature/fix first, secondary items after).
- **`## Commits`**:
  - Bullet list of `- `commit subject`` for user-facing changes only.
  - Omit developer tooling, test-only, build, and CI commits (e.g. `check:`,
    `build:`, `nix:`, internal test refactors).
- **Universal APK Trailer** (must be the exact concluding paragraph):
  ```markdown
  Universal release APK includes native support for `arm64-v8a`, `armeabi-v7a`, `x86`, and `x86_64` architectures.
  ```

## 5. Build the release APK

```bash
nix develop --command ./gradlew assembleFossRelease
```

Output: `app/build/outputs/apk/foss/release/mumla-foss-release.apk`
(already renamed from `app-foss-release.apk` by the build; signed with the
release config).

## 6. Publish the release

```bash
gh release create <version> app/build/outputs/apk/foss/release/mumla-foss-release.apk \
  --target master --title "Mumla OLED <version>" \
  --notes-file plans/release-<version>.md
```

## 7. Verify

- `gh release view <version> --json assets,body` — APK attached, title
  correct, universal-APK note present at the end of the body, and notes free
  of developer jargon or test-only sections.
- Check the release list (`gh release list`) so the new entry matches the
  naming of previous releases.
