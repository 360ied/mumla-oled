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

package se.lublin.mumla.channel.comment

import android.os.Bundle
import se.lublin.humla.IHumlaService
import se.lublin.humla.model.IUser
import se.lublin.humla.util.HumlaObserver

/**
 * Created by andrew on 03/03/14.
 */
class UserCommentFragment : AbstractCommentFragment() {

    override fun requestComment(service: IHumlaService) {
        if (!service.isConnected()) {
            return
        }
        val observer = object : HumlaObserver() {
            override fun onUserStateUpdated(user: IUser?) {
                // NPE-exact: the original dereferenced user unconditionally.
                val updated = user ?: throw NullPointerException("user")
                if (updated.session == getSession() && updated.comment != null) {
                    loadComment(updated.comment)
                    service.unregisterObserver(this)
                }
            }
        }
        trackCommentObserver(service, observer)
        service.registerObserver(observer)
        service.HumlaSession().requestComment(getSession())
    }

    override fun editComment(service: IHumlaService, comment: String) {
        if (!service.isConnected()) {
            return
        }
        service.HumlaSession().setUserComment(getSession(), comment)
    }

    private fun getSession(): Int {
        return requireIntArgument(requireArguments(), ARG_SESSION)
    }

    override fun validateArguments(args: Bundle) {
        super.validateArguments(args)
        requireIntArgument(args, ARG_SESSION)
    }

    companion object {
        const val ARG_SESSION = "session"
    }
}
