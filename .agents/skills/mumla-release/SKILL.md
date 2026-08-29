---
name: mumla-release
description: >-
  End-to-end release process for the Mumla OLED Android client: bump the
  semver version via git tag, write GitHub release notes from detailed
  commit bodies, build the signed FOSS release APK, and publish the
  release with the APK attached. Use when the user asks to cut, create,
  or publish a release.
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

## 2. Review commit bodies for release notes

- Read full messages: `git log <last-tag>..master --format='--- %h %s%n%b'`.
- Commit bodies follow the three-section format (Context & Motivation,
  Technical Approach, Edge Cases & Impact) — distill them into user-facing
  notes, not commit prose.

## 3. Tag and push

```bash
git tag -a <version> -m "Release <version>"
git push origin master <version>
```

## 4. Write release notes

- Write notes to `plans/release-<version>.md` (the `plans/` directory is
  gitignored).
- Structure (match prior releases via `gh release view <prev> --json body`):
  - `## Highlights` — one `###` section per user-facing change, ordered by
    importance (core change first, secondary changes after). Explain the
    motivation in user terms, then behavior details, edge-case handling, and
    self-healing/recovery behavior.
  - `## Commits` — bullet list of `<scope>:` subject lines.
  - Final paragraph: `Universal release APK includes native support for
    `arm64-v8a`, `armeabi-v7a`, `x86`, and `x86_64` architectures.`
- Title: `Mumla OLED <version>` (match the existing release list exactly —
  do not use the bare version as the title).

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
  correct, universal-APK note present at the end of the body.
- Check the release list (`gh release list`) so the new entry matches the
  naming of previous releases.
