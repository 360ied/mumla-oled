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

package se.lublin.mumla.service

import android.content.Context
import android.graphics.Insets
import android.graphics.PixelFormat
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.view.WindowMetrics
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import se.lublin.humla.model.IChannel
import se.lublin.humla.model.IUser
import se.lublin.humla.util.HumlaException
import se.lublin.humla.util.HumlaObserver
import se.lublin.mumla.R
import se.lublin.mumla.Settings
import se.lublin.mumla.channel.ChannelAdapter

/**
 * A minimal onscreen floating voice HUD displaying active channel members.
 */
class MumlaOverlay(service: MumlaService) {
    private val mObserver: HumlaObserver = object : HumlaObserver() {
        override fun onUserTalkStateUpdated(user: IUser?) {
            val channelAdapter = mChannelAdapter
            if (channelAdapter != null && user?.channel != null &&
                user.channel == mService.getSessionChannel()
            ) {
                channelAdapter.updateUserState(user, mOverlayList)
            }
        }

        override fun onUserStateUpdated(user: IUser?) {
            val channelAdapter = mChannelAdapter
            if (channelAdapter != null && user?.channel != null &&
                user.channel == mService.getSessionChannel()
            ) {
                channelAdapter.updateUserState(user, mOverlayList)
            }
        }

        override fun onUserConnected(user: IUser?) {
            val channelAdapter = mChannelAdapter
            if (channelAdapter != null && user?.channel != null &&
                user.channel == mService.getSessionChannel()
            ) {
                channelAdapter.notifyDataSetChanged()
            }
        }

        override fun onUserRemoved(user: IUser?, reason: String?) {
            val channelAdapter = mChannelAdapter
            if (channelAdapter != null) {
                if (user != null) {
                    channelAdapter.removeUser(user.session)
                }
                channelAdapter.notifyDataSetChanged()
            }
        }

        override fun onUserJoinedChannel(user: IUser?, newChannel: IChannel?, oldChannel: IChannel?) {
            val selfSession: Int
            try {
                selfSession = mService.getSessionId()
            } catch (e: IllegalStateException) {
                Log.d(TAG, "exception in onUserJoinedChannel: $e")
                return
            }

            val channelAdapter = mChannelAdapter
            if (channelAdapter != null && user != null) {
                if (user.session == selfSession) {
                    channelAdapter.setChannel(mService.getSessionChannel())
                } else {
                    val sessionChannel = mService.getSessionChannel()
                    if (sessionChannel != null && (
                            (newChannel != null && newChannel.id == sessionChannel.id) ||
                                (oldChannel != null && oldChannel.id == sessionChannel.id)
                            )
                    ) {
                        channelAdapter.notifyDataSetChanged()
                    }
                }
            }
        }

        override fun onConnected() {
            val channelAdapter = mChannelAdapter
            if (channelAdapter != null) {
                channelAdapter.setChannel(mService.getSessionChannel())
            }
        }

        override fun onDisconnected(e: HumlaException?) {
            val channelAdapter = mChannelAdapter
            if (channelAdapter != null) {
                channelAdapter.setChannel(null)
                channelAdapter.clearAvatarCache()
            }
        }
    }

    private val mService: MumlaService = service
    private val mSettings: Settings = Settings.getInstance(service)
    private val mWindowManager: WindowManager
    private val mOverlayView: View
    private val mOverlayList: RecyclerView
    private val mOverlayParams: WindowManager.LayoutParams

    private var mChannelAdapter: ChannelAdapter? = null
    private var mShown = false

    private var mInitialTouchX: Float = 0f
    private var mInitialTouchY: Float = 0f
    private var mInitialParamX: Int = 0
    private var mInitialParamY: Int = 0

    init {
        mWindowManager = mService.getSystemService(Context.WINDOW_SERVICE) as WindowManager?
            ?: throw NullPointerException("WindowManager")
        mOverlayView = View.inflate(service, R.layout.overlay, null)
        mOverlayList = mOverlayView.findViewById(R.id.overlay_list)
        mOverlayList.layoutManager = LinearLayoutManager(service)
        mOverlayList.itemAnimator = null

        mOverlayView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (!mShown || mSettings.isOverlayPinned) {
                return@addOnLayoutChangeListener
            }
            val dm = mService.resources.displayMetrics
            val viewWidth = mOverlayView.width
            val viewHeight = mOverlayView.height
            if (viewWidth > 0 && viewHeight > 0) {
                val maxX = Math.max(0, dm.widthPixels - viewWidth)
                val maxY = Math.max(0, dm.heightPixels - viewHeight)
                val clampedX = Math.max(0, Math.min(mOverlayParams.x, maxX))
                val clampedY = Math.max(0, Math.min(mOverlayParams.y, maxY))
                if (clampedX != mOverlayParams.x || clampedY != mOverlayParams.y) {
                    mOverlayParams.x = clampedX
                    mOverlayParams.y = clampedY
                    try {
                        mWindowManager.updateViewLayout(mOverlayView, mOverlayParams)
                    } catch (e: IllegalArgumentException) {
                        Log.d(TAG, "exception updating overlay layout on layout change: $e")
                    }
                }
            }
        }

        // CCE-exact: the original cast to OverlayLayout unconditionally.
        val overlayLayout = mOverlayView as OverlayLayout
        val touchSlop = ViewConfiguration.get(service).scaledTouchSlop

        overlayLayout.setOnDispatchTouchEventListener(object : OverlayLayout.OnDispatchTouchEventListener {
            private var mIsDragging = false

            override fun onDispatchTouchEvent(event: MotionEvent): Boolean {
                if (mSettings.isOverlayPinned) {
                    return false
                }
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        mInitialParamX = mOverlayParams.x
                        mInitialParamY = mOverlayParams.y
                        mInitialTouchX = event.rawX
                        mInitialTouchY = event.rawY
                        mIsDragging = false
                        return false
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val deltaX = event.rawX - mInitialTouchX
                        val deltaY = event.rawY - mInitialTouchY
                        if (!mIsDragging && Math.hypot(deltaX.toDouble(), deltaY.toDouble()) > touchSlop) {
                            mIsDragging = true
                        }

                        if (mIsDragging) {
                            val newX = (mInitialParamX + deltaX).toInt()
                            val newY = (mInitialParamY + deltaY).toInt()

                            val dm = mService.resources.displayMetrics
                            val viewWidth = if (mOverlayView.width > 0) mOverlayView.width else (ESTIMATED_WIDTH_DP * dm.density).toInt()
                            val viewHeight = if (mOverlayView.height > 0) mOverlayView.height else (ESTIMATED_HEIGHT_DP * dm.density).toInt()
                            val maxX = Math.max(0, dm.widthPixels - viewWidth)
                            val maxY = Math.max(0, dm.heightPixels - viewHeight)

                            mOverlayParams.x = Math.max(0, Math.min(newX, maxX))
                            mOverlayParams.y = Math.max(0, Math.min(newY, maxY))
                            if (mShown) {
                                mWindowManager.updateViewLayout(mOverlayView, mOverlayParams)
                            }
                            return true
                        }
                        return false
                    }

                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (mIsDragging) {
                            savePosition()
                            mIsDragging = false
                            return true
                        }
                    }
                }
                return false
            }
        })

        mOverlayParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        mOverlayParams.windowAnimations = 0
        applyLayoutParameters()
    }

    private fun applyLayoutParameters() {
        val dm = mService.resources.displayMetrics
        if (mSettings.isOverlayPinned) {
            mOverlayParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            mOverlayParams.gravity = mSettings.overlayGravity
            val placement = mSettings.overlayPlacement
            // Single WindowMetrics query per layout pass; the snapshot is shared by
            // all margin resolvers below instead of re-querying per edge.
            val insets = systemBarInsets
            mOverlayParams.x = getSideMargin(dm, insets, placement)
            val isTop = Settings.OVERLAY_PLACEMENT_TOP_LEFT == placement ||
                Settings.OVERLAY_PLACEMENT_TOP_RIGHT == placement
            mOverlayParams.y = if (isTop) getTopMargin(dm, insets) else getBottomMargin(dm, insets)
        } else {
            mOverlayParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            mOverlayParams.gravity = Gravity.TOP or Gravity.LEFT
            restorePosition()
        }
    }

    private val systemBarInsets: Insets
        get() {
            val metrics: WindowMetrics = mWindowManager.currentWindowMetrics
            // Ignoring visibility (rather than getInsets) bakes hidden bars into the
            // pinned margins so the overlay never slides under a transient system bar
            // when bars reappear (e.g. swipe-in gesture navigation).
            return metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
        }

    private fun getSideMargin(dm: DisplayMetrics, insets: Insets, placement: String?): Int {
        val isLeft = Settings.OVERLAY_PLACEMENT_TOP_LEFT == placement ||
            Settings.OVERLAY_PLACEMENT_BOTTOM_LEFT == placement
        val sideInset = if (isLeft) insets.left else insets.right
        return resolveSideMarginPx(Math.max(0, sideInset), dm.density)
    }

    private fun getTopMargin(dm: DisplayMetrics, insets: Insets): Int {
        val insetTop = Math.max(0, insets.top)
        if (insetTop > 0) {
            return resolveEdgeMarginPx(insetTop, 0, DEFAULT_TOP_MARGIN_DP, dm.density)
        }
        var statusBarHeight = 0
        val resourceId = mService.resources.getIdentifier("status_bar_height", "dimen", "android")
        if (resourceId > 0) {
            statusBarHeight = mService.resources.getDimensionPixelSize(resourceId)
        }
        return resolveEdgeMarginPx(0, statusBarHeight, DEFAULT_TOP_MARGIN_DP, dm.density)
    }

    private fun getBottomMargin(dm: DisplayMetrics, insets: Insets): Int {
        val insetBottom = Math.max(0, insets.bottom)
        if (insetBottom > 0) {
            return resolveEdgeMarginPx(insetBottom, 0, DEFAULT_BOTTOM_MARGIN_DP, dm.density)
        }
        var navBarHeight = 0
        val resourceId = mService.resources.getIdentifier("navigation_bar_height", "dimen", "android")
        if (resourceId > 0) {
            navBarHeight = mService.resources.getDimensionPixelSize(resourceId)
        }
        return resolveEdgeMarginPx(0, navBarHeight, DEFAULT_BOTTOM_MARGIN_DP, dm.density)
    }

    private fun restorePosition() {
        val dm = mService.resources.displayMetrics
        val defaultX = (DEFAULT_POS_X_DP * dm.density).toInt()
        val defaultY = (DEFAULT_POS_Y_DP * dm.density).toInt()

        val savedX = mSettings.getOverlayPosX(defaultX)
        val savedY = mSettings.getOverlayPosY(defaultY)

        val maxX = Math.max(0, dm.widthPixels - (ESTIMATED_WIDTH_DP * dm.density).toInt())
        val maxY = Math.max(0, dm.heightPixels - (ESTIMATED_HEIGHT_DP * dm.density).toInt())

        mOverlayParams.x = Math.max(0, Math.min(savedX, maxX))
        mOverlayParams.y = Math.max(0, Math.min(savedY, maxY))
    }

    private fun savePosition() {
        if (mSettings.isOverlayPinned) {
            return
        }
        mSettings.setOverlayPosition(mOverlayParams.x, mOverlayParams.y)
    }

    fun isShown(): Boolean {
        return mShown
    }

    fun updatePosition() {
        applyLayoutParameters()
        if (mShown) {
            try {
                mWindowManager.updateViewLayout(mOverlayView, mOverlayParams)
            } catch (e: Exception) {
                Log.d(TAG, "exception updating overlay layout: $e")
            }
        }
    }

    fun show() {
        if (mShown) {
            return
        }
        applyLayoutParameters()
        val adapter = ChannelAdapter(mService, mService.getSessionChannel())
        mChannelAdapter = adapter
        mOverlayList.adapter = adapter
        mService.registerObserver(mObserver)
        try {
            mWindowManager.addView(mOverlayView, mOverlayParams)
            mShown = true
        } catch (e: Exception) {
            Log.e(TAG, "exception showing overlay: $e")
            mService.unregisterObserver(mObserver)
            mOverlayList.adapter = null
            mChannelAdapter = null
            mShown = false
        }
    }

    fun hide() {
        if (!mShown) {
            return
        }
        mShown = false
        mService.unregisterObserver(mObserver)
        mOverlayList.adapter = null
        val channelAdapter = mChannelAdapter
        if (channelAdapter != null) {
            channelAdapter.clearAvatarCache()
        }
        mChannelAdapter = null
        try {
            mWindowManager.removeView(mOverlayView)
        } catch (e: Exception) {
            Log.d(TAG, "exception removing overlay view: $e")
        }
    }

    fun setPushToTalkShown(showPtt: Boolean) {
        // No-op in minimal HUD mode
    }

    companion object {
        private val TAG: String = MumlaOverlay::class.java.name

        /**
         * Pure margin resolution, extracted for unit testing without a {@link WindowManager}.
         *
         * @param insetPx live system-bar inset for the edge, or 0 when unavailable
         * @param barHeightPx legacy resource height ({@code status_bar_height} /
         *     {@code navigation_bar_height}), or 0 when unknown
         * @param defaultDp hardcoded fallback when neither source yields a height
         * @param density display density
         *
         * Widened to public: Kotlin has no package visibility (was package-visible
         * static in Java for the same-package tests).
         */
        @JvmStatic
        fun resolveEdgeMarginPx(insetPx: Int, barHeightPx: Int, defaultDp: Int, density: Float): Int {
            if (insetPx > 0) {
                return insetPx + (EDGE_GUTTER_DP * density).toInt()
            }
            if (barHeightPx > 0) {
                return barHeightPx + (EDGE_GUTTER_DP * density).toInt()
            }
            return (defaultDp * density).toInt()
        }

        /**
         * Pure side-margin resolution: keeps the pinned overlay clear of side cutouts
         * and gesture bars on either edge instead of using a fixed offset.
         *
         * @param sideInsetPx live left/right inset for the pinned side, or 0
         * @param density display density
         *
         * Widened to public: Kotlin has no package visibility (was package-visible
         * static in Java for the same-package tests).
         */
        @JvmStatic
        fun resolveSideMarginPx(sideInsetPx: Int, density: Float): Int {
            if (sideInsetPx > 0) {
                return sideInsetPx + (EDGE_GUTTER_DP * density).toInt()
            }
            return (SIDE_MARGIN_DP * density).toInt()
        }

        /** Extra padding between the pinned overlay and the system bars. */
        private const val EDGE_GUTTER_DP = 8
        /** Horizontal offset of the pinned overlay from the screen edge. */
        private const val SIDE_MARGIN_DP = 16
        /** Fallback pinned top margin when no status-bar height is known. */
        private const val DEFAULT_TOP_MARGIN_DP = 40
        /** Fallback pinned bottom margin when no navigation-bar height is known. */
        private const val DEFAULT_BOTTOM_MARGIN_DP = 56
        /** Default floating-overlay position used when nothing is stored yet. */
        private const val DEFAULT_POS_X_DP = 24
        /** Default floating-overlay position used when nothing is stored yet. */
        private const val DEFAULT_POS_Y_DP = 80
        /** Size estimate used to clamp stored floating positions before layout. */
        private const val ESTIMATED_WIDTH_DP = 120
        /** Size estimate used to clamp stored floating positions before layout. */
        private const val ESTIMATED_HEIGHT_DP = 60
    }
}
