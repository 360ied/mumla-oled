# 06 — Settings, UI, overlay, notifications, shortcuts, platform

Factual parity audit of the settings screens and keys, main UI, overlay and
hot corner, notifications, shortcuts and keys, the Tasker/DBus-style scripting
API, and platform integrations in Mumla OLED versus upstream Mumble. No
recommendations, no judgments.

## 1. Scope and files read

Repo root: this repository.

Downstream files read line by line, full reads:

- `app/.../preference/SettingsActivity.kt` (76 lines)
- `app/.../preference/MumlaPreferenceFragment.kt`
- `app/.../preference/GeneralSettingsFragment.kt`
- `app/.../preference/AudioSettingsFragment.kt` (inflate-only of
  `settings_audio`)
- `app/.../preference/AppearanceSettingsFragment.kt`
- `app/.../preference/AuthenticationSettingsFragment.kt`
- `app/.../preference/AboutSettingsFragment.kt`
- `app/.../preference/DataStorePreferenceDataStore.kt`
- `app/.../preference/SeekBarDialogPreference.kt`
- `app/.../preference/KeySelectDialogPreference.kt`
- `app/.../preference/SeekBarPreferenceDialogFragment.kt`
- `app/.../preference/KeySelectPreferenceDialogFragment.kt`
- `app/.../Settings.kt` (full, ~700 lines)
- `app/.../SettingsRepository.kt`
- `app/.../AppDataStore.kt`
- `app/.../SettingsModule.kt`
- `app/.../res/xml/preference_headers.xml`
- `app/.../res/xml/settings_general.xml`
- `app/.../res/xml/settings_audio.xml`
- `app/.../res/xml/settings_appearance.xml`
- `app/.../res/xml/settings_authentication.xml`
- `app/.../res/xml/settings_about.xml`
- `app/.../res/values/preference_notranslate.xml`
- `app/.../AndroidManifest.xml`
- `app/.../app/BaseActivity.kt`
- `app/.../app/DrawerAdapter.kt`
- `app/.../app/MumlaApplication.kt`
- `app/.../app/IStartupAction.kt`
- `app/.../app/StartupAction.kt` (foss source set)
- `app/.../service/ipc/TalkBroadcastReceiver.kt`
- `app/.../service/MumlaConnectionNotification.kt`
- `app/.../service/MumlaMessageNotification.kt`
- `app/.../service/MumlaOverlay.kt`
- `app/.../service/OverlayLayout.kt`
- `app/.../service/MumlaHotCorner.kt`
- `app/.../drawable/CircleDrawable.kt`
- `app/.../app/MumlaActivity.kt` (full, lines 1–1150 twice plus 1151–1581)

Downstream files partially read (grep plus segment reads, not every line):
`app/.../service/MumlaService.kt` (action intents, foreground/notification
channels, talk-broadcast, overlay/hot-corner, draw-over-apps, TTS, talk-key,
suppression, overlay visibility, foreground-service types),
`app/.../channel/ChannelFragment.kt` (segment lines 340–460),
`ChannelMenu.kt` / `UserMenu.kt` / `ChannelListFragment.kt` (grep only),
`res/layout/ptt_corner.xml` / `overlay_user_row.xml` /
`list_drawer_headerlogo.xml` (grep hits for content-description /
important-for-accessibility).

Upstream files (sibling checkout, `src/mumble`): directory listing verified
(file names exist). Verified by targeted grep only, not full line-by-line
reads: `DBus.h` (full 60-line read), `Settings.h` (field-name inventory),
`GlobalShortcut.h/.cpp`, `ConfigDialog.cpp`, `MainWindow.h`,
`OverlayConfig.h`, `Overlay.h`, `LCD.h`, `API_v_1_x_x.cpp`, `Log.h/.ui/.cpp`,
`Settings.cpp`/`SettingsMacros.h`, `TextToSpeech_unix.cpp`,
`AudioWizard.cpp`, `Messages.cpp`, `ServerHandler.cpp`, `MainWindow.cpp`.

Upstream full bodies unread: config dialog/widget headers and UI, audio
config/input/output UI and wizard, look-config, all global-shortcut
platform/engines/buttons/targets, G15/LCD engines, overlay/client/editor,
main-window UI, custom elements, log beyond grep, search dialog, about/help,
application class, AppNap, crash reporter, developer console, accessibility,
socket-RPC, themes, plugins, network config, certificate UI, tokens, ban/ACL
editors, user edit, voice recorder, PTT button, talking UI, rich-text editor,
positional viewer, volume sliders, Xbox/GKey/Jack/PipeWire/Pulse/WASAPI/ASIO/
CoreAudio/OSS/ALSA device enumerations. Downstream full bodies unread beyond
segments noted: `MumlaService.kt`, `ChannelFragment.kt`,
`ChannelListFragment.kt`, `ChannelListAdapter.kt`, `ChannelAdapter.kt`,
`ServerInfoFragment.kt`, `AccessTokenFragment.kt`, `ChannelChatFragment.kt`,
chat-target callback, user/channel menus beyond grep, server fragments,
certificate activities, dialog utilities, connect task, service fragment,
notification/trust stores, all layouts beyond grep, all values XML beyond
files noted, local config, channel menus. No tests were run (read-only lane).

## 2. Settings-key inventory (41 logical keys)

Source: `SettingsRepository.kt` Keys object plus `Settings.kt` companion
`PREF_*` constants. Widget sources are the `settings_*.xml` files. Defaults
are the repository defaults.

| # | DataStore key | `Settings.PREF_*` | Widget | Default |
|---|---|---|---|---|
| 1 | `audioInputMethod` | `PREF_INPUT_METHOD` | ListPreference: voiceActivity/ptt/continuous | voiceActivity |
| 2 | `vadThreshold` | `PREF_THRESHOLD` | SeekBar max 100 | 35 |
| 3 | `talkKey` | `PREF_PUSH_KEY` | Key-select dialog | -1 (unbound) |
| 4 | `hotCorner` | `PREF_HOT_CORNER_KEY` | ListPreference none/topLeft/bottomLeft/topRight/bottomRight | none |
| 5 | `hidePtt` | `PREF_PUSH_BUTTON_HIDE_KEY` | CheckBox | false |
| 6 | `togglePtt` | `PREF_PTT_TOGGLE` | CheckBox | false |
| 7 | `input_quality` | `PREF_INPUT_RATE` | No widget (legacy key, read via `Settings.inputSampleRate`) | "48000" |
| 8 | `input_bitrate` | `PREF_INPUT_QUALITY` | SeekBar multiplier 1000, min 8 max 192, default 40 (= 40 kbps) | 40000 |
| 9 | `inputVolume` | `PREF_AMPLITUDE_BOOST` | SeekBar max 200 | 100 |
| 10 | `chatNotify` | `PREF_CHAT_NOTIFY` | CheckBox (general) | true |
| 11 | `allowTalkBroadcast` | `PREF_ALLOW_TALK_BROADCAST` | CheckBox (general) | true |
| 12 | `notification_style` | `PREF_NOTIFICATION_STYLE` | ListPreference bigtext/media | bigtext |
| 13 | `mediaKeyMute` | `PREF_MEDIA_KEY_MUTE` | CheckBox (general) | true |
| 14 | `mediaKeyMuteTts` | `PREF_MEDIA_KEY_MUTE_TTS` | CheckBox (general) | true |
| 15 | `useTts` | `PREF_USE_TTS` | CheckBox (general) | true |
| 16 | `ttsEngine` | `PREF_TTS_ENGINE` | ListPreference, entries populated at runtime | "" (= system) |
| 17 | `shortTtsMessages` | `PREF_SHORT_TTS_MESSAGES` | CheckBox (general) | true |
| 18 | `theme` | `PREF_THEME` | ListPreference system/forceLight/forceDark/forceBlack | forceBlack |
| 19 | `language` | `PREF_LANGUAGE` | ListPreference, entries built at runtime | system |
| 20 | `pttButtonHeight` | `PREF_PTT_BUTTON_HEIGHT` | SeekBar multiplier 10 min 4 max 40 default 5 (= 50 dp) | 50 |
| 21 | `certificateId` | `PREF_CERT_ID` | No direct widget; managed via certificate activities | -1 |
| 22 | `defaultUsername` | `PREF_DEFAULT_USERNAME` | EditTextPreference | `Mumla_User` (code fallback; XML default locale-dependent) |
| 23 | `forceTcp` | `PREF_FORCE_TCP` | CheckBox (general) | false |
| 24 | `muted` | `PREF_MUTED` | No widget (session state) | false |
| 25 | `deafened` | `PREF_DEAFENED` | No widget (session state) | false |
| 26 | `firstRun` | `PREF_FIRST_RUN` | No widget | true |
| 27 | `load_images` | `PREF_LOAD_IMAGES` | CheckBox (general) | false |
| 28 | `audio_per_packet` | `PREF_FRAMES_PER_PACKET` | ListPreference 1/2/4/6 | "2" |
| 29 | `half_duplex` | `PREF_HALF_DUPLEX` | CheckBox (audio) | false |
| 30 | `handset_mode` | `PREF_HANDSET_MODE` | CheckBox (audio) | false |
| 31 | `bluetooth_headset` | `PREF_BLUETOOTH_HEADSET` | CheckBox (audio) | false |
| 32 | `ptt_sound` | `PREF_PTT_SOUND` | CheckBox (audio) | false |
| 33 | `adaptive_leveler` | `PREF_ADAPTIVE_LEVELER_ENABLED` | CheckBox (audio) | true |
| 34 | `stay_awake` | `PREF_STAY_AWAKE` | CheckBox (general) | true |
| 35 | `show_user_count` | `PREF_SHOW_USER_COUNT` | CheckBox (appearance) | false |
| 36 | `startUpInPinnedMode` | `PREF_START_UP_IN_PINNED_MODE` | CheckBox (general) | false |
| 37 | `overlay_shown` | `PREF_OVERLAY_SHOWN` | CheckBox (appearance) | false |
| 38 | `overlay_placement` | `PREF_OVERLAY_PLACEMENT` | ListPreference floating/topLeft/topRight/bottomLeft/bottomRight, dependent on overlay_shown | floating |
| 39 | `overlay_hud_pos_x` | `PREF_OVERLAY_POS_X` | No widget (persisted drag position; nullable int) | null |
| 40 | `overlay_hud_pos_y` | `PREF_OVERLAY_POS_Y` | No widget (persisted drag position; nullable int) | null |
| 41 | `newsShownVersions` | `PREF_NEWS_SHOWN_VERSIONS` | No widget (string set; cleared on About version click) | emptySet |

Derived-only (no separate DataStore key): `humlaInputMethod` (maps
voiceActivity/ptt/continuous to transmit constants), `isOverlayPinned`/
`overlayGravity`/`overlayLayout()` (derived from placement),
`isHotCornerEnabled`/`hotCornerGravity` (allowlist of 4 corners),
`isNotificationStyleBigText`, `isPushToTalkButtonShown` (= !hidePtt),
`isUsingCertificate` (= certId >= 0), `amplitudeBoostMultiplier` (=
inputVolume/100), `detectionThreshold` (= vadThreshold/100), `inputQuality`,
`inputSampleRate` (= parse of legacy `input_quality` with "48000" fallback),
`framesPerPacket` (= parse of `audio_per_packet` with default fallback).

### Upstream config-area coverage mapping (grep-verified; upstream bodies unread)

| Upstream config area | Upstream source | Downstream counterpart | Fact |
|---|---|---|---|
| Audio input transmit mode (VAD/PTT/continuous) | `Settings.h` transmit default VAD | Input-method setting; voiceActivity/ptt/continuous list | Three modes present on both sides |
| Audio quality/bitrate | `Settings.h` quality 40000 | `input_bitrate` default 40000, 8–192 kbps slider | Same numeric default |
| Frames per packet | `Settings.h` frames-per-packet 2 | `audio_per_packet` default "2", values 1/2/4/6 (10/20/40/60 ms labels) | Same default; value set matches labels |
| Audio device selection (input/output device, device index) | `Settings.h` input/output device plus port-audio indices | No device key in `SettingsRepository.kt` Keys; `settings_audio.xml` contains no device ListPreference; only handset-mode and bluetooth toggles | No device-selection preference key or widget found downstream |
| Echo cancellation / noise suppression / VAD source / voice hold / jitter | `Settings.h` voice-hold, jitter-size, VAD-source, positional flags; backend device lines | `settings_audio.xml` has vadThreshold, adaptive leveler, half-duplex; no voice-hold, jitter-buffer, VAD-source, echo-cancel, noise-suppress keys | Keys present downstream are the three listed; the other upstream fields have no downstream key match |
| TTS master plus readback/no-scope/no-author/volume/language | `Settings.h` TTS block; log checkboxes including whisper-friends; TTS volume/language lines | `useTts`, `ttsEngine` (package name or system), `shortTtsMessages`; no readback/no-scope/no-author/TTS-volume/TTS-language keys | Three TTS keys downstream versus seven upstream TTS settings by grep |
| Overlay enable/exclusion/filter/opacity/in-game | Overlay config (exclusion mode, enable, show FPS/time); window-helper and talking-UI flags | `overlay_shown`, `overlay_placement` (5 placements), `overlay_hud_pos_x/y`; `MumlaOverlay.kt` floating drag plus 4 pinned corners with system-bar insets; `OverlayLayout.kt` touch dispatch | Downstream overlay settings are the four listed; no FPS/time/exclusion-mode/opacity/filter/zoom keys found |
| LCD/G15/Logitech | LCD device and splitter keys | No LCD/G15 key in Keys; no LCD widget | No downstream counterpart found |
| Appearance themes | Style key; themes/theme-info (unread) | theme system/forceLight/forceDark/forceBlack, default forceBlack; `MumlaApplication.kt` applyTheme maps forceLight → no-night, forceDark/forceBlack → night, system → follow-system, unknown → reset to forceBlack | Four downstream theme values; upstream skin/stylesheet bodies unread |
| Language selection | Language key | language default system; runtime locale list; applied via AppCompat locales; manifest locale config | Both sides have a language setting |
| Confirm actions / quit behavior | No ask-on-quit/confirm-quit grep hit in `Settings.h` | Back-press shows a disconnect-confirm dialog; switch-server shows a reconnect dialog; wrong-password shows a retry dialog; no quit-confirm or database-wipe preference in settings XML | Downstream confirms observed are the three dialogs listed; no wipe-database widget found |
| Database wipe | Database files (unread) | No wipe key/widget | No downstream counterpart found |
| Log verbosity / console | Max-log-blocks; developer console; log (unread beyond grep) | No log-level/verbosity key; message-log getter/clearer exists (grep) but no settings UI | No downstream settings counterpart found |
| Plugins | Plugin check/auto-update keys; plugin config (unread) | No plugin key | No downstream counterpart found |
| Network (QoS/reconnect/TCP-idle, proxy) | Reconnect/QoS keys; network-config UI (unread) | `forceTcp` only; no QoS/reconnect/idle/proxy keys | One network key downstream |
| Restricted audio (whisper-to-friends) | Whisper-friends macro/setting; server-handler use | No whisper-friends key or widget | No downstream counterpart found |
| 24-hour clock | Log 24-hour-clock key | No clock-format key | No downstream counterpart found |
| Global-shortcut enable | Shortcut-enable keys; engine sort | No shortcut-enable key | No downstream counterpart found |
| PTT button window / talking UI | Show-talking-UI / show-PTT-button-window keys; PTT button and talking-UI files (unread) | hidePtt, togglePtt, pttButtonHeight (40–400 dp clamp), hotCorner; channel fragment sizes the on-screen talk button and hides it when muted/non-PTT | Related but distinct mechanisms; upstream bodies unread |
| Image size limits / path | Image path plus max width/height 1024 | `load_images` boolean only | No size/path keys downstream |
| Developer menu / hide frame / minimal | Enable-developer-menu / hide-frame keys; config hide-frame/minimal/developer-console actions | No developer-menu/hide-frame keys | No downstream counterpart found |

## 3. Per-area facts

### 3.1 Settings screens

| Area | Downstream (file:line) | Upstream | Fact |
|---|---|---|---|
| Settings entry + 5 headers | `SettingsActivity.kt` root fragment plus `preference_headers.xml` (general/authentication/audio/appearance/about) | Config dialog page registry via the config registrar; header/UI unread | Downstream has exactly 5 headers; upstream page list unread beyond the registrar loop |
| Fragment allowlist navigation | `SettingsActivity.kt` allowed-fragments (5 entries) plus launch-fragment result | n/a (Qt stacked pages) | Downstream-only mechanism |
| General screen keys | chatNotify, allowTalkBroadcast, notification_style, mediaKeyMute, mediaKeyMuteTts, stay_awake, useTts, ttsEngine, shortTtsMessages, load_images, forceTcp, startUpInPinnedMode | TTS/log checkboxes including whisper-friends; TTS block | whisperFriends, TTS readback/no-scope/no-author/TTS-volume controls present upstream have no widget in general settings |
| Audio screen keys | Full audio XML: input method, handset, bluetooth, inputVolume, vadThreshold, talkKey, hotCorner, ptt_sound, hidePtt, togglePtt, half_duplex, input_bitrate, audio_per_packet, adaptive_leveler | Audio config/input/output/wizard UI; audio settings block (grep) | No input/output device picker widget downstream; upstream device widgets unread |
| Appearance screen keys | theme, language, show_user_count, overlay_shown, overlay_placement (dependent on overlay_shown), pttButtonHeight | Look config; themes; show-user-count false | show_user_count default false matches on both sides. Other look items (font scaling hooks, stylesheet/skin path, talking-UI toggles) unread upstream |
| Authentication screen keys | Certificate generate/select/import/export, clear-trust, default username plus intent dispatch | Certificate dialogs and view (unread) | Downstream auth screen manages app certificates plus trust clear; upstream bodies unread |
| About screen | App name → project link, version key, news → releases link, acknowledgements, GPLv3/Apache links plus version summary (name + code + flavor) and news-reset on click | About/version-check (unread) | Downstream about links verified in XML; upstream bodies unread |
| Preference storage | DataStore wrapper (all 7 types, app DataStore file `"settings"` plus SharedPreferences migration); Hilt binding | QSettings load macros | Downstream persists to a DataStore file `"settings"` with migration from `<package>_preferences`; upstream schema unread beyond grep |
| SeekBar dialog | Persist multiplied value, clamp min–max, format value with divider/suffix | n/a (spin-box/slider in UI files, unread) | Downstream-only widget mechanics verified |
| KeySelect dialog | Single Android key-code capture, back dismisses, neutral reset-to-default (unbound), unknown ignored | Global-shortcut buttons/target UI (unread) | Downstream binds one key; upstream multi-key/joystick/GKey bodies unread |
| TTS engine picker | Enumerates TTS-service intents, "System default" plus installed, disables when ≤ 1, resets uninstalled to default | TTS language line (unix); win/mac TTS (unread) | Downstream engine picker verified; upstream engine/language selection bodies unread |
| Media-key dependency | MediaKeyMute + TTS follow notification-style media; mute toggles TTS-enabled | n/a | Downstream-only wiring |
| Theme apply + recreate | Base activity recreates on theme change; application applies theme plus language on setting change | Themes/translations (unread) | Downstream theme/language apply verified |
| Help / manual | No help/manual activity or preference found in about headers | Help/whats-this action | Upstream help action has no downstream settings/about counterpart found |

Upstream help/version-check, usage, and application bodies unread.

### 3.2 Main UI

| Area | Downstream | Upstream | Fact |
|---|---|---|---|
| Drawer items | Drawer adapter positions: connected-server header, server, pinned, info, tokens, favourites header, favourites, general header, settings; server/info/tokens/pinned gated on connected | Main-window server/channel/user action lists; UI unread | Drawer inventory verified downstream; upstream menu/toolbar bodies unread |
| Channel view + PTT button | Channel fragment `configureInput()`: height from setting, hidden when muted/non-PTT/hide-PTT; whisper-target banner via voice-target mode/target; setting-change wiring for input/hide/height/bluetooth | Transmit-mode action; PTT-button widget; talking UI (unread) | Downstream PTT button plus whisper banner verified in segment; upstream counterparts unread |
| Channel actions | Channel menu grep: join, view-description, pin (database pinned add/remove/check), link/unlink/unlink-all, listen toggle, whisper-target dialog (channel + linked/subchannel checkboxes) | Join/listen/add/remove/ACL/link/unlink/unlink-all/send-message/hide/pin/copy-URL actions | Action-name overlap verified by grep; bodies/behaviors unread on both sides beyond grep |
| User actions | User menu grep: kick/ban (reason field → kick/ban call), move, priority-speaker check, ignore-messages check, change/view/reset comment | Self-comment/register, kick/ban, mute/deaf, priority, local-ignore, local-ignore-TTS, local-mute, local-nickname, text-message, register, information, friend add/remove/update | Downstream lacks local-mute, local-nickname, friend, TTS-ignore, register, information entries by grep; upstream bodies unread. No user search/filter widget found in the channel list by grep |
| Search | No search-view/query-text/filter match in the channel list fragment (grep) | Search dialog files exist (listing) but unread | No downstream channel search found; upstream search body unread |
| Orientation / multi-window / tablet | `onConfigurationChanged` forwards to the drawer toggle only; manifest has no screen-orientation, config-changes, resizable-activity, or picture-in-picture attributes; dialogs dismissed in `onPause` | n/a (desktop windowing) | Manifest facts verified; no tablet/multi-window-specific layout qualifier inventoried (layout dirs not inventoried) |
| Accessibility | Talk-button corner content-description; drawer logo content-description; overlay row explicitly not important for accessibility; TalkBack fallback title comment in the activity | Accessibility files (unread) | Three downstream accessibility attributes verified; upstream body unread; no font-scaling-specific handling verified downstream |
| Crash reporting | No crash-reporter match in `app/src/main` (grep) | Crash-reporter files exist (listing) but unread | No downstream crash-reporter counterpart found |
| Developer console / log verbosity | No developer-console/log-verbosity match (grep) | Developer-console files plus action (unread) | No downstream counterpart found |

### 3.3 Overlay versus upstream overlay / talking UI

| Area | Downstream | Upstream | Fact |
|---|---|---|---|
| Chat-head overlay enable/placement | `MumlaOverlay.kt`: system-alert-window type, not-focusable/outside-touch/not-touch-modal flags; pinned (4 corners, not-touchable, system-bar/cutout insets plus 8 dp gutter, 16 dp side / 40 dp top / 56 dp bottom fallbacks) versus floating drag (touch-slop, clamp, persist position); single-snapshot layout triple | Overlay files, config, UI, editor (unread) | Downstream mechanics verified; upstream bodies unread |
| Overlay content | Observer: same-channel talk/state/join updates via channel-adapter state/notify/channel-set, avatar-cache clear on disconnect | Overlay user/group/text/client, talking UI (unread) | Downstream shows the active-channel members; upstream bodies unread |
| Overlay permission | Service checks and requests overlay permission, update-visibility gated on draw-over-apps; hot-corner setter gates on the permission intent plus OEM catch | n/a (desktop helper install) | Both downstream overlays gate on draw-over-apps |
| Hot corner (PTT corner) | Gravity param, corner layout, down/up/cancel → hot-corner callbacks, pressed/activated alpha, gesture-exclusion rects plus deferred refresh, set-gravity, shown flag; visibility = connected + hot-corner enabled + PTT mode | PTT-button widget (unread) | Downstream hot-corner verified; upstream PTT-button body unread |
| In-game overlay / Exposé | No game-process injection or Exposé action downstream | In-game overlay implementations, overlay client, whisper/overlay shortcut slots (unread) | No downstream in-game overlay found; upstream bodies unread |

### 3.4 Notifications

| Area | Downstream | Upstream | Fact |
|---|---|---|---|
| Ongoing call notification | `MumlaConnectionNotification.kt`: connected-channel (default importance, public visibility, deletes the legacy channel), foreground-service id 1 (plus microphone type on newer Android); BigText versus MediaSession styles by setting; actions mute/deafen/overlay/disconnect (plus cancel-reconnect state); media-session callback play/pause/headset-hook → mute-toggle when media-key mute, PTT-claimed keys consumed, repeat-guard; manifest foreground-service microphone declarations | n/a (tray icon plus log flashing, unread) | Downstream ongoing-notification verified as described |
| Message notifications | `MumlaMessageNotification.kt`: message channel (default importance), id 2, InboxStyle with unread-many plural, per-line sanitized actor/body, high priority, vibrate [0,100], count, auto-cancel, POST_NOTIFICATIONS double-gate (newer Android), backlog suppressed while denied | n/a | No LED/lights, custom sound, or per-channel importance controls found in the file; channel importance is default (not high) |
| Suppression / clears | Activity: on-service-connected → suppress plus clear-chat-notifications; on-pause → unsuppress; service setter (grep) | n/a | Verified by grep/segment |
| Notification channels (Android 8+) | Two channels total by grep: connected-channel plus message-channel; both default importance; created-flag cache | n/a | No other channel construction found in `app/src/main` by grep |

### 3.5 Shortcuts, keys, scripting API

| Area | Downstream | Upstream | Fact |
|---|---|---|---|
| PTT key bind | Key-select dialog (single key-code); activity key-down/up via the bound-key check (guard: bound key above unknown); service talk-key down/up/cancel segment (PTT-mode plus toggle aware); talkKey/hotCorner/togglePtt/hidePtt settings | Global-shortcut engine plus shortcut list; platform engines plus GKey/Xbox/joystick (unread) | Downstream single-key PTT verified; upstream bodies unread |
| Whisper/mute/deaf/overlay shortcuts | Talk-broadcast receiver TALK only (below); notification actions plus media keys (above); no whisper-target/mute/deaf key bindings in audio settings | Whisper-adjacent slots; global-shortcut types; default shortcut set (unread) | No downstream whisper/mute/deaf/overlay key-binding preferences found |
| Joystick buttons | No joystick/GKey/Xbox preference or engine downstream (no grep hit) | Xbox input, GKey, global-shortcut buttons (unread) | No downstream counterpart found |
| Per-target binds | Channel-menu whisper-target dialog is runtime-only (channel plus linked/subchannel checkboxes), no saved per-target bind list in settings | Shortcut-target dialog/widget/types (unread) | Upstream per-target UI bodies unread; no persisted per-target shortcut list downstream |
| TalkBroadcastReceiver intent API | `se.lublin.mumla.action.TALK`, extra `status`, values on/off/toggle (null → toggle); guards: wrong action/null ignored, requires connected plus PTT mode, null session safe; registered in the service intent filter (grep); gated by allow-talk-broadcast (grep); label "Allow Tasker TALK broadcasts" | DBus interface: mute/deaf properties, slots open-URL/current-URL/talking-users/focus/transmit-mode/get/set/toggle-mute/toggle-deaf/set-mute/set-deaf/start/stop-talking (plus legacy service name); socket-RPC files (unread); plugin API grep list (sync state, local mute, settings getters, send-data, play-sample) | Downstream intent API is the single TALK broadcast; upstream DBus/plugin-API surfaces are broader (listed) with bodies unread. No DBus/socket-RPC/plugin equivalent downstream |
| Service action intents (internal) | Disconnect/mute/deafen/toggle-overlay/cancel-reconnect (`se.lublin.mumla.action.*`), dispatched in the service (grep) | n/a (in-process Qt signals) | Internal-only notification pending intents, not a public API |

### 3.6 Platform integrations

| Area | Downstream | Upstream | Fact |
|---|---|---|---|
| Startup / first-run / news | Startup action → maybe-show-news dialog; activity first-run guide (certificate-generate task, pending-startup rotation handoff, startup action after); about fragment resets news-shown versions | Usage, version-check, application files (unread) | Downstream startup verified; upstream bodies unread |
| Runtime permissions | Activity connect-with-permission (record-audio → bluetooth-connect on newer Android when headset preference → post-notifications once); manifest permission list | n/a | Verified |
| Audio route / volume | Activity voice-call volume (voice-call versus music by media-route-active), volume-stream resync on bind/route callbacks, setting-change wiring for handset/bluetooth, absolute bluetooth-headset writer | Exclusive input/output flags; backend files (unread) | Downstream route/volume wiring verified; upstream bodies unread |
| Stay-awake / proximity | stay-awake setting default true; activity keep-screen-on flag plus setting-change wiring; service proximity wake lock | AppNap (unread) | Downstream flags verified by grep; upstream body unread |
| `mumble://` links | Activity view-intent/new-intent (action view, URL parser, edit-fragment connect dialog, URL-password flag); manifest `mumble`-scheme browsable filter | DBus open-URL/current-URL | Both sides handle URLs; bodies compared only as listed |
| Locale config | Manifest locale config; language values (grep) | Translations plus locale files (listing; unread) | Downstream locale plumbing verified; translation parity unread |
| Backup / wipe | Manifest allow-backup true, install-location auto; no wipe-database UI found | Database files (unread) | Manifest facts verified |

## 4. Intent and shortcut matrix

### 4.1 Public intent API (downstream)

| Action | Extra | Values | Gate | Handler |
|---|---|---|---|---|
| `se.lublin.mumla.action.TALK` | `status` | on / off / toggle; null → toggle | allow-talk-broadcast plus connected plus PTT transmit mode; non-PTT/unknown-status/disconnected safely ignored with a log | `session.setTalkingState(...)` |

No other public broadcast/content-provider/API hire (Tasker/Locale, DBus,
socket-RPC, plugin API) found downstream. Internal service intents
(mute/deafen/toggle-overlay/disconnect/cancel-reconnect) are notification
pending intents, not a public API.

### 4.2 Shortcut matrix (downstream versus upstream)

| Function | Downstream binding | Upstream binding (grep/listing only) |
|---|---|---|
| Push-to-talk | Single Android key-code (default unbound) plus hot corner (4 corners plus none) plus on-screen talk button (hide/toggle/height) | Global-shortcut engine plus platform backends plus GKey/Xbox/joystick (bodies unread) |
| Whisper/shout to channel | Runtime whisper-target dialog per channel (linked/subchannel checkboxes); no saved shortcut | Global-shortcut whisper entries plus per-target dialog (unread) |
| Self mute / deafen | Notification actions, channel overflow menu, media-key mute toggle (media style only) | Global-shortcut mute/deaf plus menu actions (unread bodies) |
| Overlay toggle/Exposé | Notification overlay action; no keyboard shortcut | Overlay shortcuts plus Exposé-style (unread) |
| LCD/G15 | None | LCD devices, G15 engines (unread) |
| DBus/scripting | None (TALK broadcast only) | DBus 13-slot interface plus plugin API functions listed plus socket-RPC (unread) |

## 5. Factual gap inventory (no judgments)

Gaps below mean "no downstream counterpart found by the stated search";
upstream bodies for most rows are unread (see §1).

1. Audio I/O device selection — no input/output-device/device-index key or
   picker widget downstream.
2. Voice-hold / jitter-buffer / VAD-source / echo-cancel / noise-suppress
   settings — no keys downstream.
3. TTS readback / no-scope / no-author / TTS volume / TTS language — no keys
   downstream; only use-TTS/TTS-engine/short-messages.
4. Overlay FPS / time / exclusion-mode / opacity / filter / zoom / avatar /
   column settings — no keys downstream; only shown/placement/drag-position.
5. LCD/G15/Logitech support — no keys/UI downstream.
6. Global-shortcut enable plus multi-key/joystick/GKey/Xbox bindings plus
   per-target saved binds — no keys/UI downstream; single-key PTT plus hot
   corner only.
7. Whisper/mute/deaf/overlay keyboard shortcuts — no key-bind preferences
   downstream.
8. DBus interface (13 slots), socket-RPC, plugin API (functions listed) — no
   downstream equivalents; only the TALK broadcast.
9. Channel/user search dialog — no search widget found in the channel list;
   upstream search dialog unread.
10. User local-mute / local-nickname / friend add-remove / TTS-ignore /
    register / information actions — not found in the user menu (grep);
    upstream slots listed.
11. Confirm-quit / database-wipe settings — no widgets downstream.
12. Log verbosity / developer console / crash reporter — no keys/UI/code
    matches downstream.
13. Plugin management / QoS / reconnect / proxy / image-size/path / 24-hour
    clock / whisper-to-friends / developer-menu / hide-frame — no keys
    downstream.
14. Help/manual/whats-this — no downstream help activity/preference found.
15. LED / custom-sound / per-conversation notification controls — not present
    in the message notification (vibrate-only, default importance).
16. Font-scaling-specific handling and tablet/multi-window/orientation-lock
    declarations — none found (manifest, accessibility grep); only the three
    content-description/important-for-accessibility attributes listed in §3.2.
