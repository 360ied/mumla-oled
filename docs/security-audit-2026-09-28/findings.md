# Findings — security audit 2026-09-28

Scope: `app/` + `libraries/humla/` (UI, service/IPC, TLS/protocol, secret storage,
untrusted-input rendering, JNI/native, supply chain, build).

Conventions: severity Critical / High / Medium / Low / Info. Confidence
verified (directly read) / likely / possible. CWE IDs where applicable.
`[INFERENCE]` marks unobserved consequences. All paths repo-relative.

Count: 0 Critical, 2 High, 12 Medium, 16 Low, 2 Info. C1, H6, H7, H8, L1
withdrawn as noise — see [secrets-at-rest-plan.md](secrets-at-rest-plan.md) ("do not
fix"). H3–H5 downgraded to Medium (installed-app-only), H12 to Medium (no
demonstrated RCE); H9–H11 and M1/M8–M10/M12–M15 to Low (legacy, dead, local-only,
or hygiene). Cert export (H9) was reported by multiple slices — deduplicated here.

---

## High

### H1 — Missing TLS hostname verification on SSLSocket — High, verified

Files:

- `libraries/humla/src/main/java/se/lublin/humla/net/HumlaSSLSocketFactory.java:49-70`
  (context/factory), `129-137` (`checkServerTrusted`)
- `libraries/humla/src/main/java/se/lublin/humla/net/HumlaTCP.java:150-165` (handshake)

Evidence:

```java
mContext = SSLContext.getInstance("TLS");
...
mTCPSocket = mSocketFactory.createSocket(mHost, mPort, CONNECT_TIMEOUT);
scsf.setHostname(mTCPSocket, mHost);   // SNI only
mTCPSocket.startHandshake();
// wrapper:
mDefaultTrustManager.checkServerTrusted(chain, authType); // chain validity only
```

No `HostnameVerifier`, no `SSLParameters.setEndpointIdentificationAlgorithm`, no
manual SAN/CN-vs-`mHost` check (including the `InetAddress.getByName(host)` path and
the `createSocket(plainSocket, host, port, true)` timeout path).

Impact: any CA-valid certificate satisfies the handshake regardless of host. Network
attacker with any valid cert MITMs voice + server password + tokens despite a "green"
handshake. Compounds M2 (pinned certs trusted globally).

Fix: set endpoint identification (`HTTPS`) on the `SSLSocket` or verify leaf SAN/CN
against `mHost` (with IP-literal handling) post-handshake, fail closed. Regression test
with valid-for-wrong-host cert. CWE-297, CWE-298.

### H2 — Unbounded server-controlled TCP protobuf frame allocation — High, verified

File: `libraries/humla/src/main/java/se/lublin/humla/net/HumlaTCP.java:179-185`

Evidence:

```java
final short messageType = mDataInput.readShort();
final int messageLength = mDataInput.readInt();
final byte[] data = new byte[messageLength];
mDataInput.readFully(data);
if (messageType < 0 || ...) // validated AFTER allocation
```

`setSoTimeout(0)` (infinite) set just above at line 165. Catch set is
`SocketException`/`SSLHandshakeException`/`IOException` — misses
`NegativeArraySizeException` / `OutOfMemoryError` from the allocation.

Impact: pre-auth remote DoS. Malicious/failing server forces hundred-MB allocation
(OOM kill), negative length crashes the reader thread, slow sender stalls it forever.

Fix: max-frame cap (upstream ~1 MiB / protocol limit), reject negative/overlarge
before allocating, validate `messageType` first, keep a finite read timeout.
CWE-400, CWE-789.

---

## Medium

### H3 — Exported `MumlaService` allows third-party control of call state — Medium, verified

*Downgraded 2026-09-29: High→Medium — requires installed malicious app; own call-state availability only.*

Files:

- `app/src/main/AndroidManifest.xml:60-64`
- `app/src/main/java/se/lublin/mumla/service/MumlaService.java:838-866`

Evidence:

```xml
<service android:name=".service.MumlaService" android:exported="true" ... />
```

```java
if (ACTION_DISCONNECT.equals(action)) { onDisconnect(); ...
} else if (ACTION_MUTE.equals(action)) { onMuteToggled(); ...
} else if (ACTION_DEAFEN.equals(action)) { onDeafenToggled(); ...
} else if (ACTION_TOGGLE_OVERLAY.equals(action)) { onOverlayToggled(); ...
```

No permission guard, no caller check (`getCallingUid` / signature permission).

Impact: any installed app sends explicit intents to mute/deafen/disconnect/toggle-overlay
mid-call. Harassment + availability primitive; mute-state confusion aids social engineering.

Fix: `exported=false` or signature-level permission; validate caller for control actions.
CWE-276, CWE-862.

### H4 — Exported `HumlaService` CONNECT enables crash + attacker-driven connect — Medium, verified

*Downgraded 2026-09-29: High→Medium — requires installed malicious app; service-process DoS + self-inflicted connect config.*

Files:

- `libraries/humla/src/main/AndroidManifest.xml:29-35`
- `libraries/humla/src/main/java/se/lublin/humla/HumlaService.java:212-219,620-624`

Evidence:

```xml
<service android:name=".HumlaService" android:exported="true">
    <intent-filter>
        <action android:name="se.lublin.humla.ACTION_CONNECT"/>
```

```java
if (isConnect && (extras == null || !extras.containsKey(EXTRAS_SERVER))) {
    throw new RuntimeException(ACTION_CONNECT + " requires a server provided in extras.");
}
...
mServer = extras.getParcelable(EXTRAS_SERVER); // + cert, trust-store path below
```

Impact: any app crashes the service process on demand (`RuntimeException` from an
exported entry point) and otherwise supplies server parcel, client cert bytes, and
trust-store path — attacker-driven connect config, credential/phishing-adjacent.

Fix: `exported=false` or signature permission; return error instead of `throw`;
reject external trust-store paths. CWE-862, CWE-755.

### H5 — Exported TALK broadcast drives microphone state — Medium, verified

*Downgraded 2026-09-29: High→Medium — requires installed malicious app; documented Tasker/Automate surface, permission-guard at most.*

Files:

- `app/src/main/java/se/lublin/mumla/service/MumlaService.java:665-667`
- `app/src/main/java/se/lublin/mumla/service/ipc/TalkBroadcastReceiver.java:30-57`

Evidence:

```java
ContextCompat.registerReceiver(this, mTalkReceiver,
        new IntentFilter(TalkBroadcastReceiver.BROADCAST_TALK),
        ContextCompat.RECEIVER_EXPORTED);
```

```java
String status = intent.getStringExtra(EXTRA_TALK_STATUS);
if (status == null) status = TALK_STATUS_TOGGLE; // defaults to toggle
```

No sender/permission check.

Impact: any app broadcasts `se.lublin.mumla.action.TALK` to force mic on/off/toggle
while connected. Silent unmute is a voice-privacy violation; mute is a DoS on speech.

Fix: signature-level permission; verify sender in `onReceive`. `RECEIVER_NOT_EXPORTED` would break the documented Tasker/Automate surface.
CWE-862.

### H12 — Vendored Opus codec is EOL (1.1-beta) — Medium, verified

*Downgraded 2026-09-29: High→Medium — stale pin + remote sink confirmed, no demonstrated RCE.*

Files:

- `libraries/humla/src/main/jni/opus/version.mk:2` (`PACKAGE_VERSION = "1.1-beta"`)
- `libraries/humla/src/main/jni/audio_engine/OpusVoiceDecoder.cpp:71-78` (remote-input sink)
- Submodule pin `65471dd567beb6b1156dc292858c0a28ca55ca3e` (exact SHA — good hygiene, stale tree)

Impact: ~13 years of upstream correctness/security fixes missing, on a decoder fed
attacker-controlled voice packets. Treat remote Opus input as untrusted until rebased.

Fix: rebase to supported 1.5.x, keep exact-SHA pin, regression-test voice interop;
fuzz `decodeFloat` / `packetSampleCount` paths meanwhile. CWE-1104, CWE-937.

### M2 — User-pinned server cert trusted for any host — Medium, verified

Files:

- `app/src/main/java/se/lublin/mumla/app/MumlaActivity.java:202-247` (TOFU allow-and-pin)
- `libraries/humla/src/main/java/se/lublin/humla/net/HumlaSSLSocketFactory.java:129-137`

TOFU dialog itself is reasonable (shows subject/dates/SHA-1+SHA-256). But Allow stores
`trustStore.setCertificateEntry(alias=hostname, x509)` while the wrapper consults the
custom manager as a **global** extra anchor with no hostname binding — a cert pinned
for host A is accepted for host B. Alias=host also silently overwrites prior pins.
First-connection MITM therefore persists and is cross-host; compounds H1.

Fix: bind pins per-host (hostname→expected fingerprint/SPKI, compare each connect),
surface which validation step failed, re-verify hostname even for pinned entries.
CWE-295.

### M3 — Chat HTML links have no URL-scheme allowlist — Medium, verified

File: `app/src/main/java/se/lublin/mumla/channel/ChannelChatFragment.java:625-626`

```java
messageText.setText(HtmlCompat.fromHtml(message.getBody(), FROM_HTML_MODE_LEGACY, mImageGetter, null));
messageText.setMovementMethod(LinkMovementMethod.getInstance());
```

Server-controlled body rendered as HTML; default `URLSpan` dispatches any `href` scheme
to `ACTION_VIEW` — `javascript:` / `intent:` / `file:` / `tel:` or lookalike display
text — with no `TagHandler` sanitization or scheme allowlist.

Fix: intercept clicks (custom MovementMethod / URLSpan replacement), allowlist
http/https only, open via Custom Tab or chooser. CWE-20, CWE-829, CWE-1021.

### M4 — Server HTML in comment WebView without hardening — Medium, verified

File: `app/src/main/java/se/lublin/mumla/channel/comment/AbstractCommentFragment.java:100,127`

```java
mCommentView.loadData(comment, "text/html", "UTF-8");
```

No observed settings lockdown (JS/file/content access), no `WebViewClient` URL
interception. Server-controlled channel descriptions / user comments (+ edit-preview
rendering raw editor HTML) enable phishing/defacement content and automatic
remote-subresource fetches. No JS bridge/RCE observed.

Fix: explicitly disable JS, file/content access; block network images or gate on the
external-images setting; `WebViewClient` opening http(s) externally, blocking other
schemes. CWE-20, CWE-200, CWE-829.

### M5 — Remote `<img>` fetch allows intranet/SSRF-style requests when enabled — Medium, verified

File: `app/src/main/java/se/lublin/mumla/util/MumbleImageGetter.java:191-192` (gate),
`411-440` (fetch)

Only scheme check is http/https; `setInstanceFollowRedirects(true)`; no private-IP /
link-local / loopback block. Mitigated by default-off (`DEFAULT_LOAD_IMAGES = false`)
and opt-in setting — but once enabled, malicious server `<img src>` leaks client IP
and probes intranet.

Fix: when enabled, block RFC1918/link-local/loopback, cap redirects, surface remote
origin per message. CWE-918, CWE-200.

### M6 — Unbounded bitmap decode (decompression bomb / UI-thread ANR) — Medium, verified

File: `app/src/main/java/se/lublin/mumla/util/MumbleImageGetter.java:398-409,434-441`

```java
return BitmapFactory.decodeByteArray(data, 0, data.length);
return BitmapFactory.decodeByteArray(src, 0, src.length); // data: URI, sync on UI thread in getDrawable
```

10 MB compressed cap (`MAX_LENGTH`) does not bound decompressed dimensions; no
`inJustDecodeBounds` / dimension cap / `inSampleSize` downsampling. Small compressed
bomb → OOM/ANR.

Fix: two-pass decode (bounds first, reject absurd dimensions, downsample), move
data-URI decode off UI thread. CWE-400, CWE-789.

### M7 — Avatar texture decoded without size caps on UI path — Medium, verified

File: `app/src/main/java/se/lublin/mumla/channel/AvatarCache.java:104-108`

```java
Bitmap bitmap = BitmapFactory.decodeByteArray(texture, 0, texture.length);
```

Server-controlled avatar bytes decoded full-size on the bind path. 4 MB LRU bounds the
cache, not the decode. Hostile server pushes huge avatars → OOM/jank.

Fix: max-bytes + max-dimension checks, downsample to icon size, decode off UI thread.
CWE-400, CWE-789.

### M11 — RNNoise model weights downloaded without integrity verification — Medium, verified

Files:

- `libraries/humla/build.gradle:88-135`
- `libraries/humla/src/main/jni/Android.mk:68`

```groovy
def modelUrl = new URL("https://media.xiph.org/rnnoise/models/rnnoise_data-${modelHash}.tar.gz")
modelUrl.withInputStream { ins -> tarGz.withOutputStream { outs -> outs << ins } }
// no digest/signature check; filename hash is not verification
```

Downloaded C compiles into `libhumlaaudio` (`LOCAL_SRC_FILES :=
rnnoise-build/generated/rnnoise_data.c`) and a derived `.bin` ships as an asset — a
MITM/compromised mirror injects code + asset.

Fix: vendor SHA-256 in-repo, verify before `tarTree`, fail closed; ideally commit
generated `rnnoise_data.c/h` or fetch from pinned checksummed mirror.
CWE-494, CWE-829.

### M16 — Release silently falls back to debug signing key — Medium, verified

File: `app/build.gradle:97-109`

```groovy
if (android.hasProperty("signingConfigs") && signingConfigs.hasProperty("release")) {
    signingConfig = signingConfigs.release
} else {
    signingConfig = signingConfigs.debug
}
```

Optional external `signing.gradle` not committed (good for secrets) + silent fallback
(bad): a published artifact can be signed by the public debug key and impersonated by
anyone holding one.

Fix: fail closed — require `signingConfigs.release` for release builds (throw when
missing); document expectations. CWE-798.

---

## Low

### H9 — Private-key export to shared/external storage without re-auth — Low, verified

*Downgraded 2026-09-29: High→Low — explicit user tap; SAF user-picked URI on modern devices; pre-R shared-storage path legacy-only.*

Files: `app/src/main/java/se/lublin/mumla/preference/CertificateExportActivity.java:105` (SAF launch), `143-148` (classic path), `179-190` (write)

Evidence:

```java
File mumlaDirectory = new File(storageDirectory, EXTERNAL_STORAGE_DIR); // /sdcard/Mumla
...
File outputFile = new File(mumlaDirectory, certificate.getName());
...
byte[] data = mDatabase.getCertificateData(cert.getId()); bos.write(data);
```

Legacy path writes raw PKCS#12 to shared external storage (pre-R); SAF path writes to
any user-picked URI. No re-authentication, no re-encryption with a fresh password.
Cert name used verbatim as filename (see L6).

Impact: private key lands world-readable to storage/media-permission holders, survives
uninstall; self-signed generated certs (no PBE password) export as effectively plaintext keys.
User intent likely present (export is explicit) but consequence (key material to shared
storage, unencrypted) is not conveyed or gated.

Fix: SAF-only, explicit confirmation + warning, optional re-encryption with
user-supplied password, sanitize filename, never leave shared-storage copies.
CWE-312, CWE-276, CWE-922.

### H10 — Signed-overflow bypass in `nativeProcessFrame` bounds check — Low, verified

*Downgraded 2026-09-29: High→Low — sole in-tree caller passes offset 0; local bug-class, no remote path.*

Files:

- `libraries/humla/src/main/jni/audio_engine/NativeAudioInputEngineJni.cpp:179-182`
- `libraries/humla/src/main/java/se/lublin/humla/audio/NativeAudioInputEngine.java:112-115`

Evidence:

```cpp
if (ctx == nullptr || ... || length <= 0 || offset < 0) return;
jsize arrayLen = env->GetArrayLength(pcmArray);
if (offset + length > arrayLen) return;   // 32-bit signed add: wraps negative, passes
jshort* pcmPtr = env->GetShortArrayElements(pcmArray, nullptr);
ctx->engine->processFrame(pcmPtr + offset, ...); // OOB read past array end
```

Java caller only guards `length > 0`, so hostile `offset` reaches native code.
(Current callers are internal, so exploitability is local/bug-class, not remote —
still memory-unsafe in shipped `.so`.)

Fix: 64-bit check `if ((int64_t)offset + (int64_t)length > arrayLen) return;` as
`nativeRender` already does; mirror guard in Java `processFrame()`.
CWE-190, CWE-129.

### H11 — Legacy JavaCPP JNI shims copy arrays with no bounds checks — Low, verified

*Downgraded 2026-09-29: High→Low — zero in-tree Java callers; dead code, delete rather than harden.*

File: `libraries/humla/src/main/jni/jniopus.cpp:407-420` (representative), `481-509+`
(same shape through ~830); pattern repeats for Short/Int/Long/Float/Double, get+put

Evidence:

```cpp
signed char* ptr0 = arg0 == NULL ? NULL : (jbyte*)env->GetPrimitiveArrayCritical(arg0, NULL);
memcpy(ptr0 + arg1, ptr, arg2 * sizeof(*ptr0));   // arg1=offset, arg2=count: no GetArrayLength check
```

Also no null-check on `GetPrimitiveArrayCritical`, no overflow-safe offset+count.

Impact: Java-reachable OOB read/write in native code. No Java peer usage found (only
ProGuard keeps) — likely dead attack surface that can simply be deleted.

Fix: preferred — delete `jniopus` JavaCPP shims + `javacpp:0.7` dep, keep only the Opus
codec. Else add `GetArrayLength` + int64 offset+count validation before every `memcpy`.
CWE-120, CWE-129.

### M1 — TLS versions/ciphers left at device defaults — Low, verified

*Downgraded 2026-09-29: Medium→Low — modern devices negotiate TLS 1.2+; residual is API-21-era peers only.*

File: `libraries/humla/src/main/java/se/lublin/humla/net/HumlaSSLSocketFactory.java:50`;
SDK levels `app/build.gradle:68`, `libraries/humla/build.gradle:76` (`minSdkVersion 21`)

`SSLContext.getInstance("TLS")` with no `setEnabledProtocols` /
`setEnabledCipherSuites` anywhere. On old devices (minSdk 21) negotiates TLS 1.0/1.1
or weak suites.

Fix: restrict to TLSv1.2+ (1.3 where available) + modern cipher list post-creation.
CWE-327, CWE-757.

### M8 — Import passphrase + key bytes retained as Strings and in instance-state Bundle — Low, verified

*Downgraded 2026-09-29: Medium→Low — reader needs system_server/root/dump; low-value import password.*

File: `app/src/main/java/se/lublin/mumla/preference/CertificateImportActivity.java:103-140,193-237`

`char[]` immediately `new String(password)`; password + full PKCS#12 bytes + field text
cached in `onSaveInstanceState` (`STATE_CERT_BYTES`, `STATE_PREVIOUS_PASSWORD`).
Bundle contents live in `system_server` Binder memory, parcelled to disk on process
death; immutable `String`s can't be wiped (the `Arrays.fill(passChars, '\0')` is
theater while `String` copies survive) and linger in heap dumps / bug reports.

Fix: `char[]`/`ByteBuffer` end-to-end, zero on completion, never put secrets/key bytes
in Bundles/Intents/fields; re-read file on rotation. CWE-312, CWE-316.

### M9 — Passwords + private-key bytes passed/held via Intent extras and immutable Strings — Low, verified

*Downgraded 2026-09-29: Medium→Low — intra-app explicit Intent; privileged Binder/dump reader only.*

Files:

- `app/src/main/java/se/lublin/mumla/app/ServerConnectTask.java:53-101`
- `libraries/humla/src/main/java/se/lublin/humla/HumlaService.java:123-131, 620-631`
- `libraries/humla/src/main/java/se/lublin/humla/model/Server.java:82-100`

Connect `Intent` carries full PKCS#12 + password, `Server` parcel (with password),
tokens, trust-store password into exported `HumlaService`; retained as
`String`/`byte[]` fields (`HumlaService`/`HumlaConnection`) with no wipe;
`Server.writeToParcel` writes password as `String`. Binder parcels live in
`system_server`, visible in dumps; root/bug-report readers observe them.

Fix: pass by handle (content URI / one-shot binder / KeyStore alias), `char[]` +
zero after `KeyManagerFactory.init`, `exported=false` or permission on
`ACTION_CONNECT`, no `Parcelable` passwords. CWE-312, CWE-319, CWE-200.

### M10 — Notifications expose content on lockscreen — Low, verified

*Downgraded 2026-09-29: Medium→Low — message-channel PUBLIC claim refuted (no flag in file); needs physical lockscreen proximity.*

Files:

- `app/src/main/java/se/lublin/mumla/service/MumlaConnectionNotification.java:327,373`
- `app/src/main/java/se/lublin/mumla/service/MumlaMessageNotification.java:100-101`
  (raw actor/message content; no `setVisibility` call in file — channel default)

```java
chan.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
builder.setVisibility(NotificationCompat.VISIBILITY_PUBLIC);
...
.setContentTitle(message.getActorName()).setContentText(message.getMessage())
```

Connection notification is `PUBLIC` and shows on lockscreen; the message notification sets no visibility flag. Either way the reader needs physical lockscreen proximity.

Fix: `PRIVATE` visibility on the connection channel/builder, redact lockscreen content, add opt-out. CWE-200.

### M12 — JavaCPP 0.7 is ancient/unmaintained — Low, verified

*Downgraded 2026-09-29: Medium→Low — dead keeps, no peer usage; delete with H11.*

Files: `libraries/humla/build.gradle:39`, `app/proguard-rules.pro:18-20`

`implementation 'com.googlecode.javacpp:javacpp:0.7'` (~2012/14-era); no Java peer
usage found, only ProGuard keeps — the generated JNI in H11 is this dependency's
payload.

Fix: remove dep + `jniopus` shims if unused (preferred); else upgrade to maintained
JavaCPP and regenerate. CWE-937.

### M13 — jsoup 1.13.1 is stale with known follow-on fixes — Low, verified

*Downgraded 2026-09-29: Medium→Low — parse+text() only, no HTML sink at this call site.*

Files: `app/build.gradle:155`, `app/src/main/java/se/lublin/mumla/service/MumlaService.java:386`

2020-era pin processing remote chat HTML (`Jsoup.parseBodyFragment(...)`).
Current use (parse + `text()`) limits but doesn't eliminate exposure to post-1.13.1
XSS-bypass/DoS fixes in 1.14.x/1.15.x+.

Fix: upgrade to supported 1.17+/1.18+, re-test chat rendering. CWE-937, CWE-79.

### M14 — MiniDNS 0.3.4 is stale — Low, verified

*Downgraded 2026-09-29: Medium→Low — stale dep, no vuln demonstrated; hygiene.*

File: `libraries/humla/build.gradle:41-42`

`minidns-hla:0.3.4` + `minidns-android21:0.3.4` (2021-era) drive SRV resolution
(`ResolverApi` import); current line is 1.x with protocol/security fixes.

Fix: upgrade to maintained 1.x, verify DoT/SRV on Android 21+. CWE-937.

### M15 — NDK 25 outdated; no explicit native hardening flags — Low, verified

*Downgraded 2026-09-29: Medium→Low — hardening rides toolchain defaults; no break demonstrated.*

Files: `libraries/humla/build.gradle:57`, `flake.nix:39`, `libraries/humla/src/main/jni/Android.mk:20-21`

Pinned `25.1.8937393` (late 2022), two LTS generations behind 27/28 lines. `COMMON_CFLAGS`
is `-ffunction-sections -fdata-sections -fvisibility=hidden` only — hardening rides
entirely on toolchain defaults; `APP_PLATFORM android-21`.

Fix: NDK 27 LTS (or current 28), keep flake/build.gradle in sync, add explicit
`-fstack-protector-strong -D_FORTIFY_SOURCE=2 -Wl,-z,RelRO,-z,Now`. CWE-937.

### L2 — Unclosed trust-store / ping sockets and streams — Low, verified

Files:

- `libraries/humla/src/main/java/se/lublin/humla/net/HumlaSSLSocketFactory.java:55-62`
  (`FileInputStream` not in try-with-resources)
- `app/src/main/java/se/lublin/mumla/servers/ServerInfoTask.java:52-77`
  (`DatagramSocket` never closed; blanket `catch (Exception)`)

Resource exhaustion at scale; also keeps file descriptors/sockets alive longer than
needed around trust material.

Fix: try-with-resources / `finally` close; close ping socket per attempt.

### L3 — Server ping reply parsed without length check — Low, verified

Files:

- `app/src/main/java/se/lublin/mumla/servers/ServerInfoResponse.java:49-60`
- `app/src/main/java/se/lublin/mumla/servers/ServerInfoTask.java:52-77`

```java
ByteBuffer buffer = ByteBuffer.wrap(response);
mVersion = buffer.getInt(); mIdentifier = buffer.getLong(); ... // 24 B assumed
```

`ServerInfoTask` allocates a fixed `new byte[24]` and passes the buffer unsliced,
so short/spoofed UDP replies yield trailing-zero garbage with no length validation
(no `BufferUnderflowException` path — that mechanism was misdescribed).
Availability/display nuance, not RCE.

Fix: `responsePacket.getLength() >= 24` guard before parsing (plus L2 socket close). CWE-20.

### L4 — Unauthenticated `mumble://` link prefills credentials — Low, verified

Files: `app/src/main/AndroidManifest.xml:70` (exported) + `83-91` (`mumble://` filter),
`libraries/humla/src/main/java/se/lublin/humla/util/MumbleURLParser.java:34,42-52`,
`app/src/main/java/se/lublin/mumla/app/MumlaActivity.java:347+` (VIEW → connect dialog)

Any app/website can fire a `mumble://` VIEW intent prefilling attacker host/credentials.
Gated by a connect-confirmation dialog (show host prominently; confirm embedded
passwords) — phishing primitive, not silent connect. CWE-939.

### L5 — Untrusted actor/message text in TTS and notifications — Low, verified

Files:

- `app/src/main/java/se/lublin/mumla/service/MumlaService.java:383-415`
- `app/src/main/java/se/lublin/mumla/service/MumlaMessageNotification.java:72-103`

Body is Jsoup-stripped for TTS (good) but server-controlled actor name concatenated
raw and spoken; raw HTML-bearing strings flow into notification title/text/lines.
Annoyance / social-engineering, not code execution. `PendingIntent`s themselves safe
(explicit + `FLAG_IMMUTABLE`).

Fix: strip/length-cap actor names for TTS (neutralize newlines/control), render
notification text as plain stripped text. CWE-20.

### L6 — Residual hardening gaps — Low, verified

- `libraries/humla/src/main/jni/crypto/NativeCryptStateJni.cpp:208`:
  `NewByteArray(length + 4)` in 32-bit arithmetic — `srcLen` check bounds it in practice,
  but add explicit `length > INT_MAX-4` guard / 64-bit math. DoS-shaped, not heap overflow.
- `CertificateExportActivity.java:148` + SAF `documentCreator.launch(certificate.getName())`:
  cert name used verbatim as filename — sanitize (path separators, reserved names).
- `app/src/main/AndroidManifest.xml:52`: `requestLegacyExternalStorage="true"` on a
  target-36 app + `WRITE/READ_EXTERNAL_STORAGE` (maxSdk 29/32) — drop legacy flag/path.

---

## Info

### I1 — OCB voice tag truncated to 24 bits (upstream protocol property) — Info, verified

File: `libraries/humla/src/main/jni/crypto/CryptStateOCB2.cpp:172-310`

`encrypt` emits `iv[0]` + 3 tag bytes; `decrypt` recomputes and compares 3 bytes,
restoring IV on failure (correct fail-closed); 256-entry replay window present —
matches upstream Mumble OCB2 design. Per-packet forgery probability inherently
2^-24. Key/nonce sizes enforced. No code bug; document that UDP voice
confidentiality/integrity rests on the TLS key exchange. No action without breaking
wire compat.

### I2 — Sensitive screens not screenshot-protected — Info, verified

No `FLAG_SECURE` anywhere in repo (grep: zero hits); e.g.
`CertificateImportActivity.java:88` region. Credential/key screens are screenshottable
by design default. Add `FLAG_SECURE` to credential windows only if in threat model.
CWE-200.

---

## Cross-reference: threat → findings

| Attacker | Paths |
|---|---|
| Root/physical reader | withdrawn (former C1, H6, H7, H8, L1 — see secrets-at-rest-plan.md) |
| Network (any valid CA cert) | H1, M1, M2, I1 |
| Malicious server | H2, M3–M7, L3, L5 |
| Malicious local app | H3, H4, H5, M9, L4 |
| Build/mirror compromise | M11, H12, M12–M15 |
| User error / device hygiene | H9, M8, M10, L6, I2 |
| Release process | M16 |