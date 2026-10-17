# 07 — Desktop-only subsystem sweep (`src/mumble/`)

Systematic per-file inventory of upstream `src/mumble/` so that anything lanes
01–06 missed is recorded. Read-only; no Mumla OLED files modified. Factual
only; no recommendations, no judgments.

## 1. Upstream directory contents (verified this session)

Top-level `src/mumble/` code files (45 `mumble_*.ts` locale files collapsed):

About, Accessibility, ACLEditor (+ UI), ALSAAudio, API, ApplicationPaletteTemplate,
AppNap (+ macOS), ASIOInput (+ UI), Audio, AudioConfigDialog, AudioInput (+ UI),
AudioOutput (+ UI), AudioOutputBuffer, AudioOutputCache, AudioOutputSample,
AudioOutputSpeech, AudioOutputToken, AudioPreprocessor, AudioStats (+ UI),
AudioWizard (+ UI), BanEditor (+ UI), Cert (+ UI), ChannelFilterMode, ClientUser,
CMakeLists, ConfigDialog (+ UI), ConfigWidget, ConnectDialog (+ UI, edit UI),
CoreAudio, CrashReporter, CustomElements, Database, DBus, DeveloperConsole,
EchoCancelOption, EnumStringConversions, G15LCDEngine (helper, lglcd, unix),
GKey, Global, GlobalShortcut (macOS, unix, win, base, UI, buttons UI, target UI,
types), JackAudio, JSONSerialization, LCD (+ UI), LegacyPlugin,
ListenerVolumeSlider, Log (+ UI), LookConfig (+ UI), main, MainWindow (+ UI),
ManualPlugin (+ UI), Markdown, MenuLabel, Messages, MumbleApplication,
NetworkConfig (+ UI), os (early-win, macOS, unix, win), OSS, Overlay (base, UI,
unix, win), OverlayClient, OverlayConfig, OverlayEditor (+ UI, scene,
positionable item, text, user, user-group), PAAudio, PathListWidget, PipeWire,
Plugin, PluginConfig (+ UI), PluginInstaller (+ UI), PluginManager,
PluginManifest, PluginUpdater (+ UI), PositionalAudioViewer (+ UI),
PositionalData, PTTButtonWidget (+ UI), PulseAudio, QtWidgetUtils, QuitBehavior,
RichTextEditor (+ UI, link UI), Screen, SearchDialog (+ UI), ServerHandler,
ServerInformation (+ UI), Settings, SettingsKeys, SettingsMacros,
SharedMemory (base, unix, win), SocketRPC, SvgIcon, TalkingUI (base, component,
container, entry, selection), TaskList, TextMessage (+ UI),
TextToSpeech (base, macOS, unix, win), ThemeInfo, Themes, ThemeSwitch (macOS),
Tokens (+ UI), Translations, Usage, UserEdit (+ UI), UserInformation (+ UI),
UserListModel, UserLocalNicknameDialog (+ UI), UserLocalVolumeSlider,
UserLockFile (win + header), UserModel, UserView, VersionCheck, ViewCert,
VoiceRecorder (+ UI, dialog UI), VolumeSliderWidgetAction, WASAPI (+
notification client), WebFetch, WinGUIDs, XboxInput, XMLTools, Zeroconf,
MumbleAPI structs, resource/packaging files, `mumble_*.ts` (45 locale files).

`src/mumble/widgets/`: AccessibleQGroupBox, BanDialog (+ UI), ClickableLabel,
CompletablePage, EventFilters, FailedConnectionDialog (+ UI), MUComboBox,
MultiColumnTreeWidget, MultiStyleWidgetWrapper, ResponsiveImageDialog,
RichTextItemDelegate, SearchDialogItemDelegate, SearchDialogTree,
SemanticSlider, TrayIcon.

`src/mumble/qttranslations/`: license, `qt_it/nl/tr/zh_CN.ts`,
translations config.

Mumla OLED file inventory (verified this session): `app/.../AppDataStore.kt`,
`Settings.kt` (+ module, repository), `app/` (base activity, dialog utils,
drawer adapter, startup action, main activity, application, connect task),
`channel/` (access-token fragment, avatar cache, channel adapter/chat
fragment/fragment/list adapter/list fragment/menu, chat-target callback,
chat-target provider, click listeners, permissions popup, server-info
fragment, user menu), `channel/comment/` (abstract, channel-description, user
comment), `db/` (DAOs, database certificate, module, entities, database, room
database), `drawable/CircleDrawable.kt`, `preference/` (about, appearance,
audio, authentication, certificate export/generate/import/select, data-store
bridge, general, key-select dialog + fragment, generate task, base fragment,
seek-bar dialog + fragment, server-certificate clear, settings activity),
`servers/` (favourite adapter/list, server adapter/edit/info-response/info
task), `service/` (chat message, service interfaces, connection notification,
hot corner, message notification, overlay, service, overlay layout),
`service/ipc/TalkBroadcastReceiver.kt`, `util/` (bitmap utils, chat-link
policy, HTML utils, service fragment/provider, model utils, image getter,
trust store, notification sanitizer, pinned TLS factory, SSRF policy);
resources: token fragment, server-info fragment, news dialog, settings XMLs,
night themes, 30 locale `values-*/strings.xml`; `libraries/humla/.../audio/`
(input, output, Bluetooth SCO manager, invalid-sample-rate exception, native
input/output engines), `audio/inputmode/` (activity, continuous, toggle,
interface), `model/` (channel, channel interface, message, message interface,
server-settings, server-settings interface, talk state, user, user interface,
whisper-target family), `net/` (crypt state, frame validator, handshake
failure, certificate generator, connection, SSL factory, TCP, TCP/UDP message
types, UDP, packet buffer, permissions, hostname verifier), `protocol/` (audio
handler, TCP/UDP listeners, model handler), `util/` (config delta, callbacks,
disconnect/network/logger/observer interfaces and implementations, exception,
message formatter, URL parser, voice-target mode); native `jni/audio_engine/`
(adaptive leveler, input/output engines, biquad filter, hysteresis VAD,
native JNI bridges, Opus encoder/decoder, pre-speech ring, RNNoise processor,
soft limiter, Speex jitter).

## 2. Exhaustive sweep table

Conventions: **Absent** = zero matches in `app/src/main/java` plus
`libraries/humla/src/main/java` for the named patterns (case-insensitive group
grep, verified this session). Verified line citations are given where probed;
other paths are cited as inventoried.

| Upstream file / group | Purpose (1 line) | Mumla OLED counterpart or Absent | Notes |
|---|---|---|---|
| About | About-dialog version/credits display | `preference/AboutSettingsFragment.kt` (inventoried) | About exists as a settings fragment, not a dialog; shows the version string (name/code, probed) |
| Accessibility | Qt accessibility helpers/focus behavior | Absent | No accessibility match in app+humla grep |
| ACLEditor (+ UI) | Channel ACL editor dialog (permission rules UI) | Absent | No match; only the read-side permissions popup and permission bits exist |
| ALSAAudio | Linux ALSA audio backend | Absent | Android uses `AudioRecord`/`AudioTrack` (`AudioInput.kt`, `AudioOutput.kt`) |
| API (+ structs) | Public C plugin/RPC API surface | Absent | No plugin/RPC match in app+humla grep |
| ApplicationPaletteTemplate | Palette stylesheet helper | Absent | Theming is apply-theme plus night themes |
| AppNap (+ macOS) | macOS App Nap inhibition | Absent | Desktop-OS-only |
| ASIOInput (+ UI) | Windows pro-audio input backend + config UI | Absent | Input is `AudioRecord` (`audioRecord: AudioRecord?`) |
| Audio | Abstract audio base (device enumeration helpers) | `audio/AudioInput.kt`, `audio/AudioOutput.kt` | Single Android backend instead of abstract multi-backend |
| AudioConfigDialog | Audio settings page (desktop) | `preference/AudioSettingsFragment.kt` | Settings UI is per-fragment XML |
| AudioInput (+ UI) | Desktop mic capture pipeline + UI | `audio/AudioInput.kt` | Nullable `AudioRecord`; re-create on dead object |
| AudioOutput (+ UI) | Desktop speaker playback pipeline + UI | `audio/AudioOutput.kt` | Nullable `AudioTrack`; low-latency stream mode |
| AudioOutputBuffer | Per-user decoded-audio buffer base | `jni/audio_engine/` decoder/engine (inventoried) | Decode path is native; no per-file Java buffer class |
| AudioOutputCache | Disk/memory cache of decoded audio | Absent | No match; no decoded-audio cache |
| AudioOutputSample | One-shot positional sample playback | Absent | No match; no sample-playback class |
| AudioOutputSpeech | Per-talker speech stream renderer | `audio/AudioOutput.kt` (partial) | Java owns the track lifecycle; per-talker mixing lives in the native engine, not a speech subclass |
| AudioOutputToken | Sentinel token for the output queue | Absent | No match |
| AudioPreprocessor | Speex preprocessor (denoise/AGC/VAD glue) | Native DSP chain (adaptive leveler, hysteresis VAD, RNNoise processor, biquad, soft limiter, pre-speech ring) | Custom native chain replaces the Speex preprocessor; RNNoise included |
| AudioStats (+ UI) | Live audio statistics dialog | Absent | No match |
| AudioWizard (+ UI) | First-run mic setup wizard | Absent | No match |
| BanEditor (+ UI), widgets/BanDialog | Server ban-list editor (+ shared ban dialog widget) | Absent | No match |
| Cert (+ UI) | Client certificate wizard/view (desktop) | Certificate generate task plus generate/import/export/select activities | Certificate lifecycle split across four activities plus a server-certificate-clear activity |
| ChannelFilterMode | Channel-tree filter mode enum | Absent (enum) | Filtering folded into the list fragment/adapters; no such enum file |
| ClientUser | Client-side user entity (mute/volume/local state) | `model/User.kt` + `IUser.kt` | Java model layer; no per-user local volume slider (see listener-slider row) |
| CMakeLists (mumble/) | Desktop client build definition | Absent (not applicable) | Android builds via the app/library Gradle files |
| ConfigDialog (+ UI), ConfigWidget | Tabbed settings dialog framework + page base | `preference/SettingsActivity.kt` + base fragment + preference headers XML | AndroidX preference headers replace the Qt tab framework |
| ConnectDialog (+ UI, edit UI) | Server browser/connect dialog + edit sub-form | Favourites list fragment, server-edit fragment (dialog), connect task | Favourites fragment, dialog edit fragment, connect task classes; ping via the server-info task |
| CoreAudio | macOS CoreAudio backend | Absent | Android backend only |
| CrashReporter | Desktop crash-report upload dialog | Absent | No match |
| CustomElements | Custom Qt tree/delegate widgets | Absent | Drawer/channel rows are drawer/channel adapters |
| Database | SQLite favorites/tokens store (desktop) | `db/MumlaDatabase.kt`, `MumlaRoomDatabase.kt` | Database interface plus Room implementation; tokens getters/add/remove |
| DBus | Linux D-Bus remote-control interface | Absent | No match |
| DeveloperConsole | In-app JS developer console | Absent | No match |
| EchoCancelOption | Echo-canceller option widget | Absent | No match; echo handling is in the native audio engine, no such option widget |
| EnumStringConversions | Enum↔string helpers | Absent | No equivalent file |
| G15LCDEngine (helper, lglcd, unix) | Logitech G15 LCD integrations | Absent | No match |
| GKey | Logitech G-key macro support | Absent | No G-key-symbol match outside DataStore key-helper names (`longKey`/`stringKey` contain the substring); PTT keys via the key-select preference |
| Global | Global singleton (all desktop state) | `app/MumlaApplication.kt` + `HumlaService.kt` | Application class; service state in the service class |
| GlobalShortcut (all platforms, buttons, target, types) | OS-wide hotkey capture + config UI | `service/ipc/TalkBroadcastReceiver.kt` (partial) | Broadcast-receiver talk class; key selection via the key-select preference/dialog; no OS-global hotkey capture |
| JackAudio | JACK pro-audio backend | Absent | Android backend only |
| JSONSerialization | JSON config import/export helpers | Absent | No match |
| LCD (+ UI) | Generic LCD display page | Absent | No match; no LCD support |
| LegacyPlugin | Legacy (pre-1.4) game-link plugin loader | Absent | No legacy-plugin/manager match; no game-link plugins at all |
| ListenerVolumeSlider | Per-listener volume slider | Absent | No match |
| Log (+ UI) | Log window + chat-window filters/search | `channel/ChannelChatFragment.kt` (+ service chat-log tests; partial) | Chat exists as a fragment; service-side log tests reference the service log; no desktop-style filter/search config UI, no Log-window class (only the `HumlaLogger` utility interface), no search dialog |
| LookConfig (+ UI) | Skin/QSS theme config page | `app/MumlaApplication.kt` apply-theme + night themes (partial) | Day/night Material themes only — no user-loaded skins |
| main | Desktop entry point | `app/MumlaActivity.kt` (entry activity) | Single activity; process entry is the Android manifest |
| MainWindow (+ UI) | Main window (menus, docks, tray) | `app/MumlaActivity.kt` + drawer adapter (partial) | Single-activity plus nav drawer replaces the multi-dock main window; no tray (see tray-icon row) |
| ManualPlugin (+ UI) | Manual (link-file) positional plugin UI | Absent | No match; no positional-audio plugins |
| Markdown | Markdown-to-HTML chat renderer | `util/HtmlUtils.kt` + `MumbleImageGetter.kt` (partial) | HTML-utils object; image getter with SSRF guards; no Markdown dialect parser |
| MenuLabel | Menu header label widget | Absent | Android menus are XML |
| Messages | Message-box helpers | `app/DialogUtils.kt` | Dialog helper file exists; scope not line-probed |
| MumbleApplication | QApplication subclass (events, theme) | `app/MumlaApplication.kt` | Application class with theme apply on theme change |
| NetworkConfig (+ UI) | Proxy/network settings page | `preference/GeneralSettingsFragment.kt` (partial) | No dedicated proxy UI probed; TLS handled by the trust store plus pinned factory |
| os (early-win, macOS, unix, win) | Per-OS init/helpers | Absent | Android lifecycle replaces OS init; no OS-specific entry files |
| OSS | OSS (legacy Unix) audio backend | Absent | Android backend only |
| Overlay (base, UI, unix, win) | In-game overlay injection framework | `service/MumlaOverlay.kt` + `OverlayLayout.kt` (different mechanism) | Android system overlay (draw-over-apps), not game-process injection |
| OverlayClient | Overlay helper process (injected side) | Absent | No match; no helper-process injection on Android |
| OverlayConfig | Overlay settings backend | Service overlay shown/hidden interface (partial) | Shown/hidden setters; visibility via service observers |
| OverlayEditor (+ UI, scene, positionable item, text, user, user-group) | Drag-and-drop overlay layout editor + items | Absent | No match; overlay layout is fixed layouts |
| PAAudio | PortAudio cross-platform backend | Absent | Android backend only |
| PathListWidget | Plugin-path list editor widget | Absent | No match; no plugin paths on Android |
| PipeWire | PipeWire (modern Linux) backend | Absent | Android backend only |
| Plugin | Plugin API wrapper (1.4+) | `protocol/HumlaTCPMessageListener.kt` (protocol only) | Plugin-data-transmission message plumbed plus no-op default; no plugin runtime |
| PluginConfig (+ UI) | Per-plugin settings page | Absent | No match |
| PluginInstaller (+ UI) | Plugin bundle installer UI | Absent | No match |
| PluginManager, PluginManifest, PluginUpdater (+ UI) | Plugin discovery/manifest/update lifecycle | Absent | No match in app+humla |
| PositionalAudioViewer (+ UI), PositionalData | 3-D positional-audio debug viewer + data | Absent | No match outside the generated protobuf field; whisper-target models exist but no positional engine |
| PTTButtonWidget (+ UI) | Push-to-talk toolbar button widget | `service/MumlaHotCorner.kt` + corner layout + PTT on/off sounds (mechanism differs) | Touch hot-corner replaces the toolbar PTT button |
| PulseAudio | PulseAudio backend | Absent | Android backend only |
| QtWidgetUtils | Qt widget helpers | Absent | Android view helpers live in dialog/bitmap utils |
| QuitBehavior | Quit-to-tray behavior enum | Absent | Android has no quit-to-tray |
| RichTextEditor (+ UI, link UI) | Rich-text message composer + link dialog | Absent | Compose UI is chat layouts with HTML rendering only |
| Screen | Screen-capture helpers (overlay) | Absent | No screen class match |
| SearchDialog (+ UI) | Channel/user full-text search dialog | Absent | No match |
| ServerHandler | Multi-server tab connection manager | `app/ServerConnectTask.kt` (single-connect only) | Single active service session — no multi-tab equivalent |
| ServerInformation (+ UI) | Server info dialog (version/users) | `channel/ServerInfoFragment.kt` + `servers/ServerInfoTask.kt` | Server-info fragment plus info task returning the info-response class |
| Settings, SettingsKeys, SettingsMacros | Global settings store + key enums | `Settings.kt` + `SettingsRepository.kt` + `AppDataStore.kt` | Listener map plus register-listener; DataStore-backed instead of QSettings |
| SharedMemory (base, unix, win) | Game-link shared-memory reader | Absent | No match |
| SocketRPC | Named-pipe/loopback script RPC server | Absent | No match; IPC is the talk broadcast plus service binders |
| SvgIcon | SVG icon loader with theme recolor | Absent | Icons are vector drawables |
| TalkingUI (base, component, container, entry, selection) | Talking-heads overlay UI list | `service/MumlaOverlay.kt` (partial) | Overlay reuses the channel adapter; no separate talking-UI hierarchy |
| TaskList | Windows taskbar list integration | Absent | Android recents only |
| TextMessage (+ UI) | Outgoing text-message composer dialog | Service chat-message interface + chat fragment (mechanism differs) | Chat send path via the service message interface; no standalone composer dialog probed |
| TextToSpeech (base, macOS, unix, win) | Cross-platform TTS announcements | `service/MumlaService.kt` TTS import/field/speak/helper/init plus short-TTS-messages use | Android TTS engine with guarded init |
| ThemeInfo, Themes, ThemeSwitch (macOS) | Installable theme packages + switching | Apply-theme plus night themes (partial) | Bundled day/night themes only — no installable theme packages |
| Tokens (+ UI) | Access-token management dialog | `channel/AccessTokenFragment.kt` + `db/MumlaDatabase.kt` token methods | Access-token fragment plus database token add/remove; token row layout |
| Translations (+ 45 locale files, resource, qttranslations) | Qt Linguist translation loading | `res/values-*/strings.xml` + preference overlays (partial) | Android resource locales (30 locales inventoried); no Qt translation pipeline |
| Usage | Anonymous usage-statistics reporting | Absent | No usage class match |
| UserEdit (+ UI) | Self user edit dialog (name/comment/avatar) | `channel/UserMenu.kt` + user/channel comment fragments (partial) | Actions via menus/popups/fragments; no single user-edit dialog |
| UserInformation (+ UI) | User info dialog (stats/cert) | `channel/UserMenu.kt` + `AvatarCache.kt` (partial) | No dedicated user-info dialog file; per-user actions in the user menu |
| UserListModel | Flat user-list model (for search) | Absent | No match; user rows via channel adapters |
| UserLocalNicknameDialog (+ UI) | Per-user local nickname dialog | Absent | No match |
| UserLocalVolumeSlider | Per-user local volume slider | Absent | No match |
| UserLockFile (win + header) | Single-instance lock file (Windows) | Absent | Android single-task model |
| UserModel, UserView | Channel-tree user/channel model + view | Channel adapters + channel fragment + click listeners (re-architected) | RecyclerView adapters replace the model/view; no such files |
| VersionCheck | New-version check + announcement | Absent | No match |
| ViewCert | Server-certificate viewer dialog | `preference/ServerCertificateClearActivity.kt` (partial) | Clear-only — no certificate-details viewer |
| VoiceRecorder (+ UI, dialog UI) | Multi-channel recording to disk + dialog | Absent | No match; no recording-to-disk path |
| VolumeSliderWidgetAction | Volume slider embedded in menus | Absent | Volume via the seek-bar preference/dialog in settings |
| WASAPI (+ notification client) | Windows WASAPI backend + endpoint notifications | Absent | Output is the low-latency `AudioTrack` stream |
| WebFetch | Async HTTP fetch (avatars/version) | `util/MumbleImageGetter.kt` (partial, different scope) | Pinned fetch with redirect/SSRF policy; no generic web-fetch helper |
| WinGUIDs | Windows device GUID table | Absent | Windows-only |
| XboxInput | XInput controller-as-PTT support | Absent | No match |
| XMLTools | XML export/import helpers | Absent | No match |
| Zeroconf | LAN (Bonjour/Avahi) server discovery | Absent | Servers from database favorites plus manual entry plus ping task; no discovery |
| qrc/plist/entitlements/rc | Qt resources + macOS/Windows packaging metadata | `AndroidManifest.xml` + resources (packaging only) | Android manifest/resources replace Qt resource plus OS packaging files |
| widgets/AccessibleQGroupBox | Accessible group-box wrapper | Absent | No match |
| widgets/ClickableLabel | Clickable label | Absent | No match |
| widgets/CompletablePage | Wizard page with completeness API | Absent | No wizard (see audio-wizard row) |
| widgets/EventFilters | Event-filter helpers | Absent | No match |
| widgets/FailedConnectionDialog | Connection-failure dialog with retry | Absent (dedicated dialog) | Failure surfaced via the activity connect flow; no dedicated failed-connection dialog file |
| widgets/MUComboBox | History combo box | Absent | No match |
| widgets/MultiColumnTreeWidget | Multi-column channel tree | `channel/ChannelListFragment.kt` (re-architected) | RecyclerView list replaces the tree widget |
| widgets/MultiStyleWidgetWrapper | Cross-style widget wrapper | Absent | No match |
| widgets/ResponsiveImageDialog | Large-image viewer dialog | Absent | Images inline via the image getter; no dedicated viewer |
| widgets/RichTextItemDelegate | Rich-text tree delegate | `util/MumbleImageGetter.kt` (partial) | HTML image-getter only; no rich-text delegate |
| widgets/SearchDialogItemDelegate, SearchDialogTree | Search-result delegates/tree | Absent | No search dialog (see search row) |
| widgets/SemanticSlider | Labeled slider with semantics | `preference/SeekBarDialogPreference.kt` + fragment | Android seek-bar preference replaces the semantic slider |
| widgets/TrayIcon | System-tray icon + notifications | Connection/message notifications | Android status-bar notifications replace the tray icon |

## 3. Server files — single summary section

Not enumerated (out of scope for this lane). Mumla OLED is a client only:
the `humla` library implements the Mumble client protocol (connection/TCP/UDP,
model handler, audio handler, crypt state, permissions, URL parser) and
persists only client-side state (favorites, certificates, access tokens via
the room database); no server (murmur) logic — no channel/user registry, no
ban/ACL enforcement, no server-side voice mixing, no SQLite server database,
no RPC/DBus server interfaces — exists anywhere in `app/` or
`libraries/humla/` (zero matches for ban editor, ACL editor, server handler,
Zeroconf, DBus, socket-RPC, voice recorder, version check, crash report,
developer console in both trees).
