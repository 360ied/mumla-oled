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

import android.content.res.TypedArray
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentPagerAdapter
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewpager.widget.PagerTabStrip
import androidx.viewpager.widget.ViewPager
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import se.lublin.humla.model.TalkState
import se.lublin.mumla.R
import se.lublin.mumla.Settings
import se.lublin.mumla.container
import se.lublin.mumla.app.MumlaActivity
import se.lublin.mumla.app.MumlaViewModelFactory

/**
 * Class to encapsulate both a ChannelListFragment and ChannelChatFragment.
 * Created by andrew on 02/08/13.
 */
class ChannelFragment : Fragment() {

    private val viewModel: ChannelViewModel by viewModels {
        MumlaViewModelFactory(this)
    }

    private var mViewPager: ViewPager? = null
    private var mTabStrip: PagerTabStrip? = null
    private var mTalkButton: Button? = null
    private var mTalkView: View? = null

    private var mTargetPanel: View? = null
    private var mTargetPanelText: TextView? = null

    /** Menu-relevant state; invalidates the overflow menu only on change. */
    private var mMenuKey: Pair<Boolean, Boolean>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_channel, container, false)
        // Nullable: sw600dp/sw720dp layouts have no pager (tablet branch below).
        mViewPager = view.findViewById(R.id.channel_view_pager) as ViewPager?
        mTabStrip = view.findViewById(R.id.channel_tab_strip) as PagerTabStrip?
        val tabStrip = mTabStrip
        if (tabStrip != null) {
            val attrs = intArrayOf(android.R.attr.colorPrimary, android.R.attr.textColorPrimaryInverse)
            // NPE-exact: the original dereferenced getActivity() unconditionally.
            val host = activity ?: throw NullPointerException("activity")
            val a: TypedArray = host.obtainStyledAttributes(attrs)
            // .use{}: the pre-image leaked on a getColor throw; parity-safe
            // (same colors, just exception-safe recycling).
            val (titleStripBackground, titleStripColor) = a.use {
                Pair(it.getColor(0, -1), it.getColor(1, -1))
            }

            tabStrip.setTextColor(titleStripColor)
            tabStrip.tabIndicatorColor = titleStripColor
            tabStrip.setBackgroundColor(titleStripBackground)
            tabStrip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }

        mTalkView = view.findViewById(R.id.pushtotalk_view)
        // NPE-exact: the original dereferenced the button unconditionally.
        val talkButton = view.findViewById(R.id.pushtotalk) as Button?
            ?: throw NullPointerException("pushtotalk")
        mTalkButton = talkButton
        talkButton.setOnTouchListener { v, event ->
            // NPE-exact: the original dereferenced event unconditionally.
            val e = event ?: throw NullPointerException("event")
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    viewModel.talkDown()
                }
                MotionEvent.ACTION_UP -> {
                    v.isPressed = false
                    viewModel.talkUp()
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    viewModel.talkCancel()
                }
            }
            true
        }
        mTargetPanel = view.findViewById(R.id.target_panel)
        // NPE-exact: the original dereferenced the cancel view unconditionally.
        val targetPanelCancel = view.findViewById(R.id.target_panel_cancel) as ImageView?
            ?: throw NullPointerException("target_panel_cancel")
        targetPanelCancel.setOnClickListener { viewModel.clearVoiceTarget() }
        mTargetPanelText = view.findViewById(R.id.target_panel_warning) as TextView?
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { render(it) }
            }
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        if (mViewPager != null) { // Phone
            val pagerAdapter = ChannelFragmentPagerAdapter(childFragmentManager)
            val pager = mViewPager ?: throw NullPointerException("mViewPager")
            pager.adapter = pagerAdapter
        } else { // Tablet
            val listFragment = ChannelListFragment()
            val listArgs = Bundle()
            listArgs.putBoolean("pinned", isShowingPinnedChannels())
            listFragment.arguments = listArgs
            val chatFragment = ChannelChatFragment()

            childFragmentManager.beginTransaction()
                .replace(R.id.list_fragment, listFragment)
                .replace(R.id.chat_fragment, chatFragment)
                .commit()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)
        inflater.inflate(R.menu.channel_menu, menu)
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        super.onPrepareOptionsMenu(menu)
        val bluetoothItem = menu.findItem(R.id.menu_bluetooth_headset)
        if (bluetoothItem != null && activity != null) {
            val state = viewModel.state.value
            bluetoothItem.setVisible(state.bluetoothMenuVisible)
            bluetoothItem.setChecked(state.bluetoothHeadset)
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val itemId = item.itemId
        if (itemId == R.id.menu_bluetooth_headset) {
            val host = activity
            if (host is MumlaActivity) {
                // Thin alias of the Settings > Audio checkbox: flip the
                // requested state. No explicit retry; a failed link
                // retries by flipping off and on again.
                host.setBluetoothHeadset(
                    !host.container.settings.isBluetoothHeadset
                )
            }
            return true
        }
        // NPE-exact: the original passed getActivity() straight through.
        val host = activity ?: throw NullPointerException("activity")
        val settings = host.container.settings
        if (itemId == R.id.menu_input_voice) {
            settings.inputMethod = Settings.ARRAY_INPUT_METHOD_VOICE
            return true
        } else if (itemId == R.id.menu_input_ptt) {
            settings.inputMethod = Settings.ARRAY_INPUT_METHOD_PTT
            return true
        } else if (itemId == R.id.menu_input_continuous) {
            settings.inputMethod = Settings.ARRAY_INPUT_METHOD_CONTINUOUS
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onPause() {
        super.onPause()
        val talkButton = mTalkButton
        if (talkButton != null) {
            talkButton.isPressed = false
        }
        // A cancel always ends a held press, so leaving or rotating mid-press
        // never leaves transmit on. Toggle mode ignores it, as before.
        viewModel.talkCancel()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        val talkButton = mTalkButton
        if (talkButton != null) {
            talkButton.isActivated = false
            talkButton.isPressed = false
        }
        mViewPager = null
        mTabStrip = null
        mTalkButton = null
        mTalkView = null
        mTargetPanel = null
        mTargetPanelText = null
        mMenuKey = null
    }

    private fun render(state: ChannelUiState) {
        // The overflow checkmark mirrors the requested toggle and is gated on
        // the connection, so only those two need an invalidation.
        val menuKey = state.bluetoothMenuVisible to state.bluetoothHeadset
        if (menuKey != mMenuKey) {
            mMenuKey = menuKey
            activity?.supportInvalidateOptionsMenu()
        }
        val talkButton = mTalkButton
        if (talkButton != null) {
            talkButton.isActivated = state.connected && state.selfTalking
            val heightPx = calculateButtonHeightPx(
                state.pttButtonHeight,
                resources?.displayMetrics
            )
            val params = talkButton.layoutParams
            if (params != null && params.height != heightPx) {
                params.height = heightPx
                talkButton.layoutParams = params
            }
        }
        setTalkButtonHidden(!state.showPttButton)

        val targetPanel = mTargetPanel
        val targetPanelText = mTargetPanelText
        if (targetPanel != null && targetPanelText != null) {
            val targetName = state.whisperTargetName
            if (targetName != null) {
                targetPanel.visibility = View.VISIBLE
                targetPanelText.text = getString(R.string.shout_target, targetName)
            } else {
                targetPanel.visibility = View.GONE
            }
        }
    }

    private fun setTalkButtonHidden(hidden: Boolean) {
        val talkView = mTalkView
        if (talkView == null) {
            return
        }
        talkView.visibility = if (hidden) View.GONE else View.VISIBLE
        val talkButton = mTalkButton
        if (hidden && talkButton != null) {
            talkButton.isActivated = false
            talkButton.isPressed = false
        }
    }

    /**
     * @return true if the channel fragment is set to display only the user's pinned channels.
     */
    private fun isShowingPinnedChannels(): Boolean {
        val args = arguments
        return args != null && args.getBoolean("pinned")
    }

    private inner class ChannelFragmentPagerAdapter(fm: FragmentManager) : FragmentPagerAdapter(fm) {

        override fun getItem(i: Int): Fragment {
            val args = Bundle()
            if (i == 0) {
                val fragment = ChannelListFragment()
                args.putBoolean("pinned", isShowingPinnedChannels())
                fragment.arguments = args
                return fragment
            }
            val fragment = ChannelChatFragment()
            fragment.arguments = args
            return fragment
        }

        override fun getPageTitle(position: Int): CharSequence? {
            return when (position) {
                0 -> getString(R.string.channel).uppercase()
                1 -> getString(R.string.chat).uppercase()
                else -> null
            }
        }

        override fun getCount(): Int {
            return 2
        }
    }

    companion object {
        /**
         * Converts the configured PTT button height (in dp) to physical pixels using display metrics.
         *
         * Widened to public: Kotlin has no package visibility (was package-visible
         * static in Java for the same-package tests).
         */
        @JvmStatic
        fun calculateButtonHeightPx(heightDp: Int, metrics: android.util.DisplayMetrics?): Int {
            if (heightDp <= 0) {
                return heightDp
            }
            if (metrics == null) {
                return heightDp
            }
            val px = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, heightDp.toFloat(), metrics)
            if (px > 0) {
                return maxOf(1, px.roundToInt())
            }
            val density = if (metrics.density > 0) metrics.density else 1.0f
            return maxOf(1, (heightDp * density).roundToInt())
        }

        /**
         * Evaluates whether a talk state corresponds to an active transmission state.
         *
         * Widened to public: Kotlin has no package visibility (was package-visible
         * static in Java for the same-package tests).
         */
        @JvmStatic
        fun isTalkingState(state: TalkState?): Boolean {
            if (state == null) {
                return false
            }
            return when (state) {
                TalkState.TALKING, TalkState.SHOUTING, TalkState.WHISPERING -> true
                TalkState.PASSIVE -> false
            }
        }
    }
}
