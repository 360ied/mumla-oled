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
import android.util.Log
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
import androidx.viewpager.widget.PagerTabStrip
import androidx.viewpager.widget.ViewPager
import se.lublin.humla.HumlaService
import se.lublin.humla.IHumlaService
import se.lublin.humla.IHumlaSession
import se.lublin.humla.model.IUser
import se.lublin.humla.model.TalkState
import se.lublin.humla.model.WhisperTarget
import se.lublin.humla.util.HumlaDisconnectedException
import se.lublin.humla.util.HumlaObserver
import se.lublin.humla.util.IHumlaObserver
import se.lublin.humla.util.VoiceTargetMode
import se.lublin.mumla.R
import se.lublin.mumla.Settings
import se.lublin.mumla.app.MumlaActivity
import se.lublin.mumla.util.HumlaServiceFragment

/**
 * Class to encapsulate both a ChannelListFragment and ChannelChatFragment.
 * Created by andrew on 02/08/13.
 */
class ChannelFragment : HumlaServiceFragment(), Settings.OnSettingChangedListener, ChatTargetProvider {
    private var mSettings: Settings? = null

    private var mViewPager: ViewPager? = null
    private var mTabStrip: PagerTabStrip? = null
    private var mTalkButton: Button? = null
    private var mTalkView: View? = null

    private var mTargetPanel: View? = null
    private var mTargetPanelCancel: ImageView? = null
    private var mTargetPanelText: TextView? = null

    private var mChatTarget: ChatTargetProvider.ChatTarget? = null
    /** Chat target listeners, notified when the chat target is changed. */
    private val mChatTargetListeners = ArrayList<ChatTargetProvider.OnChatTargetSelectedListener>()

    private val mObserver: HumlaObserver = object : HumlaObserver() {
        override fun onUserTalkStateUpdated(user: IUser?) {
            val service = getService()
            if (service == null || !service.isConnected()) {
                return
            }
            val selfSession: Int
            try {
                selfSession = service.HumlaSession().getSessionId()
            } catch (e: HumlaDisconnectedException) {
                Log.d(TAG, "exception in onUserTalkStateUpdated: $e")
                return
            } catch (e: IllegalStateException) {
                Log.d(TAG, "exception in onUserTalkStateUpdated: $e")
                return
            }
            val talkButton = mTalkButton
            if (user != null && user.session == selfSession && talkButton != null) {
                // Manually set button activation state when we receive a talk state update.
                // This allows representation of talk state when using hot corners and PTT toggle.
                talkButton.isActivated = isTalkingState(user.talkState)
            }
        }

        override fun onUserStateUpdated(user: IUser?) {
            val service = getService()
            if (service == null || !service.isConnected()) {
                return
            }
            val selfSession: Int
            try {
                selfSession = service.HumlaSession().getSessionId()
            } catch (e: HumlaDisconnectedException) {
                Log.d(TAG, "exception in onUserStateUpdated: $e")
                return
            } catch (e: IllegalStateException) {
                Log.d(TAG, "exception in onUserStateUpdated: $e")
                return
            }
            if (user != null && user.session == selfSession) {
                configureInput()
            }
        }

        override fun onVoiceTargetChanged(mode: VoiceTargetMode?) {
            configureTargetPanel()
        }
    }

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
                    val service = getService()
                    if (service != null) {
                        service.onTalkKeyDown()
                    }
                }
                MotionEvent.ACTION_UP -> {
                    v.isPressed = false
                    val service = getService()
                    if (service != null) {
                        service.onTalkKeyUp()
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    val service = getService()
                    if (service != null) {
                        service.onTalkKeyCancel()
                    }
                }
            }
            true
        }
        mTargetPanel = view.findViewById(R.id.target_panel)
        // NPE-exact: the original dereferenced the cancel view unconditionally.
        val targetPanelCancel = view.findViewById(R.id.target_panel_cancel) as ImageView?
            ?: throw NullPointerException("target_panel_cancel")
        mTargetPanelCancel = targetPanelCancel
        targetPanelCancel.setOnClickListener {
            val service = getService()
            if (service == null || !service.isConnected()) {
                return@setOnClickListener
            }

            val session: IHumlaSession
            try {
                session = service.HumlaSession()
            } catch (e: HumlaDisconnectedException) {
                return@setOnClickListener
            } catch (e: IllegalStateException) {
                return@setOnClickListener
            }
            if (session.getVoiceTargetMode() == VoiceTargetMode.WHISPER) {
                val target = session.getVoiceTargetId()
                session.setVoiceTargetId(0.toByte())
                session.unregisterWhisperTarget(target)
            }
        }
        mTargetPanelText = view.findViewById(R.id.target_panel_warning) as TextView?
        configureInput()
        return view
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)

        // NPE-exact: the original passed getActivity() straight through.
        val host = activity ?: throw NullPointerException("activity")
        val settings = Settings.getInstance(host)
        mSettings = settings
        settings.registerListener(this)

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
            // Checked reflects the requested toggle, mirroring Settings >
            // Audio: a failed or in-flight bring-up still reads checked
            // until the user flips it off. Link state is signaled by
            // toasts, never by this checkmark.
            // NPE-exact: the original dereferenced getActivity() unconditionally.
            val host = activity ?: throw NullPointerException("activity")
            val requested = Settings.getInstance(host).isBluetoothHeadset
            val service = getService()
            bluetoothItem.setVisible(service != null && service.isConnected())
            bluetoothItem.setChecked(requested)
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
                    !Settings.getInstance(host).isBluetoothHeadset
                )
            }
            return true
        }
        // NPE-exact: the original passed getActivity() straight through.
        val host = activity ?: throw NullPointerException("activity")
        val settings = Settings.getInstance(host)
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
        val service = getService()
        if (service != null) {
            service.onTalkKeyCancel()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        val talkButton = mTalkButton
        if (talkButton != null) {
            talkButton.isActivated = false
            talkButton.isPressed = false
        }
        // NPE-exact: the original dereferenced mSettings unconditionally.
        val settings = mSettings ?: throw NullPointerException("mSettings")
        settings.unregisterListener(this)
        // Release the detached hierarchy: the service observer outlives the
        // view (until onDestroy), and its callbacks null-guard these fields.
        mViewPager = null
        mTabStrip = null
        mTalkButton = null
        mTalkView = null
        mTargetPanel = null
        mTargetPanelCancel = null
        mTargetPanelText = null
    }

    override fun getServiceObserver(): IHumlaObserver {
        return mObserver
    }

    override fun onServiceBound(service: IHumlaService) {
        super.onServiceBound(service)
        if (service.getConnectionState() == HumlaService.ConnectionState.CONNECTED) {
            configureTargetPanel()
            configureInput()
        }
    }

    private fun configureTargetPanel() {
        val targetPanel = mTargetPanel
        val targetPanelText = mTargetPanelText
        if (!isAdded || activity == null || targetPanel == null || targetPanelText == null) {
            return
        }
        val service = getService()
        if (service == null || !service.isConnected()) {
            return
        }

        val session: IHumlaSession
        try {
            session = service.HumlaSession()
        } catch (e: HumlaDisconnectedException) {
            return
        } catch (e: IllegalStateException) {
            return
        }
        val mode = session.getVoiceTargetMode()
        // Null when WHISPER points at an unregistered id: hide rather than
        // dereference (pedantic review).
        val target: WhisperTarget? =
            if (mode == VoiceTargetMode.WHISPER) session.getWhisperTarget() else null
        if (target != null) {
            targetPanel.visibility = View.VISIBLE
            targetPanelText.text = getString(R.string.shout_target, target.name)
        } else {
            targetPanel.visibility = View.GONE
        }
    }

    /**
     * @return true if the channel fragment is set to display only the user's pinned channels.
     */
    private fun isShowingPinnedChannels(): Boolean {
        val args = arguments
        return args != null && args.getBoolean("pinned")
    }

    /**
     * Configures the fragment in accordance with the user's interface preferences.
     */
    private fun configureInput() {
        val talkButton = mTalkButton
        if (!isAdded || activity == null || talkButton == null) {
            return
        }
        // NPE-exact: the original passed getActivity() straight through.
        val host = activity ?: throw NullPointerException("activity")
        val settings = Settings.getInstance(host)

        val heightDp = settings.pttButtonHeight
        val heightPx = calculateButtonHeightPx(
            heightDp,
            resources?.displayMetrics
        )

        val params = talkButton.layoutParams
        if (params != null) {
            params.height = heightPx
            talkButton.layoutParams = params
        }

        var muted = false
        val service = getService()
        if (service != null && service.isConnected()) {
            var self: IUser? = null
            try {
                val session = service.HumlaSession()
                talkButton.isActivated = session.isTalking()
                self = session.getSessionUser()
            } catch (e: HumlaDisconnectedException) {
                Log.d(TAG, "exception in configureInput: $e")
            } catch (e: IllegalStateException) {
                Log.d(TAG, "exception in configureInput: $e")
            }
            muted = self == null || self.isMuted || self.isSuppressed || self.isSelfMuted
        } else {
            talkButton.isActivated = false
        }
        val showPttButton =
            !muted &&
            settings.isPushToTalkButtonShown &&
            settings.inputMethod == Settings.ARRAY_INPUT_METHOD_PTT
        setTalkButtonHidden(!showPttButton)
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

    override fun onSettingChanged(key: String?) {
        if (Settings.PREF_INPUT_METHOD == key ||
            Settings.PREF_PUSH_BUTTON_HIDE_KEY == key ||
            Settings.PREF_PTT_BUTTON_HEIGHT == key
        ) {
            configureInput()
        }
        if (Settings.PREF_BLUETOOTH_HEADSET == key && isAdded && activity is MumlaActivity) {
            // Requested-state alias: a Settings flip must refresh the
            // overflow checkmark without waiting for an SCO callback.
            (activity as MumlaActivity).supportInvalidateOptionsMenu()
        }
    }

    override fun getChatTarget(): ChatTargetProvider.ChatTarget? {
        return mChatTarget
    }

    override fun setChatTarget(target: ChatTargetProvider.ChatTarget?) {
        mChatTarget = target
        for (listener in mChatTargetListeners) {
            listener.onChatTargetSelected(target)
        }
    }

    override fun registerChatTargetListener(listener: ChatTargetProvider.OnChatTargetSelectedListener) {
        mChatTargetListeners.add(listener)
    }

    override fun unregisterChatTargetListener(listener: ChatTargetProvider.OnChatTargetSelectedListener) {
        mChatTargetListeners.remove(listener)
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
        private val TAG: String = ChannelFragment::class.java.name

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
                return Math.max(1, Math.round(px))
            }
            val density = if (metrics.density > 0) metrics.density else 1.0f
            return Math.max(1, Math.round(heightDp * density))
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
