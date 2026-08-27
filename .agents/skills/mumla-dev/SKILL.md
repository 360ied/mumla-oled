---
name: mumla-dev
description: >-
  Development workflows, guidelines, verification tools, and architecture reference
  for the Mumla OLED Android client repository.
---

# Mumla OLED Development Guide

## Workflow Overview

1. **Branching**:
   - Always create and work on a dedicated branch (`feature/<name>`, `bugfix/<name>`, `docs/<name>`, `chore/<name>`). Never commit directly on `master`.

2. **Commit Wrapper & 50/72 Rule**:
   - Use `python3 scripts/commit.py -m "<message>"` (or `-s "<subject>" -b "<body>"`) to automatically format and execute `git commit`.
   - Subject line: <= 50 characters, concise and imperative with a scope prefix (e.g. `proto:`, `nix:`, `core:`, `docs:`, `chat:`, `util:`, `app:`, `ui:`).
   - Body: Automatically wrapped to 72 columns with structured sections:
     - Context & Motivation
     - Technical Approach
     - Edge Cases & Impact

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
