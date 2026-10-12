/*
 * Copyright (C) 2014 Andrew Comminos
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package se.lublin.mumla;

import android.content.Context;
import android.view.Gravity;
import android.view.KeyEvent;

import androidx.annotation.NonNull;
import androidx.datastore.core.DataStore;
import androidx.datastore.preferences.core.Preferences;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import se.lublin.humla.Constants;

/**
 * Singleton settings class for universal access to the app's preferences.
 *
 * Migration slice 2: backed by the Preferences DataStore via
 * {@link SettingsRepository} instead of SharedPreferences. The public surface
 * (keys, defaults, accessors, validation) is unchanged — fleet settings
 * survive through `SharedPreferencesMigration` with no silent resets.
 *
 * Threading (interim): the surface is synchronous, so every call bridges to
 * the store with a blocking read/write on DataStore's in-memory cache.
 *
 * @author morlunk
 */
public class Settings {
    private static final String TAG = Settings.class.getName();

    public static final String PREF_INPUT_METHOD = "audioInputMethod";
    public static final Set<String> ARRAY_INPUT_METHODS;
    /** Voice activity transmits depending on the amplitude of user input. */
    public static final String ARRAY_INPUT_METHOD_VOICE = "voiceActivity";
    /** Push to talk transmits on command. */
    public static final String ARRAY_INPUT_METHOD_PTT = "ptt";
    /** Continuous transmits always. */
    public static final String ARRAY_INPUT_METHOD_CONTINUOUS = "continuous";

    // NOTE: When changing DEFAULTs, the default value in the corresponding
    // widget in settings_PAGE.xml must also be changed. It doesn't pick this
    // up itself...

    public static final String PREF_THRESHOLD = "vadThreshold";
    public static final int DEFAULT_THRESHOLD = 35;

    public static final String PREF_PUSH_KEY = "talkKey";
    public static final int DEFAULT_PUSH_KEY = -1;

    public static final String PREF_HOT_CORNER_KEY = "hotCorner";
    public static final String ARRAY_HOT_CORNER_NONE = "none";
    public static final String ARRAY_HOT_CORNER_TOP_LEFT = "topLeft";
    public static final String ARRAY_HOT_CORNER_BOTTOM_LEFT = "bottomLeft";
    public static final String ARRAY_HOT_CORNER_TOP_RIGHT = "topRight";
    public static final String ARRAY_HOT_CORNER_BOTTOM_RIGHT = "bottomRight";
    public static final String DEFAULT_HOT_CORNER = ARRAY_HOT_CORNER_NONE;

    public static final String PREF_PUSH_BUTTON_HIDE_KEY = "hidePtt";
    public static final Boolean DEFAULT_PUSH_BUTTON_HIDE = false;

    public static final String PREF_PTT_TOGGLE = "togglePtt";
    public static final Boolean DEFAULT_PTT_TOGGLE = false;

    public static final String PREF_INPUT_RATE = "input_quality";
    public static final String DEFAULT_RATE = "48000";

    public static final String PREF_INPUT_QUALITY = "input_bitrate";
    public static final int DEFAULT_INPUT_QUALITY = 40000;

    public static final String PREF_AMPLITUDE_BOOST = "inputVolume";
    public static final Integer DEFAULT_AMPLITUDE_BOOST = 100;

    public static final String PREF_CHAT_NOTIFY = "chatNotify";
    public static final Boolean DEFAULT_CHAT_NOTIFY = true;

    public static final String PREF_ALLOW_TALK_BROADCAST = "allowTalkBroadcast";
    public static final Boolean DEFAULT_ALLOW_TALK_BROADCAST = true;

    public static final String PREF_NOTIFICATION_STYLE = "notification_style";
    public static final String NOTIFICATION_STYLE_BIGTEXT = "bigtext";
    public static final String NOTIFICATION_STYLE_MEDIA = "media";
    public static final String DEFAULT_NOTIFICATION_STYLE = NOTIFICATION_STYLE_BIGTEXT;

    public static final String PREF_MEDIA_KEY_MUTE = "mediaKeyMute";
    public static final boolean DEFAULT_MEDIA_KEY_MUTE = true;

    public static final String PREF_MEDIA_KEY_MUTE_TTS = "mediaKeyMuteTts";
    public static final boolean DEFAULT_MEDIA_KEY_MUTE_TTS = true;

    public static final String PREF_USE_TTS = "useTts";
    public static final Boolean DEFAULT_USE_TTS = true;

    /** Package name of the TTS engine to use, or null for the system default. */
    public static final String PREF_TTS_ENGINE = "ttsEngine";
    /** Preference value identifying the "System default" engine entry. */
    public static final String TTS_ENGINE_SYSTEM_DEFAULT = "";

    public static final String PREF_SHORT_TTS_MESSAGES = "shortTtsMessages";
    public static final boolean DEFAULT_SHORT_TTS_MESSAGES = true;

    public static final String PREF_THEME = "theme";
    public static final String PREF_LANGUAGE = "language";

    public static final String PREF_PTT_BUTTON_HEIGHT = "pttButtonHeight";
    public static final int DEFAULT_PTT_BUTTON_HEIGHT = 50;
    public static final int MIN_PTT_BUTTON_HEIGHT = 40;
    public static final int MAX_PTT_BUTTON_HEIGHT = 400;

    /**
     * The DB identifier for the default certificate.
     * @see se.lublin.mumla.db.DatabaseCertificate
     */
    public static final String PREF_CERT_ID = "certificateId";

    public static final String PREF_DEFAULT_USERNAME = "defaultUsername";
    public static final String DEFAULT_DEFAULT_USERNAME = "Mumla_User"; // funny var name

    public static final String PREF_FORCE_TCP = "forceTcp";
    public static final Boolean DEFAULT_FORCE_TCP = false;

    public static final String PREF_MUTED = "muted";
    public static final Boolean DEFAULT_MUTED = false;

    public static final String PREF_DEAFENED = "deafened";
    public static final Boolean DEFAULT_DEAFENED = false;

    public static final String PREF_FIRST_RUN = "firstRun";
    public static final Boolean DEFAULT_FIRST_RUN = true;

    public static final String PREF_LOAD_IMAGES = "load_images";
    public static final boolean DEFAULT_LOAD_IMAGES = false;

    public static final String PREF_FRAMES_PER_PACKET = "audio_per_packet";
    public static final String DEFAULT_FRAMES_PER_PACKET = Integer.toString(Constants.DEFAULT_FRAMES_PER_PACKET);

    public static final String PREF_HALF_DUPLEX = "half_duplex";
    public static final boolean DEFAULT_HALF_DUPLEX = false;

    public static final String PREF_HANDSET_MODE = "handset_mode";
    public static final boolean DEFAULT_HANDSET_MODE = false;

    public static final String PREF_BLUETOOTH_HEADSET = "bluetooth_headset";
    public static final boolean DEFAULT_BLUETOOTH_HEADSET = false;

    public static final String PREF_PTT_SOUND = "ptt_sound";
    public static final boolean DEFAULT_PTT_SOUND = false;

    public static final String PREF_ADAPTIVE_LEVELER_ENABLED = "adaptive_leveler";
    public static final boolean DEFAULT_ADAPTIVE_LEVELER_ENABLED = true;

    public static final String PREF_STAY_AWAKE = "stay_awake";
    public static final boolean DEFAULT_STAY_AWAKE = true;

    public static final String PREF_SHOW_USER_COUNT = "show_user_count";
    public static final boolean DEFAULT_SHOW_USER_COUNT = false;

    public static final String PREF_START_UP_IN_PINNED_MODE = "startUpInPinnedMode";
    public static final boolean DEFAULT_START_UP_IN_PINNED_MODE = false;

    public static final String PREF_OVERLAY_SHOWN = "overlay_shown";
    public static final boolean DEFAULT_OVERLAY_SHOWN = false;

    public static final String PREF_OVERLAY_POS_X = "overlay_hud_pos_x";
    public static final String PREF_OVERLAY_POS_Y = "overlay_hud_pos_y";

    public static final String PREF_OVERLAY_PLACEMENT = "overlay_placement";
    public static final String OVERLAY_PLACEMENT_FLOATING = "floating";
    public static final String OVERLAY_PLACEMENT_TOP_LEFT = "topLeft";
    public static final String OVERLAY_PLACEMENT_TOP_RIGHT = "topRight";
    public static final String OVERLAY_PLACEMENT_BOTTOM_LEFT = "bottomLeft";
    public static final String OVERLAY_PLACEMENT_BOTTOM_RIGHT = "bottomRight";
    public static final String DEFAULT_OVERLAY_PLACEMENT = OVERLAY_PLACEMENT_FLOATING;

    public static final String PREF_NEWS_SHOWN_VERSIONS = "newsShownVersions";

    static {
        ARRAY_INPUT_METHODS = new HashSet<String>();
        ARRAY_INPUT_METHODS.add(ARRAY_INPUT_METHOD_VOICE);
        ARRAY_INPUT_METHODS.add(ARRAY_INPUT_METHOD_PTT);
        ARRAY_INPUT_METHODS.add(ARRAY_INPUT_METHOD_CONTINUOUS);
    }

    /** Listener for settings changes; delivers the legacy PREF_* key strings. */
    public interface OnSettingChangedListener {
        void onSettingChanged(String key);
    }

    private final SettingsRepository repository;
    private final Map<OnSettingChangedListener, java.util.function.Consumer<String>> listenerAdapters =
            new HashMap<>();

    public static Settings getInstance(Context context) {
        return new Settings(AppDataStore.INSTANCE.get(context));
    }

    Settings(DataStore<Preferences> store) {
        repository = new SettingsRepository(store);
    }

    public static Settings createForTesting(DataStore<Preferences> store) {
        return new Settings(store);
    }

    private PrefSnapshot snapshot() {
        return repository.snapshotBlocking();
    }

    public void registerListener(OnSettingChangedListener listener) {
        java.util.function.Consumer<String> adapter = listener::onSettingChanged;
        synchronized (listenerAdapters) {
            listenerAdapters.put(listener, adapter);
        }
        repository.registerListener(adapter);
    }

    public void unregisterListener(OnSettingChangedListener listener) {
        java.util.function.Consumer<String> adapter;
        synchronized (listenerAdapters) {
            adapter = listenerAdapters.remove(listener);
        }
        if (adapter != null) {
            repository.unregisterListener(adapter);
        }
    }

    public String getInputMethod() {
        String method = snapshot().getAudioInputMethod();
        if(!ARRAY_INPUT_METHODS.contains(method)) {
            // Set default method for users who used to use handset mode before removal.
            method = ARRAY_INPUT_METHOD_VOICE;
        }
        return method;
    }

    /**
     * Converts the preference input method value to the one used to connect to a server via Humla.
     * @return An input method value used to instantiate a Humla service.
     */
    public int getHumlaInputMethod() {
        String inputMethod = getInputMethod();
        if (ARRAY_INPUT_METHOD_VOICE.equals(inputMethod)) {
            return Constants.TRANSMIT_VOICE_ACTIVITY;
        } else if (ARRAY_INPUT_METHOD_PTT.equals(inputMethod)) {
            return Constants.TRANSMIT_PUSH_TO_TALK;
        } else if (ARRAY_INPUT_METHOD_CONTINUOUS.equals(inputMethod)) {
            return Constants.TRANSMIT_CONTINUOUS;
        }
        throw new RuntimeException("Could not convert input method '" + inputMethod + "' to a Humla input method id!");
    }

    public void setInputMethod(String inputMethod) {
        if(ARRAY_INPUT_METHOD_VOICE.equals(inputMethod) ||
                ARRAY_INPUT_METHOD_PTT.equals(inputMethod) ||
                ARRAY_INPUT_METHOD_CONTINUOUS.equals(inputMethod)) {
            repository.updateBlocking(prefs -> prefs.setAudioInputMethod(inputMethod));
        } else {
            throw new RuntimeException("Invalid input method " + inputMethod);
        }
    }

    public int getInputSampleRate() {
        try {
            return Integer.parseInt(snapshot().getInputQuality());
        } catch (NumberFormatException e) {
            return Integer.parseInt(DEFAULT_RATE);
        }
    }

    public int getInputQuality() {
        return snapshot().getInputBitrate();
    }

    public float getAmplitudeBoostMultiplier() {
        return (float)snapshot().getInputVolume()/100;
    }

    public float getDetectionThreshold() {
        return (float)snapshot().getVadThreshold()/100;
    }

    public int getPushToTalkKey() {
        return snapshot().getTalkKey();
    }

    /**
     * Returns whether the given key event matches the configured push-to-talk key.
     *
     * <p>An unconfigured key ({@link #DEFAULT_PUSH_KEY}, {@code -1}) and
     * {@link KeyEvent#KEYCODE_UNKNOWN} ({@code 0}, also the legacy pre-ODD-07 reset value)
     * never match, so unknown or unbound keys cannot trigger transmission (ODD-07).
     */
    public static boolean isPttKeyBound(int pttKey, int keyCode) {
        return pttKey > KeyEvent.KEYCODE_UNKNOWN && keyCode == pttKey;
    }

    public String getHotCorner() {
        return snapshot().getHotCorner();
    }

    /**
     * Returns whether or not the hot corner is enabled.
     *
     * <p>Strict allowlist: only the four known corner values count as enabled.
     * Corrupted or unknown stored values resolve to disabled (consistent with
     * {@link #getHotCornerGravity()}, which returns 0 for such values).
     * @return true if a hot corner should be shown.
     */
    public boolean isHotCornerEnabled() {
        String hc = snapshot().getHotCorner();
        return ARRAY_HOT_CORNER_TOP_LEFT.equals(hc)
                || ARRAY_HOT_CORNER_TOP_RIGHT.equals(hc)
                || ARRAY_HOT_CORNER_BOTTOM_LEFT.equals(hc)
                || ARRAY_HOT_CORNER_BOTTOM_RIGHT.equals(hc);
    }

    /**
     * Returns the view gravity of the hot corner, or 0 if hot corner is disabled.
     * @return A {@link android.view.Gravity} value, or 0 if disabled.
     */
    public int getHotCornerGravity() {
        String hc = getHotCorner();
        if(ARRAY_HOT_CORNER_BOTTOM_LEFT.equals(hc)) {
            return Gravity.LEFT | Gravity.BOTTOM;
        } else if(ARRAY_HOT_CORNER_BOTTOM_RIGHT.equals(hc)) {
            return Gravity.RIGHT | Gravity.BOTTOM;
        } else if(ARRAY_HOT_CORNER_TOP_LEFT.equals(hc)) {
            return Gravity.LEFT | Gravity.TOP;
        } else if(ARRAY_HOT_CORNER_TOP_RIGHT.equals(hc)) {
            return Gravity.RIGHT | Gravity.TOP;
        }
        return 0;
    }


    /* @return the height of PTT button in dp */
    public int getPTTButtonHeight() {
        int height = snapshot().getPttButtonHeight();
        if (height <= 0) {
            return DEFAULT_PTT_BUTTON_HEIGHT;
        }
        return Math.min(MAX_PTT_BUTTON_HEIGHT, Math.max(MIN_PTT_BUTTON_HEIGHT, height));
    }

    /**
     * Returns a database identifier for the default certificate, or a negative number if there is
     * no default certificate set.
     * @return The default certificate's ID, or a negative integer if not set.
     */
    public long getDefaultCertificate() {
        return snapshot().getCertificateId();
    }

    public String getDefaultUsername() {
        return snapshot().getDefaultUsername();
    }

    public boolean isPushToTalkToggle() {
        return snapshot().getTogglePtt();
    }

    public boolean isPushToTalkButtonShown() {
        return !snapshot().getHidePtt();
    }

    public boolean isChatNotifyEnabled() {
        return snapshot().getChatNotify();
    }

    public boolean isTalkBroadcastAllowed() {
        return snapshot().getAllowTalkBroadcast();
    }

    public String getNotificationStyle() {
        String style = snapshot().getNotificationStyle();
        if (!NOTIFICATION_STYLE_MEDIA.equals(style) && !NOTIFICATION_STYLE_BIGTEXT.equals(style)) {
            return DEFAULT_NOTIFICATION_STYLE;
        }
        return style;
    }

    public boolean isNotificationStyleBigText() {
        return NOTIFICATION_STYLE_BIGTEXT.equals(getNotificationStyle());
    }

    public boolean isMediaKeyMuteEnabled() {
        return snapshot().getMediaKeyMute();
    }

    public boolean isMediaKeyMuteTtsEnabled() {
        return snapshot().getMediaKeyMuteTts();
    }

    public void setNotificationStyle(String style) {
        if (NOTIFICATION_STYLE_BIGTEXT.equals(style) || NOTIFICATION_STYLE_MEDIA.equals(style)) {
            repository.updateBlocking(prefs -> {
                prefs.setNotificationStyle(style);
            });
        } else {
            throw new IllegalArgumentException("Invalid notification style: " + style);
        }
    }

    public boolean isTextToSpeechEnabled() {
        return snapshot().getUseTts();
    }

    public boolean isShortTextToSpeechMessagesEnabled() {
        return snapshot().getShortTtsMessages();
    }

    /**
     * Returns the package name of the preferred TTS engine,
     * or null to use the system default engine.
     */
    public String getTtsEngine() {
        String pkg = snapshot().getTtsEngine();
        if (pkg == null || pkg.equals(TTS_ENGINE_SYSTEM_DEFAULT))
            return null;
        return pkg;
    }

    /**
     * Sets the preferred TTS engine by package name,
     * or the system default if pkg is null.
     */
    public void setTtsEngine(String pkg) {
        repository.updateBlocking(prefs -> {
            prefs.setTtsEngine(pkg == null ? Optional.empty() : Optional.of(pkg));
            });
    }

    public boolean isTcpForced() {
        return snapshot().getForceTcp();
    }

    public boolean isMuted() {
        return snapshot().getMuted();
    }

    public boolean isDeafened() {
        return snapshot().getDeafened();
    }

    public boolean isFirstRun() {
        return snapshot().getFirstRun();
    }

    public boolean shouldLoadExternalImages() {
        return snapshot().getLoadImages();
    }

    public void setMutedAndDeafened(boolean muted, boolean deafened) {
        repository.updateBlocking(prefs -> {
            prefs.setMuted(muted || deafened);
            prefs.setDeafened(deafened);
            });
    }

    public void setFirstRun(boolean run) {
        repository.updateBlocking(prefs -> {
            prefs.setFirstRun(run);
            });
    }

    public int getFramesPerPacket() {
        try {
            return Integer.parseInt(snapshot().getAudioPerPacket());
        } catch (NumberFormatException e) {
            return Constants.DEFAULT_FRAMES_PER_PACKET;
        }
    }

    public boolean isHalfDuplex() {
        return snapshot().getHalfDuplex();
    }

    public boolean isHandsetMode() {
        return snapshot().getHandsetMode();
    }

    public boolean isBluetoothHeadset() {
        return snapshot().getBluetoothHeadset();
    }

    public void setBluetoothHeadset(boolean enabled) {
        repository.updateBlocking(prefs -> {
            prefs.setBluetoothHeadset(enabled);
            });
    }

    public boolean isPttSoundEnabled() {
        return snapshot().getPttSound();
    }

    public boolean isAdaptiveLevelerEnabled() {
        return snapshot().getAdaptiveLeveler();
    }

    public boolean shouldStayAwake() {
        return snapshot().getStayAwake();
    }

    public void setDefaultCertificateId(long defaultCertificateId) {
        repository.updateBlocking(prefs -> {
            prefs.setCertificateId(defaultCertificateId);
            });
    }

    public void disableCertificate() {
        repository.updateBlocking(prefs -> {
            prefs.setCertificateId(-1L);
            });
    }

    public boolean isUsingCertificate() {
        return getDefaultCertificate() >= 0;
    }

    /**
     * @return true if the user count should be shown next to channels.
     */
    public boolean shouldShowUserCount() {
        return snapshot().getShowUserCount();
    }

    public boolean shouldStartUpInPinnedMode() {
        return snapshot().getStartUpInPinnedMode();
    }

    public boolean isOverlayShown() {
        return snapshot().getOverlayShown();
    }

    public void setOverlayShown(boolean shown) {
        repository.updateBlocking(prefs -> {
            prefs.setOverlayShown(shown);
            });
    }

    public String getOverlayPlacement() {
        String placement = snapshot().getOverlayPlacement();
        return placement != null ? placement : DEFAULT_OVERLAY_PLACEMENT;
    }

    public void setOverlayPlacement(String placement) {
        repository.updateBlocking(prefs -> {
            prefs.setOverlayPlacement(placement);
            });
    }

    public boolean isOverlayPinned() {
        String placement = getOverlayPlacement();
        return OVERLAY_PLACEMENT_TOP_LEFT.equals(placement)
                || OVERLAY_PLACEMENT_TOP_RIGHT.equals(placement)
                || OVERLAY_PLACEMENT_BOTTOM_LEFT.equals(placement)
                || OVERLAY_PLACEMENT_BOTTOM_RIGHT.equals(placement);
    }

    public int getOverlayGravity() {
        String placement = getOverlayPlacement();
        if (OVERLAY_PLACEMENT_TOP_LEFT.equals(placement)) {
            return Gravity.TOP | Gravity.LEFT;
        } else if (OVERLAY_PLACEMENT_TOP_RIGHT.equals(placement)) {
            return Gravity.TOP | Gravity.RIGHT;
        } else if (OVERLAY_PLACEMENT_BOTTOM_LEFT.equals(placement)) {
            return Gravity.BOTTOM | Gravity.LEFT;
        } else if (OVERLAY_PLACEMENT_BOTTOM_RIGHT.equals(placement)) {
            return Gravity.BOTTOM | Gravity.RIGHT;
        }
        return Gravity.TOP | Gravity.LEFT;
    }

    public int getOverlayPosX(int defaultX) {
        Integer x = snapshot().getOverlayPosX();
        return x != null ? x : defaultX;
    }

    public int getOverlayPosY(int defaultY) {
        Integer y = snapshot().getOverlayPosY();
        return y != null ? y : defaultY;
    }

    public void setOverlayPosition(int x, int y) {
        repository.updateBlocking(prefs -> {
            prefs.setOverlayPosX(x);
            prefs.setOverlayPosY(y);
            });
    }

    public String getTheme() {
        return snapshot().getTheme();
    }

    public void setTheme(String theme) {
        repository.updateBlocking(prefs -> {
            prefs.setTheme(theme);
            });
    }

    public String getLanguage() {
        return snapshot().getLanguage();
    }

    public void setLanguage(String language) {
        repository.updateBlocking(prefs -> {
            prefs.setLanguage(language);
            });
    }

    public Set<String> getNewsShownVersions() {
        return snapshot().getNewsShownVersions();
    }

    public void addNewsShownVersions(@NonNull List<String> versions) {
        // Making a copy; the stored set must not be modified in place.
        Set<String> shownVersions = new HashSet<>(snapshot().getNewsShownVersions());
        for (Iterator<String> it = versions.iterator(); it.hasNext(); ) {
            String s = it.next();
            if (s == null || s.isEmpty()) {
                it.remove();
            }
        }
        if (shownVersions.addAll(versions)) {
            final Set<String> updated = shownVersions;
            repository.updateBlocking(prefs -> {
                prefs.setNewsShownVersions(updated);
            });
        }
    }

    public void resetNewsShownVersion() {
        repository.updateBlocking(prefs -> {
            prefs.setNewsShownVersions(new HashSet<>());
            });
    }
}
