# Phase 2 implementation plan — malicious-server rendering + decode (M3–M7, L3, L5)

Companion to [remediation-plan.md](remediation-plan.md) Phase 2 and
[findings.md](findings.md) (M3–M7, L3, L5). Incorporates a
pre-implementation review of the tree: five corrections to the
remediation plan as written, prerequisites to close before coding, then
the work split (four parallel worktrees). Implementation itself MUST
happen in dedicated worktrees (`./scripts/worktree.py add <branch>`);
this file is docs-only on `master`.

Threat model: one hostile server reaches every client.

## 0. User-visible changes (UX contract — read first)

No new dialogs. Behavior changes:

- Non-http(s) chat links become inert (no `ACTION_VIEW` dispatch).
  `http`/`https` links open via system chooser (`ACTION_VIEW`), not an
  in-app WebView.
- Comment WebView stops loading remote images unless the existing
  external-images setting is on (default off). Other schemes blocked.
- Chat images stay sync-bounded, no pop-in (decided, slice C): data-URI
  decode stays on the UI thread but capped (4096 px / 16 MP two-pass);
  URL fetch already runs on `mExecutor` with bounded decode; avatars stay
  sync on the bind path (512 px two-pass) because `ChannelAdapter:122`
  and `ChannelListAdapter:381` require `Bitmap` synchronously. No layout
  or signature change.
- Comment remote-image gating follows the same setting; no per-message
  origin surfacing in this change.
- TTS actor names truncated and stripped of control characters; message
  notifications show plain stripped text (no raw HTML).
- New strings ship English-only initially (no translations in this change).

## 1. Corrections to remediation-plan.md (read first)

### C1 — M4 bypasses M5: WebView is a second image-fetch path

`AbstractCommentFragment.java:100,127` calls `loadData("text/html")`
with no settings lockdown. Remote `<img>` in comments fetches via
WebView, never touching the `MumbleImageGetter.java:191-192` gate.
Gating only `MumbleImageGetter` leaves the SSRF path open. Fix both
with one policy: WebView blocks network images (or gates on
`Settings.shouldLoadExternalImages()`), `MumbleImageGetter` enforces
the SSRF blocklist below. Alternatively block network images in
WebView outright.

### C2 — M6/M7 sync contract: "move off UI thread" changes layout behavior

`MumbleImageGetter.getDrawable` decodes data-URIs synchronously on the
UI thread (`MumbleImageGetter.java:153-177`), and `AvatarCache.get`
returns `Bitmap` synchronously on the adapter bind path
(`AvatarCache.java:87-122`, callers `ChannelAdapter.java:122`,
`ChannelListAdapter.java:381`). Bounding the decode (two-pass
`inJustDecodeBounds` + `inSampleSize`) alone kills the OOM primitive
without changing signatures. Full async (return null, decode on the
existing `mExecutor`, `notifyDataSetChanged` / adapter invalidation)
changes first-layout to pop-in. Decide per slice before coding; do not
half-migrate (sync signature with background decode = dropped images).

### C3 — M5 pre-connect IP check is best-effort (DNS TOCTOU)

A pre-connect `InetAddress.getAllByName(host)` check is racy: DNS
rebind between check and `url.openConnection()` defeats it, and
`HttpURLConnection` cannot pin the checked IP. Land best-effort check
plus manual redirect handling (`setInstanceFollowRedirects(false)`,
cap 5, per-hop scheme + IP check), and document the residual
explicitly. Reject userinfo (`user@host`).

### C4 — L3+L2 are one hunk, not two phases

`ServerInfoTask.java:53-67` never closes the `DatagramSocket` and
passes the full `byte[24]` to `ServerInfoResponse` ignoring
`responsePacket.getLength()`. The length guard cannot land without
touching that method, so include the try-with-resources drive-by here
(Phase 1 precedent: `HumlaSSLSocketFactory.java:57`), not in Phase 4.

### C5 — L5 surface is wider than the TTS actor

`MumlaMessageNotification.java:74,100-101` puts raw `getActorName()`
and raw HTML `getMessage()` into title/text/Inbox lines. Strip both
(same sanitizer as the TTS path), not just the TTS actor in
`MumlaService.java:384-408`.

## 2. Prerequisites (close before coding, not during)

- **P1 — avatar/downsample targets.** Read `ChannelAdapter.java:100-130`
  and `ChannelListAdapter.java:370-390` bind paths (async feasibility),
  `dialog_comment.xml` + `list_chat_item.xml` (view sizes for
  downsample targets), `Settings` image-pref wiring for WebView gating.
- **P2 — outgoing image budget.** `ChannelChatFragment.java:348,371`
  already caps sends (1600 px, server `getImageMessageLength`); receive
  caps can be smaller (chat longest-side ~4096 / ~16 MP, avatar ~512)
  without breaking interop.
- **P3 — test seam.** `unitTests.returnDefaultValues = true`
  (`app/build.gradle:140`, `libraries/humla/build.gradle:85`) stubs
  `BitmapFactory`/`WebView` on the JVM. Pure helpers
  (`ChatLinkPolicy`, SSRF host classifier, `calculateInSampleSize`,
  actor sanitizer, ping length guard) are unit-testable; WebView
  lockdown is manual-test only.

## 3. Work split — four parallel worktrees

Four slices, not six: M5+M6+M7 stay together because
`fetchURLImage:411-451` does SSRF fetch (M5) and unbounded
`decodeByteArray:439` (M6) in one method, and `getBase64Image:398-409`
shares the same decode path. Two owners on `MumbleImageGetter.java` is
a guaranteed conflict.

| Slice | Branch | Findings | Files owned | Tests owned |
|---|---|---|---|---|
| A | `phase2-chat-links` | M3 | `ChannelChatFragment.java` + new `util/ChatLinkPolicy.java` | new `ChatLinkPolicyTest` |
| B | `phase2-comment-webview` | M4 | `AbstractCommentFragment.java` only | manual (WebView unstubbable) |
| C | `phase2-image-pipeline` | M5+M6+M7 | `MumbleImageGetter.java` + `AvatarCache.java` + new bounded-decode helper | `MumbleImageGetterTest`, `AvatarCacheTest` |
| D | `phase2-ping-tts` | L3+L5 | `ServerInfoResponse.java`, `ServerInfoTask.java`, `MumlaService.java:383-415`, `MumlaMessageNotification.java`, actor sanitizer | new `ServerInfoResponseTest`, sanitizer test |

Each worktree forks `master`; no cross-slice file touches.

### Slice A — chat links (M3)

- New pure `ChatLinkPolicy.isAllowedUrl` (`java.net.URI`, http/https
  only); replace `URLSpan`s or install a custom `MovementMethod` at
  `ChannelChatFragment.java:625-626`; open via `ACTION_VIEW` chooser.
  Outgoing `LINK_PATTERN:101` is already http-only — incoming `href`
  is the bug. No `customtabs` dependency.
- Accept: `javascript:` / `intent:` / `file:` / `tel:` inert;
  http(s) opens externally; `./scripts/check.sh` green.

### Slice B — comment WebView (M4)

- Explicit `setJavaScriptEnabled(false)`, file/content access off,
  `setBlockNetworkImage` gated on the external-images setting,
  `WebViewClient.shouldOverrideUrlLoading` (both the API-24 and legacy
  overloads for minSdk 21) opening http(s) externally and blocking the
  rest. Keep `loadData` with null base (relative URLs do not resolve).
- Accept: JS/file/content off, remote images follow the setting,
  non-http(s) navigation blocked; manual verification on device.

### Slice C — image pipeline (M5+M6+M7)

- M5: manual redirects (cap 5), per-hop scheme + IP blocklist: 10/8,
  172.16/12, 192.168/16, 127/8, 169.254/16, `::1`, fe80::/10,
  fc00::/7, 0.0.0.0/`::`, multicast/reserved; recommended: block CGNAT
  100.64/10. Handle trailing-dot/case/IPv6 brackets; reject userinfo.
- M6/M7: two-pass `inJustDecodeBounds` + `inSampleSize` via a pure
  `calculateInSampleSize` helper; chat longest-side ~4096 / ~16 MP
  cap, avatar ~512. `LruCache` (`MumbleImageGetter.java:112`) is
  touched from UI + background threads — synchronize or document.
- Decided: sync-bounded everywhere (no signature change, no pop-in).
  Rationale: adapter bind paths require `Bitmap` synchronously; bounding
  alone kills the OOM primitive without an async migration. Residual:
  worst-case bounded-decode jank on the UI thread for data-URIs.

### Slice D — ping + TTS/notifications (L3+L5)

- L3 (+L2 drive-by, C4): `responsePacket.getLength() >= 24` guard at
  the call site, defensive `response.length >= 24` in
  `ServerInfoResponse.java:49-58` (throw funnels through the existing
  `catch (Exception)` to dummy), try-with-resources socket close.
- L5 (C5): `sanitizeActor` — Jsoup-strip, kill `[\p{Cntrl}]`/newlines,
  trim, cap ~64 chars; notification title/text/lines use stripped
  body. `jsoup:1.13.1` is available to unit tests.
- Accept: short/spoofed UDP reply yields dummy; raw HTML/control
  actors neutralized in TTS and notifications; `./scripts/check.sh`
  green.

## 4. Non-interaction contract (enforce before fan-out)

- Slice C MUST NOT change `MumbleImageGetter(Context[, listener])` /
  `getDrawable(String)` signatures — slice A instantiates it at
  `ChannelChatFragment.java:553`.
- Slices B and C share only the `shouldLoadExternalImages()` read; no
  code overlap.
- Slice D is fully disjoint. L3+L5 are combined in one worktree to
  avoid two worktrees churning `MumlaService` imports.

## 5. Test matrix (JVM unit tests unless noted)

| Case | Expects |
|---|---|
| `javascript:` / `intent:` / `file:` chat `href` | inert, no intent fired |
| `http`/`https` chat link | external chooser |
| Comment WebView (manual) | JS/file/content off, images follow setting, other schemes blocked |
| Remote `<img>` to RFC1918/loopback/link-local | blocked when images enabled |
| Redirect chain > 5 / redirect to private IP | stopped, blocked |
| Compressed bomb (small bytes, huge dimensions) | rejected or downsampled before full decode |
| Avatar > 512 target | downsampled to icon size |
| UDP ping reply < 24 bytes | dummy response, no parse |
| Actor with HTML/newlines/controls, long body | stripped, single-line, capped in TTS + notification |
| Existing suite | `./scripts/check.sh` green from each worktree |

## 6. Residuals and non-goals (explicit, not overlooked)

- M5 DNS TOCTOU (C3) — accepted, documented here.
- M6/M7 async decode declined (slice C): sync-bounded keeps
  `getDrawable`/`AvatarCache.get` signatures; worst-case bounded jank
  accepted over pop-in plumbing. Revisit only with adapter
  invalidation work.
- Secrets at rest (former C1, H6–H8, L1) out of scope per
  [secrets-at-rest-plan.md](secrets-at-rest-plan.md).
- No `customtabs` dependency, no per-message remote-origin surfacing,
  no WebView unit tests (platform-stubbed).
- `onImagePicked` local-decode path (`ChannelChatFragment.java:319-328`)
  untouched (user-picked content, not server-controlled).

## 7. Execution (worktree + commit + merge order)

- Create: `./scripts/worktree.py add phase2-chat-links`,
  `phase2-comment-webview`, `phase2-image-pipeline`, `phase2-ping-tts`
  (root stays on `master`; stagger `check.sh` runs — nix gradle is
  heavy in parallel).
- Commits via `scripts/commit.py` with the three-section body; every
  code commit leaves `./scripts/check.sh` green inside its worktree.
- No autonomous merging, pushing, or deletion (per `AGENTS.md`): leave
  branches and worktrees intact and unpushed, report for review.
- Suggested review/merge order: links, webview, ping-tts, images last
  (largest blast radius).
