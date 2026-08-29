---
name: mumla-dev
description: >-
  Development workflows, guidelines, verification tools, and architecture reference
  for the Mumla OLED Android client repository.
---

# Mumla OLED Development Guide

## Repository Architecture

- **`:app`**: Android UI layer, activities, fragments, overlay service, and preferences.
- **`:libraries:humla`**: In-tree core library containing the Mumble protocol engine, background service, JNI native audio engine (`rnnoise`, Oboe/AAudio), and codec bindings.
- **Submodules**: Direct 1st-level submodules defined in root `.gitmodules`:
  - `libraries/humla/src/main/jni/{opus, celt-0.11.0-src, celt-0.7.0-src, speex, rnnoise}`
  - `libraries/humla/libs/humla-spongycastle`


## Workflow Overview

1. **Branching**:
   - Always create and work on a dedicated branch (`feature/<name>`, `bugfix/<name>`, `docs/<name>`, `chore/<name>`). Never commit directly on `master`.

2. **Commit Wrapper & 50/72 Rule**:
   - Use `python3 scripts/commit.py -m "<message>"` to automatically format to the 50/72 rule, enforce the body format, and execute `git commit`.
   - Subject line: <= 50 characters, concise and imperative with a scope prefix (e.g. `app:`, `ui:`, `humla:`, `audio:`, `proto:`, `build:`, `nix:`, `docs:`, `util:`).
   - Body: Automatically wrapped to 72 columns; the wrapper hard-fails (exit 1) unless the body contains the three labeled sections, labels exact and in this order, as plain line starts (no Markdown bullets/headings):
     - Context & Motivation
     - Technical Approach
     - Edge Cases & Impact
   - On failure, the error names the problem, prints the required template, and points to AGENTS.md — fix the message and retry; do not bypass with `--no-body-check`.

3. **Fast Verification**:
   - Run `./scripts/check.sh` before completing any task.
   - Fast FOSS unit tests: `nix develop --command ./gradlew testFossDebugUnitTest` (~4s).
   - Full test suite (when required): `nix develop --command ./gradlew test` (~24s).

4. **Building & Running**:
   - Build FOSS debug APK: `nix develop --command ./gradlew assembleFossDebug`.
   - Launch app on connected device/emulator:
     ```bash
     adb shell am start -n se.lublin.mumla.oled15/se.lublin.mumla.app.MumlaActivity
     ```

5. **Upstream Reference**:
   - Protocol schemas, audio codec logic, and server communication are referenced in `../mumble` (e.g. `../mumble/src/Mumble.proto`, `../mumble/src/MumbleUDP.proto`).
