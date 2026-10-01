# Inventory of Broken Features & Deficiencies

This directory contains individual analysis and remediation plans for features in the Mumla OLED codebase that are definitely broken, unimplemented stubs, or suffering from critical bugs.

---

## Issue Index

| # | Issue / Plan | Status | Severity | Component | Summary |
|---|---|---|---|---|---|
| 1 | [Channel Editing Discards Changes & Empty Position Crashes](../../docs/broken-features/channel-editing-unimplemented.md) | Closed (Out of Scope) | High | `app` | Channel creation, editing, and deletion removed from app; server admin scoped out. |
| 2 | [Password-Protected Certificate Import Fails Immediately](../../docs/broken-features/password-protected-cert-import-failure.md) | Resolved | High | `app` | Password failures distinguished from corrupt files; prompt uses proper masking and retry error feedback; raw cert bytes and password stored in DB for bit-identical round-trips and seamless connections. |
| 3 | [Channel Search Provider Blocking IPC and Unhandled Free-Text Search](../../docs/broken-features/search-provider-orphaned-and-deadlock.md) | Open | Medium | `app` | Toolbar suggestion search works (provider registered via foss overlay); `query()` stalls a Binder thread up to 5s on first call, lacks null-guards, leaks its binding, and free-text `ACTION_SEARCH` submit goes nowhere. |
| 4 | [DatagramSocket File Descriptor Leak During Server Pings](../../docs/broken-features/favourite-server-ping-socket-leak.md) | Resolved | High | `app` | Ping socket wrapped in `try-with-resources` so the descriptor always closes; short-reply guard rejects truncated responses. |
| 5 | [Certificate Export Error Dialog Dismissed Instantly by Activity Finish](../../docs/broken-features/certificate-export-dialog-lifecycle.md) | Resolved | Medium | `app` | Error dialog replaced with a `Toast` that survives `finish()`; null-data and null-stream guards added. |
| 6 | [Whisper Target to Individual Users Throws UnsupportedOperationException](../../docs/broken-features/whisper-target-users-unimplemented.md) | Open | Medium | `libraries/humla` | `WhisperTargetUsers` unconditionally throws `UnsupportedOperationException` on all methods. Incoming whisper voice packets are not differentiated. |
| 7 | [Server Ban and User List Administration APIs Throw UnsupportedOperationException](../../docs/broken-features/server-ban-and-user-lists-unimplemented.md) | Closed (Out of Scope) | Medium | `libraries/humla` | Dead `requestBanList()` and `requestUserList()` methods pruned; server admin scoped out. |
| 8 | [CELT and Speex Codecs Dropped](../../docs/broken-features/celt-11-robot-voices-disabled.md) | Closed (Deprecated / Dropped) | Low | `libraries/humla` | Obsolete pre-2013 CELT and Speex codecs dropped in parity with upstream Mumble 1.5+; adaptive jitter buffer migrated in-tree into `libhumlaaudio.so`. |
| 9 | [Priority Speaker Audio Ducking Not Implemented](../../docs/broken-features/priority-speaker-ducking-unimplemented.md) | Open | Low-Med | `libraries/humla` | Marked as `// TODO: add priority speaker support.`, priority speakers do not duck or attenuate non-priority audio streams during playback. |
| 10 | [User Context Menu Ban Dialog Mislabeled as "Kick"](../../docs/broken-features/user-ban-dialog-mislabeled-kick.md) | Resolved | Medium | `app` | Resolved in `UserMenu.java` by conditionally choosing between `user_menu_ban` and `user_menu_kick`. |
| 11 | [Channel Description Editing Is an Unimplemented Stub](../../docs/broken-features/channel-description-editing-stub.md) | Closed (Out of Scope) | Low-Med | `app` | Channel descriptions are strictly view-only; editing is out of scope and throws `UnsupportedOperationException`. |
