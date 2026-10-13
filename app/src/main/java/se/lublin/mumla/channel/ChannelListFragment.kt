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

import android.content.Context
import android.graphics.PorterDuff
import android.os.Bundle
import android.os.RemoteException
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import se.lublin.humla.IHumlaService
import se.lublin.humla.model.IChannel
import se.lublin.humla.model.IUser
import se.lublin.humla.util.HumlaDisconnectedException
import se.lublin.humla.util.HumlaException
import se.lublin.humla.util.HumlaObserver
import se.lublin.humla.util.IHumlaObserver
import se.lublin.mumla.R
import se.lublin.mumla.Settings
import se.lublin.mumla.db.MumlaDatabase
import se.lublin.mumla.db.MumlaRoomDatabase
import se.lublin.mumla.util.HumlaServiceFragment

class ChannelListFragment : HumlaServiceFragment(), OnChannelClickListener, OnUserClickListener,
    Settings.OnSettingChangedListener {

    private val mServiceObserver: IHumlaObserver = object : HumlaObserver() {
        override fun onDisconnected(e: HumlaException?) {
            // NPE-exact: the original dereferenced both fields unconditionally.
            val adapter = mChannelListAdapter ?: throw NullPointerException("mChannelListAdapter")
            adapter.clearAvatarCache()
            val channelView = mChannelView ?: throw NullPointerException("mChannelView")
            channelView.adapter = null
        }

        override fun onUserJoinedChannel(user: IUser?, newChannel: IChannel?, oldChannel: IChannel?) {
            // NPE-exact: the original dereferenced the adapter unconditionally.
            val adapter = mChannelListAdapter ?: throw NullPointerException("mChannelListAdapter")
            adapter.updateChannels()
            adapter.notifyDataSetChanged()

            val service = getService()
            if (service == null || !service.isConnected()) {
                return
            }

            val selfSession: Int
            try {
                selfSession = service.HumlaSession().getSessionId()
            } catch (e: HumlaDisconnectedException) {
                Log.d(TAG, "exception in onUserJoinedChannel: $e")
                return
            } catch (e: IllegalStateException) {
                Log.d(TAG, "exception in onUserJoinedChannel: $e")
                return
            }

            // NPE-exact: the original dereferenced both user and newChannel unconditionally.
            val u = user ?: throw NullPointerException("user")
            val target = newChannel ?: throw NullPointerException("newChannel")
            if (u.session == selfSession) {
                scrollToChannel(target.id)
            }
        }

        override fun onChannelAdded(channel: IChannel?) {
            // NPE-exact: the original dereferenced the adapter unconditionally.
            val adapter = mChannelListAdapter ?: throw NullPointerException("mChannelListAdapter")
            adapter.updateChannels()
            adapter.notifyDataSetChanged()
        }

        override fun onChannelRemoved(channel: IChannel?) {
            // NPE-exact: the original dereferenced the adapter unconditionally.
            val adapter = mChannelListAdapter ?: throw NullPointerException("mChannelListAdapter")
            adapter.updateChannels()
            adapter.notifyDataSetChanged()
        }

        override fun onChannelStateUpdated(channel: IChannel?) {
            // NPE-exact: the original dereferenced the adapter unconditionally.
            val adapter = mChannelListAdapter ?: throw NullPointerException("mChannelListAdapter")
            adapter.updateChannels()
            adapter.notifyDataSetChanged()
        }

        override fun onUserConnected(user: IUser?) {
            // NPE-exact: the original dereferenced the adapter unconditionally.
            val adapter = mChannelListAdapter ?: throw NullPointerException("mChannelListAdapter")
            adapter.updateChannels()
            adapter.notifyDataSetChanged()
        }

        override fun onUserRemoved(user: IUser?, reason: String?) {
            // If we are the user being removed, don't update the channel list.
            // We won't be in a synchronized state.
            val service = getService()
            if (service == null || !service.isConnected()) {
                return
            }

            // NPE-exact: the original dereferenced the adapter unconditionally.
            val adapter = mChannelListAdapter ?: throw NullPointerException("mChannelListAdapter")
            if (user != null) {
                adapter.removeUser(user.session)
            }

            adapter.updateChannels()
            adapter.notifyDataSetChanged()
        }

        override fun onUserStateUpdated(user: IUser?) {
            // NPE-exact: the original dereferenced the adapter, view, and activity unconditionally.
            val adapter = mChannelListAdapter ?: throw NullPointerException("mChannelListAdapter")
            val channelView = mChannelView ?: throw NullPointerException("mChannelView")
            val host = activity ?: throw NullPointerException("activity")
            adapter.updateUserStates(user, channelView)
            host.supportInvalidateOptionsMenu() // Update self mute/deafen state
        }

        override fun onUserTalkStateUpdated(user: IUser?) {
            // NPE-exact: the original dereferenced the adapter and view unconditionally.
            val adapter = mChannelListAdapter ?: throw NullPointerException("mChannelListAdapter")
            val channelView = mChannelView ?: throw NullPointerException("mChannelView")
            adapter.updateUserStates(user, channelView)
        }
    }

    private var mChannelView: RecyclerView? = null
    private var mChannelListAdapter: ChannelListAdapter? = null
    private var mTargetProvider: ChatTargetProvider? = null
    private var mDatabase: MumlaDatabase? = null
    private var mActionMode: ActionMode? = null
    private var mSettings: Settings? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        try {
            mTargetProvider = parentFragment as ChatTargetProvider
        } catch (e: ClassCastException) {
            throw ClassCastException(parentFragment.toString() + " must implement ChatTargetProvider")
        }
        mDatabase = MumlaRoomDatabase.getInstance(context)
        val settings = Settings.getInstance(context)
        mSettings = settings
        settings.registerListener(this)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_channel_list, container, false)
        // NPE-exact: the original dereferenced the lookup unconditionally.
        val channelView = view.findViewById(R.id.channelUsers) as RecyclerView?
            ?: throw NullPointerException("channelUsers")
        mChannelView = channelView
        // NPE-exact: the original passed getActivity() straight through.
        channelView.layoutManager = LinearLayoutManager(activity ?: throw NullPointerException("activity"))

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // NPE-exact: the original dereferenced mChannelView unconditionally.
        val channelView = mChannelView ?: throw NullPointerException("mChannelView")
        registerForContextMenu(channelView)
    }

    override fun onDestroy() {
        super.onDestroy()
        // NPE-exact: the original dereferenced mSettings unconditionally.
        val settings = mSettings ?: throw NullPointerException("mSettings")
        settings.unregisterListener(this)
        val adapter = mChannelListAdapter
        if (adapter != null) {
            adapter.clearAvatarCache()
        }
    }

    override fun getServiceObserver(): IHumlaObserver {
        return mServiceObserver
    }

    override fun onServiceBound(service: IHumlaService) {
        try {
            if (mChannelListAdapter == null) {
                setupChannelList()
            } else {
                val adapter = mChannelListAdapter ?: throw NullPointerException("mChannelListAdapter")
                adapter.setService(service)
            }
        } catch (e: RemoteException) {
            e.printStackTrace()
        }
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        super.onPrepareOptionsMenu(menu)

        val muteItem = menu.findItem(R.id.menu_mute_button)
        val deafenItem = menu.findItem(R.id.menu_deafen_button)

        val service = getService()
        if (service != null && service.isConnected()) {
            val session = service.HumlaSession()
            // NPE-exact: the original dereferenced getActivity() unconditionally.
            val host = activity ?: throw NullPointerException("activity")

            // Color the action bar icons to the primary text color of the theme, TODO move this elsewhere
            val foregroundColor = host.theme.obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimaryInverse)).use { it.getColor(0, -1) }

            val self = session.getSessionUser()
            if (self != null) {
                // NPE-exact: the original dereferenced both items and icons unconditionally.
                val mute = muteItem ?: throw NullPointerException("muteItem")
                val deafen = deafenItem ?: throw NullPointerException("deafenItem")
                mute.setIcon(if (self.isSelfMuted) R.drawable.ic_action_microphone_muted else R.drawable.ic_action_microphone)
                deafen.setIcon(if (self.isSelfDeafened) R.drawable.ic_action_audio_muted else R.drawable.ic_action_audio)
                val muteIcon = mute.icon ?: throw NullPointerException("icon")
                val deafenIcon = deafen.icon ?: throw NullPointerException("icon")
                muteIcon.mutate().setColorFilter(foregroundColor, PorterDuff.Mode.MULTIPLY)
                deafenIcon.mutate().setColorFilter(foregroundColor, PorterDuff.Mode.MULTIPLY)
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.fragment_channel_list, menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val service = getService()
        if (service == null || !service.isConnected()) {
            return super.onOptionsItemSelected(item)
        }

        val session = service.HumlaSession()
        val itemId = item.itemId
        if (itemId == R.id.menu_mute_button) {
            val self = session.getSessionUser()
            if (self != null) {
                val muted = !self.isSelfMuted
                var deafened = self.isSelfDeafened
                deafened = deafened and muted // Undeafen if mute is off
                session.setSelfMuteDeafState(muted, deafened)
            }

            // NPE-exact: the original dereferenced getActivity() unconditionally.
            val host = activity ?: throw NullPointerException("activity")
            host.supportInvalidateOptionsMenu()
            return true
        } else if (itemId == R.id.menu_deafen_button) {
            val self = session.getSessionUser()
            if (self != null) {
                val deafened = !self.isSelfDeafened
                session.setSelfMuteDeafState(deafened, deafened)
            }

            // NPE-exact: the original dereferenced getActivity() unconditionally.
            val host = activity ?: throw NullPointerException("activity")
            host.supportInvalidateOptionsMenu()
            return true
        }

        return super.onOptionsItemSelected(item)
    }

    @Throws(RemoteException::class)
    private fun setupChannelList() {
        // NPE-exact: the original dereferenced every field unconditionally.
        val host = activity ?: throw NullPointerException("activity")
        val service = getService() ?: throw NullPointerException("service")
        val database = mDatabase ?: throw NullPointerException("mDatabase")
        val settings = mSettings ?: throw NullPointerException("mSettings")
        val channelView = mChannelView ?: throw NullPointerException("mChannelView")
        val adapter = ChannelListAdapter(
            host, service,
            database, childFragmentManager,
            isShowingPinnedChannels(), settings.shouldShowUserCount
        )
        mChannelListAdapter = adapter
        adapter.setOnChannelClickListener(this)
        adapter.setOnUserClickListener(this)
        channelView.adapter = adapter
        adapter.notifyDataSetChanged()
    }

    /**
     * Scrolls to the passed channel.
     */
    fun scrollToChannel(channelId: Int) {
        // NPE-exact: the original dereferenced both fields unconditionally.
        val adapter = mChannelListAdapter ?: throw NullPointerException("mChannelListAdapter")
        val channelView = mChannelView ?: throw NullPointerException("mChannelView")
        val channelPosition = adapter.getChannelPosition(channelId)
        channelView.scrollToPosition(channelPosition)
    }

    /**
     * Scrolls to the passed user.
     */
    fun scrollToUser(userId: Int) {
        // NPE-exact: the original dereferenced both fields unconditionally.
        val adapter = mChannelListAdapter ?: throw NullPointerException("mChannelListAdapter")
        val channelView = mChannelView ?: throw NullPointerException("mChannelView")
        val userPosition = adapter.getUserPosition(userId)
        channelView.scrollToPosition(userPosition)
    }

    private fun isShowingPinnedChannels(): Boolean {
        // NPE-exact: the original dereferenced getArguments() unconditionally.
        val args = arguments ?: throw NullPointerException("arguments")
        return args.getBoolean("pinned")
    }

    override fun onChannelClick(channel: IChannel) {
        // NPE-exact: the original dereferenced the provider unconditionally.
        val provider = mTargetProvider ?: throw NullPointerException("mTargetProvider")
        if (provider.getChatTarget() != null &&
            channel == provider.getChatTarget()?.getChannel() &&
            mActionMode != null
        ) {
            // Dismiss action mode if double pressed. FIXME: use list view selection instead?
            val mode = mActionMode ?: throw NullPointerException("mActionMode")
            mode.finish()
        } else {
            val cb = object : ChatTargetActionModeCallback(provider, ChatTargetProvider.ChatTarget(channel)) {
                override fun onDestroyActionMode(actionMode: ActionMode) {
                    super.onDestroyActionMode(actionMode)
                    mActionMode = null
                }
            }
            // NPE-exact: the original dereferenced getActivity() unconditionally.
            val host = activity as AppCompatActivity? ?: throw NullPointerException("activity")
            mActionMode = host.startSupportActionMode(cb)
        }
    }

    override fun onUserClick(user: IUser) {
        // NPE-exact: the original dereferenced the provider unconditionally.
        val provider = mTargetProvider ?: throw NullPointerException("mTargetProvider")
        if (provider.getChatTarget() != null &&
            user == provider.getChatTarget()?.getUser() &&
            mActionMode != null
        ) {
            // Dismiss action mode if double pressed. FIXME: use list view selection instead?
            val mode = mActionMode ?: throw NullPointerException("mActionMode")
            mode.finish()
        } else {
            val cb = object : ChatTargetActionModeCallback(provider, ChatTargetProvider.ChatTarget(user)) {
                override fun onDestroyActionMode(actionMode: ActionMode) {
                    super.onDestroyActionMode(actionMode)
                    mActionMode = null
                }
            }
            // NPE-exact: the original dereferenced getActivity() unconditionally.
            val host = activity as AppCompatActivity? ?: throw NullPointerException("activity")
            mActionMode = host.startSupportActionMode(cb)
        }
    }

    override fun onSettingChanged(key: String?) {
        // NPE-exact: the original dereferenced mSettings unconditionally.
        val settings = mSettings ?: throw NullPointerException("mSettings")
        if (Settings.PREF_SHOW_USER_COUNT == key && mChannelListAdapter != null) {
            val adapter = mChannelListAdapter ?: throw NullPointerException("mChannelListAdapter")
            adapter.setShowChannelUserCount(settings.shouldShowUserCount)
        }
    }

    companion object {
        private val TAG: String = ChannelListFragment::class.java.name
    }
}
