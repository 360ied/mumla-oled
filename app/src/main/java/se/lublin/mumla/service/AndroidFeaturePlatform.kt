/*
 * Copyright (C) 2026 Brian Zhu
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
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package se.lublin.mumla.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.Toast
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.content.ContextCompat
import se.lublin.mumla.R
import se.lublin.mumla.service.IChatMessage
import se.lublin.mumla.Settings
import se.lublin.mumla.app.MumlaApplication
import se.lublin.mumla.feature.ConnectionNotificationView
import se.lublin.mumla.feature.FeaturePlatform
import se.lublin.mumla.feature.HotCornerListener
import se.lublin.mumla.feature.HotCornerView
import se.lublin.mumla.feature.MessageNotificationsView
import se.lublin.mumla.feature.NotificationActions
import se.lublin.mumla.feature.OverlayHost
import se.lublin.mumla.feature.OverlayView
import se.lublin.mumla.feature.SoundBank
import se.lublin.mumla.feature.TtsSpeaker
import se.lublin.mumla.feature.WakeLockLike
import se.lublin.mumla.service.ipc.TalkBroadcastReceiver

/**
 * Real Android edges for the features, all over the application context:
 * no feature holds a service or activity. Heavy resources are created by the
 * features on first use, not here.
 */
class AndroidFeaturePlatform(
    private val context: Context,
    private val settings: Settings,
) : FeaturePlatform {

    override fun toast(resId: Int) {
        Toast.makeText(context, resId, Toast.LENGTH_LONG).show()
    }

    override fun toast(text: String) {
        Toast.makeText(context, text, Toast.LENGTH_LONG).show()
    }

    @SuppressLint("InlinedApi")
    override fun hasBluetoothConnectGrant(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
    }

    override fun canDrawOverlays(): Boolean =
        android.provider.Settings.canDrawOverlays(context)

    override fun requestOverlayPermission() {
        try {
            val intent = Intent(
                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + context.packageName),
            )
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(intent)
            Toast.makeText(context, R.string.grant_perm_draw_over_apps, Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open overlay permission settings", e)
        }
    }

    override fun setBluetoothHeadset(enabled: Boolean) {
        settings.isBluetoothHeadset = enabled
    }

    override fun setOverlayShown(enabled: Boolean) {
        settings.isOverlayShown = enabled
    }

    override fun newSoundBank(voiceRoute: Boolean): SoundBank = AndroidSoundBank(voiceRoute)

    override fun newTts(engine: String?, onInit: (Int) -> Unit): TtsSpeaker =
        AndroidTtsSpeaker(engine, onInit)

    override fun newConnectionNotification(actions: NotificationActions): ConnectionNotificationView =
        AndroidConnectionNotification(actions)

    override fun newMessageNotifications(): MessageNotificationsView =
        AndroidMessageNotifications()

    /** Overlay windows inflate from the app theme, over the application context. */
    private val themedContext: Context by lazy {
        ContextThemeWrapper(context, R.style.Theme_Mumla_Oled).also {
            MumlaApplication.applyTheme(it)
        }
    }

    override fun newHotCorner(gravity: Int, listener: HotCornerListener): HotCornerView =
        AndroidHotCorner(gravity, listener)

    override fun newOverlay(host: OverlayHost): OverlayView = AndroidOverlay(host)

    override fun newProximityLock(): WakeLockLike? {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager? ?: return null
        return try {
            AndroidWakeLock(pm.newWakeLock(PROXIMITY_SCREEN_OFF_WAKE_LOCK, "Mumla:Proximity"))
        } catch (e: RuntimeException) {
            Log.w(TAG, "proximity sensor unavailable", e)
            null
        }
    }

    override fun newTalkReceiver(sink: (String?) -> Unit): BroadcastReceiver =
        TalkBroadcastReceiver { status -> sink(status) }

    override fun registerTalkReceiver(receiver: BroadcastReceiver) {
        ContextCompat.registerReceiver(
            context, receiver,
            IntentFilter(TalkBroadcastReceiver.BROADCAST_TALK),
            ContextCompat.RECEIVER_EXPORTED,
        )
    }

    override fun unregisterTalkReceiver(receiver: BroadcastReceiver) {
        try {
            context.unregisterReceiver(receiver)
        } catch (e: IllegalArgumentException) {
            Log.d(TAG, "talk receiver was not registered", e)
        }
    }

    private inner class AndroidSoundBank(private val voiceRoute: Boolean) : SoundBank {
        private var pool: SoundPool? = null
        private var onId = 0
        private var offId = 0
        private var onLoaded = false
        private var offLoaded = false
        private var activeStream = 0

        init {
            try {
                val stream = if (voiceRoute) AudioManager.STREAM_VOICE_CALL else AudioManager.STREAM_MUSIC
                val usage = if (voiceRoute) {
                    AudioAttributes.USAGE_VOICE_COMMUNICATION
                } else {
                    AudioAttributes.USAGE_MEDIA
                }
                val attributes = AudioAttributes.Builder()
                    .setLegacyStreamType(stream)
                    .setUsage(usage)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                val built = SoundPool.Builder().setMaxStreams(2).setAudioAttributes(attributes).build()
                pool = built
                built.setOnLoadCompleteListener { soundPool, sampleId, status ->
                    if (soundPool === pool && status == 0) {
                        if (sampleId == onId) onLoaded = true
                        else if (sampleId == offId) offLoaded = true
                    }
                }
                onId = built.load(context, R.raw.ptt_on, 1)
                offId = built.load(context, R.raw.ptt_off, 1)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize SoundPool: " + e.message)
            } catch (e: NoSuchMethodError) {
                Log.e(TAG, "Failed to initialize SoundPool: " + e.message)
            } catch (e: NoClassDefFoundError) {
                Log.e(TAG, "Failed to initialize SoundPool: " + e.message)
            }
        }

        override fun play(on: Boolean) {
            val soundPool = pool ?: return
            val id = if (on) onId else offId
            val loaded = if (on) onLoaded else offLoaded
            if (id == 0 || !loaded) return
            try {
                if (activeStream != 0) soundPool.stop(activeStream)
                activeStream = soundPool.play(id, 1.0f, 1.0f, 1, 0, 1.0f)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to play PTT cue: " + e.message)
            }
        }

        override fun release() {
            val soundPool = pool ?: return
            try {
                soundPool.setOnLoadCompleteListener(null)
                soundPool.release()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to release SoundPool: " + e.message)
            }
            pool = null
            onId = 0
            offId = 0
            onLoaded = false
            offLoaded = false
            activeStream = 0
        }
    }

    private inner class AndroidTtsSpeaker(
        engine: String?,
        onInit: (Int) -> Unit,
    ) : TtsSpeaker {
        private val tts: TextToSpeech? = try {
            val listener = TextToSpeech.OnInitListener { status -> onInit(status) }
            if (engine != null) TextToSpeech(context, listener, engine)
            else TextToSpeech(context, listener)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create TTS engine", e)
            onInit(TextToSpeech.ERROR)
            null
        }

        override fun speak(text: String) {
            try {
                tts?.speak(text, TextToSpeech.QUEUE_ADD, null, null as String?)
            } catch (e: Exception) {
                Log.w(TAG, "TTS speak failed", e)
            }
        }

        override fun shutdown() {
            try {
                tts?.shutdown()
            } catch (e: Exception) {
                Log.w(TAG, "TTS shutdown failed", e)
            }
        }
    }

    private inner class AndroidConnectionNotification(
        actions: NotificationActions,
    ) : ConnectionNotificationView {
        private val listener = object : MumlaConnectionNotification.OnActionListener {
            override fun onMuteToggled() = actions.onMuteToggled()
            override fun onMediaKeyMuteToggled() = actions.onMediaKeyMuteToggled()
            override fun onDeafenToggled() = actions.onDeafenToggled()
            override fun onOverlayToggled() = actions.onOverlayToggled()
            override fun onCancelReconnect() = actions.onCancelReconnect()
            override fun onDisconnect() = actions.onDisconnect()
        }
        private val view = MumlaConnectionNotification.create(context, listener, settings)

        override fun connecting(serverName: String, host: String, port: Int): Notification {
            view.showConnecting(serverName, host, port)
            return view.build()
        }

        override fun connected(
            serverName: String, channelName: String?, muted: Boolean, deafened: Boolean,
            overlayShown: Boolean, hostInfo: String?,
        ): Notification {
            view.showConnected(serverName, channelName, muted, deafened, overlayShown, hostInfo)
            return view.build()
        }

        override fun reconnecting(
            serverName: String?, error: String?, attempt: Int, delaySec: Int, hostInfo: String?,
        ): Notification {
            view.showReconnecting(serverName, error, attempt, delaySec, hostInfo)
            return view.build()
        }

        override fun show(): Notification = view.build()

        override fun hide() = view.hide()
    }

    private inner class AndroidMessageNotifications : MessageNotificationsView {
        private val view = MumlaMessageNotification(context)
        override fun show(message: IChatMessage.TextMessage) = view.show(message)
        override fun dismiss() = view.dismiss()
    }

    private inner class AndroidHotCorner(
        gravity: Int,
        listener: HotCornerListener,
    ) : HotCornerView {
        private val view = MumlaHotCorner(
            themedContext, gravity,
            object : MumlaHotCorner.MumlaHotCornerListener {
                override fun onHotCornerDown() = listener.onDown()
                override fun onHotCornerUp() = listener.onUp()
                override fun onHotCornerCancel() = listener.onCancel()
            },
        )
        override fun isShown(): Boolean = view.isShown()
        override fun setShown(shown: Boolean) = view.setShown(shown)
        override fun updateTalkState(talking: Boolean) = view.updateTalkState(talking)
        override fun setGravity(gravity: Int) = view.setGravity(gravity)
        override fun refreshGestureExclusionDeferred() = view.refreshGestureExclusionDeferred()
    }

    private inner class AndroidOverlay(host: OverlayHost) : OverlayView {
        private val view = MumlaOverlay(themedContext, host)
        override fun isShown(): Boolean = view.isShown()
        override fun show() = view.show()
        override fun hide() = view.hide()
        override fun updatePosition() = view.updatePosition()
        override fun setPushToTalkShown(showPtt: Boolean) = view.setPushToTalkShown(showPtt)
    }

    private class AndroidWakeLock(private val lock: PowerManager.WakeLock) : WakeLockLike {
        override fun isHeld(): Boolean = lock.isHeld
        override fun acquire() {
            lock.acquire()
        }
        override fun release() {
            if (lock.isHeld) lock.release()
        }
    }

    companion object {
        private const val TAG = "AndroidFeaturePlatform"
        /** Undocumented constant that permits a proximity-sensing wake lock. */
        const val PROXIMITY_SCREEN_OFF_WAKE_LOCK = 32
    }
}
