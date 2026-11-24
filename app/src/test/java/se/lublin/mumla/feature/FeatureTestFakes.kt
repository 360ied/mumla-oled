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

package se.lublin.mumla.feature

import android.app.Notification
import android.content.BroadcastReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.mockito.Mockito.mock
import se.lublin.humla.client.ClientState
import se.lublin.humla.client.Session
import se.lublin.humla.client.emptyConnectTarget
import se.lublin.humla.model.IMessage
import se.lublin.mumla.PrefSnapshot
import se.lublin.mumla.Settings
import se.lublin.mumla.service.ipc.TalkBroadcastReceiver

/** A PrefSnapshot with today's defaults; tests override only what they drive. */
fun testSnapshot(
    audioInputMethod: String = Settings.ARRAY_INPUT_METHOD_PTT,
    hotCorner: String = Settings.ARRAY_HOT_CORNER_NONE,
    togglePtt: Boolean = false,
    chatNotify: Boolean = true,
    allowTalkBroadcast: Boolean = true,
    notificationStyle: String = Settings.NOTIFICATION_STYLE_BIGTEXT,
    mediaKeyMute: Boolean = true,
    mediaKeyMuteTts: Boolean = true,
    useTts: Boolean = true,
    ttsEngine: String? = null,
    shortTtsMessages: Boolean = true,
    certificateId: Long = -1L,
    forceTcp: Boolean = false,
    handsetMode: Boolean = false,
    bluetoothHeadset: Boolean = false,
    pttSound: Boolean = true,
    overlayShown: Boolean = false,
    overlayPlacement: String = Settings.OVERLAY_PLACEMENT_TOP_LEFT,
): PrefSnapshot = PrefSnapshot(
    audioInputMethod = audioInputMethod,
    vadThreshold = 35,
    talkKey = -1,
    hotCorner = hotCorner,
    hidePtt = false,
    togglePtt = togglePtt,
    inputQuality = Settings.DEFAULT_RATE,
    inputBitrate = Settings.DEFAULT_INPUT_QUALITY,
    inputVolume = Settings.DEFAULT_AMPLITUDE_BOOST,
    chatNotify = chatNotify,
    allowTalkBroadcast = allowTalkBroadcast,
    notificationStyle = notificationStyle,
    mediaKeyMute = mediaKeyMute,
    mediaKeyMuteTts = mediaKeyMuteTts,
    useTts = useTts,
    ttsEngine = ttsEngine,
    shortTtsMessages = shortTtsMessages,
    theme = Settings.DEFAULT_THEME,
    language = Settings.DEFAULT_LANGUAGE,
    pttButtonHeight = 60,
    certificateId = certificateId,
    defaultUsername = "",
    forceTcp = forceTcp,
    muted = false,
    deafened = false,
    firstRun = false,
    loadImages = true,
    audioPerPacket = "2",
    halfDuplex = false,
    handsetMode = handsetMode,
    bluetoothHeadset = bluetoothHeadset,
    pttSound = pttSound,
    adaptiveLeveler = true,
    stayAwake = false,
    showUserCount = false,
    startUpInPinnedMode = false,
    overlayShown = overlayShown,
    overlayPlacement = overlayPlacement,
    overlayPosX = null,
    overlayPosY = null,
    newsShownVersions = emptySet(),
)

/** A Connected client state whose Session is never dereferenced by features. */
fun connectedState(): ClientState.Connected =
    ClientState.Connected(emptyConnectTarget(), mock(Session::class.java))

/** Runs a feature on a test main dispatcher and cancels its child scope. */
@OptIn(ExperimentalCoroutinesApi::class)
fun runFeature(block: suspend (CoroutineScope) -> Unit) {
    Dispatchers.setMain(UnconfinedTestDispatcher())
    try {
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            try {
                block(scope)
            } finally {
                scope.cancel()
            }
        }
    } finally {
        Dispatchers.resetMain()
    }
}

class FakeSoundBank : SoundBank {
    val plays = ArrayList<Boolean>()
    var released = false
    override fun play(on: Boolean) {
        plays.add(on)
    }
    override fun release() {
        released = true
    }
}

class FakeTts : TtsSpeaker {
    val spoken = ArrayList<String>()
    var shutdown = false
    override fun speak(text: String) {
        spoken.add(text)
    }
    override fun shutdown() {
        shutdown = true
    }
}

class FakeMessageNotifications : MessageNotificationsView {
    val shown = ArrayList<IMessage>()
    var dismissed = 0
    override fun show(message: IMessage) {
        shown.add(message)
    }
    override fun dismiss() {
        dismissed++
    }
}

class FakeConnectionNotification : ConnectionNotificationView {
    var connecting = 0
    var connected = 0
    var reconnecting = 0
    var shown = 0
    var hidden = 0
    var lastServerName: String? = null
    override fun connecting(serverName: String, host: String, port: Int): Notification {
        connecting++
        lastServerName = serverName
        return Notification()
    }
    override fun connected(
        serverName: String, channelName: String?, muted: Boolean, deafened: Boolean,
        overlayShown: Boolean, hostInfo: String?,
    ): Notification {
        connected++
        lastServerName = serverName
        return Notification()
    }
    override fun reconnecting(
        serverName: String?, error: String?, attempt: Int, delaySec: Int, hostInfo: String?,
    ): Notification {
        reconnecting++
        return Notification()
    }
    override fun show(): Notification {
        shown++
        return Notification()
    }
    override fun hide() {
        hidden++
    }
}

class FakeHotCorner : HotCornerView {
    private var visible = false
    private var talkState = false
    private var gravitySetting = 0
    var refreshes = 0
    val shown: Boolean get() = visible
    val talking: Boolean get() = talkState
    val gravity: Int get() = gravitySetting
    override fun isShown(): Boolean = visible
    override fun setShown(shown: Boolean) {
        visible = shown
    }
    override fun updateTalkState(talking: Boolean) {
        talkState = talking
    }
    override fun setGravity(gravity: Int) {
        gravitySetting = gravity
    }
    override fun refreshGestureExclusionDeferred() {
        refreshes++
    }
}

class FakeOverlay : OverlayView {
    var shown = false
    var positions = 0
    var pttShown: Boolean? = null
    override fun isShown(): Boolean = shown
    override fun show() {
        shown = true
    }
    override fun hide() {
        shown = false
    }
    override fun updatePosition() {
        positions++
    }
    override fun setPushToTalkShown(showPtt: Boolean) {
        pttShown = showPtt
    }
}

class FakeWakeLock : WakeLockLike {
    var held = false
    var acquires = 0
    var releases = 0
    var throwOnAcquire = false
    override fun isHeld(): Boolean = held
    override fun acquire() {
        if (throwOnAcquire) throw RuntimeException("no proximity sensor")
        held = true
        acquires++
    }
    override fun release() {
        held = false
        releases++
    }
}

class FakeFeaturePlatform : FeaturePlatform {
    val toasts = ArrayList<Int>()
    val texts = ArrayList<String>()
    val bluetoothWrites = ArrayList<Boolean>()
    val overlayWrites = ArrayList<Boolean>()
    val soundBanks = ArrayList<FakeSoundBank>()
    val ttsEngines = ArrayList<FakeTts>()
    val messages = FakeMessageNotifications()
    val notification = FakeConnectionNotification()
    val hotCorner = FakeHotCorner()
    val overlay = FakeOverlay()
    val lock = FakeWakeLock()
    /** Null models a device without a proximity sensor. */
    var proximityLock: WakeLockLike? = lock
    val registered = ArrayList<BroadcastReceiver>()
    val unregistered = ArrayList<BroadcastReceiver>()
    var grant = true
    var canDraw = true
    var permissionRequests = 0
    var lastTtsEngine: String? = null
    var ttsInitStatus: Int = 0

    override fun toast(resId: Int) {
        toasts.add(resId)
    }
    override fun toast(text: String) {
        texts.add(text)
    }
    override fun hasBluetoothConnectGrant(): Boolean = grant
    override fun canDrawOverlays(): Boolean = canDraw
    override fun requestOverlayPermission() {
        permissionRequests++
    }
    override fun setBluetoothHeadset(enabled: Boolean) {
        bluetoothWrites.add(enabled)
    }
    override fun setOverlayShown(enabled: Boolean) {
        overlayWrites.add(enabled)
    }
    override fun newSoundBank(voiceRoute: Boolean): SoundBank =
        FakeSoundBank().also { soundBanks.add(it) }
    override fun newTts(engine: String?, onInit: (Int) -> Unit): TtsSpeaker {
        lastTtsEngine = engine
        onInit(ttsInitStatus)
        return FakeTts().also { ttsEngines.add(it) }
    }
    override fun newConnectionNotification(actions: NotificationActions): ConnectionNotificationView =
        notification
    override fun newMessageNotifications(): MessageNotificationsView = messages
    override fun newHotCorner(gravity: Int, listener: HotCornerListener): HotCornerView {
        hotCorner.setGravity(gravity)
        return hotCorner
    }
    override fun newOverlay(host: OverlayHost): OverlayView = overlay
    override fun newProximityLock(): WakeLockLike? = proximityLock
    override fun newTalkReceiver(sink: (String?) -> Unit): BroadcastReceiver =
        TalkBroadcastReceiver { status -> sink(status) }
    override fun registerTalkReceiver(receiver: BroadcastReceiver) {
        registered.add(receiver)
    }
    override fun unregisterTalkReceiver(receiver: BroadcastReceiver) {
        unregistered.add(receiver)
    }
}
