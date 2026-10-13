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

package se.lublin.mumla.channel

import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.view.ActionMode
import se.lublin.mumla.R

/**
 * A callback that sets the active chat target when activated, and resets when destroyed (usually
 * to the user's current channel).
 * Created by andrew on 26/06/14.
 */
// Open: ChannelListFragment mounts an anonymous subclass clearing mActionMode.
open class ChatTargetActionModeCallback(provider: ChatTargetProvider, target: ChatTargetProvider.ChatTarget) :
    ActionMode.Callback {
    private val mProvider: ChatTargetProvider = provider
    private val mChatTarget: ChatTargetProvider.ChatTarget = target

    override fun onCreateActionMode(actionMode: ActionMode, menu: Menu): Boolean {
        actionMode.title = mChatTarget.getName()
        actionMode.setSubtitle(R.string.current_chat_target)
        mProvider.setChatTarget(getChatTarget())
        return true
    }

    override fun onPrepareActionMode(actionMode: ActionMode, menu: Menu): Boolean {
        return false
    }

    override fun onActionItemClicked(actionMode: ActionMode, menuItem: MenuItem): Boolean {
        return false
    }

    override fun onDestroyActionMode(actionMode: ActionMode) {
        mProvider.setChatTarget(null)
    }

    fun getChatTarget(): ChatTargetProvider.ChatTarget {
        return mChatTarget
    }
}
