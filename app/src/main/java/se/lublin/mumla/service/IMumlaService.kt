package se.lublin.mumla.service

import se.lublin.humla.IHumlaService

/**
 * Created by andrew on 28/02/17.
 */
interface IMumlaService : IHumlaService {
    fun setOverlayShown(showOverlay: Boolean)

    fun isOverlayShown(): Boolean

    fun clearChatNotifications()

    fun markErrorShown()

    fun isErrorShown(): Boolean

    fun onTalkKeyDown()

    fun onTalkKeyUp()

    fun onTalkKeyCancel()

    fun getMessageLog(): List<IChatMessage>

    fun clearMessageLog()

    fun setSuppressNotifications(suppressNotifications: Boolean)
}
