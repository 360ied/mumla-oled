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
import kotlinx.coroutines.flow.StateFlow
import se.lublin.humla.model.IMessage
import se.lublin.humla.state.SessionState
import se.lublin.humla.voice.TalkActivity

/**
 * Android edges behind small interfaces: a feature's test passes a fake, the
 * container passes [se.lublin.mumla.service.AndroidFeaturePlatform]. Defaults
 * are inert so a test only overrides the edge it exercises.
 */
interface FeaturePlatform {
    fun toast(resId: Int) = Unit
    fun toast(text: String) = Unit
    fun hasBluetoothConnectGrant(): Boolean = true
    fun canDrawOverlays(): Boolean = true
    fun requestOverlayPermission() = Unit
    fun setBluetoothHeadset(enabled: Boolean) = Unit
    fun setOverlayShown(enabled: Boolean) = Unit
    fun newSoundBank(voiceRoute: Boolean): SoundBank = NoopSoundBank
    fun newTts(engine: String?, onInit: (Int) -> Unit): TtsSpeaker = NoopTtsSpeaker
    fun newConnectionNotification(actions: NotificationActions): ConnectionNotificationView =
        NoopConnectionNotification
    fun newMessageNotifications(): MessageNotificationsView = NoopMessageNotifications
    fun newHotCorner(gravity: Int, listener: HotCornerListener): HotCornerView = NoopHotCorner
    fun newOverlay(host: OverlayHost): OverlayView = NoopOverlay
    fun newProximityLock(): WakeLockLike? = null
    fun newTalkReceiver(sink: (String?) -> Unit): BroadcastReceiver? = null
    fun registerTalkReceiver(receiver: BroadcastReceiver) = Unit
    fun unregisterTalkReceiver(receiver: BroadcastReceiver) = Unit
}

interface SoundBank {
    fun play(on: Boolean)
    fun release()
}

interface TtsSpeaker {
    fun speak(text: String)
    fun shutdown()
}

interface MessageNotificationsView {
    fun show(message: IMessage)
    fun dismiss()
}

interface ConnectionNotificationView {
    fun connecting(serverName: String, host: String, port: Int): Notification
    fun connected(
        serverName: String,
        channelName: String?,
        muted: Boolean,
        deafened: Boolean,
        overlayShown: Boolean,
        hostInfo: String?,
    ): Notification
    fun reconnecting(
        serverName: String?,
        error: String?,
        attempt: Int,
        delaySec: Int,
        hostInfo: String?,
    ): Notification
    fun show(): Notification
    fun hide()
}

interface NotificationActions {
    fun onMuteToggled()
    fun onMediaKeyMuteToggled()
    fun onDeafenToggled()
    fun onOverlayToggled()
    fun onCancelReconnect()
    fun onDisconnect()
}

interface HotCornerListener {
    fun onDown()
    fun onUp()
    fun onCancel()
}

interface HotCornerView {
    fun isShown(): Boolean
    fun setShown(shown: Boolean)
    fun updateTalkState(talking: Boolean)
    fun setGravity(gravity: Int)
    fun refreshGestureExclusionDeferred()
}

/** Immutable overlay layout triple, kept out of the Settings facade. */
data class OverlayLayout(val pinned: Boolean, val gravity: Int, val placement: String)

/**
 * The overlay HUD's narrow view over the container's session-state mirror.
 * Features never receive the whole mutable Settings facade: only the
 * overlay's own state is readable and writable through this seam.
 */
interface OverlayHost {
    fun overlayPinned(): Boolean
    fun overlayLayout(): OverlayLayout
    fun overlayPosX(defaultX: Int): Int
    fun overlayPosY(defaultY: Int): Int
    fun setOverlayPosition(x: Int, y: Int)

    /** The process session-state mirror; the overlay collects it while shown. */
    fun sessionState(): StateFlow<SessionState>

    /** The live talk levels; the overlay collects them while shown. */
    fun talkActivity(): StateFlow<TalkActivity>
}

interface OverlayView {
    fun isShown(): Boolean
    fun show()
    fun hide()
    fun updatePosition()
    fun setPushToTalkShown(showPtt: Boolean)
}

interface WakeLockLike {
    fun isHeld(): Boolean
    fun acquire()
    fun release()
}

private object NoopSoundBank : SoundBank {
    override fun play(on: Boolean) = Unit
    override fun release() = Unit
}

private object NoopTtsSpeaker : TtsSpeaker {
    override fun speak(text: String) = Unit
    override fun shutdown() = Unit
}

private object NoopMessageNotifications : MessageNotificationsView {
    override fun show(message: IMessage) = Unit
    override fun dismiss() = Unit
}

private object NoopConnectionNotification : ConnectionNotificationView {
    override fun connecting(serverName: String, host: String, port: Int): Notification = Notification()
    override fun connected(
        serverName: String, channelName: String?, muted: Boolean, deafened: Boolean,
        overlayShown: Boolean, hostInfo: String?,
    ): Notification = Notification()
    override fun reconnecting(
        serverName: String?, error: String?, attempt: Int, delaySec: Int, hostInfo: String?,
    ): Notification = Notification()
    override fun show(): Notification = Notification()
    override fun hide() = Unit
}

private object NoopHotCorner : HotCornerView {
    override fun isShown(): Boolean = false
    override fun setShown(shown: Boolean) = Unit
    override fun updateTalkState(talking: Boolean) = Unit
    override fun setGravity(gravity: Int) = Unit
    override fun refreshGestureExclusionDeferred() = Unit
}

private object NoopOverlay : OverlayView {
    override fun isShown(): Boolean = false
    override fun show() = Unit
    override fun hide() = Unit
    override fun updatePosition() = Unit
    override fun setPushToTalkShown(showPtt: Boolean) = Unit
}
