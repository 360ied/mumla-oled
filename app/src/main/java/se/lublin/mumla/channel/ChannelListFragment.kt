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

import android.graphics.PorterDuff
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import se.lublin.humla.state.ChannelInfo
import se.lublin.humla.state.UserInfo
import se.lublin.mumla.R
import se.lublin.mumla.app.MumlaViewModelFactory

class ChannelListFragment : Fragment() {

    private val viewModel: ChannelListViewModel by viewModels {
        MumlaViewModelFactory(this)
    }

    /** The shared chat target owner; the parent screen builds it once. */
    private val channelViewModel: ChannelViewModel by viewModels(
        ownerProducer = { requireParentFragment() },
    ) {
        MumlaViewModelFactory(requireParentFragment())
    }

    private var mChannelView: RecyclerView? = null
    private var mChannelListAdapter: ChannelListAdapter? = null
    private var mActionMode: ActionMode? = null
    private var mSelfState: Pair<Boolean, Boolean>? = null
    private var mLastSelfChannel: Int? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_channel_list, container, false)
        val channelView = view.findViewById<RecyclerView>(R.id.channelUsers)
            ?: throw NullPointerException("channelUsers")
        mChannelView = channelView
        channelView.layoutManager = LinearLayoutManager(requireActivity())
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val channelView = mChannelView ?: throw NullPointerException("mChannelView")
        registerForContextMenu(channelView)

        val adapter = ChannelListAdapter(
            context = requireContext(),
            showPinnedOnly = isShowingPinnedChannels(),
            onChannelClick = ::onChannelClick,
            onUserClick = ::onUserClick,
            onChannelJoin = { viewModel.joinChannel(it.id) },
            onChannelMenu = { channel, anchor ->
                ChannelMenu(requireContext(), channel, viewModel, childFragmentManager)
                    .showPopup(anchor)
            },
            onUserMenu = { user, anchor ->
                UserMenu(requireContext(), user, viewModel, childFragmentManager)
                    .showPopup(anchor)
            },
        )
        mChannelListAdapter = adapter
        channelView.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.state.collect { state ->
                        adapter.submit(state)
                        renderSelfChannel(state)
                        renderMenuIconState(state)
                    }
                }
            }
        }
    }

    /** Scrolls to the self user's channel when it changes, as the old callback did. */
    private fun renderSelfChannel(state: ChannelListUiState) {
        val selfChannel = state.server.self?.channelId
        if (state.connected && selfChannel != mLastSelfChannel) {
            mLastSelfChannel = selfChannel
            if (selfChannel != null) {
                scrollToChannel(selfChannel)
            }
        }
        if (!state.connected) {
            mLastSelfChannel = null
        }
    }

    /** The mute/deafen items repaint only when the self flags change. */
    private fun renderMenuIconState(state: ChannelListUiState) {
        val self = state.server.self ?: return
        val key = self.selfMuted to self.selfDeafened
        if (key != mSelfState) {
            mSelfState = key
            activity?.supportInvalidateOptionsMenu()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mChannelListAdapter?.clearAvatarCache()
        mChannelListAdapter = null
        mChannelView = null
        mActionMode = null
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        super.onPrepareOptionsMenu(menu)

        val muteItem = menu.findItem(R.id.menu_mute_button)
        val deafenItem = menu.findItem(R.id.menu_deafen_button)

        if (!viewModel.state.value.connected) {
            return
        }
        val self = viewModel.state.value.server.self ?: return
        // NPE-exact: the original dereferenced getActivity() unconditionally.
        val host = requireActivity()

        // Color the action bar icons to the primary text color of the theme, TODO move this elsewhere
        val foregroundColor = host.theme
            .obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimaryInverse))
            .use { it.getColor(0, -1) }

        // NPE-exact: the original dereferenced both items and icons unconditionally.
        val mute = muteItem ?: throw NullPointerException("muteItem")
        val deafen = deafenItem ?: throw NullPointerException("deafenItem")
        mute.setIcon(
            if (self.selfMuted) R.drawable.ic_action_microphone_muted
            else R.drawable.ic_action_microphone
        )
        deafen.setIcon(
            if (self.selfDeafened) R.drawable.ic_action_audio_muted
            else R.drawable.ic_action_audio
        )
        val muteIcon = mute.icon ?: throw NullPointerException("icon")
        val deafenIcon = deafen.icon ?: throw NullPointerException("icon")
        muteIcon.mutate().setColorFilter(foregroundColor, PorterDuff.Mode.MULTIPLY)
        deafenIcon.mutate().setColorFilter(foregroundColor, PorterDuff.Mode.MULTIPLY)
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.fragment_channel_list, menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (!viewModel.state.value.connected) {
            return super.onOptionsItemSelected(item)
        }
        val itemId = item.itemId
        if (itemId == R.id.menu_mute_button) {
            viewModel.toggleSelfMute()
            return true
        } else if (itemId == R.id.menu_deafen_button) {
            viewModel.toggleSelfDeafen()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    /**
     * Scrolls to the passed channel.
     */
    fun scrollToChannel(channelId: Int) {
        val adapter = mChannelListAdapter ?: return
        val channelView = mChannelView ?: return
        val channelPosition = adapter.getChannelPosition(channelId)
        if (channelPosition >= 0) {
            channelView.scrollToPosition(channelPosition)
        }
    }

    /**
     * Scrolls to the passed user.
     */
    fun scrollToUser(userId: Int) {
        val adapter = mChannelListAdapter ?: return
        val channelView = mChannelView ?: return
        val userPosition = adapter.getUserPosition(userId)
        if (userPosition >= 0) {
            channelView.scrollToPosition(userPosition)
        }
    }

    private fun isShowingPinnedChannels(): Boolean {
        val args = arguments ?: return false
        return args.getBoolean("pinned")
    }

    private fun onChannelClick(channel: ChannelInfo) {
        val target = ChatTarget.channel(channel.id)
        if (channelViewModel.chatTarget.value == target && mActionMode != null) {
            // Dismiss action mode if double pressed. FIXME: use list view selection instead?
            mActionMode?.finish()
        } else {
            val cb = object : ChatTargetActionModeCallback(
                target,
                channel.name,
                channelViewModel::setChatTarget,
            ) {
                override fun onDestroyActionMode(actionMode: ActionMode) {
                    super.onDestroyActionMode(actionMode)
                    mActionMode = null
                }
            }
            val host = requireActivity() as AppCompatActivity
            mActionMode = host.startSupportActionMode(cb)
        }
    }

    private fun onUserClick(user: UserInfo) {
        val target = ChatTarget.user(user.session)
        if (channelViewModel.chatTarget.value == target && mActionMode != null) {
            // Dismiss action mode if double pressed. FIXME: use list view selection instead?
            mActionMode?.finish()
        } else {
            val cb = object : ChatTargetActionModeCallback(
                target,
                user.name,
                channelViewModel::setChatTarget,
            ) {
                override fun onDestroyActionMode(actionMode: ActionMode) {
                    super.onDestroyActionMode(actionMode)
                    mActionMode = null
                }
            }
            val host = requireActivity() as AppCompatActivity
            mActionMode = host.startSupportActionMode(cb)
        }
    }
}
